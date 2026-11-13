package com.aidan.skyserver.bots;

import com.aidan.skyserver.bedwars.BedwarsBots;
import com.aidan.skyserver.bedwars.BwTeam;
import com.aidan.skyserver.lobby.Stacks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.*;

/**
 * Bedwars bot. The team picks a plan (learned: see BwLearning) and gives each bot a role
 * (BwStrategy): defenders wrap the bed, collectors fetch diamonds and emeralds and buy team
 * upgrades, rushers gear up, bridge over, kill the defenders and break the bed. With no bed left
 * everyone hunts, and in sudden death they head for the middle. All bots share this logic; the
 * difficulty only changes how well they fight (aim, reactions, clicking).
 *
 * 5.9 playbook (Verzi10):
 *  - Always doing something. One bot works at home (within ~20 blocks: generator, team chest,
 *    defence, end stone cover); the job rotates to whoever comes home. Nobody stands guard idle.
 *    No bot needs to stay if a real teammate is home. A lone bot does short loops.
 *  - Diamonds: grab what's there and go straight home (at most a few seconds' wait).
 *  - Emeralds: first trip right after the team's first Forge, then about every 3 minutes (learned).
 *  - Bed attacked (enemy within 10 blocks of the bed, the bell / a trap / the alarm) -> everyone comes
 *    back (ender pearl if far). A bot already breaking an enemy bed finishes the job first.
 *  - End stone cover over the defence from about minute 5 (5.19), and reachable wool in it is swapped for end stone.
 *  - 5.19: a bot with a bow shoots bridgers heading our way as soon as they're within 32 blocks and in sight.
 *  - Bridgers are only intercepted when they're within 15 blocks of the base; no waiting at the edge.
 *  - Buys by situation: fireballs/TNT and tools that match the enemy bed's defence, a bow when people
 *    bridge at us, golden apples before fights, a Rescue Cloud after being knocked into the void.
 *
 * 5.13 (fair play against real players):
 *  - No gang-ups: at most ONE bot of a team goes after the same real player at a time (a "claim",
 *    shared by the team's bots). Exceptions: a real player within GANG_UP_BED_RANGE blocks of the
 *    team's own bed - then every bot of that team may fight him - and a bot he just hit hits back.
 *  - A real player who is breaking a THIRD team's bed is left alone by everyone else (they want that
 *    bed broken too), unless he hits them or comes for their own bed.
 *  - Bed commit: once a bot is at an enemy bed it keeps breaking until the bed is gone (or it dies);
 *    it only turns round to fight someone who is actually hitting it.
 *
 * Like the real players, bots use the team chest: whenever they're home they put what they don't
 * need in it, and they take from it when it has what the next purchase or team upgrade is short of.
 */
public final class BedwarsBrain extends BotBrain {
    // ---- Team plan and roles, shared by a team's bots for one match ----
    private static final Map<BwTeam, Map<UUID, BwStrategy.Role>> ROLES = new EnumMap<>(BwTeam.class);
    private static int rolesMatch = -1;

    /** 5.13: a real player this close to a team's bed may be fought by all of that team's bots. */
    static final double GANG_UP_BED_RANGE = 8;
    /** 5.13: a real player this close to a third team's bed counts as "breaking someone else's bed". */
    static final double THIRD_BED_RANGE = 10;
    /** 5.13: a claim on a real player lasts this many match ticks unless the bot keeps it up. */
    static final long CLAIM_TICKS = 40;
    /** 5.13: once this close to the enemy bed while attacking it, the bot commits to breaking it. */
    static final double COMMIT_RANGE = 7, COMMIT_LEAVE_RANGE = 16;

    /** 5.9: what a team's bots share during a match (times are match ticks). */
    private static final class TeamState {
        long lastEmerald = -1;
        UUID homeWorker;
        long homeWorkerUntil = 0;
        long alarmUntil = -1, alarmLogged = -10_000;
        long lastBridger = -100_000;
        int lastMissing = -1;
        /** 5.13: real player -> the one bot of this team going after him, and when it last said so. */
        final Map<UUID, UUID> claim = new HashMap<>();
        final Map<UUID, Long> claimAt = new HashMap<>();
    }

    private static final Map<BwTeam, TeamState> TEAM = new EnumMap<>(BwTeam.class);
    private static int teamMatch = -1;

    private boolean wasAlive = false;
    private int match = -1;
    private BwTeam team;
    private BwStrategy.Role role = BwStrategy.Role.DEFENDER;
    private int target = -1;
    private String doing = "waiting";
    private long eatingUntil = 0, nextBuy = 0, nextFireball = 0, guardLook = 0, nextPearl = 0;
    private final Map<UUID, Long> ignore = new HashMap<>();
    private final Map<Long, Long> skipCell = new HashMap<>();
    private UUID fightTarget;
    private int fightFails = 0;
    private long goingHomeUntil = 0;
    /** Team chest: next time we may use it, no banking until (just took something), stop at home between trips. */
    private long chestCooldown = 0, noBankUntil = 0, homeStopUntil = 0;
    /** 5.13: the enemy team whose bed this bot is committed to breaking (-1 = none). */
    private int commitBed = -1;

    /** 5.9 trips: where the bot is heading on a run, and how the stop at the generator is going. */
    private enum Trip { NONE, DIAMOND, EMERALD, HOME }

    private Trip trip = Trip.NONE;
    private long tripStart = 0, atGenSince = -1, gainedAt = -1, lastLoopEnd = 0;
    private int resWhenArrived = 0;
    private boolean soloLoop = false;
    /** When this bot last fell into the void (wants a Rescue Cloud for a while after). */
    private long knockedOffAt = -100_000;
    private int enemyDefence = 0, enemyDefenceFor = -1;
    private long enemyDefenceAt = -1000;

    public BedwarsBrain(BotPlayer bot) {
        super(bot);
    }

    @Override
    protected LevelWorld world() {
        BwTeam me = team;
        return new LevelWorld(level(), BedwarsBots.voidY(), pos -> BedwarsBots.canBreak(pos, me) && !ownDefence(pos), BedwarsBots::canPlace);
    }

    /** Never dig through our own bed's defence (e.g. while chasing someone at home). */
    private boolean ownDefence(BlockPos p) {
        if (team == null || !BedwarsBots.bedAlive(team)) return false;
        BlockPos foot = BedwarsBots.bedFoot(team), head = BedwarsBots.bedHead(team);
        int ring = Math.max(dist1(p.getX(), foot.getX(), head.getX()), dist1(p.getZ(), foot.getZ(), head.getZ()));
        return ring <= 3 && p.getY() >= foot.getY() && p.getY() <= foot.getY() + 3;
    }

    /** Everyone bridges at the same steady pace: difficulty only changes fighting. */
    @Override
    protected int bridgeDelay() {
        return 6;
    }

    // ---- Called by the game ----

    public void onDeath(ServerPlayer killer) {
        trip = Trip.NONE;
        soloLoop = false;
        atGenSince = -1;
        commitBed = -1;
        if (bot.getY() < BedwarsBots.floorY() - 6) {
            knockedOffAt = age;
            log("DECIDE", "knocked into the void - will buy a Rescue Cloud");
        }
        if (killer != null && !BedwarsBots.isBot(killer.getUUID()) && role == BwStrategy.Role.RUSHER) {
            BotMemory.add("bw:rusherDiedToHuman", 0, 1, 1);
        }
    }

    public void onRespawn() {
        wasAlive = false;
    }

    public void onBedBroken(BwTeam victim) {
        if (target == victim.ordinal()) doing = "bed broken, hunting " + victim.display;
        if (commitBed == victim.ordinal()) commitBed = -1;
    }

    // ---- For the training log ----

    public String roleName() {
        return role.name().toLowerCase() + "/" + planName() + (team != null && isHomeWorker() ? "/home" : "");
    }

    public String doing() {
        return doing;
    }

    public int blocks() {
        return countBlocks();
    }

    private String name() {
        return bot.getName().getString();
    }

    private void log(String kind, String text) {
        com.aidan.skyserver.bedwars.BwBotLog.event(name(), kind, text);
    }

    @Override
    public String describe() {
        return (team == null ? "" : team.display + " ") + role.name().toLowerCase() + " (" + planName() + "): " + doing;
    }

    private String planName() {
        int p = team == null ? -1 : BedwarsBots.plan(team);
        return p < 0 ? "?" : BwStrategy.Plan.values()[p].name().toLowerCase();
    }

    // =====================================================================

    private BwStrategy.Plan plan() {
        int p = BedwarsBots.plan(team);
        if (p < 0) {
            BwStrategy.Plan pick = BwLearning.pickPlan(BedwarsBots.mapId(), rng);
            BedwarsBots.setPlan(team, pick.ordinal());
            log("DECIDE", team.display + " team plan: " + pick.name().toLowerCase() + " (" + pick.text + ")");
            return pick;
        }
        return BwStrategy.Plan.values()[p];
    }

    /** Hands out roles to a team's bots once per match. */
    private BwStrategy.Role baseRole(BwStrategy.Plan plan) {
        if (rolesMatch != BedwarsBots.matchNumber()) {
            ROLES.clear();
            rolesMatch = BedwarsBots.matchNumber();
        }
        Map<UUID, BwStrategy.Role> m = ROLES.computeIfAbsent(team, t -> new HashMap<>());
        if (!m.containsKey(bot.getUUID())) {
            List<UUID> bots = new ArrayList<>();
            for (UUID u : BedwarsBots.members(team)) if (BedwarsBots.isBot(u)) bots.add(u);
            bots.sort(Comparator.naturalOrder());
            BwStrategy.Role[] r = BwStrategy.roles(plan, bots.size());
            m.clear();
            for (int i = 0; i < bots.size() && i < r.length; i++) m.put(bots.get(i), r[i]);
        }
        return m.getOrDefault(bot.getUUID(), BwStrategy.Role.DEFENDER);
    }

