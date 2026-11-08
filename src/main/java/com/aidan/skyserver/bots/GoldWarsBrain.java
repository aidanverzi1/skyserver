package com.aidan.skyserver.bots;

import com.aidan.skyserver.Longbow;
import com.aidan.skyserver.goldwars.GoldWarsBots;
import com.aidan.skyserver.goldwars.GwClass;
import com.aidan.skyserver.goldwars.GwTeam;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.*;

/**
 * Gold Wars bot body. All decisions (which job, where to go, who to fight, what to mine) come
 * from the shared plain-Java code: {@link GwStrategy} (team commander) and {@link GwPlanner}
 * (this bot). The same code runs in the offline simulator used to tune the bots. This class
 * only carries the chosen action out in the real world: walking, fighting, mining, placing.
 *
 * The vault wall (the cover over the gold) is built from planks; sandstone is only used once the
 * planks run out (and for bridging).
 */
public final class GoldWarsBrain extends BotBrain {

    private boolean wasAlive = false;
    private GwTeam team;
    private GwClass cls;
    private GwPlanner planner;
    private GwAction action;
    private long nextWand = 0;
    private UUID fightTarget;
    private int fightFails = 0;
    private long fightCheckAge = 0;
    private double fightCheckDist = 0;
    /** True while placing a vault wall block (so it's wood, not sandstone). */
    private boolean wallBlock = false;

    public GoldWarsBrain(BotPlayer bot) {
        super(bot);
    }

    @Override
    protected LevelWorld world() {
        return new LevelWorld(level(), GoldWarsBots.voidY(),
                pos -> GoldWarsBots.canEdit(pos) && !GoldWarsBots.isGold(pos),
                GoldWarsBots::canEdit);
    }

    // ---- Called by the game ----

    public void onGoldBroken() {
        GwStrategy.Order o = planner == null ? null : planner.order();
        if (o != null && o.job() != GwStrategy.Job.DEFEND) LaneMemory.goldBroken(GoldWarsBots.mapId(), o.lane());
    }

    public void onDeath() {
        if (team == null) return;
        GwStrategy.Order o = planner == null ? null : planner.order();
        if (o != null && o.job() == GwStrategy.Job.ATTACK) {
            LaneMemory.died(GoldWarsBots.mapId(), o.lane());
            // Killed by a real player: that route counts as extra dangerous.
            if (bot.getLastHurtByMob() instanceof ServerPlayer p && !(p instanceof BotPlayer)) LaneMemory.died(GoldWarsBots.mapId(), o.lane());
        }
        GoldWarsCommander.of(team).botDied(bot.getUUID());
    }

    /** For /bots debug. */
    @Override
    public String describe() {
        GwStrategy.Order o = planner == null ? null : planner.order();
        if (o == null) return "waiting";
        String what = switch (o.job()) {
            case ATTACK -> "ATTACK enemy " + (o.vault() == 0 ? "north" : "south") + " vault via " + LaneMemory.laneName(o.lane());
            case INTERCEPT -> "INTERCEPT on our " + (o.vault() == 0 ? "north" : "south") + " side";
            case DEFEND -> "DEFEND our " + (o.vault() == 0 ? "north" : "south") + " vault";
        };
        return what + " | " + planner.doing();
    }

    // =====================================================================

