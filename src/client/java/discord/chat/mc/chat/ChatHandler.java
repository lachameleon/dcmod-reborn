package discord.chat.mc.chat;

import discord.chat.mc.DiscordChatIntegration;
import discord.chat.mc.config.ModConfig;
import discord.chat.mc.relay.RelayService;
import discord.chat.mc.websocket.DiscordWebSocketServer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class ChatHandler {
    private static ChatHandler instance;
    
    private final AtomicBoolean isSendingFromDiscord = new AtomicBoolean(false);
    private final ConcurrentHashMap<String, Boolean> processedMessageIds = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> sentFromDiscord = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> suppressedIncomingMessages = new ConcurrentHashMap<>();
    private static final long SENT_FROM_DISCORD_WINDOW_MS = 3000;
    private static final long SUPPRESSED_MESSAGE_WINDOW_MS = 5000;
    private static final long SERVER_ONLY_MESSAGE_WINDOW_MS = 8000;
    private static final long RATE_LIMIT_WINDOW_MS = 60_000;
    private static final long RATE_LIMIT_NOTICE_COOLDOWN_MS = 5000;
    private static final String SEND_CHAT_PREFIX = "/send";
    private final ConcurrentLinkedQueue<DiscordWebSocketServer.ChatMessage> tickSyncQueue = new ConcurrentLinkedQueue<>();
    private final Deque<Long> discordSendHistory = new ArrayDeque<>();
    private final Object rateLimitLock = new Object();
    private final ConcurrentHashMap<String, Long> serverOnlyOutgoingMessages = new ConcurrentHashMap<>();
    private final AtomicInteger allowedServerChatPackets = new AtomicInteger(0);
    private final AtomicInteger pendingServerOnlyEchoSkips = new AtomicInteger(0);
    private volatile long pendingServerOnlyEchoExpiresAtMs = 0;
    private volatile long lastRateLimitNoticeMs = 0;
    
    private volatile String lastSyncGroup = "none";
    private volatile long lastTargetTick = -1;
    private volatile long lastExecutionTick = -1;
    private volatile long lastReceiveTime = -1;
    private volatile long lastExecutionTime = -1;
    private boolean tickListenerRegistered = false;
    
    private final ExecutorService messageProcessor = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "Discord-Message-Processor");
        t.setDaemon(true);
        return t;
    });
    
    private final ExecutorService discordForwardExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Discord-Forward-Processor");
        t.setDaemon(true);
        return t;
    });
    
    public static ChatHandler getInstance() {
        if (instance == null) {
            instance = new ChatHandler();
            instance.messageProcessor.execute(() -> {});
            instance.registerTickListener();
        }
        return instance;
    }
    
    private void registerTickListener() {
        if (tickListenerRegistered) return;
        
        ClientTickEvents.END_CLIENT_TICK.register(minecraftClient -> {
            if (!tickSyncQueue.isEmpty() && minecraftClient.level != null) {
                long currentTick = minecraftClient.level.getGameTime();
                
                java.util.List<DiscordWebSocketServer.ChatMessage> readyMessages = new java.util.ArrayList<>();
                java.util.List<DiscordWebSocketServer.ChatMessage> pendingMessages = new java.util.ArrayList<>();
                
                DiscordWebSocketServer.ChatMessage message;
                while ((message = tickSyncQueue.poll()) != null) {
                    if (message.targetTick < 0 || currentTick >= message.targetTick) {
                        readyMessages.add(message);
                    } else {
                        pendingMessages.add(message);
                    }
                }
                
                tickSyncQueue.addAll(pendingMessages);
                
                for (DiscordWebSocketServer.ChatMessage readyMsg : readyMessages) {
                    if (readyMsg.targetTick >= 0) {
                        lastExecutionTick = currentTick;
                        lastExecutionTime = System.currentTimeMillis();
                    }
                    executeMessageImmediately(readyMsg);
                }
            }
        });
        
        tickListenerRegistered = true;
    }
    
    public String getLastSyncGroup() { return lastSyncGroup; }
    
    public void setLastSyncGroup(String syncGroup) {
        if (syncGroup != null && !syncGroup.isEmpty()) {
            this.lastSyncGroup = syncGroup;
        }
    }
    
    public long[] getLastExecutionInfo() {
        if (lastExecutionTime < 0) return null;
        return new long[] { lastTargetTick, lastExecutionTick, lastReceiveTime, lastExecutionTime };
    }
    
    public void handleDiscordMessage(DiscordWebSocketServer.ChatMessage message) {
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.player == null || client.player.connection == null) return;
        
        messageProcessor.execute(() -> {
            try {
                if (message.messageId != null && !message.messageId.isEmpty()) {
                    if (processedMessageIds.putIfAbsent(message.messageId, Boolean.TRUE) != null) return;
                    if (processedMessageIds.size() > 2000) processedMessageIds.clear();
                    }
                
                if (message.syncGroup != null && !message.syncGroup.isEmpty()) {
                    lastSyncGroup = message.syncGroup;
                }
                
                if (message.targetTick >= 0) {
                    lastReceiveTime = System.currentTimeMillis();
                    lastTargetTick = message.targetTick;
                    tickSyncQueue.add(message);
                    return;
                }
                
                if (message.tickSync) {
                    tickSyncQueue.add(message);
                    return;
                }
                
                executeMessageImmediately(message);
            } catch (Exception e) {
                DiscordChatIntegration.LOGGER.error("Error processing Discord message: {}", e.getMessage());
            }
        });
    }
    
    private void executeMessageImmediately(DiscordWebSocketServer.ChatMessage message) {
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.player == null || client.player.connection == null) return;
        
        if (!isSendingFromDiscord.compareAndSet(false, true)) {
            DiscordChatIntegration.LOGGER.debug("Concurrent message execution");
        }
        
        try {
            OutboundMessage outboundMessage = parseOutboundMessage(message.content);
            if (outboundMessage == null) {
                isSendingFromDiscord.set(false);
                return;
            }
            
            int maxPerMinute = ModConfig.getInstance().getMaxDiscordMessagesPerMinute();
            if (!tryAcquireDiscordSendSlot(maxPerMinute)) {
                isSendingFromDiscord.set(false);
                notifyRateLimitReached(maxPerMinute);
                DiscordChatIntegration.LOGGER.debug(
                        "Dropped Discord message due to rate limit ({} per minute): {}",
                        maxPerMinute,
                        outboundMessage.content()
                );
                return;
            }
            
            long now = System.currentTimeMillis();
            String playerName = getPlayerName(client);
            if (playerName != null) {
                sentFromDiscord.put("<" + playerName + "> " + outboundMessage.echoKey(), now);
            }
            sentFromDiscord.put(outboundMessage.echoKey(), now);
            
            String originalNormalized = normalizeMessageKey(message.content);
            if (!originalNormalized.isEmpty() && !originalNormalized.equals(outboundMessage.echoKey())) {
                sentFromDiscord.put(originalNormalized, now);
            }
            
            if (sentFromDiscord.size() > 100) {
                long cutoff = now - SENT_FROM_DISCORD_WINDOW_MS;
                sentFromDiscord.entrySet().removeIf(entry -> entry.getValue() < cutoff);
            }
            
            client.execute(() -> {
                try {
                    if (outboundMessage.isCommand()) {
                        client.player.connection.sendCommand(outboundMessage.content());
                    } else {
                        allowNextServerChatPacket();
                        client.player.connection.sendChat(outboundMessage.content());
                    }
                } catch (Exception e) {
                    DiscordChatIntegration.LOGGER.error("Error sending to chat: {}", e.getMessage());
                } finally {
                    messageProcessor.execute(() -> {
                        try { Thread.sleep(100); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                        isSendingFromDiscord.set(false);
                    });
                }
            });
        } catch (Exception e) {
            DiscordChatIntegration.LOGGER.error("Error executing message: {}", e.getMessage());
            isSendingFromDiscord.set(false);
        }
    }
    
    public int getDiscordRateLimitUsage() {
        synchronized (rateLimitLock) {
            pruneRateLimitHistoryLocked(System.currentTimeMillis());
            return discordSendHistory.size();
        }
    }
    
    private OutboundMessage parseOutboundMessage(String rawContent) {
        String normalized = normalizeMessageKey(rawContent);
        if (normalized.isEmpty()) return null;
        
        if (isSendChatCommand(normalized)) {
            String chatMessage = normalizeMessageKey(normalized.substring(SEND_CHAT_PREFIX.length()));
            if (chatMessage.isEmpty()) return null;
            return new OutboundMessage(chatMessage, false, chatMessage);
        }
        
        if (normalized.startsWith("/")) {
            String command = normalizeMessageKey(normalized.substring(1));
            if (command.isEmpty()) return null;
            return new OutboundMessage(command, true, normalized);
        }
        
        return new OutboundMessage(normalized, false, normalized);
    }
    
    private boolean isSendChatCommand(String normalizedContent) {
        if (!normalizedContent.regionMatches(true, 0, SEND_CHAT_PREFIX, 0, SEND_CHAT_PREFIX.length())) {
            return false;
        }
        return normalizedContent.length() == SEND_CHAT_PREFIX.length() ||
                Character.isWhitespace(normalizedContent.charAt(SEND_CHAT_PREFIX.length()));
    }
    
    private boolean tryAcquireDiscordSendSlot(int maxPerMinute) {
        if (maxPerMinute <= 0) return false;
        
        long now = System.currentTimeMillis();
        synchronized (rateLimitLock) {
            pruneRateLimitHistoryLocked(now);
            if (discordSendHistory.size() >= maxPerMinute) return false;
            discordSendHistory.addLast(now);
            return true;
        }
    }
    
    private void pruneRateLimitHistoryLocked(long nowMs) {
        long cutoff = nowMs - RATE_LIMIT_WINDOW_MS;
        while (!discordSendHistory.isEmpty()) {
            Long timestamp = discordSendHistory.peekFirst();
            if (timestamp == null || timestamp >= cutoff) break;
            discordSendHistory.pollFirst();
        }
    }
    
    private void notifyRateLimitReached(int maxPerMinute) {
        long now = System.currentTimeMillis();
        synchronized (rateLimitLock) {
            if ((now - lastRateLimitNoticeMs) < RATE_LIMIT_NOTICE_COOLDOWN_MS) {
                return;
            }
            lastRateLimitNoticeMs = now;
        }
        
        Minecraft client = Minecraft.getInstance();
        if (client == null) return;
        
        client.execute(() -> {
            if (client.player != null) {
                client.player.displayClientMessage(
                        Component.literal(
                                String.format("§6[Discord Chat] §cRate limit reached (§f%d/min§c). Message dropped.", maxPerMinute)
                        ),
                        false
                );
            }
        });
    }
    
    public boolean consumeServerChatPacketBypass() {
        while (true) {
            int current = allowedServerChatPackets.get();
            if (current <= 0) return false;
            if (allowedServerChatPackets.compareAndSet(current, current - 1)) {
                return true;
            }
        }
    }
    
    public boolean isLocalChatToDiscordMode() {
        return ModConfig.getInstance().isLocalChatToDiscord();
    }
    
    public boolean toggleLocalChatMode() {
        ModConfig config = ModConfig.getInstance();
        boolean next = !config.isLocalChatToDiscord();
        config.setLocalChatToDiscord(next);
        config.save();
        return next;
    }
    
    public void relayLocalChatOnly(String message) {
        String normalizedMessage = normalizeMessageKey(message);
        if (normalizedMessage.isEmpty()) return;
        
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.player == null) return;
        
        String playerName = client.player.getName().getString();
        String playerUuid = client.player.getUUID() != null ? client.player.getUUID().toString() : null;
        sendToDiscordForLogging(playerName, playerUuid, null, normalizedMessage);
        
        client.player.displayClientMessage(
                Component.literal("§8[DCI Relay] §9[Discord] §f<" + playerName + "> §7" + normalizedMessage),
                false
        );
    }
    
    public void markLocalServerOnlyOutgoingMessage(String message) {
        String normalizedMessage = normalizeMessageKey(message);
        if (normalizedMessage.isEmpty()) return;
        
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.player == null) return;
        
        markServerOnlyOutgoingMessage(client.player.getName().getString(), normalizedMessage);
    }
    
    public boolean sendChatToServerOnly(String message) {
        String normalizedMessage = normalizeMessageKey(message);
        if (normalizedMessage.isEmpty()) return false;
        
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.player == null || client.player.connection == null) return false;
        
        markServerOnlyOutgoingMessage(client.player.getName().getString(), normalizedMessage);
        markPendingServerOnlyEchoSkip();
        client.execute(() -> {
            allowNextServerChatPacket();
            client.player.connection.sendChat(normalizedMessage);
        });
        return true;
    }
    
    public void handleIncomingMinecraftMessage(String playerName, String message) {
        handleIncomingMinecraftMessage(playerName, message, null, null);
    }
    
    public void handleIncomingMinecraftMessage(String playerName, String message, String playerUuid) {
        handleIncomingMinecraftMessage(playerName, message, playerUuid, null);
    }
    
    public void handleIncomingMinecraftMessage(String playerName, String message, String playerUuid, String skinUrl) {
        String normalizedMessage = normalizeMessageKey(message);
        if (normalizedMessage.isEmpty()) return;
        
        if (consumePendingServerOnlyEchoSkip(playerName)) {
            return;
        }
        
        if (isServerOnlyOutgoingMessage(playerName, normalizedMessage)) {
            return;
        }
        
        if (normalizedMessage.startsWith("[DCI Relay]")) {
            return;
        }
        
        if (isSuppressedIncomingMessage(normalizedMessage)) {
            return;
        }
        
        long now = System.currentTimeMillis();
        
        Long sentTime = sentFromDiscord.get(normalizedMessage);
        if (sentTime != null && (now - sentTime) < SENT_FROM_DISCORD_WINDOW_MS) {
            sentFromDiscord.remove(normalizedMessage);
            return;
        }
        
        if (!sentFromDiscord.isEmpty()) {
            long cutoff = now - SENT_FROM_DISCORD_WINDOW_MS;
            for (var entry : sentFromDiscord.entrySet()) {
                if (entry.getValue() < cutoff) {
                    sentFromDiscord.remove(entry.getKey());
                } else if (normalizedMessage.contains(entry.getKey()) || entry.getKey().equals(normalizedMessage)) {
                    sentFromDiscord.remove(entry.getKey());
                    return;
                }
            }
        }
        
        sendToDiscordForLogging(playerName, playerUuid, skinUrl, normalizedMessage);
    }
    
    private void sendToDiscordForLogging(String playerName, String playerUuid, String skinUrl, String message) {
        discordForwardExecutor.execute(() -> {
            DiscordWebSocketServer server = DiscordWebSocketServer.getInstance();
            if (server != null && server.isRunning() && server.getConnectionCount() > 0) {
                server.broadcastMinecraftMessage(playerName, message);
            }
            RelayService.getInstance().relayMinecraftMessage(playerName, message, playerUuid, skinUrl);
        });
    }
    
    private void allowNextServerChatPacket() {
        allowedServerChatPackets.incrementAndGet();
    }
    
    private void markServerOnlyOutgoingMessage(String playerName, String message) {
        String normalizedPlayer = normalizeMessageKey(playerName);
        String normalized = normalizeMessageKey(message);
        if (normalized.isEmpty()) return;
        String key = buildServerOnlyMessageKey(normalizedPlayer, normalized);
        
        long now = System.currentTimeMillis();
        serverOnlyOutgoingMessages.put(key, now);
        
        if (serverOnlyOutgoingMessages.size() > 200) {
            long cutoff = now - SERVER_ONLY_MESSAGE_WINDOW_MS;
            serverOnlyOutgoingMessages.entrySet().removeIf(entry -> entry.getValue() < cutoff);
        }
    }
    
    private boolean isServerOnlyOutgoingMessage(String playerName, String message) {
        String normalizedPlayer = normalizeMessageKey(playerName);
        String normalized = normalizeMessageKey(message);
        String key = buildServerOnlyMessageKey(normalizedPlayer, normalized);
        Long timestamp = serverOnlyOutgoingMessages.get(key);
        if (timestamp == null) return false;
        
        long now = System.currentTimeMillis();
        if (now - timestamp < SERVER_ONLY_MESSAGE_WINDOW_MS) {
            serverOnlyOutgoingMessages.remove(key);
            return true;
        }
        
        serverOnlyOutgoingMessages.remove(key);
        return false;
    }
    
    private void markPendingServerOnlyEchoSkip() {
        pendingServerOnlyEchoSkips.incrementAndGet();
        pendingServerOnlyEchoExpiresAtMs = System.currentTimeMillis() + SERVER_ONLY_MESSAGE_WINDOW_MS;
    }
    
    private boolean consumePendingServerOnlyEchoSkip(String playerName) {
        if (!isLocalPlayerName(playerName)) return false;
        
        long now = System.currentTimeMillis();
        if (now > pendingServerOnlyEchoExpiresAtMs) {
            pendingServerOnlyEchoSkips.set(0);
            return false;
        }
        
        while (true) {
            int current = pendingServerOnlyEchoSkips.get();
            if (current <= 0) return false;
            if (pendingServerOnlyEchoSkips.compareAndSet(current, current - 1)) {
                return true;
            }
        }
    }
    
    private boolean isLocalPlayerName(String playerName) {
        String normalizedIncomingPlayer = normalizeMessageKey(playerName);
        if (normalizedIncomingPlayer.isEmpty()) return false;
        
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.player == null) return false;
        
        String localPlayerName = normalizeMessageKey(client.player.getName().getString());
        return !localPlayerName.isEmpty() && localPlayerName.equalsIgnoreCase(normalizedIncomingPlayer);
    }
    
    private String buildServerOnlyMessageKey(String playerName, String message) {
        return playerName.toLowerCase() + "|" + message;
    }
    
    public void suppressIncomingMessage(String message) {
        String normalized = normalizeMessageKey(message);
        if (normalized.isEmpty()) return;
        
        suppressedIncomingMessages.put(normalized, System.currentTimeMillis());
        if (suppressedIncomingMessages.size() > 200) {
            long cutoff = System.currentTimeMillis() - SUPPRESSED_MESSAGE_WINDOW_MS;
            suppressedIncomingMessages.entrySet().removeIf(entry -> entry.getValue() < cutoff);
        }
    }
    
    private boolean isSuppressedIncomingMessage(String message) {
        Long timestamp = suppressedIncomingMessages.get(message);
        if (timestamp == null) return false;
        
        if (System.currentTimeMillis() - timestamp < SUPPRESSED_MESSAGE_WINDOW_MS) {
            return true;
        }
        
        suppressedIncomingMessages.remove(message);
        return false;
    }
    
    private String normalizeMessageKey(String message) {
        return message != null ? message.trim() : "";
    }
    
    private String getPlayerName(Minecraft client) {
        if (client == null || client.player == null) return null;
        
        try {
            String name = client.player.getName().getString();
            if (name != null && !name.isEmpty() && !name.equals("Player")) return name;
        } catch (Exception ignored) {}
        
        try {
            String name = client.player.getGameProfile().name();
            if (name != null && !name.isEmpty() && !name.equals("Player")) return name;
        } catch (Exception ignored) {}
        
        try {
            if (client.getUser() != null) {
                String name = client.getUser().getName();
                if (name != null && !name.isEmpty() && !name.equals("Player")) return name;
            }
        } catch (Exception ignored) {}
        
        return null;
    }
    
    private record OutboundMessage(String content, boolean isCommand, String echoKey) {}
    
    public void shutdown() {
        messageProcessor.shutdown();
        discordForwardExecutor.shutdown();
        try {
            if (!messageProcessor.awaitTermination(2, TimeUnit.SECONDS)) messageProcessor.shutdownNow();
            if (!discordForwardExecutor.awaitTermination(2, TimeUnit.SECONDS)) discordForwardExecutor.shutdownNow();
        } catch (InterruptedException e) {
            messageProcessor.shutdownNow();
            discordForwardExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
