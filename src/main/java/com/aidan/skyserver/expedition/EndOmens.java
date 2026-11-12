package com.aidan.skyserver.expedition;

import com.aidan.skyserver.Safe;
import com.aidan.skyserver.lobby.Announce;
import com.aidan.skyserver.lobby.Lobby;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import java.util.*;

/**
 * 5.18b: OMENS - the End Expedition's optional debuffs.
 *
 * Before waves 1, 11, 21... Captain Wren offers two random omens. The crew may take either, both or neither (any crew
 * member clicks; the last click counts). Taken omens last for the next 10 waves, and each one gives +25% Void
 * Fragments from everything killed in those waves.
 *
 *  Void Tide         - the ledge under the rim is gone for these 10 waves.
 *  Unstable Pearls   - thrown ender pearls wobble off course (land up to ~3 blocks away).
 *  Enderstorm        - up to 10 endermen, and they come for you during waves; pearl limit +2 a round.
 *  Shifting Ground   - every 20 seconds of a wave one block a player placed crumbles; block limit +2 a wave.
 *  Eclipse           - every 8 seconds of a wave, darkness for 2 seconds.
 *  Dragon's Breath   - purple clouds drift onto the floor near you and hurt while you stand in them.
 *  Featherweight     - every guard hit knocks you back about twice as far.
 *  Shulker Rain      - every 10 seconds of a wave, marked spots fall from the sky: get hit and you float up.
 *
 * Every number is in the constants below.
 */
final class EndOmens {
    private EndOmens() {}

    static final int VOID_TIDE = 0, UNSTABLE_PEARLS = 1, ENDERSTORM = 2, SHIFTING_GROUND = 3, ECLIPSE = 4, DRAGONS_BREATH = 5,
            FEATHERWEIGHT = 6, SHULKER_RAIN = 7;
    static final String[] NAMES = {"§3§lVoid Tide", "§d§lUnstable Pearls", "§5§lEnderstorm", "§8§lShifting Ground", "§0§lEclipse",
            "§5§lDragon's Breath", "§f§lFeatherweight", "§d§lShulker Rain"};
    static final String[] TIPS = {
            "§7The ledge under the rim is gone - falling off is deadly",
            "§7Your pearls wobble off course",
            "§7Up to 10 endermen, and they hunt you §8(+2 pearls a round)",
            "§7Blocks you place crumble one by one §8(+2 blocks a wave)",
            "§7The lights go out every few seconds",
            "§7Purple clouds drift onto the floor - don't stand in them",
            "§7Guard hits knock you back twice as far",
            "§7Marked spots fall from the sky and lift you up"};

    /** +25% Void Fragments per omen taken. */
    static final double FRAGMENT_BONUS = 0.25;
    static final int SHIFTING_EXTRA_BLOCKS = 2, SHIFTING_EVERY = 20;
    static final int ENDERSTORM_EXTRA_PEARLS = 2, ENDERSTORM_MAX = 10;
    /** Unstable Pearls: random push added to a pearl's speed on each axis (about 3 blocks over a long throw). */
    static final double PEARL_WOBBLE = 0.10;
    static final int ECLIPSE_EVERY = 8, ECLIPSE_TICKS = 40;
    /** Dragon's Breath: a new cloud every few seconds, how long it lasts, how wide, damage per second. */
    static final int BREATH_EVERY = 6, BREATH_SECONDS = 8;
    static final double BREATH_RADIUS = 2.5;
    static final float BREATH_DAMAGE = 2f;
    /** Shulker Rain: seconds between volleys, ticks of warning, reach, damage, how long you float. */
    static final int RAIN_EVERY = 10, RAIN_WARN = 30, RAIN_LEVITATION_TICKS = 40;
    static final double RAIN_RADIUS = 2.0;
    static final float RAIN_DAMAGE = 2f;

    private static final Random RNG = new Random();

    private record Cloud(EndExpedition.Run run, Vec3 at, long until) {}

    private static final class Mark {
        final EndExpedition.Run run;
        final Vec3 at;
        int ticks = RAIN_WARN;

        Mark(EndExpedition.Run run, Vec3 at) {
            this.run = run;
            this.at = at;
        }
    }

    private static final List<Cloud> CLOUDS = new ArrayList<>();
    private static final List<Mark> MARKS = new ArrayList<>();

    static String list(Set<Integer> omens) {
        if (omens.isEmpty()) return "§7none";
        StringBuilder b = new StringBuilder();
        for (int o : omens) b.append(b.length() == 0 ? "" : "§7, ").append(NAMES[o]);
        return b.toString();
    }

    // =====================================================================
    // Offering and picking
    // =====================================================================

