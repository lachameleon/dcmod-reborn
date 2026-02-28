package discord.chat.mc.relay;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import discord.chat.mc.DiscordChatIntegration;
import discord.chat.mc.config.ModConfig;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;

public final class RelayService {
    private static final Gson GSON = new Gson();
    private static final RelayService INSTANCE = new RelayService();
    private static final int MAX_PLAYER_NAME_LENGTH = 64;
    private static final int MAX_MESSAGE_LENGTH = 1600;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private RelayService() {}

    public static RelayService getInstance() {
        return INSTANCE;
    }
    
    public void relaySessionJoin(String playerName, boolean multiplayer, String serverAddress, String playerUuid, String skinUrl) {
        ModConfig config = ModConfig.getInstance();
        if (!config.isRelayEnabled()) return;
        
        String relayUrl = config.getRelayUrl();
        if (relayUrl.isBlank()) return;
        
        URI relayUri;
        try {
            relayUri = URI.create(relayUrl);
        } catch (IllegalArgumentException e) {
            DiscordChatIntegration.LOGGER.warn("Relay URL is invalid: {}", relayUrl);
            return;
        }
        
        JsonObject payload = new JsonObject();
        payload.addProperty("type", "session_event");
        payload.addProperty("event", "join");
        payload.addProperty("playerName", sanitize(playerName, "Player", MAX_PLAYER_NAME_LENGTH));
        payload.addProperty("mode", multiplayer ? "multiplayer" : "singleplayer");
        payload.addProperty("timestamp", Instant.now().toString());
        payload.addProperty("sourceClientId", config.getRelayClientId());
        
        if (serverAddress != null && !serverAddress.isBlank()) {
            payload.addProperty("serverAddress", sanitize(serverAddress, "", 200));
        }
        
        if (playerUuid != null && !playerUuid.isBlank()) {
            payload.addProperty("playerUuid", playerUuid);
        }
        
        String safeSkinUrl = sanitizeUrl(skinUrl);
        if (!safeSkinUrl.isEmpty()) {
            payload.addProperty("skinUrl", safeSkinUrl);
        }
        
        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(relayUri)
                .timeout(Duration.ofMillis(config.getRelayTimeoutMs()))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(payload)));
        
        String relayToken = config.getRelayToken();
        if (!relayToken.isBlank()) {
            requestBuilder.header("Authorization", "Bearer " + relayToken);
        }
        
        httpClient.sendAsync(requestBuilder.build(), HttpResponse.BodyHandlers.ofString())
                .exceptionally(error -> {
                    DiscordChatIntegration.LOGGER.debug("Relay session event failed: {}", error.getMessage());
                    return null;
                });
    }

    public void relayMinecraftMessage(String playerName, String message, String playerUuid, String skinUrl) {
        ModConfig config = ModConfig.getInstance();
        if (!config.isRelayEnabled()) return;

        String relayUrl = config.getRelayUrl();
        if (relayUrl.isBlank()) return;

        URI relayUri;
        try {
            relayUri = URI.create(relayUrl);
        } catch (IllegalArgumentException e) {
            DiscordChatIntegration.LOGGER.warn("Relay URL is invalid: {}", relayUrl);
            return;
        }

        String safePlayerName = sanitize(playerName, "System", MAX_PLAYER_NAME_LENGTH);
        String safeMessage = sanitize(message, "", MAX_MESSAGE_LENGTH);
        if (safeMessage.isEmpty()) return;

        JsonObject payload = new JsonObject();
        payload.addProperty("type", "minecraft_message");
        payload.addProperty("playerName", safePlayerName);
        payload.addProperty("message", safeMessage);
        payload.addProperty("timestamp", Instant.now().toString());
        payload.addProperty("sourceClientId", config.getRelayClientId());
        if (playerUuid != null && !playerUuid.isBlank()) {
            payload.addProperty("playerUuid", playerUuid);
        }
        String safeSkinUrl = sanitizeUrl(skinUrl);
        if (!safeSkinUrl.isEmpty()) {
            payload.addProperty("skinUrl", safeSkinUrl);
        }

        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(relayUri)
                .timeout(Duration.ofMillis(config.getRelayTimeoutMs()))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(payload)));

        String relayToken = config.getRelayToken();
        if (!relayToken.isBlank()) {
            requestBuilder.header("Authorization", "Bearer " + relayToken);
        }

        httpClient.sendAsync(requestBuilder.build(), HttpResponse.BodyHandlers.ofString())
                .thenAccept(response -> {
                    if (response.statusCode() < 200 || response.statusCode() >= 300) {
                        DiscordChatIntegration.LOGGER.warn(
                                "Relay request failed with status {}: {}",
                                response.statusCode(),
                                response.body()
                        );
                    }
                })
                .exceptionally(error -> {
                    DiscordChatIntegration.LOGGER.warn("Relay request failed: {}", error.getMessage());
                    return null;
                });
    }

    private static String sanitize(String value, String fallback, int maxLength) {
        String safeValue = value != null ? value.trim() : fallback;
        if (safeValue.isEmpty()) safeValue = fallback;
        if (safeValue.length() > maxLength) safeValue = safeValue.substring(0, maxLength);
        return safeValue;
    }
    
    private static String sanitizeUrl(String value) {
        if (value == null) return "";
        String trimmed = value.trim();
        if (trimmed.isEmpty() || trimmed.length() > 1024) return "";
        try {
            URI uri = URI.create(trimmed);
            String scheme = uri.getScheme();
            if (scheme == null) return "";
            if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) return "";
            return trimmed;
        } catch (IllegalArgumentException e) {
            return "";
        }
    }
}
