package com.aidan.skyserver.lobby;

import com.aidan.skyserver.Skyserver;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static com.aidan.skyserver.lobby.ActivityDesign.*;
import static com.aidan.skyserver.lobby.LobbyDesign.G;

/**
 * 5.9: the Elytra courses. Course 0 is the original Elytra Tower ring course; courses 1-5 are new
 * sky courses floating high above the lobby (none of them touch the ground, so they never get in
 * the way of anything), each with a launch platform, a line of rings and a landing platform:
 *
 *   1 Cloud Drift    (Easy)     north-west, big rings, gentle glide
 *   2 Castle Circuit (Medium)   round the north and west of the castle
 *   3 Storm Spiral   (Hard)     a descending spiral in the north-east
 *   4 Serpent Run    (Hard)     a zig-zag slalom over the south-east
 *   5 Void Dive      (Extreme)  off the east edge of the island, small rings, ends low
 *
 * /elytra (or the compass -> Elytra Rings) opens the course picker; it teleports you to the
 * launch platform. The rules are in Activities (step on the gold pad, jump, fly every ring in order,
 * land on the pad). Money: the first finish of ANY course each day pays the same (Activities.ELYTRA_MONEY).
 *
 * The new courses are placed into the lobby world once (config/skyserver/elytra-courses.txt holds
 * the version built); bump VERSION to place them again after changing a course.
 */
public final class ElytraCourses {
    private ElytraCourses() {}

    public static final int VERSION = 1;
    private static final Path MARKER = Path.of("config", "skyserver", "elytra-courses.txt");
    private static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;

    /**
     * One course. rings in order; launchPad = the gold pad you stand on to get the elytra; launch =
     * where the picker puts you; landing = where you must land; board = where its best-times board floats.
     */
    public record Course(String id, String name, String diff, String color, String ringBlock, Item icon, int rockets,
                         List<Ring> rings, Area launchPad, Spot launch, Area landing, Spot board, boolean tower) {
        /** Feet height on the launch pad (standing there and on ground above this = not flying yet). */
        public double launchY() {
            return launchPad.y1();
        }

        /** Key for best times (course 0 keeps the old "elytra" key so its records carry over). */
        public String recordKey() {
            return tower ? "elytra" : "elytra_" + id;
        }
    }

    public static final List<Course> ALL = new ArrayList<>();

    static {
        ALL.add(new Course("tower", "Tower Rings", "Medium", "§d", "magenta_concrete", Items.ELYTRA, 3, RINGS, LAUNCH_PAD, LAUNCH_SPOT,
                LANDING_PAD, new Spot(TOWER_BASE.x() + 6, G + 4.2, TOWER_BASE.z(), 0f), true));
        // 1 Cloud Drift (Easy): west, then north, big rings, a long gentle glide.
        ALL.add(sky("cloud", "Cloud Drift", "Easy", "§a", "lime_concrete", Items.FEATHER, 3, 5, 22,
                new double[][]{{-120, 200, -140}, {-255, 183, -140}, {-300, 176, -195}, {-300, 167, -290}, {-245, 159, -330}}));
        // 2 Castle Circuit (Medium): west along the north side of the castle, south past the west gate.
        ALL.add(sky("castle", "Castle Circuit", "Medium", "§e", "yellow_concrete", Items.STONE_BRICKS, 4, 4, 20,
                new double[][]{{70, 206, -175}, {-150, 192, -175}, {-178, 184, -100}, {-178, 178, -20}, {-120, 172, 45}}));
        // 3 Storm Spiral (Hard): one and a half turns down round a point in the north-east.
        ALL.add(sky("storm", "Storm Spiral", "Hard", "§b", "light_blue_concrete", Items.TRIDENT, 4, 3, 20, spiral(240, -110, 48, 214, 168, 1.5)));
        // 4 Serpent Run (Hard): a zig-zag slalom south over the south-east.
        ALL.add(sky("serpent", "Serpent Run", "Hard", "§2", "green_concrete", Items.SLIME_BALL, 4, 3, 17,
                new double[][]{{130, 206, 55}, {205, 198, 92}, {130, 190, 130}, {205, 182, 168}, {130, 175, 206}, {205, 169, 244}, {168, 163, 288}}));
        // 5 Void Dive (Extreme): out over the void off the east edge, diving low; small rings.
        ALL.add(sky("void", "Void Dive", "Extreme", "§4", "red_concrete", Items.ENDER_EYE, 4, 2, 19,
                new double[][]{{300, 232, -255}, {342, 208, -172}, {398, 180, -92}, {404, 156, 0}, {396, 138, 90}, {342, 128, 160}, {300, 121, 206}}));
    }