    @Override
    protected void think() {
        UUID id = bot.getUUID();
        if (!GoldWarsBots.inMatch() || !GoldWarsBots.alive(id) || GoldWarsBots.level() != level()) {
            wasAlive = false;
            return;
        }
        team = GoldWarsBots.team(id);
        cls = GoldWarsBots.cls(id);
        if (team == null || cls == null) return;
        if (planner == null) planner = new GwPlanner(id, new Random(rng.nextLong()));
        if (!wasAlive) {
            wasAlive = true;
            resetBody();
            planner.respawned();
            action = null;
        }
        GwState state = GoldWarsCommander.snapshot(level().getServer(), world());
        GwAction next = planner.decide(state, GoldWarsCommander.of(team), countBlocks());
        // A Longbow shot half drawn: finish it rather than dropping it for a moment's change of plan.
        if (next.kind() != GwAction.Kind.FIGHT && cls == GwClass.LONGBOW && fightTarget != null && bot.isUsingItem()
                && Longbow.isLongbow(bot.getUseItem()) && bot.getTicksUsingItem() < Longbow.FULL_CHARGE_TICKS + 30) {
            ServerPlayer e = level().getServer().getPlayerList().getPlayer(fightTarget);
            if (e != null && e.isAlive() && bot.hasLineOfSight(e)) {
                fight(fightTarget);
                return;
            }
        }
        if (action != null && action.kind() != next.kind()) clearPath();
        action = next;
        if (next.kind() != GwAction.Kind.FIGHT) {
            stopShooting();
            fightTarget = null;
        }
        switch (next.kind()) {
            case GOTO -> walk(next);
            case FIGHT -> fight(next.target());
            case MINE -> {
                BlockPos pos = new BlockPos(next.x(), next.y(), next.z());
                mine(pos);
            }
            case PLACE -> {
                BlockPos pos = new BlockPos(next.x(), next.y(), next.z());
                lookAt(Vec3.atCenterOf(pos), 0.3);
                if (placeReady()) {
                    placeCooldown();
                    wallBlock = true; // the planner only places vault wall blocks
                    try {
                        placeAt(pos);
                    } finally {
                        wallBlock = false;
                    }
                }
            }
            case IDLE -> {}
        }
    }

    /** Vault wall blocks are wood (planks or logs) while there's any; everything else as normal. */
    @Override
    protected boolean placeAgainst(BlockPos support, Direction face) {
        if (wallBlock) {
            int slot = findSlot(s -> s.is(ItemTags.PLANKS) || s.is(ItemTags.LOGS));
            if (slot >= 0) {
                select(slot);
                Vec3 hit = Vec3.atCenterOf(support).add(face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);
                BlockPos target = support.relative(face);
                bot.gameMode.useItemOn(bot, level(), bot.getMainHandItem(), InteractionHand.MAIN_HAND, new BlockHitResult(hit, face, support, false));
                swingArm();
                return !level().getBlockState(target).isAir();
            }
        }
        return super.placeAgainst(support, face);
    }

    private void walk(GwAction a) {
        BlockPos goal = new BlockPos(a.x(), a.y(), a.z());
        PathStatus status = goTo(goal, a.goal(), a.options());
        if (status == PathStatus.STUCK) {
            planner.unreachable(a.x(), a.y(), a.z()); // pick another spot or route instead of standing here
        } else if (status == PathStatus.ARRIVED && age % 40 == 0) {
            lookDirection(bot.getYRot() + (rng.nextFloat() * 120 - 60), rng.nextFloat() * 20 - 10, 0);
        }
    }

