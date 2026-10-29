package net.craftcitizen.imagemaps.core;

import net.md_5.bungee.api.ChatColor;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.Plugin;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Sends "[prefix] message" lines coloured by {@link MessageLevel}.
 */
public final class MessageUtil {
    private static final Style UNREGISTERED = new Style(new TextComponent(), new EnumMap<>(MessageLevel.class));
    private static final Map<String, Style> STYLES = new ConcurrentHashMap<>();

    private MessageUtil() {
    }

    public static void registerPlugin(Plugin plugin, BaseComponent prefix, ChatColor normal, ChatColor info,
                                      ChatColor warning, ChatColor error, ChatColor debug) {
        Map<MessageLevel, ChatColor> colors = new EnumMap<>(MessageLevel.class);
        colors.put(MessageLevel.NORMAL, normal);
        colors.put(MessageLevel.INFO, info);
        colors.put(MessageLevel.WARNING, warning);
        colors.put(MessageLevel.ERROR, error);
        colors.put(MessageLevel.DEBUG, debug);
        colors.put(MessageLevel.SUCCESS, ChatColor.GREEN);
        STYLES.put(plugin.getName(), new Style(prefix, colors));
    }

    public static void sendMessage(Plugin plugin, CommandSender sender, MessageLevel level, String message) {
        sendMessage(plugin, sender, level, new TextComponent(message));
    }

    public static void sendMessage(Plugin plugin, CommandSender sender, MessageLevel level, BaseComponent message) {
        Style style = plugin == null ? UNREGISTERED : STYLES.getOrDefault(plugin.getName(), UNREGISTERED);
        sender.spigot().sendMessage(style.format(level, message));
    }

    private static final class Style {
        private final BaseComponent prefix;
        private final Map<MessageLevel, ChatColor> colors;

        Style(BaseComponent prefix, Map<MessageLevel, ChatColor> colors) {
            this.prefix = prefix;
            this.colors = colors;
        }

        BaseComponent format(MessageLevel level, BaseComponent message) {
            BaseComponent line = new TextComponent();
            ChatColor color = colors.get(level);
            if (color != null)
                line.setColor(color);
            line.addExtra(prefix.duplicate());
            line.addExtra(" ");
            line.addExtra(message);
            return line;
        }
    }
}
