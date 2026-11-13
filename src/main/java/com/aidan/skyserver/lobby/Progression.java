package com.aidan.skyserver.lobby;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Every progression number in one place: the server level curve (1-99), prestige, XP caps, the
 * battle pass and its rewards, and the cosmetics. Plain Java (no Minecraft classes), so the
 * balance simulation can use it offline. Change a number here and restart.
 *
 * Economy target (same as Rewards.java): about $2,500 an hour of active play from games and the
 * island. Level-ups add about $300/h early on and less later; the free battle pass about $13,500 a
 * season; the Captain's Pass costs more money than it pays back.
 *
 * 5.13: the new cosmetic kinds (Skywars cages, bed-break effects, name colours) live in
 * CosmeticsPlus and join the 5.8 lists here. Exclusive "2" = Voyage stamp book only (never in a
 * shop or a chest).
 */
public final class Progression {
    private Progression() {}

    // =====================================================================
    // Server level 1-99
    // =====================================================================

    public static final int MAX_LEVEL = 99;

    /** XP needed to go from this level to the next one (level 1 -> 2 is 500 XP). */
    public static int xpForNext(int level) {
        return 500 + 35 * (level - 1);
    }

    /** Total XP needed to reach a level from level 1. */
    public static long totalFor(int level) {
        long t = 0;
        for (int l = 1; l < Math.min(level, MAX_LEVEL); l++) t += xpForNext(l);
        return t;
    }

    /** Total XP at which level 99 is reached (about 215,000). */
    public static final long XP_TO_99 = totalFor(MAX_LEVEL);

    /** {level 1..99, xp into the level, xp needed for the next level (0 at 99)}. */
    public static int[] levelOf(long total) {
        int level = 1;
        long left = Math.max(0, total);
        while (level < MAX_LEVEL && left >= xpForNext(level)) {
            left -= xpForNext(level);
            level++;
        }
        if (level >= MAX_LEVEL) return new int[]{MAX_LEVEL, 0, 0};
        return new int[]{level, (int) left, xpForNext(level)};
    }

    /** Money for reaching a level. */
    public static long levelMoney(int level) {
        return 100L + 10L * level;
    }

    /** Key tier for reaching a level (0 Common .. 4 Legendary), or -1 for none. */
    public static int levelKeyTier(int level) {
        if (level == 50 || level == 99) return 4;
        if (level == 25 || level == 75) return 3;
        if (level % 10 == 0) return 2;
        if (level % 5 == 0) return 1;
        if (level % 2 == 0) return 0;
        return -1;
    }

    // =====================================================================
    // Prestige (after level 99): P1..P99, a new label every 10
    // =====================================================================

    public static final int PRESTIGE_XP = 4_000;
    public static final int MAX_PRESTIGE = 99;

    /** 0 = not prestiged yet (still level 1-99), 1..99 = prestige level. */
    public static int prestigeOf(long total) {
        if (total < XP_TO_99) return 0;
        return (int) Math.min(MAX_PRESTIGE, (total - XP_TO_99) / PRESTIGE_XP);
    }

    /** {xp into this prestige level, xp needed} (needed 0 at P99). */
    public static int[] prestigeProgress(long total) {
        int p = prestigeOf(total);
        if (p >= MAX_PRESTIGE) return new int[]{0, 0};
        long into = Math.max(0, total - XP_TO_99 - (long) p * PRESTIGE_XP);
        return new int[]{(int) into, PRESTIGE_XP};
    }

    public static long prestigeMoney(int p) {
        return 500;
    }

    /** Epic Key with every new label, Rare Key every 5 prestige levels, else none. */
    public static int prestigeKeyTier(int p) {
        if (p == 1 || p % 10 == 0 || p == MAX_PRESTIGE) return 3;
        if (p % 5 == 0) return 2;
        return -1;
    }

    public static final String[] LABELS = {"✦Skyborn", "Cloudwalker", "Windrider", "Stormchaser", "Sunseeker", "Starfarer",
            "Moonsailor", "Skywarden", "Aether Lord", "Celestial", "✦Eternal✦"};
    public static final String[] LABEL_COLORS = {"§f", "§b", "§a", "§9", "§e", "§6", "§d", "§3", "§5", "§c", "§6§l"};

    /** Which label a prestige level has (0 = Skyborn ... 10 = Eternal), or -1 before prestige. */
    public static int labelIndex(int prestige) {
        if (prestige <= 0) return -1;
        if (prestige >= MAX_PRESTIGE) return LABELS.length - 1;
        return prestige / 10;
    }

