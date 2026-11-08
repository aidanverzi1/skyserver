package com.aidan.skyserver.bedwars;

import java.util.Map;

/**
 * The programmatic shop: how a bot (or a headless training simulator) reads its balances, gear and
 * team upgrades and buys things without clicking through the shop menus. Plain Java, so the offline
 * simulator can supply its own version; on the server use {@code BedwarsBots.shop(player)}.
 *
 * Offer ids are the ones in BwRules.OFFERS ("wool", "stone_sword", "chain_armor", "pickaxe", ...).
 * Upgrade ids are BwRules.Upgrade names ("SHARPNESS", "PROTECTION", "HASTE", "FORGE", "HEAL_POOL") and
 * traps are "trap:" + a BwRules.Trap name ("trap:ITS_A_TRAP"). Every buy attempt is logged (BwBotLog).
 */
public interface BotShoppingInterface {
    /** Gear tiers: armor 0 leather, 1 chain, 2 iron, 3 diamond; sword 0 wood..3 diamond; tools 0 none..4. */
    record Gear(int armor, int sword, int pickaxe, int axe, boolean shears) {}

    /** Who this is (for logs). */
    String who();

    /** {iron, gold, diamonds, emeralds} carried right now. */
    int[] balances();

    Gear gear();

    /** Team upgrade levels by upgrade name, plus "TRAPS" = traps queued. */
    Map<String, Integer> teamUpgrades();

    /** {currency, cost} of an offer for this buyer right now (tools by level, mode prices), or null if they can't buy it. */
    int[] price(String offerId);

    /** Tries to buy an item. True = bought. */
    boolean buy(String offerId);

    /** Tries to buy the next level of a team upgrade, or a trap ("trap:NAME"). True = bought. */
    boolean upgrade(String upgradeOrTrap);

    /** Why the last buy/upgrade failed ("" after a success). */
    String lastResult();
}
