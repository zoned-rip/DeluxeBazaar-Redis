package me.sedattr.deluxebazaar.menus;

import me.sedattr.deluxebazaar.DeluxeBazaar;
import me.sedattr.bazaarapi.BazaarItemHook;
import me.sedattr.deluxebazaar.handlers.MenuHandler;
import me.sedattr.deluxebazaar.inventoryapi.HInventory;
import me.sedattr.deluxebazaar.inventoryapi.item.ClickableItem;
import me.sedattr.deluxebazaar.managers.BazaarItem;
import me.sedattr.deluxebazaar.managers.OrderPrice;
import me.sedattr.deluxebazaar.managers.PlayerBazaar;
import me.sedattr.deluxebazaar.others.PlaceholderUtil;
import me.sedattr.deluxebazaar.others.Utils;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

public class ItemMenu {
    public final ConfigurationSection section;
    public final Player player;

    public ItemMenu(Player player) {
        this.section = DeluxeBazaar.getInstance().menusFile.getConfigurationSection("menus.item");
        this.player = player;
    }

    public void openMenu(String name) {
        if (section == null)
            return;

        String itemName = DeluxeBazaar.getInstance().itemsFile.getString("items." + name + ".name");
        String mainItem = itemName;

        BazaarItem bazaarItem = DeluxeBazaar.getInstance().bazaarItems.get(name);
        if (bazaarItem == null)
            return;

        if (bazaarItem.getSubcategory() != null) {
            ConfigurationSection subcategorySection = DeluxeBazaar.getInstance().itemsFile.getConfigurationSection("groups." + bazaarItem.getSubcategory());
            if (subcategorySection != null) {
                String mainItemName = subcategorySection.getString("main_item");
                if (mainItemName != null)
                    mainItem = DeluxeBazaar.getInstance().itemsFile.getString("items." + mainItemName + ".name", itemName);
            }
        }

        PlayerBazaar playerBazaar = DeluxeBazaar.getInstance().players.getOrDefault(player.getUniqueId(), new PlayerBazaar(player));
        String categoryName = DeluxeBazaar.getInstance().categoriesFile.getString(playerBazaar.getCategory() + ".name");

        double buyPrice = BazaarItemHook.getBuyPrice(player, name, 1);
        double sellPrice = BazaarItemHook.getSellPrice(player, name, 1);
        PlaceholderUtil placeholderUtil = new PlaceholderUtil()
                .addPlaceholder("%category_name%", Utils.strip(categoryName))
                .addPlaceholder("%category_name_colored%", Utils.colorize(categoryName))
                .addPlaceholder("%main_item_name%", Utils.strip(mainItem))
                .addPlaceholder("%main_item_name_colored%", Utils.colorize(mainItem))
                .addPlaceholder("%mainitem%", Utils.strip(mainItem))
                .addPlaceholder("%buy_price%", DeluxeBazaar.getInstance().numberFormat.format(buyPrice))
                .addPlaceholder("%price%", DeluxeBazaar.getInstance().numberFormat.format(buyPrice))
                .addPlaceholder("%buyprice%", DeluxeBazaar.getInstance().numberFormat.format(buyPrice))
                .addPlaceholder("%sell_price%", DeluxeBazaar.getInstance().numberFormat.format(sellPrice))
                .addPlaceholder("%sellprice%", DeluxeBazaar.getInstance().numberFormat.format(sellPrice))
                .addPlaceholder("%buy_amount%", String.valueOf(BazaarItemHook.getBuyCount(name)))
                .addPlaceholder("%buycount%", String.valueOf(BazaarItemHook.getBuyCount(name)))
                .addPlaceholder("%sell_amount%", String.valueOf(BazaarItemHook.getSellCount(name)))
                .addPlaceholder("%sellcount%", String.valueOf(BazaarItemHook.getSellCount(name)))
                .addPlaceholder("%item_name_colored%", Utils.colorize(itemName))
                .addPlaceholder("%item%", Utils.strip(itemName))
                .addPlaceholder("%item_name%", Utils.strip(itemName));

        HInventory gui = DeluxeBazaar.getInstance().menuHandler.createInventory(player, this.section, name, placeholderUtil);
        ItemStack exampleItem = bazaarItem.getItemStack();
        if (exampleItem != null) {
            exampleItem = exampleItem.clone();

            gui.setItem(this.section.getInt("example") - 1, ClickableItem.empty(exampleItem));
        }

        placeholderUtil
                .addPlaceholder("%item_maximum_stack_amount%", String.valueOf(exampleItem.getMaxStackSize()))
                .addPlaceholder("%buy_price_64%", DeluxeBazaar.getInstance().numberFormat.format(buyPrice*64))
                .addPlaceholder("%sell_price_64%", DeluxeBazaar.getInstance().numberFormat.format(sellPrice*64))
                .addPlaceholder("%stackprice%", DeluxeBazaar.getInstance().numberFormat.format(buyPrice * exampleItem.getMaxStackSize()))
                .addPlaceholder("%buy_price_stack%", DeluxeBazaar.getInstance().numberFormat.format(buyPrice * exampleItem.getMaxStackSize()))
                .addPlaceholder("%sell_price_stack%", DeluxeBazaar.getInstance().numberFormat.format(sellPrice * exampleItem.getMaxStackSize()));

        ItemStack buy = Utils.createItemFromSection(this.section.getConfigurationSection("buy"), placeholderUtil);
        if (buy != null) {
            ConfigurationSection buySection = this.section.getConfigurationSection("buy");
            MenuHandler.setItemInSlots(gui, buySection, ClickableItem.of(buy, (event) -> {
                if (BazaarItemHook.getDefaultBuyPrice(name) <= 0.0) {
                    Utils.sendMessage(player, "buying_disabled_for_item", placeholderUtil);
                    return;
                }

                new BuyMenu(player).openMenu(name, "default");
            }));
        }

        ItemStack sell = Utils.createItemFromSection(this.section.getConfigurationSection("sell"), placeholderUtil);
        if (sell != null) {
            List<String> sellLore = new ArrayList<>();

            int count = DeluxeBazaar.getInstance().itemHandler.getSellableItemCount(player, name);
            Double price = sellPrice * Math.max(count, 1);
            if (count > 0) {
                this.section.getStringList("sell.lore.sell").forEach((string) -> sellLore.add(Utils.colorize(string
                        .replace("%total_amount%", String.valueOf(count))
                        .replace("%count%", String.valueOf(count))
                        .replace("%price%", DeluxeBazaar.getInstance().numberFormat.format(price))
                        .replace("%total_price%", DeluxeBazaar.getInstance().numberFormat.format(price)))));

                Utils.changeLore(sell, sellLore, placeholderUtil);
                ConfigurationSection sellSection = this.section.getConfigurationSection("sell");
                MenuHandler.setItemInSlots(gui, sellSection, ClickableItem.of(sell, (event) -> {
                    if (BazaarItemHook.getDefaultSellPrice(name) <= 0.0) {
                        Utils.sendMessage(player, "selling_disabled_for_item", placeholderUtil);
                        return;
                    }

                    new SellMenu(player).openMenu(name, "default");
                }));
            } else {
                this.section.getStringList("sell.lore.none").forEach((string) -> sellLore.add(Utils.colorize(string
                        .replace("%unit_price%", DeluxeBazaar.getInstance().numberFormat.format(price))
                        .replace("%price%", DeluxeBazaar.getInstance().numberFormat.format(price)))));

                Utils.changeLore(sell, sellLore, placeholderUtil);
                ConfigurationSection sellSection = this.section.getConfigurationSection("sell");
                MenuHandler.setItemInSlots(gui, sellSection, ClickableItem.empty(sell));
            }
        }

        ItemStack buyOrder = Utils.createItemFromSection(this.section.getConfigurationSection("buyOrder"), null);
        if (buyOrder != null) {
            ConfigurationSection loreSection = this.section.getConfigurationSection("buyOrder.lore");
            List<OrderPrice> buyOrders = bazaarItem.getBuyPrices().stream().sorted(Comparator.comparingDouble(OrderPrice::getPrice).reversed()).collect(Collectors.toList());
            List<String> lore = new ArrayList<>();
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

            Utils.changeLore(buyOrder, lore, placeholderUtil);

            ConfigurationSection buyOrderSection = this.section.getConfigurationSection("buyOrder");
            MenuHandler.setItemInSlots(gui, buyOrderSection, ClickableItem.of(buyOrder, (event) -> {
                if (BazaarItemHook.getDefaultSellPrice(name) <= 0.0) {
                    Utils.sendMessage(player, "selling_disabled_for_item", placeholderUtil);
                    return;
                }

                new BuyOrderMenu(player).openMenu(name, "default");
            }));
        }

        ItemStack sellOffer = Utils.createItemFromSection(this.section.getConfigurationSection("sellOffer"), null);
        if (sellOffer != null) {
            ConfigurationSection loreSection = this.section.getConfigurationSection("sellOffer.lore");
            List<OrderPrice> sellOffers = bazaarItem.getSellPrices().stream().sorted(Comparator.comparingDouble(OrderPrice::getPrice)).collect(Collectors.toList());
            
            boolean itemMenuDebug = DeluxeBazaar.getInstance().configFile.getBoolean("settings.debug.item_menu", false);
            if (itemMenuDebug) {
                me.sedattr.deluxebazaar.others.Logger.sendConsoleMessage("§e[DEBUG] ItemMenu: Opening menu for " + name + ", sellOffers count=" + sellOffers.size(), me.sedattr.deluxebazaar.others.Logger.LogLevel.INFO);
                me.sedattr.deluxebazaar.others.Logger.sendConsoleMessage("§e[DEBUG] ItemMenu: bazaarItem.getSellPrices() size=" + bazaarItem.getSellPrices().size(), me.sedattr.deluxebazaar.others.Logger.LogLevel.INFO);
                for (OrderPrice order : sellOffers) {
                    me.sedattr.deluxebazaar.others.Logger.sendConsoleMessage("§e[DEBUG] ItemMenu: SellOffer - price=" + order.getPrice() + ", itemAmount=" + order.getItemAmount() + ", orderAmount=" + order.getOrderAmount(), me.sedattr.deluxebazaar.others.Logger.LogLevel.INFO);
                }
            }
            
            List<String> lore = new ArrayList<>();
            if (sellOffers.size() > 0) {
                lore.addAll(loreSection.getStringList("header"));

                for (OrderPrice order : sellOffers) {
                    lore.add(loreSection.getString("offer")
                            .replace("%count%", String.valueOf(order.getItemAmount()))
                            .replace("%offer%", String.valueOf(order.getOrderAmount()))
                            .replace("%price%", DeluxeBazaar.getInstance().numberFormat.format(order.getPrice()))

                            .replace("%item_amount%", String.valueOf(order.getItemAmount()))
                            .replace("%order_amount%", String.valueOf(order.getOrderAmount()))
                            .replace("%order_price%", DeluxeBazaar.getInstance().numberFormat.format(order.getPrice())));
                }

                lore.addAll(loreSection.getStringList("footer"));
            } else
                lore.addAll(loreSection.getStringList("nothing"));

            Utils.changeLore(sellOffer, lore, placeholderUtil);

            ConfigurationSection sellOfferSection = this.section.getConfigurationSection("sellOffer");
            MenuHandler.setItemInSlots(gui, sellOfferSection, ClickableItem.of(sellOffer, (event) -> {
                if (BazaarItemHook.getDefaultBuyPrice(name) <= 0.0) {
                    Utils.sendMessage(player, "buying_disabled_for_item", placeholderUtil);
                    return;
                }

                new SellOfferMenu(player).openMenu(name, "default");
            }));
        }

        ItemStack stats = Utils.createItemFromSection(this.section.getConfigurationSection("stats"), placeholderUtil);
        if (stats != null) {
            ConfigurationSection statsSection = this.section.getConfigurationSection("stats");
            MenuHandler.setItemInSlots(gui, statsSection, ClickableItem.empty(stats));
        }

        int goBackSlot = this.section.getInt("back");
        ItemStack goBackItem = DeluxeBazaar.getInstance().normalItems.get("goBack");
        if (goBackSlot > 0 && goBackItem != null)
            gui.setItem(goBackSlot - 1, ClickableItem.of(goBackItem, (event) -> {
                if (bazaarItem.getSubcategory() != null)
                    new SubcategoryMenu(player).openMenu(bazaarItem.getSubcategory());
                else
                    new MainMenu(player).openMenu(playerBazaar.getCategory(), 1);
            }));

        gui.open(player);
    }
}
