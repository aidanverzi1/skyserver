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
 * 5.24: chicken pages (29, 30) and /oldlook. 5.24b: Snack Planters.
 * 5.27: the index builds itself (it used to run past the bottom of page 1); a personal "What next?" page; Game pay
 * ($50 a game, first 3 wins +$300); Nether pages rewritten (45-80 min, waves 40-60 s apart, twists, co-op,
 * archers breaking blocks, revive from the bank) plus a tips page; Captain Wren's ship room + quest line; copper golem pages.
 */
public final class Guide {
    private Guide() {}

    /**
     * The topic pages: up to about 14 short lines each (a book page shows ~14). §l = bold, §n = underline, colours as in chat.
     * 5.27: the index is built from each page's first line (3 index pages, 12 entries each), so adding a page here
     * never breaks the page numbers. Page 4 is the player's own "What next?" page.
     */
    private static final String[][] PAGES = {
{"§2§lSky Island§r", "", "§l/is§r", "go to your island", "(made the first time)", "",
                    "§l/is menu§r", "upgrades, size, info", "", "§l/is challenges§r", "goals with rewards", "",
                    "§l/is sethome§r", "set where you appear"},
            {"§2§lIsland friends§r", "", "§l/is invite <name>§r", "add a co-op member", "(up to 4)",
                    "§l/is join <name>§r", "§l/is leave§r  §l/is kick§r", "§l/is members§r", "",
                    "§l/is trust <name> [min]§r", "let them build for", "a while (no chests)", "§l/is untrust <name>§r",
                    "§l/is visit <name>§r  §l/is open§r"},
            {"§2§lMore island§r", "", "§l/is level§r", "level + worth", "§l/is top§r", "richest islands",
                    "§l/is shiproom§r", "Captain Wren's ship", "", "You keep your items", "if you fall or die", "on your island.",
                    "Sleep = morning on", "your island only."},
            {"§4§lGames§r", "", "§lGold Wars§r: §l/gw§r", "§l/class§r or §l/builder§r", "§l/knight /archer /mage§r", "§l/scout /lb§r (Longbow)",
                    "§lSkywars§r: §l/sw§r", "§lBedwars§r: §l/bw§r", "§l/leave§r leaves any game", "§l/tc§r team  §l/mc§r match",
                    "§l/ready§r start now,", "bots fill the rest", "Click a map name to vote.", "§l/l§r or §l/lobby§r back"},
            {"§4§lGame pay§r", "", "Every Bedwars, Skywars", "and Gold Wars game:", "§2$50§0 for playing.", "Your first §l3 wins§r",
                    "of the day in EACH", "game: §2+$300§0 more.", "First win of the day:", "a big bonus + a key.",
                    "Bots in the match", "= less win pay.", "Kill streak of 4: you", "glow, $150+ bounty!"},
            {"§c§lBedwars§r", "", "§l/bw§r join  §l/leave§r", "§l/stats§r your record", "4 teams of 3 or 2. While", "your bed stands you",
                    "respawn. Break theirs!", "Armor drops one tier", "when you die.", "Iron & gold: home", "Diamonds: side islands",
                    "Emeralds: the middle", "Right-click the", "shopkeepers to buy."},
            {"§6§lLobby fun§r", "", "§l/duel <name> [bet]§r", "1v1, winner takes", "the whole bet", "",
                    "§l/spleef§r Spleef and", "TNT Run pit: last", "one standing wins", "",
                    "The Maze, Elytra", "Rings, the Fishing", "Pond and 4 obstacle", "courses: the compass!"},
            {"§9§lKeys & money§r", "", "§l/keys§r your keys", "Open them on the", "chests in the Great", "Hall. §lLeft-click§r a",
                    "chest to see every", "prize and its chance.", "Log in daily: a 7-day", "streak, Rare Key on",
                    "day 7! §l/trade <name>§r", "keys, items, money", "§l/claim§r get prizes", "(on your island)"},
            {"§5§lLevels§r", "", "§l/level§r  §l/rewards§r", "One level for the", "whole server, 1-99.", "Never resets!", "",
                    "After 99: Prestige.", "Every 10 prestige", "levels = a new label", "by your name.", "",
                    "Most XP: Skywars, Gold", "Wars, Bedwars, Island."},
            {"§6§lBattle pass§r", "", "§l/pass§r 50 tiers of", "rewards, 6-week", "seasons.", "§l/challenges§r", "7 new every Monday",
                    "§l/quests§r 3 daily", "(they give pass XP)", "§l/cosmetics§r titles", "and trails", "",
                    "§l/pass top§r: top 3", "win big keys!"},
            {"§6§lThe Town§r", "", "South-east of the", "castle (compass).", "", "§l/bank§r 1% a week,", "added every day.",
                    "Expedition revives", "can use bank money.", "§l/skins§r skin shop",
                    "Skill buildings give", "a daily crate once", "your skill is level 5", "§l/skills§r your levels"},
            {"§7§lThe Mine§r", "", "Under the Town.", "Mine ores, they grow", "back. More ores open", "as Mining levels up.", "",
                    "What you mine goes", "to your island:", "§l/claim§r there."},
            {"§9§lFactions§r", "", "§l/f create <name>§r", "§l/f invite <name>§r", "§l/f join <name>§r", "§l/f leave§r  §l/f kick§r",
                    "§l/f info§r  §l/f top§r", "§l/fc <msg>§r chat", "§l/f deposit <amt>§r", "", "Up to 8 members."},
            {"§2§lCaptain Wren§r", "", "He lives in his ship", "room on your island.", "Talk to him for his", "quest line: it visits",
                    "every part of the", "server, with money,", "XP and keys.", "", "§l/is quest§r shows", "your current quest.",
                    "§l/duel bot§r practice", "against a bot."},
            {"§d§lParties§r", "", "§l/party invite <name>§r", "§l/party accept§r", "§l/party leave§r  §l/party kick§r", "§l/party warp§r", "§l/pc <msg>§r chat", "",
                    "When the leader joins", "a game, the party", "comes too (same team", "in Gold Wars). Visit", "each other's islands", "without asking."},
            {"§2§lIsland extras§r", "", "§l/is setwarp <name>§r", "§l/is warp <name>§r", "§l/is warps§r (up to 5)", "",
                    "§l/is contribute <amt>§r", "shared upgrade fund", "§l/is fund§r", "", "§l/ic <msg>§r island chat"},
            {"§6§lEvents§r", "", "§l+25% XP§r every", "weekend!", "", "§lSpleef Tournament§r", "Saturdays 7pm: most", "wins gets a Rare Key.", "",
                    "§l/events§r details", "§l/sidebar§r hide/show", "the side panel"},
            {"§a§lTown people§r", "", "Right-click them:", "§lFarmer Hal§r orders", "§lShepherd Mae§r eggs", "§lSgt. Vex§r Gauntlet", "§lCurator Ivy§r contest",
                    "§lSmith Brann§r pickaxe", "§lOld Gil§r fish contest", "§lCoach Pip§r time trial", "", "§l/build§r contest", "Crates: right-click", "the barrel daily."},
            {"§c§lNether trips§r", "", "Talk to §lWren§r ->", "Nether (§l/exp§r).", "§a$5k §e$10k §c$20k §4$40k", "45 to 80 minutes.",
                    "Beat each arena's", "waves (40-60 s", "apart), then the boss.", "§cDie = lose what you", "§ccarry!§0 Revive: 20%",
                    "(wallet, then bank).", "Talk to Wren to leave.", "Tips: next page."},
            {"§c§lNether tips§r", "", "Every arena has a", "twist - read the", "title when it starts.", "Archers break blocks",
                    "you place to hide", "or tower up.", "5 gold blocks on the", "bridges - most are", "traps! Look for tells.",
                    "Crew of 2-3: +1 guard", "a wave each, tougher", "guards, more time."},
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
            {"§b§lSunken Monument§r", "", "Fare = farm items in", "the Tribute Chests.", "All underwater! Air:", "§dvents§r (few puffs).", "Touch the §lTide Seals",
                    "in order. Then push", "Wren's bell (only air", "down deep) and beat", "the Tidemother!", "§a$7.5k §e$15k §c$25k §4$45k"},
            {"§3§lCity Cash§r", "", "Earned escaping the", "Ancient City. Spend it", "at §lOld Ves§r in Wren's", "ship room: gear,",
                    "books, discs, trims.", "§l/cc§r your balance", "Weekly requests and", "a 25%-off find.", "Pays for City trips:", "§b1k 2k 4k 8k CC", ""},
            {"§e§lYour chicken§r", "", "Comes with your island.", "§l/pet§r its menu", "§l/pet call§r  §l/pet away§r", "§l/pet name <name>§r", "",
                    "Fights with you in the", "Nether, End and Sunken.", "Keeps one monster busy.", "Stays home: Ancient", "City, games, duels.",
                    "Downed? It follows you", "but won't fight."},
            {"§e§lChicken food§r", "", "§dLittle Treat§0: downed", "-> 75%. Daily quests +", "first expedition clear", "(2 a day, hold 8).",
                    "§aLittle Snack§0: +10%.", "Pies from §2Snack", "§2Planters§0 (Wren / §l/is§r).", "15 min on your island:", "back to full health.",
                    "Nest Egg once a day!", "", "§l/oldlook§r 2015 blocks"},
            {"§6§lCopper golems§r", "", "Build one by a copper", "chest: up to §l3§r per", "island, shared by the", "whole island.",
                    "§lSneak-click§r a chest", "(empty hand) to label", "it: items, categories", "or overflow. Golems", "sort the copper chest",
                    "(the hub) into them.", "Sneak-click the copper", "chest: the golem hub."},
            {"§6§lGolem jobs§r", "", "Upgrade in the hub or", "§l/is menu§r (money +", "copper), levels 1-5:", "2 Find it  3 Fetch +",
                    "no freezing  4 Furnace", "Tender  5 Courier.", "Storage Ledger: every", "item in your chests.",
                    "Tribute Runner fills", "the Sunken fare pool.", "§l/parcels§r golem mail"},
            {"§l§nRules§r", "", "Be kind in chat.", "No cheats or hacks.", "No scamming trades.", "", "Have fun!", "",
                    "§l/guide§r opens this", "book again."}
    };

