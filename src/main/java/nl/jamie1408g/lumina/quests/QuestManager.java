package nl.jamie1408g.lumina.quests;

import nl.jamie1408g.lumina.LuminaPlugin;
import nl.jamie1408g.lumina.network.Station;
import nl.jamie1408g.lumina.storage.PlayerData;
import nl.jamie1408g.lumina.util.Money;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Quests that reward LumiCoins, driven by trips, station visits and work time. */
public final class QuestManager {

    public enum Type { TRIPS, TRIP_BETWEEN, DISTANCE, VISIT_STATIONS, WORK_MINUTES }

    public enum Repeat { NEVER, DAILY, WEEKLY }

    /** A quest from config.yml. from/to are station keys (only for TRIP_BETWEEN). */
    public record Quest(String id, String name, String description, Type type, long amount, long reward,
                        Repeat repeat, String from, String to, String job) {
    }

    private final LuminaPlugin plugin;
    private final Map<String, Quest> quests = new LinkedHashMap<>();
    private ZoneId zone = ZoneId.systemDefault();

    public QuestManager(LuminaPlugin plugin) {
        this.plugin = plugin;
    }

    /** Load after the network, so station names can be checked. */
    public void load() {
        quests.clear();
        try {
            zone = ZoneId.of(plugin.getConfig().getString("quests.reset-timezone", "UTC"));
        } catch (Exception e) {
            plugin.getLogger().warning("quests.reset-timezone is not a valid timezone, using the server timezone.");
            zone = ZoneId.systemDefault();
        }
        ConfigurationSection list = plugin.getConfig().getConfigurationSection("quests.list");
        if (list == null) {
            return;
        }
        for (String id : list.getKeys(false)) {
            ConfigurationSection q = list.getConfigurationSection(id);
            if (q == null) {
                continue;
            }
            Type type;
            Repeat repeat;
            try {
                type = Type.valueOf(q.getString("type", "TRIPS").toUpperCase(Locale.ROOT));
                repeat = Repeat.valueOf(q.getString("repeat", "NEVER").toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning("Quest " + id + " has an unknown type or repeat value and is skipped.");
                continue;
            }
            String from = null;
            String to = null;
            if (type == Type.TRIP_BETWEEN) {
                Station fromStation = plugin.network().network().findStation(q.getString("from", ""));
                Station toStation = plugin.network().network().findStation(q.getString("to", ""));
                if (fromStation == null || toStation == null) {
                    plugin.getLogger().warning("Quest " + id + ": unknown from/to station, quest skipped.");
                    continue;
                }
                from = fromStation.key();
                to = toStation.key();
            }
            long amount = type == Type.TRIP_BETWEEN ? 1 : Math.max(1, q.getLong("amount", 1));
            String job = q.getString("job", "");
            quests.put(id, new Quest(id, q.getString("name", id), q.getString("description", ""), type, amount,
                    Money.fromDouble(q.getDouble("reward", 0)), repeat, from, to,
                    job == null ? "" : job.toLowerCase(Locale.ROOT)));
        }
    }

    public Collection<Quest> quests() {
        return Collections.unmodifiableCollection(quests.values());
    }

    public Quest quest(String id) {
        return quests.get(id);
    }

    private long currentPeriod(Repeat repeat) {
        LocalDate today = LocalDate.now(zone);
        return switch (repeat) {
            case NEVER -> 0;
            case DAILY -> today.toEpochDay();
            case WEEKLY -> today.with(DayOfWeek.MONDAY).toEpochDay();
        };
    }

    /** Progress for a quest, reset automatically when a new day or week starts. */
    public PlayerData.QuestProgress progress(PlayerData data, Quest quest) {
        long period = currentPeriod(quest.repeat());
        PlayerData.QuestProgress progress = data.quests().get(quest.id());
        if (progress == null || progress.period != period) {
            progress = new PlayerData.QuestProgress();
            progress.period = period;
            data.quests().put(quest.id(), progress);
        }
        return progress;
    }

    public void reset(PlayerData data, String questId) {
        if (questId == null) {
            data.quests().clear();
        } else {
            data.quests().remove(questId);
        }
        plugin.storage().markDirty();
    }

    // ------------------------------------------------------------ hooks

    public void onTrip(Player player, Station from, Station to, int distance) {
        PlayerData data = plugin.data(player);
        for (Quest quest : quests.values()) {
            switch (quest.type()) {
                case TRIPS -> add(player, data, quest, 1);
                case DISTANCE -> add(player, data, quest, distance);
                case TRIP_BETWEEN -> {
                    if (from.key().equals(quest.from()) && to.key().equals(quest.to())) {
                        add(player, data, quest, 1);
                    }
                }
                default -> {
                }
            }
        }
    }

    public void onStationVisit(Player player, Station station) {
        PlayerData data = plugin.data(player);
        for (Quest quest : quests.values()) {
            if (quest.type() != Type.VISIT_STATIONS) {
                continue;
            }
            PlayerData.QuestProgress progress = progress(data, quest);
            if (!progress.completed && progress.seen.add(station.key())) {
                add(player, data, quest, 1);
            }
        }
    }

    public void onWork(Player player, String jobId, int minutes) {
        PlayerData data = plugin.data(player);
        for (Quest quest : quests.values()) {
            if (quest.type() == Type.WORK_MINUTES && (quest.job().isEmpty() || quest.job().equals(jobId))) {
                add(player, data, quest, minutes);
            }
        }
    }

    private void add(Player player, PlayerData data, Quest quest, long amount) {
        PlayerData.QuestProgress progress = progress(data, quest);
        if (progress.completed) {
            return;
        }
        progress.progress = Math.min(quest.amount(), progress.progress + amount);
        plugin.storage().markDirty();
        if (progress.progress >= quest.amount()) {
            progress.completed = true;
            plugin.economy().deposit(player.getUniqueId(), quest.reward(), "quest " + quest.id());
            plugin.msg().send(player, "quests.completed", "quest", quest.name(), "reward", Money.format(quest.reward()));
            player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.7f, 1.2f);
        }
    }
}
