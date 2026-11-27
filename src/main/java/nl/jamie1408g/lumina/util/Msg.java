package nl.jamie1408g.lumina.util;

import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * Messages from messages.yml with &amp; colour codes and {placeholders}.
 * Missing keys fall back to the messages.yml inside the jar.
 */
public final class Msg {

    private final JavaPlugin plugin;
    private YamlConfiguration messages = new YamlConfiguration();
    private YamlConfiguration defaults = new YamlConfiguration();

    public Msg(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void load() {
        File file = new File(plugin.getDataFolder(), "messages.yml");
        if (!file.exists()) {
            plugin.saveResource("messages.yml", false);
        }
        messages = YamlConfiguration.loadConfiguration(file);
        InputStream stream = plugin.getResource("messages.yml");
        if (stream != null) {
            defaults = YamlConfiguration.loadConfiguration(new InputStreamReader(stream, StandardCharsets.UTF_8));
            messages.setDefaults(defaults);
        }
    }

    public static String color(String text) {
        return text == null ? "" : ChatColor.translateAlternateColorCodes('&', text);
    }

    public static String strip(String text) {
        return text == null ? "" : ChatColor.stripColor(text);
    }

    /**
     * Text for a key with placeholders filled in. Placeholders are given as pairs:
     * get("route.header", "from", "Expo Town", "to", "Vivora").
     */
    public String get(String key, Object... placeholders) {
        String text = messages.getString(key);
        if (text == null) {
            text = "&c[missing message: " + key + "]";
        }
        for (int i = 0; i + 1 < placeholders.length; i += 2) {
            text = text.replace("{" + placeholders[i] + "}", String.valueOf(placeholders[i + 1]));
        }
        return color(text);
    }

    /** A list of sections from messages.yml (falls back to the built-in one if the key is missing). */
    public java.util.List<java.util.Map<?, ?>> mapList(String key) {
        java.util.List<java.util.Map<?, ?>> list = messages.getMapList(key);
        if (list.isEmpty() && defaults != null) {
            list = defaults.getMapList(key);
        }
        return list;
    }

    /** Sends a message with the prefix. Use "\n" in messages.yml for several lines. */
    public void send(CommandSender to, String key, Object... placeholders) {
        String prefix = get("prefix");
        for (String line : get(key, placeholders).split("\n")) {
            to.sendMessage(prefix + line);
        }
    }

    /** Sends a message without the prefix (for lists and headers). */
    public void line(CommandSender to, String key, Object... placeholders) {
        for (String line : get(key, placeholders).split("\n")) {
            to.sendMessage(line);
        }
    }

    /** Sends plain text without the prefix. */
    public static void raw(CommandSender to, String text) {
        to.sendMessage(color(text));
    }

    public void actionBar(Player player, String key, Object... placeholders) {
        player.spigot().sendMessage(ChatMessageType.ACTION_BAR, new TextComponent(get(key, placeholders)));
    }
}
