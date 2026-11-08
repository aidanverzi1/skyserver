package com.aidan.skyserver.goldwars;

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
 * A chest-style menu for picking a class. Works on vanilla clients because it's
 * a normal chest screen; the server just ignores item movement and reads clicks.
 */
public class ClassMenu extends ChestMenu {
    private static final int FIRST_SLOT = 10; // middle row of a 3-row chest

    private ClassMenu(int id, Inventory playerInventory, SimpleContainer container) {
        super(MenuType.GENERIC_9x3, id, playerInventory, container, 3);
    }

    public static void open(ServerPlayer player) {
        SimpleContainer container = new SimpleContainer(27);
        GwClass[] classes = GwClass.values();
        for (int i = 0; i < classes.length; i++) {
            container.setItem(FIRST_SLOT + i, GoldWarsGame.classIcon(player, classes[i]));
        }
        player.openMenu(new SimpleMenuProvider(
                (id, inv, p) -> new ClassMenu(id, inv, container),
                Component.literal("Choose your class")));
    }

    @Override
    public void clicked(int slot, int button, ContainerInput containerInput, Player player) {
        int index = slot - FIRST_SLOT;
        GwClass[] classes = GwClass.values();
        boolean chosen = false;
        if (index >= 0 && index < classes.length && player instanceof ServerPlayer sp) {
            chosen = GoldWarsGame.chooseClass(sp, classes[index]);
            if (chosen) GoldWarsGame.runNextTick(sp::closeContainer);
        }
        // Never actually move items: undo anything the client tried to do.
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
}
