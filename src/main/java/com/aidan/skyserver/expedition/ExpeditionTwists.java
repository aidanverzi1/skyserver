package com.aidan.skyserver.expedition;

import com.aidan.skyserver.Safe;
import com.aidan.skyserver.lobby.Announce;
import com.aidan.skyserver.lobby.Lobby;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.*;

/**
 * 5.17: the arena twists (debuffs) of the Nether Expedition. Every chest arena gets one, and a trip never
 * repeats a twist while unused ones are left. The Boss Arena never has one.
 *
 *  Ambush       - every wave comes through every doorway, and the next wave is due 5 seconds sooner (Expedition).
 *  Withering    - half the guards' hits wither you (Expedition; the ONLY place anything withers you now).
 *  Gloom        - every 7 seconds everyone in the arena is blinded for 0.2 seconds.
 *  Volatile     - a guard explodes 1.5 seconds after it dies: as much as 2 of its hits at its feet, less further away.
 *  War Banner   - a red banner up on a balcony (6 blocks up) heals every guard 1 heart a second until someone
 *                 breaks it. It is raised again at the start of every wave.
 *  Swarm        - 50% more guards, each with 40% less health (Expedition).
 *  Rain of Fire - fireballs fall on marked spots: being hit hurts and sets you alight. Nothing burns on the floor.
 *  Soul Shield  - the first hit on every guard does nothing (it glows until its shield is gone).
 *
 * Every number is in the constants below.
 */
final class ExpeditionTwists {
    private ExpeditionTwists() {}

    static final int AMBUSH = 0, WITHERING = 1, GLOOM = 2, VOLATILE = 3, WAR_BANNER = 4, SWARM = 5, RAIN_OF_FIRE = 6, SOUL_SHIELD = 7;
    static final String[] NAMES = {"§c§lAmbush", "§8§lWithering", "§5§lGloom", "§6§lVolatile", "§4§lWar Banner", "§e§lSwarm", "§c§lRain of Fire", "§b§lSoul Shield"};
    static final String[] TIPS = {
            "§7They come through every doorway at once",
            "§7Half their hits wither you",
            "§7Every few seconds the lights go out for a blink",
            "§7Guards explode a moment after they die - step back",
            "§7A red banner up on a balcony heals the guards - break it (every wave)",
            "§7More guards, but weaker ones",
            "§7Fireballs fall on the marked spots - keep moving",
            "§7The first hit on every guard does nothing"};

    /** Gloom: seconds between blinks, and how many ticks one lasts (4 = 0.2 seconds). */
    static final int GLOOM_EVERY = 7, GLOOM_TICKS = 4;
    /** Volatile: ticks until the dead guard explodes, how far it reaches, and how many of its hits it's worth. */
    static final int VOLATILE_FUSE = 30;
    static final double VOLATILE_RADIUS = 4.0, VOLATILE_HITS = 2.0;
    /** War Banner: health every guard gets back each second (2 = 1 heart), and how high the banner stands. */
    static final float BANNER_HEAL = 2f;
    static final int BANNER_HEIGHT = 6;
    /** Swarm: guard count and health multipliers. */
    static final double SWARM_COUNT = 1.5, SWARM_HEALTH = 0.6;
    /** Rain of Fire: seconds between volleys, ticks of warning, reach, damage (plus 2 per difficulty), fire ticks. */
    static final int RAIN_EVERY = 4, RAIN_WARN = 30, RAIN_FIRE_TICKS = 80;
    static final double RAIN_RADIUS = 2.5;
    static final float RAIN_DAMAGE = 5f;

    /** War Banner arenas: where the banner stands, and the arenas whose banner is up right now. */
    private static final Map<ExpeditionRooms.Room, BlockPos> BANNER = new HashMap<>();
    private static final Set<ExpeditionRooms.Room> BANNER_UP = new HashSet<>();
    /** Soul Shield: guards that still have their shield. */
    private static final Set<UUID> SHIELDED = new HashSet<>();

    /** A spot a fireball is about to land on. */
    private static final class Mark {
        final ServerLevel level;
        final Expedition.Run run;
        final ExpeditionRooms.Room room;
        final Vec3 at;
        int ticks;

