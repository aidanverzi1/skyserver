package com.aidan.skyserver.lobby;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.*;

/**
 * 5.19: Wren's Charts - Captain Wren's sea chart of every expedition. One stamp per difficulty cleared:
 *
 *   Nether Expedition  - Easy, Normal, Hard, Nightmare (beat the boss)
 *   End Expedition     - wave 10, wave 20, wave 30 (the End has no difficulties: its waves are the steps)
 *   Ancient City       - Easy, Normal, Hard, Nightmare (escape with the relic)
 *   Sunken Monument    - Easy, Normal, Hard, Nightmare (beat the Tidemother) - 5.20
 *
 * A full chart gives the title "Wren's Cartographer" and a Legendary key, once. /charts shows yours.
 * Saved in config/skyserver/charts.json.
 */
public final class Charts {
    private Charts() {}

    public static final String TITLE = "title_cartographer";
    /** {stamp id, row, label}. Rows are shown in this order. */
    private static final String[][] STAMPS = {
            {"nether_0", "§cNether Expedition", "§aEasy"}, {"nether_1", "§cNether Expedition", "§eNormal"},
            {"nether_2", "§cNether Expedition", "§cHard"}, {"nether_3", "§cNether Expedition", "§4Nightmare"},
            {"end_10", "§5End Expedition", "§dWave 10"}, {"end_20", "§5End Expedition", "§dWave 20"}, {"end_30", "§5End Expedition", "§5Wave 30"},
            {"city_0", "§3Ancient City", "§aEasy"}, {"city_1", "§3Ancient City", "§eNormal"},
            {"city_2", "§3Ancient City", "§cHard"}, {"city_3", "§3Ancient City", "§4Nightmare"},
            {"sunken_0", "§bSunken Monument", "§aEasy"}, {"sunken_1", "§bSunken Monument", "§eNormal"},
            {"sunken_2", "§bSunken Monument", "§cHard"}, {"sunken_3", "§bSunken Monument", "§4Nightmare"}};
    private static final Map<UUID, Set<String>> DATA = new HashMap<>();
    private static final Set<UUID> REWARDED = new HashSet<>();
    private static boolean loaded = false;

    public static void init() {
        CommandRegistrationCallback.EVENT.register((d, r, e) -> d.register(Commands.literal("charts")
                .executes(c -> { show(c.getSource().getPlayerOrException()); return 1; })));
    }

    /** Stamps a chart (nothing happens if it already has it). Bots are ignored. */
    public static void stamp(ServerPlayer p, String id) {
        if (p == null || com.aidan.skyserver.bots.BotManager.isBot(p.getUUID())) return;
        load();
        String label = null;
        for (String[] s : STAMPS) if (s[0].equals(id)) label = s[1] + " §7- " + s[2];
        if (label == null) return;
        Set<String> mine = DATA.computeIfAbsent(p.getUUID(), k -> new HashSet<>());
        if (!mine.add(id)) return;
        save();
        int have = count(mine);
        p.sendSystemMessage(Component.literal("§6§l✎ WREN'S CHARTS §7New stamp: " + label + " §7(" + have + "/" + STAMPS.length + ") ")
                .append(Component.literal("§e[/charts]").withStyle(Style.EMPTY.withClickEvent(new ClickEvent.RunCommand("/charts")))));
        Announce.ding(p, 1.8f);
        if (have >= STAMPS.length && REWARDED.add(p.getUUID())) {
            save();
            MinecraftServer server = p.level().getServer();
            PlayerProfile prof = PlayerCache.get(p);
            if (prof.owned.add(TITLE)) prof.touch();
            Keys.reward(server, p.getUUID(), 4, 1, "(Wren's Charts complete)");
            Announce.title(p, "§6§lCHART COMPLETE", "§7Title: " + Progression.cosmeticName(TITLE) + " §7+ a Legendary key", 10, 70, 20);
            server.getPlayerList().broadcastSystemMessage(Component.literal("§6§l✎ §e" + p.getName().getString()
                    + " §7completed §6Wren's Charts §7- every expedition at every difficulty!"), false);
        }
    }

    /** 5.21: does this player have this stamp? (Old Ves's shelves unlock with Ancient City stamps.) */
    public static boolean has(UUID id, String stamp) {
        load();
        Set<String> mine = DATA.get(id);
        return mine != null && mine.contains(stamp);
    }

    private static int count(Set<String> mine) {
        int n = 0;
        for (String[] s : STAMPS) if (mine.contains(s[0])) n++;
        return n;
    }

    public static void show(ServerPlayer p) {
        load();
        Set<String> mine = DATA.getOrDefault(p.getUUID(), Set.of());
        Lobby.msg(p, "§6§l✎ WREN'S CHARTS §7(" + count(mine) + "/" + STAMPS.length + ")");
        String row = null;
        StringBuilder sb = new StringBuilder();
        for (String[] s : STAMPS) {
            if (!s[1].equals(row)) {
                if (row != null) Lobby.msg(p, sb.toString());
                row = s[1];
                sb = new StringBuilder(" " + row + "§7: ");
            }
            sb.append(mine.contains(s[0]) ? "§a✔ " : "§8✘ ").append(s[2]).append("  ");
        }
        if (row != null) Lobby.msg(p, sb.toString());
        Lobby.msg(p, REWARDED.contains(p.getUUID()) ? "§a Chart complete! §7(" + Progression.cosmeticName(TITLE) + "§7)"
                : "§7 Fill it all: the §6Wren's Cartographer §7title and a §6Legendary key§7.");
    }

    private static void load() {
        if (loaded) return;
        loaded = true;
        JsonObject root = Stacks.readFile("charts.json");
        try {
            if (root.has("stamps")) for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("stamps").entrySet()) {
                Set<String> s = new HashSet<>();
                for (JsonElement x : e.getValue().getAsJsonArray()) s.add(x.getAsString());
                DATA.put(UUID.fromString(e.getKey()), s);
            }
            if (root.has("rewarded")) for (JsonElement x : root.getAsJsonArray("rewarded")) REWARDED.add(UUID.fromString(x.getAsString()));
        } catch (RuntimeException ex) {
            com.aidan.skyserver.Skyserver.LOGGER.warn("Couldn't read charts.json fully: {}", ex.toString());
        }
    }

    private static void save() {
        JsonObject root = new JsonObject(), stamps = new JsonObject();
        DATA.forEach((id, s) -> {
            JsonArray a = new JsonArray();
            for (String x : s) a.add(x);
            stamps.add(id.toString(), a);
        });
        root.add("stamps", stamps);
        JsonArray rw = new JsonArray();
        for (UUID id : REWARDED) rw.add(id.toString());
        root.add("rewarded", rw);
        PlayerCache.io(() -> Stacks.writeFile("charts.json", root));
    }
}
