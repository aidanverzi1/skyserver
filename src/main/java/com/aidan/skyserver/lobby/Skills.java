package com.aidan.skyserver.lobby;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.commands.Commands;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.stats.Stats;

import java.time.LocalDate;
import java.util.*;

/**
 * Skills: separate levels for Mining, Farming, Shepherd, Fishing, Combat, Building and Parkour.
 * They never reset (unlike the seasonal server level). Each level pays a little money, every 10th
 * level a key, and some levels unlock things: ores in the lobby mine, lobby areas (Farmers' Market,
 * Shepherd's Fields, Fighters' Guild, Builders' Gallery) and better fishing pay.
 *
 * Where the XP comes from:
 *  Mining   - the lobby mine, and mining stone/ores on your island
 *  Farming  - harvesting fully grown crops on your island
 *  Shepherd - shearing, breeding and milking animals on your island
 *  Fishing  - every fish caught (lobby pond or island)
 *  Combat   - kills in Skywars / Gold Wars, duel and Spleef wins
 *  Building - placing blocks on your island (up to 3,000 XP a day), Captain Wren's quests
 *
 * Tuned so each skill takes roughly 6-8 hours of focused play to max out (76,440 XP).
 *  Parkour  - obstacle courses, the maze and the elytra rings
 * Saved in config/skyserver/skills.json.
 */
public final class Skills {
    public enum Skill {
        MINING("Mining", "§7", "⛏"), FARMING("Farming", "§a", "🌾"), SHEPHERD("Shepherd", "§f", "🐑"), FISHING("Fishing", "§b", "🎣"),
        COMBAT("Combat", "§c", "⚔"), BUILDING("Building", "§6", "🏠"), PARKOUR("Parkour", "§d", "🏃");

        public final String title, color, icon;

        Skill(String title, String color, String icon) {
            this.title = title;
            this.color = color;
            this.icon = icon;
        }
    }

    public static final int MAX_LEVEL = 50;
    /** Legends of the Skies (finished all of Captain Wren's quests) can keep going to this level. */
    public static final int LEGEND_MAX_LEVEL = 100;

    public static boolean isLegend(UUID id) {
        return PlayerStats.get(id, "legend") > 0;
    }

    public static int cap(UUID id) {
        return isLegend(id) ? LEGEND_MAX_LEVEL : MAX_LEVEL;
    }

    /** Total XP needed to reach a level from level 1. */
    public static long totalFor(int level) {
        long t = 0;
        for (int l = 1; l < level; l++) t += xpForNext(l);
        return t;
    }
    public static final int BUILDING_DAILY_CAP = 3_000;

    /** What a level unlocks, for the level-up message and /skills. */
    private static final Map<String, String> UNLOCKS = new HashMap<>();

    static {
        String[][] m = Mine.UNLOCK_TEXT;
        for (String[] u : m) UNLOCKS.put("MINING:" + u[0], u[1]);
        for (TownDesign.Area a : TownDesign.AREAS) if (a.skill() != null) UNLOCKS.merge(a.skill() + ":" + a.level(), "the " + a.name() + " in the lobby", (x, y) -> x + ", " + y);
        UNLOCKS.put("FISHING:10", "+20% pay at the lobby pond");
        UNLOCKS.put("FISHING:25", "+50% pay at the lobby pond");
        UNLOCKS.put("FISHING:40", "double pay at the lobby pond");
    }

    private static final Map<UUID, long[]> XP = new HashMap<>();          // per skill
    private static final Map<UUID, int[]> TODAY = new HashMap<>();        // {building xp today}
    private static final Map<UUID, Integer> FISH_SEEN = new HashMap<>();  // last fish-caught stat
    /** XP earned today in each skill (for the Town NPCs' daily tasks). */
    private static final Map<UUID, long[]> XP_TODAY = new HashMap<>();
    private static String xpDay = "";

    public static long xpToday(UUID id, Skill s) {
        if (!LocalDate.now().toString().equals(xpDay)) return 0;
        long[] v = XP_TODAY.get(id);
        return v == null ? 0 : v[s.ordinal()];
    }
    private static String today = "";
    private static boolean loaded = false, dirty = false;

    private Skills() {}

    public static void init() {
        CommandRegistrationCallback.EVENT.register((d, r, e) -> d.register(Commands.literal("skills").executes(c -> {
            show(c.getSource().getPlayerOrException());
            return 1;
        })));
        ServerTickEvents.END_SERVER_TICK.register(s -> {
            if (s.getTickCount() % 40 != 0) return;
            com.aidan.skyserver.Safe.run("skills", () -> skillTick(s));
        });
    }

    private static void skillTick(net.minecraft.server.MinecraftServer s) {
        {
            // Fishing anywhere: every fish caught (the game counts them).
            for (ServerPlayer p : s.getPlayerList().getPlayers()) {
                if (p instanceof com.aidan.skyserver.bots.BotPlayer) continue;
                int caught = p.getStats().getValue(Stats.CUSTOM.get(Stats.FISH_CAUGHT));
                Integer before = FISH_SEEN.put(p.getUUID(), caught);
                if (before != null && caught > before) add(p, Skill.FISHING, 60 * (caught - before));
            }
            if (dirty && s.getTickCount() % 400 == 0) save();
        }
    }

    // ---- Levels ----

    public static int xpForNext(int level) {
        return 120 + 60 * (level - 1);
    }

    /** {level, xp into level, xp needed}. */
    public static int[] levelOf(long xp) {
        return levelOf(xp, MAX_LEVEL);
    }

