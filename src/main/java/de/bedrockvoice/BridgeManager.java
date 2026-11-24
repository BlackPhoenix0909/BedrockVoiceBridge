package de.bedrockvoice;

import de.maxhenkel.voicechat.api.Group;
import de.maxhenkel.voicechat.api.VoicechatConnection;
import de.maxhenkel.voicechat.api.VoicechatServerApi;
import de.maxhenkel.voicechat.api.events.MicrophonePacketEvent;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.GuildVoiceState;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.concrete.Category;
import net.dv8tion.jda.api.entities.channel.concrete.VoiceChannel;
import net.dv8tion.jda.api.managers.AudioManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** Verwaltet alle Gruppen-Bruecken und die Teilnehmer, die ueber Discord dabei sind. */
public final class BridgeManager {

    private static final EnumSet<Permission> VOICE_PERMS =
            EnumSet.of(Permission.VIEW_CHANNEL, Permission.VOICE_CONNECT, Permission.VOICE_SPEAK);
    private static final EnumSet<Permission> NONE = EnumSet.noneOf(Permission.class);

    private record Invite(UUID groupId, long expiresAt) {}

    private final BedrockVoiceBridgePlugin plugin;
    private final BotManager bots;
    private final LinkStore links;

    private final Map<UUID, GroupBridge> bridges = new ConcurrentHashMap<>();             // groupId -> Bruecke
    private final Map<UUID, CompletableFuture<GroupBridge>> pending = new ConcurrentHashMap<>();
    private final Map<UUID, GroupBridge> byPlayer = new ConcurrentHashMap<>();            // Spieler -> Bruecke
    private final Map<UUID, Invite> invites = new ConcurrentHashMap<>();

    volatile VoicechatServerApi api;

    BridgeManager(BedrockVoiceBridgePlugin plugin, BotManager bots, LinkStore links) {
        this.plugin = plugin;
        this.bots = bots;
        this.links = links;
    }

    // =====================================================================
    //  Befehle (Main-Thread)
    // =====================================================================

    Collection<Group> groups() {
        VoicechatServerApi a = api;
        return a == null ? List.of() : a.getGroups();
    }

    boolean isBridged(UUID groupId) {
        return bridges.containsKey(groupId);
    }

    GroupBridge bridgeOf(UUID player) {
        return byPlayer.get(player);
    }

    void join(Player p, String groupName) {
        VoicechatServerApi a = api;
        if (a == null) { Msg.err(p, "Simple Voice Chat ist noch nicht gestartet."); return; }
        if (!bots.isReady()) { Msg.err(p, "Der Discord-Bot ist nicht verbunden. Bitte einen Admin informieren."); return; }

        Long discordId = links.get(p.getUniqueId());
        if (discordId == null) {
            Msg.err(p, "Du musst zuerst dein Discord-Konto verknuepfen: /dvc link");
            return;
        }

        Group group = null;
        for (Group g : a.getGroups()) {
            if (g.getName().equalsIgnoreCase(groupName)) { group = g; break; }
        }
        if (group == null) {
            Msg.err(p, "Gruppe \"" + groupName + "\" gibt es nicht. Liste: /dvc groups");
            return;
        }

        if (group.hasPassword() && !p.hasPermission("bedrockvoice.bypasspassword")) {
            Invite inv = invites.get(p.getUniqueId());
            if (inv == null || !inv.groupId().equals(group.getId()) || inv.expiresAt() < System.currentTimeMillis()) {
                Msg.err(p, "Diese Gruppe hat ein Passwort. Ein Mitglied muss dich einladen: /dvc invite " + p.getName());
                return;
            }
            invites.remove(p.getUniqueId());
        }

        GroupBridge current = byPlayer.get(p.getUniqueId());
        if (current != null) {
            if (current.groupId.equals(group.getId())) { Msg.info(p, "Du bist bereits in dieser Gruppe."); return; }
            leave(p.getUniqueId(), false);
        }

        GroupBridge existing = bridges.get(group.getId());
        if (existing != null) {
            addParticipant(existing, p, discordId);
            return;
        }

        Msg.info(p, "Erstelle Discord-Sprachkanal fuer \"" + group.getName() + "\" ...");
        final Group g = group;
        CompletableFuture<GroupBridge> future = pending.computeIfAbsent(g.getId(), k -> createBridge(a, g));
        UUID uuid = p.getUniqueId();
        future.whenComplete((bridge, err) -> sync(() -> {
            pending.remove(g.getId(), future);
            if (err != null) {
                Throwable cause = err.getCause() != null ? err.getCause() : err;
                Player online = Bukkit.getPlayer(uuid);
                if (online != null) Msg.err(online, "Discord-Kanal konnte nicht erstellt werden: " + cause.getMessage());
                return;
            }
            bridges.putIfAbsent(g.getId(), bridge);
            bridge.updateTargets();
            Player online = Bukkit.getPlayer(uuid);
            if (online != null) {
                addParticipant(bridge, online, discordId);
            } else if (bridge.participants.isEmpty()) {
                teardown(bridge, false);
            }
        }));
    }

