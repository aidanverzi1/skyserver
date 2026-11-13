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
 * 5.21: the Ancient City Expedition's map - a 201 x 201 deepslate MAZE, built fresh for every trip in its own
 * world (skyserver:ancient_city, deep dark biome: nothing spawns there by itself).
 *
 * THREE FIXED MAPS rotate weekly (CityMarket.mapIndex(): map 1, 2, 3, 1... switching Thursday 00:00 Eastern).
 * The same map always has the same maze, house spots, plazas, rope shafts, carpet and (per difficulty) the same
 * carpet gaps, sensors, shriekers and urns - players can learn it. What changes EVERY TRIP: which houses are real
 * (lit candles inside), which are decoys (unlit candles), which are sealed, and which shaft gets Wren's rope.
 *
 * Layout (from the middle; floor = FLOOR_Y, the first air layer):
 *  - Passages 1-3 wide on a 4-block grid (walls 1 thick, 7 tall to the ceiling), with dead ends, loops and plazas.
 *  - The middle: a domed plaza (16 tall) with the reinforced deepslate PORTAL FRAME; the relic (a sculk catalyst)
 *    sits on its pedestal. South of it: the DEEP VAULT plug (3x3x3 cracked deepslate tiles) over a shaft down to
 *    the vault room. The plug breaks to a Warden's sonic boom or a pickaxe (very loud).
 *  - The south wall: WREN'S LIFT, where the crew lands (safe inside). Once the relic is taken it jams: Wren drops
 *    a rope at one of the 3 ROPE SHAFTS instead (far corners / sides, 90+ blocks from the lift).
 *  - 14 house spots (7 x 7 rooms with one door), grey carpet along the main routes (gaps on purpose), sculk
 *    sensors near the gaps, shriekers, sculk patches, urns (decorated pots) in dead ends, soul lanterns.
 * Numbers by difficulty are in the arrays below (index 0 Easy .. 3 Nightmare).
 */
final class AncientCityMap {
    private AncientCityMap() {}

    static final int FLOOR_Y = 64, R = 100, HEIGHT = 7, DOME = 20, DOME_HEIGHT = 16;
    /** Grid: one cell every P blocks (3 air + 1 wall), N cells a side. */
    static final int P = 4, N = 50;
    /** Real houses / decoys per trip (the rest of the 14 spots are sealed). */
    static final int[] HOUSES = {5, 6, 7, 8}, DECOYS = {3, 4, 5, 6};
    static final int HOUSE_SPOTS = 14;
    static final int[] SENSORS = {90, 120, 160, 200};
    static final int[] SHRIEKERS = {10, 14, 18, 24};
    static final int[] SCULK_PATCHES = {30, 40, 50, 60};
    static final int[] URNS = {15, 20, 25, 30};
    /** Chance (%) a carpeted cell of a route has no carpet (you cross bare floor there). */
    static final int[] CARPET_GAPS = {20, 35, 50, 65};
    /** The three fixed map seeds. */
    static final long[] SEEDS = {0x5C17A1L, 0xDEE9C17L, 0xA4C1E47L};
    private static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;

    /** One house spot: its door, its chest, its candles. */
    static final class House {
        BlockPos chest, door;
        final List<BlockPos> candles = new ArrayList<>();
        /** 0 = real, 1 = decoy, 2 = sealed (for this trip). */
        int kind;
    }

    /** Everything planned for one trip. */
    static final class Layout {
        int map;
        BlockPos center, relic, vaultChest;
        /** Where the crew lands, and where Captain Wren stands {x, y, z, yaw}. */
        double[] landing, wren;
        /** The lift: the safe box at the south wall {x0, z0, x1, z1} (inclusive, world coords). */
        int[] lift;
        final List<House> houses = new ArrayList<>();
        final List<BlockPos> shriekers = new ArrayList<>();
        final List<BlockPos> sensors = new ArrayList<>();
        final List<BlockPos> urns = new ArrayList<>();
        /** The 3 rope shaft centres (floor level). */
        final List<BlockPos> shafts = new ArrayList<>();
        /** Floor spots the roaming Wardens walk between (cell centres of plazas and corridors). */
        final List<BlockPos> patrol = new ArrayList<>();
        /** The Deep Vault plug (breakable). */
        final Set<Long> plug = new HashSet<>();
        /** Every block the city is made of (none can be broken, except the plug). */
        final Long2ObjectOpenHashMap<BlockState> blocks = new Long2ObjectOpenHashMap<>();
        Builder builder;

        boolean inLift(double x, double y, double z) {
            return x >= lift[0] && x < lift[2] + 1 && z >= lift[1] && z < lift[3] + 1 && y >= FLOOR_Y - 1 && y < FLOOR_Y + HEIGHT;
        }

        boolean isCityBlock(BlockPos p) {
            BlockState s = blocks.get(p.asLong());
            return s != null && !s.isAir();
        }

        /** Near a rope shaft (the escape zone: 3 x 3 around it). */
        boolean atShaft(BlockPos shaft, double x, double y, double z) {
            return Math.abs(x - (shaft.getX() + 0.5)) <= 2.0 && Math.abs(z - (shaft.getZ() + 0.5)) <= 2.0 && y >= FLOOR_Y - 1 && y < FLOOR_Y + HEIGHT;
        }

        House houseAt(BlockPos chest) {
            for (House h : houses) if (h.chest.equals(chest)) return h;
            return null;
        }

