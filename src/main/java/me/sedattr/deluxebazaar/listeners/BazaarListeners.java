package me.sedattr.deluxebazaar.listeners;

import me.sedattr.deluxebazaar.DeluxeBazaar;
import me.sedattr.bazaarapi.events.BazaarItemBuyEvent;
import me.sedattr.bazaarapi.events.BazaarItemSellEvent;
import me.sedattr.deluxebazaar.database.HybridDatabase;
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
        BazaarItem item = e.getItem();
        int count = e.getCount();

        Logger.sendConsoleMessage("§e[DEBUG] onItemBuy: Player=" + player.getName() + ", Item=" + item.getName() + ", Count=" + count, Logger.LogLevel.INFO);

        // Get sell prices sorted by lowest price first (same order as BazaarItemHook.getBuyPrice uses)
        List<OrderPrice> orderPrices = new ArrayList<>(item.getSellPrices());
        orderPrices.sort(Comparator.comparing(OrderPrice::getPrice));
        
        Logger.sendConsoleMessage("§e[DEBUG] onItemBuy: Found " + orderPrices.size() + " sell order prices", Logger.LogLevel.INFO);

        boolean itemModified = false;
        
        if (!orderPrices.isEmpty())
            for (OrderPrice orderPrice : orderPrices) {
                if (count <= 0)
                    break;
                // Note: We don't filter by price here because the buyer has already paid
                // the total price calculated by BazaarItemHook.getBuyPrice() which includes
                // orders at various price points. We process orders in price order (cheapest first).

                Logger.sendConsoleMessage("§e[DEBUG] onItemBuy: Processing OrderPrice at price=" + orderPrice.getPrice() + ", itemAmount=" + orderPrice.getItemAmount() + ", orderAmount=" + orderPrice.getOrderAmount() + ", players=" + orderPrice.getPlayers().size(), Logger.LogLevel.INFO);


                int playerOwnItemsInThisPrice = 0;
                if (orderPrice.getPlayers().containsKey(player.getUniqueId())) {
                    for (PlayerOrder playerOrder : orderPrice.getPlayers().get(player.getUniqueId())) {
                        playerOwnItemsInThisPrice += (playerOrder.getAmount() - playerOrder.getFilled());
                    }
                    Logger.sendConsoleMessage("§e[DEBUG] onItemBuy: Player has " + playerOwnItemsInThisPrice + " own items in this OrderPrice, excluding from available count", Logger.LogLevel.INFO);
                }
                
                int newCount = orderPrice.getItemAmount() - playerOwnItemsInThisPrice;
                if (newCount <= 0) {
                    Logger.sendConsoleMessage("§e[DEBUG] onItemBuy: No items available after excluding player's own orders, skipping", Logger.LogLevel.INFO);
                    continue;
                }

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
                    itemModified = true;
                    break;
                }
                else {
                    Logger.sendConsoleMessage("§e[DEBUG] onItemBuy: Full fill - calling changePlayerOrders with count=" + newCount, Logger.LogLevel.INFO);
                    count-=newCount;
                    changePlayerOrders(player.getUniqueId(), newCount, orderPrice);

                    item.getSellPrices().remove(orderPrice);
                    itemModified = true;
                }
            }

        if (itemModified) {
            if (DeluxeBazaar.getInstance().databaseManager instanceof HybridDatabase) {
                HybridDatabase hybridDb = (HybridDatabase) DeluxeBazaar.getInstance().databaseManager;
                hybridDb.saveItemAsync(item.getName(), item);
                Logger.sendConsoleMessage("§e[DEBUG] onItemBuy: Saved item to Hybrid (Redis+MySQL)", Logger.LogLevel.INFO);
            } else if (DeluxeBazaar.getInstance().databaseManager instanceof MySQLDatabase) {
                MySQLDatabase mysqlDb = (MySQLDatabase) DeluxeBazaar.getInstance().databaseManager;
                mysqlDb.saveItemAsync(item.getName(), item);
                Logger.sendConsoleMessage("§e[DEBUG] onItemBuy: Saved item to MySQL", Logger.LogLevel.INFO);
            } else if (DeluxeBazaar.getInstance().databaseManager instanceof RedisDatabase) {
                RedisDatabase redisDb = (RedisDatabase) DeluxeBazaar.getInstance().databaseManager;
                redisDb.saveItemAsync(item.getName(), item);
                Logger.sendConsoleMessage("§e[DEBUG] onItemBuy: Saved item to Redis", Logger.LogLevel.INFO);
            }
        } else {
            Logger.sendConsoleMessage("§e[DEBUG] onItemBuy: No orders processed, skipping item save", Logger.LogLevel.INFO);
        }
    }    @EventHandler
    public void onItemSell(BazaarItemSellEvent e) {
        OfflinePlayer player = e.getPlayer();
        BazaarItem item = e.getItem();
        int count = e.getCount();

        boolean itemModified = false;
        
        // Get buy prices sorted by highest price first (same order as BazaarItemHook.getSellPrice uses)
        List<OrderPrice> orderPrices = new ArrayList<>(item.getBuyPrices());
        orderPrices.sort(Comparator.comparing(OrderPrice::getPrice).reversed());
        
        if (!orderPrices.isEmpty())
            for (OrderPrice orderPrice : orderPrices) {
                if (count <= 0)
                    break;
                // Note: We don't filter by price here because the seller has already received
                // the total price calculated by BazaarItemHook.getSellPrice() which includes
                // orders at various price points. We process orders in price order (highest first).


                int playerOwnItemsInThisPrice = 0;
                if (orderPrice.getPlayers().containsKey(player.getUniqueId())) {
                    for (PlayerOrder playerOrder : orderPrice.getPlayers().get(player.getUniqueId())) {
                        playerOwnItemsInThisPrice += (playerOrder.getAmount() - playerOrder.getFilled());
                    }
                }
                
                int newCount = orderPrice.getItemAmount() - playerOwnItemsInThisPrice;
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
                    itemModified = true;
                    break;
                }
                else {
                    count-=newCount;
                    changePlayerOrders(player.getUniqueId(), newCount, orderPrice);

                    item.getBuyPrices().remove(orderPrice);
                    itemModified = true;
                }
            }

        if (itemModified) {
            if (DeluxeBazaar.getInstance().databaseManager instanceof HybridDatabase) {
                HybridDatabase hybridDb = (HybridDatabase) DeluxeBazaar.getInstance().databaseManager;
                hybridDb.saveItemAsync(item.getName(), item);
            } else if (DeluxeBazaar.getInstance().databaseManager instanceof MySQLDatabase) {
                MySQLDatabase mysqlDb = (MySQLDatabase) DeluxeBazaar.getInstance().databaseManager;
                mysqlDb.saveItemAsync(item.getName(), item);
            } else if (DeluxeBazaar.getInstance().databaseManager instanceof RedisDatabase) {
                RedisDatabase redisDb = (RedisDatabase) DeluxeBazaar.getInstance().databaseManager;
                redisDb.saveItemAsync(item.getName(), item);
            }
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
        
        // Determine affected item for item save
        BazaarItem affectedItem = null;
        for (Map.Entry<UUID, List<PlayerOrder>> entry : orderPrice.getPlayers().entrySet()) {
            List<PlayerOrder> orders = entry.getValue();
            if (!orders.isEmpty()) {
                affectedItem = orders.get(0).getItem();
                break;
            }
        }
        
        if (DeluxeBazaar.getInstance().databaseManager instanceof HybridDatabase) {
            HybridDatabase hybridDb = (HybridDatabase) DeluxeBazaar.getInstance().databaseManager;
            Logger.sendConsoleMessage("§e[DEBUG] changePlayerOrders: Using Hybrid (Redis+MySQL), modified " + modifiedPlayers.size() + " players", Logger.LogLevel.INFO);

            for (UUID modifiedPlayerUUID : modifiedPlayers) {
                PlayerBazaar playerBazaar = DeluxeBazaar.getInstance().players.get(modifiedPlayerUUID);
                if (playerBazaar != null) {
                    Logger.sendConsoleMessage("§e[DEBUG] changePlayerOrders: Saving player " + modifiedPlayerUUID + " to Hybrid", Logger.LogLevel.INFO);
                    hybridDb.savePlayerAsync(modifiedPlayerUUID, playerBazaar);
                } else {
                    Logger.sendConsoleMessage("§c[DEBUG] changePlayerOrders: PlayerBazaar is NULL when trying to save " + modifiedPlayerUUID, Logger.LogLevel.WARN);
                }
            }

            // CRITICAL FIX: Save the item to sync OrderPrice.itemAmount across servers
            if (affectedItem != null) {
                Logger.sendConsoleMessage("§e[DEBUG] changePlayerOrders: Saving item " + affectedItem.getName() + " to Hybrid to sync OrderPrice changes", Logger.LogLevel.INFO);
                hybridDb.saveItemAsync(affectedItem.getName(), affectedItem);
            } else {
                Logger.sendConsoleMessage("§c[DEBUG] changePlayerOrders: Could not determine affected item for Hybrid save!", Logger.LogLevel.WARN);
            }
        } else if (DeluxeBazaar.getInstance().databaseManager instanceof MySQLDatabase) {
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

            // CRITICAL FIX: Save the item to sync OrderPrice.itemAmount across servers
            // When orders quantities change and must be published via Redis pub/sub
            if (affectedItem != null) {
                Logger.sendConsoleMessage("§e[DEBUG] changePlayerOrders: Saving item " + affectedItem.getName() + " to Redis to sync OrderPrice changes", Logger.LogLevel.INFO);
                redisDb.saveItemAsync(affectedItem.getName(), affectedItem);
            } else {
                Logger.sendConsoleMessage("§c[DEBUG] changePlayerOrders: Could not determine affected item for Redis save!", Logger.LogLevel.WARN);
            }
        }
    }


}