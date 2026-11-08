package com.aidan.skyserver.lobby;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.Style;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.*;

/**
 * Parties: play together with friends (up to 8).
 *  /party invite <player>, /party accept, /party leave, /party kick <player>, /party disband,
 *  /party (list), /party warp (leader brings everyone in the lobby to them), /pc <message> party chat.
 *
 *  - When the leader joins Skywars or Gold Wars, everyone in the party who's in the lobby joins too.
 *  - In Gold Wars, party members are put on the same team (when there's room).
 *  - Party members can visit each other's Sky Islands without asking.
 * Parties last until the server restarts; leaving the server for 5 minutes takes you out.
 */
public final class Parties {
    public static final int MAX = 8;

    static final class Party {
        UUID leader;
        final LinkedHashSet<UUID> members = new LinkedHashSet<>();
    }

    private static final Map<UUID, Party> OF = new HashMap<>();
    private static final Map<UUID, UUID> INVITES = new HashMap<>();   // invited player -> leader
    private static final Map<UUID, Long> OFFLINE_SINCE = new HashMap<>();
    private static boolean following = false;

    private Parties() {}

    public static void init() {
        CommandRegistrationCallback.EVENT.register((d, r, e) -> {
            for (String root : new String[]{"party", "p"}) {
                LiteralArgumentBuilder<CommandSourceStack> c = Commands.literal(root).executes(x -> { list(x.getSource().getPlayerOrException()); return 1; });
                c.then(Commands.literal("invite").then(Commands.argument("player", EntityArgument.player())
                        .executes(x -> { invite(x.getSource().getPlayerOrException(), EntityArgument.getPlayer(x, "player")); return 1; })));
                c.then(Commands.literal("accept").executes(x -> { accept(x.getSource().getPlayerOrException()); return 1; }));
                c.then(Commands.literal("leave").executes(x -> { leave(x.getSource().getPlayerOrException(), true); return 1; }));
                c.then(Commands.literal("kick").then(Commands.argument("player", StringArgumentType.word())
                        .executes(x -> { kick(x.getSource().getPlayerOrException(), StringArgumentType.getString(x, "player")); return 1; })));
                c.then(Commands.literal("disband").executes(x -> { disband(x.getSource().getPlayerOrException()); return 1; }));
                c.then(Commands.literal("warp").executes(x -> { warp(x.getSource().getPlayerOrException()); return 1; }));
                c.then(Commands.literal("chat").then(Commands.argument("message", StringArgumentType.greedyString())
                        .executes(x -> { chat(x.getSource().getPlayerOrException(), StringArgumentType.getString(x, "message")); return 1; })));
                c.then(Commands.literal("list").executes(x -> { list(x.getSource().getPlayerOrException()); return 1; }));
                d.register(c);
            }
            d.register(Commands.literal("pc").then(Commands.argument("message", StringArgumentType.greedyString())
                    .executes(x -> { chat(x.getSource().getPlayerOrException(), StringArgumentType.getString(x, "message")); return 1; })));
        });
        ServerPlayConnectionEvents.DISCONNECT.register((h, s) -> OFFLINE_SINCE.put(h.getPlayer().getUUID(), System.currentTimeMillis()));
        ServerPlayConnectionEvents.JOIN.register((h, sender, s) -> OFFLINE_SINCE.remove(h.getPlayer().getUUID()));
    }

    /** Every minute: people offline for 5+ minutes leave their party. */
    static void tick(MinecraftServer server, long ticks) {
        if (ticks % 1200 != 0 || OFFLINE_SINCE.isEmpty()) return;
        long now = System.currentTimeMillis();
        for (Map.Entry<UUID, Long> e : new ArrayList<>(OFFLINE_SINCE.entrySet())) {
            if (now - e.getValue() < 5 * 60_000L) continue;
            OFFLINE_SINCE.remove(e.getKey());
            Party p = OF.get(e.getKey());
            if (p != null) remove(server, p, e.getKey(), "§7(offline)");
        }
    }

    // ---- Questions other code asks ----

