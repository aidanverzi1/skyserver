package com.aidan.skyserver.island;

import com.aidan.skyserver.lobby.Economy;
import com.aidan.skyserver.lobby.Keys;
import com.aidan.skyserver.lobby.Lobby;
import com.aidan.skyserver.lobby.LockedMenu;
import com.aidan.skyserver.lobby.Stacks;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** Island challenges, one page per tier (tabs along the bottom). Click a challenge to hand in the items. */
public final class ChallengeMenu extends LockedMenu {
    private static final int TAB_FIRST = 46; // 46..49
    private final int tier;
    private final List<Challenges.Challenge> shown = new ArrayList<>();

    private ChallengeMenu(int id, Inventory inv, SimpleContainer box, ServerPlayer viewer, int tier) {
        super(id, inv, box, 6, viewer);
        this.tier = tier;
        draw();
    }

    public static void open(ServerPlayer p, int tier) {
        LockedMenu.open(p, "§8Challenges - " + Challenges.TIER_NAMES[tier], 6, (id, inv, box, v) -> new ChallengeMenu(id, inv, box, v, tier));
    }

    private void draw() {
        box.clearContent();
        shown.clear();
        Islands.Island is = Islands.islandOf(viewer.getUUID());
        if (is == null) return;
        int level = is.level();
        int unlocked = Challenges.unlockedTier(level);
        String today = LocalDate.now().toString();
        int slot = 10;
        for (Challenges.Challenge ch : Challenges.ALL) {
            if (ch.tier() != tier) continue;
            boolean done = is.completed.contains(ch.id());
            boolean locked = tier > unlocked;
            List<String> lore = new ArrayList<>();
            if (ch.isLevelGoal()) lore.add("§7Reach server level §f" + ch.islandLevel() + " §7(island best: " + level + ")");
            else for (Challenges.Need n : ch.needs()) lore.add("§7Bring: §f" + n.label());
            lore.add("§7Reward: §6" + Economy.format(ch.money()) + " §7+ §b" + ch.xp() + " XP"
                    + (ch.keyTier() >= 0 ? " §7+ " + Keys.COLORS[ch.keyTier()] + Keys.NAMES[ch.keyTier()] + " Key" : ""));
            if (locked) lore.add("§cUnlocks at server level " + Challenges.TIER_LEVEL[tier]);
            else if (done && ch.isLevelGoal()) lore.add("§aCompleted!");
            else if (done) lore.add(today.equals(is.repeatedOn.get(ch.id())) ? "§aCompleted §7(repeated today)" : "§aCompleted §7- repeat today for §6" + Economy.format(ch.money() / 4));
            else lore.add("§eClick to complete");
            ItemStack icon = new ItemStack(locked ? Items.DYE.pick(DyeColor.GRAY) : done ? Items.DYE.pick(DyeColor.LIME) : ch.icon());
            box.setItem(slot, Stacks.named(icon, (done ? "§a" : locked ? "§8" : "§e") + ch.name(), lore.toArray(new String[0])));
            shown.add(ch);
            slot++;
            if (slot % 9 == 8) slot += 2;
            if (slot >= 44) break;
        }
        for (int t = 0; t < Challenges.TIER_NAMES.length; t++) {
            boolean open = t <= unlocked;
            box.setItem(TAB_FIRST + t, Stacks.named(new ItemStack(t == tier ? Items.ENCHANTED_BOOK : open ? Items.BOOK : Items.BARRIER),
                    (t == tier ? "§6§l" : open ? "§e" : "§8") + Challenges.TIER_NAMES[t],
                    open ? "§7Click to view" : "§cServer level " + Challenges.TIER_LEVEL[t] + " needed",
                    "§7(the highest level of anyone on your island)"));
        }
        frame(Stacks.named(new ItemStack(Items.STAINED_GLASS_PANE.pick(DyeColor.PURPLE)), " "));
        broadcastChanges();
    }

    @Override
    protected void onClick(int slot, int button, boolean shift) {
        if (slot >= TAB_FIRST && slot < TAB_FIRST + Challenges.TIER_NAMES.length) {
            int t = slot - TAB_FIRST;
            if (t != tier) Lobby.later(viewer, () -> open(viewer, t));
            return;
        }
        int idx = 0;
        for (int s = 10; s < 44; s++) {
            if (s % 9 == 0 || s % 9 == 8) continue;
            if (s == slot) break;
            idx++;
        }
        if (slot < 10 || slot >= 44 || slot % 9 == 0 || slot % 9 == 8 || idx >= shown.size()) return;
        Islands.Island is = Islands.islandOf(viewer.getUUID());
        if (is == null) return;
        Lobby.msg(viewer, Challenges.complete(viewer, is, shown.get(idx)));
        draw();
    }
}
