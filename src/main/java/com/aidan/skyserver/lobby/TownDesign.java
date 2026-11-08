package com.aidan.skyserver.lobby;

import java.util.*;
import java.util.function.Consumer;

import static com.aidan.skyserver.goldwars.MapCanvas.noise;
import static com.aidan.skyserver.lobby.LobbyDesign.G;

/**
 * The Town: a street south-east of the castle (plain Java, previewable offline).
 *
 *  North side: Bank, Skin Shop, Farmers' Market, Fighters' Guild, Builders' Gallery, Faction Hall.
 *  South side: Shepherd's Fields (a fenced pasture) and the Mine Head, whose stairs go down to the
 *  Mine: a main hall with eight ore galleries (coal, iron, copper, gold, redstone, lapis, diamond,
 *  emerald) that open up as your Mining skill grows.
 *
 * A road runs from the east gate path down to the street.
 */
public final class TownDesign {
    private TownDesign() {}

    // ---- The Mine's tables (edit here): one entry per gallery, coal .. emerald ----
    public static final String[] GALLERY_NAMES = {"Coal Seam", "Iron Drift", "Copper Vein", "Gold Reef", "Redstone Run", "Lapis Hollow", "Diamond Deep", "Emerald Heart"};
    /** Mining level needed for each gallery. */
    public static final int[] MINE_LEVELS = {1, 5, 10, 15, 20, 25, 35, 50};
    /** How many ore blocks each gallery has. */
    public static final int[] MINE_COUNTS = {12, 8, 8, 6, 6, 4, 2, 2};
    /** Seconds for a mined ore to grow back. */
    public static final int[] MINE_REGROW_SECONDS = {60, 120, 120, 240, 180, 240, 900, 1200};
    /** Mining XP per ore. */
    public static final int[] MINE_XP = {36, 60, 55, 100, 90, 100, 225, 350};
    public static final String[] MINE_ORES = {"coal_ore", "iron_ore", "copper_ore", "gold_ore", "redstone_ore", "lapis_ore", "diamond_ore", "emerald_ore"};
    static final String[] MINE_MARKERS = {"coal_block", "iron_block", "cut_copper", "gold_block", "redstone_block", "lapis_block", "diamond_block", "emerald_block"};
    public static final int STREET_Z = 100, STREET_X1 = 126, STREET_X2 = 316;

    /** A building or area. skill/level = what it takes to go inside (null = open to everyone). */
    public record Area(String id, String name, String skill, int level, ActivityDesign.Area inside, ActivityDesign.Spot door,
                       ActivityDesign.Area pad, String color) {}

    public static final List<Area> AREAS = new ArrayList<>();

    // ---- The mine ----
    public static final int MINE_FLOOR = G - 16;           // the block you walk on (y 84)
    public static final int HALL_X1 = 214, HALL_X2 = 322, HALL_Z1 = 125, HALL_Z2 = 131;
    public static final int GALLERY_X0 = 226, GALLERY_STEP = 12, GALLERY_W = 9, GALLERY_Z2 = 146;
    public static final int MINE_BOX_X1 = 206, MINE_BOX_X2 = 330, MINE_BOX_Z1 = 104, MINE_BOX_Z2 = 152;
    public static final ActivityDesign.Area MINE = new ActivityDesign.Area(MINE_BOX_X1, MINE_FLOOR - 2, MINE_BOX_Z1, MINE_BOX_X2, G - 1, MINE_BOX_Z2);
    public static final ActivityDesign.Spot MINE_SPOT = new ActivityDesign.Spot(220.5, MINE_FLOOR + 1, 127.5, -90f);
    public static final ActivityDesign.Spot TOWN_SPOT = new ActivityDesign.Spot(140.5, G + 1, STREET_Z + 0.5, -90f);

