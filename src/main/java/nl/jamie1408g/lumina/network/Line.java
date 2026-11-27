package nl.jamie1408g.lumina.network;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** A metro (MRT) or light rail (LRT) line: an ordered list of stations. */
public final class Line {

    private final String code;
    private final String name;
    private final String color;
    private final boolean loop;
    private final boolean lrt;
    private final List<Station> stations = new ArrayList<>();

    Line(String code, String name, String color, boolean loop, boolean lrt) {
        this.code = code;
        this.name = name;
        this.color = color == null ? "" : color;
        this.loop = loop;
        this.lrt = lrt;
    }

    public String code() {
        return code;
    }

    public String name() {
        return name;
    }

    /** Chat colour code like "&b". */
    public String color() {
        return color;
    }

    public boolean isLoop() {
        return loop;
    }

    /** LRT: no faregates; you pay when you get into the train (see LrtFareListener). */
    public boolean isLrt() {
        return lrt;
    }

    /** "MRT" or "LRT". */
    public String type() {
        return lrt ? "LRT" : "MRT";
    }

    void add(Station station) {
        stations.add(station);
    }

    public List<Station> stations() {
        return Collections.unmodifiableList(stations);
    }

    public int indexOf(Station station) {
        return stations.indexOf(station);
    }

    public Station first() {
        return stations.get(0);
    }

    public Station last() {
        return stations.get(stations.size() - 1);
    }
}
