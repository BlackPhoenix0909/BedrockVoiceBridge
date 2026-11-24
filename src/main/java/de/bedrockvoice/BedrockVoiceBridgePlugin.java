package de.bedrockvoice;

import de.maxhenkel.voicechat.api.BukkitVoicechatService;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.List;

public final class BedrockVoiceBridgePlugin extends JavaPlugin implements Listener {

    private LinkStore links;
    private BotManager bots;
    private BridgeManager bridges;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        long guildId = BridgeManager.parseId(getConfig().getString("discord.guild-id"));
        if (guildId == 0) getLogger().severe("discord.guild-id ist nicht gesetzt! Bitte config.yml ausfuellen.");

        links = new LinkStore(new File(getDataFolder(), "links.yml"), getLogger());
        links.load();
        bots = new BotManager(guildId, getLogger());
        bridges = new BridgeManager(this, bots, links);

        BukkitVoicechatService service = getServer().getServicesManager().load(BukkitVoicechatService.class);
        if (service == null) {
            getLogger().severe("Simple Voice Chat wurde nicht gefunden – Plugin wird deaktiviert.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        service.registerPlugin(new VoiceIntegration(bridges));

        DvcCommand cmd = new DvcCommand(this, bridges, links);
        PluginCommand pc = getCommand("dvc");
        if (pc != null) {
            pc.setExecutor(cmd);
            pc.setTabCompleter(cmd);
        }
        getServer().getPluginManager().registerEvents(this, this);
        Bukkit.getScheduler().runTaskTimer(this, bridges::tick, 20L, 10L);

        List<String> tokens = getConfig().getStringList("discord.bot-tokens");
        DiscordListener listener = new DiscordListener(bridges, links, guildId);
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            bots.start(tokens, listener);
            if (bots.isReady()) bridges.cleanupOldChannels();
        });
    }

    @Override
    public void onDisable() {
        if (bridges != null) bridges.shutdown();
        if (bots != null) bots.shutdown();
        if (links != null) links.save();
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        bridges.onQuit(e.getPlayer().getUniqueId());
    }
}
