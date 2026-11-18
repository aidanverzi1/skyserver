package com.aidan.skyserver.island;

import com.aidan.skyserver.lobby.Economy;
import com.aidan.skyserver.lobby.Lobby;
import com.aidan.skyserver.lobby.LockedMenu;
import com.aidan.skyserver.lobby.Stacks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.*;
import java.util.function.Consumer;

/**
 * 5.26: the copper golem screens (all plain chest screens, plus an anvil for typing a search).
 *  - Label menu: sneak-click a regular chest.
 *  - Golem hub: sneak-click a copper chest (golems, jobs, upgrade, Storage Ledger, Tribute Runner, Lost & Found,
 *    Courier, parcels). The upgrade is also in the /is menu.
 */
public final class GolemMenus {
    private GolemMenus() {}

    static ItemStack icon(Item item, String name, String... lore) {
        return Stacks.named(new ItemStack(item), name, lore);
    }

    static ItemStack glow(ItemStack s) {
        s.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
        return s;
    }

    static ItemStack filler(DyeColor c) {
        return Stacks.named(new ItemStack(Items.STAINED_GLASS_PANE.pick(c)), " ");
    }

    static String nice(String itemId) {
        String p = itemId.contains(":") ? itemId.substring(itemId.indexOf(':') + 1) : itemId;
        StringBuilder b = new StringBuilder();
        for (String w : p.split("_")) if (!w.isEmpty()) b.append(b.length() == 0 ? "" : " ").append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
        return b.toString();
    }

    /** The island at this spot, if this player is a member of it (members own the golems and chests). */
    static Islands.Island memberIsland(ServerPlayer p, BlockPos pos) {
        Islands.Island is = Islands.islandAt(pos.getX(), pos.getZ());
        return is != null && is.isMember(p.getUUID()) ? is : null;
    }

    // =====================================================================
    // Upgrade icon (hub and /is menu)
    // =====================================================================

    public static ItemStack upgradeIcon(Islands.Island is, ServerPlayer viewer) {
        int lvl = Golems.level(is);
        List<String> lore = new ArrayList<>();
        lore.add("§7Island-wide: every copper golem gets it.");
        lore.add("§7Now: carry §f" + Golems.CARRY[lvl - 1] + "§7, range §f" + Golems.RANGE[lvl - 1] + " blocks§7, speed §f" + Golems.SPEED[lvl - 1]);
        lore.add((lvl >= Golems.FIND_LEVEL ? "§a✔" : "§8✘") + " §7Find it §8(level " + Golems.FIND_LEVEL + ")");
        lore.add((lvl >= Golems.FETCH_LEVEL ? "§a✔" : "§8✘") + " §7Fetch + no freezing into statues §8(level " + Golems.FETCH_LEVEL + ")");
        lore.add((lvl >= Golems.FURNACE_LEVEL ? "§a✔" : "§8✘") + " §7Furnace Tender job §8(level " + Golems.FURNACE_LEVEL + ")");
        lore.add((lvl >= Golems.COURIER_LEVEL ? "§a✔" : "§8✘") + " §7Golem Courier (parcels) §8(level " + Golems.COURIER_LEVEL + ")");
        if (lvl >= Golems.MAX_LEVEL) lore.add("§a(max level)");
        else {
            int n = lvl + 1;
            lore.add("");
            lore.add("§7Level " + n + ": §6" + Economy.format(Golems.PRICES[n - 1]) + " §7+ §6" + Golems.COPPER[n - 1] + " copper ingots");
            lore.add("§8(island fund pays first; copper blocks count as 9)");
            lore.add("§7" + Golems.unlockText(n));
            lore.add("§7You carry §f" + Golems.copperIn(viewer) + " §7copper.");
            lore.add("§eClick to upgrade");
        }
        ItemStack s = icon(Golems.item("minecraft:copper_block"), "§6§lCopper Golems Level " + lvl, lore.toArray(new String[0]));
        s.setCount(lvl);
        return s;
    }

    // =====================================================================
    // Label menu
    // =====================================================================

    public static boolean openLabels(ServerPlayer p, ServerLevel level, BlockPos pos) {
        Islands.Island is = memberIsland(p, pos);
        if (is == null || !level.getBlockState(pos).is(net.minecraft.world.level.block.Blocks.CHEST)) return false;
        BlockPos key = Golems.canonical(level, pos);
        LockedMenu.open(p, "§8Chest labels (golems)", 6, (id, inv, box, v) -> new LabelMenu(id, inv, box, v, is, level, key));
        return true;
    }

    static final class LabelMenu extends LockedMenu {
        private static final int INFO = 4, EVERYTHING = 46, CATS = 47, SEARCH = 48, OVERFLOW = 50, CLEAR = 51, HELP = 52;
        final Islands.Island is;
        final ServerLevel level;
        final BlockPos pos;
        final List<Object> shown = new ArrayList<>();

        LabelMenu(int id, Inventory inv, SimpleContainer box, ServerPlayer viewer, Islands.Island is, ServerLevel level, BlockPos pos) {
            super(id, inv, box, 6, viewer);
            this.is = is;
            this.level = level;
            this.pos = pos;
            draw();
        }

        Golems.Label label(boolean create) {
            return Golems.label(Golems.data(is.id), level, pos, create);
        }

