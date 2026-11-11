package com.aidan.skyserver.lobby;

import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;

/**
 * 5.13: left-click a mystery chest in the Great Hall to see every prize and its chance (with or
 * without a key). Right-click still opens it with a key, or shows the odds without one (Crates).
 */
public final class CrateOdds {
    private CrateOdds() {}

    public static void init() {
        AttackBlockCallback.EVENT.register((player, level, hand, pos, dir) -> {
            if (level.isClientSide() || !Lobby.isLobby(level) || !(player instanceof ServerPlayer sp)) return InteractionResult.PASS;
            int tier = Crates.crateAt(pos);
            if (tier < 0) return InteractionResult.PASS;
            Lobby.later(sp, () -> Crates.previewList(sp, Keys.COLORS[tier] + Keys.NAMES[tier] + " Chest §8- prizes", Loot.TABLES.get(tier),
                    "§7You have §f" + Keys.count(sp.getUUID(), tier) + " §7of these keys"));
            return InteractionResult.FAIL;
        });
    }
}
