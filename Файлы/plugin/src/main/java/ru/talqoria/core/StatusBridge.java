package ru.talqoria.core;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitTask;

/** Network I/O never runs on the Minecraft tick thread. */
public final class StatusBridge {
    private final TalqoriaCorePlugin plugin;
    private final AtomicBoolean inFlight = new AtomicBoolean(false);
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3)).build();
    private BukkitTask job;

    public StatusBridge(TalqoriaCorePlugin plugin) { this.plugin = plugin; }

    public void start() {
        if (!plugin.getConfig().getBoolean("integration.enabled", false)) return;
        String token = System.getenv("TALQORIA_BRIDGE_TOKEN");
        if (token == null || token.isBlank()) {
            plugin.getLogger().warning("Status bridge enabled but TALQORIA_BRIDGE_TOKEN is missing.");
            return;
        }
        String endpoint = plugin.getConfig().getString("integration.endpoint", "");
        URI uri;
        try {
            uri = BridgeEndpointPolicy.parse(endpoint);
        } catch (IllegalArgumentException bad) {
            plugin.getLogger().warning("Status bridge endpoint invalid: " + bad.getMessage());
            return;
        }
        long interval = Math.max(200L, plugin.getConfig().getLong("integration.interval-ticks", 400));
        job = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!inFlight.compareAndSet(false, true)) return;
            // Snapshot from the synchronous Bukkit thread before dispatch.
            int online = Bukkit.getOnlinePlayers().size();
            int maximum = Math.max(online, Bukkit.getMaxPlayers());
            String version = Bukkit.getMinecraftVersion();
            String body = String.format("{\"online\":%d,\"max\":%d,\"version\":\"%s\"}",
                    online, maximum, version.replaceAll("[^a-zA-Z0-9 ._+\\-]", ""));
            try {
                HttpRequest request = HttpRequest.newBuilder(uri)
                        .timeout(Duration.ofSeconds(6))
                        .header("Content-Type", "application/json")
                        .header("Authorization", "Bearer " + token)
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build();
                http.sendAsync(request, HttpResponse.BodyHandlers.discarding())
                        .whenComplete((response, error) -> {
                            inFlight.set(false);
                            if (error != null) return; // temporary API outages are expected during development
                            if (response.statusCode() != 200 && response.statusCode() != 503) {
                                plugin.getLogger().warning("Status bridge HTTP " + response.statusCode());
                            }
                        });
            } catch (RuntimeException exception) {
                inFlight.set(false);
                plugin.getLogger().warning("Status bridge error: " + exception.getMessage());
            }
        }, 20L, interval);
    }

    public void stop() {
        if (job != null) job.cancel();
    }
}
