package me.sedattr.deluxebazaar.menus;

import me.sedattr.deluxebazaar.DeluxeBazaar;
import me.sedattr.bazaarapi.BazaarItemHook;
import me.sedattr.deluxebazaar.inventoryapi.HInventory;
import me.sedattr.deluxebazaar.inventoryapi.item.ClickableItem;
import me.sedattr.deluxebazaar.managers.BazaarItem;
import me.sedattr.deluxebazaar.others.PlaceholderUtil;
import me.sedattr.deluxebazaar.others.Utils;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

public class SellMenu implements MenuManager {
    private final ConfigurationSection section;
    private final Player player;
    private BazaarItem bazaarItem;

    public SellMenu(Player player) {
        this.section = DeluxeBazaar.getInstance().menusFile.getConfigurationSection("menus.sell");
        this.player = player;
    }

    public void openMenu(String name, String type) {
        if (section == null)
            return;

        String itemName = DeluxeBazaar.getInstance().itemsFile.getString("items." + name + ".name");
        PlaceholderUtil placeholderUtil = new PlaceholderUtil()
                .addPlaceholder("%item_name_colored%", Utils.colorize(itemName))
                .addPlaceholder("%item%", Utils.strip(itemName))
                .addPlaceholder("%item_name%", Utils.strip(itemName));
        HInventory gui = DeluxeBazaar.getInstance().menuHandler.createInventory(this.player, this.section, "sell", placeholderUtil);

        this.bazaarItem = DeluxeBazaar.getInstance().bazaarItems.get(name);
        if (bazaarItem == null)
            return;
        ItemStack exampleItem = bazaarItem.getItemStack();
        if (exampleItem == null)
            return;
        exampleItem = exampleItem.clone();
        Utils.addMenuItemFlags(exampleItem);

        ConfigurationSection itemsSection = this.section.getConfigurationSection("items");

        int emptySlots = DeluxeBazaar.getInstance().itemHandler.getSellableItemCount(player, name);
        List<String> newLore;
        List<String> lore;
        for (String entry : itemsSection.getKeys(false)) {
            String itemType = itemsSection.getString(entry + ".type");
            if (itemType == null)
                continue;

            newLore = new ArrayList<>();
            int amount = Math.max(itemsSection.getInt(entry + ".count"), 1);

            ItemStack item;
            if (itemType.equalsIgnoreCase("custom")) {
                item = Utils.createItemFromSection(itemsSection.getConfigurationSection(entry + "." + type), placeholderUtil);

                lore = itemsSection.getStringList(entry + "." + type + ".lore");
                if (!lore.isEmpty())
                    for (String line : lore)
                        newLore.add(Utils.colorize(line
                                .replace("%empty_slots%", String.valueOf(emptySlots))
                                .replace("%maximum%", String.valueOf(emptySlots))
                                .replace("%item_name%", Utils.strip(itemName))
                                .replace("%item%", Utils.strip(itemName))
                                .replace("%item_name_colored%", Utils.colorize(itemName))));

                Utils.changeLore(item, newLore, placeholderUtil);
            } else
                item = Utils.createItemFromSection(itemsSection.getConfigurationSection(entry), placeholderUtil);

            lore = itemsSection.getStringList(entry + ".lore");
            if (item == null) {
                item = exampleItem.clone();

                if (!lore.isEmpty())
                    for (String line : lore)
                        newLore.add(Utils.colorize(line
                                .replace("%total_amount%", String.valueOf(amount))
                                .replace("%amount%", String.valueOf(amount))
                                .replace("%unit_price%", DeluxeBazaar.getInstance().numberFormat.format(BazaarItemHook.getSellPrice(player, name, 1)))
                                .replace("%total_price%", DeluxeBazaar.getInstance().numberFormat.format(BazaarItemHook.getSellPrice(player, name, amount)))
                                .replace("%item_name%", Utils.strip(itemName))
                                .replace("%item%", Utils.strip(itemName))
                                .replace("%item_name_colored%", Utils.colorize(itemName))));

                item.setAmount(amount);
                Utils.changeLore(item, newLore, placeholderUtil);
                Utils.changeName(item, Utils.colorize(itemsSection.getString(entry + ".name")), placeholderUtil);
            } else if (!itemType.equalsIgnoreCase("custom")) {
                if (!lore.isEmpty())
                    for (String line : lore)
                        newLore.add(Utils.colorize(line
                                .replace("%empty_slots%", String.valueOf(emptySlots))
                                .replace("%total_price%", DeluxeBazaar.getInstance().numberFormat.format(BazaarItemHook.getSellPrice(player, name, emptySlots)))
                                .replace("%unit_price%", DeluxeBazaar.getInstance().numberFormat.format(BazaarItemHook.getSellPrice(player, name, 1)))
                                .replace("%maximum%", String.valueOf(emptySlots))
                                .replace("%item%", Utils.strip(itemName))
                                .replace("%item_name%", Utils.strip(itemName))
                                .replace("%item_name_colored%", Utils.colorize(itemName))));

                Utils.changeLore(item, newLore, placeholderUtil);
            }

            if (itemType.equalsIgnoreCase("fill"))
                item.setAmount(Math.max(emptySlots / item.getMaxStackSize(), 1));

            ConfigurationSection entrySection = itemsSection.getConfigurationSection(entry);
            me.sedattr.deluxebazaar.handlers.MenuHandler.setItemInSlots(gui, entrySection, ClickableItem.of(item, (event) -> {
                if (BazaarItemHook.getDefaultSellPrice(name) <= 0.0) {
                    Utils.sendMessage(this.player, "selling_disabled_for_item", placeholderUtil);
                    return;
                }

                if (emptySlots < 1) {
                    Utils.sendMessage(player, "no_item_found");
                    return;
                }

                if (itemType.equalsIgnoreCase("custom"))
                    DeluxeBazaar.getInstance().inputMenu.open(player, this);
                else {
                    Boolean status;
                    if (itemType.equalsIgnoreCase("all"))
                        status = DeluxeBazaar.getInstance().economyHandler.event(player, "sell", bazaarItem, emptySlots);
                    else
                        status = DeluxeBazaar.getInstance().economyHandler.event(player, "sell", bazaarItem, amount);
                    if (!status)
                        return;

                    if (DeluxeBazaar.getInstance().configFile.getBoolean("settings.go_back_when_sold"))
                        new ItemMenu(player).openMenu(name);
                    else
                        openMenu(name, "default");
                }
            }));
        }

        int goBackSlot = this.section.getInt("back");
        ItemStack goBackItem = DeluxeBazaar.getInstance().normalItems.get("goBack");
        if (goBackSlot > 0 && goBackItem != null)
            gui.setItem(goBackSlot - 1, ClickableItem.of(goBackItem, (event) -> new ItemMenu(player).openMenu(name)));

        gui.open(player);
    }

    @Override
    public void inputResult(String input) {
        int number;
        try {
            number = Integer.parseInt(input);
        } catch (NumberFormatException e) {
            try {
                number = DeluxeBazaar.getInstance().numberFormat.reverseFormat(input).intValue();
            } catch (NumberFormatException a) {
                number = 0;
            }
        }

        int count = DeluxeBazaar.getInstance().itemHandler.getSellableItemCount(this.player, this.bazaarItem.getName());
        if (number <= 0 || number > count) {
            openMenu(this.bazaarItem.getName(), "invalid");
            return;
        }

        new ConfirmMenu(this.player).openMenu(this.bazaarItem.getName(), "sell", number, BazaarItemHook.getSellPrice(player, this.bazaarItem.getName(), number)/number);
    }
}