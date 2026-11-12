package com.aidan.skyserver.lobby;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Items;

import java.util.*;

/**
 * The five mystery chests in the Great Hall. Right-click with a key to open one: prizes scroll
 * past in a chest screen, slow down and stop on what you won. Without a key, right-clicking shows
 * every prize and its chance. Items go to your Sky Island inventory (delivered on /is, or /claim), money and keys
 * are given right away. Epic and Legendary wins are announced to the whole server.
 *
 * 5.8: every opening gives Key Shards (ShardShop.OPEN_SHARDS). When the prize is an item you can
 * KEEP it or SALVAGE it for more shards (closing the screen keeps it). Token prizes (boosters,
 * cosmetics, vouchers, tickets...) are handed out by Tokens.
 */
public final class Crates {
    private static final Random RNG = new Random();

    private Crates() {}

    /** Which crate (tier) is at this block, or -1. */
    public static int crateAt(BlockPos pos) {
        for (LobbyDesign.Crate c : LobbyDesign.CRATES) if (c.x() == pos.getX() && c.y() == pos.getY() && c.z() == pos.getZ()) return c.tier();
        return -1;
    }

    public static void use(ServerPlayer p, int tier) {
        if (Keys.count(p.getUUID(), tier) <= 0) {
            preview(p, tier);
            Lobby.msg(p, "§7You don't have a " + Keys.COLORS[tier] + Keys.NAMES[tier] + " Key§7. Get keys from your daily streak, obstacle courses, wins, the key shop or §d/shards§7.");
            return;
        }
        if (!Keys.take(p.getUUID(), tier, 1)) return;
        Lobby.refreshKit(p);
        Loot.Prize prize = Loot.roll(tier, RNG);
        Levels.addXp(p.level().getServer(), p, Rewards.CHEST_OPEN_XP);
        Quests.progress(p, "crate", 1);
        PlayerStats.add(p, "crate", 1);
        Tokens.add(p.getUUID(), Tokens.SHARDS, ShardShop.OPEN_SHARDS[tier]);
        LockedMenu.open(p, Keys.COLORS[tier] + Keys.NAMES[tier] + " Chest", 3, (id, inv, box, viewer) -> new SpinMenu(id, inv, box, viewer, tier, prize));
    }

    public static void preview(ServerPlayer p, int tier) {
        List<Loot.Prize> table = Loot.TABLES.get(tier);
        int rows = Math.min(6, (table.size() + 8) / 9 + 1);
        LockedMenu.open(p, Keys.COLORS[tier] + Keys.NAMES[tier] + " Chest prizes", rows, (id, inv, box, viewer) -> {
            LockedMenu m = new LockedMenu(id, inv, box, rows, viewer);
            MinecraftServer server = viewer.level().getServer();
            for (int i = 0; i < table.size() && i < box.getContainerSize() - 9; i++) {
                Loot.Prize pr = table.get(i);
                ItemStack icon = pr.display(server).copy();
                Stacks.named(icon, "§f" + stripColor(pr.label()), String.format("§7Chance: §e%.1f%%", Loot.chance(tier, pr)));
                box.setItem(i, icon);
            }
            box.setItem(box.getContainerSize() - 5, Stacks.named(new ItemStack(Items.TRIPWIRE_HOOK), Keys.COLORS[tier] + "Your " + Keys.NAMES[tier] + " Keys: §f" + Keys.count(viewer.getUUID(), tier),
                    "§7Every opening also gives §d" + ShardShop.OPEN_SHARDS[tier] + " Key Shards§7.", "§7Item prizes can be salvaged for §d" + ShardShop.SALVAGE_SHARDS[tier] + "§7 more."));
            return m;
        });
    }

    private static String stripColor(String s) {
        return s.replaceAll("§.", "");
    }

