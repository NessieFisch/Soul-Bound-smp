package dev.soulbound.smp;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/** /sbadmin - click menu for ops: set the event, revive players. */
public final class AdminGui implements Listener {

    private enum Type { MAIN, EVENTS, REVIVE, TIMING }

    private static final class Holder implements InventoryHolder {
        final Type type;
        Inventory inv;

        Holder(Type type) { this.type = type; }

        @Override
        public Inventory getInventory() { return inv; }
    }

    private static final int[] EVENT_SLOTS = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25, 28, 29};
    private static final int HELL_SLOT = 31;

    private final SoulboundPlugin plugin;
    private final NamespacedKey actionKey;

    public AdminGui(SoulboundPlugin plugin) {
        this.plugin = plugin;
        this.actionKey = new NamespacedKey(plugin, "admin_action");
    }

    private ItemStack item(Material m, String name, boolean glow, String action, String... lore) {
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(plugin.msg().parse(name).decoration(TextDecoration.ITALIC, false));
        if (lore.length > 0) {
            List<Component> l = new ArrayList<>();
            for (String s : lore) l.add(plugin.msg().parse(s).decoration(TextDecoration.ITALIC, false));
            meta.lore(l);
        }
        if (glow) meta.setEnchantmentGlintOverride(true);
        if (action != null) meta.getPersistentDataContainer().set(actionKey, PersistentDataType.STRING, action);
        it.setItemMeta(meta);
        return it;
    }

    private static Material iconFor(SoulEvent e) {
        return switch (e) {
            case BLOOD_MOON -> Material.REDSTONE;
            case EMPTY_SOULS -> Material.GLISTERING_MELON_SLICE;
            case SOUL_DRAIN -> Material.WITHER_ROSE;
            case ETERNAL_NIGHT -> Material.BLACK_CONCRETE;
            case FRAGILE_WORLD -> Material.INFESTED_STONE;
            case HUNGER_GAMES -> Material.ROTTEN_FLESH;
            case NO_ESCAPE -> Material.ENDER_PEARL;
            case BEAST_AWAKENING -> Material.ZOMBIE_HEAD;
            case BROKEN_BONDS -> Material.SHIELD;
            case THE_SILENCE -> Material.SCULK_SENSOR;
            case SOUL_SURGE -> Material.GHAST_TEAR;
            case BOUNTIFUL_DROPS -> Material.CHEST;
            case WISDOM_OF_THE_DEAD -> Material.EXPERIENCE_BOTTLE;
            case SWIFT_WINDS -> Material.FEATHER;
            case ANCIENT_HASTE -> Material.GOLDEN_PICKAXE;
            case IRON_SKIN -> Material.IRON_CHESTPLATE;
            case SUPER_HELL -> Material.WITHER_SKELETON_SKULL;
        };
    }

    // ------------------------------------------------------------------ menus

    public void openMain(Player p) {
        Holder h = new Holder(Type.MAIN);
        Inventory inv = Bukkit.createInventory(h, 27, Component.text("Soulbound Admin", NamedTextColor.DARK_PURPLE));
        h.inv = inv;

        SoulEvent cur = plugin.events().current();
        String curLine = cur == null ? "<gray>Current: <white>none" : "<gray>Current: " + cur.color + cur.display;
        int dead = 0;
        for (PlayerRecord r : plugin.data().all()) if ((r.eliminated || r.permanent) && !r.pendingRevive) dead++;

        inv.setItem(10, item(Material.CLOCK, "<light_purple><bold>Set Event", false, "events", curLine, "<dark_gray>Click to choose an event"));
        inv.setItem(12, item(Material.TOTEM_OF_UNDYING, "<green><bold>Revive a Player", false, "revive",
                "<gray>Eliminated players: <white>" + dead, "<dark_gray>Click to choose a player"));
        inv.setItem(14, item(Material.ENDER_EYE, "<yellow><bold>Roll Random Event", false, "roll", "<dark_gray>Starts a random event now"));
        inv.setItem(16, item(Material.BARRIER, "<red><bold>Stop Current Event", false, "stop", "<dark_gray>Ends the event; next one after a full interval"));
        inv.setItem(22, item(Material.REPEATER, "<aqua><bold>Event Timing", false, "timing",
                "<gray>Every <white>" + Msg.hours(plugin.events().intervalHours()),
                "<dark_gray>Change how often events start and how long they last"));
        p.openInventory(inv);
    }

    public void openTiming(Player p) {
        Holder h = new Holder(Type.TIMING);
        Inventory inv = Bukkit.createInventory(h, 36, Component.text("Event timing", NamedTextColor.DARK_PURPLE));
        h.inv = inv;
        EventManager em = plugin.events();

        inv.setItem(13, item(Material.CLOCK, "<light_purple><bold>Interval", false, null,
                "<gray>A new event starts every <white>" + Msg.hours(em.intervalHours()),
                "<dark_gray>Next event in " + Msg.clock(em.nextRollSeconds())));
        inv.setItem(22, item(Material.REPEATER, "<aqua><bold>Duration", false, null,
                "<gray>Each event lasts <white>" + (em.durationHours() <= 0 ? "until the next event" : Msg.hours(Math.min(em.durationHours(), em.intervalHours()))),
                "<dark_gray>0 = until the next event starts"));

        double[] steps = {-6, -1, 1, 6};
        int[] intervalSlots = {10, 11, 15, 16};
        int[] durationSlots = {19, 20, 24, 25};
        for (int i = 0; i < steps.length; i++) {
            boolean plus = steps[i] > 0;
            String name = (plus ? "<green>" : "<red>") + "<bold>" + (plus ? "+" : "") + Msg.num(steps[i]) + "h";
            Material mat = plus ? Material.LIME_STAINED_GLASS_PANE : Material.RED_STAINED_GLASS_PANE;
            inv.setItem(intervalSlots[i], item(mat, name, false, "time:interval:" + Msg.num(steps[i])));
            inv.setItem(durationSlots[i], item(mat, name, false, "time:duration:" + Msg.num(steps[i])));
        }
        inv.setItem(31, item(Material.ARROW, "<gray>Back", false, "back"));
        p.openInventory(inv);
    }

    public void openEvents(Player p) {
        Holder h = new Holder(Type.EVENTS);
        Inventory inv = Bukkit.createInventory(h, 45, Component.text("Choose an event", NamedTextColor.DARK_PURPLE));
        h.inv = inv;
        SoulEvent cur = plugin.events().current();
        int next = 0;
        for (SoulEvent e : SoulEvent.values()) {
            int slot;
            if (e == SoulEvent.SUPER_HELL) slot = HELL_SLOT;
            else if (next < EVENT_SLOTS.length) slot = EVENT_SLOTS[next++];
            else continue;
            String label = switch (e.category) {
                case GOOD -> "<green>Blessing";
                case BAD -> "<red>Curse";
                case HELL -> "<dark_red><bold>HELL - every curse at once";
            };
            String time = e == SoulEvent.SUPER_HELL
                    ? "<dark_gray>Lasts " + Msg.clock(plugin.events().durationFor(e) / 1000) + " only"
                    : "<dark_gray>Click to start now";
            inv.setItem(slot, item(iconFor(e), e.color + "<bold>" + e.display, e == cur, "event:" + e.id,
                    label,
                    "<gray>" + plugin.events().describe(e),
                    e == cur ? "<green>Currently active" : time));
        }
        inv.setItem(40, item(Material.ARROW, "<gray>Back", false, "back"));
        p.openInventory(inv);
    }

    public void openRevive(Player p) {
        List<PlayerRecord> list = new ArrayList<>();
        for (PlayerRecord r : plugin.data().all()) if ((r.eliminated || r.permanent) && !r.pendingRevive) list.add(r);
        list.sort(Comparator.comparing(r -> r.name.toLowerCase()));
        if (list.size() > 45) list = list.subList(0, 45);

        int size = Math.min(54, ((list.size() + 8) / 9) * 9 + 9);
        Holder h = new Holder(Type.REVIVE);
        Inventory inv = Bukkit.createInventory(h, size, Component.text("Revive a player", NamedTextColor.DARK_PURPLE));
        h.inv = inv;

        for (PlayerRecord r : list) {
            ItemStack skull = new ItemStack(Material.PLAYER_HEAD);
            SkullMeta sm = (SkullMeta) skull.getItemMeta();
            sm.setOwningPlayer(Bukkit.getOfflinePlayer(r.uuid));
            sm.displayName(Component.text(r.name, NamedTextColor.LIGHT_PURPLE).decoration(TextDecoration.ITALIC, false));
            sm.lore(List.of(
                    plugin.msg().parse(r.permanent ? "<dark_red>Permanently eliminated (banned)" : "<red>Eliminated").decoration(TextDecoration.ITALIC, false),
                    plugin.msg().parse("<gray>Revive used: " + (r.reviveUsed ? "<red>yes" : "<green>no")).decoration(TextDecoration.ITALIC, false),
                    plugin.msg().parse("<dark_gray>Click to revive (does not use their revive)").decoration(TextDecoration.ITALIC, false)));
            sm.getPersistentDataContainer().set(actionKey, PersistentDataType.STRING, "player:" + r.uuid);
            skull.setItemMeta(sm);
            inv.addItem(skull);
        }
        inv.setItem(size - 5, item(Material.ARROW, "<gray>Back", false, "back"));
        p.openInventory(inv);
    }

    // ------------------------------------------------------------------ clicks

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof Holder)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (!p.hasPermission("soulbound.admin")) {
            p.closeInventory();
            return;
        }
        if (e.getClickedInventory() != e.getInventory()) return;
        ItemStack it = e.getCurrentItem();
        if (it == null || !it.hasItemMeta()) return;
        String action = it.getItemMeta().getPersistentDataContainer().get(actionKey, PersistentDataType.STRING);
        if (action == null) return;

        Msg msg = plugin.msg();
        if (action.startsWith("event:")) {
            SoulEvent ev = SoulEvent.byId(action.substring(6));
            if (ev == null) return;
            p.closeInventory();
            plugin.events().setEvent(ev);
            msg.send(p, "<green>Event set to " + ev.color + ev.display + "<green>.");
        } else if (action.startsWith("player:")) {
            UUID id;
            try { id = UUID.fromString(action.substring(7)); } catch (IllegalArgumentException ex) { return; }
            PlayerRecord r = plugin.data().find(id);
            if (r == null) return;
            p.closeInventory();
            plugin.rituals().forceRevive(r);
            msg.send(p, "<green>Revived <white>" + Msg.esc(r.name) + "<green>.");
        } else if (action.startsWith("time:")) {
            String[] parts = action.split(":");
            if (parts.length != 3) return;
            double delta;
            try { delta = Double.parseDouble(parts[2]); } catch (NumberFormatException ex) { return; }
            EventManager em = plugin.events();
            if (parts[1].equals("interval")) em.setIntervalHours(Math.max(1.0, em.intervalHours() + delta));
            else em.setDurationHours(Math.max(0, em.durationHours() + delta));
            Bukkit.getScheduler().runTask(plugin, () -> openTiming(p));
        } else {
            switch (action) {
                case "timing" -> Bukkit.getScheduler().runTask(plugin, () -> openTiming(p));
                case "events" -> Bukkit.getScheduler().runTask(plugin, () -> openEvents(p));
                case "revive" -> Bukkit.getScheduler().runTask(plugin, () -> openRevive(p));
                case "back" -> Bukkit.getScheduler().runTask(plugin, () -> openMain(p));
                case "roll" -> {
                    p.closeInventory();
                    plugin.events().rollWithCountdown();
                }
                case "stop" -> {
                    p.closeInventory();
                    plugin.events().stop();
                    msg.send(p, "<gray>Event stopped. The next one starts after a full interval.");
                }
                default -> { }
            }
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getInventory().getHolder() instanceof Holder) e.setCancelled(true);
    }
}
