package com.aidan.skyserver.island;

import com.aidan.skyserver.lobby.Economy;
import com.aidan.skyserver.lobby.Keys;
import com.aidan.skyserver.lobby.Levels;
import com.aidan.skyserver.lobby.Lobby;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.stats.Stats;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.util.*;

/**
 * Captain Wren and the island quest line. Wren stands by the path on every Sky Island (owners and
 * co-op members can move him with /is movewren); right-click him to start. The quests teach the
 * island step by step (bridge to the little island, the generator, farming, smelting, upgrades...)
 * and pay money, XP and keys along the way.
 *
 * 5.25: every player has their OWN quest line (wren-quests.json): own progress, own rewards, and it stays with
 * them if they change islands. Island things (the islet chest, generator level, size, challenges) count for
 * everyone on the island. Players who were on an island when 5.25 arrived start at that island's old shared
 * quest; anyone who joins later starts at quest 1. Edit the steps below to change the quest line.
 *
 * 5.27: the quest line visits every activity: the lobby Mine, an elytra course, a mystery chest, the fishing pond,
 * Wren's Voyage, a copper golem, the chicken, all four expeditions, Wren's Charts (13 new quests, 68 in all; the
 * Spleef quest became "clear 10 expeditions"). Everyone keeps the quest they were on (PLAYER_LINE 3 in
 * wren-quests.json); new quests that land before your spot are skipped.
 */
public final class IslandQuests {
    public static final String WREN_TAG = "skyserver_wren";

    /**
     * REACH_ISLET, LEVEL (the player's own server level), GEN (generator level),
     *   SIZE (expansions), CHALLENGE: island things.
     * MINED / CRAFTED: Minecraft's own block-mined / item-crafted counters, counted from when the
     *   quest starts (buying from the shop doesn't count - you have to make it).
     * STAT: another Minecraft counter (animals_bred, fish_caught, enchant_item), from when it starts.
     * COUNT: one of our counters (PlayerStats: sw_win, gw_win, game_kill, harvest...), from when it starts.
     * TOTAL: one of our counters, all-time (for "have you ever..." requirements).
     * SKILL: have [amount] skills at level [target] or higher.
     * 5.27: COUNT / TOTAL targets may add counters together ("expedition_win+city_win").
     * GOLEM: the island has a copper golem on its crew (island thing). CHART: Wren's Charts stamps - "nether_0,nether_1"
     *   = has any of them (amount 1), "*" = how many stamps in all.
     */
    enum Kind { REACH_ISLET, MINED, CRAFTED, PLACED, LEVEL, GEN, SIZE, CHALLENGE, STAT, COUNT, TOTAL, SKILL, GOLEM, CHART }

    /** One quest. target = block/item id for MINED/CRAFTED/PLACED ("#sapling" = any sapling). */
    record Step(Kind kind, String target, int amount, String task, String done, long money, int xp, int keyTier) {}

