package com.aidan.skyserver.island;

import com.aidan.skyserver.lobby.Economy;
import com.aidan.skyserver.lobby.Lobby;
import com.aidan.skyserver.lobby.LockedMenu;
import com.aidan.skyserver.lobby.Stacks;
import com.aidan.skyserver.lobby.Tokens;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** The /is menu: home, info, challenges, visitors, the upgrades, themes, and (5.20) Captain Wren's ship room. */
public final class IslandMenu extends LockedMenu {
    private static final int HOME = 10, INFO = 12, CHALLENGES = 14, VISITORS = 16;
    private static final int THEME = 20, EXPEDITION = 22, VOUCHERS = 24;
    private static final int SIZE = 28, GEN = 30, CROPS = 32, EXTRA = 34, HELP = 40, SPEED = 39;
    private static final int[] EGG_SLOTS = {37, 38, 42, 43};
    private static final net.minecraft.world.item.Item[] EGGS = {Items.SHEEP_SPAWN_EGG, Items.COW_SPAWN_EGG, Items.CHICKEN_SPAWN_EGG, Items.PIG_SPAWN_EGG};
    private static final String[] EGG_NAMES = {"Sheep", "Cow", "Chicken", "Pig"};
    private static final long[] EGG_PRICES = {400, 400, 250, 350};

    private IslandMenu(int id, Inventory inv, SimpleContainer box, ServerPlayer viewer) {
        super(id, inv, box, 5, viewer);
        draw();
    }

    public static void open(ServerPlayer p) {
        LockedMenu.open(p, "§8Sky Island", 5, IslandMenu::new);
    }

    private void draw() {
        Islands.Island is = Islands.islandOf(viewer.getUUID());
        if (is == null) return;
        long bal = Economy.balance(viewer.level().getServer(), viewer.getUUID());
        String money = bal >= 0 ? "§7Your money: §6" + Economy.format(bal) : "";
        box.setItem(HOME, Stacks.named(new ItemStack(Items.GRASS_BLOCK), "§a§lGo Home", "§7Teleport to your island's home.", "§7Set it with §e/is sethome§7.", "§eClick"));
        box.setItem(INFO, Stacks.named(new ItemStack(Items.FILLED_MAP), "§e§l" + is.name(),
                "§7Island level: §f" + is.level() + " §8(highest server level of the crew)",
                "§7Island worth: §f" + is.worth() + " §8(" + is.points + " points)",
                "§7Build area: §f" + is.size + " x " + is.size,
                "§7Members: §f" + (is.members.isEmpty() ? "just you" : is.ownerName + ", " + String.join(", ", is.memberNames.values())),
                "§7Visitors: " + (is.open ? "§aanyone can visit" : "§eneed to ask"),
                "§8Worth grows with valuable blocks", "§8(iron/gold/diamond blocks, beacons...)"));
        box.setItem(CHALLENGES, Stacks.named(new ItemStack(Items.WRITABLE_BOOK), "§d§lChallenges",
                "§7Bring items, earn money, XP and keys.", "§7Done: §f" + is.completed.size() + "/" + Challenges.ALL.size(), "§eClick to open"));
        boolean owner = is.owner.equals(viewer.getUUID());
        box.setItem(VISITORS, Stacks.named(new ItemStack(is.open ? Items.OAK_DOOR : Items.IRON_DOOR), is.open ? "§a§lOpen to visitors" : "§e§lVisitors must ask",
                "§7Visitors can look around, use doors", "§7and crafting tables - nothing else.", owner ? "§eClick to switch" : "§8(owner only)"));

        // 5.8
        int themes = Tokens.count(viewer.getUUID(), Tokens.THEME);
        box.setItem(THEME, Stacks.named(new ItemStack(Items.FLOWERING_AZALEA), "§a§lIsland Theme",
                "§7Change your island's biome: grass,", "§7leaves, water and sky colours.", "§7Theme tokens: §f" + themes + " §8(from Epic chests)",
                themes > 0 ? "§eClick to pick a theme" : "§8You need a theme token"));
        // 5.20: every expedition starts from Captain Wren in his ship room now.
        box.setItem(EXPEDITION, Stacks.named(new ItemStack(Items.DARK_OAK_BOAT), "§6§lCaptain Wren's Ship Room",
                "§7Talk to Wren there to start any", "§7expedition: §cNether§7, §5End§7, §3Ancient City§7,", "§bSunken Monument§7. Also his quests,",
                "§7the Tribute Chests and the desk.", is.wrenRoom != null ? "§eClick to go there" : "§eNot placed yet: §f/is shiproom place"));
        box.setItem(VOUCHERS, Stacks.named(new ItemStack(Items.PAPER), "§d§lVouchers & Tickets",
                "§7Upgrade vouchers (25% off): §f" + Tokens.count(viewer.getUUID(), Tokens.UPGRADE_VOUCHER),
                "§8(used on your next upgrade below)",
                "§7Expedition tickets: §aEasy " + Tokens.count(viewer.getUUID(), Tokens.TICKET[0]) + " §eNormal " + Tokens.count(viewer.getUUID(), Tokens.TICKET[1])
                        + " §cHard " + Tokens.count(viewer.getUUID(), Tokens.TICKET[2]) + " §4Nightmare " + Tokens.count(viewer.getUUID(), Tokens.TICKET[3]),
                "§7End Expedition tickets: §5" + Tokens.count(viewer.getUUID(), Tokens.END_TICKET)));

        int step = (is.size - Islands.START_SIZE) / Islands.SIZE_STEP;
        boolean sizeMax = is.size >= Islands.MAX_SIZE;
        box.setItem(SIZE, Stacks.named(new ItemStack(Items.MAP), "§b§lBigger Island",
                "§7Now: §f" + is.size + " x " + is.size + (sizeMax ? " §a(max)" : ""),
                sizeMax ? "" : "§7Next: §f" + (is.size + Islands.SIZE_STEP) + " x " + (is.size + Islands.SIZE_STEP) + " §7for §6" + Economy.format(Islands.SIZE_PRICES[step]),
                money, sizeMax ? "" : "§eClick to buy"));
        boolean genMax = is.genLevel >= Islands.GEN_PRICES.length;
        box.setItem(GEN, Stacks.named(new ItemStack(Items.LODESTONE), "§b§lGenerator Level " + is.genLevel,
                "§7Makes: §f" + Islands.genOdds(is.genLevel),
                genMax ? "§a(max)" : "§7Level " + (is.genLevel + 1) + ": §f" + Islands.genOdds(is.genLevel + 1),
                genMax ? "" : "§7Price: §6" + Economy.format(Islands.GEN_PRICES[is.genLevel]), money, genMax ? "" : "§eClick to upgrade"));
        boolean cropMax = is.cropLevel >= Islands.CROP_PRICES.length - 1;
        box.setItem(CROPS, Stacks.named(new ItemStack(Items.WHEAT), "§b§lCrop Growth Level " + is.cropLevel,
                "§7Crops, saplings and cane grow faster", "§7near island members.",
                cropMax ? "§a(max)" : "§7Next level: §6" + Economy.format(Islands.CROP_PRICES[is.cropLevel + 1]), money, cropMax ? "" : "§eClick to upgrade"));
        box.setItem(EXTRA, Stacks.named(new ItemStack(Items.LODESTONE), "§b§lExtra Generator",
                "§7Another lodestone generator to place.", "§7Price: §6" + Economy.format(Islands.EXTRA_GENERATOR_PRICE), money, "§eClick to buy"));
        box.setItem(HELP, Stacks.named(new ItemStack(Items.PAPER), "§f§lCommands", "§7Click to list the island commands."));
        boolean speedMax = is.genSpeed >= Islands.GEN_SPEED_TICKS.length - 1;
        int ns = Math.min(Islands.GEN_SPEED_TICKS.length - 1, is.genSpeed + 1);
        box.setItem(SPEED, Stacks.named(new ItemStack(Items.CLOCK), "§b§lGenerator Speed " + is.genSpeed,
                "§7Blocks grow back every §f" + (Islands.GEN_SPEED_TICKS[is.genSpeed] / 20.0) + "s" + (speedMax ? " §a(max)" : ""),
                speedMax ? "" : "§7Next: §f" + (Islands.GEN_SPEED_TICKS[ns] / 20.0) + "s §7for §6" + Economy.format(Islands.GEN_SPEED_PRICES[ns]),
                speedMax ? "" : "§7Needs generator level " + Islands.GEN_SPEED_NEEDS_LEVEL[ns], money, speedMax ? "" : "§eClick to upgrade"));
        for (int i = 0; i < EGGS.length; i++)
            box.setItem(EGG_SLOTS[i], Stacks.named(new ItemStack(EGGS[i]), "§a§l" + EGG_NAMES[i] + " Spawn Egg",
                    "§7Right-click the ground on your island", "§7to get a " + EGG_NAMES[i].toLowerCase() + ". Breed them for", "§7Shepherd XP.",
                    "§7Price: §6" + Economy.format(EGG_PRICES[i]), money, "§eClick to buy one"));
        frame(Stacks.named(new ItemStack(Items.STAINED_GLASS_PANE.pick(DyeColor.LIGHT_BLUE)), " "));
        broadcastChanges();
    }

