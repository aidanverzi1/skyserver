package com.aidan.skyserver.bots;

import java.util.*;

/** Random, normal-looking player names for bots. */
public final class BotNames {
    private static final String[] WORDS = {
            "Frost", "Pixel", "Shadow", "Blaze", "Lucky", "Turbo", "Mango", "Cobalt", "Echo", "Raven",
            "Nova", "Ziggy", "Bean", "Toast", "Moss", "Comet", "Dusty", "Waffle", "Kiwi", "Vortex",
            "Ember", "Rusty", "Ninja", "Panda", "Otter", "Fox", "Wolf", "Hawk", "Storm", "Sky",
            "Stone", "Maple", "Pickle", "Nugget", "Biscuit", "Gizmo", "Jinx", "Rocket", "Sprout", "Taco",
            "Noodle", "Muffin", "Sunny", "Glitch", "Bolt", "Drift", "Flint", "Gravel", "Hazel", "Iris",
            "Jade", "Koda", "Luna", "Milo", "Nash", "Onyx", "Piper", "Quill", "Rex", "Sage",
            "Tango", "Ursa", "Vex", "Willow", "Yeti", "Zane", "Acorn", "Badger", "Cactus", "Dingo",
            "Falcon", "Goose", "Honey", "Igloo", "Jelly", "Karma", "Lemon", "Mocha", "Nacho", "Olive",
            "Pepper", "Quartz", "Rogue", "Salsa", "Tiger", "Viper", "Walrus", "Yarrow", "Zest", "Crow",
            "Ash", "Birch", "Cedar", "Coal", "Copper", "Dune", "Flare", "Ghost", "Hex", "Ivy"
    };
    private static final String[] ENDINGS = {"YT", "MC", "TV", "XD", "Pro", "HD", "Jr", "Gamer", "Plays", "Craft"};

    private static final Random RNG = new Random();

    private BotNames() {}

    /** A fresh name that isn't in the given set (compared ignoring case). */
    public static String next(Set<String> taken) {
        Set<String> lower = new HashSet<>();
        for (String t : taken) lower.add(t.toLowerCase(Locale.ROOT));
        for (int tries = 0; tries < 200; tries++) {
            String name = generate();
            if (name.length() >= 3 && name.length() <= 16 && !lower.contains(name.toLowerCase(Locale.ROOT))) return name;
        }
        return "Player" + (1000 + RNG.nextInt(9000));
    }

    private static String word() {
        return WORDS[RNG.nextInt(WORDS.length)];
    }

    private static String number() {
        return switch (RNG.nextInt(4)) {
            case 0 -> String.valueOf(RNG.nextInt(100));
            case 1 -> String.valueOf(2000 + RNG.nextInt(16));
            case 2 -> String.valueOf(100 + RNG.nextInt(900));
            default -> String.valueOf(RNG.nextInt(10)) + RNG.nextInt(10);
        };
    }

    private static String generate() {
        return switch (RNG.nextInt(9)) {
            case 0 -> word() + word();
            case 1 -> word() + "_" + word();
            case 2 -> word() + number();
            case 3 -> word().toLowerCase(Locale.ROOT) + number();
            case 4 -> "x" + word() + "x";
            case 5 -> word() + ENDINGS[RNG.nextInt(ENDINGS.length)];
            case 6 -> "The" + word() + word();
            case 7 -> word().toLowerCase(Locale.ROOT) + "_" + word().toLowerCase(Locale.ROOT);
            default -> word() + word() + RNG.nextInt(10);
        };
    }
}
