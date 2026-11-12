package com.aidan.skyserver.lobby;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.time.LocalDate;
import java.util.*;

/**
 * Every amount of money and XP the server hands out, in one place, so the economy can be tuned
 * here. The target is roughly $2,500 and 400 XP for an hour of active play (more for good players,
 * less for AFK), with prices in the shop set so that:
 *   - a diamond is ~5 minutes of play, a diamond armor set ~2 hours,
 *   - a netherite set ~2-3 weeks of regular play, an elytra or beacon ~2-3 weeks,
 * and something to show for every session (levels, daily bonuses, keys).
 *
 * Fairness rules:
 *   - Wins against bots pay less: -15% per bot on the other side, but never below 25%.
 *   - Bedwars (5.13): win, loss and bed pay drop 8% for every OTHER team with at least one bot
 *     (3 teams with bots = 76%: win $532). Was 15% in 5.8.
 *   - Kills on bots pay half.
 *   - The first win of the day in each game pays a big bonus (so short daily sessions feel great,
 *     and grinding all day isn't required to keep up).
 *
 * 5.8: money paid through here gets money boosters (Boosters.money).
 * 5.13: and the Voyage money perk (+10% in the featured mode); it's also counted for /ss usage.
 */
public final class Rewards {
    // ---- Gold Wars (a match is ~20 minutes) ---- 5.8: win 600 -> 550, loss 200 -> 175, first win 750 -> 600
    // 5.18: Skywars, Gold Wars and Bedwars pay 25% more (they're meant to be the main money makers).
    public static final long GW_WIN = 688, GW_LOSS = 219, GW_GOLD = 19, GW_KILL = 19, GW_FIRST_WIN = 750;
    public static final int GW_WIN_XP = 120, GW_LOSS_XP = 50, GW_GOLD_XP = 10, GW_KILL_XP = 6;
    // ---- Skywars (a match is ~5-8 minutes) ----
    public static final long SW_WIN = 625, SW_SECOND = 250, SW_THIRD = 125, SW_KILL = 38, SW_PLAY = 50, SW_FIRST_WIN = 625;
    public static final int SW_WIN_XP = 100, SW_SECOND_XP = 50, SW_THIRD_XP = 30, SW_KILL_XP = 12, SW_PLAY_XP = 15;
    // ---- Bedwars (a match is ~15-30 minutes) ---- 5.8: win 700, loss 100, bed 100, first win $500 (+ Common Key)
    public static final long BW_WIN = 875, BW_LOSS = 125, BW_BED = 125, BW_FINAL = 31, BW_KILL = 13, BW_FIRST_WIN = 625;
    /** No longer paid (5.8: everyone who doesn't win gets BW_LOSS instead). Kept so old code still compiles. */
    public static final long BW_PLAY = 0;
    public static final int BW_WIN_XP = 110, BW_PLAY_XP = 45, BW_BED_XP = 25, BW_FINAL_XP = 10, BW_KILL_XP = 5;
    /** Bedwars: -8% for every other team that has at least one bot (5.13: was 15%). */
    public static final double BW_CUT_PER_BOT_TEAM = 0.08;
    // ---- Other ----
    public static final double BOT_KILL_PAY = 0.5, WIN_CUT_PER_BOT = 0.15, MIN_BOT_SCALE = 0.25;
    public static final int DAILY_LOGIN_XP = 30, CHEST_OPEN_XP = 10;

    private static final Map<String, String> FIRST_WIN_DAY = new HashMap<>(); // "game:uuid" -> day
    private static boolean loaded = false;

    private Rewards() {}

    /** Skywars has up to 7 bots, so each bot cuts less: -10% per bot, never below 35%. */
    public static double skywarsBotScale(int bots) {
        return Math.max(0.35, 1 - 0.10 * bots);
    }

    /** Old Bedwars rule (per bot). 5.8 uses bedwarsTeamScale instead; kept for anything still calling it. */
    public static double bedwarsBotScale(int bots) {
        return Math.max(0.30, 1 - 0.08 * bots);
    }

