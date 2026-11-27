package dev.soulbound.smp;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Keyed;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Creates legendary weapons, registers their recipes, and tracks cooldowns and how many have been forged. */
public final class LegendaryManager {

    private final SoulboundPlugin plugin;
    private final NamespacedKey tagKey;
    private final Map<LegendaryWeapon, NamespacedKey> recipeKeys = new EnumMap<>(LegendaryWeapon.class);
    private final Map<UUID, Map<LegendaryWeapon, Long>> cooldowns = new HashMap<>();

    public LegendaryManager(SoulboundPlugin plugin) {
        this.plugin = plugin;
        this.tagKey = new NamespacedKey(plugin, "legendary_weapon");
        for (LegendaryWeapon w : LegendaryWeapon.values()) {
            recipeKeys.put(w, new NamespacedKey(plugin, "legendary_" + w.id));
        }
    }

    private String path(LegendaryWeapon w) { return "legendary.weapons." + w.id; }

    // ------------------------------------------------------------------ items

    private Component line(String mini) {
        return plugin.msg().parse(mini).decoration(TextDecoration.ITALIC, false);
    }

    public ItemStack create(LegendaryWeapon w) {
        ItemStack it = new ItemStack(w.material);
        ItemMeta m = it.getItemMeta();
        m.displayName(line(w.title()));

        List<Component> lore = new ArrayList<>();
        lore.add(line(w.gradient(w.mythic() ? "\u2726\u2726 MYTHIC \u2726\u2726" : "\u2726 LEGENDARY \u2726")));
        if (w.epithet() != null) lore.add(line(w.color + "<italic>" + w.epithet()));
        lore.add(line(""));
        if (w.power != null) {
            lore.add(line(w.color + "<bold>" + w.power + "</bold> <dark_gray>\u2022 <gray>" + w.activation() + " <dark_gray>\u2022 <gray>"
                    + cooldownSeconds(w) + "s cooldown"));
            for (String s : w.powerLines) lore.add(line("<gray>  " + s));
            lore.add(line(""));
        }
        if (!w.passiveLines.isEmpty()) {
            lore.add(line("<aqua><bold>Passive</bold>"));
            for (String s : w.passiveLines) lore.add(line("<dark_gray> \u25AA <gray>" + s));
            lore.add(line(""));
        }
        lore.add(line("<dark_gray>Unbreakable"));
        m.lore(lore);

        for (Map.Entry<Enchantment, Integer> en : w.enchants().entrySet()) {
            m.addEnchant(en.getKey(), en.getValue(), true);
        }
        m.setUnbreakable(true);
        m.getPersistentDataContainer().set(tagKey, PersistentDataType.STRING, w.id);
        it.setItemMeta(m);
        return it;
    }

    /** Which legendary weapon this stack is (by hidden tag), or null. */
    public LegendaryWeapon identify(ItemStack it) {
        if (it == null || it.getType().isAir() || !it.hasItemMeta()) return null;
        String id = it.getItemMeta().getPersistentDataContainer().get(tagKey, PersistentDataType.STRING);
        return id == null ? null : LegendaryWeapon.byId(id);
    }

    public boolean isLegendary(ItemStack it) { return identify(it) != null; }

    public LegendaryWeapon held(Player p) { return identify(p.getInventory().getItemInMainHand()); }

    /** The legendary item worn in the chest slot (the God Elytra), or null. */
    public LegendaryWeapon worn(Player p) { return identify(p.getInventory().getChestplate()); }

    // ------------------------------------------------------------------ cooldowns

    public int cooldownSeconds(LegendaryWeapon w) {
        return Math.max(0, plugin.getConfig().getInt(path(w) + ".cooldown-seconds", w.cooldown));
    }

    public long cooldownLeftMs(Player p, LegendaryWeapon w) {
        Map<LegendaryWeapon, Long> m = cooldowns.get(p.getUniqueId());
        if (m == null) return 0L;
        Long until = m.get(w);
        if (until == null) return 0L;
        return Math.max(0L, until - System.currentTimeMillis());
    }

