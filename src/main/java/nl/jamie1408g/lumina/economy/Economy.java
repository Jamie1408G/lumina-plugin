package nl.jamie1408g.lumina.economy;

import nl.jamie1408g.lumina.storage.PlayerData;
import nl.jamie1408g.lumina.storage.Storage;
import nl.jamie1408g.lumina.util.Money;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * LumiCoins. Kept separate from any server-wide economy on purpose, so Lumina money
 * stays inside Lumina and never clashes with the rest of the server.
 */
public final class Economy {

    private final Storage storage;

    public Economy(Storage storage) {
        this.storage = storage;
    }

    public long balance(UUID uuid) {
        PlayerData data = storage.get(uuid);
        return data == null ? 0 : data.balance();
    }

    public boolean has(UUID uuid, long amount) {
        return balance(uuid) >= amount;
    }

    public void deposit(UUID uuid, long amount, String reason) {
        PlayerData data = storage.get(uuid);
        if (data == null || amount <= 0) {
            return;
        }
        data.setBalance(data.balance() + amount);
        storage.log("+" + Money.plain(amount) + " " + data.name() + " (" + reason + ") -> " + Money.plain(data.balance()));
    }

    /** Takes money if the player has enough. Returns false (and changes nothing) otherwise. */
    public boolean withdraw(UUID uuid, long amount, String reason) {
        PlayerData data = storage.get(uuid);
        if (data == null || amount < 0 || data.balance() < amount) {
            return false;
        }
        if (amount == 0) {
            return true;
        }
        data.setBalance(data.balance() - amount);
        storage.log("-" + Money.plain(amount) + " " + data.name() + " (" + reason + ") -> " + Money.plain(data.balance()));
        return true;
    }

    public boolean transfer(UUID from, UUID to, long amount) {
        PlayerData receiver = storage.get(to);
        if (receiver == null || amount <= 0 || from.equals(to)) {
            return false;
        }
        PlayerData sender = storage.get(from);
        String senderName = sender == null ? "?" : sender.name();
        if (!withdraw(from, amount, "pay to " + receiver.name())) {
            return false;
        }
        deposit(to, amount, "pay from " + senderName);
        return true;
    }

    public void set(UUID uuid, long amount, String reason) {
        PlayerData data = storage.get(uuid);
        if (data == null) {
            return;
        }
        data.setBalance(Math.max(0, amount));
        storage.log("=" + Money.plain(data.balance()) + " " + data.name() + " (" + reason + ")");
    }

    public List<PlayerData> top(int size) {
        List<PlayerData> list = new ArrayList<>(storage.allPlayers());
        list.sort(Comparator.comparingLong(PlayerData::balance).reversed());
        return list.subList(0, Math.min(size, list.size()));
    }
}
