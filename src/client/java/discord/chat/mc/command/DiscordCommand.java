package discord.chat.mc.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import discord.chat.mc.chat.ChatHandler;
import discord.chat.mc.config.ModConfig;
import discord.chat.mc.websocket.DiscordWebSocketServer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;

import java.net.URI;
import java.util.List;

public class DiscordCommand {
    
    private static final SuggestionProvider<FabricClientCommandSource> AUTOMATION_SUGGESTIONS = (context, builder) -> {
        DiscordWebSocketServer server = DiscordWebSocketServer.getInstance();
        if (server != null && server.isRunning()) {
            server.requestAutomationsList();
            List<String> names = server.getCachedAutomationNames();
            return SharedSuggestionProvider.suggest(names, builder);
        }
        return builder.buildFuture();
    };
    
    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register(DiscordCommand::registerCommands);
    }
    
    private static void registerCommands(CommandDispatcher<FabricClientCommandSource> dispatcher, CommandBuildContext registryAccess) {
        dispatcher.register(
            ClientCommandManager.literal("discordchat")
                .executes(context -> {
                    showStatus(context.getSource());
                    return 1;
                })
                .then(ClientCommandManager.literal("status")
                    .executes(context -> {
                        showStatus(context.getSource());
                        return 1;
                    })
                )
                .then(ClientCommandManager.literal("send")
                    .then(ClientCommandManager.argument("message", StringArgumentType.greedyString())
                        .executes(context -> {
                            sendMessageToServer(
                                    context.getSource(),
                                    StringArgumentType.getString(context, "message")
                            );
                            return 1;
                        })
                    )
                    .executes(context -> {
                        context.getSource().sendError(Component.literal("§cUsage: /discordchat send <message>"));
                        return 0;
                    })
                )
                .then(ClientCommandManager.literal("port")
                    .then(ClientCommandManager.argument("port", IntegerArgumentType.integer(1024, 65535))
                        .executes(context -> {
                            int port = IntegerArgumentType.getInteger(context, "port");
                            setPort(context.getSource(), port);
                            return 1;
                        })
                    )
                    .executes(context -> {
                        showPort(context.getSource());
                        return 1;
                    })
                )
                .then(ClientCommandManager.literal("reconnect")
                    .executes(context -> {
                        reconnect(context.getSource());
                        return 1;
                    })
                )
                .then(ClientCommandManager.literal("disconnect")
                    .executes(context -> {
                        disconnect(context.getSource());
                        return 1;
                    })
                )
                .then(ClientCommandManager.literal("ticktest")
                    .executes(context -> {
                        showTickTest(context.getSource());
                        return 1;
                    })
                )
                .then(ClientCommandManager.literal("run")
                    .then(ClientCommandManager.argument("automation", StringArgumentType.greedyString())
                        .suggests(AUTOMATION_SUGGESTIONS)
                        .executes(context -> {
                            String automationName = StringArgumentType.getString(context, "automation");
                            runAutomation(context.getSource(), automationName);
                            return 1;
                        })
                    )
                    .executes(context -> {
                        listAutomations(context.getSource());
                        return 1;
                    })
                )
                .then(ClientCommandManager.literal("stop")
                    .executes(context -> {
                        stopAutomations(context.getSource());
                        return 1;
                    })
                )
                .then(ClientCommandManager.literal("relay")
                    .executes(context -> {
                        showRelayStatus(context.getSource());
                        return 1;
                    })
                    .then(ClientCommandManager.literal("status")
                        .executes(context -> {
                            showRelayStatus(context.getSource());
                            return 1;
                        })
                    )
                    .then(ClientCommandManager.literal("enable")
                        .executes(context -> {
                            setRelayEnabled(context.getSource(), true);
                            return 1;
                        })
                    )
                    .then(ClientCommandManager.literal("disable")
                        .executes(context -> {
                            setRelayEnabled(context.getSource(), false);
                            return 1;
                        })
                    )
                    .then(ClientCommandManager.literal("url")
                        .executes(context -> {
                            showRelayUrl(context.getSource());
                            return 1;
                        })
                        .then(ClientCommandManager.argument("url", StringArgumentType.greedyString())
                            .executes(context -> {
                                setRelayUrl(context.getSource(), StringArgumentType.getString(context, "url"));
                                return 1;
                            })
                        )
                    )
                    .then(ClientCommandManager.literal("token")
                        .executes(context -> {
                            showRelayTokenStatus(context.getSource());
                            return 1;
                        })
                        .then(ClientCommandManager.literal("clear")
                            .executes(context -> {
                                clearRelayToken(context.getSource());
                                return 1;
                            })
                        )
                        .then(ClientCommandManager.argument("token", StringArgumentType.greedyString())
                            .executes(context -> {
                                setRelayToken(context.getSource(), StringArgumentType.getString(context, "token"));
                                return 1;
                            })
                        )
                    )
                    .then(ClientCommandManager.literal("timeout")
                        .executes(context -> {
                            showRelayTimeout(context.getSource());
                            return 1;
                        })
                        .then(ClientCommandManager.argument("ms", IntegerArgumentType.integer(1000, 30000))
                            .executes(context -> {
                                setRelayTimeout(context.getSource(), IntegerArgumentType.getInteger(context, "ms"));
                                return 1;
                            })
                        )
                    )
                )
                .then(ClientCommandManager.literal("ratelimit")
                    .executes(context -> {
                        showRateLimit(context.getSource());
                        return 1;
                    })
                    .then(ClientCommandManager.literal("status")
                        .executes(context -> {
                            showRateLimit(context.getSource());
                            return 1;
                        })
                    )
                    .then(ClientCommandManager.argument("messagesPerMinute", IntegerArgumentType.integer(1, 600))
                        .executes(context -> {
                            setRateLimit(
                                    context.getSource(),
                                    IntegerArgumentType.getInteger(context, "messagesPerMinute")
                            );
                            return 1;
                        })
                    )
                )
        );
        
        dispatcher.register(
            ClientCommandManager.literal("toggle")
                .executes(context -> {
                    toggleChatTarget(context.getSource());
                    return 1;
                })
        );
    }
    
    private static void showStatus(FabricClientCommandSource source) {
        DiscordWebSocketServer server = DiscordWebSocketServer.getInstance();
        ModConfig config = ModConfig.getInstance();
        
        StringBuilder status = new StringBuilder();
        status.append("§6=== Discord Chat Integration Status ===§r\n");
        
        if (server == null) {
            if (config.isRelayEnabled()) {
                status.append("§eWebSocket Bridge: Not initialized (optional in relay mode)§r\n");
            } else {
                status.append("§cServer: Not initialized§r\n");
                status.append("§7Warning: Port may be taken or server failed to start§r\n");
                status.append("§7Use §f/discordchat port <number>§7 to change to a different port§r");
            }
        } else if (server.isRunning()) {
            status.append("§aServer: Running§r\n");
            status.append(String.format("§7Port: §f%d§r\n", server.getPort()));
            status.append(String.format("§7Connected clients: §f%d§r", server.getConnectionCount()));
        } else {
            if (config.isRelayEnabled()) {
                status.append("§eWebSocket Bridge: Stopped (optional in relay mode)§r");
            } else {
                status.append("§cServer: Stopped§r\n");
                status.append("§7Warning: Port may be taken or server is offline§r\n");
                status.append("§7Use §f/discordchat port <number>§7 to change to a different port§r");
            }
        }
        
        status.append(String.format("\n§7Relay: §f%s§r", config.isRelayEnabled() ? "Enabled" : "Disabled"));
        if (config.getRelayUrl().isBlank()) {
            status.append("\n§7Relay URL: §cNot set§r");
        } else {
            status.append("\n§7Relay URL: §aConfigured§r");
        }
        status.append(String.format(
                "\n§7Local Chat Target: §f%s§r",
                ChatHandler.getInstance().isLocalChatToDiscordMode() ? "Discord relay" : "Minecraft server"
        ));
        status.append(
                String.format(
                        "\n§7Rate Limit: §f%d/%d per minute§r",
                        ChatHandler.getInstance().getDiscordRateLimitUsage(),
                        config.getMaxDiscordMessagesPerMinute()
                )
        );
        
        source.sendFeedback(Component.literal(status.toString()));
    }
    
    private static void showPort(FabricClientCommandSource source) {
        int currentPort = ModConfig.getInstance().getPort();
        source.sendFeedback(Component.literal(
            String.format("§6Current WebSocket port: §f%d§r\n§7Use §f/discordchat port <number>§7 to change it.", currentPort)
        ));
    }
    
    private static void sendMessageToServer(FabricClientCommandSource source, String rawMessage) {
        String message = rawMessage != null ? rawMessage.trim() : "";
        if (message.isEmpty()) {
            source.sendError(Component.literal("§cMessage cannot be empty."));
            return;
        }
        
        boolean sent = ChatHandler.getInstance().sendChatToServerOnly(message);
        if (!sent) {
            source.sendError(Component.literal("§cUnable to send message right now."));
            return;
        }
        
        source.sendFeedback(Component.literal("§aSent to Minecraft server only (relay bypassed)."));
    }
    
    private static void toggleChatTarget(FabricClientCommandSource source) {
        ChatHandler handler = ChatHandler.getInstance();
        boolean nowDiscordMode = handler.toggleLocalChatMode();
        source.sendFeedback(Component.literal(
                nowDiscordMode
                        ? "§aLocal chat mode set to §fDiscord relay only§a. Plain chat won't be sent to the server."
                        : "§aLocal chat mode set to §fMinecraft server§a. Plain chat won't be relayed to Discord."
        ));
    }
    
    private static void setPort(FabricClientCommandSource source, int port) {
        ModConfig config = ModConfig.getInstance();
        int oldPort = config.getPort();
        
        if (oldPort == port) {
            source.sendFeedback(Component.literal(
                String.format("§7Port is already set to §f%d§r", port)
            ));
            return;
        }
        
        config.setPort(port);
        config.save();
        
        source.sendFeedback(Component.literal(
            String.format("§aPort changed from §f%d§a to §f%d§r", oldPort, port)
        ));
        
        reconnect(source);
    }
    
    private static void reconnect(FabricClientCommandSource source) {
        source.sendFeedback(Component.literal("§6Restarting WebSocket server...§r"));
        
        DiscordWebSocketServer oldServer = DiscordWebSocketServer.getInstance();
        if (oldServer != null && oldServer.isRunning()) {
            oldServer.stopServer();
        }
        
        int port = ModConfig.getInstance().getPort();
        DiscordWebSocketServer.createInstance(port);
        DiscordWebSocketServer newServer = DiscordWebSocketServer.getInstance();
        
        newServer.setMessageHandler(message -> ChatHandler.getInstance().handleDiscordMessage(message));
        
        new Thread(() -> {
            try {
                newServer.start();
                source.sendFeedback(Component.literal(
                    String.format("§aWebSocket server restarted on port §f%d§r", port)
                ));
            } catch (Exception e) {
                source.sendError(Component.literal(
                    String.format("§cFailed to start server: %s§r", e.getMessage())
                ));
            }
        }, "Discord-WebSocket-Server").start();
    }
    
    private static void disconnect(FabricClientCommandSource source) {
        DiscordWebSocketServer server = DiscordWebSocketServer.getInstance();
        
        if (server == null || !server.isRunning()) {
            source.sendFeedback(Component.literal("§cWebSocket server is not running.§r"));
            return;
        }
        
        int clientCount = server.getConnectionCount();
        server.stopServer();
        
        if (clientCount > 0) {
            source.sendFeedback(Component.literal(
                String.format("§aDisconnected from Discord. §f%d§a client(s) were disconnected.§r\n§7Use §f/discordchat reconnect§7 to reconnect.", clientCount)
            ));
        } else {
            source.sendFeedback(Component.literal(
                "§aWebSocket server stopped.§r\n§7Use §f/discordchat reconnect§7 to reconnect."
            ));
        }
    }
    
    private static void showTickTest(FabricClientCommandSource source) {
        Minecraft client = Minecraft.getInstance();
        
        if (client == null) {
            source.sendFeedback(Component.literal("§cError: Minecraft client not available§r"));
            return;
        }
        
        if (client.level == null) {
            source.sendFeedback(Component.literal("§cError: Not in a world§r\n§7Join a world or server to test tick synchronization§r"));
            return;
        }
        
        long serverTick = client.level.getGameTime();
        long clientTimeMs = System.currentTimeMillis();
        
        String playerName = "Unknown";
        if (client.player != null) {
            try {
                playerName = client.player.getName().getString();
            } catch (Exception e) {
                try { playerName = client.player.getGameProfile().name(); } catch (Exception ignored) {}
            }
        }
        
        String syncGroup = ChatHandler.getInstance().getLastSyncGroup();
        long[] execInfo = ChatHandler.getInstance().getLastExecutionInfo();
        
        StringBuilder message = new StringBuilder();
        message.append("§6=== Tick Test Result ===§r\n");
        message.append(String.format("§7Player: §f%s§r\n", playerName));
        message.append(String.format("§7Server Tick: §f%d§r\n", serverTick));
        message.append(String.format("§7Client Time: §f%d§r ms\n", clientTimeMs));
        message.append(String.format("§7Sync Group: §f%s§r\n", syncGroup != null ? syncGroup : "none"));
        
        if (execInfo != null) {
            long targetTick = execInfo[0];
            long execTick = execInfo[1];
            long receiveTime = execInfo[2];
            long execTime = execInfo[3];
            
            message.append("§6--- Last Sync Execution ---§r\n");
            message.append(String.format("§7Target Tick: §f%d§r\n", targetTick));
            message.append(String.format("§7Exec Tick: §f%d§r\n", execTick));
            message.append(String.format("§7Receive Time: §f%d§r ms\n", receiveTime));
            message.append(String.format("§7Exec Time: §f%d§r ms\n", execTime));
            message.append(String.format("§7Waited: §f%d§r ms", execTime - receiveTime));
        }
        
        source.sendFeedback(Component.literal(message.toString()));
    }
    
    private static void runAutomation(FabricClientCommandSource source, String automationName) {
        DiscordWebSocketServer server = DiscordWebSocketServer.getInstance();
        
        if (server == null || !server.isRunning()) {
            source.sendError(Component.literal("§cWebSocket server is not running. Use §f/discordchat reconnect§c to start it."));
            return;
        }
        
        if (server.getConnectionCount() == 0) {
            source.sendError(Component.literal("§cNo Discord clients connected."));
            return;
        }
        
        source.sendFeedback(Component.literal(String.format("§6Running automation: §f%s§6...§r", automationName)));
        server.runAutomation(automationName);
        
        new Thread(() -> {
            try {
                Thread.sleep(500);
                String result = server.getAndClearAutomationResult();
                if (result != null) {
                    Minecraft client = Minecraft.getInstance();
                    if (client != null && client.player != null) {
                        client.execute(() -> client.player.displayClientMessage(Component.literal(result), false));
                    }
                }
            } catch (InterruptedException ignored) {}
        }, "Automation-Result-Wait").start();
    }
    
    private static void stopAutomations(FabricClientCommandSource source) {
        DiscordWebSocketServer server = DiscordWebSocketServer.getInstance();
        
        if (server == null || !server.isRunning()) {
            source.sendError(Component.literal("§cWebSocket server is not running."));
            return;
        }
        
        if (server.getConnectionCount() == 0) {
            source.sendError(Component.literal("§cNo Discord clients connected."));
            return;
        }
        
        source.sendFeedback(Component.literal("§6Stopping automations...§r"));
        server.stopAutomations();
        
        new Thread(() -> {
            try {
                Thread.sleep(300);
                String result = server.getAndClearAutomationResult();
                if (result != null) {
                    Minecraft client = Minecraft.getInstance();
                    if (client != null && client.player != null) {
                        client.execute(() -> client.player.displayClientMessage(Component.literal(result), false));
                    }
                }
            } catch (InterruptedException ignored) {}
        }, "Automation-Stop-Wait").start();
    }
    
    private static void listAutomations(FabricClientCommandSource source) {
        DiscordWebSocketServer server = DiscordWebSocketServer.getInstance();
        
        if (server == null || !server.isRunning()) {
            source.sendError(Component.literal("§cWebSocket server is not running."));
            return;
        }
        
        if (server.getConnectionCount() == 0) {
            source.sendError(Component.literal("§cNo Discord clients connected."));
            return;
        }
        
        server.requestAutomationsList();
        
        new Thread(() -> {
            try {
                Thread.sleep(300);
                List<String> names = server.getCachedAutomationNames();
                Minecraft client = Minecraft.getInstance();
                if (client != null && client.player != null) {
                    client.execute(() -> {
                        if (names.isEmpty()) {
                            client.player.displayClientMessage(Component.literal("§7No automations configured in Discord plugin."), false);
                        } else {
                            StringBuilder sb = new StringBuilder();
                            sb.append("§6=== Available Automations ===§r\n");
                            for (String name : names) {
                                sb.append("§7• §f").append(name).append("§r\n");
                            }
                            sb.append("§7Use §f/discordchat run <name>§7 to run an automation.");
                            client.player.displayClientMessage(Component.literal(sb.toString()), false);
                        }
                    });
                }
            } catch (InterruptedException ignored) {}
        }, "Automations-List-Wait").start();
    }
    
    private static void showRelayStatus(FabricClientCommandSource source) {
        ModConfig config = ModConfig.getInstance();
        
        StringBuilder message = new StringBuilder();
        message.append("§6=== Relay Status ===§r\n");
        message.append(String.format("§7Enabled: §f%s§r\n", config.isRelayEnabled() ? "Yes" : "No"));
        message.append(String.format("§7URL: §f%s§r\n", config.getRelayUrl().isBlank() ? "(not set)" : config.getRelayUrl()));
        message.append(String.format("§7Token: §f%s§r\n", config.getRelayToken().isBlank() ? "Not set" : "Configured"));
        message.append(String.format("§7Timeout: §f%d ms§r\n", config.getRelayTimeoutMs()));
        message.append("§7Commands: §f/discordchat relay url <url>§7, §f/discordchat relay enable§7");
        
        source.sendFeedback(Component.literal(message.toString()));
    }
    
    private static void setRelayEnabled(FabricClientCommandSource source, boolean enabled) {
        ModConfig config = ModConfig.getInstance();
        config.setRelayEnabled(enabled);
        config.save();
        
        if (enabled && config.getRelayUrl().isBlank()) {
            source.sendFeedback(Component.literal("§eRelay enabled, but URL is not set. Use §f/discordchat relay url <url>§e."));
            return;
        }
        
        source.sendFeedback(Component.literal(String.format("§aRelay %s§r", enabled ? "enabled" : "disabled")));
    }
    
    private static void showRelayUrl(FabricClientCommandSource source) {
        ModConfig config = ModConfig.getInstance();
        String currentUrl = config.getRelayUrl();
        source.sendFeedback(Component.literal(
                currentUrl.isBlank()
                        ? "§7Relay URL is not set. Use §f/discordchat relay url <url>§7."
                        : String.format("§6Relay URL: §f%s§r", currentUrl)
        ));
    }
    
    private static void setRelayUrl(FabricClientCommandSource source, String rawUrl) {
        String relayUrl = rawUrl != null ? rawUrl.trim() : "";
        if (!isValidRelayUrl(relayUrl)) {
            source.sendError(Component.literal("§cInvalid relay URL. Use a full http/https URL."));
            return;
        }
        
        ModConfig config = ModConfig.getInstance();
        config.setRelayUrl(relayUrl);
        config.save();
        
        source.sendFeedback(Component.literal(String.format("§aRelay URL set to §f%s§r", relayUrl)));
    }
    
    private static void showRelayTokenStatus(FabricClientCommandSource source) {
        ModConfig config = ModConfig.getInstance();
        source.sendFeedback(Component.literal(
                config.getRelayToken().isBlank()
                        ? "§7Relay token is not set."
                        : "§aRelay token is configured."
        ));
    }
    
    private static void setRelayToken(FabricClientCommandSource source, String token) {
        String relayToken = token != null ? token.trim() : "";
        if (relayToken.isEmpty()) {
            source.sendError(Component.literal("§cToken cannot be empty. Use §f/discordchat relay token clear§c to remove it."));
            return;
        }
        
        ModConfig config = ModConfig.getInstance();
        config.setRelayToken(relayToken);
        config.save();
        
        source.sendFeedback(Component.literal("§aRelay token updated."));
    }
    
    private static void clearRelayToken(FabricClientCommandSource source) {
        ModConfig config = ModConfig.getInstance();
        config.setRelayToken("");
        config.save();
        source.sendFeedback(Component.literal("§aRelay token cleared."));
    }
    
    private static void showRelayTimeout(FabricClientCommandSource source) {
        ModConfig config = ModConfig.getInstance();
        source.sendFeedback(Component.literal(
                String.format("§6Relay timeout: §f%d ms§r", config.getRelayTimeoutMs())
        ));
    }
    
    private static void setRelayTimeout(FabricClientCommandSource source, int timeoutMs) {
        ModConfig config = ModConfig.getInstance();
        config.setRelayTimeoutMs(timeoutMs);
        config.save();
        
        source.sendFeedback(Component.literal(
                String.format("§aRelay timeout set to §f%d ms§r", timeoutMs)
        ));
    }
    
    private static void showRateLimit(FabricClientCommandSource source) {
        ModConfig config = ModConfig.getInstance();
        int usage = ChatHandler.getInstance().getDiscordRateLimitUsage();
        int limit = config.getMaxDiscordMessagesPerMinute();
        source.sendFeedback(Component.literal(
                String.format(
                        "§6Discord send rate limit: §f%d/%d per minute§r\n§7Set with §f/discordchat ratelimit <1-600>§7.",
                        usage,
                        limit
                )
        ));
    }
    
    private static void setRateLimit(FabricClientCommandSource source, int messagesPerMinute) {
        ModConfig config = ModConfig.getInstance();
        int oldValue = config.getMaxDiscordMessagesPerMinute();
        config.setMaxDiscordMessagesPerMinute(messagesPerMinute);
        config.save();
        
        source.sendFeedback(Component.literal(
                String.format(
                        "§aDiscord send rate limit changed from §f%d§a to §f%d§a messages/minute.§r",
                        oldValue,
                        messagesPerMinute
                )
        ));
    }
    
    private static boolean isValidRelayUrl(String relayUrl) {
        try {
            URI uri = URI.create(relayUrl);
            if (uri.getHost() == null || uri.getHost().isBlank()) return false;
            String scheme = uri.getScheme();
            return "http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
