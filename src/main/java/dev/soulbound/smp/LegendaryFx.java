package dev.soulbound.smp;

import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * Visual toolkit for the legendary weapons.
 * <p>
 * A "brush" is a {@code Consumer<Location>} that paints something at a point. Shapes (rings, helixes,
 * lines, lightning, spheres...) call a brush for each point, so any shape can be drawn with any look.
 * Particle counts scale with {@code legendary.effects.intensity} in config.yml.
 */
public final class LegendaryFx {

    private final SoulboundPlugin plugin;

    public LegendaryFx(SoulboundPlugin plugin) { this.plugin = plugin; }

    // ------------------------------------------------------------------ config

    public boolean enabled(String key) {
        return plugin.getConfig().getBoolean("legendary.effects." + key, true);
    }

    private double intensity() {
        return Math.max(0.1, Math.min(3.0, plugin.getConfig().getDouble("legendary.effects.intensity", 1.0)));
    }

    /** Scales a particle count by the configured intensity (never below 1). */
    public int n(int base) {
        return Math.max(1, (int) Math.round(base * intensity()));
    }

    // ------------------------------------------------------------------ raw helpers

    public void sp(Particle particle, Location l, int count, double ox, double oy, double oz, double speed) {
        l.getWorld().spawnParticle(particle, l, n(count), ox, oy, oz, speed);
    }

    public void sound(Location l, Sound s, float volume, float pitch) {
        l.getWorld().playSound(l, s, volume, pitch);
    }

