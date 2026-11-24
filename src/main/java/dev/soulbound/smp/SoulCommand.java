package dev.soulbound.smp;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class SoulCommand implements TabExecutor {

    private static final List<String> PLAYER_SUBS = List.of("status", "event", "time");
    private static final List<String> ADMIN_SUBS = List.of("admin", "revive", "eliminate", "reset", "givebeacon", "giveseal", "legendary", "ritual", "reload");

    private final SoulboundPlugin plugin;

    public SoulCommand(SoulboundPlugin plugin) { this.plugin = plugin; }

    private boolean admin(CommandSender s) { return s.hasPermission("soulbound.admin"); }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        Msg msg = plugin.msg();
        if (args.length == 0) {
            help(sender);
            return true;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        if (!PLAYER_SUBS.contains(sub) && !admin(sender)) {
            msg.send(sender, "<red>You don't have permission to do that.");
            return true;
        }
        switch (sub) {
            case "status" -> status(sender, args);
            case "event" -> event(sender, args);
            case "time" -> time(sender, args);
            case "admin" -> {
                if (sender instanceof Player p) plugin.adminGui().openMain(p);
                else msg.send(sender, "<red>The admin menu is for players only.");
            }
            case "revive" -> adminRevive(sender, args);
            case "eliminate" -> adminEliminate(sender, args);
            case "reset" -> adminReset(sender, args);
            case "givebeacon" -> giveBeacon(sender, args);
            case "giveseal" -> giveSeal(sender, args);
            case "legendary" -> legendary(sender, args);
            case "ritual" -> ritual(sender, args);
            case "reload" -> {
                plugin.reload();
                msg.send(sender, "<green>Config reloaded.");
            }
            default -> help(sender);
        }
        return true;
    }

    private void help(CommandSender s) {
        plugin.msg().send(s, "<white>/sb status [player] <gray>- soul status");
        plugin.msg().send(s, "<white>/sb event <gray>- current event");
        plugin.msg().send(s, "<white>/sb time <gray>- event schedule" + (admin(s) ? " | <white>/sb time interval|duration <value>" : ""));
        if (admin(s)) {
            plugin.msg().send(s, "<white>/sbadmin <gray>- admin menu (events + revive)");
            plugin.msg().send(s, "<white>/sbroadcast [-t] [-q] <message>");
            plugin.msg().send(s, "<white>/sb event <roll|set <id> [time]|next <id|clear>|stop|list>");
            plugin.msg().send(s, "<white>/sb revive|eliminate|reset <player>");
            plugin.msg().send(s, "<white>/sb givebeacon [player] [amount]");
            plugin.msg().send(s, "<white>/sb giveseal [player] [amount] <gray>- Heavenly Seal");
            plugin.msg().send(s, "<white>/sb legendary <list|give <id|all> [player]|reset <id>> <gray>- legendary weapons");
            plugin.msg().send(s, "<white>/sb ritual <info|cancel>");
            plugin.msg().send(s, "<white>/sb reload");
        }
    }

    // ------------------------------------------------------------------ status

    private void status(CommandSender sender, String[] args) {
        Msg msg = plugin.msg();
        PlayerRecord r;
        if (args.length > 1) {
            if (!admin(sender)) {
                msg.send(sender, "<red>You can only view your own status.");
                return;
            }
            r = plugin.data().findByName(args[1]);
            if (r == null) {
                msg.send(sender, "<red>No record for that player.");
                return;
            }
        } else if (sender instanceof Player p) {
            r = plugin.data().record(p);
        } else {
            msg.send(sender, "<red>Specify a player.");
            return;
        }
        String state = r.permanent ? "<dark_red>Permanently eliminated"
                : r.pendingRevive ? "<green>Revived (returns on next login)"
                : r.eliminated ? "<red>Eliminated" : "<green>Alive";
        msg.send(sender, "<white>" + Msg.esc(r.name) + "<gray>: " + state + "<gray> | Revive: "
                + (r.reviveUsed ? "<red>used" : "<green>available"));
    }

    // ------------------------------------------------------------------ events

    private void event(CommandSender sender, String[] args) {
        Msg msg = plugin.msg();
        EventManager em = plugin.events();
        if (args.length == 1) {
            SoulEvent cur = em.current();
            if (cur == null) msg.send(sender, "<gray>No event is active right now.");
            else msg.send(sender, cur.color + "<bold>" + cur.display + "</bold><gray> - " + em.describe(cur)
                    + " <dark_gray>| <white>" + Msg.clock(em.remainingSeconds()) + "<gray> left");
            return;
        }
        if (!admin(sender)) {
            msg.send(sender, "<red>You don't have permission to do that.");
            return;
        }
        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "roll" -> em.rollWithCountdown();
            case "next" -> {
                if (args.length < 3) {
                    SoulEvent q = em.queued();
                    msg.send(sender, q == null
                            ? "<gray>No event queued. Usage: /sb event next <id|clear>"
                            : "<gray>Queued as the next scheduled event: " + q.color + q.display);
                    return;
                }
                if (args[2].equalsIgnoreCase("clear")) {
                    em.setQueued(null);
                    msg.send(sender, "<gray>Queue cleared.");
                    return;
                }
                SoulEvent q = SoulEvent.byId(args[2]);
                if (q == null) {
                    msg.send(sender, "<red>Unknown event. Use /sb event list.");
                    return;
                }
                em.setQueued(q);
                msg.send(sender, "<green>The next scheduled event will be " + q.color + q.display + "<green>. <gray>(next event in "
                        + Msg.clock(em.nextRollSeconds()) + ")");
            }
            case "stop" -> {
                em.stop();
                msg.send(sender, "<gray>Event stopped. The next one starts after a full interval.");
            }
            case "list" -> {
                StringBuilder sb = new StringBuilder();
                for (SoulEvent e : SoulEvent.values()) sb.append(e.id).append(", ");
                msg.send(sender, "<white>" + sb.substring(0, sb.length() - 2));
            }
            case "set" -> {
                if (args.length < 3) {
                    msg.send(sender, "<red>Usage: /sb event set <id> [hours]");
                    return;
                }
                SoulEvent e = SoulEvent.byId(args[2]);
                if (e == null) {
                    msg.send(sender, "<red>Unknown event. Use /sb event list.");
                    return;
                }
                if (args.length > 3) {
                    Double hours = Msg.parseHours(args[3]);
                    if (hours == null || hours <= 0) {
                        msg.send(sender, "<red>Invalid time. Examples: 30m, 6h, 1d");
                        return;
                    }
                    em.setEvent(e, (long) (hours * 3_600_000L));
                } else {
                    em.setEvent(e);
                }
            }
            default -> msg.send(sender, "<red>Usage: /sb event <roll|set|stop|list>");
        }
    }

    private void time(CommandSender sender, String[] args) {
        Msg msg = plugin.msg();
        EventManager em = plugin.events();
        if (args.length == 1) {
            msg.send(sender, em.timingSummary());
            return;
        }
        if (!admin(sender)) {
            msg.send(sender, "<red>You don't have permission to do that.");
            return;
        }
        String which = args[1].toLowerCase(Locale.ROOT);
        if (args.length < 3 || !(which.equals("interval") || which.equals("duration"))) {
            msg.send(sender, "<red>Usage: /sb time <interval|duration> <value>  <gray>(e.g. 30m, 6h, 1d; duration 0 = until next event)");
            return;
        }
        Double hours = Msg.parseHours(args[2]);
        if (hours == null || (which.equals("interval") && hours <= 0)) {
            msg.send(sender, "<red>Invalid time. Examples: 30m, 6h, 12h, 1d");
            return;
        }
        if (which.equals("interval")) em.setIntervalHours(hours);
        else em.setDurationHours(hours);
        msg.send(sender, "<green>Saved. " + em.timingSummary());
        msg.send(sender, "<dark_gray>Duration changes apply from the next event; use /sb event set <id> <time> to change the current one.");
    }

    // ------------------------------------------------------------------ admin tools

    private void adminRevive(CommandSender sender, String[] args) {
        Msg msg = plugin.msg();
        PlayerRecord r = args.length > 1 ? plugin.data().findByName(args[1]) : null;
        if (r == null) {
            msg.send(sender, "<red>Usage: /sb revive <player> (player must have a record)");
            return;
        }
        plugin.rituals().forceRevive(r);
        msg.send(sender, "<green>Revived " + Msg.esc(r.name) + " (their revive flag was not changed).");
    }

    private void adminEliminate(CommandSender sender, String[] args) {
        Msg msg = plugin.msg();
        Player p = args.length > 1 ? Bukkit.getPlayerExact(args[1]) : null;
        if (p == null) {
            msg.send(sender, "<red>Usage: /sb eliminate <online player>");
            return;
        }
        PlayerRecord r = plugin.data().record(p);
        r.eliminated = true;
        r.deathLocation = p.getLocation().clone();
        plugin.data().save();
        p.setGameMode(GameMode.SPECTATOR);
        msg.send(sender, "<gray>" + Msg.esc(p.getName()) + " marked as eliminated.");
    }

    private void adminReset(CommandSender sender, String[] args) {
        Msg msg = plugin.msg();
        PlayerRecord r = args.length > 1 ? plugin.data().findByName(args[1]) : null;
        if (r == null) {
            msg.send(sender, "<red>Usage: /sb reset <player>");
            return;
        }
        boolean wasDead = r.eliminated || r.permanent;
        if (r.permanent) plugin.pardonPlayer(r.name);
        r.eliminated = false;
        r.permanent = false;
        r.reviveUsed = false;
        r.pendingRevive = false;
        r.pendingLocation = null;
        Player p = Bukkit.getPlayer(r.uuid);
        if (p != null) {
            p.setGameMode(GameMode.SURVIVAL);
            plugin.events().syncPlayer(p);
        } else if (wasDead) {
            // offline: make sure they come back in survival (not stuck in spectator) on next login
            r.pendingRevive = true;
            r.pendingLocation = r.deathLocation != null ? r.deathLocation : Bukkit.getWorlds().get(0).getSpawnLocation();
        }
        plugin.data().save();
        msg.send(sender, "<green>Reset " + Msg.esc(r.name) + ": alive, revive available.");
    }

    private void giveBeacon(CommandSender sender, String[] args) {
        Msg msg = plugin.msg();
        final Player target = args.length > 1 ? Bukkit.getPlayerExact(args[1]) : (sender instanceof Player p ? p : null);
        if (target == null) {
            msg.send(sender, "<red>Player not found.");
            return;
        }
        int amount = 1;
        if (args.length > 2) {
            try { amount = Math.max(1, Math.min(64, Integer.parseInt(args[2]))); } catch (NumberFormatException ignored) { }
        }
        for (ItemStack left : target.getInventory().addItem(plugin.beacon().create(amount)).values()) {
            target.getWorld().dropItemNaturally(target.getLocation(), left);
        }
        msg.send(sender, "<green>Gave " + amount + " Revive Beacon(s) to " + Msg.esc(target.getName()) + ".");
    }

    private void giveSeal(CommandSender sender, String[] args) {
        Msg msg = plugin.msg();
        final Player target = args.length > 1 ? Bukkit.getPlayerExact(args[1]) : (sender instanceof Player p ? p : null);
        if (target == null) {
            msg.send(sender, "<red>Player not found.");
            return;
        }
        int amount = 1;
        if (args.length > 2) {
            try { amount = Math.max(1, Math.min(64, Integer.parseInt(args[2]))); } catch (NumberFormatException ignored) { }
        }
        for (ItemStack left : target.getInventory().addItem(plugin.seal().create(amount)).values()) {
            target.getWorld().dropItemNaturally(target.getLocation(), left);
        }
        msg.send(sender, "<green>Gave " + amount + " Heavenly Seal(s) to " + Msg.esc(target.getName()) + ".");
    }

    private void legendary(CommandSender sender, String[] args) {
        Msg msg = plugin.msg();
        LegendaryManager lm = plugin.legendary();
        String sub = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "list";
        switch (sub) {
            case "list" -> {
                for (LegendaryWeapon w : LegendaryWeapon.values()) {
                    int max = lm.maxCrafted(w);
                    msg.send(sender, w.title() + "<gray> - forged <white>" + lm.crafted(w)
                            + "<gray>/<white>" + (max == 0 ? "unlimited" : String.valueOf(max)) + " <dark_gray>(" + w.id + ")");
                }
            }
            case "give" -> {
                boolean all = args.length > 2 && args[2].equalsIgnoreCase("all");
                LegendaryWeapon w = args.length > 2 ? LegendaryWeapon.byId(args[2]) : null;
                if (w == null && !all) {
                    msg.send(sender, "<red>Usage: /sb legendary give <id|all> [player]. <gray>Ids: /sb legendary list");
                    return;
                }
                Player target = args.length > 3 ? Bukkit.getPlayerExact(args[3]) : (sender instanceof Player p ? p : null);
                if (target == null) {
                    msg.send(sender, "<red>Player not found.");
                    return;
                }
                List<LegendaryWeapon> toGive = all ? List.of(LegendaryWeapon.values()) : List.of(w);
                for (LegendaryWeapon g : toGive) {
                    for (ItemStack left : target.getInventory().addItem(lm.create(g)).values()) {
                        target.getWorld().dropItemNaturally(target.getLocation(), left);
                    }
                }
                msg.send(sender, "<green>Gave " + (all ? "all legendary weapons" : w.display) + " to " + Msg.esc(target.getName())
                        + ". <gray>(Admin gifts do not count towards the forge limit.)");
            }
            case "reset" -> {
                LegendaryWeapon w = args.length > 2 ? LegendaryWeapon.byId(args[2]) : null;
                if (w == null) {
                    msg.send(sender, "<red>Usage: /sb legendary reset <id>");
                    return;
                }
                lm.resetCrafted(w);
                msg.send(sender, "<green>" + w.display + " forge count reset: it can be crafted again.");
            }
            default -> msg.send(sender, "<red>Usage: /sb legendary <list|give|reset>");
        }
    }

    private void ritual(CommandSender sender, String[] args) {
        Msg msg = plugin.msg();
        RitualManager rm = plugin.rituals();
        String sub = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "info";
        if (sub.equals("cancel")) {
            msg.send(sender, rm.cancel() ? "<gray>Ritual cancelled (no penalty)." : "<red>No ritual is active.");
            return;
        }
        RitualManager.Ritual r = rm.active();
        if (r == null) msg.send(sender, "<gray>No ritual is active.");
        else msg.send(sender, "<dark_purple>Reviving <white>" + Msg.esc(r.targetName()) + "<gray> at <yellow>"
                + RitualManager.coords(r.core()) + "<gray> - " + Msg.clock(r.remaining()) + " left.");
    }

    // ------------------------------------------------------------------ tab complete

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            out.addAll(PLAYER_SUBS);
            if (admin(sender)) out.addAll(ADMIN_SUBS);
        } else if (args.length == 2) {
            switch (args[0].toLowerCase(Locale.ROOT)) {
                case "status", "revive", "eliminate", "reset", "givebeacon", "giveseal" ->
                        Bukkit.getOnlinePlayers().forEach(p -> out.add(p.getName()));
                case "event" -> { if (admin(sender)) out.addAll(List.of("roll", "set", "next", "stop", "list")); }
                case "time" -> { if (admin(sender)) out.addAll(List.of("interval", "duration")); }
                case "ritual" -> out.addAll(List.of("info", "cancel"));
                case "legendary" -> out.addAll(List.of("list", "give", "reset"));
                default -> { }
            }
        } else if (args.length == 3 && args[0].equalsIgnoreCase("time") && admin(sender)) {
            out.addAll(List.of("0", "30m", "1h", "6h", "12h", "1d"));
        } else if (args.length == 3 && args[0].equalsIgnoreCase("event")
                && (args[1].equalsIgnoreCase("set") || args[1].equalsIgnoreCase("next"))) {
            for (SoulEvent e : SoulEvent.values()) out.add(e.id);
            if (args[1].equalsIgnoreCase("next")) out.add("clear");
        }
        else if (args.length == 3 && args[0].equalsIgnoreCase("legendary")
                && (args[1].equalsIgnoreCase("give") || args[1].equalsIgnoreCase("reset"))) {
            for (LegendaryWeapon w : LegendaryWeapon.values()) out.add(w.id);
            if (args[1].equalsIgnoreCase("give")) out.add("all");
        } else if (args.length == 4 && args[0].equalsIgnoreCase("legendary") && args[1].equalsIgnoreCase("give")) {
            Bukkit.getOnlinePlayers().forEach(p -> out.add(p.getName()));
        }
        String last = args[args.length - 1].toLowerCase(Locale.ROOT);
        out.removeIf(s -> !s.toLowerCase(Locale.ROOT).startsWith(last));
        return out;
    }
}
