package nl.jamie1408g.lumina.command;

import nl.jamie1408g.lumina.LuminaPlugin;
import nl.jamie1408g.lumina.city.LawBook;
import nl.jamie1408g.lumina.city.Laws;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * /lulaws          opens Lumina's law book (nothing is added to your inventory)
 * /lulaws <word>   looks up one offence in chat, e.g. /lulaws griefing
 */
public final class LawsCommand implements TabExecutor {

    private final LuminaPlugin plugin;

    public LawsCommand(LuminaPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        Laws laws = plugin.city().laws();
        if (laws.isEmpty()) {
            plugin.msg().send(sender, "laws.none");
            return true;
        }
        if (args.length > 0) {
            String query = String.join(" ", args);
            List<String[]> hits = laws.search(query);
            if (hits.isEmpty()) {
                plugin.msg().send(sender, "laws.no-match", "query", query, "label", label);
                return true;
            }
            for (String[] h : hits) {
                plugin.msg().send(sender, "laws.match", "offence", h[0], "penalty", h[1], "kind", h[2]);
            }
            return true;
        }
        if (sender instanceof Player player) {
            plugin.city().openLaws(player);
        } else {
            for (String line : LawBook.chat(laws)) {
                sender.sendMessage(line);
            }
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length != 1) {
            return out;
        }
        String k = Laws.normalize(args[0]);
        for (Laws.Part p : plugin.city().laws().parts()) {
            for (Laws.Offence o : p.offences()) {
                String first = o.name().split(" ")[0].toLowerCase(Locale.ROOT);
                if (Laws.normalize(first).startsWith(k) && !out.contains(first)) {
                    out.add(first);
                }
            }
        }
        return out;
    }
}
