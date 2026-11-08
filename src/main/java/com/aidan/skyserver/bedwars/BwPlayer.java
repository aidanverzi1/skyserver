package com.aidan.skyserver.bedwars;

import com.aidan.skyserver.skywars.PlayerSnapshot;
import net.minecraft.world.SimpleContainer;

import java.util.UUID;

/** One person (or bot) in a Bedwars match. */
final class BwPlayer {
    PlayerSnapshot snapshot;
    final long joinOrder;
    final boolean bot;
    BwTeam team;
    boolean offline = false;      // real player who disconnected mid-match (can rejoin while the bed stands)
    long offlineSince = 0;
    boolean eliminated = false;   // final-killed: watching until the match ends
    long respawnAt = -1;          // tick to respawn at, -1 = alive (or eliminated)
    long invulnUntil = 0;
    long noFallUntil = 0;         // spring pads, grapples and rescue clouds cancel fall damage for a moment
    long trapImmuneUntil = 0;     // Magic Milk
    int kills = 0, finals = 0, beds = 0, deaths = 0;
    /** Gear kept between lives: armor 0 leather..3 diamond, tools 0 none..4, shears. Sword resets to wood on death. */
    int armor = 0, sword = 0, pick = 0, axe = 0;
    boolean shears = false;
    UUID lastAttacker = null;
    long lastAttackTick = 0;
    boolean active = false;       // did anything this match (no pay for AFK)
    final SimpleContainer ender = new SimpleContainer(27);

    BwPlayer(PlayerSnapshot snapshot, long joinOrder, BwTeam team, boolean bot) {
        this.snapshot = snapshot;
        this.joinOrder = joinOrder;
        this.team = team;
        this.bot = bot;
    }

    boolean alive() {
        return respawnAt < 0 && !eliminated && !offline;
    }
}
