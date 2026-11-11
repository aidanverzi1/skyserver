package com.aidan.skyserver.expedition;

import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.item.enchantment.ItemEnchantments;

import java.util.*;

/**
 * Nether Expedition rewards (difficulty 0 Easy, 1 Normal, 2 Hard, 3 Nightmare). Every reward number is here.
 *
 *  - Room chests: chestLoot() is what ONE player finds in a chest arena's chest once its guards are beaten
 *    (5.17: every player gets their own roll - ExpeditionChests). Many things are rolled per "slot"
 *    (e.g. diamond 7 x 32% = seven separate 32% rolls for 1 diamond each).
 *  - Treasure Vault: vault() is what ONE player finds in the chest that appears when the boss dies.
 *  - Vault money: 21% of the trip's price, to EVERY player (5.17: everyone pays for their own place, so
 *    everyone gets the whole reward: the money, the vault key, the bonus-key roll and all the shards).
 *
 * 5.11: rewards up ~5%; netherite upgrade templates down by a fifth (room chests 8/12/20%, vault 80%).
 * 5.13: Wren's Bounty (Voyage) adds one more roll to every rolled room-chest item.
 * 5.14: every room chest has a BOOK_CHANCE (3%) of one enchanted book - Sharpness, Protection, Power, Punch
 *       or Knockback. Easy / Normal: a vanilla level. Hard / Nightmare: a level beyond vanilla.
 * 5.17: gold up 75% (room chests 4-9 / 8-15 / 13-21 / 17-29 -> 7-16 / 14-26 / 23-37 / 30-51; the Easy vault's
 *       gold blocks 2 -> 3 or 4) and diamonds up 40% (room rolls 5x32% / 5x53% / 6x63% / 7x68% ->
 *       7x32% / 7x53% / 8x66% / 9x74%; vault 4 / 8 / 15 / 13 -> 6 / 11 / 21 / 18).
 *
 * No Wither skulls anywhere (Withers are blocked server-wide): Nightmare gives a shard bundle instead.
 */
public final class ExpeditionLoot {
    private ExpeditionLoot() {}

    /** Vault money for each player: this share of the trip's price. */
    static final double VAULT_MONEY = 0.21;
    /** Keys from the vault: {tier, count} (1 Uncommon, 2 Rare, 3 Epic, 4 Legendary). */
    static final int[][] VAULT_KEYS = {{1, 1}, {2, 1}, {3, 1}, {3, 1}};
    /** Extra key chance (%) and its tier: Hard 25% Rare, Nightmare 25% Legendary. */
    static final int[] BONUS_KEY_CHANCE = {0, 0, 25, 25}, BONUS_KEY_TIER = {0, 0, 2, 4};
    /** The old "crew members' key chance" (5.17: everyone gets the key; kept so anything that reads it compiles). */
    static final int CREW_KEY_CHANCE = 100;
    static final int[] VAULT_SHARDS = {5, 11, 21, 42};
    static final int[] WIN_PASS_XP = {315, 525, 840, 1_260};
    static final int[] WIN_XP = {158, 263, 420, 630};
    /** Chance (%) of the netherite upgrade template in the Hard / Nightmare vault. */
    static final int VAULT_TEMPLATE_CHANCE = 80;
    /** Hard: chance (%) of a 30-minute +50% money booster (each player). */
    static final int HARD_BOOSTER_CHANCE = 30;
    static final String HARD_BOOSTER = "booster:money:30:50";
    /** Nightmare: chance (%) of an Island Theme token and of a 10-shard bundle (each player). */
    static final int NIGHTMARE_THEME_CHANCE = 25, NIGHTMARE_SHARD_BUNDLE_CHANCE = 25, NIGHTMARE_SHARD_BUNDLE = 10;
    /** Nightmare first clear: exclusive title. */
    static final String CONQUEROR_TITLE = "title_conqueror";

    /** 5.14: chance (%) that a room chest holds one enchanted book. */
    static final int BOOK_CHANCE = 3;
    /** 5.14: the book enchantments, and their vanilla maximum / highest level on this server (same order). */
    private static final List<ResourceKey<Enchantment>> BOOK_ENCHANTS =
            List.of(Enchantments.SHARPNESS, Enchantments.PROTECTION, Enchantments.POWER, Enchantments.PUNCH, Enchantments.KNOCKBACK);
    private static final int[] VANILLA_MAX = {5, 4, 5, 2, 2}, SERVER_MAX = {7, 7, 7, 5, 5};

    /** 5.13: extra rolls per rolled item while filling one room chest (Wren's Bounty). */
    private static int bonusRolls = 0;

    private static ItemStack s(Item item, int n) {
        return new ItemStack(item, Math.max(1, n));
    }

    private static int between(Random rng, int lo, int hi) {
        return lo + rng.nextInt(hi - lo + 1);
    }

