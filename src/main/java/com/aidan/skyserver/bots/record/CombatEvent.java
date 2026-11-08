package com.aidan.skyserver.bots.record;

/**
 * Something that happened to a recorded player on a tick.
 * otherId = the other entity involved (-1 = none); value depends on the kind (see each one).
 */
public record CombatEvent(int tick, Kind kind, int otherId, float value) {

    public enum Kind {
        /** Arm swing (every left click: hits, misses, mining). value = item code in hand. */
        SWING,
        /** Melee hit landed. other = victim, value = damage dealt. */
        HIT,
        /** Arrow/fireball/snowball hit landed. other = victim, value = damage dealt. */
        SHOT_HIT,
        /** Got hurt. other = attacker (-1 = fall, void, fire...), value = damage taken. */
        HURT,
        /** Started drawing a bow or crossbow. value = item code. */
        BOW_DRAW,
        /** Let go of the bow. value = ticks it was drawn (20+ = full power). */
        BOW_RELEASE,
        /** Started eating or drinking. value = item code. */
        EAT,
        /** Started holding right-click with something else (shield...). value = item code. */
        ITEM_USE,
        /** Threw something (snowball, egg, pearl, fireball...). value = item code. */
        THROW,
        /** Placed a block. */
        PLACE,
        /** Broke a block. */
        BREAK,
        /** Killed someone (other = victim). */
        KILL,
        /** Died (other = killer or -1). In Bedwars/Skywars/duels this is the "death" the game catches. */
        DEATH
    }
}
