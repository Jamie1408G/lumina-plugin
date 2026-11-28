package nl.jamie1408g.lumina.machine;

import nl.jamie1408g.lumina.LuminaPlugin;
import org.bukkit.Bukkit;
import org.bukkit.Color;
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
import org.bukkit.entity.TextDisplay;
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
import java.util.Map;
import java.util.UUID;

/**
 * Ticket machines at stations: a 3D kiosk built from display entities (see
 * {@link TicketMachineModel}), stored in machines.yml. Two invisible barrier blocks keep people from
 * walking through it. Right-clicking it opens the ticket machine menu ({@link MachineMenu}).
 */
public final class TicketMachineManager implements Listener {

    /** A placed machine (MRT or LRT). parts are in its type's parts() order, then the header label. */
    public record Machine(String world, int x, int y, int z, float yaw, TicketMachineModel.Type type,
                          List<UUID> parts, List<UUID> hitboxes, int model) {
        public String id() {
            return world + ":" + x + ":" + y + ":" + z;
        }

        boolean occupies(String w, int bx, int by, int bz) {
            return world.equals(w) && bx == x && bz == z && (by == y || by == y + 1);
        }
    }

    /** Every machine entity carries this tag, so staff can find strays: /kill @e[tag=lumina_machine] */
    public static final String TAG = "lumina_machine";
    private static final long FLASH_TICKS = 30;

    private final LuminaPlugin plugin;
    private final File file;
    private final List<Machine> machines = new ArrayList<>();
    private final Map<String, Long> flashUntil = new HashMap<>();

