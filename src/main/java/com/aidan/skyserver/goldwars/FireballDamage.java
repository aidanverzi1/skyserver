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
 * 5.13: in a Fireball Frenzy match (Wren's Weekly Voyage) Bedwars fireballs and blasts hurt players
 * another 25% more.
 * How: the hit is cancelled and dealt again, boosted (so armor, teams and kill credit work as normal).
 */
public final class FireballDamage {
    private FireballDamage() {}

    public static final float MULTIPLIER = 1.5f;
    /** 5.13: extra in a Fireball Frenzy Bedwars match. */
    public static final float FRENZY_MULTIPLIER = 1.25f;
    private static boolean dealing = false; // true while we deal the boosted hit (so it isn't boosted twice)

    public static void init() {
        ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> {
            if (dealing || !(entity instanceof ServerPlayer target) || amount <= 0) return true;
            if (!(target.level() instanceof ServerLevel level) || !Lobby.isGameLevel(level)) return true;
            float m = multiplier(level, target, source);
            if (m == 1f) return true;
            dealing = true;
            try {
                target.hurtServer(level, source, amount * m);
            } finally {
                dealing = false;
            }
            return false; // the boosted hit replaced this one
        });
    }

    private static float multiplier(ServerLevel level, ServerPlayer target, DamageSource source) {
        Entity attacker = source.getEntity();
        if (!(attacker instanceof ServerPlayer) || attacker == target) return 1f; // self-damage stays the same
        boolean direct = source.getDirectEntity() instanceof LargeFireball;
        float m = 1f;
        if (direct) m = MULTIPLIER;                                                // Skywars blast, direct hits anywhere
        // Gold Wars arena: the Fire Wand's explosion is caused by the player directly.
        else if (level.dimension() == Lobby.ARENA && source.is(DamageTypeTags.IS_EXPLOSION) && source.getDirectEntity() == attacker) m = MULTIPLIER;
        // 5.13: Fireball Frenzy (Bedwars): fireballs and their blasts.
        if (com.aidan.skyserver.bedwars.BwRules.fireballFrenzy && level.dimension().identifier().getPath().equals("bedwars")
                && (direct || source.is(DamageTypeTags.IS_EXPLOSION))) m *= FRENZY_MULTIPLIER;
        return m;
    }
}
