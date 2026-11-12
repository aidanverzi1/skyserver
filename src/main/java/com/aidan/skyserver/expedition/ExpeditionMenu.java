package com.aidan.skyserver.expedition;

import com.aidan.skyserver.island.Islands;
import com.aidan.skyserver.lobby.*;
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
 * The Nether Expedition menus.
 *  - CallMenu (from /is): call the airship with the deposit or a ticket.
 *  - HelmMenu (right-click Wren on the deck): Fortress / Bastion / Surprise and the difficulty.
 *  - WarningMenu: "you lose everything you carry if you die" - confirm to set sail.
 * 5.17: the text matches co-op (everyone pays for their own place and gets their own rewards, crew
 * scaling, the teammate pick-up), the easier boss and the twists.
 */
public final class ExpeditionMenu {
    private ExpeditionMenu() {}

    private static final Item[] DIFF_ICONS = {Items.IRON_SWORD, Items.DIAMOND_SWORD, Items.NETHERITE_SWORD, Items.NETHER_STAR};

    /** What the guards wear and get on each difficulty (matches Expedition.gear). */
    private static final String[] GUARD_GEAR = {
            "§7Guards: iron/gold helmets and chestplates",
            "§7Guards: full iron/gold armor, Resistance",
            "§7Guards: diamond & iron armor, Resistance + Strength",
            "§7Guards: full diamond, Strength II + Speed"};
    /** What to bring (Wren's advice). */
    private static final String[] BRING = {
            "§8Bring diamond armor and golden apples.",
            "§8Bring enchanted diamond armor and gapples.",
            "§8Bring your best gear and lots of gapples.",
            "§8Netherite, totems and a full crew."};

    private static String k(long price) {
        return price % 1000 == 0 ? "$" + (price / 1000) + "k" : Economy.format(price);
    }