    /** Builds a sky course from waypoints {x, y, z}: launch platform at the first, rings every `step` blocks, landing at the last. */
    private static Course sky(String id, String name, String diff, String color, String ringBlock, Item icon, int rockets, int radius,
                              double step, double[][] wp) {
        List<Ring> rings = new ArrayList<>();
        double total = 0;
        double[] seg = new double[wp.length - 1];
        for (int i = 0; i + 1 < wp.length; i++) {
            seg[i] = Math.hypot(wp[i + 1][0] - wp[i][0], wp[i + 1][2] - wp[i][2]);
            total += seg[i];
        }
        for (double s = step; s <= total - 14; s += step) {
            double acc = 0;
            for (int i = 0; i < seg.length; i++) {
                if (s > acc + seg[i] && i < seg.length - 1) {
                    acc += seg[i];
                    continue;
                }
                double t = (s - acc) / seg[i];
                double x = wp[i][0] + (wp[i + 1][0] - wp[i][0]) * t, y = wp[i][1] + (wp[i + 1][1] - wp[i][1]) * t, z = wp[i][2] + (wp[i + 1][2] - wp[i][2]) * t;
                double tx = wp[i + 1][0] - wp[i][0], tz = wp[i + 1][2] - wp[i][2];
                rings.add(new Ring((int) Math.round(x), (int) Math.round(y) - 1, (int) Math.round(z), Math.abs(tx) >= Math.abs(tz), radius));
                break;
            }
        }
        double[] a = wp[0], b = wp[1], end = wp[wp.length - 1];
        int lx = (int) Math.floor(a[0]), ly = (int) Math.floor(a[1]), lz = (int) Math.floor(a[2]);
        float yaw = (float) (Math.toDegrees(Math.atan2(b[2] - a[2], b[0] - a[0])) - 90.0);
        Area pad = new Area(lx - 2, ly, lz - 2, lx + 2, ly + 2, lz + 2);
        Spot launch = new Spot(lx + 0.5, ly, lz + 0.5, yaw);
        int ex = (int) Math.floor(end[0]), ey = (int) Math.floor(end[1]), ez = (int) Math.floor(end[2]);
        Area landing = new Area(ex - 5, ey, ez - 5, ex + 5, ey + 2, ez + 5);
        Spot board = new Spot(lx + 0.5 - Math.signum(b[0] - a[0]) * 4, ly + 3.2, lz + 0.5 - Math.signum(b[2] - a[2]) * 4, 0f);
        return new Course(id, name, diff, color, ringBlock, icon, rockets, rings, pad, launch, landing, board, false);
    }

    /** Waypoints of a descending spiral round (cx, cz). */
    private static double[][] spiral(double cx, double cz, double r, double yTop, double yEnd, double turns) {
        int n = (int) Math.round(turns * 36);
        double[][] wp = new double[n + 2][];
        for (int i = 0; i <= n; i++) {
            double ang = i * Math.PI * 2 * turns / n;
            double rr = r - 14 * i / (double) n; // tightens a little
            wp[i] = new double[]{cx + Math.cos(ang) * rr, yTop + (yEnd - yTop) * i / n, cz + Math.sin(ang) * rr};
        }
        // Land in the middle, a little lower.
        wp[n + 1] = new double[]{cx, yEnd - 4, cz};
        return wp;
    }

    public static Course course(int i) {
        return ALL.get(Math.max(0, Math.min(ALL.size() - 1, i)));
    }

    // =====================================================================
    // Placing the sky courses into the lobby world (once per VERSION) and their signs
    // =====================================================================

    private static int buildNext = -1;
    private static boolean signsUp = false;

    public static void init() {
        ServerLifecycleEvents.SERVER_STARTED.register(s -> {
            signsUp = false;
            buildNext = built() ? -1 : 1;
        });
        ServerTickEvents.END_SERVER_TICK.register(s -> com.aidan.skyserver.Safe.run("elytra courses", () -> tick(s.getTickCount())));
        CommandRegistrationCallback.EVENT.register((d, r, e) -> d.register(Commands.literal("elytra").executes(c -> {
            ServerPlayer p = c.getSource().getPlayerOrException();
            if (!Lobby.isLobby(p.level())) {
                Lobby.msg(p, "§cThe Elytra courses are in the lobby (§e/lobby§c).");
                return 0;
            }
            openMenu(p);
            return 1;
        })));
    }

