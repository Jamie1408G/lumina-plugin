package nl.jamie1408g.lumina.command;

import nl.jamie1408g.lumina.LuminaPlugin;
import nl.jamie1408g.lumina.card.FaregateListener;
import nl.jamie1408g.lumina.city.CityManager;
import nl.jamie1408g.lumina.card.ModelGateManager;
import nl.jamie1408g.lumina.jobs.JobManager;
import nl.jamie1408g.lumina.network.Station;
import nl.jamie1408g.lumina.property.Property;
import nl.jamie1408g.lumina.shop.ShopManager;
import nl.jamie1408g.lumina.storage.CardRecord;
import nl.jamie1408g.lumina.storage.PlayerData;
import nl.jamie1408g.lumina.util.Money;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** /lumina - overview for players, plus all admin tools. */
public final class LuminaCommand implements TabExecutor {

    private final LuminaPlugin plugin;

    public LuminaCommand(LuminaPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "" -> overview(sender);
            case "help" -> {
                plugin.msg().line(sender, "admin.player-help");
                if (sender.hasPermission("lumina.admin")) {
                    plugin.msg().line(sender, "admin.admin-help");
                }
            }
            case "reload" -> {
                if (check(sender, "lumina.admin.reload")) {
                    plugin.reload();
                    plugin.msg().send(sender, "admin.reloaded", "lines", plugin.network().network().lines().size(),
                            "stations", plugin.network().network().stations().size(),
                            "quests", plugin.quests().quests().size(), "jobs", plugin.jobs().roles().size());
                }
            }
            case "setstation" -> {
                if (!check(sender, "lumina.admin.network") || !(sender instanceof Player player)) {
                    playersOnly(sender);
                    return true;
                }
                Station station = args.length < 2 ? null : plugin.network().network().findStation(Cmd.join(args, 1));
                if (station == null) {
                    plugin.msg().send(sender, "route.unknown-station", "station", args.length < 2 ? "" : Cmd.join(args, 1));
                    return true;
                }
                plugin.network().setLocation(station, player.getLocation());
                plugin.msg().send(sender, "admin.station-set", "station", station.name(),
                        "x", player.getLocation().getBlockX(), "y", player.getLocation().getBlockY(),
                        "z", player.getLocation().getBlockZ());
            }
            case "network" -> {
                if (!check(sender, "lumina.admin.network")) {
                    return true;
                }
                List<String> missing = new ArrayList<>();
                for (Station station : plugin.network().network().stations()) {
                    if (station.location() == null) {
                        missing.add(station.codeLabel() + " " + station.name());
                    }
                }
                plugin.msg().line(sender, "admin.network-status", "lines", plugin.network().network().lines().size(),
                        "stations", plugin.network().network().stations().size(), "missing", missing.size());
                if (!missing.isEmpty()) {
                    plugin.msg().line(sender, "admin.network-missing", "list", String.join("&7, &f", missing));
                }
                for (String warning : plugin.network().network().warnings()) {
                    plugin.msg().line(sender, "admin.network-warning", "warning", warning);
                }
            }
            case "pos1", "pos2" -> {
                if (!check(sender, "lumina.admin.property") || !(sender instanceof Player player)) {
                    playersOnly(sender);
                    return true;
                }
                plugin.properties().setPos(player, sub.equals("pos1") ? 0 : 1);
            }
            case "property" -> property(sender, args);
            case "gate", "gates" -> gate(sender, args);
            case "where" -> {
                if (!(sender instanceof Player player)) {
                    playersOnly(sender);
                    return true;
                }
                plugin.msg().send(sender, "city.where", "place", plugin.city().describe(player.getLocation()));
            }
            case "city" -> city(sender, args);
            case "shop" -> {
                plugin.msg().line(sender, "shop.list-header", "count", plugin.shop().items().size());
                for (ShopManager.Item item : plugin.shop().items()) {
                    plugin.msg().line(sender, "shop.list-entry", "item", item.label(), "price", Money.format(item.price()),
                            "id", item.key());
                }
            }
            case "district", "districts" -> district(sender, args);
            case "givecard" -> {
                if (!check(sender, "lumina.admin.card")) {
                    return true;
                }
                Player target = args.length < 2 ? null : Bukkit.getPlayerExact(args[1]);
                if (target == null) {
                    plugin.msg().send(sender, "general.unknown-player", "player", args.length < 2 ? "" : args[1]);
                    return true;
                }
                long value = args.length >= 3 ? Math.max(0, Money.parse(args[2])) : 0;
                plugin.data(target);
                CardRecord card = plugin.cards().giveFree(target, value);
                plugin.msg().send(sender, "admin.card-given", "player", target.getName(), "id", card.id(),
                        "value", Money.format(card.value()));
            }
            case "card", "blockcard", "unblockcard" -> {
                if (!check(sender, "lumina.admin.card")) {
                    return true;
                }
                CardRecord card = args.length < 2 ? null : plugin.storage().card(args[1]);
                if (card == null) {
                    plugin.msg().send(sender, "admin.unknown-card", "id", args.length < 2 ? "" : args[1]);
                    return true;
                }
                if (!sub.equals("card")) {
                    card.setBlocked(sub.equals("blockcard"));
                    plugin.storage().markDirty();
                }
                PlayerData owner = plugin.storage().get(card.owner());
                plugin.msg().line(sender, "admin.card-info", "id", card.id(),
                        "owner", owner == null ? card.owner().toString() : owner.name(),
                        "value", Money.format(card.value()), "blocked", card.blocked(),
                        "tapped", card.tappedIn() == null ? "-" : card.tappedIn());
            }
            case "job" -> {
                if (!check(sender, "lumina.admin.jobs")) {
                    return true;
                }
                if (args.length < 3) {
                    plugin.msg().send(sender, "admin.job-usage", "label", label);
                    return true;
                }
                PlayerData target = plugin.storage().findByName(args[1]);
                if (target == null) {
                    plugin.msg().send(sender, "general.unknown-player", "player", args[1]);
                    return true;
                }
                JobManager.Role role = args[2].equalsIgnoreCase("none") ? null : plugin.jobs().role(args[2]);
                if (role == null && !args[2].equalsIgnoreCase("none")) {
                    plugin.msg().send(sender, "jobs.unknown", "job", args[2]);
                    return true;
                }
                plugin.jobs().adminSet(target, role);
                plugin.msg().send(sender, "admin.job-set", "player", target.name(),
                        "job", role == null ? "none" : role.name());
            }
            case "questreset" -> {
                if (!check(sender, "lumina.admin.quests")) {
                    return true;
                }
                PlayerData target = args.length < 2 ? null : plugin.storage().findByName(args[1]);
                if (target == null) {
                    plugin.msg().send(sender, "general.unknown-player", "player", args.length < 2 ? "" : args[1]);
                    return true;
                }
                plugin.quests().reset(target, args.length >= 3 ? args[2] : null);
                plugin.msg().send(sender, "admin.quests-reset", "player", target.name());
            }
            default -> plugin.msg().send(sender, "admin.unknown-sub", "label", label);
        }
        return true;
    }

    private void overview(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            plugin.msg().line(sender, "admin.player-help");
            plugin.msg().line(sender, "admin.admin-help");
            return;
        }
        PlayerData data = plugin.data(player);
        JobManager.Role role = plugin.jobs().roleOf(data);
        List<CardRecord> cards = plugin.cards().activeCards(player.getUniqueId());
        plugin.msg().line(player, "admin.overview", "player", player.getName(),
                "balance", Money.format(data.balance()),
                "card", cards.isEmpty() ? plugin.msg().get("admin.overview-no-card") : Money.format(cards.get(0).value()),
                "job", role == null ? plugin.msg().get("admin.overview-no-job") : role.name(),
                "trips", data.trips(), "distance", Cmd.distance(data.distance()),
                "stations", data.visitedStations().size(),
                "properties", plugin.properties().ownedBy(player.getUniqueId()).size());
        plugin.msg().line(player, "admin.player-help");
    }

    private void property(CommandSender sender, String[] args) {
        if (!check(sender, "lumina.admin.property")) {
            return;
        }
        if (args.length < 2) {
            plugin.msg().send(sender, "admin.property-usage");
            return;
        }
        String action = args[1].toLowerCase(Locale.ROOT);
        if (action.equals("create")) {
            if (!(sender instanceof Player player)) {
                playersOnly(sender);
                return;
            }
            long price = args.length >= 4 ? Money.parse(args[3]) : -1;
            if (args.length < 4 || price < 0 || !args[2].matches("[A-Za-z0-9_-]{1,32}")) {
                plugin.msg().send(sender, "admin.property-usage");
                return;
            }
            String district = args.length >= 5 ? Cmd.join(args, 4) : "";
            Property created = plugin.properties().create(player, args[2], price, district);
            if (created != null) {
                plugin.msg().send(sender, "admin.property-created", "id", created.id(), "size", created.size(),
                        "price", Money.format(created.price()));
            }
            return;
        }
        Property p = args.length >= 3 ? plugin.properties().get(args[2]) : null;
        if (p == null) {
            plugin.msg().send(sender, "property.unknown", "id", args.length >= 3 ? args[2] : "");
            return;
        }
        switch (action) {
            case "delete" -> {
                plugin.properties().delete(p);
                plugin.msg().send(sender, "admin.property-deleted", "id", p.id());
            }
            case "price" -> {
                long price = args.length >= 4 ? Money.parse(args[3]) : -1;
                if (price < 0) {
                    plugin.msg().send(sender, "general.invalid-amount");
                    return;
                }
                p.setPrice(price);
                plugin.properties().save();
                plugin.msg().send(sender, "admin.property-price", "id", p.id(), "price", Money.format(price));
            }
            case "evict" -> {
                plugin.properties().evict(p);
                plugin.msg().send(sender, "admin.property-evicted", "id", p.id());
            }
            default -> plugin.msg().send(sender, "admin.property-usage");
        }
    }

    /** The pos1/pos2 corners as a box on the map, or null (with a message). */
    private CityManager.Box selectionBox(Player player) {
        Location[] sel = plugin.properties().selection(player);
        if (sel == null || sel[0] == null || sel[1] == null || sel[0].getWorld() == null
                || !sel[0].getWorld().equals(sel[1].getWorld())) {
            plugin.msg().send(player, "city.need-selection");
            return null;
        }
        return CityManager.Box.of(sel[0].getWorld().getName(), sel[0].getBlockX(), sel[0].getBlockZ(),
                sel[1].getBlockX(), sel[1].getBlockZ());
    }

    private static String describe(CityManager.Box b) {
        return "x " + b.minX() + " to " + b.maxX() + ", z " + b.minZ() + " to " + b.maxZ() + " (" + b.world() + ")";
    }

    /** /lumina city set | info */
    private void city(CommandSender sender, String[] args) {
        if (!check(sender, "lumina.admin.city")) {
            return;
        }
        String action = args.length < 2 ? "info" : args[1].toLowerCase(Locale.ROOT);
        if (action.equals("set")) {
            if (!(sender instanceof Player player)) {
                playersOnly(sender);
                return;
            }
            CityManager.Box box = selectionBox(player);
            if (box != null) {
                plugin.city().setCity(box);
                plugin.msg().send(sender, "city.set", "area", describe(box));
            }
            return;
        }
        CityManager.Box c = plugin.city().city();
        plugin.msg().send(sender, c == null ? "city.info-none" : "city.info", "area", c == null ? "" : describe(c),
                "districts", plugin.city().districts().size());
    }

    /** /lumina district add <name> | remove <name> | list */
    private void district(CommandSender sender, String[] args) {
        if (!check(sender, "lumina.admin.city")) {
            return;
        }
        String action = args.length < 2 ? "list" : args[1].toLowerCase(Locale.ROOT);
        String name = args.length < 3 ? "" : Cmd.join(args, 2);
        switch (action) {
            case "add" -> {
                if (!(sender instanceof Player player)) {
                    playersOnly(sender);
                    return;
                }
                if (name.isBlank()) {
                    plugin.msg().send(sender, "city.district-usage");
                    return;
                }
                CityManager.Box box = selectionBox(player);
                if (box != null) {
                    plugin.city().addDistrict(name, box);
                    plugin.msg().send(sender, "city.district-added", "district", name.trim(), "area", describe(box));
                }
            }
            case "remove", "delete" -> plugin.msg().send(sender,
                    !name.isBlank() && plugin.city().removeDistrict(name) ? "city.district-removed" : "city.district-unknown",
                    "district", name);
            case "list" -> {
                plugin.msg().line(sender, "city.district-header", "count", plugin.city().districts().size());
                for (CityManager.District d : plugin.city().districts()) {
                    plugin.msg().line(sender, "city.district-entry", "district", d.name(), "area", describe(d.box()));
                }
            }
            default -> plugin.msg().send(sender, "city.district-usage");
        }
    }

    /** /lumina gate place <station> [in|out|both] | remove | rebuild | list */
    private void gate(CommandSender sender, String[] args) {
        if (!check(sender, "lumina.admin.signs")) {
            return;
        }
        ModelGateManager gates = plugin.modelGates();
        String action = args.length < 2 ? "list" : args[1].toLowerCase(Locale.ROOT);
        switch (action) {
            case "list" -> {
                plugin.msg().line(sender, "modelgate.list-header", "count", gates.all().size());
                for (ModelGateManager.Gate g : gates.all()) {
                    Station st = plugin.network().network().byKey(g.stationKey());
                    plugin.msg().line(sender, "modelgate.list-entry", "station", st == null ? g.stationKey() : st.name(),
                            "mode", g.mode().name().toLowerCase(Locale.ROOT), "x", g.x(), "y", g.y(), "z", g.z(), "world", g.world());
                }
            }
            case "place" -> {
                if (!(sender instanceof Player player)) {
                    playersOnly(sender);
                    return;
                }
                if (args.length < 3) {
                    plugin.msg().send(sender, "modelgate.usage");
                    return;
                }
                FaregateListener.Mode mode = FaregateListener.Mode.BOTH;
                int end = args.length;
                String last = args[args.length - 1].toLowerCase(Locale.ROOT);
                if (args.length >= 4 && (last.equals("in") || last.equals("out") || last.equals("both"))) {
                    mode = FaregateListener.Mode.valueOf(last.toUpperCase(Locale.ROOT));
                    end = args.length - 1;
                }
                Station station = plugin.network().network().findStation(Cmd.join(args, 2, end));
                if (station == null) {
                    plugin.msg().send(sender, "route.unknown-station", "station", Cmd.join(args, 2, end));
                    return;
                }
                String problem = gates.place(player, station, mode);
                if (problem != null) {
                    plugin.msg().send(sender, problem);
                    return;
                }
                plugin.msg().send(sender, "modelgate.placed", "station", station.name(), "mode", mode.name().toLowerCase(Locale.ROOT));
            }
            case "remove" -> {
                if (!(sender instanceof Player player)) {
                    playersOnly(sender);
                    return;
                }
                ModelGateManager.Gate g = gates.near(player.getLocation(), 3);
                if (g == null) {
                    plugin.msg().send(sender, "modelgate.none-near");
                    return;
                }
                gates.remove(g);
                plugin.msg().send(sender, "modelgate.removed");
            }
            case "rebuild" -> {
                int[] r = gates.rebuildAll();
                plugin.msg().send(sender, "modelgate.rebuilt", "count", r[0], "skipped", r[1]);
            }
            default -> plugin.msg().send(sender, "modelgate.usage");
        }
    }

    private boolean check(CommandSender sender, String permission) {
        if (sender.hasPermission(permission)) {
            return true;
        }
        plugin.msg().send(sender, "general.no-permission");
        return false;
    }

    private void playersOnly(CommandSender sender) {
        if (!(sender instanceof Player)) {
            plugin.msg().send(sender, "general.players-only");
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> subs = new ArrayList<>(List.of("help", "where", "shop"));
            if (sender.hasPermission("lumina.admin")) {
                subs.addAll(List.of("reload", "setstation", "network", "pos1", "pos2", "city", "district", "property", "gate", "givecard",
                        "card", "blockcard", "unblockcard", "job", "questreset"));
            }
            return Cmd.filter(subs, args[0]);
        }
        if (!sender.hasPermission("lumina.admin")) {
            return List.of();
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        if (args.length == 2) {
            return switch (sub) {
                case "setstation" -> plugin.network().network().completions(args[1]);
                case "property" -> Cmd.filter(args[1], "create", "delete", "price", "evict");
                case "gate", "gates" -> Cmd.filter(args[1], "place", "remove", "rebuild", "list");
                case "city" -> Cmd.filter(args[1], "set", "info");
                case "district", "districts" -> Cmd.filter(args[1], "add", "remove", "list");
                case "givecard", "job", "questreset" -> Cmd.filter(plugin.storage().knownNames(), args[1]);
                default -> List.of();
            };
        }
        if (args.length == 3 && sub.equals("job")) {
            List<String> ids = new ArrayList<>(List.of("none"));
            for (JobManager.Role role : plugin.jobs().roles()) {
                ids.add(role.id());
            }
            return Cmd.filter(ids, args[2]);
        }
        if (args.length >= 3 && (sub.equals("gate") || sub.equals("gates")) && args[1].equalsIgnoreCase("place")) {
            List<String> options = new ArrayList<>(plugin.network().network().completions(args[args.length - 1]));
            if (args.length >= 4) {
                options.addAll(Cmd.filter(args[args.length - 1], "in", "out", "both"));
            }
            return options;
        }
        if (args.length == 3 && (sub.equals("district") || sub.equals("districts")) && args[1].equalsIgnoreCase("remove")) {
            List<String> names = new ArrayList<>();
            for (CityManager.District d : plugin.city().districts()) {
                names.add(d.name());
            }
            return Cmd.filter(names, args[2]);
        }
        if (args.length == 3 && sub.equals("questreset")) {
            List<String> ids = new ArrayList<>();
            plugin.quests().quests().forEach(q -> ids.add(q.id()));
            return Cmd.filter(ids, args[2]);
        }
        if (args.length == 3 && sub.equals("property") && !args[1].equalsIgnoreCase("create")) {
            List<String> ids = new ArrayList<>();
            plugin.properties().all().forEach(p -> ids.add(p.id()));
            return Cmd.filter(ids, args[2]);
        }
        return List.of();
    }
}