    /** "3, 4, 5" for a difficulty's wave sizes. */
    private static String waves(int d) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < Expedition.WAVES[d].length; i++) b.append(i == 0 ? "" : ", ").append(Expedition.WAVES[d][i]);
        return b.toString();
    }

    /** What the one-time paid revive costs on this difficulty (the same sum Expedition.reviveCost uses). */
    private static long reviveCost(int d) {
        return Math.round(Expedition.PRICES[d] * Expedition.REVIVE_SHARE);
    }

    private static String mmss(int seconds) {
        return (seconds / 60) + ":" + String.format("%02d", seconds % 60);
    }

    /** The button in the /is menu. */
    public static ItemStack islandButton(ServerPlayer p) {
        Islands.Island is = Islands.islandOf(p.getUUID());
        String state = is == null ? "" : Expedition.running(is) ? "§eYour crew is in the Nether right now!"
                : Expedition.hasShip(is) ? "§aThe airship is waiting - board it!" : "§eClick to call the airship";
        return Stacks.named(new ItemStack(Items.NETHERRACK), "§c§lNether Expedition",
                "§7Captain Wren flies you and up to 2 island", "§7or faction mates to a Fortress or Bastion.",
                "§7Clear the guarded chest rooms, then", "§7beat the boss for the Treasure Vault.",
                "§7Prices (each): §a" + k(Expedition.PRICES[0]) + " §e" + k(Expedition.PRICES[1]) + " §c" + k(Expedition.PRICES[2]) + " §4" + k(Expedition.PRICES[3]),
                "§c§lDying loses everything you carry!", "§7(Once per trip you can pay to revive.)", state);
    }

    public static void openSummon(ServerPlayer p) {
        Islands.Island is = Islands.islandOf(p.getUUID());
        if (is == null) {
            Lobby.msg(p, "§cYou need a Sky Island first. §7/is");
            return;
        }
        if (Expedition.hasShip(is)) {
            if (Expedition.running(is)) Lobby.msg(p, "§7Your crew is on an expedition right now.");
            else Expedition.board(p);
            return;
        }
        LockedMenu.open(p, "§8Call Captain Wren's airship", 3, CallMenu::new);
    }

    /** Right-click Wren at the helm. */
    public static void open(ServerPlayer p) {
        Islands.Island at = Islands.islandAt(p.getX(), p.getZ());
        Islands.Island own = Islands.islandOf(p.getUUID());
        if (at == null || own != at) {
            Lobby.msg(p, "§6[Captain Wren] §fAhoy! Only this island's crew can take the helm. §7When the captain sets sail you'll be asked to pay for your place.");
            return;
        }
        Expedition.Pending pd = Expedition.pendingOf(own);
        if (pd == null) return;
        if (pd.diff >= 0) {
            Lobby.msg(p, "§6[Captain Wren] §fThe crew is paying up - we sail in a moment.");
            return;
        }
        LockedMenu.open(p, "§8Captain Wren - set sail", 3, HelmMenu::new);
    }

    // ---- Calling ----

    static final class CallMenu extends LockedMenu {
        private static final int DEPOSIT = 11;
        private static final int[] TICKETS = {13, 14, 15, 16};

        CallMenu(int id, Inventory inv, SimpleContainer box, ServerPlayer viewer) {
            super(id, inv, box, 3, viewer);
            box.setItem(DEPOSIT, Stacks.named(new ItemStack(Items.GOLD_INGOT), "§6§lCall the airship",
                    "§7Deposit: §6" + Economy.format(Expedition.PRICES[0]) + " §7(counts toward your place)",
                    "§7Pick the difficulty on board:",
                    "§aEasy " + Economy.format(Expedition.PRICES[0]) + " §7| §eNormal " + Economy.format(Expedition.PRICES[1]),
                    "§cHard " + Economy.format(Expedition.PRICES[2]) + " §7| §4Nightmare " + Economy.format(Expedition.PRICES[3]),
                    "§7Up to " + Expedition.CREW_MAX + " can sail (island or faction mates).",
                    "§7Everyone pays for their own place.",
                    "§7If nobody sets sail in 10 minutes,", "§7the airship leaves and you get it back.", "§eClick to call"));
            for (int d = 0; d < 4; d++) {
                int have = Tokens.count(viewer.getUUID(), Tokens.TICKET[d]);
                box.setItem(TICKETS[d], Stacks.named(new ItemStack(Items.MAP, Math.max(1, Math.min(64, have))),
                        Expedition.DIFF_COLORS[d] + "Use a " + Expedition.DIFF_NAMES[d] + " Ticket", "§7You have: §f" + have,
                        "§7Pays for your place on a " + Expedition.DIFF_NAMES[d] + " trip", "§7(or any easier one).", "§7Harder ones cost the difference.",
                        have > 0 ? "§eClick to call with it" : "§8From Rare / Epic / Legendary chests"));
            }
            box.setItem(22, Stacks.named(new ItemStack(Items.SHIELD), "§fBefore you go",
                    "§7Wear your own armor, bring weapons,", "§7food, blocks and torches.",
                    "§cIf you die you lose everything you carry.", "§7Your ender chest stays safe.", "§7Totems of Undying work.",
                    "§aOnce per trip you can pay 20% of the", "§atrip's price to revive and keep it all.",
                    "§bIn a crew, a teammate can pick one", "§bdowned player up per trip for free."));
            frame(Stacks.named(new ItemStack(Items.STAINED_GLASS_PANE.pick(DyeColor.RED)), " "));
            broadcastChanges();
        }

        @Override
        protected void onClick(int slot, int button, boolean shift) {
            if (slot == DEPOSIT) {
                viewer.closeContainer();
                Lobby.later(viewer, () -> Expedition.summon(viewer, -1));
                return;
            }
            for (int d = 0; d < 4; d++) {
                if (slot != TICKETS[d] || Tokens.count(viewer.getUUID(), Tokens.TICKET[d]) <= 0) continue;
                final int dd = d;
                viewer.closeContainer();
                Lobby.later(viewer, () -> Expedition.summon(viewer, dd));
            }
        }
    }

    // ---- At the helm ----

    static final class HelmMenu extends LockedMenu {
        private static final int[] DIFFS = {10, 12, 14, 16};
        private static final int STRUCTURE = 22, CREW = 4;

        HelmMenu(int id, Inventory inv, SimpleContainer box, ServerPlayer viewer) {
            super(id, inv, box, 3, viewer);
            draw();
        }

        private void draw() {
            Islands.Island is = Islands.islandOf(viewer.getUUID());
            Expedition.Pending pd = Expedition.pendingOf(is);
            if (pd == null) return;
            for (int d = 0; d < 4; d++) {
                long owe = Expedition.owed(pd, d);
                int key = ExpeditionLoot.VAULT_KEYS[d][0];
                List<String> lore = new ArrayList<>();
                lore.add("§7Price: §6" + Economy.format(Expedition.PRICES[d]) + " §7each" + (owe == 0 ? " §a(yours is paid)" : " §7- you pay §6" + Economy.format(owe) + " §7more"));
                lore.add("§7" + Expedition.ROOMS[d] + " chest rooms, then the boss arena");
                lore.add("§7Waves of §f" + waves(d) + " §7guards §8(+1 per extra player, each +70% health)");
                lore.add("§7Next wave every §f" + Expedition.WAVE_SECONDS[d] + "s §8(+25% per extra player)§7, a §4captain §7leads the last");
                lore.add(GUARD_GEAR[d]);
                lore.add("§7Every arena has a twist");
                lore.add("§7Boss: §c" + Math.round(ExpeditionBoss.HP[d]) + "❤ §8(x1.75 with 2, x2.3 with 3)§7, enrages at §f" + mmss(ExpeditionBoss.ENRAGE_SECONDS[d]));
                lore.add("§7Time limit: §f" + (Expedition.TIME_LIMITS[d] / 60) + " minutes §8(+25% with 2, +40% with 3)");
                lore.add("§7One paid revive each per trip: §6" + Economy.format(reviveCost(d)));
                lore.add("§7Vault: §6" + Economy.format(ExpeditionLoot.vaultMoney(d)) + " §7each + " + Keys.COLORS[key] + Keys.NAMES[key] + " Key"
                        + (ExpeditionLoot.BONUS_KEY_CHANCE[d] > 0 ? " §7(+" + ExpeditionLoot.BONUS_KEY_CHANCE[d] + "% " + Keys.COLORS[ExpeditionLoot.BONUS_KEY_TIER[d]]
                        + Keys.NAMES[ExpeditionLoot.BONUS_KEY_TIER[d]] + "§7)" : ""));
                lore.add("§7Everyone gets their own chest loot.");
                lore.add(BRING[d]);
                lore.add("§eClick to choose");
                box.setItem(DIFFS[d], Stacks.named(new ItemStack(DIFF_ICONS[d]), Expedition.DIFF_COLORS[d] + "§l" + Expedition.DIFF_NAMES[d], lore.toArray(new String[0])));
            }
            String[] st = {"§4Nether Fortress §7(blazes, wither skeletons)", "§6Bastion Remnant §7(piglins, brutes, hoglins)", "§dSurprise me! §7(either one)"};
            box.setItem(STRUCTURE, Stacks.named(new ItemStack(pd.structure == 0 ? Items.NETHER_BRICKS : pd.structure == 1 ? Items.GILDED_BLACKSTONE : Items.COMPASS),
                    "§fDestination: " + st[pd.structure], "§eClick to change"));
            List<String> crew = new ArrayList<>(List.of("§7On the deck now (island and faction mates).", "§7When you set sail they get " + Expedition.MUSTER_SECONDS + " seconds",
                    "§7to pay for their own place - up to " + Expedition.CREW_MAX + " sail:"));
            for (ServerPlayer m : Expedition.crewOnDeck(is)) crew.add(" §f" + m.getName().getString());
            box.setItem(CREW, Stacks.named(new ItemStack(Items.PLAYER_HEAD), "§a§lCrew", crew.toArray(new String[0])));
            frame(Stacks.named(new ItemStack(Items.STAINED_GLASS_PANE.pick(DyeColor.ORANGE)), " "));
            broadcastChanges();
        }

        @Override
        protected void onClick(int slot, int button, boolean shift) {
            Islands.Island is = Islands.islandOf(viewer.getUUID());
            Expedition.Pending pd = Expedition.pendingOf(is);
            if (pd == null) return;
            if (slot == STRUCTURE) {
                pd.structure = (pd.structure + 1) % 3;
                draw();
                return;
            }
            for (int d = 0; d < 4; d++) {
                if (slot != DIFFS[d]) continue;
                final int dd = d;
                Lobby.later(viewer, () -> LockedMenu.open(viewer, "§4§lWARNING", 3, (id, inv, box, v) -> new WarningMenu(id, inv, box, v, dd)));
            }
        }
    }

    static final class WarningMenu extends LockedMenu {
        private static final int YES = 11, NO = 15;
        private final int diff;

        WarningMenu(int id, Inventory inv, SimpleContainer box, ServerPlayer viewer, int diff) {
            super(id, inv, box, 3, viewer);
            this.diff = diff;
            Expedition.Pending pd = Expedition.pendingOf(Islands.islandOf(viewer.getUUID()));
            long owe = pd == null ? 0 : Expedition.owed(pd, diff);
            box.setItem(13, Stacks.named(new ItemStack(Items.BARRIER), "§c§lREAD THIS FIRST",
                    "§fIf you die in the Nether, you §c§llose", "§c§leverything you are carrying§f.",
                    "§7It drops where you die: your crew can", "§7pick it up before the trip ends.",
                    "§aOnce per trip you can revive instead", "§afor §6" + Economy.format(reviveCost(diff)) + " §a(30 seconds to decide).",
                    "§bIn a crew, a teammate can pick ONE", "§bdowned player up per trip (half health).",
                    "§7Your ender chest is safe. Totems work.", "§7Quitting during a fight counts as dying.",
                    "§7Talk to Captain Wren in Wren's Landing", "§7to come home safe with your loot.",
                    "§7Time limit: §f" + (Expedition.TIME_LIMITS[diff] / 60) + " minutes §8(more with a crew)§7."));
            box.setItem(YES, Stacks.named(new ItemStack(Items.EMERALD_BLOCK), "§a§lI understand - set sail!",
                    Expedition.DIFF_COLORS[diff] + Expedition.DIFF_NAMES[diff] + " §7expedition",
                    owe > 0 ? "§7You pay §6" + Economy.format(owe) + " §7when the airship leaves" : "§7Your place is already paid",
                    "§7Anyone else on the deck gets " + Expedition.MUSTER_SECONDS + " seconds", "§7to pay " + Economy.format(Expedition.PRICES[diff]) + " for their own place."));
            box.setItem(NO, Stacks.named(new ItemStack(Items.REDSTONE_BLOCK), "§c§lNot yet", "§7Back to the helm."));
            frame(Stacks.named(new ItemStack(Items.STAINED_GLASS_PANE.pick(DyeColor.BLACK)), " "));
            broadcastChanges();
        }

        @Override
        protected void onClick(int slot, int button, boolean shift) {
            if (slot == YES) {
                viewer.closeContainer();
                Lobby.later(viewer, () -> Expedition.launch(viewer, diff));
            } else if (slot == NO) {
                Lobby.later(viewer, () -> LockedMenu.open(viewer, "§8Captain Wren - set sail", 3, HelmMenu::new));
            }
        }
    }
}
