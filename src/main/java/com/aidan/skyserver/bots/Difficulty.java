package com.aidan.skyserver.bots;

import java.util.*;

/** Bot skill dials. Change the numbers to make bots easier or harder. */
public enum Difficulty {
    //        react aimErr turn  attackAt skip  reach strafe bowLead bowErr retreatHp crits sight placeDelay
    EASY  (12,   9.0, 10,  0.80,  0.35, 2.3,  0.0,  0.0,    6.0,   0,        false, 18,   9),
    MEDIUM( 8,   5.0, 16,  0.90,  0.15, 2.5,  0.35, 0.6,    3.0,   5,        false, 26,   5),
    HARD  ( 5,   3.0, 26,  0.95,  0.05, 2.7,  0.7,  1.0,    1.2,   7,        true,  34,   3);

    /** Ticks before reacting to a new target. */
    public final int reactionTicks;
    /** Random aim wobble in degrees. */
    public final double aimError;
    /** How fast the bot can turn its head, degrees per tick. */
    public final double turnSpeed;
    /** Attack once the attack cooldown is at least this full (1.0 = fully charged). */
    public final double attackAt;
    /** Chance to skip a hit it could have made (sloppy clicking). */
    public final double skipChance;
    public final double reach;
    /** 0 = walks straight at you, 1 = strafes constantly. */
    public final double strafe;
    /** How much it leads moving targets with arrows (0 to 1). */
    public final double bowLead;
    public final double bowError;
    /** Runs away below this much health (0 = never). */
    public final float retreatHealth;
    public final boolean crits;
    /** How far away it notices enemies, in blocks. */
    public final double sight;
    /** Ticks between placing blocks. */
    public final int placeDelay;

    /** Most extra ticks the bot waits after its sword is ready (humans aren't frame-perfect). */
    public int hitDelay() {
        return switch (this) { case EASY -> 8; case MEDIUM -> 5; case HARD -> 2; };
    }

    /** Chance to re-sprint between hits (makes each hit knock you back much further). */
    public double wTapChance() {
        return switch (this) { case EASY -> 0.0; case MEDIUM -> 0.3; case HARD -> 0.6; };
    }

    Difficulty(int reactionTicks, double aimError, double turnSpeed, double attackAt, double skipChance,
               double reach, double strafe, double bowLead, double bowError, float retreatHealth,
               boolean crits, double sight, int placeDelay) {
        this.reactionTicks = reactionTicks;
        this.aimError = aimError;
        this.turnSpeed = turnSpeed;
        this.attackAt = attackAt;
        this.skipChance = skipChance;
        this.reach = reach;
        this.strafe = strafe;
        this.bowLead = bowLead;
        this.bowError = bowError;
        this.retreatHealth = retreatHealth;
        this.crits = crits;
        this.sight = sight;
        this.placeDelay = placeDelay;
    }

    /**
     * A random mix of difficulties of the given size, always using every difficulty
     * when there is room (e.g. 5 slots -> 2 of one, 2 of another, 1 of the third).
     */
    public static List<Difficulty> randomMix(int count, Random rng) {
        List<Difficulty> order = new ArrayList<>(List.of(values()));
        Collections.shuffle(order, rng);
        List<Difficulty> list = new ArrayList<>();
        for (int i = 0; i < count; i++) list.add(order.get(i % order.size()));
        Collections.shuffle(list, rng);
        return list;
    }
}
