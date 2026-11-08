package com.aidan.skyserver.lobby;

/**
 * A tick-by-tick copy of how a Minecraft player moves (sprinting, sprint-jumping, air control,
 * friction and gravity), used to check that every obstacle course jump can really be made.
 * Plain Java so it runs offline.
 *
 * Vanilla numbers (per tick): sprint ground acceleration 0.13 (0.1 x 1.3, on normal blocks),
 * air acceleration 0.026, ground friction x0.546, air drag x0.91, jump speed 0.42 up plus a
 * 0.2 sprint-jump boost forward, gravity: vy = (vy - 0.08) x 0.98. Player hitbox 0.6 wide, 1.8 tall.
 */
public final class JumpSim {
    private JumpSim() {}

    static final double HALF = 0.3, HEIGHT = 1.8;
    static final double GROUND_ACC = 0.13, AIR_ACC = 0.026, GROUND_FRICTION = 0.546, AIR_DRAG = 0.91;

    /** A landing target: a box top (x1..x2, z1..z2 at height top) or a ladder cell column. */
    public record Target(double x1, double z1, double x2, double z2, double top, boolean ladder, double ladderBottom) {
        static Target block(int x, int y, int z, double topOffset, double width) {
            double h = width / 2;
            return new Target(x + 0.5 - h, z + 0.5 - h, x + 0.5 + h, z + 0.5 + h, y + topOffset, false, 0);
        }

        static Target platform(int cx, int y, int cz) {
            return new Target(cx - 1, cz - 1, cx + 2, cz + 2, y + 1, false, 0);
        }

        static Target ladder(int x, int yBottom, int yTop, int z) {
            return new Target(x, z, x + 1, z + 1, yTop + 1, true, yBottom);
        }
    }

    /** Where the player is after landing (feet position and horizontal velocity). */
    public record State(double x, double y, double z, double vx, double vz) {}

    public record Result(boolean ok, int window, State landed) {}

    private static boolean overlaps(double x, double z, Target t) {
        return x + HALF > t.x1() && x - HALF < t.x2() && z + HALF > t.z1() && z - HALF < t.z2();
    }

    /**
     * Tries every moment to jump from the current support, running toward the target. Returns how
     * many different jump ticks work (the "timing window") and where you land with the best one.
     * ceiling: y of the ceiling block above the takeoff (head-hitter), or NaN for none.
     */
    public static Result jump(State s, Target from, Target to, double ceiling) {
        return jump(s, from, to, ceiling, 1.0);
    }

    /**
     * skill: 1.0 = frame-perfect movement; lower = a real person's slightly-off timing and aim
     * (scales the acceleration they get out of each tick).
     */
    public static Result jump(State s, Target from, Target to, double ceiling, double skill) {
        // Players can keep their momentum, or stop and line up first (careful players on small blocks).
        Result moving = jumpFrom(s, from, to, ceiling, skill);
        double cx = (from.x1() + from.x2()) / 2, cz = (from.z1() + from.z2()) / 2;
        // Standing at the back of the support (as far from the target as you can) with no speed.
        double tx = (to.x1() + to.x2()) / 2, tz = (to.z1() + to.z2()) / 2;
        double dx = tx - cx, dz = tz - cz, len = Math.max(1e-9, Math.hypot(dx, dz));
        double back = Math.max(0, Math.min((from.x2() - from.x1()) / 2, (from.z2() - from.z1()) / 2) - HALF + 0.29);
        Result still = jumpFrom(new State(cx - dx / len * back, s.y(), cz - dz / len * back, 0, 0), from, to, ceiling, skill);
        return still.window() > moving.window() ? still : moving;
    }

    private static Result jumpFrom(State s, Target from, Target to, double ceiling, double skill) {
        double tx = (to.x1() + to.x2()) / 2, tz = (to.z1() + to.z2()) / 2;
        int window = 0;
        State bestLanding = null;
        // Walk/sprint along the support: try jumping on each ground tick while still on it.
        double x = s.x(), z = s.z(), vx = s.vx(), vz = s.vz();
        for (int ground = 0; ground < 40; ground++) {
            if (!overlaps(x, z, from)) break; // walked off the edge
            State land = fly(x, s.y(), z, vx, vz, tx, tz, to, ceiling, skill);
            if (land != null) {
                window++;
                if (bestLanding == null) bestLanding = land;
                else bestLanding = land; // later jumps land further in: keep the last one that works
            }
            // One more ground tick of sprinting toward the target.
            double dx = tx - x, dz = tz - z, len = Math.max(1e-9, Math.hypot(dx, dz));
            vx += dx / len * GROUND_ACC * skill;
            vz += dz / len * GROUND_ACC * skill;
            x += vx;
            z += vz;
            vx *= GROUND_FRICTION;
            vz *= GROUND_FRICTION;
        }
        return new Result(window > 0, window, bestLanding);
    }

    /** One sprint-jump from (x, y, z). Returns the landing state, or null if it misses. */
    static State fly(double x, double y, double z, double vx, double vz, double tx, double tz, Target to, double ceiling, double skill) {
        double dx = tx - x, dz = tz - z, len = Math.max(1e-9, Math.hypot(dx, dz));
        double fx = dx / len, fz = dz / len;
        // Jump tick: sprint-jump boost, ground acceleration, then ground friction.
        double vy = 0.42;
        vx += fx * 0.2 + fx * GROUND_ACC * skill;
        vz += fz * 0.2 + fz * GROUND_ACC * skill;
        for (int t = 0; t < 60; t++) {
            double ny = y + vy;
            if (!Double.isNaN(ceiling) && ny + HEIGHT > ceiling && overlapsCeiling(x, z, ceiling)) {
                ny = ceiling - HEIGHT;
                vy = 0;
            }
            double nx = x + vx, nz = z + vz;
            if (to.ladder()) {
                // On a ladder once your feet are inside its block (between its bottom and top).
                if (nx > to.x1() && nx < to.x2() && nz > to.z1() && nz < to.z2() && ny >= to.ladderBottom() - 0.5 && ny <= to.top()) {
                    return new State(to.x1() + 0.5, to.top(), to.z1() + 0.5, 0, 0);
                }
            } else {
                // Landing: feet pass the top going down while over the block.
                if (vy <= 0 && y >= to.top() - 1e-6 && ny <= to.top() && overlaps(nx, nz, to)) {
                    return new State(nx, to.top(), nz, vx * AIR_DRAG, vz * AIR_DRAG);
                }
                // Hit the side of the target (below its top while over it): miss.
                if (ny < to.top() - 0.05 && overlaps(nx, nz, to)) return null;
            }
            x = nx;
            y = ny;
            z = nz;
            // Friction for this tick (ground on the jump tick, air after), then next tick's air control.
            double f = t == 0 ? GROUND_FRICTION : AIR_DRAG;
            vx = vx * f + fx * AIR_ACC * skill;
            vz = vz * f + fz * AIR_ACC * skill;
            vy = (vy - 0.08) * 0.98;
            if (t > 0 && vy < 0 && y < to.top() - 3) return null; // fell past it
        }
        return null;
    }

    private static boolean overlapsCeiling(double x, double z, double ceiling) {
        return true; // ceilings in the courses always cover the takeoff block
    }
}
