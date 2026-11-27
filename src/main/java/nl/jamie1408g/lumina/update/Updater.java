package nl.jamie1408g.lumina.update;

import nl.jamie1408g.lumina.LuminaPlugin;
import nl.jamie1408g.lumina.util.Msg;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;

/**
 * Keeps Lumina up to date by itself. It looks at the latest release of the GitHub repository; when
 * there's a newer version it downloads the jar into the server's update folder, and the server
 * installs it on its next restart (that's how Paper and Spigot update plugins safely). A download
 * is only kept if its SHA-256 matches the published .sha256 file and the jar really is Lumina.
 */
public final class Updater implements Listener {

    public static final String PERMISSION = "lumina.admin.update";

    private record Config(String github, int everyHours, boolean autoDownload, boolean requireChecksum) {
    }

    private final LuminaPlugin plugin;
    private final String current;
    private final HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private Config config = new Config("", 6, true, true);
    private BukkitTask task;
    private volatile String pending;   // version waiting in the update folder
    private volatile String available; // newer version seen but not downloaded (auto-download off)
    private volatile String lastResult = "not checked yet";
    private volatile boolean checking;

    public Updater(LuminaPlugin plugin) {
        this.plugin = plugin;
        this.current = plugin.getDescription().getVersion();
    }

    public String current() {
        return current;
    }

    public String lastResult() {
        return lastResult;
    }

    /** Reads updater: from config.yml and checks a minute after start-up, then every few hours. */
    public void load() {
        FileConfiguration c = plugin.getConfig();
        config = new Config(c.getString("updater.github", "").trim(), Math.max(1, c.getInt("updater.check-every-hours", 6)),
                c.getBoolean("updater.auto-download", true), c.getBoolean("updater.require-checksum", true));
        if (task != null) {
            task.cancel();
            task = null;
        }
        if (!config.github().isEmpty()) {
            long every = config.everyHours() * 72_000L;
            task = Bukkit.getScheduler().runTaskTimer(plugin, () -> check(null, false), 1200L, every);
        }
    }

    /**
     * Checks in the background and reports to who (or the console). force = download even when
     * auto-download is off (staff typed /lumina update).
     */
    public void check(CommandSender who, boolean force) {
        Config c = config;
        if (c.github().isEmpty()) {
            if (who != null) {
                plugin.msg().send(who, "update.not-set-up");
            }
            return;
        }
        if (checking) {
            if (who != null) {
                plugin.msg().send(who, "update.busy");
            }
            return;
        }
        checking = true;
        File updateFolder = Bukkit.getUpdateFolderFile(); // read on the main thread
        Path work = plugin.getDataFolder().toPath();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            String result;
            try {
                result = run(c, updateFolder.toPath(), work, force || c.autoDownload());
            } catch (Exception e) {
                result = "Update check failed: " + e.getMessage();
            }
            lastResult = result;
            checking = false;
            String text = result;
            Bukkit.getScheduler().runTask(plugin, () -> {
                plugin.getLogger().info(text);
                if (who != null) {
                    who.sendMessage(plugin.msg().get("prefix") + Msg.color("&7") + text);
                } else if (pending != null && text.contains("downloaded")) {
                    for (Player p : Bukkit.getOnlinePlayers()) {
                        if (p.hasPermission(PERMISSION)) {
                            remind(p);
                        }
                    }
                }
            });
        });
    }

    /** The actual check (off the main thread). Returns what happened, in a sentence. */
    private String run(Config c, Path updateFolder, Path work, boolean download) throws IOException, InterruptedException {
        HttpResponse<String> res = http.send(HttpRequest.newBuilder(URI.create("https://api.github.com/repos/" + c.github() + "/releases/latest"))
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "Lumina-Updater/" + current)
                .timeout(Duration.ofSeconds(20)).GET().build(), HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() == 404) {
            return "No releases found at github.com/" + c.github() + " (is the repository public, with a release?).";
        }
        if (res.statusCode() != 200) {
            return "GitHub answered " + res.statusCode() + " when checking for updates.";
        }
        UpdateCheck.Release r = UpdateCheck.parse(res.body());
        if (r == null) {
            return "The latest release on GitHub has no .jar attached.";
        }
        if (UpdateCheck.compare(r.version(), current) <= 0) {
            available = null;
            return "Lumina is up to date (" + current + ").";
        }
        if (r.version().equals(pending)) {
            return "Lumina " + r.version() + " is already downloaded; it'll be installed on the next restart.";
        }
        if (!download) {
            available = r.version();
            return "Lumina " + r.version() + " is available (you have " + current + "). Download it with /lumina update.";
        }

        Files.createDirectories(updateFolder);
        Files.createDirectories(work);
        // Download next to the plugin's data first; only a checked jar ever goes into the update folder.
        Path tmp = Files.createTempFile(work, "update-", ".download");
        try {
            HttpResponse<Path> jar = http.send(HttpRequest.newBuilder(URI.create(r.jarUrl()))
                            .header("User-Agent", "Lumina-Updater/" + current).timeout(Duration.ofMinutes(2)).GET().build(),
                    HttpResponse.BodyHandlers.ofFile(tmp));
            if (jar.statusCode() != 200) {
                return "Downloading Lumina " + r.version() + " failed (" + jar.statusCode() + ").";
            }
            if (r.checksumUrl() != null) {
                HttpResponse<String> sum = http.send(HttpRequest.newBuilder(URI.create(r.checksumUrl()))
                                .header("User-Agent", "Lumina-Updater/" + current).timeout(Duration.ofSeconds(20)).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                String want = sum.statusCode() == 200 ? UpdateCheck.expectedHash(sum.body()) : null;
                if (want == null) {
                    return "Couldn't read the checksum for Lumina " + r.version() + "; not installing it.";
                }
                if (!want.equals(UpdateCheck.sha256(tmp))) {
                    return "Lumina " + r.version() + " didn't match its checksum; not installing it.";
                }
            } else if (c.requireChecksum()) {
                return "Lumina " + r.version() + " has no .sha256 file, so it wasn't installed (updater.require-checksum is on).";
            }
            if (!"Lumina".equals(UpdateCheck.pluginName(tmp))) {
                return "The download for " + r.version() + " isn't the Lumina plugin; not installing it.";
            }
            Files.move(tmp, updateFolder.resolve("Lumina.jar"), StandardCopyOption.REPLACE_EXISTING);
            pending = r.version();
            available = null;
            return "Lumina " + r.version() + " downloaded. It'll be installed on the next restart (you have " + current + ").";
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private void remind(Player p) {
        if (pending != null) {
            plugin.msg().send(p, "update.pending", "version", pending);
        } else if (available != null) {
            plugin.msg().send(p, "update.available", "version", available);
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        if (event.getPlayer().hasPermission(PERMISSION)) {
            remind(event.getPlayer());
        }
    }

    /** For /lumina version. */
    public void status(CommandSender who) {
        plugin.msg().send(who, "update.version", "version", current,
                "repo", config.github().isEmpty() ? "-" : config.github(), "result", lastResult);
        if (pending != null) {
            plugin.msg().send(who, "update.pending", "version", pending);
        }
    }
}
