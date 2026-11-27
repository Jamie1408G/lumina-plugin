package nl.jamie1408g.lumina.api;

import nl.jamie1408g.lumina.network.Station;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * Fired when a player taps out after a trip. Other plugins can listen to this
 * (for example to add their own rewards or statistics).
 */
public final class TripCompletedEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private final Station from;
    private final Station to;
    private final int distance;
    private final long fare;

    public TripCompletedEvent(Player player, Station from, Station to, int distance, long fare) {
        this.player = player;
        this.from = from;
        this.to = to;
        this.distance = distance;
        this.fare = fare;
    }

    public Player getPlayer() {
        return player;
    }

    public Station getFrom() {
        return from;
    }

    public Station getTo() {
        return to;
    }

    /** Ride distance in blocks. */
    public int getDistance() {
        return distance;
    }

    /** Fare paid, in LumiCoin cents. */
    public long getFare() {
        return fare;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
