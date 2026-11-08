package com.aidan.skyserver.lobby;

import java.util.*;

import static com.aidan.skyserver.goldwars.MapCanvas.noise;
import static com.aidan.skyserver.goldwars.MapCanvas.smooth;

/**
 * The lobby: a 750 x 750 floating island in its own dimension with a castle in the middle.
 * Plain Java (no Minecraft classes), so it can be previewed offline and built chunk by chunk.
 *
 *  - Castle walls 220 x 220 with corner towers, gatehouses on all four sides and a moat.
 *  - Courtyard: spawn plaza with a fountain, portals to Skywars (west), Gold Wars (east),
 *    Bedwars (beside the spawn) and Sky Island (north), gardens and market stalls.
 *  - The Keep (north): Great Hall with the five mystery chests on a raised dais.
 *  - Outside the walls: three obstacle courses (Garden Run west, Ruins Run east, Sky Spire north).
 *
 * Ground level is y = 100 (grass top). Everything stands from y = 101.
 */
public final class LobbyDesign {
    public static final int G = 100;               // grass top
    public static final int HALF = 375;            // island is x, z in [-375, 374]
    public static final int WALL_IN = 105, WALL_OUT = 110, WALL_TOP = 122;
    public static final int MOAT_IN = 112, MOAT_OUT = 121;
    public static final int KEEP_X = 42, KEEP_Z1 = -95, KEEP_Z2 = -45, KEEP_TOP = 136;
    public static final int PLAZA_Z = 20;

    /** Where players appear (feet), facing north toward the fountain and the keep. */
    public static final double[] SPAWN = {0.5, G + 1, 40.5};
    public static final float SPAWN_YAW = 180f;

    // ---- Things the game code needs to know about ----

    public record Box(int x1, int y1, int z1, int x2, int y2, int z2) {
        public boolean contains(double x, double y, double z) {
            return x >= x1 && x < x2 + 1 && y >= y1 && y < y2 + 1 && z >= z1 && z < z2 + 1;
        }
    }

    public record Holo(double x, double y, double z, List<String> lines) {}

    public record Portal(String id, Box area, double[] particleCenter, Holo label) {}

    /** A mystery chest: tier 0 common .. 4 legendary. */
    public record Crate(int tier, int x, int y, int z) {}

    /** An obstacle course. stations: [0] start, checkpoints..., [last] finish (feet positions on top). */
    public record Course(String id, String name, int tier, List<int[]> stations, Set<Long> stationBlocks,
                         Map<Long, Integer> stationOf, int failY, List<int[]> jumps, String jumpBlock,
                         List<String> states, List<int[]> extras, List<String> extraStates, int[] segmentFailY) {
        public int[] start() { return stations.get(0); }
        public int[] finish() { return stations.get(stations.size() - 1); }
        /** Below this height you go back to the checkpoint (depends on which section you're on). */
        public int failYFor(int checkpoint) {
            return segmentFailY[Math.max(0, Math.min(segmentFailY.length - 1, checkpoint))];
        }
    }

    public static final List<Portal> PORTALS = new ArrayList<>();
    public static final List<Crate> CRATES = new ArrayList<>();
    public static final List<Course> COURSES = new ArrayList<>();
    public static final List<Holo> HOLOS = new ArrayList<>();
    public static final String[] TIER_NAMES = {"Common", "Uncommon", "Rare", "Epic", "Legendary"};
    public static final String[] TIER_COLORS = {"§f", "§a", "§9", "§5", "§6"};
    static final String[] TIER_BLOCKS = {"smooth_quartz", "emerald_block", "lapis_block", "purpur_block", "gold_block"};
    static final String[] TIER_GLASS = {"white_stained_glass", "lime_stained_glass", "blue_stained_glass", "purple_stained_glass", "yellow_stained_glass"};

    // =====================================================================
    // Parts: each has a bounding box and paints itself (clipped to the canvas)
    // =====================================================================

    private record Part(int x1, int z1, int x2, int z2, java.util.function.Consumer<LobbyCanvas> paint) {}

    private static final List<Part> PARTS = new ArrayList<>();

    /** Paints everything that touches the canvas area. */
    public static void paint(LobbyCanvas c) {
        for (Part p : PARTS) if (c.overlaps(p.x1, p.z1, p.x2, p.z2)) p.paint.accept(c);
    }

    private static void part(int x1, int z1, int x2, int z2, java.util.function.Consumer<LobbyCanvas> paint) {
        PARTS.add(new Part(Math.min(x1, x2), Math.min(z1, z2), Math.max(x1, x2), Math.max(z1, z2), paint));
    }

    private static void layout() {
        part(-HALF, -HALF, HALF, HALF, LobbyDesign::ground);
        // Clear the course areas first (the courses were redesigned; old blocks must go).
        part(-350, -66, -130, 66, LobbyDesign::clearCourseArea);
        part(130, -66, 350, 66, LobbyDesign::clearCourseArea);
        part(-76, -350, 76, -130, LobbyDesign::clearCourseArea);
        part(-76, 130, 76, 355, LobbyDesign::clearCourseArea);
        part(-MOAT_OUT, -MOAT_OUT, MOAT_OUT, MOAT_OUT, LobbyDesign::moat);
        outsideFeatures();
        courtyard();
        walls();
        keep();
        portals();
        courses();
        ActivityDesign.layout(LobbyDesign::part);
        TownDesign.layout(LobbyDesign::part);
        part(-HALF, -HALF, HALF, HALF, LobbyDesign::islandEdge);
        HOLOS.add(new Holo(SPAWN[0], G + 4.2, SPAWN[2] - 4, List.of("§6§lWELCOME", "§7Walk into a portal to play", "§7or use the §ecompass§7 in your hotbar")));
    }

    // =====================================================================
    // Ground
    // =====================================================================

    /** How far inside the island edge this column is (negative = void). The edge is a bit wavy. */
    public static double edgeDepth(int x, int z) {
        double wob = smooth(x + z * 3, z - x, 23, 7) * 9;
        return HALF - 2 - wob - Math.max(Math.abs(x + 0.5), Math.abs(z + 0.5));
    }

    private static void ground(LobbyCanvas c) {
        for (int x = c.minX; x <= c.maxX; x++)
            for (int z = c.minZ; z <= c.maxZ; z++) {
                double e = edgeDepth(x, z);
                if (e < 0) continue;
                c.set(x, G, z, "grass_block");
                c.set(x, G - 1, z, "dirt");
                c.set(x, G - 2, z, e > 3 ? "dirt" : "coarse_dirt");
                c.set(x, G - 3, z, "stone");
                // Near the edge the island hangs down in rocky lumps.
                if (e < 14) {
                    int hang = (int) ((14 - e) * 0.6 + noise(x, z, 11) * 4);
                    for (int y = G - 4; y >= G - 3 - hang; y--) c.set(x, y, z, noise(x, z * 3 + y, 12) < 0.25 ? "andesite" : "stone");
                }
            }
    }

