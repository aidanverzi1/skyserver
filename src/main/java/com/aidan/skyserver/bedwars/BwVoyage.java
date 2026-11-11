package com.aidan.skyserver.bedwars;

import com.aidan.skyserver.lobby.CosmeticsPlus;
import com.aidan.skyserver.lobby.Voyage;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;

import java.util.*;

/**
 * 5.13: Bedwars extras that run next to BedwarsGame without changing it.
 *  - Wren's Weekly Voyage twists, fixed when a match starts:
 *      Fireball Frenzy (BwRules.fireballFrenzy: half price; FireballDamage: +25%),
 *      Rush Hour (BwRules.rushHour: events every 4 min, beds at 20, sudden death at 25; and every
 *      generator drop is doubled here), Armistice (every bed is "blanketed" for the first 5 minutes),
 *      Emerald Tide (team forges drop emeralds from the start).
 *  - Bed-break cosmetics (CosmeticsPlus): played where a bed someone breaks was.
 * Must be registered AFTER BedwarsGame and BEFORE BwForge (the doubled drops are then shared too).
 */
public final class BwVoyage {
    private BwVoyage() {}

    private static int match = -1;
    private static boolean armistice, emeraldTide, rush;
    private static final Map<BwTeam, Long> NEXT_EMERALD = new EnumMap<>(BwTeam.class);
    private static final Map<UUID, Integer> BEDS = new HashMap<>();
    private static final Map<BwTeam, Boolean> BED_UP = new EnumMap<>(BwTeam.class);

    public static void init() {
        ServerTickEvents.END_SERVER_TICK.register(s -> com.aidan.skyserver.Safe.run("bedwars voyage", BwVoyage::tick));
    }

    private static void tick() {
        if (!BedwarsGame.playing()) return;
        ServerLevel level = BedwarsGame.level();
        if (level == null) return;
        if (BedwarsGame.matchNumber() != match) start();
        if (rush) doubleDrops(level);
        if (emeraldTide) emeralds(level);
        bedEffects(level);
    }

    private static void start() {
        match = BedwarsGame.matchNumber();
        BwRules.fireballFrenzy = Voyage.is(Voyage.Twist.FIREBALL_FRENZY);
        BwRules.rushHour = rush = Voyage.is(Voyage.Twist.RUSH_HOUR);
        armistice = Voyage.is(Voyage.Twist.ARMISTICE);
        emeraldTide = Voyage.is(Voyage.Twist.EMERALD_TIDE);
        NEXT_EMERALD.clear();
        BEDS.clear();
        BED_UP.clear();
        for (BwTeamState ts : BedwarsGame.teams.values()) BED_UP.put(ts.team, ts.bed);
        Voyage.Twist t = Voyage.twist();
        if (t != null && t.mode == Voyage.Mode.BEDWARS) BedwarsGame.broadcast("§6⚓ Wren's Voyage: §e" + t.display + " §7- " + t.about);
        if (armistice) {
            long until = BedwarsGame.now() + 5 * 60 * 20L;
            for (BwTeamState ts : BedwarsGame.teams.values()) if (!ts.out) ts.blanketUntil = until;
        }
    }

    /** Rush Hour: every new iron, gold, diamond or emerald on a generator gets a twin. */
    private static void doubleDrops(ServerLevel level) {
        BwMapDesigns.MapData m = BwMap.data();
        List<BlockPos> gens = new ArrayList<>();
        for (BwTeam t : BwTeam.values()) gens.add(BwMap.pos(m.gen[t.ordinal()]));
        for (int[] d : m.diamondGens) gens.add(BwMap.pos(d));
        for (int[] e : m.emeraldGens) gens.add(BwMap.pos(e));
        for (BlockPos g : gens) {
            for (ItemEntity ie : level.getEntitiesOfClass(ItemEntity.class, new AABB(g).inflate(0.6, 0.6, 0.6),
                    ie -> ie.tickCount == 0 && BedwarsGame.resource(ie.getItem()) >= 0)) {
                ItemEntity twin = new ItemEntity(level, ie.getX(), ie.getY(), ie.getZ(), ie.getItem().copy(), 0, 0, 0);
                twin.setPickUpDelay(0);
                level.addFreshEntity(twin);
            }
        }
    }

    /** Emerald Tide: team forges drop emeralds before Forge III. */
    private static void emeralds(ServerLevel level) {
        BwMapDesigns.MapData m = BwMap.data();
        long now = BedwarsGame.matchTicks();
        for (BwTeamState ts : BedwarsGame.teams.values()) {
            if (ts.out || ts.level(BwRules.Upgrade.FORGE) >= 3) continue;
            long next = NEXT_EMERALD.getOrDefault(ts.team, (long) BwRules.HOME_EMERALD_TICKS / 2);
            if (now < next) continue;
            NEXT_EMERALD.put(ts.team, now + BwRules.HOME_EMERALD_TICKS);
            BlockPos g = BwMap.pos(m.gen[ts.team.ordinal()]);
            int have = 0;
            for (ItemEntity ie : level.getEntitiesOfClass(ItemEntity.class, new AABB(g).inflate(1.5), ie -> ie.getItem().is(Items.EMERALD)))
                have += ie.getItem().getCount();
            if (have >= BwRules.HOME_EMERALD_CAP) continue;
            ItemEntity ie = new ItemEntity(level, g.getX() + 0.5, g.getY() + 0.05, g.getZ() + 0.5, new ItemStack(Items.EMERALD), 0, 0, 0);
            ie.setPickUpDelay(0);
            level.addFreshEntity(ie);
        }
    }

    /** Spots a bed break (someone's bed count went up and a bed went down) and plays their effect there. */
    private static void bedEffects(ServerLevel level) {
        BwTeam broke = null;
        for (BwTeamState ts : BedwarsGame.teams.values()) {
            Boolean was = BED_UP.put(ts.team, ts.bed);
            if (Boolean.TRUE.equals(was) && !ts.bed) broke = ts.team;
        }
        for (Map.Entry<UUID, BwPlayer> e : BedwarsGame.players.entrySet()) {
            int now = e.getValue().beds;
            Integer before = BEDS.put(e.getKey(), now);
            if (before == null || now <= before || broke == null) continue;
            ServerPlayer p = BedwarsGame.online(e.getKey());
            if (p != null && !e.getValue().bot) CosmeticsPlus.bedBreak(p, level, BwMap.pos(BwMap.data().bedFoot[broke.ordinal()]));
        }
    }
}
