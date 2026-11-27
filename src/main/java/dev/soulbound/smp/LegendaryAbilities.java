package dev.soulbound.smp;

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
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Item;
import org.bukkit.entity.LargeFireball;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Tameable;
import org.bukkit.entity.Trident;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;

/** The powers, passives and visual effects of the legendary weapons. */
public final class LegendaryAbilities {

    private static final Set<EntityType> UNDEAD = EnumSet.of(
            EntityType.ZOMBIE, EntityType.ZOMBIE_VILLAGER, EntityType.HUSK, EntityType.DROWNED,
            EntityType.SKELETON, EntityType.STRAY, EntityType.BOGGED, EntityType.WITHER_SKELETON,
            EntityType.WITHER, EntityType.PHANTOM, EntityType.ZOMBIFIED_PIGLIN, EntityType.ZOGLIN,
            EntityType.SKELETON_HORSE, EntityType.ZOMBIE_HORSE);

    /** Every ailment the Benediction cleanses and the Malediction deals out. */
    private static final List<PotionEffectType> CURSES = List.of(
            PotionEffectType.POISON, PotionEffectType.WITHER, PotionEffectType.SLOWNESS, PotionEffectType.WEAKNESS,
            PotionEffectType.BLINDNESS, PotionEffectType.NAUSEA, PotionEffectType.HUNGER,
            PotionEffectType.MINING_FATIGUE, PotionEffectType.DARKNESS);

    /** A player flying through the air after a leap-type power; resolved when they land. */
    private static final class Slam {
        final long start = System.currentTimeMillis();
        final LegendaryWeapon weapon;
        double maxY;

        Slam(double y, LegendaryWeapon weapon) {
            this.maxY = y;
            this.weapon = weapon;
        }
    }

    private static final class Homing {
        final long start = System.currentTimeMillis();
        final UUID shooter;

        Homing(UUID shooter) { this.shooter = shooter; }
    }

    /** A flying projectile that paints a trail behind it. */
    private static final class Trail {
        final Consumer<Location> brush;
        Location last;

        Trail(Consumer<Location> brush) { this.brush = brush; }
    }

    private record Landing(Player player, LegendaryWeapon weapon, double fall) { }

    private final SoulboundPlugin plugin;
    private final LegendaryFx fx;
    private final NamespacedKey projKey;
    private final Map<UUID, Slam> slams = new HashMap<>();
    private final Map<UUID, Homing> homing = new HashMap<>();
    private final Map<UUID, Trail> trails = new HashMap<>();
    /** Shadowfang: players whose next strike is empowered, until the given time. */
    private final Map<UUID, Long> ambush = new HashMap<>();
    /** Titan's Wrath: players currently raging, until the given time. */
    private final Map<UUID, Long> rage = new HashMap<>();
    /** Ability messages show on the action bar; keep the HUD from overwriting them straight away. */
    private final Map<UUID, Long> quiet = new HashMap<>();
    private final Map<UUID, Long> lastSwing = new HashMap<>();
    /** Dropped legendary items, which get a pillar of light. */
    private final Set<UUID> drops = new HashSet<>();
    /** God Elytra: the highest point of the current glide, so altitude lost counts as fall distance. */
    private final Map<UUID, Double> peak = new HashMap<>();
    private final Map<UUID, Long> lastBoom = new HashMap<>();
    /** True while a power is dealing damage itself, so our own damage hooks do not re-trigger. */
    private boolean internal;
    private BukkitTask task;
    private int ticks;

    public LegendaryAbilities(SoulboundPlugin plugin) {
        this.plugin = plugin;
        this.fx = new LegendaryFx(plugin);
        this.projKey = new NamespacedKey(plugin, "legendary_projectile");
    }

    public LegendaryFx fx() { return fx; }

