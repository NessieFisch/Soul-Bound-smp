package dev.soulbound.smp;

import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;

import java.util.UUID;

/** Revive Beacon placement / breaking / activation and ritual sabotage. */
public final class RitualListener implements Listener {

    private final SoulboundPlugin plugin;

    public RitualListener(SoulboundPlugin plugin) { this.plugin = plugin; }

    @EventHandler(ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        if (!plugin.beacon().is(e.getItemInHand())) return;
        plugin.data().addCore(e.getBlockPlaced());
        plugin.data().save();
        plugin.msg().send(e.getPlayer(), "<light_purple>Revive Beacon placed. <gray>Build a <white>"
                + plugin.rituals().altarDescription() + "<gray> directly beneath it, then <white>right-click<gray> the beacon to begin the ritual.");
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        if (plugin.rituals().onBlockBreak(e)) return; // part of an active ritual

        Block b = e.getBlock();
        if (!plugin.data().isCore(b)) return;
        // Idle Revive Beacon: give the custom item back instead of a vanilla beacon.
        e.setDropItems(false);
        plugin.data().removeCore(b);
        plugin.data().save();
        Player p = e.getPlayer();
        if (p.getGameMode() != GameMode.CREATIVE) {
            b.getWorld().dropItemNaturally(b.getLocation().add(0.5, 0.5, 0.5), plugin.beacon().create(1));
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Block b = e.getClickedBlock();
        if (b == null || b.getType() != Material.BEACON || !plugin.data().isCore(b)) return;

        Player p = e.getPlayer();
        // Sneaking with a block in hand = just build against it.
        if (p.isSneaking() && p.getInventory().getItemInMainHand().getType().isBlock()
                && !p.getInventory().getItemInMainHand().getType().isAir()) return;

        e.setCancelled(true); // never open the vanilla beacon screen
        if (e.getHand() == EquipmentSlot.HAND) plugin.rituals().interact(p, b);
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof RitualManager.SelectHolder holder)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (e.getClickedInventory() != e.getInventory()) return;
        UUID target = plugin.rituals().targetFrom(e.getCurrentItem());
        if (target == null) return;
        p.closeInventory();
        plugin.rituals().start(p, holder.core, target);
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getInventory().getHolder() instanceof RitualManager.SelectHolder) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent e) {
        plugin.rituals().handleExplosion(e.blockList());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent e) {
        plugin.rituals().handleExplosion(e.blockList());
    }

    @EventHandler(ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent e) {
        for (Block b : e.getBlocks()) {
            if (plugin.data().isCore(b)) { e.setCancelled(true); return; }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent e) {
        for (Block b : e.getBlocks()) {
            if (plugin.data().isCore(b)) { e.setCancelled(true); return; }
        }
    }
}