    /** @return true, wenn der Spieler in einer Bruecke war */
    boolean leave(UUID uuid, boolean notify) {
        GroupBridge b = byPlayer.remove(uuid);
        if (b == null) return false;
        b.participants.remove(uuid);

        Long discordId = links.get(uuid);
        Guild guild = bots.mainGuild();
        if (discordId != null && guild != null) {
            VoiceChannel vc = guild.getVoiceChannelById(b.channelId);
            if (vc != null) vc.getManager().removePermissionOverride(discordId).queue(null, e -> {});
            Member m = guild.getMemberById(discordId);
            if (m != null) {
                GuildVoiceState vs = m.getVoiceState();
                if (vs != null && vs.getChannel() != null && vs.getChannel().getIdLong() == b.channelId) {
                    guild.kickVoiceMember(m).queue(null, e -> {});
                }
            }
        }

        Player p = Bukkit.getPlayer(uuid);
        if (notify && p != null) Msg.info(p, "Du hast die Gruppe \"" + b.groupName + "\" verlassen.");
        if (p != null) notifyGroup(b, Msg.text(p.getName() + " hat die Gruppe (Discord) verlassen.", NamedTextColor.GRAY));

        if (b.participants.isEmpty()) teardown(b, false);
        return true;
    }

    void invite(Player inviter, Player target) {
        VoicechatServerApi a = api;
        if (a == null) return;
        VoicechatConnection c = a.getConnectionOf(inviter.getUniqueId());
        Group g = c == null ? null : c.getGroup();
        if (g == null) {
            GroupBridge b = byPlayer.get(inviter.getUniqueId());
            g = b == null ? null : a.getGroup(b.groupId);
        }
        if (g == null) { Msg.err(inviter, "Du bist in keiner Voicechat-Gruppe."); return; }

        long minutes = plugin.getConfig().getLong("invite-minutes", 5);
        invites.put(target.getUniqueId(), new Invite(g.getId(), System.currentTimeMillis() + minutes * 60_000L));
        Msg.ok(inviter, target.getName() + " wurde in \"" + g.getName() + "\" eingeladen.");
        Msg.info(target, inviter.getName() + " hat dich in die Voicechat-Gruppe \"" + g.getName()
                + "\" eingeladen. Beitreten mit: /dvc join " + g.getName());
    }

    // =====================================================================
    //  Bruecke erstellen / abbauen
    // =====================================================================

