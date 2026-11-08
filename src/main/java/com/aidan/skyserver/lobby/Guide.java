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
                    "§cNether trips§0 ... 19", "§dShards & more§0 . 20"},
            {"§2§lSky Island§r", "", "§l/is§r", "go to your island", "(made the first time)", "",
                    "§l/is menu§r", "upgrades, size, info", "", "§l/is challenges§r", "goals with rewards", "",
                    "§l/is sethome§r", "set where you appear"},
            {"§2§lIsland friends§r", "", "§l/is invite <name>§r", "add a co-op member", "(up to 4)",
                    "§l/is join <name>§r", "§l/is leave§r  §l/is kick§r", "§l/is members§r", "",
                    "§l/is trust <name> [min]§r", "let them build for", "a while (no chests)", "§l/is untrust <name>§r",
                    "§l/is visit <name>§r  §l/is open§r"},
            {"§2§lMore island§r", "", "§l/is level§r", "level + worth", "§l/is top§r", "richest islands", "",
                    "§l/is movewren§r", "move Captain Wren", "", "You keep your items", "if you fall or die", "on your island.",
                    "Sleep = morning on", "your island only."},
            {"§4§lGames§r", "", "§lGold Wars§r", "§l/gw join§r  §l/gw leave§r", "§l/class§r pick a class", "",
                    "§lSkywars§r", "§l/sw join§r  §l/sw leave§r", "§l/tc§r team chat", "§l/mc§r match chat", "Or walk into the", "portals by spawn.",
                    "Click a map name in", "chat to vote for it.",
                    "§l/lobby§r back (stand", "still 5 seconds)"},
            {"§c§lBedwars§r", "", "§l/bw join§r  §l/bw leave§r", "§l/bw stats§r your record", "4 teams of 3 or 2. While", "your bed stands you",
                    "respawn. Break theirs!", "", "Iron & gold: home", "Diamonds: side islands", "(team upgrades)", "Emeralds: the middle",
                    "Right-click the", "shopkeepers to buy."},
            {"§6§lLobby fun§r", "", "§l/spleef§r join the", "Spleef / TNT Run pit", "",
                    "§l/duel <name> [bet]§r", "1v1, winner takes", "the whole bet", "",
                    "Archery, the Maze,", "Elytra Rings, the", "Fishing Pond and 4", "obstacle courses:", "use the compass!"},
            {"§9§lKeys & money§r", "", "§l/keys§r your keys", "Open them on the", "chests in the Great", "Hall.", "",
                    "Log in daily: a 7-day", "streak, Rare Key on", "day 7!", "§l/trade <name>§r", "keys, items, money", "§l/claim§r get prizes", "(on your island)"},
            {"§5§lLevels§r", "", "§l/level§r  §l/rewards§r", "One level for the", "whole server, 1-99.", "Never resets!", "",
                    "After 99: Prestige.", "Every 10 prestige", "levels = a new label", "by your name.", "",
                    "Most XP: Skywars, Gold", "Wars, Bedwars, Island."},
            {"§6§lBattle pass§r", "", "§l/pass§r 50 tiers of", "rewards, 6-week", "seasons.", "§l/pass challenges§r", "7 new every Monday",
                    "§l/quests§r 3 daily", "(they give pass XP)", "§l/cosmetics§r titles", "and trails", "",
                    "§l/pass top§r: top 3", "win big keys!"},
            {"§6§lThe Town§r", "", "South-east of the", "castle (compass).", "", "§l/bank§r 1% a week", "§l/skins§r skin shop", "",
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
            {"§c§lNether trips§r", "", "§l/is§r -> Nether", "Expedition: Captain", "Wren's airship takes", "your island crew to", "a Fortress or Bastion.", "",
                    "§a$5k §e$10k §c$20k §4$50k", "Beat chest guards,", "then the boss.", "§cDie = lose what", "§cyou carry!", "Sneak on the rope", "to go home."},
            {"§d§lShards & more§r", "", "Every chest gives", "Key Shards. §l/shards§r", "buys keys, cosmetics,", "boosters.", "",
                    "§l/boosters§r use them", "§l/cshop§r cosmetics", "(new every Monday)", "§l/cosmetics§r equip", "§l/is theme§r island", "look (theme token)"},
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