    private TeamState ts() {
        if (teamMatch != BedwarsBots.matchNumber()) {
            TEAM.clear();
            teamMatch = BedwarsBots.matchNumber();
        }
        return TEAM.computeIfAbsent(team, t -> new TeamState());
    }

    private int teamBots() {
        int n = 0;
        for (UUID u : BedwarsBots.members(team)) if (BedwarsBots.isBot(u)) n++;
        return n;
    }

    private boolean humanOnTeam() {
        for (UUID u : BedwarsBots.members(team)) if (!BedwarsBots.isBot(u)) return true;
        return false;
    }

    private double minute() {
        return BedwarsBots.matchTicks() / 1200.0;
    }

    private String loggedActivity = "", loggedRole = "";
    private Vec3 watchPos = null;
    private long watchSince = 0, homeUntil = 0;
    private UUID intercepting = null;

    @Override
    protected void think() {
        think0();
        if (team == null || !BedwarsBots.alive(bot.getUUID())) return;
        // Log changes of role and of activity (first two words of "doing").
        String r = role.name() + (isHomeWorker() ? " (home)" : "");
        if (!r.equals(loggedRole)) {
            loggedRole = r;
            log("DECIDE", "role is now " + r.toLowerCase());
        }
        String[] w = doing.split(" ");
        String act = w.length >= 2 ? w[0] + " " + w[1] : doing;
        if (!act.equals(loggedActivity)) {
            loggedActivity = act;
            log("DO", doing + " | " + pathDebug());
        }
        watchdog();
    }

    /**
     * Stuck watchdog: travelling but hardly moved in 15 seconds (e.g. at the end of a bridge). Logs the
     * spot and the path, then changes plan instead of standing there.
     */
    private void watchdog() {
        if (age % 20 != 0) return;
        Vec3 pos = bot.position();
        if (watchPos == null || pos.distanceTo(watchPos) > 2.0) {
            watchPos = pos;
            watchSince = age;
            return;
        }
        boolean travelling = doing.startsWith("attacking") || doing.startsWith("collecting diamonds") || doing.startsWith("collecting emeralds")
                || doing.startsWith("taking diamonds") || doing.startsWith("heading") || doing.startsWith("hunting") || doing.startsWith("sudden death")
                || doing.startsWith("out of blocks") || doing.startsWith("buying") || doing.startsWith("putting spare") || doing.startsWith("taking resources")
                || doing.startsWith("rushing home");
        if (!travelling || pathStatus() == PathStatus.ARRIVED || atGenSince >= 0) {
            watchSince = age;
            return;
        }
        if (age - watchSince < 300) return;
        log("STUCK", String.format("no progress for 15s at %.1f %.1f %.1f while '%s', %d blocks | %s", pos.x, pos.y, pos.z, doing, countBlocks(), pathDebug()));
        watchSince = age;
        clearPath();
        commitBed = -1;
        if (doing.startsWith("putting spare") || doing.startsWith("taking resources")) chestCooldown = age + 1200; // can't get to the chest: forget it for a minute
        switch (role) {
            case RUSHER, HUNTER -> {
                if (target >= 0) ignoreTeamUntil.put(target, age + 600L); // try someone else for 30 s
                target = -1;
                if (countBlocks() < 16) goingHomeUntil = age + 400;
            }
            case COLLECTOR -> {
                trip = Trip.NONE;
                homeUntil = age + 400;
            }
            default -> {
                trip = Trip.NONE;
                homeUntil = age + 200;
            }
        }
    }

    private final Map<Integer, Long> ignoreTeamUntil = new HashMap<>();

    private void think0() {
        UUID id = bot.getUUID();
        if (!BedwarsBots.inMatch() || !BedwarsBots.alive(id) || BedwarsBots.level() != level()) {
            wasAlive = false;
            return;
        }
        team = BedwarsBots.team(id);
        if (team == null) return;
        if (match != BedwarsBots.matchNumber()) {
            match = BedwarsBots.matchNumber();
            target = -1;
            commitBed = -1;
            ignore.clear();
            skipCell.clear();
            trip = Trip.NONE;
            soloLoop = false;
            knockedOffAt = -100_000;
            chestCooldown = noBankUntil = homeStopUntil = 0;
            lastLoopEnd = age;
        }
        if (!wasAlive) {
            wasAlive = true;
            resetBody();
            atGenSince = -1;
            fightTarget = null;
            trip = Trip.NONE;
            commitBed = -1;
        }
        BwStrategy.Plan plan = plan();
        int prot = BedwarsBots.upgradeLevels(team)[1];
        role = BwStrategy.current(plan, baseRole(plan), minute(), BedwarsBots.bedAlive(team), prot, BedwarsBots.suddenDeath(), teamBots());
        updateAlarm();

        if (age < eatingUntil) {
            if (bot.isUsingItem()) return;
            eatingUntil = 0;
        }
        if (bot.getHealth() < 10 && heal()) return;

        // 5.13: committed to an enemy bed: keep breaking it; only turn round for someone actually hitting us.
        if (committedBed()) {
            ServerPlayer hitter = hittingMe(5);
            if (hitter != null) {
                doing = "fighting " + hitter.getName().getString() + " at the bed";
                fight(hitter);
                return;
            }
            target = commitBed;
            stopShooting();
            fightTarget = null;
            breakBed(BwTeam.values()[commitBed]);
            return;
        }

        ServerPlayer bridger = bridgerToStop();
        ServerPlayer enemy = threat();
        if (enemy != null && (bridger == null || distanceTo(enemy) < 6)) {
            doing = "fighting " + enemy.getName().getString();
            claim(enemy);
            fight(enemy);
            return;
        }
        // The bed is under attack: everyone comes back (unless already breaking an enemy bed).
        if (alarm() && !finishingEnemyBed()) {
            rushHome();
            return;
        }
        if (bridger != null && intercept(bridger)) {
            claim(bridger);
            return;
        }
        intercepting = null;
        if (age < homeUntil) {
            collectHome("recovering after getting stuck");
            return;
        }
        stopShooting();
        fightTarget = null;
        if (nearHome(18) && shop(plan)) return;
        if (nearHome(18) && useChest(plan)) return;
        // One bot always works at home (the job rotates); it builds the defence and the cover.
        if (isHomeWorker() && role != BwStrategy.Role.HUNTER) {
            homeWork(plan);
            return;
        }
        switch (role) {
            case DEFENDER -> defend(plan);
            case COLLECTOR -> collect(plan);
            case RUSHER -> rush(plan);
            case HUNTER -> hunt(plan);
        }
    }

    // =====================================================================
    // 5.13: fair play against real players (claims, third-party beds, bed commit)
    // =====================================================================

    private boolean isHuman(ServerPlayer e) {
        return !BedwarsBots.isBot(e.getUUID());
    }

    /** True if e hit this bot in the last 3 seconds. */
    private boolean hurtBy(ServerPlayer e) {
        return bot.getLastHurtByMob() == e && bot.tickCount - bot.getLastHurtByMobTimestamp() < 60;
    }

    /** An enemy within `range` blocks who hit this bot in the last 3 seconds, or null. */
    private ServerPlayer hittingMe(double range) {
        if (!(bot.getLastHurtByMob() instanceof ServerPlayer e)) return null;
        if (bot.tickCount - bot.getLastHurtByMobTimestamp() >= 60 || !BedwarsBots.alive(e.getUUID())) return null;
        BwTeam et = BedwarsBots.team(e.getUUID());
        if (et == null || et == team || distanceTo(e) > range) return null;
        return e;
    }

    /** A real player close to our own bed: every bot of the team may fight him. */
    private boolean nearOurBed(ServerPlayer e, double range) {
        return BedwarsBots.bedAlive(team) && e.position().distanceTo(Vec3.atCenterOf(BedwarsBots.bedFoot(team))) < range;
    }

    /** A real player at a THIRD team's standing bed (not ours, not his own). */
    private boolean atThirdBed(ServerPlayer e) {
        BwTeam et = BedwarsBots.team(e.getUUID());
        for (BwTeam t : BwTeam.values()) {
            if (t == team || t == et || !BedwarsBots.bedAlive(t)) continue;
            if (e.position().distanceTo(Vec3.atCenterOf(BedwarsBots.bedFoot(t))) < THIRD_BED_RANGE) return true;
        }
        return false;
    }

    /**
     * May this bot go after e right now? Bots: always. Real players: not while he's breaking a third
     * team's bed (unless he hit us or is at our bed), and only if no other bot of our team has him
     * claimed (unless he's at our bed, or he hit us).
     */
    private boolean mayTarget(ServerPlayer e) {
        if (!isHuman(e)) return true;
        boolean hitMe = hurtBy(e);
        if (nearOurBed(e, GANG_UP_BED_RANGE) || hitMe) return true;
        if (atThirdBed(e) && !nearOurBed(e, THIRD_BED_RANGE + 1)) return false;
        TeamState s = ts();
        UUID owner = s.claim.get(e.getUUID());
        if (owner == null || owner.equals(bot.getUUID())) return true;
        long at = s.claimAt.getOrDefault(e.getUUID(), -1_000L);
        return BedwarsBots.matchTicks() - at > CLAIM_TICKS || !BedwarsBots.alive(owner);
    }

