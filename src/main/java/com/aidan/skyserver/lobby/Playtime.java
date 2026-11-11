package com.aidan.skyserver.lobby;

import com.aidan.skyserver.island.Islands;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.time.LocalDate;
import java.util.*;

/**
 * 5.13: how long people play, and where. Once a minute every real player online adds a minute to
 * the area they're in (lobby, island, Bedwars, Gold Wars, Skywars, expedition). Games played per mode
 * (from Quests: *_play events) and money paid through Rewards are counted per day too.
 * Saved in config/skyserver/playtime.json (the last 60 days). /ss usage shows the last 7 days.
 */
public final class Playtime {
    private Playtime() {}

    public static final String[] AREAS = {"lobby", "island", "bedwars", "goldwars", "skywars", "expedition", "other"};
    private static final int KEEP_DAYS = 60;

    /** day -> player -> area -> minutes */
    private static final Map<String, Map<UUID, Map<String, Integer>>> MIN = new TreeMap<>();
    /** day -> mode -> games */
    private static final Map<String, Map<String, Integer>> GAMES = new TreeMap<>();
    /** day -> money paid through Rewards (games, quests, activities) */
    private static final Map<String, Long> MONEY = new TreeMap<>();
    private static boolean loaded = false, dirty = false;
    private static long ticks = 0;

    public static void init() {
        ServerLifecycleEvents.SERVER_STOPPING.register(s -> { if (dirty) Stacks.writeFile("playtime.json", toJson()); });
        ServerTickEvents.END_SERVER_TICK.register(s -> com.aidan.skyserver.Safe.run("playtime", () -> tick(s)));
        CommandRegistrationCallback.EVENT.register((d, r, e) -> d.register(Commands.literal("ss")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(Commands.literal("usage").executes(c -> {
                    for (String l : report()) c.getSource().sendSuccess(() -> Component.literal(l), false);
                    return 1;
                }))));
    }

    private static String today() {
        return LocalDate.now().toString();
    }

    static String area(ServerPlayer p) {
        UUID id = p.getUUID();
        if (com.aidan.skyserver.bedwars.BedwarsGame.isInGame(id)) return "bedwars";
        if (com.aidan.skyserver.goldwars.GoldWarsGame.isInGame(id)) return "goldwars";
        if (com.aidan.skyserver.skywars.SkywarsGame.isInGame(id)) return "skywars";
        if (Voyage.isExpedition(p)) return "expedition";
        if (Islands.isIsland(p.level())) return "island";
        if (Lobby.isLobby(p.level())) return "lobby";
        return "other";
    }

    private static void tick(MinecraftServer s) {
        ticks++;
        if (ticks % 1200 != 0) return;
        load();
        String day = today();
        Map<UUID, Map<String, Integer>> m = MIN.computeIfAbsent(day, k -> new HashMap<>());
        for (ServerPlayer p : s.getPlayerList().getPlayers()) {
            if (p instanceof com.aidan.skyserver.bots.BotPlayer) continue;
            m.computeIfAbsent(p.getUUID(), k -> new HashMap<>()).merge(area(p), 1, Integer::sum);
            dirty = true;
        }
        if (dirty && ticks % 6000 == 0) save();
    }

    /** A game of this mode was played (Quests: bw_play / sw_play / gw_play). */
    public static void game(String mode) {
        load();
        GAMES.computeIfAbsent(today(), k -> new HashMap<>()).merge(mode, 1, Integer::sum);
        dirty = true;
    }

    /** Money paid through Rewards.pay. */
    public static void money(long amount) {
        if (amount <= 0) return;
        load();
        MONEY.merge(today(), amount, Long::sum);
        dirty = true;
    }

