package net.craftcitizen.imagemaps.core.command;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * "help [command]": the general help, or the help of the named sub command.
 */
public abstract class HelpCommand extends SubCommand {
    private final Map<String, SubCommand> commands;

    protected HelpCommand(String permission, Plugin plugin, Map<String, SubCommand> commands) {
        super(permission, plugin, true);
        this.commands = commands;
    }

    @Override
    protected String execute(CommandSender sender, Command cmd, String label, String[] args) {
        if (sender instanceof Player && !sender.hasPermission(getPermission()))
            return cmd.getPermissionMessage();

        SubCommand target = args.length >= 2 ? commands.get(args[1]) : null;
        if (target != null)
            target.help(sender);
        else
            help(sender);
        return null;
    }

    @Override
    protected List<String> onTabComplete(CommandSender sender, String[] args) {
        if (args.length != 2)
            return Collections.emptyList();
        return commands.keySet().stream().filter(a -> a.startsWith(args[1])).collect(Collectors.toList());
    }
}
