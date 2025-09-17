package me.sedattr.deluxebazaar.managers;

import lombok.Getter;
import lombok.Setter;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.util.UUID;

@Getter
public class PlayerOrder {
    private final UUID uuid;
    private final UUID player;
    private final BazaarItem item;
    private final OrderType type;
    @Setter private double price;
    private final int amount;
    @Setter private int collected = 0;
    @Setter private int filled = 0;

    public PlayerOrder(OfflinePlayer player, UUID uuid, BazaarItem item, OrderType type, double price, int amount) {
        this.player = player.getUniqueId();
        this.item = item;
        this.type = type;
        this.price = price;
        this.amount = amount;
        this.uuid = uuid;
    }

    public PlayerOrder(UUID orderUUID, UUID uuid, BazaarItem item, OrderType type, double price, int collected, int filled, int amount) {
        this.player = uuid;
        this.item = item;
        this.type = type;
        this.price = price;
        this.filled = filled;
        this.collected = collected;
        this.amount = amount;
        this.uuid = orderUUID != null ? orderUUID : UUID.randomUUID();
    }

    @Override
    public String toString() {
        return uuid + "," + player + "," + item.getName() + "," + type.name() + "," + price + "," + collected + "," + filled + "," + amount;
    }
}