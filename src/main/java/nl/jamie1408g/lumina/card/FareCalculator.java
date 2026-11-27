package nl.jamie1408g.lumina.card;

/** Distance-based fares, in cents. Contains no Bukkit code. */
public final class FareCalculator {

    private final long base;
    private final long perKm;
    private final long min;
    private final long max;

    public FareCalculator(long base, long perKm, long min, long max) {
        this.base = base;
        this.perKm = perKm;
        this.min = min;
        this.max = Math.max(min, max);
    }

    /** Fare for a ride of the given length in blocks (1 km = 1000 blocks). */
    public long fare(int distanceBlocks) {
        long distanceFare = Math.round(perKm * (Math.max(0, distanceBlocks) / 1000.0));
        long fare = base + distanceFare;
        return Math.max(min, Math.min(max, fare));
    }

    public long max() {
        return max;
    }
}