    private static void islandEdge(LobbyCanvas c) {
        // Grass and flowers on open ground (skips anything already built on).
        for (int x = c.minX; x <= c.maxX; x++)
            for (int z = c.minZ; z <= c.maxZ; z++) {
                if (!"grass_block".equals(c.get(x, G, z)) || c.get(x, G + 1, z) != null) continue;
                double n = noise(x, z, 301);
                String plant = null;
                if (n < 0.07) plant = "short_grass";
                else if (n < 0.08) plant = "fern";
                else if (n < 0.084) plant = "poppy";
                else if (n < 0.088) plant = "dandelion";
                else if (n < 0.090) plant = "cornflower";
                else if (n < 0.092) plant = "oxeye_daisy";
                if (plant != null) c.set(x, G + 1, z, plant);
            }
    }

    private static void moat(LobbyCanvas c) {
        for (int x = c.minX; x <= c.maxX; x++)
            for (int z = c.minZ; z <= c.maxZ; z++) {
                int r = Math.max(Math.abs(x), Math.abs(z));
                if (r < MOAT_IN || r > MOAT_OUT) continue;
                boolean bank = r == MOAT_IN || r == MOAT_OUT;
                if (bank) {
                    c.set(x, G, z, "stone_bricks");
                    c.set(x, G + 1, z, null);
                    continue;
                }
                c.set(x, G - 3, z, "gravel");
                for (int y = G - 2; y <= G; y++) c.set(x, y, z, "water");
                c.set(x, G + 1, z, null);
            }
        // Bridges at the four gates.
        for (int s = 0; s < 4; s++) {
            int[] ax = sideAxis(s);
            for (int t = -5; t <= 5; t++)
                for (int r = MOAT_IN - 1; r <= MOAT_OUT + 1; r++) {
                    int x = ax[0] * r + ax[2] * t, z = ax[1] * r + ax[3] * t;
                    boolean rail = Math.abs(t) == 5;
                    c.set(x, G, z, rail ? "stone_bricks" : (Math.abs(t) <= 1 ? "polished_andesite" : "stone_bricks"));
                    c.set(x, G - 1, z, "stone_bricks");
                    if (rail) {
                        c.set(x, G + 1, z, "stone_brick_wall");
                        if (r % 4 == 0) c.set(x, G + 2, z, "lantern");
                    } else {
                        c.set(x, G + 1, z, null);
                    }
                }
        }
    }

    /** Side 0 south (+z), 1 north (-z), 2 east (+x), 3 west (-x): {outX, outZ, alongX, alongZ}. */
    private static int[] sideAxis(int s) {
        return switch (s) {
            case 0 -> new int[]{0, 1, 1, 0};
            case 1 -> new int[]{0, -1, 1, 0};
            case 2 -> new int[]{1, 0, 0, 1};
            default -> new int[]{-1, 0, 0, 1};
        };
    }

    // =====================================================================
    // Castle walls, towers, gatehouses
    // =====================================================================

    private static String wallStone(int x, int y, int z) {
        double n = noise(x * 3 + y, z * 5 - y, 41);
        return n < 0.12 ? "mossy_stone_bricks" : n < 0.22 ? "cracked_stone_bricks" : "stone_bricks";
    }

    private static void walls() {
        // Four straight wall runs.
        part(-WALL_OUT, -WALL_OUT, WALL_OUT, WALL_OUT, c -> {
            for (int x = c.minX; x <= c.maxX; x++)
                for (int z = c.minZ; z <= c.maxZ; z++) {
                    int r = Math.max(Math.abs(x), Math.abs(z));
                    if (r < WALL_IN || r > WALL_OUT) continue;
                    for (int y = G - 1; y <= WALL_TOP; y++) c.set(x, y, z, wallStone(x, y, z));
                    // Walkway on top, battlements on the outer edge.
                    c.set(x, WALL_TOP, z, "stone_brick_slab[type=top]");
                    if (r == WALL_OUT) {
                        boolean merlon = Math.floorMod(Math.abs(x) + Math.abs(z), 4) < 2;
                        c.set(x, WALL_TOP + 1, z, "stone_bricks");
                        if (merlon) c.set(x, WALL_TOP + 2, z, "stone_bricks");
                    } else if (r == WALL_IN) {
                        c.set(x, WALL_TOP + 1, z, "stone_brick_wall");
                        if (Math.floorMod(Math.abs(x) + Math.abs(z), 8) == 0) c.set(x, WALL_TOP + 2, z, "lantern");
                    }
                    // Lights on the inside face of the wall.
                    if (r == WALL_IN && Math.floorMod(Math.abs(x) + Math.abs(z), 10) == 5) {
                        c.set(x, G + 6, z, "glowstone");
                    }
                    // Arrow slits and a darker plinth.
                    if (r == WALL_OUT && Math.floorMod(Math.abs(x) + Math.abs(z), 12) == 0) {
                        c.set(x, G + 12, z, "air");
                        c.set(x, G + 13, z, "air");
                    }
                    if (r == WALL_OUT || r == WALL_IN) {
                        c.set(x, G + 1, z, "polished_andesite");
                        if (y0(x, z)) c.set(x, WALL_TOP - 1, z, "chiseled_stone_bricks");
                    }
                }
        });
        for (int s = 0; s < 4; s++) gatehouse(s);
        for (int sx = -1; sx <= 1; sx += 2)
            for (int sz = -1; sz <= 1; sz += 2) roundTower(sx * 108, sz * 108, 10, WALL_TOP + 18, true);
        // Smaller towers half way along each wall.
        for (int s = 0; s < 4; s++) {
            int[] ax = sideAxis(s);
            for (int t = -55; t <= 55; t += 110) roundTower(ax[0] * 108 + ax[2] * t, ax[1] * 108 + ax[3] * t, 6, WALL_TOP + 8, false);
        }
    }

    private static boolean y0(int x, int z) {
        return Math.floorMod(x * 7 + z * 13, 9) == 0;
    }