    static final List<Step> STEPS = List.of(
            new Step(Kind.REACH_ISLET, "", 1, "Build a bridge east to the little island (it's 25 blocks across) and open the chest there.",
                    "Ha! You made it across. That chest is from my old airship - keep what's in it.", 300, 40, -1),
            new Step(Kind.MINED, "cobblestone", 32, "Mine 32 cobblestone from the generator by the house (it grows back).",
                    "Endless stone! An islander's best friend.", 300, 40, -1),
            new Step(Kind.CRAFTED, "furnace", 1, "Craft a furnace.", "A fine furnace. Let's put it to work.", 200, 30, -1),
            new Step(Kind.CRAFTED, "stone", 16, "Smelt 16 cobblestone into stone.", "Smooth as a calm sky.", 300, 40, -1),
            new Step(Kind.PLACED, "wheat", 16, "Plant 16 wheat seeds (hoe the dirt next to water first).", "Now we'll never go hungry.", 300, 40, -1),
            new Step(Kind.CRAFTED, "bread", 5, "Bake 5 bread from your wheat.", "Smells like home. Thank you, friend.", 400, 50, 0),
            new Step(Kind.PLACED, "#sapling", 4, "Plant 4 saplings so you'll never run out of wood.", "A forest in the clouds. I love it.", 250, 40, -1),
            new Step(Kind.CRAFTED, "chest", 2, "Craft 2 chests to keep your things in.", "Every captain needs a good hold.", 250, 30, -1),
            new Step(Kind.COUNT, "mine_ore", 20, "Visit the lobby Mine (under the Town - use the compass) and mine 20 ores. They come to your island: /claim here.",
                    "Ores from under the Town! Every bit helps out here.", 400, 50, -1), // 5.27
            new Step(Kind.LEVEL, "", 3, "Reach server level 3 (everything you do on the server gives XP - type /level).", "You're growing, captain!", 500, 60, 0),
            new Step(Kind.COUNT, "elytra", 1, "Fly an elytra ring course in the lobby (compass -> Elytra Rings).",
                    "Through the rings! You've got a sailor's nerve.", 500, 60, -1), // 5.27
            new Step(Kind.COUNT, "crate", 1, "Open a mystery chest in the lobby's Great Hall with any key (/keys shows yours).",
                    "What did you get? Keys turn up everywhere - save them up!", 400, 50, -1), // 5.27
            new Step(Kind.GEN, "", 2, "Upgrade your generator to level 2 in the /is menu (it starts making ores).",
                    "Ores from thin air! Now you're thinking like an islander.", 1_000, 80, -1),
            new Step(Kind.MINED, "iron_ore", 10, "Mine 10 iron ore from your generator.", "Iron! Now we can build properly.", 750, 70, -1),
            new Step(Kind.CRAFTED, "iron_pickaxe", 1, "Smelt the iron and craft an iron pickaxe.", "That'll cut through anything.", 600, 60, 1),
            new Step(Kind.CHALLENGE, "", 1, "Complete an island challenge (/is challenges).", "Challenges are a fine way to earn a living.", 750, 70, -1),
            new Step(Kind.COUNT, "pond_fish", 10, "Catch 10 fish at the lobby Fishing Pond (compass -> Fishing Pond). The pond pays for good fish!",
                    "A fine catch. The pond's a good place to earn on a quiet day.", 600, 60, -1), // 5.27
            new Step(Kind.SIZE, "", 1, "Buy your first island expansion in the /is menu.", "More sky to build on. The horizon is yours!", 3_000, 150, 1),
            new Step(Kind.LEVEL, "", 10, "Reach server level 10.", "Look at this place! You've built a real home. I'm proud of you, captain.", 5_000, 300, 2),

            // ===== Chapter 2: The Captain's Trials (ends with an Epic Key) =====
            new Step(Kind.TOTAL, "sw_win", 1, "Before the Trials: win a game of Skywars.", "A Skywars champion! Good.", 1_000, 100, -1),
            new Step(Kind.TOTAL, "gw_win", 1, "Win a game of Gold Wars.", "You can fight with a crew too. Excellent.", 1_000, 100, -1),
            new Step(Kind.TOTAL, "bw_win", 1, "Win a game of Bedwars.", "Guarded your bed and broke theirs! A captain always protects her bunk.", 1_000, 100, -1),
            new Step(Kind.COUNT, "voyage_stamp", 1, "Earn a stamp in Wren's Weekly Voyage - one game gets a twist from Thursday to Sunday (/voyage).",
                    "A Voyage stamp! I'll mark it in the ship's log.", 1_500, 120, -1), // 5.27
            new Step(Kind.SKILL, "5", 1, "Get any skill to level 5 (/skills).", "You're learning a trade. The Trials can begin!", 1_000, 100, 1),
            new Step(Kind.MINED, "iron_ore", 64, "Mine 64 iron ore on your island.", "That's a proper haul of iron.", 2_000, 150, -1),
            new Step(Kind.CRAFTED, "iron_ingot", 128, "Smelt 128 iron ingots.", "The furnaces have been busy, I see.", 2_000, 150, -1),
            new Step(Kind.CRAFTED, "anvil", 1, "Forge an anvil.", "Every good workshop needs one.", 1_500, 120, -1),
            new Step(Kind.GOLEM, "", 1, "Get a copper golem for your island crew: build one next to a copper chest, then sneak-click the chest for the golem hub.",
                    "A little copper deckhand! Give it some chests to sort.", 2_000, 150, -1), // 5.27
            new Step(Kind.STAT, "animals_bred", 20, "Breed 20 animals (Wren's starter flock, or spawn eggs from /is menu).", "A proper farm! I can hear them from here.", 2_500, 180, 1),
            new Step(Kind.STAT, "fish_caught", 100, "Catch 100 fish.", "Patience - the mark of a true sailor.", 2_500, 180, -1),
            new Step(Kind.MINED, "wheat", 500, "Harvest 500 wheat.", "Golden fields in the sky. Beautiful.", 2_500, 180, -1),
            new Step(Kind.CRAFTED, "cake", 10, "Bake 10 cakes.", "Ten cakes! Save me a slice.", 2_500, 180, -1),
            new Step(Kind.MINED, "diamond_ore", 20, "Mine 20 diamond ore on your island (upgrade your generator).", "Diamonds! Now we're talking.", 4_000, 250, 2),
            new Step(Kind.CRAFTED, "enchanting_table", 1, "Craft an enchanting table.", "Magic, out here in the clouds...", 3_000, 200, -1),
            new Step(Kind.STAT, "enchant_item", 10, "Enchant 10 items.", "Glowing gear for a glowing captain.", 3_500, 220, -1),
            new Step(Kind.CHART, "nether_0,nether_1,nether_2,nether_3", 1, "Clear a Nether Expedition with me (ship room). Bring diamond armour, a bow and golden apples - or try it first with friends.",
                    "Back from the Nether in one piece! I knew you had it in you.", 4_000, 250, 1), // 5.27
            new Step(Kind.COUNT, "snack_fed", 1, "Feed your chicken a Little Snack when it's hurt (it fights with you on expeditions; grow snacks on a Snack Planter).",
                    "Happy chicken, happy captain.", 1_500, 120, -1), // 5.27
            new Step(Kind.COUNT, "duel_win", 5, "Win 5 duels in the lobby (practice bots count).", "Quick with a sword, aren't you?", 3_000, 200, -1),
            new Step(Kind.COUNT, "bw_bed", 5, "Break 5 enemy beds in Bedwars.", "Five beds! Nobody's sleeping well tonight.", 3_000, 200, 1),
            new Step(Kind.COUNT, "course_hard", 1, "Finish the Sky Spire (hard) obstacle course.", "Light on your feet! I'd never manage that.", 3_500, 220, 2),
            new Step(Kind.CHART, "city_0,city_1,city_2,city_3", 1, "Escape the Ancient City with the relic (stay quiet, crouch on carpet, bring a recovery compass).",
                    "Out of the deep dark! Old Ves will want to hear every word.", 4_000, 250, -1), // 5.27
            new Step(Kind.CHART, "sunken_0,sunken_1,sunken_2,sunken_3", 1, "Clear the Sunken Monument (the fare is farm items in the Tribute Chests).",
                    "The Tidemother beaten! The sea owes you one, captain.", 4_000, 250, 1), // 5.27
            new Step(Kind.SKILL, "15", 3, "Get 3 skills to level 15.", "A captain of many trades.", 4_000, 250, -1),
            new Step(Kind.LEVEL, "", 35, "Reach server level 35.", "Your name's known across the skies now.", 6_000, 350, -1),
            new Step(Kind.LEVEL, "", 45, "Reach server level 45 to finish the Trials.",
                    "You've passed every Trial. Take this - I've been saving it for someone worthy. An Epic Key!", 10_000, 500, 3),

            // ===== Chapter 3: The Legend of the Skies (about a month; ends with a Legendary Key) =====
            new Step(Kind.SKILL, "50", 2, "To begin the Legend: max out 2 skills (level 50).", "Two masteries. Now the real voyage begins.", 10_000, 500, 2),
            new Step(Kind.CHART, "end_10", 1, "Reach wave 10 of the End Expedition (full netherite only).",
                    "The End, and back again! Few captains can say that.", 12_000, 600, -1), // 5.27
            new Step(Kind.COUNT, "island_ore", 1_000, "Mine 1,000 ores on your island.", "A thousand ores! The generators must be worn smooth.", 12_000, 600, -1),
            new Step(Kind.COUNT, "harvest", 5_000, "Harvest 5,000 grown crops on your island.", "Enough to feed a fleet.", 12_000, 600, -1),
            new Step(Kind.STAT, "animals_bred", 200, "Breed 200 animals.", "Your island's a zoo! A wonderful one.", 12_000, 600, 2),
            new Step(Kind.STAT, "fish_caught", 1_000, "Catch 1,000 fish.", "I've never seen a rod worked so hard.", 12_000, 600, -1),
            new Step(Kind.COUNT, "island_place", 25_000, "Place 25,000 blocks on your island.", "You've built a city in the clouds.", 15_000, 700, -1),
            new Step(Kind.COUNT, "sw_win", 25, "Win 25 games of Skywars.", "The arenas fear your name.", 15_000, 700, 2),
            new Step(Kind.COUNT, "gw_win", 25, "Win 25 games of Gold Wars.", "A general as well as a captain!", 15_000, 700, -1),
            new Step(Kind.COUNT, "gw_gold", 250, "Break 250 enemy gold blocks in Gold Wars.", "That's a dragon's hoard of gold, smashed.", 15_000, 700, -1),
            new Step(Kind.COUNT, "bw_win", 25, "Win 25 games of Bedwars.", "Twenty-five! The sky's best bed-guard.", 15_000, 700, -1),
            new Step(Kind.COUNT, "bw_final", 100, "Get 100 final kills in Bedwars.", "A hundred crews sent home for good.", 15_000, 700, 2),
            new Step(Kind.COUNT, "game_kill", 300, "Get 300 kills in Skywars, Gold Wars and Bedwars.", "Remind me never to cross you.", 15_000, 700, 2),
            new Step(Kind.CHART, "nether_2,nether_3", 1, "Clear a Hard or Nightmare Nether Expedition.",
                    "A Hard Nether! The piglins tell stories about you now.", 15_000, 700, 2), // 5.27
            new Step(Kind.COUNT, "course_extreme", 10, "Finish the Void Gauntlet (extreme course) 10 times.", "Ten times! You must have wings.", 20_000, 800, -1),
            new Step(Kind.COUNT, "expedition_win+city_win+sunken_win", 10, "Clear 10 expeditions (Nether, Ancient City or Sunken Monument).",
                    "Ten voyages! My old charts are full of your name.", 15_000, 700, -1), // 5.27: was 50 Spleef wins
            new Step(Kind.COUNT, "duel_win", 50, "Win 50 duels.", "The Duel Arena should name a pit after you.", 15_000, 700, 2),
            new Step(Kind.COUNT, "daily_quest", 30, "Complete 30 daily quests.", "Steady work, every day. That's how legends are made.", 15_000, 700, -1),
            new Step(Kind.CHART, "*", 8, "Earn 8 stamps on Wren's Charts (/charts).",
                    "Half the chart filled in by your hand. A true explorer.", 20_000, 800, 2), // 5.27
            new Step(Kind.CRAFTED, "diamond_block", 32, "Craft 32 diamond blocks.", "That's... that's a lot of diamonds, captain.", 25_000, 900, 2),
            new Step(Kind.LEVEL, "", 60, "Reach server level 60.", "There's no captain like you anywhere in the skies.", 30_000, 1_000, -1),
            new Step(Kind.SKILL, "50", 4, "Max out 4 skills.", "Four masteries. Only three to go.", 25_000, 900, 2),
            new Step(Kind.SKILL, "50", 7, "Max out ALL 7 skills to become a Legend of the Skies.",
                    "You've done it. Every skill mastered, every trial passed. You're a Legend of the Skies - and this Legendary Key is yours.", 100_000, 2_000, 4));

