package me.sedattr.deluxebazaar.others;

import me.sedattr.deluxebazaar.DeluxeBazaar;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

public class PersistentDataContainerUtils {
    public static void setItem(ItemStack item, ConfigurationSection section) {
        if (section == null)
            return;

        ItemMeta meta = item.getItemMeta();
        if (meta == null)
            return;

        String key = section.getString("key");
        String data = section.getString("data");
        if (key == null || data == null || key.isEmpty() || data.isEmpty())
            return;

        NamespacedKey namespacedKey = new NamespacedKey(DeluxeBazaar.getInstance(), key);

        PersistentDataContainer container = meta.getPersistentDataContainer();
        container.set(namespacedKey, PersistentDataType.STRING, data);

        item.setItemMeta(meta);
    }

    public static boolean isCustomItem(ItemStack item, ConfigurationSection section) {
        if (item == null)
            return false;
        if (section == null)
            return false;

        ItemMeta meta = item.getItemMeta();
        if (meta == null)
            return false;

        String key = section.getString("key");
        String data = section.getString("data");
        if (key == null || data == null || key.isEmpty() || data.isEmpty())
            return false;

        PersistentDataContainer persistentDataContainer = meta.getPersistentDataContainer();
        NamespacedKey namespacedKey = new NamespacedKey(DeluxeBazaar.getInstance(), key);

        String customData = persistentDataContainer.getOrDefault(namespacedKey, PersistentDataType.STRING, "");
        return customData.equals(data);
    }
}