    /** Gate through the wall with two square towers, an arch and a raised portcullis. */
    private static void gatehouse(int s) {
        int[] ax = sideAxis(s);
        int cx = ax[0] * 108, cz = ax[1] * 108;
        part(cx - 16, cz - 16, cx + 16, cz + 16, c -> {
            for (int side = -1; side <= 1; side += 2) {
                // Towers 9 x 9 beside the gate, sticking out past the wall.
                for (int t = 6; t <= 14; t++)
                    for (int r = WALL_IN - 2; r <= WALL_OUT + 3; r++) {
                        int x = ax[0] * r + ax[2] * t * side, z = ax[1] * r + ax[3] * t * side;
                        boolean edge = t == 6 || t == 14 || r == WALL_IN - 2 || r == WALL_OUT + 3;
                        for (int y = G - 1; y <= WALL_TOP + 10; y++) c.set(x, y, z, edge ? wallStone(x, y, z) : (y == G ? "spruce_planks" : null));
                        c.set(x, WALL_TOP + 10, z, "stone_bricks");
                        if (edge) {
                            boolean merlon = (t + r) % 2 == 0;
                            c.set(x, WALL_TOP + 11, z, merlon ? "stone_bricks" : "stone_brick_slab");
                            if ((t == 10 || r == WALL_IN + 3) && edge) {
                                for (int y = G + 14; y <= G + 16; y++) c.set(x, y, z, "black_stained_glass_pane");
                            }
                        }
                    }
                // Banner-coloured stripe and lanterns at the gate.
                int x = ax[0] * (WALL_OUT + 1) + ax[2] * 5 * side, z = ax[1] * (WALL_OUT + 1) + ax[3] * 5 * side;
                c.set(x, G + 6, z, "lantern[hanging=false]");
            }
            // The opening: 9 wide, 12 high, round-ish top.
            for (int t = -4; t <= 4; t++)
                for (int r = WALL_IN - 2; r <= WALL_OUT + 3; r++) {
                    int x = ax[0] * r + ax[2] * t, z = ax[1] * r + ax[3] * t;
                    int top = G + 12 - (Math.abs(t) == 4 ? 2 : Math.abs(t) == 3 ? 1 : 0);
                    for (int y = G + 1; y <= top; y++) c.set(x, y, z, null);
                    c.set(x, G, z, Math.abs(t) <= 1 ? "polished_andesite" : "stone_bricks");
                    c.set(x, top + 1, z, "chiseled_stone_bricks");
                    if (r == WALL_OUT + 2) c.set(x, top, z, "iron_bars"); // raised portcullis teeth
                }
        });
    }

    /** Round tower: hollow, crenellated top, cone roof (main towers) or flat top (small ones). */
    private static void roundTower(int cx, int cz, int rad, int top, boolean roof) {
        part(cx - rad - 1, cz - rad - 1, cx + rad + 1, cz + rad + 1, c -> {
            for (int dx = -rad - 1; dx <= rad + 1; dx++)
                for (int dz = -rad - 1; dz <= rad + 1; dz++) {
                    double d = Math.sqrt(dx * dx + dz * dz);
                    int x = cx + dx, z = cz + dz;
                    if (d > rad + 0.5) continue;
                    boolean shell = d > rad - 1.5;
                    for (int y = G - 1; y <= top; y++) {
                        if (shell) c.set(x, y, z, wallStone(x, y, z));
                        else c.set(x, y, z, y == G || y == WALL_TOP ? "spruce_planks" : null);
                    }
                    if (shell && (y0(x, z) || Math.floorMod(dx + dz, 7) == 0)) {
                        for (int y = G + 8; y <= G + 10; y++) c.set(x, y, z, "black_stained_glass_pane");
                        for (int y = WALL_TOP + 4; y <= WALL_TOP + 6; y++) c.set(x, y, z, "black_stained_glass_pane");
                    }
                    c.set(x, top, z, "stone_bricks");
                    if (shell) {
                        c.set(x, top + 1, z, Math.floorMod(dx * 3 + dz, 2) == 0 ? "stone_bricks" : "stone_brick_slab");
                        c.set(x, top - 1, z, "chiseled_stone_bricks");
                    }
                }
            if (roof) {
                // Cone of deepslate tiles with a gold tip.
                int h = rad * 2;
                for (int i = 0; i <= h; i++) {
                    double r = (rad - 1) * (1 - i / (double) h);
                    for (int dx = -rad; dx <= rad; dx++)
                        for (int dz = -rad; dz <= rad; dz++) {
                            double d = Math.sqrt(dx * dx + dz * dz);
                            if (d <= r + 0.5 && d > r - 1.2) c.set(cx + dx, top + 2 + i, cz + dz, "deepslate_tiles");
                        }
                }
                c.set(cx, top + 3 + h, cz, "gold_block");
                c.set(cx, top + 4 + h, cz, "lightning_rod");
            }
        });
    }

    // =====================================================================
    // Courtyard
    // =====================================================================

    private static void courtyard() {
        int in = WALL_IN - 1;
        // Paths: main avenue south gate -> plaza -> keep, cross avenue west gate -> east gate, north path.
        part(-in, -in, in, in, c -> {
            for (int x = c.minX; x <= c.maxX; x++)
                for (int z = c.minZ; z <= c.maxZ; z++) {
                    if (Math.abs(x) > in || Math.abs(z) > in) continue;
                    boolean avenue = (Math.abs(x) <= 3 && z > PLAZA_Z) || (Math.abs(z - PLAZA_Z) <= 3) || (Math.abs(x) <= 3 && z < KEEP_Z1);
                    boolean keepPath = z <= PLAZA_Z && z >= KEEP_Z2 && (Math.abs(Math.abs(x) - 11) <= 1);
                    if (avenue || keepPath) {
                        boolean edge = (Math.abs(x) == 3 && !(Math.abs(z - PLAZA_Z) <= 3)) || (Math.abs(z - PLAZA_Z) == 3 && Math.abs(x) > 3);
                        c.set(x, G, z, edge ? "polished_andesite" : (noise(x, z, 51) < 0.2 ? "cracked_stone_bricks" : "stone_bricks"));
                    }
                }
        });
        plaza();
        // Lamp posts along the avenues.
        for (int t = -96; t <= 96; t += 8) {
            if (Math.abs(t) < 40) continue; // keep clear of the plaza and the portals
            lamp(t, PLAZA_Z - 5);
            lamp(t, PLAZA_Z + 5);
        }
        for (int z = PLAZA_Z + 22; z <= 100; z += 8) {
            lamp(-5, z);
            lamp(5, z);
        }
        // Gardens in the four quarters (hedges, trees, flower beds).
        garden(-60, 62, 30, 26, 1);
        garden(60, 62, 30, 26, 2);
        garden(-62, -20, 26, 30, 3);
        garden(62, -20, 26, 30, 4);
        // Market stalls by the south gate.
        for (int i = 0; i < 3; i++) {
            stall(-16, 72 + i * 10, "red_wool", "west");
            stall(16, 72 + i * 10, "blue_wool", "east");
        }
        // Training yard targets and a well near the keep.
        well(-28, -32);
        well(28, -32);
    }