    static List<String> report() {
        load();
        LocalDate t = LocalDate.now();
        Set<UUID> players = new HashSet<>();
        Map<String, Long> byArea = new LinkedHashMap<>();
        for (String a : AREAS) byArea.put(a, 0L);
        Map<String, Integer> games = new TreeMap<>();
        long total = 0, money = 0;
        int[] perDay = new int[7];
        for (int i = 0; i < 7; i++) {
            String day = t.minusDays(i).toString();
            Map<UUID, Map<String, Integer>> m = MIN.get(day);
            if (m != null) {
                perDay[i] = m.size();
                for (Map.Entry<UUID, Map<String, Integer>> e : m.entrySet()) {
                    players.add(e.getKey());
                    for (Map.Entry<String, Integer> a : e.getValue().entrySet()) {
                        byArea.merge(a.getKey(), (long) a.getValue(), Long::sum);
                        total += a.getValue();
                    }
                }
            }
            Map<String, Integer> g = GAMES.get(day);
            if (g != null) g.forEach((k, v) -> games.merge(k, v, Integer::sum));
            money += MONEY.getOrDefault(day, 0L);
        }
        List<String> out = new ArrayList<>();
        out.add("§6§lUSAGE §7(last 7 days)");
        out.add("§7Unique players: §f" + players.size() + "  §7Total: §f" + hours(total) + " h"
                + "  §7Players per day (today first): §f" + Arrays.toString(perDay));
        StringBuilder sb = new StringBuilder("§7Hours: ");
        byArea.forEach((a, v) -> { if (v > 0) sb.append("§f").append(a).append(" §e").append(hours(v)).append("  "); });
        out.add(sb.toString());
        StringBuilder gb = new StringBuilder("§7Games: ");
        if (games.isEmpty()) gb.append("§8none");
        games.forEach((k, v) -> gb.append("§f").append(k).append(" §e").append(v).append("  "));
        out.add(gb.toString());
        out.add("§7Money from games, quests & activities: §6" + Economy.format(money)
                + (total > 0 ? " §7= §6" + Economy.format(Math.round(money / (total / 60.0))) + "§7 per player-hour" : ""));
        out.add("§8(Skill level-ups and island money aren't in that figure: compare /ss economy.)");
        out.add(Voyage.usageLine());
        return out;
    }

    private static String hours(long minutes) {
        return String.format("%.1f", minutes / 60.0);
    }

    // ---- Saving ----

    private static void load() {
        if (loaded) return;
        loaded = true;
        JsonObject root = Stacks.readFile("playtime.json");
        if (!root.has("days")) return;
        for (Map.Entry<String, JsonElement> d : root.getAsJsonObject("days").entrySet()) {
            JsonObject o = d.getValue().getAsJsonObject();
            if (o.has("min")) {
                Map<UUID, Map<String, Integer>> m = new HashMap<>();
                for (Map.Entry<String, JsonElement> pe : o.getAsJsonObject("min").entrySet()) {
                    Map<String, Integer> a = new HashMap<>();
                    for (Map.Entry<String, JsonElement> ae : pe.getValue().getAsJsonObject().entrySet()) a.put(ae.getKey(), ae.getValue().getAsInt());
                    m.put(UUID.fromString(pe.getKey()), a);
                }
                MIN.put(d.getKey(), m);
            }
            if (o.has("games")) {
                Map<String, Integer> g = new HashMap<>();
                for (Map.Entry<String, JsonElement> ge : o.getAsJsonObject("games").entrySet()) g.put(ge.getKey(), ge.getValue().getAsInt());
                GAMES.put(d.getKey(), g);
            }
            if (o.has("money")) MONEY.put(d.getKey(), o.get("money").getAsLong());
        }
    }

    private static JsonObject toJson() {
        String oldest = LocalDate.now().minusDays(KEEP_DAYS).toString();
        MIN.keySet().removeIf(k -> k.compareTo(oldest) < 0);
        GAMES.keySet().removeIf(k -> k.compareTo(oldest) < 0);
        MONEY.keySet().removeIf(k -> k.compareTo(oldest) < 0);
        Set<String> days = new TreeSet<>(MIN.keySet());
        days.addAll(GAMES.keySet());
        days.addAll(MONEY.keySet());
        JsonObject all = new JsonObject();
        for (String day : days) {
            JsonObject o = new JsonObject();
            Map<UUID, Map<String, Integer>> m = MIN.get(day);
            if (m != null) {
                JsonObject mo = new JsonObject();
                m.forEach((id, a) -> {
                    JsonObject ao = new JsonObject();
                    a.forEach(ao::addProperty);
                    mo.add(id.toString(), ao);
                });
                o.add("min", mo);
            }
            Map<String, Integer> g = GAMES.get(day);
            if (g != null) {
                JsonObject go = new JsonObject();
                g.forEach(go::addProperty);
                o.add("games", go);
            }
            if (MONEY.containsKey(day)) o.addProperty("money", MONEY.get(day));
            all.add(day, o);
        }
        JsonObject root = new JsonObject();
        root.add("days", all);
        return root;
    }

    private static void save() {
        dirty = false;
        JsonObject root = toJson();
        PlayerCache.io(() -> Stacks.writeFile("playtime.json", root));
    }
}
