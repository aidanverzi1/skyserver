package com.aidan.skyserver.lobby;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.Style;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.*;

/**
 * 5.8: boosters from mystery chests and the Shard Shop. They wait in your booster bag until you
 * use them (/boosters), then run for real minutes (online or not).
 *
 *  - XP booster: +PCT% server XP.
 *  - Money booster: +PCT% money from games and lobby activities (everything paid through Rewards).
 *  - Server booster: +PCT% money for EVERYONE online. Only one runs at a time; others wait in a queue.
 *
 * Personal boosters of the same kind don't stack: using another one adds its minutes to the running one.
 * Saved in config/skyserver/boosters.json.
 */
public final class Boosters {
    /** Active personal boosters: player -> kind ("xp"/"money") -> {until ms, pct}. */
    private static final Map<UUID, Map<String, long[]>> ACTIVE = new HashMap<>();
    /** The running server booster {until ms, pct} and who started it. */
    private static long serverUntil = 0, serverPct = 0;
    private static String serverBy = "";
    /** Waiting server boosters: {minutes, pct, name}. */
    private static final List<String[]> QUEUE = new ArrayList<>();
    private static boolean loaded = false;

    private Boosters() {}

    public static void init() {
        CommandRegistrationCallback.EVENT.register((d, r, e) -> d.register(Commands.literal("boosters")
                .executes(c -> { show(c.getSource().getPlayerOrException()); return 1; })
                .then(Commands.literal("use").then(Commands.argument("id", StringArgumentType.greedyString())
                        .executes(c -> { use(c.getSource().getPlayerOrException(), StringArgumentType.getString(c, "id")); return 1; })))));
        ServerTickEvents.END_SERVER_TICK.register(s -> {
            if (s.getTickCount() % 100 != 0) return;
            com.aidan.skyserver.Safe.run("boosters", () -> tick(s));
        });
    }

    // ---- Effects ----

    private static long pct(UUID id, String kind) {
        load();
        Map<String, long[]> m = ACTIVE.get(id);
        if (m == null) return 0;
        long[] a = m.get(kind);
        return a != null && a[0] > System.currentTimeMillis() ? a[1] : 0;
    }

    /** Money with the player's money booster and any server booster. */
    public static long money(ServerPlayer p, long amount) {
        if (p == null || amount <= 0) return amount;
        long pct = pct(p.getUUID(), "money") + (serverUntil > System.currentTimeMillis() ? serverPct : 0);
        return pct <= 0 ? amount : Math.round(amount * (100 + pct) / 100.0);
    }

    /** XP with the player's XP booster. */
    public static int xp(ServerPlayer p, int amount) {
        if (p == null || amount <= 0) return amount;
        long pct = pct(p.getUUID(), "xp");
        return pct <= 0 ? amount : (int) Math.round(amount * (100 + pct) / 100.0);
    }

    // ---- Text ----

    /** "§b30 min +25% XP Booster" for a bag id like booster:xp:30:25. */
    public static String describe(String id) {
        String[] t = id.split(":");
        if (t.length < 4) return id;
        return switch (t[1]) {
            case "xp" -> "§b" + t[2] + " min +" + t[3] + "% XP Booster";
            case "money" -> "§6" + t[2] + " min +" + t[3] + "% Money Booster";
            case "server" -> "§d§l" + t[2] + " min +" + t[3] + "% SERVER Money Booster";
            default -> id;
        };
    }

    static String timeLeft(long until) {
        long min = Math.max(0, (until - System.currentTimeMillis()) / 60_000);
        return min >= 60 ? (min / 60) + "h " + (min % 60) + "m" : min + "m";
    }

    /** Lines for the sidebar/menus: what's running for this player. */
    public static List<String> activeLines(UUID id) {
        load();
        List<String> out = new ArrayList<>();
        long now = System.currentTimeMillis();
        Map<String, long[]> m = ACTIVE.get(id);
        if (m != null) for (Map.Entry<String, long[]> e : m.entrySet()) if (e.getValue()[0] > now)
            out.add((e.getKey().equals("xp") ? "§b+" : "§6+") + e.getValue()[1] + "% " + (e.getKey().equals("xp") ? "XP" : "money") + " §7(" + timeLeft(e.getValue()[0]) + ")");
        if (serverUntil > now) out.add("§d+" + serverPct + "% money for everyone §7(" + serverBy + ", " + timeLeft(serverUntil) + ")");
        return out;
    }

    // ---- Using ----

    static void show(ServerPlayer p) {
        Lobby.msg(p, "§6§lYOUR BOOSTERS");
        List<String> act = activeLines(p.getUUID());
        if (act.isEmpty()) Lobby.msg(p, " §7Nothing running right now.");
        else for (String l : act) Lobby.msg(p, " §a▶ " + l);
        Map<String, Integer> bag = Tokens.withPrefix(p.getUUID(), "booster:");
        if (bag.isEmpty()) {
            Lobby.msg(p, " §7Your booster bag is empty. Boosters come from mystery chests and the §e/shards §7shop.");
            return;
        }
        Lobby.msg(p, "§7In your bag (click to use):");
        for (Map.Entry<String, Integer> e : bag.entrySet()) {
            p.sendSystemMessage(Component.literal(" §e• ").append(Component.literal(describe(e.getKey()) + " §7x" + e.getValue() + " §a[Use]")
                    .withStyle(Style.EMPTY.withClickEvent(new ClickEvent.RunCommand("/boosters use " + e.getKey()))
                            .withHoverEvent(new HoverEvent.ShowText(Component.literal("Start it now"))))));
        }
        if (!QUEUE.isEmpty()) Lobby.msg(p, "§7Server boosters waiting: §f" + QUEUE.size());
    }

