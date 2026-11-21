package dev.soulbound.smp;

import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.util.Arrays;
import java.util.List;

/** /sbroadcast [-t] [-q] <message> - announce to every player. MiniMessage tags work (e.g. <red>text</red>). */
public final class BroadcastCommand implements TabExecutor {

    private final SoulboundPlugin plugin;

    public BroadcastCommand(SoulboundPlugin plugin) { this.plugin = plugin; }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        Msg msg = plugin.msg();
        if (!sender.hasPermission("soulbound.admin")) {
            msg.send(sender, "<red>You don't have permission to do that.");
            return true;
        }
        boolean title = false, quiet = false;
        int start = 0;
        while (start < args.length && (args[start].equalsIgnoreCase("-t") || args[start].equalsIgnoreCase("-q"))) {
            if (args[start].equalsIgnoreCase("-t")) title = true;
            else quiet = true;
            start++;
        }
        if (start >= args.length) {
            msg.send(sender, "<red>Usage: /" + label + " [-t] [-q] <message>");
            msg.send(sender, "<gray>-t also shows a big title, -q = no sound. Colors: <red>&lt;red&gt;text<gray>, <bold>&lt;bold&gt;<gray>.");
            return true;
        }
        String text = String.join(" ", Arrays.copyOfRange(args, start, args.length));

        Bukkit.broadcast(msg.parse("<dark_purple><bold>ANNOUNCEMENT</bold> <dark_gray>» <white>" + text));
        if (title) msg.titleAll("<dark_purple><bold>ANNOUNCEMENT</bold>", "<white>" + text);
        if (!quiet) {
            for (Player p : Bukkit.getOnlinePlayers()) p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BELL, 1f, 1f);
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (sender.hasPermission("soulbound.admin") && args.length == 1) return List.of("-t", "-q");
        return List.of();
    }
}