        void draw() {
            for (int i = 0; i < box.getContainerSize(); i++) box.setItem(i, ItemStack.EMPTY);
            Golems.Label l = label(false);
            shown.clear();
            if (l != null) {
                shown.addAll(l.items);
                shown.addAll(l.cats);
            }
            box.setItem(INFO, icon(Items.CHEST, "§6§lChest at " + Golems.where(pos),
                    "§7Copper golems bring these things here,", "§7even when the chest is empty.",
                    "§7Labels: §f" + shown.size() + "/" + Golems.MAX_LABELS + (l != null && l.overflow ? " §7+ §eoverflow" : ""),
                    "§8Item frames on chests are just for you -", "§8golems go by these labels."));
            for (int i = 0; i < shown.size() && i < 36; i++) {
                Object o = shown.get(i);
                ItemStack s;
                if (o instanceof String sid) s = icon(Golems.item(sid), "§f" + nice(sid), "§7Item label", "§cClick to remove");
                else {
                    int c = (Integer) o;
                    s = glow(icon(Golems.CATEGORY_ICONS[c], "§e" + Golems.CATEGORY_NAMES[c], "§7Category label (everything of this kind)", "§cClick to remove"));
                }
                box.setItem(9 + i, s);
            }
            box.setItem(EVERYTHING, icon(Items.BARREL, "§a§lLabel what's inside", "§7Adds every kind of item that's in", "§7this chest right now.", "§eClick"));
            box.setItem(CATS, icon(Items.BOOKSHELF, "§e§lCategories", "§7Whole groups, like all ores or all food", "§8(the same groups as the Sort button)", "§eClick"));
            box.setItem(SEARCH, icon(Items.NAME_TAG, "§b§lSearch all items", "§7Type part of a name to find any item,", "§7even ones you don't have.", "§eClick"));
            boolean of = l != null && l.overflow;
            ItemStack ofi = icon(Items.HOPPER, of ? "§a§lOverflow chest: ON" : "§7§lOverflow chest: OFF",
                    "§7Golems put things here that have", "§7nowhere else to go.", "§eClick to switch");
            box.setItem(OVERFLOW, of ? glow(ofi) : ofi);
            box.setItem(CLEAR, icon(Items.BARRIER, "§c§lClear all labels", "§eShift-click"));
            box.setItem(HELP, icon(Items.PAPER, "§f§lAdd from your inventory", "§7Click any item in your inventory", "§7below to add it as a label."));
            frame(filler(DyeColor.ORANGE));
            broadcastChanges();
        }

        @Override
        protected void onClick(int slot, int button, boolean shift) {
            Golems.Label l;
            if (slot >= 9 && slot < 45) {
                int i = slot - 9;
                if (i >= shown.size()) return;
                l = label(false);
                if (l == null) return;
                Object o = shown.get(i);
                if (o instanceof String sid) l.items.remove(sid);
                else l.cats.remove(o);
            } else if (slot == EVERYTHING) {
                Container c = Golems.container(level, pos);
                if (c == null) return;
                l = label(true);
                int added = 0;
                for (int i = 0; i < c.getContainerSize(); i++) {
                    ItemStack s = c.getItem(i);
                    if (s.isEmpty() || l.size() >= Golems.MAX_LABELS) continue;
                    if (l.items.add(Golems.id(s.getItem()))) added++;
                }
                com.aidan.skyserver.lobby.Announce.actionBar(viewer, added > 0 ? "§aAdded " + added + " labels." : "§7Nothing new to add.");
            } else if (slot == CATS) {
                Lobby.later(viewer, () -> LockedMenu.openWithBack(viewer, () -> openLabels(viewer, level, pos),
                        () -> LockedMenu.open(viewer, "§8Category labels", 3, (id, inv, box, v) -> new CatMenu(id, inv, box, v, is, level, pos))));
                return;
            } else if (slot == SEARCH) {
                Lobby.later(viewer, () -> SearchAnvil.open(viewer, text -> openResults(viewer, is, level, pos, text)));
                return;
            } else if (slot == OVERFLOW) {
                l = label(true);
                l.overflow = !l.overflow;
            } else if (slot == CLEAR) {
                if (!shift) {
                    com.aidan.skyserver.lobby.Announce.actionBar(viewer, "§7Shift-click to clear every label on this chest.");
                    return;
                }
                l = label(false);
                if (l == null) return;
                l.items.clear();
                l.cats.clear();
                l.overflow = false;
            } else if (slot >= box.getContainerSize() && slot < slots.size()) {
                ItemStack s = slots.get(slot).getItem();
                if (s.isEmpty()) return;
                l = label(true);
                if (l.size() >= Golems.MAX_LABELS) {
                    com.aidan.skyserver.lobby.Announce.actionBar(viewer, "§cA chest can have at most " + Golems.MAX_LABELS + " labels.");
                    return;
                }
                l.items.add(Golems.id(s.getItem()));
            } else return;
            Golems.dirty();
            draw();
        }
    }

    static final class CatMenu extends LockedMenu {
        final Islands.Island is;
        final ServerLevel level;
        final BlockPos pos;

        CatMenu(int id, Inventory inv, SimpleContainer box, ServerPlayer viewer, Islands.Island is, ServerLevel level, BlockPos pos) {
            super(id, inv, box, 3, viewer);
            this.is = is;
            this.level = level;
            this.pos = pos;
            draw();
        }

        void draw() {
            for (int i = 0; i < box.getContainerSize(); i++) box.setItem(i, ItemStack.EMPTY);
            Golems.Label l = Golems.label(Golems.data(is.id), level, pos, false);
            for (int c = 0; c < Golems.CATEGORY_NAMES.length; c++) {
                boolean on = l != null && l.cats.contains(c);
                ItemStack s = icon(Golems.CATEGORY_ICONS[c], (on ? "§a✔ " : "§7") + Golems.CATEGORY_NAMES[c], on ? "§aThis chest takes these" : "§7Not on this chest", "§eClick to switch");
                box.setItem(9 + c, on ? glow(s) : s);
            }
            box.setItem(4, icon(Items.PAPER, "§f§lCategory labels", "§7The same groups the chest Sort", "§7button uses. An item label beats a", "§7category label if both match."));
            frame(filler(DyeColor.ORANGE));
            broadcastChanges();
        }

