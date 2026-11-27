package nl.jamie1408g.lumina;

import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.block.Sign;
import org.bukkit.block.sign.Side;
import org.bukkit.entity.Player;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.plugin.java.JavaPlugin;

public final class LuminaPlugin extends JavaPlugin {
    // Temporary API probe, replaced by the real plugin.
    static void probe(Player p, Sign s, SignChangeEvent e) {
        p.spigot().sendMessage(ChatMessageType.ACTION_BAR, new TextComponent("x"));
        String line = s.getSide(Side.FRONT).getLine(0);
        Side side = e.getSide();
        boolean b = Material.OAK_DOOR.isInteractable();
        p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_CHIME, 1f, 1f);
    }
}