    private static void plaza() {
        int cz = PLAZA_Z;
        part(-18, cz - 18, 18, cz + 18, c -> {
            for (int dx = -17; dx <= 17; dx++)
                for (int dz = -17; dz <= 17; dz++) {
                    double d = Math.sqrt(dx * dx + dz * dz);
                    if (d > 16.5) continue;
                    String s = ((int) d) % 4 == 0 ? "polished_andesite" : (Math.floorMod(dx + dz, 2) == 0 ? "smooth_stone" : "stone_bricks");
                    if (d > 15.5) s = "chiseled_stone_bricks";
                    c.set(dx, G, cz + dz, s);
                    c.set(dx, G + 1, cz + dz, null);
                }
            // Fountain: quartz basin with a tiered column.
            for (int dx = -5; dx <= 5; dx++)
                for (int dz = -5; dz <= 5; dz++) {
                    double d = Math.sqrt(dx * dx + dz * dz);
                    if (d > 5.4) continue;
                    if (d > 4.4) {
                        c.set(dx, G + 1, cz + dz, "quartz_bricks");
                    } else {
                        c.set(dx, G, cz + dz, "quartz_bricks");
                        c.set(dx, G + 1, cz + dz, "water");
                    }
                }
            for (int y = G + 1; y <= G + 5; y++) c.set(0, y, cz, "quartz_pillar");
            for (int dx = -1; dx <= 1; dx++)
                for (int dz = -1; dz <= 1; dz++) if (dx != 0 || dz != 0) c.set(dx, G + 4, cz + dz, "quartz_slab");
            c.set(0, G + 6, cz, "water");
            c.set(0, G + 7, cz, "sea_lantern");
            // Lamp posts around the plaza.
            for (int a = 0; a < 12; a++) {
                double ang = a * Math.PI / 6;
                int lx = (int) Math.round(Math.cos(ang) * 15), lz = (int) Math.round(Math.sin(ang) * 15);
                if (Math.abs(lx) <= 4 || Math.abs(lz) <= 4) continue; // keep the paths clear
                c.set(lx, G + 1, cz + lz, "stone_bricks");
                c.set(lx, G + 2, cz + lz, "stone_brick_wall");
                c.set(lx, G + 3, cz + lz, "lantern");
            }
            // Benches facing the fountain.
            for (int i = -2; i <= 2; i++) {
                c.set(i, G + 1, cz + 11, "spruce_stairs[facing=south]");
                c.set(i, G + 1, cz - 11, "spruce_stairs[facing=north]");
            }
        });
    }

    private static void lamp(int x, int z) {
        part(x, z, x, z, c -> {
            c.set(x, G + 1, z, "stone_bricks");
            c.set(x, G + 2, z, "stone_brick_wall");
            c.set(x, G + 3, z, "stone_brick_wall");
            c.set(x, G + 4, z, "lantern");
        });
    }

    private static void garden(int cx, int cz, int w, int d, int salt) {
        part(cx - w / 2, cz - d / 2, cx + w / 2, cz + d / 2, c -> {
            int x1 = cx - w / 2, x2 = cx + w / 2, z1 = cz - d / 2, z2 = cz + d / 2;
            for (int x = x1; x <= x2; x++)
                for (int z = z1; z <= z2; z++) {
                    boolean edge = x == x1 || x == x2 || z == z1 || z == z2;
                    boolean gap = Math.abs(x - cx) <= 1 || Math.abs(z - cz) <= 1;
                    if (edge && !gap) {
                        c.set(x, G + 1, z, "oak_leaves[persistent=true]");
                    } else if (gap) {
                        c.set(x, G, z, "gravel");
                    } else if (Math.floorMod(x + z * 3, 5) == 0 && !edge) {
                        c.set(x, G, z, "grass_block");
                        String[] f = {"rose_bush", "peony", "lilac", "allium", "red_tulip", "orange_tulip", "pink_tulip"};
                        String flower = f[(int) (noise(x, z, salt) * f.length)];
                        if (flower.endsWith("bush") || flower.equals("peony") || flower.equals("lilac")) {
                            c.set(x, G + 1, z, flower + "[half=lower]");
                            c.set(x, G + 2, z, flower + "[half=upper]");
                        } else {
                            c.set(x, G + 1, z, flower);
                        }
                    }
                }
            oakTree(c, x1 + 4, G + 1, z1 + 4, 5);
            oakTree(c, x2 - 4, G + 1, z2 - 4, 5);
            birchTree(c, x1 + 4, G + 1, z2 - 4, 6);
            birchTree(c, x2 - 4, G + 1, z1 + 4, 6);
        });
    }

    private static void stall(int x, int z, String wool, String facing) {
        part(x - 3, z - 3, x + 3, z + 3, c -> {
            for (int dx = -2; dx <= 2; dx++)
                for (int dz = -2; dz <= 2; dz++) {
                    boolean corner = Math.abs(dx) == 2 && Math.abs(dz) == 2;
                    if (corner) for (int y = G + 1; y <= G + 3; y++) c.set(x + dx, y, z + dz, "spruce_fence");
                    c.set(x + dx, G + 4, z + dz, wool);
                }
            for (int dz = -1; dz <= 1; dz++) c.set(x, G + 1, z + dz, "barrel[facing=up]");
            c.set(x, G + 2, z, "lantern");
        });
    }

    private static void well(int x, int z) {
        part(x - 3, z - 3, x + 3, z + 3, c -> {
            for (int dx = -2; dx <= 2; dx++)
                for (int dz = -2; dz <= 2; dz++) {
                    boolean rim = Math.abs(dx) == 2 || Math.abs(dz) == 2;
                    c.set(x + dx, G, z + dz, "cobblestone");
                    c.set(x + dx, G + 1, z + dz, rim ? "cobblestone_wall" : "water");
                    if (Math.abs(dx) == 2 && Math.abs(dz) == 2) for (int y = G + 2; y <= G + 3; y++) c.set(x + dx, y, z + dz, "oak_fence");
                    c.set(x + dx, G + 4, z + dz, "oak_slab");
                }
            c.set(x, G + 3, z, "iron_chain");
            c.set(x, G + 2, z, "lantern[hanging=true]");
        });
    }

    // =====================================================================
    // The Keep and the Great Hall (mystery chests)
    // =====================================================================