    public void startCooldown(Player p, LegendaryWeapon w) {
        cooldowns.computeIfAbsent(p.getUniqueId(), k -> new EnumMap<>(LegendaryWeapon.class))
                .put(w, System.currentTimeMillis() + cooldownSeconds(w) * 1000L);
    }

    // ------------------------------------------------------------------ forge limit

    /** How many may ever be crafted (0 = unlimited). */
    public int maxCrafted(LegendaryWeapon w) {
        return Math.max(0, plugin.getConfig().getInt(path(w) + ".max-crafted", 1));
    }

    public int crafted(LegendaryWeapon w) {
        return plugin.data().raw().getInt("legendary-crafted." + w.id, 0);
    }

    public boolean canCraft(LegendaryWeapon w) {
        int max = maxCrafted(w);
        return max == 0 || crafted(w) < max;
    }

    public void markCrafted(LegendaryWeapon w) {
        plugin.data().raw().set("legendary-crafted." + w.id, crafted(w) + 1);
        plugin.data().save();
    }

    public void resetCrafted(LegendaryWeapon w) {
        plugin.data().raw().set("legendary-crafted." + w.id, 0);
        plugin.data().save();
    }

    // ------------------------------------------------------------------ recipes

    public void registerRecipes() {
        for (LegendaryWeapon w : LegendaryWeapon.values()) {
            NamespacedKey key = recipeKeys.get(w);
            Bukkit.removeRecipe(key);
            if (!plugin.getConfig().getBoolean(path(w) + ".craftable", true)) continue;
            Bukkit.addRecipe(buildRecipe(w));
        }
        for (Player p : Bukkit.getOnlinePlayers()) discover(p);
    }

    public void unregisterRecipes() {
        for (NamespacedKey key : recipeKeys.values()) Bukkit.removeRecipe(key);
    }

    public void discover(Player p) {
        for (LegendaryWeapon w : LegendaryWeapon.values()) {
            if (plugin.getConfig().getBoolean(path(w) + ".craftable", true)) p.discoverRecipe(recipeKeys.get(w));
        }
    }

    /** The legendary weapon a recipe produces, or null if it is not one of ours. */
    public LegendaryWeapon weaponForRecipe(Recipe r) {
        if (!(r instanceof Keyed k)) return null;
        for (Map.Entry<LegendaryWeapon, NamespacedKey> en : recipeKeys.entrySet()) {
            if (en.getValue().equals(k.getKey())) return en.getKey();
        }
        return null;
    }

    private ShapedRecipe buildRecipe(LegendaryWeapon w) {
        NamespacedKey key = recipeKeys.get(w);
        ConfigurationSection c = plugin.getConfig().getConfigurationSection(path(w) + ".recipe");
        if (c != null) {
            try {
                List<String> shape = c.getStringList("shape");
                ConfigurationSection ing = c.getConfigurationSection("ingredients");
                if (shape.isEmpty() || shape.size() > 3 || ing == null) throw new IllegalArgumentException("bad shape");
                ShapedRecipe r = new ShapedRecipe(key, create(w));
                r.shape(shape.toArray(new String[0]));
                String joined = String.join("", shape);
                for (String k : ing.getKeys(false)) {
                    Material m = Material.matchMaterial(ing.getString(k, ""));
                    if (k.length() != 1 || m == null) throw new IllegalArgumentException("bad ingredient '" + k + "'");
                    if (joined.indexOf(k.charAt(0)) >= 0) r.setIngredient(k.charAt(0), m);
                }
                return r;
            } catch (Exception ex) {
                plugin.getLogger().warning("Invalid recipe for " + w.id + " in config (" + ex.getMessage() + "), using default.");
            }
        }
        ShapedRecipe r = new ShapedRecipe(key, create(w));
        r.shape(w.defaultShape());
        for (Map.Entry<Character, Material> en : w.defaultIngredients().entrySet()) {
            r.setIngredient(en.getKey(), en.getValue());
        }
        return r;
    }
}
