package me.sedattr.bazaarapi.events;

import lombok.Getter;
import me.sedattr.deluxebazaar.managers.BazaarItem;
import org.bukkit.OfflinePlayer;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

public class BazaarItemSellEvent extends Event implements Cancellable {
    private static final HandlerList handlers = new HandlerList();
    @Getter
    private final OfflinePlayer player;
    @Getter
    private final Integer count;
    @Getter
    private final Double unitPrice;
    @Getter
    private final BazaarItem item;

    private Boolean cancelled = false;

    public BazaarItemSellEvent(OfflinePlayer player, BazaarItem item, Double unitPrice, Integer count) {
        this.player = player;
        this.count = count;
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
    public @NotNull HandlerList getHandlers() {
        return handlers;
    }
}
