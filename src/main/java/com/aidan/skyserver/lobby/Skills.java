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
 *  Mining   - the lobby mine, and blocks that grew on an island generator (5.23: only those - placed
 *             blocks give nothing, so no Silk Touch / place-and-break loops)
 *  Farming  - harvesting grown crops on your island (5.23: only the grown part; placed melons,
 *             pumpkins, cane, cactus and bamboo give nothing)
 *  Shepherd - shearing, breeding and milking animals on your island
 *  Fishing  - every fish caught (lobby pond or island)
 *  Combat   - kills and wins in Bedwars / Skywars / Gold Wars, duels, Spleef and the Nether Expedition
 *  Building - placing blocks on your island (up to 5,000 XP a day), Captain Wren's quests
 *  Parkour  - obstacle courses, the maze and the elytra rings
 *
 * 5.10 balance: every skill earns roughly 10,000 XP an hour of focused play (the per-skill
 * multipliers below even out the sources). Level 50 takes ~130,000 XP (about 13 hours) and
 * Legends need another ~325,000 XP (about 32 hours) to reach 100. Old saves are converted so
 * everyone keeps their level and how far they were into it.
 * 5.23: Fishing is tuned to ~13,000 an hour (it's the calmest skill) but stops at FISH_WINDOW_CAP
 * XP per rolling FISH_WINDOW_MINUTES (in case someone beats the AFK checks). Every skill also stops at
 * HOUR_CAP XP per rolling hour, counted after every bonus - a safety net against future bugs.
 * Saved in config/skyserver/skills.json (5.23: the daily counters and the rolling hours too, in "_daily",
 * so a restart doesn't reset them).
 */
public final class Skills {
    public enum Skill {
        MINING("Mining", "§7", "⛏", 1.0), FARMING("Farming", "§a", "🌾", 0.45), SHEPHERD("Shepherd", "§f", "🐑", 0.5),
        FISHING("Fishing", "§b", "🎣", 0.9), COMBAT("Combat", "§c", "⚔", 1.0), BUILDING("Building", "§6", "🏠", 1.0),
        PARKOUR("Parkour", "§d", "🏃", 1.0);

        public final String title, color, icon;
        /** Evens out how fast each skill earns XP (about 10,000 an hour for all of them). */
        public final double rate;

        Skill(String title, String color, String icon, double rate) {
            this.title = title;
            this.color = color;
            this.icon = icon;
            this.rate = rate;
        }
    }

    public static final int MAX_LEVEL = 50;
    /** Legends of the Skies (finished all of Captain Wren's quests) can keep going to this level. */
    public static final int LEGEND_MAX_LEVEL = 100;
    /** skills.json format: 2 = the 5.10 curve. Older files are converted on load. */
    private static final int SAVE_VERSION = 2;

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
    public static final int BUILDING_DAILY_CAP = 5_000;
    /** 5.23: most XP of one skill per rolling hour (after every bonus). */
    public static final int HOUR_CAP = 17_500;
    /** 5.23: most Fishing XP per rolling FISH_WINDOW_MINUTES. */
    public static final int FISH_WINDOW_CAP = 30_000, FISH_WINDOW_MINUTES = 8 * 60;
    /** 5.23: Fishing XP per fish before the skill rate (x0.9 = 72) - about 13,000 an hour. */
    public static final int FISH_XP = 80;

    /** What a level unlocks, for the level-up message and /skills. */
    private static final Map<String, String> UNLOCKS = new HashMap<>();

    static {
        String[][] m = Mine.UNLOCK_TEXT;
        for (String[] u : m) UNLOCKS.put("MINING:" + u[0], u[1]);
        for (TownDesign.Area a : TownDesign.AREAS) if (a.skill() != null) UNLOCKS.merge(a.skill() + ":" + a.level(), "the " + a.name() + " in the lobby", (x, y) -> x + ", " + y);
        for (int l = Activities.FISH_BITE_LEVELS; l <= Activities.FISH_BITE_LEVELS * Activities.FISH_BITE_MAX_STEPS; l += Activities.FISH_BITE_LEVELS)
            UNLOCKS.merge("FISHING:" + l, "fish bite " + Activities.fishBitePercent(l) + "% sooner (pond and island)", (x, y) -> x + ", " + y);
    }

    private static final Map<UUID, long[]> XP = new HashMap<>();          // per skill
    private static final Map<UUID, int[]> TODAY = new HashMap<>();        // {building xp today}
    private static final Map<UUID, Integer> FISH_SEEN = new HashMap<>();  // last fish-caught stat
    /** Leftover fractions of XP per skill, so small amounts times the rate aren't lost. */
    private static final Map<UUID, double[]> CARRY = new HashMap<>();
    /** XP earned today in each skill (for the Town NPCs' daily tasks). */
    private static final Map<UUID, long[]> XP_TODAY = new HashMap<>();
    private static String xpDay = "";
    /** 5.23: XP gained per minute, per player per skill: {epoch minute, xp}, oldest first (for the rolling caps). */
    private static final Map<UUID, List<ArrayDeque<long[]>>> RECENT = new HashMap<>();
    private static final Map<String, Long> CAP_MSG = new HashMap<>(); // "uuid:skill" -> last message (ms)

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
                if (before != null && caught > before) add(p, Skill.FISHING, FISH_XP * (caught - before));
            }
            if (dirty && s.getTickCount() % 400 == 0) save();
        }
    }

    // ---- Levels ----

    /** XP from one level to the next. Levels 1-49: 150 rising to ~5,150. Legend levels 50-99: 5,400 rising to ~7,600. */
    public static int xpForNext(int level) {
        if (level < MAX_LEVEL) return (int) Math.round(150 + 104.2 * (level - 1));
        return (int) Math.round(5400 + 44.9 * (level - MAX_LEVEL));
    }

    /** The pre-5.10 curve, only used to convert old saves. */
    private static int oldXpForNext(int level) {
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

    /** Converts old XP to the new curve, keeping the level and how far into it the player was. */
    private static long convertOld(long oldXp) {
        int level = 1;
        long left = oldXp;
        while (level < LEGEND_MAX_LEVEL && left >= oldXpForNext(level)) {
            left -= oldXpForNext(level);
            level++;
        }
        if (level >= LEGEND_MAX_LEVEL) return totalFor(LEGEND_MAX_LEVEL);
        double frac = Math.min(1.0, left / (double) oldXpForNext(level));
        return totalFor(level) + (long) Math.floor(frac * xpForNext(level));
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
        add(p, s, (double) amount);
    }

    /** 5.23: fractional amounts are kept (island generators give 0.7 XP for cobble, for example). */
    public static void add(ServerPlayer p, Skill s, double amount) {
        if (p == null || amount <= 0 || p instanceof com.aidan.skyserver.bots.BotPlayer) return;
        grant(p, s, amount * Events.xpMult() * s.rate);
    }

    /**
     * 5.23: XP that's already final (no weekend bonus or skill rate on top) - for bonuses worked out from
     * XP that was already given, like Harvest Moon's +10%. The caps still apply.
     */
    public static void addExact(ServerPlayer p, Skill s, double amount) {
        if (p == null || amount <= 0 || p instanceof com.aidan.skyserver.bots.BotPlayer) return;
        grant(p, s, amount);
    }

    private static void grant(ServerPlayer p, Skill s, double scaledIn) {
        if (com.aidan.skyserver.expedition.ExpeditionTest.isTesting(p.getUUID())) return; // 5.25: no skill XP on a test trip
        load();
        UUID id = p.getUUID();
        // Small amounts keep their leftover fraction for next time.
        double[] carry = CARRY.computeIfAbsent(id, k -> new double[Skill.values().length]);
        double scaled = scaledIn + carry[s.ordinal()];
        int amount = (int) Math.floor(scaled);
        carry[s.ordinal()] = scaled - amount;
        if (amount <= 0) return;
        if (s == Skill.BUILDING) {
            String d = LocalDate.now().toString();
            if (!d.equals(today)) {
                today = d;
                TODAY.clear();
            }
            int[] t = TODAY.computeIfAbsent(id, k -> new int[1]);
            amount = Math.min(amount, BUILDING_DAILY_CAP - t[0]);
            if (amount <= 0) return;
            t[0] += amount;
        }
        long minute = System.currentTimeMillis() / 60_000L;
        ArrayDeque<long[]> recent = recent(id, s, minute);
        // 5.23: Fishing - at most FISH_WINDOW_CAP per rolling 8 hours.
        if (s == Skill.FISHING) {
            long had = sum(recent, minute, FISH_WINDOW_MINUTES);
            int room = (int) Math.max(0, FISH_WINDOW_CAP - had);
            if (amount > room) {
                amount = room;
                capMessage(p, s, "§b🎣 The fish are wary of you. §7No more Fishing XP for about §f"
                        + waitText(recent, minute, FISH_WINDOW_MINUTES, FISH_WINDOW_CAP) + " §8(" + String.format("%,d", FISH_WINDOW_CAP)
                        + " Fishing XP per " + FISH_WINDOW_MINUTES / 60 + " hours)");
            } else if (had + amount >= FISH_WINDOW_CAP * 3L / 4) {
                Announce.actionBar(p, "§bFishing XP: §f" + String.format("%,d", had + amount) + " / " + String.format("%,d", FISH_WINDOW_CAP)
                        + " §7(last " + FISH_WINDOW_MINUTES / 60 + " hours)");
            }
            if (amount <= 0) return;
        }
        // 5.23: every skill - at most HOUR_CAP per rolling hour.
        long hour = sum(recent, minute, 60);
        int room = (int) Math.max(0, HOUR_CAP - hour);
        if (amount > room) {
            amount = room;
            capMessage(p, s, "§7You've reached the hourly limit for " + s.color + s.title + " XP §7(" + String.format("%,d", HOUR_CAP)
                    + "). §8More in about " + waitText(recent, minute, 60, HOUR_CAP));
            if (amount <= 0) return;
        }
        long[] last = recent.peekLast();
        if (last != null && last[0] == minute) last[1] += amount;
        else recent.addLast(new long[]{minute, amount});
        String d0 = LocalDate.now().toString();
        if (!d0.equals(xpDay)) {
            xpDay = d0;
            XP_TODAY.clear();
        }
        XP_TODAY.computeIfAbsent(id, k -> new long[Skill.values().length])[s.ordinal()] += amount;
        long[] x = xp(id);
        int cap = cap(id);
        int before = levelOf(x[s.ordinal()], cap)[0];
        x[s.ordinal()] += amount;
        // Until you're a Legend, XP stops at level 50 (it doesn't pile up for later).
        if (cap == MAX_LEVEL) x[s.ordinal()] = Math.min(x[s.ordinal()], totalFor(MAX_LEVEL));
        int after = levelOf(x[s.ordinal()], cap)[0];
        dirty = true;
        for (int lvl = before + 1; lvl <= after; lvl++) levelUp(p, s, lvl);
        if (after > before) save();
    }

    /** This player's per-minute XP list for a skill, with entries older than 8 hours dropped. */
    private static ArrayDeque<long[]> recent(UUID id, Skill s, long minute) {
        List<ArrayDeque<long[]>> all = RECENT.computeIfAbsent(id, k -> {
            List<ArrayDeque<long[]>> l = new ArrayList<>();
            for (int i = 0; i < Skill.values().length; i++) l.add(new ArrayDeque<>());
            return l;
        });
        ArrayDeque<long[]> q = all.get(s.ordinal());
        while (!q.isEmpty() && q.peekFirst()[0] <= minute - FISH_WINDOW_MINUTES) q.pollFirst();
        return q;
    }

    private static long sum(ArrayDeque<long[]> q, long minute, int window) {
        long t = 0;
        for (long[] e : q) if (e[0] > minute - window) t += e[1];
        return t;
    }

    /** How long until there's room under the cap again, as "1h 20m" / "12 min". */
    private static String waitText(ArrayDeque<long[]> q, long minute, int window, long cap) {
        long total = sum(q, minute, window), wait = window;
        for (long[] e : q) {
            if (e[0] <= minute - window) continue;
            total -= e[1];
            if (total < cap) {
                wait = e[0] + window - minute;
                break;
            }
        }
        wait = Math.max(1, wait);
        return wait >= 60 ? (wait / 60) + "h " + (wait % 60) + "m" : wait + " min";
    }

    private static void capMessage(ServerPlayer p, Skill s, String text) {
        String key = p.getUUID() + ":" + s.name();
        long now = System.currentTimeMillis();
        Long last = CAP_MSG.get(key);
        if (last != null && now - last < 120_000L) return;
        CAP_MSG.put(key, now);
        Lobby.msg(p, text);
    }

    /**
     * Level-up money: $32 per level, plus $16 per level past 10 since levels take longer.
     * 5.27: 20% less than before ($40 + $20) - skills were paying far more than Bedwars / Skywars / Gold Wars.
     */
    public static long levelMoney(int lvl) {
        return 32L * lvl + (lvl > 10 ? 16L * (lvl - 10) : 0);
    }

    private static void levelUp(ServerPlayer p, Skill s, int lvl) {
        MinecraftServer server = p.level().getServer();
        long money = levelMoney(lvl);
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
        Lobby.msg(p, "§8Limits: " + String.format("%,d", HOUR_CAP) + " XP per skill per hour; Fishing " + String.format("%,d", FISH_WINDOW_CAP)
                + " per " + FISH_WINDOW_MINUTES / 60 + " hours." + (Events.doubleXp() ? " §6" + Events.xpEventName() + " is on!" : ""));
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

    /** /ss resetskills: everything back to level 1. */
    public static void adminReset(UUID id) {
        load();
        XP.remove(id);
        CARRY.remove(id);
        save();
    }

    /** 5.23 /ss setskill: puts one skill at the start of a level (no level-up rewards). Returns the level set. */
    public static int adminSetLevel(UUID id, Skill s, int level) {
        load();
        int lvl = Math.max(1, Math.min(level, cap(id)));
        xp(id)[s.ordinal()] = totalFor(lvl);
        double[] c = CARRY.get(id);
        if (c != null) c[s.ordinal()] = 0;
        save();
        return lvl;
    }

    // ---- Saving ----

    private static void load() {
        if (loaded) return;
        loaded = true;
        JsonObject root = Stacks.readFile("skills.json");
        int version = root.has("_version") ? root.get("_version").getAsInt() : 1;
        boolean convert = version < SAVE_VERSION && !root.entrySet().isEmpty();
        for (Map.Entry<String, JsonElement> e : root.entrySet()) {
            if (e.getKey().startsWith("_") || !e.getValue().isJsonArray()) continue;
            JsonArray a = e.getValue().getAsJsonArray();
            long[] v = new long[Skill.values().length];
            for (int i = 0; i < v.length && i < a.size(); i++) {
                long old = a.get(i).getAsLong();
                v[i] = convert ? convertOld(old) : old;
            }
            try {
                XP.put(UUID.fromString(e.getKey()), v);
            } catch (IllegalArgumentException ignored) {
            }
        }
        if (root.has("_daily") && root.get("_daily").isJsonObject()) loadDaily(root.getAsJsonObject("_daily"));
        if (convert) {
            // Keep a copy of the old file, then save in the new format right away.
            Stacks.writeFile("skills-before-5.10.json", root);
            save();
        }
    }

    static void save() {
        dirty = false;
        JsonObject root = new JsonObject();
        root.addProperty("_version", SAVE_VERSION);
        XP.forEach((k, v) -> {
            JsonArray a = new JsonArray();
            for (long x : v) a.add(x);
            root.add(k.toString(), a);
        });
        root.add("_daily", saveDaily());
        Stacks.writeFile("skills.json", root);
    }

    /** 5.23: the Building cap, XP-today and the rolling hours survive a restart. */
    private static JsonObject saveDaily() {
        JsonObject o = new JsonObject();
        o.addProperty("buildDay", today);
        JsonObject b = new JsonObject();
        TODAY.forEach((k, v) -> b.addProperty(k.toString(), v[0]));
        o.add("building", b);
        o.addProperty("xpDay", xpDay);
        JsonObject t = new JsonObject();
        XP_TODAY.forEach((k, v) -> {
            JsonArray a = new JsonArray();
            for (long x : v) a.add(x);
            t.add(k.toString(), a);
        });
        o.add("xpToday", t);
        long minute = System.currentTimeMillis() / 60_000L;
        JsonObject r = new JsonObject();
        RECENT.forEach((k, list) -> {
            JsonArray a = new JsonArray();
            for (int i = 0; i < list.size(); i++)
                for (long[] e : list.get(i)) {
                    if (e[0] <= minute - FISH_WINDOW_MINUTES) continue;
                    JsonArray en = new JsonArray();
                    en.add(i);
                    en.add(e[0]);
                    en.add(e[1]);
                    a.add(en);
                }
            if (!a.isEmpty()) r.add(k.toString(), a);
        });
        o.add("recent", r);
        return o;
    }

    private static void loadDaily(JsonObject o) {
        try {
            String d = LocalDate.now().toString();
            if (o.has("buildDay") && d.equals(o.get("buildDay").getAsString()) && o.has("building")) {
                today = d;
                for (Map.Entry<String, JsonElement> e : o.getAsJsonObject("building").entrySet())
                    TODAY.put(UUID.fromString(e.getKey()), new int[]{e.getValue().getAsInt()});
            }
            if (o.has("xpDay") && d.equals(o.get("xpDay").getAsString()) && o.has("xpToday")) {
                xpDay = d;
                for (Map.Entry<String, JsonElement> e : o.getAsJsonObject("xpToday").entrySet()) {
                    JsonArray a = e.getValue().getAsJsonArray();
                    long[] v = new long[Skill.values().length];
                    for (int i = 0; i < v.length && i < a.size(); i++) v[i] = a.get(i).getAsLong();
                    XP_TODAY.put(UUID.fromString(e.getKey()), v);
                }
            }
            if (o.has("recent")) {
                long minute = System.currentTimeMillis() / 60_000L;
                for (Map.Entry<String, JsonElement> e : o.getAsJsonObject("recent").entrySet()) {
                    UUID id = UUID.fromString(e.getKey());
                    for (JsonElement el : e.getValue().getAsJsonArray()) {
                        JsonArray en = el.getAsJsonArray();
                        int i = en.get(0).getAsInt();
                        if (i < 0 || i >= Skill.values().length) continue;
                        recent(id, Skill.values()[i], minute).addLast(new long[]{en.get(1).getAsLong(), en.get(2).getAsLong()});
                    }
                }
            }
        } catch (RuntimeException ex) {
            com.aidan.skyserver.Skyserver.LOGGER.warn("skills.json: couldn't read the daily counters ({}), starting them fresh", ex.toString());
        }
    }
}
