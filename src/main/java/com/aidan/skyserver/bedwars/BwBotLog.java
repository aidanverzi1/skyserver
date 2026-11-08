package com.aidan.skyserver.bedwars;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * The Bedwars training log (plain Java: the live server and the offline simulator both write it).
 *
 * During a match every bot decision, shop attempt (with the list of things it considered and why),
 * fight, stuck spot, death and bed is recorded, plus where every bot and player is every 2 seconds.
 * When the match ends it's written to config/skyserver-bot-logs/bw-<date>-<map>-<mode>.txt with a
 * summary per bot at the end (what it spent its time on, what it bought, where it got stuck), so a
 * person - or an AI reading the file - can see why a bot did what it did. The newest 30 are kept.
 *
 * Live streaming: /bw botlog on (bot shopping, decisions and problems in the server console),
 * /bw botlog verbose (also every bot's state every 2 s), /bw botlog off.
 *
 * 5.9: every purchase a real player makes is also passed to BwLearning (bots learn their timings).
 */
public final class BwBotLog {
    private BwBotLog() {}

    public enum Stream { OFF, ON, VERBOSE }

    /** Where live lines go (the server console; System.out in the simulator). Null = nowhere. */
    public static Consumer<String> console = null;
    public static Stream stream = Stream.OFF;
    /** Match time in ticks (set by the game / simulator). */
    public static LongSupplier clock = () -> 0;
    public static Path dir = Path.of("config", "skyserver-bot-logs");
    private static final int KEEP = 30;

    private static final List<String> LINES = new ArrayList<>();
    private static final Map<String, Summary> SUMMARY = new LinkedHashMap<>();
    private static boolean open = false;
    private static String name = "match";

    /** Per-bot totals for the end-of-match summary. */
    private static final class Summary {
        boolean bot;
        String role = "?";
        final Map<String, Integer> seconds = new LinkedHashMap<>();
        int bought, failed, deaths, kills, beds, stuckEvents;
        final List<String> purchases = new ArrayList<>();
        final List<String> problems = new ArrayList<>();
        double lx = Double.NaN, ly, lz;
        int still = 0, longestStill = 0;
        String longestStillWhere = "";
    }

    // =====================================================================

    public static boolean isOpen() {
        return open;
    }

    /** Starts a new match log. header = lines describing the match (map, teams, positions). */
    public static void start(String fileName, List<String> header) {
        LINES.clear();
        SUMMARY.clear();
        open = true;
        name = fileName;
        LINES.add("# SKYSERVER BEDWARS BOT LOG");
        LINES.add("# How to read: [mm:ss] = match time. STATE lines (every 2 s) give each bot's role, position, health,");
        LINES.add("# blocks, resources (i=iron g=gold d=diamond e=emerald), gear (armor/sword/pick/axe), what it's doing and");
        LINES.add("# its path (moving/arrived/STUCK, steps done/total, failed searches). SHOP lines are every purchase");
        LINES.add("# attempt; THINK lines show what a bot considered before buying; DECIDE/FIGHT/STUCK/INTERCEPT are choices.");
        LINES.add("# The SUMMARY at the end totals each bot's time and lists its problems.");
        LINES.add("");
        LINES.addAll(header);
        LINES.add("");
        console("=== Bedwars bot log started: " + fileName);
    }

    private static String stamp() {
        long t = clock.getAsLong() / 20;
        return String.format("[%02d:%02d] ", t / 60, t % 60);
    }

    private static void console(String s) {
        Consumer<String> c = console;
        if (c != null && stream != Stream.OFF) c.accept("[BW-BOT] " + s);
    }

    private static Summary sum(String who) {
        return SUMMARY.computeIfAbsent(who, k -> new Summary());
    }

    /** A free-form line in the match log. */
    public static void line(String text) {
        if (open) LINES.add(stamp() + text);
    }

    /** A bot (or game) event worth streaming live: decisions, fights, problems. */
    public static void event(String who, String kind, String text) {
        String l = kind + " " + who + ": " + text;
        if (open) LINES.add(stamp() + l);
        if (BOT_NAMES.contains(who)) console(stamp() + l);
        if (kind.equals("STUCK") || kind.equals("GIVE-UP")) {
            Summary s = sum(who);
            if (kind.equals("STUCK")) s.stuckEvents++;
            if (s.problems.size() < 40) s.problems.add(stamp() + kind + ": " + text);
        }
    }

    private static final Set<String> BOT_NAMES = new HashSet<>();

    /** Registers who is a bot (their lines are streamed live). */
    public static void member(String who, boolean bot, String team, String extra) {
        if (bot) BOT_NAMES.add(who);
        Summary s = sum(who);
        s.bot = bot;
        line("MEMBER " + who + " " + (bot ? "bot" : "player") + " team " + team + (extra.isEmpty() ? "" : " " + extra));
    }

    /** Every shop/upgrade/trap attempt (the game's transaction processor calls this). */
    public static void transaction(String who, boolean bot, String kind, String id, String cost, int[] resAfter, boolean ok, String why) {
        String res = resAfter == null ? "" : " left i" + resAfter[0] + " g" + resAfter[1] + " d" + resAfter[2] + " e" + resAfter[3];
        String l = kind + " " + who + (bot ? "" : " (player)") + " " + (ok ? "BOUGHT " : "FAILED ") + id + " for " + cost + res + (ok ? "" : " - " + why);
        if (open) LINES.add(stamp() + l);
        if (bot) console(stamp() + l);
        Summary s = sum(who);
        if (ok) {
            s.bought++;
            if (s.purchases.size() < 60) s.purchases.add(stamp().trim() + " " + id);
        } else s.failed++;
        // 5.9: bots learn their timings from what real players buy.
        if (ok && !bot) {
            try {
                com.aidan.skyserver.bots.BwLearning.humanBought(who, id, clock.getAsLong());
            } catch (RuntimeException ignored) {
            }
        }
    }

    /** What a bot weighed up before shopping (its wish list, in order, with the reason for each). */
    public static void think(String who, String role, List<String> considered, String chosen) {
        String l = "THINK " + who + " (" + role + ") -> " + (chosen == null ? "nothing to buy" : chosen) + " | " + String.join("; ", considered);
        if (open) LINES.add(stamp() + l);
        console(stamp() + l);
    }

    /**
     * A bot's or player's state (call every 2 seconds). doing = the activity in plain words; detail =
     * path/target info. Totals time per activity and spots standing still.
     */
    public static void state(String who, boolean bot, String role, double x, double y, double z, String doing, String detail) {
        Summary s = sum(who);
        s.role = role;
        String activity = doing.split(" ").length >= 2 ? doing.split(" ")[0] + " " + doing.split(" ")[1] : doing;
        s.seconds.merge(activity, 2, Integer::sum);
        double moved = Double.isNaN(s.lx) ? 99 : Math.sqrt((x - s.lx) * (x - s.lx) + (y - s.ly) * (y - s.ly) + (z - s.lz) * (z - s.lz));
        if (moved < 1.0) {
            s.still += 2;
            if (s.still > s.longestStill) {
                s.longestStill = s.still;
                s.longestStillWhere = String.format("%.0f %.0f %.0f while '%s'", x, y, z, doing);
            }
        } else s.still = 0;
        s.lx = x;
        s.ly = y;
        s.lz = z;
        if (!bot && clock.getAsLong() % 200 >= 40) return; // players: every 10 s is plenty
        String l = String.format("STATE %s [%s] at %.1f %.1f %.1f | %s | %s", who, role, x, y, z, doing, detail);
        if (open) LINES.add(stamp() + l);
        if (bot && stream == Stream.VERBOSE) console(stamp() + l);
    }

    public static void death(String who, String killer, String how, boolean fin) {
        sum(who).deaths++;
        if (killer != null) {
            sum(killer).kills++;
        }
        event(who, "DEATH", how + (killer == null ? "" : " by " + killer) + (fin ? " (FINAL)" : ""));
    }

    public static void bed(String breaker, String team) {
        if (breaker != null) sum(breaker).beds++;
        event(breaker == null ? "game" : breaker, "BED", team + " bed destroyed");
    }

    /** Ends the log: adds the summary and writes the file. Returns the file (null if nothing was written). */
    public static Path end(String result) {
        if (!open) return null;
        open = false;
        LINES.add("");
        LINES.add(stamp() + "RESULT " + result);
        LINES.add("");
        LINES.add("# ===================== SUMMARY =====================");
        for (Map.Entry<String, Summary> e : SUMMARY.entrySet()) {
            Summary s = e.getValue();
            if (!s.bot) continue;
            LINES.add("BOT " + e.getKey() + " (" + s.role + "): kills " + s.kills + ", deaths " + s.deaths + ", beds " + s.beds
                    + ", bought " + s.bought + " (failed attempts " + s.failed + "), stuck " + s.stuckEvents + " times");
            List<Map.Entry<String, Integer>> acts = new ArrayList<>(s.seconds.entrySet());
            acts.sort((a, b) -> b.getValue() - a.getValue());
            StringBuilder sb = new StringBuilder("   time: ");
            for (int i = 0; i < acts.size() && i < 8; i++) sb.append(acts.get(i).getKey()).append(" ").append(acts.get(i).getValue()).append("s, ");
            LINES.add(sb.toString());
            if (s.longestStill >= 20) LINES.add("   longest standing still: " + s.longestStill + "s at " + s.longestStillWhere);
            if (!s.purchases.isEmpty()) LINES.add("   purchases: " + String.join(", ", s.purchases));
            for (String p : s.problems) LINES.add("   problem " + p);
        }
        for (Map.Entry<String, Summary> e : SUMMARY.entrySet()) {
            Summary s = e.getValue();
            if (s.bot) continue;
            LINES.add("PLAYER " + e.getKey() + ": kills " + s.kills + ", deaths " + s.deaths + ", beds " + s.beds + ", bought " + s.bought);
        }
        console("=== Bedwars match over: " + result);
        BOT_NAMES.clear();
        if (dir == null) return null;
        try {
            Files.createDirectories(dir);
            String stampName = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm"));
            Path file = dir.resolve("bw-" + stampName + "-" + name.replaceAll("[^a-zA-Z0-9_-]", "") + ".txt");
            Files.write(file, LINES);
            // Keep the newest few.
            List<Path> old = new ArrayList<>();
            try (var st = Files.list(dir)) {
                st.filter(p -> p.getFileName().toString().startsWith("bw-") && p.getFileName().toString().endsWith(".txt")).forEach(old::add);
            }
            old.sort(Comparator.comparing(p -> p.getFileName().toString()));
            for (int i = 0; i < old.size() - KEEP; i++) Files.deleteIfExists(old.get(i));
            console("Log written to " + file);
            return file;
        } catch (Exception ex) {
            System.err.println("[skyserver] Bedwars bot log: couldn't write: " + ex);
            return null;
        }
    }

    /** The log so far (for the simulator). */
    public static List<String> lines() {
        return Collections.unmodifiableList(LINES);
    }
}
