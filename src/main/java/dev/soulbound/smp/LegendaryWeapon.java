package dev.soulbound.smp;

import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.enchantments.Enchantment;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Every legendary weapon: look, enchants, powers and default crafting recipe. */
public enum LegendaryWeapon {

    GOD_MACE("god_mace", "God Mace", "<gold>", Material.MACE, Particle.END_ROD, true, 20, 1.0,
            "Heaven's Fall",
            List.of("Launch yourself skyward, then crash down.",
                    "Hold sneak while falling to dive.",
                    "Landing unleashes a huge shockwave."),
            List.of("No fall damage while held",
                    "Smash attacks call down lightning"),
            new Object[]{Enchantment.DENSITY, 10, Enchantment.BREACH, 7, Enchantment.WIND_BURST, 5,
                    Enchantment.UNBREAKING, 10, Enchantment.MENDING, 1},
            "NHN/DMD/NEN",
            new Object[]{'N', Material.NETHER_STAR, 'H', Material.HEAVY_CORE, 'D', Material.DIAMOND_BLOCK,
                    'M', Material.MACE, 'E', Material.ENCHANTED_GOLDEN_APPLE}),

    DAWNBREAKER("dawnbreaker", "Dawnbreaker", "<yellow>", Material.NETHERITE_SWORD, Particle.FLAME, true, 12, 1.2,
            "Solar Beam",
            List.of("Fire a beam of sunlight that pierces",
                    "everything in its path and sets it ablaze."),
            List.of("Undead take 50% more damage"),
            new Object[]{Enchantment.SHARPNESS, 10, Enchantment.SWEEPING_EDGE, 5, Enchantment.FIRE_ASPECT, 3,
                    Enchantment.LOOTING, 5, Enchantment.UNBREAKING, 10, Enchantment.MENDING, 1},
            "GNG/DSD/GTG",
            new Object[]{'G', Material.GOLD_BLOCK, 'N', Material.NETHER_STAR, 'D', Material.DIAMOND_BLOCK,
                    'S', Material.NETHERITE_SWORD, 'T', Material.TOTEM_OF_UNDYING}),

    SOUL_REAPER("soul_reaper", "Soul Reaper", "<dark_aqua>", Material.NETHERITE_HOE, Particle.SOUL, true, 25, 1.8,
            "Soul Harvest",
            List.of("Drain every enemy around you and",
                    "heal for each soul you take."),
            List.of("Steals 25% of damage dealt as health",
                    "Hits inflict Wither"),
            new Object[]{Enchantment.SHARPNESS, 8, Enchantment.LOOTING, 5, Enchantment.EFFICIENCY, 10,
                    Enchantment.UNBREAKING, 10, Enchantment.MENDING, 1},
            "WNW/THT/DDD",
            new Object[]{'W', Material.WITHER_SKELETON_SKULL, 'N', Material.NETHER_STAR, 'T', Material.TOTEM_OF_UNDYING,
                    'H', Material.NETHERITE_HOE, 'D', Material.DIAMOND_BLOCK}),

    VOIDPIERCER("voidpiercer", "Voidpiercer", "<light_purple>", Material.BOW, Particle.PORTAL, false, 0, 1.0,
            null,
            List.of(),
            List.of("Arrows fly straight and pierce targets",
                    "Full draws fire two extra arrows",
                    "Struck targets glow and take 50% more damage"),
            new Object[]{Enchantment.POWER, 10, Enchantment.PUNCH, 3, Enchantment.INFINITY, 1,
                    Enchantment.UNBREAKING, 10, Enchantment.MENDING, 1},
            "ENE/DBD/ENE",
            new Object[]{'E', Material.ENDER_EYE, 'N', Material.NETHER_STAR, 'D', Material.DIAMOND_BLOCK,
                    'B', Material.BOW}),

    TITAN_WRATH("titan_wrath", "Titan's Wrath", "<red>", Material.NETHERITE_AXE, Particle.CRIT, true, 45, 1.3,
            "Berserker's Rage",
            List.of("Gain Strength II, Speed and Resistance",
                    "for 10 seconds."),
            List.of("Hits cleave nearby enemies for 50% damage"),
            new Object[]{Enchantment.SHARPNESS, 10, Enchantment.EFFICIENCY, 10, Enchantment.KNOCKBACK, 2,
                    Enchantment.UNBREAKING, 10, Enchantment.MENDING, 1},
            "DND/TAT/DND",
            new Object[]{'D', Material.DIAMOND_BLOCK, 'N', Material.NETHERITE_BLOCK, 'T', Material.TOTEM_OF_UNDYING,
                    'A', Material.NETHERITE_AXE});

    public final String id;
    public final String display;
    /** MiniMessage colour tag, e.g. "<gold>". */
    public final String color;
    public final Material material;
    public final Particle aura;
    /** true = has a right-click power. */
    public final boolean active;
    public final int cooldown;
    public final double damageMultiplier;
    /** Name of the right-click power (null if none). */
    public final String power;
    public final List<String> powerLines;
    public final List<String> passiveLines;
    private final Object[] enchantPairs;
    private final String shape;
    private final Object[] ingredientPairs;

    LegendaryWeapon(String id, String display, String color, Material material, Particle aura, boolean active,
                    int cooldown, double damageMultiplier, String power, List<String> powerLines,
                    List<String> passiveLines, Object[] enchantPairs, String shape, Object[] ingredientPairs) {
        this.id = id;
        this.display = display;
        this.color = color;
        this.material = material;
        this.aura = aura;
        this.active = active;
        this.cooldown = cooldown;
        this.damageMultiplier = damageMultiplier;
        this.power = power;
        this.powerLines = powerLines;
        this.passiveLines = passiveLines;
        this.enchantPairs = enchantPairs;
        this.shape = shape;
        this.ingredientPairs = ingredientPairs;
    }

    public Map<Enchantment, Integer> enchants() {
        Map<Enchantment, Integer> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < enchantPairs.length; i += 2) {
            m.put((Enchantment) enchantPairs[i], (Integer) enchantPairs[i + 1]);
        }
        return m;
    }

    public String[] defaultShape() { return shape.split("/"); }

    public Map<Character, Material> defaultIngredients() {
        Map<Character, Material> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < ingredientPairs.length; i += 2) {
            m.put((Character) ingredientPairs[i], (Material) ingredientPairs[i + 1]);
        }
        return m;
    }

    public static LegendaryWeapon byId(String s) {
        if (s == null) return null;
        String k = s.toLowerCase(Locale.ROOT);
        for (LegendaryWeapon w : values()) if (w.id.equals(k)) return w;
        return null;
    }
}
