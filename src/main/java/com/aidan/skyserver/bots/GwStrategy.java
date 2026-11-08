package com.aidan.skyserver.bots;

import java.util.*;

/**
 * Team commander (plain Java). Every 2 seconds it looks at the match and gives each bot a job.
 * A full team of 6 bots: 2 DEFEND (one Knight in each gold room), 1 INTERCEPT (a ranged class
 * watching the flanks and middle), 3 ATTACK (a Scout/Builder "miner" with a fighter escorting it,
 * plus one more fighter). A vault a real teammate is already guarding gets no bot defender - that
 * bot attacks instead. A vault down to its last 1-2 gold that is being overpowered while the other
 * vault is also under attack is given up.
 *
 * Attack pairs: the miner's job is the gold (it avoids fights on the way unless it's cornered or
 * its escort starts one); the escort sticks with it and kills defenders.
 *
 * Jobs are sticky (a bot keeps its job and vault for at least 20 seconds unless something urgent
 * happens), so bots commit to what they're doing instead of wandering between orders.
 */
public final class GwStrategy {

    public enum Job { DEFEND, INTERCEPT, ATTACK }

    /** vault: own vault for DEFEND/INTERCEPT, enemy vault for ATTACK. lane: -2/-1/+1/+2 route. */
    public record Order(Job job, int vault, int lane, long since) {}

    private static final int UPDATE_TICKS = 40;
    private static final int MIN_HOLD_TICKS = 400;
    private static final double THREAT_RADIUS = 16;

    private final int side;
    private final Random rng;
    private long lastUpdate = -1_000_000;
    private final Map<UUID, Order> orders = new HashMap<>();
    private final Set<UUID> needNewLane = new HashSet<>();
    private final Map<String, double[]> routeScores; // shared route memory (may be LaneMemory-backed)
    private String situation = "";
    /** Attack roles: miner -> its escort, and who is a miner / escort. */
    private final Map<UUID, UUID> escortOf = new HashMap<>(), minerOf = new HashMap<>();
    private final Set<UUID> wantMiner = new HashSet<>();
    private int[] threat = new int[2];
    private boolean anyEnemyOnOurHalf = false;

    /** Route memory: returns a score for a (map, lane); higher = has worked better. */
    public interface RouteMemory {
        double score(String map, int lane);
        void tried(String map, int lane);
    }

    private final RouteMemory memory;
    private GwLearning.Plan plan;
    private boolean learnedOk = true;

    /** The team plan picked for this match (learned per map), or null before the first update. */
    public GwLearning.Plan plan() {
        return plan;
    }

    /** Turn off using what was learned about players (the simulator compares with and without). */
    public void useLearning(boolean on) {
        learnedOk = on;
    }

    public GwStrategy(int side, Random rng, RouteMemory memory) {
        this.side = side;
        this.rng = rng;
        this.memory = memory;
        this.routeScores = new HashMap<>();
    }

    public String situation() {
        return situation;
    }

    public int threat(int vault) {
        return threat[vault];
    }

    public boolean enemyOnOurHalf() {
        return anyEnemyOnOurHalf;
    }

    public void botDied(UUID id) {
        needNewLane.add(id);
    }

    /** "miner", "escort" or "fighter" for attackers; "" otherwise. */
    public String role(UUID bot) {
        Order o = orders.get(bot);
        if (o == null || o.job() != Job.ATTACK) return "";
        if (escortOf.containsKey(bot) || wantMiner.contains(bot)) return "miner";
        if (minerOf.containsKey(bot)) return "escort";
        return "fighter";
    }

    /** The bot escorting this miner, or the miner this escort looks after (null if none). */
    public UUID buddy(UUID bot) {
        UUID e = escortOf.get(bot);
        return e != null ? e : minerOf.get(bot);
    }

    /** The class category a bot should be for its job: "knight", "ranged", "miner" or "fighter". */
    public String wantedClass(UUID bot) {
        Order o = orders.get(bot);
        if (o == null) return "";
        return switch (o.job()) {
            case DEFEND -> "knight";
            case INTERCEPT -> "ranged";
            case ATTACK -> role(bot).equals("miner") ? "miner" : "fighter";
        };
    }