    /** Gallery k (0 coal .. 7 emerald): its inside, x1..x2 and z from the hall to GALLERY_Z2. */
    public static ActivityDesign.Area gallery(int k) {
        int x1 = GALLERY_X0 + GALLERY_STEP * k;
        return new ActivityDesign.Area(x1, MINE_FLOOR + 1, HALL_Z2 + 1, x1 + GALLERY_W - 1, MINE_FLOOR + 5, GALLERY_Z2);
    }

    /** Where the ore blocks are in gallery k (walls and end wall), as {x, y, z}. */
    public static List<int[]> oreSlots(int k, int count) {
        ActivityDesign.Area g = gallery(k);
        List<int[]> all = new ArrayList<>();
        for (int z = g.z1() + 1; z <= g.z2(); z++) {
            if (z >= g.z1() + 2 && z < g.z2() && (z - g.z1() - 2) % 4 == 0) continue; // timber supports stand in front of the wall here
            for (int y = MINE_FLOOR + 1; y <= MINE_FLOOR + 4; y++) {
                all.add(new int[]{g.x1() - 1, y, z});
                all.add(new int[]{g.x2() + 1, y, z});
            }
        }
        for (int x = g.x1(); x <= g.x2(); x++)
            for (int y = MINE_FLOOR + 1; y <= MINE_FLOOR + 4; y++) all.add(new int[]{x, y, g.z2() + 1});
        // Spread them out evenly, always the same ones.
        Collections.shuffle(all, new Random(900 + k));
        return all.subList(0, Math.min(count, all.size()));
    }

    static {
        int[][] north = {{134, 156}, {162, 186}, {192, 216}, {222, 246}, {252, 276}, {282, 306}};
        String[][] info = {
                {"bank", "Bank", null, "0", "§6"},
                {"skins", "Skin Shop", null, "0", "§d"},
                {"market", "Farmers' Market", "FARMING", "5", "§a"},
                {"guild", "Fighters' Guild", "COMBAT", "5", "§c"},
                {"gallery", "Builders' Gallery", "BUILDING", "5", "§6"},
                {"factions", "Faction Hall", null, "0", "§9"}};
        for (int i = 0; i < north.length; i++) {
            int x1 = north[i][0], x2 = north[i][1], cx = (x1 + x2) / 2;
            ActivityDesign.Area inside = new ActivityDesign.Area(x1 + 1, G + 1, 77, x2 - 1, G + 7, 95);
            ActivityDesign.Area pad = new ActivityDesign.Area(cx - 1, G + 1, 80, cx + 1, G + 2, 82);
            AREAS.add(new Area(info[i][0], info[i][1], info[i][2], Integer.parseInt(info[i][3]), inside,
                    new ActivityDesign.Spot(cx + 0.5, G + 1, 98.5, 180f), pad, info[i][4]));
        }
        AREAS.add(new Area("fields", "Shepherd's Fields", "SHEPHERD", 5, new ActivityDesign.Area(135, G + 1, 106, 199, G + 8, 140),
                new ActivityDesign.Spot(167.5, G + 1, 103.5, 0f), new ActivityDesign.Area(166, G + 1, 108, 168, G + 2, 110), "§f"));
    }

    public static Area area(String id) {
        for (Area a : AREAS) if (a.id().equals(id)) return a;
        return null;
    }

    public static boolean inArea(int x, int z) {
        if (x >= 120 && x <= 336 && z >= 70 && z <= 156) return true;
        return x >= 124 && x <= 132 && z >= 4 && z <= 100; // the road from the east path
    }

    // =====================================================================
    // Painting
    // =====================================================================

