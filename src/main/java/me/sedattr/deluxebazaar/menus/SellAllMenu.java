package me.sedattr.deluxebazaar.menus;

import me.sedattr.deluxebazaar.DeluxeBazaar;
import me.sedattr.bazaarapi.BazaarItemHook;
import me.sedattr.deluxebazaar.inventoryapi.HInventory;
import me.sedattr.deluxebazaar.inventoryapi.item.ClickableItem;
import me.sedattr.deluxebazaar.managers.PlayerBazaar;
import me.sedattr.deluxebazaar.others.PlaceholderUtil;
import me.sedattr.deluxebazaar.others.Utils;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.Map;

public class SellAllMenu {
    private final ConfigurationSection section;
    private final Player player;

    public SellAllMenu(Player player) {
        this.section = DeluxeBazaar.getInstance().menusFile.getConfigurationSection("menus.sellAll");
        this.player = player;
    }

    public void openMenu(HashMap<String, Integer> items) {
        if (section == null)
            return;

        HInventory gui = DeluxeBazaar.getInstance().menuHandler.createInventory(this.player, this.section, "sell_all", null);

        ConfigurationSection itemSection = this.section.getConfigurationSection("confirm.notSold");
        if (itemSection != null) {
            double price = 0.0;
            int count = 0;

            for (Map.Entry<String, Integer> item : items.entrySet()) {
                count += item.getValue();
                price += BazaarItemHook.getSellPrice(player, item.getKey(), item.getValue());
            }

            PlaceholderUtil placeholders = new PlaceholderUtil()
                    .addPlaceholder("%total_amount%", String.valueOf(count))
                    .addPlaceholder("%count%", String.valueOf(count))
                    .addPlaceholder("%total_price%", DeluxeBazaar.getInstance().numberFormat.format(price))
                    .addPlaceholder("%price%", DeluxeBazaar.getInstance().numberFormat.format(price));

            ItemStack confirm = Utils.createItemFromSection(itemSection, placeholders);

            if (confirm != null)
                gui.setItem(itemSection.getInt("slot") - 1, ClickableItem.of(confirm, (event) -> {
                    boolean goBack = DeluxeBazaar.getInstance().configFile.getBoolean("settings.go_back_when_sold", false);
                    if (!goBack) {
                        ConfigurationSection soldSection = this.section.getConfigurationSection("confirm.sold");
                        if (soldSection != null) {
                            ItemStack sold = Utils.createItemFromSection(soldSection, placeholders);
                            gui.setItem(soldSection.getInt("slot", (itemSection.getInt("slot") - 1)) - 1, ClickableItem.empty(sold));
                        }
                    }

                    DeluxeBazaar.getInstance().itemHandler.sellBazaarItems(player, items);
                    if (goBack) {
                        new MainMenu(player).openMenu(DeluxeBazaar.getInstance().players.getOrDefault(player.getUniqueId(), new PlayerBazaar(player.getUniqueId())).getCategory(), 1);
                    }
                }));
        }

        ConfigurationSection cancelSection = this.section.getConfigurationSection("cancel");
        if (cancelSection != null) {
            ItemStack cancel = Utils.createItemFromSection(cancelSection, null);
            if (cancel != null)
                gui.setItem(cancelSection.getInt("slot") - 1, ClickableItem.of(cancel, (event) -> player.closeInventory()));
        }

        gui.open(player);
    }
}
