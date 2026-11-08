package com.aidan.skyserver.lobby;

import java.util.*;

/**
 * The four obstacle courses, built from real parkour jump types and checked against how far a
 * player can actually jump. Plain Java so it can be checked offline.
 *
 * What makes a good course (and what these follow):
 *  - Every jump is readable: landing blocks stand out from the surroundings, nothing is blind.
 *  - Variety: flat sprint jumps, jumps up and down, neo jumps around walls, ladder catches,
 *    head-hitters, and thin landings (fence posts, walls, iron bars) on the harder courses.
 *  - Difficulty ramps up inside each course, and checkpoints sit right before the hard parts.
 *  - Harder courses have fewer checkpoints, so a mistake costs more.
 *
 * Jump limits used (sprint jump, no momentum tricks), as air distance from edge to edge after
 * allowing for the player's width: up 1 block 2.5, up half 3.0, flat 3.45, down half 3.7,
 * down 1 3.9, further down 4.2. Head-hitter jumps 2.6.
 *
 *  Easy "Garden Run" (west)      - what used to be the hard course: 3-block gaps, climbs.
 *  Medium "Ruins Run" (east)     - 4-block gaps, 3+1 climbs, fence posts, 1-block neos, ladders.
 *  Hard "Sky Spire" (north)      - 2-block neos, ladder catches, head-hitters, walls and bars.
 *  Extreme "Void Gauntlet" (south) - max-length jumps onto thin blocks, 3-block neos, few checkpoints.
 */
public final class CourseDesign {
    private CourseDesign() {}

    /** Kinds of landing block: how high its top is and how wide it is. */
    enum Kind {
        FULL(1.0, 1.0), SLAB(0.5, 1.0), FENCE(1.5, 0.25), WALL(1.5, 0.5), BARS(1.0, 0.125), TRAPDOOR(0.1875, 1.0), ROD(1.0, 0.25);
        final double top, width;

        Kind(double top, double width) {
            this.top = top;
            this.width = width;
        }
    }

    record Theme(String full, String slab, String fence, String wall, String bars, String trapdoor, String rod, String wallBlock, String ceiling) {}

    static final Theme EASY = new Theme("lime_concrete", "birch_slab[type=bottom]", "birch_fence", "mossy_cobblestone_wall", "iron_bars",
            "birch_trapdoor[half=bottom,facing=north]", "end_rod[facing=up]", "oak_leaves[persistent=true]", "birch_planks");
    static final Theme MEDIUM = new Theme("orange_concrete", "spruce_slab[type=bottom]", "spruce_fence", "cobblestone_wall", "iron_bars",
            "spruce_trapdoor[half=bottom,facing=north]", "end_rod[facing=up]", "mossy_stone_bricks", "spruce_planks");
    static final Theme HARD = new Theme("red_concrete", "dark_oak_slab[type=bottom]", "dark_oak_fence", "blackstone_wall", "iron_bars",
            "dark_oak_trapdoor[half=bottom,facing=north]", "end_rod[facing=up]", "polished_blackstone_bricks", "dark_oak_planks");
    static final Theme EXTREME = new Theme("purple_concrete", "purpur_slab[type=bottom]", "crimson_fence", "end_stone_brick_wall", "iron_bars",
            "crimson_trapdoor[half=bottom,facing=north]", "end_rod[facing=up]", "obsidian", "purpur_block");

    /** Skill needed per course (easy, medium, hard, extreme): 1.0 = frame-perfect. */
    static final double[] SKILL = {0.80, 0.88, 0.93, 0.97};
    /** Fewest ticks of timing slack per course: how many different moments you can jump and still make it. */
    static final int[] WINDOW = {3, 2, 2, 1};

    /** Air distance limit for a height change (target top - takeoff top). */
    static double limit(double dh) {
        if (dh > 1.01) return -1;
        double[][] pts = {{-2, 4.2}, {-1.5, 4.2}, {-1, 3.9}, {-0.5, 3.7}, {0, 3.45}, {0.5, 3.0}, {1.0, 2.5}};
        if (dh <= pts[0][0]) return pts[0][1];
        for (int i = 1; i < pts.length; i++)
            if (dh <= pts[i][0]) {
                double t = (dh - pts[i - 1][0]) / (pts[i][0] - pts[i - 1][0]);
                return pts[i - 1][1] + t * (pts[i][1] - pts[i - 1][1]);
            }
        return pts[pts.length - 1][1];
    }

