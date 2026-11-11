package com.aidan.skyserver.lobby;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;

/**
 * The compass menu:
 *  row 1: Skywars, Gold Wars, Bedwars, Sky Island, the Great Hall (mystery chests)
 *  row 2: your level, daily quests, your keys, the guide
 *  row 3: the four obstacle courses (5.8: boosters and daily streak in between)
 *  row 4: Spleef / TNT Run pit (5.16: back), Duels, Wren's Weekly Voyage (5.13), Maze, Elytra Rings, Fishing
 *         (5.13 removed the Spleef and Archery buttons; Archery is still off the compass - the range is still there)
 *  row 5: the key shop, the Shard Shop and the Cosmetic Shop (5.8)
 * 5.12: screens opened from here (Shard Shop, Cosmetic Shop, Elytra courses) get a ◀ Back arrow.
 */
public final class GameMenu extends LockedMenu {
    private static final int SKYWARS = 10, GOLDWARS = 11, BEDWARS = 12, BEDWARS_DUOS = 13, ISLAND = 14, HALL = 16;
    private static final int LEVEL = 19, SKILLS = 20, QUESTS = 21, TOWN = 22, KEYS = 23, MINE = 24, GUIDE = 25;
    private static final int[] COURSE_SLOTS = {28, 30, 32, 34};
    private static final String[] COURSE_IDS = {"easy", "medium", "hard", "extreme"};
    private static final int BOOSTERS = 29, STREAK = 31;
    private static final int SPLEEF = 37, DUELS = 38, VOYAGE = 39, MAZE = 40, ELYTRA = 41, FISHING = 42, SURVIVAL = 43;
    private static final int SHOP_FIRST = 46; // 46..50 = common..legendary key shop
    private static final int SHARDS = 51, CSHOP = 52;

    private GameMenu(int id, Inventory inv, SimpleContainer box, ServerPlayer viewer) {
        super(id, inv, box, 6, viewer);
        draw();
    }

    public static void open(ServerPlayer p) {
        LockedMenu.open(p, "§8Game Menu", 6, GameMenu::new);
    }

    /** 5.12: opens a screen from the compass with a Back arrow to the compass. */
    private void sub(Runnable opener) {
        ServerPlayer v = viewer;
        Lobby.later(v, () -> LockedMenu.openWithBack(v, () -> GameMenu.open(v), opener));
    }

