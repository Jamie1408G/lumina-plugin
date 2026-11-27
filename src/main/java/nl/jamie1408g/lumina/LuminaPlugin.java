package nl.jamie1408g.lumina;

import nl.jamie1408g.lumina.card.CardManager;
import nl.jamie1408g.lumina.card.FaregateListener;
import nl.jamie1408g.lumina.card.ModelGateManager;
import nl.jamie1408g.lumina.city.CityManager;
import nl.jamie1408g.lumina.shop.ShopManager;
import nl.jamie1408g.lumina.update.Updater;
import nl.jamie1408g.lumina.command.CardCommand;
import nl.jamie1408g.lumina.command.JobCommand;
import nl.jamie1408g.lumina.command.LawsCommand;
import nl.jamie1408g.lumina.command.LumiCoinsCommand;
import nl.jamie1408g.lumina.command.LuminaCommand;
import nl.jamie1408g.lumina.command.MetroCommand;
import nl.jamie1408g.lumina.command.PropertyCommand;
import nl.jamie1408g.lumina.command.QuestCommand;
import nl.jamie1408g.lumina.command.RouteCommand;
import nl.jamie1408g.lumina.economy.Economy;
import nl.jamie1408g.lumina.jobs.JobManager;
import nl.jamie1408g.lumina.network.NetworkService;
import nl.jamie1408g.lumina.property.PropertyManager;
import nl.jamie1408g.lumina.quests.QuestManager;
import nl.jamie1408g.lumina.storage.PlayerData;
import nl.jamie1408g.lumina.storage.Storage;
import nl.jamie1408g.lumina.util.Money;
import nl.jamie1408g.lumina.util.Msg;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * Lumina: journey planner, LumiCoins, Lumina Card faregates, city jobs, quests and property
 * for Minecraft Lumina.
 */
public final class LuminaPlugin extends JavaPlugin implements Listener {

    private Msg msg;
    private Storage storage;
    private Economy economy;
    private NetworkService network;
    private CardManager cards;
    private FaregateListener gates;
    private ModelGateManager modelGates;
    private JobManager jobs;
    private QuestManager quests;
    private PropertyManager properties;
    private CityManager city;
    private ShopManager shop;
    private Updater updater;
    private BukkitTask autosave;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        Money.setSymbol(getConfig().getString("lumicoins.symbol", "LC"));

        msg = new Msg(this);
        msg.load();

        storage = new Storage(this);
        storage.load();
        economy = new Economy(storage);

        network = new NetworkService(this);
        network.load();

        cards = new CardManager(this);
        cards.load();
        gates = new FaregateListener(this);
        gates.load();
        modelGates = new ModelGateManager(this);
        modelGates.load();
        jobs = new JobManager(this);
        jobs.load();
        quests = new QuestManager(this);
        quests.load();
        properties = new PropertyManager(this);
        properties.load();
        city = new CityManager(this);
        city.load();
        shop = new ShopManager(this);
        shop.load();
        updater = new Updater(this);
        updater.load();

        Bukkit.getPluginManager().registerEvents(this, this);
        Bukkit.getPluginManager().registerEvents(gates, this);
        Bukkit.getPluginManager().registerEvents(modelGates, this);
        Bukkit.getPluginManager().registerEvents(jobs, this);
        Bukkit.getPluginManager().registerEvents(properties, this);
        Bukkit.getPluginManager().registerEvents(city, this);
        Bukkit.getPluginManager().registerEvents(shop, this);
        Bukkit.getPluginManager().registerEvents(updater, this);

        register("lumina", new LuminaCommand(this));
        register("route", new RouteCommand(this));
        register("metro", new MetroCommand(this));
        register("lumicoins", new LumiCoinsCommand(this));
        register("luminacard", new CardCommand(this));
        register("luminajob", new JobCommand(this));
        register("lquests", new QuestCommand(this));
        register("property", new PropertyCommand(this));
        register("lulaws", new LawsCommand(this));

        for (Player player : Bukkit.getOnlinePlayers()) {
            data(player);
        }
        startAutosave();
        getLogger().info("Lumina is running. Welcome to the city!");
    }

    @Override
    public void onDisable() {
        if (autosave != null) {
            autosave.cancel();
        }
        if (gates != null) {
            gates.closeAll();
        }
        if (modelGates != null) {
            modelGates.shutAll();
        }
        if (storage != null) {
            storage.saveNow();
        }
        if (properties != null) {
            properties.save();
        }
    }

    /** Reloads all config files. Player data, cards and properties stay as they are. */
    public void reload() {
        reloadConfig();
        Money.setSymbol(getConfig().getString("lumicoins.symbol", "LC"));
        msg.load();
        network.load();
        cards.load();
        gates.load();
        jobs.load();
        quests.load();
        properties.loadSettings();
        city.load();
        shop.load();
        updater.load();
        startAutosave();
    }

    private void startAutosave() {
        if (autosave != null) {
            autosave.cancel();
        }
        long ticks = Math.max(1, getConfig().getLong("autosave-minutes", 5)) * 60L * 20L;
        autosave = Bukkit.getScheduler().runTaskTimer(this, storage::saveAsync, ticks, ticks);
    }

    private void register(String name, TabExecutor executor) {
        PluginCommand command = getCommand(name);
        if (command == null) {
            getLogger().severe("Command /" + name + " is missing from plugin.yml");
            return;
        }
        command.setExecutor(executor);
        command.setTabCompleter(executor);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event) {
        data(event.getPlayer());
    }

    /** Data of an online player, created on first use. */
    public PlayerData data(Player player) {
        return storage.getOrCreate(player.getUniqueId(), player.getName(),
                Money.fromDouble(getConfig().getDouble("lumicoins.starting-balance", 50)));
    }

    public Msg msg() {
        return msg;
    }

    public Storage storage() {
        return storage;
    }

    public Economy economy() {
        return economy;
    }

    public NetworkService network() {
        return network;
    }

    public CardManager cards() {
        return cards;
    }

    public FaregateListener gates() {
        return gates;
    }

    public ModelGateManager modelGates() {
        return modelGates;
    }

    public Updater updater() {
        return updater;
    }

    public ShopManager shop() {
        return shop;
    }

    public CityManager city() {
        return city;
    }

    public JobManager jobs() {
        return jobs;
    }

    public QuestManager quests() {
        return quests;
    }

    public PropertyManager properties() {
        return properties;
    }
}
