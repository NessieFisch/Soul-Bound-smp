package dev.soulbound.smp;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.List;
import java.util.UUID;

/**
 * The Heavenly Seal: a craftable one-use item. Sneak + right-click any player with it and
 * their soul is sealed away: permanently eliminated, no revive, and banned.
 */
public final class HeavenlySeal {

    private final SoulboundPlugin plugin;
    private final NamespacedKey tagKey;
    private final NamespacedKey recipeKey;

    public HeavenlySeal(SoulboundPlugin plugin) {
        this.plugin = plugin;
        this.tagKey = new NamespacedKey(plugin, "heavenly_seal");
        this.recipeKey = new NamespacedKey(plugin, "heavenly_seal");
    }

    // ------------------------------------------------------------------ item

    private Material material() {
        Material m = Material.matchMaterial(plugin.getConfig().getString("heavenly-seal.material", "NETHER_STAR"));
        return m == null || m.isAir() ? Material.NETHER_STAR : m;
    }

    public ItemStack create(int amount) {
        ItemStack it = new ItemStack(material(), amount);
        ItemMeta m = it.getItemMeta();
        m.displayName(Component.text("Heavenly Seal", NamedTextColor.GOLD)
                .decoration(TextDecoration.ITALIC, false).decorate(TextDecoration.BOLD));
        m.lore(List.of(
                line("Sneak + right-click a player to", NamedTextColor.GRAY),
                line("seal their soul away forever.", NamedTextColor.GRAY),
                line("", NamedTextColor.GRAY),
                line("Sealed players are banned. No revive.", NamedTextColor.RED),
                line("One use.", NamedTextColor.DARK_GRAY)));
        m.setEnchantmentGlintOverride(true);
        m.getPersistentDataContainer().set(tagKey, PersistentDataType.BYTE, (byte) 1);
        it.setItemMeta(m);
        return it;
    }

    private static Component line(String s, NamedTextColor c) {
        return Component.text(s, c).decoration(TextDecoration.ITALIC, false);
    }

    /** True for a genuine Heavenly Seal (checked by tag, not by material). */
    public boolean is(ItemStack it) {
        if (it == null || it.getType().isAir() || !it.hasItemMeta()) return false;
        return it.getItemMeta().getPersistentDataContainer().has(tagKey, PersistentDataType.BYTE);
    }

    // ------------------------------------------------------------------ recipe

    public void registerRecipe() {
        Bukkit.removeRecipe(recipeKey);
        if (!plugin.getConfig().getBoolean("heavenly-seal.craftable", true)) return;
        try {
            ConfigurationSection c = plugin.getConfig().getConfigurationSection("heavenly-seal.recipe");
            if (c == null) throw new IllegalArgumentException("recipe section missing");
            Bukkit.addRecipe(build(c.getStringList("shape"), c.getConfigurationSection("ingredients")));
        } catch (Exception ex) {
            plugin.getLogger().warning("Invalid heavenly-seal recipe in config (" + ex.getMessage() + "), using default.");
            Bukkit.removeRecipe(recipeKey);
            Bukkit.addRecipe(defaultRecipe());
        }
        for (Player p : Bukkit.getOnlinePlayers()) discover(p);
    }

    private ShapedRecipe build(List<String> shape, ConfigurationSection ingredients) {
        if (shape.isEmpty() || shape.size() > 3 || ingredients == null) throw new IllegalArgumentException("bad shape");
        ShapedRecipe r = new ShapedRecipe(recipeKey, create(1));
        r.shape(shape.toArray(new String[0]));
        String joined = String.join("", shape);
        for (String k : ingredients.getKeys(false)) {
            Material m = Material.matchMaterial(ingredients.getString(k, ""));
            if (k.length() != 1 || m == null) throw new IllegalArgumentException("bad ingredient '" + k + "'");
            if (joined.indexOf(k.charAt(0)) >= 0) r.setIngredient(k.charAt(0), m);
        }
        return r;
    }

    private ShapedRecipe defaultRecipe() {
        ShapedRecipe r = new ShapedRecipe(recipeKey, create(1));
        r.shape("NTN", "DAD", "NTN");
        r.setIngredient('N', Material.NETHER_STAR);
        r.setIngredient('T', Material.TOTEM_OF_UNDYING);
        r.setIngredient('D', Material.DIAMOND_BLOCK);
        r.setIngredient('A', Material.ENCHANTED_GOLDEN_APPLE);
        return r;
    }

