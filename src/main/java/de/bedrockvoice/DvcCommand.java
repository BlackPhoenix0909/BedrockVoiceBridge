package de.bedrockvoice;

import de.maxhenkel.voicechat.api.Group;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** /dvc link | unlink | groups | join <Gruppe> | leave | invite <Spieler> */
public final class DvcCommand implements TabExecutor {

    private static final List<String> SUBS = List.of("link", "unlink", "groups", "join", "leave", "invite");

    private final BedrockVoiceBridgePlugin plugin;
    private final BridgeManager bridges;
    private final LinkStore links;

    DvcCommand(BedrockVoiceBridgePlugin plugin, BridgeManager bridges, LinkStore links) {
        this.plugin = plugin;
        this.bridges = bridges;
        this.links = links;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player p)) {
            Msg.err(sender, "Nur fuer Spieler.");
            return true;
        }
        if (args.length == 0) { help(p); return true; }

        switch (args[0].toLowerCase()) {
            case "link" -> {
                long minutes = plugin.getConfig().getLong("link-code-minutes", 5);
                String code = links.createCode(p.getUniqueId(), p.getName(), minutes);
                Msg.info(p, "Dein Code: " + code);
                Msg.info(p, "Gib in Discord ein:  /link code:" + code + "   (gueltig " + minutes + " Min.)");
            }
            case "unlink" -> {
                bridges.leave(p.getUniqueId(), true);
                if (links.unlink(p.getUniqueId())) Msg.ok(p, "Discord-Verknuepfung aufgehoben.");
                else Msg.err(p, "Du bist nicht verknuepft.");
            }
            case "groups" -> {
                var groups = bridges.groups();
                if (groups.isEmpty()) {
                    Msg.info(p, "Es gibt gerade keine Voicechat-Gruppen.");
                    return true;
                }
                Msg.info(p, "Voicechat-Gruppen:");
                for (Group g : groups) {
                    String line = " - " + g.getName()
                            + (g.hasPassword() ? " (Passwort)" : "")
                            + (bridges.isBridged(g.getId()) ? " [Discord aktiv]" : "");
                    Msg.info(p, line);
                }
            }
            case "join" -> {
                if (args.length < 2) { Msg.err(p, "/dvc join <Gruppenname>"); return true; }
                bridges.join(p, String.join(" ", Arrays.copyOfRange(args, 1, args.length)));
            }
            case "leave" -> {
                if (!bridges.leave(p.getUniqueId(), true)) Msg.err(p, "Du bist ueber Discord in keiner Gruppe.");
            }
            case "invite" -> {
                if (args.length < 2) { Msg.err(p, "/dvc invite <Spieler>"); return true; }
                Player target = Bukkit.getPlayerExact(args[1]);
                if (target == null) { Msg.err(p, "Spieler nicht gefunden."); return true; }
                bridges.invite(p, target);
            }
            default -> help(p);
        }
        return true;
    }

    private void help(Player p) {
        Msg.info(p, "/dvc link  - Discord-Konto verknuepfen");
        Msg.info(p, "/dvc groups  - Voicechat-Gruppen anzeigen");
        Msg.info(p, "/dvc join <Gruppe>  - Gruppe ueber Discord beitreten");
        Msg.info(p, "/dvc leave  - Gruppe verlassen");
        Msg.info(p, "/dvc invite <Spieler>  - Spieler in deine Gruppe einladen");
        Msg.info(p, "/dvc unlink  - Verknuepfung aufheben");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            for (String s : SUBS) if (s.startsWith(args[0].toLowerCase())) out.add(s);
        } else if (args.length == 2 && args[0].equalsIgnoreCase("join")) {
            for (Group g : bridges.groups())
                if (g.getName().toLowerCase().startsWith(args[1].toLowerCase())) out.add(g.getName());
        } else if (args.length == 2 && args[0].equalsIgnoreCase("invite")) {
            for (Player pl : Bukkit.getOnlinePlayers())
                if (pl.getName().toLowerCase().startsWith(args[1].toLowerCase())) out.add(pl.getName());
        }
        return out;
    }
}
