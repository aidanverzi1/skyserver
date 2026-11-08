package com.aidan.skyserver.bots.record;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPOutputStream;

/**
 * Saves recordings as gzipped JSON in config/skyserver-recordings/, on a background thread so the
 * server never waits on the disk. Keeps the newest files up to a count and size limit.
 * Plain Java (no Minecraft classes).
 *
 * File layout (columns listed once, then one array per frame):
 * {"v":1,"game":"bedwars","map":"ember","mode":"trios","start":"2026-11-04T18:22:10","part":1,
 *  "player":"uuid","name":"Aidan",
 *  "cols":["t","x","y","z","yaw","pitch","f","s","flags","slot","item","hp","tgt","tdx","tdy","tdz","thp"],
 *  "frames":[[0,12.5,100,4.25,-91.3,4.2,1,0,3,0,1,20,-1,0,0,0,0], ...],
 *  "events":[[143,"HIT",812,6], ...]}
 */
public final class RecordingWriter {
    public static final Path DIR = Path.of("config", "skyserver-recordings");
    /** Most recording files kept (oldest deleted first). */
    public static final int KEEP_FILES = 500;
    /** Most space used by recordings, in megabytes (oldest deleted first). */
    public static final long KEEP_MB = 300;
    /** Recordings with fewer kept frames than this (3 seconds) aren't worth saving. */
    public static final int MIN_FRAMES = 60;

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final String[] COLS = {"t", "x", "y", "z", "yaw", "pitch", "f", "s", "flags", "slot", "item", "hp", "tgt", "tdx", "tdy", "tdz", "thp"};
    private static ExecutorService pool = newPool();

    private RecordingWriter() {}

