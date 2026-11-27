package nl.jamie1408g.lumina.property;

import nl.jamie1408g.lumina.LuminaPlugin;
import nl.jamie1408g.lumina.storage.PlayerData;
import nl.jamie1408g.lumina.storage.Storage;
import nl.jamie1408g.lumina.util.Money;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.hanging.HangingPlaceEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

/** Properties (apartments), buying and selling, and optional build protection. */
public final class PropertyManager implements Listener {

    private final LuminaPlugin plugin;
    private final File file;
    private final Map<String, Property> properties = new LinkedHashMap<>();
    private final Map<UUID, Location[]> selections = new HashMap<>();
    private int maxPerPlayer = 2;
    private int sellBackPercent = 75;
    private boolean protection = true;

    public PropertyManager(LuminaPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "properties.yml");
    }

    public void loadSettings() {
        maxPerPlayer = Math.max(1, plugin.getConfig().getInt("property.max-per-player", 2));
        sellBackPercent = Math.max(0, Math.min(100, plugin.getConfig().getInt("property.sell-back-percent", 75)));
        protection = plugin.getConfig().getBoolean("property.protection", true);
    }

    public void load() {
        loadSettings();
        properties.clear();
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection root = yaml.getConfigurationSection("properties");
        if (root == null) {
            return;
        }
        for (String id : root.getKeys(false)) {
            ConfigurationSection s = root.getConfigurationSection(id);
            if (s == null) {
                continue;
            }
            Property property = new Property(id, s.getString("world", "world"),
                    s.getInt("min.x"), s.getInt("min.y"), s.getInt("min.z"),
                    s.getInt("max.x"), s.getInt("max.y"), s.getInt("max.z"),
                    s.getLong("price"), s.getString("district", ""));
            String owner = s.getString("owner");
            if (owner != null && !owner.isEmpty()) {
                try {
                    property.setOwner(UUID.fromString(owner), s.getString("owner-name", "?"));
                    for (String t : s.getStringList("trusted")) {
                        property.trusted().add(UUID.fromString(t));
                    }
                } catch (IllegalArgumentException e) {
                    plugin.getLogger().warning("Property " + id + " has an invalid owner; it is for sale again.");
                    property.setOwner(null, null);
                }
            }
            properties.put(id.toLowerCase(Locale.ROOT), property);
        }
    }

    public void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        for (Property p : properties.values()) {
            String base = "properties." + p.id() + ".";
            yaml.set(base + "world", p.world());
            yaml.set(base + "min.x", p.minX());
            yaml.set(base + "min.y", p.minY());
            yaml.set(base + "min.z", p.minZ());
            yaml.set(base + "max.x", p.maxX());
            yaml.set(base + "max.y", p.maxY());
            yaml.set(base + "max.z", p.maxZ());
            yaml.set(base + "price", p.price());
            yaml.set(base + "district", p.district());
            if (p.owner() != null) {
                yaml.set(base + "owner", p.owner().toString());
                yaml.set(base + "owner-name", p.ownerName());
                List<String> trusted = new ArrayList<>();
                for (UUID uuid : p.trusted()) {
                    trusted.add(uuid.toString());
                }
                yaml.set(base + "trusted", trusted);
            }
        }
        try {
            Storage.writeAtomic(file.toPath(), yaml.saveToString());
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Could not save properties.yml", e);
        }
    }

    // ------------------------------------------------------------ lookups

    public Collection<Property> all() {
        return Collections.unmodifiableCollection(properties.values());
    }

    public Property get(String id) {
        return id == null ? null : properties.get(id.toLowerCase(Locale.ROOT));
    }

    /** The smallest property at a location (so an apartment inside a building wins). */
    public Property at(Location location) {
        Property best = null;
        for (Property p : properties.values()) {
            if (p.contains(location) && (best == null || p.volume() < best.volume())) {
                best = p;
            }
        }
        return best;
    }

    public List<Property> ownedBy(UUID uuid) {
        List<Property> result = new ArrayList<>();
        for (Property p : properties.values()) {
            if (uuid.equals(p.owner())) {
                result.add(p);
            }
        }
        return result;
    }

    public int sellBackPercent() {
        return sellBackPercent;
    }

    // ------------------------------------------------------------ admin

    public void setPos(Player player, int index) {
        Location[] selection = selections.computeIfAbsent(player.getUniqueId(), k -> new Location[2]);
        selection[index] = player.getLocation().getBlock().getLocation();
        Location l = selection[index];
        plugin.msg().send(player, "property.pos-set", "pos", index + 1,
                "x", l.getBlockX(), "y", l.getBlockY(), "z", l.getBlockZ());
    }

    public Property create(Player player, String id, long price, String district) {
        Location[] selection = selections.get(player.getUniqueId());
        if (selection == null || selection[0] == null || selection[1] == null) {
            plugin.msg().send(player, "property.need-selection");
            return null;
        }
        if (selection[0].getWorld() == null || !selection[0].getWorld().equals(selection[1].getWorld())) {
            plugin.msg().send(player, "property.need-selection");
            return null;
        }
        if (get(id) != null) {
            plugin.msg().send(player, "property.exists", "id", id);
            return null;
        }
        Property property = new Property(id, selection[0].getWorld().getName(),
                selection[0].getBlockX(), selection[0].getBlockY(), selection[0].getBlockZ(),
                selection[1].getBlockX(), selection[1].getBlockY(), selection[1].getBlockZ(), price, district);
        properties.put(id.toLowerCase(Locale.ROOT), property);
        save();
        return property;
    }

    public void delete(Property property) {
        properties.remove(property.id().toLowerCase(Locale.ROOT));
        save();
    }

    // ------------------------------------------------------------ player actions

    public void buy(Player player, Property property) {
        if (!property.forSale()) {
            plugin.msg().send(player, "property.not-for-sale", "id", property.id(), "owner", property.ownerName());
            return;
        }
        if (ownedBy(player.getUniqueId()).size() >= maxPerPlayer) {
            plugin.msg().send(player, "property.max-owned", "max", maxPerPlayer);
            return;
        }
        PlayerData data = plugin.data(player);
        if (!plugin.economy().withdraw(player.getUniqueId(), property.price(), "buy property " + property.id())) {
            plugin.msg().send(player, "general.not-enough-money", "amount", Money.format(property.price()),
                    "balance", Money.format(data.balance()));
            return;
        }
        property.setOwner(player.getUniqueId(), player.getName());
        save();
        plugin.storage().markDirty();
        plugin.msg().send(player, "property.bought", "id", property.id(), "price", Money.format(property.price()));
        plugin.gates().playSuccess(player);
    }

    public void sell(Player player, Property property) {
        if (!player.getUniqueId().equals(property.owner())) {
            plugin.msg().send(player, "property.not-yours", "id", property.id());
            return;
        }
        long refund = property.price() * sellBackPercent / 100;
        property.setOwner(null, null);
        save();
        plugin.economy().deposit(player.getUniqueId(), refund, "sell property " + property.id());
        plugin.storage().markDirty();
        plugin.msg().send(player, "property.sold", "id", property.id(), "refund", Money.format(refund),
                "percent", sellBackPercent);
    }

    public void evict(Property property) {
        property.setOwner(null, null);
        save();
    }

    // ------------------------------------------------------------ protection

    /** Whether the player may build/change things at this location. */
    public boolean canBuild(Player player, Location location) {
        if (!protection) {
            return true;
        }
        Property property = at(location);
        if (property == null) {
            return true;
        }
        if (player.hasPermission("lumina.property.bypass") || plugin.jobs().propertyBypass(player)) {
            return true;
        }
        return !property.forSale() && property.isMember(player.getUniqueId());
    }

    private static boolean isEntrance(Material type) {
        return Tag.DOORS.isTagged(type) || Tag.TRAPDOORS.isTagged(type) || Tag.FENCE_GATES.isTagged(type)
                || Tag.BUTTONS.isTagged(type);
    }

    private boolean deny(Player player, Location location) {
        if (canBuild(player, location)) {
            return false;
        }
        Property property = at(location);
        plugin.msg().actionBar(player, property != null && property.forSale() ? "property.protected-for-sale-bar"
                : "property.protected-bar", "id", property == null ? "?" : property.id());
        return true;
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (deny(event.getPlayer(), event.getBlock().getLocation())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (deny(event.getPlayer(), event.getBlock().getLocation())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getClickedBlock() == null) {
            return;
        }
        if (event.useInteractedBlock() == org.bukkit.event.Event.Result.DENY) {
            return;
        }
        Block block = event.getClickedBlock();
        if (!protection || !block.getType().isInteractable()) {
            return;
        }
        Property property = at(block.getLocation());
        // Doors, gates and buttons of empty properties stay usable, so people can look around before buying.
        if (property == null || (property.forSale() && isEntrance(block.getType()))) {
            return;
        }
        if (deny(event.getPlayer(), block.getLocation())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent event) {
        if (deny(event.getPlayer(), event.getBlockClicked().getRelative(event.getBlockFace()).getLocation())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent event) {
        if (deny(event.getPlayer(), event.getBlockClicked().getLocation())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onHangingPlace(HangingPlaceEvent event) {
        if (event.getPlayer() != null && deny(event.getPlayer(), event.getEntity().getLocation())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onHangingBreak(HangingBreakByEntityEvent event) {
        Player player = playerOf(event.getRemover());
        if (player != null && deny(player, event.getEntity().getLocation())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onEntityInteract(PlayerInteractEntityEvent event) {
        Entity entity = event.getRightClicked();
        if (entity instanceof Player) {
            return;
        }
        if (entity instanceof org.bukkit.entity.ItemFrame || entity instanceof org.bukkit.entity.ArmorStand) {
            if (deny(event.getPlayer(), entity.getLocation())) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onArmorStand(PlayerArmorStandManipulateEvent event) {
        if (deny(event.getPlayer(), event.getRightClicked().getLocation())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onEntityDamage(EntityDamageByEntityEvent event) {
        Entity target = event.getEntity();
        if (!(target instanceof org.bukkit.entity.ItemFrame || target instanceof org.bukkit.entity.ArmorStand)) {
            return;
        }
        Player player = playerOf(event.getDamager());
        if (player != null && deny(player, target.getLocation())) {
            event.setCancelled(true);
        }
    }

    private static Player playerOf(Entity entity) {
        if (entity instanceof Player player) {
            return player;
        }
        if (entity instanceof Projectile projectile && projectile.getShooter() instanceof Player shooter) {
            return shooter;
        }
        return null;
    }
}