    /** One jump as recorded for checking: air distance needed, height change, what kind of jump. */
    public record JumpInfo(double air, double dh, double limit, String type) {}

    // =====================================================================
    // The builder
    // =====================================================================

    static final class B {
        final String id, name;
        final int tier;
        final Theme th;
        final Random rng;
        final int[] bounds; // x1, z1, x2, z2
        final List<int[]> jumps = new ArrayList<>();
        final List<String> states = new ArrayList<>();
        final List<int[]> extras = new ArrayList<>();
        final List<String> extraStates = new ArrayList<>();
        final List<Integer> stationIdx = new ArrayList<>();
        final List<JumpInfo> info = new ArrayList<>();
        int x, y, z, fx, fz;          // current landing block and forward direction
        Kind kind = Kind.FULL;
        boolean onStation = true;
        // Snaking back and forth in lanes so the course stays inside its area.
        int mainFx, mainFz, sideX, sideZ, laneCenter, turnStart;
        boolean turning = false;

        B(String id, String name, int tier, Theme th, long seed, int[] bounds, int sx, int sy, int sz, int fx, int fz) {
            this.id = id;
            this.name = name;
            this.tier = tier;
            this.th = th;
            this.rng = new Random(seed);
            this.bounds = bounds;
            this.x = sx;
            this.y = sy;
            this.z = sz;
            this.fx = fx;
            this.fz = fz;
            this.mainFx = fx;
            this.mainFz = fz;
            // Lanes go toward whichever side has more room.
            int lx0 = -fz, lz0 = fx;
            int cx = (bounds[0] + bounds[2]) / 2, cz = (bounds[1] + bounds[3]) / 2;
            double toCentre = (cx - sx) * lx0 + (cz - sz) * lz0;
            this.sideX = toCentre >= 0 ? lx0 : -lx0;
            this.sideZ = toCentre >= 0 ? lz0 : -lz0;
            if (toCentre == 0) { this.sideX = lx0; this.sideZ = lz0; }
            this.laneCenter = sc();
            jumps.add(new int[]{x, y, z});
            states.add("emerald_block");
            stationIdx.add(0);
            standOn(sx, sy, sz, JumpSim.Target.platform(sx, sy, sz));
        }

        double top() {
            return y + kind.top;
        }

        double half() {
            return onStation ? 1.5 : kind.width / 2;
        }

        String state(Kind k) {
            return switch (k) {
                case FULL -> th.full;
                case SLAB -> th.slab;
                case FENCE -> th.fence;
                case WALL -> th.wall;
                case BARS -> th.bars;
                case TRAPDOOR -> th.trapdoor;
                case ROD -> th.rod;
            };
        }

        int sc() { return x * sideX + z * sideZ; } // position across the lanes

        boolean inside(int px, int pz, int margin) {
            return px >= bounds[0] + margin && px <= bounds[2] - margin && pz >= bounds[1] + margin && pz <= bounds[3] - margin;
        }

        /** After each landing: turn at the end of a lane, and back after moving over one lane. */
        void steer() {
            if (!turning) {
                if (!inside(x + fx * 11, z + fz * 11, 2)) {
                    turning = true;
                    turnStart = sc();
                    fx = sideX;
                    fz = sideZ;
                }
            } else if (sc() - turnStart >= 13) {
                turning = false;
                laneCenter = sc();
                mainFx = -mainFx;
                mainFz = -mainFz;
                fx = mainFx;
                fz = mainFz;
            }
        }

        int lx() { return -fz; } // left/right axis
        int lz() { return fx; }

        void extra(int ex, int ey, int ez, String s) {
            extras.add(new int[]{ex, ey, ez});
            extraStates.add(s);
        }

        // ---- Every jump is checked with JumpSim (a copy of Minecraft's movement physics) ----

        /** Skill a player needs on each course (1.0 = frame-perfect), and the fewest ticks of timing slack. */
        double skill() { return SKILL[tier]; }
        int window() { return WINDOW[tier]; }

        JumpSim.State st;
        JumpSim.Target cur;

        void standOn(int bx, int by, int bz, JumpSim.Target support) {
            st = new JumpSim.State(bx + 0.5, support.top(), bz + 0.5, 0, 0);
            cur = support;
        }

