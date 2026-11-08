package com.aidan.skyserver.goldwars;

import static com.aidan.skyserver.goldwars.MapCanvas.noise;
import static com.aidan.skyserver.goldwars.MapCanvas.noise3;
import static com.aidan.skyserver.goldwars.MapCanvas.pick;
import static com.aidan.skyserver.goldwars.MapCanvas.smooth;

import java.util.ArrayList;
import java.util.List;

/**
 * The three Gold Wars map designs, written in plain Java so they can be previewed outside
 * the game. Each map is designed for the Blue half (east, +X) and then mirrored onto the
 * Red half, so both teams always get an identical side.
 *
 * Every map shares the same plan (Blue side shown; Red is the mirror image):
 *   two gold vaults  2x2 towers, 3 tall: x 38..39, z -17..-16 (north) and z 16..17 (south), y 1..3
 *   spawn pad        x 76..84, z -4..4, walled, with an exit toward each vault (~50 block walk)
 *   routes           two center routes (split by an obstacle in the middle) and a flank route
 *                    along each edge; each vault sits between its center route and its flank
 *   middle wall      x = 0 during the build phase (anything on x = 0 must be air or a full block)
 * Vaults are open to the sky (or a tall cave hall) so players can build over and drop in;
 * in the caves, old half-dug tunnels run underneath toward the vaults.
 */
public final class MapDesigns {
    public static final int VAULT_X = 38;        // vault gold x 38..39
    public static final int VAULT_Z = 16;        // south vault z 16..17, north vault z -17..-16
    public static final int SPAWN_X = 80;
    public static final int SPAWN_PROTECT = 4;
    public static final int HALF_LENGTH = 84;    // outdoor build-phase area
    public static final int HALF_WIDTH = 42;
    public static final int CAVE_X = 90;         // cave interior
    public static final int CAVE_Z = 45;
    public static final int CAVE_SHELL = 3;
    public static final int CAVE_TOP = 24;
    public static final int CAVE_BUILD_TOP = 20;
    public static final int CAVE_DIG = 10;       // how far below the cave floor players may dig

    private static final String BLUE_PAD = "blue_concrete";
    private static final String RED_PAD = "red_concrete";
    private static final String BLUE_ACCENT = "blue_concrete";
    private static final String RED_ACCENT = "red_concrete";
    private static final String ACCENT = "team_accent";  // placeholder, swapped per side
    private static final String BANNER = "team_banner";  // placeholder wall banner, e.g. team_banner[facing=west]

    private MapDesigns() {}

    /** z of the two center routes (+/-) on a map. */
    public static int centerLaneZ(String layoutId) {
        return switch (layoutId) {
            case "caves" -> 11;
            case "village" -> 8;
            default -> 10;
        };
    }

    /**
     * The sky paths: 1-block-wide walkways high above the map, one north and one south, running from
     * a tower/ledge by each team's vault straight across to the enemy's. A quick, sneaky way in, but
     * you're out in the open: one Mage fireball (or an arrow) and you're off it.
     * {z, y of the walkway block} (y above the floor).
     */
    public static int[] skyPath(String layoutId) {
        return switch (layoutId) {
            case "caves" -> new int[]{17, 8};
            case "village" -> new int[]{25, 6};
            default -> new int[]{23, 10};
        };
    }

    /** Conflicts found while laying sky paths (for the offline checker). */
    static final List<String> SKY_CONFLICTS = new ArrayList<>();

    /** One walkway block, with head room cleared above it. Notes anything solid it had to replace. */
    private static void skyStep(MapCanvas c, int x, int y, int z, String block, boolean carve) {
        String was = c.get(x, y, z);
        if (was != null && !carve) SKY_CONFLICTS.add(x + "," + y + "," + z + " walk " + was);
        c.set(x, y, z, block);
        for (int h = 1; h <= 2; h++) {
            String above = c.get(x, y + h, z);
            if (above != null && !carve) SKY_CONFLICTS.add(x + "," + (y + h) + "," + z + " head " + above);
            c.set(x, y + h, z, null);
        }
    }

    /** z of the two flank routes (+/-) on a map. */
    public static int flankLaneZ(String layoutId) {
        return layoutId.equals("sky") ? 36 : 34;
    }

    /** Every gold block for one side (+1 = Blue/east, -1 = Red/west): two 2x2x3 vaults. */
    public static List<int[]> goldPositions(int side) {
        List<int[]> list = new ArrayList<>();
        for (int zBase : new int[]{-VAULT_Z - 1, VAULT_Z})
            for (int dx = 0; dx <= 1; dx++)
                for (int dz = 0; dz <= 1; dz++)
                    for (int y = 1; y <= 3; y++)
                        list.add(new int[]{side * (VAULT_X + dx), y, zBase + dz});
        return list;
    }

    public static MapCanvas build(String layoutId) {
        MapCanvas c = new MapCanvas();
        switch (layoutId) {
            case "village" -> village(c);
            case "caves" -> caves(c);
            default -> skyIslands(c);
        }
        c.mirrorEastToWest();
        finish(c);
        c.connectFencesAndPanes();
        return c;
    }

    /** Spawn pads, gold vaults and team colors (after mirroring so each side gets its own). */
    private static void finish(MapCanvas c) {
        for (int side = -1; side <= 1; side += 2) {
            String pad = side > 0 ? BLUE_PAD : RED_PAD;
            for (int dx = -SPAWN_PROTECT; dx <= SPAWN_PROTECT; dx++) {
                for (int dz = -SPAWN_PROTECT; dz <= SPAWN_PROTECT; dz++) {
                    int x = side * SPAWN_X + dx;
                    c.set(x, 0, dz, pad);
                    for (int y = 1; y <= 3; y++) c.set(x, y, dz, null);
                }
            }
            for (int[] g : goldPositions(side)) {
                c.set(g[0], g[1], g[2], "gold_block");
                if (g[1] == 3) for (int y = 4; y <= 6; y++) c.set(g[0], y, g[2], null); // open above
            }
        }
        // Team accent blocks.
        List<int[]> accents = new ArrayList<>();
        c.forEach((x, y, z, s) -> { if (s.equals(ACCENT)) accents.add(new int[]{x, y, z}); });
        for (int[] a : accents) c.set(a[0], a[1], a[2], a[0] > 0 ? BLUE_ACCENT : RED_ACCENT);
        // Team banners.
        List<Object[]> banners = new ArrayList<>();
        c.forEach((x, y, z, s) -> { if (MapCanvas.name(s).equals(BANNER)) banners.add(new Object[]{x, y, z, s}); });
        for (Object[] b : banners) {
            int x = (int) b[0];
            String props = ((String) b[3]).substring(BANNER.length());
            c.set(x, (int) b[1], (int) b[2], (x > 0 ? "blue" : "red") + "_wall_banner" + props);
        }
    }

    /** Spots decorations must leave alone: the middle line, vaults and spawn. */
    private static boolean reserved(int x, int z) {
        int ax = Math.abs(x), az = Math.abs(z);
        return ax <= 1
                || (ax >= VAULT_X - 3 && ax <= VAULT_X + 4 && az >= VAULT_Z - 3 && az <= VAULT_Z + 4)
                || (ax >= SPAWN_X - SPAWN_PROTECT - 1 && ax <= SPAWN_X + SPAWN_PROTECT + 1 && az <= SPAWN_PROTECT + 1);
    }

    /** Lays a 3-wide path between two points (L-shaped: along x first, then z) on whatever is the ground. */
    private static void path(MapCanvas c, int x1, int z1, int x2, int z2, int y, String... surface) {
        for (int x = Math.min(x1, x2); x <= Math.max(x1, x2); x++)
            for (int w = -1; w <= 1; w++) paint(c, x, y, z1 + w, surface);
        for (int z = Math.min(z1, z2); z <= Math.max(z1, z2); z++)
            for (int w = -1; w <= 1; w++) paint(c, x2 + w, y, z, surface);
    }

    private static void paint(MapCanvas c, int x, int y, int z, String... surface) {
        String g = c.get(x, y, z);
        if (g == null) return;
        String n = MapCanvas.name(g);
        if (!n.equals("grass_block") && !n.equals("moss_block") && !n.equals("coarse_dirt") && !n.equals("dirt")) return;
        c.set(x, y, z, pick(x, y, z, 777, surface));
        String above = c.get(x, y + 1, z);
        if (above != null && !MapCanvas.isFull(above)) c.set(x, y + 1, z, null);
    }

    /** Solid 3x3 lookout tower, 'h' tall and crenellated, with a ladder at (lx, lz) facing away from it. */
    private static void lookoutTower(MapCanvas c, int cx, int cz, int h, int lx, int lz, String facing) {
        for (int dx = -1; dx <= 1; dx++)
            for (int dz = -1; dz <= 1; dz++) {
                for (int y = 0; y <= h; y++) c.set(cx + dx, y, cz + dz, wallStone(cx + dx, y, cz + dz));
                if ((dx != 0 || dz != 0) && (dx + dz) % 2 != 0) c.set(cx + dx, h + 1, cz + dz, "stone_bricks");
            }
        c.set(cx, h, cz, ACCENT);
        for (int y = 1; y <= h; y++) c.set(lx, y, lz, "ladder[facing=" + facing + "]");
    }

    /** Plain-Java helper: the sign (+1 south, -1 north) loop used by every map. */
    private static final int[] SIDES = {-1, 1};

    // =====================================================================
    // MAP 1: SKYREACH ISLES
    // A big home island holds both vaults (each in an open-topped walled courtyard with a
    // lookout tower) and the walled spawn. Two chains of islands make the center routes,
    // split by open void with a floating crystal rock above it; two more chains along the
    // edges make the flanks. Every crossing needs a short bridge.
    // =====================================================================

