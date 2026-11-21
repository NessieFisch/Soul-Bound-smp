package dev.soulbound.smp;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.time.Duration;

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

    public void actionBar(Player p, String mini) { p.sendActionBar(parse(mini)); }

    public static String esc(String s) { return MM.escapeTags(s); }

    public static String clock(long totalSeconds) {
        long h = totalSeconds / 3600, m = (totalSeconds % 3600) / 60, s = totalSeconds % 60;
        return h > 0 ? String.format("%d:%02d:%02d", h, m, s) : String.format("%d:%02d", m, s);
    }

    public static String num(double d) {
        return d == Math.rint(d) ? String.valueOf((long) d) : String.valueOf(d);
    }
}
