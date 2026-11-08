package com.aidan.skyserver.bots;

import java.util.*;

/**
 * What Gold Wars bots learn, per map (plain Java, shared with the simulator).
 *
 * From real players (watched every 2 seconds):
 *  - which routes they attack along (north/south, center/flank) -> defenders and interceptors
 *    set up where people actually come from;
 *  - which vault they stand guard at -> attackers send more pressure to the other one.
 *
 * From match results:
 *  - which team plan wins more on this map (aggressive / balanced / defensive);
 *  - which routes get attackers killed (route memory, deaths to real players count double).
 */
public final class GwLearning {
    public enum Plan {
        AGGRESSIVE("aggressive: fewer interceptors, more attackers"),
        BALANCED("balanced"),
        DEFENSIVE("defensive: an extra defender");

        public final String text;

        Plan(String text) {
            this.text = text;
        }
    }

    private static final int[] LANES = {-2, -1, 1, 2};
    /** Below this many (faded) samples, what we saw about players is too little to act on. */
    private static final double MIN_SAMPLES = 15;

    private GwLearning() {}

    private static String k(String map, String what) {
        return "gw:" + map + ":" + what;
    }

    private static int laneIndex(int lane) {
        return switch (lane) { case -3, -2 -> 0; case -1 -> 1; case 1 -> 2; default -> 3; };
    }

    /** Which route a spot is on, by its z (north < 0 < south; flank further out). */
    public static int laneAt(GwState s, double z) {
        double edge = (s.centerLaneZ() + s.flankLaneZ()) / 2.0;
        int sign = z < 0 ? -1 : 1;
        return sign * (Math.abs(z) >= edge ? 2 : 1);
    }

    private static final Map<UUID, double[]> LAST_SEEN = new HashMap<>();

    /** Watches real players. Call every tick (it only looks every 2 seconds). */
    public static void observe(GwState s) {
        if (s.buildPhase || s.tick % 40 != 0) return;
        for (GwState.Unit u : s.units) {
            if (u.isBot() || !u.alive) continue;
            double[] last = LAST_SEEN.put(u.id, new double[]{u.x, u.z, s.tick});
            // Attacking: crossing the middle part of the enemy half (where the routes are apart).
            double into = -u.x * u.side;
            if (into > 6 && into < 30) {
                BotMemory.add(k(s.map, "humanLane"), laneIndex(laneAt(s, u.z)), 4, 1);
            }
            // Guarding: standing (not just walking past) near one of their own vaults.
            boolean still = last != null && s.tick - last[2] <= 60 && Math.hypot(u.x - last[0], u.z - last[1]) < 4;
            if (!still) continue;
            for (int v = 0; v < 2; v++) {
                int[] p = s.vault(u.side, v);
                if (s.vaultCount(u.side, v) > 0 && u.dist(p[0], p[1], p[2]) < 12) BotMemory.add(k(s.map, "humanDefend"), v, 2, 1);
            }
        }
    }

    /** A real player broke gold in vault v (0 north, 1 south). */
    public static void humanBrokeGold(String map, int vault) {
        BotMemory.add(k(map, "humanGold"), vault, 2, 1);
    }

    /**
     * Share of real players' attacks that come at the north side (vault 0), or -1 if we haven't
     * seen enough yet. Gold they broke counts more than just walking there.
     */
    public static double humanNorthShare(String map) {
        double[] lanes = BotMemory.get(k(map, "humanLane"), 4);
        double[] gold = BotMemory.get(k(map, "humanGold"), 2);
        double north = lanes[0] + lanes[1] + gold[0] * 4, south = lanes[2] + lanes[3] + gold[1] * 4;
        if (north + south < MIN_SAMPLES) return -1;
        return north / (north + south);
    }

    /** On one side (vault 0 north / 1 south): share of real players' attacks that use the flank, or -1. */
    public static double humanFlankShare(String map, int vault) {
        double[] lanes = BotMemory.get(k(map, "humanLane"), 4);
        double flank = vault == 0 ? lanes[0] : lanes[3], center = vault == 0 ? lanes[1] : lanes[2];
        if (flank + center < MIN_SAMPLES / 2) return -1;
        return flank / (flank + center);
    }

    /** Share of real players' guarding done at the north vault, or -1. */
    public static double humanDefendNorthShare(String map) {
        double[] d = BotMemory.get(k(map, "humanDefend"), 2);
        if (d[0] + d[1] < MIN_SAMPLES) return -1;
        return d[0] / (d[0] + d[1]);
    }

    // ---- Team plan ----

    public static Plan pickPlan(String map, Random rng) {
        return Plan.values()[BotMemory.pickPlan(k(map, "plan"), 3, null, rng)];
    }

    /** Called at the end of a match for a team that had bots. */
    public static void planResult(String map, Plan plan, double result) {
        BotMemory.planResult(k(map, "plan"), 3, plan.ordinal(), result);
    }

    public static String summary(String map) {
        StringBuilder sb = new StringBuilder();
        double[] lanes = BotMemory.get(k(map, "humanLane"), 4);
        double total = lanes[0] + lanes[1] + lanes[2] + lanes[3];
        sb.append("\n§7 Where players attack:");
        if (total < MIN_SAMPLES) {
            sb.append(" §8not enough seen yet");
        } else {
            for (int i = 0; i < 4; i++) sb.append(String.format(" §f%s %.0f%%§7,", LaneMemory.laneName(LANES[i]), 100 * lanes[i] / total));
        }
        double dn = humanDefendNorthShare(map);
        sb.append("\n§7 Where players guard: ").append(dn < 0 ? "§8not enough seen yet"
                : String.format("§fnorth vault %.0f%%, south vault %.0f%%", dn * 100, (1 - dn) * 100));
        sb.append("\n§7 Team plans (win score / games):");
        for (Plan p : Plan.values()) {
            double[] st = BotMemory.planStats(k(map, "plan"), 3, p.ordinal());
            sb.append(String.format(" §f%s %.2f/%.0f§7,", p.name().toLowerCase(), st[0], st[1]));
        }
        return sb.toString();
    }
}
