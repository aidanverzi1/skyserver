package com.aidan.skyserver.expedition;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.phys.Vec3;

import java.util.*;

/**
 * 5.10: the Nether Expedition is a custom arena map, built fresh for every trip high above a lava sea
 * in its own flat Nether world (resources/data/skyserver/dimension/expedition.json).
 *
 * Layout: a 3 x 3 grid of big platforms joined by wide railed bridges.
 *  - Middle: Wren's Landing, a roofed pavilion. Safe, unbreakable, no mobs. Captain Wren waits there.
 *  - The 8 platforms around it: the chest arenas (3/4/5/6 by difficulty, ~33 x 33 inside, 13-16
 *    tall, every one with 2+ entrances, balconies, ramps, pillars and cover). Spare platforms are
 *    open plazas. The corners and sides link up into an outer ring path.
 *  - The Boss Arena hangs off one side behind an iron gate that only opens at the end.
 *  - Fortress or Bastion theme. Every trip randomizes which platform is which, each arena's design
 *    and rotation, chest spots, and 1-2 secret alcoves (look for cracked bricks at floor level).
 *  - Around it all: netherrack and basalt spires rising out of the lava, lavafalls, glowstone, and
 *    big rock masses overhead for the cavern-ceiling feel.
 * Every edge is railed - but rails and floors can be broken, and below is lava.
 *
 * Nothing is placed at once: the plan is made instantly, then a Builder places it over ~10 seconds
 * (while Captain Wren "flies") a chunk at a time, then fixes fence/wall/stair shapes.
 */
final class ExpeditionRooms {
    private ExpeditionRooms() {}

    static final int SPAWN = 0, CHEST = 1, ARENA = 2;
    /** The first air layer of every floor. The lava sea's top is SEA_Y. */
    static final int FLOOR_Y = 64, SEA_Y = 39;
    /** Grid spacing, arena / hub / boss sizes (outer, walls included), bridge width. */
    static final int PITCH = 52, ROOM = 35, HUB = 17, BOSS = 45, BRIDGE_W = 7;
    static final int ROOM_HALF = ROOM / 2, HUB_HALF = HUB / 2, BOSS_HALF = BOSS / 2;
    /** Boss arena middle, from the map's middle. */
    static final int BOSS_DIST = PITCH + ROOM_HALF + 1 + (PITCH - ROOM) + BOSS_HALF;
    /** Half the size of the play area (the border). */
    static final int HALF = BOSS_DIST + BOSS_HALF + 10;
    private static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;
    private static final DyeColor[] COLORS = {DyeColor.RED, DyeColor.ORANGE, DyeColor.YELLOW, DyeColor.LIGHT_BLUE,
            DyeColor.MAGENTA, DyeColor.PURPLE, DyeColor.CYAN, DyeColor.PINK};
    private static final String[] COLOR_NAMES = {"§cRed", "§6Orange", "§eYellow", "§bLight Blue", "§dMagenta", "§5Purple", "§3Cyan", "§dPink"};

    /** One room. Interior = the air inside (inclusive coordinates). */
    static final class Room {
        final int kind, index;
        final int x0, y0, z0, x1, y1, z1;
        int colorIndex = -1;
        BlockPos chest;
        final List<BlockPos> markers = new ArrayList<>();
        /** A spot just outside each doorway (for the direction arrow). */
        final List<BlockPos> doors = new ArrayList<>();
        /** A spot just inside each doorway (later waves come in through these). */
        final List<BlockPos> doorsIn = new ArrayList<>();
        final Set<UUID> guards = new HashSet<>();
        boolean woke = false, cleared = false;
        /** Waves: which one is on (1-based), how many, how many mobs in each, and whether the next is on its way. */
        int wave = 0, waves = 0;
        int[] waveSizes = new int[0];
        boolean waveComing = false;

        Room(int kind, int index, int x0, int y0, int z0, int x1, int y1, int z1) {
            this.kind = kind;
            this.index = index;
            this.x0 = x0;
            this.y0 = y0;
            this.z0 = z0;
            this.x1 = x1;
            this.y1 = y1;
            this.z1 = z1;
        }

        boolean inside(double x, double y, double z, double pad) {
            return x >= x0 - pad && x < x1 + 1 + pad && y >= y0 - pad && y < y1 + 1 + pad && z >= z0 - pad && z < z1 + 1 + pad;
        }

        /** In an active fight: woken, not cleared. */
        boolean active() {
            return woke && !cleared;
        }

        Vec3 center() {
            return new Vec3((x0 + x1 + 1) / 2.0, y0, (z0 + z1 + 1) / 2.0);
        }

        BlockPos floorCenter() {
            return new BlockPos((x0 + x1) / 2, y0, (z0 + z1) / 2);
        }

        /** Where the direction arrow points: the nearest doorway (or the middle). */
        Vec3 target(double fromX, double fromZ) {
            Vec3 best = center();
            double bd = Double.MAX_VALUE;
            for (BlockPos d : doors) {
                double dd = Math.hypot(d.getX() + 0.5 - fromX, d.getZ() + 0.5 - fromZ);
                if (dd < bd) {
                    bd = dd;
                    best = Vec3.atBottomCenterOf(d);
                }
            }
            return best;
        }

        String name() {
            if (kind == ARENA) return "§c§lBoss Arena";
            if (kind == SPAWN) return "§6Wren's Landing";
            return COLOR_NAMES[Math.max(0, colorIndex)] + " Arena";
        }

        int[] outer() {
            return new int[]{x0 - 1, y0 - 1, z0 - 1, x1 + 1, y1 + 1, z1 + 1};
        }
    }

    /** A hidden nook behind cracked bricks, with a small chest. */
    static final class Secret {
        BlockPos chest;
        int x0, y0, z0, x1, y1, z1;
        boolean found = false;

        boolean inside(double x, double y, double z) {
            return x >= x0 && x < x1 + 1 && y >= y0 && y < y1 + 1 && z >= z0 && z < z1 + 1;
        }
    }

    /** 5.19: one of the 5 gold blocks set into a bridge floor, and what breaking it does. */
    static final class GoldTrap {
        static final int JACKPOT = 0, LAVA = 1, AMBUSH = 2, TNT = 3;
        final BlockPos pos;
        final int kind;
        /** A spot on the bridge a few blocks away, where the ambushing brute steps out. */
        final BlockPos ambushFrom;
        boolean sprung = false;

        GoldTrap(BlockPos pos, int kind, BlockPos ambushFrom) {
            this.pos = pos;
            this.kind = kind;
            this.ambushFrom = ambushFrom;
        }
    }

    /** Everything planned for one trip. */
    static final class Layout {
        Room spawn, arena;
        final List<Room> rooms = new ArrayList<>();
        /** Blocks nobody may break: Wren's Landing, the Boss Arena's shell, the gate. */
        final Set<Long> locked = new HashSet<>();
        /** The iron gate in front of the Boss Arena. */
        final List<BlockPos> gate = new ArrayList<>();
        /** Floor spots for the Gilded Tyrant's three gold pillars. */
        final List<BlockPos> pillarSpots = new ArrayList<>();
        final List<Secret> secrets = new ArrayList<>();
        /** 5.19: the 5 trapped gold blocks on the bridges (ExpeditionGold). */
        final List<GoldTrap> goldTraps = new ArrayList<>();
        BlockPos center, landing;
        double[] wren;
        Builder builder;
        boolean fortress;
    }

    // =====================================================================
    // Materials
    // =====================================================================

    private static final class Theme {
        final BlockState wall, band, cracked, floor, floorB, trim, rail, cover, pillar, accent, stairs, slab, roof;
        final BlockState light = Blocks.SHROOMLIGHT.defaultBlockState(), glow = Blocks.GLOWSTONE.defaultBlockState();
        final BlockState lantern = Blocks.LANTERN.defaultBlockState();
        final boolean fortress;

        Theme(boolean fortress) {
            this.fortress = fortress;
            if (fortress) {
                wall = Blocks.NETHER_BRICKS.defaultBlockState();
                band = Blocks.RED_NETHER_BRICKS.defaultBlockState();
                cracked = Blocks.CRACKED_NETHER_BRICKS.defaultBlockState();
                floor = Blocks.NETHER_BRICKS.defaultBlockState();
                floorB = Blocks.RED_NETHER_BRICKS.defaultBlockState();
                trim = Blocks.CHISELED_NETHER_BRICKS.defaultBlockState();
                rail = Blocks.NETHER_BRICK_FENCE.defaultBlockState();
                cover = Blocks.NETHER_BRICK_WALL.defaultBlockState();
                pillar = Blocks.NETHER_BRICKS.defaultBlockState();
                accent = Blocks.MAGMA_BLOCK.defaultBlockState();
                stairs = Blocks.NETHER_BRICK_STAIRS.defaultBlockState();
                slab = Blocks.NETHER_BRICK_SLAB.defaultBlockState();
                roof = Blocks.RED_NETHER_BRICKS.defaultBlockState();
            } else {
                wall = Blocks.POLISHED_BLACKSTONE_BRICKS.defaultBlockState();
                band = Blocks.BLACKSTONE.defaultBlockState();
                cracked = Blocks.CRACKED_POLISHED_BLACKSTONE_BRICKS.defaultBlockState();
                floor = Blocks.POLISHED_BLACKSTONE.defaultBlockState();
                floorB = Blocks.POLISHED_BLACKSTONE_BRICKS.defaultBlockState();
                trim = Blocks.CHISELED_POLISHED_BLACKSTONE.defaultBlockState();
                rail = Blocks.POLISHED_BLACKSTONE_BRICK_WALL.defaultBlockState();
                cover = Blocks.POLISHED_BLACKSTONE_WALL.defaultBlockState();
                pillar = Blocks.POLISHED_BASALT.defaultBlockState();
                accent = Blocks.GILDED_BLACKSTONE.defaultBlockState(); // 5.19: was gold blocks (50+ per map)
                stairs = Blocks.POLISHED_BLACKSTONE_BRICK_STAIRS.defaultBlockState();
                slab = Blocks.POLISHED_BLACKSTONE_BRICK_SLAB.defaultBlockState();
                roof = Blocks.BLACKSTONE.defaultBlockState();
            }
        }