        int realCount() {
            int n = 0;
            for (House h : houses) if (h.kind == 0) n++;
            return n;
        }

        int[] box() {
            return new int[]{center.getX() - R - 3, center.getZ() - R - 3, center.getX() + R + 3, center.getZ() + R + 3};
        }
    }

    private static BlockState b(String id) {
        return BuiltInRegistries.BLOCK.getValue(Identifier.withDefaultNamespace(id)).defaultBlockState();
    }

    // =====================================================================
    // The maze (cells, walls)
    // =====================================================================

    /** Local x/z of a cell's middle block. */
    static int mid(int i) {
        return -R + 2 + P * i;
    }

    /** Local x/z of the wall line before cell i (i = 0..N). */
    static int line(int i) {
        return -R + P * i;
    }

    private static final class Maze {
        /** east[i][j]: wall between (i,j) and (i+1,j). south[i][j]: wall between (i,j) and (i,j+1). */
        final boolean[][] east = new boolean[N][N], south = new boolean[N][N];
        /** 0 = maze cell, 1 = house cell, 2 = plaza cell (walls inside its plaza removed). */
        final int[][] kind = new int[N][N];
        /** Plaza id per cell (-1 none). */
        final int[][] plaza = new int[N][N];
        /** House spot index per cell (-1 none). */
        final int[][] house = new int[N][N];
        /** 0 = full width, 1 = 2 wide, 2 = 1 wide (pinched corridor). */
        final int[][] pinch = new int[N][N];

        Maze() {
            for (int i = 0; i < N; i++)
                for (int j = 0; j < N; j++) {
                    east[i][j] = true;
                    south[i][j] = true;
                    plaza[i][j] = -1;
                    house[i][j] = -1;
                }
        }

        boolean open(int i, int j, int dir) {
            return switch (dir) {
                case 0 -> j > 0 && !south[i][j - 1];   // north
                case 1 -> i < N - 1 && !east[i][j];    // east
                case 2 -> j < N - 1 && !south[i][j];   // south
                default -> i > 0 && !east[i - 1][j];   // west
            };
        }

        void setOpen(int i, int j, int dir, boolean open) {
            switch (dir) {
                case 0 -> { if (j > 0) south[i][j - 1] = !open; }
                case 1 -> { if (i < N - 1) east[i][j] = !open; }
                case 2 -> { if (j < N - 1) south[i][j] = !open; }
                default -> { if (i > 0) east[i - 1][j] = !open; }
            }
        }

        int openings(int i, int j) {
            int n = 0;
            for (int d = 0; d < 4; d++) if (open(i, j, d)) n++;
            return n;
        }
    }

    private static final int[] DI = {0, 1, 0, -1}, DJ = {-1, 0, 1, 0};

    private static boolean inGrid(int i, int j) {
        return i >= 0 && j >= 0 && i < N && j < N;
    }

