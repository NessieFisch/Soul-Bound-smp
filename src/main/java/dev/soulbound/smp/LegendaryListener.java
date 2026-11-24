package dev.soulbound.smp;

import org.bukkit.Bukkit;
import org.bukkit.block.Block;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.entity.Trident;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.PrepareGrindstoneEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerAnimationType;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
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
                + "<gray> has forged " + w.title() + "<gray>!");
        plugin.msg().titleAll(w.title(), "<gray>has been forged by " + Msg.esc(who.getName()));
        plugin.abilities().fx().forge(who.getLocation(), w);
    }

    /** Grindstones would strip the enchants off a legendary weapon. */
    @EventHandler
    public void onGrindstone(PrepareGrindstoneEvent e) {
        GrindstoneInventory inv = e.getInventory();
        if (plugin.legendary().isLegendary(inv.getUpperItem()) || plugin.legendary().isLegendary(inv.getLowerItem())) {
            e.setResult(null);
        }
    }

    /** Blocky weapons (the Thunder Rod is a lightning rod) must never be placed in the world. */
    @EventHandler(ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        if (plugin.legendary().isLegendary(e.getItemInHand())) e.setCancelled(true);
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

    /** Arm swings leave a coloured slash in the air. */
    @EventHandler(ignoreCancelled = true)
    public void onSwing(PlayerAnimationEvent e) {
        if (e.getAnimationType() == PlayerAnimationType.ARM_SWING) plugin.abilities().onSwing(e.getPlayer());
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

    // ------------------------------------------------------------------ projectiles

    @EventHandler(ignoreCancelled = true)
    public void onShoot(EntityShootBowEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        LegendaryWeapon w = plugin.legendary().identify(e.getBow());
        if (w != null) plugin.abilities().onShoot(p, w, e);
    }

    @EventHandler(ignoreCancelled = true)
    public void onLaunch(ProjectileLaunchEvent e) {
        if (e.getEntity() instanceof Trident t && t.getShooter() instanceof Player p) {
            plugin.abilities().onTridentLaunch(p, t);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onProjectileHit(ProjectileHitEvent e) {
        plugin.abilities().onProjectileHit(e);
    }

    // ------------------------------------------------------------------ dropped weapons

    /** A dropped legendary can't burn, explode or despawn, and shines a pillar of light so it can be found. */
    @EventHandler(ignoreCancelled = true)
    public void onItemSpawn(ItemSpawnEvent e) {
        Item item = e.getEntity();
        if (!plugin.legendary().isLegendary(item.getItemStack())) return;
        item.setInvulnerable(true);
        item.setUnlimitedLifetime(true);
        item.setGlowing(true);
        plugin.abilities().trackDrop(item);
    }

    /** Re-adopt dropped legendaries when their chunk loads again. */
    @EventHandler
    public void onEntitiesLoad(EntitiesLoadEvent e) {
        for (Entity en : e.getEntities()) {
            if (en instanceof Item item && plugin.legendary().isLegendary(item.getItemStack())) {
                plugin.abilities().trackDrop(item);
            }
        }
    }
}