    /** A bot's current order without updating anything (null if it has none yet). */
    public Order peek(UUID bot) {
        return orders.get(bot);
    }

    public Order orderFor(GwState s, UUID bot) {
        if (s.tick - lastUpdate >= UPDATE_TICKS) {
            lastUpdate = s.tick;
            update(s);
        }
        Order o = orders.get(bot);
        if (o == null) {
            o = new Order(Job.ATTACK, rng.nextInt(2), rng.nextBoolean() ? -1 : 1, s.tick);
            orders.put(bot, o);
        }
        return o;
    }

    // =====================================================================

    private void update(GwState s) {
        if (plan == null) plan = GwLearning.pickPlan(s.map, rng);
        List<GwState.Unit> team = new ArrayList<>(), bots = new ArrayList<>(), enemies = new ArrayList<>();
        boolean humansAgainstUs = false;
        for (GwState.Unit u : s.units) {
            if (u.side == side) {
                team.add(u);
                if (u.isBot()) bots.add(u);
            } else {
                if (!u.isBot()) humansAgainstUs = true;
                if (u.alive) enemies.add(u);
            }
        }
        // What we've learned about how real players on this server play this map.
        int hotVault = -1, strongHot = -1, softVault = -1;
        if (humansAgainstUs && learnedOk) {
            double north = GwLearning.humanNorthShare(s.map);
            if (north >= 0) {
                hotVault = north > 0.6 ? 0 : north < 0.4 ? 1 : -1;
                strongHot = north > 0.75 ? 0 : north < 0.25 ? 1 : -1;
            }
            double guardNorth = GwLearning.humanDefendNorthShare(s.map);
            if (guardNorth >= 0) softVault = guardNorth > 0.6 ? 1 : guardNorth < 0.4 ? 0 : -1;
        }
        bots.sort(Comparator.comparing(u -> u.id));
        int teamSize = team.size();

        int[] ourGold = {s.vaultCount(side, 0), s.vaultCount(side, 1)};
        int[] enemyGold = {s.vaultCount(-side, 0), s.vaultCount(-side, 1)};
        threat = new int[2];
        int onOurHalf = 0;
        for (GwState.Unit e : enemies) {
            for (int i = 0; i < 2; i++) {
                int[] v = s.vault(side, i);
                if (ourGold[i] > 0 && e.dist(v[0], v[1], v[2]) < THREAT_RADIUS) threat[i]++;
            }
            if (e.x * side > 4) onOurHalf++;
        }
        anyEnemyOnOurHalf = onOurHalf > 0;
        int[] defendersAt = new int[2];
        for (GwState.Unit b : bots) {
            Order o = orders.get(b.id);
            if (o != null && o.job() == Job.DEFEND && b.alive) defendersAt[o.vault()]++;
        }

        // Which of our vaults are worth defending?
        boolean[] defend = {ourGold[0] > 0, ourGold[1] > 0};
        for (int i = 0; i < 2; i++) {
            boolean nearlyGone = ourGold[i] > 0 && ourGold[i] <= 2;
            boolean overpowered = threat[i] >= Math.max(2, defendersAt[i] + 1);
            boolean otherUnderAttack = ourGold[1 - i] > 0 && threat[1 - i] > 0;
            if (nearlyGone && overpowered && otherUnderAttack) defend[i] = false;
        }
        int defendable = (defend[0] ? 1 : 0) + (defend[1] ? 1 : 0);
        int enemyVaults = (enemyGold[0] > 0 ? 1 : 0) + (enemyGold[1] > 0 ? 1 : 0);

        // How many of each job?
        int ourTotal = ourGold[0] + ourGold[1], enemyTotal = enemyGold[0] + enemyGold[1];
        boolean late = !s.buildPhase && !s.overtime && s.timerTicks < 5 * 60 * 20;
        // Real teammates already guarding a vault: no bot needed there.
        boolean[] guarded = new boolean[2];
        for (GwState.Unit u : team) {
            if (u.isBot() || !u.alive) continue;
            for (int i = 0; i < 2; i++) {
                int[] v = s.vault(side, i);
                if (ourGold[i] > 0 && u.dist(v[0], v[1], v[2]) < 14) guarded[i] = true;
            }
        }
        int nb = bots.size();
        int def = 0;
        for (int i = 0; i < 2; i++) if (defend[i] && !guarded[i]) def++;
        if (nb <= 2) def = Math.min(def, 1);
        int inter = nb >= 4 ? 1 : 0;
        if (threat[0] + threat[1] >= 3 && nb >= 5) inter = 2;   // under heavy attack: a second hunter
        if (plan == GwLearning.Plan.AGGRESSIVE && !anyEnemyOnOurHalf) inter = 0;
        if (plan == GwLearning.Plan.DEFENSIVE && nb >= 5) inter = Math.max(inter, 2);
        situation = plan.name().toLowerCase() + " plan";
        if (s.overtime) {
            def = 1;
            inter = 0;
            situation = "overtime: all-in";
        } else if (late && ourTotal < enemyTotal) {
            def = 1;
            inter = Math.min(inter, 1);
            situation = "behind late: pushing";
        } else if (late && ourTotal > enemyTotal) {
            inter += 1;
            situation = "ahead late: protecting the lead";
        }
        if (defendable == 1 && nb >= 4) {
            int lastV = defend[0] ? 0 : 1;
            if (!guarded[lastV]) def = Math.max(def, 2);
            situation += ", one vault left";
        }
        if (defendable == 0) def = 0;
        if (enemyVaults == 1 && !situation.startsWith("ahead")) {
            // Their last vault: everyone who isn't defending goes, if we're not ahead.
            inter = ourTotal <= enemyTotal ? 0 : Math.max(0, inter - 1);
            situation += ourTotal <= enemyTotal ? ", their last vault: all in" : ", their last vault";
        }
        // Enemy down to their last 3 gold blocks: everyone goes for it (one Knight stays home only if
        // we're nearly out too).
        if (enemyTotal > 0 && enemyTotal <= 3) {
            def = ourTotal <= 3 && defendable > 0 ? Math.min(def, 1) : 0;
            inter = 0;
            situation += ", ALL-OUT ATTACK (" + enemyTotal + " gold left)";
        }
        def = Math.min(def, bots.size());
        inter = Math.min(inter, bots.size() - def);

        // Who does what (sticky)?
        Map<UUID, Order> next = new HashMap<>();
        List<GwState.Unit> pool = new ArrayList<>(bots);
        List<GwState.Unit> defenders = take(pool, def, Job.DEFEND, s.tick);
        List<GwState.Unit> interceptors = take(pool, inter, Job.INTERCEPT, s.tick);
        List<GwState.Unit> attackers = pool;

        for (GwState.Unit b : defenders) {
            Order old = orders.get(b.id);
            int v;
            if (defendable == 1) {
                v = defend[0] ? 0 : 1;
            } else if (guarded[0] != guarded[1]) {
                v = guarded[0] ? 1 : 0;   // the vault no real teammate is watching
            } else if (old != null && old.job() == Job.DEFEND && defend[old.vault()]) {
                v = old.vault();
                // Only move if the other vault is in trouble and nobody is there.
                int other = 1 - v;
                if (threat[other] >= 2 && threat[v] == 0 && count(next, Job.DEFEND, other) == 0 && defendersAt[other] == 0) v = other;
            } else {
                int c0 = count(next, Job.DEFEND, 0), c1 = count(next, Job.DEFEND, 1);
                v = c0 == c1 && hotVault >= 0 ? hotVault : c0 <= c1 ? 0 : 1; // players usually come this way
                if (!defend[v]) v = 1 - v;
            }
            keepOrNew(next, b, Job.DEFEND, v, v == 0 ? -1 : 1, old, s.tick);
        }
        for (GwState.Unit b : interceptors) {
            Order old = orders.get(b.id);
            int v;
            if (old != null && old.job() == Job.INTERCEPT && ourGold[old.vault()] > 0) {
                v = old.vault();
                if (threat[1 - v] >= threat[v] + 2 && ourGold[1 - v] > 0) v = 1 - v;
            } else {
                int c0 = count(next, Job.INTERCEPT, 0), c1 = count(next, Job.INTERCEPT, 1);
                if (strongHot >= 0) v = strongHot; // nearly every player attack comes this way
                else v = c0 == c1 && hotVault >= 0 ? hotVault : c0 <= c1 ? 0 : 1;
            }
            if (ourGold[v] == 0 && ourGold[1 - v] > 0) v = 1 - v;
            keepOrNew(next, b, Job.INTERCEPT, v, v == 0 ? -1 : 1, old, s.tick);
        }
        for (GwState.Unit b : attackers) {
            Order old = orders.get(b.id);
            int v;
            if (enemyGold[0] == 0) v = 1;
            else if (enemyGold[1] == 0) v = 0;
            else if (old != null && old.job() == Job.ATTACK) v = old.vault();
            else {
                int c0 = count(next, Job.ATTACK, 0), c1 = count(next, Job.ATTACK, 1);
                v = c0 == c1 && softVault >= 0 ? softVault : c0 <= c1 ? 0 : 1; // the vault players guard less
            }
            boolean keepLane = old != null && old.job() == Job.ATTACK && old.vault() == v && !needNewLane.contains(b.id);
            int lane = keepLane ? old.lane() : pickLane(s, b, v, next);
            if (!keepLane && memory != null) memory.tried(s.map, lane);
            keepOrNew(next, b, Job.ATTACK, v, lane, old, s.tick);
        }
        // Balance attackers across both enemy vaults (pressure both), moving the newest first.
        if (enemyVaults == 2) {
            int a0 = count(next, Job.ATTACK, 0), a1 = count(next, Job.ATTACK, 1);
            while (Math.abs(a0 - a1) > 1) {
                int from = a0 > a1 ? 0 : 1;
                UUID move = null;
                long newest = Long.MIN_VALUE;
                for (Map.Entry<UUID, Order> e : next.entrySet())
                    if (e.getValue().job() == Job.ATTACK && e.getValue().vault() == from && e.getValue().since() > newest) {
                        newest = e.getValue().since();
                        move = e.getKey();
                    }
                if (move == null) break;
                GwState.Unit b = s.unit(move);
                next.put(move, new Order(Job.ATTACK, 1 - from, pickLane(s, b, 1 - from, next), s.tick));
                if (from == 0) { a0--; a1++; } else { a1--; a0++; }
            }
        }
        needNewLane.clear();
        orders.clear();
        orders.putAll(next);
        pairUp(s, bots);
    }

