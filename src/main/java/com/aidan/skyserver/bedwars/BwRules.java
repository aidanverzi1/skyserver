package com.aidan.skyserver.bedwars;

import java.util.*;

/**
 * Every Bedwars number in one place: generator speeds, the match timeline, shop prices and team
 * upgrades. Plain Java, so the offline simulator plays with exactly the same numbers as the server.
 * Change a number here and both the game and the bots follow it.
 *
 * 5.13: two Voyage switches, set by BwVoyage when a match starts: fireballFrenzy (fireballs half
 * price) and rushHour (the faster timeline in Event.rushMinute).
 */
public final class BwRules {
    private BwRules() {}

    /** 5.13 Voyage: Fireball Frenzy this match (fireballs cost half). */
    public static boolean fireballFrenzy = false;
    /** 5.13 Voyage: Rush Hour this match (events every 4 minutes, beds at 20, sudden death at 25). */
    public static boolean rushHour = false;

    // =====================================================================
    // Teams and match length
    // =====================================================================
    public static final int TEAMS = 4;

    /**
     * Team size. 3v3v3v3 uses Hypixel's 3s/4s prices, 2v2v2v2 uses Hypixel's Solo/Doubles prices
     * (cheaper upgrades, TNT 4 gold). Only one Bedwars match runs at a time; the first player to
     * join an empty Bedwars picks the mode.
     */
    public enum Mode {
        TRIOS("3v3v3v3", "4 teams of 3", 3),
        DUOS("2v2v2v2", "4 teams of 2", 2);

        public final String display, text;
        public final int size;

        Mode(String display, String text, int size) {
            this.display = display;
            this.text = text;
            this.size = size;
        }

        public static Mode parse(String s) {
            if (s == null) return null;
            return switch (s.toLowerCase(java.util.Locale.ROOT)) {
                case "trios", "trio", "3s", "3", "3v3", "3v3v3v3", "threes" -> TRIOS;
                case "duos", "duo", "2s", "2", "2v2", "2v2v2v2", "doubles", "twos" -> DUOS;
                default -> null;
            };
        }
    }

    /** The mode of the current (or next) match. */
    public static Mode mode = Mode.TRIOS;

    /** Players per team in the current mode. */
    public static int perTeam() {
        return mode.size;
    }

    /** Everyone a full match holds. */
    public static int maxPlayers() {
        return TEAMS * perTeam();
    }
    public static final int MIN_PLAYERS = 2;
    public static final int COUNTDOWN_SECONDS = 30;
    public static final int FULL_COUNTDOWN_SECONDS = 10;
    public static final int RESPAWN_SECONDS = 5;
    public static final int SPAWN_INVULN_SECONDS = 3;

    // =====================================================================
    // Resources: 0 iron, 1 gold, 2 diamond, 3 emerald
    // =====================================================================
    public static final int IRON = 0, GOLD = 1, DIAMOND = 2, EMERALD = 3;
    public static final String[] CURRENCY = {"Iron", "Gold", "Diamond", "Emerald"};
    public static final String[] CURRENCY_COLOR = {"§f", "§6", "§b", "§2"};

    /** Home generator: ticks per iron / gold at forge level 0..4 (forge 3 also adds emeralds). */
    public static final int[] IRON_TICKS = {22, 15, 11, 11, 8};
    public static final int[] GOLD_TICKS = {130, 87, 65, 65, 44};
    /** Emeralds at home from Emerald Forge (level 3+). */
    public static final int HOME_EMERALD_TICKS = 1500;
    /** Most items of one kind left lying on a home generator (stops piles building up). */
    public static final int IRON_CAP = 48, GOLD_CAP = 12, DIAMOND_CAP = 4, EMERALD_CAP = 2, HOME_EMERALD_CAP = 2;

    /** Diamond and emerald generators: seconds per item at tier I, II, III. */
    public static final int[] DIAMOND_SECONDS = {30, 23, 12};
    public static final int[] EMERALD_SECONDS = {65, 50, 35};

    // =====================================================================
    // Timeline (minutes into the match)
    // =====================================================================
    public enum Event {
        DIAMOND_2(6, 4, "Diamond II"),
        EMERALD_2(12, 8, "Emerald II"),
        DIAMOND_3(18, 12, "Diamond III"),
        EMERALD_3(24, 16, "Emerald III"),
        BEDS_GONE(30, 20, "Bed Destruction"),
        SUDDEN_DEATH(35, 25, "Sudden Death"),
        GAME_END(45, 35, "Game End");

