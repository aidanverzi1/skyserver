package com.aidan.skyserver.lobby;

import com.aidan.skyserver.bedwars.BedwarsGame;
import com.aidan.skyserver.goldwars.GoldWarsGame;
import com.aidan.skyserver.goldwars.GwClass;
import com.aidan.skyserver.skywars.SkywarsGame;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.Commands;
import net.minecraft.server.level.ServerPlayer;

/**
 * 5.19: short commands. Every old long command still works - these just run it for you, so all the
 * usual rules (no /lobby during a Nether trip, join checks, etc.) still apply.
 *
 *   /builder /knight /archer /mage /scout /lb   = /gw class <class>  (/lb is the Longbow class: /longbow is the admin give)
 *   /class                                       = /gw class (the class menu)
 *   /gw  /bw  /sw                                = /gw join  /bw join  /sw join
 *   /leave                                       = leave whatever game you're in (Gold Wars, Skywars, Bedwars, Spleef)
 *   /l                                           = /lobby (as well as /hub and /spawn, which already worked)
 *   /challenges                                  = /pass challenges
 *   /stats                                       = /bw stats
 *   /exp                                         = /expedition
 */
public final class Shortcuts {
    private Shortcuts() {}

    public static void init() {
        CommandRegistrationCallback.EVENT.register((d, r, e) -> {
            for (GwClass cls : GwClass.values()) {
                String name = cls == GwClass.LONGBOW ? "lb" : cls.id;
                d.register(Commands.literal(name).executes(c -> run(c.getSource().getPlayerOrException(), "gw class " + cls.id)));
            }
            d.register(Commands.literal("class").executes(c -> run(c.getSource().getPlayerOrException(), "gw class")));
            // A bare /gw, /bw or /sw joins (their sub-commands are untouched: Brigadier merges the nodes).
            d.register(Commands.literal("gw").executes(c -> run(c.getSource().getPlayerOrException(), "gw join")));
            d.register(Commands.literal("bw").executes(c -> run(c.getSource().getPlayerOrException(), "bw join"))
                    .then(Commands.literal("help").executes(c -> {
                        ServerPlayer p = c.getSource().getPlayerOrException();
                        Lobby.msg(p, "§c§lBEDWARS");
                        Lobby.msg(p, "§e/bw §7or §e/bw join [trios|duos] §7- join the next match   §e/leave §7- leave");
                        Lobby.msg(p, "§e/stats §7- your Bedwars stats   §e/bw vote <map> §7- vote for a map");
                        return 1;
                    })));
            d.register(Commands.literal("sw").executes(c -> run(c.getSource().getPlayerOrException(), "sw join")));
            d.register(Commands.literal("leave").executes(c -> { leave(c.getSource().getPlayerOrException()); return 1; }));
            d.register(Commands.literal("l").executes(c -> run(c.getSource().getPlayerOrException(), "lobby")));
            d.register(Commands.literal("challenges").executes(c -> run(c.getSource().getPlayerOrException(), "pass challenges")));
            d.register(Commands.literal("stats").executes(c -> run(c.getSource().getPlayerOrException(), "bw stats")));
            d.register(Commands.literal("exp").executes(c -> run(c.getSource().getPlayerOrException(), "expedition")));
        });
    }

    private static int run(ServerPlayer p, String command) {
        p.level().getServer().getCommands().performPrefixedCommand(p.createCommandSourceStack(), command);
        return 1;
    }

    /** /leave: whichever game you're in. */
    private static void leave(ServerPlayer p) {
        if (GoldWarsGame.isInGame(p.getUUID())) run(p, "gw leave");
        else if (SkywarsGame.isInGame(p.getUUID())) run(p, "sw leave");
        else if (BedwarsGame.isInGame(p.getUUID())) run(p, "bw leave");
        else if (Duels.inDuel(p.getUUID())) Lobby.msg(p, "§cFinish your duel first.");
        else if (Activities.busy(p)) {
            Activities.leaveAll(p);
            Lobby.msg(p, "§7You left.");
        } else Lobby.msg(p, "§7You're not in a game. §8(/lobby takes you back to the lobby.)");
    }
}