    private static boolean built() {
        try {
            return Files.exists(MARKER) && Files.readString(MARKER).trim().equals("built " + VERSION);
        } catch (Exception e) {
            return false;
        }
    }

    private static void tick(long ticks) {
        ServerLevel lobby = Lobby.level();
        if (lobby == null || ticks % 10 != 0 || !LobbyBuilder.isBuilt() || LobbyBuilder.isBuilding()) return;
        if (buildNext >= 1) {
            // One course per half second, so the server doesn't stall loading the chunks.
            build(lobby, ALL.get(buildNext));
            buildNext++;
            if (buildNext >= ALL.size()) {
                buildNext = -1;
                try {
                    Files.createDirectories(MARKER.getParent());
                    Files.writeString(MARKER, "built " + VERSION);
                } catch (Exception e) {
                    Skyserver.LOGGER.warn("Couldn't write {}: {}", MARKER, e.toString());
                }
                Skyserver.LOGGER.info("Elytra sky courses placed ({} courses).", ALL.size() - 1);
            }
            return;
        }
        if (!signsUp) {
            signsUp = true;
            for (Course c : ALL) {
                if (c.tower()) continue;
                Holograms.add(c.launch().x(), c.launch().y() + 2.6, c.launch().z(), List.of(c.color() + "§l" + c.name().toUpperCase(),
                        "§7" + c.diff() + " - " + c.rings().size() + " rings, " + c.rockets() + " rockets",
                        "§7Step on the §6gold pad§7, jump off and fly!"));
                Area l = c.landing();
                Holograms.add((l.x1() + l.x2()) / 2.0 + 0.5, l.y1() + 3.5, (l.z1() + l.z2()) / 2.0 + 0.5, List.of(c.color() + "§lLANDING PAD", "§7Finish of " + c.name()));
            }
        }
    }

    private static BlockState block(String id) {
        return com.aidan.skyserver.goldwars.GoldWarsMap.toState(id);
    }

