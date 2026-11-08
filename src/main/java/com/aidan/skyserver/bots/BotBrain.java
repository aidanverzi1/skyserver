package com.aidan.skyserver.bots;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Random;
import java.util.function.Predicate;

/**
 * The shared "body" of every bot: walking along paths, aiming, fighting with swords and bows,
 * mining and placing blocks. Each game extends this with its own decisions in {@link #think()}.
 */
public abstract class BotBrain {
    protected final BotPlayer bot;
    protected final Random rng = new Random();
    protected long age = 0;

    // Inputs for this tick (applied after think()).
    private float forward, strafe;
    private boolean jump, sprint, sneak;
    private boolean trustMove;      // path says this move is safe (drops, bridges)
    private boolean aimedThisTick;

    // Aim.
    private float wantYaw, wantPitch;
    private double wobbleYaw, wobblePitch;
    private long nextWobble;

    // Path following.
    private List<Pathfinder.Step> path = List.of();
    private int pathIndex = 0;
    private BlockPos pathGoal = null;
    private long pathAge = 0;
    private Vec3 progressPos = Vec3.ZERO;
    private int stuckTicks = 0;
    /** Ticks left of "step back to the middle of my block" after getting caught on a corner (fence posts...). */
    private int recenter = 0;
    private Vec3 recenterTo = null;
    private int failedPaths = 0;
    private int totalFailedPaths = 0;
    private long nextSearch = 0;

    // Mining and placing.
    private BlockPos miningPos = null;
    private float miningProgress = 0;
    private long nextPlace = 0;

    // Combat.
    private LivingEntity target = null;
    private long targetSince = 0;
    private int strafeDir = 1;
    private long nextStrafeFlip = 0;
    private int drawTicks = 0;
    private boolean resprint = false;

    protected BotBrain(BotPlayer bot) {
        this.bot = bot;
        this.wantYaw = bot.getYRot();
    }

    protected Difficulty d() {
        return bot.difficulty();
    }

    protected ServerLevel level() {
        return (ServerLevel) bot.level();
    }

    /** What the bot is doing right now, for /bots debug. */
    public String describe() {
        return "";
    }

    /** The game's decisions for this tick. */
    protected abstract void think();

    /** Rules for the pathfinder: what the bot may break and place right now. */
    protected abstract LevelWorld world();

    final void tick() {
        age++;
        forward = strafe = 0;
        jump = sprint = sneak = trustMove = aimedThisTick = false;
        if (bot.isInWater() || bot.isInLava()) jump = true;
        think();
        unstick();
        apply();
    }

    // ---- Getting free of fences, walls, lamp posts and other thin blocks ----
    private Vec3 lastTickPos = null;
    private int blockedTicks = 0, escapeTicks = 0, escapesInARow = 0;
    private long lastEscape = -1000;
    private Vec3 escapeDir = Vec3.ZERO;
    private BlockPos breakFree = null;

    /**
     * When the bot pushes into something and doesn't move for over half a second (usually a fence
     * post or a wall corner it clipped), it backs off to the middle of its block and slides around
     * the obstacle, then searches a fresh path. If that keeps failing and it's allowed to break the
     * block (Gold Wars maps), it breaks it - the same thing a player would do.
     */
    private void unstick() {
        Vec3 pos = bot.position();
        boolean wantsToMove = forward != 0 || strafe != 0;
        double moved = lastTickPos == null ? 1 : Math.hypot(pos.x - lastTickPos.x, pos.z - lastTickPos.z);
        lastTickPos = pos;
        if (miningPos != null && breakFree == null) { blockedTicks = 0; return; }

        if (breakFree != null) {
            if (mine(breakFree)) {
                breakFree = null;
                escapesInARow = 0;
                clearPath();
            }
            forward = strafe = 0;
            return;
        }
        if (escapeTicks > 0) {
            escapeTicks--;
            move(escapeDir.x, escapeDir.z, 0.8);
            sprint = false;
            if (escapeTicks == 0) clearPath();
            return;
        }
        if (wantsToMove && bot.horizontalCollision && moved < 0.03) blockedTicks++;
        else blockedTicks = Math.max(0, blockedTicks - 2);
        if (blockedTicks < 12) {
            if (age - lastEscape > 200) escapesInARow = 0;
            return;
        }
        blockedTicks = 0;
        BlockPos obstacle = touchingObstacle();
        if (age - lastEscape < 120) escapesInARow++;
        else escapesInARow = 1;
        lastEscape = age;
        if (obstacle != null && escapesInARow >= 3 && !level().getBlockState(obstacle).isCollisionShapeFullBlock(level(), obstacle)
                && world().breakable(obstacle.getX(), obstacle.getY(), obstacle.getZ())) {
            breakFree = obstacle;
            return;
        }
        // Away from the obstacle and back toward the middle of our block, turned a bit so we slide around it.
        Vec3 center = Vec3.atBottomCenterOf(bot.blockPosition());
        Vec3 away = obstacle == null ? Vec3.ZERO : pos.subtract(Vec3.atCenterOf(obstacle)).multiply(1, 0, 1);
        Vec3 toCenter = center.subtract(pos).multiply(1, 0, 1);
        Vec3 dir = away.normalize().add(toCenter.scale(2));
        double turn = Math.toRadians((escapesInARow % 2 == 0 ? 1 : -1) * 55);
        if (dir.lengthSqr() < 1e-4) dir = new Vec3(Math.cos(age), 0, Math.sin(age));
        escapeDir = new Vec3(dir.x * Math.cos(turn) - dir.z * Math.sin(turn), 0, dir.x * Math.sin(turn) + dir.z * Math.cos(turn));
        escapeTicks = 10;
        move(escapeDir.x, escapeDir.z, 0.8);
    }