    /** Marks a real player as this bot's (the rest of the team leaves him to us). */
    private void claim(ServerPlayer e) {
        if (e == null || !isHuman(e)) return;
        TeamState s = ts();
        UUID prev = s.claim.put(e.getUUID(), bot.getUUID());
        s.claimAt.put(e.getUUID(), BedwarsBots.matchTicks());
        if (prev == null || !prev.equals(bot.getUUID())) log("DECIDE", "I'll take " + e.getName().getString() + " (the rest of the team leaves him to me)");
    }

    /** Still committed to breaking an enemy bed? Drops the commitment when the bed is gone or we wandered off. */
    private boolean committedBed() {
        if (commitBed < 0) return false;
        BwTeam tt = BwTeam.values()[commitBed];
        if (!BedwarsBots.bedAlive(tt) || BedwarsBots.blanketed(tt)
                || bot.getEyePosition().distanceTo(Vec3.atCenterOf(BedwarsBots.bedFoot(tt))) > COMMIT_LEAVE_RANGE) {
            commitBed = -1;
            return false;
        }
        return true;
    }

    // =====================================================================
    // The home job and the bed alarm (5.9)
    // =====================================================================

    /**
     * Is this bot the one working at home right now? Nobody is needed if a real teammate is home.
     * The job goes to the bot best placed (nearest home, defenders first, rushers last) and passes to
     * another bot that's home after HOME_JOB_SECONDS, so it rotates.
     */
    private boolean isHomeWorker() {
        if (team == null || !BedwarsBots.bedAlive(team)) return false;
        Vec3 base = Vec3.atCenterOf(BedwarsBots.base(team));
        List<ServerPlayer> bots = new ArrayList<>();
        for (ServerPlayer p : BedwarsBots.alivePlayers()) {
            if (BedwarsBots.team(p.getUUID()) != team) continue;
            if (!BedwarsBots.isBot(p.getUUID())) {
                if (p.position().distanceTo(base) < 20) return false; // a real teammate is home
                continue;
            }
            bots.add(p);
        }
        if (bots.isEmpty()) return false;
        TeamState s = ts();
        long now = BedwarsBots.matchTicks();
        boolean workerAlive = false;
        for (ServerPlayer p : bots) if (p.getUUID().equals(s.homeWorker)) workerAlive = true;
        if (!workerAlive || now >= s.homeWorkerUntil) {
            ServerPlayer best = null;
            double bestScore = 1e9;
            for (ServerPlayer p : bots) {
                double d = p.position().distanceTo(base);
                boolean current = p.getUUID().equals(s.homeWorker);
                if (workerAlive && current && bots.size() > 1) continue; // rotate: give someone else a turn
                if (workerAlive && d > 35) continue;                     // only hand over to someone who's home
                BwStrategy.Role r = ROLES.getOrDefault(team, Map.of()).getOrDefault(p.getUUID(), BwStrategy.Role.DEFENDER);
                double score = d + (r == BwStrategy.Role.DEFENDER ? -10 : r == BwStrategy.Role.RUSHER ? 15 : 0);
                if (score < bestScore) {
                    bestScore = score;
                    best = p;
                }
            }
            if (best != null) {
                s.homeWorker = best.getUUID();
                s.homeWorkerUntil = now + BwStrategy.HOME_JOB_SECONDS * 20L;
                if (best == bot) log("DECIDE", "my turn to work at home");
            } else {
                s.homeWorkerUntil = now + 100; // nobody home to hand over to yet: the current one keeps it a bit
            }
        }
        return bot.getUUID().equals(s.homeWorker);
    }

    /** Raises the team alarm: an enemy close to our bed, defence blocks disappearing with an enemy near, or the bell/trap/alarm. */
    private void updateAlarm() {
        if (!BedwarsBots.bedAlive(team)) return;
        TeamState s = ts();
        long now = BedwarsBots.matchTicks();
        Vec3 bed = Vec3.atCenterOf(BedwarsBots.bedFoot(team));
        ServerPlayer intruder = null;
        double nearest = 1e9;
        for (ServerPlayer e : BedwarsBots.alivePlayers()) {
            BwTeam et = BedwarsBots.team(e.getUUID());
            if (et == null || et == team) continue;
            double d = e.position().distanceTo(bed);
            if (d < nearest) {
                nearest = d;
                intruder = e;
            }
        }
        boolean raise = nearest < BwStrategy.BED_ALARM_RANGE || BedwarsBots.bedAlertRecently(team);
        if (age % 20 == 0) {
            int missing = missingCount();
            if (s.lastMissing >= 0 && missing > s.lastMissing && nearest < 20) raise = true; // defence broken
            s.lastMissing = missing;
        }
        if (raise) {
            if (now - s.alarmUntil > 200 && now - s.alarmLogged > 200) {
                s.alarmLogged = now;
                log("DECIDE", "BED UNDER ATTACK" + (intruder == null ? "" : " by " + intruder.getName().getString()
                        + String.format(" (%.0f blocks from the bed)", nearest)) + " - everyone home");
            }
            s.alarmUntil = now + 100;
        }
    }

    private boolean alarm() {
        return team != null && BedwarsBots.bedAlive(team) && BedwarsBots.matchTicks() < ts().alarmUntil;
    }

    /** Soft exception to the alarm: committed to an enemy bed, or already at one that's partly broken open. */
    private boolean finishingEnemyBed() {
        if (committedBed()) return true;
        if (target < 0) return false;
        BwTeam tt = BwTeam.values()[target];
        if (!BedwarsBots.bedAlive(tt)) return false;
        if (bot.getEyePosition().distanceTo(Vec3.atCenterOf(BedwarsBots.bedFoot(tt))) > 7) return false;
        return doing.startsWith("breaking") || defenceMissing(tt, 2) > 0;
    }

    /** Back to the bed (by ender pearl if far away). Fighting is handled by threat() once there. */
    private void rushHome() {
        BlockPos bed = BedwarsBots.bedFoot(team);
        double d = bot.position().distanceTo(Vec3.atCenterOf(bed));
        trip = Trip.NONE;
        if (d > 30 && pearlTo(Vec3.atBottomCenterOf(BedwarsBots.base(team)))) {
            doing = "rushing home (ender pearl)";
            return;
        }
        if (d < 6) {
            doing = "defending the bed";
            lookAround();
            List<BlockPos> missing = missingDefence();
            if (!missing.isEmpty() && countBlocks() > 0) buildDefence(missing);
            return;
        }
        doing = "rushing home: the bed is under attack";
        BlockPos g = BedwarsBots.gen(team);
        goTo(bed, Pathfinder.near(bed.getX(), bed.getY(), bed.getZ(), 3.5), travel());
        if (pathStatus() == PathStatus.STUCK) goTo(g, Pathfinder.near(g.getX(), g.getY(), g.getZ(), 1.5), travel());
    }

    /**
     * Throws an ender pearl so it lands at dest (simulated like the game: 1.5 blocks/tick, drag 0.99,
     * gravity 0.03). True while lining up or just thrown.
     */
    private boolean pearlTo(Vec3 dest) {
        if (age < nextPearl || !bot.onGround()) return false;
        int slot = findSlot(s -> s.is(Items.ENDER_PEARL));
        if (slot < 0) return false;
        Vec3 eye = bot.getEyePosition();
        double dx = dest.x - eye.x, dz = dest.z - eye.z, flat = Math.hypot(dx, dz), dy = dest.y + 0.5 - eye.y;
        float bestPitch = 0;
        double bestErr = 1e9;
        for (int p = -70; p <= 20; p += 2) {
            double rad = Math.toRadians(p);
            double vx = 1.5 * Math.cos(rad), vy = -1.5 * Math.sin(rad), x = 0, y = 0;
            for (int t = 0; t < 300; t++) {
                x += vx;
                y += vy;
                vx *= 0.99;
                vy = vy * 0.99 - 0.03;
                if (vy < 0 && y <= dy) break;
            }
            double err = Math.abs(x - flat);
            if (err < bestErr) {
                bestErr = err;
                bestPitch = p;
            }
        }
        if (bestErr > 3) return false; // out of range
        float yaw = yawTo(dx, dz);
        lookDirection(yaw, bestPitch, 0);
        float yawOff = Math.abs(((bot.getYRot() - yaw) % 360 + 540) % 360 - 180);
        if (yawOff < 2.5f && Math.abs(bot.getXRot() - bestPitch) < 2f) {
            useItem(slot);
            nextPearl = age + 100;
            log("DECIDE", String.format("ender pearl home (%.0f blocks)", flat));
        }
        return true;
    }

    /** The home job: defence, then the end stone cover, then the generator and the team chest. A lone bot also loops to the diamonds. */
    private void homeWork(BwStrategy.Plan plan) {
        List<BlockPos> missing = missingDefence();
        if (!missing.isEmpty() && countBlocks() > 0 && !soloLoop) {
            buildDefence(missing);
            return;
        }
        // 5.19: the defence is whole: swap any reachable wool in it for end stone (only with end stone in hand).
        if (missing.isEmpty() && !soloLoop && endstoneCount() > 0) {
            BlockPos wool = woolToReplace();
            if (wool != null) {
                replaceWool(wool);
                return;
            }
        }
        // A lone bot: short loops (home -> diamonds -> home), never away long.
        if (teamBots() == 1 && !humanOnTeam()) {
            if (!soloLoop && age - lastLoopEnd > 30 * 20 && countBlocks() >= 16 && minute() > 1.5) {
                soloLoop = true;
                tripStart = age;
                trip = Trip.NONE;
            }
            if (soloLoop) {
                if (age - tripStart > BwStrategy.SOLO_AWAY_SECONDS * 20L && trip != Trip.HOME) trip = Trip.HOME;
                runTrips(plan);
                if (trip == Trip.NONE && age - tripStart > 40) {
                    soloLoop = false;
                    lastLoopEnd = age;
                }
                return;
            }
        }
        if (!missing.isEmpty()) {
            collectHome("collecting iron for the bed defence");
            return;
        }
        collectHome("working at home: collecting at the generator");
    }

