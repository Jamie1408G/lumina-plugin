package nl.jamie1408g.lumina.card;

import nl.jamie1408g.lumina.LuminaPlugin;
import nl.jamie1408g.lumina.storage.CardRecord;
import nl.jamie1408g.lumina.storage.PlayerData;
import nl.jamie1408g.lumina.util.Money;
import nl.jamie1408g.lumina.util.Msg;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/** Lumina Cards: buying, the card item, top-ups and replacements. */
public final class CardManager {

    private final LuminaPlugin plugin;
    private final NamespacedKey cardKey;

    private Material material = Material.PAPER;
    private String name = "&b&lLumina Card";
    private long price;
    private long initialValue;
    private int maxCards;
    private long replaceFee;
    private long maxValue;
    private boolean commandTopUp;

    public CardManager(LuminaPlugin plugin) {
        this.plugin = plugin;
        this.cardKey = new NamespacedKey(plugin, "card_id");
    }

    public void load() {
        FileConfiguration config = plugin.getConfig();
        Material configured = Material.matchMaterial(config.getString("card.material", "PAPER"));
        if (configured == null || !configured.isItem()) {
            plugin.getLogger().warning("card.material is not a valid item, using PAPER.");
            configured = Material.PAPER;
        }
        material = configured;
        name = config.getString("card.name", "&b&lLumina Card");
        price = Money.fromDouble(config.getDouble("card.price", 5.00));
        initialValue = Money.fromDouble(config.getDouble("card.initial-value", 0));
        maxCards = Math.max(1, config.getInt("card.max-cards-per-player", 1));
        replaceFee = Money.fromDouble(config.getDouble("card.replace-fee", 2.00));
        maxValue = Money.fromDouble(config.getDouble("card.max-value", 500));
        commandTopUp = config.getBoolean("card.allow-command-topup", true);
    }

    public long price() {
        return price;
    }

    public boolean commandTopUpAllowed() {
        return commandTopUp;
    }

    // ------------------------------------------------------------ items