    /** Adds `slots` separate rolls of `chance`% for one of `item`. Returns how many hit. */
    private static int rolls(List<ItemStack> out, Random rng, Item item, int slots, int chance) {
        int hit = 0;
        for (int i = 0; i < slots + bonusRolls; i++) {
            if (rng.nextInt(100) < chance) {
                out.add(new ItemStack(item));
                hit++;
            }
        }
        return hit;
    }

    static void enchant(ServerLevel level, ItemStack s, ResourceKey<Enchantment> key, int lvl) {
        try {
            Holder<Enchantment> e = level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(key);
            s.enchant(e, lvl);
        } catch (RuntimeException ignored) {
        }
    }

    /** 5.14: an enchanted book holding one enchantment at the given level (the level may be above vanilla's maximum). */
    static ItemStack book(ServerLevel level, ResourceKey<Enchantment> key, int lvl) {
        ItemStack b = new ItemStack(Items.ENCHANTED_BOOK);
        try {
            ItemEnchantments.Mutable m = new ItemEnchantments.Mutable(ItemEnchantments.EMPTY);
            m.set(level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(key), lvl);
            b.set(DataComponents.STORED_ENCHANTMENTS, m.toImmutable());
        } catch (RuntimeException ignored) {
        }
        return b;
    }

    /** 5.14: the chest's random book for this difficulty (see the class comment). */
    static ItemStack randomBook(ServerLevel level, int d, Random rng) {
        int i = rng.nextInt(BOOK_ENCHANTS.size());
        int lvl = d >= 2 ? between(rng, VANILLA_MAX[i] + 1, SERVER_MAX[i]) : between(rng, 1, VANILLA_MAX[i]);
        return book(level, BOOK_ENCHANTS.get(i), lvl);
    }

    private static final Item[] DIAMOND_ARMOR = {Items.DIAMOND_HELMET, Items.DIAMOND_CHESTPLATE, Items.DIAMOND_LEGGINGS, Items.DIAMOND_BOOTS};

    /** A diamond armor piece or sword. prot/sharp = enchant levels (0 = none), unb = Unbreaking level. */
    private static ItemStack diamondPiece(ServerLevel level, Random rng, int prot, int sharp, int unb) {
        boolean sword = rng.nextInt(5) == 0;
        ItemStack it = new ItemStack(sword ? Items.DIAMOND_SWORD : DIAMOND_ARMOR[rng.nextInt(4)]);
        if (sword && sharp > 0) enchant(level, it, Enchantments.SHARPNESS, sharp);
        if (!sword && prot > 0) enchant(level, it, Enchantments.PROTECTION, prot);
        if (unb > 0) enchant(level, it, Enchantments.UNBREAKING, unb);
        return it;
    }

    /**
     * What ONE player finds in a room chest once its guards are beaten. scrap[0] is increased by the
     * netherite scrap put in (that player's vault uses it for Easy/Normal).
     */
    static List<ItemStack> chestLoot(ServerLevel level, int d, boolean fortress, Random rng, int[] scrap) {
        bonusRolls = ExpVoyage.bonusRolls();
        try {
            return chestLoot0(level, d, fortress, rng, scrap);
        } finally {
            bonusRolls = 0;
        }
    }

