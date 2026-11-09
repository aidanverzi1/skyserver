package com.aidan.skyserver.island;

import com.aidan.skyserver.lobby.Economy;
import com.aidan.skyserver.lobby.Lobby;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.stats.Stats;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.phys.AABB;

import java.util.*;

/**
 * 5.10: island chests (regular chests only) open a paged screen with a button row:
 *   [Sort] . . [◀] [page x/y] [▶] . . [+ Page]
 * Sort groups items by kind (building blocks, ores, farming/food, mob drops, tools, weapons, armor,
 * potions/books, everything else), then A-Z, then enchantments, then durability, and merges stacks.
 * Extra pages are bought for one chest: $5,000 for the first, $10,000 for the second (click twice to
 * confirm). A single chest gets +27 slots per page; a double chest buys for both halves at once (+54).
 * Broken chests keep their pages on the dropped chest item and get them back when placed.
 * Trapped chests, barrels, ender chests and everything else are unchanged.
 */
public final class ChestPages {
    public static final int MAX_PAGES = 2;
    public static final long[] PRICES = {5_000, 10_000};
    private static final String TAG = "skyserver_pages";

    private ChestPages() {}

    public static void init() {
        // Open: regular chests on the islands get the paged screen.
        UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
            if (level.isClientSide() || !Islands.isIsland(level) || !(player instanceof ServerPlayer sp)) return InteractionResult.PASS;
            BlockPos pos = hit.getBlockPos();
            BlockState state = level.getBlockState(pos);
            ItemStack held = sp.getItemInHand(hand);
            if (state.is(Blocks.CHEST)) {
                if (sp.isSecondaryUseActive() && !held.isEmpty()) return InteractionResult.PASS; // sneaking to place
                if (hand != InteractionHand.MAIN_HAND) return InteractionResult.SUCCESS;
                if (ChestBlock.isChestBlockedAt(level, pos)) return InteractionResult.PASS; // vanilla says "blocked"
                open(sp, (ServerLevel) level, pos);
                return InteractionResult.SUCCESS;
            }
            // Placing a chest that carries pages: give them back to the placed chest.
            int pages = pagesOnItem(held);
            if (pages > 0 && held.is(Items.CHEST)) {
                BlockPos target = state.canBeReplaced() ? pos : pos.relative(hit.getDirection());
                Lobby.runLater(() -> {
                    if (level.getBlockState(target).is(Blocks.CHEST) && level.getBlockEntity(target) instanceof ChestPagesAccess a && a.skyserver$getPages() < pages) {
                        a.skyserver$setPages(pages);
                        level.getBlockEntity(target).setChanged();
                    }
                });
            }
            return InteractionResult.PASS;
        });
        // Break: the dropped chest item remembers the pages (the items inside drop as normal).
        PlayerBlockBreakEvents.AFTER.register((level, player, pos, state, be) -> {
            if (!Islands.isIsland(level) || !state.is(Blocks.CHEST) || !(be instanceof ChestPagesAccess a)) return;
            int pages = a.skyserver$getPages();
            if (pages <= 0 || player.isCreative()) return;
            Lobby.runLater(() -> tagDrop((ServerLevel) level, pos, pages));
        });
    }

    // ---- Items carrying pages ----

    public static int pagesOnItem(ItemStack stack) {
        if (stack.isEmpty()) return 0;
        CustomData cd = stack.get(DataComponents.CUSTOM_DATA);
        if (cd == null) return 0;
        return cd.copyTag().getIntOr(TAG, 0);
    }

    private static void tagDrop(ServerLevel level, BlockPos pos, int pages) {
        // The chest's own drop is the newest single chest item near the spot.
        ItemEntity best = null;
        for (ItemEntity e : level.getEntitiesOfClass(ItemEntity.class, new AABB(pos).inflate(2.0))) {
            ItemStack s = e.getItem();
            if (!s.is(Items.CHEST) || s.getCount() != 1 || pagesOnItem(s) > 0 || e.tickCount > 5) continue;
            if (best == null || e.getId() > best.getId()) best = e;
        }
        if (best == null) return;
        ItemStack s = best.getItem().copy();
        CompoundTag tag = new CompoundTag();
        tag.putInt(TAG, pages);
        s.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        s.set(DataComponents.LORE, new ItemLore(List.of(Component.literal("§7Extra pages: §e" + pages + " §7(" + (27 * (1 + pages)) + " slots)"),
                Component.literal("§8Place it to get them back"))));
        best.setItem(s);
    }

    // ---- Opening ----

    /** The chest (single) or both halves (double), first half first - same order as vanilla's double chest. */
    private static List<BlockEntity> halves(ServerLevel level, BlockPos pos) {
        List<BlockEntity> out = new ArrayList<>();
        BlockState state = level.getBlockState(pos);
        BlockEntity here = level.getBlockEntity(pos);
        if (here != null) out.add(here);
        if (state.getValue(ChestBlock.TYPE) != ChestType.SINGLE) {
            Direction d = ChestBlock.getConnectedDirection(state);
            BlockPos other = pos.relative(d);
            if (level.getBlockState(other).is(Blocks.CHEST) && level.getBlockEntity(other) != null) out.add(level.getBlockEntity(other));
        }
        return out;
    }

    private static void open(ServerPlayer p, ServerLevel level, BlockPos pos) {
        Container real = ChestBlock.getContainer((ChestBlock) Blocks.CHEST, level.getBlockState(pos), level, pos, false);
        if (real == null) return;
        boolean big = halves(level, pos).size() > 1;
        Component title = Component.literal(big ? "Large Chest" : "Chest");
        p.openMenu(new SimpleMenuProvider((id, inv, pl) -> new Menu(id, inv, real, level, pos.immutable(), big), title));
        p.awardStat(Stats.CUSTOM.get(Stats.OPEN_CHEST));
    }

    private static int pagesOf(ServerLevel level, BlockPos pos) {
        int n = 0;
        for (BlockEntity be : halves(level, pos)) if (be instanceof ChestPagesAccess a) n = Math.max(n, a.skyserver$getPages());
        return n;
    }

    // ---- Sorting ----

    /** Merges stacks and sorts everything in the chest (all pages). */
    static void sort(Container c) {
        List<ItemStack> all = new ArrayList<>();
        for (int i = 0; i < c.getContainerSize(); i++) {
            ItemStack s = c.getItem(i);
            if (s.isEmpty()) continue;
            ItemStack left = s.copy();
            for (ItemStack m : all) {
                if (left.isEmpty()) break;
                if (ItemStack.isSameItemSameComponents(m, left) && m.getCount() < m.getMaxStackSize()) {
                    int move = Math.min(left.getCount(), m.getMaxStackSize() - m.getCount());
                    m.grow(move);
                    left.shrink(move);
                }
            }
            if (!left.isEmpty()) all.add(left);
        }
        all.sort(ORDER);
        for (int i = 0; i < c.getContainerSize(); i++) c.setItem(i, i < all.size() ? all.get(i) : ItemStack.EMPTY);
        c.setChanged();
    }

    private static final Comparator<ItemStack> ORDER = Comparator
            .comparingInt(ChestPages::category)
            .thenComparing(s -> s.getHoverName().getString().toLowerCase(Locale.ROOT))
            .thenComparing(Comparator.comparingInt(ChestPages::enchantLevels).reversed())
            .thenComparing(ChestPages::enchantText)
            .thenComparing(Comparator.comparingInt((ItemStack s) -> s.isDamageableItem() ? s.getMaxDamage() - s.getDamageValue() : 0).reversed())
            .thenComparing(Comparator.comparingInt(ItemStack::getCount).reversed());

    private static int enchantLevels(ItemStack s) {
        int n = 0;
        ItemEnchantments e = EnchantmentHelper.getEnchantmentsForCrafting(s);
        for (Object2IntMap.Entry<Holder<Enchantment>> en : e.entrySet()) n += en.getIntValue();
        return n;
    }

    private static String enchantText(ItemStack s) {
        StringBuilder b = new StringBuilder();
        ItemEnchantments e = EnchantmentHelper.getEnchantmentsForCrafting(s);
        for (Object2IntMap.Entry<Holder<Enchantment>> en : e.entrySet())
            b.append(en.getKey().getRegisteredName()).append(en.getIntValue()).append(',');
        return b.toString();
    }

    private static final Set<String> MOB_DROPS = Set.of("bone", "bone_meal", "string", "rotten_flesh", "gunpowder", "spider_eye", "fermented_spider_eye",
            "ender_pearl", "ender_eye", "blaze_rod", "blaze_powder", "slime_ball", "leather", "feather", "ink_sac", "glow_ink_sac", "phantom_membrane",
            "ghast_tear", "magma_cream", "shulker_shell", "rabbit_hide", "rabbit_foot", "nether_star", "prismarine_shard", "prismarine_crystals",
            "scute", "turtle_scute", "armadillo_scute", "breeze_rod", "wind_charge", "echo_shard", "honeycomb", "egg", "brown_egg", "blue_egg",
            "white_wool", "dragon_breath");
    private static final Set<String> FARMING = Set.of("wheat", "wheat_seeds", "beetroot_seeds", "melon_seeds", "pumpkin_seeds", "torchflower_seeds",
            "pitcher_pod", "sugar_cane", "cactus", "bamboo", "cocoa_beans", "nether_wart", "pumpkin", "melon", "hay_block", "sweet_berries",
            "glow_berries", "kelp", "dried_kelp_block", "sugar", "brown_mushroom", "red_mushroom", "lily_pad", "vine", "moss_block");

    /** 0 building blocks, 1 ores/minerals, 2 farming/food, 3 mob drops, 4 tools, 5 weapons, 6 armor, 7 potions/books, 8 everything else. */
    static int category(ItemStack s) {
        Item item = s.getItem();
        String id = BuiltInRegistries.ITEM.getKey(item).getPath();
        if (id.endsWith("_sword") || id.equals("bow") || id.equals("crossbow") || id.equals("trident") || id.equals("mace")
                || id.endsWith("arrow") || id.endsWith("_spear")) return 5;
        if (id.endsWith("_pickaxe") || id.endsWith("_axe") || id.endsWith("_shovel") || id.endsWith("_hoe") || id.equals("shears")
                || id.equals("fishing_rod") || id.equals("flint_and_steel") || id.equals("brush") || id.endsWith("_on_a_stick")
                || id.equals("spyglass") || id.equals("compass") || id.equals("recovery_compass") || id.equals("clock") || id.equals("lead")
                || id.equals("bucket") || id.endsWith("_bucket") && !id.contains("milk")) return 4;
        if (id.endsWith("_helmet") || id.endsWith("_chestplate") || id.endsWith("_leggings") || id.endsWith("_boots") || id.equals("elytra")
                || id.equals("shield") || id.endsWith("_horse_armor") || id.endsWith("_harness") || id.equals("wolf_armor") || id.equals("turtle_helmet")) return 6;
        if (id.contains("potion") || id.equals("enchanted_book") || id.equals("book") || id.equals("writable_book") || id.equals("written_book")
                || id.equals("experience_bottle") || id.equals("tipped_arrow") || id.equals("knowledge_book")) return 7;
        if (s.has(DataComponents.FOOD) || FARMING.contains(id) || id.endsWith("_seeds") || id.endsWith("_sapling") || id.endsWith("_propagule")) return 2;
        if (MOB_DROPS.contains(id) || id.endsWith("_spawn_egg") || id.endsWith("_head") || id.endsWith("_skull")) return 3;
        if (id.contains("_ore") || id.startsWith("raw_") || id.endsWith("_ingot") || id.endsWith("_nugget") || id.equals("diamond") || id.equals("emerald")
                || id.equals("coal") || id.equals("charcoal") || id.equals("lapis_lazuli") || id.equals("redstone") || id.equals("quartz")
                || id.equals("amethyst_shard") || id.equals("netherite_scrap") || id.equals("flint") || id.equals("glowstone_dust")
                || id.endsWith("_block") && (id.startsWith("iron") || id.startsWith("gold") || id.startsWith("diamond") || id.startsWith("emerald")
                || id.startsWith("coal") || id.startsWith("lapis") || id.startsWith("redstone") || id.startsWith("netherite") || id.startsWith("copper")
                || id.startsWith("raw_"))) return 1;
        if (item instanceof BlockItem) return 0;
        return 8;
    }

    /** Puts a stack anywhere in the chest (all pages): first onto matching stacks, then into empty slots. */
    static void insertAll(Container c, ItemStack stack) {
        for (int pass = 0; pass < 2 && !stack.isEmpty(); pass++) {
            for (int i = 0; i < c.getContainerSize() && !stack.isEmpty(); i++) {
                ItemStack in = c.getItem(i);
                if (pass == 0) {
                    if (in.isEmpty() || !ItemStack.isSameItemSameComponents(in, stack)) continue;
                    int move = Math.min(stack.getCount(), in.getMaxStackSize() - in.getCount());
                    if (move <= 0) continue;
                    in.grow(move);
                    stack.shrink(move);
                } else if (in.isEmpty() && c.canPlaceItem(i, stack)) {
                    c.setItem(i, stack.split(Math.min(stack.getCount(), stack.getMaxStackSize())));
                }
            }
        }
        c.setChanged();
    }

    // ---- The screen ----

    private static ItemStack button(Item item, String name, String... lore) {
        ItemStack s = new ItemStack(item);
        s.set(DataComponents.CUSTOM_NAME, Component.literal(name));
        List<Component> l = new ArrayList<>();
        for (String x : lore) l.add(Component.literal(x));
        if (!l.isEmpty()) s.set(DataComponents.LORE, new ItemLore(l));
        return s;
    }

    private static ItemStack noSlotCache; private static ItemStack noSlot() { if (noSlotCache == null) noSlotCache = button(Items.STAINED_GLASS_PANE.pick(net.minecraft.world.item.DyeColor.BLACK), "§8No slot here", "§7Buy another page with §a+ Page"); return noSlotCache; }

    /** One page of the chest, shown in the top part of the screen. */
    private static final class PageView implements Container {
        final Container real;
        final int size;
        int page;

        PageView(Container real, int size) {
            this.real = real;
            this.size = size;
        }

        int realIndex(int i) {
            return page * size + i;
        }

        boolean inRange(int i) {
            return realIndex(i) < real.getContainerSize();
        }

        @Override public int getContainerSize() { return size; }
        @Override public boolean isEmpty() { return real.isEmpty(); }
        @Override public ItemStack getItem(int i) { return inRange(i) ? real.getItem(realIndex(i)) : noSlot(); }
        @Override public ItemStack removeItem(int i, int n) { return inRange(i) ? real.removeItem(realIndex(i), n) : ItemStack.EMPTY; }
        @Override public ItemStack removeItemNoUpdate(int i) { return inRange(i) ? real.removeItemNoUpdate(realIndex(i)) : ItemStack.EMPTY; }
        @Override public void setItem(int i, ItemStack s) { if (inRange(i)) real.setItem(realIndex(i), s); }
        @Override public void setChanged() { real.setChanged(); }
        @Override public boolean stillValid(Player p) { return real.stillValid(p); }
        @Override public boolean canPlaceItem(int i, ItemStack s) { return inRange(i) && real.canPlaceItem(realIndex(i), s); }
        @Override public void clearContent() { }
    }

    private static final class PageSlot extends Slot {
        final PageView view;

        PageSlot(PageView view, int i, int x, int y) {
            super(view, i, x, y);
            this.view = view;
        }

        @Override public boolean mayPlace(ItemStack s) { return view.inRange(getContainerSlot()) && super.mayPlace(s); }
        @Override public boolean mayPickup(Player p) { return view.inRange(getContainerSlot()); }
    }

    private static final class ButtonSlot extends Slot {
        ButtonSlot(Container c, int i, int x, int y) {
            super(c, i, x, y);
        }

        @Override public boolean mayPlace(ItemStack s) { return false; }
        @Override public boolean mayPickup(Player p) { return false; }
    }

    static final class Menu extends AbstractContainerMenu {
        final Container real;
        final ServerLevel level;
        final BlockPos pos;
        final PageView view;
        final SimpleContainer buttons = new SimpleContainer(9);
        final int pageSize, buttonStart, invStart, invEnd;
        long confirmUntil = 0;

        Menu(int id, Inventory inv, Container real, ServerLevel level, BlockPos pos, boolean big) {
            super(big ? MenuType.GENERIC_9x6 : MenuType.GENERIC_9x4, id);
            this.real = real;
            this.level = level;
            this.pos = pos;
            this.pageSize = big ? 45 : 27;
            this.view = new PageView(real, pageSize);
            int rows = pageSize / 9;
            for (int r = 0; r < rows; r++) for (int c = 0; c < 9; c++) addSlot(new PageSlot(view, r * 9 + c, 8 + c * 18, 18 + r * 18));
            buttonStart = slots.size();
            for (int c = 0; c < 9; c++) addSlot(new ButtonSlot(buttons, c, 8 + c * 18, 18 + rows * 18));
            invStart = slots.size();
            int top = 18 + (rows + 1) * 18 + 13;
            for (int r = 0; r < 3; r++) for (int c = 0; c < 9; c++) addSlot(new Slot(inv, 9 + r * 9 + c, 8 + c * 18, top + r * 18));
            for (int c = 0; c < 9; c++) addSlot(new Slot(inv, c, 8 + c * 18, top + 58));
            invEnd = slots.size();
            real.startOpen(inv.player);
            refreshButtons();
        }

        int pageCount() {
            return Math.max(1, (real.getContainerSize() + pageSize - 1) / pageSize);
        }

        void refreshButtons() {
            int pages = pageCount();
            if (view.page >= pages) view.page = pages - 1;
            ItemStack filler = button(Items.STAINED_GLASS_PANE.pick(net.minecraft.world.item.DyeColor.GRAY), " ");
            for (int i = 0; i < 9; i++) buttons.setItem(i, filler.copy());
            buttons.setItem(0, button(Items.HOPPER, "§e§lSort", "§7Groups items by kind, then A-Z,", "§7then enchantments and durability.", "§7Also merges stacks. Sorts every page."));
            buttons.setItem(3, view.page > 0 ? button(Items.ARROW, "§f◀ Previous page") : button(Items.STAINED_GLASS_PANE.pick(net.minecraft.world.item.DyeColor.GRAY), "§8◀ First page"));
            ItemStack info = button(Items.PAPER, "§fPage " + (view.page + 1) + "/" + pages, "§7" + real.getContainerSize() + " slots in this chest");
            info.setCount(Math.min(64, view.page + 1));
            buttons.setItem(4, info);
            buttons.setItem(5, view.page < pages - 1 ? button(Items.ARROW, "§fNext page ▶") : button(Items.STAINED_GLASS_PANE.pick(net.minecraft.world.item.DyeColor.GRAY), "§8Last page ▶"));
            int owned = pagesOf(level, pos);
            boolean big = pageSize == 45;
            if (owned >= MAX_PAGES) buttons.setItem(8, button(Items.BARRIER, "§7+ Page", "§7This chest has every extra page (" + MAX_PAGES + ")."));
            else {
                long price = PRICES[owned];
                boolean confirming = level.getServer().getTickCount() < confirmUntil;
                buttons.setItem(8, confirming
                        ? button(Items.DYE.pick(net.minecraft.world.item.DyeColor.LIME), "§a§lClick again to buy", "§7Pay §a" + Economy.format(price) + " §7for +" + (big ? 54 : 27) + " slots.")
                        : button(Items.EMERALD, "§a+ Page §7(" + Economy.format(price) + ")", "§7Adds " + (big ? "54 slots (27 to each half)" : "27 slots") + " to this chest only.",
                        "§7Extra pages: §e" + owned + "/" + MAX_PAGES, "§7The chest keeps them if you break it.", "§8Click twice to buy."));
            }
        }

        @Override
        public void clicked(int slotId, int button, ContainerInput type, Player player) {
            if (slotId >= buttonStart && slotId < invStart) {
                if (player instanceof ServerPlayer sp && type.name().equals("PICKUP")) press(sp, slotId - buttonStart);
                refreshButtons();
                sendAllDataToRemote();
                return;
            }
            super.clicked(slotId, button, type, player);
        }

        void press(ServerPlayer sp, int b) {
            switch (b) {
                case 0 -> {
                    sort(real);
                    sp.level().playSound(null, pos, SoundEvents.BUNDLE_INSERT, SoundSource.BLOCKS, 0.8f, 1.2f);
                }
                case 3 -> { if (view.page > 0) view.page--; }
                case 5 -> { if (view.page < pageCount() - 1) view.page++; }
                case 8 -> buy(sp);
                default -> { }
            }
        }

        void buy(ServerPlayer sp) {
            int owned = pagesOf(level, pos);
            if (owned >= MAX_PAGES) return;
            int now = level.getServer().getTickCount();
            if (now >= confirmUntil) {
                confirmUntil = now + 100; // 5 seconds to click again
                return;
            }
            confirmUntil = 0;
            long price = PRICES[owned];
            if (!Economy.take(level.getServer(), sp.getUUID(), price)) {
                Lobby.msg(sp, "§cYou need " + Economy.format(price) + " for another page.");
                return;
            }
            for (BlockEntity be : halves(level, pos)) {
                if (be instanceof ChestPagesAccess a) {
                    a.skyserver$setPages(owned + 1);
                    be.setChanged();
                }
            }
            Lobby.msg(sp, "§a+ Page bought for " + Economy.format(price) + "! §7This chest now has " + real.getContainerSize() + " slots.");
            sp.level().playSound(null, pos, SoundEvents.PLAYER_LEVELUP, SoundSource.BLOCKS, 0.6f, 1.6f);
        }

        @Override
        public ItemStack quickMoveStack(Player player, int index) {
            if (index < 0 || index >= slots.size() || (index >= buttonStart && index < invStart)) return ItemStack.EMPTY;
            Slot slot = slots.get(index);
            if (!slot.hasItem() || !slot.mayPickup(player)) return ItemStack.EMPTY;
            ItemStack stack = slot.getItem();
            if (index < buttonStart) {
                if (!moveItemStackTo(stack, invStart, invEnd, true)) return ItemStack.EMPTY;
            } else {
                insertAll(real, stack); // shift-click fills any page, not just this one
            }
            if (stack.isEmpty()) slot.setByPlayer(ItemStack.EMPTY);
            else slot.setChanged();
            return ItemStack.EMPTY;
        }

        @Override
        public boolean canTakeItemForPickAll(ItemStack stack, Slot slot) {
            return !(slot instanceof ButtonSlot) && slot.mayPickup(null) && super.canTakeItemForPickAll(stack, slot);
        }

        @Override
        public boolean stillValid(Player player) {
            return level.getBlockState(pos).is(Blocks.CHEST) && real.stillValid(player);
        }

        @Override
        public void removed(Player player) {
            super.removed(player);
            real.stopOpen(player);
        }
    }
}
