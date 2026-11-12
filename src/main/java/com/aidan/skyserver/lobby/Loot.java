package com.aidan.skyserver.lobby;

import com.aidan.skyserver.FireWand;
import com.aidan.skyserver.Longbow;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;

import java.util.*;
import java.util.function.Function;

/**
 * What each mystery chest can give (5.8 overhaul). Weights are relative chances inside one chest
 * (a weight-20 prize is twice as likely as a weight-10 one).
 *
 * Every tier mixes: money, things for your Sky Island, gear for the Nether Expedition (armor, weapons,
 * totems, food), boosters, cosmetics and Key Shards. Animal spawners come from Rare/Epic chests,
 * monster spawners only from Legendary. Opening any chest also gives Key Shards (ShardShop.OPEN_SHARDS),
 * and item prizes can be salvaged for more shards instead of kept (Crates).
 *
 * Cash value (money + /sell price of items + key shop value of keys) is about a third of the key's
 * shop price for Common-Rare and ~15% for Epic/Legendary, which lean on spawners, upgrade vouchers,
 * boosters, cosmetics and expedition tickets instead. Buying keys is meant to be a money sink.
 *
 * 5.14: the BEYOND-VANILLA enchantment books (Sharpness VI-VII, Protection V-VII, Power VI-VII, Punch and
 * Knockback III-V; EnchantLevelMixin lets the anvil use them) are prizes here:
 *   Epic      - the first and middle extra levels (Sharpness VI, Protection V + VI, Power VI, Punch III + IV,
 *               Knockback III + IV), weight 2 each.
 *   Legendary - the top levels (Sharpness VII, Protection VII, Power VII, Punch V, Knockback V), weight 3 each.
 * The Legendary chest also gives the Longbow and the Fire Wand (weight 4 each; the Longbow comes with an arrow).
 * Hard / Nightmare Expedition room chests give the same books at a flat 3% (ExpeditionLoot).
 *
 * 5.17: ender pearls are out of the Rare chest (nothing replaces them; the other Rare prizes are a little
 * more likely).
 *
 * 5.18: every chest has all 4 armour pieces as separate prizes: Common iron Protection I, Uncommon iron
 * Protection III, Rare diamond Protection II, Epic diamond Protection IV + Unbreaking III that COMES WITH a random
 * first/middle extra-level book (the old Epic book list), Legendary unenchanted netherite that comes with a random
 * top-level book (the old Legendary book list). The extra-level books are no longer prizes on their own. The
 * Elytra and the Shulker Box are gone (End things only come from the End Expedition); the Elytra's place is an
 * End Expedition ticket.
 * Change anything here and restart.
 */
public final class Loot {
    public enum Kind { MONEY, KEYS, ITEM, TOKEN }

    /**
     * One prize. token = a Tokens prize string for Kind.TOKEN (null otherwise). The 8-value
     * constructor (no token) is kept so older code (SkillHalls crates) still works.
     */
    public record Prize(int weight, Kind kind, String label, long money, int keyTier, int keyCount,
                        Function<MinecraftServer, ItemStack> item, Item icon, String token) {
        public Prize(int weight, Kind kind, String label, long money, int keyTier, int keyCount,
                     Function<MinecraftServer, ItemStack> item, Item icon) {
            this(weight, kind, label, money, keyTier, keyCount, item, icon, null);
        }

        public ItemStack display(MinecraftServer server) {
            return switch (kind) {
                case MONEY -> Stacks.named(new ItemStack(icon, 1), "§6" + label);
                case KEYS -> Stacks.named(new ItemStack(Items.TRIPWIRE_HOOK, keyCount), label);
                case ITEM -> item.apply(server);
                case TOKEN -> Stacks.named(new ItemStack(icon == null ? Items.PAPER : icon, 1), label);
            };
        }
    }

    public static final List<List<Prize>> TABLES = new ArrayList<>();

    private Loot() {}

    private static Prize money(int w, long amount) {
        Item icon = amount >= 10_000 ? Items.GOLD_BLOCK : amount >= 1_000 ? Items.GOLD_INGOT : Items.GOLD_NUGGET;
        return new Prize(w, Kind.MONEY, Economy.format(amount), amount, 0, 0, null, icon);
    }

    private static Prize keys(int w, int tier, int n) {
        String label = Keys.COLORS[tier] + n + " " + Keys.NAMES[tier] + " Key" + (n == 1 ? "" : "s");
        return new Prize(w, Kind.KEYS, label, 0, tier, n, null, Items.TRIPWIRE_HOOK);
    }

