package com.aidan.skyserver.island;

import com.aidan.skyserver.Skyserver;
import com.aidan.skyserver.bots.BotPlayer;
import com.aidan.skyserver.lobby.Lobby;
import net.fabricmc.fabric.api.entity.event.v1.EntitySleepEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.core.Holder;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundSetTimePacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.clock.ClockNetworkState;
import net.minecraft.world.clock.WorldClock;
import net.minecraft.world.level.GameType;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Every Sky Island has its own time of day. All islands are in one dimension (one shared clock),
 * so each island keeps an offset, and the server shows players the time of the island they're
 * standing on (IslandTimeMixin swaps the time packet on its way out).
 *
 *  - Vanilla's "everyone sleeps -> morning" is turned off (gamerule players_sleeping_percentage
 *    101), so sleeping never changes the time for anyone else.
 *  - When every player on an island is asleep, only that island skips to morning.
 *    5.17: to WAKE_TIME (1000, mid-morning - the sun is up and it's light), not to 0 (sunrise, still dim).
 *  - You can sleep whenever it's night on the island you're on.
 * Only the sky changes: crops, generators and everything else run on the shared clock.
 */
public final class IslandTime {
    private static final int DAY = 24_000, NIGHT_START = 12_542, NIGHT_END = 23_460;
    /** 5.17: the time of day an island wakes up at (0 = sunrise, 1000 = what "/time set day" gives, 6000 = noon). */
    private static final int WAKE_TIME = 1_000;
    private static final ThreadLocal<Boolean> BYPASS = ThreadLocal.withInitial(() -> false);
    /** Per player: {last clock ticks the server sent, server tick when seen}. */
    private static final Map<UUID, long[]> LAST = new ConcurrentHashMap<>();
    /** Per player: the last real (unchanged) time packet, to resend right after an island sleeps. */
    private static final Map<UUID, ClientboundSetTimePacket> LAST_PACKET = new ConcurrentHashMap<>();
    private static MinecraftServer server;

    private IslandTime() {}

    public static void init() {
        ServerLifecycleEvents.SERVER_STARTED.register(s -> {
            server = s;
            // Vanilla never skips the night for everyone (islands handle their own nights).
            try {
                s.getCommands().performPrefixedCommand(s.createCommandSourceStack().withSuppressedOutput(), "gamerule players_sleeping_percentage 101");
            } catch (Throwable t) {
                Skyserver.LOGGER.warn("Couldn't set players_sleeping_percentage to 101 ({}). Run it yourself: /gamerule players_sleeping_percentage 101", t.toString());
            }
        });
        net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents.DISCONNECT.register((h, s) -> {
            if (h.getPlayer() != null) forget(h.getPlayer().getUUID());
        });
        EntitySleepEvents.ALLOW_SLEEPING.register((player, pos) -> {
            if (!(player instanceof ServerPlayer sp) || !Islands.isIsland(sp.level())) return null;
            Long t = timeOfDay(sp);
            if (t == null) return null;
            return isNight(t) ? null : net.minecraft.world.entity.player.Player.BedSleepingProblem.OTHER_PROBLEM;
        });
    }

    private static boolean isNight(long t) {
        return t >= NIGHT_START && t < NIGHT_END;
    }

    /** Called by IslandTimeMixin for every packet sent to a player. True = replaced (don't send the original). */
    public static boolean intercept(Object listener, Packet<?> packet) {
        if (BYPASS.get() || !(packet instanceof ClientboundSetTimePacket tp) || !(listener instanceof ServerGamePacketListenerImpl g)) return false;
        ServerPlayer p = g.player;
        if (p == null || p instanceof BotPlayer) return false;
        Iterator<ClockNetworkState> it = tp.clockUpdates().values().iterator();
        if (it.hasNext()) {
            LAST.put(p.getUUID(), new long[]{it.next().totalTicks(), server == null ? 0 : server.getTickCount()});
            LAST_PACKET.put(p.getUUID(), tp);
        }
        if (!Islands.isIsland(p.level())) return false;
        Islands.Island at = Islands.islandAt(p.getX(), p.getZ());
        long off = at == null ? 0 : at.timeOffset;
        if (off == 0) return false;
        BYPASS.set(true);
        try {
            g.send(shifted(tp, off));
        } finally {
            BYPASS.set(false);
        }
        return true;
    }

    private static ClientboundSetTimePacket shifted(ClientboundSetTimePacket tp, long off) {
        Map<Holder<WorldClock>, ClockNetworkState> moved = new LinkedHashMap<>();
        tp.clockUpdates().forEach((k, v) -> moved.put(k, new ClockNetworkState(v.totalTicks() + off, v.partialTick(), v.rate())));
        return new ClientboundSetTimePacket(tp.gameTime(), moved);
    }

    /** Sends a player the time again (after their island's offset changed). */
    static void refresh(ServerPlayer p) {
        ClientboundSetTimePacket last = LAST_PACKET.get(p.getUUID());
        if (last != null) p.connection.send(last); // goes through intercept, which applies the new offset
    }

    /** The time of day (0..23999) on the island this player is standing on, or null if unknown yet. */
    static Long timeOfDay(ServerPlayer p) {
        long[] l = LAST.get(p.getUUID());
        if (l == null || server == null) return null;
        Islands.Island at = Islands.islandAt(p.getX(), p.getZ());
        long now = l[0] + (server.getTickCount() - l[1]) + (at == null ? 0 : at.timeOffset);
        return Math.floorMod(now, (long) DAY);
    }

    /** Every second (from Islands.tick): islands where everyone is asleep skip to morning. */
    static void tick(ServerLevel level) {
        Map<Islands.Island, List<ServerPlayer>> by = new HashMap<>();
        for (ServerPlayer p : level.players()) {
            if (p instanceof BotPlayer || p.gameMode.getGameModeForPlayer() == GameType.SPECTATOR) continue;
            Islands.Island at = Islands.islandAt(p.getX(), p.getZ());
            if (at != null) by.computeIfAbsent(at, k -> new ArrayList<>()).add(p);
        }
        by.forEach((is, players) -> {
            for (ServerPlayer p : players) if (!p.isSleeping() || !p.isSleepingLongEnough()) return;
            Long t = timeOfDay(players.get(0));
            if (t == null) return;
            // 5.17: on to WAKE_TIME (light outside), not just to sunrise.
            is.timeOffset = Math.floorMod(is.timeOffset + (DAY - t) + WAKE_TIME, DAY);
            Islands.save();
            for (ServerPlayer p : players) {
                p.stopSleeping();
                refresh(p);
                Lobby.msg(p, "§eGood morning! §7(Only your island's sky changed - other islands keep their own time.)");
            }
        });
    }

    static void forget(UUID id) {
        LAST.remove(id);
        LAST_PACKET.remove(id);
    }
}
