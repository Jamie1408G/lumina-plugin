package nl.jamie1408g.lumina.property;

import org.bukkit.Location;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/** An apartment or other property: a box in the world that can be bought with LumiCoins. */
public final class Property {

    private final String id;
    private final String world;
    private final int minX;
    private final int minY;
    private final int minZ;
    private final int maxX;
    private final int maxY;
    private final int maxZ;
    private long price;
    private String district;
    private UUID owner;
    private String ownerName;
    private final Set<UUID> trusted = new LinkedHashSet<>();

    public Property(String id, String world, int x1, int y1, int z1, int x2, int y2, int z2, long price, String district) {
        this.id = id;
        this.world = world;
        this.minX = Math.min(x1, x2);
        this.minY = Math.min(y1, y2);
        this.minZ = Math.min(z1, z2);
        this.maxX = Math.max(x1, x2);
        this.maxY = Math.max(y1, y2);
        this.maxZ = Math.max(z1, z2);
        this.price = price;
        this.district = district == null ? "" : district;
    }

    public boolean contains(Location location) {
        if (location.getWorld() == null || !location.getWorld().getName().equals(world)) {
            return false;
        }
        int x = location.getBlockX();
        int y = location.getBlockY();
        int z = location.getBlockZ();
        return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
    }

    public long volume() {
        return (long) (maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1);
    }

    public String size() {
        return (maxX - minX + 1) + "x" + (maxZ - minZ + 1) + "x" + (maxY - minY + 1);
    }

    public String id() {
        return id;
    }

    public String world() {
        return world;
    }

    public int minX() {
        return minX;
    }

    public int minY() {
        return minY;
    }

    public int minZ() {
        return minZ;
    }

    public int maxX() {
        return maxX;
    }

    public int maxY() {
        return maxY;
    }

    public int maxZ() {
        return maxZ;
    }

    public long price() {
        return price;
    }

    public void setPrice(long price) {
        this.price = price;
    }

    public String district() {
        return district;
    }

    public UUID owner() {
        return owner;
    }

    public String ownerName() {
        return ownerName;
    }

    public void setOwner(UUID owner, String ownerName) {
        this.owner = owner;
        this.ownerName = ownerName;
        if (owner == null) {
            trusted.clear();
        }
    }

    public boolean forSale() {
        return owner == null;
    }

    public Set<UUID> trusted() {
        return trusted;
    }

    public boolean isMember(UUID uuid) {
        return uuid.equals(owner) || trusted.contains(uuid);
    }
}
