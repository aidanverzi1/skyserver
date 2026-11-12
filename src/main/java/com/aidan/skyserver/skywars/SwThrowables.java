package com.aidan.skyserver.skywars;

import com.aidan.skyserver.Safe;
import com.aidan.skyserver.bots.BotPlayer;
import com.aidan.skyserver.lobby.Lobby;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.throwableitemprojectile.Snowball;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrowableItemProjectile;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEgg;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.*;

/**
 * 5.18: snowballs and (normal) eggs give a slight knockback to players in Skywars.
 *
 * Vanilla snowballs and eggs do 0 damage to players, so no damage event fires for them (that's why the old
 * snowball push in SwItems never did anything). Instead, every tick this looks at each flying snowball / egg in
 * the Skywars world and, if it is about to touch a player alive in the game (not its thrower), pushes that
 * player along the throw and removes the projectile. Bridge eggs are left alone (BridgeEggs builds with them).
 */
public final class SwThrowables {
    private SwThrowables() {}

    /** Sideways push and lift (a normal punch is about 0.4 sideways / 0.36 up). */
    static final double PUSH = 0.45, LIFT = 0.25;

    private static boolean registered = false;

    static void init() {
        if (registered) return;
        registered = true;
        ServerTickEvents.END_SERVER_TICK.register(s -> {
            if (!SkywarsGame.isPlaying()) return;
            Safe.run("skywars throwables", () -> tick(s));
        });
    }

    private static void tick(MinecraftServer s) {
        ServerLevel level = s.getLevel(Lobby.SKYWARS);
        if (level == null) return;
        List<ServerPlayer> alive = new ArrayList<>();
        for (ServerPlayer p : level.players()) if (SkywarsGame.isAlive(p.getUUID()) && !p.isSpectator()) alive.add(p);
        if (alive.isEmpty()) return;
        for (ServerPlayer target : alive) {
            AABB near = target.getBoundingBox().inflate(3.0);
            for (ThrowableItemProjectile ball : level.getEntitiesOfClass(ThrowableItemProjectile.class, near,
                    e -> (e instanceof Snowball || e instanceof ThrownEgg) && e.isAlive())) {
                if (ball instanceof ThrownEgg egg && SkywarsLoot.isBridgeEgg(egg.getItem())) continue;
                Entity owner = ball.getOwner();
                if (owner == target) continue;
                Vec3 v = ball.getDeltaMovement();
                // Will it touch the player this tick? (its box, swept along its movement)
                AABB sweep = ball.getBoundingBox().expandTowards(v).inflate(0.3);
                if (!sweep.intersects(target.getBoundingBox())) continue;
                hit(level, target, ball, v);
            }
        }
    }

    private static void hit(ServerLevel level, ServerPlayer target, ThrowableItemProjectile ball, Vec3 v) {
        Vec3 flat = new Vec3(v.x, 0, v.z);
        if (flat.lengthSqr() < 1e-4) flat = target.position().subtract(ball.position()).multiply(1, 0, 1);
        if (flat.lengthSqr() < 1e-4) flat = new Vec3(0, 0, 1);
        flat = flat.normalize().scale(PUSH);
        ball.discard();
        target.setDeltaMovement(target.getDeltaMovement().add(flat.x, LIFT, flat.z));
        target.syncVelocity = true;
        if (!(target instanceof BotPlayer)) target.connection.send(new ClientboundSetEntityMotionPacket(target));
        level.playSound(null, target.getX(), target.getY(), target.getZ(), SoundEvents.PLAYER_ATTACK_KNOCKBACK, SoundSource.PLAYERS, 0.5f, 1.6f);
    }
}
