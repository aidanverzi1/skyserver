package com.aidan.skyserver.mixin;

import net.minecraft.world.level.block.WitherSkullBlock;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 5.8: the Wither can't be summoned anywhere. Wither skeleton skulls can come home from Nether
 * Expeditions; a Wither would blow up Sky Islands and nobody could stop it. Placing the last skull
 * is the only way to build one (players and dispensers both go through checkSpawn), so cancelling
 * it here covers everything. The skulls still work as decoration and sell normally.
 */
@Mixin(WitherSkullBlock.class)
public class WitherSpawnMixin {
    @Inject(method = "checkSpawn", at = @At("HEAD"), cancellable = true)
    private static void skyserver$noWither(CallbackInfo ci) {
        ci.cancel();
    }
}
