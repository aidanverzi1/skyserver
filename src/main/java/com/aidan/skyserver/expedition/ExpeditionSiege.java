package com.aidan.skyserver.expedition;

import com.aidan.skyserver.Safe;
import com.aidan.skyserver.lobby.Lobby;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ProjectileWeaponItem;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.*;

/**
 * 5.25: NETHER EXPEDITION - ranged guards break the blocks players hide behind or tower up on.
 *
 *  - Only blocks a PLAYER placed during a trip can be broken (never the fortress / bastion, never a block with
 *    an inventory such as a chest). Placed blocks are remembered here (PLACED) from the moment they're put down.
 *  - A ranged guard (anything holding a bow or crossbow, and blazes) that is after a player...
 *      a) and has had no clear shot at them for NO_SHOT_TICKS: breaks the first PLACED block between it and
 *         the player (a real wall in the way first = nothing happens, hiding behind the fortress is fair);
 *      b) or whose player stands on a stack of TOWER_HEIGHT or more placed blocks: breaks the block under
 *         their feet.
 *  - The block cracks first (sound + sparks), and is gone WARN_TICKS later. Nothing drops. Each guard breaks at
 *    most one block every COOLDOWN_TICKS.
 *
 * Started from Expedition.init(). Edit the numbers below.
 */
final class ExpeditionSiege {
    private ExpeditionSiege() {}

    static final int NO_SHOT_TICKS = 60, COOLDOWN_TICKS = 40, WARN_TICKS = 10, TOWER_HEIGHT = 2, STEP = 10;
    static final double RANGE = 24;

    /** Blocks players placed in the expedition world (block position as a long). */
    private static final Set<Long> PLACED = new HashSet<>();
    /** Per ranged guard: {ticks without a clear shot, tick of its last break}. */
    private static final Map<UUID, long[]> GUARDS = new HashMap<>();
    /** Blocks that are cracking: position -> the tick they go. */
    private static final Map<Long, Long> CRACKING = new HashMap<>();
    private static long ticks;