    public ItemStack createItem(CardRecord card, String ownerName) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(Msg.color(name));
            List<String> lore = new ArrayList<>();
            lore.add(plugin.msg().get("card.lore-number", "id", card.id()));
            lore.add(plugin.msg().get("card.lore-owner", "owner", ownerName));
            lore.add(plugin.msg().get("card.lore-hint"));
            meta.setLore(lore);
            meta.getPersistentDataContainer().set(cardKey, PersistentDataType.STRING, card.id());
            item.setItemMeta(meta);
        }
        return item;
    }

    /** Card number on an item, or null if it is not a Lumina Card. */
    public String cardId(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return null;
        }
        ItemMeta meta = item.getItemMeta();
        return meta == null ? null : meta.getPersistentDataContainer().get(cardKey, PersistentDataType.STRING);
    }

    /** The card the player holds (main hand first, then off hand), or null. */
    public CardRecord cardInHand(Player player) {
        String id = cardId(player.getInventory().getItemInMainHand());
        if (id == null) {
            id = cardId(player.getInventory().getItemInOffHand());
        }
        return plugin.storage().card(id);
    }

    /** A Lumina Card anywhere in the player's inventory (hands first), or null. */
    public CardRecord cardOnPlayer(Player player) {
        CardRecord held = cardInHand(player);
        if (held != null) {
            return held;
        }
        for (ItemStack item : player.getInventory().getContents()) {
            String id = cardId(item);
            if (id != null) {
                CardRecord card = plugin.storage().card(id);
                if (card != null) {
                    return card;
                }
            }
        }
        return null;
    }

    public List<CardRecord> activeCards(UUID owner) {
        List<CardRecord> result = new ArrayList<>();
        for (CardRecord card : plugin.storage().cardsOf(owner)) {
            if (!card.blocked()) {
                result.add(card);
            }
        }
        return result;
    }

    private String newId() {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        String id;
        do {
            id = String.format("LC-%04d-%04d", random.nextInt(10000), random.nextInt(10000));
        } while (plugin.storage().card(id) != null);
        return id;
    }

    private void give(Player player, ItemStack item) {
        Map<Integer, ItemStack> left = player.getInventory().addItem(item);
        for (ItemStack rest : left.values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), rest);
        }
    }

    // ------------------------------------------------------------ actions

    public void buy(Player player) {
        PlayerData data = plugin.data(player);
        if (activeCards(player.getUniqueId()).size() >= maxCards) {
            plugin.msg().send(player, "card.max-cards", "max", maxCards);
            return;
        }
        if (!plugin.economy().withdraw(player.getUniqueId(), price, "buy Lumina Card")) {
            plugin.msg().send(player, "general.not-enough-money", "amount", Money.format(price),
                    "balance", Money.format(data.balance()));
            return;
        }
        CardRecord card = new CardRecord(newId(), player.getUniqueId());
        card.setValue(initialValue);
        plugin.storage().addCard(card);
        give(player, createItem(card, player.getName()));
        plugin.msg().send(player, "card.bought", "id", card.id(), "price", Money.format(price),
                "value", Money.format(card.value()));
    }

    /** Gives a free card (admin). */
    public CardRecord giveFree(Player player, long value) {
        CardRecord card = new CardRecord(newId(), player.getUniqueId());
        card.setValue(Math.min(maxValue, Math.max(0, value)));
        plugin.storage().addCard(card);
        give(player, createItem(card, player.getName()));
        return card;
    }

    /** Moves LumiCoins from the wallet onto the card in hand. */
    public void topUp(Player player, long amount) {
        CardRecord card = cardInHand(player);
        if (card == null) {
            plugin.msg().send(player, "card.hold-card");
            return;
        }
        if (card.blocked()) {
            plugin.msg().send(player, "card.blocked");
            return;
        }
        if (amount <= 0) {
            plugin.msg().send(player, "general.invalid-amount");
            return;
        }
        if (card.value() + amount > maxValue) {
            plugin.msg().send(player, "card.max-value", "max", Money.format(maxValue));
            return;
        }
        if (!plugin.economy().withdraw(player.getUniqueId(), amount, "top up " + card.id())) {
            plugin.msg().send(player, "general.not-enough-money", "amount", Money.format(amount),
                    "balance", Money.format(plugin.data(player).balance()));
            return;
        }
        card.setValue(card.value() + amount);
        plugin.storage().markDirty();
        plugin.msg().send(player, "card.topped-up", "amount", Money.format(amount), "value", Money.format(card.value()));
        plugin.gates().playSuccess(player);
    }

    /** Blocks the player's old cards and gives a new one with the remaining value. */
    public void replace(Player player) {
        List<CardRecord> old = activeCards(player.getUniqueId());
        if (old.isEmpty()) {
            plugin.msg().send(player, "card.no-card");
            return;
        }
        if (!plugin.economy().withdraw(player.getUniqueId(), replaceFee, "replace Lumina Card")) {
            plugin.msg().send(player, "general.not-enough-money", "amount", Money.format(replaceFee),
                    "balance", Money.format(plugin.data(player).balance()));
            return;
        }
        long value = 0;
        for (CardRecord card : old) {
            value += card.value();
            card.setValue(0);
            card.setBlocked(true);
        }
        CardRecord fresh = new CardRecord(newId(), player.getUniqueId());
        fresh.setValue(Math.min(maxValue, value));
        if (value > maxValue) {
            plugin.economy().deposit(player.getUniqueId(), value - maxValue, "card value above max after replace");
        }
        plugin.storage().addCard(fresh);
        give(player, createItem(fresh, player.getName()));
        plugin.msg().send(player, "card.replaced", "id", fresh.id(), "value", Money.format(fresh.value()),
                "fee", Money.format(replaceFee));
    }
}
