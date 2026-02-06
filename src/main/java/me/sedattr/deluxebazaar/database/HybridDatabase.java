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
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import redis.clients.jedis.JedisPubSub;

import java.sql.*;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * HybridDatabase - Combines Redis and MySQL for optimal performance and durability.
 * 
 * Architecture (similar to HuskSync):
 * - Redis: Fast in-memory cache + pub/sub message bus for real-time cross-server sync
 * - MySQL: Durable persistent storage for backup and recovery
 * 
 * Data Flow:
 * 1. On startup: Load from MySQL (source of truth) → Cache in Redis
 * 2. On change: Write to Redis (instant sync) + Queue async write to MySQL (durability)
 * 3. On shutdown: Flush write queue to MySQL
 * 
 * Sync Modes:
 * - LOCKSTEP: Coordinated writes to both Redis and MySQL (recommended)
 * - DELAY: Short delay before applying Redis updates (for high-latency networks)
 */
public class HybridDatabase implements DatabaseManager {
    
    // Redis components
    private JedisPool jedisPool;
    private JedisPubSub subscriber;
    private volatile boolean redisConnected = false;
    private final String serverId = UUID.randomUUID().toString().substring(0, 8);
    private final Set<String> recentLocalUpdates = Collections.synchronizedSet(new HashSet<>());
    private volatile boolean subscriberRunning = false;
    
    // Redis settings
    private final String redisHost;
    private final int redisPort;
    private final String redisPassword;
    private final int redisDatabase;
    private final String channelPrefix;
    private final boolean useRedisSsl;
    
    // MySQL components
    private Connection mysqlConnection;
    private final Object mysqlLock = new Object();
    private final String mysqlUrl;
    private final String mysqlUser;
    private final String mysqlPassword;
    private final String mysqlLink;
    private volatile boolean mysqlConnected = false;
    
    // Hybrid settings
    private final SyncMode syncMode;
    private final int networkLatencyMs;
    private final int mysqlWriteIntervalSeconds;
    private final boolean writeToMySqlOnChange;
    
    // Write queue for async MySQL writes
    private final ConcurrentLinkedQueue<Runnable> mysqlWriteQueue = new ConcurrentLinkedQueue<>();
    private volatile boolean writeQueueRunning = false;
    
    // Reconnection settings
    private static final int RECONNECT_DELAY_SECONDS = 30;
    private volatile boolean reconnectScheduled = false;
    
    public enum SyncMode {
        LOCKSTEP,  // Coordinated writes - recommended for most networks
        DELAY      // Apply short delay before Redis updates - for stable networks
    }
    
    public HybridDatabase() {
        ConfigurationSection config = DeluxeBazaar.getInstance().configFile;
        
        // Load Redis settings
        this.redisHost = config.getString("database.redis_settings.host", "localhost");
        this.redisPort = config.getInt("database.redis_settings.port", 6379);
        this.redisPassword = config.getString("database.redis_settings.password", "");
        this.redisDatabase = config.getInt("database.redis_settings.database", 0);
        this.channelPrefix = config.getString("database.redis_settings.channel_prefix", "deluxebazaar:");
        this.useRedisSsl = config.getBoolean("database.redis_settings.use_ssl", false);
        
        // Load MySQL settings
        ConfigurationSection mysqlSection = config.getConfigurationSection("database.mysql_settings");
        if (mysqlSection != null) {
            this.mysqlUrl = mysqlSection.getString("url");
            this.mysqlUser = mysqlSection.getString("user");
            this.mysqlPassword = mysqlSection.getString("password");
            this.mysqlLink = mysqlSection.getString("link", "com.mysql.cj.jdbc.Driver");
        } else {
            this.mysqlUrl = null;
            this.mysqlUser = null;
            this.mysqlPassword = null;
            this.mysqlLink = null;
        }
        
        // Load hybrid settings
        String syncModeStr = config.getString("database.hybrid_settings.sync_mode", "LOCKSTEP");
        this.syncMode = SyncMode.valueOf(syncModeStr.toUpperCase());
        this.networkLatencyMs = config.getInt("database.hybrid_settings.network_latency_milliseconds", 100);
        this.mysqlWriteIntervalSeconds = config.getInt("database.hybrid_settings.mysql_write_interval", 30);
        this.writeToMySqlOnChange = config.getBoolean("database.hybrid_settings.write_mysql_on_change", true);
        
        // Initialize connections
        initializeRedis();
        initializeMySql();
        
        // Start background write queue processor
        if (mysqlConnected) {
            startMySqlWriteQueue();
        }
        
        // Start health check task for reconnection
        startHealthCheckTask();
        
        logConnectionStatus();
    }
    
    private void initializeRedis() {
        try {
            JedisPoolConfig poolConfig = new JedisPoolConfig();
            poolConfig.setMaxTotal(20);
            poolConfig.setMaxIdle(10);
            poolConfig.setMinIdle(5);
            poolConfig.setTestOnBorrow(true);
            poolConfig.setTestWhileIdle(true);
            poolConfig.setTimeBetweenEvictionRunsMillis(30000);
            poolConfig.setMinEvictableIdleTimeMillis(60000);
            
            if (jedisPool != null && !jedisPool.isClosed()) {
                jedisPool.close();
            }
            
            if (redisPassword != null && !redisPassword.isEmpty()) {
                this.jedisPool = new JedisPool(poolConfig, redisHost, redisPort, 5000, redisPassword, redisDatabase, useRedisSsl);
            } else {
                this.jedisPool = new JedisPool(poolConfig, redisHost, redisPort, 5000, null, redisDatabase, useRedisSsl);
            }
            
            try (Jedis jedis = jedisPool.getResource()) {
                String response = jedis.ping();
                if ("PONG".equals(response)) {
                    redisConnected = true;
                    if (!subscriberRunning) {
                        startRedisSubscriber();
                    }
                    Logger.sendConsoleMessage("§a[Hybrid] Redis connection established", Logger.LogLevel.INFO);
                }
            }
        } catch (Exception e) {
            redisConnected = false;
            Logger.sendConsoleMessage("§c[Hybrid] Redis connection failed: " + e.getMessage(), Logger.LogLevel.ERROR);
            scheduleReconnect();
        }
    }
    
