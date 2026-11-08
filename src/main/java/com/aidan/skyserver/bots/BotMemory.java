package com.aidan.skyserver.bots;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * What the bots have learned, saved to config/skyserver-bot-memory.txt so it carries over between
 * restarts. Plain named lists of numbers ("gw:village:humanLane" -> 4 counters). After every match
 * all numbers fade a little (x0.97), so newer matches count more and the bots keep adapting to how
 * people on your server play now. Safe to delete the file to reset (or /bots learned reset).
 */
public final class BotMemory {
    private static final double FADE = 0.97;
    private static Path file = Path.of("config", "skyserver-bot-memory.txt");
    private static final Map<String, double[]> DATA = new TreeMap<>();
    private static boolean loaded = false;

    private BotMemory() {}

    /** For the simulator: keep memory in RAM only (null) or use another file. */
    public static void useFile(Path f) {
        file = f;
        DATA.clear();
        loaded = f == null;
    }

    public static double[] get(String key, int size) {
        if (!loaded) load();
        double[] v = DATA.get(key);
        if (v == null || v.length < size) {
            double[] n = new double[size];
            if (v != null) System.arraycopy(v, 0, n, 0, v.length);
            DATA.put(key, n);
            v = n;
        }
        return v;
    }

    public static void add(String key, int index, int size, double amount) {
        get(key, size)[index] += amount;
    }

    /** Call when a match ends: older results count a little less, then save. */
    public static void matchEnded() {
        if (!loaded) load();
        for (double[] v : DATA.values()) for (int i = 0; i < v.length; i++) v[i] *= FADE;
        save();
    }

    /** Fades only the keys starting with prefix (by factor), without saving. */
    public static void fade(String prefix, double factor) {
        if (!loaded) load();
        for (Map.Entry<String, double[]> e : DATA.entrySet()) {
            if (!e.getKey().startsWith(prefix)) continue;
            double[] v = e.getValue();
            for (int i = 0; i < v.length; i++) v[i] *= factor;
        }
    }

    /** Saves right away (no fading). */
    public static void saveNow() {
        if (!loaded) load();
        save();
    }

    public static void reset() {
        DATA.clear();
        loaded = true;
        save();
    }

    private static void load() {
        loaded = true;
        if (file == null) return;
        try {
            if (!Files.exists(file)) return;
            for (String line : Files.readAllLines(file)) {
                if (line.startsWith("#") || line.isBlank()) continue;
                String[] p = line.trim().split("\\s+");
                double[] v = new double[p.length - 1];
                for (int i = 1; i < p.length; i++) v[i - 1] = Double.parseDouble(p[i]);
                DATA.put(p[0], v);
            }
        } catch (Exception e) {
            System.err.println("[skyserver] Bots: couldn't read " + file + ": " + e);
        }
    }

    private static void save() {
        if (file == null) return;
        try {
            if (file.getParent() != null) Files.createDirectories(file.getParent());
            List<String> lines = new ArrayList<>();
            lines.add("# What the bots have learned (safe to delete to reset). key value value ...");
            for (Map.Entry<String, double[]> e : DATA.entrySet()) {
                StringBuilder sb = new StringBuilder(e.getKey());
                for (double d : e.getValue()) sb.append(' ').append(String.format(Locale.ROOT, "%.3f", d));
                lines.add(sb.toString());
            }
            Files.write(file, lines);
        } catch (Exception e) {
            System.err.println("[skyserver] Bots: couldn't save " + file + ": " + e);
        }
    }

    // ---- Picking between a few plans (learns which one wins more) ----

    /**
     * Picks one of n plans for "key": mostly the one with the best average result so far, sometimes
     * one that hasn't been tried much (so it keeps testing). bonus[i] (or null) nudges the choice.
     */
    public static int pickPlan(String key, int n, double[] bonus, Random rng) {
        double[] v = get(key, n * 2); // [tries0, total0, tries1, total1, ...]
        if (rng.nextDouble() < 0.12) return rng.nextInt(n);
        double totalTries = 0;
        for (int i = 0; i < n; i++) totalTries += v[i * 2];
        int best = 0;
        double bestScore = -1e9;
        for (int i = 0; i < n; i++) {
            double tries = v[i * 2], avg = (v[i * 2 + 1] + 0.5) / (tries + 1); // starts at 0.5
            double score = avg + 0.35 * Math.sqrt(Math.log(totalTries + 2) / (tries + 1)) + (bonus == null ? 0 : bonus[i]);
            if (score > bestScore) {
                bestScore = score;
                best = i;
            }
        }
        return best;
    }

    /** Records how a plan went (result from 0 = bad to 1 = great). */
    public static void planResult(String key, int n, int plan, double result) {
        double[] v = get(key, n * 2);
        v[plan * 2] += 1;
        v[plan * 2 + 1] += Math.max(0, Math.min(1, result));
    }

    /** Average result of a plan (0.5 if never tried) and how many (faded) tries it has. */
    public static double[] planStats(String key, int n, int plan) {
        double[] v = get(key, n * 2);
        return new double[]{(v[plan * 2 + 1] + 0.5) / (v[plan * 2] + 1), v[plan * 2]};
    }
}