        @Override
        protected void onClick(int slot, int button, boolean shift) {
            int c = slot - 9;
            if (c < 0 || c >= Golems.CATEGORY_NAMES.length) return;
            Golems.Label l = Golems.label(Golems.data(is.id), level, pos, true);
            if (!l.cats.remove(c)) {
                if (l.size() >= Golems.MAX_LABELS) return;
                l.cats.add(c);
            }
            Golems.dirty();
            draw();
        }
    }

    // ---- Search ----

    private static List<Item> allItems;

    static List<Item> allItems() {
        if (allItems == null) {
            allItems = new ArrayList<>();
            for (Item i : BuiltInRegistries.ITEM) {
                String p = Golems.path(i);
                if (i == Items.AIR || p.contains("command_block") || p.startsWith("structure_") || p.equals("jigsaw") || p.equals("barrier")
                        || p.equals("debug_stick") || p.equals("light") || p.equals("knowledge_book") || p.startsWith("test_")) continue;
                allItems.add(i);
            }
            allItems.sort(Comparator.comparing(Golems::path));
        }
        return allItems;
    }

    static void openResults(ServerPlayer p, Islands.Island is, ServerLevel level, BlockPos pos, String text) {
        String q = text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
        List<Item> hits = new ArrayList<>();
        for (Item i : allItems()) if (q.isEmpty() || Golems.path(i).replace('_', ' ').contains(q) || Golems.path(i).contains(q.replace(' ', '_'))) hits.add(i);
        if (hits.isEmpty()) {
            Lobby.msg(p, "§7No items match \"" + text + "\".");
            openLabels(p, level, pos);
            return;
        }
        LockedMenu.openWithBack(p, () -> openLabels(p, level, pos),
                () -> LockedMenu.open(p, "§8Search: " + (q.isEmpty() ? "all items" : q), 6, (id, inv, box, v) -> new ResultsMenu(id, inv, box, v, is, level, pos, hits)));
    }

    static final class ResultsMenu extends LockedMenu {
        private static final int PREV = 48, INFO = 49, NEXT = 50, AGAIN = 52;
        final Islands.Island is;
        final ServerLevel level;
        final BlockPos pos;
        final List<Item> hits;
        int page;

        ResultsMenu(int id, Inventory inv, SimpleContainer box, ServerPlayer viewer, Islands.Island is, ServerLevel level, BlockPos pos, List<Item> hits) {
            super(id, inv, box, 6, viewer);
            this.is = is;
            this.level = level;
            this.pos = pos;
            this.hits = hits;
            draw();
        }

        int pages() {
            return Math.max(1, (hits.size() + 44) / 45);
        }

        void draw() {
            for (int i = 0; i < box.getContainerSize(); i++) box.setItem(i, ItemStack.EMPTY);
            Golems.Label l = Golems.label(Golems.data(is.id), level, pos, false);
            for (int i = 0; i < 45; i++) {
                int k = page * 45 + i;
                if (k >= hits.size()) break;
                Item it = hits.get(k);
                boolean on = l != null && l.items.contains(Golems.id(it));
                ItemStack s = icon(it, (on ? "§a✔ " : "§f") + nice(Golems.id(it)), on ? "§aOn this chest - click to remove" : "§eClick to add to this chest");
                box.setItem(i, on ? glow(s) : s);
            }
            if (page > 0) box.setItem(PREV, icon(Items.ARROW, "§f◀ Previous page"));
            box.setItem(INFO, icon(Items.PAPER, "§fPage " + (page + 1) + "/" + pages(), "§7" + hits.size() + " items match"));
            if (page < pages() - 1) box.setItem(NEXT, icon(Items.ARROW, "§fNext page ▶"));
            box.setItem(AGAIN, icon(Items.NAME_TAG, "§b§lSearch again", "§eClick"));
            frame(filler(DyeColor.ORANGE));
            broadcastChanges();
        }

        @Override
        protected void onClick(int slot, int button, boolean shift) {
            if (slot == PREV && page > 0) page--;
            else if (slot == NEXT && page < pages() - 1) page++;
            else if (slot == AGAIN) {
                Lobby.later(viewer, () -> SearchAnvil.open(viewer, text -> openResults(viewer, is, level, pos, text)));
                return;
            } else if (slot >= 0 && slot < 45) {
                int k = page * 45 + slot;
                if (k >= hits.size()) return;
                Golems.Label l = Golems.label(Golems.data(is.id), level, pos, true);
                String sid = Golems.id(hits.get(k));
                if (!l.items.remove(sid)) {
                    if (l.size() >= Golems.MAX_LABELS) {
                        com.aidan.skyserver.lobby.Announce.actionBar(viewer, "§cA chest can have at most " + Golems.MAX_LABELS + " labels.");
                        return;
                    }
                    l.items.add(sid);
                }
                Golems.dirty();
            } else return;
            draw();
        }
    }

    /**
     * The anvil's rename box as a search field (works on vanilla clients). Type, then click the result or
     * press Esc to search. The paper in the anvil is never given to the player.
     */
    static final class SearchAnvil extends AnvilMenu {
        final ServerPlayer viewer;
        final Consumer<String> done;
        String text = "";
        boolean finished;

        SearchAnvil(int id, Inventory inv, ServerPlayer viewer, Consumer<String> done) {
            super(id, inv, ContainerLevelAccess.NULL);
            this.viewer = viewer;
            this.done = done;
            getSlot(0).set(Stacks.named(new ItemStack(Items.PAPER), " "));
        }

