package me.sedattr.deluxebazaar.database;

import me.sedattr.deluxebazaar.DeluxeBazaar;
import me.sedattr.deluxebazaar.managers.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.*;

public class YamlDatabase implements DatabaseManager {
    private final File file;
    private final YamlConfiguration data;

    public YamlDatabase() {
        this.file = new File(DeluxeBazaar.getInstance().getDataFolder(), "database.yml");
        if (!this.file.exists())
            try {
                this.file.createNewFile();
            } catch (IOException e) {
                e.printStackTrace();
            }
        this.data = YamlConfiguration.loadConfiguration(this.file);
    }

    public boolean savePlayer(UUID uuid, PlayerBazaar playerBazaar) {
        if (playerBazaar == null)
            playerBazaar = DeluxeBazaar.getInstance().players.get(uuid);

        if (playerBazaar == null) {
            this.data.set("players." + uuid, null);
            return false;
        }

        String defaultMode = DeluxeBazaar.getInstance().configFile.getString("settings.default_mode", "direct");
        if (!playerBazaar.getMode().equals(defaultMode))
            this.data.set("players." + uuid + ".mode", playerBazaar.getMode());

        String defaultCategory = DeluxeBazaar.getInstance().configFile.getString("settings.default_category", "mining");
        if (!playerBazaar.getCategory().equals(defaultCategory))
            this.data.set("players." + uuid + ".category", playerBazaar.getCategory());

        for (PlayerOrder order : playerBazaar.getBuyOrders()) {
            this.data.set("players." + uuid + ".buy_orders." + order.getUuid() + ".item", order.getItem().getName());
            this.data.set("players." + uuid + ".buy_orders." + order.getUuid() + ".price", order.getPrice());
            this.data.set("players." + uuid + ".buy_orders." + order.getUuid() + ".collected", order.getCollected());
            this.data.set("players." + uuid + ".buy_orders." + order.getUuid() + ".filled", order.getFilled());
            this.data.set("players." + uuid + ".buy_orders." + order.getUuid() + ".amount", order.getAmount());
        }

        for (PlayerOrder order : playerBazaar.getSellOffers()) {
            this.data.set("players." + uuid + ".sell_offers." + order.getUuid() + ".item", order.getItem().getName());
            this.data.set("players." + uuid + ".sell_offers." + order.getUuid() + ".price", order.getPrice());
            this.data.set("players." + uuid + ".sell_offers." + order.getUuid() + ".collected", order.getCollected());
            this.data.set("players." + uuid + ".sell_offers." + order.getUuid() + ".filled", order.getFilled());
            this.data.set("players." + uuid + ".sell_offers." + order.getUuid() + ".amount", order.getAmount());
        }

        return true;
    }

    public void saveItem(String name, BazaarItem item) {
        if (item == null)
            item = DeluxeBazaar.getInstance().bazaarItems.get(name);

        if (item == null) {
            this.data.set("items." + name, null);
            return;
        }

        if (item.getBuyPrice() > 0 && item.getBuyPrice() != DeluxeBazaar.getInstance().itemsFile.getDouble("items." + name + ".prices.buy"))
            this.data.set("items." + name + ".buy_price", item.getBuyPrice());
        if (item.getSellPrice() > 0 && item.getSellPrice() != DeluxeBazaar.getInstance().itemsFile.getDouble("items." + name + ".prices.sell"))
            this.data.set("items." + name + ".sell_price", item.getSellPrice());
        if (item.getTotalBuyCount() > 0)
            this.data.set("items." + name + ".buy_amount", item.getTotalBuyCount());
        if (item.getTotalSellCount() > 0)
            this.data.set("items." + name + ".sell_amount", item.getTotalSellCount());

        for (OrderPrice order : item.getBuyPrices()) {
            this.data.set("items." + name + ".buy_prices." + order.getUuid() + ".price", order.getPrice());
            this.data.set("items." + name + ".buy_prices." + order.getUuid() + ".item_amount", order.getItemAmount());
            this.data.set("items." + name + ".buy_prices." + order.getUuid() + ".order_amount", order.getOrderAmount());
        }

        for (OrderPrice order : item.getSellPrices()) {
            this.data.set("items." + name + ".sell_prices." + order.getUuid() + ".price", order.getPrice());
            this.data.set("items." + name + ".sell_prices." + order.getUuid() + ".item_amount", order.getItemAmount());
            this.data.set("items." + name + ".sell_prices." + order.getUuid() + ".order_amount", order.getOrderAmount());
        }
    }

    public boolean saveDatabase() {
        this.data.set("players", null);
        this.data.set("items", null);

        for (Map.Entry<UUID, PlayerBazaar> entry : DeluxeBazaar.getInstance().players.entrySet())
            savePlayer(entry.getKey(), entry.getValue());

        for (Map.Entry<String, BazaarItem> entry : DeluxeBazaar.getInstance().bazaarItems.entrySet())
            saveItem(entry.getKey(), entry.getValue());

        try {
            this.data.save(file);
        } catch (IOException e) {
            e.printStackTrace();
            return false;
        }

        return true;
    }

    @Override
    public boolean loadDatabase() {
        return loadItems() && loadPlayers();
    }

