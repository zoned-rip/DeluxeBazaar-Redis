package me.sedattr.deluxebazaar.listeners;

import me.sedattr.deluxebazaar.DeluxeBazaar;
import me.sedattr.bazaarapi.events.BazaarItemBuyEvent;
import me.sedattr.bazaarapi.events.BazaarItemSellEvent;
import me.sedattr.deluxebazaar.managers.*;
import me.sedattr.deluxebazaar.others.PlaceholderUtil;
import me.sedattr.deluxebazaar.others.Utils;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

import java.util.*;

public class BazaarListeners implements Listener {
    @EventHandler
    public void onItemBuy(BazaarItemBuyEvent e) {
        OfflinePlayer player = e.getPlayer();
        Double price = e.getUnitPrice();
        BazaarItem item = e.getItem();
        int count = e.getCount();

        List<OrderPrice> orderPrices = new ArrayList<>(item.getSellPrices());
        if (!orderPrices.isEmpty())
            for (OrderPrice orderPrice : orderPrices) {
                if (count <= 0)
                    break;
                if (orderPrice.getPrice() > price)
                    continue;

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
                    orderPrice.setItemAmount(orderPrice.getItemAmount() - count);
                    changePlayerOrders(player.getUniqueId(), count, orderPrice);
                    break;
                }
                else {
                    count-=newCount;
                    changePlayerOrders(player.getUniqueId(), newCount, orderPrice);

                    item.getSellPrices().remove(orderPrice);
                }
            }
    }

    @EventHandler
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
    }

    public void changePlayerOrders(UUID uuid, Integer count, OrderPrice orderPrice) {
        for (Map.Entry<UUID, List<PlayerOrder>> entry : orderPrice.getPlayers().entrySet()) {
            if (count <= 0)
                return;
            if (uuid == entry.getKey())
                continue;

            PlayerBazaar playerBazaar = DeluxeBazaar.getInstance().players.get(entry.getKey());
            if (playerBazaar == null)
                continue;

            List<PlayerOrder> orders = entry.getValue();
            if (orders.isEmpty())
                return;

            for (PlayerOrder order : orders) {
                int totalCount = order.getAmount();
                if (order.getFilled() >= totalCount)
                    continue;

                int fillCount = order.getFilled() + count;
                if (totalCount > fillCount) {
                    order.setFilled(fillCount);
                    return;
                }
                else {
                    count-=(totalCount-order.getFilled());
                    order.setFilled(totalCount);

                    int newOrderAmount = orderPrice.getOrderAmount()-1;
                    if (newOrderAmount <= 0) {
                        if (orderPrice.getType().equals(OrderType.BUY))
                            order.getItem().getBuyPrices().remove(orderPrice);
                        else
                            order.getItem().getSellPrices().remove(orderPrice);
                    } else
                        orderPrice.setOrderAmount(orderPrice.getOrderAmount()-1);

                    String itemName = DeluxeBazaar.getInstance().itemsFile.getString("items." + order.getItem().getName() + ".name");
                    Player player = Bukkit.getPlayer(entry.getKey());
                    if (player != null && player.isOnline())
                        Utils.sendMessage(player, order.getType().equals(OrderType.BUY) ? "filled_buy_order" : "filled_sell_offerr", new PlaceholderUtil()
                                .addPlaceholder("%total_amount%", String.valueOf(totalCount))
                                .addPlaceholder("%item_name%", Utils.strip(itemName))
                                .addPlaceholder("%item_name_colored%", Utils.colorize(itemName)));
                }
            }
        }
    }


}