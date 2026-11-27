package nl.jamie1408g.lumina.storage;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/**
 * Player data and Lumina Cards, saved as YAML in the plugin folder.
 * Snapshots are taken on the main thread and written by one background thread.
 */
public final class Storage {

    private static final DateTimeFormatter LOG_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final JavaPlugin plugin;
    private final File playersFile;
    private final File cardsFile;
    private final File logFile;
    private final Map<UUID, PlayerData> players = new HashMap<>();
    private final Map<String, UUID> nameIndex = new HashMap<>();
    private final Map<String, CardRecord> cards = new HashMap<>();
    private final List<String> logBuffer = new ArrayList<>();
    private ExecutorService io = Executors.newSingleThreadExecutor(r -> new Thread(r, "Lumina-IO"));
    private boolean dirty;

    public Storage(JavaPlugin plugin) {
        this.plugin = plugin;
        this.playersFile = new File(plugin.getDataFolder(), "players.yml");
        this.cardsFile = new File(plugin.getDataFolder(), "cards.yml");
        this.logFile = new File(plugin.getDataFolder(), "transactions.log");
    }

    // ------------------------------------------------------------ players

    public PlayerData get(UUID uuid) {
        return players.get(uuid);
    }

    /** Returns the player's data, creating it with the starting balance if new. */
    public PlayerData getOrCreate(UUID uuid, String name, long startingBalance) {
        PlayerData data = players.get(uuid);
        if (data == null) {
            data = new PlayerData(uuid, name);
            data.setBalance(startingBalance);
            players.put(uuid, data);
            log("NEW " + name + " starting balance " + startingBalance);
            dirty = true;
        } else if (name != null && !name.equals(data.name())) {
            nameIndex.remove(data.name().toLowerCase(Locale.ROOT));
            data.setName(name);
            dirty = true;
        }
        nameIndex.put(name.toLowerCase(Locale.ROOT), uuid);
        return data;
    }

    /** Finds a known player (online or offline) by name. */
    public PlayerData findByName(String name) {
        UUID uuid = nameIndex.get(name.toLowerCase(Locale.ROOT));
        return uuid == null ? null : players.get(uuid);
    }

    public Collection<PlayerData> allPlayers() {
        return Collections.unmodifiableCollection(players.values());
    }

    public List<String> knownNames() {
        List<String> names = new ArrayList<>();
        for (PlayerData data : players.values()) {
            names.add(data.name());
        }
        return names;
    }

    // ------------------------------------------------------------ cards

    public CardRecord card(String id) {
        return id == null ? null : cards.get(id.toUpperCase(Locale.ROOT));
    }

    public void addCard(CardRecord card) {
        cards.put(card.id().toUpperCase(Locale.ROOT), card);
        dirty = true;
    }

    public List<CardRecord> cardsOf(UUID owner) {
        List<CardRecord> result = new ArrayList<>();
        for (CardRecord card : cards.values()) {
            if (card.owner().equals(owner)) {
                result.add(card);
            }
        }
        return result;
    }

    // ------------------------------------------------------------ saving

    public void markDirty() {
        dirty = true;
    }

    /** Adds a line to transactions.log (written with the next save). */
    public void log(String line) {
        logBuffer.add(LocalDateTime.now().format(LOG_TIME) + " " + line);
        dirty = true;
    }

    public void load() {
        players.clear();
        nameIndex.clear();
        cards.clear();

        YamlConfiguration playerYaml = YamlConfiguration.loadConfiguration(playersFile);
        ConfigurationSection root = playerYaml.getConfigurationSection("players");
        if (root != null) {
            for (String key : root.getKeys(false)) {
                ConfigurationSection s = root.getConfigurationSection(key);
                if (s == null) {
                    continue;
                }
                UUID uuid;
                try {
                    uuid = UUID.fromString(key);
                } catch (IllegalArgumentException e) {
                    plugin.getLogger().warning("Skipping player with invalid UUID " + key);
                    continue;
                }
                PlayerData data = new PlayerData(uuid, s.getString("name", "unknown"));
                data.setBalance(s.getLong("balance"));
                data.setJob(s.getString("job"));
                data.setLastJobChange(s.getLong("last-job-change"));
                data.setPeriodMinutes(s.getInt("period-minutes"));
                data.setWorkMinutes(s.getLong("work-minutes"));
                data.setTrips(s.getInt("trips"));
                data.setDistance(s.getLong("distance"));
                data.setWalkthroughSeen(s.getBoolean("walkthrough-seen"));
                data.visitedStations().addAll(s.getStringList("visited"));
                ConfigurationSection quests = s.getConfigurationSection("quests");
                if (quests != null) {
                    for (String questId : quests.getKeys(false)) {
                        ConfigurationSection q = quests.getConfigurationSection(questId);
                        if (q == null) {
                            continue;
                        }
                        PlayerData.QuestProgress progress = new PlayerData.QuestProgress();
                        progress.progress = q.getLong("progress");
                        progress.completed = q.getBoolean("completed");
                        progress.period = q.getLong("period");
                        progress.seen.addAll(q.getStringList("seen"));
                        data.quests().put(questId, progress);
                    }
                }
                players.put(uuid, data);
                nameIndex.put(data.name().toLowerCase(Locale.ROOT), uuid);
            }
        }

        YamlConfiguration cardYaml = YamlConfiguration.loadConfiguration(cardsFile);
        ConfigurationSection cardRoot = cardYaml.getConfigurationSection("cards");
        if (cardRoot != null) {
            for (String id : cardRoot.getKeys(false)) {
                ConfigurationSection s = cardRoot.getConfigurationSection(id);
                if (s == null) {
                    continue;
                }
                try {
                    CardRecord card = new CardRecord(id, UUID.fromString(s.getString("owner", "")));
                    card.setValue(s.getLong("value"));
                    card.setTappedIn(s.getString("tapped-in"), s.getLong("tap-in-time"));
                    card.setBlocked(s.getBoolean("blocked"));
                    card.history().addAll(s.getStringList("history"));
                    cards.put(id.toUpperCase(Locale.ROOT), card);
                } catch (IllegalArgumentException e) {
                    plugin.getLogger().warning("Skipping card " + id + " with invalid owner.");
                }
            }
        }
        dirty = false;
    }

