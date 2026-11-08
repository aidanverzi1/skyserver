package com.aidan.skyserver.bots;

import com.aidan.skyserver.goldwars.MapDesigns;

import java.util.*;

/**
 * A snapshot of a Gold Wars match that the bot decision code reads. Plain Java (no Minecraft
 * classes), so the same decisions run both on the server and in the offline simulator.
 *
 * Teams are identified by their side: -1 = Red (west), +1 = Blue (east).
 * Vaults: index 0 = north (negative z), 1 = south.
 */
public final class GwState {

    /** One player (real or bot). */
    public static final class Unit {
        public final UUID id;
        public final int side;
        public final String cls;          // builder, knight, archer, mage, longbow, scout
        public final Difficulty diff;     // null for real players
        public final boolean alive;
        public final double x, y, z;
        public final float health;
        public final UUID lastAttacker;   // who hit this unit last
        public final long lastHurtTick;

        public Unit(UUID id, int side, String cls, Difficulty diff, boolean alive,
                    double x, double y, double z, float health, UUID lastAttacker, long lastHurtTick) {
            this.id = id;
            this.side = side;
            this.cls = cls;
            this.diff = diff;
            this.alive = alive;
            this.x = x;
            this.y = y;
            this.z = z;
            this.health = health;
            this.lastAttacker = lastAttacker;
            this.lastHurtTick = lastHurtTick;
        }

        public boolean isBot() {
            return diff != null;
        }

        public int bx() { return (int) Math.floor(x); }
        public int by() { return (int) Math.floor(y + 0.01); }
        public int bz() { return (int) Math.floor(z); }

        public double dist(Unit o) {
            double dx = x - o.x, dy = y - o.y, dz = z - o.z;
            return Math.sqrt(dx * dx + dy * dy + dz * dz);
        }

        public double dist(double px, double py, double pz) {
            double dx = x - px, dy = y - py, dz = z - pz;
            return Math.sqrt(dx * dx + dy * dy + dz * dz);
        }
    }

    public final long tick;
    public final boolean buildPhase;
    public final boolean overtime;
    public final int timerTicks;          // ticks left in the current phase
    public final String map;              // sky, village, caves
    public final int floorY;              // world y of the map's floor (design y = 0)
    public final List<Unit> units;
    private final Map<Integer, Set<Long>> gold; // side -> packed gold positions
    public final Pathfinder.World world;  // shared map view (line of sight, open spots)
    private final Map<UUID, Unit> byId = new HashMap<>();

    public GwState(long tick, boolean buildPhase, boolean overtime, int timerTicks, String map, int floorY,
                   List<Unit> units, Set<Long> redGold, Set<Long> blueGold, Pathfinder.World world) {
        this.tick = tick;
        this.buildPhase = buildPhase;
        this.overtime = overtime;
        this.timerTicks = timerTicks;
        this.map = map;
        this.floorY = floorY;
        this.units = units;
        this.gold = Map.of(-1, redGold, 1, blueGold);
        this.world = world;
        for (Unit u : units) byId.put(u.id, u);
    }

    public Unit unit(UUID id) {
        return byId.get(id);
    }

    // ---- Packed block positions ----

    public static long pack(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
    }

    public static int px(long p) { return (int) (p >> 38); }
    public static int pz(long p) { return (int) (p << 26 >> 38); }
    public static int py(long p) { return (int) (p << 52 >> 52); }

    // ---- Gold and vaults ----

    public Set<Long> gold(int side) {
        return gold.get(side);
    }

    /** Gold still standing in one vault (0 north, 1 south). */
    public List<long[]> vaultGold(int side, int vault) {
        List<long[]> list = new ArrayList<>();
        for (long p : gold(side)) {
            boolean north = pz(p) < 0;
            if ((vault == 0) == north) list.add(new long[]{px(p), py(p), pz(p)});
        }
        return list;
    }

    public int vaultCount(int side, int vault) {
        int n = 0;
        for (long p : gold(side)) if ((vault == 0) == (pz(p) < 0)) n++;
        return n;
    }

    /** One of the vault's middle gold blocks (world coordinates). */
    public int[] vault(int side, int vault) {
        int x = side * MapDesigns.VAULT_X; // the inner column (same for both teams)
        int z = vault == 0 ? -MapDesigns.VAULT_Z - 1 : MapDesigns.VAULT_Z;
        return new int[]{x, floorY + 2, z};
    }

    public boolean inSpawnZone(double x, double z) {
        for (int side = -1; side <= 1; side += 2) {
            if (Math.abs(x - side * MapDesigns.SPAWN_X - 0.5) <= MapDesigns.SPAWN_PROTECT + 1 && Math.abs(z - 0.5) <= MapDesigns.SPAWN_PROTECT + 1) return true;
        }
        return false;
    }

    public int centerLaneZ() {
        return MapDesigns.centerLaneZ(map);
    }

    public int flankLaneZ() {
        return MapDesigns.flankLaneZ(map);
    }

    /** Clear line between two points (no solid blocks in the way)? */
    public boolean lineOfSight(double x1, double y1, double z1, double x2, double y2, double z2) {
        return firstBlock(x1, y1, z1, x2, y2, z2) == null;
    }

    /** The first solid block on the line from a to b (ignoring the cell b is in), or null. */
    public int[] firstBlock(double x1, double y1, double z1, double x2, double y2, double z2) {
        double dx = x2 - x1, dy = y2 - y1, dz = z2 - z1;
        double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        int steps = (int) Math.ceil(len * 4);
        int ex = (int) Math.floor(x2), ey = (int) Math.floor(y2), ez = (int) Math.floor(z2);
        int lx = Integer.MIN_VALUE, ly = 0, lz = 0;
        for (int i = 1; i < steps; i++) {
            double t = i / (double) steps;
            int bx = (int) Math.floor(x1 + dx * t), by = (int) Math.floor(y1 + dy * t), bz = (int) Math.floor(z1 + dz * t);
            if (bx == lx && by == ly && bz == lz) continue;
            lx = bx; ly = by; lz = bz;
            if (bx == ex && by == ey && bz == ez) return null;
            if (!world.passable(bx, by, bz)) return new int[]{bx, by, bz};
        }
        return null;
    }
}