    private CompletableFuture<GroupBridge> createBridge(VoicechatServerApi a, Group g) {
        BotManager.BotSlot slot = bots.acquire();
        if (slot == null) {
            return CompletableFuture.failedFuture(new IllegalStateException(
                    "Alle " + bots.size() + " Discord-Bots sind belegt (ein Bot pro Gruppe). Mehr Bot-Tokens in der config.yml eintragen."));
        }
        Guild guild = bots.mainGuild();
        if (guild == null) {
            bots.release(slot);
            return CompletableFuture.failedFuture(new IllegalStateException("Discord-Server nicht gefunden (guild-id pruefen)."));
        }

        long categoryId = parseId(plugin.getConfig().getString("discord.category-id", "0"));
        Category category = categoryId == 0 ? null : guild.getCategoryById(categoryId);
        String name = plugin.getConfig().getString("discord.channel-prefix", "MC-Gruppe: ") + g.getName();
        double toMc = plugin.getConfig().getDouble("audio.discord-to-minecraft-volume", 1.0);
        double toDc = plugin.getConfig().getDouble("audio.minecraft-to-discord-volume", 1.0);

        AtomicLong createdId = new AtomicLong();
        return guild.createVoiceChannel(name, category)
                // Kanal ist privat: nur der Audio-Bot und freigeschaltete Spieler duerfen rein
                .addPermissionOverride(guild.getPublicRole(), NONE, EnumSet.of(Permission.VIEW_CHANNEL, Permission.VOICE_CONNECT))
                .addMemberPermissionOverride(slot.selfId(), VOICE_PERMS, NONE)
                .submit()
                .thenCompose(vc -> {
                    createdId.set(vc.getIdLong());
                    return waitForChannel(slot, vc.getIdLong(), 30);
                })
                .thenApply(vc -> {
                    GroupBridge bridge = new GroupBridge(a, g.getId(), g.getName(), slot, vc.getIdLong(), toMc, toDc);
                    AudioManager am = vc.getGuild().getAudioManager();
                    am.setSendingHandler(bridge);
                    am.setReceivingHandler(bridge);
                    am.setSelfDeafened(false);
                    am.setSelfMuted(false);
                    am.openAudioConnection(vc);
                    bots.assign(slot, bridge);
                    return bridge;
                })
                .whenComplete((bridge, err) -> {
                    if (err == null) return;
                    bots.release(slot);
                    long id = createdId.get();
                    if (id != 0) {
                        VoiceChannel vc = guild.getVoiceChannelById(id);
                        if (vc != null) vc.delete().queue(null, e -> {});
                    }
                });
    }

    /** Der Audio-Bot erfaehrt vom neuen Kanal erst kurz nach dem Erstellen. */
    private CompletableFuture<VoiceChannel> waitForChannel(BotManager.BotSlot slot, long id, int tries) {
        Guild g = slot.jda.getGuildById(bots.guildId());
        VoiceChannel vc = g == null ? null : g.getVoiceChannelById(id);
        if (vc != null) return CompletableFuture.completedFuture(vc);
        if (tries <= 0) return CompletableFuture.failedFuture(new IllegalStateException("Bot findet den neuen Kanal nicht."));
        return CompletableFuture.supplyAsync(() -> null, CompletableFuture.delayedExecutor(200, TimeUnit.MILLISECONDS))
                .thenCompose(x -> waitForChannel(slot, id, tries - 1));
    }

    private void addParticipant(GroupBridge b, Player p, long discordId) {
        b.participants.add(p.getUniqueId());
        byPlayer.put(p.getUniqueId(), b);
        b.removeSender(p.getUniqueId());

        Guild guild = bots.mainGuild();
        VoiceChannel vc = guild == null ? null : guild.getVoiceChannelById(b.channelId);
        if (vc == null) { Msg.err(p, "Discord-Kanal ist verschwunden."); leave(p.getUniqueId(), false); return; }

        String url = vc.getJumpUrl();
        vc.getManager().putMemberPermissionOverride(discordId, VOICE_PERMS, NONE).queue(ok -> {
            // Ist der Spieler schon in irgendeinem Sprachkanal, direkt hineinschieben
            Member m = guild.getMemberById(discordId);
            if (m != null && m.getVoiceState() != null && m.getVoiceState().inAudioChannel()) {
                guild.moveVoiceMember(m, vc).queue(null, e -> {});
            }
        }, e -> plugin.getLogger().warning("Berechtigung konnte nicht gesetzt werden: " + e.getMessage()));

        // Link per Discord-DM schicken (Bedrock kann Chat-Links nicht anklicken)
        bots.main().jda.retrieveUserById(discordId)
                .flatMap(User::openPrivateChannel)
                .flatMap(ch -> ch.sendMessage("Du bist der Minecraft-Voicechat-Gruppe **" + b.groupName
                        + "** beigetreten. Tritt diesem Sprachkanal bei: " + url))
                .queue(null, e -> {});

        Msg.ok(p, "Du bist der Gruppe \"" + b.groupName + "\" beigetreten!");
        Msg.info(p, "Tritt in Discord dem Sprachkanal \"" + vc.getName() + "\" bei (Link per DM geschickt).");
        p.sendMessage(Msg.text("[Kanal in Discord oeffnen]", NamedTextColor.AQUA).clickEvent(ClickEvent.openUrl(url)));
        notifyGroup(b, Msg.text(p.getName() + " ist der Gruppe ueber Discord beigetreten.", NamedTextColor.GRAY));
    }

