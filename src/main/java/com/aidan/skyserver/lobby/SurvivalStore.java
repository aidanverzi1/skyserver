package com.aidan.skyserver.lobby;

import com.aidan.skyserver.Skyserver;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.protocol.game.ClientboundSetExperiencePacket;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.Set;

/**
 * Each player's survival state while they're in the lobby or a minigame: inventory, position,
 * health, hunger, XP and potion effects. Saved to config/skyserver/survival/<uuid>.json when
 * they leave survival, and given back when they return.
 */
public final class SurvivalStore {
    private SurvivalStore() {}

    private static String file(ServerPlayer p) {
        return "survival/" + p.getUUID() + ".json";
    }

    public static boolean has(ServerPlayer p) {
        return java.nio.file.Files.exists(Stacks.DIR.resolve(file(p)));
    }

    /** Saves the player's current survival state (call while they're still in survival). */
    public static void save(ServerPlayer p) {
        MinecraftServer server = p.level().getServer();
        JsonObject o = new JsonObject();
        o.addProperty("dimension", p.level().dimension().identifier().toString());
        o.addProperty("x", p.getX());
        o.addProperty("y", p.getY());
        o.addProperty("z", p.getZ());
        o.addProperty("yaw", p.getYRot());
        o.addProperty("pitch", p.getXRot());
        o.addProperty("mode", p.gameMode.getGameModeForPlayer().getName());
        o.addProperty("health", p.getHealth());
        o.addProperty("food", p.getFoodData().getFoodLevel());
        o.addProperty("saturation", p.getFoodData().getSaturationLevel());
        o.addProperty("xpLevel", p.experienceLevel);
        o.addProperty("xpProgress", p.experienceProgress);
        o.addProperty("xpTotal", p.totalExperience);
        JsonArray items = new JsonArray();
        Inventory inv = p.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (s.isEmpty()) continue;
            JsonObject e = new JsonObject();
            e.addProperty("slot", i);
            e.add("item", Stacks.toJson(server, s));
            items.add(e);
        }
        o.add("items", items);
        JsonArray effects = new JsonArray();
        try {
            RegistryOps<JsonElement> ops = RegistryOps.create(JsonOps.INSTANCE, server.registryAccess());
            for (MobEffectInstance eff : p.getActiveEffects()) {
                MobEffectInstance.CODEC.encodeStart(ops, eff).result().ifPresent(effects::add);
            }
        } catch (Throwable t) {
            Skyserver.LOGGER.warn("Couldn't save effects for {}: {}", p.getName().getString(), t.toString());
        }
        o.add("effects", effects);
        Stacks.writeFile(file(p), o);
    }

    /** Puts the player back into survival exactly as they left it (or at world spawn the first time). */
    public static void restore(ServerPlayer p) {
        MinecraftServer server = p.level().getServer();
        JsonObject o = Stacks.readFile(file(p));
        Inventory inv = p.getInventory();
        inv.clearContent();
        p.removeAllEffects();
        ServerLevel level = server.overworld();
        double x, y, z;
        float yaw = 0, pitch = 0;
        GameType mode = GameType.SURVIVAL;
        if (o.has("dimension")) {
            ResourceKey<Level> key = ResourceKey.create(Registries.DIMENSION, Identifier.parse(o.get("dimension").getAsString()));
            ServerLevel saved = server.getLevel(key);
            if (saved != null && !Lobby.isGameLevel(saved)) level = saved;
            x = o.get("x").getAsDouble();
            y = o.get("y").getAsDouble();
            z = o.get("z").getAsDouble();
            yaw = o.get("yaw").getAsFloat();
            pitch = o.get("pitch").getAsFloat();
            GameType m = GameType.byName(o.get("mode").getAsString(), GameType.SURVIVAL);
            if (m != GameType.SPECTATOR) mode = m;
            for (JsonElement e : o.getAsJsonArray("items")) {
                JsonObject eo = e.getAsJsonObject();
                int slot = eo.get("slot").getAsInt();
                if (slot >= 0 && slot < inv.getContainerSize()) inv.setItem(slot, Stacks.fromJson(server, eo.get("item")));
            }
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
                Skyserver.LOGGER.warn("Couldn't restore effects for {}: {}", p.getName().getString(), t.toString());
            }
        } else {
            // First time: the overworld's surface at 0, 0 (normally the world spawn area).
            x = 0.5;
            z = 0.5;
            y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, 0, 0) + 0.1;
            p.setHealth(p.getMaxHealth());
            p.getFoodData().setFoodLevel(20);
            p.experienceLevel = 0;
            p.experienceProgress = 0;
            p.totalExperience = 0;
        }
        p.connection.send(new ClientboundSetExperiencePacket(p.experienceProgress, p.totalExperience, p.experienceLevel));
        p.setGameMode(mode);
        p.resetFallDistance();
        p.teleportTo(level, x, y, z, Set.of(), yaw, pitch, true);
    }
}
