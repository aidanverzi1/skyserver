package com.aidan.skyserver.lobby;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;

/**
 * 5.13: the /voyage screen. Top: this week's twist and your stamp progress, the weekly board and the
 * next Voyage. Middle: the stamp book. Bottom: the last 8 Voyages and whether you got their stamp.
 * Opened from the compass it gets a Back arrow (LockedMenu.openWithBack).
 */
public final class VoyageMenu extends LockedMenu {
    private static final int INFO = 4, PROGRESS = 20, BOARD = 22, NEXT = 24, CHOOSE_CAGE = 39, CHOOSE_BED = 41;
    private static final int BOOK_FIRST = 28, PAST_FIRST = 46;

    private VoyageMenu(int id, Inventory inv, SimpleContainer box, ServerPlayer viewer) {
        super(id, inv, box, 6, viewer);
        draw();
    }

    public static void open(ServerPlayer p) {
        LockedMenu.open(p, "§8Wren's Weekly Voyage", 6, VoyageMenu::new);
    }

    private static Item modeIcon(Voyage.Mode m) {
        return switch (m) {
            case BEDWARS -> Items.BED.pick(DyeColor.RED);
            case SKYWARS -> Items.FEATHER;
            case GOLDWARS -> Items.GOLD_BLOCK;
            case ELYTRA -> Items.ELYTRA;
            case ISLAND -> Items.WHEAT;
            case EXPEDITION -> Items.NETHERRACK;
        };
    }