    /** Where each chapter starts (step numbers) and its name. */
    static final int[] CHAPTER_START = {1, 20, 46}; // 5.27: was {1, 16, 36}

    /**
     * 5.27: the per-player quest line's version (saved in wren-quests.json as "line"). Line 3 added these steps (in
     * the new numbering, smallest first); players saved under line 2 move to the same quest in the new numbering.
     */
    static final int PLAYER_LINE = 3;
    private static final int[] ADDED_V3 = {9, 11, 12, 17, 23, 28, 36, 37, 41, 42, 47, 59, 64};
    /** Line 2's step 49 (50 Spleef wins) became "clear 10 expeditions" - anyone on it starts that count fresh. */
    private static final int REPLACED_V3_OLD = 49;

    /** A player's quest number saved under line 2, in line 3's numbering. */
    static int migrateV3(int quest) {
        if (quest <= 0) return quest;
        int q = quest;
        for (int added : ADDED_V3) if (q >= added) q++;
        return q;
    }

    /**
     * The quest line's version (saved with each island). Version 2 added Bedwars steps; islands saved
     * before that are moved to the same step under the new numbering, so nobody loses progress.
     * (5.7 changed the LEVEL steps to the server level but kept every step number, so no new version.)
     */
    public static final int LINE_VERSION = 2;
    /** Step numbers added in version 2 (in the new numbering, smallest first). */
    private static final int[] ADDED_V2 = {18, 31, 45, 46};

