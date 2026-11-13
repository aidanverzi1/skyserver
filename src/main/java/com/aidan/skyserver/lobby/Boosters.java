package com.aidan.skyserver.lobby;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
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
 * Boosters from mystery chests and the Shard Shop. They wait in your booster bag until you use them
 * (/boosters), then run for real minutes (online or not).
 *
 * 5.19: EVERY booster is server-wide.
 *  - XP booster: +PCT% server XP for everyone online. Money booster (and the old "server" booster):
 *    +PCT% money from games and lobby activities for everyone online.
 *  - Using one is announced to the whole server. If one of the same kind is already running, yours
 *    queues behind it, so the boost lasts longer.
 *  - /tip: thank whoever started the latest booster: $200 from your wallet goes to them, plus $50 from
 *    the server. Once per booster per player, and not your own.
 *  - Patron: while a booster you started runs, you have a gold [Patron] tag (Ranks).
 *  - Every 5 tips one booster gets, its owner gets a Key Shard.
 *  - "Most Generous" board in the lobby: the most tips given, all time.
 *  - /boosters gift <player> <booster>: moves one from your bag to a friend's bag; when they use it,
 *    the announcement credits you both.
 * Personal boosters started before 5.19 run out as they were. Saved in config/skyserver/boosters.json.
 */
public final class Boosters {
    /** /tip: what the tipper pays, what the server adds, and tips per Key Shard for the booster's owner. */
    public static final long TIP = 200, TIP_SERVER = 50;
    public static final int TIPS_PER_SHARD = 5;
    /** Tag shown in front of the name while your booster runs. */
    public static final String PATRON_TAG = "§6[Patron] ";
    /** The two kinds that run server-wide. */
    private static final String[] KINDS = {"money", "xp"};

    /** Legacy personal boosters (before 5.19): player -> kind -> {until ms, pct}. */
    private static final Map<UUID, Map<String, long[]>> ACTIVE = new HashMap<>();

    /** A running or waiting server booster. */
    private static final class Run {
        long id, until, minutes, pct;
        String by = "", from = "";
        UUID byId;
        int tips = 0;
        final Set<UUID> tippers = new HashSet<>();
    }

    /** kind -> the running booster (null = none), and the queue behind it. */
    private static final Map<String, Run> RUNNING = new HashMap<>();
    private static final Map<String, List<Run>> QUEUE = new HashMap<>();
    /** The booster /tip goes to (the latest one started that's still running). */
    private static Run latest = null;
    private static long nextId = 1;
    /** Tips given, all time (for the board), and the names to show. */
    private static final Map<UUID, Integer> TIPS_GIVEN = new HashMap<>();
    private static final Map<UUID, String> NAMES = new HashMap<>();
    private static boolean loaded = false;
    private static MinecraftServer server;
    private static Holograms.Holo board;

    private Boosters() {}

    public static void init() {
        ServerLifecycleEvents.SERVER_STARTED.register(s -> server = s);
        CommandRegistrationCallback.EVENT.register((d, r, e) -> {
            d.register(Commands.literal("boosters")
                    .executes(c -> { show(c.getSource().getPlayerOrException()); return 1; })
                    .then(Commands.literal("use").then(Commands.argument("id", StringArgumentType.greedyString())
                            .executes(c -> { use(c.getSource().getPlayerOrException(), StringArgumentType.getString(c, "id")); return 1; })))
                    .then(Commands.literal("gift").then(Commands.argument("player", StringArgumentType.word())
                            .executes(c -> { giftList(c.getSource().getPlayerOrException(), StringArgumentType.getString(c, "player")); return 1; })
                            .then(Commands.argument("id", StringArgumentType.greedyString())
                                    .executes(c -> { gift(c.getSource().getPlayerOrException(), StringArgumentType.getString(c, "player"), StringArgumentType.getString(c, "id")); return 1; })))));
            d.register(Commands.literal("tip").executes(c -> { tip(c.getSource().getPlayerOrException()); return 1; }));
        });
        ServerTickEvents.END_SERVER_TICK.register(s -> {
            if (s.getTickCount() % 100 != 0) return;
            com.aidan.skyserver.Safe.run("boosters", () -> tick(s));
        });
    }

