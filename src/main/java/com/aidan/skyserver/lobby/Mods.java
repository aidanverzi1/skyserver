package com.aidan.skyserver.lobby;

import com.aidan.skyserver.Skyserver;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.level.GameType;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 5.9: Moderators. One mod level; Aidan (the only op) adds and removes them. Mods are NOT vanilla
 * ops, so they never get /gamemode, /give, money, keys, tickets or boosters.
 *
 * Op only:  /ss mod add <player> | /ss mod remove <player> | /ss mod list
 * Mods (and ops), all under /mod:
 *   /mod fill bw [count] | /mod fill gw | /mod fill sw   - fill the waiting game with bots
 *   /mod skip                                            - skip Gold Wars' countdown or build phase (never ends a match)
 *   /mod mute <player> <minutes> [reason] | /mod unmute <player>
 *   /mod kick <player> [reason]
 *   /mod ban <player> <minutes> [reason] (24 hours at most) | /mod unban <player>
 *   /mod spectate <player> (from the lobby, players in Bedwars/Gold Wars/Skywars) | /mod spectate (back to the lobby)
 *   /mod inv <player>     - look at someone's inventory (look only)
 *   /mod status | /mod log <player> | /mod backup | /mod spleef <minutes> | /mod endexpeditions
 *   /sc <message>         - staff chat (mods + ops)
 * Mods can't mute, kick, ban or spectate-check ops or other mods. Chat shows "[MOD]" before a mod's
 * name (Ranks). Every mod action goes to config/skyserver/logs/mod-actions.log and to online ops.
 * Mods also see the chat filter's alerts.
 */
public final class Mods {
    private Mods() {}

    public static final int MAX_MINUTES = 24 * 60;
    private static final Map<UUID, String> MODS = new LinkedHashMap<>();
    private static final Map<UUID, Ban> BANS = new HashMap<>();
    private static final Map<UUID, double[]> SPECTATING = new HashMap<>(); // lobby spot to come back to
    private static boolean loaded = false;
    private static MinecraftServer server;

    private record Ban(String name, long until, String reason, String by) {}

    // =====================================================================
    // Who is what
    // =====================================================================

    public static boolean isMod(UUID id) {
        load();
        return id != null && MODS.containsKey(id);
    }

    public static boolean isOp(ServerPlayer p) {
        return p != null && server != null && server.getPlayerList().isOp(p.nameAndId());
    }

    /** Console, ops and mods. */
    public static boolean staff(CommandSourceStack s) {
        ServerPlayer p = s.getPlayer();
        return p == null || isOp(p) || isMod(p.getUUID());
    }

    /** Console and ops only. */
    public static boolean opOnly(CommandSourceStack s) {
        ServerPlayer p = s.getPlayer();
        return p == null || isOp(p);
    }

    /** Online mods and ops (not bots). */
    public static List<ServerPlayer> onlineStaff() {
        List<ServerPlayer> out = new ArrayList<>();
        if (server == null) return out;
        for (ServerPlayer p : server.getPlayerList().getPlayers())
            if (!(p instanceof com.aidan.skyserver.bots.BotPlayer) && (isOp(p) || isMod(p.getUUID()))) out.add(p);
        return out;
    }

    // =====================================================================
    // Setup
    // =====================================================================