    /** The block (fence, wall, post, pane...) the bot is pressing against, or null. */
    private BlockPos touchingObstacle() {
        net.minecraft.world.phys.AABB box = bot.getBoundingBox().inflate(0.12, 0, 0.12);
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        ServerLevel level = level();
        for (int y = (int) Math.floor(box.minY + 0.05); y <= (int) Math.floor(box.maxY - 0.05); y++)
            for (int x = (int) Math.floor(box.minX); x <= (int) Math.floor(box.maxX); x++)
                for (int z = (int) Math.floor(box.minZ); z <= (int) Math.floor(box.maxZ); z++) {
                    BlockPos p = new BlockPos(x, y, z);
                    BlockState s = level.getBlockState(p);
                    if (s.isAir()) continue;
                    var shape = s.getCollisionShape(level, p);
                    if (shape.isEmpty()) continue;
                    boolean hit = false;
                    for (net.minecraft.world.phys.AABB b : shape.toAabbs()) if (b.move(p).intersects(box)) { hit = true; break; }
                    if (!hit) continue;
                    double d = Vec3.atCenterOf(p).distanceToSqr(bot.position().add(0, 0.5, 0));
                    // Thin blocks are the usual culprits, so prefer them.
                    if (!s.isCollisionShapeFullBlock(level, p)) d -= 4;
                    if (d < bestDist) { bestDist = d; best = p; }
                }
        return best;
    }

    static void stopInputs(BotPlayer bot) {
        bot.zza = 0;
        bot.xxa = 0;
        bot.setJumping(false);
        bot.setSprinting(false);
        bot.setShiftKeyDown(false);
    }

    /** Forget the current path, target and mining (e.g. after respawning). */
    protected void resetBody() {
        path = List.of();
        pathGoal = null;
        target = null;
        cancelMining();
        if (bot.isUsingItem()) bot.stopUsingItem();
        drawTicks = 0;
    }

    // =====================================================================
    // Inputs and aiming
    // =====================================================================

    private void apply() {
        // Turn the head toward where we want to look, at the bot's turn speed.
        if (!aimedThisTick && (forward != 0 || strafe != 0)) {
            // Not aiming at anything: look where we're walking.
            Vec3 m = worldMove();
            if (m.lengthSqr() > 0.01) {
                wantYaw = yawTo(m.x, m.z);
                wantPitch = 0;
            }
        }
        float yaw = approachAngle(bot.getYRot(), wantYaw, (float) d().turnSpeed);
        float pitch = approach(bot.getXRot(), wantPitch, (float) d().turnSpeed);

        // Edge safety: never walk off into the void unless the path says this drop is fine.
        if (!trustMove && (forward != 0 || strafe != 0)) {
            Vec3 m = worldMove(yaw);
            if (!safeAt(bot.position().add(m.x * 0.9, 0, m.z * 0.9))) {
                forward = 0;
                strafe = 0;
                sprint = false;
            }
        }

        bot.setYRot(yaw);
        bot.setXRot(pitch);
        bot.setYHeadRot(yaw);
        bot.zza = forward;
        bot.xxa = strafe;
        bot.setJumping(jump);
        bot.setShiftKeyDown(sneak);
        boolean wantSprint = sprint && forward > 0.6f && !sneak;
        if (resprint) {
            wantSprint = false; // let go of sprint for a tick after a hit, so the next hit knocks back further
            resprint = false;
        }
        bot.setSprinting(wantSprint);
    }

    /** Current inputs as a world-space direction. */
    private Vec3 worldMove() {
        return worldMove(bot.getYRot());
    }

    private Vec3 worldMove(float yawDeg) {
        double yaw = Math.toRadians(yawDeg);
        double fx = -Math.sin(yaw), fz = Math.cos(yaw);   // forward
        double lx = Math.cos(yaw), lz = Math.sin(yaw);    // left
        return new Vec3(fx * forward + lx * strafe, 0, fz * forward + lz * strafe);
    }

    /** Move in a world direction (x, z) at the given speed (0..1), whatever way the bot is facing. */
    protected void move(double x, double z, double speed) {
        double len = Math.sqrt(x * x + z * z);
        if (len < 1e-4) return;
        x /= len;
        z /= len;
        double yaw = Math.toRadians(bot.getYRot());
        double fx = -Math.sin(yaw), fz = Math.cos(yaw);
        double lx = Math.cos(yaw), lz = Math.sin(yaw);
        forward = (float) ((x * fx + z * fz) * speed);
        strafe = (float) ((x * lx + z * lz) * speed);
    }

    protected void moveTo(Vec3 point, double speed) {
        move(point.x - bot.getX(), point.z - bot.getZ(), speed);
    }

