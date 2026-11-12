package com.aidan.skyserver.expedition;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 5.18: the End Expedition's island - a bigger copy of the End's main island, rebuilt at the start of every trip.
 *
 *  - An end stone island about 150 blocks across (R = 75; the vanilla island is about 100 across in its middle),
 *    gently bumpy on top, deepest in the middle.
 *  - 10 obsidian pillars in a ring (the 2 shortest have iron-bar cages), like the real End.
 *  - A bedrock podium at 0,0 (the dragon perches on it; The Eye floats over it).
 *  - Wren's Pad: a purpur platform where the crew lands and Captain Wren waits.
 *  - THE LEDGE: a 7-block-wide end stone brick ring 8 blocks under the island's rim, all the way round. Get knocked
 *    off the edge and you land on it; throw a pearl back up.
 *
 * The shape is the same every trip (fixed seed). Rebuilding only changes blocks that differ from the plan, so
 * repairs after a trip are quick. Building is spread over several ticks (COLUMNS_PER_TICK).
 */
final class EndIsland {
    private EndIsland() {}

    /** Top of the island, its radius, the ledge (8 under the rim, 7 wide) and where the crew lands. */
    static final int Y0 = 64, R = 75;
    static final int LEDGE_DROP = 8, LEDGE_WIDTH = 7;
    static final int LEDGE_Y = Y0 - LEDGE_DROP;
    static final int PAD_X = 0, PAD_Z = 30, PAD_HALF = 3;
    static final int PODIUM_HEIGHT = 4;
    /** Columns (x, z) checked/built per tick. */
    static final int COLUMNS_PER_TICK = 700;

    /** The pillars: x, z, radius, top y. */
    static final List<int[]> PILLARS = new ArrayList<>();

    static {
        Random rng = new Random(1_000_018L);
        int[] heights = {76, 79, 82, 85, 88, 91, 94, 97, 100, 103};
        // Shuffle the heights the same way every time.
        for (int i = heights.length - 1; i > 0; i--) {
            int j = rng.nextInt(i + 1), t = heights[i];
            heights[i] = heights[j];
            heights[j] = t;
        }
        double ring = R * 0.6;
        for (int i = 0; i < 10; i++) {
            double a = 2 * Math.PI * i / 10;
            int x = (int) Math.round(Math.cos(a) * ring), z = (int) Math.round(Math.sin(a) * ring);
            int top = heights[i];
            int radius = 2 + (top - 76) / 9;
            PILLARS.add(new int[]{x, z, radius, top});
        }
    }

    /** True for the pillars that get an iron-bar cage (the 2 shortest). */
    static boolean caged(int[] pillar) {
        return pillar[3] <= 79;
    }

    /** The island's edge at this angle (a little wavy, never smaller than 0.9 R). */
    static double edge(double angle) {
        return R * (0.93 + 0.04 * Math.sin(3 * angle + 1.3) + 0.03 * Math.sin(7 * angle + 0.4));
    }

    static double edgeAt(int x, int z) {
        return edge(Math.atan2(z, x));
    }

    /** The top block of the island at x, z (Y0 to Y0+2; flat on Wren's Pad and around the podium). */
    static int top(int x, int z) {
        if (onPad(x, z, 2) || (Math.abs(x) <= 4 && Math.abs(z) <= 4)) return Y0;
        double b = 1.0 + 0.7 * Math.sin(x * 0.11 + 0.5) + 0.6 * Math.cos(z * 0.13 + 1.1) + 0.4 * Math.sin((x + z) * 0.07);
        double d = Math.sqrt(x * x + z * z), e = edgeAt(x, z);
        if (d > e - 4) b = Math.min(b, 0.5); // the rim is flat-ish so there's always something to stand on
        return Y0 + Math.max(0, Math.min(2, (int) Math.round(b)));
    }

    /** The lowest block of the island at x, z (deep in the middle, 3 thick at the rim). */
    static int bottom(int x, int z) {
        double d = Math.sqrt(x * x + z * z), e = edgeAt(x, z);
        double f = Math.max(0, 1 - (d / e) * (d / e));
        return Y0 - 3 - (int) Math.round(34 * f);
    }

    static boolean onIsland(int x, int z) {
        return Math.sqrt(x * x + z * z) <= edgeAt(x, z);
    }

    static boolean onPad(int x, int z, int margin) {
        return Math.abs(x - PAD_X) <= PAD_HALF + margin && Math.abs(z - PAD_Z) <= PAD_HALF + margin;
    }

    static boolean onLedge(int x, int z) {
        double d = Math.sqrt(x * x + z * z), e = edgeAt(x, z);
        return d >= e - 1 && d <= e + LEDGE_WIDTH - 1;
    }

    /** Blocks nobody can break: Wren's Pad, the podium and the ledge. */
    static boolean locked(BlockPos p) {
        int x = p.getX(), y = p.getY(), z = p.getZ();
        if (onPad(x, z, 0) && y >= Y0 - 1 && y <= Y0) return true;
        if (Math.abs(x) <= 1 && Math.abs(z) <= 1 && y >= Y0 && y <= Y0 + PODIUM_HEIGHT) return true;
        return y == LEDGE_Y && onLedge(x, z);
    }

