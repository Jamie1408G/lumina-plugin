package nl.jamie1408g.lumina.card;

import nl.jamie1408g.lumina.LuminaPlugin;
import nl.jamie1408g.lumina.network.Station;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Modern faregates: a 3D gate built from display entities (see {@link GateModel}), no resource
 * pack needed. While shut, two invisible barrier blocks fill the lane. Players right-click the gate
 * (or its card reader) with their Lumina Card; the same tap-in/tap-out rules as the sign gates apply.
 * Accepted: every light turns green and the flaps retract. Refused: everything turns red.
 */
public final class ModelGateManager implements Listener {

    /** A placed gate. parts are in GateModel.PARTS order. */
    public record Gate(String world, int x, int y, int z, float yaw, String stationKey, FaregateListener.Mode mode,
                       List<UUID> parts, List<UUID> hitboxes, int model) {
        public String id() {
            return world + ":" + x + ":" + y + ":" + z;
        }

        boolean occupies(String w, int bx, int by, int bz) {
            return world.equals(w) && bx == x && bz == z && (by == y || by == y + 1);
        }
    }

    /** Every gate entity carries this tag, so staff can find strays: /kill @e[tag=lumina_gate] */
    public static final String TAG = "lumina_gate";
    private static final int FLAP_TICKS = 6;
    private static final long RED_TICKS = 30;

    private final LuminaPlugin plugin;
    private final File file;
    private final List<Gate> gates = new ArrayList<>();
    private final Map<String, Long> openUntil = new HashMap<>();
    private final Map<String, Long> redUntil = new HashMap<>();

