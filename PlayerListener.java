package dev.soulbound.smp;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

/** Hardcore rules: elimination, spectating, login handling. */
public final class PlayerListener implements Listener {

    private final SoulboundPlugin plugin;

    public PlayerListener(SoulboundPlugin plugin) { this.plugin = plugin; }

    /** Keeps eliminated players in spectator mode no matter what. */
    public void startEnforcement() {
        Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            for (Player p : Bukkit.getOnlinePlayers()) {
                PlayerRecord r = plugin.data().find(p.getUniqueId());
                if (r != null && r.eliminated && !r.pendingRevive && !p.isDead()
                        && p.getGameMode() != GameMode.SPECTATOR) {
                    p.setGameMode(GameMode.SPECTATOR);
                }
            }
        }, 40L, 40L);
    }

    // ------------------------------------------------------------------ login

    @EventHandler
    public void onPreLogin(AsyncPlayerPreLoginEvent e) {
        PlayerRecord r = plugin.data().find(e.getUniqueId());
        if (r != null && r.permanent) {
            e.disallow(AsyncPlayerPreLoginEvent.Result.KICK_BANNED,
                    Component.text("You are permanently eliminated from Soulbound SMP.", NamedTextColor.DARK_RED));
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        PlayerRecord r = plugin.data().record(p);
        plugin.beacon().discover(p);
        plugin.seal().discover(p);
        plugin.legendary().discover(p);
        plugin.rituals().onJoin(p);
        plugin.events().onJoin(p);

        if (r.permanent) {
            p.kick(Component.text("You are permanently eliminated from Soulbound SMP.", NamedTextColor.DARK_RED));
            return;
        }
        if (r.pendingRevive) {
            Location loc = r.pendingLocation != null ? r.pendingLocation : Bukkit.getWorlds().get(0).getSpawnLocation();
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (p.isOnline()) plugin.rituals().applyRevive(p, r, loc);
            }, 5L);
            return;
        }
        if (r.eliminated) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (!p.isOnline()) return;
                p.setGameMode(GameMode.SPECTATOR);
                plugin.msg().send(p, r.reviveUsed
                        ? "<red>You are eliminated and have no revive left."
                        : "<red>You are eliminated. <gray>Your team can bring you back with a Revive Beacon ritual.");
            }, 5L);
        }
        plugin.data().save();
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        plugin.events().onQuit(e.getPlayer());
    }

    // ------------------------------------------------------------------ death / respawn

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent e) {
        Player p = e.getEntity();
        if (p.hasPermission("soulbound.bypass")) return;

        // SUPER HELL: dying costs nothing, you just respawn normally.
        if (plugin.events().deathsAreSafe()) {
            plugin.msg().send(p, "<red>You died during <dark_red><bold>SUPER HELL</bold><red>. <gray>No elimination - you respawn normally.");
            long hellDelay = plugin.getConfig().getLong("events.super-hell.respawn-delay-ticks", 20L);
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (p.isOnline() && p.isDead()) p.spigot().respawn();
            }, hellDelay);
            return;
        }

        PlayerRecord r = plugin.data().record(p);
        boolean finalDeath = r.reviveUsed;
        boolean ban = finalDeath && plugin.getConfig().getBoolean("hardcore.ban-on-final-death", true);

        r.eliminated = true;
        r.deathLocation = p.getLocation().clone();
        if (ban) r.permanent = true;
        plugin.data().save();

        if (finalDeath) {
            plugin.msg().broadcast("<dark_red><bold>" + Msg.esc(p.getName()) + " HAS FALLEN FOR THE FINAL TIME.</bold> <gray>Their revive was already spent.");
        } else {
            plugin.msg().broadcast("<red>" + Msg.esc(p.getName()) + " has been eliminated. <gray>Their soul can still be called back with a <light_purple>Revive Beacon<gray>.");
        }

        long respawnDelay = plugin.getConfig().getLong("hardcore.auto-respawn-delay-ticks", 40L);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (p.isOnline() && p.isDead()) p.spigot().respawn();
        }, respawnDelay);

        if (ban) {
            long banDelay = plugin.getConfig().getLong("hardcore.ban-delay-ticks", 100L);
            String name = p.getName();
            Bukkit.getScheduler().runTaskLater(plugin, () ->
                    plugin.banPlayer(name, "SOULBOUND SMP: You died with no revive left. You are permanently eliminated."),
                    respawnDelay + banDelay);
        }
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) {
        Player p = e.getPlayer();
        PlayerRecord r = plugin.data().find(p.getUniqueId());
        if (r != null && r.eliminated) {
            if (r.deathLocation != null) e.setRespawnLocation(r.deathLocation);
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (p.isOnline()) p.setGameMode(GameMode.SPECTATOR);
            }, 1L);
        } else {
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (!p.isOnline()) return;
                plugin.events().syncPlayer(p);
                // a hardcore world would drop them into spectator; they are not eliminated, so play on
                if (p.getGameMode() == GameMode.SPECTATOR) p.setGameMode(GameMode.SURVIVAL);
            }, 1L);
        }
    }

    // ------------------------------------------------------------------ spectator leash

    private int spectatorRadius() { return plugin.getConfig().getInt("hardcore.spectator-radius", 100); }

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        Player p = e.getPlayer();
        if (p.getGameMode() != GameMode.SPECTATOR) return;
        int radius = spectatorRadius();
        if (radius <= 0) return;
        PlayerRecord r = plugin.data().find(p.getUniqueId());
        if (r == null || !r.eliminated || r.deathLocation == null) return;

        Location origin = r.deathLocation;
        Location to = e.getTo();
        if (!origin.getWorld().equals(to.getWorld())) {
            e.setTo(origin);
            return;
        }
        double max = (double) radius * radius;
        if (to.distanceSquared(origin) > max) {
            Location from = e.getFrom();
            if (from.getWorld().equals(origin.getWorld()) && from.distanceSquared(origin) <= max) e.setTo(from);
            else e.setTo(origin);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onSpectateTeleport(PlayerTeleportEvent e) {
        if (e.getCause() != PlayerTeleportEvent.TeleportCause.SPECTATE || spectatorRadius() <= 0) return;
        PlayerRecord r = plugin.data().find(e.getPlayer().getUniqueId());
        if (r != null && r.eliminated) e.setCancelled(true);
    }
}
