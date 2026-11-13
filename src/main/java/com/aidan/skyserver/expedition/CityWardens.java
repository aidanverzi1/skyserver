package com.aidan.skyserver.expedition;

import com.aidan.skyserver.Skyserver;
import com.aidan.skyserver.lobby.Lobby;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import net.minecraft.world.entity.monster.warden.Warden;

import java.util.*;

/**
 * 5.21: the Ancient City's ROAMING WARDENS. They can never be hurt.
 *
 *  - They walk between plazas and junctions (layout.patrol) and IGNORE vanilla vibrations: their anger and
 *    "disturbance" are wiped every tick while they're calm.
 *  - They ENGAGE (go angry at one player) only when:
 *      a player comes within ENGAGE_RANGE (6 / 7 / 8 / 10 blocks, Easy .. Nightmare),
 *      one noise event of 5+ points happens within HEAR_RANGE (16) of them (that player),
 *      or the noise meter reaches 50% (the loudest player).
 *  - They LOSE you after 15 seconds without seeing you (more than 6 blocks away), and go back to walking.
 *  - Bone Rattles: a Warden within 20 blocks of where one lands goes there for 10 seconds (calm, even if it was
 *    chasing someone). The relic's heartbeat makes Wardens within 16 blocks come and look.
 *  - Hard / Nightmare: +15% / +25% speed while chasing the relic carrier.
 * Count per difficulty: AncientCity.ROAMING. A roaming Warden that digs away comes back 20 seconds later.
 */
final class CityWardens {
    private CityWardens() {}

    static final int[] ENGAGE_RANGE = {6, 7, 8, 10};
    static final double HEAR_RANGE = 16, RATTLE_RANGE = 20;
    static final int LOSE_TICKS = 15 * 20, INVESTIGATE_TICKS = 10 * 20, RESPAWN_TICKS = 20 * 20;
    static final double[] CHASE_SPEED = {0, 0, 0.15, 0.25};
    private static final Identifier SPEED_ID = Identifier.fromNamespaceAndPath(Skyserver.MOD_ID, "city_chase");
    private static final Random RNG = new Random();

    /** One Warden's state. */
    static final class W {
        final UUID id;
        final boolean roaming;
        UUID target;
        long lostSince = -1, goalUntil = 0, investigateUntil = 0;
        BlockPos goal;

        W(UUID id, boolean roaming) {
            this.id = id;
            this.roaming = roaming;
        }
    }

    /** Spawns a Warden near `at` (a free floor spot). engage: a player to go after at once (or null). */
    static W spawn(ServerLevel l, AncientCity.Run run, BlockPos at, boolean roaming, ServerPlayer engage) {
        Mob m = EntityTypes.WARDEN.create(l, EntitySpawnReason.TRIGGERED);
        if (!(m instanceof Warden w)) return null;
        BlockPos spot = freeSpot(l, new BlockPos(at.getX(), AncientCityMap.FLOOR_Y, at.getZ()));
        w.snapTo(spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5, RNG.nextFloat() * 360f, 0f);
        w.finalizeSpawn(l, l.getCurrentDifficultyAt(spot), EntitySpawnReason.TRIGGERED, null);
        w.setPersistenceRequired();
        l.addFreshEntity(w);
        W s = new W(w.getUUID(), roaming);
        run.wardens.add(s);
        if (engage != null) engage(run, s, engage);
        return s;
    }

