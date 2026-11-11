package com.aidan.skyserver.expedition;

import com.aidan.skyserver.lobby.Voyage;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;

/**
 * 5.13: Nether Expedition hooks for Wren's Weekly Voyage, without changing Expedition.java.
 *  - Wren's Bounty: trip prices 20% off while it runs (Expedition.PRICES is lowered, and put back
 *    afterwards). The extra room-chest roll is in ExpeditionLoot.chestLoot.
 *  - A boss kill (ExpeditionLoot.vault is called once when the boss dies) counts a cleared run for
 *    every real player in the expedition world.
 */
public final class ExpVoyage {
    private ExpVoyage() {}

    private static long[] original;

    public static void init() {
        ServerTickEvents.END_SERVER_TICK.register(s -> {
            if (s.getTickCount() % 20 == 11) com.aidan.skyserver.Safe.run("expedition voyage", ExpVoyage::prices);
        });
    }

    private static void prices() {
        if (original == null) original = Expedition.PRICES.clone();
        boolean bounty = Voyage.is(Voyage.Twist.WRENS_BOUNTY);
        for (int i = 0; i < original.length && i < Expedition.PRICES.length; i++) {
            long want = bounty ? Math.round(original[i] * 0.8) : original[i];
            if (Expedition.PRICES[i] != want) Expedition.PRICES[i] = want;
        }
    }

    /** Wren's Bounty: one extra roll on every room-chest item. */
    static int bonusRolls() {
        return Voyage.is(Voyage.Twist.WRENS_BOUNTY) ? 1 : 0;
    }

    /** The boss died (from ExpeditionLoot.vault). */
    /** d = difficulty 0 Easy .. 3 Nightmare (board points: Easy 1, Normal 2, Hard 3, Nightmare 4). */
    static void bossKilled(ServerLevel level, int d) {
        List<ServerPlayer> crew = new ArrayList<>();
        for (ServerPlayer p : level.players()) if (!(p instanceof com.aidan.skyserver.bots.BotPlayer) && !p.isSpectator()) crew.add(p);
        if (crew.isEmpty()) return;
        for (ServerPlayer p : crew) com.aidan.skyserver.lobby.PlayerStats.add(p, "expedition_clear", 1);
        com.aidan.skyserver.lobby.Playtime.game("expedition");
        Voyage.onBossKilled(crew, d);
    }
}