    /** The lobby's "Most Generous" board (Lobby.lobbyBuilt). */
    static void setupBoard(double x, double y, double z) {
        board = Holograms.add(x, y, z, List.of("§6§lMOST GENEROUS", "§7nobody has tipped yet", "§8/tip after a booster"));
        refreshBoard();
    }

    // ---- Effects ----

    private static long personal(UUID id, String kind) {
        load();
        Map<String, long[]> m = ACTIVE.get(id);
        if (m == null) return 0;
        long[] a = m.get(kind);
        return a != null && a[0] > System.currentTimeMillis() ? a[1] : 0;
    }

    private static long serverPct(String kind) {
        load();
        Run r = RUNNING.get(kind);
        return r != null && r.until > System.currentTimeMillis() ? r.pct : 0;
    }

    /** Money with any money booster (server-wide, plus an old personal one still running). */
    public static long money(ServerPlayer p, long amount) {
        if (p == null || amount <= 0) return amount;
        long pct = personal(p.getUUID(), "money") + serverPct("money");
        return pct <= 0 ? amount : Math.round(amount * (100 + pct) / 100.0);
    }

    /** XP with any XP booster (server-wide, plus an old personal one still running). */
    public static int xp(ServerPlayer p, int amount) {
        if (p == null || amount <= 0) return amount;
        long pct = personal(p.getUUID(), "xp") + serverPct("xp");
        return pct <= 0 ? amount : (int) Math.round(amount * (100 + pct) / 100.0);
    }

    /** True while a booster this player started is running (the [Patron] tag). */
    public static boolean isPatron(UUID id) {
        load();
        long now = System.currentTimeMillis();
        for (Run r : RUNNING.values()) if (r != null && r.until > now && id.equals(r.byId)) return true;
        return false;
    }

    // ---- Text ----

    private static String kindOf(String t1) {
        return t1.equals("xp") ? "xp" : "money"; // "server" boosters are money boosters
    }

    /** "§b30 min +25% XP Booster (everyone)" for a bag id like booster:xp:30:25 (gifts: ...:from:Name). */
    public static String describe(String id) {
        String[] t = id.split(":");
        if (t.length < 4) return id;
        String gift = t.length >= 6 && t[4].equals("from") ? " §7(gift from " + t[5] + ")" : "";
        return switch (t[1]) {
            case "xp" -> "§b" + t[2] + " min +" + t[3] + "% XP Booster §7(everyone)" + gift;
            case "money", "server" -> "§6" + t[2] + " min +" + t[3] + "% Money Booster §7(everyone)" + gift;
            default -> id;
        };
    }

    static String timeLeft(long until) {
        long min = Math.max(0, (until - System.currentTimeMillis()) / 60_000);
        return min >= 60 ? (min / 60) + "h " + (min % 60) + "m" : min + "m";
    }

    private static String label(String kind) {
        return kind.equals("xp") ? "§b+%d%% XP" : "§6+%d%% money";
    }

    /** Lines for the sidebar/menus: what's running. */
    public static List<String> activeLines(UUID id) {
        load();
        List<String> out = new ArrayList<>();
        long now = System.currentTimeMillis();
        Map<String, long[]> m = ACTIVE.get(id);
        if (m != null) for (Map.Entry<String, long[]> e : m.entrySet()) if (e.getValue()[0] > now)
            out.add((e.getKey().equals("xp") ? "§b+" : "§6+") + e.getValue()[1] + "% " + (e.getKey().equals("xp") ? "XP" : "money") + " §7(yours, " + timeLeft(e.getValue()[0]) + ")");
        for (String k : KINDS) {
            Run r = RUNNING.get(k);
            if (r == null || r.until <= now) continue;
            int q = QUEUE.getOrDefault(k, List.of()).size();
            out.add(String.format(label(k), r.pct) + " for everyone §7(" + r.by + ", " + timeLeft(r.until) + (q > 0 ? ", +" + q + " queued" : "") + ")");
        }
        return out;
    }

