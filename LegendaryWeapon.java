package dev.soulbound.smp;

import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.enchantments.Enchantment;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Every legendary weapon: look, enchants, powers and default crafting recipe.
 * Each weapon has a two-colour gradient (used for its name and for coloured particles everywhere)
 * plus an "aura" and an "accent" particle that form its visual signature.
 */
public enum LegendaryWeapon {

    GOD_MACE("god_mace", "God Mace", "#ffd54a", "#ff8f00", Material.MACE, Particle.END_ROD, Particle.FIREWORK, true, 20, 1.0,
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

    DAWNBREAKER("dawnbreaker", "Dawnbreaker", "#fff59d", "#ff7043", Material.NETHERITE_SWORD, Particle.FLAME, Particle.END_ROD, true, 12, 1.2,
            "Solar Beam",
            List.of("Fire a beam of sunlight that pierces",
                    "everything in its path and sets it ablaze."),
            List.of("Undead take 50% more damage"),
            new Object[]{Enchantment.SHARPNESS, 10, Enchantment.SWEEPING_EDGE, 5, Enchantment.FIRE_ASPECT, 3,
                    Enchantment.LOOTING, 5, Enchantment.UNBREAKING, 10, Enchantment.MENDING, 1},
            "GNG/DSD/GTG",
            new Object[]{'G', Material.GOLD_BLOCK, 'N', Material.NETHER_STAR, 'D', Material.DIAMOND_BLOCK,
                    'S', Material.NETHERITE_SWORD, 'T', Material.TOTEM_OF_UNDYING}),

    SOUL_REAPER("soul_reaper", "Soul Reaper", "#69f0ae", "#0097a7", Material.NETHERITE_HOE, Particle.SOUL, Particle.SOUL_FIRE_FLAME, true, 25, 1.8,
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

    VOIDPIERCER("voidpiercer", "Voidpiercer", "#ce93d8", "#4a148c", Material.BOW, Particle.PORTAL, Particle.REVERSE_PORTAL, false, 0, 1.0,
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

    TITAN_WRATH("titan_wrath", "Titan's Wrath", "#ff5252", "#b71c1c", Material.NETHERITE_AXE, Particle.SMALL_FLAME, Particle.CRIT, true, 45, 1.3,
            "Berserker's Rage",
            List.of("Gain Strength II, Speed and Resistance",
                    "for 10 seconds, wreathed in flame."),
            List.of("Hits cleave nearby enemies for 50% damage"),
            new Object[]{Enchantment.SHARPNESS, 10, Enchantment.EFFICIENCY, 10, Enchantment.KNOCKBACK, 2,
                    Enchantment.UNBREAKING, 10, Enchantment.MENDING, 1},
            "DND/TAT/DND",
            new Object[]{'D', Material.DIAMOND_BLOCK, 'N', Material.NETHERITE_BLOCK, 'T', Material.TOTEM_OF_UNDYING,
                    'A', Material.NETHERITE_AXE}),

    STORMCALLER("stormcaller", "Stormcaller", "#80d8ff", "#2979ff", Material.TRIDENT, Particle.ELECTRIC_SPARK, Particle.CLOUD, false, 0, 1.0,
            null,
            List.of(),
            List.of("Thrown trident strikes lightning on impact",
                    "Lightning chains to nearby enemies",
                    "20% chance to shock in melee"),
            new Object[]{Enchantment.IMPALING, 10, Enchantment.LOYALTY, 5, Enchantment.CHANNELING, 1,
                    Enchantment.UNBREAKING, 10, Enchantment.MENDING, 1},
            "LNL/HTH/LNL",
            new Object[]{'L', Material.LIGHTNING_ROD, 'N', Material.NETHER_STAR, 'H', Material.HEART_OF_THE_SEA,
                    'T', Material.TRIDENT}),

    HELLFIRE_CROSSBOW("hellfire_crossbow", "Hellfire Crossbow", "#ffab40", "#dd2c00", Material.CROSSBOW, Particle.SMALL_FLAME, Particle.LAVA, false, 0, 1.0,
            null,
            List.of(),
            List.of("Bolts explode on impact",
                    "The blast ignites everything it hits"),
            new Object[]{Enchantment.QUICK_CHARGE, 5, Enchantment.UNBREAKING, 10, Enchantment.MENDING, 1},
            "BNB/MCM/BNB",
            new Object[]{'B', Material.BLAZE_ROD, 'N', Material.NETHER_STAR, 'M', Material.MAGMA_BLOCK,
                    'C', Material.CROSSBOW}),

    FROSTBRAND("frostbrand", "Frostbrand", "#e0f7fa", "#29b6f6", Material.DIAMOND_SWORD, Particle.SNOWFLAKE, Particle.CLOUD, true, 18, 1.2,
            "Frost Nova",
            List.of("Freeze everything around you,",
                    "slowing and chilling enemies."),
            List.of("Hits slow and chill the target"),
            new Object[]{Enchantment.SHARPNESS, 9, Enchantment.SWEEPING_EDGE, 4, Enchantment.LOOTING, 4,
                    Enchantment.UNBREAKING, 10, Enchantment.MENDING, 1},
            "BNB/TDT/BNB",
            new Object[]{'B', Material.BLUE_ICE, 'N', Material.NETHER_STAR, 'T', Material.TOTEM_OF_UNDYING,
                    'D', Material.DIAMOND_SWORD}),

    SHADOWFANG("shadowfang", "Shadowfang", "#b39ddb", "#4527a0", Material.NETHERITE_SWORD, Particle.SMOKE, Particle.PORTAL, true, 10, 1.3,
            "Shadow Step",
            List.of("Blink forward through the shadows and",
                    "vanish briefly. Your next strike is empowered."),
            List.of("Attacks from behind deal 50% more damage"),
            new Object[]{Enchantment.SHARPNESS, 8, Enchantment.SWEEPING_EDGE, 3, Enchantment.LOOTING, 5,
                    Enchantment.UNBREAKING, 10, Enchantment.MENDING, 1},
            "CNC/ESE/CWC",
            new Object[]{'C', Material.CRYING_OBSIDIAN, 'N', Material.NETHER_STAR, 'E', Material.ENDER_EYE,
                    'S', Material.NETHERITE_SWORD, 'W', Material.WITHER_SKELETON_SKULL}),

    EVENT_HORIZON("event_horizon", "Event Horizon", "#b388ff", "#263238", Material.NETHERITE_SHOVEL, Particle.REVERSE_PORTAL, Particle.SMOKE, true, 22, 1.5,
            "Singularity",
            List.of("Rip every nearby enemy toward you,",
                    "damaging and slowing them."),
            List.of("Hits drag enemies toward you"),
            new Object[]{Enchantment.EFFICIENCY, 10, Enchantment.SHARPNESS, 7,
                    Enchantment.UNBREAKING, 10, Enchantment.MENDING, 1},
            "ONO/ASA/OCO",
            new Object[]{'O', Material.OBSIDIAN, 'N', Material.NETHER_STAR, 'A', Material.AMETHYST_BLOCK,
                    'S', Material.NETHERITE_SHOVEL, 'C', Material.ECHO_SHARD}),

    VIPER_FANG("viper_fang", "Viper's Fang", "#b2ff59", "#1b5e20", Material.GOLDEN_SWORD, Particle.ITEM_SLIME, Particle.WARPED_SPORE, true, 20, 1.4,
            "Toxic Burst",
            List.of("Release a lingering cloud of venom",
                    "that poisons everything inside it."),
            List.of("Hits inflict Poison",
                    "Immune to Poison while held"),
            new Object[]{Enchantment.SHARPNESS, 8, Enchantment.BANE_OF_ARTHROPODS, 10, Enchantment.LOOTING, 4,
                    Enchantment.UNBREAKING, 10, Enchantment.MENDING, 1},
            "SNS/PGP/STS",
            new Object[]{'S', Material.SPIDER_EYE, 'N', Material.NETHER_STAR, 'P', Material.POISONOUS_POTATO,
                    'G', Material.GOLDEN_SWORD, 'T', Material.TOTEM_OF_UNDYING}),

    STARGAZER("stargazer", "Stargazer", "#ffffff", "#82b1ff", Material.BOW, Particle.GLOW, Particle.END_ROD, false, 0, 1.0,
            null,
            List.of(),
            List.of("Arrows fly fast and home in on enemies",
                    "Arrows deal 25% more damage"),
            new Object[]{Enchantment.POWER, 8, Enchantment.PUNCH, 2, Enchantment.FLAME, 1, Enchantment.INFINITY, 1,
                    Enchantment.UNBREAKING, 10, Enchantment.MENDING, 1},
            "SNS/EBE/SNS",
            new Object[]{'S', Material.AMETHYST_SHARD, 'N', Material.NETHER_STAR, 'E', Material.SPYGLASS,
                    'B', Material.BOW}),

    EXECUTIONER("executioner", "Executioner", "#ff8a80", "#7f0000", Material.DIAMOND_AXE, Particle.DAMAGE_INDICATOR, Particle.CRIT, true, 30, 1.2,
            "Headsman's Leap",
            List.of("Leap forward with Resistance;",
                    "landing slams the ground."),
            List.of("Targets below 25% health take double damage"),
            new Object[]{Enchantment.SHARPNESS, 9, Enchantment.EFFICIENCY, 8,
                    Enchantment.UNBREAKING, 10, Enchantment.MENDING, 1},
            "IWI/TAT/INI",
            new Object[]{'I', Material.IRON_BLOCK, 'W', Material.WITHER_SKELETON_SKULL, 'T', Material.TOTEM_OF_UNDYING,
                    'A', Material.DIAMOND_AXE, 'N', Material.NETHER_STAR}),

    INFERNAL_SCEPTER("infernal_scepter", "Infernal Scepter", "#ff9100", "#ff1744", Material.BLAZE_ROD, Particle.LAVA, Particle.FLAME, true, 8, 3.0,
            "Dragonfire",
            List.of("Hurl a fireball that bursts into",
                    "flames on impact. Never harms blocks."),
            List.of("Immune to fire while held",
                    "Hits set targets ablaze"),
            new Object[]{Enchantment.UNBREAKING, 10, Enchantment.MENDING, 1},
            "MNM/GRG/MDM",
            new Object[]{'M', Material.MAGMA_BLOCK, 'N', Material.NETHER_STAR, 'G', Material.GHAST_TEAR,
                    'R', Material.BLAZE_ROD, 'D', Material.DRAGON_BREATH}),

    THUNDER_ROD("thunder_rod", "Thunder Rod", "#fffde7", "#ffea00", Material.LIGHTNING_ROD, Particle.ELECTRIC_SPARK, Particle.END_ROD, true, 15, 3.0,
            "Call Thunder",
            List.of("Mark the block you are looking at (50 blocks).",
                    "A bolt falls from the sky a moment later,",
                    "so anyone quick can still dodge it."),
            List.of(),
            new Object[]{Enchantment.UNBREAKING, 10, Enchantment.MENDING, 1},
            "CNC/TLT/CNC",
            new Object[]{'C', Material.COPPER_BLOCK, 'N', Material.NETHER_STAR, 'T', Material.TOTEM_OF_UNDYING,
                    'L', Material.LIGHTNING_ROD}),

    GOD_ELYTRA("god_elytra", "God Elytra", "#ffe082", "#40c4ff", Material.ELYTRA, Particle.END_ROD, Particle.CLOUD, false, 4, 1.0,
            "Sky Thrust",
            List.of("Press the swap-hand key (F) while gliding",
                    "to blast forward. Hold sneak to air-brake."),
            List.of("Dives accelerate hard; level flight holds speed",
                    "Altitude lost while gliding counts as fall distance",
                    "Mace hits while gliding become Sky Smashes:",
                    "huge bonus damage, a shockwave and a rebound",
                    "No fall or flight-impact damage"),
            new Object[]{Enchantment.UNBREAKING, 10, Enchantment.MENDING, 1},
            "NFN/PEP/NTN",
            new Object[]{'N', Material.NETHER_STAR, 'F', Material.FIREWORK_ROCKET, 'P', Material.PHANTOM_MEMBRANE,
                    'E', Material.ELYTRA, 'T', Material.TOTEM_OF_UNDYING});

    public final String id;
    public final String display;
    /** Gradient start / end as hex, e.g. "#ffd54a". */
    public final String colorA;
    public final String colorB;
    /** MiniMessage colour tag for plain coloured text, e.g. "<#ffd54a>". */
    public final String color;
    public final Color rgbA;
    public final Color rgbB;
    public final Material material;
    /** Ambient particle and secondary particle that make up the weapon's visual signature. */
    public final Particle aura;
    public final Particle accent;
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

    LegendaryWeapon(String id, String display, String colorA, String colorB, Material material, Particle aura,
                    Particle accent, boolean active, int cooldown, double damageMultiplier, String power,
                    List<String> powerLines, List<String> passiveLines, Object[] enchantPairs, String shape,
                    Object[] ingredientPairs) {
        this.id = id;
        this.display = display;
        this.colorA = colorA;
        this.colorB = colorB;
        this.color = "<" + colorA + ">";
        this.rgbA = Color.fromRGB(Integer.parseInt(colorA.substring(1), 16));
        this.rgbB = Color.fromRGB(Integer.parseInt(colorB.substring(1), 16));
        this.material = material;
        this.aura = aura;
        this.accent = accent;
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

    /** MiniMessage for text shaded from this weapon's first colour to its second. */
    public String gradient(String text) {
        return "<gradient:" + colorA + ":" + colorB + ">" + text + "</gradient>";
    }

    /** How this item's power is triggered, for the lore. */
    public String activation() {
        return this == GOD_ELYTRA ? "Swap-hand key while gliding" : "Right-click";
    }

    /** The weapon's display name, bold, in its gradient. */
    public String title() { return gradient("<bold>" + display + "</bold>"); }

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