    /**
     * For each enemy vault with 2+ attackers: one miner (a Scout or Builder - or the bot that will
     * switch to one) and the nearest fighter as its escort. Pairs are kept while both still attack it.
     */
    private void pairUp(GwState s, List<GwState.Unit> bots) {
        Map<UUID, UUID> oldEscort = new HashMap<>(escortOf);
        escortOf.clear();
        minerOf.clear();
        wantMiner.clear();
        for (int v = 0; v < 2; v++) {
            List<GwState.Unit> att = new ArrayList<>();
            for (GwState.Unit b : bots) {
                Order o = orders.get(b.id);
                if (o != null && o.job() == Job.ATTACK && o.vault() == v) att.add(b);
            }
            if (att.size() < 2) {
                // A lone attacker mines if it can, fights otherwise.
                for (GwState.Unit b : att) if (isMinerClass(b.cls)) wantMiner.add(b.id);
                continue;
            }
            // Keep an existing pair if both are still here.
            GwState.Unit miner = null;
            for (GwState.Unit b : att) if (oldEscort.containsKey(b.id) && att.stream().anyMatch(u -> u.id.equals(oldEscort.get(b.id)))) miner = b;
            if (miner == null) for (GwState.Unit b : att) if (isMinerClass(b.cls)) { miner = b; break; }
            if (miner == null) miner = att.get(att.size() - 1); // nobody fits: this one switches to Scout/Builder
            UUID keep = oldEscort.get(miner.id);
            GwState.Unit escort = null;
            if (keep != null) for (GwState.Unit b : att) if (b.id.equals(keep)) escort = b;
            if (escort == null) {
                double best = 1e9;
                for (GwState.Unit b : att) {
                    if (b == miner) continue;
                    double score = miner.dist(b) + (isMinerClass(b.cls) ? 40 : 0) + ("knight".equals(b.cls) ? -10 : 0);
                    if (score < best) { best = score; escort = b; }
                }
            }
            wantMiner.add(miner.id);
            if (escort != null) {
                escortOf.put(miner.id, escort.id);
                minerOf.put(escort.id, miner.id);
            }
        }
    }

