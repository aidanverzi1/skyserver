package com.aidan.skyserver.bots;

import com.aidan.skyserver.goldwars.MapDesigns;

import java.util.*;

/**
 * One bot's decisions in Gold Wars (plain Java, shared by the server and the simulator).
 * Each tick it reads the match snapshot and its job from the team {@link GwStrategy}, and
 * returns one {@link GwAction}: walk somewhere, fight someone, mine a block, or place one.
 *
 *  - ATTACK: follow the route to the target vault and mine it; only fight what's in the way.
 *  - INTERCEPT: fight enemies on our half; if none, push the enemy vault on our side.
 *    Longbows and Mages play it the way real players did (Nov 5 recording):
 *      Longbow - sits up high on our half (the sky path) and takes the shots that matter: attackers
 *      close to our gold first, then defenders at the enemy gold while our attackers are in that
 *      gold room, then anyone else coming across our half.
 *      Mage - mostly defence from range in front of our vault, and goes forward to fireball the
 *      defenders only while our attackers are at the enemy gold and our own vaults are quiet.
 *  - DEFEND: fight intruders near the vault, Builders repair the wall, otherwise guard.
 */
public final class GwPlanner {
    private static final double GOLD_REACH = 4.5;
    /** Longbow shots are instant beams with no range limit: this is just how far a bot looks for a shot. */
    private static final double SNIPE_RANGE = 70;
    private static final double MAGE_RANGE = 24;

    private final UUID id;
    private final Random rng;
    private GwStrategy.Order order;
    private int waypoint = 0;
    private int[] post;
    private long postUntil = 0;
    private UUID lastTarget;
    private String doing = "";
    /** Spots we couldn't find a way to recently (packed pos -> until tick). */
    private final Map<Long, Long> unreachable = new HashMap<>();
    private long now = 0;
    /** Interceptor mode: pushing the enemy (true) or guarding our half (false). */
    private boolean pushing = false;
    private long quietSince = 0;
    /** Attackers wait for teammates at the middle of their route (Medium/Hard). */
    private long regroupSince = -1;
    private GwStrategy mates;
    private int forcedVault = -1;
    private GwStrategy.Order forced;
    /** Longbow: on (or heading for) a perch and shooting from there - the body shouldn't chase. */
    private boolean perched = false;
    private int[] perch;
    private long perchUntil = 0;

    /** Simulator only: always follow this order (used to fake a player with habits). */
    public void force(GwStrategy.Order o) {
        forced = o;
    }
    private final Map<UUID, Long> badTargets = new HashMap<>();
    // Progress watchdog: an attacker that hasn't moved, mined or fought for a while backs out and retries.
    private double checkX, checkZ;
    private long movedTick = 0, busyTick = 0, escapeUntil = 0;
    private int escapes = 0;

    public GwPlanner(UUID id, Random rng) {
        this.id = id;
        this.rng = rng;
    }

    public GwStrategy.Order order() {
        return order;
    }

    public String doing() {
        return doing;
    }

    /** True while a Longbow is sniping from its perch (it shoots from where it is instead of chasing). */
    public boolean perched() {
        return perched;
    }

    /** Call when the bot (re)spawns. */
    public void respawned() {
        movedTick = busyTick = now;
        escapeUntil = 0;
        forcedVault = -1;
        waypoint = 0;
        post = null;
        perch = null;
        lastTarget = null;
        regroupSince = -1;
    }

    /** The body calls this when it can't find any way to a GOTO target, so we pick something else. */
    public void unreachable(int x, int y, int z) {
        unreachable.put(GwState.pack(x, y, z), now + 400);
        post = null;
        perch = null;
        if (order != null && order.job() == GwStrategy.Job.ATTACK && waypoint < 2) waypoint++;
    }

    /** The body calls this when it can't find a way to the enemy it's fighting. */
    public void unreachableTarget(UUID target) {
        badTargets.put(target, now + 160);
    }

    private boolean reachable(GwState.Unit e) {
        Long until = badTargets.get(e.id);
        return until == null || until <= now;
    }

    private boolean isUnreachable(int x, int y, int z) {
        Long until = unreachable.get(GwState.pack(x, y, z));
        return until != null && until > now;
    }

    public GwAction decide(GwState s, GwStrategy strategy, int blocks) {
        now = s.tick;
        perched = false;
        GwState.Unit me = s.unit(id);
        if (me == null || !me.alive) return GwAction.idle("dead");
        mates = strategy;
        GwStrategy.Order o = forced != null ? forced : strategy.orderFor(s, id);
        if (!o.equals(order)) {
            order = o;
            waypoint = 0;
            post = null;
            perch = null;
            forcedVault = -1;
        }
        GwAction a;
        if (s.buildPhase) a = buildPhase(s, me, blocks);
        else a = switch (o.job()) {
            case ATTACK -> attack(s, me, strategy, blocks);
            case INTERCEPT -> intercept(s, me, strategy, blocks);
            case DEFEND -> defend(s, me, o.vault(), blocks);
        };
        // Watchdog bookkeeping.
        if ((me.x - checkX) * (me.x - checkX) + (me.z - checkZ) * (me.z - checkZ) > 2.25) {
            checkX = me.x;
            checkZ = me.z;
            movedTick = s.tick;
        }
        if (a.kind() == GwAction.Kind.MINE || a.kind() == GwAction.Kind.PLACE || a.kind() == GwAction.Kind.FIGHT) busyTick = s.tick;
        doing = a.doing();
        if (a.kind() == GwAction.Kind.FIGHT) lastTarget = a.target();
        return a;
    }

