package com.aidan.skyserver.lobby;

import java.util.*;
import java.util.function.Consumer;

import static com.aidan.skyserver.goldwars.MapCanvas.noise;
import static com.aidan.skyserver.lobby.LobbyDesign.G;

/**
 * The lobby activities outside the castle walls (plain Java, previewable offline):
 *
 *  - North-west: the Hedge Maze and the Spleef Pit.
 *  - North-east: the Duel Arena (three pits) and the Archery Range.
 *  - South-east: the Fishing Pond (docks and a hut on the big lake).
 *  - South-west: the Elytra Tower and its ring course.
 *
 * The game code (Activities, Duels) uses the positions defined here.
 */
public final class ActivityDesign {
    private ActivityDesign() {}

    public record Spot(double x, double y, double z, float yaw) {}

    interface PartAdder {
        void add(int x1, int z1, int x2, int z2, Consumer<LobbyCanvas> paint);
    }

    // =====================================================================
    // Areas (also keep trees out)
    // =====================================================================

    // Hedge maze: 21 x 21 cells, 3 blocks per cell (2-wide paths, 1-wide hedges) = 64 x 64.
    public static final int MAZE_CELLS = 21, MAZE_PITCH = 3, MAZE_X = -282, MAZE_Z = -217, MAZE_H = 4;
    public static final int MAZE_SIZE = MAZE_CELLS * MAZE_PITCH + 1; // 64
    // Spleef pit.
    public static final int SPLEEF_X = -215, SPLEEF_Z = -295, SPLEEF_R = 20, SPLEEF_FLOOR = G + 8;
    /** TNT Run has 4 floors (the top one is the Spleef floor); each is this far below the last. */
    public static final int TNT_FLOORS = 4, TNT_GAP = 5;

    /** y of TNT Run floor i (0 = top). */
    public static int tntFloorY(int i) {
        return SPLEEF_FLOOR - i * TNT_GAP;
    }

    /** Which TNT Run floor someone at height y is standing on / above (0 = top). */
    public static int tntFloorAt(double y) {
        for (int i = 0; i < TNT_FLOORS; i++) if (y >= tntFloorY(i) + 0.5) return i;
        return TNT_FLOORS;
    }
    // Duels: three pits in a row.
    public static final int[][] DUEL_PITS = {{190, -200}, {222, -200}, {254, -200}};
    public static final int DUEL_HALF = 10; // interior is 21 x 21
    // Archery.
    public static final int ARCH_X1 = 212, ARCH_X2 = 258, ARCH_LINE_Z = -262;
    /** Moving targets: sliders at 25 blocks, pop-ups at 40, the bonus target at 32 (high up). */
    public static final int ARCH_SLIDE_Z = ARCH_LINE_Z - 25, ARCH_POP_Z = ARCH_LINE_Z - 40, ARCH_BONUS_Z = ARCH_LINE_Z - 32;
    public static final int ARCH_SLIDE_Y = G + 4, ARCH_BONUS_Y = G + 12;
    // Fishing pond (the lobby's big south-east lake).
    public static final int POND_X = 200, POND_Z = 220, POND_R = 38;
    // Elytra tower.
    public static final int TOWER_X = -160, TOWER_Z = 170, TOWER_TOP = G + 60;

    public static boolean inArea(int x, int z) {
        if (x >= MAZE_X - 8 && x <= MAZE_X + MAZE_SIZE + 8 && z >= MAZE_Z - 12 && z <= MAZE_Z + MAZE_SIZE + 8) return true;
        if (Math.hypot(x - SPLEEF_X, z - SPLEEF_Z) < SPLEEF_R + 12) return true;
        if (x >= 170 && x <= 274 && z >= -224 && z <= -168) return true; // duels
        if (x >= ARCH_X1 - 8 && x <= ARCH_X2 + 8 && z >= -322 && z <= -248) return true; // archery
        if (Math.hypot(x - POND_X, z - POND_Z) < POND_R + 22) return true;
        if (Math.hypot(x - TOWER_X, z - TOWER_Z) < 16) return true;
        if (x >= -200 && x <= -170 && z >= 320 && z <= 345) return true; // elytra landing pad
        return false;
    }

    // =====================================================================
    // What the game code needs
    // =====================================================================

    /** Box of blocks (inclusive). */
    public record Area(int x1, int y1, int z1, int x2, int y2, int z2) {
        public boolean contains(double x, double y, double z) {
            return x >= x1 && x < x2 + 1 && y >= y1 && y < y2 + 1 && z >= z1 && z < z2 + 1;
        }
    }

    // ---- Maze ----
    public static final Spot MAZE_SPOT = new Spot(MAZE_X + MAZE_SIZE / 2.0 + 0.5, G + 1, MAZE_Z + MAZE_SIZE + 5.5, 180f);
    /** Stepping into the entrance starts the clock; the middle is the finish. */
    public static final Area MAZE_START;
    public static final Area MAZE_FINISH;

