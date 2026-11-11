package com.aidan.skyserver;

import com.aidan.skyserver.island.Islands;
import com.aidan.skyserver.lobby.Announce;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.hurtingprojectile.LargeFireball;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.*;

/**
 * 5.14: the Fire Wand, a Legendary crate prize that works anywhere (not only in Gold Wars).
 *
 * Right-click: it shoots a fireball. Where the fireball lands it blasts: players and mobs nearby are hurt
 * and set alight (not you), and blocks within about 2 blocks are DESTROYED (no drops), like the Gold Wars
 * Fire Wand. 4-second cooldown.
 *
 * Block safety: every block goes through the same "may this player break this block?" checks as a normal
 * break (island ownership, the lobby, Wren's Landing and Boss Arena walls, the games' own rules), so it
 * can't dig into someone else's island or the protected parts of a map. Blocks with inventories (chests,
 * furnaces...) and unbreakable blocks (bedrock) are never touched.
 *
 * It's a blaze rod with a hidden marker in its lore (like the Longbow) so renaming a blaze rod can't fake
 * one. Started from Longbow.init(). Admins get one with /firewand.
 */
public final class FireWand {
    public static final String NAME = "Fire Wand";
    private static final String MARKER = "Skyserver Fire Wand";

    public static final int COOLDOWN_TICKS = 80;
    public static final float BLAST_POWER = 1.0f;
    public static final double BLOCK_RADIUS = 2.2;
    public static final double BURN_RADIUS = 3.5;
    public static final int BURN_TICKS = 80;
    public static final int LIFETIME_TICKS = 200;

    /** A fireball in flight: where it was last tick and how fast, so the blast lands where it hit. */
    private static final class Shot {
        final LargeFireball ball;
        final UUID owner;
        final long born;
        Vec3 pos;
        Vec3 vel = Vec3.ZERO;

        Shot(LargeFireball ball, UUID owner, long born) {
            this.ball = ball;
            this.owner = owner;
            this.born = born;
            this.pos = ball.position();
        }
    }

    private static final List<Shot> SHOTS = new ArrayList<>();
    private static final Map<UUID, Long> READY_AT = new HashMap<>();
    private static long ticks = 0;
    private static boolean initialized = false;

    private FireWand() {}

