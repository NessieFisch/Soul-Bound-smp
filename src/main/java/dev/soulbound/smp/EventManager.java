package dev.soulbound.smp;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/** Runs the rotating world events (a new random one every X hours) and the effects that need ticking. */
public final class EventManager {

    private static final Map<Material, Material> INFEST = Map.of(
            Material.STONE, Material.INFESTED_STONE,
            Material.COBBLESTONE, Material.INFESTED_COBBLESTONE,
            Material.STONE_BRICKS, Material.INFESTED_STONE_BRICKS,
            Material.MOSSY_STONE_BRICKS, Material.INFESTED_MOSSY_STONE_BRICKS,
            Material.CRACKED_STONE_BRICKS, Material.INFESTED_CRACKED_STONE_BRICKS,
            Material.CHISELED_STONE_BRICKS, Material.INFESTED_CHISELED_STONE_BRICKS,
            Material.DEEPSLATE, Material.INFESTED_DEEPSLATE);

    private final SoulboundPlugin plugin;
    private final NamespacedKey drainKey;
    private final BossBar bar = BossBar.bossBar(Component.empty(), 1f, BossBar.Color.WHITE, BossBar.Overlay.PROGRESS);
    private final Map<UUID, Float> lastExhaustion = new HashMap<>();

    private SoulEvent current;
    private SoulEvent previous;
    private long endsAt;
    private long totalMillis;
    private long startedAt;
    private long nextRollAt;
    private boolean forced;
    private long ticks;
    private BukkitTask task;

    public EventManager(SoulboundPlugin plugin) {
        this.plugin = plugin;
        this.drainKey = new NamespacedKey(plugin, "soul_drain");
    }

    private FileConfiguration cfg() { return plugin.getConfig(); }

    // ---- timing settings (config: events.interval-hours / events.duration-hours)

    public double intervalHours() { return Math.max(1.0 / 60.0, cfg().getDouble("events.interval-hours", 12)); }

    /** 0 = "until the next event". */
    public double durationHours() { return Math.max(0, cfg().getDouble("events.duration-hours", 0)); }

    public long intervalMillis() { return (long) (intervalHours() * 3_600_000L); }

    /** How long a scheduled event lasts (never longer than the interval). */
    public long durationMillis() {
        double d = durationHours();
        long ms = d <= 0 ? intervalMillis() : (long) (d * 3_600_000L);
        return Math.min(ms, intervalMillis());
    }

    public void setIntervalHours(double hours) {
        cfg().set("events.interval-hours", Math.max(1.0 / 60.0, Math.round(hours * 1000) / 1000.0));
        plugin.saveConfig();
        // Re-base the countdown to the new interval (unless an admin-forced event is running).
        if (startedAt > 0 && !(forced && current != null)) {
            nextRollAt = startedAt + intervalMillis();
            persist();
        }
    }

    public void setDurationHours(double hours) {
        cfg().set("events.duration-hours", Math.max(0, Math.round(hours * 1000) / 1000.0));
        plugin.saveConfig();
    }

    public long nextRollSeconds() { return Math.max(0, (nextRollAt - System.currentTimeMillis()) / 1000); }

    public String timingSummary() {
        String dur = durationHours() <= 0 ? "until the next event" : Msg.hours(Math.min(durationHours(), intervalHours()));
        String next = !enabled() ? "events are disabled" : "next event in <white>" + Msg.clock(nextRollSeconds());
        return "<gray>A new event every <white>" + Msg.hours(intervalHours()) + "<gray>, each lasts <white>" + dur + "<gray> | " + next;
    }

    // ------------------------------------------------------------------ lifecycle

