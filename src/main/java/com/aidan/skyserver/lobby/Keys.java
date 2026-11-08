package com.aidan.skyserver.lobby;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.*;

/**
 * Mystery chest keys, stored by the server (they can't be dropped or lost). Five tiers:
 * 0 Common, 1 Uncommon, 2 Rare, 3 Epic, 4 Legendary. Saved in config/skyserver/keys.json.
 *
 * Ways to get keys (all numbers can be changed here and in DailyStreak):
 *  - the daily login streak (5.8: days 1/3 Common, day 5 Uncommon, day 7 Rare)
 *  - obstacle courses (first finish each day): Common / Uncommon / Rare
 *  - winning games, daily quests, levels, the battle pass
 *  - the key shop in the game menu, and the Shard Shop (5.8)
 *  - mystery chests sometimes give keys, and players can trade them
 */
public final class Keys {
    public static final String[] NAMES = LobbyDesign.TIER_NAMES;
    public static final String[] COLORS = LobbyDesign.TIER_COLORS;
    /** Key shop prices (0 = not for sale). 5.8: Epic 22k -> 25k, Legendary 50k -> 60k. */
    public static final long[] PRICES = {600, 1_800, 5_000, 25_000, 60_000};

    private static final Map<UUID, int[]> KEYS = new HashMap<>();
    private static final Map<UUID, String> LAST_DAILY = new HashMap<>();
    private static boolean loaded = false;

    private Keys() {}

    private static void load() {
        if (loaded) return;
        loaded = true;
        JsonObject root = Stacks.readFile("keys.json");
        JsonObject keys = root.has("keys") ? root.getAsJsonObject("keys") : new JsonObject();
        for (Map.Entry<String, JsonElement> e : keys.entrySet()) {
            int[] k = new int[5];
            JsonArray a = e.getValue().getAsJsonArray();
            for (int i = 0; i < 5 && i < a.size(); i++) k[i] = a.get(i).getAsInt();
            KEYS.put(UUID.fromString(e.getKey()), k);
        }
        JsonObject daily = root.has("daily") ? root.getAsJsonObject("daily") : new JsonObject();
        for (Map.Entry<String, JsonElement> e : daily.entrySet()) LAST_DAILY.put(UUID.fromString(e.getKey()), e.getValue().getAsString());
    }

    private static void save() {
        JsonObject root = new JsonObject(), keys = new JsonObject(), daily = new JsonObject();
        for (Map.Entry<UUID, int[]> e : KEYS.entrySet()) {
            JsonArray a = new JsonArray();
            for (int v : e.getValue()) a.add(v);
            keys.add(e.getKey().toString(), a);
        }
        for (Map.Entry<UUID, String> e : LAST_DAILY.entrySet()) daily.addProperty(e.getKey().toString(), e.getValue());
        root.add("keys", keys);
        root.add("daily", daily);
        Stacks.writeFile("keys.json", root);
    }

    public static int[] of(UUID id) {
        load();
        return KEYS.computeIfAbsent(id, k -> new int[5]).clone();
    }

    public static int count(UUID id, int tier) {
        return of(id)[tier];
    }

    public static void give(UUID id, int tier, int amount) {
        load();
        int[] k = KEYS.computeIfAbsent(id, x -> new int[5]);
        k[tier] = Math.max(0, k[tier] + amount);
        TxLog.keys(id, tier, amount);
        save();
    }

    /** Gives keys and tells the player (if online). */
    public static void reward(MinecraftServer server, UUID id, int tier, int amount, String why) {
        give(id, tier, amount);
        ServerPlayer p = server.getPlayerList().getPlayer(id);
        if (p != null) {
            Lobby.msg(p, "§a+" + amount + " " + COLORS[tier] + NAMES[tier] + " Key" + (amount == 1 ? "" : "s") + " §7" + why);
            Lobby.refreshKit(p);
        }
    }

    public static boolean take(UUID id, int tier, int amount) {
        load();
        int[] k = KEYS.computeIfAbsent(id, x -> new int[5]);
        if (k[tier] < amount) return false;
        k[tier] -= amount;
        TxLog.keys(id, tier, -amount);
        save();
        return true;
    }

    /** First login of the day: the daily streak reward (5.8). Returns true the first time today. */
    public static boolean dailyLogin(MinecraftServer server, ServerPlayer p) {
        boolean first = DailyStreak.claim(server, p);
        if (first) Lobby.refreshKit(p);
        return first;
    }

    public static String summary(UUID id) {
        int[] k = of(id);
        StringBuilder sb = new StringBuilder();
        for (int t = 0; t < 5; t++) sb.append(COLORS[t]).append(NAMES[t]).append(": §f").append(k[t]).append(t < 4 ? "§7, " : "");
        return sb.toString();
    }
}
