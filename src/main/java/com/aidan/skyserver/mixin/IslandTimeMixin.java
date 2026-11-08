package com.aidan.skyserver.mixin;

import com.aidan.skyserver.island.IslandTime;
import net.minecraft.network.protocol.Packet;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Per-island time of day: when the server sends a player the time, IslandTime swaps in the time
 * of the island they're standing on. Everything else goes through untouched.
 */
@Mixin(ServerCommonPacketListenerImpl.class)
public abstract class IslandTimeMixin {
    @Inject(method = "send(Lnet/minecraft/network/protocol/Packet;)V", at = @At("HEAD"), cancellable = true, require = 0)
    private void skyserver$islandTime(Packet<?> packet, CallbackInfo ci) {
        if (IslandTime.intercept(this, packet)) ci.cancel();
    }
}