        static void open(ServerPlayer p, Consumer<String> done) {
            p.openMenu(new SimpleMenuProvider((id, inv, pl) -> new SearchAnvil(id, inv, p, done), Component.literal("Type to search, then Esc")));
        }

        @Override
        public boolean setItemName(String name) {
            text = name == null ? "" : name.trim();
            getSlot(2).set(Stacks.named(new ItemStack(Items.SPYGLASS), "§aSearch: §f" + text, "§7Click (or press Esc) to search"));
            broadcastChanges();
            return true;
        }

        @Override
        public void createResult() {
        }

        @Override
        public void clicked(int slot, int button, ContainerInput input, Player player) {
            if (slot == 2 && !finished) {
                finished = true;
                String t = text;
                Lobby.later(viewer, () -> done.accept(t));
            }
            setCarried(ItemStack.EMPTY);
            sendAllDataToRemote();
        }

        @Override
        public ItemStack quickMoveStack(Player player, int slot) {
            return ItemStack.EMPTY;
        }

        @Override
        public void removed(Player player) {
            for (int i = 0; i < 3; i++) getSlot(i).set(ItemStack.EMPTY); // nothing goes back to the player
            super.removed(player);
            if (!finished) {
                finished = true;
                String t = text;
                Lobby.runLater(() -> { if (!viewer.hasDisconnected()) done.accept(t); });
            }
        }
    }

    // =====================================================================
    // The golem hub (sneak-click a copper chest)
    // =====================================================================

    public static boolean openHub(ServerPlayer p, ServerLevel level, BlockPos pos) {
        Islands.Island is = memberIsland(p, pos);
        if (is == null) return false;
        LockedMenu.open(p, "§8Copper Golem Hub", 5, (id, inv, box, v) -> new HubMenu(id, inv, box, v, is, level, pos.immutable()));
        return true;
    }

    static void reopenHub(ServerPlayer p, Islands.Island is, ServerLevel level, BlockPos hub) {
        LockedMenu.open(p, "§8Copper Golem Hub", 5, (id, inv, box, v) -> new HubMenu(id, inv, box, v, is, level, hub));
    }

    static final class HubMenu extends LockedMenu {
        private static final int INFO = 4, PROBLEMS = 20, LEDGER = 22, TRIBUTE = 24, UPGRADE = 29, LOST = 31, SEND = 33, PARCELS = 40;
        private static final int[] GOLEM_SLOTS = {11, 13, 15};
        final Islands.Island is;
        final ServerLevel level;
        final BlockPos hub;

        HubMenu(int id, Inventory inv, SimpleContainer box, ServerPlayer viewer, Islands.Island is, ServerLevel level, BlockPos hub) {
            super(id, inv, box, 5, viewer);
            this.is = is;
            this.level = level;
            this.hub = hub;
            draw();
        }

        void draw() {
            for (int i = 0; i < box.getContainerSize(); i++) box.setItem(i, ItemStack.EMPTY);
            Golems.IslandData d = Golems.data(is.id);
            int lvl = d.level;
            box.setItem(INFO, icon(Items.WRITABLE_BOOK, "§6§lCopper Golem Hub",
                    "§7Golems take things out of this copper", "§7chest and put them away:",
                    "§f 1. §7a chest labelled with that item", "§f 2. §7a chest labelled with its category",
                    "§f 3. §7an unlabelled chest that already has it", "§f 4. §7an overflow chest",
                    "§fSneak-click a chest §7to label it.", "§7Golems: §f" + d.golems.size() + "/" + Golems.MAX_GOLEMS + " §8(build: carved pumpkin on a copper block)",
                    "§7Range: §f" + Golems.RANGE[lvl - 1] + " blocks §7from the copper chest, carry §f" + Golems.CARRY[lvl - 1]));
            for (int i = 0; i < Golems.MAX_GOLEMS; i++) {
                if (i >= d.golems.size()) {
                    box.setItem(GOLEM_SLOTS[i], icon(Items.CARVED_PUMPKIN, "§7Free golem slot", "§7Build a copper golem: a carved", "§7pumpkin on a copper block."));
                    continue;
                }
                Golems.GolemRec g = d.golems.get(i);
                List<String> lore = new ArrayList<>();
                lore.add("§7Job: §f" + (g.job == Golems.Job.FURNACE ? "Furnace Tender" : "Sorter"));
                lore.add("§7Now: §f" + (g.mob == null ? "§8not around (not loaded)" : g.status));
                if (!g.bag.isEmpty()) lore.add("§7Carrying: §f" + Golems.bagCount(g.bag) + " " + g.bag.get(0).getHoverName().getString() + (g.bag.size() > 1 ? " and more" : ""));
                lore.add("§7Deliveries: §f" + g.trips);
                if (lvl >= Golems.FURNACE_LEVEL) lore.add("§eClick: switch job §8(one Furnace Tender per island)");
                else lore.add("§8Furnace Tender job at golem level " + Golems.FURNACE_LEVEL);
                if (g.mob == null) lore.add("§cShift-click: forget this golem §8(if it's really gone)");
                ItemStack s = icon(g.job == Golems.Job.FURNACE ? Items.FURNACE : Items.COPPER_INGOT, "§6§l" + g.label(i), lore.toArray(new String[0]));
                box.setItem(GOLEM_SLOTS[i], s);
            }
            List<String> prob = new ArrayList<>();
            prob.add(d.homeless > 0 ? "§c" + d.homeless + " items §7in copper chests have nowhere to go" : "§aEvery item has a home");
            int full = 0;
            for (long k : d.fullChests) {
                BlockPos fp = BlockPos.of(k);
                if (full++ < 6) prob.add("§cFull: §7chest at " + Golems.where(fp));
            }
            if (full == 0) prob.add("§aNo labelled chest is full");
            prob.add("§8Label a chest or add an overflow chest to fix");
            box.setItem(PROBLEMS, icon(d.homeless > 0 || full > 0 ? Items.REDSTONE_TORCH : Items.DYE.pick(DyeColor.LIME), "§e§lProblems", prob.toArray(new String[0])));
            box.setItem(LEDGER, icon(Items.BOOK, "§b§lStorage Ledger", "§7Everything in the chests your golems", "§7reach, and where it is.",
                    lvl >= Golems.FIND_LEVEL ? "§aLeft-click an item: Find it" : "§8Find it at golem level " + Golems.FIND_LEVEL,
                    lvl >= Golems.FETCH_LEVEL ? "§aRight-click an item: Fetch a stack" : "§8Fetch at golem level " + Golems.FETCH_LEVEL, "§eClick to open"));
            box.setItem(TRIBUTE, icon(Items.PRISMARINE_SHARD, "§3§lTribute Runner", "§7A golem gathers a Sunken Monument", "§7fare from your chests and carries it",
                    "§7to Wren's Tribute Chests (island pool:", "§7anyone on the island can use it).", "§eClick to pick a difficulty"));
            box.setItem(UPGRADE, upgradeIcon(is, viewer));
            box.setItem(LOST, icon(Items.BUNDLE, "§6§lLost & Found", "§7Things golems couldn't put anywhere.",
                    "§7Waiting: §f" + d.lost.size() + " stack" + (d.lost.size() == 1 ? "" : "s"), "§eClick to take them"));
            box.setItem(SEND, icon(Items.CHEST, lvl >= Golems.COURIER_LEVEL ? "§d§lSend a parcel" : "§8§lSend a parcel",
                    "§7A golem carries items to any player.", "§7They take it or send it back.", "§7Postage: §6" + Golems.POSTAGE + " copper ingots",
                    lvl >= Golems.COURIER_LEVEL ? "§eClick" : "§8Needs golem level " + Golems.COURIER_LEVEL));
            int waiting = Golems.parcelsFor(viewer.getUUID()).size();
            ItemStack pi = icon(Items.ENDER_CHEST, "§d§lYour parcels §7(" + waiting + ")", waiting > 0 ? "§aSomeone sent you something!" : "§7Nothing waiting.", "§eClick to open");
            if (waiting > 0) pi.setCount(Math.min(64, waiting));
            box.setItem(PARCELS, waiting > 0 ? glow(pi) : pi);
            frame(filler(DyeColor.ORANGE));
            broadcastChanges();
        }