    public TicketMachineManager(LuminaPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "machines.yml");
    }

    public List<Machine> all() {
        return Collections.unmodifiableList(machines);
    }

    // ------------------------------------------------------------ storage

    public void load() {
        machines.clear();
        if (!file.exists()) {
            return;
        }
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection section = yml.getConfigurationSection("machines");
        if (section == null) {
            return;
        }
        for (String key : section.getKeys(false)) {
            ConfigurationSection m = section.getConfigurationSection(key);
            if (m == null) {
                continue;
            }
            try {
                TicketMachineModel.Type type = TicketMachineModel.Type.parse(m.getString("type", "mrt"));
                machines.add(new Machine(m.getString("world", "world"), m.getInt("x"), m.getInt("y"), m.getInt("z"),
                        (float) m.getDouble("yaw"), type == null ? TicketMachineModel.Type.MRT : type,
                        uuids(m.getStringList("parts")), uuids(m.getStringList("hitboxes")), m.getInt("model", 1)));
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning("Skipping a damaged ticket machine in machines.yml (" + key + ")");
            }
        }
    }

    public void save() {
        YamlConfiguration yml = new YamlConfiguration();
        for (int i = 0; i < machines.size(); i++) {
            Machine m = machines.get(i);
            String p = "machines." + i + ".";
            yml.set(p + "world", m.world());
            yml.set(p + "x", m.x());
            yml.set(p + "y", m.y());
            yml.set(p + "z", m.z());
            yml.set(p + "yaw", (double) m.yaw());
            yml.set(p + "type", m.type().key());
            yml.set(p + "model", m.model());
            yml.set(p + "parts", strings(m.parts()));
            yml.set(p + "hitboxes", strings(m.hitboxes()));
        }
        try {
            yml.save(file);
        } catch (IOException e) {
            plugin.getLogger().severe("Could not save machines.yml: " + e.getMessage());
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

    /** Places a machine in the block the player is facing, its front towards them. Null, or a messages.yml key. */
    public String place(Player player, TicketMachineModel.Type type) {
        Location l = player.getLocation();
        World w = l.getWorld();
        if (w == null) {
            return "machine.no-world";
        }
        float yaw = TicketMachineModel.snapYaw(l.getYaw());
        int[] f = TicketMachineModel.forward(yaw);
        int x = l.getBlockX() + f[0], y = l.getBlockY(), z = l.getBlockZ() + f[1];
        if (at(w.getName(), x, y, z) != null || at(w.getName(), x, y - 1, z) != null
                || plugin.modelGates().at(w.getName(), x, y, z) != null
                || plugin.modelGates().at(w.getName(), x, y + 1, z) != null) {
            return "machine.already-there";
        }
        Block low = w.getBlockAt(x, y, z);
        Block high = w.getBlockAt(x, y + 1, z);
        if (low.getType() != Material.AIR || high.getType() != Material.AIR) {
            return "machine.not-empty";
        }
        Machine machine = build(w, x, y, z, yaw, type);
        low.setType(Material.BARRIER, false);
        high.setType(Material.BARRIER, false);
        machines.add(machine);
        save();
        return null;
    }

    private Machine build(World w, int x, int y, int z, float yaw, TicketMachineModel.Type type) {
        Location base = new Location(w, x + 0.5, y, z + 0.5, yaw, 0f);
        List<UUID> ids = new ArrayList<>();
        for (TicketMachineModel.Part part : type.parts()) {
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
        // The name on the header. Text displays face +z, so this one is turned around to face the front.
        double[] off = TicketMachineModel.toWorld(0, TicketMachineModel.LABEL_Z, yaw);
        Location labelAt = new Location(w, x + 0.5 + off[0], y + type.labelY(), z + 0.5 + off[1], yaw + 180f, 0f);
        TextDisplay label = w.spawn(labelAt, TextDisplay.class, t -> {
            t.setText(plugin.msg().get("machine.model-label-" + type.key()));
            t.setBillboard(Display.Billboard.FIXED);
            t.setDefaultBackground(false);
            t.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
            t.setShadowed(false);
            t.setAlignment(TextDisplay.TextAlignment.CENTER);
            t.setBrightness(new Display.Brightness(15, 15));
            float s = type.labelScale();
            t.setTransformation(new Transformation(new Vector3f(), new AxisAngle4f(), new Vector3f(s, s, s), new AxisAngle4f()));
            t.addScoreboardTag(TAG);
        });
        ids.add(label.getUniqueId());
        Interaction hit = w.spawn(new Location(w, x + 0.5, y, z + 0.5), Interaction.class, i -> {
            i.setInteractionWidth(1.0f);
            i.setInteractionHeight(type.height());
            i.setResponsive(true);
            i.addScoreboardTag(TAG);
        });
        return new Machine(w.getName(), x, y, z, yaw, type, ids, List.of(hit.getUniqueId()), TicketMachineModel.VERSION);
    }

    /** Removes a machine: its entities and barrier blocks. */
    public void remove(Machine m) {
        flashUntil.remove(m.id());
        for (UUID u : m.parts()) {
            kill(u);
        }
        for (UUID u : m.hitboxes()) {
            kill(u);
        }
        World w = Bukkit.getWorld(m.world());
        if (w != null) {
            for (int dy = 0; dy <= 1; dy++) {
                Block b = w.getBlockAt(m.x(), m.y() + dy, m.z());
                if (b.getType() == Material.BARRIER) {
                    b.setType(Material.AIR, false);
                }
            }
        }
        machines.remove(m);
        save();
    }

    /** Rebuilds machines with the current model (after an update). Returns {rebuilt, skipped}. */
    public int[] rebuildAll() {
        int done = 0, skipped = 0;
        for (Machine m : new ArrayList<>(machines)) {
            World w = Bukkit.getWorld(m.world());
            if (w == null || !loaded(m)) {
                skipped++;
                continue;
            }
            for (UUID u : m.parts()) {
                kill(u);
            }
            for (UUID u : m.hitboxes()) {
                kill(u);
            }
            Machine fresh = build(w, m.x(), m.y(), m.z(), m.yaw(), m.type());
            for (int dy = 0; dy <= 1; dy++) {
                Block b = w.getBlockAt(m.x(), m.y() + dy, m.z());
                if (b.getType() == Material.AIR) {
                    b.setType(Material.BARRIER, false);
                }
            }
            machines.set(machines.indexOf(m), fresh);
            done++;
        }
        save();
        return new int[] {done, skipped};
    }

    private static boolean loaded(Machine m) {
        for (UUID u : m.parts()) {
            if (Bukkit.getEntity(u) != null) {
                return true;
            }
        }
        for (UUID u : m.hitboxes()) {
            if (Bukkit.getEntity(u) != null) {
                return true;
            }
        }
        return m.parts().isEmpty() && m.hitboxes().isEmpty();
    }

    public Machine at(String world, int x, int y, int z) {
        for (Machine m : machines) {
            if (m.occupies(world, x, y, z)) {
                return m;
            }
        }
        return null;
    }

    public Machine byId(String id) {
        if (id == null) {
            return null;
        }
        for (Machine m : machines) {
            if (m.id().equals(id)) {
                return m;
            }
        }
        return null;
    }

    /** Closest machine within radius blocks, or null. */
    public Machine near(Location l, int radius) {
        if (l.getWorld() == null) {
            return null;
        }
        Machine best = null;
        double bestD = Double.MAX_VALUE;
        for (Machine m : machines) {
            if (!m.world().equals(l.getWorld().getName())) {
                continue;
            }
            double dx = m.x() + 0.5 - l.getX(), dy = m.y() - l.getY(), dz = m.z() + 0.5 - l.getZ();
            if (Math.abs(dx) > radius || Math.abs(dy) > radius || Math.abs(dz) > radius) {
                continue;
            }
            double d = dx * dx + dy * dy + dz * dz;
            if (d < bestD) {
                bestD = d;
                best = m;
            }
        }
        return best;
    }

    // ------------------------------------------------------------ using it

    @EventHandler(priority = EventPriority.LOW)
    public void onEntityClick(PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        UUID id = event.getRightClicked().getUniqueId();
        for (Machine m : machines) {
            if (m.hitboxes().contains(id)) {
                event.setCancelled(true);
                plugin.machineMenu().open(event.getPlayer(), m.id());
                return;
            }
        }
    }

    /** Clicking the invisible barrier counts too. */
    @EventHandler(priority = EventPriority.LOW)
    public void onBlockClick(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        Block b = event.getClickedBlock();
        if (b == null || b.getType() != Material.BARRIER) {
            return;
        }
        Machine m = at(b.getWorld().getName(), b.getX(), b.getY(), b.getZ());
        if (m == null) {
            return;
        }
        event.setCancelled(true);
        plugin.machineMenu().open(event.getPlayer(), m.id());
    }

    /** The reader light and status light flash green (paid) or red (refused) for a moment. */
    public void flash(String machineId, boolean ok) {
        Machine m = byId(machineId);
        if (m == null || m.model() != TicketMachineModel.VERSION) {
            return;
        }
        lights(m, ok ? TicketMachineModel.OK : TicketMachineModel.NO);
        flashUntil.put(m.id(), System.currentTimeMillis() + FLASH_TICKS * 50L);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Long until = flashUntil.get(m.id());
            if (until == null || System.currentTimeMillis() + 25 < until) {
                return; // flashed again since
            }
            flashUntil.remove(m.id());
            lights(m, null);
        }, FLASH_TICKS);
    }

    private static void lights(Machine m, String material) {
        List<TicketMachineModel.Part> parts = m.type().parts();
        for (int i = 0; i < parts.size() && i < m.parts().size(); i++) {
            TicketMachineModel.Part part = parts.get(i);
            if (part.kind() != TicketMachineModel.Kind.LIGHT || part.name().contains("strip")) {
                continue;
            }
            if (Bukkit.getEntity(m.parts().get(i)) instanceof BlockDisplay d) {
                d.setBlock(Material.valueOf(material == null ? part.material() : material).createBlockData());
            }
        }
    }

    private static Transformation transform(TicketMachineModel.Part p) {
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
