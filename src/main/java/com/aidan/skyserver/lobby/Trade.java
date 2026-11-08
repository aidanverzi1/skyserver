package com.aidan.skyserver.lobby;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Items;

import java.util.*;

/**
 * Player-to-player trading: /trade <player>, they type /trade accept. A shared screen opens:
 * your offer on the left, theirs on the right. Click items in your inventory to offer them, use
 * the key and money buttons along the bottom, then press Ready. When both are ready there's a
 * 3-second countdown (any change cancels it) and everything swaps at once.
 *
 * Items can only be traded when both players are on the Sky Islands (or both in survival). In the lobby you can trade keys
 * and money. Closing the screen or logging out cancels and gives everything back.
 */
public final class Trade {
    private static final int OFFER_SLOTS = 20;
    private static final Map<UUID, UUID> REQUESTS = new HashMap<>();   // target -> requester
    private static final Map<UUID, Long> REQUEST_TIME = new HashMap<>();
    private static final Map<UUID, Session> ACTIVE = new HashMap<>();
    private static final List<Session> SESSIONS = new ArrayList<>();

    private Trade() {}

    // =====================================================================
    // Requests
    // =====================================================================

    public static void request(ServerPlayer from, ServerPlayer to) {
        if (from == to) {
            Lobby.msg(from, "§cYou can't trade with yourself.");
            return;
        }
        String problem = realmProblem(from, to);
        if (problem != null) {
            Lobby.msg(from, "§c" + problem);
            return;
        }
        if (ACTIVE.containsKey(from.getUUID()) || ACTIVE.containsKey(to.getUUID())) {
            Lobby.msg(from, "§cOne of you is already trading.");
            return;
        }
        // They already asked us: accept straight away.
        if (to.getUUID().equals(REQUESTS.get(from.getUUID()))) {
            accept(from, to);
            return;
        }
        REQUESTS.put(to.getUUID(), from.getUUID());
        REQUEST_TIME.put(to.getUUID(), System.currentTimeMillis());
        Lobby.msg(from, "§aTrade request sent to §f" + to.getName().getString() + "§a.");
        Lobby.msg(to, "§e" + from.getName().getString() + " §awants to trade. Type §e/trade accept §ato start (expires in 60 seconds).");
    }

    public static void accept(ServerPlayer me, ServerPlayer from) {
        UUID asked = REQUESTS.get(me.getUUID());
        Long when = REQUEST_TIME.get(me.getUUID());
        if (asked == null || when == null || System.currentTimeMillis() - when > 60_000 || (from != null && !asked.equals(from.getUUID()))) {
            Lobby.msg(me, "§cYou don't have a trade request" + (from == null ? "." : " from " + from.getName().getString() + "."));
            return;
        }
        ServerPlayer other = me.level().getServer().getPlayerList().getPlayer(asked);
        REQUESTS.remove(me.getUUID());
        if (other == null) {
            Lobby.msg(me, "§cThey're not online anymore.");
            return;
        }
        String problem = realmProblem(me, other);
        if (problem != null) {
            Lobby.msg(me, "§c" + problem);
            return;
        }
        if (ACTIVE.containsKey(me.getUUID()) || ACTIVE.containsKey(other.getUUID())) {
            Lobby.msg(me, "§cOne of you is already trading.");
            return;
        }
        new Session(other, me);
    }

    private static String realmProblem(ServerPlayer a, ServerPlayer b) {
        boolean surv = Lobby.sameItemWorld(a.level(), b.level());
        boolean lobby = Lobby.isLobby(a.level()) && Lobby.isLobby(b.level());
        if (!surv && !lobby) return "You both need to be on Sky Islands (items, keys, money) or both in the lobby (keys and money).";
        return null;
    }

    public static UUID pendingFrom(ServerPlayer me) {
        return REQUESTS.get(me.getUUID());
    }

    /** Cancels any trade this player is in (logout, teleport). */
    public static void cancel(ServerPlayer p) {
        Session s = ACTIVE.get(p.getUUID());
        if (s != null) s.cancel(p.getName().getString() + " left the trade.");
    }

    public static void tick() {
        for (Session s : new ArrayList<>(SESSIONS)) s.tick();
    }

    // =====================================================================
    // A trade between two players
    // =====================================================================

    private static final class Session {
        final ServerPlayer[] players = new ServerPlayer[2];
        final List<List<ItemStack>> offers = List.of(new ArrayList<>(), new ArrayList<>());
        final int[][] keys = new int[2][5];
        final long[] money = new long[2];
        final boolean[] ready = new boolean[2];
        final TradeMenu[] menus = new TradeMenu[2];
        final boolean items;
        int countdown = -1;
        boolean over = false;

