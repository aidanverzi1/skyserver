package com.aidan.skyserver.expedition;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

import java.util.*;

/**
 * 5.20: the Sunken Monument's map - prismarine halls built fresh for every trip in their own world
 * (skyserver:sunken, an empty void: nothing spawns there by itself). Everything is closed in, so the water
 * stays where it's put. 5.25: EVERYTHING is flooded, always - no dry dock, no doors, no floodgates on the wings
 * (doors were air pockets), no pumps.
 *
 * Layout (from the centre of the HUB, floor = FLOOR_Y, the first water layer):
 *  - The HUB: a flooded 21 x 21 hall, 12 tall, with four pillars and two AIR VENTS.
 *  - WREN'S DOCK (south): where the crew lands and Wren waits (flooded too; nothing can hit you there).
 *  - Three WINGS, open from the start: West (Guardian Barracks), East (Drowned Quarters) and North (the Upper
 *    Hall). Each is a flooded corridor (one air vent) with rooms off it.
 *  - Every ROOM: 9 x 9, 6 tall. Nine TIDE SEALS (redstone lamps) in its walls, a TIDE CRYSTAL (amethyst) in the
 *    middle of the floor, 1-2 AIR VENTS (bubble coral blocks), the chest, and two spots where boon shells
 *    appear once it's cleared. One room per trip has the SUNKEN IDOL's pedestal (raw gold).
 *  - RIPTIDE CURRENTS: a tube above each wing's corridor, from its far end back to the hub. Some dump you
 *    into the GUARDIAN PIT under the hub instead (random per current, per trip).
 *  - The VAULT GATE (bars over a shaft in the hub floor, north side) opens when all three wings are done.
 *    The shaft drops into the DEEP PASSAGE, where the DIVING BELL waits; push it to the VAULT at the end,
 *    where the Tidemother lives. Three guarded AIR PODS on the passage floor refill the bell's air.
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

    private static final BlockState WATER = Blocks.WATER.defaultBlockState();

    static final class Room {
        final int wing, index;
        /** Interior box {x0, y0, z0, x1, y1, z1}, inclusive. */
        final int[] box;
        BlockPos chest, crystal, idolSpot;
        /** 5.25: the nine Tide Seal lamps (in the walls), this room's air vents, the two boon shell spots. */
        final List<BlockPos> seals = new ArrayList<>();
        final List<BlockPos> vents = new ArrayList<>();
        final BlockPos[] shells = new BlockPos[2];
        // ---- run state ----
        boolean active = false, cleared = false;
        int wave = 0;
        long nextWave = 0;
        final Set<UUID> guards = new HashSet<>();
        // 5.25: Tide Seals - the order (indexes into seals), how far the crew got, the replay, the lamp lit by it.
        int[] order = new int[0];
        int progress = 0, replayStep = -1;
        boolean sealsDone = false;
        long replayNext = 0, nextCrystal = 0, unlightAt = 0;
        BlockPos lit;
        final Set<UUID> steadyUsed = new HashSet<>();
        // 5.25: boon shells (types offered, who took one).
        final int[] boons = {-1, -1};
        boolean shellsUp = false;
        final Set<UUID> boonTaken = new HashSet<>();

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
        BlockPos center, bossSpot;
        double[] landing, wren, pitLanding;
        /** Dock interior {x0, y0, z0, x1, y1, z1}. */
        int[] dock, pit, passage, vault;
        final List<Room> rooms = new ArrayList<>();
        final List<BlockPos> vaultGate = new ArrayList<>();
        /** 5.25: air vents outside the rooms (hub, corridors), and the passage's air pods {u along the passage} -> block. */
        final List<BlockPos> vents = new ArrayList<>();
        final List<BlockPos> pods = new ArrayList<>();
        /** Two vents in the vault, behind the pillars: they only work once the Tidemother is up. */
        final List<BlockPos> vaultVents = new ArrayList<>();
        final List<Integer> podU = new ArrayList<>();
        /** The room with the Sunken Idol's pedestal (-1 = none). */
        int idolRoom = -1;
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

    static BlockState block(String id) {
        return BuiltInRegistries.BLOCK.getValue(Identifier.withDefaultNamespace(id)).defaultBlockState();
    }

    /** 5.25 blocks: seals (off), vents, the crystal, the idol's pedestal. */
    static final String SEAL_OFF = "redstone_lamp", SEAL_ON = "sea_lantern", VENT = "bubble_coral_block", CRYSTAL = "amethyst_block", IDOL = "raw_gold_block";
    /** The air pods along the Deep Passage (u along the north wing). */
    static final int[] POD_U = {18, 28, 38};

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
        chambers.add(new Box(lay.dock, WATER)); // 5.25: flooded like everything else
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
        int roomsTotal = perWing[0] + perWing[1] + perWing[2];
        lay.idolRoom = rng.nextInt(roomsTotal);
        BlockState sealOff = block(SEAL_OFF), vent = block(VENT), crystal = block(CRYSTAL), idol = block(IDOL);
        List<Object[]> extras = new ArrayList<>(); // {pos, state} put in after the chambers are carved
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
                // 5.25: the doorway is just an opening now (2 tall, water).
                extras.add(new Object[]{wpos(center, w, u, 0, s * 3), WATER});
                extras.add(new Object[]{wpos(center, w, u, 1, s * 3), WATER});
                // Tide Seals: three in the far wall, two in each side wall, two in the corridor wall (mid height).
                int[][] sealUV = {{u - 3, s * 13}, {u, s * 13}, {u + 3, s * 13}, {u - 5, s * 6}, {u - 5, s * 10}, {u + 5, s * 6}, {u + 5, s * 10}, {u - 3, s * 3}, {u + 3, s * 3}};
                for (int[] sv : sealUV) {
                    BlockPos sp = wpos(center, w, sv[0], 2, sv[1]);
                    r.seals.add(sp);
                    extras.add(new Object[]{sp, sealOff});
                }
                r.crystal = wpos(center, w, u, 0, s * 8);
                extras.add(new Object[]{r.crystal, crystal});
                BlockPos v1 = wpos(center, w, u + 3, 0, s * 11);
                r.vents.add(v1);
                extras.add(new Object[]{v1, vent});
                if (d <= 1) {
                    BlockPos v2 = wpos(center, w, u - 3, 0, s * 5);
                    r.vents.add(v2);
                    extras.add(new Object[]{v2, vent});
                }
                r.shells[0] = wpos(center, w, u - 2, 0, s * 8);
                r.shells[1] = wpos(center, w, u + 2, 0, s * 8);
                if (lay.rooms.size() == lay.idolRoom) {
                    r.idolSpot = wpos(center, w, u + 3, 0, s * 5);
                    extras.add(new Object[]{r.idolSpot, idol});
                }
                lay.rooms.add(r);
            }
            // The riptide current: from the corridor's far end (up through its ceiling) back to the hub.
            chambers.add(new Box(wbox(center, w, 11, end - 1, 7, 9, -1, 1), WATER));
            chambers.add(new Box(wbox(center, w, end - 2, end - 1, 6, 6, -1, 1), WATER));
            int[] tube = wbox(center, w, 11, end - 1, 7, 9, -1, 1);
            int[] dir = wf(w, -1, 0);
            lay.currents.add(new Current(w, tube, dir[0], dir[1]));
            // 5.25: one air vent near the start of the corridor.
            BlockPos cv = wpos(center, w, 13, 0, -2);
            lay.vents.add(cv);
            extras.add(new Object[]{cv, vent});
        }
        // 5.25: two vents in the hub, and the air pods along the Deep Passage floor.
        for (int vx : new int[]{-9, 9}) {
            BlockPos hv = center.offset(vx, 0, 4);
            lay.vents.add(hv);
            extras.add(new Object[]{hv, vent});
        }
        for (int vx : new int[]{-9, 9}) {
            BlockPos vv = new BlockPos(cx + vx, lay.vault[1], lay.vault[2] + 10);
            lay.vaultVents.add(vv);
            extras.add(new Object[]{vv, vent});
        }
        for (int pu : POD_U) {
            BlockPos pp = wpos(center, 2, pu, -12, 3);
            lay.pods.add(pp);
            lay.podU.add(pu);
            extras.add(new Object[]{pp, vent});
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

        // ---- The vault gate (5.25: the wings have no floodgates any more - their openings are just water) ----
        BlockState bars = Blocks.IRON_BARS.defaultBlockState().setValue(BlockStateProperties.WATERLOGGED, true);
        for (int w = 0; w < 3; w++)
            for (int v = -2; v <= 2; v++)
                for (int y = 0; y <= 5; y++) c.put(wpos(center, w, 11, y, v).asLong(), WATER);
        for (int x = -2; x <= 2; x++)
            for (int z = -10; z <= -6; z++) {
                BlockPos p = center.offset(x, -1, z);
                c.put(p.asLong(), bars);
                lay.vaultGate.add(p);
            }

        // ---- Openings, chests, seals, vents (5.25: no doors - they were air pockets) ----
        c.put(center.offset(0, 0, 12).asLong(), WATER);
        c.put(center.offset(0, 1, 12).asLong(), WATER);
        for (Room r : lay.rooms) {
            // The chest faces back toward the corridor (wing frame: facing -v / +v).
            int s = r.index % 2 == 0 ? 1 : -1;
            Direction local = s > 0 ? Direction.SOUTH : Direction.NORTH;
            Direction toward = WING_ROT[r.wing].rotate(local);
            c.put(r.chest.asLong(), Blocks.CHEST.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, toward.getOpposite())
                    .setValue(BlockStateProperties.WATERLOGGED, true));
        }
        for (Object[] e : extras) c.put(((BlockPos) e[0]).asLong(), (BlockState) e[1]);

        // ---- The dock: Wren and a little light ----
        lay.wren = new double[]{cx + 0.5, cy, cz + 19.5, 180};
        lay.landing = new double[]{cx + 0.5, cy, cz + 16.5, 180};
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

}
