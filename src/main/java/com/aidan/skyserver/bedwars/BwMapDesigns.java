package com.aidan.skyserver.bedwars;

import com.aidan.skyserver.goldwars.MapCanvas;

import java.util.*;

import static com.aidan.skyserver.goldwars.MapCanvas.noise;
import static com.aidan.skyserver.goldwars.MapCanvas.noise3;
import static com.aidan.skyserver.goldwars.MapCanvas.smooth;

/**
 * The two Bedwars maps, in plain Java (previewed and checked offline before they go in the game).
 * Each map is drawn once for the Red team and turned a quarter at a time for Blue, Green and
 * Yellow, so every team gets exactly the same base, distances and routes. y = 0 is the floor
 * level (people stand at y = 1). Blocks named "team_..." become the team's colour.
 *
 *  SLUMBER ISLES (id "slumber") - dreamy islands resting on clouds.
 *    Bases on the four corners of a square. A diamond island sits on each side, between two
 *    neighbouring bases (so each one is fought over), and the Moon Garden is in the middle:
 *    an open lower ring and a covered walk under a raised deck that holds the 2 emerald
 *    generators. Every route is a straight line: base -> diamond island -> middle.
 *
 *  EMBER FORGE (id "ember") - volcanic forge islands of blackstone, basalt and copper.
 *    Bases on the four sides. Each team has its own diamond island straight in front of its
 *    base (enemies must cross it to reach you), and the Great Forge in the middle has a
 *    chimney deck with the 2 emerald generators.
 */
public final class BwMapDesigns {
    private BwMapDesigns() {}

    public static final String[] IDS = {"slumber", "ember"};
    public static final String[] NAMES = {"Slumber Isles", "Ember Forge"};

    /** Everything the game needs to know about a built map. Positions are feet positions (x, y, z). */
    public static final class MapData {
        public final String id, display;
        public final MapCanvas canvas = new MapCanvas();
        public final int[][] spawn = new int[4][];
        public final float[] spawnYaw = new float[4];
        public final int[][] bedFoot = new int[4][], bedHead = new int[4][];
        public final int[][] gen = new int[4][];
        public final int[][] shop = new int[4][], upgrades = new int[4][];
        public final float[] shopYaw = new float[4], upgradesYaw = new float[4];
        public final int[][] teamChest = new int[4][], enderChest = new int[4][];
        /** The centre of each team's island (traps, heal pool and the bed alarm work around it). */
        public final int[][] base = new int[4][];
        public final List<int[]> diamondGens = new ArrayList<>(), emeraldGens = new ArrayList<>();
        /** Players may build within this distance of the middle (x and z) ... */
        public int half;
        /** ... and between these heights. */
        public int buildTop = 28, buildBottom = -22;
        /** Below this height you fall into the void. */
        public int voidY = -32;

        MapData(String id, String display) {
            this.id = id;
            this.display = display;
        }
    }

    public static MapData build(String id) {
        return id.equals("ember") ? ember() : slumber();
    }

    // =====================================================================
    // Turning the design for each team
    // =====================================================================

    /** (x, z) turned t quarter-turns: (x, z) -> (-z, x). */
    public static int[] turn(int x, int z, int t) {
        for (int i = 0; i < (t & 3); i++) {
            int nx = -z;
            z = x;
            x = nx;
        }
        return new int[]{x, z};
    }

    static int[] turnPos(int[] p, int t) {
        int[] xz = turn(p[0], p[2], t);
        return new int[]{xz[0], p[1], xz[1]};
    }

    private static final String[] DIRS = {"north", "east", "south", "west"};

    private static String turnDir(String d, int t) {
        for (int i = 0; i < 4; i++) if (DIRS[i].equals(d)) return DIRS[(i + t) & 3];
        return d;
    }

    /** A block state turned t quarter-turns (facing, axis, side connections, sign/banner rotation). */
    public static String turnState(String state, int t) {
        if ((t & 3) == 0 || state.indexOf('[') < 0) return state;
        String name = MapCanvas.name(state);
        LinkedHashMap<String, String> p = MapCanvas.props(state);
        LinkedHashMap<String, String> out = new LinkedHashMap<>();
        Map<String, String> sides = new HashMap<>();
        for (Map.Entry<String, String> e : p.entrySet()) {
            String k = e.getKey(), v = e.getValue();
            switch (k) {
                case "facing" -> out.put(k, turnDir(v, t));
                case "axis" -> out.put(k, (t & 1) == 1 ? (v.equals("x") ? "z" : v.equals("z") ? "x" : v) : v);
                case "rotation" -> out.put(k, String.valueOf((Integer.parseInt(v) + 4 * t) & 15));
                case "north", "east", "south", "west" -> sides.put(turnDir(k, t), v);
                default -> out.put(k, v);
            }
        }
        out.putAll(sides);
        return MapCanvas.build(name, out);
    }

