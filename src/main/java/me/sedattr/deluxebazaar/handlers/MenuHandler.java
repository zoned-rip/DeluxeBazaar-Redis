package me.sedattr.deluxebazaar.handlers;

import me.sedattr.deluxebazaar.inventoryapi.inventory.InventoryAPI;
import me.sedattr.deluxebazaar.inventoryapi.HInventory;
import me.sedattr.deluxebazaar.inventoryapi.item.ClickableItem;
import me.sedattr.deluxebazaar.DeluxeBazaar;
import me.sedattr.deluxebazaar.others.Logger;
import me.sedattr.deluxebazaar.others.MaterialHelper;
import me.sedattr.deluxebazaar.others.PlaceholderUtil;
import me.sedattr.deluxebazaar.others.Utils;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class MenuHandler {
    public void addCustomItems(Player player, HInventory gui, ConfigurationSection section) {
        if (section == null)
            return;

        section = section.getConfigurationSection("items");
        if (section == null)
            return;

        Set<String> keys = section.getKeys(false);
        if (keys.isEmpty())
            return;

        for (String key : keys) {
            List<Integer> slots = section.getIntegerList(key + ".slots");
            int singleSlot = section.getInt(key + ".slot");
            
            if (slots.isEmpty()) {
                if (singleSlot <= 0)
                    continue;
                slots = List.of(singleSlot);
            }

            ItemStack item = Utils.createItemFromSection(section.getConfigurationSection(key), null);
            if (item == null)
                continue;

            List<String> commands = section.getStringList(key + ".commands");
            ClickableItem clickableItem;
            
            if (commands.isEmpty())
                clickableItem = ClickableItem.empty(item);
            else
                clickableItem = ClickableItem.of(item, (event) -> {
                    for (String command : commands) {
                        command = command
                                .replace("%player_displayname%", player.getDisplayName())
                                .replace("%player_name%", player.getName())
                                .replace("%player_uuid%", String.valueOf(player.getUniqueId()));

                        if (command.startsWith("[close]"))
                            player.closeInventory();
                        else if (command.startsWith("[player]"))
                            player.performCommand(command
                                    .replace("[player] ", "")
                                    .replace("[player]", ""));
                        else
                            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command
                                    .replace("[console] ", "")
                                    .replace("[console]", ""));
                    }
                });
            
            for (int slot : slots) {
                if (slot > 0)
                    gui.setItem(slot, clickableItem);
            }
        }
    }

    public void addNormalItems(Player player, HInventory gui, ConfigurationSection section, String item) {
        List<Integer> glassSlots = section.getIntegerList("glass");
        ItemStack glass = DeluxeBazaar.getInstance().normalItems.get("glass");
        if (glass == null)
            return;
        glass = glass.clone();

        if (!glassSlots.isEmpty()) {
            if (item != null && !item.equals("")) {
                if (section.getName().equalsIgnoreCase("main") || section.getBoolean("coloredGlasses")) {
                    ConfigurationSection glassSection = DeluxeBazaar.getInstance().categoriesFile.getConfigurationSection(item + ".glass");
                    if (glassSection != null) {
                        String material = glassSection.getString("material");
                        if (material != null && !material.equals("")) {
                            Material mat = MaterialHelper.getMaterial(material);
                            if (mat == null)
                                Logger.sendConsoleMessage("&f" + material + " &eis wrong material in main menu!", Logger.LogLevel.WARN);
                            else {
                                glass.setType(mat);
                                glass.setDurability((short) glassSection.getInt("data"));

                                if (DeluxeBazaar.getInstance().version > 13 && glassSection.getInt("model", 0) != 0) {
                                    ItemMeta meta = glass.getItemMeta();
                                    meta.setCustomModelData(glassSection.getInt("model", 0));

                                    glass.setItemMeta(meta);
                                }
                            }
                        }
                    }
                }
            }

            for (int i : glassSlots)
                gui.setItem(i-1, ClickableItem.empty(glass));
        }

        int closeSlot = section.getInt("close");
        ItemStack close = DeluxeBazaar.getInstance().normalItems.get("close");
        if (closeSlot > 0 && close != null)
            gui.setItem(closeSlot-1, ClickableItem.of(close, (event) -> player.closeInventory()));
    }

    String randomUUID = "%%__USER__%%";
    public HInventory createInventory(Player player, ConfigurationSection section, String type, PlaceholderUtil placeholderUtil) {
        int size = section.getInt("size", 6);

        size = size > 6 ? size/9 : size;
        if (size <= 0)
            size = 6;

        String title = section.getName().equals("main") && type.equalsIgnoreCase("search") && DeluxeBazaar.getInstance().categoriesFile.getString("search.title") != null ? DeluxeBazaar.getInstance().categoriesFile.getString("search.title") : section.getString("title");
        if (title == null)
            title = "&cTitle is missing in config!";

        if (placeholderUtil != null) {
            HashMap<String, String> placeholders = placeholderUtil.getPlaceholders();
            if (placeholders != null && !placeholders.isEmpty())
                for (Map.Entry<String, String> placeholder : placeholders.entrySet()) {
                    String key = placeholder.getKey();
                    String value = placeholder.getValue();

                    title = title
                            .replace(key, value);
                }
        }

        HInventory gui = InventoryAPI.getInventoryManager()
                .setTitle(Utils.colorize(capTitle(title)))
                .setSize(size)
                .setId(type)
                .create();

        boolean skipCustomItems = type.equalsIgnoreCase("setBuyOrder") || 
                                   type.equalsIgnoreCase("setSellOffer") ||
                                   type.equalsIgnoreCase("buyOrder") ||
                                   type.equalsIgnoreCase("sellOffer") ||
                                   type.equalsIgnoreCase("buy") ||
                                   type.equalsIgnoreCase("sell") ||
                                   type.equalsIgnoreCase("sellAll");
        
        if (!skipCustomItems) {
            addCustomItems(player, gui, section);
        }
        addNormalItems(player, gui, section, type);
        return gui;
    }

    /**
     * Sets an item in multiple slots. Supports both single 'slot' and multiple 'slots' from config.
     * @param gui The inventory to set items in
     * @param section The config section containing slot/slots
     * @param clickableItem The item to place
     */
    public static void setItemInSlots(HInventory gui, ConfigurationSection section, ClickableItem clickableItem) {
        if (section == null || clickableItem == null) return;
        
        List<Integer> slots = section.getIntegerList("slots");
        if (slots.isEmpty()) {
            int singleSlot = section.getInt("slot");
            if (singleSlot > 0) {
                gui.setItem(singleSlot - 1, clickableItem);
            }
        } else {
            for (int slot : slots) {
                if (slot > 0) {
                    gui.setItem(slot - 1, clickableItem);
                }
            }
        }
    }

    public static String capTitle(String s) {
        if (s.length() > 32)
            return s.substring(0, 29) + "...";

        return s;
    }
}
