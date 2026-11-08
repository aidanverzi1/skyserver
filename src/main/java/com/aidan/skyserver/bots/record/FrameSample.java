package com.aidan.skyserver.bots.record;

/**
 * One tick of one real player, as the server sees it. Kept small: the recorder writes thousands of these.
 *
 * tick     ticks since this recording started (gaps = boring stretches that weren't kept)
 * x y z    feet position
 * yaw/pitch where they're looking
 * forward/strafe  keys worked out from movement (1 = walking speed, more = sprinting, negative = back/right)
 * flags    bits, see the constants below
 * slot     selected hotbar slot, item = what's in the hand (see PlayerRecorder.itemCode)
 * health   their health
 * targetId entity id of the nearest enemy (-1 = none within 16 blocks); tdx/tdy/tdz = where that enemy is
 *          relative to the player's feet; targetHealth = the enemy's health
 */
public record FrameSample(int tick, float x, float y, float z, float yaw, float pitch,
                          float forward, float strafe, int flags, int slot, int item, float health,
                          int targetId, float tdx, float tdy, float tdz, float targetHealth) {

    public static final int ON_GROUND = 1;
    public static final int SPRINTING = 1 << 1;
    public static final int SNEAKING = 1 << 2;
    public static final int JUMPED = 1 << 3;
    public static final int USING_ITEM = 1 << 4;
    /** Moved too far in one tick (teleport, respawn): ignore this frame's movement. */
    public static final int TELEPORTED = 1 << 5;
    public static final int TARGET_IS_BOT = 1 << 6;
    public static final int IN_WATER = 1 << 7;
}
