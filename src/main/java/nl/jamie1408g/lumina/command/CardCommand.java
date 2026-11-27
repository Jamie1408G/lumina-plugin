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

/** /luminacard buy | info | topup <amount> | auto [off|10|20] | pass [buy|renew|stop] | history | replace */
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
                plugin.msg().line(player, "card.info-auto", "auto", card.autoTopUp() > 0
                        ? plugin.msg().get("card.auto-word-on", "amount", Money.format(card.autoTopUp()))
                        : plugin.msg().get("card.auto-word-off"));
                plugin.msg().line(player, "card.info-pass", "pass", plugin.passes().status(card.owner()));
            }
            case "auto", "autotopup", "auto-top-up" -> {
                CardRecord card = plugin.cards().mainCard(player);
                if (card == null) {
                    plugin.msg().send(player, "card.no-card-buy", "label", label,
                            "price", Money.format(plugin.cards().price()));
                    return true;
                }
                List<String> options = autoOptions();
                if (args.length < 2) {
                    plugin.msg().send(player, "card.auto-usage", "label", label, "options", String.join("|", options),
                            "now", card.autoTopUp() > 0 ? Money.format(card.autoTopUp()) : "off");
                    return true;
                }
                if (args[1].equalsIgnoreCase("off")) {
                    plugin.cards().setAutoTopUp(player, card, 0);
                    return true;
                }
                long amount = Money.parse(args[1]);
                if (!plugin.cards().autoTopUpAmounts().contains(amount)) {
                    plugin.msg().send(player, "card.auto-usage", "label", label, "options", String.join("|", options),
                            "now", card.autoTopUp() > 0 ? Money.format(card.autoTopUp()) : "off");
                    return true;
                }
                plugin.cards().setAutoTopUp(player, card, amount);
            }
            case "pass", "monthly" -> {
                String action = args.length < 2 ? "" : args[1].toLowerCase(java.util.Locale.ROOT);
                switch (action) {
                    case "buy" -> plugin.passes().buy(player);
                    case "stop", "cancel" -> plugin.passes().setRenew(player, false);
                    case "renew", "resume" -> plugin.passes().setRenew(player, true);
                    default -> plugin.msg().send(player, "pass.info", "status", plugin.passes().status(player.getUniqueId()),
                            "label", label, "price", Money.format(plugin.passes().price()), "days", plugin.passes().days());
                }
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
            return Cmd.filter(args[0], "buy", "info", "topup", "auto", "pass", "history", "replace");
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("auto")) {
            return Cmd.filter(autoOptions(), args[1]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("pass")) {
            return Cmd.filter(args[1], "buy", "stop", "renew");
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("topup")) {
            return Cmd.filter(args[1], "5", "10", "20", "50");
        }
        return List.of();
    }

    /** "off", "10", "20" */
    private List<String> autoOptions() {
        List<String> options = new java.util.ArrayList<>(List.of("off"));
        for (long amount : plugin.cards().autoTopUpAmounts()) {
            options.add(Money.plain(amount).replaceAll("\\.00$", ""));
        }
        return options;
    }
}
