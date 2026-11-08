package com.aidan.skyserver.goldwars;

import net.minecraft.ChatFormatting;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/** The two Gold Wars teams. Red spawns on the west (-X) side, Blue on the east (+X). */
public enum GwTeam {
    RED("Red", ChatFormatting.RED, -1, Items.OAK_PLANKS, Blocks.REDSTONE_BLOCK),
    BLUE("Blue", ChatFormatting.BLUE, 1, Items.OAK_PLANKS, Blocks.LAPIS_BLOCK);

    public final String display;
    public final ChatFormatting color;
    public final int side;      // -1 = west, +1 = east
    public final Item wool;     // team-colored building blocks
    public final Block pad;     // spawn pad block

    GwTeam(String display, ChatFormatting color, int side, Item wool, Block pad) {
        this.display = display;
        this.color = color;
        this.side = side;
        this.wool = wool;
        this.pad = pad;
    }

    /** Team name with its color code, e.g. "§cRed§r". */
    public String colored() {
        return color + display + "§r";
    }

    public GwTeam other() {
        return this == RED ? BLUE : RED;
    }
}
