package me.sedattr.deluxebazaar.menus;

import me.sedattr.deluxebazaar.DeluxeBazaar;
import me.sedattr.bazaarapi.BazaarItemHook;
import me.sedattr.deluxebazaar.inventoryapi.HInventory;
import me.sedattr.deluxebazaar.inventoryapi.item.ClickableItem;
import me.sedattr.deluxebazaar.managers.BazaarItem;
import me.sedattr.deluxebazaar.managers.OrderType;
import me.sedattr.deluxebazaar.managers.PlayerBazaar;
import me.sedattr.deluxebazaar.managers.PlayerOrder;
import me.sedattr.deluxebazaar.others.PlaceholderUtil;
import me.sedattr.deluxebazaar.others.Utils;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class SubcategoryMenu {
    private final ConfigurationSection section;
    private final Player player;
    private HInventory gui;
    private ConfigurationSection subcategorySection;
    private String oldSubCategory;

    public SubcategoryMenu(Player player) {
        this.section = DeluxeBazaar.getInstance().menusFile.getConfigurationSection("menus.items");
        this.player = player;
    }

    public void openMenu(String name) {
        PlayerBazaar playerBazaar = DeluxeBazaar.getInstance().players.getOrDefault(player.getUniqueId(), new PlayerBazaar(player));

        this.subcategorySection = DeluxeBazaar.getInstance().itemsFile.getConfigurationSection("groups." + name);
        if (this.subcategorySection == null)
            return;

        String itemName = this.subcategorySection.getString("item.name");
        if (itemName == null)
            itemName = DeluxeBazaar.getInstance().itemsFile.getString("items." + this.subcategorySection.getString("main_item") + ".name");

        PlaceholderUtil placeholderUtil = new PlaceholderUtil()
                .addPlaceholder("%item_name%", Utils.strip(itemName))
                .addPlaceholder("%item_name_colored%", Utils.colorize(itemName))
                .addPlaceholder("%category_name%", Utils.strip(DeluxeBazaar.getInstance().categoriesFile.getString(playerBazaar.getCategory() + ".name")))
                .addPlaceholder("%category_name_colored%", Utils.colorize(DeluxeBazaar.getInstance().categoriesFile.getString(playerBazaar.getCategory() + ".name")));

        this.gui = DeluxeBazaar.getInstance().menuHandler.createInventory(this.player, this.section, "subcategory", placeholderUtil);

        int goBackSlot = this.section.getInt("back");
        ItemStack goBackItem = DeluxeBazaar.getInstance().normalItems.get("goBack");
        if (goBackSlot > 0 && goBackItem != null)
            this.gui.setItem(goBackSlot - 1, ClickableItem.of(goBackItem, (event) -> {
                if (oldSubCategory != null) {
                    openMenu(oldSubCategory);
                    oldSubCategory = null;
                } else
                    new MainMenu(player).openMenu(playerBazaar.getCategory(), 1);

            }));

        loadItems();
        loadSellItem();
        loadManageItem();

        this.gui.open(player);
    }

    public void loadManageItem() {
        ItemStack manage = Utils.createItemFromSection(this.section.getConfigurationSection("manage"), null);
        if (manage == null)
            return;

        List<String> items = this.subcategorySection.getStringList("item_list");
        if (items.isEmpty())
            return;

        ConfigurationSection manageSection = this.section.getConfigurationSection("manage");
        List<String> lore = new ArrayList<>();

        PlayerBazaar playerBazaar = DeluxeBazaar.getInstance().players.getOrDefault(player.getUniqueId(), new PlayerBazaar(player));
        List<PlayerOrder> orders = new ArrayList<>();
        orders.addAll(playerBazaar.getBuyOrders());
        orders.addAll(playerBazaar.getSellOffers());

        if (orders.isEmpty()) {
            Utils.changeLore(manage, manageSection.getStringList("lore.none"), new PlaceholderUtil()
                    .addPlaceholder("%order_amount%", "0")
                    .addPlaceholder("%other_order_amount%", "0"));
            gui.setItem(this.section.getInt("manage.slot") - 1, ClickableItem.empty(manage));
        } else {
            int totalCount = 0;
            double totalPrice = 0.0;

            int collectableItems = 0;
            double collectableMoney = 0.0;

            int orderAmount = 0;
            List<String> itemNames = new ArrayList<>();
            items.forEach(i -> itemNames.add(i.split("[:]", 2)[0]));

            for (PlayerOrder order : orders) {
                if (!itemNames.contains(order.getItem().getName()))
                    continue;

                totalCount += order.getAmount();
                totalPrice += order.getPrice() * order.getAmount();

                if (order.getType().equals(OrderType.BUY))
                    collectableItems += order.getFilled() - order.getCollected();
                else
                    collectableMoney += order.getPrice() * (order.getFilled() - order.getCollected());
                orderAmount++;
            }
            if (orderAmount > 0) {
                int finalOrderAmount = orderAmount;
                if (collectableItems > 0 || collectableMoney > 0) {
                    int finalCollectableItems = collectableItems;
                    double finalCollectableMoney = collectableMoney;

                    manageSection.getStringList("lore.collectable").forEach((string) -> lore.add(Utils.colorize(string
                            .replace("%order%", String.valueOf(finalOrderAmount))
                            .replace("%price%", DeluxeBazaar.getInstance().numberFormat.format(finalCollectableMoney))
                            .replace("%count%", String.valueOf(finalCollectableItems))

                            .replace("%order_amount%", String.valueOf(finalOrderAmount))
                            .replace("%other_order_amount%", String.valueOf(orders.size() - finalOrderAmount))
                            .replace("%collectable_coins%", DeluxeBazaar.getInstance().numberFormat.format(finalCollectableMoney))
                            .replace("%collectable_items%", String.valueOf(finalCollectableItems)))));
                } else if (collectableMoney < 1) {
                    double finalTotalPrice = totalPrice;
                    int finalTotalCount = totalCount;

                    manageSection.getStringList("lore.order").forEach((string) -> lore.add(Utils.colorize(string
                            .replace("%order%", String.valueOf(finalOrderAmount))
                            .replace("%price%", DeluxeBazaar.getInstance().numberFormat.format(finalTotalPrice))
                            .replace("%amount%", String.valueOf(finalTotalCount))

                            .replace("%order_amount%", String.valueOf(finalOrderAmount))
                            .replace("%other_order_amount%", String.valueOf(orders.size() - finalOrderAmount))
                            .replace("%total_price%", DeluxeBazaar.getInstance().numberFormat.format(finalTotalPrice))
                            .replace("%item_amount%", String.valueOf(finalTotalCount)))));
                }

                Utils.changeLore(manage, lore, null);
            } else {
                Utils.changeLore(manage, manageSection.getStringList("lore.none"), new PlaceholderUtil()
                        .addPlaceholder("%order_amount%", "0")
                        .addPlaceholder("%other_order_amount%", String.valueOf(orders.size())));
            }
            gui.setItem(this.section.getInt("manage.slot") - 1, ClickableItem.of(manage, (event ->
                    new OrdersMenu(player).openMenu(1))));
        }
    }

    public void loadSellItem() {
        List<String> items = this.subcategorySection.getStringList("item_list");
        if (items.isEmpty())
            return;

        ItemStack sell = Utils.createItemFromSection(this.section.getConfigurationSection("sell"), null);

        HashMap<String, Integer> itemList = new HashMap<>();
        for (String args : items) {
            String entry = args.split("[:]")[0];

            int itemCount = DeluxeBazaar.getInstance().itemHandler.getSellableItemCount(player, entry);
            if (itemCount > 0)
                itemList.put(entry, itemCount);
        }

        ConfigurationSection sellSection = this.section.getConfigurationSection("sell");
        double totalPrice = 0;
        int number = 0;

        List<String> lore = new ArrayList<>();

        if (itemList.isEmpty()) {
            Utils.changeLore(sell, sellSection.getStringList("lore.nothing"), new PlaceholderUtil()
                    .addPlaceholder("%item_name%", Utils.strip(DeluxeBazaar.getInstance().itemsFile.getString("items." + this.subcategorySection.getString("main_item") + ".name")))
                    .addPlaceholder("%item_name_colored%", Utils.colorize(DeluxeBazaar.getInstance().itemsFile.getString("items." + this.subcategorySection.getString("main_item") + ".name"))));
            gui.setItem(this.section.getInt("sell.slot") - 1,
                    ClickableItem.of(sell, (event) -> Utils.sendMessage(player, "no_sellable_item")));
        } else {
            sellSection.getStringList("lore.header")
                    .forEach((string) -> lore.add(Utils.colorize(string
                            .replace("%item_name%", Utils.strip(DeluxeBazaar.getInstance().itemsFile.getString("items." + this.subcategorySection.getString("main_item") + ".name")))
                            .replace("%item_name_colored%", Utils.colorize(DeluxeBazaar.getInstance().itemsFile.getString("items." + this.subcategorySection.getString("main_item") + ".name"))))));

            for (Map.Entry<String, Integer> entry : itemList.entrySet()) {
                String key = entry.getKey();
                Integer value = entry.getValue();
                String entryName = DeluxeBazaar.getInstance().itemsFile.getString("items." + key + ".name");

                double itemPrice = BazaarItemHook.getSellPrice(player, key, value);
                totalPrice += itemPrice;

                if (number < sellSection.getInt("maximum")) {
                    number++;
                    lore.add(Utils.colorize(sellSection.getString("items.sellable")
                            .replace("%item_amount%", String.valueOf(value))
                            .replace("%count%", String.valueOf(value))
                            .replace("%item_ranking%", String.valueOf(number))
                            .replace("%number%", String.valueOf(number))
                            .replace("%item_name_colored%", Utils.colorize(entryName))
                            .replace("%item_name%", Utils.strip(entryName))
                            .replace("%item_sell_price%", DeluxeBazaar.getInstance().numberFormat.format(itemPrice))));
                }
            }

            if (number < itemList.size())
                lore.add(Utils.colorize(sellSection.getString("items.more")
                        .replace("%other_items%", String.valueOf(itemList.size() - number))));

            double finalPrice = totalPrice;
            sellSection.getStringList("lore.footer").forEach((string) -> {
                lore.add(Utils.colorize(string
                        .replace("%price%", DeluxeBazaar.getInstance().numberFormat.format(finalPrice))
                        .replace("%total_price%", DeluxeBazaar.getInstance().numberFormat.format(finalPrice))));
            });

            Utils.changeLore(sell, lore, null);
            gui.setItem(this.section.getInt("sell.slot") - 1,
                    ClickableItem.of(sell, (event -> new SellAllMenu(player).openMenu(itemList))));
        }
    }

    public void loadItems() {
        List<String> items = this.subcategorySection.getStringList("item_list");
        if (items.isEmpty())
            return;

        PlayerBazaar playerBazaar = DeluxeBazaar.getInstance().players.getOrDefault(player.getUniqueId(), new PlayerBazaar(player));
        String mode = playerBazaar.getMode();

        for (String entry : items) {
            String[] args = entry.split("[:]", 2);
            if (args.length < 2)
                continue;

            ItemStack item;
            List<String> lore = new ArrayList<>();

            ConfigurationSection subcategorySection = DeluxeBazaar.getInstance().itemsFile.getConfigurationSection("groups." + args[0]);
            if (subcategorySection == null) {
                ConfigurationSection itemSection = DeluxeBazaar.getInstance().itemsFile.getConfigurationSection("items." + args[0]);
                if (itemSection == null)
                    continue;

                BazaarItem bazaarItem = DeluxeBazaar.getInstance().bazaarItems.get(args[0]);
                if (bazaarItem == null)
                    continue;

                item = bazaarItem.getItemStack().clone();

                int productCount = Math.max(items.size(), 1);
                boolean buyStock = DeluxeBazaar.getInstance().orderHandler.isStockEnabled(args[0], "buy") && DeluxeBazaar.getInstance().orderHandler.getStockCount(player, args[0], "buy") < 1;
                boolean sellStock = DeluxeBazaar.getInstance().orderHandler.isStockEnabled(args[0], "sell") && DeluxeBazaar.getInstance().orderHandler.getStockCount(player, args[0], "sell") < 1;

                double buyPrice = BazaarItemHook.getBuyPrice(player, args[0], 1);
                double sellPrice = BazaarItemHook.getSellPrice(player, args[0], 1);
                List<String> details = itemSection.getStringList("details");

                PlaceholderUtil placeholderUtil = new PlaceholderUtil()
                        .addPlaceholder("%item_buy_price%", buyStock ?
                                DeluxeBazaar.getInstance().messagesFile.getString("no_stock") :
                                DeluxeBazaar.getInstance().numberFormat.format(buyPrice))
                        .addPlaceholder("%item_sell_price%", sellStock ?
                                DeluxeBazaar.getInstance().messagesFile.getString("no_stock") :
                                DeluxeBazaar.getInstance().numberFormat.format(sellPrice))
                        .addPlaceholder("%item_buy_amount%", String.valueOf(BazaarItemHook.getBuyCount(args[0])))
                        .addPlaceholder("%item_sell_amount%", String.valueOf(BazaarItemHook.getSellCount(args[0])))
                        .addPlaceholder("%product_amount%", String.valueOf(productCount));


                boolean disableLore = DeluxeBazaar.getInstance().configFile.getBoolean("settings.disable_price_lore", true);
                for (String string : DeluxeBazaar.getInstance().messagesFile.getStringList("lores.item." + mode)) {
                    if (string.contains("%item_details%")) {
                        if (!details.isEmpty())
                            for (String detail : details)
                                lore.add(Utils.colorize(Utils.replacePlaceholders(detail, placeholderUtil)));

                        continue;
                    }

                    if (string.contains("%buy_lore%")) {
                        if (disableLore)
                            if (BazaarItemHook.getDefaultBuyPrice(args[0]) <= 0.0)
                                continue;

                        List<String> buyLore = DeluxeBazaar.getInstance().messagesFile.getStringList("lores.buy_lore." + mode);
                        if (!buyLore.isEmpty())
                            for (String line : buyLore)
                                lore.add(Utils.colorize(Utils.replacePlaceholders(line, placeholderUtil)));

                        continue;
                    }

                    if (string.contains("%sell_lore%")) {
                        if (disableLore)
                            if (BazaarItemHook.getDefaultSellPrice(args[0]) <= 0.0)
                                continue;

                        List<String> sellLore = DeluxeBazaar.getInstance().messagesFile.getStringList("lores.sell_lore." + mode);
                        if (!sellLore.isEmpty())
                            for (String line : sellLore)
                                lore.add(Utils.colorize(Utils.replacePlaceholders(line, placeholderUtil)));

                        continue;
                    }

                    lore.add(Utils.colorize(Utils.replacePlaceholders(string, placeholderUtil)));
                }
            } else {
                String mainItemName = subcategorySection.getString("main_item");
                if (mainItemName == null || mainItemName.equals(""))
                    continue;

                BazaarItem bazaarItem = DeluxeBazaar.getInstance().bazaarItems.get(mainItemName);
                if (bazaarItem == null)
                    continue;

                item = Utils.createItemFromSection(section.getConfigurationSection("item"), null);
                if (item == null)
                    item = bazaarItem.getItemStack().clone();

                    String subcategoryName = subcategorySection.getString("item.name");
                    if (subcategoryName != null && !subcategoryName.equals(""))
                        Utils.changeName(item, Utils.colorize(DeluxeBazaar.getInstance().categoriesFile.getString(playerBazaar.getCategory() + ".color", "") + subcategoryName), null);
                    else
                        Utils.changeName(item, Utils.colorize(DeluxeBazaar.getInstance().categoriesFile.getString(playerBazaar.getCategory() + ".color", "") + Utils.strip(DeluxeBazaar.getInstance().itemsFile.getString("items." + section.getString("main_item") + ".name"))), null);

                List<String> itemlist = subcategorySection.getStringList("item_list");
                for (String string : DeluxeBazaar.getInstance().messagesFile.getStringList("lores.subcategory." + mode)) {
                    if (string.contains("%subcategory_description%")) {
                        List<String> description = section.getStringList("item.description");
                        if (description.isEmpty())
                            continue;

                        for (String line : description)
                            lore.add(Utils.colorize(line));

                        continue;
                    }

                    if (string.contains("%item_informations%")) {
                        String itemInformation = DeluxeBazaar.getInstance().messagesFile.getString("lores.subcategory.item_information");

                        for (String itemArgs : itemlist) {
                            String arg = itemArgs.split("[:]", 2)[0];

                            PlaceholderUtil placeholderUtil = new PlaceholderUtil()
                                    .addPlaceholder("%item_name%", Utils.strip(DeluxeBazaar.getInstance().itemsFile.getString("items." + arg + ".name")))
                                    .addPlaceholder("%item_name_colored%", Utils.colorize(DeluxeBazaar.getInstance().itemsFile.getString("items." + arg + ".name")))
                                    .addPlaceholder("%item_buy_price%", DeluxeBazaar.getInstance().numberFormat.format(BazaarItemHook.getBuyPrice(player, arg, 1)))
                                    .addPlaceholder("%item_sell_price%", DeluxeBazaar.getInstance().numberFormat.format(BazaarItemHook.getSellPrice(player, arg, 1)));

                            lore.add(Utils.colorize(Utils.replacePlaceholders(itemInformation, placeholderUtil)));
                        }

                        continue;
                    }

                    PlaceholderUtil placeholderUtil = new PlaceholderUtil()
                            .addPlaceholder("%product_amount%", String.valueOf(itemlist.size()));
                    lore.add(Utils.colorize(Utils.replacePlaceholders(string, placeholderUtil)));
                }

            }

            Utils.changeLore(item, lore, null);
            gui.setItem(Integer.parseInt(args[1]) - 1, ClickableItem.of(item, (event ->  {
                if (subcategorySection == null)
                new ItemMenu(player).openMenu(args[0]);
                else {
                    oldSubCategory = this.subcategorySection.getName();
                    openMenu(args[0]);
                }
            })));
        }
    }


}