    /** Saves in the background if anything changed. Call from the main thread. */
    public void saveAsync() {
        if (!dirty) {
            return;
        }
        Snapshot snapshot = snapshot();
        io.execute(() -> write(snapshot));
    }

    /** Saves right away (used when the plugin stops). */
    public void saveNow() {
        Snapshot snapshot = dirty ? snapshot() : null;
        io.shutdown();
        try {
            if (!io.awaitTermination(10, TimeUnit.SECONDS)) {
                plugin.getLogger().warning("Background save took too long.");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (snapshot != null) {
            write(snapshot);
        }
        io = Executors.newSingleThreadExecutor(r -> new Thread(r, "Lumina-IO"));
    }

    private record Snapshot(String players, String cards, List<String> log) {
    }

    private Snapshot snapshot() {
        YamlConfiguration playerYaml = new YamlConfiguration();
        for (PlayerData data : players.values()) {
            String base = "players." + data.uuid() + ".";
            playerYaml.set(base + "name", data.name());
            playerYaml.set(base + "balance", data.balance());
            playerYaml.set(base + "job", data.job());
            playerYaml.set(base + "last-job-change", data.lastJobChange());
            playerYaml.set(base + "period-minutes", data.periodMinutes());
            playerYaml.set(base + "work-minutes", data.workMinutes());
            playerYaml.set(base + "trips", data.trips());
            playerYaml.set(base + "distance", data.distance());
            playerYaml.set(base + "walkthrough-seen", data.walkthroughSeen() ? true : null);
            playerYaml.set(base + "visited", new ArrayList<>(data.visitedStations()));
            for (Map.Entry<String, PlayerData.QuestProgress> entry : data.quests().entrySet()) {
                String q = base + "quests." + entry.getKey() + ".";
                PlayerData.QuestProgress progress = entry.getValue();
                playerYaml.set(q + "progress", progress.progress);
                playerYaml.set(q + "completed", progress.completed);
                playerYaml.set(q + "period", progress.period);
                if (!progress.seen.isEmpty()) {
                    playerYaml.set(q + "seen", new ArrayList<>(progress.seen));
                }
            }
        }

        YamlConfiguration cardYaml = new YamlConfiguration();
        for (CardRecord card : cards.values()) {
            String base = "cards." + card.id() + ".";
            cardYaml.set(base + "owner", card.owner().toString());
            cardYaml.set(base + "value", card.value());
            cardYaml.set(base + "tapped-in", card.tappedIn());
            cardYaml.set(base + "tap-in-time", card.tapInTime());
            cardYaml.set(base + "blocked", card.blocked());
            cardYaml.set(base + "history", new ArrayList<>(card.history()));
        }

        List<String> log = new ArrayList<>(logBuffer);
        logBuffer.clear();
        dirty = false;
        return new Snapshot(playerYaml.saveToString(), cardYaml.saveToString(), log);
    }

    private void write(Snapshot snapshot) {
        try {
            Files.createDirectories(plugin.getDataFolder().toPath());
            writeAtomic(playersFile.toPath(), snapshot.players());
            writeAtomic(cardsFile.toPath(), snapshot.cards());
            if (!snapshot.log().isEmpty()) {
                Files.write(logFile.toPath(), snapshot.log(), StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            }
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Could not save Lumina data", e);
        }
    }

    public static void writeAtomic(Path target, String content) throws IOException {
        Path temp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.writeString(temp, content, StandardCharsets.UTF_8);
        try {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