    /** Hands out a prize. salvage = item prize turned into shards instead (key chests only). */
    static void award(ServerPlayer p, int tier, Loot.Prize prize, boolean salvage) {
        MinecraftServer server = p.level().getServer();
        String label = prize.label(), where = "";
        switch (prize.kind()) {
            case MONEY -> Economy.add(server, p.getUUID(), p.getName().getString(), prize.money());
            case KEYS -> Keys.give(p.getUUID(), prize.keyTier(), prize.keyCount());
            case ITEM -> {
                if (salvage) {
                    int n = ShardShop.SALVAGE_SHARDS[Math.max(0, Math.min(4, tier))];
                    Tokens.add(p.getUUID(), Tokens.SHARDS, n);
                    label = "§d" + n + " Key Shards §7(salvaged " + stripColor(prize.label()) + ")";
                } else {
                    Mailbox.send(server, p.getUUID(), prize.item().apply(server));
                    for (ItemStack extra : Loot.extras(prize, server)) Mailbox.send(server, p.getUUID(), extra);
                    where = " §7(delivered when you go to your Sky Island: §e/is§7)";
                }
            }
            case TOKEN -> label = Tokens.grant(p, prize.token());
        }
        Lobby.msg(p, "§aYou won: §f" + label + where);
        Lobby.refreshKit(p);
        p.level().playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 1f, 1f);
        if (tier >= 3 && !salvage) {
            Component msg = Component.literal("§6§l✦ §e" + p.getName().getString() + " §7opened a " + Keys.COLORS[tier] + "§l" + Keys.NAMES[tier]
                    + " Chest §7and won §f" + prize.label() + "§7!");
            server.getPlayerList().broadcastSystemMessage(msg, false);
        }
    }

    /** The spinning prize screen. */
    static final class SpinMenu extends LockedMenu {
        private static final int KEEP = 11, SALVAGE = 15;
        private final int tier;
        private final Loot.Prize prize;
        private final String color;
        private final boolean keyChest;
        private final List<Loot.Prize> reel = new ArrayList<>();
        private int step = 0, wait = 0;
        private boolean done = false, choosing = false, awarded = false;
        private static final int STEPS = 32;

        SpinMenu(int id, Inventory inv, SimpleContainer box, ServerPlayer viewer, int tier, Loot.Prize prize) {
            this(id, inv, box, viewer, tier, prize, null, Keys.COLORS[tier]);
        }

        /** pool = the prizes that scroll past (null = the key chest of this tier). */
        SpinMenu(int id, Inventory inv, SimpleContainer box, ServerPlayer viewer, int tier, Loot.Prize prize, List<Loot.Prize> pool, String color) {
            super(id, inv, box, 3, viewer);
            this.tier = tier;
            this.prize = prize;
            this.color = color;
            this.keyChest = pool == null;
            // Random prizes scroll past; the winning one lands in the middle slot on the last step.
            for (int i = 0; i < STEPS + 9; i++) reel.add(pool == null ? Loot.roll(tier, RNG) : rollFrom(pool, RNG));
            reel.set(STEPS + 4, prize);
            ItemStack glass = Stacks.named(new ItemStack(Items.STAINED_GLASS_PANE.pick(DyeColor.BLACK)), " ");
            for (int i = 0; i < 27; i++) box.setItem(i, glass.copy());
            ItemStack pointer = Stacks.named(new ItemStack(Items.HOPPER), color + "▼");
            box.setItem(4, pointer);
            box.setItem(22, Stacks.named(new ItemStack(Items.END_ROD), color + "▲"));
            draw();
            SPINNING.add(this);
        }

        private void draw() {
            MinecraftServer server = viewer.level().getServer();
            for (int i = 0; i < 9; i++) box.setItem(9 + i, reel.get(step + i).display(server));
            broadcastChanges();
        }

        /** Called every tick while open. Slows down as it goes. */
        boolean tick() {
            if (done) return false;
            if (--wait > 0) return true;
            step++;
            wait = step < 18 ? 2 : step < 26 ? 4 : step < 30 ? 7 : 11;
            draw();
            viewer.level().playSound(null, viewer.getX(), viewer.getY(), viewer.getZ(), SoundEvents.UI_BUTTON_CLICK, SoundSource.PLAYERS, 0.4f, 1.2f + step * 0.02f);
            if (step >= STEPS) {
                finish();
                return false;
            }
            return true;
        }

        private void finish() {
            if (done) return;
            done = true;
            ItemStack glass = Stacks.named(new ItemStack(Items.STAINED_GLASS_PANE.pick(DyeColor.LIME)), " ");
            for (int i = 0; i < 27; i++) if (i != 13) box.setItem(i, glass.copy());
            box.setItem(13, prize.display(viewer.level().getServer()).copy());
            if (keyChest && prize.kind() == Loot.Kind.ITEM) {
                // Keep it or turn it into shards.
                choosing = true;
                int n = ShardShop.SALVAGE_SHARDS[Math.max(0, Math.min(4, tier))];
                box.setItem(KEEP, Stacks.named(new ItemStack(Items.EMERALD), "§a§lKEEP IT",
                        "§7Sent to your Sky Island (§e/is§7)."));
                box.setItem(SALVAGE, Stacks.named(new ItemStack(Items.AMETHYST_SHARD), "§d§lSALVAGE for " + n + " Key Shards",
                        "§7Spend shards in §e/shards§7 on the", "§7key or cosmetic you want."));
                Lobby.msg(viewer, "§7Keep the prize or salvage it for shards? §8(closing keeps it)");
            } else {
                give(false);
            }
            broadcastChanges();
        }

        private void give(boolean salvage) {
            if (awarded) return;
            awarded = true;
            choosing = false;
            award(viewer, tier, prize, salvage);
        }

        @Override
        protected void onClick(int slot, int button, boolean shift) {
            if (!choosing) return;
            if (slot == KEEP || slot == SALVAGE) {
                give(slot == SALVAGE);
                viewer.closeContainer();
            }
        }

        @Override
        public void removed(Player player) {
            super.removed(player);
            if (!done) finish();   // closing early still gives the prize
            if (!awarded) give(false); // closing without choosing keeps it
            SPINNING.remove(this);
        }
    }

    private static final List<SpinMenu> SPINNING = new ArrayList<>();

    /** A weighted random prize from a list. */
    public static Loot.Prize rollFrom(List<Loot.Prize> pool, Random rng) {
        int total = 0;
        for (Loot.Prize p : pool) total += p.weight();
        int r = rng.nextInt(Math.max(1, total));
        for (Loot.Prize p : pool) {
            r -= p.weight();
            if (r < 0) return p;
        }
        return pool.get(pool.size() - 1);
    }

    /** Opens a spinning prize screen for any prize list (the Town's daily crates use this). */
    public static void spin(ServerPlayer p, String title, String color, List<Loot.Prize> pool) {
        Loot.Prize prize = rollFrom(pool, RNG);
        LockedMenu.open(p, title, 3, (id, inv, box, viewer) -> new SpinMenu(id, inv, box, viewer, 0, prize, pool, color));
    }

    /** Shows a prize list with each one's chance. */
    public static void previewList(ServerPlayer p, String title, List<Loot.Prize> pool, String footer) {
        int total = 0;
        for (Loot.Prize q : pool) total += q.weight();
        final int t = total;
        int rows = Math.min(6, (pool.size() + 8) / 9 + 1);
        LockedMenu.open(p, title, rows, (id, inv, box, viewer) -> {
            LockedMenu m = new LockedMenu(id, inv, box, rows, viewer);
            MinecraftServer server = viewer.level().getServer();
            for (int i = 0; i < pool.size() && i < box.getContainerSize() - 9; i++) {
                Loot.Prize pr = pool.get(i);
                ItemStack icon = pr.display(server).copy();
                Stacks.named(icon, "§f" + stripColor(pr.label()), String.format("§7Chance: §e%.1f%%", 100.0 * pr.weight() / t));
                box.setItem(i, icon);
            }
            if (footer != null) box.setItem(box.getContainerSize() - 5, Stacks.named(new ItemStack(Items.CLOCK), footer));
            return m;
        });
    }

    public static void tick() {
        SPINNING.removeIf(m -> !m.tick());
    }
}
