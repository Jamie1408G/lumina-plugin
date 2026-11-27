package nl.jamie1408g.lumina.network;

import nl.jamie1408g.lumina.card.FareCalculator;
import nl.jamie1408g.lumina.util.Money;
import org.bukkit.Location;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.logging.Level;

/** Loads network.yml, keeps the planner and fares, and saves station locations. */
public final class NetworkService {

    private final JavaPlugin plugin;
    private final File file;
    private YamlConfiguration yaml = new YamlConfiguration();
    private Network network = new Network();
    private RoutePlanner planner = new RoutePlanner(network);
    private FareCalculator fares = new FareCalculator(80, 40, 80, 300);

    public NetworkService(JavaPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "network.yml");
    }

    public void load() {
        if (!file.exists()) {
            plugin.saveResource("network.yml", false);
        }
        yaml = YamlConfiguration.loadConfiguration(file);
        FileConfiguration config = plugin.getConfig();

        Network fresh = new Network();
        fresh.setSettings(new Network.Settings(
                Math.max(1, config.getInt("planner.default-hop-distance", 400)),
                Math.max(0.1, config.getDouble("planner.distance-factor", 1.15)),
                Math.max(0, config.getInt("planner.transfer-penalty", 600))));

        ConfigurationSection lines = yaml.getConfigurationSection("lines");
        if (lines != null) {
            for (String code : lines.getKeys(false)) {
                ConfigurationSection line = lines.getConfigurationSection(code);
                if (line == null) {
                    continue;
                }
                List<String> stations = line.getStringList("stations");
                fresh.addLine(code, line.getString("name", code), line.getString("color", "&f"),
                        line.getBoolean("loop", false), stations);
            }
        }

        ConfigurationSection info = yaml.getConfigurationSection("station-info");
        if (info != null) {
            for (String name : info.getKeys(false)) {
                fresh.setStationInfo(name, info.getString(name, ""));
            }
        }

        ConfigurationSection locations = yaml.getConfigurationSection("locations");
        if (locations != null) {
            for (String name : locations.getKeys(false)) {
                ConfigurationSection loc = locations.getConfigurationSection(name);
                Station station = fresh.byKey(name);
                if (loc == null || station == null) {
                    continue;
                }
                station.setLocation(new StationLocation(loc.getString("world", "world"),
                        loc.getDouble("x"), loc.getDouble("y"), loc.getDouble("z")));
            }
        }

        for (String warning : fresh.warnings()) {
            plugin.getLogger().warning("network.yml: " + warning);
        }
        plugin.getLogger().info("Loaded " + fresh.lines().size() + " lines and " + fresh.stations().size() + " stations.");

        network = fresh;
        planner = new RoutePlanner(network);
        fares = new FareCalculator(
                Money.fromDouble(config.getDouble("fares.base", 0.80)),
                Money.fromDouble(config.getDouble("fares.per-km", 0.40)),
                Money.fromDouble(config.getDouble("fares.min", 0.80)),
                Money.fromDouble(config.getDouble("fares.max", 3.00)));
    }

    public Network network() {
        return network;
    }

    public RoutePlanner planner() {
        return planner;
    }

    public FareCalculator fares() {
        return fares;
    }

    /** Fare between two stations, or the max fare if they are not connected. */
    public long fareBetween(Station from, Station to) {
        int distance = planner.shortestDistance(from, to);
        return distance < 0 ? fares.max() : fares.fare(distance);
    }

    public void setLocation(Station station, Location location) {
        if (location.getWorld() == null) {
            return;
        }
        station.setLocation(new StationLocation(location.getWorld().getName(),
                location.getX(), location.getY(), location.getZ()));
        String base = "locations." + station.name() + ".";
        yaml.set(base + "world", location.getWorld().getName());
        yaml.set(base + "x", Math.round(location.getX() * 10) / 10.0);
        yaml.set(base + "y", Math.round(location.getY() * 10) / 10.0);
        yaml.set(base + "z", Math.round(location.getZ() * 10) / 10.0);
        try {
            yaml.save(file);
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Could not save network.yml", e);
        }
    }
}