    private void initializeMySql() {
        if (mysqlUrl == null || mysqlUser == null) {
            Logger.sendConsoleMessage("§c[Hybrid] MySQL settings not configured!", Logger.LogLevel.ERROR);
            return;
        }
        
        try {
            Class.forName(mysqlLink != null && !mysqlLink.isEmpty() ? mysqlLink : "com.mysql.cj.jdbc.Driver");
            synchronized (mysqlLock) {
                this.mysqlConnection = DriverManager.getConnection(mysqlUrl, mysqlUser, mysqlPassword);
            }
            
            createMySqlTables();
            mysqlConnected = true;
            Logger.sendConsoleMessage("§a[Hybrid] MySQL connection established", Logger.LogLevel.INFO);
        } catch (Exception e) {
            mysqlConnected = false;
            Logger.sendConsoleMessage("§c[Hybrid] MySQL connection failed: " + e.getMessage(), Logger.LogLevel.ERROR);
            scheduleReconnect();
        }
    }
    
    /**
     * Schedules a reconnection attempt if not already scheduled.
     */
    private void scheduleReconnect() {
        if (reconnectScheduled || !DeluxeBazaar.getInstance().isEnabled()) return;
        
        reconnectScheduled = true;
        Logger.sendConsoleMessage("§e[Hybrid] Scheduling reconnection attempt in " + RECONNECT_DELAY_SECONDS + " seconds...", Logger.LogLevel.WARN);
        
        TaskUtils.runLater(() -> {
            reconnectScheduled = false;
            attemptReconnect();
        }, RECONNECT_DELAY_SECONDS * 20L);
    }
    
    /**
     * Attempts to reconnect to disconnected databases.
     */
    private void attemptReconnect() {
        if (!DeluxeBazaar.getInstance().isEnabled()) return;
        
        TaskUtils.runAsync(() -> {
            boolean reconnected = false;
            
            if (!redisConnected) {
                Logger.sendConsoleMessage("§e[Hybrid] Attempting Redis reconnection...", Logger.LogLevel.INFO);
                initializeRedis();
                if (redisConnected) {
                    reconnected = true;
                    // Sync current data to Redis
                    syncToRedisCache();
                }
            }
            
            if (!mysqlConnected) {
                Logger.sendConsoleMessage("§e[Hybrid] Attempting MySQL reconnection...", Logger.LogLevel.INFO);
                initializeMySql();
                if (mysqlConnected && !writeQueueRunning) {
                    startMySqlWriteQueue();
                }
                reconnected = reconnected || mysqlConnected;
            }
            
            if (reconnected) {
                Logger.sendConsoleMessage("§a[Hybrid] Reconnection successful!", Logger.LogLevel.INFO);
            } else if (!redisConnected || !mysqlConnected) {
                scheduleReconnect();
            }
        });
    }
    
    /**
     * Starts a periodic health check task to monitor connections.
     */
    private void startHealthCheckTask() {
        TaskUtils.runTimer(() -> {
            TaskUtils.runAsync(() -> {
                // Check Redis health
                if (redisConnected && jedisPool != null) {
                    try (Jedis jedis = jedisPool.getResource()) {
                        jedis.ping();
                    } catch (Exception e) {
                        Logger.sendConsoleMessage("§c[Hybrid] Redis connection lost: " + e.getMessage(), Logger.LogLevel.ERROR);
                        redisConnected = false;
                        scheduleReconnect();
                    }
                }
                
                // Check MySQL health
                if (mysqlConnected) {
                    try {
                        synchronized (mysqlLock) {
                            if (mysqlConnection == null || mysqlConnection.isClosed() || !mysqlConnection.isValid(5)) {
                                throw new SQLException("Connection invalid");
                            }
                        }
                    } catch (Exception e) {
                        Logger.sendConsoleMessage("§c[Hybrid] MySQL connection lost: " + e.getMessage(), Logger.LogLevel.ERROR);
                        mysqlConnected = false;
                        scheduleReconnect();
                    }
                }
                
                // Try to reconnect if not already scheduled
                if ((!redisConnected || !mysqlConnected) && !reconnectScheduled) {
                    scheduleReconnect();
                }
            });
        }, 600L, 600L); // Check every 30 seconds
    }
    
    private void createMySqlTables() throws SQLException {
        try (PreparedStatement stmt1 = getMySqlConnection().prepareStatement(
                "CREATE TABLE IF NOT EXISTS BazaarItems (" +
                "name VARCHAR(36) PRIMARY KEY, " +
                "buy_price DOUBLE, " +
                "sell_price DOUBLE, " +
                "buy_amount INTEGER, " +
                "sell_amount INTEGER, " +
                "buy_prices TEXT, " +
                "sell_prices TEXT, " +
                "updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP)");
             PreparedStatement stmt2 = getMySqlConnection().prepareStatement(
                "CREATE TABLE IF NOT EXISTS BazaarPlayers (" +
                "uuid VARCHAR(36) PRIMARY KEY, " +
                "mode TEXT, " +
                "category TEXT, " +
                "buy_orders TEXT, " +
                "sell_offers TEXT, " +
                "updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP)")) {
            stmt1.execute();
            stmt2.execute();
        }
    }
    
    private Connection getMySqlConnection() throws SQLException {
        synchronized (mysqlLock) {
            if (mysqlConnection == null || mysqlConnection.isClosed() || !mysqlConnection.isValid(3)) {
                try {
                    Class.forName(mysqlLink != null && !mysqlLink.isEmpty() ? mysqlLink : "com.mysql.cj.jdbc.Driver");
                    mysqlConnection = DriverManager.getConnection(mysqlUrl, mysqlUser, mysqlPassword);
                    mysqlConnected = true;
                } catch (ClassNotFoundException e) {
                    mysqlConnected = false;
                    throw new SQLException("MySQL driver not found", e);
                } catch (SQLException e) {
                    mysqlConnected = false;
                    throw e;
                }
            }
            return mysqlConnection;
        }
    }
    
