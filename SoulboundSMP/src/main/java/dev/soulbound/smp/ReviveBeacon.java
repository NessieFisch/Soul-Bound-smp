package dev.soulbound.smp;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.List;

/** The craftable Revive Beacon item + its recipe. */
public final class ReviveBeacon {

    private final SoulboundPlugin plugin;
    private final NamespacedKey tagKey;
    private final NamespacedKey recipeKey;

    public ReviveBeacon(SoulboundPlugin plugin) {
        this.plugin = plugin;
        this.tagKey = new NamespacedKey(plugin, "revive_beacon");
        this.recipeKey = new NamespacedKey(plugin, "revive_beacon");
    }

    public ItemStack create(int amount) {
        ItemStack it = new ItemStack(Material.BEACON, amount);
        ItemMeta m = it.getItemMeta();
        m.displayName(Component.text("Revive Beacon", NamedTextColor.LIGHT_PURPLE)
                .decoration(TextDecoration.ITALIC, false).decorate(TextDecoration.BOLD));
        m.lore(List.of(
                line("Place it on top of a ritual altar,"),
                line("then right-click it to bring back"),
                line("a fallen soul. One revive per player.")));
        m.setEnchantmentGlintOverride(true);
        m.getPersistentDataContainer().set(tagKey, PersistentDataType.BYTE, (byte) 1);
        it.setItemMeta(m);
        return it;
    }

    private static Component line(String s) {
        return Component.text(s, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false);
    }

    public boolean is(ItemStack it) {
        if (it == null || it.getType() != Material.BEACON || !it.hasItemMeta()) return false;
        return it.getItemMeta().getPersistentDataContainer().has(tagKey, PersistentDataType.BYTE);
    }

    public void registerRecipe() {
        Bukkit.removeRecipe(recipeKey);
        try {
            ConfigurationSection c = plugin.getConfig().getConfigurationSection("revive-beacon.recipe");
            if (c == null) throw new IllegalArgumentException("recipe section missing");
            Bukkit.addRecipe(build(c.getStringList("shape"), c.getConfigurationSection("ingredients")));
        } catch (Exception ex) {
            plugin.getLogger().warning("Invalid revive-beacon recipe in config (" + ex.getMessage() + "), using default.");
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
        r.shape("DTD", "GNG", "OOO");
        r.setIngredient('D', Material.DIAMOND_BLOCK);
        r.setIngredient('T', Material.TOTEM_OF_UNDYING);
        r.setIngredient('G', Material.GOLD_BLOCK);
        r.setIngredient('N', Material.NETHER_STAR);
        r.setIngredient('O', Material.OBSIDIAN);
        return r;
    }

    public void unregisterRecipe() { Bukkit.removeRecipe(recipeKey); }

    public void discover(Player p) { p.discoverRecipe(recipeKey); }
}
