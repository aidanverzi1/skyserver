package com.aidan.skyserver.goldwars;

import java.util.*;

/**
 * A plain-Java 3D grid of blocks used to design Gold Wars maps before they are placed
 * in the world. Coordinates are relative to the map center; y = 0 is the main floor level.
 *
 * Blocks are written as Minecraft block-state strings, e.g. "stone_bricks" or
 * "oak_stairs[facing=east,half=bottom]". Missing = air.
 */
public final class MapCanvas {
    private final HashMap<Long, String> blocks = new HashMap<>();

    // ---------------------------------------------------------------------
    // Basic access
    // ---------------------------------------------------------------------

    private static final int OFF = 1 << 20;
    private static final long MASK = (1L << 21) - 1;

    static long key(int x, int y, int z) {
        return (((long) (x + OFF)) & MASK) << 42 | (((long) (y + OFF)) & MASK) << 21 | (((long) (z + OFF)) & MASK);
    }

    static int kx(long k) { return (int) ((k >>> 42) & MASK) - OFF; }
    static int ky(long k) { return (int) ((k >>> 21) & MASK) - OFF; }
    static int kz(long k) { return (int) (k & MASK) - OFF; }

    public void set(int x, int y, int z, String state) {
        if (state == null || state.equals("air")) blocks.remove(key(x, y, z));
        else blocks.put(key(x, y, z), state);
    }

    public String get(int x, int y, int z) {
        return blocks.get(key(x, y, z));
    }

    public boolean isAir(int x, int y, int z) {
        return !blocks.containsKey(key(x, y, z));
    }

    /** Only places the block if the spot is currently empty. */
    public void setIfAir(int x, int y, int z, String state) {
        if (isAir(x, y, z)) set(x, y, z, state);
    }

    public void fill(int x1, int y1, int z1, int x2, int y2, int z2, String state) {
        for (int x = Math.min(x1, x2); x <= Math.max(x1, x2); x++)
            for (int y = Math.min(y1, y2); y <= Math.max(y1, y2); y++)
                for (int z = Math.min(z1, z2); z <= Math.max(z1, z2); z++)
                    set(x, y, z, state);
    }

    public void clearBox(int x1, int y1, int z1, int x2, int y2, int z2) {
        fill(x1, y1, z1, x2, y2, z2, null);
    }

    public int size() {
        return blocks.size();
    }

    /** Calls the action for every block: (x, y, z, state). */
    public void forEach(BlockAction action) {
        for (Map.Entry<Long, String> e : blocks.entrySet()) {
            long k = e.getKey();
            action.accept(kx(k), ky(k), kz(k), e.getValue());
        }
    }

    public interface BlockAction {
        void accept(int x, int y, int z, String state);
    }

    // ---------------------------------------------------------------------
    // Block-state string helpers
    // ---------------------------------------------------------------------

    public static String name(String state) {
        int i = state.indexOf('[');
        return i < 0 ? state : state.substring(0, i);
    }

    public static LinkedHashMap<String, String> props(String state) {
        LinkedHashMap<String, String> map = new LinkedHashMap<>();
        int i = state.indexOf('[');
        if (i < 0) return map;
        String inner = state.substring(i + 1, state.length() - 1);
        if (inner.isEmpty()) return map;
        for (String part : inner.split(",")) {
            String[] kv = part.split("=", 2);
            if (kv.length == 2) map.put(kv[0].trim(), kv[1].trim());
        }
        return map;
    }

    public static String build(String name, Map<String, String> props) {
        if (props.isEmpty()) return name;
        StringBuilder sb = new StringBuilder(name).append('[');
        boolean first = true;
        for (Map.Entry<String, String> e : props.entrySet()) {
            if (!first) sb.append(',');
            sb.append(e.getKey()).append('=').append(e.getValue());
            first = false;
        }
        return sb.append(']').toString();
    }

    private static final String[] NOT_FULL = {
            "stairs", "slab", "fence", "pane", "iron_bars", "lantern", "torch", "ladder", "carpet",
            "rail", "light", "short_grass", "tall_grass", "fern", "sapling", "roots", "campfire",
            "button", "trapdoor", "door", "dripstone", "pot", "poppy", "dandelion", "cornflower",
            "bluet", "daisy", "allium", "tulip", "orchid", "lily", "water", "lava", "snow", "anvil",
            "wheat", "carrots", "potatoes", "beetroots", "sugar_cane", "spore_blossom", "azalea",
            "vine", "chain", "wall", "flower", "bush", "bars", "head", "candle", "cobweb", "sign",
            "grindstone", "lectern", "bell", "pressure_plate", "lever", "farmland", "dirt_path",
            "amethyst_cluster", "bud", "cherry_leaves", "leaves", "moss_carpet", "barrier", "banner", "mushroom"
    };

    /** True for normal full-cube blocks (used for wall-line checks and fence connections). */
    public static boolean isFull(String state) {
        if (state == null) return false;
        String n = name(state);
        if (n.endsWith("_wall") || n.equals("cobblestone_wall")) return false;
        for (String s : NOT_FULL) {
            if (n.contains(s)) {
                // A few full blocks have these words in them.
                if (n.equals("dripstone_block") || n.equals("mossy_stone_bricks") || n.equals("moss_block")
                        || n.equals("hay_block") || n.equals("glowstone") || n.equals("sea_lantern")
                        || n.equals("redstone_lamp") || n.equals("jack_o_lantern")) return true;
                return false;
            }
        }
        return true;
    }