    private void draw() {
        Voyage.Week w = Voyage.current();
        int stamps = Voyage.stamps(viewer.getUUID());
        if (w != null) {
            List<String> lore = new ArrayList<>(List.of("§7" + w.twist.mode.display + ": " + w.twist.about, "§7Perk: §f" + Voyage.perkText(w),
                    "§7Ends in §f" + Voyage.duration(Voyage.secondsToChange()), "", "§6Captain Wren: §f\"" + w.twist.wren + "\""));
            if (w.twist == Voyage.Twist.TIME_TRIAL && !ElytraCourses.ALL.isEmpty())
                lore.add(1, "§7Featured course: " + ElytraCourses.ALL.get(Voyage.featuredCourse()).color() + ElytraCourses.ALL.get(Voyage.featuredCourse()).name());
            box.setItem(INFO, Stacks.named(new ItemStack(modeIcon(w.twist.mode)), "§6§l⚓ " + w.twist.display, lore.toArray(new String[0])));
            Voyage.Prog g = w.prog.get(viewer.getUUID());
            int have = g == null ? 0 : g.count, need = Voyage.stampTarget(w.twist);
            box.setItem(PROGRESS, Stacks.named(new ItemStack(g != null && g.stamp ? Items.DYE.pick(DyeColor.LIME) : Items.PAPER),
                    g != null && g.stamp ? "§a§lStamp earned!" : "§e§lYour stamp", "§7" + Voyage.stampGoal(w.twist),
                    g != null && g.stamp ? "§a✔ Done this week" : "§f" + Math.min(have, need) + "§7/" + need,
                    "§7A stamp = §b+" + Voyage.STAMP_PASS_XP + " pass XP§7 and a page in your book."));
            List<String> top = new ArrayList<>();
            List<Voyage.Entry> b = Voyage.board(w);
            String[] medal = {"§6§l1.", "§f§l2.", "§c§l3."};
            for (int i = 0; i < Math.min(5, b.size()); i++)
                top.add((i < 3 ? medal[i] : "§7" + (i + 1) + ".") + " §f" + b.get(i).name() + " §7- " + Voyage.scoreText(w, b.get(i)));
            if (top.isEmpty()) top.add("§7Nobody yet - be the first!");
            top.add("");
            top.add("§7Sunday 23:59: top 3 get a §9Rare§7, §aUncommon§7, §fCommon §7key.");
            box.setItem(BOARD, Stacks.named(new ItemStack(Items.OAK_SIGN), "§e§lThis week's board", top.toArray(new String[0])));
        } else {
            box.setItem(INFO, Stacks.named(new ItemStack(Items.CLOCK), "§6§l⚓ No Voyage right now",
                    "§7Voyages run §fThursday to Sunday§7.", "§7Next one starts in §f" + Voyage.duration(Voyage.secondsToChange())));
            box.setItem(PROGRESS, Stacks.named(new ItemStack(Items.PAPER), "§7Your stamp", "§7Come back Thursday!"));
            box.setItem(BOARD, Stacks.named(new ItemStack(Items.OAK_SIGN), "§7Weekly board", "§7Opens with the next Voyage."));
        }
        Voyage.Week up = Voyage.upcoming();
        if (up != null && up.twist != null && !up.off && Voyage.currentKey() == null)
            box.setItem(NEXT, Stacks.named(new ItemStack(modeIcon(up.twist.mode)), "§b§lNext Voyage: §f" + up.twist.display,
                    "§7" + up.twist.mode.display + ": " + up.twist.about, "§7Starts Thursday."));
        else box.setItem(NEXT, Stacks.named(new ItemStack(Items.COMPASS), "§b§lNext Voyage", "§7Revealed on Monday."));

        // The stamp book.
        String[] rewards = {"§3Voyage Deckhand §7title", "§aVoyage Trail", "§b§lVoyage Navigator §7title + §9Rare Key",
                "§6Pick: Crow's Nest cage §7or §6Cannon Volley", "§6The other one of those two", "§5Epic Key §7(and every 5 after)"};
        var got = Voyage.claimed(viewer.getUUID());
        for (int i = 0; i < Voyage.BOOK.length; i++) {
            int m = Voyage.BOOK[i];
            boolean done = got.contains(m) || stamps >= m && m != 10;
            box.setItem(BOOK_FIRST + i, Stacks.named(new ItemStack(done ? Items.ENCHANTED_BOOK : Items.BOOK),
                    (done ? "§a✔ " : "§7") + m + " stamp" + (m == 1 ? "" : "s"), "§7" + rewards[i],
                    stamps >= m ? "§aReached" : "§7You have §e" + stamps));
        }
        box.setItem(BOOK_FIRST + 6, Stacks.named(new ItemStack(Items.WRITABLE_BOOK), "§6§lYour stamp book", "§7Stamps so far: §e" + stamps));
        if (stamps >= 10 && Voyage.choice(viewer.getUUID()) == null) {
            box.setItem(CHOOSE_CAGE, Stacks.named(new ItemStack(Items.SPRUCE_FENCE), "§b§lTake the Crow's Nest cage", "§7A Skywars cage. The other comes at 15.", "§eClick to choose"));
            box.setItem(CHOOSE_BED, Stacks.named(new ItemStack(Items.TNT), "§c§lTake Wren's Cannon Volley", "§7A bed-break effect. The other comes at 15.", "§eClick to choose"));
        }

        // The last 8 Voyages.
        List<Voyage.Week> past = Voyage.history(8);
        for (int i = 0; i < past.size() && i < 7; i++) {
            Voyage.Week pw = past.get(i);
            if (pw.twist == null) continue;
            Voyage.Prog g = pw.prog.get(viewer.getUUID());
            List<Voyage.Entry> b = Voyage.board(pw);
            box.setItem(PAST_FIRST + i, Stacks.named(new ItemStack(modeIcon(pw.twist.mode)), "§7" + pw.key + ": §f" + pw.twist.display,
                    pw.off ? "§cCancelled" : g != null && g.stamp ? "§a✔ You got this stamp" : "§8No stamp",
                    b.isEmpty() ? "§7No winner" : "§7Winner: §f" + b.get(0).name()));
        }
        frame(Stacks.named(new ItemStack(Items.STAINED_GLASS_PANE.pick(DyeColor.ORANGE)), " "));
        broadcastChanges();
    }

    @Override
    protected void onClick(int slot, int button, boolean shift) {
        if (slot == CHOOSE_CAGE || slot == CHOOSE_BED) {
            if (Voyage.stamps(viewer.getUUID()) < 10 || Voyage.choice(viewer.getUUID()) != null) return;
            Voyage.choose(viewer, slot == CHOOSE_CAGE ? "cage" : "bed");
            viewer.closeContainer();
        }
    }
}
