package com.aidan.skyserver.goldwars;

import com.aidan.skyserver.lobby.Lobby;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.hurtingprojectile.LargeFireball;

/**
 * 5.9: fireballs hurt players 50% more in the games. Knockback, block breaking and self-damage are
 * unchanged.
 *  - Skywars: the vanilla fireball (its explosion and a direct hit).
 *  - Gold Wars: the Mage's Fire Wand (our explosion in the arena: any explosion a player caused -
 *    TNT isn't in any Gold Wars kit) and a direct hit.
 *  - Bedwars: the blast is boosted in BwItems itself (FIREBALL_DAMAGE 7.5); a direct hit is boosted here.
 * How: the hit is cancelled and dealt again at 1.5x (so armor, teams and kill credit work as normal).
 */
public final class FireballDamage {
    private FireballDamage() {}

    public static final float MULTIPLIER = 1.5f;
    private static boolean dealing = false; // true while we deal the boosted hit (so it isn't boosted twice)

    public static void init() {
        ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> {
            if (dealing || !(entity instanceof ServerPlayer target) || amount <= 0) return true;
            if (!(target.level() instanceof ServerLevel level) || !Lobby.isGameLevel(level)) return true;
            if (!boosted(level, target, source)) return true;
            dealing = true;
            try {
                target.hurtServer(level, source, amount * MULTIPLIER);
            } finally {
                dealing = false;
            }
            return false; // the boosted hit replaced this one
        });
    }

    private static boolean boosted(ServerLevel level, ServerPlayer target, DamageSource source) {
        Entity attacker = source.getEntity();
        if (!(attacker instanceof ServerPlayer) || attacker == target) return false; // self-damage stays the same
        if (source.getDirectEntity() instanceof LargeFireball) return true;       // Skywars blast, direct hits anywhere
        // Gold Wars arena: the Fire Wand's explosion is caused by the player directly.
        return level.dimension() == Lobby.ARENA && source.is(DamageTypeTags.IS_EXPLOSION) && source.getDirectEntity() == attacker;
    }
}