    // =====================================================================
    // Build phase
    // =====================================================================

    private GwAction buildPhase(GwState s, GwState.Unit me, int blocks) {
        boolean builds = me.cls.equals("builder") || (order.job() == GwStrategy.Job.DEFEND && me.diff != Difficulty.EASY);
        if (builds && blocks > 0) {
            int vault = order.job() == GwStrategy.Job.ATTACK ? 0 : order.vault();
            GwAction w = wall(s, me, vault);
            if (w == null) w = wall(s, me, 1 - vault);
            if (w != null) return w;
        }
        int[] spot = order.job() == GwStrategy.Job.ATTACK ? routeStart(s, me) : guardSpot(s, me.side, order.vault());
        return hold(s, me, spot, 400, "waiting for the wall to drop", blocks);
    }

    /** Next wall block around one of our vaults (place it, or walk into reach). Null if done. */
    private GwAction wall(GwState s, GwState.Unit me, int vault) {
        List<long[]> gold = s.vaultGold(me.side, vault);
        if (gold.isEmpty()) return null;
        Set<Long> goldSet = new HashSet<>();
        for (long[] g : gold) goldSet.add(GwState.pack((int) g[0], (int) g[1], (int) g[2]));
        int layers = me.diff == Difficulty.HARD ? 2 : 1;
        Set<Long> ring = new HashSet<>(goldSet);
        int[][] dirs = {{1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1}, {0, 1, 0}};
        for (int layer = 1; layer <= layers; layer++) {
            int[] best = null;
            double bestScore = Double.MAX_VALUE;
            Set<Long> next = new HashSet<>();
            for (long p : ring) {
                for (int[] d : dirs) {
                    int x = GwState.px(p) + d[0], y = GwState.py(p) + d[1], z = GwState.pz(p) + d[2];
                    long k = GwState.pack(x, y, z);
                    if (goldSet.contains(k)) continue;
                    next.add(k);
                    if (!s.world.placeable(x, y, z)) continue;
                    if (x == me.bx() && z == me.bz() && (y == me.by() || y == me.by() + 1)) continue; // not inside myself
                    if (isUnreachable(x, y, z)) continue;
                    double score = y * 10 + eyeDist(me, x, y, z);
                    if (score < bestScore) {
                        bestScore = score;
                        best = new int[]{x, y, z};
                    }
                }
            }
            if (best != null) {
                if (eyeDist(me, best[0], best[1], best[2]) > 4.3) {
                    return GwAction.go(best[0], best[1], best[2], Pathfinder.near(best[0], best[1] - 1, best[2], 3.3),
                            walkOptions(0), "building the vault wall");
                }
                return GwAction.place(best[0], best[1], best[2], "building the vault wall");
            }
            ring = next;
        }
        return null;
    }

    // =====================================================================
    // Jobs
    // =====================================================================

