package com.aidan.skyserver.expedition;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

import java.util.*;

/**
 * 5.20: the Sunken Monument's map - prismarine halls built fresh for every trip in their own world
 * (skyserver:sunken, an empty void: nothing spawns there by itself). Everything is closed in, so the water
 * stays where it's put.
 *
 * Layout (from the centre of the HUB, floor = FLOOR_Y, the first water layer):
 *  - The HUB: a flooded 21 x 21 hall, 12 tall, with four pillars.
 *  - WREN'S DOCK (south): a dry room behind a door. The crew lands here; Wren and the sponge dryer stand here.
 *  - Three WINGS behind FLOODGATES (waterlogged iron bars): West (Guardian Barracks), East (Drowned
 *    Quarters) and North (the Upper Hall). Each is a flooded corridor with rooms off it behind doors.
 *    The crew opens one wing at a time, in the order they choose.
 *  - Every ROOM: 9 x 9, 6 tall, starts flooded. A pump (sticky piston) drains it a little per right-click;
 *    the Rising Tide floods it again. A chest opens when the room's guards are beaten.
 *  - RIPTIDE CURRENTS: a tube above each wing's corridor, from its far end back to the hub. Some dump you
 *    into the GUARDIAN PIT under the hub instead (random per current, per trip).
 *  - The VAULT GATE (bars over a shaft in the hub floor, north side) opens when all three wings are done.
 *    The shaft drops into the DEEP PASSAGE, where the DIVING BELL waits; push it to the VAULT at the end,
 *    where the Tidemother lives.
 * Wing coordinates: u = along the corridor away from the hub, v = across it.
 */
final class SunkenMap {
    private SunkenMap() {}

    static final int FLOOR_Y = 64;
    /** Rooms per wing (West, East, North) by difficulty: 3 / 4 / 5 / 6 rooms in all. */
    static final int[][] ROOMS = {{1, 1, 1}, {2, 1, 1}, {2, 2, 1}, {2, 2, 2}};
    static final String[] WING_NAMES = {"West - Guardian Barracks", "East - Drowned Quarters", "North - the Upper Hall"};
    static final String[] WING_SHORT = {"West", "East", "North"};
    static final int ROOM_HEIGHT = 6;
    /** The bell's path along the Deep Passage (north-wing u), and its height (centre, relative to the floor). */
    static final int BELL_START = 9, BELL_END = 44, BELL_Y = -9;

    private static final BlockState WATER = Blocks.WATER.defaultBlockState(), AIR = Blocks.AIR.defaultBlockState();

    static final class Room {
        final int wing, index;
        /** Interior box {x0, y0, z0, x1, y1, z1}, inclusive. */
        final int[] box;
        BlockPos chest, pump, door;
        final List<BlockPos> leakSpots = new ArrayList<>();
        // ---- run state ----
        boolean active = false, cleared = false;
        int wave = 0;
        long nextWave = 0, nextLeak = 0;
        double level = ROOM_HEIGHT;
        int placedLevel = ROOM_HEIGHT;
        final Set<UUID> guards = new HashSet<>();
        final Set<Long> leaks = new HashSet<>();
        final Set<UUID> sponged = new HashSet<>();
        final Set<UUID> looted = new HashSet<>();

        Room(int wing, int index, int[] box) {
            this.wing = wing;
            this.index = index;
            this.box = box;
        }

        boolean inside(double x, double y, double z, double pad) {
            return x >= box[0] - pad && x < box[3] + 1 + pad && y >= box[1] - pad && y < box[4] + 1 + pad && z >= box[2] - pad && z < box[5] + 1 + pad;
        }
    }

    static final class Current {
        final int wing;
        final int[] box;
        final double vx, vz;
        boolean trap;

        Current(int wing, int[] box, double vx, double vz) {
            this.wing = wing;
            this.box = box;
            this.vx = vx;
            this.vz = vz;
        }

        boolean inside(double x, double y, double z) {
            return x >= box[0] && x < box[3] + 1 && y >= box[1] && y < box[4] + 1 && z >= box[2] && z < box[5] + 1;
        }

        /** Near the hub end (where a trap current throws you into the pit). */
        boolean atExit(double x, double z, int cx, int cz) {
            return Math.abs(x - cx - 0.5) <= 13.5 && Math.abs(z - cz - 0.5) <= 13.5;
        }
    }