    private static Prize item(int w, String label, Function<MinecraftServer, ItemStack> make) {
        return new Prize(w, Kind.ITEM, label, 0, 0, 0, make, null);
    }

    private static Prize stack(int w, Item item, int count) {
        return item(w, count + "x " + new ItemStack(item).getHoverName().getString(), s -> new ItemStack(item, count));
    }

    private static Prize token(int w, String label, String token, Item icon) {
        return new Prize(w, Kind.TOKEN, label, 0, 0, 0, null, icon, token);
    }

    private static Function<MinecraftServer, ItemStack> enchanted(Item item, Object... enchants) {
        return server -> {
            ItemStack s = new ItemStack(item);
            for (int i = 0; i + 1 < enchants.length; i += 2) {
                @SuppressWarnings("unchecked") ResourceKey<Enchantment> key = (ResourceKey<Enchantment>) enchants[i];
                Holder<Enchantment> h = server.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(key);
                s.enchant(h, (Integer) enchants[i + 1]);
            }
            return s;
        };
    }

    private static Function<MinecraftServer, ItemStack> book(Object... enchants) {
        return server -> {
            ItemStack s = new ItemStack(Items.ENCHANTED_BOOK);
            net.minecraft.world.item.enchantment.ItemEnchantments.Mutable m =
                    new net.minecraft.world.item.enchantment.ItemEnchantments.Mutable(net.minecraft.world.item.enchantment.ItemEnchantments.EMPTY);
            for (int i = 0; i + 1 < enchants.length; i += 2) {
                @SuppressWarnings("unchecked") ResourceKey<Enchantment> key = (ResourceKey<Enchantment>) enchants[i];
                m.set(server.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(key), (Integer) enchants[i + 1]);
            }
            s.set(DataComponents.STORED_ENCHANTMENTS, m.toImmutable());
            return s;
        };
    }

    /** 5.14: a one-enchantment book prize. */
    private static Prize bookPrize(int w, String label, ResourceKey<Enchantment> key, int level) {
        return item(w, label, book(key, level));
    }

    /** An 8-minute Fire Resistance potion (the Nether Expedition's best friend). */
    public static ItemStack fireRes() {
        ItemStack s = new ItemStack(Items.POTION);
        s.set(DataComponents.POTION_CONTENTS, new PotionContents(Potions.LONG_FIRE_RESISTANCE));
        return s;
    }

    private static final Item[] IRON = {Items.IRON_HELMET, Items.IRON_CHESTPLATE, Items.IRON_LEGGINGS, Items.IRON_BOOTS};
    private static final Item[] DIAMOND = {Items.DIAMOND_HELMET, Items.DIAMOND_CHESTPLATE, Items.DIAMOND_LEGGINGS, Items.DIAMOND_BOOTS};
    private static final Item[] NETHERITE = {Items.NETHERITE_HELMET, Items.NETHERITE_CHESTPLATE, Items.NETHERITE_LEGGINGS, Items.NETHERITE_BOOTS};
    private static final String[] PIECE = {"Helmet", "Chestplate", "Leggings", "Boots"};

    /** 5.18: the extra-level books that come with Epic armour (first / middle levels) and Legendary armour (top levels). */
    private static final Object[][] EPIC_BOOKS = {{Enchantments.SHARPNESS, 6}, {Enchantments.PROTECTION, 5}, {Enchantments.PROTECTION, 6},
            {Enchantments.POWER, 6}, {Enchantments.PUNCH, 3}, {Enchantments.PUNCH, 4}, {Enchantments.KNOCKBACK, 3}, {Enchantments.KNOCKBACK, 4}};
    private static final Object[][] LEGENDARY_BOOKS = {{Enchantments.SHARPNESS, 7}, {Enchantments.PROTECTION, 7}, {Enchantments.POWER, 7},
            {Enchantments.PUNCH, 5}, {Enchantments.KNOCKBACK, 5}};
    static final String WITH_EPIC_BOOK = "+ an extra-level book", WITH_LEGENDARY_BOOK = "+ a top-level book";

    /** 4 prizes, one per armour piece, each with these enchantments. */
    private static List<Prize> armour(int w, Item[] set, String material, String suffix, Object... enchants) {
        List<Prize> out = new ArrayList<>();
        for (int i = 0; i < 4; i++) out.add(item(w, material + " " + PIECE[i] + suffix, enchanted(set[i], enchants)));
        return out;
    }

