package com.aidan.skyserver.pet;

import com.aidan.skyserver.Safe;
import com.aidan.skyserver.island.Islands;
import com.aidan.skyserver.lobby.Economy;
import com.aidan.skyserver.lobby.Keys;
import com.aidan.skyserver.lobby.Lobby;
import com.aidan.skyserver.lobby.Mailbox;
import com.aidan.skyserver.lobby.Stacks;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Random;
import java.util.UUID;

/**
 * 5.24: the chicken's items and where they come from.
 *
 *  - LITTLE TREAT (cookie): gets a downed chicken back up at 75% health. 1 for finishing all 3 daily quests,
 *    1 for your first expedition clear of the day (any expedition) = 2 a day. Hold up to 8. Tradeable.
 *  - LITTLE SNACK (pumpkin pie): +10% health each, 0.25 s per snack, no other limit (not while downed).
 *    5.24b: grown on Little Snack Planters (see Planters).
 *  - NEST EGG (egg): laid once a day on your island. Right-click: 55% a small bundle of items, 35% $25-100,
 *    8% a Common key, 2% an Uncommon key.
 *  - FEATHER PLATE (-18% damage, 12,000 durability) and GILDED FEATHER PLATE (-25%, 20,000): uncraftable chicken
 *    armour. 1% in Epic / Legendary key chests, Old Ves (10,000 / 25,000 City Cash, 1 a week each, after a
 *    Hard / Nightmare escape), End Expedition wave 20 / wave 40 (1 of each per player per week). Only hits from
 *    the monster the chicken is distracting wear it down (1 durability per point of damage).
 */
public final class PetItems {
    private PetItems() {}

    public static final String TREAT = "pet_treat", SNACK = "pet_snack", EGG = "pet_egg",
            FEATHER = "pet_plate_feather", GILDED = "pet_plate_gilded";
    /** Wren's quest step that gives the first Little Snack Planter (16 = the first step of chapter 2). */
    public static final int SNACK_PLANT_STEP = 20; // 5.27: the Skywars-win quest is #20 now (was 16)
    public static final int MAX_TREATS = 8;
    public static final int SNACKS_PER_HARVEST = 3;
    public static final int FEATHER_DURABILITY = 12_000, GILDED_DURABILITY = 20_000;
    public static final double FEATHER_REDUCTION = 0.18, GILDED_REDUCTION = 0.25;

    static final ZoneId ET = ZoneId.of("America/New_York");
    private static final Random RNG = new Random();

    static String today() {
        return LocalDate.now(ET).toString();
    }

    // =====================================================================
    // The items
    // =====================================================================

    public static ItemStack treat(int n) {
        ItemStack s = Stacks.tag(new ItemStack(Items.COOKIE, n), TREAT);
        s.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
        return Stacks.named(s, "§d§lLittle Treat", "§7Right-click your §fdowned §7chicken:", "§7it gets back up with §a75% §7health.", "§8Hold up to 8. Tradeable.");
    }

    public static ItemStack snack(int n) {
        return Stacks.named(Stacks.tag(new ItemStack(Items.PUMPKIN_PIE, n), SNACK), "§aLittle Snack", "§7Right-click your chicken: §a+10% §7health.", "§8(Not while it's downed.)");
    }

    public static ItemStack nestEgg() {
        return Stacks.named(Stacks.tag(new ItemStack(Items.EGG), EGG), "§eNest Egg", "§7Laid by your chicken.", "§7Right-click to crack it open!");
    }

    public static ItemStack featherPlate() {
        return plate(false, FEATHER_DURABILITY);
    }

    public static ItemStack gildedPlate() {
        return plate(true, GILDED_DURABILITY);
    }

    /** A plate with this much durability left (kept on the item, so it can be traded). */
    static ItemStack plate(boolean gilded, int dur) {
        ItemStack s = new ItemStack(gilded ? Items.GOLDEN_CHESTPLATE : Items.LEATHER_CHESTPLATE);
        CompoundTag t = new CompoundTag();
        t.putString("skyserver", gilded ? GILDED : FEATHER);
        t.putInt("pet_dur", dur);
        s.set(DataComponents.CUSTOM_DATA, CustomData.of(t));
        s.set(DataComponents.MAX_STACK_SIZE, 1);
        s.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
        int max = gilded ? GILDED_DURABILITY : FEATHER_DURABILITY;
        Stacks.named(s, gilded ? "§6§lGilded Feather Plate" : "§e§lFeather Plate",
                "§7Chicken armour: §f-" + (gilded ? "25" : "18") + "% §7damage.", "§7Right-click your chicken to put it on.",
                "§7Durability: §f" + String.format("%,d", dur) + " §8/ " + String.format("%,d", max), "§8Only monster hits wear it down.");
        return s;
    }