    public void start() {
        YamlConfiguration y = plugin.data().raw();
        current = SoulEvent.byId(y.getString("event.current"));
        previous = SoulEvent.byId(y.getString("event.previous"));
        endsAt = y.getLong("event.ends-at", 0);
        totalMillis = y.getLong("event.total", intervalMillis());
        startedAt = y.getLong("event.started-at", 0);
        forced = y.getBoolean("event.forced", false);
        nextRollAt = y.getLong("event.next-roll", -1);
        if (nextRollAt < 0) nextRollAt = current != null ? endsAt : 0;
        if (current != null) {
            for (Player p : Bukkit.getOnlinePlayers()) {
                p.showBossBar(bar);
                syncPlayer(p);
            }
            updateBar();
        }
        checkSchedule();
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 1L);
    }

    public void shutdown() {
        if (task != null) task.cancel();
        for (Player p : Bukkit.getOnlinePlayers()) p.hideBossBar(bar);
    }

    public SoulEvent current() { return current; }

    public boolean isActive(SoulEvent e) { return current == e; }

    public long remainingSeconds() { return current == null ? 0 : Math.max(0, (endsAt - System.currentTimeMillis()) / 1000); }

    public boolean enabled() { return cfg().getBoolean("events.enabled", true); }

    // ------------------------------------------------------------------ scheduling

    private void checkSchedule() {
        long now = System.currentTimeMillis();
        if (current != null && now >= endsAt) {
            plugin.msg().broadcast(current.color + "<bold>" + current.display + "</bold><gray> has ended.");
            endCurrent();
        }
        if (enabled() && now >= nextRollAt) roll();
    }

    public void roll() {
        List<SoulEvent> disabled = new ArrayList<>();
        for (String id : cfg().getStringList("events.disabled")) {
            SoulEvent e = SoulEvent.byId(id);
            if (e != null) disabled.add(e);
        }
        List<SoulEvent> pool = new ArrayList<>();
        for (SoulEvent e : SoulEvent.values()) {
            if (!disabled.contains(e) && e != current && e != previous) pool.add(e);
        }
        if (pool.isEmpty()) {
            for (SoulEvent e : SoulEvent.values()) if (!disabled.contains(e)) pool.add(e);
        }
        if (pool.isEmpty()) return;
        startEvent(pool.get(ThreadLocalRandom.current().nextInt(pool.size())), durationMillis(), false);
    }

    /** Starts an event with the configured duration; the next one follows the normal schedule. */
    public void setEvent(SoulEvent e) { startEvent(e, durationMillis(), false); }

    /** Starts an event with a custom duration; the next random event begins when it ends. */
    public void setEvent(SoulEvent e, long durationMillis) { startEvent(e, durationMillis, true); }

    private void startEvent(SoulEvent e, long durationMillis, boolean forcedStart) {
        if (current != null) endCurrent();
        long now = System.currentTimeMillis();
        current = e;
        totalMillis = durationMillis;
        startedAt = now;
        endsAt = now + durationMillis;
        forced = forcedStart;
        nextRollAt = forcedStart ? endsAt : now + intervalMillis();
        persist();

        for (Player p : Bukkit.getOnlinePlayers()) {
            p.showBossBar(bar);
            syncPlayer(p);
        }
        updateBar();
        if (e == SoulEvent.ETERNAL_NIGHT) night();

        String desc = describe(e);
        plugin.msg().broadcast(e.color + "<bold>☠ EVENT: " + e.display.toUpperCase() + "</bold><gray> - " + desc
                + " <dark_gray>(" + Msg.clock(durationMillis / 1000) + ")");
        plugin.msg().titleAll(e.color + "<bold>" + e.display.toUpperCase() + "</bold>", "<gray>" + desc);
        if (cfg().getBoolean("events.announce-sound", true)) {
            for (Player p : Bukkit.getOnlinePlayers()) p.playSound(p.getLocation(), Sound.ENTITY_ENDER_DRAGON_GROWL, 0.6f, 0.6f);
        }
    }

    /** Ends the current event without starting a new one. */
    public void endCurrent() {
        SoulEvent ended = current;
        if (ended == null) return;
        current = null;
        previous = ended;
        lastExhaustion.clear();
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.hideBossBar(bar);
            syncPlayer(p);
        }
        persist();
    }

    /** Admin stop: no new event until a full interval has passed. */
    public void stop() {
        endCurrent();
        long now = System.currentTimeMillis();
        startedAt = now;
        forced = false;
        nextRollAt = now + intervalMillis();
        persist();
    }

    private void persist() {
        YamlConfiguration y = plugin.data().raw();
        y.set("event.current", current == null ? null : current.id);
        y.set("event.previous", previous == null ? null : previous.id);
        y.set("event.ends-at", endsAt);
        y.set("event.total", totalMillis);
        y.set("event.started-at", startedAt);
        y.set("event.next-roll", nextRollAt);
        y.set("event.forced", forced);
        plugin.data().save();
    }

    public String describe(SoulEvent e) {
        FileConfiguration c = cfg();
        return switch (e) {
            case BLOOD_MOON -> "Mobs deal " + Msg.num(c.getDouble("events.blood-moon.damage-multiplier", 2.0)) + "x damage";
            case SOUL_DRAIN -> {
                int h = hearts();
                yield "Every player loses " + h + (h == 1 ? " heart" : " hearts");
            }
            case HUNGER_GAMES -> "Hunger drains " + Msg.num(c.getDouble("events.hunger-games.multiplier", 3.0)) + "x faster";
            default -> e.defaultDescription;
        };
    }

    private void updateBar() {
        if (current == null) return;
        long left = Math.max(0, endsAt - System.currentTimeMillis());
        bar.color(current.barColor);
        bar.progress(Math.max(0f, Math.min(1f, totalMillis <= 0 ? 0f : (float) left / (float) totalMillis)));
        bar.name(plugin.msg().parse(current.color + "<bold>" + current.display + "</bold> <gray>- " + describe(current)
                + " <dark_gray>| <white>" + Msg.clock(left / 1000)));
    }

    public void onJoin(Player p) {
        if (current != null) p.showBossBar(bar);
        syncPlayer(p);
    }

    public void onQuit(Player p) { lastExhaustion.remove(p.getUniqueId()); }

    // ------------------------------------------------------------------ ticking

    private void tick() {
        ticks++;
        if (current != null) {
            switch (current) {
                case HUNGER_GAMES -> hunger();
                case BROKEN_BONDS -> { if (ticks % 5 == 0) shields(); }
                case FRAGILE_WORLD -> {
                    int every = Math.max(5, cfg().getInt("events.fragile-world.interval-ticks", 40));
                    if (ticks % every == 0) fragile();
                }
                default -> { }
            }
        }
        if (ticks % 20 == 0) {
            checkSchedule();
            if (current != null) {
                updateBar();
                if (current == SoulEvent.ETERNAL_NIGHT) night();
            }
        }
    }

    private void night() {
        for (World w : Bukkit.getWorlds()) {
            if (w.getEnvironment() == World.Environment.NORMAL) w.setTime(18000L);
        }
    }

    private void shields() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.isBlocking()) p.clearActiveItem();
            p.setCooldown(Material.SHIELD, 20);
        }
    }

    /** Multiplies hunger exhaustion by tracking how much each player gained since last tick. */
    private void hunger() {
        double mult = Math.max(1.0, cfg().getDouble("events.hunger-games.multiplier", 3.0));
        for (Player p : Bukkit.getOnlinePlayers()) {
            GameMode gm = p.getGameMode();
            if (gm != GameMode.SURVIVAL && gm != GameMode.ADVENTURE) {
                lastExhaustion.remove(p.getUniqueId());
                continue;
            }
            float ex = p.getExhaustion();
            Float last = lastExhaustion.get(p.getUniqueId());
            if (last != null) {
                float d = ex - last;
                if (d < 0) d += 4f; // vanilla subtracts 4 when exhaustion rolls over
                if (d > 0f && d < 4f) p.setExhaustion(ex + (float) (d * (mult - 1.0)));
            }
            lastExhaustion.put(p.getUniqueId(), p.getExhaustion());
        }
    }

    private void fragile() {
        int radius = Math.max(2, cfg().getInt("events.fragile-world.radius", 12));
        int attempts = Math.max(1, cfg().getInt("events.fragile-world.attempts-per-player", 25));
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getGameMode() == GameMode.SPECTATOR) continue;
            World w = p.getWorld();
            int px = p.getLocation().getBlockX(), py = p.getLocation().getBlockY(), pz = p.getLocation().getBlockZ();
            for (int i = 0; i < attempts; i++) {
                int x = px + rnd.nextInt(-radius, radius + 1);
                int y = py + rnd.nextInt(-radius, radius + 1);
                int z = pz + rnd.nextInt(-radius, radius + 1);
                if (y < w.getMinHeight() || y >= w.getMaxHeight()) continue;
                if (!w.isChunkLoaded(x >> 4, z >> 4)) continue;
                Block b = w.getBlockAt(x, y, z);
                Material to = INFEST.get(b.getType());
                if (to != null) b.setType(to, false);
            }
        }
    }

    // ------------------------------------------------------------------ Soul Drain

    private int hearts() { return Math.max(1, Math.min(9, cfg().getInt("events.soul-drain.hearts", 1))); }

    /** Makes sure the player's max health matches the current event (call on join / respawn / revive). */
    public void syncPlayer(Player p) {
        AttributeInstance inst = p.getAttribute(Attribute.MAX_HEALTH);
        if (inst == null) return;
        for (AttributeModifier m : new ArrayList<>(inst.getModifiers())) {
            if (drainKey.equals(m.getKey())) inst.removeModifier(m);
        }
        if (current == SoulEvent.SOUL_DRAIN) {
            inst.addModifier(new AttributeModifier(drainKey, -2.0 * hearts(), AttributeModifier.Operation.ADD_NUMBER));
            double max = inst.getValue();
            if (p.getHealth() > max) p.setHealth(Math.max(1.0, max));
        }
    }

    // ------------------------------------------------------------------ Beast Awakening

    public void buff(LivingEntity e) {
        FileConfiguration c = cfg();
        scale(e, Attribute.MAX_HEALTH, "beast_health", c.getDouble("events.beast-awakening.health-multiplier", 2.0));
        scale(e, Attribute.ATTACK_DAMAGE, "beast_damage", c.getDouble("events.beast-awakening.damage-multiplier", 1.5));
        scale(e, Attribute.MOVEMENT_SPEED, "beast_speed", c.getDouble("events.beast-awakening.speed-multiplier", 1.2));
        AttributeInstance hp = e.getAttribute(Attribute.MAX_HEALTH);
        if (hp != null) e.setHealth(hp.getValue());
    }

    private void scale(LivingEntity e, Attribute attr, String key, double mult) {
        if (mult == 1.0) return;
        AttributeInstance inst = e.getAttribute(attr);
        if (inst == null) return;
        NamespacedKey k = new NamespacedKey(plugin, key);
        for (AttributeModifier m : new ArrayList<>(inst.getModifiers())) {
            if (k.equals(m.getKey())) inst.removeModifier(m);
        }
        inst.addModifier(new AttributeModifier(k, mult - 1.0, AttributeModifier.Operation.ADD_SCALAR));
    }
}
