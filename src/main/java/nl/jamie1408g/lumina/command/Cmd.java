package nl.jamie1408g.lumina.command;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

/** Small helpers shared by the commands. */
final class Cmd {

    private Cmd() {
    }

    static List<String> filter(Collection<String> options, String prefix) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        List<String> result = new ArrayList<>();
        for (String option : options) {
            if (option.toLowerCase(Locale.ROOT).startsWith(lower)) {
                result.add(option);
            }
        }
        return result;
    }

    static List<String> filter(String prefix, String... options) {
        return filter(Arrays.asList(options), prefix);
    }

    static String join(String[] args, int from) {
        return join(args, from, args.length);
    }

    static String join(String[] args, int from, int to) {
        return String.join(" ", Arrays.copyOfRange(args, from, Math.max(from, to)));
    }

    /** "~3 min", "<1 min" or "~1 h 5 min". */
    static String duration(int seconds) {
        if (seconds < 60) {
            return "<1 min";
        }
        int minutes = Math.round(seconds / 60f);
        if (minutes < 60) {
            return "~" + minutes + " min";
        }
        return "~" + (minutes / 60) + " h " + (minutes % 60) + " min";
    }

    /** 1234 blocks -> "1.2 km", 300 -> "300 m". */
    static String distance(long blocks) {
        if (blocks < 1000) {
            return blocks + " m";
        }
        return String.format(Locale.ROOT, "%.1f km", blocks / 1000.0);
    }

    /** Compass direction from (x1, z1) towards (x2, z2). */
    static String compass(double x1, double z1, double x2, double z2) {
        double angle = Math.toDegrees(Math.atan2(x2 - x1, -(z2 - z1)));
        if (angle < 0) {
            angle += 360;
        }
        String[] names = {"N", "NE", "E", "SE", "S", "SW", "W", "NW"};
        return names[(int) Math.round(angle / 45) % 8];
    }
}
