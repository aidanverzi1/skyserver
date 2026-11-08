package com.aidan.skyserver.bedwars;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

import java.util.*;

/** What the Bedwars bots (in the bots package) may see and do. */
public final class BedwarsBots {
    private BedwarsBots() {}

    public static boolean inMatch() {
        return BedwarsGame.playing();
    }

    public static boolean alive(UUID id) {
        return BedwarsGame.alive(id);
    }

    public static BwTeam team(UUID id) {
        return BedwarsGame.teamOf(id);
    }

    public static ServerLevel level() {
        return BedwarsGame.level();
    }

    public static int voidY() {
        return BwMap.voidY();
    }

    public static int floorY() {
        return BwMap.OY;
    }

    public static String mapId() {
        return BwMap.id();
    }

    public static int matchNumber() {
        return BedwarsGame.matchNumber();
    }

    public static long matchTicks() {
        return BedwarsGame.matchTicks();
    }

    public static boolean suddenDeath() {
        return BedwarsGame.matchTicks() >= BwRules.Event.SUDDEN_DEATH.tick();
    }

    private static BlockPos at(int[][] arr, BwTeam t) {
        return BwMap.pos(arr[t.ordinal()]);
    }

    public static BlockPos spawn(BwTeam t) { return at(BwMap.data().spawn, t); }
    public static BlockPos bedFoot(BwTeam t) { return at(BwMap.data().bedFoot, t); }
    public static BlockPos bedHead(BwTeam t) { return at(BwMap.data().bedHead, t); }
    public static BlockPos gen(BwTeam t) { return at(BwMap.data().gen, t); }
    public static BlockPos shop(BwTeam t) { return at(BwMap.data().shop, t); }
    public static BlockPos upgrades(BwTeam t) { return at(BwMap.data().upgrades, t); }
    public static BlockPos base(BwTeam t) { return at(BwMap.data().base, t); }
    public static BlockPos teamChest(BwTeam t) { return at(BwMap.data().teamChest, t); }

    public static List<BlockPos> diamondGens() {
        List<BlockPos> l = new ArrayList<>();
        for (int[] g : BwMap.data().diamondGens) l.add(BwMap.pos(g));
        return l;
    }

    public static List<BlockPos> emeraldGens() {
        List<BlockPos> l = new ArrayList<>();
        for (int[] g : BwMap.data().emeraldGens) l.add(BwMap.pos(g));
        return l;
    }

    public static boolean bedAlive(BwTeam t) {
        return BedwarsGame.bedAlive(t);
    }

    public static boolean out(BwTeam t) {
        BwTeamState ts = BedwarsGame.teams.get(t);
        return ts == null || ts.out;
    }

    public static boolean blanketed(BwTeam t) {
        BwTeamState ts = BedwarsGame.teams.get(t);
        return ts != null && BedwarsGame.now() < ts.blanketUntil;
    }

    /** 5.9: the team's Bed Bell, a trap or the bed alarm went off in the last 5 seconds. */
    public static boolean bedAlertRecently(BwTeam t) {
        BwTeamState ts = BedwarsGame.teams.get(t);
        if (ts == null) return false;
        long now = BedwarsGame.now();
        return now - ts.lastBell < 100 || now - ts.lastTrap < 100 || now - ts.lastAlarm < 100;
    }

    public static List<ServerPlayer> alivePlayers() {
        return BedwarsGame.alivePlayers();
    }

    public static List<UUID> members(BwTeam t) {
        return BedwarsGame.members(t);
    }

    public static boolean isBot(UUID id) {
        BwPlayer bp = BedwarsGame.bp(id);
        return bp != null && bp.bot;
    }

    public static boolean canPlace(BlockPos p) {
        return BedwarsGame.canPlace(p);
    }

    public static boolean isPlaced(BlockPos p) {
        return BedwarsGame.isPlaced(p);
    }

    /** Bots may break placed blocks and enemy beds (not their own bed, not a blanketed one). */
    public static boolean canBreak(BlockPos p, BwTeam me) {
        if (BedwarsGame.isPlaced(p)) return true;
        BwTeam owner = BedwarsGame.bedOwner(p);
        return owner != null && owner != me && bedAlive(owner) && !blanketed(owner);
    }

    /** The programmatic shop for a bot (balances, gear, upgrades, buying): see BotShoppingInterface. */
    public static BotShoppingInterface shop(ServerPlayer p) {
        return new LiveShop(p);
    }

    public static BwRules.Mode mode() {
        return BwRules.mode;
    }

    public static int[] resources(ServerPlayer p) {
        return BwShop.resources(p);
    }

    public static String buy(ServerPlayer p, String id) {
        return BwShop.buy(p, id);
    }

    public static String buyUpgrade(ServerPlayer p, String up) {
        if (up.startsWith("trap:")) return BwUpgrades.buyTrap(p, BwRules.Trap.valueOf(up.substring(5)));
        return BwUpgrades.buyUpgrade(p, BwRules.Upgrade.valueOf(up));
    }

