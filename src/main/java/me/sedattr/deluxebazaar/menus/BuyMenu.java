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

public class BuyMenu implements MenuManager {
    private final ConfigurationSection section;

    private final Player player;
    private BazaarItem bazaarItem;

    public BuyMenu(Player player) {
        this.section = DeluxeBazaar.getInstance().menusFile.getConfigurationSection("menus.buy");
        this.player = player;
    }

    public void openMenu(String name, String type) {
        if (this.section == null)
            return;

        String itemName = DeluxeBazaar.getInstance().itemsFile.getString("items." + name + ".name");
        PlaceholderUtil placeholderUtil = new PlaceholderUtil()
                .addPlaceholder("%item_name_colored%", Utils.colorize(itemName))
                .addPlaceholder("%item%", Utils.strip(itemName))
                .addPlaceholder("%item_name%", Utils.strip(itemName));

        HInventory gui = DeluxeBazaar.getInstance().menuHandler.createInventory(this.player, this.section, "buy", placeholderUtil);

        this.bazaarItem = DeluxeBazaar.getInstance().bazaarItems.get(name);
        if (bazaarItem == null)
            return;
        ItemStack exampleItem = bazaarItem.getItemStack();
        if (exampleItem == null)
            return;
        exampleItem = exampleItem.clone();

        ConfigurationSection itemsSection = this.section.getConfigurationSection("items");
        if (itemsSection == null)
            return;

        int emptySlots = DeluxeBazaar.getInstance().itemHandler.getEmptySlots(player, exampleItem);
        List<String> newLore;
        List<String> lore;

        Utils.addMenuItemFlags(exampleItem);
        for (String entry : itemsSection.getKeys(false)) {
            String itemType = itemsSection.getString(entry + ".type");
            if (itemType == null)
                continue;

            newLore = new ArrayList<>();
            int amount = Math.max(itemsSection.getInt(entry + ".amount"), 1);

            ItemStack item;
            if (itemType.equalsIgnoreCase("custom")) {
                item = Utils.createItemFromSection(itemsSection.getConfigurationSection(entry + "." + type), null);

                lore = itemsSection.getStringList(entry + "." + type + ".lore");
                if (!lore.isEmpty())
                    for (String line : lore)
                        newLore.add(Utils.colorize(line
                                .replace("%maximum%", String.valueOf(emptySlots))
                                .replace("%empty_slots%", String.valueOf(emptySlots))
                                .replace("%item_name%", Utils.strip(itemName))
                                .replace("%item%", Utils.strip(itemName))
                                .replace("%item_name_colored%", Utils.colorize(itemName))));

                Utils.changeLore(item, newLore, null);
            } else
                item = Utils.createItemFromSection(itemsSection.getConfigurationSection(entry), null);

            lore = itemsSection.getStringList(entry + ".lore");
            if (item == null) {
                item = exampleItem.clone();

                if (!lore.isEmpty())
                    for (String line : lore)
                        newLore.add(Utils.colorize(line
                                .replace("%total_amount%", String.valueOf(amount))
                                .replace("%amount%", String.valueOf(amount))
                                .replace("%unit_price%", DeluxeBazaar.getInstance().numberFormat.format(BazaarItemHook.getBuyPrice(player, name, 1)))
                                .replace("%total_price%", DeluxeBazaar.getInstance().numberFormat.format(BazaarItemHook.getBuyPrice(player, name, amount)))
                                .replace("%item_name%", Utils.strip(itemName))
                                .replace("%item%", Utils.strip(itemName))
                                .replace("%item_name_colored%", Utils.colorize(itemName))));

                item.setAmount(amount);
                Utils.changeLore(item, newLore, null);
                Utils.changeName(item, Utils.colorize(itemsSection.getString(entry + ".name")), null);
            } else if (!itemType.equalsIgnoreCase("custom")) {
                if (!lore.isEmpty())
                    for (String line : lore)
                        newLore.add(Utils.colorize(line
                                .replace("%empty_slots%", String.valueOf(emptySlots))
                                .replace("%maximum%", String.valueOf(emptySlots))
                                .replace("%total_price%", DeluxeBazaar.getInstance().numberFormat.format(BazaarItemHook.getBuyPrice(player, name, emptySlots)))
                                .replace("%unit_price%", DeluxeBazaar.getInstance().numberFormat.format(BazaarItemHook.getBuyPrice(player, name, 1)))
                                .replace("%item_name%", Utils.strip(itemName))
                                .replace("%item%", Utils.strip(itemName))
                                .replace("%item_name_colored%", Utils.colorize(itemName))));

                Utils.changeLore(item, newLore, null);
            }

            if (itemType.equalsIgnoreCase("fill"))
                item.setAmount(Math.max(emptySlots / exampleItem.getMaxStackSize(), 1));

            gui.setItem(itemsSection.getInt(entry + ".slot") - 1, ClickableItem.of(item, (event) -> {
                if (BazaarItemHook.getDefaultBuyPrice(name) <= 0.0) {
                    Utils.sendMessage(this.player, "buying_disabled_for_item", placeholderUtil);
                    return;
                }

                if (emptySlots < 1) {
                    Utils.sendMessage(player, "no_empty_slot");
                    return;
                }

                if (itemType.equalsIgnoreCase("custom"))
                    DeluxeBazaar.getInstance().inputMenu.open(player, this);
                else {
                    Boolean status;
                    if (itemType.equalsIgnoreCase("fill"))
                        status = DeluxeBazaar.getInstance().economyHandler.event(player, "buy", bazaarItem, emptySlots);
                    else
                        status = DeluxeBazaar.getInstance().economyHandler.event(player, "buy", bazaarItem, amount);
                    if (!status)
                        return;

                    if (DeluxeBazaar.getInstance().configFile.getBoolean("settings.go_back_when_bought"))
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

        int emptySlots = DeluxeBazaar.getInstance().itemHandler.getEmptySlots(this.player, this.bazaarItem.getItemStack());
        if (number <= 0 || number > emptySlots) {
            openMenu(this.bazaarItem.getName(), "invalid");
            return;
        }

        new ConfirmMenu(player).openMenu(this.bazaarItem.getName(), "buy", number, BazaarItemHook.getBuyPrice(player, this.bazaarItem.getName(), number)/number);
    }
}