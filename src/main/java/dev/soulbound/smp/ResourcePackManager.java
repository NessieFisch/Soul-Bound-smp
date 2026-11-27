package dev.soulbound.smp;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Sends the Soulbound resource pack (mythic sword models) to players and keeps it up to date.
 *
 * The zip is downloaded from {@code resource-pack.url} every few minutes; when its SHA-1 changes
 * (you uploaded a new zip at the same link) every online player is sent the new pack automatically.
 * A fixed pack UUID makes the client REPLACE the old pack instead of stacking a second one.
 */
public final class ResourcePackManager {

    private static final UUID PACK_ID = UUID.nameUUIDFromBytes("soulbound-smp-mythic-pack".getBytes(StandardCharsets.UTF_8));

    private final SoulboundPlugin plugin;
    private final HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.ALWAYS)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private BukkitTask task;
    private volatile String baseUrl = "";
    private volatile String sendUrl = "";
    private volatile byte[] hash;
    private volatile long lastChange;

    public ResourcePackManager(SoulboundPlugin plugin) {
        this.plugin = plugin;
    }

    /** (Re)reads the config and starts the periodic update check. */
    public void start() {
        stop();
        var cfg = plugin.getConfig();
        if (!cfg.getBoolean("resource-pack.enabled", false)) return;
        baseUrl = cfg.getString("resource-pack.url", "").trim();
        if (baseUrl.isEmpty()) {
            plugin.getLogger().info("resource-pack.url is empty, the mythic sword pack will not be sent.");
            return;
        }
        long minutes = Math.max(1, cfg.getLong("resource-pack.check-interval-minutes", 5));
        long ticks = minutes * 60L * 20L;
        task = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, () -> check(null), 20L, ticks);
    }

    public void stop() {
        if (task != null) task.cancel();
        task = null;
    }

    /** Checks the URL now (async). Pushes the pack to everyone online if it changed. */
    public void checkNow(CommandSender requester) {
        if (baseUrl.isEmpty()) {
            if (requester != null) plugin.msg().send(requester, "<red>resource-pack.url is not set in config.yml.");
            return;
        }
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> check(requester));
    }

    public String status() {
        if (baseUrl.isEmpty()) return "<red>No pack configured (resource-pack.url is empty).";
        String h = hash == null ? "not downloaded yet" : HexFormat.of().formatHex(hash).substring(0, 10);
        return "<gray>Pack <white>" + baseUrl + " <gray>sha1 <white>" + h;
    }

    private void check(CommandSender requester) {
        try {
            String probe = baseUrl + (baseUrl.contains("?") ? "&" : "?") + "sbcheck=" + System.currentTimeMillis();
            HttpResponse<byte[]> res = http.send(
                    HttpRequest.newBuilder(URI.create(probe)).timeout(Duration.ofSeconds(60)).GET().build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            if (res.statusCode() != 200) {
                plugin.getLogger().warning("Resource pack check failed: HTTP " + res.statusCode());
                if (requester != null) plugin.msg().send(requester, "<red>Pack download failed: HTTP " + res.statusCode());
                return;
            }
            byte[] body = res.body();
            if (body.length < 4 || body[0] != 'P' || body[1] != 'K') {
                plugin.getLogger().warning("Resource pack URL did not return a zip file. Use a direct download link.");
                if (requester != null) plugin.msg().send(requester, "<red>That URL did not return a zip. Use a direct download link.");
                return;
            }
            byte[] fresh = MessageDigest.getInstance("SHA-1").digest(body);
            byte[] old = hash;
            if (old != null && java.util.Arrays.equals(old, fresh)) {
                if (requester != null) plugin.msg().send(requester, "<green>Pack is already up to date.");
                return;
            }
            hash = fresh;
            lastChange = System.currentTimeMillis();
            String hex = HexFormat.of().formatHex(fresh);
            // the ?v= part makes players (and any CDN) fetch the new file instead of a cached copy
            sendUrl = baseUrl + (baseUrl.contains("?") ? "&" : "?") + "v=" + hex.substring(0, 10);
            plugin.getLogger().info("Resource pack " + (old == null ? "loaded" : "UPDATED") + " (sha1 " + hex + ").");
            if (old != null) {
                Bukkit.getScheduler().runTask(plugin, () -> {
                    for (Player p : Bukkit.getOnlinePlayers()) send(p);
                });
            }
            if (requester != null) plugin.msg().send(requester, "<green>Pack " + (old == null ? "loaded" : "updated and sent to all players") + ".");
        } catch (Exception ex) {
            plugin.getLogger().warning("Resource pack check failed: " + ex.getMessage());
            if (requester != null) plugin.msg().send(requester, "<red>Pack check failed: " + ex.getMessage());
        }
    }

    /** Sends the current pack to one player (no-op until the first download finished). */
    public void send(Player p) {
        byte[] h = hash;
        if (h == null || sendUrl.isEmpty() || !p.isOnline()) return;
        var cfg = plugin.getConfig();
        p.setResourcePack(PACK_ID, sendUrl, h,
                plugin.msg().parse(cfg.getString("resource-pack.prompt", "<gray>Soulbound SMP mythic sword models")),
                cfg.getBoolean("resource-pack.required", false));
    }

    /** Called on join: waits a moment so the client is fully in, then sends the pack. */
    public void sendOnJoin(Player p) {
        Bukkit.getScheduler().runTaskLater(plugin, () -> send(p), 40L);
    }
}
