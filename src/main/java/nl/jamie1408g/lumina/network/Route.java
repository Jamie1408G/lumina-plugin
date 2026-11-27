package nl.jamie1408g.lumina.network;

import java.util.Collections;
import java.util.List;

/** A planned journey: one or more legs, with transfers in between. */
public final class Route {

    /** Part of a journey on one line. */
    public record Leg(Line line, Station from, Station to, String towards, int stops, int distance) {
    }

    private final Station from;
    private final Station to;
    private final List<Leg> legs;

    Route(Station from, Station to, List<Leg> legs) {
        this.from = from;
        this.to = to;
        this.legs = List.copyOf(legs);
    }

    public Station from() {
        return from;
    }

    public Station to() {
        return to;
    }

    public List<Leg> legs() {
        return Collections.unmodifiableList(legs);
    }

    public int transfers() {
        return Math.max(0, legs.size() - 1);
    }

    public int stops() {
        int total = 0;
        for (Leg leg : legs) {
            total += leg.stops();
        }
        return total;
    }

    /** Ride distance in blocks (transfers count as 0). */
    public int distance() {
        int total = 0;
        for (Leg leg : legs) {
            total += leg.distance();
        }
        return total;
    }

    /** Rough travel time in seconds. */
    public int estimateSeconds(double blocksPerSecond, int dwellSeconds, int transferSeconds) {
        double speed = blocksPerSecond <= 0 ? 10 : blocksPerSecond;
        return (int) Math.round(distance() / speed) + stops() * dwellSeconds + transfers() * transferSeconds;
    }
}
