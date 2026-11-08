package com.aidan.skyserver.lobby;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.Style;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.*;

/**
 * Factions: groups of up to 8 players.
 *  /f create <name>, /f invite <player>, /f join <name>, /f leave, /f kick <player>, /f disband,
 *  /f info [name], /f top, /f chat <message> (or /fc <message>), /f deposit|withdraw <amount>.
 * The leader can invite, kick, withdraw from the faction bank and disband. Anyone can deposit.
 * Faction level = the island levels of its members added up (each island counted once).
 * No land claims or faction PvP (yet). Saved in config/skyserver/factions.json.
 */
public final class Factions {
    public static final int MAX_MEMBERS = 8;
    public static final long CREATE_COST = 5_000;

    static final class Faction {
        String name;
        UUID leader;
        final LinkedHashMap<UUID, String> members = new LinkedHashMap<>();
        long bank = 0;
    }

    private static final Map<String, Faction> FACTIONS = new LinkedHashMap<>(); // lower-case name -> faction
    private static final Map<UUID, String> INVITES = new HashMap<>();          // player -> faction name
    private static boolean loaded = false;

    private Factions() {}

    public static void init() {
        CommandRegistrationCallback.EVENT.register((d, r, e) -> {
            for (String root : new String[]{"f", "faction"}) {
                LiteralArgumentBuilder<CommandSourceStack> f = Commands.literal(root).executes(c -> { info(c.getSource().getPlayerOrException(), null); return 1; });
                f.then(Commands.literal("create").then(Commands.argument("name", StringArgumentType.word())
                        .executes(c -> { create(c.getSource().getPlayerOrException(), StringArgumentType.getString(c, "name")); return 1; })));
                f.then(Commands.literal("invite").then(Commands.argument("player", EntityArgument.player())
                        .executes(c -> { invite(c.getSource().getPlayerOrException(), EntityArgument.getPlayer(c, "player")); return 1; })));
                f.then(Commands.literal("join").executes(c -> { join(c.getSource().getPlayerOrException(), null); return 1; })
                        .then(Commands.argument("name", StringArgumentType.word()).suggests((c, b) -> SharedSuggestionProvider.suggest(names(), b))
                                .executes(c -> { join(c.getSource().getPlayerOrException(), StringArgumentType.getString(c, "name")); return 1; })));
                f.then(Commands.literal("leave").executes(c -> { leave(c.getSource().getPlayerOrException()); return 1; }));
                f.then(Commands.literal("kick").then(Commands.argument("player", StringArgumentType.word())
                        .executes(c -> { kick(c.getSource().getPlayerOrException(), StringArgumentType.getString(c, "player")); return 1; })));
                f.then(Commands.literal("disband").executes(c -> { disband(c.getSource().getPlayerOrException()); return 1; }));
                f.then(Commands.literal("info").executes(c -> { info(c.getSource().getPlayerOrException(), null); return 1; })
                        .then(Commands.argument("name", StringArgumentType.word()).suggests((c, b) -> SharedSuggestionProvider.suggest(names(), b))
                                .executes(c -> { info(c.getSource().getPlayerOrException(), StringArgumentType.getString(c, "name")); return 1; })));
                f.then(Commands.literal("top").executes(c -> {
                    ServerPlayer p = c.getSource().getPlayerOrException();
                    Lobby.msg(p, "§9§lTOP FACTIONS");
                    for (String l : top(10)) Lobby.msg(p, l);
                    return 1;
                }));
                f.then(Commands.literal("chat").then(Commands.argument("message", StringArgumentType.greedyString())
                        .executes(c -> { chat(c.getSource().getPlayerOrException(), StringArgumentType.getString(c, "message")); return 1; })));
                f.then(Commands.literal("deposit").then(Commands.argument("amount", StringArgumentType.word())
                        .executes(c -> { bank(c.getSource().getPlayerOrException(), StringArgumentType.getString(c, "amount"), true); return 1; })));
                f.then(Commands.literal("withdraw").then(Commands.argument("amount", StringArgumentType.word())
                        .executes(c -> { bank(c.getSource().getPlayerOrException(), StringArgumentType.getString(c, "amount"), false); return 1; })));
                d.register(f);
            }
            d.register(Commands.literal("fc").then(Commands.argument("message", StringArgumentType.greedyString())
                    .executes(c -> { chat(c.getSource().getPlayerOrException(), StringArgumentType.getString(c, "message")); return 1; })));
        });
    }

