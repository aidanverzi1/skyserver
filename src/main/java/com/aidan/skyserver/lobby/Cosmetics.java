package com.aidan.skyserver.lobby;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.LightningBolt;

import java.util.*;

/**
 * 5.8: kill effects and victory effects (all particles and sounds, so vanilla clients see them),
 * and giving out random cosmetics from mystery chests. The cosmetic list itself is in Progression.
 *
 *  - Kill effects play where a player you killed fell (Gold Wars, Skywars, Bedwars) - hooked in DamageCredit.shares.
 *  - Victory effects play around you when you win one of those games - hooked in Quests.progress (the *_win events).
 */
public final class Cosmetics {
    private static final Random RNG = new Random();

    private Cosmetics() {}

    /** Gives a random cosmetic of this rarity the player doesn't own yet (shards instead if they own them all). */
    public static String grantRandom(ServerPlayer p, int rarity) {
        PlayerProfile prof = PlayerCache.get(p);
        List<String> pool = new ArrayList<>(Progression.cosmeticsOfRarity(rarity, true));
        pool.removeIf(prof.owned::contains);
        if (pool.isEmpty()) {
            int shards = Progression.COSMETIC_SHARDS[rarity] / 2;
            Tokens.add(p.getUUID(), Tokens.SHARDS, shards);
            return "§d" + shards + " Key Shards §7(you already own every " + Progression.RARITY_NAMES[rarity].substring(2).toLowerCase() + " cosmetic)";
        }
        String id = pool.get(RNG.nextInt(pool.size()));
        prof.owned.add(id);
        prof.touch();
        p.sendSystemMessage(Component.literal("§7New cosmetic: " + Progression.cosmeticName(id) + " ")
                .append(BattlePass.button("§a[Equip]", "/cosmetics equip " + id, "Use it now")));
        return Progression.RARITY_NAMES[rarity] + " cosmetic: " + Progression.cosmeticName(id);
    }

    /** A player was killed in a game: the top attacker's kill effect plays where they fell. */
    public static void kill(UUID victimId, UUID killerId) {
        MinecraftServer server = Lobby.server();
        if (server == null || killerId == null) return;
        ServerPlayer victim = server.getPlayerList().getPlayer(victimId), killer = server.getPlayerList().getPlayer(killerId);
        if (victim == null || killer == null || killer instanceof com.aidan.skyserver.bots.BotPlayer) return;
        PlayerProfile prof = PlayerCache.ifOnline(killerId);
        if (prof == null || prof.killFx == null) return;
        ServerLevel level = (ServerLevel) victim.level();
        double x = victim.getX(), y = victim.getY() + 1, z = victim.getZ();
        switch (prof.killFx) {
            case "kill_flame" -> burst(level, ParticleTypes.FLAME, x, y, z, 40, 0.6, SoundEvents.BLAZE_SHOOT);
            case "kill_heart" -> burst(level, ParticleTypes.HEART, x, y, z, 14, 0.7, SoundEvents.EXPERIENCE_ORB_PICKUP);
            case "kill_note" -> burst(level, ParticleTypes.NOTE, x, y, z, 16, 0.8, SoundEvents.NOTE_BLOCK_BELL.value());
            case "kill_soul" -> {
                for (int i = 0; i < 6; i++) level.sendParticles(ParticleTypes.SOUL, x, y + i * 0.4, z, 6, 0.25, 0.1, 0.25, 0.02);
                level.playSound(null, x, y, z, SoundEvents.SOUL_ESCAPE.value(), SoundSource.PLAYERS, 1.5f, 1f);
            }
            case "kill_spark" -> burst(level, ParticleTypes.ELECTRIC_SPARK, x, y, z, 50, 0.7, SoundEvents.COPPER_BULB_TURN_ON);
            case "kill_totem" -> burst(level, ParticleTypes.TOTEM_OF_UNDYING, x, y, z, 60, 0.4, SoundEvents.TOTEM_USE);
            case "kill_thunder" -> {
                LightningBolt bolt = net.minecraft.world.entity.EntityTypes.LIGHTNING_BOLT.create(level, EntitySpawnReason.TRIGGERED);
                if (bolt != null) {
                    bolt.snapTo(x, victim.getY(), z, 0, 0);
                    bolt.setVisualOnly(true);
                    level.addFreshEntity(bolt);
                }
            }
            default -> {}
        }
    }

