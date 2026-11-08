package com.aidan.skyserver.lobby;

import com.google.gson.JsonObject;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.util.*;

/**
 * Server events.
 *
 *  Double XP weekends: every Saturday and Sunday (server's clock), all server XP and skill XP is doubled.
 *  Admins can also start one any time with /ss event doublexp <hours>.
 *
 *  Spleef Tournament: every Saturday at 7pm (server's clock) for 1 hour. Spleef / TNT Run wins against
 *  real players score tournament points. When it ends, 1st gets a Rare Key + $5,000, 2nd an Uncommon Key
 *  + $2,500, 3rd a Common Key + $1,000. Admins can start one with /ss event spleef <minutes>.
 *
 * Settings are in config/skyserver/events.json (made the first time the server starts):
 *   weekendDoubleXp (true/false), tournamentDay (MONDAY..SUNDAY), tournamentHour (0-23), tournamentMinutes.
 */
public final class Events {
    private static boolean loaded = false;
    private static boolean weekendDoubleXp = true;
    private static DayOfWeek tournamentDay = DayOfWeek.SATURDAY;
    private static int tournamentHour = 19, tournamentMinutes = 60;

    private static long manualXpUntil = 0;          // epoch ms
    private static long tournamentEnd = 0;          // epoch ms, 0 = none running
    private static String lastAutoTournament = "";  // date the automatic one last ran
    private static final Map<UUID, Integer> POINTS = new HashMap<>();
    private static final Map<UUID, String> NAMES = new HashMap<>();
    private static boolean warned10 = false, warned1 = false, xpAnnounced = false;

    private Events() {}

    public static void init() {
        net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback.EVENT.register((d, r, e) -> d.register(
                net.minecraft.commands.Commands.literal("events").executes(c -> { showStatus(c.getSource().getPlayerOrException()); return 1; })));
    }

    private static void load() {
        if (loaded) return;
        loaded = true;
        JsonObject o = Stacks.readFile("events.json");
        try {
            if (o.has("weekendDoubleXp")) weekendDoubleXp = o.get("weekendDoubleXp").getAsBoolean();
            if (o.has("tournamentDay")) tournamentDay = DayOfWeek.valueOf(o.get("tournamentDay").getAsString().toUpperCase(Locale.ROOT));
            if (o.has("tournamentHour")) tournamentHour = Math.max(0, Math.min(23, o.get("tournamentHour").getAsInt()));
            if (o.has("tournamentMinutes")) tournamentMinutes = Math.max(10, o.get("tournamentMinutes").getAsInt());
            if (o.has("manualXpUntil")) manualXpUntil = o.get("manualXpUntil").getAsLong();
            if (o.has("lastAutoTournament")) lastAutoTournament = o.get("lastAutoTournament").getAsString();
        } catch (RuntimeException e) {
            com.aidan.skyserver.Skyserver.LOGGER.warn("events.json has a bad value, using defaults: {}", e.toString());
        }
        save();
    }

    private static void save() {
        JsonObject o = new JsonObject();
        o.addProperty("weekendDoubleXp", weekendDoubleXp);
        o.addProperty("tournamentDay", tournamentDay.name());
        o.addProperty("tournamentHour", tournamentHour);
        o.addProperty("tournamentMinutes", tournamentMinutes);
        o.addProperty("manualXpUntil", manualXpUntil);
        o.addProperty("lastAutoTournament", lastAutoTournament);
        Stacks.writeFile("events.json", o);
    }

    /** Re-reads events.json (for /ss reload). */
    public static void reload() {
        loaded = false;
        load();
    }

    // ---- Double XP ----

    public static boolean doubleXp() {
        load();
        if (System.currentTimeMillis() < manualXpUntil) return true;
        DayOfWeek d = LocalDateTime.now().getDayOfWeek();
        return weekendDoubleXp && (d == DayOfWeek.SATURDAY || d == DayOfWeek.SUNDAY);
    }

    public static int xp(int amount) {
        return doubleXp() ? amount * 2 : amount;
    }

    public static void startDoubleXp(MinecraftServer server, int hours) {
        load();
        manualXpUntil = System.currentTimeMillis() + hours * 3_600_000L;
        save();
        broadcast(server, "§6§l✦ DOUBLE XP §efor the next " + hours + " hour" + (hours == 1 ? "" : "s") + "! §7All server and skill XP is doubled.");
    }

    // ---- Spleef Tournament ----

    public static boolean tournamentOn() {
        return tournamentEnd > System.currentTimeMillis();
    }

    public static void startTournament(MinecraftServer server, int minutes) {
        POINTS.clear();
        tournamentEnd = System.currentTimeMillis() + minutes * 60_000L;
        broadcast(server, "§b§l❄ SPLEEF TOURNAMENT STARTED! §fWin Spleef or TNT Run rounds against real players for points. §7"
                + minutes + " minutes. §e1st: Rare Key §7| §e2nd: Uncommon Key §7| §e3rd: Common Key");
        for (ServerPlayer p : server.getPlayerList().getPlayers())
            Announce.title(p, "§b§lSPLEEF TOURNAMENT", "§fHead to the Spleef Pit!", 10, 60, 10);
    }

