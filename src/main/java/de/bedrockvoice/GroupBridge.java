package de.bedrockvoice;

import de.maxhenkel.voicechat.api.ServerLevel;
import de.maxhenkel.voicechat.api.VoicechatConnection;
import de.maxhenkel.voicechat.api.VoicechatServerApi;
import de.maxhenkel.voicechat.api.audiochannel.StaticAudioChannel;
import de.maxhenkel.voicechat.api.opus.OpusDecoder;
import de.maxhenkel.voicechat.api.opus.OpusEncoder;
import net.dv8tion.jda.api.audio.AudioReceiveHandler;
import net.dv8tion.jda.api.audio.AudioSendHandler;
import net.dv8tion.jda.api.audio.CombinedAudio;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Eine Bruecke zwischen genau einer Simple-Voice-Chat-Gruppe und einem Discord-Sprachkanal.
 *
 * Discord -> Minecraft: Der Bot empfaengt alle Discord-Sprecher gemischt (48 kHz Stereo PCM),
 *   wandelt in Mono um, kodiert Opus und schickt es per StaticAudioChannel an jedes
 *   Gruppenmitglied, das den Mod hat.
 * Minecraft -> Discord: Mikrofonpakete der Gruppenmitglieder werden dekodiert, gepuffert,
 *   alle 20 ms gemischt und als 48 kHz Stereo PCM an Discord gesendet.
 */
public final class GroupBridge implements AudioSendHandler, AudioReceiveHandler {

    private static final int FRAME_SAMPLES = 960;   // 20 ms bei 48 kHz Mono
    private static final int MAX_QUEUE = 10;        // max. 200 ms Puffer pro Sprecher

    final UUID groupId;
    volatile String groupName;
    final BotManager.BotSlot bot;
    final long channelId;
    /** Spieler, die ueber Discord an dieser Gruppe teilnehmen. */
    final Set<UUID> participants = ConcurrentHashMap.newKeySet();

    private final VoicechatServerApi api;
    private final UUID sourceId = UUID.randomUUID();
    private final OpusEncoder encoder;
    private final double toMcVolume;
    private final double toDiscordVolume;

    private final Map<UUID, OpusDecoder> decoders = new ConcurrentHashMap<>();
    private final Map<UUID, Queue<short[]>> queues = new ConcurrentHashMap<>();

    /** Nur vom Main-Thread benutzt. */
    private final Map<UUID, Target> targetCache = new HashMap<>();
    /** Snapshot fuer den Discord-Audio-Thread. */
    private volatile List<StaticAudioChannel> targets = List.of();
    private volatile boolean closed;
    private boolean wasTalking;

    private record Target(Object rawLevel, StaticAudioChannel channel) {}

    GroupBridge(VoicechatServerApi api, UUID groupId, String groupName, BotManager.BotSlot bot,
                long channelId, double toMcVolume, double toDiscordVolume) {
        this.api = api;
        this.groupId = groupId;
        this.groupName = groupName;
        this.bot = bot;
        this.channelId = channelId;
        this.toMcVolume = toMcVolume;
        this.toDiscordVolume = toDiscordVolume;
        this.encoder = api.createEncoder();
    }

    // ------------------------------------------------------------------ Main-Thread