    /** A player won a game: their victory effect plays around them for a few seconds. */
    public static void victory(ServerPlayer p) {
        PlayerProfile prof = PlayerCache.ifOnline(p.getUUID());
        if (prof == null || prof.winFx == null) return;
        String fx = prof.winFx;
        for (int i = 0; i < 6; i++) {
            final int step = i;
            Lobby.runLaterTicks(i * 12, () -> {
                if (p.hasDisconnected()) return;
                ServerLevel level = (ServerLevel) p.level();
                double x = p.getX(), y = p.getY(), z = p.getZ();
                double a = step * Math.PI / 3, ox = Math.cos(a) * 2.5, oz = Math.sin(a) * 2.5;
                switch (fx) {
                    case "win_firework" -> {
                        level.sendParticles(ParticleTypes.FIREWORK, x + ox, y + 4 + step % 2, z + oz, 60, 0.4, 0.4, 0.4, 0.18);
                        level.playSound(null, x, y, z, step % 2 == 0 ? SoundEvents.FIREWORK_ROCKET_BLAST : SoundEvents.FIREWORK_ROCKET_LARGE_BLAST, SoundSource.PLAYERS, 2f, 1f);
                    }
                    case "win_soul" -> {
                        level.sendParticles(ParticleTypes.SOUL_FIRE_FLAME, x + ox, y + 1, z + oz, 25, 0.2, 1.2, 0.2, 0.02);
                        level.sendParticles(ParticleTypes.SOUL, x, y + 2, z, 10, 1.2, 0.8, 1.2, 0.02);
                        if (step == 0) level.playSound(null, x, y, z, SoundEvents.WITHER_SPAWN, SoundSource.PLAYERS, 0.6f, 1.6f);
                    }
                    case "win_totem" -> {
                        level.sendParticles(ParticleTypes.TOTEM_OF_UNDYING, x, y + 1.2, z, 80, 0.6, 1.0, 0.6, 0.4);
                        if (step == 0) level.playSound(null, x, y, z, SoundEvents.TOTEM_USE, SoundSource.PLAYERS, 1.5f, 1f);
                    }
                    case "win_portal" -> {
                        level.sendParticles(ParticleTypes.PORTAL, x, y + 1, z, 120, 1.5, 1.2, 1.5, 0.6);
                        level.sendParticles(ParticleTypes.REVERSE_PORTAL, x + ox, y + 2, z + oz, 30, 0.2, 0.8, 0.2, 0.05);
                        if (step == 0) level.playSound(null, x, y, z, SoundEvents.ENDERMAN_TELEPORT, SoundSource.PLAYERS, 1.5f, 0.6f);
                    }
                    case "win_thunder" -> {
                        if (step % 2 == 0) {
                            LightningBolt bolt = net.minecraft.world.entity.EntityTypes.LIGHTNING_BOLT.create(level, EntitySpawnReason.TRIGGERED);
                            if (bolt != null) {
                                bolt.snapTo(x + ox * 1.6, y, z + oz * 1.6, 0, 0);
                                bolt.setVisualOnly(true);
                                level.addFreshEntity(bolt);
                            }
                        }
                        level.sendParticles(ParticleTypes.ELECTRIC_SPARK, x, y + 2.2, z, 30, 0.4, 0.1, 0.4, 0.1);
                    }
                    default -> {}
                }
            });
        }
    }

    private static void burst(ServerLevel level, SimpleParticleType type, double x, double y, double z, int count, double spread, SoundEvent sound) {
        level.sendParticles(type, x, y, z, count, spread, spread, spread, 0.08);
        level.playSound(null, x, y, z, sound, SoundSource.PLAYERS, 1.2f, 1f);
    }
}