        private void accept(int nx, int ny, int nz, Kind k, JumpSim.Target t, JumpSim.Result r, String type) {
            info.add(new JumpInfo(r.window(), t.top() - cur.top(), window(), type));
            st = r.landed();
            cur = t;
            x = nx;
            z = nz;
            y = ny;
            kind = k;
            onStation = false;
            jumps.add(new int[]{x, y, z});
            states.add(state(k));
            steer();
        }

        /** Tries a landing; true (and placed) if the jump works for this course's skill and timing. */
        private boolean tryLand(int fwd, int lat, int dy, Kind k, double ceiling, String type) {
            int nx = x + fx * fwd + lx() * lat, nz = z + fz * fwd + lz() * lat, ny = y + dy;
            JumpSim.Target t = JumpSim.Target.block(nx, ny, nz, k.top, k.width);
            JumpSim.Result r = JumpSim.jump(st, cur, t, ceiling, skill());
            if (r.window() < window()) return false;
            accept(nx, ny, nz, k, t, r, type);
            return true;
        }

        /** The longest gap (up to fwd) that works; if none does, a lower or easier landing. */
        void land(int fwd, int lat, int dy, Kind k, double ceiling, String type) {
            for (int dd = dy; dd >= dy - 2; dd--)
                for (int f = fwd; f >= 2; f--)
                    if (tryLand(f, dd == dy ? lat : 0, dd, k, ceiling, type)) return;
            // Nothing worked (shouldn't happen): a plain block one down, two ahead.
            for (int f = 2; f >= 1; f--) if (tryLand(f, 0, -1, Kind.FULL, Double.NaN, "fallback")) return;
            int nx = x + fx * 2, nz = z + fz * 2;
            JumpSim.Target t = JumpSim.Target.block(nx, y - 1, nz, 1, 1);
            accept(nx, y - 1, nz, Kind.FULL, t, new JumpSim.Result(true, 1, new JumpSim.State(nx + 0.5, y, nz + 0.5, 0, 0)), "fallback");
        }

        /** A plain jump: the longest gap this course allows (so harder courses get longer jumps). */
        void jump(Kind k, int dy, int lat, double lo, double hi, String type) {
            land(7, lat, dy, k, Double.NaN, type);
        }

        /** Neo: a wall in the way that you jump around. thick = wall thickness (1..2). */
        void neo(int thick) {
            int side = rng.nextBoolean() ? 1 : -1;
            for (int f = 1; f <= thick; f++)
                for (int l = 0; l <= 2; l++)
                    for (int h = 1; h <= 3; h++) {
                        int ex = x + fx * f + lx() * l * side, ez = z + fz * f + lz() * l * side;
                        int ey = (int) Math.floor(cur.top()) - 1 + h;
                        extra(ex, ey, ez, th.wallBlock);
                    }
            int fwd = thick + 1;
            // Neos are known parkour jumps: a 1-wide neo is moderate, 2-wide is hard (3-wide is not used).
            info.add(new JumpInfo(window(), 0, window(), "neo " + thick));
            x += fx * fwd;
            z += fz * fwd;
            y = (int) Math.floor(cur.top()) - 1;
            kind = Kind.FULL;
            onStation = false;
            jumps.add(new int[]{x, y, z});
            states.add(th.full);
            standOn(x, y, z, JumpSim.Target.block(x, y, z, 1, 1)); // you come out of a neo slowly
            steer();
        }

        /** Jump to a ladder on a pillar, climb it, stand on top. */
        void ladder(int gap) {
            String facing = fx > 0 ? "west" : fx < 0 ? "east" : fz > 0 ? "north" : "south";
            int base = (int) Math.floor(cur.top()) - 1;
            for (int g = gap; g >= 1; g--) {
                int face = g + 1;
                int px = x + fx * (face + 1), pz = z + fz * (face + 1);
                int lx = x + fx * face, lz = z + fz * face;
                JumpSim.Target t = JumpSim.Target.ladder(lx, base + 1, base + 3, lz);
                JumpSim.Result r = JumpSim.jump(st, cur, t, Double.NaN, skill());
                if (r.window() < window() && g > 1) continue;
                for (int h = 0; h <= 3; h++) extra(px, base + h, pz, th.wallBlock);
                for (int h = 1; h <= 3; h++) extra(lx, base + h, lz, "ladder[facing=" + facing + "]");
                info.add(new JumpInfo(Math.max(r.window(), 1), 0, window(), "ladder " + g));
                x = px;
                z = pz;
                y = base + 3;
                kind = Kind.FULL;
                onStation = false;
                jumps.add(new int[]{x, y, z});
                states.add(th.full);
                standOn(x, y, z, JumpSim.Target.block(x, y, z, 1, 1)); // climbing stops you
                steer();
                return;
            }
        }