    public static boolean sameParty(UUID a, UUID b) {
        Party p = OF.get(a);
        return p != null && p.members.contains(b);
    }

    public static Set<UUID> membersOf(UUID id) {
        Party p = OF.get(id);
        return p == null ? Set.of() : Collections.unmodifiableSet(p.members);
    }

    public static boolean isLeader(UUID id) {
        Party p = OF.get(id);
        return p != null && p.leader.equals(id);
    }

    /**
     * Called when someone joins a game. If they lead a party, the others in the lobby come along.
     * game = "skywars", "goldwars" or "bedwars".
     */
    public static void followLeader(ServerPlayer leader, String game) {
        if (following || !isLeader(leader.getUUID())) return;
        following = true;
        try {
            MinecraftServer server = leader.level().getServer();
            for (UUID u : new ArrayList<>(OF.get(leader.getUUID()).members)) {
                if (u.equals(leader.getUUID())) continue;
                ServerPlayer m = server.getPlayerList().getPlayer(u);
                if (m == null || !Lobby.isLobby(m.level())) continue;
                Lobby.msg(m, "§dYour party leader joined " + (game.equals("skywars") ? "Skywars" : game.equals("bedwars") ? "Bedwars" : "Gold Wars") + " - bringing you along!");
                Lobby.joinGame(m, game);
            }
        } finally {
            following = false;
        }
    }

    // ---- Commands ----

    private static void tell(MinecraftServer server, Party p, String text) {
        for (UUID u : p.members) {
            ServerPlayer m = server.getPlayerList().getPlayer(u);
            if (m != null) Lobby.msg(m, text);
        }
    }

    static void invite(ServerPlayer from, ServerPlayer to) {
        if (to == from || to instanceof com.aidan.skyserver.bots.BotPlayer) return;
        Party p = OF.get(from.getUUID());
        if (p == null) {
            p = new Party();
            p.leader = from.getUUID();
            p.members.add(from.getUUID());
            OF.put(from.getUUID(), p);
        }
        if (!p.leader.equals(from.getUUID())) {
            Lobby.msg(from, "§cOnly the party leader can invite.");
            return;
        }
        if (p.members.size() >= MAX) {
            Lobby.msg(from, "§cYour party is full (" + MAX + ").");
            return;
        }
        if (OF.containsKey(to.getUUID())) {
            Lobby.msg(from, "§c" + to.getName().getString() + " is already in a party.");
            return;
        }
        INVITES.put(to.getUUID(), from.getUUID());
        Lobby.msg(from, "§dInvited " + to.getName().getString() + " to your party.");
        to.sendSystemMessage(Component.literal("§d" + from.getName().getString() + " invited you to their party. ")
                .append(Component.literal("§a§l[Accept]").withStyle(Style.EMPTY.withClickEvent(new ClickEvent.RunCommand("/party accept"))
                        .withHoverEvent(new HoverEvent.ShowText(Component.literal("Join the party"))))));
    }

    static void accept(ServerPlayer p) {
        UUID leader = INVITES.remove(p.getUUID());
        Party party = leader == null ? null : OF.get(leader);
        if (party == null || !party.leader.equals(leader)) {
            Lobby.msg(p, "§7You don't have a party invite.");
            return;
        }
        if (OF.containsKey(p.getUUID())) leave(p, false);
        if (party.members.size() >= MAX) {
            Lobby.msg(p, "§cThat party is full.");
            return;
        }
        party.members.add(p.getUUID());
        OF.put(p.getUUID(), party);
        tell(p.level().getServer(), party, "§d" + p.getName().getString() + " joined the party! §7(" + party.members.size() + "/" + MAX + ") §8/pc to chat");
    }

    static void leave(ServerPlayer p, boolean tellThem) {
        Party party = OF.get(p.getUUID());
        if (party == null) {
            if (tellThem) Lobby.msg(p, "§7You're not in a party.");
            return;
        }
        remove(p.level().getServer(), party, p.getUUID(), "left");
        if (tellThem) Lobby.msg(p, "§7You left the party.");
    }

