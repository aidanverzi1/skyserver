package com.aidan.skyserver.lobby;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.time.LocalDate;
import java.util.*;

/**
 * 5.8: the 7-day login streak (replaces the flat "1 Common Key a day"). Your first login each day
 * moves you one day along; day 7 is the big one, then it starts again at day 1. Miss a day and you
 * drop back 2 days (not to the start). Saved in config/skyserver/streak.json.
 */
public final class DailyStreak {
    /** Money and key (-1 = none) for days 1..7. */
    public static final long[] MONEY = {250, 300, 400, 500, 600, 750, 1_000};
    public static final int[] KEY = {0, -1, 0, -1, 1, -1, 2};
    public static final int DROP_BACK = 2;

    private static final Map<UUID, long[]> DATA = new HashMap<>(); // {streak day 1..7, last epoch day}
    private static boolean loaded = false;

    private DailyStreak() {}

    public static int day(UUID id) {
        load();
        long[] d = DATA.get(id);
        return d == null ? 0 : (int) d[0];
    }

    /** First login today: pays today's streak reward. False if already claimed today. */
    public static boolean claim(MinecraftServer server, ServerPlayer p) {
        load();
        long today = LocalDate.now().toEpochDay();
        long[] d = DATA.computeIfAbsent(p.getUUID(), k -> new long[]{0, 0});
        if (d[1] == today) return false;
        int day;
        if (d[1] == today - 1) day = (int) (d[0] % 7) + 1;
        else if (d[0] > 0) day = (int) Math.max(1, d[0] - DROP_BACK);
        else day = 1;
        boolean missed = d[1] != 0 && d[1] < today - 1;
        d[0] = day;
        d[1] = today;
        save();
        Economy.add(server, p.getUUID(), p.getName().getString(), MONEY[day - 1]);
        String key = "";
        if (KEY[day - 1] >= 0) {
            Keys.give(p.getUUID(), KEY[day - 1], 1);
            key = " §7+ " + Keys.COLORS[KEY[day - 1]] + Keys.NAMES[KEY[day - 1]] + " Key";
        }
        Lobby.msg(p, "§6§lDAILY STREAK §e- day " + day + "/7: §a+" + Economy.format(MONEY[day - 1]) + key
                + (missed ? " §8(you missed a day, so you dropped back " + DROP_BACK + ")" : "")
                + (day < 7 ? " §7Come back tomorrow for day " + (day + 1) + (KEY[day] >= 0 ? " (" + Keys.NAMES[KEY[day]] + " Key)" : "") + "!" : " §6§lStreak complete!"));
        return true;
    }

    private static void load() {
        if (loaded) return;
        loaded = true;
        JsonObject o = Stacks.readFile("streak.json");
        for (Map.Entry<String, JsonElement> e : o.entrySet()) {
            JsonArray a = e.getValue().getAsJsonArray();
            DATA.put(UUID.fromString(e.getKey()), new long[]{a.get(0).getAsLong(), a.get(1).getAsLong()});
        }
    }

    private static void save() {
        JsonObject o = new JsonObject();
        DATA.forEach((k, v) -> {
            JsonArray a = new JsonArray();
            a.add(v[0]);
            a.add(v[1]);
            o.add(k.toString(), a);
        });
        PlayerCache.io(() -> Stacks.writeFile("streak.json", o));
    }
}
