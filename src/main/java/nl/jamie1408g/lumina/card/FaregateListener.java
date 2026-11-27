package nl.jamie1408g.lumina.card;

import nl.jamie1408g.lumina.LuminaPlugin;
import nl.jamie1408g.lumina.api.TripCompletedEvent;
import nl.jamie1408g.lumina.network.Station;
import nl.jamie1408g.lumina.storage.CardRecord;
import nl.jamie1408g.lumina.storage.PlayerData;
import nl.jamie1408g.lumina.util.Money;
import nl.jamie1408g.lumina.util.Msg;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Sign;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Openable;
import org.bukkit.block.sign.Side;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.scheduler.BukkitTask;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Faregate, top-up and card-machine signs, and the tap-in/tap-out rules every faregate uses
 * (sign gates here, modern 3D gates in {@link ModelGateManager}).
 *
 * Admins write on a sign:
 *   [Faregate]      / station code or name / in, out or both (empty = both)
 *   [Top Up]        / amount
 *   [Card Machine]
 */
public final class FaregateListener implements Listener {

    private enum SignType { GATE, TOP_UP, MACHINE }

    /** Which way a gate lets people through. */
    public enum Mode { IN, OUT, BOTH }

    /** What a gate does when a tap is accepted or refused. */
    public interface GateHandle {
        void open();

        default void refuse() {
        }
    }

    private static final DateTimeFormatter HISTORY_TIME = DateTimeFormatter.ofPattern("dd-MM HH:mm");

    private final LuminaPlugin plugin;
    private final Map<Location, BukkitTask> closing = new HashMap<>();

    private int openTicks = 50;
    private int radius = 2;
    private long incompleteFare;
    private long graceMillis;
    private long minToEnter;

    public FaregateListener(LuminaPlugin plugin) {
        this.plugin = plugin;
    }

    public void load() {
        FileConfiguration config = plugin.getConfig();
        openTicks = Math.max(10, config.getInt("faregates.open-ticks", 50));
        radius = Math.max(1, Math.min(4, config.getInt("faregates.search-radius", 2)));
        incompleteFare = Money.fromDouble(config.getDouble("fares.incomplete-trip-fare", 3.00));
        graceMillis = Math.max(0, config.getLong("fares.same-station-grace-minutes", 5)) * 60_000L;
        minToEnter = Money.fromDouble(config.getDouble("fares.min-value-to-enter", 0.80));
    }

    private static SignType typeOf(String firstLine) {
        String head = Msg.strip(firstLine).trim().toLowerCase(Locale.ROOT);
        return switch (head) {
            case "[faregate]", "[gate]" -> SignType.GATE;
            case "[top up]", "[topup]", "[top-up]" -> SignType.TOP_UP;
            case "[card machine]", "[lumina card]" -> SignType.MACHINE;
            default -> null;
        };
    }

    // ------------------------------------------------------------ creating signs

