package me.sedattr.deluxebazaar.listeners;

import me.sedattr.deluxebazaar.DeluxeBazaar;
import me.sedattr.bazaarapi.BazaarItemHook;
import me.sedattr.deluxebazaar.managers.BazaarItem;
import me.sedattr.deluxebazaar.managers.PlayerBazaar;
import me.sedattr.deluxebazaar.others.Utils;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.Map;

public class ItemListeners implements Listener {
    @EventHandler
    public void onClick(PlayerInteractEvent e) {
        Player player = e.getPlayer();
        ItemStack item = e.getItem();
        if (item == null || item.getType().equals(Material.AIR))
            return;

        PlayerBazaar playerBazaar = DeluxeBazaar.getInstance().players.getOrDefault(e.getPlayer().getUniqueId(), new PlayerBazaar(e.getPlayer()));
        if (playerBazaar == null)
            return;

        HashMap<String, Integer> sellableItems = playerBazaar.getSellableItems();
        if (sellableItems == null || sellableItems.isEmpty())
            return;

        for (Map.Entry<String, Integer> entry : sellableItems.entrySet()) {
            String itemName = entry.getKey();
            BazaarItem bazaarItem = DeluxeBazaar.getInstance().bazaarItems.get(itemName);
            if (bazaarItem == null)
                continue;

            if (BazaarItemHook.isSimilar(item, bazaarItem.getItemStack())) {
                if (e.getAction() == Action.LEFT_CLICK_AIR || e.getAction() == Action.LEFT_CLICK_BLOCK)
                    Utils.executeCommands(player, itemName, "click_commands.left");
                else if (e.getAction() == Action.RIGHT_CLICK_AIR || e.getAction() == Action.RIGHT_CLICK_BLOCK)
                    Utils.executeCommands(player, itemName, "click_commands.right");
            }
        }
    }

    @EventHandler
    public void onPlace(BlockPlaceEvent e) {
        ItemStack item = e.getItemInHand();
        if (item.getType().equals(Material.AIR))
            return;

        PlayerBazaar playerBazaar = DeluxeBazaar.getInstance().players.getOrDefault(e.getPlayer().getUniqueId(), new PlayerBazaar(e.getPlayer()));
        if (playerBazaar == null)
            return;

        HashMap<String, Integer> sellableItems = playerBazaar.getSellableItems();
        if (sellableItems == null || sellableItems.isEmpty())
            return;

        for (Map.Entry<String, Integer> entry : sellableItems.entrySet()) {
            String itemName = entry.getKey();
            BazaarItem bazaarItem = DeluxeBazaar.getInstance().bazaarItems.get(itemName);
            if (bazaarItem == null)
                continue;

            if (BazaarItemHook.isSimilar(item, bazaarItem.getItemStack())) {
                if (!DeluxeBazaar.getInstance().itemsFile.getBoolean("items." + itemName + ".placeable", true))
                    e.setCancelled(true);
            }
        }
    }
}