    private GwAction attack(GwState s, GwState.Unit me, GwStrategy st, int blocks) {
        GwState.Unit enemy = nearestEnemy(s, me, sight(me));
        int vault = order.vault();
        // Push WITH a real teammate who's attacking (what made the best bots good): go where they're going.
        GwState.Unit leader = humanLeader(s, me);
        if (leader != null) vault = nearestEnemyVault(s, me, leader);
        // Stuck (not moving, mining or fighting for 5 s, not at a post on purpose): back out to the
        // middle of the route for a few seconds, then come in again (another way if it keeps happening).
        if (!s.buildPhase && now - Math.max(movedTick, busyTick) > 100 && now > escapeUntil && !doing.startsWith("waiting")) {
            escapeUntil = now + 80;
            escapes++;
            waypoint = 0;
            movedTick = now;
            if (escapes % 2 == 0 && s.vaultCount(-me.side, 1 - vault) > 0) forcedVault = 1 - vault;
        }
        if (forcedVault >= 0 && s.vaultCount(-me.side, forcedVault) > 0) vault = forcedVault;
        if (now < escapeUntil) {
            if (enemy != null && me.dist(enemy) < 6) return GwAction.fight(enemy.id, "fighting while backing out");
            int[] back = lanePoint(s, me.side, order.lane(), -10);
            return GwAction.go(back[0], back[1], back[2], flatGoal(back[0], back[1], back[2], 4), attackOptions(blocks), "backing out to try again");
        }
        // Attack pairs: the miner (Scout/Builder) goes for the gold, its escort keeps it alive.
        String role = mates == null ? "" : mates.role(me.id);
        UUID buddyId = mates == null ? null : mates.buddy(me.id);
        GwState.Unit buddy = buddyId == null ? null : s.unit(buddyId);
        if (buddy != null && !buddy.alive) buddy = null;
        int[] tv = s.vault(-me.side, vault);
        boolean inRoom = s.vaultCount(-me.side, vault) > 0 && me.dist(tv[0], tv[1], tv[2]) < 12;
        if (role.equals("miner")) {
            GwState.Unit hitter = me.lastAttacker == null ? null : s.unit(me.lastAttacker);
            boolean beingHit = hitter != null && hitter.alive && s.tick - me.lastHurtTick < 40 && me.dist(hitter) < 4;
            // Scouts are fast: outside the gold room they run on unless they're getting low.
            boolean canEscape = me.cls.equals("scout") && !inRoom && me.health > 8;
            // At the gold with a teammate already on the defender: keep mining (the real scout kept
            // breaking gold down to 2 hearts while his knight fought the defenders off him).
            boolean covered = beingHit && inRoom && me.health > 4 && teammateNear(s, me, new int[]{(int) hitter.x, (int) hitter.y, (int) hitter.z}, 4);
            if (beingHit && !canEscape && !covered) return GwAction.fight(hitter.id, inRoom ? "fighting off a defender at the gold" : "cornered - fighting back");
            if (buddy != null) {
                GwState.Unit threat = nearestTo(s, me, buddy, 5);
                if (threat != null && me.dist(threat) < 10) return GwAction.fight(threat.id, "helping my escort fight");
                if (!inRoom && buddy.dist(tv[0], tv[1], tv[2]) > me.dist(tv[0], tv[1], tv[2]) + 8 && me.dist(buddy) > 6) {
                    int bx = (int) Math.floor(buddy.x), by = (int) Math.floor(buddy.y), bz = (int) Math.floor(buddy.z);
                    return GwAction.go(bx, by, bz, Pathfinder.near(bx, by, bz, 4), walkOptions(blocks), "waiting for my escort");
                }
            }
            GwAction mine = attackGold(s, me, vault, order.lane(), blocks);
            if (mine != null) return mine.withDoing((inRoom ? "at the gold: " : "heading for the gold: ") + mine.doing());
            return defend(s, me, fullestVault(s, me.side), blocks);
        }
        // Escort: its bot miner, or a real teammate Scout/Builder on the attack (the real knight went in
        // with the scouts/builders and kept the defenders off them).
        GwState.Unit ward = role.equals("escort") ? buddy : null;
        if (ward == null && !GwStrategy.isMinerClass(me.cls) && !role.equals("miner")) ward = humanMiner(s, me);
        if (ward != null) {
            int wv = nearestEnemyVault(s, me, ward);
            int[] wt = s.vault(-me.side, wv);
            boolean wardInRoom = s.vaultCount(-me.side, wv) > 0 && ward.dist(wt[0], wt[1], wt[2]) < 12;
            GwState.Unit threat = nearestTo(s, me, ward, 10);
            if (threat != null && (me.dist(threat) < 8 || sees(s, me, threat))) return GwAction.fight(threat.id, "keeping defenders off my miner");
            if (wardInRoom) {
                // Nobody on them: stand between the gold and the enemy spawn, where the defenders come back from.
                int gx = wt[0] - me.side * 4, gy = wt[1] - 1, gz = (int) Math.floor(ward.z);
                if (!isUnreachable(gx, gy, gz) && me.dist(gx + 0.5, gy, gz + 0.5) > 2.5)
                    return GwAction.go(gx, gy, gz, Pathfinder.near(gx, gy, gz, 2), attackOptions(blocks), "guarding my miner from the spawn side");
                if (me.dist(ward) < 8) return GwAction.go(gx, gy, gz, Pathfinder.near(gx, gy, gz, 2.5), attackOptions(blocks), "guarding my miner at the gold");
            }
            if (me.dist(ward) > 6) {
                int bx = (int) Math.floor(ward.x), by = (int) Math.floor(ward.y), bz = (int) Math.floor(ward.z);
                return GwAction.go(bx, by, bz, Pathfinder.near(bx, by, bz, 3), attackOptions(blocks), "escorting my miner");
            }
        }
        GwAction a;
        if (leader != null && me.dist(leader) > 7 && leader.dist(s.vault(-me.side, vault)[0], s.vault(-me.side, vault)[1], s.vault(-me.side, vault)[2])
                < me.dist(s.vault(-me.side, vault)[0], s.vault(-me.side, vault)[1], s.vault(-me.side, vault)[2]) - 3) {
            a = GwAction.go((int) Math.floor(leader.x), (int) Math.floor(leader.y), (int) Math.floor(leader.z),
                    Pathfinder.near((int) Math.floor(leader.x), (int) Math.floor(leader.y), (int) Math.floor(leader.z), 4), attackOptions(blocks),
                    "pushing with a teammate");
        } else {
            a = attackGold(s, me, vault, order.lane(), blocks);
        }
        // Waiting for teammates (or walking up with one): anyone in plain view nearby gets fought, no staring contests.
        if (enemy != null && a != null && a.kind() == GwAction.Kind.GOTO && me.dist(enemy) < 14 && sees(s, me, enemy))
            return GwAction.fight(enemy.id, "fighting an enemy in view");
        if (enemy != null && blocking(s, me, enemy)) {
            // At the gold with a teammate already fighting this enemy: keep mining (fast miners only, healthy).
            boolean covered = false;
            for (GwState.Unit u : s.units) {
                if (u.side == me.side && u.alive && !u.id.equals(me.id) && u.dist(enemy) < me.dist(enemy) && u.dist(enemy) < 4) covered = true;
            }
            boolean miner = me.cls.equals("builder") || me.cls.equals("scout");
            if (!(covered && miner && me.health > 8 && a != null && a.kind() == GwAction.Kind.MINE)) {
                return GwAction.fight(enemy.id, "fighting an enemy in the way");
            }
            return a.withDoing("mining while a teammate covers");
        }
        return a != null ? a : defend(s, me, fullestVault(s, me.side), blocks);
    }

