package com.aidan.skyserver.expedition;

import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;

import java.util.*;

/**
 * Nether Expedition rewards (difficulty 0 Easy, 1 Normal, 2 Hard, 3 Nightmare). Every reward number is here.
 *
 *  - Room chests: each chest room's chest is filled with chestLoot() when its guards are beaten. Many
 *    things are rolled per "slot" (e.g. diamond 5 x 32% = five separate 32% rolls for 1 diamond each).
 *  - Treasure Vault: the chest that appears in the boss arena when the boss dies: vault() items.
 *  - Vault money: 21% of the trip's price in total (only one player pays for a trip), split between
 *    everyone in the Nether when the boss dies. The player who paid gets the vault keys and all the
 *    shards; the rest of the crew get a 50% chance at the same key and half the shards. Pass XP and
 *    level XP go to everyone.
 *
 * 5.11: the expedition got much harder, so rewards went up ~5% (money, shards, XP, gold, quartz,
 * diamond / golden apple / scrap chances). Netherite upgrade templates went DOWN by a fifth everywhere
 * (room chests 10/15/25% -> 8/12/20%; the Hard and Nightmare vault template 100% -> 80%).
 *
 * No Wither skulls anywhere (Withers are blocked server-wide): Nightmare gives a shard bundle instead.
 */
public final class ExpeditionLoot {
    private ExpeditionLoot() {}

    /** Vault money (total, split between the crew): this share of the price. */
    static final double VAULT_MONEY = 0.21;
    /** Keys from the vault: {tier, count} (1 Uncommon, 2 Rare, 3 Epic, 4 Legendary). */
    static final int[][] VAULT_KEYS = {{1, 1}, {2, 1}, {3, 1}, {3, 1}};
    /** Extra key chance (%) and its tier: Hard 25% Rare, Nightmare 25% Legendary. */
    static final int[] BONUS_KEY_CHANCE = {0, 0, 25, 25}, BONUS_KEY_TIER = {0, 0, 2, 4};
    /** Other crew members (not the payer): chance (%) of also getting the vault key. */
    static final int CREW_KEY_CHANCE = 50;
    static final int[] VAULT_SHARDS = {5, 11, 21, 42};
    static final int[] WIN_PASS_XP = {315, 525, 840, 1_260};
    static final int[] WIN_XP = {158, 263, 420, 630};
    /** Chance (%) of the netherite upgrade template in the Hard / Nightmare vault (5.11: was always there). */
    static final int VAULT_TEMPLATE_CHANCE = 80;
    /** Hard: chance (%) of a 30-minute +50% money booster (each player). */
    static final int HARD_BOOSTER_CHANCE = 30;
    static final String HARD_BOOSTER = "booster:money:30:50";
    /** Nightmare: chance (%) of an Island Theme token and of a 10-shard bundle (each player). */
    static final int NIGHTMARE_THEME_CHANCE = 25, NIGHTMARE_SHARD_BUNDLE_CHANCE = 25, NIGHTMARE_SHARD_BUNDLE = 10;
    /** Nightmare first clear: exclusive title. */
    static final String CONQUEROR_TITLE = "title_conqueror";

    private static ItemStack s(Item item, int n) {
        return new ItemStack(item, Math.max(1, n));
    }

    private static int between(Random rng, int lo, int hi) {
        return lo + rng.nextInt(hi - lo + 1);
    }

