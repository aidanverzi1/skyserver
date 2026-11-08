package com.aidan.skyserver.bots;

import java.util.Random;

/**
 * TNT Run and Spleef movement decisions in plain Java (shared by the Spleef Pit bots and the
 * offline simulator used to train them). The pit is a circle of radius r around (cx, cz).
 *
 * TNT Run plans:
 *  CIRCLE  - big loops around the middle
 *  ZIGZAG  - weaves left and right
 *  WANDER  - just follows the most floor
 *  SEEKER  - heads for the biggest untouched patch of floor (and away from other players' trails)
 */
public final class TntLogic {
    private TntLogic() {}

    /** What the bot can see of the floor it's standing on. */
    public interface Floor {
        boolean solid(double x, double z);

        /** Solid and not about to drop (nobody stepped on it yet). Defaults to solid. */
        default boolean fresh(double x, double z) {
            return solid(x, z);
        }
    }

    public static final int CIRCLE = 0, ZIGZAG = 1, WANDER = 2, SEEKER = 3;
    /** SEEKER tuning (found with the offline simulator). */
    public static double SEEK_WEIGHT = 0;
    public static int SEEK_AHEAD = 5, SEEK_RADIUS = 3;
    /** SEEKER: count only floor nobody has stepped on yet, and look further ahead. */
    public static boolean FRESH = true;
    public static int SEEK_EXTRA = 0;
    /** Smart bots (Medium/Hard) avoid floor someone already stepped on, whatever their plan. */
    public static boolean ALL_FRESH = true;

    /** How good it is to head this way: solid floor ahead counts, holes and the wall count against. */
    public static double headingScore(Floor f, double x, double z, double dx, double dz, int lookAhead, boolean tnt,
                                      double cx, double cz, double r) {
        return headingScore(f, x, z, dx, dz, lookAhead, tnt, cx, cz, r, false);
    }

    public static double headingScore(Floor f, double x, double z, double dx, double dz, int lookAhead, boolean tnt,
                                      double cx, double cz, double r, boolean useFresh) {
        double score = 0;
        boolean gap = false;
        for (int k = 1; k <= lookAhead; k++) {
            double px = x + dx * k, pz = z + dz * k;
            boolean solid = useFresh ? f.fresh(px, pz) : f.solid(px, pz);
            if (!solid) {
                if (k == 1) score -= 6;      // a hole right in front
                if (gap) return score - 4;  // two in a row: can't jump that
                gap = true;
                continue;
            }
            gap = false;
            score += 1.0 / k + (tnt ? 0.4 : 0.2);
            if (useFresh ? f.fresh(px - dz, pz + dx) : f.solid(px - dz, pz + dx)) score += 0.15;
            if (useFresh ? f.fresh(px + dz, pz - dx) : f.solid(px + dz, pz - dx)) score += 0.15;
        }
        double edge = Math.hypot(x + dx * 2 - cx, z + dz * 2 - cz);
        if (edge > r - 1.5) score -= 1.5;
        return score;
    }

    /** How much untouched floor there is around a spot (0..1). */
    public static double density(Floor f, double x, double z, int radius) {
        int solid = 0, all = 0;
        for (int dx = -radius; dx <= radius; dx++)
            for (int dz = -radius; dz <= radius; dz++) {
                if (dx * dx + dz * dz > radius * radius) continue;
                all++;
                if (f.solid(x + dx, z + dz)) solid++;
            }
        return all == 0 ? 0 : solid / (double) all;
    }

    /**
     * Picks a new heading. bias = the plan's preferred direction (weight w); randomness = how sloppy
     * (Easy bots). Returns {dx, dz}.
     */
    public static double[] pick(Floor f, double x, double z, double headX, double headZ, int lookAhead, boolean tnt,
                                double cx, double cz, double r, double biasX, double biasZ, double w,
                                boolean seek, double randomness, Random rng) {
        double best = -1e9, bx = headX, bz = headZ;
        for (int i = 0; i < 16; i++) {
            double a = i * Math.PI / 8;
            double dx = Math.cos(a), dz = Math.sin(a);
            double s = headingScore(f, x, z, dx, dz, seek ? lookAhead + SEEK_EXTRA : lookAhead, tnt, cx, cz, r, tnt && FRESH && (seek || ALL_FRESH)) + w * (dx * biasX + dz * biasZ);
            s += 0.6 * (dx * headX + dz * headZ); // don't flip back and forth
            if (seek) s += SEEK_WEIGHT * density(f, x + dx * SEEK_AHEAD, z + dz * SEEK_AHEAD, SEEK_RADIUS);
            if (randomness > 0) s += rng.nextDouble() * randomness;
            if (s > best) {
                best = s;
                bx = dx;
                bz = dz;
            }
        }
        return new double[]{bx, bz};
    }

    /** The plan's preferred direction: {x, z, weight}. zig = +1/-1, flips now and then for ZIGZAG. */
    public static double[] bias(int plan, double x, double z, double cx, double cz, double headX, double headZ, double zig) {
        double tx = cx - x, tz = cz - z, len = Math.max(0.01, Math.hypot(tx, tz));
        return switch (plan) {
            case CIRCLE -> new double[]{-tz / len, tx / len, 0.8};
            case ZIGZAG -> new double[]{-headZ * zig, headX * zig, 0.6};
            case SEEKER -> new double[]{tx / len, tz / len, 0.15}; // a slight pull to the middle (most room)
            default -> new double[]{0, 0, 0};
        };
    }
}
