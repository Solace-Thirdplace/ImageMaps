package net.craftcitizen.imagemaps.core.command;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.Collections;
import java.util.List;

/**
 * One sub command of a {@link CommandHandler}.
 */
public abstract class SubCommand {
    private final String permission;
    protected final Plugin plugin;
    private final boolean console;

    protected SubCommand(String permission, Plugin plugin, boolean console) {
        this.permission = permission == null ? "" : permission;
        this.plugin = plugin;
        this.console = console;
    }

    /**
     * Non-players may run console commands; everyone else needs the permission (if any).
     */
    public boolean checkSender(CommandSender sender) {
        if (!(sender instanceof Player) && isConsoleCommand())
            return true;
        return permission.isEmpty() || sender.hasPermission(permission);
    }

    public Plugin getPlugin() {
        return plugin;
    }

    public boolean isConsoleCommand() {
        return console;
    }

    public String getPermission() {
        return permission;
    }

    /**
     * Runs the sub command.
     *
     * @param args all arguments, the sub command name first, quoted arguments already joined
     * @return a message for the sender, or null for none
     */
    protected abstract String execute(CommandSender sender, Command cmd, String label, String[] args);

    /**
     * @param args all arguments, the sub command name first, the last one being completed
     */
    protected List<String> onTabComplete(CommandSender sender, String[] args) {
        return Collections.emptyList();
    }

    public abstract void help(CommandSender sender);
}
