package com.aidan.skyserver.lobby;

import java.util.*;

/**
 * A plain-Java block grid for one piece (usually one chunk) of the lobby. Everything outside
 * the clip area is ignored, so the huge lobby can be built one chunk at a time. Blocks are
 * Minecraft block-state strings ("stone_bricks", "oak_stairs[facing=east]"); null = air.
 */
public final class LobbyCanvas {
    public final int minX, maxX, minZ, maxZ;
    private final LinkedHashMap<Long, String> blocks = new LinkedHashMap<>();

    public LobbyCanvas(int minX, int minZ, int maxX, int maxZ) {
        this.minX = minX;
        this.minZ = minZ;
        this.maxX = maxX;
        this.maxZ = maxZ;
    }

    static long key(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
    }

    static int kx(long k) { return (int) (k >> 38); }
    static int kz(long k) { return (int) (k << 26 >> 38); }
    static int ky(long k) { return (int) (k << 52 >> 52); }

    public boolean in(int x, int z) {
        return x >= minX && x <= maxX && z >= minZ && z <= maxZ;
    }

    public boolean overlaps(int x1, int z1, int x2, int z2) {
        return Math.max(x1, x2) >= minX && Math.min(x1, x2) <= maxX && Math.max(z1, z2) >= minZ && Math.min(z1, z2) <= maxZ;
    }

    /** Sets a block (null or "air" = air, which also clears what an earlier part placed). */
    public void set(int x, int y, int z, String state) {
        if (!in(x, z)) return;
        long k = key(x, y, z);
        blocks.remove(k); // keep placing order = painting order
        blocks.put(k, state == null ? "air" : state);
    }

    public String get(int x, int y, int z) {
        String s = blocks.get(key(x, y, z));
        return s == null || s.equals("air") ? null : s;
    }

    public void setIfAir(int x, int y, int z, String state) {
        if (in(x, z) && get(x, y, z) == null) set(x, y, z, state);
    }

    public void fill(int x1, int y1, int z1, int x2, int y2, int z2, String state) {
        int ax = Math.max(Math.min(x1, x2), minX), bx = Math.min(Math.max(x1, x2), maxX);
        int az = Math.max(Math.min(z1, z2), minZ), bz = Math.min(Math.max(z1, z2), maxZ);
        int ay = Math.min(y1, y2), by = Math.max(y1, y2);
        for (int x = ax; x <= bx; x++)
            for (int z = az; z <= bz; z++)
                for (int y = ay; y <= by; y++) set(x, y, z, state);
    }

    /** Calls the action for every block in painting order: (x, y, z, state or "air"). */
    public void forEach(Action action) {
        for (Map.Entry<Long, String> e : blocks.entrySet()) {
            long k = e.getKey();
            action.accept(kx(k), ky(k), kz(k), e.getValue());
        }
    }

    public int size() {
        return blocks.size();
    }

    public interface Action {
        void accept(int x, int y, int z, String state);
    }
}
