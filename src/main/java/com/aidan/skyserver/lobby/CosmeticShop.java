package com.aidan.skyserver.lobby;

import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.Commands;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.time.LocalDate;
import java.util.*;

/**
 * 5.8: the Cosmetic Shop (/cshop or the compass menu). Six cosmetics for money, a new selection
 * every Monday (the same for everyone). Legendary "exclusive" cosmetics are never sold here -
 * they only come from Legendary chests.
 */
public final class CosmeticShop extends LockedMenu {
    public static final int SHOWN = 6;
    private static final int[] SLOTS = {10, 11, 12, 14, 15, 16};
    private final List<String> stock;

    private CosmeticShop(int id, Inventory inv, SimpleContainer box, ServerPlayer viewer) {
        super(id, inv, box, 3, viewer);
        stock = thisWeek();
        draw();
    }

    public static void init() {
        CommandRegistrationCallback.EVENT.register((d, r, e) -> {
            d.register(Commands.literal("cshop").executes(c -> { open(c.getSource().getPlayerOrException()); return 1; }));
            d.register(Commands.literal("cosmeticshop").executes(c -> { open(c.getSource().getPlayerOrException()); return 1; }));
        });
    }

    public static void open(ServerPlayer p) {
        LockedMenu.open(p, "§8Cosmetic Shop §7(new every Monday)", 3, CosmeticShop::new);
    }

    /** This week's six: two common, three rare, one legendary (non-exclusive). */
    static List<String> thisWeek() {
        long week = Math.floorDiv(LocalDate.now().toEpochDay() + 3, 7); // weeks start on Monday
        Random rng = new Random(week * 7919L);
        List<String> out = new ArrayList<>();
        int[] want = {2, 3, 1};
        for (int r = 0; r < 3; r++) {
            List<String> pool = new ArrayList<>(Progression.cosmeticsOfRarity(r, false));
            Collections.shuffle(pool, rng);
            for (int i = 0; i < want[r] && i < pool.size(); i++) out.add(pool.get(i));
        }
        return out;
    }

    static Item icon(String id) {
        if (Progression.isTitle(id)) return Items.NAME_TAG;
        if (Progression.isKillEffect(id)) return Items.IRON_SWORD;
        if (Progression.isWinEffect(id)) return Items.FIREWORK_ROCKET;
        return Items.BLAZE_POWDER;
    }

    private void draw() {
        PlayerProfile prof = PlayerCache.get(viewer);
        long bal = Economy.balance(viewer.level().getServer(), viewer.getUUID());
        for (int i = 0; i < SLOTS.length; i++) {
            if (i >= stock.size()) continue;
            String id = stock.get(i);
            int r = Math.max(0, Progression.cosmeticRarity(id));
            boolean own = prof.owned.contains(id);
            box.setItem(SLOTS[i], Stacks.named(new ItemStack(icon(id)), Progression.cosmeticName(id),
                    Progression.RARITY_NAMES[r] + " §7cosmetic",
                    own ? "§a✔ You own this" : "§7Price: §6" + Economy.format(Progression.COSMETIC_PRICE[r]),
                    own ? "§7Use it: §e/cosmetics" : bal >= Progression.COSMETIC_PRICE[r] ? "§eClick to buy" : "§cNot enough money"));
        }
        box.setItem(22, Stacks.named(new ItemStack(Items.GOLD_INGOT), "§6Your money: " + Economy.format(Math.max(0, bal)),
                "§7Also try the §d/shards §7shop", "§7and §e/cosmetics §7to switch."));
        frame(Stacks.named(new ItemStack(Items.STAINED_GLASS_PANE.pick(DyeColor.MAGENTA)), " "));
        broadcastChanges();
    }

    @Override
    protected void onClick(int slot, int button, boolean shift) {
        for (int i = 0; i < SLOTS.length && i < stock.size(); i++) {
            if (SLOTS[i] != slot) continue;
            String id = stock.get(i);
            PlayerProfile prof = PlayerCache.get(viewer);
            if (prof.owned.contains(id)) return;
            long price = Progression.COSMETIC_PRICE[Math.max(0, Progression.cosmeticRarity(id))];
            if (!Economy.take(viewer.level().getServer(), viewer.getUUID(), price)) {
                Lobby.msg(viewer, "§cThat costs " + Economy.format(price) + ".");
                return;
            }
            prof.owned.add(id);
            prof.touch();
            Lobby.msg(viewer, "§d§lNew cosmetic: §r" + Progression.cosmeticName(id) + "§d! §7Use it with §e/cosmetics§7.");
            draw();
        }
    }
}
