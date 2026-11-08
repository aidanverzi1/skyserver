package com.aidan.skyserver.bots;

import java.util.*;

/**
 * Bedwars bot decisions that don't need Minecraft (plain Java, so the offline simulator runs the
 * exact same choices): the team's plan, each bot's role, what to buy next, which team upgrade to
 * save for, and when to go on the attack.
 *
 * Roles (a full bot team has one of each on the Balanced plan):
 *  - DEFENDER: wraps the bed in wool, then a tougher layer (end stone or wood), later an end stone
 *    cover; then works like everyone else (nobody stands around guarding).
 *  - COLLECTOR: short trips: grabs the diamonds that are there and comes straight home (never waits
 *    at a diamond island); emerald trips once Forge I is bought, about every 3 minutes.
 *  - RUSHER: gears up, bridges to an enemy, kills the defenders and breaks the bed.
 * One bot (whoever is home, the job rotates) always works near the base. Once their own bed is gone,
 * everyone hunts. In sudden death everyone heads for the middle.
 *
 * 5.9 playbook, from Verzi10 (skilled player, Slumber 2v2v2v2, Nov 7): home every ~40 s, never more
 * than ~20 s at a diamond island, first emerald trip right after the first Forge, team upgrades
 * Forge I-II -> Heal Pool -> Forge III -> Protection I-IV -> Forge IV, lots of fireballs and arrows.
 * Bots still buy armor early (Verzi didn't, that's his style) and buy other things by situation.
 */
public final class BwStrategy {
    private BwStrategy() {}

    public enum Plan {
        RUSH("two rushers go early with wool and stone swords"),
        BALANCED("one defends, one collects diamonds, one rushes once geared"),
        ECO("defend and collect diamonds first, attack later with upgrades and better armor");

        public final String text;

        Plan(String text) {
            this.text = text;
        }
    }

    public enum Role { DEFENDER, COLLECTOR, RUSHER, HUNTER }

    /** Defence block kinds seen around an enemy bed (bit mask, see Me.enemyDefence). */
    public static final int DEF_WOOL = 1, DEF_WOOD = 2, DEF_STONE = 4, DEF_OBSIDIAN = 8;

    /** Roles for a team's bots (real players on the team aren't given one). */
    public static Role[] roles(Plan plan, int bots) {
        Role[] three = switch (plan) {
            case RUSH -> new Role[]{Role.RUSHER, Role.DEFENDER, Role.RUSHER};
            case BALANCED -> new Role[]{Role.DEFENDER, Role.COLLECTOR, Role.RUSHER};
            case ECO -> new Role[]{Role.DEFENDER, Role.COLLECTOR, Role.COLLECTOR};
        };
        if (bots >= 3) return three;
        // Two bots on Balanced: one walls the bed then attacks, the other runs diamonds (see current()).
        if (bots == 2) return plan == Plan.RUSH ? new Role[]{Role.RUSHER, Role.DEFENDER}
                : new Role[]{Role.DEFENDER, Role.COLLECTOR};
        if (bots == 1) return new Role[]{Role.DEFENDER};
        return new Role[0];
    }

    /** Roles change as the match goes on (for a team of three or more bots). */
    public static Role current(Plan plan, Role role, double minute, boolean bedAlive, int protection, boolean suddenDeath) {
        return current(plan, role, minute, bedAlive, protection, suddenDeath, 3);
    }

    /** Roles change as the match goes on. teamBots = how many bots are on the team. */
    public static Role current(Plan plan, Role role, double minute, boolean bedAlive, int protection, boolean suddenDeath, int teamBots) {
        if (!bedAlive || suddenDeath) return Role.HUNTER;
        // Two bots on Balanced: the bed's walled by now, so the defender goes on the attack (like the
        // real players did) while the collector keeps running diamonds and is home between trips.
        if (teamBots == 2 && plan == Plan.BALANCED && role == Role.DEFENDER && minute >= 3) return Role.RUSHER;
        if (role == Role.COLLECTOR && plan == Plan.ECO && (protection >= 2 || minute >= 12)) return Role.RUSHER;
        if (role == Role.COLLECTOR && minute >= 20) return Role.RUSHER;
        return role;
    }