    /** Before waves 1, 11, 21...: two new omens to choose from. */
    static void offerIfDue(EndExpedition.Run run) {
        if (run.wave % 10 != 0) return;
        List<Integer> all = new ArrayList<>();
        for (int i = 0; i < NAMES.length; i++) all.add(i);
        Collections.shuffle(all, RNG);
        run.offered[0] = all.get(0);
        run.offered[1] = all.get(1);
        run.chosen.clear();
        for (ServerPlayer p : EndExpedition.inside(run)) showOffer(run, p);
    }

    static void showOffer(EndExpedition.Run run, ServerPlayer p) {
        if (run.offered[0] < 0) return;
        int from = run.wave + 1;
        Lobby.msg(p, "§6[Captain Wren] §fOmens for waves §e" + from + "-" + (from + 9) + "§f, captain. Take any you dare - §5+"
                + Math.round(FRAGMENT_BONUS * 100) + "% Void Fragments §feach.");
        for (int i = 0; i < 2; i++) {
            int o = run.offered[i];
            boolean on = run.chosen.contains(o);
            MutableComponent line = Component.literal("  " + NAMES[o] + " §8- " + TIPS[o] + " ");
            line.append(Expedition.button(on ? "§a[Taken - click to drop]" : "§e[Take it]", "/endexp omen " + (i + 1), on ? "Drop this omen" : "Take this omen"));
            p.sendSystemMessage(line);
        }
    }

    static void toggle(EndExpedition.Run run, ServerPlayer by, int idx) {
        int o = run.offered[idx];
        boolean on = run.chosen.contains(o);
        if (on) run.chosen.remove(o);
        else run.chosen.add(o);
        for (ServerPlayer p : EndExpedition.inside(run)) {
            Lobby.msg(p, "§7" + by.getName().getString() + (on ? " dropped " : " took ") + NAMES[o] + "§7. Omens for the next 10 waves: " + list(run.chosen)
                    + (run.chosen.isEmpty() ? "" : " §5(+" + Math.round(FRAGMENT_BONUS * 100 * run.chosen.size()) + "% fragments)"));
        }
    }

    // =====================================================================
    // A block of 10 waves starts / ends
    // =====================================================================

    static void blockStarts(ServerLevel level, EndExpedition.Run run) {
        run.active.clear();
        run.active.addAll(run.chosen);
        run.chosen.clear();
        run.offered[0] = run.offered[1] = -1;
        if (run.active.isEmpty()) return;
        for (ServerPlayer p : EndExpedition.inside(run)) Announce.title(p, "§5§lOMENS", list(run.active), 5, 50, 10);
        if (run.omen(VOID_TIDE) && level != null) ledge(level, false);
    }

    static void blockEnds(ServerLevel level, EndExpedition.Run run) {
        if (run.omen(VOID_TIDE) && level != null) ledge(level, true);
        run.active.clear();
        forget(run);
    }

    static void runOver(ServerLevel level, EndExpedition.Run run) {
        forget(run); // the island (and its ledge) is rebuilt at the start of the next trip
    }

    private static void forget(EndExpedition.Run run) {
        CLOUDS.removeIf(c -> c.run() == run);
        MARKS.removeIf(m -> m.run == run);
    }

    /** Takes the ledge away (Void Tide) or puts it back. */
    private static void ledge(ServerLevel level, boolean back) {
        int reach = EndIsland.R + EndIsland.LEDGE_WIDTH + 4;
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int x = -reach; x <= reach; x++) {
            for (int z = -reach; z <= reach; z++) {
                if (!EndIsland.onLedge(x, z) || EndIsland.onIsland(x, z) && Math.hypot(x, z) < EndIsland.edgeAt(x, z) - 0.5) continue;
                m.set(x, EndIsland.LEDGE_Y, z);
                level.setBlock(m, back ? Blocks.END_STONE_BRICKS.defaultBlockState() : Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
            }
        }
    }

    // =====================================================================
    // Effects
    // =====================================================================

    /** Unstable Pearls: a thrown pearl gets a random nudge. */
    static void wobble(Entity pearl) {
        Vec3 v = pearl.getDeltaMovement();
        pearl.setDeltaMovement(v.add((RNG.nextDouble() - 0.5) * 2 * PEARL_WOBBLE, (RNG.nextDouble() - 0.5) * PEARL_WOBBLE, (RNG.nextDouble() - 0.5) * 2 * PEARL_WOBBLE));
    }

