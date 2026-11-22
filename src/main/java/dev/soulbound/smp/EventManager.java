package dev.soulbound.smp;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
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
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/** Runs the rotating world events (blessings, curses and the rare SUPER HELL) and the effects that need ticking. */
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
    private SoulEvent queued;
    private SoulEvent pending;
    private int countdown;
    private int milestone;
    private static final int[] MILESTONES = {300, 60, 10};

    public EventManager(SoulboundPlugin plugin) {
        this.plugin = plugin;
        this.drainKey = new NamespacedKey(plugin, "soul_drain");
    }

    private FileConfiguration cfg() { return plugin.getConfig(); }

    // ------------------------------------------------------------------ timing settings

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

    /** Super Hell always uses its own short duration (default 30 minutes). */
    public long durationFor(SoulEvent e) {
        if (e == SoulEvent.SUPER_HELL) {
            return (long) (Math.max(1.0, cfg().getDouble("events.super-hell.duration-minutes", 30)) * 60_000L);
        }
        return durationMillis();
    }

    public void setIntervalHours(double hours) {
        cfg().set("events.interval-hours", Math.max(1.0 / 60.0, Math.round(hours * 1000) / 1000.0));
        plugin.saveConfig();
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
        queued = SoulEvent.byId(y.getString("event.queued"));
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

    public boolean isHell() { return current == SoulEvent.SUPER_HELL; }

    /** During Super Hell deaths do not eliminate anyone - players just respawn normally. */
    public boolean deathsAreSafe() {
        return isHell() && cfg().getBoolean("events.super-hell.no-elimination", true);
    }

    /** True if this event's effect is running. SUPER HELL switches on every curse at once. */
    public boolean isActive(SoulEvent e) {
        if (current == e) return true;
        if (current != SoulEvent.SUPER_HELL || e.category != SoulEvent.Category.BAD) return false;
        return e != SoulEvent.THE_SILENCE || cfg().getBoolean("events.super-hell.include-silence", true);
    }

    public long remainingSeconds() { return current == null ? 0 : Math.max(0, (endsAt - System.currentTimeMillis()) / 1000); }

    public boolean enabled() { return cfg().getBoolean("events.enabled", true); }

    // ------------------------------------------------------------------ strengths (Super Hell turns everything up)

    private double num(String path, double def, String hellPath, double hellDef) {
        return isHell() ? cfg().getDouble(hellPath, hellDef) : cfg().getDouble(path, def);
    }

    public double damageMultiplier() {
        return num("events.blood-moon.damage-multiplier", 2.0, "events.super-hell.damage-multiplier", 3.0);
    }

    private double hungerMultiplier() {
        return Math.max(1.0, num("events.hunger-games.multiplier", 3.0, "events.super-hell.hunger-multiplier", 5.0));
    }

    private int hearts() {
        int h = isHell() ? cfg().getInt("events.super-hell.hearts", 2) : cfg().getInt("events.soul-drain.hearts", 1);
        return Math.max(1, Math.min(9, h));
    }

    public double beastBonusChance() {
        return num("events.beast-awakening.bonus-spawn-chance", 0.25, "events.super-hell.bonus-spawn-chance", 0.6);
    }

    public int bountifulMultiplier() { return Math.max(1, cfg().getInt("events.bountiful-drops.multiplier", 2)); }

    public double xpMultiplier() { return Math.max(1.0, cfg().getDouble("events.wisdom-of-the-dead.multiplier", 2.0)); }

    // ------------------------------------------------------------------ scheduling

    private void checkSchedule() {
        long now = System.currentTimeMillis();
        if (current != null && now >= endsAt) {
            if (isHell()) {
                plugin.msg().broadcast("<green><bold>SUPER HELL HAS ENDED.</bold><gray> You survived.");
                plugin.msg().titleAll("<green><bold>YOU SURVIVED</bold>", "<gray>Super Hell has ended");
                for (Player p : Bukkit.getOnlinePlayers()) {
                    p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
                    celebrate(p);
                }
            } else {
                plugin.msg().broadcast(current.color + "<bold>" + current.display + "</bold><gray> has ended.");
            }
            endCurrent();
        }
        if (enabled() && pending == null && now >= nextRollAt) rollWithCountdown();
    }

    private List<SoulEvent> candidates(List<SoulEvent> disabled, SoulEvent.Category cat, boolean excludeRecent) {
        List<SoulEvent> out = new ArrayList<>();
        for (SoulEvent e : SoulEvent.values()) {
            if (e == SoulEvent.SUPER_HELL || disabled.contains(e)) continue;
            if (cat != null && e.category != cat) continue;
            if (excludeRecent && (e == current || e == previous)) continue;
            out.add(e);
        }
        return out;
    }

    /** Chooses the next event: a queued one first, otherwise random (rare Super Hell, then blessing/curse). */
    private SoulEvent pickEvent() {
        if (queued != null) {
            SoulEvent q = queued;
            queued = null;
            persist();
            return q;
        }
        List<SoulEvent> disabled = new ArrayList<>();
        for (String id : cfg().getStringList("events.disabled")) {
            SoulEvent e = SoulEvent.byId(id);
            if (e != null) disabled.add(e);
        }
        ThreadLocalRandom rnd = ThreadLocalRandom.current();

        if (!disabled.contains(SoulEvent.SUPER_HELL) && current != SoulEvent.SUPER_HELL && previous != SoulEvent.SUPER_HELL
                && rnd.nextDouble() < cfg().getDouble("events.super-hell-chance", 0.08)) {
            return SoulEvent.SUPER_HELL;
        }

        SoulEvent.Category wanted = rnd.nextDouble() < cfg().getDouble("events.good-event-chance", 0.3)
                ? SoulEvent.Category.GOOD : SoulEvent.Category.BAD;
        List<SoulEvent> pool = candidates(disabled, wanted, true);
        if (pool.isEmpty()) pool = candidates(disabled, wanted, false);
        if (pool.isEmpty()) pool = candidates(disabled, null, false);
        if (pool.isEmpty()) return null;
        return pool.get(rnd.nextInt(pool.size()));
    }

    /** Rolls and starts the next event instantly (no countdown). */
    public void roll() {
        SoulEvent e = pickEvent();
        if (e != null) setEvent(e);
    }

    /** Rolls the next event with a suspense countdown (Super Hell is announced by name, others are a surprise). */
    public void rollWithCountdown() {
        if (pending != null) return;
        SoulEvent e = pickEvent();
        if (e == null) {
            nextRollAt = System.currentTimeMillis() + intervalMillis();
            persist();
            return;
        }
        int warn = Math.max(0, cfg().getInt("events.warning-seconds", 10));
        if (warn == 0) {
            setEvent(e);
            return;
        }
        pending = e;
        countdown = warn;
        if (e == SoulEvent.SUPER_HELL) plugin.msg().broadcast("<dark_red><bold>☠ SOMETHING TERRIBLE IS COMING... brace yourselves.</bold>");
        else plugin.msg().broadcast("<gray>A new event is about to begin...");
    }

    private void countdownTick() {
        if (pending == null) return;
        if (countdown <= 0) {
            SoulEvent e = pending;
            pending = null;
            setEvent(e);
            return;
        }
        boolean hell = pending == SoulEvent.SUPER_HELL;
        plugin.msg().titleAll((hell ? "<dark_red>" : "<yellow>") + "<bold>" + countdown + "</bold>",
                hell ? "<red>SUPER HELL is about to begin" : "<gray>A new event is about to begin", 0, 900, 100);
        float pitch = hell ? 0.5f : (countdown <= 3 ? 1.5f : 1.0f);
        for (Player p : Bukkit.getOnlinePlayers()) p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1f, pitch);
        countdown--;
    }

    /** Queue the next scheduled event (e.g. to plan a video moment). null clears the queue. */
    public void setQueued(SoulEvent e) {
        queued = e;
        persist();
    }

    public SoulEvent queued() { return queued; }

    /** Starts an event with its normal duration (Super Hell: 30 min, then a normal event follows). */
    public void setEvent(SoulEvent e) { startEvent(e, durationFor(e), e == SoulEvent.SUPER_HELL); }

    /** Starts an event with a custom duration; the next random event begins when it ends. */
    public void setEvent(SoulEvent e, long durationMillis) { startEvent(e, durationMillis, true); }

    private void startEvent(SoulEvent e, long durationMillis, boolean forcedStart) {
        if (current != null) endCurrent();
        long now = System.currentTimeMillis();
        pending = null;
        current = e;
        totalMillis = durationMillis;
        startedAt = now;
        endsAt = now + durationMillis;
        forced = forcedStart;
        // after Super Hell give everyone a 30 s breather before the next event
        nextRollAt = forcedStart ? endsAt + (e == SoulEvent.SUPER_HELL ? 30_000L : 0L) : now + intervalMillis();
        milestone = 0;
        while (milestone < MILESTONES.length && MILESTONES[milestone] >= durationMillis / 1000) milestone++;
        persist();

        for (Player p : Bukkit.getOnlinePlayers()) {
            p.showBossBar(bar);
            syncPlayer(p);
        }
        updateBar();
        if (isActive(SoulEvent.ETERNAL_NIGHT)) night();

        String desc = describe(e);
        long secs = durationMillis / 1000;
        boolean hell = e == SoulEvent.SUPER_HELL;
        boolean good = e.category == SoulEvent.Category.GOOD;
        if (hell) {
            plugin.msg().broadcast("<dark_red><bold>☠☠☠ SUPER HELL HAS BEGUN ☠☠☠</bold>");
            plugin.msg().broadcast("<red>" + desc + ". <gray>It lasts only <white>" + Msg.clock(secs) + "<gray>. <red>Survive.");
        } else {
            plugin.msg().broadcast(e.color + "<bold>" + (good ? "✦ BLESSING: " : "☠ EVENT: ") + e.display.toUpperCase()
                    + "</bold><gray> - " + desc + " <dark_gray>(" + Msg.clock(secs) + ")");
        }
        plugin.msg().titleAll(e.color + "<bold>" + e.display.toUpperCase() + "</bold>", "<gray>" + desc);
        if (cfg().getBoolean("events.announce-sound", true)) {
            Sound snd = hell ? Sound.ENTITY_WITHER_SPAWN : good ? Sound.UI_TOAST_CHALLENGE_COMPLETE : Sound.ENTITY_ENDER_DRAGON_GROWL;
            float pitch = hell ? 0.5f : good ? 1.0f : 0.6f;
            for (Player p : Bukkit.getOnlinePlayers()) p.playSound(p.getLocation(), snd, hell ? 1.0f : 0.6f, pitch);
        }
        intro(e);
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
        pending = null;
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
        y.set("event.queued", queued == null ? null : queued.id);
        plugin.data().save();
    }

    public String describe(SoulEvent e) {
        FileConfiguration c = cfg();
        return switch (e) {
            case BLOOD_MOON -> "Mobs deal " + Msg.num(c.getDouble("events.blood-moon.damage-multiplier", 2.0)) + "x damage";
            case SOUL_DRAIN -> {
                int h = Math.max(1, Math.min(9, c.getInt("events.soul-drain.hearts", 1)));
                yield "Every player loses " + h + (h == 1 ? " heart" : " hearts");
            }
            case HUNGER_GAMES -> "Hunger drains " + Msg.num(c.getDouble("events.hunger-games.multiplier", 3.0)) + "x faster";
            case BOUNTIFUL_DROPS -> "Mobs drop " + bountifulMultiplier() + "x loot";
            case WISDOM_OF_THE_DEAD -> "Experience is multiplied by " + Msg.num(xpMultiplier());
            case SUPER_HELL -> "Every curse at once, and worse";
            default -> e.defaultDescription;
        };
    }

    private void updateBar() {
        if (current == null) return;
        long left = Math.max(0, endsAt - System.currentTimeMillis());
        bar.color(current.barColor);
        bar.overlay(isHell() ? BossBar.Overlay.NOTCHED_10 : BossBar.Overlay.PROGRESS);
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
            if (isActive(SoulEvent.HUNGER_GAMES)) hunger();
            if (isActive(SoulEvent.BROKEN_BONDS) && ticks % 5 == 0) shields();
            if (isActive(SoulEvent.FRAGILE_WORLD)) {
                int every = Math.max(5, cfg().getInt("events.fragile-world.interval-ticks", 40));
                if (ticks % every == 0) fragile();
            }
        }
        if (ticks % 20 == 0) {
            countdownTick();
            checkSchedule();
            if (current != null) {
                updateBar();
                if (isActive(SoulEvent.ETERNAL_NIGHT)) night();
                blessings();
                if (isHell()) {
                    hellfire();
                    milestones();
                }
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
        double mult = hungerMultiplier();
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
        int attempts = Math.max(1, isHell()
                ? cfg().getInt("events.super-hell.fragile-attempts", 60)
                : cfg().getInt("events.fragile-world.attempts-per-player", 25));
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

    // ------------------------------------------------------------------ blessings (potion-based)

    private void blessings() {
        FileConfiguration c = cfg();
        if (current == SoulEvent.SOUL_SURGE) effect(PotionEffectType.REGENERATION, c.getInt("events.soul-surge.amplifier", 1));
        if (current == SoulEvent.SWIFT_WINDS) effect(PotionEffectType.SPEED, c.getInt("events.swift-winds.amplifier", 1));
        if (current == SoulEvent.ANCIENT_HASTE) effect(PotionEffectType.HASTE, c.getInt("events.ancient-haste.amplifier", 1));
        if (current == SoulEvent.IRON_SKIN) effect(PotionEffectType.RESISTANCE, c.getInt("events.iron-skin.amplifier", 0));
    }

    private void effect(PotionEffectType type, int amplifier) {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getGameMode() == GameMode.SPECTATOR) continue;
            p.addPotionEffect(new PotionEffect(type, 100, Math.max(0, amplifier), true, false, true));
        }
    }

    // ------------------------------------------------------------------ cinematic touches

    /** Visual-only flair when an event begins (no damage). */
    private void intro(SoulEvent e) {
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getGameMode() == GameMode.SPECTATOR) continue;
            if (e == SoulEvent.SUPER_HELL) {
                Location l = p.getLocation().add(rnd.nextInt(-6, 7), 0, rnd.nextInt(-6, 7));
                p.getWorld().strikeLightningEffect(l);
                p.playSound(p.getLocation(), Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 1f, 0.8f);
            } else if (e.category == SoulEvent.Category.GOOD) {
                celebrate(p);
                p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.8f, 1f);
            }
        }
    }

    private void celebrate(Player p) {
        if (p.getGameMode() == GameMode.SPECTATOR) return;
        Location l = p.getLocation().add(0, 1, 0);
        p.getWorld().spawnParticle(Particle.FIREWORK, l, 50, 0.6, 0.8, 0.6, 0.08);
        p.getWorld().spawnParticle(Particle.TOTEM_OF_UNDYING, l, 30, 0.5, 0.8, 0.5, 0.2);
    }

    /** "Super Hell ends in 5:00 / 1:00 / 0:10" */
    private void milestones() {
        long left = remainingSeconds();
        while (milestone < MILESTONES.length && left <= MILESTONES[milestone]) {
            plugin.msg().broadcast("<red>Super Hell ends in <white>" + Msg.clock(MILESTONES[milestone]) + "<red>. Hold on.");
            milestone++;
        }
    }

    // ------------------------------------------------------------------ SUPER HELL extras

    /** Permanent thunderstorm + lightning striking around every player. */
    private void hellfire() {
        double chance = cfg().getDouble("events.super-hell.lightning-chance", 0.04);
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        for (World w : Bukkit.getWorlds()) {
            if (w.getEnvironment() != World.Environment.NORMAL) continue;
            if (!w.hasStorm()) w.setStorm(true);
            if (!w.isThundering()) w.setThundering(true);
        }
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getGameMode() == GameMode.SPECTATOR || p.isDead()) continue;
            World w = p.getWorld();
            if (w.getEnvironment() != World.Environment.NORMAL) continue;
            if (rnd.nextDouble() >= chance) continue;
            int x = p.getLocation().getBlockX() + rnd.nextInt(-8, 9);
            int z = p.getLocation().getBlockZ() + rnd.nextInt(-8, 9);
            if (!w.isChunkLoaded(x >> 4, z >> 4)) continue;
            Location strike = w.getHighestBlockAt(x, z).getLocation().add(0.5, 1, 0.5);
            w.strikeLightning(strike);
        }
    }

    // ------------------------------------------------------------------ Soul Drain

    /** Makes sure the player's max health matches the current event (call on join / respawn / revive). */
    public void syncPlayer(Player p) {
        AttributeInstance inst = p.getAttribute(Attribute.MAX_HEALTH);
        if (inst == null) return;
        for (AttributeModifier m : new ArrayList<>(inst.getModifiers())) {
            if (drainKey.equals(m.getKey())) inst.removeModifier(m);
        }
        if (isActive(SoulEvent.SOUL_DRAIN)) {
            inst.addModifier(new AttributeModifier(drainKey, -2.0 * hearts(), AttributeModifier.Operation.ADD_NUMBER));
            double max = inst.getValue();
            if (p.getHealth() > max) p.setHealth(Math.max(1.0, max));
        }
    }

    // ------------------------------------------------------------------ Beast Awakening

    public void buff(LivingEntity e) {
        scale(e, Attribute.MAX_HEALTH, "beast_health",
                num("events.beast-awakening.health-multiplier", 2.0, "events.super-hell.health-multiplier", 2.5));
        scale(e, Attribute.ATTACK_DAMAGE, "beast_damage",
                num("events.beast-awakening.damage-multiplier", 1.5, "events.super-hell.beast-damage-multiplier", 2.0));
        scale(e, Attribute.MOVEMENT_SPEED, "beast_speed",
                num("events.beast-awakening.speed-multiplier", 1.2, "events.super-hell.speed-multiplier", 1.25));
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
