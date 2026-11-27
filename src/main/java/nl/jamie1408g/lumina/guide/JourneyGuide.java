package nl.jamie1408g.lumina.guide;

import nl.jamie1408g.lumina.LuminaPlugin;
import nl.jamie1408g.lumina.api.TripCompletedEvent;
import nl.jamie1408g.lumina.network.Place;
import nl.jamie1408g.lumina.network.Station;
import nl.jamie1408g.lumina.network.StationLocation;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.vehicle.VehicleExitEvent;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Guides a player from the station to a place. After /route to a place (with a location), a bar
 * shows where to get off. Once they get off the train there (or tap out at that station), the place
 * is highlighted: a bar with the distance and an arrow that follows where they look, a particle trail
 * towards it, a light column on the spot and, for players who turned it on, a waypoint for Xaero's
 * Minimap. It ends when they arrive, with /route stop, or after a while.
 */
public final class JourneyGuide implements Listener {

    private enum Stage { WAITING, GUIDING }

    private static final class Guide {
        final String placeName;
        final StationLocation target;
        final String stationKey;
        final String stationName;
        final BossBar bar;
        Stage stage = Stage.WAITING;
        long since = System.currentTimeMillis();
        double startDistance = 1;

        Guide(String placeName, StationLocation target, String stationKey, String stationName, BossBar bar) {
            this.placeName = placeName;
            this.target = target;
            this.stationKey = stationKey;
            this.stationName = stationName;
            this.bar = bar;
        }
    }

    private static final String[] ARROWS = {"↑", "↗", "→", "↘", "↓", "↙", "←", "↖"};
    private static final Particle.DustOptions TRAIL = new Particle.DustOptions(Color.fromRGB(0x55, 0xFF, 0x55), 1.2f);

    private final LuminaPlugin plugin;
    private final Map<UUID, Guide> guides = new HashMap<>();
    private BukkitTask task;

    private boolean enabled = true;
    private boolean showBar = true;
    private boolean trail = true;
    private double stationRadius = 48;
    private double arriveRadius = 8;
    private long waitingMillis = 45 * 60_000L;
    private long guidingMillis = 10 * 60_000L;
    private int xaeroColor = 11;

    public JourneyGuide(LuminaPlugin plugin) {
        this.plugin = plugin;
    }

