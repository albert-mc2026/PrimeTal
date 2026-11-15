package kg.primetal;

import org.bukkit.ChatColor;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

/** PrimeTal: talismans (work in the inventory) and spheres (work in the off hand). */
public final class PrimeTal extends JavaPlugin {

    @Override
    public void onEnable() {
        saveDefaultConfig();
        Talismans t = new Talismans(this);
        getServer().getPluginManager().registerEvents(t, this);
        for (String name : new String[]{"talisman", "sphere"}) {
            PluginCommand pc = getCommand(name);
            if (pc == null) continue;
            pc.setExecutor(t);
            pc.setTabCompleter(t);
        }
        getLogger().info("PrimeTal enabled.");
    }

    static String c(String s) {
        return ChatColor.translateAlternateColorCodes('&', s == null ? "" : s);
    }
}
