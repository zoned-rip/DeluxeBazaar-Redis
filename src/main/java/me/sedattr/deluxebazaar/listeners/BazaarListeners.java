package me.sedattr.deluxebazaar.listeners;

import me.sedattr.deluxebazaar.DeluxeBazaar;
import me.sedattr.bazaarapi.events.BazaarItemBuyEvent;
import me.sedattr.bazaarapi.events.BazaarItemSellEvent;
import me.sedattr.deluxebazaar.database.MySQLDatabase;
import me.sedattr.deluxebazaar.database.RedisDatabase;
import me.sedattr.deluxebazaar.managers.*;
import me.sedattr.deluxebazaar.others.Logger;
import me.sedattr.deluxebazaar.others.PlaceholderUtil;
import me.sedattr.deluxebazaar.others.Utils;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

import java.util.*;

public class BazaarListeners implements Listener {

    // Note: For cross-server sync with MySQL/Redis, make sure to enable auto_save in config.yml:
    // database.auto_save.player_orders: true
    // database.auto_save.item_orders: true
    // database.auto_save.item_prices: true
    //
    // Data will be synced in real-time via the async save methods in OrderHandler
    // and the periodic refresh task in DataHandler.startDataTask()

    @EventHandler
    public void onItemBuy(BazaarItemBuyEvent e) {
        OfflinePlayer player = e.getPlayer();
        Double price = e.getUnitPrice();
        BazaarItem item = e.getItem();
        int count = e.getCount();

        Logger.sendConsoleMessage("§e[DEBUG] onItemBuy: Player=" + player.getName() + ", Item=" + item.getName() + ", Count=" + count + ", Price=" + price, Logger.LogLevel.INFO);

        List<OrderPrice> orderPrices = new ArrayList<>(item.getSellPrices());
        Logger.sendConsoleMessage("§e[DEBUG] onItemBuy: Found " + orderPrices.size() + " sell order prices", Logger.LogLevel.INFO);

        if (!orderPrices.isEmpty())
            for (OrderPrice orderPrice : orderPrices) {
                if (count <= 0)
                    break;
                if (orderPrice.getPrice() > price)
                    continue;

                Logger.sendConsoleMessage("§e[DEBUG] onItemBuy: Processing OrderPrice at price=" + orderPrice.getPrice() + ", itemAmount=" + orderPrice.getItemAmount() + ", orderAmount=" + orderPrice.getOrderAmount() + ", players=" + orderPrice.getPlayers().size(), Logger.LogLevel.INFO);

                int itemCount = DeluxeBazaar.getInstance().orderHandler.getItemCount(player, "buy", item.getName(), orderPrice.getPrice());
                int newCount = orderPrice.getItemAmount() - itemCount;
                if (newCount <= 0)
                    continue;

                /*
                double maximumPriceChange = DeluxeBazaar.getInstance().configFile.getBoolean("sell_offer.limit_price_change") ? DeluxeBazaar.getInstance().configFile.getDouble("sell_offer.maximum_price_change", 0.0) : 0.0;
                if (maximumPriceChange > 0.0) {
                    double currentChange = BazaarItemHook.getDefaultBuyPrice(item.getName()) - orderPrice.getPrice();
                    if (currentChange > maximumPriceChange)
                        continue;
                }
                 */

                if (newCount > count) {
                    Logger.sendConsoleMessage("§e[DEBUG] onItemBuy: Partial fill - calling changePlayerOrders with count=" + count, Logger.LogLevel.INFO);
                    orderPrice.setItemAmount(orderPrice.getItemAmount() - count);
                    changePlayerOrders(player.getUniqueId(), count, orderPrice);
                    break;
                }
                else {
                    Logger.sendConsoleMessage("§e[DEBUG] onItemBuy: Full fill - calling changePlayerOrders with count=" + newCount, Logger.LogLevel.INFO);
                    count-=newCount;
                    changePlayerOrders(player.getUniqueId(), newCount, orderPrice);

                    item.getSellPrices().remove(orderPrice);
                }
            }

        if (DeluxeBazaar.getInstance().databaseManager instanceof MySQLDatabase) {
            MySQLDatabase mysqlDb = (MySQLDatabase) DeluxeBazaar.getInstance().databaseManager;
            mysqlDb.saveItemAsync(item.getName(), item);
            Logger.sendConsoleMessage("§e[DEBUG] onItemBuy: Saved item to MySQL", Logger.LogLevel.INFO);
        } else if (DeluxeBazaar.getInstance().databaseManager instanceof RedisDatabase) {
            RedisDatabase redisDb = (RedisDatabase) DeluxeBazaar.getInstance().databaseManager;
            redisDb.saveItemAsync(item.getName(), item);
            Logger.sendConsoleMessage("§e[DEBUG] onItemBuy: Saved item to Redis", Logger.LogLevel.INFO);
        }
    }    @EventHandler
    public void onItemSell(BazaarItemSellEvent e) {
        OfflinePlayer player = e.getPlayer();
        Double price = e.getUnitPrice();
        BazaarItem item = e.getItem();
        int count = e.getCount();

        List<OrderPrice> orderPrices = new ArrayList<>(item.getBuyPrices());
        if (!orderPrices.isEmpty())
            for (OrderPrice orderPrice : orderPrices) {
                if (count <= 0)
                    break;
                if (orderPrice.getPrice() < price)
                    continue;

                int itemCount = DeluxeBazaar.getInstance().orderHandler.getItemCount(player, "sell", item.getName(), orderPrice.getPrice());
                int newCount = orderPrice.getItemAmount() - itemCount;
                if (newCount <= 0)
                    continue;

                /*
                double maximumPriceChange = DeluxeBazaar.getInstance().configFile.getBoolean("buy_order.limit_price_change") ? DeluxeBazaar.getInstance().configFile.getDouble("buy_order.maximum_price_change", 0.0) : 0.0;
                if (maximumPriceChange > 0.0) {
                    double currentChange = orderPrice.getPrice() - BazaarItemHook.getDefaultSellPrice(item.getName());
                    if (currentChange > maximumPriceChange)
                        continue;
                }
                 */

                if (newCount > count) {
                    orderPrice.setItemAmount(orderPrice.getItemAmount() - count);

                    changePlayerOrders(player.getUniqueId(), count, orderPrice);
                    break;
                }
                else {
                    count-=newCount;
                    changePlayerOrders(player.getUniqueId(), newCount, orderPrice);

                    item.getBuyPrices().remove(orderPrice);
                }
            }

        if (DeluxeBazaar.getInstance().databaseManager instanceof MySQLDatabase) {
            MySQLDatabase mysqlDb = (MySQLDatabase) DeluxeBazaar.getInstance().databaseManager;
            mysqlDb.saveItemAsync(item.getName(), item);
        } else if (DeluxeBazaar.getInstance().databaseManager instanceof RedisDatabase) {
            RedisDatabase redisDb = (RedisDatabase) DeluxeBazaar.getInstance().databaseManager;
            redisDb.saveItemAsync(item.getName(), item);
        }
    }    public void changePlayerOrders(UUID uuid, Integer count, OrderPrice orderPrice) {
        Logger.sendConsoleMessage("§e[DEBUG] changePlayerOrders: Called with uuid=" + uuid + ", count=" + count + ", orderPrice players=" + orderPrice.getPlayers().size(), Logger.LogLevel.INFO);

        Set<UUID> modifiedPlayers = new HashSet<>();

        for (Map.Entry<UUID, List<PlayerOrder>> entry : orderPrice.getPlayers().entrySet()) {
            Logger.sendConsoleMessage("§e[DEBUG] changePlayerOrders: Checking player " + entry.getKey() + " with " + entry.getValue().size() + " orders", Logger.LogLevel.INFO);

            if (count <= 0) {
                Logger.sendConsoleMessage("§e[DEBUG] changePlayerOrders: Count is 0 or less, breaking", Logger.LogLevel.INFO);
                break;
            }
            if (uuid == entry.getKey()) {
                Logger.sendConsoleMessage("§e[DEBUG] changePlayerOrders: Skipping buyer's own UUID", Logger.LogLevel.INFO);
                continue;
            }

            PlayerBazaar playerBazaar = DeluxeBazaar.getInstance().players.get(entry.getKey());
            if (playerBazaar == null) {
                Logger.sendConsoleMessage("§c[DEBUG] changePlayerOrders: PlayerBazaar is NULL for " + entry.getKey(), Logger.LogLevel.WARN);
                continue;
            }

            List<PlayerOrder> orders = entry.getValue();
            if (orders.isEmpty()) {
                Logger.sendConsoleMessage("§e[DEBUG] changePlayerOrders: Orders list is empty, continuing", Logger.LogLevel.INFO);
                continue;
            }

            for (PlayerOrder order : orders) {
                int totalCount = order.getAmount();
                int currentFilled = order.getFilled();
                Logger.sendConsoleMessage("§e[DEBUG] changePlayerOrders: Processing order - totalCount=" + totalCount + ", currentFilled=" + currentFilled + ", price=" + order.getPrice(), Logger.LogLevel.INFO);

                if (order.getFilled() >= totalCount) {
                    Logger.sendConsoleMessage("§e[DEBUG] changePlayerOrders: Order already fully filled, skipping", Logger.LogLevel.INFO);
                    continue;
                }

                int fillCount = order.getFilled() + count;
                if (totalCount > fillCount) {
                    Logger.sendConsoleMessage("§e[DEBUG] changePlayerOrders: Partial fill - setting filled to " + fillCount, Logger.LogLevel.INFO);
                    order.setFilled(fillCount);
                    modifiedPlayers.add(entry.getKey());
                    break;
                }
                else {
                    int fillAmount = totalCount - order.getFilled();
                    Logger.sendConsoleMessage("§e[DEBUG] changePlayerOrders: Full fill - setting filled to " + totalCount + ", fillAmount=" + fillAmount, Logger.LogLevel.INFO);
                    count -= fillAmount;
                    order.setFilled(totalCount);
                    modifiedPlayers.add(entry.getKey());

                    int newOrderAmount = orderPrice.getOrderAmount()-1;
                    if (newOrderAmount <= 0) {
                        if (orderPrice.getType().equals(OrderType.BUY))
                            order.getItem().getBuyPrices().remove(orderPrice);
                        else
                            order.getItem().getSellPrices().remove(orderPrice);
                        Logger.sendConsoleMessage("§e[DEBUG] changePlayerOrders: Removed OrderPrice from item order book", Logger.LogLevel.INFO);
                    } else {
                        orderPrice.setOrderAmount(orderPrice.getOrderAmount()-1);
                        Logger.sendConsoleMessage("§e[DEBUG] changePlayerOrders: Decreased orderAmount to " + (orderPrice.getOrderAmount()), Logger.LogLevel.INFO);
                    }

                    Logger.sendConsoleMessage("§e[DEBUG] changePlayerOrders: Order fully filled, keeping in player list for claiming", Logger.LogLevel.INFO);

                    String itemName = DeluxeBazaar.getInstance().itemsFile.getString("items." + order.getItem().getName() + ".name");
                    Player player = Bukkit.getPlayer(entry.getKey());
                    if (player != null && player.isOnline()) {
                        Utils.sendMessage(player, order.getType().equals(OrderType.BUY) ? "filled_buy_order" : "filled_sell_offerr", new PlaceholderUtil()
                                .addPlaceholder("%total_amount%", String.valueOf(totalCount))
                                .addPlaceholder("%item_name%", Utils.strip(itemName))
                                .addPlaceholder("%item_name_colored%", Utils.colorize(itemName)));
                        Logger.sendConsoleMessage("§e[DEBUG] changePlayerOrders: Sent filled message to online player", Logger.LogLevel.INFO);
                    }
                }
            }
        }

        Logger.sendConsoleMessage("§e[DEBUG] changePlayerOrders: Finished loop, modified " + modifiedPlayers.size() + " players", Logger.LogLevel.INFO);
        Logger.sendConsoleMessage("§e[DEBUG] changePlayerOrders: Keeping filled orders in player lists for claiming", Logger.LogLevel.INFO);
        if (DeluxeBazaar.getInstance().databaseManager instanceof MySQLDatabase) {
            MySQLDatabase mysqlDb = (MySQLDatabase) DeluxeBazaar.getInstance().databaseManager;
            for (UUID modifiedPlayerUUID : modifiedPlayers) {
                PlayerBazaar playerBazaar = DeluxeBazaar.getInstance().players.get(modifiedPlayerUUID);
                if (playerBazaar != null) {
                    mysqlDb.savePlayerAsync(modifiedPlayerUUID, playerBazaar);
                    Logger.sendConsoleMessage("§e[DEBUG] changePlayerOrders: Saved player " + modifiedPlayerUUID + " to MySQL", Logger.LogLevel.INFO);
                }
            }
        } else if (DeluxeBazaar.getInstance().databaseManager instanceof RedisDatabase) {
            RedisDatabase redisDb = (RedisDatabase) DeluxeBazaar.getInstance().databaseManager;
            Logger.sendConsoleMessage("§e[DEBUG] changePlayerOrders: Using Redis, modified " + modifiedPlayers.size() + " players", Logger.LogLevel.INFO);

            for (UUID modifiedPlayerUUID : modifiedPlayers) {
                PlayerBazaar playerBazaar = DeluxeBazaar.getInstance().players.get(modifiedPlayerUUID);
                if (playerBazaar != null) {
                    Logger.sendConsoleMessage("§e[DEBUG] changePlayerOrders: Saving player " + modifiedPlayerUUID + " to Redis", Logger.LogLevel.INFO);
                    redisDb.savePlayerAsync(modifiedPlayerUUID, playerBazaar);
                } else {
                    Logger.sendConsoleMessage("§c[DEBUG] changePlayerOrders: PlayerBazaar is NULL when trying to save " + modifiedPlayerUUID, Logger.LogLevel.WARN);
                }
            }
        }
    }


}