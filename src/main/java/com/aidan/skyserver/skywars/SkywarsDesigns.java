package com.aidan.skyserver.skywars;

import com.aidan.skyserver.goldwars.MapCanvas;

import java.util.*;

import static com.aidan.skyserver.goldwars.MapCanvas.noise;
import static com.aidan.skyserver.goldwars.MapCanvas.noise3;
import static com.aidan.skyserver.goldwars.MapCanvas.smooth;

/**
 * The Skywars maps, drawn as plain Java (no Minecraft classes) so they can be previewed offline.
 * Coordinates are relative to the map centre; y = 0 is the top of the spawn islands.
 *
 * Layout (the same for every map, so it stays fair; what changes is the look):
 *  - 8 spawn islands in a ring 44 blocks from the middle, each with 3 chests (one by the spawn,
 *    one inside a small building, one up on a ledge) and some ore to mine for extra blocks.
 *  - 4 mid islands between each pair of spawns and the centre, 2 better chests each. Every spawn
 *    is ~12 blocks of bridging from its mid island, ~21 from the next spawn.
 *  - A big centre island with two floors, cover from arrows and 6 of the best chests.
 * What makes a good Skywars map (and what this follows): equal distances for everyone, a clear
 * landmark in the middle, height changes and cover so bows don't win everything, a choice of
 * routes (straight to the middle, via a mid island, or to a neighbour), islands you can mine for
 * blocks, and enough detail under the islands that falling looks as scary as it is.
 */
public final class SkywarsDesigns {

    public enum Theme {
        RUINS("ruins", "Overgrown Ruins"),
        EMBER("ember", "Ember Isles");

        public final String id, display;

        Theme(String id, String display) {
            this.id = id;
            this.display = display;
        }
    }

    public static final int SPAWNS = 8;
    public static final int SPAWN_RING = 44, MID_RING = 25;

    /** Everything the game needs to know about a built map (relative to the centre). */
    public static final class Layout {
        public final MapCanvas canvas = new MapCanvas();
        public final List<int[]> spawns = new ArrayList<>();               // feet positions
        public final List<List<int[]>> islandChests = new ArrayList<>();   // per spawn
        public final List<int[]> midChests = new ArrayList<>();
        public final List<int[]> centerChests = new ArrayList<>();
        public int[] supplyDrop = {0, 1, 0};
        public int[] spectator = {0, 20, 0};
        public final Theme theme;

        Layout(Theme theme) {
            this.theme = theme;
        }
    }

    /** Block palette for a theme. */
    private record Palette(String top, String soil, String stone, String stone2, String accent, String accentStairs,
                           String accentSlab, String accentWall, String pillar, String glass, String light, String plank,
                           String plankStairs, String log, String leaves, String[] plants, String[] ores) {}

    private static Palette palette(Theme t) {
        return switch (t) {
            case RUINS -> new Palette("grass_block", "dirt", "stone", "andesite", "mossy_stone_bricks", "mossy_stone_brick_stairs",
                    "mossy_stone_brick_slab", "mossy_stone_brick_wall", "chiseled_stone_bricks", "glass", "lantern", "oak_planks",
                    "oak_stairs", "oak_log", "oak_leaves[persistent=true]",
                    new String[]{"short_grass", "short_grass", "fern", "poppy", "dandelion", "cornflower", "azure_bluet"},
                    new String[]{"coal_ore", "coal_ore", "iron_ore", "copper_ore", "gold_ore"});
            case EMBER -> new Palette("crimson_nylium", "netherrack", "blackstone", "basalt[axis=y]", "polished_blackstone_bricks",
                    "polished_blackstone_brick_stairs", "polished_blackstone_brick_slab", "polished_blackstone_brick_wall",
                    "chiseled_polished_blackstone", "red_stained_glass", "soul_lantern", "crimson_planks", "crimson_stairs",
                    "crimson_stem[axis=y]", "nether_wart_block",
                    new String[]{"crimson_roots", "crimson_roots", "crimson_fungus", "nether_sprouts"},
                    new String[]{"nether_quartz_ore", "nether_gold_ore", "nether_gold_ore", "gilded_blackstone", "coal_ore"});
        };
    }

    private SkywarsDesigns() {}