    private static List<Prize> table(Object... parts) {
        List<Prize> out = new ArrayList<>();
        for (Object o : parts) {
            if (o instanceof Prize p) out.add(p);
            else if (o instanceof List<?> l) for (Object x : l) out.add((Prize) x);
        }
        return List.copyOf(out);
    }

    private static final Random BOOK_RNG = new Random();

    @SuppressWarnings("unchecked")
    private static ItemStack randomBook(MinecraftServer server, Object[][] books) {
        Object[] b = books[BOOK_RNG.nextInt(books.length)];
        return book(b[0], b[1]).apply(server);
    }

    private static Prize spawner(int w, String mob, String name) {
        return item(w, "§d" + name + " Spawner", s -> SpawnerItems.create(mob, name));
    }

    static {
        // ---- Common ($600) ----
        TABLES.add(table(
                money(24, 300), money(12, 600),
                stack(10, Items.DIAMOND, 3), stack(8, Items.GOLDEN_CARROT, 16), stack(8, Items.COOKED_BEEF, 16), stack(6, Items.BONE_MEAL, 32),
                item(6, "Iron Sword (Sharpness II)", enchanted(Items.IRON_SWORD, Enchantments.SHARPNESS, 2)),
                armour(3, IRON, "Iron", " (Protection I)", Enchantments.PROTECTION, 1),
                stack(5, Items.COW_SPAWN_EGG, 1), stack(5, Items.SHEEP_SPAWN_EGG, 1),
                token(8, "§b15 min +25% XP Booster", "booster:xp:15:25", Items.EXPERIENCE_BOTTLE),
                token(8, "§b+250 Battle Pass XP", "passxp:250", Items.PAPER),
                token(6, "§d5 Key Shards", "shards:5", Items.AMETHYST_SHARD),
                keys(3, 1, 1)));
        // ---- Uncommon ($1,800) ----
        TABLES.add(table(
                money(20, 1_000), money(8, 1_500),
                stack(10, Items.DIAMOND, 8), stack(8, Items.GOLDEN_APPLE, 4),
                item(7, "Fortune II Book", book(Enchantments.FORTUNE, 2)),
                item(7, "Efficiency IV Book", book(Enchantments.EFFICIENCY, 4)),
                armour(3, IRON, "Iron", " (Protection III)", Enchantments.PROTECTION, 3),
                item(6, "2 Fire Resistance Potions (8:00)", s -> fireRes()),
                token(8, "§630 min +25% Money Booster", "booster:money:30:25", Items.GOLD_NUGGET),
                token(6, "§fCommon cosmetic", "cosmetic:0", Items.NAME_TAG),
                token(5, "§d12 Key Shards", "shards:12", Items.AMETHYST_SHARD),
                keys(5, 0, 2), keys(4, 2, 1)));
        // ---- Rare ($5,000) ---- (5.17: ender pearls removed)
        TABLES.add(table(
                money(18, 2_500), money(6, 4_000),
                item(8, "Diamond Pickaxe (Fortune I, Efficiency III, Unbreaking II)",
                        enchanted(Items.DIAMOND_PICKAXE, Enchantments.FORTUNE, 1, Enchantments.EFFICIENCY, 3, Enchantments.UNBREAKING, 2)),
                armour(3, DIAMOND, "Diamond", " (Protection II)", Enchantments.PROTECTION, 2),
                item(6, "Diamond Sword (Sharpness IV, Unbreaking III)", enchanted(Items.DIAMOND_SWORD, Enchantments.SHARPNESS, 4, Enchantments.UNBREAKING, 3)),
                stack(6, Items.TOTEM_OF_UNDYING, 1), stack(6, Items.GOLDEN_APPLE, 8),
                item(5, "3 Fire Resistance Potions (8:00)", s -> fireRes()),
                spawner(3, "minecraft:cow", "Cow"), spawner(3, "minecraft:sheep", "Sheep"),
                token(6, "§b60 min +50% XP Booster", "booster:xp:60:50", Items.EXPERIENCE_BOTTLE),
                token(6, "§9Rare cosmetic", "cosmetic:1", Items.NAME_TAG),
                token(6, "§cEasy Nether Expedition Ticket", "ticket:0", Items.MAP),
                keys(3, 3, 1), keys(5, 1, 2)));
        // ---- Epic ($25,000) ---- (5.18: armour + an extra-level book; End ticket instead of the Elytra; no Shulker Box)
        TABLES.add(table(
                money(14, 10_000),
                token(7, "§5End Expedition Ticket", "endticket", Items.END_STONE),
                stack(7, Items.NETHERITE_INGOT, 1),
                armour(4, DIAMOND, "Diamond", " (Protection IV, Unbreaking III) " + WITH_EPIC_BOOK, Enchantments.PROTECTION, 4, Enchantments.UNBREAKING, 3),
                stack(6, Items.ENCHANTED_GOLDEN_APPLE, 2),
                spawner(3, "minecraft:pig", "Pig"), spawner(3, "minecraft:chicken", "Chicken"),
                token(8, "§dIsland Upgrade Voucher (25% off)", "upgrade_voucher", Items.FILLED_MAP),
                token(6, "§aIsland Theme", "theme", Items.GRASS_BLOCK),
                token(6, "§9Rare cosmetic", "cosmetic:1", Items.NAME_TAG),
                token(5, "§cHard Nether Expedition Ticket", "ticket:2", Items.MAP),
                token(5, "§660 min +50% Money Booster", "booster:money:60:50", Items.GOLD_INGOT),
                keys(2, 4, 1), keys(5, 2, 2)));
        // ---- Legendary ($60,000) ---- (the only chest with monster spawners; 5.18: netherite armour + a top-level book)
        TABLES.add(table(
                money(12, 35_000),
                item(8, "Netherite Pickaxe (Fortune III, Efficiency V, Unbreaking III)",
                        enchanted(Items.NETHERITE_PICKAXE, Enchantments.FORTUNE, 3, Enchantments.EFFICIENCY, 5, Enchantments.UNBREAKING, 3)),
                item(7, "Mending Book", book(Enchantments.MENDING, 1)),
                armour(4, NETHERITE, "Netherite", " " + WITH_LEGENDARY_BOOK),
                item(6, "Netherite Sword (Sharpness V, Unbreaking III, Looting III)",
                        enchanted(Items.NETHERITE_SWORD, Enchantments.SHARPNESS, 5, Enchantments.UNBREAKING, 3, Enchantments.LOOTING, 3)),
                stack(6, Items.BEACON, 1),
                spawner(4, "minecraft:zombie", "Zombie"), spawner(4, "minecraft:skeleton", "Skeleton"),
                spawner(3, "minecraft:spider", "Spider"), spawner(2, "minecraft:cave_spider", "Cave Spider"),
                spawner(3, "minecraft:creeper", "Creeper"), spawner(2, "minecraft:blaze", "Blaze"),
                token(6, "§d§lServer Money Booster (+50%, 60 min)", "booster:server:60:50", Items.NETHER_STAR),
                token(6, "§6Legendary cosmetic", "cosmetic:2", Items.NAME_TAG),
                token(5, "§4Nightmare Nether Expedition Ticket", "ticket:3", Items.MAP),
                item(4, "Longbow (3.5s full-charge beam)", Longbow::create),
                item(4, "Fire Wand (fireball launcher)", s -> FireWand.create()),
                keys(4, 3, 2)));
    }