    // =====================================================================
    // Fighting
    // =====================================================================

    /** The enemy worth fighting right now, or null. */
    private ServerPlayer threat() {
        ServerPlayer best = null;
        double bestScore = 1e9;
        BlockPos bed = BedwarsBots.bedFoot(team);
        long now = age;
        boolean alarm = alarm();
        ignore.values().removeIf(t -> t < now);
        for (ServerPlayer e : BedwarsBots.alivePlayers()) {
            BwTeam et = BedwarsBots.team(e.getUUID());
            if (et == null || et == team || ignore.containsKey(e.getUUID())) continue;
            double d = distanceTo(e);
            if (d > d().sight) continue;
            // Invisible people are only noticed up close (like a player would).
            if (e.hasEffect(net.minecraft.world.effect.MobEffects.INVISIBILITY) && d > 4) continue;
            // 5.13: one bot per real player (except at our bed), and leave him alone at a third team's bed.
            if (!mayTarget(e)) continue;
            boolean sees = bot.hasLineOfSight(e);
            boolean hurtMe = bot.getLastHurtByMob() == e && bot.tickCount - bot.getLastHurtByMobTimestamp() < 80;
            // Someone out on a bridge across a gap: running at them only walks us to the edge.
            // (People bridging toward our base are handled by intercept().)
            if (d >= 4 && onBridge(e) && !groundTo(e.position()) && !(hurtMe && d < 6)) continue;
            boolean atOurBed = BedwarsBots.bedAlive(team) && e.position().distanceTo(Vec3.atCenterOf(bed)) < 11;
            boolean atTheirBase = target >= 0 && e.position().distanceTo(Vec3.atCenterOf(BedwarsBots.base(BwTeam.values()[target]))) < 14;
            boolean want = d < 5 || (hurtMe && d < 16) || (sees && d < 8)
                    || (atOurBed && (alarm || role == BwStrategy.Role.DEFENDER || bot.position().distanceTo(Vec3.atCenterOf(bed)) < 25))
                    || (atTheirBase && (role == BwStrategy.Role.RUSHER || role == BwStrategy.Role.HUNTER) && d < 14);
            if (!want) continue;
            double score = d - (atOurBed ? 8 : 0) - (hurtMe ? 4 : 0);
            if (score < bestScore) {
                bestScore = score;
                best = e;
            }
        }
        return best;
    }

    // =====================================================================
    // Stopping people who are bridging to our island
    // =====================================================================

    /** True if the player is standing on (or just above) a block somebody placed. */
    private boolean onBridge(ServerPlayer e) {
        BlockPos feet = e.blockPosition();
        for (int dy = 1; dy <= 2; dy++) {
            BlockPos b = feet.below(dy);
            if (!level().getBlockState(b).isAir()) return BedwarsBots.isPlaced(b);
        }
        return false;
    }

    /** The placed block under someone on a bridge, or null. */
    private BlockPos bridgeBlockUnder(ServerPlayer e) {
        BlockPos feet = e.blockPosition();
        for (int dy = 1; dy <= 2; dy++) {
            BlockPos b = feet.below(dy);
            if (!level().getBlockState(b).isAir()) return BedwarsBots.isPlaced(b) ? b : null;
        }
        return null;
    }

    /**
     * An enemy out on a bridge heading for our island, within INTERCEPT_RANGE of the base, that this
     * bot should deal with, or null. Bridgers further out are only noted (the home bot buys a bow).
     * 5.13: a real bridger is met by one bot of the team (the claim), not all of them.
     */
    private ServerPlayer bridgerToStop() {
        if (!BedwarsBots.alive(bot.getUUID())) return null;
        Vec3 base = Vec3.atCenterOf(BedwarsBots.base(team));
        ServerPlayer best = null;
        double bestD = 1e9;
        for (ServerPlayer e : BedwarsBots.alivePlayers()) {
            BwTeam et = BedwarsBots.team(e.getUUID());
            if (et == null || et == team || ignore.containsKey(e.getUUID())) continue;
            if (e.hasEffect(net.minecraft.world.effect.MobEffects.INVISIBILITY)) continue;
            double toBase = e.position().distanceTo(base);
            if (toBase > BwStrategy.BRIDGER_WATCH_RANGE || !onBridge(e)) continue;
            // Heading our way: closer to our base than to their own.
            if (toBase > e.position().distanceTo(Vec3.atCenterOf(BedwarsBots.base(et)))) continue;
            if (toBase <= 32) ts().lastBridger = BedwarsBots.matchTicks();
            // 5.19: a bot with a bow shoots at a bridger as soon as they're in bow range and in sight,
            // not only once they're close to our island.
            boolean canShoot = findArrowCount() > 0 && findSlot(st -> st.is(Items.BOW)) >= 0
                    && distanceTo(e) <= BwStrategy.BRIDGER_SHOOT_RANGE && bot.hasLineOfSight(e);
            if (toBase > BwStrategy.INTERCEPT_RANGE && !canShoot) continue;
            if (groundTo(e.position()) && distanceTo(e) < 6) continue; // they've landed: normal fighting
            if (!mayTarget(e)) continue;                               // 5.13: a teammate bot already has him
            if (toBase < bestD) {
                bestD = toBase;
                best = e;
            }
        }
        if (best == null) return null;
        // Only bots that are home (or the home worker) go out to meet them.
        return nearHome(24) || isHomeWorker() ? best : null;
    }

    /**
     * Knock a bridger off: break the block under them, hit them, shoot them or fireball them. False if
     * none of that is possible right now (then the bot just carries on with its job - no waiting at the edge).
     */
    private boolean intercept(ServerPlayer e) {
        String who = e.getName().getString();
        double dist = distanceTo(e);
        if (!e.getUUID().equals(intercepting)) {
            intercepting = e.getUUID();
            log("INTERCEPT", String.format("%s is bridging toward our base, %.1f blocks away (arrows %d, fireballs %s)",
                    who, dist, findArrowCount(), findSlot(s -> Stacks.is(s, "bw_fireball")) >= 0 ? "yes" : "no"));
        }
        boolean sees = bot.hasLineOfSight(e);
        // 1) Close enough to reach the block they're standing on: break it and they drop.
        BlockPos under = bridgeBlockUnder(e);
        if (under != null && bot.getEyePosition().distanceTo(Vec3.atCenterOf(under)) < 4.5) {
            doing = "breaking " + who + "'s bridge";
            stopShooting();
            if (mine(under)) log("INTERCEPT", "broke the block under " + who);
            return true;
        }
        // 2) Close and reachable on foot: just hit them (knockback off the bridge).
        if (dist < 3.5) {
            doing = "knocking " + who + " off the bridge";
            setTarget(e);
            lookAt(e.getEyePosition(), 1.0);
            if (reacted()) tryAttack(e);
            return true;
        }
        // 3) Bow.
        // 5.19: long shots spread more (none up to 12 blocks, about 1.2 blocks at 32).
        shotSpread = Math.max(0, (dist - 12) * BwStrategy.BRIDGER_SPREAD_PER_BLOCK);
        boolean shooting = sees && dist <= BwStrategy.BRIDGER_SHOOT_RANGE && findArrowCount() > 0 && shoot(e, st -> st.is(Items.BOW), 20, false, true);
        shotSpread = 0;
        if (shooting) {
            doing = "shooting " + who + " off the bridge";
            return true;
        }
        // 4) Fireball.
        if (sees && dist > 6 && dist < 22 && age >= nextFireball && reacted()) {
            int slot = findSlot(st -> Stacks.is(st, "bw_fireball"));
            if (slot >= 0) {
                doing = "fireballing " + who + " off the bridge";
                Vec3 aim = e.position().add(e.getDeltaMovement().scale(dist * 0.4));
                lookAt(aim, 1.0);
                if (aimOff(aim) < 6) {
                    useItem(slot);
                    nextFireball = age + 60;
                }
                return true;
            }
        }
        // 5) Walk out to the bridge's root if it's on our side and close; otherwise carry on working.
        stopShooting();
        if (under != null && dist < 10) {
            doing = "going to break " + who + "'s bridge";
            BlockPos edge = under;
            if (goTo(edge, Pathfinder.near(edge.getX(), edge.getY() + 1, edge.getZ(), 3.5), homeOptions()) == PathStatus.STUCK) {
                ignore.put(e.getUUID(), age + 100);
                return false;
            }
            return true;
        }
        return false;
    }