    // ---- Spleef ----
    public static final Spot SPLEEF_STANDS = new Spot(SPLEEF_X + 0.5, G + 11, SPLEEF_Z + SPLEEF_R + 4.5, 180f);
    /** Below this you're out of the round. */
    public static final int SPLEEF_OUT_Y = SPLEEF_FLOOR - 2;
    /** TNT Run: out once you drop below the bottom floor. */
    public static final int TNT_OUT_Y = SPLEEF_FLOOR - (TNT_FLOORS - 1) * TNT_GAP - 2;

    // ---- Duels ----
    public static final Spot DUEL_SPOT = new Spot(222.5, G + 1, -178.5, 180f);

    public static Area duelInterior(int pit) {
        int[] c = DUEL_PITS[pit];
        return new Area(c[0] - DUEL_HALF, G + 1, c[1] - DUEL_HALF, c[0] + DUEL_HALF, G + 12, c[1] + DUEL_HALF);
    }

    public static Spot duelSpawn(int pit, int side) {
        int[] c = DUEL_PITS[pit];
        return side == 0 ? new Spot(c[0] + 0.5, G + 1, c[1] - DUEL_HALF + 2.5, 0f) : new Spot(c[0] + 0.5, G + 1, c[1] + DUEL_HALF - 1.5, 180f);
    }

    // ---- Archery ----
    public record Target(Area face, int points, int bx, int by, int bz) {}

    public static final List<Target> TARGETS = new ArrayList<>();
    /** Stand here (on the shooting line) to start a round. */
    public static final Area ARCHERY_LINE = new Area(ARCH_X1, G + 1, ARCH_LINE_Z - 2, ARCH_X2, G + 4, ARCH_LINE_Z);
    public static final Area ARCHERY_RANGE = new Area(ARCH_X1 - 6, G - 2, -325, ARCH_X2 + 6, G + 30, ARCH_LINE_Z + 4);
    public static final Spot ARCHERY_SPOT = new Spot((ARCH_X1 + ARCH_X2) / 2.0 + 0.5, G + 1, ARCH_LINE_Z + 4.5, 180f);

    // ---- Fishing ----
    public static final Spot POND_SPOT = new Spot(POND_X + 0.5, G + 1, POND_Z - POND_R - 9.5, 180f);

    public static boolean atPond(double x, double z) {
        return Math.hypot(x - POND_X, z - POND_Z) < POND_R + 12;
    }

    // ---- Elytra ----
    /** One ring: a vertical circle. alongX = you fly through it along the x axis. */
    public record Ring(int cx, int cy, int cz, boolean alongX, int r) {}

    public static final List<Ring> RINGS = new ArrayList<>();
    public static final Area LAUNCH_PAD = new Area(TOWER_X - 2, TOWER_TOP + 1, TOWER_Z - 2, TOWER_X + 2, TOWER_TOP + 3, TOWER_Z + 2);
    public static final Spot LAUNCH_SPOT = new Spot(TOWER_X + 0.5, TOWER_TOP + 1, TOWER_Z + 0.5, 90f);
    public static final Spot TOWER_BASE = new Spot(TOWER_X + 0.5, G + 1, TOWER_Z + 9.5, 180f);
    /** The glowing pad inside the tower: step on it to ride to the top. */
    public static final Area TOWER_LIFT = new Area(TOWER_X - 1, G + 1, TOWER_Z - 1, TOWER_X + 1, G + 2, TOWER_Z + 1);
    public static final Area LANDING_PAD = new Area(-192, G + 1, 324, -178, G + 3, 338);

    static {
        int mid = MAZE_CELLS / 2;
        int sx = MAZE_X + mid * MAZE_PITCH + 1, sz = MAZE_Z + (MAZE_CELLS - 1) * MAZE_PITCH + 1;
        MAZE_START = new Area(sx, G + 1, sz, sx + 1, G + 3, sz + 2);
        int fx = MAZE_X + mid * MAZE_PITCH + 1, fz = MAZE_Z + mid * MAZE_PITCH + 1;
        MAZE_FINISH = new Area(fx, G + 1, fz, fx + 1, G + 3, fz + 1);

        // Archery: five lanes; each has a fixed near target (15 blocks, 1 point). The rest move (see
        // ARCH_SLIDE_Z / ARCH_POP_Z / ARCH_BONUS_Z): sliders, pop-ups and a fast bonus target.
        for (int lane = 0; lane < 5; lane++) {
            int lx = ARCH_X1 + 5 + lane * 9;
            addTarget(lx - 2, G + 2, ARCH_LINE_Z - 15, 1);
        }

        // Elytra rings: west off the tower, a long left turn to the south, then east to the pad.
        int[][] r = {
                {-182, 157, 170, 1, 5}, {-205, 154, 171, 1, 5}, {-229, 151, 176, 1, 4}, {-251, 148, 187, 1, 4},
                {-265, 145, 205, 0, 4}, {-271, 142, 227, 0, 4}, {-268, 139, 249, 0, 4}, {-256, 136, 268, 0, 3},
                {-236, 133, 282, 1, 4}, {-214, 130, 292, 1, 3}, {-196, 126, 304, 1, 4}, {-186, 121, 318, 0, 3}};
        for (int[] a : r) RINGS.add(new Ring(a[0], a[1], a[2], a[3] == 1, a[4]));
    }

