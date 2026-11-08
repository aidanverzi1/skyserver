package com.aidan.skyserver.bots;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Match recorder: writes what every bot (and every real player) is doing once a second during a
 * Gold Wars match, plus kills and gold broken, to config/skyserver-bot-logs/gw-DATE.txt.
 * If a bot plays really well (or really badly), send that file to the mod author and its exact
 * decisions can be copied (or fixed). The newest 30 matches are kept.
 *
 * Line format: time  name  team  class  difficulty  x y z  health  | what it's doing
 */
public final class BotRecorder {
    private static final Path DIR = Path.of("config", "skyserver-bot-logs");
    private static final DateTimeFormatter NAME = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");
    private static final int KEEP = 30;
    private static StringBuilder buf;
    private static Path file;
    private static long startMs;

    private BotRecorder() {}

    public static synchronized void start(String map) {
        try {
            Files.createDirectories(DIR);
            file = DIR.resolve("gw-" + LocalDateTime.now().format(NAME) + ".txt");
            buf = new StringBuilder("Gold Wars match on " + map + ", started " + LocalDateTime.now() + System.lineSeparator()
                    + "time  name  team  class  difficulty  x y z  health  | doing" + System.lineSeparator());
            startMs = System.currentTimeMillis();
        } catch (Exception e) {
            buf = null;
        }
    }

    private static String t() {
        long s = (System.currentTimeMillis() - startMs) / 1000;
        return String.format("%02d:%02d", s / 60, s % 60);
    }

    public static synchronized void unit(String name, String team, String cls, String diff, double x, double y, double z, float health, String doing) {
        if (buf == null) return;
        buf.append(String.format("%s  %-16s %-4s %-7s %-6s %6.1f %5.1f %6.1f  %4.1f  | %s%n", t(), name, team, cls, diff, x, y, z, health, doing));
        if (buf.length() > 64_000) flush();
    }

    public static synchronized void event(String text) {
        if (buf == null) return;
        buf.append(t()).append("  ** ").append(text).append(System.lineSeparator());
    }

    private static void flush() {
        if (buf == null || file == null) return;
        try {
            Files.writeString(file, buf.toString(), StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        } catch (Exception ignored) {
        }
        buf.setLength(0);
    }

    public static synchronized void end(String result) {
        if (buf == null) return;
        event("MATCH OVER: " + result);
        flush();
        buf = null;
        try (var s = Files.list(DIR)) {
            List<Path> all = new ArrayList<>(s.filter(p -> p.getFileName().toString().startsWith("gw-")).sorted().toList());
            while (all.size() > KEEP) Files.deleteIfExists(all.remove(0));
        } catch (Exception ignored) {
        }
    }

    public static boolean recording() {
        return buf != null;
    }
}