    /** Builds the fixed part of a map (same every time for the same seed). */
    private static Maze maze(long seed, List<int[]> houseCells, List<int[]> shaftCells) {
        Random rng = new Random(seed);
        Maze m = new Maze();
        int plazaId = 0;
        // ---- Plazas: the domed middle, the lift, the 3 rope shafts, and a few small ones ----
        for (int i = 0; i < N; i++)
            for (int j = 0; j < N; j++) {
                int x = mid(i), z = mid(j);
                if (Math.abs(x) <= DOME && Math.abs(z) <= DOME) { m.kind[i][j] = 2; m.plaza[i][j] = 0; }
                else if (z >= R - 12 && Math.abs(x) <= 8) { m.kind[i][j] = 2; m.plaza[i][j] = 1; }
            }
        plazaId = 2;
        // Rope shafts: NW and NE corners, and one side (west or east, by the seed).
        int[][] shafts = {{0, 0}, {N - 2, 0}, rng.nextBoolean() ? new int[]{0, N / 2 - 1} : new int[]{N - 2, N / 2 - 1}};
        for (int[] s : shafts) {
            for (int a = 0; a < 2; a++) for (int c = 0; c < 2; c++) { m.kind[s[0] + a][s[1] + c] = 2; m.plaza[s[0] + a][s[1] + c] = plazaId; }
            shaftCells.add(s);
            plazaId++;
        }
        // ---- House spots (2 x 2 cells each) ----
        for (int tries = 0; tries < 5000 && houseCells.size() < HOUSE_SPOTS; tries++) {
            int i = 1 + rng.nextInt(N - 3), j = 1 + rng.nextInt(N - 3);
            int x = mid(i) + 2, z = mid(j) + 2;
            if (Math.abs(x) <= DOME + 8 && Math.abs(z) <= DOME + 8) continue;
            if (z >= R - 28 && Math.abs(x) <= 20) continue;
            boolean ok = true;
            for (int a = -1; a <= 2 && ok; a++)
                for (int c = -1; c <= 2 && ok; c++) if (!inGrid(i + a, j + c) || m.kind[i + a][j + c] != 0) ok = false;
            for (int[] h : houseCells) if (Math.abs(h[0] - i) < 5 && Math.abs(h[1] - j) < 5) ok = false;
            if (!ok) continue;
            for (int a = 0; a < 2; a++) for (int c = 0; c < 2; c++) { m.kind[i + a][j + c] = 1; m.house[i + a][j + c] = houseCells.size(); }
            houseCells.add(new int[]{i, j});
        }
        // Small plazas.
        for (int tries = 0, made = 0; tries < 2000 && made < 9; tries++) {
            int size = 2 + rng.nextInt(2), i = 1 + rng.nextInt(N - size - 1), j = 1 + rng.nextInt(N - size - 1);
            boolean ok = true;
            for (int a = -1; a <= size && ok; a++)
                for (int c = -1; c <= size && ok; c++) if (!inGrid(i + a, j + c) || m.kind[i + a][j + c] != 0) ok = false;
            if (!ok) continue;
            for (int a = 0; a < size; a++) for (int c = 0; c < size; c++) { m.kind[i + a][j + c] = 2; m.plaza[i + a][j + c] = plazaId; }
            plazaId++;
            made++;
        }
        // ---- The maze itself: depth-first carving over every non-house cell ----
        boolean[][] seen = new boolean[N][N];
        int startI = N / 2, startJ = N - 1;
        carve(m, seen, startI, startJ, rng);
        // Anything not reached (cut off by houses): carve from there and join it to the rest.
        boolean changed = true;
        while (changed) {
            changed = false;
            for (int i = 0; i < N; i++)
                for (int j = 0; j < N; j++) {
                    if (seen[i][j] || m.kind[i][j] == 1) continue;
                    boolean[][] part = new boolean[N][N];
                    carve(m, part, i, j, rng);
                    // Join: knock a wall between this part and a cell already reached.
                    outer:
                    for (int a = 0; a < N; a++)
                        for (int c = 0; c < N; c++) {
                            if (!part[a][c]) continue;
                            for (int d = 0; d < 4; d++) {
                                int ni = a + DI[d], nj = c + DJ[d];
                                if (inGrid(ni, nj) && seen[ni][nj]) {
                                    m.setOpen(a, c, d, true);
                                    break outer;
                                }
                            }
                        }
                    for (int a = 0; a < N; a++) for (int c = 0; c < N; c++) if (part[a][c]) seen[a][c] = true;
                    changed = true;
                }
        }
        // Loops: knock out some extra walls between maze cells.
        for (int i = 0; i < N; i++)
            for (int j = 0; j < N; j++) {
                if (m.kind[i][j] == 1) continue;
                if (i < N - 1 && m.east[i][j] && m.kind[i + 1][j] != 1 && rng.nextInt(100) < 10) m.east[i][j] = false;
                if (j < N - 1 && m.south[i][j] && m.kind[i][j + 1] != 1 && rng.nextInt(100) < 10) m.south[i][j] = false;
            }
        // Plazas: no walls inside one plaza.
        for (int i = 0; i < N; i++)
            for (int j = 0; j < N; j++) {
                if (m.plaza[i][j] < 0) continue;
                if (i < N - 1 && m.plaza[i + 1][j] == m.plaza[i][j]) m.east[i][j] = false;
                if (j < N - 1 && m.plaza[i][j + 1] == m.plaza[i][j]) m.south[i][j] = false;
            }
        // Houses: open inside, closed outside (the door comes later).
        for (int i = 0; i < N; i++)
            for (int j = 0; j < N; j++) {
                if (m.kind[i][j] != 1) continue;
                for (int d = 0; d < 4; d++) {
                    int ni = i + DI[d], nj = j + DJ[d];
                    boolean same = inGrid(ni, nj) && m.house[ni][nj] == m.house[i][j];
                    m.setOpen(i, j, d, same);
                }
            }
        // Pinched corridors (straight cells only).
        for (int i = 0; i < N; i++)
            for (int j = 0; j < N; j++) {
                if (m.kind[i][j] != 0) continue;
                boolean ns = m.open(i, j, 0) || m.open(i, j, 2), ew = m.open(i, j, 1) || m.open(i, j, 3);
                if (ns == ew) continue;
                int r = rng.nextInt(100);
                m.pinch[i][j] = r < 8 ? 2 : r < 33 ? 1 : 0;
            }
        return m;
    }

    /** Iterative depth-first carving from (i, j) over cells that aren't houses. */
    private static void carve(Maze m, boolean[][] seen, int si, int sj, Random rng) {
        Deque<int[]> stack = new ArrayDeque<>();
        seen[si][sj] = true;
        stack.push(new int[]{si, sj});
        Integer[] dirs = {0, 1, 2, 3};
        while (!stack.isEmpty()) {
            int[] c = stack.peek();
            List<Integer> order = new ArrayList<>(Arrays.asList(dirs));
            Collections.shuffle(order, rng);
            boolean moved = false;
            for (int d : order) {
                int ni = c[0] + DI[d], nj = c[1] + DJ[d];
                if (!inGrid(ni, nj) || seen[ni][nj] || m.kind[ni][nj] == 1) continue;
                m.setOpen(c[0], c[1], d, true);
                seen[ni][nj] = true;
                stack.push(new int[]{ni, nj});
                moved = true;
                break;
            }
            if (!moved) stack.pop();
        }
    }

