package com.aidan.skyserver.bots;

import com.aidan.skyserver.lobby.Activities;
import com.aidan.skyserver.lobby.ActivityDesign;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * A bot in the Spleef Pit. Spleef rounds: keeps to solid snow, picks someone (by its plan) and
 * breaks the snow under or in front of them. TNT Run rounds: never stops moving and always heads
 * for the most floor that's left, jumping small gaps. Slower reactions and shorter look-ahead on
 * easy; quick and far-sighted on hard.
 */
public final class SpleefBrain extends BotBrain {
    private MiniLearning.SpleefPlan spleefPlan;
    private MiniLearning.RunPlan runPlan;
    private int roundSeen = -1;
    private double headX = 1, headZ = 0;
    private long nextHeading = 0, nextBreak = 0, nextTurn = 0;
    private int zig = 1;

    public SpleefBrain(BotPlayer bot) {
        super(bot);
    }

    @Override
    protected LevelWorld world() {
        return new LevelWorld(level(), ActivityDesign.SPLEEF_FLOOR - 3, pos -> false, pos -> false);
    }

    public MiniLearning.SpleefPlan spleefPlan() {
        return spleefPlan;
    }

    public MiniLearning.RunPlan runPlan() {
        return runPlan;
    }

    @Override
    public String describe() {
        if (!Activities.spleefAlive(bot)) return "waiting for a Spleef round";
        return Activities.spleefMode() == 0 ? "spleef: " + (spleefPlan == null ? "?" : spleefPlan.name().toLowerCase())
                : "TNT run: " + (runPlan == null ? "?" : runPlan.name().toLowerCase());
    }

    @Override
    protected void think() {
        if (!Activities.spleefAlive(bot)) {
            roundSeen = -1;
            return;
        }
        int round = Activities.spleefRound();
        if (round != roundSeen) {
            roundSeen = round;
            spleefPlan = MiniLearning.pickSpleef(rng);
            runPlan = MiniLearning.pickRun(rng);
            nextHeading = 0;
        }
        if (Activities.spleefMode() == 1) tntRun();
        else spleef();
    }

    // =====================================================================
    // Reading the floor
    // =====================================================================

    /** The floor we're on: the top one in Spleef, whichever we're standing on in TNT Run. */
    private int floorY() {
        if (Activities.spleefMode() == 0) return ActivityDesign.SPLEEF_FLOOR;
        int i = Math.min(ActivityDesign.TNT_FLOORS - 1, ActivityDesign.tntFloorAt(bot.getY()));
        return ActivityDesign.tntFloorY(i);
    }

    private boolean floorAt(double x, double z) {
        int bx = (int) Math.floor(x), bz = (int) Math.floor(z);
        if (Math.hypot(bx + 0.5 - ActivityDesign.SPLEEF_X - 0.5, bz + 0.5 - ActivityDesign.SPLEEF_Z - 0.5) > ActivityDesign.SPLEEF_R + 0.3) return false;
        return !level().getBlockState(new BlockPos(bx, floorY(), bz)).isAir();
    }

    /** What this bot can read of the floor (Easy bots don't notice which blocks were already stepped on). */
    private final TntLogic.Floor floor = new TntLogic.Floor() {
        public boolean solid(double x, double z) {
            return floorAt(x, z);
        }

        public boolean fresh(double x, double z) {
            if (!floorAt(x, z)) return false;
            if (d() == Difficulty.EASY) return true;
            return !Activities.tntDoomed(new BlockPos((int) Math.floor(x), floorY(), (int) Math.floor(z)));
        }
    };

    private int lookAhead() {
        return switch (d()) { case EASY -> 3; case MEDIUM -> 5; case HARD -> 7; };
    }

    /** Moves along the best heading; jumps over one-block gaps. */
    private void travel(double biasX, double biasZ, double biasWeight, boolean tnt, double speed) {
        int every = switch (d()) { case EASY -> 10; case MEDIUM -> 6; case HARD -> 3; };
        if (age >= nextHeading) {
            nextHeading = age + every;
            double[] h = TntLogic.pick(floor, bot.getX(), bot.getZ(), headX, headZ, lookAhead(), tnt,
                    ActivityDesign.SPLEEF_X + 0.5, ActivityDesign.SPLEEF_Z + 0.5, ActivityDesign.SPLEEF_R,
                    biasX, biasZ, biasWeight, tnt && runPlan == MiniLearning.RunPlan.SEEKER,
                    d() == Difficulty.EASY ? 1.5 : 0, rng);
            headX = h[0];
            headZ = h[1];
        }
        move(headX, headZ, speed);
        lookDirection(yawTo(headX, headZ), 20f, 0.5);
        if (speed > 0.9 && d() != Difficulty.EASY) sprint();
        // Jump a gap right in front, if there's floor just past it.
        double x = bot.getX(), z = bot.getZ();
        if (bot.onGround() && !floorAt(x + headX * 0.9, z + headZ * 0.9) && floorAt(x + headX * 2, z + headZ * 2)) jump();
    }

    // =====================================================================
    // TNT Run
    // =====================================================================

