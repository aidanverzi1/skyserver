package com.aidan.skyserver.bots;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * What the bots have learned about each route on each map, from real matches: how often a
 * route was taken, how much gold attackers on it broke, and how often they died on it.
 * Saved to config/skyserver-bot-routes.txt so it carries over between restarts. Old results
 * slowly fade, so the bots keep adapting to how your players defend.
 *
 * Routes: -2 north flank, -1 north center, +1 south center, +2 south flank.
 */
public final class LaneMemory {
    private static final Path FILE = Path.of("config", "skyserver-bot-routes.txt");
    private static final double FADE = 0.97; // each match, older results count a little less
    private static final Map<String, double[]> STATS = new HashMap<>(); // "map:lane" -> {tries, gold, deaths}
    private static boolean loaded = false;

    private LaneMemory() {}

    private static double[] get(String map, int lane) {
        if (!loaded) load();
        return STATS.computeIfAbsent(map + ":" + lane, k -> new double[3]);
    }

    public static void tried(String map, int lane) {
        get(map, lane)[0] += 1;
    }

    public static void goldBroken(String map, int lane) {
        get(map, lane)[1] += 1;
    }

    public static void died(String map, int lane) {
        get(map, lane)[2] += 1;
    }

    /** How well a route has been working (higher = better). Unknown routes start in the middle. */
    public static double score(String map, int lane) {
        double[] s = get(map, lane);
        return (s[1] + 1.0) / (s[2] + 0.4 * s[0] + 2.0);
    }

    public static String summary(String map) {
        StringBuilder sb = new StringBuilder();
        for (int lane : new int[]{-3, -2, -1, 1, 2, 3}) {
            double[] s = get(map, lane);
            sb.append(String.format("%n  %-13s tries %.0f, gold %.0f, deaths %.0f, score %.2f",
                    laneName(lane), s[0], s[1], s[2], score(map, lane)));
        }
        return sb.toString();
    }

    public static String laneName(int lane) {
        return switch (lane) {
            case -2 -> "north flank";
            case -1 -> "north center";
            case 1 -> "south center";
            case 2 -> "south flank";
            case -3 -> "north sky path";
            case 3 -> "south sky path";
            default -> "?";
        };
    }

    private static void load() {
        loaded = true;
        try {
            if (!Files.exists(FILE)) return;
            for (String line : Files.readAllLines(FILE)) {
                String[] p = line.trim().split("\\s+");
                if (p.length != 4 || line.startsWith("#")) continue;
                STATS.put(p[0], new double[]{Double.parseDouble(p[1]), Double.parseDouble(p[2]), Double.parseDouble(p[3])});
            }
        } catch (Exception e) {
            System.err.println("[skyserver] Bots: couldn't read " + FILE + ": " + e);
        }
    }

    /** Forget everything (and save the empty file). */
    public static void reset() {
        STATS.clear();
        loaded = true;
        try {
            Files.deleteIfExists(FILE);
        } catch (Exception ignored) {
        }
    }

    /** Called when a match ends: fade old results a little and save. */
    public static void save() {
        if (!loaded) return;
        try {
            Files.createDirectories(FILE.getParent());
            List<String> lines = new ArrayList<>();
            lines.add("# Gold Wars bot route memory: map:route tries gold deaths (safe to delete to reset)");
            for (Map.Entry<String, double[]> e : new TreeMap<>(STATS).entrySet()) {
                double[] s = e.getValue();
                for (int i = 0; i < 3; i++) s[i] *= FADE;
                lines.add(String.format(Locale.ROOT, "%s %.3f %.3f %.3f", e.getKey(), s[0], s[1], s[2]));
            }
            Files.write(FILE, lines);
        } catch (Exception e) {
            System.err.println("[skyserver] Bots: couldn't save " + FILE + ": " + e);
        }
    }
}
