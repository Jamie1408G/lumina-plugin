package nl.jamie1408g.lumina.command;

import nl.jamie1408g.lumina.LuminaPlugin;
import nl.jamie1408g.lumina.property.Property;
import nl.jamie1408g.lumina.property.PropertyManager;
import nl.jamie1408g.lumina.storage.PlayerData;
import nl.jamie1408g.lumina.util.Money;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** /property list [district] | info [id] | buy [id] | sell [id] | mine | trust/untrust <player> [id] */
public final class PropertyCommand implements TabExecutor {

    private final LuminaPlugin plugin;

    public PropertyCommand(LuminaPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        PropertyManager properties = plugin.properties();
        String sub = args.length == 0 ? "info" : args[0].toLowerCase(Locale.ROOT);

        if (sub.equals("list")) {
            String district = args.length >= 2 ? Cmd.join(args, 1) : null;
            plugin.msg().line(sender, "property.list-header");
            int shown = 0;
            for (Property p : properties.all()) {
                if (!p.forSale() || (district != null && !p.district().equalsIgnoreCase(district))) {
                    continue;
                }
                plugin.msg().line(sender, "property.list-entry", "id", p.id(), "district", districtOf(p),
                        "price", Money.format(p.price()), "size", p.size());
                shown++;
            }
            if (shown == 0) {
                plugin.msg().line(sender, "property.list-empty");
            }
            return true;
        }

        if (!(sender instanceof Player player)) {
            plugin.msg().send(sender, "general.players-only");
            return true;
        }

        switch (sub) {
            case "info" -> {
                Property p = args.length >= 2 ? properties.get(args[1]) : properties.at(player.getLocation());
                if (p == null) {
                    plugin.msg().send(player, args.length >= 2 ? "property.unknown" : "property.none-here",
                            "id", args.length >= 2 ? args[1] : "", "label", label);
                    return true;
                }
                showInfo(player, p, label);
            }
            case "buy" -> {
                Property p = target(player, args, 1);
                if (p != null) {
                    properties.buy(player, p);
                }
            }
            case "sell" -> {
                Property p = target(player, args, 1);
                if (p != null) {
                    properties.sell(player, p);
                }
            }
            case "mine" -> {
                List<Property> mine = properties.ownedBy(player.getUniqueId());
                plugin.msg().line(player, "property.mine-header");
                if (mine.isEmpty()) {
                    plugin.msg().line(player, "property.mine-empty", "label", label);
                }
                for (Property p : mine) {
                    plugin.msg().line(player, "property.mine-entry", "id", p.id(), "district", districtOf(p),
                            "x", (p.minX() + p.maxX()) / 2, "y", p.minY(), "z", (p.minZ() + p.maxZ()) / 2);
                }
            }
            case "trust", "untrust" -> {
                if (args.length < 2) {
                    plugin.msg().send(player, "property.trust-usage", "label", label);
                    return true;
                }
                Property p = target(player, args, 2);
                if (p == null) {
                    return true;
                }
                if (!player.getUniqueId().equals(p.owner())) {
                    plugin.msg().send(player, "property.not-yours", "id", p.id());
                    return true;
                }
                PlayerData other = plugin.storage().findByName(args[1]);
                if (other == null) {
                    plugin.msg().send(player, "general.unknown-player", "player", args[1]);
                    return true;
                }
                if (sub.equals("trust")) {
                    p.trusted().add(other.uuid());
                    plugin.msg().send(player, "property.trusted", "player", other.name(), "id", p.id());
                } else {
                    p.trusted().remove(other.uuid());
                    plugin.msg().send(player, "property.untrusted", "player", other.name(), "id", p.id());
                }
                properties.save();
            }
            default -> plugin.msg().send(player, "property.usage", "label", label);
        }
        return true;
    }

    /** Property by id from args[index], or the one the player stands in. */
    private Property target(Player player, String[] args, int index) {
        Property p = args.length > index ? plugin.properties().get(args[index]) : plugin.properties().at(player.getLocation());
        if (p == null) {
            plugin.msg().send(player, args.length > index ? "property.unknown" : "property.none-here",
                    "id", args.length > index ? args[index] : "", "label", "property");
        }
        return p;
    }

    private String districtOf(Property p) {
        return p.district().isEmpty() ? "-" : p.district();
    }

    private void showInfo(Player player, Property p, String label) {
        String owner = p.forSale() ? plugin.msg().get("property.for-sale") : p.ownerName();
        plugin.msg().line(player, "property.info", "id", p.id(), "district", districtOf(p),
                "price", Money.format(p.price()), "size", p.size(), "owner", owner);
        if (p.forSale()) {
            plugin.msg().line(player, "property.info-buy-hint", "label", label, "id", p.id());
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return Cmd.filter(args[0], "list", "info", "buy", "sell", "mine", "trust", "untrust");
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        List<String> ids = new ArrayList<>();
        if (args.length == 2 && (sub.equals("info") || sub.equals("buy"))) {
            for (Property p : plugin.properties().all()) {
                if (sub.equals("info") || p.forSale()) {
                    ids.add(p.id());
                }
            }
            return Cmd.filter(ids, args[1]);
        }
        if (args.length == 2 && sub.equals("list")) {
            List<String> districts = new ArrayList<>();
            for (Property p : plugin.properties().all()) {
                if (!p.district().isEmpty() && !districts.contains(p.district())) {
                    districts.add(p.district());
                }
            }
            return Cmd.filter(districts, args[1]);
        }
        if (sender instanceof Player player && ((args.length == 2 && sub.equals("sell"))
                || (args.length == 3 && (sub.equals("trust") || sub.equals("untrust"))))) {
            for (Property p : plugin.properties().ownedBy(player.getUniqueId())) {
                ids.add(p.id());
            }
            return Cmd.filter(ids, args[args.length - 1]);
        }
        if (args.length == 2 && (sub.equals("trust") || sub.equals("untrust"))) {
            return Cmd.filter(plugin.storage().knownNames(), args[1]);
        }
        return List.of();
    }
}
