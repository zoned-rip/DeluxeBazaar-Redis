package me.sedattr.deluxebazaar.commands;

import me.sedattr.deluxebazaar.DeluxeBazaar;
import me.sedattr.bazaarapi.BazaarItemHook;
import me.sedattr.deluxebazaar.inventoryapi.HInventory;
import me.sedattr.deluxebazaar.inventoryapi.inventory.InventoryAPI;
import me.sedattr.deluxebazaar.managers.BazaarItem;
import me.sedattr.deluxebazaar.managers.PlayerBazaar;
import me.sedattr.deluxebazaar.menus.MainMenu;
import me.sedattr.deluxebazaar.others.PlaceholderUtil;
import me.sedattr.deluxebazaar.others.Utils;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.io.File;
import java.io.IOException;
import java.util.*;

public class BazaarAdminCommand implements CommandExecutor, TabCompleter {
    public List<String> onTabComplete(CommandSender commandSender, Command command, String s, String[] args) {
        if (!Utils.checkPermission(commandSender, "admin_commands", "command"))
            return null;

        ArrayList<String> complete = new ArrayList<>(Arrays.asList("reload", "buy", "sell", "menu", "createitem", "sellall", "clearorders"));
        complete.removeIf(type -> !Utils.checkPermission(commandSender, "admin_commands", type));

        if (args.length == 1)
            return complete;

        // Tab complete item names for clearorders command
        if (args.length == 2 && args[0].equalsIgnoreCase("clearorders")) {
            return new ArrayList<>(DeluxeBazaar.getInstance().bazaarItems.keySet());
        }

        return null;
    }

