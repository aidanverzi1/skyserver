package com.aidan.skyserver.bots;

import com.aidan.skyserver.bedwars.BwTeam;

import java.util.*;

/**
 * What Bedwars bots learn (plain Java, saved in the shared bot memory file).
 *  - Each bot team picks a plan per match (Rush / Balanced / Eco) on each map. Plans that finish
 *    higher get picked more, with some exploring so the bots keep testing.
 *  - How real players play nudges the choice: if people on your server rush beds early, staying
 *    home to defend (Eco) gets a bonus; if they sit back, rushing them early does.
 *  - 5.9: the playbook's timings follow real players. Every purchase a real player makes is seen
 *    here: the time between their emerald buys sets how often bots go for emeralds (default 3 min,
 *    2-4), and when they first buy end stone after the early game sets when bots start the end
 *    stone cover over their bed (default minute 10, 7-13). Until enough has been seen the
 *    defaults from Verzi10's game are used.
 */
public final class BwLearning {
    private BwLearning() {}

    private static final Random RNG = new Random();
    private static final int PLANS = BwStrategy.Plan.values().length;

    /** 3v3v3v3 keeps the original key (so what was learned in 5.4 carries over); 2v2v2v2 learns separately. */
    private static String planKey(String map) {
        return "bw:" + map + (com.aidan.skyserver.bedwars.BwRules.mode == com.aidan.skyserver.bedwars.BwRules.Mode.DUOS ? ":duos" : "") + ":plan";
    }

    /** Called when a match starts. */
    public static void matchStarted(String map) {
        LAST_EMERALD_BUY.clear();
        COVER_SEEN.clear();
    }

    public static BwStrategy.Plan pickPlan(String map, Random rng) {
        double[] bonus = new double[PLANS];
        double[] early = BotMemory.get("bw:humanRush", 2); // [early bed visits, late bed visits]
        double total = early[0] + early[1];
        if (total >= 10) {
            double share = early[0] / total;
            bonus[BwStrategy.Plan.ECO.ordinal()] += 0.15 * share;
            bonus[BwStrategy.Plan.RUSH.ordinal()] += 0.15 * (1 - share);
        }
        return BwStrategy.Plan.values()[BotMemory.pickPlan(planKey(map), PLANS, bonus, rng == null ? RNG : rng)];
    }

    /** An enemy came near someone's bed. Real players doing it in the first 6 minutes = "early rushers". */
    public static void enemyNearBed(String map, boolean isBot, long matchTicks) {
        if (isBot) return;
        BotMemory.add("bw:humanRush", matchTicks < 6 * 60 * 20 ? 0 : 1, 2, 0.2);
    }

    public static void bedBroken(String map, boolean byHuman, long matchTicks) {
        if (byHuman) BotMemory.add("bw:humanRush", matchTicks < 8 * 60 * 20 ? 0 : 1, 2, 1);
    }

    // =====================================================================
    // 5.9: timings learned from real players' purchases
    // =====================================================================

    private static final Map<String, Long> LAST_EMERALD_BUY = new HashMap<>();
    private static final Set<String> COVER_SEEN = new HashSet<>();
    private static final Set<String> EMERALD_ITEMS = Set.of("diamond_armor", "diamond_sword", "ender_pearl", "obsidian", "rescue_cloud",
            "bow_punch", "invis_potion", "speed_potion", "jump_potion", "bridge_egg", "blanket");

    /** A real player bought something (called by the Bedwars transaction log). */
    public static void humanBought(String who, String id, long matchTicks) {
        if (id == null || who == null) return;
        double minute = matchTicks / 1200.0;
        if (EMERALD_ITEMS.contains(id)) {
            Long last = LAST_EMERALD_BUY.put(who, matchTicks);
            if (last != null && matchTicks > last) {
                double gap = (matchTicks - last) / 1200.0;
                if (gap >= 1 && gap <= 8) {
                    BotMemory.add("bw:humanEmeraldGap", 0, 2, gap);
                    BotMemory.add("bw:humanEmeraldGap", 1, 2, 1);
                }
            }
        }
        if (id.equals("endstone") && minute >= 5 && COVER_SEEN.add(who)) {
            BotMemory.add("bw:humanCoverMinute", 0, 2, minute);
            BotMemory.add("bw:humanCoverMinute", 1, 2, 1);
        }
    }

    private static double learned(String key, double def, double lo, double hi) {
        double[] v = BotMemory.get(key, 2);
        if (v[1] < 3) return def;
        return Math.max(lo, Math.min(hi, v[0] / v[1]));
    }

    /** Minutes between a team's emerald trips (learned from real players; default 3). */
    public static double emeraldIntervalMinutes() {
        return learned("bw:humanEmeraldGap", 3.0, 2.0, 4.0);
    }

    /** Around when the end stone cover goes on (learned from real players; default minute 10). */
    public static double coverMinute() {
        return learned("bw:humanCoverMinute", 10.0, 7.0, 13.0);
    }

    /** places: finishing place of every team (1 = won); plans: the plan each bot team used. */
    public static void matchEnded(String map, Map<BwTeam, Integer> places, Map<BwTeam, Integer> plans, int teams) {
        if (teams < 2) return;
        for (Map.Entry<BwTeam, Integer> e : plans.entrySet()) {
            Integer place = places.get(e.getKey());
            if (place == null) continue;
            double score = (teams - place) / (double) (teams - 1);
            BotMemory.planResult(planKey(map), PLANS, e.getValue(), score);
        }
        BotMemory.matchEnded();
    }

    public static String summary() {
        StringBuilder sb = new StringBuilder("\n§6Bedwars:");
        double[] early = BotMemory.get("bw:humanRush", 2);
        double t = early[0] + early[1];
        sb.append("\n§7 How players attack beds: ").append(t < 10 ? "§8not enough seen yet" : String.format("§f%.0f%% early rushes", 100 * early[0] / t));
        sb.append(String.format("\n§7 Emerald trips every §f%.1f min§7, end stone cover around §fminute %.0f §7(learned from players)",
                emeraldIntervalMinutes(), coverMinute()));
        for (String map : com.aidan.skyserver.bedwars.BwMapDesigns.IDS)
            for (String mode : new String[]{"", ":duos"}) {
                sb.append("\n§7 ").append(map).append(mode.isEmpty() ? " 3v3v3v3" : " 2v2v2v2").append(" plans (score / games):");
                for (BwStrategy.Plan p : BwStrategy.Plan.values()) {
                    double[] st = BotMemory.planStats("bw:" + map + mode + ":plan", PLANS, p.ordinal());
                    sb.append(String.format(" §f%s %.2f/%.0f§7,", p.name().toLowerCase(), st[0], st[1]));
                }
            }
        return sb.toString();
    }
}
