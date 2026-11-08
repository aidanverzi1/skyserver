package com.aidan.skyserver.lobby;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;

/**
 * /pass: the battle pass as a chest screen. 9 tiers per page: the top row shows the tier, the
 * middle row the free reward, the bottom row the Captain's Pass reward. Rewards are given
 * automatically when a tier is reached, so this screen is for looking (and buying the pass).
 */
public final class PassMenu extends LockedMenu {
    private static final int PER_PAGE = 9, PAGES = (Progression.TIERS + PER_PAGE - 1) / PER_PAGE;
    private static final int PREV = 45, CHALLENGES = 48, BUY = 49, COSMETICS = 50, NEXT = 53;
    private final int page;

    private PassMenu(int id, Inventory inv, SimpleContainer box, ServerPlayer viewer, int page) {
        super(id, inv, box, 6, viewer);
        this.page = page;
        draw();
    }

    /** page -1 = the page with your current tier. */
    public static void open(ServerPlayer p, int page) {
        PlayerProfile prof = PlayerCache.get(p);
        int tier = BattlePass.tierOf(prof.passSeason == BattlePass.season() ? prof.passXp : 0);
        int pg = page >= 0 ? page : Math.min(PAGES - 1, Math.max(0, (tier) / PER_PAGE));
        final int fp = Math.max(0, Math.min(PAGES - 1, pg));
        LockedMenu.open(p, "§8Season " + BattlePass.season() + " Pass - page " + (fp + 1) + "/" + PAGES, 6,
                (id, inv, box, v) -> new PassMenu(id, inv, box, v, fp));
    }

    private void draw() {
        box.clearContent();
        int season = BattlePass.season();
        PlayerProfile prof = PlayerCache.get(viewer);
        long xp = prof.passSeason == season ? prof.passXp : 0;
        boolean premium = prof.passSeason == season && prof.premium;
        int tier = BattlePass.tierOf(xp);
        long into = tier >= Progression.TIERS ? 0 : xp - (long) tier * Progression.TIER_XP;

        box.setItem(4, Stacks.named(new ItemStack(Items.NETHER_STAR), "§6§lSeason " + season + ": " + Progression.seasonName(season),
                "§7Tier §e" + tier + "§7/" + Progression.TIERS + (tier < Progression.TIERS ? " §8(" + into + "/" + Progression.TIER_XP + " pass XP)" : " §a(complete!)"),
                "§7" + BattlePass.daysLeft() + " days left in the season.",
                "§7Earn pass XP from weekly challenges,", "§7daily quests and playing anything.",
                "§7Rewards are given automatically."));
        for (int i = 0; i < PER_PAGE; i++) {
            int t = page * PER_PAGE + i + 1;
            if (t > Progression.TIERS) break;
            boolean reached = t <= tier;
            box.setItem(9 + i, Stacks.named(new ItemStack(reached ? Items.STAINED_GLASS_PANE.pick(DyeColor.LIME) : Items.STAINED_GLASS_PANE.pick(DyeColor.GRAY), Math.min(64, t)),
                    (reached ? "§a" : "§7") + "Tier " + t, reached ? "§aReached!" : "§7" + String.format("%,d", (long) t * Progression.TIER_XP - xp) + " pass XP to go"));
            Progression.Reward free = Progression.freeReward(season, t);
            List<String> fl = new ArrayList<>(List.of("§7Free track", BattlePass.rewardText(free)));
            if (t == Progression.TIERS) fl.add(Keys.COLORS[Progression.FREE_FINAL_KEY] + Keys.NAMES[Progression.FREE_FINAL_KEY] + " Key");
            fl.add(reached ? "§aGiven" : "§7Locked");
            box.setItem(18 + i, Stacks.named(new ItemStack(icon(free)), (reached ? "§a" : "§f") + "Tier " + t + " - free", fl.toArray(new String[0])));
            Progression.Reward prem = Progression.premiumReward(season, t);
            List<String> pl = new ArrayList<>(List.of("§6Captain's Pass", BattlePass.rewardText(prem)));
            if (t == Progression.TIERS) pl.add(Keys.COLORS[Progression.PREMIUM_FINAL_KEY] + Keys.NAMES[Progression.PREMIUM_FINAL_KEY] + " Key");
            pl.add(!premium ? "§cNeeds the Captain's Pass" : reached ? "§aGiven" : "§7Locked");
            box.setItem(27 + i, Stacks.named(new ItemStack(icon(prem)), (premium && reached ? "§a" : "§6") + "Tier " + t + " - Captain's Pass", pl.toArray(new String[0])));
        }
        if (page > 0) box.setItem(PREV, Stacks.named(new ItemStack(Items.ARROW), "§ePrevious page"));
        if (page < PAGES - 1) box.setItem(NEXT, Stacks.named(new ItemStack(Items.ARROW), "§eNext page"));
        box.setItem(CHALLENGES, Stacks.named(new ItemStack(Items.WRITABLE_BOOK), "§e§lWeekly Challenges",
                "§7Week " + (BattlePass.weekNow() + 1) + "/" + Progression.WEEKS + " - new ones in " + BattlePass.daysToNextWeek() + " days",
                "§eClick to list them in chat"));
        box.setItem(BUY, premium
                ? Stacks.named(new ItemStack(Items.GOLDEN_HELMET), "§6§lCaptain's Pass", "§aYou have it this season!")
                : Stacks.named(new ItemStack(Items.GOLDEN_HELMET), "§6§lBuy the Captain's Pass", "§7" + Economy.format(Progression.PREMIUM_PRICE) + " in-game money",
                "§7A second reward on every tier,", "§7also for tiers you've reached.", "§7Tier 50: an exclusive title", "§7and a Legendary Key.", "§eClick to buy"));
        box.setItem(COSMETICS, Stacks.named(new ItemStack(Items.ARMOR_STAND), "§d§lYour Cosmetics", "§7Titles and trails you've won.", "§eClick to list them"));
        frame(Stacks.named(new ItemStack(Items.STAINED_GLASS_PANE.pick(DyeColor.BLACK)), " "));
        broadcastChanges();
    }

    private static Item icon(Progression.Reward r) {
        return switch (r.kind()) {
            case MONEY -> r.value() >= 900 ? Items.GOLD_BLOCK : Items.GOLD_INGOT;
            case KEY -> Items.TRIPWIRE_HOOK;
            case COSMETIC -> Progression.isTitle(r.cosmetic()) ? Items.NAME_TAG : Items.BLAZE_POWDER;
            default -> Items.BARRIER;
        };
    }

    @Override
    protected void onClick(int slot, int button, boolean shift) {
        switch (slot) {
            case PREV -> { if (page > 0) Lobby.later(viewer, () -> open(viewer, page - 1)); }
            case NEXT -> { if (page < PAGES - 1) Lobby.later(viewer, () -> open(viewer, page + 1)); }
            case CHALLENGES -> { viewer.closeContainer(); BattlePass.showChallenges(viewer); }
            case COSMETICS -> { viewer.closeContainer(); BattlePass.showCosmetics(viewer); }
            case BUY -> { viewer.closeContainer(); BattlePass.buy(viewer, false); }
            default -> {}
        }
    }
}
