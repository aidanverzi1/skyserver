package com.aidan.skyserver.mixin;

import com.aidan.skyserver.island.Islands;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BaseSpawner;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 5.8: at most Islands.MAX_SPAWNERS mob spawners work on one Sky Island (the rest sit idle until
 * one is broken), so stacking Legendary-chest spawners can't flood the economy with mob drops.
 * Only affects the islands dimension.
 */
@Mixin(BaseSpawner.class)
public class SpawnerCapMixin {
    @Inject(method = "serverTick", at = @At("HEAD"), cancellable = true)
    private void skyserver$islandSpawnerCap(ServerLevel level, BlockPos pos, CallbackInfo ci) {
        if (!Islands.spawnerMayRun(level, pos)) ci.cancel();
    }
}
