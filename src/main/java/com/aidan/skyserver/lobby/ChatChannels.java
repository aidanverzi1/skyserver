package com.aidan.skyserver.lobby;

import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.*;

/**
 * Team chat and match chat for Gold Wars and Skywars.
 *  /tc <message>  - your Gold Wars team only
 *  /mc <message>  - everyone in your match (Gold Wars or Skywars)
 *  /chat team | match | all - where your normal chat goes while you're in a match
 *                             (back in the lobby, normal chat always goes to everyone)
 */
public final class ChatChannels {
    private enum Channel { ALL, TEAM, MATCH }

    private static final Map<UUID, Channel> DEFAULT = new HashMap<>();

    private ChatChannels() {}

    public static void init() {
        CommandRegistrationCallback.EVENT.register((d, r, e) -> {
            for (String name : new String[]{"tc", "teamchat"})
                d.register(Commands.literal(name).then(Commands.argument("message", StringArgumentType.greedyString())
                        .executes(c -> { send(c.getSource().getPlayerOrException(), Channel.TEAM, StringArgumentType.getString(c, "message")); return 1; })));
            for (String name : new String[]{"mc", "matchchat"})
                d.register(Commands.literal(name).then(Commands.argument("message", StringArgumentType.greedyString())
                        .executes(c -> { send(c.getSource().getPlayerOrException(), Channel.MATCH, StringArgumentType.getString(c, "message")); return 1; })));
            d.register(Commands.literal("chat")
                    .executes(c -> {
                        ServerPlayer p = c.getSource().getPlayerOrException();
                        Lobby.msg(p, "§7Your chat in matches goes to: §f" + DEFAULT.getOrDefault(p.getUUID(), Channel.ALL).name().toLowerCase()
                                + "§7. Change it with §e/chat team§7, §e/chat match §7or §e/chat all§7. One-off: §e/tc <msg>§7, §e/mc <msg>§7.");
                        return 1;
                    })
                    .then(Commands.literal("team").executes(c -> set(c.getSource().getPlayerOrException(), Channel.TEAM)))
                    .then(Commands.literal("match").executes(c -> set(c.getSource().getPlayerOrException(), Channel.MATCH)))
                    .then(Commands.literal("all").executes(c -> set(c.getSource().getPlayerOrException(), Channel.ALL))));
        });
        // Normal chat: redirect it if the player picked team or match chat and is in a match.
        ServerMessageEvents.ALLOW_CHAT_MESSAGE.register((message, sender, params) -> {
            Channel ch = DEFAULT.getOrDefault(sender.getUUID(), Channel.ALL);
            if (ch == Channel.ALL || game(sender) == null) return true;
            Lobby.runLater(() -> send(sender, ch, message.signedContent()));
            return false;
        });
    }

    private static int set(ServerPlayer p, Channel ch) {
        DEFAULT.put(p.getUUID(), ch);
        Lobby.msg(p, "§7In matches, your chat now goes to §f" + ch.name().toLowerCase() + "§7." + (ch == Channel.ALL ? "" : " §8(/chat all to change back)"));
        return 1;
    }

    /** "goldwars", "skywars", "bedwars", or null if not in a match. */
    private static String game(ServerPlayer p) {
        if (com.aidan.skyserver.goldwars.GoldWarsGame.isInGame(p.getUUID())) return "goldwars";
        if (com.aidan.skyserver.bedwars.BedwarsGame.isInGame(p.getUUID())) return "bedwars";
        if (com.aidan.skyserver.skywars.SkywarsGame.isInGame(p.getUUID())) return "skywars";
        return null;
    }

    private static void send(ServerPlayer p, Channel ch, String text) {
        if (!ChatFilter.allow(p, text, "chat")) return;
        String game = game(p);
        if (game == null) {
            Lobby.msg(p, "§cYou're not in a match. §7(Team and match chat work in Gold Wars, Bedwars and Skywars.)");
            return;
        }
        text = text.replace("§", "");
        MinecraftServer server = p.level().getServer();
        List<ServerPlayer> to = new ArrayList<>();
        String tag;
        if (ch == Channel.TEAM && game.equals("bedwars")) {
            var bt = com.aidan.skyserver.bedwars.BedwarsGame.teamOf(p.getUUID());
            for (ServerPlayer o : server.getPlayerList().getPlayers())
                if (bt != null && bt == com.aidan.skyserver.bedwars.BedwarsGame.teamOf(o.getUUID())) to.add(o);
            tag = (bt == null ? "§7" : bt.color) + "[TEAM] ";
        } else if (ch == Channel.TEAM) {
            if (!game.equals("goldwars")) {
                Lobby.msg(p, "§7Skywars has no teams - use §e/mc <message> §7for match chat.");
                return;
            }
            var team = com.aidan.skyserver.goldwars.GoldWarsBots.team(p.getUUID());
            if (team == null) {
                Lobby.msg(p, "§7You're not on a team yet.");
                return;
            }
            for (ServerPlayer o : server.getPlayerList().getPlayers())
                if (team == com.aidan.skyserver.goldwars.GoldWarsBots.team(o.getUUID())) to.add(o);
            tag = team.color + "[TEAM] ";
        } else {
            for (ServerPlayer o : server.getPlayerList().getPlayers()) if (game.equals(game(o))) to.add(o);
            tag = game.equals("goldwars") ? "§6[MATCH] " : game.equals("bedwars") ? "§c[MATCH] " : "§b[MATCH] ";
        }
        Component line = Component.literal(tag + "§f" + p.getName().getString() + "§7: §f" + text);
        for (ServerPlayer o : to) if (!(o instanceof com.aidan.skyserver.bots.BotPlayer)) o.sendSystemMessage(line);
    }
}
