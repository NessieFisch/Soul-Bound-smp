package dev.soulbound.smp;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.Locale;

public final class Msg {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private final SoulboundPlugin plugin;
    private String prefix = "";

    public Msg(SoulboundPlugin plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        prefix = plugin.getConfig().getString("prefix", "<dark_gray>[<dark_purple>Soulbound<dark_gray>] <gray>");
    }

    public Component parse(String mini) { return MM.deserialize(mini); }

    public Component prefixed(String mini) { return MM.deserialize(prefix + mini); }

    public void send(CommandSender to, String mini) { to.sendMessage(prefixed(mini)); }

    public void broadcast(String mini) { Bukkit.broadcast(prefixed(mini)); }

    public void title(Player p, String title, String subtitle) {
        p.showTitle(Title.title(parse(title), parse(subtitle),
                Title.Times.times(Duration.ofMillis(400), Duration.ofSeconds(4), Duration.ofMillis(800))));
    }

    public void titleAll(String title, String subtitle) {
        for (Player p : Bukkit.getOnlinePlayers()) title(p, title, subtitle);
    }

    public void titleAll(String title, String subtitle, long fadeInMs, long stayMs, long fadeOutMs) {
        Title t = Title.title(parse(title), parse(subtitle),
                Title.Times.times(Duration.ofMillis(fadeInMs), Duration.ofMillis(stayMs), Duration.ofMillis(fadeOutMs)));
        for (Player p : Bukkit.getOnlinePlayers()) p.showTitle(t);
    }

    public void actionBar(Player p, String mini) { p.sendActionBar(parse(mini)); }

    public static String esc(String s) { return MM.escapeTags(s); }

    public static String clock(long totalSeconds) {
        long h = totalSeconds / 3600, m = (totalSeconds % 3600) / 60, s = totalSeconds % 60;
        return h > 0 ? String.format("%d:%02d:%02d", h, m, s) : String.format("%d:%02d", m, s);
    }

    /** Parses "30m", "6h", "1d" or a plain number of hours. Returns null if invalid. */
    public static Double parseHours(String s) {
        if (s == null || s.isBlank()) return null;
        s = s.trim().toLowerCase(Locale.ROOT);
        double mult = 1.0;
        if (s.endsWith("m")) { mult = 1.0 / 60.0; s = s.substring(0, s.length() - 1); }
        else if (s.endsWith("h")) { s = s.substring(0, s.length() - 1); }
        else if (s.endsWith("d")) { mult = 24.0; s = s.substring(0, s.length() - 1); }
        try {
            double v = Double.parseDouble(s) * mult;
            return v < 0 ? null : v;
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    /** 0.5 -> "30 min", 12 -> "12h", 1.5 -> "1.5h" */
    public static String hours(double h) {
        if (h < 1.0) return Math.round(h * 60) + " min";
        return num(Math.round(h * 100) / 100.0) + "h";
    }

    public static String num(double d) {
        return d == Math.rint(d) ? String.valueOf((long) d) : String.valueOf(d);
    }
}
