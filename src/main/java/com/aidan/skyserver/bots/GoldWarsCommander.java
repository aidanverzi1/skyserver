package com.aidan.skyserver.bots;

import com.aidan.skyserver.goldwars.GoldWarsBots;
import com.aidan.skyserver.goldwars.GwClass;
import com.aidan.skyserver.goldwars.GwTeam;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;

import java.util.*;

/**
 * Connects the Gold Wars match to the shared bot decision code. Holds one {@link GwStrategy}
 * (team commander) per team, and builds one {@link GwState} snapshot of the match per server
 * tick that every bot reads. The same strategy and planner code runs in the offline simulator
 * that was used to tune the bots.
 */
public final class GoldWarsCommander {
    private static final Map<GwTeam, GwStrategy> TEAMS = new EnumMap<>(GwTeam.class);
    private static final Random RNG = new Random();
    private static GwState cached;
    private static long cachedTick = -1;

    /** Route memory backed by LaneMemory (saved between restarts). */
    private static final GwStrategy.RouteMemory MEMORY = new GwStrategy.RouteMemory() {
        public double score(String map, int lane) {
            return LaneMemory.score(map, lane);
        }

        public void tried(String map, int lane) {
            LaneMemory.tried(map, lane);
        }
    };

    private GoldWarsCommander() {}

    public static GwStrategy of(GwTeam team) {
        return TEAMS.computeIfAbsent(team, t -> new GwStrategy(t.side, new Random(RNG.nextLong()), MEMORY));
    }

    /** Called when the winner is decided (null = tie): teams with bots learn how their plan did. */
    public static void matchResult(GwTeam winner) {
        String map = GoldWarsBots.mapId();
        for (Map.Entry<GwTeam, GwStrategy> e : TEAMS.entrySet()) {
            GwTeam t = e.getKey();
            if (e.getValue().plan() == null) continue;
            int ours = GoldWarsBots.gold(t).size(), theirs = GoldWarsBots.gold(t.other()).size();
            double result = (winner == t ? 1 : winner == null ? 0.5 : 0) + 0.3 * (ours - theirs) / 24.0;
            GwLearning.planResult(map, e.getValue().plan(), result);
        }
    }

    /** The map was changed by a vote just before the match: forget plans made for the old map. */
    public static void mapChanged() {
        TEAMS.clear();
        cached = null;
        cachedTick = -1;
    }

    /** Called when a match ends. */
    public static void matchOver() {
        TEAMS.clear();
        cached = null;
        cachedTick = -1;
        LaneMemory.save();
        BotMemory.matchEnded();
    }

    /** The match as the decision code sees it, built once per server tick. */
    public static GwState snapshot(MinecraftServer server, LevelWorld world) {
        long tick = server.getTickCount();
        if (cached != null && cachedTick == tick) return cached;
        List<GwState.Unit> units = new ArrayList<>();
        for (GwTeam team : GwTeam.values()) {
            for (UUID id : GoldWarsBots.members(team)) {
                ServerPlayer p = server.getPlayerList().getPlayer(id);
                GwClass cls = GoldWarsBots.cls(id);
                String clsId = cls == null ? "knight" : cls.id;
                Difficulty diff = p instanceof BotPlayer b ? b.difficulty() : null;
                if (p == null) {
                    units.add(new GwState.Unit(id, team.side, clsId, diff, false, 0, 0, 0, 0, null, -10000));
                    continue;
                }
                boolean alive = GoldWarsBots.alive(id) && !p.isSpectator() && p.level() == GoldWarsBots.level();
                LivingEntity attacker = p.getLastHurtByMob();
                long hurtTick = attacker == null ? -10000 : tick - (p.tickCount - p.getLastHurtByMobTimestamp());
                units.add(new GwState.Unit(id, team.side, clsId, diff, alive, p.getX(), p.getY(), p.getZ(), p.getHealth(),
                        attacker == null ? null : attacker.getUUID(), hurtTick));
            }
        }
        cached = new GwState(tick, GoldWarsBots.isBuildPhase(), GoldWarsBots.isOvertime(), GoldWarsBots.timerTicks(),
                GoldWarsBots.mapId(), GoldWarsBots.floorY(), units, packed(GwTeam.RED), packed(GwTeam.BLUE), world);
        cachedTick = tick;
        GwLearning.observe(cached); // watch how real players play this map
        return cached;
    }

    private static Set<Long> packed(GwTeam team) {
        Set<Long> set = new HashSet<>();
        for (BlockPos g : GoldWarsBots.gold(team)) set.add(GwState.pack(g.getX(), g.getY(), g.getZ()));
        return set;
    }
}
