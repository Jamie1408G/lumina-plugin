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

/**
 * Lumina Cards: buying, the card item, top-ups, auto top-up and replacements. Card types: the
 * Lumina Card (stored value), the Auto Card (tops itself up from the wallet when it runs low) and
 * the Lumina Pass (any card of a player with a Monthly Pass, see {@link PassManager}).
 */
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
    private String autoName = "&b&lLumina Auto Card";
    private String passName = "&6&lLumina Pass";
    private List<Long> autoAmounts = List.of(1000L, 2000L);

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
        autoName = config.getString("card.auto-name", "&b&lLumina Auto Card");
        passName = config.getString("card.pass-name", "&6&lLumina Pass");
        List<Long> amounts = new ArrayList<>();
        for (Double d : config.getDoubleList("card.auto-top-up-amounts")) {
            long cents = Money.fromDouble(d);
            if (cents > 0 && !amounts.contains(cents)) {
                amounts.add(cents);
            }
        }
        autoAmounts = amounts.isEmpty() ? List.of(1000L, 2000L) : List.copyOf(amounts);
    }

    /** The auto top-up amounts players can pick (LC 10 and LC 20 by default). */
    public List<Long> autoTopUpAmounts() {
        return autoAmounts;
    }

    public long maxValue() {
        return maxValue;
    }

    public long replaceFee() {
        return replaceFee;
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
            boolean pass = plugin.passes() != null && plugin.passes().validNow(card.owner());
            meta.setDisplayName(Msg.color(pass ? passName : card.autoTopUp() > 0 ? autoName : name));
            List<String> lore = new ArrayList<>();
            lore.add(plugin.msg().get("card.lore-number", "id", card.id()));
            lore.add(plugin.msg().get("card.lore-owner", "owner", ownerName));
            if (pass) {
                lore.add(plugin.msg().get("card.lore-pass", "until", plugin.passes().until(card.owner())));
            }
            if (card.autoTopUp() > 0) {
                lore.add(plugin.msg().get("card.lore-auto", "amount", Money.format(card.autoTopUp())));
            }
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

    /** The player's own card: in hand, else in their inventory, else any active card of theirs. */
    public CardRecord mainCard(Player player) {
        CardRecord card = cardOnPlayer(player);
        if (card != null && !card.blocked() && card.owner().equals(player.getUniqueId())) {
            return card;
        }
        List<CardRecord> cards = activeCards(player.getUniqueId());
        return cards.isEmpty() ? null : cards.get(0);
    }

    /** Updates the name and lore of the player's own card items (after a change of card type). */
    public void refreshItems(Player player) {
        ItemStack[] contents = player.getInventory().getContents();
        for (int i = 0; i < contents.length; i++) {
            String id = cardId(contents[i]);
            CardRecord card = id == null ? null : plugin.storage().card(id);
            if (card != null && card.owner().equals(player.getUniqueId())) {
                player.getInventory().setItem(i, createItem(card, player.getName()));
            }
        }
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
        buy(player, 0);
    }

    /**
     * Buys a card; autoTopUp above 0 makes it an Auto Card. Someone who already has the maximum
     * number of cards switches their card to that type instead, for free.
     */
    public void buy(Player player, long autoTopUp) {
        PlayerData data = plugin.data(player);
        List<CardRecord> owned = activeCards(player.getUniqueId());
        if (owned.size() >= maxCards) {
            CardRecord card = mainCard(player);
            if (card != null && card.autoTopUp() != autoTopUp) {
                setAutoTopUp(player, card, autoTopUp);
                return;
            }
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
        card.setAutoTopUp(autoTopUp);
        plugin.storage().addCard(card);
        give(player, createItem(card, player.getName()));
        plugin.msg().send(player, autoTopUp > 0 ? "card.bought-auto" : "card.bought", "id", card.id(),
                "price", Money.format(price), "value", Money.format(card.value()), "amount", Money.format(autoTopUp));
        plugin.gates().playSuccess(player);
    }

    /** Turns auto top-up on (amount) or off (0) for this card. */
    public void setAutoTopUp(Player player, CardRecord card, long amount) {
        if (card.blocked()) {
            plugin.msg().send(player, "card.blocked");
            return;
        }
        card.setAutoTopUp(amount);
        plugin.storage().markDirty();
        refreshItems(player);
        if (amount > 0) {
            plugin.msg().send(player, "card.auto-on", "amount", Money.format(amount),
                    "low", Money.format(plugin.network().fares().fare(0)));
            afterCharge(player, card); // already low: top it up now
        } else {
            plugin.msg().send(player, "card.auto-off");
        }
        plugin.gates().playSuccess(player);
    }

    /**
     * Auto top-up: a card with less than needed gets its auto top-up amount from the owner's wallet,
     * a few times if one isn't enough. True if the card has enough now (auto top-up or not).
     */
    public boolean ensureValue(Player player, CardRecord card, long needed) {
        if (card.value() >= needed) {
            return true;
        }
        long amount = card.autoTopUp();
        if (amount <= 0 || card.blocked()) {
            return false;
        }
        for (int i = 0; i < 5 && card.value() < needed; i++) {
            if (card.value() + amount > maxValue) {
                plugin.msg().send(player, "card.max-value", "max", Money.format(maxValue));
                break;
            }
            if (!plugin.economy().withdraw(card.owner(), amount, "auto top-up " + card.id())) {
                PlayerData owner = plugin.storage().get(card.owner());
                plugin.msg().send(player, "card.auto-failed", "amount", Money.format(amount),
                        "balance", Money.format(owner == null ? 0 : owner.balance()));
                break;
            }
            card.setValue(card.value() + amount);
            card.addHistory(FaregateListener.time() + " | auto top-up | +" + Money.plain(amount));
            plugin.storage().markDirty();
            plugin.msg().send(player, "card.auto-topped-up", "amount", Money.format(amount),
                    "value", Money.format(card.value()));
        }
        return card.value() >= needed;
    }

    /** After paying a fare: an Auto Card that ran low (below the starting fare) is topped up right away. */
    public void afterCharge(Player player, CardRecord card) {
        if (card.autoTopUp() > 0) {
            ensureValue(player, card, plugin.network().fares().fare(0));
        }
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
        topUp(player, card, amount);
    }

    /** Moves LumiCoins from the wallet onto this card. True if it worked. */
    public boolean topUp(Player player, CardRecord card, long amount) {
        if (card.blocked()) {
            plugin.msg().send(player, "card.blocked");
            return false;
        }
        if (amount <= 0) {
            plugin.msg().send(player, "general.invalid-amount");
            return false;
        }
        if (card.value() + amount > maxValue) {
            plugin.msg().send(player, "card.max-value", "max", Money.format(maxValue));
            return false;
        }
        if (!plugin.economy().withdraw(player.getUniqueId(), amount, "top up " + card.id())) {
            plugin.msg().send(player, "general.not-enough-money", "amount", Money.format(amount),
                    "balance", Money.format(plugin.data(player).balance()));
            return false;
        }
        card.setValue(card.value() + amount);
        plugin.storage().markDirty();
        plugin.msg().send(player, "card.topped-up", "amount", Money.format(amount), "value", Money.format(card.value()));
        plugin.gates().playSuccess(player);
        return true;
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
        long auto = 0;
        for (CardRecord card : old) {
            value += card.value();
            auto = Math.max(auto, card.autoTopUp());
            card.setValue(0);
            card.setBlocked(true);
        }
        CardRecord fresh = new CardRecord(newId(), player.getUniqueId());
        fresh.setValue(Math.min(maxValue, value));
        fresh.setAutoTopUp(auto);
        if (value > maxValue) {
            plugin.economy().deposit(player.getUniqueId(), value - maxValue, "card value above max after replace");
        }
        plugin.storage().addCard(fresh);
        give(player, createItem(fresh, player.getName()));
        plugin.msg().send(player, "card.replaced", "id", fresh.id(), "value", Money.format(fresh.value()),
                "fee", Money.format(replaceFee));
    }
}
