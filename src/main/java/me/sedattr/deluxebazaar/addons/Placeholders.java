package me.sedattr.deluxebazaar.addons;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import me.sedattr.deluxebazaar.DeluxeBazaar;
import me.sedattr.deluxebazaar.managers.BazaarItem;
import me.sedattr.deluxebazaar.managers.PlayerBazaar;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.ConfigurationSection;
import org.jetbrains.annotations.NotNull;

public class Placeholders extends PlaceholderExpansion {
    @Override
    public @NotNull String getIdentifier() {
        return "bazaar";
    }

    @Override
    public @NotNull String getAuthor() {
        return "SedatTR";
    }

    @Override
    public @NotNull String getVersion() {
        return "2.0";
    }

    @Override
    public String onRequest(OfflinePlayer player, String identifier) {

        // %bazaar_buy_orders%
        if (identifier.contains("buy_orders")) {
            PlayerBazaar playerBazaar = DeluxeBazaar.getInstance().players.get(player.getUniqueId());
            if (playerBazaar == null)
                return "0";

            return String.valueOf(playerBazaar.getBuyOrders().size());
        }

        // %bazaar_sell_offers%
        if (identifier.contains("sell_offers")) {
            PlayerBazaar playerBazaar = DeluxeBazaar.getInstance().players.get(player.getUniqueId());
            if (playerBazaar == null)
                return "0";

            return String.valueOf(playerBazaar.getSellOffers().size());
        }

        // %bazaar_balance_formatted%
        if (identifier.contains("balance_formatted")) {
            return DeluxeBazaar.getInstance().numberFormat.format(DeluxeBazaar.getInstance().economyManager.getBalance(player));
        }

        // %bazaar_balance%
        if (identifier.contains("balance")) {
            return String.valueOf(DeluxeBazaar.getInstance().economyManager.getBalance(player));
        }

        // %bazaar_mode%
        if (identifier.contains("mode")) {
            PlayerBazaar playerBazaar = DeluxeBazaar.getInstance().players.get(player.getUniqueId());
            if (playerBazaar == null)
                return DeluxeBazaar.getInstance().configFile.getString("settings.default_mode", "direct");

            return playerBazaar.getMode();
        }

        // %bazaar_category%
        if (identifier.contains("category")) {
            PlayerBazaar playerBazaar = DeluxeBazaar.getInstance().players.get(player.getUniqueId());
            if (playerBazaar == null)
                return DeluxeBazaar.getInstance().configFile.getString("settings.default_category", "mining");

            return playerBazaar.getCategory();
        }

        String[] args = identifier.split("_");
        if (args.length < 1)
            return "0";

        String item = args[args.length - 1];
        if (item == null)
            return "0";

        ConfigurationSection itemSection = DeluxeBazaar.getInstance().itemsFile.getConfigurationSection("items." + item);
        if (itemSection == null)
            return "0";

        BazaarItem bazaarItem = DeluxeBazaar.getInstance().bazaarItems.get(item);
        if (bazaarItem == null)
            return "0";

        // %bazaar_buyprice_formatted_ITEMNAME%
        if (identifier.contains("buy_price_formatted")) {
            return DeluxeBazaar.getInstance().numberFormat.format(bazaarItem.getBuyPrice());
        }

        // %bazaar_sellprice_formatted_ITEMNAME%
        if (identifier.contains("sell_price_formatted")) {
            return DeluxeBazaar.getInstance().numberFormat.format(bazaarItem.getSellPrice());
        }

        // %bazaar_buyprice_fixed_ITEMNAME%
        if (identifier.contains("buy_price_fixed")) {
            return String.valueOf(bazaarItem.getBuyPrice());
        }

        // %bazaar_sellprice_fixed_ITEMNAME%
        if (identifier.contains("sell_price_fixed")) {
            return String.valueOf(bazaarItem.getSellPrice());
        }

        // %bazaar_buystock_ITEMNAME%
        if (identifier.contains("buy_stock")) {
            boolean buyStockEnabled = DeluxeBazaar.getInstance().orderHandler.isStockEnabled(item, "buy");
            int buyStock = buyStockEnabled ? DeluxeBazaar.getInstance().orderHandler.getStockCount(player, item, "buy") : 0;
            if (buyStock <= 0)
                if (buyStockEnabled)
                    return "0";
                else
                    return "false";

            return String.valueOf(buyStock);
        }

        // %bazaar_sellstock_ITEMNAME%
        if (identifier.contains("sell_stock")) {
            boolean sellStockEnabled = DeluxeBazaar.getInstance().orderHandler.isStockEnabled(item, "sell");
            int sellStock = sellStockEnabled ? DeluxeBazaar.getInstance().orderHandler.getStockCount(player, item, "sell") : 0;
            if (sellStock <= 0)
                if (sellStockEnabled)
                    return "0";
                else
                    return "false";

            return String.valueOf(sellStock);
        }

        /*
        // %bazaar_sell_stock_ITEMNAME%
        if (identifier.contains("sell_stock")) {
            boolean sellStockEnabled = DeluxeBazaar.getInstance().orderHandler.isStockEnabled(item);
            int sellStock = sellStockEnabled ? DeluxeBazaar.getInstance().orderHandler.getStockCount(player, "sell", args[0]) : 0;
            if (sellStock <= 0)
                if (sellStockEnabled)
                    return "0";
                else
                    return "false";

            return String.valueOf(sellStock);
        }
         */

        // %bazaar_buy_amount_ITEMNAME%
        if (identifier.contains("buy_count")) {
            return DeluxeBazaar.getInstance().numberFormat.format(bazaarItem.getTotalBuyCount());
        }

        // %bazaar_sell_amount_ITEMNAME%
        if (identifier.contains("sell_count")) {
            return DeluxeBazaar.getInstance().numberFormat.format(bazaarItem.getTotalSellCount());
        }

        return "";
    }
}
