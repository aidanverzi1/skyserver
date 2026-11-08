package com.aidan.skyserver;

import java.util.HashMap;
import java.util.Map;

/**
 * Crash protection. Every bit of SkyServer that runs each tick goes through Safe.run, so if one part
 * hits a bug (say, a bot in a weird spot or a broken save file) it gets logged and skipped instead of
 * crashing the whole server. The first error from each part is logged in full; after that, a short
 * count once a minute so the log doesn't fill up.
 */
public final class Safe {
    private static final Map<String, long[]> ERRORS = new HashMap<>(); // name -> {count, lastLogMs}

    private Safe() {}

    public static void run(String name, Runnable r) {
        try {
            r.run();
        } catch (RuntimeException | StackOverflowError | NoClassDefFoundError | ExceptionInInitializerError e) {
            long[] c = ERRORS.computeIfAbsent(name, k -> new long[2]);
            c[0]++;
            long now = System.currentTimeMillis();
            if (c[0] == 1) {
                Skyserver.LOGGER.error("[SkyServer] '{}' hit an error and was skipped (the server keeps running). Please send this to the mod author:", name, e);
                c[1] = now;
            } else if (now - c[1] > 60_000) {
                Skyserver.LOGGER.error("[SkyServer] '{}' is still failing ({} times so far): {}", name, c[0], e.toString());
                c[1] = now;
            }
        }
    }

    /** Errors so far, for /ss status. */
    public static Map<String, long[]> errors() {
        return ERRORS;
    }
}
