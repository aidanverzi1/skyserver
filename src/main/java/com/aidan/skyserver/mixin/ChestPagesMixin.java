package com.aidan.skyserver.mixin;

import com.aidan.skyserver.island.ChestPages;
import com.aidan.skyserver.island.ChestPagesAccess;
import net.minecraft.core.NonNullList;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 5.10: chests can have extra pages (bought per chest on the islands). The chest simply gets more
 * slots - 27 per page - so hoppers, comparators and breaking the chest all see every page.
 * A chest with no extra pages is exactly vanilla (27 slots, nothing extra saved).
 */
@Mixin(ChestBlockEntity.class)
public abstract class ChestPagesMixin implements ChestPagesAccess {
    @Shadow
    private NonNullList<ItemStack> items;

    @Unique
    private int skyserver$pages;

    @Override
    public int skyserver$getPages() {
        return skyserver$pages;
    }

    @Override
    public void skyserver$setPages(int pages) {
        pages = Math.max(0, Math.min(ChestPages.MAX_PAGES, pages));
        skyserver$pages = pages;
        int size = 27 * (1 + pages);
        if (items.size() != size) {
            NonNullList<ItemStack> grown = NonNullList.withSize(size, ItemStack.EMPTY);
            for (int i = 0; i < Math.min(size, items.size()); i++) grown.set(i, items.get(i));
            items = grown;
        }
    }

    @Inject(method = "getContainerSize", at = @At("HEAD"), cancellable = true)
    private void skyserver$size(CallbackInfoReturnable<Integer> cir) {
        cir.setReturnValue(items.size());
    }

    @Inject(method = "loadAdditional", at = @At("HEAD"))
    private void skyserver$loadPages(ValueInput input, CallbackInfo ci) {
        // Before vanilla makes the item list (it uses getContainerSize), so every page loads.
        skyserver$setPages(input.getIntOr("skyserver_pages", 0));
    }

    @Inject(method = "saveAdditional", at = @At("TAIL"))
    private void skyserver$savePages(ValueOutput output, CallbackInfo ci) {
        if (skyserver$pages > 0) output.putInt("skyserver_pages", skyserver$pages);
    }
}
