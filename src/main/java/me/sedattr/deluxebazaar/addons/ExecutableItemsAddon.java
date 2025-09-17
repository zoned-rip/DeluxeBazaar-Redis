package me.sedattr.deluxebazaar.addons;

import com.ssomar.score.api.executableitems.ExecutableItemsAPI;
import com.ssomar.score.api.executableitems.config.ExecutableItemInterface;
import org.bukkit.inventory.ItemStack;

import java.util.Optional;

public class ExecutableItemsAddon {
    public ItemStack getExecutableItem(String name) {
        if (name == null || name.isEmpty())
            return null;

        Optional<ExecutableItemInterface> item = ExecutableItemsAPI.getExecutableItemsManager().getExecutableItem(name);
        return item.map(executableItemInterface -> executableItemInterface.buildItem(1, Optional.empty())).orElse(null);
    }
}