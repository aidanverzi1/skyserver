package com.aidan.skyserver.mixin;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.world.item.enchantment.Enchantment;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 5.14: higher enchantment levels. The anvil (and every other "can this be applied" check) never lets an
 * item get a level above Enchantment.getMaxLevel(), so books of Sharpness VI-VII, Protection V-VII,
 * Power VI-VII and Punch / Knockback III-V (Loot.java crates and Expedition chests) would have been
 * useless. This raises the maximum for exactly those five enchantments and leaves every other one alone.
 *
 *   Sharpness 5 -> 7, Protection 4 -> 7, Power 5 -> 7, Punch 2 -> 5, Knockback 2 -> 5.
 *
 * (Protection past V changes little: armour protection is capped at 20 points in total, and four
 * Protection V pieces already reach it.) Vanilla clients are fine: the levels are plain numbers on the
 * item and show as roman numerals / "enchantment.level.6" text, which the client already knows.
 *
 * require = 0, so if a future Minecraft renames the method this simply does nothing instead of crashing.
 */
@Mixin(Enchantment.class)
public class EnchantLevelMixin {
    @Inject(method = "getMaxLevel", at = @At("RETURN"), cancellable = true, require = 0)
    private void skyserver$higherLevels(CallbackInfoReturnable<Integer> cir) {
        Component description = ((Enchantment) (Object) this).description();
        if (!(description.getContents() instanceof TranslatableContents tc)) return;
        int max = switch (tc.getKey()) {
            case "enchantment.minecraft.sharpness", "enchantment.minecraft.power" -> 7;
            case "enchantment.minecraft.protection" -> 7;
            case "enchantment.minecraft.punch", "enchantment.minecraft.knockback" -> 5;
            default -> 0;
        };
        if (max > cir.getReturnValueI()) cir.setReturnValue(max);
    }
}