        @Override
        protected void onClick(int slot, int button, boolean shift) {
            Golems.IslandData d = Golems.data(is.id);
            Runnable back = () -> reopenHub(viewer, is, level, hub);
            for (int i = 0; i < GOLEM_SLOTS.length; i++) if (slot == GOLEM_SLOTS[i]) {
                if (i >= d.golems.size()) return;
                Golems.GolemRec g = d.golems.get(i);
                if (shift && g.mob == null) {
                    d.golems.remove(g);
                    for (ItemStack s : g.bag) if (!s.isEmpty()) d.lost.add(s);
                    g.bag.clear();
                    Golems.dirty();
                    Lobby.msg(viewer, "§7Forgot that golem. §8(Anything it carried is in Lost & Found.)");
                } else if (d.level >= Golems.FURNACE_LEVEL) {
                    if (g.job == Golems.Job.SORT) {
                        for (Golems.GolemRec o : d.golems) if (o != g && o.job == Golems.Job.FURNACE) o.job = Golems.Job.SORT;
                        g.job = Golems.Job.FURNACE;
                    } else g.job = Golems.Job.SORT;
                    g.plan.clear();
                    g.cur = null;
                    Golems.dirty();
                } else com.aidan.skyserver.lobby.Announce.actionBar(viewer, "§7The Furnace Tender job unlocks at golem level " + Golems.FURNACE_LEVEL + ".");
                draw();
                return;
            }
            switch (slot) {
                case LEDGER -> Lobby.later(viewer, () -> LockedMenu.openWithBack(viewer, back, () -> openLedger(viewer, is, level, hub)));
                case TRIBUTE -> Lobby.later(viewer, () -> LockedMenu.openWithBack(viewer, back,
                        () -> LockedMenu.open(viewer, "§8Tribute Runner", 3, (id, inv, box, v) -> new TributeMenu(id, inv, box, v, is, level, hub))));
                case UPGRADE -> {
                    String r = Golems.buyUpgrade(viewer, is);
                    if (r != null) Lobby.msg(viewer, r);
                    draw();
                }
                case LOST -> Lobby.later(viewer, () -> LockedMenu.openWithBack(viewer, back,
                        () -> LockedMenu.open(viewer, "§8Lost & Found", 6, (id, inv, box, v) -> new LostMenu(id, inv, box, v, is))));
                case SEND -> {
                    if (d.level < Golems.COURIER_LEVEL) {
                        com.aidan.skyserver.lobby.Announce.actionBar(viewer, "§7The Golem Courier unlocks at golem level " + Golems.COURIER_LEVEL + ".");
                        return;
                    }
                    Lobby.later(viewer, () -> LockedMenu.openWithBack(viewer, back,
                            () -> LockedMenu.open(viewer, "§8Send a parcel to...", 6, PickPlayerMenu::new)));
                }
                case PARCELS -> Lobby.later(viewer, () -> openParcels(viewer, back));
                default -> { }
            }
        }
    }

    // =====================================================================
    // Storage Ledger
    // =====================================================================

