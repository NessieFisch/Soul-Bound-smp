package dev.soulbound.smp;

import net.kyori.adventure.bossbar.BossBar;

public enum SoulEvent {
    // ---- curses
    BLOOD_MOON("blood_moon", "Blood Moon", "Mobs deal 2x damage", "<red>", BossBar.Color.RED, Category.BAD),
    EMPTY_SOULS("empty_souls", "Empty Souls", "Natural regeneration is disabled", "<gray>", BossBar.Color.WHITE, Category.BAD),
    SOUL_DRAIN("soul_drain", "Soul Drain", "Every player loses 1 heart", "<dark_purple>", BossBar.Color.PURPLE, Category.BAD),
    ETERNAL_NIGHT("eternal_night", "Eternal Night", "The world stays dark", "<blue>", BossBar.Color.BLUE, Category.BAD),
    FRAGILE_WORLD("fragile_world", "Fragile World", "Blocks randomly become infested", "<green>", BossBar.Color.GREEN, Category.BAD),
    HUNGER_GAMES("hunger_games", "Hunger Games", "Hunger drains 3x faster", "<gold>", BossBar.Color.YELLOW, Category.BAD),
    NO_ESCAPE("no_escape", "No Escape", "Ender pearls are disabled", "<aqua>", BossBar.Color.PINK, Category.BAD),
    BEAST_AWAKENING("beast_awakening", "Beast Awakening", "Stronger mobs spawn everywhere", "<dark_red>", BossBar.Color.RED, Category.BAD),
    BROKEN_BONDS("broken_bonds", "Broken Bonds", "Players cannot use shields", "<yellow>", BossBar.Color.YELLOW, Category.BAD),
    THE_SILENCE("the_silence", "The Silence", "Players cannot see chat messages", "<dark_gray>", BossBar.Color.WHITE, Category.BAD),

    // ---- blessings
    SOUL_SURGE("soul_surge", "Soul Surge", "Everyone gets Regeneration", "<green>", BossBar.Color.GREEN, Category.GOOD),
    BOUNTIFUL_DROPS("bountiful_drops", "Bountiful Drops", "Mobs drop 2x loot", "<gold>", BossBar.Color.YELLOW, Category.GOOD),
    WISDOM_OF_THE_DEAD("wisdom_of_the_dead", "Wisdom of the Dead", "Experience is doubled", "<aqua>", BossBar.Color.BLUE, Category.GOOD),
    SWIFT_WINDS("swift_winds", "Swift Winds", "Everyone moves faster", "<white>", BossBar.Color.WHITE, Category.GOOD),
    ANCIENT_HASTE("ancient_haste", "Ancient Haste", "Mining and attacking are faster", "<yellow>", BossBar.Color.YELLOW, Category.GOOD),
    IRON_SKIN("iron_skin", "Iron Skin", "Everyone takes less damage", "<gray>", BossBar.Color.WHITE, Category.GOOD),

    // ---- the worst one
    SUPER_HELL("super_hell", "Super Hell", "Every curse at once, and worse", "<dark_red>", BossBar.Color.RED, Category.HELL);

    public enum Category { GOOD, BAD, HELL }

    public final String id;
    public final String display;
    public final String defaultDescription;
    public final String color;
    public final BossBar.Color barColor;
    public final Category category;

    SoulEvent(String id, String display, String defaultDescription, String color, BossBar.Color barColor, Category category) {
        this.id = id;
        this.display = display;
        this.defaultDescription = defaultDescription;
        this.color = color;
        this.barColor = barColor;
        this.category = category;
    }

    public static SoulEvent byId(String id) {
        if (id == null) return null;
        for (SoulEvent e : values()) if (e.id.equalsIgnoreCase(id)) return e;
        return null;
    }
}
