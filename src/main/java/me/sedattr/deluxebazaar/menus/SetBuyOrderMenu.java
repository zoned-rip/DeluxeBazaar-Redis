package me.sedattr.deluxebazaar.menus;

import me.sedattr.deluxebazaar.DeluxeBazaar;
import me.sedattr.bazaarapi.BazaarItemHook;
import me.sedattr.deluxebazaar.inventoryapi.HInventory;
import me.sedattr.deluxebazaar.inventoryapi.item.ClickableItem;
import me.sedattr.deluxebazaar.managers.BazaarItem;
import me.sedattr.deluxebazaar.managers.OrderPrice;
import me.sedattr.deluxebazaar.others.PlaceholderUtil;
import me.sedattr.deluxebazaar.others.Utils;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

public class SetBuyOrderMenu implements MenuManager {
    private final ConfigurationSection section;
    private final Player player;
    private String name;
    private int amount;

    public SetBuyOrderMenu(Player player) {
        this.section = DeluxeBazaar.getInstance().menusFile.getConfigurationSection("menus.setBuyOrder");
        this.player = player;
    }

    public void openMenu(Integer count, String name, String type) {
        if (section == null)
            return;
        this.amount = count;
        this.name = name;

        String itemName = DeluxeBazaar.getInstance().itemsFile.getString("items." + name + ".name");
        PlaceholderUtil placeholderUtil = new PlaceholderUtil()
                .addPlaceholder("%item_name_colored%", Utils.colorize(itemName))
                .addPlaceholder("%item%", Utils.strip(itemName))
                .addPlaceholder("%item_name%", Utils.strip(itemName));
        HInventory gui = DeluxeBazaar.getInstance().menuHandler.createInventory(this.player, this.section, "setBuyOrder", placeholderUtil);

        BazaarItem bazaarItem = DeluxeBazaar.getInstance().bazaarItems.get(name);
        if (bazaarItem == null)
            return;
        ItemStack exampleItem = bazaarItem.getItemStack();
        if (exampleItem == null)
            return;

        double buyPrice = BazaarItemHook.getBuyPrice(null, name, 1);
        double sellPrice = BazaarItemHook.getSellPrice(null, name, 1);

        placeholderUtil
                .addPlaceholder("%buyprice%", DeluxeBazaar.getInstance().numberFormat.format(buyPrice))
                .addPlaceholder("%sellprice%", DeluxeBazaar.getInstance().numberFormat.format(sellPrice))
                .addPlaceholder("%buy_price%", DeluxeBazaar.getInstance().numberFormat.format(buyPrice))
                .addPlaceholder("%sell_price%", DeluxeBazaar.getInstance().numberFormat.format(sellPrice));

        ConfigurationSection itemsSection = this.section.getConfigurationSection("items");
        double unitPrice;
        for (String entry : itemsSection.getKeys(false)) {
            ConfigurationSection itemSection = itemsSection.getConfigurationSection(entry);
            String itemType = itemSection.getString("type");
            if (itemType == null)
                continue;

            switch (itemType) {
                case "top":
                    unitPrice = sellPrice + itemSection.getDouble("price");
                    break;
                case "spread":
                    double spread = buyPrice - sellPrice;
                    double totalSpread = (spread / 100) * itemSection.getDouble("spread");
                    unitPrice = sellPrice + totalSpread;

                    placeholderUtil
                            .addPlaceholder("%spread_amount%", DeluxeBazaar.getInstance().numberFormat.format(spread))
                            .addPlaceholder("%spread%", DeluxeBazaar.getInstance().numberFormat.format(spread));
                    break;
                default:
                    unitPrice = sellPrice;
                    break;
            }

            int amount = Math.max(itemSection.getInt("amount"), 1);
            ItemStack item;
            if (itemType.equalsIgnoreCase("custom")) {
                item = Utils.createItemFromSection(itemSection.getConfigurationSection(type), placeholderUtil);

                List<String> lore = new ArrayList<>();
                if (type.equalsIgnoreCase("invalid")) {
                    lore = itemSection.getStringList(type + ".lore");
                } else {
                    ConfigurationSection loreSection = itemSection.getConfigurationSection(type + ".lore");
                    List<OrderPrice> buyOrders = bazaarItem.getBuyPrices().stream().sorted(Comparator.comparingDouble(OrderPrice::getPrice).reversed()).collect(Collectors.toList());
                    if (buyOrders.size() > 0) {
                        lore.addAll(loreSection.getStringList("header"));

                        for (OrderPrice order : buyOrders) {
                            lore.add(loreSection.getString("order")
                                    .replace("%count%", String.valueOf(order.getItemAmount()))
                                    .replace("%order%", String.valueOf(order.getOrderAmount()))
                                    .replace("%price%", DeluxeBazaar.getInstance().numberFormat.format(order.getPrice()))

                                    .replace("%item_amount%", String.valueOf(order.getItemAmount()))
                                    .replace("%order_amount%", String.valueOf(order.getOrderAmount()))
                                    .replace("%order_price%", DeluxeBazaar.getInstance().numberFormat.format(order.getPrice())));
                        }

                        lore.addAll(loreSection.getStringList("footer"));
                    } else
                        lore.addAll(loreSection.getStringList("nothing"));
                }

                Utils.changeLore(item, lore, placeholderUtil.addPlaceholder("%total_amount%", String.valueOf(amount)));
            } else {
                item = Utils.createItemFromSection(itemsSection.getConfigurationSection(entry), placeholderUtil);
                if (item == null) {
                    item = exampleItem.clone();
                    item.setAmount(amount);

                    Utils.changeName(item, Utils.colorize(itemSection.getString("name")), placeholderUtil);
                }

                Utils.changeLore(item, itemSection.getStringList("lore"), placeholderUtil
                        .addPlaceholder("%amount%", String.valueOf(count))
                        .addPlaceholder("%price%", DeluxeBazaar.getInstance().numberFormat.format(unitPrice * count))
                        .addPlaceholder("%unitprice%", DeluxeBazaar.getInstance().numberFormat.format(unitPrice))

                        .addPlaceholder("%total_price%", DeluxeBazaar.getInstance().numberFormat.format(unitPrice * count))
                        .addPlaceholder("%total_amount%", String.valueOf(count))
                        .addPlaceholder("%unit_price%", DeluxeBazaar.getInstance().numberFormat.format(unitPrice)));
            }

            double finalUnitPrice = unitPrice;
            ClickableItem clickableItem = ClickableItem.of(item, (event) -> {
                if (itemType.equalsIgnoreCase("custom"))
                    DeluxeBazaar.getInstance().inputMenu.open(player, this);
                else
                    new ConfirmMenu(player).openMenu(name, "buyOrder", count, finalUnitPrice);
            });
            me.sedattr.deluxebazaar.handlers.MenuHandler.setItemInSlots(gui, itemSection, clickableItem);
        }

        int goBackSlot = this.section.getInt("back");
        ItemStack goBackItem = DeluxeBazaar.getInstance().normalItems.get("goBack");
        if (goBackSlot > 0 && goBackItem != null)
            gui.setItem(goBackSlot - 1, ClickableItem.of(goBackItem, (event) -> new BuyOrderMenu(player).openMenu(name, "default")));

        gui.open(player);
    }

    @Override
    public void inputResult(String input) {
        double number;
        try {
            number = Double.parseDouble(input);
        } catch (NumberFormatException e) {
            try {
                number = DeluxeBazaar.getInstance().numberFormat.reverseFormat(input);
            } catch (NumberFormatException a) {
                number = 0;
            }
        }

        if (number <= 0) {
            openMenu(this.amount, this.name, "invalid");
            return;
        }

        new ConfirmMenu(player).openMenu(this.name, "buyOrder", this.amount, number);
    }
}
