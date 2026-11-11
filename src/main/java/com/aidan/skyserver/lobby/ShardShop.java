package com.aidan.skyserver.lobby;

import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.Commands;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.*;

/**
 * 5.8: the Shard Shop (/shards or the compass menu). Key Shards come from every mystery chest you
 * open (more from better chests) and from prizes you salvage instead of keeping. Spend them on the
 * key you want, a cosmetic you choose (non-exclusive ones), or boosters.
 *
 * 5.17:
 *  - FIX: the shop didn't open at all. Once there were more than 21 cosmetics for sale, the menu tried to
 *    put the 22nd into a slot the chest doesn't have. The cosmetics are now shown in PAGES of 14 (two rows),
 *    with Previous / Next arrows in the bottom row.
 *  - The third booster sat on the border; boosters moved to their own row.
 *  - Boosters cost much more: 150 / 200 / 400 shards (were 20 / 30 / 60).
 */
public final class ShardShop extends LockedMenu {
    /** Shards for opening a chest of each tier, and for salvaging an item prize from it. */
    public static final int[] OPEN_SHARDS = {1, 3, 8, 20, 50};
    public static final int[] SALVAGE_SHARDS = {2, 6, 15, 40, 100};
    /** Keys for shards (Common .. Legendary). */
    public static final int[] KEY_COST = {12, 35, 100, 400, 1_000};
    /** Boosters for shards: bag id, cost. 5.17: 150 / 200 / 400 (were 20 / 30 / 60). */
    public static final String[][] BOOSTERS = {{"booster:xp:30:25", "150"}, {"booster:money:30:25", "200"}, {"booster:xp:60:50", "400"}};

    /** Row 2: keys. Row 3: boosters. Rows 4-5: one page of cosmetics. Row 6: the page arrows. */
    private static final int KEYS_FIRST = 10, BOOST_FIRST = 19, COSM_FIRST = 28, PER_PAGE = 14, PREV = 48, PAGE_INFO = 49, NEXT = 50;
    private final List<String> cosmetics = new ArrayList<>();
    private int page = 0;

    private ShardShop(int id, Inventory inv, SimpleContainer box, ServerPlayer viewer) {
        super(id, inv, box, 6, viewer);
        draw();
    }

    public static void init() {
        CommandRegistrationCallback.EVENT.register((d, r, e) -> d.register(Commands.literal("shards")
                .executes(c -> { open(c.getSource().getPlayerOrException()); return 1; })));
    }

    public static void open(ServerPlayer p) {
        LockedMenu.open(p, "§8Shard Shop", 6, ShardShop::new);
    }

    private int pages() {
        return Math.max(1, (cosmetics.size() + PER_PAGE - 1) / PER_PAGE);
    }

