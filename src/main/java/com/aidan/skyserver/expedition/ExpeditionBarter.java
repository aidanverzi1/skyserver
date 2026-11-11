package com.aidan.skyserver.expedition;

import com.aidan.skyserver.lobby.Announce;
import com.aidan.skyserver.lobby.Economy;
import com.aidan.skyserver.lobby.Lobby;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Prediction;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.List;
import java.util.Random;
import java.util.UUID;

/**
 * 5.17: the gold trader in Wren's Landing - a piglin that can't be hurt, never attacks and never moves.
 * Right-click it holding gold ingots: 1 ingot = 1 roll of the table below (sneak = the whole stack you hold).
 * Every player can trade CAP[difficulty] ingots per trip.
 *
 * The table is this server's own (no ender pearls, potions or enchanted books). weight = relative chance:
 * the Common rows add up to 60, Uncommon 30, Rare 9, Jackpot 1.
 */
final class ExpeditionBarter {
    private ExpeditionBarter() {}

    static final String PIGLIN_TAG = "skyserver_exppiglin";
    /** Gold ingots one player may trade on one trip (Easy, Normal, Hard, Nightmare). */
    static final int[] CAP = {48, 96, 160, 256};

    /** One row: weight (x10, so decimals fit), item id (null = money), how many, money. */
    private record Offer(int weight, String item, int count, long money, boolean jackpot) {}

    private static Offer item(int weight, String id, int count) {
        return new Offer(weight, id, count, 0, false);
    }

    private static final List<Offer> TABLE = List.of(
            // Common (600)
            item(75, "soul_sand", 4), item(75, "nether_wart", 3), item(75, "glowstone_dust", 4), item(75, "quartz", 4),
            item(75, "blackstone", 8), item(75, "basalt", 8), item(75, "string", 3), item(75, "leather", 2),
            // Uncommon (300)
            item(43, "obsidian", 1), item(43, "crying_obsidian", 1), item(43, "magma_cream", 2), item(43, "slime_ball", 2),
            item(43, "clay_ball", 4), item(43, "red_sand", 8), item(42, "spectral_arrow", 6),
            // Rare (90)
            item(30, "experience_bottle", 8), item(10, "cherry_sapling", 1), item(10, "mangrove_propagule", 1),
            item(20, "amethyst_shard", 4), item(20, "honeycomb", 3),
            // Jackpot (10)
            new Offer(4, null, 0, 300, true), new Offer(3, "diamond", 1, 0, true), new Offer(3, "experience_bottle", 16, 0, true));

    /** Puts the trader in Wren's Landing. */
    static UUID spawn(ServerLevel nl, double x, double y, double z, float yaw) {
        Mob m = net.minecraft.world.entity.EntityTypes.PIGLIN.create(nl, EntitySpawnReason.COMMAND);
        if (m == null) return null;
        m.snapTo(x, y, z, yaw, 0f);
        m.setYHeadRot(yaw);
        m.setNoAi(true);
        m.setPermanentlyInvulnerable(true);
        m.setSilent(true);
        m.setPersistenceRequired();
        m.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.GOLD_INGOT));
        m.setDropChance(EquipmentSlot.MAINHAND, 0f);
        m.setCustomName(Component.literal("§6Gold Trader §7(right-click with gold ingots)"));
        m.setCustomNameVisible(true);
        m.addTag(PIGLIN_TAG);
        nl.addFreshEntity(m);
        return m.getUUID();
    }

    /** A player right-clicked the trader. */
    static void trade(ServerPlayer p, Expedition.Run run, MinecraftServer server, Random rng) {
        ItemStack hand = p.getMainHandItem();
        int used = run.bartered.getOrDefault(p.getUUID(), 0), cap = CAP[run.diff];
        if (!hand.is(Items.GOLD_INGOT)) {
            Lobby.msg(p, "§6[Gold Trader] §f*snort* §7Hold §6gold ingots §7and right-click me: one ingot, one trade. Sneak to trade the whole stack. §8("
                    + (cap - used) + " of " + cap + " trades left this trip)");
            return;
        }
        if (used >= cap) {
            Announce.actionBar(p, "§6The trader has had enough gold from you this trip §7(" + cap + " ingots).");
            return;
        }
        int n = Math.min(p.isShiftKeyDown() ? hand.getCount() : 1, cap - used);
        hand.shrink(n);
        run.bartered.put(p.getUUID(), used + n);
        long money = 0;
        boolean jackpot = false;
        for (int i = 0; i < n; i++) {
            Offer o = roll(rng);
            jackpot |= o.jackpot();
            if (o.item() == null) {
                money += o.money();
                continue;
            }
            ItemStack s = new ItemStack(BuiltInRegistries.ITEM.getValue(Identifier.withDefaultNamespace(o.item())), o.count());
            if (!p.getInventory().add(s) || !s.isEmpty()) p.drop(s, false, Prediction.SERVER_ONLY);
        }
        if (money > 0) Economy.add(server, p.getUUID(), p.getName().getString(), money);
        ServerLevel level = (ServerLevel) p.level();
        level.playSound(null, p.getX(), p.getY(), p.getZ(), jackpot ? SoundEvents.UI_TOAST_CHALLENGE_COMPLETE : SoundEvents.CHEST_OPEN, SoundSource.PLAYERS, 0.7f, jackpot ? 1.2f : 1.6f);
        if (jackpot) {
            level.sendParticles(ParticleTypes.WAX_ON, p.getX(), p.getY() + 1, p.getZ(), 30, 0.5, 0.6, 0.5, 0.2);
            Lobby.msg(p, "§6§lJACKPOT! §7The trader hands over something special" + (money > 0 ? " §a(+" + Economy.format(money) + ")" : "") + ".");
        }
        Announce.actionBar(p, "§6Traded " + n + " gold §7(" + (cap - used - n) + " trades left this trip)" + (money > 0 ? " §a+" + Economy.format(money) : ""));
    }

    private static Offer roll(Random rng) {
        int total = 0;
        for (Offer o : TABLE) total += o.weight();
        int r = rng.nextInt(total);
        for (Offer o : TABLE) {
            r -= o.weight();
            if (r < 0) return o;
        }
        return TABLE.get(0);
    }
}