    private void teardown(GroupBridge b, boolean blocking) {
        bridges.remove(b.groupId, b);
        for (UUID u : new ArrayList<>(b.participants)) byPlayer.remove(u, b);
        b.participants.clear();

        try {
            Guild wg = b.bot.jda.getGuildById(bots.guildId());
            if (wg != null) {
                AudioManager am = wg.getAudioManager();
                am.closeAudioConnection();
                am.setSendingHandler(null);
                am.setReceivingHandler(null);
            }
        } catch (Exception ignored) {}
        b.close();
        bots.release(b.bot);

        Guild guild = bots.mainGuild();
        VoiceChannel vc = guild == null ? null : guild.getVoiceChannelById(b.channelId);
        if (vc != null) {
            if (blocking) {
                try { vc.delete().complete(); } catch (Exception ignored) {}
            } else {
                vc.delete().queue(null, e -> {});
            }
        }
    }

    /** Loescht verwaiste Kanaele von einem frueheren Absturz. */
    void cleanupOldChannels() {
        Guild guild = bots.mainGuild();
        if (guild == null) return;
        String prefix = plugin.getConfig().getString("discord.channel-prefix", "MC-Gruppe: ");
        long categoryId = parseId(plugin.getConfig().getString("discord.category-id", "0"));
        for (VoiceChannel vc : guild.getVoiceChannels()) {
            boolean sameCategory = categoryId == 0 ? vc.getParentCategoryIdLong() == 0 : vc.getParentCategoryIdLong() == categoryId;
            if (sameCategory && vc.getName().startsWith(prefix) && !isActiveChannel(vc.getIdLong())) {
                vc.delete().queue(null, e -> {});
            }
        }
    }

    private boolean isActiveChannel(long id) {
        for (GroupBridge b : bridges.values()) if (b.channelId == id) return true;
        return false;
    }

    // =====================================================================
    //  Laufende Aktualisierung
    // =====================================================================

    /** Alle 10 Ticks im Main-Thread. */
    void tick() {
        VoicechatServerApi a = api;
        if (a == null) return;
        for (GroupBridge b : bridges.values()) {
            Group g = a.getGroup(b.groupId);
            if (g == null) {
                for (UUID u : b.participants) {
                    Player p = Bukkit.getPlayer(u);
                    if (p != null) Msg.info(p, "Die Gruppe \"" + b.groupName + "\" wurde aufgeloest.");
                }
                teardown(b, false);
                continue;
            }
            b.groupName = g.getName();
            b.updateTargets();
        }
        long now = System.currentTimeMillis();
        invites.values().removeIf(i -> i.expiresAt() < now);
    }

    /** Voicechat-Netzwerkthread. */
    void onMicrophone(MicrophonePacketEvent event) {
        VoicechatConnection c = event.getSenderConnection();
        if (c == null) return;
        Group g = c.getGroup();
        if (g == null) return;
        GroupBridge b = bridges.get(g.getId());
        if (b != null) b.onMicrophone(c.getPlayer().getUuid(), event.getPacket().getOpusEncodedData());
    }

    void onQuit(UUID uuid) {
        leave(uuid, false);
        invites.remove(uuid);
        for (GroupBridge b : bridges.values()) b.removeSender(uuid);
    }

    void shutdown() {
        for (GroupBridge b : new ArrayList<>(bridges.values())) teardown(b, true);
    }

    // =====================================================================

    private void notifyGroup(GroupBridge b, Component message) {
        VoicechatServerApi a = api;
        if (a == null) return;
        for (Player p : Bukkit.getOnlinePlayers()) {
            VoicechatConnection c = a.getConnectionOf(p.getUniqueId());
            if (c != null && c.getGroup() != null && b.groupId.equals(c.getGroup().getId())) p.sendMessage(message);
        }
    }

    void sync(Runnable r) {
        if (plugin.isEnabled()) Bukkit.getScheduler().runTask(plugin, r);
    }

    static long parseId(String s) {
        try { return Long.parseLong(s == null ? "0" : s.trim()); } catch (NumberFormatException e) { return 0; }
    }
}
