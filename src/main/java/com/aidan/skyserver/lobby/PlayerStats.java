package com.aidan.skyserver.lobby;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.level.ServerPlayer;

import java.util.*;

/**
 * Lifetime counters for each player that Minecraft doesn't track itself (game wins, kills, gold
 * broken, duel and Spleef wins, course finishes, island harvests...). Captain Wren's later quests
 * read them. Saved in config/skyserver/player-stats.json.
 *
 * Keys: sw_win, gw_win, bw_win, bw_play, bw_bed, bw_final, bw_kill, game_kill, gw_gold, duel_win, spleef_win, course_easy/medium/hard/extreme,
 * maze, elytra, daily_quest, island_ore, harvest, island_place, crate, mine_ore.
 */
public final class PlayerStats {
    private static final Map<UUID, Map<String, Long>> DATA = new HashMap<>();
    private static boolean loaded = false, dirty = false;

    private PlayerStats() {}

    public static void init() {
        ServerTickEvents.END_SERVER_TICK.register(s -> { if (dirty && s.getTickCount() % 1200 == 0) com.aidan.skyserver.Safe.run("stats save", PlayerStats::save); });
        ServerLifecycleEvents.SERVER_STOPPING.register(s -> { if (dirty) save(); });
    }

    public static void add(ServerPlayer p, String key, long amount) {
        if (p == null || amount <= 0 || p instanceof com.aidan.skyserver.bots.BotPlayer) return;
        load();
        DATA.computeIfAbsent(p.getUUID(), k -> new HashMap<>()).merge(key, amount, Long::sum);
        dirty = true;
    }

    /** Same, by id (for players who may be offline). */
    public static void add(UUID id, String key, long amount) {
        if (id == null || amount <= 0 || com.aidan.skyserver.bots.BotManager.isBot(id)) return;
        load();
        DATA.computeIfAbsent(id, k -> new HashMap<>()).merge(key, amount, Long::sum);
        dirty = true;
        save();
    }

    public static long get(UUID id, String key) {
        load();
        Map<String, Long> m = DATA.get(id);
        return m == null ? 0 : m.getOrDefault(key, 0L);
    }

    private static void load() {
        if (loaded) return;
        loaded = true;
        JsonObject root = Stacks.readFile("player-stats.json");
        for (Map.Entry<String, JsonElement> e : root.entrySet()) {
            Map<String, Long> m = new HashMap<>();
            for (Map.Entry<String, JsonElement> k : e.getValue().getAsJsonObject().entrySet()) m.put(k.getKey(), k.getValue().getAsLong());
            DATA.put(UUID.fromString(e.getKey()), m);
        }
    }

    static void save() {
        dirty = false;
        JsonObject root = new JsonObject();
        DATA.forEach((id, m) -> {
            JsonObject o = new JsonObject();
            m.forEach(o::addProperty);
            root.add(id.toString(), o);
        });
        Stacks.writeFile("player-stats.json", root);
    }
}
