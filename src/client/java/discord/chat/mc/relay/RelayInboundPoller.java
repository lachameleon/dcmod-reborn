package discord.chat.mc.relay;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import discord.chat.mc.DiscordChatIntegration;
import discord.chat.mc.chat.ChatHandler;
import discord.chat.mc.config.ModConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class RelayInboundPoller {
    private static final RelayInboundPoller INSTANCE = new RelayInboundPoller();
    private static final long POLL_INTERVAL_MS = 1500L;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private ScheduledExecutorService pollExecutor;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile long lastEventId = 0L;

    private RelayInboundPoller() {}

    public static RelayInboundPoller getInstance() {
        return INSTANCE;
    }

    public synchronized void start() {
        if (running.get()) return;

        running.set(true);
        if (pollExecutor == null || pollExecutor.isShutdown()) {
            pollExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "Discord-Relay-Inbound-Poller");
                t.setDaemon(true);
                return t;
            });
        }

        pollExecutor.scheduleWithFixedDelay(this::pollOnce, 1500L, POLL_INTERVAL_MS, TimeUnit.MILLISECONDS);
        DiscordChatIntegration.LOGGER.info("Relay inbound poller started");
    }

    public synchronized void stop() {
        running.set(false);
        if (pollExecutor != null) {
            pollExecutor.shutdownNow();
            pollExecutor = null;
        }
        lastEventId = 0L;
        DiscordChatIntegration.LOGGER.info("Relay inbound poller stopped");
    }

    private void pollOnce() {
        if (!running.get()) return;

        ModConfig config = ModConfig.getInstance();
        if (!config.isRelayEnabled()) return;

        String relayUrl = config.getRelayUrl();
        if (relayUrl.isBlank()) return;

        URI eventsUri;
        try {
            eventsUri = buildEventsUri(relayUrl, config.getRelayClientId(), lastEventId);
        } catch (Exception e) {
            DiscordChatIntegration.LOGGER.debug("Relay poll URL build failed: {}", e.getMessage());
            return;
        }

        try {
            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(eventsUri)
                    .header("Accept", "application/json")
                    .timeout(Duration.ofMillis(config.getRelayTimeoutMs()))
                    .GET();

            String relayToken = config.getRelayToken();
            if (!relayToken.isBlank()) {
                requestBuilder.header("Authorization", "Bearer " + relayToken);
            }

            HttpResponse<String> response = httpClient.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                DiscordChatIntegration.LOGGER.debug("Relay poll returned status {}", response.statusCode());
                return;
            }

            JsonObject body = JsonParser.parseString(response.body()).getAsJsonObject();
            if (body.has("latestEventId")) {
                try {
                    long latest = body.get("latestEventId").getAsLong();
                    if (latest > lastEventId) {
                        lastEventId = latest;
                    }
                } catch (Exception ignored) {}
            }

            if (!body.has("events") || !body.get("events").isJsonArray()) return;

            JsonArray events = body.getAsJsonArray("events");
            for (int i = 0; i < events.size(); i++) {
                if (!events.get(i).isJsonObject()) continue;
                JsonObject event = events.get(i).getAsJsonObject();

                long eventId = event.has("id") ? event.get("id").getAsLong() : -1L;
                if (eventId <= 0) continue;
                if (eventId > lastEventId) {
                    lastEventId = eventId;
                }

                if (event.has("sourceClientId") && config.getRelayClientId().equals(event.get("sourceClientId").getAsString())) {
                    continue;
                }

                displayEvent(event);
            }
        } catch (Exception e) {
            DiscordChatIntegration.LOGGER.debug("Relay poll failed: {}", e.getMessage());
        }
    }

    private URI buildEventsUri(String relayUrl, String clientId, long since) {
        URI base = URI.create(relayUrl);
        String existingQuery = base.getQuery();
        String separator = (existingQuery == null || existingQuery.isBlank()) ? "?" : "&";

        String query = String.format(
                "%sevents=1&since=%d&clientId=%s",
                separator,
                Math.max(0L, since),
                URLEncoder.encode(clientId, StandardCharsets.UTF_8)
        );

        return URI.create(relayUrl + query);
    }

    private void displayEvent(JsonObject event) {
        String type = event.has("type") ? event.get("type").getAsString() : "";
        String formattedMessage;

        if ("discord_message".equals(type)) {
            String author = event.has("author") ? event.get("author").getAsString() : "Discord";
            String content = event.has("message") ? event.get("message").getAsString() : "";
            if (content.isBlank()) return;
            formattedMessage = "§8[DCI Relay] §9[Discord] §f<" + author + "> §7" + content;
        } else if ("minecraft_message".equals(type)) {
            String playerName = event.has("playerName") ? event.get("playerName").getAsString() : "Minecraft";
            String content = event.has("message") ? event.get("message").getAsString() : "";
            if (content.isBlank()) return;
            formattedMessage = "§8[DCI Relay] §a[Chat] §f<" + playerName + "> §7" + content;
        } else {
            return;
        }

        String plainMessage = formattedMessage.replaceAll("\\u00A7.", "").trim();

        Minecraft client = Minecraft.getInstance();
        if (client == null) return;

        client.execute(() -> {
            try {
                if (client.player == null) return;
                ChatHandler.getInstance().suppressIncomingMessage(plainMessage);
                client.player.displayClientMessage(Component.literal(formattedMessage), false);
            } catch (Exception ignored) {}
        });
    }
}
