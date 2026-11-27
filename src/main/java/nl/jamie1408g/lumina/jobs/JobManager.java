package nl.jamie1408g.lumina.jobs;

import nl.jamie1408g.lumina.LuminaPlugin;
import nl.jamie1408g.lumina.storage.PlayerData;
import nl.jamie1408g.lumina.util.Money;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * City jobs. Players pick a role themselves, go on or off duty to show they are
 * available, and earn a salary for active time on duty.
 *
 * Job requests: anyone can ask for a job ("/luminajob request lupa-builder approve my house").
 * Someone on duty in that job accepts it. While they're on an accepted job, faregates let them
 * through for free (jobs with free-travel), but the fares are noted: they're only forgiven once
 * the person who asked confirms the job is done. Dropped or timed-out jobs are charged after all.
 */
public final class JobManager implements Listener {

    /** A job from config.yml. */
    public record Role(String id, String name, String description, long salary, int maxPlayers,
                       String permission, boolean propertyBypass, boolean freeTravel) {
    }

    private static final long CALL_COOLDOWN_MILLIS = 60_000L;

    private final LuminaPlugin plugin;
    private final Map<String, Role> roles = new LinkedHashMap<>();
    private final Map<UUID, Long> lastActivity = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastCall = new HashMap<>();
    private final JobBoard board = new JobBoard();
    private int jobMinutes = 60;
    private int requestMinutes = 120;
    private int rideHomeMinutes = 30;
    private int payPeriod = 20;
    private long afkMillis = 5 * 60_000L;
    private long switchCooldownMillis = 60 * 60_000L;
    private BukkitTask ticker;

    public JobManager(LuminaPlugin plugin) {
        this.plugin = plugin;
    }

    public void load() {
        FileConfiguration config = plugin.getConfig();
        payPeriod = Math.max(1, config.getInt("jobs.pay-period-minutes", 20));
        afkMillis = Math.max(1, config.getLong("jobs.afk-minutes", 5)) * 60_000L;
        switchCooldownMillis = Math.max(0, config.getLong("jobs.switch-cooldown-minutes", 60)) * 60_000L;
        jobMinutes = Math.max(5, config.getInt("jobs.job-time-limit-minutes", 60));
        requestMinutes = Math.max(5, config.getInt("jobs.request-expires-minutes", 120));
        rideHomeMinutes = Math.max(0, config.getInt("jobs.free-ride-home-minutes", 30));

        roles.clear();
        ConfigurationSection section = config.getConfigurationSection("jobs.roles");
        if (section != null) {
            for (String id : section.getKeys(false)) {
                ConfigurationSection r = section.getConfigurationSection(id);
                if (r == null) {
                    continue;
                }
                String key = id.toLowerCase(Locale.ROOT);
                roles.put(key, new Role(key, r.getString("name", id), r.getString("description", ""),
                        Money.fromDouble(r.getDouble("salary", 0)), Math.max(0, r.getInt("max-players", 0)),
                        r.getString("permission", ""), r.getBoolean("property-bypass", false),
                        r.getBoolean("free-travel", false)));
            }
        }

        if (ticker != null) {
            ticker.cancel();
        }
        ticker = Bukkit.getScheduler().runTaskTimer(plugin, this::tickMinute, 1200L, 1200L);
        long now = System.currentTimeMillis();
        for (Player player : Bukkit.getOnlinePlayers()) {
            lastActivity.putIfAbsent(player.getUniqueId(), now);
        }
    }

    public Collection<Role> roles() {
        return Collections.unmodifiableCollection(roles.values());
    }

    public Role role(String id) {
        return id == null ? null : roles.get(id.toLowerCase(Locale.ROOT));
    }

    public Role roleOf(PlayerData data) {
        return role(data.job());
    }

    public int payPeriod() {
        return payPeriod;
    }

    /**
     * Job pass: free through the faregates while on an accepted job (for jobs with free-travel).
     * The fares are noted with {@link #noteFare} and only forgiven if the job gets completed.
     */
    public boolean freeTravel(Player player) {
        PlayerData data = plugin.data(player);
        Role role = roleOf(data);
        return role != null && role.freeTravel() && board.activeFor(player.getUniqueId()) != null;
    }

    /** A fare the player would have paid while on their job. */
    public void noteFare(Player player, long fare) {
        board.note(player.getUniqueId(), fare);
    }