        public final int minute;
        /** 5.13: the minute during a Rush Hour Voyage match. */
        public final int rushMinute;
        public final String display;

        Event(int minute, int rushMinute, String display) {
            this.minute = minute;
            this.rushMinute = rushMinute;
            this.display = display;
        }

        public long tick() {
            return (rushHour ? rushMinute : minute) * 60L * 20L;
        }
    }

    /** Sudden death: the border closes from the map's edge to this half-width over this many minutes. */
    public static final int BORDER_END_HALF = 8;
    public static final int BORDER_SHRINK_MINUTES = 8;

    // =====================================================================
    // The item shop
    // =====================================================================
    public enum Category {
        QUICK("Quick Buy"), BLOCKS("Blocks"), MELEE("Melee"), ARMOR("Armor"), TOOLS("Tools"),
        RANGED("Ranged"), POTIONS("Potions"), UTILITY("Utility"), SPECIAL("SkyServer Specials");

        public final String display;

        Category(String display) {
            this.display = display;
        }
    }

    /**
     * One thing in the item shop. amount = how many you get. currency/cost = the price.
     * The game turns the id into a real item (BwShop); bots and the simulator buy by id.
     */
    public record Offer(String id, String name, Category cat, int amount, int currency, int cost, String... about) {}

    public static final List<Offer> OFFERS = List.of(
            // Blocks
            new Offer("wool", "Wool", Category.BLOCKS, 16, IRON, 4, "Cheap and quick to place.", "Your team's colour."),
            new Offer("clay", "Hardened Clay", Category.BLOCKS, 16, IRON, 12, "Tougher than wool."),
            new Offer("glass", "Shatterproof Glass", Category.BLOCKS, 4, IRON, 12, "Explosions can't break it."),
            new Offer("endstone", "End Stone", Category.BLOCKS, 12, IRON, 24, "Slow to break, shrugs off fireballs."),
            new Offer("ladder", "Ladder", Category.BLOCKS, 8, IRON, 4, "Climb your own defences."),
            new Offer("wood", "Oak Planks", Category.BLOCKS, 16, GOLD, 4, "Strong, and axes break it fast."),
            new Offer("obsidian", "Obsidian", Category.BLOCKS, 4, EMERALD, 4, "The toughest block in the game."),
            // Melee
            new Offer("stone_sword", "Stone Sword", Category.MELEE, 1, IRON, 10, "Replaces your wooden sword."),
            new Offer("iron_sword", "Iron Sword", Category.MELEE, 1, GOLD, 7),
            new Offer("diamond_sword", "Diamond Sword", Category.MELEE, 1, EMERALD, 4),
            new Offer("kb_stick", "Knockback Stick", Category.MELEE, 1, GOLD, 5, "Knockback I. Send them flying."),
            // Armor (kept for the whole match)
            new Offer("chain_armor", "Chainmail Armor", Category.ARMOR, 1, IRON, 24, "Leggings and boots.", "§aKept when you die."),
            new Offer("iron_armor", "Iron Armor", Category.ARMOR, 1, GOLD, 12, "Leggings and boots.", "§aKept when you die."),
            new Offer("diamond_armor", "Diamond Armor", Category.ARMOR, 1, EMERALD, 6, "Leggings and boots.", "§aKept when you die."),
            // Tools (go down one level when you die)
            new Offer("pickaxe", "Pickaxe", Category.TOOLS, 1, IRON, 10, "Upgrades one level per buy.", "§eDrops one level when you die."),
            new Offer("axe", "Axe", Category.TOOLS, 1, IRON, 10, "Upgrades one level per buy.", "§eDrops one level when you die."),
            new Offer("shears", "Shears", Category.TOOLS, 1, IRON, 20, "Cut through wool fast.", "§aKept when you die."),
            // Ranged
            new Offer("arrows", "Arrows", Category.RANGED, 6, GOLD, 2),
            new Offer("bow", "Bow", Category.RANGED, 1, GOLD, 12),
            new Offer("bow_power", "Bow (Power I)", Category.RANGED, 1, GOLD, 20),
            new Offer("bow_punch", "Bow (Power I, Punch I)", Category.RANGED, 1, EMERALD, 5),
            // Potions
            new Offer("speed_potion", "Speed II Potion (45s)", Category.POTIONS, 1, EMERALD, 1),
            new Offer("jump_potion", "Jump V Potion (45s)", Category.POTIONS, 1, EMERALD, 1),
            new Offer("invis_potion", "Invisibility Potion (30s)", Category.POTIONS, 1, EMERALD, 2, "Hides you and your name."),
            // Utility
            new Offer("golden_apple", "Golden Apple", Category.UTILITY, 1, GOLD, 3, "Heals fast, with absorption."),
            new Offer("fireball", "Fireball", Category.UTILITY, 1, IRON, 40, "Right-click to launch.", "Breaks placed blocks, knocks back.", "Jump on its blast to fly!"),
            new Offer("tnt", "TNT", Category.UTILITY, 1, GOLD, 8, "Lights itself when placed.", "Glass and obsidian stop it."),
            new Offer("ender_pearl", "Ender Pearl", Category.UTILITY, 1, EMERALD, 4),
            new Offer("water", "Water Bucket", Category.UTILITY, 1, GOLD, 3, "Break a fall, push people back."),
            new Offer("bridge_egg", "Builder's Egg", Category.UTILITY, 1, EMERALD, 1, "Throw it: lays a wool bridge", "along its path."),
            new Offer("magic_milk", "Calm Milk", Category.UTILITY, 1, GOLD, 4, "Enemy traps ignore you for 30s."),
            new Offer("popup_tower", "Instant Watchtower", Category.UTILITY, 1, IRON, 24, "Place it: a wool tower", "with a ladder builds itself."),
            new Offer("sky_mite", "Sky Mite", Category.UTILITY, 1, IRON, 24, "Throw it: a mite hatches where it", "lands and attacks enemies for 15s."),
            // SkyServer specials
            new Offer("grapple", "Wren's Grapple", Category.SPECIAL, 1, GOLD, 6, "Cast at a block, reel in", "to be pulled to it. 5 uses.", "§7Captain Wren's own design."),
            new Offer("decoy_bed", "Decoy Bed", Category.SPECIAL, 1, GOLD, 6, "Looks just like your bed.", "Enemies who break it get", "blasted and lit up for all to see."),
            new Offer("blanket", "Bed Blanket", Category.SPECIAL, 1, EMERALD, 3, "Right-click: your bed can't be", "broken for 20 seconds.", "§eOnce per team per match."),
            new Offer("rescue_cloud", "Rescue Cloud", Category.SPECIAL, 1, EMERALD, 1, "Keep it on you: if you fall", "into the void, a cloud catches you.", "§eUsed up when it saves you."),
            new Offer("spring_pad", "Spring Pad", Category.SPECIAL, 1, GOLD, 4, "Place it, step on it, fly.", "Great for crossing gaps."),
            new Offer("airship_plank", "Airship Plank", Category.SPECIAL, 1, GOLD, 2, "Place it: an 8-block plank walk", "shoots out ahead of you.", "§eIt crumbles after 10 seconds."),
            new Offer("bed_bell", "Bed Bell", Category.SPECIAL, 1, GOLD, 4, "Rings for your whole team when an", "enemy breaks blocks near your bed.", "§eOnce per team, lasts all match.")
    );

