package com.aidan.skyserver.bots;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraft.core.Direction;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Predicate;

/** Lets the pathfinder read a real Minecraft world. Make a new one per search (it caches blocks). */
public final class LevelWorld implements Pathfinder.World {
    private final ServerLevel level;
    private final Predicate<BlockPos> canBreak;
    private final Predicate<BlockPos> canPlace;
    private final int minY;
    private final Map<Long, BlockState> cache = new HashMap<>();
    private final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

    public LevelWorld(ServerLevel level, int minY, Predicate<BlockPos> canBreak, Predicate<BlockPos> canPlace) {
        this.level = level;
        this.minY = minY;
        this.canBreak = canBreak;
        this.canPlace = canPlace;
    }

    private BlockState state(int x, int y, int z) {
        long key = BlockPos.asLong(x, y, z);
        BlockState s = cache.get(key);
        if (s == null) {
            cursor.set(x, y, z);
            // Unloaded chunks count as walls (and we never load chunks just to look).
            s = level.isLoaded(cursor) ? level.getBlockState(cursor) : Blocks.BEDROCK.defaultBlockState();
            cache.put(key, s);
        }
        return s;
    }

    private VoxelShape shape(BlockState s, int x, int y, int z) {
        return s.getCollisionShape(level, cursor.set(x, y, z));
    }

    @Override
    public boolean passable(int x, int y, int z) {
        BlockState s = state(x, y, z);
        if (s.isAir()) return true;
        if (s.is(BlockTags.CLIMBABLE)) return true;
        if (s.getFluidState().is(FluidTags.LAVA)) return false;
        VoxelShape shape = shape(s, x, y, z);
        return shape.isEmpty() || shape.max(Direction.Axis.Y) <= 0.2; // carpets, plates
    }

    @Override
    public boolean standable(int x, int y, int z) {
        BlockState s = state(x, y, z);
        if (s.isAir()) return false;
        VoxelShape shape = shape(s, x, y, z);
        if (shape.isEmpty()) return false;
        double top = shape.max(Direction.Axis.Y);
        return top > 0.2 && top <= 1.0; // not fences/walls (1.5 tall)
    }

    @Override
    public boolean climbable(int x, int y, int z) {
        return state(x, y, z).is(BlockTags.CLIMBABLE);
    }

    @Override
    public boolean dangerous(int x, int y, int z) {
        BlockState s = state(x, y, z);
        if (s.isAir()) return false;
        return s.getFluidState().is(FluidTags.LAVA)
                || s.is(BlockTags.FIRE) || s.is(Blocks.MAGMA_BLOCK) || s.is(BlockTags.CAMPFIRES)
                || s.is(Blocks.CACTUS) || s.is(Blocks.SWEET_BERRY_BUSH) || s.is(Blocks.COBWEB)
                || s.is(Blocks.POWDER_SNOW) || s.is(Blocks.WITHER_ROSE) || s.is(Blocks.LAVA_CAULDRON);
    }

    @Override
    public boolean breakable(int x, int y, int z) {
        BlockState s = state(x, y, z);
        if (s.isAir() || s.getDestroySpeed(level, cursor.set(x, y, z)) < 0) return false;
        if (s.getDestroySpeed(level, cursor) > 5) return false; // too slow to dig through
        return canBreak.test(new BlockPos(x, y, z));
    }

    @Override
    public boolean placeable(int x, int y, int z) {
        BlockState s = state(x, y, z);
        return (s.isAir() || s.canBeReplaced()) && canPlace.test(new BlockPos(x, y, z));
    }

    @Override
    public int minY() {
        return minY;
    }
}