    // ---- Using ----

    static void show(ServerPlayer p) {
        Lobby.msg(p, "§6§lBOOSTERS §7(every booster boosts the whole server)");
        List<String> act = activeLines(p.getUUID());
        if (act.isEmpty()) Lobby.msg(p, " §7Nothing running right now.");
        else for (String l : act) Lobby.msg(p, " §a▶ " + l);
        Map<String, Integer> bag = Tokens.withPrefix(p.getUUID(), "booster:");
        if (bag.isEmpty()) {
            Lobby.msg(p, " §7Your booster bag is empty. Boosters come from mystery chests and the §e/shards §7shop.");
            return;
        }
        Lobby.msg(p, "§7In your bag (click to use, or §e/boosters gift <player> §7to give one away):");
        for (Map.Entry<String, Integer> e : bag.entrySet()) {
            p.sendSystemMessage(Component.literal(" §e• ").append(Component.literal(describe(e.getKey()) + " §7x" + e.getValue() + " §a[Use]")
                    .withStyle(Style.EMPTY.withClickEvent(new ClickEvent.RunCommand("/boosters use " + e.getKey()))
                            .withHoverEvent(new HoverEvent.ShowText(Component.literal("Start it now for everyone"))))));
        }
    }

    static void use(ServerPlayer p, String id) {
        String[] t = id.split(":");
        if (t.length < 4 || !Tokens.take(p.getUUID(), id, 1)) {
            Lobby.msg(p, "§cYou don't have that booster. §7/boosters");
            return;
        }
        load();
        int minutes, pct;
        try {
            minutes = Integer.parseInt(t[2]);
            pct = Integer.parseInt(t[3]);
        } catch (NumberFormatException ex) {
            Tokens.add(p.getUUID(), id, 1);
            Lobby.msg(p, "§cThat booster looks broken - it's back in your bag. Tell an admin.");
            return;
        }
        Run r = new Run();
        r.id = nextId++;
        r.minutes = minutes;
        r.pct = pct;
        r.by = p.getName().getString();
        r.byId = p.getUUID();
        r.from = t.length >= 6 && t[4].equals("from") ? t[5] : "";
        String kind = kindOf(t[1]);
        MinecraftServer s = p.level().getServer();
        Run running = RUNNING.get(kind);
        if (running != null && running.until > System.currentTimeMillis()) {
            List<Run> q = QUEUE.computeIfAbsent(kind, k -> new ArrayList<>());
            q.add(r);
            long total = running.until - System.currentTimeMillis();
            for (Run w : q) total += w.minutes * 60_000L;
            broadcast(s, "§d§l✦ BOOSTER QUEUED! §e" + r.by + credit(r) + " §7queued " + describe(id).replace(" §7(everyone)", "")
                    + " §7- " + (kind.equals("xp") ? "XP" : "money") + " boosts now run for §f" + timeLeft(System.currentTimeMillis() + total) + "§7!");
        } else {
            start(s, kind, r);
        }
        save();
    }

    private static String credit(Run r) {
        return r.from.isEmpty() ? "" : " §7(a gift from §e" + r.from + "§7)";
    }

