package nl.jamie1408g.lumina.city;

import nl.jamie1408g.lumina.LuminaPlugin;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Lumina's city border and districts (boxes on the map, any height, stored in areas.yml) and its
 * laws (laws.yml). Crossing the border shows "Welcome to Lumina", followed every time by a short
 * hint that /lulaws opens the laws. Walking into a district shows its name on the action bar.
 */
public final class CityManager implements Listener {

    public record Box(String world, int minX, int minZ, int maxX, int maxZ) {
        public static Box of(String world, int x1, int z1, int x2, int z2) {
            return new Box(world, Math.min(x1, x2), Math.min(z1, z2), Math.max(x1, x2), Math.max(z1, z2));
        }

        public boolean contains(String w, int x, int z) {
            return world.equals(w) && x >= minX && x <= maxX && z >= minZ && z <= maxZ;
        }

        public long size() {
            return (long) (maxX - minX + 1) * (maxZ - minZ + 1);
        }
    }

    public record District(String name, Box box) {
    }

    private record Spot(boolean inCity, String district) {
    }

    private final LuminaPlugin plugin;
    private final File areasFile;
    private Box city;
    private final List<District> districts = new ArrayList<>();
    private final Map<UUID, Spot> spots = new HashMap<>();
    private final Map<UUID, Long> lastWelcome = new HashMap<>();
    private Laws laws = Laws.NONE;
    private List<String> lawPages = List.of();
    private boolean enabled = true;
    private long welcomeCooldownMillis = 60_000L;
    private long hintDelayTicks = 40L;

    public CityManager(LuminaPlugin plugin) {
        this.plugin = plugin;
        this.areasFile = new File(plugin.getDataFolder(), "areas.yml");
    }

    // ------------------------------------------------------------ loading

    public void load() {
        FileConfiguration config = plugin.getConfig();
        enabled = config.getBoolean("city.enabled", true);
        welcomeCooldownMillis = Math.max(0, config.getLong("city.welcome-cooldown-seconds", 60)) * 1000L;
        hintDelayTicks = Math.max(0, config.getLong("city.laws-hint-delay-ticks", 40));
        loadAreas();
        loadLaws();
        spots.clear();
    }

    private void loadLaws() {
        File file = new File(plugin.getDataFolder(), "laws.yml");
        if (!file.exists()) {
            plugin.saveResource("laws.yml", false);
        }
        List<String> warnings = new ArrayList<>();
        laws = Laws.load(YamlConfiguration.loadConfiguration(file).getConfigurationSection("laws"), warnings);
        lawPages = laws.isEmpty() ? List.of() : LawBook.pages(laws);
        for (String w : warnings) {
            plugin.getLogger().warning("laws.yml: " + w);
        }
    }

    private void loadAreas() {
        city = null;
        districts.clear();
        if (!areasFile.exists()) {
            return;
        }
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(areasFile);
        city = box(yml.getConfigurationSection("city"));
        ConfigurationSection ds = yml.getConfigurationSection("districts");
        if (ds != null) {
            for (String key : ds.getKeys(false)) {
                ConfigurationSection d = ds.getConfigurationSection(key);
                Box b = box(d);
                if (b != null) {
                    districts.add(new District(d.getString("name", key), b));
                }
            }
        }
    }

    private static Box box(ConfigurationSection s) {
        if (s == null || !s.contains("world")) {
            return null;
        }
        return Box.of(s.getString("world"), s.getInt("min-x"), s.getInt("min-z"), s.getInt("max-x"), s.getInt("max-z"));
    }

    private static void put(YamlConfiguration yml, String path, Box b) {
        yml.set(path + ".world", b.world());
        yml.set(path + ".min-x", b.minX());
        yml.set(path + ".min-z", b.minZ());
        yml.set(path + ".max-x", b.maxX());
        yml.set(path + ".max-z", b.maxZ());
    }

    private void saveAreas() {
        YamlConfiguration yml = new YamlConfiguration();
        yml.options().setHeader(List.of("Lumina's city border and districts. Edit in game with /lumina city and /lumina district."));
        if (city != null) {
            put(yml, "city", city);
        }
        for (int i = 0; i < districts.size(); i++) {
            yml.set("districts." + i + ".name", districts.get(i).name());
            put(yml, "districts." + i, districts.get(i).box());
        }
        try {
            yml.save(areasFile);
        } catch (IOException e) {
            plugin.getLogger().severe("Could not save areas.yml: " + e.getMessage());
        }
        spots.clear(); // everyone's position is worked out fresh, silently
    }

    // ------------------------------------------------------------ areas

    public Box city() {
        return city;
    }

