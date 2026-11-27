package nl.jamie1408g.lumina.city;

import nl.jamie1408g.lumina.LuminaPlugin;
import nl.jamie1408g.lumina.storage.PlayerData;
import nl.jamie1408g.lumina.util.Money;
import nl.jamie1408g.lumina.util.Msg;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A short tour of how Lumina works, shown as title screens (with the same text in chat, so it can
 * be read back). Players get it once, automatically, the first time they're in Lumina; afterwards
 * /lumina walkthrough shows it again. The steps are in messages.yml under walkthrough.steps.
 */
public final class Walkthrough {

    private record Step(String title, String subtitle, String chat) {
    }

    private final LuminaPlugin plugin;
    private final Map<UUID, BukkitTask> running = new HashMap<>();
    private boolean auto = true;
    private long stepTicks = 100;

    public Walkthrough(LuminaPlugin plugin) {
        this.plugin = plugin;
    }

    public void load() {
        FileConfiguration c = plugin.getConfig();
        auto = c.getBoolean("walkthrough.show-once-automatically", true);
        stepTicks = Math.max(40, Math.round(c.getDouble("walkthrough.seconds-per-step", 5) * 20));
    }

    public boolean isRunning(Player player) {
        return running.containsKey(player.getUniqueId());
    }

    /** True if this player should get the walkthrough by itself now (in Lumina, never seen it). */
    public boolean due(Player player) {
        return auto && !isRunning(player) && !plugin.data(player).walkthroughSeen();
    }

    /** Starts (or restarts) the walkthrough. */
    public void start(Player player) {
        stop(player);
        List<Step> steps = steps();
        if (steps.isEmpty()) {
            return;
        }
        UUID id = player.getUniqueId();
        int[] index = {0};
        BukkitTask task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            Player p = Bukkit.getPlayer(id);
            if (p == null || !p.isOnline()) {
                stopTask(id);
                return;
            }
            if (index[0] >= steps.size()) {
                stopTask(id);
                markSeen(p);
                return;
            }
            show(p, steps.get(index[0]), index[0] == steps.size() - 1);
            index[0]++;
        }, 10L, stepTicks);
        running.put(id, task);
    }

    /** Skips the rest; it won't come back by itself. */
    public boolean skip(Player player) {
        boolean was = stop(player);
        markSeen(player);
        player.resetTitle();
        return was;
    }

    /** Stops without marking it as seen (e.g. the player left the server). */
    public boolean stop(Player player) {
        return stopTask(player.getUniqueId());
    }

    private boolean stopTask(UUID id) {
        BukkitTask task = running.remove(id);
        if (task != null) {
            task.cancel();
            return true;
        }
        return false;
    }

    private void markSeen(Player player) {
        PlayerData data = plugin.data(player);
        if (!data.walkthroughSeen()) {
            data.setWalkthroughSeen(true);
            plugin.storage().markDirty();
        }
    }

    private void show(Player player, Step step, boolean last) {
        int stay = (int) Math.max(20, stepTicks - 20);
        player.sendTitle(fill(step.title()), fill(step.subtitle()), 10, stay, 10);
        if (!step.chat().isEmpty()) {
            for (String line : fill(step.chat()).split("\n")) {
                player.sendMessage(line);
            }
        }
        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_CHIME, 0.6f, last ? 1.6f : 1.2f);
    }

    /** Colours, plus the current fares so the tour never shows old prices. */
    private String fill(String text) {
        if (text == null) {
            return "";
        }
        FileConfiguration c = plugin.getConfig();
        var fares = plugin.network().fares();
        return Msg.color(text
                .replace("{base}", Money.format(Money.fromDouble(c.getDouble("fares.base"))))
                .replace("{perkm}", Money.format(Money.fromDouble(c.getDouble("fares.per-km"))))
                .replace("{max}", Money.format(fares.max()))
                .replace("{incomplete}", Money.format(Money.fromDouble(c.getDouble("fares.incomplete-trip-fare")))));
    }

    private List<Step> steps() {
        List<Step> out = new ArrayList<>();
        for (Map<?, ?> m : plugin.msg().mapList("walkthrough.steps")) {
            Object title = m.get("title"), subtitle = m.get("subtitle"), chat = m.get("chat");
            out.add(new Step(title == null ? "" : title.toString(), subtitle == null ? "" : subtitle.toString(),
                    chat == null ? "" : chat.toString()));
        }
        return out;
    }
}
