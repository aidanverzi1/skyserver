package com.aidan.skyserver.lobby;

import com.aidan.skyserver.Skyserver;
import com.google.gson.JsonObject;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Automatic backups. Every few hours (6 by default) the server saves everything, pauses world saving
 * for a moment, and zips the whole world folder plus the config folder (SkyServer's money, keys,
 * skills, islands and quests, EconomyCraft's settings) into backups/skyserver-DATE.zip next to the
 * server jar. The zipping happens in the background so the game doesn't freeze. Only the newest
 * few backups are kept (10 by default) so the disk doesn't fill up.
 *
 * /ss backup makes one right now. Settings: config/skyserver/backups.json
 *   enabled (true/false), everyHours, keep, folder.
 * To restore: stop the server, rename the world folder (as a spare), unzip a backup into the server
 * folder (it contains world/ and config/), start the server.
 */
public final class Backups {
    private static final DateTimeFormatter NAME = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm");

    private static boolean loaded = false, enabled = true;
    private static int everyHours = 6, keep = 10;
    private static String folder = "backups";
    private static long last = 0;
    private static volatile boolean running = false;
    private static volatile String lastResult = "none yet";

    private Backups() {}

    private static void load() {
        if (loaded) return;
        loaded = true;
        JsonObject o = Stacks.readFile("backups.json");
        try {
            if (o.has("enabled")) enabled = o.get("enabled").getAsBoolean();
            if (o.has("everyHours")) everyHours = Math.max(1, o.get("everyHours").getAsInt());
            if (o.has("keep")) keep = Math.max(1, o.get("keep").getAsInt());
            if (o.has("folder")) folder = o.get("folder").getAsString();
            if (o.has("last")) last = o.get("last").getAsLong();
            if (o.has("lastResult")) lastResult = o.get("lastResult").getAsString();
        } catch (RuntimeException e) {
            Skyserver.LOGGER.warn("backups.json has a bad value, using defaults: {}", e.toString());
        }
        save();
    }

    private static void save() {
        JsonObject o = new JsonObject();
        o.addProperty("enabled", enabled);
        o.addProperty("everyHours", everyHours);
        o.addProperty("keep", keep);
        o.addProperty("folder", folder);
        o.addProperty("last", last);
        o.addProperty("lastResult", lastResult);
        Stacks.writeFile("backups.json", o);
    }

    public static void reload() {
        loaded = false;
        load();
    }

    public static String status() {
        load();
        String when = last == 0 ? "never" : ((System.currentTimeMillis() - last) / 60_000) + " min ago";
        return (enabled ? "every " + everyHours + "h, keeping " + keep : "OFF") + " §7| last: " + when + " (" + lastResult + ")" + (running ? " §e[running now]" : "");
    }

    static void tick(MinecraftServer server, long ticks) {
        if (ticks % 200 != 0) return;
        load();
        if (!enabled || running) return;
        // First backup 10 minutes after the server starts if there's never been one, then every few hours.
        long due = last == 0 ? 0 : last + everyHours * 3_600_000L;
        if (System.currentTimeMillis() >= due && ticks > 12_000) start(server, null);
    }

    /** Starts a backup. who = the admin who asked (gets told when it's done), or null. */
    public static void start(MinecraftServer server, ServerPlayer who) {
        load();
        if (running) {
            if (who != null) Lobby.msg(who, "§eA backup is already running.");
            return;
        }
        running = true;
        last = System.currentTimeMillis();
        save();
        UUID whoId = who == null ? null : who.getUUID();
        for (ServerPlayer p : server.getPlayerList().getPlayers()) if (Lobby.isStaff(p) || p == who) Lobby.msg(p, "§7[Backup] Saving the world...");
        // 1. Save everything to disk now, then pause saving so files don't change while we copy them.
        server.saveEverything(true, true, true);
        Map<ServerLevel, Boolean> wasNoSave = new HashMap<>();
        for (ServerLevel l : server.getAllLevels()) {
            wasNoSave.put(l, l.noSave);
            l.noSave = true;
        }
        Path world = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
        Path config = Path.of("config").toAbsolutePath().normalize();
        Path outDir = Path.of(folder).toAbsolutePath().normalize();
        String name = "skyserver-" + LocalDateTime.now().format(NAME) + ".zip";
        Thread t = new Thread(() -> {
            String result;
            long started = System.currentTimeMillis();
            try {
                Files.createDirectories(outDir);
                Path tmp = outDir.resolve(name + ".part");
                long files;
                try (OutputStream os = Files.newOutputStream(tmp); ZipOutputStream zip = new ZipOutputStream(os)) {
                    zip.setLevel(5);
                    files = add(zip, world, world.getFileName().toString(), outDir);
                    if (Files.isDirectory(config)) files += add(zip, config, "config", outDir);
                }
                Files.move(tmp, outDir.resolve(name), StandardCopyOption.REPLACE_EXISTING);
                prune(outDir);
                long mb = Files.size(outDir.resolve(name)) / (1024 * 1024);
                result = "OK " + name + " (" + mb + " MB, " + files + " files, " + (System.currentTimeMillis() - started) / 1000 + "s)";
                Skyserver.LOGGER.info("[SkyServer] Backup saved: {}", outDir.resolve(name));
            } catch (Throwable e) {
                result = "FAILED: " + e;
                Skyserver.LOGGER.error("[SkyServer] Backup failed", e);
            }
            String r = result;
            server.execute(() -> {
                // 2. Turn saving back on (as it was before).
                wasNoSave.forEach((l, v) -> l.noSave = v);
                lastResult = r;
                running = false;
                save();
                for (ServerPlayer p : server.getPlayerList().getPlayers())
                    if (Lobby.isStaff(p) || p.getUUID().equals(whoId)) Lobby.msg(p, (r.startsWith("OK") ? "§a" : "§c") + "[Backup] " + r);
            });
        }, "SkyServer backup");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
    }

    /** Adds a folder to the zip. Skips the lock file, the backups folder itself and files it can't read. */
    private static long add(ZipOutputStream zip, Path root, String prefix, Path skip) throws IOException {
        long[] count = {0};
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                return dir.startsWith(skip) ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                String fn = file.getFileName().toString();
                if (fn.equals("session.lock") || fn.endsWith(".tmp") || fn.endsWith(".part")) return FileVisitResult.CONTINUE;
                String entry = prefix + "/" + root.relativize(file).toString().replace('\\', '/');
                try {
                    zip.putNextEntry(new ZipEntry(entry));
                    Files.copy(file, zip);
                    zip.closeEntry();
                    count[0]++;
                } catch (IOException e) {
                    Skyserver.LOGGER.warn("[SkyServer] Backup skipped {}: {}", file, e.toString());
                    try {
                        zip.closeEntry();
                    } catch (IOException ignored) {
                    }
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                return FileVisitResult.CONTINUE;
            }
        });
        return count[0];
    }

    /** Deletes the oldest backups beyond the "keep" number. */
    private static void prune(Path dir) throws IOException {
        List<Path> zips;
        try (Stream<Path> s = Files.list(dir)) {
            zips = new ArrayList<>(s.filter(p -> p.getFileName().toString().startsWith("skyserver-") && p.getFileName().toString().endsWith(".zip")).sorted().toList());
        }
        while (zips.size() > keep) Files.deleteIfExists(zips.remove(0));
    }
}
