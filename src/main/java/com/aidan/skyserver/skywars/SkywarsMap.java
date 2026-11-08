package com.aidan.skyserver.skywars;

import com.aidan.skyserver.goldwars.GoldWarsMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds the current Skywars map (from {@link SkywarsDesigns}) in the Skywars dimension and knows
 * where the spawns and chests are. Maps rotate every match.
 */
public final class SkywarsMap {
    public static final int CENTER_X = 0;
    public static final int CENTER_Z = 0;
    public static final int ISLAND_Y = 120;      // top (grass) layer of the spawn islands
    public static final int VOID_Y = 75;         // fall below this = eliminated
    public static final int SPAWN_COUNT = SkywarsDesigns.SPAWNS;

    private static final int CLEAR_RADIUS = 80;
    private static final int CLEAR_MIN_Y = 80;
    private static final int CLEAR_MAX_Y = 170;
    private static final int FLAGS = Block.UPDATE_CLIENTS;

    private static int themeIndex = 0;
    private static SkywarsDesigns.Layout layout = SkywarsDesigns.build(SkywarsDesigns.Theme.values()[0]);

    private SkywarsMap() {}

    public static SkywarsDesigns.Theme theme() {
        return layout.theme;
    }

    public static int themeIndex() {
        return themeIndex;
    }

    /** Picks a map by number (call before rebuilding). */
    public static void setTheme(int i) {
        themeIndex = i;
        layout = SkywarsDesigns.build(SkywarsDesigns.Theme.values()[themeIndex]);
    }

    /** Switches to the next map (call before rebuilding). */
    public static void nextMap() {
        themeIndex = (themeIndex + 1) % SkywarsDesigns.Theme.values().length;
        layout = SkywarsDesigns.build(SkywarsDesigns.Theme.values()[themeIndex]);
    }

    private static BlockPos abs(int[] p) {
        return new BlockPos(CENTER_X + p[0], ISLAND_Y + p[1], CENTER_Z + p[2]);
    }

    /** Block position the player stands in for spawn number i (0..7). */
    public static BlockPos spawn(int i) {
        return abs(layout.spawns.get(i));
    }

    /** The whole arena volume, used to find eggs, TNT and dropped items. */
    public static AABB arenaBox() {
        return new AABB(CENTER_X - CLEAR_RADIUS, CLEAR_MIN_Y - 20, CENTER_Z - CLEAR_RADIUS,
                CENTER_X + CLEAR_RADIUS, CLEAR_MAX_Y + 1, CENTER_Z + CLEAR_RADIUS);
    }

    /** Places the supply drop chest on the centre island, on the first free block going up. */
    public static BlockPos placeSupplyDrop(ServerLevel level) {
        BlockPos pos = abs(layout.supplyDrop);
        while (!level.getBlockState(pos).isAir() && pos.getY() < CLEAR_MAX_Y) pos = pos.above();
        level.setBlock(pos, Blocks.CHEST.defaultBlockState(), Block.UPDATE_ALL);
        return pos;
    }

    public static BlockPos supplyDropSpot() {
        return abs(layout.supplyDrop);
    }

    public static BlockPos spectatorPoint() {
        return abs(layout.spectator);
    }

    /** The three chests on spawn island i. */
    public static List<BlockPos> islandChests(int spawn) {
        List<BlockPos> list = new ArrayList<>();
        for (int[] c : layout.islandChests.get(spawn)) list.add(abs(c));
        return list;
    }

    public static List<BlockPos> allIslandChests() {
        List<BlockPos> list = new ArrayList<>();
        for (int i = 0; i < SPAWN_COUNT; i++) list.addAll(islandChests(i));
        return list;
    }

    public static List<BlockPos> midChests() {
        List<BlockPos> list = new ArrayList<>();
        for (int[] c : layout.midChests) list.add(abs(c));
        return list;
    }

    public static List<BlockPos> centerChests() {
        List<BlockPos> list = new ArrayList<>();
        for (int[] c : layout.centerChests) list.add(abs(c));
        return list;
    }

    /** Wipes the arena area and builds a fresh copy of the current map with cages. */
    public static void rebuild(ServerLevel level) {
        clear(level);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        layout.canvas.forEach((x, y, z, text) ->
                level.setBlock(pos.set(CENTER_X + x, ISLAND_Y + y, CENTER_Z + z), GoldWarsMap.toState(text), FLAGS));
        // Chests face away from the wall/toward the open side; any facing works for looting.
        for (BlockPos c : allIslandChests()) placeChest(level, c);
        for (BlockPos c : midChests()) placeChest(level, c);
        for (BlockPos c : centerChests()) placeChest(level, c);
        setCages(level, true);
    }

    private static void placeChest(ServerLevel level, BlockPos pos) {
        // Face the chest toward the map centre so it looks deliberate.
        double dx = CENTER_X - pos.getX(), dz = CENTER_Z - pos.getZ();
        Direction face = Math.abs(dx) > Math.abs(dz) ? (dx > 0 ? Direction.EAST : Direction.WEST) : (dz > 0 ? Direction.SOUTH : Direction.NORTH);
        BlockState chest = Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, face);
        level.setBlock(pos, chest, FLAGS);
    }

    /** Builds (true) or removes (false) the glass cage around every spawn. */
    public static void setCages(ServerLevel level, boolean present) {
        BlockState state = present ? Blocks.GLASS.defaultBlockState() : Blocks.AIR.defaultBlockState();
        for (int i = 0; i < SPAWN_COUNT; i++) {
            BlockPos s = spawn(i);
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    boolean wall = dx != 0 || dz != 0;
                    if (wall) {
                        level.setBlock(s.offset(dx, 0, dz), state, FLAGS);
                        level.setBlock(s.offset(dx, 1, dz), state, FLAGS);
                    }
                    level.setBlock(s.offset(dx, 2, dz), state, FLAGS); // roof
                }
            }
        }
    }

    /** Sets everything in the arena box to air and removes dropped items. */
    private static void clear(ServerLevel level) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        BlockState air = Blocks.AIR.defaultBlockState();
        for (int x = CENTER_X - CLEAR_RADIUS; x <= CENTER_X + CLEAR_RADIUS; x++) {
            for (int z = CENTER_Z - CLEAR_RADIUS; z <= CENTER_Z + CLEAR_RADIUS; z++) {
                for (int y = CLEAR_MIN_Y; y <= CLEAR_MAX_Y; y++) {
                    pos.set(x, y, z);
                    BlockState current = level.getBlockState(pos);
                    if (current.isAir()) continue;
                    // Empty containers first so chests don't spill items when removed.
                    if (current.hasBlockEntity() && level.getBlockEntity(pos) instanceof Container c) c.clearContent();
                    level.setBlock(pos, air, FLAGS);
                }
            }
        }
        for (Entity e : level.getEntitiesOfClass(Entity.class, arenaBox(),
                e -> e instanceof ItemEntity || e instanceof PrimedTnt || e instanceof Projectile)) {
            e.discard();
        }
    }
}