    private GwAction intercept(GwState s, GwState.Unit me, GwStrategy st, int blocks) {
        if (me.cls.equals("longbow")) {
            GwAction a = longbowWatch(s, me, blocks);
            if (a != null) return a;
        } else if (me.cls.equals("mage")) {
            return mageWatch(s, me, st, blocks);
        }
        // Guard or push? Push once nobody has come at our vaults for a few seconds; go back to
        // guarding when a vault is threatened. (Hysteresis, so they don't turn around every second.)
        boolean threatened = st.threat(0) > 0 && s.vaultCount(me.side, 0) > 0 || st.threat(1) > 0 && s.vaultCount(me.side, 1) > 0;
        GwState.Unit close = nearestEnemy(s, me, 10);
        if (threatened || (close != null && close.x * me.side > -4)) {
            pushing = false;
            quietSince = s.tick;
        } else if (!pushing && s.tick - quietSince > 100) {
            pushing = true;
        }
        if (!pushing) {
            GwState.Unit target = null;
            double best = 28;
            for (GwState.Unit e : enemies(s, me)) {
                if (s.inSpawnZone(e.x, e.z)) continue;
                double d = me.dist(e), w = weighted(me, e);
                boolean onOurHalf = e.x * me.side > -4;
                if (((onOurHalf && w < best && (d < 10 || sees(s, me, e))) || d < 6) && (target == null || w < best)) {
                    best = w;
                    target = e;
                }
            }
            if (target != null) return GwAction.fight(target.id, "picking off an attacker");
            for (int v = 0; v < 2; v++) {
                if (st.threat(v) > 0 && s.vaultCount(me.side, v) > 0) return defend(s, me, v, blocks);
            }
            return hold(s, me, interceptPost(s, me), 300, "watching for attackers", blocks);
        }
        // Pushing: same as an attacker, on the enemy vault on our side of the map.
        GwState.Unit enemy = nearestEnemy(s, me, sight(me));
        if (enemy != null && blocking(s, me, enemy)) return GwAction.fight(enemy.id, "fighting an enemy in the way");
        GwAction a = attackGold(s, me, order.vault(), order.vault() == 0 ? -1 : 1, blocks);
        if (a != null) return a.withDoing("pushing (nobody coming): " + a.doing());
        return hold(s, me, interceptPost(s, me), 300, "holding the middle", blocks);
    }

    /**
     * Longbow interceptor, learned from a real player who got most of his team's kills: he spent
     * about half the match up on the sky path over our half (36 blocks away on average when he
     * shot), and picked his shots - attackers close to our gold, or defenders at the enemy gold
     * while his teammates were in that gold room. Null = no perch to use (fall back to the normal
     * interceptor).
     */
    private GwAction longbowWatch(GwState s, GwState.Unit me, int blocks) {
        GwState.Unit close = nearestEnemy(s, me, 6);
        if (close != null) return GwAction.fight(close.id, "fighting off someone who reached me");
        GwState.Unit best = null;
        double bestScore = 1e9;
        String why = null;
        for (GwState.Unit e : enemies(s, me)) {
            if (s.inSpawnZone(e.x, e.z) || !sees(s, me, e)) continue;
            double d = me.dist(e);
            if (d > SNIPE_RANGE) continue;
            double score;
            String w;
            if (nearGold(s, me.side, e, 12)) {
                score = d - 100;
                w = "sniping an attacker at our gold";
            } else if (defendingAgainstOurAttackers(s, me, e)) {
                score = d - 60;
                w = "sniping a defender off our attackers";
            } else if (e.x * me.side > -4 && d < 45) {
                score = d;
                w = "sniping an attacker on our half";
            } else {
                continue;
            }
            if (!e.isBot()) score -= 10; // real players first
            if (score < bestScore) {
                bestScore = score;
                best = e;
                why = w;
            }
        }
        if (best != null) {
            perched = true;
            return GwAction.fight(best.id, why);
        }
        // No shot worth taking: get up on the perch (the sky path over our half, on our vault's side).
        if (perch == null || now >= perchUntil) {
            int lane = order.vault() == 0 ? -3 : 3;
            int[] p = lanePoint(s, me.side, lane, -(10 + rng.nextInt(16)));
            perch = isUnreachable(p[0], p[1], p[2]) ? null : p;
            perchUntil = now + 400 + rng.nextInt(400);
        }
        if (perch == null) return null;
        perched = true;
        return GwAction.go(perch[0], perch[1], perch[2], laneGoal(3, perch), walkOptions(blocks), "watching from a perch");
    }

    /** An enemy close to their own gold while one of our team is in that gold room. */
    private static boolean defendingAgainstOurAttackers(GwState s, GwState.Unit me, GwState.Unit e) {
        for (int v = 0; v < 2; v++) {
            if (s.vaultCount(-me.side, v) == 0) continue;
            int[] vt = s.vault(-me.side, v);
            if (e.dist(vt[0], vt[1], vt[2]) >= 12) continue;
            if (teammateNear(s, me, vt, 12)) return true;
        }
        return false;
    }

    private static boolean teammateNear(GwState s, GwState.Unit me, int[] spot, double r) {
        for (GwState.Unit u : s.units) {
            if (u.side == me.side && u.alive && !u.id.equals(me.id) && u.dist(spot[0], spot[1], spot[2]) < r) return true;
        }
        return false;
    }

