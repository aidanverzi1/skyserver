package com.aidan.skyserver;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.advancements.AdvancementTree;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.ServerAdvancementManager;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.ServerRecipeBook;
import net.minecraft.world.item.crafting.RecipeHolder;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.*;

/**
 * Small server-wide changes:
 *  - No vanilla advancements: they're removed when the server starts (and after /reload), so no
 *    toasts and no "has made the advancement" messages.
 *  - Every recipe is unlocked in the recipe book when a player joins, quietly (no popups).
 *  - No crop trampling (FarmlandMixin asks isLandingOnFarmland()).
 */
public final class WorldTweaks {
    private static final ThreadLocal<Boolean> LANDING = ThreadLocal.withInitial(() -> false);

    private WorldTweaks() {}

    public static void init() {
        ServerLifecycleEvents.SERVER_STARTED.register(s -> {
            removeAdvancements(s);
            command(s, "gamerule show_advancement_messages false");
        });
        ServerLifecycleEvents.END_DATA_PACK_RELOAD.register((s, rm, ok) -> removeAdvancements(s));
        ServerPlayConnectionEvents.JOIN.register((handler, sender, s) -> {
            ServerPlayer p = handler.getPlayer();
            if (p instanceof com.aidan.skyserver.bots.BotPlayer) return;
            Safe.run("unlock recipes", () -> unlockAllRecipes(s, p));
        });
    }

    // ---- Crop trampling ----

    public static void landingOnFarmland(boolean on) {
        LANDING.set(on);
    }

    public static boolean isLandingOnFarmland() {
        return LANDING.get();
    }

    // ---- Advancements ----

    private static void removeAdvancements(MinecraftServer s) {
        ServerAdvancementManager mgr = s.getAdvancements();
        int changed = 0;
        try {
            for (Field f : ServerAdvancementManager.class.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                f.setAccessible(true);
                if (Map.class.isAssignableFrom(f.getType())) {
                    f.set(mgr, new HashMap<>());
                    changed++;
                } else if (f.getType() == AdvancementTree.class) {
                    f.set(mgr, new AdvancementTree());
                    changed++;
                }
            }
            Skyserver.LOGGER.info("Vanilla advancements removed ({} lists cleared).", changed);
        } catch (Throwable t) {
            Skyserver.LOGGER.warn("Couldn't remove vanilla advancements: {} (advancement messages are still turned off).", t.toString());
        }
    }

    // ---- Recipes ----

    private static Field knownField;
    private static Method sendBook;
    private static boolean looked = false;

    /** Finds the recipe book's "known recipes" set and its "send the whole book" method. */
    private static void lookUp() {
        if (looked) return;
        looked = true;
        try {
            for (Field f : ServerRecipeBook.class.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers()) || !Set.class.isAssignableFrom(f.getType())) continue;
                f.setAccessible(true);
                knownField = f; // the first set is the known recipes (the second is "highlighted")
                break;
            }
            List<Method> candidates = new ArrayList<>();
            for (Method m : ServerRecipeBook.class.getDeclaredMethods()) {
                if (m.getReturnType() == void.class && m.getParameterCount() == 1 && m.getParameterTypes()[0] == ServerPlayer.class) candidates.add(m);
            }
            if (candidates.size() == 1) {
                sendBook = candidates.get(0);
                sendBook.setAccessible(true);
            }
        } catch (Throwable t) {
            knownField = null;
            sendBook = null;
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void unlockAllRecipes(MinecraftServer s, ServerPlayer p) {
        Collection<RecipeHolder<?>> all = s.getRecipeManager().getRecipes();
        lookUp();
        ServerRecipeBook book = p.getRecipeBook();
        if (knownField != null && sendBook != null) {
            try {
                Set known = (Set) knownField.get(book);
                int before = known.size();
                for (RecipeHolder<?> h : all) known.add(h.id());
                if (known.size() != before) sendBook.invoke(book, p); // the whole book at once, without popups
                return;
            } catch (Throwable t) {
                Skyserver.LOGGER.warn("Quiet recipe unlock failed ({}); using the normal unlock.", t.toString());
                knownField = null;
            }
        }
        p.awardRecipes(all);
    }

    private static void command(MinecraftServer s, String cmd) {
        try {
            s.getCommands().performPrefixedCommand(s.createCommandSourceStack().withSuppressedOutput(), cmd);
        } catch (Throwable t) {
            Skyserver.LOGGER.warn("Couldn't run '{}': {}", cmd, t.toString());
        }
    }
}
