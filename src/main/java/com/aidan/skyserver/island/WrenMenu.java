package com.aidan.skyserver.island;

import com.aidan.skyserver.expedition.AncientCity;
import com.aidan.skyserver.expedition.CityMarket;
import com.aidan.skyserver.expedition.EndExpedition;
import com.aidan.skyserver.expedition.ExpeditionMenu;
import com.aidan.skyserver.expedition.SunkenMonument;
import com.aidan.skyserver.lobby.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * 5.20: what Captain Wren offers when you talk to him in his ship room: his quests, and the start of every
 * expedition (Nether, End, Ancient City, Sunken Monument). The island menu's expedition buttons are gone.
 */
public final class WrenMenu extends LockedMenu {
    private static final int QUESTS = 10, NETHER = 12, END = 13, CITY = 14, SUNKEN = 15, CHARTS = 16, ROOM = 22, VES = 24;

    private WrenMenu(int id, Inventory inv, SimpleContainer box, ServerPlayer viewer) {
        super(id, inv, box, 3, viewer);
        Islands.Island is = Islands.islandOf(viewer.getUUID());
        box.setItem(QUESTS, Stacks.named(new ItemStack(Items.WRITABLE_BOOK), "§6§lCaptain Wren's Quests",
                "§7Now: §f" + (is == null ? "-" : IslandQuests.progressShort(is)), "§eClick to talk about quests"));
        box.setItem(NETHER, ExpeditionMenu.islandButton(viewer));
        box.setItem(END, EndExpedition.islandButton(viewer));
        box.setItem(CITY, AncientCity.islandButton(viewer));
        box.setItem(SUNKEN, SunkenMonument.islandButton(viewer));
        box.setItem(CHARTS, Stacks.named(new ItemStack(Items.FILLED_MAP), "§6§lWren's Charts", "§7Every expedition, every difficulty.", "§eClick to see yours"));
        boolean room = is != null && is.wrenRoom != null;
        // 5.21: Old Ves's City Cash market.
        box.setItem(VES, Stacks.named(new ItemStack(Items.ECHO_SHARD), "§3§lOld Ves's Stall", "§7Spend City Cash from the Ancient City:",
                "§7gear, books, discs, trims, cosmetics.", "§7City Cash: §b" + CityMarket.fmt(CityMarket.balance(viewer.getUUID())) + " CC",
                room ? "§eClick to trade" : "§8He moves in when the ship room is built"));
        box.setItem(ROOM, Stacks.named(new ItemStack(Items.DARK_OAK_BOAT), room ? "§6§lThe Ship Room" : "§e§lA Ship Room for Wren",
                room ? new String[]{"§7Your crew gathers here before a trip.", "§7Tribute Chests: the Sunken Monument fare.", "§7Lectern: island menu and Charts.",
                        "§8Move it: /is shiproom place (owner)"}
                        : new String[]{"§7Wren wants a proper cabin!", "§7Stand where the door should go,", "§7face where the ship should be, then:", "§e/is shiproom place"}));
        frame(Stacks.named(new ItemStack(Items.STAINED_GLASS_PANE.pick(DyeColor.BLUE)), " "));
        broadcastChanges();
    }

    public static void open(ServerPlayer p) {
        LockedMenu.open(p, "§8Captain Wren", 3, WrenMenu::new);
    }

    @Override
    protected void onClick(int slot, int button, boolean shift) {
        Islands.Island is = Islands.islandOf(viewer.getUUID());
        switch (slot) {
            case QUESTS -> {
                viewer.closeContainer();
                Lobby.later(viewer, () -> IslandQuests.questTalk(viewer, is));
            }
            case NETHER -> Lobby.later(viewer, () -> ExpeditionMenu.openSummon(viewer));
            case END -> Lobby.later(viewer, () -> EndExpedition.offer(viewer));
            case CITY -> Lobby.later(viewer, () -> AncientCity.offer(viewer));
            case SUNKEN -> Lobby.later(viewer, () -> SunkenMonument.openMenu(viewer));
            case VES -> {
                if (is != null && is.wrenRoom != null) Lobby.later(viewer, () -> com.aidan.skyserver.lobby.LockedMenu.openWithBack(viewer, () -> open(viewer), () -> CityMarket.openStall(viewer)));
                else {
                    viewer.closeContainer();
                    Lobby.msg(viewer, "§7Old Ves moves in once Captain Wren has his ship room: §e/is shiproom place§7.");
                }
            }
            case CHARTS -> {
                viewer.closeContainer();
                Lobby.later(viewer, () -> Charts.show(viewer));
            }
            case ROOM -> {
                if (is != null && is.wrenRoom == null) {
                    viewer.closeContainer();
                    Lobby.msg(viewer, "§6[Captain Wren] §fStand where the door should be, face where the ship should go, and type §e/is shiproom place§f.");
                }
            }
            default -> {}
        }
    }
}