        BlockState stairs(Direction facing, boolean top) {
            return stairs.setValue(StairBlock.FACING, facing).setValue(StairBlock.HALF, top ? Half.TOP : Half.BOTTOM);
        }

        BlockState topSlab() {
            return slab.setValue(SlabBlock.TYPE, SlabType.TOP);
        }

        /** Walls with a little wear (never at floor level, where cracks mean a secret). */
        BlockState wallAt(Random rng, int ly) {
            if (ly >= 2 && rng.nextInt(100) < 7) return cracked;
            if (!fortress && rng.nextInt(100) < 3) return Blocks.GILDED_BLACKSTONE.defaultBlockState();
            return wall;
        }

        BlockState floorAt(Random rng, boolean border) {
            if (border) return floorB;
            int r = rng.nextInt(100);
            if (fortress) return r < 8 ? cracked : floor;
            return r < 10 ? Blocks.BLACKSTONE.defaultBlockState() : r < 12 ? Blocks.GILDED_BLACKSTONE.defaultBlockState() : floor;
        }
    }

    private static final BlockState AIR = Blocks.AIR.defaultBlockState();

    // =====================================================================
    // The canvas (later writes win) and painting in a cell's own turned coordinates
    // =====================================================================

    private static final class Canvas {
        final Long2ObjectOpenHashMap<BlockState> map = new Long2ObjectOpenHashMap<>();

        void set(int x, int y, int z, BlockState s) {
            map.put(BlockPos.asLong(x, y, z), s);
        }

        BlockState get(int x, int y, int z) {
            return map.get(BlockPos.asLong(x, y, z));
        }

        void fill(int x0, int y0, int z0, int x1, int y1, int z1, BlockState s) {
            for (int x = Math.min(x0, x1); x <= Math.max(x0, x1); x++)
                for (int y = Math.min(y0, y1); y <= Math.max(y0, y1); y++)
                    for (int z = Math.min(z0, z1); z <= Math.max(z0, z1); z++) set(x, y, z, s);
        }
    }

    /** Paints in local coordinates (0..size-1, y 0 = the floor's first air layer), turned by rot quarter turns. */
    private static final class Cell {
        final Canvas c;
        final int ox, oz, size, rot;
        final Rotation rotation;
        final Theme t;
        final Random rng;
        Set<Long> lock;

        Cell(Canvas c, int cx, int cz, int size, int rot, Theme t, Random rng) {
            this.c = c;
            this.size = size;
            this.ox = cx - size / 2;
            this.oz = cz - size / 2;
            this.rot = rot & 3;
            this.rotation = switch (this.rot) {
                case 1 -> Rotation.CLOCKWISE_90;
                case 2 -> Rotation.CLOCKWISE_180;
                case 3 -> Rotation.COUNTERCLOCKWISE_90;
                default -> Rotation.NONE;
            };
            this.t = t;
            this.rng = rng;
        }

        int wx(int lx, int lz) {
            return ox + switch (rot) {
                case 1 -> size - 1 - lz;
                case 2 -> size - 1 - lx;
                case 3 -> lz;
                default -> lx;
            };
        }

        int wz(int lx, int lz) {
            return oz + switch (rot) {
                case 1 -> lx;
                case 2 -> size - 1 - lz;
                case 3 -> size - 1 - lx;
                default -> lz;
            };
        }

        BlockPos world(int lx, int ly, int lz) {
            return new BlockPos(wx(lx, lz), FLOOR_Y + ly, wz(lx, lz));
        }

        void set(int lx, int ly, int lz, BlockState s) {
            int x = wx(lx, lz), y = FLOOR_Y + ly, z = wz(lx, lz);
            c.set(x, y, z, s.rotate(rotation));
            if (lock != null && !s.isAir()) lock.add(BlockPos.asLong(x, y, z));
        }

        void fill(int lx0, int ly0, int lz0, int lx1, int ly1, int lz1, BlockState s) {
            for (int x = Math.min(lx0, lx1); x <= Math.max(lx0, lx1); x++)
                for (int y = Math.min(ly0, ly1); y <= Math.max(ly0, ly1); y++)
                    for (int z = Math.min(lz0, lz1); z <= Math.max(lz0, lz1); z++) set(x, y, z, s);
        }

        /** A local direction (as if rot were 0) turned to the world. */
        Direction dir(Direction local) {
            return rotation.rotate(local);
        }
    }

    // =====================================================================
    // Planning a trip
    // =====================================================================

    /** Grid steps: N, E, S, W (index = side). */
    private static final int[][] SIDES = {{0, -1}, {1, 0}, {0, 1}, {-1, 0}};
    private static final Direction[] SIDE_DIR = {Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST};

    /**
     * Plans the whole map around `center` (floor level) and returns it with a Builder ready to place it.
     */
    static Layout plan(ServerLevel level, BlockPos center, boolean fortress, int roomCount, Random rng) {
        Layout lay = new Layout();
        lay.center = center;
        lay.fortress = fortress;
        Theme t = new Theme(fortress);
        Canvas c = new Canvas();
        int cx = center.getX(), cz = center.getZ();

        // ---- Which platform is what ----
        int bossSide = rng.nextInt(4);
        List<int[]> cells = new ArrayList<>(); // {i, j}
        for (int i = -1; i <= 1; i++) for (int j = -1; j <= 1; j++) if (i != 0 || j != 0) cells.add(new int[]{i, j});
        Collections.shuffle(cells, rng);
        Set<Long> roomCells = new HashSet<>();
        for (int k = 0; k < Math.min(roomCount, cells.size()); k++) roomCells.add(key(cells.get(k)[0], cells.get(k)[1]));

        // ---- Wren's Landing ----
        hub(c, t, cx, cz, lay, rng);

        // ---- The 8 platforms ----
        int colorIndex = 0;
        for (int i = -1; i <= 1; i++)
            for (int j = -1; j <= 1; j++) {
                if (i == 0 && j == 0) continue;
                boolean[] doors = new boolean[4];
                for (int s = 0; s < 4; s++) {
                    int ni = i + SIDES[s][0], nj = j + SIDES[s][1];
                    doors[s] = Math.abs(ni) <= 1 && Math.abs(nj) <= 1; // a neighbour in the grid (the hub included)
                    if (i == SIDES[bossSide][0] && j == SIDES[bossSide][1] && s == bossSide) doors[s] = true; // the way to the boss
                }
                int px = cx + i * PITCH, pz = cz + j * PITCH;
                if (roomCells.contains(key(i, j))) {
                    Room r = chestRoom(c, t, px, pz, doors, lay.rooms.size(), rng);
                    r.colorIndex = colorIndex++ % COLORS.length;
                    paintMarkers(c, r);
                    lay.rooms.add(r);
                } else {
                    plaza(c, t, px, pz, doors, rng);
                }
            }

        // ---- Bridges: hub spokes, and the outer ring ----
        List<int[]> bridges = new ArrayList<>();
        for (int s = 0; s < 4; s++) bridges.add(bridge(c, t, cx, cz, HUB_HALF, cx + SIDES[s][0] * PITCH, cz + SIDES[s][1] * PITCH, ROOM_HALF, rng));
        int[][] ring = {{-1, -1}, {0, -1}, {1, -1}, {1, 0}, {1, 1}, {0, 1}, {-1, 1}, {-1, 0}};
        for (int k = 0; k < ring.length; k++) {
            int[] a = ring[k], b = ring[(k + 1) % ring.length];
            bridges.add(bridge(c, t, cx + a[0] * PITCH, cz + a[1] * PITCH, ROOM_HALF, cx + b[0] * PITCH, cz + b[1] * PITCH, ROOM_HALF, rng));
        }

        // ---- The Boss Arena, behind its gate ----
        int ex = cx + SIDES[bossSide][0] * PITCH, ez = cz + SIDES[bossSide][1] * PITCH;
        int bx = cx + SIDES[bossSide][0] * BOSS_DIST, bz = cz + SIDES[bossSide][1] * BOSS_DIST;
        lay.arena = bossArena(c, t, bx, bz, (bossSide + 2) % 4, lay, rng);
        bridge(c, t, ex, ez, ROOM_HALF, bx, bz, BOSS_HALF, rng);

        // ---- 5.19: five trapped gold blocks, each on a different bridge (outside the arenas) ----
        goldTraps(c, t, lay, bridges, rng);

        // ---- 1-2 secret alcoves on the outer walls of chest arenas ----
        List<Room> shuffled = new ArrayList<>(lay.rooms);
        Collections.shuffle(shuffled, rng);
        int secrets = 1 + rng.nextInt(2);
        for (Room r : shuffled) {
            if (lay.secrets.size() >= secrets) break;
            int ci = Integer.signum((r.x0 + r.x1) / 2 - cx), cj = Integer.signum((r.z0 + r.z1) / 2 - cz);
            List<Integer> outward = new ArrayList<>();
            for (int s = 0; s < 4; s++) {
                int ni = ci + SIDES[s][0], nj = cj + SIDES[s][1];
                boolean grid = Math.abs(ni) <= 1 && Math.abs(nj) <= 1;
                boolean boss = ci == SIDES[bossSide][0] && cj == SIDES[bossSide][1] && s == bossSide;
                if (!grid && !boss) outward.add(s);
            }
            if (outward.isEmpty()) continue;
            lay.secrets.add(secret(c, t, r, outward.get(rng.nextInt(outward.size())), rng));
        }

        // ---- Scenery: spires, lavafalls, the rocky ceiling ----
        scenery(c, cx, cz, bossSide, rng);

        lay.builder = new Builder(level, c);
        return lay;
    }

