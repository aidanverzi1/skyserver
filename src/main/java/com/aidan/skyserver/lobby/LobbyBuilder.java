package com.aidan.skyserver.lobby;

import com.aidan.skyserver.Skyserver;
import com.aidan.skyserver.goldwars.GoldWarsMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Builds the lobby into its dimension the first time the server starts (and again when the
 * design version changes). It builds a few chunks every tick, starting at spawn and working
 * outward, so the server keeps running while it builds (about 3 million blocks in total).
 */
public final class LobbyBuilder {
    /** Bump this when the lobby design changes to rebuild it on the next start. */
    public static final int VERSION = 8;
    private static final Path MARKER = Path.of("config", "skyserver", "lobby-built.txt");
    private static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;
    private static final long BUDGET_NANOS = 25_000_000L; // 25 ms of building per tick

    private static ArrayDeque<int[]> queue;
    private static int total = 0;
    private static long nextReport = 0;

    private LobbyBuilder() {}

    public static boolean isBuilding() {
        return queue != null && !queue.isEmpty();
    }

    public static int percentDone() {
        return total == 0 ? 100 : 100 - (queue == null ? 0 : queue.size() * 100 / total);
    }

    public static boolean isBuilt() {
        try {
            return Files.exists(MARKER) && Files.readString(MARKER).trim().equals("built " + VERSION);
        } catch (Exception e) {
            return false;
        }
    }

    /** Starts building (call once at startup, or from /lobbyadmin rebuild). */
    public static void start(ServerLevel level) {
        int h = LobbyDesign.HALF + 2;
        List<int[]> chunks = new ArrayList<>();
        for (int cx = Math.floorDiv(-h, 16); cx <= Math.floorDiv(h, 16); cx++)
            for (int cz = Math.floorDiv(-h, 16); cz <= Math.floorDiv(h, 16); cz++) chunks.add(new int[]{cx, cz});
        // Spawn first, then outward.
        int sx = (int) LobbyDesign.SPAWN[0] >> 4, sz = (int) LobbyDesign.SPAWN[2] >> 4;
        chunks.sort(Comparator.comparingInt(c -> (c[0] - sx) * (c[0] - sx) + (c[1] - sz) * (c[1] - sz)));
        queue = new ArrayDeque<>(chunks);
        total = chunks.size();
        Skyserver.LOGGER.info("Building the lobby ({} chunks). This takes a minute or two the first time.", total);
        // The few chunks around spawn right away, so nobody falls while it builds.
        for (int i = 0; i < 9 && !queue.isEmpty(); i++) buildChunk(level, queue.poll());
    }

    public static void tick(ServerLevel level) {
        if (level == null) return;
        if (!isBuilding()) return;
        long start = System.nanoTime();
        while (!queue.isEmpty() && System.nanoTime() - start < BUDGET_NANOS) buildChunk(level, queue.poll());
        if (System.currentTimeMillis() > nextReport) {
            nextReport = System.currentTimeMillis() + 10_000;
            Skyserver.LOGGER.info("Lobby: {}% built", percentDone());
        }
        if (queue.isEmpty()) {
            try {
                Files.createDirectories(MARKER.getParent());
                Files.writeString(MARKER, "built " + VERSION);
            } catch (Exception e) {
                Skyserver.LOGGER.warn("Couldn't write {}: {}", MARKER, e.toString());
            }
            Skyserver.LOGGER.info("Lobby finished building.");
            Lobby.lobbyBuilt();
        }
    }

    private static void buildChunk(ServerLevel level, int[] c) {
        // A temporary ticket (it expires by itself) keeps the chunk loaded until its lighting is done.
        level.getChunkSource().addTicketWithRadius(net.minecraft.server.level.TicketType.PORTAL, new net.minecraft.world.level.ChunkPos(c[0], c[1]), 1);
        LobbyCanvas canvas = new LobbyCanvas(c[0] * 16, c[1] * 16, c[0] * 16 + 15, c[1] * 16 + 15);
        LobbyDesign.paint(canvas);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        canvas.forEach((x, y, z, text) -> {
            pos.set(x, y, z);
            if (text.equals("air")) {
                if (!level.getBlockState(pos).isAir()) level.setBlock(pos, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), FLAGS);
                return;
            }
            BlockState state = GoldWarsMap.toState(text);
            level.setBlock(pos, state, FLAGS);
        });
    }
}
