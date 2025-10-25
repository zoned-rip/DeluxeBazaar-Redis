package me.sedattr.deluxebazaar.addons;

import com.willfp.ecoitems.items.EcoItem;
import com.willfp.ecoitems.items.EcoItems;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

public class EcoItemsAddon {
    public ItemStack getEcoItem(String name) {
        if (name == null || name.isEmpty())
            return null;

        EcoItem item = EcoItems.INSTANCE.getByID(name);
        if (item == null)
            return null;

        return item.getItemStack();
    }

    /**
     * Check if an ItemStack is an EcoItem and return its ID
     * @param itemStack The ItemStack to check
     * @return The EcoItem ID (eco_items field from items.yml), or null if not an EcoItem
     */
    public String getEcoItemId(ItemStack itemStack) {
        if (itemStack == null)
            return null;

        try {
            ItemMeta meta = itemStack.getItemMeta();
            if (meta == null)
                return null;

            PersistentDataContainer container = meta.getPersistentDataContainer();
            
            String[] namespaces = {"ecoitems", "eco-items", "ECOITEMS"};
            String[] keys = {"item", "id", "item-id"};
            
            for (String namespace : namespaces) {
                for (String key : keys) {
                    try {
                        NamespacedKey ecoKey = new NamespacedKey(namespace, key);
                        if (container.has(ecoKey, PersistentDataType.STRING)) {
                            String id = container.get(ecoKey, PersistentDataType.STRING);
                            if (id != null && !id.isEmpty())
                                return id;
                        }
                    } catch (Exception ignored) {
                    }
                }
            }
        } catch (Exception e) {
            return null;
        }

        return null;
    }
}