    private static void start(MinecraftServer s, String kind, Run r) {
        r.until = System.currentTimeMillis() + r.minutes * 60_000L;
        RUNNING.put(kind, r);
        latest = r;
        save();
        Component line = Component.literal("§d§l✦ SERVER BOOSTER! §e" + r.by + credit(r) + " §7started " + String.format(label(kind), r.pct)
                        + " §7for everyone for " + r.minutes + " minutes! ")
                .append(Component.literal("§a§l[/tip $" + TIP + "]").withStyle(Style.EMPTY.withClickEvent(new ClickEvent.RunCommand("/tip"))
                        .withHoverEvent(new HoverEvent.ShowText(Component.literal("Send " + r.by + " $" + TIP + " (the server adds $" + TIP_SERVER + ")")))));
        s.getPlayerList().broadcastSystemMessage(line, false);
    }

    // ---- Tips ----

    static void tip(ServerPlayer p) {
        load();
        long now = System.currentTimeMillis();
        Run r = latest;
        if (r == null || r.until <= now) {
            // The latest one ended: tip whichever is still running.
            r = null;
            for (Run x : RUNNING.values()) if (x != null && x.until > now && (r == null || x.id > r.id)) r = x;
        }
        if (r == null) {
            Lobby.msg(p, "§7No booster is running right now. §8When someone uses one, /tip thanks them.");
            return;
        }
        if (p.getUUID().equals(r.byId)) {
            Lobby.msg(p, "§7You can't tip your own booster - but thank you for it!");
            return;
        }
        if (r.tippers.contains(p.getUUID())) {
            Lobby.msg(p, "§7You already tipped " + r.by + " for this booster.");
            return;
        }
        MinecraftServer s = p.level().getServer();
        if (!Economy.take(s, p.getUUID(), TIP)) {
            Lobby.msg(p, "§cYou need " + Economy.format(TIP) + " to tip.");
            return;
        }
        r.tippers.add(p.getUUID());
        r.tips++;
        Economy.add(s, r.byId, r.by, TIP + TIP_SERVER);
        TIPS_GIVEN.merge(p.getUUID(), 1, Integer::sum);
        NAMES.put(p.getUUID(), p.getName().getString());
        Lobby.msg(p, "§aYou tipped §e" + r.by + " §a" + Economy.format(TIP) + "! §7(The server added " + Economy.format(TIP_SERVER) + ".)");
        ServerPlayer owner = s.getPlayerList().getPlayer(r.byId);
        if (owner != null) {
            Lobby.msg(owner, "§6§l✦ TIP! §e" + p.getName().getString() + " §ftipped you §6" + Economy.format(TIP + TIP_SERVER) + " §7(" + Economy.format(TIP_SERVER) + " from the server) §ffor your booster!");
            Announce.ding(owner, 1.6f);
        }
        if (r.tips % TIPS_PER_SHARD == 0) {
            Tokens.add(r.byId, Tokens.SHARDS, 1);
            if (owner != null) Lobby.msg(owner, "§d+1 Key Shard §7for " + r.tips + " tips on one booster!");
        }
        save();
        refreshBoard();
    }

    // ---- Gifts ----

    private static void giftList(ServerPlayer p, String name) {
        Map<String, Integer> bag = Tokens.withPrefix(p.getUUID(), "booster:");
        if (bag.isEmpty()) {
            Lobby.msg(p, "§7Your booster bag is empty.");
            return;
        }
        Lobby.msg(p, "§6Pick a booster to give §e" + name + "§6:");
        for (Map.Entry<String, Integer> e : bag.entrySet()) {
            if (e.getKey().contains(":from:")) continue; // gifts can't be passed on
            p.sendSystemMessage(Component.literal(" §e• ").append(Component.literal(describe(e.getKey()) + " §7x" + e.getValue() + " §d[Gift]")
                    .withStyle(Style.EMPTY.withClickEvent(new ClickEvent.RunCommand("/boosters gift " + name + " " + e.getKey())))));
        }
    }