    private void fight(ServerPlayer enemy) {
        double dist = distanceTo(enemy);
        if (!enemy.getUUID().equals(fightTarget)) {
            fightTarget = enemy.getUUID();
            fightFails = 0;
            log("FIGHT", String.format("%s (%s) at %.1f blocks, hp %.0f vs %.0f", enemy.getName().getString(),
                    BedwarsBots.team(enemy.getUUID()) == null ? "?" : BedwarsBots.team(enemy.getUUID()).display, dist, bot.getHealth(), enemy.getHealth()));
        }
        boolean sees = bot.hasLineOfSight(enemy);
        // A golden apple right before a close fight, if hurt.
        if (dist < 6 && bot.getHealth() < 16 && bot.getAbsorptionAmount() <= 0 && heal()) return;
        // Bow from range.
        if (dist > 9 && sees && findArrowCount() > 0 && shoot(enemy, s -> s.is(Items.BOW), 20, false, true)) return;
        // Fireball at someone standing out in the open.
        if (dist > 6 && dist < 20 && sees && age >= nextFireball && reacted()) {
            int slot = findSlot(s -> Stacks.is(s, "bw_fireball"));
            if (slot >= 0) {
                Vec3 aim = enemy.position().add(enemy.getDeltaMovement().scale(dist * 0.4));
                lookAt(aim, 1.0);
                if (aimOff(aim) < 6) {
                    useItem(slot);
                    nextFireball = age + 60;
                }
                return;
            }
        }
        Pathfinder.Options chase = new Pathfinder.Options().bridge(Math.min(countBlocks(), 6)).dig(true).nodes(2500);
        int before = failedPathCount();
        melee(enemy, chase);
        if (failedPathCount() > before) fightFails++;
        if (fightFails >= 2) {
            fightFails = 0;
            ignore.put(enemy.getUUID(), age + 200); // can't get to them: back to the job for 10 s
            log("GIVE-UP", "can't reach " + enemy.getName().getString() + " (no path twice), ignoring for 10s | " + pathDebug());
        }
    }

    private boolean heal() {
        int slot = findSlot(s -> s.is(Items.GOLDEN_APPLE));
        if (slot < 0) return false;
        doing = "eating a golden apple";
        useItem(slot);
        eatingUntil = age + 40;
        return true;
    }

    // =====================================================================
    // Shopping
    // =====================================================================

    private BwStrategy.Me me(BwStrategy.Role r) {
        int[] res = BedwarsBots.resources(bot);
        int[] g = BedwarsBots.gear(bot.getUUID());
        Inventory inv = bot.getInventory();
        int strong = 0, fireballs = 0, arrows = 0, gapples = 0, pearls = 0, tnt = 0, clouds = 0, endstone = 0;
        boolean bow = false;
        for (int i = 0; i < 36; i++) {
            ItemStack s = inv.getItem(i);
            if (s.isEmpty()) continue;
            if (isStrong(s)) strong += s.getCount();
            if (s.is(Items.END_STONE)) endstone += s.getCount();
            if (Stacks.is(s, "bw_fireball")) fireballs += s.getCount();
            if (Stacks.is(s, "bw_cloud")) clouds += s.getCount();
            if (Stacks.is(s, "bw_tnt") || s.is(Items.TNT)) tnt += s.getCount();
            if (s.is(Items.ENDER_PEARL)) pearls += s.getCount();
            if (s.is(Items.ARROW)) arrows += s.getCount();
            if (s.is(Items.GOLDEN_APPLE)) gapples += s.getCount();
            if (s.is(Items.BOW)) bow = true;
        }
        boolean home = team != null && isHomeWorker();
        boolean builder = r == BwStrategy.Role.DEFENDER || home;
        boolean bridgers = team != null && BedwarsBots.matchTicks() - ts().lastBridger < 3 * 60 * 20;
        boolean wantCloud = clouds == 0 && age - knockedOffAt < 5 * 60 * 20;
        boolean attacker = r == BwStrategy.Role.RUSHER || r == BwStrategy.Role.HUNTER;
        return new BwStrategy.Me(r, res[0], res[1], res[2], res[3], g[0], g[1], g[2], g[3], g[4] == 1,
                countBlocks(), strong, fireballs, bow, arrows, gapples, minute(), builder ? missingDefence().size() + (woolToReplace() != null ? 8 : 0) : 0,
                BedwarsBots.bedBell(team), home, coverTime(), bridgers, attacker ? targetDefence() : 0, pearls, tnt, wantCloud, endstone);
    }

    /** At home with something to buy: walk to the shopkeeper and buy it. True while busy with that. */
    private boolean shop(BwStrategy.Plan plan) {
        BwStrategy.Me me = me(role);
        String item = BwStrategy.nextBuy(plan, me);
        if (item != null) {
            doing = "buying " + item;
            BlockPos shop = BedwarsBots.shop(team);
            if (bot.position().distanceTo(Vec3.atBottomCenterOf(shop)) > 3.2) {
                goTo(shop, Pathfinder.near(shop.getX(), shop.getY(), shop.getZ(), 2.2), homeOptions());
            } else {
                lookAt(Vec3.atCenterOf(shop).add(0, 0.6, 0), 0.3);
                if (age >= nextBuy) {
                    com.aidan.skyserver.bedwars.BwBotLog.think(name(), roleName(), BwStrategy.explainBuy(plan, me), item);
                    var shopApi = BedwarsBots.shop(bot);
                    if (!shopApi.buy(item)) log("SHOP-FAIL", item + ": " + shopApi.lastResult());
                    nextBuy = age + 5 + rng.nextInt(6);
                }
            }
            return true;
        }
        int[] levels = BedwarsBots.upgradeLevels(team);
        int traps = BedwarsBots.traps(team);
        String up = BwStrategy.nextUpgrade(plan, levels, traps, minute());
        int cost = BwStrategy.upgradeCost(up, levels, traps);
        if (up != null && me.diamonds() >= cost) {
            doing = "buying " + up.toLowerCase();
            BlockPos upg = BedwarsBots.upgrades(team);
            if (bot.position().distanceTo(Vec3.atBottomCenterOf(upg)) > 3.2) {
                goTo(upg, Pathfinder.near(upg.getX(), upg.getY(), upg.getZ(), 2.2), homeOptions());
            } else {
                lookAt(Vec3.atCenterOf(upg).add(0, 0.6, 0), 0.3);
                if (age >= nextBuy) {
                    var shopApi = BedwarsBots.shop(bot);
                    log("THINK", "team upgrade next: " + up + " for " + cost + " diamonds (have " + me.diamonds() + ")");
                    if (!shopApi.upgrade(up)) log("SHOP-FAIL", up + ": " + shopApi.lastResult());
                    else if (up.equals("FORGE") && levels[3] == 0) log("DECIDE", "first Forge bought: emerald trips start now");
                    nextBuy = age + 10 + rng.nextInt(8);
                }
            }
            return true;
        }
        return false;
    }

    // =====================================================================
    // The team chest
    // =====================================================================

    /**
     * At home: take from the team chest whatever the next purchase or team upgrade is short of, or
     * put spare resources in it (anything over what this role keeps on hand). True while busy.
     * With a real player on the team, bots only count half of what's in the chest as theirs to use.
     */
    private boolean useChest(BwStrategy.Plan plan) {
        if (age < chestCooldown) return false;
        int[] mine = BedwarsBots.resources(bot);
        int[] box = BedwarsBots.chestResources(team);
        if (humanOnTeam()) for (int k = 0; k < 4; k++) box[k] /= 2;
        int[] take = new int[4], put = new int[4];
        String what = null;
        BwStrategy.Me me = me(role);
        if (BwStrategy.nextBuy(plan, me) == null) {
            BwStrategy.Me rich = me.plus(box);
            String item = BwStrategy.nextBuy(plan, rich);
            int[] p = item == null ? null : BwStrategy.price(item, rich);
            if (p != null && p[1] > mine[p[0]]) {
                take[p[0]] = p[1] - mine[p[0]];
                what = item;
            }
        }
        int[] levels = BedwarsBots.upgradeLevels(team);
        int traps = BedwarsBots.traps(team);
        String up = BwStrategy.nextUpgrade(plan, levels, traps, minute());
        int cost = BwStrategy.upgradeCost(up, levels, traps);
        if (what == null && up != null && cost != Integer.MAX_VALUE && mine[2] < cost && mine[2] + box[2] >= cost) {
            take[2] = cost - mine[2];
            what = up.toLowerCase();
        }
        boolean taking = what != null;
        if (!taking) {
            if (age < noBankUntil) return false;
            int[] keep = BwStrategy.keepOnHand(role);
            for (int k = 0; k < 4; k++) put[k] = Math.max(0, mine[k] - keep[k]);
            if (!BwStrategy.worthBanking(put)) return false;
        }
        BlockPos chest = BedwarsBots.teamChest(team);
        doing = taking ? "taking resources from the team chest for " + what : "putting spare resources in the team chest";
        if (bot.getEyePosition().distanceTo(Vec3.atCenterOf(chest)) > 3.6) {
            if (goTo(chest, Pathfinder.near(chest.getX(), chest.getY(), chest.getZ(), 2.2), homeOptions()) == PathStatus.STUCK) {
                chestCooldown = age + 600;
                log("GIVE-UP", "can't reach the team chest | " + pathDebug());
            }
            return true;
        }
        clearPath();
        lookAt(Vec3.atCenterOf(chest), 0.4);
        if (aimOff(Vec3.atCenterOf(chest)) > 30) return true; // turn to it first
        swingArm();
        String[] names = {"iron", "gold", "diamonds", "emeralds"};
        StringBuilder sb = new StringBuilder();
        int moved = 0;
        for (int k = 0; k < 4; k++) {
            int n = taking ? BedwarsBots.withdraw(bot, team, k, take[k]) : BedwarsBots.deposit(bot, team, k, put[k]);
            if (n > 0) sb.append(sb.length() > 0 ? ", " : "").append(n).append(" ").append(names[k]);
            moved += n;
        }
        int[] now = BedwarsBots.chestResources(team);
        if (moved > 0) {
            log("CHEST", (taking ? "took " + sb + " for " + what : "put " + sb + " in")
                    + String.format(" | chest now i%d g%d d%d e%d", now[0], now[1], now[2], now[3]));
            chestCooldown = age + 30;
        } else {
            chestCooldown = age + 600; // chest full / empty after all: leave it for 30 s
        }
        if (taking) noBankUntil = age + 200; // go and spend it before banking again
        return true;
    }