    static String plateKind(ItemStack s) {
        if (Stacks.is(s, FEATHER)) return "feather";
        if (Stacks.is(s, GILDED)) return "gilded";
        return null;
    }

    static int plateDur(ItemStack s) {
        CustomData cd = s.get(DataComponents.CUSTOM_DATA);
        if (cd == null) return 0;
        boolean gilded = Stacks.is(s, GILDED);
        return cd.copyTag().getIntOr("pet_dur", gilded ? GILDED_DURABILITY : FEATHER_DURABILITY);
    }

    static double reduction(String armor) {
        return "gilded".equals(armor) ? GILDED_REDUCTION : "feather".equals(armor) ? FEATHER_REDUCTION : 0;
    }

    static int maxDur(String armor) {
        return "gilded".equals(armor) ? GILDED_DURABILITY : FEATHER_DURABILITY;
    }

    static String armorName(String armor) {
        return "gilded".equals(armor) ? "§6Gilded Feather Plate" : "feather".equals(armor) ? "§eFeather Plate" : "§7none";
    }

    /** Puts an item in the player's island inventory if they're in an island world, otherwise in their island mailbox. */
    public static void give(ServerPlayer p, ItemStack s) {
        if (s.isEmpty()) return;
        if (Lobby.isIsland(p.level())) {
            Inventory inv = p.getInventory();
            if (!inv.add(s) || !s.isEmpty()) p.drop(s, false, net.minecraft.util.Prediction.SERVER_ONLY);
        } else {
            Mailbox.send(p.level().getServer(), p.getUUID(), s);
            Lobby.msg(p, "§7(Sent to your Sky Island - §e/claim §7there.)");
        }
    }

    static int countTagged(ServerPlayer p, String tag) {
        Inventory inv = p.getInventory();
        int n = 0;
        for (int i = 0; i < inv.getContainerSize(); i++) if (Stacks.is(inv.getItem(i), tag)) n += inv.getItem(i).getCount();
        return n;
    }

    // =====================================================================
    // Where they come from
    // =====================================================================

    /** All 3 daily quests done (Quests): 1 Little Treat, once a day. */
    public static void questTreat(ServerPlayer p) {
        Companion.Pet pet = Companion.PETS.get(p.getUUID());
        if (pet == null) return;
        String day = today();
        if (day.equals(pet.treatQuestDay)) return;
        pet.treatQuestDay = day;
        Companion.dirty();
        giveTreat(p, pet, "all 3 daily quests");
    }

    /** Any expedition clear (Charts.stamp): the first one each day gives 1 Little Treat. */
    public static void clearTreat(ServerPlayer p) {
        Companion.Pet pet = Companion.PETS.get(p.getUUID());
        if (pet == null) return;
        String day = today();
        if (day.equals(pet.treatClearDay)) return;
        pet.treatClearDay = day;
        Companion.dirty();
        giveTreat(p, pet, "your first expedition clear today");
    }

    private static void giveTreat(ServerPlayer p, Companion.Pet pet, String why) {
        int have = Lobby.isIsland(p.level()) ? countTagged(p, TREAT) : pet.treatsKnown;
        if (have >= MAX_TREATS) {
            Lobby.msg(p, "§d§lLittle Treat §7(" + why + ") §8- you already hold " + MAX_TREATS + ", the most you can carry.");
            return;
        }
        give(p, treat(1));
        pet.treatsKnown = have + 1;
        Lobby.msg(p, "§d§l+1 Little Treat §7(" + why + ") §8- revives your chicken when it's downed.");
    }

    /** Captain Wren's quest (step SNACK_PLANT_STEP): the first Little Snack Planter. */
    public static void wrenPlant(ServerPlayer p) {
        Planters.wrenGift(p);
    }

