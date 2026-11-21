package dev.soulbound.smp;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

/**
 * Handles the whole revive ritual: altar validation, soul selection GUI, the timed ritual,
 * success (revive), and failure (permanent elimination).
 */
public final class RitualManager {

    public static final class Ritual {
        final Location core;
        final UUID target;
        final String targetName;
        final String starter;
        final int total;
        int remaining;
        BossBar bar;
        BukkitTask secondTask;
        BukkitTask fxTask;
        int fxTicks;

        Ritual(Location core, UUID target, String targetName, String starter, int total, int remaining) {
            this.core = core;
            this.target = target;
            this.targetName = targetName;
            this.starter = starter;
            this.total = total;
            this.remaining = remaining;
        }

        public Location core() { return core; }
        public String targetName() { return targetName; }
        public int remaining() { return remaining; }
    }

    public static final class SelectHolder implements InventoryHolder {
        public final Location core;
        Inventory inventory;

        SelectHolder(Location core) { this.core = core; }

        @Override
        public Inventory getInventory() { return inventory; }
    }

    private final SoulboundPlugin plugin;
    private final NamespacedKey targetKey;
    private Ritual active;

    public RitualManager(SoulboundPlugin plugin) {
        this.plugin = plugin;
        this.targetKey = new NamespacedKey(plugin, "soul_target");
    }

    private FileConfiguration cfg() { return plugin.getConfig(); }

    public Ritual active() { return active; }

    // ------------------------------------------------------------------ altar

    public Material platformMaterial() {
        Material m = Material.matchMaterial(cfg().getString("ritual.platform-block", "GOLD_BLOCK"));
        return m == null ? Material.GOLD_BLOCK : m;
    }

    public int platformRadius() { return Math.max(0, cfg().getInt("ritual.platform-radius", 1)); }

    public String altarDescription() {
        int size = platformRadius() * 2 + 1;
        return size + "x" + size + " platform of " + platformMaterial().name().toLowerCase().replace('_', ' ') + "s";
    }