    private void logConnectionStatus() {
        Logger.sendConsoleMessage("§e╔════════════════════════════════════════╗", Logger.LogLevel.INFO);
        Logger.sendConsoleMessage("§e║       §6Hybrid Database Status§e          ║", Logger.LogLevel.INFO);
        Logger.sendConsoleMessage("§e╠════════════════════════════════════════╣", Logger.LogLevel.INFO);
        Logger.sendConsoleMessage("§e║ §fRedis:  " + (redisConnected ? "§a✓ Connected" : "§c✗ Disconnected") + "                §e║", Logger.LogLevel.INFO);
        Logger.sendConsoleMessage("§e║ §fMySQL:  " + (mysqlConnected ? "§a✓ Connected" : "§c✗ Disconnected") + "                §e║", Logger.LogLevel.INFO);
        Logger.sendConsoleMessage("§e║ §fMode:   §b" + syncMode.name() + "                       §e║", Logger.LogLevel.INFO);
        Logger.sendConsoleMessage("§e╚════════════════════════════════════════╝", Logger.LogLevel.INFO);
        
        if (redisConnected && mysqlConnected) {
            Logger.sendConsoleMessage("§a[Hybrid] Full hybrid mode active - Redis for real-time sync, MySQL for persistence", Logger.LogLevel.INFO);
        } else if (mysqlConnected) {
            Logger.sendConsoleMessage("§e[Hybrid] MySQL-only mode - cross-server sync will use polling", Logger.LogLevel.WARN);
        } else if (redisConnected) {
            Logger.sendConsoleMessage("§e[Hybrid] Redis-only mode - data may not persist across restarts", Logger.LogLevel.WARN);
        } else {
            Logger.sendConsoleMessage("§c[Hybrid] No database connected! Data will not persist!", Logger.LogLevel.ERROR);
        }
    }
    
    // ==================== DatabaseManager Interface ====================
    
    @Override
    public boolean loadDatabase() {
        boolean success = true;
        
        // Primary: Load from MySQL (source of truth for persistence)
        if (mysqlConnected) {
            Logger.sendConsoleMessage("§e[Hybrid] Loading data from MySQL...", Logger.LogLevel.INFO);
            success = loadFromMySql();
        }
        
        // Secondary: Sync to Redis cache (if MySQL loaded successfully)
        if (success && redisConnected) {
            Logger.sendConsoleMessage("§e[Hybrid] Syncing data to Redis cache...", Logger.LogLevel.INFO);
            syncToRedisCache();
        }
        
        // Rebuild player-order mappings
        if (success) {
            rebuildOrderPlayerMappings();
        }
        
        return success;
    }
    
    @Override
    public boolean saveDatabase() {
        boolean success = true;
        
        // Flush any pending writes
        flushWriteQueue();
        
        // Save to MySQL (persistent storage)
        if (mysqlConnected) {
            Logger.sendConsoleMessage("§e[Hybrid] Saving data to MySQL...", Logger.LogLevel.INFO);
            success = saveToMySql();
        }
        
        // Note: Redis data is already up-to-date via real-time pub/sub
        // No need to save to Redis on shutdown
        
        return success;
    }
    
    // ==================== Real-time Save Methods (called on data changes) ====================
    
    /**
     * Saves player data to both Redis (instant) and MySQL (queued).
     * This method is called whenever a player's orders change.
     */
    public void savePlayerAsync(UUID uuid, PlayerBazaar playerBazaar) {
        // Redis: Instant save + pub/sub notification
        if (redisConnected) {
            TaskUtils.runAsync(() -> savePlayerToRedis(uuid, playerBazaar));
        }
        
        // MySQL: Queue for async write
        if (mysqlConnected && writeToMySqlOnChange) {
            queueMySqlWrite(() -> savePlayerToMySql(uuid, playerBazaar));
        }
    }
    
    /**
     * Saves item data to both Redis (instant) and MySQL (queued).
     * This method is called whenever an item's orders change.
     */
    public void saveItemAsync(String name, BazaarItem bazaarItem) {
        // Redis: Instant save + pub/sub notification
        if (redisConnected) {
            TaskUtils.runAsync(() -> saveItemToRedis(name, bazaarItem));
        }
        
        // MySQL: Queue for async write
        if (mysqlConnected && writeToMySqlOnChange) {
            queueMySqlWrite(() -> saveItemToMySql(name, bazaarItem));
        }
    }
    
    /**
     * Saves item price data to both Redis (instant) and MySQL (queued).
     */
    public void saveItemPriceAsync(String name, BazaarItem bazaarItem) {
        // Redis: Instant save + pub/sub notification
        if (redisConnected) {
            TaskUtils.runAsync(() -> saveItemPriceToRedis(name, bazaarItem));
        }
        
        // MySQL: Queue for async write (same as saveItem since prices are in same row)
        if (mysqlConnected && writeToMySqlOnChange) {
            queueMySqlWrite(() -> saveItemToMySql(name, bazaarItem));
        }
    }
    
    // ==================== Redis Operations ====================
    
