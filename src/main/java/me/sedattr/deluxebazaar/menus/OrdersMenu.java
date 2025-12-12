package me.sedattr.deluxebazaar.menus;

import me.sedattr.deluxebazaar.DeluxeBazaar;
import me.sedattr.deluxebazaar.database.HybridDatabase;
import me.sedattr.deluxebazaar.database.MySQLDatabase;
import me.sedattr.deluxebazaar.database.RedisDatabase;
import me.sedattr.deluxebazaar.inventoryapi.HInventory;
import me.sedattr.deluxebazaar.inventoryapi.item.ClickableItem;
import me.sedattr.deluxebazaar.managers.OrderType;
import me.sedattr.deluxebazaar.managers.PlayerBazaar;
import me.sedattr.deluxebazaar.managers.PlayerOrder;
import me.sedattr.deluxebazaar.others.Logger;
import me.sedattr.deluxebazaar.others.PlaceholderUtil;
import me.sedattr.deluxebazaar.others.Utils;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

public class OrdersMenu {
    private final ConfigurationSection section;
    private final Player player;
    private ItemStack next;
    private ItemStack previous;
    private boolean pagination;

    public OrdersMenu(Player player) {
        this.section = DeluxeBazaar.getInstance().menusFile.getConfigurationSection("menus.order");
        this.player = player;

        if (section == null)
            return;
        pagination = section.getBoolean("pagination");
        if (pagination) {
            next = Utils.createItemFromSection(section.getConfigurationSection("next"), null);
            previous = Utils.createItemFromSection(section.getConfigurationSection("previous"), null);
        }
    }

    public void loadItem(ItemStack item, PlayerOrder playerOrder, String type) {
        double price = playerOrder.getPrice();
        Utils.addMenuItemFlags(item);

        String itemName = DeluxeBazaar.getInstance().itemsFile.getString("items." + playerOrder.getItem().getName() + ".name");
        Utils.changeName(item, DeluxeBazaar.getInstance().messagesFile.getString("lores." + (playerOrder.getType().equals(OrderType.BUY) ? "buy_order." : "sell_offer.") + type + ".name"), new PlaceholderUtil()
                .addPlaceholder("%item_name%", Utils.strip(itemName))
                .addPlaceholder("%name%", Utils.strip(itemName))
                .addPlaceholder("%item_name_colored%", Utils.colorize(itemName))
        );

        if (type.equalsIgnoreCase("filled")) {
            int percent = (playerOrder.getFilled() * 100) / playerOrder.getAmount();
            Utils.changeLore(item, DeluxeBazaar.getInstance().messagesFile.getStringList("lores." + (playerOrder.getType().equals(OrderType.BUY) ? "buy_order." : "sell_offer.") + type + ".lore"), new PlaceholderUtil()
                    .addPlaceholder("%unit_price%", DeluxeBazaar.getInstance().numberFormat.format(price))
                    .addPlaceholder("%total_amount%", String.valueOf(playerOrder.getAmount()))
                    .addPlaceholder("%fill_amount%", String.valueOf(playerOrder.getFilled()))
                    .addPlaceholder("%collectable_items%", String.valueOf(playerOrder.getFilled() - playerOrder.getCollected()))
                    .addPlaceholder("%collectable_coins%", DeluxeBazaar.getInstance().numberFormat.format(price * (playerOrder.getFilled() - playerOrder.getCollected())))
                    .addPlaceholder("%fill_percent%", String.valueOf(percent == 100 ? "&l" + percent : percent))
                    .addPlaceholder("%total_price%", DeluxeBazaar.getInstance().numberFormat.format(price * playerOrder.getAmount())));
            return;
        }

        Utils.changeLore(item, DeluxeBazaar.getInstance().messagesFile.getStringList("lores." + (playerOrder.getType().equals(OrderType.BUY) ? "buy_order." : "sell_offer.") + type + ".lore"), new PlaceholderUtil()
                .addPlaceholder("%unit_price%", DeluxeBazaar.getInstance().numberFormat.format(price))
                .addPlaceholder("%unitprice%", DeluxeBazaar.getInstance().numberFormat.format(price))
                .addPlaceholder("%player%", player.getName())
                .addPlaceholder("%count%", String.valueOf(playerOrder.getAmount()))
                .addPlaceholder("%price%", DeluxeBazaar.getInstance().numberFormat.format(price * playerOrder.getAmount()))
                .addPlaceholder("%total_amount%", String.valueOf(playerOrder.getAmount()))
                .addPlaceholder("%player_displayname%", player.getDisplayName())
                .addPlaceholder("%player_name%", player.getName())
                .addPlaceholder("%total_price%", DeluxeBazaar.getInstance().numberFormat.format(price * playerOrder.getAmount())));
    }