    /** Where the crew lands (the middle of Wren's Pad) and where Wren stands. */
    static double[] landing() {
        return new double[]{PAD_X + 0.5, Y0 + 1, PAD_Z + 0.5, 180};
    }

    static double[] wrenSpot() {
        return new double[]{PAD_X + 3.5, Y0 + 1, PAD_Z + 0.5, 90};
    }

    /** The top of the podium (where the dragon perches and The Eye floats over). */
    static int podiumTop() {
        return Y0 + PODIUM_HEIGHT;
    }

    // =====================================================================
    // Building
    // =====================================================================

    /** Builds (or repairs) the island a bit at a time. Call step() every tick until it returns true. */
    static final class Builder {
        private final ServerLevel level;
        private final int min = -R - LEDGE_WIDTH - 4, max = R + LEDGE_WIDTH + 4;
        private int x = min, z = min;
        private boolean columnsDone = false, done = false;
        private final BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();

        Builder(ServerLevel level) {
            this.level = level;
        }

        boolean done() {
            return done;
        }

        /** Does one tick of work. True when the whole island is ready. */
        boolean step() {
            if (done) return true;
            if (!columnsDone) {
                for (int n = 0; n < COLUMNS_PER_TICK && !columnsDone; n++) {
                    column(x, z);
                    if (++z > max) {
                        z = min;
                        if (++x > max) columnsDone = true;
                    }
                }
                return false;
            }
            pillars();
            podium();
            pad();
            done = true;
            return true;
        }

        private void set(int bx, int by, int bz, BlockState s) {
            m.set(bx, by, bz);
            if (level.getBlockState(m) != s) level.setBlock(m, s, Block.UPDATE_CLIENTS);
        }

        private void column(int cx, int cz) {
            BlockState stone = Blocks.END_STONE.defaultBlockState(), air = Blocks.AIR.defaultBlockState();
            if (onIsland(cx, cz)) {
                int t = top(cx, cz), b = bottom(cx, cz);
                for (int y = b; y <= t; y++) set(cx, y, cz, stone);
                // Bumps that were dug down to a lower plan stay as the plan says: clear 3 above the top.
                for (int y = t + 1; y <= t + 3; y++) set(cx, y, cz, air);
            }
            if (onLedge(cx, cz)) set(cx, LEDGE_Y, cz, Blocks.END_STONE_BRICKS.defaultBlockState());
        }

        private void pillars() {
            BlockState obsidian = Blocks.OBSIDIAN.defaultBlockState(), air = Blocks.AIR.defaultBlockState();
            for (int[] p : PILLARS) {
                int px = p[0], pz = p[1], r = p[2], topY = p[3];
                for (int dx = -r - 2; dx <= r + 2; dx++) {
                    for (int dz = -r - 2; dz <= r + 2; dz++) {
                        double d = Math.sqrt(dx * dx + dz * dz);
                        int x = px + dx, z = pz + dz;
                        if (d <= r + 0.5) {
                            for (int y = Y0 - 6; y <= topY; y++) set(x, y, z, obsidian);
                            for (int y = topY + 1; y <= topY + 4; y++) set(x, y, z, air);
                        } else if (caged(p) && Math.abs(dx) <= 2 && Math.abs(dz) <= 2) {
                            for (int y = topY + 1; y <= topY + 4; y++) set(x, y, z, air);
                        }
                    }
                }
                set(px, topY, pz, Blocks.BEDROCK.defaultBlockState());
                if (caged(p)) cage(px, topY, pz);
            }
        }

        /** An iron-bar cage around a pillar top: 5 x 5, 3 tall, with a roof. */
        private void cage(int px, int topY, int pz) {
            BlockState bars = Blocks.IRON_BARS.defaultBlockState();
            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    boolean side = Math.abs(dx) == 2 || Math.abs(dz) == 2;
                    for (int y = topY + 1; y <= topY + 3; y++) if (side) set(px + dx, y, pz + dz, bars);
                    set(px + dx, topY + 4, pz + dz, bars);
                }
            }
        }

        private void podium() {
            BlockState bedrock = Blocks.BEDROCK.defaultBlockState();
            for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) set(dx, Y0 + 1, dz, bedrock);
            for (int y = Y0 + 2; y <= Y0 + PODIUM_HEIGHT; y++) set(0, y, 0, bedrock);
        }

        private void pad() {
            BlockState purpur = Blocks.PURPUR_BLOCK.defaultBlockState(), air = Blocks.AIR.defaultBlockState();
            for (int dx = -PAD_HALF; dx <= PAD_HALF; dx++) {
                for (int dz = -PAD_HALF; dz <= PAD_HALF; dz++) {
                    int x = PAD_X + dx, z = PAD_Z + dz;
                    set(x, Y0, z, (Math.abs(dx) == PAD_HALF || Math.abs(dz) == PAD_HALF) ? Blocks.PURPUR_PILLAR.defaultBlockState() : purpur);
                    for (int y = Y0 + 1; y <= Y0 + 3; y++) set(x, y, z, air);
                }
            }
        }
    }
}