    @EventHandler(ignoreCancelled = true)
    public void onSignChange(SignChangeEvent event) {
        SignType type = typeOf(event.getLine(0));
        if (type == null) {
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
        switch (type) {
            case GATE -> {
                Station station = plugin.network().network().findStation(Msg.strip(event.getLine(1)).trim());
                if (station == null) {
                    event.setCancelled(true);
                    plugin.msg().send(player, "signs.unknown-station", "station", Msg.strip(event.getLine(1)));
                    return;
                }
                Mode mode = parseModeInput(Msg.strip(event.getLine(2)));
                boolean hasCodes = !station.codes().isEmpty();
                event.setLine(0, Msg.color("&1[Faregate]"));
                event.setLine(1, Msg.color("&0" + (hasCodes ? station.codeLabel() : station.name())));
                event.setLine(2, Msg.color("&0" + (hasCodes ? station.name() : "")));
                event.setLine(3, plugin.msg().get(switch (mode) {
                    case IN -> "signs.mode-in";
                    case OUT -> "signs.mode-out";
                    case BOTH -> "signs.mode-both";
                }));
                plugin.msg().send(player, "signs.gate-created", "station", station.name());
            }
            case TOP_UP -> {
                long amount = Money.parse(Msg.strip(event.getLine(1)).replace(Money.symbol(), "").trim());
                if (amount <= 0) {
                    event.setCancelled(true);
                    plugin.msg().send(player, "signs.invalid-amount");
                    return;
                }
                event.setLine(0, Msg.color("&1[Top Up]"));
                event.setLine(1, Msg.color("&0" + Money.format(amount)));
                event.setLine(2, plugin.msg().get("signs.topup-line3"));
                event.setLine(3, plugin.msg().get("signs.topup-line4"));
                plugin.msg().send(player, "signs.topup-created", "amount", Money.format(amount));
            }
            case MACHINE -> {
                event.setLine(0, Msg.color("&1[Card Machine]"));
                event.setLine(1, Msg.color("&0Lumina Card"));
                event.setLine(2, Msg.color("&0" + Money.format(plugin.cards().price())));
                event.setLine(3, plugin.msg().get("signs.machine-line4"));
                plugin.msg().send(player, "signs.machine-created");
            }
        }
    }

    private static Mode parseModeInput(String text) {
        String t = text.trim().toLowerCase(Locale.ROOT);
        if (t.equals("in") || t.equals("entry") || t.equals("enter")) {
            return Mode.IN;
        }
        if (t.equals("out") || t.equals("exit")) {
            return Mode.OUT;
        }
        return Mode.BOTH;
    }

    private Mode parseModeSign(String line) {
        String t = Msg.strip(line).trim();
        if (t.equalsIgnoreCase(Msg.strip(plugin.msg().get("signs.mode-in")).trim())) {
            return Mode.IN;
        }
        if (t.equalsIgnoreCase(Msg.strip(plugin.msg().get("signs.mode-out")).trim())) {
            return Mode.OUT;
        }
        return Mode.BOTH;
    }

    private Station parseStation(String line) {
        String text = Msg.strip(line).trim();
        Station station = plugin.network().network().findStation(text);
        if (station == null && text.contains("/")) {
            station = plugin.network().network().findStation(text.substring(0, text.indexOf('/')));
        }
        return station;
    }

    // ------------------------------------------------------------ using signs

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
        SignType type = typeOf(lines[0]);
        if (type == null) {
            return;
        }
        event.setCancelled(true);
        Player player = event.getPlayer();
        switch (type) {
            case GATE -> useSignGate(player, block, lines);
            case TOP_UP -> {
                long amount = Money.parse(Msg.strip(lines[1]).replace(Money.symbol(), "").trim());
                if (amount > 0) {
                    plugin.cards().topUp(player, amount);
                }
            }
            case MACHINE -> plugin.cards().buy(player);
        }
    }

    private void useSignGate(Player player, Block signBlock, String[] lines) {
        Station station = parseStation(lines[1]);
        if (station == null) {
            plugin.msg().send(player, "gate.out-of-service");
            playDeny(player);
            return;
        }
        useGate(player, station, parseModeSign(lines[3]), () -> openGate(signBlock));
    }

    /** A player taps their card at a gate of this station. The gate opens or refuses through the handle. */
    public void useGate(Player player, Station station, Mode mode, GateHandle gate) {
        CardRecord card = plugin.cards().cardInHand(player);
        if (card == null) {
            plugin.msg().actionBar(player, "gate.hold-card-bar");
            plugin.msg().send(player, "card.hold-card");
            deny(player, gate);
            return;
        }
        if (card.blocked()) {
            plugin.msg().send(player, "card.blocked");
            deny(player, gate);
            return;
        }
        boolean inside = card.tappedIn() != null;
        if (mode == Mode.IN || (mode == Mode.BOTH && !inside)) {
            tapIn(player, card, station, gate);
        } else {
            tapOut(player, card, station, gate);
        }
    }

    private void deny(Player player, GateHandle gate) {
        playDeny(player);
        gate.refuse();
    }

    public int openTicks() {
        return openTicks;
    }

    private void tapIn(Player player, CardRecord card, Station station, GateHandle gate) {
        boolean free = plugin.jobs().freeTravel(player);
        long now = System.currentTimeMillis();
        if (card.tappedIn() != null) {
            boolean turnedBack = card.tappedIn().equals(station.key()) && now - card.tapInTime() <= graceMillis;
            if (!turnedBack) {
                long penalty = free ? 0 : incompleteFare;
                if (card.value() < penalty) {
                    plugin.msg().send(player, "gate.penalty-topup", "fare", Money.format(penalty),
                            "value", Money.format(card.value()));
                    deny(player, gate);
                    return;
                }
                card.setValue(card.value() - penalty);
                card.addHistory(time() + " | incomplete trip | -" + Money.plain(penalty));
                plugin.msg().send(player, "gate.incomplete-charged", "fare", Money.format(penalty));
            }
        }
        if (!free && card.value() < minToEnter) {
            plugin.msg().send(player, "gate.insufficient-enter", "min", Money.format(minToEnter),
                    "value", Money.format(card.value()));
            plugin.msg().actionBar(player, "gate.insufficient-bar", "value", Money.format(card.value()));
            deny(player, gate);
            return;
        }
        card.setTappedIn(station.key(), now);
        plugin.storage().markDirty();
        gate.open();
        plugin.msg().actionBar(player, free ? "gate.tap-in-free-bar" : "gate.tap-in-bar",
                "station", station.name(), "value", Money.format(card.value()));
        playSuccess(player);
        visit(player, station);
    }

