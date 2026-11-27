package nl.jamie1408g.lumina.command;

import nl.jamie1408g.lumina.LuminaPlugin;
import nl.jamie1408g.lumina.network.Line;
import nl.jamie1408g.lumina.network.Network;
import nl.jamie1408g.lumina.network.Station;
import nl.jamie1408g.lumina.network.StationLocation;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/** /metro lines | line <code> | station <name> | nearest */
public final class MetroCommand implements TabExecutor {

    private final LuminaPlugin plugin;

    public MetroCommand(LuminaPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        Network network = plugin.network().network();
        String sub = args.length == 0 ? "lines" : args[0].toLowerCase();
        switch (sub) {
            case "lines" -> {
                plugin.msg().line(sender, "metro.lines-header");
                for (Line line : network.lines()) {
                    plugin.msg().line(sender, "metro.lines-entry", "color", line.color(), "code", line.code(),
                            "name", line.name(), "type", line.type(), "count", line.stations().size(),
                            "first", line.first().name(), "last", line.last().name());
                }
                plugin.msg().line(sender, "metro.lines-hint", "label", label);
            }
            case "line" -> {
                Line line = args.length < 2 ? null : network.line(args[1]);
                if (line == null) {
                    plugin.msg().send(sender, "metro.unknown-line", "line", args.length < 2 ? "" : args[1]);
                    return true;
                }
                plugin.msg().line(sender, "metro.line-header", "color", line.color(), "code", line.code(),
                        "name", line.name());
                for (Station station : line.stations()) {
                    StringBuilder transfers = new StringBuilder();
                    for (String other : station.lineCodes()) {
                        Line otherLine = network.line(other);
                        if (otherLine != null && otherLine != line) {
                            transfers.append(' ').append(otherLine.color()).append(otherLine.code());
                        }
                    }
                    String code = station.codeOn(line.code());
                    plugin.msg().line(sender, transfers.length() > 0 ? "metro.line-interchange" : "metro.line-station",
                            "color", line.color(), "code", code == null ? "--" : code, "name", station.name(),
                            "transfers", transfers.toString().trim());
                }
                if (line.isLoop()) {
                    plugin.msg().line(sender, "metro.line-loop");
                }
            }
            case "station" -> {
                Station station = args.length < 2 ? null : network.findStation(Cmd.join(args, 1));
                if (station == null) {
                    plugin.msg().send(sender, "route.unknown-station", "station", args.length < 2 ? "" : Cmd.join(args, 1));
                    return true;
                }
                List<String> lines = new ArrayList<>();
                for (String code : station.lineCodes()) {
                    Line line = network.line(code);
                    if (line != null) {
                        lines.add(line.color() + line.name());
                    }
                }
                plugin.msg().line(sender, "metro.station-header", "name", station.name(), "codes", station.codeLabel());
                plugin.msg().line(sender, "metro.station-lines", "lines", String.join("&7, ", lines));
                if (!station.info().isEmpty()) {
                    plugin.msg().line(sender, "metro.station-info", "info", station.info());
                }
                StationLocation loc = station.location();
                if (loc != null && sender instanceof Player player && player.getWorld().getName().equals(loc.world())) {
                    double distance = loc.horizontalDistance(loc.world(), player.getLocation().getX(), player.getLocation().getZ());
                    plugin.msg().line(sender, "metro.station-distance", "distance", Cmd.distance(Math.round(distance)),
                            "direction", Cmd.compass(player.getLocation().getX(), player.getLocation().getZ(), loc.x(), loc.z()));
                } else if (loc == null) {
                    plugin.msg().line(sender, "metro.station-no-location");
                }
            }
            case "nearest" -> {
                if (!(sender instanceof Player player)) {
                    plugin.msg().send(sender, "general.players-only");
                    return true;
                }
                Network.Nearest nearest = network.nearest(player.getWorld().getName(),
                        player.getLocation().getX(), player.getLocation().getZ());
                if (nearest == null) {
                    plugin.msg().send(sender, "route.no-nearest", "label", "route");
                    return true;
                }
                StationLocation loc = nearest.station().location();
                plugin.msg().send(sender, "metro.nearest", "name", nearest.station().name(),
                        "codes", nearest.station().codeLabel(),
                        "distance", Cmd.distance(Math.round(nearest.distance())),
                        "direction", Cmd.compass(player.getLocation().getX(), player.getLocation().getZ(), loc.x(), loc.z()));
            }
            default -> plugin.msg().send(sender, "metro.usage", "label", label);
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return Cmd.filter(args[0], "lines", "line", "station", "nearest");
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("line")) {
            List<String> codes = new ArrayList<>();
            for (Line line : plugin.network().network().lines()) {
                codes.add(line.code());
            }
            return Cmd.filter(codes, args[1]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("station")) {
            return plugin.network().network().completions(args[1]);
        }
        return List.of();
    }
}
