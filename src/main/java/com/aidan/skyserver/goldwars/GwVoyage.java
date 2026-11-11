package com.aidan.skyserver.goldwars;

import com.aidan.skyserver.lobby.Voyage;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.level.ServerPlayer;

import java.util.*;

/**
 * 5.13: Gold Wars extras that run next to GoldWarsGame without changing it.
 *  - Voyage twist Class Roulette: every real player gets a random class at the start and a new random
 *    one (never the same twice in a row) every time they die. Picking a class yourself is undone.
 *  - (Gold Rush, the other Gold Wars twist, pays from Voyage.onQuest when a gold block breaks.)
 */
public final class GwVoyage {
    private GwVoyage() {}

    private static final Random RNG = new Random();
    private static boolean wasInMatch = false, roulette = false;
    private static final Map<UUID, GwClass> PICK = new HashMap<>();
    private static final Map<UUID, Boolean> ALIVE = new HashMap<>();

    public static void init() {
        ServerTickEvents.END_SERVER_TICK.register(s -> {
            if (s.getTickCount() % 10 == 4) com.aidan.skyserver.Safe.run("gold wars voyage", GwVoyage::tick);
        });
    }

    private static void tick() {
        boolean in = GoldWarsGame.inMatchNow();
        if (in && !wasInMatch) start();
        wasInMatch = in;
        if (!in || !roulette) return;
        for (GwTeam t : GwTeam.values()) for (UUID id : GoldWarsGame.members(t)) {
            ServerPlayer p = GoldWarsGame.player(id);
            if (p == null || p instanceof com.aidan.skyserver.bots.BotPlayer) continue;
            boolean alive = GoldWarsGame.isAlive(id);
            Boolean before = ALIVE.put(id, alive);
            if (Boolean.TRUE.equals(before) && !alive) reroll(p);      // just died: a new class for the next life
            GwClass want = PICK.get(id);
            if (want == null) continue;
            // Keep the roulette's choice (the class menu doesn't change it this week).
            if (pending(id) != want) GoldWarsGame.chooseClass(p, want);
        }
    }

    private static void start() {
        PICK.clear();
        ALIVE.clear();
        roulette = Voyage.is(Voyage.Twist.CLASS_ROULETTE);
        Voyage.Twist t = Voyage.twist();
        for (GwTeam team : GwTeam.values()) for (UUID id : GoldWarsGame.members(team)) {
            ServerPlayer p = GoldWarsGame.player(id);
            if (p == null || p instanceof com.aidan.skyserver.bots.BotPlayer) continue;
            if (t != null && t.mode == Voyage.Mode.GOLDWARS) GoldWarsGame.message(p, "§6⚓ Wren's Voyage: §e" + t.display + " §7- " + t.about);
            if (roulette) reroll(p);
        }
    }

    /** A random class this player isn't already, that their team has room for. */
    private static void reroll(ServerPlayer p) {
        UUID id = p.getUUID();
        GwClass now = PICK.getOrDefault(id, GoldWarsGame.classOf(id));
        List<GwClass> all = new ArrayList<>(Arrays.asList(GwClass.values()));
        Collections.shuffle(all, RNG);
        for (GwClass c : all) {
            if (c == now) continue;
            if (GoldWarsGame.chooseClass(p, c)) {
                PICK.put(id, c);
                GoldWarsGame.message(p, "§6⚓ Class Roulette: §fyou're a §e" + c.display + " §fthis life.");
                return;
            }
        }
    }

    /** The class this player respawns as (GoldWarsGame keeps it private: read by reflection). */
    private static GwClass pending(UUID id) {
        try {
            java.lang.reflect.Field f = GoldWarsGame.class.getDeclaredField("players");
            f.setAccessible(true);
            Object gp = ((Map<?, ?>) f.get(null)).get(id);
            if (gp == null) return null;
            java.lang.reflect.Field pf = gp.getClass().getDeclaredField("pending");
            pf.setAccessible(true);
            Object v = pf.get(gp);
            return v instanceof GwClass c ? c : GoldWarsGame.classOf(id);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return PICK.get(id); // can't read it: assume it's right (no spam)
        }
    }
}
