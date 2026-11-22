package dev.soulbound.smp;

import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.PrepareGrindstoneEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.CraftingInventory;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.GrindstoneInventory;
import org.bukkit.inventory.ItemStack;

/** Wires the legendary weapons into the game: powers, passives, forging and item protection. */
public final class LegendaryListener implements Listener {

    private final SoulboundPlugin plugin;

    public LegendaryListener(SoulboundPlugin plugin) { this.plugin = plugin; }

    // ------------------------------------------------------------------ forging

    @EventHandler
    public void onPrepareCraft(PrepareItemCraftEvent e) {
        CraftingInventory inv = e.getInventory();
        // legendary weapons can't be used up as crafting ingredients
        for (ItemStack it : inv.getMatrix()) {
            if (plugin.legendary().isLegendary(it)) {
                inv.setResult(null);
                return;
            }
        }
        LegendaryWeapon w = plugin.legendary().weaponForRecipe(e.getRecipe());
        if (w != null && !plugin.legendary().canCraft(w)) inv.setResult(null);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onCraft(CraftItemEvent e) {
        LegendaryWeapon w = plugin.legendary().weaponForRecipe(e.getRecipe());
        if (w == null) return;
        ItemStack cursor = e.getCursor();
        boolean cursorBusy = cursor != null && !cursor.getType().isAir();
        // one at a time, hands free, and only while the world still has a slot for it
        if (e.isShiftClick() || cursorBusy || !plugin.legendary().canCraft(w)) {
            e.setCancelled(true);
            return;
        }
        plugin.legendary().markCrafted(w);

        HumanEntity who = e.getWhoClicked();
        plugin.msg().broadcast("<gold><bold>LEGENDARY FORGED!</bold> <white>" + Msg.esc(who.getName())
                + "<gray> has forged " + w.color + "<bold>" + w.display + "</bold><gray>!");
        plugin.msg().titleAll(w.color + "<bold>" + w.display, "<gray>has been forged by " + Msg.esc(who.getName()));
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
        }
    }

    /** Grindstones would strip the enchants off a legendary weapon. */
    @EventHandler
    public void onGrindstone(PrepareGrindstoneEvent e) {
        GrindstoneInventory inv = e.getInventory();
        if (plugin.legendary().isLegendary(inv.getUpperItem()) || plugin.legendary().isLegendary(inv.getLowerItem())) {
            e.setResult(null);
        }
    }

    // ------------------------------------------------------------------ powers

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        Action a = e.getAction();
        if (a != Action.RIGHT_CLICK_AIR && a != Action.RIGHT_CLICK_BLOCK) return;
        if (e.useItemInHand() == Event.Result.DENY) return;
        LegendaryWeapon w = plugin.legendary().identify(e.getItem());
        if (w == null || !w.active) return;

        Player p = e.getPlayer();
        Block b = e.getClickedBlock();
        // let people still open chests / use ritual beacons while holding a weapon
        if (b != null && !p.isSneaking() && (plugin.data().isCore(b) || b.getType().isInteractable())) return;

        e.setCancelled(true); // no tilling, stripping logs, etc.
        plugin.abilities().trigger(p, w);
    }

    // ------------------------------------------------------------------ passives

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent e) {
        LegendaryAbilities ab = plugin.abilities();
        if (ab.isInternal()) return;
        Entity d = e.getDamager();
        if (d instanceof Player p) {
            if (e.getCause() != EntityDamageEvent.DamageCause.ENTITY_ATTACK) return;
            LegendaryWeapon w = plugin.legendary().held(p);
            if (w != null) ab.onMelee(p, w, e);
        } else if (d instanceof AbstractArrow arrow) {
            ab.onArrowHit(arrow, e);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onFall(EntityDamageEvent e) {
        if (e.getCause() != EntityDamageEvent.DamageCause.FALL || !(e.getEntity() instanceof Player p)) return;
        if (plugin.legendary().held(p) == LegendaryWeapon.GOD_MACE || plugin.abilities().isSlamming(p)) {
            e.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onShoot(EntityShootBowEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        if (plugin.legendary().identify(e.getBow()) == LegendaryWeapon.VOIDPIERCER) {
            plugin.abilities().onShoot(p, e);
        }
    }

    // ------------------------------------------------------------------ dropped weapons

    /** A dropped legendary can't burn, explode or despawn, and glows so it can be found. */
    @EventHandler(ignoreCancelled = true)
    public void onItemSpawn(ItemSpawnEvent e) {
        Item item = e.getEntity();
        if (!plugin.legendary().isLegendary(item.getItemStack())) return;
        item.setInvulnerable(true);
        item.setUnlimitedLifetime(true);
        item.setGlowing(true);
    }
}
