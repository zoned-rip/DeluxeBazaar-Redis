package me.sedattr.deluxebazaar.commands;

import me.sedattr.deluxebazaar.DeluxeBazaar;
import me.sedattr.bazaarapi.BazaarItemHook;
import me.sedattr.deluxebazaar.managers.BazaarItem;
import me.sedattr.deluxebazaar.managers.PlayerBazaar;
import me.sedattr.deluxebazaar.menus.MainMenu;
import me.sedattr.deluxebazaar.others.PlaceholderUtil;
import me.sedattr.deluxebazaar.others.Utils;
import org.bukkit.command.*;
import org.bukkit.entity.Player;

import java.util.*;

public class BazaarCommand implements CommandExecutor, TabCompleter {
    public List<String> onTabComplete(CommandSender commandSender, Command command, String s, String[] args) {
        if (!Utils.checkPermission(commandSender, "player_commands", "command"))
            return null;

        ArrayList<String> complete = new ArrayList<>(Arrays.asList("sellall", "buy", "sell", "menu"));
        complete.removeIf(type -> !Utils.checkPermission(commandSender, "player_commands", type));

        if (args.length == 1)
            return complete;
        return null;
    }

    public boolean onCommand(CommandSender commandSender, Command command, String label, String[] args) {
        if (!Utils.checkPermission(commandSender, "player_commands", "command")) {
            Utils.sendMessage(commandSender, "no_permission");
            return false;
        }

        if (!(commandSender instanceof Player player)) {
            Utils.sendMessage(commandSender, "not_player");
            return false;
        }

        PlayerBazaar playerBazaar;
        PlaceholderUtil placeholderUtil = new PlaceholderUtil()
                .addPlaceholder("%command_name%", label);

        if (args.length > 0) {
            switch (args[0].toLowerCase()) {
                case "commands":
                case "help":
                    Utils.sendMessage(commandSender, "player_usage", placeholderUtil);
                    return true;
                case "info":
                    List<String> lines = new ArrayList<>(
                            Arrays.asList("&8[&6DeluxeBazaar&8] &6Plugin Information",
                                    "&8- &fDeluxeBazaar &eis made by &fSedatTR&e.",
                                    "&8- &eDiscord Support Server: &fdiscord.gg/nchk86TKMT",
                                    "&8- &eCurrent Plugin Version: &fv" + DeluxeBazaar.getInstance().getDescription().getVersion()));

                    for (String line : lines)
                        player.sendMessage(Utils.colorize(line));
                    return true;

                case "sell_all":
                case "sellall":
                    if (!Utils.checkPermission(commandSender, "player_commands", "sellall")) {
                        Utils.sendMessage(commandSender, "no_permission");
                        return false;
                    }

                    DeluxeBazaar.getInstance().itemHandler.updateSellableItems(player);

                    playerBazaar = DeluxeBazaar.getInstance().players.getOrDefault(player.getUniqueId(), new PlayerBazaar(player));
                    HashMap<String, Integer> items = playerBazaar.getSellableItems();
                    if (items.isEmpty()) {
                        Utils.sendMessage(player, "no_sellable_item");
                        return false;
                    }

                    DeluxeBazaar.getInstance().itemHandler.sellBazaarItems(player, items);

                    //DeluxeBazaar.getInstance().itemHandler.sellSpecificItems(player, DeluxeBazaar.getInstance().itemHandler.getSellableItems(player, null));
                    return true;
                case "open":
                case "menu":
                    if (!Utils.checkPermission(commandSender, "player_commands", "menu")) {
                        Utils.sendMessage(commandSender, "no_permission");
                        return false;
                    }

                    playerBazaar = DeluxeBazaar.getInstance().players.getOrDefault(player.getUniqueId(), new PlayerBazaar(player));
                    String category = playerBazaar.getCategory();
                    if (category == null || category.isEmpty() || category.equalsIgnoreCase("search"))
                        category = DeluxeBazaar.getInstance().configFile.getString("settings.default_category", "mining");

                    DeluxeBazaar.getInstance().itemHandler.updateSellableItems(player);

                    new MainMenu(player).openMenu(category, 1);
                    return true;
                case "buy":
                case "sell":
                    if (!Utils.checkPermission(player, "player_commands", args[0].toLowerCase())) {
                        Utils.sendMessage(player, "no_permission");
                        return false;
                    }

                    if (args.length < 3) {
                        Utils.sendMessage(player, args[0].toLowerCase() + "_usage", placeholderUtil);
                        return false;
                    }

                    BazaarItem item = BazaarItemHook.getBazaarItem(args[1]);
                    if (item == null) {
                        Utils.sendMessage(player, "wrong_item", placeholderUtil.addPlaceholder("%item_name%", args[1]));
                        return false;
                    }

                    if (!Utils.checkItemPermission(player, item.getName())) {
                        Utils.sendMessage(player, "no_permission_for_item");
                        return false;
                    }

                    int count;
                    try {
                        count = Integer.parseInt(args[2]);
                    } catch (NumberFormatException ex) {
                        Utils.sendMessage(player, "wrong_number");
                        return false;
                    }

                    DeluxeBazaar.getInstance().economyHandler.event(player, args[0], item, count);
                    return true;
            }
        }

        if (DeluxeBazaar.getInstance().configFile.getBoolean("settings.open_menu_directly")) {
            if (!Utils.checkPermission(commandSender, "player_commands", "menu")) {
                Utils.sendMessage(commandSender, "no_permission");
                return false;
            }

            playerBazaar = DeluxeBazaar.getInstance().players.getOrDefault(player.getUniqueId(), new PlayerBazaar(player));
            String category = playerBazaar.getCategory();
            if (category == null || category.isEmpty() || category.equalsIgnoreCase("search"))
                category = DeluxeBazaar.getInstance().configFile.getString("settings.default_category", "mining");

            DeluxeBazaar.getInstance().itemHandler.updateSellableItems(player);

            new MainMenu(player).openMenu(category, 1);
            return true;
        }

        Utils.sendMessage(commandSender, "player_usage", placeholderUtil);
        //commandSender.sendMessage(Utils.colorize("&8(&fDeluxeBazaar &7is made by &fSedatTR &7with love...&8)"));
        return false;
    }
}