    /** Uses the class's best way to fight. */
    private void fight(UUID targetId) {
        ServerPlayer enemy = level().getServer().getPlayerList().getPlayer(targetId);
        if (enemy == null || !enemy.isAlive()) return;
        double dist = distanceTo(enemy);
        if (!targetId.equals(fightTarget)) {
            fightTarget = targetId;
            fightFails = 0;
            fightCheckAge = age;
            fightCheckDist = dist;
        }
        boolean sees = bot.hasLineOfSight(enemy);
        // No progress toward them for 3 s and not in sword range (e.g. a gap neither of us can cross):
        // stop staring, go back to the objective. Ranged classes that can see them keep shooting
        // (a Longbow's beam has no range limit, so it keeps shooting from wherever it can see them).
        if (age - fightCheckAge >= 60) {
            double reach = cls == GwClass.LONGBOW ? 80 : 24;
            boolean shooting = (cls == GwClass.ARCHER || cls == GwClass.LONGBOW || cls == GwClass.MAGE) && sees && dist < reach;
            if (dist > 3.5 && dist > fightCheckDist - 0.8 && !shooting && !nearOurGold(enemy, 8)) {
                planner.unreachableTarget(targetId);
                fightCheckAge = age;
                fightCheckDist = dist;
                return;
            }
            fightCheckAge = age;
            fightCheckDist = dist;
        }
        // Bots may dig through blocks to reach someone (a player who walls themselves in can't hide).
        Pathfinder.Options chase = new Pathfinder.Options().bridge(Math.min(countBlocks(), 4)).dig(true).nodes(2500);
        boolean atOurGold = nearOurGold(enemy, 8);
        switch (cls) {
            case ARCHER -> {
                if (dist > 6 && shoot(enemy, s -> s.is(Items.BOW) && !Longbow.isLongbow(s), 20, false, true)) return;
            }
            case LONGBOW -> {
                if (dist > 7) {
                    // Hold for the full charge (with a little margin) so it's always the beam shot.
                    if (Longbow.isReady(bot) && shoot(enemy, Longbow::isLongbow, Longbow.FULL_CHARGE_TICKS + 4, true, false)) return;
                    if (dist > 12 && sees) {
                        stopShooting();
                        setTarget(enemy);
                        lookAt(enemy.getEyePosition(), 1.0);
                        return;
                    }
                    // Sniping from a perch and they ducked out of sight: stay put (the planner picks a new shot).
                    if (planner.perched() && dist > 12) {
                        stopShooting();
                        clearPath();
                        return;
                    }
                }
            }
            case MAGE -> {
                if (dist > 4 && dist < 24 && sees) {
                    setTarget(enemy);
                    Vec3 aim = enemy.position().add(enemy.getDeltaMovement().scale(dist * 0.6 * d().bowLead)).add(0, 0.6, 0);
                    lookAt(aim, 1.0);
                    if (reacted() && age >= nextWand && aimOff(aim) < 6) {
                        int slot = findSlot(GwClass::isFireWand);
                        if (slot >= 0) useItem(slot);
                        nextWand = age + 20;
                    }
                    if (dist > 8) return;
                }
            }
            default -> {}
        }
        // Walled in close by: tunnel straight at them.
        if (dist < 6 && !sees && tunnelToward(enemy)) return;
        int before = failedPathCount();
        melee(enemy, chase);
        if (failedPathCount() > before) fightFails++;
        if (fightFails >= 2) {
            fightFails = 0;
            // Someone at our gold is never "unreachable": dig to them instead of giving up.
            if (atOurGold) {
                if (!tunnelToward(enemy)) moveTo(enemy.position(), 1.0);
            } else {
                planner.unreachableTarget(targetId); // can't get to them: go back to the objective
            }
        }
    }

    private boolean nearOurGold(ServerPlayer enemy, double range) {
        for (BlockPos g : GoldWarsBots.gold(team)) if (g.distToCenterSqr(enemy.position()) < range * range) return true;
        return false;
    }

    /** Mines the first breakable block between us and the enemy. False if there's nothing to dig. */
    private boolean tunnelToward(ServerPlayer enemy) {
        Vec3 from = bot.position().add(0, 0.5, 0), to = enemy.position().add(0, 0.5, 0);
        Vec3 step = to.subtract(from);
        double len = step.length();
        if (len < 0.5) return false;
        step = step.scale(1 / len);
        LevelWorld w = world();
        for (double t = 0.6; t < len; t += 0.4) {
            Vec3 p = from.add(step.scale(t));
            for (int dy = 0; dy <= 1; dy++) {
                BlockPos b = BlockPos.containing(p.x, p.y + dy, p.z);
                if (level().getBlockState(b).isAir() || level().getBlockState(b).getCollisionShape(level(), b).isEmpty()) continue;
                if (!w.breakable(b.getX(), b.getY(), b.getZ())) return false;
                lookAt(Vec3.atCenterOf(b), 0.3);
                mine(b);
                moveTo(enemy.position(), 0.3);
                return true;
            }
        }
        return false;
    }
}