    private static void skyIslands(MapCanvas c) {
        island(c, 61, 0, 26, 26, 0, 22, 1);         // home island (spawn and both vaults)
        foundation(c, 30, 47, -25, -8);              // ground under the north vault courtyard
        foundation(c, 30, 47, 8, 25);                // ... and the south one
        foundation(c, 44, 70, -7, 7);                // between them, toward spawn
        for (int s : SIDES) {
            island(c, 20, s * 10, 7, 5, 0, 10, 4 + s);        // center route islands
            island(c, 0, s * 11, 10, 6, 0, 12, 6 + s);        // middle islands (void between them)
            island(c, 40, s * 30, 4, 3, 0, 6, 8 + s);         // flank outposts off the home island
            island(c, 23, s * 35, 10, 5, 0, 9, 10 + s);       // flank islands
            island(c, 0, s * 36, 10, 5, 0, 10, 12 + s);
        }
        floatingRock(c, 0, 9, 0, 3, 14);             // high crystal rock between the center routes
        for (int y = 10; y <= 16; y++) c.set(0, y, 0, "amethyst_block");
        for (int y = 10; y <= 13; y++) { c.set(1, y, 0, "amethyst_block"); c.set(0, y, 1, "amethyst_block"); c.set(0, y, -1, "amethyst_block"); }
        c.set(2, 10, 1, "amethyst_cluster[facing=up]");
        c.set(1, 10, -2, "amethyst_cluster[facing=up]");

        // Decorative rocks floating below and around.
        floatingRock(c, 34, -12, -40, 3, 15);
        floatingRock(c, 12, -16, 26, 2, 16);
        floatingRock(c, 70, -14, 30, 3, 17);
        floatingRock(c, 52, 14, -34, 2, 18);
        floatingRock(c, 80, 11, 24, 2, 19);

        for (int s : SIDES) skyVault(c, s);
        for (int s : SIDES) skyWalk(c, s);
        skySpawn(c);
        for (int s : SIDES) {
            skyPerch(c, 20, s * 10);
            lampPost(c, 40, s * 30 - s * 2, "spruce_fence");
            lampPost(c, 25, s * 13, "spruce_fence");
        }
        watchtowerRuin(c, 24, -35);
        cherryGrove(c, 24, 35);
        oakTree(c, 6, 1, -9, 5);
        cherryTree(c, 6, 1, 13);
        oakTree(c, 66, 1, -16, 5);
        cherryTree(c, 70, 1, 14);
        birchTree(c, 54, 1, 21, 5);
        spruceTree(c, 78, 1, -14, 7);
        oakTree(c, 4, 1, 38, 4);
        decorateGrass(c, 0, 90, -45, 45, 1);
    }

    /** Open-topped walled courtyard around a vault, 4 gates and a lookout tower at the outer corner. */
    private static void skyVault(MapCanvas c, int s) {
        int x1 = 32, x2 = 45;
        int zIn = s * 10, zOut = s * 23;               // wall toward the center / toward the flank
        int zc = s * 16 + (s > 0 ? 0 : -1);            // vault middle row (16 south, -17 north)
        int zlo = Math.min(zIn, zOut), zhi = Math.max(zIn, zOut);
        for (int x = x1 + 1; x < x2; x++)
            for (int z = zlo + 1; z < zhi; z++) {
                boolean plinth = x >= VAULT_X - 1 && x <= VAULT_X + 2 && Math.abs(Math.abs(z) - 16.5) <= 2;
                c.set(x, 0, z, plinth ? "chiseled_stone_bricks"
                        : pick(x, 0, z, 41, "polished_andesite", "polished_andesite", "stone_bricks", "andesite", "cracked_stone_bricks"));
                for (int y = 1; y <= 8; y++) c.set(x, y, z, null);
            }
        for (int x = x1; x <= x2; x++)
            for (int z = zlo; z <= zhi; z++) {
                if (x != x1 && x != x2 && z != zlo && z != zhi) continue;
                for (int y = 0; y <= 5; y++) c.set(x, y, z, wallStone(x, y, z));
                if ((x + z) % 2 == 0) c.set(x, 6, z, "stone_bricks");
            }
        gate(c, x1, zc, true);                 // west, facing the enemy
        gate(c, x2, zc, true);                 // east, toward spawn
        gate(c, VAULT_X, zIn, false);          // toward the center route
        gate(c, VAULT_X, zOut, false);         // toward the flank
        // Lookout tower at the outer-west corner: high ground for bridging over the walls.
        lookoutTower(c, x1, zOut, 10, x1 + 2, zOut - s, "east");
        c.set(x1 - 1, 4, zc - 2, BANNER + "[facing=west]");
        c.set(x1 - 1, 4, zc + 2, BANNER + "[facing=west]");
        c.set(x2 - 2, 1, zIn + s * 2, "barrel[facing=up]");
        c.set(x2 - 3, 1, zIn + s * 2, "barrel[facing=up]");
        c.set(x2 - 2, 2, zIn + s * 2, "barrel[facing=up]");
        c.set(x1 + 2, 1, zIn + s * 2, "hay_block[axis=y]");
    }

    /**
     * Sky path: from the top of the vault's lookout tower, a 1-wide plank walkway runs west at the
     * tower's height straight to the enemy tower, with a little 3x3 crow's nest halfway.
     */
    private static void skyWalk(MapCanvas c, int s) {
        int[] sp = skyPath("sky");
        int z = s * sp[0], y = sp[1];
        c.set(31, y + 1, z, null);                       // gap in the tower's battlements
        for (int x = 30; x >= 0; x--)
            skyStep(c, x, y, z, pick(x, y, z, 651, "spruce_planks", "spruce_planks", "stripped_spruce_wood[axis=x]"), false);
        for (int x = 14; x <= 16; x++)
            for (int dz = -1; dz <= 1; dz++) if (dz != 0) skyStep(c, x, y, z + dz, "spruce_planks", false);
        c.set(15, y + 1, z + 1, "spruce_fence");
        c.set(15, y + 2, z + 1, "lantern[hanging=false]");
    }

    /** Walled spawn yard at the back of the home island, an exit toward each vault and a big arch behind. */
    private static void skySpawn(MapCanvas c) {
        int wx = 72, ex = 87;
        for (int x = wx; x <= ex; x++)
            for (int z = -8; z <= 8; z++) {
                boolean wall = x == wx || x == ex || Math.abs(z) == 8;
                c.set(x, 0, z, pick(x, 0, z, 61, "stone_bricks", "polished_andesite", "mossy_stone_bricks"));
                for (int y = 1; y <= 6; y++) c.set(x, y, z, null);
                if (wall) for (int y = 1; y <= 3; y++) c.set(x, y, z, wallStone(x, y, z));
            }
        for (int s : SIDES)
            for (int z = 5; z <= 7; z++) for (int y = 1; y <= 3; y++) c.set(wx, y, s * z, null); // exits
        foundation(c, wx, ex, -8, 8);
        lampPost(c, ex - 1, -7, "spruce_fence");   // back corners, out of the way of the exits
        lampPost(c, ex - 1, 7, "spruce_fence");
        // Arch behind spawn with the team color: a landmark you can see from the middle.
        int ax = 86;
        for (int y = 1; y <= 8; y++) {
            c.set(ax, y, -3, y == 8 ? "chiseled_stone_bricks" : wallStone(ax, y, -3));
            c.set(ax, y, 3, y == 8 ? "chiseled_stone_bricks" : wallStone(ax, y, 3));
        }
        for (int z = -3; z <= 3; z++) c.set(ax, 9, z, Math.abs(z) <= 1 ? ACCENT : "stone_bricks");
        c.set(ax, 8, 0, "lantern[hanging=true]");
        // Paths from each exit to that vault's east gate.
        for (int s : SIDES) {
            path(c, wx - 1, s * 6, 60, s * 6, 0, "dirt_path", "dirt_path", "coarse_dirt", "gravel");
            path(c, 60, s * 6, 60, s * 16, 0, "dirt_path", "dirt_path", "coarse_dirt");
            path(c, 60, s * 16, 46, s * 16, 0, "dirt_path", "dirt_path", "coarse_dirt", "gravel");
        }
    }

    /** High platform above a center island, reached by a ladder: overlooks the middle. */
    private static void skyPerch(MapCanvas c, int cx, int cz) {
        int y = 10;
        for (int x = cx - 2; x <= cx + 2; x++)
            for (int z = cz - 2; z <= cz + 2; z++) {
                c.set(x, y, z, pick(x, y, z, 611, "stone_bricks", "mossy_stone_bricks", "cracked_stone_bricks", "polished_andesite"));
                boolean edge = Math.abs(x - cx) == 2 || Math.abs(z - cz) == 2;
                if (edge && !(x == cx + 2 && z == cz)) c.set(x, y + 1, z, "spruce_fence");
            }
        for (int yy = 1; yy < y; yy++) c.set(cx + 2, yy, cz, "stone_bricks");
        for (int yy = 1; yy <= y; yy++) c.set(cx + 3, yy, cz, "ladder[facing=east]");
        c.set(cx - 2, y + 2, cz - 2, "lantern[hanging=false]");
        c.set(cx - 2, y + 1, cz - 2, "stone_bricks");
    }

    // =====================================================================
    // MAP 2: HARVEST VILLAGE
    // A closed town hall fills the middle, so the main road splits into two center streets
    // that cross the river on stone bridges. Each vault sits in a palisade yard with a
    // watchtower; flank roads run along the edges (houses and a smithy to the north, wheat
    // fields and an orchard to the south) and cross the river on wooden bridges.
    // =====================================================================

