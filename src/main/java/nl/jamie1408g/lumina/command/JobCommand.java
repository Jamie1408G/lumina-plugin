package nl.jamie1408g.lumina.command;

import nl.jamie1408g.lumina.LuminaPlugin;
import nl.jamie1408g.lumina.jobs.JobManager;
import nl.jamie1408g.lumina.storage.PlayerData;
import nl.jamie1408g.lumina.util.Money;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/** /luminajob list | join <job> | leave | duty [on|off] | who | call <job> <message> | info */
public final class JobCommand implements TabExecutor {

    private final LuminaPlugin plugin;

    public JobCommand(LuminaPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        JobManager jobs = plugin.jobs();
        String sub = args.length == 0 ? "info" : args[0].toLowerCase();
        if (sub.equals("list")) {
            plugin.msg().line(sender, "jobs.list-header");
            for (JobManager.Role role : jobs.roles()) {
                String slots = role.maxPlayers() > 0 ? jobs.members(role.id()) + "/" + role.maxPlayers()
                        : String.valueOf(jobs.members(role.id()));
                plugin.msg().line(sender, "jobs.list-entry", "id", role.id(), "job", role.name(),
                        "salary", Money.format(role.salary()), "period", jobs.payPeriod(), "slots", slots,
                        "description", role.description());
            }
            plugin.msg().line(sender, "jobs.list-hint", "label", label);
            return true;
        }
        if (sub.equals("who")) {
            plugin.msg().line(sender, "jobs.who-header");
            for (JobManager.Role role : jobs.roles()) {
                List<String> names = new ArrayList<>();
                for (Player p : jobs.onDuty(role.id())) {
                    names.add(p.getName());
                }
                plugin.msg().line(sender, "jobs.who-entry", "job", role.name(),
                        "players", names.isEmpty() ? plugin.msg().get("jobs.who-nobody") : String.join(", ", names));
            }
            return true;
        }
        if (!(sender instanceof Player player)) {
            plugin.msg().send(sender, "general.players-only");
            return true;
        }
        switch (sub) {
            case "join" -> {
                if (args.length < 2) {
                    plugin.msg().send(player, "jobs.join-usage", "label", label);
                    return true;
                }
                jobs.join(player, args[1]);
            }
            case "leave", "quit" -> jobs.leave(player);
            case "duty", "available", "unavailable" -> {
                Boolean wanted = null;
                if (sub.equals("available")) {
                    wanted = true;
                } else if (sub.equals("unavailable")) {
                    wanted = false;
                } else if (args.length >= 2) {
                    wanted = args[1].equalsIgnoreCase("on");
                }
                jobs.setDuty(player, wanted);
            }
            case "call" -> {
                if (args.length < 3) {
                    plugin.msg().send(player, "jobs.call-usage", "label", label);
                    return true;
                }
                jobs.call(player, args[1], Cmd.join(args, 2));
            }
            case "info" -> {
                PlayerData data = plugin.data(player);
                JobManager.Role role = jobs.roleOf(data);
                if (role == null) {
                    plugin.msg().send(player, "jobs.no-job-hint", "label", label);
                    return true;
                }
                String status = !data.onDuty() ? plugin.msg().get("jobs.status-off")
                        : jobs.isAfk(player) ? plugin.msg().get("jobs.status-afk") : plugin.msg().get("jobs.status-on");
                plugin.msg().line(player, "jobs.info", "job", role.name(), "status", status,
                        "salary", Money.format(role.salary()), "period", jobs.payPeriod(),
                        "progress", data.periodMinutes(), "total", data.workMinutes(), "label", label);
            }
            default -> plugin.msg().send(player, "jobs.usage", "label", label);
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return Cmd.filter(args[0], "list", "join", "leave", "duty", "who", "call", "info");
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("join") || args[0].equalsIgnoreCase("call"))) {
            List<String> ids = new ArrayList<>();
            for (JobManager.Role role : plugin.jobs().roles()) {
                ids.add(role.id());
            }
            return Cmd.filter(ids, args[1]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("duty")) {
            return Cmd.filter(args[1], "on", "off");
        }
        return List.of();
    }
}