    private static void keep() {
        int x1 = -KEEP_X, x2 = KEEP_X, z1 = KEEP_Z1, z2 = KEEP_Z2;
        part(x1 - 6, z1 - 6, x2 + 6, z2 + 6, c -> {
            for (int x = x1; x <= x2; x++)
                for (int z = z1; z <= z2; z++) {
                    boolean wall = x == x1 || x == x2 || z == z1 || z == z2;
                    boolean wall2 = x == x1 + 1 || x == x2 - 1 || z == z1 + 1 || z == z2 - 1;
                    for (int y = G - 1; y <= KEEP_TOP; y++) {
                        String s = null;
                        if (wall || wall2) s = wallStone(x, y, z);
                        else if (y == G) s = Math.floorMod(x + z, 2) == 0 ? "polished_andesite" : "polished_deepslate";
                        else if (y == G + 24) s = "dark_oak_planks";      // hall ceiling / upper floor
                        else if (y == KEEP_TOP) s = "stone_bricks";        // roof
                        c.set(x, y, z, s);
                    }
                    if (wall) {
                        // Battlements and corner trim.
                        boolean merlon = Math.floorMod(x + z, 4) < 2;
                        c.set(x, KEEP_TOP + 1, z, "stone_bricks");
                        if (merlon) c.set(x, KEEP_TOP + 2, z, "stone_bricks");
                        c.set(x, G + 1, z, "polished_andesite");
                        c.set(x, G + 24, z, "chiseled_stone_bricks");
                        // Tall windows every 8 blocks.
                        int along = (z == z1 || z == z2) ? x : z;
                        if (Math.floorMod(along, 8) == 0 && Math.abs(along) > 4) {
                            for (int y = G + 6; y <= G + 16; y++) {
                                c.set(x, y, z, "gray_stained_glass_pane");
                                c.set(inward(x, x1, x2), y, inward(z, z1, z2), "gray_stained_glass_pane");
                            }
                            for (int y = G + 28; y <= G + 32; y++) {
                                c.set(x, y, z, "gray_stained_glass_pane");
                                c.set(inward(x, x1, x2), y, inward(z, z1, z2), "gray_stained_glass_pane");
                            }
                        }
                    }
                }
            // Front door (south) with steps.
            for (int dx = -4; dx <= 4; dx++) {
                int top = G + 10 - (Math.abs(dx) == 4 ? 2 : Math.abs(dx) == 3 ? 1 : 0);
                for (int y = G + 1; y <= top; y++) {
                    c.set(dx, y, z2, null);
                    c.set(dx, y, z2 - 1, null);
                }
                c.set(dx, top + 1, z2, "chiseled_stone_bricks");
                c.set(dx, G, z2, "polished_andesite");
                c.set(dx, G, z2 - 1, "polished_andesite");
                c.set(dx, G + 1, z2 + 1, "stone_brick_slab");
            }
            c.set(-5, G + 6, z2 + 1, "lantern");
            c.set(5, G + 6, z2 + 1, "lantern");
            // Corner turrets on the roof and a central tower.
            for (int sx = -1; sx <= 1; sx += 2)
                for (int sz = 0; sz <= 1; sz++) {
                    int tx = sx * (KEEP_X - 2), tz = sz == 0 ? z1 + 2 : z2 - 2;
                    for (int dx = -4; dx <= 4; dx++)
                        for (int dz = -4; dz <= 4; dz++) {
                            double d = Math.sqrt(dx * dx + dz * dz);
                            if (d > 4.4) continue;
                            for (int y = KEEP_TOP; y <= KEEP_TOP + 10; y++) c.set(tx + dx, y, tz + dz, d > 3.2 ? wallStone(tx + dx, y, tz + dz) : null);
                            c.set(tx + dx, KEEP_TOP + 10, tz + dz, "stone_bricks");
                            if (d > 3.2 && Math.floorMod(dx + dz, 2) == 0) c.set(tx + dx, KEEP_TOP + 11, tz + dz, "stone_bricks");
                        }
                }
            int cz = (z1 + z2) / 2;
            for (int dx = -7; dx <= 7; dx++)
                for (int dz = -7; dz <= 7; dz++) {
                    boolean edge = Math.abs(dx) == 7 || Math.abs(dz) == 7;
                    for (int y = KEEP_TOP; y <= KEEP_TOP + 22; y++) c.set(dx, y, cz + dz, edge ? wallStone(dx, y, cz + dz) : null);
                    if (edge && (Math.abs(dx) == 0 || Math.abs(dz) == 0 || Math.abs(dx) == 3 || Math.abs(dz) == 3))
                        for (int y = KEEP_TOP + 12; y <= KEEP_TOP + 16; y++) c.set(dx, y, cz + dz, "gray_stained_glass_pane");
                    c.set(dx, KEEP_TOP + 22, cz + dz, "stone_bricks");
                    if (edge && Math.floorMod(dx + dz, 2) == 0) c.set(dx, KEEP_TOP + 23, cz + dz, "stone_bricks");
                }
            for (int y = KEEP_TOP + 23; y <= KEEP_TOP + 30; y++) c.set(0, y, cz, "oak_fence");
            for (int y = KEEP_TOP + 27; y <= KEEP_TOP + 30; y++)
                for (int dx = 1; dx <= 4; dx++) c.set(dx, y, cz, "yellow_wool");
            greatHall(c, x1 + 2, x2 - 2, z1 + 2, z2 - 2);
        });
        // Mystery chests on the dais.
        for (int t = 0; t < 5; t++) {
            int x = -24 + t * 12, z = KEEP_Z1 + 7;
            CRATES.add(new Crate(t, x, G + 3, z));
            HOLOS.add(new Holo(x + 0.5, G + 5.0, z + 0.5, List.of(TIER_COLORS[t] + "§l" + TIER_NAMES[t].toUpperCase() + " CHEST",
                    "§7Right-click with a " + TIER_COLORS[t] + TIER_NAMES[t] + " Key", "§8(right-click without one to see the prizes)")));
        }
        HOLOS.add(new Holo(0.5, G + 12.0, KEEP_Z2 + 1.5, List.of("§6§lTHE GREAT HALL", "§eMystery chests inside")));
    }

    private static int inward(int v, int lo, int hi) {
        return v == lo ? lo + 1 : v == hi ? hi - 1 : v;
    }