    private static String forTeam(String state, BwTeam team) {
        return state.startsWith("team_") ? team.dye + "_" + state.substring(5) : state;
    }

    /** Copies the Red design onto all four teams. */
    private static void stampTeams(MapCanvas red, MapData m) {
        for (BwTeam team : BwTeam.values()) {
            int t = team.turns();
            red.forEach((x, y, z, s) -> {
                int[] xz = turn(x, z, t);
                m.canvas.set(xz[0], y, xz[1], forTeam(turnState(s, t), team));
            });
        }
    }

    /** Fills in every team's key positions from Red's. */
    private static void positions(MapData m, int[] spawn, float yaw, int[] foot, int[] head, int[] gen,
                                  int[] shop, float shopYaw, int[] upg, float upgYaw, int[] chest, int[] ender, int[] base) {
        for (BwTeam team : BwTeam.values()) {
            int t = team.turns(), i = team.ordinal();
            m.spawn[i] = turnPos(spawn, t);
            m.spawnYaw[i] = yaw + 90f * t;
            m.bedFoot[i] = turnPos(foot, t);
            m.bedHead[i] = turnPos(head, t);
            m.gen[i] = turnPos(gen, t);
            m.shop[i] = turnPos(shop, t);
            m.shopYaw[i] = shopYaw + 90f * t;
            m.upgrades[i] = turnPos(upg, t);
            m.upgradesYaw[i] = upgYaw + 90f * t;
            m.teamChest[i] = turnPos(chest, t);
            m.enderChest[i] = turnPos(ender, t);
            m.base[i] = turnPos(base, t);
        }
    }

    /** Draws a shape that has 4-way symmetry: paint() is called for one quarter, then turned 3 more times. */
    private interface Quarter {
        void paint(MapCanvas q);
    }

    private static void fourWays(MapCanvas into, Quarter quarter) {
        MapCanvas q = new MapCanvas();
        quarter.paint(q);
        for (int t = 0; t < 4; t++) {
            final int tt = t;
            q.forEach((x, y, z, s) -> {
                int[] xz = turn(x, z, tt);
                into.set(xz[0], y, xz[1], turnState(s, tt));
            });
        }
    }

    // =====================================================================
    // Shared building pieces
    // =====================================================================

    /**
     * A floating island's underside: under every column of the top (y = 0), rock hangs down deeper
     * the further the column is from the edge. depthOf returns how far in from the edge (blocks).
     */
    private interface Edge {
        /** Distance in from the edge, or negative if outside. */
        double depth(int x, int z);
    }

    private static void underside(MapCanvas c, int x1, int z1, int x2, int z2, Edge edge, double scale, int maxDepth, int salt,
                                  String[] layers) {
        for (int x = x1; x <= x2; x++)
            for (int z = z1; z <= z2; z++) {
                double e = edge.depth(x, z);
                if (e < 0) continue;
                int depth = (int) Math.min(maxDepth, 1 + e * scale + smooth(x, z, 4, salt) * 3 + noise(x, z, salt + 1) * 1.5);
                for (int k = 1; k <= depth; k++) {
                    String s;
                    if (k == 1) s = layers[0];
                    else if (k <= 3) s = layers[1];
                    else s = noise3(x, -k, z, salt + 2) < 0.3 ? layers[3] : layers[2];
                    c.set(x, -k, z, s);
                }
            }
    }

    private static void lampPost(MapCanvas c, int x, int y, int z, String post, String base, String light, int height) {
        if (base != null) c.set(x, y, z, base);
        for (int h = base == null ? 0 : 1; h < height; h++) c.set(x, y + h, z, post);
        c.set(x, y + height, z, light);
    }

