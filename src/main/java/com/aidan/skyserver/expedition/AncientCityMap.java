package com.aidan.skyserver.expedition;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

import java.util.*;

/**
 * 5.19: the Ancient City Expedition's map - one big dark deepslate cavern, built fresh for every trip in
 * its own world (skyserver:ancient_city, deep dark biome: nothing spawns there by itself).
 *
 * Layout (from the middle, floor = FLOOR_Y, the first air layer):
 *  - The cavern: 93 x 93 inside, 20 tall, closed in by deepslate (none of the city can be broken).
 *  - The middle: the reinforced deepslate PORTAL FRAME. On its pedestal sits the relic (a sculk catalyst).
 *  - The south wall: WREN'S LIFT, where the crew lands and where the relic must be brought back.
 *    Nothing can hurt you inside it.
 *  - A ring of small deepslate HOUSES around the frame, each with one chest (every player loots it once).
 *  - Sculk patches, SCULK SENSORS and SCULK SHRIEKERS (vanilla: footsteps, opening chests, breaking and
 *    placing are heard; shriekers raise your warning level and the 4th warning brings the Warden).
 *  - Grey carpet paths from the lift toward the houses: wool muffles footsteps, but the paths have gaps.
 *  - Deepslate brick pillars for cover, soul lanterns and candles for a little light.
 * Numbers by difficulty are in the arrays below (index 0 Easy .. 3 Nightmare).
 */
final class AncientCityMap {
    private AncientCityMap() {}

    static final int FLOOR_Y = 64, R = 46, HEIGHT = 20;
    static final int[] HOUSES = {5, 6, 7, 8};
    static final int[] SENSORS = {26, 36, 50, 64};
    static final int[] SHRIEKERS = {5, 7, 10, 13};
    static final int[] SCULK_PATCHES = {10, 14, 18, 22};
    /** Chance (%) a carpet path block is missing (so you still have to sneak across the gaps). */
    static final int[] CARPET_GAPS = {25, 40, 55, 70};
    /** Chance (%) a house has a sensor just inside its door. */
    static final int[] HOUSE_SENSOR = {25, 50, 75, 100};
    static final int PILLARS = 14, HOUSE_RING = 30;
    private static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;

    /** Everything planned for one trip. */
    static final class Layout {
        BlockPos center, relic;
        /** Where the crew lands, and where Captain Wren stands {x, y, z, yaw}. */
        double[] landing, wren;
        /** The lift: the safe box at the south wall {x0, z0, x1, z1} (inclusive, world coords). */
        int[] lift;
        final List<BlockPos> chests = new ArrayList<>();
        final List<BlockPos> shriekers = new ArrayList<>();
        /** Every block the city is made of (none can be broken). */
        final Long2ObjectOpenHashMap<BlockState> blocks = new Long2ObjectOpenHashMap<>();
        Builder builder;

        boolean inLift(double x, double y, double z) {
            return x >= lift[0] && x < lift[2] + 1 && z >= lift[1] && z < lift[3] + 1 && y >= FLOOR_Y - 1 && y < FLOOR_Y + HEIGHT;
        }

        boolean isCityBlock(BlockPos p) {
            BlockState s = blocks.get(p.asLong());
            return s != null && !s.isAir();
        }

        int[] box() {
            return new int[]{center.getX() - R - 3, center.getZ() - R - 3, center.getX() + R + 3, center.getZ() + R + 3};
        }
    }

    private static BlockState b(String id) {
        return BuiltInRegistries.BLOCK.getValue(Identifier.withDefaultNamespace(id)).defaultBlockState();
    }

    // =====================================================================
    // Planning
    // =====================================================================

