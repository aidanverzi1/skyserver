package com.aidan.skyserver.bedwars;

import com.aidan.skyserver.lobby.LockedMenu;
import com.aidan.skyserver.lobby.Stacks;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.enchantment.Enchantments;

import java.util.*;

/**
 * The item shop (right-click the Item Shop villager). Tabs along the top, items below; each shows
 * its price in iron, gold or emeralds. Prices are in BwRules. Bots buy through {@link #buy} too.
 */
public final class BwShop extends LockedMenu {
    private static final int[] ITEM_SLOTS = {19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34, 37, 38, 39, 40, 41, 42, 43, 46, 47, 48, 49, 50, 51, 52};
    private BwRules.Category tab = BwRules.Category.QUICK;
    private final List<String> shown = new ArrayList<>();

    private BwShop(int id, Inventory inv, SimpleContainer box, ServerPlayer viewer) {
        super(id, inv, box, 6, viewer);
        draw();
    }

    public static void open(ServerPlayer p) {
        LockedMenu.open(p, "§8Item Shop", 6, BwShop::new);
    }

    private static final Item[] TAB_ICONS = {Items.NETHER_STAR, Items.TERRACOTTA, Items.GOLDEN_SWORD, Items.CHAINMAIL_BOOTS,
            Items.STONE_PICKAXE, Items.BOW, Items.BREWING_STAND, Items.TNT, Items.AMETHYST_SHARD};

    private void draw() {
        for (int i = 0; i < box.getContainerSize(); i++) box.setItem(i, ItemStack.EMPTY);
        BwRules.Category[] cats = BwRules.Category.values();
        for (int i = 0; i < cats.length; i++) {
            ItemStack icon = Stacks.named(new ItemStack(TAB_ICONS[i]), (cats[i] == tab ? "§a§l" : "§e") + cats[i].display, "§7Click to view");
            box.setItem(i, icon);
            box.setItem(9 + i, Stacks.named(new ItemStack(Items.STAINED_GLASS_PANE.pick(cats[i] == tab ? DyeColor.LIME : DyeColor.GRAY)), " "));
        }
        shown.clear();
        if (tab == BwRules.Category.QUICK) shown.addAll(BwRules.QUICK_BUY);
        else for (BwRules.Offer o : BwRules.OFFERS) if (o.cat() == tab) shown.add(o.id());
        int[] res = resources(viewer);
        for (int i = 0; i < shown.size() && i < ITEM_SLOTS.length; i++) {
            box.setItem(ITEM_SLOTS[i], icon(viewer, shown.get(i), res));
        }
        frame(Stacks.named(new ItemStack(Items.STAINED_GLASS_PANE.pick(DyeColor.BLACK)), " "));
        broadcastChanges();
    }

    @Override
    protected void onClick(int slot, int button, boolean shift) {
        BwRules.Category[] cats = BwRules.Category.values();
        if (slot >= 0 && slot < cats.length) {
            tab = cats[slot];
            draw();
            return;
        }
        for (int i = 0; i < ITEM_SLOTS.length && i < shown.size(); i++) {
            if (ITEM_SLOTS[i] != slot) continue;
            String err = buy(viewer, shown.get(i));
            if (err != null) {
                BedwarsGame.msg(viewer, "§c" + err);
                viewer.level().playSound(null, viewer.getX(), viewer.getY(), viewer.getZ(), SoundEvents.VILLAGER_NO, SoundSource.PLAYERS, 0.6f, 1f);
            } else {
                viewer.level().playSound(null, viewer.getX(), viewer.getY(), viewer.getZ(), SoundEvents.NOTE_BLOCK_PLING.value(), SoundSource.PLAYERS, 0.6f, 2f);
            }
            draw();
            return;
        }
    }

    // =====================================================================
    // Prices and buying (also used by bots)
    // =====================================================================

