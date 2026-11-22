package dev.soulbound.smp;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Tameable;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** The powers and passives of the legendary weapons. */
public final class LegendaryAbilities {

    private static final Set<EntityType> UNDEAD = EnumSet.of(
            EntityType.ZOMBIE, EntityType.ZOMBIE_VILLAGER, EntityType.HUSK, EntityType.DROWNED,
            EntityType.SKELETON, EntityType.STRAY, EntityType.BOGGED, EntityType.WITHER_SKELETON,
            EntityType.WITHER, EntityType.PHANTOM, EntityType.ZOMBIFIED_PIGLIN, EntityType.ZOGLIN,
            EntityType.SKELETON_HORSE, EntityType.ZOMBIE_HORSE);

    private static final class Slam {
        final long start = System.currentTimeMillis();
        double maxY;

        Slam(double y) { this.maxY = y; }
    }

    private final SoulboundPlugin plugin;
    private final NamespacedKey arrowKey;
    private final Map<UUID, Slam> slams = new HashMap<>();
    /** True while a power is dealing damage itself, so our own damage hooks do not re-trigger. */
    private boolean internal;
    private BukkitTask task;
    private int ticks;

    public LegendaryAbilities(SoulboundPlugin plugin) {
        this.plugin = plugin;
        this.arrowKey = new NamespacedKey(plugin, "voidpiercer_arrow");
    }

