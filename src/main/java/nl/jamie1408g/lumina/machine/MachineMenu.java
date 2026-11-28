package nl.jamie1408g.lumina.machine;

import nl.jamie1408g.lumina.LuminaPlugin;
import nl.jamie1408g.lumina.storage.CardRecord;
import nl.jamie1408g.lumina.storage.PlayerData;
import nl.jamie1408g.lumina.util.Money;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The ticket machine's menu (a chest screen). Main screen: your card and wallet, then Top up,
 * Auto top-up, Buy a card, Monthly Pass and Recent trips. Each opens its own screen. All texts are
 * in messages.yml under machine:.
 */
public final class MachineMenu implements Listener {

    enum Screen { MAIN, TOP_UP, AUTO, BUY, PASS }

    /** Marks our inventories and remembers which screen, machine and buttons they show. */
    static final class View implements InventoryHolder {
        final Screen screen;
        final String machineId;
        final Map<Integer, Runnable> buttons = new HashMap<>();
        Inventory inventory;

        View(Screen screen, String machineId) {
            this.screen = screen;
            this.machineId = machineId;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private static final int SIZE = 27;

    private final LuminaPlugin plugin;
    private List<Long> topUpAmounts = List.of(500L, 1000L, 2000L, 5000L, 10000L);

    public MachineMenu(LuminaPlugin plugin) {
        this.plugin = plugin;
    }

    public void load() {
        FileConfiguration c = plugin.getConfig();
        List<Long> amounts = new ArrayList<>();
        for (Double d : c.getDoubleList("machine.top-up-amounts")) {
            long cents = Money.fromDouble(d);
            if (cents > 0 && !amounts.contains(cents) && amounts.size() < 7) {
                amounts.add(cents);
            }
        }
        topUpAmounts = amounts.isEmpty() ? List.of(500L, 1000L, 2000L, 5000L, 10000L) : List.copyOf(amounts);
    }

    /** Opens the main screen. machineId is the machine that was clicked (null for a sign). */
    public void open(Player player, String machineId) {
        show(player, Screen.MAIN, machineId);
    }

    private void show(Player player, Screen screen, String machineId) {
        View view = new View(screen, machineId);
        String key = "machine.title-" + screen.name().toLowerCase().replace('_', '-');
        if (screen == Screen.MAIN) {
            TicketMachineManager.Machine machine = plugin.machines().byId(machineId);
            key = machine == null ? "machine.title-main" : "machine.title-main-" + machine.type().key();
        }
        view.inventory = Bukkit.createInventory(view, SIZE, plugin.msg().get(key));
        fill(player, view);
        player.openInventory(view.inventory);
    }

    /** Draws the screen again in the same window (after something changed). */
    private void redraw(Player player, View view) {
        view.buttons.clear();
        view.inventory.clear();
        fill(player, view);
    }

    private void fill(Player player, View view) {
        ItemStack pane = item(Material.LIGHT_BLUE_STAINED_GLASS_PANE, " ", List.of(), false);
        for (int i = 0; i < SIZE; i++) {
            view.inventory.setItem(i, pane);
        }
        switch (view.screen) {
            case MAIN -> main(player, view);
            case TOP_UP -> topUp(player, view);
            case AUTO -> auto(player, view);
            case BUY -> buy(player, view);
            case PASS -> pass(player, view);
        }
        if (view.screen != Screen.MAIN) {
            button(view, 18, item(Material.ARROW, plugin.msg().get("machine.back"), List.of(), false),
                    () -> show(player, Screen.MAIN, view.machineId));
        }
        button(view, 26, item(Material.BARRIER, plugin.msg().get("machine.close"), List.of(), false), player::closeInventory);
    }

    // ------------------------------------------------------------ screens

    private void main(Player player, View view) {
        view.inventory.setItem(4, cardInfo(player));
        button(view, 10, item(Material.GOLD_INGOT, plugin.msg().get("machine.top-up"), lore("machine.top-up-lore"), false),
                () -> show(player, Screen.TOP_UP, view.machineId));
        button(view, 12, item(Material.HOPPER, plugin.msg().get("machine.auto"), lore("machine.auto-lore",
                        "amounts", amounts(plugin.cards().autoTopUpAmounts())), false),
                () -> show(player, Screen.AUTO, view.machineId));
        button(view, 14, item(Material.NAME_TAG, plugin.msg().get("machine.buy"), lore("machine.buy-lore"), false),
                () -> show(player, Screen.BUY, view.machineId));
        if (plugin.passes().enabled()) {
            button(view, 16, item(Material.CLOCK, plugin.msg().get("machine.pass"), lore("machine.pass-lore",
                            "price", Money.format(plugin.passes().price()), "days", plugin.passes().days(),
                            "status", plugin.passes().status(player.getUniqueId())), false),
                    () -> show(player, Screen.PASS, view.machineId));
        }
        CardRecord card = plugin.cards().mainCard(player);
        List<String> trips = new ArrayList<>();
        if (card == null || card.history().isEmpty()) {
            trips.add(plugin.msg().get("machine.trips-none"));
        } else {
            List<String> history = card.history();
            for (int i = Math.max(0, history.size() - 6); i < history.size(); i++) {
                trips.add(plugin.msg().get("machine.trips-entry", "entry", history.get(i)));
            }
        }
        view.inventory.setItem(22, item(Material.BOOK, plugin.msg().get("machine.trips"), trips, false));
    }

    private void topUp(Player player, View view) {
        view.inventory.setItem(4, cardInfo(player));
        CardRecord card = plugin.cards().mainCard(player);
        int slot = 10 + Math.max(0, (7 - topUpAmounts.size()) / 2);
        for (long amount : topUpAmounts) {
            List<String> lore = card == null ? lore("machine.need-card")
                    : lore("machine.top-up-amount-lore", "from", Money.format(card.value()),
                    "to", Money.format(card.value() + amount), "wallet", Money.format(plugin.data(player).balance()));
            ItemStack coins = item(Material.SUNFLOWER, plugin.msg().get("machine.top-up-amount", "amount", Money.format(amount)), lore, false);
            coins.setAmount((int) Math.max(1, Math.min(64, amount / 100)));
            button(view, slot++, coins, () -> {
                CardRecord c = plugin.cards().mainCard(player);
                if (c == null) {
                    plugin.msg().send(player, "card.no-card");
                    refused(player, view);
                    return;
                }
                if (plugin.cards().topUp(player, c, amount)) {
                    paid(view);
                } else {
                    refused(player, view);
                }
                redraw(player, view);
            });
        }
    }

    private void auto(Player player, View view) {
        view.inventory.setItem(4, cardInfo(player));
        CardRecord card = plugin.cards().mainCard(player);
        long now = card == null ? -1 : card.autoTopUp();
        List<Long> options = new ArrayList<>();
        options.add(0L);
        options.addAll(plugin.cards().autoTopUpAmounts());
        int slot = 13 - (options.size() - 1);
        for (long amount : options) {
            boolean selected = amount == now;
            String name = amount == 0 ? plugin.msg().get("machine.auto-off")
                    : plugin.msg().get("machine.auto-amount", "amount", Money.format(amount));
            List<String> lore = card == null ? lore("machine.need-card")
                    : amount == 0 ? lore("machine.auto-off-lore")
                    : lore("machine.auto-amount-lore", "amount", Money.format(amount), "low", Money.format(plugin.network().fares().fare(0)));
            if (selected) {
                lore = new ArrayList<>(lore);
                lore.add(plugin.msg().get("machine.selected"));
            }
            button(view, slot, item(amount == 0 ? Material.GRAY_DYE : Material.LIME_DYE, name, lore, selected), () -> {
                CardRecord c = plugin.cards().mainCard(player);
                if (c == null) {
                    plugin.msg().send(player, "card.no-card");
                    refused(player, view);
                    return;
                }
                if (c.autoTopUp() != amount) {
                    plugin.cards().setAutoTopUp(player, c, amount);
                    paid(view);
                }
                redraw(player, view);
            });
            slot += 2;
        }
    }

    private void buy(Player player, View view) {
        view.inventory.setItem(4, cardInfo(player));
        CardRecord card = plugin.cards().mainCard(player);
        String price = Money.format(plugin.cards().price());
        // Regular card
        List<String> regular = lore(card == null ? "machine.card-lore" : "machine.card-have-lore", "price", price);
        button(view, 10, item(Material.PAPER, plugin.msg().get("machine.card", "price", price), regular,
                card != null && card.autoTopUp() == 0), () -> {
            plugin.cards().buy(player, 0);
            paid(view);
            redraw(player, view);
        });
        // Auto Cards
        int slot = 12;
        for (long amount : plugin.cards().autoTopUpAmounts()) {
            if (slot > 14) {
                break;
            }
            List<String> lore = lore(card == null ? "machine.auto-card-lore" : "machine.auto-card-have-lore",
                    "price", price, "amount", Money.format(amount), "low", Money.format(plugin.network().fares().fare(0)));
            button(view, slot, item(Material.PAPER, plugin.msg().get("machine.auto-card", "amount", Money.format(amount), "price", price),
                    lore, card != null && card.autoTopUp() == amount), () -> {
                plugin.cards().buy(player, amount);
                paid(view);
                redraw(player, view);
            });
            slot += 2;
        }
        // Monthly Pass
        if (plugin.passes().enabled()) {
            button(view, 16, item(Material.CLOCK, plugin.msg().get("machine.pass-card", "price", Money.format(plugin.passes().price())),
                    lore("machine.pass-card-lore", "price", Money.format(plugin.passes().price()), "days", plugin.passes().days()),
                    plugin.passes().validNow(player.getUniqueId())), () -> show(player, Screen.PASS, view.machineId));
        }
        // Lost card
        if (card != null) {
            button(view, 22, item(Material.WRITABLE_BOOK, plugin.msg().get("machine.replace"),
                    lore("machine.replace-lore", "fee", Money.format(plugin.cards().replaceFee())), false), () -> {
                plugin.cards().replace(player);
                paid(view);
                redraw(player, view);
            });
        }
    }

    private void pass(Player player, View view) {
        boolean active = plugin.passes().validNow(player.getUniqueId());
        String price = Money.format(plugin.passes().price());
        view.inventory.setItem(4, item(Material.CLOCK, plugin.msg().get("machine.pass"),
                List.of(plugin.passes().status(player.getUniqueId()),
                        plugin.msg().get("machine.wallet", "balance", Money.format(plugin.data(player).balance()))), active));
        if (!active) {
            button(view, 13, item(Material.EMERALD, plugin.msg().get("machine.pass-buy", "price", price),
                    lore("machine.pass-buy-lore", "price", price, "days", plugin.passes().days()), false), () -> {
                plugin.passes().buy(player);
                if (plugin.passes().validNow(player.getUniqueId())) {
                    paid(view);
                } else {
                    refused(player, view);
                }
                redraw(player, view);
            });
            return;
        }
        boolean renews = plugin.passes().renews(player.getUniqueId());
        String until = plugin.passes().until(player.getUniqueId());
        view.inventory.setItem(11, item(Material.EMERALD, plugin.msg().get("machine.pass-active"),
                lore("machine.pass-active-lore", "until", until), true));
        button(view, 15, item(renews ? Material.REPEATER : Material.COMPARATOR,
                plugin.msg().get(renews ? "machine.pass-renew-on" : "machine.pass-renew-off"),
                lore(renews ? "machine.pass-renew-on-lore" : "machine.pass-renew-off-lore", "until", until, "price", price), renews), () -> {
            plugin.passes().setRenew(player, !plugin.passes().renews(player.getUniqueId()));
            redraw(player, view);
        });
    }

    /** Closes every open machine menu (when the plugin stops, so nobody can take the buttons out). */
    public void closeAll() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getOpenInventory().getTopInventory().getHolder() instanceof View) {
                p.closeInventory();
            }
        }
    }

    // ------------------------------------------------------------ clicks

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof View view)) {
            return;
        }
        event.setCancelled(true); // nothing can be taken out or put in
        if (!(event.getWhoClicked() instanceof Player) || event.getRawSlot() < 0 || event.getRawSlot() >= SIZE) {
            return;
        }
        Runnable action = view.buttons.get(event.getRawSlot());
        if (action != null) {
            action.run();
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof View) {
            event.setCancelled(true);
        }
    }

    // ------------------------------------------------------------ helpers

    /** Card number, value, type, pass and wallet, at the top of most screens. */
    private ItemStack cardInfo(Player player) {
        PlayerData data = plugin.data(player);
        CardRecord card = plugin.cards().mainCard(player);
        List<String> lore = new ArrayList<>();
        if (card == null) {
            lore.addAll(lore("machine.info-no-card", "price", Money.format(plugin.cards().price())));
        } else {
            lore.add(plugin.msg().get("machine.info-number", "id", card.id()));
            lore.add(plugin.msg().get("machine.info-value", "value", Money.format(card.value())));
            lore.add(plugin.msg().get("machine.info-auto", "auto", card.autoTopUp() > 0
                    ? plugin.msg().get("card.auto-word-on", "amount", Money.format(card.autoTopUp()))
                    : plugin.msg().get("card.auto-word-off")));
            lore.add(plugin.msg().get("machine.info-pass", "pass", plugin.passes().status(player.getUniqueId())));
        }
        lore.add(plugin.msg().get("machine.wallet", "balance", Money.format(data.balance())));
        return item(Material.PAPER, plugin.msg().get(card == null ? "machine.info-title-none" : "machine.info-title"), lore,
                card != null && plugin.passes().validNow(player.getUniqueId()));
    }

    private void paid(View view) {
        if (view.machineId != null) {
            plugin.machines().flash(view.machineId, true);
        }
    }

    private void refused(Player player, View view) {
        plugin.gates().playDeny(player);
        if (view.machineId != null) {
            plugin.machines().flash(view.machineId, false);
        }
    }

    private static void button(View view, int slot, ItemStack item, Runnable action) {
        view.inventory.setItem(slot, item);
        view.buttons.put(slot, action);
    }

    /** A messages.yml text as lore lines ("\n" starts a new line). */
    private List<String> lore(String key, Object... placeholders) {
        return Arrays.asList(plugin.msg().get(key, placeholders).split("\n"));
    }

    private static String amounts(List<Long> amounts) {
        List<String> out = new ArrayList<>();
        for (long a : amounts) {
            out.add(Money.format(a));
        }
        return String.join(" / ", out);
    }

    private static ItemStack item(Material material, String name, List<String> lore, boolean glow) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            meta.setLore(lore);
            meta.addItemFlags(ItemFlag.values());
            if (glow) {
                meta.setEnchantmentGlintOverride(true);
            }
            item.setItemMeta(meta);
        }
        return item;
    }
}