    private static void addTarget(int cx, int y, int z, int points) {
        TARGETS.add(new Target(new Area(cx - 1, y, z, cx + 1, y + 2, z), points, cx, y + 1, z));
    }

    // =====================================================================
    // Painting
    // =====================================================================

    static void layout(PartAdder part) {
        // Clear what an older lobby build left in these areas (trees, old courses).
        part.add(MAZE_X - 8, MAZE_Z - 12, MAZE_X + MAZE_SIZE + 8, MAZE_Z + MAZE_SIZE + 8, ActivityDesign::clearArea);
        part.add(SPLEEF_X - 31, SPLEEF_Z - 31, SPLEEF_X + 31, SPLEEF_Z + 45, ActivityDesign::clearArea);
        part.add(170, -224, 274, -168, ActivityDesign::clearArea);
        part.add(ARCH_X1 - 8, -322, ARCH_X2 + 8, -248, ActivityDesign::clearArea);
        part.add(TOWER_X - 15, TOWER_Z - 15, TOWER_X + 15, TOWER_Z + 15, ActivityDesign::clearArea);
        part.add(-200, 320, -170, 345, ActivityDesign::clearArea);

        part.add(MAZE_X - 6, MAZE_Z - 10, MAZE_X + MAZE_SIZE + 6, MAZE_Z + MAZE_SIZE + 8, ActivityDesign::maze);
        part.add(SPLEEF_X - 30, SPLEEF_Z - 30, SPLEEF_X + 30, SPLEEF_Z + 45, ActivityDesign::spleef);
        for (int i = 0; i < DUEL_PITS.length; i++) {
            final int pit = i;
            int[] c = DUEL_PITS[i];
            part.add(c[0] - DUEL_HALF - 3, c[1] - DUEL_HALF - 3, c[0] + DUEL_HALF + 3, c[1] + DUEL_HALF + 3, cv -> duelPit(cv, pit));
        }
        part.add(170, -186, 274, -170, ActivityDesign::duelPlaza);
        part.add(ARCH_X1 - 6, -322, ARCH_X2 + 6, -250, ActivityDesign::archery);
        part.add(POND_X - POND_R - 20, POND_Z - POND_R - 20, POND_X + POND_R + 20, POND_Z + POND_R + 20, ActivityDesign::pond);
        part.add(TOWER_X - 14, TOWER_Z - 14, TOWER_X + 14, TOWER_Z + 14, ActivityDesign::tower);
        for (Ring ring : RINGS) {
            final Ring rr = ring;
            part.add(ring.cx() - ring.r() - 1, ring.cz() - ring.r() - 1, ring.cx() + ring.r() + 1, ring.cz() + ring.r() + 1, cv -> ring(cv, rr));
        }
        part.add(-200, 320, -170, 345, ActivityDesign::landingPad);

        // Signs (floating text) for each activity.
        LobbyDesign.HOLOS.add(new LobbyDesign.Holo(MAZE_SPOT.x(), G + 5.5, MAZE_Z + MAZE_SIZE + 2.5, List.of("§2§lHEDGE MAZE", "§7Find the fountain in the middle", "§7Walk in to start the clock")));
        LobbyDesign.HOLOS.add(new LobbyDesign.Holo(SPLEEF_X + 0.5, SPLEEF_FLOOR + 14, SPLEEF_Z + 0.5, List.of("§f§lSPLEEF PIT", "§7Spleef and TNT Run rounds", "§7Join with §e/spleef §7or the compass")));
        LobbyDesign.HOLOS.add(new LobbyDesign.Holo(DUEL_SPOT.x(), G + 5.5, DUEL_SPOT.z() - 4, List.of("§c§lDUEL ARENA", "§e/duel <player> [bet]", "§7Winner takes the whole bet")));
        LobbyDesign.HOLOS.add(new LobbyDesign.Holo(ARCHERY_SPOT.x(), G + 6.5, ARCH_LINE_Z + 1.5, List.of("§6§lARCHERY RANGE", "§7Step onto the shooting line to start", "§7Moving targets 2, pop-ups 3, §egold bonus 5", "§7Centre block = double. Goals: 40 / 70 / 100")));
        LobbyDesign.HOLOS.add(new LobbyDesign.Holo(POND_SPOT.x(), G + 4.5, POND_SPOT.z() - 2, List.of("§b§lFISHING POND", "§7You get a rod near the water", "§7Every catch pays - any catch can be a key!")));
        LobbyDesign.HOLOS.add(new LobbyDesign.Holo(TOWER_BASE.x(), G + 5.5, TOWER_BASE.z() + 1, List.of("§d§lELYTRA RINGS", "§7Step on the pad in the tower to ride up", "§7Fly through every ring, land on the pad")));
        LobbyDesign.HOLOS.add(new LobbyDesign.Holo(-184.5, G + 4.5, 331.5, List.of("§d§lLANDING PAD", "§7Finish of the Elytra Rings")));
    }

