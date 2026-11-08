package com.aidan.skyserver.island;

import com.aidan.skyserver.goldwars.MapCanvas;

import static com.aidan.skyserver.goldwars.MapCanvas.noise;
import static com.aidan.skyserver.goldwars.MapCanvas.noise3;
import static com.aidan.skyserver.goldwars.MapCanvas.smooth;

/**
 * The starter Sky Island, drawn as plain Java (previewable offline). Relative to the island centre;
 * y = 0 is the grass. About 17 blocks across:
 *  - a little oak house (crafting table, furnace, bed) with the surface chest out front,
 *  - a wheat plot with water, a tree, and the Island Generator (a lodestone that keeps making
 *    cobblestone, and better ores as you upgrade it),
 *  - a trapdoor and ladder down into a small cave with exposed ores and the second chest.
 */
public final class IslandDesign {
    /** Where new players appear (feet), and the chests. */
    public static final int[] SPAWN = {0, 1, 3};
    public static final int[] SURFACE_CHEST = {-1, 1, 1};
    public static final int[] CAVE_CHEST = {-5, -5, -1};
    public static final int[] GENERATOR = {5, 0, 3};   // the lodestone; cobblestone appears on top of it

    /** The little second island: its centre relative to the main island (a 25-block gap east). */
    public static final int[] ISLET = {40, 0, 0};
    public static final double ISLET_RADIUS = 5.5;
    /** Chest on the little island (relative to the main island centre). */
    public static final int[] ISLET_CHEST = {40, 1, -1};
    /** Captain Wren stands here on the main island (feet), facing the path. */
    public static final double[] WREN = {2.5, 1, 2.5, 90};

    private IslandDesign() {}

    /** The little island, relative to its own centre (y = 0 is the grass). */
    public static MapCanvas buildIslet() {
        MapCanvas c = new MapCanvas();
        int salt = 777;
        for (int dx = -7; dx <= 7; dx++)
            for (int dz = -7; dz <= 7; dz++) {
                double ang = Math.atan2(dz, dx);
                double edge = ISLET_RADIUS + (smooth((int) (ang * 20) + 50, 3, 5, salt) - 0.5) * 1.5;
                double d = Math.hypot(dx, dz);
                if (d > edge) continue;
                double inside = (edge - d) / edge;
                int hang = 2 + (int) Math.round(inside * 7 + (noise(dx, dz, salt + 1) - 0.5) * 2 * inside);
                for (int y = 0; y >= -hang; y--) {
                    int below = -y;
                    String st = below == 0 ? (noise(dx, dz, salt + 5) < 0.3 ? "moss_block" : "grass_block") : below <= 1 ? "dirt"
                            : noise3(dx, y, dz, salt + 2) < 0.3 ? "tuff" : "stone";
                    if (below >= 3 && noise3(dx, y, dz, salt + 3) < 0.07) st = noise3(dx, y, dz, salt + 4) < 0.5 ? "copper_ore" : "iron_ore";
                    c.set(dx, y, dz, st);
                }
            }
        // A broken mast from Captain Wren's old airship, with a lantern.
        for (int y = 1; y <= 5; y++) c.set(2, y, 2, "stripped_spruce_log[axis=y]");
        c.set(3, 4, 2, "spruce_fence");
        c.set(4, 4, 2, "spruce_fence");
        c.set(4, 3, 2, "lantern[hanging=true]");
        c.set(1, 5, 2, "white_wool");
        c.set(1, 4, 2, "white_wool");
        c.set(-2, 1, 2, "barrel[facing=up]");
        // A small azalea and some flowers.
        c.set(-2, 1, -2, "flowering_azalea");
        c.set(-3, 1, 0, "poppy");
        c.set(1, 1, -3, "dandelion");
        // The chest spot (filled by the game).
        c.set(ISLET_CHEST[0] - ISLET[0], 1, ISLET_CHEST[2] - ISLET[2], null);
        c.connectFencesAndPanes();
        return c;
    }

