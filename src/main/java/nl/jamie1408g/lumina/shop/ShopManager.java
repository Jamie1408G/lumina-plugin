package nl.jamie1408g.lumina.shop;

import nl.jamie1408g.lumina.LuminaPlugin;
import nl.jamie1408g.lumina.city.Laws;
import nl.jamie1408g.lumina.util.Money;
import nl.jamie1408g.lumina.util.Msg;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Sign;
import org.bukkit.block.sign.Side;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Things the city sells for LumiCoins, listed under shop: in config.yml.
 * Staff write a sign:   [Shop] / item id (e.g. lumina-flag)
 * Players right-click it to buy; the price comes out of their wallet.
 */
public final class ShopManager implements Listener {

    /** @param name custom item name, or null */
    public record Item(String key, Material material, int amount, String name, long price) {
        /** "Lumina Flag", "8 Bread". */
        public String label() {
            String base = name != null ? name : pretty(material.name());
            return amount > 1 ? amount + " " + base : base;
        }
    }

    private final LuminaPlugin plugin;
    private List<Item> items = List.of();

    public ShopManager(LuminaPlugin plugin) {
        this.plugin = plugin;
    }

    public void load() {
        List<Item> loaded = new ArrayList<>();
        ConfigurationSection s = plugin.getConfig().getConfigurationSection("shop");
        if (s != null) {
            for (String key : s.getKeys(false)) {
                ConfigurationSection i = s.getConfigurationSection(key);
                if (i == null) {
                    continue;
                }
                String id = i.getString("item", "");
                Material material = Material.matchMaterial(id);
                if (material == null || !material.isItem() || material.isAir()) {
                    plugin.getLogger().warning("Shop item " + key + ": '" + id + "' isn't a Minecraft item id.");
                    continue;
                }
                long price = Money.fromDouble(i.getDouble("price", -1));
                if (price < 0) {
                    plugin.getLogger().warning("Shop item " + key + " has no price.");
                    continue;
                }
                int amount = Math.max(1, Math.min(material.getMaxStackSize(), i.getInt("amount", 1)));
                String name = i.getString("name");
                loaded.add(new Item(key, material, amount, name == null || name.isBlank() ? null : name, price));
            }
        }
        items = Collections.unmodifiableList(loaded);
    }

    public List<Item> items() {
        return items;
    }

    /** By id or by name, ignoring case, spaces and dashes. */
    public Item find(String input) {
        String k = Laws.normalize(input);
        if (k.isEmpty()) {
            return null;
        }
        for (Item i : items) {
            if (Laws.normalize(i.key()).equals(k)) {
                return i;
            }
        }
        for (Item i : items) {
            if (i.name() != null && Laws.normalize(i.name()).equals(k) || Laws.normalize(i.label()).equals(k)) {
                return i;
            }
        }
        return null;
    }

    private static boolean isShop(String firstLine) {
        return Msg.strip(firstLine).trim().equalsIgnoreCase("[shop]");
    }

    @EventHandler(ignoreCancelled = true)
    public void onSignChange(SignChangeEvent event) {
        if (!isShop(event.getLine(0))) {
            return;
        }
        Player player = event.getPlayer();
        if (!player.hasPermission("lumina.admin.signs")) {
            event.setCancelled(true);
            plugin.msg().send(player, "signs.no-permission");
            return;
        }
        if (event.getSide() != Side.FRONT) {
            event.setCancelled(true);
            plugin.msg().send(player, "signs.front-only");
            return;
        }
        Item item = find(Msg.strip(event.getLine(1)));
        if (item == null) {
            event.setCancelled(true);
            List<String> ids = new ArrayList<>();
            for (Item i : items) {
                ids.add(i.key());
            }
            plugin.msg().send(player, "shop.unknown-item", "item", Msg.strip(event.getLine(1)),
                    "items", ids.isEmpty() ? "-" : String.join(", ", ids));
            return;
        }
        event.setLine(0, Msg.color("&2&l[Shop]"));
        event.setLine(1, Msg.color("&0" + item.label()));
        event.setLine(2, Msg.color("&0" + Money.format(item.price())));
        event.setLine(3, Msg.color("&8#" + item.key()));
        plugin.msg().send(player, "shop.sign-created", "item", item.label());
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        Block block = event.getClickedBlock();
        if (block == null || !Tag.ALL_SIGNS.isTagged(block.getType())) {
            return;
        }
        BlockState state = block.getState();
        if (!(state instanceof Sign sign)) {
            return;
        }
        String[] lines = sign.getSide(Side.FRONT).getLines();
        if (!isShop(lines[0])) {
            return;
        }
        event.setCancelled(true);
        String key = Msg.strip(lines[3]).trim();
        buy(event.getPlayer(), find(key.startsWith("#") ? key.substring(1) : key));
    }

    private void buy(Player player, Item item) {
        if (item == null) {
            plugin.msg().send(player, "shop.gone");
            plugin.gates().playDeny(player);
            return;
        }
        long balance = plugin.economy().balance(player.getUniqueId());
        if (balance < item.price()) {
            plugin.msg().send(player, "shop.too-expensive", "item", item.label(), "price", Money.format(item.price()),
                    "balance", Money.format(balance));
            plugin.gates().playDeny(player);
            return;
        }
        if (player.getInventory().firstEmpty() < 0) {
            plugin.msg().send(player, "shop.inventory-full");
            plugin.gates().playDeny(player);
            return;
        }
        if (!plugin.economy().withdraw(player.getUniqueId(), item.price(), "shop " + item.key())) {
            plugin.gates().playDeny(player);
            return;
        }
        ItemStack goods = new ItemStack(item.material(), item.amount());
        if (item.name() != null) {
            ItemMeta meta = goods.getItemMeta();
            if (meta != null) {
                meta.setDisplayName(Msg.color("&f" + item.name()));
                goods.setItemMeta(meta);
            }
        }
        player.getInventory().addItem(goods);
        plugin.storage().markDirty();
        plugin.gates().playSuccess(player);
        plugin.msg().send(player, "shop.bought", "item", item.label(), "price", Money.format(item.price()),
                "balance", Money.format(plugin.economy().balance(player.getUniqueId())));
    }

    /** "LIGHT_BLUE_BANNER" -> "Light Blue Banner". */
    static String pretty(String material) {
        StringBuilder b = new StringBuilder();
        for (String w : material.toLowerCase(Locale.ROOT).split("[_ ]+")) {
            if (w.isEmpty()) {
                continue;
            }
            if (b.length() > 0) {
                b.append(' ');
            }
            b.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
        }
        return b.toString();
    }
}
