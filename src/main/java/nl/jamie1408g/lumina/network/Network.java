package nl.jamie1408g.lumina.network;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/** All lines and stations of the Lumina network. Contains no Bukkit code. */
public final class Network {

    /** Planner settings from config.yml. */
    public record Settings(int defaultHopDistance, double distanceFactor, int transferPenalty) {
        public static Settings defaults() {
            return new Settings(400, 1.15, 600);
        }
    }

    /** Result of a nearest-station lookup. */
    public record Nearest(Station station, double distance) {
    }

    private static final Pattern CODE = Pattern.compile("[A-Za-z]{1,4}\\d{1,3}[A-Za-z]?");

    private final Map<String, Line> lines = new LinkedHashMap<>();
    private final Map<String, Station> stationsByKey = new LinkedHashMap<>();
    private final Map<String, Station> stationsByCode = new HashMap<>();
    private final List<String> warnings = new ArrayList<>();
    private Settings settings = Settings.defaults();

    public void setSettings(Settings settings) {
        this.settings = settings;
    }

    public Settings settings() {
        return settings;
    }

    /**
     * Adds a line. Each entry is "CODE Name" (like "LM1 Tanjong Gateway") or just a name.
     */
    public void addLine(String code, String name, String color, boolean loop, List<String> entries) {
        addLine(code, name, color, loop, false, entries);
    }

    /** Same, with lrt = true for a light rail line (pay on boarding instead of at faregates). */
    public void addLine(String code, String name, String color, boolean loop, boolean lrt, List<String> entries) {
        String lineCode = code.toUpperCase(Locale.ROOT);
        if (lines.containsKey(lineCode)) {
            warnings.add("Line " + lineCode + " is defined twice; the second one is ignored.");
            return;
        }
        Line line = new Line(lineCode, name == null || name.isBlank() ? lineCode : name, color, loop, lrt);
        for (String raw : entries) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            String entry = raw.trim();
            String stationCode = null;
            String stationName = entry;
            int space = entry.indexOf(' ');
            if (space > 0 && CODE.matcher(entry.substring(0, space)).matches()) {
                stationCode = entry.substring(0, space).toUpperCase(Locale.ROOT);
                stationName = entry.substring(space + 1).trim();
            }
            String finalName = stationName;
            Station station = stationsByKey.computeIfAbsent(Station.keyOf(finalName), k -> new Station(finalName));
            if (line.indexOf(station) >= 0) {
                warnings.add(stationName + " appears twice on " + lineCode + "; the second one is ignored.");
                continue;
            }
            if (stationCode != null) {
                Station existing = stationsByCode.get(stationCode);
                if (existing != null && existing != station) {
                    warnings.add("Code " + stationCode + " is used by both " + existing.name() + " and "
                            + stationName + "; it stays with " + existing.name() + ".");
                    stationCode = null;
                } else {
                    stationsByCode.put(stationCode, station);
                }
            }
            station.addCode(lineCode, stationCode);
            line.add(station);
        }
        if (line.stations().size() < 2) {
            warnings.add("Line " + lineCode + " needs at least 2 stations and is ignored.");
            for (Station station : line.stations()) {
                String stationCode = station.codeOn(lineCode);
                if (stationCode != null) {
                    stationsByCode.remove(stationCode);
                }
                station.removeLine(lineCode);
            }
            return;
        }
        lines.put(lineCode, line);
    }

    public void setStationInfo(String stationName, String info) {
        Station station = stationsByKey.get(Station.keyOf(stationName));
        if (station == null) {
            warnings.add("station-info: unknown station '" + stationName + "'.");
            return;
        }
        station.setInfo(info);
    }

    public List<String> warnings() {
        return Collections.unmodifiableList(warnings);
    }

    public Collection<Line> lines() {
        return Collections.unmodifiableCollection(lines.values());
    }

    public Line line(String code) {
        return code == null ? null : lines.get(code.toUpperCase(Locale.ROOT));
    }

    /** Only stations that are on at least one valid line. */
    public List<Station> stations() {
        List<Station> result = new ArrayList<>();
        for (Station station : stationsByKey.values()) {
            if (!station.lineCodes().isEmpty()) {
                result.add(station);
            }
        }
        return result;
    }

    public Station byKey(String key) {
        Station station = stationsByKey.get(Station.keyOf(key));
        return station == null || station.lineCodes().isEmpty() ? null : station;
    }

    /**
     * Finds a station by code ("LM2"), by name ("Civic Skyline", "civic-skyline")
     * or by a unique start of the name ("civic s").
     */
    public Station findStation(String query) {
        if (query == null || query.isBlank()) {
            return null;
        }
        Station byCode = stationsByCode.get(query.trim().toUpperCase(Locale.ROOT));
        if (byCode != null && !byCode.lineCodes().isEmpty()) {
            return byCode;
        }
        Station exact = byKey(query);
        if (exact != null) {
            return exact;
        }
        String key = Station.keyOf(query);
        Station match = null;
        for (Station station : stations()) {
            if (station.key().startsWith(key)) {
                if (match != null) {
                    return null;
                }
                match = station;
            }
        }
        return match;
    }

    /** Tab completion: codes and names (spaces replaced by dashes). */
    public List<String> completions(String prefix) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        List<String> result = new ArrayList<>();
        for (Station station : stations()) {
            for (String code : station.codes()) {
                if (code.toLowerCase(Locale.ROOT).startsWith(lower)) {
                    result.add(code);
                }
            }
            String dashed = station.name().replace(' ', '-');
            if (dashed.toLowerCase(Locale.ROOT).startsWith(lower)) {
                result.add(dashed);
            }
        }
        return result;
    }

    /** Track distance between two neighbouring stations, in blocks. */
    public int hopDistance(Station a, Station b) {
        if (a.location() != null && b.location() != null) {
            double straight = a.location().horizontalDistance(b.location());
            if (straight >= 0) {
                return Math.max(1, (int) Math.round(straight * settings.distanceFactor()));
            }
        }
        return settings.defaultHopDistance();
    }

    /** Nearest station with a location in the given world, or null. */
    public Nearest nearest(String world, double x, double z) {
        Nearest best = null;
        for (Station station : stations()) {
            if (station.location() == null) {
                continue;
            }
            double distance = station.location().horizontalDistance(world, x, z);
            if (distance >= 0 && (best == null || distance < best.distance())) {
                best = new Nearest(station, distance);
            }
        }
        return best;
    }
}