    private void tntRun() {
        if (!Activities.spleefStarted()) return;
        if (runPlan == MiniLearning.RunPlan.ZIGZAG && age >= nextTurn) {
            zig = -zig;
            nextTurn = age + 25 + rng.nextInt(20);
        }
        double[] b = TntLogic.bias(runPlan.ordinal(), bot.getX(), bot.getZ(), ActivityDesign.SPLEEF_X + 0.5, ActivityDesign.SPLEEF_Z + 0.5, headX, headZ, zig);
        travel(b[0], b[1], b[2], true, 1.0);
    }

    // =====================================================================
    // Spleef
    // =====================================================================

    private void spleef() {
        List<ServerPlayer> others = Activities.spleefOpponents(bot);
        ServerPlayer target = pickTarget(others);
        double cx = ActivityDesign.SPLEEF_X + 0.5 - bot.getX(), cz = ActivityDesign.SPLEEF_Z + 0.5 - bot.getZ();
        if (target == null) {
            travel(cx, cz, 0.3, false, 0.6);
            return;
        }
        double tx = target.getX() - bot.getX(), tz = target.getZ() - bot.getZ();
        double dist = Math.hypot(tx, tz);
        double want = spleefPlan == MiniLearning.SpleefPlan.GUARD ? 99 : 3.0;
        if (spleefPlan == MiniLearning.SpleefPlan.GUARD) {
            // Hold the middle; only go for people who come close.
            double mid = Math.hypot(cx, cz);
            if (mid > 3) travel(cx, cz, 1.0, false, 0.8);
            else if (dist > 4) travel(tx, tz, 0.3, false, 0.3);
        } else if (dist > want) {
            travel(tx / dist, tz / dist, 1.2, false, 1.0);
        } else {
            // Close enough: hold position on solid ground (sidestep holes).
            if (!floorAt(bot.getX(), bot.getZ()) || !floorAt(bot.getX() + headX * 0.8, bot.getZ() + headZ * 0.8)) travel(-tx, -tz, 0.5, false, 0.5);
        }
        if (!Activities.spleefStarted()) return;
        // Break the snow under them (or where they're about to step).
        if (dist < 4.3 && age >= nextBreak) {
            Vec3 v = target.getDeltaMovement();
            double lead = d() == Difficulty.HARD ? 4 : d() == Difficulty.MEDIUM ? 2 : 0;
            int bx = (int) Math.floor(target.getX() + v.x * lead), bz = (int) Math.floor(target.getZ() + v.z * lead);
            if (spleefPlan == MiniLearning.SpleefPlan.TRAPPER) {
                // Cut off their escape: break the block on the side of them with the most floor (away from us).
                double best = -1;
                for (int[] o : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                    int cx2 = (int) Math.floor(target.getX()) + o[0], cz2 = (int) Math.floor(target.getZ()) + o[1];
                    if (!floorAt(cx2 + 0.5, cz2 + 0.5)) continue;
                    double away = (cx2 + 0.5 - bot.getX()) * o[0] + (cz2 + 0.5 - bot.getZ()) * o[1];
                    double room = TntLogic.density(floor, cx2 + o[0] * 2, cz2 + o[1] * 2, 2) + 0.1 * away;
                    if (room > best && bot.getEyePosition().distanceTo(new Vec3(cx2 + 0.5, ActivityDesign.SPLEEF_FLOOR + 0.5, cz2 + 0.5)) < 4.5) {
                        best = room;
                        bx = cx2;
                        bz = cz2;
                    }
                }
                // Once they're boxed in (2+ holes around them), take the block they stand on.
                int holes = 0;
                for (int[] o : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}})
                    if (!floorAt(target.getX() + o[0], target.getZ() + o[1])) holes++;
                if (holes >= 2 || best < 0) {
                    bx = (int) Math.floor(target.getX());
                    bz = (int) Math.floor(target.getZ());
                }
            }
            BlockPos pos = new BlockPos(bx, ActivityDesign.SPLEEF_FLOOR, bz);
            if (level().getBlockState(pos).isAir()) {
                // Already a hole there: take the block next to them instead.
                pos = new BlockPos((int) Math.floor(target.getX() + Math.signum(tx) * 0.6), ActivityDesign.SPLEEF_FLOOR, (int) Math.floor(target.getZ() + Math.signum(tz) * 0.6));
            }
            lookAt(Vec3.atCenterOf(pos), 1.0);
            if (aimOff(Vec3.atCenterOf(pos)) < 30 && Activities.botBreak(bot, pos)) {
                swingArm();
                nextBreak = age + switch (d()) { case EASY -> 14 + rng.nextInt(10); case MEDIUM -> 7 + rng.nextInt(6); case HARD -> 4 + rng.nextInt(3); };
            }
        }
    }

    private ServerPlayer pickTarget(List<ServerPlayer> others) {
        ServerPlayer best = null;
        double bestScore = Double.MAX_VALUE;
        for (ServerPlayer o : others) {
            double dist = bot.distanceTo(o);
            double score = dist;
            if (spleefPlan == MiniLearning.SpleefPlan.OPPORTUNIST) {
                // Prefer whoever is next to a hole or the wall.
                int holes = 0;
                for (int dx = -1; dx <= 1; dx++)
                    for (int dz = -1; dz <= 1; dz++) if (!floorAt(o.getX() + dx, o.getZ() + dz)) holes++;
                score -= holes * 2.5;
            }
            if (score < bestScore) {
                bestScore = score;
                best = o;
            }
        }
        return best;
    }
}