    private static void village(MapCanvas c) {
        for (int x = 0; x <= HALF_LENGTH; x++)
            for (int z = -HALF_WIDTH; z <= HALF_WIDTH; z++) {
                double n = noise(x, z, 201);
                c.set(x, 0, z, n < 0.05 ? "coarse_dirt" : n < 0.08 ? "moss_block" : "grass_block");
                for (int y = 1; y <= 3; y++) c.set(x, -y, z, pick(x, -y, z, 202, "dirt", "dirt", "coarse_dirt", "rooted_dirt"));
                c.set(x, -4, z, "bedrock");
                int edge = Math.min(HALF_LENGTH - x, HALF_WIDTH - Math.abs(z));
                int depth = 1 + Math.min(edge, 10) + (int) (smooth(x, z, 4, 203) * 4);
                for (int y = 5; y <= 4 + depth; y++) c.set(x, -y, z, rock(x, -y, z, 204));
                if (x > 0 && edge <= 1 && noise(x, z, 205) < 0.35) c.set(x, -5 - depth, z, "hanging_roots");
            }

        villageRiver(c);
        villageRoads(c);
        townHall(c);
        for (int s : SIDES) villageYard(c, s);
        for (int s : SIDES) villageWalk(c, s);
        villageSpawn(c);

        // Market stalls on the center streets near the middle.
        for (int s : SIDES) {
            stall(c, 9, s * 13);
            stall(c, 16, s * 3 + s * 10);
            lampPost(c, 21, s * 11, "spruce_fence");
            lampPost(c, 3, s * 5, "spruce_fence");
        }
        // North flank: houses along the road, a cottage row and a smithy.
        tudorHouse(c, 10, -39, 1);
        tudorHouse(c, 40, -39, 1);
        tudorHouse(c, 62, -39, 1);
        cottage(c, 14, -28);
        cottage(c, 52, -29);
        smithy(c, 68, -28);
        lampPost(c, 22, -31, "spruce_fence");
        lampPost(c, 48, -36, "spruce_fence");
        // South flank: wheat fields, an orchard and a lookout hill.
        wheatField(c, 4, 37, 18, 41);
        wheatField(c, 48, 37, 62, 41);
        orchard(c, 30, 37, 42, 42);
        scarecrow(c, 21, 39);
        lookoutHill(c, 58, 26);
        c.set(66, 1, 38, "hay_block[axis=y]");
        c.set(66, 1, 39, "hay_block[axis=x]");
        c.set(66, 2, 38, "hay_block[axis=z]");

        oakTree(c, 70, 1, -14, 5);
        oakTree(c, 66, 1, 15, 5);
        spruceTree(c, 82, 1, -24, 8);
        spruceTree(c, 80, 1, 26, 7);
        birchTree(c, 30, 1, -2, 5);
        birchTree(c, 30, 1, 3, 4);
        oakTree(c, 52, 1, 2, 5);
        decorateGrass(c, 0, HALF_LENGTH, -HALF_WIDTH, HALF_WIDTH, 2);
    }

    /** River at x 24..27 with stone bridges on both center streets, wooden ones on the flanks, a log and a ford. */
    private static void villageRiver(MapCanvas c) {
        for (int z = -41; z <= 41; z++) {
            for (int x = 24; x <= 27; x++) {
                c.set(x, 0, z, "water");
                c.set(x, -1, z, "water");
                c.set(x, -2, z, pick(x, -2, z, 211, "gravel", "sand", "clay", "gravel"));
            }
            c.set(23, 0, z, pick(23, 0, z, 212, "mud", "grass_block", "coarse_dirt"));
            c.set(28, 0, z, pick(28, 0, z, 213, "mud", "grass_block", "coarse_dirt"));
            if (noise(25, z, 214) < 0.12 && Math.abs(z) > 3 && Math.abs(Math.abs(z) - 8) > 3 && Math.abs(Math.abs(z) - 34) > 3)
                c.set(23, 1, z, "sugar_cane");
        }
        // Ends of the river are closed by the banks so the water never spills off the map.
        for (int x = 24; x <= 27; x++) for (int z : new int[]{-42, 42}) { c.set(x, 0, z, "mud"); c.set(x, -1, z, "dirt"); }
        for (int s : SIDES) {
            // Stone bridge on the center street.
            for (int z = s * 6; z != s * 11; z += s)
                for (int x = 23; x <= 28; x++) c.set(x, 0, z, Math.abs(z) == 6 || Math.abs(z) == 10 ? "stone_bricks" : "polished_andesite");
            for (int x = 23; x <= 28; x++) {
                c.set(x, 1, s * 6, "stone_brick_slab[type=bottom]");
                c.set(x, 1, s * 10, "stone_brick_slab[type=bottom]");
            }
            // Wooden bridge on the flank road.
            for (int z = s * 32; z != s * 37; z += s)
                for (int x = 23; x <= 28; x++) c.set(x, 0, z, "spruce_planks");
            for (int x = 23; x <= 28; x++) { c.set(x, 1, s * 32, "spruce_fence"); c.set(x, 1, s * 36, "spruce_fence"); }
            c.set(23, 2, s * 32, "lantern[hanging=false]");
            c.set(28, 2, s * 36, "lantern[hanging=false]");
        }
        // A narrow log to balance across (north) and stepping stones (south).
        for (int x = 23; x <= 28; x++) c.set(x, 0, -21, "oak_log[axis=x]");
        c.set(24, 0, 21, "mossy_cobblestone");
        c.set(26, 0, 21, "mossy_cobblestone");
        c.set(27, 0, 22, "mossy_cobblestone");
    }

    private static void villageRoads(MapCanvas c) {
        for (int s : SIDES) {
            path(c, 1, s * 8, 31, s * 8, 0, "dirt_path", "dirt_path", "dirt_path", "coarse_dirt", "gravel");    // center street
            path(c, 31, s * 8, 31, s * 9, 0, "dirt_path");
            path(c, 1, s * 34, 46, s * 34, 0, "dirt_path", "dirt_path", "coarse_dirt", "gravel");               // flank road
            path(c, 46, s * 34, 46, s * 24, 0, "dirt_path", "dirt_path", "coarse_dirt");
            path(c, 71, s * 6, 60, s * 6, 0, "dirt_path", "dirt_path", "coarse_dirt");                          // spawn roads
            path(c, 60, s * 6, 60, s * 16, 0, "dirt_path", "dirt_path", "coarse_dirt");
            path(c, 60, s * 16, 47, s * 16, 0, "dirt_path", "dirt_path", "gravel");
        }
    }

    /** Closed stone town hall filling the middle (splits the center into two streets), with a clock tower. */
    private static void townHall(MapCanvas c) {
        int x2 = 14, z1 = -4, z2 = 4;
        for (int x = 0; x <= x2; x++)
            for (int z = z1; z <= z2; z++) {
                boolean edge = x == x2 || z == z1 || z == z2;
                c.set(x, 0, z, "stone_bricks");
                for (int y = 1; y <= 7; y++) {
                    String s = !edge ? null : (x == x2 && Math.abs(z) == 4) ? "polished_andesite"
                            : y == 1 ? pick(x, y, z, 731, "cobblestone", "mossy_cobblestone", "stone_bricks")
                            : pick(x, y, z, 732, "stone_bricks", "stone_bricks", "mossy_stone_bricks", "cracked_stone_bricks");
                    if (x == 0 && !edge) s = "stone_bricks"; // solid on the middle line
                    c.set(x, y, z, s);
                }
            }
        for (int x = 3; x <= 12; x += 3) for (int z : new int[]{z1, z2}) { c.set(x, 3, z, "glass_pane"); c.set(x, 4, z, "glass_pane"); }
        // Pitched roof along x.
        for (int layer = 0; layer <= 4; layer++) {
            int y = 8 + layer;
            int za = z1 - 1 + layer, zb = z2 + 1 - layer;
            for (int x = 0; x <= x2 + 1; x++) {
                if (za < zb) {
                    c.set(x, y, za, x == 0 ? "stone_bricks" : "dark_oak_stairs[facing=south,half=bottom]");
                    c.set(x, y, zb, x == 0 ? "stone_bricks" : "dark_oak_stairs[facing=north,half=bottom]");
                    for (int z = za + 1; z < zb; z++) if (x == 0 || x == x2) c.set(x, y, z, "stone_bricks");
                } else if (za == zb) {
                    c.set(x, y, za, x == 0 ? "stone_bricks" : "dark_oak_slab[type=bottom]");
                }
            }
        }
        // Clock tower over the middle.
        for (int x = 0; x <= 1; x++)
            for (int z = -1; z <= 1; z++)
                for (int y = 13; y <= 18; y++) c.set(x, y, z, x == 1 && Math.abs(z) == 1 ? "stripped_spruce_log[axis=y]" : "stone_bricks");
        c.set(2, 16, 0, ACCENT);
        for (int x = 0; x <= 2; x++) for (int z = -2; z <= 2; z++) c.set(x, 19, z, x == 0 ? "stone_bricks" : "dark_oak_slab[type=bottom]");
        c.set(0, 20, 0, "stone_bricks");
        c.set(0, 21, 0, "chiseled_stone_bricks");
        c.set(x2 + 1, 4, -2, BANNER + "[facing=east]");
        c.set(x2 + 1, 4, 2, BANNER + "[facing=east]");
    }