    private void tapOut(Player player, CardRecord card, Station station, GateHandle gate) {
        if (card.tappedIn() == null) {
            plugin.msg().send(player, "gate.not-tapped-in");
            deny(player, gate);
            return;
        }
        boolean free = plugin.jobs().freeTravel(player);
        Station entry = plugin.network().network().byKey(card.tappedIn());
        int distance = 0;
        long fare;
        if (entry == null) {
            fare = plugin.network().fares().max();
        } else if (entry == station) {
            boolean withinGrace = System.currentTimeMillis() - card.tapInTime() <= graceMillis;
            fare = withinGrace ? 0 : plugin.network().fares().fare(0);
        } else {
            distance = plugin.network().planner().shortestDistance(entry, station);
            fare = distance < 0 ? plugin.network().fares().max() : plugin.network().fares().fare(distance);
            distance = Math.max(0, distance);
        }
        if (free) {
            fare = 0;
        }
        if (card.value() < fare) {
            plugin.msg().send(player, "gate.insufficient-exit", "fare", Money.format(fare),
                    "value", Money.format(card.value()));
            plugin.msg().actionBar(player, "gate.insufficient-bar", "value", Money.format(card.value()));
            deny(player, gate);
            return;
        }
        card.setValue(card.value() - fare);
        card.setTappedIn(null, 0);
        String fromName = entry == null ? "?" : entry.name();
        card.addHistory(time() + " | " + fromName + " > " + station.name() + " | -" + Money.plain(fare));
        plugin.storage().markDirty();
        gate.open();
        plugin.msg().actionBar(player, "gate.tap-out-bar", "fare", Money.format(fare), "value", Money.format(card.value()));
        playSuccess(player);
        visit(player, station);

        if (entry != null && entry != station) {
            PlayerData data = plugin.data(player);
            data.setTrips(data.trips() + 1);
            data.setDistance(data.distance() + distance);
            plugin.quests().onTrip(player, entry, station, distance);
            Bukkit.getPluginManager().callEvent(new TripCompletedEvent(player, entry, station, distance, fare));
        }
    }

    private void visit(Player player, Station station) {
        PlayerData data = plugin.data(player);
        if (data.visitedStations().add(station.key())) {
            plugin.storage().markDirty();
        }
        plugin.quests().onStationVisit(player, station);
    }

    private static String time() {
        return LocalDateTime.now().format(HISTORY_TIME);
    }

    // ------------------------------------------------------------ gates and sounds

    private static boolean isGateBlock(Material type) {
        return Tag.FENCE_GATES.isTagged(type) || Tag.DOORS.isTagged(type) || Tag.TRAPDOORS.isTagged(type);
    }

    /** Opens fence gates, doors and trapdoors near the sign, and closes them again later. */
    private void openGate(Block signBlock) {
        List<Block> opened = new ArrayList<>();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    Block block = signBlock.getRelative(dx, dy, dz);
                    if (!isGateBlock(block.getType())) {
                        continue;
                    }
                    BlockData data = block.getBlockData();
                    if (data instanceof Openable openable) {
                        if (!openable.isOpen()) {
                            openable.setOpen(true);
                            block.setBlockData(openable);
                        }
                        opened.add(block);
                    }
                }
            }
        }
        for (Block block : opened) {
            Location key = block.getLocation();
            BukkitTask previous = closing.remove(key);
            if (previous != null) {
                previous.cancel();
            }
            closing.put(key, Bukkit.getScheduler().runTaskLater(plugin, () -> {
                closing.remove(key);
                close(block);
            }, openTicks));
        }
    }

    private static void close(Block block) {
        BlockData data = block.getBlockData();
        if (isGateBlock(block.getType()) && data instanceof Openable openable && openable.isOpen()) {
            openable.setOpen(false);
            block.setBlockData(openable);
        }
    }

    /** Closes every gate that is still open (used on shutdown). */
    public void closeAll() {
        for (Map.Entry<Location, BukkitTask> entry : closing.entrySet()) {
            entry.getValue().cancel();
            close(entry.getKey().getBlock());
        }
        closing.clear();
    }

    public void playSuccess(Player player) {
        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_CHIME, 0.8f, 1.6f);
    }

    public void playDeny(Player player) {
        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.6f);
    }
}
