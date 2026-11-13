package com.aidan.skyserver.lobby;

import com.aidan.skyserver.Safe;
import com.aidan.skyserver.bots.BotManager;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.GameType;

import java.util.*;

/**
 * 5.19: kill streak bounties in Bedwars, Skywars and Gold Wars.
 *
 *  - Every kill adds 1 to your streak (a bot you kill adds half). Dying ends it.
 *  - At a streak of 4 you glow for everyone and carry a bounty: $150, +$25 for each kill after that
 *    (+$12.50 for a bot). The whole match is told in chat.
 *  - Whoever kills you (whoever did the most damage) collects the bounty. Bots can carry a bounty too,
 *    but a bot that ends a streak collects nothing.
 *  - Leaving the game's world (the match ends, /leave, logging out) clears your streak.
 *
 * The games already ask DamageCredit for kill shares whenever someone dies; that's where this hooks in
 * (DamageCredit.shares -> KillStreaks.died), so no game file needed changing.
 */
public final class KillStreaks {
    private KillStreaks() {}

    /** The streak that starts a bounty, the bounty then, and what each further kill adds. */
    public static final double BOUNTY_STREAK = 4.0;
    public static final long BOUNTY_START = 150, BOUNTY_STEP = 25;
    /** A bot victim counts this much of a kill. */
    public static final double BOT_KILL = 0.5;

    private static final class Streak {
        double kills;
        String level;
        boolean announced;
    }

    private static final Map<UUID, Streak> STREAKS = new HashMap<>();
    private static MinecraftServer server;

    public static void init() {
        ServerLifecycleEvents.SERVER_STARTED.register(s -> server = s);
        ServerTickEvents.END_SERVER_TICK.register(s -> {
            if (s.getTickCount() % 20 == 0 && !STREAKS.isEmpty()) Safe.run("kill streaks", () -> second(s));
        });
    }

    /** The bounty on a streak of this size (0 = none yet). */
    public static long bounty(double kills) {
        if (kills < BOUNTY_STREAK) return 0;
        return BOUNTY_START + Math.round(BOUNTY_STEP * (kills - BOUNTY_STREAK));
    }

    /** Someone died in Bedwars, Skywars or Gold Wars. killer = whoever did the most damage, or null. */
    public static void died(UUID victim, UUID killer) {
        if (server == null) return;
        Safe.run("kill streak death", () -> handle(victim, killer));
    }

    private static void handle(UUID victimId, UUID killerId) {
        ServerPlayer victim = server.getPlayerList().getPlayer(victimId);
        Streak vs = STREAKS.remove(victimId);
        long bounty = vs == null ? 0 : bounty(vs.kills);
        if (victim != null && vs != null && vs.kills >= BOUNTY_STREAK) victim.setGlowingTag(false);
        ServerPlayer killer = killerId == null ? null : server.getPlayerList().getPlayer(killerId);
        String vName = victim != null ? victim.getName().getString() : "someone";
        // The bounty is collected.
        if (bounty > 0) {
            if (killer != null && !killerId.equals(victimId)) {
                boolean bot = BotManager.isBot(killerId);
                if (!bot) {
                    Economy.add(server, killerId, killer.getName().getString(), bounty);
                    killer.level().playSound(null, killer.getX(), killer.getY(), killer.getZ(), SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 1f, 1.2f);
                }
                tell(victim != null ? victim : killer, "§6§l☠ BOUNTY! §e" + killer.getName().getString() + " §fended §e" + vName
                        + "§f's streak of " + fmt(vs.kills) + (bot ? "." : " §fand collects §6" + Economy.format(bounty) + "§f!"));
            } else {
                tell(victim, "§6§l☠ §e" + vName + "§7's streak of " + fmt(vs.kills) + " ended - nobody collects the §6" + Economy.format(bounty) + " §7bounty.");
            }
        }
        // The killer's streak grows.
        if (killer == null || killerId.equals(victimId) || victim == null) return;
        Streak ks = STREAKS.computeIfAbsent(killerId, k -> new Streak());
        String lvl = killer.level().dimension().identifier().toString();
        if (ks.level != null && !ks.level.equals(lvl)) {
            ks.kills = 0;
            ks.announced = false;
        }
        ks.level = lvl;
        double before = ks.kills;
        ks.kills += BotManager.isBot(victimId) ? BOT_KILL : 1.0;
        if (ks.kills >= BOUNTY_STREAK) {
            killer.setGlowingTag(true);
            String kName = killer.getName().getString();
            if (before < BOUNTY_STREAK) {
                tell(killer, "§c§l🔥 KILL STREAK! §e" + kName + " §fis on a streak of " + fmt(ks.kills)
                        + " §fand now glows - §6" + Economy.format(bounty(ks.kills)) + " §fbounty to whoever stops them!");
                if (!BotManager.isBot(killerId)) Announce.title(killer, "§c§lKILL STREAK", "§eYou glow - §6" + Economy.format(bounty(ks.kills)) + " §eon your head", 5, 40, 10);
            } else if ((long) ks.kills > (long) before) {
                tell(killer, "§c🔥 §e" + kName + " §fis on " + fmt(ks.kills) + " - bounty now §6" + Economy.format(bounty(ks.kills)) + "§f!");
            }
            ks.announced = true;
        }
    }

    /** Clears streaks for anyone who left their game's world, logged out or is spectating; keeps the glow on the rest. */
    private static void second(MinecraftServer s) {
        for (Iterator<Map.Entry<UUID, Streak>> it = STREAKS.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, Streak> e = it.next();
            ServerPlayer p = s.getPlayerList().getPlayer(e.getKey());
            Streak st = e.getValue();
            boolean gone = p == null || !p.level().dimension().identifier().toString().equals(st.level);
            if (gone) {
                if (p != null && st.kills >= BOUNTY_STREAK) p.setGlowingTag(false);
                it.remove();
                continue;
            }
            // Games turn glowing off on death / respawn; keep it on while the streak lasts.
            if (st.kills >= BOUNTY_STREAK && p.isAlive() && p.gameMode.getGameModeForPlayer() != GameType.SPECTATOR && !p.hasGlowingTag())
                p.setGlowingTag(true);
        }
    }

    /** A line for everyone in the same world (the match). */
    private static void tell(ServerPlayer anyoneThere, String text) {
        if (anyoneThere == null) return;
        ServerLevel level = (ServerLevel) anyoneThere.level();
        for (ServerPlayer p : level.players()) if (!BotManager.isBot(p.getUUID())) Lobby.msg(p, text);
    }

    private static String fmt(double kills) {
        return kills == Math.floor(kills) ? String.valueOf((long) kills) : String.format(Locale.ROOT, "%.1f", kills);
    }

    /** For /stats or debugging: someone's current streak. */
    public static double streak(UUID id) {
        Streak s = STREAKS.get(id);
        return s == null ? 0 : s.kills;
    }
}