    @Override
    protected void onClick(int slot, int button, boolean shift) {
        Islands.Island is = Islands.islandOf(viewer.getUUID());
        if (is == null) return;
        String result = null;
        switch (slot) {
            case HOME -> Lobby.later(viewer, () -> Islands.goHome(viewer));
            case CHALLENGES -> Lobby.later(viewer, () -> ChallengeMenu.open(viewer, Challenges.unlockedTier(is.level())));
            case VISITORS -> {
                if (!is.owner.equals(viewer.getUUID())) return;
                is.open = !is.open;
                Islands.save();
            }
            case THEME -> Lobby.later(viewer, () -> IslandThemes.open(viewer));
            case EXPEDITION -> {
                viewer.closeContainer();
                Lobby.later(viewer, () -> WrenRoom.goTo(viewer));
            }
            case SIZE -> result = Islands.buySize(viewer, is);
            case GEN -> result = Islands.buyGenerator(viewer, is);
            case CROPS -> result = Islands.buyCrops(viewer, is);
            case SPEED -> result = Islands.buyGenSpeed(viewer, is);
            case EXTRA -> result = Islands.buyExtraGenerator(viewer);
            case HELP -> Lobby.later(viewer, () -> Islands.help(viewer));
            default -> {
                int egg = -1;
                for (int i = 0; i < EGG_SLOTS.length; i++) if (EGG_SLOTS[i] == slot) egg = i;
                if (egg < 0) return;
                if (!Economy.take(viewer.level().getServer(), viewer.getUUID(), EGG_PRICES[egg])) result = "§cThat costs " + Economy.format(EGG_PRICES[egg]) + ".";
                else {
                    ItemStack e = new ItemStack(EGGS[egg]);
                    if (!Islands.isIsland(viewer.level())) com.aidan.skyserver.lobby.Mailbox.send(viewer.level().getServer(), viewer.getUUID(), e);
                    else if (!viewer.getInventory().add(e)) viewer.spawnAtLocation((net.minecraft.server.level.ServerLevel) viewer.level(), e);
                    result = "§aBought a " + EGG_NAMES[egg] + " Spawn Egg.";
                }
            }
        }
        if (result != null) Lobby.msg(viewer, result);
        draw();
    }
}
