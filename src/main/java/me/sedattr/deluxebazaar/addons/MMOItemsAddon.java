package me.sedattr.deluxebazaar.addons;

import net.Indyuce.mmoitems.MMOItems;
import org.bukkit.inventory.ItemStack;

public class MMOItemsAddon {
    public ItemStack getMMOItem(String type, String name) {
        if (name == null || name.isEmpty())
            return null;

        return MMOItems.plugin.getItem(MMOItems.plugin.getTypes().get(type), name);
    }
}
