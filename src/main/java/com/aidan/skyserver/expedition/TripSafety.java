package com.aidan.skyserver.expedition;

import com.aidan.skyserver.Safe;
import com.aidan.skyserver.Skyserver;
import com.aidan.skyserver.bots.BotPlayer;
import com.aidan.skyserver.island.Tribute;
import com.aidan.skyserver.lobby.Economy;
import com.aidan.skyserver.lobby.Lobby;
import com.aidan.skyserver.lobby.Stacks;
import com.aidan.skyserver.lobby.Tokens;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.*;

/**
 * 5.27: crash safety for expeditions. While a trip is getting ready or running, what each player paid for it
 * (money, a ticket, City Cash, Sunken farm-item fares, a paid revive) is written to trips-in-progress.json.
 * When the trip ends normally - or the player gets their payment back the normal way - their line is removed.
 *
 * A normal server stop ends trips the normal way (players are hauled home, no refund - same as before), so it
 * leaves nothing behind. If the server CRASHES or loses power, the lines are still in the file on the next start:
 * every one of them becomes a refund, paid the next time that player joins (chat message from Captain Wren).
 * Their inventory is whatever the last world save had (they land in the lobby as usual, island stuff kept).
 *
 * Test trips never record anything (nothing is paid). Kinds: "nether", "end", "city", "sunken".
 */
public final class TripSafety {
    private TripSafety() {}

    private static final String FILE = "trips-in-progress.json";

    /** What one player has paid for one trip. */
    static final class Owe {
        String kind = "";
        int island = -1;
        long money, cityCash;
        final Map<String, Integer> tokens = new HashMap<>();
        final Map<String, Integer> fare = new HashMap<>();

        boolean empty() {
            return money <= 0 && cityCash <= 0 && tokens.isEmpty() && fare.isEmpty();
        }
    }

    /** Trips going on right now: "kind|uuid" -> what they paid. */
    private static final Map<String, Owe> ACTIVE = new HashMap<>();
    /** Refunds waiting for the player to log in (from a crash). */
    private static final Map<UUID, List<Owe>> OWED = new HashMap<>();
    private static boolean loaded;
    private static MinecraftServer server;

    public static void init() {
        ServerLifecycleEvents.SERVER_STARTED.register(s -> {
            server = s;
            Safe.run("trip safety load", TripSafety::load);
        });
        ServerPlayConnectionEvents.JOIN.register((handler, sender, s) -> {
            ServerPlayer p = handler.getPlayer();
            if (p instanceof BotPlayer) return;
            Lobby.runLaterTicks(100, () -> Safe.run("trip refunds", () -> payOwed(p)));
        });
    }

    private static String key(String kind, UUID id) {
        return kind + "|" + id;
    }

    private static Owe entry(UUID id, String kind, int island) {
        load();
        Owe o = ACTIVE.computeIfAbsent(key(kind, id), k -> new Owe());
        o.kind = kind;
        if (island >= 0) o.island = island;
        return o;
    }

    /** Money paid toward a trip (a price, a deposit, the rest of a price, a paid revive). */
    static void money(UUID id, String kind, int island, long amount) {
        if (id == null || amount <= 0) return;
        entry(id, kind, island).money += amount;
        save();
    }

    /** A ticket (or any token) used for a trip. */
    static void token(UUID id, String kind, int island, String token, int count) {
        if (id == null || token == null || count <= 0) return;
        entry(id, kind, island).tokens.merge(token, count, Integer::sum);
        save();
    }

    /** City Cash paid for an Ancient City place. */
    static void cityCash(UUID id, String kind, int island, long amount) {
        if (id == null || amount <= 0) return;
        entry(id, kind, island).cityCash += amount;
        save();
    }

    /** Sunken Monument fare taken from the island's Tribute Chests (item id -> count; given back there). */
    static void fare(UUID id, String kind, int island, Map<String, Integer> fare) {
        if (id == null || fare == null || fare.isEmpty()) return;
        Owe o = entry(id, kind, island);
        fare.forEach((k, v) -> o.fare.merge(k, v, Integer::sum));
        save();
    }

    /** This player got their payment back the normal way, or their part of the trip is over. */
    static void clear(UUID id, String kind) {
        if (id == null) return;
        load();
        if (ACTIVE.remove(key(kind, id)) != null) save();
    }

    static void clearAll(String kind, Collection<UUID> ids) {
        if (ids == null) return;
        load();
        boolean changed = false;
        for (UUID id : ids) changed |= ACTIVE.remove(key(kind, id)) != null;
        if (changed) save();
    }

    /** A trip from this island ended normally (Nether: one trip per island, so this clears the deposit payer too). */
    static void clearIsland(String kind, int island) {
        load();
        if (ACTIVE.values().removeIf(o -> o.kind.equals(kind) && o.island == island)) save();
    }

    // =====================================================================
    // Loading (a crash leaves lines behind) and paying back
    // =====================================================================

