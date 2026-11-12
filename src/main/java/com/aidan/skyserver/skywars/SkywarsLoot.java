package com.aidan.skyserver.skywars;

import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.Supplier;

/**
 * Skywars loot. Every chest has a weapon (or a tool good enough to fight with). Building blocks
 * are kept scarce: each spawn island has 32-48 in total, split over two of its three chests, so
 * you have to choose between bridging out and mining the island. Instead of chests refilling,
 * every player gets one new item every 20 seconds, and the items get better as the match goes on.
 */
public final class SkywarsLoot {
    private static final Random RNG = new Random();

    public static final String BRIDGE_EGG_NAME = "Bridge Egg";

    /** Which chest on a spawn island (0 = by the spawn, 1 = hut, 2 = ledge). */
    public enum Table { ISLAND_SPAWN, ISLAND_HUT, ISLAND_LEDGE, MID, CENTER, SUPPLY }

    private record Entry(Supplier<ItemStack> make, int weight) {}

    private static Entry e(int weight, Supplier<ItemStack> make) {
        return new Entry(make, weight);
    }

    private static Supplier<ItemStack> it(net.minecraft.world.item.Item item, int min, int max) {
        return () -> new ItemStack(item, min + RNG.nextInt(max - min + 1));
    }

    // ---- Guaranteed weapons ----
    private static final List<Entry> W_ISLAND = List.of(
            e(5, it(Items.WOODEN_SWORD, 1, 1)), e(8, it(Items.STONE_SWORD, 1, 1)), e(3, it(Items.STONE_AXE, 1, 1)),
            e(2, it(Items.IRON_SWORD, 1, 1)), e(1, it(Items.GOLDEN_SWORD, 1, 1)));
    private static final List<Entry> W_TOOL = List.of(
            e(6, it(Items.STONE_PICKAXE, 1, 1)), e(5, it(Items.STONE_AXE, 1, 1)), e(2, it(Items.IRON_PICKAXE, 1, 1)), e(2, it(Items.IRON_AXE, 1, 1)));
    private static final List<Entry> W_MID = List.of(
            e(6, it(Items.IRON_SWORD, 1, 1)), e(3, it(Items.IRON_AXE, 1, 1)), e(2, SwItems::kbStick), e(2, it(Items.STONE_SWORD, 1, 1)));
    private static final List<Entry> W_CENTER = List.of(
            e(4, it(Items.IRON_SWORD, 1, 1)), e(3, it(Items.DIAMOND_SWORD, 1, 1)), e(2, it(Items.DIAMOND_AXE, 1, 1)), e(2, () -> SwItems.luckyGear(false)));

    // ---- Armor (one piece per spawn-island chest) ----
    private static final List<Entry> A_ISLAND = List.of(
            e(5, it(Items.LEATHER_HELMET, 1, 1)), e(5, it(Items.LEATHER_BOOTS, 1, 1)), e(4, it(Items.CHAINMAIL_CHESTPLATE, 1, 1)),
            e(4, it(Items.CHAINMAIL_LEGGINGS, 1, 1)), e(3, it(Items.GOLDEN_HELMET, 1, 1)), e(3, it(Items.IRON_HELMET, 1, 1)),
            e(3, it(Items.IRON_BOOTS, 1, 1)), e(2, it(Items.IRON_LEGGINGS, 1, 1)), e(1, it(Items.IRON_CHESTPLATE, 1, 1)));