        /** Head-hitter: a ceiling 2 blocks above the takeoff, flat jump. */
        void headHitter(int fwd, Kind k) {
            int cy = (int) Math.ceil(cur.top() + JumpSim.HEIGHT); // just above your head when standing on the takeoff
            extra(x, cy, z, th.ceiling);
            land(fwd, 0, 0, k, cy, "head-hitter");
        }

        /** A 3x3 checkpoint platform. */
        void station() {
            platform("light_blue_concrete", "to platform", true);
        }

        private void platform(String block, String type, boolean checkpoint) {
            int py = (int) Math.floor(cur.top()) - 1;
            int best = 2;
            for (int f = 2; f <= 5; f++) {
                int px = x + fx * f, pz = z + fz * f;
                JumpSim.Result r = JumpSim.jump(st, cur, JumpSim.Target.platform(px, py, pz), Double.NaN, Math.min(skill(), 0.85));
                if (r.window() >= 4) best = f;
            }
            x += fx * best;
            z += fz * best;
            y = py;
            info.add(new JumpInfo(4, 0, 4, type));
            kind = Kind.FULL;
            onStation = true;
            jumps.add(new int[]{x, y, z});
            states.add(block);
            stationIdx.add(jumps.size() - 1);
            standOn(x, y, z, JumpSim.Target.platform(x, y, z));
            if (checkpoint) steer();
        }

        void finish() {
            platform("gold_block", "to finish", false);
        }

        int dyFor(int minY, int maxY) {
            double r = rng.nextDouble();
            if (y <= minY + 1) return r < 0.5 ? 1 : 0;
            if (y >= maxY) return r < 0.5 ? -1 : 0;
            return r < 0.35 ? 1 : r < 0.5 ? -1 : 0;
        }

        /** A sideways offset for the next jump that keeps the course near the middle of its lane. */
        int lat() {
            int r = rng.nextInt(3) - 1;
            if (turning) return 0;
            int per = lx() * sideX + lz() * sideZ; // how much sc() changes per +1 lat
            if (Math.abs(sc() + r * per - laneCenter) > 2) return 0;
            return r;
        }
    }

    // =====================================================================
    // The four courses
    // =====================================================================

    public static final Map<String, List<JumpInfo>> CHECK = new LinkedHashMap<>();

    static LobbyDesign.Course easy() {
        B b = new B("easy", "Garden Run", 0, EASY, 11, new int[]{-335, -55, -138, 55}, -142, 104, 0, -1, 0);
        for (int seg = 0; seg < 6; seg++) {
            for (int i = 0; i < 7; i++) {
                int dy = b.dyFor(104, 116);
                double r = b.rng.nextDouble();
                if (r < 0.15) b.jump(Kind.SLAB, dy, b.lat(), 0.6, 0.8, "slab");
                else if (r < 0.22 && seg >= 3) b.ladder(1);
                else b.jump(Kind.FULL, dy, b.lat(), 0.6, seg < 2 ? 0.75 : 0.85, "full");
            }
            if (seg < 5) b.station();
        }
        b.finish();
        return build(b);
    }

    static LobbyDesign.Course medium() {
        B b = new B("medium", "Ruins Run", 1, MEDIUM, 22, new int[]{138, -55, 335, 55}, 142, 104, 0, 1, 0);
        for (int seg = 0; seg < 7; seg++) {
            for (int i = 0; i < 8; i++) {
                int dy = b.dyFor(104, 122);
                double r = b.rng.nextDouble();
                double hi = seg < 2 ? 0.9 : 0.97;
                if (r < 0.14) b.jump(Kind.FENCE, 0, b.lat(), 0.8, 0.9, "fence");
                else if (r < 0.30) b.ladder(2);
                else if (r < 0.38) b.jump(Kind.TRAPDOOR, Math.min(dy, 0), 0, 0.8, hi, "trapdoor");
                else b.jump(Kind.FULL, dy, b.lat(), 0.85, hi, "full");
            }
            if (seg < 6) b.station();
        }
        b.finish();
        return build(b);
    }

