package me.sedattr.deluxebazaar.menus;

import me.sedattr.bazaarapi.BazaarItemHook;
import me.sedattr.deluxebazaar.DeluxeBazaar;
import me.sedattr.deluxebazaar.inventoryapi.HInventory;
import me.sedattr.deluxebazaar.inventoryapi.item.ClickableItem;
import me.sedattr.deluxebazaar.managers.BazaarItem;
import me.sedattr.deluxebazaar.others.PlaceholderUtil;
import me.sedattr.deluxebazaar.others.Utils;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

public class BuyOrderMenu implements MenuManager {
    private final ConfigurationSection section;
    private final Player player;
    private String name;
    private String input = "-";
    private BazaarItem bazaarItem;

    public BuyOrderMenu(Player player) {
        this.section = DeluxeBazaar.getInstance().menusFile.getConfigurationSection("menus.buyOrder");
        this.player = player;
    }

    public void openMenu(String name, String type) {
        if (section == null)
            return;
        this.name = name;
        this.bazaarItem = DeluxeBazaar.getInstance().bazaarItems.get(name);
        if (bazaarItem == null)
            return;

        ItemStack exampleItem = bazaarItem.getItemStack();
        if (exampleItem == null)
            return;

        String itemName = DeluxeBazaar.getInstance().itemsFile.getString("items." + name + ".name");
        PlaceholderUtil placeholderUtil = new PlaceholderUtil()
                .addPlaceholder("%item_name_colored%", Utils.colorize(itemName))
                .addPlaceholder("%item%", Utils.strip(itemName))
                .addPlaceholder("%item_name%", Utils.strip(itemName));

        HInventory gui = DeluxeBazaar.getInstance().menuHandler.createInventory(player, this.section, "buyOrder", placeholderUtil);
        ConfigurationSection itemsSection = this.section.getConfigurationSection("items");

        int maximum = DeluxeBazaar.getInstance().configFile.getInt("buy_order.maximum_item_amount");
        placeholderUtil
                .addPlaceholder("%maximum%", String.valueOf(maximum))
                .addPlaceholder("%maximum_amount%", String.valueOf(maximum));

        for (String entry : itemsSection.getKeys(false)) {
            ConfigurationSection itemSection = itemsSection.getConfigurationSection(entry);
            String itemType = itemSection.getString("type");
            if (itemType == null)
                continue;

            int amount = Math.max(itemSection.getInt("amount"), 1);
            ItemStack item;
            if (itemType.equalsIgnoreCase("custom")) {
                item = Utils.createItemFromSection(itemSection.getConfigurationSection(type), placeholderUtil);
                if (type.equalsIgnoreCase("invalid") && this.input != null && !this.input.equalsIgnoreCase(""))
                    placeholderUtil.addPlaceholder("%input%", this.input);

                Utils.changeLore(item, itemSection.getStringList(type + ".lore"), placeholderUtil
                        .addPlaceholder("%total_amount%", String.valueOf(amount))
                        .addPlaceholder("%amount%", String.valueOf(amount)));
            } else {
                item = Utils.createItemFromSection(itemsSection.getConfigurationSection(entry), placeholderUtil);
                if (item == null) {
                    item = exampleItem.clone();
                    item.setAmount(amount);

                    Utils.changeName(item, Utils.colorize(itemSection.getString("name")), placeholderUtil);
                }

                Utils.changeLore(item, itemSection.getStringList("lore"), placeholderUtil
                        .addPlaceholder("%total_amount%", String.valueOf(amount))
                        .addPlaceholder("%amount%", String.valueOf(amount)));
            }

            int count = itemSection.getInt("count");
            gui.setItem(itemSection.getInt("slot") - 1, ClickableItem.of(item, (event) -> {
                if (itemType.equalsIgnoreCase("custom"))
                    DeluxeBazaar.getInstance().inputMenu.open(player, this);
                else {
                    int itemAmount = count > 0 ? count : amount;

                    ConfigurationSection stockSection = DeluxeBazaar.getInstance().orderHandler.getStockConfiguration(name);
                    if (stockSection != null && stockSection.getBoolean("only_orders", false)) {
                        int maximumStock = stockSection.getInt("max_stock", 0);
                        if (maximumStock <= 0) {
                            if (bazaarItem.getCategory() != null && !bazaarItem.getCategory().isEmpty()) {
                                int maximumCategoryStock = DeluxeBazaar.getInstance().configFile.getInt("item_stock_system.item_list." + bazaarItem.getCategory() + ".max_stock", 0);
                                if (maximumCategoryStock > 0)
                                    maximumStock = maximumCategoryStock;
                            }
                        }

                        if (maximumStock > 0) {
                            int stockCount = DeluxeBazaar.getInstance().orderHandler.getStockCount(null, name, "sell");

                            if ((stockCount + itemAmount) > maximumStock) {
                                Utils.sendMessage(player, "exceeds_maximum_stock", new PlaceholderUtil()
                                        .addPlaceholder("%left_stock_amount%", String.valueOf(maximumStock - stockCount))
                                        .addPlaceholder("%current_stock_amount%", String.valueOf(stockCount))
                                        .addPlaceholder("%maximum_stock_amount%", String.valueOf(maximumStock)));
                                return;
                            }
                        }
                    }

                    new SetBuyOrderMenu(player).openMenu(itemAmount, name, "default");
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

        this.input = "-";

        int maximum = DeluxeBazaar.getInstance().configFile.getInt("buy_order.maximum_item_amount");
        if (number <= 0 || number > maximum) {
            if (input != null && !input.equalsIgnoreCase(""))
                this.input = input;
            openMenu(name, "invalid");
            return;
        }

        ConfigurationSection stockSection = DeluxeBazaar.getInstance().orderHandler.getStockConfiguration(name);
        if (stockSection != null && stockSection.getBoolean("only_orders", false)) {
            int maximumStock = stockSection.getInt("max_stock", 0);
            if (maximumStock <= 0) {
                if (bazaarItem.getCategory() != null && !bazaarItem.getCategory().isEmpty()) {
                    int maximumCategoryStock = DeluxeBazaar.getInstance().configFile.getInt("item_stock_system.item_list." + bazaarItem.getCategory() + ".max_stock", 0);
                    if (maximumCategoryStock > 0)
                        maximumStock = maximumCategoryStock;
                }
            }

            if (maximumStock > 0) {
                int stockCount = DeluxeBazaar.getInstance().orderHandler.getStockCount(null, name, "sell");

                if ((stockCount + number) > maximumStock) {
                    Utils.sendMessage(player, "exceeds_maximum_stock", new PlaceholderUtil()
                            .addPlaceholder("%left_stock_amount%", String.valueOf(maximumStock - stockCount))
                            .addPlaceholder("%current_stock_amount%", String.valueOf(stockCount))
                            .addPlaceholder("%maximum_stock_amount%", String.valueOf(maximumStock)));

                    openMenu(name, "invalid");
                    return;
                }
            }
        }

        new SetBuyOrderMenu(player).openMenu(number, name, "default");
    }
}