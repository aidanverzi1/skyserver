package com.aidan.skyserver.goldwars;

import com.aidan.skyserver.Longbow;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.DyedItemColor;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;

/**
 * Gold Wars classes and their kits.
 * limit = most players per team who can pick it (0 = no limit).
 */
public enum GwClass {
    BUILDER("builder", "Builder", Items.SANDSTONE, 0,
            "64 sandstone, planks & glass, fast at breaking gold"),
    KNIGHT("knight", "Knight", Items.IRON_SWORD, 0,
            "Iron armor and sword, permanent Resistance"),
    ARCHER("archer", "Archer", Items.BOW, 0,
            "Power I bow and 64 arrows"),
    MAGE("mage", "Mage", Items.BLAZE_ROD, 1,
            "Fire Wand: explosive fireballs every 4s"),
    LONGBOW("longbow", "Longbow", Items.SPECTRAL_ARROW, 1,
            "Charge 3.5s for an instant long-range shot"),
    SCOUT("scout", "Scout", Items.FEATHER, 0,
            "Permanent Speed, quick at breaking gold");

    public static final String FIRE_WAND_NAME = "Fire Wand";
    private static final int RED_LEATHER = 0xB02E26;
    private static final int BLUE_LEATHER = 0x3C44AA;

    public final String id;
    public final String display;
    public final Item icon;
    public final int limit;
    public final String description;

    GwClass(String id, String display, Item icon, int limit, String description) {
        this.id = id;
        this.display = display;
        this.icon = icon;
        this.limit = limit;
        this.description = description;
    }

    /** How many of each building block (sandstone, planks, glass) this class starts with. */
    public int blockCount() {
        return this == BUILDER ? 64 : 16;
    }

    /** Clears the player's inventory and gives this class's kit. */
    public void giveKit(ServerPlayer p, GwTeam team) {
        Inventory inv = p.getInventory();
        inv.clearContent();
        int blocks = 16;

        switch (this) {
            case BUILDER -> {
                armor(p, team, Items.LEATHER_HELMET, Items.LEATHER_CHESTPLATE, Items.LEATHER_LEGGINGS, Items.LEATHER_BOOTS);
                inv.setItem(0, new ItemStack(Items.STONE_SWORD));
                ItemStack pick = tool(p, Items.STONE_PICKAXE);
                enchant(p, pick, Enchantments.EFFICIENCY, 2);
                inv.setItem(1, pick);
                blocks = 64;
            }
            case KNIGHT -> {
                armor(p, team, Items.IRON_HELMET, Items.IRON_CHESTPLATE, Items.CHAINMAIL_LEGGINGS, Items.IRON_BOOTS);
                inv.setItem(0, new ItemStack(Items.IRON_SWORD));
                inv.setItem(1, tool(p, Items.WOODEN_PICKAXE));
            }
            case ARCHER -> {
                armor(p, team, Items.CHAINMAIL_HELMET, Items.LEATHER_CHESTPLATE, Items.LEATHER_LEGGINGS, Items.LEATHER_BOOTS);
                inv.setItem(0, new ItemStack(Items.STONE_SWORD));
                ItemStack bow = tool(p, Items.BOW);
                enchant(p, bow, Enchantments.POWER, 1);
                inv.setItem(1, bow);
                inv.setItem(2, tool(p, Items.WOODEN_PICKAXE));
                inv.setItem(9, new ItemStack(Items.ARROW, 64));
            }
            case MAGE -> {
                armor(p, team, Items.GOLDEN_HELMET, Items.LEATHER_CHESTPLATE, Items.LEATHER_LEGGINGS, Items.LEATHER_BOOTS);
                inv.setItem(0, new ItemStack(Items.WOODEN_SWORD));
                inv.setItem(1, fireWand());
                inv.setItem(2, tool(p, Items.WOODEN_PICKAXE));
            }
            case LONGBOW -> {
                armor(p, team, Items.CHAINMAIL_HELMET, Items.LEATHER_CHESTPLATE, Items.LEATHER_LEGGINGS, Items.LEATHER_BOOTS);
                inv.setItem(0, new ItemStack(Items.STONE_SWORD));
                inv.setItem(1, Longbow.create(p));
                inv.setItem(2, tool(p, Items.WOODEN_PICKAXE));
                inv.setItem(9, new ItemStack(Items.ARROW, 1));
            }
            case SCOUT -> {
                armor(p, team, Items.LEATHER_HELMET, Items.LEATHER_CHESTPLATE, Items.LEATHER_LEGGINGS, Items.LEATHER_BOOTS);
                inv.setItem(0, new ItemStack(Items.WOODEN_SWORD));
                ItemStack pick = tool(p, Items.WOODEN_PICKAXE);
                enchant(p, pick, Enchantments.EFFICIENCY, 2);
                inv.setItem(1, pick);
            }
        }

        inv.setItem(3, new ItemStack(Items.SANDSTONE, blocks));
        inv.setItem(4, new ItemStack(Items.OAK_PLANKS, blocks));
        inv.setItem(5, new ItemStack(Items.GLASS, blocks));
        inv.setItem(8, GoldWarsGame.classSelector());
    }

    public static ItemStack fireWand() {
        ItemStack stack = new ItemStack(Items.BLAZE_ROD);
        stack.set(DataComponents.CUSTOM_NAME, Component.literal("§6" + FIRE_WAND_NAME + " §7(right-click)"));
        return stack;
    }

    public static boolean isFireWand(ItemStack stack) {
        return stack.is(Items.BLAZE_ROD) && hasName(stack, FIRE_WAND_NAME);
    }

    /** The official server Longbow (see com.aidan.skyserver.Longbow). */
    public static boolean isLongbow(ItemStack stack) {
        return Longbow.isLongbow(stack);
    }

    private static boolean hasName(ItemStack stack, String name) {
        Component c = stack.get(DataComponents.CUSTOM_NAME);
        return c != null && c.getString().contains(name);
    }

    public static GwClass byId(String id) {
        for (GwClass c : values()) if (c.id.equals(id)) return c;
        return null;
    }

    /** A tool with Unbreaking III so it never breaks mid-match. */
    private static ItemStack tool(ServerPlayer p, Item item) {
        ItemStack stack = new ItemStack(item);
        enchant(p, stack, Enchantments.UNBREAKING, 3);
        return stack;
    }

    /** Puts on armor; any leather piece is dyed the team's color. */
    private static void armor(ServerPlayer p, GwTeam team, Item head, Item chest, Item legs, Item feet) {
        p.setItemSlot(EquipmentSlot.HEAD, armorPiece(head, team));
        p.setItemSlot(EquipmentSlot.CHEST, armorPiece(chest, team));
        p.setItemSlot(EquipmentSlot.LEGS, armorPiece(legs, team));
        p.setItemSlot(EquipmentSlot.FEET, armorPiece(feet, team));
    }

    private static ItemStack armorPiece(Item item, GwTeam team) {
        ItemStack stack = new ItemStack(item);
        if (item == Items.LEATHER_HELMET || item == Items.LEATHER_CHESTPLATE
                || item == Items.LEATHER_LEGGINGS || item == Items.LEATHER_BOOTS) {
            stack.set(DataComponents.DYED_COLOR, new DyedItemColor(team == GwTeam.RED ? RED_LEATHER : BLUE_LEATHER));
        }
        return stack;
    }

    private static void enchant(ServerPlayer p, ItemStack stack, ResourceKey<Enchantment> key, int level) {
        Holder<Enchantment> holder = p.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(key);
        stack.enchant(holder, level);
    }
}