    public boolean onCommand(CommandSender commandSender, Command command, String label, String[] args) {
        if (!Utils.checkPermission(commandSender, "admin_commands", "command")) {
            Utils.sendMessage(commandSender, "no_permission");
            return false;
        }

        PlaceholderUtil placeholderUtil = new PlaceholderUtil()
                .addPlaceholder("%command_name%", label);

        if (args.length > 0) {
            switch (args[0].toLowerCase()) {
                case "reload":
                    if (!Utils.checkPermission(commandSender, "admin_commands", "reload")) {
                        Utils.sendMessage(commandSender, "no_permission");
                        return false;
                    }

                    long start2 = System.currentTimeMillis();
                    Bukkit.getServer().getOnlinePlayers().forEach(player -> {
                        HInventory gui = InventoryAPI.getInventory(player);
                        if (gui != null)
                            player.closeInventory();
                    });

                    DeluxeBazaar.getInstance().databaseManager.saveDatabase();

                    DeluxeBazaar.getInstance().reloadConfig();
                    DeluxeBazaar.getInstance().dataHandler.load();

                    // Load database to restore prices, orders, and player data
                    DeluxeBazaar.getInstance().databaseManager.loadDatabase();

                    Utils.sendMessage(commandSender, "reloaded", new PlaceholderUtil()
                            .addPlaceholder("%reload_time%", String.valueOf(System.currentTimeMillis() - start2)));
                    return true;
                case "open":
                case "menu":
                    if (!Utils.checkPermission(commandSender, "admin_commands", "menu")) {
                        Utils.sendMessage(commandSender, "no_permission");
                        return false;
                    }

                    if (args.length < 2) {
                        Utils.sendMessage(commandSender, "admin_menu_usage", placeholderUtil);
                        return false;
                    }

                    Player player = Bukkit.getPlayerExact(args[1]);
                    if (player == null) {
                        Utils.sendMessage(commandSender, "wrong_player", placeholderUtil
                                .addPlaceholder("%player_name%", args[1]));
                        return false;
                    }

                    String category = args.length > 2 ? args[2] : DeluxeBazaar.getInstance().configFile.getString("settings.default_category", "mining");
                    DeluxeBazaar.getInstance().itemHandler.updateSellableItems(player);

                    new MainMenu(player).openMenu(category, 1);
                    return true;
                case "create":
                case "createitem":
                case "create_item":
                    if (!Utils.checkPermission(commandSender, "admin_commands", "createitem")) {
                        Utils.sendMessage(commandSender, "no_permission");
                        return false;
                    }

                    if (!(commandSender instanceof Player)) {
                        Utils.sendMessage(commandSender, "not_player");
                        return false;
                    }

                    if (args.length < 3) {
                        Utils.sendMessage(commandSender, "create_item_usage", placeholderUtil);
                        return false;
                    }

                    if (DeluxeBazaar.getInstance().categoriesFile.getConfigurationSection(args[2]) == null) {
                        Utils.sendMessage(commandSender, "wrong_category", placeholderUtil
                                .addPlaceholder("%category_name%", args[2]));
                        return false;
                    }

                    ItemStack item = ((Player) commandSender).getItemInHand().clone();
                    if (item.getType().equals(Material.AIR)) {
                        Utils.sendMessage(commandSender, "hold_item");
                        return false;
                    }

                    File file = new File(DeluxeBazaar.getInstance().getDataFolder(), "created_items.yml");
                    YamlConfiguration configuration = null;
                    try {
                        if (!file.exists())
                            DeluxeBazaar.getInstance().saveResource("created_items.yml", false);

                        configuration = new YamlConfiguration();
                        configuration.load(file);
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                    if (configuration == null)
                        return false;

                    ItemMeta meta = item.getItemMeta();
                    String name;
                    if (meta != null && meta.hasDisplayName())
                        name = meta.getDisplayName();
                    else
                        name = Utils.getDisplayName(item);

                    List<String> lore = new ArrayList<>();
                    if (meta != null && meta.hasLore())
                        lore = meta.getLore();

                    if (!lore.isEmpty())
                        configuration.set("items." + args[1] + ".lore", lore);

                    configuration.set("items." + args[1] + ".item", item);
                    configuration.set("items." + args[1] + ".category", args[2]);
                    configuration.set("items." + args[1] + ".name", DeluxeBazaar.getInstance().categoriesFile.getString(args[2] + ".color", "") + name);
                    configuration.set("items." + args[1] + ".prices.buy", 10.0);
                    configuration.set("items." + args[1] + ".prices.sell", 5.0);

                    try {
                        configuration.save(file);
                    } catch (IOException e) {
                        e.printStackTrace();
                    }

                    DeluxeBazaar.getInstance().itemsFile = YamlConfiguration.loadConfiguration(new File(DeluxeBazaar.getInstance().getDataFolder(), "items.yml"));


                    Utils.sendMessage(commandSender, "created_item", new PlaceholderUtil()
                            .addPlaceholder("%item_name%", args[1])
                            .addPlaceholder("%category_name%", args[2]));
                    return true;
                case "sell_all":
                case "sellall": {
                    if (!Utils.checkPermission(commandSender, "admin_commands", "sellall")) {
                        Utils.sendMessage(commandSender, "no_permission");
                        return false;
                    }

                    if (args.length < 2) {
                        Utils.sendMessage(commandSender, "admin_sell_all_usage", placeholderUtil);
                        return false;
                    }

                    Player target = Bukkit.getPlayerExact(args[1]);
                    if (target == null) {
                        Utils.sendMessage(commandSender, "wrong_player", placeholderUtil
                                .addPlaceholder("%player_name%", args[1]));
                        return false;
                    }


                    DeluxeBazaar.getInstance().itemHandler.updateSellableItems(target);

                    PlayerBazaar playerBazaar = DeluxeBazaar.getInstance().players.getOrDefault(target.getUniqueId(), new PlayerBazaar(target));
                    HashMap<String, Integer> items = playerBazaar.getSellableItems();
                    if (items == null || items.isEmpty()) {
                        Utils.sendMessage(target, "no_sellable_item");
                        return false;
                    }

                    DeluxeBazaar.getInstance().itemHandler.sellBazaarItems(target, items);
                    return true;
                }

                case "buy":
                case "sell":
                    if (!Utils.checkPermission(commandSender, "admin_commands", args[0].toLowerCase())) {
                        Utils.sendMessage(commandSender, "no_permission");
                        return false;
                    }

                    if (args.length < 4) {
                        Utils.sendMessage(commandSender, "admin" + (args[0].equalsIgnoreCase("buy") ? "_buy" : "_sell") + "_usage", placeholderUtil);
                        return false;
                    }

                    Player target = Bukkit.getPlayerExact(args[1]);
                    if (target == null) {
                        Utils.sendMessage(commandSender, "wrong_player", placeholderUtil
                                .addPlaceholder("%player_name%", args[1]));
                        return false;
                    }

                    BazaarItem itemName = BazaarItemHook.getBazaarItem(args[2]);
                    if (itemName == null) {
                        Utils.sendMessage(commandSender, "wrong_item", placeholderUtil
                                .addPlaceholder("%item_name%", args[2]));
                        return false;
                    }

                    int count;
                    try {
                        count = Integer.parseInt(args[3]);
                    } catch (NumberFormatException ex) {
                        Utils.sendMessage(commandSender, "wrong_number");
                        return false;
                    }

                    return DeluxeBazaar.getInstance().economyHandler.event(target, args[0].toLowerCase(), itemName, count);

                case "clearorders":
                    if (!Utils.checkPermission(commandSender, "admin_commands", "clearorders")) {
                        Utils.sendMessage(commandSender, "no_permission");
                        return false;
                    }

                    if (args.length < 2) {
                        Utils.sendMessage(commandSender, "admin_clearorders_usage", placeholderUtil);
                        return false;
                    }

                    BazaarItem clearItem = BazaarItemHook.getBazaarItem(args[1]);
                    if (clearItem == null) {
                        Utils.sendMessage(commandSender, "wrong_item", placeholderUtil
                                .addPlaceholder("%item_name%", args[1]));
                        return false;
                    }

                    int buyOrdersCleared = clearItem.getBuyPrices().size();
                    clearItem.getBuyPrices().clear();

                    int sellOrdersCleared = clearItem.getSellPrices().size();
                    clearItem.getSellPrices().clear();

                    if (DeluxeBazaar.getInstance().databaseManager instanceof me.sedattr.deluxebazaar.database.RedisDatabase) {
                        me.sedattr.deluxebazaar.database.RedisDatabase redisDb =
                                (me.sedattr.deluxebazaar.database.RedisDatabase) DeluxeBazaar.getInstance().databaseManager;
                        redisDb.saveItemAsync(args[1], clearItem);
                    } else if (DeluxeBazaar.getInstance().databaseManager instanceof me.sedattr.deluxebazaar.database.MySQLDatabase) {
                        me.sedattr.deluxebazaar.database.MySQLDatabase mysqlDb =
                                (me.sedattr.deluxebazaar.database.MySQLDatabase) DeluxeBazaar.getInstance().databaseManager;
                        mysqlDb.saveItemAsync(args[1], clearItem);
                    }

                    Utils.sendMessage(commandSender, "cleared_orders", new PlaceholderUtil()
                            .addPlaceholder("%item_name%", clearItem.getName())
                            .addPlaceholder("%buy_orders%", String.valueOf(buyOrdersCleared))
                            .addPlaceholder("%sell_orders%", String.valueOf(sellOrdersCleared)));
                    return true;
            }
        }

        Utils.sendMessage(commandSender, "admin_usage", placeholderUtil);
        return false;
    }
}
