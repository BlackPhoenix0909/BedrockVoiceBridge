package de.bedrockvoice;

import net.dv8tion.jda.api.events.guild.GuildReadyEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.UUID;

/** Slash-Befehle /link und /unlink auf dem Haupt-Bot. */
public final class DiscordListener extends ListenerAdapter {

    private final BridgeManager bridges;
    private final LinkStore links;
    private final long guildId;

    DiscordListener(BridgeManager bridges, LinkStore links, long guildId) {
        this.bridges = bridges;
        this.links = links;
        this.guildId = guildId;
    }

    @Override
    public void onGuildReady(GuildReadyEvent event) {
        if (event.getGuild().getIdLong() != guildId) return;
        event.getGuild().updateCommands().addCommands(
                Commands.slash("link", "Verknuepft dein Discord-Konto mit Minecraft")
                        .addOption(OptionType.STRING, "code", "Der Code aus /dvc link im Spiel", true),
                Commands.slash("unlink", "Hebt die Verknuepfung mit Minecraft auf")
        ).queue();
    }

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        long userId = event.getUser().getIdLong();
        switch (event.getName()) {
            case "link" -> {
                OptionMapping opt = event.getOption("code");
                LinkStore.PendingCode pc = opt == null ? null : links.redeem(opt.getAsString(), userId);
                if (pc == null) {
                    event.reply("Ungueltiger oder abgelaufener Code. Gib im Spiel `/dvc link` ein, um einen neuen zu bekommen.")
                            .setEphemeral(true).queue();
                    return;
                }
                event.reply("Verknuepft mit **" + pc.playerName() + "**! Jetzt im Spiel `/dvc join <Gruppe>` eingeben.")
                        .setEphemeral(true).queue();
                bridges.sync(() -> {
                    Player p = Bukkit.getPlayer(pc.player());
                    if (p != null) Msg.ok(p, "Discord-Konto " + event.getUser().getName() + " verknuepft!");
                });
            }
            case "unlink" -> {
                UUID player = links.byDiscord(userId);
                if (player == null) {
                    event.reply("Dein Konto ist mit keinem Minecraft-Spieler verknuepft.").setEphemeral(true).queue();
                    return;
                }
                bridges.sync(() -> {
                    bridges.leave(player, true);
                    links.unlink(player);
                });
                event.reply("Verknuepfung aufgehoben.").setEphemeral(true).queue();
            }
            default -> { }
        }
    }
}