    public static MapCanvas build() {
        MapCanvas c = new MapCanvas();
        int salt = 4242;
        // Island body: a lumpy disc that hangs down into a rocky point.
        double radius = 8.5;
        for (int dx = -11; dx <= 11; dx++)
            for (int dz = -11; dz <= 11; dz++) {
                double ang = Math.atan2(dz, dx);
                double edge = radius + (smooth((int) (ang * 30) + 300, 7, 7, salt) - 0.5) * 2.5;
                double d = Math.hypot(dx, dz);
                if (d > edge) continue;
                double inside = (edge - d) / edge;
                int hang = 3 + (int) Math.round(inside * 11 + (noise(dx, dz, salt + 1) - 0.5) * 3 * inside);
                for (int y = 0; y >= -hang; y--) {
                    int below = -y;
                    String s;
                    if (below == 0) s = "grass_block";
                    else if (below <= 2) s = "dirt";
                    else {
                        s = noise3(dx, y, dz, salt + 2) < 0.25 ? "andesite" : "stone";
                        if (noise3(dx, y, dz, salt + 3) < 0.05) s = noise3(dx, y, dz, salt + 4) < 0.6 ? "coal_ore" : "iron_ore";
                    }
                    c.set(dx, y, dz, s);
                }
            }

        // House: 5x5 oak cabin north-east of the spawn, door facing south.
        int hx = 3, hz = -4;
        for (int dx = -2; dx <= 2; dx++)
            for (int dz = -2; dz <= 2; dz++) {
                int x = hx + dx, z = hz + dz;
                boolean wall = Math.abs(dx) == 2 || Math.abs(dz) == 2;
                boolean corner = Math.abs(dx) == 2 && Math.abs(dz) == 2;
                c.set(x, 0, z, "oak_planks");
                for (int y = 1; y <= 3; y++) {
                    String s = null;
                    if (corner) s = "oak_log[axis=y]";
                    else if (wall) s = (y == 2 && (dx == 0 || dz == 0) && dz != 2) ? "glass_pane" : "oak_planks";
                    c.set(x, y, z, s);
                }
                c.set(x, 4, z, wall ? "oak_slab[type=bottom]" : "oak_planks");
            }
        c.set(hx, 1, hz + 2, "oak_door[facing=south,half=lower,hinge=left]");
        c.set(hx, 2, hz + 2, "oak_door[facing=south,half=upper,hinge=left]");
        c.set(hx - 1, 1, hz - 1, "crafting_table");
        c.set(hx, 1, hz - 1, "furnace[facing=south]");
        c.set(hx + 1, 1, hz - 1, "red_bed[facing=south,part=head]");
        c.set(hx + 1, 1, hz, "red_bed[facing=south,part=foot]");
        c.set(hx, 3, hz, "lantern[hanging=true]");
        c.set(hx - 1, 1, hz + 1, "potted_poppy");

        // Wheat plot west of the spawn, water in the middle.
        for (int dx = -2; dx <= 2; dx++)
            for (int dz = -1; dz <= 1; dz++) {
                int x = -5 + dx, z = -3 + dz;
                if (dx == 0 && dz == 0) {
                    c.set(x, 0, z, "water");
                    continue;
                }
                c.set(x, 0, z, "farmland[moisture=7]");
                c.set(x, 1, z, "wheat[age=" + (int) (noise(x, z, 9) * 4) + "]");
            }
        c.set(-8, 1, -3, "composter");

        // Tree south-west.
        int tx = -4, tz = 4;
        for (int y = 1; y <= 5; y++) c.set(tx, y, tz, "oak_log[axis=y]");
        for (int y = 4; y <= 6; y++) {
            int r = y == 6 ? 1 : 2;
            for (int dx = -r; dx <= r; dx++)
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.abs(dx) == r && Math.abs(dz) == r && noise(dx, dz, y) < 0.5) continue;
                    c.setIfAir(tx + dx, y, tz + dz, "oak_leaves[persistent=true]");
                }
        }
        c.setIfAir(tx, 7, tz, "oak_leaves[persistent=true]");

        // Island Generator on a little stone-brick pad.
        int gx = GENERATOR[0], gz = GENERATOR[2];
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) c.set(gx + dx, 0, gz + dz, "stone_bricks");
        c.set(gx, 0, gz, "lodestone");
        c.set(gx, 1, gz, "cobblestone");
        c.set(gx + 1, 1, gz + 1, "lantern");

        // Surface chest, a path from the spawn, a few flowers.
        c.set(SURFACE_CHEST[0], 1, SURFACE_CHEST[2], null);
        for (int z = -1; z <= 4; z++) if ("grass_block".equals(c.get(0, 0, z))) c.set(0, 0, z, "dirt_path");
        for (int dx = -8; dx <= 8; dx++)
            for (int dz = -8; dz <= 8; dz++)
                if ("grass_block".equals(c.get(dx, 0, dz)) && c.isAir(dx, 1, dz) && noise(dx, dz, salt + 9) < 0.08)
                    c.set(dx, 1, dz, new String[]{"short_grass", "dandelion", "poppy", "cornflower"}[(int) (noise(dx, dz, salt + 10) * 4)]);

        // Cave: trapdoor + ladder down to a small room with ores and the second chest.
        int sx = -3, sz = 1;
        c.set(sx, 0, sz, "oak_trapdoor[facing=north,half=top,open=false]");
        for (int y = -1; y >= -5; y--) {
            c.set(sx, y, sz, "ladder[facing=north]");
            c.set(sx, y, sz + 1, "stone");
        }
        for (int x = -7; x <= -3; x++)
            for (int z = -3; z <= 0; z++)
                for (int y = -5; y <= -3; y++) c.set(x, y, z, null);
        for (int x = -8; x <= -2; x++) for (int z = -4; z <= 1; z++) {
            if (c.isAir(x, -6, z) || c.get(x, -6, z) == null) c.set(x, -6, z, "stone");
        }
        // Exposed ores and glow on the cave walls.
        String[] ores = {"coal_ore", "iron_ore", "copper_ore", "coal_ore", "iron_ore", "lapis_ore"};
        int k = 0;
        for (int x = -7; x <= -3; x++) {
            c.set(x, -4, -4, ores[k++ % ores.length]);
            if (x % 2 == 0) c.set(x, -3, -3, "glow_lichen[north=true]");
        }
        c.set(-8, -4, -2, "iron_ore");
        c.set(-8, -5, -1, "coal_ore");
        c.set(-5, -3, -2, "lantern[hanging=true]");
        c.set(-4, -5, -3, "glow_lichen[down=true]");
        c.set(CAVE_CHEST[0], CAVE_CHEST[1], CAVE_CHEST[2], null);

        // Captain Wren's spot, with a crate beside him.
        for (int y = 1; y <= 2; y++) c.set((int) Math.floor(WREN[0]), y, (int) Math.floor(WREN[2]), null);
        c.set((int) Math.floor(WREN[0]) + 1, 1, (int) Math.floor(WREN[2]) + 1, "barrel[facing=up]");

        // Keep the spawn spot clear.
        c.set(SPAWN[0], 1, SPAWN[2], null);
        c.set(SPAWN[0], 2, SPAWN[2], null);
        c.connectFencesAndPanes();
        return c;
    }
}
