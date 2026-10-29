package net.craftcitizen.imagemaps.core.command;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Routes "/cmd <sub> ..." to registered {@link SubCommand}s, falling back to "help".
 */
public abstract class CommandHandler implements TabExecutor {
    private final Map<String, SubCommand> commands = new HashMap<>();
    private final Plugin plugin;

    protected CommandHandler(Plugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] rawArgs) {
        String[] args = joinQuotedArguments(rawArgs);
        SubCommand sub = args.length > 0 ? commands.get(args[0]) : null;
        if (sub == null)
            sub = commands.get("help");
        if (sub == null)
            return false;

        String message = sub.execute(sender, cmd, label, args);
        if (message != null)
            sender.sendMessage(message);
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String label, String[] args) {
        if (args.length == 0)
            return Collections.emptyList();

        if (args.length == 1)
            return commands.entrySet().stream().filter(e -> e.getKey().startsWith(args[0]))
                           .filter(e -> e.getValue().getPermission().isEmpty()
                                        || sender.hasPermission(e.getValue().getPermission()))
                           .map(Map.Entry::getKey).collect(Collectors.toList());

        SubCommand sub = commands.get(args[0]);
        return sub == null ? Collections.emptyList() : sub.onTabComplete(sender, args);
    }

    public void registerSubCommand(String name, SubCommand command, String... aliases) {
        if (command == null)
            throw new IllegalArgumentException("Command can't be null!");
        if (name == null || name.isEmpty())
            throw new IllegalArgumentException("Commandname can't be empty!");
        List<String> names = new ArrayList<>();
        names.add(name);
        Collections.addAll(names, aliases);
        for (String n : names)
            if (commands.containsKey(n))
                throw new IllegalArgumentException("Command " + n + " is already defined");
        for (String n : names)
            commands.put(n, command);
    }

    protected Plugin getPlugin() {
        return plugin;
    }

    protected Map<String, SubCommand> getCommands() {
        return commands;
    }

    /**
     * Joins arguments wrapped in double quotes, so {@code "my image.png"} becomes one argument without the
     * outer quotes. A group starts at an argument with leading quotes and ends at the first argument where the
     * leading quotes seen so far are matched by trailing ones. An unterminated quote is left untouched.
     */
    static String[] joinQuotedArguments(String[] args) {
        List<String> out = new ArrayList<>(args.length);
        int i = 0;
        while (i < args.length) {
            if (leadingQuotes(args[i]) == 0) {
                out.add(args[i++]);
                continue;
            }

            int balance = 0;
            int end = -1;
            for (int j = i; j < args.length; j++) {
                balance += leadingQuotes(args[j]) - trailingQuotes(args[j]);
                if (balance <= 0) {
                    end = j;
                    break;
                }
            }

            if (end < 0) {
                out.add(args[i++]);
                continue;
            }

            StringBuilder joined = new StringBuilder(args[i]);
            for (int j = i + 1; j <= end; j++)
                joined.append(' ').append(args[j]);
            out.add(joined.length() < 2 ? "" : joined.substring(1, joined.length() - 1));
            i = end + 1;
        }
        return out.toArray(new String[0]);
    }

    private static int leadingQuotes(String s) {
        int n = 0;
        while (n < s.length() && s.charAt(n) == '"')
            n++;
        return n;
    }

    private static int trailingQuotes(String s) {
        int n = 0;
        while (n < s.length() && s.charAt(s.length() - 1 - n) == '"')
            n++;
        return n;
    }
}