    /**
     * What a bot knows about itself when deciding what to buy.
     * homeWork = it's the bot working at home right now (builds the defence and the cover);
     * cover = time for the end stone cover; bridgers = enemies have been bridging at our base lately;
     * enemyDefence = DEF_* kinds around the bed it's attacking (0 = not known); wantCloud = got knocked
     * into the void lately and has no Rescue Cloud.
     */
    public record Me(Role role, int iron, int gold, int diamonds, int emeralds, int armor, int sword, int pick, int axe, boolean shears,
                     int blocks, int strongBlocks, int fireballs, boolean bow, int arrows, int gapples, double minute, int defenseMissing,
                     boolean bedBell, boolean homeWork, boolean cover, boolean bridgers, int enemyDefence, int pearls, int tnt,
                     boolean wantCloud, int endstone) {
        int res(int currency) {
            return switch (currency) {
                case 0 -> iron;
                case 1 -> gold;
                case 2 -> diamonds;
                default -> emeralds;
            };
        }

        /** The same bot with extra resources (e.g. what's in the team chest). */
        public Me plus(int[] r) {
            return new Me(role, iron + r[0], gold + r[1], diamonds + r[2], emeralds + r[3], armor, sword, pick, axe, shears, blocks,
                    strongBlocks, fireballs, bow, arrows, gapples, minute, defenseMissing, bedBell, homeWork, cover, bridgers, enemyDefence,
                    pearls, tnt, wantCloud, endstone);
        }
    }

    /**
     * The next thing to buy that the bot can afford right now, or null. The order is a human-like
     * build: blocks, a stone sword, armor early, then whatever the situation calls for.
     */
    public static String nextBuy(Plan plan, Me me) {
        for (String id : wishList(plan, me)) {
            int[] p = price(id, me);
            if (p != null && me.res(p[0]) >= p[1]) return id;
        }
        return null;
    }

    /**
     * The same choice as nextBuy, explained: every item on the bot's wish list in order, with its price
     * and whether it can pay (these are the "utility checks" written to the training log).
     */
    public static List<String> explainBuy(Plan plan, Me me) {
        List<String> out = new ArrayList<>();
        String[] cur = com.aidan.skyserver.bedwars.BwRules.CURRENCY;
        for (String id : wishList(plan, me)) {
            int[] p = price(id, me);
            if (p == null) out.add(id + ": have it");
            else if (me.res(p[0]) >= p[1]) out.add(id + ": OK " + p[1] + " " + cur[p[0]]);
            else out.add(id + ": short " + (p[1] - me.res(p[0])) + " " + cur[p[0]]);
        }
        if (out.isEmpty()) out.add("wish list empty");
        return out;
    }

    /** What the bot wants, most important first (it buys the first one it can afford). */
    public static List<String> wishList(Plan plan, Me me) {
        boolean rusher = me.role == Role.RUSHER || me.role == Role.HUNTER;
        boolean traveller = rusher || me.role == Role.COLLECTOR;
        boolean builder = me.role == Role.DEFENDER || me.homeWork;
        List<String> wish = new ArrayList<>();
        if (me.blocks < 16) wish.add("wool");
        // The bed defence (and later the end stone cover: fireballs can't break end stone).
        if (builder && me.defenseMissing > 0) {
            if (me.cover) {
                if (me.endstone < Math.min(me.defenseMissing, 24)) wish.add("endstone");
            } else {
                if (me.blocks < Math.min(me.defenseMissing, 20)) wish.add("wool");
                if (me.strongBlocks < Math.min(me.defenseMissing, 24)) wish.add(me.iron >= 24 ? "endstone" : "wood");
            }
        }
        if (me.sword < 1) wish.add("stone_sword");
        // Armor early, for everyone.
        if (me.armor < 1) wish.add("chain_armor");
        if (rusher && me.blocks < 64) wish.add("wool"); // a long bridge to an enemy base is ~40-50 blocks
        if (me.role == Role.COLLECTOR && me.blocks < 32) wish.add("wool"); // enough to bridge to the diamonds and back
        // Emeralds: diamond armor first, then a pearl (travellers) or a diamond sword.
        if (me.emeralds >= 6 && me.armor < 3) wish.add("diamond_armor");
        if (me.armor >= 3 || me.emeralds >= 10) {
            if (traveller && me.pearls < 1) wish.add("ender_pearl");
            if (me.sword < 3) wish.add("diamond_sword");
            if (!traveller && me.pearls < 1) wish.add("ender_pearl");
        }
        if (me.armor < 2 && me.minute > 2) wish.add("iron_armor");
        if (me.sword < 2 && me.minute > 3) wish.add("iron_sword");
        // Knocked into the void lately: a Rescue Cloud.
        if (me.wantCloud) wish.add("rescue_cloud");
        // A golden apple before fights (rushers before they set off).
        if (me.gapples < 1 && (rusher ? me.minute > 2 : me.minute > 5)) wish.add("golden_apple");
        if (rusher) {
            int def = me.enemyDefence;
            // Tools that match what's around the enemy bed (no idea yet: the usual shears + pickaxe).
            if ((def & DEF_WOOL) != 0 || (def == 0 && me.minute > 2)) if (!me.shears) wish.add("shears");
            if ((def & DEF_WOOD) != 0 && me.axe < 2) wish.add("axe");
            if ((def & (DEF_STONE | DEF_OBSIDIAN)) != 0 || (def == 0 && me.minute > 3)) if (me.pick < 2) wish.add("pickaxe");
            // Fireballs blow wool and wood away; end stone needs TNT (or the pickaxe).
            if ((def & (DEF_WOOL | DEF_WOOD)) != 0 && me.fireballs < 2) wish.add("fireball");
            if ((def & DEF_STONE) != 0 && me.tnt < 1) wish.add("tnt");
            if ((def & DEF_OBSIDIAN) != 0 && me.pick < 3) wish.add("pickaxe");
            // Verzi's favourites: always a couple of fireballs and a bow later on.
            if (me.minute > 5 && me.fireballs < 2) wish.add("fireball");
            if (me.minute > 8 && !me.bow) wish.add("bow");
            if (me.minute > 10 && me.pick < 3) wish.add("pickaxe");
            if (me.emeralds >= 1 && me.minute > 8) wish.add("rescue_cloud");
        }
        if (builder && me.minute > 3) wish.add("bed_bell");
        // People bridging at our base: a bow and arrows to shoot them off.
        if (me.bridgers && !me.bow) wish.add("bow");
        if (me.bow && me.arrows < 12) wish.add("arrows");
        return new ArrayList<>(new LinkedHashSet<>(wish));
    }