    /** Bedwars: pay scale when this many OTHER teams have at least one bot (5.13: 3 teams with bots = 76%). */
    public static double bedwarsTeamScale(int otherTeamsWithBots) {
        return Math.max(0.25, 1 - BW_CUT_PER_BOT_TEAM * otherTeamsWithBots);
    }

    /** How much a win/placement is worth against this many bots (1.0 = none). */
    public static double botScale(int bots) {
        return Math.max(MIN_BOT_SCALE, 1 - WIN_CUT_PER_BOT * bots);
    }

    /** Pays money (with boosters) and tells the player. Bots never get paid. */
    public static void pay(MinecraftServer server, ServerPlayer p, long amount, String why) {
        if (amount <= 0 || p == null || p instanceof com.aidan.skyserver.bots.BotPlayer) return;
        long base = amount;
        long perk = Voyage.moneyPerk(p, amount); // 5.13: Voyage money perk
        long boosted = Boosters.money(p, perk);
        String extra = (boosted > perk ? " §d(+" + Economy.format(boosted - perk) + " booster)" : "")
                + (perk > base ? " §6(+" + Economy.format(perk - base) + " Voyage)" : "");
        amount = boosted;
        Economy.add(server, p.getUUID(), p.getName().getString(), amount);
        Playtime.money(amount);
        long[] t = TALLY.get(p.getUUID());
        if (t != null) {
            // In a match: a quick note above the hotbar now, one summary line at the end.
            t[0] += amount;
            Announce.actionBar(p, "§a+" + Economy.format(amount) + " §7" + why.replaceAll("§.", "") + extra);
        } else {
            Lobby.msg(p, "§a+" + Economy.format(amount) + " §7" + why + extra);
        }
    }

    // ---- Match tallies: money and XP from one match, shown as a single line at the end ----
    private static final Map<UUID, long[]> TALLY = new HashMap<>(); // {money, xp}

    public static void matchStart(ServerPlayer p) {
        if (p != null && !(p instanceof com.aidan.skyserver.bots.BotPlayer)) TALLY.put(p.getUUID(), new long[2]);
    }

    /** True if the XP was counted toward a match summary. */
    static boolean tallyXp(UUID id, long xp) {
        long[] t = TALLY.get(id);
        if (t == null) return false;
        t[1] += xp;
        return true;
    }

    /** Ends the tally and prints "Match rewards: $X and Y XP" (if anything was earned). */
    public static void matchEnd(ServerPlayer p, String game) {
        if (p == null) return;
        long[] t = TALLY.remove(p.getUUID());
        if (t == null || (t[0] == 0 && t[1] == 0)) return;
        Lobby.msg(p, "§6[" + game + "] §aMatch rewards: §6" + Economy.format(t[0]) + " §7and §b" + t[1] + " XP"
                + " §8(level " + Levels.level(p.getUUID()) + ")");
    }

    /** Money + XP in one go. */
    public static void reward(MinecraftServer server, ServerPlayer p, long money, int xp, String why) {
        if (p == null || p instanceof com.aidan.skyserver.bots.BotPlayer) return;
        pay(server, p, money, why);
        Levels.addXp(server, p, xp);
    }

    /** True (once per day per game) the first time this player wins that game today. */
    public static boolean firstWinToday(String game, UUID id) {
        load();
        String key = game + ":" + id, today = LocalDate.now().toString();
        if (today.equals(FIRST_WIN_DAY.get(key))) return false;
        FIRST_WIN_DAY.put(key, today);
        save();
        return true;
    }

    private static void load() {
        if (loaded) return;
        loaded = true;
        JsonObject o = Stacks.readFile("first-wins.json");
        for (Map.Entry<String, JsonElement> e : o.entrySet()) FIRST_WIN_DAY.put(e.getKey(), e.getValue().getAsString());
    }

    private static void save() {
        JsonObject o = new JsonObject();
        String today = LocalDate.now().toString();
        FIRST_WIN_DAY.forEach((k, v) -> { if (v.equals(today)) o.addProperty(k, v); });
        Stacks.writeFile("first-wins.json", o);
    }
}