    /** An island's step number saved under an older quest line, in today's numbering. */
    public static int migrate(int quest, int fromVersion) {
        if (fromVersion >= 2 || quest <= 0) return quest;
        int q = quest;
        for (int added : ADDED_V2) if (q >= added) q++;
        return q;
    }
    static final String[] CHAPTER_NAMES = {"Chapter 1: Settling In", "Chapter 2: The Captain's Trials", "Chapter 3: The Legend of the Skies"};

    /** 5.27: the player's current quest task (Guide's "What next?" page), or null if not started / all done. */
    public static String taskOf(UUID player) {
        int quest = questOf(player);
        if (quest < 1 || quest > STEPS.size()) return null;
        return STEPS.get(quest - 1).task();
    }

    /** 5.27: how many quests there are. */
    public static int questCount() {
        return STEPS.size();
    }

    /** Short progress text for the sidebar and Wren's menu (5.25: the player's own quest). */
    public static String progressShort(UUID player) {
        int quest = questOf(player);
        if (quest <= 0) return "§7talk to Captain Wren";
        if (quest > STEPS.size()) return "§6✦ all done";
        return "§d" + quest + "/" + STEPS.size() + " §7ch." + (chapterOf(quest) + 1);
    }

    static int chapterOf(int step) {
        int c = 0;
        for (int i = 0; i < CHAPTER_START.length; i++) if (step >= CHAPTER_START[i]) c = i;
        return c;
    }

    /** Wren's speech when a new chapter begins. */
    private static void chapterIntro(ServerPlayer p, int step) {
        if (step == CHAPTER_START[1]) {
            say(p, "You've settled in nicely. But a real sky captain is tested beyond their own island...");
            say(p, "These are the Captain's Trials. Prove yourself in the arenas - Skywars, Gold Wars and Bedwars - master a trade, and work the land and the forge. Finish them and I'll give you my Epic Key.");
        } else if (step == CHAPTER_START[2]) {
            say(p, "You passed the Trials. There's one more voyage, and it's a long one - the Legend of the Skies.");
            say(p, "Only someone who has mastered every skill can finish it. When you're ready, come back with two skills at level 50.");
        }
    }

    private IslandQuests() {}

    private static void say(ServerPlayer p, String text) {
        Lobby.msg(p, "§6[Captain Wren] §f" + text);
    }

    // =====================================================================
    // Talking
    // =====================================================================

    /** The starter flock: 2 sheep, 2 cows, 2 chickens, right next to the player. */
    private static void giveHerd(ServerPlayer p) {
        net.minecraft.server.level.ServerLevel level = (net.minecraft.server.level.ServerLevel) p.level();
        net.minecraft.world.entity.EntityType<?>[] types = {net.minecraft.world.entity.EntityTypes.SHEEP, net.minecraft.world.entity.EntityTypes.SHEEP,
                net.minecraft.world.entity.EntityTypes.COW, net.minecraft.world.entity.EntityTypes.COW,
                net.minecraft.world.entity.EntityTypes.CHICKEN, net.minecraft.world.entity.EntityTypes.CHICKEN};
        for (int i = 0; i < types.length; i++) {
            net.minecraft.world.entity.Entity e = types[i].create(level, EntitySpawnReason.COMMAND);
            if (e == null) continue;
            double a = i * Math.PI / 3;
            e.snapTo(p.getX() + Math.cos(a) * 1.5, p.getY(), p.getZ() + Math.sin(a) * 1.5, (float) (a * 57.3), 0f);
            if (e instanceof net.minecraft.world.entity.Mob m) m.setPersistenceRequired();
            level.addFreshEntity(e);
        }
    }

