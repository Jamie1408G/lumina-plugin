package nl.jamie1408g.lumina.card;

import nl.jamie1408g.lumina.LuminaPlugin;
import nl.jamie1408g.lumina.storage.PlayerData;
import nl.jamie1408g.lumina.util.Money;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.scheduler.BukkitTask;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.UUID;

/**
 * The Monthly Pass: a subscription (LC 100 for 30 days by default) for unlimited MRT and LRT rides
 * with your Lumina Card. It's on the player's account, so it stays when a lost card is replaced.
 * It renews itself from the wallet when it runs out (only while the player is online, so nobody is
 * charged for months they weren't playing); without enough LumiCoins it simply ends.
 */
public final class PassManager implements Listener {

    private static final long DAY = 86_400_000L;
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMM HH:mm", Locale.ENGLISH);

    private final LuminaPlugin plugin;
    private boolean enabled = true;
    private long price = 10_000;
    private int days = 30;
    private BukkitTask task;

    public PassManager(LuminaPlugin plugin) {
        this.plugin = plugin;
    }

    public void load() {
        FileConfiguration c = plugin.getConfig();
        enabled = c.getBoolean("pass.enabled", true);
        price = Money.fromDouble(c.getDouble("pass.price", 100.00));
        days = Math.max(1, c.getInt("pass.days", 30));
        if (task == null) {
            // Renew or end passes of online players every few minutes.
            task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
                for (Player p : Bukkit.getOnlinePlayers()) {
                    renewIfDue(plugin.data(p), p);
                }
            }, 1200L, 6000L);
        }
    }

    public boolean enabled() {
        return enabled;
    }

    public long price() {
        return price;
    }

    public int days() {
        return days;
    }

    /** Whether this card owner's pass is valid right now (renewing it first if it's due and they're online). */
    public boolean active(UUID owner) {
        if (!enabled) {
            return false;
        }
        PlayerData data = plugin.storage().get(owner);
        if (data == null) {
            return false;
        }
        Player online = Bukkit.getPlayer(owner);
        if (online != null) {
            renewIfDue(data, online);
        }
        return data.passUntil() > System.currentTimeMillis();
    }

    /** Like active(), without renewing anything (for showing it). */
    public boolean validNow(UUID owner) {
        PlayerData data = plugin.storage().get(owner);
        return enabled && data != null && data.passUntil() > System.currentTimeMillis();
    }

    /** "28 Dec 14:05", the end of the current pass, or null. */
    public String until(UUID owner) {
        PlayerData data = plugin.storage().get(owner);
        if (data == null || data.passUntil() <= System.currentTimeMillis()) {
            return null;
        }
        return DATE.format(Instant.ofEpochMilli(data.passUntil()).atZone(ZoneId.systemDefault()));
    }

    public boolean renews(UUID owner) {
        PlayerData data = plugin.storage().get(owner);
        return data != null && data.passRenew();
    }

    /** A pass that has run out: renew it from the wallet, or end it. */
    public void renewIfDue(PlayerData data, Player player) {
        long now = System.currentTimeMillis();
        if (data.passUntil() <= 0 || data.passUntil() > now) {
            return;
        }
        if (data.passRenew() && enabled && plugin.economy().withdraw(data.uuid(), price, "Monthly Pass renewal")) {
            data.setPassUntil(now + days * DAY);
            plugin.storage().markDirty();
            plugin.msg().send(player, "pass.renewed", "price", Money.format(price), "until", until(data.uuid()));
            plugin.cards().refreshItems(player);
            return;
        }
        boolean triedToRenew = data.passRenew();
        data.setPassUntil(0);
        data.setPassRenew(false);
        plugin.storage().markDirty();
        plugin.msg().send(player, triedToRenew ? "pass.renew-failed" : "pass.ended",
                "price", Money.format(price), "balance", Money.format(data.balance()));
        plugin.cards().refreshItems(player);
    }

    /** Buys the pass for days days. Comes with a free Lumina Card if they don't have one. */
    public void buy(Player player) {
        if (!enabled) {
            plugin.msg().send(player, "pass.disabled");
            return;
        }
        PlayerData data = plugin.data(player);
        if (active(player.getUniqueId())) {
            plugin.msg().send(player, "pass.already", "until", until(player.getUniqueId()),
                    "renew", plugin.msg().get(data.passRenew() ? "pass.word-renews" : "pass.word-ends"));
            return;
        }
        if (!plugin.economy().withdraw(player.getUniqueId(), price, "Monthly Pass")) {
            plugin.msg().send(player, "general.not-enough-money", "amount", Money.format(price),
                    "balance", Money.format(data.balance()));
            plugin.gates().playDeny(player);
            return;
        }
        data.setPassUntil(System.currentTimeMillis() + days * DAY);
        data.setPassRenew(true);
        plugin.storage().markDirty();
        if (plugin.cards().activeCards(player.getUniqueId()).isEmpty()) {
            var card = plugin.cards().giveFree(player, 0);
            plugin.msg().send(player, "pass.card-included", "id", card.id());
        }
        plugin.cards().refreshItems(player);
        plugin.msg().send(player, "pass.bought", "price", Money.format(price), "days", days,
                "until", until(player.getUniqueId()));
        plugin.gates().playSuccess(player);
    }

    /** Turn automatic renewal on or off. The pass stays valid until it ends either way. */
    public void setRenew(Player player, boolean renew) {
        PlayerData data = plugin.data(player);
        if (!validNow(player.getUniqueId())) {
            plugin.msg().send(player, "pass.none", "price", Money.format(price), "days", days);
            return;
        }
        data.setPassRenew(renew);
        plugin.storage().markDirty();
        plugin.msg().send(player, renew ? "pass.renew-on" : "pass.renew-off", "until", until(player.getUniqueId()),
                "price", Money.format(price));
    }

    /** One line about the pass, for /luminacard info and the ticket machine. */
    public String status(UUID owner) {
        String until = until(owner);
        if (until == null) {
            return plugin.msg().get("pass.status-none", "price", Money.format(price), "days", days);
        }
        return plugin.msg().get(renews(owner) ? "pass.status-renews" : "pass.status-ends", "until", until,
                "price", Money.format(price));
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline()) {
                renewIfDue(plugin.data(player), player);
                plugin.cards().refreshItems(player);
            }
        }, 40L);
    }
}
