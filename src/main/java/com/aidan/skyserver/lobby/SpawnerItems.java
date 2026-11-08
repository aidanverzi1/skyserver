package com.aidan.skyserver.lobby;

import com.aidan.skyserver.Skyserver;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.SpawnerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;

/**
 * Mob spawners from mystery chests. Vanilla clears a spawner's mob when a normal player places
 * it, so the item carries the mob in its data and we set the mob ourselves right after placing.
 */
public final class SpawnerItems {
    public static final List<String> MOBS = List.of(
            "minecraft:cow", "minecraft:pig", "minecraft:sheep", "minecraft:chicken",
            "minecraft:zombie", "minecraft:skeleton", "minecraft:spider", "minecraft:cave_spider",
            "minecraft:creeper", "minecraft:blaze");

    private SpawnerItems() {}

    public static ItemStack create(String mob, String name) {
        ItemStack s = new ItemStack(Items.SPAWNER);
        Stacks.named(s, "§d" + name + " Spawner", "§7Place it on your Sky Island to spawn " + name.toLowerCase() + "s.", "§8From a mystery chest");
        return Stacks.tag(s, "spawner:" + mob);
    }

    public static String mobOf(ItemStack s) {
        if (!s.is(Items.SPAWNER)) return null;
        for (String m : MOBS) if (Stacks.is(s, "spawner:" + m)) return m;
        return null;
    }

    public static void init() {
        UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
            if (level.isClientSide() || !(level instanceof ServerLevel sl) || Lobby.isLobby(level)) return InteractionResult.PASS;
            ItemStack held = player.getItemInHand(hand);
            String mob = mobOf(held);
            if (mob == null) return InteractionResult.PASS;
            BlockPos clicked = hit.getBlockPos();
            BlockPos beside = clicked.relative(hit.getDirection());
            // Only a spawner that appears now counts (so nobody can change an existing spawner's mob).
            boolean[] wasSpawner = {sl.getBlockState(beside).is(Blocks.SPAWNER), sl.getBlockState(clicked).is(Blocks.SPAWNER)};
            BlockPos[] spots = {beside, clicked};
            // After vanilla places the block this tick, set its mob.
            Lobby.runLater(() -> {
                for (int i = 0; i < 2; i++) {
                    if (wasSpawner[i]) continue;
                    BlockPos p = spots[i];
                    BlockState state = sl.getBlockState(p);
                    if (!state.is(Blocks.SPAWNER)) continue;
                    if (!(sl.getBlockEntity(p) instanceof SpawnerBlockEntity spawner)) continue;
                    EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getValue(Identifier.parse(mob));
                    spawner.setEntityId(type, sl.getRandom());
                    spawner.setChanged();
                    sl.sendBlockUpdated(p, state, state, 3);
                    return;
                }
                Skyserver.LOGGER.debug("Spawner place: no spawner found after placing {}", mob);
            });
            return InteractionResult.PASS;
        });
    }
}
