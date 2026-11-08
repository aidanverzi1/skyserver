package com.aidan.skyserver.goldwars;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.*;

/** Read-only view of Gold Wars for the bot code (which lives in another package). */
public final class GoldWarsBots {
    private GoldWarsBots() {}

    public static boolean inMatch() {
        return GoldWarsGame.inMatchNow();
    }

    public static boolean isBuildPhase() {
        return GoldWarsGame.inMatchNow() && !GoldWarsGame.isFighting();
    }

    public static GwTeam team(UUID id) {
        return GoldWarsGame.teamOf(id);
    }

    public static GwClass cls(UUID id) {
        return GoldWarsGame.classOf(id);
    }

    public static boolean alive(UUID id) {
        return GoldWarsGame.isAlive(id);
    }

    public static Set<BlockPos> gold(GwTeam team) {
        return GoldWarsGame.goldOf(team);
    }

    public static ServerLevel level() {
        return GoldWarsGame.arenaLevel();
    }

    public static boolean canEdit(BlockPos pos) {
        return GoldWarsGame.canEdit(pos);
    }

    public static boolean isGold(BlockPos pos) {
        return GoldWarsGame.isGold(pos);
    }

    public static List<ServerPlayer> alivePlayers() {
        return GoldWarsGame.alivePlayers();
    }

    public static BlockPos spawn(GwTeam team) {
        return GoldWarsMap.spawn(team);
    }

    /** A team's two vaults (north, south), each as one of its middle gold blocks. */
    public static List<BlockPos> vaults(GwTeam team) {
        return GoldWarsMap.vaults(team);
    }

    public static int floorY() {
        return GoldWarsMap.FLOOR_Y;
    }

    public static int voidY() {
        return GoldWarsMap.VOID_Y;
    }

    public static boolean inSpawnZone(BlockPos pos) {
        return GoldWarsMap.inSpawnZone(pos);
    }

    /** z of the two center routes on the current map. */
    public static int centerLaneZ() {
        return MapDesigns.centerLaneZ(GoldWarsMap.current().id);
    }

    /** z of the two flank routes on the current map. */
    public static int flankLaneZ() {
        return MapDesigns.flankLaneZ(GoldWarsMap.current().id);
    }

    public static int timerTicks() {
        return GoldWarsGame.timerTicks();
    }

    public static boolean isOvertime() {
        return GoldWarsGame.isOvertime();
    }

    public static List<UUID> members(GwTeam team) {
        return GoldWarsGame.members(team);
    }

    public static String mapId() {
        return GoldWarsMap.current().id;
    }

    public static boolean isCaves() {
        return GoldWarsMap.current() == GoldWarsMap.Layout.CAVES;
    }
}