    static void openLedger(ServerPlayer p, Islands.Island is, ServerLevel level, BlockPos hub) {
        Golems.IslandData d = Golems.data(is.id);
        Map<Item, Integer> count = new HashMap<>();
        Map<Item, Integer> chestsWith = new HashMap<>();
        List<BlockPos> all = Golems.chests(level, is, hub, Golems.RANGE[d.level - 1]);
        all.add(hub);
        for (BlockPos c : all) {
            Container con = Golems.container(level, c);
            if (con == null) continue;
            Set<Item> here = new HashSet<>();
            for (int i = 0; i < con.getContainerSize(); i++) {
                ItemStack s = con.getItem(i);
                if (s.isEmpty()) continue;
                count.merge(s.getItem(), s.getCount(), Integer::sum);
                here.add(s.getItem());
            }
            for (Item i : here) chestsWith.merge(i, 1, Integer::sum);
        }
        List<Item> items = new ArrayList<>(count.keySet());
        items.sort(Comparator.comparing(Golems::path));
        LockedMenu.open(p, "§8Storage Ledger", 6, (id, inv, box, v) -> new LedgerMenu(id, inv, box, v, is, level, hub, items, count, chestsWith, all.size()));
    }

    static final class LedgerMenu extends LockedMenu {
        private static final int PREV = 48, INFO = 49, NEXT = 50, FARE = 53;
        final Islands.Island is;
        final ServerLevel level;
        final BlockPos hub;
        final List<Item> items;
        final Map<Item, Integer> count, chestsWith;
        final int chestCount;
        int page;

        LedgerMenu(int id, Inventory inv, SimpleContainer box, ServerPlayer viewer, Islands.Island is, ServerLevel level, BlockPos hub,
                   List<Item> items, Map<Item, Integer> count, Map<Item, Integer> chestsWith, int chestCount) {
            super(id, inv, box, 6, viewer);
            this.is = is;
            this.level = level;
            this.hub = hub;
            this.items = items;
            this.count = count;
            this.chestsWith = chestsWith;
            this.chestCount = chestCount;
            draw();
        }

        int pages() {
            return Math.max(1, (items.size() + 44) / 45);
        }

        void draw() {
            for (int i = 0; i < box.getContainerSize(); i++) box.setItem(i, ItemStack.EMPTY);
            int lvl = Golems.data(is.id).level;
            for (int i = 0; i < 45; i++) {
                int k = page * 45 + i;
                if (k >= items.size()) break;
                Item it = items.get(k);
                int n = count.get(it);
                ItemStack s = icon(it, "§f" + nice(Golems.id(it)), "§7You have: §f" + n, "§7In §f" + chestsWith.get(it) + " §7chest" + (chestsWith.get(it) == 1 ? "" : "s"),
                        lvl >= Golems.FIND_LEVEL ? "§eLeft-click: Find it" : "§8Find it at golem level " + Golems.FIND_LEVEL,
                        lvl >= Golems.FETCH_LEVEL ? "§eRight-click: Fetch a stack" : "§8Fetch at golem level " + Golems.FETCH_LEVEL);
                s.setCount(Math.max(1, Math.min(64, n)));
                box.setItem(i, s);
            }
            if (page > 0) box.setItem(PREV, icon(Items.ARROW, "§f◀ Previous page"));
            box.setItem(INFO, icon(Items.PAPER, "§fPage " + (page + 1) + "/" + pages(), "§7" + items.size() + " kinds of item in " + chestCount + " chests",
                    "§8(chests within " + Golems.RANGE[lvl - 1] + " blocks of this copper chest)"));
            if (page < pages() - 1) box.setItem(NEXT, icon(Items.ARROW, "§fNext page ▶"));
            // Sunken fare readiness: island pool + what's in these chests.
            List<String> fare = new ArrayList<>();
            String[] dn = {"§aEasy", "§eNormal", "§cHard", "§4Nightmare"};
            for (int dIdx = 0; dIdx < 4; dIdx++) {
                int missing = 0;
                for (Tribute.Need n : Tribute.NEEDS) {
                    int have = Tribute.poolCount(is.id, n);
                    for (Map.Entry<Item, Integer> e : count.entrySet()) if (n.match().test(e.getKey())) have += e.getValue();
                    missing += Math.max(0, n.amount(dIdx) - have);
                }
                fare.add(dn[dIdx] + "§7: " + (missing == 0 ? "§aready" : "§c" + missing + " items short"));
            }
            fare.add("§8(island pool + these chests)");
            fare.add("§8Tribute Runner in the hub carries it to Wren.");
            box.setItem(FARE, icon(Items.PRISMARINE_SHARD, "§3§lSunken Monument fares", fare.toArray(new String[0])));
            frame(filler(DyeColor.LIGHT_BLUE));
            broadcastChanges();
        }

        @Override
        protected void onClick(int slot, int button, boolean shift) {
            if (slot == PREV && page > 0) page--;
            else if (slot == NEXT && page < pages() - 1) page++;
            else if (slot >= 0 && slot < 45) {
                int k = page * 45 + slot;
                if (k >= items.size()) return;
                Item it = items.get(k);
                int lvl = Golems.data(is.id).level;
                if (button == 1) {
                    if (lvl < Golems.FETCH_LEVEL) {
                        com.aidan.skyserver.lobby.Announce.actionBar(viewer, "§7Fetch unlocks at golem level " + Golems.FETCH_LEVEL + ".");
                        return;
                    }
                    viewer.closeContainer();
                    Golems.fetch(viewer, is, hub, it);
                } else {
                    if (lvl < Golems.FIND_LEVEL) {
                        com.aidan.skyserver.lobby.Announce.actionBar(viewer, "§7Find it unlocks at golem level " + Golems.FIND_LEVEL + ".");
                        return;
                    }
                    viewer.closeContainer();
                    Golems.find(viewer, is, hub, it);
                }
                return;
            } else return;
            draw();
        }
    }

