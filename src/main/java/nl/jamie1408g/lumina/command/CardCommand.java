package nl.jamie1408g.lumina.command;

import nl.jamie1408g.lumina.LuminaPlugin;
import nl.jamie1408g.lumina.network.Station;
import nl.jamie1408g.lumina.storage.CardRecord;
import nl.jamie1408g.lumina.util.Money;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.util.List;

/** /luminacard buy | info | topup <amount> | history | replace */
public final class CardCommand implements TabExecutor {

    private final LuminaPlugin plugin;

    public CardCommand(LuminaPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            plugin.msg().send(sender, "general.players-only");
            return true;
        }
        String sub = args.length == 0 ? "info" : args[0].toLowerCase();
        switch (sub) {
            case "buy" -> plugin.cards().buy(player);
            case "info", "balance" -> {
                CardRecord card = plugin.cards().cardInHand(player);
                if (card == null) {
                    List<CardRecord> cards = plugin.cards().activeCards(player.getUniqueId());
                    if (cards.isEmpty()) {
                        plugin.msg().send(player, "card.no-card-buy", "label", label,
                                "price", Money.format(plugin.cards().price()));
                        return true;
                    }
                    card = cards.get(0);
                }
                String status;
                if (card.blocked()) {
                    status = plugin.msg().get("card.status-blocked");
                } else if (card.tappedIn() != null) {
                    Station station = plugin.network().network().byKey(card.tappedIn());
                    status = plugin.msg().get("card.status-in", "station", station == null ? card.tappedIn() : station.name());
                } else {
                    status = plugin.msg().get("card.status-out");
                }
                plugin.msg().line(player, "card.info", "id", card.id(), "value", Money.format(card.value()),
                        "status", status);
            }
            case "topup", "top-up" -> {
                if (!plugin.cards().commandTopUpAllowed()) {
                    plugin.msg().send(player, "card.topup-sign-only");
                    return true;
                }
                if (args.length < 2) {
                    plugin.msg().send(player, "card.topup-usage", "label", label);
                    return true;
                }
                plugin.cards().topUp(player, Money.parse(args[1]));
            }
            case "history" -> {
                CardRecord card = plugin.cards().cardInHand(player);
                if (card == null) {
                    plugin.msg().send(player, "card.hold-card");
                    return true;
                }
                plugin.msg().line(player, "card.history-header", "id", card.id());
                if (card.history().isEmpty()) {
                    plugin.msg().line(player, "card.history-empty");
                }
                for (String entry : card.history()) {
                    plugin.msg().line(player, "card.history-entry", "entry", entry);
                }
            }
            case "replace" -> plugin.cards().replace(player);
            default -> plugin.msg().send(player, "card.usage", "label", label);
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return Cmd.filter(args[0], "buy", "info", "topup", "history", "replace");
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("topup")) {
            return Cmd.filter(args[1], "5", "10", "20", "50");
        }
        return List.of();
    }
}
