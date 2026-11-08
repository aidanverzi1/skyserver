package com.aidan.skyserver.bots;

import java.util.*;

/**
 * What Skywars bots learn (plain Java). Each bot picks an opening plan every match and the plans
 * that place better are picked more often. How real players open (stay home to loot, run to the
 * middle, or rush someone) nudges the choice: against rushers it pays to gear up at home and shoot
 * them while they bridge over; against players who loot slowly it pays to rush them.
 */
public final class SwLearning {
    public enum Plan {
        MIDDLE("loot the middle, then hunt"),
        RUSH("rush the nearest player right after looting home"),
        CAMP("gear up at home and shoot anyone bridging over, then go middle");

        public final String text;

        Plan(String text) {
            this.text = text;
        }
    }

    public static final int HOME = 0, MIDDLE = 1, OUT = 2;
    private static final String PLAN = "sw:plan", OPENING = "sw:humanOpening";

    private SwLearning() {}

    /** A real player seen in the first minute: at home, in the middle, or out on the way to someone. */
    public static void observeOpening(int where) {
        BotMemory.add(OPENING, where, 3, 1);
    }

    /** Shares of [home, middle, out] for real players' openings, or null if too little seen. */
    public static double[] openingShares() {
        double[] v = BotMemory.get(OPENING, 3);
        double t = v[0] + v[1] + v[2];
        if (t < 30) return null;
        return new double[]{v[0] / t, v[1] / t, v[2] / t};
    }

    public static Plan pickPlan(Random rng) {
        double[] o = openingShares();
        double[] bonus = null;
        if (o != null) {
            bonus = new double[3];
            bonus[Plan.MIDDLE.ordinal()] = 0.15 * (1 - o[MIDDLE]); // middle is free if players don't go there
            bonus[Plan.RUSH.ordinal()] = 0.15 * o[HOME];             // catch them while they loot
            bonus[Plan.CAMP.ordinal()] = 0.15 * o[OUT];              // they come to us: shoot them on the bridge
        }
        return Plan.values()[BotMemory.pickPlan(PLAN, 3, bonus, rng)];
    }

    /** place 1 = won; players = how many started. Kills add a little. */
    public static void result(Plan plan, int place, int players, int kills) {
        if (players < 2) return;
        double r = (players - place) / (double) (players - 1) + 0.15 * kills;
        BotMemory.planResult(PLAN, 3, plan.ordinal(), r);
    }

    public static String summary() {
        StringBuilder sb = new StringBuilder();
        double[] o = openingShares();
        sb.append("\n§7 How players open: ").append(o == null ? "§8not enough seen yet"
                : String.format("§fstay home %.0f%%, go middle %.0f%%, rush someone %.0f%%", o[0] * 100, o[1] * 100, o[2] * 100));
        sb.append("\n§7 Opening plans (score / games):");
        for (Plan p : Plan.values()) {
            double[] st = BotMemory.planStats(PLAN, 3, p.ordinal());
            sb.append(String.format(" §f%s %.2f/%.0f§7,", p.name().toLowerCase(), st[0], st[1]));
        }
        return sb.toString();
    }
}