    /** The coloured label for chat, e.g. "§bCloudwalker". */
    public static String label(int prestige) {
        int i = labelIndex(prestige);
        return i < 0 ? "" : LABEL_COLORS[i] + LABELS[i];
    }

    /** Colour of the [42] level tag: changes every 10 levels. */
    public static String levelColor(int level) {
        String[] c = {"§7", "§f", "§e", "§6", "§a", "§2", "§b", "§3", "§d", "§5"};
        if (level >= MAX_LEVEL) return "§c";
        return c[Math.min(c.length - 1, level / 10)];
    }

    // =====================================================================
    // Where XP comes from (caps)
    // =====================================================================

    /** The first 300 XP each day counts double. */
    public static final int DAILY_BOOST_XP = 300;
    /** Island activity: 1 server XP for every 12 skill XP earned on the island. */
    public static final int ISLAND_SKILL_PER_XP = 12;
    public static final int ISLAND_HOUR_CAP = 450, ISLAND_DAY_CAP = 1_500;
    /** Side activities (parkour, spleef, duels, archery, maze, elytra, fishing, the Mine, logins, chests). */
    public static final int SIDE_DAY_CAP = 600;

    // =====================================================================
    // Battle pass
    // =====================================================================

    public static final int SEASON_DAYS = 42, WEEKS = 6, TIERS = 50, TIER_XP = 1_000;
    public static final int BONUS_TIER_XP = 5_000, MAX_BONUS_TIERS = 10;
    public static final long BONUS_MONEY = 250;
    public static final long PREMIUM_PRICE = 30_000;
    /** Share of every server XP gain that also counts as pass XP. */
    public static final double PASS_SHARE_OF_XP = 0.15;
    public static final int DAILY_QUEST_PASS_XP = 120, DAILY_ALL_PASS_XP = 150;
    /** Pass XP for weekly challenges: easy, medium, hard. */
    public static final int[] CHALLENGE_PASS_XP = {600, 1_000, 2_000};
    /** Weekly challenges per difficulty: 3 easy, 3 medium, 1 hard. */
    public static final int[] CHALLENGES_PER_WEEK = {3, 3, 1};
    /** End-of-season key prizes for the top 3 by pass XP: Legendary, Epic, Rare. */
    public static final int[] SEASON_PRIZE_TIER = {4, 3, 2};

    public static final String[] SEASON_NAMES = {"The Maiden Voyage", "Storm Front", "Golden Skies", "The Long Night",
            "Wren's Return", "Sky Pirates", "The Frozen Isles", "Starfall"};

    public static String seasonName(int season) {
        return SEASON_NAMES[(Math.max(1, season) - 1) % SEASON_NAMES.length];
    }

    public enum Kind { MONEY, KEY, COSMETIC, NONE }

    /** One tier reward. value = money, key tier, or cosmetic index (see cosmeticFor). */
    public record Reward(Kind kind, long value, String cosmetic) {
        static Reward money(long m) { return new Reward(Kind.MONEY, m, null); }
        static Reward key(int t) { return new Reward(Kind.KEY, t, null); }
        static Reward cosmetic(String id) { return new Reward(Kind.COSMETIC, 0, id); }
    }

    /** Free track reward for a tier (1..50). Tier 50 also gives the season's Sailor title. */
    public static Reward freeReward(int season, int tier) {
        if (tier == TIERS) return Reward.cosmetic(seasonTitle(season, false));
        if (tier % 10 == 0) return Reward.key(2);
        if (tier % 5 == 0) return Reward.key(1);
        if (tier % 2 == 0) return Reward.money(600);
        if (tier % 4 == 1) return Reward.key(0);
        return Reward.money(150);
    }

    /** Captain's Pass reward for a tier (1..50). Tier 50 = the season's exclusive Admiral title. */
    public static Reward premiumReward(int season, int tier) {
        if (tier == TIERS) return Reward.cosmetic(seasonTitle(season, true));
        if (tier == 20 || tier == 40) return Reward.key(3);
        if (tier == 10 || tier == 30) return Reward.key(2);
        if (tier % 2 == 0) return Reward.money(900);
        if (tier % 4 == 1) return Reward.key(0);
        return Reward.cosmetic(cosmeticFor(season, tier));
    }

    /** Extra keys at the very end: tier 50 on the Captain's Pass also gives a Legendary Key. */
    public static final int PREMIUM_FINAL_KEY = 4;
    /** Free tier 50 also gives a Rare Key. */
    public static final int FREE_FINAL_KEY = 2;

    // =====================================================================
    // Cosmetics (all work on vanilla clients)
    // =====================================================================