    private void draw() {
        box.setItem(SKYWARS, Stacks.named(new ItemStack(Items.FEATHER), "§b§lSkywars", "§7Last player standing wins.", Lobby.gameStatus("skywars"), "§eClick to join"));
        box.setItem(GOLDWARS, Stacks.named(new ItemStack(Items.GOLD_BLOCK), "§6§lGold Wars", "§76v6 teams: break the enemy's gold.", Lobby.gameStatus("goldwars"), "§eClick to join"));
        box.setItem(BEDWARS, Stacks.named(new ItemStack(Items.BED.pick(DyeColor.RED)), "§c§lBedwars §7(3v3v3v3)", "§74 teams of 3: protect your bed,",
                "§7break everyone else's.", Lobby.gameStatus("bedwars"), "§eClick to join"));
        box.setItem(BEDWARS_DUOS, Stacks.named(new ItemStack(Items.BED.pick(DyeColor.ORANGE)), "§6§lBedwars §7(2v2v2v2)", "§74 teams of 2, cheaper upgrades.",
                "§7One Bedwars match runs at a time.", Lobby.gameStatus("bedwars"), "§eClick to join"));
        box.setItem(ISLAND, Stacks.named(new ItemStack(Items.GRASS_BLOCK), "§a§lSky Island", "§7Your own island: build, farm,", "§7upgrade and grow it.",
                "§cNether Expeditions §7start there too.", "§eClick to go §7(or type /is)"));
        box.setItem(HALL, Stacks.named(new ItemStack(Items.CHEST), "§d§lMystery Chests", "§7In the Great Hall of the castle.",
                "§7Left-click a chest to see every prize and its chance.", "§eClick to go there"));

        // Level and rewards. (5.13: the old "Season X - N days left" line is gone; the battle pass shows its own season.)
        List<String> lore = new ArrayList<>();
        int[] lv = Levels.levelOf(Levels.totalXp(viewer.getUUID()));
        lore.add("§eLevel §f" + lv[0] + " §7(" + lv[1] + "/" + lv[2] + " XP)");
        lore.add(Levels.progressBar(viewer.getUUID()));
        lore.add("");
        lore.add("§6Coming up:");
        lore.addAll(Levels.upcoming(viewer.getUUID(), 5));
        lore.add("");
        lore.add("§eClick for the full list in chat");
        box.setItem(LEVEL, Stacks.named(new ItemStack(Items.EXPERIENCE_BOTTLE), "§b§lYour Level & Rewards", lore.toArray(new String[0])));

        List<String> q = new ArrayList<>(Quests.describe(viewer.getUUID()));
        box.setItem(QUESTS, Stacks.named(new ItemStack(Items.WRITABLE_BOOK), "§6§lDaily Quests", q.toArray(new String[0])));

        int[] k = Keys.of(viewer.getUUID());
        box.setItem(KEYS, Stacks.named(new ItemStack(Items.TRIPWIRE_HOOK), "§e§lYour Keys",
                Keys.COLORS[0] + "Common: §f" + k[0], Keys.COLORS[1] + "Uncommon: §f" + k[1], Keys.COLORS[2] + "Rare: §f" + k[2],
                Keys.COLORS[3] + "Epic: §f" + k[3], Keys.COLORS[4] + "Legendary: §f" + k[4], "§dKey Shards: §f" + Tokens.shards(viewer.getUUID()),
                "§7Trade keys with §e/trade <player>"));
        List<String> sk = new ArrayList<>(Skills.lines(viewer.getUUID()));
        sk.add("");
        sk.add("§7Skills never reset. They unlock ores in the");
        sk.add("§7Mine and buildings in the Town.");
        sk.add("§eClick for details");
        box.setItem(SKILLS, Stacks.named(new ItemStack(Items.ENCHANTED_BOOK), "§e§lYour Skills", sk.toArray(new String[0])));
        List<String> town = new ArrayList<>(List.of("§7Bank, Skin Shop, Faction Hall and", "§7skill buildings with daily crates:"));
        town.addAll(Town.areaLines(viewer));
        town.add("§eClick to go there");
        box.setItem(TOWN, Stacks.named(new ItemStack(Items.BELL), "§6§lThe Town", town.toArray(new String[0])));
        int ml = Skills.level(viewer.getUUID(), Skills.Skill.MINING);
        box.setItem(MINE, Stacks.named(new ItemStack(Items.IRON_PICKAXE), "§7§lThe Mine", "§7Mine ores that grow back.", "§7Your Mining level: §f" + ml,
                "§7Drops are sent to your Sky Island.", "§eClick to go down"));
        box.setItem(GUIDE, Stacks.named(new ItemStack(Items.WRITTEN_BOOK), "§e§lServer Guide", "§7Every command you can use.", "§eClick to read it in chat"));

        // Obstacle courses.
        String[] colors = {"§a", "§6", "§c", "§5"};
        DyeColor[] dyes = {DyeColor.LIME, DyeColor.ORANGE, DyeColor.RED, DyeColor.PURPLE};
        for (int i = 0; i < 4; i++) {
            LobbyDesign.Course c = course(COURSE_IDS[i]);
            if (c == null) continue;
            int tier = Parkour.REWARD_KEY_TIER[i];
            box.setItem(COURSE_SLOTS[i], Stacks.named(new ItemStack(Items.CONCRETE.pick(dyes[i])),
                    colors[i] + c.name() + " §7(" + LobbyDesign.TIER_WORDS[i].toLowerCase() + ")",
                    "§7" + (c.stations().size() - 2) + " checkpoints",
                    "§7Daily reward: " + Keys.COLORS[tier] + Keys.NAMES[tier] + " Key §7+ §6" + Economy.format(Parkour.REWARD_MONEY[i]),
                    "§eClick to go to the start"));
        }

        // 5.8: boosters and the daily streak.
        List<String> bl = new ArrayList<>(Boosters.activeLines(viewer.getUUID()));
        if (bl.isEmpty()) bl.add("§7Nothing running.");
        bl.add("§7In your bag: §f" + Tokens.withPrefix(viewer.getUUID(), "booster:").values().stream().mapToInt(Integer::intValue).sum());
        bl.add("§eClick to see and use them");
        box.setItem(BOOSTERS, Stacks.named(new ItemStack(Items.BREWING_STAND), "§b§lBoosters", bl.toArray(new String[0])));
        int day = DailyStreak.day(viewer.getUUID());
        List<String> st = new ArrayList<>();
        st.add("§7Log in every day for bigger rewards.");
        st.add("§7Your streak: §eday " + day + "/7");
        for (int d = 0; d < 7; d++) {
            int key = DailyStreak.KEY[d];
            st.add((d + 1 <= day ? "§a✔ " : "§7• ") + "Day " + (d + 1) + ": §6" + Economy.format(DailyStreak.MONEY[d])
                    + (key >= 0 ? " §7+ " + Keys.COLORS[key] + Keys.NAMES[key] + " Key" : ""));
        }
        st.add("§8Miss a day: back " + DailyStreak.DROP_BACK + " days.");
        box.setItem(STREAK, Stacks.named(new ItemStack(Items.CLOCK), "§6§lDaily Streak", st.toArray(new String[0])));

        // Activities.
        // 5.16: the Spleef / TNT Run pit is back on the compass.
        box.setItem(SPLEEF, Stacks.named(new ItemStack(Items.SNOW_BLOCK), "§f§lSpleef & TNT Run Pit", "§7Break the floor under the others,", "§7or outrun the falling floor.",
                "§7Last one standing wins the round.", "§7Bots fill the pit if you're alone.", "§eClick to join the queue §7(/spleef)"));
        box.setItem(DUELS, Stacks.named(new ItemStack(Items.IRON_SWORD), "§c§lDuel Arena", "§7Challenge someone: §e/duel <name> [bet]", "§7The winner takes the whole bet.", "§eClick to go there"));
        box.setItem(VOYAGE, Voyage.compassIcon(viewer)); // 5.13: Wren's Weekly Voyage
        box.setItem(MAZE, Stacks.named(new ItemStack(Items.OAK_LEAVES), "§2§lHedge Maze", "§7Find the fountain in the middle.", "§7Daily reward §6" + Economy.format(Activities.MAZE_MONEY) + "§7.", "§eClick to go there"));
        box.setItem(ELYTRA, Stacks.named(new ItemStack(Items.ELYTRA), "§d§lElytra Rings", "§7Fly through every ring, land on the pad.", "§7Daily reward §6" + Economy.format(Activities.ELYTRA_MONEY) + "§7.", "§eClick to pick a course"));
        box.setItem(FISHING, Stacks.named(new ItemStack(Items.FISHING_ROD), "§b§lFishing Pond", "§7Every catch pays a little.", "§7Any catch can be a key!", "§eClick to go there"));
        if (Lobby.SURVIVAL_OPEN) {
            box.setItem(SURVIVAL, Stacks.named(new ItemStack(Items.IRON_PICKAXE), "§a§lSurvival", "§7Back to your survival world,", "§7with your own inventory.", "§eClick to go"));
        }

        long bal = Economy.balance(viewer.level().getServer(), viewer.getUUID());
        for (int t = 0; t < 5; t++) {
            box.setItem(SHOP_FIRST + t, Stacks.named(new ItemStack(Items.TRIPWIRE_HOOK), "§fBuy a " + Keys.COLORS[t] + Keys.NAMES[t] + " Key",
                    "§7Price: §6" + Economy.format(Keys.PRICES[t]), bal >= 0 ? "§7Your money: §6" + Economy.format(bal) : "",
                    "§7Left-click a chest in the Great Hall", "§7to see every prize and its chance.", "§eClick to buy"));
        }
        box.setItem(SHARDS, Stacks.named(new ItemStack(Items.AMETHYST_SHARD), "§d§lShard Shop", "§7Your Key Shards: §f" + Tokens.shards(viewer.getUUID()),
                "§7Trade shards for the key, cosmetic", "§7or booster you want.", "§eClick to open"));
        box.setItem(CSHOP, Stacks.named(new ItemStack(Items.ARMOR_STAND), "§5§lCosmetic Shop", "§7Titles, trails, kill and victory", "§7effects. New picks every Monday.", "§eClick to open"));
        frame(Stacks.named(new ItemStack(Items.STAINED_GLASS_PANE.pick(DyeColor.BLACK)), " "));
        broadcastChanges();
    }

