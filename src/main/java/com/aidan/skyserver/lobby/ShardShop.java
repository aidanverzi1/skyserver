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
 */
public final class ShardShop extends LockedMenu {
    /** Shards for opening a chest of each tier, and for salvaging an item prize from it. */
    public static final int[] OPEN_SHARDS = {1, 3, 8, 20, 50};
    public static final int[] SALVAGE_SHARDS = {2, 6, 15, 40, 100};
    /** Keys for shards (Common .. Legendary). */
    public static final int[] KEY_COST = {12, 35, 100, 400, 1_000};
    /** Boosters for shards: bag id, cost. */
    public static final String[][] BOOSTERS = {{"booster:xp:30:25", "20"}, {"booster:money:30:25", "30"}, {"booster:xp:60:50", "60"}};

    private static final int KEYS_FIRST = 10, BOOST_FIRST = 15, COSM_FIRST = 28;
    private final List<String> cosmetics = new ArrayList<>();

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
        for (int i = 0; i < cosmetics.size() && COSM_FIRST + i < 53; i++) {
            String id = cosmetics.get(i);
            int r = Math.max(0, Progression.cosmeticRarity(id));
            boolean own = prof.owned.contains(id);
            box.setItem(slotFor(i), Stacks.named(new ItemStack(CosmeticShop.icon(id)), Progression.cosmeticName(id), Progression.RARITY_NAMES[r] + " §7cosmetic",
                    own ? "§a✔ You own this" : "§7Cost: §d" + Progression.COSMETIC_SHARDS[r] + " shards",
                    own ? "" : have >= Progression.COSMETIC_SHARDS[r] ? "§eClick to buy" : "§cNot enough shards"));
        }
        frame(Stacks.named(new ItemStack(Items.STAINED_GLASS_PANE.pick(DyeColor.PURPLE)), " "));
        broadcastChanges();
    }

    /** Cosmetics fill rows 4-6 inside the frame (slots 28-34, 37-43, 46-52). */
    private static int slotFor(int i) {
        int row = i / 7, col = i % 7;
        return COSM_FIRST + row * 9 + col;
    }

    @Override
    protected void onClick(int slot, int button, boolean shift) {
        UUID id = viewer.getUUID();
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
        for (int i = 0; i < cosmetics.size(); i++) {
            if (slotFor(i) != slot) continue;
            String cid = cosmetics.get(i);
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
