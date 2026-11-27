package nl.jamie1408g.lumina.storage;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A Lumina Card. The item only carries the card number; the value lives here on the
 * server, so copying the item can never copy money.
 */
public final class CardRecord {

    public static final int HISTORY_SIZE = 10;

    private final String id;
    private final UUID owner;
    private long value;
    private String tappedIn;
    private long tapInTime;
    private boolean blocked;
    private long autoTopUp;
    private final List<String> history = new ArrayList<>();

    public CardRecord(String id, UUID owner) {
        this.id = id;
        this.owner = owner;
    }

    public String id() {
        return id;
    }

    public UUID owner() {
        return owner;
    }

    public long value() {
        return value;
    }

    public void setValue(long value) {
        this.value = value;
    }

    /** Key of the station the card tapped in at, or null. */
    public String tappedIn() {
        return tappedIn;
    }

    public void setTappedIn(String stationKey, long time) {
        this.tappedIn = stationKey;
        this.tapInTime = time;
    }

    public long tapInTime() {
        return tapInTime;
    }

    public boolean blocked() {
        return blocked;
    }

    public void setBlocked(boolean blocked) {
        this.blocked = blocked;
    }

    /** Newest first. */
    /** Auto top-up amount in LumiCoin cents (0 = off): added from the owner's wallet when the card runs low. */
    public long autoTopUp() {
        return autoTopUp;
    }

    public void setAutoTopUp(long autoTopUp) {
        this.autoTopUp = Math.max(0, autoTopUp);
    }

    public List<String> history() {
        return history;
    }

    public void addHistory(String entry) {
        history.add(0, entry);
        while (history.size() > HISTORY_SIZE) {
            history.remove(history.size() - 1);
        }
    }
}