    // =====================================================================
    // Tribute Runner
    // =====================================================================

    static final class TributeMenu extends LockedMenu {
        private static final int[] DIFFS = {10, 12, 14, 16};
        final Islands.Island is;
        final ServerLevel level;
        final BlockPos hub;

        TributeMenu(int id, Inventory inv, SimpleContainer box, ServerPlayer viewer, Islands.Island is, ServerLevel level, BlockPos hub) {
            super(id, inv, box, 3, viewer);
            this.is = is;
            this.level = level;
            this.hub = hub;
            draw();
        }

        void draw() {
            String[] dn = {"§aEasy", "§eNormal", "§cHard", "§4Nightmare"};
            Item[] icons = {Items.DYE.pick(DyeColor.LIME), Items.DYE.pick(DyeColor.YELLOW), Items.DYE.pick(DyeColor.RED), Items.DYE.pick(DyeColor.BLACK)};
            for (int dIdx = 0; dIdx < 4; dIdx++) {
                List<String> lore = new ArrayList<>();
                lore.add("§7Island pool now:");
                for (Tribute.Need n : Tribute.NEEDS) {
                    int have = Tribute.poolCount(is.id, n), need = n.amount(dIdx);
                    lore.add((have >= need ? " §a✔ " : " §c✘ ") + "§7" + n.name() + " §f" + have + "/" + need);
                }
                lore.add("§eClick: a golem gathers the rest");
                box.setItem(DIFFS[dIdx], icon(icons[dIdx], "§l" + dn[dIdx] + " fare", lore.toArray(new String[0])));
            }
            box.setItem(4, icon(Items.PRISMARINE_SHARD, "§3§lTribute Runner", "§7Pick a difficulty. A golem takes what's", "§7still missing from the copper chest and",
                    "§7the chests in range, and carries it to", "§7the Tribute Chests in Wren's ship room.", "§7Anyone on the island can use it."));
            frame(filler(DyeColor.CYAN));
            broadcastChanges();
        }

        @Override
        protected void onClick(int slot, int button, boolean shift) {
            for (int dIdx = 0; dIdx < 4; dIdx++) if (slot == DIFFS[dIdx]) {
                viewer.closeContainer();
                Lobby.msg(viewer, Golems.tribute(viewer, is, hub, dIdx));
                return;
            }
        }
    }

    // =====================================================================
    // Lost & Found
    // =====================================================================

    static final class LostMenu extends LockedMenu {
        final Islands.Island is;

        LostMenu(int id, Inventory inv, SimpleContainer box, ServerPlayer viewer, Islands.Island is) {
            super(id, inv, box, 6, viewer);
            this.is = is;
            draw();
        }

        void draw() {
            for (int i = 0; i < box.getContainerSize(); i++) box.setItem(i, ItemStack.EMPTY);
            List<ItemStack> lost = Golems.data(is.id).lost;
            for (int i = 0; i < lost.size() && i < 45; i++) box.setItem(i, lost.get(i).copy());
            box.setItem(49, icon(Items.PAPER, "§f§lLost & Found", "§7Click a stack to take it.", "§7" + lost.size() + " stack" + (lost.size() == 1 ? "" : "s") + " waiting"));
            frame(filler(DyeColor.ORANGE));
            broadcastChanges();
        }

        @Override
        protected void onClick(int slot, int button, boolean shift) {
            List<ItemStack> lost = Golems.data(is.id).lost;
            if (slot < 0 || slot >= 45 || slot >= lost.size()) return;
            ItemStack s = lost.get(slot);
            viewer.getInventory().add(s); // shrinks by what fit
            if (s.isEmpty()) lost.remove(slot);
            else com.aidan.skyserver.lobby.Announce.actionBar(viewer, "§cYour inventory is full.");
            Golems.dirty();
            draw();
        }
    }

    // =====================================================================
    // Courier: pick a player, pack the parcel
    // =====================================================================

    static final class PickPlayerMenu extends LockedMenu {
        final List<ServerPlayer> who = new ArrayList<>();

        PickPlayerMenu(int id, Inventory inv, SimpleContainer box, ServerPlayer viewer) {
            super(id, inv, box, 6, viewer);
            for (ServerPlayer o : viewer.level().getServer().getPlayerList().getPlayers())
                if (o != viewer && !(o instanceof com.aidan.skyserver.bots.BotPlayer) && who.size() < 45) who.add(o);
            for (int i = 0; i < who.size(); i++)
                box.setItem(i, icon(Items.PLAYER_HEAD, "§f" + who.get(i).getName().getString(), "§eClick to pack a parcel for them"));
            if (who.isEmpty()) box.setItem(22, icon(Items.BARRIER, "§7Nobody else is online"));
            frame(filler(DyeColor.PURPLE));
            broadcastChanges();
        }

        @Override
        protected void onClick(int slot, int button, boolean shift) {
            if (slot < 0 || slot >= who.size()) return;
            ServerPlayer to = who.get(slot);
            UUID id = to.getUUID();
            String name = to.getName().getString();
            Lobby.later(viewer, () -> LockedMenu.open(viewer, "§8Parcel for " + name, 6, (i, inv, box, v) -> new ParcelMenu(i, inv, box, v, id, name)));
        }
    }

    static final class ParcelMenu extends LockedMenu {
        private static final int INFO = 47, SEND = 49, CANCEL = 51;
        final UUID to;
        final String toName;
        final List<ItemStack> parcel = new ArrayList<>();
        boolean closed;