    /** Current level of every upgrade (BwRules.Upgrade order). */
    public static int[] upgradeLevels(BwTeam t) {
        BwTeamState ts = BedwarsGame.teams.get(t);
        int[] l = new int[BwRules.Upgrade.values().length];
        for (BwRules.Upgrade u : BwRules.Upgrade.values()) l[u.ordinal()] = ts == null ? 0 : ts.level(u);
        return l;
    }

    public static boolean bedBell(BwTeam t) {
        BwTeamState ts = BedwarsGame.teams.get(t);
        return ts != null && ts.bedBell;
    }

    public static int traps(BwTeam t) {
        BwTeamState ts = BedwarsGame.teams.get(t);
        return ts == null ? 0 : ts.traps.size();
    }

    /** {armor, sword, pickaxe, axe, shears 0/1} */
    public static int[] gear(UUID id) {
        BwPlayer bp = BedwarsGame.bp(id);
        if (bp == null) return new int[5];
        return new int[]{bp.armor, bp.sword, bp.pick, bp.axe, bp.shears ? 1 : 0};
    }

    /** The plan a team's bots follow this match (-1 = not chosen yet). */
    public static int plan(BwTeam t) {
        BwTeamState ts = BedwarsGame.teams.get(t);
        return ts == null ? -1 : ts.botPlan;
    }

    public static void setPlan(BwTeam t, int plan) {
        BwTeamState ts = BedwarsGame.teams.get(t);
        if (ts != null) ts.botPlan = plan;
    }

    public static boolean isEnemyBed(BlockPos p, BwTeam me) {
        BwTeam owner = BedwarsGame.bedOwner(p);
        return owner != null && owner != me;
    }

    // =====================================================================
    // The team chest (bots bank spare resources and take what they need, like real players)
    // =====================================================================

    private static Container chest(BwTeam t) {
        ServerLevel l = level();
        if (l == null) return null;
        return l.getBlockEntity(teamChest(t)) instanceof Container c ? c : null;
    }

    /** Iron, gold, diamonds, emeralds in a team's chest. */
    public static int[] chestResources(BwTeam t) {
        int[] r = new int[4];
        Container c = chest(t);
        if (c == null) return r;
        for (int i = 0; i < c.getContainerSize(); i++) {
            int k = BedwarsGame.resource(c.getItem(i));
            if (k >= 0) r[k] += c.getItem(i).getCount();
        }
        return r;
    }

    /** Moves up to amount of one resource (0 iron, 1 gold, 2 diamonds, 3 emeralds) into the team chest. Returns how many moved. */
    public static int deposit(ServerPlayer p, BwTeam t, int currency, int amount) {
        Container c = chest(t);
        if (c == null || amount <= 0) return 0;
        Inventory inv = p.getInventory();
        int left = amount;
        for (int i = 0; i < inv.getContainerSize() && left > 0; i++) {
            ItemStack s = inv.getItem(i);
            if (s.isEmpty() || BedwarsGame.resource(s) != currency) continue;
            int n = Math.min(left, s.getCount());
            ItemStack part = s.copyWithCount(n);
            insert(c, part);
            int moved = n - part.getCount();
            s.shrink(moved);
            left -= moved;
            if (moved < n) break; // chest full
        }
        c.setChanged();
        return amount - left;
    }

    /** Moves up to amount of one resource from the team chest into the player's inventory. Returns how many moved. */
    public static int withdraw(ServerPlayer p, BwTeam t, int currency, int amount) {
        Container c = chest(t);
        if (c == null || amount <= 0) return 0;
        int left = amount;
        for (int i = c.getContainerSize() - 1; i >= 0 && left > 0; i--) {
            ItemStack s = c.getItem(i);
            if (s.isEmpty() || BedwarsGame.resource(s) != currency) continue;
            int n = Math.min(left, s.getCount());
            ItemStack part = s.copyWithCount(n);
            p.getInventory().add(part); // shrinks part by what fitted
            int moved = n - part.getCount();
            s.shrink(moved);
            left -= moved;
            if (!part.isEmpty()) break; // inventory full
        }
        c.setChanged();
        return amount - left;
    }

    /** Puts a stack into a container (topping up matching stacks first). Whatever doesn't fit stays in the stack. */
    private static void insert(Container c, ItemStack stack) {
        int max = Math.min(stack.getMaxStackSize(), c.getMaxStackSize());
        for (int i = 0; i < c.getContainerSize() && !stack.isEmpty(); i++) {
            ItemStack in = c.getItem(i);
            if (in.isEmpty() || !ItemStack.isSameItemSameComponents(in, stack)) continue;
            int n = Math.min(max - in.getCount(), stack.getCount());
            if (n <= 0) continue;
            in.grow(n);
            stack.shrink(n);
        }
        for (int i = 0; i < c.getContainerSize() && !stack.isEmpty(); i++) {
            if (!c.getItem(i).isEmpty()) continue;
            int n = Math.min(max, stack.getCount());
            c.setItem(i, stack.copyWithCount(n));
            stack.shrink(n);
        }
    }
}