    private static void birch(MapCanvas c, int x, int y, int z, int h, String leaves) {
        for (int i = 0; i < h; i++) c.set(x, y + i, z, "birch_log[axis=y]");
        for (int dy = h - 3; dy <= h + 1; dy++) {
            int r = dy >= h ? 1 : 2;
            for (int dx = -r; dx <= r; dx++)
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.abs(dx) == r && Math.abs(dz) == r && (r == 2 || dy == h + 1)) continue;
                    if (dx == 0 && dz == 0 && dy < h) continue;
                    c.setIfAir(x + dx, y + dy, z + dz, leaves + "[persistent=true]");
                }
        }
    }

    private static void cherry(MapCanvas c, int x, int y, int z, int h) {
        for (int i = 0; i < h; i++) c.set(x, y + i, z, "cherry_log[axis=y]");
        for (int dy = h - 2; dy <= h + 1; dy++) {
            int r = dy == h + 1 ? 1 : dy == h - 2 ? 2 : 3;
            for (int dx = -r; dx <= r; dx++)
                for (int dz = -r; dz <= r; dz++) {
                    if (dx * dx + dz * dz > r * r + 1) continue;
                    if (dx == 0 && dz == 0 && dy < h) continue;
                    if (noise3(x + dx, y + dy, z + dz, 41) < 0.12) continue;
                    c.setIfAir(x + dx, y + dy, z + dz, "cherry_leaves[persistent=true]");
                }
        }
    }

    // =====================================================================
    // SLUMBER ISLES
    // =====================================================================

    /** Red's base centre (the others are this, turned). */
    private static final int S_BASE = 36;
    private static final int S_ISLAND = 10;     // base island half-size
    private static final int S_DIAMOND_R = 5;
    private static final int S_CENTER_R = 13;
    private static final int S_DECK_Y = 6, S_DECK_R = 7;

    private static MapData slumber() {
        MapData m = new MapData("slumber", "Slumber Isles");
        MapCanvas red = new MapCanvas();
        slumberBase(red, S_BASE, S_BASE);
        slumberDiamond(red, S_BASE, 0);
        slumberCloudBank(red, 58, 0);
        stampTeams(red, m);
        slumberCenter(m.canvas);
        m.canvas.connectFencesAndPanes();

        int b = S_BASE;
        positions(m,
                new int[]{b + 2, 1, b + 2}, 135f,
                new int[]{b - 5, 1, b - 6}, new int[]{b - 6, 1, b - 6},
                new int[]{b + 7, 1, b + 7},
                new int[]{b + 8, 1, b - 1}, 90f,
                new int[]{b - 1, 1, b + 8}, 180f,
                new int[]{b + 5, 1, b + 8}, new int[]{b + 8, 1, b + 5},
                new int[]{b, 1, b});
        for (int t = 0; t < 4; t++) m.diamondGens.add(turnPos(new int[]{S_BASE, 1, 0}, t));
        m.emeraldGens.add(new int[]{3, S_DECK_Y + 1, 0});
        m.emeraldGens.add(new int[]{-3, S_DECK_Y + 1, 0});
        m.half = S_BASE + S_ISLAND + 14;
        return m;
    }

    private static boolean inSlumberBase(int a, int b) {
        int r = S_ISLAND;
        if (Math.abs(a) > r || Math.abs(b) > r) return false;
        int ca = Math.abs(a) - (r - 3), cb = Math.abs(b) - (r - 3);
        return !(ca > 0 && cb > 0 && ca * ca + cb * cb > 9);
    }

    private static double slumberBaseEdge(int a, int b) {
        if (!inSlumberBase(a, b)) return -1;
        return Math.min(S_ISLAND - Math.abs(a), S_ISLAND - Math.abs(b));
    }

    /** Red's base: corner island at (bx, bz). The bed faces the middle (towards -x, -z). */
    private static void slumberBase(MapCanvas c, int bx, int bz) {
        int r = S_ISLAND;
        for (int a = -r; a <= r; a++)
            for (int b = -r; b <= r; b++) {
                if (!inSlumberBase(a, b)) continue;
                int x = bx + a, z = bz + b;
                double edge = slumberBaseEdge(a, b);
                String top;
                boolean plaza = Math.abs(a) <= 5 && Math.abs(b) <= 5;
                if (plaza) top = (Math.floorMod(a + b, 2) == 0) ? "birch_planks" : "stripped_birch_wood[axis=x]";
                else if (edge < 1) top = noise(x, z, 3) < 0.5 ? "moss_block" : "grass_block";
                else top = noise(x, z, 4) < 0.15 ? "moss_block" : "grass_block";
                c.set(x, 0, z, top);
            }
        // Plaza border and a team-coloured rug in front of the spawn.
        for (int a = -6; a <= 6; a++)
            for (int b = -6; b <= 6; b++)
                if ((Math.abs(a) == 6 || Math.abs(b) == 6) && inSlumberBase(a, b)) c.set(bx + a, 0, bz + b, "smooth_quartz");
        for (int a = 0; a <= 4; a++)
            for (int b = 0; b <= 4; b++) c.set(bx + a, 0, bz + b, (a == 0 || b == 0 || a == 4 || b == 4) ? "team_terracotta" : "team_wool");
        // Bedroom corner: floor of team wool around the bed (flat, so defences can be built on it).
        for (int a = -8; a <= -3; a++)
            for (int b = -8; b <= -4; b++) c.set(bx + a, 0, bz + b, (a == -8 || a == -3 || b == -8 || b == -4) ? "white_wool" : "team_wool");
        // Nightstands with lanterns either side of the bedroom corner (outside defence range).
        c.set(bx - 9, 1, bz - 2, "bookshelf");
        c.set(bx - 9, 2, bz - 2, "lantern");
        c.set(bx - 2, 1, bz - 9, "bookshelf");
        c.set(bx - 2, 2, bz - 9, "lantern");

        underside(c, bx - r, bz - r, bx + r, bz + r, (x, z) -> slumberBaseEdge(x - bx, z - bz), 1.25, 14, 11,
                new String[]{"dirt", "dirt", "calcite", "white_concrete"});
        clouds(c, bx, bz, r + 1, -8, 21);

        // Spawn pad.
        for (int a = 1; a <= 3; a++) for (int b = 1; b <= 3; b++) c.set(bx + a, 0, bz + b, "team_concrete");
        c.set(bx + 2, 0, bz + 2, "sea_lantern");

        // Home generator (outer corner): iron block in a quartz ring with lantern posts.
        int gx = bx + 7, gz = bz + 7;
        for (int a = -1; a <= 1; a++) for (int b = -1; b <= 1; b++) c.set(gx + a, 0, gz + b, "chiseled_quartz_block");
        c.set(gx, 0, gz, "iron_block");
        for (int[] d : new int[][]{{-2, 2}, {2, -2}, {2, 2}}) lampPost(c, gx + d[0], 1, gz + d[1], "birch_fence", null, "lantern", 2);

        // Shops: little stalls with team-coloured awnings.
        stall(c, bx + 8, bz - 1, "west");
        stall(c, bx - 1, bz + 8, "north");
        // Team chest and personal ender chest.
        c.set(bx + 5, 1, bz + 8, "chest[facing=north]");
        c.set(bx + 8, 1, bz + 5, "ender_chest[facing=west]");

        // Trees and flowers on the outer sides.
        birch(c, bx + 8, 1, bz - 8, 6, "birch_leaves");
        birch(c, bx - 8, 1, bz + 8, 6, "birch_leaves");
        for (int a = -r; a <= r; a++)
            for (int b = -r; b <= r; b++) {
                if (!inSlumberBase(a, b) || slumberBaseEdge(a, b) > 2) continue;
                if (a < -2 && b < -2) continue; // keep the bed side clear
                int x = bx + a, z = bz + b;
                if (!c.isAir(x, 1, z)) continue;
                double n = noise(x, z, 9);
                if (n < 0.10) c.set(x, 1, z, "white_tulip");
                else if (n < 0.16) c.set(x, 1, z, "allium");
                else if (n < 0.22) c.set(x, 1, z, "azure_bluet");
                else if (n < 0.27) c.set(x, 1, z, "lily_of_the_valley");
            }
        // Little moon lamp on the back corner.
        lampPost(c, bx + 9, 1, bz - 5, "quartz_pillar[axis=y]", null, "pearlescent_froglight[axis=y]", 3);
        lampPost(c, bx - 5, 1, bz + 9, "quartz_pillar[axis=y]", null, "pearlescent_froglight[axis=y]", 3);
    }

    /** A shop stall: the villager stands at (x, 1, z); three posts and an awning behind it. */
    private static void stall(MapCanvas c, int x, int z, String facing) {
        // The awning sits behind and above the villager, opening towards "facing".
        int bx = facing.equals("west") ? 1 : facing.equals("east") ? -1 : 0;
        int bz = facing.equals("north") ? 1 : facing.equals("south") ? -1 : 0;
        int sx = bz != 0 ? 1 : 0, sz = bx != 0 ? 1 : 0; // sideways
        c.set(x, 0, z, "smooth_quartz");
        for (int s = -1; s <= 1; s += 2) {
            int px = x + bx + sx * s * 1, pz = z + bz + sz * s * 1;
            for (int h = 1; h <= 3; h++) c.set(px, h, pz, "stripped_birch_log[axis=y]");
        }
        for (int s = -1; s <= 1; s++)
            for (int d = -1; d <= 1; d++) {
                int ax = x + bx * (1 - d) + sx * s, az = z + bz * (1 - d) + sz * s;
                c.set(ax, 4, az, (s + d) % 2 == 0 ? "team_wool" : "white_wool");
            }
        c.set(x + bx * 2, 1, z + bz * 2, "barrel[facing=up]");
        c.set(x + bx * 2, 2, z + bz * 2, "lantern");
    }

    /** Puffy clouds under and around an island. */
    private static void clouds(MapCanvas c, int cx, int cz, int radius, int y, int salt) {
        for (int dx = -radius - 4; dx <= radius + 4; dx++)
            for (int dz = -radius - 4; dz <= radius + 4; dz++) {
                double d = Math.sqrt(dx * dx + dz * dz);
                double puff = smooth(cx + dx, cz + dz, 5, salt) * 6 - 1.5;
                if (d > radius + puff) continue;
                int thick = 1 + (int) (smooth(cx + dx + 99, cz + dz, 4, salt + 1) * 3);
                for (int k = 0; k < thick; k++) c.setIfAir(cx + dx, y - k, cz + dz, k == 0 ? "white_concrete" : "snow_block");
            }
    }

    /** A cloud bank far out at the side (scenery, but you can bridge to it). */
    private static void slumberCloudBank(MapCanvas c, int x, int z) {
        clouds(c, x, z, 4, -3, 77);
        c.set(x, -2, z, "pearlescent_froglight[axis=y]");
    }

    /** The diamond island between Red and its neighbour: round, amethyst-crusted, with a diamond pedestal. */
    private static void slumberDiamond(MapCanvas c, int cx, int cz) {
        int r = S_DIAMOND_R;
        for (int dx = -r; dx <= r; dx++)
            for (int dz = -r; dz <= r; dz++) {
                double d = Math.sqrt(dx * dx + dz * dz);
                if (d > r + 0.4) continue;
                c.set(cx + dx, 0, cz + dz, d < 2.2 ? "smooth_quartz" : noise(cx + dx, cz + dz, 5) < 0.3 ? "amethyst_block" : "calcite");
            }
        underside(c, cx - r, cz - r, cx + r, cz + r, (x, z) -> r + 0.4 - Math.sqrt((x - cx) * (x - cx) + (z - cz) * (z - cz)),
                1.6, 10, 31, new String[]{"calcite", "calcite", "amethyst_block", "budding_amethyst"});
        c.set(cx, 0, cz, "diamond_block");
        // Crystal spires at the four diagonals (off the bridging lines).
        for (int[] d : new int[][]{{3, 3}, {-3, 3}, {3, -3}, {-3, -3}}) {
            c.set(cx + d[0], 1, cz + d[1], "amethyst_block");
            c.set(cx + d[0], 2, cz + d[1], "amethyst_cluster[facing=up]");
        }
        clouds(c, cx, cz, r, -6, 51);
    }

    /** The Moon Garden. */
    private static void slumberCenter(MapCanvas c) {
        int R = S_CENTER_R;
        fourWays(c, q -> {
            for (int x = 0; x <= R; x++)
                for (int z = 1; z <= R; z++) {
                    double d = Math.sqrt(x * x + z * z);
                    if (d > R + 0.4) continue;
                    // Lower floor: moon dust with crater rings.
                    String s = ((int) d) % 5 == 0 ? "smooth_stone" : noise(x, z, 61) < 0.25 ? "end_stone" : "end_stone_bricks";
                    if (d > R - 0.6) s = "chiseled_quartz_block";
                    q.set(x, 0, z, s);
                    // Upper deck (covers the inner ring).
                    if (d <= S_DECK_R + 0.4) {
                        q.set(x, S_DECK_Y, z, d > S_DECK_R - 0.6 ? "quartz_bricks" : noise(x, z, 62) < 0.5 ? "smooth_quartz" : "quartz_block");
                    }
                }
            // Underside: half a moon.
            for (int x = 0; x <= R; x++)
                for (int z = 1; z <= R; z++) {
                    double d = Math.sqrt(x * x + z * z);
                    if (d > R + 0.4) continue;
                    int depth = (int) Math.sqrt(Math.max(0, (R + 0.4) * (R + 0.4) - d * d));
                    for (int k = 1; k <= depth; k++) q.set(x, -k, z, noise3(x, -k, z, 63) < 0.2 ? "end_stone" : k > depth - 2 ? "smooth_stone" : "end_stone_bricks");
                }
            // Staircase up to the deck (2 wide, climbing towards the middle).
            for (int step = 0; step < 5; step++) {
                int z = 12 - step, top = step + 1;
                for (int x = 1; x <= 2; x++) {
                    for (int y = 1; y < top; y++) q.set(x, y, z, "quartz_bricks");
                    q.set(x, top, z, "quartz_stairs[facing=north,half=bottom]");
                }
            }
            // Deck pillars (off the axes and stairs).
            for (int[] p : new int[][]{{3, 6}, {6, 3}}) {
                for (int y = 1; y < S_DECK_Y; y++) q.set(p[0], y, p[1], "quartz_pillar[axis=y]");
                q.set(p[0], S_DECK_Y + 1, p[1], "end_rod[facing=up]");
            }
            // Lamps and moon rocks on the outer ring.
            lampPost(q, 9, 1, 8, "quartz_pillar[axis=y]", null, "pearlescent_froglight[axis=y]", 3);
            q.set(10, 1, 4, "calcite");
            q.set(11, 1, 4, "calcite");
            q.set(10, 2, 4, "amethyst_cluster[facing=up]");
            q.set(5, 1, 11, "calcite");
            q.set(5, 2, 11, "small_amethyst_bud[facing=up]");
        });
        // Middle column under the deck, and the origin cells the quarters don't cover.
        c.set(0, 0, 0, "sea_lantern");
        for (int y = 1; y < S_DECK_Y; y++) c.set(0, y, 0, y % 2 == 0 ? "quartz_pillar[axis=y]" : "sea_lantern");
        c.set(0, S_DECK_Y, 0, "chiseled_quartz_block");
        for (int k = 1; k <= S_CENTER_R; k++) c.set(0, -k, 0, "end_stone_bricks");
        c.set(0, S_DECK_Y + 1, 0, "end_rod[facing=up]");
        // Emerald pedestals on the deck.
        for (int sx = -1; sx <= 1; sx += 2) {
            c.set(3 * sx, S_DECK_Y, 0, "emerald_block");
            c.set(3 * sx, S_DECK_Y, 1, "chiseled_quartz_block");
            c.set(3 * sx, S_DECK_Y, -1, "chiseled_quartz_block");
        }
    }

    // =====================================================================
    // EMBER FORGE
    // =====================================================================

    private static final int E_BASE = 56;       // Red's base centre z (x = 0)
    private static final int E_HALF_W = 10, E_HALF_L = 10;
    private static final int E_DIAMOND_Z = 32, E_DIAMOND_R = 4;
    private static final int E_CENTER_R = 12;
    private static final int E_DECK_Y = 6, E_DECK_R = 6;

    private static MapData ember() {
        MapData m = new MapData("ember", "Ember Forge");
        MapCanvas red = new MapCanvas();
        emberBase(red, 0, E_BASE);
        emberDiamond(red, 0, E_DIAMOND_Z);
        emberSpire(red, 40, 40);
        stampTeams(red, m);
        emberCenter(m.canvas);
        m.canvas.connectFencesAndPanes();

        int b = E_BASE;
        positions(m,
                new int[]{0, 1, b + 1}, 180f,
                new int[]{0, 1, b - 6}, new int[]{0, 1, b - 7},
                new int[]{0, 1, b + 7},
                new int[]{7, 1, b}, 90f,
                new int[]{-7, 1, b}, -90f,
                new int[]{3, 1, b + 8}, new int[]{-3, 1, b + 8},
                new int[]{0, 1, b});
        for (int t = 0; t < 4; t++) m.diamondGens.add(turnPos(new int[]{0, 1, E_DIAMOND_Z}, t));
        m.emeraldGens.add(new int[]{3, E_DECK_Y + 1, 3});
        m.emeraldGens.add(new int[]{-3, E_DECK_Y + 1, -3});
        m.half = E_BASE + E_HALF_L + 12;
        return m;
    }

    private static boolean inEmberBase(int a, int b) {
        if (Math.abs(a) > E_HALF_W || Math.abs(b) > E_HALF_L) return false;
        // Notched corners, like a forge floor.
        return !(Math.abs(a) >= E_HALF_W - 1 && Math.abs(b) >= E_HALF_L - 1);
    }

    private static double emberEdge(int a, int b) {
        if (!inEmberBase(a, b)) return -1;
        return Math.min(E_HALF_W - Math.abs(a), E_HALF_L - Math.abs(b));
    }

    /** Red's base at (bx, bz), facing the middle (-z). */
    private static void emberBase(MapCanvas c, int bx, int bz) {
        for (int a = -E_HALF_W; a <= E_HALF_W; a++)
            for (int b = -E_HALF_L; b <= E_HALF_L; b++) {
                if (!inEmberBase(a, b)) continue;
                int x = bx + a, z = bz + b;
                double edge = emberEdge(a, b);
                String top;
                if (edge < 1) top = "polished_blackstone_bricks";
                else if (Math.abs(a) <= 2 || Math.abs(b) <= 1) top = "polished_blackstone";
                else top = noise(x, z, 101) < 0.2 ? "cracked_polished_blackstone_bricks" : noise(x, z, 102) < 0.3 ? "crimson_nylium" : "blackstone";
                c.set(x, 0, z, top);
            }
        underside(c, bx - E_HALF_W, bz - E_HALF_L, bx + E_HALF_W, bz + E_HALF_L, (x, z) -> emberEdge(x - bx, z - bz), 1.4, 15, 111,
                new String[]{"blackstone", "basalt[axis=y]", "blackstone", "gilded_blackstone"});

        // Bed floor at the front: team terracotta with a copper trim (flat for defences).
        for (int a = -3; a <= 3; a++)
            for (int b = -9; b <= -4; b++)
                c.set(bx + a, 0, bz + b, (Math.abs(a) == 3 || b == -9 || b == -4) ? "waxed_oxidized_cut_copper" : "team_terracotta");
        // Spawn pad.
        for (int a = -1; a <= 1; a++) for (int b = 0; b <= 2; b++) c.set(bx + a, 0, bz + b, "team_concrete");
        c.set(bx, 0, bz + 1, "shroomlight");

        // The forge at the back: generator in a furnace-lined bay.
        int gz = bz + 7;
        for (int a = -1; a <= 1; a++) for (int b = -1; b <= 1; b++) c.set(bx + a, 0, gz + b, "polished_basalt[axis=y]");
        c.set(bx, 0, gz, "iron_block");
        for (int a = -2; a <= 2; a += 4) {
            for (int h = 1; h <= 4; h++) c.set(bx + a, h, gz + 2, "polished_blackstone_bricks");
            c.set(bx + a, 1, gz + 1, "blast_furnace[facing=" + (a < 0 ? "east" : "west") + ",lit=true]");
            c.set(bx + a, 5, gz + 2, "lantern");
        }
        for (int a = -2; a <= 2; a++) c.set(bx + a, 4, gz + 2, "polished_blackstone_brick_slab[type=bottom]");
        c.set(bx, 3, gz + 2, "iron_chain[axis=y]");
        c.set(bx, 2, gz + 2, "lantern[hanging=true]");

        // Shops: blackstone booths with chains and lanterns.
        emberStall(c, bx + 7, bz, "west");
        emberStall(c, bx - 7, bz, "east");
        c.set(bx + 3, 1, bz + 8, "chest[facing=north]");
        c.set(bx - 3, 1, bz + 8, "ender_chest[facing=north]");

        // Basalt pillars with braziers on the corners.
        for (int[] d : new int[][]{{-8, -8}, {8, -8}, {-8, 8}, {8, 8}}) {
            for (int h = 1; h <= 3; h++) c.set(bx + d[0], h, bz + d[1], "polished_basalt[axis=y]");
            c.set(bx + d[0], 4, bz + d[1], "shroomlight");
        }
        // Crimson roots and fungi here and there (not near the bed).
        for (int a = -E_HALF_W; a <= E_HALF_W; a++)
            for (int b = -E_HALF_L; b <= E_HALF_L; b++) {
                if (!inEmberBase(a, b) || b < -3) continue;
                int x = bx + a, z = bz + b;
                if (!"crimson_nylium".equals(c.get(x, 0, z)) || !c.isAir(x, 1, z)) continue;
                double n = noise(x, z, 105);
                if (n < 0.35) c.set(x, 1, z, "crimson_roots");
                else if (n < 0.45) c.set(x, 1, z, "crimson_fungus");
            }
    }

    private static void emberStall(MapCanvas c, int x, int z, String facing) {
        int bx = facing.equals("west") ? 1 : -1;
        c.set(x, 0, z, "polished_blackstone");
        for (int s = -1; s <= 1; s += 2) for (int h = 1; h <= 3; h++) c.set(x + bx, h, z + s, "polished_blackstone_bricks");
        for (int s = -1; s <= 1; s++) {
            c.set(x + bx, 4, z + s, "polished_blackstone_brick_slab[type=bottom]");
            c.set(x, 4, z + s, s == 0 ? "team_wool" : "polished_blackstone_brick_slab[type=bottom]");
        }
        c.set(x + bx * 2, 1, z, "smithing_table");
        c.set(x + bx * 2, 2, z, "lantern");
        c.set(x + bx, 3, z, "iron_chain[axis=y]");
        c.set(x + bx, 2, z, "lantern[hanging=true]");
    }

    /** Red's own diamond island, straight in front of the base. */
    private static void emberDiamond(MapCanvas c, int cx, int cz) {
        int r = E_DIAMOND_R;
        for (int dx = -r; dx <= r; dx++)
            for (int dz = -r; dz <= r; dz++) {
                double d = Math.sqrt(dx * dx + dz * dz);
                if (d > r + 0.4) continue;
                c.set(cx + dx, 0, cz + dz, d < 1.8 ? "polished_blackstone" : noise(cx + dx, cz + dz, 121) < 0.3 ? "gilded_blackstone" : "blackstone");
            }
        underside(c, cx - r, cz - r, cx + r, cz + r, (x, z) -> r + 0.4 - Math.sqrt((x - cx) * (x - cx) + (z - cz) * (z - cz)),
                1.8, 10, 131, new String[]{"blackstone", "basalt[axis=y]", "blackstone", "magma_block"});
        c.set(cx, 0, cz, "diamond_block");
        for (int[] d : new int[][]{{3, 2}, {-3, 2}, {3, -2}, {-3, -2}}) {
            c.set(cx + d[0], 1, cz + d[1], "polished_blackstone_wall");
            c.set(cx + d[0], 2, cz + d[1], "soul_lantern");
        }
    }

    /** A basalt spire between bases (scenery). */
    private static void emberSpire(MapCanvas c, int cx, int cz) {
        for (int y = -10; y <= 9; y++) {
            double r = 3.2 - Math.abs(y + 1) * 0.16;
            for (int dx = -4; dx <= 4; dx++)
                for (int dz = -4; dz <= 4; dz++) {
                    if (Math.sqrt(dx * dx + dz * dz) > r + noise3(cx + dx, y, cz + dz, 141) * 0.8) continue;
                    c.set(cx + dx, y, cz + dz, noise3(cx + dx, y, cz + dz, 142) < 0.15 ? "magma_block" : "basalt[axis=y]");
                }
        }
        c.set(cx, 10, cz, "shroomlight");
    }

    /** The Great Forge in the middle. */
    private static void emberCenter(MapCanvas c) {
        int R = E_CENTER_R;
        fourWays(c, q -> {
            for (int x = 0; x <= R; x++)
                for (int z = 1; z <= R; z++) {
                    double d = Math.sqrt(x * x + z * z);
                    if (d > R + 0.4) continue;
                    String s = d > R - 0.6 ? "polished_blackstone_bricks" : ((int) (d + 0.5)) % 4 == 0 ? "waxed_cut_copper" : noise(x, z, 151) < 0.3 ? "cracked_polished_blackstone_bricks" : "polished_blackstone";
                    q.set(x, 0, z, s);
                    if (d <= E_DECK_R + 0.4) q.set(x, E_DECK_Y, z, d > E_DECK_R - 0.6 ? "polished_blackstone_bricks" : "smooth_basalt");
                    int depth = (int) (Math.sqrt(Math.max(0, (R + 0.4) * (R + 0.4) - d * d)) * 0.9);
                    for (int k = 1; k <= depth; k++) q.set(x, -k, z, noise3(x, -k, z, 152) < 0.2 ? "gilded_blackstone" : k < 3 ? "polished_blackstone" : "basalt[axis=y]");
                }
            // Stairs up to the deck along each arm (people arrive on the axes).
            for (int step = 0; step < 5; step++) {
                int z = 11 - step, top = step + 1;
                for (int x = 1; x <= 2; x++) {
                    for (int y = 1; y < top; y++) q.set(x, y, z, "polished_blackstone_bricks");
                    q.set(x, top, z, "polished_blackstone_brick_stairs[facing=north,half=bottom]");
                }
            }
            // Corner anvils and copper braziers on the lower ring.
            q.set(8, 1, 7, "anvil[facing=east]");
            lampPost(q, 9, 1, 3, "polished_basalt[axis=y]", null, "waxed_copper_lantern", 2);
            lampPost(q, 4, 1, 10, "polished_basalt[axis=y]", null, "waxed_copper_lantern", 2);
            // Deck supports.
            for (int y = 1; y < E_DECK_Y; y++) q.set(4, y, 4, "polished_basalt[axis=y]");
        });
        // Chimney in the very middle (from the deck up), with a glowing top.
        for (int y = 1; y <= 14; y++) c.set(0, y, 0, y < E_DECK_Y ? "shroomlight" : "polished_blackstone_bricks");
        c.set(0, 0, 0, "polished_blackstone");
        c.set(0, 15, 0, "shroomlight");
        for (int k = 1; k <= E_CENTER_R; k++) c.set(0, -k, 0, "basalt[axis=y]");
        // Emerald pedestals.
        c.set(3, E_DECK_Y, 3, "emerald_block");
        c.set(-3, E_DECK_Y, -3, "emerald_block");
    }

    // =====================================================================
    // Preview (for checking offline): a top-down map of one height
    // =====================================================================

    public static String preview(MapData m, int y, int step) {
        StringBuilder sb = new StringBuilder();
        for (int z = -m.half; z <= m.half; z += step) {
            for (int x = -m.half; x <= m.half; x += step) {
                char ch = '.';
                for (int[] s : m.spawn) if (s[0] == x && s[2] == z) ch = 'S';
                for (int[] b : m.bedFoot) if (b[0] == x && b[2] == z) ch = 'B';
                for (int[] g : m.gen) if (g[0] == x && g[2] == z) ch = 'G';
                for (int[] g : m.diamondGens) if (g[0] == x && g[2] == z) ch = 'D';
                for (int[] g : m.emeraldGens) if (g[0] == x && g[2] == z) ch = 'E';
                if (ch == '.') {
                    String s = m.canvas.get(x, y, z);
                    if (s != null) ch = s.contains("wool") || s.contains("terracotta") ? 'w' : '#';
                }
                sb.append(ch);
            }
            sb.append('\n');
        }
        return sb.toString();
    }
}