    static void layout(ActivityDesign.PartAdder part) {
        part.add(120, 70, 336, 156, TownDesign::clear);
        part.add(124, 4, 132, 104, TownDesign::road);
        part.add(STREET_X1, STREET_Z - 3, STREET_X2 + 4, STREET_Z + 3, TownDesign::street);
        for (Area a : AREAS) {
            if (a.id().equals("fields")) continue;
            final Area aa = a;
            part.add(a.inside().x1() - 2, 75, a.inside().x2() + 2, 97, c -> building(c, aa));
        }
        part.add(132, 104, 202, 142, TownDesign::fields);
        part.add(MINE_BOX_X1, MINE_BOX_Z1, MINE_BOX_X2, MINE_BOX_Z2, TownDesign::mine);

        for (Area a : AREAS) {
            String need = a.skill() == null ? "§7Open to everyone" : "§7Needs " + a.skill().charAt(0) + a.skill().substring(1).toLowerCase() + " level " + a.level();
            String what = switch (a.id()) {
                case "bank" -> "§7Earn 1% a week: §e/bank";
                case "skins" -> "§7Buy and wear skins: §e/skins";
                case "market" -> "§7Daily crate + Farmer Hal's orders";
                case "guild" -> "§7Daily crate + Sergeant Vex's Gauntlet";
                case "gallery" -> "§7Daily crate + the weekly build contest";
                case "factions" -> "§7Make or join a faction: §e/f";
                case "fields" -> "§7Daily crate + Shepherd Mae's shop and orders";
                default -> "";
            };
            ActivityDesign.Spot d = a.door();
            LobbyDesign.HOLOS.add(new LobbyDesign.Holo(d.x(), G + 5.6, a.id().equals("fields") ? d.z() + 1 : d.z() - 3.5,
                    List.of(a.color() + "§l" + a.name().toUpperCase(), what, need)));
            if (a.pad() != null && !a.id().equals("factions") && !a.id().equals("bank") && !a.id().equals("skins")) {
                ActivityDesign.Area p = a.pad();
                LobbyDesign.HOLOS.add(new LobbyDesign.Holo((p.x1() + p.x2() + 1) / 2.0, G + 3.2, (p.z1() + p.z2() + 1) / 2.0,
                        List.of("§eDaily crate", "§7Right-click the barrel")));
            }
        }
        LobbyDesign.HOLOS.add(new LobbyDesign.Holo(227.5, G + 5.5, 104.5, List.of("§8§lTHE MINE", "§7Mine coal - more ores open up", "§7as your Mining skill grows")));
        LobbyDesign.HOLOS.add(new LobbyDesign.Holo(128.5, G + 4.5, 8.5, List.of("§e§lTOWN §7→", "§7Bank, skins, skills, factions, the mine")));
        String[] ores = GALLERY_NAMES;
        for (int k = 0; k < ores.length; k++) {
            ActivityDesign.Area g = gallery(k);
            LobbyDesign.HOLOS.add(new LobbyDesign.Holo((g.x1() + g.x2() + 1) / 2.0, MINE_FLOOR + 4.6, HALL_Z2 - 0.5,
                    List.of("§f§l" + ores[k].toUpperCase(), MINE_LEVELS[k] <= 1 ? "§7Open to everyone" : "§7Mining level " + MINE_LEVELS[k])));
        }
    }

    private static void clear(LobbyCanvas c) {
        for (int x = c.minX; x <= c.maxX; x++)
            for (int z = c.minZ; z <= c.maxZ; z++) {
                if (!inArea(x, z) || LobbyDesign.edgeDepth(x, z) < 0) continue;
                for (int y = G + 1; y <= G + 30; y++) c.set(x, y, z, null);
            }
    }

    private static void road(LobbyCanvas c) {
        for (int z = 4; z <= 104; z++)
            for (int x = 125; x <= 131; x++) {
                c.set(x, G, z, x == 125 || x == 131 ? "coarse_dirt" : "dirt_path");
                for (int y = G + 1; y <= G + 4; y++) c.set(x, y, z, null);
            }
        for (int z = 12; z <= 96; z += 14) {
            c.set(132, G + 1, z, "stone_brick_wall");
            c.set(132, G + 2, z, "lantern");
        }
    }