    /** Shortest cell path from (si, sj) to (ti, tj) through open walls (empty if none). */
    private static List<int[]> path(Maze m, int si, int sj, int ti, int tj) {
        int[][] from = new int[N][N];
        for (int[] row : from) Arrays.fill(row, -2);
        ArrayDeque<int[]> q = new ArrayDeque<>();
        from[si][sj] = -1;
        q.add(new int[]{si, sj});
        while (!q.isEmpty()) {
            int[] c = q.poll();
            if (c[0] == ti && c[1] == tj) break;
            for (int d = 0; d < 4; d++) {
                if (!m.open(c[0], c[1], d)) continue;
                int ni = c[0] + DI[d], nj = c[1] + DJ[d];
                if (from[ni][nj] != -2) continue;
                from[ni][nj] = c[0] * N + c[1];
                q.add(new int[]{ni, nj});
            }
        }
        List<int[]> out = new ArrayList<>();
        if (from[ti][tj] == -2) return out;
        int cur = ti * N + tj;
        while (cur != -1) {
            out.add(0, new int[]{cur / N, cur % N});
            cur = from[cur / N][cur % N];
        }
        return out;
    }

    // =====================================================================
    // Planning one trip
    // =====================================================================

    /**
     * map: 0..2 (the week's map). d: difficulty. trip: this trip's own random (decoys, rope shaft...).
     */
    static Layout plan(ServerLevel level, BlockPos center, int map, int d, Random trip) {
        Layout lay = new Layout();
        lay.map = map;
        lay.center = center;
        long seed = SEEDS[Math.floorMod(map, SEEDS.length)];
        List<int[]> houseCells = new ArrayList<>(), shaftCells = new ArrayList<>();
        Maze m = maze(seed, houseCells, shaftCells);
        Random diff = new Random(seed * 31 + d * 7919L + 17);
        Long2ObjectOpenHashMap<BlockState> c = lay.blocks;
        int cx = center.getX(), cz = center.getZ(), fy = FLOOR_Y;
        BlockState deepslate = b("deepslate"), tiles = b("deepslate_tiles"), cobbled = b("cobbled_deepslate"),
                cracked = b("cracked_deepslate_tiles"), bricks = b("deepslate_bricks"), polished = b("polished_deepslate"),
                reinforced = b("reinforced_deepslate"), chiseled = b("chiseled_deepslate"), sculk = b("sculk"),
                soulLantern = b("soul_lantern"), crackedBricks = b("cracked_deepslate_bricks"), crackedTiles = b("cracked_deepslate_tiles");

        // ---- The shell: floor (2 thick), ceiling (2 thick, higher over the dome), outer walls ----
        for (int x = -R - 2; x <= R + 2; x++)
            for (int z = -R - 2; z <= R + 2; z++) {
                boolean wall = Math.abs(x) > R || Math.abs(z) > R;
                boolean dome = Math.abs(x) <= DOME && Math.abs(z) <= DOME;
                boolean domeRim = !dome && Math.abs(x) <= DOME + 1 && Math.abs(z) <= DOME + 1;
                int ceil = dome ? fy + DOME_HEIGHT : fy + HEIGHT;
                int top = wall || domeRim ? fy + DOME_HEIGHT + 1 : ceil + 1;
                for (int y = fy - 2; y <= top; y++) {
                    boolean floor = y < fy;
                    if (!wall && !floor && y < ceil && !(domeRim && y >= fy + HEIGHT)) continue;
                    BlockState s = deepslate;
                    if (y == fy - 1 && !wall) {
                        int r = diff.nextInt(100);
                        s = r < 20 ? cobbled : r < 25 ? cracked : tiles;
                    }
                    set(c, cx + x, y, cz + z, s);
                }
            }

        // ---- Maze walls ----
        for (int i = 0; i <= N; i++)
            for (int j = 0; j < N; j++) {
                // Vertical wall at x = line(i), along cell j.
                boolean solid = i == 0 || i == N || m.east[i - 1][j];
                if (solid) for (int k = 1; k <= 3; k++) column(c, cx + line(i), cz + line(j) + k, fy, diff, bricks, crackedBricks, tiles);
                // Horizontal wall at z = line(i), along cell j (x).
                boolean solidH = i == 0 || i == N || m.south[j][i - 1];
                if (solidH) for (int k = 1; k <= 3; k++) column(c, cx + line(j) + k, cz + line(i), fy, diff, bricks, crackedBricks, tiles);
            }
        // Posts where walls meet (gone only when every wall around them is gone).
        for (int i = 0; i <= N; i++)
            for (int j = 0; j <= N; j++) {
                boolean any = i == 0 || j == 0 || i == N || j == N;
                if (!any) any = m.east[i - 1][j - 1] || m.east[i - 1][j] || m.south[i - 1][j - 1] || m.south[i][j - 1];
                if (any) column(c, cx + line(i), cz + line(j), fy, diff, bricks, crackedBricks, tiles);
            }
        // Pinches: fill the edge columns of straight corridor cells.
        for (int i = 0; i < N; i++)
            for (int j = 0; j < N; j++) {
                if (m.pinch[i][j] == 0) continue;
                boolean ns = m.open(i, j, 0) || m.open(i, j, 2);
                int x0 = line(i) + 1, z0 = line(j) + 1;
                for (int k = 0; k < 3; k++) {
                    if (ns) {
                        column(c, cx + x0, cz + z0 + k, fy, diff, bricks, crackedBricks, tiles);
                        if (m.pinch[i][j] == 2) column(c, cx + x0 + 2, cz + z0 + k, fy, diff, bricks, crackedBricks, tiles);
                    } else {
                        column(c, cx + x0 + k, cz + z0, fy, diff, bricks, crackedBricks, tiles);
                        if (m.pinch[i][j] == 2) column(c, cx + x0 + k, cz + z0 + 2, fy, diff, bricks, crackedBricks, tiles);
                    }
                }
            }

        // ---- Wren's lift (south wall) ----
        int lz0 = cz + R - 8, lz1 = cz + R - 1;
        lay.lift = new int[]{cx - 5, lz0, cx + 5, lz1};
        for (int x = -5; x <= 5; x++)
            for (int z = lz0; z <= lz1; z++) {
                set(c, cx + x, fy - 1, z, (Math.abs(x) == 5 || z == lz0) ? chiseled : polished);
                for (int y = fy; y < fy + HEIGHT; y++) c.remove(BlockPos.asLong(cx + x, y, z));
            }
        for (int[] p : new int[][]{{-5, lz0}, {5, lz0}}) {
            set(c, cx + p[0], fy, p[1], bricks);
            set(c, cx + p[0], fy + 1, p[1], soulLantern);
        }
        lay.landing = new double[]{cx + 0.5, fy, lz1 - 2.5, 180};
        lay.wren = new double[]{cx + 0.5, fy, lz1 + 0.5 - 0.0, 180};

        // ---- The portal frame and the relic ----
        for (int side = -1; side <= 1; side += 2)
            for (int x = 5; x <= 7; x++)
                for (int z = -1; z <= 1; z++)
                    for (int y = 0; y <= 9; y++) set(c, cx + side * x, fy + y, cz + z, reinforced);
        for (int x = -7; x <= 7; x++)
            for (int z = -1; z <= 1; z++)
                for (int y = 10; y <= 11; y++) set(c, cx + x, fy + y, cz + z, reinforced);
        for (int x = -4; x <= 4; x++)
            for (int z = -4; z <= 4; z++) set(c, cx + x, fy - 1, cz + z, Math.abs(x) == 4 || Math.abs(z) == 4 ? chiseled : polished);
        for (int x = -1; x <= 1; x++)
            for (int z = -1; z <= 1; z++) set(c, cx + x, fy, cz + z, x == 0 && z == 0 ? reinforced : polished);
        lay.relic = new BlockPos(cx, fy + 1, cz);
        set(c, cx, fy + 1, cz, b("sculk_catalyst"));
        // Dome lanterns.
        for (int[] p : new int[][]{{-12, -12}, {12, -12}, {-12, 12}, {12, 12}}) {
            for (int y = 0; y < DOME_HEIGHT; y++) set(c, cx + p[0], fy + y, cz + p[1], y % 5 == 4 ? soulLantern : bricks);
        }

        // ---- The Deep Vault: the plug (3x3x3) south of the frame, a shaft, the vault room under the floor ----
        int vz = 12;
        for (int x = -4; x <= 4; x++)
            for (int z = vz - 4; z <= vz + 4; z++)
                for (int y = fy - 8; y <= fy - 3; y++) set(c, cx + x, y, cz + z, deepslate);
        for (int x = -3; x <= 3; x++)
            for (int z = vz - 3; z <= vz + 3; z++)
                for (int y = fy - 7; y <= fy - 4; y++) c.remove(BlockPos.asLong(cx + x, y, cz + z));
        for (int x = -3; x <= 3; x++) for (int z = vz - 3; z <= vz + 3; z++) set(c, cx + x, fy - 8, cz + z, polished);
        for (int y = fy - 3; y <= fy - 1; y++) c.remove(BlockPos.asLong(cx, y, cz + vz)); // the shaft (1 x 1)
        for (int x = -1; x <= 1; x++)
            for (int z = vz - 1; z <= vz + 1; z++)
                for (int y = fy; y <= fy + 2; y++) {
                    set(c, cx + x, y, cz + z, crackedTiles);
                    lay.plug.add(BlockPos.asLong(cx + x, y, cz + z));
                }
        lay.vaultChest = new BlockPos(cx, fy - 7, cz + vz + 3);
        set(c, cx, fy - 7, cz + vz + 3, Blocks.CHEST.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH));
        set(c, cx - 3, fy - 7, cz + vz - 3, soulLantern);
        set(c, cx + 3, fy - 7, cz + vz - 3, soulLantern);
        set(c, cx - 3, fy - 7, cz + vz + 3, soulLantern);
        set(c, cx + 3, fy - 7, cz + vz + 3, soulLantern);

