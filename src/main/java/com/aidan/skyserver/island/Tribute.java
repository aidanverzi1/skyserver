package com.aidan.skyserver.island;

import com.aidan.skyserver.lobby.*;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.*;
import java.util.function.Predicate;

/**
 * 5.20: the three TRIBUTE CHESTS in Captain Wren's ship room - where players put the items that pay for a
 * Sunken Monument Expedition (iron, copper, coal, redstone, cooked chicken, wool, seeds, wheat, carrots,
 * dried kelp blocks, pumpkins, sugar cane, and diamonds for Nightmare).
 *
 *  - Every chest has one slot per item. Click an item in your own inventory to put it in; click a slot to
 *    take back a stack (shift: all of it). "Put in everything" moves all of them at once.
 *  - Everyone has their own share of each chest: you can only take out what YOU put in. A player from
 *    another island (a faction mate coming along) puts their items in the HOST's chests.
 *  - The island owner can send other islands' players' items back to their /claim (so nothing gets stuck).
 *  - Each player can store at most the Nightmare amount of each item (it's not a free vault).
 *  - When a trip leaves, Wren takes each crew member's items from the host island's chests.
 * Saved in config/skyserver/tribute.json as island id -> chest -> player -> item id -> count.
 */
public final class Tribute {
    private Tribute() {}

    public static final int CHESTS = 3;

    /** One kind of item the entry asks for. */
    public record Need(String key, String name, Item icon, Predicate<Item> match, int[] amounts) {
        public int amount(int diff) {
            return amounts[Math.max(0, Math.min(3, diff))];
        }

        public int max() {
            int m = 0;
            for (int a : amounts) m = Math.max(m, a);
            return m;
        }
    }

    private static int[] steps(int per) {
        return new int[]{per, per * 2, per * 3, per * 4};
    }

    private static boolean idEnds(Item i, String suffix) {
        return BuiltInRegistries.ITEM.getKey(i).getPath().endsWith(suffix);
    }

    /** Everything the Sunken Monument entry asks for, per difficulty (Easy, Normal, Hard, Nightmare). */
    public static final List<Need> NEEDS = List.of(
            new Need("iron", "Iron Ingots", Items.IRON_INGOT, i -> i == Items.IRON_INGOT, steps(64)),
            new Need("copper", "Copper Ingots", Items.COPPER_INGOT, i -> i == Items.COPPER_INGOT, steps(64)),
            new Need("coal", "Coal", Items.COAL, i -> i == Items.COAL, steps(64)),
            new Need("redstone", "Redstone", Items.REDSTONE, i -> i == Items.REDSTONE, steps(64)),
            new Need("chicken", "Cooked Chicken", Items.COOKED_CHICKEN, i -> i == Items.COOKED_CHICKEN, steps(64)),
            new Need("wool", "Wool (any colour)", Items.WOOL.pick(DyeColor.WHITE), i -> idEnds(i, "_wool"), steps(64)),
            new Need("seeds", "Seeds (wheat, beetroot, melon, pumpkin)", Items.WHEAT_SEEDS,
                    i -> i == Items.WHEAT_SEEDS || i == Items.BEETROOT_SEEDS || i == Items.MELON_SEEDS || i == Items.PUMPKIN_SEEDS, steps(64)),
            new Need("wheat", "Wheat", Items.WHEAT, i -> i == Items.WHEAT, steps(64)),
            new Need("carrot", "Carrots", Items.CARROT, i -> i == Items.CARROT, steps(64)),
            new Need("kelp", "Dried Kelp Blocks", Items.DRIED_KELP_BLOCK, i -> i == Items.DRIED_KELP_BLOCK, steps(16)),
            new Need("pumpkin", "Pumpkins", Items.PUMPKIN, i -> i == Items.PUMPKIN, steps(32)),
            new Need("cane", "Sugar Cane", Items.SUGAR_CANE, i -> i == Items.SUGAR_CANE, steps(64)),
            new Need("diamond", "Diamonds", Items.DIAMOND, i -> i == Items.DIAMOND, new int[]{0, 0, 0, 16}));

    /** island -> chest -> player -> item id -> count */
    private static final Map<Integer, Map<Integer, Map<UUID, Map<String, Integer>>>> DATA = new HashMap<>();
    private static boolean loaded = false;

    public static Need needFor(Item item) {
        for (Need n : NEEDS) if (n.match.test(item)) return n;
        return null;
    }

    private static String id(Item i) {
        return BuiltInRegistries.ITEM.getKey(i).toString();
    }

    private static Item item(String id) {
        return BuiltInRegistries.ITEM.getValue(Identifier.parse(id));
    }

