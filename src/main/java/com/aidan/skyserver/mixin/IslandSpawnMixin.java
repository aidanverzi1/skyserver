package com.aidan.skyserver.mixin;

import com.aidan.skyserver.expedition.Expedition;
import com.aidan.skyserver.island.Islands;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.SpawnPlacements;
import net.minecraft.world.level.ServerLevelAccessor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 5.8: nothing spawns naturally on the Sky Islands, whatever the biome. Before, the islands were
 * safe only because they use the empty "void" biome; Island Themes can change the biome, so this
 * keeps the islands free of hostile mobs (and free animals). Spawners, spawn eggs, breeding and
 * commands use other spawn reasons and still work.
 *
 * 5.11: the same for the Nether Expedition world. Its nether_wastes biome kept spawning ghasts,
 * zombified piglins, magma cubes and striders, which the expedition then deleted every second -
 * mobs popping in and out all over the map. Now only the expedition's own guards exist there.
 */
@Mixin(SpawnPlacements.class)
public class IslandSpawnMixin {
    @Inject(method = "checkSpawnRules", at = @At("HEAD"), cancellable = true)
    private static void skyserver$noNaturalIslandSpawns(EntityType<?> type, ServerLevelAccessor level, EntitySpawnReason reason, BlockPos pos,
                                                        RandomSource random, CallbackInfoReturnable<Boolean> cir) {
        if ((reason == EntitySpawnReason.NATURAL || reason == EntitySpawnReason.CHUNK_GENERATION)
                && (Islands.isIsland(level.getLevel()) || Expedition.isExpedition(level.getLevel()))) {
            cir.setReturnValue(false);
        }
    }
}
