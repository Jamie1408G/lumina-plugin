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

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * LRT lines (type: lrt in network.yml) have no faregates: getting into a train at an LRT station
 * charges a flat fare from the player's Lumina Card (held or anywhere in their inventory).
 *
 * Free when: on an accepted job with a free-travel role (the fare is noted on the job and only
 * forgiven once the job is confirmed), the free ride home after a job, already tapped in through
 * an MRT faregate (the LRT is part of that trip), or boarding again within the grace time.
 * Without a card, or with too little on it, you can't get in.
 */
public final class LrtFareListener implements Listener {

    private final LuminaPlugin plugin;
    private final Map<UUID, Long> lastBoarding = new HashMap<>();
    private boolean enabled = true;
    private long fare;
    private double radius = 12;
    private double heightRange = 12;
    private long graceMillis = 120_000L;

    public LrtFareListener(LuminaPlugin plugin) {
        this.plugin = plugin;
    }

    public void load() {
        FileConfiguration c = plugin.getConfig();
        enabled = c.getBoolean("lrt.enabled", true);
        fare = Money.fromDouble(c.getDouble("lrt.fare", c.getDouble("fares.base", 0.80)));
        radius = Math.max(1, c.getDouble("lrt.boarding-radius", 12));
        heightRange = Math.max(1, c.getDouble("lrt.boarding-height", 12));
        graceMillis = Math.max(0, c.getLong("lrt.reboard-grace-seconds", 120)) * 1000L;
    }

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
        Long last = lastBoarding.get(player.getUniqueId());
        if (last != null && now - last < graceMillis) {
            return; // changed seats, or got off and on again: already paid
        }

        CardRecord card = plugin.cards().cardInHand(player);
        if (card == null) {
            card = plugin.cards().cardOnPlayer(player);
        }
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

        if (card.tappedIn() != null) {
            // Came through an MRT faregate: the LRT ride is part of that trip, paid when tapping out.
            boarded(player, now, station);
            plugin.msg().actionBar(player, "lrt.in-trip-bar", "station", station.name());
            return;
        }
        if (plugin.jobs().freeTravel(player)) {
            plugin.jobs().noteFare(player, fare);
            boarded(player, now, station);
            plugin.msg().actionBar(player, "lrt.job-bar", "station", station.name(), "fare", Money.format(fare));
            return;
        }
        if (plugin.jobs().useRideHome(player)) {
            boarded(player, now, station);
            plugin.msg().actionBar(player, "lrt.ride-home-bar", "station", station.name(), "value", Money.format(card.value()));
            return;
        }
        if (card.value() < fare) {
            event.setCancelled(true);
            plugin.msg().send(player, "lrt.insufficient", "fare", Money.format(fare), "value", Money.format(card.value()));
            plugin.gates().playDeny(player);
            return;
        }
        card.setValue(card.value() - fare);
        card.addHistory(FaregateListener.time() + " | LRT " + station.name() + " | -" + Money.plain(fare));
        plugin.storage().markDirty();
        boarded(player, now, station);
        plugin.gates().playSuccess(player);
        plugin.msg().actionBar(player, "lrt.paid-bar", "station", station.name(), "fare", Money.format(fare),
                "value", Money.format(card.value()));
    }

    private void boarded(Player player, long now, Station station) {
        lastBoarding.put(player.getUniqueId(), now);
        plugin.gates().visit(player, station);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        lastBoarding.remove(event.getPlayer().getUniqueId());
    }

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