    private static List<String> names() {
        load();
        List<String> l = new ArrayList<>();
        for (Faction f : FACTIONS.values()) l.add(f.name);
        return l;
    }

    public static String factionOf(UUID id) {
        Faction f = of(id);
        return f == null ? null : f.name;
    }

    private static Faction of(UUID id) {
        load();
        for (Faction f : FACTIONS.values()) if (f.members.containsKey(id)) return f;
        return null;
    }

    private static MinecraftServer server() {
        return Lobby.server();
    }

    private static void tellAll(Faction f, String text) {
        for (UUID u : f.members.keySet()) {
            ServerPlayer m = server().getPlayerList().getPlayer(u);
            if (m != null) Lobby.msg(m, text);
        }
    }

    /** Faction level: members' island levels added up (each island once). */
    static int level(Faction f) {
        Set<Integer> seen = new HashSet<>();
        int total = 0;
        for (UUID u : f.members.keySet()) {
            var is = com.aidan.skyserver.island.Islands.islandOf(u);
            if (is != null && seen.add(is.id)) total += is.level();
        }
        return total;
    }

    static void create(ServerPlayer p, String name) {
        if (!ChatFilter.allow(p, name, "faction name")) return;
        load();
        if (of(p.getUUID()) != null) {
            Lobby.msg(p, "§cYou're already in a faction. §7(/f leave first)");
            return;
        }
        if (!name.matches("[A-Za-z0-9_]{3,16}")) {
            Lobby.msg(p, "§cFaction names are 3-16 letters, numbers or _.");
            return;
        }
        if (FACTIONS.containsKey(name.toLowerCase(Locale.ROOT))) {
            Lobby.msg(p, "§cThere's already a faction called " + name + ".");
            return;
        }
        if (!Economy.take(server(), p.getUUID(), CREATE_COST)) {
            Lobby.msg(p, "§cStarting a faction costs " + Economy.format(CREATE_COST) + ".");
            return;
        }
        Faction f = new Faction();
        f.name = name;
        f.leader = p.getUUID();
        f.members.put(p.getUUID(), p.getName().getString());
        FACTIONS.put(name.toLowerCase(Locale.ROOT), f);
        save();
        Lobby.msg(p, "§9§lFaction " + name + " created! §7Invite friends with §e/f invite <player>§7 (up to " + MAX_MEMBERS + "). Talk with §e/fc <message>§7.");
    }

    static void invite(ServerPlayer p, ServerPlayer target) {
        Faction f = of(p.getUUID());
        if (f == null || !f.leader.equals(p.getUUID())) {
            Lobby.msg(p, "§cOnly a faction leader can invite.");
            return;
        }
        if (target instanceof com.aidan.skyserver.bots.BotPlayer || target == p) return;
        if (f.members.size() >= MAX_MEMBERS) {
            Lobby.msg(p, "§cYour faction is full (" + MAX_MEMBERS + ").");
            return;
        }
        if (of(target.getUUID()) != null) {
            Lobby.msg(p, "§c" + target.getName().getString() + " is already in a faction.");
            return;
        }
        INVITES.put(target.getUUID(), f.name.toLowerCase(Locale.ROOT));
        Lobby.msg(p, "§9Invited " + target.getName().getString() + " to " + f.name + ".");
        target.sendSystemMessage(Component.literal("§9" + p.getName().getString() + " invited you to the faction §l" + f.name + "§9. ")
                .append(Component.literal("§a§l[Join]").withStyle(Style.EMPTY.withClickEvent(new ClickEvent.RunCommand("/f join " + f.name))
                        .withHoverEvent(new HoverEvent.ShowText(Component.literal("Join " + f.name))))));
    }