    // ---- Extras ----
    private static final List<Entry> X_ISLAND = List.of(
            e(6, it(Items.SNOWBALL, 1, 5)), e(5, it(Items.EGG, 1, 5)), // 5.18: 1-5 (they knock back now)
            e(4, it(Items.ARROW, 4, 10)), e(3, it(Items.BOW, 1, 1)), e(3, () -> bridgeEgg(1)), e(3, it(Items.WATER_BUCKET, 1, 1)),
            e(2, () -> SwItems.soup(1)), e(2, () -> SwItems.fireball(1)), e(1, it(Items.ENDER_PEARL, 1, 1)),
            e(2, SwItems::speedPotion), e(2, SwItems::jumpPotion), e(1, SwItems::hook), e(2, it(Items.COBWEB, 1, 3)));
    private static final List<Entry> X_MID = List.of(
            e(5, it(Items.IRON_CHESTPLATE, 1, 1)), e(5, it(Items.IRON_LEGGINGS, 1, 1)), e(4, it(Items.IRON_HELMET, 1, 1)),
            e(4, it(Items.IRON_BOOTS, 1, 1)), e(4, it(Items.BOW, 1, 1)), e(5, it(Items.ARROW, 8, 16)), e(3, it(Items.ENDER_PEARL, 1, 2)),
            e(4, () -> SwItems.fireball(1 + RNG.nextInt(2))), e(4, () -> SwItems.soup(1 + RNG.nextInt(2))), e(3, () -> SwItems.instantTnt(1 + RNG.nextInt(2))),
            e(3, SwItems::hook), e(3, () -> bridgeEgg(1 + RNG.nextInt(2))), e(2, it(Items.GOLDEN_APPLE, 1, 1)), e(3, it(Items.OAK_PLANKS, 8, 16)),
            e(2, SwItems::speedPotion), e(2, SwItems::jumpPotion), e(2, it(Items.LAVA_BUCKET, 1, 1)), e(2, SwItems::kbStick));
    private static final List<Entry> X_CENTER = List.of(
            e(4, it(Items.DIAMOND_HELMET, 1, 1)), e(3, it(Items.DIAMOND_CHESTPLATE, 1, 1)), e(3, it(Items.DIAMOND_LEGGINGS, 1, 1)),
            e(4, it(Items.DIAMOND_BOOTS, 1, 1)), e(5, it(Items.IRON_CHESTPLATE, 1, 1)), e(5, it(Items.BOW, 1, 1)), e(6, it(Items.ARROW, 10, 24)),
            e(5, it(Items.GOLDEN_APPLE, 1, 2)), e(4, it(Items.ENDER_PEARL, 1, 3)), e(4, () -> SwItems.fireball(2 + RNG.nextInt(2))),
            e(3, () -> SwItems.instantTnt(2)), e(3, () -> SwItems.luckyGear(false)), e(2, () -> SwItems.luckyGear(true)),
            e(3, SwItems::regenPotion), e(3, () -> SwItems.soup(2)), e(3, it(Items.COBBLESTONE, 12, 24)), e(2, SwItems::hook));
    private static final List<Entry> X_SUPPLY = List.of(
            e(6, it(Items.DIAMOND_CHESTPLATE, 1, 1)), e(6, it(Items.DIAMOND_LEGGINGS, 1, 1)), e(6, it(Items.DIAMOND_HELMET, 1, 1)),
            e(6, it(Items.DIAMOND_BOOTS, 1, 1)), e(6, () -> SwItems.luckyGear(true)), e(6, it(Items.GOLDEN_APPLE, 2, 3)),
            e(4, it(Items.ENDER_PEARL, 2, 3)), e(4, () -> SwItems.fireball(3)), e(3, it(Items.SHIELD, 1, 1)), e(3, () -> bridgeEgg(2)));

    private SkywarsLoot() {}

    public static ItemStack bridgeEgg(int count) {
        ItemStack stack = new ItemStack(Items.EGG, count);
        stack.set(DataComponents.CUSTOM_NAME, Component.literal(BRIDGE_EGG_NAME));
        return stack;
    }

    public static boolean isBridgeEgg(ItemStack stack) {
        Component name = stack.get(DataComponents.CUSTOM_NAME);
        return name != null && BRIDGE_EGG_NAME.equals(name.getString());
    }

    /** Fills a chest (always clears it first). */
    public static void fill(Container chest, Table table) {
        chest.clearContent();
        List<ItemStack> items = new ArrayList<>();
        switch (table) {
            case ISLAND_SPAWN -> {
                items.add(pick(W_ISLAND));
                items.add(blocks(16, 24));
                items.add(pick(A_ISLAND));
                extras(items, X_ISLAND, 1 + RNG.nextInt(2));
            }
            case ISLAND_HUT -> {
                items.add(pick(W_ISLAND));
                items.add(blocks(16, 24));
                items.add(pick(A_ISLAND));
                extras(items, X_ISLAND, 1 + RNG.nextInt(2));
            }
            case ISLAND_LEDGE -> {
                items.add(pick(W_TOOL));
                items.add(pick(A_ISLAND));
                extras(items, X_ISLAND, 2 + RNG.nextInt(2));
            }
            case MID -> {
                items.add(pick(W_MID));
                extras(items, X_MID, 3 + RNG.nextInt(2));
            }
            case CENTER -> {
                items.add(pick(W_CENTER));
                extras(items, X_CENTER, 4 + RNG.nextInt(2));
            }
            case SUPPLY -> extras(items, X_SUPPLY, 7 + RNG.nextInt(3));
        }
        List<Integer> slots = new ArrayList<>();
        for (int i = 0; i < chest.getContainerSize(); i++) slots.add(i);
        for (ItemStack s : items) {
            if (slots.isEmpty() || s.isEmpty()) break;
            chest.setItem(slots.remove(RNG.nextInt(slots.size())), s);
        }
    }