    /**
     * Right-clicked Captain Wren. 5.20: members get Wren's menu (quests and every expedition), except the very
     * first talk (his introduction and the starter animals), which goes straight to the quests.
     */
    static void talk(ServerPlayer p, Islands.Island is) {
        if (is == null) return;
        if (!is.isMember(p.getUUID())) {
            say(p, "Ahoy, visitor! This isn't your island, but you're welcome to look around."
                    + (WrenRoom.hasRoom(is) ? " If you're sailing with this crew, the Tribute Chests are at the back." : ""));
            return;
        }
        com.aidan.skyserver.Safe.run("wren planter catch-up", () -> com.aidan.skyserver.pet.Planters.wrenCatchUp(p, is)); // 5.24b
        if (is.herd && pq(p.getUUID()).quest != 0) {
            WrenMenu.open(p);
            return;
        }
        questTalk(p, is);
    }

    /** Wren's quest talk (the old right-click). 5.25: every player has their own quest line. */
    public static void questTalk(ServerPlayer p, Islands.Island is) {
        if (is == null) return;
        PQ q = pq(p.getUUID());
        boolean gaveHerd = false;
        if (!is.herd) {
            gaveHerd = true;
            is.herd = true;
            giveHerd(p);
            if (q.quest != 0) {
                say(p, "Oh! Before I forget - I rescued these animals from the wreck. A Shepherd needs a flock!");
                Lobby.msg(p, "§a+2 sheep, 2 cows, 2 chickens §7(feed two of a kind to breed them; shears and buckets for wool and milk)");
            }
            Islands.save();
        }
        if (q.quest == 0) {
            say(p, "Ahoy there! I'm Captain Wren. My airship crashed on these islands long ago, and I've been waiting for someone like you.");
            say(p, "Help me out and I'll make it worth your while. Here's a little something to get started.");
            Economy.add(p.level().getServer(), p.getUUID(), p.getName().getString(), 100);
            Lobby.msg(p, "§a+$100 §7(Captain Wren)");
            if (gaveHerd) {
                say(p, "And these animals came down with my airship. Look after them - sheep, cows and chickens are the start of every good farm.");
                Lobby.msg(p, "§a+2 sheep, 2 cows, 2 chickens §7(more spawn eggs in §e/is menu§7)");
            }
            startStep(q, 1);
            showTask(p, is);
            return;
        }
        if (q.quest > STEPS.size()) {
            say(p, new String[]{"Fair winds, captain.", "Have you seen how big your island's grown?", "I'll never tire of this view."}[(int) (p.level().getGameTime() / 200 % 3)]);
            return;
        }
        if (check(is, p)) return;
        if (q.quest == CHAPTER_START[1] || q.quest == CHAPTER_START[2]) chapterIntro(p, q.quest);
        showTask(p, is);
    }

    static void showTask(ServerPlayer p, Islands.Island is) {
        PQ q = pq(p.getUUID());
        if (q.quest < 1 || q.quest > STEPS.size()) return;
        Step s = STEPS.get(q.quest - 1);
        long prog = progress(p, is, q, s);
        String count = s.amount > 1 ? " §7(" + String.format("%,d", Math.min(prog, s.amount)) + "/" + String.format("%,d", s.amount) + ")" : "";
        say(p, s.task + count);
        int c = chapterOf(q.quest);
        int inChapter = q.quest - CHAPTER_START[c] + 1;
        int chapterLen = (c + 1 < CHAPTER_START.length ? CHAPTER_START[c + 1] : STEPS.size() + 1) - CHAPTER_START[c];
        Lobby.msg(p, "§7" + CHAPTER_NAMES[c] + " - quest " + inChapter + "/" + chapterLen + " - reward §6" + Economy.format(s.money)
                + (s.keyTier >= 0 ? " §7+ " + Keys.COLORS[s.keyTier] + Keys.NAMES[s.keyTier] + " Key" : "") + "§7. Type §e/is quest §7any time.");
    }

    /** /is quest */
    static void status(ServerPlayer p) {
        Islands.Island is = Islands.islandOf(p.getUUID());
        PQ q = pq(p.getUUID());
        if (is == null) {
            Lobby.msg(p, "§7You don't have an island yet. Type §e/is§7.");
        } else if (q.quest == 0) {
            Lobby.msg(p, "§7Talk to §6Captain Wren §7(in his ship room, or by the path) to start his quests.");
        } else if (q.quest > STEPS.size()) {
            Lobby.msg(p, "§6You're a Legend of the Skies: §ayou've finished all of Captain Wren's quests!");
        } else {
            showTask(p, is);
        }
    }

    // =====================================================================
    // 5.25: every player's own quest line (wren-quests.json)
    // =====================================================================

    /** One player's place in the quest line. It stays with the player if they change islands. */
    public static final class PQ {
        /** 0 = hasn't met Wren yet, 1..STEPS.size() = on that quest, above = all done. */
        public int quest;
        /** PLACED / REACH_ISLET: how many so far on this quest. */
        int count;
        /** MINED / CRAFTED / STAT / COUNT: the player's counter when this quest started (-1 = not read yet). */
        long base = -1;
        /** Progress carried over from the old shared quest (what the rest of the island had done on this quest). */
        long carry;
    }

    private static final String FILE = "wren-quests.json";
    private static final Map<UUID, PQ> PLAYERS = new HashMap<>();
    private static boolean loaded;