    private static Map<String, Integer> share(int island, int chest, UUID who, boolean create) {
        load();
        if (!create) {
            Map<Integer, Map<UUID, Map<String, Integer>>> a = DATA.get(island);
            Map<UUID, Map<String, Integer>> b = a == null ? null : a.get(chest);
            Map<String, Integer> c = b == null ? null : b.get(who);
            return c == null ? Map.of() : c;
        }
        return DATA.computeIfAbsent(island, k -> new HashMap<>()).computeIfAbsent(chest, k -> new HashMap<>()).computeIfAbsent(who, k -> new HashMap<>());
    }

    /** How many of this need the player has in ONE chest. */
    public static int inChest(int island, int chest, UUID who, Need n) {
        int t = 0;
        for (Map.Entry<String, Integer> e : share(island, chest, who, false).entrySet()) if (n.match.test(item(e.getKey()))) t += e.getValue();
        return t;
    }

    /** How many of this need the player has in all of an island's tribute chests. */
    public static int total(int island, UUID who, Need n) {
        int t = 0;
        for (int c = 0; c < CHESTS; c++) t += inChest(island, c, who, n);
        return t;
    }

    /** The needs this player hasn't filled yet for a difficulty on this island ("Iron Ingots 40/64", ...). */
    public static List<String> missing(int island, UUID who, int diff) {
        List<String> out = new ArrayList<>();
        for (Need n : NEEDS) {
            int need = n.amount(diff), have = total(island, who, n);
            if (have < need) out.add(n.name + " " + have + "/" + need);
        }
        return out;
    }

    public static boolean ready(int island, UUID who, int diff) {
        return missing(island, who, diff).isEmpty();
    }

    /**
     * Takes one difficulty's entry from this player's share of the island's chests. Returns what was taken
     * (item id -> count, so it can be given back), or null (and takes nothing) if anything is short.
     */
    public static Map<String, Integer> take(int island, UUID who, int diff) {
        if (!ready(island, who, diff)) return null;
        Map<String, Integer> taken = new HashMap<>();
        for (Need n : NEEDS) {
            int left = n.amount(diff);
            for (int c = 0; c < CHESTS && left > 0; c++) {
                Map<String, Integer> s = share(island, c, who, true);
                for (String k : new ArrayList<>(s.keySet())) {
                    if (left <= 0) break;
                    if (!n.match.test(item(k))) continue;
                    int use = Math.min(left, s.get(k));
                    left -= use;
                    taken.merge(k, use, Integer::sum);
                    if (s.get(k) - use <= 0) s.remove(k);
                    else s.put(k, s.get(k) - use);
                }
            }
        }
        save();
        return taken;
    }

    /** Puts items taken by take() back into the first chest (the trip didn't happen for them). */
    public static void giveBack(int island, UUID who, Map<String, Integer> taken) {
        if (taken == null || taken.isEmpty()) return;
        Map<String, Integer> s = share(island, 0, who, true);
        taken.forEach((k, v) -> s.merge(k, v, Integer::sum));
        save();
    }

    // =====================================================================
    // The chest menu
    // =====================================================================

    public static void open(ServerPlayer p, Islands.Island is, int chest) {
        if (is == null || chest < 0 || chest >= CHESTS) return;
        LockedMenu.open(p, "§8Tribute Chest " + (chest + 1) + " - " + is.ownerName, 6, (id, inv, box, v) -> new Menu(id, inv, box, v, is.id, chest));
    }

    private static final Map<UUID, Integer> SHOW_DIFF = new HashMap<>();

    static final class Menu extends LockedMenu {
        private static final int[] SLOTS = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24};
        private static final int[] DIFFS = {1, 2, 3, 4};
        private static final int INFO = 7, ALL_IN = 40, SEND_BACK = 44, STATUS = 49;
        private final int island, chest;

        Menu(int id, Inventory inv, SimpleContainer box, ServerPlayer viewer, int island, int chest) {
            super(id, inv, box, 6, viewer);
            this.island = island;
            this.chest = chest;
            draw();
        }

        private int diff() {
            return SHOW_DIFF.getOrDefault(viewer.getUUID(), 0);
        }