    /** The nearest spot (up to 10 blocks away) with 3 blocks of air above the floor. */
    static BlockPos freeSpot(ServerLevel l, BlockPos at) {
        for (int r = 0; r <= 10; r++)
            for (int dx = -r; dx <= r; dx++)
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                    BlockPos p = at.offset(dx, 0, dz);
                    if (l.getBlockState(p).isAir() && l.getBlockState(p.above()).isAir() && l.getBlockState(p.above(2)).isAir()
                            && !l.getBlockState(p.below()).isAir()) return p;
                }
        return at;
    }

    private static Warden entity(ServerLevel l, W s) {
        Entity e = l.getEntity(s.id);
        return e instanceof Warden w && w.isAlive() ? w : null;
    }

    static void engage(AncientCity.Run run, W s, ServerPlayer p) {
        if (p == null || s.target != null && s.target.equals(p.getUUID())) return;
        s.target = p.getUUID();
        s.lostSince = -1;
        s.investigateUntil = 0;
        Lobby.msg(p, "§4§lThe Warden has found you! §7Break its line of sight - it gives up after 15 seconds.");
        p.level().playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.WARDEN_HEARTBEAT, SoundSource.HOSTILE, 2f, 1.2f);
    }

    static void disengage(ServerLevel l, AncientCity.Run run, W s) {
        Warden w = entity(l, s);
        UUID was = s.target;
        s.target = null;
        s.lostSince = -1;
        if (w != null) {
            calm(w, run);
            AttributeInstance a = w.getAttribute(Attributes.MOVEMENT_SPEED);
            if (a != null) a.removeModifier(SPEED_ID);
        }
        if (was != null) {
            ServerPlayer p = l.getServer().getPlayerList().getPlayer(was);
            if (p != null && run.inside.contains(was)) Lobby.msg(p, "§7The Warden lost your trail.");
        }
    }

    /** Wipes its anger at everyone on the trip, and anything it was investigating. */
    private static void calm(Warden w, AncientCity.Run run) {
        for (ServerPlayer p : AncientCity.inside(run)) w.clearAnger(p);
        w.getBrain().eraseMemory(MemoryModuleType.DISTURBANCE_LOCATION);
        w.getBrain().eraseMemory(MemoryModuleType.ROAR_TARGET);
        w.getBrain().eraseMemory(MemoryModuleType.ATTACK_TARGET);
    }

    private static void walkTo(Warden w, BlockPos pos, float speed) {
        w.getBrain().setMemory(MemoryModuleType.WALK_TARGET, new WalkTarget(pos, speed, 1));
    }

    /** Every tick: calm Wardens stay calm (vanilla vibrations and sniffing don't count). */
    static void fastTick(ServerLevel l, AncientCity.Run run) {
        for (W s : run.wardens) {
            if (s.target != null) continue;
            Warden w = entity(l, s);
            if (w != null) calm(w, run);
        }
    }

    /** Every 10 ticks: engaging, losing, patrolling. */
    static void tick(ServerLevel l, AncientCity.Run run, long ticks) {
        int d = run.diff;
        // Wardens a shrieker summoned (vanilla): adopt them - they go after the nearest player, then follow our rules.
        int[] b = run.layout.box();
        net.minecraft.world.phys.AABB box = new net.minecraft.world.phys.AABB(b[0], AncientCityMap.FLOOR_Y - 10, b[1], b[2], AncientCityMap.FLOOR_Y + AncientCityMap.DOME_HEIGHT + 2, b[3]);
        for (Warden w : l.getEntitiesOfClass(Warden.class, box, Warden::isAlive)) {
            boolean known = false;
            for (W s : run.wardens) if (s.id.equals(w.getUUID())) known = true;
            if (known) continue;
            W s = new W(w.getUUID(), false);
            run.wardens.add(s);
            ServerPlayer near = null;
            double best = 32 * 32;
            for (ServerPlayer p : AncientCity.inside(run)) {
                double dd = p.distanceToSqr(w);
                if (dd < best && !run.layout.inLift(p.getX(), p.getY(), p.getZ())) {
                    best = dd;
                    near = p;
                }
            }
            if (near != null) engage(run, s, near);
        }
        for (Iterator<W> it = run.wardens.iterator(); it.hasNext(); ) {
            W s = it.next();
            Warden w = entity(l, s);
            if (w == null) {
                it.remove();
                if (s.roaming) run.wardenRespawns.add(ticks + RESPAWN_TICKS);
                continue;
            }
            if (ticks % 200 == 0) w.getBrain().setMemoryWithExpiry(MemoryModuleType.DIG_COOLDOWN, net.minecraft.util.Unit.INSTANCE, 1200L);
            if (s.target != null) {
                ServerPlayer p = l.getServer().getPlayerList().getPlayer(s.target);
                if (p == null || !run.inside.contains(s.target) || AncientCity.isDowned(s.target) || p.level() != l
                        || run.layout.inLift(p.getX(), p.getY(), p.getZ())) {
                    disengage(l, run, s);
                    continue;
                }
                w.increaseAngerAt(p, 100, false);
                boolean sees = w.hasLineOfSight(p) || w.distanceTo(p) <= 6;
                if (sees) s.lostSince = -1;
                else if (s.lostSince < 0) s.lostSince = ticks;
                else if (ticks - s.lostSince >= LOSE_TICKS) {
                    disengage(l, run, s);
                    continue;
                }
                AttributeInstance a = w.getAttribute(Attributes.MOVEMENT_SPEED);
                if (a != null) {
                    boolean chaseCarrier = CHASE_SPEED[d] > 0 && AncientCity.hasRelic(p);
                    if (chaseCarrier && !a.hasModifier(SPEED_ID))
                        a.addTransientModifier(new AttributeModifier(SPEED_ID, CHASE_SPEED[d], AttributeModifier.Operation.ADD_MULTIPLIED_BASE));
                    else if (!chaseCarrier) a.removeModifier(SPEED_ID);
                }
                continue;
            }
            // Calm: anyone too close?
            ServerPlayer close = null;
            double best = Double.MAX_VALUE;
            for (ServerPlayer p : AncientCity.inside(run)) {
                if (AncientCity.isDowned(p.getUUID()) || run.layout.inLift(p.getX(), p.getY(), p.getZ())) continue;
                double dist = w.distanceTo(p);
                if (dist <= ENGAGE_RANGE[d] && dist < best) {
                    best = dist;
                    close = p;
                }
            }
            if (close != null) {
                engage(run, s, close);
                continue;
            }
            // Walk: investigate first, else the next patrol spot.
            if (s.investigateUntil > ticks && s.goal != null) {
                walkTo(w, s.goal, 0.9f);
                continue;
            }
            if (s.goal == null || ticks > s.goalUntil || w.blockPosition().distSqr(s.goal) < 6) {
                s.goal = nextSpot(run, w.blockPosition());
                s.goalUntil = ticks + 30 * 20;
            }
            if (s.goal != null) walkTo(w, s.goal, 0.6f);
        }
        // Roaming Wardens that dug away come back.
        for (Iterator<Long> it = run.wardenRespawns.iterator(); it.hasNext(); ) {
            long at = it.next();
            if (ticks < at) continue;
            it.remove();
            BlockPos p = farSpot(run, 30);
            if (p != null) spawn(l, run, p, true, null);
        }
    }

    /** A patrol spot 12-36 blocks from here (random), or any. */
    private static BlockPos nextSpot(AncientCity.Run run, BlockPos from) {
        List<BlockPos> near = new ArrayList<>();
        for (BlockPos p : run.layout.patrol) {
            double dd = Math.sqrt(p.distSqr(from));
            if (dd >= 12 && dd <= 36) near.add(p);
        }
        if (near.isEmpty()) return run.layout.patrol.isEmpty() ? null : run.layout.patrol.get(RNG.nextInt(run.layout.patrol.size()));
        return near.get(RNG.nextInt(near.size()));
    }

    /** A patrol spot at least `away` blocks from the lift and from every player. */
    static BlockPos farSpot(AncientCity.Run run, double away) {
        List<BlockPos> ok = new ArrayList<>();
        BlockPos lift = new BlockPos((run.layout.lift[0] + run.layout.lift[2]) / 2, AncientCityMap.FLOOR_Y, run.layout.lift[1]);
        outer:
        for (BlockPos p : run.layout.patrol) {
            if (p.distSqr(lift) < 40 * 40) continue;
            for (ServerPlayer pl : AncientCity.inside(run)) if (pl.blockPosition().distSqr(p) < away * away) continue outer;
            for (W s : run.wardens) if (s.goal != null && s.goal.distSqr(p) < 15 * 15) continue outer;
            ok.add(p);
        }
        return ok.isEmpty() ? null : ok.get(RNG.nextInt(ok.size()));
    }

    /** A noise event: 5+ points near a calm Warden makes it engage that player (or look, if nobody made it). */
    static void heard(ServerLevel l, AncientCity.Run run, ServerPlayer src, double raw, BlockPos at, long ticks) {
        if (raw < 5 || at == null) return;
        for (W s : run.wardens) {
            if (s.target != null) continue;
            Warden w = entity(l, s);
            if (w == null || w.blockPosition().distSqr(at) > HEAR_RANGE * HEAR_RANGE) continue;
            if (src != null && !run.layout.inLift(src.getX(), src.getY(), src.getZ())) engage(run, s, src);
            else investigate(s, at, ticks);
        }
    }

    static void investigate(W s, BlockPos at, long ticks) {
        s.goal = at;
        s.investigateUntil = ticks + INVESTIGATE_TICKS;
        s.goalUntil = s.investigateUntil;
    }

    /** A Bone Rattle landed: nearby Wardens drop what they're doing and go there. */
    static void rattle(ServerLevel l, AncientCity.Run run, BlockPos at, long ticks) {
        for (W s : run.wardens) {
            Warden w = entity(l, s);
            if (w == null || w.blockPosition().distSqr(at) > RATTLE_RANGE * RATTLE_RANGE) continue;
            if (s.target != null) disengage(l, run, s);
            investigate(s, at, ticks);
        }
    }

    /** The relic's heartbeat: calm Wardens close by come and look. */
    static void heartbeat(ServerLevel l, AncientCity.Run run, ServerPlayer carrier, long ticks) {
        for (W s : run.wardens) {
            if (s.target != null) continue;
            Warden w = entity(l, s);
            if (w == null || w.distanceTo(carrier) > HEAR_RANGE) continue;
            investigate(s, carrier.blockPosition(), ticks);
        }
    }

    /** 50%: every Warden goes after the loudest player. */
    static void engageAll(ServerLevel l, AncientCity.Run run, ServerPlayer p) {
        if (p == null) return;
        for (W s : run.wardens) if (entity(l, s) != null) engage(run, s, p);
    }

    /** Some shrieker went off by itself: the nearest calm Warden goes to look. */
    static void lookAt(ServerLevel l, AncientCity.Run run, BlockPos at, long ticks) {
        W best = null;
        double bd = Double.MAX_VALUE;
        for (W s : run.wardens) {
            if (s.target != null) continue;
            Warden w = entity(l, s);
            if (w == null) continue;
            double dd = w.blockPosition().distSqr(at);
            if (dd < bd) {
                bd = dd;
                best = s;
            }
        }
        if (best != null) investigate(best, at, ticks);
    }
}