    /** Palisade yard around a vault: 4 gates and a watchtower at the outer-west corner. */
    private static void villageYard(MapCanvas c, int s) {
        int x1 = 32, x2 = 45, zIn = s * 10, zOut = s * 23;
        int zc = s * 16 + (s > 0 ? 0 : -1);
        int zlo = Math.min(zIn, zOut), zhi = Math.max(zIn, zOut);
        for (int x = x1 + 1; x < x2; x++)
            for (int z = zlo + 1; z < zhi; z++) {
                boolean plinth = x >= VAULT_X - 1 && x <= VAULT_X + 2 && Math.abs(Math.abs(z) - 16.5) <= 2;
                c.set(x, 0, z, plinth ? pick(x, 0, z, 241, "stone_bricks", "polished_andesite")
                        : pick(x, 0, z, 242, "coarse_dirt", "dirt_path", "gravel", "grass_block", "coarse_dirt"));
                for (int y = 1; y <= 6; y++) c.set(x, y, z, null);
            }
        for (int x = x1; x <= x2; x++)
            for (int z = zlo; z <= zhi; z++) {
                if (x != x1 && x != x2 && z != zlo && z != zhi) continue;
                int h = 4 + (int) (noise(x, z, 243) * 2);
                for (int y = 1; y <= h; y++) c.set(x, y, z, "spruce_log[axis=y]");
                c.set(x, 0, z, "cobblestone");
            }
        for (int d = -1; d <= 1; d++)
            for (int y = 1; y <= 5; y++) {
                c.set(x1, y, zc + d, null);
                c.set(x2, y, zc + d, null);
                c.set(VAULT_X + d, y, zIn, null);
                c.set(VAULT_X + d, y, zOut, null);
            }
        for (int d = -1; d <= 1; d++) {
            c.set(x1, 4, zc + d, "stripped_spruce_log[axis=z]");
            c.set(VAULT_X + d, 4, zIn, "stripped_spruce_log[axis=x]");
            c.set(VAULT_X + d, 4, zOut, "stripped_spruce_log[axis=x]");
        }
        c.set(x1, 5, zc, ACCENT);
        c.set(x1 - 1, 3, zc - 2, BANNER + "[facing=west]");
        c.set(x1 - 1, 3, zc + 2, BANNER + "[facing=west]");
        watchtower(c, x1 - 3, zOut + s * 3);
        c.set(x2 - 2, 1, zIn + s * 2, "anvil[facing=north]");
        c.set(x2 - 3, 1, zIn + s * 2, "smithing_table");
        c.set(x1 + 2, 1, zOut - s * 2, "hay_block[axis=y]");
        c.set(x1 + 2, 2, zOut - s * 2, "hay_block[axis=y]");
        c.set(x1 + 3, 1, zOut - s * 2, "barrel[facing=up]");
    }

    /**
     * Sky path: from each vault yard's watchtower platform, a 1-wide rope-and-plank walkway runs west
     * over the river and the rooftops on log posts, to the enemy watchtower.
     */
    private static void villageWalk(MapCanvas c, int s) {
        int[] sp = skyPath("village");
        int z = s * sp[0], y = sp[1];
        int tz = s * 26;                                  // watchtower centre (x 29)
        c.set(28, y + 1, tz, null);                       // gap in the railing on the west side
        skyStep(c, 27, y, tz, "spruce_planks", false);    // step off the platform...
        skyStep(c, 27, y, z, "spruce_planks", false);     // ...onto the walkway line
        for (int x = 26; x >= 0; x--) skyStep(c, x, y, z, pick(x, y, z, 652, "spruce_planks", "spruce_planks", "oak_planks"), false);
        for (int x = 21; x >= 3; x -= 9)                  // log posts down to the ground
            for (int yy = 1; yy < y; yy++) if (c.isAir(x, yy, z)) c.set(x, yy, z, "spruce_log[axis=y]");
    }

    /** Stone gatehouse yard around the spawn with an exit toward each vault. */
    private static void villageSpawn(MapCanvas c) {
        int wx = 72, ex = 87;
        for (int x = wx; x <= ex; x++)
            for (int z = -8; z <= 8; z++) {
                boolean wall = x == wx || x == ex || Math.abs(z) == 8;
                c.set(x, 0, z, pick(x, 0, z, 251, "stone_bricks", "cobblestone", "polished_andesite"));
                for (int y = 1; y <= 6; y++) c.set(x, y, z, null);
                if (wall) for (int y = 1; y <= 4; y++) c.set(x, y, z, pick(x, y, z, 252, "cobblestone", "stone_bricks", "mossy_cobblestone"));
            }
        for (int s : SIDES) {
            for (int z = 5; z <= 7; z++) for (int y = 1; y <= 3; y++) c.set(wx, y, s * z, null);
            c.set(wx, 4, s * 6, ACCENT);
        }
        lampPost(c, ex - 1, -7, "spruce_fence");   // back corners, out of the way of the exits
        lampPost(c, ex - 1, 7, "spruce_fence");
    }

    // =====================================================================
    // MAP 3: DEEPFORGE CAVES
    // Inside a mountain. The middle is two domed caverns split by a giant geode wall, each
    // feeding a center tunnel into a tall deepslate vault hall (high ceilings for pillaring
    // over the gold). The north flank is an old mine tunnel, the south flank a flooded
    // grotto, with side passages from the caverns. Old half-dug tunnels run under the
    // middle toward each vault: finish digging them to come up underneath the gold.
    // =====================================================================

    private static void caves(MapCanvas c) {
        int sx = CAVE_X + CAVE_SHELL, sz = CAVE_Z + CAVE_SHELL;
        int bottom = -CAVE_DIG - 2;
        for (int x = 0; x <= sx; x++)
            for (int z = -sz; z <= sz; z++) {
                c.set(x, bottom, z, "bedrock");
                for (int y = bottom + 1; y < CAVE_TOP; y++) c.set(x, y, z, caveRock(x, y, z));
                c.set(x, CAVE_TOP, z, noise(x, z, 401) < 0.15 ? "moss_block" : "grass_block");
            }
        for (int s : SIDES) {
            cavern(c, s);
            centerTunnel(c, s);
            vaultHall(c, s);
            spawnCorridor(c, s);
            underTunnel(c, s);
            cavernToFlank(c, s);
        }
        geodeWall(c);
        mineTunnel(c);
        grotto(c);
        caveSpawnRoom(c);
        caveLighting(c);
        for (int s : SIDES) caveCatwalk(c, s);

        spruceTree(c, 25, CAVE_TOP + 1, -25, 8);
        spruceTree(c, 60, CAVE_TOP + 1, 30, 9);
        oakTree(c, 12, CAVE_TOP + 1, 20, 5);
        oakTree(c, 70, CAVE_TOP + 1, -20, 5);
        spruceTree(c, 44, CAVE_TOP + 1, 4, 7);
    }

    /** One of the two middle caverns (north s=-1 / south s=+1): domed, with stalagmites and mushrooms. */
    private static void cavern(MapCanvas c, int s) {
        int cz = s * 13, rx = 22, rz = 9;
        for (int x = 0; x <= rx + 2; x++)
            for (int z = cz - rz - 2; z <= cz + rz + 2; z++) {
                double dist = Math.sqrt(Math.pow(x / (double) rx, 2) + Math.pow((z - cz) / (double) rz, 2));
                if (dist > 1.0 + 0.08 * smooth(x, z, 3, 421)) continue;
                if (Math.abs(z) <= 4) continue; // the geode wall
                int h = 6 + (int) ((1 - Math.min(1, dist)) * 10 + smooth(x, z, 5, 422) * 3);
                for (int y = 1; y <= h; y++) c.set(x, y, z, null);
                double n = noise(x, z, 423);
                c.set(x, 0, z, n < 0.15 ? "gravel" : n < 0.3 ? "andesite" : n < 0.4 ? "tuff" : "stone");
                if (x >= 3 && h > 12 && noise(x, z, 424) < 0.05) {
                    c.set(x, h, z, "pointed_dripstone[vertical_direction=down,thickness=base]");
                    c.set(x, h - 1, z, "pointed_dripstone[vertical_direction=down,thickness=tip]");
                    c.set(x, h + 1, z, "dripstone_block");
                }
            }
        for (int x = 4; x <= rx; x++)
            for (int z = cz - rz; z <= cz + rz; z++) {
                if (!c.isAir(x, 1, z) || c.get(x, 0, z) == null || Math.abs(z) <= 5) continue;
                double edgeDist = Math.sqrt(Math.pow(x / (double) rx, 2) + Math.pow((z - cz) / (double) rz, 2));
                double n = noise(x, z, 427);
                if (edgeDist > 0.8 && n < 0.10 && c.isAir(x, 2, z)) {
                    c.set(x, 1, z, "pointed_dripstone[vertical_direction=up,thickness=base]");
                    c.set(x, 2, z, "pointed_dripstone[vertical_direction=up,thickness=tip]");
                } else if (edgeDist > 0.75 && n < 0.14) {
                    c.set(x, 1, z, n < 0.12 ? "brown_mushroom" : "red_mushroom");
                }
            }
    }

    /** The wall of rock between the two caverns, faced with a giant amethyst geode. */
    private static void geodeWall(MapCanvas c) {
        for (int x = 0; x <= 22; x++)
            for (int z = -4; z <= 4; z++)
                for (int y = 0; y <= 18; y++) {
                    boolean face = Math.abs(z) == 4;
                    double r = noise3(x, y, z, 425);
                    String st = face ? (r < 0.5 ? "amethyst_block" : r < 0.8 ? "calcite" : "smooth_basalt")
                            : (Math.abs(z) == 3 ? "calcite" : "smooth_basalt");
                    if (c.get(x, y, z) != null) c.set(x, y, z, st);
                }
        for (int s : SIDES)
            for (int x = 2; x <= 20; x += 3)
                if (c.isAir(x, 1, s * 5) && noise(x, s, 426) < 0.6) c.set(x, 1, s * 5, "amethyst_cluster[facing=up]");
    }

