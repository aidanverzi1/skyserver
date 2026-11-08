package com.aidan.skyserver.bots;

import java.util.Random;

/**
 * What the Spleef / TNT Run and Duel bots learn (plain Java), the same way as the Gold Wars and
 * Skywars bots: each round a bot picks a plan, plans that do better get picked more, and what real
 * players do nudges the choice.
 *
 *  - Spleef: HUNTER (chase people and dig under them), GUARD (hold the middle, dig under anyone who
 *    comes close), OPPORTUNIST (go after whoever is nearest a hole or the edge).
 *    Players who chase a lot -> GUARD pays; players who keep away -> HUNTER pays.
 *  - TNT Run: CIRCLE (big loops round the arena), ZIGZAG (sharp turns, eats the floor less evenly),
 *    WANDER (always heads for the most floor).
 *  - Duels: RUSH (sword, close the gap fast), KITE (bow at range, sword up close), COUNTER (circle
 *    just out of reach and punish right after they swing).
 *    Players who bow a lot -> RUSH pays; players who rush -> KITE and COUNTER pay.
 */
public final class MiniLearning {
    public enum SpleefPlan { HUNTER, GUARD, OPPORTUNIST, TRAPPER }
    public enum RunPlan { CIRCLE, ZIGZAG, WANDER, SEEKER }
    public enum DuelPlan { RUSH, KITE, COUNTER }

    private static final String SPLEEF = "spleef:plan", RUN = "tntrun:plan", DUEL = "duel:plan";
    private static final String SPLEEF_HUMAN = "spleef:human", DUEL_HUMAN = "duel:human";

    private MiniLearning() {}

    // ---- Watching real players ----

    /** A real player in a Spleef round: close to someone (chasing) or keeping away. */
    public static void observeSpleef(boolean chasing) {
        BotMemory.add(SPLEEF_HUMAN, chasing ? 0 : 1, 2, 1);
    }

    /** A real player's hit in a duel: sword or bow. */
    public static void observeDuelHit(boolean bow) {
        BotMemory.add(DUEL_HUMAN, bow ? 1 : 0, 2, 1);
    }

    private static double share(String key, int index, double minSeen) {
        double[] v = BotMemory.get(key, 2);
        double t = v[0] + v[1];
        return t < minSeen ? -1 : v[index] / t;
    }

    // ---- Picking ----

    public static SpleefPlan pickSpleef(Random rng) {
        double chase = share(SPLEEF_HUMAN, 0, 40);
        double[] bonus = null;
        if (chase >= 0) {
            bonus = new double[4];
            bonus[SpleefPlan.GUARD.ordinal()] = 0.15 * chase;
            bonus[SpleefPlan.HUNTER.ordinal()] = 0.15 * (1 - chase);
        }
        return SpleefPlan.values()[BotMemory.pickPlan(SPLEEF, 4, bonus, rng)];
    }

    public static RunPlan pickRun(Random rng) {
        // SEEKER (only steps on untouched floor) did best in the offline TNT Run simulator: a small head start.
        double[] bonus = new double[4];
        bonus[RunPlan.SEEKER.ordinal()] = 0.08;
        return RunPlan.values()[BotMemory.pickPlan(RUN, 4, bonus, rng)];
    }

    public static DuelPlan pickDuel(Random rng) {
        double bow = share(DUEL_HUMAN, 1, 25);
        double[] bonus = null;
        if (bow >= 0) {
            bonus = new double[3];
            bonus[DuelPlan.RUSH.ordinal()] = 0.15 * bow;
            bonus[DuelPlan.KITE.ordinal()] = 0.12 * (1 - bow);
            bonus[DuelPlan.COUNTER.ordinal()] = 0.12 * (1 - bow);
        }
        return DuelPlan.values()[BotMemory.pickPlan(DUEL, 3, bonus, rng)];
    }

    // ---- Results ----

    /** place 1 = won, out of players. */
    public static void spleefResult(SpleefPlan plan, int place, int players) {
        if (players < 2 || plan == null) return;
        BotMemory.planResult(SPLEEF, 4, plan.ordinal(), (players - place) / (double) (players - 1));
    }

    public static void runResult(RunPlan plan, int place, int players) {
        if (players < 2 || plan == null) return;
        BotMemory.planResult(RUN, 4, plan.ordinal(), (players - place) / (double) (players - 1));
    }

    /** won, plus a little for health left / damage done (0..1 each). */
    public static void duelResult(DuelPlan plan, boolean won, double healthLeft) {
        if (plan == null) return;
        BotMemory.planResult(DUEL, 3, plan.ordinal(), (won ? 0.75 : 0) + 0.25 * healthLeft);
    }

    /** Saves without fading the other games' memories (minigame rounds are short and frequent). */
    public static void save() {
        BotMemory.fade("spleef:", 0.99);
        BotMemory.fade("tntrun:", 0.99);
        BotMemory.fade("duel:", 0.98);
        BotMemory.saveNow();
    }

    public static String summary() {
        StringBuilder sb = new StringBuilder();
        double chase = share(SPLEEF_HUMAN, 0, 40), bow = share(DUEL_HUMAN, 1, 25);
        sb.append("\n§6Spleef:§7 players chase ").append(chase < 0 ? "§8not enough seen yet" : String.format("§f%.0f%%§7 of the time", chase * 100));
        sb.append("\n§7 Plans:");
        for (SpleefPlan p : SpleefPlan.values()) {
            double[] st = BotMemory.planStats(SPLEEF, 3, p.ordinal());
            sb.append(String.format(" §f%s %.2f/%.0f§7,", p.name().toLowerCase(), st[0], st[1]));
        }
        sb.append("\n§6TNT Run:§7 plans:");
        for (RunPlan p : RunPlan.values()) {
            double[] st = BotMemory.planStats(RUN, 3, p.ordinal());
            sb.append(String.format(" §f%s %.2f/%.0f§7,", p.name().toLowerCase(), st[0], st[1]));
        }
        sb.append("\n§6Duels:§7 players use the bow for ").append(bow < 0 ? "§8not enough seen yet" : String.format("§f%.0f%%§7 of hits", bow * 100));
        sb.append("\n§7 Plans:");
        for (DuelPlan p : DuelPlan.values()) {
            double[] st = BotMemory.planStats(DUEL, 3, p.ordinal());
            sb.append(String.format(" §f%s %.2f/%.0f§7,", p.name().toLowerCase(), st[0], st[1]));
        }
        return sb.toString();
    }
}
