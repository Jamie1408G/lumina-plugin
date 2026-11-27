package nl.jamie1408g.lumina.card;

import nl.jamie1408g.lumina.LuminaPlugin;
import nl.jamie1408g.lumina.network.Line;
import nl.jamie1408g.lumina.network.Station;
import nl.jamie1408g.lumina.network.StationLocation;
import nl.jamie1408g.lumina.storage.CardRecord;
import nl.jamie1408g.lumina.util.Money;
import org.bukkit.Location;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Minecart;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.vehicle.VehicleEnterEvent;
import org.bukkit.event.vehicle.VehicleExitEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * LRT lines (type: lrt in network.yml) have no faregates, but cost the same as the MRT: the fare
 * for the distance travelled, with the same base, steps, minimum and maximum (fares: in config.yml).
 *
 * Getting into a train at an LRT station pays the starting fare from the player's Lumina Card (held
 * or anywhere in the inventory). Getting off at another LRT station pays the rest, so the ride
 * costs exactly what the MRT would. Changing trains within the grace time continues the same ride.
 *
 * Free when: on an accepted job with a free-travel role (the fares are noted on the job and only
 * forgiven once the job is confirmed), the free ride home after a job, or already tapped in through
 * an MRT faregate (then the ride is part of that trip). Without a card, or with less than the
 * starting fare on it, you can't get in.
 */
public final class LrtFareListener implements Listener {

    private enum Pay { CARD, JOB, FREE }

    /** One LRT ride: where it started, what's been paid so far, and when they last got on or off. */
    private static final class Ride {
        final String startKey;
        final String startName;
        final Pay pay;
        final String cardId;
        long paid;
        long lastSeen;

        Ride(Station start, Pay pay, String cardId, long paid, long now) {
            this.startKey = start.key();
            this.startName = start.name();
            this.pay = pay;
            this.cardId = cardId;
            this.paid = paid;
            this.lastSeen = now;
        }
    }

    private final LuminaPlugin plugin;
    private final Map<UUID, Ride> rides = new HashMap<>();
    private boolean enabled = true;
    private double radius = 12;
    private double heightRange = 12;
    private long graceMillis = 120_000L;

    public LrtFareListener(LuminaPlugin plugin) {
        this.plugin = plugin;
    }

    public void load() {
        FileConfiguration c = plugin.getConfig();
        enabled = c.getBoolean("lrt.enabled", true);
        radius = Math.max(1, c.getDouble("lrt.boarding-radius", 12));
        heightRange = Math.max(1, c.getDouble("lrt.boarding-height", 12));
        graceMillis = Math.max(0, c.getLong("lrt.reboard-grace-seconds", 120)) * 1000L;
    }

    private FareCalculator fares() {
        return plugin.network().fares();
    }

    // ------------------------------------------------------------ getting in

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEnter(VehicleEnterEvent event) {
        if (!enabled || !(event.getEntered() instanceof Player player) || !(event.getVehicle() instanceof Minecart)) {
            return;
        }
        Station station = lrtStationNear(event.getVehicle().getLocation());
        if (station == null) {
            return; // not a train at an LRT station (MRT platforms are behind the faregates)
        }
        long now = System.currentTimeMillis();
        Ride ride = rides.get(player.getUniqueId());
        if (ride != null && now - ride.lastSeen < graceMillis) {
            ride.lastSeen = now; // changing seats or trains: the same ride goes on
            plugin.gates().visit(player, station);
            return;
        }

        CardRecord card = plugin.cards().cardOnPlayer(player);
        if (card == null) {
            event.setCancelled(true);
            plugin.msg().send(player, "lrt.no-card", "station", station.name());
            plugin.gates().playDeny(player);
            return;
        }
        if (card.blocked()) {
            event.setCancelled(true);
            plugin.msg().send(player, "card.blocked");
            plugin.gates().playDeny(player);
            return;
        }

        long start = fares().fare(0);
        if (card.tappedIn() != null) {
            // Came through an MRT faregate: the LRT ride is part of that trip, paid when tapping out.
            begin(player, new Ride(station, Pay.FREE, card.id(), 0, now), station);
            plugin.msg().actionBar(player, "lrt.in-trip-bar", "station", station.name());
            return;
        }
        if (plugin.jobs().freeTravel(player)) {
            plugin.jobs().noteFare(player, start);
            begin(player, new Ride(station, Pay.JOB, card.id(), start, now), station);
            plugin.msg().actionBar(player, "lrt.job-bar", "station", station.name());
            return;
        }
        if (plugin.jobs().useRideHome(player)) {
            begin(player, new Ride(station, Pay.FREE, card.id(), 0, now), station);
            plugin.msg().actionBar(player, "lrt.ride-home-bar", "station", station.name(), "value", Money.format(card.value()));
            return;
        }
        if (card.value() < start) {
            event.setCancelled(true);
            plugin.msg().send(player, "lrt.insufficient", "fare", Money.format(start), "value", Money.format(card.value()));
            plugin.gates().playDeny(player);
            return;
        }
        card.setValue(card.value() - start);
        card.addHistory(FaregateListener.time() + " | LRT from " + station.name() + " | -" + Money.plain(start));
        plugin.storage().markDirty();
        begin(player, new Ride(station, Pay.CARD, card.id(), start, now), station);
        plugin.gates().playSuccess(player);
        plugin.msg().actionBar(player, "lrt.paid-bar", "station", station.name(), "fare", Money.format(start),
                "value", Money.format(card.value()));
    }