    /** Center tunnel from a cavern to the vault hall's west gate, with stone arches. */
    private static void centerTunnel(MapCanvas c, int s) {
        int zc = s * 11;
        for (int x = 18; x <= 31; x++)
            for (int z = zc - 2; z <= zc + 2; z++) {
                for (int y = 1; y <= 5; y++) c.set(x, y, z, null);
                c.set(x, 0, z, pick(x, 0, z, 471, "stone", "andesite", "cobblestone", "gravel"));
            }
        for (int x = 22; x <= 30; x += 4) {
            for (int y = 1; y <= 5; y++) { c.set(x, y, zc - 3, "stone_bricks"); c.set(x, y, zc + 3, "stone_bricks"); }
            for (int z = zc - 3; z <= zc + 3; z++) c.set(x, 6, z, "stone_bricks");
            c.set(x, 5, zc, "lantern[hanging=true]");
        }
    }

    /** Tall deepslate hall around a vault: west (center tunnel), flank, and spawn entrances. */
    private static void vaultHall(MapCanvas c, int s) {
        int x1 = 30, x2 = 47, zIn = s * 8, zOut = s * 25;
        int zlo = Math.min(zIn, zOut), zhi = Math.max(zIn, zOut);
        int top = 13;
        for (int x = x1; x <= x2; x++)
            for (int z = zlo; z <= zhi; z++) {
                boolean wall = x == x1 || x == x2 || z == zlo || z == zhi;
                for (int y = 0; y <= top + 1; y++) {
                    if (wall) c.set(x, y, z, y == 0 ? "polished_deepslate" : pick(x, y, z, 431, "deepslate_bricks", "deepslate_bricks", "cracked_deepslate_bricks", "deepslate_tiles"));
                    else if (y == 0) c.set(x, 0, z, x >= VAULT_X - 1 && x <= VAULT_X + 2 && Math.abs(Math.abs(z) - 16.5) <= 2 ? "polished_blackstone_bricks"
                            : pick(x, 0, z, 432, "deepslate_tiles", "polished_deepslate", "deepslate_tiles"));
                    else if (y <= top - 1) c.set(x, y, z, null);
                    else c.set(x, y, z, "deepslate_tiles");
                }
            }
        // Pillars.
        for (int x : new int[]{x1 + 3, x2 - 3})
            for (int z : new int[]{zIn + s * 3, zOut - s * 3})
                for (int y = 1; y < top; y++) c.set(x, y, z, "polished_basalt[axis=y]");
        // Entrances.
        for (int z = s * 9; z != s * 14; z += s) for (int y = 1; y <= 4; y++) c.set(x1, y, z, null);   // center tunnel
        for (int x = 37; x <= 40; x++) for (int y = 1; y <= 4; y++) c.set(x, y, zOut, null);            // flank passage
        for (int z = s * 15; z != s * 19; z += s) for (int y = 1; y <= 4; y++) c.set(x2, y, z, null);  // spawn corridor
        c.set(x1, 5, s * 11, ACCENT);
        c.set(x1 - 1, 4, s * 9 - s, BANNER + "[facing=west]");
        // Forge decorations and hanging lights.
        c.set(x1 + 1, 1, zOut - s * 2, "lava_cauldron");
        c.set(x2 - 1, 1, zIn + s * 2, "anvil[facing=east]");
        c.set(x2 - 1, 1, zIn + s * 3, "blast_furnace[facing=west]");
        c.set(x2 - 1, 1, zOut - s * 2, "smithing_table");
        for (int x : new int[]{x1 + 4, VAULT_X + 6, x2 - 2})
            for (int z : new int[]{zIn + s * 4, zOut - s * 4}) c.set(x, top - 1, z, "lantern[hanging=true]");
        // Passage from the flank tunnel to this hall.
        for (int x = 37; x <= 40; x++)
            for (int z = s * 26; z != s * 33; z += s) {
                for (int y = 1; y <= 4; y++) c.set(x, y, z, null);
                c.set(x, 0, z, pick(x, 0, z, 442, "gravel", "stone", "andesite"));
            }
        for (int z = s * 27; z != s * 33; z += s * 3) {
            c.set(36, 1, z, "oak_log[axis=y]"); c.set(36, 2, z, "oak_log[axis=y]");
            c.set(41, 1, z, "oak_log[axis=y]"); c.set(41, 2, z, "oak_log[axis=y]");
        }
    }

    /**
     * Sky path: an old miners' catwalk high in the caverns. From a ladder in each vault hall, it goes
     * through a narrow crawl in the rock and out over the cavern on a 1-wide walkway, to the enemy hall.
     */
    private static void caveCatwalk(MapCanvas c, int s) {
        int[] sp = skyPath("caves");
        int z = s * sp[0], y = sp[1];
        for (int x = 30; x >= 0; x--) {
            boolean inRock = c.get(x, y + 1, z) != null;
            skyStep(c, x, y, z, inRock ? pick(x, y, z, 653, "stone_bricks", "cobblestone") : pick(x, y, z, 654, "spruce_planks", "spruce_planks", "dark_oak_planks"), true);
            if (inRock) c.set(x, y + 3, z, x % 5 == 0 ? "lantern[hanging=true]" : null); // a bit of head room, lit
        }
        for (int yy = 1; yy <= y; yy++) c.set(31, yy, z, "ladder[facing=east]");  // up the hall wall
        for (int yy = y + 1; yy <= y + 2; yy++) c.set(31, yy, z, null);
    }

    /** Corridor from the spawn room's exit to the vault hall's east door (~50 blocks to the gold). */
    private static void spawnCorridor(MapCanvas c, int s) {
        int[][] legs = {{58, 71, s * 4, s * 6}, {56, 58, s * 4, s * 18}, {48, 56, s * 15, s * 18}};
        for (int[] l : legs)
            for (int x = l[0]; x <= l[1]; x++)
                for (int z = Math.min(l[2], l[3]); z <= Math.max(l[2], l[3]); z++) {
                    for (int y = 1; y <= 4; y++) c.set(x, y, z, null);
                    c.set(x, 0, z, pick(x, 0, z, 481, "polished_deepslate", "deepslate_tiles", "stone"));
                }
        c.set(57, 4, s * 11, "lantern[hanging=true]");
        c.set(64, 4, s * 5, "lantern[hanging=true]");
        c.set(52, 4, s * 16, "lantern[hanging=true]");
    }

    /** Spawn room at the back of the mountain, with an exit toward each vault. */
    private static void caveSpawnRoom(MapCanvas c) {
        int x1 = 72, x2 = 88;
        for (int x = x1 - 1; x <= x2 + 1; x++)
            for (int z = -8; z <= 8; z++) {
                boolean wall = x == x1 - 1 || x == x2 + 1 || Math.abs(z) == 8;
                for (int y = 0; y <= 7; y++) {
                    if (wall || y == 7) c.set(x, y, z, y == 7 ? "deepslate_tiles" : "deepslate_bricks");
                    else if (y == 0) c.set(x, 0, z, "polished_deepslate");
                    else c.set(x, y, z, null);
                }
            }
        for (int s : SIDES) for (int z = 4; z <= 6; z++) for (int y = 1; y <= 3; y++) c.set(x1 - 1, y, s * z, null);
        c.set(x2 + 1, 3, 0, ACCENT);
        c.set(SPAWN_X, 6, -6, "lantern[hanging=true]");
        c.set(SPAWN_X, 6, 6, "lantern[hanging=true]");
    }

    /** Old half-dug tunnel under the middle, heading for a vault: dig the rest of the way to come up under the gold. */
    private static void underTunnel(MapCanvas c, int s) {
        int zc = s * 16;
        for (int x = 0; x <= 24; x++)
            for (int z = zc - 1; z <= zc + 1; z++) {
                for (int y = -5; y <= -3; y++) c.set(x, y, z, null);
                c.set(x, -6, z, pick(x, -6, z, 491, "gravel", "deepslate", "tuff"));
            }
        for (int x = 3; x <= 24; x += 4) {
            for (int y = -5; y <= -4; y++) { c.set(x, y, zc - 2, "oak_log[axis=y]"); c.set(x, y, zc + 2, "oak_log[axis=y]"); }
            for (int z = zc - 2; z <= zc + 2; z++) c.set(x, -2, z, "stripped_oak_log[axis=z]");
            c.set(x, -3, zc, "lantern[hanging=true]");
        }
        // Rails and an abandoned cart spot; the dead end is propped with a fence.
        for (int x = 1; x <= 23; x++) c.set(x, -5, zc, "rail[shape=east_west]");
        c.set(24, -5, zc - 1, "oak_fence");
        c.set(24, -5, zc + 1, "oak_fence");
        // Ladder shaft from the cavern floor down into the tunnel.
        int sx = 12;
        for (int y = -5; y <= 0; y++) { c.set(sx, y, zc, null); c.set(sx, y, zc + s, null); }
        for (int y = -5; y <= 0; y++) c.set(sx, y, zc + s, "ladder[facing=" + (s > 0 ? "north" : "south") + "]");
        for (int y = -5; y <= 0; y++) c.set(sx, y, zc + 2 * s, caveRock(sx, y, zc + 2 * s));
        c.set(sx, -5, zc, "rail[shape=east_west]");
    }

    /** Side passage from a cavern to its flank tunnel (lets fights rotate between routes). */
    private static void cavernToFlank(MapCanvas c, int s) {
        for (int x = 8; x <= 10; x++)
            for (int z = s * 20; z != s * 33; z += s) {
                for (int y = 1; y <= 4; y++) c.set(x, y, z, null);
                c.set(x, 0, z, pick(x, 0, z, 461, "stone", "gravel", "andesite"));
            }
        c.set(9, 4, s * 26, "lantern[hanging=true]");
    }

