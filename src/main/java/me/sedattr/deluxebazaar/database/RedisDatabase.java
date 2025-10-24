package me.sedattr.deluxebazaar.database;

import me.sedattr.deluxebazaar.DeluxeBazaar;
import me.sedattr.deluxebazaar.managers.BazaarItem;
import me.sedattr.deluxebazaar.managers.OrderPrice;
import me.sedattr.deluxebazaar.managers.OrderType;
import me.sedattr.deluxebazaar.managers.PlayerBazaar;
import me.sedattr.deluxebazaar.managers.PlayerOrder;
import me.sedattr.deluxebazaar.menus.OrdersMenu;
import me.sedattr.deluxebazaar.others.Logger;
import me.sedattr.deluxebazaar.others.TaskUtils;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import redis.clients.jedis.JedisPubSub;

import java.util.*;

public class RedisDatabase implements DatabaseManager {
    private JedisPool jedisPool;
    private final String host;
    private final int port;
    private final String password;
    private final int database;
    private final String channelPrefix;
    private final boolean useSsl;
    private JedisPubSub subscriber;
    private boolean connected = false;

    private final Set<String> recentLocalUpdates = Collections.synchronizedSet(new HashSet<>());
    private final String serverId = UUID.randomUUID().toString().substring(0, 8);

    public RedisDatabase() {
        this.host = DeluxeBazaar.getInstance().configFile.getString("database.redis_settings.host", "localhost");
        this.port = DeluxeBazaar.getInstance().configFile.getInt("database.redis_settings.port", 6379);
        this.password = DeluxeBazaar.getInstance().configFile.getString("database.redis_settings.password", "");
        this.database = DeluxeBazaar.getInstance().configFile.getInt("database.redis_settings.database", 0);
        this.channelPrefix = DeluxeBazaar.getInstance().configFile.getString("database.redis_settings.channel_prefix", "deluxebazaar:");
        this.useSsl = DeluxeBazaar.getInstance().configFile.getBoolean("database.redis_settings.use_ssl", false);

        try {
            JedisPoolConfig poolConfig = new JedisPoolConfig();
            poolConfig.setMaxTotal(20);
            poolConfig.setMaxIdle(10);
            poolConfig.setMinIdle(5);
            poolConfig.setTestOnBorrow(true);

            if (password != null && !password.isEmpty()) {
                this.jedisPool = new JedisPool(poolConfig, host, port, 2000, password, database, useSsl);
            } else {
                this.jedisPool = new JedisPool(poolConfig, host, port, 2000, null, database, useSsl);
            }

            try (Jedis jedis = jedisPool.getResource()) {
                String response = jedis.ping();
                if ("PONG".equals(response)) {
                    connected = true;
                    Logger.sendConsoleMessage("§a✓ Redis connected at " + host + ":" + port + (useSsl ? " (SSL)" : ""), Logger.LogLevel.INFO);
                    Logger.sendConsoleMessage("§aDatabase: " + database + " | Prefix: " + channelPrefix, Logger.LogLevel.INFO);

                    startSubscriber();
                }
            }

        } catch (redis.clients.jedis.exceptions.JedisConnectionException e) {
            Logger.sendConsoleMessage("§c✗ Cannot connect to Redis server at " + host + ":" + port, Logger.LogLevel.ERROR);
            Logger.sendConsoleMessage("§cError: " + e.getMessage(), Logger.LogLevel.ERROR);
            if (useSsl) {
                Logger.sendConsoleMessage("§cSSL is enabled - ensure your Redis server supports TLS", Logger.LogLevel.ERROR);
            }
            if (password != null && !password.isEmpty()) {
                Logger.sendConsoleMessage("§cAuthentication configured - ensure password is correct", Logger.LogLevel.ERROR);
            } else {
                Logger.sendConsoleMessage("§cNo password set - use password from Upstash dashboard", Logger.LogLevel.ERROR);
            }
            Logger.sendConsoleMessage("§cFalling back to local-only mode (no cross-server sync)", Logger.LogLevel.WARN);
        } catch (Exception e) {
            Logger.sendConsoleMessage("§c✗ Redis connection failed: " + e.getMessage(), Logger.LogLevel.ERROR);
            Logger.sendConsoleMessage("§cFalling back to local-only mode", Logger.LogLevel.WARN);
            e.printStackTrace();
        }
    }

    private void startSubscriber() {
        TaskUtils.runAsync(() -> {
            try (Jedis jedis = jedisPool.getResource()) {
                subscriber = new JedisPubSub() {
                    @Override
                    public void onPMessage(String pattern, String channel, String message) {
                        handleRedisMessage(channel, message);
                    }
                };

                Logger.sendConsoleMessage("§eStarting Redis subscriber for patterns: " + channelPrefix + "*", Logger.LogLevel.INFO);
                jedis.psubscribe(subscriber,
                        channelPrefix + "player:*",
                        channelPrefix + "item:*",
                        channelPrefix + "price:*");

            } catch (Exception e) {
                Logger.sendConsoleMessage("§cRedis subscriber error: " + e.getMessage(), Logger.LogLevel.ERROR);
                e.printStackTrace();
            }
        });
    }

