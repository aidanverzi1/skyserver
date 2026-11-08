package com.aidan.skyserver.skywars;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Remembers a player's inventory, position and game mode from before they joined a game.
 * When the lobby is running, "restoring" just sends them back to the lobby (games can only be
 * joined from the lobby, and survival inventories are kept safe by the lobby).
 */
public class PlayerSnapshot {
    private final List<ItemStack> items = new ArrayList<>();
    private final ServerLevel level;
    private final double x, y, z;
    private final float yRot, xRot;
    private final GameType gameMode;

    public PlayerSnapshot(ServerPlayer player) {
        Inventory inv = player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            items.add(inv.getItem(i).copy());
        }
        this.level = (ServerLevel) player.level();
        this.x = player.getX();
        this.y = player.getY();
        this.z = player.getZ();
        this.yRot = player.getYRot();
        this.xRot = player.getXRot();
        this.gameMode = player.gameMode.getGameModeForPlayer();
    }

    public void restore(ServerPlayer player) {
        // With the lobby, everyone simply goes back to the lobby after a game.
        if (com.aidan.skyserver.lobby.Lobby.level() != null) {
            com.aidan.skyserver.lobby.Lobby.toLobby(player);
            return;
        }
        Inventory inv = player.getInventory();
        inv.clearContent();
        for (int i = 0; i < items.size() && i < inv.getContainerSize(); i++) {
            inv.setItem(i, items.get(i));
        }
        player.setGameMode(gameMode);
        player.teleportTo(level, x, y, z, Set.of(), yRot, xRot, true);
        SkywarsGame.resetVitals(player);
    }
}