    static Layout plan(ServerLevel level, BlockPos center, int d, Random rng) {
        Layout lay = new Layout();
        lay.center = center;
        Long2ObjectOpenHashMap<BlockState> c = lay.blocks;
        int cx = center.getX(), cz = center.getZ(), fy = FLOOR_Y;
        BlockState deepslate = b("deepslate"), tiles = b("deepslate_tiles"), cobbled = b("cobbled_deepslate"),
                cracked = b("cracked_deepslate_tiles"), bricks = b("deepslate_bricks"), polished = b("polished_deepslate"),
                reinforced = b("reinforced_deepslate"), chiseled = b("chiseled_deepslate"), sculk = b("sculk"),
                soulLantern = b("soul_lantern"), crackedBricks = b("cracked_deepslate_bricks");

        // ---- The shell: floor, ceiling, walls ----
        for (int x = -R - 2; x <= R + 2; x++)
            for (int z = -R - 2; z <= R + 2; z++) {
                boolean wall = Math.abs(x) > R || Math.abs(z) > R;
                for (int y = fy - 3; y <= fy + HEIGHT + 3; y++) {
                    boolean floor = y < fy, ceiling = y > fy + HEIGHT;
                    if (!wall && !floor && !ceiling) continue;
                    BlockState s = deepslate;
                    if (y == fy - 1 && !wall) {
                        int r = rng.nextInt(100);
                        s = r < 20 ? cobbled : r < 25 ? cracked : tiles;
                    }
                    set(c, cx + x, y, cz + z, s);
                }
            }
        // A few deepslate lumps hanging from the ceiling.
        for (int i = 0; i < 40; i++) {
            int x = rng.nextInt(2 * R - 4) - R + 2, z = rng.nextInt(2 * R - 4) - R + 2, len = 1 + rng.nextInt(4);
            for (int y = 0; y < len; y++) set(c, cx + x, fy + HEIGHT - y, cz + z, y == len - 1 && rng.nextBoolean() ? cobbled : deepslate);
        }

        // ---- Sculk patches on the floor ----
        for (int i = 0; i < SCULK_PATCHES[d]; i++) {
            int px = rng.nextInt(2 * R - 8) - R + 4, pz = rng.nextInt(2 * R - 20) - R + 4, rad = 2 + rng.nextInt(3);
            for (int x = -rad; x <= rad; x++)
                for (int z = -rad; z <= rad; z++)
                    if (x * x + z * z <= rad * rad + rng.nextInt(3)) set(c, cx + px + x, fy - 1, cz + pz + z, sculk);
        }

        // ---- Wren's lift (south wall) ----
        int lz0 = cz + R - 8, lz1 = cz + R;
        lay.lift = new int[]{cx - 5, lz0, cx + 5, lz1};
        for (int x = -5; x <= 5; x++)
            for (int z = lz0; z <= lz1; z++) set(c, cx + x, fy - 1, z, (Math.abs(x) == 5 || z == lz0) ? chiseled : polished);
        for (int[] p : new int[][]{{-5, lz0}, {5, lz0}, {-5, lz1}, {5, lz1}}) {
            set(c, cx + p[0], fy, p[1], bricks);
            set(c, cx + p[0], fy + 1, p[1], soulLantern);
        }
        lay.landing = new double[]{cx + 0.5, fy, lz1 - 3.5, 180};
        lay.wren = new double[]{cx + 0.5, fy, lz1 - 0.5, 180};

        // ---- The portal frame and the relic ----
        for (int side = -1; side <= 1; side += 2)
            for (int x = 5; x <= 7; x++)
                for (int z = -1; z <= 1; z++)
                    for (int y = 0; y <= 9; y++) set(c, cx + side * x, fy + y, cz + z, reinforced);
        for (int x = -7; x <= 7; x++)
            for (int z = -1; z <= 1; z++)
                for (int y = 10; y <= 11; y++) set(c, cx + x, fy + y, cz + z, reinforced);
        for (int x = -4; x <= 4; x++)
            for (int z = -4; z <= 4; z++)
                if (Math.abs(x) <= 4 && Math.abs(z) <= 4) set(c, cx + x, fy - 1, cz + z, Math.abs(x) == 4 || Math.abs(z) == 4 ? chiseled : polished);
        for (int x = -1; x <= 1; x++)
            for (int z = -1; z <= 1; z++) set(c, cx + x, fy, cz + z, x == 0 && z == 0 ? reinforced : polished);
        lay.relic = new BlockPos(cx, fy + 1, cz);
        set(c, cx, fy + 1, cz, b("sculk_catalyst"));

        // ---- Houses around the frame ----
        List<int[]> houses = new ArrayList<>(); // {x, z, doorSide}
        int n = HOUSES[d];
        for (int i = 0; i < n; i++) {
            double a = Math.toRadians(40 + 280.0 * (i + 0.5) / n + (rng.nextDouble() - 0.5) * 12);
            int hx = (int) Math.round(Math.sin(a) * HOUSE_RING), hz = (int) Math.round(Math.cos(a) * HOUSE_RING);
            // The door faces the frame (0 N, 1 E, 2 S, 3 W).
            int door = Math.abs(hx) > Math.abs(hz) ? (hx > 0 ? 3 : 1) : (hz > 0 ? 0 : 2);
            houses.add(new int[]{hx, hz, door});
            house(lay, c, cx + hx, cz + hz, door, d, rng, bricks, crackedBricks, tiles, polished, soulLantern);
        }

        // ---- Carpet paths from the lift toward each house door (with gaps) ----
        BlockState carpet = Blocks.CARPET.pick(DyeColor.GRAY).defaultBlockState();
        for (int[] h : houses) {
            int[] dd = SIDE[h[2]];
            int tx = h[0] + dd[0] * 5, tz = h[1] + dd[1] * 5;
            int sx = 0, sz = R - 9;
            int steps = Math.max(Math.abs(tx - sx), Math.abs(tz - sz));
            for (int s = 0; s <= steps; s++) {
                int x = cx + sx + Math.round((tx - sx) * s / (float) Math.max(1, steps));
                int z = cz + sz + Math.round((tz - sz) * s / (float) Math.max(1, steps));
                if (rng.nextInt(100) < CARPET_GAPS[d] || has(c, x, fy, z) || inFrame(x - cx, z - cz)) continue;
                set(c, x, fy, z, carpet);
            }
        }

        // ---- Pillars ----
        List<int[]> pillars = new ArrayList<>();
        for (int tries = 0; tries < 400 && pillars.size() < PILLARS; tries++) {
            int x = rng.nextInt(2 * R - 8) - R + 4, z = rng.nextInt(2 * R - 20) - R + 4;
            if (Math.abs(x) < 12 && Math.abs(z) < 12) continue;
            if (nearHouse(houses, x, z, 7)) continue;
            boolean close = false;
            for (int[] p : pillars) if (Math.abs(p[0] - x) < 7 && Math.abs(p[1] - z) < 7) close = true;
            if (close) continue;
            pillars.add(new int[]{x, z});
            for (int y = 0; y <= HEIGHT; y++)
                for (int ox = 0; ox <= 1; ox++)
                    for (int oz = 0; oz <= 1; oz++) set(c, cx + x + ox, fy + y, cz + z + oz, rng.nextInt(100) < 8 ? crackedBricks : bricks);
            set(c, cx + x - 1, fy, cz + z, soulLantern);
        }

        // ---- Shriekers (on sculk, with room around them for the Warden to climb out) ----
        BlockState shrieker = b("sculk_shrieker").setValue(BlockStateProperties.CAN_SUMMON, true);
        for (int tries = 0; tries < 2000 && lay.shriekers.size() < SHRIEKERS[d]; tries++) {
            int x = rng.nextInt(2 * R - 10) - R + 5, z = rng.nextInt(2 * R - 24) - R + 5;
            if (inFrame(x, z) || nearHouse(houses, x, z, 6) || z > R - 14) continue;
            boolean clear = true;
            for (int ox = -2; ox <= 2 && clear; ox++)
                for (int oz = -2; oz <= 2 && clear; oz++)
                    for (int y = 0; y <= 3; y++) if (has(c, cx + x + ox, fy + y, cz + z + oz)) { clear = false; break; }
            if (!clear) continue;
            for (BlockPos s : lay.shriekers) if (Math.abs(s.getX() - cx - x) < 9 && Math.abs(s.getZ() - cz - z) < 9) clear = false;
            if (!clear) continue;
            for (int ox = -1; ox <= 1; ox++) for (int oz = -1; oz <= 1; oz++) set(c, cx + x + ox, fy - 1, cz + z + oz, sculk);
            set(c, cx + x, fy, cz + z, shrieker);
            lay.shriekers.add(new BlockPos(cx + x, fy, cz + z));
        }

        // ---- Sensors (a ring of them around the relic, the rest anywhere) ----
        BlockState sensor = b("sculk_sensor");
        int placed = 0, nearRelic = 4 + 2 * d;
        for (int tries = 0; tries < 4000 && placed < SENSORS[d]; tries++) {
            int x, z;
            if (placed < nearRelic) {
                double a = rng.nextDouble() * Math.PI * 2, rr = 5 + rng.nextDouble() * 3;
                x = (int) Math.round(Math.cos(a) * rr);
                z = (int) Math.round(Math.sin(a) * rr);
            } else {
                x = rng.nextInt(2 * R - 4) - R + 2;
                z = rng.nextInt(2 * R - 16) - R + 2;
            }
            if (z > R - 12 || inFrame(x, z) || insideHouse(houses, x, z)) continue;
            if (has(c, cx + x, fy, cz + z) || has(c, cx + x, fy + 1, cz + z)) continue;
            set(c, cx + x, fy, cz + z, sensor);
            placed++;
        }
        lay.builder = new Builder(level, c);
        return lay;
    }