    public static Layout build(Theme theme) {
        Layout L = new Layout(theme);
        Palette p = palette(theme);
        int salt = theme.ordinal() * 1000;

        // Spawn islands. Each one's "front" faces the middle; the building is to one side.
        for (int i = 0; i < SPAWNS; i++) {
            double a = Math.PI * 2 * i / SPAWNS;
            int cx = (int) Math.round(Math.cos(a) * SPAWN_RING), cz = (int) Math.round(Math.sin(a) * SPAWN_RING);
            // Direction toward the middle, snapped to the grid (for building facing).
            int fx = (int) -Math.signum(Math.round(Math.cos(a) * 10) / 10.0), fz = (int) -Math.signum(Math.round(Math.sin(a) * 10) / 10.0);
            L.spawns.add(new int[]{cx, 1, cz});
            L.islandChests.add(spawnIsland(L.canvas, p, cx, cz, fx, fz, salt + i * 37, theme));
        }
        // Mid islands, between spawn 2k and 2k+1.
        for (int k = 0; k < 4; k++) {
            double a = Math.PI * 2 * (2 * k + 0.5) / SPAWNS;
            int cx = (int) Math.round(Math.cos(a) * MID_RING), cz = (int) Math.round(Math.sin(a) * MID_RING);
            midIsland(L, p, cx, cz, salt + 500 + k * 13, theme);
        }
        // Small rocks halfway between neighbouring spawns: a stepping stone (with a little ore to
        // mine) so going for your neighbour costs fewer blocks than it looks.
        for (int i = 0; i < SPAWNS; i++) {
            double a = Math.PI * 2 * (i + 0.5) / SPAWNS;
            int cx = (int) Math.round(Math.cos(a) * (SPAWN_RING + 1)), cz = (int) Math.round(Math.sin(a) * (SPAWN_RING + 1));
            islet(L.canvas, p, cx, cz, -1, salt + 700 + i, theme);
        }
        centerIsland(L, p, salt + 900, theme);
        L.canvas.connectFencesAndPanes();
        return L;
    }

    // =====================================================================
    // Terrain
    // =====================================================================

    /**
     * A floating island: a lumpy blob with a flat top at y = top, hanging down in a rocky cone.
     * Returns the set of top positions (packed) so decorations can sit on it.
     */
    private static void blob(MapCanvas c, Palette p, int cx, int cz, double radius, int top, int depth, int salt) {
        int r = (int) Math.ceil(radius + 3);
        for (int dx = -r; dx <= r; dx++)
            for (int dz = -r; dz <= r; dz++) {
                int x = cx + dx, z = cz + dz;
                double ang = Math.atan2(dz, dx);
                double edge = radius + (smooth((int) (ang * 40) + 400, salt, 9, salt) - 0.5) * 3.0;
                double d = Math.hypot(dx, dz);
                if (d > edge) continue;
                double inside = (edge - d) / edge; // 1 at the middle, 0 at the edge
                int hang = 2 + (int) Math.round(inside * depth + (noise(x, z, salt + 3) - 0.5) * 3 * inside);
                for (int y = top; y >= top - hang; y--) {
                    int below = top - y;
                    String s;
                    if (below == 0) s = p.top();
                    else if (below <= 2 && inside > 0.15) s = p.soil();
                    else {
                        s = noise3(x, y, z, salt + 5) < 0.3 ? p.stone2() : p.stone();
                        if (below >= 3 && noise3(x, y, z, salt + 7) < 0.07) s = p.ores()[(int) (noise3(x, y, z, salt + 8) * p.ores().length)];
                    }
                    c.set(x, y, z, s);
                }
            }
    }

    /** A few stalactites under an island so it doesn't look flat underneath. */
    private static void drips(MapCanvas c, Palette p, int cx, int cz, double radius, int top, int salt, Theme t) {
        for (int k = 0; k < 4; k++) {
            int x = cx + (int) ((noise(k, salt, 11) - 0.5) * radius * 1.2);
            int z = cz + (int) ((noise(k, salt, 12) - 0.5) * radius * 1.2);
            int y = top;
            while (y > top - 40 && c.get(x, y - 1, z) != null) y--;
            int len = 3 + (int) (noise(k, salt, 13) * 5);
            for (int i = 0; i < len; i++) c.set(x, y - 1 - i, z, i == len - 1 && t == Theme.EMBER ? "glowstone" : (i < len / 2 ? p.stone() : p.stone2()));
            if (t == Theme.RUINS && k % 2 == 0) for (int i = 0; i < 3; i++) c.setIfAir(x + 1, y - 1 - i, z, "hanging_roots");
        }
    }

