package me.sedattr.bazaarapi;

import de.tr7zw.changeme.nbtapi.NBT;
import me.sedattr.deluxebazaar.DeluxeBazaar;
import me.sedattr.deluxebazaar.managers.BazaarItem;
import me.sedattr.deluxebazaar.managers.OrderPrice;
import me.sedattr.deluxebazaar.others.PersistentDataContainerUtils;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.*;

public class BazaarItemHook {
    public static BazaarItem getBazaarItem(String name) {
        return DeluxeBazaar.getInstance().bazaarItems.getOrDefault(name, null);
    }

    /**
     * Checks if an item is a vanilla Minecraft item (not modified by custom plugins)
     * Vanilla items have minecraft namespace and minecraft:common tooltip_style
     * Excludes EcoItems (they have ecoitems namespace in PersistentDataContainer)
     */
    private static boolean isVanillaItem(ItemStack item) {
        if (item == null)
            return false;
        
        try {
            String materialKey = item.getType().getKey().toString();
            if (!materialKey.startsWith("minecraft:"))
                return false;
            
            if (DeluxeBazaar.getInstance().ecoItemsAddon != null) {
                String ecoId = DeluxeBazaar.getInstance().ecoItemsAddon.getEcoItemId(item);
                if (ecoId != null)
                    return false; 
            }

            ItemMeta meta = item.getItemMeta();
            if (meta != null && DeluxeBazaar.getInstance().version > 14) {
                try {
                    org.bukkit.persistence.PersistentDataContainer container = meta.getPersistentDataContainer();
                    for (org.bukkit.NamespacedKey key : container.getKeys()) {
                        String namespace = key.getNamespace().toLowerCase();
                        if (namespace.equals("ecoitems") || namespace.equals("eco-items"))
                            return false;
                    }
                } catch (Exception ignored) {}
            }
            
            de.tr7zw.changeme.nbtapi.NBTItem nbtItem = new de.tr7zw.changeme.nbtapi.NBTItem(item);
            if (nbtItem.hasTag("tooltip_style")) {
                String tooltipStyle = nbtItem.getString("tooltip_style");
                return tooltipStyle != null && tooltipStyle.startsWith("minecraft:common");
            }
    
            
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean isSimilar(ItemStack item1, ItemStack item2) {
        if (item1.getType() != item2.getType())
            return false;
        if (item1.getMaxStackSize() != item2.getMaxStackSize())
            return false;
        
        boolean isVanilla1 = isVanillaItem(item1);
        boolean isVanilla2 = isVanillaItem(item2);
        
        if (isVanilla1 && isVanilla2) {
            return true;
        }
        
        if (isVanilla1 != isVanilla2)
            return false;

        if (DeluxeBazaar.getInstance().ecoItemsAddon != null) {
            String ecoId1 = DeluxeBazaar.getInstance().ecoItemsAddon.getEcoItemId(item1);
            String ecoId2 = DeluxeBazaar.getInstance().ecoItemsAddon.getEcoItemId(item2);
            
            if (ecoId1 != null && ecoId2 == null)
                return false;
            if (ecoId1 == null && ecoId2 != null)
                return false;
            
            if (ecoId1 != null && ecoId2 != null)
                return ecoId1.equals(ecoId2);
        }

        ItemMeta meta1 = item1.getItemMeta();
        ItemMeta meta2 = item2.getItemMeta();
        if (meta1 == null && meta2 != null)
            return false;
        if (meta2 == null && meta1 != null)
            return false;

        if (DeluxeBazaar.getInstance().version < 13) {
            if (item1.getDurability() != item2.getDurability())
                return false;
        } else {
            if (meta1.hasCustomModelData() && meta2.hasCustomModelData() && meta1.getCustomModelData() > 0 && meta2.getCustomModelData() > 0 && meta1.getCustomModelData() != meta2.getCustomModelData())
                return false;
        }

        boolean ignoreMetadata = DeluxeBazaar.getInstance().configFile.getBoolean("settings.ignore_item_metadata", false);
        if (ignoreMetadata) {
            return true;
        }

        if (meta1 != null && meta2 != null) {
            String displayName1 = meta1.getDisplayName();
            String displayName2 = meta2.getDisplayName();
            if (displayName1 == null && displayName2 == null) {
            } else if (displayName1 == null || displayName2 == null) {
                return false; 
            } else if (!displayName1.equals(displayName2)) {
                return false; 
            }

            List<String> lore1 = meta1.getLore();
            List<String> lore2 = meta2.getLore();
            
            if (lore1 == null && lore2 == null) {
            } else if (lore1 == null || lore2 == null) {
                return false;
            } else if (!lore1.equals(lore2)) {
                return false;
            }

            if (DeluxeBazaar.getInstance().version > 14) {
                try {
                    org.bukkit.persistence.PersistentDataContainer container1 = meta1.getPersistentDataContainer();
                    org.bukkit.persistence.PersistentDataContainer container2 = meta2.getPersistentDataContainer();
                    
                    java.util.Set<org.bukkit.NamespacedKey> keys1 = container1.getKeys();
                    java.util.Set<org.bukkit.NamespacedKey> keys2 = container2.getKeys();
                    
                    if (keys1.size() != keys2.size())
                        return false;
                    
                    for (org.bukkit.NamespacedKey key : keys1) {
                        if (!keys2.contains(key))
                            return false;
                        
                        String val1 = container1.get(key, org.bukkit.persistence.PersistentDataType.STRING);
                        String val2 = container2.get(key, org.bukkit.persistence.PersistentDataType.STRING);
                        
                        if (val1 != null || val2 != null) {
                            if (val1 == null || val2 == null || !val1.equals(val2))
                                return false;
                        }
                    }
                } catch (Exception e) {
                    return false;
                }
            }

            return true;
        }

        return true;
    }

    public static String getItemName(ItemStack item) {
        if (item == null || item.getType().equals(Material.AIR))
            return null;

        String nbt = NBT.itemStackToNBT(item).getString("BazaarITEM");
        if (nbt != null && !nbt.isEmpty()) {
            ConfigurationSection itemSection = DeluxeBazaar.getInstance().itemsFile.getConfigurationSection("items." + nbt);
            if (itemSection != null)
                return nbt;
        }

        HashMap<String, BazaarItem> bazaarItems = DeluxeBazaar.getInstance().bazaarItems;
        if (bazaarItems.isEmpty())
            return null;

        for (Map.Entry<String, BazaarItem> entry : bazaarItems.entrySet()) {
            BazaarItem bazaarItem = entry.getValue();
            if (bazaarItem == null)
                continue;

            ItemStack itemStack = bazaarItem.getItemStack();
            if (itemStack==null)
                continue;

            if (DeluxeBazaar.getInstance().version > 14) {
                ConfigurationSection section = DeluxeBazaar.getInstance().itemsFile.getConfigurationSection("items." + entry.getKey() + ".persistent");
                if (section != null && PersistentDataContainerUtils.isCustomItem(itemStack, section) && PersistentDataContainerUtils.isCustomItem(item, section))
                    return entry.getKey();
            }

            if (DeluxeBazaar.getInstance().ecoItemsAddon != null) {
                String ecoItemId = DeluxeBazaar.getInstance().ecoItemsAddon.getEcoItemId(item);
                if (ecoItemId != null) {
                    ConfigurationSection itemSection = DeluxeBazaar.getInstance().itemsFile.getConfigurationSection("items." + entry.getKey());
                    if (itemSection != null) {
                        String configuredEcoId = itemSection.getString("eco_items");
                        if (ecoItemId.equals(configuredEcoId))
                            return entry.getKey();
                    }
                }
            }

            if (isSimilar(item, itemStack))
                return entry.getKey();
        }

        return null;
    }

    public static double getBuyPrice(Player player, String name, int amount) {
        double totalPrice = 0.0;
        int totalAmount = amount;
        double defaultPrice = getDefaultBuyPrice(name);

        BazaarItem bazaarItem = BazaarItemHook.getBazaarItem(name);
        if (bazaarItem == null)
            return 0.0;

        List<OrderPrice> orderPrices = bazaarItem.getSellPrices();
        if (!orderPrices.isEmpty()) {
            orderPrices.sort(Comparator.comparing(OrderPrice::getPrice));
            for (OrderPrice order : orderPrices) {
                if (order == null)
                    continue;
                if (totalAmount <= 0)
                    break;

                double price = order.getPrice();
                int itemAmount = order.getItemAmount() - DeluxeBazaar.getInstance().orderHandler.getItemCount(player, "buy", name, price);
                if (itemAmount <= 0)
                    continue;

                if (totalAmount >= itemAmount) {
                    totalPrice += price * itemAmount;
                    totalAmount -= itemAmount;
                } else {
                    totalPrice += price * totalAmount;
                    totalAmount = 0;
                }
            }
        }

        if (totalAmount > 0)
            totalPrice += totalAmount * defaultPrice;

        return totalPrice;
    }

    public static double getSellPrice(Player player, String name, int amount) {
        double totalPrice = 0.0;
        int totalAmount = amount;
        double defaultPrice = getDefaultSellPrice(name);

        BazaarItem bazaarItem = BazaarItemHook.getBazaarItem(name);
        if (bazaarItem == null)
            return 0.0;

        List<OrderPrice> orderPrices = bazaarItem.getBuyPrices();
        if (!orderPrices.isEmpty()) {
            orderPrices.sort(Comparator.comparing(OrderPrice::getPrice));
            Collections.reverse(orderPrices);
            for (OrderPrice order : orderPrices) {
                if (order == null)
                    continue;
                if (totalAmount <= 0)
                    break;

                double price = order.getPrice();
                int itemAmount = order.getItemAmount() - DeluxeBazaar.getInstance().orderHandler.getItemCount(player, "sell", name, price);
                if (itemAmount <= 0)
                    continue;

                if (totalAmount >= itemAmount) {
                    totalPrice += price * itemAmount;
                    totalAmount -= itemAmount;
                } else {
                    totalPrice += price * totalAmount;
                    totalAmount = 0;
                }
            }
        }

        if (totalAmount > 0)
            totalPrice += totalAmount * defaultPrice;

        return totalPrice;
    }

    public static Double getDefaultBuyPrice(String item) {
        BazaarItem bazaarItem = DeluxeBazaar.getInstance().bazaarItems.get(item);
        if (bazaarItem == null)
            return 0.0;

        double price = bazaarItem.getBuyPrice();
        if (price <= 0.0)
            return DeluxeBazaar.getInstance().itemsFile.getDouble("items." + item + ".prices.buy", 0.0);
        else
            return price;
    }

    public static Double getDefaultSellPrice(String item) {
        BazaarItem bazaarItem = DeluxeBazaar.getInstance().bazaarItems.get(item);
        if (bazaarItem == null)
            return 0.0;

        double price = bazaarItem.getSellPrice();
        if (price <= 0.0)
            return DeluxeBazaar.getInstance().itemsFile.getDouble("items." + item + ".prices.sell", 0.0);
        else
            return price;
    }

    public static void setBuyPrice(String item, Double amount) {
        BazaarItem bazaarItem = DeluxeBazaar.getInstance().bazaarItems.get(item);
        if (bazaarItem == null)
            return;

        bazaarItem.setBuyPrice(amount);
    }

    public static void setSellPrice(String item, Double amount) {
        BazaarItem bazaarItem = DeluxeBazaar.getInstance().bazaarItems.get(item);
        if (bazaarItem == null)
            return;

        bazaarItem.setSellPrice(amount);
    }

    public static Integer getBuyCount(String item) {
        BazaarItem bazaarItem = DeluxeBazaar.getInstance().bazaarItems.get(item);
        if (bazaarItem == null)
            return 0;

        return bazaarItem.getTotalBuyCount();
    }

    public static Integer getSellCount(String item) {
        BazaarItem bazaarItem = DeluxeBazaar.getInstance().bazaarItems.get(item);
        if (bazaarItem == null)
            return 0;

        return bazaarItem.getTotalSellCount();
    }
}