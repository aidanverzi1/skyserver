package com.aidan.skyserver.lobby;

import com.aidan.skyserver.Safe;
import com.aidan.skyserver.bots.BotPlayer;
import com.aidan.skyserver.island.Islands;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.phys.AABB;

import java.util.*;

/**
 * 5.17: fishing on your Sky Island. No money (the items are the reward, as in vanilla), but every catch
 * rolls the same key chance as the lobby pond. Keys share the pond's daily limit of key-eligible catches
 * (Activities.FISH_KEY_CATCHES_PER_DAY) and its AFK check (move or look around between catches).
 *
 * How a catch is spotted: when an item appears in the island world right next to a fishing bobber that
 * is in water, that bobber's owner just reeled something in. Each bobber counts once.
 * Started from Activities.init().
 */
public final class IslandFishing {
    private static final Map<UUID, Long> COUNTED = new HashMap<>(); // bobber -> tick counted
    private static long lastClean = 0;

    private IslandFishing() {}

    // ---- 5.25: the Fishing skill makes fish bite sooner (lobby pond and islands) ----
    private static final Random RNG = new Random();
    /** FishingHook's private "ticks until a fish shows up" counter. */
    private static java.lang.reflect.Field lureField;
    private static boolean lureBroken;

    private static java.lang.reflect.Field lureField() {
        if (lureField != null || lureBroken) return lureField;
        try {
            lureField = FishingHook.class.getDeclaredField("timeUntilLured");
            lureField.setAccessible(true);
        } catch (Throwable t) {
            lureBroken = true;
            com.aidan.skyserver.Skyserver.LOGGER.error("[SkyServer] Fishing skill speed is OFF: FishingHook.timeUntilLured not found ({}) - tell Claude.", t.toString());
        }
        return lureField;
    }

    /**
     * Every tick: a wait that should be f shorter runs 1 / (1 - f) times as fast, so on average f / (1 - f) extra ticks
     * come off the bobber's wait each tick. Works on top of the Lure enchantment; never below Activities.FISH_MIN_WAIT_TICKS.
     */
    private static void speedTick(MinecraftServer s) {
        java.lang.reflect.Field f = lureField();
        if (f == null) return;
        for (ServerPlayer p : s.getPlayerList().getPlayers()) {
            FishingHook hook = p.fishing;
            if (hook == null || p instanceof BotPlayer) continue;
            if (!Islands.isIsland(p.level()) && !Lobby.isLobby(p.level())) continue;
            double cut = Activities.fishBitePercent(Skills.level(p.getUUID(), Skills.Skill.FISHING)) / 100.0;
            if (cut <= 0 || RNG.nextDouble() >= cut / (1 - cut)) continue;
            try {
                int left = f.getInt(hook);
                if (left > Activities.FISH_MIN_WAIT_TICKS) f.setInt(hook, left - 1);
            } catch (IllegalAccessException e) {
                lureBroken = true;
                lureField = null;
                return;
            }
        }
    }

    static void init() {
        ServerTickEvents.END_SERVER_TICK.register(s -> Safe.run("fishing speed", () -> speedTick(s)));
        ServerEntityEvents.ENTITY_LOAD.register((entity, world) -> {
            if (!(entity instanceof ItemEntity item) || !Islands.isIsland(world)) return;
            Safe.run("island fishing", () -> {
                AABB box = item.getBoundingBox().inflate(1.5);
                for (Projectile hook : world.getEntitiesOfClass(Projectile.class, box, e -> e.getType() == EntityTypes.FISHING_BOBBER)) {
                    if (!(hook.getOwner() instanceof ServerPlayer p) || p instanceof BotPlayer || !hook.isInWater()) continue;
                    long t = world.getServer().getTickCount();
                    if (t - lastClean > 1200) {
                        COUNTED.values().removeIf(v -> t - v > 1200);
                        lastClean = t;
                    }
                    if (COUNTED.putIfAbsent(hook.getUUID(), t) != null) return;
                    Activities.islandCatch(p);
                    return;
                }
            });
        });
    }
}
