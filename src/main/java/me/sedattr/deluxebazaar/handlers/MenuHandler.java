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
            int slot = section.getInt(key + ".slot");
            if (slot <= 0)
                continue;

            ItemStack item = Utils.createItemFromSection(section.getConfigurationSection(key), null);
            if (item == null)
                continue;

            List<String> commands = section.getStringList(key + ".commands");
            if (commands.isEmpty())
                gui.setItem(slot, ClickableItem.empty(item));
            else
                gui.setItem(slot, ClickableItem.of(item, (event) -> {
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
                }));
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

        addCustomItems(player, gui, section);
        addNormalItems(player, gui, section, type);
        return gui;
    }

    public static String capTitle(String s) {
        if (s.length() > 32)
            return s.substring(0, 29) + "...";

        return s;
    }
}