    static void use(ServerPlayer p, String id) {
        String[] t = id.split(":");
        if (t.length < 4 || !Tokens.take(p.getUUID(), id, 1)) {
            Lobby.msg(p, "§cYou don't have that booster. §7/boosters");
            return;
        }
        load();
        int minutes = Integer.parseInt(t[2]), pct = Integer.parseInt(t[3]);
        long now = System.currentTimeMillis();
        if (t[1].equals("server")) {
            if (serverUntil > now) {
                QUEUE.add(new String[]{String.valueOf(minutes), String.valueOf(pct), p.getName().getString()});
                Lobby.msg(p, "§dA server booster is already running - yours is queued (#" + QUEUE.size() + ") and starts after it.");
            } else {
                startServer(p.level().getServer(), minutes, pct, p.getName().getString());
            }
        } else {
            long[] a = ACTIVE.computeIfAbsent(p.getUUID(), k -> new HashMap<>()).get(t[1]);
            if (a != null && a[0] > now) {
                a[0] += minutes * 60_000L;
                a[1] = Math.max(a[1], pct);
                Lobby.msg(p, "§aAdded " + minutes + " minutes to your running booster §7(" + timeLeft(a[0]) + " left).");
            } else {
                ACTIVE.get(p.getUUID()).put(t[1], new long[]{now + minutes * 60_000L, pct});
                Lobby.msg(p, "§a§lBooster on! " + describe(id));
            }
        }
        save();
    }

    private static void startServer(MinecraftServer server, int minutes, long pct, String by) {
        serverUntil = System.currentTimeMillis() + minutes * 60_000L;
        serverPct = pct;
        serverBy = by;
        save();
        server.getPlayerList().broadcastSystemMessage(Component.literal("§d§l✦ SERVER BOOSTER! §e" + by + " §7started §d+" + pct
                + "% money for everyone §7for " + minutes + " minutes! §8(Say thanks!)"), false);
    }

    private static void tick(MinecraftServer server) {
        load();
        long now = System.currentTimeMillis();
        if (serverUntil != 0 && serverUntil <= now) {
            serverUntil = 0;
            server.getPlayerList().broadcastSystemMessage(Component.literal("§7The server booster from §e" + serverBy + " §7has ended."), false);
            if (!QUEUE.isEmpty()) {
                String[] q = QUEUE.remove(0);
                startServer(server, Integer.parseInt(q[0]), Long.parseLong(q[1]), q[2]);
            } else save();
        }
        for (Map.Entry<UUID, Map<String, long[]>> e : ACTIVE.entrySet()) {
            ServerPlayer p = server.getPlayerList().getPlayer(e.getKey());
            for (Iterator<Map.Entry<String, long[]>> it = e.getValue().entrySet().iterator(); it.hasNext(); ) {
                Map.Entry<String, long[]> b = it.next();
                if (b.getValue()[0] > now) continue;
                it.remove();
                if (p != null) Lobby.msg(p, "§7Your " + (b.getKey().equals("xp") ? "XP" : "money") + " booster has run out. §e/boosters");
            }
        }
    }

    // ---- Saving ----

    private static void load() {
        if (loaded) return;
        loaded = true;
        JsonObject root = Stacks.readFile("boosters.json");
        if (root.has("active")) for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("active").entrySet()) {
            Map<String, long[]> m = new HashMap<>();
            for (Map.Entry<String, JsonElement> b : e.getValue().getAsJsonObject().entrySet()) {
                JsonArray a = b.getValue().getAsJsonArray();
                m.put(b.getKey(), new long[]{a.get(0).getAsLong(), a.get(1).getAsLong()});
            }
            ACTIVE.put(UUID.fromString(e.getKey()), m);
        }
        if (root.has("server")) {
            JsonArray a = root.getAsJsonArray("server");
            serverUntil = a.get(0).getAsLong();
            serverPct = a.get(1).getAsLong();
            serverBy = a.get(2).getAsString();
        }
        if (root.has("queue")) for (JsonElement e : root.getAsJsonArray("queue")) {
            JsonArray a = e.getAsJsonArray();
            QUEUE.add(new String[]{a.get(0).getAsString(), a.get(1).getAsString(), a.get(2).getAsString()});
        }
    }

    private static void save() {
        JsonObject root = new JsonObject(), active = new JsonObject();
        ACTIVE.forEach((id, m) -> {
            JsonObject o = new JsonObject();
            m.forEach((k, v) -> {
                JsonArray a = new JsonArray();
                a.add(v[0]);
                a.add(v[1]);
                o.add(k, a);
            });
            active.add(id.toString(), o);
        });
        root.add("active", active);
        JsonArray sv = new JsonArray();
        sv.add(serverUntil);
        sv.add(serverPct);
        sv.add(serverBy);
        root.add("server", sv);
        JsonArray q = new JsonArray();
        for (String[] x : QUEUE) {
            JsonArray a = new JsonArray();
            for (String v : x) a.add(v);
            q.add(a);
        }
        root.add("queue", q);
        PlayerCache.io(() -> Stacks.writeFile("boosters.json", root));
    }
}