    private static void gift(ServerPlayer p, String name, String id) {
        MinecraftServer s = p.level().getServer();
        ServerPlayer to = s.getPlayerList().getPlayerByName(name);
        if (to == null || com.aidan.skyserver.bots.BotManager.isBot(to.getUUID())) {
            Lobby.msg(p, "§c" + name + " isn't online.");
            return;
        }
        if (to == p) {
            Lobby.msg(p, "§7That's you! Use it with §e/boosters§7.");
            return;
        }
        if (id.contains(":from:")) {
            Lobby.msg(p, "§7A gifted booster can't be given away again.");
            return;
        }
        if (!Tokens.take(p.getUUID(), id, 1)) {
            Lobby.msg(p, "§cYou don't have that booster. §7/boosters");
            return;
        }
        String giftId = id + ":from:" + p.getName().getString();
        Tokens.add(to.getUUID(), giftId, 1);
        broadcast(s, "§d§l🎁 GIFT! §e" + p.getName().getString() + " §7gave §e" + to.getName().getString() + " §7a " + describe(id).replace(" §7(everyone)", "") + "§7!");
        Lobby.msg(to, "§dStart it with §e/boosters §d- everyone gets the boost and you both get the credit.");
    }

    // ---- Every 5 seconds ----

    private static void tick(MinecraftServer s) {
        load();
        long now = System.currentTimeMillis();
        boolean changed = false;
        for (String k : KINDS) {
            Run r = RUNNING.get(k);
            if (r == null || r.until > now) continue;
            RUNNING.remove(k);
            changed = true;
            List<Run> q = QUEUE.getOrDefault(k, new ArrayList<>());
            if (!q.isEmpty()) start(s, k, q.remove(0));
            else broadcast(s, "§7The " + (k.equals("xp") ? "XP" : "money") + " booster from §e" + r.by + " §7has ended.");
        }
        for (Map.Entry<UUID, Map<String, long[]>> e : ACTIVE.entrySet()) {
            ServerPlayer p = s.getPlayerList().getPlayer(e.getKey());
            for (Iterator<Map.Entry<String, long[]>> it = e.getValue().entrySet().iterator(); it.hasNext(); ) {
                Map.Entry<String, long[]> b = it.next();
                if (b.getValue()[0] > now) continue;
                it.remove();
                changed = true;
                if (p != null) Lobby.msg(p, "§7Your " + (b.getKey().equals("xp") ? "XP" : "money") + " booster has run out. §e/boosters");
            }
        }
        if (changed) save();
    }

    private static void broadcast(MinecraftServer s, String text) {
        s.getPlayerList().broadcastSystemMessage(Component.literal(text), false);
    }

    private static void refreshBoard() {
        if (board == null) return;
        List<Map.Entry<UUID, Integer>> top = new ArrayList<>(TIPS_GIVEN.entrySet());
        top.sort((a, b) -> b.getValue() - a.getValue());
        List<String> lines = new ArrayList<>();
        lines.add("§6§lMOST GENEROUS");
        if (top.isEmpty()) lines.add("§7nobody has tipped yet");
        for (int i = 0; i < Math.min(5, top.size()); i++)
            lines.add("§e" + (i + 1) + ". §f" + NAMES.getOrDefault(top.get(i).getKey(), "?") + " §7- " + top.get(i).getValue() + " tip" + (top.get(i).getValue() == 1 ? "" : "s"));
        lines.add("§8/tip after a booster");
        board.setLines(lines);
    }

    // ---- Saving ----

    private static JsonArray runJson(Run r) {
        JsonArray a = new JsonArray();
        a.add(r.id);
        a.add(r.until);
        a.add(r.minutes);
        a.add(r.pct);
        a.add(r.by);
        a.add(r.byId == null ? "" : r.byId.toString());
        a.add(r.from);
        a.add(r.tips);
        return a;
    }

    private static Run runFrom(JsonArray a) {
        Run r = new Run();
        r.id = a.get(0).getAsLong();
        r.until = a.get(1).getAsLong();
        r.minutes = a.get(2).getAsLong();
        r.pct = a.get(3).getAsLong();
        r.by = a.get(4).getAsString();
        String u = a.get(5).getAsString();
        r.byId = u.isEmpty() ? null : UUID.fromString(u);
        r.from = a.get(6).getAsString();
        r.tips = a.get(7).getAsInt();
        return r;
    }