    static void init() {
        UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
            if (level.isClientSide() || !Expedition.isExpedition(level) || !(player instanceof ServerPlayer)) return InteractionResult.PASS;
            ItemStack held = player.getItemInHand(hand);
            if (!(held.getItem() instanceof BlockItem)) return InteractionResult.PASS;
            BlockPos clicked = hit.getBlockPos();
            BlockPos target = level.getBlockState(clicked).canBeReplaced() ? clicked : clicked.relative(hit.getDirection());
            BlockState before = level.getBlockState(target);
            // Looked at again a moment later: if a block appeared there, the player placed it.
            Lobby.runLater(() -> {
                BlockState after = level.getBlockState(target);
                if (after != before && !after.isAir()) PLACED.add(target.asLong());
            });
            return InteractionResult.PASS;
        });
        ServerTickEvents.END_SERVER_TICK.register(s -> {
            ticks++;
            if (ticks % STEP == 0) Safe.run("expedition siege", ExpeditionSiege::tick);
        });
    }

    static boolean isPlaced(BlockPos pos) {
        return PLACED.contains(pos.asLong());
    }

    private static boolean ranged(Mob m) {
        return m.getType() == EntityTypes.BLAZE || m.getMainHandItem().getItem() instanceof ProjectileWeaponItem;
    }

    private static void tick() {
        ServerLevel level = Expedition.level();
        if (level == null) return;
        List<ServerPlayer> players = level.players();
        if (players.isEmpty()) {
            PLACED.clear();
            GUARDS.clear();
            CRACKING.clear();
            return;
        }
        // Blocks that finished cracking.
        for (Iterator<Map.Entry<Long, Long>> it = CRACKING.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Long, Long> e = it.next();
            if (ticks < e.getValue()) continue;
            it.remove();
            BlockPos pos = BlockPos.of(e.getKey());
            if (!PLACED.remove(e.getKey())) continue;
            BlockState st = level.getBlockState(pos);
            if (st.isAir() || level.getBlockEntity(pos) != null) continue;
            level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            level.playSound(null, pos, SoundEvents.GLASS_BREAK, SoundSource.HOSTILE, 1.2f, 0.6f);
            level.sendParticles(ParticleTypes.CRIT, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, 20, 0.3, 0.3, 0.3, 0.2);
        }
        Set<UUID> seen = new HashSet<>();
        for (ServerPlayer p : players) {
            if (p.isSpectator() || p.isCreative() || !p.isAlive()) continue;
            List<Mob> mobs = level.getEntitiesOfClass(Mob.class, p.getBoundingBox().inflate(RANGE),
                    m -> m.isAlive() && m.entityTags().contains(Expedition.GUARD_TAG) && ranged(m));
            if (mobs.isEmpty()) continue;
            BlockPos tower = towerTop(level, p);
            for (Mob m : mobs) {
                if (m.getTarget() != null && m.getTarget() != p) continue;
                if (!seen.add(m.getUUID())) continue;
                long[] st = GUARDS.computeIfAbsent(m.getUUID(), k -> new long[]{0, -COOLDOWN_TICKS});
                boolean sees = m.hasLineOfSight(p);
                st[0] = sees ? 0 : st[0] + STEP;
                if (ticks - st[1] < COOLDOWN_TICKS) continue;
                BlockPos hit = null;
                if (tower != null && sees) hit = tower;
                else if (!sees && st[0] >= NO_SHOT_TICKS) hit = firstPlacedBetween(level, m.getEyePosition(), p.getEyePosition());
                if (hit == null && tower != null && st[0] >= NO_SHOT_TICKS) hit = tower;
                if (hit == null || CRACKING.containsKey(hit.asLong())) continue;
                st[1] = ticks;
                CRACKING.put(hit.asLong(), ticks + WARN_TICKS);
                level.playSound(null, hit, SoundEvents.GRAVEL_BREAK, SoundSource.HOSTILE, 1.4f, 0.5f);
                level.sendParticles(ParticleTypes.CRIT, hit.getX() + 0.5, hit.getY() + 0.5, hit.getZ() + 0.5, 10, 0.3, 0.3, 0.3, 0.1);
            }
        }
        GUARDS.keySet().retainAll(seen);
    }

    /** The block under the player's feet, if they stand on TOWER_HEIGHT or more placed blocks stacked up. */
    private static BlockPos towerTop(ServerLevel level, ServerPlayer p) {
        if (!p.onGround()) return null;
        BlockPos top = BlockPos.containing(p.getX(), p.getY() - 0.2, p.getZ());
        for (int i = 0; i < TOWER_HEIGHT; i++) {
            BlockPos b = top.below(i);
            if (!PLACED.contains(b.asLong()) || level.getBlockState(b).isAir()) return null;
        }
        return level.getBlockEntity(top) == null ? top : null;
    }

    /**
     * Walks from the guard's eyes to the player's: the first solid block on the way, if a player placed it.
     * (A fortress block first = null: the guard can't shoot through real walls.)
     */
    private static BlockPos firstPlacedBetween(ServerLevel level, Vec3 from, Vec3 to) {
        Vec3 d = to.subtract(from);
        double len = d.length();
        if (len < 0.5 || len > RANGE + 4) return null;
        Vec3 step = d.scale(0.25 / len);
        Vec3 at = from;
        long last = Long.MIN_VALUE;
        for (double done = 0; done < len; done += 0.25) {
            at = at.add(step);
            BlockPos pos = BlockPos.containing(at.x, at.y, at.z);
            long key = pos.asLong();
            if (key == last) continue;
            last = key;
            BlockState st = level.getBlockState(pos);
            if (st.isAir() || !st.isSolid()) continue;
            return PLACED.contains(key) && level.getBlockEntity(pos) == null ? pos : null;
        }
        return null;
    }
}