    private static void greatHall(LobbyCanvas c, int x1, int x2, int z1, int z2) {
        // Red carpet aisle from the door to the dais.
        for (int z = z1 + 8; z <= z2; z++)
            for (int x = -2; x <= 2; x++) c.set(x, G + 1, z, Math.abs(x) == 2 ? "yellow_carpet" : "red_carpet");
        // Pillars down both sides, with hanging lanterns between them.
        for (int z = z1 + 14; z <= z2 - 3; z += 6)
            for (int sx = -1; sx <= 1; sx += 2) {
                int x = sx * 12;
                for (int y = G + 1; y <= G + 23; y++) {
                    c.set(x, y, z, "polished_deepslate");
                    c.set(x + 1, y, z, "polished_deepslate");
                }
                c.set(x, G + 1, z, "chiseled_stone_bricks");
                c.set(x + 1, G + 1, z, "chiseled_stone_bricks");
                int lx = sx * 6;
                for (int y = G + 19; y <= G + 23; y++) c.set(lx, y, z + 3, "iron_chain");
                c.set(lx, G + 18, z + 3, "lantern[hanging=true]");
                // Banners on the walls between windows.
                c.set(x2 * sx > 0 ? x2 : x1, G + 18, z, sx > 0 ? "yellow_wall_banner[facing=west]" : "yellow_wall_banner[facing=east]");
            }
        // Long tables along the side walls.
        for (int z = z1 + 10; z <= z2 - 4; z++)
            for (int sx = -1; sx <= 1; sx += 2) {
                int x = sx * 30;
                c.set(x, G + 1, z, "dark_oak_slab[type=top]");
                c.set(x + sx, G + 1, z, z % 3 == 0 ? "spruce_stairs[facing=" + (sx > 0 ? "west" : "east") + "]" : null);
                c.set(x - sx, G + 1, z, z % 3 == 1 ? "spruce_stairs[facing=" + (sx > 0 ? "east" : "west") + "]" : null);
                if (z % 4 == 0) c.set(x, G + 2, z, "candle[lit=true,candles=3]");
            }
        // Glowstone hidden in the ceiling.
        for (int x = x1 + 2; x <= x2 - 2; x += 4)
            for (int z = z1 + 2; z <= z2 - 2; z += 4) c.set(x, G + 24, z, "glowstone");
        // Lanterns along the walls at head height.
        for (int z = z1 + 2; z <= z2 - 2; z += 5) {
            c.set(x1, G + 4, z, "glowstone");
            c.set(x2, G + 4, z, "glowstone");
        }
        for (int x = x1 + 2; x <= x2 - 2; x += 5) c.set(x, G + 4, z2, "glowstone");
        // The dais: 3 steps up across the back of the hall.
        for (int x = x1; x <= x2; x++)
            for (int z = z1; z <= z1 + 9; z++) {
                c.set(x, G + 1, z, "polished_blackstone_bricks");
                if (z <= z1 + 8) c.set(x, G + 2, z, z == z1 + 8 ? "polished_blackstone_brick_slab" : "polished_blackstone_bricks");
            }
        // Five chests, each on a tier-coloured pedestal under a glass canopy.
        for (int t = 0; t < 5; t++) {
            int x = -24 + t * 12, z = KEEP_Z1 + 7;
            for (int dx = -2; dx <= 2; dx++)
                for (int dz = -2; dz <= 2; dz++) {
                    c.set(x + dx, G + 2, z + dz, Math.abs(dx) == 2 || Math.abs(dz) == 2 ? "polished_blackstone_bricks" : TIER_BLOCKS[t]);
                    c.set(x + dx, G + 9, z + dz, TIER_GLASS[t]);
                }
            for (int sx = -2; sx <= 2; sx += 4)
                for (int sz = -2; sz <= 2; sz += 4)
                    for (int y = G + 3; y <= G + 8; y++) c.set(x + sx, y, z + sz, "polished_blackstone_wall");
            c.set(x, G + 3, z, "chest[facing=south]");
            c.set(x, G + 8, z, "sea_lantern");
        }
        // Throne behind the chests.
        c.set(0, G + 3, z1, "gold_block");
        c.set(0, G + 4, z1, "gold_block");
        c.set(-1, G + 3, z1 + 1, "gold_block");
        c.set(1, G + 3, z1 + 1, "gold_block");
        c.set(0, G + 3, z1 + 1, "red_wool");
    }

    // =====================================================================
    // Portals
    // =====================================================================

    private static void portals() {
        arch("skywars", -30, PLAZA_Z, true, "light_blue_concrete", "§b§lSKYWARS", "§7Last player standing wins");
        arch("goldwars", 30, PLAZA_Z, true, "yellow_concrete", "§6§lGOLD WARS", "§76v6: break the enemy's gold");
        arch("island", 0, -12, false, "lime_concrete", "§a§lSKY ISLAND", "§7Your own island: build, farm, grow");
        arch("bedwars", 26, 40, true, "red_concrete", "§c§lBEDWARS", "§74 teams of 3 or 2: protect your bed!");
        bedwarsCorner(26, 40);
    }

    /** A path from the spawn to the Bedwars arch, and a giant bed beside it. */
    private static void bedwarsCorner(int cx, int cz) {
        part(4, cz - 2, cx - 6, cz + 2, c -> {
            for (int x = 4; x <= cx - 6; x++)
                for (int t = -2; t <= 2; t++) {
                    c.set(x, G, cz + t, Math.abs(t) == 2 ? "polished_andesite" : noise(x, t, 81) < 0.2 ? "cracked_stone_bricks" : "stone_bricks");
                    c.set(x, G + 1, cz + t, null); // no flowers on the path
                }
        });
        // A giant red bed (3 wide, 6 long, with white pillows) north of the arch, and a little sign post.
        part(cx - 3, cz - 14, cx + 3, cz - 6, c -> {
            int x1 = cx - 1, x2 = cx + 1, z1 = cz - 13, z2 = cz - 8;
            for (int x = x1 - 1; x <= x2 + 1; x++)
                for (int z = z1 - 1; z <= z2 + 1; z++) c.set(x, G, z, "dark_oak_planks");
            for (int x = x1; x <= x2; x++)
                for (int z = z1; z <= z2; z++) {
                    c.set(x, G + 1, z, z <= z1 + 1 ? "white_wool" : "red_wool");
                    if (z >= z1 + 2) c.set(x, G + 2, z, (z + x) % 3 == 0 ? "red_carpet" : "white_carpet");
                }
            for (int x = x1 - 1; x <= x2 + 1; x += x2 - x1 + 2) {
                c.set(x, G + 1, z1 - 1, "dark_oak_fence");
                c.set(x, G + 2, z1 - 1, "dark_oak_fence");
                c.set(x, G + 1, z2 + 1, "dark_oak_fence");
            }
            for (int x = x1 - 1; x <= x2 + 1; x++) c.set(x, G + 3, z1 - 1, "dark_oak_slab[type=bottom]");
            c.set(cx + 3, G + 1, cz - 6, "red_concrete");
            c.set(cx + 3, G + 2, cz - 6, "red_concrete");
            c.set(cx + 3, G + 3, cz - 6, "lantern");
        });
    }

