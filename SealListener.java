package dev.soulbound.smp;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

/** Using the Heavenly Seal on a player, and keeping it out of other crafting recipes. */
public final class SealListener implements Listener {

    private final SoulboundPlugin plugin;

    public SealListener(SoulboundPlugin plugin) { this.plugin = plugin; }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onUse(PlayerInteractEntityEvent e) {
        if (!(e.getRightClicked() instanceof Player target)) return;
        EquipmentSlot hand = e.getHand();
        Player p = e.getPlayer();
        if (!plugin.seal().is(p.getInventory().getItem(hand))) return;
        e.setCancelled(true);
        plugin.seal().use(p, target, hand);
    }

    /** A seal is a re-skinned nether star by default: don't let it be crafted away as a plain ingredient. */
    @EventHandler
    public void onPrepareCraft(PrepareItemCraftEvent e) {
        for (ItemStack it : e.getInventory().getMatrix()) {
            if (plugin.seal().is(it)) {
                e.getInventory().setResult(null);
                return;
            }
        }
    }
}
