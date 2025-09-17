package me.sedattr.deluxebazaar.menus;

import de.rapha149.signgui.SignGUI;
import me.sedattr.deluxebazaar.DeluxeBazaar;
import me.sedattr.deluxebazaar.others.ChatInput;
import me.sedattr.deluxebazaar.others.TaskUtils;
import me.sedattr.deluxebazaar.others.Utils;
import net.wesjd.anvilgui.AnvilGUI;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.Collections;
import java.util.List;

public class InputMenu {
    private String type;

    public InputMenu() {
        this.type = DeluxeBazaar.getInstance().configFile.getString("settings.input_type");
        if (this.type == null)
            this.type = "sign";
    }

    public void open(Player player, MenuManager menuManager) {
        if (this.type.equalsIgnoreCase("sign"))
            signInput(player, menuManager);
        else if (this.type.equalsIgnoreCase("anvil"))
            anvilInput(player, menuManager);
        else
            chatInput(player, menuManager);
    }

    private void signInput(Player player, MenuManager menuManager) {
        String textType = menuManager.getClass().equals(MainMenu.class) ? "text" : "number";

        List<String> lines = DeluxeBazaar.getInstance().messagesFile.getStringList("input_lines.sign." + textType);
        if (!lines.isEmpty() && lines.size() > 3) {
            try {
                SignGUI gui = SignGUI.builder()
                        .setLines(lines.get(0), lines.get(1), lines.get(2), lines.get(3))
                        .setHandler((p, entry) -> {
                            String result = entry.getLineWithoutColor(0).trim();
                            TaskUtils.run(() -> menuManager.inputResult(result));

                            return Collections.emptyList();
                        }).build();

                gui.open(player);
            } catch (Exception exception) {
                anvilInput(player, menuManager);
            }
        } else
            anvilInput(player, menuManager);
    }

    private void anvilInput(Player player, MenuManager menuManager) {
        String textType = menuManager.getClass().equals(MainMenu.class) ? "text" : "number";

        try {
            AnvilGUI.Builder builder = new AnvilGUI.Builder()
                    .onClick((slot, state) -> {
                        String result = state.getText().trim();

                        menuManager.inputResult(result);
                        return Collections.singletonList(AnvilGUI.ResponseAction.close());
                    }).text(Utils.colorize((DeluxeBazaar.getInstance().messagesFile.getString("input_lines.anvil." + textType))))
                    .plugin(DeluxeBazaar.getInstance());

            if (TaskUtils.isFolia)
                builder.mainThreadExecutor((command) -> Bukkit.getGlobalRegionScheduler().execute(DeluxeBazaar.getInstance(), command));

            builder.open(player);
        } catch (Exception exception) {
            chatInput(player, menuManager);
        }
    }

    private void chatInput(Player player, MenuManager menuManager) {
        String textType = menuManager.getClass().equals(MainMenu.class) ? "text" : "number";

        player.closeInventory();
        Utils.sendMessage(player, "input_lines.chat." + textType);

        new ChatInput(player, menuManager::inputResult);
    }
}
