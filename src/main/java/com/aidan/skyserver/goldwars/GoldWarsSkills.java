package com.aidan.skyserver.goldwars;

import com.aidan.skyserver.Longbow;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import com.aidan.skyserver.Skyserver;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Container;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.hurtingprojectile.LargeFireball;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.*;

/**
 * Class abilities and passives:
 *  - Mage: Fire Wand fireballs (explode, knock back, burn players, break blocks, can be punched back)
 *  - Longbow (the item itself lives in com.aidan.skyserver.Longbow): Gold Wars adds no shooting
 *    from spawn and no hitting teammates
 *  - Knight: permanent Resistance I.  Scout: permanent Speed I.
 *  - Gold mining speed per class (Builder 1.7s, Scout 2.5s, everyone else 4.5s per block)
 *
 * Explosions are custom: they hurt players normally but only break blocks players may break,
 * never gold or spawn pads, and they drop nothing.
 */
final class GoldWarsSkills {
    // ---- Settings you can change ----
    static final int WAND_COOLDOWN_TICKS = 4 * 20;
    static final float FIREBALL_POWER = 1.0f;          // explosion strength (damage + knockback)
    static final float FIREBALL_BLOCK_RADIUS = 2.2f;   // how far it breaks blocks
    static final double FIREBALL_BURN_RADIUS = 3.5;
    static final int FIREBALL_BURN_SECONDS = 4;
    static final int FIREBALL_LIFETIME_TICKS = 10 * 20;

    /** Extra mining speed while aiming at gold, so a gold block takes the target time. */
    private static final Identifier GOLD_MINING_ID = Identifier.fromNamespaceAndPath(Skyserver.MOD_ID, "gold_mining");

    private static final Map<UUID, Long> wandReady = new HashMap<>();

    private static final class Fireball {
        final LargeFireball entity;
        final long firedAt;
        Vec3 lastPos;
        UUID lastOwner;

        Fireball(LargeFireball entity, long firedAt, UUID owner) {
            this.entity = entity;
            this.firedAt = firedAt;
            this.lastPos = entity.position();
            this.lastOwner = owner;
        }
    }
    private static final List<Fireball> fireballs = new ArrayList<>();

    private record Bomb(PrimedTnt tnt, long explodeAt, UUID owner, float blockRadius, float power) {}
    private static final List<Bomb> bombs = new ArrayList<>();

    private GoldWarsSkills() {}

