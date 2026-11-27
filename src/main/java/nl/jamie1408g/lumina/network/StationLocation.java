package nl.jamie1408g.lumina.network;

/** A station position, kept free of Bukkit types so the planner can be tested on its own. */
public record StationLocation(String world, double x, double y, double z) {

    /** Horizontal distance in blocks, or -1 if the worlds differ. */
    public double horizontalDistance(StationLocation other) {
        if (other == null || !world.equals(other.world)) {
            return -1;
        }
        double dx = x - other.x;
        double dz = z - other.z;
        return Math.sqrt(dx * dx + dz * dz);
    }

    public double horizontalDistance(String otherWorld, double otherX, double otherZ) {
        if (!world.equals(otherWorld)) {
            return -1;
        }
        double dx = x - otherX;
        double dz = z - otherZ;
        return Math.sqrt(dx * dx + dz * dz);
    }
}
