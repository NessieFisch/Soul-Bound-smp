package dev.soulbound.smp;

import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;

public final class SoulboundPlugin extends JavaPlugin {

    private DataManager data;
    private Msg msg;
    private ReviveBeacon beacon;
    private RitualManager rituals;
    private EventManager events;
    private AdminGui adminGui;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        msg = new Msg(this);
        data = new DataManager(this);
        data.load();
        beacon = new ReviveBeacon(this);
        beacon.registerRecipe();
        rituals = new RitualManager(this);
        events = new EventManager(this);

        PlayerListener playerListener = new PlayerListener(this);
        register(playerListener);
        register(new RitualListener(this));
        register(new EventListener(this));
        adminGui = new AdminGui(this);
        register(adminGui);
        playerListener.startEnforcement();

        PluginCommand cmd = getCommand("soulbound");
        if (cmd != null) {
            SoulCommand sc = new SoulCommand(this);
            cmd.setExecutor(sc);
            cmd.setTabCompleter(sc);
        }

        PluginCommand adminCmd = getCommand("sbadmin");
        if (adminCmd != null) {
            adminCmd.setExecutor((sender, command, label, args) -> {
                if (!sender.hasPermission("soulbound.admin")) {
                    msg.send(sender, "<red>You don't have permission to do that.");
                } else if (sender instanceof org.bukkit.entity.Player p) {
                    adminGui.openMain(p);
                } else {
                    msg.send(sender, "<red>The admin menu is for players only. Use /sb event set <id> and /sb revive <player> from console.");
                }
                return true;
            });
        }
        PluginCommand bcCmd = getCommand("sbroadcast");
        if (bcCmd != null) {
            BroadcastCommand bc = new BroadcastCommand(this);
            bcCmd.setExecutor(bc);
            bcCmd.setTabCompleter(bc);
        }

        rituals.resume();
        events.start();
        getLogger().info("Soulbound SMP enabled.");
    }

    @Override
    public void onDisable() {
        if (rituals != null) rituals.shutdown();
        if (events != null) events.shutdown();
        if (beacon != null) beacon.unregisterRecipe();
        if (data != null) data.save();
    }

    private void register(Listener l) {
        getServer().getPluginManager().registerEvents(l, this);
    }

    public void reload() {
        reloadConfig();
        msg.reload();
        beacon.registerRecipe();
    }

    /** Permanently bans a player by name using the vanilla ban command. */
    public void banPlayer(String name, String reason) {
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "ban " + name + " " + reason);
    }

    public void pardonPlayer(String name) {
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "pardon " + name);
    }

    public DataManager data() { return data; }
    public Msg msg() { return msg; }
    public ReviveBeacon beacon() { return beacon; }
    public RitualManager rituals() { return rituals; }
    public EventManager events() { return events; }
    public AdminGui adminGui() { return adminGui; }
}
