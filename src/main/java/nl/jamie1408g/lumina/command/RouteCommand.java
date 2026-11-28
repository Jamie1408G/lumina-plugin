package nl.jamie1408g.lumina.command;

import nl.jamie1408g.lumina.LuminaPlugin;
import nl.jamie1408g.lumina.network.Network;
import nl.jamie1408g.lumina.network.Route;
import nl.jamie1408g.lumina.util.Money;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;

import java.util.List;

/** /route [from] <to> - the journey planner. */
public final class RouteCommand implements TabExecutor {

    private final LuminaPlugin plugin;

    public RouteCommand(LuminaPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        Network network = plugin.network().network();
        if (args.length == 0) {
            plugin.msg().send(sender, "route.usage", "label", label);
            return true;
        }
        // /route stop and /route xaero [on|off], unless a station or place is called that.
        String first = args[0].toLowerCase(java.util.Locale.ROOT);
        if (args.length <= 2 && (first.equals("stop") || first.equals("xaero"))
                && network.byKey(first) == null && network.findPlace(first) == null) {
            if (!(sender instanceof Player player)) {
                plugin.msg().send(sender, "general.players-only");
                return true;
            }
            if (first.equals("stop")) {
                plugin.msg().send(sender, plugin.guide().stop(player.getUniqueId()) ? "guide.stopped" : "guide.none");
            } else {
                var data = plugin.data(player);
                boolean on = args.length < 2 ? !data.xaeroWaypoints() : !args[1].equalsIgnoreCase("off");
                data.setXaeroWaypoints(on);
                plugin.storage().markDirty();
                plugin.msg().send(sender, on ? "guide.xaero-on" : "guide.xaero-off");
            }
            return true;
        }

        // Stations and places (like "Awards Center") both work, at either end.
        Network.Destination from = null;
        Network.Destination to = null;
        // Try every split point, so names with spaces work: /route tanjong gateway awards center
        for (int split = 1; split < args.length && (from == null || to == null); split++) {
            Network.Destination a = network.findDestination(Cmd.join(args, 0, split));
            Network.Destination b = network.findDestination(Cmd.join(args, split));
            if (a != null && b != null) {
                from = a;
                to = b;
            }
        }

        if (from == null || to == null) {
            // One station or place given: plan from the nearest station.
            to = network.findDestination(Cmd.join(args, 0));
            if (to == null) {
                plugin.msg().send(sender, "route.unknown-destination", "station", Cmd.join(args, 0));
                return true;
            }
            if (!(sender instanceof Player player)) {
                plugin.msg().send(sender, "route.usage", "label", label);
                return true;
            }
            Network.Nearest nearest = network.nearest(player.getWorld().getName(),
                    player.getLocation().getX(), player.getLocation().getZ());
            if (nearest == null) {
                plugin.msg().send(sender, "route.no-nearest", "label", label);
                return true;
            }
            from = Network.Destination.of(nearest.station());
            plugin.msg().send(sender, "route.from-nearest", "station", nearest.station().name(),
                    "distance", Cmd.distance(Math.round(nearest.distance())));
        }

        Route route = plugin.network().planner().planBest(from.stations(), to.stations());
        if (route == null) {
            plugin.msg().send(sender, "route.no-route", "from", from.name(), "to", to.name());
            return true;
        }
        if (route.legs().isEmpty()) {
            if (to.place() != null) {
                plugin.msg().send(sender, "route.already-there", "place", to.place().name(), "station", route.to().name());
                if (sender instanceof Player player) {
                    plugin.guide().plan(player, route, to.place(), true);
                }
            } else {
                plugin.msg().send(sender, "route.same-station", "station", route.to().name());
            }
            return true;
        }
        show(sender, route, from, to);
        if (sender instanceof Player player && plugin.guide().plan(player, route, to.place(), false)) {
            boolean toPlace = to.place() != null && to.place().location() != null;
            if (route.transfers() > 0) {
                plugin.msg().line(sender, "guide.hint-change", "station", route.legs().get(1).from().name());
            }
            if (toPlace) {
                plugin.msg().line(sender, "guide.hint", "place", to.place().name(), "station", route.to().name());
            }
        }
        return true;
    }

    private void show(CommandSender sender, Route route, Network.Destination from, Network.Destination to) {
        FileConfiguration config = plugin.getConfig();
        int seconds = route.estimateSeconds(config.getDouble("planner.train-speed-blocks-per-second", 12),
                config.getInt("planner.dwell-seconds", 15), config.getInt("planner.transfer-seconds", 60));
        long fare = plugin.network().fareBetween(route.from(), route.to());

        plugin.msg().line(sender, "route.header", "from", from.name(), "to", to.name());
        plugin.msg().line(sender, "route.summary", "distance", Cmd.distance(route.distance()),
                "stops", route.stops(), "transfers", route.transfers(), "time", Cmd.duration(seconds),
                "fare", Money.format(fare));
        if (from.place() != null) {
            plugin.msg().line(sender, "route.start-at", "station", route.from().name(), "place", from.place().name());
        }
        int step = 1;
        for (int i = 0; i < route.legs().size(); i++) {
            Route.Leg leg = route.legs().get(i);
            if (i > 0) {
                plugin.msg().line(sender, "route.transfer", "station", leg.from().name(),
                        "line", leg.line().color() + leg.line().name());
            }
            String fromCode = leg.from().codeOn(leg.line().code());
            String toCode = leg.to().codeOn(leg.line().code());
            plugin.msg().line(sender, "route.leg", "step", step++, "color", leg.line().color(),
                    "line", leg.line().name(), "code", leg.line().code(), "towards", leg.towards());
            plugin.msg().line(sender, "route.leg-stations",
                    "from", (fromCode == null ? "" : fromCode + " ") + leg.from().name(),
                    "to", (toCode == null ? "" : toCode + " ") + leg.to().name(),
                    "stops", leg.stops(), "stops-word", leg.stops() == 1 ? "stop" : "stops");
        }
        if (to.place() != null) {
            plugin.msg().line(sender, "route.alight-for", "station", route.to().name(), "place", to.place().name());
        }
        if (!route.to().info().isEmpty()) {
            plugin.msg().line(sender, "route.destination-info", "info", route.to().info());
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> options = new java.util.ArrayList<>(plugin.network().network().destinationCompletions(args[0]));
            options.addAll(Cmd.filter(args[0], "stop", "xaero"));
            return options;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("xaero")) {
            return Cmd.filter(args[1], "on", "off");
        }
        if (args.length <= 2) {
            return plugin.network().network().destinationCompletions(args[args.length - 1]);
        }
        return List.of();
    }
}