    /** 5.18: extra items, including the random book that comes with Epic / Legendary armour. */
    public static List<ItemStack> extras(Prize p, MinecraftServer server) {
        if (p.label().endsWith(WITH_EPIC_BOOK)) return List.of(randomBook(server, EPIC_BOOKS));
        if (p.label().endsWith(WITH_LEGENDARY_BOOK)) return List.of(randomBook(server, LEGENDARY_BOOKS));
        return extras(p);
    }

    /** Extra items that come with a prize (more potions, an arrow for the Longbow). */
    public static List<ItemStack> extras(Prize p) {
        if (p.label().startsWith("2 Fire Resistance")) return List.of(fireRes());
        if (p.label().startsWith("3 Fire Resistance")) return List.of(fireRes(), fireRes());
        if (p.label().startsWith("Longbow")) return List.of(new ItemStack(Items.ARROW));
        return List.of();
    }

    public static Prize roll(int tier, Random rng) {
        List<Prize> table = TABLES.get(tier);
        int total = 0;
        for (Prize p : table) total += p.weight();
        int r = rng.nextInt(total);
        for (Prize p : table) {
            r -= p.weight();
            if (r < 0) return p;
        }
        return table.get(table.size() - 1);
    }

    public static double chance(int tier, Prize p) {
        int total = 0;
        for (Prize q : TABLES.get(tier)) total += q.weight();
        return 100.0 * p.weight() / total;
    }
}