    /**
     * Mage interceptor, learned from a real player: about three quarters of the time on our half,
     * fireballing anyone coming at our gold from range; forward only while our attackers are at the
     * enemy gold (and nobody is at ours), to blast the defenders off them.
     */
    private GwAction mageWatch(GwState s, GwState.Unit me, GwStrategy st, int blocks) {
        GwState.Unit close = nearestEnemy(s, me, 5);
        if (close != null) return GwAction.fight(close.id, "fighting off someone who reached me");
        boolean homeQuiet = st.threat(0) == 0 && st.threat(1) == 0;
        // Our attackers are at their gold: go and help.
        if (homeQuiet) {
            for (int v = 0; v < 2; v++) {
                if (s.vaultCount(-me.side, v) == 0) continue;
                int[] vt = s.vault(-me.side, v);
                if (!teammateNear(s, me, vt, 10)) continue;
                GwState.Unit def = null;
                double bd = 1e9;
                for (GwState.Unit e : enemies(s, me)) {
                    if (e.dist(vt[0], vt[1], vt[2]) >= 12 || !sees(s, me, e)) continue;
                    double d = me.dist(e);
                    if (d < MAGE_RANGE && d < bd) {
                        bd = d;
                        def = e;
                    }
                }
                if (def != null) return GwAction.fight(def.id, "fireballing defenders off our attackers");
                int x = vt[0] + me.side * 12, y = vt[1] - 1, z = vt[2];
                if (isUnreachable(x, y, z)) continue;
                return GwAction.go(x, y, z, flatGoal(x, y, z, 4), attackOptions(blocks), "joining the push on their gold");
            }
        }
        // Defence: anyone near our gold, or crossing our half in range.
        GwState.Unit target = null;
        double best = 1e9;
        for (GwState.Unit e : enemies(s, me)) {
            if (s.inSpawnZone(e.x, e.z)) continue;
            double d = me.dist(e);
            boolean atGold = nearGold(s, me.side, e, 16) && d < 30;
            boolean coming = e.x * me.side > -4 && d < MAGE_RANGE && sees(s, me, e);
            if (!atGold && !coming) continue;
            double w = weighted(me, e) - (atGold ? 15 : 0);
            if (w < best) {
                best = w;
                target = e;
            }
        }
        if (target != null) return GwAction.fight(target.id, "fireballing an attacker");
        // Nobody about: stand in front of the vault (the one under more pressure), a little toward the middle.
        int v = order.vault();
        if (st.threat(1 - v) > st.threat(v) && s.vaultCount(me.side, 1 - v) > 0) v = 1 - v;
        if (s.vaultCount(me.side, v) == 0 && s.vaultCount(me.side, 1 - v) > 0) v = 1 - v;
        int[] vv = s.vault(me.side, v);
        int[] spot = {vv[0] - me.side * (6 + rng.nextInt(5)), vv[1] - 1, vv[2] + rng.nextInt(7) - 3};
        return hold(s, me, spot, 200, "guarding the vault from range", blocks);
    }

    private GwAction defend(GwState s, GwState.Unit me, int vault, int blocks) {
        // Our own vault empty? Defend the other one.
        if (s.vaultCount(me.side, vault) == 0 && s.vaultCount(me.side, 1 - vault) > 0) vault = 1 - vault;
        GwState.Unit intruder = intruderAt(s, me, vault, 16);
        if (intruder == null) intruder = nearestEnemy(s, me, 7);
        if (intruder == null) {
            // Anyone we can see coming toward this vault on our half of the map.
            int[] v = s.vault(me.side, vault);
            // Anyone in plain view who looks like they're coming for the gold: on our half, or already
            // close to this vault. (Not people far away on their own side.)
            for (GwState.Unit e : enemies(s, me)) {
                if (s.inSpawnZone(e.x, e.z) || !sees(s, me, e)) continue;
                double toVault = e.dist(v[0], v[1], v[2]), d = me.dist(e);
                boolean coming = e.x * me.side > -4 || toVault < 30;
                if (coming && d < sight(me) && toVault < 34 && (intruder == null || weighted(me, e) < weighted(me, intruder))) intruder = e;
            }
        }
        if (intruder != null) return GwAction.fight(intruder.id, "defending the vault");
        // Nobody here, but the other vault is being attacked: go and help.
        GwState.Unit other = intruderAt(s, me, 1 - vault, 16);
        if (other != null) {
            if (me.dist(other) < 22) return GwAction.fight(other.id, "helping defend the other vault");
            int[] o = s.vault(me.side, 1 - vault);
            return GwAction.go(o[0] - me.side * 3, o[1] - 1, o[2], Pathfinder.near(o[0] - me.side * 3, o[1] - 1, o[2], 3),
                    walkOptions(blocks), "running to help the other vault");
        }
        if (me.cls.equals("builder") && blocks > 0) {
            GwAction w = wall(s, me, vault);
            if (w != null) return w.withDoing("repairing the vault wall");
        }
        // Patrol around the gold, looking for anyone coming.
        return hold(s, me, patrolSpot(s, me.side, vault), 50, "patrolling the vault", blocks);
    }

    /** A spot in a loose ring around the vault (a new one every few seconds while patrolling). */
    private int[] patrolSpot(GwState s, int side, int vault) {
        int[] v = s.vault(side, vault);
        for (int tries = 0; tries < 12; tries++) {
            double a = rng.nextDouble() * Math.PI * 2, r = 3 + rng.nextDouble() * 4;
            int x = v[0] + (int) Math.round(Math.cos(a) * r), z = v[2] + (int) Math.round(Math.sin(a) * r);
            int y = v[1] - 1;
            if (isUnreachable(x, y, z)) continue;
            if (s.gold(side).contains(GwState.pack(x, y, z)) || s.gold(side).contains(GwState.pack(x, y + 1, z))) continue;
            return new int[]{x, y, z};
        }
        return guardSpot(s, side, vault);
    }