    /** The Quick Buy page. */
    public static final List<String> QUICK_BUY = List.of(
            "wool", "stone_sword", "chain_armor", "pickaxe", "bow", "speed_potion", "tnt",
            "wood", "iron_sword", "iron_armor", "shears", "arrows", "invis_potion", "water",
            "glass", "diamond_sword", "diamond_armor", "axe", "bow_power", "jump_potion", "golden_apple",
            "endstone", "kb_stick", "fireball", "ender_pearl", "popup_tower", "bridge_egg", "grapple");

    public static Offer offer(String id) {
        for (Offer o : OFFERS) if (o.id().equals(id)) return o;
        return null;
    }

    /** Item prices that are different in 2v2v2v2 (Hypixel Solo/Doubles): offer id -> cost. */
    private static final Map<String, Integer> DUOS_COST = Map.of("tnt", 4);

    /** What an offer costs in the current mode (same currency in both modes). 5.13: fireballs half price in a Fireball Frenzy match. */
    public static int cost(Offer o) {
        int c = mode == Mode.DUOS && DUOS_COST.containsKey(o.id()) ? DUOS_COST.get(o.id()) : o.cost();
        if (fireballFrenzy && o.id().equals("fireball")) c = Math.max(1, c / 2);
        return c;
    }

    // ---- Tool levels ----
    /** Pickaxe levels 1..4 (wood Eff I, iron Eff II, gold Eff III, diamond Eff III): currency and cost of each. */
    public static final int[][] PICKAXE_COST = {{IRON, 10}, {IRON, 10}, {GOLD, 3}, {GOLD, 6}};
    /** Axe levels 1..4 (wood Eff I, stone Eff I, iron Eff II, diamond Eff III). */
    public static final int[][] AXE_COST = {{IRON, 10}, {IRON, 10}, {GOLD, 3}, {GOLD, 6}};