        Mark(ServerLevel level, Expedition.Run run, ExpeditionRooms.Room room, Vec3 at) {
            this.level = level;
            this.run = run;
            this.room = room;
            this.at = at;
            this.ticks = RAIN_WARN;
        }
    }

    private static final List<Mark> MARKS = new ArrayList<>();

    /** A random twist this trip hasn't used yet (any twist once they've all been used). */
    static int pick(Random rng, Set<Integer> used) {
        List<Integer> free = new ArrayList<>();
        for (int i = 0; i < NAMES.length; i++) if (!used.contains(i)) free.add(i);
        int tw = free.isEmpty() ? rng.nextInt(NAMES.length) : free.get(rng.nextInt(free.size()));
        used.add(tw);
        return tw;
    }

    // =====================================================================
    // Hooks called by Expedition
    // =====================================================================

    /** A wave starts in this arena. */
    static void waveStarted(ServerLevel level, Expedition.Run run, ExpeditionRooms.Room r, int twist, Random rng) {
        if (twist != WAR_BANNER) return;
        BlockPos at = BANNER.computeIfAbsent(r, k -> bannerSpot(level, r, rng));
        level.setBlock(at, bannerState(), Block.UPDATE_ALL);
        BANNER_UP.add(r);
        level.playSound(null, at, SoundEvents.BEACON_ACTIVATE, SoundSource.HOSTILE, 1.5f, 1.4f);
        for (ServerPlayer p : Expedition.inRoom(run, r)) Lobby.msg(p, "§4§lThe war banner is up! §7It heals every guard - §fbreak the red banner §7up on the balcony.");
    }

    /** A guard of an arena with this twist was just made (before it joins the world). */
    static void guardSpawned(Mob m, int twist) {
        if (twist != SOUL_SHIELD) return;
        SHIELDED.add(m.getUUID());
        m.setGlowingTag(true);
    }

    /** A player is about to hurt this guard. True = the hit does nothing (it only broke the guard's soul shield). */
    static boolean blocksHit(ServerLevel level, Entity guard, ServerPlayer attacker) {
        if (!SHIELDED.remove(guard.getUUID())) return false;
        if (guard.getCustomName() == null) guard.setGlowingTag(false); // captains keep their glow
        level.playSound(null, guard.blockPosition(), SoundEvents.GLASS_BREAK, SoundSource.HOSTILE, 1f, 1.6f);
        level.sendParticles(ParticleTypes.END_ROD, guard.getX(), guard.getY() + 1, guard.getZ(), 14, 0.4, 0.6, 0.4, 0.05);
        Announce.actionBar(attacker, "§bSoul shield broken! §7Hit it again.");
        return true;
    }

    /** A guard died in an arena with this twist. */
    static void guardDied(ServerLevel level, Expedition.Run run, ExpeditionRooms.Room r, int twist, Entity e) {
        SHIELDED.remove(e.getUUID());
        if (twist != VOLATILE || !(e instanceof LivingEntity le)) return;
        AttributeInstance a = le.getAttribute(Attributes.ATTACK_DAMAGE);
        final float damage = (float) (VOLATILE_HITS * Math.max(4.0, a == null ? 4.0 : a.getValue()));
        final Vec3 at = e.position();
        level.playSound(null, e.blockPosition(), SoundEvents.BLAZE_SHOOT, SoundSource.HOSTILE, 1.2f, 0.5f);
        for (int t = 0; t < VOLATILE_FUSE; t += 5) {
            Lobby.runLaterTicks(t, () -> Safe.run("expedition volatile fuse", () -> {
                level.sendParticles(ParticleTypes.SMOKE, at.x, at.y + 0.4, at.z, 10, 0.4, 0.3, 0.4, 0.02);
                level.sendParticles(ParticleTypes.FLAME, at.x, at.y + 0.4, at.z, 6, 0.3, 0.3, 0.3, 0.02);
            }));
        }
        Lobby.runLaterTicks(VOLATILE_FUSE, () -> Safe.run("expedition volatile", () -> {
            if (run.over) return;
            level.sendParticles(ParticleTypes.EXPLOSION, at.x, at.y + 0.5, at.z, 3, 0.5, 0.3, 0.5, 0);
            level.playSound(null, BlockPos.containing(at), SoundEvents.DRAGON_FIREBALL_EXPLODE, SoundSource.HOSTILE, 1.2f, 1.3f);
            for (ServerPlayer p : Expedition.inRoom(run, r)) {
                double d = Math.sqrt(p.distanceToSqr(at.x, at.y, at.z));
                if (d > VOLATILE_RADIUS) continue;
                p.hurtServer(level, level.damageSources().explosion(null, null), (float) (damage * (1 - d / VOLATILE_RADIUS)));
            }
        }));
    }