    static boolean isMinerClass(String cls) {
        return "scout".equals(cls) || "builder".equals(cls);
    }

    private void keepOrNew(Map<UUID, Order> next, GwState.Unit b, Job job, int vault, int lane, Order old, long tick) {
        boolean same = old != null && old.job() == job && old.vault() == vault && old.lane() == lane;
        next.put(b.id, same ? old : new Order(job, vault, lane, tick));
    }

    private int pickLane(GwState s, GwState.Unit b, int v, Map<UUID, Order> sofar) {
        int sign = v == 0 ? -1 : 1, center = sign, flank = sign * 2;
        if ("scout".equals(b.cls)) return flank;
        int sky = sign * 3;
        int onCenter = 0, onFlank = 0, onSky = 0;
        for (Order o : sofar.values()) {
            if (o.job() != Job.ATTACK) continue;
            if (o.lane() == center) onCenter++;
            if (o.lane() == flank) onFlank++;
            if (o.lane() == sky) onSky++;
        }
        // The sky path is one block wide: at most one bot on it at a time, and not heavy/slow classes.
        boolean skyOk = onSky == 0 && !"knight".equals(b.cls) && !"builder".equals(b.cls);
        return switch (b.diff) {
            case EASY -> rng.nextInt(10) < 7 ? center : flank;
            case MEDIUM -> skyOk && rng.nextInt(10) == 0 ? sky : onCenter <= onFlank ? center : flank;
            case HARD -> {
                double sc = (memory == null ? 1 : memory.score(s.map, center)) / (1 + 0.35 * onCenter);
                double sf = (memory == null ? 1 : memory.score(s.map, flank)) / (1 + 0.35 * onFlank);
                double ss = skyOk ? (memory == null ? 0.9 : memory.score(s.map, sky)) * 0.9 : -1;
                if (ss > sc && ss > sf) yield sky;
                yield sf >= sc ? flank : center;
            }
        };
    }