    protected void jump() {
        jump = true;
    }

    protected void sprint() {
        sprint = true;
    }

    protected void sneak() {
        sneak = true;
    }

    /** Look at a point, with the bot's aim wobble (scaled by errorScale). */
    protected void lookAt(Vec3 point, double errorScale) {
        Vec3 eye = bot.getEyePosition();
        double dx = point.x - eye.x, dy = point.y - eye.y, dz = point.z - eye.z;
        lookDirection(yawTo(dx, dz), (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz))), errorScale);
    }

    protected void lookDirection(float yaw, float pitch, double errorScale) {
        if (age >= nextWobble) {
            double e = d().aimError * errorScale;
            wobbleYaw = (rng.nextDouble() * 2 - 1) * e;
            wobblePitch = (rng.nextDouble() * 2 - 1) * e * 0.6;
            nextWobble = age + 6 + rng.nextInt(10);
        }
        wantYaw = (float) (yaw + wobbleYaw * errorScale);
        wantPitch = (float) Math.max(-90, Math.min(90, pitch + wobblePitch * errorScale));
        aimedThisTick = true;
    }

    /** How far (degrees) the bot's current view is from looking at the point. */
    protected double aimOff(Vec3 point) {
        Vec3 eye = bot.getEyePosition();
        Vec3 to = point.subtract(eye).normalize();
        Vec3 look = bot.getLookAngle();
        return Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, to.dot(look)))));
    }

    protected static float yawTo(double dx, double dz) {
        return (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
    }

    private static float approachAngle(float from, float to, float max) {
        float diff = wrap(to - from);
        if (diff > max) diff = max;
        if (diff < -max) diff = -max;
        return from + diff;
    }

    private static float approach(float from, float to, float max) {
        float diff = to - from;
        return from + Math.max(-max, Math.min(max, diff));
    }

    private static float wrap(float a) {
        a %= 360f;
        if (a >= 180f) a -= 360f;
        if (a < -180f) a += 360f;
        return a;
    }

    /** Is there safe ground under the whole straight line from us to the point? */
    protected boolean groundTo(Vec3 to) {
        Vec3 from = bot.position();
        double len = Math.hypot(to.x - from.x, to.z - from.z);
        for (double t = 0.5; t < len; t += 0.5) {
            double f = t / len;
            if (!safeAt(new Vec3(from.x + (to.x - from.x) * f, from.y, from.z + (to.z - from.z) * f))) return false;
        }
        return true;
    }

    /** Is there ground within 3 blocks under this spot (and no lava)? */
    protected boolean safeAt(Vec3 p) {
        LevelWorld w = world();
        int x = (int) Math.floor(p.x), z = (int) Math.floor(p.z);
        int y = (int) Math.floor(bot.getY() + 0.01);
        if (w.dangerous(x, y, z)) return false;
        for (int k = 0; k <= 3; k++) {
            if (w.standable(x, y - 1 - k, z)) return !w.dangerous(x, y - 1 - k, z);
            if (!w.passable(x, y - 1 - k, z)) return false;
        }
        return w.climbable(x, y, z);
    }

    // =====================================================================
    // Path following
    // =====================================================================

    public enum PathStatus { MOVING, ARRIVED, STUCK }

    /**
     * Walks toward a goal using the pathfinder. Call every tick. The path is recomputed when the
     * goal moves, the path runs out, or the bot gets stuck.
     */
    private PathStatus lastStatus = PathStatus.ARRIVED;
    private boolean lastComplete = true;

    /** How the last goTo went (MOVING / ARRIVED / STUCK). */
    protected PathStatus pathStatus() {
        return lastStatus;
    }

    /** The path state in a few words, for logs: status, step done/total, goal, completeness, failures. */
    public String pathDebug() {
        String goal = pathGoal == null ? "-" : pathGoal.getX() + "," + pathGoal.getY() + "," + pathGoal.getZ();
        int bridges = 0;
        for (int i = pathIndex; i < path.size(); i++) if (path.get(i).move() == Pathfinder.Move.BRIDGE) bridges++;
        return "path " + lastStatus + " " + Math.min(pathIndex, path.size()) + "/" + path.size() + (lastComplete ? "" : " PARTIAL")
                + " goal " + goal + " bridgesLeft " + bridges + " failed " + totalFailedPaths;
    }

    protected PathStatus goTo(BlockPos goalPos, Pathfinder.Goal goal, Pathfinder.Options options) {
        PathStatus s = goTo0(goalPos, goal, options);
        lastStatus = s;
        return s;
    }

    private PathStatus goTo0(BlockPos goalPos, Pathfinder.Goal goal, Pathfinder.Options options) {
        BlockPos feet = bot.blockPosition();
        if (bot.isInWater() && !Pathfinder.canStand(world(), feet.getX(), feet.getY(), feet.getZ())) {
            swimToShore(goalPos);
            return PathStatus.MOVING;
        }
        if (shore != null) {
            shore = null;
            clearPath(); // just climbed out: plan again from dry land
        }
        if (goal.reached(feet.getX(), feet.getY(), feet.getZ())) {
            path = List.of();
            return PathStatus.ARRIVED;
        }
        boolean goalMoved = pathGoal == null || pathGoal.distManhattan(goalPos) > 3;
        boolean done = pathIndex >= path.size();
        boolean stale = age - pathAge > 200;
        boolean retryOk = goalMoved || age >= nextSearch; // after a failed search, wait a second before trying again
        if ((goalMoved || done || stale) && retryOk && BotManager.takePathToken()) {
            Pathfinder.Result r = Pathfinder.find(world(), feet.getX(), feet.getY(), feet.getZ(), goal, options);
            path = r.steps;
            lastComplete = r.complete;
            pathIndex = 0;
            pathGoal = goalPos;
            pathAge = age;
            stuckTicks = 0;
            progressPos = bot.position();
            if (path.isEmpty()) {
                failedPaths++;
                totalFailedPaths++;
                nextSearch = age + 20;
                // No way there. Only step straight at it if it's right next to us; otherwise
                // report STUCK so the decision code picks something else (instead of walking
                // into a wall or off an edge).
                if (bot.position().distanceTo(Vec3.atBottomCenterOf(goalPos)) < 3) moveTo(Vec3.atBottomCenterOf(goalPos), 1.0);
                return PathStatus.STUCK;
            }
            failedPaths = 0;
        }
        if (pathIndex >= path.size()) {
            // Waiting for a path search (they're shared between bots, a few per tick).
            if (bot.position().distanceTo(Vec3.atBottomCenterOf(goalPos)) < 3) moveTo(Vec3.atBottomCenterOf(goalPos), 1.0);
            else lookAt(Vec3.atCenterOf(goalPos).add(0, 1, 0), 0);
            return PathStatus.MOVING;
        }
        followPath();
        return PathStatus.MOVING;
    }

    private BlockPos shore;
    private long shoreAge = -1000;

    /**
     * In deep water (a bridge was blown up under us...): the pathfinder can't plan from here, so
     * swim - keep jumping to stay up - to the nearest dry spot, leaning toward where we're going.
     */
    private void swimToShore(BlockPos goalPos) {
        if (shore == null || age - shoreAge > 40) {
            shoreAge = age;
            LevelWorld w = world();
            BlockPos feet = bot.blockPosition();
            double best = 1e9;
            BlockPos pick = null;
            for (int dx = -7; dx <= 7; dx++)
                for (int dz = -7; dz <= 7; dz++)
                    for (int dy = -1; dy <= 2; dy++) {
                        int x = feet.getX() + dx, y = feet.getY() + dy, z = feet.getZ() + dz;
                        if (!Pathfinder.canStand(w, x, y, z)) continue;
                        if (level().getBlockState(new BlockPos(x, y, z)).getFluidState().isSource()) continue;
                        double d = Math.sqrt(dx * dx + dy * dy * 2 + dz * dz);
                        double toGoal = Math.sqrt(new BlockPos(x, y, z).distSqr(goalPos));
                        double score = d + 0.4 * toGoal;
                        if (score < best) {
                            best = score;
                            pick = new BlockPos(x, y, z);
                        }
                    }
            shore = pick;
        }
        if (shore == null) {
            // Nothing dry nearby: swim toward the goal and hope.
            moveTo(Vec3.atBottomCenterOf(goalPos), 1.0);
        } else {
            moveTo(Vec3.atBottomCenterOf(shore), 1.0);
            lookAt(Vec3.atCenterOf(shore), 0.5);
        }
        jump(); // swimming up / climbing out
    }

    /** How many path searches have found nothing so far (goes up when a goal is unreachable). */
    protected int failedPathCount() {
        return totalFailedPaths;
    }

    protected boolean hasPath() {
        return pathIndex < path.size();
    }

    protected void clearPath() {
        path = List.of();
        pathGoal = null;
    }

    private void followPath() {
        if (recenter > 0 && recenterTo != null) {
            // Caught on a corner: back off to the middle of the block we were on, then carry on.
            recenter--;
            moveTo(recenterTo, 0.8);
            if (bot.position().distanceToSqr(recenterTo.x, bot.getY(), recenterTo.z) < 0.04) recenter = 0;
            return;
        }
        Pathfinder.Step step = path.get(pathIndex);
        BlockPos next = new BlockPos(step.x(), step.y(), step.z());
        Vec3 center = Vec3.atBottomCenterOf(next);
        double dx = center.x - bot.getX(), dz = center.z - bot.getZ();
        double flat = Math.sqrt(dx * dx + dz * dz);
        double dy = step.y() - bot.getY();

        // Reached this step?
        boolean closeY = switch (step.move()) {
            case CLIMB_UP -> bot.getY() >= step.y() - 0.05;
            case PILLAR -> bot.onGround() && bot.getY() >= step.y() - 0.05;
            case CLIMB_DOWN, DROP -> bot.getY() <= step.y() + 0.5;
            default -> Math.abs(dy) < 1.0;
        };
        if (flat < 0.35 && closeY) {
            pathIndex++;
            if (pathIndex >= path.size()) return;
            step = path.get(pathIndex);
            next = new BlockPos(step.x(), step.y(), step.z());
            center = Vec3.atBottomCenterOf(next);
            dx = center.x - bot.getX();
            dz = center.z - bot.getZ();
            flat = Math.sqrt(dx * dx + dz * dz);
        }

        trustMove = true;
        LevelWorld w = world();
        switch (step.move()) {
            case BRIDGE -> {
                if (!w.standable(next.getX(), next.getY() - 1, next.getZ())) {
                    // Place the block first, standing still and crouching at the edge.
                    sneak();
                    BlockPos support = bot.blockPosition().below();
                    Direction dir = horizontal(next.getX() - support.getX(), next.getZ() - support.getZ());
                    Vec3 face = Vec3.atCenterOf(support).add(dir.getStepX() * 0.5, 0, dir.getStepZ() * 0.5);
                    lookAt(face, 0.2);
                    if (age >= nextPlace && aimOff(face) < 35) {
                        nextPlace = age + bridgeDelay();
                        if (!placeAgainst(support, dir)) {
                            clearPath(); // out of blocks or not allowed: find another way
                        }
                    }
                    // Creep toward the edge while waiting.
                    if (flat > 0.9) move(dx, dz, 0.3);
                    return;
                }
                move(dx, dz, 0.6);
            }
            case PILLAR -> {
                BlockPos under = next.below(); // where we were standing
                if (!w.standable(under.getX(), under.getY(), under.getZ())) {
                    // Center on the spot, jump, and place a block under our feet at the top of the jump.
                    move(dx, dz, flat > 0.15 ? 0.3 : 0);
                    lookDirection(bot.getYRot(), 90f, 0);
                    if (bot.onGround()) jump();
                    if (bot.getY() >= under.getY() + 1.0 && age >= nextPlace) {
                        nextPlace = age + bridgeDelay();
                        if (!placeAgainst(under.below(), Direction.UP)) clearPath();
                    }
                    return;
                }
                move(dx, dz, 0.3);
            }
            case DIG -> {
                BlockPos head = next.above();
                if (!w.passable(head.getX(), head.getY(), head.getZ())) {
                    mine(head);
                    return;
                }
                if (!w.passable(next.getX(), next.getY(), next.getZ())) {
                    mine(next);
                    return;
                }
                move(dx, dz, 1.0);
            }
            case JUMP -> {
                move(dx, dz, 1.0);
                if (flat < 1.4) jump();
            }
            case CLIMB_UP -> {
                move(dx, dz, flat > 0.2 ? 1.0 : 0.2);
                jump();
            }
            case CLIMB_DOWN -> move(dx, dz, flat > 0.2 ? 0.6 : 0);
            default -> {
                move(dx, dz, 1.0);
                if (remainingStraight() > 4) sprint();
            }
        }
        if (bot.horizontalCollision && bot.onGround()) jump();

        // Stuck detection.
        if (age % 20 == 0) {
            if (bot.position().distanceToSqr(progressPos) < 0.25) {
                stuckTicks += 20;
                if (stuckTicks == 20 || stuckTicks == 60) {
                    // First try: step back to the centre of the previous path block (gets off fence corners).
                    Pathfinder.Step back = pathIndex > 0 && pathIndex - 1 < path.size() ? path.get(pathIndex - 1) : null;
                    BlockPos b = back != null ? new BlockPos(back.x(), back.y(), back.z()) : bot.blockPosition();
                    recenterTo = Vec3.atBottomCenterOf(b);
                    recenter = 12;
                } else {
                    jump();
                }
            } else {
                stuckTicks = 0;
            }
            progressPos = bot.position();
            if (stuckTicks >= 60) clearPath();
        }
    }

    /** The horizontal direction (east/west/south/north) closest to (dx, dz). */
    protected static Direction horizontal(int dx, int dz) {
        if (Math.abs(dx) >= Math.abs(dz)) return dx >= 0 ? Direction.EAST : Direction.WEST;
        return dz >= 0 ? Direction.SOUTH : Direction.NORTH;
    }

    private int remainingStraight() {
        int n = 0;
        for (int i = pathIndex; i < path.size() && n < 6; i++) {
            Pathfinder.Move m = path.get(i).move();
            if (m != Pathfinder.Move.WALK) break;
            n++;
        }
        return n;
    }

    // =====================================================================
    // Mining and placing
    // =====================================================================

    /** Mines a block a bit more. Returns true once it's gone (or was already air). */
    protected boolean mine(BlockPos pos) {
        ServerLevel level = level();
        BlockState state = level.getBlockState(pos);
        if (state.isAir()) {
            if (pos.equals(miningPos)) cancelMining();
            return true;
        }
        if (!pos.equals(miningPos)) {
            cancelMining();
            miningPos = pos;
            miningProgress = 0;
        }
        lookAt(Vec3.atCenterOf(pos), 0.15);
        selectBestTool(state);
        stuckTicks = 0; // busy, not stuck
        progressPos = bot.position();
        miningProgress += state.getDestroyProgress(bot, level, pos);
        if (age % 4 == 0) swingArm();
        level.destroyBlockProgress(bot.getId(), pos, Math.min(9, (int) (miningProgress * 10)));
        if (miningProgress >= 1f) {
            level.destroyBlockProgress(bot.getId(), pos, -1);
            bot.gameMode.destroyBlock(pos);
            miningPos = null;
            miningProgress = 0;
            boolean gone = level.getBlockState(pos).isAir();
            if (!gone) clearPath(); // protected block: plan another way
            return gone;
        }
        return false;
    }

    /** Shows the arm-swing animation to everyone nearby. */
    protected void swingArm() {
        // The swing method changed in 26.x (it takes extra settings), so find it at runtime and use
        // the default animation. If anything goes wrong the bot just doesn't animate.
        try {
            if (swingMethod == null && !swingLookedUp) {
                swingLookedUp = true;
                for (java.lang.reflect.Method m : net.minecraft.world.entity.LivingEntity.class.getMethods()) {
                    Class<?>[] t = m.getParameterTypes();
                    if (m.getName().equals("swing") && t.length >= 1 && t[0] == InteractionHand.class) {
                        swingMethod = m;
                        break;
                    }
                }
            }
            if (swingMethod == null) return;
            Class<?>[] t = swingMethod.getParameterTypes();
            Object[] args = new Object[t.length];
            args[0] = InteractionHand.MAIN_HAND;
            for (int i = 1; i < t.length; i++) {
                if (t[i] == boolean.class) args[i] = true;
                else if (t[i].isEnum()) args[i] = t[i].getEnumConstants()[0];
                else return;
            }
            swingMethod.invoke(bot, args);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // cosmetic only
        }
    }

    private static java.lang.reflect.Method swingMethod;
    private static boolean swingLookedUp = false;

    protected void cancelMining() {
        if (miningPos != null) level().destroyBlockProgress(bot.getId(), miningPos, -1);
        miningPos = null;
        miningProgress = 0;
    }

    /** Places a block from the inventory against the given face of support. */
    protected boolean placeAgainst(BlockPos support, Direction face) {
        int slot = findSlot(BotBrain::isBuildingBlock);
        if (slot < 0) return false;
        select(slot);
        ItemStack stack = bot.getMainHandItem();
        Vec3 hit = Vec3.atCenterOf(support).add(face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);
        BlockHitResult result = new BlockHitResult(hit, face, support, false);
        BlockPos target = support.relative(face);
        bot.gameMode.useItemOn(bot, level(), stack, InteractionHand.MAIN_HAND, result);
        swingArm();
        stuckTicks = 0;
        progressPos = bot.position();
        return !level().getBlockState(target).isAir();
    }

    /** Places a block at target, against any solid neighbour. */
    protected boolean placeAt(BlockPos target) {
        for (Direction dir : Direction.values()) {
            BlockPos support = target.relative(dir);
            BlockState s = level().getBlockState(support);
            if (!s.isAir() && !s.canBeReplaced() && s.isCollisionShapeFullBlock(level(), support)) {
                return placeAgainst(support, dir.getOpposite());
            }
        }
        return false;
    }

    /** Ticks between blocks while bridging or pillaring (games can slow this down). */
    protected int bridgeDelay() {
        return d().placeDelay;
    }

    protected boolean placeReady() {
        return age >= nextPlace;
    }

    protected void placeCooldown() {
        nextPlace = age + d().placeDelay;
    }

    public static boolean isBuildingBlock(ItemStack stack) {
        if (!(stack.getItem() instanceof BlockItem bi)) return false;
        if (stack.is(Items.TNT)) return false;
        BlockState s = bi.getBlock().defaultBlockState();
        return s.isSolid() && !s.hasBlockEntity() && !s.is(Blocks.SAND) && !s.is(Blocks.GRAVEL);
    }

    protected int countBlocks() {
        int n = 0;
        Inventory inv = bot.getInventory();
        for (int i = 0; i < 36; i++) {
            ItemStack s = inv.getItem(i);
            if (isBuildingBlock(s)) n += s.getCount();
        }
        return n;
    }

    // =====================================================================
    // Inventory
    // =====================================================================

    /** Finds a hotbar slot matching the test, moving it into the hotbar if it's in the backpack. */
    protected int findSlot(Predicate<ItemStack> test) {
        Inventory inv = bot.getInventory();
        for (int i = 0; i < 9; i++) if (!inv.getItem(i).isEmpty() && test.test(inv.getItem(i))) return i;
        for (int i = 9; i < 36; i++) {
            ItemStack s = inv.getItem(i);
            if (!s.isEmpty() && test.test(s)) {
                int dest = 6; // a spare hotbar slot
                for (int h = 0; h < 9; h++) if (inv.getItem(h).isEmpty()) { dest = h; break; }
                ItemStack old = inv.getItem(dest);
                inv.setItem(dest, s);
                inv.setItem(i, old);
                return dest;
            }
        }
        return -1;
    }

    protected void select(int slot) {
        if (slot >= 0 && slot < 9 && bot.getInventory().getSelectedSlot() != slot) {
            if (bot.isUsingItem()) bot.stopUsingItem();
            bot.getInventory().setSelectedSlot(slot);
        }
    }

    private void selectBestTool(BlockState state) {
        Inventory inv = bot.getInventory();
        int best = -1;
        float bestSpeed = 1f;
        for (int i = 0; i < 9; i++) {
            float speed = inv.getItem(i).getDestroySpeed(state);
            if (speed > bestSpeed) {
                bestSpeed = speed;
                best = i;
            }
        }
        if (best >= 0) select(best);
    }

    /** Rough weapon strength from the item name (works for any sword or axe). */
    public static double weaponScore(ItemStack s) {
        if (s.isEmpty()) return 0;
        String id = BuiltInRegistries.ITEM.getKey(s.getItem()).getPath();
        boolean sword = id.endsWith("_sword");
        boolean axe = id.endsWith("_axe");
        if (!sword && !axe) return 0;
        double tier = id.startsWith("netherite") ? 6 : id.startsWith("diamond") ? 5 : id.startsWith("iron") ? 4
                : id.startsWith("stone") ? 3 : id.startsWith("golden") ? 2 : id.startsWith("wooden") ? 1.5 : 2;
        return sword ? tier : tier - 0.5;
    }

    protected void selectBestWeapon() {
        Inventory inv = bot.getInventory();
        int best = -1;
        double bestScore = 0;
        for (int i = 0; i < 9; i++) {
            double s = weaponScore(inv.getItem(i));
            if (s > bestScore) {
                bestScore = s;
                best = i;
            }
        }
        if (best < 0) best = findSlot(st -> weaponScore(st) > 0);
        if (best >= 0) select(best);
    }

    /** Armor strength from the item name. */
    public static int armorScore(ItemStack s) {
        if (s.isEmpty()) return 0;
        String id = BuiltInRegistries.ITEM.getKey(s.getItem()).getPath();
        return id.startsWith("netherite") ? 6 : id.startsWith("diamond") ? 5 : id.startsWith("iron") ? 4
                : id.startsWith("chainmail") ? 3 : id.startsWith("golden") ? 2 : id.startsWith("leather") ? 1 : 0;
    }

    public static EquipmentSlot armorSlot(ItemStack s) {
        String id = BuiltInRegistries.ITEM.getKey(s.getItem()).getPath();
        if (id.endsWith("_helmet")) return EquipmentSlot.HEAD;
        if (id.endsWith("_chestplate")) return EquipmentSlot.CHEST;
        if (id.endsWith("_leggings")) return EquipmentSlot.LEGS;
        if (id.endsWith("_boots")) return EquipmentSlot.FEET;
        return null;
    }

    /** Puts on the best armor in the inventory. */
    protected void equipBestArmor() {
        Inventory inv = bot.getInventory();
        for (int i = 0; i < 36; i++) {
            ItemStack s = inv.getItem(i);
            EquipmentSlot slot = s.isEmpty() ? null : armorSlot(s);
            if (slot == null) continue;
            ItemStack worn = bot.getItemBySlot(slot);
            if (armorScore(s) > armorScore(worn)) {
                bot.setItemSlot(slot, s.copy());
                inv.setItem(i, worn.copy());
            }
        }
    }

    // =====================================================================
    // Combat
    // =====================================================================

    /** Call when choosing who to fight; handles reaction time. */
    protected void setTarget(LivingEntity t) {
        if (t != target) {
            target = t;
            targetSince = age;
        }
    }

    protected LivingEntity target() {
        return target;
    }

    protected boolean reacted() {
        return age - targetSince >= d().reactionTicks;
    }

    /** Sword fighting: chase, strafe, hit when the cooldown allows. */
    protected void melee(LivingEntity t, Pathfinder.Options chaseOptions) {
        setTarget(t);
        if (bot.isUsingItem()) bot.stopUsingItem();
        drawTicks = 0;
        selectBestWeapon();
        Vec3 aimPoint = t.position().add(0, t.getBbHeight() * 0.6, 0);
        double dist = bot.getEyePosition().distanceTo(closestPoint(t));
        boolean sees = bot.hasLineOfSight(t);
        if (reacted()) lookAt(aimPoint, 1.0);

        if (dist > d().reach + 0.3) {
            double dy = t.getY() - bot.getY();
            // Run straight at them only if there's ground all the way (otherwise the void-edge safety
            // stops us at the end of a bridge and we'd stand there forever): else plan a path (bridging).
            if (sees && Math.abs(dy) < 1.5 && dist < 10 && groundTo(t.position())) {
                moveTo(t.position(), 1.0);
                sprint();
                if (!aimedThisTick) lookAt(aimPoint, 1.0);
            } else {
                goTo(t.blockPosition(), Pathfinder.near(t.getBlockX(), t.getBlockY(), t.getBlockZ(), 1.5), chaseOptions);
            }
        } else {
            // In range: strafe around them and keep pressure.
            if (age >= nextStrafeFlip) {
                strafeDir = rng.nextBoolean() ? 1 : -1;
                nextStrafeFlip = age + 15 + rng.nextInt(30);
            }
            Vec3 to = t.position().subtract(bot.position());
            Vec3 side = new Vec3(-to.z, 0, to.x).normalize().scale(strafeDir * d().strafe);
            Vec3 fwd = to.normalize().scale(dist > d().reach - 0.6 ? 1.0 : 0.1);
            Vec3 m = fwd.add(side);
            move(m.x, m.z, bot.difficulty() == Difficulty.EASY ? 0.8 : 1.0);
            if (bot.difficulty() != Difficulty.EASY) sprint(); // easy bots don't sprint-hit
            if (d().crits && bot.onGround() && bot.getAttackStrengthScale(0.5f) > 0.9f && rng.nextInt(10) == 0) jump();
        }
        if (reacted() && dist <= d().reach && sees && aimOff(aimPoint) < 25) tryAttack(t);
    }

    private Vec3 closestPoint(LivingEntity t) {
        Vec3 eye = bot.getEyePosition();
        var box = t.getBoundingBox();
        return new Vec3(Math.max(box.minX, Math.min(eye.x, box.maxX)),
                Math.max(box.minY, Math.min(eye.y, box.maxY)),
                Math.max(box.minZ, Math.min(eye.z, box.maxZ)));
    }

    private long hitReadyAt = -1;

    protected boolean tryAttack(LivingEntity t) {
        if (bot.getAttackStrengthScale(0.5f) < d().attackAt) {
            hitReadyAt = -1;
            return false;
        }
        // Wait a little random extra time after the sword is ready, like a person would.
        if (hitReadyAt < 0) hitReadyAt = age + rng.nextInt(d().hitDelay() + 1);
        if (age < hitReadyAt) return false;
        hitReadyAt = -1;
        if (rng.nextDouble() < d().skipChance) {
            bot.resetAttackStrengthTicker();
            return false;
        }
        bot.attack(t);
        swingArm();
        if (rng.nextDouble() < d().wTapChance()) resprint = true;
        return true;
    }

    /**
     * Bow fighting. fullDraw = ticks to hold before releasing; beam = true for the Longbow's
     * full-charge beam (aim straight, no arrow drop). Returns false if the bot has no usable bow.
     */
    protected boolean shoot(LivingEntity t, Predicate<ItemStack> isBow, int fullDraw, boolean beam, boolean needsArrows) {
        if (needsArrows && findArrowCount() == 0) return false;
        int slot = findSlot(isBow);
        if (slot < 0) return false;
        setTarget(t);
        if (!bot.isUsingItem() || !isBow.test(bot.getUseItem())) {
            select(slot);
            bot.gameMode.useItem(bot, level(), bot.getMainHandItem(), InteractionHand.MAIN_HAND);
            drawTicks = 0;
        }
        drawTicks++;

        Vec3 aim = beam ? t.position().add(0, t.getBbHeight() * 0.55, 0) : arrowAim(t);
        if (reacted()) lookAt(aim, d().bowError / Math.max(0.5, d().aimError));
        // Count the draw by how long the bow has really been pulled (what the server judges the shot by).
        int drawn = Math.min(drawTicks, bot.getTicksUsingItem());
        if (drawn >= fullDraw && reacted() && aimOff(aim) < 4 + d().bowError && bot.hasLineOfSight(t)) {
            bot.releaseUsingItem();
            drawTicks = 0;
        }
        return true;
    }

    protected void stopShooting() {
        if (bot.isUsingItem()) bot.stopUsingItem();
        drawTicks = 0;
    }

    protected int findArrowCount() {
        int n = 0;
        Inventory inv = bot.getInventory();
        for (int i = 0; i < 36; i++) if (inv.getItem(i).is(Items.ARROW)) n += inv.getItem(i).getCount();
        return n;
    }

    /** Where to aim a full-power arrow so it drops onto the target, leading it if moving. */
    private Vec3 arrowAim(LivingEntity t) {
        Vec3 eye = bot.getEyePosition();
        Vec3 pos = t.position().add(0, t.getBbHeight() * 0.5, 0);
        double v = 3.0;   // full-power arrow speed, blocks per tick
        double g = 0.06;  // gravity (a bit extra for air drag)
        double flat = Math.sqrt(sq(pos.x - eye.x) + sq(pos.z - eye.z));
        double time = flat / (v * 0.92);
        Vec3 vel = t.getDeltaMovement();
        pos = pos.add(vel.x * time * d().bowLead, 0, vel.z * time * d().bowLead);
        flat = Math.sqrt(sq(pos.x - eye.x) + sq(pos.z - eye.z));
        double y = pos.y - eye.y;
        double disc = v * v * v * v - g * (g * flat * flat + 2 * y * v * v);
        double angle = disc < 0 ? Math.PI / 4 : Math.atan((v * v - Math.sqrt(disc)) / (g * flat));
        // Turn the launch angle back into a point to look at.
        return new Vec3(pos.x, eye.y + Math.tan(angle) * flat, pos.z);
    }

    private static double sq(double a) {
        return a * a;
    }

    /** Uses the held item once (fire wand, bridge egg, food). */
    protected void useItem(int slot) {
        select(slot);
        bot.gameMode.useItem(bot, level(), bot.getMainHandItem(), InteractionHand.MAIN_HAND);
        swingArm();
    }

    /** Runs away from a threat toward a safe spot. */
    protected void retreat(LivingEntity from, BlockPos safeSpot, Pathfinder.Options options) {
        stopShooting();
        goTo(safeSpot, Pathfinder.near(safeSpot.getX(), safeSpot.getY(), safeSpot.getZ(), 2), options);
        sprint();
    }

    protected double distanceTo(LivingEntity t) {
        return bot.position().distanceTo(t.position());
    }
}