    private static ExecutorService newPool() {
        return Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "SkyServer recording writer");
            t.setDaemon(true);
            return t;
        });
    }

    /** Saves a finished recording in the background. Returns false if it was too short to keep. */
    public static boolean writeAsync(RecordingSession s, java.util.function.Consumer<String> errorLog) {
        if (s.keptFrames() < MIN_FRAMES) return false;
        if (pool.isShutdown()) pool = newPool();
        // Copy the lists so the server can keep going while the file is written.
        List<FrameSample> frames = new ArrayList<>(s.frames);
        List<CombatEvent> events = new ArrayList<>(s.events);
        pool.submit(() -> {
            try {
                write(s, frames, events);
                prune();
            } catch (Exception e) {
                if (errorLog != null) errorLog.accept("Couldn't save a recording for " + s.name + ": " + e);
            }
        });
        return true;
    }

    /** Finishes writing everything queued (server stopping). */
    public static void shutdown() {
        pool.shutdown();
        try {
            pool.awaitTermination(15, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    private static void write(RecordingSession s, List<FrameSample> frames, List<CombatEvent> events) throws IOException {
        Files.createDirectories(DIR);
        String file = s.game + "-" + LocalDateTime.now().format(STAMP) + "-" + safe(s.name) + (s.part > 1 ? "-p" + s.part : "") + ".json.gz";
        Path out = DIR.resolve(file);
        Path tmp = DIR.resolve(file + ".tmp");
        try (Writer w = new OutputStreamWriter(new GZIPOutputStream(Files.newOutputStream(tmp), 1 << 16), StandardCharsets.UTF_8)) {
            StringBuilder sb = new StringBuilder(1 << 16);
            sb.append("{\"v\":1");
            str(sb, "game", s.game);
            str(sb, "map", s.map);
            str(sb, "mode", s.mode);
            str(sb, "start", s.started);
            sb.append(",\"part\":").append(s.part);
            str(sb, "player", s.player.toString());
            str(sb, "name", s.name);
            sb.append(",\"cols\":[");
            for (int i = 0; i < COLS.length; i++) sb.append(i == 0 ? "" : ",").append('"').append(COLS[i]).append('"');
            sb.append("],\"frames\":[");
            for (int i = 0; i < frames.size(); i++) {
                FrameSample f = frames.get(i);
                sb.append(i == 0 ? "[" : ",\n[");
                sb.append(f.tick());
                num(sb, f.x(), 100);
                num(sb, f.y(), 100);
                num(sb, f.z(), 100);
                num(sb, f.yaw(), 10);
                num(sb, f.pitch(), 10);
                num(sb, f.forward(), 100);
                num(sb, f.strafe(), 100);
                sb.append(',').append(f.flags()).append(',').append(f.slot()).append(',').append(f.item());
                num(sb, f.health(), 10);
                sb.append(',').append(f.targetId());
                num(sb, f.tdx(), 100);
                num(sb, f.tdy(), 100);
                num(sb, f.tdz(), 100);
                num(sb, f.targetHealth(), 10);
                sb.append(']');
                if (sb.length() > 60_000) {
                    w.write(sb.toString());
                    sb.setLength(0);
                }
            }
            sb.append("],\"events\":[");
            for (int i = 0; i < events.size(); i++) {
                CombatEvent e = events.get(i);
                sb.append(i == 0 ? "[" : ",\n[").append(e.tick()).append(",\"").append(e.kind().name()).append("\",").append(e.otherId());
                num(sb, e.value(), 100);
                sb.append(']');
            }
            sb.append("]}\n");
            w.write(sb.toString());
        }
        Files.move(tmp, out, StandardCopyOption.REPLACE_EXISTING);
    }

    /** Deletes the oldest recordings beyond the file count and size limits. */
    private static void prune() throws IOException {
        List<Path> all = list();
        all.sort(Comparator.comparingLong(RecordingWriter::modified));
        long total = 0;
        for (Path p : all) total += size(p);
        long limit = KEEP_MB * 1024 * 1024;
        while (!all.isEmpty() && (all.size() > KEEP_FILES || total > limit)) {
            Path oldest = all.remove(0);
            total -= size(oldest);
            Files.deleteIfExists(oldest);
        }
    }

    /** All finished recordings. */
    public static List<Path> list() {
        List<Path> out = new ArrayList<>();
        if (!Files.isDirectory(DIR)) return out;
        try (var s = Files.list(DIR)) {
            s.filter(p -> p.getFileName().toString().endsWith(".json.gz")).forEach(out::add);
        } catch (IOException ignored) {
        }
        return out;
    }

    /** {file count, total bytes} for /record status. */
    public static long[] stats() {
        long n = 0, bytes = 0;
        for (Path p : list()) {
            n++;
            bytes += size(p);
        }
        return new long[]{n, bytes};
    }

    private static long size(Path p) {
        try {
            return Files.size(p);
        } catch (IOException e) {
            return 0;
        }
    }

    private static long modified(Path p) {
        try {
            return Files.getLastModifiedTime(p).toMillis();
        } catch (IOException e) {
            return 0;
        }
    }

    private static String safe(String name) {
        return name.replaceAll("[^A-Za-z0-9_]", "_");
    }

    private static void str(StringBuilder sb, String key, String value) {
        sb.append(",\"").append(key).append("\":\"");
        String v = value == null ? "" : value;
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            if (c == '"' || c == '\\') sb.append('\\').append(c);
            else if (c < 0x20) sb.append(' ');
            else sb.append(c);
        }
        sb.append('"');
    }

    /** Appends ",value" rounded to 1/scale, without a trailing ".0" on whole numbers. */
    static void num(StringBuilder sb, double v, int scale) {
        sb.append(',');
        if (Double.isNaN(v) || Double.isInfinite(v)) {
            sb.append('0');
            return;
        }
        long r = Math.round(v * scale);
        if (r % scale == 0) {
            sb.append(r / scale);
            return;
        }
        if (r < 0) {
            sb.append('-');
            r = -r;
        }
        long whole = r / scale, frac = r % scale;
        sb.append(whole).append('.');
        String fs = Long.toString(frac);
        for (int i = fs.length(); i < Integer.toString(scale).length() - 1; i++) sb.append('0');
        // trim trailing zeros
        int end = fs.length();
        while (end > 1 && fs.charAt(end - 1) == '0') end--;
        sb.append(fs, 0, end);
    }
}
