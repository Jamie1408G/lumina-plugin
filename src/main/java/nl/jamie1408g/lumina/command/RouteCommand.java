package nl.jamie1408g.lumina.command;

import nl.jamie1408g.lumina.LuminaPlugin;
import nl.jamie1408g.lumina.network.Network;
import nl.jamie1408g.lumina.network.Route;
import nl.jamie1408g.lumina.network.Station;
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

        Station from = null;
        Station to = null;
        // Try every split point, so names with spaces work: /route tanjong gateway vivora
        for (int split = 1; split < args.length && (from == null || to == null); split++) {
            Station a = network.findStation(Cmd.join(args, 0, split));
            Station b = network.findStation(Cmd.join(args, split));
            if (a != null && b != null) {
                from = a;
                to = b;
            }
        }

        if (from == null || to == null) {
            // One station given: plan from the nearest station.
            to = network.findStation(Cmd.join(args, 0));
            if (to == null) {
                plugin.msg().send(sender, "route.unknown-station", "station", Cmd.join(args, 0));
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
            from = nearest.station();
            plugin.msg().send(sender, "route.from-nearest", "station", from.name(),
                    "distance", Cmd.distance(Math.round(nearest.distance())));
        }

        if (from == to) {
            plugin.msg().send(sender, "route.same-station", "station", from.name());
            return true;
        }
        Route route = plugin.network().planner().plan(from, to);
        if (route == null) {
            plugin.msg().send(sender, "route.no-route", "from", from.name(), "to", to.name());
            return true;
        }
        show(sender, route);
        return true;
    }

    private void show(CommandSender sender, Route route) {
        FileConfiguration config = plugin.getConfig();
        int seconds = route.estimateSeconds(config.getDouble("planner.train-speed-blocks-per-second", 12),
                config.getInt("planner.dwell-seconds", 15), config.getInt("planner.transfer-seconds", 60));
        long fare = plugin.network().fareBetween(route.from(), route.to());

        plugin.msg().line(sender, "route.header", "from", route.from().name(), "to", route.to().name());
        plugin.msg().line(sender, "route.summary", "distance", Cmd.distance(route.distance()),
                "stops", route.stops(), "transfers", route.transfers(), "time", Cmd.duration(seconds),
                "fare", Money.format(fare));
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
        if (!route.to().info().isEmpty()) {
            plugin.msg().line(sender, "route.destination-info", "info", route.to().info());
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length <= 2) {
            return plugin.network().network().completions(args[args.length - 1]);
        }
        return List.of();
    }
}