    private static void clearArea(LobbyCanvas c) {
        for (int x = c.minX; x <= c.maxX; x++)
            for (int z = c.minZ; z <= c.maxZ; z++) {
                if (!inArea(x, z) || LobbyDesign.edgeDepth(x, z) < 0) continue;
                for (int y = G + 1; y <= G + 40; y++) c.set(x, y, z, null);
            }
    }

    // ---- Hedge maze ----

    private static boolean[][] mazeOpen; // which hedge cells are open (path), in block units

    static boolean[][] mazeGrid() {
        if (mazeOpen != null) return mazeOpen;
        int n = MAZE_SIZE;
        boolean[][] open = new boolean[n][n];
        Random rng = new Random(2026);
        boolean[][] seen = new boolean[MAZE_CELLS][MAZE_CELLS];
        Deque<int[]> stack = new ArrayDeque<>();
        int mid = MAZE_CELLS / 2;
        stack.push(new int[]{mid, mid});
        seen[mid][mid] = true;
        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        while (!stack.isEmpty()) {
            int[] cur = stack.peek();
            List<int[]> next = new ArrayList<>();
            for (int[] d : dirs) {
                int nx = cur[0] + d[0], nz = cur[1] + d[1];
                if (nx >= 0 && nz >= 0 && nx < MAZE_CELLS && nz < MAZE_CELLS && !seen[nx][nz]) next.add(new int[]{nx, nz, d[0], d[1]});
            }
            if (next.isEmpty()) {
                stack.pop();
                continue;
            }
            int[] pick = next.get(rng.nextInt(next.size()));
            seen[pick[0]][pick[1]] = true;
            // Open the wall between the two cells.
            int wx = cur[0] * MAZE_PITCH + 1 + (pick[2] > 0 ? 2 : pick[2] < 0 ? -1 : 0);
            int wz = cur[1] * MAZE_PITCH + 1 + (pick[3] > 0 ? 2 : pick[3] < 0 ? -1 : 0);
            for (int t = 0; t < 2; t++) {
                int ox = pick[2] != 0 ? wx : wx + t, oz = pick[3] != 0 ? wz : wz + t;
                open[ox][oz] = true;
            }
            stack.push(new int[]{pick[0], pick[1]});
        }
        // A few extra openings so there's more than one way (loops make it a real maze, not a corridor).
        for (int i = 0; i < 30; i++) {
            int cx = rng.nextInt(MAZE_CELLS - 1), cz = rng.nextInt(MAZE_CELLS);
            int wx = cx * MAZE_PITCH + 3, wz = cz * MAZE_PITCH + 1;
            if (rng.nextBoolean()) {
                open[wx][wz] = open[wx][wz + 1] = true;
            } else {
                open[wz][wx] = open[wz + 1][wx] = true;
            }
        }
        for (int cx = 0; cx < MAZE_CELLS; cx++)
            for (int cz = 0; cz < MAZE_CELLS; cz++)
                for (int a = 0; a < 2; a++)
                    for (int b = 0; b < 2; b++) open[cx * MAZE_PITCH + 1 + a][cz * MAZE_PITCH + 1 + b] = true;
        // Entrance in the middle of the south side.
        open[mid * MAZE_PITCH + 1][n - 1] = open[mid * MAZE_PITCH + 2][n - 1] = true;
        mazeOpen = open;
        return open;
    }