    /** What the next item on the bot's list costs (so it knows whether to keep collecting). */
    public static String wanted(Plan plan, Me me) {
        Me rich = new Me(me.role, 999, 999, 999, 0, me.armor, me.sword, me.pick, me.axe, me.shears, me.blocks, me.strongBlocks,
                me.fireballs, me.bow, me.arrows, me.gapples, me.minute, me.defenseMissing, me.bedBell, me.homeWork, me.cover, me.bridgers,
                me.enemyDefence, me.pearls, me.tnt, me.wantCloud, me.endstone);
        return nextBuy(plan, rich);
    }

    /** {currency, cost} for a bot (tools depend on their level), or null if it has it already. */
    public static int[] price(String id, Me me) {
        if (id.equals("pickaxe")) return me.pick >= 4 ? null : com.aidan.skyserver.bedwars.BwRules.PICKAXE_COST[me.pick];
        if (id.equals("axe")) return me.axe >= 4 ? null : com.aidan.skyserver.bedwars.BwRules.AXE_COST[me.axe];
        var o = com.aidan.skyserver.bedwars.BwRules.offer(id);
        if (o == null) return null;
        int at = com.aidan.skyserver.bedwars.BwRules.armorTier(id);
        if (at >= 0 && me.armor >= at) return null;
        int st = com.aidan.skyserver.bedwars.BwRules.swordTier(id);
        if (st >= 0 && me.sword >= st) return null;
        if (id.equals("shears") && me.shears) return null;
        if (id.equals("bed_bell") && me.bedBell) return null;
        return new int[]{o.currency(), com.aidan.skyserver.bedwars.BwRules.cost(o)};
    }

    /**
     * The team upgrade (or trap, "trap:NAME") to save diamonds for next, or null when everything
     * worth having is bought. levels = current level of each BwRules.Upgrade, traps = queued.
     *
     * 5.9: one fixed order for every plan (Verzi10's): Forge I, Forge II, Healing Spring, Forge III,
     * Protection I-IV, Forge IV. Traps never before Forge I (the first one right after the Healing
     * Spring, more once the main upgrades are done), then Sharpness and Haste.
     */
    public static String nextUpgrade(Plan plan, int[] levels, int traps, double minute) {
        int sharp = levels[0], prot = levels[1], haste = levels[2], forge = levels[3], heal = levels[4];
        if (forge < 2) return "FORGE";
        if (heal < 1) return "HEAL_POOL";
        if (traps == 0) return "trap:ITS_A_TRAP";
        if (forge < 3) return "FORGE";
        if (prot < 4) return "PROTECTION";
        if (forge < 4) return "FORGE";
        if (traps <= 1) return "trap:MINER_FATIGUE";
        if (sharp < 1) return "SHARPNESS";
        if (traps <= 2) return "trap:ALARM";
        if (haste < 1) return "HASTE";
        if (sharp < 2) return "SHARPNESS";
        if (haste < 2) return "HASTE";
        return null;
    }