    public List<District> districts() {
        return Collections.unmodifiableList(districts);
    }

    public void setCity(Box box) {
        city = box;
        saveAreas();
    }

    /** Adds a district, replacing one with the same name. */
    public void addDistrict(String name, Box box) {
        districts.removeIf(d -> Laws.normalize(d.name()).equals(Laws.normalize(name)));
        districts.add(new District(name.trim(), box));
        saveAreas();
    }

    public boolean removeDistrict(String name) {
        boolean removed = districts.removeIf(d -> Laws.normalize(d.name()).equals(Laws.normalize(name)));
        if (removed) {
            saveAreas();
        }
        return removed;
    }

    public boolean inCity(Location l) {
        return l.getWorld() != null && city != null && city.contains(l.getWorld().getName(), l.getBlockX(), l.getBlockZ());
    }

    /** The smallest district containing this spot, so a small district inside a bigger one wins. */
    public District districtAt(Location l) {
        if (l.getWorld() == null) {
            return null;
        }
        District best = null;
        for (District d : districts) {
            if (d.box().contains(l.getWorld().getName(), l.getBlockX(), l.getBlockZ())
                    && (best == null || d.box().size() < best.box().size())) {
                best = d;
            }
        }
        return best;
    }

    // ------------------------------------------------------------ laws

    public Laws laws() {
        return laws;
    }

    /** Opens the law book on the player's screen. Nothing is added to their inventory. */
    public void openLaws(Player player) {
        ItemStack item = new ItemStack(Material.WRITTEN_BOOK);
        BookMeta meta = (BookMeta) item.getItemMeta();
        if (meta == null) {
            return;
        }
        meta.setTitle(laws.bookTitle());
        meta.setAuthor("Lumina");
        meta.setPages(lawPages);
        item.setItemMeta(meta);
        player.openBook(item);
    }

    // ------------------------------------------------------------ crossing borders

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Location to = event.getTo();
        if (to == null) {
            return;
        }
        Location from = event.getFrom();
        if (from.getBlockX() == to.getBlockX() && from.getBlockZ() == to.getBlockZ()
                && Objects.equals(from.getWorld(), to.getWorld())) {
            return;
        }
        update(event.getPlayer(), to, true);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        if (event.getTo() != null) {
            update(event.getPlayer(), event.getTo(), true);
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        update(event.getPlayer(), event.getPlayer().getLocation(), false);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        spots.remove(event.getPlayer().getUniqueId());
        lastWelcome.remove(event.getPlayer().getUniqueId());
    }

    private void update(Player player, Location to, boolean show) {
        if (!enabled || city == null && districts.isEmpty()) {
            return;
        }
        District d = districtAt(to);
        Spot now = new Spot(inCity(to), d == null ? null : d.name());
        Spot before = spots.put(player.getUniqueId(), now);
        if (!show || before == null || before.equals(now)) {
            return; // the first update (just joined, or areas edited) only records where they are
        }
        boolean entered = now.inCity() && !before.inCity();
        boolean left = !now.inCity() && before.inCity();
        boolean districtChanged = !Objects.equals(before.district(), now.district());
        if (entered && welcome(player, now.district())) {
            return;
        }
        if (left) {
            plugin.msg().actionBar(player, "city.leaving");
        } else if (districtChanged && now.district() != null) {
            plugin.msg().actionBar(player, "city.district", "district", now.district());
        }
    }

    /** "Welcome to Lumina", then the laws hint. False if it was shown too recently. */
    private boolean welcome(Player player, String district) {
        long now = System.currentTimeMillis();
        Long last = lastWelcome.get(player.getUniqueId());
        if (last != null && now - last < welcomeCooldownMillis) {
            return false;
        }
        lastWelcome.put(player.getUniqueId(), now);
        String subtitle = district == null ? "" : plugin.msg().get("city.welcome-subtitle", "district", district);
        player.sendTitle(plugin.msg().get("city.welcome-title"), subtitle, 10, 50, 15);
        if (!laws.isEmpty()) {
            // A moment after the title, so it clearly follows "Welcome to Lumina".
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (player.isOnline()) {
                    plugin.msg().send(player, "city.laws-hint");
                }
            }, hintDelayTicks);
        }
        return true;
    }

    /** Where a spot is, in words, for /lumina where. */
    public String describe(Location l) {
        District d = districtAt(l);
        if (inCity(l)) {
            return d == null ? plugin.msg().get("city.where-city") : plugin.msg().get("city.where-district", "district", d.name());
        }
        return d == null ? plugin.msg().get("city.where-outside") : plugin.msg().get("city.where-district", "district", d.name());
    }
}