    public void start() {
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 2L, 2L);
    }

    public void shutdown() {
        if (task != null) task.cancel();
        slams.clear();
        homing.clear();
        trails.clear();
        ambush.clear();
        rage.clear();
        quiet.clear();
        lastSwing.clear();
        drops.clear();
        peak.clear();
        lastBoom.clear();
        fx.cleanup();
    }

    public boolean isInternal() { return internal; }

    public boolean isSlamming(Player p) { return slams.containsKey(p.getUniqueId()); }

    public void trackDrop(Item item) { drops.add(item.getUniqueId()); }

    // ------------------------------------------------------------------ ticking

    private void tick() {
        try {
            ticks++;
            tickSlams();
            tickHoming();
            tickTrails();
            tickElytra();
            if (fx.enabled("aura")) auras();
            if (ticks % 5 == 0) {
                passives();
                if (fx.enabled("hud")) hud();
                beacons();
            }
        } catch (Exception ex) {
            plugin.getLogger().warning("Legendary tick error: " + ex);
        }
    }

    private void say(Player p, String mini) {
        plugin.msg().actionBar(p, mini);
        quiet.put(p.getUniqueId(), System.currentTimeMillis() + 2000L);
    }

    // ------------------------------------------------------------------ ambient visuals

    private void auras() {
        long now = System.currentTimeMillis();
        for (Player p : Bukkit.getOnlinePlayers()) {
            LegendaryWeapon w = plugin.legendary().held(p);
            if (w != null) heldAura(p, w);
            if (ticks % 5 == 0 && !p.isGliding() && plugin.legendary().worn(p) == LegendaryWeapon.GOD_ELYTRA) wingShimmer(p);
            Long until = rage.get(p.getUniqueId());
            if (until != null) {
                if (until < now) rage.remove(p.getUniqueId());
                else rageAura(p);
            }
        }
    }

    /** Orbiting lights in the weapon's colours, plus one signature touch per weapon. */
    private void heldAura(Player p, LegendaryWeapon w) {
        Location base = p.getLocation();
        double t = ticks * 0.3;
        int orbs = 2 + (w.ordinal() % 2);
        Consumer<Location> orb = fx.signature(w, 1, 0.02);
        for (int i = 0; i < orbs; i++) {
            double a = t + i * (Math.PI * 2 / orbs);
            double y = 0.9 + Math.sin(t * 0.7 + i * 2) * 0.35;
            orb.accept(base.clone().add(Math.cos(a) * 0.75, y, Math.sin(a) * 0.75));
        }
        if (ticks % 3 != 0) return;
        Location head = base.clone().add(0, 2.1, 0);
        switch (w) {
            case GOD_MACE -> fx.ring(head, 0.4, 6, fx.particle(Particle.END_ROD, 1, 0.0, 0.0));
            case DAWNBREAKER -> fx.sp(Particle.FLAME, head, 1, 0.25, 0.05, 0.25, 0.0);
            case SOUL_REAPER -> fx.sp(Particle.SOUL, base.clone().add(0, 0.2, 0), 1, 0.3, 0.1, 0.3, 0.01);
            case VOIDPIERCER -> fx.sp(Particle.PORTAL, base.clone().add(0, 1.0, 0), 4, 0.5, 0.7, 0.5, 0.4);
            case TITAN_WRATH -> fx.sp(Particle.CRIT, base.clone().add(0, 1.6, 0), 2, 0.4, 0.2, 0.4, 0.1);
            case STORMCALLER -> {
                Location a1 = base.clone().add(Math.cos(t) * 0.75, 1.0, Math.sin(t) * 0.75);
                Location a2 = base.clone().add(Math.cos(t + 2.5) * 0.75, 1.3, Math.sin(t + 2.5) * 0.75);
                fx.jagged(a1, a2, 3, 0.15, 0.25, fx.particle(Particle.ELECTRIC_SPARK, 1, 0.0, 0.0));
            }
            case HELLFIRE_CROSSBOW -> fx.sp(Particle.LAVA, base.clone().add(0, 0.3, 0), 1, 0.3, 0.1, 0.3, 0.0);
            case FROSTBRAND -> fx.sp(Particle.SNOWFLAKE, head.clone().add(0, 0.5, 0), 3, 0.5, 0.2, 0.5, 0.01);
            case SHADOWFANG -> fx.sp(Particle.SMOKE, base.clone().add(0, 0.15, 0), 3, 0.3, 0.05, 0.3, 0.01);
            case EVENT_HORIZON -> fx.ring(base.clone().add(0, 1.0, 0), 0.9, 6, fx.particle(Particle.REVERSE_PORTAL, 1, 0.0, 0.0));
            case VIPER_FANG -> fx.sp(Particle.ITEM_SLIME, base.clone().add(0, 0.9, 0), 1, 0.5, 0.4, 0.5, 0.0);
            case STARGAZER -> fx.sp(Particle.GLOW, head.clone().add(0, 0.2, 0), 2, 0.4, 0.2, 0.4, 0.05);
            case EXECUTIONER -> fx.sp(Particle.DAMAGE_INDICATOR, base.clone().add(0, 1.8, 0), 1, 0.3, 0.1, 0.3, 0.0);
            case INFERNAL_SCEPTER -> fx.sp(Particle.FLAME, base.clone().add(0, 1.0, 0), 2, 0.5, 0.5, 0.5, 0.01);
            case THUNDER_ROD -> fx.sp(Particle.ELECTRIC_SPARK, head, 2, 0.5, 0.3, 0.5, 0.2);
            case BENEDICTION -> {
                fx.ring(head.clone().add(0, 0.3, 0), 0.45, 10, fx.particle(Particle.END_ROD, 1, 0.0, 0.0)); // a halo
                fx.sp(Particle.HAPPY_VILLAGER, base.clone().add(0, 1.0, 0), 1, 0.5, 0.6, 0.5, 0.0);
            }
            case MALEDICTION -> {
                fx.ring(base.clone().add(0, 0.05, 0), 0.9, 8, fx.dust(w.rgbA, w.rgbB, 1.2f, 1, 0.02)); // a creeping sigil
                fx.sp(Particle.SOUL_FIRE_FLAME, base.clone().add(0, 0.1, 0), 2, 0.4, 0.05, 0.4, 0.01);
                fx.sp(Particle.SMOKE, head, 1, 0.3, 0.1, 0.3, 0.01);
            }
        }
    }

    private void rageAura(Player p) {
        Location l = p.getLocation();
        fx.sp(Particle.FLAME, l.clone().add(0, 1.0, 0), 4, 0.4, 0.7, 0.4, 0.02);
        if (ticks % 4 == 0) {
            fx.ring(l.clone().add(0, 0.1, 0), 0.9, 10, fx.particle(Particle.CRIT, 1, 0.0, 0.1));
            fx.sp(Particle.ANGRY_VILLAGER, l.clone().add(0, 2.1, 0), 1, 0.3, 0.1, 0.3, 0.0);
        }
    }

    /** Fire immunity, poison immunity and end-of-rage cleanup. */
    private void passives() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            LegendaryWeapon w = plugin.legendary().held(p);
            if (w == LegendaryWeapon.INFERNAL_SCEPTER) {
                p.addPotionEffect(new PotionEffect(PotionEffectType.FIRE_RESISTANCE, 60, 0, true, false, false));
            } else if (w == LegendaryWeapon.VIPER_FANG) {
                p.removePotionEffect(PotionEffectType.POISON);
            } else if (w == LegendaryWeapon.BENEDICTION) {
                // the God of Blessings shrugs off every curse, including the world events
                for (PotionEffectType bad : CURSES) p.removePotionEffect(bad);
                p.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, 60, 0, true, false, false));
            } else if (w == LegendaryWeapon.MALEDICTION) {
                p.removePotionEffect(PotionEffectType.WITHER);
            }
        }
    }

    /** Cooldown bar on the action bar while holding a weapon with a power. */
    private void hud() {
        long now = System.currentTimeMillis();
        for (Player p : Bukkit.getOnlinePlayers()) {
            LegendaryWeapon w = plugin.legendary().held(p);
            if (w == null || !w.active || w.power == null) continue;
            Long q = quiet.get(p.getUniqueId());
            if (q != null && q > now) continue;
            long left = plugin.legendary().cooldownLeftMs(p, w);
            String text;
            if (left <= 0) {
                text = w.gradient("<bold>" + w.power + "</bold>") + " <green>\u2714 READY";
            } else {
                long total = Math.max(1L, plugin.legendary().cooldownSeconds(w) * 1000L);
                int filled = (int) Math.round(10.0 * (1.0 - (double) left / total));
                filled = Math.max(0, Math.min(10, filled));
                text = w.color + w.power + " " + w.color + "\u25B0".repeat(filled)
                        + "<dark_gray>" + "\u25B1".repeat(10 - filled) + " <white>" + ((left + 999) / 1000) + "s";
            }
            plugin.msg().actionBar(p, text);
        }
    }

    private void beacons() {
        if (drops.isEmpty() || !fx.enabled("drop-beacons")) return;
        Iterator<UUID> it = drops.iterator();
        while (it.hasNext()) {
            Entity en = Bukkit.getEntity(it.next());
            if (!(en instanceof Item item) || !item.isValid()) {
                it.remove();
                continue;
            }
            LegendaryWeapon w = plugin.legendary().identify(item.getItemStack());
            if (w == null) {
                it.remove();
                continue;
            }
            fx.beacon(item.getLocation(), w);
        }
    }

    // ------------------------------------------------------------------ swing trails

    /** An arm swing with a legendary weapon leaves a coloured slash in the air. */
    public void onSwing(Player p) {
        if (!fx.enabled("swing-arc")) return;
        LegendaryWeapon w = plugin.legendary().held(p);
        if (w == null || w.material == Material.BOW || w.material == Material.CROSSBOW) return;
        long now = System.currentTimeMillis();
        Long last = lastSwing.get(p.getUniqueId());
        if (last != null && now - last < 150L) return;
        lastSwing.put(p.getUniqueId(), now);

        Consumer<Location> brush = fx.combo(fx.dust(w.rgbA, w.rgbB, 1.1f, 1, 0.02), fx.particle(w.accent, 1, 0.03, 0.01));
        boolean overhead = switch (w) {
            case GOD_MACE, TITAN_WRATH, EXECUTIONER, EVENT_HORIZON, THUNDER_ROD, INFERNAL_SCEPTER -> true;
            default -> false;
        };
        if (fx.solid()) {
            List<Location> pts = new ArrayList<>();
            Consumer<Location> collect = pts::add;
            if (overhead) fx.arcVertical(p.getEyeLocation(), 2.3, -45, 75, 6, collect);
            else fx.arc(p.getLocation(), p.getLocation().getDirection(), 2.2, -75, 75, 7, 1.1, collect);
            for (int i = 0; i + 1 < pts.size(); i++) fx.beam(pts.get(i), pts.get(i + 1), 0.14f, fx.core(w), 4);
        }
        if (overhead) {
            fx.arcVertical(p.getEyeLocation(), 2.3, -45, 75, 12, brush);
        } else {
            fx.arc(p.getLocation(), p.getLocation().getDirection(), 2.2, -75, 75, 14, 1.1, brush);
        }
    }

    // ------------------------------------------------------------------ slam / leap tracking

    private void tickSlams() {
        if (slams.isEmpty()) return;
        long now = System.currentTimeMillis();
        List<Landing> landed = new ArrayList<>();
        Iterator<Map.Entry<UUID, Slam>> it = slams.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Slam> en = it.next();
            Player p = Bukkit.getPlayer(en.getKey());
            Slam s = en.getValue();
            if (p == null || !p.isOnline() || p.isDead()) {
                it.remove();
                continue;
            }
            long age = now - s.start;
            s.maxY = Math.max(s.maxY, p.getLocation().getY());
            if (age > 12_000L || p.isInWater() || p.isGliding()) {
                it.remove();
                continue;
            }
            Vector v = p.getVelocity();
            if (fx.enabled("trails")) slamTrail(p, s, v);
            if (s.weapon == LegendaryWeapon.GOD_MACE && p.isSneaking() && age > 300L && v.getY() < 0.0) {
                p.setVelocity(new Vector(v.getX() * 0.5, -2.2, v.getZ() * 0.5)); // dive
            }
            if (age > 400L && p.isOnGround()) {
                it.remove();
                landed.add(new Landing(p, s.weapon, s.maxY - p.getLocation().getY()));
            }
        }
        // resolve outside the iterator
        for (Landing l : landed) {
            if (l.weapon() == LegendaryWeapon.GOD_MACE) shockwave(l.player(), l.fall());
            else leapImpact(l.player());
        }
    }

    private void slamTrail(Player p, Slam s, Vector v) {
        Location l = p.getLocation();
        if (s.weapon == LegendaryWeapon.GOD_MACE) {
            if (v.getY() < -0.3) {
                // comet: a glowing shell with a blazing tail above
                fx.sphere(l.clone().add(0, 1.0, 0), 0.9, fx.n(10), fx.signature(s.weapon, 1, 0.03));
                fx.sp(Particle.FLAME, l.clone().add(0, 2.4, 0), 4, 0.2, 0.5, 0.2, 0.02);
                fx.sp(Particle.CLOUD, l.clone().add(0, 3.0, 0), 3, 0.2, 0.6, 0.2, 0.02);
            } else {
                fx.sp(Particle.CLOUD, l, 3, 0.2, 0.05, 0.2, 0.02);
                fx.ring(l, 0.8, 8, fx.signature(s.weapon, 1, 0.02));
            }
        } else {
            fx.signature(s.weapon, 3, 0.15).accept(l.clone().add(0, 1.0, 0));
        }
    }

    private void tickHoming() {
        if (homing.isEmpty()) return;
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<UUID, Homing>> it = homing.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Homing> en = it.next();
            Homing h = en.getValue();
            long age = now - h.start;
            Entity ent = Bukkit.getEntity(en.getKey());
            if (ent == null) {
                if (age > 1000L) it.remove(); // it may not have spawned yet
                continue;
            }
            if (!(ent instanceof AbstractArrow a) || !a.isValid() || a.isInBlock() || age > 6000L) {
                it.remove();
                continue;
            }
            Player shooter = Bukkit.getPlayer(h.shooter);
            if (shooter == null) {
                it.remove();
                continue;
            }
            Vector vel = a.getVelocity();
            if (vel.lengthSquared() < 0.01) continue;
            double speed = Math.max(1.3, vel.length());
            Vector dir = vel.clone().normalize();
            LivingEntity target = nearestEnemy(shooter, a.getLocation(), dir, 16.0);
            if (target == null) continue;
            Vector to = target.getLocation().add(0, target.getHeight() * 0.6, 0).toVector().subtract(a.getLocation().toVector());
            if (to.lengthSquared() < 0.01) continue;
            a.setVelocity(dir.multiply(0.6).add(to.normalize().multiply(0.4)).normalize().multiply(speed));
        }
    }

    private void tickTrails() {
        if (trails.isEmpty()) return;
        Iterator<Map.Entry<UUID, Trail>> it = trails.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Trail> en = it.next();
            Entity ent = Bukkit.getEntity(en.getKey());
            Trail tr = en.getValue();
            if (ent == null || !ent.isValid() || (ent instanceof AbstractArrow a && a.isInBlock())) {
                it.remove();
                continue;
            }
            Location now = ent.getLocation();
            if (tr.last != null && tr.last.getWorld() == now.getWorld() && tr.last.distanceSquared(now) < 4096.0) {
                fx.line(tr.last, now, 0.4, tr.brush);
            } else {
                tr.brush.accept(now);
            }
            tr.last = now;
        }
    }

    // ------------------------------------------------------------------ helpers

    private boolean validTarget(Player p, Entity en) {
        if (!(en instanceof LivingEntity le) || en.equals(p) || en instanceof ArmorStand) return false;
        if (le.isInvulnerable() || le.isDead()) return false;
        if (le instanceof Player tp && tp.getGameMode() == GameMode.SPECTATOR) return false;
        if (le instanceof Tameable t && p.getUniqueId().equals(t.getOwnerUniqueId())) return false;
        return true;
    }

    /** Nearest hostile mob or player inside a cone around {@code dir}. */
    private LivingEntity nearestEnemy(Player shooter, Location from, Vector dir, double range) {
        LivingEntity best = null;
        double bestDist = Double.MAX_VALUE;
        for (Entity en : from.getWorld().getNearbyEntities(from, range, range, range)) {
            if (!(en instanceof Enemy || en instanceof Player) || !validTarget(shooter, en)) continue;
            Vector to = en.getLocation().add(0, 1.0, 0).toVector().subtract(from.toVector());
            double d = to.lengthSquared();
            if (d < 0.25 || d > range * range) continue;
            if (to.normalize().dot(dir) < 0.8) continue; // roughly in front of the arrow
            if (d < bestDist) {
                bestDist = d;
                best = (LivingEntity) en;
            }
        }
        return best;
    }

    private void heal(Player p, double amount) {
        if (amount <= 0) return;
        AttributeInstance max = p.getAttribute(Attribute.MAX_HEALTH);
        double cap = max != null ? max.getValue() : 20.0;
        p.setHealth(Math.min(cap, p.getHealth() + amount));
    }

    private void hurt(Player by, LivingEntity target, double amount) {
        internal = true;
        try {
            target.damage(amount, by);
        } finally {
            internal = false;
        }
    }

    private void tag(Entity e, LegendaryWeapon w) {
        e.getPersistentDataContainer().set(projKey, PersistentDataType.STRING, w.id);
        if (fx.enabled("trails")) trails.put(e.getUniqueId(), new Trail(fx.trail(w)));
    }

    private LegendaryWeapon tagged(Entity e) {
        String id = e.getPersistentDataContainer().get(projKey, PersistentDataType.STRING);
        return id == null ? null : LegendaryWeapon.byId(id);
    }

    private Material blockBelow(Location l) {
        Material m = l.clone().subtract(0, 0.5, 0).getBlock().getType();
        return m.isBlock() && m.isSolid() ? m : Material.STONE;
    }

    private void knockAway(LivingEntity le, Location from, double strength, double lift) {
        Vector kb = le.getLocation().toVector().subtract(from.toVector());
        kb.setY(0);
        if (kb.lengthSquared() > 0.01) kb.normalize().multiply(strength);
        else kb = new Vector(0, 0, 0);
        kb.setY(lift);
        le.setVelocity(kb);
    }

    /** Wisps of light flowing from a point to a player, e.g. stolen life. */
    private void stream(Location from, Player to, int delay, Consumer<Location> brush) {
        fx.later(delay + 1, () -> fx.animate(10, t -> {
            if (!to.isOnline()) return;
            Vector d = to.getLocation().add(0, 1.0, 0).toVector().subtract(from.toVector());
            double k = (t + 1) / 10.0;
            double wobble = Math.sin(k * Math.PI * 3) * 0.35 * (1 - k);
            Location pt = from.clone().add(d.multiply(k));
            pt.add(wobble, Math.sin(k * Math.PI) * 0.8, wobble);
            brush.accept(pt);
        }));
    }

    // ------------------------------------------------------------------ right-click powers

    public void trigger(Player p, LegendaryWeapon w) {
        long left = plugin.legendary().cooldownLeftMs(p, w);
        if (left > 0) {
            say(p, w.color + w.power + " <gray>is recharging: <white>" + ((left + 999) / 1000) + "s");
            fx.sound(p.getLocation(), Sound.BLOCK_FIRE_EXTINGUISH, 0.5f, 1.6f);
            return;
        }
        boolean ok = switch (w) {
            case GOD_MACE -> heavensFall(p);
            case DAWNBREAKER -> solarBeam(p);
            case SOUL_REAPER -> soulHarvest(p);
            case TITAN_WRATH -> berserkersRage(p);
            case FROSTBRAND -> frostNova(p);
            case SHADOWFANG -> shadowStep(p);
            case EVENT_HORIZON -> singularity(p);
            case VIPER_FANG -> toxicBurst(p);
            case EXECUTIONER -> headsmansLeap(p);
            case INFERNAL_SCEPTER -> dragonfire(p);
            case THUNDER_ROD -> callThunder(p);
            case BENEDICTION -> divineGrace(p);
            case MALEDICTION -> doomDecree(p);
            default -> false;
        };
        if (!ok) return;
        plugin.legendary().startCooldown(p, w);
        int cd = plugin.legendary().cooldownSeconds(w);
        if (cd > 0) {
            UUID id = p.getUniqueId();
            Bukkit.getScheduler().runTaskLater(plugin, () -> readyPing(id, w), cd * 20L);
        }
    }

    /** A chime and sparkle when a power comes off cooldown. */
    private void readyPing(UUID id, LegendaryWeapon w) {
        Player p = Bukkit.getPlayer(id);
        if (p == null || (plugin.legendary().held(p) != w && plugin.legendary().worn(p) != w)) return;
        Location l = p.getLocation().add(0, 1.2, 0);
        fx.sphere(l, 0.9, fx.n(14), fx.signature(w, 1, 0.02));
        fx.sound(l, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1f, 1.5f);
        say(p, w.gradient("<bold>" + w.power + "</bold>") + " <green>\u2714 READY");
    }

    // ---- God Mace

    /** God Mace: leap skyward, then slam down for a shockwave. */
    private boolean heavensFall(Player p) {
        final LegendaryWeapon g = LegendaryWeapon.GOD_MACE;
        final Location l = p.getLocation();
        Vector v = p.getVelocity();
        p.setFallDistance(0f);
        p.setVelocity(new Vector(v.getX(), 1.8, v.getZ()));
        slams.put(p.getUniqueId(), new Slam(l.getY(), g));

        Consumer<Location> sig = fx.signature(g, 3, 0.08);
        Consumer<Location> rod = fx.particle(Particle.END_ROD, 1, 0.02, 0.01);
        fx.animate(10, t -> {
            fx.ring(l, 0.4 + t * 0.4, 28, sig);
            fx.column(l.clone().add(Math.cos(t) * 0.5, 0, Math.sin(t) * 0.5), 0.35 * t, 0.5, rod);
        });
        fx.debris(blockBelow(l), 40, 0.5).accept(l);
        if (fx.solid()) {
            fx.beam(l, l.clone().add(0, 7, 0), 1.0f, fx.glass(g), 10);
            fx.beam(l, l.clone().add(0, 7, 0), 0.4f, fx.core(g), 8);
            fx.shockRing(l.clone().add(0, 0.1, 0), 0.5, 4.0, 0.25f, 0.35f, fx.core(g), 8);
        }
        fx.sphere(l.clone().add(0, 1, 0), 1.2, fx.n(30), fx.particle(Particle.CLOUD, 1, 0.05, 0.1));
        fx.sound(l, Sound.ENTITY_ENDER_DRAGON_FLAP, 1.5f, 0.7f);
        fx.sound(l, Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 0.8f, 1.4f);
        say(p, g.color + "Heaven's Fall! <gray>Hold sneak to dive.");
        return true;
    }

    private void shockwave(Player p, double fall) {
        final LegendaryWeapon g = LegendaryWeapon.GOD_MACE;
        final Location c = p.getLocation();
        final World w = c.getWorld();
        final double radius = 7.0;
        double damage = Math.max(8.0, Math.min(40.0, 8.0 + Math.max(0.0, fall) * 0.8));

        Consumer<Location> sig = fx.signature(g, 3, 0.1);
        Consumer<Location> rock = fx.debris(blockBelow(c), 3, 0.15);
        Consumer<Location> rod = fx.particle(Particle.END_ROD, 1, 0.1, 0.05);
        w.strikeLightningEffect(c);
        w.spawnParticle(Particle.EXPLOSION_EMITTER, c, 1);
        fx.debris(blockBelow(c), 80, 1.2).accept(c.clone().add(0, 0.3, 0));
        if (fx.solid()) {
            fx.shockRing(c.clone().add(0, 0.15, 0), 0.6, radius, 0.4f, 0.5f, fx.core(g), 10);
            fx.shockRing(c.clone().add(0, 0.1, 0), 0.4, radius * 0.7, 0.25f, 0.4f, fx.glass(g), 8);
            fx.beam(c, c.clone().add(0, 10, 0), 1.6f, fx.glass(g), 12);
            fx.beam(c, c.clone().add(0, 10, 0), 0.6f, fx.core(g), 10);
            fx.bloom(c.clone().add(0, 1, 0), fx.glass(g), 4.5f, 8);
        }
        fx.animate(9, t -> {
            double r = radius * (t + 1) / 9.0;
            fx.ring(c, r, fx.n(44), sig);
            fx.ring(c, r, fx.n(30), rock);
            if (t % 3 == 0) fx.ring(c.clone().add(0, 0.6, 0), r * 0.7, 24, rod);
            if (t == 2 || t == 6) fx.column(c, 6.0, 0.5, rod);
        });
        fx.sphere(c.clone().add(0, 1.5, 0), 2.5, fx.n(40), fx.particle(Particle.CLOUD, 1, 0.1, 0.15));
        fx.sound(c, Sound.ENTITY_LIGHTNING_BOLT_IMPACT, 2f, 0.6f);
        fx.sound(c, Sound.ITEM_TRIDENT_THUNDER, 2f, 0.8f);
        fx.sound(c, Sound.BLOCK_ANVIL_LAND, 1.5f, 0.5f);

        for (Entity en : w.getNearbyEntities(c, radius, radius, radius)) {
            if (!validTarget(p, en)) continue;
            LivingEntity le = (LivingEntity) en;
            double dist = le.getLocation().distance(c);
            if (dist > radius) continue;
            hurt(p, le, damage * (1.0 - 0.5 * (dist / radius)));
            knockAway(le, c, 1.3, 0.7);
        }
    }

    // ---- Dawnbreaker

    /** Dawnbreaker: a piercing, burning beam that sweeps outward over a few ticks. */
    private boolean solarBeam(Player p) {
        final LegendaryWeapon d = LegendaryWeapon.DAWNBREAKER;
        final Location eye = p.getEyeLocation();
        final Vector dir = eye.getDirection().normalize();
        final World w = p.getWorld();
        final Location start = eye.clone().add(dir.clone().multiply(0.8)).subtract(0, 0.25, 0);

        double length = 26.0;
        Set<LivingEntity> hit = new LinkedHashSet<>();
        for (double dist = 1.0; dist <= 26.0; dist += 0.5) {
            Location pt = eye.clone().add(dir.clone().multiply(dist));
            if (pt.getBlock().getType().isSolid()) {
                length = dist;
                break;
            }
            for (Entity en : w.getNearbyEntities(pt, 1.0, 1.0, 1.0)) {
                if (validTarget(p, en)) hit.add((LivingEntity) en);
            }
        }
        final double len = length;
        final Location end = eye.clone().add(dir.clone().multiply(len));
        Consumer<Location> core = fx.combo(fx.particle(Particle.END_ROD, 1, 0.0, 0.0), fx.dust(d.rgbA, d.rgbB, 1.6f, 1, 0.05));
        Consumer<Location> flame = fx.combo(fx.particle(Particle.FLAME, 1, 0.02, 0.01), fx.dust(d.rgbB, d.rgbA, 1.2f, 1, 0.02));
        final int frames = 6;

        fx.ring(start, 0.5, 12, core);
        if (fx.solid()) {
            fx.beam(start, end, 1.0f, fx.glass(d), 10);
            fx.beam(start, end, 0.45f, fx.core(d), 8);
            fx.beam(start, end, 0.18f, Material.WHITE_CONCRETE, 6);
            fx.bloom(start, fx.core(d), 1.2f, 5);
            fx.bloom(end, fx.glass(d), 3.2f, 7);
            fx.bloom(end, fx.core(d), 1.6f, 6);
            fx.shockRing(end.clone(), 0.4, 3.5, 0.2f, 0.3f, fx.core(d), 8);
        }
        fx.animate(frames, t -> {
            Location a = start.clone().add(dir.clone().multiply(len * t / frames));
            Location b = start.clone().add(dir.clone().multiply(len * (t + 1) / frames));
            fx.line(a, b, 0.25, core);
            fx.spiralAlong(a, b, 0.55, 2.0, fx.n(18), flame);
            fx.spiralAlong(a, b, 0.35, -3.0, fx.n(12), fx.particle(Particle.END_ROD, 1, 0.0, 0.0));
        });
        fx.later(frames, () -> {
            fx.sphere(end, 1.6, fx.n(40), flame);
            fx.sp(Particle.EXPLOSION, end, 1, 0.1, 0.1, 0.1, 0);
            fx.expandingRing(end, 2.5, 4, 24, core);
        });
        fx.sound(eye, Sound.ENTITY_BLAZE_SHOOT, 1.5f, 0.6f);
        fx.sound(eye, Sound.ENTITY_LIGHTNING_BOLT_IMPACT, 1.0f, 1.6f);
        fx.sound(eye, Sound.ENTITY_FIREWORK_ROCKET_BLAST, 1.0f, 0.8f);

        for (LivingEntity le : hit) {
            hurt(p, le, 14.0);
            le.setFireTicks(100);
            fx.sphere(le.getLocation().add(0, 1, 0), 0.9, fx.n(25), flame);
        }
        if (!hit.isEmpty()) say(p, d.color + "Solar Beam hit <white>" + hit.size() + d.color + " target(s)");
        return true;
    }

    // ---- Soul Reaper

    /** Soul Reaper: drain everything nearby; souls spiral back to you. */
    private boolean soulHarvest(Player p) {
        final LegendaryWeapon r = LegendaryWeapon.SOUL_REAPER;
        final Location c = p.getLocation();
        final World w = c.getWorld();
        final double radius = 8.0;
        Consumer<Location> sig = fx.signature(r, 2, 0.05);

        List<LivingEntity> victims = new ArrayList<>();
        for (Entity en : w.getNearbyEntities(c, radius, radius, radius)) {
            if (!validTarget(p, en)) continue;
            LivingEntity le = (LivingEntity) en;
            if (le.getLocation().distance(c) > radius) continue;
            hurt(p, le, 8.0);
            victims.add(le);
        }
        for (int i = 0; i < victims.size(); i++) {
            Location from = victims.get(i).getLocation().add(0, 1, 0);
            fx.sphere(from, 0.8, fx.n(20), fx.particle(Particle.SOUL_FIRE_FLAME, 1, 0.03, 0.02));
            stream(from, p, i * 2, sig);
            fx.beam(from, p.getLocation().add(0, 1, 0), 0.14f, fx.core(r), 10);
        }
        fx.animate(10, t -> fx.ring(c.clone().add(0, 0.3, 0), radius * (t + 1) / 10.0, fx.n(36), sig));
        fx.expandingRing(c.clone().add(0, 0.6, 0), radius, 10, 30, fx.particle(Particle.SOUL, 1, 0.02, 0.02));
        fx.sound(c, Sound.ENTITY_WITHER_SHOOT, 1.2f, 0.6f);
        if (fx.solid()) fx.shockRing(c.clone().add(0, 0.1, 0), 0.5, radius, 0.25f, 0.4f, fx.core(r), 10);
        fx.sound(c, Sound.ENTITY_ELDER_GUARDIAN_CURSE, 1.0f, 1.2f);

        if (!victims.isEmpty()) {
            final int souls = victims.size();
            fx.later(12, () -> {
                if (!p.isOnline()) return;
                heal(p, Math.min(souls * 2.0, 12.0));
                Location l = p.getLocation().add(0, 1.2, 0);
                fx.sp(Particle.HEART, l.clone().add(0, 0.6, 0), Math.min(souls * 2, 10), 0.5, 0.4, 0.5, 0.0);
                fx.sphere(l, 1.0, fx.n(20), sig);
                fx.sound(l, Sound.ENTITY_PLAYER_LEVELUP, 0.8f, 1.6f);
            });
            say(p, r.color + "Harvested <white>" + souls + r.color + " soul(s)");
        }
        return true;
    }

    // ---- Titan's Wrath

    /** Titan's Wrath: a short burst of power, wreathed in flame. */
    private boolean berserkersRage(Player p) {
        final LegendaryWeapon t0 = LegendaryWeapon.TITAN_WRATH;
        p.addPotionEffect(new PotionEffect(PotionEffectType.STRENGTH, 200, 1));
        p.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 200, 0));
        p.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, 200, 0));
        rage.put(p.getUniqueId(), System.currentTimeMillis() + 10_000L);

        final Location l = p.getLocation();
        Consumer<Location> sig = fx.signature(t0, 3, 0.1);
        Consumer<Location> flame = fx.combo(fx.particle(Particle.FLAME, 1, 0.03, 0.02), fx.dust(t0.rgbA, t0.rgbB, 1.4f, 1, 0.03));
        fx.animate(14, t -> {
            fx.helix(l, 0.22 * (t + 1), 1.1 - t * 0.03, 2.0, fx.n(18), flame);
            fx.ring(l.clone().add(0, 0.1, 0), 0.5 + t * 0.3, 28, sig);
        });
        fx.sp(Particle.CRIT, l.clone().add(0, 1, 0), 60, 0.5, 0.9, 0.5, 0.4);
        if (fx.solid()) {
            fx.beam(l, l.clone().add(0, 6, 0), 1.3f, fx.glass(t0), 16);
            fx.beam(l, l.clone().add(0, 6, 0), 0.5f, fx.core(t0), 14);
            fx.shockRing(l.clone().add(0, 0.1, 0), 0.5, 5.0, 0.3f, 0.4f, fx.core(t0), 10);
        }
        fx.sound(l, Sound.ENTITY_RAVAGER_ROAR, 1.5f, 0.8f);
        fx.sound(l, Sound.ENTITY_LIGHTNING_BOLT_IMPACT, 0.8f, 0.7f);
        say(p, t0.color + "<bold>BERSERKER'S RAGE!");
        return true;
    }

    // ---- Frostbrand

    /** Frostbrand: a wave of frost with ice spikes erupting from the ground. */
    private boolean frostNova(Player p) {
        final LegendaryWeapon f = LegendaryWeapon.FROSTBRAND;
        final Location c = p.getLocation();
        final World w = c.getWorld();
        final double radius = 7.0;

        for (Entity en : w.getNearbyEntities(c, radius, radius, radius)) {
            if (!validTarget(p, en)) continue;
            LivingEntity le = (LivingEntity) en;
            if (le.getLocation().distance(c) > radius) continue;
            hurt(p, le, 4.0);
            le.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 100, 3));
            le.setFreezeTicks(Math.max(le.getFreezeTicks(), 200));
            fx.debris(Material.BLUE_ICE, 25, 0.4).accept(le.getLocation().add(0, 1, 0));
        }
        Consumer<Location> frost = fx.combo(fx.signature(f, 2, 0.06), fx.particle(Particle.SNOWFLAKE, 2, 0.1, 0.02));
        Consumer<Location> ice = fx.debris(Material.BLUE_ICE, 4, 0.2);
        Consumer<Location> spike = fx.combo(fx.dust(f.rgbA, f.rgbB, 1.2f, 1, 0.03), fx.debris(Material.ICE, 1, 0.05));
        fx.animate(10, t -> {
            double r = radius * (t + 1) / 10.0;
            fx.ring(c, r, fx.n(48), frost);
            fx.ring(c.clone().add(0, 0.3, 0), r, fx.n(24), ice);
            if (t % 2 == 1) {
                ThreadLocalRandom rnd = ThreadLocalRandom.current();
                for (int i = 0; i < 6; i++) {
                    double a = rnd.nextDouble() * Math.PI * 2;
                    Location base = c.clone().add(Math.cos(a) * r, 0, Math.sin(a) * r);
                    fx.column(base, 1.2 + rnd.nextDouble(), 0.3, spike);
                }
            }
        });
        fx.sphere(c.clone().add(0, 1, 0), 1.5, fx.n(30), fx.particle(Particle.CLOUD, 1, 0.05, 0.05));
        fx.sound(c, Sound.BLOCK_GLASS_BREAK, 1.5f, 0.6f);
        if (fx.solid()) {
            fx.shockRing(c.clone().add(0, 0.1, 0), 0.5, radius, 0.3f, 0.45f, fx.core(f), 14);
            fx.shockRing(c.clone().add(0, 0.1, 0), 0.4, radius * 0.75, 0.2f, 0.35f, fx.glass(f), 12);
            ThreadLocalRandom rnd = ThreadLocalRandom.current();
            for (int i = 0; i < 28; i++) {
                double ang = rnd.nextDouble() * Math.PI * 2;
                double rr = 1.2 + rnd.nextDouble() * (radius - 1.2);
                int delay = (int) (rr / radius * 8.0);
                Location base = c.clone().add(Math.cos(ang) * rr, 0, Math.sin(ang) * rr);
                float h = 1.3f + (float) rnd.nextDouble() * 1.7f;
                fx.later(delay + 1, () -> fx.spike(base, h, 0.45f, fx.core(f), 3, 40));
            }
        }
        fx.sound(c, Sound.ENTITY_PLAYER_HURT_FREEZE, 1.5f, 0.8f);
        fx.sound(c, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1.5f, 0.5f);
        return true;
    }

    // ---- Shadowfang

    /** Shadowfang: blink forward leaving a shadow trail, vanish briefly, empower the next strike. */
    private boolean shadowStep(Player p) {
        final LegendaryWeapon s = LegendaryWeapon.SHADOWFANG;
        final Location start = p.getLocation();
        Vector dir = start.getDirection().normalize();
        Location best = start.clone();
        for (double d = 0.5; d <= 9.0; d += 0.5) {
            Location feet = start.clone().add(dir.clone().multiply(d));
            if (!feet.getBlock().isPassable() || !feet.clone().add(0, 1, 0).getBlock().isPassable()) break;
            best = feet;
        }
        if (best.distanceSquared(start) < 1.0) {
            say(p, s.color + "No room to step.");
            return false;
        }
        final Location dest = best;
        final Location from = start.clone().add(0, 1, 0);
        final Location to = dest.clone().add(0, 1, 0);
        Consumer<Location> shade = fx.combo(fx.signature(s, 2, 0.1), fx.particle(Particle.SMOKE, 2, 0.1, 0.02));

        // the afterimage and the path between
        fx.column(start, 1.8, 0.25, shade);
        fx.sphere(from, 0.7, fx.n(25), shade);
        fx.line(from, to, 0.35, shade);
        fx.spiralAlong(from, to, 0.5, 3.0, fx.n(24), fx.signature(s, 1, 0.0));
        fx.sound(start, Sound.ENTITY_ENDERMAN_TELEPORT, 1f, 0.8f);
        if (fx.solid()) {
            fx.beam(from, to, 0.9f, fx.glass(s), 10);
            fx.beam(from, to, 0.35f, fx.core(s), 8);
            fx.collapse(from, fx.core(s), 1.6f, 6);
        }

        p.teleport(dest);
        p.setFallDistance(0f);
        p.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, 60, 0, false, false, true));
        p.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 60, 1, false, false, true));
        ambush.put(p.getUniqueId(), System.currentTimeMillis() + 4000L);

        // reappear: a ring collapses into the destination
        Consumer<Location> portal = fx.combo(fx.signature(s, 2, 0.03), fx.particle(Particle.PORTAL, 2, 0.05, 0.2));
        fx.animate(6, t -> fx.ring(to, 1.6 * (1.0 - t / 6.0), 20, portal));
        fx.later(6, () -> {
            fx.sphere(to, 1.0, fx.n(30), portal);
            fx.sp(Particle.PORTAL, to, 50, 0.3, 0.6, 0.3, 0.5);
        });
        fx.sound(dest, Sound.ENTITY_ENDERMAN_TELEPORT, 1f, 1.2f);
        if (fx.solid()) {
            fx.bloom(to, fx.glass(s), 2.4f, 6);
            fx.shockRing(dest.clone().add(0, 0.1, 0), 2.0, 0.3, 0.2f, 0.3f, fx.core(s), 6);
        }
        say(p, s.color + "Shadow Step! <gray>Your next strike is empowered.");
        return true;
    }

    // ---- Event Horizon

    /** Event Horizon: spiral arms collapse inward as every nearby enemy is dragged to you. */
    private boolean singularity(Player p) {
        final LegendaryWeapon e = LegendaryWeapon.EVENT_HORIZON;
        final Location base = p.getLocation();
        final Location c = base.clone().add(0, 1.0, 0);
        final World w = c.getWorld();
        final double radius = 12.0;
        Consumer<Location> arm = fx.combo(fx.dust(e.rgbA, e.rgbB, 1.4f, 2, 0.04), fx.particle(Particle.REVERSE_PORTAL, 2, 0.05, 0.1));

        for (Entity en : w.getNearbyEntities(c, radius, radius, radius)) {
            if (!validTarget(p, en)) continue;
            LivingEntity le = (LivingEntity) en;
            if (le.getLocation().distance(c) > radius) continue;
            hurt(p, le, 5.0);
            le.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 60, 2));
            Vector pull = c.toVector().subtract(le.getLocation().toVector());
            if (pull.lengthSquared() > 1.0) {
                pull.normalize().multiply(1.4);
                pull.setY(Math.max(0.3, pull.getY()));
                le.setVelocity(pull);
            }
            fx.line(le.getLocation().add(0, 1, 0), c, 0.4, arm);
        }
        fx.animate(14, t -> {
            double r = 2.0 + 10.0 * (1.0 - (t + 1) / 14.0);
            for (int a = 0; a < 3; a++) {
                double ang = t * 0.5 + a * (Math.PI * 2 / 3);
                for (int j = 0; j < 4; j++) {
                    double aj = ang + j * 0.18;
                    double rj = r - j * 0.25;
                    arm.accept(base.clone().add(Math.cos(aj) * rj, 0.3 + (14 - t) * 0.04, Math.sin(aj) * rj));
                }
            }
            fx.ring(c, 1.2, 10, fx.particle(Particle.SMOKE, 1, 0.02, 0.0)); // dark core
        });
        fx.later(14, () -> {
            fx.sphere(c, 1.5, fx.n(40), arm);
            fx.sp(Particle.EXPLOSION, c, 1, 0.1, 0.1, 0.1, 0);
            fx.expandingRing(base, 5.0, 5, 30, arm);
            fx.sound(c, Sound.ENTITY_ENDERMAN_TELEPORT, 1.5f, 0.4f);
        });
        fx.sound(c, Sound.ENTITY_ENDERMAN_TELEPORT, 1.5f, 0.5f);
        fx.sound(c, Sound.BLOCK_BEACON_DEACTIVATE, 1.5f, 0.5f);
        if (fx.solid()) {
            fx.collapse(c, fx.core(e), 2.8f, 14);
            fx.collapse(c, fx.glass(e), 5.0f, 14);
            fx.shockRing(base.clone().add(0, 0.1, 0), radius, 1.0, 0.25f, 0.35f, fx.core(e), 14);
            fx.later(14, () -> fx.bloom(c, fx.glass(e), 5.5f, 6));
        }
        return true;
    }

    // ---- Viper's Fang

    /** Viper's Fang: an expanding shell of venom that lingers as a poison cloud. */
    private boolean toxicBurst(Player p) {
        final LegendaryWeapon v = LegendaryWeapon.VIPER_FANG;
        final Location c = p.getLocation();
        final World w = c.getWorld();
        final double radius = 6.0;
        Consumer<Location> cloud = fx.combo(fx.dust(v.rgbA, v.rgbB, 1.8f, 2, 0.15), fx.particle(Particle.ITEM_SLIME, 1, 0.15, 0.0));

        for (Entity en : w.getNearbyEntities(c, radius, radius, radius)) {
            if (!validTarget(p, en)) continue;
            LivingEntity le = (LivingEntity) en;
            if (le.getLocation().distance(c) > radius) continue;
            hurt(p, le, 3.0);
            le.addPotionEffect(new PotionEffect(PotionEffectType.POISON, 160, 2));
            le.addPotionEffect(new PotionEffect(PotionEffectType.NAUSEA, 100, 0));
        }
        fx.animate(8, t -> fx.sphere(c.clone().add(0, 1, 0), radius * (t + 1) / 8.0, fx.n(50), cloud));
        // the cloud lingers for 3 seconds, re-poisoning anything that stays inside
        fx.later(8, () -> fx.animate(60, t -> {
            ThreadLocalRandom rnd = ThreadLocalRandom.current();
            if (t % 2 == 0) {
                for (int i = 0; i < 6; i++) {
                    double ang = rnd.nextDouble() * Math.PI * 2;
                    double rr = Math.sqrt(rnd.nextDouble()) * radius;
                    Location pt = c.clone().add(Math.cos(ang) * rr, rnd.nextDouble() * 2.0, Math.sin(ang) * rr);
                    cloud.accept(pt);
                    fx.sp(Particle.WARPED_SPORE, pt, 1, 0.1, 0.1, 0.1, 0.0);
                }
            }
            if (t % 10 == 0) {
                for (Entity en : w.getNearbyEntities(c, radius, radius, radius)) {
                    if (!validTarget(p, en)) continue;
                    ((LivingEntity) en).addPotionEffect(new PotionEffect(PotionEffectType.POISON, 60, 1));
                }
            }
        }));
        fx.sound(c, Sound.ENTITY_SPLASH_POTION_BREAK, 1.5f, 0.7f);
        fx.sound(c, Sound.ENTITY_WITCH_THROW, 1.0f, 0.6f);
        if (fx.solid()) {
            fx.bloom(c.clone().add(0, 1, 0), fx.glass(v), (float) radius * 2.0f, 8);
            fx.bloom(c.clone().add(0, 1, 0), fx.core(v), (float) radius * 0.9f, 8);
            fx.shockRing(c.clone().add(0, 0.1, 0), 0.5, radius, 0.25f, 0.4f, fx.core(v), 10);
        }
        return true;
    }

    // ---- Executioner

    /** Executioner: leap forward, slam on landing. */
    private boolean headsmansLeap(Player p) {
        final LegendaryWeapon x = LegendaryWeapon.EXECUTIONER;
        Vector dir = p.getLocation().getDirection();
        dir.setY(0);
        if (dir.lengthSquared() < 0.01) dir = new Vector(0, 0, 1);
        dir.normalize().multiply(1.7).setY(0.55);
        p.setFallDistance(0f);
        p.setVelocity(dir);
        p.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, 60, 1));
        slams.put(p.getUniqueId(), new Slam(p.getLocation().getY(), x));
        Location l = p.getLocation();
        fx.sphere(l.clone().add(0, 0.5, 0), 1.0, fx.n(25), fx.particle(Particle.CLOUD, 1, 0.05, 0.1));
        fx.expandingRing(l, 2.0, 5, 20, fx.signature(x, 2, 0.05));
        fx.sound(l, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 1.5f, 0.6f);
        fx.sound(l, Sound.ENTITY_RAVAGER_ROAR, 0.6f, 1.6f);
        return true;
    }

    private void leapImpact(Player p) {
        final LegendaryWeapon x = LegendaryWeapon.EXECUTIONER;
        final Location c = p.getLocation();
        final World w = c.getWorld();
        final double radius = 4.5;
        Consumer<Location> sig = fx.signature(x, 3, 0.1);
        fx.debris(blockBelow(c), 50, 0.8).accept(c.clone().add(0, 0.3, 0));
        fx.animate(6, t -> fx.ring(c, radius * (t + 1) / 6.0, 36, sig));
        fx.slashMark(c.clone().add(0, 1.2, 0), x, 1.4);
        if (fx.solid()) {
            fx.shockRing(c.clone().add(0, 0.1, 0), 0.5, radius, 0.35f, 0.45f, fx.core(x), 10);
            fx.beam(c, c.clone().add(0, 5, 0), 0.9f, fx.glass(x), 8);
        }
        fx.sphere(c.clone().add(0, 0.8, 0), 1.6, fx.n(30), fx.particle(Particle.CLOUD, 1, 0.05, 0.1));
        fx.sound(c, Sound.BLOCK_ANVIL_LAND, 1.5f, 0.6f);
        fx.sound(c, Sound.ENTITY_PLAYER_ATTACK_CRIT, 1.5f, 0.5f);
        for (Entity en : w.getNearbyEntities(c, radius, radius, radius)) {
            if (!validTarget(p, en)) continue;
            LivingEntity le = (LivingEntity) en;
            if (le.getLocation().distance(c) > radius) continue;
            hurt(p, le, 10.0);
            knockAway(le, c, 1.0, 0.5);
        }
    }

    // ---- Infernal Scepter

    /** Infernal Scepter: a fireball that bursts on impact (never damages blocks). */
    private boolean dragonfire(Player p) {
        final LegendaryWeapon s = LegendaryWeapon.INFERNAL_SCEPTER;
        Vector dir = p.getEyeLocation().getDirection().normalize();
        Location muzzle = p.getEyeLocation().add(dir.clone().multiply(1.2));
        LargeFireball fb = p.launchProjectile(LargeFireball.class, dir.clone().multiply(1.5));
        fb.setYield(0f);
        fb.setIsIncendiary(false);
        fb.setDirection(dir);
        tag(fb, s);
        fx.sphere(muzzle, 0.7, fx.n(25), fx.combo(fx.signature(s, 2, 0.05), fx.particle(Particle.FLAME, 1, 0.05, 0.05)));
        fx.sound(muzzle, Sound.ENTITY_BLAZE_SHOOT, 1.5f, 0.6f);
        fx.bloom(muzzle, fx.glass(s), 1.4f, 5);
        fx.bloom(muzzle, fx.core(s), 0.7f, 4);
        fx.sound(muzzle, Sound.ENTITY_GHAST_SHOOT, 1.0f, 0.8f);
        return true;
    }

    // ---- Thunder Rod

    /** Thunder Rod: a telegraphed lightning strike on the targeted block. */
    private boolean callThunder(Player p) {
        final LegendaryWeapon t0 = LegendaryWeapon.THUNDER_ROD;
        Block b = p.getTargetBlockExact(50);
        if (b == null) {
            say(p, t0.color + "No target in range.");
            return false;
        }
        final Location at = b.getLocation().add(0.5, 1.0, 0.5);
        Consumer<Location> spark = fx.combo(fx.dust(t0.rgbA, t0.rgbB, 1.4f, 2, 0.03), fx.particle(Particle.ELECTRIC_SPARK, 1, 0.05, 0.02));

        fx.sound(at, Sound.BLOCK_BEACON_ACTIVATE, 2f, 2f);
        if (fx.solid()) {
            fx.shockRing(at.clone().add(0, 0.1, 0), 4.5, 0.5, 0.2f, 0.3f, fx.core(t0), 12);
            fx.beam(at, at.clone().add(0, 40, 0), 0.12f, fx.glass(t0), 12);
        }
        // 12 ticks of warning: a shrinking ring on the ground and a bolt growing down from the clouds
        fx.animate(12, t -> {
            fx.ring(at, 0.5 + 4.0 * (1.0 - t / 12.0), 28, spark);
            double y = 30.0 * (1.0 - (t + 1) / 12.0);
            fx.jagged(at.clone().add(0, y + 8.0, 0), at.clone().add(0, Math.max(y, 0.0), 0), 4, 0.5, 0.4, spark);
        });
        fx.later(12, () -> strikeThunder(p, at, spark));
        return true;
    }

    private void strikeThunder(Player p, Location at, Consumer<Location> spark) {
        World w = at.getWorld();
        w.strikeLightningEffect(at);
        fx.sphere(at.clone().add(0, 1, 0), 2.0, fx.n(50), spark);
        fx.expandingRing(at, 4.5, 5, 30, spark);
        fx.column(at, 12.0, 0.5, spark);
        fx.debris(Material.COPPER_BLOCK, 30, 0.8).accept(at);
        LegendaryWeapon tr = LegendaryWeapon.THUNDER_ROD;
        fx.bolt(at.clone().add(0, 40, 0), at, 0.45f, fx.core(tr), fx.glass(tr), 8);
        fx.bloom(at.clone().add(0, 1, 0), fx.glass(tr), 4.0f, 6);
        fx.shockRing(at.clone().add(0, 0.1, 0), 0.5, 4.5, 0.25f, 0.4f, fx.core(tr), 8);
        fx.sound(at, Sound.ITEM_TRIDENT_THUNDER, 3f, 0.9f);
        fx.sound(at, Sound.ENTITY_LIGHTNING_BOLT_IMPACT, 3f, 1.2f);
        if (!p.isOnline()) return;
        double radius = 4.5;
        for (Entity en : w.getNearbyEntities(at, radius, radius, radius)) {
            if (!validTarget(p, en)) continue;
            LivingEntity le = (LivingEntity) en;
            hurt(p, le, 16.0);
            le.setFireTicks(100);
        }
    }

    // ------------------------------------------------------------------ mythic weapons

    private int countCurses(LivingEntity le) {
        int n = 0;
        for (PotionEffectType t : CURSES) if (le.hasPotionEffect(t)) n++;
        return n;
    }

    /** Benediction: full heal and a stack of buffs, blessings for nearby players, holy light for hostile mobs. */
    private boolean divineGrace(Player p) {
        final LegendaryWeapon b = LegendaryWeapon.BENEDICTION;
        final Location c = p.getLocation();
        final World w = c.getWorld();
        final double radius = 14.0;

        AttributeInstance max = p.getAttribute(Attribute.MAX_HEALTH);
        heal(p, max != null ? max.getValue() : 20.0);
        for (PotionEffectType bad : CURSES) p.removePotionEffect(bad);
        p.addPotionEffect(new PotionEffect(PotionEffectType.ABSORPTION, 400, 4));
        p.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, 200, 2));
        p.addPotionEffect(new PotionEffect(PotionEffectType.STRENGTH, 300, 1));
        p.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, 300, 1));
        p.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 300, 1));

        int blessed = 0;
        int smitten = 0;
        for (Entity en : w.getNearbyEntities(c, radius, radius, radius)) {
            if (en.getLocation().distance(c) > radius) continue;
            if (en instanceof Player other) {
                if (other.equals(p) || other.getGameMode() == GameMode.SPECTATOR) continue;
                for (PotionEffectType bad : CURSES) other.removePotionEffect(bad);
                other.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, 200, 1));
                other.addPotionEffect(new PotionEffect(PotionEffectType.ABSORPTION, 300, 1));
                other.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 200, 0));
                fx.sp(Particle.TOTEM_OF_UNDYING, other.getLocation().add(0, 1, 0), 25, 0.4, 0.8, 0.4, 0.3);
                blessed++;
            } else if (en instanceof Enemy && validTarget(p, en)) {
                LivingEntity le = (LivingEntity) en;
                hurt(p, le, 20.0);
                le.setFireTicks(100);
                if (smitten < 8) {
                    Location at = le.getLocation();
                    fx.bolt(at.clone().add(0, 14, 0), at, 0.35f, fx.core(b), fx.glass(b), 8);
                }
                smitten++;
            }
        }

        Consumer<Location> gold = fx.combo(fx.signature(b, 2, 0.06), fx.particle(Particle.END_ROD, 1, 0.02, 0.02));
        fx.animate(16, t -> {
            fx.helix(c, 0.2 * (t + 1), 1.4 - t * 0.04, 2.0, fx.n(20), gold);
            fx.ring(c.clone().add(0, 0.1, 0), 0.6 + t * 0.85, fx.n(48), gold);
        });
        fx.sp(Particle.TOTEM_OF_UNDYING, c.clone().add(0, 1, 0), 60, 0.6, 1.0, 0.6, 0.4);
        if (fx.solid()) {
            fx.beam(c, c.clone().add(0, 40, 0), 2.0f, fx.glass(b), 30);
            fx.beam(c, c.clone().add(0, 40, 0), 0.8f, fx.core(b), 26);
            fx.shockRing(c.clone().add(0, 0.1, 0), 0.5, radius, 0.3f, 0.5f, fx.core(b), 16);
            fx.shockRing(c.clone().add(0, 0.1, 0), 0.4, radius * 0.7, 0.2f, 0.4f, fx.glass(b), 14);
        }
        fx.sound(c, Sound.BLOCK_BEACON_ACTIVATE, 1.5f, 1.4f);
        fx.sound(c, Sound.ITEM_TOTEM_USE, 1.0f, 1.0f);
        fx.sound(c, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1.5f, 0.6f);
        say(p, b.color + "<bold>DIVINE GRACE!</bold> <gray>blessed <white>" + blessed + "<gray>, smote <white>" + smitten);
        return true;
    }

    /** Malediction: a wave of curses over everything nearby, feeding the wielder with stolen life. */
    private boolean doomDecree(Player p) {
        final LegendaryWeapon m = LegendaryWeapon.MALEDICTION;
        final Location c = p.getLocation();
        final World w = c.getWorld();
        final double radius = 18.0;

        int cursed = 0;
        List<Location> marks = new ArrayList<>();
        for (Entity en : w.getNearbyEntities(c, radius, radius, radius)) {
            if (!validTarget(p, en)) continue;
            LivingEntity le = (LivingEntity) en;
            if (le.getLocation().distance(c) > radius) continue;
            hurt(p, le, 8.0);
            le.addPotionEffect(new PotionEffect(PotionEffectType.WITHER, 200, 2));
            le.addPotionEffect(new PotionEffect(PotionEffectType.WEAKNESS, 200, 1));
            le.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 120, 1));
            le.addPotionEffect(new PotionEffect(PotionEffectType.HUNGER, 200, 2));
            le.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING, 200, 0));
            cursed++;
            if (marks.size() < 8) marks.add(le.getLocation());
        }
        heal(p, Math.min(20.0, cursed * 3.0));

        Consumer<Location> dark = fx.combo(fx.signature(m, 2, 0.06), fx.particle(Particle.SOUL_FIRE_FLAME, 1, 0.04, 0.02));
        fx.animate(16, t -> {
            double r = radius * (t + 1) / 16.0;
            fx.ring(c.clone().add(0, 0.1, 0), r, fx.n(60), dark);
            fx.ring(c.clone().add(0, 0.1, 0), r * 0.6, fx.n(36), fx.particle(Particle.SMOKE, 1, 0.05, 0.02));
            if (t % 4 == 0) fx.helix(c, 3.0, Math.max(0.5, 3.5 - t * 0.2), 1.5, fx.n(26), dark);
        });
        if (fx.solid()) {
            fx.shockRing(c.clone().add(0, 0.1, 0), 0.5, radius, 0.35f, 0.5f, fx.core(m), 18);
            fx.shockRing(c.clone().add(0, 0.1, 0), 0.4, radius * 0.7, 0.25f, 0.4f, fx.glass(m), 16);
            fx.beam(c, c.clone().add(0, 30, 0), 1.6f, fx.glass(m), 24);
            fx.beam(c, c.clone().add(0, 30, 0), 0.6f, fx.core(m), 20);
            for (Location at : marks) fx.bolt(at.clone().add(0, 14, 0), at, 0.35f, fx.core(m), fx.glass(m), 8);
        }
        for (Location at : marks) fx.sphere(at.clone().add(0, 1, 0), 0.9, fx.n(18), dark);
        fx.sound(c, Sound.ENTITY_ELDER_GUARDIAN_CURSE, 1.5f, 0.7f);
        fx.sound(c, Sound.ENTITY_WITHER_SPAWN, 0.8f, 0.6f);
        say(p, m.color + "<bold>DOOM DECREE!</bold> <gray>cursed <white>" + cursed + "<gray> soul(s)");
        return true;
    }

    // ------------------------------------------------------------------ God Elytra

    private double elytraMaxSpeed() {
        return Math.max(1.0, plugin.getConfig().getDouble("legendary.elytra.max-speed", 3.0));
    }

    /** Runs for every gliding player: God Elytra flight handling, plus wing and speed effects. */
    private void tickElytra() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            UUID id = p.getUniqueId();
            if (!p.isGliding()) {
                peak.remove(id);
                continue;
            }
            if (plugin.legendary().worn(p) != LegendaryWeapon.GOD_ELYTRA) continue;

            Location l = p.getLocation();
            double y = l.getY();
            double pk = Math.max(peak.getOrDefault(id, y), y);
            peak.put(id, pk);
            // vanilla forgets fall distance in shallow dives; here every block of altitude lost counts for a smash
            double fall = pk - y;
            if (fall > p.getFallDistance()) p.setFallDistance((float) fall);

            Vector v = p.getVelocity();
            Vector look = l.getDirection().normalize();
            double pitch = l.getPitch(); // positive = looking down
            Vector nv = v.clone();
            boolean changed = true;
            if (p.isSneaking()) {
                nv.multiply(0.88); // air-brake for precise landings
            } else if (pitch > 25.0) {
                // stoop: the steeper the dive, the harder it accelerates
                double accel = 0.10 + Math.min(1.0, (pitch - 25.0) / 55.0) * 0.14;
                nv.add(look.clone().multiply(accel));
            } else if (pitch > -20.0 && v.length() < 1.5) {
                nv.add(look.clone().multiply(0.06)); // level flight keeps its speed
            } else {
                changed = false;
            }
            double maxSpeed = elytraMaxSpeed();
            if (nv.length() > maxSpeed) {
                nv.normalize().multiply(maxSpeed);
                changed = true;
            }
            if (changed) p.setVelocity(nv);

            double speed = nv.length();
            if (fx.enabled("trails")) wingFx(p, l, look, speed);
            if (speed > 2.2) {
                long now = System.currentTimeMillis();
                Long lb = lastBoom.get(id);
                if (lb == null || now - lb > 2000L) {
                    lastBoom.put(id, now);
                    Location body = l.clone().add(0, 0.4, 0);
                    fx.sp(Particle.SONIC_BOOM, body, 1, 0.0, 0.0, 0.0, 0.0);
                    fx.sphere(body.clone().subtract(look.clone().multiply(1.5)), 1.2, fx.n(20), fx.particle(Particle.CLOUD, 1, 0.05, 0.05));
                    fx.sound(body, Sound.ENTITY_WARDEN_SONIC_BOOM, 0.4f, 1.8f);
                }
            }
            if (ticks % 5 == 0) elytraHud(p, speed);
        }
    }

    /** Glowing wings streaming from the shoulders, with contrails at the tips when fast. */
    private void wingFx(Player p, Location l, Vector look, double speed) {
        LegendaryWeapon g = LegendaryWeapon.GOD_ELYTRA;
        Vector right = look.clone().crossProduct(new Vector(0, 1, 0));
        if (right.lengthSquared() < 1.0E-4) return; // flying straight up or down
        right.normalize();
        Location body = l.clone().add(0, 0.4, 0);
        Consumer<Location> feather = fx.dust(g.rgbA, g.rgbB, 1.0f, 1, 0.02);
        for (int side = -1; side <= 1; side += 2) {
            for (int i = 1; i <= 5; i++) {
                Location pt = body.clone().add(right.clone().multiply(side * (0.25 + i * 0.28)))
                        .subtract(look.clone().multiply(i * 0.18));
                feather.accept(pt);
                if (i == 5) {
                    fx.sp(Particle.END_ROD, pt, 1, 0.0, 0.0, 0.0, 0.0);
                    if (speed > 1.2) fx.sp(Particle.CLOUD, pt, 1, 0.02, 0.02, 0.02, 0.0);
                }
            }
        }
    }

    /** Folded wings shimmer behind the shoulders while standing around in the God Elytra. */
    private void wingShimmer(Player p) {
        LegendaryWeapon g = LegendaryWeapon.GOD_ELYTRA;
        Location l = p.getLocation();
        Vector back = l.getDirection().setY(0);
        if (back.lengthSquared() < 1.0E-4) return;
        back.normalize().multiply(-0.35);
        Vector right = back.clone().crossProduct(new Vector(0, 1, 0));
        if (right.lengthSquared() < 1.0E-4) return;
        right.normalize();
        Consumer<Location> feather = fx.dust(g.rgbA, g.rgbB, 0.9f, 1, 0.03);
        for (int side = -1; side <= 1; side += 2) {
            for (int i = 0; i < 3; i++) {
                Location pt = l.clone().add(0, 1.55 - i * 0.28, 0).add(back)
                        .add(right.clone().multiply(side * (0.25 + i * 0.12)));
                feather.accept(pt);
                if (i == 0) fx.sp(Particle.END_ROD, pt, 1, 0.0, 0.0, 0.0, 0.0);
            }
        }
    }

    private void elytraHud(Player p, double speed) {
        Long q = quiet.get(p.getUniqueId());
        if (q != null && q > System.currentTimeMillis()) return;
        LegendaryWeapon g = LegendaryWeapon.GOD_ELYTRA;
        long left = plugin.legendary().cooldownLeftMs(p, g);
        String thrust = left <= 0 ? "<green>\u2714 THRUST READY" : "<gray>Thrust <white>" + ((left + 999) / 1000) + "s";
        plugin.msg().actionBar(p, g.gradient("<bold>God Elytra</bold>") + " <dark_gray>\u2022 <white>"
                + Math.round(speed * 20) + " b/s <dark_gray>\u2022 " + thrust);
    }

    /** Sky Thrust (swap-hand key while gliding): a rocket-like burst along your line of sight. */
    public void elytraThrust(Player p) {
        LegendaryWeapon g = LegendaryWeapon.GOD_ELYTRA;
        long left = plugin.legendary().cooldownLeftMs(p, g);
        if (left > 0) {
            say(p, g.color + "Sky Thrust <gray>is recharging: <white>" + ((left + 999) / 1000) + "s");
            fx.sound(p.getLocation(), Sound.BLOCK_FIRE_EXTINGUISH, 0.5f, 1.6f);
            return;
        }
        Location l = p.getLocation();
        Vector look = l.getDirection().normalize();
        Vector nv = p.getVelocity().add(look.clone().multiply(1.5));
        double maxSpeed = elytraMaxSpeed();
        if (nv.length() > maxSpeed) nv.normalize().multiply(maxSpeed);
        p.setVelocity(nv);

        Location body = l.clone().add(0, 0.4, 0);
        Location behind = body.clone().subtract(look.clone().multiply(1.2));
        fx.sp(Particle.SONIC_BOOM, behind, 1, 0.0, 0.0, 0.0, 0.0);
        fx.sphere(behind, 1.4, fx.n(40), fx.combo(fx.signature(g, 2, 0.05), fx.particle(Particle.CLOUD, 1, 0.05, 0.1)));
        fx.sp(Particle.FIREWORK, behind, 30, 0.4, 0.4, 0.4, 0.15);
        fx.spiralAlong(body, body.clone().add(look.clone().multiply(8.0)), 0.6, 3.0, fx.n(30), fx.signature(g, 2, 0.04));
        fx.sound(l, Sound.ENTITY_FIREWORK_ROCKET_LAUNCH, 1.2f, 0.8f);
        if (fx.solid()) {
            Location ahead = body.clone().add(look.clone().multiply(12.0));
            fx.beam(body, ahead, 0.7f, fx.glass(g), 8);
            fx.beam(body, ahead, 0.25f, fx.core(g), 6);
            fx.bloom(behind, fx.glass(g), 2.2f, 6);
        }
        fx.sound(l, Sound.ENTITY_WARDEN_SONIC_BOOM, 0.5f, 1.8f);
        fx.sound(l, Sound.ENTITY_ENDER_DRAGON_FLAP, 1f, 1.5f);

        plugin.legendary().startCooldown(p, g);
        int cd = plugin.legendary().cooldownSeconds(g);
        if (cd > 0) {
            UUID id = p.getUniqueId();
            Bukkit.getScheduler().runTaskLater(plugin, () -> readyPing(id, g), cd * 20L);
        }
    }

    /** Starting a glide: reset the altitude tracker and open the wings with a flourish. */
    public void onGlideToggle(Player p, boolean gliding) {
        UUID id = p.getUniqueId();
        if (!gliding) {
            peak.remove(id);
            return;
        }
        peak.put(id, p.getLocation().getY());
        LegendaryWeapon g = LegendaryWeapon.GOD_ELYTRA;
        Location l = p.getLocation().add(0, 0.5, 0);
        fx.sphere(l, 1.2, fx.n(30), fx.signature(g, 2, 0.05));
        fx.expandingRing(p.getLocation(), 2.5, 5, 24, fx.signature(g, 2, 0.05));
        fx.sound(l, Sound.ENTITY_ENDER_DRAGON_FLAP, 0.8f, 1.7f);
        fx.sound(l, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.8f, 1.8f);
    }

    /**
     * Sky Smash: vanilla refuses smash attacks while gliding, so with the God Elytra a mace hit mid-glide
     * is resolved here. Bonus damage follows the vanilla smash curve plus Density, scaled by flight speed,
     * then a shockwave hits nearby enemies and the wearer rebounds upward to dive again.
     */
    public void onElytraStrike(Player p, EntityDamageByEntityEvent e) {
        if (e.getCause() != EntityDamageEvent.DamageCause.ENTITY_ATTACK || !p.isGliding()) return;
        if (plugin.legendary().worn(p) != LegendaryWeapon.GOD_ELYTRA) return;
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (hand.getType() != Material.MACE) return;

        UUID id = p.getUniqueId();
        Location pl = p.getLocation();
        double fall = Math.max(p.getFallDistance(), peak.getOrDefault(id, pl.getY()) - pl.getY());
        if (fall < 3.0) return;

        double speed = p.getVelocity().length();
        double bonus = 4.0 * Math.min(fall, 3.0) + 2.0 * Math.max(0.0, Math.min(fall - 3.0, 5.0)) + Math.max(0.0, fall - 8.0);
        bonus += hand.getEnchantmentLevel(Enchantment.DENSITY) * 0.5 * fall;
        bonus *= 1.0 + Math.min(speed, 3.0) * 0.15;
        bonus = Math.min(bonus, plugin.getConfig().getDouble("legendary.elytra.smash-damage-cap", 60.0));
        e.setDamage(e.getDamage() + bonus);

        Entity victim = e.getEntity();
        Location hc = victim.getLocation().add(0, Math.max(0.5, victim.getHeight() * 0.6), 0);
        LegendaryWeapon g = LegendaryWeapon.GOD_ELYTRA;
        Consumer<Location> sig = fx.signature(g, 3, 0.1);
        fx.sp(Particle.SONIC_BOOM, hc, 1, 0.0, 0.0, 0.0, 0.0);
        fx.sphere(hc, 1.8, fx.n(50), sig);
        fx.expandingRing(victim.getLocation().add(0, 0.5, 0), 4.5, 6, 32, sig);
        fx.sp(Particle.FIREWORK, hc, 40, 0.5, 0.5, 0.5, 0.2);
        fx.sound(hc, Sound.ENTITY_WARDEN_SONIC_BOOM, 1.2f, 1.2f);
        fx.sound(hc, Sound.BLOCK_ANVIL_LAND, 1f, 0.5f);
        if (fx.solid()) {
            fx.shockRing(victim.getLocation().add(0, 0.2, 0), 0.5, 5.0, 0.3f, 0.45f, fx.core(g), 10);
            fx.bloom(hc, fx.glass(g), 4.0f, 7);
            fx.bloom(hc, fx.core(g), 1.8f, 6);
            fx.beam(hc.clone().add(0, 10, 0), hc, 0.6f, fx.glass(g), 8);
        }
        say(p, g.color + "<bold>SKY SMASH!</bold> <white>+" + Math.round(bonus) + g.color + " damage");

        // shockwave on everything else nearby
        double wave = bonus * 0.25;
        for (Entity en : victim.getNearbyEntities(4.0, 3.0, 4.0)) {
            if (en.equals(victim) || !validTarget(p, en)) continue;
            LivingEntity le = (LivingEntity) en;
            hurt(p, le, wave);
            knockAway(le, victim.getLocation(), 1.1, 0.5);
        }

        // bounce back up so the dive can be repeated
        if (plugin.getConfig().getBoolean("legendary.elytra.rebound", true)) {
            Vector look = pl.getDirection().normalize();
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!p.isOnline()) return;
                p.setVelocity(new Vector(look.getX() * 0.7, 1.15, look.getZ() * 0.7));
                p.setFallDistance(0f);
                peak.put(id, p.getLocation().getY());
            });
        }
    }

    // ------------------------------------------------------------------ passives (melee)

    public void onMelee(Player p, LegendaryWeapon w, EntityDamageByEntityEvent e) {
        Entity victim = e.getEntity();
        if (w.damageMultiplier != 1.0) e.setDamage(e.getDamage() * w.damageMultiplier);

        Location hc = victim.getLocation().add(0, Math.max(0.5, victim.getHeight() * 0.6), 0);
        if (fx.enabled("hit-effects")) {
            fx.sphere(hc, 0.7, fx.n(12), fx.signature(w, 1, 0.03));
            fx.sound(hc, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.6f, 0.7f + (w.ordinal() % 6) * 0.15f);
        }

        switch (w) {
            case GOD_MACE -> {
                double fall = p.getFallDistance();
                if (fall > 2.5) {
                    e.setDamage(e.getDamage() + Math.min(20.0, fall * 0.5));
                    Location l = victim.getLocation();
                    l.getWorld().strikeLightningEffect(l);
                    fx.helix(l, 3.0, 1.0, 2.0, fx.n(30), fx.signature(w, 1, 0.02));
                    fx.debris(blockBelow(l), 40, 0.6).accept(l.clone().add(0, 0.2, 0));
                    fx.expandingRing(l, 3.0, 4, 24, fx.signature(w, 2, 0.05));
                    fx.bolt(l.clone().add(0, 14, 0), l, 0.4f, fx.core(w), fx.glass(w), 8);
                    fx.shockRing(l.clone().add(0, 0.1, 0), 0.4, 3.5, 0.25f, 0.4f, fx.core(w), 8);
                    fx.sound(l, Sound.ENTITY_LIGHTNING_BOLT_IMPACT, 1f, 1f);
                }
            }
            case DAWNBREAKER -> {
                if (UNDEAD.contains(victim.getType())) {
                    e.setDamage(e.getDamage() * 1.5);
                    fx.sphere(hc, 1.0, fx.n(30), fx.combo(fx.particle(Particle.FLAME, 1, 0.03, 0.02),
                            fx.particle(Particle.END_ROD, 1, 0.03, 0.02)));
                    fx.sound(hc, Sound.ENTITY_FIREWORK_ROCKET_TWINKLE, 1f, 1.2f);
                }
            }
            case SOUL_REAPER -> {
                heal(p, e.getFinalDamage() * 0.25);
                if (victim instanceof LivingEntity le) {
                    le.addPotionEffect(new PotionEffect(PotionEffectType.WITHER, 60, 1));
                }
                stream(hc, p, 0, fx.signature(w, 1, 0.03));
            }
            case TITAN_WRATH -> cleave(p, victim, e.getFinalDamage() * 0.5);
            case STORMCALLER -> {
                if (ThreadLocalRandom.current().nextDouble() < 0.2) {
                    e.setDamage(e.getDamage() + 6.0);
                    Location l = victim.getLocation();
                    Consumer<Location> spark = fx.combo(fx.dust(w.rgbA, w.rgbB, 1.3f, 1, 0.03), fx.particle(Particle.ELECTRIC_SPARK, 2, 0.05, 0.05));
                    l.getWorld().strikeLightningEffect(l);
                    fx.jagged(l.clone().add(0, 10, 0), l, 5, 0.6, 0.4, spark);
                    fx.bolt(l.clone().add(0, 12, 0), l, 0.3f, fx.core(w), fx.glass(w), 8);
                    fx.sphere(hc, 1.2, fx.n(30), spark);
                }
            }
            case FROSTBRAND -> {
                if (victim instanceof LivingEntity le) {
                    le.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 60, 1));
                    le.setFreezeTicks(Math.max(le.getFreezeTicks(), 140));
                    fx.debris(Material.BLUE_ICE, 20, 0.35).accept(hc);
                    fx.sp(Particle.SNOWFLAKE, hc, 20, 0.3, 0.5, 0.3, 0.03);
                    fx.sound(hc, Sound.BLOCK_GLASS_BREAK, 0.6f, 1.6f);
                }
            }
            case SHADOWFANG -> {
                Long until = ambush.get(p.getUniqueId());
                boolean empowered = until != null && until >= System.currentTimeMillis();
                boolean behind = victim.getLocation().getDirection().dot(p.getLocation().getDirection()) > 0.5;
                if (empowered || behind) {
                    ambush.remove(p.getUniqueId());
                    e.setDamage(e.getDamage() * 1.5);
                    fx.slashMark(hc, w, 1.0);
                    fx.sp(Particle.SMOKE, hc, 25, 0.3, 0.5, 0.3, 0.05);
                    fx.sound(hc, Sound.ENTITY_PLAYER_ATTACK_CRIT, 1f, 0.8f);
                    fx.sound(hc, Sound.ENTITY_ENDERMAN_TELEPORT, 0.5f, 1.8f);
                }
            }
            case EVENT_HORIZON -> {
                if (victim instanceof LivingEntity le) {
                    // pulled in on the next tick, after the hit's own knockback
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        if (!le.isValid() || !p.isOnline()) return;
                        Vector pull = p.getLocation().toVector().subtract(le.getLocation().toVector());
                        if (pull.lengthSquared() < 0.25) return;
                        pull.normalize().multiply(0.7);
                        pull.setY(0.25);
                        le.setVelocity(pull);
                    });
                    fx.line(hc, p.getLocation().add(0, 1.0, 0), 0.4,
                            fx.combo(fx.dust(w.rgbA, w.rgbB, 1.2f, 1, 0.02), fx.particle(Particle.REVERSE_PORTAL, 1, 0.02, 0.02)));
                }
            }
            case VIPER_FANG -> {
                if (victim instanceof LivingEntity le) {
                    le.addPotionEffect(new PotionEffect(PotionEffectType.POISON, 80, 1));
                    Consumer<Location> venom = fx.combo(fx.dust(w.rgbA, w.rgbB, 1.2f, 1, 0.02), fx.particle(Particle.ITEM_SLIME, 1, 0.03, 0.0));
                    fx.line(hc.clone().add(-0.15, 0.35, 0), hc.clone().add(-0.15, -0.35, 0), 0.1, venom); // twin bite marks
                    fx.line(hc.clone().add(0.15, 0.35, 0), hc.clone().add(0.15, -0.35, 0), 0.1, venom);
                    fx.sp(Particle.ITEM_SLIME, hc, 15, 0.3, 0.5, 0.3, 0.05);
                }
            }
            case EXECUTIONER -> {
                if (victim instanceof LivingEntity le) {
                    AttributeInstance max = le.getAttribute(Attribute.MAX_HEALTH);
                    double cap = max != null ? max.getValue() : 20.0;
                    if (le.getHealth() / cap <= 0.25) {
                        e.setDamage(e.getDamage() * 2.0);
                        fx.slashMark(hc, w, 1.3);
                        fx.sp(Particle.DAMAGE_INDICATOR, hc, 20, 0.3, 0.5, 0.3, 0.1);
                        fx.sound(hc, Sound.ENTITY_PLAYER_ATTACK_CRIT, 1.2f, 0.6f);
                        fx.sound(hc, Sound.BLOCK_ANVIL_LAND, 0.6f, 1.4f);
                    }
                }
            }
            case BENEDICTION -> {
                if (victim instanceof LivingEntity le) {
                    if (UNDEAD.contains(le.getType())) {
                        e.setDamage(e.getDamage() * 1.75);
                        le.setFireTicks(80);
                        fx.sp(Particle.END_ROD, hc, 20, 0.3, 0.5, 0.3, 0.12);
                    }
                    heal(p, e.getDamage() * 0.30);
                    p.addPotionEffect(new PotionEffect(PotionEffectType.ABSORPTION, 100, 1));
                    stream(hc, p, 0, fx.combo(fx.dust(w.rgbA, w.rgbB, 1.0f, 1, 0.02),
                            fx.particle(Particle.HAPPY_VILLAGER, 1, 0.02, 0.0)));
                }
            }
            case MALEDICTION -> {
                if (victim instanceof LivingEntity le) {
                    int curses = countCurses(le);
                    if (curses > 0) e.setDamage(e.getDamage() * (1.0 + Math.min(0.6, curses * 0.12)));
                    PotionEffectType pick = CURSES.get(ThreadLocalRandom.current().nextInt(CURSES.size()));
                    le.addPotionEffect(new PotionEffect(pick, 100, 0));
                    Consumer<Location> curse = fx.combo(fx.dust(w.rgbA, w.rgbB, 1.2f, 1, 0.02),
                            fx.particle(Particle.SOUL_FIRE_FLAME, 1, 0.03, 0.02));
                    fx.sphere(hc, 0.8, fx.n(16), curse);
                    fx.sound(hc, Sound.ENTITY_ELDER_GUARDIAN_CURSE, 0.35f, 1.6f);
                }
            }
            case INFERNAL_SCEPTER -> {
                victim.setFireTicks(80);
                fx.sphere(hc, 0.9, fx.n(20), fx.particle(Particle.FLAME, 1, 0.03, 0.03));
            }
            default -> { }
        }
    }

    private void cleave(Player p, Entity center, double damage) {
        if (damage <= 0) return;
        Location c = center.getLocation();
        World w = c.getWorld();
        for (Entity en : center.getNearbyEntities(3.0, 2.0, 3.0)) {
            if (en.equals(center) || !validTarget(p, en)) continue;
            hurt(p, (LivingEntity) en, damage);
        }
        fx.expandingRing(c.clone().add(0, 1.0, 0), 3.0, 4, 24, fx.signature(LegendaryWeapon.TITAN_WRATH, 2, 0.05));
        fx.sp(Particle.SWEEP_ATTACK, c.clone().add(0, 1, 0), 6, 1.2, 0.3, 1.2, 0);
        fx.sound(c, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 1f, 0.8f);
    }

    // ------------------------------------------------------------------ projectiles

    /** A bow/crossbow legendary was fired. */
    public void onShoot(Player p, LegendaryWeapon w, EntityShootBowEvent e) {
        if (!(e.getProjectile() instanceof AbstractArrow arrow)) return;
        Location eye = p.getEyeLocation();
        Location muzzle = eye.clone().add(eye.getDirection().multiply(1.0));
        switch (w) {
            case VOIDPIERCER -> {
                prepareArrow(arrow, w, 1.4, true, 3, 80L);
                if (e.getForce() >= 0.9f) {
                    Vector v = arrow.getVelocity();
                    for (double deg : new double[]{-9.0, 9.0}) {
                        Arrow extra = p.launchProjectile(Arrow.class, v.clone().rotateAroundY(Math.toRadians(deg)));
                        extra.setPickupStatus(AbstractArrow.PickupStatus.DISALLOWED);
                        extra.setDamage(arrow.getDamage());
                        prepareArrow(extra, w, 1.0, true, 3, 80L);
                    }
                }
                fx.sphere(muzzle, 0.6, fx.n(20), fx.signature(w, 1, 0.03));
                fx.sp(Particle.PORTAL, muzzle, 25, 0.2, 0.2, 0.2, 0.5);
                fx.sound(eye, Sound.ENTITY_ENDER_DRAGON_FLAP, 1f, 1.6f);
                fx.sound(eye, Sound.ENTITY_ENDERMAN_TELEPORT, 0.5f, 1.8f);
            }
            case STARGAZER -> {
                prepareArrow(arrow, w, 1.3, true, 0, 120L);
                homing.put(arrow.getUniqueId(), new Homing(p.getUniqueId()));
                fx.sphere(muzzle, 0.6, fx.n(20), fx.signature(w, 1, 0.03));
                fx.sp(Particle.END_ROD, muzzle, 12, 0.2, 0.2, 0.2, 0.05);
                fx.sound(eye, Sound.ENTITY_EVOKER_CAST_SPELL, 1f, 1.8f);
                fx.sound(eye, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1f, 1.6f);
            }
            case HELLFIRE_CROSSBOW -> {
                prepareArrow(arrow, w, 1.1, false, 0, 200L);
                fx.sphere(muzzle, 0.6, fx.n(20), fx.signature(w, 1, 0.03));
                fx.sp(Particle.FLAME, muzzle, 15, 0.15, 0.15, 0.15, 0.05);
                fx.sound(eye, Sound.ENTITY_BLAZE_SHOOT, 1f, 1.2f);
            }
            default -> { }
        }
    }

    private void prepareArrow(AbstractArrow a, LegendaryWeapon w, double speed, boolean noGravity, int pierce, long lifeTicks) {
        tag(a, w);
        a.setCritical(true);
        if (noGravity) a.setGravity(false);
        if (pierce > 0) a.setPierceLevel(pierce);
        if (speed != 1.0) a.setVelocity(a.getVelocity().multiply(speed));
        // keep stray arrows from lingering forever
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (a.isValid()) a.remove();
        }, lifeTicks);
    }

    /** A trident was thrown: mark it if it is a Stormcaller. */
    public void onTridentLaunch(Player p, Trident t) {
        boolean storm = plugin.legendary().identify(p.getInventory().getItemInMainHand()) == LegendaryWeapon.STORMCALLER
                || plugin.legendary().identify(p.getInventory().getItemInOffHand()) == LegendaryWeapon.STORMCALLER;
        if (storm) {
            tag(t, LegendaryWeapon.STORMCALLER);
            fx.sound(p.getLocation(), Sound.ITEM_TRIDENT_THUNDER, 0.6f, 1.6f);
        }
    }

    /** Bonus damage and impact flourishes from marked arrows. */
    public void onArrowHit(AbstractArrow a, EntityDamageByEntityEvent e) {
        LegendaryWeapon w = tagged(a);
        if (w == null) return;
        Location l = e.getEntity().getLocation().add(0, 1, 0);
        if (w == LegendaryWeapon.VOIDPIERCER) {
            e.setDamage(e.getDamage() * 1.5);
            if (e.getEntity() instanceof LivingEntity le) {
                le.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING, 200, 0, false, false));
            }
            // a tiny rift opens on the target
            fx.sphere(l, 1.0, fx.n(30), fx.signature(w, 1, 0.03));
            fx.ring(l, 0.9, 16, fx.particle(Particle.REVERSE_PORTAL, 1, 0.02, 0.05));
            fx.sp(Particle.PORTAL, l, 30, 0.3, 0.6, 0.3, 0.5);
            fx.sound(l, Sound.ENTITY_ENDERMAN_TELEPORT, 0.6f, 1.8f);
            fx.bloom(l, fx.glass(w), 2.2f, 6);
        } else if (w == LegendaryWeapon.STARGAZER) {
            e.setDamage(e.getDamage() * 1.25);
            fx.constellation(l, w);
            fx.sound(l, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1f, 1.8f);
        }
    }

    /** Explosions, lightning and fireball bursts when a marked projectile lands. */
    public void onProjectileHit(ProjectileHitEvent e) {
        Projectile proj = e.getEntity();
        LegendaryWeapon w = tagged(proj);
        if (w == null || !(proj.getShooter() instanceof Player shooter)) return;
        Location at;
        if (e.getHitEntity() != null) at = e.getHitEntity().getLocation();
        else if (e.getHitBlock() != null) at = e.getHitBlock().getLocation().add(0.5, 1.0, 0.5);
        else at = proj.getLocation();

        switch (w) {
            case HELLFIRE_CROSSBOW -> {
                blast(shooter, at, 4.0, 10.0, 100, w);
                proj.remove();
            }
            case INFERNAL_SCEPTER -> {
                blast(shooter, at, 4.5, 12.0, 120, w);
                proj.remove();
            }
            case STORMCALLER -> lightningChain(shooter, at, e.getHitEntity(), w);
            default -> { }
        }
    }

    /** A fiery burst that damages and ignites but never breaks blocks or hurts the shooter. */
    private void blast(Player by, Location at, double radius, double damage, int fireTicks, LegendaryWeapon w) {
        fx.fireBlast(at, radius, w);
        for (Entity en : at.getWorld().getNearbyEntities(at, radius, radius, radius)) {
            if (!validTarget(by, en)) continue;
            LivingEntity le = (LivingEntity) en;
            if (le.getLocation().distance(at) > radius + 1.0) continue;
            hurt(by, le, damage);
            le.setFireTicks(fireTicks);
        }
    }

    /** Stormcaller: lightning at the impact point that arcs to nearby enemies. */
    private void lightningChain(Player by, Location at, Entity hit, LegendaryWeapon w) {
        World world = at.getWorld();
        Consumer<Location> spark = fx.combo(fx.dust(w.rgbA, w.rgbB, 1.3f, 1, 0.03), fx.particle(Particle.ELECTRIC_SPARK, 2, 0.05, 0.05));
        world.strikeLightningEffect(at);
        fx.expandingRing(at, 3.5, 5, 24, spark);
        fx.jagged(at.clone().add(0, 12, 0), at, 6, 0.8, 0.4, spark);
        fx.bolt(at.clone().add(0, 16, 0), at, 0.4f, fx.core(w), fx.glass(w), 8);
        fx.bloom(at.clone().add(0, 1, 0), fx.glass(w), 3.0f, 6);
        fx.sound(at, Sound.ITEM_TRIDENT_THUNDER, 2f, 1f);

        Set<Entity> done = new HashSet<>();
        if (hit != null && validTarget(by, hit)) {
            hurt(by, (LivingEntity) hit, 12.0);
            done.add(hit);
        }
        Location from = at.clone().add(0, 1, 0);
        int chains = 0;
        for (Entity en : world.getNearbyEntities(at, 6.0, 4.0, 6.0)) {
            if (chains >= 4) break;
            if (done.contains(en) || !validTarget(by, en)) continue;
            LivingEntity le = (LivingEntity) en;
            hurt(by, le, 7.0);
            fx.jagged(from, le.getLocation().add(0, 1, 0), 5, 0.3, 0.3, spark);
            fx.bolt(from, le.getLocation().add(0, 1, 0), 0.18f, fx.core(w), fx.glass(w), 6);
            fx.sphere(le.getLocation().add(0, 1, 0), 0.7, fx.n(14), spark);
            done.add(en);
            chains++;
        }
    }
}