    /** North flank: a 5-wide mine tunnel with timber frames, rails and hanging lanterns. */
    private static void mineTunnel(MapCanvas c) {
        int zA = -36, zB = -32;
        for (int x = 0; x <= 44; x++)
            for (int z = zA; z <= zB; z++) {
                for (int y = 1; y <= 4; y++) c.set(x, y, z, null);
                c.set(x, 0, z, pick(x, 0, z, 441, "gravel", "stone", "andesite", "tuff"));
            }
        for (int x = 1; x <= 42; x++) c.set(x, 1, -34, "rail[shape=east_west]");
        for (int x = 4; x <= 44; x += 5) {
            for (int y = 1; y <= 3; y++) { c.set(x, y, zA, "oak_log[axis=y]"); c.set(x, y, zB, "oak_log[axis=y]"); }
            for (int z = zA; z <= zB; z++) c.set(x, 4, z, "stripped_oak_log[axis=z]");
            c.set(x, 3, -34, "lantern[hanging=true]");
        }
        c.set(30, 1, -35, "barrel[facing=up]");
        c.set(31, 1, -35, "barrel[facing=up]");
        c.set(17, 1, -33, "oak_slab[type=bottom]");
    }

    /** South flank: a lush flooded grotto with a lake and a stone walkway, joined to the middle line. */
    private static void grotto(MapCanvas c) {
        int cx = 22, cz = 35;
        for (int x = 0; x <= 42; x++)
            for (int z = 27; z <= 43; z++) {
                double d = Math.pow((x - cx) / 21.0, 2) + Math.pow((z - cz) / 7.0, 2);
                boolean link = x <= 6 && z >= 32 && z <= 36;   // reaches the middle line
                if (d > 1.0 + 0.15 * smooth(x, z, 3, 451) && !link) continue;
                int h = 5 + (int) (smooth(x, z, 4, 452) * 5);
                for (int y = 1; y <= h; y++) c.set(x, y, z, null);
                c.set(x, 0, z, noise(x, z, 453) < 0.6 ? "moss_block" : "clay");
                if (x > 1 && noise(x, z, 454) < 0.04 && !c.isAir(x, h + 1, z)) c.set(x, h, z, "spore_blossom");
            }
        for (int x = 10; x <= 34; x++)
            for (int z = 30; z <= 40; z++) {
                double d = Math.pow((x - cx) / 11.0, 2) + Math.pow((z - cz) / 4.5, 2);
                if (d > 1.0) continue;
                c.set(x, 0, z, "water");
                c.set(x, -1, z, "water");
                c.set(x, -2, z, "clay");
            }
        for (int x = 10; x <= 34; x++) if ("water".equals(c.get(x, 0, cz))) c.set(x, 0, cz, x % 3 == 0 ? "mossy_cobblestone" : "mossy_stone_bricks");
        for (int x = 0; x <= 42; x++)
            for (int z = 27; z <= 43; z++) {
                String floor = c.get(x, 0, z);
                if (floor == null || !floor.equals("moss_block") || !c.isAir(x, 1, z) || x <= 1) continue;
                double n = noise(x, z, 456);
                if (n < 0.25) c.set(x, 1, z, "moss_carpet");
                else if (n < 0.30) c.set(x, 1, z, "azalea");
                else if (n < 0.33) c.set(x, 1, z, "flowering_azalea");
                else if (n < 0.38) c.set(x, 1, z, "short_grass");
            }
    }

    /** Invisible light blocks through every open space (skipping the middle line), so nothing is dark. */
    private static void caveLighting(MapCanvas c) {
        for (int x = 2; x <= CAVE_X; x += 4)
            for (int z = -CAVE_Z; z <= CAVE_Z; z += 4)
                for (int y : new int[]{-4, 2, 6, 10, 14, 18})
                    if (c.isAir(x, y, z) && y < CAVE_TOP - 2) c.set(x, y, z, "light[level=15]");
    }

    // =====================================================================
    // Building pieces shared by the maps
    // =====================================================================

    /** Makes sure every spot in the box has ground (grass, dirt, bedrock, rock) under it. */
    private static void foundation(MapCanvas c, int x1, int x2, int z1, int z2) {
        for (int x = x1; x <= x2; x++)
            for (int z = z1; z <= z2; z++) {
                if (c.get(x, 0, z) != null && c.get(x, -4, z) != null) continue;
                if (c.get(x, 0, z) == null) c.set(x, 0, z, "grass_block");
                for (int y = 1; y <= 3; y++) if (c.get(x, -y, z) == null) c.set(x, -y, z, "dirt");
                c.set(x, -4, z, "bedrock");
                int depth = 2 + (int) (noise(x, z, 501) * 4);
                for (int y = 5; y <= 4 + depth; y++) if (c.get(x, -y, z) == null) c.set(x, -y, z, rock(x, -y, z, 502));
            }
    }

    /** Organic floating island: grass top, mixed dirt, a bedrock layer, rocky underside with roots. */
    private static void island(MapCanvas c, int cx, int cz, int rx, int rz, int top, int depth, int salt) {
        for (int dx = -rx - 1; dx <= rx + 1; dx++) {
            for (int dz = -rz - 1; dz <= rz + 1; dz++) {
                double d = (double) (dx * dx) / (rx * rx) + (double) (dz * dz) / (rz * rz);
                double edge = 1.0 - 0.22 * smooth(cx + dx, cz + dz, 3, salt);
                if (d > edge) continue;
                int x = cx + dx, z = cz + dz;
                double n = noise(x, z, salt + 100);

                String surface = n < 0.06 ? "moss_block" : n < 0.10 ? "coarse_dirt" : "grass_block";
                c.set(x, top, z, surface);
                for (int y = 1; y <= 3; y++) {
                    c.set(x, top - y, z, pick(x, top - y, z, salt, "dirt", "dirt", "dirt", "coarse_dirt", "rooted_dirt"));
                }
                c.set(x, top - 4, z, "bedrock");

                double t = Math.sqrt(d / edge);
                int below = 1 + (int) ((1 - t) * depth + smooth(x, z, 4, salt + 7) * 4);
                int bottom = top - 4 - below;
                for (int y = top - 5; y >= bottom; y--) c.set(x, y, z, rock(x, y, z, salt));
                // Hanging roots near the edges, dripstone tips under the deep middle.
                double r = noise(x, z, salt + 200);
                if (x == 0) continue; // keep the middle line clean
                if (t > 0.7 && r < 0.25) c.set(x, bottom - 1, z, "hanging_roots");
                else if (t < 0.6 && r < 0.12) c.set(x, bottom - 1, z, "pointed_dripstone[vertical_direction=down,thickness=tip]");
            }
        }
    }

    private static void floatingRock(MapCanvas c, int cx, int cy, int cz, int r, int salt) {
        for (int dx = -r; dx <= r; dx++)
            for (int dz = -r; dz <= r; dz++) {
                if (dx * dx + dz * dz > r * r + 1) continue;
                int x = cx + dx, z = cz + dz;
                int thick = 1 + (int) ((r - Math.sqrt(dx * dx + dz * dz)) * 1.5) + (int) (noise(x, z, salt) * 2);
                c.set(x, cy, z, noise(x, z, salt + 1) < 0.5 ? "moss_block" : "grass_block");
                for (int y = 1; y <= thick; y++) c.set(x, cy - y, z, rock(x, cy - y, z, salt));
            }
    }

    private static String rock(int x, int y, int z, int salt) {
        double n = noise3(x, y, z, salt + 31);
        if (n < 0.025) return "coal_ore";
        if (n < 0.035) return "iron_ore";
        if (n < 0.20) return "andesite";
        if (n < 0.30) return "tuff";
        if (n < 0.38) return "cobblestone";
        return "stone";
    }

    private static String wallStone(int x, int y, int z) {
        if (y <= 1) return pick(x, y, z, 51, "mossy_cobblestone", "cobblestone", "mossy_stone_bricks", "stone_bricks");
        return pick(x, y, z, 52, "stone_bricks", "stone_bricks", "stone_bricks", "mossy_stone_bricks", "cracked_stone_bricks", "andesite");
    }

    /** 3-wide, 3-tall opening in a wall, with an accent block above and lanterns either side. */
    private static void gate(MapCanvas c, int x, int z, boolean alongZ) {
        for (int i = -1; i <= 1; i++)
            for (int y = 1; y <= 3; y++) {
                if (alongZ) c.set(x, y, z + i, null);
                else c.set(x + i, y, z, null);
            }
        if (alongZ) {
            c.set(x, 4, z, ACCENT);
            c.set(x, 4, z - 1, "chiseled_stone_bricks");
            c.set(x, 4, z + 1, "chiseled_stone_bricks");
            c.set(x, 7, z - 2, "lantern[hanging=false]");
            c.set(x, 7, z + 2, "lantern[hanging=false]");
            c.set(x, 6, z - 2, "stone_bricks");
            c.set(x, 6, z + 2, "stone_bricks");
        } else {
            c.set(x, 4, z, ACCENT);
            c.set(x - 1, 4, z, "chiseled_stone_bricks");
            c.set(x + 1, 4, z, "chiseled_stone_bricks");
            c.set(x - 2, 6, z, "stone_bricks");
            c.set(x + 2, 6, z, "stone_bricks");
            c.set(x - 2, 7, z, "lantern[hanging=false]");
            c.set(x + 2, 7, z, "lantern[hanging=false]");
        }
    }

    private static void lampPost(MapCanvas c, int x, int z, String fence) {
        c.set(x, 1, z, fence);
        c.set(x, 2, z, fence);
        c.set(x, 3, z, "lantern[hanging=false]");
    }

