package com.aidan.skyserver.lobby;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.*;

/**
 * 5.13: three new kinds of cosmetics, all vanilla-client friendly.
 *  - Skywars cages ("cage_..."): the cage you wait in before a Skywars match (SwVoyage swaps the blocks).
 *  - Bed-break effects ("bedfx_..."): play where a bed you break was (BwVoyage spots the break).
 *  - Name colours ("color_..."): your name's colour in chat, the tab list and over your head (Ranks).
 *    In a game your team colour shows instead.
 * The lists (CosmeticLists, plain Java) join the 5.8 cosmetics in Progression (shops, chests, Shard Shop). Rarity "2" in the
 * last column = stamp-book only (Wren's Weekly Voyage): never in a shop or a chest.
 * What a player wears of these kinds is saved here (cosmetics-extra.json), not in PlayerProfile.
 * /cosmetics equip <id> and /cosmetics clear also handle these kinds (wrapped in at server start).
 */
public final class CosmeticsPlus {
    private CosmeticsPlus() {}

    public static boolean isExtraKind(String id) {
        return CosmeticLists.isExtraKind(id);
    }

    // =====================================================================
    // What each player wears (cage, bed effect, name colour)
    // =====================================================================

    private static final Map<UUID, String[]> WORN = new HashMap<>(); // {cage, bedfx, color}
    private static boolean loaded = false;

    private static void load() {
        if (loaded) return;
        loaded = true;
        JsonObject root = Stacks.readFile("cosmetics-extra.json");
        for (Map.Entry<String, JsonElement> e : root.entrySet()) {
            JsonObject o = e.getValue().getAsJsonObject();
            WORN.put(UUID.fromString(e.getKey()), new String[]{str(o, "cage"), str(o, "bedfx"), str(o, "color")});
        }
    }

    private static String str(JsonObject o, String k) {
        return o.has(k) ? o.get(k).getAsString() : null;
    }

    private static void save() {
        JsonObject root = new JsonObject();
        WORN.forEach((id, w) -> {
            JsonObject o = new JsonObject();
            if (w[0] != null) o.addProperty("cage", w[0]);
            if (w[1] != null) o.addProperty("bedfx", w[1]);
            if (w[2] != null) o.addProperty("color", w[2]);
            if (o.size() > 0) root.add(id.toString(), o);
        });
        PlayerCache.io(() -> Stacks.writeFile("cosmetics-extra.json", root));
    }

    private static String[] worn(UUID id) {
        load();
        return WORN.computeIfAbsent(id, k -> new String[3]);
    }

    public static String cage(UUID id) {
        return worn(id)[0];
    }

    public static String bedFx(UUID id) {
        return worn(id)[1];
    }

    public static String color(UUID id) {
        return worn(id)[2];
    }

    /** Puts on a cage / bed effect / name colour the player owns. */
    public static void equip(ServerPlayer p, String id) {
        PlayerProfile prof = PlayerCache.get(p);
        if (!prof.owned.contains(id)) {
            Lobby.msg(p, "§cYou don't own that cosmetic. §7/cosmetics");
            return;
        }
        String[] w = worn(p.getUUID());
        if (CosmeticLists.isCage(id)) w[0] = id;
        else if (CosmeticLists.isBedFx(id)) w[1] = id;
        else if (CosmeticLists.isColor(id)) w[2] = id;
        save();
        Lobby.msg(p, "§aNow using " + Progression.cosmeticName(id) + "§a.");
    }

    public static void clear(ServerPlayer p) {
        String[] w = worn(p.getUUID());
        Arrays.fill(w, null);
        save();
    }

    // =====================================================================
    // Skywars cage blocks
    // =====================================================================

