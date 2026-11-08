package com.aidan.skyserver.bots.record;

/**
 * Works out which movement keys a player was holding from how they moved. The server never sees the
 * keys themselves, only where the player ends up each tick. Plain Java (no Minecraft classes).
 */
public final class InputInference {
    /** Blocks per tick when walking on flat ground. Sprinting is about 1.3x this. */
    public static final double WALK_SPEED = 0.2158;
    /** Faster than this in one tick = a teleport or respawn, not movement. */
    public static final double TELEPORT_DIST = 4.0;

    private InputInference() {}

    /**
     * {forward, strafe} from this tick's movement (dx, dz) and the player's yaw.
     * 1.0 = walking speed. Positive forward = W, negative = S. Positive strafe = A (left), negative = D (right),
     * the same convention the bots use (zza / xxa).
     */
    public static float[] forwardStrafe(double dx, double dz, float yawDeg) {
        double yaw = Math.toRadians(yawDeg);
        double fx = -Math.sin(yaw), fz = Math.cos(yaw);   // facing direction
        double lx = Math.cos(yaw), lz = Math.sin(yaw);    // to the left
        double f = (dx * fx + dz * fz) / WALK_SPEED;
        double s = (dx * lx + dz * lz) / WALK_SPEED;
        return new float[]{(float) clamp(f, -1.5, 1.5), (float) clamp(s, -1.5, 1.5)};
    }

    /** Left the ground going up this tick = pressed jump (or got knocked up; the HURT event tells them apart). */
    public static boolean jumped(boolean wasOnGround, boolean onGround, double dy) {
        return wasOnGround && !onGround && dy > 0.05;
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