    static final class Layout {
        BlockPos center, dryer, bossSpot;
        double[] landing, wren, pitLanding;
        /** Dock interior {x0, y0, z0, x1, y1, z1}. */
        int[] dock, pit, passage, vault;
        final List<Room> rooms = new ArrayList<>();
        final List<List<BlockPos>> gates = new ArrayList<>();
        final List<BlockPos> vaultGate = new ArrayList<>();
        final List<Current> currents = new ArrayList<>();
        final Long2ObjectOpenHashMap<BlockState> blocks = new Long2ObjectOpenHashMap<>();
        AncientCityMap.Builder builder;

        boolean inDock(double x, double y, double z) {
            return in(dock, x, y, z, 0);
        }

        boolean inPassage(double x, double y, double z) {
            return in(passage, x, y, z, 0);
        }

        boolean inVault(double x, double y, double z) {
            return in(vault, x, y, z, 0);
        }

        boolean isMapBlock(BlockPos p) {
            BlockState s = blocks.get(p.asLong());
            return s != null && !s.isAir() && !s.is(Blocks.WATER);
        }

        List<Room> wingRooms(int wing) {
            List<Room> out = new ArrayList<>();
            for (Room r : rooms) if (r.wing == wing) out.add(r);
            return out;
        }

        /** World position of a point on the bell's path (u along the north wing). */
        BlockPos bellPos(int u) {
            return center.offset(0, BELL_Y, -u);
        }

        int[] box() {
            return new int[]{center.getX() - 30, center.getZ() - 75, center.getX() + 30, center.getZ() + 25};
        }
    }

    static boolean in(int[] b, double x, double y, double z, double pad) {
        return b != null && x >= b[0] - pad && x < b[3] + 1 + pad && y >= b[1] - pad && y < b[4] + 1 + pad && z >= b[2] - pad && z < b[5] + 1 + pad;
    }

    // =====================================================================
    // Planning
    // =====================================================================

    /** Wing frame -> world offset: West (x, z) = (-u, -v), East = (u, v), North = (v, -u). */
    private static int[] wf(int wing, int u, int v) {
        return switch (wing) {
            case 0 -> new int[]{-u, -v};
            case 1 -> new int[]{u, v};
            default -> new int[]{v, -u};
        };
    }

    private static final Rotation[] WING_ROT = {Rotation.CLOCKWISE_180, Rotation.NONE, Rotation.COUNTERCLOCKWISE_90};

    /** A wing-frame box -> a world box {x0, y0, z0, x1, y1, z1} (sorted). */
    private static int[] wbox(BlockPos c, int wing, int u0, int u1, int y0, int y1, int v0, int v1) {
        int[] a = wf(wing, u0, v0), b = wf(wing, u1, v1);
        return new int[]{c.getX() + Math.min(a[0], b[0]), c.getY() + y0, c.getZ() + Math.min(a[1], b[1]),
                c.getX() + Math.max(a[0], b[0]), c.getY() + y1, c.getZ() + Math.max(a[1], b[1])};
    }

    private static BlockPos wpos(BlockPos c, int wing, int u, int y, int v) {
        int[] a = wf(wing, u, v);
        return c.offset(a[0], y, a[1]);
    }

    private record Box(int[] b, BlockState fill) {}

