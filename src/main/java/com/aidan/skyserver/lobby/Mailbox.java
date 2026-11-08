package com.aidan.skyserver.lobby;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.util.*;

/**
 * Items waiting for a player on their Sky Island: mystery chest prizes, shop items bought in the lobby, and trade items that didn't fit.
 * Delivered automatically when they go to their island, or with /claim. config/skyserver/mailbox.json
 */
public final class Mailbox {
    private static final Map<UUID, List<JsonElement>> MAIL = new HashMap<>();
    private static boolean loaded = false;

    private Mailbox() {}

    private static void load() {
        if (loaded) return;
        loaded = true;
        JsonObject root = Stacks.readFile("mailbox.json");
        for (Map.Entry<String, JsonElement> e : root.entrySet()) {
            List<JsonElement> list = new ArrayList<>();
            for (JsonElement j : e.getValue().getAsJsonArray()) list.add(j);
            MAIL.put(UUID.fromString(e.getKey()), list);
        }
    }

    private static void save() {
        JsonObject root = new JsonObject();
        for (Map.Entry<UUID, List<JsonElement>> e : MAIL.entrySet()) {
            if (e.getValue().isEmpty()) continue;
            JsonArray a = new JsonArray();
            e.getValue().forEach(a::add);
            root.add(e.getKey().toString(), a);
        }
        Stacks.writeFile("mailbox.json", root);
    }

    public static void send(MinecraftServer server, UUID id, ItemStack stack) {
        if (stack.isEmpty()) return;
        load();
        MAIL.computeIfAbsent(id, k -> new ArrayList<>()).add(Stacks.toJson(server, stack));
        save();
    }

    public static int waiting(UUID id) {
        load();
        List<JsonElement> l = MAIL.get(id);
        return l == null ? 0 : l.size();
    }

    /** Puts as much mail as fits into the player's inventory. Returns how many items were delivered. */
    public static int deliver(ServerPlayer p) {
        load();
        List<JsonElement> list = MAIL.get(p.getUUID());
        if (list == null || list.isEmpty()) return 0;
        MinecraftServer server = p.level().getServer();
        int delivered = 0;
        while (!list.isEmpty()) {
            ItemStack stack = Stacks.fromJson(server, list.get(0));
            if (stack.isEmpty()) {
                list.remove(0);
                continue;
            }
            p.getInventory().add(stack); // shrinks the stack by however much fit
            if (stack.isEmpty()) {
                list.remove(0);
                delivered++;
            } else {
                list.set(0, Stacks.toJson(server, stack)); // keep what didn't fit
                break; // inventory full; the rest waits
            }
        }
        save();
        return delivered;
    }
}
