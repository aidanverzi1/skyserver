package com.aidan.skyserver.bedwars;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.EnumMap;
import java.util.Map;

/** A team's bed, upgrades and traps for one match. */
final class BwTeamState {
    final BwTeam team;
    boolean bed = true;
    boolean out = false;          // eliminated
    int place = 0;                // finishing place (1 = winner), set when knocked out
    final Map<BwRules.Upgrade, Integer> upgrades = new EnumMap<>(BwRules.Upgrade.class);
    final Deque<BwRules.Trap> traps = new ArrayDeque<>();
    boolean blanketBought = false;
    boolean bedBell = false;
    long lastBell = -1000;
    long blanketUntil = 0;
    long lastAlarm = -1000, lastTrap = -1000;
    long ironAt = 0, goldAt = 0, emeraldAt = 0;
    int finals = 0;
    /** Plan the team's bots are following (index into BwStrategy.Plan), -1 = none. */
    int botPlan = -1;

    BwTeamState(BwTeam team) {
        this.team = team;
    }

    int level(BwRules.Upgrade u) {
        return upgrades.getOrDefault(u, 0);
    }
}