    // =====================================================================
    // Roles
    // =====================================================================

    /** Stand on the home generator and let iron and gold pile up. */
    private void collectHome(String why) {
        doing = why;
        BlockPos g = BedwarsBots.gen(team);
        PathStatus s = goTo(g, Pathfinder.near(g.getX(), g.getY(), g.getZ(), 0.6), homeOptions());
        if (s == PathStatus.ARRIVED) lookAround();
    }

    /** Defender (when someone else has the home job): finish the defence, then run trips like a collector. */
    private void defend(BwStrategy.Plan plan) {
        List<BlockPos> missing = missingDefence();
        if (!missing.isEmpty() && countBlocks() > 0) {
            buildDefence(missing);
            return;
        }
        if (!missing.isEmpty() && minute() < 3) {
            collectHome("collecting iron for bed defence");
            return;
        }
        collect(plan);
    }

    /** Collector: short trips to the diamonds (and emeralds once the first Forge is in), straight home each time. */
    private void collect(BwStrategy.Plan plan) {
        BwStrategy.Me me = me(role);
        if (trip == Trip.NONE && (me.blocks() < 24 || (me.sword() < 1 && minute() >= 2))) {
            collectHome("collecting iron to gear up");
            return;
        }
        if (trip == Trip.NONE && age < homeStopUntil) {
            collectHome("collecting at home between runs");
            return;
        }
        runTrips(plan);
    }

    /** Picks and runs the next trip (emerald when it's due, otherwise diamonds). */
    private void runTrips(BwStrategy.Plan plan) {
        if (trip == Trip.NONE) {
            TeamState s = ts();
            long now = BedwarsBots.matchTicks();
            boolean forge = BedwarsBots.upgradeLevels(team)[3] >= 1;
            long interval = (long) (BwLearning.emeraldIntervalMinutes() * 1200);
            if (forge && !BedwarsBots.emeraldGens().isEmpty() && (s.lastEmerald < 0 || now - s.lastEmerald >= interval)) {
                boolean first = s.lastEmerald < 0;
                s.lastEmerald = now;
                startTrip(Trip.EMERALD);
                log("DECIDE", (first ? "first emerald run (right after the first Forge)" : "emerald run") + String.format(", next in %.1f min", BwLearning.emeraldIntervalMinutes()));
            } else if (!BedwarsBots.diamondGens().isEmpty()) {
                startTrip(Trip.DIAMOND);
            } else {
                collectHome("collecting at home");
                return;
            }
        }
        switch (trip) {
            case DIAMOND -> tripTo(nearest(BedwarsBots.diamondGens(), BedwarsBots.base(team)), 2, "collecting diamonds");
            case EMERALD -> tripTo(nearest(BedwarsBots.emeraldGens(), BedwarsBots.base(team)), 3, "collecting emeralds");
            case HOME -> {
                int dia = BedwarsBots.resources(bot)[2];
                doing = "heading home" + (dia > 0 ? " with " + dia + " diamonds" : "");
                BlockPos g = BedwarsBots.gen(team);
                goTo(g, Pathfinder.near(g.getX(), g.getY(), g.getZ(), 1.5), travel());
                if (nearHome(9)) {
                    trip = Trip.NONE;
                    atGenSince = -1;
                    homeStopUntil = age + 100; // a few seconds at home: bank, buy, grab the generator
                }
            }
            default -> {}
        }
    }

    private void startTrip(Trip t) {
        trip = t;
        tripStart = age;
        atGenSince = -1;
        gainedAt = -1;
    }

    /** Go to a generator, grab what's there and head home: at most GEN_WAIT_SECONDS there, never waiting for more. */
    private void tripTo(BlockPos g, int currency, String why) {
        doing = why;
        if (countBlocks() < 4 && !nearPos(g, 6)) {
            trip = Trip.HOME;
            doing = "heading home: out of blocks";
            return;
        }
        PathStatus s = goTo(g, Pathfinder.near(g.getX(), g.getY(), g.getZ(), 0.7), travel());
        if (s == PathStatus.STUCK && !nearPos(g, 4)) {
            log("GIVE-UP", "can't reach the " + (currency == 3 ? "emerald" : "diamond") + " generator | " + pathDebug());
            trip = Trip.HOME;
            return;
        }
        if (age - tripStart > 60 * 20) { // a minute and still not there: home
            trip = Trip.HOME;
            return;
        }
        if (s != PathStatus.ARRIVED) {
            atGenSince = -1;
            return;
        }
        int have = BedwarsBots.resources(bot)[currency];
        if (atGenSince < 0) {
            atGenSince = age;
            resWhenArrived = have;
        }
        if (have > resWhenArrived && gainedAt < 0) gainedAt = age;
        lookAround();
        boolean grabbed = gainedAt >= 0 && age - gainedAt >= 15;           // picked up what was there
        boolean waited = age - atGenSince >= BwStrategy.GEN_WAIT_SECONDS * 20L; // nothing there: don't wait for it
        if (grabbed || waited) {
            log("DO", String.format("leaving the %s generator after %ds with %d", currency == 3 ? "emerald" : "diamond",
                    (age - atGenSince) / 20, have - resWhenArrived));
            trip = Trip.HOME;
        }
    }

    private void rush(BwStrategy.Plan plan) {
        BwStrategy.Me me = me(role);
        if (age < goingHomeUntil || !BwStrategy.readyToAttack(plan, me)) {
            if (!nearHome(14) && age < goingHomeUntil) {
                doing = "heading home for blocks";
                BlockPos g = BedwarsBots.gen(team);
                goTo(g, Pathfinder.near(g.getX(), g.getY(), g.getZ(), 1.0), travel());
                return;
            }
            // Gearing up: grab diamonds while waiting instead of standing at home (always doing something).
            if (me.blocks() >= 24 && me.sword() >= 1 && minute() > 1 && (trip != Trip.NONE || age >= homeStopUntil)) {
                runTrips(plan);
                return;
            }
            collectHome("gearing up to attack");
            return;
        }
        trip = Trip.NONE;
        attack(true);
    }

    private void hunt(BwStrategy.Plan plan) {
        if (BedwarsBots.suddenDeath()) {
            BlockPos mid = nearest(BedwarsBots.emeraldGens(), bot.blockPosition());
            doing = "sudden death: heading to the middle";
            goTo(mid, Pathfinder.near(mid.getX(), mid.getY(), mid.getZ(), 2.5), travel());
            if (nearPos(mid, 3)) lookAround();
            return;
        }
        BwStrategy.Me me = me(role);
        if (me.blocks() < 12 && nearHome(16)) {
            collectHome("collecting before the hunt");
            return;
        }
        attack(false);
    }

    /** Go after the target team: break their bed if it stands, otherwise hunt them down. */
    private void attack(boolean bedFirst) {
        int me = team.ordinal();
        if (target < 0 || BedwarsBots.out(BwTeam.values()[target]) || (age % 200 == 0 && !BedwarsBots.bedAlive(BwTeam.values()[target]))) {
            double[] dist = new double[4], def = new double[4];
            boolean[] bed = new boolean[4], out = new boolean[4];
            for (BwTeam t : BwTeam.values()) {
                int i = t.ordinal();
                dist[i] = Math.sqrt(BedwarsBots.base(t).distSqr(BedwarsBots.base(team)));
                bed[i] = BedwarsBots.bedAlive(t);
                out[i] = BedwarsBots.out(t);
                for (ServerPlayer p : BedwarsBots.alivePlayers())
                    if (BedwarsBots.team(p.getUUID()) == t && p.position().distanceTo(Vec3.atCenterOf(BedwarsBots.base(t))) < 14) def[i]++;
            }
            for (int i = 0; i < 4; i++) if (ignoreTeamUntil.getOrDefault(i, 0L) > age) dist[i] += 500;
            int before = target;
            target = BwStrategy.pickTarget(dist, bed, out, def, me, target);
            if (target != before && target >= 0) log("DECIDE", "attack target: " + BwTeam.values()[target].display
                    + (bed[target] ? " (bed standing, " + (int) def[target] + " defenders seen, defence " + defenceText(targetDefence()) + ")" : " (no bed, hunting)"));
        }
        if (target < 0) {
            collectHome("no one left to attack");
            return;
        }
        BwTeam tt = BwTeam.values()[target];
        if (countBlocks() < 5 && !nearPos(BedwarsBots.bedFoot(tt), 14)) {
            goingHomeUntil = age + 20 * 30;
            return;
        }
        if (BedwarsBots.bedAlive(tt) && !BedwarsBots.blanketed(tt)) {
            breakBed(tt);
            return;
        }
        // No bed: hunt their players (nearest), or wait in their base. 5.13: a real player another bot of ours has is left to it.
        ServerPlayer prey = null;
        double best = 1e9;
        for (ServerPlayer p : BedwarsBots.alivePlayers()) {
            if (BedwarsBots.team(p.getUUID()) != tt) continue;
            if (!mayTarget(p)) continue;
            double d = distanceTo(p);
            if (d < best) { best = d; prey = p; }
        }
        if (prey != null && !ignore.containsKey(prey.getUUID())) {
            doing = "hunting " + prey.getName().getString();
            claim(prey);
            if (best < 12) {
                fight(prey);
                return;
            }
            BlockPos at = prey.blockPosition();
            if (goTo(at, Pathfinder.near(at.getX(), at.getY(), at.getZ(), 2), travel()) == PathStatus.STUCK) ignore.put(prey.getUUID(), age + 300);
            return;
        }
        BlockPos base = BedwarsBots.base(tt);
        doing = "waiting in " + tt.display + "'s base";
        goTo(base, Pathfinder.near(base.getX(), base.getY(), base.getZ(), 3), travel());
    }