    /**
     * A stone archway 7 wide and 9 tall. alongZ = the arch runs north-south (walk through it
     * east-west). The space inside is the portal: walk into it.
     */
    private static void arch(String id, int cx, int cz, boolean alongZ, String accent, String title, String sub) {
        part(cx - 6, cz - 6, cx + 6, cz + 6, c -> {
            for (int t = -4; t <= 4; t++)
                for (int y = G; y <= G + 10; y++) {
                    int x = alongZ ? cx : cx + t, z = alongZ ? cz + t : cz;
                    boolean side = Math.abs(t) >= 3;
                    boolean top = y >= G + 9 - (Math.abs(t) == 2 ? 1 : 0);
                    String s = null;
                    if (y == G) s = "chiseled_quartz_block";
                    else if (side || top) s = (Math.abs(t) == 4 || y == G + 10) ? "quartz_pillar" : accent;
                    if (Math.abs(t) == 4 && y == G + 10) s = "quartz_bricks";
                    c.set(x, y, z, s);
                }
            // Coloured carpet runway in front of and behind the arch.
            for (int a = -5; a <= 5; a++)
                for (int t = -2; t <= 2; t++) {
                    if (a == 0) continue;
                    int x = alongZ ? cx + a : cx + t, z = alongZ ? cz + t : cz + a;
                    c.set(x, G + 1, z, accent.replace("_concrete", "_carpet"));
                }
        });
        Box area = alongZ ? new Box(cx, G + 1, cz - 2, cx, G + 8, cz + 2) : new Box(cx - 2, G + 1, cz, cx + 2, G + 8, cz);
        Holo label = new Holo(cx + 0.5, G + 12.3, cz + 0.5, new ArrayList<>(List.of(title, sub, "§7Players: §f...")));
        PORTALS.add(new Portal(id, area, new double[]{cx + 0.5, G + 4.5, cz + 0.5}, label));
    }

    // =====================================================================
    // Outside the walls: trees, a lake, paths to the courses
    // =====================================================================

    private static void outsideFeatures() {
        // Paths from each gate out to the courses / island edge.
        part(-HALF, -6, -MOAT_OUT, 6, c -> path(c, -HALF + 30, -MOAT_OUT - 1, true));
        part(MOAT_OUT, -6, HALF, 6, c -> path(c, MOAT_OUT + 1, HALF - 30, true));
        part(-6, -HALF, 6, -MOAT_OUT, c -> path(c, -HALF + 30, -MOAT_OUT - 1, false));
        part(-6, MOAT_OUT, 6, HALF, c -> path(c, MOAT_OUT + 1, HALF - 30, false));
        // A lake in the south-east and one in the south-west.
        lake(200, 220, 38, 1);
        lake(-210, 230, 30, 2);
        // Scattered trees (not on paths, the moat, the lakes or the courses).
        for (int gx = -HALF + 12; gx <= HALF - 12; gx += 7)
            for (int gz = -HALF + 12; gz <= HALF - 12; gz += 7) {
                int x = gx + (int) (noise(gx, gz, 71) * 6) - 3, z = gz + (int) (noise(gx, gz, 72) * 6) - 3;
                if (Math.max(Math.abs(x), Math.abs(z)) < MOAT_OUT + 6) continue;
                if (Math.abs(x) < 9 || Math.abs(z) < 9) continue; // paths
                if (inCourseArea(x, z) || inLake(x, z) || edgeDepth(x, z) < 8) continue;
                // Forests where the noise is high, open meadows where it's low.
                double density = smooth(x, z, 70, 73);
                double chance = density > 0.62 ? 0.85 : density > 0.45 ? 0.25 : 0.04;
                if (noise(x, z, 74) > chance) continue;
                int kind = (int) (noise(x, z, 75) * 10);
                final int fx = x, fz = z;
                part(x - 4, z - 4, x + 4, z + 4, c -> {
                    if (kind < 5) oakTree(c, fx, G + 1, fz, 5 + kind % 3);
                    else if (kind < 8) birchTree(c, fx, G + 1, fz, 6 + kind % 2);
                    else spruceTree(c, fx, G + 1, fz, 8 + kind % 3);
                });
            }
    }

    private static void path(LobbyCanvas c, int from, int to, boolean alongX) {
        for (int a = from; a <= to; a += 16) {
            int x = alongX ? a : 3, z = alongX ? 3 : a;
            if (edgeDepth(x, z) < 2) continue;
            c.set(x, G + 1, z, "stone_bricks");
            c.set(x, G + 2, z, "stone_brick_wall");
            c.set(x, G + 3, z, "lantern");
        }
        for (int a = from; a <= to; a++)
            for (int t = -2; t <= 2; t++) {
                int x = alongX ? a : t, z = alongX ? t : a;
                if (edgeDepth(x, z) < 1) continue;
                c.set(x, G, z, Math.abs(t) == 2 ? "coarse_dirt" : "dirt_path");
            }
    }

    private static final int[][] LAKES = {{200, 220, 38}, {-210, 230, 30}};

    private static boolean inLake(int x, int z) {
        for (int[] l : LAKES) if (Math.hypot(x - l[0], z - l[1]) < l[2] + 6) return true;
        return false;
    }

    private static void lake(int cx, int cz, int r, int salt) {
        part(cx - r - 3, cz - r - 3, cx + r + 3, cz + r + 3, c -> {
            for (int x = cx - r - 2; x <= cx + r + 2; x++)
                for (int z = cz - r - 2; z <= cz + r + 2; z++) {
                    double d = Math.hypot(x - cx, z - cz) + smooth(x, z, 9, salt) * 5;
                    if (d > r + 1) continue;
                    if (d > r - 1) {
                        c.set(x, G, z, "sand");
                        c.set(x, G + 1, z, null);
                        continue;
                    }
                    int depth = 1 + (int) ((r - d) / 6);
                    for (int y = G - depth; y <= G; y++) c.set(x, y, z, "water");
                    c.set(x, G - depth - 1, z, "sand");
                    c.set(x, G + 1, z, null);
                    if (noise(x, z, salt + 80) < 0.02 && depth <= 2) c.set(x, G + 1, z, "lily_pad");
                }
        });
    }

    // =====================================================================
    // Obstacle courses
    // =====================================================================

    private static boolean inCourseArea(int x, int z) {
        boolean west = x < -135 && x > -345 && Math.abs(z) < 62;
        boolean east = x > 135 && x < 345 && Math.abs(z) < 62;
        boolean north = z < -135 && z > -345 && Math.abs(x) < 72;
        boolean south = z > 135 && z < 350 && Math.abs(x) < 72;
        return west || east || north || south || ActivityDesign.inArea(x, z) || TownDesign.inArea(x, z);
    }

