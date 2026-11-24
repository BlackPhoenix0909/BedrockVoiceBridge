package de.bedrockvoice;

import club.minnced.discord.jdave.interop.JDaveSessionFactory;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.audio.AudioModuleConfig;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.dv8tion.jda.api.utils.MemberCachePolicy;
import net.dv8tion.jda.api.utils.cache.CacheFlag;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Logger;

/** Verwaltet einen Pool von Discord-Bots. Jeder Bot kann gleichzeitig eine Gruppe bedienen. */
public final class BotManager {

    public static final class BotSlot {
        final JDA jda;
        volatile GroupBridge bridge;
        /** true, sobald der Bot fuer eine (evtl. noch entstehende) Bruecke reserviert ist. */
        boolean reserved;

        BotSlot(JDA jda) { this.jda = jda; }

        long selfId() { return jda.getSelfUser().getIdLong(); }
    }

    private final List<BotSlot> slots = new CopyOnWriteArrayList<>();
    private final long guildId;
    private final Logger log;
    private volatile boolean ready;

    BotManager(long guildId, Logger log) {
        this.guildId = guildId;
        this.log = log;
    }

    /** Blockierend – im asynchronen Thread aufrufen. */
    void start(List<String> tokens, ListenerAdapter mainListener) {
        for (int i = 0; i < tokens.size(); i++) {
            String token = tokens.get(i);
            if (token == null || token.isBlank() || token.startsWith("HIER_")) continue;
            try {
                JDABuilder builder = JDABuilder.createLight(token, GatewayIntent.GUILD_VOICE_STATES)
                        .enableCache(CacheFlag.VOICE_STATE)
                        .setMemberCachePolicy(MemberCachePolicy.VOICE)
                        // DAVE (Ende-zu-Ende-Verschluesselung) ist seit Maerz 2026 Pflicht fuer Discord-Voice
                        .setAudioModuleConfig(new AudioModuleConfig()
                                .withDaveSessionFactory(new JDaveSessionFactory()));
                if (slots.isEmpty()) builder.addEventListeners(mainListener);
                JDA jda = builder.build().awaitReady();
                if (jda.getGuildById(guildId) == null) {
                    log.warning("Bot " + jda.getSelfUser().getName() + " ist nicht auf dem Discord-Server "
                            + guildId + " – bitte einladen! Bot wird ignoriert.");
                    jda.shutdown();
                    continue;
                }
                slots.add(new BotSlot(jda));
                log.info("Discord-Bot verbunden: " + jda.getSelfUser().getName());
            } catch (Exception e) {
                log.severe("Discord-Bot #" + (i + 1) + " konnte nicht starten: " + e.getMessage());
            }
        }
        ready = !slots.isEmpty();
        if (!ready) log.severe("Kein Discord-Bot verfuegbar! Bitte bot-tokens und guild-id in der config.yml pruefen.");
    }

    boolean isReady() { return ready; }

    BotSlot main() { return slots.isEmpty() ? null : slots.get(0); }

    Guild mainGuild() {
        BotSlot m = main();
        return m == null ? null : m.jda.getGuildById(guildId);
    }

    long guildId() { return guildId; }

    synchronized BotSlot acquire() {
        for (BotSlot s : slots) {
            if (!s.reserved) {
                s.reserved = true;
                return s;
            }
        }
        return null;
    }

    synchronized void release(BotSlot slot) {
        slot.bridge = null;
        slot.reserved = false;
    }

    synchronized void assign(BotSlot slot, GroupBridge bridge) {
        slot.bridge = bridge;
    }

    int size() { return slots.size(); }

    void shutdown() {
        for (BotSlot s : slots) {
            try { s.jda.shutdown(); } catch (Exception ignored) {}
        }
        slots.clear();
        ready = false;
    }
}
