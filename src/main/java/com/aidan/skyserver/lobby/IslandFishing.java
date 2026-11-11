package com.aidan.skyserver.lobby;

import com.aidan.skyserver.Safe;
import com.aidan.skyserver.bots.BotPlayer;
import com.aidan.skyserver.island.Islands;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.item.ItemEntity;
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

    static void init() {
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
