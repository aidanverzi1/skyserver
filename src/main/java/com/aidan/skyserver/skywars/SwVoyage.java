package com.aidan.skyserver.skywars;

import com.aidan.skyserver.lobby.CosmeticsPlus;
import com.aidan.skyserver.lobby.Lobby;
import com.aidan.skyserver.lobby.Voyage;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.*;

/**
 * 5.13: Skywars extras that run next to SkywarsGame without changing it.
 *  - Voyage twists, fixed when a match starts: Legendary Skies (every chest refilled with the supply
 *    drop's loot), Shrinking Sky (a storm wall closes from the outer islands to 20 blocks across
 *    between 3:00 and 6:00; 1 heart a second outside it).
 *  - Skywars cage cosmetics: while you wait, your glass cage is rebuilt in your cage's blocks.
 */
public final class SwVoyage {
    private SwVoyage() {}

    public static final int SHRINK_START = 3 * 60 * 20, SHRINK_TICKS = 3 * 60 * 20, END_HALF = 10;

    private static MinecraftServer server;
    private static int match = -1;
    private static long matchTicks = 0;
    private static boolean shrink;
    private static double startHalf = 60;

    public static void init() {
        ServerLifecycleEvents.SERVER_STARTED.register(s -> server = s);
        ServerTickEvents.END_SERVER_TICK.register(s -> com.aidan.skyserver.Safe.run("skywars voyage", SwVoyage::tick));
    }

    private static ServerLevel arena() {
        return server == null ? null : server.getLevel(Lobby.SKYWARS);
    }

    private static void tick() {
        if (server == null) return;
        ServerLevel arena = arena();
        if (arena == null) return;
        if (SkywarsGame.isPlaying()) {
            if (SkywarsGame.matchNumber() != match) start(arena);
            matchTicks++;
            if (shrink) storm(arena);
        } else if (server.getTickCount() % 20 == 9) {
            cages(arena);
        }
    }

    private static void start(ServerLevel arena) {
        match = SkywarsGame.matchNumber();
        matchTicks = 0;
        shrink = Voyage.is(Voyage.Twist.SHRINKING_SKY);
        Voyage.Twist t = Voyage.twist();
        if (t != null && t.mode == Voyage.Mode.SKYWARS) tell("§6⚓ Wren's Voyage: §e" + t.display + " §7- " + t.about);
        if (Voyage.is(Voyage.Twist.LEGENDARY_SKIES)) {
            List<BlockPos> chests = new ArrayList<>(SkywarsMap.allIslandChests());
            chests.addAll(SkywarsMap.midChests());
            chests.addAll(SkywarsMap.centerChests());
            for (BlockPos pos : chests)
                if (arena.getBlockEntity(pos) instanceof Container c) {
                    c.clearContent();
                    SkywarsLoot.fill(c, SkywarsLoot.Table.SUPPLY);
                }
        }
        double far = 30;
        for (int i = 0; i < SkywarsMap.SPAWN_COUNT; i++) {
            BlockPos s = SkywarsMap.spawn(i);
            far = Math.max(far, Math.max(Math.abs(s.getX() - SkywarsMap.CENTER_X), Math.abs(s.getZ() - SkywarsMap.CENTER_Z)));
        }
        startHalf = far + 6;
    }

    private static double half() {
        double f = Math.max(0, Math.min(1, (matchTicks - SHRINK_START) / (double) SHRINK_TICKS));
        return startHalf - (startHalf - END_HALF) * f;
    }

    private static void storm(ServerLevel arena) {
        if (matchTicks == SHRINK_START - 30 * 20) tell("§cThe storm wall starts closing in 30 seconds! Head for the middle.");
        if (matchTicks == SHRINK_START) tell("§4§lTHE STORM WALL IS CLOSING IN!");
        if (matchTicks < SHRINK_START || matchTicks % 10 != 0) return;
        double h = half();
        for (ServerPlayer p : SkywarsGame.alivePlayers()) {
            double dx = Math.abs(p.getX() - SkywarsMap.CENTER_X - 0.5), dz = Math.abs(p.getZ() - SkywarsMap.CENTER_Z - 0.5);
            if ((dx > h || dz > h) && matchTicks % 20 == 0) {
                p.hurtServer(arena, arena.damageSources().magic(), 2f); // a heart a second
                if (!(p instanceof com.aidan.skyserver.bots.BotPlayer))
                    com.aidan.skyserver.lobby.Announce.actionBar(p, "§c§lYou're outside the storm wall! §7Get to the middle!");
            }
            double edgeX = Math.signum(p.getX() - SkywarsMap.CENTER_X) * h + SkywarsMap.CENTER_X;
            double edgeZ = Math.signum(p.getZ() - SkywarsMap.CENTER_Z) * h + SkywarsMap.CENTER_Z;
            if (Math.abs(dx - h) < 12)
                for (int k = -6; k <= 6; k += 2) arena.sendParticles(p, ParticleTypes.CLOUD, true, false, edgeX, p.getY() + 1, p.getZ() + k, 2, 0, 1.2, 0.3, 0);
            if (Math.abs(dz - h) < 12)
                for (int k = -6; k <= 6; k += 2) arena.sendParticles(p, ParticleTypes.CLOUD, true, false, p.getX() + k, p.getY() + 1, edgeZ, 2, 0.3, 1.2, 0, 0);
        }
    }

    /** Waiting players with a cage cosmetic: swap their glass cage for the cage's blocks. */
    private static void cages(ServerLevel arena) {
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (p instanceof com.aidan.skyserver.bots.BotPlayer || !SkywarsGame.isInGame(p.getUUID())) continue;
            BlockState[] cage = CosmeticsPlus.cageBlocks(CosmeticsPlus.cage(p.getUUID()));
            BlockPos s = SkywarsGame.ownSpawn(p.getUUID());
            if (cage == null || s == null) continue;
            if (!arena.getBlockState(s.offset(1, 0, 0)).is(Blocks.GLASS) && !arena.getBlockState(s.offset(0, 2, 0)).is(Blocks.GLASS)) continue;
            for (int dx = -1; dx <= 1; dx++)
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx != 0 || dz != 0) {
                        swap(arena, s.offset(dx, 0, dz), cage[0]);
                        swap(arena, s.offset(dx, 1, dz), cage[0]);
                    }
                    swap(arena, s.offset(dx, 2, dz), cage[1]);
                }
        }
    }

    private static void swap(ServerLevel arena, BlockPos pos, BlockState to) {
        if (arena.getBlockState(pos).is(Blocks.GLASS)) arena.setBlock(pos, to, 2 | 16);
    }

    private static void tell(String text) {
        for (ServerPlayer p : server.getPlayerList().getPlayers())
            if (!(p instanceof com.aidan.skyserver.bots.BotPlayer) && SkywarsGame.isInGame(p.getUUID())) p.sendSystemMessage(Component.literal("§b[Skywars] §r" + text));
    }
}