    /** Armor tier: 0 leather, 1 chain, 2 iron, 3 diamond. */
    public static int armorTier(String offerId) {
        return switch (offerId) {
            case "chain_armor" -> 1;
            case "iron_armor" -> 2;
            case "diamond_armor" -> 3;
            default -> -1;
        };
    }

    /** Sword tier: 0 wood, 1 stone, 2 iron, 3 diamond. */
    public static int swordTier(String offerId) {
        return switch (offerId) {
            case "stone_sword" -> 1;
            case "iron_sword" -> 2;
            case "diamond_sword" -> 3;
            default -> -1;
        };
    }

    // =====================================================================
    // Team upgrades (paid in diamonds) and traps
    // =====================================================================
    public enum Upgrade {
        //                                                                                       3v3v3v3 costs          2v2v2v2 costs
        SHARPNESS("Honed Blades", "Sharpness on all your team's swords and axes.", new int[]{4, 12}, new int[]{4, 12}),
        PROTECTION("Steel Weave", "Protection on your team's armor.", new int[]{5, 10, 20, 30}, new int[]{2, 4, 8, 16}),
        HASTE("Quick Picks", "Your team mines faster (Haste).", new int[]{4, 6}, new int[]{2, 4}),
        FORGE("Forge", "Your home generator makes more (level III adds emeralds).", new int[]{4, 8, 12, 16}, new int[]{2, 4, 6, 8}),
        HEAL_POOL("Healing Spring", "Regeneration for your team near your bed.", new int[]{3}, new int[]{2});

        public final String display;
        public final String about;
        private final int[] trios, duos;

        Upgrade(String display, String about, int[] trios, int[] duos) {
            this.display = display;
            this.about = about;
            this.trios = trios;
            this.duos = duos;
        }

        public int max() {
            return trios.length;
        }

        /** Diamonds for level (1..max) in the current mode. */
        public int cost(int level) {
            int[] c = mode == Mode.DUOS ? duos : trios;
            return c[Math.max(0, Math.min(c.length, level) - 1)];
        }

        /** Level name for the menu. */
        public String levelName(int level) {
            if (this == FORGE) return switch (level) {
                case 1 -> "Forge I (+50% iron & gold)";
                case 2 -> "Forge II (+100%)";
                case 3 -> "Forge III (adds emeralds)";
                default -> "Forge IV (+200%)";
            };
            return display + (max() > 1 ? " " + new String[]{"", "I", "II", "III", "IV"}[level] : "");
        }
    }

    public enum Trap {
        ITS_A_TRAP("Snare Trap", "Blindness and Slowness on the intruder (8s)."),
        COUNTER("Rally Trap", "Speed and Jump Boost for your team near the bed (15s)."),
        ALARM("Tripwire Alarm", "Reveals invisible intruders and alerts your whole team."),
        MINER_FATIGUE("Heavy-Hands Trap", "Mining Fatigue on the intruder (10s). Great against bed rushers.");

        public final String display;
        public final String about;

        Trap(String display, String about) {
            this.display = display;
            this.about = about;
        }
    }

    public static final int MAX_TRAPS = 3;
    /** Cost in diamonds of the next trap, by how many are already queued (0, 1, 2). Same as Hypixel in every mode. */
    public static final int[] TRAP_COST = {1, 2, 4};
    public static final int SKY_MITE_SECONDS = 15, PLANK_SECONDS = 10, PLANK_LENGTH = 8;
    public static final double BED_BELL_RADIUS = 4.5;
    /** How close (blocks) an enemy must get to a base to set off a trap / the bed alarm. */
    public static final double TRAP_RADIUS = 9;
    public static final double BED_ALARM_RADIUS = 10;
    public static final double HEAL_POOL_RADIUS = 9;
    public static final int BLANKET_SECONDS = 20;
    public static final int MAGIC_MILK_SECONDS = 30;
}
