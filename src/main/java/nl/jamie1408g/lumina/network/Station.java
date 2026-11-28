package nl.jamie1408g.lumina.network;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * One physical station. A station served by several lines is an interchange
 * and has one code per line (for example LM2 and CT2 for Civic Skyline).
 */
public final class Station {

    private final String key;
    private final String name;
    private final Map<String, String> codesByLine = new LinkedHashMap<>();
    private String info = "";
    private StationLocation location;
    private final Map<String, StationLocation> platforms = new LinkedHashMap<>();

    Station(String name) {
        this.name = name;
        this.key = keyOf(name);
    }

    /** Normalised key: "Civic Skyline", "civic-skyline" and "civic_skyline" all become "civic skyline". */
    public static String keyOf(String name) {
        return name.trim().toLowerCase(Locale.ROOT).replace('-', ' ').replace('_', ' ').replaceAll("\\s+", " ");
    }

    public String key() {
        return key;
    }

    public String name() {
        return name;
    }

    void addCode(String lineCode, String stationCode) {
        codesByLine.put(lineCode, stationCode);
    }

    void removeLine(String lineCode) {
        codesByLine.remove(lineCode);
    }

    /** Code on a given line, or null when this station has no code there. */
    public String codeOn(String lineCode) {
        return codesByLine.get(lineCode);
    }

    public List<String> lineCodes() {
        return new ArrayList<>(codesByLine.keySet());
    }

    /** All codes of this station, like ["LM2", "CT2"]. */
    public List<String> codes() {
        List<String> codes = new ArrayList<>();
        for (String code : codesByLine.values()) {
            if (code != null && !code.isEmpty()) {
                codes.add(code);
            }
        }
        return Collections.unmodifiableList(codes);
    }

    /** "LM2/CT2" or the name when no codes are set. */
    public String codeLabel() {
        List<String> codes = codes();
        return codes.isEmpty() ? name : String.join("/", codes);
    }

    public boolean isInterchange() {
        return codesByLine.size() > 1;
    }

    public String info() {
        return info;
    }

    void setInfo(String info) {
        this.info = info == null ? "" : info;
    }

    public StationLocation location() {
        return location;
    }

    public void setLocation(StationLocation location) {
        this.location = location;
    }

    /**
     * The platform of one line at this station (set with /lumina setstation <code>, like LM2 or
     * CT2), or null. Interchanges can have a separate platform per line.
     */
    public StationLocation platform(String lineCode) {
        return lineCode == null ? null : platforms.get(lineCode);
    }

    public void setPlatform(String lineCode, StationLocation platform) {
        if (platform == null) {
            platforms.remove(lineCode);
        } else {
            platforms.put(lineCode, platform);
        }
    }

    /** Where this line stops here: its own platform, or else the station's location (or null). */
    public StationLocation locationOn(String lineCode) {
        StationLocation p = platform(lineCode);
        return p != null ? p : location;
    }
}
