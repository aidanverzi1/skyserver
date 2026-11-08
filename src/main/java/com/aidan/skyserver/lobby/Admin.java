package com.aidan.skyserver.lobby;

import com.aidan.skyserver.island.Islands;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Admin tools (ops only), all under /ss:
 *  /ss status                        - server speed, players, backups, any errors SkyServer caught
 *  /ss reload                        - re-read events.json and backups.json
 *  /ss backup                        - make a backup right now
 *  /ss log <player>                  - their last 15 money/key changes
 *  /ss unmute <player>               - lift a chat mute
 *  /ss resetskills <player> confirm  - all their skills back to level 1
 *  /ss resetisland <player> confirm  - they lose their island and get a fresh one next /is
 *  /ss event doublexp <hours>        - start double XP now
 *  /ss event spleef <minutes>        - start a Spleef Tournament now
 *  /ss mod add|remove|list           - 5.9: moderators (see Mods; mods use /mod)
 */
public final class Admin {
    private Admin() {}

    public static void init() {
        CommandRegistrationCallback.EVENT.register((d, r, e) -> d.register(Commands.literal("ss")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .executes(c -> { help(c.getSource()); return 1; })
                .then(Commands.literal("status").executes(c -> { status(c.getSource()); return 1; }))
                .then(Commands.literal("reload").executes(c -> {
                    Events.reload();
                    Backups.reload();
                    say(c.getSource(), "§aReloaded events.json and backups.json.");
                    return 1;
                }))
                .then(Commands.literal("backup").executes(c -> {
                    Backups.start(c.getSource().getServer(), c.getSource().getPlayer());
                    if (c.getSource().getPlayer() == null) say(c.getSource(), "Backup started.");
                    return 1;
                }))
                .then(Commands.literal("unmute").then(Commands.argument("player", StringArgumentType.word()).executes(c -> {
                    UUID id = find(c.getSource(), StringArgumentType.getString(c, "player"));
                    if (id == null) return 0;
                    say(c.getSource(), ChatFilter.unmute(id) ? "§aUnmuted." : "§7They weren't muted.");
                    return 1;
                })))
                .then(Commands.literal("log").then(Commands.argument("player", StringArgumentType.word()).executes(Admin::log)))
                .then(Commands.literal("resetskills").then(Commands.argument("player", StringArgumentType.word())
                        .executes(c -> { say(c.getSource(), "§cThis sets ALL their skills back to level 1. Add §fconfirm §cto the end to do it."); return 1; })
                        .then(Commands.literal("confirm").executes(c -> resetSkills(c)))))
                .then(Commands.literal("resetisland").then(Commands.argument("player", StringArgumentType.word())
                        .executes(c -> { say(c.getSource(), "§cThis takes away their island (members too). They get a fresh one with /is. Add §fconfirm §cto the end to do it."); return 1; })
                        .then(Commands.literal("confirm").executes(c -> resetIsland(c)))))
                .then(Commands.literal("event")
                        .then(Commands.literal("doublexp").then(Commands.argument("hours", IntegerArgumentType.integer(1, 72))
                                .executes(c -> { Events.startDoubleXp(c.getSource().getServer(), IntegerArgumentType.getInteger(c, "hours")); return 1; })))
                        .then(Commands.literal("spleef").then(Commands.argument("minutes", IntegerArgumentType.integer(5, 240))
                                .executes(c -> { Events.startTournament(c.getSource().getServer(), IntegerArgumentType.getInteger(c, "minutes")); return 1; }))))));
    }

    private static void say(CommandSourceStack src, String text) {
        src.sendSuccess(() -> Component.literal(text), false);
    }

    private static void help(CommandSourceStack s) {
        say(s, "§6§lSkyServer admin");
        say(s, "§e/ss status §7- speed, backups, errors");
        say(s, "§e/ss reload §7- re-read events.json / backups.json");
        say(s, "§e/ss backup §7- back up now");
        say(s, "§e/ss log <player> §7- money & key history");
        say(s, "§e/ss unmute <player>");
        say(s, "§e/ss resetskills <player> §7| §e/ss resetisland <player>");
        say(s, "§e/ss event doublexp <hours> §7| §e/ss event spleef <minutes>");
        say(s, "§e/ss mod add <player> §7| §e/ss mod remove <player> §7| §e/ss mod list §7- moderators (they use §e/mod§7)");
    }

    /** /ss status and /mod status. */
    static void status(CommandSourceStack s) {
        MinecraftServer server = s.getServer();
        double mspt = server.getAverageTickTimeNanos() / 1_000_000.0;
        double tps = Math.min(20, 1000.0 / Math.max(50, mspt));
        int real = 0, bots = 0;
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (p instanceof com.aidan.skyserver.bots.BotPlayer) bots++;
            else real++;
        }
        Runtime rt = Runtime.getRuntime();
        long used = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024), max = rt.maxMemory() / (1024 * 1024);
        say(s, "§6§lSkyServer status");
        say(s, String.format(" §fTPS: %s%.1f §7(%.1f ms per tick, under 50 is good)", tps >= 19 ? "§a" : tps >= 15 ? "§e" : "§c", tps, mspt));
        say(s, " §fPlayers: §a" + real + " §7+ " + bots + " bots §8| §fMemory: §e" + used + "§7/" + max + " MB");
        say(s, " §fBackups: §e" + Backups.status());
        say(s, " §fEvents: §e" + (Events.doubleXp() ? "double XP on" : "double XP off") + (Events.tournamentOn() ? ", tournament running" : ""));
        Map<String, long[]> errs = com.aidan.skyserver.Safe.errors();
        if (errs.isEmpty()) say(s, " §fErrors caught: §anone");
        else errs.forEach((k, v) -> say(s, " §cErrors in '" + k + "': " + v[0] + " §7(details in logs/latest.log)"));
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
        say(s, "§cNo player called " + name + " has been on this server.");
        return null;
    }

    private static int log(CommandContext<CommandSourceStack> c) {
        return showLog(c.getSource(), StringArgumentType.getString(c, "player"));
    }

    /** /ss log and /mod log: the last 15 money/key changes. */
    static int showLog(CommandSourceStack src, String name) {
        UUID id = find(src, name);
        if (id == null) return 0;
        var lines = TxLog.recent(id, 15);
        say(src, "§6Money & keys for " + name + " §7(since the server started; full history in config/skyserver/logs)");
        if (lines.isEmpty()) say(src, " §7nothing yet");
        for (String l : lines) say(src, " §7" + l);
        say(src, " §fNow: §a" + Economy.format(Economy.balance(src.getServer(), id)) + " §7cash, §a"
                + Economy.format(Bank.balance(id)) + " §7bank, keys " + Keys.summary(id));
        return 1;
    }

    private static int resetSkills(CommandContext<CommandSourceStack> c) {
        String name = StringArgumentType.getString(c, "player");
        UUID id = find(c.getSource(), name);
        if (id == null) return 0;
        Skills.adminReset(id);
        say(c.getSource(), "§aReset all skills for " + name + ".");
        com.aidan.skyserver.Skyserver.LOGGER.info("[SkyServer] {} reset the skills of {}", c.getSource().getTextName(), name);
        return 1;
    }

    private static int resetIsland(CommandContext<CommandSourceStack> c) {
        String name = StringArgumentType.getString(c, "player");
        UUID id = find(c.getSource(), name);
        if (id == null) return 0;
        String r = Islands.adminReset(id);
        say(c.getSource(), r);
        com.aidan.skyserver.Skyserver.LOGGER.info("[SkyServer] {} reset the island of {}: {}", c.getSource().getTextName(), name, r);
        return 1;
    }
}