    private static void watchtowerRuin(MapCanvas c, int cx, int cz) {
        // Hollow 3x3 tower with a ladder inside, broken top.
        for (int dx = -1; dx <= 1; dx++)
            for (int dz = -1; dz <= 1; dz++) {
                int x = cx + dx, z = cz + dz;
                int h = 6 + (int) (noise(x, z, 71) * 3);
                for (int y = 0; y <= h; y++) {
                    if (dx == 0 && dz == 0 && y >= 1) continue; // inside
                    c.set(x, y, z, pick(x, y, z, 72, "mossy_stone_bricks", "cracked_stone_bricks", "stone_bricks", "mossy_cobblestone"));
                }
            }
        // Doorway facing the middle of the map (south side), ladder on the north inner wall.
        c.set(cx, 1, cz + 1, null);
        c.set(cx, 2, cz + 1, null);
        int backH = 6 + (int) (noise(cx, cz - 1, 71) * 3);
        for (int y = 1; y <= backH; y++) c.set(cx, y, cz, "ladder[facing=south]");
        // Rubble around the base.
        c.set(cx + 2, 1, cz - 1, "mossy_cobblestone");
        c.set(cx - 2, 1, cz + 2, "cobblestone");
        c.set(cx + 2, 1, cz + 2, "mossy_cobblestone_slab[type=bottom]");
        c.set(cx - 2, 1, cz - 2, "cracked_stone_bricks");
    }

    private static void cherryGrove(MapCanvas c, int cx, int cz) {
        cherryTree(c, cx + 2, 1, cz + 1);
        // Little pond.
        for (int x = cx - 3; x <= cx - 1; x++)
            for (int z = cz - 2; z <= cz; z++) {
                c.set(x, 0, z, "water");
                c.set(x, -1, z, "clay");
            }
        c.set(cx - 2, 1, cz + 1, "pink_petals[flower_amount=4]");
        c.set(cx, 1, cz - 2, "pink_petals[flower_amount=3]");
    }

    private static void oakTree(MapCanvas c, int x, int y, int z, int h) {
        for (int i = 0; i < h; i++) c.set(x, y + i, z, "oak_log[axis=y]");
        canopy(c, x, y + h - 2, z, 2, "oak_leaves[persistent=true]", 91);
        canopy(c, x, y + h, z, 1, "oak_leaves[persistent=true]", 92);
        c.setIfAir(x, y + h + 1, z, "oak_leaves[persistent=true]");
    }

    private static void birchTree(MapCanvas c, int x, int y, int z, int h) {
        for (int i = 0; i < h; i++) c.set(x, y + i, z, "birch_log[axis=y]");
        canopy(c, x, y + h - 2, z, 2, "birch_leaves[persistent=true]", 93);
        canopy(c, x, y + h, z, 1, "birch_leaves[persistent=true]", 94);
        c.setIfAir(x, y + h + 1, z, "birch_leaves[persistent=true]");
    }

    /** Wide cherry tree with a bent trunk and a big pink canopy. */
    private static void cherryTree(MapCanvas c, int x, int y, int z) {
        for (int i = 0; i < 4; i++) c.set(x, y + i, z, "cherry_log[axis=y]");
        c.set(x + 1, y + 4, z, "cherry_log[axis=x]");
        c.set(x + 2, y + 5, z, "cherry_log[axis=y]");
        c.set(x - 1, y + 4, z, "cherry_log[axis=x]");
        c.set(x - 2, y + 5, z, "cherry_log[axis=y]");
        canopy(c, x, y + 6, z, 4, "cherry_leaves[persistent=true]", 95);
        canopy(c, x, y + 7, z, 3, "cherry_leaves[persistent=true]", 96);
        canopy(c, x, y + 5, z, 2, "cherry_leaves[persistent=true]", 97);
        // Petals drooping from the canopy edge.
        for (int dx = -4; dx <= 4; dx++)
            for (int dz = -4; dz <= 4; dz++)
                if (dx * dx + dz * dz >= 12 && dx * dx + dz * dz <= 17 && noise(x + dx, z + dz, 98) < 0.4)
                    c.setIfAir(x + dx, y + 5, z + dz, "cherry_leaves[persistent=true]");
    }

    private static void spruceTree(MapCanvas c, int x, int y, int z, int h) {
        for (int i = 0; i < h; i++) c.set(x, y + i, z, "spruce_log[axis=y]");
        for (int i = 2; i < h + 1; i++) {
            int r = Math.max(0, (h - i) / 2);
            if (i % 2 == 1 && r > 0) r--;
            canopy(c, x, y + i, z, Math.max(r, i == h ? 0 : 1), "spruce_leaves[persistent=true]", 99 + i);
        }
        c.setIfAir(x, y + h, z, "spruce_leaves[persistent=true]");
        c.setIfAir(x, y + h + 1, z, "spruce_leaves[persistent=true]");
    }

    private static void canopy(MapCanvas c, int x, int y, int z, int r, String leaves, int salt) {
        for (int dx = -r; dx <= r; dx++)
            for (int dz = -r; dz <= r; dz++) {
                int dd = dx * dx + dz * dz;
                if (dd > r * r + 1) continue;
                if (dd >= r * r && noise(x + dx, z + dz, salt) < 0.45) continue; // ragged edge
                c.setIfAir(x + dx, y, z + dz, leaves);
            }
    }

    /** Scatters grass, ferns, flowers and bushes on open grass (never on the x = 0 line or near spawns/cores). */
    private static void decorateGrass(MapCanvas c, int x1, int x2, int z1, int z2, int salt) {
        for (int x = Math.max(1, x1); x <= x2; x++)
            for (int z = z1; z <= z2; z++) {
                if (reserved(x, z)) continue;
                for (int y = 4; y >= -16; y--) {
                    String s = c.get(x, y, z);
                    if (s == null) continue;
                    if (!s.equals("grass_block") || !c.isAir(x, y + 1, z) || !c.isAir(x, y + 2, z)) break;
                    double n = noise(x, z, salt + 300);
                    String plant = null;
                    if (n < 0.12) plant = "short_grass";
                    else if (n < 0.15) plant = "fern";
                    else if (n < 0.165) plant = "poppy";
                    else if (n < 0.18) plant = "dandelion";
                    else if (n < 0.19) plant = "cornflower";
                    else if (n < 0.20) plant = "azure_bluet";
                    else if (n < 0.21) plant = "oxeye_daisy";
                    else if (n < 0.225) plant = "oak_leaves[persistent=true]";
                    if (plant != null) c.set(x, y + 1, z, plant);
                    break;
                }
            }
    }

    /** Two-story timber-frame house: stone base, dark log frame, birch infill, steep roof with overhang. */
    private static void tudorHouse(MapCanvas c, int cx, int cz, int doorSouth) {
        int x1 = cx - 3, x2 = cx + 3, z1 = cz - 3, z2 = cz + 2;
        for (int x = x1; x <= x2; x++)
            for (int z = z1; z <= z2; z++) {
                boolean edge = x == x1 || x == x2 || z == z1 || z == z2;
                boolean corner = (x == x1 || x == x2) && (z == z1 || z == z2);
                c.set(x, 0, z, edge ? "cobblestone" : "spruce_planks");
                for (int y = 1; y <= 7; y++) {
                    String s;
                    if (!edge) s = (y == 4) ? "spruce_planks" : null;      // upper floor
                    else if (corner) s = "dark_oak_log[axis=y]";
                    else if (y <= 2) s = pick(x, y, z, 261, "cobblestone", "stone_bricks", "mossy_cobblestone");
                    else if (y == 3 || y == 7) s = "dark_oak_log[axis=" + (x == x1 || x == x2 ? "z" : "x") + "]";
                    else s = (x + z + y) % 4 == 0 ? "dark_oak_log[axis=y]" : "birch_planks";
                    c.set(x, y, z, s);
                }
            }
        // Windows (glass panes, set into the wall) on both floors.
        for (int x : new int[]{cx - 1, cx + 1}) {
            c.set(x, 2, z1, "glass_pane");
            c.set(x, 5, z1, "glass_pane");
            c.set(x, 5, z2, "glass_pane");
        }
        c.set(x1, 5, cz, "glass_pane");
        c.set(x2, 5, cz, "glass_pane");
        c.set(x2, 2, cz, "glass_pane");
        // Door on the south side (toward the alley), with a lantern.
        c.set(cx, 1, z2, null);
        c.set(cx, 2, z2, null);
        c.set(cx, 3, z2 + 1, "lantern[hanging=true]");
        c.set(cx, 4, z2 + 1, "dark_oak_slab[type=top]");
        // Steep gable roof running east-west, overhanging one block.
        for (int layer = 0; layer <= 4; layer++) {
            int y = 8 + layer;
            int za = z1 - 1 + layer, zb = z2 + 1 - layer;
            for (int x = x1 - 1; x <= x2 + 1; x++) {
                if (za < zb) {
                    c.set(x, y, za, "spruce_stairs[facing=south,half=bottom]");
                    c.set(x, y, zb, "spruce_stairs[facing=north,half=bottom]");
                    for (int z = za + 1; z < zb; z++) if (x == x1 || x == x2) c.set(x, y, z, "birch_planks");
                } else if (za == zb) {
                    c.set(x, y, za, "spruce_slab[type=bottom]");
                }
            }
        }
        // Chimney with a campfire (smoke) on top.
        for (int y = 1; y <= 12; y++) c.set(x2 - 1, y, z1 + 1, y <= 7 ? (y == 4 ? "spruce_planks" : null) : "cobblestone");
        for (int y = 1; y <= 7; y++) c.set(x2 - 1, y, z1 + 1, "cobblestone");
        c.set(x2 - 1, 13, z1 + 1, "campfire[lit=true,facing=north]");
        // Inside: a few things to see through the windows.
        c.set(x1 + 1, 1, z1 + 1, "crafting_table");
        c.set(x1 + 1, 1, z2 - 1, "barrel[facing=up]");
        c.set(x1 + 1, 5, z1 + 1, "lantern[hanging=false]");
        c.set(cx, 1, z1 + 1, "lantern[hanging=false]");
        c.set(x2 - 2, 5, z2 - 1, "potted_poppy");
    }

