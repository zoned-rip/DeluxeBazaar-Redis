package me.sedattr.deluxebazaar.economy;

import me.sedattr.deluxebazaar.DeluxeBazaar;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.OfflinePlayer;

public class VaultEconomy implements EconomyManager {
    public boolean addBalance(OfflinePlayer player, Double count) {
        EconomyResponse r = DeluxeBazaar.getInstance().economy.depositPlayer(player, count);
        return r.transactionSuccess();
    }

    @Override
    public boolean removeBalance(OfflinePlayer player, Double count) {
        EconomyResponse r = DeluxeBazaar.getInstance().economy.withdrawPlayer(player, count);
        return r.transactionSuccess();
    }

    @Override
    public double getBalance(OfflinePlayer player) {
        return DeluxeBazaar.getInstance().economy.getBalance(player);
    }
}