    static Layout plan(ServerLevel level, BlockPos center, int d, Random rng) {
        Layout lay = new Layout();
        lay.center = center;
        Long2ObjectOpenHashMap<BlockState> c = lay.blocks;
        int cx = center.getX(), cy = center.getY(), cz = center.getZ();
        BlockState bricks = Blocks.PRISMARINE_BRICKS.defaultBlockState(), dark = Blocks.DARK_PRISMARINE.defaultBlockState(),
                prism = Blocks.PRISMARINE.defaultBlockState(), lantern = Blocks.SEA_LANTERN.defaultBlockState();
        List<Box> chambers = new ArrayList<>();

        // ---- The hub, the dock, the pit, the shaft, the passage, the vault ----
        chambers.add(new Box(new int[]{cx - 10, cy, cz - 10, cx + 10, cy + 11, cz + 10}, WATER));
        lay.dock = new int[]{cx - 4, cy, cz + 13, cx + 4, cy + 4, cz + 19};
        chambers.add(new Box(lay.dock, AIR));
        chambers.add(new Box(new int[]{cx, cy, cz + 11, cx, cy + 1, cz + 11}, WATER)); // the hub side of the dock door
        lay.pit = new int[]{cx - 6, cy - 8, cz + 3, cx, cy - 3, cz + 9};
        chambers.add(new Box(lay.pit, WATER));
        chambers.add(new Box(new int[]{cx - 3, cy - 2, cz + 6, cx - 2, cy - 1, cz + 7}, WATER)); // the pit's way out (up into the hub)
        chambers.add(new Box(new int[]{cx - 2, cy - 5, cz - 10, cx + 2, cy - 1, cz - 6}, WATER)); // the shaft under the vault gate
        lay.passage = wbox(center, 2, 6, 48, -12, -6, -3, 3);
        chambers.add(new Box(lay.passage, WATER));
        lay.vault = wbox(center, 2, 49, 69, -14, -2, -10, 10);
        chambers.add(new Box(lay.vault, WATER));

        // ---- The wings ----
        int[] perWing = ROOMS[d];
        int[] corridorEnd = new int[3];
        for (int w = 0; w < 3; w++) {
            int n = perWing[w];
            int end = 11 + 4 + 12 * n;
            corridorEnd[w] = end;
            chambers.add(new Box(wbox(center, w, 11, end, 0, 5, -2, 2), WATER));
            for (int i = 0; i < n; i++) {
                int u = 17 + 12 * i, s = i % 2 == 0 ? 1 : -1;
                int[] rb = wbox(center, w, u - 4, u + 4, 0, ROOM_HEIGHT - 1, s > 0 ? 4 : -12, s > 0 ? 12 : -4);
                chambers.add(new Box(rb, WATER));
                Room r = new Room(w, lay.rooms.size(), rb);
                r.chest = wpos(center, w, u - 3, 0, s * 11);
                r.pump = wpos(center, w, u + 3, 0, s * 11);
                r.door = wpos(center, w, u, 0, s * 3);
                // Leak spots: the top layer, against the three outer walls.
                for (int uu = u - 3; uu <= u + 3; uu += 2) r.leakSpots.add(wpos(center, w, uu, ROOM_HEIGHT - 1, s * 12));
                for (int vv = 5; vv <= 11; vv += 3) {
                    r.leakSpots.add(wpos(center, w, u - 4, ROOM_HEIGHT - 1, s * vv));
                    r.leakSpots.add(wpos(center, w, u + 4, ROOM_HEIGHT - 1, s * vv));
                }
                lay.rooms.add(r);
            }
            // The riptide current: from the corridor's far end (up through its ceiling) back to the hub.
            chambers.add(new Box(wbox(center, w, 11, end - 1, 7, 9, -1, 1), WATER));
            chambers.add(new Box(wbox(center, w, end - 2, end - 1, 6, 6, -1, 1), WATER));
            int[] tube = wbox(center, w, 11, end - 1, 7, 9, -1, 1);
            int[] dir = wf(w, -1, 0);
            lay.currents.add(new Current(w, tube, dir[0], dir[1]));
        }

        // ---- Shells first (every chamber's walls), then the insides carve the openings ----
        for (Box b : chambers) {
            int[] k = b.b;
            for (int x = k[0] - 1; x <= k[3] + 1; x++)
                for (int y = k[1] - 1; y <= k[4] + 1; y++)
                    for (int z = k[2] - 1; z <= k[5] + 1; z++) {
                        boolean edge = x < k[0] || x > k[3] || y < k[1] || y > k[4] || z < k[2] || z > k[5];
                        if (!edge) continue;
                        long key = BlockPos.asLong(x, y, z);
                        if (c.containsKey(key)) continue;
                        BlockState s = y < k[1] ? dark : y > k[4] ? bricks : (rng.nextInt(9) == 0 ? prism : bricks);
                        if ((y < k[1] || y > k[4]) && Math.floorMod(x, 5) == 0 && Math.floorMod(z, 5) == 0) s = lantern;
                        c.put(key, s);
                    }
        }
        for (Box b : chambers) {
            int[] k = b.b;
            for (int x = k[0]; x <= k[3]; x++)
                for (int y = k[1]; y <= k[4]; y++)
                    for (int z = k[2]; z <= k[5]; z++) c.put(BlockPos.asLong(x, y, z), b.fill);
        }

        // ---- Pillars (hub cover, vault cover against the Tidemother's beam) ----
        for (int px : new int[]{-7, 6}) for (int pz : new int[]{-7, 6}) pillar(c, cx + px, cy, cy + 11, cz + pz, bricks, lantern);
        int[] vb = lay.vault;
        for (int px : new int[]{-6, 5}) for (int dz : new int[]{6, 14}) pillar(c, cx + px, vb[1], vb[4], vb[2] + dz, bricks, lantern);

        // ---- Gates ----
        BlockState bars = Blocks.IRON_BARS.defaultBlockState().setValue(BlockStateProperties.WATERLOGGED, true);
        for (int w = 0; w < 3; w++) {
            List<BlockPos> g = new ArrayList<>();
            for (int v = -2; v <= 2; v++)
                for (int y = 0; y <= 5; y++) {
                    BlockPos p = wpos(center, w, 11, y, v);
                    c.put(p.asLong(), bars);
                    g.add(p);
                }
            lay.gates.add(g);
        }
        for (int x = -2; x <= 2; x++)
            for (int z = -10; z <= -6; z++) {
                BlockPos p = center.offset(x, -1, z);
                c.put(p.asLong(), bars);
                lay.vaultGate.add(p);
            }

        // ---- Doors, chests, pumps ----
        door(c, center.offset(0, 0, 12), Direction.NORTH);
        for (Room r : lay.rooms) {
            Direction toward = Rotation.NONE.rotate(Direction.SOUTH);
            // The door sits in the wall between the corridor and the room (wing frame: facing +v / -v).
            int s = r.index % 2 == 0 ? 1 : -1;
            Direction local = s > 0 ? Direction.SOUTH : Direction.NORTH;
            toward = WING_ROT[r.wing].rotate(local);
            door(c, r.door, toward);
            c.put(r.chest.asLong(), Blocks.CHEST.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, toward.getOpposite()));
            c.put(r.pump.asLong(), Blocks.STICKY_PISTON.defaultBlockState().setValue(BlockStateProperties.FACING, Direction.UP));
        }

