package com.aidan.skyserver.island;

import com.aidan.skyserver.Skyserver;
import com.aidan.skyserver.lobby.Stacks;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import net.minecraft.network.protocol.game.ClientboundSetExperiencePacket;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.item.ItemStack;

/**
 * Each player's Sky Island inventory, ender chest, XP, health, hunger and effects, kept apart from
 * survival so items can never move between the two. Saved to config/skyserver/island/<uuid>.json
 * when they leave the islands, and given back when they return.
 *
 * The vanilla ender chest normally holds the player's survival ender chest. While they're on the
 * islands it holds their island one instead; leaving swaps it back.
 */
public final class IslandStore {
    private IslandStore() {}

    private static String file(ServerPlayer p) {
        return "island/" + p.getUUID() + ".json";
    }

    private static String survivalEnderFile(ServerPlayer p) {
        return "island/survival-ender-" + p.getUUID() + ".json";
    }

    /** Leaving the islands: saves the island state and puts the survival ender chest back. */
    public static void save(ServerPlayer p) {
        MinecraftServer server = p.level().getServer();
        JsonObject o = new JsonObject();
        o.add("items", writeContainer(server, p.getInventory()));
        o.add("ender", writeContainer(server, p.getEnderChestInventory()));
        o.addProperty("health", p.getHealth());
        o.addProperty("food", p.getFoodData().getFoodLevel());
        o.addProperty("saturation", p.getFoodData().getSaturationLevel());
        o.addProperty("xpLevel", p.experienceLevel);
        o.addProperty("xpProgress", p.experienceProgress);
        o.addProperty("xpTotal", p.totalExperience);
        JsonArray effects = new JsonArray();
        try {
            RegistryOps<JsonElement> ops = RegistryOps.create(JsonOps.INSTANCE, server.registryAccess());
            for (MobEffectInstance eff : p.getActiveEffects()) MobEffectInstance.CODEC.encodeStart(ops, eff).result().ifPresent(effects::add);
        } catch (Throwable t) {
            Skyserver.LOGGER.warn("Couldn't save island effects for {}: {}", p.getName().getString(), t.toString());
        }
        o.add("effects", effects);
        Stacks.writeFile(file(p), o);
        // Survival's ender chest goes back in.
        JsonObject surv = Stacks.readFile(survivalEnderFile(p));
        p.getEnderChestInventory().clearContent();
        if (surv.has("ender")) readContainer(server, surv.getAsJsonArray("ender"), p.getEnderChestInventory());
        p.getInventory().clearContent();
        p.removeAllEffects();
    }

    /** Arriving on the islands: stores the survival ender chest and loads the island state. */
    public static void restore(ServerPlayer p) {
        MinecraftServer server = p.level().getServer();
        JsonObject surv = new JsonObject();
        surv.add("ender", writeContainer(server, p.getEnderChestInventory()));
        Stacks.writeFile(survivalEnderFile(p), surv);

        JsonObject o = Stacks.readFile(file(p));
        p.getInventory().clearContent();
        p.getEnderChestInventory().clearContent();
        p.removeAllEffects();
        if (o.has("items")) {
            readContainer(server, o.getAsJsonArray("items"), p.getInventory());
            if (o.has("ender")) readContainer(server, o.getAsJsonArray("ender"), p.getEnderChestInventory());
            p.setHealth(Math.max(1f, Math.min(p.getMaxHealth(), o.get("health").getAsFloat())));
            p.getFoodData().setFoodLevel(o.get("food").getAsInt());
            p.getFoodData().setSaturation(o.get("saturation").getAsFloat());
            p.experienceLevel = o.get("xpLevel").getAsInt();
            p.experienceProgress = o.get("xpProgress").getAsFloat();
            p.totalExperience = o.get("xpTotal").getAsInt();
            try {
                RegistryOps<JsonElement> ops = RegistryOps.create(JsonOps.INSTANCE, server.registryAccess());
                if (o.has("effects")) for (JsonElement e : o.getAsJsonArray("effects"))
                    MobEffectInstance.CODEC.parse(ops, e).result().ifPresent(p::addEffect);
            } catch (Throwable t) {
                Skyserver.LOGGER.warn("Couldn't restore island effects for {}: {}", p.getName().getString(), t.toString());
            }
        } else {
            p.setHealth(p.getMaxHealth());
            p.getFoodData().setFoodLevel(20);
            p.getFoodData().setSaturation(5f);
            p.experienceLevel = 0;
            p.experienceProgress = 0;
            p.totalExperience = 0;
        }
        p.connection.send(new ClientboundSetExperiencePacket(p.experienceProgress, p.totalExperience, p.experienceLevel));
    }

    private static JsonArray writeContainer(MinecraftServer server, Container c) {
        JsonArray items = new JsonArray();
        for (int i = 0; i < c.getContainerSize(); i++) {
            ItemStack s = c.getItem(i);
            if (s.isEmpty()) continue;
            JsonObject e = new JsonObject();
            e.addProperty("slot", i);
            e.add("item", Stacks.toJson(server, s));
            items.add(e);
        }
        return items;
    }

    private static void readContainer(MinecraftServer server, JsonArray items, Container c) {
        for (JsonElement e : items) {
            JsonObject eo = e.getAsJsonObject();
            int slot = eo.get("slot").getAsInt();
            if (slot >= 0 && slot < c.getContainerSize()) c.setItem(slot, Stacks.fromJson(server, eo.get("item")));
        }
    }
}
