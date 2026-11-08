package com.aidan.skyserver.lobby;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;

/**
 * A chest screen used as a button menu: items can't be taken or moved, clicks are reported to
 * {@link #onClick}. Works on vanilla clients (it's just a chest screen).
 */
public class LockedMenu extends ChestMenu {
    public final SimpleContainer box;
    protected final ServerPlayer viewer;

    protected LockedMenu(int id, Inventory inv, SimpleContainer box, int rows, ServerPlayer viewer) {
        super(type(rows), id, inv, box, rows);
        this.box = box;
        this.viewer = viewer;
    }

    private static MenuType<ChestMenu> type(int rows) {
        return switch (rows) {
            case 1 -> MenuType.GENERIC_9x1;
            case 2 -> MenuType.GENERIC_9x2;
            case 3 -> MenuType.GENERIC_9x3;
            case 4 -> MenuType.GENERIC_9x4;
            case 5 -> MenuType.GENERIC_9x5;
            default -> MenuType.GENERIC_9x6;
        };
    }

    public interface Factory {
        LockedMenu create(int id, Inventory inv, SimpleContainer box, ServerPlayer viewer);
    }

    /** Opens a menu of the given size for the player. */
    public static void open(ServerPlayer player, String title, int rows, Factory factory) {
        SimpleContainer box = new SimpleContainer(rows * 9);
        player.openMenu(new SimpleMenuProvider((id, inv, p) -> factory.create(id, inv, box, player), Component.literal(title)));
    }

    /** slot: menu slot (0..rows*9-1 = the menu, higher = the player's own inventory). */
    protected void onClick(int slot, int button, boolean shift) {}

    @Override
    public void clicked(int slot, int button, ContainerInput input, Player player) {
        if (slot >= 0 && slot < slots.size()) onClick(slot, button, input.name().contains("QUICK"));
        // Never move items: undo whatever the client tried.
        setCarried(ItemStack.EMPTY);
        sendAllDataToRemote();
    }

    @Override
    public ItemStack quickMoveStack(Player player, int slot) {
        return ItemStack.EMPTY;
    }

    @Override
    public boolean stillValid(Player player) {
        return true;
    }

    /** Fills the empty slots with dark glass. */
    protected void frame(ItemStack filler) {
        for (int i = 0; i < box.getContainerSize(); i++) if (box.getItem(i).isEmpty()) box.setItem(i, filler.copy());
    }
}
