package com.aidan.skyserver.bots;

import java.util.UUID;

/** What the decision code wants a bot to do this tick. The bot's "body" (server or simulator) carries it out. */
public record GwAction(Kind kind, int x, int y, int z, Pathfinder.Goal goal, Pathfinder.Options options,
                       UUID target, String doing) {

    public enum Kind {
        /** Walk to a goal (and stand there once reached). */
        GOTO,
        /** Fight the target unit (the body picks sword / bow / wand). */
        FIGHT,
        /** Mine the block at x, y, z. */
        MINE,
        /** Place a block at x, y, z. */
        PLACE,
        /** Nothing to do. */
        IDLE
    }

    public GwAction withDoing(String newDoing) {
        return new GwAction(kind, x, y, z, goal, options, target, newDoing);
    }

    public static GwAction go(int x, int y, int z, Pathfinder.Goal goal, Pathfinder.Options options, String doing) {
        return new GwAction(Kind.GOTO, x, y, z, goal, options, null, doing);
    }

    public static GwAction fight(UUID target, String doing) {
        return new GwAction(Kind.FIGHT, 0, 0, 0, null, null, target, doing);
    }

    public static GwAction mine(int x, int y, int z, String doing) {
        return new GwAction(Kind.MINE, x, y, z, null, null, null, doing);
    }

    public static GwAction place(int x, int y, int z, String doing) {
        return new GwAction(Kind.PLACE, x, y, z, null, null, null, doing);
    }

    public static GwAction idle(String doing) {
        return new GwAction(Kind.IDLE, 0, 0, 0, null, null, null, doing);
    }
}