    /** End Expedition wave 20 / 40 milestone (EndRewards.milestone): one plate of each kind per player per week. */
    public static void endWave(ServerPlayer p, int wave) {
        Companion.Pet pet = Companion.PETS.get(p.getUUID());
        if (pet == null) return;
        int week = com.aidan.skyserver.expedition.CityMarket.week();
        if (wave == 20) {
            if (pet.plate20Week == week) {
                Lobby.msg(p, "§7(You already got this week's Feather Plate from wave 20.)");
                return;
            }
            pet.plate20Week = week;
            give(p, featherPlate());
            Lobby.msg(p, "§e§lFEATHER PLATE! §7Chicken armour for reaching wave 20 (1 a week).");
        } else if (wave == 40) {
            if (pet.plate40Week == week) {
                Lobby.msg(p, "§7(You already got this week's Gilded Feather Plate from wave 40.)");
                return;
            }
            pet.plate40Week = week;
            give(p, gildedPlate());
            Lobby.msg(p, "§6§lGILDED FEATHER PLATE! §7Chicken armour for reaching wave 40 (1 a week).");
        }
        Companion.dirty();
    }

    // ---- Nest eggs ----

    private static void crack(ServerPlayer p, ItemStack held) {
        held.shrink(1);
        MinecraftServer server = p.level().getServer();
        int r = RNG.nextInt(100);
        String what;
        if (r < 2) {
            Keys.reward(server, p.getUUID(), 1, 1, "(Nest Egg)");
            what = "an §aUncommon Key";
        } else if (r < 10) {
            Keys.reward(server, p.getUUID(), 0, 1, "(Nest Egg)");
            what = "a §fCommon Key";
        } else if (r < 45) {
            long cash = 25 + RNG.nextInt(76);
            Economy.add(server, p.getUUID(), p.getName().getString(), cash);
            what = "§6" + Economy.format(cash);
        } else {
            // misc bundle: {weight, item index}
            int[] w = {14, 14, 14, 12, 10, 8, 2, 13, 13};
            int total = 0;
            for (int x : w) total += x;
            int k = RNG.nextInt(total), i = 0;
            while (k >= w[i]) k -= w[i++];
            ItemStack s = switch (i) {
                case 0 -> new ItemStack(Items.BONE_MEAL, 8);
                case 1 -> new ItemStack(Items.STRING, 4);
                case 2 -> new ItemStack(Items.FEATHER, 6);
                case 3 -> new ItemStack(Items.IRON_NUGGET, 9);
                case 4 -> new ItemStack(Items.SLIME_BALL, 2);
                case 5 -> new ItemStack(Items.LEAD);
                case 6 -> new ItemStack(Items.NAME_TAG);
                case 7 -> new ItemStack(Items.LEATHER, 4);
                default -> new ItemStack(Items.FLINT, 6);
            };
            what = "§f" + s.getCount() + "x " + s.getHoverName().getString();
            give(p, s);
        }
        p.level().playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.CHICKEN_EGG, SoundSource.PLAYERS, 1f, 1.3f);
        Lobby.msg(p, "§eYou crack the Nest Egg: " + what + "§e!");
    }

    // =====================================================================
    // Listeners
    // =====================================================================

    static void init() {
        UseItemCallback.EVENT.register((player, level, hand) -> {
            if (level.isClientSide() || !(player instanceof ServerPlayer sp)) return InteractionResult.PASS;
            ItemStack held = player.getItemInHand(hand);
            if (Stacks.is(held, EGG)) {
                Safe.run("nest egg", () -> crack(sp, held));
                return InteractionResult.SUCCESS;
            }
            // Treats and snacks are for the chicken (no eating them yourself).
            if (Stacks.is(held, TREAT) || Stacks.is(held, SNACK)) {
                Lobby.msg(sp, "§7That's for your chicken - right-click it with this (§e/pet§7 calls it).");
                return InteractionResult.FAIL;
            }
            return InteractionResult.PASS;
        });
        UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
            if (level.isClientSide() || !(player instanceof ServerPlayer sp)) return InteractionResult.PASS;
            ItemStack held = player.getItemInHand(hand);
            if (Stacks.is(held, TREAT) || Stacks.is(held, SNACK) || Stacks.is(held, EGG)) return InteractionResult.FAIL;
            return InteractionResult.PASS;
        });
    }

    /** Lore line refresh for a plate's durability (used when a plate comes off the chicken). */
    static ItemStack plateWithDur(String armor, int dur) {
        return plate("gilded".equals(armor), Math.max(1, dur));
    }
}