    public ModelGateManager(LuminaPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "gates.yml");
    }

    public List<Gate> all() {
        return Collections.unmodifiableList(gates);
    }

    // ------------------------------------------------------------ storage

    public void load() {
        gates.clear();
        if (!file.exists()) {
            return;
        }
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection section = yml.getConfigurationSection("gates");
        if (section == null) {
            return;
        }
        for (String key : section.getKeys(false)) {
            ConfigurationSection g = section.getConfigurationSection(key);
            if (g == null) {
                continue;
            }
            try {
                FaregateListener.Mode mode = FaregateListener.Mode.valueOf(g.getString("mode", "BOTH").toUpperCase(Locale.ROOT));
                gates.add(new Gate(g.getString("world", "world"), g.getInt("x"), g.getInt("y"), g.getInt("z"),
                        (float) g.getDouble("yaw"), g.getString("station", ""), mode,
                        uuids(g.getStringList("parts")), uuids(g.getStringList("hitboxes")), g.getInt("model", 1)));
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning("Skipping a damaged faregate in gates.yml (" + key + ")");
            }
        }
    }

    public void save() {
        YamlConfiguration yml = new YamlConfiguration();
        for (int i = 0; i < gates.size(); i++) {
            Gate g = gates.get(i);
            String p = "gates." + i + ".";
            yml.set(p + "world", g.world());
            yml.set(p + "x", g.x());
            yml.set(p + "y", g.y());
            yml.set(p + "z", g.z());
            yml.set(p + "yaw", (double) g.yaw());
            yml.set(p + "station", g.stationKey());
            yml.set(p + "mode", g.mode().name());
            yml.set(p + "model", g.model());
            yml.set(p + "parts", strings(g.parts()));
            yml.set(p + "hitboxes", strings(g.hitboxes()));
        }
        try {
            yml.save(file);
        } catch (IOException e) {
            plugin.getLogger().severe("Could not save gates.yml: " + e.getMessage());
        }
    }

    private static List<UUID> uuids(List<String> list) {
        List<UUID> out = new ArrayList<>();
        for (String s : list) {
            out.add(UUID.fromString(s.trim()));
        }
        return out;
    }

    private static List<String> strings(List<UUID> list) {
        List<String> out = new ArrayList<>();
        for (UUID u : list) {
            out.add(u.toString());
        }
        return out;
    }

    // ------------------------------------------------------------ placing and removing

    /** Places a gate in the block the player is facing. Returns null, or a messages.yml key for what's wrong. */
    public String place(Player player, Station station, FaregateListener.Mode mode) {
        Location l = player.getLocation();
        World w = l.getWorld();
        if (w == null) {
            return "modelgate.no-world";
        }
        float yaw = GateModel.snapYaw(l.getYaw());
        int[] f = GateModel.forward(yaw);
        int x = l.getBlockX() + f[0], y = l.getBlockY(), z = l.getBlockZ() + f[1];
        if (at(w.getName(), x, y, z) != null || at(w.getName(), x, y - 1, z) != null) {
            return "modelgate.already-there";
        }
        Block low = w.getBlockAt(x, y, z);
        Block high = w.getBlockAt(x, y + 1, z);
        if (low.getType() != Material.AIR || high.getType() != Material.AIR) {
            return "modelgate.not-empty";
        }
        Gate gate = build(w, x, y, z, yaw, station.key(), mode);
        low.setType(Material.BARRIER, false);
        high.setType(Material.BARRIER, false);
        gates.add(gate);
        save();
        return null;
    }

    private Gate build(World w, int x, int y, int z, float yaw, String stationKey, FaregateListener.Mode mode) {
        Location base = new Location(w, x + 0.5, y, z + 0.5, yaw, 0f);
        List<UUID> ids = new ArrayList<>();
        for (GateModel.Part part : GateModel.PARTS) {
            BlockDisplay d = w.spawn(base, BlockDisplay.class, e -> {
                e.setBlock(Material.valueOf(part.material()).createBlockData());
                e.setTransformation(transform(part));
                if (part.glow()) {
                    e.setBrightness(new Display.Brightness(15, 15));
                }
                e.addScoreboardTag(TAG);
            });
            ids.add(d.getUniqueId());
        }
        List<UUID> hits = new ArrayList<>();
        for (GateModel.Hitbox h : GateModel.HITBOXES) {
            double[] off = GateModel.toWorld(h.x(), h.z(), yaw);
            Location at = new Location(w, x + 0.5 + off[0], y + h.y(), z + 0.5 + off[1]);
            Interaction hit = w.spawn(at, Interaction.class, i -> {
                i.setInteractionWidth(h.width());
                i.setInteractionHeight(h.height());
                i.setResponsive(true);
                i.addScoreboardTag(TAG);
            });
            hits.add(hit.getUniqueId());
        }
        return new Gate(w.getName(), x, y, z, yaw, stationKey, mode, ids, hits, GateModel.VERSION);
    }

    /** Removes a gate: its entities and barrier blocks. */
    public void remove(Gate g) {
        openUntil.remove(g.id());
        redUntil.remove(g.id());
        for (UUID u : g.parts()) {
            kill(u);
        }
        for (UUID u : g.hitboxes()) {
            kill(u);
        }
        World w = Bukkit.getWorld(g.world());
        if (w != null) {
            for (int dy = 0; dy <= 1; dy++) {
                Block b = w.getBlockAt(g.x(), g.y() + dy, g.z());
                if (b.getType() == Material.BARRIER) {
                    b.setType(Material.AIR, false);
                }
            }
        }
        gates.remove(g);
        save();
    }

    /**
     * Rebuilds gates with the current model (after an update). Only gates whose entities are loaded
     * can be rebuilt. Returns {rebuilt, skipped}.
     */
    public int[] rebuildAll() {
        int done = 0, skipped = 0;
        for (Gate g : new ArrayList<>(gates)) {
            World w = Bukkit.getWorld(g.world());
            if (w == null || !loaded(g)) {
                skipped++;
                continue;
            }
            openUntil.remove(g.id());
            for (UUID u : g.parts()) {
                kill(u);
            }
            for (UUID u : g.hitboxes()) {
                kill(u);
            }
            Gate fresh = build(w, g.x(), g.y(), g.z(), g.yaw(), g.stationKey(), g.mode());
            barrier(w, fresh, true);
            gates.set(gates.indexOf(g), fresh);
            done++;
        }
        save();
        return new int[] {done, skipped};
    }

    private static boolean loaded(Gate g) {
        for (UUID u : g.parts()) {
            if (Bukkit.getEntity(u) != null) {
                return true;
            }
        }
        for (UUID u : g.hitboxes()) {
            if (Bukkit.getEntity(u) != null) {
                return true;
            }
        }
        return g.parts().isEmpty() && g.hitboxes().isEmpty();
    }

    public Gate at(String world, int x, int y, int z) {
        for (Gate g : gates) {
            if (g.occupies(world, x, y, z)) {
                return g;
            }
        }
        return null;
    }

    /** Closest gate within radius blocks, or null. */
    public Gate near(Location l, int radius) {
        if (l.getWorld() == null) {
            return null;
        }
        Gate best = null;
        double bestD = Double.MAX_VALUE;
        for (Gate g : gates) {
            if (!g.world().equals(l.getWorld().getName())) {
                continue;
            }
            double dx = g.x() + 0.5 - l.getX(), dy = g.y() - l.getY(), dz = g.z() + 0.5 - l.getZ();
            if (Math.abs(dx) > radius || Math.abs(dy) > radius || Math.abs(dz) > radius) {
                continue;
            }
            double d = dx * dx + dy * dy + dz * dz;
            if (d < bestD) {
                bestD = d;
                best = g;
            }
        }
        return best;
    }

    private Gate byHitbox(UUID id) {
        for (Gate g : gates) {
            if (g.hitboxes().contains(id)) {
                return g;
            }
        }
        return null;
    }

    // ------------------------------------------------------------ tapping

    @EventHandler(priority = EventPriority.LOW)
    public void onEntityClick(PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        Gate g = byHitbox(event.getRightClicked().getUniqueId());
        if (g == null) {
            return;
        }
        event.setCancelled(true);
        tap(event.getPlayer(), g);
    }

    /** Clicking the invisible barrier in the lane counts too. */
    @EventHandler(priority = EventPriority.LOW)
    public void onBlockClick(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        Block b = event.getClickedBlock();
        if (b == null || b.getType() != Material.BARRIER) {
            return;
        }
        Gate g = at(b.getWorld().getName(), b.getX(), b.getY(), b.getZ());
        if (g == null) {
            return;
        }
        event.setCancelled(true);
        tap(event.getPlayer(), g);
    }

    private void tap(Player player, Gate g) {
        Station station = plugin.network().network().byKey(g.stationKey());
        if (station == null) {
            plugin.msg().send(player, "gate.out-of-service");
            plugin.gates().playDeny(player);
            refuse(g);
            return;
        }
        plugin.gates().useGate(player, station, g.mode(), new FaregateListener.GateHandle() {
            @Override
            public void open() {
                ModelGateManager.this.open(g);
            }

            @Override
            public void refuse() {
                ModelGateManager.this.refuse(g);
            }
        });
    }

    // ------------------------------------------------------------ animation

    /** Retracts the flaps, clears the lane, turns the lights green; shuts once the lane is clear. */
    public void open(Gate g) {
        long ticks = plugin.gates().openTicks();
        openUntil.put(g.id(), System.currentTimeMillis() + ticks * 50L);
        redUntil.remove(g.id());
        World w = Bukkit.getWorld(g.world());
        if (w == null) {
            return;
        }
        barrier(w, g, false);
        move(g, true);
        lights(g, GateModel.OK);
        Bukkit.getScheduler().runTaskLater(plugin, () -> tryClose(g), ticks + 1L);
    }

    /** Every light red for a moment; the gate stays shut. */
    public void refuse(Gate g) {
        lights(g, GateModel.NO);
        redUntil.put(g.id(), System.currentTimeMillis() + RED_TICKS * 50L);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Long until = redUntil.get(g.id());
            if (until == null || System.currentTimeMillis() + 25 < until) {
                return; // refused again since
            }
            redUntil.remove(g.id());
            if (!openUntil.containsKey(g.id())) {
                lights(g, null);
            }
        }, RED_TICKS);
    }

    private void tryClose(Gate g) {
        Long until = openUntil.get(g.id());
        if (until == null) {
            return;
        }
        if (System.currentTimeMillis() + 25 < until) {
            return; // tapped again; a later check will close it
        }
        if (someoneInside(g)) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> tryClose(g), 10L);
            return;
        }
        shut(g);
    }

    private void shut(Gate g) {
        openUntil.remove(g.id());
        World w = Bukkit.getWorld(g.world());
        if (w != null) {
            barrier(w, g, true);
        }
        move(g, false);
        lights(g, null);
    }

    /** On shutdown: shut every open gate. */
    public void shutAll() {
        for (Gate g : gates) {
            if (openUntil.containsKey(g.id())) {
                shut(g);
            }
        }
    }

    /** Anyone whose body overlaps the lane (a barrier there would trap them). */
    private static boolean someoneInside(Gate g) {
        double cx = g.x() + 0.5, cz = g.z() + 0.5;
        for (Player p : Bukkit.getOnlinePlayers()) {
            Location l = p.getLocation();
            if (l.getWorld() == null || !l.getWorld().getName().equals(g.world())) {
                continue;
            }
            if (Math.abs(l.getX() - cx) < 0.8 && Math.abs(l.getZ() - cz) < 0.8 && l.getY() > g.y() - 1.8 && l.getY() < g.y() + 2) {
                return true;
            }
        }
        return false;
    }

    private static void barrier(World w, Gate g, boolean shut) {
        for (int dy = 0; dy <= 1; dy++) {
            Block b = w.getBlockAt(g.x(), g.y() + dy, g.z());
            if (shut && b.getType() == Material.AIR) {
                b.setType(Material.BARRIER, false);
            } else if (!shut && b.getType() == Material.BARRIER) {
                b.setType(Material.AIR, false);
            }
        }
    }

    private static void move(Gate g, boolean open) {
        if (g.model() != GateModel.VERSION) {
            return; // old gate: /lumina gate rebuild
        }
        for (int i = 0; i < GateModel.PARTS.size() && i < g.parts().size(); i++) {
            GateModel.Kind k = GateModel.PARTS.get(i).kind();
            if (k != GateModel.Kind.FLAP && k != GateModel.Kind.EDGE) {
                continue;
            }
            if (Bukkit.getEntity(g.parts().get(i)) instanceof BlockDisplay d) {
                d.setInterpolationDelay(0);
                d.setInterpolationDuration(FLAP_TICKS);
                d.setTransformation(transform(GateModel.moved(i, open)));
            }
        }
    }

    /** Sets every light to a colour, or back to its own colour (null). */
    private static void lights(Gate g, String material) {
        if (g.model() != GateModel.VERSION) {
            return;
        }
        for (int i = 0; i < GateModel.PARTS.size() && i < g.parts().size(); i++) {
            GateModel.Part part = GateModel.PARTS.get(i);
            if (part.kind() != GateModel.Kind.LIGHT) {
                continue;
            }
            if (Bukkit.getEntity(g.parts().get(i)) instanceof BlockDisplay d) {
                d.setBlock(Material.valueOf(material == null ? part.material() : material).createBlockData());
            }
        }
    }

    private static Transformation transform(GateModel.Part p) {
        AxisAngle4f tilt = p.tiltDeg() == 0 ? new AxisAngle4f()
                : new AxisAngle4f((float) Math.toRadians(p.tiltDeg()), 1f, 0f, 0f);
        return new Transformation(new Vector3f(p.tx(), p.ty(), p.tz()), tilt, new Vector3f(p.sx(), p.sy(), p.sz()), new AxisAngle4f());
    }

    private static void kill(UUID id) {
        Entity e = Bukkit.getEntity(id);
        if (e != null) {
            e.remove();
        }
    }
}