    /**
     * A real (human) teammate who's pushing into the enemy half within 50 blocks - bots go with them.
     * Not used if they're standing in their own spawn or are far behind.
     */
    private GwState.Unit humanLeader(GwState s, GwState.Unit me) {
        GwState.Unit best = null;
        double bestD = 50;
        for (GwState.Unit u : s.units) {
            if (u.side != me.side || u.isBot() || !u.alive || s.inSpawnZone(u.x, u.z)) continue;
            if (u.x * me.side > -2) continue;                 // still on our half
            double d = me.dist(u);
            if (d < bestD) {
                bestD = d;
                best = u;
            }
        }
        return best;
    }

    /** A real teammate Scout/Builder pushing into the enemy half within 30 blocks (bot fighters escort them). */
    private GwState.Unit humanMiner(GwState s, GwState.Unit me) {
        GwState.Unit best = null;
        double bestD = 30;
        for (GwState.Unit u : s.units) {
            if (u.side != me.side || u.isBot() || !u.alive || !GwStrategy.isMinerClass(u.cls) || s.inSpawnZone(u.x, u.z)) continue;
            if (u.x * me.side > -2) continue; // still on our half
            double d = me.dist(u);
            if (d < bestD) {
                bestD = d;
                best = u;
            }
        }
        return best;
    }

    /** Real players count as closer than bots when picking who to fight (they're the priority). */
    private static double weighted(GwState.Unit me, GwState.Unit e) {
        return me.dist(e) * (e.isBot() ? 1.35 : 1.0);
    }

    /** The enemy nearest to a unit u (within r of it), real players first. */
    private GwState.Unit nearestTo(GwState s, GwState.Unit me, GwState.Unit u, double r) {
        GwState.Unit best = null;
        double bestD = 1e9;
        for (GwState.Unit e : enemies(s, me)) {
            if (s.inSpawnZone(e.x, e.z)) continue;
            double d = u.dist(e);
            if (d > r) continue;
            double w = d * (e.isBot() ? 1.35 : 1.0);
            if (w < bestD) {
                bestD = w;
                best = e;
            }
        }
        return best;
    }

    /** The enemy vault (with gold left) closest to a unit. */
    private int nearestEnemyVault(GwState s, GwState.Unit me, GwState.Unit u) {
        if (s.vaultCount(-me.side, 0) == 0) return 1;
        if (s.vaultCount(-me.side, 1) == 0) return 0;
        int[] a = s.vault(-me.side, 0), b = s.vault(-me.side, 1);
        return u.dist(a[0], a[1], a[2]) <= u.dist(b[0], b[1], b[2]) ? 0 : 1;
    }

    /** Worth stopping for on the way to the gold: right in front, hitting us, or a ranged target already in progress. */
    private boolean blocking(GwState s, GwState.Unit me, GwState.Unit enemy) {
        double d = me.dist(enemy);
        boolean hitByThem = enemy.id.equals(me.lastAttacker) && s.tick - me.lastHurtTick < 50;
        if (d < 3.5) return true;
        if (hitByThem && d < 8) return true;
        boolean seen = sees(s, me, enemy);
        // Close and in plain view: deal with them instead of running past (even if they're standing still).
        if (d < 7 && seen) return true;
        // Guarding the gold we're going for: clear them out before mining.
        if (order != null && s.vaultCount(-me.side, order.vault()) > 0) {
            int[] v = s.vault(-me.side, order.vault());
            if (enemy.dist(v[0], v[1], v[2]) < 11 && d < 16 && seen) return true;
            // In the gold room: clear it out first, then mine (even if they're round a pillar).
            if (me.dist(v[0], v[1], v[2]) < 12 && enemy.dist(v[0], v[1], v[2]) < 12) return true;
        }
        boolean ranged = me.cls.equals("archer") || me.cls.equals("longbow");
        return ranged && d < 20 && enemy.id.equals(lastTarget) && sees(s, me, enemy);
    }

