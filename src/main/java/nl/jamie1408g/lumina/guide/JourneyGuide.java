package nl.jamie1408g.lumina.guide;

import nl.jamie1408g.lumina.LuminaPlugin;
import nl.jamie1408g.lumina.api.TripCompletedEvent;
import nl.jamie1408g.lumina.network.Place;
import nl.jamie1408g.lumina.network.Route;
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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Guides a player along a planned journey. Each change of line is a step: a bar says where to
 * change, and once they get off the train there the way to the other line's platform is
 * highlighted (when that platform has its own spot, /lumina setstation CT2). A place at the end
 * is the last step: after getting off (or tapping out) at its station, the place is highlighted.
 * Highlighting means: a bar with the distance and an arrow that follows where they look, a particle
 * trail towards it, a light column on the spot and, for players who turned it on, a waypoint for
 * Xaero's Minimap. It ends at the last step, with /route stop, or after a while.
 */
public final class JourneyGuide implements Listener {

    private enum Stage { WAITING, GUIDING }

    /** One step: get off at a station (arriving on a line), then maybe walk to a target. */
    private record Step(boolean transfer, String stationKey, String stationName, String arrivingLine,
                        StationLocation target, String targetName, String lineName, String towards) {
    }

    private static final class Guide {
        final List<Step> steps;
        final BossBar bar;
        int index;
        Stage stage = Stage.WAITING;
        long since = System.currentTimeMillis();
        double startDistance = 1;

        Guide(List<Step> steps, BossBar bar) {
            this.steps = steps;
            this.bar = bar;
        }

        Step step() {
            return steps.get(index);
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
     * Called after /route. Makes a step for every change of line and, if place has a location, one
     * for the place at the end. alreadyThere: the player is at the place's station now (an empty
     * route), so the place is highlighted straight away. False if there's nothing to guide.
     */
    public boolean plan(Player player, Route route, Place place, boolean alreadyThere) {
        if (!enabled) {
            return false;
        }
        List<Step> steps = new ArrayList<>();
        List<Route.Leg> legs = route.legs();
        for (int i = 1; i < legs.size(); i++) {
            Route.Leg before = legs.get(i - 1);
            Route.Leg next = legs.get(i);
            Station at = next.from();
            String nextLine = next.line().code();
            steps.add(new Step(true, at.key(), at.name(), before.line().code(), at.platform(nextLine),
                    next.line().color() + next.line().name(), next.line().color() + next.line().name(), next.towards()));
        }
        if (place != null && place.location() != null) {
            String arriving = legs.isEmpty() ? null : legs.get(legs.size() - 1).line().code();
            steps.add(new Step(false, route.to().key(), route.to().name(), arriving, place.location(),
                    place.name(), null, null));
        }
        if (steps.isEmpty()) {
            return false;
        }
        stop(player.getUniqueId());
        BossBar bar = showBar ? Bukkit.createBossBar("", BarColor.BLUE, BarStyle.SOLID) : null;
        if (bar != null) {
            bar.addPlayer(player);
        }
        Guide guide = new Guide(steps, bar);
        guides.put(player.getUniqueId(), guide);
        waitFor(guide);
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

    /** The bar for the step we're riding towards. */
    private void waitFor(Guide guide) {
        guide.stage = Stage.WAITING;
        guide.since = System.currentTimeMillis();
        if (guide.bar == null) {
            return;
        }
        Step step = guide.step();
        guide.bar.setColor(BarColor.BLUE);
        guide.bar.setProgress(0);
        guide.bar.setTitle(step.transfer()
                ? plugin.msg().get("guide.bar-change", "station", step.stationName(), "line", step.lineName(), "towards", step.towards())
                : plugin.msg().get("guide.bar-waiting", "place", step.targetName(), "station", step.stationName()));
    }

    /** Got off at the step's station: highlight where to go (or, without a spot, just say it). */
    private void begin(Player player, Guide guide) {
        Step step = guide.step();
        if (step.target() == null) {
            // A change of line without its own platform spot: say where to go, then wait for the next step.
            plugin.msg().send(player, "guide.change-here", "line", step.lineName(), "towards", step.towards());
            next(player, guide);
            return;
        }
        guide.stage = Stage.GUIDING;
        guide.since = System.currentTimeMillis();
        double distance = distance(player.getLocation(), step.target());
        guide.startDistance = Math.max(1, distance);
        if (guide.bar != null) {
            guide.bar.setColor(BarColor.GREEN);
        }
        if (step.transfer()) {
            plugin.msg().send(player, "guide.start-change", "line", step.lineName(), "towards", step.towards(),
                    "distance", Math.round(distance), "direction", compass(player.getLocation(), step.target()));
        } else {
            plugin.msg().send(player, "guide.start", "place", step.targetName(),
                    "distance", Math.round(distance), "direction", compass(player.getLocation(), step.target()));
            if (plugin.data(player).xaeroWaypoints()) {
                sendXaeroWaypoint(player, step);
            }
        }
        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_CHIME, 0.8f, 1.4f);
        if (distance >= 0 && distance <= arriveRadius) {
            arrive(player, guide);
        }
    }