    /** Ermittelt alle Gruppenmitglieder mit installiertem Mod. Muss im Main-Thread laufen. */
    @SuppressWarnings("deprecation")
    void updateTargets() {
        if (closed) return;
        Set<UUID> seen = new HashSet<>();
        List<StaticAudioChannel> list = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            UUID uuid = p.getUniqueId();
            if (participants.contains(uuid)) continue;
            VoicechatConnection c = api.getConnectionOf(uuid);
            if (c == null || !c.isInstalled() || c.getGroup() == null
                    || !groupId.equals(c.getGroup().getId())) continue;

            ServerLevel level = c.getPlayer().getServerLevel();
            Target t = targetCache.get(uuid);
            if (t == null || t.rawLevel() != level.getServerLevel()) {
                StaticAudioChannel ch = api.createStaticAudioChannel(sourceId, level, c);
                if (ch == null) continue;
                t = new Target(level.getServerLevel(), ch);
                targetCache.put(uuid, t);
            }
            seen.add(uuid);
            list.add(t.channel());
        }
        targetCache.keySet().retainAll(seen);
        targets = List.copyOf(list);
    }

    // ------------------------------------------------------------------ Minecraft -> Discord

    /** Wird vom Voicechat-Netzwerkthread aufgerufen. */
    void onMicrophone(UUID sender, byte[] opus) {
        if (closed) return;
        if (opus == null || opus.length == 0) {
            // Ende einer Sprachuebertragung
            OpusDecoder d = decoders.get(sender);
            if (d != null) synchronized (d) { d.resetState(); }
            return;
        }
        OpusDecoder d = decoders.computeIfAbsent(sender, k -> api.createDecoder());
        short[] pcm;
        synchronized (d) {
            if (d.isClosed()) return;
            pcm = d.decode(opus);
        }
        Queue<short[]> q = queues.computeIfAbsent(sender, k -> new ConcurrentLinkedQueue<>());
        q.add(pcm);
        while (q.size() > MAX_QUEUE) q.poll();
    }

    void removeSender(UUID sender) {
        queues.remove(sender);
        OpusDecoder d = decoders.remove(sender);
        if (d != null) synchronized (d) { d.close(); }
    }

    @Override
    public boolean canProvide() {
        if (closed) return false;
        for (Queue<short[]> q : queues.values()) if (!q.isEmpty()) return true;
        return false;
    }

    @Override
    public ByteBuffer provide20MsAudio() {
        int[] mix = new int[FRAME_SAMPLES];
        boolean any = false;
        for (Queue<short[]> q : queues.values()) {
            short[] f = q.poll();
            if (f == null) continue;
            any = true;
            int n = Math.min(FRAME_SAMPLES, f.length);
            for (int i = 0; i < n; i++) mix[i] += f[i];
        }
        if (!any) return null;

        // 48 kHz, 16 bit, Stereo, Big-Endian (ByteBuffer ist standardmaessig Big-Endian)
        ByteBuffer buf = ByteBuffer.allocate(FRAME_SAMPLES * 4);
        for (int i = 0; i < FRAME_SAMPLES; i++) {
            short s = clamp(mix[i] * toDiscordVolume);
            buf.putShort(s).putShort(s);
        }
        buf.flip();
        return buf;
    }

    // ------------------------------------------------------------------ Discord -> Minecraft

    @Override
    public boolean canReceiveCombined() {
        return true;
    }

    @Override
    public void handleCombinedAudio(CombinedAudio audio) {
        if (closed) return;
        if (audio.getUsers().isEmpty()) {
            if (wasTalking) {
                wasTalking = false;
                encoder.resetState();
            }
            return;
        }
        wasTalking = true;

        byte[] pcm = audio.getAudioData(toMcVolume); // Stereo, Big-Endian
        short[] mono = new short[FRAME_SAMPLES];
        for (int i = 0; i < FRAME_SAMPLES; i++) {
            int o = i * 4;
            int l = (short) ((pcm[o] << 8) | (pcm[o + 1] & 0xFF));
            int r = (short) ((pcm[o + 2] << 8) | (pcm[o + 3] & 0xFF));
            mono[i] = (short) ((l + r) / 2);
        }
        byte[] opus = encoder.encode(mono);
        for (StaticAudioChannel ch : targets) ch.send(opus);
    }

    // ------------------------------------------------------------------

    void close() {
        closed = true;
        targets = List.of();
        queues.clear();
        for (OpusDecoder d : decoders.values()) synchronized (d) { d.close(); }
        decoders.clear();
        encoder.close();
    }

    private static short clamp(double v) {
        if (v > Short.MAX_VALUE) return Short.MAX_VALUE;
        if (v < Short.MIN_VALUE) return Short.MIN_VALUE;
        return (short) v;
    }
}