        ParcelMenu(int id, Inventory inv, SimpleContainer box, ServerPlayer viewer, UUID to, String toName) {
            super(id, inv, box, 6, viewer);
            this.to = to;
            this.toName = toName;
            draw();
        }

        void draw() {
            for (int i = 0; i < box.getContainerSize(); i++) box.setItem(i, ItemStack.EMPTY);
            for (int i = 0; i < parcel.size(); i++) box.setItem(i, parcel.get(i).copy());
            box.setItem(INFO, icon(Items.PAPER, "§f§lParcel for " + toName, "§fClick items in your inventory §7to pack", "§7them, click a packed stack to take it", "§7back. Up to 27 stacks."));
            box.setItem(SEND, icon(Items.DYE.pick(DyeColor.LIME), "§a§lSend it", "§7Postage: §6" + Golems.POSTAGE + " copper ingots §8(from your inventory)",
                    "§7" + toName + " can take it or send it back.", parcel.isEmpty() ? "§8Pack something first" : "§eClick"));
            box.setItem(CANCEL, icon(Items.BARRIER, "§c§lCancel", "§7Everything goes back to you."));
            frame(filler(DyeColor.PURPLE));
            broadcastChanges();
        }

        @Override
        protected void onClick(int slot, int button, boolean shift) {
            if (closed) return;
            if (slot >= 0 && slot < 27) {
                if (slot >= parcel.size()) return;
                giveBack(parcel.remove(slot));
            } else if (slot == SEND) {
                if (parcel.isEmpty()) return;
                if (!Golems.takeCopper(viewer, Golems.POSTAGE)) {
                    Lobby.msg(viewer, "§cPostage is " + Golems.POSTAGE + " copper ingots - you don't have enough.");
                    return;
                }
                closed = true;
                List<ItemStack> items = new ArrayList<>(parcel);
                parcel.clear();
                Golems.send(viewer, to, toName, items);
                Lobby.msg(viewer, "§6📦 §fYour golem is off with the parcel for §e" + toName + "§f.");
                viewer.closeContainer();
                return;
            } else if (slot == CANCEL) {
                viewer.closeContainer();
                return;
            } else if (slot >= box.getContainerSize() && slot < slots.size()) {
                if (parcel.size() >= 27) {
                    com.aidan.skyserver.lobby.Announce.actionBar(viewer, "§cA parcel holds at most 27 stacks.");
                    return;
                }
                ItemStack st = slots.get(slot).getItem();
                if (st.isEmpty()) return;
                parcel.add(st.copy());
                slots.get(slot).set(ItemStack.EMPTY);
            } else return;
            draw();
        }

        private void giveBack(ItemStack s) {
            if (s.isEmpty()) return;
            if (viewer.hasDisconnected()) { // logged out mid-packing: it waits in /claim
                com.aidan.skyserver.lobby.Mailbox.send(viewer.level().getServer(), viewer.getUUID(), s);
                return;
            }
            if (!viewer.getInventory().add(s) || !s.isEmpty()) viewer.drop(s, false, net.minecraft.util.Prediction.SERVER_ONLY);
        }

        @Override
        public void removed(Player player) {
            super.removed(player);
            if (!closed) {
                closed = true;
                for (ItemStack s : parcel) giveBack(s);
                parcel.clear();
            }
        }
    }

    // =====================================================================
    // Parcels waiting for you
    // =====================================================================

    public static void openParcels(ServerPlayer p, Runnable back) {
        if (back != null) LockedMenu.openWithBack(p, back, () -> LockedMenu.open(p, "§8Your parcels", 6, ParcelsMenu::new));
        else LockedMenu.open(p, "§8Your parcels", 6, ParcelsMenu::new);
    }

    static final class ParcelsMenu extends LockedMenu {
        final List<Golems.Parcel> list = new ArrayList<>();

        ParcelsMenu(int id, Inventory inv, SimpleContainer box, ServerPlayer viewer) {
            super(id, inv, box, 6, viewer);
            draw();
        }

        void draw() {
            for (int i = 0; i < box.getContainerSize(); i++) box.setItem(i, ItemStack.EMPTY);
            list.clear();
            list.addAll(Golems.parcelsFor(viewer.getUUID()));
            for (int i = 0; i < list.size() && i < 45; i++) {
                Golems.Parcel pc = list.get(i);
                List<String> lore = new ArrayList<>();
                lore.add(pc.returned ? "§7Your parcel, sent back by §f" + pc.fromName : "§7From: §f" + pc.fromName);
                for (int k = 0; k < pc.items.size() && k < 8; k++)
                    lore.add(" §8- §f" + pc.items.get(k).getCount() + "x " + pc.items.get(k).getHoverName().getString());
                if (pc.items.size() > 8) lore.add(" §8...and " + (pc.items.size() - 8) + " more stacks");
                lore.add("§7Needs §f" + pc.items.size() + " §7empty slots");
                lore.add("§aLeft-click: take it");
                if (!pc.returned) lore.add("§cRight-click: send it back");
                box.setItem(i, icon(pc.returned ? Items.BARREL : Items.CHEST, "§d§lParcel #" + pc.id, lore.toArray(new String[0])));
            }
            if (list.isEmpty()) box.setItem(22, icon(Items.PAPER, "§7No parcels waiting"));
            frame(filler(DyeColor.PURPLE));
            broadcastChanges();
        }

        @Override
        protected void onClick(int slot, int button, boolean shift) {
            if (slot < 0 || slot >= 45 || slot >= list.size()) return;
            Golems.Parcel pc = list.get(slot);
            Lobby.msg(viewer, button == 1 ? Golems.decline(viewer, pc) : Golems.accept(viewer, pc));
            draw();
        }
    }
}