    /** Once a second for every arena whose fight is on. sec = the server's seconds counter. */
    static void second(ServerLevel level, Expedition.Run run, ExpeditionRooms.Room r, int twist, long sec, Random rng) {
        List<ServerPlayer> players = Expedition.inRoom(run, r);
        if (twist == GLOOM && sec % GLOOM_EVERY == 0) {
            for (ServerPlayer p : players) {
                // A long effect taken away after GLOOM_TICKS: a short one would only fade in a little.
                p.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, 40, 0, false, false));
                Lobby.runLaterTicks(GLOOM_TICKS, () -> Safe.run("expedition gloom", () -> p.removeEffect(MobEffects.BLINDNESS)));
            }
        }
        if (twist == WAR_BANNER && BANNER_UP.contains(r)) {
            BlockPos at = BANNER.get(r);
            if (at != null && level.getBlockState(at).isAir()) {
                BANNER_UP.remove(r); // gone some other way (an explosion, a piston...)
            } else {
                for (UUID id : new ArrayList<>(r.guards)) {
                    Entity g = level.getEntity(id);
                    if (g instanceof LivingEntity le && le.isAlive() && le.getHealth() < le.getMaxHealth()) {
                        le.heal(BANNER_HEAL);
                        level.sendParticles(ParticleTypes.HAPPY_VILLAGER, le.getX(), le.getY() + 1.2, le.getZ(), 3, 0.3, 0.4, 0.3, 0);
                    }
                }
                if (at != null) level.sendParticles(ParticleTypes.END_ROD, at.getX() + 0.5, at.getY() + 1, at.getZ() + 0.5, 12, 0.2, 1.2, 0.2, 0.01);
                for (ServerPlayer p : players) Announce.actionBar(p, "§4The war banner heals the guards! §fBreak the red banner §7(up on the balcony)");
            }
        }
        if (twist == RAIN_OF_FIRE && sec % RAIN_EVERY == 0 && !players.isEmpty()) {
            for (ServerPlayer p : players) {
                MARKS.add(new Mark(level, run, r, new Vec3(p.getX(), r.y0, p.getZ())));
                int extra = 1 + run.diff / 2;
                for (int i = 0; i < extra; i++) {
                    double x = r.x0 + 1 + rng.nextDouble() * (r.x1 - r.x0 - 1), z = r.z0 + 1 + rng.nextDouble() * (r.z1 - r.z0 - 1);
                    MARKS.add(new Mark(level, run, r, new Vec3(x, r.y0, z)));
                }
            }
            level.playSound(null, r.floorCenter(), SoundEvents.BLAZE_SHOOT, SoundSource.HOSTILE, 1.5f, 0.4f);
        }
    }

    /** Every tick: fireballs on their way down. */
    static void fastTick() {
        if (MARKS.isEmpty()) return;
        for (Iterator<Mark> it = MARKS.iterator(); it.hasNext(); ) {
            Mark m = it.next();
            if (m.run.over || m.room.cleared) {
                it.remove();
                continue;
            }
            m.ticks--;
            Vec3 a = m.at;
            if (m.ticks > 0) {
                if (m.ticks % 3 == 0) {
                    m.level.sendParticles(ParticleTypes.FLAME, a.x, a.y + 0.1, a.z, 5, 0.9, 0.02, 0.9, 0.0);
                    m.level.sendParticles(ParticleTypes.SMOKE, a.x, a.y + 0.1, a.z, 4, 0.8, 0.05, 0.8, 0.01);
                    // The fireball itself, coming down.
                    m.level.sendParticles(ParticleTypes.LAVA, a.x, a.y + m.ticks * 0.4, a.z, 2, 0.1, 0.1, 0.1, 0.0);
                }
                continue;
            }
            it.remove();
            m.level.sendParticles(ParticleTypes.EXPLOSION, a.x, a.y + 0.5, a.z, 2, 0.4, 0.3, 0.4, 0);
            m.level.sendParticles(ParticleTypes.LAVA, a.x, a.y + 0.5, a.z, 14, 0.7, 0.5, 0.7, 0.2);
            m.level.playSound(null, BlockPos.containing(a), SoundEvents.DRAGON_FIREBALL_EXPLODE, SoundSource.HOSTILE, 1f, 1.4f);
            float dmg = RAIN_DAMAGE + 2 * m.run.diff;
            for (ServerPlayer p : Expedition.inRoom(m.run, m.room)) {
                double d = Math.hypot(p.getX() - a.x, p.getZ() - a.z);
                if (d > RAIN_RADIUS || Math.abs(p.getY() - a.y) > 3) continue;
                p.hurtServer(m.level, m.level.damageSources().explosion(null, null), (float) (dmg * (1 - d / 4.0)));
                p.setRemainingFireTicks(Math.max(p.getRemainingFireTicks(), RAIN_FIRE_TICKS));
            }
        }
    }

    /** A player is breaking a block. True = it was a war banner (it comes down; nothing drops). */
    static boolean breakBanner(ServerLevel level, BlockPos pos, ServerPlayer breaker, Expedition.Run run) {
        for (Map.Entry<ExpeditionRooms.Room, BlockPos> e : BANNER.entrySet()) {
            if (!e.getValue().equals(pos)) continue;
            ExpeditionRooms.Room r = e.getKey();
            level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            if (BANNER_UP.remove(r)) {
                level.playSound(null, pos, SoundEvents.GLASS_BREAK, SoundSource.HOSTILE, 1.5f, 0.7f);
                for (ServerPlayer p : Expedition.inRoom(run, r))
                    Lobby.msg(p, "§a" + breaker.getName().getString() + " tore down the war banner! §7The guards stop healing (until the next wave).");
            }
            return true;
        }
        return false;
    }

    /** An arena is finished (cleared, or its trip ended): take its banner down and forget it. */
    static void roomDone(ServerLevel level, ExpeditionRooms.Room r) {
        BlockPos at = BANNER.remove(r);
        BANNER_UP.remove(r);
        if (at != null && level != null) level.setBlock(at, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        for (UUID id : r.guards) SHIELDED.remove(id);
        MARKS.removeIf(m -> m.room == r);
    }

    // =====================================================================
    // The banner
    // =====================================================================

    private static BlockState bannerState() {
        return BuiltInRegistries.BLOCK.getValue(Identifier.withDefaultNamespace("red_banner")).defaultBlockState();
    }

    private static boolean standable(ServerLevel level, BlockPos.MutableBlockPos m, int x, int y, int z) {
        if (!level.getBlockState(m.set(x, y, z)).isAir() || !level.getBlockState(m.set(x, y + 1, z)).isAir()) return false;
        return level.getBlockState(m.set(x, y - 1, z)).isFaceSturdy(level, m, Direction.UP);
    }

    /**
     * Where the banner stands: a spot BANNER_HEIGHT blocks up that you can stand on (the balconies and
     * walkways are that high). If the arena has none, a brick ledge is built on a wall at that height.
     */
    private static BlockPos bannerSpot(ServerLevel level, ExpeditionRooms.Room r, Random rng) {
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        int y = r.y0 + BANNER_HEIGHT;
        for (int tries = 0; tries < 200; tries++) {
            int x = r.x0 + rng.nextInt(r.x1 - r.x0 + 1), z = r.z0 + rng.nextInt(r.z1 - r.z0 + 1);
            if (standable(level, m, x, y, z)) return new BlockPos(x, y, z);
        }
        for (int tries = 0; tries < 200; tries++) {
            int x = r.x0 + rng.nextInt(r.x1 - r.x0 + 1), z = r.z0 + rng.nextInt(r.z1 - r.z0 + 1);
            for (int yy = y - 1; yy <= y + 1; yy++) if (standable(level, m, x, yy, z)) return new BlockPos(x, yy, z);
        }
        // No balcony: a ledge on the wall.
        int z = (r.z0 + r.z1) / 2;
        BlockPos ledge = new BlockPos(r.x0, y - 1, z);
        level.setBlock(ledge, Blocks.NETHER_BRICKS.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(ledge.above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(ledge.above(2), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        return ledge.above();
    }
}