    public static void init() {
        ServerLifecycleEvents.SERVER_STARTED.register(s -> {
            server = s;
            load();
        });
        // Banned players are turned away at the door.
        ServerPlayConnectionEvents.JOIN.register((handler, sender, s) -> {
            server = s;
            ServerPlayer p = handler.getPlayer();
            Ban b = activeBan(p.getUUID());
            if (b != null) {
                handler.disconnect(Component.literal("§cYou're banned from SkyServer for " + left(b.until - System.currentTimeMillis())
                        + ".\n§7Reason: §f" + b.reason));
                return;
            }
            if (isMod(p.getUUID())) Lobby.msg(p, "§bYou're a SkyServer moderator. §7/mod for your commands, /sc for staff chat.");
        });
        // Logging out while spectating: back to the lobby first (so nothing saves the wrong inventory).
        ServerPlayConnectionEvents.DISCONNECT.register((handler, s) -> com.aidan.skyserver.Safe.run("mod spectate logout", () -> {
            ServerPlayer p = handler.getPlayer();
            if (p != null && SPECTATING.containsKey(p.getUUID())) stopSpectating(p, false);
        }));
        // Spectators may only be in a game world: anywhere else (islands, the Nether, survival) a
        // command like /lobby would save their empty spectator inventory over their real one.
        ServerTickEvents.END_SERVER_TICK.register(s -> {
            if (SPECTATING.isEmpty() || s.getTickCount() % 10 != 0) return;
            com.aidan.skyserver.Safe.run("mod spectate", () -> {
                for (UUID id : new ArrayList<>(SPECTATING.keySet())) {
                    ServerPlayer p = s.getPlayerList().getPlayer(id);
                    if (p == null) SPECTATING.remove(id);
                    else if (!Lobby.isGameLevel(p.level())) {
                        stopSpectating(p, true);
                        Lobby.msg(p, "§7Spectating only works in Bedwars, Gold Wars and Skywars.");
                    } else if (!p.isSpectator()) p.setGameMode(GameType.SPECTATOR);
                }
            });
        });
        CommandRegistrationCallback.EVENT.register((d, r, e) -> {
            d.register(modCommand());
            d.register(Commands.literal("sc").requires(Mods::staff)
                    .then(Commands.argument("message", StringArgumentType.greedyString())
                            .executes(c -> { staffChat(c.getSource(), StringArgumentType.getString(c, "message")); return 1; })));
            // /ss mod ... (merged into Admin's /ss; op only either way)
            d.register(Commands.literal("ss").requires(Mods::opOnly)
                    .then(Commands.literal("mod").requires(Mods::opOnly)
                            .executes(c -> { listMods(c.getSource()); return 1; })
                            .then(Commands.literal("list").executes(c -> { listMods(c.getSource()); return 1; }))
                            .then(Commands.literal("add").then(Commands.argument("player", StringArgumentType.word())
                                    .suggests((c, b) -> SharedSuggestionProvider.suggest(onlineNames(), b))
                                    .executes(c -> { setMod(c.getSource(), StringArgumentType.getString(c, "player"), true); return 1; })))
                            .then(Commands.literal("remove").then(Commands.argument("player", StringArgumentType.word())
                                    .suggests((c, b) -> SharedSuggestionProvider.suggest(MODS.values(), b))
                                    .executes(c -> { setMod(c.getSource(), StringArgumentType.getString(c, "player"), false); return 1; })))));
        });
    }

