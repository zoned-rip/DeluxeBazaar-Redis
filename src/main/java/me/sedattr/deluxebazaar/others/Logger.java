package me.sedattr.deluxebazaar.others;

import lombok.Getter;
import org.bukkit.Bukkit;

public class Logger {
    @Getter
    public static String prefix = "&8[&bDeluxeBazaar&8]";

    public static void sendConsoleMessage(String message, LogLevel level) {
        if (message == null || message.equals(""))
            return;
        level = level == null ? LogLevel.INFO : level;

        Bukkit.getConsoleSender().sendMessage(Utils.colorize(getPrefix() + " " + level.getPrefix() + " " + level.getColor() + message
                .replace("%prefix%", getPrefix())
                .replace("%level_prefix%", level.getPrefix())
                .replace("%level_color%", level.getColor())));
    }

    public enum LogLevel {
        INFO("&8(&2INFO&8)", "&a"),
        WARN("&8(&6WARN&8)", "&e"),
        ERROR("&8(&4ERROR&8)", "&c");

        @Getter
        private final String prefix;
        @Getter
        private final String color;

        LogLevel(String prefix, String color) {
            this.prefix = prefix;
            this.color = color;
        }
    }
}
