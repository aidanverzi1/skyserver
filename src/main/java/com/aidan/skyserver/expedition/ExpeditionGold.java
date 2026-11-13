package com.aidan.skyserver.expedition;

import com.aidan.skyserver.Safe;
import com.aidan.skyserver.lobby.Lobby;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/**
 * 5.19: the Nether Expedition's 5 gold blocks (there used to be 50+ in the bastion's floors and trims).
 * Each sits in a bridge floor, outside the arenas, so it never touches the waves. ExpeditionRooms rolls
 * what each one does when the map is planned and builds its "tell" next to it:
 *  - Jackpot (5%): no tell. Drops 3 more gold blocks and a diamond.
 *  - Lava drop: magma on both sides, nothing under it. Standing on it when it breaks = a fall to the lava sea.
 *  - Ambush: a cracked brick in the railing beside it. ONE piglin brute steps out of the bridge's end.
 *  - TNT: a pressure plate next to it. Hidden TNT under it goes off 1.5 seconds later (hurts, breaks nothing).
 * The gold block itself always drops as normal.
 */
final class ExpeditionGold {
    private ExpeditionGold() {}

    /** Tag on the ambushing brute, so the expedition's stray-mob sweep leaves it alone. */
    static final String TRAP_TAG = "skyserver_exptrap";
    /** Ticks until the hidden TNT goes off, and its blast power (TNT is 4). */
    static final int TNT_FUSE = 30;
    static final float TNT_POWER = 3.5f;
    /** Extra health for the ambushing brute by difficulty (0.5 = +50%). */
    static final double[] AMBUSH_HEALTH = {0.0, 0.2, 0.5, 1.0};

    static void init() {
        PlayerBlockBreakEvents.AFTER.register((level, player, pos, state, be) -> {
            if (!Expedition.isExpedition(level) || !(player instanceof ServerPlayer sp) || !state.is(Blocks.GOLD_BLOCK)) return;
            Safe.run("expedition gold trap", () -> broke((ServerLevel) level, sp, pos));
        });
    }

    private static void broke(ServerLevel level, ServerPlayer p, BlockPos pos) {
        ExpeditionRooms.Layout lay = Expedition.layoutOf(p);
        if (lay == null) return;
        for (ExpeditionRooms.GoldTrap g : lay.goldTraps) {
            if (g.sprung || !g.pos.equals(pos)) continue;
            g.sprung = true;
            spring(level, p, g, Math.max(0, Expedition.diffOf(p)));
            return;
        }
    }

    private static void spring(ServerLevel level, ServerPlayer p, ExpeditionRooms.GoldTrap g, int diff) {
        BlockPos pos = g.pos;
        double x = pos.getX() + 0.5, y = pos.getY() + 0.5, z = pos.getZ() + 0.5;
        switch (g.kind) {
            case ExpeditionRooms.GoldTrap.JACKPOT -> {
                drop(level, x, y + 0.5, z, new ItemStack(Items.GOLD_BLOCK, 3));
                drop(level, x, y + 0.5, z, new ItemStack(Items.DIAMOND, 1));
                level.sendParticles(ParticleTypes.WAX_ON, x, y + 0.8, z, 40, 0.6, 0.6, 0.6, 0.3);
                level.playSound(null, pos, SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 1f, 1.4f);
                Lobby.msg(p, "§6§lJACKPOT! §eThat gold block was the real thing - 3 more gold blocks and a diamond!");
            }
            case ExpeditionRooms.GoldTrap.LAVA -> {
                // Nothing to do: the hole under it was built that way. Just the warning creak.
                level.playSound(null, pos, SoundEvents.GRAVEL_BREAK, SoundSource.BLOCKS, 1.2f, 0.5f);
                level.sendParticles(ParticleTypes.LAVA, x, y - 1, z, 8, 0.3, 0.3, 0.3, 0.1);
                Lobby.msg(p, "§c§lTRAP! §7There was nothing under that gold block but a long drop to the lava...");
            }
            case ExpeditionRooms.GoldTrap.AMBUSH -> {
                BlockPos from = g.ambushFrom;
                Mob m = net.minecraft.world.entity.EntityTypes.PIGLIN_BRUTE.create(level, EntitySpawnReason.EVENT);
                if (m == null) return;
                m.snapTo(from.getX() + 0.5, from.getY(), from.getZ() + 0.5, 0f, 0f);
                m.finalizeSpawn(level, level.getCurrentDifficultyAt(from), EntitySpawnReason.EVENT, null);
                m.addTag(TRAP_TAG);
                m.setPersistenceRequired();
                double extra = AMBUSH_HEALTH[Math.min(diff, AMBUSH_HEALTH.length - 1)];
                if (extra > 0) {
                    var hp = m.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH);
                    if (hp != null) hp.setBaseValue(hp.getBaseValue() * (1 + extra));
                    m.setHealth(m.getMaxHealth());
                }
                m.setTarget(p);
                level.addFreshEntity(m);
                level.sendParticles(ParticleTypes.LARGE_SMOKE, from.getX() + 0.5, from.getY() + 1, from.getZ() + 0.5, 25, 0.4, 0.8, 0.4, 0.02);
                level.playSound(null, from, SoundEvents.PIGLIN_BRUTE_ANGRY, SoundSource.HOSTILE, 1.5f, 1f);
                Lobby.msg(p, "§c§lTRAP! §7A piglin brute was guarding that gold!");
            }
            case ExpeditionRooms.GoldTrap.TNT -> {
                BlockPos under = pos.below();
                if (level.getBlockState(under).is(Blocks.TNT)) level.setBlock(under, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                PrimedTnt tnt = new PrimedTnt(level, x, pos.getY(), z, null);
                tnt.setFuse(TNT_FUSE + 40); // it never gets to explode itself - the blast below breaks no blocks
                level.addFreshEntity(tnt);
                level.playSound(null, pos, SoundEvents.TNT_PRIMED, SoundSource.BLOCKS, 1.5f, 1f);
                Lobby.msg(p, "§c§lTRAP! §7Something hisses under your feet - RUN!");
                Lobby.runLaterTicks(TNT_FUSE, () -> Safe.run("expedition gold tnt", () -> {
                    if (tnt.isRemoved()) return;
                    double tx = tnt.getX(), ty = tnt.getY(), tz = tnt.getZ();
                    tnt.discard();
                    level.explode(null, tx, ty, tz, TNT_POWER, Level.ExplosionInteraction.NONE);
                }));
            }
            default -> { }
        }
    }

    private static void drop(ServerLevel level, double x, double y, double z, ItemStack stack) {
        ItemEntity e = new ItemEntity(level, x, y, z, stack);
        e.setDefaultPickUpDelay();
        level.addFreshEntity(e);
    }
}