    private static void plants(MapCanvas c, Palette p, int cx, int cz, int r, int top, int salt, double chance) {
        for (int dx = -r; dx <= r; dx++)
            for (int dz = -r; dz <= r; dz++) {
                int x = cx + dx, z = cz + dz;
                if (!p.top().equals(c.get(x, top, z)) || !c.isAir(x, top + 1, z)) continue;
                if (noise(x, z, salt + 21) > chance) continue;
                c.set(x, top + 1, z, p.plants()[(int) (noise(x, z, salt + 22) * p.plants().length)]);
            }
    }

    private static void tree(MapCanvas c, Palette p, int x, int y, int z, int h, Theme t) {
        for (int i = 0; i < h; i++) c.set(x, y + i, z, p.log());
        int ly = y + h - 2;
        for (int dy = 0; dy <= 2; dy++) {
            int r = dy == 2 ? 1 : 2;
            for (int dx = -r; dx <= r; dx++)
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.abs(dx) == r && Math.abs(dz) == r && noise(x + dx, z + dz, ly + dy) < 0.6) continue;
                    c.setIfAir(x + dx, ly + dy, z + dz, p.leaves());
                }
        }
        c.setIfAir(x, y + h + 1, z, p.leaves());
        if (t == Theme.EMBER) {
            c.setIfAir(x + 2, ly - 1, z, "shroomlight");
            c.setIfAir(x - 1, ly - 1, z + 2, "weeping_vines");
        }
    }

    // =====================================================================
    // Spawn island
    // =====================================================================

    /** Returns the chest positions (by the spawn, in the hut, on the ledge). */
    private static List<int[]> spawnIsland(MapCanvas c, Palette p, int cx, int cz, int fx, int fz, int salt, Theme t) {
        blob(c, p, cx, cz, 6.5, 0, 9, salt);
        drips(c, p, cx, cz, 6.5, 0, salt, t);
        // Use one of the two axes as "sideways" (perpendicular to the way to the middle).
        int sx = fz != 0 ? 1 : 0, sz = fz != 0 ? 0 : 1;
        if (fx != 0 && fz != 0) { sx = -fz; sz = fx; } // diagonal islands: perpendicular diagonal
        sx = Integer.signum(sx);
        sz = Integer.signum(sz);
        // Hut: 4x4 footprint behind and to one side of the spawn.
        int hx = cx - fx * 3 + sx * 3, hz = cz - fz * 3 + sz * 3;
        hut(c, p, hx, hz, t);
        // A raised ledge on the other side with stairs up (and a chest on top).
        int lx = cx - fx * 2 - sx * 3, lz = cz - fz * 2 - sz * 3;
        for (int dx = -1; dx <= 1; dx++)
            for (int dz = -1; dz <= 1; dz++) {
                c.set(lx + dx, 1, lz + dz, p.stone());
                c.set(lx + dx, 2, lz + dz, dx == 0 && dz == 0 ? p.pillar() : p.accent());
            }
        // Stair block up to the ledge (toward the spawn).
        c.set(lx + fx * 2, 1, lz + fz * 2, p.accent());
        c.set(lx + fx * 2, 2, lz + fz * 2, null);
        c.set(lx + fx * 3, 1, lz + fz * 3, p.accentSlab() + "[type=bottom]");
        int[] ledgeChest = {lx, 3, lz};
        c.set(lx - fx, 3, lz - fz, p.light());
        // Ore vein on the outside edge: worth mining for blocks.
        int ox = cx - fx * 5 - sx, oz = cz - fz * 5 - sz;
        for (int i = 0; i < 4; i++) {
            int x = ox + (i % 2) * sx, z = oz + (i % 2) * sz;
            int y = -1 - i / 2;
            if (c.get(x, y, z) != null) c.set(x, y, z, p.ores()[(salt + i) % p.ores().length]);
        }
        // Chest by the spawn, a tree, and plants.
        int[] spawnChest = {cx - fx * 2 + sx, 1, cz - fz * 2 + sz};
        tree(c, p, cx + fx * 3 - sx * 4, 1, cz + fz * 3 - sz * 4, 4, t);
        props(c, p, cx + fx * 2 + sx * 3, cz + fz * 2 + sz * 3, fx, fz, t, salt);
        plants(c, p, cx, cz, 7, 0, salt, 0.14);
        // Clear the cage spot and the chests' spots.
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) for (int y = 1; y <= 3; y++) c.set(cx + dx, y, cz + dz, null);
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) c.set(cx + dx, 0, cz + dz, p.top());
        c.set(spawnChest[0], 1, spawnChest[2], null);
        int[] hutChest = {hx, 1, hz};
        return List.of(spawnChest, hutChest, ledgeChest);
    }

    /** A small open-fronted hut (3x3 inside) with a chest in it. */
    private static void hut(MapCanvas c, Palette p, int hx, int hz, Theme t) {
        for (int dx = -2; dx <= 2; dx++)
            for (int dz = -2; dz <= 2; dz++) {
                int x = hx + dx, z = hz + dz;
                boolean wall = Math.abs(dx) == 2 || Math.abs(dz) == 2;
                boolean corner = Math.abs(dx) == 2 && Math.abs(dz) == 2;
                c.set(x, 0, z, p.plank());
                for (int y = 1; y <= 3; y++) {
                    String s = null;
                    if (corner) s = p.log();
                    else if (wall) {
                        boolean doorway = (dx == 0 || dz == 0) && y <= 2; // openings on all four sides
                        boolean window = y == 2 && (Math.abs(dx) == 1 || Math.abs(dz) == 1);
                        boolean ruined = t == Theme.RUINS && y == 3 && noise(x, z, 77) < 0.4;
                        s = doorway ? null : window ? p.glass().replace("glass", "glass_pane").replace("stained_glass_pane", "stained_glass_pane") : ruined ? null : p.accent();
                    }
                    c.set(x, y, z, s);
                }
                c.set(x, 4, z, wall ? p.accentSlab() + "[type=bottom]" : p.plank());
            }
        c.set(hx, 3, hz, p.light().equals("lantern") ? "lantern[hanging=true]" : "soul_lantern[hanging=true]");
    }

    /** A tiny 3-4 block wide floating rock with an ore and a bit of decoration. */
    private static void islet(MapCanvas c, Palette p, int cx, int cz, int top, int salt, Theme t) {
        blob(c, p, cx, cz, 2.2, top, 4, salt);
        c.set(cx, top - 1, cz, p.ores()[salt % p.ores().length]);
        if (t == Theme.RUINS) {
            c.set(cx, top + 1, cz, "mossy_cobblestone_wall");
            c.set(cx, top + 2, cz, "lantern");
        } else {
            c.set(cx, top + 1, cz, "basalt[axis=y]");
            c.set(cx, top + 2, cz, "soul_lantern");
        }
    }

    /** Small details that make a spawn island feel lived in. */
    private static void props(MapCanvas c, Palette p, int x, int z, int fx, int fz, Theme t, int salt) {
        if (!c.isAir(x, 1, z) || c.get(x, 0, z) == null) return;
        if (t == Theme.RUINS) {
            c.set(x, 1, z, "barrel[facing=up]");
            if (c.isAir(x + fz, 1, z + fx) && c.get(x + fz, 0, z + fx) != null) c.set(x + fz, 1, z + fx, "composter");
            // A broken column.
            int bx = x - fx * 2, bz = z - fz * 2;
            if (c.get(bx, 0, bz) != null && c.isAir(bx, 1, bz)) {
                c.set(bx, 1, bz, "mossy_stone_bricks");
                c.set(bx, 2, bz, "cracked_stone_bricks");
                c.set(bx, 3, bz, "mossy_stone_brick_slab[type=bottom]");
            }
        } else {
            c.set(x, 1, z, "basalt[axis=y]");
            c.set(x, 2, z, "basalt[axis=y]");
            if (c.isAir(x + fz, 1, z + fx) && c.get(x + fz, 0, z + fx) != null) c.set(x + fz, 1, z + fx, "magma_block");
            c.set(x, 3, z, "iron_chain[axis=y]");
            c.set(x, 4, z, "soul_lantern[hanging=true]");
            c.set(x, 5, z, "polished_blackstone_slab[type=bottom]");
        }
    }

    // =====================================================================
    // Mid islands
    // =====================================================================

    private static void midIsland(Layout L, Palette p, int cx, int cz, int salt, Theme t) {
        MapCanvas c = L.canvas;
        blob(c, p, cx, cz, 5.5, 2, 8, salt);
        drips(c, p, cx, cz, 5.5, 2, salt, t);
        // A little shrine: four pillars, a slab roof, two chests in the middle.
        for (int dx = -2; dx <= 2; dx += 4)
            for (int dz = -2; dz <= 2; dz += 4)
                for (int y = 3; y <= 6; y++) c.set(cx + dx, y, cz + dz, y == 6 ? p.pillar() : p.accentWall());
        for (int dx = -2; dx <= 2; dx++)
            for (int dz = -2; dz <= 2; dz++) {
                c.set(cx + dx, 7, cz + dz, Math.abs(dx) == 2 || Math.abs(dz) == 2 ? p.accentSlab() + "[type=bottom]" : p.accent());
                c.set(cx + dx, 2, cz + dz, Math.abs(dx) <= 1 && Math.abs(dz) <= 1 ? p.pillar() : p.accent());
            }
        c.set(cx, 6, cz, p.light().equals("lantern") ? "lantern[hanging=true]" : "soul_lantern[hanging=true]");
        L.midChests.add(new int[]{cx - 1, 3, cz});
        L.midChests.add(new int[]{cx + 1, 3, cz});
        plants(c, p, cx, cz, 6, 2, salt, 0.12);
        for (int[] ch : L.midChests) c.set(ch[0], ch[1], ch[2], null);
    }

    // =====================================================================
    // Centre island
    // =====================================================================

    private static void centerIsland(Layout L, Palette p, int salt, Theme t) {
        MapCanvas c = L.canvas;
        int top = 1;
        blob(c, p, 0, 0, 13.5, top, 16, salt);
        drips(c, p, 0, 0, 13.5, top, salt, t);
        drips(c, p, 0, 0, 10, top, salt + 1, t);
        // Paved courtyard ring and a plus-shaped walkway.
        for (int dx = -9; dx <= 9; dx++)
            for (int dz = -9; dz <= 9; dz++) {
                double d = Math.hypot(dx, dz);
                if ((d <= 9 && d >= 7.5) || Math.abs(dx) <= 1 || Math.abs(dz) <= 1)
                    if (c.get(dx, top, dz) != null) c.set(dx, top, dz, noise(dx, dz, salt + 31) < 0.3 ? p.accent() : p.pillar().startsWith("chiseled_stone") ? "stone_bricks" : "polished_blackstone");
            }
        // Central tower: 5x5, two floors, open sides with half walls for cover.
        for (int dx = -2; dx <= 2; dx++)
            for (int dz = -2; dz <= 2; dz++) {
                boolean edge = Math.abs(dx) == 2 || Math.abs(dz) == 2;
                boolean corner = Math.abs(dx) == 2 && Math.abs(dz) == 2;
                for (int y = top + 1; y <= top + 9; y++) {
                    String s = null;
                    if (corner) s = y == top + 9 ? p.light() : p.pillar();
                    else if (y == top + 5) s = p.accent();                                  // first floor
                    else if (edge && (y == top + 6)) s = p.accentWall();                     // half wall up top
                    else if (edge && y == top + 1 && (dx == 0 || dz == 0)) s = null;         // doorways
                    else if (edge && y == top + 1) s = p.accentWall();
                    c.set(dx, y, dz, s);
                }
            }
        // Ladder up the inside of the tower to the first floor (through a hole).
        c.set(0, top + 5, 1, null);
        for (int y = top + 1; y <= top + 5; y++) c.set(0, y, 2, null);
        for (int y = top + 1; y <= top + 5; y++) c.set(0, y, 1, "ladder[facing=north]");
        c.set(0, top + 1, 2, p.accent());
        c.set(0, top + 2, 2, p.accent());
        // A beacon of light on top (the landmark).
        c.set(0, top + 6, 0, p.accent());
        c.set(0, top + 7, 0, t == Theme.EMBER ? "crying_obsidian" : "sea_lantern");
        // Four little cover walls / ruins around the ring.
        for (int q = 0; q < 4; q++) {
            int ax = q == 0 ? 6 : q == 1 ? -6 : 0, az = q == 2 ? 6 : q == 3 ? -6 : 0;
            int px = az != 0 ? 1 : 0, pz = ax != 0 ? 1 : 0;
            for (int k = -2; k <= 2; k++) {
                if (k == 0) continue;
                int h = Math.abs(k) == 2 ? 1 : 2;
                for (int y = top + 1; y <= top + h; y++) c.set(ax + px * k + Integer.signum(ax) * 2, y, az + pz * k + Integer.signum(az) * 2, p.accent());
            }
        }
        // Broken bridge stubs reaching toward each mid island (they show the way and save a few blocks).
        for (int k = 0; k < 4; k++) {
            double a = Math.PI * 2 * (2 * k + 0.5) / SPAWNS;
            double ux = Math.cos(a), uz = Math.sin(a);
            for (double d = 12; d <= 16; d += 0.5) {
                int x = (int) Math.round(ux * d), z = (int) Math.round(uz * d);
                if (c.get(x, top, z) == null || d > 13.5) {
                    if (d > 13.5 + noise(k, 0, salt + 41) * 2.5) break;
                    c.set(x, top, z, p.accentSlab() + "[type=top]");
                }
            }
            // An arch at the edge of the centre island on the way to the mid island.
            int ax = (int) Math.round(ux * 11), az = (int) Math.round(uz * 11);
            int px = Math.abs(uz) > Math.abs(ux) ? 1 : 0, pz = 1 - px;
            for (int y = top + 1; y <= top + 4; y++) {
                c.set(ax + px * 2, y, az + pz * 2, y == top + 4 ? p.accentStairs() : p.accent());
                c.set(ax - px * 2, y, az - pz * 2, y == top + 4 ? p.accentStairs() : (t == Theme.RUINS && y == top + 3 ? "cracked_stone_bricks" : p.accent()));
            }
            c.set(ax, top + 4, az, p.accentSlab() + "[type=top]");
            c.set(ax + px, top + 4, az + pz, p.accent());
            c.set(ax - px, top + 4, az - pz, p.accent());
            c.set(ax, top + 3, az, p.light().equals("lantern") ? "lantern[hanging=true]" : "soul_lantern[hanging=true]");
        }
        // Trees at the diagonals.
        for (int q = 0; q < 4; q++) {
            int x = (q % 2 == 0 ? 1 : -1) * 8, z = (q < 2 ? 1 : -1) * 8;
            if (c.get(x, top, z) != null) tree(c, p, x, top + 1, z, 5, t);
        }
        // Chests: 4 on the ground floor around the tower, 2 up on the first floor.
        int[][] ground = {{3, top + 1, 3}, {-3, top + 1, -3}, {3, top + 1, -3}, {-3, top + 1, 3}};
        for (int[] g : ground) L.centerChests.add(g);
        L.centerChests.add(new int[]{1, top + 6, -1});
        L.centerChests.add(new int[]{-1, top + 6, 1});
        plants(c, p, 0, 0, 14, top, salt, 0.08);
        for (int[] ch : L.centerChests) c.set(ch[0], ch[1], ch[2], null);
        L.supplyDrop = new int[]{0, top + 1, 0};
        L.spectator = new int[]{0, top + 22, 0};
        // Lights around the ring.
        for (int q = 0; q < 8; q++) {
            double a = Math.PI * 2 * q / 8 + Math.PI / 8;
            int x = (int) Math.round(Math.cos(a) * 10), z = (int) Math.round(Math.sin(a) * 10);
            if (c.get(x, top, z) == null || !c.isAir(x, top + 1, z)) continue;
            c.set(x, top + 1, z, p.accentWall());
            c.set(x, top + 2, z, p.light());
        }
    }
}