    private static LiteralArgumentBuilder<CommandSourceStack> modCommand() {
        LiteralArgumentBuilder<CommandSourceStack> m = Commands.literal("mod").requires(Mods::staff)
                .executes(c -> { help(c.getSource()); return 1; });
        m.then(Commands.literal("help").executes(c -> { help(c.getSource()); return 1; }));
        m.then(Commands.literal("fill")
                .then(Commands.literal("bw").executes(c -> fill(c.getSource(), "bw", -1))
                        .then(Commands.argument("count", IntegerArgumentType.integer(1, 12)).executes(c -> fill(c.getSource(), "bw", IntegerArgumentType.getInteger(c, "count")))))
                .then(Commands.literal("gw").executes(c -> fill(c.getSource(), "gw", -1)))
                .then(Commands.literal("sw").executes(c -> fill(c.getSource(), "sw", -1))));
        m.then(Commands.literal("skip").executes(c -> skipGoldWars(c.getSource())));
        m.then(Commands.literal("mute").then(Commands.argument("player", StringArgumentType.word()).suggests((c, b) -> SharedSuggestionProvider.suggest(onlineNames(), b))
                .then(Commands.argument("minutes", IntegerArgumentType.integer(1, MAX_MINUTES))
                        .executes(c -> mute(c.getSource(), StringArgumentType.getString(c, "player"), IntegerArgumentType.getInteger(c, "minutes"), "no reason given"))
                        .then(Commands.argument("reason", StringArgumentType.greedyString())
                                .executes(c -> mute(c.getSource(), StringArgumentType.getString(c, "player"), IntegerArgumentType.getInteger(c, "minutes"), StringArgumentType.getString(c, "reason")))))));
        m.then(Commands.literal("unmute").then(Commands.argument("player", StringArgumentType.word()).suggests((c, b) -> SharedSuggestionProvider.suggest(onlineNames(), b))
                .executes(c -> unmute(c.getSource(), StringArgumentType.getString(c, "player")))));
        m.then(Commands.literal("kick").then(Commands.argument("player", StringArgumentType.word()).suggests((c, b) -> SharedSuggestionProvider.suggest(onlineNames(), b))
                .executes(c -> kick(c.getSource(), StringArgumentType.getString(c, "player"), "no reason given"))
                .then(Commands.argument("reason", StringArgumentType.greedyString())
                        .executes(c -> kick(c.getSource(), StringArgumentType.getString(c, "player"), StringArgumentType.getString(c, "reason"))))));
        m.then(Commands.literal("ban").then(Commands.argument("player", StringArgumentType.word()).suggests((c, b) -> SharedSuggestionProvider.suggest(onlineNames(), b))
                .then(Commands.argument("minutes", IntegerArgumentType.integer(1, MAX_MINUTES))
                        .executes(c -> ban(c.getSource(), StringArgumentType.getString(c, "player"), IntegerArgumentType.getInteger(c, "minutes"), "no reason given"))
                        .then(Commands.argument("reason", StringArgumentType.greedyString())
                                .executes(c -> ban(c.getSource(), StringArgumentType.getString(c, "player"), IntegerArgumentType.getInteger(c, "minutes"), StringArgumentType.getString(c, "reason")))))));
        m.then(Commands.literal("unban").then(Commands.argument("player", StringArgumentType.word())
                .suggests((c, b) -> { List<String> n = new ArrayList<>(); for (Ban x : BANS.values()) n.add(x.name); return SharedSuggestionProvider.suggest(n, b); })
                .executes(c -> unban(c.getSource(), StringArgumentType.getString(c, "player")))));
        m.then(Commands.literal("spectate").executes(c -> spectate(c.getSource(), null))
                .then(Commands.argument("player", StringArgumentType.word()).suggests((c, b) -> SharedSuggestionProvider.suggest(onlineNames(), b))
                        .executes(c -> spectate(c.getSource(), StringArgumentType.getString(c, "player")))));
        m.then(Commands.literal("inv").then(Commands.argument("player", StringArgumentType.word()).suggests((c, b) -> SharedSuggestionProvider.suggest(onlineNames(), b))
                .executes(c -> inv(c.getSource(), StringArgumentType.getString(c, "player")))));
        m.then(Commands.literal("status").executes(c -> { Admin.status(c.getSource()); return 1; }));
        m.then(Commands.literal("log").then(Commands.argument("player", StringArgumentType.word())
                .executes(c -> { Admin.showLog(c.getSource(), StringArgumentType.getString(c, "player")); return 1; })));
        m.then(Commands.literal("backup").executes(c -> {
            Backups.start(c.getSource().getServer(), c.getSource().getPlayer());
            record(c.getSource(), "started a backup");
            return 1;
        }));
        m.then(Commands.literal("spleef").then(Commands.argument("minutes", IntegerArgumentType.integer(5, 240)).executes(c -> {
            int min = IntegerArgumentType.getInteger(c, "minutes");
            Events.startTournament(c.getSource().getServer(), min);
            record(c.getSource(), "started a " + min + "-minute Spleef Tournament");
            return 1;
        })));
        m.then(Commands.literal("endexpeditions").executes(c -> {
            int n = com.aidan.skyserver.expedition.Expedition.endAll("ended by a moderator");
            say(c.getSource(), "§aEnded " + n + " Nether Expedition" + (n == 1 ? "" : "s") + " §7(everyone kept what they carried).");
            if (n > 0) record(c.getSource(), "ended " + n + " Nether Expedition(s)");
            return 1;
        }));
        return m;
    }

    private static void help(CommandSourceStack s) {
        say(s, "§b§lMODERATOR COMMANDS");
        say(s, "§e/mod fill bw [count] §7| §e/mod fill gw §7| §e/mod fill sw §7- bots for the waiting game");
        say(s, "§e/mod skip §7- skip Gold Wars' countdown or build phase");
        say(s, "§e/mod mute <player> <minutes> [reason] §7| §e/mod unmute <player>");
        say(s, "§e/mod kick <player> [reason]");
        say(s, "§e/mod ban <player> <minutes> [reason] §7(max " + (MAX_MINUTES / 60) + " h) | §e/mod unban <player>");
        say(s, "§e/mod spectate <player> §7(from the lobby) | §e/mod spectate §7to come back");
        say(s, "§e/mod inv <player> §7- look at their inventory");
        say(s, "§e/mod status §7| §e/mod log <player> §7| §e/mod backup");
        say(s, "§e/mod spleef <minutes> §7- start a Spleef Tournament | §e/mod endexpeditions §7- end stuck expeditions");
        say(s, "§e/sc <message> §7- staff chat");
    }

