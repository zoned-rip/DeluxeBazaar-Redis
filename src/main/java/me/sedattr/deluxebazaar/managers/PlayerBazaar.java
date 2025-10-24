package me.sedattr.deluxebazaar.managers;

import com.google.common.collect.Maps;
import lombok.Getter;
import lombok.Setter;
import me.sedattr.deluxebazaar.DeluxeBazaar;
import org.bukkit.OfflinePlayer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;

@Getter
public class PlayerBazaar {
    private final UUID player;
    private @Setter List<PlayerOrder> buyOrders = new ArrayList<>();
    private @Setter List<PlayerOrder> sellOffers = new ArrayList<>();
    private @Setter String mode = DeluxeBazaar.getInstance().configFile.getString("settings.default_mode", "direct");
    private @Setter String category = DeluxeBazaar.getInstance().configFile.getString("settings.default_category", "mining");
    private @Setter HashMap<String, Integer> sellableItems = Maps.newHashMap();

    public PlayerBazaar(OfflinePlayer player) {
        this.player = player.getUniqueId();

        DeluxeBazaar.getInstance().players.putIfAbsent(player.getUniqueId(), this);
    }

    public PlayerBazaar(UUID uuid) {
        this.player = uuid;

        DeluxeBazaar.getInstance().players.putIfAbsent(uuid, this);
    }
}