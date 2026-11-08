package com.aidan.skyserver.bots;

import java.util.*;

/**
 * A* pathfinding over the block grid for bots. Plain Java (no Minecraft classes), so it can be
 * tested offline against the map designs.
 *
 * A node is the block a bot's feet are in. Moves: walk (8 directions), jump up one block,
 * drop down up to 3, climb ladders, bridge across gaps by placing a block, and dig through
 * blocks the bot is allowed to break.
 */
public final class Pathfinder {

    /** What the pathfinder needs to know about the world. */
    public interface World {
        /** No collision for a body (air, flowers, ladders, water...). */
        boolean passable(int x, int y, int z);
        /** Something you can stand on top of (not fences, not too thin). */
        boolean standable(int x, int y, int z);
        boolean climbable(int x, int y, int z);
        /** Lava, fire, magma, cactus... never stand in or on it. */
        boolean dangerous(int x, int y, int z);
        /** The bot may break this block (and it's worth breaking). */
        boolean breakable(int x, int y, int z);
        /** The bot may place a block here. */
        boolean placeable(int x, int y, int z);
        /** Below this height is the void. */
        int minY();
    }

    public enum Move { START, WALK, JUMP, DROP, CLIMB_UP, CLIMB_DOWN, BRIDGE, DIG, PILLAR }

    public record Step(int x, int y, int z, Move move) {}

    public interface Goal {
        boolean reached(int x, int y, int z);
        /** Straight-line guess of the distance left. */
        double estimate(int x, int y, int z);
    }

    public static final class Options {
        public boolean bridge = false;
        public int maxBridge = 0;
        public boolean dig = false;
        public int maxNodes = 6000;
        public int maxFall = 3;
        public double bridgeCost = 4;
        public double digCost = 5;

        public Options bridge(int blocks) {
            this.bridge = blocks > 0;
            this.maxBridge = blocks;
            return this;
        }

        public Options dig(boolean dig) {
            this.dig = dig;
            return this;
        }

        public Options nodes(int n) {
            this.maxNodes = n;
            return this;
        }
    }

    public static final class Result {
        public final List<Step> steps;
        public final boolean complete;  // false = best partial path toward the goal
        public final int explored;

        Result(List<Step> steps, boolean complete, int explored) {
            this.steps = steps;
            this.complete = complete;
            this.explored = explored;
        }
    }

    private static final int[][] DIRS = {
            {1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}
    };

    private static final class Node {
        final int x, y, z;
        double g, f;
        Node parent;
        Move move;
        int bridges;

        Node(int x, int y, int z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }

    private Pathfinder() {}

    public static Goal near(int tx, int ty, int tz, double within) {
        return new Goal() {
            public boolean reached(int x, int y, int z) {
                double dx = x - tx, dy = y - ty, dz = z - tz;
                return dx * dx + dy * dy + dz * dz <= within * within;
            }

            public double estimate(int x, int y, int z) {
                double dx = x - tx, dy = y - ty, dz = z - tz;
                return Math.max(0, Math.sqrt(dx * dx + dy * dy + dz * dz) - within);
            }
        };
    }

    public static boolean canStand(World w, int x, int y, int z) {
        if (y <= w.minY()) return false;
        if (!w.passable(x, y, z) || !w.passable(x, y + 1, z)) return false;
        if (w.dangerous(x, y, z) || w.dangerous(x, y + 1, z)) return false;
        if (w.climbable(x, y, z)) return true;
        return w.standable(x, y - 1, z) && !w.dangerous(x, y - 1, z);
    }

    private static boolean body(World w, int x, int y, int z) {
        return w.passable(x, y, z) && w.passable(x, y + 1, z) && !w.dangerous(x, y, z) && !w.dangerous(x, y + 1, z);
    }

    private static long key(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
    }

    public static Result find(World w, int sx, int sy, int sz, Goal goal, Options o) {
        PriorityQueue<Node> open = new PriorityQueue<>(Comparator.comparingDouble(n -> n.f));
        Map<Long, Node> seen = new HashMap<>();
        Node start = new Node(sx, sy, sz);
        start.move = Move.START;
        start.f = goal.estimate(sx, sy, sz);
        open.add(start);
        seen.put(key(sx, sy, sz), start);
        Node best = start;
        double bestH = start.f;
        Set<Long> closed = new HashSet<>();
        int explored = 0;

        while (!open.isEmpty() && explored < o.maxNodes) {
            Node n = open.poll();
            long k = key(n.x, n.y, n.z);
            if (!closed.add(k)) continue;
            explored++;
            if (goal.reached(n.x, n.y, n.z)) return new Result(build(n), true, explored);
            double h = goal.estimate(n.x, n.y, n.z);
            if (h < bestH) {
                bestH = h;
                best = n;
            }
            expand(w, n, o, open, seen, closed, goal);
        }
        return new Result(best == start ? List.of() : build(best), false, explored);
    }

