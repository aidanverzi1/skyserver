package com.aidan.skyserver.island;

import com.aidan.skyserver.lobby.Economy;
import com.aidan.skyserver.lobby.Keys;
import com.aidan.skyserver.lobby.Levels;
import com.aidan.skyserver.lobby.Quests;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Island challenges: bring the items (they're taken) or reach a server level, get a reward.
 * Grouped in tiers that unlock with the island's server level (the highest level of anyone on the
 * island - see Islands.Island.level()). Item challenges can be repeated once a day for a quarter of
 * the money (no XP or keys), so they stay worth doing without printing money.
 */
public final class Challenges {

    public record Need(Predicate<ItemStack> test, int count, String label, Item icon) {}

    /** islandLevel = the server level needed, for level goals (the name is kept from older versions). */
    public record Challenge(String id, String name, int tier, List<Need> needs, int islandLevel,
                            long money, int xp, int keyTier, Item icon) {
        boolean isLevelGoal() {
            return needs.isEmpty();
        }
    }

    /** Server level needed to unlock each tier. */
    public static final int[] TIER_LEVEL = {0, 10, 25, 45};
    public static final String[] TIER_NAMES = {"Starter", "Settler", "Baron", "Legend"};

    public static final List<Challenge> ALL = new ArrayList<>();

    private static Need need(Item item, int count, String label) {
        return new Need(s -> s.is(item), count, label, item);
    }

    private static void add(String id, String name, int tier, long money, int xp, int key, Item icon, Need... needs) {
        ALL.add(new Challenge(id, name, tier, List.of(needs), 0, money, xp, key, icon));
    }

    private static void level(String id, String name, int tier, int serverLevel, long money, int xp, int key, Item icon) {
        ALL.add(new Challenge(id, name, tier, List.of(), serverLevel, money, xp, key, icon));
    }

    static {
        // Starter
        add("cobble", "Cobble Collector", 0, 300, 40, -1, Items.COBBLESTONE, need(Items.COBBLESTONE, 64, "64 Cobblestone"));
        add("logs", "Lumberjack", 0, 250, 40, -1, Items.OAK_LOG, new Need(s -> s.is(ItemTags.LOGS), 32, "32 Logs (any wood)", Items.OAK_LOG));
        add("wheat", "Farmer", 0, 300, 40, -1, Items.WHEAT, need(Items.WHEAT, 32, "32 Wheat"));
        add("bread", "Baker", 0, 300, 40, -1, Items.BREAD, need(Items.BREAD, 16, "16 Bread"));
        add("cane", "Sugar Rush", 0, 300, 40, -1, Items.SUGAR_CANE, need(Items.SUGAR_CANE, 32, "32 Sugar Cane"));
        add("iron", "Smelter", 0, 500, 60, -1, Items.IRON_INGOT, need(Items.IRON_INGOT, 16, "16 Iron Ingots"));
        add("bricks", "Stonemason", 0, 500, 60, -1, Items.STONE_BRICKS, need(Items.STONE_BRICKS, 64, "64 Stone Bricks"));
        add("melon", "Melon Grower", 0, 400, 50, -1, Items.MELON_SLICE, need(Items.MELON_SLICE, 64, "64 Melon Slices"));
        // Settler (ids kept so islands that already finished them stay finished)
        level("lvl5", "Settler", 1, 10, 1_000, 120, 0, Items.GRASS_BLOCK);
        add("miner", "Miner", 1, 800, 80, -1, Items.COAL, need(Items.COAL, 32, "32 Coal"), need(Items.REDSTONE, 16, "16 Redstone"));
        add("gold", "Goldsmith", 1, 900, 80, -1, Items.GOLD_INGOT, need(Items.GOLD_INGOT, 16, "16 Gold Ingots"));
        add("diamonds", "Gem Hunter", 1, 1_500, 120, -1, Items.DIAMOND, need(Items.DIAMOND, 5, "5 Diamonds"));
        add("glass", "Glassblower", 1, 600, 60, -1, Items.GLASS, need(Items.GLASS, 64, "64 Glass"));
        add("pumpkin", "Pumpkin Patch", 1, 600, 60, -1, Items.PUMPKIN, need(Items.PUMPKIN, 16, "16 Pumpkins"));
        add("library", "Librarian", 1, 1_000, 100, -1, Items.BOOKSHELF, need(Items.BOOKSHELF, 8, "8 Bookshelves"));
        // Baron
        level("lvl20", "Baron", 2, 25, 5_000, 300, 1, Items.BEACON);
        add("dblocks", "Treasure Vault", 2, 5_000, 250, -1, Items.DIAMOND_BLOCK, need(Items.DIAMOND_BLOCK, 3, "3 Diamond Blocks"));
        add("emeralds", "Emerald Hoard", 2, 3_000, 200, -1, Items.EMERALD, need(Items.EMERALD, 10, "10 Emeralds"));
        add("lapis", "Lapis Collector", 2, 2_000, 150, -1, Items.LAPIS_BLOCK, need(Items.LAPIS_BLOCK, 16, "16 Lapis Blocks"));
        add("ironblocks", "Ironworks", 2, 2_500, 150, -1, Items.IRON_BLOCK, need(Items.IRON_BLOCK, 16, "16 Iron Blocks"));
        // Legend
        level("lvl50", "Island Legend", 3, 45, 15_000, 600, 2, Items.NETHER_STAR);
        add("goldblocks", "Golden Island", 3, 8_000, 300, -1, Items.GOLD_BLOCK, need(Items.GOLD_BLOCK, 32, "32 Gold Blocks"));
        add("emblocks", "Emerald Empire", 3, 12_000, 400, -1, Items.EMERALD_BLOCK, need(Items.EMERALD_BLOCK, 10, "10 Emerald Blocks"));
    }