        Session(ServerPlayer a, ServerPlayer b) {
            players[0] = a;
            players[1] = b;
            items = Lobby.sameItemWorld(a.level(), b.level());
            ACTIVE.put(a.getUUID(), this);
            ACTIVE.put(b.getUUID(), this);
            SESSIONS.add(this);
            for (int i = 0; i < 2; i++) {
                final int side = i;
                ServerPlayer other = players[1 - i];
                LockedMenu.open(players[i], "Trade with " + other.getName().getString(), 6,
                        (id, inv, box, viewer) -> menus[side] = new TradeMenu(id, inv, box, viewer, this, side));
            }
            if (!items) for (ServerPlayer p : players) Lobby.msg(p, "§7(In the lobby you can trade keys and money. Trade items on your Sky Islands.)");
            refresh();
        }

        int sideOf(ServerPlayer p) {
            return players[0] == p ? 0 : 1;
        }

        void changed() {
            ready[0] = ready[1] = false;
            countdown = -1;
            refresh();
        }

        void refresh() {
            for (int i = 0; i < 2; i++) if (menus[i] != null) menus[i].draw();
        }

        void tick() {
            if (over) return;
            for (ServerPlayer p : players) {
                if (p.isRemoved() || p.hasDisconnected()) {
                    cancel(p.getName().getString() + " left.");
                    return;
                }
            }
            if (ready[0] && ready[1]) {
                if (countdown < 0) countdown = 60;
                if (countdown % 20 == 0) {
                    refresh();
                    for (ServerPlayer p : players) p.level().playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.UI_BUTTON_CLICK, SoundSource.PLAYERS, 0.6f, 1.5f);
                }
                if (--countdown <= 0) complete();
            }
        }