    /** {wall block, roof block} for a cage, or null for the normal glass cage. */
    public static BlockState[] cageBlocks(String id) {
        if (id == null) return null;
        return switch (id) {
            case "cage_oak" -> new BlockState[]{Blocks.OAK_FENCE.defaultBlockState(), Blocks.OAK_PLANKS.defaultBlockState()};
            case "cage_ice" -> new BlockState[]{Blocks.ICE.defaultBlockState(), Blocks.PACKED_ICE.defaultBlockState()};
            case "cage_moss" -> new BlockState[]{Blocks.MOSSY_STONE_BRICK_WALL.defaultBlockState(), Blocks.MOSSY_STONE_BRICKS.defaultBlockState()};
            case "cage_honey" -> new BlockState[]{Blocks.HONEY_BLOCK.defaultBlockState(), Blocks.HONEYCOMB_BLOCK.defaultBlockState()};
            case "cage_amethyst" -> new BlockState[]{Blocks.AMETHYST_BLOCK.defaultBlockState(), Blocks.BUDDING_AMETHYST.defaultBlockState()};
            case "cage_nether" -> new BlockState[]{Blocks.NETHER_BRICK_FENCE.defaultBlockState(), Blocks.NETHER_BRICKS.defaultBlockState()};
            case "cage_gold" -> new BlockState[]{Blocks.GOLD_BLOCK.defaultBlockState(), Blocks.DIAMOND_BLOCK.defaultBlockState()};
            case "cage_crowsnest" -> new BlockState[]{Blocks.SPRUCE_FENCE.defaultBlockState(), Blocks.WOOL.pick(net.minecraft.world.item.DyeColor.WHITE).defaultBlockState()};
            default -> null;
        };
    }

    // =====================================================================
    // Bed-break effects
    // =====================================================================

    /** Plays the breaker's bed-break effect at the bed's spot. */
    public static void bedBreak(ServerPlayer breaker, ServerLevel level, BlockPos bed) {
        String fx = bedFx(breaker.getUUID());
        if (fx == null) return;
        double x = bed.getX() + 0.5, y = bed.getY() + 0.8, z = bed.getZ() + 0.5;
        switch (fx) {
            case "bedfx_feather" -> {
                level.sendParticles(ParticleTypes.CLOUD, x, y, z, 40, 0.8, 0.4, 0.8, 0.05);
                level.playSound(null, x, y, z, SoundEvents.CHICKEN_EGG, SoundSource.PLAYERS, 1.5f, 0.8f);
            }
            case "bedfx_splash" -> {
                level.sendParticles(ParticleTypes.SPLASH, x, y, z, 120, 1.0, 0.5, 1.0, 0.3);
                level.playSound(null, x, y, z, SoundEvents.PLAYER_SPLASH, SoundSource.PLAYERS, 1.5f, 1f);
            }
            case "bedfx_note" -> {
                level.sendParticles(ParticleTypes.NOTE, x, y + 0.5, z, 20, 1.0, 0.6, 1.0, 1.0);
                level.playSound(null, x, y, z, SoundEvents.NOTE_BLOCK_BELL.value(), SoundSource.PLAYERS, 1.5f, 1.4f);
            }
            case "bedfx_lightning" -> {
                LightningBolt bolt = net.minecraft.world.entity.EntityTypes.LIGHTNING_BOLT.create(level, EntitySpawnReason.TRIGGERED);
                if (bolt != null) {
                    bolt.snapTo(x, bed.getY(), z, 0, 0);
                    bolt.setVisualOnly(true);
                    level.addFreshEntity(bolt);
                }
            }
            case "bedfx_firework" -> {
                level.sendParticles(ParticleTypes.FIREWORK, x, y + 1.5, z, 80, 0.5, 0.5, 0.5, 0.2);
                level.playSound(null, x, y, z, SoundEvents.FIREWORK_ROCKET_LARGE_BLAST, SoundSource.PLAYERS, 2f, 1f);
            }
            case "bedfx_ender" -> {
                level.sendParticles(ParticleTypes.PORTAL, x, y, z, 150, 0.6, 0.8, 0.6, 1.0);
                level.sendParticles(ParticleTypes.REVERSE_PORTAL, x, y + 1, z, 40, 0.3, 0.8, 0.3, 0.05);
                level.playSound(null, x, y, z, SoundEvents.ENDERMAN_TELEPORT, SoundSource.PLAYERS, 1.5f, 0.7f);
            }
            case "bedfx_dragon" -> {
                level.sendParticles(ParticleTypes.WITCH, x, y, z, 150, 1.2, 0.6, 1.2, 0.02);
                level.playSound(null, x, y, z, SoundEvents.ENDER_DRAGON_GROWL, SoundSource.PLAYERS, 1.2f, 1.2f);
            }
            case "bedfx_cannon" -> {
                for (int i = 0; i < 3; i++) {
                    final int k = i;
                    Lobby.runLaterTicks(i * 6, () -> {
                        level.sendParticles(ParticleTypes.EXPLOSION, x + (k - 1) * 1.2, y + 0.5, z, 2, 0.2, 0.2, 0.2, 0);
                        level.sendParticles(ParticleTypes.LARGE_SMOKE, x + (k - 1) * 1.2, y + 0.8, z, 15, 0.3, 0.4, 0.3, 0.02);
                        level.playSound(null, x, y, z, SoundEvents.GENERIC_EXPLODE.value(), SoundSource.PLAYERS, 1.2f, 1.3f);
                    });
                }
            }
            default -> {}
        }
    }