        // ---- Rope shafts ----
        for (int[] s : shaftCells) {
            int sx = line(s[0] + 1), sz = line(s[1] + 1); // the middle of the 2 x 2 cells
            BlockPos at = new BlockPos(cx + sx, fy, cz + sz);
            lay.shafts.add(at);
            for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++) set(c, cx + sx + x, fy - 1, cz + sz + z, x == 0 && z == 0 ? chiseled : polished);
            for (int y = fy; y < fy + HEIGHT; y++) c.remove(BlockPos.asLong(cx + sx, y, cz + sz)); // no post in the middle
        }

        // ---- Houses: door, chest, candles (lit in real houses, unlit in decoys), sealed when unused ----
        List<Integer> order = new ArrayList<>();
        for (int h = 0; h < houseCells.size(); h++) order.add(h);
        Collections.shuffle(order, trip);
        int real = HOUSES[d], decoys = DECOYS[d];
        BlockState carpet = Blocks.CARPET.pick(DyeColor.GRAY).defaultBlockState();
        List<int[]> doorCells = new ArrayList<>();
        for (int k = 0; k < houseCells.size(); k++) {
            int idx = order.get(k);
            int[] hc = houseCells.get(idx);
            int kind = k < real ? 0 : k < real + decoys ? 1 : 2;
            House h = house(lay, m, c, cx, cz, hc[0], hc[1], kind, new Random(seed * 131 + idx), bricks, crackedBricks, polished, soulLantern, doorCells);
            h.kind = kind;
            lay.houses.add(h);
        }

        // ---- Carpet along the main routes (lift -> houses, relic, vault, shafts), with gaps ----
        int li = N / 2, lj = N - 1;
        List<int[]> targets = new ArrayList<>(doorCells);
        targets.add(new int[]{N / 2, N / 2});
        for (int[] s : shaftCells) targets.add(s);
        Set<Long> routeCells = new LinkedHashSet<>();
        Set<Long> routeEdges = new HashSet<>();
        for (int[] t : targets) {
            List<int[]> p = path(m, li, lj, t[0], t[1]);
            for (int k = 0; k < p.size(); k++) {
                routeCells.add(key(p.get(k)[0], p.get(k)[1]));
                if (k > 0) {
                    routeEdges.add(edge(p.get(k - 1), p.get(k)));
                    routeEdges.add(edge(p.get(k), p.get(k - 1)));
                }
            }
        }
        List<int[]> gapCells = new ArrayList<>();
        for (long rc : routeCells) {
            int i = (int) (rc >> 16), j = (int) (rc & 0xFFFF);
            boolean gap = diff.nextInt(100) < CARPET_GAPS[d] && !(mid(j) >= R - 12 && Math.abs(mid(i)) <= 8);
            if (gap) {
                gapCells.add(new int[]{i, j});
                continue;
            }
            int x0 = mid(i), z0 = mid(j);
            setCarpet(c, cx + x0, fy, cz + z0, carpet);
            for (int dd = 0; dd < 4; dd++) {
                int ni = i + DI[dd], nj = j + DJ[dd];
                if (!inGrid(ni, nj) || !routeEdges.contains(edge(new int[]{i, j}, new int[]{ni, nj}))) continue;
                for (int s = 1; s <= 2; s++) setCarpet(c, cx + x0 + DI[dd] * s, fy, cz + z0 + DJ[dd] * s, carpet);
            }
        }

        // ---- Sculk patches ----
        for (int k = 0; k < SCULK_PATCHES[d]; k++) {
            int px = diff.nextInt(2 * R - 8) - R + 4, pz = diff.nextInt(2 * R - 24) - R + 4, rad = 1 + diff.nextInt(3);
            if (Math.abs(px) <= 6 && Math.abs(pz) <= 16) continue;
            for (int x = -rad; x <= rad; x++)
                for (int z = -rad; z <= rad; z++)
                    if (x * x + z * z <= rad * rad + diff.nextInt(2)) {
                        long k2 = BlockPos.asLong(cx + px + x, fy - 1, cz + pz + z);
                        BlockState under = c.get(k2);
                        if (under != null && !under.is(Blocks.CHEST)) c.put(k2, sculk);
                    }
        }

        // ---- Sensors: next to the carpet gaps first, a ring round the relic, the rest in corridors ----
        BlockState sensor = b("sculk_sensor");
        int placed = 0, nearRelic = 4 + 2 * d;
        for (int k = 0; k < nearRelic; k++) {
            double a = diff.nextDouble() * Math.PI * 2, rr = 2.5 + diff.nextDouble() * 1.2;
            int x = (int) Math.round(Math.cos(a) * rr), z = (int) Math.round(Math.sin(a) * rr);
            if (placeOn(c, cx + x, fy, cz + z, sensor)) { lay.sensors.add(new BlockPos(cx + x, fy, cz + z)); placed++; }
        }
        Collections.shuffle(gapCells, diff);
        for (int[] g : gapCells) {
            if (placed >= SENSORS[d] * 6 / 10) break;
            if (m.pinch[g[0]][g[1]] == 2) continue;
            int[] corner = {diff.nextBoolean() ? -1 : 1, diff.nextBoolean() ? -1 : 1};
            int x = mid(g[0]) + corner[0], z = mid(g[1]) + corner[1];
            if (placeOn(c, cx + x, fy, cz + z, sensor)) { lay.sensors.add(new BlockPos(cx + x, fy, cz + z)); placed++; }
        }
        for (int tries = 0; tries < 20000 && placed < SENSORS[d]; tries++) {
            int i = diff.nextInt(N), j = diff.nextInt(N);
            if (m.kind[i][j] == 1 || m.pinch[i][j] == 2 || (mid(j) >= R - 12 && Math.abs(mid(i)) <= 8)) continue;
            int x = mid(i) + diff.nextInt(3) - 1, z = mid(j) + diff.nextInt(3) - 1;
            if (Math.abs(x) <= 8 && Math.abs(z) <= 17) continue;
            if (placeOn(c, cx + x, fy, cz + z, sensor)) { lay.sensors.add(new BlockPos(cx + x, fy, cz + z)); placed++; }
        }

        // ---- Shriekers (on 3x3 sculk, in plazas and junctions) ----
        BlockState shrieker = b("sculk_shrieker").setValue(BlockStateProperties.CAN_SUMMON, true);
        for (int tries = 0; tries < 20000 && lay.shriekers.size() < SHRIEKERS[d]; tries++) {
            int i = diff.nextInt(N), j = diff.nextInt(N);
            if (m.kind[i][j] == 1 || m.pinch[i][j] != 0 || (mid(j) >= R - 16 && Math.abs(mid(i)) <= 12)) continue;
            if (m.kind[i][j] == 0 && m.openings(i, j) < 3) continue;
            int x = mid(i), z = mid(j);
            if (Math.abs(x) <= 9 && Math.abs(z) <= 17) continue;
            boolean far = true;
            for (BlockPos s : lay.shriekers) if (Math.abs(s.getX() - cx - x) < 14 && Math.abs(s.getZ() - cz - z) < 14) far = false;
            if (!far || c.containsKey(BlockPos.asLong(cx + x, fy, cz + z))) continue;
            for (int ox = -1; ox <= 1; ox++) for (int oz = -1; oz <= 1; oz++) {
                long k2 = BlockPos.asLong(cx + x + ox, fy - 1, cz + z + oz);
                if (c.containsKey(k2)) c.put(k2, sculk);
            }
            set(c, cx + x, fy, cz + z, shrieker);
            lay.shriekers.add(new BlockPos(cx + x, fy, cz + z));
        }

        // ---- Urns (decorated pots): dead ends first ----
        BlockState pot = b("decorated_pot");
        List<int[]> ends = new ArrayList<>(), corridors = new ArrayList<>();
        for (int i = 0; i < N; i++)
            for (int j = 0; j < N; j++) {
                if (m.kind[i][j] != 0) continue;
                if (m.openings(i, j) == 1) ends.add(new int[]{i, j});
                else corridors.add(new int[]{i, j});
            }
        Collections.shuffle(ends, diff);
        Collections.shuffle(corridors, diff);
        List<int[]> urnCells = new ArrayList<>(ends);
        urnCells.addAll(corridors);
        for (int[] u : urnCells) {
            if (lay.urns.size() >= URNS[d]) break;
            if (m.pinch[u[0]][u[1]] == 2) continue;
            int x = mid(u[0]) + (diff.nextBoolean() ? 1 : -1), z = mid(u[1]) + (diff.nextBoolean() ? 1 : -1);
            if (m.pinch[u[0]][u[1]] == 1) { x = mid(u[0]); z = mid(u[1]); if (m.openings(u[0], u[1]) > 1) continue; }
            if (placeOn(c, cx + x, fy, cz + z, pot)) lay.urns.add(new BlockPos(cx + x, fy, cz + z));
        }

        // ---- Soul lanterns here and there, pillars in the small plazas ----
        for (int i = 0; i < N; i++)
            for (int j = 0; j < N; j++) {
                if (m.kind[i][j] == 1) continue;
                if (m.kind[i][j] == 2 && m.plaza[i][j] >= 5 && (i + j) % 3 == 0) {
                    if (!c.containsKey(BlockPos.asLong(cx + mid(i), fy, cz + mid(j))))
                        for (int y = 0; y < HEIGHT; y++) set(c, cx + mid(i), fy + y, cz + mid(j), y == 2 ? soulLantern : bricks);
                    continue;
                }
                if (new Random(seed + i * 977L + j).nextInt(100) < 7) {
                    int x = cx + mid(i) + 1, z = cz + mid(j) - 1;
                    if (!c.containsKey(BlockPos.asLong(x, fy, z)) && !c.containsKey(BlockPos.asLong(x, fy + HEIGHT - 1, z)))
                        set(c, x, fy + HEIGHT - 1, z, soulLantern.setValue(BlockStateProperties.HANGING, true));
                }
            }

        // ---- Patrol spots: middles of plazas and junctions ----
        for (int i = 0; i < N; i++)
            for (int j = 0; j < N; j++) {
                if (m.kind[i][j] == 1 || m.pinch[i][j] == 2) continue;
                if (mid(j) >= R - 14 && Math.abs(mid(i)) <= 10) continue;
                if (m.kind[i][j] == 2 || m.openings(i, j) >= 3) {
                    BlockPos p = new BlockPos(cx + mid(i), fy, cz + mid(j));
                    if (!c.containsKey(p.asLong()) && !c.containsKey(p.above().asLong())) lay.patrol.add(p);
                }
            }

        lay.builder = new Builder(level, c);
        return lay;
    }

    private static long key(int i, int j) {
        return ((long) i << 16) | j;
    }

    private static long edge(int[] a, int[] b) {
        return (key(a[0], a[1]) << 32) | key(b[0], b[1]);
    }

    /** A full-height wall column (floor to the normal ceiling). */
    private static void column(Long2ObjectOpenHashMap<BlockState> c, int x, int z, int fy, Random rng, BlockState bricks, BlockState cracked, BlockState tiles) {
        for (int y = fy; y < fy + HEIGHT; y++) {
            int r = rng.nextInt(100);
            set(c, x, y, z, r < 8 ? cracked : r < 18 ? tiles : bricks);
        }
    }

    private static void setCarpet(Long2ObjectOpenHashMap<BlockState> c, int x, int fy, int z, BlockState carpet) {
        if (c.containsKey(BlockPos.asLong(x, fy, z))) return; // a wall, post, pinch or something already there
        BlockState under = c.get(BlockPos.asLong(x, fy - 1, z));
        if (under == null || under.isAir()) return;
        c.put(BlockPos.asLong(x, fy, z), carpet);
    }

    /** Puts a block on the floor if the spot and the block above are free. */
    private static boolean placeOn(Long2ObjectOpenHashMap<BlockState> c, int x, int fy, int z, BlockState s) {
        if (c.containsKey(BlockPos.asLong(x, fy, z)) || c.containsKey(BlockPos.asLong(x, fy + 1, z))) return false;
        BlockState under = c.get(BlockPos.asLong(x, fy - 1, z));
        if (under == null || under.isAir()) return false;
        c.put(BlockPos.asLong(x, fy, z), s);
        return true;
    }

    /** A 7 x 7 house on cells (i, j)..(i+1, j+1): a door into the maze, a chest at the back, candles inside. */
    private static House house(Layout lay, Maze m, Long2ObjectOpenHashMap<BlockState> c, int cx, int cz, int i, int j, int kind, Random rng,
                               BlockState bricks, BlockState cracked, BlockState polished, BlockState lantern, List<int[]> doorCells) {
        House h = new House();
        int fy = FLOOR_Y;
        int x0 = line(i) + 1, z0 = line(j) + 1; // interior x0..x0+6, z0..z0+6
        for (int x = 0; x < 7; x++) for (int z = 0; z < 7; z++) set(c, cx + x0 + x, fy - 1, cz + z0 + z, polished);
        // Door: on a side whose outside cell is a maze or plaza cell (fixed per house, by its own random).
        List<int[]> options = new ArrayList<>(); // {dir, cellI, cellJ, insideI, insideJ}
        for (int a = 0; a < 2; a++) {
            options.add(new int[]{0, i + a, j - 1, i + a, j});
            options.add(new int[]{2, i + a, j + 2, i + a, j + 1});
            options.add(new int[]{3, i - 1, j + a, i, j + a});
            options.add(new int[]{1, i + 2, j + a, i + 1, j + a});
        }
        options.removeIf(o -> !inGrid(o[1], o[2]) || m.kind[o[1]][o[2]] == 1);
        int[] door = options.isEmpty() ? new int[]{0, i, j - 1, i, j} : options.get(rng.nextInt(options.size()));
        int dir = door[0];
        int dx, dz;
        switch (dir) {
            case 0 -> { dx = mid(door[3]); dz = line(j); }
            case 2 -> { dx = mid(door[3]); dz = line(j + 2); }
            case 3 -> { dx = line(i); dz = mid(door[4]); }
            default -> { dx = line(i + 2); dz = mid(door[4]); }
        }
        h.door = new BlockPos(cx + dx, fy, cz + dz);
        if (kind == 2) {
            // Sealed: the doorway stays bricked up with cracked bricks.
            set(c, cx + dx, fy, cz + dz, cracked);
            set(c, cx + dx, fy + 1, cz + dz, cracked);
        } else {
            c.remove(BlockPos.asLong(cx + dx, fy, cz + dz));
            c.remove(BlockPos.asLong(cx + dx, fy + 1, cz + dz));
            // Keep the block in front of the door clear (a pinched corridor could fill it).
            int ox = dir == 3 ? -1 : dir == 1 ? 1 : 0, oz = dir == 0 ? -1 : dir == 2 ? 1 : 0;
            for (int y = fy; y < fy + HEIGHT; y++) c.remove(BlockPos.asLong(cx + dx + ox, y, cz + dz + oz));
        }
        doorCells.add(new int[]{door[1], door[2]});
        // The chest against the wall opposite the door, facing it.
        int bx = cx + x0 + 3, bz = cz + z0 + 3;
        Direction facing;
        switch (dir) {
            case 0 -> { bz = cz + z0 + 6; facing = Direction.NORTH; }
            case 2 -> { bz = cz + z0; facing = Direction.SOUTH; }
            case 3 -> { bx = cx + x0 + 6; facing = Direction.WEST; }
            default -> { bx = cx + x0; facing = Direction.EAST; }
        }
        h.chest = new BlockPos(bx, fy, bz);
        set(c, bx, fy, bz, Blocks.CHEST.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, facing));
        // Candles: beside the chest and in the two corners on the chest's side. Lit = real house.
        boolean lit = kind == 0;
        int sideX = facing.getAxis() == Direction.Axis.Z ? 1 : 0, sideZ = 1 - sideX;
        int[][] spots = {{bx + sideX * 2, bz + sideZ * 2}, {bx - sideX * 2, bz - sideZ * 2}, {bx + sideX, bz + sideZ}};
        for (int[] s : spots) {
            BlockPos p = new BlockPos(s[0], fy, s[1]);
            set(c, s[0], fy, s[1], net.minecraft.core.registries.BuiltInRegistries.BLOCK.getValue(Identifier.withDefaultNamespace("candle")).defaultBlockState()
                    .setValue(BlockStateProperties.LIT, lit).setValue(BlockStateProperties.CANDLES, 1 + rng.nextInt(4)));
            h.candles.add(p);
        }
        // A soul lantern on the other side of the room (the same in every house: the candles are the tell).
        int ox = bx + facing.getStepX() * 5, oz = bz + facing.getStepZ() * 5;
        int lx = ox + sideX * 2, lz = oz + sideZ * 2;
        if (!(lx == cx + dx && lz == cz + dz)) set(c, lx, fy, lz, lantern);
        return h;
    }

    private static void set(Long2ObjectOpenHashMap<BlockState> c, int x, int y, int z, BlockState s) {
        c.put(BlockPos.asLong(x, y, z), s);
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
                // Solid blocks first, then things that sit on them (carpet, candles, lanterns, sensors...).
                List<Long> list = byChunk.get(ck);
                list.sort(Comparator.comparingInt(p -> BlockPos.getY(p)));
                for (long p : list) {
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