    /** Safe to call more than once. */
    public static void init() {
        if (initialized) return;
        initialized = true;
        UseItemCallback.EVENT.register((player, level, hand) -> {
            if (level.isClientSide() || !(player instanceof ServerPlayer sp)) return InteractionResult.PASS;
            if (!isFireWand(player.getItemInHand(hand))) return InteractionResult.PASS;
            Safe.run("fire wand", () -> fire(sp, (ServerLevel) level));
            return InteractionResult.SUCCESS;
        });
        ServerTickEvents.END_SERVER_TICK.register(s -> {
            ticks++;
            if (!SHOTS.isEmpty()) Safe.run("fire wand tick", FireWand::tickShots);
        });
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> dispatcher.register(
                Commands.literal("firewand").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS)).executes(c -> {
                    ServerPlayer p = c.getSource().getPlayerOrException();
                    p.getInventory().add(create());
                    c.getSource().sendSuccess(() -> Component.literal("Gave you a Fire Wand."), false);
                    return 1;
                })));
    }

    // =====================================================================
    // The item
    // =====================================================================

    public static ItemStack create() {
        ItemStack wand = new ItemStack(Items.BLAZE_ROD);
        wand.set(DataComponents.CUSTOM_NAME, Component.literal("§6§l" + NAME));
        wand.set(DataComponents.LORE, new ItemLore(List.of(
                Component.literal("§7Right-click: shoot a fireball"),
                Component.literal("§7It blasts players and mobs, sets them alight"),
                Component.literal("§7and destroys blocks where it lands"),
                Component.literal("§74 second cooldown"),
                Component.literal("§8" + MARKER))));
        return wand;
    }

    public static boolean isFireWand(ItemStack stack) {
        if (stack == null || !stack.is(Items.BLAZE_ROD)) return false;
        ItemLore lore = stack.get(DataComponents.LORE);
        if (lore == null) return false;
        for (Component line : lore.lines()) {
            if (line.getString().contains(MARKER)) return true;
        }
        return false;
    }

    // =====================================================================
    // Shooting
    // =====================================================================

    private static void fire(ServerPlayer p, ServerLevel level) {
        Long ready = READY_AT.get(p.getUUID());
        if (ready != null && ticks < ready) {
            Announce.actionBar(p, "§cFire Wand recharging... §f" + String.format(Locale.ROOT, "%.1f", (ready - ticks) / 20.0) + "s");
            return;
        }
        READY_AT.put(p.getUUID(), ticks + COOLDOWN_TICKS);
        Vec3 look = p.getLookAngle();
        LargeFireball ball = new LargeFireball(level, p, look, 0);
        Vec3 at = p.getEyePosition().add(look.scale(1.5));
        ball.setPos(at.x, at.y, at.z);
        level.addFreshEntity(ball);
        SHOTS.add(new Shot(ball, p.getUUID(), ticks));
        level.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.BLAZE_SHOOT, SoundSource.PLAYERS, 1.5f, 0.8f);
    }

    private static void tickShots() {
        for (Iterator<Shot> it = SHOTS.iterator(); it.hasNext(); ) {
            Shot s = it.next();
            if (!(s.ball.level() instanceof ServerLevel level)) {
                it.remove();
                continue;
            }
            boolean gone = s.ball.isRemoved();
            if (!gone && ticks - s.born > LIFETIME_TICKS) { // flew too long: it just fizzles
                s.ball.discard();
                it.remove();
                continue;
            }
            if (!gone) {
                s.pos = s.ball.position();
                s.vel = s.ball.getDeltaMovement();
                continue;
            }
            // It hit something this tick and vanished: blast just ahead of where it was last seen.
            it.remove();
            Vec3 at = s.pos.add(s.vel.scale(0.6));
            ServerPlayer owner = level.getServer().getPlayerList().getPlayer(s.owner);
            blast(level, owner, at);
        }
    }

    private static void blast(ServerLevel level, ServerPlayer owner, Vec3 at) {
        level.explode(owner, at.x, at.y, at.z, BLAST_POWER, Level.ExplosionInteraction.NONE);
        level.sendParticles(ParticleTypes.FLAME, at.x, at.y, at.z, 40, 1.2, 1.2, 1.2, 0.05);
        // Everything close burns (not the shooter).
        for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, new AABB(at, at).inflate(BURN_RADIUS),
                x -> x != owner && x.isAlive() && !x.isSpectator())) {
            if (e.position().add(0, e.getBbHeight() / 2, 0).distanceTo(at) <= BURN_RADIUS)
                e.setRemainingFireTicks(Math.max(e.getRemainingFireTicks(), BURN_TICKS));
        }
        if (owner == null) return; // the shooter left: no block damage without someone to check permission for
        BlockPos centre = BlockPos.containing(at);
        int r = (int) Math.ceil(BLOCK_RADIUS);
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int dx = -r; dx <= r; dx++) {
            for (int dy = -r; dy <= r; dy++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.sqrt(dx * dx + dy * dy + dz * dz) > BLOCK_RADIUS) continue;
                    m.set(centre.getX() + dx, centre.getY() + dy, centre.getZ() + dz);
                    breakBlock(level, owner, m.immutable());
                }
            }
        }
    }

    /** Destroys one block (no drops) if the player may break it. */
    private static boolean breakBlock(ServerLevel level, ServerPlayer owner, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (state.isAir() || !state.getFluidState().isEmpty() || state.getDestroySpeed(level, pos) < 0) return false;
        if (level.getBlockEntity(pos) != null) return false; // chests, furnaces, spawners...
        if (Islands.isIsland(level) && !Islands.canBuild(owner, pos)) return false;
        if (!PlayerBlockBreakEvents.BEFORE.invoker().beforeBlockBreak(level, owner, pos, state, null)) return false;
        level.sendParticles(ParticleTypes.SMOKE, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, 2, 0.3, 0.3, 0.3, 0.01);
        level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        return true;
    }
}