    private void begin(Player player, Ride ride, Station station) {
        rides.put(player.getUniqueId(), ride);
        plugin.gates().visit(player, station);
    }

    // ------------------------------------------------------------ getting off

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onExit(VehicleExitEvent event) {
        if (!(event.getExited() instanceof Player player) || !(event.getVehicle() instanceof Minecart)) {
            return;
        }
        Ride ride = rides.get(player.getUniqueId());
        if (ride == null) {
            return;
        }
        ride.lastSeen = System.currentTimeMillis();
        Station station = lrtStationNear(event.getVehicle().getLocation());
        if (station == null || station.key().equals(ride.startKey)) {
            return; // got off between stations, or where they got on: only the starting fare
        }
        plugin.gates().visit(player, station);
        // Looked up by key, so a /lumina reload during the ride doesn't matter.
        Station start = plugin.network().network().byKey(ride.startKey);
        int distance = start == null ? -1 : plugin.network().planner().shortestDistance(start, station);
        long total = distance < 0 ? fares().max() : fares().fare(distance);
        long extra = total - ride.paid;
        if (extra <= 0) {
            return; // already paid for this far (e.g. rode back towards the start)
        }
        ride.paid = total;
        switch (ride.pay) {
            case FREE -> {
            }
            case JOB -> {
                plugin.jobs().noteFare(player, extra);
                plugin.msg().actionBar(player, "lrt.job-exit-bar", "station", station.name(), "fare", Money.format(total));
            }
            case CARD -> {
                CardRecord card = plugin.storage().card(ride.cardId);
                if (card == null) {
                    return;
                }
                // They're already off the train, so this can take the card below zero; the next
                // trip needs a top-up first (fares.min-value-to-enter).
                card.setValue(card.value() - extra);
                card.addHistory(FaregateListener.time() + " | LRT " + ride.startName + " > " + station.name()
                        + " | -" + Money.plain(extra));
                plugin.storage().markDirty();
                plugin.msg().actionBar(player, "lrt.exit-bar", "station", station.name(), "extra", Money.format(extra),
                        "fare", Money.format(total), "value", Money.format(card.value()));
                if (card.value() < 0) {
                    plugin.msg().send(player, "lrt.below-zero", "value", Money.format(card.value()));
                }
            }
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        rides.remove(event.getPlayer().getUniqueId());
    }

    // ------------------------------------------------------------ stations

    /** The closest station on an LRT line within the boarding radius, or null. */
    private Station lrtStationNear(Location l) {
        if (l.getWorld() == null) {
            return null;
        }
        String world = l.getWorld().getName();
        Station best = null;
        double bestD = Double.MAX_VALUE;
        for (Station s : plugin.network().network().stations()) {
            StationLocation at = s.location();
            if (at == null || !onLrt(s)) {
                continue;
            }
            double d = at.horizontalDistance(world, l.getX(), l.getZ());
            if (d < 0 || d > radius || Math.abs(at.y() - l.getY()) > heightRange) {
                continue;
            }
            if (d < bestD) {
                bestD = d;
                best = s;
            }
        }
        return best;
    }

    private boolean onLrt(Station s) {
        for (String code : s.lineCodes()) {
            Line line = plugin.network().network().line(code);
            if (line != null && line.isLrt()) {
                return true;
            }
        }
        return false;
    }
}
