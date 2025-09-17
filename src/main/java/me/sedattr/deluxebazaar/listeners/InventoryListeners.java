package me.sedattr.deluxebazaar.listeners;

import me.sedattr.bazaarapi.BazaarItemHook;
import me.sedattr.deluxebazaar.inventoryapi.HInventory;
import me.sedattr.deluxebazaar.inventoryapi.inventory.InventoryAPI;
import me.sedattr.deluxebazaar.inventoryapi.inventory.InventoryVariables;
import me.sedattr.deluxebazaar.menus.ItemMenu;
import me.sedattr.deluxebazaar.others.Utils;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.time.ZonedDateTime;

public class InventoryListeners implements Listener {
    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player player))
            return;
        HInventory gui = InventoryAPI.getInventory(player);
        if (gui == null)
            return;
        e.setCancelled(true);

        Inventory inventory = e.getClickedInventory();
        if (inventory == null || e.getSlot() < 0)
            return;
        if (inventory.equals(gui.getInventory()))
            return;
        if (e.getAction() == InventoryAction.DROP_ALL_SLOT || e.getAction() == InventoryAction.DROP_ONE_SLOT)
            return;

        String type = gui.getId();
        if (type == null || type.isEmpty())
            return;

        ItemStack item = e.getCurrentItem();
        if (item == null || item.getType().equals(Material.AIR))
            return;

        String name = BazaarItemHook.getItemName(item);
        if (name == null)
            return;

        if (!Utils.checkItemPermission(player, name))
            return;

        if (name.equalsIgnoreCase(type))
            return;

        long cooldown = InventoryVariables.getCooldown(player);
        if (cooldown > 0) {
            long time = ZonedDateTime.now().toInstant().toEpochMilli() - cooldown;
            if (time < 300) {
                Utils.sendMessage(player, "click_cooldown");
                return;
            }
        }

        open(player, name);
        InventoryVariables.addCooldown(player, ZonedDateTime.now().toInstant().toEpochMilli());
    }

    @EventHandler
    public void onLeave(PlayerQuitEvent e) {
        Player player = e.getPlayer();

        InventoryVariables.removeCooldown(player);
        InventoryVariables.removePlayerInventory(player);
    }

    public void open(Player player, String name) {
        Utils.playSound(player, "inventory_item_click");
        new ItemMenu(player).openMenu(name);
    }
}