    /** Adds `slots` separate rolls of `chance`% for one of `item`. Returns how many hit. */
    private static int rolls(List<ItemStack> out, Random rng, Item item, int slots, int chance) {
        int hit = 0;
        for (int i = 0; i < slots; i++) {
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
     * What a room chest holds once its guards are beaten. scrap[0] is increased by the netherite scrap
     * put in (the vault uses it for Easy/Normal).
     */
    static List<ItemStack> chestLoot(ServerLevel level, int d, boolean fortress, Random rng, int[] scrap) {
        List<ItemStack> out = new ArrayList<>();
        Item rod = fortress ? Items.BLAZE_ROD : Items.CRYING_OBSIDIAN;
        switch (d) {
            case 0 -> {
                out.add(s(Items.GOLD_INGOT, between(rng, 4, 9)));
                out.add(s(Items.QUARTZ, between(rng, 8, 17)));
                out.add(s(rod, between(rng, 1, 2)));
                rolls(out, rng, Items.DIAMOND, 5, 32);
                rolls(out, rng, Items.GOLDEN_APPLE, 4, 26);
                scrap[0] += rolls(out, rng, Items.NETHERITE_SCRAP, 2, 16);
            }
            case 1 -> {
                out.add(s(Items.GOLD_INGOT, between(rng, 8, 15)));
                out.add(s(Items.QUARTZ, between(rng, 17, 25)));
                out.add(s(rod, between(rng, 1, 2)));
                rolls(out, rng, Items.DIAMOND, 5, 53);
                if (rng.nextInt(100) < 21) out.add(diamondPiece(level, rng, 0, 0, 0));
                rolls(out, rng, Items.GOLDEN_APPLE, 4, 42);
                scrap[0] += rolls(out, rng, Items.NETHERITE_SCRAP, 2, 26);
                rolls(out, rng, Items.NETHERITE_UPGRADE_SMITHING_TEMPLATE, 1, 8);
            }
            case 2 -> {
                out.add(s(Items.GOLD_INGOT, between(rng, 13, 21)));
                out.add(s(Items.QUARTZ, between(rng, 25, 34)));
                out.add(s(rod, between(rng, 2, 3)));
                rolls(out, rng, Items.DIAMOND, 6, 63);
                if (rng.nextInt(100) < 32) out.add(rng.nextBoolean() ? diamondPiece(level, rng, 2, 2, 0) : diamondPiece(level, rng, 0, 0, 0));
                rolls(out, rng, Items.GOLDEN_APPLE, 4, 53);
                scrap[0] += rolls(out, rng, Items.NETHERITE_SCRAP, 3, 26);
                rolls(out, rng, Items.NETHERITE_UPGRADE_SMITHING_TEMPLATE, 1, 12);
                rolls(out, rng, Items.TOTEM_OF_UNDYING, 1, 5);
            }
            default -> {
                out.add(s(Items.GOLD_INGOT, between(rng, 17, 29)));
                out.add(s(Items.QUARTZ, between(rng, 34, 50)));
                out.add(s(rod, between(rng, 3, 4)));
                rolls(out, rng, Items.DIAMOND, 7, 68);
                if (rng.nextInt(100) < 47) out.add(diamondPiece(level, rng, 3, 3, 2));
                rolls(out, rng, Items.GOLDEN_APPLE, 4, 63);
                rolls(out, rng, Items.ENCHANTED_GOLDEN_APPLE, 1, 8);
                scrap[0] += rolls(out, rng, Items.NETHERITE_SCRAP, 3, 37);
                rolls(out, rng, Items.NETHERITE_UPGRADE_SMITHING_TEMPLATE, 1, 20);
                rolls(out, rng, Items.TOTEM_OF_UNDYING, 1, 10);
            }
        }
        Collections.shuffle(out, rng);
        return out;
    }

    /** What's in the Treasure Vault chest (shared: whoever takes it). roomScrap = scrap that came out of the room chests. */
    static List<ItemStack> vault(ServerLevel level, int d, Random rng, int roomScrap) {
        List<ItemStack> out = new ArrayList<>();
        switch (d) {
            case 0 -> {
                out.add(s(Items.DIAMOND, 4));
                out.add(s(Items.GOLD_BLOCK, 2));
                // No scrap from the rooms: one guaranteed. Otherwise a 50% chance.
                if (roomScrap == 0 || rng.nextBoolean()) out.add(s(Items.NETHERITE_SCRAP, 1));
            }
            case 1 -> {
                out.add(s(Items.DIAMOND, 8));
                out.add(s(Items.ANCIENT_DEBRIS, 1));
                out.add(diamondArmor(level, rng, 2));
                // At least one scrap from the rooms: one more. None: two.
                out.add(s(Items.NETHERITE_SCRAP, roomScrap >= 1 ? 1 : 2));
            }
            case 2 -> {
                out.add(s(Items.DIAMOND, 15));
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
                out.add(s(Items.DIAMOND, 13));
            }
        }
        return out;
    }

    private static ItemStack diamondArmor(ServerLevel level, Random rng, int prot) {
        ItemStack it = new ItemStack(DIAMOND_ARMOR[rng.nextInt(4)]);
        enchant(level, it, Enchantments.PROTECTION, prot);
        return it;
    }

    /** Vault money for the whole crew (split between them). */
    static long vaultMoneyTotal(int d) {
        return Math.round(Expedition.PRICES[d] * VAULT_MONEY);
    }
}
