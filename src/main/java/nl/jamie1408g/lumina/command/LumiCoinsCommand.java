package nl.jamie1408g.lumina.command;

import nl.jamie1408g.lumina.LuminaPlugin;
import nl.jamie1408g.lumina.storage.PlayerData;
import nl.jamie1408g.lumina.util.Money;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/** /lumicoins [balance [player]] | pay <player> <amount> | top | give/take/set <player> <amount> */
public final class LumiCoinsCommand implements TabExecutor {

    private final LuminaPlugin plugin;

    public LumiCoinsCommand(LuminaPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length == 0 ? "balance" : args[0].toLowerCase();
        switch (sub) {
            case "balance", "bal" -> {
                if (args.length >= 2 && sender.hasPermission("lumina.admin.eco")) {
                    PlayerData target = plugin.storage().findByName(args[1]);
                    if (target == null) {
                        plugin.msg().send(sender, "general.unknown-player", "player", args[1]);
                        return true;
                    }
                    plugin.msg().send(sender, "coins.balance-other", "player", target.name(),
                            "balance", Money.format(target.balance()));
                    return true;
                }
                if (!(sender instanceof Player player)) {
                    plugin.msg().send(sender, "general.players-only");
                    return true;
                }
                plugin.msg().send(sender, "coins.balance", "balance", Money.format(plugin.data(player).balance()));
            }
            case "pay" -> {
                if (!(sender instanceof Player player)) {
                    plugin.msg().send(sender, "general.players-only");
                    return true;
                }
                if (args.length < 3) {
                    plugin.msg().send(sender, "coins.pay-usage", "label", label);
                    return true;
                }
                PlayerData target = plugin.storage().findByName(args[1]);
                long amount = Money.parse(args[2]);
                if (target == null) {
                    plugin.msg().send(sender, "general.unknown-player", "player", args[1]);
                    return true;
                }
                if (amount <= 0) {
                    plugin.msg().send(sender, "general.invalid-amount");
                    return true;
                }
                if (target.uuid().equals(player.getUniqueId())) {
                    plugin.msg().send(sender, "coins.pay-self");
                    return true;
                }
                if (!plugin.economy().transfer(player.getUniqueId(), target.uuid(), amount)) {
                    plugin.msg().send(sender, "general.not-enough-money", "amount", Money.format(amount),
                            "balance", Money.format(plugin.data(player).balance()));
                    return true;
                }
                plugin.storage().markDirty();
                plugin.msg().send(sender, "coins.paid", "player", target.name(), "amount", Money.format(amount));
                Player online = Bukkit.getPlayer(target.uuid());
                if (online != null) {
                    plugin.msg().send(online, "coins.received", "player", player.getName(), "amount", Money.format(amount));
                }
            }
            case "top" -> {
                int size = Math.max(1, plugin.getConfig().getInt("lumicoins.top-size", 10));
                plugin.msg().line(sender, "coins.top-header");
                int rank = 1;
                for (PlayerData data : plugin.economy().top(size)) {
                    plugin.msg().line(sender, "coins.top-entry", "rank", rank++, "player", data.name(),
                            "balance", Money.format(data.balance()));
                }
            }
            case "give", "take", "set" -> {
                if (!sender.hasPermission("lumina.admin.eco")) {
                    plugin.msg().send(sender, "general.no-permission");
                    return true;
                }
                if (args.length < 3) {
                    plugin.msg().send(sender, "coins.admin-usage", "label", label);
                    return true;
                }
                PlayerData target = plugin.storage().findByName(args[1]);
                long amount = Money.parse(args[2]);
                if (target == null) {
                    plugin.msg().send(sender, "general.unknown-player", "player", args[1]);
                    return true;
                }
                if (amount < 0 || (amount == 0 && !sub.equals("set"))) {
                    plugin.msg().send(sender, "general.invalid-amount");
                    return true;
                }
                String reason = "admin " + sub + " by " + sender.getName();
                switch (sub) {
                    case "give" -> plugin.economy().deposit(target.uuid(), amount, reason);
                    case "take" -> plugin.economy().set(target.uuid(), target.balance() - amount, reason);
                    default -> plugin.economy().set(target.uuid(), amount, reason);
                }
                plugin.storage().markDirty();
                plugin.msg().send(sender, "coins.admin-done", "player", target.name(),
                        "balance", Money.format(target.balance()));
            }
            default -> plugin.msg().send(sender, "coins.usage", "label", label);
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> subs = new ArrayList<>(List.of("balance", "pay", "top"));
            if (sender.hasPermission("lumina.admin.eco")) {
                subs.addAll(List.of("give", "take", "set"));
            }
            return Cmd.filter(subs, args[0]);
        }
        if (args.length == 2 && !args[0].equalsIgnoreCase("top")) {
            return Cmd.filter(plugin.storage().knownNames(), args[1]);
        }
        return List.of();
    }
}
