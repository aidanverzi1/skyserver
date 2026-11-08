package com.aidan.skyserver.bedwars;

import com.aidan.skyserver.lobby.LockedMenu;
import com.aidan.skyserver.lobby.Stacks;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;

import java.util.ArrayList;
import java.util.List;

/**
 * Team upgrades (right-click the Team Upgrades villager), paid in diamonds and shared by the whole
 * team: Sharpened Swords, Reinforced Armor, Maniac Miner, Forge, Heal Pool, and up to 3 traps.
 */
public final class BwUpgrades extends LockedMenu {
    private static final int[] UPGRADE_SLOTS = {10, 11, 12, 13, 14};
    private static final int[] TRAP_SLOTS = {28, 29, 30, 31};
    private static final int[] QUEUE_SLOTS = {39, 40, 41};
    private static final Item[] UPGRADE_ICONS = {Items.IRON_SWORD, Items.IRON_CHESTPLATE, Items.GOLDEN_PICKAXE, Items.FURNACE, Items.BEACON};
    private static final Item[] TRAP_ICONS = {Items.TRIPWIRE_HOOK, Items.FEATHER, Items.REDSTONE_TORCH, Items.IRON_PICKAXE};

    private BwUpgrades(int id, Inventory inv, SimpleContainer box, ServerPlayer viewer) {
        super(id, inv, box, 5, viewer);
        draw();
    }

    public static void open(ServerPlayer p) {
        LockedMenu.open(p, "§8Team Upgrades", 5, BwUpgrades::new);
    }

    private void draw() {
        for (int i = 0; i < box.getContainerSize(); i++) box.setItem(i, ItemStack.EMPTY);
        BwPlayer bp = BedwarsGame.bp(viewer.getUUID());
        if (bp == null) return;
        BwTeamState ts = BedwarsGame.teams.get(bp.team);
        int dia = BwShop.resources(viewer)[BwRules.DIAMOND];
        BwRules.Upgrade[] ups = BwRules.Upgrade.values();
        for (int i = 0; i < ups.length; i++) {
            BwRules.Upgrade u = ups[i];
            int lvl = ts.level(u);
            List<String> lore = new ArrayList<>();
            lore.add("§7" + u.about);
            lore.add("");
            for (int k = 1; k <= u.max(); k++)
                lore.add((k <= lvl ? "§a✔ " : "§7• ") + u.levelName(k) + " §7- §b" + u.cost(k) + " Diamonds");
            lore.add("");
            if (lvl >= u.max()) lore.add("§aFully upgraded!");
            else lore.add(dia >= u.cost(lvl + 1) ? "§eClick to buy " + u.levelName(lvl + 1) + "!" : "§cYou need " + (u.cost(lvl + 1) - dia) + " more Diamonds.");
            ItemStack icon = Stacks.named(new ItemStack(UPGRADE_ICONS[i]), (lvl >= u.max() ? "§a" : dia >= u.cost(lvl + 1) ? "§e" : "§c") + u.display, lore.toArray(new String[0]));
            box.setItem(UPGRADE_SLOTS[i], icon);
        }
        int cost = ts.traps.size() >= BwRules.MAX_TRAPS ? -1 : BwRules.TRAP_COST[ts.traps.size()];
        BwRules.Trap[] traps = BwRules.Trap.values();
        for (int i = 0; i < traps.length; i++) {
            BwRules.Trap t = traps[i];
            box.setItem(TRAP_SLOTS[i], Stacks.named(new ItemStack(TRAP_ICONS[i]), (cost >= 0 && dia >= cost ? "§e" : "§c") + t.display,
                    "§7" + t.about, "§7Goes off when an enemy enters your base.", "",
                    cost < 0 ? "§cThe trap queue is full (3)." : "§7Cost: §b" + cost + " Diamond" + (cost == 1 ? "" : "s") + " §8(goes up with each trap queued)",
                    cost >= 0 && dia >= cost ? "§eClick to queue it!" : ""));
        }
        List<BwRules.Trap> queue = new ArrayList<>(ts.traps);
        for (int i = 0; i < QUEUE_SLOTS.length; i++) {
            if (i < queue.size()) box.setItem(QUEUE_SLOTS[i], Stacks.named(new ItemStack(TRAP_ICONS[queue.get(i).ordinal()]), "§aTrap #" + (i + 1) + ": " + queue.get(i).display, "§7Next to go off: #1"));
            else box.setItem(QUEUE_SLOTS[i], Stacks.named(new ItemStack(Items.STAINED_GLASS_PANE.pick(DyeColor.LIGHT_GRAY)), "§7Trap #" + (i + 1) + ": §8empty"));
        }
        box.setItem(4, Stacks.named(new ItemStack(Items.DIAMOND, Math.max(1, Math.min(64, dia))), "§bYour diamonds: " + dia, "§7Diamonds come from the islands", "§7between the bases."));
        box.setItem(37, Stacks.named(new ItemStack(Items.LEATHER), "§eTrap queue", "§7Traps go off one at a time,", "§7first in, first out."));
        frame(Stacks.named(new ItemStack(Items.STAINED_GLASS_PANE.pick(DyeColor.BLACK)), " "));
        broadcastChanges();
    }