    /** Head for an enemy vault by a route and mine it. Null if the enemy has no gold left. */
    private GwAction attackGold(GwState s, GwState.Unit me, int vault, int lane, int blocks) {
        if (s.gold(-me.side).isEmpty()) return null;
        if (s.vaultCount(-me.side, vault) == 0) vault = 1 - vault;
        List<long[]> gold = s.vaultGold(-me.side, vault);
        double ex = me.x, ey = me.y + 1.62, ez = me.z;

        // Gold in reach that we can see: mine it. Something in the way: dig through it.
        long[] visible = null;
        int[] blocker = null;
        double best = GOLD_REACH;
        for (long[] g : gold) {
            double gx = g[0] + 0.5, gy = g[1] + 0.5, gz = g[2] + 0.5;
            double d = Math.sqrt((gx - ex) * (gx - ex) + (gy - ey) * (gy - ey) + (gz - ez) * (gz - ez));
            if (d > best) continue;
            int[] hit = s.firstBlock(ex, ey, ez, gx, gy, gz);
            if (hit == null) {
                visible = g;
                best = d;
            } else if (blocker == null && s.world.breakable(hit[0], hit[1], hit[2])) {
                blocker = hit;
            }
        }
        if (visible != null) return GwAction.mine((int) visible[0], (int) visible[1], (int) visible[2], "mining gold");
        if (blocker != null) return GwAction.mine(blocker[0], blocker[1], blocker[2], "digging to the gold");

        int[] target = s.vault(-me.side, vault);
        int[] wp = waypoint(s, me, lane, target);
        if (wp != null) {
            Pathfinder.Goal g = laneGoal(lane, wp);
            if (g.reached(me.bx(), me.by(), me.bz())) {
                if (waypoint == 0 && order.job() == GwStrategy.Job.ATTACK && Math.abs(lane) != 3 && shouldWait(s, me, vault)) {
                    return GwAction.go(wp[0], wp[1], wp[2], flatGoal(wp[0], wp[1], wp[2], 4), attackOptions(blocks),
                            "waiting for teammates to push together");
                }
                regroupSince = -1;
                waypoint++;
                wp = waypoint(s, me, lane, target);
            }
            if (wp != null) return GwAction.go(wp[0], wp[1], wp[2], laneGoal(lane, wp), attackOptions(blocks),
                    "taking the " + LaneMemory.laneName(lane));
        }
        final List<long[]> goldF = gold;
        Pathfinder.Goal nextToGold = new Pathfinder.Goal() {
            public boolean reached(int x, int y, int z) {
                for (long[] g : goldF) {
                    long dx = Math.abs(g[0] - x), dz = Math.abs(g[2] - z), dy = g[1] - y;
                    if (dx + dz == 1 && (dy == 0 || dy == 1)) return true;
                    if (dx == 0 && dz == 0 && dy == -1) return true;
                }
                return false;
            }

            public double estimate(int x, int y, int z) {
                double dx = x - target[0], dy = y - target[1], dz = z - target[2];
                return Math.max(0, Math.sqrt(dx * dx + dy * dy + dz * dz) - 2);
            }
        };
        return GwAction.go(target[0], target[1], target[2], nextToGold, attackOptions(blocks), "closing in on the vault");
    }

    /**
     * Attackers wait in the middle for teammates heading to the same vault, so they push together
     * instead of running in one at a time. Normally only Medium/Hard wait for one partner (up to
     * 12 s). Against the enemy's last vault everyone waits for a group of up to 3 (up to 30 s,
     * Easy 15 s), because a lone attacker never gets through the final defense.
     */
    private boolean shouldWait(GwState s, GwState.Unit me, int vault) {
        if (s.overtime || mates == null) return false;
        if (s.gold(-me.side).size() <= 3) return false; // all-out attack: no waiting around
        boolean lastVault = s.vaultCount(-me.side, 0) == 0 || s.vaultCount(-me.side, 1) == 0;
        int maxWait = lastVault ? (me.diff == Difficulty.EASY ? 300 : 600) : (me.diff == Difficulty.EASY ? 160 : 240);
        if (regroupSince < 0) regroupSince = s.tick;
        if (s.tick - regroupSince > maxWait) return false;
        int here = 1, coming = 0;
        for (GwState.Unit u : s.units) {
            if (u.side != me.side || u.id.equals(me.id) || !u.alive) continue;
            GwStrategy.Order o = u.isBot() ? mates.peek(u.id) : null;
            boolean pushing = o != null && (o.job() == GwStrategy.Job.ATTACK || o.job() == GwStrategy.Job.INTERCEPT) && o.vault() == vault;
            if (u.isBot() && !pushing) continue;
            double d = me.dist(u);
            if (d < 12) here++;
            else if (u.isBot() && o.job() == GwStrategy.Job.ATTACK && u.x * me.side > me.x * me.side) coming++; // still behind us
            else if (!u.isBot() && d < 40 && u.x * me.side > me.x * me.side && !s.inSpawnZone(u.x, u.z)) coming++; // a player on the way
        }
        int need = lastVault ? 3 : 2;
        return coming > 0 && here < need;
    }

    // =====================================================================
    // Places
    // =====================================================================

    private int[] lanePoint(GwState s, int side, int lane, int depth) {
        if (Math.abs(lane) == 3) {
            int[] sky = com.aidan.skyserver.goldwars.MapDesigns.skyPath(s.map);
            return new int[]{-side * depth, s.floorY + sky[1] + 1, Integer.signum(lane) * sky[0]};
        }
        int z = Integer.signum(lane) * (Math.abs(lane) == 2 ? s.flankLaneZ() : s.centerLaneZ());
        return new int[]{-side * depth, s.floorY + 1, z};
    }

    /** Where a route waypoint counts as reached: anywhere near it on the ground, or ON the walkway for a sky path. */
    static Pathfinder.Goal laneGoal(int lane, int[] wp) {
        if (Math.abs(lane) != 3) return flatGoal(wp[0], wp[1], wp[2], 4);
        return new Pathfinder.Goal() {
            public boolean reached(int x, int y, int z) {
                return Math.abs(x - wp[0]) <= 2 && y == wp[1] && Math.abs(z - wp[2]) <= 1;
            }

            public double estimate(int x, int y, int z) {
                return Math.abs(x - wp[0]) + Math.abs(z - wp[2]) + 2 * Math.abs(y - wp[1]);
            }
        };
    }

    private int[] waypoint(GwState s, GwState.Unit me, int lane, int[] targetVault) {
        if (me.dist(targetVault[0], targetVault[1], targetVault[2]) < 16) return null;
        while (waypoint < 2) {
            int[] wp = lanePoint(s, me.side, lane, waypoint == 0 ? 0 : (Math.abs(lane) == 2 ? 26 : 20));
            if (Math.abs(lane) == 3) {
                // Sky path: done with a waypoint once we're past it on the walkway (or have dropped off it).
                boolean passedSky = (me.x - wp[0]) * -me.side > 2;
                if (!passedSky) return wp;
                waypoint++;
                continue;
            }
            boolean passed = (me.x - wp[0]) * -me.side > 2;
            if (!passed) return wp;
            waypoint++;
        }
        return null;
    }