    static void join(ServerPlayer p, String name) {
        load();
        String key = INVITES.get(p.getUUID());
        if (key == null || (name != null && !key.equals(name.toLowerCase(Locale.ROOT)))) {
            Lobby.msg(p, "§cYou need an invite from that faction's leader.");
            return;
        }
        Faction f = FACTIONS.get(key);
        if (f == null) {
            INVITES.remove(p.getUUID());
            Lobby.msg(p, "§cThat faction doesn't exist any more.");
            return;
        }
        if (of(p.getUUID()) != null) {
            Lobby.msg(p, "§cLeave your current faction first (§e/f leave§c).");
            return;
        }
        if (f.members.size() >= MAX_MEMBERS) {
            Lobby.msg(p, "§cThat faction is full.");
            return;
        }
        INVITES.remove(p.getUUID());
        f.members.put(p.getUUID(), p.getName().getString());
        save();
        tellAll(f, "§9" + p.getName().getString() + " joined " + f.name + "!");
    }

    static void leave(ServerPlayer p) {
        Faction f = of(p.getUUID());
        if (f == null) {
            Lobby.msg(p, "§7You're not in a faction.");
            return;
        }
        if (f.leader.equals(p.getUUID())) {
            if (f.members.size() > 1) {
                // Leadership passes to the next member.
                f.members.remove(p.getUUID());
                f.leader = f.members.keySet().iterator().next();
                tellAll(f, "§9" + p.getName().getString() + " left. §f" + f.members.get(f.leader) + " §9is the new leader.");
                save();
                Lobby.msg(p, "§7You left " + f.name + ".");
                return;
            }
            disband(p);
            return;
        }
        f.members.remove(p.getUUID());
        save();
        Lobby.msg(p, "§7You left " + f.name + ".");
        tellAll(f, "§9" + p.getName().getString() + " left the faction.");
    }

    static void kick(ServerPlayer p, String name) {
        Faction f = of(p.getUUID());
        if (f == null || !f.leader.equals(p.getUUID())) {
            Lobby.msg(p, "§cOnly the faction leader can kick.");
            return;
        }
        for (Map.Entry<UUID, String> m : new ArrayList<>(f.members.entrySet())) {
            if (m.getValue().equalsIgnoreCase(name) && !m.getKey().equals(f.leader)) {
                f.members.remove(m.getKey());
                save();
                tellAll(f, "§9" + m.getValue() + " was removed from the faction.");
                ServerPlayer k = server().getPlayerList().getPlayer(m.getKey());
                if (k != null) Lobby.msg(k, "§cYou were removed from " + f.name + ".");
                return;
            }
        }
        Lobby.msg(p, "§c" + name + " isn't in your faction.");
    }

    static void disband(ServerPlayer p) {
        Faction f = of(p.getUUID());
        if (f == null || !f.leader.equals(p.getUUID())) {
            Lobby.msg(p, "§cOnly the faction leader can disband it.");
            return;
        }
        if (f.bank > 0) Economy.add(server(), p.getUUID(), p.getName().getString(), f.bank);
        tellAll(f, "§9" + f.name + " was disbanded." + (f.bank > 0 ? " §7(The leader got the faction bank: " + Economy.format(f.bank) + ")" : ""));
        FACTIONS.remove(f.name.toLowerCase(Locale.ROOT));
        save();
    }

    static void info(ServerPlayer p, String name) {
        load();
        Faction f = name == null ? of(p.getUUID()) : FACTIONS.get(name.toLowerCase(Locale.ROOT));
        if (f == null) {
            if (name != null) Lobby.msg(p, "§cNo faction called " + name + ".");
            else Lobby.msg(p, "§7You're not in a faction. §e/f create <name> §7(" + Economy.format(CREATE_COST) + ") or ask a leader for an invite. §e/f top §7lists them.");
            return;
        }
        Lobby.msg(p, "§9§l" + f.name + " §7- level §f" + level(f) + " §7- " + f.members.size() + "/" + MAX_MEMBERS + " members - bank §6" + Economy.format(f.bank));
        List<String> names = new ArrayList<>();
        for (Map.Entry<UUID, String> m : f.members.entrySet())
            names.add((m.getKey().equals(f.leader) ? "§6★" : "§f") + m.getValue() + (server().getPlayerList().getPlayer(m.getKey()) != null ? "§a●" : "§8●"));
        Lobby.msg(p, "§7Members: " + String.join("§7, ", names));
    }