    private static void load() {
        if (loaded) return;
        loaded = true;
        JsonObject root = Stacks.readFile(FILE);
        int crashed = 0;
        try {
            if (root.has("owed")) {
                for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("owed").entrySet()) {
                    List<Owe> list = new ArrayList<>();
                    for (JsonElement el : e.getValue().getAsJsonArray()) list.add(read(el.getAsJsonObject()));
                    OWED.put(UUID.fromString(e.getKey()), list);
                }
            }
            // Anything still "active" in the file was running when the server went down without a normal stop.
            if (root.has("active")) {
                for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("active").entrySet()) {
                    int bar = e.getKey().indexOf('|');
                    if (bar < 0) continue;
                    Owe o = read(e.getValue().getAsJsonObject());
                    if (o.empty()) continue;
                    OWED.computeIfAbsent(UUID.fromString(e.getKey().substring(bar + 1)), k -> new ArrayList<>()).add(o);
                    crashed++;
                }
            }
        } catch (RuntimeException ex) {
            Skyserver.LOGGER.error("[Trips] couldn't read {} fully: {}", FILE, ex.toString());
        }
        if (crashed > 0) Skyserver.LOGGER.warn("[Trips] the server stopped mid-trip: {} refund(s) will be paid when those players join", crashed);
        save();
    }

    private static Owe read(JsonObject o) {
        Owe w = new Owe();
        if (o.has("kind")) w.kind = o.get("kind").getAsString();
        if (o.has("island")) w.island = o.get("island").getAsInt();
        if (o.has("money")) w.money = o.get("money").getAsLong();
        if (o.has("cc")) w.cityCash = o.get("cc").getAsLong();
        if (o.has("tokens")) for (Map.Entry<String, JsonElement> e : o.getAsJsonObject("tokens").entrySet()) w.tokens.put(e.getKey(), e.getValue().getAsInt());
        if (o.has("fare")) for (Map.Entry<String, JsonElement> e : o.getAsJsonObject("fare").entrySet()) w.fare.put(e.getKey(), e.getValue().getAsInt());
        return w;
    }

    private static JsonObject write(Owe w) {
        JsonObject o = new JsonObject();
        o.addProperty("kind", w.kind);
        o.addProperty("island", w.island);
        o.addProperty("money", w.money);
        o.addProperty("cc", w.cityCash);
        JsonObject t = new JsonObject();
        w.tokens.forEach(t::addProperty);
        o.add("tokens", t);
        JsonObject f = new JsonObject();
        w.fare.forEach(f::addProperty);
        o.add("fare", f);
        return o;
    }

    private static void save() {
        JsonObject root = new JsonObject(), active = new JsonObject(), owed = new JsonObject();
        ACTIVE.forEach((k, v) -> { if (!v.empty()) active.add(k, write(v)); });
        OWED.forEach((id, list) -> {
            JsonArray arr = new JsonArray();
            for (Owe w : list) arr.add(write(w));
            owed.add(id.toString(), arr);
        });
        root.add("active", active);
        root.add("owed", owed);
        Stacks.writeFile(FILE, root);
    }

    private static String tripName(String kind) {
        return switch (kind) {
            case "nether" -> "Nether Expedition";
            case "end" -> "End Expedition";
            case "city" -> "Ancient City";
            case "sunken" -> "Sunken Monument";
            default -> "expedition";
        };
    }

    /** On join: pays back every trip the server lost in a crash. */
    private static void payOwed(ServerPlayer p) {
        if (p == null || p.hasDisconnected() || server == null) return;
        load();
        List<Owe> list = OWED.remove(p.getUUID());
        if (list == null || list.isEmpty()) return;
        save();
        UUID id = p.getUUID();
        for (Owe w : list) {
            List<String> parts = new ArrayList<>();
            if (w.money > 0) {
                Economy.add(server, id, p.getName().getString(), w.money);
                parts.add("§6" + Economy.format(w.money));
            }
            w.tokens.forEach((t, n) -> {
                Tokens.add(id, t, n);
                parts.add("§f" + n + " ticket" + (n == 1 ? "" : "s"));
            });
            if (w.cityCash > 0) {
                CityMarket.earn(id, (int) Math.min(Integer.MAX_VALUE, w.cityCash), "trip refund (server crash)");
                parts.add("§b" + CityMarket.fmt(w.cityCash) + " City Cash");
            }
            if (!w.fare.isEmpty() && w.island >= 0) {
                Tribute.giveBack(w.island, id, w.fare);
                parts.add("§fyour fare (back in the Tribute Chests)");
            }
            if (parts.isEmpty()) continue;
            Lobby.msg(p, "§6[Captain Wren] §fThe server went down in the middle of your " + tripName(w.kind) + ", so it's on me: "
                    + String.join("§7, ", parts) + " §fback.");
            Skyserver.LOGGER.info("[Trips] refunded {} for a {} lost in a crash", p.getName().getString(), w.kind);
        }
    }
}
