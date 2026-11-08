package com.aidan.skyserver.mixin;

import com.aidan.skyserver.bots.record.PlayerRecorder;
import net.minecraft.network.protocol.game.ServerboundPunchPacket;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * SkyServer's only mixin. Tells the player recorder when a real player swings their arm (every left
 * click, including misses, which no Fabric event reports). It only watches: the swing itself is untouched.
 *
 * TAIL = after vanilla handled the packet on the server thread (the first, off-thread call to
 * handleAnimate bails out early and never reaches TAIL, so each swing is counted once).
 * require = 0: if a future Minecraft renames the method, the server still starts; /record status
 * will just say the swing hook hasn't been seen.
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class SwingRecorderMixin {
    @Inject(method = "handlePunch", at = @At("TAIL"), require = 0)
    private void skyserver$recordSwing(ServerboundPunchPacket packet, CallbackInfo ci) {
        PlayerRecorder.onSwing(((ServerGamePacketListenerImpl) (Object) this).player);
    }
}