        void complete() {
            MinecraftServer server = players[0].level().getServer();
            // Check they still have the keys and money they offered.
            for (int i = 0; i < 2; i++) {
                for (int t = 0; t < 5; t++) if (Keys.count(players[i].getUUID(), t) < keys[i][t]) {
                    cancel(players[i].getName().getString() + " doesn't have those keys anymore.");
                    return;
                }
                if (money[i] > 0) {
                    long bal = Economy.balance(server, players[i].getUUID());
                    if (bal < money[i]) {
                        cancel(players[i].getName().getString() + " doesn't have " + Economy.format(money[i]) + ".");
                        return;
                    }
                }
            }
            over = true;
            for (int i = 0; i < 2; i++) {
                ServerPlayer from = players[i], to = players[1 - i];
                for (int t = 0; t < 5; t++) if (keys[i][t] > 0 && Keys.take(from.getUUID(), t, keys[i][t])) Keys.give(to.getUUID(), t, keys[i][t]);
                if (money[i] > 0 && Economy.take(server, from.getUUID(), money[i])) Economy.add(server, to.getUUID(), to.getName().getString(), money[i]);
                for (ItemStack s : offers.get(i)) giveOrMail(to, s);
                offers.get(i).clear();
            }
            end();
            for (ServerPlayer p : players) {
                Lobby.msg(p, "§aTrade complete!");
                p.level().playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 1f, 1.4f);
                Lobby.refreshKit(p);
            }
        }

        void cancel(String why) {
            if (over) return;
            over = true;
            for (int i = 0; i < 2; i++) {
                for (ItemStack s : offers.get(i)) giveOrMail(players[i], s);
                offers.get(i).clear();
            }
            end();
            for (ServerPlayer p : players) if (!p.hasDisconnected()) Lobby.msg(p, "§cTrade cancelled. §7" + why);
        }

        private void end() {
            ACTIVE.remove(players[0].getUUID());
            ACTIVE.remove(players[1].getUUID());
            SESSIONS.remove(this);
            for (ServerPlayer p : players) {
                if (p.containerMenu instanceof TradeMenu tm && tm.session == this) p.closeContainer();
            }
        }

        private void giveOrMail(ServerPlayer p, ItemStack s) {
            if (s.isEmpty()) return;
            if (!p.hasDisconnected() && Lobby.isItemWorld(p.level())) {
                p.getInventory().add(s); // shrinks by what fit
                if (s.isEmpty()) return;
            }
            Mailbox.send(p.level().getServer(), p.getUUID(), s);
            if (!p.hasDisconnected()) Lobby.msg(p, "§7Some items didn't fit: they're waiting for you (§e/claim§7).");
        }
    }

    // =====================================================================
    // The trade screen
    // =====================================================================

    static final class TradeMenu extends LockedMenu {
        final Session session;
        final int side;

        TradeMenu(int id, Inventory inv, SimpleContainer box, ServerPlayer viewer, Session session, int side) {
            super(id, inv, box, 6, viewer);
            this.session = session;
            this.side = side;
        }

        private static int mySlot(int i) {  // offer index -> menu slot (left 4 columns, 5 rows)
            return (i / 4) * 9 + (i % 4);
        }

        private static int theirSlot(int i) {
            return (i / 4) * 9 + 5 + (i % 4);
        }

        void draw() {
            ItemStack glass = Stacks.named(new ItemStack(Items.STAINED_GLASS_PANE.pick(DyeColor.GRAY)), " ");
            for (int i = 0; i < 54; i++) box.setItem(i, ItemStack.EMPTY);
            for (int r = 0; r < 5; r++) box.setItem(r * 9 + 4, glass.copy());
            List<ItemStack> mine = session.offers.get(side), theirs = session.offers.get(1 - side);
            for (int i = 0; i < OFFER_SLOTS; i++) {
                if (i < mine.size()) box.setItem(mySlot(i), mine.get(i).copy());
                if (i < theirs.size()) box.setItem(theirSlot(i), theirs.get(i).copy());
            }
            boolean meReady = session.ready[side], themReady = session.ready[1 - side];
            String count = session.countdown >= 0 ? " §e(" + (session.countdown / 20 + 1) + "...)" : "";
            box.setItem(45, Stacks.named(new ItemStack(meReady ? Items.DYE.pick(DyeColor.LIME) : Items.DYE.pick(DyeColor.RED)),
                    meReady ? "§a§lREADY" + count : "§c§lNOT READY", "§7Click when you're happy with the trade.",
                    session.items ? "§7Click items in your inventory to offer them." : "§7Lobby: keys and money only."));
            for (int t = 0; t < 5; t++) {
                int n = session.keys[side][t];
                ItemStack k = new ItemStack(Items.TRIPWIRE_HOOK, Math.max(1, Math.min(64, n)));
                Stacks.named(k, Keys.COLORS[t] + "Offer " + Keys.NAMES[t] + " Keys: §f" + n,
                        "§7You have " + Keys.count(viewer.getUUID(), t), "§7Left-click +1, right-click -1");
                box.setItem(46 + t, k);
            }
            box.setItem(51, Stacks.named(new ItemStack(Items.GOLD_INGOT), "§6Offer money: §f" + Economy.format(session.money[side]),
                    "§7Left +$100, right -$100", "§7Shift-left +$1,000, shift-right -$1,000"));
            StringBuilder theirKeys = new StringBuilder();
            for (int t = 0; t < 5; t++) if (session.keys[1 - side][t] > 0) theirKeys.append(Keys.COLORS[t]).append(session.keys[1 - side][t]).append(" ").append(Keys.NAMES[t]).append(" ");
            box.setItem(52, Stacks.named(new ItemStack(Items.PAPER), "§eThey offer:",
                    "§7Keys: " + (theirKeys.length() == 0 ? "none" : theirKeys.toString()),
                    "§7Money: §6" + Economy.format(session.money[1 - side])));
            box.setItem(53, Stacks.named(new ItemStack(themReady ? Items.DYE.pick(DyeColor.LIME) : Items.DYE.pick(DyeColor.RED)), themReady ? "§a§lTHEY'RE READY" : "§c§lTHEY'RE NOT READY"));
            broadcastChanges();
        }

        @Override
        protected void onClick(int slot, int button, boolean shift) {
            if (session.over) return;
            int menuSize = 54;
            if (slot >= menuSize) {
                // Offer an item from the player's own inventory.
                if (!session.items) return;
                Slot s = slots.get(slot);
                ItemStack stack = s.getItem();
                if (stack.isEmpty()) return;
                List<ItemStack> mine = session.offers.get(side);
                if (mine.size() >= OFFER_SLOTS) {
                    Lobby.msg(viewer, "§cYour offer is full.");
                    return;
                }
                mine.add(stack.copy());
                s.set(ItemStack.EMPTY);
                session.changed();
                return;
            }
            int col = slot % 9, row = slot / 9;
            if (row < 5 && col < 4) {
                int idx = row * 4 + col;
                List<ItemStack> mine = session.offers.get(side);
                if (idx < mine.size()) {
                    ItemStack back = mine.remove(idx);
                    viewer.getInventory().add(back);
                    if (!back.isEmpty()) Mailbox.send(viewer.level().getServer(), viewer.getUUID(), back);
                    session.changed();
                }
                return;
            }
            if (slot == 45) {
                session.ready[side] = !session.ready[side];
                if (!session.ready[side]) session.countdown = -1;
                session.refresh();
                return;
            }
            if (slot >= 46 && slot <= 50) {
                int t = slot - 46;
                int n = session.keys[side][t] + (button == 1 ? -1 : 1);
                if (n < 0 || n > Keys.count(viewer.getUUID(), t)) return;
                session.keys[side][t] = n;
                session.changed();
                return;
            }
            if (slot == 51) {
                long step = shift ? 1_000 : 100;
                long m = session.money[side] + (button == 1 ? -step : step);
                if (m < 0) m = 0;
                long bal = Economy.balance(viewer.level().getServer(), viewer.getUUID());
                if (bal >= 0 && m > bal) m = bal;
                if (bal < 0 && m > 0) {
                    Lobby.msg(viewer, "§cMoney trading isn't available (economy mod not found).");
                    return;
                }
                session.money[side] = m;
                session.changed();
            }
        }

        @Override
        public void removed(Player player) {
            super.removed(player);
            if (!session.over) session.cancel(player.getName().getString() + " closed the trade.");
        }
    }
}