    public void animate(int duration, IntConsumer frame) {
        new BukkitRunnable() {
            int t = 0;

            @Override
            public void run() {
                try {
                    frame.accept(t);
                } catch (RuntimeException ex) {
                    cancel();
                    return;
                }
                if (++t >= duration) cancel();
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }

    public void later(int ticks, Runnable r) {
        Bukkit.getScheduler().runTaskLater(plugin, r, Math.max(1, ticks));
    }

    // ------------------------------------------------------------------ brushes

    public Consumer<Location> particle(Particle p, int count, double spread, double speed) {
        return l -> l.getWorld().spawnParticle(p, l, n(count), spread, spread, spread, speed);
    }

    /** Coloured dust that fades from colour {@code a} to colour {@code b}. */
    public Consumer<Location> dust(Color a, Color b, float size, int count, double spread) {
        Particle.DustTransition dt = new Particle.DustTransition(a, b, size);
        return l -> l.getWorld().spawnParticle(Particle.DUST_COLOR_TRANSITION, l, n(count), spread, spread, spread, 0, dt);
    }

    /** Block-crack debris, e.g. for ground impacts. */
    public Consumer<Location> debris(Material m, int count, double spread) {
        BlockData data = m.createBlockData();
        return l -> l.getWorld().spawnParticle(Particle.BLOCK, l, n(count), spread, spread, spread, 0.1, data);
    }

    public Consumer<Location> combo(Consumer<Location> a, Consumer<Location> b) {
        return l -> {
            a.accept(l);
            b.accept(l);
        };
    }

    /** The weapon's colours plus its accent particle: its visual signature. */
    public Consumer<Location> signature(LegendaryWeapon w, int count, double spread) {
        return combo(dust(w.rgbA, w.rgbB, 1.3f, count, spread),
                particle(w.accent, Math.max(1, count / 3), spread, 0.02));
    }

    public Consumer<Location> accent(LegendaryWeapon w, int count, double spread) {
        return particle(w.accent, count, spread, 0.03);
    }

    /** What a flying projectile leaves behind. */
    public Consumer<Location> trail(LegendaryWeapon w) {
        Consumer<Location> base = signature(w, 2, 0.06);
        return switch (w) {
            case VOIDPIERCER -> combo(base, particle(Particle.PORTAL, 2, 0.1, 0.3));
            case STARGAZER -> combo(base, particle(Particle.END_ROD, 1, 0.04, 0.01));
            case HELLFIRE_CROSSBOW -> combo(base, combo(particle(Particle.SMALL_FLAME, 1, 0.05, 0.01),
                    particle(Particle.SMOKE, 1, 0.05, 0.01)));
            case STORMCALLER -> combo(base, particle(Particle.ELECTRIC_SPARK, 2, 0.15, 0.1));
            case INFERNAL_SCEPTER -> combo(base, combo(particle(Particle.FLAME, 3, 0.12, 0.02),
                    particle(Particle.LAVA, 1, 0.1, 0.0)));
            default -> base;
        };
    }

    // ------------------------------------------------------------------ shapes

    public void ring(Location c, double r, int points, Consumer<Location> brush) {
        for (int i = 0; i < points; i++) {
            double a = i * (Math.PI * 2 / points);
            brush.accept(c.clone().add(Math.cos(a) * r, 0, Math.sin(a) * r));
        }
    }

    public void helix(Location base, double height, double radius, double turns, int points, Consumer<Location> brush) {
        for (int i = 0; i < points; i++) {
            double t = i / (double) Math.max(1, points - 1);
            double a = t * turns * Math.PI * 2;
            brush.accept(base.clone().add(Math.cos(a) * radius, t * height, Math.sin(a) * radius));
        }
    }

    public void line(Location a, Location b, double spacing, Consumer<Location> brush) {
        Vector d = b.toVector().subtract(a.toVector());
        double len = d.length();
        if (len < 0.01) {
            brush.accept(a.clone());
            return;
        }
        d.normalize().multiply(spacing);
        int steps = (int) (len / spacing);
        Location p = a.clone();
        for (int i = 0; i <= steps; i++) {
            brush.accept(p.clone());
            p.add(d);
        }
    }

    /** A jittery, lightning-like line. */
    public void jagged(Location a, Location b, int segments, double jitter, double spacing, Consumer<Location> brush) {
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        Vector d = b.toVector().subtract(a.toVector());
        Location prev = a.clone();
        for (int i = 1; i <= segments; i++) {
            Location next = a.clone().add(d.clone().multiply(i / (double) segments));
            if (i < segments) {
                next.add((rnd.nextDouble() - 0.5) * 2 * jitter, (rnd.nextDouble() - 0.5) * 2 * jitter,
                        (rnd.nextDouble() - 0.5) * 2 * jitter);
            }
            line(prev, next, spacing, brush);
            prev = next;
        }
    }

    /** Evenly spread points on a sphere surface. */
    public void sphere(Location c, double r, int points, Consumer<Location> brush) {
        for (int i = 0; i < points; i++) {
            double y = 1 - 2 * (i + 0.5) / points;
            double rad = Math.sqrt(Math.max(0.0, 1 - y * y));
            double phi = i * 2.399963229728653;
            brush.accept(c.clone().add(Math.cos(phi) * rad * r, y * r, Math.sin(phi) * rad * r));
        }
    }

    public void column(Location base, double height, double step, Consumer<Location> brush) {
        for (double y = 0; y <= height; y += step) brush.accept(base.clone().add(0, y, 0));
    }

    /** A flat horizontal arc in front of {@code forward}, for sword-style swing trails. */
    public void arc(Location origin, Vector forward, double radius, double fromDeg, double toDeg, int points,
                    double y, Consumer<Location> brush) {
        Vector f = forward.clone().setY(0);
        if (f.lengthSquared() < 1.0E-4) return;
        f.normalize();
        for (int i = 0; i < points; i++) {
            double k = points == 1 ? 0.5 : i / (double) (points - 1);
            double deg = fromDeg + (toDeg - fromDeg) * k;
            Vector d = f.clone().rotateAroundY(Math.toRadians(deg)).multiply(radius);
            brush.accept(origin.clone().add(d.getX(), y, d.getZ()));
        }
    }

    /** An overhead arc in front of the eyes, for axe/mace-style swing trails. */
    public void arcVertical(Location eye, double radius, double fromDeg, double toDeg, int points,
                            Consumer<Location> brush) {
        Vector f = eye.getDirection().setY(0);
        if (f.lengthSquared() < 1.0E-4) f = new Vector(0, 0, 1);
        f.normalize();
        for (int i = 0; i < points; i++) {
            double k = points == 1 ? 0.5 : i / (double) (points - 1);
            double rad = Math.toRadians(fromDeg + (toDeg - fromDeg) * k);
            brush.accept(eye.clone().add(f.getX() * Math.cos(rad) * radius, Math.sin(rad) * radius - 0.2,
                    f.getZ() * Math.cos(rad) * radius));
        }
    }

    /** A corkscrew wrapped around the straight line from {@code from} to {@code to}. */
    public void spiralAlong(Location from, Location to, double radius, double turns, int points, Consumer<Location> brush) {
        Vector d = to.toVector().subtract(from.toVector());
        if (d.length() < 0.01) return;
        Vector dir = d.clone().normalize();
        Vector up = Math.abs(dir.getY()) < 0.99 ? new Vector(0, 1, 0) : new Vector(1, 0, 0);
        Vector u = dir.clone().crossProduct(up).normalize();
        Vector v = dir.clone().crossProduct(u).normalize();
        for (int i = 0; i < points; i++) {
            double t = i / (double) Math.max(1, points - 1);
            double a = t * turns * Math.PI * 2;
            brush.accept(from.clone().add(d.clone().multiply(t))
                    .add(u.clone().multiply(Math.cos(a) * radius))
                    .add(v.clone().multiply(Math.sin(a) * radius)));
        }
    }

    public void expandingRing(Location c, double maxRadius, int duration, int points, Consumer<Location> brush) {
        animate(duration, t -> ring(c, maxRadius * (t + 1) / duration, points, brush));
    }

    // ------------------------------------------------------------------ composite effects

    /** An "X" slash through a point, drawn in the weapon's colours. */
    public void slashMark(Location centre, LegendaryWeapon w, double size) {
        Consumer<Location> b = combo(dust(w.rgbA, w.rgbB, 1.2f, 1, 0.0), particle(Particle.CRIT, 1, 0.02, 0.05));
        line(centre.clone().add(-size, size, -size * 0.5), centre.clone().add(size, -size, size * 0.5), 0.2, b);
        line(centre.clone().add(size, size, -size * 0.5), centre.clone().add(-size, -size, size * 0.5), 0.2, b);
    }

    /** Random "stars" joined by lines, centred on a point. */
    public void constellation(Location c, LegendaryWeapon w) {
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        Consumer<Location> lineBrush = dust(w.rgbA, w.rgbB, 0.8f, 1, 0.0);
        Consumer<Location> star = combo(particle(Particle.END_ROD, 2, 0.02, 0.01), particle(Particle.GLOW, 2, 0.05, 0.02));
        Location prev = null;
        for (int i = 0; i < 5; i++) {
            Location pt = c.clone().add((rnd.nextDouble() - 0.5) * 3.0, (rnd.nextDouble() - 0.5) * 2.0,
                    (rnd.nextDouble() - 0.5) * 3.0);
            star.accept(pt);
            if (prev != null) line(prev, pt, 0.3, lineBrush);
            prev = pt;
        }
    }

    /** A fiery expanding sphere, used by explosive bolts and fireballs. */
    public void fireBlast(Location at, double radius, LegendaryWeapon w) {
        World world = at.getWorld();
        Consumer<Location> flames = combo(signature(w, 1, 0.1), particle(Particle.FLAME, 1, 0.1, 0.02));
        Consumer<Location> smoke = particle(Particle.SMOKE, 1, 0.1, 0.02);
        world.spawnParticle(Particle.EXPLOSION, at, 2, 0.3, 0.3, 0.3, 0);
        debris(Material.MAGMA_BLOCK, 30, 0.6).accept(at);
        sound(at, Sound.ENTITY_LIGHTNING_BOLT_IMPACT, 1.5f, 1.4f);
        sound(at, Sound.ENTITY_BLAZE_SHOOT, 1.5f, 0.5f);
        animate(6, t -> {
            double r = radius * (t + 1) / 6.0;
            sphere(at, r, n(45), flames);
            if (t >= 2) sphere(at, r * 0.8, n(18), smoke);
            if (t == 5) {
                ring(at, radius, 24, particle(Particle.DRIPPING_LAVA, 1, 0.1, 0.0));
                ring(at, radius, 24, particle(Particle.LAVA, 1, 0.1, 0.0));
            }
        });
    }

    /** A pillar of light for a dropped legendary weapon. */
    public void beacon(Location base, LegendaryWeapon w) {
        Consumer<Location> b = signature(w, 1, 0.05);
        column(base, 7.0, 0.7, b);
        sp(Particle.END_ROD, base.clone().add(0, 0.4, 0), 2, 0.2, 0.2, 0.2, 0.02);
    }

    /** Celebration when a legendary weapon is forged. */
    public void forge(Location where, LegendaryWeapon w) {
        Location base = where.clone();
        World world = base.getWorld();
        world.strikeLightningEffect(base);
        Consumer<Location> sig = signature(w, 2, 0.03);
        sound(base, Sound.BLOCK_BEACON_ACTIVATE, 2f, 1.2f);
        animate(40, t -> {
            for (int arm = 0; arm < 3; arm++) {
                double a = t * 0.45 + arm * (Math.PI * 2 / 3);
                double radius = 1.6 - t * 0.03;
                sig.accept(base.clone().add(Math.cos(a) * radius, t * 0.09, Math.sin(a) * radius));
            }
            if (t % 8 == 0) ring(base.clone().add(0, 0.1, 0), 0.8 + t * 0.07, 24, sig);
            if (t == 39) {
                sphere(base.clone().add(0, 1.5, 0), 1.8, n(60), signature(w, 2, 0.05));
                sp(Particle.FIREWORK, base.clone().add(0, 1.8, 0), 40, 0.6, 0.6, 0.6, 0.15);
                sp(Particle.TOTEM_OF_UNDYING, base.clone().add(0, 1.2, 0), 60, 0.6, 0.8, 0.6, 0.4);
                sound(base, Sound.UI_TOAST_CHALLENGE_COMPLETE, 1.5f, 1.0f);
                sound(base, Sound.ITEM_TRIDENT_THUNDER, 1.5f, 1.2f);
            }
        });
    }
}
