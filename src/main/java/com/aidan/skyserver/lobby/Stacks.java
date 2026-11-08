package com.aidan.skyserver.lobby;

import com.aidan.skyserver.Skyserver;
import com.google.gson.*;
import com.mojang.serialization.JsonOps;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Small helpers: items <-> JSON, named items, tagged items, and JSON files in config/skyserver/. */
public final class Stacks {
    static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    static final Path DIR = Path.of("config", "skyserver");

    private Stacks() {}

    // ---- Items as JSON (keeps enchantments, names and everything else) ----

    public static JsonElement toJson(MinecraftServer server, ItemStack stack) {
        if (stack.isEmpty()) return JsonNull.INSTANCE;
        RegistryOps<JsonElement> ops = RegistryOps.create(JsonOps.INSTANCE, server.registryAccess());
        return ItemStack.CODEC.encodeStart(ops, stack).result().orElse(JsonNull.INSTANCE);
    }

    public static ItemStack fromJson(MinecraftServer server, JsonElement json) {
        if (json == null || json.isJsonNull()) return ItemStack.EMPTY;
        RegistryOps<JsonElement> ops = RegistryOps.create(JsonOps.INSTANCE, server.registryAccess());
        return ItemStack.CODEC.parse(ops, json).result().orElse(ItemStack.EMPTY);
    }

    // ---- Named / tagged items ----

    public static ItemStack named(ItemStack stack, String name, String... lore) {
        stack.set(DataComponents.CUSTOM_NAME, Component.literal(name));
        if (lore.length > 0) {
            List<Component> lines = new ArrayList<>();
            for (String l : lore) lines.add(Component.literal(l));
            stack.set(DataComponents.LORE, new ItemLore(lines));
        }
        return stack;
    }

    /** Lets this tool break only these blocks in adventure mode (the client won't even try otherwise). */
    public static ItemStack canBreak(ItemStack stack, net.minecraft.world.level.block.Block... blocks) {
        var set = net.minecraft.core.HolderSet.direct(net.minecraft.world.level.block.Block::builtInRegistryHolder, List.of(blocks));
        var pred = new net.minecraft.advancements.predicates.BlockPredicate(java.util.Optional.of(set), java.util.Optional.empty(),
                java.util.Optional.empty(), net.minecraft.advancements.predicates.DataComponentMatchers.ANY);
        stack.set(DataComponents.CAN_BREAK, new net.minecraft.world.item.AdventureModePredicate(List.of(pred)));
        return stack;
    }

    /** Marks an item as one of ours ("skyserver" = id), so we can recognise it later. */
    public static ItemStack tag(ItemStack stack, String id) {
        CompoundTag t = new CompoundTag();
        t.putString("skyserver", id);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(t));
        return stack;
    }

    public static boolean is(ItemStack stack, String id) {
        if (stack.isEmpty()) return false;
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) return false;
        CompoundTag t = new CompoundTag();
        t.putString("skyserver", id);
        return data.matchedBy(t);
    }

    // ---- JSON files ----

    public static JsonObject readFile(String name) {
        Path p = DIR.resolve(name);
        try {
            if (Files.exists(p)) return JsonParser.parseString(Files.readString(p, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (Exception e) {
            Skyserver.LOGGER.warn("Couldn't read {}: {}", p, e.toString());
        }
        return new JsonObject();
    }

    public static void writeFile(String name, JsonObject json) {
        Path p = DIR.resolve(name);
        try {
            Files.createDirectories(p.getParent());
            Path tmp = p.resolveSibling(p.getFileName() + ".tmp");
            Files.writeString(tmp, GSON.toJson(json), StandardCharsets.UTF_8);
            Files.move(tmp, p, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            Skyserver.LOGGER.warn("Couldn't save {}: {}", p, e.toString());
        }
    }
}