    // =====================================================================
    // /cosmetics equip and /cosmetics clear also handle the new kinds
    // =====================================================================

    public static void init() {
        ServerLifecycleEvents.SERVER_STARTED.register(CosmeticsPlus::wrapCommands);
    }

    /** Wraps the /cosmetics equip <id> and /cosmetics clear commands (from BattlePass) by reflection. */
    @SuppressWarnings("unchecked")
    private static void wrapCommands(MinecraftServer s) {
        try {
            var root = s.getCommands().getDispatcher().getRoot();
            var cosmetics = root.getChild("cosmetics");
            if (cosmetics == null) return;
            java.lang.reflect.Field cmd = com.mojang.brigadier.tree.CommandNode.class.getDeclaredField("command");
            cmd.setAccessible(true);
            var equip = cosmetics.getChild("equip");
            var idNode = equip == null ? null : equip.getChild("id");
            if (idNode != null) {
                var inner = (com.mojang.brigadier.Command<net.minecraft.commands.CommandSourceStack>) cmd.get(idNode);
                cmd.set(idNode, (com.mojang.brigadier.Command<net.minecraft.commands.CommandSourceStack>) c -> {
                    String id = com.mojang.brigadier.arguments.StringArgumentType.getString(c, "id");
                    if (isExtraKind(id)) {
                        equip(c.getSource().getPlayerOrException(), id);
                        return 1;
                    }
                    return inner.run(c);
                });
            }
            var clear = cosmetics.getChild("clear");
            if (clear != null) {
                var inner = (com.mojang.brigadier.Command<net.minecraft.commands.CommandSourceStack>) cmd.get(clear);
                cmd.set(clear, (com.mojang.brigadier.Command<net.minecraft.commands.CommandSourceStack>) c -> {
                    clear(c.getSource().getPlayerOrException());
                    return inner.run(c);
                });
            }
        } catch (Throwable t) {
            com.aidan.skyserver.Skyserver.LOGGER.warn("Couldn't extend /cosmetics for cages, bed effects and name colours: {}", t.toString());
        }
    }

    /** Tells a player about a new cosmetic of these kinds with a [Wear] button. */
    public static void announce(ServerPlayer p, String id) {
        p.sendSystemMessage(Component.literal("§7New cosmetic: " + Progression.cosmeticName(id) + " ")
                .append(BattlePass.button("§a[Wear]", "/cosmetics equip " + id, "Use it now")));
    }
}
