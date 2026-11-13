package com.aidan.skyserver.lobby;

import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.Filterable;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.WrittenBookContent;

import java.util.ArrayList;
import java.util.List;

/**
 * The Server Guide: a book in every player's lobby hotbar listing all the commands they can use.
 * Right-click it (or type /guide) to read it. Edit the pages here.
 * 5.13: Spleef / Archery lines gone from Lobby fun, left-click chest odds on the Keys page,
 * a Wren's Voyage page (21), /ready on the Games page.
 * 5.16: /spleef back on Lobby fun; the bank adds interest daily; Nether trips page rewritten (right
 * prices, 30-60 minute limits, the one-time revive, Wren takes you home); the new chest prizes
 * (books above the normal max, Longbow, Fire Wand) on Shards & more.
 * 5.19: the short commands (/gw, /bw, /sw, /leave, /class, /builder..., /l, /stats, /challenges, /exp),
 * kill streak bounties, Bedwars armor dropping a tier, the Nether's trapped gold, and new pages for
 * boosters & /tip, the End, the Ancient City and Wren's Charts.
 * 5.20: Captain Wren's ship room (/is shiproom) and the Sunken Monument pages; expeditions start with Wren.
 * 5.21: the Ancient City page rewritten (noise meter, rope, compass); new City Cash page (28).
 */
public final class Guide {
    private Guide() {}

