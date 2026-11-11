package com.aidan.skyserver.lobby;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.time.LocalDate;
import java.util.*;

/**
 * Daily quests: everyone gets 3 each day (picked from the list below, different for each player).
 * Each pays money and XP when it's done, and finishing all 3 gives a Common Key. The games and
 * lobby activities call {@link #progress} when something happens. Saved in quests.json (in the
 * background).
 *
 * Every event also goes to the battle pass (weekly challenges use the same event names), and each
 * finished daily quest gives pass XP.
 *
 * 5.13: every event also goes to Wren's Weekly Voyage (stamps, the board, Gold Rush), and the
 * sw_play / gw_play events are counted in PlayerStats and Playtime (/ss usage). The Archery and
 * Spleef quests were taken out of the pool (those modes went off the compass).
 *
 * 5.16: the Spleef quest is back (the pit is on the compass again). Archery stays out.
 */
public final class Quests {
    public record Quest(String key, String text, int target, long money, int xp) {}

    public static final List<Quest> POOL = List.of(
            new Quest("sw_play", "Play 2 Skywars matches", 2, 200, 50),
            new Quest("sw_win", "Win a Skywars match", 1, 400, 90),
            new Quest("sw_kill", "Get 3 kills in Skywars", 3, 300, 70),
            new Quest("gw_play", "Play a Gold Wars match", 1, 250, 60),
            new Quest("gw_gold", "Break 3 gold blocks in Gold Wars", 3, 350, 80),
            new Quest("gw_win", "Win a Gold Wars match", 1, 400, 90),
            new Quest("bw_play", "Play a Bedwars match", 1, 250, 60),
            new Quest("bw_bed", "Break a bed in Bedwars", 1, 350, 80),
            new Quest("bw_final", "Get 3 final kills in Bedwars", 3, 300, 70),
            new Quest("bw_win", "Win a Bedwars match", 1, 400, 90),
            new Quest("parkour", "Finish any obstacle course", 1, 250, 60),
            new Quest("crate", "Open a mystery chest", 1, 150, 40),
            new Quest("fish", "Catch 5 fish at the lobby pond", 5, 200, 50),
            new Quest("duel", "Win a duel", 1, 300, 70),
            new Quest("maze", "Find your way through the hedge maze", 1, 200, 50),
            new Quest("elytra", "Finish an elytra ring course", 1, 250, 60),
            new Quest("island_place", "Place 100 blocks on your Sky Island", 100, 250, 60),
            new Quest("island_break", "Mine 200 blocks on your Sky Island", 200, 250, 60),
            new Quest("challenge", "Complete an island challenge", 1, 300, 70),
            new Quest("spleef", "Win a round in the Spleef & TNT Run pit", 1, 250, 60)); // 5.16: back
    public static final int PER_DAY = 3;

    private static final Map<UUID, JsonObject> DATA = new HashMap<>();
    private static boolean loaded = false;

    private Quests() {}

    private static void load() {
        if (loaded) return;
        loaded = true;
        JsonObject root = Stacks.readFile("quests.json");
        for (Map.Entry<String, JsonElement> e : root.entrySet()) DATA.put(UUID.fromString(e.getKey()), e.getValue().getAsJsonObject());
    }

    /** Writes today's quest progress in the background (a copy is taken here, on the main thread). */
    private static void save() {
        JsonObject root = new JsonObject();
        String today = LocalDate.now().toString();
        DATA.forEach((k, v) -> { if (today.equals(v.get("day").getAsString())) root.add(k.toString(), v.deepCopy()); });
        PlayerCache.io(() -> Stacks.writeFile("quests.json", root));
    }

    /** Today's quests for a player (always the same 3 for the same player on the same day). */
    public static List<Quest> today(UUID id) {
        long seed = id.getMostSignificantBits() ^ id.getLeastSignificantBits() ^ LocalDate.now().toEpochDay() * 31L;
        List<Quest> pool = new ArrayList<>(POOL);
        Collections.shuffle(pool, new Random(seed));
        return pool.subList(0, PER_DAY);
    }

