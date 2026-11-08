package com.aidan.skyserver.mixin;

import com.aidan.skyserver.WorldTweaks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * No crop trampling anywhere: while something lands on farmland, the "turn to dirt" step is
 * skipped. Fall damage still happens as normal, and farmland still turns to dirt for other
 * reasons (a block placed on top, no water nearby).
 *
 * The farmland class was renamed in 26.x (FarmBlock -> FarmlandBlock), so it's targeted by name:
 * whichever of the two exists gets the change, the other is skipped (@Pseudo).
 */
@Pseudo
@Mixin(targets = {"net.minecraft.world.level.block.FarmlandBlock", "net.minecraft.world.level.block.FarmBlock"})
public abstract class FarmlandMixin {
    @Inject(method = "fallOn", at = @At("HEAD"), require = 0)
    private void skyserver$landStart(CallbackInfo ci) {
        WorldTweaks.landingOnFarmland(true);
    }

    @Inject(method = "fallOn", at = @At("RETURN"), require = 0)
    private void skyserver$landEnd(CallbackInfo ci) {
        WorldTweaks.landingOnFarmland(false);
    }

    @Inject(method = "turnToBaseBlock", at = @At("HEAD"), cancellable = true, require = 0)
    private static void skyserver$noTrample(CallbackInfo ci) {
        if (WorldTweaks.isLandingOnFarmland()) ci.cancel();
    }
}