    /** Titles show after your name in chat and the tab list. id -> display. */
    public static final String[][] TITLES = {
            {"title_deckhand", "§7Deckhand"}, {"title_navigator", "§bNavigator"}, {"title_storm", "§9Storm Rider"},
            {"title_pirate", "§cSky Pirate"}, {"title_baron", "§eCloud Baron"}, {"title_wren", "§6Wren's Friend"},
            {"title_starlit", "§dStarlit"}};
    /** Trails: particles behind you while you walk (lobby and islands) and behind your arrows in games. */
    public static final String[][] TRAILS = {
            {"trail_cloud", "Cloud Trail"}, {"trail_heart", "Heart Trail"}, {"trail_note", "Music Trail"},
            {"trail_flame", "Flame Trail"}, {"trail_snow", "Snowflake Trail"}, {"trail_magic", "Magic Trail"},
            {"trail_star", "Starlight Trail"}, {"trail_leaf", "Happy Trail"}, {"trail_soul", "Soul Flame Trail"},
            {"trail_cherry", "Cherry Blossom Trail"}};

    /**
     * 5.9: titles you can only earn (never in chests, shops or the pass).
     * title_conqueror = first Nightmare Nether Expedition clear.
     * 5.19: title_cartographer = Wren's Charts complete; title_silent = first Nightmare Ancient City escape.
     * 5.20: title_tidebreaker = first Nightmare Sunken Monument clear.
     */
    public static final String[][] EARNED_TITLES = {
            {"title_conqueror", "§4§l✦Nether Conqueror"}, {"title_cartographer", "§6§l✎Wren's Cartographer"},
            {"title_silent", "§3§l✦The Silent"}, {"title_tidebreaker", "§b§l✦Tidebreaker"},
            // 5.21: Ancient City - all 6 Ancient Shards; Old Ves's stall (City Cash). trail_sculk is a trail, named here.
            {"title_archivist", "§3§l✎Archivist of the Deep"}, {"title_whisper", "§3Whisper"}, {"title_hollow_saint", "§3§l✦Hollow Saint"},
            {"trail_sculk", "§aSculk Soul Trail"}};

    /** All cosmetics the pass hands out, in the order they appear (rotated each season). */
    public static List<String> cosmeticPool() {
        List<String> all = new ArrayList<>();
        int n = Math.max(TITLES.length, TRAILS.length);
        for (int i = 0; i < n; i++) {
            if (i < TRAILS.length) all.add(TRAILS[i][0]);
            if (i < TITLES.length) all.add(TITLES[i][0]);
        }
        return all;
    }

    /** Which cosmetic a Captain's Pass tier gives (different order each season). */
    public static String cosmeticFor(int season, int tier) {
        List<String> pool = cosmeticPool();
        int slot = 0;
        for (int t = 1; t < tier; t++) {
            Reward r = premiumRewardKindOnly(t);
            if (r == null) slot++;
        }
        return pool.get(Math.floorMod(slot + (season - 1) * 5, pool.size()));
    }

    /** Same rules as premiumReward but returns null on cosmetic tiers (no recursion). */
    private static Reward premiumRewardKindOnly(int tier) {
        if (tier == TIERS) return Reward.money(0);
        if (tier == 20 || tier == 40 || tier == 10 || tier == 30) return Reward.key(0);
        if (tier % 2 == 0) return Reward.money(0);
        if (tier % 4 == 1) return Reward.key(0);
        return null;
    }

    /** Season titles: "Season 3 Sailor" (free tier 50) and "✦Season 3 Admiral" (Captain's Pass tier 50). */
    public static String seasonTitle(int season, boolean admiral) {
        return (admiral ? "title_s" + season + "_admiral" : "title_s" + season + "_sailor");
    }

    // ---- 5.8: cosmetics from mystery chests, the Cosmetic Shop and the Shard Shop ----
    // Rarity: 0 common, 1 rare, 2 legendary. Legendary ones marked "exclusive" only come from Legendary chests.