    private void handleRedisMessage(String channel, String message) {
        try {
            Logger.sendConsoleMessage("§e[DEBUG] handleRedisMessage: Received on channel=" + channel + ", message=" + message, Logger.LogLevel.INFO);

            if (message != null && message.equals(serverId)) {
                Logger.sendConsoleMessage("§e[DEBUG] handleRedisMessage: Ignoring own server's update (serverId=" + serverId + ")", Logger.LogLevel.INFO);
                return;
            }

            String updateKey = channel.substring(channelPrefix.length());
            if (recentLocalUpdates.contains(updateKey)) {
                Logger.sendConsoleMessage("§e[DEBUG] handleRedisMessage: Ignoring recent local update (key=" + updateKey + ")", Logger.LogLevel.INFO);
                recentLocalUpdates.remove(updateKey);
                return;
            }

            if (channel.startsWith(channelPrefix + "player:")) {
                String uuidStr = channel.substring((channelPrefix + "player:").length());
                UUID uuid = UUID.fromString(uuidStr);
                Logger.sendConsoleMessage("§e[DEBUG] handleRedisMessage: Loading player " + uuid, Logger.LogLevel.INFO);
                loadPlayerFromRedis(uuid);

                PlayerBazaar playerBazaar = DeluxeBazaar.getInstance().players.get(uuid);
                if (playerBazaar != null) {
                    Logger.sendConsoleMessage("§e[DEBUG] handleRedisMessage: Rebuilding mappings for player", Logger.LogLevel.INFO);
                    rebuildPlayerOrderMappings(uuid, playerBazaar);
                    refreshPlayerOrdersMenu(uuid);
                } else {
                    Logger.sendConsoleMessage("§c[DEBUG] handleRedisMessage: PlayerBazaar is NULL after load!", Logger.LogLevel.WARN);
                }

                Logger.sendConsoleMessage("§a[DEBUG] handleRedisMessage: Completed player data load", Logger.LogLevel.INFO);
            } else if (channel.startsWith(channelPrefix + "item:")) {
                String itemName = channel.substring((channelPrefix + "item:").length());
                loadItemFromRedis(itemName);
                Logger.sendConsoleMessage("§a[Redis] Loaded item data: " + itemName, Logger.LogLevel.INFO);
            } else if (channel.startsWith(channelPrefix + "price:")) {
                String itemName = channel.substring((channelPrefix + "price:").length());
                loadItemPriceFromRedis(itemName);
                Logger.sendConsoleMessage("§a[Redis] Loaded price data: " + itemName, Logger.LogLevel.INFO);
            }
        } catch (Exception e) {
            Logger.sendConsoleMessage("§cError handling Redis message: " + e.getMessage(), Logger.LogLevel.ERROR);
            e.printStackTrace();
        }
    }

    @Override
    public boolean saveDatabase() {
        if (!connected) {
            Logger.sendConsoleMessage("§eRedis not connected - skipping save", Logger.LogLevel.WARN);
            return false;
        }

        try {
            for (Map.Entry<String, BazaarItem> item : DeluxeBazaar.getInstance().bazaarItems.entrySet())
                saveItem(item.getKey(), item.getValue());
            for (Map.Entry<UUID, PlayerBazaar> entry : DeluxeBazaar.getInstance().players.entrySet())
                savePlayer(entry.getKey(), entry.getValue());
            return true;
        } catch (Exception e) {
            Logger.sendConsoleMessage("§cError saving to Redis: " + e.getMessage(), Logger.LogLevel.ERROR);
            return false;
        }
    }

    @Override
    public boolean loadDatabase() {
        if (!connected) {
            Logger.sendConsoleMessage("§eRedis not connected - skipping load", Logger.LogLevel.WARN);
            return false;
        }
        boolean success = loadItems() && loadPlayers();

        if (success) {
            rebuildOrderPlayerMappings();
        }

        return success;
    }

