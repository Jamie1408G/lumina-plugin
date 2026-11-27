package nl.jamie1408g.lumina.storage;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Everything Lumina remembers about one player. */
public final class PlayerData {

    /** Progress on one quest. */
    public static final class QuestProgress {
        public long progress;
        public boolean completed;
        /** Day or week number the progress belongs to (for repeating quests). */
        public long period;
        /** Stations already counted, for VISIT_STATIONS quests. */
        public final Set<String> seen = new LinkedHashSet<>();
    }

    private final UUID uuid;
    private String name;
    private long balance;

    private String job;
    private boolean onDuty;
    private long lastJobChange;
    private int periodMinutes;
    private long workMinutes;

    private int trips;
    private long distance;
    private boolean walkthroughSeen;
    private boolean xaeroWaypoints;
    private final Set<String> visitedStations = new LinkedHashSet<>();
    private final Map<String, QuestProgress> quests = new HashMap<>();

    public PlayerData(UUID uuid, String name) {
        this.uuid = uuid;
        this.name = name;
    }

    public UUID uuid() {
        return uuid;
    }

    public String name() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public long balance() {
        return balance;
    }

    public void setBalance(long balance) {
        this.balance = balance;
    }

    public String job() {
        return job;
    }

    public void setJob(String job) {
        this.job = job == null || job.isEmpty() ? null : job;
    }

    public boolean onDuty() {
        return onDuty;
    }

    public void setOnDuty(boolean onDuty) {
        this.onDuty = onDuty;
    }

    public long lastJobChange() {
        return lastJobChange;
    }

    public void setLastJobChange(long lastJobChange) {
        this.lastJobChange = lastJobChange;
    }

    public int periodMinutes() {
        return periodMinutes;
    }

    public void setPeriodMinutes(int periodMinutes) {
        this.periodMinutes = periodMinutes;
    }

    public long workMinutes() {
        return workMinutes;
    }

    public void setWorkMinutes(long workMinutes) {
        this.workMinutes = workMinutes;
    }

    public int trips() {
        return trips;
    }

    public void setTrips(int trips) {
        this.trips = trips;
    }

    public long distance() {
        return distance;
    }

    public void setDistance(long distance) {
        this.distance = distance;
    }

    /** Seen (or skipped) the Lumina walkthrough. */
    public boolean walkthroughSeen() {
        return walkthroughSeen;
    }

    public void setWalkthroughSeen(boolean walkthroughSeen) {
        this.walkthroughSeen = walkthroughSeen;
    }

    /** Wants journey destinations as Xaero's Minimap waypoints in chat (/route xaero). */
    public boolean xaeroWaypoints() {
        return xaeroWaypoints;
    }

    public void setXaeroWaypoints(boolean xaeroWaypoints) {
        this.xaeroWaypoints = xaeroWaypoints;
    }

    public Set<String> visitedStations() {
        return visitedStations;
    }

    public Map<String, QuestProgress> quests() {
        return quests;
    }
}
