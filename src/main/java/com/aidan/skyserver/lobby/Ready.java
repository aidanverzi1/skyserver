package com.aidan.skyserver.lobby;

import com.aidan.skyserver.Skyserver;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;

/**
 * 5.13: "play now with bots". Anyone waiting in the Bedwars, Gold Wars or Skywars queue can type
 * /ready (or click the line in chat). When EVERY real player in that queue is ready, bots fill the
 * empty slots straight away and the countdown starts. Otherwise bots still fill on their own after
 * BotManager.FILL_AFTER_SECONDS (120 s). The games keep their bot code private, so this reaches it
 * by reflection (like /mod fill does).
 */
public final class Ready {
    private Ready() {}

    private enum Game {
        BEDWARS("Bedwars", com.aidan.skyserver.bedwars.BedwarsGame.class),
        GOLDWARS("Gold Wars", com.aidan.skyserver.goldwars.GoldWarsGame.class),
        SKYWARS("Skywars", com.aidan.skyserver.skywars.SkywarsGame.class);

        final String display;
        final Class<?> cls;

        Game(String display, Class<?> cls) {
            this.display = display;
            this.cls = cls;
        }

        boolean has(UUID id) {
            return switch (this) {
                case BEDWARS -> com.aidan.skyserver.bedwars.BedwarsGame.isInGame(id);
                case GOLDWARS -> com.aidan.skyserver.goldwars.GoldWarsGame.isInGame(id);
                case SKYWARS -> com.aidan.skyserver.skywars.SkywarsGame.isInGame(id);
            };
        }
    }

    private static final Map<Game, Set<UUID>> VOTES = new EnumMap<>(Game.class);
    private static final Set<UUID> TOLD = new HashSet<>();

    public static void init() {
        for (Game g : Game.values()) VOTES.put(g, new HashSet<>());
        CommandRegistrationCallback.EVENT.register((d, r, e) -> d.register(Commands.literal("ready").executes(c -> {
            ready(c.getSource().getPlayerOrException());
            return 1;
        })));
        ServerTickEvents.END_SERVER_TICK.register(s -> {
            if (s.getTickCount() % 20 == 5) com.aidan.skyserver.Safe.run("ready", () -> tick(s));
        });
    }

    private static Game gameOf(UUID id) {
        for (Game g : Game.values()) if (g.has(id)) return g;
        return null;
    }

    private static String state(Game g) {
        try {
            Field f = g.cls.getDeclaredField("state");
            f.setAccessible(true);
            Object v = f.get(null);
            return v == null ? "" : ((Enum<?>) v).name();
        } catch (ReflectiveOperationException | RuntimeException e) {
            return "";
        }
    }

    private static int bots(Game g) {
        try {
            Method m = g.cls.getDeclaredMethod("botCount");
            m.setAccessible(true);
            return (int) m.invoke(null);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return 0;
        }
    }

    private static boolean waiting(Game g) {
        String st = state(g);
        return (st.equals("WAITING") || st.equals("COUNTDOWN")) && bots(g) == 0;
    }

    private static List<ServerPlayer> queue(MinecraftServer s, Game g) {
        List<ServerPlayer> out = new ArrayList<>();
        for (ServerPlayer p : s.getPlayerList().getPlayers())
            if (!(p instanceof com.aidan.skyserver.bots.BotPlayer) && g.has(p.getUUID())) out.add(p);
        return out;
    }

    static void ready(ServerPlayer p) {
        Game g = gameOf(p.getUUID());
        if (g == null) {
            Lobby.msg(p, "§7Join a Bedwars, Gold Wars or Skywars queue first, then §e/ready§7.");
            return;
        }
        if (!waiting(g)) {
            Lobby.msg(p, "§7Bots are already in, or the match has started.");
            return;
        }
        MinecraftServer s = p.level().getServer();
        Set<UUID> votes = VOTES.get(g);
        votes.add(p.getUUID());
        List<ServerPlayer> q = queue(s, g);
        votes.removeIf(id -> q.stream().noneMatch(x -> x.getUUID().equals(id)));
        if (votes.size() >= q.size()) {
            fill(g, q);
            return;
        }
        for (ServerPlayer o : q)
            Lobby.msg(o, "§e" + p.getName().getString() + " §7is ready to play with bots §8(" + votes.size() + "/" + q.size() + "). §7Type §e/ready §7too.");
    }

    private static void fill(Game g, List<ServerPlayer> q) {
        VOTES.get(g).clear();
        try {
            Method m = g.cls.getDeclaredMethod("fillBots");
            m.setAccessible(true);
            m.invoke(null);
            for (ServerPlayer o : q) Lobby.msg(o, "§aEveryone's ready - bots are filling the empty slots!");
        } catch (ReflectiveOperationException | RuntimeException e) {
            Skyserver.LOGGER.warn("/ready couldn't fill {} with bots: {}", g.display, e.toString());
            for (ServerPlayer o : q) Lobby.msg(o, "§cCouldn't add bots right now - they'll fill in on their own soon.");
        }
    }

    /** Tells each player once per queue that they can start now; forgets votes of people who left. */
    private static void tick(MinecraftServer s) {
        for (Game g : Game.values()) VOTES.get(g).removeIf(id -> !g.has(id));
        for (ServerPlayer p : s.getPlayerList().getPlayers()) {
            if (p instanceof com.aidan.skyserver.bots.BotPlayer) continue;
            Game g = gameOf(p.getUUID());
            if (g == null) {
                TOLD.remove(p.getUUID());
                continue;
            }
            if (TOLD.contains(p.getUUID()) || !waiting(g)) continue;
            TOLD.add(p.getUUID());
            p.sendSystemMessage(Component.literal("§7Don't want to wait for more players? ")
                    .append(BattlePass.button("§a§l[Play now with bots]", "/ready", "Everyone in the queue has to be ready")));
        }
    }
}