    /** Each page: up to about 14 short lines. §l = bold, §n = underline, colours as in chat. */
    private static final String[][] PAGES = {
            {"§l§nServer Guide§r", "", "Every command you", "can use, by topic.",
                    "§2Sky Island§0 ....... 2", "§4Games§0 ............ 5", "§cBedwars§0 ......... 6", "§6Lobby fun§0 ........ 7",
                    "§9Keys & money§0 ... 8", "§5Levels§0 ............ 9", "§6Battle pass§0 .... 10", "§6Town & bank§0 .... 11",
                    "§7The Mine§0 ........ 12", "§9Factions§0 ........ 13", "§2Captain Wren§0 ... 14", "§dParties§0 ......... 15",
                    "§2Island extras§0 .. 16", "§6Events§0 .......... 17", "§aTown people§0 ... 18",
                    "§cNether trips§0 ... 19", "§dShards & more§0 . 20", "§6Wren's Voyage§0 . 21",
                    "§dBoosters & tips§0 22", "§5The End§0 .......... 23", "§3Ancient City§0 ... 24", "§6Wren's Charts§0 . 25",
                    "§6Ship room§0 ...... 26", "§bSunken Monument 27", "§3City Cash§0 ...... 28"},
            {"§2§lSky Island§r", "", "§l/is§r", "go to your island", "(made the first time)", "",
                    "§l/is menu§r", "upgrades, size, info", "", "§l/is challenges§r", "goals with rewards", "",
                    "§l/is sethome§r", "set where you appear"},
            {"§2§lIsland friends§r", "", "§l/is invite <name>§r", "add a co-op member", "(up to 4)",
                    "§l/is join <name>§r", "§l/is leave§r  §l/is kick§r", "§l/is members§r", "",
                    "§l/is trust <name> [min]§r", "let them build for", "a while (no chests)", "§l/is untrust <name>§r",
                    "§l/is visit <name>§r  §l/is open§r"},
            {"§2§lMore island§r", "", "§l/is level§r", "level + worth", "§l/is top§r", "richest islands", "",
                    "§l/is shiproom§r", "Captain Wren's ship", "", "You keep your items", "if you fall or die", "on your island.",
                    "Sleep = morning on", "your island only."},
            {"§4§lGames§r", "", "§lGold Wars§r: §l/gw§r", "§l/class§r or §l/builder§r", "§l/knight /archer /mage§r", "§l/scout /lb§r (Longbow)",
                    "§lSkywars§r: §l/sw§r", "§l/leave§r leaves any game", "§l/tc§r team  §l/mc§r match",
                    "§l/ready§r start now,", "bots fill the rest", "Click a map name to vote.",
                    "§l/l§r or §l/lobby§r back", "Kill streak of 4: you", "glow, $150+ bounty!"},
            {"§c§lBedwars§r", "", "§l/bw§r join  §l/leave§r", "§l/stats§r your record", "4 teams of 3 or 2. While", "your bed stands you",
                    "respawn. Break theirs!", "Armor drops one tier", "when you die.", "Iron & gold: home", "Diamonds: side islands",
                    "Emeralds: the middle", "Right-click the", "shopkeepers to buy."},
            {"§6§lLobby fun§r", "", "§l/duel <name> [bet]§r", "1v1, winner takes", "the whole bet", "",
                    "§l/spleef§r Spleef and", "TNT Run pit: last", "one standing wins", "",
                    "The Maze, Elytra", "Rings, the Fishing", "Pond and 4 obstacle", "courses: the compass!"},
            {"§9§lKeys & money§r", "", "§l/keys§r your keys", "Open them on the", "chests in the Great", "Hall. §lLeft-click§r a",
                    "chest to see every", "prize and its chance.", "",
                    "Log in daily: a 7-day", "streak, Rare Key on", "day 7!", "§l/trade <name>§r", "keys, items, money", "§l/claim§r get prizes", "(on your island)"},
            {"§5§lLevels§r", "", "§l/level§r  §l/rewards§r", "One level for the", "whole server, 1-99.", "Never resets!", "",
                    "After 99: Prestige.", "Every 10 prestige", "levels = a new label", "by your name.", "",
                    "Most XP: Skywars, Gold", "Wars, Bedwars, Island."},
            {"§6§lBattle pass§r", "", "§l/pass§r 50 tiers of", "rewards, 6-week", "seasons.", "§l/challenges§r", "7 new every Monday",
                    "§l/quests§r 3 daily", "(they give pass XP)", "§l/cosmetics§r titles", "and trails", "",
                    "§l/pass top§r: top 3", "win big keys!"},
            {"§6§lThe Town§r", "", "South-east of the", "castle (compass).", "", "§l/bank§r 1% a week,", "added every day", "§l/skins§r skin shop", "",
                    "Skill buildings give", "a daily crate once", "your skill is level 5", "", "§l/skills§r your levels"},
            {"§7§lThe Mine§r", "", "Under the Town.", "Mine ores, they grow", "back. More ores open", "as Mining levels up.", "",
                    "What you mine goes", "to your island:", "§l/claim§r there."},
            {"§9§lFactions§r", "", "§l/f create <name>§r", "§l/f invite <name>§r", "§l/f join <name>§r", "§l/f leave§r  §l/f kick§r",
                    "§l/f info§r  §l/f top§r", "§l/fc <msg>§r chat", "§l/f deposit <amt>§r", "", "Up to 8 members."},
            {"§2§lCaptain Wren§r", "", "He's by the path on", "your island. Talk to", "him for a quest line", "with money, XP and", "keys.", "",
                    "§l/is quest§r shows", "your current quest.", "", "§l/duel bot§r practice", "against a bot."},
            {"§d§lParties§r", "", "§l/party invite <name>§r", "§l/party accept§r", "§l/party leave§r  §l/party kick§r", "§l/party warp§r", "§l/pc <msg>§r chat", "",
                    "When the leader joins", "a game, the party", "comes too (same team", "in Gold Wars). Visit", "each other's islands", "without asking."},
            {"§2§lIsland extras§r", "", "§l/is setwarp <name>§r", "§l/is warp <name>§r", "§l/is warps§r (up to 5)", "",
                    "§l/is contribute <amt>§r", "shared upgrade fund", "§l/is fund§r", "", "§l/ic <msg>§r island chat"},
            {"§6§lEvents§r", "", "§lDouble XP§r every", "weekend!", "", "§lSpleef Tournament§r", "Saturdays 7pm: most", "wins gets a Rare Key.", "",
                    "§l/events§r details", "§l/sidebar§r hide/show", "the side panel"},
            {"§a§lTown people§r", "", "Right-click them:", "§lFarmer Hal§r orders", "§lShepherd Mae§r eggs", "§lSgt. Vex§r Gauntlet", "§lCurator Ivy§r contest",
                    "§lSmith Brann§r pickaxe", "§lOld Gil§r fish contest", "§lCoach Pip§r time trial", "", "§l/build§r contest", "Crates: right-click", "the barrel daily."},
            {"§c§lNether trips§r", "", "Talk to §lWren§r ->", "Nether (§l/exp§r).", "§a$5k §e$10k §c$20k §4$40k", "30 to 60 minutes.",
                    "Beat the arenas,", "then the boss.", "5 gold blocks on the", "bridges - most are", "traps! Look for tells.",
                    "§cDie = lose what you", "§ccarry!§0 Revive: 20%.", "Talk to Wren to leave."},
            {"§d§lShards & more§r", "Every chest gives", "Key Shards. §l/shards§r", "buys keys, cosmetics,", "boosters.",
                    "§l/boosters§r use them", "§l/cshop§r cosmetics", "§l/cosmetics§r equip", "§l/is theme§r island look", "",
                    "Epic & Legendary", "chests: books above", "the normal max,", "Longbow, Fire Wand."},
            {"§6§lWren's Voyage§r", "", "Every Thursday to", "Sunday: one game gets", "a twist (§l/voyage§r).", "",
                    "Do the goal for a", "§lstamp§r: pass XP and", "rewards in your", "stamp book (cages,", "bed effects, titles).", "",
                    "Top 3 on the board", "win keys on Sunday!"},
            {"§d§lBoosters & tips§r", "", "Every booster boosts", "the WHOLE server.", "§l/boosters§r use one.", "Two at once? They", "queue: it lasts longer!", "",
                    "§l/tip§r thanks whoever", "used it: you pay $200,", "the server adds $50.", "§l/boosters gift <name>§r", "give one to a friend."},
            {"§5§lThe End§r", "", "Talk to §lWren§r -> End", "Expedition: $15,000", "or an End ticket.", "§5Full netherite only!§0", "Press the button to",
                    "start each wave.", "Mini boss every 5,", "boss every 10.", "§l/voidshop§r spend", "Void Fragments", "(on your island)."},
            {"§3§lAncient City§r", "", "§lWren§r -> Ancient City", "Bring a recovery", "compass! Crouch on", "carpet = silent.",
                    "Noise 100% = lose", "EVERYTHING. Lit", "candles = real house.", "Take the relic, then", "follow the compass to", "Wren's rope.",
                    "§l/city top§r leaderboard", "New map each Thursday"},
            {"§6§lWren's Charts§r", "", "§l/charts§r", "A stamp for every", "expedition at every", "difficulty (the End:", "waves 10, 20, 30).", "",
                    "Fill the whole chart", "for the §6Cartographer§0", "title and a", "Legendary key!"},
            {"§6§lShip room§r", "", "Captain Wren lives in", "his pirate ship room.", "Talk to him for quests", "and EVERY expedition.", "",
                    "§l/is shiproom§r go there", "§l/is shiproom place§r", "put / move it (stand", "at the door, face", "the ship). Tribute", "Chests + the desk."},
            {"§b§lSunken Monument§r", "", "Fare = farm items in", "the Tribute Chests.", "Drain rooms: right-", "click the §lpump§r.", "Crouch to plug leaks.",
                    "Push Wren's diving", "bell to the vault and", "beat the Tidemother!", "§a$5k §e$11.5k §c$19k §4$35k", "(half after 2 a day)"},
            {"§3§lCity Cash§r", "", "Earned escaping the", "Ancient City. Spend it", "at §lOld Ves§r in Wren's", "ship room: gear,",
                    "books, discs, trims.", "§l/cc§r your balance", "Weekly requests and", "a 25%-off find.", "Pays for City trips:", "§b1k 2k 4k 8k CC", ""},
            {"§l§nRules§r", "", "Be kind in chat.", "No cheats or hacks.", "No scamming trades.", "", "Have fun!", "",
                    "§l/guide§r opens this", "book again."}
    };

