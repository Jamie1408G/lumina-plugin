package nl.jamie1408g.lumina.network;

import java.util.List;

/**
 * A destination that isn't a station itself, like "Awards Center" or "Skyline Plaza": you get there
 * by alighting at one of its stations. Usually one station; with several, the journey planner picks
 * the one with the best journey. With a location (set in game with /lumina place here), players are
 * guided from the station to the place. Contains no Bukkit code.
 */
public record Place(String name, List<Station> stations, StationLocation location) {

    public Place {
        stations = List.copyOf(stations);
    }

    public Place(String name, List<Station> stations) {
        this(name, stations, null);
    }

    public String key() {
        return Station.keyOf(name);
    }

    public Place withLocation(StationLocation newLocation) {
        return new Place(name, stations, newLocation);
    }

    /** "Bukit Simei" or "Bukit Simei / Expo Town". */
    public String stationNames() {
        StringBuilder out = new StringBuilder();
        for (Station station : stations) {
            if (out.length() > 0) {
                out.append(" / ");
            }
            out.append(station.name());
        }
        return out.toString();
    }
}
