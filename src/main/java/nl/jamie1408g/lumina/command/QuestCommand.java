package nl.jamie1408g.lumina.command;

import nl.jamie1408g.lumina.LuminaPlugin;
import nl.jamie1408g.lumina.quests.QuestManager;
import nl.jamie1408g.lumina.storage.PlayerData;
import nl.jamie1408g.lumina.util.Money;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.util.List;

/** /lquests - shows all quests and your progress. */
public final class QuestCommand implements TabExecutor {

    private static final int BAR_LENGTH = 10;

    private final LuminaPlugin plugin;

    public QuestCommand(LuminaPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            plugin.msg().send(sender, "general.players-only");
            return true;
        }
        PlayerData data = plugin.data(player);
        plugin.msg().line(player, "quests.header");
        if (plugin.quests().quests().isEmpty()) {
            plugin.msg().line(player, "quests.none");
        }
        for (QuestManager.Quest quest : plugin.quests().quests()) {
            PlayerData.QuestProgress progress = plugin.quests().progress(data, quest);
            String repeat = plugin.msg().get("quests.repeat-" + quest.repeat().name().toLowerCase());
            if (progress.completed) {
                plugin.msg().line(player, "quests.entry-done", "quest", quest.name(), "repeat", repeat);
                continue;
            }
            plugin.msg().line(player, "quests.entry", "quest", quest.name(), "description", quest.description(),
                    "bar", bar(progress.progress, quest.amount()), "progress", formatAmount(quest, progress.progress),
                    "amount", formatAmount(quest, quest.amount()), "reward", Money.format(quest.reward()),
                    "repeat", repeat);
        }
        return true;
    }

    private static String formatAmount(QuestManager.Quest quest, long value) {
        return quest.type() == QuestManager.Type.DISTANCE ? Cmd.distance(value) : String.valueOf(value);
    }

    private static String bar(long progress, long amount) {
        int filled = (int) Math.min(BAR_LENGTH, amount <= 0 ? 0 : progress * BAR_LENGTH / amount);
        return "&a" + "|".repeat(filled) + "&8" + "|".repeat(BAR_LENGTH - filled);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        return List.of();
    }
}