    private static void clearCourseArea(LobbyCanvas c) {
        for (int x = c.minX; x <= c.maxX; x++)
            for (int z = c.minZ; z <= c.maxZ; z++) {
                if (!inCourseArea(x, z) || ActivityDesign.inArea(x, z) || TownDesign.inArea(x, z) || edgeDepth(x, z) < 0) continue;
                for (int y = G + 1; y <= G + 50; y++) c.set(x, y, z, null);
            }
    }

    static final String[] TIER_WORDS = {"Easy", "Medium", "Hard", "Extreme"};

    private static void courses() {
        COURSES.addAll(CourseDesign.all());
        for (Course course : COURSES) {
            final Course cc = course;
            int x1 = Integer.MAX_VALUE, z1 = Integer.MAX_VALUE, x2 = Integer.MIN_VALUE, z2 = Integer.MIN_VALUE;
            List<int[]> all = new ArrayList<>(course.jumps());
            all.addAll(course.extras());
            for (int[] j : all) {
                x1 = Math.min(x1, j[0] - 6);
                z1 = Math.min(z1, j[2] - 6);
                x2 = Math.max(x2, j[0] + 6);
                z2 = Math.max(z2, j[2] + 6);
            }
            part(x1, z1, x2, z2, c -> paintCourse(c, cc));
            int[] s = course.start(), f = course.finish();
            String color = new String[]{"§a", "§6", "§c", "§5"}[course.tier()];
            HOLOS.add(new Holo(s[0] + 0.5, s[1] + 3.2, s[2] + 0.5, List.of(color + "§l" + course.name().toUpperCase(),
                    "§7" + TIER_WORDS[course.tier()] + " obstacle course", "§7Step on the §agreen block§7 to start")));
            HOLOS.add(new Holo(f[0] + 0.5, f[1] + 3.0, f[2] + 0.5, List.of("§6§lFINISH", color + course.name())));
        }
    }

    private static void paintCourse(LobbyCanvas c, Course course) {
        List<int[]> jumps = course.jumps();
        int last = course.stations().size() - 1;
        for (int i = 0; i < jumps.size(); i++) {
            int[] b = jumps.get(i);
            for (int y = b[1] + 1; y <= b[1] + 3; y++) c.set(b[0], y, b[2], null); // head room
        }
        // Walls to jump around, ladders, ceilings...
        for (int i = 0; i < course.extras().size(); i++) {
            int[] e = course.extras().get(i);
            c.set(e[0], e[1], e[2], course.extraStates().get(i));
        }
        for (int i = 0; i < jumps.size(); i++) {
            int[] b = jumps.get(i);
            c.set(b[0], b[1], b[2], i < course.states().size() ? course.states().get(i) : course.jumpBlock());
        }
        for (int s = 0; s <= last; s++) {
            int[] st = course.stations().get(s);
            int bx = st[0], by = st[1] - 1, bz = st[2];
            String plat = s == 0 ? "emerald_block" : s == last ? "gold_block" : "light_blue_concrete";
            for (int dx = -1; dx <= 1; dx++)
                for (int dz = -1; dz <= 1; dz++) {
                    c.set(bx + dx, by, bz + dz, dx == 0 && dz == 0 ? plat : "smooth_quartz");
                    for (int y = by + 1; y <= by + 3; y++) c.set(bx + dx, y, bz + dz, null);
                }
            if (s > 0 && s < last) {
                c.set(bx, by + 1, bz, "light_weighted_pressure_plate");
                c.set(bx + 1, by + 1, bz - 1, "lantern");
            }
            if (s == last) {
                c.set(bx + 1, by + 1, bz + 1, "lantern");
                c.set(bx - 1, by + 1, bz - 1, "lantern");
            }
        }
        // Stairs up to the start platform from the ground.
        int[] st = course.stations().get(0);
        int[] next = jumps.get(1);
        int dirX = Integer.signum(st[0] - next[0]), dirZ = Integer.signum(st[2] - next[2]);
        if (dirX == 0 && dirZ == 0) dirZ = 1;
        if (dirX != 0 && dirZ != 0) dirZ = 0;
        int top = st[1] - 1;
        for (int i = 1; i <= top - G; i++) {
            int x = st[0] + dirX * (1 + i), z = st[2] + dirZ * (1 + i);
            for (int t = -1; t <= 1; t++) {
                int xx = x + dirZ * t, zz = z + dirX * t;
                for (int y = G + 1; y <= top - i; y++) c.set(xx, y, zz, "stone_bricks");
                c.set(xx, top - i + 1, zz, null);
            }
        }
    }

    // =====================================================================
    // Trees
    // =====================================================================

    static void oakTree(LobbyCanvas c, int x, int y, int z, int h) {
        for (int i = 0; i < h; i++) c.set(x, y + i, z, "oak_log[axis=y]");
        canopy(c, x, y + h - 2, z, 2, "oak_leaves[persistent=true]", 91);
        canopy(c, x, y + h - 1, z, 2, "oak_leaves[persistent=true]", 90);
        canopy(c, x, y + h, z, 1, "oak_leaves[persistent=true]", 92);
        c.setIfAir(x, y + h + 1, z, "oak_leaves[persistent=true]");
    }

    static void birchTree(LobbyCanvas c, int x, int y, int z, int h) {
        for (int i = 0; i < h; i++) c.set(x, y + i, z, "birch_log[axis=y]");
        canopy(c, x, y + h - 2, z, 2, "birch_leaves[persistent=true]", 93);
        canopy(c, x, y + h, z, 1, "birch_leaves[persistent=true]", 94);
        c.setIfAir(x, y + h + 1, z, "birch_leaves[persistent=true]");
    }

    static void spruceTree(LobbyCanvas c, int x, int y, int z, int h) {
        for (int i = 0; i < h; i++) c.set(x, y + i, z, "spruce_log[axis=y]");
        for (int i = 2; i < h + 1; i++) {
            int r = Math.max(0, (h - i) / 2);
            if (i % 2 == 1 && r > 0) r--;
            canopy(c, x, y + i, z, Math.max(r, i == h ? 0 : 1), "spruce_leaves[persistent=true]", 99 + i);
        }
        c.setIfAir(x, y + h, z, "spruce_leaves[persistent=true]");
        c.setIfAir(x, y + h + 1, z, "spruce_leaves[persistent=true]");
    }

    static void canopy(LobbyCanvas c, int x, int y, int z, int r, String leaves, int salt) {
        for (int dx = -r; dx <= r; dx++)
            for (int dz = -r; dz <= r; dz++) {
                int dd = dx * dx + dz * dz;
                if (dd > r * r + 1) continue;
                if (dd >= r * r && noise(x + dx, z + dz, salt) < 0.45) continue;
                c.setIfAir(x + dx, y, z + dz, leaves);
            }
    }

    // Runs last, after every table above exists.
    static {
        layout();
    }
}