        private void draw() {
            for (int i = 0; i < box.getContainerSize(); i++) box.setItem(i, ItemStack.EMPTY);
            UUID me = viewer.getUUID();
            int d = diff();
            String[] names = {"§aEasy", "§eNormal", "§cHard", "§4Nightmare"};
            for (int i = 0; i < 4; i++)
                box.setItem(DIFFS[i], Stacks.named(new ItemStack(Items.DYE.pick(i == d ? DyeColor.LIME : DyeColor.GRAY)), names[i] + (i == d ? " §7(showing)" : ""),
                        "§7Show what a " + names[i] + " §7Sunken Monument", "§7trip needs.", "§eClick"));
            box.setItem(INFO, Stacks.named(new ItemStack(Items.CHEST), "§6§lTribute Chest " + (chest + 1),
                    "§7Captain Wren takes these items as the", "§7fare for a §bSunken Monument Expedition§7.",
                    "§fClick an item in your inventory §7to put it in.", "§fClick a slot §7to take a stack back §8(shift: all)§7.",
                    "§7You can only take out what §fyou §7put in.", "§7All 3 chests count together.",
                    "§7Coming from another island? Put your", "§7items in the §fhost's §7chests."));
            for (int i = 0; i < NEEDS.size() && i < SLOTS.length; i++) {
                Need n = NEEDS.get(i);
                int here = inChest(island, chest, me, n), all = total(island, me, n), need = n.amount(d);
                ItemStack icon = new ItemStack(n.icon, Math.max(1, Math.min(64, here)));
                box.setItem(SLOTS[i], Stacks.named(icon, (need == 0 ? "§8" : all >= need ? "§a✔ " : "§c✘ ") + "§f" + n.name,
                        "§7In this chest (yours): §f" + here, "§7In all 3 chests: §f" + all,
                        need == 0 ? "§8Not needed on this difficulty" : "§7Needed: §f" + need + (all >= need ? " §a(done)" : " §c(" + (need - all) + " more)"),
                        "§8Most you can store: " + n.max(), "§eClick: take back 64 §8| §eShift-click: take back all"));
            }
            List<String> miss = missing(island, me, d);
            box.setItem(ALL_IN, Stacks.named(new ItemStack(Items.HOPPER), "§a§lPut in everything",
                    "§7Moves every tribute item from your", "§7inventory into this chest.", "§eClick"));
            List<String> st = new ArrayList<>();
            if (miss.isEmpty()) st.add("§aYour " + names[d].substring(2) + " fare is ready!");
            else {
                st.add("§7Still missing:");
                for (String m : miss) st.add(" §c" + m);
            }
            box.setItem(STATUS, Stacks.named(new ItemStack(miss.isEmpty() ? Items.EMERALD : Items.REDSTONE), (miss.isEmpty() ? "§a§l" : "§c§l") + "Fare status", st.toArray(new String[0])));
            Islands.Island is = islandById(island);
            if (is != null && is.owner.equals(me))
                box.setItem(SEND_BACK, Stacks.named(new ItemStack(Items.ENDER_CHEST), "§d§lSend visitors' items home",
                        "§7Sends everything players from §fother", "§7islands §7have in this chest to their", "§e/claim§7.", "§eClick §8(owner only)"));
            frame(Stacks.named(new ItemStack(Items.STAINED_GLASS_PANE.pick(DyeColor.CYAN)), " "));
            broadcastChanges();
        }

        @Override
        protected void onClick(int slot, int button, boolean shift) {
            UUID me = viewer.getUUID();
            for (int i = 0; i < 4; i++) if (slot == DIFFS[i]) {
                SHOW_DIFF.put(me, i);
                draw();
                return;
            }
            if (slot == ALL_IN) {
                int moved = 0;
                for (int i = 0; i < viewer.getInventory().getContainerSize(); i++) moved += deposit(viewer.getInventory().getItem(i));
                Lobby.msg(viewer, moved > 0 ? "§aPut " + moved + " items in Tribute Chest " + (chest + 1) + "." : "§7Nothing in your inventory is tribute (or you've stored the most of each).");
                draw();
                return;
            }
            if (slot == SEND_BACK) {
                Islands.Island is = islandById(island);
                if (is != null && is.owner.equals(me)) {
                    int n = sendVisitorsHome(viewer.level().getServer(), is, chest);
                    Lobby.msg(viewer, n > 0 ? "§aSent " + n + " stacks back to their owners' /claim." : "§7No other islands' items in this chest.");
                }
                draw();
                return;
            }
            for (int i = 0; i < NEEDS.size() && i < SLOTS.length; i++) if (slot == SLOTS[i]) {
                withdraw(NEEDS.get(i), shift ? Integer.MAX_VALUE : 64);
                draw();
                return;
            }
            // The player's own inventory: put that stack in.
            if (slot >= box.getContainerSize() && slot < slots.size()) {
                Slot s = slots.get(slot);
                ItemStack st = s.getItem();
                if (st.isEmpty()) return;
                if (needFor(st.getItem()) == null) {
                    Announce.actionBar(viewer, "§7Wren doesn't take that as tribute.");
                    return;
                }
                if (deposit(st) == 0) Announce.actionBar(viewer, "§7You've already stored the most of that.");
                s.setChanged();
                draw();
            }
        }