    private static void extras(List<ItemStack> into, List<Entry> table, int n) {
        for (int i = 0; i < n; i++) into.add(pick(table));
    }

    private static ItemStack blocks(int min, int max) {
        int n = min + RNG.nextInt(max - min + 1);
        return new ItemStack(RNG.nextInt(3) == 0 ? Items.COBBLESTONE : Items.OAK_PLANKS, n);
    }

    private static ItemStack pick(List<Entry> table) {
        int total = 0;
        for (Entry e : table) total += e.weight();
        int roll = RNG.nextInt(total);
        for (Entry e : table) {
            roll -= e.weight();
            if (roll < 0) return e.make().get();
        }
        return table.get(0).make().get();
    }

    // =====================================================================
    // The item every 20 seconds
    // =====================================================================

    private static final List<Entry> DRIP_1 = List.of( // first 2 minutes
            e(8, it(Items.OAK_PLANKS, 6, 10)), e(6, it(Items.ARROW, 4, 8)), e(6, it(Items.SNOWBALL, 4, 8)),
            e(4, () -> bridgeEgg(1)), e(3, it(Items.LEATHER_CHESTPLATE, 1, 1)), e(3, it(Items.STONE_SWORD, 1, 1)), e(3, () -> SwItems.soup(1)),
            e(2, it(Items.BOW, 1, 1)), e(2, it(Items.COBWEB, 1, 2)), e(2, it(Items.IRON_HELMET, 1, 1)));
    private static final List<Entry> DRIP_2 = List.of( // 2-4 minutes
            e(6, it(Items.OAK_PLANKS, 8, 12)), e(5, it(Items.IRON_CHESTPLATE, 1, 1)), e(5, it(Items.IRON_LEGGINGS, 1, 1)),
            e(4, it(Items.IRON_SWORD, 1, 1)), e(4, it(Items.BOW, 1, 1)), e(5, it(Items.ARROW, 6, 12)), e(4, () -> SwItems.fireball(1)),
            e(4, () -> SwItems.soup(1)), e(3, SwItems::kbStick), e(3, SwItems::hook), e(3, () -> SwItems.instantTnt(1)),
            e(3, it(Items.ENDER_PEARL, 1, 1)), e(3, () -> bridgeEgg(1)), e(2, SwItems::speedPotion), e(2, SwItems::jumpPotion));
    private static final List<Entry> DRIP_3 = List.of( // 4-7 minutes
            e(5, it(Items.COBBLESTONE, 10, 16)), e(4, it(Items.DIAMOND_HELMET, 1, 1)), e(4, it(Items.DIAMOND_BOOTS, 1, 1)),
            e(3, it(Items.DIAMOND_CHESTPLATE, 1, 1)), e(3, it(Items.DIAMOND_LEGGINGS, 1, 1)), e(3, it(Items.DIAMOND_SWORD, 1, 1)),
            e(5, it(Items.GOLDEN_APPLE, 1, 1)), e(4, it(Items.ENDER_PEARL, 1, 2)), e(5, () -> SwItems.fireball(2)),
            e(3, () -> SwItems.luckyGear(false)), e(3, SwItems::regenPotion), e(3, () -> SwItems.instantTnt(2)), e(3, () -> bridgeEgg(2)));
    private static final List<Entry> DRIP_4 = List.of( // 7+ minutes: finish it
            e(5, () -> SwItems.luckyGear(true)), e(5, it(Items.GOLDEN_APPLE, 1, 2)), e(5, () -> SwItems.fireball(3)),
            e(4, it(Items.ENDER_PEARL, 2, 2)), e(3, () -> SwItems.instantTnt(3)), e(4, it(Items.COBBLESTONE, 16, 24)),
            e(3, SwItems::regenPotion), e(3, () -> bridgeEgg(2)));

    /** The item for this point in the match (seconds since it started). */
    public static ItemStack dripItem(int seconds) {
        List<Entry> table = seconds < 120 ? DRIP_1 : seconds < 240 ? DRIP_2 : seconds < 420 ? DRIP_3 : DRIP_4;
        return pick(table);
    }

    /** Friendly name for the "+ item" message. */
    public static String describe(ItemStack s) {
        return (s.getCount() > 1 ? s.getCount() + "x " : "") + s.getHoverName().getString();
    }
}