    /** Once a second during a wave. */
    static void second(ServerLevel level, EndExpedition.Run run, long sec) {
        if (run.active.isEmpty()) return;
        long t = sec - run.waveStartSec;
        List<ServerPlayer> ps = EndExpedition.fighters(run);
        if (ps.isEmpty()) return;
        if (run.omen(ECLIPSE) && t % ECLIPSE_EVERY == ECLIPSE_EVERY - 1)
            for (ServerPlayer p : ps) p.addEffect(new MobEffectInstance(MobEffects.DARKNESS, ECLIPSE_TICKS, 0, false, false));
        if (run.omen(SHIFTING_GROUND) && t > 0 && t % SHIFTING_EVERY == 0 && !run.placed.isEmpty()) {
            List<Long> placed = new ArrayList<>(run.placed);
            BlockPos b = BlockPos.of(placed.get(RNG.nextInt(placed.size())));
            run.placed.remove(b.asLong());
            if (!level.getBlockState(b).isAir() && !EndIsland.locked(b)) {
                level.setBlock(b, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                level.sendParticles(ParticleTypes.PORTAL, b.getX() + 0.5, b.getY() + 0.5, b.getZ() + 0.5, 20, 0.3, 0.3, 0.3, 0.4);
                level.playSound(null, b, SoundEvents.CHORUS_FLOWER_DEATH, SoundSource.BLOCKS, 1f, 0.8f);
            }
        }
        if (run.omen(DRAGONS_BREATH)) {
            if (t % BREATH_EVERY == 0) {
                ServerPlayer near = ps.get(RNG.nextInt(ps.size()));
                double a = RNG.nextDouble() * Math.PI * 2, d = 2 + RNG.nextDouble() * 5;
                int x = (int) Math.floor(near.getX() + Math.cos(a) * d), z = (int) Math.floor(near.getZ() + Math.sin(a) * d);
                int y = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                if (y > EndIsland.LEDGE_Y) CLOUDS.add(new Cloud(run, new Vec3(x + 0.5, y, z + 0.5), sec + BREATH_SECONDS));
            }
            for (Iterator<Cloud> it = CLOUDS.iterator(); it.hasNext(); ) {
                Cloud c = it.next();
                if (c.run() != run) continue;
                if (sec >= c.until()) {
                    it.remove();
                    continue;
                }
                level.sendParticles(ParticleTypes.WITCH, c.at().x, c.at().y + 0.3, c.at().z, 30, BREATH_RADIUS * 0.6, 0.15, BREATH_RADIUS * 0.6, 0.01);
                level.sendParticles(ParticleTypes.PORTAL, c.at().x, c.at().y + 0.3, c.at().z, 15, BREATH_RADIUS * 0.6, 0.2, BREATH_RADIUS * 0.6, 0.1);
                for (ServerPlayer p : ps) {
                    if (Math.hypot(p.getX() - c.at().x, p.getZ() - c.at().z) > BREATH_RADIUS || Math.abs(p.getY() - c.at().y) > 2) continue;
                    p.hurtServer(level, level.damageSources().magic(), BREATH_DAMAGE);
                    Announce.actionBar(p, "§5Dragon's breath! §7Get out of the cloud.");
                }
            }
        }
        if (run.omen(SHULKER_RAIN) && t > 0 && t % RAIN_EVERY == 0) {
            for (ServerPlayer p : ps) {
                MARKS.add(new Mark(run, p.position()));
                double a = RNG.nextDouble() * Math.PI * 2, d = 3 + RNG.nextDouble() * 6;
                MARKS.add(new Mark(run, new Vec3(p.getX() + Math.cos(a) * d, p.getY(), p.getZ() + Math.sin(a) * d)));
            }
            level.playSound(null, BlockPos.containing(ps.get(0).position()), SoundEvents.SHULKER_SHOOT, SoundSource.HOSTILE, 1.5f, 0.6f);
        }
    }

    /** Every tick: Shulker Rain marks counting down. */
    static void fastTick(ServerLevel level, EndExpedition.Run run) {
        if (MARKS.isEmpty()) return;
        for (Iterator<Mark> it = MARKS.iterator(); it.hasNext(); ) {
            Mark m = it.next();
            if (m.run != run) continue;
            m.ticks--;
            Vec3 a = m.at;
            if (m.ticks > 0) {
                if (m.ticks % 3 == 0) {
                    level.sendParticles(ParticleTypes.END_ROD, a.x, a.y + 0.1, a.z, 4, RAIN_RADIUS * 0.5, 0.02, RAIN_RADIUS * 0.5, 0);
                    level.sendParticles(ParticleTypes.END_ROD, a.x, a.y + m.ticks * 0.4, a.z, 1, 0.05, 0.05, 0.05, 0);
                }
                continue;
            }
            it.remove();
            level.sendParticles(ParticleTypes.END_ROD, a.x, a.y + 0.5, a.z, 25, 0.8, 0.5, 0.8, 0.1);
            level.playSound(null, BlockPos.containing(a), SoundEvents.SHULKER_BULLET_HIT, SoundSource.HOSTILE, 1f, 1f);
            Safe.run("end shulker rain", () -> {
                for (ServerPlayer p : EndExpedition.fighters(run)) {
                    if (Math.hypot(p.getX() - a.x, p.getZ() - a.z) > RAIN_RADIUS || Math.abs(p.getY() - a.y) > 3) continue;
                    p.hurtServer(level, level.damageSources().magic(), RAIN_DAMAGE);
                    p.addEffect(new MobEffectInstance(MobEffects.LEVITATION, RAIN_LEVITATION_TICKS, 0));
                }
            });
        }
    }
}
