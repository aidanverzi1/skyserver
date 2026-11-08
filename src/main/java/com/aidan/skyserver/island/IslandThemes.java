package com.aidan.skyserver.island;

import com.aidan.skyserver.Skyserver;
import com.aidan.skyserver.lobby.Lobby;
import com.aidan.skyserver.lobby.LockedMenu;
import com.aidan.skyserver.lobby.Stacks;
import com.aidan.skyserver.lobby.Tokens;
import net.minecraft.server.MinecraftServer;
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
 * 5.8: Island Themes (/is theme, needs a theme token from an Epic chest). Changes the biome over
 * the whole island area, which changes the grass, leaf, water and sky colours, the weather and the
 * ambience. No mobs spawn from it: IslandSpawnMixin stops all natural spawning on the islands.
 *
 * Done with the vanilla fillbiome command, one 16x16x128 column at a time (the command's size
 * limit), a few per tick so nothing lags.
 */
public final class IslandThemes extends LockedMenu {
    /** biome id, name, icon. */
    record Theme(String biome, String name, Item icon, String note) {}

    static final List<Theme> THEMES = List.of(
            new Theme("minecraft:the_void", "Classic Sky", Items.GLASS, "The starting look."),
            new Theme("minecraft:plains", "Meadow", Items.GRASS_BLOCK, "Bright green, rain."),
            new Theme("minecraft:flower_forest", "Flower Forest", Items.POPPY, "Lush green, rain."),
            new Theme("minecraft:cherry_grove", "Cherry Grove", Items.CHERRY_SAPLING, "Pink-tinted, soft light."),
            new Theme("minecraft:jungle", "Jungle", Items.JUNGLE_SAPLING, "Deep green, lots of rain."),
            new Theme("minecraft:savanna", "Savanna", Items.ACACIA_SAPLING, "Dry olive grass, no rain."),
            new Theme("minecraft:desert", "Desert", Items.SAND, "Sandy grass, never rains."),
            new Theme("minecraft:badlands", "Badlands", Items.TERRACOTTA, "Brown grass, never rains."),
            new Theme("minecraft:swamp", "Swamp", Items.LILY_PAD, "Murky water and grass."),
            new Theme("minecraft:mushroom_fields", "Mushroom Isle", Items.RED_MUSHROOM, "Purple-ish, calm."),
            new Theme("minecraft:pale_garden", "Pale Garden", Items.PALE_OAK_SAPLING, "Grey, misty and quiet."),
            new Theme("minecraft:snowy_plains", "Snowfield", Items.SNOW_BLOCK, "Snow falls. §cOpen water can freeze!"));

    private IslandThemes(int id, Inventory inv, SimpleContainer box, ServerPlayer viewer) {
        super(id, inv, box, 3, viewer);
        draw();
    }

    public static void open(ServerPlayer p) {
        Islands.Island is = Islands.islandOf(p.getUUID());
        if (is == null) {
            Lobby.msg(p, "§cYou don't have an island yet. §7/is");
            return;
        }
        LockedMenu.open(p, "§8Island Themes", 3, IslandThemes::new);
    }

    private void draw() {
        int tokens = Tokens.count(viewer.getUUID(), Tokens.THEME);
        for (int i = 0; i < THEMES.size() && i < 18; i++) {
            Theme t = THEMES.get(i);
            box.setItem(i, Stacks.named(new ItemStack(t.icon()), "§a§l" + t.name(), "§7" + t.note(),
                    tokens > 0 ? "§eClick to use 1 theme token" : "§8Needs a theme token (Epic chests)"));
        }
        box.setItem(22, Stacks.named(new ItemStack(Items.PAPER), "§fTheme tokens: §a" + tokens, "§7Only the island owner can change it.",
                "§7It covers your whole build area."));
        frame(Stacks.named(new ItemStack(Items.STAINED_GLASS_PANE.pick(DyeColor.GREEN)), " "));
        broadcastChanges();
    }

    @Override
    protected void onClick(int slot, int button, boolean shift) {
        if (slot < 0 || slot >= THEMES.size()) return;
        Islands.Island is = Islands.islandOf(viewer.getUUID());
        if (is == null) return;
        if (!is.owner.equals(viewer.getUUID())) {
            Lobby.msg(viewer, "§cOnly the island owner can change the theme.");
            return;
        }
        if (!Tokens.take(viewer.getUUID(), Tokens.THEME, 1)) {
            Lobby.msg(viewer, "§cYou need a theme token. §7They come from Epic mystery chests.");
            return;
        }
        Theme t = THEMES.get(slot);
        viewer.closeContainer();
        apply(viewer.level().getServer(), is, t.biome());
        Islands.islandChat(is, "§a" + viewer.getName().getString() + " changed the island theme to §f" + t.name() + "§a! §7(It spreads over a few seconds.)");
    }

    /** Re-paints the island's biome, a few chunk columns per tick. */
    static void apply(MinecraftServer server, Islands.Island is, String biome) {
        int half = is.size / 2 + 2;
        int x1 = is.cx() - half, x2 = is.cx() + half, z1 = is.cz() - half, z2 = is.cz() + half;
        int y1 = Math.max(-64, is.minY - 8), y2 = y1 + 127;
        List<String> cmds = new ArrayList<>();
        for (int cx = Math.floorDiv(x1, 16); cx <= Math.floorDiv(x2, 16); cx++)
            for (int cz = Math.floorDiv(z1, 16); cz <= Math.floorDiv(z2, 16); cz++)
                cmds.add("execute in " + Islands.ISLANDS.identifier() + " run fillbiome " + (cx * 16) + " " + y1 + " " + (cz * 16) + " "
                        + (cx * 16 + 15) + " " + y2 + " " + (cz * 16 + 15) + " " + biome);
        runBatch(server, cmds, 0);
    }

    private static void runBatch(MinecraftServer server, List<String> cmds, int from) {
        int to = Math.min(cmds.size(), from + 16);
        for (int i = from; i < to; i++) {
            try {
                server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(), cmds.get(i));
            } catch (Throwable t) {
                Skyserver.LOGGER.warn("Island theme: {} failed: {}", cmds.get(i), t.toString());
            }
        }
        if (to < cmds.size()) Lobby.runLaterTicks(1, () -> runBatch(server, cmds, to));
    }
}
