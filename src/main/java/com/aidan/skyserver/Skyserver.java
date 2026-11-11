package com.aidan.skyserver;

import com.aidan.skyserver.bots.BotManager;
import com.aidan.skyserver.goldwars.GoldWarsGame;
import com.aidan.skyserver.skywars.SkywarsGame;
import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Skyserver implements ModInitializer {
    public static final String MOD_ID = "skyserver";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        com.aidan.skyserver.bots.record.PlayerRecorder.init(); // first: watches matches (records only, changes nothing)
        com.aidan.skyserver.expedition.Expedition.init(); // 5.8: before Lobby, so a logout in the Nether drops/moves items before anything saves
        com.aidan.skyserver.lobby.Lobby.init(); // so it sees logins/logouts before the games do
        com.aidan.skyserver.lobby.Mods.init();            // 5.9: moderators (/mod, /sc, /ss mod), temp bans
        com.aidan.skyserver.island.Islands.init(); // (before PlayerCache so its last save at shutdown goes in first)
        WorldTweaks.init();                               // 5.7: no advancements, all recipes, no crop trampling
        com.aidan.skyserver.lobby.Levels.init();          // 5.7: server level 1-99 + prestige
        com.aidan.skyserver.lobby.PlayerCache.init();     // 5.7: in-memory player data, saved in the background
        com.aidan.skyserver.lobby.BattlePass.init();      // 5.7: battle pass, weekly challenges, cosmetics
        com.aidan.skyserver.lobby.CosmeticsPlus.init();   // 5.13: cages, bed-break effects, name colours (after BattlePass: wraps /cosmetics)
        com.aidan.skyserver.lobby.Ranks.init();           // 5.7: level/prestige tags, titles, trails (5.9: [MOD] tag)
        com.aidan.skyserver.lobby.EconomyReport.init();   // 5.7: /ss economy
        com.aidan.skyserver.lobby.Boosters.init();        // 5.8: /boosters
        com.aidan.skyserver.lobby.ShardShop.init();       // 5.8: /shards
        com.aidan.skyserver.lobby.CosmeticShop.init();    // 5.8: /cshop
        com.aidan.skyserver.lobby.Voyage.init();          // 5.13: Wren's Weekly Voyage (/voyage)
        com.aidan.skyserver.lobby.Playtime.init();        // 5.13: playtime / usage numbers (/ss usage)
        com.aidan.skyserver.lobby.Ready.init();           // 5.13: /ready (start now with bots)
        com.aidan.skyserver.lobby.CrateOdds.init();       // 5.13: left-click a mystery chest to see the odds
        com.aidan.skyserver.goldwars.FireballDamage.init(); // 5.9: fireballs hurt players 50% more in Skywars / Gold Wars (Bedwars: BwItems)
        Longbow.init();
        DamageCredit.init();
        BotManager.init();
        SkywarsGame.init();
        com.aidan.skyserver.skywars.SwVoyage.init();      // 5.13: Voyage twists for Skywars (after SkywarsGame)
        GoldWarsGame.init();
        com.aidan.skyserver.goldwars.GwVoyage.init();     // 5.13: Class Roulette (after GoldWarsGame)
        com.aidan.skyserver.expedition.ExpVoyage.init();  // 5.13: Wren's Bounty
        com.aidan.skyserver.bedwars.BedwarsGame.init();
        com.aidan.skyserver.bedwars.BwVoyage.init();      // 5.13: Voyage twists for Bedwars (after BedwarsGame, before BwForge)
        com.aidan.skyserver.bedwars.BwForge.init();       // 5.12: shared team forges (must come after BedwarsGame)
        LOGGER.info("SkyServer loaded!");
    }
}