    private static void maze(LobbyCanvas c) {
        boolean[][] open = mazeGrid();
        int n = MAZE_SIZE;
        for (int i = 0; i < n; i++)
            for (int j = 0; j < n; j++) {
                int x = MAZE_X + i, z = MAZE_Z + j;
                if (!c.in(x, z)) continue;
                if (open[i][j]) {
                    c.set(x, G, z, noise(x, z, 501) < 0.3 ? "moss_block" : "grass_block");
                    continue;
                }
                String leaf = noise(x, z, 502) < 0.15 ? "flowering_azalea_leaves[persistent=true]" : noise(x, z, 503) < 0.3 ? "azalea_leaves[persistent=true]" : "oak_leaves[persistent=true]";
                c.set(x, G, z, "dirt");
                for (int y = G + 1; y <= G + MAZE_H; y++) c.set(x, y, z, leaf);
            }
        // The fountain in the middle (the finish).
        int mx = MAZE_FINISH.x1(), mz = MAZE_FINISH.z1();
        c.set(mx, G, mz, "gold_block");
        c.set(mx + 1, G, mz, "gold_block");
        c.set(mx, G, mz + 1, "gold_block");
        c.set(mx + 1, G, mz + 1, "gold_block");
        // Lanterns on posts at the entrance.
        int ex = MAZE_START.x1(), ez = MAZE_Z + n;
        for (int s : new int[]{-1, 2}) {
            c.set(ex + s, G + 1, ez, "spruce_fence");
            c.set(ex + s, G + 2, ez, "spruce_fence");
            c.set(ex + s, G + 3, ez, "lantern");
        }
        // Path from the entrance to the main east-west path is open meadow; a gravel path in front.
        for (int z = ez; z <= ez + 7; z++)
            for (int x = ex - 1; x <= ex + 2; x++) c.set(x, G, z, "dirt_path");
    }

    // ---- Spleef pit ----

    private static void spleef(LobbyCanvas c) {
        int cx = SPLEEF_X, cz = SPLEEF_Z;
        for (int x = cx - 30; x <= cx + 30; x++)
            for (int z = cz - 30; z <= cz + 30; z++) {
                if (!c.in(x, z)) continue;
                double d = Math.hypot(x - cx, z - cz);
                int bottom = TNT_OUT_Y - 3;
                if (d <= SPLEEF_R + 0.5) {
                    // A deep shaft: the top (Spleef) floor, then three more TNT Run floors, then water.
                    c.set(x, bottom - 1, z, "packed_ice");
                    c.set(x, bottom, z, "water");
                    for (int y = bottom + 1; y <= SPLEEF_FLOOR + 4; y++) c.set(x, y, z, null);
                    c.set(x, SPLEEF_FLOOR, z, "snow_block");
                    for (int i = 1; i < TNT_FLOORS; i++) c.set(x, tntFloorY(i), z, "tnt");
                } else if (d <= SPLEEF_R + 2.5) {
                    // Arena wall: stone down the shaft (lit at each floor), then glass so you can't walk off.
                    boolean inner = d <= SPLEEF_R + 1.5;
                    for (int y = bottom - 1; y <= SPLEEF_FLOOR + 3; y++) {
                        String st = y > SPLEEF_FLOOR ? "light_blue_stained_glass" : inner ? "packed_ice" : "polished_andesite";
                        for (int i = 1; i < TNT_FLOORS; i++)
                            if (inner && y == tntFloorY(i) + 2 && Math.round(Math.atan2(z - cz, x - cx) * 8 / Math.PI) % 2 == 0) st = "sea_lantern";
                        c.set(x, y, z, st);
                    }
                    c.set(x, SPLEEF_FLOOR + 4, z, "polished_diorite_slab[type=bottom]");
                } else if (d <= SPLEEF_R + 7.5) {
                    // Stands: rising rows of seats.
                    int row = (int) (d - (SPLEEF_R + 2.5));
                    int top = SPLEEF_FLOOR + 1 + row / 2 * 1 + row % 2;
                    for (int y = G; y <= top; y++) c.set(x, y, z, y == top ? "smooth_quartz" : "stone_bricks");
                    for (int y = top + 1; y <= top + 3; y++) c.set(x, y, z, null);
                } else if (d <= SPLEEF_R + 8.5) {
                    int top = SPLEEF_FLOOR + 5;
                    for (int y = G; y <= top + 1; y++) c.set(x, y, z, y == top + 1 ? "stone_brick_wall" : "stone_bricks");
                }
            }
        // Stairs up to the stands from the south.
        int steps = SPLEEF_FLOOR + 5 - G;
        for (int i = 0; i < steps; i++) {
            int z = cz + SPLEEF_R + 9 + (steps - 1 - i);
            for (int x = cx - 1; x <= cx + 1; x++) {
                for (int y = G + 1; y < G + 1 + i; y++) c.set(x, y, z, "stone_bricks");
                c.set(x, G + 1 + i, z, "stone_brick_stairs[facing=north]");
                for (int y = G + 2 + i; y <= G + 5 + i; y++) c.set(x, y, z, null);
            }
        }
        // Gap in the outer wall where the stairs arrive.
        for (int x = cx - 1; x <= cx + 1; x++)
            for (int y = SPLEEF_FLOOR + 5; y <= SPLEEF_FLOOR + 9; y++) c.set(x, y, cz + SPLEEF_R + 8, null);
        // Lanterns around the top of the glass.
        for (int a = 0; a < 16; a++) {
            double ang = a * Math.PI / 8;
            int x = cx + (int) Math.round(Math.cos(ang) * (SPLEEF_R + 2)), z = cz + (int) Math.round(Math.sin(ang) * (SPLEEF_R + 2));
            c.set(x, SPLEEF_FLOOR + 5, z, "lantern");
        }
    }

