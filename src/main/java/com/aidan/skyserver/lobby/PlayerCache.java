package com.aidan.skyserver.lobby;

import com.aidan.skyserver.Skyserver;
import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * In-memory player data with background saving. Nothing here ever reads or writes a file on the
 * main server thread:
 *  - JOIN: the player's file is read on the background thread, then merged in on the main thread.
 *  - During play: changes only touch the in-memory PlayerProfile.
 *  - Every 5 minutes: changed profiles are saved in the background.
 *  - DISCONNECT: saved in the background, then dropped from memory.
 *  - Server stop: everything is saved and the server waits (up to 15 s) for the writes to finish.
 * All file work runs on ONE background thread, in order, so a save always finishes before a later
 * load of the same player starts (quick rejoin = no lost progress).
 */
public final class PlayerCache {
    private static final Map<UUID, PlayerProfile> CACHE = new ConcurrentHashMap<>();
    private static final List<Consumer<ServerPlayer>> ON_LOAD = new ArrayList<>();
    private static final int AUTOSAVE_TICKS = 5 * 60 * 20;
    private static final ExecutorService io = newIo();
    private static MinecraftServer server;

    private PlayerCache() {}

    private static ExecutorService newIo() {
        return Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "SkyServer-IO");
            t.setDaemon(true);
            return t;
        });
    }

    /** Runs file work on the background thread (in order with all other saves). */
    public static CompletableFuture<Void> io(Runnable task) {
        if (io.isShutdown()) {
            // Server is stopping and the background thread is done: just do it now.
            try {
                task.run();
            } catch (Throwable t) {
                Skyserver.LOGGER.warn("Save failed: {}", t.toString());
            }
            return CompletableFuture.completedFuture(null);
        }
        return CompletableFuture.runAsync(() -> {
            try {
                task.run();
            } catch (Throwable t) {
                Skyserver.LOGGER.warn("Background save failed: {}", t.toString());
            }
        }, io);
    }

    /** Something to do (on the main thread) once a player's saved data has been read in. */
    public static void onLoad(Consumer<ServerPlayer> listener) {
        ON_LOAD.add(listener);
    }

    public static void init() {
        ServerLifecycleEvents.SERVER_STARTED.register(s -> server = s);
        ServerPlayConnectionEvents.JOIN.register((handler, sender, s) -> {
            server = s;
            ServerPlayer p = handler.getPlayer();
            if (isBot(p)) return;
            load(p);
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, s) -> {
            ServerPlayer p = handler.getPlayer();
            if (p == null || isBot(p)) return;
            unload(p.getUUID());
        });
        ServerTickEvents.END_SERVER_TICK.register(s -> {
            if (s.getTickCount() % AUTOSAVE_TICKS == 0) com.aidan.skyserver.Safe.run("player autosave", PlayerCache::autosave);
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(s -> saveAllAndWait());
    }

    private static boolean isBot(ServerPlayer p) {
        return p instanceof com.aidan.skyserver.bots.BotPlayer;
    }

    private static String file(UUID id) {
        return "players/" + id + ".json";
    }

    /** The player's profile (created empty if their data is still loading; it's merged in when it arrives). */
    public static PlayerProfile get(ServerPlayer p) {
        if (isBot(p)) return new PlayerProfile(p.getUUID()); // bots keep nothing
        PlayerProfile prof = CACHE.computeIfAbsent(p.getUUID(), PlayerProfile::new);
        prof.name = p.getName().getString();
        return prof;
    }

    /** The profile if this player is online (null otherwise). */
    public static PlayerProfile ifOnline(UUID id) {
        return CACHE.get(id);
    }

    public static boolean isLoaded(UUID id) {
        PlayerProfile p = CACHE.get(id);
        return p != null && p.loaded;
    }

    private static void load(ServerPlayer player) {
        UUID id = player.getUUID();
        PlayerProfile pending = get(player);
        CompletableFuture.supplyAsync(() -> Stacks.readFile(file(id)), io).whenComplete((json, err) -> {
            if (server == null) return;
            server.execute(() -> {
                PlayerProfile cur = CACHE.get(id);
                if (cur != pending || cur.loaded) return; // they already left (unload merges it) or it's done
                if (err != null) Skyserver.LOGGER.warn("Couldn't read player data for {}: {}", player.getName().getString(), err.toString());
                PlayerProfile saved = PlayerProfile.fromJson(id, json == null || json.size() == 0 ? null : json);
                cur.absorb(saved);
                cur.loaded = true;
                ServerPlayer online = server.getPlayerList().getPlayer(id);
                if (online != null) for (Consumer<ServerPlayer> l : ON_LOAD) com.aidan.skyserver.Safe.run("profile loaded", () -> l.accept(online));
            });
        });
    }

    private static void unload(UUID id) {
        PlayerProfile prof = CACHE.remove(id);
        if (prof == null) return;
        if (prof.loaded) {
            JsonObject snap = prof.toJson();
            io(() -> Stacks.writeFile(file(id), snap));
        } else {
            // Left before their file finished loading: merge what they earned into the file.
            io(() -> {
                PlayerProfile saved = PlayerProfile.fromJson(id, Stacks.readFile(file(id)));
                prof.absorb(saved);
                Stacks.writeFile(file(id), prof.toJson());
            });
        }
    }

    private static void autosave() {
        for (PlayerProfile prof : CACHE.values()) {
            if (!prof.loaded || !prof.dirty) continue;
            prof.dirty = false;
            JsonObject snap = prof.toJson();
            UUID id = prof.id;
            io(() -> Stacks.writeFile(file(id), snap));
        }
        Levels.saveSoon();
        BattlePass.saveSoon();
    }

    private static void saveAllAndWait() {
        for (UUID id : new ArrayList<>(CACHE.keySet())) unload(id);
        Levels.saveSoon();
        BattlePass.saveSoon();
        io.shutdown();
        try {
            if (!io.awaitTermination(15, TimeUnit.SECONDS)) Skyserver.LOGGER.warn("Player data saves took longer than 15 s at shutdown.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