    private static void load() {
        if (loaded) return;
        loaded = true;
        JsonObject root = Stacks.readFile("boosters.json");
        try {
            if (root.has("active")) for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("active").entrySet()) {
                Map<String, long[]> m = new HashMap<>();
                for (Map.Entry<String, JsonElement> b : e.getValue().getAsJsonObject().entrySet()) {
                    JsonArray a = b.getValue().getAsJsonArray();
                    m.put(b.getKey(), new long[]{a.get(0).getAsLong(), a.get(1).getAsLong()});
                }
                ACTIVE.put(UUID.fromString(e.getKey()), m);
            }
            if (root.has("nextId")) nextId = root.get("nextId").getAsLong();
            if (root.has("running")) for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("running").entrySet())
                RUNNING.put(e.getKey(), runFrom(e.getValue().getAsJsonArray()));
            if (root.has("queues")) for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("queues").entrySet()) {
                List<Run> q = new ArrayList<>();
                for (JsonElement x : e.getValue().getAsJsonArray()) q.add(runFrom(x.getAsJsonArray()));
                QUEUE.put(e.getKey(), q);
            }
            // Before 5.19: one "server" booster and its queue {minutes, pct, name} -> the money lane.
            if (root.has("server") && !root.has("running")) {
                JsonArray a = root.getAsJsonArray("server");
                long until = a.get(0).getAsLong();
                if (until > System.currentTimeMillis()) {
                    Run r = new Run();
                    r.id = nextId++;
                    r.until = until;
                    r.pct = a.get(1).getAsLong();
                    r.by = a.get(2).getAsString();
                    r.minutes = Math.max(1, (until - System.currentTimeMillis()) / 60_000);
                    RUNNING.put("money", r);
                }
            }
            if (root.has("queue") && !root.has("queues")) for (JsonElement e : root.getAsJsonArray("queue")) {
                JsonArray a = e.getAsJsonArray();
                Run r = new Run();
                r.id = nextId++;
                r.minutes = Long.parseLong(a.get(0).getAsString());
                r.pct = Long.parseLong(a.get(1).getAsString());
                r.by = a.get(2).getAsString();
                QUEUE.computeIfAbsent("money", k -> new ArrayList<>()).add(r);
            }
            if (root.has("tipsGiven")) for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("tipsGiven").entrySet()) {
                JsonArray a = e.getValue().getAsJsonArray();
                UUID id = UUID.fromString(e.getKey());
                TIPS_GIVEN.put(id, a.get(0).getAsInt());
                NAMES.put(id, a.get(1).getAsString());
            }
        } catch (RuntimeException ex) {
            com.aidan.skyserver.Skyserver.LOGGER.warn("Couldn't read boosters.json fully: {}", ex.toString());
        }
        long now = System.currentTimeMillis();
        for (Run r : RUNNING.values()) if (r.until > now && (latest == null || r.id > latest.id)) latest = r;
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
        root.addProperty("nextId", nextId);
        JsonObject running = new JsonObject();
        RUNNING.forEach((k, r) -> { if (r != null) running.add(k, runJson(r)); });
        root.add("running", running);
        JsonObject queues = new JsonObject();
        QUEUE.forEach((k, q) -> {
            JsonArray a = new JsonArray();
            for (Run r : q) a.add(runJson(r));
            queues.add(k, a);
        });
        root.add("queues", queues);
        JsonObject tips = new JsonObject();
        TIPS_GIVEN.forEach((id, n) -> {
            JsonArray a = new JsonArray();
            a.add(n);
            a.add(NAMES.getOrDefault(id, "?"));
            tips.add(id.toString(), a);
        });
        root.add("tipsGiven", tips);
        PlayerCache.io(() -> Stacks.writeFile("boosters.json", root));
    }
}
