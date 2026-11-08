package com.aidan.skyserver.skywars;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEgg;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Bridge Eggs: when thrown, the egg disappears and a straight line of
 * 20 dirt blocks builds out from the thrower's feet in the direction they threw.
 */
public final class BridgeEggs {
    public static final int LENGTH = 20;
    private static final int BLOCKS_PER_TICK = 2; // builds out over half a second

    private static final List<Bridge> active = new ArrayList<>();

    private static final class Bridge {
        final ServerLevel level;
        final double startX, startZ, dirX, dirZ;
        final int y;
        int placed = 0;
        double distance = 0.5; // start just past the block the player stands on
        BlockPos last = null;

        Bridge(ServerLevel level, double startX, int y, double startZ, double dirX, double dirZ) {
            this.level = level;
            this.startX = startX;
            this.y = y;
            this.startZ = startZ;
            this.dirX = dirX;
            this.dirZ = dirZ;
        }
    }

    private BridgeEggs() {}

    /** Call every tick while a match is running. */
    public static void tick(ServerLevel level) {
        catchNewEggs(level);
        buildActiveBridges();
    }

    public static void clearAll() {
        active.clear();
    }

    private static void catchNewEggs(ServerLevel level) {
        AABB arena = SkywarsMap.arenaBox();
        for (ThrownEgg egg : level.getEntitiesOfClass(ThrownEgg.class, arena, e -> true)) {
            if (!SkywarsLoot.isBridgeEgg(egg.getItem())) continue;
            if (!(egg.getOwner() instanceof ServerPlayer owner)) continue;

            // Use the throw direction, flattened so the bridge is perfectly level.
            Vec3 motion = egg.getDeltaMovement();
            double len = Math.sqrt(motion.x * motion.x + motion.z * motion.z);
            if (len < 0.01) {
                // Thrown straight up/down: fall back to where the player is looking.
                float yaw = owner.getYRot() * Mth.DEG_TO_RAD;
                motion = new Vec3(-Mth.sin(yaw), 0, Mth.cos(yaw));
                len = 1;
            }
            double dx = motion.x / len;
            double dz = motion.z / len;

            int y = Mth.floor(owner.getY()) - 1; // level with the block under their feet
            active.add(new Bridge(level, owner.getX(), y, owner.getZ(), dx, dz));
            egg.discard(); // no chickens, no extra effects
        }
    }

    private static void buildActiveBridges() {
        Iterator<Bridge> it = active.iterator();
        while (it.hasNext()) {
            Bridge b = it.next();
            int placedThisTick = 0;
            int safety = 0;
            while (placedThisTick < BLOCKS_PER_TICK && b.placed < LENGTH && safety++ < 20) {
                // Walk forward in small steps so diagonal bridges have no gaps.
                b.distance += 0.25;
                BlockPos pos = BlockPos.containing(
                        b.startX + b.dirX * b.distance, b.y, b.startZ + b.dirZ * b.distance);
                if (pos.equals(b.last)) continue;

                // If we moved diagonally, fill the corner so the bridge is walkable.
                if (b.last != null && pos.getX() != b.last.getX() && pos.getZ() != b.last.getZ()) {
                    place(b.level, new BlockPos(pos.getX(), b.y, b.last.getZ()));
                }
                place(b.level, pos);
                b.last = pos;
                b.placed++;
                placedThisTick++;
            }
            if (b.placed >= LENGTH) it.remove();
        }
    }

    private static void place(ServerLevel level, BlockPos pos) {
        if (level.getBlockState(pos).isAir()) {
            level.setBlock(pos, Blocks.DIRT.defaultBlockState(), Block.UPDATE_ALL);
        }
    }
}