    private Challenges() {}

    public static int unlockedTier(int serverLevel) {
        int t = 0;
        for (int i = 0; i < TIER_LEVEL.length; i++) if (serverLevel >= TIER_LEVEL[i]) t = i;
        return t;
    }

    /** Tries to complete a challenge for this player's island. Returns a message. */
    public static String complete(ServerPlayer p, Islands.Island island, Challenge ch) {
        MinecraftServer server = p.level().getServer();
        int level = island.level();
        if (ch.tier() > unlockedTier(level)) return "§cReach server level " + TIER_LEVEL[ch.tier()] + " to unlock " + TIER_NAMES[ch.tier()] + " challenges. §7(/level)";
        boolean done = island.completed.contains(ch.id());
        String today = LocalDate.now().toString();
        if (ch.isLevelGoal()) {
            if (done) return "§7You've already completed this one.";
            if (level < ch.islandLevel()) return "§cYour island's best server level is " + level + "; this needs level " + ch.islandLevel() + ".";
        } else {
            if (done && today.equals(island.repeatedOn.get(ch.id()))) return "§7Already repeated today. Come back tomorrow!";
            Inventory inv = p.getInventory();
            for (Need n : ch.needs()) if (count(inv, n.test()) < n.count()) return "§cYou need " + n.label() + " in your inventory.";
            for (Need n : ch.needs()) take(inv, n.test(), n.count());
        }
        long money = done ? ch.money() / 4 : ch.money();
        Economy.add(server, p.getUUID(), p.getName().getString(), money);
        if (!done) {
            Levels.addXp(server, p, ch.xp());
            if (ch.keyTier() >= 0) Keys.reward(server, p.getUUID(), ch.keyTier(), 1, "(challenge: " + ch.name() + ")");
            island.completed.add(ch.id());
        } else {
            island.repeatedOn.put(ch.id(), today);
        }
        Islands.save();
        p.level().playSound(null, p.getX(), p.getY(), p.getZ(), net.minecraft.sounds.SoundEvents.PLAYER_LEVELUP, net.minecraft.sounds.SoundSource.PLAYERS, 0.8f, 1.3f);
        Quests.progress(p, "challenge", 1);
        return (done ? "§aRepeated " : "§a§lCHALLENGE COMPLETE: §a") + ch.name() + " §7(+" + Economy.format(money) + (done ? "" : ", " + ch.xp() + " XP") + ")";
    }

    private static int count(Inventory inv, Predicate<ItemStack> test) {
        int n = 0;
        for (int i = 0; i < inv.getContainerSize(); i++) if (test.test(inv.getItem(i))) n += inv.getItem(i).getCount();
        return n;
    }

    private static void take(Inventory inv, Predicate<ItemStack> test, int amount) {
        for (int i = 0; i < inv.getContainerSize() && amount > 0; i++) {
            ItemStack s = inv.getItem(i);
            if (!test.test(s)) continue;
            int t = Math.min(amount, s.getCount());
            s.shrink(t);
            amount -= t;
        }
    }
}
