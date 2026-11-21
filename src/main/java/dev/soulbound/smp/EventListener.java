package dev.soulbound.smp;

import io.papermc.paper.event.player.AsyncChatEvent;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityRegainHealthEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.ItemStack;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/** Gameplay effects of the 12-hour events. (Soul Drain, Eternal Night, Fragile World, Hunger Games and Broken Bonds' cooldown are ticked in EventManager.) */
public final class EventListener implements Listener {

    private static final Set<SpawnReason> BEAST_REASONS = EnumSet.of(
            SpawnReason.NATURAL, SpawnReason.SPAWNER, SpawnReason.TRAP, SpawnReason.REINFORCEMENTS,
            SpawnReason.PATROL, SpawnReason.RAID, SpawnReason.VILLAGE_INVASION, SpawnReason.JOCKEY,
            SpawnReason.SILVERFISH_BLOCK);

    private final SoulboundPlugin plugin;

    public EventListener(SoulboundPlugin plugin) { this.plugin = plugin; }

    private boolean active(SoulEvent e) { return plugin.events().isActive(e); }

    // ---- Blood Moon: mobs deal 2x damage
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent e) {
        if (!active(SoulEvent.BLOOD_MOON) || !(e.getEntity() instanceof Player)) return;
        Entity src = e.getDamager();
        if (src instanceof Projectile proj && proj.getShooter() instanceof Entity shooter) src = shooter;
        if (src instanceof Enemy) {
            double mult = plugin.getConfig().getDouble("events.blood-moon.damage-multiplier", 2.0);
            e.setDamage(e.getDamage() * mult);
        }
    }

    // ---- Empty Souls: no natural regeneration
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onRegen(EntityRegainHealthEvent e) {
        if (!active(SoulEvent.EMPTY_SOULS) || !(e.getEntity() instanceof Player)) return;
        EntityRegainHealthEvent.RegainReason reason = e.getRegainReason();
        if (reason == EntityRegainHealthEvent.RegainReason.SATIATED || reason == EntityRegainHealthEvent.RegainReason.REGEN) {
            e.setCancelled(true);
        }
    }

    // ---- No Escape (ender pearls) + Broken Bonds (shields)
    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent e) {
        Action a = e.getAction();
        if (a != Action.RIGHT_CLICK_AIR && a != Action.RIGHT_CLICK_BLOCK) return;
        ItemStack item = e.getItem();
        if (item == null) return;
        if (item.getType() == Material.ENDER_PEARL && active(SoulEvent.NO_ESCAPE)) {
            e.setUseItemInHand(Event.Result.DENY);
            plugin.msg().actionBar(e.getPlayer(), "<aqua>No Escape <gray>- ender pearls are disabled.");
        } else if (item.getType() == Material.SHIELD && active(SoulEvent.BROKEN_BONDS)) {
            e.setUseItemInHand(Event.Result.DENY);
            plugin.msg().actionBar(e.getPlayer(), "<yellow>Broken Bonds <gray>- shields are disabled.");
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent e) {
        if (active(SoulEvent.NO_ESCAPE) && e.getCause() == PlayerTeleportEvent.TeleportCause.ENDER_PEARL) {
            e.setCancelled(true);
        }
    }

    // ---- Beast Awakening
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSpawn(CreatureSpawnEvent e) {
        if (!active(SoulEvent.BEAST_AWAKENING)) return;
        if (!(e.getEntity() instanceof Enemy) || !BEAST_REASONS.contains(e.getSpawnReason())) return;

        plugin.events().buff(e.getEntity());

        double chance = plugin.getConfig().getDouble("events.beast-awakening.bonus-spawn-chance", 0.25);
        if (e.getSpawnReason() == SpawnReason.NATURAL && ThreadLocalRandom.current().nextDouble() < chance) {
            // spawned with reason CUSTOM, so it won't trigger this handler again
            Entity extra = e.getLocation().getWorld().spawnEntity(e.getLocation(), e.getEntityType());
            if (extra instanceof LivingEntity le) plugin.events().buff(le);
        }
    }

    // ---- The Silence
    @EventHandler(priority = EventPriority.HIGH)
    public void onChat(AsyncChatEvent e) {
        if (!active(SoulEvent.THE_SILENCE)) return;
        e.viewers().removeIf(a -> a instanceof Player); // console still logs it
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTask(plugin, () ->
                plugin.msg().actionBar(p, "<dark_gray>The Silence swallows your words..."));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent e) {
        if (!active(SoulEvent.THE_SILENCE)) return;
        String raw = e.getMessage();
        if (raw.length() < 2) return;
        String label = raw.substring(1).split(" ", 2)[0].toLowerCase(Locale.ROOT);
        int colon = label.indexOf(':');
        if (colon >= 0) label = label.substring(colon + 1);
        if (plugin.getConfig().getStringList("events.the-silence.blocked-commands").contains(label)) {
            e.setCancelled(true);
            plugin.msg().actionBar(e.getPlayer(), "<dark_gray>The Silence swallows your words...");
        }
    }
}