    /** @return null if the altar is valid, otherwise a human-readable problem. */
    public String validateAltar(Block core) {
        if (core.getType() != Material.BEACON) return "The Revive Beacon is missing.";
        Material mat = platformMaterial();
        int r = platformRadius();
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                if (core.getRelative(dx, -1, dz).getType() != mat) {
                    return "The altar is incomplete. Place the beacon on a " + altarDescription() + ".";
                }
            }
        }
        return null;
    }

    private boolean isRitualBlock(Block b) {
        if (active == null) return false;
        Location c = active.core;
        if (!b.getWorld().equals(c.getWorld())) return false;
        int dx = b.getX() - c.getBlockX(), dy = b.getY() - c.getBlockY(), dz = b.getZ() - c.getBlockZ();
        if (dx == 0 && dy == 0 && dz == 0) return true;
        int r = platformRadius();
        return dy == -1 && Math.abs(dx) <= r && Math.abs(dz) <= r;
    }

    private boolean isActiveCore(Block b) {
        if (active == null) return false;
        Location c = active.core;
        return b.getWorld().equals(c.getWorld()) && b.getX() == c.getBlockX()
                && b.getY() == c.getBlockY() && b.getZ() == c.getBlockZ();
    }

    public static String coords(Location l) {
        String dim = switch (l.getWorld().getEnvironment()) {
            case NORMAL -> "Overworld";
            case NETHER -> "Nether";
            case THE_END -> "End";
            default -> l.getWorld().getName();
        };
        return l.getBlockX() + ", " + l.getBlockY() + ", " + l.getBlockZ() + " (" + dim + ")";
    }

    // ------------------------------------------------------------------ interaction / GUI

    /** Player right-clicked a placed Revive Beacon. */
    public void interact(Player p, Block core) {
        Msg msg = plugin.msg();
        if (active != null) {
            if (isActiveCore(core)) {
                msg.send(p, "<dark_purple>The ritual to revive <white>" + Msg.esc(active.targetName)
                        + "<dark_purple> is in progress. <red>" + Msg.clock(active.remaining) + "<gray> remaining.");
            } else {
                msg.send(p, "<red>A ritual is already in progress at <yellow>" + coords(active.core)
                        + "<red>. Only one ritual can run at a time.");
            }
            return;
        }
        String problem = validateAltar(core);
        if (problem != null) {
            msg.send(p, "<red>" + problem);
            return;
        }
        openSelector(p, core);
    }

    private void openSelector(Player p, Block core) {
        List<PlayerRecord> list = new ArrayList<>();
        for (PlayerRecord r : plugin.data().all()) if (r.canBeRevived()) list.add(r);
        list.sort(Comparator.comparing(r -> r.name.toLowerCase()));
        if (list.isEmpty()) {
            plugin.msg().send(p, "<red>There are no fallen souls that can be revived.");
            return;
        }
        if (list.size() > 54) list = list.subList(0, 54);

        SelectHolder holder = new SelectHolder(core.getLocation());
        int size = Math.max(9, ((list.size() + 8) / 9) * 9);
        Inventory inv = Bukkit.createInventory(holder, size, Component.text("Choose a soul to return", NamedTextColor.DARK_PURPLE));
        holder.inventory = inv;

        int minutes = Math.max(1, cfg().getInt("ritual.duration-seconds", 600) / 60);
        for (PlayerRecord rec : list) {
            ItemStack skull = new ItemStack(Material.PLAYER_HEAD);
            SkullMeta sm = (SkullMeta) skull.getItemMeta();
            sm.setOwningPlayer(Bukkit.getOfflinePlayer(rec.uuid));
            sm.displayName(Component.text(rec.name, NamedTextColor.LIGHT_PURPLE).decoration(TextDecoration.ITALIC, false));
            sm.lore(List.of(
                    Component.text("Click to begin the ritual", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                    Component.text("Duration: ~" + minutes + " min", NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false),
                    Component.text("If it is destroyed, they are gone forever.", NamedTextColor.RED).decoration(TextDecoration.ITALIC, false)));
            sm.getPersistentDataContainer().set(targetKey, PersistentDataType.STRING, rec.uuid.toString());
            skull.setItemMeta(sm);
            inv.addItem(skull);
        }
        p.openInventory(inv);
    }

    public UUID targetFrom(ItemStack it) {
        if (it == null || !it.hasItemMeta()) return null;
        String s = it.getItemMeta().getPersistentDataContainer().get(targetKey, PersistentDataType.STRING);
        if (s == null) return null;
        try { return UUID.fromString(s); } catch (IllegalArgumentException ex) { return null; }
    }

    // ------------------------------------------------------------------ start

    public void start(Player starter, Location coreLoc, UUID targetId) {
        Msg msg = plugin.msg();
        DataManager data = plugin.data();
        Block core = coreLoc.getBlock();

        if (active != null) {
            msg.send(starter, "<red>Another ritual is already in progress. Only one ritual can run at a time.");
            return;
        }
        if (!data.isCore(core)) {
            msg.send(starter, "<red>That Revive Beacon is no longer valid.");
            return;
        }
        String problem = validateAltar(core);
        if (problem != null) {
            msg.send(starter, "<red>" + problem);
            return;
        }
        PlayerRecord rec = data.find(targetId);
        if (rec == null || !rec.canBeRevived()) {
            msg.send(starter, "<red>That soul can no longer be revived.");
            return;
        }
        int total = Math.max(10, cfg().getInt("ritual.duration-seconds", 600));
        launch(new Ritual(core.getLocation(), targetId, rec.name, starter.getName(), total, total), true);
    }

    private void launch(Ritual r, boolean fresh) {
        active = r;
        World w = r.core.getWorld();
        if (cfg().getBoolean("ritual.keep-chunk-loaded", true)) {
            w.addPluginChunkTicket(r.core.getBlockX() >> 4, r.core.getBlockZ() >> 4, plugin);
        }
        r.bar = BossBar.bossBar(Component.empty(), 0f, BossBar.Color.PURPLE, BossBar.Overlay.NOTCHED_10);
        updateBar(r);
        for (Player p : Bukkit.getOnlinePlayers()) p.showBossBar(r.bar);

        if (fresh) announceStart(r);
        else plugin.msg().broadcast("<dark_purple>The ritual to revive <white>" + Msg.esc(r.targetName)
                + "<dark_purple> continues at <yellow>" + coords(r.core) + "<dark_purple>.");

        r.secondTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> tickSecond(r), 20L, 20L);
        r.fxTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> effects(r), 5L, 5L);
        saveState(r);
    }

    private void announceStart(Ritual r) {
        Msg msg = plugin.msg();
        boolean showCoords = cfg().getBoolean("ritual.broadcast-coordinates", true);
        String where = showCoords ? " at <yellow>" + coords(r.core) + "<gray>" : "";
        msg.broadcast("<dark_purple><bold>A REVIVAL RITUAL HAS BEGUN!</bold> <white>" + Msg.esc(r.starter)
                + "<gray> is reviving <white>" + Msg.esc(r.targetName) + "<gray>" + where
                + ". Destroy the beacon or altar within <red>" + Msg.clock(r.total) + "<gray> to stop it!");
        msg.titleAll("<dark_purple><bold>RITUAL BEGUN</bold>",
                showCoords ? "<gray>Location: <yellow>" + coords(r.core) : "<gray>Somewhere, a soul is being called back...");
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.playSound(p.getLocation(), Sound.ENTITY_WITHER_SPAWN, 0.8f, 0.7f);
        }
    }

    // ------------------------------------------------------------------ ticking

    private void tickSecond(Ritual r) {
        if (active != r) {
            r.secondTask.cancel();
            return;
        }
        if (validateAltar(r.core.getBlock()) != null) {
            fail("The ritual structure collapsed.", null);
            return;
        }
        r.remaining--;
        updateBar(r);
        if (r.remaining <= 0) {
            succeed();
            return;
        }
        int elapsed = r.total - r.remaining;
        int interval = cfg().getInt("ritual.reminder-interval-seconds", 60);
        if (interval > 0 && elapsed % interval == 0 && cfg().getBoolean("ritual.broadcast-coordinates", true)) {
            plugin.msg().broadcast("<dark_purple>Ritual to revive <white>" + Msg.esc(r.targetName) + "<dark_purple> at <yellow>"
                    + coords(r.core) + "<gray> - <red>" + Msg.clock(r.remaining) + "<gray> left.");
        }
        if (elapsed % 30 == 0) saveState(r);
    }

    private void updateBar(Ritual r) {
        float progress = 1f - (float) r.remaining / (float) r.total;
        r.bar.progress(Math.max(0f, Math.min(1f, progress)));
        boolean showCoords = cfg().getBoolean("ritual.broadcast-coordinates", true);
        r.bar.name(plugin.msg().parse("<dark_purple><bold>RITUAL</bold> <gray>» reviving <white>" + Msg.esc(r.targetName)
                + (showCoords ? " <gray>at <yellow>" + coords(r.core) : "")
                + " <dark_gray>| <red>" + Msg.clock(Math.max(0, r.remaining))));
    }

    private void effects(Ritual r) {
        if (active != r) {
            r.fxTask.cancel();
            return;
        }
        World w = r.core.getWorld();
        Location c = r.core.clone().add(0.5, 0.5, 0.5);
        double base = (r.fxTicks * 0.18);
        for (int i = 0; i < 4; i++) {
            double a = base + i * (Math.PI / 2);
            Location p = c.clone().add(Math.cos(a) * 1.8, 0.2 + ((r.fxTicks + i * 3) % 12) * 0.15, Math.sin(a) * 1.8);
            w.spawnParticle(Particle.SOUL_FIRE_FLAME, p, 2, 0.05, 0.1, 0.05, 0.0);
        }
        w.spawnParticle(Particle.SOUL, c.clone().add(0, 1.2, 0), 3, 0.3, 0.6, 0.3, 0.02);
        if (r.fxTicks % 8 == 0) w.playSound(c, Sound.BLOCK_BEACON_AMBIENT, 2.0f, 0.6f);
        r.fxTicks++;
    }

    // ------------------------------------------------------------------ outcome

    private void succeed() {
        Ritual r = active;
        if (r == null) return;
        finish(r);

        Block coreBlock = r.core.getBlock();
        Location spawn = r.core.clone().add(0.5, 0, 0.5);
        coreBlock.setType(Material.AIR, false);
        plugin.data().removeCore(coreBlock);

        World w = r.core.getWorld();
        w.strikeLightningEffect(spawn);
        w.spawnParticle(Particle.TOTEM_OF_UNDYING, spawn.clone().add(0, 1, 0), 80, 0.5, 1.0, 0.5, 0.3);

        PlayerRecord rec = plugin.data().find(r.target);
        if (rec != null) {
            rec.eliminated = false;
            rec.permanent = false;
            rec.reviveUsed = true; // the ONE revive is now consumed
            Player p = Bukkit.getPlayer(r.target);
            if (p != null) {
                applyRevive(p, rec, spawn);
            } else {
                rec.pendingRevive = true;
                rec.pendingLocation = spawn;
            }
        }
        plugin.data().save();

        plugin.msg().broadcast("<green><bold>THE RITUAL SUCCEEDED!</bold> <white>" + Msg.esc(r.targetName)
                + "<gray> has returned from the dead. Their one revive has been consumed.");
        plugin.msg().titleAll("<green><bold>SOUL RETURNED</bold>", "<gray>" + Msg.esc(r.targetName) + " lives again");
    }

    /** Brings an eliminated player back to life at the given location. */
    public void applyRevive(Player p, PlayerRecord rec, Location loc) {
        rec.eliminated = false;
        rec.permanent = false;
        rec.pendingRevive = false;
        rec.pendingLocation = null;

        p.setGameMode(GameMode.SURVIVAL);
        p.teleport(loc);
        plugin.events().syncPlayer(p);
        for (PotionEffect e : new ArrayList<>(p.getActivePotionEffects())) p.removePotionEffect(e.getType());
        AttributeInstance max = p.getAttribute(Attribute.MAX_HEALTH);
        p.setHealth(max != null ? Math.max(1.0, max.getValue()) : 20.0);
        p.setFoodLevel(20);
        p.setSaturation(20f);
        p.setFireTicks(0);
        p.setFallDistance(0f);
        p.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, 100, 4));
        plugin.msg().title(p, "<green><bold>YOU HAVE RETURNED</bold>", "<gray>Your one revive has been used. Do not die again.");
        p.playSound(p.getLocation(), Sound.ITEM_TOTEM_USE, 1f, 1f);
        plugin.data().save();
    }

    /** Admin revive: no ritual, does not touch the player's revive flag. */
    public void forceRevive(PlayerRecord r) {
        if (r.permanent) plugin.pardonPlayer(r.name);
        Location loc = r.deathLocation != null ? r.deathLocation : Bukkit.getWorlds().get(0).getSpawnLocation();
        r.eliminated = false;
        r.permanent = false;
        Player p = Bukkit.getPlayer(r.uuid);
        if (p != null) {
            applyRevive(p, r, loc);
        } else {
            r.pendingRevive = true;
            r.pendingLocation = loc;
        }
        plugin.data().save();
    }

    /** Ritual failed -> target is permanently eliminated and banned. */
    public void fail(String cause, String breakerName) {
        Ritual r = active;
        if (r == null) return;
        finish(r);

        Block coreBlock = r.core.getBlock();
        if (coreBlock.getType() == Material.BEACON) coreBlock.setType(Material.AIR, false);
        plugin.data().removeCore(coreBlock);

        Location c = r.core.clone().add(0.5, 0.5, 0.5);
        r.core.getWorld().createExplosion(c, 0f, false, false);
        r.core.getWorld().playSound(c, Sound.BLOCK_BEACON_DEACTIVATE, 3f, 0.5f);

        PlayerRecord rec = plugin.data().find(r.target);
        if (rec != null) {
            rec.eliminated = true;
            rec.reviveUsed = true;
            rec.permanent = true;
            rec.pendingRevive = false;
        }
        plugin.data().save();
        plugin.banPlayer(r.targetName, "SOULBOUND SMP: Your revival ritual was destroyed. You are permanently eliminated.");

        String by = breakerName != null ? " by <white>" + Msg.esc(breakerName) + "<red>" : "";
        plugin.msg().broadcast("<dark_red><bold>THE RITUAL WAS DESTROYED</bold>" + by + "<red>! <white>"
                + Msg.esc(r.targetName) + "<red> is permanently eliminated. <dark_gray>(" + Msg.esc(cause) + ")");
        plugin.msg().titleAll("<dark_red><bold>RITUAL DESTROYED</bold>",
                "<gray>" + Msg.esc(r.targetName) + " is gone forever");
        for (Player p : Bukkit.getOnlinePlayers()) p.playSound(p.getLocation(), Sound.ENTITY_WITHER_DEATH, 0.7f, 0.8f);
    }

    /** Admin cancel: no penalty, beacon stays in place. */
    public boolean cancel() {
        Ritual r = active;
        if (r == null) return false;
        finish(r);
        plugin.data().save();
        plugin.msg().broadcast("<gray>The ritual for <white>" + Msg.esc(r.targetName) + "<gray> was cancelled by an admin.");
        return true;
    }

    private void finish(Ritual r) {
        if (r.secondTask != null) r.secondTask.cancel();
        if (r.fxTask != null) r.fxTask.cancel();
        for (Player p : Bukkit.getOnlinePlayers()) p.hideBossBar(r.bar);
        r.core.getWorld().removePluginChunkTicket(r.core.getBlockX() >> 4, r.core.getBlockZ() >> 4, plugin);
        if (active == r) active = null;
        plugin.data().raw().set("ritual", null);
    }

    // ------------------------------------------------------------------ world events

    /** @return true if the break was part of the active ritual (and has been handled). */
    public boolean onBlockBreak(BlockBreakEvent e) {
        Block b = e.getBlock();
        if (active == null || !isRitualBlock(b)) return false;
        if (isActiveCore(b)) e.setDropItems(false);
        String name = e.getPlayer().getName();
        Bukkit.getScheduler().runTask(plugin, () -> fail("A ritual block was broken.", name));
        return true;
    }

    public void handleExplosion(List<Block> blocks) {
        boolean destroyed = false;
        Iterator<Block> it = blocks.iterator();
        while (it.hasNext()) {
            Block b = it.next();
            if (plugin.data().isCore(b)) {
                if (isActiveCore(b)) {
                    it.remove();
                    b.setType(Material.AIR, false); // no vanilla beacon drop
                    destroyed = true;
                } else {
                    it.remove(); // idle Revive Beacons are blast-proof
                }
            } else if (isRitualBlock(b)) {
                destroyed = true;
            }
        }
        if (destroyed) Bukkit.getScheduler().runTask(plugin, () -> fail("The ritual was blown apart.", null));
    }

    // ------------------------------------------------------------------ players / persistence

    public void onJoin(Player p) {
        if (active == null) return;
        p.showBossBar(active.bar);
        plugin.msg().send(p, "<dark_purple>A revival ritual for <white>" + Msg.esc(active.targetName)
                + "<dark_purple> is in progress at <yellow>" + coords(active.core) + "<gray> (" + Msg.clock(active.remaining) + " left).");
    }

    private void saveState(Ritual r) {
        YamlConfiguration y = plugin.data().raw();
        y.set("ritual.core", DataManager.locToString(r.core));
        y.set("ritual.target", r.target.toString());
        y.set("ritual.target-name", r.targetName);
        y.set("ritual.starter", r.starter);
        y.set("ritual.total", r.total);
        y.set("ritual.remaining", r.remaining);
        plugin.data().save();
    }

    public void resume() {
        YamlConfiguration y = plugin.data().raw();
        if (!y.contains("ritual.core")) return;
        Location core = DataManager.stringToLoc(y.getString("ritual.core"));
        String target = y.getString("ritual.target");
        if (core == null || target == null) {
            y.set("ritual", null);
            return;
        }
        Location blockLoc = core.getBlock().getLocation();
        launch(new Ritual(blockLoc, UUID.fromString(target), y.getString("ritual.target-name", "unknown"),
                y.getString("ritual.starter", "unknown"), y.getInt("ritual.total", 600), y.getInt("ritual.remaining", 600)), false);
    }

    /** Server stopping: keep the ritual state on disk so it resumes after restart. */
    public void shutdown() {
        if (active == null) return;
        Ritual r = active;
        saveState(r);
        if (r.secondTask != null) r.secondTask.cancel();
        if (r.fxTask != null) r.fxTask.cancel();
        for (Player p : Bukkit.getOnlinePlayers()) p.hideBossBar(r.bar);
        active = null;
    }
}
