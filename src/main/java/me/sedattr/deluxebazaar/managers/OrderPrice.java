package me.sedattr.deluxebazaar.managers;

import lombok.Getter;
import lombok.Setter;

import java.util.HashMap;
import java.util.List;
import java.util.UUID;

@Getter
public class OrderPrice {
    private final UUID uuid;
    private final OrderType type;
    private final double price;
    @Setter private int itemAmount;
    @Setter private int orderAmount = 1;
    private final HashMap<UUID, List<PlayerOrder>> players = new HashMap<>();

    public OrderPrice(OrderType type, double price, int itemAmount) {
        this.type = type;
        this.price = price;
        this.itemAmount = itemAmount;
        this.uuid = UUID.randomUUID();
    }

    public OrderPrice(UUID uuid, OrderType type, double price, int orderAmount, int itemAmount) {
        this.type = type;
        this.price = price;
        this.itemAmount = itemAmount;
        this.orderAmount = orderAmount;
        this.uuid = uuid;
    }

    @Override
    public String toString() {
        return uuid + "," + type.name() + "," + price + "," + orderAmount + "," + itemAmount;
    }
}
