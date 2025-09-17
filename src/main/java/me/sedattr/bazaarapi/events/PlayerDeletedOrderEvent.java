package me.sedattr.bazaarapi.events;

import lombok.Getter;
import me.sedattr.deluxebazaar.managers.PlayerOrder;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

public class PlayerDeletedOrderEvent extends Event implements Cancellable {
    private static final HandlerList handlers = new HandlerList();
    @Getter
    private final Player player;
    @Getter
    private final PlayerOrder order;

    private Boolean cancelled = false;

    public PlayerDeletedOrderEvent(Player player, PlayerOrder order) {
        this.player = player;
        this.order = order;
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
