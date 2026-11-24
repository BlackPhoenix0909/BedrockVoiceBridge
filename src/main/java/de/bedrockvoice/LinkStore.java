package de.bedrockvoice;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.security.SecureRandom;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/** Speichert, welcher Minecraft-Spieler zu welchem Discord-Konto gehoert (links.yml). */
public final class LinkStore {

    record PendingCode(UUID player, String playerName, long expiresAt) {}

    private final File file;
    private final Logger log;
    private final SecureRandom random = new SecureRandom();
    private final Map<UUID, Long> links = new ConcurrentHashMap<>();
    private final Map<String, PendingCode> codes = new ConcurrentHashMap<>();

    LinkStore(File file, Logger log) {
        this.file = file;
        this.log = log;
    }

    void load() {
        links.clear();
        if (!file.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection s = y.getConfigurationSection("links");
        if (s == null) return;
        for (String key : s.getKeys(false)) {
            try {
                links.put(UUID.fromString(key), Long.parseLong(s.getString(key, "0")));
            } catch (IllegalArgumentException ignored) {}
        }
    }

    synchronized void save() {
        YamlConfiguration y = new YamlConfiguration();
        links.forEach((uuid, id) -> y.set("links." + uuid, String.valueOf(id)));
        try {
            file.getParentFile().mkdirs();
            y.save(file);
        } catch (IOException e) {
            log.severe("links.yml konnte nicht gespeichert werden: " + e.getMessage());
        }
    }

    Long get(UUID player) {
        return links.get(player);
    }

    UUID byDiscord(long discordId) {
        for (Map.Entry<UUID, Long> e : links.entrySet()) if (e.getValue() == discordId) return e.getKey();
        return null;
    }

    String createCode(UUID player, String name, long minutes) {
        codes.values().removeIf(c -> c.player().equals(player) || c.expiresAt() < System.currentTimeMillis());
        String code;
        do {
            code = String.format("%06d", random.nextInt(1_000_000));
        } while (codes.containsKey(code));
        codes.put(code, new PendingCode(player, name, System.currentTimeMillis() + minutes * 60_000L));
        return code;
    }

    /** @return der verknuepfte Spieler oder null, wenn der Code ungueltig/abgelaufen ist */
    synchronized PendingCode redeem(String code, long discordId) {
        PendingCode pc = codes.remove(code.trim());
        if (pc == null || pc.expiresAt() < System.currentTimeMillis()) return null;
        // Ein Discord-Konto gehoert immer nur zu einem Spieler
        links.values().removeIf(id -> id == discordId);
        links.put(pc.player(), discordId);
        save();
        return pc;
    }

    synchronized boolean unlink(UUID player) {
        boolean removed = links.remove(player) != null;
        if (removed) save();
        return removed;
    }
}