    public void start() {
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 2L, 2L);
    }

    public void shutdown() {
        if (task != null) task.cancel();
        slams.clear();
    }

    public boolean isInternal() { return internal; }

    public boolean isSlamming(Player p) { return slams.containsKey(p.getUniqueId()); }

    // ------------------------------------------------------------------ ticking

    private void tick() {
        try {
            ticks++;
            tickSlams();
            if (ticks % 5 == 0) auras();
        } catch (Exception ex) {
            plugin.getLogger().warning("Legendary tick error: " + ex);
        }
    }

    private void auras() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            LegendaryWeapon w = plugin.legendary().held(p);
            if (w == null) continue;
            p.getWorld().spawnParticle(w.aura, p.getLocation().add(0, 1.0, 0), 3, 0.3, 0.5, 0.3, 0.01);
        }
    }

    private void tickSlams() {
        if (slams.isEmpty()) return;
        long now = System.currentTimeMillis();
        List<Player> landed = new ArrayList<>();
        List<Double> falls = new ArrayList<>();
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
            if (p.isSneaking() && age > 300L && v.getY() < 0.0) {
                p.setVelocity(new Vector(v.getX() * 0.5, -2.2, v.getZ() * 0.5)); // dive
            }
            if (age > 400L && p.isOnGround()) {
                it.remove();
                landed.add(p);
                falls.add(s.maxY - p.getLocation().getY());
            }
        }
        // resolve outside the iterator
        for (int i = 0; i < landed.size(); i++) shockwave(landed.get(i), falls.get(i));
    }

    // ------------------------------------------------------------------ helpers

    private boolean validTarget(Player p, Entity en) {
        if (!(en instanceof LivingEntity le) || en.equals(p) || en instanceof ArmorStand) return false;
        if (le.isInvulnerable() || le.isDead()) return false;
        if (le instanceof Player tp && tp.getGameMode() == GameMode.SPECTATOR) return false;
        if (le instanceof Tameable t && p.getUniqueId().equals(t.getOwnerUniqueId())) return false;
        return true;
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

    // ------------------------------------------------------------------ right-click powers

    public void trigger(Player p, LegendaryWeapon w) {
        long left = plugin.legendary().cooldownLeftMs(p, w);
        if (left > 0) {
            plugin.msg().actionBar(p, w.color + w.power + " <gray>is recharging: <white>" + ((left + 999) / 1000) + "s");
            return;
        }
        switch (w) {
            case GOD_MACE -> heavensFall(p);
            case DAWNBREAKER -> solarBeam(p);
            case SOUL_REAPER -> soulHarvest(p);
            case TITAN_WRATH -> berserkersRage(p);
            default -> { return; }
        }
        plugin.legendary().startCooldown(p, w);
    }

    /** God Mace: leap skyward, then slam down for a shockwave. */
    private void heavensFall(Player p) {
        Location l = p.getLocation();
        World w = l.getWorld();
        Vector v = p.getVelocity();
        p.setFallDistance(0f);
        p.setVelocity(new Vector(v.getX(), 1.8, v.getZ()));
        slams.put(p.getUniqueId(), new Slam(l.getY()));
        w.spawnParticle(Particle.CLOUD, l, 40, 0.5, 0.1, 0.5, 0.15);
        w.spawnParticle(Particle.END_ROD, l.clone().add(0, 1, 0), 40, 0.4, 0.8, 0.4, 0.2);
        w.playSound(l, Sound.ENTITY_ENDER_DRAGON_FLAP, 1.5f, 0.7f);
        w.playSound(l, Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 0.8f, 1.4f);
        plugin.msg().actionBar(p, "<gold>Heaven's Fall! <gray>Hold sneak to dive.");
    }

    private void shockwave(Player p, double fall) {
        Location c = p.getLocation();
        World w = c.getWorld();
        double radius = 7.0;
        double damage = Math.max(8.0, Math.min(40.0, 8.0 + Math.max(0.0, fall) * 0.8));

        w.strikeLightningEffect(c);
        w.spawnParticle(Particle.EXPLOSION_EMITTER, c, 1);
        w.spawnParticle(Particle.CLOUD, c, 60, radius / 2, 0.2, radius / 2, 0.1);
        for (int ring = 1; ring <= 3; ring++) {
            double r = ring * 2.2;
            for (int i = 0; i < 28; i++) {
                double a = i * (Math.PI * 2 / 28);
                Location pt = c.clone().add(Math.cos(a) * r, 0.2, Math.sin(a) * r);
                w.spawnParticle(Particle.END_ROD, pt, 1, 0, 0.1, 0, 0.02);
            }
        }
        w.playSound(c, Sound.ENTITY_LIGHTNING_BOLT_IMPACT, 2f, 0.6f);
        w.playSound(c, Sound.ITEM_TRIDENT_THUNDER, 2f, 0.8f);

        for (Entity en : w.getNearbyEntities(c, radius, radius, radius)) {
            if (!validTarget(p, en)) continue;
            LivingEntity le = (LivingEntity) en;
            double dist = le.getLocation().distance(c);
            if (dist > radius) continue;
            hurt(p, le, damage * (1.0 - 0.5 * (dist / radius)));
            Vector kb = le.getLocation().toVector().subtract(c.toVector());
            kb.setY(0);
            if (kb.lengthSquared() > 0.01) kb.normalize().multiply(1.3);
            else kb = new Vector(0, 0, 0);
            kb.setY(0.7);
            le.setVelocity(kb);
        }
    }

    /** Dawnbreaker: a piercing, burning beam. */
    private void solarBeam(Player p) {
        Location eye = p.getEyeLocation();
        Vector dir = eye.getDirection().normalize();
        World w = p.getWorld();
        Set<LivingEntity> hit = new LinkedHashSet<>();
        for (double d = 1.0; d <= 26.0; d += 0.5) {
            Location pt = eye.clone().add(dir.clone().multiply(d));
            if (pt.getBlock().getType().isSolid()) break;
            w.spawnParticle(Particle.FLAME, pt, 2, 0.05, 0.05, 0.05, 0.0);
            w.spawnParticle(Particle.END_ROD, pt, 1, 0, 0, 0, 0);
            for (Entity en : w.getNearbyEntities(pt, 1.0, 1.0, 1.0)) {
                if (validTarget(p, en)) hit.add((LivingEntity) en);
            }
        }
        w.playSound(eye, Sound.ENTITY_BLAZE_SHOOT, 1.5f, 0.6f);
        w.playSound(eye, Sound.ENTITY_LIGHTNING_BOLT_IMPACT, 1.0f, 1.6f);
        for (LivingEntity le : hit) {
            hurt(p, le, 14.0);
            le.setFireTicks(100);
            le.getWorld().spawnParticle(Particle.FLAME, le.getLocation().add(0, 1, 0), 25, 0.3, 0.6, 0.3, 0.05);
        }
        if (!hit.isEmpty()) plugin.msg().actionBar(p, "<yellow>Solar Beam hit <white>" + hit.size() + "<yellow> target(s)");
    }

    /** Soul Reaper: drain everything nearby and heal for each soul. */
    private void soulHarvest(Player p) {
        Location c = p.getLocation();
        World w = c.getWorld();
        double radius = 8.0;
        int souls = 0;
        for (Entity en : w.getNearbyEntities(c, radius, radius, radius)) {
            if (!validTarget(p, en)) continue;
            LivingEntity le = (LivingEntity) en;
            if (le.getLocation().distance(c) > radius) continue;
            hurt(p, le, 8.0);
            souls++;
            // a trail of souls flowing from the victim to the reaper
            Location from = le.getLocation().add(0, 1, 0);
            Vector step = p.getLocation().add(0, 1, 0).toVector().subtract(from.toVector()).multiply(1.0 / 10.0);
            Location pt = from.clone();
            for (int i = 0; i < 10; i++) {
                w.spawnParticle(Particle.SOUL, pt, 1, 0, 0, 0, 0);
                pt.add(step);
            }
            w.spawnParticle(Particle.SOUL_FIRE_FLAME, from, 12, 0.3, 0.5, 0.3, 0.02);
        }
        for (int i = 0; i < 36; i++) {
            double a = i * (Math.PI * 2 / 36);
            w.spawnParticle(Particle.SOUL, c.clone().add(Math.cos(a) * radius, 0.3, Math.sin(a) * radius), 1, 0, 0, 0, 0.01);
        }
        w.playSound(c, Sound.ENTITY_WITHER_SHOOT, 1.2f, 0.6f);
        w.playSound(c, Sound.ENTITY_ELDER_GUARDIAN_CURSE, 1.0f, 1.2f);
        if (souls > 0) {
            heal(p, Math.min(souls * 2.0, 12.0));
            plugin.msg().actionBar(p, "<dark_aqua>Harvested <white>" + souls + "<dark_aqua> soul(s)");
        }
    }

    /** Titan's Wrath: a short burst of power. */
    private void berserkersRage(Player p) {
        p.addPotionEffect(new PotionEffect(PotionEffectType.STRENGTH, 200, 1));
        p.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 200, 0));
        p.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, 200, 0));
        Location l = p.getLocation();
        World w = l.getWorld();
        w.spawnParticle(Particle.CRIT, l.clone().add(0, 1, 0), 60, 0.5, 0.9, 0.5, 0.4);
        w.spawnParticle(Particle.FLAME, l.clone().add(0, 0.2, 0), 40, 0.6, 0.1, 0.6, 0.05);
        w.playSound(l, Sound.ENTITY_RAVAGER_ROAR, 1.5f, 0.8f);
        plugin.msg().actionBar(p, "<red><bold>BERSERKER'S RAGE!");
    }

    // ------------------------------------------------------------------ passives (melee)

    public void onMelee(Player p, LegendaryWeapon w, EntityDamageByEntityEvent e) {
        Entity victim = e.getEntity();
        if (w.damageMultiplier != 1.0) e.setDamage(e.getDamage() * w.damageMultiplier);

        switch (w) {
            case GOD_MACE -> {
                double fall = p.getFallDistance();
                if (fall > 2.5) {
                    e.setDamage(e.getDamage() + Math.min(20.0, fall * 0.5));
                    Location l = victim.getLocation();
                    World world = l.getWorld();
                    world.strikeLightningEffect(l);
                    world.spawnParticle(Particle.ELECTRIC_SPARK, l.clone().add(0, 1, 0), 40, 0.4, 0.8, 0.4, 0.3);
                    world.playSound(l, Sound.ENTITY_LIGHTNING_BOLT_IMPACT, 1f, 1f);
                }
            }
            case DAWNBREAKER -> {
                if (UNDEAD.contains(victim.getType())) {
                    e.setDamage(e.getDamage() * 1.5);
                    victim.getWorld().spawnParticle(Particle.ENCHANTED_HIT, victim.getLocation().add(0, 1, 0), 25, 0.3, 0.6, 0.3, 0.3);
                }
            }
            case SOUL_REAPER -> {
                heal(p, e.getFinalDamage() * 0.25);
                if (victim instanceof LivingEntity le) {
                    le.addPotionEffect(new PotionEffect(PotionEffectType.WITHER, 60, 1));
                }
                victim.getWorld().spawnParticle(Particle.SOUL, victim.getLocation().add(0, 1, 0), 8, 0.3, 0.5, 0.3, 0.03);
            }
            case TITAN_WRATH -> cleave(p, victim, e.getFinalDamage() * 0.5);
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
        w.spawnParticle(Particle.SWEEP_ATTACK, c.clone().add(0, 1, 0), 6, 1.2, 0.3, 1.2, 0);
        w.playSound(c, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 1f, 0.8f);
    }

    // ------------------------------------------------------------------ Voidpiercer

    public void onShoot(Player p, EntityShootBowEvent e) {
        if (!(e.getProjectile() instanceof AbstractArrow arrow)) return;
        prepareArrow(arrow, 1.4);
        if (e.getForce() >= 0.9f) {
            Vector v = arrow.getVelocity();
            for (double deg : new double[]{-9.0, 9.0}) {
                Arrow extra = p.launchProjectile(Arrow.class, v.clone().rotateAroundY(Math.toRadians(deg)));
                extra.setPickupStatus(AbstractArrow.PickupStatus.DISALLOWED);
                extra.setDamage(arrow.getDamage());
                prepareArrow(extra, 1.0);
            }
        }
        Location l = p.getEyeLocation();
        l.getWorld().playSound(l, Sound.ENTITY_ENDER_DRAGON_FLAP, 1f, 1.6f);
    }

    private void prepareArrow(AbstractArrow a, double speed) {
        a.getPersistentDataContainer().set(arrowKey, PersistentDataType.BYTE, (byte) 1);
        a.setGravity(false);
        a.setPierceLevel(3);
        a.setCritical(true);
        if (speed != 1.0) a.setVelocity(a.getVelocity().multiply(speed));
        // no-gravity arrows never fall, so clean them up
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (a.isValid()) a.remove();
        }, 80L);
    }

    public void onArrowHit(AbstractArrow a, EntityDamageByEntityEvent e) {
        if (!a.getPersistentDataContainer().has(arrowKey, PersistentDataType.BYTE)) return;
        e.setDamage(e.getDamage() * 1.5);
        if (e.getEntity() instanceof LivingEntity le) {
            le.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING, 200, 0, false, false));
            le.getWorld().spawnParticle(Particle.PORTAL, le.getLocation().add(0, 1, 0), 30, 0.3, 0.6, 0.3, 0.5);
        }
    }
}