    public void openMenu(int page) {
        openMenu(page, true);
    }

    public void openMenu(int page, boolean reloadFromRedis) {
        if (this.section == null)
            return;

        if (reloadFromRedis && DeluxeBazaar.getInstance().databaseManager instanceof HybridDatabase) {
            // HybridDatabase loads from Redis cache for real-time data
            // No need to explicitly reload as HybridDatabase handles this via pub/sub
        } else if (reloadFromRedis && DeluxeBazaar.getInstance().databaseManager instanceof RedisDatabase) {
            RedisDatabase redisDb = (RedisDatabase) DeluxeBazaar.getInstance().databaseManager;
            redisDb.loadPlayerFromRedis(player.getUniqueId());
        }

        PlayerBazaar playerBazaar = DeluxeBazaar.getInstance().players.getOrDefault(player.getUniqueId(), new PlayerBazaar(player));

        HInventory gui = DeluxeBazaar.getInstance().menuHandler.createInventory(this.player, section, playerBazaar.getCategory(), new PlaceholderUtil()
                .addPlaceholder("%current_page%", String.valueOf(page))
                .addPlaceholder("%currentpage%", String.valueOf(page))
                .addPlaceholder("%category_name_colored%", Utils.colorize(DeluxeBazaar.getInstance().categoriesFile.getString(playerBazaar.getCategory() + ".name")))
                .addPlaceholder("%category_name%", Utils.strip(DeluxeBazaar.getInstance().categoriesFile.getString(playerBazaar.getCategory() + ".name"))));

        boolean buyLastPage = false;
        boolean sellLastPage = false;
        for (OrderType type : OrderType.values()) {
            int i = 0;

            List<Integer> slots = section.getIntegerList("items." + type.name().toLowerCase());
            List<PlayerOrder> playerOrders = type.equals(OrderType.BUY) ? playerBazaar.getBuyOrders() : playerBazaar.getSellOffers();
            int count = slots.size();

            if (pagination) {
                int orderCount = playerOrders.size();
                if (orderCount <= count) {
                    if (type.equals(OrderType.BUY))
                        buyLastPage = true;
                    else
                        sellLastPage = true;
                } else if (page > 1 && (orderCount > ((page - 1) * count))) {
                    int minimum = Math.min((page - 1) * count, orderCount - 1);
                    int maximum = Math.min(page * count, orderCount);

                    playerOrders = playerOrders.subList(minimum, maximum);
                    if (maximum >= orderCount)
                        if (type.equals(OrderType.BUY))
                            buyLastPage = true;
                        else
                            sellLastPage = true;
                } else if (orderCount <= (page - 1) * count) {
                    playerOrders = new ArrayList<>();
                    if (type.equals(OrderType.BUY))
                        buyLastPage = true;
                    else
                        sellLastPage = true;
                }
            }

            for (PlayerOrder playerOrder : new ArrayList<>(playerOrders)) {
                if (i >= count)
                    break;

                ConfigurationSection itemSection = DeluxeBazaar.getInstance().itemsFile.getConfigurationSection("items." + playerOrder.getItem().getName());
                String itemType = playerOrder.getFilled() < 1 ? "setup" : "filled";

                AtomicReference<ItemStack> bazaarItem = new AtomicReference<>(playerOrder.getItem().getItemStack());
                ItemStack itemStack = bazaarItem.get().clone();
                loadItem(itemStack, playerOrder, itemType);

                gui.setItem(slots.get(i) - 1, ClickableItem.of(itemStack, (event -> {
                    int left = playerOrder.getFilled() - playerOrder.getCollected();

                    if (left <= 0) {
                        new OrderSettingsMenu(player).openMenu(playerOrder);
                        return;
                    }

                    Double price = playerOrder.getPrice() * left;
                    if (playerOrder.getType().equals(OrderType.BUY)) {
                        int emptySlot = DeluxeBazaar.getInstance().itemHandler.getEmptySlots(player, bazaarItem.get().clone());
                        if (emptySlot < left)
                            left = emptySlot;

                        if (left < 1) {
                            Utils.sendMessage(player, "no_empty_slot");
                            new OrderSettingsMenu(player).openMenu(playerOrder);
                            return;
                        }

                        DeluxeBazaar.getInstance().itemHandler.giveBazaarItems(player, bazaarItem.get().clone(), left);
                    } else if (!DeluxeBazaar.getInstance().economyManager.addBalance(player, price)) {
                        new OrderSettingsMenu(player).openMenu(playerOrder);
                        return;
                    }

                    int total = playerOrder.getCollected() + left;

                    playerOrder.setCollected(total);

                    if (total >= playerOrder.getAmount()) {
                        if (type.equals(OrderType.BUY))
                            playerBazaar.getBuyOrders().remove(playerOrder);
                        else
                            playerBazaar.getSellOffers().remove(playerOrder);
                    }

                    if (DeluxeBazaar.getInstance().databaseManager instanceof HybridDatabase) {
                        HybridDatabase hybridDb = (HybridDatabase) DeluxeBazaar.getInstance().databaseManager;
                        hybridDb.savePlayerAsync(player.getUniqueId(), playerBazaar);
                    } else if (DeluxeBazaar.getInstance().databaseManager instanceof MySQLDatabase) {
                        MySQLDatabase mysqlDb = (MySQLDatabase) DeluxeBazaar.getInstance().databaseManager;
                        mysqlDb.savePlayer(player.getUniqueId(), playerBazaar);
                    } else if (DeluxeBazaar.getInstance().databaseManager instanceof RedisDatabase) {
                        RedisDatabase redisDb = (RedisDatabase) DeluxeBazaar.getInstance().databaseManager;
                        redisDb.savePlayer(player.getUniqueId(), playerBazaar);
                    }

                    Utils.sendMessage(player, playerOrder.getType().equals(OrderType.BUY) ? "claimed_buy_order" : "claimed_sell_offer", new PlaceholderUtil()
                            .addPlaceholder("%total_amount%", String.valueOf(left))
                            .addPlaceholder("%total_price%", DeluxeBazaar.getInstance().numberFormat.format(price))
                            .addPlaceholder("%item_name%", Utils.strip(itemSection.getString("name")))
                            .addPlaceholder("%item_name_colored%", Utils.colorize(itemSection.getString("name")))
                            .addPlaceholder("%unit_price%", DeluxeBazaar.getInstance().numberFormat.format(playerOrder.getPrice())));

                    DeluxeBazaar.getInstance().getServer().getScheduler().runTaskLater(DeluxeBazaar.getInstance(), () -> {
                        openMenu(page);
                    }, 5L);
                })));

                i++;
            }
        }

        if (pagination) {
            if (!(buyLastPage && sellLastPage) && next != null)
                gui.setItem(section.getInt("next.slot") - 1, ClickableItem.of(next, (event -> openMenu(page + 1))));

            if (page > 1 && previous != null)
                gui.setItem(section.getInt("previous.slot") - 1, ClickableItem.of(previous, (event -> openMenu(page - 1))));
        }

        int goBackSlot = section.getInt("back");
        ItemStack goBackItem = DeluxeBazaar.getInstance().normalItems.get("goBack");
        if (goBackSlot > 0 && goBackItem != null)
            gui.setItem(goBackSlot - 1, ClickableItem.of(goBackItem, (event) -> new MainMenu(player).openMenu(playerBazaar.getCategory(), 1)));

        gui.open(player);
    }
}