    private static void street(LobbyCanvas c) {
        for (int x = STREET_X1; x <= STREET_X2 + 4; x++)
            for (int z = STREET_Z - 3; z <= STREET_Z + 3; z++) {
                String s = Math.abs(z - STREET_Z) == 3 ? "polished_andesite" : (noise(x, z, 811) < 0.25 ? "cobblestone" : "stone_bricks");
                c.set(x, G, z, s);
                for (int y = G + 1; y <= G + 5; y++) c.set(x, y, z, null);
            }
        for (int x = STREET_X1 + 4; x <= STREET_X2; x += 10)
            for (int z : new int[]{STREET_Z - 3, STREET_Z + 3}) {
                c.set(x, G + 1, z, "spruce_fence");
                c.set(x, G + 2, z, "spruce_fence");
                c.set(x, G + 3, z, "lantern");
            }
    }

    private static void building(LobbyCanvas c, Area a) {
        ActivityDesign.Area in = a.inside();
        int x1 = in.x1() - 1, x2 = in.x2() + 1, z1 = 76, z2 = 96, cx = (x1 + x2) / 2;
        String wall, trim, floor, roof, stairs;
        switch (a.id()) {
            case "bank" -> { wall = "quartz_bricks"; trim = "gold_block"; floor = "polished_diorite"; roof = "smooth_quartz_slab[type=bottom]"; stairs = "quartz_stairs"; }
            case "skins" -> { wall = "purpur_block"; trim = "magenta_terracotta"; floor = "pink_concrete"; roof = "purpur_slab[type=bottom]"; stairs = "purpur_stairs"; }
            case "market" -> { wall = "spruce_planks"; trim = "stripped_spruce_log[axis=y]"; floor = "hay_block[axis=y]"; roof = "spruce_slab[type=bottom]"; stairs = "dark_oak_stairs"; }
            case "guild" -> { wall = "deepslate_bricks"; trim = "red_nether_bricks"; floor = "polished_blackstone"; roof = "deepslate_brick_slab[type=bottom]"; stairs = "deepslate_brick_stairs"; }
            case "gallery" -> { wall = "white_concrete"; trim = "orange_terracotta"; floor = "smooth_stone"; roof = "smooth_stone_slab[type=bottom]"; stairs = "cut_copper_stairs"; }
            default -> { wall = "blue_terracotta"; trim = "dark_prismarine"; floor = "polished_andesite"; roof = "prismarine_brick_slab[type=bottom]"; stairs = "prismarine_brick_stairs"; }
        }
        for (int x = x1; x <= x2; x++)
            for (int z = z1; z <= z2; z++) {
                boolean edge = x == x1 || x == x2 || z == z1 || z == z2;
                boolean corner = (x == x1 || x == x2) && (z == z1 || z == z2);
                c.set(x, G, z, edge ? "stone_bricks" : floor);
                for (int y = G + 1; y <= G + 7; y++) {
                    String s = null;
                    if (edge) {
                        s = corner || (x - x1) % 6 == 0 && (z == z1 || z == z2) ? trim : wall;
                        if (y >= G + 3 && y <= G + 5 && !corner && ((x - x1) % 3 == 1 || z != z1 && z != z2 && (z - z1) % 3 == 1)) s = "glass_pane";
                    }
                    c.set(x, y, z, s);
                }
                c.set(x, G + 8, z, edge ? wall : roof.replace("[type=bottom]", "[type=top]"));
                if (edge) c.set(x, G + 9, z, roof);
            }
        // Pitched roof over the whole building (gable ends filled with wall, a window in each).
        for (int layer = 0; layer <= (z2 - z1) / 2 + 1; layer++) {
            int y = G + 9 + layer, za = z1 - 1 + layer, zb = z2 + 1 - layer;
            if (za > zb) break;
            for (int x = x1 - 1; x <= x2 + 1; x++) {
                boolean gable = x == x1 || x == x2;
                if (za == zb) {
                    c.set(x, y, za, gable ? wall : roof);
                    continue;
                }
                c.set(x, y, za, gable && layer > 0 ? wall : stairs + "[facing=south,half=bottom]");
                c.set(x, y, zb, gable && layer > 0 ? wall : stairs + "[facing=north,half=bottom]");
                if (gable) for (int z = za + 1; z < zb; z++) c.set(x, y, z, Math.abs(z - (z1 + z2) / 2) <= 1 && layer > 1 && layer < 6 ? "glass_pane" : wall);
            }
        }
        // Door on the street side (3 wide, 4 tall).
        for (int x = cx - 1; x <= cx + 1; x++)
            for (int y = G + 1; y <= G + 4; y++) c.set(x, y, z2, null);
        c.set(cx - 2, G + 4, z2 + 1, "lantern");
        c.set(cx + 2, G + 4, z2 + 1, "lantern");
        // Lights inside.
        for (int x = x1 + 3; x < x2; x += 5)
            for (int z = z1 + 4; z < z2; z += 6) c.set(x, G + 7, z, "lantern[hanging=true]");
        // The gold plate (daily crate / shop counter) in the back.
        ActivityDesign.Area p = a.pad();
        for (int x = p.x1(); x <= p.x2(); x++)
            for (int z = p.z1(); z <= p.z2(); z++) c.set(x, G, z, "gold_block");
        if (a.skill() == null) c.set(cx, G + 1, p.z1() - 1, "light_weighted_pressure_plate");
        else {
            // The daily crate (right-click) on a little stand, and room for the person who works here.
            c.set(cx, G + 1, p.z1() - 1, "barrel[facing=up]");
            c.set(cx - 1, G + 1, p.z1() - 1, trim);
            c.set(cx + 1, G + 1, p.z1() - 1, trim);
            c.set(cx, G + 2, p.z1() - 2, "lantern");
        }
        // A few props per building.
        switch (a.id()) {
            case "bank" -> {
                for (int x = x1 + 2; x <= x2 - 2; x++) c.set(x, G + 1, 86, x == cx ? null : "polished_andesite");
                for (int x = x1 + 2; x <= x2 - 2; x += 4) c.set(x, G + 2, 86, "gold_block");
                c.set(x1 + 2, G + 1, 79, "barrel[facing=up]");
                c.set(x2 - 2, G + 1, 79, "barrel[facing=up]");
            }
            case "skins" -> {
                for (int z = 80; z <= 92; z += 4) {
                    c.set(x1 + 2, G + 1, z, "armor_stand_placeholder");
                    c.set(x2 - 2, G + 1, z, "armor_stand_placeholder");
                }
            }
            case "market" -> {
                // Market stalls along the back, a crop bed down the middle with a little fence.
                for (int x = x1 + 2; x <= x2 - 2; x += 3) {
                    c.set(x, G + 1, 91, x % 2 == 0 ? "pumpkin" : "melon");
                    c.set(x, G + 1, 93, "barrel[facing=up]");
                    c.set(x, G + 2, 93, x % 2 == 0 ? "hay_block[axis=y]" : "composter");
                }
                String[] crops = {"wheat[age=7]", "carrots[age=7]", "potatoes[age=7]", "beetroots[age=3]"};
                for (int x = x1 + 3; x <= x2 - 3; x++)
                    for (int z = 85; z <= 88; z++) {
                        boolean water = z == 86 && x == cx;
                        // Not real farmland (jumping on farmland tramples it): the crops sit on rooted dirt.
                        c.set(x, G, z, water ? "water" : "rooted_dirt");
                        c.set(x, G + 1, z, water ? null : crops[(x + z) % crops.length]);
                    }
                c.set(x1 + 2, G + 1, 79, "smoker[facing=south]");
                c.set(x2 - 2, G + 1, 79, "beehive[facing=south]");
            }
            case "guild" -> {
                // Training dummies, a sparring ring and weapon racks.
                for (int x = x1 + 3; x <= x2 - 3; x += 4) {
                    c.set(x, G + 1, 84, "spruce_fence");
                    c.set(x, G + 2, 84, "hay_block[axis=y]");
                    c.set(x, G + 3, 84, "carved_pumpkin[facing=south]");
                }
                for (int x = cx - 4; x <= cx + 4; x++)
                    for (int z = 87; z <= 92; z++) {
                        boolean edge = x == cx - 4 || x == cx + 4 || z == 87 || z == 92;
                        c.set(x, G, z, edge ? "red_nether_bricks" : "red_terracotta");
                    }
                for (int z = 80; z <= 92; z += 3) {
                    c.set(x1 + 1, G + 2, z, "spruce_trapdoor[half=top,open=true,facing=east]");
                    c.set(x2 - 1, G + 2, z, "spruce_trapdoor[half=top,open=true,facing=west]");
                }
                c.set(x1 + 2, G + 1, 94, "anvil");
                c.set(x2 - 2, G + 1, 94, "grindstone[face=floor,facing=north]");
                c.set(x1 + 2, G + 1, 79, "smithing_table");
            }
            case "gallery" -> {
                // Display pedestals with block samples, and a model house on a plinth in the middle.
                String[] blocks = {"cherry_planks", "mud_bricks", "copper_block", "prismarine_bricks", "terracotta", "calcite", "amethyst_block", "bamboo_mosaic",
                        "tuff_bricks", "quartz_bricks", "deepslate_tiles", "polished_blackstone_bricks"};
                for (int i = 0; i < 6; i++) {
                    int x = x1 + 2 + i * 3;
                    if (x >= x2 - 1) break;
                    c.set(x, G + 1, 93, "polished_andesite");
                    c.set(x, G + 2, 93, blocks[i]);
                    c.set(x, G + 1, 84, "polished_andesite");
                    c.set(x, G + 2, 84, blocks[6 + i]);
                }
                for (int x = cx - 2; x <= cx + 2; x++)
                    for (int z = 87; z <= 90; z++) {
                        c.set(x, G + 1, z, "smooth_quartz");
                        boolean edge = x == cx - 2 || x == cx + 2 || z == 87 || z == 90;
                        boolean door = x == cx && z == 90;
                        c.set(x, G + 2, z, door ? "spruce_door[half=lower,facing=south]" : edge ? "white_terracotta" : null);
                        c.set(x, G + 3, z, door ? "spruce_door[half=upper,facing=south]" : edge ? "white_terracotta" : null);
                        c.set(x, G + 4, z, "brick_slab[type=bottom]");
                    }
                c.set(x1 + 2, G + 1, 79, "loom[facing=south]");
                c.set(x2 - 2, G + 1, 79, "stonecutter[facing=south]");
            }
            default -> {
                for (int z = 84; z <= 92; z += 4) {
                    c.set(cx - 3, G + 1, z, "blue_banner[rotation=0]");
                    c.set(cx + 3, G + 1, z, "red_banner[rotation=0]");
                }
                c.set(cx, G + 1, 88, "lectern[facing=south]");
            }
        }
        // Clear any placeholders we can't place as blocks.
        for (int x = x1; x <= x2; x++)
            for (int z = z1; z <= z2; z++)
                if ("armor_stand_placeholder".equals(c.get(x, G + 1, z))) c.set(x, G + 1, z, "quartz_pillar[axis=y]");
    }