    /** After a completed (or cancelled) job, the worker's next tap-out is free. Uses it up. */
    public boolean useRideHome(Player player) {
        return board.useRideHome(player.getUniqueId(), System.currentTimeMillis());
    }

    public JobBoard board() {
        return board;
    }

    public int jobMinutes() {
        return jobMinutes;
    }

    public int requestMinutes() {
        return requestMinutes;
    }

    public boolean propertyBypass(Player player) {
        PlayerData data = plugin.data(player);
        Role role = roleOf(data);
        return role != null && role.propertyBypass() && data.onDuty();
    }

    public int members(String roleId) {
        int count = 0;
        for (PlayerData data : plugin.storage().allPlayers()) {
            if (roleId.equals(data.job())) {
                count++;
            }
        }
        return count;
    }

    public List<Player> onDuty(String roleId) {
        List<Player> result = new ArrayList<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            PlayerData data = plugin.storage().get(player.getUniqueId());
            if (data != null && data.onDuty() && roleId.equals(data.job())) {
                result.add(player);
            }
        }
        return result;
    }

    public boolean isAfk(Player player) {
        Long last = lastActivity.get(player.getUniqueId());
        return last == null || System.currentTimeMillis() - last > afkMillis;
    }

    // ------------------------------------------------------------ actions

    public void join(Player player, String roleId) {
        Role role = role(roleId);
        PlayerData data = plugin.data(player);
        if (role == null) {
            plugin.msg().send(player, "jobs.unknown", "job", roleId);
            return;
        }
        if (role.id().equals(data.job())) {
            plugin.msg().send(player, "jobs.already", "job", role.name());
            return;
        }
        if (!role.permission().isEmpty() && !player.hasPermission(role.permission())) {
            plugin.msg().send(player, "jobs.no-permission", "job", role.name());
            return;
        }
        if (role.maxPlayers() > 0 && members(role.id()) >= role.maxPlayers()) {
            plugin.msg().send(player, "jobs.full", "job", role.name(), "max", role.maxPlayers());
            return;
        }
        long wait = data.lastJobChange() + switchCooldownMillis - System.currentTimeMillis();
        if (data.lastJobChange() > 0 && wait > 0) {
            plugin.msg().send(player, "jobs.cooldown", "minutes", (wait + 59_999) / 60_000);
            return;
        }
        data.setJob(role.id());
        data.setOnDuty(false);
        data.setPeriodMinutes(0);
        data.setLastJobChange(System.currentTimeMillis());
        plugin.storage().markDirty();
        plugin.msg().send(player, "jobs.joined", "job", role.name(), "salary", Money.format(role.salary()),
                "period", payPeriod);
    }

    public void leave(Player player) {
        PlayerData data = plugin.data(player);
        Role role = roleOf(data);
        if (role == null) {
            plugin.msg().send(player, "jobs.no-job");
            return;
        }
        drop(player, "jobs.dropped-role");
        data.setJob(null);
        data.setOnDuty(false);
        data.setPeriodMinutes(0);
        data.setLastJobChange(System.currentTimeMillis());
        plugin.storage().markDirty();
        plugin.msg().send(player, "jobs.left", "job", role.name());
    }

    public void setDuty(Player player, Boolean wanted) {
        PlayerData data = plugin.data(player);
        Role role = roleOf(data);
        if (role == null) {
            plugin.msg().send(player, "jobs.no-job");
            return;
        }
        boolean duty = wanted == null ? !data.onDuty() : wanted;
        data.setOnDuty(duty);
        lastActivity.put(player.getUniqueId(), System.currentTimeMillis());
        if (duty) {
            plugin.msg().send(player, "jobs.on-duty", "job", role.name());
            for (Player other : onDuty(role.id())) {
                if (!other.equals(player)) {
                    plugin.msg().send(other, "jobs.colleague-on-duty", "player", player.getName(), "job", role.name());
                }
            }
        } else {
            plugin.msg().send(player, "jobs.off-duty", "job", role.name());
        }
    }

    /** Posts a job request and tells everyone on duty in that job. ("call" does the same.) */
    public void request(Player player, String roleId, String message) {
        Role role = role(roleId);
        if (role == null) {
            plugin.msg().send(player, "jobs.unknown", "job", roleId);
            return;
        }
        long now = System.currentTimeMillis();
        Long last = lastCall.get(player.getUniqueId());
        if (last != null && now - last < CALL_COOLDOWN_MILLIS) {
            plugin.msg().send(player, "jobs.call-cooldown", "seconds", (CALL_COOLDOWN_MILLIS - (now - last) + 999) / 1000);
            return;
        }
        JobBoard.Job job = board.request(player.getUniqueId(), player.getName(), role.id(), message, now);
        if (job == null) {
            plugin.msg().send(player, "jobs.request-limit", "max", JobBoard.MAX_REQUESTS);
            return;
        }
        lastCall.put(player.getUniqueId(), now);
        Location loc = player.getLocation();
        String where = loc.getBlockX() + ", " + loc.getBlockY() + ", " + loc.getBlockZ();
        var nearest = plugin.network().network().nearest(player.getWorld().getName(), loc.getX(), loc.getZ());
        String near = nearest == null ? where : nearest.station().name() + " (" + where + ")";
        List<Player> staff = onDuty(role.id());
        staff.remove(player);
        for (Player member : staff) {
            plugin.msg().send(member, "jobs.request-received", "id", job.id, "player", player.getName(), "job", role.name(),
                    "message", message.isEmpty() ? "-" : message, "where", near);
            plugin.gates().playSuccess(member);
        }
        if (staff.isEmpty()) {
            plugin.msg().send(player, "jobs.request-nobody", "id", job.id, "job", role.name(), "minutes", requestMinutes);
        } else {
            plugin.msg().send(player, "jobs.request-sent", "id", job.id, "job", role.name(), "count", staff.size());
        }
    }

    /** Someone on duty takes a request (id null = the only open one for their job). */
    public void accept(Player player, Integer id) {
        PlayerData data = plugin.data(player);
        Role role = roleOf(data);
        if (role == null) {
            plugin.msg().send(player, "jobs.no-job");
            return;
        }
        if (!data.onDuty()) {
            plugin.msg().send(player, "jobs.accept-off-duty");
            return;
        }
        if (id == null) {
            List<JobBoard.Job> open = board.open(role.id());
            if (open.size() != 1) {
                plugin.msg().send(player, "jobs.accept-which");
                return;
            }
            id = open.get(0).id;
        }
        String problem = board.accept(id, player.getUniqueId(), player.getName(), role.id(), System.currentTimeMillis());
        if (problem != null) {
            plugin.msg().send(player, "jobs.accept-failed", "reason", problem);
            return;
        }
        JobBoard.Job job = board.byId(id);
        plugin.msg().send(player, role.freeTravel() ? "jobs.accepted-free" : "jobs.accepted", "id", job.id,
                "player", job.requesterName, "minutes", jobMinutes);
        tell(job.requester, "jobs.request-taken", "id", job.id, "player", player.getName(), "job", role.name());
    }

    /** The person who asked confirms the job is done. */
    public void done(Player player, Integer id) {
        JobBoard.Job job = board.complete(player.getUniqueId(), id, System.currentTimeMillis(), rideHomeMinutes * 60_000L);
        if (job == null) {
            plugin.msg().send(player, board.activeFor(player.getUniqueId()) != null ? "jobs.done-not-yours" : "jobs.done-none");
            return;
        }
        plugin.msg().send(player, "jobs.done", "id", job.id, "player", job.workerName());
        Role role = role(job.roleKey);
        boolean free = role != null && role.freeTravel();
        tell(job.worker(), free ? "jobs.done-worker-free" : "jobs.done-worker", "id", job.id, "player", job.requesterName,
                "fare", Money.format(job.owed()), "minutes", rideHomeMinutes);
    }

    /** The worker gives the job back; the travel noted on it is charged. */
    public void drop(Player player, String reasonKey) {
        JobBoard.Job job = board.drop(player.getUniqueId());
        if (job == null) {
            if (reasonKey == null) {
                plugin.msg().send(player, "jobs.drop-none");
            }
            return;
        }
        chargeUnfinished(job, reasonKey == null ? "jobs.dropped" : reasonKey);
        tell(job.requester, "jobs.request-dropped", "id", job.id, "player", job.workerName());
    }

    /** The person who asked withdraws the request. The worker isn't charged. */
    public void cancel(Player player, Integer id) {
        JobBoard.Job job = board.cancel(player.getUniqueId(), id);
        if (job == null) {
            plugin.msg().send(player, "jobs.cancel-none");
            return;
        }
        plugin.msg().send(player, "jobs.cancelled", "id", job.id);
        if (job.worker() != null) {
            tell(job.worker(), "jobs.cancelled-worker", "id", job.id, "player", job.requesterName);
        }
    }

    /** Charges the worker the fares noted on a job that wasn't completed: from their card, else their wallet. */
    private void chargeUnfinished(JobBoard.Job job, String reasonKey) {
        UUID worker = job.worker();
        if (worker == null) {
            return;
        }
        long owed = job.owed();
        if (owed > 0) {
            var cards = plugin.cards().activeCards(worker);
            if (!cards.isEmpty()) {
                var card = cards.get(0);
                card.setValue(card.value() - owed);
                card.addHistory(java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("dd-MM HH:mm"))
                        + " | job #" + job.id + " not completed | -" + Money.plain(owed));
            } else {
                long take = Math.min(owed, plugin.economy().balance(worker));
                if (take > 0) {
                    plugin.economy().withdraw(worker, take, "job " + job.id + " not completed");
                }
            }
            plugin.storage().markDirty();
        }
        tell(worker, reasonKey, "id", job.id, "player", job.requesterName, "fare", Money.format(owed));
    }

    private void tell(UUID uuid, String key, Object... placeholders) {
        Player p = Bukkit.getPlayer(uuid);
        if (p != null) {
            plugin.msg().send(p, key, placeholders);
        }
    }

    private void expireJobs() {
        for (JobBoard.Job job : board.expire(System.currentTimeMillis(), jobMinutes * 60_000L, requestMinutes * 60_000L)) {
            if (job.state() == JobBoard.State.ACCEPTED) {
                chargeUnfinished(job, "jobs.expired-worker");
                tell(job.requester, "jobs.expired-requester", "id", job.id);
            } else {
                tell(job.requester, "jobs.request-unanswered", "id", job.id);
            }
        }
    }

    /** Admin: put a player in a job (or none) without checks. */
    public void adminSet(PlayerData data, Role role) {
        Player online = Bukkit.getPlayer(data.uuid());
        if (online != null) {
            drop(online, "jobs.dropped-role");
        }
        data.setJob(role == null ? null : role.id());
        data.setOnDuty(false);
        data.setPeriodMinutes(0);
        data.setLastJobChange(System.currentTimeMillis());
        plugin.storage().markDirty();
    }

    // ------------------------------------------------------------ salary

    private void tickMinute() {
        expireJobs();
        for (Player player : Bukkit.getOnlinePlayers()) {
            PlayerData data = plugin.storage().get(player.getUniqueId());
            if (data == null || !data.onDuty()) {
                continue;
            }
            Role role = roleOf(data);
            if (role == null) {
                data.setOnDuty(false);
                continue;
            }
            if (isAfk(player)) {
                continue;
            }
            data.setWorkMinutes(data.workMinutes() + 1);
            data.setPeriodMinutes(data.periodMinutes() + 1);
            plugin.quests().onWork(player, role.id(), 1);
            if (data.periodMinutes() >= payPeriod) {
                data.setPeriodMinutes(0);
                if (role.salary() > 0) {
                    plugin.economy().deposit(player.getUniqueId(), role.salary(), "salary " + role.id());
                    plugin.msg().send(player, "jobs.paid", "salary", Money.format(role.salary()), "job", role.name(),
                            "balance", Money.format(data.balance()));
                    plugin.gates().playSuccess(player);
                }
            }
            plugin.storage().markDirty();
        }
    }

    // ------------------------------------------------------------ activity

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        lastActivity.put(event.getPlayer().getUniqueId(), System.currentTimeMillis());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChat(AsyncPlayerChatEvent event) {
        lastActivity.put(event.getPlayer().getUniqueId(), System.currentTimeMillis());
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        lastActivity.put(event.getPlayer().getUniqueId(), System.currentTimeMillis());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        PlayerData data = plugin.storage().get(uuid);
        if (data != null) {
            data.setOnDuty(false);
        }
        lastActivity.remove(uuid);
        lastCall.remove(uuid);
    }
}