    @Override
    protected void onClick(int slot, int button, boolean shift) {
        String err = null;
        boolean hit = false;
        for (int i = 0; i < UPGRADE_SLOTS.length; i++) if (slot == UPGRADE_SLOTS[i]) {
            hit = true;
            err = buyUpgrade(viewer, BwRules.Upgrade.values()[i]);
        }
        for (int i = 0; i < TRAP_SLOTS.length; i++) if (slot == TRAP_SLOTS[i]) {
            hit = true;
            err = buyTrap(viewer, BwRules.Trap.values()[i]);
        }
        if (!hit) return;
        if (err != null) {
            BedwarsGame.msg(viewer, "§c" + err);
            viewer.level().playSound(null, viewer.getX(), viewer.getY(), viewer.getZ(), SoundEvents.VILLAGER_NO, SoundSource.PLAYERS, 0.6f, 1f);
        }
        draw();
    }

    /** Buys the next level of an upgrade for the player's team. Null = done, else the reason it failed. */
    public static String buyUpgrade(ServerPlayer p, BwRules.Upgrade u) {
        BwPlayer bp = BedwarsGame.bp(p.getUUID());
        if (bp == null || !BedwarsGame.alive(p.getUUID())) return "You can't buy that now.";
        BwTeamState ts = BedwarsGame.teams.get(bp.team);
        int lvl = ts.level(u);
        if (lvl >= u.max()) return u.display + " is already maxed.";
        int cost = u.cost(lvl + 1);
        if (!BwShop.take(p, BwRules.DIAMOND, cost)) {
            String why = "You need " + (cost - BwShop.resources(p)[BwRules.DIAMOND]) + " more Diamonds.";
            BwBotLog.transaction(p.getName().getString(), bp.bot, "UPGRADE", u.name(), cost + " Diamond", BwShop.resources(p), false, why);
            return why;
        }
        ts.upgrades.put(u, lvl + 1);
        bp.active = true;
        BwBotLog.transaction(p.getName().getString(), bp.bot, "UPGRADE", u.name() + " -> " + (lvl + 1), cost + " Diamond", BwShop.resources(p), true, "");
        BedwarsGame.teamMsg(bp.team, "§a" + p.getName().getString() + " bought §6" + u.levelName(lvl + 1) + "§a for the team!");
        for (ServerPlayer mate : BedwarsGame.teamOnline(bp.team))
            mate.level().playSound(null, mate.getX(), mate.getY(), mate.getZ(), SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 0.6f, 1.4f);
        // Armor and sword enchantments are put on within a second by the game's gear check.
        return null;
    }

    public static String buyTrap(ServerPlayer p, BwRules.Trap t) {
        BwPlayer bp = BedwarsGame.bp(p.getUUID());
        if (bp == null || !BedwarsGame.alive(p.getUUID())) return "You can't buy that now.";
        BwTeamState ts = BedwarsGame.teams.get(bp.team);
        if (ts.traps.size() >= BwRules.MAX_TRAPS) {
            BwBotLog.transaction(p.getName().getString(), bp.bot, "TRAP", t.name(), "-", BwShop.resources(p), false, "queue full");
            return "Your trap queue is full.";
        }
        int cost = BwRules.TRAP_COST[ts.traps.size()];
        if (!BwShop.take(p, BwRules.DIAMOND, cost)) {
            String why = "You need " + (cost - BwShop.resources(p)[BwRules.DIAMOND]) + " more Diamonds.";
            BwBotLog.transaction(p.getName().getString(), bp.bot, "TRAP", t.name(), cost + " Diamond", BwShop.resources(p), false, why);
            return why;
        }
        ts.traps.add(t);
        bp.active = true;
        BwBotLog.transaction(p.getName().getString(), bp.bot, "TRAP", t.name() + " (#" + ts.traps.size() + ")", cost + " Diamond", BwShop.resources(p), true, "");
        BedwarsGame.teamMsg(bp.team, "§a" + p.getName().getString() + " queued §6" + t.display + "§a (" + ts.traps.size() + "/" + BwRules.MAX_TRAPS + ").");
        return null;
    }
}
