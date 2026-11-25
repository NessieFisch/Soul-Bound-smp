package dev.soulbound.smp;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Stores player states, placed Revive Beacons, and the persisted ritual / event state in data.yml. */
public final class DataManager {

    private final SoulboundPlugin plugin;
    private final File file;
    private YamlConfiguration yaml = new YamlConfiguration();
    private final Map<UUID, PlayerRecord> players = new ConcurrentHashMap<>();
    private final Set<String> cores = ConcurrentHashMap.newKeySet();

    public DataManager(SoulboundPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "data.yml");
    }

    public void load() {
        if (file.exists()) yaml = YamlConfiguration.loadConfiguration(file);
        players.clear();
        cores.clear();
        ConfigurationSection ps = yaml.getConfigurationSection("players");
        if (ps != null) {
            for (String key : ps.getKeys(false)) {
                ConfigurationSection s = ps.getConfigurationSection(key);
                if (s == null) continue;
                UUID id;
                try { id = UUID.fromString(key); } catch (IllegalArgumentException ex) { continue; }
                PlayerRecord r = new PlayerRecord(id, s.getString("name", "unknown"));
                r.eliminated = s.getBoolean("eliminated");
                r.reviveUsed = s.getBoolean("revive-used");
                r.permanent = s.getBoolean("permanent");
                r.pendingRevive = s.getBoolean("pending-revive");
                r.deathLocation = stringToLoc(s.getString("death-location"));
                r.pendingLocation = stringToLoc(s.getString("pending-location"));
                players.put(id, r);
            }
        }
        cores.addAll(yaml.getStringList("cores"));
    }

    public synchronized void save() {
        yaml.set("players", null);
        for (PlayerRecord r : players.values()) {
            String b = "players." + r.uuid + ".";
            yaml.set(b + "name", r.name);
            yaml.set(b + "eliminated", r.eliminated);
            yaml.set(b + "revive-used", r.reviveUsed);
            yaml.set(b + "permanent", r.permanent);
            yaml.set(b + "pending-revive", r.pendingRevive);
            yaml.set(b + "death-location", locToString(r.deathLocation));
            yaml.set(b + "pending-location", locToString(r.pendingLocation));
        }
        yaml.set("cores", new ArrayList<>(cores));
        try {
            plugin.getDataFolder().mkdirs();
            yaml.save(file);
        } catch (IOException ex) {
            plugin.getLogger().severe("Could not save data.yml: " + ex.getMessage());
        }
    }

    public YamlConfiguration raw() { return yaml; }

    // ---- players
    public PlayerRecord record(Player p) {
        PlayerRecord r = players.computeIfAbsent(p.getUniqueId(), id -> new PlayerRecord(id, p.getName()));
        r.name = p.getName();
        return r;
    }

    public PlayerRecord find(UUID id) { return players.get(id); }

    public PlayerRecord findByName(String name) {
        for (PlayerRecord r : players.values()) if (r.name.equalsIgnoreCase(name)) return r;
        return null;
    }

    public Collection<PlayerRecord> all() { return players.values(); }

    // ---- revive beacon blocks
    public static String key(Block b) {
        return b.getWorld().getName() + ";" + b.getX() + ";" + b.getY() + ";" + b.getZ();
    }

    public boolean isCore(Block b) { return cores.contains(key(b)); }
    public void addCore(Block b) { cores.add(key(b)); }
    public void removeCore(Block b) { cores.remove(key(b)); }

    // ---- location helpers
    public static String locToString(Location l) {
        if (l == null || l.getWorld() == null) return null;
        return l.getWorld().getName() + ";" + l.getX() + ";" + l.getY() + ";" + l.getZ() + ";" + l.getYaw() + ";" + l.getPitch();
    }

    public static Location stringToLoc(String s) {
        if (s == null) return null;
        String[] p = s.split(";");
        if (p.length < 6) return null;
        World w = Bukkit.getWorld(p[0]);
        if (w == null) return null;
        try {
            return new Location(w, Double.parseDouble(p[1]), Double.parseDouble(p[2]), Double.parseDouble(p[3]),
                    Float.parseFloat(p[4]), Float.parseFloat(p[5]));
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
