package com.aidan.skyserver;

import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.Projectile;

import java.util.*;

/**
 * Who did how much damage to each player, for splitting kill money (Gold Wars, Skywars, Bedwars).
 * Once a player heals 2 hearts without being hurt in between, the slate is wiped: only damage from
 * then on counts. Anyone under 10% of the damage gets nothing (their part goes to the others).
 *
 * 5.8: the games ask for shares when someone dies, so this is also where the biggest share's kill
 * effect cosmetic plays (Cosmetics.kill).
 */
public final class DamageCredit {
    private static final Map<UUID, Map<UUID, Float>> DAMAGE = new HashMap<>(); // victim -> attacker -> damage
    private static final Map<UUID, Float> HEALED = new HashMap<>();             // victim -> health regained since last hit
    private static final Map<UUID, Float> LAST_HEALTH = new HashMap<>();
    public static final float RESET_AFTER_HEAL = 4f;   // 2 hearts
    public static final double MIN_SHARE = 0.10;

    private DamageCredit() {}

    public static void init() {
        ServerLivingEntityEvents.AFTER_DAMAGE.register((entity, source, baseDamage, damageTaken, blocked) -> {
            if (!(entity instanceof ServerPlayer victim) || damageTaken <= 0) return;
            float before = LAST_HEALTH.getOrDefault(victim.getUUID(), victim.getMaxHealth());
            float dealt = Math.min(damageTaken, Math.max(0.5f, before)); // overkill doesn't count extra
            HEALED.put(victim.getUUID(), 0f);
            LAST_HEALTH.put(victim.getUUID(), victim.getHealth());
            Entity a = source.getEntity();
            if (a instanceof Projectile pr && pr.getOwner() != null) a = pr.getOwner();
            if (!(a instanceof ServerPlayer attacker) || attacker == victim) return;
            DAMAGE.computeIfAbsent(victim.getUUID(), k -> new HashMap<>()).merge(attacker.getUUID(), dealt, Float::sum);
        });
        ServerTickEvents.END_SERVER_TICK.register(s -> Safe.run("damage credit", () -> tick(s)));
    }

    private static void tick(MinecraftServer server) {
        for (ServerPlayer p : server.getPlayerList().getPlayers())
            if (!DAMAGE.containsKey(p.getUUID())) LAST_HEALTH.put(p.getUUID(), p.getHealth());
        if (DAMAGE.isEmpty()) return;
        for (Iterator<Map.Entry<UUID, Map<UUID, Float>>> it = DAMAGE.entrySet().iterator(); it.hasNext(); ) {
            UUID id = it.next().getKey();
            ServerPlayer p = server.getPlayerList().getPlayer(id);
            if (p == null) {
                it.remove();
                continue;
            }
            float now = p.getHealth(), before = LAST_HEALTH.getOrDefault(id, now);
            if (now > before) {
                float healed = HEALED.merge(id, now - before, Float::sum);
                if (healed >= RESET_AFTER_HEAL) {
                    it.remove();          // healed 2 hearts without being hit: start counting again
                    HEALED.put(id, 0f);
                }
            }
            LAST_HEALTH.put(id, now);
        }
    }

    /**
     * Shares of a kill: attacker -> fraction (adds up to 1). If nobody's damage counts (e.g. they
     * fell), the killer the game picked gets it all. Clears the victim's record.
     */
    public static Map<UUID, Double> shares(UUID victim, UUID fallbackKiller) {
        Map<UUID, Float> d = DAMAGE.remove(victim);
        HEALED.remove(victim);
        Map<UUID, Double> out = new LinkedHashMap<>();
        if (d != null && !d.isEmpty()) {
            double total = 0;
            for (float v : d.values()) total += v;
            double kept = 0;
            for (Map.Entry<UUID, Float> e : d.entrySet()) if (e.getValue() / total >= MIN_SHARE) kept += e.getValue();
            if (kept > 0) for (Map.Entry<UUID, Float> e : d.entrySet()) if (e.getValue() / total >= MIN_SHARE) out.put(e.getKey(), e.getValue() / kept);
        }
        if (out.isEmpty() && fallbackKiller != null) out.put(fallbackKiller, 1.0);
        // 5.8: kill effect cosmetic of whoever did the most.
        UUID top = null;
        double best = -1;
        for (Map.Entry<UUID, Double> e : out.entrySet()) if (e.getValue() > best) { best = e.getValue(); top = e.getKey(); }
        final UUID killer = top;
        if (killer != null) Safe.run("kill effect", () -> com.aidan.skyserver.lobby.Cosmetics.kill(victim, killer));
        // 5.19: kill streaks and bounties (Bedwars, Skywars, Gold Wars all come through here on a death).
        com.aidan.skyserver.lobby.KillStreaks.died(victim, killer);
        return out;
    }

    /** Forget someone (left the match / respawned out of a game). */
    public static void clear(UUID victim) {
        DAMAGE.remove(victim);
        HEALED.remove(victim);
    }
}
