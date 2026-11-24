package de.bedrockvoice;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.CommandSender;

/** Kleine Helfer fuer Chatnachrichten. */
final class Msg {
    private static final Component PREFIX = Component.text("[DVC] ", NamedTextColor.DARK_AQUA);

    private Msg() {}

    static Component text(String s, NamedTextColor c) {
        return PREFIX.append(Component.text(s, c));
    }

    static void info(CommandSender s, String m) { s.sendMessage(text(m, NamedTextColor.YELLOW)); }
    static void ok(CommandSender s, String m)   { s.sendMessage(text(m, NamedTextColor.GREEN)); }
    static void err(CommandSender s, String m)  { s.sendMessage(text(m, NamedTextColor.RED)); }
}