    private static void build(ServerLevel level, Course c) {
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        // Launch platform: 9 x 9 purpur with the 5 x 5 gold pad, glass rail except on the side you jump from.
        Area pad = c.launchPad();
        int cx = pad.x1() + 2, cy = pad.y1() - 1, cz = pad.z1() + 2;
        Ring first = c.rings().isEmpty() ? null : c.rings().get(0);
        int ox = first == null ? 0 : (int) Math.signum(first.cx() - cx), oz = first == null ? 0 : (int) Math.signum(first.cz() - cz);
        boolean openX = first != null && Math.abs(first.cx() - cx) >= Math.abs(first.cz() - cz);
        for (int x = cx - 4; x <= cx + 4; x++)
            for (int z = cz - 4; z <= cz + 4; z++) {
                boolean padCell = Math.abs(x - cx) <= 2 && Math.abs(z - cz) <= 2;
                level.setBlock(m.set(x, cy, z), padCell ? Blocks.GOLD_BLOCK.defaultBlockState() : Blocks.PURPUR_BLOCK.defaultBlockState(), FLAGS);
                for (int y = cy + 1; y <= cy + 5; y++) level.setBlock(m.set(x, y, z), Blocks.AIR.defaultBlockState(), FLAGS);
                boolean edge = Math.abs(x - cx) == 4 || Math.abs(z - cz) == 4;
                boolean jumpSide = openX ? (x - cx) * ox == 4 : (z - cz) * oz == 4;
                if (edge && !jumpSide) level.setBlock(m.set(x, cy + 1, z), block("purple_stained_glass_pane"), Block.UPDATE_ALL);
            }
        for (int[] corner : new int[][]{{-4, -4}, {4, -4}, {-4, 4}, {4, 4}}) level.setBlock(m.set(cx + corner[0], cy + 1, cz + corner[1]), block("end_rod[facing=up]"), FLAGS);
        // A few blocks hanging under it so it doesn't look like a flat sheet.
        for (int x = cx - 3; x <= cx + 3; x++)
            for (int z = cz - 3; z <= cz + 3; z++) {
                int hang = 1 + (Math.abs(x - cx) + Math.abs(z - cz) <= 2 ? 2 : Math.abs(x - cx) + Math.abs(z - cz) <= 4 ? 1 : 0);
                for (int y = cy - hang; y < cy; y++) level.setBlock(m.set(x, y, z), Blocks.PURPUR_PILLAR.defaultBlockState(), FLAGS);
            }
        // Rings.
        for (int i = 0; i < c.rings().size(); i++) {
            Ring r = c.rings().get(i);
            String b = i == 0 ? "lime_concrete" : i == c.rings().size() - 1 ? "gold_block" : c.ringBlock();
            for (int a = -r.r() - 1; a <= r.r() + 1; a++)
                for (int k = -r.r() - 1; k <= r.r() + 1; k++) {
                    double d = Math.hypot(a, k);
                    int x = r.alongX() ? r.cx() : r.cx() + a, z = r.alongX() ? r.cz() + a : r.cz();
                    if (d < r.r() + 0.5) {
                        level.setBlock(m.set(x, r.cy() + k, z), Blocks.AIR.defaultBlockState(), FLAGS); // keep the hole clear
                        continue;
                    }
                    if (d > r.r() + 1.4) continue;
                    level.setBlock(m.set(x, r.cy() + k, z), block((Math.abs(a) <= 1 && k > 0) ? "sea_lantern" : b), FLAGS);
                }
        }
        // Landing platform: 11 x 11 with a border, hanging rock under it.
        Area l = c.landing();
        int ly = l.y1() - 1;
        for (int x = l.x1(); x <= l.x2(); x++)
            for (int z = l.z1(); z <= l.z2(); z++) {
                boolean edge = x == l.x1() || x == l.x2() || z == l.z1() || z == l.z2();
                level.setBlock(m.set(x, ly, z), block(edge ? "purpur_pillar[axis=y]" : (x + z) % 2 == 0 ? "magenta_glazed_terracotta" : "purpur_block"), FLAGS);
                for (int y = ly + 1; y <= ly + 7; y++) level.setBlock(m.set(x, y, z), Blocks.AIR.defaultBlockState(), FLAGS);
                int dx = Math.min(x - l.x1(), l.x2() - x), dz = Math.min(z - l.z1(), l.z2() - z);
                int hang = Math.min(dx, dz);
                for (int y = ly - hang; y < ly; y++) level.setBlock(m.set(x, y, z), block(y == ly - hang ? "andesite" : "stone"), FLAGS);
            }
        for (int[] corner : new int[][]{{l.x1(), l.z1()}, {l.x2(), l.z1()}, {l.x1(), l.z2()}, {l.x2(), l.z2()}})
            level.setBlock(m.set(corner[0], ly + 1, corner[1]), block("end_rod[facing=up]"), FLAGS);
        Skyserver.LOGGER.info("Placed elytra course {} ({} rings).", c.name(), c.rings().size());
    }

    // =====================================================================
    // The course picker
    // =====================================================================

    public static void openMenu(ServerPlayer p) {
        LockedMenu.open(p, "§8Elytra courses", 3, Picker::new);
    }

    private static final int FIRST_SLOT = 10;

    static final class Picker extends LockedMenu {
        Picker(int id, Inventory inv, SimpleContainer box, ServerPlayer viewer) {
            super(id, inv, box, 3, viewer);
            box.setItem(4, Stacks.named(new ItemStack(Items.ELYTRA), "§d§lELYTRA COURSES",
                    "§7Step on the gold pad, jump off and", "§7fly through every ring, then land on the pad.",
                    "§6The first finish of any course each day", "§6pays " + Economy.format(Activities.ELYTRA_MONEY) + "§6 (same for every course)."));
            for (int i = 0; i < ALL.size(); i++) {
                Course c = ALL.get(i);
                Integer best = Activities.bestTime(c.recordKey(), viewer.getUUID());
                box.setItem(FIRST_SLOT + i, Stacks.named(new ItemStack(c.icon()), c.color() + "§l" + c.name(),
                        "§7Difficulty: §f" + c.diff(), "§7" + c.rings().size() + " rings, " + c.rockets() + " boost rockets",
                        "§7Your best: §f" + (best == null ? "-" : Parkour.time(best)),
                        c.tower() ? "§7Starts at the top of the Elytra Tower" : "§7Floats high above the lobby", "§eClick to go to the start"));
            }
            frame(Stacks.named(new ItemStack(Items.STAINED_GLASS_PANE.pick(DyeColor.PURPLE)), " "));
            broadcastChanges();
        }

        @Override
        protected void onClick(int slot, int button, boolean shift) {
            int i = slot - FIRST_SLOT;
            if (i < 0 || i >= ALL.size()) return;
            Lobby.later(viewer, () -> Activities.toCourse(viewer, i));
        }
    }
}