    public void savePlayer(UUID uuid, PlayerBazaar playerBazaar) {
        if (!connected) return;

        Logger.sendConsoleMessage("§e[DEBUG] savePlayer: Called for UUID=" + uuid + ", buyOrders=" + playerBazaar.getBuyOrders().size() + ", sellOffers=" + playerBazaar.getSellOffers().size(), Logger.LogLevel.INFO);

        try (Jedis jedis = jedisPool.getResource()) {
            String key = "bazaar:player:" + uuid.toString();

            Map<String, String> data = new HashMap<>();
            data.put("mode", playerBazaar.getMode());
            data.put("category", playerBazaar.getCategory());

            StringBuilder buyOrders = new StringBuilder();
            for (PlayerOrder order : playerBazaar.getBuyOrders()) {
                if (buyOrders.length() > 0) buyOrders.append(",,");
                buyOrders.append(order.getUuid()).append(",")
                        .append(order.getPlayer()).append(",")
                        .append(order.getItem().getName()).append(",")
                        .append(order.getType().name()).append(",")
                        .append(order.getPrice()).append(",")
                        .append(order.getCollected()).append(",")
                        .append(order.getFilled()).append(",")
                        .append(order.getAmount());
                Logger.sendConsoleMessage("§e[DEBUG] savePlayer: Buy order - filled=" + order.getFilled() + "/" + order.getAmount() + ", price=" + order.getPrice(), Logger.LogLevel.INFO);
            }
            data.put("buy_orders", buyOrders.toString());

            StringBuilder sellOffers = new StringBuilder();
            for (PlayerOrder order : playerBazaar.getSellOffers()) {
                if (sellOffers.length() > 0) sellOffers.append(",,");
                sellOffers.append(order.getUuid()).append(",")
                        .append(order.getPlayer()).append(",")
                        .append(order.getItem().getName()).append(",")
                        .append(order.getType().name()).append(",")
                        .append(order.getPrice()).append(",")
                        .append(order.getCollected()).append(",")
                        .append(order.getFilled()).append(",")
                        .append(order.getAmount());
                Logger.sendConsoleMessage("§e[DEBUG] savePlayer: Sell offer - filled=" + order.getFilled() + "/" + order.getAmount() + ", price=" + order.getPrice(), Logger.LogLevel.INFO);
            }
            data.put("sell_offers", sellOffers.toString());

            jedis.hset(key, data);
            Logger.sendConsoleMessage("§e[DEBUG] savePlayer: Saved to Redis key=" + key, Logger.LogLevel.INFO);

            String updateKey = "player:" + uuid.toString();
            recentLocalUpdates.add(updateKey);

            long subscribers = jedis.publish(channelPrefix + updateKey, serverId);
            Logger.sendConsoleMessage("§e[DEBUG] savePlayer: Published update to " + subscribers + " subscribers (serverId=" + serverId + ")", Logger.LogLevel.INFO);

            if (DeluxeBazaar.getInstance().isEnabled()) {
                TaskUtils.runAsync(() -> {
                    try {
                        Thread.sleep(2000);
                        recentLocalUpdates.remove(updateKey);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
            } else {
                recentLocalUpdates.remove(updateKey);
            }
        } catch (Exception e) {
            Logger.sendConsoleMessage("§cError saving player to Redis: " + e.getMessage(), Logger.LogLevel.ERROR);
            e.printStackTrace();
        }
    }

    public void saveItem(String name, BazaarItem bazaarItem) {
        if (!connected) return;

        try (Jedis jedis = jedisPool.getResource()) {
            String key = "bazaar:item:" + name;

            Map<String, String> data = new HashMap<>();

            StringBuilder buyPrices = new StringBuilder();
            for (OrderPrice orderPrice : bazaarItem.getBuyPrices()) {
                if (buyPrices.length() > 0) buyPrices.append(",,");
                buyPrices.append(orderPrice.getUuid()).append(",")
                        .append(orderPrice.getType().name()).append(",")
                        .append(orderPrice.getPrice()).append(",")
                        .append(orderPrice.getOrderAmount()).append(",")
                        .append(orderPrice.getItemAmount());
            }
            data.put("buy_prices", buyPrices.toString());

            StringBuilder sellPrices = new StringBuilder();
            for (OrderPrice orderPrice : bazaarItem.getSellPrices()) {
                if (sellPrices.length() > 0) sellPrices.append(",,");
                sellPrices.append(orderPrice.getUuid()).append(",")
                        .append(orderPrice.getType().name()).append(",")
                        .append(orderPrice.getPrice()).append(",")
                        .append(orderPrice.getOrderAmount()).append(",")
                        .append(orderPrice.getItemAmount());
            }
            data.put("sell_prices", sellPrices.toString());

            jedis.hset(key, data);
            String updateKey = "item:" + name;
            recentLocalUpdates.add(updateKey);

            long subscribers = jedis.publish(channelPrefix + updateKey, serverId);
            if (DeluxeBazaar.getInstance().configFile.getBoolean("settings.enable_log", false)) {
                Logger.sendConsoleMessage("§7[Redis] Published item update: " + name + " (subscribers: " + subscribers + ", serverId=" + serverId + ")", Logger.LogLevel.INFO);
            }

            if (DeluxeBazaar.getInstance().isEnabled()) {
                TaskUtils.runAsync(() -> {
                    try {
                        Thread.sleep(2000);
                        recentLocalUpdates.remove(updateKey);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
            } else {
                recentLocalUpdates.remove(updateKey);
            }
        } catch (Exception e) {
            Logger.sendConsoleMessage("§cError saving item to Redis: " + e.getMessage(), Logger.LogLevel.ERROR);
            e.printStackTrace();
        }
    }

    public void saveItemPrice(String name, BazaarItem bazaarItem) {
        if (!connected) return;

        try (Jedis jedis = jedisPool.getResource()) {
            String key = "bazaar:price:" + name;

            Map<String, String> data = new HashMap<>();
            data.put("buy_price", String.valueOf(bazaarItem.getBuyPrice()));
            data.put("sell_price", String.valueOf(bazaarItem.getSellPrice()));
            data.put("buy_amount", String.valueOf(bazaarItem.getTotalBuyCount()));
            data.put("sell_amount", String.valueOf(bazaarItem.getTotalSellCount()));

            jedis.hset(key, data);
            String updateKey = "price:" + name;
            recentLocalUpdates.add(updateKey);

            long subscribers = jedis.publish(channelPrefix + updateKey, serverId);
            if (DeluxeBazaar.getInstance().configFile.getBoolean("settings.enable_log", false)) {
                Logger.sendConsoleMessage("§7[Redis] Published price update: " + name + " (subscribers: " + subscribers + ", serverId=" + serverId + ")", Logger.LogLevel.INFO);
            }

            if (DeluxeBazaar.getInstance().isEnabled()) {
                TaskUtils.runAsync(() -> {
                    try {
                        Thread.sleep(2000);
                        recentLocalUpdates.remove(updateKey);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
            } else {
                recentLocalUpdates.remove(updateKey);
            }
        } catch (Exception e) {
            Logger.sendConsoleMessage("§cError saving item price to Redis: " + e.getMessage(), Logger.LogLevel.ERROR);
            e.printStackTrace();
        }
    }

    public boolean loadItems() {
        try (Jedis jedis = jedisPool.getResource()) {
            Set<String> keys = jedis.keys("bazaar:item:*");

            for (String key : keys) {
                String itemName = key.substring("bazaar:item:".length());
                BazaarItem bazaarItem = DeluxeBazaar.getInstance().bazaarItems.get(itemName);
                if (bazaarItem == null) continue;

                Map<String, String> data = jedis.hgetAll(key);

                String buyPricesStr = data.get("buy_prices");
                if (buyPricesStr != null && !buyPricesStr.isEmpty()) {
                    bazaarItem.getBuyPrices().clear();
                    String[] buyPricesArray = buyPricesStr.split(",,");
                    for (String priceData : buyPricesArray) {
                        String[] parts = priceData.split(",");
                        if (parts.length >= 5) {
                            UUID uuid = UUID.fromString(parts[0]);
                            OrderType type = OrderType.valueOf(parts[1]);
                            double price = Double.parseDouble(parts[2]);
                            int orderAmount = Integer.parseInt(parts[3]);
                            int itemAmount = Integer.parseInt(parts[4]);
                            bazaarItem.getBuyPrices().add(new OrderPrice(uuid, type, price, orderAmount, itemAmount));
                        }
                    }
                }

                String sellPricesStr = data.get("sell_prices");
                if (sellPricesStr != null && !sellPricesStr.isEmpty()) {
                    bazaarItem.getSellPrices().clear();
                    String[] sellPricesArray = sellPricesStr.split(",,");
                    for (String priceData : sellPricesArray) {
                        String[] parts = priceData.split(",");
                        if (parts.length >= 5) {
                            UUID uuid = UUID.fromString(parts[0]);
                            OrderType type = OrderType.valueOf(parts[1]);
                            double price = Double.parseDouble(parts[2]);
                            int orderAmount = Integer.parseInt(parts[3]);
                            int itemAmount = Integer.parseInt(parts[4]);
                            bazaarItem.getSellPrices().add(new OrderPrice(uuid, type, price, orderAmount, itemAmount));
                        }
                    }
                }

                String priceKey = "bazaar:price:" + itemName;
                Map<String, String> priceData = jedis.hgetAll(priceKey);
                if (!priceData.isEmpty()) {
                    String buyPrice = priceData.get("buy_price");
                    if (buyPrice != null) bazaarItem.setBuyPrice(Double.parseDouble(buyPrice));

                    String sellPrice = priceData.get("sell_price");
                    if (sellPrice != null) bazaarItem.setSellPrice(Double.parseDouble(sellPrice));

                    String buyAmount = priceData.get("buy_amount");
                    if (buyAmount != null) bazaarItem.setTotalBuyCount(Integer.parseInt(buyAmount));

                    String sellAmount = priceData.get("sell_amount");
                    if (sellAmount != null) bazaarItem.setTotalSellCount(Integer.parseInt(sellAmount));
                }
            }

            return true;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    public boolean loadPlayers() {
        try (Jedis jedis = jedisPool.getResource()) {
            Set<String> keys = jedis.keys("bazaar:player:*");

            for (String key : keys) {
                String uuidStr = key.substring("bazaar:player:".length());
                UUID uuid = UUID.fromString(uuidStr);

                Map<String, String> data = jedis.hgetAll(key);
                Logger.sendConsoleMessage("§e[DEBUG] loadPlayerFromRedis: Loaded data from Redis, has data=" + !data.isEmpty(), Logger.LogLevel.INFO);

                PlayerBazaar playerBazaar = DeluxeBazaar.getInstance().players.getOrDefault(uuid, new PlayerBazaar(uuid));

                String mode = data.get("mode");
                if (mode != null && !mode.isEmpty()) playerBazaar.setMode(mode);

                String category = data.get("category");
                if (category != null && !category.isEmpty()) playerBazaar.setCategory(category);

                String buyOrdersStr = data.get("buy_orders");
                Logger.sendConsoleMessage("§e[DEBUG] loadPlayerFromRedis: buyOrdersStr length=" + (buyOrdersStr != null ? buyOrdersStr.length() : 0), Logger.LogLevel.INFO);
                if (buyOrdersStr != null && !buyOrdersStr.isEmpty()) {
                    List<PlayerOrder> buyOrders = new ArrayList<>();
                    String[] ordersArray = buyOrdersStr.split(",,");
                    Logger.sendConsoleMessage("§e[DEBUG] loadPlayerFromRedis: Processing " + ordersArray.length + " buy orders", Logger.LogLevel.INFO);
                    for (String orderData : ordersArray) {
                        String[] parts = orderData.split(",");
                        if (parts.length >= 8) {
                            UUID orderUUID = UUID.fromString(parts[0]);
                            UUID playerUUID = UUID.fromString(parts[1]);
                            String itemName = parts[2];
                            BazaarItem item = DeluxeBazaar.getInstance().bazaarItems.get(itemName);
                            if (item == null) continue;

                            double price = Double.parseDouble(parts[4]);
                            int collected = Integer.parseInt(parts[5]);
                            int filled = Integer.parseInt(parts[6]);
                            int amount = Integer.parseInt(parts[7]);

                            Logger.sendConsoleMessage("§e[DEBUG] loadPlayerFromRedis: Buy order - filled=" + filled + "/" + amount + ", price=" + price, Logger.LogLevel.INFO);

                            PlayerOrder order = new PlayerOrder(orderUUID, playerUUID, item, OrderType.BUY, price, collected, filled, amount);
                            buyOrders.add(order);
                        }
                    }
                    playerBazaar.setBuyOrders(buyOrders);
                }

                String sellOffersStr = data.get("sell_offers");
                Logger.sendConsoleMessage("§e[DEBUG] loadPlayerFromRedis: sellOffersStr length=" + (sellOffersStr != null ? sellOffersStr.length() : 0), Logger.LogLevel.INFO);
                if (sellOffersStr != null && !sellOffersStr.isEmpty()) {
                    List<PlayerOrder> sellOffers = new ArrayList<>();
                    String[] ordersArray = sellOffersStr.split(",,");
                    Logger.sendConsoleMessage("§e[DEBUG] loadPlayerFromRedis: Processing " + ordersArray.length + " sell offers", Logger.LogLevel.INFO);
                    for (String orderData : ordersArray) {
                        String[] parts = orderData.split(",");
                        if (parts.length >= 8) {
                            UUID orderUUID = UUID.fromString(parts[0]);
                            UUID playerUUID = UUID.fromString(parts[1]);
                            String itemName = parts[2];
                            BazaarItem item = DeluxeBazaar.getInstance().bazaarItems.get(itemName);
                            if (item == null) continue;

                            double price = Double.parseDouble(parts[4]);
                            int collected = Integer.parseInt(parts[5]);
                            int filled = Integer.parseInt(parts[6]);
                            int amount = Integer.parseInt(parts[7]);

                            Logger.sendConsoleMessage("§e[DEBUG] loadPlayerFromRedis: Sell offer - filled=" + filled + "/" + amount + ", price=" + price, Logger.LogLevel.INFO);

                            PlayerOrder order = new PlayerOrder(orderUUID, playerUUID, item, OrderType.SELL, price, collected, filled, amount);
                            sellOffers.add(order);
                        }
                    }
                    playerBazaar.setSellOffers(sellOffers);
                }

                DeluxeBazaar.getInstance().players.put(uuid, playerBazaar);
                Logger.sendConsoleMessage("§e[DEBUG] loadPlayerFromRedis: Completed - buyOrders=" + playerBazaar.getBuyOrders().size() + ", sellOffers=" + playerBazaar.getSellOffers().size(), Logger.LogLevel.INFO);
            }

            return true;
        } catch (Exception e) {
            Logger.sendConsoleMessage("§c[DEBUG] loadPlayerFromRedis: Exception - " + e.getMessage(), Logger.LogLevel.ERROR);
            e.printStackTrace();
            return false;
        }
    }

    public void loadPlayerFromRedis(UUID uuid) {
        try (Jedis jedis = jedisPool.getResource()) {
            String key = "bazaar:player:" + uuid.toString();
            Map<String, String> data = jedis.hgetAll(key);

            if (data.isEmpty()) {
                Logger.sendConsoleMessage("§c[DEBUG] loadPlayerFromRedis: No data found in Redis for " + uuid, Logger.LogLevel.WARN);
                return;
            }

            PlayerBazaar playerBazaar = DeluxeBazaar.getInstance().players.getOrDefault(uuid, new PlayerBazaar(uuid));

            String mode = data.get("mode");
            if (mode != null && !mode.isEmpty()) playerBazaar.setMode(mode);

            String category = data.get("category");
            if (category != null && !category.isEmpty()) playerBazaar.setCategory(category);

            String buyOrdersStr = data.get("buy_orders");
            Logger.sendConsoleMessage("§e[DEBUG] loadPlayerFromRedis: buyOrdersStr=" + (buyOrdersStr != null ? buyOrdersStr : "null"), Logger.LogLevel.INFO);
            if (buyOrdersStr != null && !buyOrdersStr.isEmpty()) {
                List<PlayerOrder> buyOrders = new ArrayList<>();
                String[] ordersArray = buyOrdersStr.split(",,");
                Logger.sendConsoleMessage("§e[DEBUG] loadPlayerFromRedis: Found " + ordersArray.length + " buy orders", Logger.LogLevel.INFO);
                for (String orderData : ordersArray) {
                    String[] parts = orderData.split(",");
                    if (parts.length >= 8) {
                        UUID orderUUID = UUID.fromString(parts[0]);
                        UUID playerUUID = UUID.fromString(parts[1]);
                        String itemName = parts[2];
                        BazaarItem item = DeluxeBazaar.getInstance().bazaarItems.get(itemName);
                        if (item == null) continue;

                        double price = Double.parseDouble(parts[4]);
                        int collected = Integer.parseInt(parts[5]);
                        int filled = Integer.parseInt(parts[6]);
                        int amount = Integer.parseInt(parts[7]);

                        PlayerOrder order = new PlayerOrder(orderUUID, playerUUID, item, OrderType.BUY, price, collected, filled, amount);
                        buyOrders.add(order);
                        Logger.sendConsoleMessage("§e[DEBUG] loadPlayerFromRedis: Loaded buy order - filled=" + filled + "/" + amount + ", price=" + price, Logger.LogLevel.INFO);
                    }
                }
                playerBazaar.setBuyOrders(buyOrders);
            } else {
                playerBazaar.setBuyOrders(new ArrayList<>());
                Logger.sendConsoleMessage("§e[DEBUG] loadPlayerFromRedis: No buy orders, clearing list", Logger.LogLevel.INFO);
            }

            String sellOffersStr = data.get("sell_offers");
            Logger.sendConsoleMessage("§e[DEBUG] loadPlayerFromRedis: sellOffersStr=" + (sellOffersStr != null ? sellOffersStr : "null"), Logger.LogLevel.INFO);
            if (sellOffersStr != null && !sellOffersStr.isEmpty()) {
                List<PlayerOrder> sellOffers = new ArrayList<>();
                String[] ordersArray = sellOffersStr.split(",,");
                Logger.sendConsoleMessage("§e[DEBUG] loadPlayerFromRedis: Found " + ordersArray.length + " sell offers", Logger.LogLevel.INFO);
                for (String orderData : ordersArray) {
                    String[] parts = orderData.split(",");
                    if (parts.length >= 8) {
                        UUID orderUUID = UUID.fromString(parts[0]);
                        UUID playerUUID = UUID.fromString(parts[1]);
                        String itemName = parts[2];
                        BazaarItem item = DeluxeBazaar.getInstance().bazaarItems.get(itemName);
                        if (item == null) continue;

                        double price = Double.parseDouble(parts[4]);
                        int collected = Integer.parseInt(parts[5]);
                        int filled = Integer.parseInt(parts[6]);
                        int amount = Integer.parseInt(parts[7]);

                        PlayerOrder order = new PlayerOrder(orderUUID, playerUUID, item, OrderType.SELL, price, collected, filled, amount);
                        sellOffers.add(order);
                        Logger.sendConsoleMessage("§e[DEBUG] loadPlayerFromRedis: Loaded sell offer - filled=" + filled + "/" + amount + ", price=" + price, Logger.LogLevel.INFO);
                    }
                }
                playerBazaar.setSellOffers(sellOffers);
            } else {
                playerBazaar.setSellOffers(new ArrayList<>());
                Logger.sendConsoleMessage("§e[DEBUG] loadPlayerFromRedis: No sell offers, clearing list", Logger.LogLevel.INFO);
            }

            DeluxeBazaar.getInstance().players.put(uuid, playerBazaar);
            Logger.sendConsoleMessage("§a[DEBUG] loadPlayerFromRedis: Completed - buyOrders=" + playerBazaar.getBuyOrders().size() + ", sellOffers=" + playerBazaar.getSellOffers().size(), Logger.LogLevel.INFO);
        } catch (Exception e) {
            Logger.sendConsoleMessage("§cError loading player from Redis: " + e.getMessage(), Logger.LogLevel.ERROR);
            e.printStackTrace();
        }
    }

    private void loadItemFromRedis(String itemName) {
        try (Jedis jedis = jedisPool.getResource()) {
            String key = "bazaar:item:" + itemName;
            Map<String, String> data = jedis.hgetAll(key);

            if (data.isEmpty()) return;

            BazaarItem bazaarItem = DeluxeBazaar.getInstance().bazaarItems.get(itemName);
            if (bazaarItem == null) return;

            String buyPricesStr = data.get("buy_prices");
            List<OrderPrice> buyPrices = new ArrayList<>();
            if (buyPricesStr != null && !buyPricesStr.isEmpty()) {
                String[] pricesArray = buyPricesStr.split(",,");
                for (String priceData : pricesArray) {
                    String[] parts = priceData.split(",");
                    if (parts.length >= 5) {
                        UUID uuid = UUID.fromString(parts[0]);
                        OrderType type = OrderType.valueOf(parts[1]);
                        double price = Double.parseDouble(parts[2]);
                        int orderAmount = Integer.parseInt(parts[3]);
                        int itemAmount = Integer.parseInt(parts[4]);

                        OrderPrice orderPrice = new OrderPrice(uuid, type, price, orderAmount, itemAmount);
                        buyPrices.add(orderPrice);
                    }
                }
            }
            bazaarItem.setBuyPrices(buyPrices);

            String sellPricesStr = data.get("sell_prices");
            List<OrderPrice> sellPrices = new ArrayList<>();
            if (sellPricesStr != null && !sellPricesStr.isEmpty()) {
                String[] pricesArray = sellPricesStr.split(",,");
                for (String priceData : pricesArray) {
                    String[] parts = priceData.split(",");
                    if (parts.length >= 5) {
                        UUID uuid = UUID.fromString(parts[0]);
                        OrderType type = OrderType.valueOf(parts[1]);
                        double price = Double.parseDouble(parts[2]);
                        int orderAmount = Integer.parseInt(parts[3]);
                        int itemAmount = Integer.parseInt(parts[4]);

                        OrderPrice orderPrice = new OrderPrice(uuid, type, price, orderAmount, itemAmount);
                        sellPrices.add(orderPrice);
                    }
                }
            }
            bazaarItem.setSellPrices(sellPrices);

            String buyPrice = data.get("buy_price");
            if (buyPrice != null && !buyPrice.isEmpty()) bazaarItem.setBuyPrice(Double.parseDouble(buyPrice));

            String sellPrice = data.get("sell_price");
            if (sellPrice != null && !sellPrice.isEmpty()) bazaarItem.setSellPrice(Double.parseDouble(sellPrice));

            String totalBuyCount = data.get("total_buy_count");
            if (totalBuyCount != null && !totalBuyCount.isEmpty()) bazaarItem.setTotalBuyCount(Integer.parseInt(totalBuyCount));

            String totalSellCount = data.get("total_sell_count");
            if (totalSellCount != null && !totalSellCount.isEmpty()) bazaarItem.setTotalSellCount(Integer.parseInt(totalSellCount));

            Logger.sendConsoleMessage("§e[DEBUG] loadItemFromRedis: Rebuilding player mappings for item " + itemName, Logger.LogLevel.INFO);
            rebuildMappingsForItem(bazaarItem);
        } catch (Exception e) {
            Logger.sendConsoleMessage("§cError loading item from Redis: " + e.getMessage(), Logger.LogLevel.ERROR);
            e.printStackTrace();
        }
    }

    private void loadItemPriceFromRedis(String itemName) {
        try (Jedis jedis = jedisPool.getResource()) {
            String key = "bazaar:price:" + itemName;
            Map<String, String> data = jedis.hgetAll(key);

            if (data.isEmpty()) return;

            BazaarItem bazaarItem = DeluxeBazaar.getInstance().bazaarItems.get(itemName);
            if (bazaarItem == null) return;

            String buyPrice = data.get("buy_price");
            if (buyPrice != null) bazaarItem.setBuyPrice(Double.parseDouble(buyPrice));

            String sellPrice = data.get("sell_price");
            if (sellPrice != null) bazaarItem.setSellPrice(Double.parseDouble(sellPrice));

            String buyAmount = data.get("buy_amount");
            if (buyAmount != null) bazaarItem.setTotalBuyCount(Integer.parseInt(buyAmount));

            String sellAmount = data.get("sell_amount");
            if (sellAmount != null) bazaarItem.setTotalSellCount(Integer.parseInt(sellAmount));
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void savePlayerAsync(UUID uuid, PlayerBazaar playerBazaar) {
        boolean autoSave = DeluxeBazaar.getInstance().configFile.getBoolean("database.auto_save.player_orders", false);
        if (!autoSave) return;

        TaskUtils.runAsync(() -> savePlayer(uuid, playerBazaar));
    }

    public void saveItemAsync(String name, BazaarItem bazaarItem) {
        boolean autoSave = DeluxeBazaar.getInstance().configFile.getBoolean("database.auto_save.item_orders", false);
        if (!autoSave) return;

        TaskUtils.runAsync(() -> saveItem(name, bazaarItem));
    }

    public void saveItemPriceAsync(String name, BazaarItem bazaarItem) {
        boolean autoSave = DeluxeBazaar.getInstance().configFile.getBoolean("database.auto_save.item_prices", false);
        if (!autoSave) return;

        TaskUtils.runAsync(() -> saveItemPrice(name, bazaarItem));
    }

    /**
     * Rebuilds the OrderPrice player mappings after loading data from Redis.
     * This is necessary because Redis stores OrderPrice and PlayerOrder separately,
     * so we need to reconnect which players created which orders.
     */
    private boolean rebuildOrderPlayerMappings() {
        Logger.sendConsoleMessage("§e[Redis] Rebuilding OrderPrice player mappings...", Logger.LogLevel.INFO);

        int totalMappings = 0;

        for (Map.Entry<UUID, PlayerBazaar> playerEntry : DeluxeBazaar.getInstance().players.entrySet()) {
            UUID playerUUID = playerEntry.getKey();
            PlayerBazaar playerBazaar = playerEntry.getValue();

            totalMappings += rebuildPlayerOrderMappings(playerUUID, playerBazaar);
        }

        Logger.sendConsoleMessage("§a[Redis] OrderPrice player mappings rebuilt! Total mappings: " + totalMappings, Logger.LogLevel.INFO);
        return true;
    }

    /**
     * Rebuilds the OrderPrice mappings for a specific player.
     * Used when a player's data is updated via Redis pub/sub.
     * @return number of mappings created
     */
    private int rebuildPlayerOrderMappings(UUID playerUUID, PlayerBazaar playerBazaar) {
        int mappings = 0;

        for (PlayerOrder playerOrder : playerBazaar.getBuyOrders()) {
            if (addPlayerToOrderPrice(playerUUID, playerOrder)) {
                mappings++;
            }
        }

        for (PlayerOrder playerOrder : playerBazaar.getSellOffers()) {
            if (addPlayerToOrderPrice(playerUUID, playerOrder)) {
                mappings++;
            }
        }

        return mappings;
    }

    /**
     * Rebuilds the OrderPrice mappings for a specific item after it's loaded from Redis.
     * When an item is loaded, new OrderPrice objects are created, breaking existing player mappings.
     * This method iterates through all players and re-links their orders to the new OrderPrice objects.
     */
    private void rebuildMappingsForItem(BazaarItem item) {
        int mappings = 0;

        for (Map.Entry<UUID, PlayerBazaar> playerEntry : DeluxeBazaar.getInstance().players.entrySet()) {
            UUID playerUUID = playerEntry.getKey();
            PlayerBazaar playerBazaar = playerEntry.getValue();

            for (PlayerOrder playerOrder : playerBazaar.getBuyOrders()) {
                if (playerOrder.getItem().equals(item)) {
                    if (addPlayerToOrderPrice(playerUUID, playerOrder)) {
                        mappings++;
                    }
                }
            }

            for (PlayerOrder playerOrder : playerBazaar.getSellOffers()) {
                if (playerOrder.getItem().equals(item)) {
                    if (addPlayerToOrderPrice(playerUUID, playerOrder)) {
                        mappings++;
                    }
                }
            }
        }

        Logger.sendConsoleMessage("§a[DEBUG] rebuildMappingsForItem: Rebuilt " + mappings + " mappings for item " + item.getName(), Logger.LogLevel.INFO);
    }

    /**
     * Adds a player's order to the corresponding OrderPrice's player map.
     * @return true if mapping was added successfully
     */
    private boolean addPlayerToOrderPrice(UUID playerUUID, PlayerOrder playerOrder) {
        BazaarItem item = playerOrder.getItem();
        if (item == null) return false;

        List<OrderPrice> orderPrices = playerOrder.getType().equals(OrderType.BUY)
                ? item.getBuyPrices()
                : item.getSellPrices();

        for (OrderPrice orderPrice : orderPrices) {
            if (Math.abs(orderPrice.getPrice() - playerOrder.getPrice()) < 0.01) {
                List<PlayerOrder> playerOrders = orderPrice.getPlayers()
                        .getOrDefault(playerUUID, new ArrayList<>());

                if (!playerOrders.contains(playerOrder)) {
                    playerOrders.add(playerOrder);
                    orderPrice.getPlayers().put(playerUUID, playerOrders);
                    return true;
                }
                break;
            }
        }
        return false;
    }

    private void refreshPlayerOrdersMenu(UUID uuid) {
        TaskUtils.run(() -> {
            Player player = Bukkit.getPlayer(uuid);
            if (player == null || !player.isOnline()) {
                return;
            }

            if (player.getOpenInventory() != null && player.getOpenInventory().getTitle() != null) {
                String title = player.getOpenInventory().getTitle();

                String ordersMenuTitle = DeluxeBazaar.getInstance().menusFile.getString("orders.title");
                if (ordersMenuTitle != null && title.contains(me.sedattr.deluxebazaar.others.Utils.colorize(ordersMenuTitle))) {
                    OrdersMenu ordersMenu = new OrdersMenu(player);
                    ordersMenu.openMenu(1);

                    if (DeluxeBazaar.getInstance().configFile.getBoolean("settings.enable_log", false)) {
                        Logger.sendConsoleMessage("§7[Redis] Refreshed orders menu for player: " + player.getName(), Logger.LogLevel.INFO);
                    }
                }
            }
        });
    }

    public void close() {
        if (subscriber != null) {
            subscriber.unsubscribe();
        }
        if (jedisPool != null && !jedisPool.isClosed()) {
            jedisPool.close();
        }
    }
}