    private void draw() {
        for (int i = 0; i < box.getContainerSize(); i++) box.setItem(i, ItemStack.EMPTY);
        int have = Tokens.shards(viewer.getUUID());
        box.setItem(4, Stacks.named(new ItemStack(Items.AMETHYST_SHARD), "§d§lKey Shards: §f" + have,
                "§7Every mystery chest gives shards:", "§7Common 1, Uncommon 3, Rare 8, Epic 20, Legendary 50.",
                "§7Item prizes can be salvaged for more."));
        for (int t = 0; t < 5; t++)
            box.setItem(KEYS_FIRST + t, Stacks.named(new ItemStack(Items.TRIPWIRE_HOOK), Keys.COLORS[t] + Keys.NAMES[t] + " Key",
                    "§7Cost: §d" + KEY_COST[t] + " shards", have >= KEY_COST[t] ? "§eClick to buy" : "§cNot enough shards"));
        for (int b = 0; b < BOOSTERS.length; b++) {
            int cost = Integer.parseInt(BOOSTERS[b][1]);
            box.setItem(BOOST_FIRST + b, Stacks.named(new ItemStack(b == 1 ? Items.GOLD_NUGGET : Items.EXPERIENCE_BOTTLE), Boosters.describe(BOOSTERS[b][0]),
                    "§7Cost: §d" + cost + " shards", "§7Goes to your booster bag (§e/boosters§7)", have >= cost ? "§eClick to buy" : "§cNot enough shards"));
        }
        cosmetics.clear();
        PlayerProfile prof = PlayerCache.get(viewer);
        for (int r = 0; r < 3; r++) for (String id : Progression.cosmeticsOfRarity(r, false)) cosmetics.add(id);
        page = Math.max(0, Math.min(page, pages() - 1));
        for (int i = 0; i < PER_PAGE; i++) {
            int index = page * PER_PAGE + i;
            if (index >= cosmetics.size()) break;
            String id = cosmetics.get(index);
            int r = Math.max(0, Progression.cosmeticRarity(id));
            boolean own = prof.owned.contains(id);
            box.setItem(slotFor(i), Stacks.named(new ItemStack(CosmeticShop.icon(id)), Progression.cosmeticName(id), Progression.RARITY_NAMES[r] + " §7cosmetic",
                    own ? "§a✔ You own this" : "§7Cost: §d" + Progression.COSMETIC_SHARDS[r] + " shards",
                    own ? "" : have >= Progression.COSMETIC_SHARDS[r] ? "§eClick to buy" : "§cNot enough shards"));
        }
        if (pages() > 1) {
            if (page > 0) box.setItem(PREV, Stacks.named(new ItemStack(Items.ARROW), "§f◀ Previous cosmetics", "§7Page " + page + " of " + pages()));
            box.setItem(PAGE_INFO, Stacks.named(new ItemStack(Items.PAPER), "§fCosmetics page " + (page + 1) + " of " + pages(), "§7" + cosmetics.size() + " cosmetics for shards"));
            if (page < pages() - 1) box.setItem(NEXT, Stacks.named(new ItemStack(Items.ARROW), "§fMore cosmetics ▶", "§7Page " + (page + 2) + " of " + pages()));
        }
        frame(Stacks.named(new ItemStack(Items.STAINED_GLASS_PANE.pick(DyeColor.PURPLE)), " "));
        broadcastChanges();
    }

    /** The i-th cosmetic of a page (0..13): rows 4 and 5 inside the frame (slots 28-34 and 37-43). */
    private static int slotFor(int i) {
        int row = i / 7, col = i % 7;
        return COSM_FIRST + row * 9 + col;
    }

    @Override
    protected void onClick(int slot, int button, boolean shift) {
        UUID id = viewer.getUUID();
        if (slot == PREV && page > 0) {
            page--;
            draw();
            return;
        }
        if (slot == NEXT && page < pages() - 1) {
            page++;
            draw();
            return;
        }
        int t = slot - KEYS_FIRST;
        if (t >= 0 && t < 5) {
            if (!Tokens.take(id, Tokens.SHARDS, KEY_COST[t])) {
                Lobby.msg(viewer, "§cYou need " + KEY_COST[t] + " shards for that.");
                return;
            }
            Keys.give(id, t, 1);
            Lobby.msg(viewer, "§aBought a " + Keys.COLORS[t] + Keys.NAMES[t] + " Key §afor §d" + KEY_COST[t] + " shards§a.");
            Lobby.refreshKit(viewer);
            draw();
            return;
        }
        int b = slot - BOOST_FIRST;
        if (b >= 0 && b < BOOSTERS.length) {
            int cost = Integer.parseInt(BOOSTERS[b][1]);
            if (!Tokens.take(id, Tokens.SHARDS, cost)) {
                Lobby.msg(viewer, "§cYou need " + cost + " shards for that.");
                return;
            }
            Tokens.add(id, BOOSTERS[b][0], 1);
            Lobby.msg(viewer, "§aBought " + Boosters.describe(BOOSTERS[b][0]) + "§a. §7Start it with §e/boosters§7.");
            draw();
            return;
        }
        for (int i = 0; i < PER_PAGE; i++) {
            if (slotFor(i) != slot) continue;
            int index = page * PER_PAGE + i;
            if (index >= cosmetics.size()) return;
            String cid = cosmetics.get(index);
            PlayerProfile prof = PlayerCache.get(viewer);
            if (prof.owned.contains(cid)) return;
            int cost = Progression.COSMETIC_SHARDS[Math.max(0, Progression.cosmeticRarity(cid))];
            if (!Tokens.take(id, Tokens.SHARDS, cost)) {
                Lobby.msg(viewer, "§cYou need " + cost + " shards for that.");
                return;
            }
            prof.owned.add(cid);
            prof.touch();
            Lobby.msg(viewer, "§d§lNew cosmetic: §r" + Progression.cosmeticName(cid) + "§d! §7Use it with §e/cosmetics§7.");
            draw();
            return;
        }
    }
}