    static LobbyDesign.Course hard() {
        B b = new B("hard", "Sky Spire", 2, HARD, 33, new int[]{-65, -335, 65, -138}, 0, 104, -142, 0, -1);
        for (int seg = 0; seg < 7; seg++) {
            for (int i = 0; i < 9; i++) {
                int dy = b.dyFor(104, 135);
                double r = b.rng.nextDouble();
                if (r < 0.24) b.ladder(3);
                else if (r < 0.33) b.headHitter(4, Kind.FULL);
                else if (r < 0.45) b.jump(Kind.FENCE, 0, b.lat(), 0.9, 1.0, "fence");
                else if (r < 0.55) b.jump(Kind.WALL, 0, 0, 0.9, 1.0, "wall");
                else if (r < 0.62 && b.kind == Kind.FULL) b.jump(Kind.BARS, Math.min(dy, 0), 0, 0.9, 0.95, "iron bars");
                else b.jump(Kind.FULL, dy, b.lat(), 0.92, 1.0, "full");
            }
            if (seg < 6) b.station();
        }
        b.finish();
        return build(b);
    }

    static LobbyDesign.Course extreme() {
        B b = new B("extreme", "Void Gauntlet", 3, EXTREME, 44, new int[]{-65, 140, 65, 333}, 0, 106, 142, 0, 1);
        for (int seg = 0; seg < 5; seg++) {
            for (int i = 0; i < 13; i++) {
                int dy = b.dyFor(106, 140);
                double r = b.rng.nextDouble();
                if (r < 0.28) b.ladder(3);
                else if (r < 0.36) b.headHitter(4, Kind.FENCE);
                else if (r < 0.50) b.jump(Kind.FENCE, b.kind == Kind.FULL ? 0 : -1, b.lat(), 0.97, 1.0, "fence");
                else if (r < 0.60) b.jump(Kind.BARS, b.kind == Kind.FULL ? 0 : -1, 0, 0.97, 1.0, "iron bars");
                else if (r < 0.68) b.jump(Kind.ROD, b.kind == Kind.FULL ? 0 : -1, 0, 0.97, 1.0, "end rod");
                else if (r < 0.76) b.jump(Kind.WALL, b.kind == Kind.FULL ? 0 : -1, b.lat(), 0.97, 1.0, "wall");
                else b.jump(Kind.FULL, dy, b.lat(), 0.97, 1.0, "full");
            }
            if (seg < 4) b.station();
        }
        b.finish();
        return build(b);
    }

    private static LobbyDesign.Course build(B b) {
        CHECK.put(b.name, b.info);
        List<int[]> stations = new ArrayList<>();
        Set<Long> stationBlocks = new HashSet<>();
        Map<Long, Integer> stationOf = new HashMap<>();
        int[] segFail = new int[b.stationIdx.size()];
        for (int s = 0; s < b.stationIdx.size(); s++) {
            int[] p = b.jumps.get(b.stationIdx.get(s));
            stations.add(new int[]{p[0], p[1] + 1, p[2]});
            for (int dx = -1; dx <= 1; dx++)
                for (int dz = -1; dz <= 1; dz++) {
                    long k = LobbyCanvas.key(p[0] + dx, p[1], p[2] + dz);
                    stationBlocks.add(k);
                    stationOf.put(k, s);
                }
            // Fall more than ~2 blocks below the lowest block of this section = back to the checkpoint.
            int from = b.stationIdx.get(s), to = s + 1 < b.stationIdx.size() ? b.stationIdx.get(s + 1) : b.jumps.size() - 1;
            int low = Integer.MAX_VALUE;
            for (int i = from; i <= to; i++) low = Math.min(low, b.jumps.get(i)[1]);
            segFail[s] = low - 1;
        }
        return new LobbyDesign.Course(b.id, b.name, b.tier, stations, stationBlocks, stationOf, segFail[0], b.jumps, b.th.full,
                b.states, b.extras, b.extraStates, segFail);
    }

    public static List<LobbyDesign.Course> all() {
        return List.of(easy(), medium(), hard(), extreme());
    }
}