        // ---- The dock: Wren, the sponge dryer, a little light ----
        lay.wren = new double[]{cx + 0.5, cy, cz + 19.5, 180};
        lay.landing = new double[]{cx + 0.5, cy, cz + 16.5, 180};
        lay.dryer = center.offset(-4, 0, 19);
        c.put(lay.dryer.asLong(), Blocks.FURNACE.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.EAST));
        for (int x = -4; x <= 4; x += 4) c.put(center.offset(x, 5, 16).asLong(), lantern);
        lay.pitLanding = new double[]{cx - 3 + 0.5, cy - 7, cz + 4.5, 0};
        lay.bossSpot = center.offset(0, -8, -59);
        lay.builder = new AncientCityMap.Builder(level, c);
        return lay;
    }

    private static void pillar(Long2ObjectOpenHashMap<BlockState> c, int x, int y0, int y1, int z, BlockState bricks, BlockState lantern) {
        for (int y = y0; y <= y1; y++)
            for (int ox = 0; ox <= 1; ox++)
                for (int oz = 0; oz <= 1; oz++) c.put(BlockPos.asLong(x + ox, y, z + oz), (y - y0) % 4 == 2 ? lantern : bricks);
    }

    private static void door(Long2ObjectOpenHashMap<BlockState> c, BlockPos lower, Direction facing) {
        BlockState d = Blocks.DARK_OAK_DOOR.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, facing);
        c.put(lower.asLong(), d.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.LOWER));
        c.put(lower.above().asLong(), d.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER));
    }

    // =====================================================================
    // Water levels (Rising Tide)
    // =====================================================================

    /** Fills a room's bottom `layers` layers with water and empties the rest (only water / air is touched). */
    static void applyLevel(ServerLevel l, Room r, int layers) {
        layers = Math.max(0, Math.min(ROOM_HEIGHT, layers));
        if (layers == r.placedLevel) return;
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int y = r.box[1]; y <= r.box[4]; y++) {
            boolean wet = y - r.box[1] < layers;
            for (int x = r.box[0]; x <= r.box[3]; x++)
                for (int z = r.box[2]; z <= r.box[5]; z++) {
                    m.set(x, y, z);
                    if (r.leaks.contains(m.asLong())) continue;
                    BlockState s = l.getBlockState(m);
                    if (wet && s.isAir()) l.setBlock(m, WATER, 2);
                    else if (!wet && s.is(Blocks.WATER)) l.setBlock(m, AIR, 2);
                }
        }
        r.placedLevel = layers;
    }
}
