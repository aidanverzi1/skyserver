package com.aidan.skyserver.expedition;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.RandomizableContainer;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.*;

/**
 * 5.9: the built parts of a Nether Expedition, placed into the real Nether terrain around a real
 * Fortress / Bastion when a trip starts:
 *
 *  - Wren's Landing (spawn room): where the crew arrives. Its walls can't be broken, no mobs get in,
 *    nobody is hurt inside. Captain Wren waits there to take you home.
 *  - Chest rooms (3/4/5/6 by difficulty): one coloured stripe over the door, a chest at the back.
 *    Guards wake when someone walks in and never leave the room.
 *  - The boss arena: a big room with pillars, cover walls and platforms to build, hide and fight.
 *
 * Reachability: before placing rooms we flood-fill the open air from the spawn room's doorway. Rooms
 * only go on standing spots that this flood reached, and each door is cut on the side where that
 * open air is, so every room can be reached by walking - or by bridging/pillaring through open air
 * - without breaking through a wall. The vanilla loot chests of the structure are removed: all loot
 * is in the rooms.
 */
final class ExpeditionRooms {
    private ExpeditionRooms() {}

    static final int SPAWN = 0, CHEST = 1, ARENA = 2;
    /** Rooms are placed within this many blocks of the landing (and the border is a bit bigger). */
    static final int R = 56;
    /** Most cells the reachability flood-fill looks at (keeps the start of a trip quick). */
    private static final int FLOOD_LIMIT = 300_000;
    private static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;
    private static final DyeColor[] COLORS = {DyeColor.RED, DyeColor.ORANGE, DyeColor.YELLOW, DyeColor.LIGHT_BLUE,
            DyeColor.MAGENTA, DyeColor.PURPLE, DyeColor.CYAN, DyeColor.PINK};
    private static final String[] COLOR_NAMES = {"§cRed", "§6Orange", "§eYellow", "§bLight Blue", "§dMagenta", "§5Purple", "§3Cyan", "§dPink"};

    /** One built room. Interior = the air inside (inclusive coordinates). */
    static final class Room {
        final int kind, index;
        final int x0, y0, z0, x1, y1, z1;
        int colorIndex = -1;
        BlockPos chest;
        final List<BlockPos> markers = new ArrayList<>();
        /** A spot just outside each door, at floor level (for the direction arrow). */
        final List<BlockPos> doors = new ArrayList<>();
        final Set<UUID> guards = new HashSet<>();
        boolean woke = false, cleared = false;

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

        Vec3 center() {
            return new Vec3((x0 + x1 + 1) / 2.0, y0, (z0 + z1 + 1) / 2.0);
        }

        BlockPos floorCenter() {
            return new BlockPos((x0 + x1) / 2, y0, (z0 + z1) / 2);
        }

        /** Where the direction arrow points: the nearest door (or the middle). */
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
            return COLOR_NAMES[Math.max(0, colorIndex)] + " Room";
        }