    private static void expand(World w, Node n, Options o, PriorityQueue<Node> open, Map<Long, Node> seen,
                               Set<Long> closed, Goal goal) {
        int x = n.x, y = n.y, z = n.z;
        boolean onGround = w.standable(x, y - 1, z) || n.move == Move.BRIDGE || n.move == Move.PILLAR; // a bridged spot will have a block

        for (int[] d : DIRS) {
            int dx = d[0], dz = d[1];
            boolean diag = dx != 0 && dz != 0;
            int nx = x + dx, nz = z + dz;

            if (canStand(w, nx, y, nz)) {
                if (diag && !(body(w, x + dx, y, z) && body(w, x, y, z + dz))) continue;
                add(n, nx, y, nz, diag ? 1.414 : 1.0, Move.WALK, n.bridges, open, seen, closed, goal);
                continue;
            }
            if (diag) continue;

            // Jump up one block.
            if (canStand(w, nx, y + 1, nz) && w.passable(x, y + 2, z)) {
                add(n, nx, y + 1, nz, 2.0, Move.JUMP, n.bridges, open, seen, closed, goal);
            }
            // Walk off an edge and drop.
            if (body(w, nx, y, nz)) {
                for (int k = 1; k <= o.maxFall; k++) {
                    if (canStand(w, nx, y - k, nz)) {
                        add(n, nx, y - k, nz, 1.0 + 0.5 * k, Move.DROP, n.bridges, open, seen, closed, goal);
                        break;
                    }
                    if (!w.passable(nx, y - k, nz) || w.dangerous(nx, y - k, nz)) break;
                }
                // Bridge: place a block under the next spot.
                if (o.bridge && n.bridges < o.maxBridge && onGround
                        && !w.standable(nx, y - 1, nz) && w.placeable(nx, y - 1, nz)) {
                    add(n, nx, y, nz, o.bridgeCost, Move.BRIDGE, n.bridges + 1, open, seen, closed, goal);
                }
            }
            // Dig through blocks in the way.
            if (o.dig && w.standable(nx, y - 1, nz) && !w.dangerous(nx, y - 1, nz)) {
                int feet = cell(w, nx, y, nz), head = cell(w, nx, y + 1, nz);
                if (feet >= 0 && head >= 0 && feet + head > 0 && w.passable(x, y + 1, z)) {
                    add(n, nx, y, nz, 1.0 + o.digCost * (feet + head), Move.DIG, n.bridges, open, seen, closed, goal);
                }
            }
        }

        // Pillar up: jump and place a block underneath (how players climb out of holes or up to a ledge).
        // Only when the goal is above us, so the search doesn't try towers everywhere.
        if (o.bridge && n.bridges < o.maxBridge && onGround && goal.estimate(x, y + 1, z) < goal.estimate(x, y, z) - 0.05
                && w.placeable(x, y, z)
                && w.passable(x, y + 2, z) && !w.dangerous(x, y + 2, z)) {
            add(n, x, y + 1, z, o.bridgeCost * 2.5, Move.PILLAR, n.bridges + 1, open, seen, closed, goal);
        }

        // Ladders.
        if (w.climbable(x, y, z) && canStand(w, x, y + 1, z)) {
            add(n, x, y + 1, z, 1.5, Move.CLIMB_UP, n.bridges, open, seen, closed, goal);
        }
        if (w.climbable(x, y - 1, z) && canStand(w, x, y - 1, z)) {
            add(n, x, y - 1, z, 1.2, Move.CLIMB_DOWN, n.bridges, open, seen, closed, goal);
        }
    }

    /** 0 = open, 1 = must break, -1 = can't pass. */
    private static int cell(World w, int x, int y, int z) {
        if (w.dangerous(x, y, z)) return -1;
        if (w.passable(x, y, z)) return 0;
        return w.breakable(x, y, z) ? 1 : -1;
    }

    private static void add(Node from, int x, int y, int z, double cost, Move move, int bridges,
                            PriorityQueue<Node> open, Map<Long, Node> seen, Set<Long> closed, Goal goal) {
        long k = key(x, y, z);
        if (closed.contains(k)) return;
        double g = from.g + cost;
        Node existing = seen.get(k);
        if (existing != null && existing.g <= g) return;
        Node n = new Node(x, y, z);
        n.g = g;
        n.f = g + goal.estimate(x, y, z) * 1.25;
        n.parent = from;
        n.move = move;
        n.bridges = bridges;
        seen.put(k, n);
        open.add(n);
    }

    private static List<Step> build(Node end) {
        ArrayList<Step> list = new ArrayList<>();
        for (Node n = end; n != null && n.parent != null; n = n.parent) list.add(new Step(n.x, n.y, n.z, n.move));
        Collections.reverse(list);
        return list;
    }
}