    private static List<ItemStack> chestLoot0(ServerLevel level, int d, boolean fortress, Random rng, int[] scrap) {
        List<ItemStack> out = new ArrayList<>();
        Item rod = fortress ? Items.BLAZE_ROD : Items.CRYING_OBSIDIAN;
        switch (d) {
            case 0 -> {
                out.add(s(Items.GOLD_INGOT, between(rng, 7, 16)));
                out.add(s(Items.QUARTZ, between(rng, 8, 17)));
                out.add(s(rod, between(rng, 1, 2)));
                rolls(out, rng, Items.DIAMOND, 7, 32);
                rolls(out, rng, Items.GOLDEN_APPLE, 4, 26);
                scrap[0] += rolls(out, rng, Items.NETHERITE_SCRAP, 2, 16);
            }
            case 1 -> {
                out.add(s(Items.GOLD_INGOT, between(rng, 14, 26)));
                out.add(s(Items.QUARTZ, between(rng, 17, 25)));
                out.add(s(rod, between(rng, 1, 2)));
                rolls(out, rng, Items.DIAMOND, 7, 53);
                if (rng.nextInt(100) < 21) out.add(diamondPiece(level, rng, 0, 0, 0));
                rolls(out, rng, Items.GOLDEN_APPLE, 4, 42);
                scrap[0] += rolls(out, rng, Items.NETHERITE_SCRAP, 2, 26);
                rolls(out, rng, Items.NETHERITE_UPGRADE_SMITHING_TEMPLATE, 1, 8);
            }
            case 2 -> {
                out.add(s(Items.GOLD_INGOT, between(rng, 23, 37)));
                out.add(s(Items.QUARTZ, between(rng, 25, 34)));
                out.add(s(rod, between(rng, 2, 3)));
                rolls(out, rng, Items.DIAMOND, 8, 66);
                if (rng.nextInt(100) < 32) out.add(rng.nextBoolean() ? diamondPiece(level, rng, 2, 2, 0) : diamondPiece(level, rng, 0, 0, 0));
                rolls(out, rng, Items.GOLDEN_APPLE, 4, 53);
                scrap[0] += rolls(out, rng, Items.NETHERITE_SCRAP, 3, 26);
                rolls(out, rng, Items.NETHERITE_UPGRADE_SMITHING_TEMPLATE, 1, 12);
                rolls(out, rng, Items.TOTEM_OF_UNDYING, 1, 5);
            }
            default -> {
                out.add(s(Items.GOLD_INGOT, between(rng, 30, 51)));
                out.add(s(Items.QUARTZ, between(rng, 34, 50)));
                out.add(s(rod, between(rng, 3, 4)));
                rolls(out, rng, Items.DIAMOND, 9, 74);
                if (rng.nextInt(100) < 47) out.add(diamondPiece(level, rng, 3, 3, 2));
                rolls(out, rng, Items.GOLDEN_APPLE, 4, 63);
                rolls(out, rng, Items.ENCHANTED_GOLDEN_APPLE, 1, 8);
                scrap[0] += rolls(out, rng, Items.NETHERITE_SCRAP, 3, 37);
                rolls(out, rng, Items.NETHERITE_UPGRADE_SMITHING_TEMPLATE, 1, 20);
                rolls(out, rng, Items.TOTEM_OF_UNDYING, 1, 10);
            }
        }
        // 5.14: a flat 3% per chest (not boosted by Wren's Bounty) for one book.
        if (rng.nextInt(100) < BOOK_CHANCE) out.add(randomBook(level, d, rng));
        Collections.shuffle(out, rng);
        return out;
    }

    /**
     * What ONE player finds in the Treasure Vault chest. roomScrap = the scrap that came out of that
     * player's room chests. (5.17: telling ExpVoyage the boss died moved to Expedition.bossDown - this is
     * now called once per player.)
     */
    static List<ItemStack> vault(ServerLevel level, int d, Random rng, int roomScrap) {
        List<ItemStack> out = new ArrayList<>();
        switch (d) {
            case 0 -> {
                out.add(s(Items.DIAMOND, 6));
                out.add(s(Items.GOLD_BLOCK, rng.nextBoolean() ? 4 : 3));
                // No scrap from the rooms: one guaranteed. Otherwise a 50% chance.
                if (roomScrap == 0 || rng.nextBoolean()) out.add(s(Items.NETHERITE_SCRAP, 1));
            }
            case 1 -> {
                out.add(s(Items.DIAMOND, 11));
                out.add(s(Items.ANCIENT_DEBRIS, 1));
                out.add(diamondArmor(level, rng, 2));
                // At least one scrap from the rooms: one more. None: two.
                out.add(s(Items.NETHERITE_SCRAP, roomScrap >= 1 ? 1 : 2));
            }
            case 2 -> {
                out.add(s(Items.DIAMOND, 21));
                out.add(s(Items.ANCIENT_DEBRIS, 2));
                if (rng.nextInt(100) < VAULT_TEMPLATE_CHANCE) out.add(s(Items.NETHERITE_UPGRADE_SMITHING_TEMPLATE, 1));
                out.add(s(Items.ENCHANTED_GOLDEN_APPLE, 1));
                if (rng.nextBoolean()) {
                    ItemStack sword = new ItemStack(Items.DIAMOND_SWORD);
                    enchant(level, sword, Enchantments.SHARPNESS, 4);
                    out.add(sword);
                } else {
                    out.add(diamondArmor(level, rng, 4));
                }
            }
            default -> {
                out.add(s(Items.NETHERITE_INGOT, 1 + rng.nextInt(2)));
                if (rng.nextInt(100) < VAULT_TEMPLATE_CHANCE) out.add(s(Items.NETHERITE_UPGRADE_SMITHING_TEMPLATE, 1));
                out.add(s(Items.ENCHANTED_GOLDEN_APPLE, 2));
                out.add(s(Items.DIAMOND, 18));
            }
        }
        return out;
    }

    private static ItemStack diamondArmor(ServerLevel level, Random rng, int prot) {
        ItemStack it = new ItemStack(DIAMOND_ARMOR[rng.nextInt(4)]);
        enchant(level, it, Enchantments.PROTECTION, prot);
        return it;
    }

    /** Vault money for ONE player (5.17: everyone gets it in full). */
    static long vaultMoney(int d) {
        return Math.round(Expedition.PRICES[d] * VAULT_MONEY);
    }

    /** The old name (it used to be split between the crew); kept so anything that still calls it compiles. */
    static long vaultMoneyTotal(int d) {
        return vaultMoney(d);
    }
}
