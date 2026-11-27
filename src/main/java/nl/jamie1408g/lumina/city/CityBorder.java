package nl.jamie1408g.lumina.city;

import java.util.ArrayList;
import java.util.List;

/**
 * Lumina's border: points on the map (block x and z) joined in order, the last one back to the
 * first. Set in game with /lumina pos1 to pos6, so normally six points, but areas.yml may hold any
 * number from three up. Height doesn't matter. A block on the border line itself counts as inside.
 * <p>
 * Plain Java on purpose (no Bukkit), so the shape maths can be tested on its own.
 */
public final class CityBorder {

    public record Point(int x, int z) {

        /** "120 -340" or "120, -340", or null. */
        public static Point parse(String text) {
            if (text == null) {
                return null;
            }
            String[] parts = text.trim().split("[,\\s]+");
            if (parts.length != 2) {
                return null;
            }
            try {
                return new Point(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]));
            } catch (NumberFormatException e) {
                return null;
            }
        }

        @Override
        public String toString() {
            return x + " " + z;
        }
    }

    /** What's wrong with a shape. Points and lines are numbered from 0; line i runs from point i to point i + 1. */
    public record Problem(Kind kind, int first, int second) {
        public enum Kind {
            /** Points first and second are on the same block. */
            SAME_SPOT,
            /** Line first crosses or touches line second. */
            CROSSING,
            /** All points are on one straight line, so nothing is inside. */
            FLAT
        }
    }

    private final String world;
    private final List<Point> points;
    private final int minX;
    private final int minZ;
    private final int maxX;
    private final int maxZ;

    public CityBorder(String world, List<Point> points) {
        if (points.size() < 3) {
            throw new IllegalArgumentException("A border needs at least 3 points");
        }
        this.world = world;
        this.points = List.copyOf(points);
        int x1 = Integer.MAX_VALUE;
        int z1 = Integer.MAX_VALUE;
        int x2 = Integer.MIN_VALUE;
        int z2 = Integer.MIN_VALUE;
        for (Point p : this.points) {
            x1 = Math.min(x1, p.x());
            z1 = Math.min(z1, p.z());
            x2 = Math.max(x2, p.x());
            z2 = Math.max(z2, p.z());
        }
        minX = x1;
        minZ = z1;
        maxX = x2;
        maxZ = z2;
    }

    /** The old two-corner border: a rectangle. */
    public static CityBorder rectangle(String world, int x1, int z1, int x2, int z2) {
        int ax = Math.min(x1, x2);
        int az = Math.min(z1, z2);
        int bx = Math.max(x1, x2);
        int bz = Math.max(z1, z2);
        return new CityBorder(world, List.of(new Point(ax, az), new Point(bx, az), new Point(bx, bz), new Point(ax, bz)));
    }

    public String world() {
        return world;
    }

    public List<Point> points() {
        return points;
    }

    public int minX() {
        return minX;
    }

    public int minZ() {
        return minZ;
    }

    public int maxX() {
        return maxX;
    }

    public int maxZ() {
        return maxZ;
    }

    /** Whether the block at x, z in this world is in Lumina (on the line counts as in). */
    public boolean contains(String w, int x, int z) {
        if (!world.equals(w) || x < minX || x > maxX || z < minZ || z > maxZ) {
            return false;
        }
        boolean inside = false;
        int n = points.size();
        for (int i = 0, j = n - 1; i < n; j = i++) {
            Point a = points.get(j);
            Point b = points.get(i);
            if (onLine(a, b, x, z)) {
                return true;
            }
            if ((b.z() > z) != (a.z() > z)) {
                double crossX = b.x() + (double) (z - b.z()) * (a.x() - b.x()) / (a.z() - b.z());
                if (x < crossX) {
                    inside = !inside;
                }
            }
        }
        return inside;
    }

    /** Shortest distance in blocks from x, z to the border line (0 when on it). */
    public double distanceTo(double x, double z) {
        double best = Double.MAX_VALUE;
        int n = points.size();
        for (int i = 0; i < n; i++) {
            Point a = points.get(i);
            Point b = points.get((i + 1) % n);
            double dx = b.x() - a.x();
            double dz = b.z() - a.z();
            double lengthSq = dx * dx + dz * dz;
            double t = lengthSq == 0 ? 0 : ((x - a.x()) * dx + (z - a.z()) * dz) / lengthSq;
            t = Math.max(0, Math.min(1, t));
            best = Math.min(best, Math.hypot(x - (a.x() + t * dx), z - (a.z() + t * dz)));
        }
        return best;
    }

    /** Size in square blocks (rounded). */
    public long area() {
        return Math.round(Math.abs(twiceArea()) / 2.0);
    }

    /** Null if this is a proper shape, otherwise the first thing that's wrong with it. */
    public Problem problem() {
        int n = points.size();
        for (int i = 0; i < n; i++) {
            int next = (i + 1) % n;
            if (points.get(i).equals(points.get(next))) {
                return new Problem(Problem.Kind.SAME_SPOT, Math.min(i, next), Math.max(i, next));
            }
        }
        boolean allOnOneLine = true;
        for (int k = 2; k < n && allOnOneLine; k++) {
            allOnOneLine = orientation(points.get(0), points.get(1), points.get(k)) == 0;
        }
        if (allOnOneLine) {
            return new Problem(Problem.Kind.FLAT, 0, 0);
        }
        for (int i = 0; i < n; i++) {
            Point a = points.get(i);
            Point b = points.get((i + 1) % n);
            Point c = points.get((i + 2) % n);
            // The next line folding straight back over this one (with 3 points that's just FLAT).
            if (n > 3) {
                long dot = ((long) b.x() - a.x()) * ((long) c.x() - b.x()) + ((long) b.z() - a.z()) * ((long) c.z() - b.z());
                if (orientation(a, b, c) == 0 && dot < 0) {
                    return new Problem(Problem.Kind.CROSSING, i, (i + 1) % n);
                }
            }
            for (int k = i + 2; k < n; k++) {
                if (i == 0 && k == n - 1) {
                    continue; // the last line meets the first one at point 0, as it should
                }
                if (intersects(a, b, points.get(k), points.get((k + 1) % n))) {
                    return new Problem(Problem.Kind.CROSSING, i, k);
                }
            }
        }
        if (twiceArea() == 0) {
            return new Problem(Problem.Kind.FLAT, 0, 0);
        }
        return null;
    }

    /** Points as "x z" text, for areas.yml. */
    public List<String> pointTexts() {
        List<String> out = new ArrayList<>(points.size());
        for (Point p : points) {
            out.add(p.toString());
        }
        return out;
    }

    // ------------------------------------------------------------ maths

    private long twiceArea() {
        long sum = 0;
        int n = points.size();
        for (int i = 0; i < n; i++) {
            Point a = points.get(i);
            Point b = points.get((i + 1) % n);
            sum += (long) a.x() * b.z() - (long) b.x() * a.z();
        }
        return sum;
    }

    /** 1 if a, b, c turn one way, -1 the other way, 0 if they're on one straight line. */
    private static int orientation(Point a, Point b, Point c) {
        long v = ((long) b.x() - a.x()) * ((long) c.z() - a.z()) - ((long) b.z() - a.z()) * ((long) c.x() - a.x());
        return Long.signum(v);
    }

    private static boolean onLine(Point a, Point b, long x, long z) {
        long cross = ((long) b.x() - a.x()) * (z - a.z()) - ((long) b.z() - a.z()) * (x - a.x());
        return cross == 0 && x >= Math.min(a.x(), b.x()) && x <= Math.max(a.x(), b.x())
                && z >= Math.min(a.z(), b.z()) && z <= Math.max(a.z(), b.z());
    }

    /** Whether line p1-p2 and line p3-p4 cross or touch. */
    private static boolean intersects(Point p1, Point p2, Point p3, Point p4) {
        int d1 = orientation(p3, p4, p1);
        int d2 = orientation(p3, p4, p2);
        int d3 = orientation(p1, p2, p3);
        int d4 = orientation(p1, p2, p4);
        if (d1 * d2 < 0 && d3 * d4 < 0) {
            return true;
        }
        return d1 == 0 && onLine(p3, p4, p1.x(), p1.z())
                || d2 == 0 && onLine(p3, p4, p2.x(), p2.z())
                || d3 == 0 && onLine(p1, p2, p3.x(), p3.z())
                || d4 == 0 && onLine(p1, p2, p4.x(), p4.z());
    }
}