    /** More titles: id, display, rarity, exclusive (1 = Legendary chests only). */
    public static final String[][] SHOP_TITLES = {
            {"title_breeze", "§bSky Breeze", "0", "0"}, {"title_raider", "§4Nether Raider", "1", "0"}, {"title_ace", "§cAce", "1", "0"},
            {"title_tycoon", "§6§lTycoon", "2", "0"}, {"title_mythic", "§5§l✦Mythic", "2", "1"}};
    /** More trails: id, display, rarity, exclusive. */
    public static final String[][] SHOP_TRAILS = {
            {"trail_glow", "Glow Trail", "0", "0"}, {"trail_crimson", "Crimson Spore Trail", "0", "0"}, {"trail_portal", "Portal Trail", "1", "0"},
            {"trail_spark", "Spark Trail", "1", "0"}, {"trail_witch", "Witchy Trail", "1", "0"}, {"trail_totem", "Golden Totem Trail", "2", "1"}};
    /** Kill effects: shown where someone you kill falls (in the games). */
    public static final String[][] KILL_FX = {
            {"kill_flame", "Flame Burst", "0", "0"}, {"kill_heart", "Heart Burst", "0", "0"}, {"kill_note", "Final Note", "0", "0"},
            {"kill_soul", "Soul Escape", "1", "0"}, {"kill_spark", "Spark Shock", "1", "0"}, {"kill_totem", "Totem Pop", "1", "0"},
            {"kill_thunder", "Thunder Strike", "2", "1"}};
    /** Victory effects: around you when you win a game. */
    public static final String[][] WIN_FX = {
            {"win_firework", "Firework Show", "1", "0"}, {"win_soul", "Soul Storm", "1", "0"}, {"win_totem", "Golden Victory", "2", "0"},
            {"win_portal", "Portal Storm", "2", "0"}, {"win_thunder", "Thunder Crown", "2", "1"}};
    public static final String[] RARITY_NAMES = {"§fCommon", "§9Rare", "§6Legendary"};
    /** Cosmetic Shop prices ($) and Shard Shop prices (shards), by rarity. */
    public static final long[] COSMETIC_PRICE = {5_000, 15_000, 40_000};
    public static final int[] COSMETIC_SHARDS = {40, 120, 350};

    private static List<String[]> extraCosmetics() {
        List<String[]> all = new ArrayList<>();
        for (String[][] list : new String[][][]{SHOP_TITLES, SHOP_TRAILS, KILL_FX, WIN_FX}) all.addAll(Arrays.asList(list));
        all.addAll(CosmeticLists.all()); // 5.13: cages, bed-break effects, name colours
        return all;
    }

    /** Rarity of a 5.8 / 5.13 cosmetic (-1 for battle-pass ones). */
    public static int cosmeticRarity(String id) {
        for (String[] c : extraCosmetics()) if (c[0].equals(id)) return Integer.parseInt(c[2]);
        return -1;
    }

    /**
     * Cosmetics of one rarity; includeExclusive = also the Legendary-chest-only ones. Stamp-book-only
     * ones (exclusive "2", 5.13) are never included.
     */
    public static List<String> cosmeticsOfRarity(int rarity, boolean includeExclusive) {
        List<String> out = new ArrayList<>();
        for (String[] c : extraCosmetics()) {
            if (c[3].equals("2")) continue;
            if (Integer.parseInt(c[2]) == rarity && (includeExclusive || c[3].equals("0"))) out.add(c[0]);
        }
        return out;
    }

    public static boolean isKillEffect(String id) {
        return id != null && id.startsWith("kill_");
    }

    public static boolean isWinEffect(String id) {
        return id != null && id.startsWith("win_");
    }

    /** Display name of any cosmetic id ("" if unknown). */
    public static String cosmeticName(String id) {
        if (id == null) return "";
        String extra = CosmeticLists.name(id);
        if (!extra.isEmpty()) return extra;
        for (String[] t : TITLES) if (t[0].equals(id)) return t[1];
        for (String[] t : TRAILS) if (t[0].equals(id)) return "§a" + t[1];
        for (String[] t : EARNED_TITLES) if (t[0].equals(id)) return t[1];
        for (String[] t : SHOP_TITLES) if (t[0].equals(id)) return t[1];
        for (String[] t : SHOP_TRAILS) if (t[0].equals(id)) return "§a" + t[1];
        for (String[] t : KILL_FX) if (t[0].equals(id)) return "§c" + t[1] + " §7(kill effect)";
        for (String[] t : WIN_FX) if (t[0].equals(id)) return "§e" + t[1] + " §7(victory effect)";
        if (id.startsWith("title_s") && id.endsWith("_admiral")) return "§6§l✦Season " + id.substring(7, id.length() - 8) + " Admiral";
        if (id.startsWith("title_s") && id.endsWith("_sailor")) return "§3Season " + id.substring(7, id.length() - 7) + " Sailor";
        return id;
    }

    public static boolean isTitle(String id) {
        return id != null && id.startsWith("title_");
    }

    public static boolean isTrail(String id) {
        return id != null && id.startsWith("trail_");
    }
}
