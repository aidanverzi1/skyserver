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
import net.minecraft.world.item.Items;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * A chest screen used as a button menu: items can't be taken or moved, clicks are reported to
 * {@link #onClick}. Works on vanilla clients (it's just a chest screen).
 *
 * 5.12: a menu opened from another menu (see {@link #openWithBack}) gets a "◀ Back" arrow in its
 * bottom-left corner that returns to the screen it came from. Any menu that calls frame() gets it.
 */
public class LockedMenu extends ChestMenu {
    public final SimpleContainer box;
    protected final ServerPlayer viewer;
    /** 5.12: where the Back arrow goes (null = this screen has no Back arrow). */
    protected final Runnable back;
    /** 5.12: the Back target for the next menu this player opens (set by openWithBack). */
    private static final Map<UUID, Runnable> PENDING_BACK = new HashMap<>();

    protected LockedMenu(int id, Inventory inv, SimpleContainer box, int rows, ServerPlayer viewer) {
        super(type(rows), id, inv, box, rows);
        this.box = box;
        this.viewer = viewer;
        this.back = viewer == null ? null : PENDING_BACK.remove(viewer.getUUID());
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

    /**
     * 5.12: runs opener (which opens a menu) and gives that menu a Back arrow that runs back.
     * Example: openWithBack(p, () -> GameMenu.open(p), () -> ShardShop.open(p)).
     */
    public static void openWithBack(ServerPlayer player, Runnable back, Runnable opener) {
        PENDING_BACK.put(player.getUUID(), back);
        try {
            opener.run();
        } finally {
            PENDING_BACK.remove(player.getUUID()); // opener didn't open a menu: don't leak it into a later one
        }
    }

    /** 5.12: the Back arrow's slot (bottom-left corner). */
    protected int backSlot() {
        return box.getContainerSize() - 9;
    }

    /** slot: menu slot (0..rows*9-1 = the menu, higher = the player's own inventory). */
    protected void onClick(int slot, int button, boolean shift) {}

    @Override
    public void clicked(int slot, int button, ContainerInput input, Player player) {
        if (back != null && slot == backSlot()) {
            Runnable b = back;
            Lobby.later(viewer, b);
        } else if (slot >= 0 && slot < slots.size()) onClick(slot, button, input.name().contains("QUICK"));
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

    /** Fills the empty slots with dark glass (5.12: and puts the Back arrow in, if this screen has one). */
    protected void frame(ItemStack filler) {
        if (back != null && backSlot() >= 0) box.setItem(backSlot(), Stacks.named(new ItemStack(Items.ARROW), "§f◀ Back", "§7To the previous screen"));
        for (int i = 0; i < box.getContainerSize(); i++) if (box.getItem(i).isEmpty()) box.setItem(i, filler.copy());
    }
}