    public static int[] levelOf(long xp, int max) {
        int level = 1;
        long left = xp;
        while (level < max && left >= xpForNext(level)) {
            left -= xpForNext(level);
            level++;
        }
        return new int[]{level, (int) left, level >= max ? 0 : xpForNext(level)};
    }

    public static int level(UUID id, Skill s) {
        return levelOf(xp(id)[s.ordinal()], cap(id))[0];
    }

    private static long[] xp(UUID id) {
        load();
        return XP.computeIfAbsent(id, k -> new long[Skill.values().length]);
    }

    /** Adds skill XP (bots get none) and hands out level-up rewards. */
    public static void add(ServerPlayer p, Skill s, int amount) {
        if (p == null || amount <= 0 || p instanceof com.aidan.skyserver.bots.BotPlayer) return;
        load();
        amount = Events.xp(amount);
        if (s == Skill.BUILDING) {
            String d = LocalDate.now().toString();
            if (!d.equals(today)) {
                today = d;
                TODAY.clear();
            }
            int[] t = TODAY.computeIfAbsent(p.getUUID(), k -> new int[1]);
            amount = Math.min(amount, BUILDING_DAILY_CAP - t[0]);
            if (amount <= 0) return;
            t[0] += amount;
        }
        String d0 = LocalDate.now().toString();
        if (!d0.equals(xpDay)) {
            xpDay = d0;
            XP_TODAY.clear();
        }
        XP_TODAY.computeIfAbsent(p.getUUID(), k -> new long[Skill.values().length])[s.ordinal()] += amount;
        long[] x = xp(p.getUUID());
        int cap = cap(p.getUUID());
        int before = levelOf(x[s.ordinal()], cap)[0];
        x[s.ordinal()] += amount;
        // Until you're a Legend, XP stops at level 50 (it doesn't pile up for later).
        if (cap == MAX_LEVEL) x[s.ordinal()] = Math.min(x[s.ordinal()], totalFor(MAX_LEVEL));
        int after = levelOf(x[s.ordinal()], cap)[0];
        dirty = true;
        for (int lvl = before + 1; lvl <= after; lvl++) levelUp(p, s, lvl);
        if (after > before) save();
    }

    private static void levelUp(ServerPlayer p, Skill s, int lvl) {
        MinecraftServer server = p.level().getServer();
        long money = 40L * lvl;
        Economy.add(server, p.getUUID(), p.getName().getString(), money);
        String unlock = UNLOCKS.get(s.name() + ":" + lvl);
        Lobby.msg(p, s.color + "§l" + s.icon + " " + s.title.toUpperCase() + " " + lvl + "! §a+" + Economy.format(money)
                + (unlock != null ? " §eUnlocked: §f" + unlock : ""));
        if (lvl % 10 == 0) Keys.reward(server, p.getUUID(), lvl >= LEGEND_MAX_LEVEL ? 3 : lvl >= 40 ? 2 : 1, 1, "(" + s.title + " level " + lvl + ")");
        p.level().playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 0.7f, 1.5f);
    }

    public static void show(ServerPlayer p) {
        Lobby.msg(p, "§6§lYOUR SKILLS §7(they never reset)" + (isLegend(p.getUUID()) ? " §6✦ Legend: up to level " + LEGEND_MAX_LEVEL
                : " §8- max " + MAX_LEVEL + ", or " + LEGEND_MAX_LEVEL + " for Legends of the Skies"));
        for (Skill s : Skill.values()) {
            int[] l = levelOf(xp(p.getUUID())[s.ordinal()], cap(p.getUUID()));
            String next = nextUnlock(s, l[0]);
            Lobby.msg(p, " " + s.color + s.icon + " " + s.title + " §f" + l[0] + (l[2] > 0 ? " §7(" + l[1] + "/" + l[2] + ")" : " §6(max)")
                    + (next != null ? " §8next: " + next : ""));
        }
    }

    public static List<String> lines(UUID id) {
        List<String> out = new ArrayList<>();
        for (Skill s : Skill.values()) {
            int[] l = levelOf(xp(id)[s.ordinal()], cap(id));
            out.add(s.color + s.icon + " " + s.title + " §f" + l[0] + (l[2] > 0 ? " §7(" + l[1] + "/" + l[2] + ")" : ""));
        }
        return out;
    }

    private static String nextUnlock(Skill s, int level) {
        for (int l = level + 1; l <= MAX_LEVEL; l++) {
            String u = UNLOCKS.get(s.name() + ":" + l);
            if (u != null) return u + " at " + l;
        }
        return null;
    }

    /** Lobby pond pay multiplier from the Fishing skill. */
    public static double fishingPay(UUID id) {
        int l = level(id, Skill.FISHING);
        return l >= 40 ? 2.0 : l >= 25 ? 1.5 : l >= 10 ? 1.2 : 1.0;
    }

    /** /ss resetskills: everything back to level 1. */
    public static void adminReset(UUID id) {
        load();
        XP.remove(id);
        save();
    }

    // ---- Saving ----

    private static void load() {
        if (loaded) return;
        loaded = true;
        JsonObject root = Stacks.readFile("skills.json");
        for (Map.Entry<String, JsonElement> e : root.entrySet()) {
            JsonArray a = e.getValue().getAsJsonArray();
            long[] v = new long[Skill.values().length];
            for (int i = 0; i < v.length && i < a.size(); i++) v[i] = a.get(i).getAsLong();
            XP.put(UUID.fromString(e.getKey()), v);
        }
    }

    static void save() {
        dirty = false;
        JsonObject root = new JsonObject();
        XP.forEach((k, v) -> {
            JsonArray a = new JsonArray();
            for (long x : v) a.add(x);
            root.add(k.toString(), a);
        });
        Stacks.writeFile("skills.json", root);
    }
}
