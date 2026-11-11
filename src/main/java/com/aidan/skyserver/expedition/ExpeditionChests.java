package com.aidan.skyserver.expedition;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Prediction;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 5.17: everyone gets their OWN loot from every chest arena and from the Treasure Vault. The chest block
 * in the world stays empty; opening it shows the player a chest of their own that nobody else can see or
 * take from. The loot is rolled the first time that player opens it. Whatever is left in it when the trip
 * ends is lost (like an unopened chest before).
 */
final class ExpeditionChests {
    private ExpeditionChests() {}

    /** chest (an arena, or the trip itself for the vault) -> player -> that player's own chest. */
    private static final Map<Object, Map<UUID, SimpleContainer>> BOXES = new HashMap<>();

    /** Opens this player's own chest for `key`, rolling `loot` the first time. */
    static void open(ServerPlayer p, Object key, String title, Supplier<List<ItemStack>> loot) {
        Map<UUID, SimpleContainer> byPlayer = BOXES.computeIfAbsent(key, k -> new HashMap<>());
        SimpleContainer box = byPlayer.get(p.getUUID());
        if (box == null) {
            box = new SimpleContainer(27);
            for (ItemStack s : loot.get()) {
                ItemStack left = box.addItem(s);
                if (!left.isEmpty()) p.drop(left, false, Prediction.SERVER_ONLY); // more than fits: at their feet
            }
            byPlayer.put(p.getUUID(), box);
        }
        final SimpleContainer mine = box;
        p.openMenu(new SimpleMenuProvider((id, inv, pl) -> new ChestMenu(MenuType.GENERIC_9x3, id, inv, mine, 3), Component.literal(title)));
    }

    /** Has this player opened (= been given) their chest for `key` yet? */
    static boolean opened(ServerPlayer p, Object key) {
        Map<UUID, SimpleContainer> byPlayer = BOXES.get(key);
        return byPlayer != null && byPlayer.containsKey(p.getUUID());
    }

    static void forget(Object key) {
        BOXES.remove(key);
    }
}