    private void breakBed(BwTeam tt) {
        BlockPos foot = BedwarsBots.bedFoot(tt), head = BedwarsBots.bedHead(tt);
        Vec3 eye = bot.getEyePosition();
        BlockPos nearer = eye.distanceTo(Vec3.atCenterOf(foot)) < eye.distanceTo(Vec3.atCenterOf(head)) ? foot : head;
        double d = eye.distanceTo(Vec3.atCenterOf(nearer));
        // 5.13: close to the bed: commit to breaking it.
        if (d < COMMIT_RANGE && commitBed != tt.ordinal()) {
            commitBed = tt.ordinal();
            log("DECIDE", "at " + tt.display + "'s bed - breaking it no matter what");
        }
        // Wool or wood around it and a fireball in hand: blow a hole from a few blocks away.
        if (d > 4 && d < 14 && age >= nextFireball && (targetDefence() & (BwStrategy.DEF_WOOL | BwStrategy.DEF_WOOD)) != 0) {
            int slot = findSlot(s -> Stacks.is(s, "bw_fireball"));
            BlockPos cover = coverBetween(nearer);
            if (slot >= 0 && cover != null && BedwarsBots.isPlaced(cover)) {
                doing = "fireballing " + tt.display + "'s bed defence";
                Vec3 aim = Vec3.atCenterOf(cover);
                lookAt(aim, 0.3);
                if (aimOff(aim) < 4) {
                    useItem(slot);
                    nextFireball = age + 40;
                    log("DECIDE", "fireball at " + tt.display + "'s bed defence");
                }
                return;
            }
        }
        if (d < 4.4) {
            doing = "breaking " + tt.display + "'s bed";
            // End stone in the way and TNT on us: blow it open.
            BlockPos cover = coverBetween(nearer);
            if (cover != null && level().getBlockState(cover).is(Blocks.END_STONE) && placeTnt(cover)) return;
            // Break whatever covers it on the way (the game only lets us break placed blocks and the bed).
            mine(cover != null ? cover : nearer);
            return;
        }
        doing = "attacking " + tt.display + "'s bed";
        Pathfinder.Options o = travel().dig(true);
        o.digCost = 2.0;
        PathStatus s = goTo(nearer, Pathfinder.near(nearer.getX(), nearer.getY(), nearer.getZ(), 1.7), o);
        if (s == PathStatus.STUCK && d < 9) {
            // Walled off close by: dig straight at it.
            BlockPos cover = coverBetween(nearer);
            if (cover != null) mine(cover);
            else moveTo(Vec3.atCenterOf(nearer), 0.6);
        }
    }

    private long nextTnt = 0;

    /** Places TNT next to a block of end stone in the defence (then backs off). */
    private boolean placeTnt(BlockPos stone) {
        if (age < nextTnt) return false;
        int slot = findSlot(s -> Stacks.is(s, "bw_tnt"));
        if (slot < 0) return false;
        for (Direction dir : Direction.values()) {
            BlockPos spot = stone.relative(dir);
            if (!level().getBlockState(spot).isAir() || !BedwarsBots.canPlace(spot)) continue;
            if (bot.getEyePosition().distanceTo(Vec3.atCenterOf(spot)) > 4.3) continue;
            // Place it against the stone.
            select(slot);
            Direction face = dir;
            Vec3 hit = Vec3.atCenterOf(stone).add(face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);
            lookAt(hit, 0.2);
            bot.gameMode.useItemOn(bot, level(), bot.getMainHandItem(), InteractionHand.MAIN_HAND, new BlockHitResult(hit, face, stone, false));
            swingArm();
            nextTnt = age + 100;
            goingHomeUntil = 0;
            log("DECIDE", "TNT on the end stone");
            return true;
        }
        return false;
    }

    /** The first placed block between our eyes and a spot, or null if the line is clear. */
    private BlockPos coverBetween(BlockPos to) {
        Vec3 from = bot.getEyePosition(), dest = Vec3.atCenterOf(to);
        Vec3 step = dest.subtract(from);
        double len = step.length();
        step = step.scale(0.3 / Math.max(len, 0.01));
        Vec3 at = from;
        for (double t = 0; t < len - 0.5; t += 0.3) {
            at = at.add(step);
            BlockPos b = BlockPos.containing(at);
            if (b.equals(to)) return null;
            if (!level().getBlockState(b).isAir() && BedwarsBots.isPlaced(b)) return b;
        }
        return null;
    }

    // =====================================================================
    // Bed defence and the end stone cover
    // =====================================================================

    /**
     * Time for the end stone cover. 5.19: at about minute 5 (it used to wait for Forge II and the Healing
     * Spring, around minute 8-13). From then on the bed's wool is also swapped for end stone.
     */
    private boolean coverTime() {
        if (team == null) return false;
        return minute() >= BwStrategy.COVER_MINUTE;
    }

    /** 5.19: a wool block in our bed defence that an enemy could reach (it has an open side), or null. */
    private BlockPos woolToReplace() {
        if (!coverTime() || !BedwarsBots.bedAlive(team)) return null;
        BlockPos foot = BedwarsBots.bedFoot(team), head = BedwarsBots.bedHead(team);
        for (BlockPos c : defenceCells()) {
            if (skipCell.containsKey(c.asLong()) || !BedwarsBots.isPlaced(c)) continue;
            BlockState s = level().getBlockState(c);
            if (!net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(s.getBlock()).getPath().endsWith("_wool")) continue;
            boolean open = false;
            for (Direction d : Direction.values()) {
                BlockPos n = c.relative(d);
                if (n.equals(foot) || n.equals(head)) continue;
                BlockState ns = level().getBlockState(n);
                if (ns.isAir() || ns.canBeReplaced()) {
                    open = true;
                    break;
                }
            }
            if (open) return c;
        }
        return null;
    }

    /** 5.19: walks to a wool block in the defence and breaks it (buildDefence then puts end stone there). */
    private void replaceWool(BlockPos cell) {
        BlockPos stand = standSpotFor(cell);
        if (stand == null) {
            skipCell.put(cell.asLong(), age + 600);
            return;
        }
        doing = "swapping the bed's wool for end stone";
        if (bot.getEyePosition().distanceTo(Vec3.atCenterOf(cell)) > 4.3 || bot.blockPosition().distManhattan(stand) > 1) {
            if (goTo(stand, Pathfinder.near(stand.getX(), stand.getY(), stand.getZ(), 0.5), homeOptions()) == PathStatus.STUCK)
                skipCell.put(cell.asLong(), age + 400);
            return;
        }
        if (mine(cell) && level().getBlockState(cell).isAir()) log("DEFENCE", "broke wool at " + cell.toShortString() + " to put end stone there");
    }

    private int endstoneCount() {
        int n = 0;
        Inventory inv = bot.getInventory();
        for (int i = 0; i < 36; i++) if (inv.getItem(i).is(Items.END_STONE)) n += inv.getItem(i).getCount();
        return n;
    }

    private int layers() {
        return coverTime() ? 3 : 2;
    }

    /** The cells of the defence layers around a team's bed, in building order. */
    private List<BlockPos> defenceCells(BwTeam t, int layers) {
        BlockPos foot = BedwarsBots.bedFoot(t), head = BedwarsBots.bedHead(t);
        int y = foot.getY();
        List<BlockPos> out = new ArrayList<>();
        for (int layer = 1; layer <= layers; layer++) {
            for (int dy = 0; dy <= layer; dy++) {
                for (int x = Math.min(foot.getX(), head.getX()) - layer; x <= Math.max(foot.getX(), head.getX()) + layer; x++)
                    for (int z = Math.min(foot.getZ(), head.getZ()) - layer; z <= Math.max(foot.getZ(), head.getZ()) + layer; z++) {
                        int ring = Math.max(dist1(x, foot.getX(), head.getX()), dist1(z, foot.getZ(), head.getZ()));
                        boolean wall = ring == layer && dy < layer;
                        boolean roof = dy == layer && ring <= layer;
                        if (!wall && !roof) continue;
                        if (dy == layer && layer >= 2 && ring == layer) continue; // the outer roof edge isn't needed
                        out.add(new BlockPos(x, y + dy, z));
                    }
            }
        }
        return out;
    }

    private List<BlockPos> defenceCells() {
        return defenceCells(team, layers());
    }

    private static int dist1(int v, int a, int b) {
        int lo = Math.min(a, b), hi = Math.max(a, b);
        return v < lo ? lo - v : v > hi ? v - hi : 0;
    }

    private int ringOf(BlockPos c) {
        BlockPos foot = BedwarsBots.bedFoot(team), head = BedwarsBots.bedHead(team);
        return Math.max(dist1(c.getX(), foot.getX(), head.getX()), dist1(c.getZ(), foot.getZ(), head.getZ()));
    }