    private void startRedisSubscriber() {
        if (subscriberRunning) return;
        
        subscriberRunning = true;
        TaskUtils.runAsync(() -> {
            while (subscriberRunning && DeluxeBazaar.getInstance().isEnabled()) {
                try (Jedis jedis = jedisPool.getResource()) {
                    subscriber = new JedisPubSub() {
                        @Override
                        public void onPMessage(String pattern, String channel, String message) {
                            handleRedisMessage(channel, message);
                        }
                        
                        @Override
                        public void onPSubscribe(String pattern, int subscribedChannels) {
                            Logger.sendConsoleMessage("§a[Hybrid] Redis subscriber connected to pattern: " + pattern, Logger.LogLevel.INFO);
                        }
                    };
                    
                    Logger.sendConsoleMessage("§e[Hybrid] Starting Redis subscriber for patterns: " + channelPrefix + "*", Logger.LogLevel.INFO);
                    jedis.psubscribe(subscriber,
                            channelPrefix + "player:*",
                            channelPrefix + "item:*",
                            channelPrefix + "price:*");
                    
                } catch (Exception e) {
                    if (subscriberRunning && DeluxeBazaar.getInstance().isEnabled()) {
                        Logger.sendConsoleMessage("§c[Hybrid] Redis subscriber error: " + e.getMessage() + " - Reconnecting in 5 seconds...", Logger.LogLevel.ERROR);
                        redisConnected = false;
                        try {
                            Thread.sleep(5000);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                        // Try to reinitialize Redis
                        initializeRedis();
                    }
                }
            }
            subscriberRunning = false;
        });
    }
    
    private void handleRedisMessage(String channel, String message) {
        try {
            boolean enableLog = DeluxeBazaar.getInstance().configFile.getBoolean("settings.enable_log", false);
            
            // Ignore our own messages
            if (message != null && message.equals(serverId)) {
                return;
            }
            
            String updateKey = channel.substring(channelPrefix.length());
            if (recentLocalUpdates.contains(updateKey)) {
                recentLocalUpdates.remove(updateKey);
                return;
            }
            
            // Apply network latency delay if in DELAY mode
            if (syncMode == SyncMode.DELAY && networkLatencyMs > 0) {
                try {
                    Thread.sleep(networkLatencyMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            
            if (channel.startsWith(channelPrefix + "player:")) {
                String uuidStr = channel.substring((channelPrefix + "player:").length());
                UUID uuid = UUID.fromString(uuidStr);
                loadPlayerFromRedis(uuid);
                
                PlayerBazaar playerBazaar = DeluxeBazaar.getInstance().players.get(uuid);
                if (playerBazaar != null) {
                    rebuildPlayerOrderMappings(uuid, playerBazaar);
                    refreshPlayerOrdersMenu(uuid);
                }
            } else if (channel.startsWith(channelPrefix + "item:")) {
                String itemName = channel.substring((channelPrefix + "item:").length());
                loadItemFromRedis(itemName);
            } else if (channel.startsWith(channelPrefix + "price:")) {
                String itemName = channel.substring((channelPrefix + "price:").length());
                loadItemPriceFromRedis(itemName);
                
            }
        } catch (Exception e) {
            Logger.sendConsoleMessage("§c[Hybrid] Error handling Redis message: " + e.getMessage(), Logger.LogLevel.ERROR);
        }
    }
    
    private void savePlayerToRedis(UUID uuid, PlayerBazaar playerBazaar) {
        if (!redisConnected) return;
        
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
            }
            data.put("sell_offers", sellOffers.toString());
            
            jedis.hset(key, data);
            
            // Pub/sub notification
            String updateKey = "player:" + uuid.toString();
            recentLocalUpdates.add(updateKey);
            jedis.publish(channelPrefix + updateKey, serverId);
            
            // Clean up recent updates after delay
            scheduleRecentUpdateCleanup(updateKey);
        } catch (Exception e) {
            Logger.sendConsoleMessage("§c[Hybrid] Error saving player to Redis: " + e.getMessage(), Logger.LogLevel.ERROR);
        }
    }
    
    private void saveItemToRedis(String name, BazaarItem bazaarItem) {
        if (!redisConnected) return;
        
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
            
            // Pub/sub notification
            String updateKey = "item:" + name;
            recentLocalUpdates.add(updateKey);
            jedis.publish(channelPrefix + updateKey, serverId);
            
            scheduleRecentUpdateCleanup(updateKey);
        } catch (Exception e) {
            Logger.sendConsoleMessage("§c[Hybrid] Error saving item to Redis: " + e.getMessage(), Logger.LogLevel.ERROR);
        }
    }
    
    private void saveItemPriceToRedis(String name, BazaarItem bazaarItem) {
        if (!redisConnected) return;
        
        try (Jedis jedis = jedisPool.getResource()) {
            String key = "bazaar:price:" + name;
            
            Map<String, String> data = new HashMap<>();
            data.put("buy_price", String.valueOf(bazaarItem.getBuyPrice()));
            data.put("sell_price", String.valueOf(bazaarItem.getSellPrice()));
            data.put("buy_amount", String.valueOf(bazaarItem.getTotalBuyCount()));
            data.put("sell_amount", String.valueOf(bazaarItem.getTotalSellCount()));
            
            jedis.hset(key, data);
            
            // Pub/sub notification
            String updateKey = "price:" + name;
            recentLocalUpdates.add(updateKey);
            jedis.publish(channelPrefix + updateKey, serverId);
            
            scheduleRecentUpdateCleanup(updateKey);
        } catch (Exception e) {
            Logger.sendConsoleMessage("§c[Hybrid] Error saving item price to Redis: " + e.getMessage(), Logger.LogLevel.ERROR);
        }
    }
    
    private void loadPlayerFromRedis(UUID uuid) {
        if (!redisConnected) return;
        
        try (Jedis jedis = jedisPool.getResource()) {
            String key = "bazaar:player:" + uuid.toString();
            Map<String, String> data = jedis.hgetAll(key);
            
            if (data.isEmpty()) return;
            
            PlayerBazaar playerBazaar = DeluxeBazaar.getInstance().players.getOrDefault(uuid, new PlayerBazaar(uuid));
            
            String mode = data.get("mode");
            if (mode != null && !mode.isEmpty()) playerBazaar.setMode(mode);
            
            String category = data.get("category");
            if (category != null && !category.isEmpty()) playerBazaar.setCategory(category);
            
            String buyOrdersStr = data.get("buy_orders");
            if (buyOrdersStr != null && !buyOrdersStr.isEmpty()) {
                List<PlayerOrder> buyOrders = new ArrayList<>();
                for (String orderData : buyOrdersStr.split(",,")) {
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
                        
                        buyOrders.add(new PlayerOrder(orderUUID, playerUUID, item, OrderType.BUY, price, collected, filled, amount));
                    }
                }
                playerBazaar.setBuyOrders(buyOrders);
            } else {
                playerBazaar.setBuyOrders(new ArrayList<>());
            }
            
            String sellOffersStr = data.get("sell_offers");
            if (sellOffersStr != null && !sellOffersStr.isEmpty()) {
                List<PlayerOrder> sellOffers = new ArrayList<>();
                for (String orderData : sellOffersStr.split(",,")) {
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
                        
                        sellOffers.add(new PlayerOrder(orderUUID, playerUUID, item, OrderType.SELL, price, collected, filled, amount));
                    }
                }
                playerBazaar.setSellOffers(sellOffers);
            } else {
                playerBazaar.setSellOffers(new ArrayList<>());
            }
            
            DeluxeBazaar.getInstance().players.put(uuid, playerBazaar);
        } catch (Exception e) {
            Logger.sendConsoleMessage("§c[Hybrid] Error loading player from Redis: " + e.getMessage(), Logger.LogLevel.ERROR);
        }
    }
    
    private void loadItemFromRedis(String itemName) {
        if (!redisConnected) return;
        
        try (Jedis jedis = jedisPool.getResource()) {
            String key = "bazaar:item:" + itemName;
            Map<String, String> data = jedis.hgetAll(key);
            
            if (data.isEmpty()) return;
            
            BazaarItem bazaarItem = DeluxeBazaar.getInstance().bazaarItems.get(itemName);
            if (bazaarItem == null) return;
            
            String buyPricesStr = data.get("buy_prices");
            List<OrderPrice> buyPrices = new ArrayList<>();
            if (buyPricesStr != null && !buyPricesStr.isEmpty()) {
                for (String priceData : buyPricesStr.split(",,")) {
                    String[] parts = priceData.split(",");
                    if (parts.length >= 5) {
                        UUID uuid = UUID.fromString(parts[0]);
                        OrderType type = OrderType.valueOf(parts[1]);
                        double price = Double.parseDouble(parts[2]);
                        int orderAmount = Integer.parseInt(parts[3]);
                        int itemAmount = Integer.parseInt(parts[4]);
                        buyPrices.add(new OrderPrice(uuid, type, price, orderAmount, itemAmount));
                    }
                }
            }
            bazaarItem.setBuyPrices(buyPrices);
            
            String sellPricesStr = data.get("sell_prices");
            List<OrderPrice> sellPrices = new ArrayList<>();
            if (sellPricesStr != null && !sellPricesStr.isEmpty()) {
                for (String priceData : sellPricesStr.split(",,")) {
                    String[] parts = priceData.split(",");
                    if (parts.length >= 5) {
                        UUID uuid = UUID.fromString(parts[0]);
                        OrderType type = OrderType.valueOf(parts[1]);
                        double price = Double.parseDouble(parts[2]);
                        int orderAmount = Integer.parseInt(parts[3]);
                        int itemAmount = Integer.parseInt(parts[4]);
                        sellPrices.add(new OrderPrice(uuid, type, price, orderAmount, itemAmount));
                    }
                }
            }
            bazaarItem.setSellPrices(sellPrices);
            
            // Rebuild mappings for this item
            rebuildMappingsForItem(bazaarItem);
        } catch (Exception e) {
            Logger.sendConsoleMessage("§c[Hybrid] Error loading item from Redis: " + e.getMessage(), Logger.LogLevel.ERROR);
        }
    }
    
    private void loadItemPriceFromRedis(String itemName) {
        if (!redisConnected) return;
        
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
            Logger.sendConsoleMessage("§c[Hybrid] Error loading item price from Redis: " + e.getMessage(), Logger.LogLevel.ERROR);
        }
    }
    
    private void syncToRedisCache() {
        if (!redisConnected) return;
        
        // Sync all items to Redis
        for (Map.Entry<String, BazaarItem> entry : DeluxeBazaar.getInstance().bazaarItems.entrySet()) {
            saveItemToRedis(entry.getKey(), entry.getValue());
            saveItemPriceToRedis(entry.getKey(), entry.getValue());
        }
        
        // Sync all players to Redis
        for (Map.Entry<UUID, PlayerBazaar> entry : DeluxeBazaar.getInstance().players.entrySet()) {
            savePlayerToRedis(entry.getKey(), entry.getValue());
        }
        
        Logger.sendConsoleMessage("§a[Hybrid] Data synced to Redis cache", Logger.LogLevel.INFO);
    }
    
    // ==================== MySQL Operations ====================
    
    private boolean loadFromMySql() {
        return loadItemsFromMySql() && loadPlayersFromMySql();
    }
    
    private boolean saveToMySql() {
        boolean success = true;
        
        for (Map.Entry<String, BazaarItem> entry : DeluxeBazaar.getInstance().bazaarItems.entrySet()) {
            try {
                saveItemToMySql(entry.getKey(), entry.getValue());
            } catch (Exception e) {
                Logger.sendConsoleMessage("§c[Hybrid] Error saving item to MySQL: " + entry.getKey(), Logger.LogLevel.ERROR);
                success = false;
            }
        }
        
        for (Map.Entry<UUID, PlayerBazaar> entry : DeluxeBazaar.getInstance().players.entrySet()) {
            try {
                savePlayerToMySql(entry.getKey(), entry.getValue());
            } catch (Exception e) {
                Logger.sendConsoleMessage("§c[Hybrid] Error saving player to MySQL: " + entry.getKey(), Logger.LogLevel.ERROR);
                success = false;
            }
        }
        
        return success;
    }
    
    private boolean loadItemsFromMySql() {
        String selectSQL = "SELECT * FROM BazaarItems";
        
        try (PreparedStatement select = getMySqlConnection().prepareStatement(selectSQL)) {
            ResultSet set = select.executeQuery();
            
            while (set.next()) {
                BazaarItem bazaarItem = DeluxeBazaar.getInstance().bazaarItems.get(set.getString("name"));
                if (bazaarItem == null) continue;
                
                double buyPrice = set.getDouble("buy_price");
                if (buyPrice > 0) bazaarItem.setBuyPrice(buyPrice);
                
                double sellPrice = set.getDouble("sell_price");
                if (sellPrice > 0) bazaarItem.setSellPrice(sellPrice);
                
                int buyAmount = set.getInt("buy_amount");
                if (buyAmount > 0) bazaarItem.setTotalBuyCount(buyAmount);
                
                int sellAmount = set.getInt("sell_amount");
                if (sellAmount > 0) bazaarItem.setTotalSellCount(sellAmount);
                
                // Load buy prices
                String itemBuyPrices = set.getString("buy_prices");
                if (itemBuyPrices != null && !itemBuyPrices.isEmpty()) {
                    for (String arg : itemBuyPrices.split(",,")) {
                        String[] newArgs = arg.split(",");
                        if (newArgs.length < 5) continue;
                        
                        UUID uuid = UUID.fromString(newArgs[0]);
                        double price = Double.parseDouble(newArgs[2]);
                        if (price <= 0) continue;
                        
                        int orderAmount = Integer.parseInt(newArgs[3]);
                        if (orderAmount <= 0) continue;
                        
                        int itemAmount = Integer.parseInt(newArgs[4]);
                        if (itemAmount <= 0) continue;
                        
                        bazaarItem.getBuyPrices().add(new OrderPrice(uuid, OrderType.BUY, price, orderAmount, itemAmount));
                    }
                }
                
                // Load sell prices
                String itemSellPrices = set.getString("sell_prices");
                if (itemSellPrices != null && !itemSellPrices.isEmpty()) {
                    for (String arg : itemSellPrices.split(",,")) {
                        String[] newArgs = arg.split(",");
                        if (newArgs.length < 5) continue;
                        
                        UUID uuid = UUID.fromString(newArgs[0]);
                        double price = Double.parseDouble(newArgs[2]);
                        if (price <= 0) continue;
                        
                        int orderAmount = Integer.parseInt(newArgs[3]);
                        if (orderAmount <= 0) continue;
                        
                        int itemAmount = Integer.parseInt(newArgs[4]);
                        if (itemAmount <= 0) continue;
                        
                        bazaarItem.getSellPrices().add(new OrderPrice(uuid, OrderType.SELL, price, orderAmount, itemAmount));
                    }
                }
            }
            
            return true;
        } catch (Exception e) {
            Logger.sendConsoleMessage("§c[Hybrid] Error loading items from MySQL: " + e.getMessage(), Logger.LogLevel.ERROR);
            return false;
        }
    }
    
    private boolean loadPlayersFromMySql() {
        String selectSQL = "SELECT * FROM BazaarPlayers";
        
        try (PreparedStatement select = getMySqlConnection().prepareStatement(selectSQL)) {
            ResultSet set = select.executeQuery();
            
            while (set.next()) {
                UUID uuid = UUID.fromString(set.getString("uuid"));
                PlayerBazaar playerBazaar = DeluxeBazaar.getInstance().players.getOrDefault(uuid, new PlayerBazaar(uuid));
                
                String mode = set.getString("mode");
                if (mode != null && !mode.isEmpty()) playerBazaar.setMode(mode);
                
                String category = set.getString("category");
                if (category != null && !category.isEmpty()) playerBazaar.setCategory(category);
                
                // Load buy orders
                String playerBuyOrders = set.getString("buy_orders");
                if (playerBuyOrders != null && !playerBuyOrders.isEmpty()) {
                    List<PlayerOrder> buyOrders = new ArrayList<>();
                    for (String arg : playerBuyOrders.split(",,")) {
                        String[] newArgs = arg.split(",");
                        if (newArgs.length < 8) continue;
                        
                        UUID orderUUID = UUID.fromString(newArgs[0]);
                        String itemName = newArgs[2];
                        BazaarItem item = DeluxeBazaar.getInstance().bazaarItems.get(itemName);
                        if (item == null) continue;
                        
                        double price = Double.parseDouble(newArgs[4]);
                        if (price <= 0) continue;
                        
                        int amount = Integer.parseInt(newArgs[7]);
                        if (amount <= 0) continue;
                        
                        int collected = Integer.parseInt(newArgs[5]);
                        int filled = Integer.parseInt(newArgs[6]);
                        
                        buyOrders.add(new PlayerOrder(orderUUID, uuid, item, OrderType.BUY, price, collected, filled, amount));
                    }
                    playerBazaar.setBuyOrders(buyOrders);
                }
                
                // Load sell offers
                String playerSellOffers = set.getString("sell_offers");
                if (playerSellOffers != null && !playerSellOffers.isEmpty()) {
                    List<PlayerOrder> sellOffers = new ArrayList<>();
                    for (String arg : playerSellOffers.split(",,")) {
                        String[] newArgs = arg.split(",");
                        if (newArgs.length < 8) continue;
                        
                        UUID orderUUID = UUID.fromString(newArgs[0]);
                        String itemName = newArgs[2];
                        BazaarItem item = DeluxeBazaar.getInstance().bazaarItems.get(itemName);
                        if (item == null) continue;
                        
                        double price = Double.parseDouble(newArgs[4]);
                        if (price <= 0) continue;
                        
                        int amount = Integer.parseInt(newArgs[7]);
                        if (amount <= 0) continue;
                        
                        int collected = Integer.parseInt(newArgs[5]);
                        int filled = Integer.parseInt(newArgs[6]);
                        
                        sellOffers.add(new PlayerOrder(orderUUID, uuid, item, OrderType.SELL, price, collected, filled, amount));
                    }
                    playerBazaar.setSellOffers(sellOffers);
                }
                
                DeluxeBazaar.getInstance().players.put(uuid, playerBazaar);
            }
            
            return true;
        } catch (Exception e) {
            Logger.sendConsoleMessage("§c[Hybrid] Error loading players from MySQL: " + e.getMessage(), Logger.LogLevel.ERROR);
            return false;
        }
    }
    
    private void savePlayerToMySql(UUID uuid, PlayerBazaar playerBazaar) {
        if (playerBazaar == null) {
            playerBazaar = DeluxeBazaar.getInstance().players.getOrDefault(uuid, new PlayerBazaar(uuid));
        }
        
        StringBuilder buyOrders = new StringBuilder();
        for (PlayerOrder order : playerBazaar.getBuyOrders()) {
            if (buyOrders.length() > 0) buyOrders.append(",,");
            buyOrders.append(order.toString());
        }
        
        StringBuilder sellOffers = new StringBuilder();
        for (PlayerOrder order : playerBazaar.getSellOffers()) {
            if (sellOffers.length() > 0) sellOffers.append(",,");
            sellOffers.append(order.toString());
        }
        
        String updateSQL = "UPDATE BazaarPlayers SET mode = ?, category = ?, buy_orders = ?, sell_offers = ? WHERE uuid = ?";
        String insertSQL = "INSERT INTO BazaarPlayers (uuid, mode, category, buy_orders, sell_offers) VALUES (?, ?, ?, ?, ?)";
        
        try (PreparedStatement update = getMySqlConnection().prepareStatement(updateSQL);
             PreparedStatement insert = getMySqlConnection().prepareStatement(insertSQL)) {
            
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
        } catch (Exception e) {
            Logger.sendConsoleMessage("§c[Hybrid] Error saving player to MySQL: " + e.getMessage(), Logger.LogLevel.ERROR);
        }
    }
    
    private void saveItemToMySql(String name, BazaarItem bazaarItem) {
        if (bazaarItem == null) {
            bazaarItem = DeluxeBazaar.getInstance().bazaarItems.get(name);
        }
        if (bazaarItem == null) return;
        
        StringBuilder buyPrices = new StringBuilder();
        for (OrderPrice orderPrice : bazaarItem.getBuyPrices()) {
            if (buyPrices.length() > 0) buyPrices.append(",,");
            buyPrices.append(orderPrice.toString());
        }
        
        StringBuilder sellPrices = new StringBuilder();
        for (OrderPrice orderPrice : bazaarItem.getSellPrices()) {
            if (sellPrices.length() > 0) sellPrices.append(",,");
            sellPrices.append(orderPrice.toString());
        }
        
        String updateSQL = "UPDATE BazaarItems SET buy_price = ?, sell_price = ?, buy_amount = ?, sell_amount = ?, buy_prices = ?, sell_prices = ? WHERE name = ?";
        String insertSQL = "INSERT INTO BazaarItems (name, buy_price, sell_price, buy_amount, sell_amount, buy_prices, sell_prices) VALUES (?, ?, ?, ?, ?, ?, ?)";
        
        try (PreparedStatement update = getMySqlConnection().prepareStatement(updateSQL);
             PreparedStatement insert = getMySqlConnection().prepareStatement(insertSQL)) {
            
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
        } catch (Exception e) {
            Logger.sendConsoleMessage("§c[Hybrid] Error saving item to MySQL: " + e.getMessage(), Logger.LogLevel.ERROR);
        }
    }
    
    // ==================== Write Queue Management ====================
    
    private void queueMySqlWrite(Runnable writeTask) {
        mysqlWriteQueue.offer(writeTask);
    }
    
    private void startMySqlWriteQueue() {
        if (writeQueueRunning) return;
        writeQueueRunning = true;
        
        // Use runTimer which runs on main thread, then dispatch to async for actual writes
        TaskUtils.runTimer(() -> {
            if (!mysqlConnected) return;
            
            // Process queue asynchronously to avoid blocking main thread
            TaskUtils.runAsync(() -> {
                int processed = 0;
                Runnable task;
                while ((task = mysqlWriteQueue.poll()) != null && processed < 100) {
                    try {
                        task.run();
                        processed++;
                    } catch (Exception e) {
                        Logger.sendConsoleMessage("§c[Hybrid] Error in MySQL write queue: " + e.getMessage(), Logger.LogLevel.ERROR);
                    }
                }
                
                if (processed > 0 && DeluxeBazaar.getInstance().configFile.getBoolean("settings.enable_log", false)) {
                    Logger.sendConsoleMessage("§7[Hybrid] Processed " + processed + " MySQL writes", Logger.LogLevel.INFO);
                }
            });
        }, mysqlWriteIntervalSeconds * 20L, mysqlWriteIntervalSeconds * 20L);
        
        Logger.sendConsoleMessage("§a[Hybrid] MySQL write queue started (interval: " + mysqlWriteIntervalSeconds + "s)", Logger.LogLevel.INFO);
    }
    
    private void flushWriteQueue() {
        Logger.sendConsoleMessage("§e[Hybrid] Flushing MySQL write queue...", Logger.LogLevel.INFO);
        
        int processed = 0;
        Runnable task;
        while ((task = mysqlWriteQueue.poll()) != null) {
            try {
                task.run();
                processed++;
            } catch (Exception e) {
                Logger.sendConsoleMessage("§c[Hybrid] Error flushing MySQL write: " + e.getMessage(), Logger.LogLevel.ERROR);
            }
        }
        
        Logger.sendConsoleMessage("§a[Hybrid] Flushed " + processed + " MySQL writes", Logger.LogLevel.INFO);
    }
    
    // ==================== Mapping Utilities ====================
    
    private void rebuildOrderPlayerMappings() {
        int totalMappings = 0;
        
        for (Map.Entry<UUID, PlayerBazaar> entry : DeluxeBazaar.getInstance().players.entrySet()) {
            totalMappings += rebuildPlayerOrderMappings(entry.getKey(), entry.getValue());
        }
        
        Logger.sendConsoleMessage("§a[Hybrid] Rebuilt " + totalMappings + " order-player mappings", Logger.LogLevel.INFO);
    }
    
    private int rebuildPlayerOrderMappings(UUID playerUUID, PlayerBazaar playerBazaar) {
        int mappings = 0;
        
        // CRITICAL: First remove old mappings for this player from ALL items
        // This ensures we don't have stale PlayerOrder objects with outdated filled counts
        removePlayerFromAllOrderPrices(playerUUID);
        
        for (PlayerOrder order : playerBazaar.getBuyOrders()) {
            if (addPlayerToOrderPrice(playerUUID, order)) mappings++;
        }
        
        for (PlayerOrder order : playerBazaar.getSellOffers()) {
            if (addPlayerToOrderPrice(playerUUID, order)) mappings++;
        }
        
        return mappings;
    }
    
    /**
     * Removes a player's old PlayerOrder objects from all OrderPrice mappings.
     * This is necessary before rebuilding mappings to avoid stale data.
     */
    private void removePlayerFromAllOrderPrices(UUID playerUUID) {
        for (BazaarItem item : DeluxeBazaar.getInstance().bazaarItems.values()) {
            // Remove from buy prices
            for (OrderPrice orderPrice : item.getBuyPrices()) {
                orderPrice.getPlayers().remove(playerUUID);
            }
            
            // Remove from sell prices
            for (OrderPrice orderPrice : item.getSellPrices()) {
                orderPrice.getPlayers().remove(playerUUID);
            }
        }
    }
    
    private void rebuildMappingsForItem(BazaarItem item) {
        for (Map.Entry<UUID, PlayerBazaar> entry : DeluxeBazaar.getInstance().players.entrySet()) {
            UUID playerUUID = entry.getKey();
            PlayerBazaar playerBazaar = entry.getValue();
            
            for (PlayerOrder order : playerBazaar.getBuyOrders()) {
                if (order.getItem().equals(item)) {
                    addPlayerToOrderPrice(playerUUID, order);
                }
            }
            
            for (PlayerOrder order : playerBazaar.getSellOffers()) {
                if (order.getItem().equals(item)) {
                    addPlayerToOrderPrice(playerUUID, order);
                }
            }
        }
    }
    
    private boolean addPlayerToOrderPrice(UUID playerUUID, PlayerOrder playerOrder) {
        BazaarItem item = playerOrder.getItem();
        if (item == null) return false;
        
        List<OrderPrice> orderPrices = playerOrder.getType().equals(OrderType.BUY)
                ? item.getBuyPrices()
                : item.getSellPrices();
        
        for (OrderPrice orderPrice : orderPrices) {
            if (Math.abs(orderPrice.getPrice() - playerOrder.getPrice()) < 0.01) {
                List<PlayerOrder> playerOrders = orderPrice.getPlayers().getOrDefault(playerUUID, new ArrayList<>());
                
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
    
    // ==================== Utility Methods ====================
    
    private void scheduleRecentUpdateCleanup(String updateKey) {
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
    }
    
    private void refreshPlayerOrdersMenu(UUID uuid) {
        TaskUtils.run(() -> {
            Player player = Bukkit.getPlayer(uuid);
            if (player == null || !player.isOnline()) return;
            
            if (player.getOpenInventory() != null && player.getOpenInventory().getTitle() != null) {
                String title = player.getOpenInventory().getTitle();
                String ordersMenuTitle = DeluxeBazaar.getInstance().menusFile.getString("orders.title");
                
                if (ordersMenuTitle != null && title.contains(me.sedattr.deluxebazaar.others.Utils.colorize(ordersMenuTitle))) {
                    OrdersMenu ordersMenu = new OrdersMenu(player);
                    ordersMenu.openMenu(1);
                }
            }
        });
    }
    
    /**
     * Reloads item data from Redis after registerAllItems() creates new BazaarItem objects.
     */
    public void reloadItemsAfterRegistration() {
        Logger.sendConsoleMessage("§e[Hybrid] Reloading data after item registration...", Logger.LogLevel.INFO);
        
        // Load from MySQL (source of truth for persistence)
        if (mysqlConnected) {
            loadItemsFromMySql();
            loadPlayersFromMySql();
            Logger.sendConsoleMessage("§a[Hybrid] Loaded data from MySQL", Logger.LogLevel.INFO);
        } else if (redisConnected) {
            // Fallback to Redis if MySQL is not available
            for (String itemName : DeluxeBazaar.getInstance().bazaarItems.keySet()) {
                loadItemFromRedis(itemName);
                loadItemPriceFromRedis(itemName);
            }
            for (UUID uuid : DeluxeBazaar.getInstance().players.keySet()) {
                loadPlayerFromRedis(uuid);
            }
            Logger.sendConsoleMessage("§e[Hybrid] Loaded data from Redis (MySQL unavailable)", Logger.LogLevel.INFO);
        }
        
        // Sync data to Redis cache if both are available
        if (mysqlConnected && redisConnected) {
            syncToRedisCache();
        }
        
        rebuildOrderPlayerMappings();
        Logger.sendConsoleMessage("§a[Hybrid] Data reloaded and mappings rebuilt successfully!", Logger.LogLevel.INFO);
    }
    
    /**
     * Returns whether Redis is connected for external checks.
     */
    public boolean isRedisConnected() {
        return redisConnected;
    }
    
    /**
     * Returns whether MySQL is connected for external checks.
     */
    public boolean isMySqlConnected() {
        return mysqlConnected;
    }
    
    /**
     * Closes all connections gracefully.
     */
    public void close() {
        Logger.sendConsoleMessage("§e[Hybrid] Shutting down database connections...", Logger.LogLevel.INFO);
        
        // Stop write queue first to flush pending writes
        writeQueueRunning = false;
        flushWriteQueue();
        
        // Stop subscriber loop
        subscriberRunning = false;
        if (subscriber != null) {
            try {
                subscriber.punsubscribe();
                subscriber.unsubscribe();
            } catch (Exception ignored) {}
        }
        
        // Close Redis pool
        redisConnected = false;
        if (jedisPool != null && !jedisPool.isClosed()) {
            try {
                jedisPool.close();
            } catch (Exception ignored) {}
        }
        
        // Close MySQL connection
        mysqlConnected = false;
        synchronized (mysqlLock) {
            if (mysqlConnection != null) {
                try {
                    if (!mysqlConnection.isClosed()) {
                        mysqlConnection.close();
                    }
                } catch (SQLException ignored) {}
            }
        }
        
        Logger.sendConsoleMessage("§a[Hybrid] Database connections closed gracefully", Logger.LogLevel.INFO);
    }
}