    private static void remove(MinecraftServer server, Party party, UUID who, String how) {
        party.members.remove(who);
        OF.remove(who);
        String name = nameOf(server, who);
        if (party.members.size() <= 1) {
            for (UUID u : party.members) {
                OF.remove(u);
                ServerPlayer m = server.getPlayerList().getPlayer(u);
                if (m != null) Lobby.msg(m, "§7" + name + " " + how + " - the party has ended.");
            }
            party.members.clear();
            return;
        }
        if (party.leader.equals(who)) party.leader = party.members.iterator().next();
        tell(server, party, "§d" + name + " " + how + ". §7Leader: " + nameOf(server, party.leader));
    }

    private static String nameOf(MinecraftServer server, UUID u) {
        ServerPlayer m = server.getPlayerList().getPlayer(u);
        return m != null ? m.getName().getString() : "a player";
    }

    static void kick(ServerPlayer p, String name) {
        Party party = OF.get(p.getUUID());
        if (party == null || !party.leader.equals(p.getUUID())) {
            Lobby.msg(p, "§cOnly the party leader can kick.");
            return;
        }
        ServerPlayer t = p.level().getServer().getPlayerList().getPlayerByName(name);
        if (t == null || !party.members.contains(t.getUUID()) || t == p) {
            Lobby.msg(p, "§c" + name + " isn't in your party.");
            return;
        }
        remove(p.level().getServer(), party, t.getUUID(), "was removed");
        Lobby.msg(t, "§cYou were removed from the party.");
    }

    static void disband(ServerPlayer p) {
        Party party = OF.get(p.getUUID());
        if (party == null || !party.leader.equals(p.getUUID())) {
            Lobby.msg(p, "§cOnly the party leader can disband it.");
            return;
        }
        tell(p.level().getServer(), party, "§7The party was disbanded.");
        for (UUID u : party.members) OF.remove(u);
        party.members.clear();
    }

    /** The leader pulls everyone who's in the lobby to where they are in the lobby. */
    static void warp(ServerPlayer p) {
        Party party = OF.get(p.getUUID());
        if (party == null || !party.leader.equals(p.getUUID())) {
            Lobby.msg(p, "§cOnly the party leader can warp the party.");
            return;
        }
        if (!Lobby.isLobby(p.level())) {
            Lobby.msg(p, "§cParty warp works in the lobby.");
            return;
        }
        for (UUID u : party.members) {
            ServerPlayer m = p.level().getServer().getPlayerList().getPlayer(u);
            if (m == null || m == p || !Lobby.isLobby(m.level())) continue;
            Lobby.leaveLobbyStuff(m);
            m.teleportTo((net.minecraft.server.level.ServerLevel) p.level(), p.getX(), p.getY(), p.getZ(), Set.of(), p.getYRot(), 0f, true);
            Lobby.refreshKit(m);
            Lobby.msg(m, "§dWarped to your party leader.");
        }
    }

    static void chat(ServerPlayer p, String text) {
        if (!ChatFilter.allow(p, text, "chat")) return;
        Party party = OF.get(p.getUUID());
        if (party == null) {
            Lobby.msg(p, "§7You're not in a party. §e/party invite <player>");
            return;
        }
        tell(p.level().getServer(), party, "§d[PARTY] §f" + p.getName().getString() + "§7: §f" + text.replace("§", ""));
    }

    static void list(ServerPlayer p) {
        Party party = OF.get(p.getUUID());
        if (party == null) {
            Lobby.msg(p, "§7You're not in a party. §e/party invite <player> §7to start one (up to " + MAX + "). Parties join games together.");
            return;
        }
        List<String> names = new ArrayList<>();
        for (UUID u : party.members) names.add((u.equals(party.leader) ? "§6★" : "§f") + nameOf(p.level().getServer(), u));
        Lobby.msg(p, "§d§lPARTY §7(" + party.members.size() + "/" + MAX + "): " + String.join("§7, ", names));
        Lobby.msg(p, "§7/party invite, kick, leave, disband, warp §8| §7/pc <message>");
    }
}