    private static final int TOC_PER_PAGE = 12;

    /** The index entry for a page: its title (first colour kept, formatting dropped) and page number. */
    private static String tocLine(String title, int page) {
        String color = "§0";
        for (int i = 0; i + 1 < title.length(); i++) {
            char c = title.charAt(i + 1);
            if (title.charAt(i) == '§' && ((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) {
                color = "§" + c;
                break;
            }
        }
        return color + title.replaceAll("§.", "") + " §8" + page;
    }

    /** Every page in order: the index pages, "What next?", then the topic pages. */
    private static List<String> allPages(ServerPlayer p) {
        List<String> titles = new ArrayList<>();
        titles.add("§2What next?");
        for (String[] page : PAGES) titles.add(page[0]);
        int tocPages = (titles.size() + TOC_PER_PAGE - 1) / TOC_PER_PAGE;
        List<String> out = new ArrayList<>();
        for (int t = 0; t < tocPages; t++) {
            List<String> lines = new ArrayList<>();
            lines.add(t == 0 ? "§l§nServer Guide§r" : "§l§nGuide (" + (t + 1) + "/" + tocPages + ")§r");
            lines.add("");
            for (int i = t * TOC_PER_PAGE; i < Math.min(titles.size(), (t + 1) * TOC_PER_PAGE); i++) lines.add(tocLine(titles.get(i), tocPages + 1 + i));
            out.add(String.join("\n", lines));
        }
        out.add(String.join("\n", whatNext(p)));
        for (String[] page : PAGES) out.add(String.join("\n", page));
        return out;
    }

    /** 5.27: the player's own "What next?" page (their Wren quest, today's win bonuses, an expedition to try). */
    private static List<String> whatNext(ServerPlayer p) {
        List<String> l = new ArrayList<>();
        l.add("§2§l§nWhat next?§r");
        l.add("");
        if (p == null) {
            l.add("Talk to §2Captain Wren§0");
            l.add("on your island (§l/is§r).");
            return l;
        }
        java.util.UUID id = p.getUUID();
        int q = com.aidan.skyserver.island.IslandQuests.questOf(id);
        String task = com.aidan.skyserver.island.IslandQuests.taskOf(id);
        if (q <= 0) l.add("§2Wren:§0 type §l/is§r and talk to Captain Wren.");
        else if (task == null) l.add("§6You finished all of Wren's quests!");
        else l.add("§2Wren " + q + ":§0 " + (task.length() > 62 ? task.substring(0, 60) + "..." : task));
        l.add("");
        int bw = Rewards.winBonusLeft("bedwars", id), sw = Rewards.winBonusLeft("skywars", id), gw = Rewards.winBonusLeft("goldwars", id);
        if (bw + sw + gw > 0) l.add("§4Win bonuses§0 left today (+$300): Bedwars " + bw + ", Skywars " + sw + ", Gold Wars " + gw + ".");
        else l.add("§4All 9 win bonuses§0 done today!");
        l.add("");
        String trip;
        if (!anyStamp(id, "nether_")) trip = "Try an §cEasy Nether§0 trip with Wren ($5k).";
        else if (!anyStamp(id, "city_")) trip = "Try the §3Ancient City§0 - no fighting, just stay quiet.";
        else if (!anyStamp(id, "sunken_")) trip = "Try the §bSunken Monument§0 - paid in farm items.";
        else if (!Charts.has(id, "end_10")) trip = "In full netherite? Try the §5End§0 (wave 10).";
        else trip = "Fill §6Wren's Charts§0: " + Charts.count(id) + " stamps so far (§l/charts§r).";
        l.add(trip);
        return l;
    }

    private static boolean anyStamp(java.util.UUID id, String prefix) {
        for (int d = 0; d < 4; d++) if (Charts.has(id, prefix + d)) return true;
        return false;
    }

    public static ItemStack book() {
        return book(null);
    }

    /** 5.27: the book with this player's own "What next?" page (null = a general one). */
    public static ItemStack book(ServerPlayer p) {
        List<Filterable<Component>> pages = new ArrayList<>();
        for (String page : allPages(p)) pages.add(Filterable.passThrough(Component.literal(page)));
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
        List<String[]> chat = new ArrayList<>();
        chat.add(whatNext(p).toArray(new String[0]));
        for (String[] page : PAGES) chat.add(page);
        for (String[] page : chat) {
            StringBuilder sb = new StringBuilder();
            for (String line : page) {
                if (line.isEmpty()) continue;
                if (sb.length() > 0) sb.append(" §8| ");
                sb.append(line.replace("§0", "§f").replace("§r", "§7"));
            }
            Lobby.msg(p, "§7" + sb);
        }
    }
}
