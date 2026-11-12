package com.aidan.skyserver.lobby;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.*;

/**
 * 5.8: things players own that aren't items or money, kept by the server (config/skyserver/tokens.json):
 * Key Shards, Island Upgrade Vouchers, Island Theme tokens, Nether Expedition tickets and unused
 * boosters. Also hands out the "token" prizes from mystery chests (see Loot.token).
 *
 * Token prize strings:
 *   shards:N            N Key Shards
 *   booster:xp:MIN:PCT  a personal booster for the bag (/boosters), e.g. booster:money:30:25
 *   booster:server:MIN:PCT  a server-wide money booster for the bag
 *   upgrade_voucher     25% off the next island upgrade
 *   theme               an Island Theme (/is theme)
 *   ticket:D            a Nether Expedition ticket (0 Easy .. 3 Nightmare)
 *   passxp:N            battle pass XP
 *   cosmetic:R          a cosmetic of rarity R (0 common, 1 rare, 2 legendary incl. exclusives); shards if you own them all
 */
public final class Tokens {
    public static final String SHARDS = "shards", UPGRADE_VOUCHER = "upgrade_voucher", THEME = "theme";
    public static final String[] TICKET = {"ticket_0", "ticket_1", "ticket_2", "ticket_3"};
    /** 5.18: a free place on the End Expedition. */
    public static final String END_TICKET = "ticket_end";

    private static final Map<UUID, Map<String, Integer>> DATA = new HashMap<>();
    private static boolean loaded = false;

    private Tokens() {}

    private static void load() {
        if (loaded) return;
        loaded = true;
        JsonObject root = Stacks.readFile("tokens.json");
        for (Map.Entry<String, JsonElement> e : root.entrySet()) {
            if (!e.getValue().isJsonObject()) continue;
            Map<String, Integer> m = new LinkedHashMap<>();
            for (Map.Entry<String, JsonElement> t : e.getValue().getAsJsonObject().entrySet()) m.put(t.getKey(), t.getValue().getAsInt());
            try {
                DATA.put(UUID.fromString(e.getKey()), m);
            } catch (IllegalArgumentException ignored) {
            }
        }
    }

    private static void save() {
        JsonObject root = new JsonObject();
        DATA.forEach((id, m) -> {
            JsonObject o = new JsonObject();
            m.forEach((k, v) -> { if (v != 0) o.addProperty(k, v); });
            root.add(id.toString(), o);
        });
        PlayerCache.io(() -> Stacks.writeFile("tokens.json", root));
    }

    public static int count(UUID id, String token) {
        load();
        Map<String, Integer> m = DATA.get(id);
        return m == null ? 0 : m.getOrDefault(token, 0);
    }

    /** Adds (or with a negative amount, takes) tokens. Never goes below 0. */
    public static void add(UUID id, String token, int amount) {
        load();
        Map<String, Integer> m = DATA.computeIfAbsent(id, k -> new LinkedHashMap<>());
        m.put(token, Math.max(0, m.getOrDefault(token, 0) + amount));
        save();
    }

    /** Takes tokens if there are enough. */
    public static boolean take(UUID id, String token, int amount) {
        if (count(id, token) < amount) return false;
        add(id, token, -amount);
        return true;
    }

    /** All tokens of a player whose name starts with prefix (e.g. "booster:"). */
    public static Map<String, Integer> withPrefix(UUID id, String prefix) {
        load();
        Map<String, Integer> out = new LinkedHashMap<>();
        Map<String, Integer> m = DATA.get(id);
        if (m != null) m.forEach((k, v) -> { if (k.startsWith(prefix) && v > 0) out.put(k, v); });
        return out;
    }

    public static int shards(UUID id) {
        return count(id, SHARDS);
    }

    public static final String[] DIFF_NAMES = {"Easy", "Normal", "Hard", "Nightmare"};

    /** Hands out a token prize. Returns what was given (for the chat line). */
    public static String grant(ServerPlayer p, String token) {
        MinecraftServer server = p.level().getServer();
        String[] t = token.split(":");
        switch (t[0]) {
            case "shards" -> {
                int n = Integer.parseInt(t[1]);
                add(p.getUUID(), SHARDS, n);
                return "§d" + n + " Key Shards §7(you have " + shards(p.getUUID()) + ", §e/shards§7)";
            }
            case "booster" -> {
                add(p.getUUID(), token, 1);
                return Boosters.describe(token) + " §7(use it any time: §e/boosters§7)";
            }
            case "upgrade_voucher" -> {
                add(p.getUUID(), UPGRADE_VOUCHER, 1);
                return "§dIsland Upgrade Voucher §7(25% off your next island upgrade, used automatically)";
            }
            case "theme" -> {
                add(p.getUUID(), THEME, 1);
                return "§aIsland Theme §7(change your island's look: §e/is theme§7)";
            }
            case "endticket" -> {
                add(p.getUUID(), END_TICKET, 1);
                return "§5End Expedition Ticket §7(use it from §e/is §7- a free place on the End Expedition)";
            }
            case "ticket" -> {
                int d = Integer.parseInt(t[1]);
                add(p.getUUID(), TICKET[d], 1);
                return "§c" + DIFF_NAMES[d] + " Nether Expedition Ticket §7(use it from §e/is §7- a free trip)";
            }
            case "passxp" -> {
                int n = Integer.parseInt(t[1]);
                BattlePass.addPassXp(p, n);
                return "§b+" + n + " battle pass XP";
            }
            case "cosmetic" -> {
                return Cosmetics.grantRandom(p, Integer.parseInt(t[1]));
            }
            default -> {
                return "§7" + token;
            }
        }
    }
}