    static void chat(ServerPlayer p, String text) {
        if (!ChatFilter.allow(p, text, "chat")) return;
        Faction f = of(p.getUUID());
        if (f == null) {
            Lobby.msg(p, "§7You're not in a faction.");
            return;
        }
        tellAll(f, "§9[" + f.name + "] §f" + p.getName().getString() + "§7: §f" + text.replace("§", ""));
    }

    static void bank(ServerPlayer p, String text, boolean deposit) {
        Faction f = of(p.getUUID());
        if (f == null) {
            Lobby.msg(p, "§7You're not in a faction.");
            return;
        }
        if (!deposit && !f.leader.equals(p.getUUID())) {
            Lobby.msg(p, "§cOnly the leader can take money out of the faction bank.");
            return;
        }
        long amount;
        try {
            amount = text.equalsIgnoreCase("all") ? (deposit ? Economy.balance(server(), p.getUUID()) : f.bank) : Long.parseLong(text.replace(",", ""));
        } catch (NumberFormatException e) {
            Lobby.msg(p, "§cThat's not an amount.");
            return;
        }
        if (amount <= 0) return;
        if (deposit) {
            if (!Economy.take(server(), p.getUUID(), amount)) {
                Lobby.msg(p, "§cYou don't have " + Economy.format(amount) + ".");
                return;
            }
            f.bank += amount;
            tellAll(f, "§9" + p.getName().getString() + " put §6" + Economy.format(amount) + " §9in the faction bank (now " + Economy.format(f.bank) + ").");
        } else {
            if (amount > f.bank) {
                Lobby.msg(p, "§cThe faction bank only has " + Economy.format(f.bank) + ".");
                return;
            }
            f.bank -= amount;
            Economy.add(server(), p.getUUID(), p.getName().getString(), amount);
            tellAll(f, "§9" + p.getName().getString() + " took §6" + Economy.format(amount) + " §9from the faction bank (now " + Economy.format(f.bank) + ").");
        }
        save();
    }

    public static List<String> top(int n) {
        load();
        List<Faction> list = new ArrayList<>(FACTIONS.values());
        Map<Faction, Integer> lv = new HashMap<>();
        for (Faction f : list) lv.put(f, level(f));
        list.sort((a, b) -> Integer.compare(lv.get(b), lv.get(a)));
        List<String> out = new ArrayList<>();
        for (int i = 0; i < Math.min(n, list.size()); i++)
            out.add("§6" + (i + 1) + ". §9" + list.get(i).name + " §7level §f" + lv.get(list.get(i)) + " §8(" + list.get(i).members.size() + " members)");
        if (out.isEmpty()) out.add("§7nobody yet");
        return out;
    }

    private static void load() {
        if (loaded) return;
        loaded = true;
        JsonObject root = Stacks.readFile("factions.json");
        for (Map.Entry<String, JsonElement> e : root.entrySet()) {
            JsonObject o = e.getValue().getAsJsonObject();
            Faction f = new Faction();
            f.name = o.get("name").getAsString();
            f.leader = UUID.fromString(o.get("leader").getAsString());
            f.bank = o.has("bank") ? o.get("bank").getAsLong() : 0;
            for (Map.Entry<String, JsonElement> m : o.getAsJsonObject("members").entrySet()) f.members.put(UUID.fromString(m.getKey()), m.getValue().getAsString());
            FACTIONS.put(f.name.toLowerCase(Locale.ROOT), f);
        }
    }

    private static void save() {
        JsonObject root = new JsonObject();
        for (Faction f : FACTIONS.values()) {
            JsonObject o = new JsonObject(), m = new JsonObject();
            o.addProperty("name", f.name);
            o.addProperty("leader", f.leader.toString());
            o.addProperty("bank", f.bank);
            f.members.forEach((k, v) -> m.addProperty(k.toString(), v));
            o.add("members", m);
            root.add(f.name.toLowerCase(Locale.ROOT), o);
        }
        Stacks.writeFile("factions.json", root);
    }
}