    // ---------------------------------------------------------------------
    // Mirroring and connections
    // ---------------------------------------------------------------------

    /**
     * Copies everything on the east side (x > 0) onto the west side (x < 0), flipped,
     * so both teams get an identical half. The x = 0 column is left as is.
     */
    public void mirrorEastToWest() {
        List<Long> west = new ArrayList<>();
        for (long k : blocks.keySet()) if (kx(k) < 0) west.add(k);
        for (long k : west) blocks.remove(k);

        List<Map.Entry<Long, String>> east = new ArrayList<>();
        for (Map.Entry<Long, String> e : blocks.entrySet()) if (kx(e.getKey()) > 0) east.add(e);
        for (Map.Entry<Long, String> e : east) {
            long k = e.getKey();
            blocks.put(key(-kx(k), ky(k), kz(k)), flipX(e.getValue()));
        }
    }

    /** Mirrors a block state across the x axis (east <-> west). */
    public static String flipX(String state) {
        if (state.indexOf('[') < 0) return state;
        String n = name(state);
        LinkedHashMap<String, String> p = props(state);
        if (p.containsKey("facing")) p.put("facing", swapEW(p.get("facing")));
        if (p.containsKey("east") || p.containsKey("west")) {
            String e = p.get("east"), w = p.get("west");
            if (w != null) p.put("east", w); else p.remove("east");
            if (e != null) p.put("west", e); else p.remove("west");
        }
        if (p.containsKey("shape")) {
            String s = p.get("shape");
            s = switch (s) {
                case "ascending_east" -> "ascending_west";
                case "ascending_west" -> "ascending_east";
                case "inner_left" -> "inner_right";
                case "inner_right" -> "inner_left";
                case "outer_left" -> "outer_right";
                case "outer_right" -> "outer_left";
                case "south_east" -> "south_west";
                case "south_west" -> "south_east";
                case "north_east" -> "north_west";
                case "north_west" -> "north_east";
                default -> s;
            };
            p.put("shape", s);
        }
        if (p.containsKey("hinge")) p.put("hinge", p.get("hinge").equals("left") ? "right" : "left");
        return build(n, p);
    }

    private static String swapEW(String d) {
        return d.equals("east") ? "west" : d.equals("west") ? "east" : d;
    }

    /** Sets fence / glass pane / iron bar connections so they join up like a player built them. */
    public void connectFencesAndPanes() {
        List<Map.Entry<Long, String>> todo = new ArrayList<>();
        for (Map.Entry<Long, String> e : blocks.entrySet()) {
            String n = name(e.getValue());
            if ((n.endsWith("_fence") || n.endsWith("glass_pane") || n.equals("iron_bars"))) todo.add(e);
        }
        int[][] dirs = {{0, -1}, {1, 0}, {0, 1}, {-1, 0}};
        String[] names = {"north", "east", "south", "west"};
        for (Map.Entry<Long, String> e : todo) {
            long k = e.getKey();
            int x = kx(k), y = ky(k), z = kz(k);
            String self = name(e.getValue());
            boolean fence = self.endsWith("_fence");
            LinkedHashMap<String, String> p = props(e.getValue());
            for (int i = 0; i < 4; i++) {
                String other = get(x + dirs[i][0], y, z + dirs[i][1]);
                boolean join = false;
                if (other != null) {
                    String on = name(other);
                    if (fence) join = on.endsWith("_fence") || isFull(other);
                    else join = on.endsWith("glass_pane") || on.equals("iron_bars") || isFull(other);
                }
                p.put(names[i], join ? "true" : "false");
            }
            blocks.put(k, build(self, p));
        }
    }

    // ---------------------------------------------------------------------
    // Noise (repeatable pseudo-random numbers, the same on every rebuild)
    // ---------------------------------------------------------------------

    public static double noise(int x, int z, int salt) {
        long h = x * 341873128712L + z * 132897987541L + salt * 2654435761L;
        h = (h ^ (h >>> 13)) * 1274126177L;
        h ^= h >>> 16;
        return (h & 0xFFFF) / 65536.0;
    }

    public static double noise3(int x, int y, int z, int salt) {
        return noise(x + y * 7919, z - y * 104729, salt);
    }

    /** Smooth value noise blended across a grid. */
    public static double smooth(int x, int z, int scale, int salt) {
        int gx = Math.floorDiv(x, scale), gz = Math.floorDiv(z, scale);
        double fx = (x - gx * scale) / (double) scale, fz = (z - gz * scale) / (double) scale;
        fx = fx * fx * (3 - 2 * fx);
        fz = fz * fz * (3 - 2 * fz);
        double a = noise(gx, gz, salt), b = noise(gx + 1, gz, salt);
        double c = noise(gx, gz + 1, salt), d = noise(gx + 1, gz + 1, salt);
        double top = a + (b - a) * fx, bottom = c + (d - c) * fx;
        return top + (bottom - top) * fz;
    }

    /** Picks one of the options using noise at a position (a simple texture "gradient"). */
    public static String pick(int x, int y, int z, int salt, String... options) {
        int i = (int) (noise3(x, y, z, salt) * options.length);
        return options[Math.min(i, options.length - 1)];
    }
}