    public void unregisterRecipe() { Bukkit.removeRecipe(recipeKey); }

    public void discover(Player p) {
        if (plugin.getConfig().getBoolean("heavenly-seal.craftable", true)) p.discoverRecipe(recipeKey);
    }

    // ------------------------------------------------------------------ use

    /** Called when {@code by} right-clicks {@code target} while holding a seal in {@code hand}. */
    public void use(Player by, Player target, EquipmentSlot hand) {
        Msg msg = plugin.msg();
        if (target.equals(by)) {
            msg.send(by, "<red>You cannot seal yourself.");
            return;
        }
        if (target.isDead()) return;
        PlayerRecord existing = plugin.data().find(target.getUniqueId());
        if (existing != null && existing.permanent) return; // already sealed / banned
        if (target.hasPermission("soulbound.seal.immune")) {
            msg.send(by, "<red>The heavens refuse to seal this soul.");
            return;
        }
        if (plugin.getConfig().getBoolean("heavenly-seal.require-sneak", true) && !by.isSneaking()) {
            msg.actionBar(by, "<gold>Sneak + right-click to seal <white>" + Msg.esc(target.getName())
                    + "<gold>. <red>This bans them forever.");
            return;
        }

        // consume one seal (not in creative)
        if (by.getGameMode() != GameMode.CREATIVE) {
            ItemStack held = by.getInventory().getItem(hand);
            if (held != null) {
                int left = held.getAmount() - 1;
                if (left <= 0) {
                    by.getInventory().setItem(hand, null);
                } else {
                    held.setAmount(left);
                    by.getInventory().setItem(hand, held);
                }
            }
        }
        seal(by, target);
    }

    /** Seals the player's soul: permanent elimination, no revive, then a ban after a short delay. */
    public void seal(Player by, Player target) {
        final UUID id = target.getUniqueId();
        final String name = target.getName();
        final Location loc = target.getLocation().clone();
        final World world = loc.getWorld();

        PlayerRecord r = plugin.data().record(target);
        r.eliminated = true;
        r.reviveUsed = true;
        r.permanent = true;
        r.pendingRevive = false;
        r.pendingLocation = null;
        r.deathLocation = loc;
        // a ritual that was reviving this player can no longer succeed
        plugin.rituals().cancelIfTarget(id);
        plugin.data().save();
        plugin.getLogger().info(by.getName() + " sealed " + name + " with a Heavenly Seal.");

        long delay = Math.max(0L, plugin.getConfig().getLong("heavenly-seal.ban-delay-ticks", 100L));

        // the sealed player is held in place until the ban lands
        target.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, (int) delay + 60, 6, false, false));
        target.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, (int) delay + 60, 0, false, false));
        plugin.msg().title(target, "<gold><bold>SEALED</bold>", "<gray>The heavens have cast you out.");

        // effects
        if (world != null) {
            world.strikeLightningEffect(loc);
            world.spawnParticle(Particle.END_ROD, loc.clone().add(0, 1, 0), 150, 0.4, 1.0, 0.4, 0.15);
            world.spawnParticle(Particle.TOTEM_OF_UNDYING, loc.clone().add(0, 1, 0), 80, 0.5, 1.0, 0.5, 0.3);
        }
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.playSound(p.getLocation(), Sound.BLOCK_END_PORTAL_SPAWN, 0.8f, 1.2f);
            p.playSound(p.getLocation(), Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 0.8f, 1.0f);
        }

        plugin.msg().broadcast("<gold><bold>HEAVENLY SEAL!</bold> <white>" + Msg.esc(by.getName())
                + "<gray> has sealed <white>" + Msg.esc(name) + "<gray> away. <red>They are gone forever.");
        plugin.msg().titleAll("<gold><bold>HEAVENLY SEAL</bold>", "<gray>" + Msg.esc(name) + " has been sealed away");

        // ban + kick
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            plugin.banPlayer(name, "SOULBOUND SMP: You were sealed away by a Heavenly Seal. You are permanently eliminated.");
            Player online = Bukkit.getPlayer(id);
            if (online != null && online.isOnline()) {
                online.kick(Component.text("You were sealed away by a Heavenly Seal.", NamedTextColor.GOLD));
            }
        }, delay);
    }
}