    public static ItemStack book() {
        List<Filterable<Component>> pages = new ArrayList<>();
        for (String[] page : PAGES) pages.add(Filterable.passThrough(Component.literal(String.join("\n", page))));
        ItemStack s = new ItemStack(Items.WRITTEN_BOOK);
        s.set(DataComponents.WRITTEN_BOOK_CONTENT, new WrittenBookContent(Filterable.passThrough("Server Guide"), "SkyServer", 0, pages, true));
        Stacks.named(s, "§e§lServer Guide §7(right-click)", "§7Every command, in one book");
        return Stacks.tag(s, "guide");
    }

    /** Opens the book (when it's the item in hand), otherwise prints the guide in chat. */
    public static void open(ServerPlayer p) {
        ItemStack held = p.getMainHandItem();
        if (Stacks.is(held, "guide")) {
            p.openItemGui(held, InteractionHand.MAIN_HAND);
            return;
        }
        Lobby.msg(p, "§e§l--- Server Guide ---");
        for (int i = 1; i < PAGES.length; i++) {
            StringBuilder sb = new StringBuilder();
            for (String line : PAGES[i]) {
                if (line.isEmpty()) continue;
                if (sb.length() > 0) sb.append(" §8| ");
                sb.append(line.replace("§0", "§f").replace("§r", "§7"));
            }
            Lobby.msg(p, "§7" + sb);
        }
    }
}