        /** Moves as much of this stack as fits into the viewer's share. Returns how many moved. */
        private int deposit(ItemStack st) {
            if (st.isEmpty()) return 0;
            Need n = needFor(st.getItem());
            if (n == null) return 0;
            int room = n.max() - total(island, viewer.getUUID(), n);
            int move = Math.min(room, st.getCount());
            if (move <= 0) return 0;
            share(island, chest, viewer.getUUID(), true).merge(id(st.getItem()), move, Integer::sum);
            st.shrink(move);
            save();
            return move;
        }

        private void withdraw(Need n, int max) {
            Map<String, Integer> s = share(island, chest, viewer.getUUID(), true);
            int left = max, gave = 0;
            for (String k : new ArrayList<>(s.keySet())) {
                if (left <= 0) break;
                Item it = item(k);
                if (!n.match.test(it)) continue;
                int have = s.get(k);
                while (have > 0 && left > 0) {
                    int take = Math.min(Math.min(have, left), it.getDefaultMaxStackSize());
                    ItemStack give = new ItemStack(it, take);
                    viewer.getInventory().add(give);
                    int moved = take - give.getCount();
                    have -= moved;
                    left -= moved;
                    gave += moved;
                    if (give.getCount() > 0) { left = 0; break; } // inventory full
                }
                if (have <= 0) s.remove(k);
                else s.put(k, have);
            }
            save();
            Announce.actionBar(viewer, gave > 0 ? "§aTook back " + gave + " " + n.name : "§7Nothing of yours to take (or your inventory is full).");
        }
    }

    /** Owner button: every non-member's share of this chest goes to their /claim. Returns stacks sent. */
    static int sendVisitorsHome(MinecraftServer server, Islands.Island is, int chest) {
        load();
        Map<Integer, Map<UUID, Map<String, Integer>>> a = DATA.get(is.id);
        Map<UUID, Map<String, Integer>> b = a == null ? null : a.get(chest);
        if (b == null) return 0;
        int stacks = 0;
        for (UUID who : new ArrayList<>(b.keySet())) {
            if (is.isMember(who)) continue;
            for (Map.Entry<String, Integer> e : b.get(who).entrySet()) {
                Item it = item(e.getKey());
                int left = e.getValue();
                while (left > 0) {
                    int n = Math.min(left, it.getDefaultMaxStackSize());
                    Mailbox.send(server, who, new ItemStack(it, n));
                    left -= n;
                    stacks++;
                }
            }
            b.remove(who);
            ServerPlayer o = server.getPlayerList().getPlayer(who);
            if (o != null) Lobby.msg(o, "§7Your tribute items in " + is.name() + "'s ship room were sent to your §e/claim§7.");
        }
        save();
        return stacks;
    }

    private static Islands.Island islandById(int id) {
        for (Islands.Island is : Islands.all()) if (is.id == id) return is;
        return null;
    }

    // =====================================================================
    // Saving
    // =====================================================================

    private static void load() {
        if (loaded) return;
        loaded = true;
        JsonObject root = Stacks.readFile("tribute.json");
        try {
            for (Map.Entry<String, JsonElement> ie : root.entrySet()) {
                int island = Integer.parseInt(ie.getKey());
                for (Map.Entry<String, JsonElement> ce : ie.getValue().getAsJsonObject().entrySet()) {
                    int chest = Integer.parseInt(ce.getKey());
                    for (Map.Entry<String, JsonElement> pe : ce.getValue().getAsJsonObject().entrySet()) {
                        Map<String, Integer> s = DATA.computeIfAbsent(island, k -> new HashMap<>()).computeIfAbsent(chest, k -> new HashMap<>())
                                .computeIfAbsent(UUID.fromString(pe.getKey()), k -> new HashMap<>());
                        for (Map.Entry<String, JsonElement> e : pe.getValue().getAsJsonObject().entrySet()) s.put(e.getKey(), e.getValue().getAsInt());
                    }
                }
            }
        } catch (RuntimeException ex) {
            com.aidan.skyserver.Skyserver.LOGGER.warn("Couldn't read tribute.json fully: {}", ex.toString());
        }
    }

    private static void save() {
        JsonObject root = new JsonObject();
        DATA.forEach((island, chests) -> {
            JsonObject co = new JsonObject();
            chests.forEach((chest, players) -> {
                JsonObject po = new JsonObject();
                players.forEach((who, items) -> {
                    if (items.isEmpty()) return;
                    JsonObject io = new JsonObject();
                    items.forEach(io::addProperty);
                    po.add(who.toString(), io);
                });
                if (po.size() > 0) co.add(String.valueOf(chest), po);
            });
            if (co.size() > 0) root.add(String.valueOf(island), co);
        });
        PlayerCache.io(() -> Stacks.writeFile("tribute.json", root));
    }
}