    /** Iron, gold, diamonds, emeralds in a player's inventory. */
    public static int[] resources(ServerPlayer p) {
        int[] r = new int[4];
        Inventory inv = p.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            int k = BedwarsGame.resource(inv.getItem(i));
            if (k >= 0) r[k] += inv.getItem(i).getCount();
        }
        return r;
    }

    static boolean take(ServerPlayer p, int currency, int amount) {
        if (resources(p)[currency] < amount) return false;
        Inventory inv = p.getInventory();
        int left = amount;
        for (int i = inv.getContainerSize() - 1; i >= 0 && left > 0; i--) {
            ItemStack s = inv.getItem(i);
            if (BedwarsGame.resource(s) != currency) continue;
            int n = Math.min(left, s.getCount());
            s.shrink(n);
            left -= n;
        }
        return true;
    }

    /** {currency, cost} of an offer for this player right now, or null if they can't buy it (maxed, sold out...). */
    public static int[] price(ServerPlayer p, String id) {
        BwPlayer bp = BedwarsGame.bp(p.getUUID());
        BwRules.Offer o = BwRules.offer(id);
        if (bp == null || o == null) return null;
        switch (id) {
            case "pickaxe" -> {
                return bp.pick >= 4 ? null : BwRules.PICKAXE_COST[bp.pick];
            }
            case "axe" -> {
                return bp.axe >= 4 ? null : BwRules.AXE_COST[bp.axe];
            }
            case "shears" -> {
                if (bp.shears) return null;
            }
            case "blanket" -> {
                if (BedwarsGame.teams.get(bp.team).blanketBought) return null;
            }
            case "bed_bell" -> {
                if (BedwarsGame.teams.get(bp.team).bedBell) return null;
            }
            default -> {
                int at = BwRules.armorTier(id);
                if (at >= 0 && bp.armor >= at) return null;
            }
        }
        return new int[]{o.currency(), BwRules.cost(o)};
    }

    /** Buys one offer. Returns null on success, or what went wrong. */
    /**
     * The transaction processor: buys one offer for a player or bot (the menu and the bots both come
     * here). Returns null on success, or what went wrong. Every attempt is written to the match log
     * (and streamed to the console for bots when /bw botlog is on).
     */
    public static String buy(ServerPlayer p, String id) {
        BwPlayer bp = BedwarsGame.bp(p.getUUID());
        BwRules.Offer o = BwRules.offer(id);
        String who = p.getName().getString();
        boolean bot = bp != null && bp.bot;
        if (bp == null || o == null || !BedwarsGame.alive(p.getUUID())) {
            BwBotLog.transaction(who, bot, "SHOP", id, "-", resources(p), false, o == null ? "no such item" : "not alive / not in a match");
            return "You can't buy that now.";
        }
        int[] pr = price(p, id);
        if (pr == null) {
            String why = id.equals("blanket") ? "Your team already has its Blanket." : id.equals("bed_bell") ? "Your team already has a Bed Bell." : "You already have that (or better).";
            BwBotLog.transaction(who, bot, "SHOP", id, "-", resources(p), false, "already owned");
            return why;
        }
        String costText = pr[1] + " " + BwRules.CURRENCY[pr[0]];
        if (!take(p, pr[0], pr[1])) {
            int have = resources(p)[pr[0]];
            String why = "You need " + (pr[1] - have) + " more " + BwRules.CURRENCY[pr[0]] + ".";
            BwBotLog.transaction(who, bot, "SHOP", id, costText, resources(p), false, why);
            return why;
        }
        bp.active = true;
        deliver(p, bp, id, o);
        BwBotLog.transaction(who, bot, "SHOP", id, costText, resources(p), true, "");
        return null;
    }

    private static void deliver(ServerPlayer p, BwPlayer bp, String id, BwRules.Offer o) {
        int at = BwRules.armorTier(id);
        if (at >= 0) {
            bp.armor = at;
            BedwarsGame.equipArmor(p, bp);
            return;
        }
        int st = BwRules.swordTier(id);
        if (st >= 0) {
            Inventory inv = p.getInventory();
            int slot = -1;
            for (int i = 0; i < 36; i++) if (inv.getItem(i).is(Items.WOODEN_SWORD)) {
                inv.setItem(i, ItemStack.EMPTY);
                if (slot < 0) slot = i;
            }
            bp.sword = Math.max(bp.sword, st);
            ItemStack sword = BedwarsGame.sword(st, bp.team);
            if (slot >= 0) inv.setItem(slot, sword);
            else BedwarsGame.give(p, sword);
            return;
        }
        switch (id) {
            case "pickaxe" -> {
                replaceTool(p, BedwarsGame.PICKS, BedwarsGame.pickaxe(bp.pick + 1));
                bp.pick++;
            }
            case "axe" -> {
                replaceTool(p, BedwarsGame.AXES, BedwarsGame.axe(bp.axe + 1, bp.team));
                bp.axe++;
            }
            case "shears" -> {
                bp.shears = true;
                BedwarsGame.give(p, BedwarsGame.unbreakable(new ItemStack(Items.SHEARS)));
            }
            case "blanket" -> {
                BedwarsGame.teams.get(bp.team).blanketBought = true;
                BedwarsGame.give(p, item(id, bp.team));
            }
            case "bed_bell" -> {
                BedwarsGame.teams.get(bp.team).bedBell = true;
                BedwarsGame.teamMsg(bp.team, "§e" + p.getName().getString() + " hung a §6Bed Bell§e: you'll hear it if an enemy breaks blocks near your bed.");
            }
            default -> BedwarsGame.give(p, item(id, bp.team));
        }
    }

    private static void replaceTool(ServerPlayer p, Item[] tiers, ItemStack better) {
        Inventory inv = p.getInventory();
        for (int i = 0; i < 36; i++) {
            ItemStack s = inv.getItem(i);
            for (Item t : tiers) if (t != null && s.is(t)) {
                inv.setItem(i, better);
                return;
            }
        }
        BedwarsGame.give(p, better);
    }

    /** The item an offer gives (a full stack of its amount). */
    static ItemStack item(String id, BwTeam team) {
        BwRules.Offer o = BwRules.offer(id);
        int n = o == null ? 1 : o.amount();
        DyeColor dye = BedwarsGame.dye(team);
        return switch (id) {
            case "wool" -> new ItemStack(Items.WOOL.pick(dye), n);
            case "clay" -> new ItemStack(Items.DYED_TERRACOTTA.pick(dye), n);
            case "glass" -> Stacks.named(new ItemStack(Items.STAINED_GLASS.pick(dye), n), "§bShatterproof Glass", "§7Explosions can't break it.");
            case "endstone" -> new ItemStack(Items.END_STONE, n);
            case "ladder" -> new ItemStack(Items.LADDER, n);
            case "wood" -> new ItemStack(Items.OAK_PLANKS, n);
            case "obsidian" -> new ItemStack(Items.OBSIDIAN, n);
            case "kb_stick" -> {
                ItemStack s = Stacks.named(new ItemStack(Items.STICK), "§cKnockback Stick");
                s.enchant(BedwarsGame.ench(Enchantments.KNOCKBACK), 1);
                yield s;
            }
            case "arrows" -> new ItemStack(Items.ARROW, n);
            case "bow" -> BedwarsGame.unbreakable(new ItemStack(Items.BOW));
            case "bow_power" -> {
                ItemStack s = BedwarsGame.unbreakable(new ItemStack(Items.BOW));
                s.enchant(BedwarsGame.ench(Enchantments.POWER), 1);
                yield s;
            }
            case "bow_punch" -> {
                ItemStack s = BedwarsGame.unbreakable(new ItemStack(Items.BOW));
                s.enchant(BedwarsGame.ench(Enchantments.POWER), 1);
                s.enchant(BedwarsGame.ench(Enchantments.PUNCH), 1);
                yield s;
            }
            case "speed_potion" -> potion("§bSpeed II Potion", new MobEffectInstance(MobEffects.SPEED, 900, 1), 0x7CAFC6);
            case "jump_potion" -> potion("§aJump V Potion", new MobEffectInstance(MobEffects.JUMP_BOOST, 900, 4), 0x22FF4C);
            case "invis_potion" -> potion("§7Invisibility Potion", new MobEffectInstance(MobEffects.INVISIBILITY, 600, 0), 0x7F8392);
            case "golden_apple" -> new ItemStack(Items.GOLDEN_APPLE, n);
            case "fireball" -> Stacks.tag(Stacks.named(new ItemStack(Items.FIRE_CHARGE, n), "§6Fireball", "§7Right-click to launch."), "bw_fireball");
            case "tnt" -> Stacks.tag(Stacks.named(new ItemStack(Items.TNT, n), "§cTNT", "§7Lights itself when placed."), "bw_tnt");
            case "ender_pearl" -> new ItemStack(Items.ENDER_PEARL, n);
            case "water" -> new ItemStack(Items.WATER_BUCKET);
            case "bridge_egg" -> Stacks.tag(Stacks.named(new ItemStack(Items.EGG, n), "§eBuilder's Egg", "§7Throw it to lay a bridge."), "bw_egg");
            case "magic_milk" -> Stacks.tag(Stacks.named(new ItemStack(Items.MILK_BUCKET), "§fCalm Milk", "§7Drink: enemy traps ignore you for 30s."), "bw_milk");
            case "popup_tower" -> Stacks.tag(Stacks.named(new ItemStack(Items.CHEST, n), "§eInstant Watchtower", "§7Place it: a tower builds itself."), "bw_tower");
            case "sky_mite" -> Stacks.tag(Stacks.named(new ItemStack(Items.SNOWBALL, n), "§7Sky Mite", "§7Throw it: a mite hatches and", "§7attacks enemies for " + BwRules.SKY_MITE_SECONDS + "s."), "bw_mite");
            case "airship_plank" -> Stacks.tag(Stacks.named(new ItemStack(Items.SPRUCE_PLANKS, n), "§6Airship Plank", "§7Place it: a plank walk shoots out", "§7ahead of you (gone after " + BwRules.PLANK_SECONDS + "s)."), "bw_plank");
            case "bed_bell" -> Stacks.named(new ItemStack(Items.BELL), "§6Bed Bell");
            case "grapple" -> {
                ItemStack s = Stacks.tag(Stacks.named(BedwarsGame.unbreakable(new ItemStack(Items.FISHING_ROD)), "§6Wren's Grapple §7(" + BwItems.GRAPPLE_USES + " uses)",
                        "§7Cast at a block, reel in to fly to it."), "bw_grapple");
                s.set(DataComponents.DAMAGE, 0);
                yield s;
            }
            case "decoy_bed" -> Stacks.tag(Stacks.named(new ItemStack(Items.BED.pick(dye)), team.color + "Decoy Bed", "§7Place it. Enemies who break it", "§7get blasted and lit up."), "bw_decoy");
            case "blanket" -> Stacks.tag(Stacks.named(new ItemStack(Items.CARPET.pick(DyeColor.LIGHT_BLUE)), "§bBed Blanket", "§7Right-click: your bed can't be", "§7broken for " + BwRules.BLANKET_SECONDS + " seconds."), "bw_blanket");
            case "rescue_cloud" -> Stacks.tag(Stacks.named(new ItemStack(Items.FEATHER, n), "§fRescue Cloud", "§7Catches you if you fall into", "§7the void (used up when it does)."), "bw_cloud");
            case "spring_pad" -> Stacks.tag(Stacks.named(new ItemStack(Items.SLIME_BLOCK, n), "§aSpring Pad", "§7Place it, then step on it to fly."), "bw_spring");
            default -> ItemStack.EMPTY;
        };
    }

    private static ItemStack potion(String name, MobEffectInstance effect, int color) {
        ItemStack s = new ItemStack(Items.POTION);
        s.set(DataComponents.POTION_CONTENTS, new PotionContents(Optional.empty(), Optional.of(color), List.of(effect), Optional.empty()));
        return Stacks.named(s, name);
    }

    /** What the shop shows for an offer: the item with its price and whether you can afford it. */
    private static ItemStack icon(ServerPlayer p, String id, int[] res) {
        BwRules.Offer o = BwRules.offer(id);
        BwPlayer bp = BedwarsGame.bp(p.getUUID());
        if (o == null || bp == null) return ItemStack.EMPTY;
        ItemStack icon;
        switch (id) {
            case "pickaxe" -> icon = new ItemStack(bp.pick >= 4 ? Items.DIAMOND_PICKAXE : BedwarsGame.PICKS[bp.pick + 1]);
            case "axe" -> icon = new ItemStack(bp.axe >= 4 ? Items.DIAMOND_AXE : BedwarsGame.AXES[bp.axe + 1]);
            case "chain_armor" -> icon = new ItemStack(Items.CHAINMAIL_BOOTS);
            case "iron_armor" -> icon = new ItemStack(Items.IRON_BOOTS);
            case "diamond_armor" -> icon = new ItemStack(Items.DIAMOND_BOOTS);
            case "stone_sword" -> icon = new ItemStack(Items.STONE_SWORD);
            case "iron_sword" -> icon = new ItemStack(Items.IRON_SWORD);
            case "diamond_sword" -> icon = new ItemStack(Items.DIAMOND_SWORD);
            case "shears" -> icon = new ItemStack(Items.SHEARS);
            default -> icon = item(id, bp.team).copy();
        }
        if (icon.isEmpty()) icon = new ItemStack(Items.BARRIER);
        int[] pr = price(p, id);
        List<Component> lore = new ArrayList<>();
        for (String a : o.about()) lore.add(Component.literal("§7" + a));
        if (o.about().length > 0) lore.add(Component.literal(""));
        String name;
        if (id.equals("pickaxe") || id.equals("axe")) {
            int lvl = id.equals("pickaxe") ? bp.pick : bp.axe;
            name = o.name() + (lvl >= 4 ? " §7(max)" : " §7(level " + (lvl + 1) + ")");
        } else name = o.name() + (o.amount() > 1 ? " §7x" + o.amount() : "");
        if (pr == null) {
            lore.add(Component.literal(id.equals("blanket") ? "§cYour team already has one." : "§aYou already have this!"));
        } else {
            lore.add(Component.literal("§7Cost: " + BwRules.CURRENCY_COLOR[pr[0]] + pr[1] + " " + BwRules.CURRENCY[pr[0]]));
            lore.add(Component.literal(""));
            lore.add(Component.literal(res[pr[0]] >= pr[1] ? "§eClick to buy!" : "§cYou need " + (pr[1] - res[pr[0]]) + " more " + BwRules.CURRENCY[pr[0]] + "."));
        }
        boolean afford = pr != null && res[pr[0]] >= pr[1];
        icon.set(DataComponents.CUSTOM_NAME, Component.literal((afford ? "§a" : "§c") + name));
        icon.set(DataComponents.LORE, new ItemLore(lore));
        icon.setCount(Math.max(1, Math.min(64, o.amount())));
        return icon;
    }
}