    // ---- Duel pits ----

    private static void duelPit(LobbyCanvas c, int pit) {
        int[] ctr = DUEL_PITS[pit];
        int h = DUEL_HALF;
        String[] floors = {"smooth_sandstone", "smooth_stone", "red_sandstone"};
        for (int x = ctr[0] - h - 2; x <= ctr[0] + h + 2; x++)
            for (int z = ctr[1] - h - 2; z <= ctr[1] + h + 2; z++) {
                int dx = Math.abs(x - ctr[0]), dz = Math.abs(z - ctr[1]);
                if (dx <= h && dz <= h) {
                    c.set(x, G, z, (dx + dz) % 7 == 0 ? "chiseled_sandstone" : floors[pit]);
                    for (int y = G + 1; y <= G + 12; y++) c.set(x, y, z, null);
                } else {
                    // Walls: a stone base, then glass (so people outside can watch), capped with stone.
                    for (int y = G; y <= G + 9; y++) c.set(x, y, z, y <= G + 1 ? "stone_bricks" : y == G + 9 ? "stone_brick_slab[type=bottom]" : "glass");
                    if ((dx == h + 2 || dz == h + 2) && (x + z) % 4 == 0) c.set(x, G + 9, z, "stone_bricks");
                }
            }
        // A little cover in the middle: two pillars and some half-walls.
        int x0 = ctr[0], z0 = ctr[1];
        for (int s : new int[]{-1, 1}) {
            for (int y = G + 1; y <= G + 3; y++) c.set(x0 + 4 * s, y, z0, "chiseled_stone_bricks");
            c.set(x0 - 3 * s, G + 1, z0 + 4 * s, "stone_brick_slab[type=bottom]");
            c.set(x0 - 2 * s, G + 1, z0 + 4 * s, "stone_brick_slab[type=bottom]");
        }
        // Lights in the corners and a number over the gate.
        for (int sx : new int[]{-h, h})
            for (int sz : new int[]{-h, h}) c.set(ctr[0] + sx, G + 1, ctr[1] + sz, "sea_lantern");
    }

    private static void duelPlaza(LobbyCanvas c) {
        for (int x = 172; x <= 272; x++)
            for (int z = -186; z <= -172; z++) {
                c.set(x, G, z, (x + z) % 2 == 0 ? "polished_andesite" : "andesite");
                for (int y = G + 1; y <= G + 4; y++) c.set(x, y, z, null);
            }
        for (int x = 174; x <= 270; x += 8) {
            c.set(x, G + 1, -173, "stone_brick_wall");
            c.set(x, G + 2, -173, "lantern");
        }
        // Benches.
        for (int x = 180; x <= 264; x += 12) {
            c.set(x, G + 1, -184, "spruce_stairs[facing=south]");
            c.set(x + 1, G + 1, -184, "spruce_stairs[facing=south]");
        }
    }

    // ---- Archery range ----