    // =====================================================================
    // Bots and Gold Wars skip (the games keep these private: reached by reflection)
    // =====================================================================

    private static int fill(CommandSourceStack s, String game, int count) {
        try {
            switch (game) {
                case "bw" -> {
                    Method m = com.aidan.skyserver.bedwars.BedwarsGame.class.getDeclaredMethod("fillCommand", CommandSourceStack.class, int.class);
                    m.setAccessible(true);
                    int r = (int) m.invoke(null, s, count);
                    if (r > 0) record(s, "filled Bedwars with bots");
                    return r;
                }
                case "gw", "sw" -> {
                    Class<?> cl = game.equals("gw") ? com.aidan.skyserver.goldwars.GoldWarsGame.class : com.aidan.skyserver.skywars.SkywarsGame.class;
                    String st = stateName(cl);
                    Method real = cl.getDeclaredMethod("realCount");
                    real.setAccessible(true);
                    if ((!st.equals("WAITING") && !st.equals("COUNTDOWN")) || (int) real.invoke(null) == 0) {
                        s.sendFailure(Component.literal("Someone has to be waiting, and only before a match starts."));
                        return 0;
                    }
                    Method fill = cl.getDeclaredMethod("fillBots");
                    fill.setAccessible(true);
                    fill.invoke(null);
                    record(s, "filled " + (game.equals("gw") ? "Gold Wars" : "Skywars") + " with bots");
                    return 1;
                }
                default -> {
                    return 0;
                }
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            Skyserver.LOGGER.warn("/mod fill {} failed: {}", game, e.toString());
            s.sendFailure(Component.literal("Couldn't fill bots (" + e.getClass().getSimpleName() + ") - tell Aidan."));
            return 0;
        }
    }

    private static String stateName(Class<?> cl) throws ReflectiveOperationException {
        Field f = cl.getDeclaredField("state");
        f.setAccessible(true);
        return ((Enum<?>) f.get(null)).name();
    }

    /** Gold Wars: countdown -> start now; build phase -> drop the wall now. Never ends a match. */
    private static int skipGoldWars(CommandSourceStack s) {
        try {
            Class<?> cl = com.aidan.skyserver.goldwars.GoldWarsGame.class;
            String st = stateName(cl);
            if (st.equals("COUNTDOWN")) {
                Method start = cl.getDeclaredMethod("start");
                start.setAccessible(true);
                start.invoke(null);
                record(s, "skipped the Gold Wars countdown");
                return 1;
            }
            if (st.equals("BUILD")) {
                Field timer = cl.getDeclaredField("timer");
                timer.setAccessible(true);
                timer.setInt(null, 1);
                record(s, "skipped the Gold Wars build phase");
                say(s, "§aSkipping the build phase - the wall drops now.");
                return 1;
            }
            s.sendFailure(Component.literal("Nothing to skip: /mod skip only works in Gold Wars' countdown or build phase."));
            return 0;
        } catch (ReflectiveOperationException | RuntimeException e) {
            Skyserver.LOGGER.warn("/mod skip failed: {}", e.toString());
            s.sendFailure(Component.literal("Couldn't skip (" + e.getClass().getSimpleName() + ") - tell Aidan."));
            return 0;
        }
    }

    // =====================================================================
    // Mute, kick, ban
    // =====================================================================

    /** A staff member may act on this player? (Mods can't act on ops or other mods.) */
    private static boolean canTarget(CommandSourceStack s, UUID target, String name) {
        ServerPlayer actor = s.getPlayer();
        if (actor == null || isOp(actor)) return true;
        ServerPlayer t = server.getPlayerList().getPlayer(target);
        boolean protectedTarget = isMod(target) || (t != null && isOp(t)) || (t == null && isOpOffline(target, name));
        if (protectedTarget) {
            s.sendFailure(Component.literal("Mods can't do that to ops or other mods."));
            return false;
        }
        if (actor.getUUID().equals(target)) {
            s.sendFailure(Component.literal("That's you!"));
            return false;
        }
        return true;
    }

    private static boolean isOpOffline(UUID id, String name) {
        try {
            return server.getPlayerList().isOp(new NameAndId(id, name));
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static int mute(CommandSourceStack s, String name, int minutes, String reason) {
        UUID id = find(s, name);
        if (id == null || !canTarget(s, id, name)) return 0;
        ChatFilter.muteFor(id, minutes * 60_000L);
        ServerPlayer t = server.getPlayerList().getPlayer(id);
        if (t != null) Lobby.msg(t, "§cA moderator muted you for " + left(minutes * 60_000L) + ". §7Reason: §f" + reason);
        say(s, "§aMuted " + name + " for " + left(minutes * 60_000L) + ".");
        record(s, "muted " + name + " for " + minutes + " min (" + reason + ")");
        return 1;
    }

    private static int unmute(CommandSourceStack s, String name) {
        UUID id = find(s, name);
        if (id == null) return 0;
        boolean was = ChatFilter.unmute(id);
        say(s, was ? "§aUnmuted " + name + "." : "§7" + name + " wasn't muted.");
        if (was) record(s, "unmuted " + name);
        return 1;
    }

    private static int kick(CommandSourceStack s, String name, String reason) {
        ServerPlayer t = server.getPlayerList().getPlayerByName(name);
        if (t == null || t instanceof com.aidan.skyserver.bots.BotPlayer) {
            s.sendFailure(Component.literal(name + " isn't online."));
            return 0;
        }
        if (!canTarget(s, t.getUUID(), name)) return 0;
        t.connection.disconnect(Component.literal("§cYou were kicked by a moderator.\n§7Reason: §f" + reason));
        say(s, "§aKicked " + name + ".");
        record(s, "kicked " + name + " (" + reason + ")");
        return 1;
    }

    private static int ban(CommandSourceStack s, String name, int minutes, String reason) {
        UUID id = find(s, name);
        if (id == null || !canTarget(s, id, name)) return 0;
        long until = System.currentTimeMillis() + minutes * 60_000L;
        BANS.put(id, new Ban(name, until, reason, s.getTextName()));
        save();
        ServerPlayer t = server.getPlayerList().getPlayer(id);
        if (t != null) t.connection.disconnect(Component.literal("§cYou're banned for " + left(minutes * 60_000L) + ".\n§7Reason: §f" + reason));
        say(s, "§aBanned " + name + " for " + left(minutes * 60_000L) + ".");
        record(s, "banned " + name + " for " + minutes + " min (" + reason + ")");
        return 1;
    }

    private static int unban(CommandSourceStack s, String name) {
        UUID id = null;
        for (Map.Entry<UUID, Ban> e : BANS.entrySet()) if (e.getValue().name.equalsIgnoreCase(name)) id = e.getKey();
        if (id == null) id = find(s, name);
        if (id == null || BANS.remove(id) == null) {
            say(s, "§7" + name + " isn't banned.");
            return 0;
        }
        save();
        say(s, "§aUnbanned " + name + ".");
        record(s, "unbanned " + name);
        return 1;
    }

    private static Ban activeBan(UUID id) {
        load();
        Ban b = BANS.get(id);
        if (b == null) return null;
        if (b.until <= System.currentTimeMillis()) {
            BANS.remove(id);
            save();
            return null;
        }
        return b;
    }

    // =====================================================================
    // Spectate and look at inventories
    // =====================================================================

    private static int spectate(CommandSourceStack s, String name) {
        ServerPlayer p = s.getPlayer();
        if (p == null) return 0;
        if (name == null) {
            if (!SPECTATING.containsKey(p.getUUID())) {
                say(s, "§7Use §e/mod spectate <player> §7from the lobby to watch someone.");
                return 0;
            }
            stopSpectating(p, true);
            return 1;
        }
        ServerPlayer t = server.getPlayerList().getPlayerByName(name);
        if (t == null || t == p) {
            s.sendFailure(Component.literal(name + " isn't online."));
            return 0;
        }
        if (!Lobby.isGameLevel(t.level())) {
            s.sendFailure(Component.literal("You can only spectate players in Bedwars, Gold Wars or Skywars."));
            return 0;
        }
        if (!SPECTATING.containsKey(p.getUUID())) {
            if (!Lobby.isLobby(p.level())) {
                s.sendFailure(Component.literal("Start spectating from the lobby (/lobby)."));
                return 0;
            }
            Lobby.leaveLobbyStuff(p);
            SPECTATING.put(p.getUUID(), new double[]{p.getX(), p.getY(), p.getZ(), p.getYRot(), p.getXRot()});
            p.getInventory().clearContent();
            p.setGameMode(GameType.SPECTATOR);
        }
        p.teleportTo((ServerLevel) t.level(), t.getX(), t.getY() + 1, t.getZ(), Set.of(), t.getYRot(), t.getXRot(), true);
        say(s, "§bSpectating " + t.getName().getString() + ". §7/mod spectate §7to come back to the lobby.");
        record(s, "started spectating " + t.getName().getString());
        return 1;
    }

    private static void stopSpectating(ServerPlayer p, boolean tell) {
        double[] at = SPECTATING.remove(p.getUUID());
        ServerLevel lobby = Lobby.level();
        if (lobby == null) return;
        if (at == null) at = new double[]{p.getX(), p.getY(), p.getZ(), 0, 0};
        p.teleportTo(lobby, at[0], at[1], at[2], Set.of(), (float) at[3], (float) at[4], true);
        p.setGameMode(GameType.ADVENTURE);
        p.resetFallDistance();
        if (tell) {
            Lobby.refreshKit(p);
            Lobby.msg(p, "§7Back in the lobby.");
        }
    }

    private static int inv(CommandSourceStack s, String name) {
        ServerPlayer viewer = s.getPlayer();
        ServerPlayer t = server.getPlayerList().getPlayerByName(name);
        if (viewer == null || t == null) {
            s.sendFailure(Component.literal(name + " isn't online."));
            return 0;
        }
        LockedMenu.open(viewer, "§8" + t.getName().getString() + "'s inventory (look only)", 5, (id, inv, box, v) -> new InvView(id, inv, box, v, t));
        record(s, "looked at " + t.getName().getString() + "'s inventory");
        return 1;
    }

    /** Look-only copy: rows 1-3 the backpack, row 4 the hotbar, row 5 armor and off hand. */
    static final class InvView extends LockedMenu {
        InvView(int id, Inventory inv, SimpleContainer box, ServerPlayer viewer, ServerPlayer target) {
            super(id, inv, box, 5, viewer);
            Inventory ti = target.getInventory();
            for (int i = 9; i < 36; i++) box.setItem(i - 9, ti.getItem(i).copy());
            for (int i = 0; i < 9; i++) box.setItem(27 + i, ti.getItem(i).copy());
            EquipmentSlot[] eq = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET, EquipmentSlot.OFFHAND};
            for (int i = 0; i < eq.length; i++) box.setItem(36 + i, target.getItemBySlot(eq[i]).copy());
            broadcastChanges();
        }
    }

    // =====================================================================
    // Staff chat, the mod list, the action log
    // =====================================================================

    private static void staffChat(CommandSourceStack s, String text) {
        ServerPlayer p = s.getPlayer();
        if (p != null && !ChatFilter.allow(p, text, "chat")) return;
        Component line = Component.literal("§b[STAFF] §f" + s.getTextName() + "§7: §f" + text.replace("§", ""));
        for (ServerPlayer o : onlineStaff()) o.sendSystemMessage(line);
        if (p != null && !onlineStaff().contains(p)) p.sendSystemMessage(line);
        Skyserver.LOGGER.info("[STAFF] {}: {}", s.getTextName(), text);
    }

    private static void listMods(CommandSourceStack s) {
        load();
        say(s, "§b§lMODERATORS §7(" + MODS.size() + ")" + (MODS.isEmpty() ? " §8- none yet: /ss mod add <player>" : ""));
        for (Map.Entry<UUID, String> e : MODS.entrySet()) {
            boolean on = server != null && server.getPlayerList().getPlayer(e.getKey()) != null;
            say(s, " §f" + e.getValue() + (on ? " §a(online)" : " §8(offline)"));
        }
    }

    private static void setMod(CommandSourceStack s, String name, boolean add) {
        load();
        UUID id = find(s, name);
        if (id == null) return;
        ServerPlayer t = server.getPlayerList().getPlayer(id);
        String real = t != null ? t.getName().getString() : name;
        if (add) {
            if (MODS.containsKey(id)) {
                say(s, "§7" + real + " is already a moderator.");
                return;
            }
            MODS.put(id, real);
            say(s, "§a" + real + " is now a moderator.");
            if (t != null) Lobby.msg(t, "§b§lYou're now a SkyServer moderator! §7Type §e/mod §7for your commands and §e/sc §7for staff chat.");
        } else {
            if (MODS.remove(id) == null) {
                say(s, "§7" + real + " isn't a moderator.");
                return;
            }
            say(s, "§e" + real + " is no longer a moderator.");
            if (t != null) {
                Lobby.msg(t, "§7You're no longer a moderator.");
                if (SPECTATING.containsKey(t.getUUID())) stopSpectating(t, true);
            }
        }
        save();
        if (t != null) server.getCommands().sendCommands(t); // their command list changes right away
        Skyserver.LOGGER.info("[Mods] {} {} {}", s.getTextName(), add ? "made a moderator:" : "removed moderator", real);
    }

    /** Writes a mod action to the log file and tells online ops. */
    private static void record(CommandSourceStack s, String what) {
        ServerPlayer actor = s.getPlayer();
        if (actor != null && isOp(actor)) return; // only mods' actions are reported
        String line = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")) + " " + s.getTextName() + " " + what;
        PlayerCache.io(() -> {
            try {
                Path f = Path.of("config", "skyserver", "logs", "mod-actions.log");
                Files.createDirectories(f.getParent());
                Files.writeString(f, line + System.lineSeparator(), StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (Exception e) {
                Skyserver.LOGGER.warn("Couldn't write the mod log: {}", e.toString());
            }
        });
        Skyserver.LOGGER.info("[Mod] {}", line);
        if (server == null) return;
        for (ServerPlayer o : server.getPlayerList().getPlayers())
            if (o != actor && isOp(o)) Lobby.msg(o, "§b[Mod] §f" + s.getTextName() + " §7" + what);
    }

    // =====================================================================
    // Helpers and saving
    // =====================================================================

    private static void say(CommandSourceStack s, String text) {
        s.sendSuccess(() -> Component.literal(text), false);
    }

    private static List<String> onlineNames() {
        List<String> n = new ArrayList<>();
        if (server == null) return n;
        for (ServerPlayer p : server.getPlayerList().getPlayers()) if (!(p instanceof com.aidan.skyserver.bots.BotPlayer)) n.add(p.getName().getString());
        return n;
    }

    /** A player's id from their name: online first, then the server's name cache. */
    private static UUID find(CommandSourceStack s, String name) {
        ServerPlayer p = s.getServer().getPlayerList().getPlayerByName(name);
        if (p != null) return p.getUUID();
        try {
            Optional<NameAndId> n = s.getServer().services().nameToIdCache().get(name);
            if (n.isPresent()) return n.get().id();
        } catch (RuntimeException ignored) {
        }
        s.sendFailure(Component.literal("No player called " + name + " has been on this server."));
        return null;
    }

    private static String left(long ms) {
        long m = Math.max(1, (ms + 59_999) / 60_000);
        return m >= 120 ? (m / 60) + " hours" : m + " minute" + (m == 1 ? "" : "s");
    }

    private static void load() {
        if (loaded) return;
        loaded = true;
        JsonObject root = Stacks.readFile("mods.json");
        if (root.has("mods")) for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("mods").entrySet()) {
            try {
                MODS.put(UUID.fromString(e.getKey()), e.getValue().getAsString());
            } catch (RuntimeException ignored) {
            }
        }
        if (root.has("bans")) for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("bans").entrySet()) {
            try {
                JsonObject b = e.getValue().getAsJsonObject();
                BANS.put(UUID.fromString(e.getKey()), new Ban(b.get("name").getAsString(), b.get("until").getAsLong(),
                        b.has("reason") ? b.get("reason").getAsString() : "", b.has("by") ? b.get("by").getAsString() : "?"));
            } catch (RuntimeException ignored) {
            }
        }
    }

    private static void save() {
        JsonObject root = new JsonObject();
        JsonObject mods = new JsonObject();
        MODS.forEach((k, v) -> mods.addProperty(k.toString(), v));
        root.add("mods", mods);
        JsonObject bans = new JsonObject();
        long now = System.currentTimeMillis();
        BANS.forEach((k, v) -> {
            if (v.until <= now) return;
            JsonObject b = new JsonObject();
            b.addProperty("name", v.name);
            b.addProperty("until", v.until);
            b.addProperty("reason", v.reason);
            b.addProperty("by", v.by);
            bans.add(k.toString(), b);
        });
        root.add("bans", bans);
        PlayerCache.io(() -> Stacks.writeFile("mods.json", root));
    }
}