    private static long key(int i, int j) {
        return ((long) i << 32) ^ (j & 0xffffffffL);
    }

    // =====================================================================
    // Wren's Landing
    // =====================================================================

    private static void hub(Canvas c, Theme t, int cx, int cz, Layout lay, Random rng) {
        Cell d = new Cell(c, cx, cz, HUB, 0, t, rng);
        d.lock = lay.locked;
        int S = HUB, H = 6;
        BlockState pb = Blocks.POLISHED_BLACKSTONE_BRICKS.defaultBlockState(), pol = Blocks.POLISHED_BLACKSTONE.defaultBlockState();
        // Floor (two thick) with a gold-ringed compass in the middle.
        for (int x = 0; x < S; x++)
            for (int z = 0; z < S; z++) {
                boolean edge = x == 0 || z == 0 || x == S - 1 || z == S - 1;
                d.set(x, -1, z, edge ? pb : (x + z) % 2 == 0 ? pol : Blocks.POLISHED_BLACKSTONE_BRICKS.defaultBlockState());
                d.set(x, -2, z, pb);
            }
        d.fill(6, -1, 6, 10, -1, 10, Blocks.GILDED_BLACKSTONE.defaultBlockState());
        d.fill(7, -1, 7, 9, -1, 9, pol);
        d.set(8, -1, 8, Blocks.GLOWSTONE.defaultBlockState());
        // Walls with a 5-wide doorway in each side, corner towers, roof with glowstone.
        for (int x = 0; x < S; x++)
            for (int z = 0; z < S; z++) {
                boolean edge = x == 0 || z == 0 || x == S - 1 || z == S - 1;
                if (!edge) continue;
                int along = (x == 0 || x == S - 1) ? z : x;
                boolean door = Math.abs(along - 8) <= 2;
                boolean corner = (x == 0 || x == S - 1) && (z == 0 || z == S - 1);
                for (int y = 0; y < H; y++) {
                    if (door && y <= 3) continue;
                    BlockState s = corner ? Blocks.POLISHED_BASALT.defaultBlockState() : y == 2 && !door && along % 4 == 0 ? t.light : pb;
                    if (door && y == 4) s = Blocks.CHISELED_POLISHED_BLACKSTONE.defaultBlockState();
                    d.set(x, y, z, s);
                }
                if (corner) for (int y = H; y < H + 3; y++) d.set(x, y, z, Blocks.POLISHED_BASALT.defaultBlockState());
            }
        for (int x = 0; x < S; x++)
            for (int z = 0; z < S; z++) {
                boolean light = x % 4 == 0 && z % 4 == 0 && x > 0 && z > 0 && x < S - 1 && z < S - 1;
                d.set(x, H, z, light ? Blocks.GLOWSTONE.defaultBlockState() : x == 0 || z == 0 || x == S - 1 || z == S - 1 ? pb : Blocks.BLACKSTONE.defaultBlockState());
            }
        // Battlements.
        for (int x = 0; x < S; x += 2) {
            d.set(x, H + 1, 0, pb);
            d.set(x, H + 1, S - 1, pb);
            d.set(0, H + 1, x, pb);
            d.set(S - 1, H + 1, x, pb);
        }
        // A few crates and a map table by Wren.
        d.set(3, 0, 3, Blocks.BARREL.defaultBlockState());
        d.set(13, 0, 3, Blocks.CARTOGRAPHY_TABLE.defaultBlockState());
        d.set(3, 0, 13, Blocks.LODESTONE.defaultBlockState());
        d.set(13, 0, 13, Blocks.BARREL.defaultBlockState());
        // Support columns down into the lava.
        for (int[] p : new int[][]{{1, 1}, {S - 2, 1}, {1, S - 2}, {S - 2, S - 2}}) column(c, d.wx(p[0], p[1]), d.wz(p[0], p[1]), 1, pb, null);
        Room spawn = new Room(SPAWN, -1, d.ox + 1, FLOOR_Y, d.oz + 1, d.ox + S - 2, FLOOR_Y + H - 1, d.oz + S - 2);
        lay.spawn = spawn;
        lay.landing = new BlockPos(cx, FLOOR_Y, cz + 3);
        lay.wren = new double[]{cx + 0.5, FLOOR_Y, cz - 2 + 0.5, 0f}; // faces south, toward the landing spot
        for (int s = 0; s < 4; s++) {
            int dx = SIDES[s][0], dz = SIDES[s][1];
            spawn.doors.add(new BlockPos(cx + dx * (HUB_HALF + 1), FLOOR_Y, cz + dz * (HUB_HALF + 1)));
        }
    }

    // =====================================================================
    // Chest arenas
    // =====================================================================

    /** A support column from the lava up to just under a floor. half = how far it reaches each way. */
    private static void column(Canvas c, int x, int z, int half, BlockState s, BlockState trim) {
        for (int y = SEA_Y - 3; y <= FLOOR_Y - 3; y++)
            for (int dx = -half; dx <= half; dx++)
                for (int dz = -half; dz <= half; dz++) {
                    boolean corner = Math.abs(dx) == half && Math.abs(dz) == half && half > 0;
                    if (corner && y > FLOOR_Y - 8) continue; // a little flare at the top
                    c.set(x + dx, y, z + dz, trim != null && y % 6 == 0 ? trim : s);
                }
        // Corbel at the top.
        for (int dx = -half - 1; dx <= half + 1; dx++)
            for (int dz = -half - 1; dz <= half + 1; dz++) c.set(x + dx, FLOOR_Y - 3, z + dz, s);
    }

