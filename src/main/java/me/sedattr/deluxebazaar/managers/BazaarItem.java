package me.sedattr.deluxebazaar.managers;

import lombok.Getter;
import lombok.Setter;
import me.sedattr.deluxebazaar.DeluxeBazaar;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class BazaarItem {
    // item name from config
    @Getter private final String name;
    @Getter @Setter private String category;
    // item stack for item
    @Getter private final ItemStack itemStack;
    // buy price of item
    private Double buyPrice;
    // sell price of item
    private Double sellPrice;
    // maximum buy price of item
    @Getter @Setter private Double maximumBuyPrice = 0.0;
    // maximum sell price of item
    @Getter @Setter private Double maximumSellPrice = 0.0;
    // item's buy price list
    @Getter @Setter private List<OrderPrice> buyPrices = new ArrayList<>();
    // item's sell price list
    @Getter @Setter private List<OrderPrice> sellPrices = new ArrayList<>();

    @Getter @Setter private int totalBuyCount = 0;
    @Getter @Setter private int totalSellCount = 0;
    @Getter @Setter private int oldBuyCount = 0;
    @Getter @Setter private int oldSellCount = 0;
    @Getter @Setter private int currentBuyCount = 0;
    @Getter @Setter private int currentSellCount = 0;
    @Getter @Setter private String subcategory;

    public double getBuyPrice() {
        return this.buyPrice;
    }

    public double getSellPrice() {
        return this.sellPrice;
    }

    public void setSellPrice(double price) {
        this.sellPrice = price;
    }

    public void setBuyPrice(double price) {
        this.buyPrice = price;
    }

    public void addBuyCount(int count) {
        this.currentBuyCount += count;
        this.totalBuyCount += count;
    }

    public void addSellCount(int count) {
        this.currentSellCount += count;
        this.totalSellCount += count;

        if (!DeluxeBazaar.getInstance().orderHandler.isStockEnabled(this.name, "buy"))
            return;
        ConfigurationSection stockSection = DeluxeBazaar.getInstance().orderHandler.getStockConfiguration(this.name);
        if (stockSection == null)
            return;

        int maxStock = stockSection.getInt("max_stock", 0);
        if (maxStock <= 0) {
            String categoryName = this.category;
            if (categoryName != null && !categoryName.isEmpty()) {
                int maximumCategoryStock = DeluxeBazaar.getInstance().configFile.getInt("item_stock_system.item_list." + categoryName + ".max_stock", 0);
                if (maximumCategoryStock > 0)
                    maxStock = maximumCategoryStock;
            }
        }

        if (maxStock > 0) {
            int currentStock = this.totalSellCount - this.totalBuyCount;
            if (currentStock > maxStock)
                this.totalSellCount = this.totalBuyCount + maxStock;
        }
    }

    public BazaarItem(String name, ItemStack item, double buyPrice, double sellPrice) {
        this.name = name;
        this.itemStack = item;
        this.buyPrice = buyPrice;
        this.sellPrice = sellPrice;

        if (!DeluxeBazaar.getInstance().orderHandler.isStockEnabled(name, "buy"))
            return;

        ConfigurationSection section = DeluxeBazaar.getInstance().orderHandler.getStockConfiguration(name);
        if (section == null)
            return;

        int stockAmount = section.getInt("default_stock", 0);
        if (stockAmount > 0)
            this.totalSellCount = stockAmount;
    }
}