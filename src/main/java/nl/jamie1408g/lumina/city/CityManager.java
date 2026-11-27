package nl.jamie1408g.lumina.city;

import nl.jamie1408g.lumina.LuminaPlugin;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
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
import org.bukkit.scheduler.BukkitRunnable;

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
 * Lumina's city border (a shape of six points, see {@link CityBorder}) and districts (boxes on the
 * map), both any height and stored in areas.yml, and its
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
    private CityBorder city;
    private final Map<UUID, BukkitRunnable> previews = new HashMap<>();
    private final List<District> districts = new ArrayList<>();
    private final Map<UUID, Spot> spots = new HashMap<>();
    private final Map<UUID, Long> lastWelcome = new HashMap<>();
    private final Walkthrough walkthrough;
    private Laws laws = Laws.NONE;
    private List<String> lawPages = List.of();
    private boolean enabled = true;
    private long welcomeCooldownMillis = 60_000L;
    private long hintDelayTicks = 40L;

    public CityManager(LuminaPlugin plugin) {
        this.plugin = plugin;
        this.areasFile = new File(plugin.getDataFolder(), "areas.yml");
        this.walkthrough = new Walkthrough(plugin);
    }

    public Walkthrough walkthrough() {
        return walkthrough;
    }

    // ------------------------------------------------------------ loading

    public void load() {
        FileConfiguration config = plugin.getConfig();
        enabled = config.getBoolean("city.enabled", true);
        welcomeCooldownMillis = Math.max(0, config.getLong("city.welcome-cooldown-seconds", 60)) * 1000L;
        hintDelayTicks = Math.max(0, config.getLong("city.laws-hint-delay-ticks", 40));
        loadAreas();
        loadLaws();
        walkthrough.load();
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
        city = border(yml.getConfigurationSection("city"));
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

    /** The city border: a list of points, or the old two-corner rectangle (min-x ... max-z). */
    private CityBorder border(ConfigurationSection s) {
        if (s == null || !s.contains("world")) {
            return null;
        }
        String world = s.getString("world");
        if (!s.isList("points")) {
            Box b = box(s);
            return b == null ? null : CityBorder.rectangle(world, b.minX(), b.minZ(), b.maxX(), b.maxZ());
        }
        List<CityBorder.Point> points = new ArrayList<>();
        for (String text : s.getStringList("points")) {
            CityBorder.Point p = CityBorder.Point.parse(text);
            if (p == null) {
                plugin.getLogger().warning("areas.yml: city point '" + text + "' isn't \"x z\", skipped.");
            } else {
                points.add(p);
            }
        }
        if (points.size() < 3) {
            plugin.getLogger().warning("areas.yml: the city border needs at least 3 points, so Lumina has no border now.");
            return null;
        }
        CityBorder border = new CityBorder(world, points);
        if (border.problem() != null) {
            plugin.getLogger().warning("areas.yml: the city border crosses itself or has no area (" + border.problem()
                    + "). Set it again with /lumina pos1 to pos6 and /lumina city set.");
        }
        return border;
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
            yml.set("city.world", city.world());
            yml.set("city.points", city.pointTexts());
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

    public CityBorder city() {
        return city;
    }

    public List<District> districts() {
        return Collections.unmodifiableList(districts);
    }

    public void setCity(CityBorder border) {
        city = border;
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
        CityBorder c = city;
        return l.getWorld() != null && c != null && c.contains(l.getWorld().getName(), l.getBlockX(), l.getBlockZ());
    }

    // ------------------------------------------------------------ showing the border

    public static final int PREVIEW_SECONDS = 20;
    public static final int PREVIEW_RADIUS = 48;
    private static final Particle.DustOptions EDGE = new Particle.DustOptions(Color.fromRGB(0x55, 0xFF, 0xFF), 1.5f);

    /**
     * Shows the border to this player only, as particles at their height, for PREVIEW_SECONDS:
     * a dotted line along the edges within PREVIEW_RADIUS blocks, and a column at each point.
     */
    public void showBorder(Player player) {
        CityBorder border = city;
        stopPreview(player.getUniqueId());
        if (border == null) {
            return;
        }
        UUID id = player.getUniqueId();
        BukkitRunnable task = new BukkitRunnable() {
            private int runs;

            @Override
            public void run() {
                if (!player.isOnline() || runs++ >= PREVIEW_SECONDS * 2
                        || !player.getWorld().getName().equals(border.world())) {
                    cancel();
                    previews.remove(id, this);
                    return;
                }
                drawBorder(player, border);
            }
        };
        previews.put(id, task);
        task.runTaskTimer(plugin, 0L, 10L);
    }

    private void stopPreview(UUID id) {
        BukkitRunnable old = previews.remove(id);
        if (old != null) {
            try {
                old.cancel();
            } catch (IllegalStateException ignored) {
                // never got scheduled
            }
        }
    }

    private static void drawBorder(Player player, CityBorder border) {
        Location l = player.getLocation();
        double px = l.getX();
        double pz = l.getZ();
        double y = l.getY() + 1.0;
        double r = PREVIEW_RADIUS;
        List<CityBorder.Point> points = border.points();
        int n = points.size();
        for (int i = 0; i < n; i++) {
            CityBorder.Point a = points.get(i);
            CityBorder.Point b = points.get((i + 1) % n);
            double ax = a.x() + 0.5;
            double az = a.z() + 0.5;
            double dx = b.x() - a.x();
            double dz = b.z() - a.z();
            double length = Math.hypot(dx, dz);
            // The point's column, if it's near.
            if (Math.hypot(ax - px, az - pz) <= r) {
                for (int h = 0; h < 10; h++) {
                    player.spawnParticle(Particle.END_ROD, ax, y - 1 + h * 0.5, az, 1, 0, 0, 0, 0);
                }
            }
            if (length == 0) {
                continue;
            }
            // Only the part of this line within the radius: one dot per block.
            double t = ((px - ax) * dx + (pz - az) * dz) / (length * length);
            double side = Math.abs((px - ax) * dz - (pz - az) * dx) / length;
            if (side > r) {
                continue;
            }
            double half = Math.sqrt(r * r - side * side) / length;
            double from = Math.max(0, t - half);
            double to = Math.min(1, t + half);
            double step = 1.0 / length;
            for (double s = from; s <= to; s += step) {
                player.spawnParticle(Particle.DUST, ax + dx * s, y, az + dz * s, 1, 0, 0, 0, 0, EDGE);
            }
        }
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
        Player player = event.getPlayer();
        update(player, player.getLocation(), false);
        // Spawned in Lumina and never had the tour: start it once they've had a moment to load in.
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline() && enabled && inCity(player.getLocation()) && walkthrough.due(player)) {
                walkthrough.start(player);
            }
        }, 60L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        spots.remove(event.getPlayer().getUniqueId());
        lastWelcome.remove(event.getPlayer().getUniqueId());
        walkthrough.stop(event.getPlayer());
        stopPreview(event.getPlayer().getUniqueId());
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
        if (entered && walkthrough.due(player)) {
            // First time in Lumina: the walkthrough is the welcome (it starts with it and mentions /lulaws).
            lastWelcome.put(player.getUniqueId(), System.currentTimeMillis());
            walkthrough.start(player);
            return;
        }
        if (entered && walkthrough.isRunning(player)) {
            return; // don't put the welcome title over the walkthrough
        }
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