    /** Takes the n bots best suited to a job (current holders strongly preferred, so jobs are sticky). */
    private List<GwState.Unit> take(List<GwState.Unit> pool, int n, Job job, long tick) {
        List<GwState.Unit> sorted = new ArrayList<>(pool);
        sorted.sort(Comparator.comparingInt((GwState.Unit b) -> -suitability(b, job, tick)));
        List<GwState.Unit> out = new ArrayList<>(sorted.subList(0, Math.max(0, Math.min(n, sorted.size()))));
        pool.removeAll(out);
        return out;
    }

    private int suitability(GwState.Unit b, Job job, long tick) {
        Order old = orders.get(b.id);
        int score = 0;
        if (old != null && old.job() == job) score += 5;
        if (old != null && old.job() != job && tick - old.since() < MIN_HOLD_TICKS) score -= 4; // let it finish its current job
        String c = b.cls;
        // Gold rooms are held by Knights; the flanks by the ranged classes; Scouts/Builders attack.
        if (job == Job.DEFEND) score += switch (c) { case "knight" -> 3; case "builder" -> 0; default -> -2; };
        if (job == Job.INTERCEPT) score += switch (c) { case "archer", "longbow", "mage" -> 3; case "knight" -> -1; default -> -2; };
        if (job == Job.ATTACK) score += switch (c) { case "scout", "builder" -> 3; default -> 0; };
        return score;
    }

    private static int count(Map<UUID, Order> orders, Job job, int vault) {
        int n = 0;
        for (Order o : orders.values()) if (o.job() == job && o.vault() == vault) n++;
        return n;
    }
}
