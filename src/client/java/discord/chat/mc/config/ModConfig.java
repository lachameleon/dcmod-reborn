package discord.chat.mc.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import discord.chat.mc.DiscordChatIntegration;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

public class ModConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String CONFIG_FILE = "discord-chat-integration.json";
    private static final String DEFAULT_RELAY_URL = "https://discordrelay.lacha.dev/relay";
    private static ModConfig instance;
    
    private int port = 25580;
    private Boolean relayEnabled = true;
    private String relayUrl = DEFAULT_RELAY_URL;
    private String relayToken = "";
    private int relayTimeoutMs = 4000;
    private String relayClientId = UUID.randomUUID().toString();
    private int maxDiscordMessagesPerMinute = 45;
    private Boolean localChatToDiscord = true;
    private transient Path configPath;
    
    public static ModConfig getInstance() {
        if (instance == null) instance = load();
        return instance;
    }
    
    private static Path getConfigPath() {
        return FabricLoader.getInstance().getConfigDir().resolve(CONFIG_FILE);
    }
    
    public static ModConfig load() {
        Path configPath = getConfigPath();
        
        if (Files.exists(configPath)) {
            try {
                ModConfig config = GSON.fromJson(Files.readString(configPath), ModConfig.class);
                if (config == null) {
                    config = new ModConfig();
                }
                config.configPath = configPath;
                config.sanitize();
                return config;
            } catch (IOException e) {
                DiscordChatIntegration.LOGGER.error("Failed to load config: {}", e.getMessage());
            }
        }
        
        ModConfig config = new ModConfig();
        config.configPath = configPath;
        config.sanitize();
        config.save();
        return config;
    }
    
    public void save() {
        try {
            if (configPath == null) configPath = getConfigPath();
            sanitize();
            Files.createDirectories(configPath.getParent());
            Files.writeString(configPath, GSON.toJson(this));
        } catch (IOException e) {
            DiscordChatIntegration.LOGGER.error("Failed to save config: {}", e.getMessage());
        }
    }
    
    private void sanitize() {
        if (port < 1024 || port > 65535) {
            port = 25580;
        }
        if (relayEnabled == null) relayEnabled = true;
        if (relayUrl == null || relayUrl.isBlank()) relayUrl = DEFAULT_RELAY_URL;
        if (relayToken == null) relayToken = "";
        if (relayTimeoutMs < 1000 || relayTimeoutMs > 30000) {
            relayTimeoutMs = 4000;
        }
        if (relayClientId == null || relayClientId.isBlank()) {
            relayClientId = UUID.randomUUID().toString();
        }
        if (maxDiscordMessagesPerMinute < 1 || maxDiscordMessagesPerMinute > 600) {
            maxDiscordMessagesPerMinute = 45;
        }
        if (localChatToDiscord == null) {
            localChatToDiscord = true;
        }
    }
    
    public int getPort() { return port; }
    public void setPort(int port) { this.port = port; }
    
    public boolean isRelayEnabled() { return relayEnabled != null && relayEnabled; }
    public void setRelayEnabled(boolean relayEnabled) { this.relayEnabled = relayEnabled; }
    
    public String getRelayUrl() { return (relayUrl != null && !relayUrl.isBlank()) ? relayUrl : DEFAULT_RELAY_URL; }
    public void setRelayUrl(String relayUrl) { this.relayUrl = relayUrl != null ? relayUrl.trim() : DEFAULT_RELAY_URL; }
    
    public String getRelayToken() { return relayToken != null ? relayToken : ""; }
    public void setRelayToken(String relayToken) { this.relayToken = relayToken != null ? relayToken.trim() : ""; }
    
    public int getRelayTimeoutMs() { return relayTimeoutMs; }
    public void setRelayTimeoutMs(int relayTimeoutMs) { this.relayTimeoutMs = relayTimeoutMs; }
    
    public String getRelayClientId() { return relayClientId; }
    public void setRelayClientId(String relayClientId) { this.relayClientId = relayClientId != null ? relayClientId.trim() : ""; }

    public int getMaxDiscordMessagesPerMinute() { return maxDiscordMessagesPerMinute; }
    public void setMaxDiscordMessagesPerMinute(int maxDiscordMessagesPerMinute) { this.maxDiscordMessagesPerMinute = maxDiscordMessagesPerMinute; }
    
    public boolean isLocalChatToDiscord() { return localChatToDiscord != null && localChatToDiscord; }
    public void setLocalChatToDiscord(boolean localChatToDiscord) { this.localChatToDiscord = localChatToDiscord; }
}