    public void load() {
        FileConfiguration c = plugin.getConfig();
        enabled = c.getBoolean("guide.enabled", true);
        showBar = c.getBoolean("guide.bossbar", true);
        trail = c.getBoolean("guide.trail", true);
        stationRadius = Math.max(4, c.getDouble("guide.station-radius", 48));
        arriveRadius = Math.max(2, c.getDouble("guide.arrive-radius", 8));
        waitingMillis = Math.max(1, c.getLong("guide.waiting-minutes", 45)) * 60_000L;
        guidingMillis = Math.max(1, c.getLong("guide.guiding-minutes", 10)) * 60_000L;
        xaeroColor = Math.max(0, Math.min(15, c.getInt("guide.xaero-color", 11)));
        if (task == null) {
            task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 10L, 10L);
        }
    }

    public boolean enabled() {
        return enabled;
    }

    // ------------------------------------------------------------ starting and stopping

    /**
     * Called after /route to a place. alightAt is the station the planner picked. alreadyThere:
     * the player is at that station now, so guiding starts straight away. False if the place has
     * no location (nothing to guide to) or guiding is off.
     */
    public boolean plan(Player player, Place place, Station alightAt, boolean alreadyThere) {
        if (!enabled || place.location() == null) {
            return false;
        }
        stop(player.getUniqueId());
        BossBar bar = null;
        if (showBar) {
            bar = Bukkit.createBossBar(plugin.msg().get("guide.bar-waiting", "place", place.name(),
                    "station", alightAt.name()), BarColor.BLUE, BarStyle.SOLID);
            bar.setProgress(0);
            bar.addPlayer(player);
        }
        Guide guide = new Guide(place.name(), place.location(), alightAt.key(), alightAt.name(), bar);
        guides.put(player.getUniqueId(), guide);
        if (alreadyThere) {
            begin(player, guide);
        }
        return true;
    }

    /** /route stop. False if nothing was running. */
    public boolean stop(UUID id) {
        Guide guide = guides.remove(id);
        if (guide == null) {
            return false;
        }
        if (guide.bar != null) {
            guide.bar.removeAll();
        }
        return true;
    }

    public void stopAll() {
        for (Guide guide : guides.values()) {
            if (guide.bar != null) {
                guide.bar.removeAll();
            }
        }
        guides.clear();
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    /** Got off at the right station: highlight the place. */
    private void begin(Player player, Guide guide) {
        guide.stage = Stage.GUIDING;
        guide.since = System.currentTimeMillis();
        double distance = distance(player.getLocation(), guide.target);
        guide.startDistance = Math.max(1, distance);
        if (guide.bar != null) {
            guide.bar.setColor(BarColor.GREEN);
        }
        plugin.msg().send(player, "guide.start", "place", guide.placeName,
                "distance", Math.round(distance), "direction", compass(player.getLocation(), guide.target));
        if (plugin.data(player).xaeroWaypoints()) {
            sendXaeroWaypoint(player, guide);
        }
        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_CHIME, 0.8f, 1.4f);
        if (distance >= 0 && distance <= arriveRadius) {
            arrive(player, guide);
        }
    }

    private void arrive(Player player, Guide guide) {
        stop(player.getUniqueId());
        player.sendTitle(plugin.msg().get("guide.arrived-title", "place", guide.placeName),
                plugin.msg().get("guide.arrived-subtitle", "place", guide.placeName), 10, 50, 15);
        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_CHIME, 1f, 2f);
    }

    // ------------------------------------------------------------ getting off at the right station

    @EventHandler(priority = EventPriority.MONITOR)
    public void onTapOut(TripCompletedEvent event) {
        Guide guide = guides.get(event.getPlayer().getUniqueId());
        if (guide != null && guide.stage == Stage.WAITING && event.getTo() != null
                && event.getTo().key().equals(guide.stationKey)) {
            begin(event.getPlayer(), guide);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGetOff(VehicleExitEvent event) {
        if (!(event.getExited() instanceof Player player)) {
            return;
        }
        Guide guide = guides.get(player.getUniqueId());
        if (guide == null || guide.stage != Stage.WAITING) {
            return;
        }
        Station station = plugin.network().network().byKey(guide.stationKey);
        if (station == null || station.location() == null) {
            return; // no location for the station: tapping out there still works
        }
        Location at = event.getVehicle().getLocation();
        double away = at.getWorld() == null ? -1
                : station.location().horizontalDistance(at.getWorld().getName(), at.getX(), at.getZ());
        if (away >= 0 && away <= stationRadius) {
            begin(player, guide);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        stop(event.getPlayer().getUniqueId());
    }

    // ------------------------------------------------------------ every half second

    private void tick() {
        long now = System.currentTimeMillis();
        // A copy, as arriving removes the guide while we go through them.
        for (Map.Entry<UUID, Guide> entry : new ArrayList<>(guides.entrySet())) {
            Guide guide = entry.getValue();
            Player player = Bukkit.getPlayer(entry.getKey());
            long limit = guide.stage == Stage.WAITING ? waitingMillis : guidingMillis;
            if (player == null || now - guide.since > limit) {
                stop(entry.getKey());
                if (player != null) {
                    plugin.msg().send(player, "guide.timeout", "place", guide.placeName);
                }
                continue;
            }
            if (guide.stage == Stage.GUIDING) {
                guideStep(player, guide);
            }
        }
    }

    private void guideStep(Player player, Guide guide) {
        Location l = player.getLocation();
        double distance = distance(l, guide.target);
        if (distance < 0) {
            if (guide.bar != null) {
                guide.bar.setTitle(plugin.msg().get("guide.bar-other-world", "place", guide.placeName));
            }
            return;
        }
        if (distance <= arriveRadius) {
            arrive(player, guide);
            return;
        }
        if (guide.bar != null) {
            guide.bar.setTitle(plugin.msg().get("guide.bar", "place", guide.placeName,
                    "distance", Math.round(distance), "arrow", arrow(l, guide.target)));
            guide.bar.setProgress(Math.max(0, Math.min(1, 1 - distance / guide.startDistance)));
        }
        double dx = guide.target.x() - l.getX();
        double dz = guide.target.z() - l.getZ();
        double length = Math.hypot(dx, dz);
        if (trail && length > 0) {
            // Dots on the ground, from just ahead of the player towards the place.
            double ux = dx / length;
            double uz = dz / length;
            double y = l.getY() + 0.25;
            for (double s = 1.5; s <= Math.min(12, length); s += 1) {
                player.spawnParticle(Particle.DUST, l.getX() + ux * s, y, l.getZ() + uz * s, 1, 0, 0, 0, 0, TRAIL);
            }
        }
        if (distance <= 48) {
            // A column of light on the spot.
            for (double h = 0; h < 12; h += 0.5) {
                player.spawnParticle(Particle.END_ROD, guide.target.x(), guide.target.y() + h, guide.target.z(), 1, 0, 0, 0, 0);
            }
        }
    }

    // ------------------------------------------------------------ Xaero's Minimap

    /**
     * Xaero's Minimap turns chat lines in its share format into a waypoint the player can add with
     * one click: xaero-waypoint:name:initials:x:y:z:colour:false:0:Internal-dimension. Only sent to
     * players who turned it on (/route xaero), as other players would just see the raw line.
     */
    private void sendXaeroWaypoint(Player player, Guide guide) {
        String name = guide.placeName.replace(':', ' ').trim();
        String line = "xaero-waypoint:" + name + ":" + initials(name) + ":"
                + (int) Math.floor(guide.target.x()) + ":" + (int) Math.floor(guide.target.y()) + ":"
                + (int) Math.floor(guide.target.z()) + ":" + xaeroColor + ":false:0:Internal-" + dimension(guide.target.world());
        plugin.msg().send(player, "guide.xaero-intro", "place", guide.placeName);
        player.sendMessage("<Lumina> " + line);
    }

    static String initials(String name) {
        StringBuilder out = new StringBuilder();
        for (String word : name.split("\\s+")) {
            if (!word.isEmpty() && Character.isLetterOrDigit(word.charAt(0)) && out.length() < 2) {
                out.append(Character.toUpperCase(word.charAt(0)));
            }
        }
        return out.length() == 0 ? "L" : out.toString();
    }

    private static String dimension(String worldName) {
        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            return "overworld";
        }
        return switch (world.getEnvironment()) {
            case NETHER -> "the_nether";
            case THE_END -> "the_end";
            default -> "overworld";
        };
    }

    // ------------------------------------------------------------ maths

    /** Horizontal distance from l to the target, or -1 in another world. */
    private static double distance(Location l, StationLocation target) {
        return l.getWorld() == null ? -1 : target.horizontalDistance(l.getWorld().getName(), l.getX(), l.getZ());
    }

    /** Arrow pointing at the target, as seen from where the player is looking. */
    static String arrow(Location l, StationLocation target) {
        return arrow(l.getYaw(), target.x() - l.getX(), target.z() - l.getZ());
    }

    /** Minecraft yaw: 0 = south (+z), 90 = west (-x); turning right makes it bigger. */
    static String arrow(float yaw, double dx, double dz) {
        double towards = Math.toDegrees(Math.atan2(-dx, dz));
        double relative = ((towards - yaw) % 360 + 540) % 360 - 180; // -180 .. 180, 0 = straight ahead
        int index = (int) Math.round(relative / 45);
        return ARROWS[((index % 8) + 8) % 8];
    }

    private static String compass(Location l, StationLocation target) {
        double angle = Math.toDegrees(Math.atan2(target.x() - l.getX(), -(target.z() - l.getZ())));
        if (angle < 0) {
            angle += 360;
        }
        String[] names = {"north", "north-east", "east", "south-east", "south", "south-west", "west", "north-west"};
        return names[(int) Math.round(angle / 45) % 8].toLowerCase(Locale.ROOT);
    }
}
