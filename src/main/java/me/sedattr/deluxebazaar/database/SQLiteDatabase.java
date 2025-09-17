package me.sedattr.deluxebazaar.database;

import me.sedattr.deluxebazaar.DeluxeBazaar;
import me.sedattr.deluxebazaar.managers.*;

import java.io.File;
import java.io.IOException;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class SQLiteDatabase implements DatabaseManager {
    private final File file;
    private Connection connection;

    public SQLiteDatabase() {
        this.file = new File(DeluxeBazaar.getInstance().getDataFolder(), "database.db");
        if (!this.file.exists()) {
            try {
                this.file.createNewFile();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        this.connection = getConnection();
        if (this.connection == null)
            return;

        try (
                PreparedStatement statement1 = connection.prepareStatement("CREATE TABLE IF NOT EXISTS BazaarItems (" +
                        "name VARCHAR(36), " +
                        "buy_price DOUBLE, " +
                        "sell_price DOUBLE, " +
                        "buy_amount INTEGER, " +
                        "sell_amount INTEGER, " +
                        "buy_prices TEXT, " +
                        "sell_prices TEXT)");

                PreparedStatement statement2 = connection.prepareStatement("CREATE TABLE IF NOT EXISTS BazaarPlayers (" +
                        "uuid VARCHAR(36), " +
                        "mode TEXT, " +
                        "category TEXT, " +
                        "buy_orders TEXT, " +
                        "sell_offers TEXT)")
        ) {
            statement1.execute();
            statement2.execute();
        } catch (Exception x) {
            x.printStackTrace();
        }
    }

    @Override
    public boolean saveDatabase() {
        for (Map.Entry<String, BazaarItem> item : DeluxeBazaar.getInstance().bazaarItems.entrySet())
            saveItem(item.getKey(), item.getValue());
        for (Map.Entry<UUID, PlayerBazaar> entry : DeluxeBazaar.getInstance().players.entrySet())
            savePlayer(entry.getKey(), entry.getValue());

        return true;
    }

    @Override
    public boolean loadDatabase() {
        return loadItems() && loadPlayers();
    }

    public Connection getConnection() {
        try {
            if (this.connection != null)
                this.connection.close();

            Class.forName("org.sqlite.JDBC");
            return this.connection = DriverManager.getConnection("jdbc:sqlite:" + this.file.getPath());
        } catch (SQLException | ClassNotFoundException throwable) {
            throwable.printStackTrace();
        }

        return null;
    }

    public void savePlayer(UUID uuid, PlayerBazaar playerBazaar) {
        if (playerBazaar == null)
            playerBazaar = DeluxeBazaar.getInstance().players.get(uuid);
        if (playerBazaar == null)
            return;

        StringBuilder buyOrders = new StringBuilder();
        if (!playerBazaar.getBuyOrders().isEmpty()) {
            for (PlayerOrder order : playerBazaar.getBuyOrders()) {
                String string = order.toString();

                buyOrders.append(",,").append(string);
            }

            buyOrders.delete(0, 2);
        }

        StringBuilder sellOffers = new StringBuilder();
        if (!playerBazaar.getSellOffers().isEmpty()) {
            for (PlayerOrder order : playerBazaar.getSellOffers()) {
                String string = order.toString();

                sellOffers.append(",,").append(string);
            }
            sellOffers.delete(0, 2);
        }

        String updateSQL = "UPDATE BazaarPlayers set mode = ?, category = ?, buy_orders = ?, sell_offers = ? where uuid = ?";
        String insertSQL = "INSERT INTO BazaarPlayers (uuid, mode, category, buy_orders, sell_offers) VALUES (?, ?, ?, ?, ?)";
        try (
                Connection connection = getConnection();
                PreparedStatement update = connection.prepareStatement(updateSQL);
                PreparedStatement insert = connection.prepareStatement(insertSQL)
        ) {
            update.setString(1, playerBazaar.getMode());
            update.setString(2, playerBazaar.getCategory());
            update.setString(3, buyOrders.toString());
            update.setString(4, sellOffers.toString());
            update.setString(5, uuid.toString());

            int count = update.executeUpdate();
            if (count == 0) {
                insert.setString(1, uuid.toString());
                insert.setString(2, playerBazaar.getMode());
                insert.setString(3, playerBazaar.getCategory());
                insert.setString(4, buyOrders.toString());
                insert.setString(5, sellOffers.toString());

                insert.executeUpdate();
            }
        } catch (Exception x) {
            x.printStackTrace();
        }
    }

    String user = "%%__BUILTBYBIT__%%";
    public void saveItem(String name, BazaarItem bazaarItem) {
        if (bazaarItem == null)
            bazaarItem = DeluxeBazaar.getInstance().bazaarItems.get(name);
        if (bazaarItem == null)
            return;

        StringBuilder buyPrices = new StringBuilder();
        if (!bazaarItem.getBuyPrices().isEmpty()) {
            for (OrderPrice orderPrice : bazaarItem.getBuyPrices()) {
                String string = orderPrice.toString();

                buyPrices.append(",,").append(string);
            }

            buyPrices.delete(0, 2);
        }

        StringBuilder sellPrices = new StringBuilder();
        if (!bazaarItem.getSellPrices().isEmpty()) {
            for (OrderPrice orderPrice : bazaarItem.getSellPrices()) {
                String string = orderPrice.toString();

                sellPrices.append(",,").append(string);
            }
            sellPrices.delete(0, 2);
        }

        String updateSQL = "UPDATE BazaarItems set buy_price = ?, sell_price = ?, buy_amount = ?, sell_amount = ?, buy_prices = ?, sell_prices = ? where name = ?";
        String insertSQL = "INSERT INTO BazaarItems (name, buy_price, sell_price, buy_amount, sell_amount, buy_prices, sell_prices) VALUES (?, ?, ?, ?, ?, ?, ?)";
        try (
                Connection connection = getConnection();
                PreparedStatement update = connection.prepareStatement(updateSQL);
                PreparedStatement insert = connection.prepareStatement(insertSQL)
        ) {
            update.setDouble(1, bazaarItem.getBuyPrice());
            update.setDouble(2, bazaarItem.getSellPrice());
            update.setInt(3, bazaarItem.getTotalBuyCount());
            update.setInt(4, bazaarItem.getTotalSellCount());
            update.setString(5, buyPrices.toString());
            update.setString(6, sellPrices.toString());
            update.setString(7, name);

            int count = update.executeUpdate();
            if (count == 0) {
                insert.setString(1, name);
                insert.setDouble(2, bazaarItem.getBuyPrice());
                insert.setDouble(3, bazaarItem.getSellPrice());
                insert.setInt(4, bazaarItem.getTotalBuyCount());
                insert.setInt(5, bazaarItem.getTotalSellCount());
                insert.setString(6, buyPrices.toString());
                insert.setString(7, sellPrices.toString());

                insert.executeUpdate();
            }
        } catch (Exception x) {
            x.printStackTrace();
        }
    }

    public boolean loadItems() {
        String selectSQL = "SELECT * FROM BazaarItems";

        try (
                Connection connection = getConnection();
                PreparedStatement select = connection.prepareStatement(selectSQL)
        ) {
            ResultSet set = select.executeQuery();

            while (set.next()) {
                BazaarItem bazaarItem = DeluxeBazaar.getInstance().bazaarItems.get(set.getString("name"));
                if (bazaarItem == null)
                    continue;

                double buyPrice = set.getDouble("buy_price");
                if (buyPrice > 0)
                    bazaarItem.setBuyPrice(buyPrice);
                double sellPrice = set.getDouble("sell_price");
                if (sellPrice > 0)
                    bazaarItem.setSellPrice(sellPrice);

                int buyAmount = set.getInt("buy_amount");
                if (buyAmount > 0)
                    bazaarItem.setTotalBuyCount(buyAmount);
                int sellAmount = set.getInt("sell_amount");
                if (sellAmount > 0)
                    bazaarItem.setTotalSellCount(sellAmount);

                String itemBuyPrices = set.getString("buy_prices");
                if (itemBuyPrices != null) {
                    String[] args = itemBuyPrices.split(",,");
                    for (String arg : args) {
                        String[] newArgs = arg.split(",");
                        if (newArgs.length < 5)
                            continue;

                        UUID uuid = UUID.fromString(newArgs[0]);
                        double price = Double.parseDouble(newArgs[2]);
                        if (price <= 0)
                            continue;

                        int orderAmount = Integer.parseInt(newArgs[3]);
                        if (orderAmount <= 0)
                            continue;

                        int itemAmount = Integer.parseInt(newArgs[4]);
                        if (itemAmount <= 0)
                            continue;

                        bazaarItem.getBuyPrices().add(new OrderPrice(uuid, OrderType.BUY, price, orderAmount, itemAmount));
                    }
                }

                String itemSellPrices = set.getString("sell_prices");
                if (itemSellPrices != null) {
                    String[] args = itemSellPrices.split(",,");
                    for (String arg : args) {
                        String[] newArgs = arg.split(",");
                        if (newArgs.length < 5)
                            continue;

                        UUID uuid = UUID.fromString(newArgs[0]);
                        double price = Double.parseDouble(newArgs[2]);
                        if (price <= 0)
                            continue;

                        int orderAmount = Integer.parseInt(newArgs[3]);
                        if (orderAmount <= 0)
                            continue;

                        int itemAmount = Integer.parseInt(newArgs[4]);
                        if (itemAmount <= 0)
                            continue;

                        bazaarItem.getSellPrices().add(new OrderPrice(uuid, OrderType.SELL, price, orderAmount, itemAmount));
                    }
                }
            }
        } catch (Exception x) {
            x.printStackTrace();
            return false;
        }

        return true;
    }

    public boolean loadPlayers() {
        String selectSQL = "SELECT * FROM BazaarPlayers";

        try (
                Connection connection = getConnection();
                PreparedStatement select = connection.prepareStatement(selectSQL)
        ) {
            ResultSet set = select.executeQuery();

            while (set.next()) {
                UUID uuid = UUID.fromString(set.getString("uuid"));

                PlayerBazaar playerBazaar = DeluxeBazaar.getInstance().players.getOrDefault(uuid, new PlayerBazaar(uuid));
                String mode = set.getString("mode");
                if (mode != null && !mode.equals(""))
                    playerBazaar.setMode(mode);

                String category = set.getString("category");
                if (category != null && !category.equals(""))
                    playerBazaar.setCategory(category);

                String playerBuyOrders = set.getString("buy_orders");
                if (playerBuyOrders != null) {
                    List<PlayerOrder> buyOrders = new ArrayList<>();
                    String[] args = playerBuyOrders.split(",,");
                    for (String arg : args) {
                        String[] newArgs = arg.split(",");
                        if (newArgs.length < 8)
                            continue;

                        UUID orderUUID = UUID.fromString(newArgs[0]);
                        String itemName = newArgs[2];
                        if (itemName == null || itemName.equals(""))
                            continue;

                        BazaarItem item = DeluxeBazaar.getInstance().bazaarItems.get(itemName);
                        if (item == null)
                            continue;

                        double price = Double.parseDouble(newArgs[4]);
                        if (price <= 0)
                            continue;

                        int amount = Integer.parseInt(newArgs[7]);
                        if (amount <= 0)
                            continue;

                        int collected = Integer.parseInt(newArgs[5]);
                        int filled = Integer.parseInt(newArgs[6]);

                        PlayerOrder playerOrder = new PlayerOrder(orderUUID, uuid, item, OrderType.BUY, price, collected, filled, amount);
                        buyOrders.add(playerOrder);

                        List<OrderPrice> orderPrices = item.getBuyPrices();
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

                String playerSellOffers = set.getString("sell_offers");
                if (playerSellOffers != null) {
                    List<PlayerOrder> sellOffers = new ArrayList<>();
                    String[] args = playerSellOffers.split(",,");
                    for (String arg : args) {
                        String[] newArgs = arg.split(",");
                        if (newArgs.length < 8)
                            continue;

                        UUID orderUUID = UUID.fromString(newArgs[0]);
                        String itemName = newArgs[2];
                        if (itemName == null || itemName.equals(""))
                            continue;

                        BazaarItem item = DeluxeBazaar.getInstance().bazaarItems.get(itemName);
                        if (item == null)
                            continue;

                        double price = Double.parseDouble(newArgs[4]);
                        if (price <= 0)
                            continue;

                        int amount = Integer.parseInt(newArgs[7]);
                        if (amount <= 0)
                            continue;

                        int collected = Integer.parseInt(newArgs[5]);
                        int filled = Integer.parseInt(newArgs[6]);

                        PlayerOrder playerOrder = new PlayerOrder(orderUUID, uuid, item, OrderType.SELL, price, collected, filled, amount);
                        sellOffers.add(playerOrder);

                        List<OrderPrice> orderPrices = item.getSellPrices();
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
        } catch (Exception x) {
            x.printStackTrace();
            return false;
        }

        return true;
    }
}
