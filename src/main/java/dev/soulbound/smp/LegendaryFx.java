package dev.soulbound.smp;

import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.HashSet;
import java.util.Set;
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
    /** Solid effect entities currently alive, so they can all be removed on shutdown. */
    private final Set<BlockDisplay> live = new HashSet<>();

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

    /** Dust particles are drawn this much bigger than their nominal size (they are tiny by default). */
    private float sizeScale() {
        return (float) Math.max(0.5, Math.min(4.0, plugin.getConfig().getDouble("legendary.effects.particle-size", 1.6)));
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
                    plugin.getLogger().warning("Legendary effect frame failed: " + ex);
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
        Particle.DustTransition dt = new Particle.DustTransition(a, b, size * sizeScale());
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
        return combo(dust(w.rgbA, w.rgbB, 1.3f, count * 2, spread),
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

    // ------------------------------------------------------------------ solid (block display) visuals

    /**
     * Real glowing geometry: beams, rings, bursts and spikes drawn with block display entities. Unlike
     * particles these are always clearly visible. They are full-bright, non-persistent, and removed
     * automatically a few ticks after they appear.
     */
    public boolean solid() { return enabled("solid"); }

    /** Removes every solid effect still alive (called on shutdown). */
    public void cleanup() {
        for (BlockDisplay d : live) {
            if (d.isValid()) d.remove();
        }
        live.clear();
    }

    /** The bright inner material of a weapon's solid effects. */
    public Material core(LegendaryWeapon w) {
        return switch (w) {
            case GOD_MACE -> Material.GOLD_BLOCK;
            case DAWNBREAKER, THUNDER_ROD, BENEDICTION -> Material.GLOWSTONE;
            case SOUL_REAPER, STORMCALLER, GOD_ELYTRA -> Material.SEA_LANTERN;
            case VOIDPIERCER, MALEDICTION -> Material.CRYING_OBSIDIAN;
            case TITAN_WRATH, EXECUTIONER -> Material.REDSTONE_BLOCK;
            case HELLFIRE_CROSSBOW, INFERNAL_SCEPTER -> Material.MAGMA_BLOCK;
            case FROSTBRAND -> Material.BLUE_ICE;
            case SHADOWFANG, EVENT_HORIZON -> Material.BLACK_CONCRETE;
            case VIPER_FANG -> Material.LIME_CONCRETE;
            case STARGAZER -> Material.WHITE_CONCRETE;
        };
    }

    /** The translucent outer material of a weapon's solid effects. */
    public Material glass(LegendaryWeapon w) {
        return switch (w) {
            case GOD_MACE, THUNDER_ROD, BENEDICTION -> Material.YELLOW_STAINED_GLASS;
            case DAWNBREAKER, HELLFIRE_CROSSBOW, INFERNAL_SCEPTER -> Material.ORANGE_STAINED_GLASS;
            case SOUL_REAPER -> Material.CYAN_STAINED_GLASS;
            case VOIDPIERCER, SHADOWFANG, EVENT_HORIZON -> Material.PURPLE_STAINED_GLASS;
            case TITAN_WRATH, EXECUTIONER, MALEDICTION -> Material.RED_STAINED_GLASS;
            case STORMCALLER, FROSTBRAND, GOD_ELYTRA -> Material.LIGHT_BLUE_STAINED_GLASS;
            case VIPER_FANG -> Material.LIME_STAINED_GLASS;
            case STARGAZER -> Material.WHITE_STAINED_GLASS;
        };
    }

    private BlockDisplay spawnBlock(Location at, Material mat, Transformation tr) {
        BlockDisplay d = at.getWorld().spawn(at, BlockDisplay.class, e -> {
            e.setBlock(mat.createBlockData());
            e.setTransformation(tr);
            e.setBrightness(new Display.Brightness(15, 15));
            e.setViewRange(3.0f);
            e.setShadowRadius(0.0f);
            e.setPersistent(false);
            e.setInvulnerable(true);
        });
        live.add(d);
        return d;
    }

    /** Animates a display to {@code end} over {@code ticks}, then removes it. */
    private void retire(BlockDisplay d, int ticks, Transformation end) {
        later(1, () -> {
            if (!d.isValid()) return;
            d.setInterpolationDelay(0);
            d.setInterpolationDuration(Math.max(1, ticks));
            d.setTransformation(end);
        });
        later(ticks + 2, () -> {
            if (d.isValid()) d.remove();
            live.remove(d);
        });
    }

    private Transformation bar(Quaternionf rot, float thickness, float len) {
        Vector3f off = new Vector3f(-thickness / 2f, -thickness / 2f, 0f);
        rot.transform(off);
        return new Transformation(off, new Quaternionf(rot), new Vector3f(thickness, thickness, len), new Quaternionf());
    }

    private Transformation cube(float size, Quaternionf rot) {
        Vector3f off = new Vector3f(-size / 2f, -size / 2f, -size / 2f);
        rot.transform(off);
        return new Transformation(off, new Quaternionf(rot), new Vector3f(size, size, size), new Quaternionf());
    }

    private Transformation slab(Quaternionf rot, Vector3f pos, float thickness, float height, float len) {
        Vector3f c = new Vector3f(thickness / 2f, 0f, len / 2f);
        rot.transform(c);
        return new Transformation(new Vector3f(pos).sub(c), new Quaternionf(rot),
                new Vector3f(thickness, height, len), new Quaternionf());
    }

    /** A solid glowing bar between two points that thins away over {@code life} ticks. */
    public void beam(Location from, Location to, float thickness, Material mat, int life) {
        if (!solid()) return;
        Vector d = to.toVector().subtract(from.toVector());
        float len = (float) d.length();
        if (len < 0.05f) return;
        Vector3f dir = new Vector3f((float) (d.getX() / len), (float) (d.getY() / len), (float) (d.getZ() / len));
        Quaternionf rot = new Quaternionf().rotationTo(new Vector3f(0f, 0f, 1f), dir);
        BlockDisplay bd = spawnBlock(from, mat, bar(rot, thickness, len));
        retire(bd, life, bar(rot, 0.02f, len));
    }

    /** A jagged lightning bolt made of solid segments, with a wide translucent glow around a bright core. */
    public void bolt(Location top, Location bottom, float thickness, Material core, Material glass, int life) {
        if (!solid()) return;
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        int segs = Math.max(4, (int) (top.distance(bottom) / 3.0));
        Vector span = bottom.toVector().subtract(top.toVector());
        Location prev = top.clone();
        for (int i = 1; i <= segs; i++) {
            Location next = top.clone().add(span.clone().multiply(i / (double) segs));
            if (i < segs) next.add((rnd.nextDouble() - 0.5) * 2.4, 0.0, (rnd.nextDouble() - 0.5) * 2.4);
            beam(prev, next, thickness * 2.6f, glass, life);
            beam(prev, next, thickness, core, life);
            prev = next;
        }
    }

    /** A ring of solid segments sweeping from {@code radius0} to {@code radius1} (outward or inward). */
    public void shockRing(Location c, double radius0, double radius1, float height, float thickness,
                          Material mat, int life) {
        if (!solid()) return;
        int segs = 20;
        for (int i = 0; i < segs; i++) {
            double a = i * (Math.PI * 2 / segs);
            Vector3f tangent = new Vector3f((float) -Math.sin(a), 0f, (float) Math.cos(a));
            Quaternionf rot = new Quaternionf().rotationTo(new Vector3f(0f, 0f, 1f), tangent);
            float len0 = (float) (2 * Math.PI * Math.max(0.1, radius0) / segs * 1.2);
            float len1 = (float) (2 * Math.PI * Math.max(0.1, radius1) / segs * 1.2);
            Vector3f pos0 = new Vector3f((float) (Math.cos(a) * radius0), 0f, (float) (Math.sin(a) * radius0));
            Vector3f pos1 = new Vector3f((float) (Math.cos(a) * radius1), 0f, (float) (Math.sin(a) * radius1));
            BlockDisplay bd = spawnBlock(c, mat, slab(rot, pos0, thickness, height, len0));
            retire(bd, life, slab(rot, pos1, thickness, 0.02f, len1));
        }
    }

    /** A crystal that shoots up out of the ground, lingers, then shrinks away. */
    public void spike(Location base, float height, float width, Material mat, int growTicks, int life) {
        if (!solid()) return;
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        Quaternionf rot = new Quaternionf().rotationXYZ((float) ((rnd.nextDouble() - 0.5) * 0.5),
                (float) (rnd.nextDouble() * Math.PI), (float) ((rnd.nextDouble() - 0.5) * 0.5));
        BlockDisplay bd = spawnBlock(base, mat, spikeShape(rot, width, 0.05f));
        later(1, () -> {
            if (!bd.isValid()) return;
            bd.setInterpolationDelay(0);
            bd.setInterpolationDuration(Math.max(1, growTicks));
            bd.setTransformation(spikeShape(rot, width, height));
        });
        later(Math.max(growTicks + 2, life), () -> retire(bd, 6, spikeShape(rot, width, 0.02f)));
    }

    private Transformation spikeShape(Quaternionf rot, float width, float height) {
        return new Transformation(new Vector3f(-width / 2f, 0f, -width / 2f), new Quaternionf(rot),
                new Vector3f(width, height, width), new Quaternionf());
    }

    /** A solid cube that blooms outward from a point while spinning, then pops. */
    public void bloom(Location centre, Material mat, float size, int life) {
        if (!solid()) return;
        BlockDisplay bd = spawnBlock(centre, mat, cube(0.1f, new Quaternionf()));
        retire(bd, life, cube(size, new Quaternionf().rotationXYZ(0.8f, 1.2f, 0.4f)));
    }

    /** A solid cube that spins and collapses to nothing, like something being sucked in. */
    public void collapse(Location centre, Material mat, float size, int life) {
        if (!solid()) return;
        BlockDisplay bd = spawnBlock(centre, mat, cube(size, new Quaternionf()));
        retire(bd, life, cube(0.05f, new Quaternionf().rotationXYZ(1.0f, 1.6f, 0.6f)));
    }

    // ------------------------------------------------------------------ composite effects

    /** An "X" slash through a point, drawn in the weapon's colours. */
    public void slashMark(Location centre, LegendaryWeapon w, double size) {
        Consumer<Location> b = combo(dust(w.rgbA, w.rgbB, 1.2f, 1, 0.0), particle(Particle.CRIT, 1, 0.02, 0.05));
        line(centre.clone().add(-size, size, -size * 0.5), centre.clone().add(size, -size, size * 0.5), 0.2, b);
        line(centre.clone().add(size, size, -size * 0.5), centre.clone().add(-size, -size, size * 0.5), 0.2, b);
        if (solid()) {
            Location a1 = centre.clone().add(-size, size, -size * 0.5);
            Location a2 = centre.clone().add(size, -size, size * 0.5);
            Location b1 = centre.clone().add(size, size, -size * 0.5);
            Location b2 = centre.clone().add(-size, -size, size * 0.5);
            beam(a1, a2, 0.16f, glass(w), 8);
            beam(a1, a2, 0.07f, core(w), 7);
            beam(b1, b2, 0.16f, glass(w), 8);
            beam(b1, b2, 0.07f, core(w), 7);
        }
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
            bloom(pt, core(w), 0.22f, 18);
            if (prev != null) {
                line(prev, pt, 0.3, lineBrush);
                beam(prev, pt, 0.06f, core(w), 24);
            }
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
        if (solid()) {
            bloom(at, glass(w), (float) radius * 2.2f, 7);
            bloom(at, core(w), (float) radius * 1.0f, 6);
            shockRing(at.clone().add(0, 0.1, 0), 0.5, radius, 0.25f, 0.4f, core(w), 8);
        }
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
        beam(base, base.clone().add(0, 9, 0), 0.3f, glass(w), 12);
        beam(base, base.clone().add(0, 9, 0), 0.1f, core(w), 12);
        sp(Particle.END_ROD, base.clone().add(0, 0.4, 0), 2, 0.2, 0.2, 0.2, 0.02);
    }

    /** Celebration when a legendary weapon is forged. */
    public void forge(Location where, LegendaryWeapon w) {
        Location base = where.clone();
        World world = base.getWorld();
        world.strikeLightningEffect(base);
        Consumer<Location> sig = signature(w, 2, 0.03);
        sound(base, Sound.BLOCK_BEACON_ACTIVATE, 2f, 1.2f);
        beam(base, base.clone().add(0, 12, 0), 1.2f, glass(w), 40);
        beam(base, base.clone().add(0, 12, 0), 0.5f, core(w), 40);
        shockRing(base.clone().add(0, 0.1, 0), 0.5, 6.0, 0.3f, 0.4f, core(w), 20);
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
