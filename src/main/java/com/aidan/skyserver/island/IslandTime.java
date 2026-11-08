package com.aidan.skyserver.island;

import com.aidan.skyserver.Skyserver;
import com.aidan.skyserver.bots.BotPlayer;
import com.aidan.skyserver.lobby.Lobby;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.core.Holder;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundSetTimePacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.clock.ClockNetworkState;
import net.minecraft.world.clock.WorldClock;
import net.minecraft.world.level.GameType;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Sleeping on an island only brings morning to that island.
 *
 * All islands share one dimension (one clock), so vanilla's "everyone sleeps -> morning" is
 * turned off (gamerule players_sleeping_percentage 101). When every player on an island is asleep,
 * that island goes into a "morning hold": its sky shows early morning (frozen) until the real
 * clock reaches morning too, then it follows the normal clock again. Other islands keep the
 * normal night. Players can sleep whenever vanilla allows it (at night).
 *
 * The sky is changed in the time packet on its way to each player (IslandTimeMixin), based on the
 * island they're standing on. Only the sky changes: crops, generators etc. use the real clock.
 * Island.timeOffset != 0 means the island is in its morning hold.
 */
public final class IslandTime {
    private static final int DAY = 24_000, MORNING = 1_000, DAY_END = 12_000;
    private static final ThreadLocal<Boolean> BYPASS = ThreadLocal.withInitial(() -> false);
    /** Per player: the last real (unchanged) time packet, to resend right after an island sleeps. */
    private static final Map<UUID, ClientboundSetTimePacket> LAST_PACKET = new ConcurrentHashMap<>();

    private IslandTime() {}

    public static void init() {
        ServerLifecycleEvents.SERVER_STARTED.register(s -> {
            try {
                s.getCommands().performPrefixedCommand(s.createCommandSourceStack().withSuppressedOutput(), "gamerule players_sleeping_percentage 101");
            } catch (Throwable t) {
                Skyserver.LOGGER.warn("Couldn't set players_sleeping_percentage to 101 ({}). Run it yourself: /gamerule players_sleeping_percentage 101", t.toString());
            }
        });
        net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents.DISCONNECT.register((h, s) -> {
            if (h.getPlayer() != null) LAST_PACKET.remove(h.getPlayer().getUUID());
        });
    }

    /** Called by IslandTimeMixin for every packet sent to a player. True = replaced (don't send the original). */
    public static boolean intercept(Object listener, Packet<?> packet) {
        if (BYPASS.get() || !(packet instanceof ClientboundSetTimePacket tp) || !(listener instanceof ServerGamePacketListenerImpl g)) return false;
        ServerPlayer p = g.player;
        if (p == null || p instanceof BotPlayer) return false;
        LAST_PACKET.put(p.getUUID(), tp);
        if (!Islands.isIsland(p.level())) return false;
        Islands.Island at = Islands.islandAt(p.getX(), p.getZ());
        if (at == null || at.timeOffset == 0) return false;
        Iterator<ClockNetworkState> it = tp.clockUpdates().values().iterator();
        if (!it.hasNext()) return false;
        long t = Math.floorMod(it.next().totalTicks(), DAY);
        if (t >= MORNING && t < DAY_END) {
            // The real clock has reached morning: the hold ends, the island follows the normal clock again.
            at.timeOffset = 0;
            Islands.save();
            return false;
        }
        BYPASS.set(true);
        try {
            g.send(morning(tp));
        } finally {
            BYPASS.set(false);
        }
        return true;
    }

    /** The same packet with every clock moved forward to early morning and paused there. */
    private static ClientboundSetTimePacket morning(ClientboundSetTimePacket tp) {
        Map<Holder<WorldClock>, ClockNetworkState> moved = new LinkedHashMap<>();
        tp.clockUpdates().forEach((k, v) -> {
            long add = Math.floorMod(MORNING - v.totalTicks(), DAY);
            moved.put(k, new ClockNetworkState(v.totalTicks() + add, 0f, 0f));
        });
        return new ClientboundSetTimePacket(tp.gameTime(), moved);
    }

    /** Sends a player the time again (after their island's hold changed). */
    static void refresh(ServerPlayer p) {
        ClientboundSetTimePacket last = LAST_PACKET.get(p.getUUID());
        if (last != null) p.connection.send(last); // goes through intercept, which applies the hold
    }

    /** Every second (from Islands.tick): islands where everyone is asleep get their morning. */
    static void tick(ServerLevel level) {
        Map<Islands.Island, List<ServerPlayer>> by = new HashMap<>();
        for (ServerPlayer p : level.players()) {
            if (p instanceof BotPlayer || p.gameMode.getGameModeForPlayer() == GameType.SPECTATOR) continue;
            Islands.Island at = Islands.islandAt(p.getX(), p.getZ());
            if (at != null) by.computeIfAbsent(at, k -> new ArrayList<>()).add(p);
        }
        by.forEach((is, players) -> {
            for (ServerPlayer p : players) if (!p.isSleeping() || !p.isSleepingLongEnough()) return;
            is.timeOffset = 1;
            Islands.save();
            for (ServerPlayer p : players) {
                p.stopSleeping();
                refresh(p);
                Lobby.msg(p, "§eGood morning! §7(Only your island's sky changed - other islands keep their own time.)");
            }
        });
    }
}