    private int[] routeStart(GwState s, GwState.Unit me) {
        int[] p = lanePoint(s, me.side, order.lane(), 0);
        return new int[]{me.side * (3 + rng.nextInt(3)), p[1], p[2] + rng.nextInt(5) - 2};
    }

    private int[] guardSpot(GwState s, int side, int vault) {
        int[] v = s.vault(side, vault);
        return new int[]{v[0] - side * (3 + rng.nextInt(2)), v[1] - 1, v[2] + rng.nextInt(5) - 2};
    }

    private int[] interceptPost(GwState s, GwState.Unit me) {
        // Covers the center route, or leans toward the flank if that's where players usually come.
        double flankShare = humansAgainst(s, me) ? GwLearning.humanFlankShare(s.map, order.vault()) : -1;
        int lineZ = flankShare < 0 ? s.centerLaneZ()
                : (int) Math.round(s.centerLaneZ() + (s.flankLaneZ() - s.centerLaneZ()) * Math.min(0.85, flankShare));
        int z = (order.vault() == 0 ? -1 : 1) * lineZ;
        return new int[]{me.side * (16 + rng.nextInt(8)), s.floorY + 1, z + rng.nextInt(7) - 3};
    }

    /** Walk to a spot and stay (a new nearby spot is picked now and then). */
    private GwAction hold(GwState s, GwState.Unit me, int[] spot, int keepTicks, String doing, int blocks) {
        if (post == null || s.tick >= postUntil) {
            post = spot;
            postUntil = s.tick + keepTicks + rng.nextInt(keepTicks);
        }
        return GwAction.go(post[0], post[1], post[2], Pathfinder.near(post[0], post[1], post[2], 2.5), walkOptions(blocks), doing);
    }

    private static boolean humansAgainst(GwState s, GwState.Unit me) {
        for (GwState.Unit u : s.units) if (u.side != me.side && !u.isBot()) return true;
        return false;
    }

    private int fullestVault(GwState s, int side) {
        return s.vaultCount(side, 0) >= s.vaultCount(side, 1) ? 0 : 1;
    }

    // =====================================================================
    // Helpers
    // =====================================================================

    static Pathfinder.Options attackOptions(int blocks) {
        return new Pathfinder.Options().bridge(Math.min(blocks, 24)).dig(true).nodes(5000);
    }

    static Pathfinder.Options walkOptions(int blocks) {
        return new Pathfinder.Options().bridge(Math.min(blocks, 6)).nodes(2500);
    }

    static Pathfinder.Goal flatGoal(int tx, int ty, int tz, double radius) {
        return new Pathfinder.Goal() {
            public boolean reached(int x, int y, int z) {
                double dx = x - tx, dz = z - tz;
                return dx * dx + dz * dz <= radius * radius;
            }

            public double estimate(int x, int y, int z) {
                double dx = x - tx, dz = z - tz;
                return Math.max(0, Math.sqrt(dx * dx + dz * dz) - radius) + Math.abs(y - ty) * 0.3;
            }
        };
    }

    private static double eyeDist(GwState.Unit me, int x, int y, int z) {
        return me.dist(x + 0.5, y + 0.5 - 1.62, z + 0.5);
    }

    private static double sight(GwState.Unit me) {
        return me.diff == null ? 30 : me.diff.sight;
    }

    private List<GwState.Unit> enemies(GwState s, GwState.Unit me) {
        List<GwState.Unit> list = new ArrayList<>();
        // Anyone right at our gold always counts, even if they walled themselves in (the body digs to them).
        for (GwState.Unit u : s.units) if (u.side != me.side && u.alive && (reachable(u) || nearGold(s, me.side, u, 8))) list.add(u);
        return list;
    }

    private static boolean nearGold(GwState s, int side, GwState.Unit u, double range) {
        for (long g : s.gold(side)) if (u.dist(GwState.px(g) + 0.5, GwState.py(g) + 0.5, GwState.pz(g) + 0.5) < range) return true;
        return false;
    }

    /** The nearest enemy within range of one of our vaults (null if none). */
    private GwState.Unit intruderAt(GwState s, GwState.Unit me, int vault, double range) {
        if (s.vaultCount(me.side, vault) == 0) return null;
        int[] v = s.vault(me.side, vault);
        GwState.Unit best = null;
        double bestD = range * 1.35;
        for (GwState.Unit e : enemies(s, me)) {
            double d0 = e.dist(v[0], v[1], v[2]);
            if (d0 > range) continue;
            double d = d0 * (e.isBot() ? 1.35 : 1.0);
            if (d < bestD) {
                bestD = d;
                best = e;
            }
        }
        return best;
    }

    private static boolean sees(GwState s, GwState.Unit me, GwState.Unit e) {
        return s.lineOfSight(me.x, me.y + 1.62, me.z, e.x, e.y + 1.62, e.z);
    }

    private GwState.Unit nearestEnemy(GwState s, GwState.Unit me, double range) {
        GwState.Unit best = null;
        double bestDist = range * 1.35;
        for (GwState.Unit e : enemies(s, me)) {
            if (s.inSpawnZone(e.x, e.z)) continue;
            double d = me.dist(e);
            if (d > range) continue;
            double w = weighted(me, e);
            if (w < bestDist && (d < 8 || sees(s, me, e))) {
                bestDist = w;
                best = e;
            }
        }
        return best;
    }
}