    /** Diamonds the next upgrade costs. */
    public static int upgradeCost(String up, int[] levels, int traps) {
        if (up == null) return Integer.MAX_VALUE;
        if (up.startsWith("trap:")) return traps >= com.aidan.skyserver.bedwars.BwRules.MAX_TRAPS ? Integer.MAX_VALUE : com.aidan.skyserver.bedwars.BwRules.TRAP_COST[traps];
        var u = com.aidan.skyserver.bedwars.BwRules.Upgrade.valueOf(up);
        int lvl = levels[u.ordinal()];
        return lvl >= u.max() ? Integer.MAX_VALUE : u.cost(lvl + 1);
    }

    /** Are the upgrades that matter most bought (Forge II and the Healing Spring)? Then the end stone cover can start. */
    public static boolean vitalUpgradesDone(int[] levels) {
        return levels[3] >= 2 && levels[4] >= 1;
    }

    /** Is a rusher geared enough to set off? (Armor and a golden apple first, when it's not a rush.) */
    public static boolean readyToAttack(Plan plan, Me me) {
        boolean apple = me.gapples >= 1 || me.minute < 3;
        return switch (plan) {
            case RUSH -> me.blocks >= 48 && me.sword >= 1;
            case BALANCED -> me.blocks >= 56 && me.sword >= 1 && (me.armor >= 1 || me.minute >= 4) && apple;
            case ECO -> me.blocks >= 56 && me.sword >= 1 && (me.armor >= 2 || me.minute >= 14) && apple;
        };
    }

    /**
     * Which enemy team to attack: beds first (a team without a bed only matters for kills), then the
     * cheapest trip - distance plus how strongly defended it looks.
     */
    public static int pickTarget(double[] distance, boolean[] bedAlive, boolean[] out, double[] defence, int me, int previous) {
        int best = -1;
        double bestScore = 1e18;
        for (int t = 0; t < distance.length; t++) {
            if (t == me || out[t]) continue;
            // Ties (equal distances, as on these symmetric maps) go to the next team clockwise, so
            // every team gets one attacker instead of everyone piling onto the same team.
            double s = distance[t] + defence[t] * 15 + (bedAlive[t] ? 0 : 400) - (t == previous ? 10 : 0)
                    + Math.floorMod(t - me, distance.length) * 2.5;
            if (s < bestScore) {
                bestScore = s;
                best = t;
            }
        }
        return best;
    }

    /** 5.9: collectors no longer wait for a full upgrade's worth: any diamonds go home (kept for the old simulator). */
    public static int diamondsToBank(Plan plan, String nextUpgrade, int cost) {
        return 1;
    }

    /** Seconds a bot waits at a diamond / emerald generator at most before heading home (Verzi: under 20). */
    public static final int GEN_WAIT_SECONDS = 8;
    /** Seconds a lone bot may be away from home on a loop early on. */
    public static final int SOLO_AWAY_SECONDS = 45;
    /** How long one bot keeps the "work at home" job before it passes to another bot that's home. */
    public static final int HOME_JOB_SECONDS = 60;
    /** Enemies bridging closer than this to our base get intercepted. */
    public static final double INTERCEPT_RANGE = 15;
    /** An enemy this close to our bed sets off the alarm (everyone comes back). */
    public static final double BED_ALARM_RANGE = 10;

    // =====================================================================
    // The team chest
    // =====================================================================

    /**
     * Iron, gold, diamonds, emeralds a bot keeps on it; the rest goes in the team chest when it's home
     * (resources in the chest aren't lost when you die). Rushers keep fireball/TNT money. 5.9:
     * collectors bank their diamonds too (upgrades are paid from the chest). Emeralds are always kept
     * (they're spent straight away).
     */
    public static int[] keepOnHand(Role role) {
        return switch (role) {
            case RUSHER, HUNTER -> new int[]{48, 12, 0, 99};
            case DEFENDER -> new int[]{32, 8, 0, 99};
            case COLLECTOR -> new int[]{24, 6, 0, 99};
        };
    }

    /** Is this much spare worth walking to the chest for? */
    public static boolean worthBanking(int[] spare) {
        return spare[0] >= 16 || spare[1] >= 4 || spare[2] >= 1 || spare[3] >= 1;
    }
}