    /** Small stone cottage with a spruce roof. */
    private static void cottage(MapCanvas c, int cx, int cz) {
        int x1 = cx - 2, x2 = cx + 2, z1 = cz - 2, z2 = cz + 2;
        for (int x = x1; x <= x2; x++)
            for (int z = z1; z <= z2; z++) {
                boolean edge = x == x1 || x == x2 || z == z1 || z == z2;
                boolean corner = (x == x1 || x == x2) && (z == z1 || z == z2);
                c.set(x, 0, z, edge ? "cobblestone" : "oak_planks");
                for (int y = 1; y <= 4; y++) {
                    String s = !edge ? null : corner ? "oak_log[axis=y]"
                            : pick(x, y, z, 271, "cobblestone", "stone_bricks", "mossy_cobblestone", "cobblestone");
                    c.set(x, y, z, s);
                }
            }
        c.set(cx, 2, z1, "glass_pane");
        c.set(x1, 2, cz, "glass_pane");
        c.set(x2, 2, cz, "glass_pane");
        c.set(cx, 1, z2, null);
        c.set(cx, 2, z2, null);
        for (int layer = 0; layer <= 3; layer++) {
            int y = 5 + layer;
            int xa = x1 - 1 + layer, xb = x2 + 1 - layer;
            for (int z = z1 - 1; z <= z2 + 1; z++) {
                if (xa < xb) {
                    c.set(xa, y, z, "spruce_stairs[facing=east,half=bottom]");
                    c.set(xb, y, z, "spruce_stairs[facing=west,half=bottom]");
                    for (int x = xa + 1; x < xb; x++) if (z == z1 || z == z2) c.set(x, y, z, "oak_planks");
                } else if (xa == xb) {
                    c.set(xa, y, z, "spruce_slab[type=bottom]");
                }
            }
        }
        c.set(cx, 1, z1 + 1, "lantern[hanging=false]");
        c.set(cx, 3, z2 + 1, "lantern[hanging=true]");
        c.set(cx, 4, z2 + 1, "spruce_slab[type=top]");
    }

    /** Open-sided smithy: log posts, slab roof, anvil and a forge. */
    private static void smithy(MapCanvas c, int cx, int cz) {
        for (int dx = -2; dx <= 2; dx += 4)
            for (int dz = -2; dz <= 2; dz += 4)
                for (int y = 1; y <= 3; y++) c.set(cx + dx, y, cz + dz, "spruce_log[axis=y]");
        for (int dx = -3; dx <= 3; dx++)
            for (int dz = -3; dz <= 3; dz++) c.set(cx + dx, 4, cz + dz, "spruce_slab[type=bottom]");
        for (int dx = -2; dx <= 2; dx++)
            for (int dz = -2; dz <= 2; dz++) c.set(cx + dx, 0, cz + dz, pick(cx + dx, 0, cz + dz, 281, "cobblestone", "stone_bricks", "gravel"));
        c.set(cx, 1, cz - 1, "anvil[facing=east]");
        c.set(cx - 1, 1, cz - 2, "blast_furnace[facing=south]");
        c.set(cx + 1, 1, cz - 2, "lava_cauldron");
        c.set(cx - 2, 1, cz, "smithing_table");
        c.set(cx, 3, cz, "lantern[hanging=true]");
    }

    private static void wheatField(MapCanvas c, int x1, int z1, int x2, int z2) {
        int midZ = (z1 + z2) / 2;
        for (int x = x1; x <= x2; x++)
            for (int z = z1; z <= z2; z++) {
                boolean border = x == x1 || x == x2 || z == z1 || z == z2;
                if (border) {
                    c.set(x, 0, z, "oak_log[axis=" + (x == x1 || x == x2 ? "z" : "x") + "]");
                    continue;
                }
                if (z == midZ) {
                    c.set(x, 0, z, "water");
                    continue;
                }
                c.set(x, 0, z, "farmland[moisture=7]");
                int age = 5 + (int) (noise(x, z, 291) * 3);
                c.set(x, 1, z, (x % 5 == 0 ? "carrots" : "wheat") + "[age=" + Math.min(7, age) + "]");
            }
    }

    private static void orchard(MapCanvas c, int x1, int z1, int x2, int z2) {
        for (int x = x1; x <= x2; x++)
            for (int z = z1; z <= z2; z++)
                if (noise(x, z, 301) < 0.3) c.set(x, 0, z, "podzol");
        oakTree(c, x1 + 1, 1, z1 + 1, 4);
        oakTree(c, x2 - 1, 1, z1 + 3, 5);
        oakTree(c, x1 + 2, 1, z2 - 2, 5);
        birchTree(c, x2 - 1, 1, z2 - 1, 6);
        for (int i = 0; i < 3; i++) c.set(x1 + 3 + i, 1, z1 + 5, "flowering_azalea_leaves[persistent=true]");
    }

    private static void scarecrow(MapCanvas c, int x, int z) {
        c.set(x, 1, z, "oak_fence");
        c.set(x, 2, z, "hay_block[axis=y]");
        c.set(x, 3, z, "carved_pumpkin[facing=west]");
        c.set(x, 1, z, "oak_fence");
    }

    /** 3x3 wooden watchtower with a ladder inside, a railed lookout at y 6 and a roof. */
    private static void watchtower(MapCanvas c, int cx, int cz) {
        for (int dx = -1; dx <= 1; dx += 2)
            for (int dz = -1; dz <= 1; dz += 2)
                for (int y = 1; y <= 9; y++) c.set(cx + dx, y, cz + dz, "spruce_log[axis=y]");
        for (int dx = -1; dx <= 1; dx++)
            for (int dz = -1; dz <= 1; dz++) {
                c.set(cx + dx, 6, cz + dz, "spruce_planks");
                c.set(cx + dx, 0, cz + dz, "cobblestone");
            }
        // Solid plank face toward the farmstead with a ladder up the outside.
        int s = cz < 0 ? 1 : -1;
        for (int y = 1; y <= 5; y++) c.set(cx, y, cz + s, "spruce_planks");
        int lz = cz + 2 * s;
        String facing = s > 0 ? "south" : "north";
        for (int y = 1; y <= 6; y++) c.set(cx, y, lz, "ladder[facing=" + facing + "]");
        // Railings and roof.
        for (int dx = -1; dx <= 1; dx++)
            for (int dz = -1; dz <= 1; dz++)
                if ((Math.abs(dx) == 1 || Math.abs(dz) == 1) && !(dx == -1 && dz == -1) && !(dx == 1 && dz == 1)
                        && !(dx == -1 && dz == 1) && !(dx == 1 && dz == -1))
                    c.set(cx + dx, 7, cz + dz, "spruce_fence");
        c.set(cx, 7, cz + (cz < 0 ? 1 : -1), null);
        for (int dx = -2; dx <= 2; dx++)
            for (int dz = -2; dz <= 2; dz++) c.set(cx + dx, 10, cz + dz, "spruce_slab[type=bottom]");
        c.set(cx, 10, cz, "spruce_planks");
        c.set(cx, 9, cz, "lantern[hanging=true]");
    }

    private static void stall(MapCanvas c, int x, int z) {
        for (int dx = -1; dx <= 1; dx += 2)
            for (int dz = -1; dz <= 1; dz += 2)
                for (int y = 1; y <= 2; y++) c.set(x + dx, y, z + dz, "spruce_fence");
        for (int dx = -2; dx <= 2; dx++)
            for (int dz = -2; dz <= 2; dz++) c.set(x + dx, 3, z + dz, "spruce_slab[type=bottom]");
        c.set(x, 3, z, "spruce_planks");
        c.set(x, 1, z, "barrel[facing=up]");
        c.set(x - 1, 1, z, "melon");
        c.set(x + 1, 1, z, "pumpkin");
        c.set(x, 2, z - 1, "lantern[hanging=true]");
    }

    private static String caveRock(int x, int y, int z) {
        double n = noise3(x, y, z, 411);
        if (y < 3) {
            if (n < 0.03) return "deepslate_iron_ore";
            if (n < 0.35) return "deepslate";
            if (n < 0.45) return "tuff";
        }
        if (n < 0.025) return "coal_ore";
        if (n < 0.035) return "iron_ore";
        if (n < 0.04) return "copper_ore";
        if (n < 0.20) return "andesite";
        if (n < 0.28) return "tuff";
        if (n < 0.33) return "cobblestone";
        return "stone";
    }

    /** Earth mound with terraces you can walk up, topped with hay and a lantern: overlooks the south gate. */
    private static void lookoutHill(MapCanvas c, int cx, int cz) {
        for (int dx = -5; dx <= 5; dx++)
            for (int dz = -5; dz <= 5; dz++) {
                double d = Math.sqrt(dx * dx + dz * dz) + noise(cx + dx, cz + dz, 711) * 0.8;
                int h = d < 2 ? 3 : d < 3.5 ? 2 : d < 5 ? 1 : 0;
                if (h == 0) continue;
                for (int y = 0; y < h; y++) c.set(cx + dx, y, cz + dz, "dirt");
                c.set(cx + dx, h, cz + dz, noise(cx + dx, cz + dz, 712) < 0.2 ? "coarse_dirt" : "grass_block");
            }
        c.set(cx, 4, cz, "hay_block[axis=y]");
        c.set(cx + 1, 4, cz, "hay_block[axis=x]");
        c.set(cx - 1, 4, cz + 1, "spruce_fence");
        c.set(cx - 1, 5, cz + 1, "lantern[hanging=false]");
    }
}
