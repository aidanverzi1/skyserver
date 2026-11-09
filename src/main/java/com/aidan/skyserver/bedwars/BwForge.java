package com.aidan.skyserver.bedwars;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.List;

/**
 * 5.12: shared team forges. When the forge at a team's base drops iron, gold or (forge upgrade)
 * emeralds and players are standing on it, EVERY one of them gets their own copy (teammates and
 * enemies alike). With nobody on it the item lands on the forge as before, and whoever walks over
 * the pile picks it up (first come, first served - it's gone for everyone else).
 * The diamond and emerald generators out on the map are not shared.
 *
 * How: BedwarsGame's tick drops the item first; this runs right after it in the same tick, finds
 * the brand-new generator item on each team forge, and if anyone is standing there it removes the
 * item and hands out the copies. BedwarsGame itself is unchanged.
 */
public final class BwForge {
    /** How far from the forge's middle you can stand (blocks, sideways) and still count as on it. */
    static final double SHARE_RADIUS = 1.5;

    private BwForge() {}

    /** Call after BedwarsGame.init() (so this tick runs after the game's tick). */
    public static void init() {
        ServerTickEvents.END_SERVER_TICK.register(s -> com.aidan.skyserver.Safe.run("bedwars forge share", BwForge::tick));
    }

    private static void tick() {
        if (!BedwarsGame.playing()) return;
        ServerLevel level = BedwarsGame.level();
        if (level == null) return;
        BwMapDesigns.MapData m = BwMap.data();
        List<ServerPlayer> alive = null;
        for (BwTeamState ts : BedwarsGame.teams.values()) {
            if (ts.out) continue;
            BlockPos g = BwMap.pos(m.gen[ts.team.ordinal()]);
            double cx = g.getX() + 0.5, cy = g.getY() + 0.05, cz = g.getZ() + 0.5;
            // Generator items appear exactly at the forge's middle, not moving, and only this tick.
            List<ItemEntity> fresh = level.getEntitiesOfClass(ItemEntity.class, new AABB(g).inflate(0.25), ie -> ie.tickCount == 0
                    && Math.abs(ie.getX() - cx) < 0.05 && Math.abs(ie.getZ() - cz) < 0.05 && Math.abs(ie.getY() - cy) < 0.1
                    && (ie.getItem().is(Items.IRON_INGOT) || ie.getItem().is(Items.GOLD_INGOT) || ie.getItem().is(Items.EMERALD)));
            if (fresh.isEmpty()) continue;
            if (alive == null) alive = BedwarsGame.alivePlayers();
            List<ServerPlayer> on = new ArrayList<>();
            for (ServerPlayer p : alive) {
                if (p.isSpectator() || p.level() != level) continue;
                if (Math.abs(p.getX() - cx) <= SHARE_RADIUS && Math.abs(p.getZ() - cz) <= SHARE_RADIUS
                        && p.getY() >= g.getY() - 1 && p.getY() <= g.getY() + 2) on.add(p);
            }
            if (on.isEmpty()) continue; // nobody on it: the item stays on the forge
            for (ItemEntity ie : fresh) {
                ItemStack drop = ie.getItem().copy();
                ie.discard();
                for (ServerPlayer p : on) {
                    if (!p.getInventory().add(drop.copy())) continue; // full inventory: this one is missed
                    level.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.ITEM_PICKUP, SoundSource.PLAYERS, 0.2f,
                            1.4f + level.getRandom().nextFloat() * 0.4f);
                }
            }
        }
    }
}