    private List<BlockPos> missingDefence() {
        List<BlockPos> list = new ArrayList<>();
        if (!BedwarsBots.bedAlive(team)) return list;
        long now = age;
        skipCell.values().removeIf(t -> t < now);
        for (BlockPos c : defenceCells()) {
            BlockState s = level().getBlockState(c);
            if (!(s.isAir() || s.canBeReplaced())) continue;
            if (!BedwarsBots.canPlace(c) || skipCell.containsKey(c.asLong())) continue;
            list.add(c);
        }
        return list;
    }

    /** Empty cells in the first two layers of our defence (for spotting blocks being broken). */
    private int missingCount() {
        return defenceMissing(team, 2);
    }

    /** Empty cells in the first `layers` layers of a team's defence. */
    private int defenceMissing(BwTeam t, int layers) {
        int n = 0;
        for (BlockPos c : defenceCells(t, layers)) {
            BlockState s = level().getBlockState(c);
            if ((s.isAir() || s.canBeReplaced()) && BedwarsBots.canPlace(c)) n++;
        }
        return n;
    }

    /** What the target team's bed is wrapped in (BwStrategy.DEF_* bits), looked at every 5 seconds. */
    private int targetDefence() {
        if (target < 0) return 0;
        if (enemyDefenceFor == target && age - enemyDefenceAt < 100) return enemyDefence;
        int mask = 0;
        for (BlockPos c : defenceCells(BwTeam.values()[target], 3)) {
            BlockState s = level().getBlockState(c);
            if (s.isAir()) continue;
            String id = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(s.getBlock()).getPath();
            if (id.endsWith("_wool")) mask |= BwStrategy.DEF_WOOL;
            else if (id.endsWith("_planks") || id.endsWith("_log")) mask |= BwStrategy.DEF_WOOD;
            else if (id.equals("end_stone") || id.endsWith("terracotta") || id.endsWith("clay")) mask |= BwStrategy.DEF_STONE;
            else if (id.equals("obsidian")) mask |= BwStrategy.DEF_OBSIDIAN;
        }
        enemyDefence = mask;
        enemyDefenceFor = target;
        enemyDefenceAt = age;
        return mask;
    }

    private static String defenceText(int mask) {
        if (mask == 0) return "none seen";
        List<String> l = new ArrayList<>();
        if ((mask & BwStrategy.DEF_WOOL) != 0) l.add("wool");
        if ((mask & BwStrategy.DEF_WOOD) != 0) l.add("wood");
        if ((mask & BwStrategy.DEF_STONE) != 0) l.add("end stone/clay");
        if ((mask & BwStrategy.DEF_OBSIDIAN) != 0) l.add("obsidian");
        return String.join("+", l);
    }

    private void buildDefence(List<BlockPos> missing) {
        BlockPos foot = BedwarsBots.bedFoot(team);
        BlockPos next = null;
        for (BlockPos c : missing) {
            if (hasSupport(c)) {
                next = c;
                break;
            }
        }
        if (next == null) {
            collectHome("bed defence: waiting");
            return;
        }
        int ring = ringOf(next);
        int mode = ring >= 3 ? 2 : (ring <= 1 && next.getY() <= foot.getY() + 1) ? 0 : 1; // 0 wool, 1 strong, 2 end stone cover
        if (coverTime()) mode = 2; // 5.19: from cover time on, end stone everywhere (wool is being swapped out)
        BlockPos stand = standSpotFor(next);
        if (stand == null) {
            skipCell.put(next.asLong(), age + 600);
            return;
        }
        doing = (mode == 2 ? "building the end stone cover (" : "building bed defence (") + missing.size() + " left)";
        Vec3 eye = bot.getEyePosition();
        if (eye.distanceTo(Vec3.atCenterOf(next)) > 4.3 || bot.blockPosition().distManhattan(stand) > 1) {
            if (goTo(stand, Pathfinder.near(stand.getX(), stand.getY(), stand.getZ(), 0.5), homeOptions()) == PathStatus.STUCK)
                skipCell.put(next.asLong(), age + 400);
            return;
        }
        lookAt(Vec3.atCenterOf(next), 0.2);
        if (placeReady() && aimOff(Vec3.atCenterOf(next)) < 30) {
            placeCooldown();
            if (!placeWith(next, mode)) skipCell.put(next.asLong(), age + 200);
        }
    }

    private boolean hasSupport(BlockPos c) {
        for (Direction d : Direction.values()) {
            BlockPos s = c.relative(d);
            BlockState st = level().getBlockState(s);
            if (!st.isAir() && !st.canBeReplaced() && st.isCollisionShapeFullBlock(level(), s)) return true;
        }
        return false;
    }

    /** A spot outside the defence (one ring beyond the outer layer) within reach of the cell. */
    private BlockPos standSpotFor(BlockPos cell) {
        BlockPos foot = BedwarsBots.bedFoot(team), head = BedwarsBots.bedHead(team);
        LevelWorld w = world();
        BlockPos best = null;
        double bestD = 1e9;
        int y = foot.getY(), minRing = layers() + 1, r = minRing + 2;
        for (int x = Math.min(foot.getX(), head.getX()) - r; x <= Math.max(foot.getX(), head.getX()) + r; x++)
            for (int z = Math.min(foot.getZ(), head.getZ()) - r; z <= Math.max(foot.getZ(), head.getZ()) + r; z++) {
                int ring = Math.max(dist1(x, foot.getX(), head.getX()), dist1(z, foot.getZ(), head.getZ()));
                if (ring < minRing) continue;
                if (!Pathfinder.canStand(w, x, y, z)) continue;
                double d = Math.sqrt(new BlockPos(x, y, z).distSqr(cell));
                if (d > 4.0) continue;
                double score = d + 0.3 * Math.sqrt(bot.blockPosition().distSqr(new BlockPos(x, y, z)));
                if (score < bestD) {
                    bestD = score;
                    best = new BlockPos(x, y, z);
                }
            }
        return best;
    }

    private static boolean isStrong(ItemStack s) {
        return s.is(Items.END_STONE) || s.is(Items.OAK_PLANKS) || s.is(Items.OBSIDIAN)
                || net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(s.getItem()).getPath().endsWith("_terracotta");
    }

    private static boolean isWool(ItemStack s) {
        return net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(s.getItem()).getPath().endsWith("_wool");
    }

    /** Places a block at target: mode 0 wool first, 1 tough blocks first, 2 end stone first (the cover). */
    private boolean placeWith(BlockPos target, int mode) {
        int slot = mode == 2 ? findSlot(s -> s.is(Items.END_STONE)) : -1;
        if (slot < 0) slot = findSlot(mode >= 1 ? BedwarsBrain::isStrong : BedwarsBrain::isWool);
        if (slot < 0) slot = findSlot(BotBrain::isBuildingBlock);
        if (slot < 0) return false;
        for (Direction dir : Direction.values()) {
            BlockPos support = target.relative(dir);
            BlockState s = level().getBlockState(support);
            if (s.isAir() || s.canBeReplaced() || !s.isCollisionShapeFullBlock(level(), support)) continue;
            select(slot);
            Direction face = dir.getOpposite();
            Vec3 hit = Vec3.atCenterOf(support).add(face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);
            bot.gameMode.useItemOn(bot, level(), bot.getMainHandItem(), InteractionHand.MAIN_HAND, new BlockHitResult(hit, face, support, false));
            swingArm();
            return !level().getBlockState(target).isAir();
        }
        return false;
    }

    /** Bridging and pillaring: wool first, so tougher blocks are kept for the bed. */
    @Override
    protected boolean placeAgainst(BlockPos support, Direction face) {
        int slot = findSlot(BedwarsBrain::isWool);
        if (slot < 0) return super.placeAgainst(support, face);
        select(slot);
        Vec3 hit = Vec3.atCenterOf(support).add(face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);
        BlockPos target = support.relative(face);
        bot.gameMode.useItemOn(bot, level(), bot.getMainHandItem(), InteractionHand.MAIN_HAND, new BlockHitResult(hit, face, support, false));
        swingArm();
        return !level().getBlockState(target).isAir();
    }

    // =====================================================================
    // Helpers
    // =====================================================================

    private Pathfinder.Options travel() {
        Pathfinder.Options o = new Pathfinder.Options().bridge(Math.min(countBlocks(), 64)).nodes(6000);
        o.bridgeCost = 1.6;
        return o;
    }

    private Pathfinder.Options homeOptions() {
        return new Pathfinder.Options().bridge(Math.min(countBlocks(), 4)).nodes(2500);
    }

    private boolean nearHome(double r) {
        return bot.position().distanceTo(Vec3.atCenterOf(BedwarsBots.base(team))) < r;
    }

    private boolean nearPos(BlockPos p, double r) {
        return bot.position().distanceTo(Vec3.atBottomCenterOf(p)) < r;
    }

    private static BlockPos nearest(List<BlockPos> list, BlockPos from) {
        BlockPos best = null;
        double bd = 1e18;
        for (BlockPos p : list) {
            double d = p.distSqr(from);
            if (d < bd) {
                bd = d;
                best = p;
            }
        }
        return best == null ? from : best;
    }

    private void lookAround() {
        if (age >= guardLook) {
            guardLook = age + 30 + rng.nextInt(40);
            lookDirection(bot.getYRot() + (rng.nextFloat() * 140 - 70), rng.nextFloat() * 20 - 10, 0);
        }
    }
}
