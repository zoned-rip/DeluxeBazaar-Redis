package me.sedattr.deluxebazaar.addons;

import me.sedattr.deluxebazaar.DeluxeBazaar;
import me.sedattr.deluxebazaar.managers.BazaarItem;
import me.sedattr.deluxebazaar.managers.PlayerOrder;
import me.sedattr.deluxebazaarredis.DeluxeBazaarRedis;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;

public class RedisAddon {
    public final DeluxeBazaarRedis plugin;

    public RedisAddon() {
        this.plugin = (DeluxeBazaarRedis) Bukkit.getPluginManager().getPlugin("DeluxeBazaarRedis");
        if (this.plugin == null)
            DeluxeBazaar.getInstance().redisAddon = null;
    }

    public void createOrder(PlayerOrder order) {
        this.plugin.orderManager.orderCreated(order);
    }

    public void deleteOrder(PlayerOrder order) {
        this.plugin.orderManager.orderDeleted(order);
    }

    public void boughtItem(OfflinePlayer player, BazaarItem item, double unitPrice, int amount) {
        this.plugin.itemManager.playerBoughtItem(player, item, unitPrice, amount);
    }
}