    /** Loads the file once. The first time ever, every owner and member starts where their island's shared quest was. */
    private static void load() {
        if (loaded) return;
        loaded = true;
        com.google.gson.JsonObject root = com.aidan.skyserver.lobby.Stacks.readFile(FILE);
        if (root.has("players")) {
            int line = root.has("line") ? root.get("line").getAsInt() : 2;
            for (Map.Entry<String, com.google.gson.JsonElement> e : root.getAsJsonObject("players").entrySet()) {
                try {
                    com.google.gson.JsonObject o = e.getValue().getAsJsonObject();
                    PQ q = new PQ();
                    q.quest = o.get("quest").getAsInt();
                    if (o.has("count")) q.count = o.get("count").getAsInt();
                    if (o.has("base")) q.base = o.get("base").getAsLong();
                    if (o.has("carry")) q.carry = o.get("carry").getAsLong();
                    if (line < PLAYER_LINE) { // 5.27: same quest, new numbering
                        int old = q.quest;
                        q.quest = migrateV3(old);
                        if (old == REPLACED_V3_OLD) {
                            q.base = -1;
                            q.carry = 0;
                            q.count = 0;
                        }
                    }
                    PLAYERS.put(UUID.fromString(e.getKey()), q);
                } catch (Exception ignored) {
                }
            }
            if (line < PLAYER_LINE) {
                com.aidan.skyserver.Skyserver.LOGGER.info("[SkyServer] Wren's quests: {} player(s) moved to the 5.27 quest line (same quest, new numbers).", PLAYERS.size());
                save();
            }
            return;
        }
        int moved = 0;
        for (Islands.Island is : Islands.all()) {
            if (is.quest <= 0) continue;
            long total = 0;
            for (long v : is.questBanked.values()) total += v;
            List<UUID> people = new ArrayList<>(is.members);
            people.add(is.owner);
            for (UUID u : people) {
                PQ q = new PQ();
                q.quest = migrateV3(is.quest); // islands still use the line-2 numbering
                q.count = is.questCount;
                Long base = is.questBase.get(u);
                q.base = base == null ? -1 : base;
                q.carry = Math.max(0, total - is.questBanked.getOrDefault(u, 0L));
                PQ old = PLAYERS.get(u);
                if (old == null || old.quest < q.quest) PLAYERS.put(u, q);
                moved++;
            }
        }
        com.aidan.skyserver.Skyserver.LOGGER.info("[SkyServer] Wren's quests are per player now: {} player(s) start at their island's quest.", moved);
        save();
    }

    private static void save() {
        com.google.gson.JsonObject root = new com.google.gson.JsonObject(), players = new com.google.gson.JsonObject();
        PLAYERS.forEach((u, q) -> {
            com.google.gson.JsonObject o = new com.google.gson.JsonObject();
            o.addProperty("quest", q.quest);
            o.addProperty("count", q.count);
            o.addProperty("base", q.base);
            o.addProperty("carry", q.carry);
            players.add(u.toString(), o);
        });
        root.add("players", players);
        root.addProperty("line", PLAYER_LINE);
        com.aidan.skyserver.lobby.Stacks.writeFile(FILE, root);
    }

    private static PQ pq(UUID u) {
        load();
        return PLAYERS.computeIfAbsent(u, k -> new PQ());
    }

    /** A player's quest number (0 = not started, above STEPS.size() = finished). */
    public static int questOf(UUID u) {
        return pq(u).quest;
    }

    /** Is anyone else on this island already past this quest? (Island-wide things count for everyone.) */
    private static boolean crewPast(Islands.Island is, UUID me, int step) {
        List<UUID> people = new ArrayList<>(is.members);
        people.add(is.owner);
        for (UUID u : people) if (!u.equals(me) && pq(u).quest > step) return true;
        return false;
    }

    // =====================================================================
    // Progress
    // =====================================================================

    private static void startStep(PQ q, int step) {
        q.quest = step;
        q.count = 0;
        q.base = -1;
        q.carry = 0;
        save();
    }

    private static long stat(ServerPlayer p, Step s) {
        if (s.target.startsWith("#")) return 0;
        if (s.kind == Kind.COUNT || s.kind == Kind.TOTAL) {
            long sum = 0; // 5.27: "a+b" adds counters
            for (String k : s.target.split("\\+")) sum += com.aidan.skyserver.lobby.PlayerStats.get(p.getUUID(), k.trim());
            return sum;
        }
        if (s.kind == Kind.CHART) {
            if (s.target.equals("*")) return com.aidan.skyserver.lobby.Charts.count(p.getUUID());
            for (String k : s.target.split(",")) if (com.aidan.skyserver.lobby.Charts.has(p.getUUID(), k.trim())) return 1;
            return 0;
        }
        if (s.kind == Kind.STAT) {
            var stat = switch (s.target) {
                case "animals_bred" -> Stats.ANIMALS_BRED;
                case "fish_caught" -> Stats.FISH_CAUGHT;
                default -> Stats.ENCHANT_ITEM;
            };
            return p.getStats().getValue(Stats.CUSTOM.get(stat));
        }
        if (s.kind == Kind.SKILL) {
            int need = Integer.parseInt(s.target), n = 0;
            for (var sk : com.aidan.skyserver.lobby.Skills.Skill.values())
                if (com.aidan.skyserver.lobby.Skills.level(p.getUUID(), sk) >= need) n++;
            return n;
        }
        Identifier id = Identifier.parse(s.target);
        if (s.kind == Kind.MINED) {
            Block b = BuiltInRegistries.BLOCK.getValue(id);
            return p.getStats().getValue(Stats.BLOCK_MINED.get(b));
        }
        return p.getStats().getValue(Stats.ITEM_CRAFTED.get(BuiltInRegistries.ITEM.getValue(id)));
    }

