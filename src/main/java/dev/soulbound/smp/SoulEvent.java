package dev.soulbound.smp;

import net.kyori.adventure.bossbar.BossBar;

public enum SoulEvent {
    BLOOD_MOON("blood_moon", "Blood Moon", "Mobs deal 2x damage", "<red>", BossBar.Color.RED),
    EMPTY_SOULS("empty_souls", "Empty Souls", "Natural regeneration is disabled", "<gray>", BossBar.Color.WHITE),
    SOUL_DRAIN("soul_drain", "Soul Drain", "Every player loses 1 heart", "<dark_purple>", BossBar.Color.PURPLE),
    ETERNAL_NIGHT("eternal_night", "Eternal Night", "The world stays dark", "<blue>", BossBar.Color.BLUE),
    FRAGILE_WORLD("fragile_world", "Fragile World", "Blocks randomly become infested", "<green>", BossBar.Color.GREEN),
    HUNGER_GAMES("hunger_games", "Hunger Games", "Hunger drains 3x faster", "<gold>", BossBar.Color.YELLOW),
    NO_ESCAPE("no_escape", "No Escape", "Ender pearls are disabled", "<aqua>", BossBar.Color.PINK),
    BEAST_AWAKENING("beast_awakening", "Beast Awakening", "Stronger mobs spawn everywhere", "<dark_red>", BossBar.Color.RED),
    BROKEN_BONDS("broken_bonds", "Broken Bonds", "Players cannot use shields", "<yellow>", BossBar.Color.YELLOW),
    THE_SILENCE("the_silence", "The Silence", "Players cannot see chat messages", "<dark_gray>", BossBar.Color.WHITE);

    public final String id;
    public final String display;
    public final String defaultDescription;
    public final String color;
    public final BossBar.Color barColor;

    SoulEvent(String id, String display, String defaultDescription, String color, BossBar.Color barColor) {
        this.id = id;
        this.display = display;
        this.defaultDescription = defaultDescription;
        this.color = color;
        this.barColor = barColor;
    }

    public static SoulEvent byId(String id) {
        if (id == null) return null;
        for (SoulEvent e : values()) if (e.id.equalsIgnoreCase(id)) return e;
        return null;
    }
}