    /** Called when someone wins a Spleef/TNT Run round with 2+ real players. */
    public static void spleefWin(ServerPlayer winner) {
        if (!tournamentOn()) return;
        int pts = POINTS.merge(winner.getUUID(), 1, Integer::sum);
        NAMES.put(winner.getUUID(), winner.getName().getString());
        Lobby.msg(winner, "§b+1 tournament point §7(you have " + pts + ")");
    }

    private static List<Map.Entry<UUID, Integer>> standings() {
        List<Map.Entry<UUID, Integer>> l = new ArrayList<>(POINTS.entrySet());
        l.sort((a, b) -> b.getValue() - a.getValue());
        return l;
    }

    private static void endTournament(MinecraftServer server) {
        tournamentEnd = 0;
        List<Map.Entry<UUID, Integer>> s = standings();
        if (s.isEmpty()) {
            broadcast(server, "§b§lSPLEEF TOURNAMENT OVER §7- nobody scored this time.");
            return;
        }
        broadcast(server, "§b§lSPLEEF TOURNAMENT OVER! §fResults:");
        long[] money = {5_000, 2_500, 1_000};
        int[] tier = {2, 1, 0};
        String[] place = {"§6§l1st", "§f§l2nd", "§c§l3rd"};
        for (int i = 0; i < Math.min(3, s.size()); i++) {
            UUID id = s.get(i).getKey();
            String name = NAMES.getOrDefault(id, "?");
            broadcast(server, " " + place[i] + " §f" + name + " §7- " + s.get(i).getValue() + " points");
            Economy.add(server, id, name, money[i]);
            Keys.reward(server, id, tier[i], 1, "(Spleef Tournament " + (i + 1) + (i == 0 ? "st" : i == 1 ? "nd" : "rd") + " place)");
            PlayerStats.add(id, "tournament_win", i == 0 ? 1 : 0);
        }
        POINTS.clear();
    }

    /** One line for the sidebar, or null. */
    public static String sidebarLine() {
        if (tournamentOn()) {
            long left = (tournamentEnd - System.currentTimeMillis()) / 60_000 + 1;
            return "§b❄ Tournament §f" + left + "m";
        }
        if (doubleXp()) return "§6✦ Double XP!";
        return null;
    }

    public static int tournamentPoints(UUID id) {
        return POINTS.getOrDefault(id, 0);
    }

    public static void showStatus(ServerPlayer p) {
        load();
        Lobby.msg(p, "§6§lEVENTS");
        Lobby.msg(p, " §6Double XP: " + (doubleXp() ? "§aON now" : "§7off") + " §8(every weekend" + (weekendDoubleXp ? "" : " - turned off") + ")");
        if (tournamentOn()) {
            Lobby.msg(p, " §bSpleef Tournament: §aRUNNING §7- your points: §f" + tournamentPoints(p.getUUID()));
            List<Map.Entry<UUID, Integer>> s = standings();
            for (int i = 0; i < Math.min(3, s.size()); i++)
                Lobby.msg(p, "   §e" + (i + 1) + ". §f" + NAMES.getOrDefault(s.get(i).getKey(), "?") + " §7" + s.get(i).getValue());
        } else {
            String day = tournamentDay.name().charAt(0) + tournamentDay.name().substring(1).toLowerCase(Locale.ROOT);
            int h12 = tournamentHour % 12 == 0 ? 12 : tournamentHour % 12;
            Lobby.msg(p, " §bSpleef Tournament: §fevery " + day + " at " + h12 + (tournamentHour < 12 ? "am" : "pm") + " §7(server time) - 1st place wins a Rare Key");
        }
    }

    // ---- Every second ----

    static void tick(MinecraftServer server, long ticks) {
        if (ticks % 20 != 0) return;
        load();
        LocalDateTime now = LocalDateTime.now();
        String today = now.toLocalDate().toString();
        if (now.getDayOfWeek() == tournamentDay && !today.equals(lastAutoTournament)) {
            int minsUntil = (tournamentHour * 60) - (now.getHour() * 60 + now.getMinute());
            if (minsUntil == 10 && !warned10) {
                warned10 = true;
                broadcast(server, "§b❄ The Spleef Tournament starts in 10 minutes! §7Winner gets a Rare Key.");
            } else if (minsUntil == 1 && !warned1) {
                warned1 = true;
                broadcast(server, "§b❄ The Spleef Tournament starts in 1 minute! §7Get to the Spleef Pit.");
            } else if (minsUntil <= 0 && minsUntil > -tournamentMinutes && !tournamentOn()) {
                lastAutoTournament = today;
                save();
                warned10 = warned1 = false;
                startTournament(server, tournamentMinutes + minsUntil);
            }
        }
        if (tournamentEnd != 0 && !tournamentOn()) endTournament(server);
        // Tell people when double XP starts (once per server run / weekend).
        boolean x = doubleXp();
        if (x && !xpAnnounced) broadcast(server, "§6§l✦ DOUBLE XP IS ON! §7All server and skill XP is doubled.");
        xpAnnounced = x;
    }

    static void broadcast(MinecraftServer server, String text) {
        for (ServerPlayer p : server.getPlayerList().getPlayers()) Lobby.msg(p, text);
    }
}