        int[] outer() {
            return new int[]{x0 - 1, y0 - 1, z0 - 1, x1 + 1, y1 + 1, z1 + 1};
        }
    }

    /** Everything built for one trip. */
    static final class Layout {
        Room spawn, arena;
        final List<Room> rooms = new ArrayList<>();
        /** Blocks nobody may break (the spawn room's walls, floor and ceiling). */
        final Set<Long> locked = new HashSet<>();
        double[] wren;
        int vanillaChestsRemoved = 0;
    }

    /** The area the flood-fill and the rooms use. */
    private static final class Area {
        final int xmin, xmax, ymin, ymax, zmin, zmax, w, d;
        final BitSet seen = new BitSet();

        Area(BlockPos c) {
            xmin = c.getX() - R;
            xmax = c.getX() + R;
            zmin = c.getZ() - R;
            zmax = c.getZ() + R;
            ymin = Math.max(33, c.getY() - 24);
            ymax = Math.min(118, c.getY() + 24);
            w = xmax - xmin + 1;
            d = zmax - zmin + 1;
        }

        boolean in(int x, int y, int z) {
            return x >= xmin && x <= xmax && y >= ymin && y <= ymax && z >= zmin && z <= zmax;
        }

        int idx(int x, int y, int z) {
            return ((y - ymin) * d + (z - zmin)) * w + (x - xmin);
        }

        boolean reached(int x, int y, int z) {
            return in(x, y, z) && seen.get(idx(x, y, z));
        }
    }

    private static boolean open(BlockState s) {
        return s.isAir() || (!s.isSolid() && s.getFluidState().isEmpty());
    }

    // =====================================================================
    // Planning and building
    // =====================================================================

    /**
     * Builds everything for a trip. landing = where the crew will stand (floor level of the spawn
     * room), toward = the structure's middle (the spawn room's door faces it).
     */
    static Layout build(ServerLevel level, BlockPos landing, int towardX, int towardZ, boolean fortress, int roomCount, Random rng) {
        Layout lay = new Layout();
        BlockState wall = fortress ? Blocks.NETHER_BRICKS.defaultBlockState() : Blocks.POLISHED_BLACKSTONE_BRICKS.defaultBlockState();
        BlockState floor = fortress ? Blocks.RED_NETHER_BRICKS.defaultBlockState() : Blocks.BLACKSTONE.defaultBlockState();

        // ---- Wren's Landing ----
        Room spawn = new Room(SPAWN, -1, landing.getX() - 3, landing.getY(), landing.getZ() - 3, landing.getX() + 3, landing.getY() + 3, landing.getZ() + 3);
        lay.spawn = spawn;
        BlockState spawnWall = Blocks.POLISHED_BLACKSTONE_BRICKS.defaultBlockState();
        shell(level, spawn, spawnWall, Blocks.POLISHED_BLACKSTONE.defaultBlockState(), lay.locked);
        Direction spawnDoor = facing(landing.getX(), landing.getZ(), towardX, towardZ);
        List<BlockPos> start = new ArrayList<>();
        cutDoor(level, spawn, spawnDoor, spawnWall, Blocks.GLOWSTONE.defaultBlockState(), null, 8, start, lay.locked);
        // Wren stands at the back wall, facing the door.
        Direction back = spawnDoor.getOpposite();
        BlockPos fc = spawn.floorCenter();
        lay.wren = new double[]{fc.getX() + 0.5 + back.getStepX() * 2, fc.getY(), fc.getZ() + 0.5 + back.getStepZ() * 2, yawFacing(spawnDoor)};

        // ---- Flood-fill the open air from the doorway ----
        Area area = new Area(landing);
        List<BlockPos> spots = flood(level, area, start, spawn, rng);

        // ---- Boss arena first (it's the biggest), then the chest rooms ----
        List<int[]> taken = new ArrayList<>();
        taken.add(grow(spawn.outer(), 3));
        Room arena = null;
        for (BlockPos s : spots) {
            double dist = Math.hypot(s.getX() - landing.getX(), s.getZ() - landing.getZ());
            if (dist < 24) continue;
            Room a = new Room(ARENA, -1, s.getX() - 11, s.getY(), s.getZ() - 11, s.getX() + 11, s.getY() + 9, s.getZ() + 11);
            if (!fits(a, area, taken)) continue;
            arena = a;
            break;
        }
        if (arena == null) {
            // Nowhere open enough: carve it out next to the landing, toward the structure.
            int ax = landing.getX() + spawnDoor.getStepX() * 26, az = landing.getZ() + spawnDoor.getStepZ() * 26;
            arena = new Room(ARENA, -1, ax - 11, landing.getY(), az - 11, ax + 11, landing.getY() + 9, az + 11);
        }
        lay.arena = arena;
        taken.add(grow(arena.outer(), 3));
        for (int spacing : new int[]{16, 11, 7}) {
            for (BlockPos s : spots) {
                if (lay.rooms.size() >= roomCount) break;
                if (Math.hypot(s.getX() - landing.getX(), s.getZ() - landing.getZ()) < 12) continue;
                Room r = new Room(CHEST, lay.rooms.size(), s.getX() - 3, s.getY(), s.getZ() - 3, s.getX() + 3, s.getY() + 3, s.getZ() + 3);
                if (!fits(r, area, taken, spacing)) continue;
                lay.rooms.add(r);
                taken.add(grow(r.outer(), 1));
            }
            if (lay.rooms.size() >= roomCount) break;
        }

        // ---- Build them ----
        BlockState light = Blocks.GLOWSTONE.defaultBlockState();
        shell(level, arena, wall, floor, null);
        List<Direction> sides = sidesByOpenAir(arena, area, landing);
        for (int i = 0; i < 2 && i < sides.size(); i++) cutDoor(level, arena, sides.get(i), wall, Blocks.WOOL.pick(DyeColor.BLACK).defaultBlockState(), area, 14, null, null);
        arenaInside(level, arena, wall, light);
        for (Room r : lay.rooms) {
            r.colorIndex = r.index % COLORS.length;
            shell(level, r, wall, floor, null);
            Direction side = sidesByOpenAir(r, area, landing).get(0);
            cutDoor(level, r, side, wall, Blocks.WOOL.pick(COLORS[r.colorIndex]).defaultBlockState(), area, 10, null, null);
            // The chest against the back wall, facing the door.
            Direction backWall = side.getOpposite();
            BlockPos c = r.floorCenter().relative(backWall, 3);
            level.setBlock(c, Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, side), FLAGS);
            r.chest = c;
        }

        // ---- The structure's own loot chests go: everything is in the rooms ----
        lay.vanillaChestsRemoved = removeVanillaChests(level, landing, R + 12);
        return lay;
    }

    /** Walls, floor and ceiling of the room's block, air inside, lights in the ceiling. */
    private static void shell(ServerLevel level, Room r, BlockState wall, BlockState floor, Set<Long> lock) {
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        BlockState air = Blocks.AIR.defaultBlockState(), glow = Blocks.GLOWSTONE.defaultBlockState();
        for (int x = r.x0 - 1; x <= r.x1 + 1; x++)
            for (int z = r.z0 - 1; z <= r.z1 + 1; z++)
                for (int y = r.y0 - 1; y <= r.y1 + 1; y++) {
                    boolean edge = x == r.x0 - 1 || x == r.x1 + 1 || z == r.z0 - 1 || z == r.z1 + 1 || y == r.y0 - 1 || y == r.y1 + 1;
                    BlockState s;
                    if (!edge) s = air;
                    else if (y == r.y0 - 1) s = floor;
                    else if (y == r.y1 + 1 && Math.floorMod(x - r.x0, 3) == 1 && Math.floorMod(z - r.z0, 3) == 1 && x >= r.x0 && x <= r.x1 && z >= r.z0 && z <= r.z1) s = glow;
                    else s = wall;
                    level.setBlock(m.set(x, y, z), s, FLAGS);
                    if (edge && lock != null) lock.add(m.asLong());
                }
        // Lava next to the walls stays outside (the walls hold it), but anything flowing in the room is gone (set to air above).
    }

    /**
     * Cuts a 3-wide, 3-high door in one wall, puts the marker above it (coloured wool / glowstone)
     * and shroomlights beside it, then digs a short tunnel out until it meets open air.
     */
    private static void cutDoor(ServerLevel level, Room r, Direction side, BlockState wall, BlockState marker, Area area, int maxTunnel,
                                List<BlockPos> tunnelOut, Set<Long> lock) {
        boolean alongX = side.getAxis() == Direction.Axis.Z; // north/south walls run along x
        int cx = (r.x0 + r.x1) / 2, cz = (r.z0 + r.z1) / 2;
        int wallCoord = switch (side) {
            case NORTH -> r.z0 - 1;
            case SOUTH -> r.z1 + 1;
            case WEST -> r.x0 - 1;
            default -> r.x1 + 1;
        };
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        BlockState air = Blocks.AIR.defaultBlockState(), light = Blocks.SHROOMLIGHT.defaultBlockState();
        for (int a = -2; a <= 2; a++) {
            for (int y = r.y0; y <= r.y0 + 3; y++) {
                int x = alongX ? cx + a : wallCoord, z = alongX ? wallCoord : cz + a;
                m.set(x, y, z);
                BlockState s;
                if (Math.abs(a) <= 1 && y <= r.y0 + 2) s = air;
                else if (Math.abs(a) <= 1) s = marker;
                else if (y == r.y0 + 2) s = light;
                else continue;
                level.setBlock(m, s, FLAGS);
                if (lock != null) {
                    if (s.isAir()) lock.remove(m.asLong());
                    else lock.add(m.asLong());
                }
                if (Math.abs(a) <= 1 && y == r.y0 + 3) r.markers.add(m.immutable());
            }
        }
        // Tunnel out.
        int out = side == Direction.NORTH || side == Direction.WEST ? -1 : 1;
        BlockPos doorOutside = null;
        for (int k = 1; k <= maxTunnel; k++) {
            int coord = wallCoord + out * k;
            boolean wasOpen = true, reached = false;
            for (int a = -1; a <= 1; a++)
                for (int y = r.y0; y <= r.y0 + 2; y++) {
                    int x = alongX ? cx + a : coord, z = alongX ? coord : cz + a;
                    BlockState s = level.getBlockState(m.set(x, y, z));
                    if (!open(s)) wasOpen = false;
                    if (area != null && area.reached(x, y, z)) reached = true;
                }
            for (int a = -2; a <= 2; a++)
                for (int y = r.y0 - 1; y <= r.y0 + 3; y++) {
                    int x = alongX ? cx + a : coord, z = alongX ? coord : cz + a;
                    m.set(x, y, z);
                    BlockState s = level.getBlockState(m);
                    boolean inside = Math.abs(a) <= 1 && y >= r.y0 && y <= r.y0 + 2;
                    if (inside) {
                        if (!s.isAir()) level.setBlock(m, air, FLAGS);
                        if (tunnelOut != null) tunnelOut.add(m.immutable());
                    } else if (y == r.y0 - 1 && Math.abs(a) <= 1) {
                        if (!s.isFaceSturdy(level, m, Direction.UP)) level.setBlock(m, wall, FLAGS); // a floor to walk on
                    } else if (!s.getFluidState().isEmpty()) {
                        level.setBlock(m, wall, FLAGS); // keep lava out of the tunnel
                    }
                }
            if (doorOutside == null) doorOutside = alongX ? new BlockPos(cx, r.y0, coord) : new BlockPos(coord, r.y0, cz);
            if (area != null ? reached : (wasOpen && k >= 2)) break;
        }
        if (doorOutside != null) r.doors.add(doorOutside);
    }

    /** Pillars, low cover walls, two raised platforms with steps, and more light. */
    private static void arenaInside(ServerLevel level, Room a, BlockState wall, BlockState light) {
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        int cx = (a.x0 + a.x1) / 2, cz = (a.z0 + a.z1) / 2, y = a.y0;
        // Four big pillars.
        for (int[] p : new int[][]{{-6, -6}, {6, -6}, {-6, 6}, {6, 6}})
            for (int dx = -1; dx <= 1; dx++)
                for (int dz = -1; dz <= 1; dz++)
                    for (int dy = 0; dy <= a.y1 - a.y0; dy++) level.setBlock(m.set(cx + p[0] + dx, y + dy, cz + p[1] + dz), wall, FLAGS);
        // Low cover walls (2 high) on each side of the middle.
        for (int t = -2; t <= 2; t++)
            for (int dy = 0; dy <= 1; dy++) {
                level.setBlock(m.set(cx + t, y + dy, cz - 9), wall, FLAGS);
                level.setBlock(m.set(cx + t, y + dy, cz + 9), wall, FLAGS);
                level.setBlock(m.set(cx - 9, y + dy, cz + t), wall, FLAGS);
                level.setBlock(m.set(cx + 9, y + dy, cz + t), wall, FLAGS);
            }
        // Two raised platforms (3 high) in opposite corners, each with steps up.
        for (int[] p : new int[][]{{-9, -9, 1}, {9, 9, -1}}) {
            for (int dx = -1; dx <= 1; dx++)
                for (int dz = -1; dz <= 1; dz++)
                    for (int dy = 0; dy <= 2; dy++) level.setBlock(m.set(cx + p[0] + dx, y + dy, cz + p[1] + dz), wall, FLAGS);
            int sx = cx + p[0] + 2 * p[2], sz = cz + p[1];
            for (int dy = 0; dy <= 1; dy++) level.setBlock(m.set(sx, y + dy, sz), wall, FLAGS);
            level.setBlock(m.set(sx + p[2], y, sz), wall, FLAGS);
        }
        // Extra lights in the pillars, halfway up.
        for (int[] p : new int[][]{{-6, -6}, {6, -6}, {-6, 6}, {6, 6}})
            for (int[] f : new int[][]{{-2, 0}, {2, 0}, {0, -2}, {0, 2}}) {
                int px = cx + p[0] + Integer.signum(f[0]) * 1, pz = cz + p[1] + Integer.signum(f[1]) * 1;
                level.setBlock(m.set(px, y + 4, pz), light, FLAGS);
            }
    }

    /**
     * Flood-fills open air from the start cells (the spawn room's tunnel) and returns standing spots
     * it reached (solid floor, two open blocks, no lava next to them), in random order.
     */
    private static List<BlockPos> flood(ServerLevel level, Area area, List<BlockPos> start, Room spawn, Random rng) {
        ArrayDeque<long[]> q = new ArrayDeque<>();
        for (BlockPos s : start) {
            if (!area.in(s.getX(), s.getY(), s.getZ())) continue;
            int i = area.idx(s.getX(), s.getY(), s.getZ());
            if (area.seen.get(i)) continue;
            area.seen.set(i);
            q.add(new long[]{s.getX(), s.getY(), s.getZ()});
        }
        List<BlockPos> spots = new ArrayList<>();
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        int visited = 0;
        int[][] dirs = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
        while (!q.isEmpty() && visited < FLOOD_LIMIT) {
            long[] c = q.poll();
            int x = (int) c[0], y = (int) c[1], z = (int) c[2];
            visited++;
            // A standing spot? (checked every 2 blocks to keep the list short)
            if ((x & 1) == 0 && (z & 1) == 0 && !spawn.inside(x, y, z, 2)) {
                BlockState below = level.getBlockState(m.set(x, y - 1, z));
                if (below.isFaceSturdy(level, m, Direction.UP) && !below.is(Blocks.MAGMA_BLOCK) && open(level.getBlockState(m.set(x, y + 1, z)))) {
                    boolean lava = false;
                    for (int dx = -1; dx <= 1 && !lava; dx++)
                        for (int dz = -1; dz <= 1 && !lava; dz++)
                            for (int dy = -1; dy <= 0 && !lava; dy++)
                                if (!level.getFluidState(m.set(x + dx, y + dy, z + dz)).isEmpty()) lava = true;
                    if (!lava) spots.add(new BlockPos(x, y, z));
                }
            }
            for (int[] d : dirs) {
                int nx = x + d[0], ny = y + d[1], nz = z + d[2];
                if (!area.in(nx, ny, nz)) continue;
                int i = area.idx(nx, ny, nz);
                if (area.seen.get(i)) continue;
                if (spawn.inside(nx, ny, nz, 1)) continue; // don't wander back into the spawn room
                if (!open(level.getBlockState(m.set(nx, ny, nz)))) continue;
                area.seen.set(i);
                q.add(new long[]{nx, ny, nz});
            }
        }
        Collections.shuffle(spots, rng);
        return spots;
    }

    /** The room's sides, the one with the most reachable open air just outside first. */
    private static List<Direction> sidesByOpenAir(Room r, Area area, BlockPos landing) {
        Map<Direction, Integer> score = new EnumMap<>(Direction.class);
        int cx = (r.x0 + r.x1) / 2, cz = (r.z0 + r.z1) / 2;
        int half = (r.x1 - r.x0) / 2;
        for (Direction side : new Direction[]{Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST}) {
            boolean alongX = side.getAxis() == Direction.Axis.Z;
            int outside = switch (side) {
                case NORTH -> r.z0 - 2;
                case SOUTH -> r.z1 + 2;
                case WEST -> r.x0 - 2;
                default -> r.x1 + 2;
            };
            int n = 0;
            for (int a = -half; a <= half; a++)
                for (int y = r.y0; y <= r.y0 + 2; y++) {
                    int x = alongX ? cx + a : outside, z = alongX ? outside : cz + a;
                    if (area.reached(x, y, z)) n += Math.abs(a) <= 2 ? 3 : 1; // near the middle (where the door goes) counts more
                }
            // Small tie-break: toward the landing.
            double toward = -(Math.abs(cx + side.getStepX() * 10 - landing.getX()) + Math.abs(cz + side.getStepZ() * 10 - landing.getZ())) / 1000.0;
            score.put(side, n * 1000 + (int) (toward * 100));
        }
        List<Direction> list = new ArrayList<>(score.keySet());
        list.sort((a, b) -> Integer.compare(score.get(b), score.get(a)));
        return list;
    }

    private static boolean fits(Room r, Area area, List<int[]> taken) {
        return fits(r, area, taken, 0);
    }

    private static boolean fits(Room r, Area area, List<int[]> taken, int spacing) {
        int[] o = r.outer();
        if (o[0] < area.xmin || o[3] > area.xmax || o[2] < area.zmin || o[5] > area.zmax) return false;
        if (o[1] < 32 || o[4] > 124) return false;
        int[] g = grow(o, spacing);
        for (int[] t : taken) if (overlap(g, t)) return false;
        return true;
    }

    private static int[] grow(int[] b, int n) {
        return new int[]{b[0] - n, b[1], b[2] - n, b[3] + n, b[4], b[5] + n};
    }

    private static boolean overlap(int[] a, int[] b) {
        return a[0] <= b[3] && a[3] >= b[0] && a[1] <= b[4] && a[4] >= b[1] && a[2] <= b[5] && a[5] >= b[2];
    }

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

    /** Removes the structure's vanilla loot chests (barrels too) within `radius` of the landing. */
    private static int removeVanillaChests(ServerLevel level, BlockPos c, int radius) {
        int n = 0;
        for (int cx = (c.getX() - radius) >> 4; cx <= (c.getX() + radius) >> 4; cx++)
            for (int cz = (c.getZ() - radius) >> 4; cz <= (c.getZ() + radius) >> 4; cz++) {
                for (BlockEntity be : new ArrayList<>(level.getChunk(cx, cz).getBlockEntities().values())) {
                    if (!(be instanceof RandomizableContainer rc) || rc.getLootTable() == null) continue;
                    level.setBlock(be.getBlockPos(), Blocks.AIR.defaultBlockState(), FLAGS);
                    n++;
                }
            }
        return n;
    }

    /** Marks a cleared room: the stripe over its door turns lime green. */
    static void markCleared(ServerLevel level, Room r) {
        BlockState lime = Blocks.WOOL.pick(DyeColor.LIME).defaultBlockState();
        for (BlockPos p : r.markers) level.setBlock(p, lime, FLAGS);
    }
}
