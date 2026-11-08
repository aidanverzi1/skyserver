package com.aidan.skyserver.bedwars;

import net.minecraft.server.level.ServerPlayer;

import java.util.LinkedHashMap;
import java.util.Map;

/** The server's BotShoppingInterface: a thin wrapper over BwShop / BwUpgrades for one player or bot. */
final class LiveShop implements BotShoppingInterface {
    private final ServerPlayer p;
    private String last = "";

    LiveShop(ServerPlayer p) {
        this.p = p;
    }

    @Override
    public String who() {
        return p.getName().getString();
    }

    @Override
    public int[] balances() {
        return BwShop.resources(p);
    }

    @Override
    public Gear gear() {
        BwPlayer bp = BedwarsGame.bp(p.getUUID());
        if (bp == null) return new Gear(0, 0, 0, 0, false);
        return new Gear(bp.armor, bp.sword, bp.pick, bp.axe, bp.shears);
    }

    @Override
    public Map<String, Integer> teamUpgrades() {
        Map<String, Integer> m = new LinkedHashMap<>();
        BwPlayer bp = BedwarsGame.bp(p.getUUID());
        BwTeamState ts = bp == null ? null : BedwarsGame.teams.get(bp.team);
        for (BwRules.Upgrade u : BwRules.Upgrade.values()) m.put(u.name(), ts == null ? 0 : ts.level(u));
        m.put("TRAPS", ts == null ? 0 : ts.traps.size());
        return m;
    }

    @Override
    public int[] price(String offerId) {
        return BwShop.price(p, offerId);
    }

    @Override
    public boolean buy(String offerId) {
        String r = BwShop.buy(p, offerId);
        last = r == null ? "" : r;
        return r == null;
    }

    @Override
    public boolean upgrade(String up) {
        String r;
        try {
            r = up.startsWith("trap:") ? BwUpgrades.buyTrap(p, BwRules.Trap.valueOf(up.substring(5))) : BwUpgrades.buyUpgrade(p, BwRules.Upgrade.valueOf(up));
        } catch (IllegalArgumentException e) {
            r = "No upgrade called " + up;
        }
        last = r == null ? "" : r;
        return r == null;
    }

    @Override
    public String lastResult() {
        return last;
    }
}