    private static final int[][] SIDE = {{0, -1}, {1, 0}, {0, 1}, {-1, 0}};

    /** A 7 x 7 deepslate house with its door toward the frame and a chest at the back. */
    private static void house(Layout lay, Long2ObjectOpenHashMap<BlockState> c, int hx, int hz, int door, int d, Random rng,
                              BlockState bricks, BlockState cracked, BlockState tiles, BlockState polished, BlockState lantern) {
        int fy = FLOOR_Y;
        for (int x = -3; x <= 3; x++)
            for (int z = -3; z <= 3; z++) {
                set(c, hx + x, fy - 1, hz + z, polished);
                set(c, hx + x, fy + 5, hz + z, tiles);
                boolean edge = Math.abs(x) == 3 || Math.abs(z) == 3;
                if (!edge) continue;
                for (int y = 0; y <= 4; y++) set(c, hx + x, fy + y, hz + z, rng.nextInt(100) < 10 ? cracked : bricks);
            }
        int[] dd = SIDE[door];
        // The doorway (1 wide, 2 tall) in the middle of the door wall.
        int doorX = hx + dd[0] * 3, doorZ = hz + dd[1] * 3;
        c.remove(BlockPos.asLong(doorX, fy, doorZ));
        c.remove(BlockPos.asLong(doorX, fy + 1, doorZ));
        // The chest against the back wall, facing the door.
        BlockPos chest = new BlockPos(hx - dd[0] * 2, fy, hz - dd[1] * 2);
        Direction facing = switch (door) {
            case 0 -> Direction.NORTH;
            case 1 -> Direction.EAST;
            case 2 -> Direction.SOUTH;
            default -> Direction.WEST;
        };
        set(c, chest.getX(), chest.getY(), chest.getZ(), Blocks.CHEST.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, facing));
        lay.chests.add(chest);
        // A soul lantern in a back corner, a lit candle beside the chest.
        int px = dd[1] != 0 ? 2 : 0, pz = dd[0] != 0 ? 2 : 0;
        set(c, hx - dd[0] * 2 + px, fy, hz - dd[1] * 2 + pz, lantern);
        set(c, hx - dd[0] * 2 - px, fy, hz - dd[1] * 2 - pz,
                b("candle").setValue(BlockStateProperties.LIT, true).setValue(BlockStateProperties.CANDLES, 1 + rng.nextInt(3)));
        // Sometimes a sensor right inside the door.
        if (rng.nextInt(100) < HOUSE_SENSOR[d])
            set(c, hx + dd[0] * 2, fy, hz + dd[1] * 2, BuiltInRegistries.BLOCK.getValue(Identifier.withDefaultNamespace("sculk_sensor")).defaultBlockState());
    }

    private static boolean inFrame(int x, int z) {
        return Math.abs(x) <= 8 && Math.abs(z) <= 4;
    }

    private static boolean nearHouse(List<int[]> houses, int x, int z, int pad) {
        for (int[] h : houses) if (Math.abs(h[0] - x) <= 3 + pad && Math.abs(h[1] - z) <= 3 + pad) return true;
        return false;
    }

    private static boolean insideHouse(List<int[]> houses, int x, int z) {
        for (int[] h : houses) if (Math.abs(h[0] - x) <= 4 && Math.abs(h[1] - z) <= 4) return true;
        return false;
    }

    private static void set(Long2ObjectOpenHashMap<BlockState> c, int x, int y, int z, BlockState s) {
        c.put(BlockPos.asLong(x, y, z), s);
    }

    private static boolean has(Long2ObjectOpenHashMap<BlockState> c, int x, int y, int z) {
        return c.containsKey(BlockPos.asLong(x, y, z));
    }

    // =====================================================================
    // Building (a chunk at a time, ~8 ms a tick, like the Nether map)
    // =====================================================================

    static final class Builder {
        final ServerLevel level;
        final long[] order;
        final BlockState[] states;
        final Set<Long> chunks = new HashSet<>();
        int next = 0;
        boolean done = false;

        Builder(ServerLevel level, Long2ObjectOpenHashMap<BlockState> c) {
            this.level = level;
            Long2ObjectOpenHashMap<List<Long>> byChunk = new Long2ObjectOpenHashMap<>();
            for (Long2ObjectMap.Entry<BlockState> e : c.long2ObjectEntrySet()) {
                long p = e.getLongKey();
                long ck = ((long) (BlockPos.getX(p) >> 4) & 0xFFFFFFFFL) | (((long) (BlockPos.getZ(p) >> 4) & 0xFFFFFFFFL) << 32);
                byChunk.computeIfAbsent(ck, k -> new ArrayList<>()).add(p);
            }
            order = new long[c.size()];
            states = new BlockState[c.size()];
            int i = 0;
            long[] keys = byChunk.keySet().toLongArray();
            Arrays.sort(keys);
            for (long ck : keys) {
                chunks.add(ck);
                for (long p : byChunk.get(ck)) {
                    order[i] = p;
                    states[i] = c.get(p);
                    i++;
                }
            }
            for (long ck : chunks) level.setChunkForced(ChunkPos.getX(ck), ChunkPos.getZ(ck), true);
        }

        double progress() {
            return order.length == 0 ? 1 : Math.min(1.0, next / (double) order.length);
        }

        /** Places blocks for up to ~8 ms. True once everything is placed. */
        boolean tick() {
            if (done) return true;
            long t0 = System.nanoTime();
            BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
            while (next < order.length) {
                m.set(order[next]);
                level.setBlock(m, states[next], FLAGS);
                next++;
                if ((next & 63) == 0 && System.nanoTime() - t0 > 8_000_000L) return false;
            }
            done = true;
            release();
            return true;
        }

        void release() {
            for (long ck : chunks) level.setChunkForced(ChunkPos.getX(ck), ChunkPos.getZ(ck), false);
            chunks.clear();
        }
    }
}