    private static LobbyDesign.Course course(String id) {
        for (LobbyDesign.Course c : LobbyDesign.COURSES) if (c.id().equals(id)) return c;
        return null;
    }

    @Override
    protected void onClick(int slot, int button, boolean shift) {
        for (int i = 0; i < 4; i++) {
            if (slot == COURSE_SLOTS[i]) {
                String id = COURSE_IDS[i];
                Lobby.later(viewer, () -> {
                    Activities.leaveAll(viewer);
                    Parkour.teleportToStart(viewer, id);
                    Lobby.refreshKit(viewer);
                });
                return;
            }
        }
        switch (slot) {
            case SKYWARS -> Lobby.later(viewer, () -> Lobby.joinGame(viewer, "skywars"));
            case GOLDWARS -> Lobby.later(viewer, () -> Lobby.joinGame(viewer, "goldwars"));
            case BEDWARS -> Lobby.later(viewer, () -> Lobby.joinGame(viewer, "bedwars_trios"));
            case BEDWARS_DUOS -> Lobby.later(viewer, () -> Lobby.joinGame(viewer, "bedwars_duos"));
            case ISLAND -> Lobby.later(viewer, () -> com.aidan.skyserver.island.Islands.goHome(viewer));
            case HALL -> Lobby.later(viewer, () -> Lobby.toGreatHall(viewer));
            case LEVEL -> Lobby.later(viewer, () -> Lobby.showLevel(viewer));
            case QUESTS -> Lobby.later(viewer, () -> {
                Lobby.msg(viewer, "§6§lDAILY QUESTS");
                for (String line : Quests.describe(viewer.getUUID())) Lobby.msg(viewer, " " + line);
            });
            case GUIDE -> Lobby.later(viewer, () -> Guide.open(viewer));
            case SKILLS -> Lobby.later(viewer, () -> Skills.show(viewer));
            case TOWN -> Lobby.later(viewer, () -> Activities.goTo(viewer, "town"));
            case MINE -> Lobby.later(viewer, () -> Activities.goTo(viewer, "mine"));
            case BOOSTERS -> Lobby.later(viewer, () -> Boosters.show(viewer));
            case SHARDS -> sub(() -> ShardShop.open(viewer)); // 5.12: with a Back arrow
            case CSHOP -> sub(() -> CosmeticShop.open(viewer)); // 5.12: with a Back arrow
            case VOYAGE -> sub(() -> VoyageMenu.open(viewer)); // 5.13: with a Back arrow
            case SPLEEF -> Lobby.later(viewer, () -> Activities.goTo(viewer, "spleef")); // 5.16: to the pit, into the queue
            case DUELS -> Lobby.later(viewer, () -> {
                Activities.goTo(viewer, "duels");
                Lobby.msg(viewer, "§cDuel Arena: §7type §e/duel <player> [bet] §7to challenge someone.");
            });
            case MAZE -> Lobby.later(viewer, () -> Activities.goTo(viewer, "maze"));
            case ELYTRA -> sub(() -> Activities.goTo(viewer, "elytra")); // 5.12: the course picker gets a Back arrow
            case FISHING -> Lobby.later(viewer, () -> Activities.goTo(viewer, "pond"));
            case SURVIVAL -> {
                if (Lobby.SURVIVAL_OPEN) Lobby.later(viewer, () -> Lobby.toSurvival(viewer));
            }
            default -> {
                int t = slot - SHOP_FIRST;
                if (t >= 0 && t < 5) {
                    if (Economy.take(viewer.level().getServer(), viewer.getUUID(), Keys.PRICES[t])) {
                        Keys.give(viewer.getUUID(), t, 1);
                        Lobby.msg(viewer, "§aBought a " + Keys.COLORS[t] + Keys.NAMES[t] + " Key §afor §6" + Economy.format(Keys.PRICES[t]) + "§a.");
                        Lobby.refreshKit(viewer);
                    } else {
                        Lobby.msg(viewer, "§cYou need " + Economy.format(Keys.PRICES[t]) + " for that.");
                    }
                    draw();
                }
            }
        }
    }
}
