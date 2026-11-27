package nl.jamie1408g.lumina.card;

/**
 * Distance-based fares, in cents. Contains no Bukkit code.
 * Fare = base + perStep for every full stepBlocks travelled, between min and max.
 */
public final class FareCalculator {

    private final long base;
    private final long perStep;
    private final int stepBlocks;
    private final long min;
    private final long max;

    public FareCalculator(long base, long perStep, int stepBlocks, long min, long max) {
        this.base = base;
        this.perStep = perStep;
        this.stepBlocks = Math.max(1, stepBlocks);
        this.min = min;
        this.max = Math.max(min, max);
    }

    /** Fare for a ride of the given length in blocks: 0-149 blocks = base, 150-299 = base + 1 step, ... */
    public long fare(int distanceBlocks) {
        long steps = Math.max(0, distanceBlocks) / stepBlocks;
        long fare = base + perStep * steps;
        return Math.max(min, Math.min(max, fare));
    }

    public long perStep() {
        return perStep;
    }

    public int stepBlocks() {
        return stepBlocks;
    }

    public long max() {
        return max;
    }
}