    /**
     * 5.25: this player's progress on their quest. Counting quests only count what the player does themselves
     * (from when the quest started); island things (the islet chest, generator, size, challenges) count for
     * everyone on the island.
     */
    private static long progress(ServerPlayer p, Islands.Island is, PQ q, Step s) {
        switch (s.kind) {
            case TOTAL, SKILL, CHART -> {
                return stat(p, s);
            }
            case GOLEM -> { // 5.27: island thing
                return Golems.data(is.id).golems.size();
            }
            case MINED, CRAFTED, STAT, COUNT -> {
                long now = stat(p, s);
                if (q.base < 0) {
                    q.base = now;
                    save();
                }
                return Math.max(0, now - q.base) + q.carry;
            }
            case PLACED -> {
                return q.count;
            }
            case REACH_ISLET -> {
                return q.count > 0 || crewPast(is, p.getUUID(), q.quest) ? 1 : 0;
            }
            case LEVEL -> {
                return Levels.level(p.getUUID());
            }
            case GEN -> {
                return is.genLevel;
            }
            case SIZE -> {
                return is.size > Islands.START_SIZE ? 1 : 0;
            }
            case CHALLENGE -> {
                return is.completed.isEmpty() ? 0 : 1;
            }
        }
        return 0;
    }

    /** Finishes the player's current quest if it's done (rewarding them). Returns true if a quest was finished. */
    static boolean check(Islands.Island is, ServerPlayer p) {
        PQ q = pq(p.getUUID());
        if (q.quest < 1 || q.quest > STEPS.size()) return false;
        Step s = STEPS.get(q.quest - 1);
        if (progress(p, is, q, s) < s.amount) return false;
        var server = p.level().getServer();
        say(p, s.done);
        Economy.add(server, p.getUUID(), p.getName().getString(), s.money);
        Lobby.msg(p, "§a+" + Economy.format(s.money) + " §7(quest " + q.quest + " done)");
        Levels.addXp(server, p, s.xp);
        if (s.keyTier >= 0) Keys.reward(server, p.getUUID(), s.keyTier, 1, "(Captain Wren's quest)");
        // 5.25: Wren's free Little Snack Planter only goes to the island's owner.
        if (q.quest == com.aidan.skyserver.pet.PetItems.SNACK_PLANT_STEP && is.owner.equals(p.getUUID()))
            com.aidan.skyserver.Safe.run("wren snack plant", () -> com.aidan.skyserver.pet.PetItems.wrenPlant(p)); // 5.24
        com.aidan.skyserver.lobby.Skills.add(p, com.aidan.skyserver.lobby.Skills.Skill.BUILDING, 40);
        com.aidan.skyserver.lobby.BattlePass.event(p, "wren_quest", 1);
        p.level().playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 1f, 1.2f);
        List<UUID> crew = new ArrayList<>(is.members);
        crew.add(is.owner);
        for (UUID u : crew) {
            ServerPlayer m = server.getPlayerList().getPlayer(u);
            if (m != null && m != p) Lobby.msg(m, "§6[Captain Wren] §f" + p.getName().getString() + " finished quest " + q.quest + ": " + s.task);
        }
        startStep(q, q.quest + 1);
        if (q.quest > STEPS.size()) {
            say(p, "There's nothing left I can teach you. Fair winds, always.");
            // 5.25: Legend status (skills past level 50) goes to the player who finished their own quest line.
            if (!com.aidan.skyserver.lobby.Skills.isLegend(p.getUUID())) {
                com.aidan.skyserver.lobby.PlayerStats.add(p.getUUID(), "legend", 1);
                Lobby.msg(p, "§6§l✦ LEGEND OF THE SKIES! §eYour skills can now go past level 50, all the way to "
                        + com.aidan.skyserver.lobby.Skills.LEGEND_MAX_LEVEL + ".");
                server.getPlayerList().broadcastSystemMessage(Component.literal("§6§l✦ §e" + p.getName().getString()
                        + " §6has become a Legend of the Skies! §7(finished all of Captain Wren's quests)"), false);
            }
        } else {
            for (int start : CHAPTER_START) if (q.quest == start) {
                chapterIntro(p, start);
                server.getPlayerList().broadcastSystemMessage(Component.literal("§6✦ §e" + p.getName().getString()
                        + " §7finished " + CHAPTER_NAMES[chapterOf(q.quest) - 1] + "!"), false);
            }
            showTask(p, is);
        }
        return true;
    }

    /** A block was placed on an island by a member. */
    static void onPlace(ServerPlayer p, Islands.Island is, BlockState placed) {
        PQ q = pq(p.getUUID());
        if (q.quest < 1 || q.quest > STEPS.size()) return;
        Step s = STEPS.get(q.quest - 1);
        if (s.kind != Kind.PLACED) return;
        String id = BuiltInRegistries.BLOCK.getKey(placed.getBlock()).getPath();
        boolean match = s.target.equals("#sapling") ? id.endsWith("_sapling") || id.equals("mangrove_propagule") : id.equals(s.target);
        if (!match) return;
        q.count++;
        if (q.count >= s.amount) check(is, p);
        else if (q.count % 4 == 0) {
            save();
            com.aidan.skyserver.lobby.Announce.actionBar(p, "§6Quest: §f" + q.count + "/" + s.amount);
        }
    }

    /** Every couple of seconds for each member on their island. */
    static void tick(ServerPlayer p, Islands.Island is) {
        PQ q = pq(p.getUUID());
        if (q.quest < 1 || q.quest > STEPS.size()) return;
        Step s = STEPS.get(q.quest - 1);
        if (s.kind == Kind.REACH_ISLET && q.count == 0) {
            double ix = is.cx() + IslandDesign.ISLET[0] + 0.5, iz = is.cz() + IslandDesign.ISLET[2] + 0.5;
            if (Math.hypot(p.getX() - ix, p.getZ() - iz) < IslandDesign.ISLET_RADIUS + 1 && p.getY() > Islands.TOP_Y - 1 && p.onGround()) {
                q.count = 1;
            }
        }
        check(is, p);
    }

    // =====================================================================
    // Captain Wren himself
    // =====================================================================

    public static boolean isWren(Entity e) {
        return e != null && e.entityTags().contains(WREN_TAG);
    }

    /** Where Wren stands on this island: {x, y, z, yaw} (moved with /is movewren, or the default spot). */
    static double[] wrenSpot(Islands.Island is) {
        if (is.wren != null) return is.wren;
        return new double[]{is.cx() + IslandDesign.WREN[0], Islands.TOP_Y + IslandDesign.WREN[1], is.cz() + IslandDesign.WREN[2], IslandDesign.WREN[3]};
    }

    /** Puts Wren on the island if he isn't there (new islands, or islands made before he existed). */
    static void ensureWren(ServerLevel level, Islands.Island is) {
        com.aidan.skyserver.Safe.run("ves check", () -> WrenRoom.checkVes(level, is)); // 5.21: Old Ves lives in the ship room
        double[] w = wrenSpot(is);
        double x = w[0], y = w[1], z = w[2];
        if (!level.isLoaded(BlockPos.containing(x, y, z))) return;
        AABB box = new AABB(x - 60, y - 40, z - 60, x + 60, y + 40, z + 60);
        if (!level.getEntitiesOfClass(Villager.class, box, IslandQuests::isWren).isEmpty()) return;
        // Not near his spot: he may still be standing at his old spot (moved before 5.7 saved it) - check the whole island.
        double half = is.size / 2.0 + 10;
        AABB island = new AABB(is.cx() - half, Islands.VOID_Y, is.cz() - half, is.cx() + half, Islands.TOP_Y + 100, is.cz() + half);
        List<Villager> elsewhere = level.getEntitiesOfClass(Villager.class, island, IslandQuests::isWren);
        if (!elsewhere.isEmpty()) {
            Villager v = elsewhere.get(0);
            v.teleportTo(x, y, z);
            v.setYRot((float) w[3]);
            v.setYHeadRot((float) w[3]);
            return;
        }
        Villager v = net.minecraft.world.entity.EntityTypes.VILLAGER.create(level, EntitySpawnReason.COMMAND);
        if (v == null) return;
        v.snapTo(x, y, z, (float) w[3], 0f);
        v.setNoAi(true);
        v.setPermanentlyInvulnerable(true);
        v.setSilent(true);
        v.setPersistenceRequired();
        v.setCustomName(Component.literal("§6Captain Wren"));
        v.setCustomNameVisible(true);
        v.addTag(WREN_TAG);
        level.addFreshEntity(v);
    }

    /** /is movewren: Wren moves to where the player is standing (owner or co-op member, on their own island). */
    static void moveWren(ServerPlayer p) {
        Islands.Island is = Islands.islandOf(p.getUUID());
        if (WrenRoom.hasRoom(is)) { // 5.20: he lives in his ship room now
            Lobby.msg(p, "§6[Captain Wren] §fI'm not leaving my cabin, captain! §7Move the whole ship with §e/is shiproom place§7.");
            return;
        }
        if (is == null || !Islands.isIsland(p.level()) || Islands.islandAt(p.getX(), p.getZ()) != is || !is.inBorder(p.getX(), p.getZ())) {
            Lobby.msg(p, "§cStand on your own island (inside the border) where you want Captain Wren to stand.");
            return;
        }
        if (!p.onGround()) {
            Lobby.msg(p, "§cStand on solid ground first - Wren's legs aren't what they used to be.");
            return;
        }
        float yaw = (p.getYRot() + 180f) % 360f; // he faces the player who called him
        is.wren = new double[]{p.getX(), p.getY(), p.getZ(), yaw};
        Islands.save();
        ServerLevel level = (ServerLevel) p.level();
        double half = is.size / 2.0 + 10;
        AABB island = new AABB(is.cx() - half, Islands.VOID_Y, is.cz() - half, is.cx() + half, Islands.TOP_Y + 100, is.cz() + half);
        List<Villager> found = level.getEntitiesOfClass(Villager.class, island, IslandQuests::isWren);
        if (found.isEmpty()) {
            ensureWren(level, is);
        } else {
            for (int i = 1; i < found.size(); i++) found.get(i).discard(); // only one Wren per island
            Villager v = found.get(0);
            v.teleportTo(p.getX(), p.getY(), p.getZ());
            v.setYRot(yaw);
            v.setYHeadRot(yaw);
        }
        say(p, new String[]{"Right here? Aye, the view's better. I'll set up my things.",
                "A new post! Every good captain moves the wheel now and then.",
                "Ahh, fresh air on this side. Good choice, captain."}[(int) (p.level().getGameTime() / 20 % 3)]);
        Islands.islandChat(is, "§7" + p.getName().getString() + " moved Captain Wren to a new spot.");
    }
}