    private static JsonObject data(UUID id) {
        load();
        String today = LocalDate.now().toString();
        JsonObject d = DATA.get(id);
        if (d == null || !today.equals(d.get("day").getAsString())) {
            d = new JsonObject();
            d.addProperty("day", today);
            d.add("progress", new JsonObject());
            d.addProperty("bonus", false);
            DATA.put(id, d);
        }
        return d;
    }

    public static int progressOf(UUID id, Quest q) {
        JsonObject p = data(id).getAsJsonObject("progress");
        return p.has(q.key()) ? p.get(q.key()).getAsInt() : 0;
    }

    /** Something happened: counts toward today's quests with this key (and the battle pass's weekly challenges). */
    public static void progress(ServerPlayer p, String key, int amount) {
        if (p == null || p instanceof com.aidan.skyserver.bots.BotPlayer || amount <= 0) return;
        com.aidan.skyserver.Safe.run("pass event", () -> BattlePass.event(p, key, amount));
        if (key.equals("sw_win") || key.equals("gw_win") || key.equals("bw_win"))
            com.aidan.skyserver.Safe.run("victory effect", () -> Cosmetics.victory(p)); // 5.8
        // 5.13: play counters (bw_play is already counted by BedwarsGame) and the Voyage.
        if (key.equals("sw_play") || key.equals("gw_play")) PlayerStats.add(p, key, amount);
        if (key.equals("sw_play") || key.equals("gw_play") || key.equals("bw_play")) Playtime.game(key.substring(0, 2));
        com.aidan.skyserver.Safe.run("voyage event", () -> Voyage.onQuest(p, key, amount));
        Quest q = null;
        for (Quest t : today(p.getUUID())) if (t.key().equals(key)) q = t;
        if (q == null) return;
        JsonObject d = data(p.getUUID());
        JsonObject prog = d.getAsJsonObject("progress");
        int before = prog.has(key) ? prog.get(key).getAsInt() : 0;
        if (before >= q.target()) return;
        int now = Math.min(q.target(), before + amount);
        prog.addProperty(key, now);
        MinecraftServer server = p.level().getServer();
        if (now >= q.target()) {
            Lobby.msg(p, "§6§lDAILY QUEST DONE: §e" + q.text());
            PlayerStats.add(p, "daily_quest", 1);
            Rewards.reward(server, p, q.money(), q.xp(), "(daily quest)");
            boolean all = true;
            for (Quest t : today(p.getUUID())) if (progressOf(p.getUUID(), t) < t.target()) all = false;
            boolean bonus = all && !d.get("bonus").getAsBoolean();
            if (bonus) {
                d.addProperty("bonus", true);
                Keys.reward(server, p.getUUID(), 0, 1, "(all 3 daily quests done!)");
            }
            final boolean allThree = bonus;
            com.aidan.skyserver.Safe.run("pass daily", () -> BattlePass.dailyQuestDone(p, allThree));
        } else if (q.target() > 1 && (now * 4 / q.target()) > (before * 4 / q.target())) {
            Announce.actionBar(p, "§eQuest: §f" + q.text() + " §7(" + now + "/" + q.target() + ")");
        }
        save();
    }

    /** Lines for chat, the menu, or a book page. */
    public static List<String> describe(UUID id) {
        List<String> lines = new ArrayList<>();
        for (Quest q : today(id)) {
            int p = progressOf(id, q);
            boolean done = p >= q.target();
            lines.add((done ? "§a✔ " : "§e• ") + "§f" + q.text() + " §7(" + p + "/" + q.target() + ") §6" + Economy.format(q.money()));
        }
        lines.add(data(id).get("bonus").getAsBoolean() ? "§aBonus key collected!" : "§7Finish all 3 for a §fCommon Key§7. New quests every day.");
        lines.add("§7Each one also gives §b" + Progression.DAILY_QUEST_PASS_XP + " pass XP§7 (§e/pass§7).");
        return lines;
    }
}