    private void arrive(Player player, Guide guide) {
        Step step = guide.step();
        if (step.transfer()) {
            player.sendTitle(plugin.msg().get("guide.platform-title", "line", step.lineName()),
                    plugin.msg().get("guide.platform-subtitle", "towards", step.towards()), 5, 40, 10);
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_CHIME, 1f, 1.8f);
            next(player, guide);
            return;
        }
        stop(player.getUniqueId());
        player.sendTitle(plugin.msg().get("guide.arrived-title", "place", step.targetName()),
                plugin.msg().get("guide.arrived-subtitle", "place", step.targetName()), 10, 50, 15);
        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_CHIME, 1f, 2f);
    }

    /** On to the next step, or done. */
    private void next(Player player, Guide guide) {
        guide.index++;
        if (guide.index >= guide.steps.size()) {
            stop(player.getUniqueId());
            return;
        }
        waitFor(guide);
    }

    // ------------------------------------------------------------ getting off at the right station

    @EventHandler(priority = EventPriority.MONITOR)
    public void onTapOut(TripCompletedEvent event) {
        Guide guide = guides.get(event.getPlayer().getUniqueId());
        if (guide != null && guide.stage == Stage.WAITING && !guide.step().transfer() && event.getTo() != null
                && event.getTo().key().equals(guide.step().stationKey())) {
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
        Step step = guide.step();
        Station station = plugin.network().network().byKey(step.stationKey());
        // Where the train we came on stops: that line's platform, else the station's location.
        StationLocation stop = station == null ? null
                : step.arrivingLine() == null ? station.location() : station.locationOn(step.arrivingLine());
        if (stop == null) {
            return; // no location for the station: tapping out there still works for a place
        }
        Location at = event.getVehicle().getLocation();
        double away = at.getWorld() == null ? -1 : stop.horizontalDistance(at.getWorld().getName(), at.getX(), at.getZ());
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
                    plugin.msg().send(player, "guide.timeout");
                }
                continue;
            }
            if (guide.stage == Stage.GUIDING) {
                guideStep(player, guide);
            }
        }
    }

    private void guideStep(Player player, Guide guide) {
        Step step = guide.step();
        StationLocation target = step.target();
        Location l = player.getLocation();
        double distance = distance(l, target);
        if (distance < 0) {
            if (guide.bar != null) {
                guide.bar.setTitle(plugin.msg().get("guide.bar-other-world", "place", step.targetName()));
            }
            return;
        }
        if (distance <= arriveRadius) {
            arrive(player, guide);
            return;
        }
        if (guide.bar != null) {
            guide.bar.setTitle(plugin.msg().get(step.transfer() ? "guide.bar-to-platform" : "guide.bar",
                    "place", step.targetName(), "line", step.lineName(), "towards", step.towards(),
                    "distance", Math.round(distance), "arrow", arrow(l, target)));
            guide.bar.setProgress(Math.max(0, Math.min(1, 1 - distance / guide.startDistance)));
        }
        double dx = target.x() - l.getX();
        double dz = target.z() - l.getZ();
        double length = Math.hypot(dx, dz);
        if (trail && length > 0) {
            // Dots on the ground, from just ahead of the player towards the spot.
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
                player.spawnParticle(Particle.END_ROD, target.x(), target.y() + h, target.z(), 1, 0, 0, 0, 0);
            }
        }
    }

    // ------------------------------------------------------------ Xaero's Minimap

    /**
     * Xaero's Minimap turns chat lines in its share format into a waypoint the player can add with
     * one click: xaero-waypoint:name:initials:x:y:z:colour:false:0:Internal-dimension. Only sent to
     * players who turned it on (/route xaero), as other players would just see the raw line.
     */
    private void sendXaeroWaypoint(Player player, Step step) {
        StationLocation target = step.target();
        String name = step.targetName().replace(':', ' ').trim();
        String line = "xaero-waypoint:" + name + ":" + initials(name) + ":"
                + (int) Math.floor(target.x()) + ":" + (int) Math.floor(target.y()) + ":"
                + (int) Math.floor(target.z()) + ":" + xaeroColor + ":false:0:Internal-" + dimension(target.world());
        plugin.msg().send(player, "guide.xaero-intro", "place", step.targetName());
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