    public boolean loadItems() {
        ConfigurationSection items = this.data.getConfigurationSection("items");
        if (items == null)
            return false;

        Set<String> keys = items.getKeys(false);
        if (keys.isEmpty())
            return false;

        for (String key : keys) {
            ConfigurationSection itemSection = items.getConfigurationSection(key);
            if (itemSection == null)
                continue;

            BazaarItem bazaarItem = DeluxeBazaar.getInstance().bazaarItems.get(key);
            if (bazaarItem == null)
                continue;

            double buyPrice = itemSection.getDouble("buy_price");
            if (buyPrice > 0)
                bazaarItem.setBuyPrice(buyPrice);
            double sellPrice = itemSection.getDouble("sell_price");
            if (sellPrice > 0)
                bazaarItem.setSellPrice(sellPrice);

            int buyAmount = itemSection.getInt("buy_amount");
            if (buyAmount > 0)
                bazaarItem.setTotalBuyCount(buyAmount);
            int sellAmount = itemSection.getInt("sell_amount");
            if (sellAmount > 0)
                bazaarItem.setTotalSellCount(sellAmount);

            ConfigurationSection itemBuyPrices = itemSection.getConfigurationSection("buy_prices");
            if (itemBuyPrices != null)
                for (String itemBuyPrice : itemBuyPrices.getKeys(false)) {
                    ConfigurationSection section = itemBuyPrices.getConfigurationSection(itemBuyPrice);
                    if (section == null)
                        continue;

                    int itemAmount = section.getInt("item_amount");
                    if (itemAmount <= 0)
                        continue;

                    int orderAmount = section.getInt("order_amount");
                    if (orderAmount <= 0)
                        continue;

                    double price = section.getDouble("price");
                    if (price <= 0)
                        continue;

                    bazaarItem.getBuyPrices().add(new OrderPrice(UUID.fromString(itemBuyPrice), OrderType.BUY, price, orderAmount, itemAmount));
                }

            ConfigurationSection itemSellPrices = itemSection.getConfigurationSection("sell_prices");
            if (itemSellPrices != null)
                for (String itemSellPrice : itemSellPrices.getKeys(false)) {
                    ConfigurationSection section = itemSellPrices.getConfigurationSection(itemSellPrice);
                    if (section == null)
                        continue;

                    int itemAmount = section.getInt("item_amount");
                    if (itemAmount < 0)
                        continue;

                    int orderAmount = section.getInt("order_amount");
                    if (orderAmount < 0)
                        continue;

                    double price = section.getDouble("price");
                    if (price < 0)
                        continue;

                    bazaarItem.getSellPrices().add(new OrderPrice(UUID.fromString(itemSellPrice), OrderType.SELL, price, orderAmount, itemAmount));
                }
        }

        return true;
    }


    public boolean loadPlayers() {
        ConfigurationSection players = this.data.getConfigurationSection("players");
        if (players == null)
            return false;

        Set<String> keys = players.getKeys(false);
        if (keys.isEmpty())
            return false;

        for (String key : keys) {
            ConfigurationSection playerSection = players.getConfigurationSection(key);
            if (playerSection == null)
                continue;

            UUID uuid = UUID.fromString(key);
            PlayerBazaar playerBazaar = new PlayerBazaar(uuid);

            String mode = playerSection.getString("mode");
            if (mode != null)
                playerBazaar.setMode(mode);

            String category = playerSection.getString("category");
            if (category != null)
                playerBazaar.setCategory(category);

            ConfigurationSection playerBuyOrders = playerSection.getConfigurationSection("buy_orders");
            if (playerBuyOrders != null) {
                List<PlayerOrder> buyOrders = new ArrayList<>();
                for (String buyOrder : playerBuyOrders.getKeys(false)) {
                    ConfigurationSection section = playerBuyOrders.getConfigurationSection(buyOrder);

                    BazaarItem bazaarItem = DeluxeBazaar.getInstance().bazaarItems.get(section.getString("item"));
                    if (bazaarItem == null)
                        continue;

                    PlayerOrder playerOrder = new PlayerOrder(UUID.fromString(buyOrder), uuid, bazaarItem, OrderType.BUY, section.getDouble("price"), section.getInt("collected"), section.getInt("filled"), section.getInt("amount"));
                    buyOrders.add(playerOrder);

                    List<OrderPrice> orderPrices = bazaarItem.getBuyPrices();
                    for (OrderPrice orderPrice : orderPrices) {
                        if (orderPrice.getPrice() != playerOrder.getPrice())
                            continue;

                        List<PlayerOrder> playerOrders = orderPrice.getPlayers().getOrDefault(uuid, new ArrayList<>());
                        playerOrders.add(playerOrder);

                        orderPrice.getPlayers().put(uuid, playerOrders);
                    }
                }

                playerBazaar.setBuyOrders(buyOrders);
            }

            ConfigurationSection playerSellOffers = playerSection.getConfigurationSection("sell_offers");
            if (playerSellOffers != null) {
                List<PlayerOrder> sellOffers = new ArrayList<>();
                for (String sellOffer : playerSellOffers.getKeys(false)) {
                    ConfigurationSection section = playerSellOffers.getConfigurationSection(sellOffer);

                    BazaarItem bazaarItem = DeluxeBazaar.getInstance().bazaarItems.get(section.getString("item"));
                    if (bazaarItem == null)
                        continue;

                    PlayerOrder playerOrder = new PlayerOrder(UUID.fromString(sellOffer), uuid, bazaarItem, OrderType.SELL, section.getDouble("price"), section.getInt("collected"), section.getInt("filled"), section.getInt("amount"));
                    sellOffers.add(playerOrder);

                    List<OrderPrice> orderPrices = bazaarItem.getSellPrices();
                    for (OrderPrice orderPrice : orderPrices) {
                        if (orderPrice.getPrice() != playerOrder.getPrice())
                            continue;

                        List<PlayerOrder> playerOrders = orderPrice.getPlayers().getOrDefault(uuid, new ArrayList<>());
                        playerOrders.add(playerOrder);

                        orderPrice.getPlayers().put(uuid, playerOrders);
                    }
                }

                playerBazaar.setSellOffers(sellOffers);
            }
        }

        return true;
    }
}