    /** The shell: floor, walls with doorways, buttresses and supports. Returns the room (interior). */
    private static Room chestRoom(Canvas c, Theme t, int cx, int cz, boolean[] doors, int index, Random rng) {
        int S = ROOM, H = 13 + rng.nextInt(4);
        Cell shell = new Cell(c, cx, cz, S, 0, t, rng);
        Room r = new Room(CHEST, index, shell.ox + 1, FLOOR_Y, shell.oz + 1, shell.ox + S - 2, FLOOR_Y + H - 1, shell.oz + S - 2);
        // Floor: border band, tiles, glowstone at tile middles.
        for (int x = 0; x < S; x++)
            for (int z = 0; z < S; z++) {
                boolean border = x <= 2 || z <= 2 || x >= S - 3 || z >= S - 3;
                BlockState f = t.floorAt(rng, border);
                if (!border && (x - 1) % 8 == 4 && (z - 1) % 8 == 4) f = t.glow;
                else if (!border && ((x - 1) / 8 + (z - 1) / 8) % 2 == 0 && (x % 8 == 1 || z % 8 == 1)) f = t.floorB;
                shell.set(x, -1, z, f);
                shell.set(x, -2, z, t.wall);
            }
        // Walls.
        for (int x = 0; x < S; x++)
            for (int z = 0; z < S; z++) {
                boolean edge = x == 0 || z == 0 || x == S - 1 || z == S - 1;
                if (!edge) continue;
                boolean corner = (x == 0 || x == S - 1) && (z == 0 || z == S - 1);
                int along = (x == 0 || x == S - 1) ? z : x;
                for (int y = 0; y < H; y++) {
                    BlockState s = t.wallAt(rng, y);
                    if (y == 3 || y == H - 3) s = t.band;
                    if (!corner && (y == 4 || y == 9) && along % 6 == 3 && Math.abs(along - 17) > 4) s = t.light;
                    if (corner) s = t.trim;
                    shell.set(x, y, z, s);
                }
                // Battlements, taller corner towers.
                if (corner) for (int y = H; y < H + 4; y++) shell.set(x, y, z, y == H + 3 ? t.glow : t.trim);
                else if (along % 2 == 0) shell.set(x, H, z, t.wall);
            }
        // Doorways (5 wide, 5 tall) with a trim frame; the colour stripe goes above (paintMarkers).
        for (int s = 0; s < 4; s++) {
            if (!doors[s]) continue;
            for (int a = -3; a <= 3; a++) {
                for (int y = 0; y <= 5; y++) {
                    int[] p = wallSpot(S, s, 17 + a);
                    if (Math.abs(a) <= 2 && y <= 4) shell.set(p[0], y, p[1], AIR);
                    else if (Math.abs(a) == 3 || y == 5) shell.set(p[0], y, p[1], t.trim);
                }
            }
            int[] out = wallSpot(S, s, 17);
            int odx = SIDES[s][0], odz = SIDES[s][1];
            r.doors.add(new BlockPos(shell.ox + out[0] + odx, FLOOR_Y, shell.oz + out[1] + odz));
            r.doorsIn.add(new BlockPos(shell.ox + out[0] - odx * 2, FLOOR_Y, shell.oz + out[1] - odz * 2));
        }
        // Buttresses on the outside and support columns below.
        for (int s = 0; s < 4; s++) {
            for (int along : new int[]{5, 11, 23, 29}) {
                int[] p = wallSpot(S, s, along);
                int bx = shell.ox + p[0] + SIDES[s][0], bz = shell.oz + p[1] + SIDES[s][1];
                for (int y = FLOOR_Y - 6; y < FLOOR_Y + H - 2; y++) c.set(bx, y, bz, y == FLOOR_Y + H - 3 ? t.trim : t.wall);
                c.set(bx + SIDES[s][0], FLOOR_Y - 6, bz + SIDES[s][1], t.wall);
            }
        }
        for (int[] p : new int[][]{{2, 2}, {S - 3, 2}, {2, S - 3}, {S - 3, S - 3}, {17, 2}, {17, S - 3}, {2, 17}, {S - 3, 17}})
            column(c, shell.ox + p[0], shell.oz + p[1], 1, t.wall, t.band);
        // ---- The inside: one of three designs, turned a random way ----
        Cell d = new Cell(c, cx, cz, S, rng.nextInt(4), t, rng);
        List<int[]> chestSpots = new ArrayList<>();
        int design = rng.nextInt(3);
        if (t.fortress) {
            switch (design) {
                case 0 -> pillarCourt(d, H, chestSpots);
                case 1 -> forgeBridge(d, H, chestSpots);
                default -> ruinedHall(d, H, chestSpots);
            }
        } else {
            switch (design) {
                case 0 -> stables(d, H, chestSpots);
                case 1 -> treasureCourt(d, H, chestSpots);
                default -> forgeBridge(d, H, chestSpots); // the bastion "bridge hall"
            }
        }
        int[] cs = chestSpots.get(rng.nextInt(chestSpots.size()));
        BlockPos chest = d.world(cs[0], cs[1], cs[2]);
        Direction face = facing(chest.getX(), chest.getZ(), cx, cz);
        c.set(chest.getX(), chest.getY(), chest.getZ(), Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, face));
        r.chest = chest;
        return r;
    }

    /** A wall block's local spot: side s (N/E/S/W), `along` = 0..S-1 along that wall. */
    private static int[] wallSpot(int S, int s, int along) {
        return switch (s) {
            case 0 -> new int[]{along, 0};
            case 1 -> new int[]{S - 1, along};
            case 2 -> new int[]{along, S - 1};
            default -> new int[]{0, along};
        };
    }

    private static void paintMarkers(Canvas c, Room r) {
        BlockState wool = Blocks.WOOL.pick(COLORS[r.colorIndex]).defaultBlockState();
        for (BlockPos out : r.doors) {
            int dx = Integer.signum(out.getX() - (r.x0 + r.x1) / 2), dz = Integer.signum(out.getZ() - (r.z0 + r.z1) / 2);
            boolean xWall = Math.abs(out.getX() - (r.x0 + r.x1) / 2) > Math.abs(out.getZ() - (r.z0 + r.z1) / 2);
            int wx = xWall ? (dx > 0 ? r.x1 + 1 : r.x0 - 1) : out.getX();
            int wz = xWall ? out.getZ() : (dz > 0 ? r.z1 + 1 : r.z0 - 1);
            for (int a = -1; a <= 1; a++) {
                int x = xWall ? wx : wx + a, z = xWall ? wz + a : wz;
                c.set(x, FLOOR_Y + 5, z, wool);
                r.markers.add(new BlockPos(x, FLOOR_Y + 5, z));
            }
        }
    }

    // ---- Pieces the designs are built from (local coordinates; doorway lanes are 14..20 near the walls) ----

    /** A w x w pillar, lit halfway up. */
    private static void pillar(Cell d, int lx, int lz, int w, int h) {
        for (int x = lx; x < lx + w; x++)
            for (int z = lz; z < lz + w; z++)
                for (int y = 0; y < h; y++) d.set(x, y, z, y == 0 || y == h - 1 ? d.t.trim : d.t.pillar);
        if (w >= 3) {
            int m = w / 2;
            int ly = Math.min(4, h - 2);
            d.set(lx + m, ly, lz - 0 , d.t.light);
            d.set(lx + m, ly, lz + w - 1, d.t.light);
            d.set(lx, ly, lz + m, d.t.light);
            d.set(lx + w - 1, ly, lz + m, d.t.light);
        }
    }

    /** A low cover wall: one solid row, a wall-block row on top. */
    private static void cover(Cell d, int lx, int lz, int len, boolean alongX) {
        for (int k = 0; k < len; k++) {
            int x = alongX ? lx + k : lx, z = alongX ? lz : lz + k;
            d.set(x, 0, z, k == 0 || k == len - 1 ? d.t.trim : d.t.wall);
            d.set(x, 1, z, d.t.cover);
        }
    }

    /**
     * A balcony along the local west wall (x 1..4, floor block at y 5, so you stand at y 6), railed,
     * held up by two pillars, with a staircase up along the north wall and lanterns underneath.
     */
    private static void westBalcony(Cell d, List<int[]> chestSpots) {
        Theme t = d.t;
        for (int z = 1; z <= 33; z++) {
            for (int x = 1; x <= 4; x++) d.set(x, 5, z, x == 4 ? t.trim : t.floorB);
            if (z >= 3) d.set(4, 6, z, t.rail);
        }
        for (int z : new int[]{8, 26}) for (int y = 0; y < 5; y++) d.set(4, y, z, y == 4 ? t.trim : t.pillar);
        for (int z : new int[]{12, 22}) d.set(2, 4, z, t.lantern.setValue(LanternBlock.HANGING, true));
        // Stairs up along the north wall: from x 9 (floor) to x 5 (y 4), landing on the balcony at x 4.
        for (int k = 0; k <= 4; k++) {
            int x = 9 - k;
            for (int z = 1; z <= 2; z++) {
                for (int y = 0; y < k; y++) d.set(x, y, z, t.wall);
                d.set(x, k, z, t.stairs(Direction.WEST, false));
            }
            for (int y = 0; y <= k; y++) d.set(x, y, 3, t.wall); // side wall of the stairs
            d.set(x, k + 1, 3, t.rail);
        }
        chestSpots.add(new int[]{2, 6, 30});
        chestSpots.add(new int[]{2, 6, 17});
    }

    private static void dais(Cell d, int cx, int cz, int r) {
        for (int x = cx - r; x <= cx + r; x++)
            for (int z = cz - r; z <= cz + r; z++) {
                boolean edge = Math.abs(x - cx) == r || Math.abs(z - cz) == r;
                d.set(x, 0, z, edge ? d.t.trim : d.t.floorB);
            }
        d.set(cx, 0, cz, d.t.glow);
    }

    private static void wartPlanter(Cell d, int lx, int lz, int len, boolean alongX) {
        for (int k = -1; k <= len; k++)
            for (int w = -1; w <= 1; w++) {
                int x = alongX ? lx + k : lx + w, z = alongX ? lz + w : lz + k;
                boolean rim = k == -1 || k == len || w != 0;
                d.set(x, 0, z, rim ? d.t.trim : Blocks.SOUL_SAND.defaultBlockState());
                if (!rim) d.set(x, 1, z, Blocks.NETHER_WART.defaultBlockState().setValue(NetherWartBlock.AGE, 3));
            }
    }

    /** Picks `n` of the candidate cover walls. */
    private static void someCover(Cell d, int[][] spots, int n) {
        List<int[]> list = new ArrayList<>(Arrays.asList(spots));
        Collections.shuffle(list, d.rng);
        for (int i = 0; i < Math.min(n, list.size()); i++) {
            int[] s = list.get(i);
            cover(d, s[0], s[1], s[2], s[3] == 1);
        }
    }

    // ---- Fortress designs ----

    /** Four big pillars, a raised dais in the middle, a balcony, cover walls and wart planters. */
    private static void pillarCourt(Cell d, int H, List<int[]> chestSpots) {
        for (int[] p : new int[][]{{8, 8}, {24, 8}, {8, 24}, {24, 24}}) pillar(d, p[0], p[1], 3, H - 1);
        dais(d, 17, 17, 3);
        westBalcony(d, chestSpots);
        someCover(d, new int[][]{{12, 11, 4, 1}, {19, 23, 4, 1}, {11, 19, 3, 0}, {23, 12, 3, 0}, {27, 17, 4, 0}, {14, 28, 3, 1},
                {14, 6, 3, 1}, {29, 5, 3, 0}, {29, 26, 3, 0}}, 6);
        wartPlanter(d, 27, 28, 4, true);
        wartPlanter(d, 28, 4, 3, false);
        chestSpots.add(new int[]{17, 1, 17});
        chestSpots.add(new int[]{31, 0, 31});
        chestSpots.add(new int[]{31, 0, 3});
    }

    /**
     * A raised walkway across the middle (over two doorways) on arches, with stairs up from both
     * sides, forge pits in two corners and cover. Also the bastion's "bridge hall".
     */
    private static void forgeBridge(Cell d, int H, List<int[]> chestSpots) {
        Theme t = d.t;
        // Walkway: z 14..20, floor block at y 5; edges are trim with rails on top.
        for (int x = 1; x <= 33; x++)
            for (int z = 14; z <= 20; z++) {
                boolean edge = z == 14 || z == 20;
                d.set(x, 5, z, edge ? t.trim : (x % 6 == 0 ? t.accent : t.floorB));
                if (edge) d.set(x, 6, z, t.rail);
                if (!edge && x % 8 == 4) d.set(x, 4, z, t.topSlab()); // arch underside
            }
        // Arches under it (1 wide posts at the edges, so the space below stays open).
        for (int x : new int[]{6, 12, 22, 28})
            for (int z : new int[]{14, 20}) for (int y = 0; y < 5; y++) d.set(x, y, z, y == 4 ? t.trim : t.pillar);
        for (int x : new int[]{9, 25}) d.set(x, 4, 17, t.lantern.setValue(LanternBlock.HANGING, true));
        // Stairs up: south side at x 11..12 rising north; north side at x 22..23 rising south.
        for (int k = 0; k <= 4; k++) {
            for (int x = 11; x <= 12; x++) {
                int z = 25 - k;
                for (int y = 0; y < k; y++) d.set(x, y, z, t.wall);
                d.set(x, k, z, t.stairs(Direction.NORTH, false));
            }
            for (int x = 22; x <= 23; x++) {
                int z = 9 + k;
                for (int y = 0; y < k; y++) d.set(x, y, z, t.wall);
                d.set(x, k, z, t.stairs(Direction.SOUTH, false));
            }
            for (int y = 0; y <= k; y++) {
                d.set(10, y, 25 - k, t.wall);
                d.set(24, y, 9 + k, t.wall);
            }
            d.set(10, k + 1, 25 - k, t.rail);
            d.set(24, k + 1, 9 + k, t.rail);
        }
        // Landings: open the walkway rails where the stairs arrive.
        for (int x = 11; x <= 12; x++) {
            d.set(x, 5, 20, t.floorB);
            d.set(x, 6, 20, AIR);
        }
        for (int x = 22; x <= 23; x++) {
            d.set(x, 5, 14, t.floorB);
            d.set(x, 6, 14, AIR);
        }
        // Forge pits (magma inside a railed rim) and cover.
        for (int[] p : new int[][]{{4, 4}, {27, 27}}) {
            for (int x = p[0]; x < p[0] + 4; x++)
                for (int z = p[1]; z < p[1] + 4; z++) {
                    boolean rim = x == p[0] || z == p[1] || x == p[0] + 3 || z == p[1] + 3;
                    d.set(x, -1, z, rim ? t.trim : Blocks.MAGMA_BLOCK.defaultBlockState());
                    if (rim) d.set(x, 0, z, t.rail);
                }
        }
        someCover(d, new int[][]{{4, 25, 4, 1}, {26, 7, 4, 1}, {16, 26, 3, 0}, {17, 5, 3, 0}, {6, 9, 3, 0}, {28, 22, 3, 0}, {3, 29, 3, 1}, {29, 3, 3, 0}}, 5);
        chestSpots.add(new int[]{17, 6, 17});
        chestSpots.add(new int[]{31, 0, 4});
        chestSpots.add(new int[]{3, 0, 30});
    }

    /** Broken pillars of all heights, rubble, a fallen column, a balcony. */
    private static void ruinedHall(Cell d, int H, List<int[]> chestSpots) {
        Theme t = d.t;
        int[][] spots = {{7, 7}, {25, 7}, {7, 25}, {25, 25}, {12, 12}, {21, 21}, {12, 21}, {21, 12}};
        List<int[]> list = new ArrayList<>(Arrays.asList(spots));
        Collections.shuffle(list, d.rng);
        for (int i = 0; i < 6; i++) {
            int[] p = list.get(i);
            int h = 2 + d.rng.nextInt(Math.max(2, H - 4));
            int w = i < 4 ? 3 : 2;
            for (int x = p[0]; x < p[0] + w; x++)
                for (int z = p[1]; z < p[1] + w; z++) {
                    int hh = h - d.rng.nextInt(2);
                    for (int y = 0; y < hh; y++) d.set(x, y, z, y == hh - 1 ? t.cracked : t.pillar);
                }
            // Rubble around the foot.
            for (int k = 0; k < 4; k++) {
                int rx = p[0] - 1 + d.rng.nextInt(w + 2), rz = p[1] - 1 + d.rng.nextInt(w + 2);
                if (rx < 1 || rz < 1 || rx > 33 || rz > 33) continue;
                d.set(rx, 0, rz, d.rng.nextBoolean() ? t.slab : t.cracked);
            }
        }
        // A fallen column lying on its side.
        for (int x = 23; x <= 29; x++) d.set(x, 0, 17 + (x % 3 == 0 ? 0 : 0), t.pillar.hasProperty(RotatedPillarBlock.AXIS)
                ? t.pillar.setValue(RotatedPillarBlock.AXIS, Direction.Axis.X) : t.pillar);
        westBalcony(d, chestSpots);
        dais(d, 17, 17, 2);
        someCover(d, new int[][]{{16, 6, 3, 1}, {16, 28, 3, 1}, {28, 10, 3, 0}, {10, 27, 3, 1}}, 3);
        chestSpots.add(new int[]{17, 1, 17});
        chestSpots.add(new int[]{31, 0, 31});
    }

    // ---- Bastion designs ----

    /** Hoglin stables: stalls along two walls under a balcony, hay, an open middle with a gold ring. */
    private static void stables(Cell d, int H, List<int[]> chestSpots) {
        Theme t = d.t;
        BlockState basalt = Blocks.BASALT.defaultBlockState(), hay = Blocks.HAY_BLOCK.defaultBlockState();
        for (int side = 0; side < 2; side++) {
            int wallX = side == 0 ? 1 : 33, dir = side == 0 ? 1 : -1;
            for (int z : new int[]{4, 9, 25, 30}) {
                for (int k = 0; k < 6; k++) {
                    int x = wallX + dir * k;
                    d.set(x, 0, z, k == 5 ? basalt : t.wall);
                    d.set(x, 1, z, k == 5 ? basalt : t.cover);
                }
                d.set(wallX + dir * 5, 2, z, t.accent);
            }
            for (int z : new int[]{6, 27}) d.set(wallX + dir, 0, z, hay);
            d.set(wallX + dir, 1, 6, hay);
        }
        // Gold ring in the floor.
        for (int a = 0; a < 360; a += 6) {
            int x = 17 + (int) Math.round(Math.cos(Math.toRadians(a)) * 6), z = 17 + (int) Math.round(Math.sin(Math.toRadians(a)) * 6);
            d.set(x, -1, z, Blocks.GILDED_BLACKSTONE.defaultBlockState());
        }
        d.set(17, -1, 17, t.glow);
        // A balcony on the north wall (turned version of the west one): built on the west and the room is rotated anyway.
        westBalcony(d, chestSpots);
        someCover(d, new int[][]{{12, 12, 3, 1}, {20, 22, 3, 1}, {22, 11, 3, 0}, {11, 21, 3, 0}}, 3);
        chestSpots.add(new int[]{31, 0, 6});
        chestSpots.add(new int[]{31, 0, 28});
        chestSpots.add(new int[]{17, 0, 17});
    }

    /** A stepped gold-cornered ziggurat in the middle, basalt pillars with lanterns, a balcony. */
    private static void treasureCourt(Cell d, int H, List<int[]> chestSpots) {
        Theme t = d.t;
        for (int tier = 0; tier < 3; tier++) {
            int r = 5 - tier * 2;
            for (int x = 17 - r; x <= 17 + r; x++)
                for (int z = 17 - r; z <= 17 + r; z++) {
                    boolean corner = Math.abs(x - 17) == r && Math.abs(z - 17) == r;
                    d.set(x, tier, z, corner ? Blocks.GILDED_BLACKSTONE.defaultBlockState() : tier == 2 ? t.trim : t.floorB);
                }
        }
        for (int[] p : new int[][]{{6, 6}, {27, 6}, {6, 27}, {27, 27}}) {
            for (int x = p[0]; x < p[0] + 2; x++)
                for (int z = p[1]; z < p[1] + 2; z++) {
                    for (int y = 0; y < H - 2; y++) d.set(x, y, z, Blocks.POLISHED_BASALT.defaultBlockState());
                    d.set(x, H - 2, z, Blocks.GILDED_BLACKSTONE.defaultBlockState());
                }
            d.set(p[0] - 1, 5, p[1], t.lantern);
            d.set(p[0] - 1, 4, p[1], t.cover);
        }
        westBalcony(d, chestSpots);
        someCover(d, new int[][]{{10, 26, 3, 1}, {22, 8, 3, 1}, {27, 15, 3, 0}, {9, 10, 3, 0}}, 3);
        chestSpots.add(new int[]{17, 3, 17});
        chestSpots.add(new int[]{31, 0, 31});
    }

    // =====================================================================
    // Plazas, bridges, the boss arena, secrets
    // =====================================================================

    /** A spare platform: open, railed, with lamp posts, a monument, a broken arch and a wart garden. */
    private static void plaza(Canvas c, Theme t, int cx, int cz, boolean[] doors, Random rng) {
        Cell d = new Cell(c, cx, cz, ROOM, 0, t, rng);
        int S = ROOM;
        for (int x = 1; x < S - 1; x++)
            for (int z = 1; z < S - 1; z++) {
                boolean border = x <= 2 || z <= 2 || x >= S - 3 || z >= S - 3;
                d.set(x, -1, z, t.floorAt(rng, border));
                d.set(x, -2, z, t.wall);
            }
        // Rails all round, with gaps where bridges arrive.
        for (int s = 0; s < 4; s++)
            for (int along = 1; along < S - 1; along++) {
                int[] p = wallSpot(S, s, along);
                int x = Math.max(1, Math.min(S - 2, p[0])), z = Math.max(1, Math.min(S - 2, p[1]));
                if (doors[s] && Math.abs(along - 17) <= 2) continue;
                d.set(x, -1, z, t.trim);
                d.set(x, 0, z, t.rail);
                if (along % 6 == 0) {
                    d.set(x, 0, z, t.wall);
                    d.set(x, 1, z, t.lantern);
                }
            }
        // Monument in the middle.
        Cell r = new Cell(c, cx, cz, ROOM, rng.nextInt(4), t, rng);
        dais(r, 17, 17, 3);
        for (int y = 1; y <= 6; y++) r.set(17, y, 17, y == 6 ? t.glow : t.fortress ? t.trim : Blocks.POLISHED_BASALT.defaultBlockState());
        if (!t.fortress) r.set(17, 7, 17, Blocks.GILDED_BLACKSTONE.defaultBlockState());
        // A broken arch.
        for (int y = 0; y < 7; y++) {
            r.set(8, y, 6, t.pillar);
            r.set(13, y, 6, y < 4 ? t.pillar : AIR);
        }
        for (int x = 8; x <= 11; x++) r.set(x, 7, 6, t.wall);
        r.set(12, 7, 6, t.cracked);
        wartPlanter(r, 24, 25, 5, true);
        cover(r, 6, 26, 4, true);
        cover(r, 27, 9, 4, false);
        for (int[] p : new int[][]{{3, 3}, {S - 4, 3}, {3, S - 4}, {S - 4, S - 4}, {17, 3}, {17, S - 4}, {3, 17}, {S - 4, 17}})
            column(c, d.wx(p[0], p[1]), d.wz(p[0], p[1]), 1, t.wall, t.band);
    }

    /** A railed bridge between two platforms on the same row or column (half = each platform's half size). */
    private static int[] bridge(Canvas c, Theme t, int ax, int az, int ha, int bx, int bz, int hb, Random rng) {
        boolean alongX = az == bz;
        int a = alongX ? ax : az, b = alongX ? bx : bz;
        int sign = Integer.signum(b - a);
        int from = a + sign * (ha + 1), to = b - sign * (hb + 1);
        int lo = Math.min(from, to), hi = Math.max(from, to);
        int mid = (lo + hi) / 2;
        int fixed = alongX ? az : ax;
        for (int k = lo; k <= hi; k++) {
            for (int w = -3; w <= 3; w++) {
                int x = alongX ? k : fixed + w, z = alongX ? fixed + w : k;
                boolean edge = Math.abs(w) == 3;
                BlockState f = edge ? t.trim : Math.abs(w) == 2 ? t.floorB : (k % 4 == 0 && w == 0 ? t.accent : t.floorAt(rng, false));
                c.set(x, FLOOR_Y - 1, z, f);
                c.set(x, FLOOR_Y - 2, z, Math.abs(w) <= 2 ? t.wall : t.topSlab());
                if (edge) {
                    boolean post = (k - lo) % 6 == 3;
                    c.set(x, FLOOR_Y, z, post ? t.wall : t.rail);
                    if (post) c.set(x, FLOOR_Y + 1, z, t.lantern);
                }
            }
            // Arch underside.
            int depth = 2 + (int) Math.round(3 * (1 - Math.abs(k - mid) / (double) Math.max(1, (hi - lo) / 2)));
            for (int w = -2; w <= 2; w += 4)
                for (int y = FLOOR_Y - 2 - depth; y <= FLOOR_Y - 3; y++) c.set(alongX ? k : fixed + w, y, alongX ? fixed + w : k, t.wall);
        }
        // A pier down to the lava in the middle.
        int px = alongX ? mid : fixed, pz = alongX ? fixed : mid;
        for (int y = SEA_Y - 3; y <= FLOOR_Y - 3; y++)
            for (int u = -1; u <= 1; u++)
                for (int w = -2; w <= 2; w++) c.set(alongX ? px + u : px + w, y, alongX ? pz + w : pz + u, y % 5 == 0 ? t.band : t.wall);
        return new int[]{alongX ? 1 : 0, fixed, lo, hi, mid};
    }

    /** 5.19: how many trapped gold blocks a map gets, and the jackpot's chance (%). */
    static final int GOLD_TRAPS = 5, JACKPOT_CHANCE = 5;

    /**
     * 5.19: puts GOLD_TRAPS gold blocks into bridge floors, one per bridge, never near the pier in the middle.
     * Each one is rolled now so its "tell" can be built next to it:
     *  - Jackpot (5%): no tell. Safe, and drops extra gold and a diamond.
     *  - Lava drop: a magma block on each side of it, and nothing underneath - break it while standing
     *    on it and you fall to the lava sea.
     *  - Ambush: the railing beside it is a cracked brick. One piglin brute steps out when it breaks.
     *  - TNT: a pressure plate on the floor next to it. Hidden TNT underneath goes off 1.5 s later.
     */
    private static void goldTraps(Canvas c, Theme t, Layout lay, List<int[]> bridges, Random rng) {
        List<int[]> pool = new ArrayList<>(bridges);
        Collections.shuffle(pool, rng);
        BlockState gold = Blocks.GOLD_BLOCK.defaultBlockState();
        for (int[] b : pool) {
            if (lay.goldTraps.size() >= GOLD_TRAPS) break;
            boolean alongX = b[0] == 1;
            int fixed = b[1], lo = b[2], hi = b[3], mid = b[4];
            List<Integer> ks = new ArrayList<>();
            for (int k = lo + 2; k <= hi - 2; k++) if (Math.abs(k - mid) >= 3) ks.add(k);
            if (ks.isEmpty()) continue;
            int k = ks.get(rng.nextInt(ks.size()));
            int w = rng.nextInt(3) - 1; // -1, 0 or 1: away from the edges
            int x = alongX ? k : fixed + w, z = alongX ? fixed + w : k;
            int kind = rng.nextInt(100) < JACKPOT_CHANCE ? GoldTrap.JACKPOT : 1 + rng.nextInt(3);
            c.set(x, FLOOR_Y - 1, z, gold);
            // Toward the nearer end of the bridge, 4 blocks away: where an ambusher appears.
            int away = (k - lo < hi - k) ? 4 : -4;
            int ak = Math.max(lo, Math.min(hi, k + away));
            BlockPos from = new BlockPos(alongX ? ak : fixed, FLOOR_Y, alongX ? fixed : ak);
            switch (kind) {
                case GoldTrap.LAVA -> {
                    c.set(x, FLOOR_Y - 2, z, AIR);
                    for (int s = -1; s <= 1; s += 2) {
                        int ww = w + s;
                        if (Math.abs(ww) > 2) continue;
                        c.set(alongX ? k : fixed + ww, FLOOR_Y - 1, alongX ? fixed + ww : k, Blocks.MAGMA_BLOCK.defaultBlockState());
                    }
                }
                case GoldTrap.AMBUSH -> {
                    int edge = w >= 0 ? 3 : -3;
                    c.set(alongX ? k : fixed + edge, FLOOR_Y, alongX ? fixed + edge : k, t.cracked);
                }
                case GoldTrap.TNT -> {
                    c.set(x, FLOOR_Y - 2, z, Blocks.TNT.defaultBlockState());
                    int pk = k + (rng.nextBoolean() ? 1 : -1);
                    c.set(alongX ? pk : fixed + w, FLOOR_Y, alongX ? fixed + w : pk, Blocks.STONE_PRESSURE_PLATE.defaultBlockState());
                }
                default -> { }
            }
            lay.goldTraps.add(new GoldTrap(new BlockPos(x, FLOOR_Y - 1, z), kind, from));
        }
    }

    /**
     * The Boss Arena: a roofed hall with four lit pillars and a gated doorway on side `doorSide`.
     * Its whole shell is unbreakable.
     */
    private static Room bossArena(Canvas c, Theme t, int cx, int cz, int doorSide, Layout lay, Random rng) {
        int S = BOSS, H = 18;
        Cell d = new Cell(c, cx, cz, S, 0, t, rng);
        d.lock = lay.locked;
        for (int x = 0; x < S; x++)
            for (int z = 0; z < S; z++) {
                boolean border = x <= 2 || z <= 2 || x >= S - 3 || z >= S - 3;
                double dist = Math.hypot(x - 22, z - 22);
                BlockState f = border ? t.floorB : dist < 3 ? t.glow : Math.abs(dist - 9) < 0.6 || Math.abs(dist - 16) < 0.6 ? t.trim : t.floorAt(rng, false);
                d.set(x, -1, z, f);
                d.set(x, -2, z, t.wall);
                // Roof with a glowstone grid.
                d.set(x, H, z, (x % 5 == 2 && z % 5 == 2) ? t.glow : t.roof);
                boolean edge = x == 0 || z == 0 || x == S - 1 || z == S - 1;
                if (!edge) continue;
                boolean corner = (x == 0 || x == S - 1) && (z == 0 || z == S - 1);
                int along = (x == 0 || x == S - 1) ? z : x;
                for (int y = 0; y < H; y++) {
                    BlockState s = corner ? t.trim : y == 3 || y == H - 3 ? t.band : (y == 5 || y == 11) && along % 5 == 2 ? t.light : t.wall;
                    d.set(x, y, z, s);
                }
                if (along % 2 == 0) d.set(x, H + 1, z, t.wall);
            }
        for (int[] p : new int[][]{{9, 9}, {33, 9}, {9, 33}, {33, 33}}) pillar(d, p[0], p[1], 3, H);
        // Low cover arcs between the pillars.
        for (int[] s : new int[][]{{20, 6, 5, 1}, {20, 38, 5, 1}, {6, 20, 5, 0}, {38, 20, 5, 0}}) cover(d, s[0], s[1], s[2], s[3] == 1);
        // Gated doorway.
        for (int a = -3; a <= 3; a++)
            for (int y = 0; y <= 5; y++) {
                int[] p = wallSpot(S, doorSide, 22 + a);
                if (Math.abs(a) <= 2 && y <= 4) {
                    d.set(p[0], y, p[1], Blocks.IRON_BARS.defaultBlockState());
                    lay.gate.add(d.world(p[0], y, p[1]));
                } else d.set(p[0], y, p[1], t.trim);
            }
        for (int[] p : new int[][]{{2, 2}, {S - 3, 2}, {2, S - 3}, {S - 3, S - 3}, {22, 2}, {22, S - 3}, {2, 22}, {S - 3, 22}})
            column(c, d.wx(p[0], p[1]), d.wz(p[0], p[1]), 1, t.wall, t.band);
        Room arena = new Room(ARENA, -1, d.ox + 1, FLOOR_Y, d.oz + 1, d.ox + S - 2, FLOOR_Y + H - 1, d.oz + S - 2);
        int[] out = wallSpot(S, doorSide, 22);
        arena.doors.add(new BlockPos(d.ox + out[0] + SIDES[doorSide][0], FLOOR_Y, d.oz + out[1] + SIDES[doorSide][1]));
        arena.doorsIn.add(new BlockPos(d.ox + out[0] - SIDES[doorSide][0] * 2, FLOOR_Y, d.oz + out[1] - SIDES[doorSide][1] * 2));
        // The Gilded Tyrant's pillar spots: three around the middle.
        for (int k = 0; k < 3; k++) {
            double ang = Math.toRadians(30 + k * 120);
            lay.pillarSpots.add(new BlockPos(cx + (int) Math.round(Math.cos(ang) * 13), FLOOR_Y, cz + (int) Math.round(Math.sin(ang) * 13)));
        }
        return arena;
    }

    /** A 3x3x3 nook built onto the outside of an arena wall, entered through two cracked bricks at floor level. */
    private static Secret secret(Canvas c, Theme t, Room r, int side, Random rng) {
        int cx = (r.x0 + r.x1) / 2, cz = (r.z0 + r.z1) / 2;
        int along = (rng.nextBoolean() ? -1 : 1) * (8 + rng.nextInt(3)); // between buttresses, away from the middle
        int dx = SIDES[side][0], dz = SIDES[side][1];
        int wallX = dx > 0 ? r.x1 + 1 : dx < 0 ? r.x0 - 1 : cx + along;
        int wallZ = dz > 0 ? r.z1 + 1 : dz < 0 ? r.z0 - 1 : cz + along;
        Secret s = new Secret();
        // Shell: 5 wide (along the wall), 5 deep outward (wall included), floor to y+3.
        for (int o = 1; o <= 4; o++)
            for (int a = -2; a <= 2; a++)
                for (int y = -1; y <= 3; y++) {
                    int x = dx != 0 ? wallX + dx * o : wallX + a, z = dz != 0 ? wallZ + dz * o : wallZ + a;
                    boolean in = o <= 3 && Math.abs(a) <= 1 && y >= 0 && y <= 2;
                    c.set(x, FLOOR_Y + y, z, in ? AIR : y == -1 ? t.floorB : t.wall);
                }
        // Supports under it.
        for (int y = FLOOR_Y - 6; y <= FLOOR_Y - 2; y++) {
            int x = dx != 0 ? wallX + dx * 2 : wallX, z = dz != 0 ? wallZ + dz * 2 : wallZ;
            c.set(x, y, z, t.wall);
        }
        // The cracked entrance in the arena wall.
        for (int y = 0; y <= 1; y++) c.set(wallX, FLOOR_Y + y, wallZ, t.cracked);
        int chx = dx != 0 ? wallX + dx * 3 : wallX, chz = dz != 0 ? wallZ + dz * 3 : wallZ;
        s.chest = new BlockPos(chx, FLOOR_Y, chz);
        Direction face = facing(chx, chz, wallX, wallZ);
        c.set(chx, FLOOR_Y, chz, Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, face));
        c.set(chx, FLOOR_Y + 2, chz, Blocks.LANTERN.defaultBlockState().setValue(LanternBlock.HANGING, true));
        int x0 = dx != 0 ? Math.min(wallX + dx, wallX + dx * 3) : wallX - 1, x1 = dx != 0 ? Math.max(wallX + dx, wallX + dx * 3) : wallX + 1;
        int z0 = dz != 0 ? Math.min(wallZ + dz, wallZ + dz * 3) : wallZ - 1, z1 = dz != 0 ? Math.max(wallZ + dz, wallZ + dz * 3) : wallZ + 1;
        s.x0 = x0;
        s.x1 = x1;
        s.z0 = z0;
        s.z1 = z1;
        s.y0 = FLOOR_Y;
        s.y1 = FLOOR_Y + 2;
        return s;
    }

    // =====================================================================
    // Scenery
    // =====================================================================

    /** True if (x, z) is on or near anything built (platforms, bridges, the boss arena). */
    private static boolean nearBuilt(int x, int z, int cx, int cz, int bossSide, int margin) {
        int dx = x - cx, dz = z - cz;
        for (int i = -1; i <= 1; i++)
            for (int j = -1; j <= 1; j++) {
                int h = (i == 0 && j == 0 ? HUB_HALF : ROOM_HALF) + margin;
                if (Math.abs(dx - i * PITCH) <= h && Math.abs(dz - j * PITCH) <= h) return true;
            }
        // Bridges: any row/column line through the grid.
        int bw = BRIDGE_W / 2 + margin;
        for (int k = -1; k <= 1; k++) {
            if (Math.abs(dz - k * PITCH) <= bw && Math.abs(dx) <= PITCH) return true;
            if (Math.abs(dx - k * PITCH) <= bw && Math.abs(dz) <= PITCH) return true;
        }
        int bx = SIDES[bossSide][0] * BOSS_DIST, bz = SIDES[bossSide][1] * BOSS_DIST;
        if (Math.abs(dx - bx) <= BOSS_HALF + margin && Math.abs(dz - bz) <= BOSS_HALF + margin) return true;
        int ex = SIDES[bossSide][0] * PITCH, ez = SIDES[bossSide][1] * PITCH;
        return Math.abs(dx - (ex + bx) / 2) <= (SIDES[bossSide][0] != 0 ? (BOSS_DIST - PITCH) / 2 : bw)
                && Math.abs(dz - (ez + bz) / 2) <= (SIDES[bossSide][1] != 0 ? (BOSS_DIST - PITCH) / 2 : bw);
    }

    private static void scenery(Canvas c, int cx, int cz, int bossSide, Random rng) {
        int giants = 0, small = 0;
        List<int[]> placed = new ArrayList<>();
        for (int tries = 0; tries < 600 && (giants < 6 || small < 14); tries++) {
            boolean giant = giants < 6;
            int r = giant ? 6 + rng.nextInt(3) : 3 + rng.nextInt(4);
            int x = cx + rng.nextInt(HALF * 2 - 10) - HALF + 5, z = cz + rng.nextInt(HALF * 2 - 10) - HALF + 5;
            if (nearBuilt(x, z, cx, cz, bossSide, r + 5)) continue;
            boolean clash = false;
            for (int[] p : placed) if (Math.hypot(p[0] - x, p[1] - z) < p[2] + r + 6) clash = true;
            if (clash) continue;
            placed.add(new int[]{x, z, r});
            int top = giant ? 108 + rng.nextInt(10) : 70 + rng.nextInt(28);
            spire(c, x, z, r, top, giant || rng.nextInt(3) == 0, rng);
            if (giant) {
                ceiling(c, x, top + 2, z, 10 + rng.nextInt(6), rng);
                giants++;
            } else small++;
        }
    }

    private static BlockState rock(Random rng, int y, double angle) {
        int band = (int) Math.floor((angle + Math.PI) / (Math.PI / 4));
        if (y < SEA_Y + 3 && rng.nextInt(3) == 0) return Blocks.MAGMA_BLOCK.defaultBlockState();
        if (band % 3 == 0 && rng.nextInt(4) != 0) return Blocks.BASALT.defaultBlockState();
        int r = rng.nextInt(100);
        if (r < 8) return Blocks.BLACKSTONE.defaultBlockState();
        if (r < 10) return Blocks.NETHER_GOLD_ORE.defaultBlockState();
        if (r < 12) return Blocks.NETHER_QUARTZ_ORE.defaultBlockState();
        return Blocks.NETHERRACK.defaultBlockState();
    }

    /** A hollow rock spire from the lava up to `top`, tapering, with glowstone and maybe a lavafall. */
    private static void spire(Canvas c, int sx, int sz, int baseR, int top, boolean lavafall, Random rng) {
        int bottom = SEA_Y - 3;
        double driftX = 0, driftZ = 0;
        for (int y = bottom; y <= top; y++) {
            double f = (y - bottom) / (double) (top - bottom);
            double r = baseR * (1 - 0.6 * f) + (rng.nextDouble() - 0.5) * 0.8 + (y < SEA_Y + 2 ? 1.5 : 0);
            driftX += (rng.nextDouble() - 0.5) * 0.3;
            driftZ += (rng.nextDouble() - 0.5) * 0.3;
            int R = (int) Math.ceil(r);
            for (int dx = -R; dx <= R; dx++)
                for (int dz = -R; dz <= R; dz++) {
                    double dist = Math.hypot(dx - driftX, dz - driftZ);
                    boolean cap = y == top;
                    if (dist > r || (!cap && dist < r - 1.7)) continue;
                    c.set(sx + dx, y, sz + dz, rock(rng, y, Math.atan2(dz, dx)));
                }
        }
        // Glowstone clusters on the sides.
        for (int k = 0; k < 2 + rng.nextInt(3); k++) {
            int y = SEA_Y + 8 + rng.nextInt(Math.max(1, top - SEA_Y - 10));
            double ang = rng.nextDouble() * Math.PI * 2;
            double f = (y - bottom) / (double) (top - bottom);
            double r = baseR * (1 - 0.6 * f) + 0.5;
            int gx = sx + (int) Math.round(Math.cos(ang) * r), gz = sz + (int) Math.round(Math.sin(ang) * r);
            for (int dx = 0; dx < 2; dx++) for (int dz = 0; dz < 2; dz++) for (int dy = 0; dy < 2; dy++)
                if (rng.nextInt(4) != 0) c.set(gx + dx, y - dy, gz + dz, Blocks.GLOWSTONE.defaultBlockState());
        }
        if (lavafall) {
            int y = top - 4 - rng.nextInt(4);
            double ang = rng.nextDouble() * Math.PI * 2;
            double f = (y - bottom) / (double) (top - bottom);
            double r = baseR * (1 - 0.6 * f);
            int lx = sx + (int) Math.round(Math.cos(ang) * (r - 1)), lz = sz + (int) Math.round(Math.sin(ang) * (r - 1));
            int ox = (int) Math.signum(Math.round(Math.cos(ang) * 2)), oz = (int) Math.signum(Math.round(Math.sin(ang) * 2));
            if (ox == 0 && oz == 0) ox = 1;
            // A solid notch around the spring so it only pours outward.
            for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) for (int dy = -1; dy <= 1; dy++)
                c.set(lx + dx, y + dy, lz + dz, Blocks.NETHERRACK.defaultBlockState());
            c.set(lx, y, lz, Blocks.LAVA.defaultBlockState());
            for (int k = 1; k <= 2; k++) {
                c.set(lx + ox * k, y, lz + oz * k, AIR);
                c.set(lx + ox * k, y + 1, lz + oz * k, AIR);
            }
            c.set(lx + ox, y - 1, lz + oz, Blocks.NETHERRACK.defaultBlockState()); // a lip it spills over
        }
    }

    /** A flattened rock mass overhead (the "ceiling") with glowstone hanging under it. */
    private static void ceiling(Canvas c, int x, int y, int z, int r, Random rng) {
        int ry = 3 + rng.nextInt(2);
        for (int dx = -r; dx <= r; dx++)
            for (int dz = -r; dz <= r; dz++)
                for (int dy = -ry; dy <= ry; dy++) {
                    double n = Math.pow(dx / (double) r, 2) + Math.pow(dz / (double) r, 2) + Math.pow(dy / (double) ry, 2);
                    if (n > 1 || n < 0.6) continue;
                    c.set(x + dx, y + dy, z + dz, rng.nextInt(6) == 0 ? Blocks.BLACKSTONE.defaultBlockState() : Blocks.NETHERRACK.defaultBlockState());
                }
        for (int k = 0; k < 4 + rng.nextInt(4); k++) {
            int gx = x + rng.nextInt(r) - r / 2, gz = z + rng.nextInt(r) - r / 2;
            int len = 2 + rng.nextInt(4);
            for (int d = 0; d < len; d++) {
                c.set(gx, y - ry + 1 - d, gz, Blocks.GLOWSTONE.defaultBlockState());
                if (d < len - 1 && rng.nextBoolean()) c.set(gx + rng.nextInt(3) - 1, y - ry + 1 - d, gz + rng.nextInt(3) - 1, Blocks.GLOWSTONE.defaultBlockState());
            }
        }
    }

    // =====================================================================
    // Placing it, a chunk at a time
    // =====================================================================

    static final class Builder {
        final ServerLevel level;
        final long[] order;
        final BlockState[] states;
        final LongArrayList shapes = new LongArrayList();
        final Set<Long> chunks = new HashSet<>();
        int next = 0, nextShape = 0;
        boolean done = false;

        Builder(ServerLevel level, Canvas c) {
            this.level = level;
            // Group by chunk so each chunk is finished before the next one.
            Long2ObjectOpenHashMap<LongArrayList> byChunk = new Long2ObjectOpenHashMap<>();
            for (Long2ObjectMap.Entry<BlockState> e : c.map.long2ObjectEntrySet()) {
                long p = e.getLongKey();
                long ck = ((long) (BlockPos.getX(p) >> 4) & 0xFFFFFFFFL) | (((long) (BlockPos.getZ(p) >> 4) & 0xFFFFFFFFL) << 32);                byChunk.computeIfAbsent(ck, k -> new LongArrayList()).add(p);
            }
            int n = c.map.size();
            order = new long[n];
            states = new BlockState[n];
            int i = 0;
            long[] keys = byChunk.keySet().toLongArray();
            Arrays.sort(keys);
            for (long ck : keys) {
                chunks.add(ck);
                for (long p : byChunk.get(ck)) {
                    order[i] = p;
                    states[i] = c.map.get(p);
                    i++;
                }
            }
            for (long ck : chunks) level.setChunkForced(ChunkPos.getX(ck), ChunkPos.getZ(ck), true);
        }

        double progress() {
            return order.length == 0 ? 1 : Math.min(1.0, (next + nextShape * 0.5) / (double) order.length);
        }

        /** Places blocks for up to ~8 ms. True once everything is placed. */
        boolean tick() {
            if (done) return true;
            long t0 = System.nanoTime();
            BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
            while (next < order.length) {
                long p = order[next];
                BlockState s = states[next];
                next++;
                m.set(p);
                if (s.isAir() && level.getBlockState(m).isAir()) continue;
                level.setBlock(m, s, FLAGS);
                Block b = s.getBlock();
                if (b instanceof CrossCollisionBlock || b instanceof WallBlock || b instanceof StairBlock) shapes.add(p);
                if ((next & 63) == 0 && System.nanoTime() - t0 > 8_000_000L) return false;
            }
            // Then fences, walls, bars and stairs join up with their neighbours.
            while (nextShape < shapes.size()) {
                m.set(shapes.getLong(nextShape++));
                BlockState cur = level.getBlockState(m);
                BlockState fixed = Block.updateFromNeighbourShapes(cur, level, m);
                if (fixed != cur) level.setBlock(m, fixed, FLAGS);
                if ((nextShape & 255) == 0 && System.nanoTime() - t0 > 8_000_000L) return false;
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

    // =====================================================================
    // Little helpers
    // =====================================================================

    private static Direction facing(int fx, int fz, int tx, int tz) {
        int dx = tx - fx, dz = tz - fz;
        if (dx == 0 && dz == 0) return Direction.NORTH;
        if (Math.abs(dx) > Math.abs(dz)) return dx > 0 ? Direction.EAST : Direction.WEST;
        return dz > 0 ? Direction.SOUTH : Direction.NORTH;
    }

    static float yawFacing(Direction d) {
        return switch (d) {
            case SOUTH -> 0f;
            case WEST -> 90f;
            case NORTH -> 180f;
            default -> -90f;
        };
    }

    /** Marks a cleared room: the stripes over its doorways turn lime green. */
    static void markCleared(ServerLevel level, Room r) {
        BlockState lime = Blocks.WOOL.pick(DyeColor.LIME).defaultBlockState();
        for (BlockPos p : r.markers) level.setBlock(p, lime, FLAGS);
    }

    /** The Boss Arena's gate swings open (the bars vanish). */
    static void openGate(ServerLevel level, Layout lay) {
        for (BlockPos p : lay.gate) {
            level.setBlock(p, AIR, Block.UPDATE_ALL);
            lay.locked.remove(p.asLong());
        }
    }
}
