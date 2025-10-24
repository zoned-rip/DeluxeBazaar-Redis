package me.sedattr.deluxebazaar.economy;

import org.black_ixx.playerpoints.PlayerPoints;
import org.black_ixx.playerpoints.PlayerPointsAPI;
import org.bukkit.OfflinePlayer;

public class PlayerPointsEconomy implements EconomyManager {
    public final PlayerPointsAPI api;

    public PlayerPointsEconomy() {
        this.api = PlayerPoints.getInstance().getAPI();
    }

    @Override
    public boolean addBalance(OfflinePlayer player, Double count) {
        return this.api.give(player.getUniqueId(), Integer.parseInt(String.valueOf(count)));
    }

    @Override
    public boolean removeBalance(OfflinePlayer player, Double count) {
        return this.api.take(player.getUniqueId(), Integer.parseInt(String.valueOf(count)));
    }

    @Override
    public double getBalance(OfflinePlayer player) {
        return Double.valueOf(this.api.look(player.getUniqueId()));
    }
}
