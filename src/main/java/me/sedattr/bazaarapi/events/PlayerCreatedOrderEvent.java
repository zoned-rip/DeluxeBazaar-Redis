package me.sedattr.bazaarapi.events;

import lombok.Getter;
import me.sedattr.deluxebazaar.managers.BazaarItem;
import me.sedattr.deluxebazaar.managers.OrderType;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

public class PlayerCreatedOrderEvent extends Event implements Cancellable {
    private static final HandlerList handlers = new HandlerList();
    @Getter
    private final Player player;
    @Getter
    private final Double unitPrice;
    @Getter
    private final Integer count;
    @Getter
    private final BazaarItem item;
    @Getter
    private final OrderType type;
    private Boolean cancelled = false;

    public PlayerCreatedOrderEvent(Player player, BazaarItem item, OrderType type, Double unitPrice, Integer count) {
        this.player = player;
        this.count = count;
        this.type = type;
        this.unitPrice = unitPrice;
        this.item = item;
    }

    public static HandlerList getHandlerList() {
        return handlers;
    }

    @Override
    public boolean isCancelled() {
        return this.cancelled;
    }

    @Override
    public void setCancelled(boolean b) {
        this.cancelled = b;
    }

    @Override
    public HandlerList getHandlers() {
        return handlers;
    }
}
