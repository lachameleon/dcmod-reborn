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
        );
    }
    
    private static void showStatus(FabricClientCommandSource source) {
        DiscordWebSocketServer server = DiscordWebSocketServer.getInstance();
        
        StringBuilder status = new StringBuilder();
        status.append("§6=== Discord Chat Integration Status ===§r\n");
        
        if (server == null) {
            status.append("§cServer: Not initialized§r\n");
            status.append("§7Warning: Port may be taken or server failed to start§r\n");
            status.append("§7Use §f/discordchat port <number>§7 to change to a different port§r");
        } else if (server.isRunning()) {
            status.append("§aServer: Running§r\n");
            status.append(String.format("§7Port: §f%d§r\n", server.getPort()));
            status.append(String.format("§7Connected clients: §f%d§r", server.getConnectionCount()));
        } else {
            status.append("§cServer: Stopped§r\n");
            status.append("§7Warning: Port may be taken or server is offline§r\n");
            status.append("§7Use §f/discordchat port <number>§7 to change to a different port§r");
        }
        
        source.sendFeedback(Component.literal(status.toString()));
    }
    
    private static void showPort(FabricClientCommandSource source) {
        int currentPort = ModConfig.getInstance().getPort();
        source.sendFeedback(Component.literal(
            String.format("§6Current WebSocket port: §f%d§r\n§7Use §f/discordchat port <number>§7 to change it.", currentPort)
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
                try { playerName = client.player.getGameProfile().getName(); } catch (Exception ignored) {}
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
}