    private static void archery(LobbyCanvas c) {
        // Grass range with a stone shooting line and a roof over it.
        for (int x = ARCH_X1 - 4; x <= ARCH_X2 + 4; x++)
            for (int z = -320; z <= ARCH_LINE_Z + 3; z++) {
                c.set(x, G, z, z > ARCH_LINE_Z - 3 ? "polished_andesite" : noise(x, z, 601) < 0.25 ? "moss_block" : "grass_block");
                for (int y = G + 1; y <= G + 14; y++) c.set(x, y, z, null);
            }
        // The shooting line.
        for (int x = ARCH_X1; x <= ARCH_X2; x++) {
            c.set(x, G, ARCH_LINE_Z - 3, "red_concrete");
            c.set(x, G + 1, ARCH_LINE_Z - 3, (x - ARCH_X1) % 9 == 0 ? "spruce_fence" : null);
        }
        // Roof on posts.
        for (int x = ARCH_X1 - 2; x <= ARCH_X2 + 2; x++)
            for (int z = ARCH_LINE_Z - 2; z <= ARCH_LINE_Z + 3; z++) c.set(x, G + 5, z, "spruce_slab[type=bottom]");
        for (int x = ARCH_X1 - 2; x <= ARCH_X2 + 2; x += 8)
            for (int z : new int[]{ARCH_LINE_Z - 2, ARCH_LINE_Z + 3}) {
                for (int y = G + 1; y <= G + 4; y++) c.set(x, y, z, "spruce_log[axis=y]");
                c.set(x, G + 4, z + (z == ARCH_LINE_Z - 2 ? 1 : -1), "lantern[hanging=true]");
            }
        // Targets on hay posts, with a backstop wall behind the far ones.
        for (Target t : TARGETS) {
            Area f = t.face();
            for (int x = f.x1(); x <= f.x2(); x++) {
                for (int y = G + 1; y < f.y1(); y++) c.set(x, y, f.z1() - 1, "hay_block[axis=y]");
                for (int y = f.y1(); y <= f.y2(); y++) {
                    c.set(x, y, f.z1(), x == t.bx() && y == t.by() ? "target" : "white_wool");
                    c.set(x, y, f.z1() - 1, "hay_block[axis=y]");
                }
            }
            // Coloured rings: red middle row edges.
            c.set(t.bx() - 1, t.by(), f.z1(), "red_wool");
            c.set(t.bx() + 1, t.by(), f.z1(), "red_wool");
            c.set(t.bx(), t.by() - 1, f.z1(), "red_wool");
            c.set(t.bx(), t.by() + 1, f.z1(), "red_wool");
        }
        for (int x = ARCH_X1 - 4; x <= ARCH_X2 + 4; x++)
            for (int y = G + 1; y <= G + 13; y++) c.set(x, y, -318, y > G + 11 ? "spruce_planks" : "dark_oak_planks");
        // Rails the sliders run along (one per lane, under the targets) and posts for the bonus line.
        for (int lane = 0; lane < 5; lane++) {
            int lx = ARCH_X1 + 5 + lane * 9;
            for (int x = lx - 3; x <= lx + 3; x++) {
                c.set(x, ARCH_SLIDE_Y - 1, ARCH_SLIDE_Z - 1, "dark_oak_slab[type=top]");
                c.set(x, ARCH_SLIDE_Y - 2, ARCH_SLIDE_Z - 1, null);
            }
            for (int y = G + 1; y <= ARCH_SLIDE_Y - 1; y++) c.set(lx - 4, y, ARCH_SLIDE_Z - 1, "dark_oak_fence");
        }
        for (int y = G + 1; y <= ARCH_BONUS_Y - 1; y++) {
            c.set(ARCH_X1 - 3, y, ARCH_BONUS_Z, "dark_oak_fence");
            c.set(ARCH_X2 + 3, y, ARCH_BONUS_Z, "dark_oak_fence");
        }
        for (int x = ARCH_X1 - 3; x <= ARCH_X2 + 3; x++) c.set(x, ARCH_BONUS_Y + 1, ARCH_BONUS_Z, "chain[axis=x]");
        // Side fences.
        for (int z = -317; z <= ARCH_LINE_Z - 4; z++)
            for (int x : new int[]{ARCH_X1 - 4, ARCH_X2 + 4}) c.set(x, G + 1, z, "spruce_fence");
    }

    // ---- Fishing pond ----

    private static void pond(LobbyCanvas c) {
        int cx = POND_X, cz = POND_Z;
        // A dock out into the water from the north shore, and one from the west.
        for (int z = cz - POND_R - 6; z <= cz - POND_R + 14; z++)
            for (int x = cx - 1; x <= cx + 1; x++) {
                c.set(x, G + 1, z, "spruce_planks");
                for (int y = G + 2; y <= G + 4; y++) c.set(x, y, z, null);
                if (Math.abs(x - cx) == 1 && (z - cz) % 4 == 0) {
                    c.set(x, G, z, "spruce_log[axis=y]");
                    c.set(x, G + 2, z, "spruce_fence");
                }
            }
        for (int x = cx - POND_R - 6; x <= cx - POND_R + 12; x++)
            for (int z = cz - 1; z <= cz + 1; z++) {
                c.set(x, G + 1, z, "spruce_planks");
                for (int y = G + 2; y <= G + 4; y++) c.set(x, y, z, null);
                if (Math.abs(z - cz) == 1 && (x - cx) % 4 == 0) {
                    c.set(x, G, z, "spruce_log[axis=y]");
                    c.set(x, G + 2, z, "spruce_fence");
                }
            }
        // T-shaped ends with lanterns.
        for (int x = cx - 4; x <= cx + 4; x++) c.set(x, G + 1, cz - POND_R + 14, "spruce_planks");
        c.set(cx - 4, G + 2, cz - POND_R + 14, "lantern");
        c.set(cx + 4, G + 2, cz - POND_R + 14, "lantern");
        // A small fishing hut on the north shore.
        int hx = cx + 8, hz = cz - POND_R - 12;
        for (int x = hx - 3; x <= hx + 3; x++)
            for (int z = hz - 3; z <= hz + 3; z++) {
                boolean edge = Math.abs(x - hx) == 3 || Math.abs(z - hz) == 3;
                c.set(x, G, z, "spruce_planks");
                for (int y = G + 1; y <= G + 4; y++) c.set(x, y, z, edge ? (Math.abs(x - hx) == 3 && Math.abs(z - hz) == 3 ? "stripped_spruce_log[axis=y]" : y == G + 2 ? "glass_pane" : "spruce_planks") : null);
                c.set(x, G + 5, z, "spruce_slab[type=bottom]");
            }
        c.set(hx, G + 1, hz + 3, null);
        c.set(hx, G + 2, hz + 3, null);
        c.set(hx - 2, G + 1, hz - 2, "barrel[facing=up]");
        c.set(hx + 2, G + 1, hz - 2, "barrel[facing=up]");
        c.set(hx, G + 4, hz, "lantern[hanging=true]");
    }