    private static void fields(LobbyCanvas c) {
        ActivityDesign.Area in = area("fields").inside();
        for (int x = in.x1() - 1; x <= in.x2() + 1; x++)
            for (int z = in.z1() - 1; z <= in.z2() + 1; z++) {
                boolean edge = x == in.x1() - 1 || x == in.x2() + 1 || z == in.z1() - 1 || z == in.z2() + 1;
                c.set(x, G, z, noise(x, z, 831) < 0.15 ? "moss_block" : "grass_block");
                c.set(x, G + 1, z, edge ? "oak_fence" : (noise(x, z, 832) < 0.06 ? "short_grass" : noise(x, z, 833) < 0.01 ? "dandelion" : null));
            }
        // Gate gap in the middle of the street side, a shelter and a water trough.
        for (int x = 166; x <= 168; x++) c.set(x, G + 1, in.z1() - 1, null);
        for (int x = 180; x <= 188; x++)
            for (int z = 128; z <= 134; z++) {
                boolean edge = x == 180 || x == 188 || z == 128 || z == 134;
                if (edge && (x == 180 || x == 188) && (z == 128 || z == 134)) for (int y = G + 1; y <= G + 3; y++) c.set(x, y, z, "oak_log[axis=y]");
                c.set(x, G + 4, z, "oak_slab[type=bottom]");
            }
        for (int x = 145; x <= 150; x++) {
            c.set(x, G, 125, "water");
            c.set(x, G + 1, 124, "oak_slab[type=bottom]");
            c.set(x, G + 1, 126, "oak_slab[type=bottom]");
        }
        ActivityDesign.Area p = area("fields").pad();
        for (int x = p.x1(); x <= p.x2(); x++)
            for (int z = p.z1(); z <= p.z2(); z++) c.set(x, G, z, "gold_block");
        // The daily crate, and a little red barn with hay inside next to where Shepherd Mae stands.
        c.set(167, G + 1, 112, "barrel[facing=up]");
        c.set(166, G + 1, 112, "hay_block[axis=y]");
        c.set(168, G + 1, 112, "hay_block[axis=y]");
        for (int x = 173; x <= 180; x++)
            for (int z = 113; z <= 119; z++) {
                boolean edge = x == 173 || x == 180 || z == 113 || z == 119;
                boolean door = z == 113 && x >= 175 && x <= 177;
                c.set(x, G, z, "spruce_planks");
                for (int y = G + 1; y <= G + 4; y++)
                    c.set(x, y, z, edge && !(door && y <= G + 3) ? (y == G + 4 ? "stripped_spruce_log[axis=x]" : "red_terracotta") : null);
                c.set(x, G + 5, z, "dark_oak_slab[type=bottom]");
            }
        for (int x = 174; x <= 179; x++) c.set(x, G + 1, 118, "hay_block[axis=y]");
    }