    static void init() {
        Longbow.init(); // make sure the Longbow is running even if Skyserver.java wasn't updated
        // No loose items in the arena: flowers, wheat, mushrooms etc. that pop off when the block
        // under them is broken or blown up are removed. Items a player throws (Q) are kept.
        ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> {
            if (!(entity instanceof ItemEntity item)) return;
            if (level != GoldWarsGame.arenaLevel()) return;
            if (item.getOwner() instanceof Player) return;
            GoldWarsGame.runNextTick(item::discard);
        });
        // Gold Wars rules for the Longbow (it works anywhere; these only apply to Gold Wars players).
        Longbow.addShotRule(p -> GoldWarsGame.isInGame(p.getUUID()) && GoldWarsMap.inSpawnZone(p.blockPosition())
                ? "§cYou can't shoot the Longbow from a spawn area." : null);
        Longbow.addTargetRule((shooter, target) -> {
            if (!GoldWarsGame.isInGame(shooter.getUUID())) return true;
            if (!(target instanceof ServerPlayer tp)) return true; // mobs are fair game
            if (!GoldWarsGame.isInGame(tp.getUUID()) || !GoldWarsGame.isAlive(tp.getUUID())) return false;
            return GoldWarsGame.teamOf(tp.getUUID()) != GoldWarsGame.teamOf(shooter.getUUID());
        });
    }

    /** Called when a match resets. */
    static void reset() {
        for (Fireball f : fireballs) f.entity.discard();
        fireballs.clear();
        for (Bomb b : bombs) b.tnt().discard();
        bombs.clear();
        wandReady.clear();
    }

    /** Called when a player leaves Gold Wars (or the match ends) to undo anything we changed on them. */
    static void cleanup(ServerPlayer p) {
        AttributeInstance mining = p.getAttribute(Attributes.MINING_EFFICIENCY);
        if (mining != null) mining.removeModifier(GOLD_MINING_ID);
    }

    /** Called every time a player (re)spawns with their kit. */
    static void onSpawn(ServerPlayer p, GwClass cls) {
        int forever = 20 * 60 * 60;
        if (cls == GwClass.KNIGHT) p.addEffect(new MobEffectInstance(MobEffects.RESISTANCE, forever, 0, false, false));
        if (cls == GwClass.SCOUT) p.addEffect(new MobEffectInstance(MobEffects.SPEED, forever, 0, false, false));
    }

    /** Extra text for the status line. */
    static String status(ServerPlayer p, GwClass cls) {
        if (cls == GwClass.MAGE) return "Fire Wand: " + cooldownText(wandReady.get(p.getUUID()));
        if (cls == GwClass.LONGBOW) return "Longbow: " + Longbow.cooldownText(p);
        return "";
    }

    private static String cooldownText(Long readyAt) {
        long now = GoldWarsGame.now();
        if (readyAt == null || now >= readyAt) return "§aready";
        return "§c" + ((readyAt - now + 19) / 20) + "s";
    }

    // =====================================================================
    // Mage: Fire Wand
    // =====================================================================

    /** Returns true if the held item was a class ability and has been handled. */
    static boolean onUseItem(ServerPlayer p, ItemStack held) {
        GwClass cls = GoldWarsGame.classOf(p.getUUID());
        if (cls != GwClass.MAGE || !GwClass.isFireWand(held)) return false;
        if (!GoldWarsGame.isAlive(p.getUUID()) || !GoldWarsGame.inMatchNow()) return true;
        long now = GoldWarsGame.now();
        Long ready = wandReady.get(p.getUUID());
        if (ready != null && now < ready) return true; // still recharging; ignore spam clicks

        ServerLevel level = GoldWarsGame.arenaLevel();
        Vec3 look = p.getLookAngle();
        LargeFireball ball = new LargeFireball(level, p, look, 0); // 0 = no vanilla explosion; we do our own
        ball.setPos(p.getX() + look.x * 1.5, p.getEyeY() - 0.2 + look.y * 1.5, p.getZ() + look.z * 1.5);
        level.addFreshEntity(ball);
        fireballs.add(new Fireball(ball, now, p.getUUID()));
        wandReady.put(p.getUUID(), now + WAND_COOLDOWN_TICKS);
        level.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.BLAZE_SHOOT, SoundSource.PLAYERS, 1f, 1f);
        return true;
    }

    private static void fireballExplodes(ServerLevel level, Vec3 pos, UUID ownerId) {
        ServerPlayer owner = ownerId == null ? null : GoldWarsGame.player(ownerId);
        blast(level, pos, FIREBALL_BLOCK_RADIUS, FIREBALL_POWER, owner);
        // Set enemy players on fire (not blocks). Teammates of whoever fired it last are safe.
        GwTeam ownerTeam = ownerId == null ? null : GoldWarsGame.teamOf(ownerId);
        for (ServerPlayer target : GoldWarsGame.alivePlayers()) {
            if (target.getUUID().equals(ownerId)) continue;
            if (ownerTeam != null && GoldWarsGame.teamOf(target.getUUID()) == ownerTeam) continue;
            if (target.position().distanceTo(pos) <= FIREBALL_BURN_RADIUS) target.igniteForSeconds(FIREBALL_BURN_SECONDS);
        }
    }

    // =====================================================================
    // Every tick during a match
    // =====================================================================

    static void tick() {
        ServerLevel level = GoldWarsGame.arenaLevel();
        if (level == null) return;
        long now = GoldWarsGame.now();

        // Fireballs: when one hits something (or flies off for too long), run our explosion.
        Iterator<Fireball> fit = fireballs.iterator();
        while (fit.hasNext()) {
            Fireball f = fit.next();
            if (f.entity.isRemoved()) {
                fireballExplodes(level, f.lastPos, f.lastOwner);
                fit.remove();
            } else if (now - f.firedAt > FIREBALL_LIFETIME_TICKS || !GoldWarsMap.box().contains(f.entity.position())) {
                f.entity.discard();
                fit.remove();
            } else {
                f.lastPos = f.entity.position();
                // Whoever punched it back last now "owns" it.
                if (f.entity.getOwner() instanceof ServerPlayer owner) f.lastOwner = owner.getUUID();
            }
        }

        // Explosives (not in any kit right now, kept for future use).
        Iterator<Bomb> bit = bombs.iterator();
        while (bit.hasNext()) {
            Bomb b = bit.next();
            if (b.tnt().isRemoved()) {
                bit.remove();
            } else if (now >= b.explodeAt()) {
                Vec3 pos = b.tnt().position();
                b.tnt().discard();
                blast(level, pos, b.blockRadius(), b.power(), GoldWarsGame.player(b.owner()));
                bit.remove();
            }
        }

        for (ServerPlayer p : GoldWarsGame.alivePlayers()) {
            GwClass cls = GoldWarsGame.classOf(p.getUUID());
            if (cls == null) continue;

            // Gold mining speed: only while aiming at a gold block.
            AttributeInstance mining = p.getAttribute(Attributes.MINING_EFFICIENCY);
            if (mining != null) {
                double bonus = goldMiningBonus(cls);
                if (bonus != 0 && lookingAtGold(p)) {
                    mining.addOrUpdateTransientModifier(new AttributeModifier(GOLD_MINING_ID, bonus, AttributeModifier.Operation.ADD_VALUE));
                } else {
                    mining.removeModifier(GOLD_MINING_ID);
                }
            }
        }
    }

    /**
     * Gold is mined with the wrong tier of pickaxe (it needs iron), so a block takes
     * 300 / speed ticks. Builder: stone 4 + Efficiency II 5 = 9  -> 34 ticks (1.7s), no change needed.
     * Scout: wood 2 + Efficiency II 5 = 7, minus 1 = 6 -> 50 ticks (2.5s).
     * Everyone else: wood 2, plus 1.34 = 3.34 -> 90 ticks (4.5s).
     */
    private static double goldMiningBonus(GwClass cls) {
        return switch (cls) {
            case BUILDER -> 0;
            case SCOUT -> -1.0;
            default -> 1.34;
        };
    }

    private static boolean lookingAtGold(ServerPlayer p) {
        HitResult hit = p.pick(5.0, 0f, false);
        return hit instanceof BlockHitResult bhr && hit.getType() == HitResult.Type.BLOCK
                && GoldWarsGame.isGold(bhr.getBlockPos());
    }

    // =====================================================================
    // Explosions
    // =====================================================================

    /** Placing TNT lights it instead (TNT isn't in any kit right now). */
    static void placeTnt(ServerPlayer p, BlockPos target, ItemStack held) {
        ServerLevel level = GoldWarsGame.arenaLevel();
        PrimedTnt tnt = new PrimedTnt(level, target.getX() + 0.5, target.getY(), target.getZ() + 0.5, p);
        tnt.setFuse(60);
        level.addFreshEntity(tnt);
        bombs.add(new Bomb(tnt, GoldWarsGame.now() + 50, p.getUUID(), 3.0f, 3.0f));
        held.shrink(1);
    }

    /**
     * Hurts and knocks back players like a normal explosion (friendly fire rules still apply),
     * then removes blocks in a sphere, skipping anything protected. Nothing drops.
     */
    static void blast(ServerLevel level, Vec3 c, float blockRadius, float power, ServerPlayer owner) {
        level.explode(owner, c.x, c.y, c.z, power, Level.ExplosionInteraction.NONE);
        int r = (int) Math.ceil(blockRadius);
        BlockPos center = BlockPos.containing(c);
        for (int dx = -r; dx <= r; dx++)
            for (int dy = -r; dy <= r; dy++)
                for (int dz = -r; dz <= r; dz++) {
                    if (dx * dx + dy * dy + dz * dz > blockRadius * blockRadius) continue;
                    BlockPos pos = center.offset(dx, dy, dz);
                    BlockState st = level.getBlockState(pos);
                    if (st.isAir() || st.is(Blocks.BEDROCK) || st.is(Blocks.BARRIER)) continue;
                    if (!GoldWarsGame.canEdit(pos)) continue; // protects gold, spawns, map edges
                    if (level.getBlockEntity(pos) instanceof Container container) container.clearContent();
                    level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                }
    }
}