    // ---- Elytra tower, rings and landing pad ----

    private static void tower(LobbyCanvas c) {
        int cx = TOWER_X, cz = TOWER_Z;
        // A round tower, radius 6. Step on the glowing pad inside to ride up to the top.
        for (int x = cx - 7; x <= cx + 7; x++)
            for (int z = cz - 7; z <= cz + 7; z++) {
                double d = Math.hypot(x - cx, z - cz);
                if (d > 6.5) continue;
                for (int y = G + 1; y <= TOWER_TOP; y++) {
                    boolean wall = d > 5.0;
                    if (wall) c.set(x, y, z, (y - G) % 10 == 5 ? "purple_stained_glass" : (y % 9 == 0 ? "purpur_pillar[axis=y]" : "purpur_block"));
                    else c.set(x, y, z, null);
                }
                c.set(x, G, z, d <= 1.5 ? "crying_obsidian" : "purpur_block");
                c.set(x, TOWER_TOP, z, d <= 2.5 ? "gold_block" : "purpur_block");
            }
        // Light inside, and the door on the south side.
        for (int y = G + 4; y < TOWER_TOP; y += 6) {
            c.set(cx + 4, y, cz, "sea_lantern");
            c.set(cx - 4, y, cz, "sea_lantern");
        }
        for (int y = G + 1; y <= G + 3; y++)
            for (int x = cx - 1; x <= cx + 1; x++)
                for (int z = cz + 4; z <= cz + 7; z++) c.set(x, y, z, null);
        // Glass rail round the top, open on the west side (that's where you jump).
        for (int x = cx - 7; x <= cx + 7; x++)
            for (int z = cz - 7; z <= cz + 7; z++) {
                double d = Math.hypot(x - cx, z - cz);
                if (d > 5.5 && d <= 6.5 && x > cx - 4) c.set(x, TOWER_TOP + 1, z, "purple_stained_glass_pane");
            }
        c.set(cx + 3, TOWER_TOP + 1, cz + 3, "end_rod[facing=up]");
        c.set(cx + 3, TOWER_TOP + 1, cz - 3, "end_rod[facing=up]");
    }

    private static void ring(LobbyCanvas c, Ring r) {
        boolean last = r.equals(RINGS.get(RINGS.size() - 1)), first = r.equals(RINGS.get(0));
        String block = first ? "lime_concrete" : last ? "gold_block" : "magenta_concrete";
        for (int a = -r.r() - 1; a <= r.r() + 1; a++)
            for (int b = -r.r() - 1; b <= r.r() + 1; b++) {
                double d = Math.hypot(a, b);
                if (d < r.r() + 0.5 || d > r.r() + 1.4) continue;
                int x = r.alongX() ? r.cx() : r.cx() + a, z = r.alongX() ? r.cz() + a : r.cz();
                c.set(x, r.cy() + b, z, (Math.abs(a) <= 1 && b > 0) ? "sea_lantern" : block);
            }
    }

    private static void landingPad(LobbyCanvas c) {
        Area p = LANDING_PAD;
        for (int x = p.x1(); x <= p.x2(); x++)
            for (int z = p.z1(); z <= p.z2(); z++) {
                boolean edge = x == p.x1() || x == p.x2() || z == p.z1() || z == p.z2();
                c.set(x, G, z, edge ? "purpur_pillar[axis=y]" : (x + z) % 2 == 0 ? "magenta_glazed_terracotta" : "purpur_block");
                for (int y = G + 1; y <= G + 6; y++) c.set(x, y, z, null);
            }
        c.set(p.x1(), G + 1, p.z1(), "end_rod[facing=up]");
        c.set(p.x2(), G + 1, p.z1(), "end_rod[facing=up]");
        c.set(p.x1(), G + 1, p.z2(), "end_rod[facing=up]");
        c.set(p.x2(), G + 1, p.z2(), "end_rod[facing=up]");
    }
}