    // ---- The mine ----

    private static void mine(LobbyCanvas c) {
        // Solid rock first (the island is only a few blocks thick here).
        for (int x = MINE_BOX_X1; x <= MINE_BOX_X2; x++)
            for (int z = MINE_BOX_Z1; z <= MINE_BOX_Z2; z++) {
                if (!c.in(x, z)) continue;
                for (int y = MINE_FLOOR - 3; y <= G - 4; y++) {
                    double n = noise(x * 3 + y, z * 3 - y, 840);
                    c.set(x, y, z, n < 0.12 ? "andesite" : n < 0.2 ? "tuff" : n < 0.24 ? "gravel" : "stone");
                }
                // Rocky underside.
                double h = noise(x, z, 841) * 4;
                for (int y = MINE_FLOOR - 4; y >= MINE_FLOOR - 4 - h; y--) c.set(x, y, z, "stone");
            }
        // Main hall with rails and timber supports.
        for (int x = HALL_X1; x <= HALL_X2; x++)
            for (int z = HALL_Z1; z <= HALL_Z2; z++) {
                c.set(x, MINE_FLOOR, z, noise(x, z, 842) < 0.3 ? "gravel" : "stone_bricks");
                for (int y = MINE_FLOOR + 1; y <= MINE_FLOOR + 5; y++) c.set(x, y, z, null);
                c.set(x, MINE_FLOOR + 6, z, "stone");
            }
        for (int x = HALL_X1; x <= HALL_X2; x++) c.set(x, MINE_FLOOR + 1, (HALL_Z1 + HALL_Z2) / 2 + 1, "rail[shape=east_west]");
        for (int x = HALL_X1 + 2; x <= HALL_X2; x += 6) {
            for (int z : new int[]{HALL_Z1, HALL_Z2}) for (int y = MINE_FLOOR + 1; y <= MINE_FLOOR + 4; y++) c.set(x, y, z, "spruce_log[axis=y]");
            for (int z = HALL_Z1; z <= HALL_Z2; z++) c.set(x, MINE_FLOOR + 5, z, "spruce_planks");
            c.set(x, MINE_FLOOR + 4, HALL_Z1 + 1, "lantern[hanging=true]");
        }
        c.set(HALL_X2 - 2, MINE_FLOOR + 1, HALL_Z1, "chest[facing=south]");
        // The eight galleries.
        for (int k = 0; k < GALLERY_NAMES.length; k++) {
            ActivityDesign.Area g = gallery(k);
            for (int x = g.x1(); x <= g.x2(); x++)
                for (int z = g.z1(); z <= g.z2(); z++) {
                    c.set(x, MINE_FLOOR, z, "stone");
                    for (int y = MINE_FLOOR + 1; y <= MINE_FLOOR + 4; y++) c.set(x, y, z, null);
                    c.set(x, MINE_FLOOR + 5, z, "stone");
                }
            for (int z = g.z1() + 2; z < g.z2(); z += 4) {
                for (int y = MINE_FLOOR + 1; y <= MINE_FLOOR + 3; y++) {
                    c.set(g.x1(), y, z, "oak_log[axis=y]");
                    c.set(g.x2(), y, z, "oak_log[axis=y]");
                }
                for (int x = g.x1(); x <= g.x2(); x++) c.set(x, MINE_FLOOR + 4, z, "oak_planks");
                c.set((g.x1() + g.x2()) / 2, MINE_FLOOR + 3, z, "lantern[hanging=true]");
            }
            // Ores in the walls.
            for (int[] s : oreSlots(k, MINE_COUNTS[k])) c.set(s[0], s[1], s[2], MINE_ORES[k]);
            // Coloured marker blocks in the floor at the entrance.
            for (int x = g.x1(); x <= g.x2(); x++) c.set(x, MINE_FLOOR, g.z1(), MINE_MARKERS[k]);
        }
        // Stairs down from the Mine Head (3 wide, from the street to the hall).
        int steps = G - MINE_FLOOR; // 16
        for (int i = 0; i <= steps; i++) {
            int z = 108 + i, y = G - i;
            for (int x = 219; x <= 221; x++) {
                c.set(x, y, z, i == steps ? "stone_bricks" : "stone_brick_stairs[facing=north]");
                for (int yy = y + 1; yy <= y + 4; yy++) c.set(x, yy, z, null);
                c.set(x, y - 1, z, "stone_bricks");
            }
            if (i % 4 == 0) c.set(218, y + 3, z, "lantern");
        }
        for (int z = 108 + steps; z <= HALL_Z1; z++)
            for (int x = 219; x <= 221; x++) {
                c.set(x, MINE_FLOOR, z, "stone_bricks");
                for (int y = MINE_FLOOR + 1; y <= MINE_FLOOR + 4; y++) c.set(x, y, z, null);
            }
        // The Mine Head building on the surface around the top of the stairs.
        for (int x = 214; x <= 226; x++)
            for (int z = 104; z <= 116; z++) {
                boolean edge = x == 214 || x == 226 || z == 104 || z == 116;
                boolean corner = (x == 214 || x == 226) && (z == 104 || z == 116);
                boolean stairs = x >= 219 && x <= 221 && z >= 108;
                if (!stairs) c.set(x, G, z, edge ? "cobblestone" : "spruce_planks");
                for (int y = G + 1; y <= G + 5; y++) {
                    if (stairs && y <= G + 4 && !edge) continue;
                    c.set(x, y, z, edge ? (corner ? "stripped_spruce_log[axis=y]" : y == G + 3 ? "spruce_fence" : "spruce_planks") : null);
                }
                c.set(x, G + 6, z, edge ? "spruce_slab[type=bottom]" : "spruce_planks");
            }
        for (int x = 219; x <= 221; x++)
            for (int y = G + 1; y <= G + 4; y++) c.set(x, y, 104, null); // door from the street
        c.set(216, G + 1, 106, "barrel[facing=up]");
        c.set(224, G + 1, 106, "anvil");
    }
}
