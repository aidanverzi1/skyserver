package com.aidan.skyserver.island;

import com.aidan.skyserver.Safe;
import com.aidan.skyserver.Skyserver;
import com.aidan.skyserver.lobby.Announce;
import com.aidan.skyserver.lobby.Lobby;
import com.aidan.skyserver.lobby.PlayerCache;
import com.aidan.skyserver.lobby.Skills;
import com.aidan.skyserver.lobby.Stacks;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.AbstractFurnaceBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.*;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * 5.26: COPPER GOLEMS that actually sort. Everything here is server-side; players use vanilla clients.
 *
 *  - Up to MAX_GOLEMS (3) golems per island. A 4th one (built or woken from a statue) falls apart into its
 *    copper block + carved pumpkin. Golems belong to the whole island: every member can manage them.
 *  - We take over the golem's brain (vanilla behaviours removed) and walk it ourselves with its own
 *    navigation, so it still walks, carries the item in its hand and opens chest lids. If it can't find a
 *    path (or gets stuck for 4 s) it hops there in a puff of smoke, so a golem never gets stuck for good.
 *  - LABELS: sneak-click a regular chest (empty hand, or an item that can't be placed) = label menu. Labels
 *    are items, the Sort categories (ChestPages.category) and/or "overflow". A labelled chest takes those
 *    items even when it's empty.
 *  - SORTERS take items out of the copper chest (the HUB) and deliver them: item label > category label >
 *    an unlabelled chest that already has that item > an overflow chest. Nothing fits = it stays in the hub
 *    (the hub menu counts those "homeless" items).
 *  - Island-wide upgrades 1-5 (money from the island fund first, plus copper from the buyer's inventory):
 *    carry, range, speed; Find it (2), Fetch + no-freeze (3), Furnace Tender (4), Courier (5).
 *  - FURNACE TENDER (one golem's job): smeltables from the hub into furnaces / blast furnaces / smokers in
 *    range, coal in when they run low, results out to their chests. Island members on the island share
 *    skill XP for what it takes out (Mining for metals, Farming for food).
 *  - ERRANDS any idle golem takes: Find it, Fetch, Tribute Runner (fills the island's pool in Wren's
 *    Tribute Chests - see Tribute.POOL), and the courier's send-off.
 *  - Items a golem is carrying are saved (golems.json), so a restart or an unloaded island never loses them;
 *    anything that truly has nowhere to go ends up in the hub's Lost & Found.
 *  - COURIER (level 5): parcels to any player, who accepts or declines them (sneak-click their copper chest,
 *    or /parcels). 4 copper ingots postage. Declined parcels go back to the sender.
 * Saved in config/skyserver/golems.json.
 */
public final class Golems {
    private Golems() {}

    public static final int MAX_GOLEMS = 3;
    public static final int MAX_LEVEL = 5;
    /** Upgrade price for level 1..5 (index = level - 1; level 1 is free). Money comes from the island fund first. */
    public static final long[] PRICES = {0, 5_000, 15_000, 40_000, 90_000};
    /** Copper for each level, in ingots (a copper block counts as 9), from the buyer's inventory. */
    public static final int[] COPPER = {0, 64, 128, 256, 512};
    /** Most items carried in one trip, by level. */
    public static final int[] CARRY = {16, 32, 64, 128, 256};
    /** How far from the hub copper chest golems will deliver, by level (blocks). */
    public static final int[] RANGE = {16, 24, 32, 40, 48};
    /** Walking speed, by level. */
    public static final double[] SPEED = {1.1, 1.1, 1.35, 1.35, 1.6};
    public static final int FIND_LEVEL = 2, FETCH_LEVEL = 3, NO_FREEZE_LEVEL = 3, FURNACE_LEVEL = 4, COURIER_LEVEL = 5;
    public static final int POSTAGE = 4;
    public static final int MAX_LABELS = 36;
    /** How far a golem looks for its hub (the nearest copper chest). */
    static final int HUB_SEARCH = 64;
    static final String TAG = "skyserver_golem";
    /** Skill XP per item a Furnace Tender takes out (before the skill's own rate), shared by members on the island. */
    static final double FURNACE_XP = 0.6;

    public static final String[] CATEGORY_NAMES = {"Building blocks", "Ores & minerals", "Farming & food", "Mob drops", "Tools", "Weapons",
            "Armour", "Potions & books", "Everything else"};
    static final Item[] CATEGORY_ICONS = {Items.BRICKS, Items.IRON_INGOT, Items.WHEAT, Items.BONE, Items.IRON_PICKAXE, Items.IRON_SWORD,
            Items.IRON_CHESTPLATE, Items.ENCHANTED_BOOK, Items.PAPER};

    enum Job { SORT, FURNACE }

    static final class Label {
        final Set<String> items = new LinkedHashSet<>();
        final Set<Integer> cats = new TreeSet<>();
        boolean overflow;

        boolean isEmpty() {
            return items.isEmpty() && cats.isEmpty() && !overflow;
        }

        int size() {
            return items.size() + cats.size();
        }
    }

    /** One step of a golem's plan: walk somewhere (or to a player), then do something. */
    static final class Stop {
        BlockPos pos;
        UUID follow;
        double reach = 2.2;
        /** Ticks to stand there after arriving (after the action). */
        int wait = 8;
        int timeout = 20 * 45;
        Consumer<GolemRec> arrive;
        String status;
        // progress
        int age, stall, noPath, waited;
        boolean done;
        double best = Double.MAX_VALUE;

        Stop(BlockPos pos, String status, Consumer<GolemRec> arrive) {
            this.pos = pos == null ? null : pos.immutable();
            this.status = status;
            this.arrive = arrive;
        }
    }

    static final class GolemRec {
        UUID uuid;
        Job job = Job.SORT;
        int trips;
        final List<ItemStack> bag = new ArrayList<>();
        // not saved
        int islandId;
        Mob mob;
        final Deque<Stop> plan = new ArrayDeque<>();
        Stop cur;
        String status = "Idle";
        long nextThink;
        BlockPos hub;
        long hubUntil;

        String label(int i) {
            if (mob != null && mob.hasCustomName()) return mob.getCustomName().getString();
            return "Golem " + (i + 1);
        }
    }

    /** Something any idle golem can do for the island. start() returns false if it couldn't. */
    interface Errand {
        boolean start(GolemRec g, ServerLevel level, Islands.Island is, IslandData d);
    }

    static final class IslandData {
        int level = 1;
        final List<GolemRec> golems = new ArrayList<>();
        final Map<Long, Label> labels = new HashMap<>();
        /** Lost & Found: things golems couldn't put anywhere (taken back in the hub menu). */
        final List<ItemStack> lost = new ArrayList<>();
        // not saved
        final Deque<Errand> errands = new ArrayDeque<>();
        int homeless;
        final Set<Long> fullChests = new LinkedHashSet<>();
    }

    static final class Parcel {
        long id;
        UUID from, to;
        String fromName = "", toName = "";
        boolean returned;
        long sent;
        final List<ItemStack> items = new ArrayList<>();
    }

    static final Map<Integer, IslandData> DATA = new HashMap<>();
    static final List<Parcel> PARCELS = new ArrayList<>();
    private static long nextParcel = 1;
    private static boolean loaded = false, dirty = false;
    static MinecraftServer server;
    private static long ticks = 0;

    static void dirty() {
        dirty = true;
    }

    // =====================================================================
    // Small helpers
    // =====================================================================

    static String id(Item i) {
        return BuiltInRegistries.ITEM.getKey(i).toString();
    }

    static String path(Item i) {
        return BuiltInRegistries.ITEM.getKey(i).getPath();
    }

    static Item item(String id) {
        return BuiltInRegistries.ITEM.getValue(Identifier.parse(id));
    }

    static String blockPath(BlockState st) {
        return BuiltInRegistries.BLOCK.getKey(st.getBlock()).getPath();
    }

    public static boolean isGolem(Entity e) {
        return e instanceof Mob && "copper_golem".equals(BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).getPath());
    }

    static boolean isOurs(Entity e) {
        return e != null && e.entityTags().contains(TAG);
    }

    static boolean isCopperChest(BlockState st) {
        return st.getBlock() instanceof ChestBlock && blockPath(st).endsWith("copper_chest");
    }

    static boolean isStatue(BlockState st) {
        return blockPath(st).contains("copper_golem_statue");
    }

    static Islands.Island islandById(int id) {
        for (Islands.Island is : Islands.all()) if (is.id == id) return is;
        return null;
    }

    static IslandData data(int island) {
        load();
        return DATA.computeIfAbsent(island, k -> new IslandData());
    }

    public static int level(Islands.Island is) {
        IslandData d = DATA.get(is.id);
        return d == null ? 1 : d.level;
    }

    static String where(BlockPos p) {
        return p.getX() + " " + p.getY() + " " + p.getZ();
    }

    /** Items that can't be placed but have a use of their own on a chest (or in the air) - those never open the label menu. */
    private static final Set<String> KEEP_USE = Set.of("item_frame", "glow_item_frame", "painting", "armor_stand", "end_crystal", "lead", "name_tag",
            "bone_meal", "flint_and_steel", "fire_charge", "ender_pearl", "ender_eye", "snowball", "egg", "blue_egg", "brown_egg", "wind_charge",
            "splash_potion", "lingering_potion", "trident", "experience_bottle", "bucket", "honeycomb", "brush");

    /** 5.26: does sneak-clicking a chest with this item open the label / hub menu? (Placeable things and items with their own use don't.) */
    public static boolean opensMenu(ItemStack held) {
        if (held.isEmpty()) return true;
        if (held.getItem() instanceof BlockItem) return false;
        String p = path(held.getItem());
        return !KEEP_USE.contains(p) && !p.endsWith("_bucket") && !p.endsWith("_spawn_egg") && !p.endsWith("_boat") && !p.endsWith("_raft")
                && !p.endsWith("minecart");
    }

    /** The container at a chest / copper chest (both halves of a double) or furnace. */
    static Container container(ServerLevel level, BlockPos pos) {
        BlockState st = level.getBlockState(pos);
        if (st.getBlock() instanceof ChestBlock cb) return ChestBlock.getContainer(cb, st, level, pos, true);
        return level.getBlockEntity(pos) instanceof Container c ? c : null;
    }

    /** How many of this stack fit into the container. */
    static int room(Container c, ItemStack s) {
        int room = 0;
        for (int i = 0; i < c.getContainerSize(); i++) {
            ItemStack in = c.getItem(i);
            if (in.isEmpty()) {
                if (c.canPlaceItem(i, s)) room += s.getMaxStackSize();
            } else if (ItemStack.isSameItemSameComponents(in, s)) room += Math.max(0, in.getMaxStackSize() - in.getCount());
            if (room >= s.getCount()) return room;
        }
        return room;
    }

    static boolean hasEmptySlot(Container c) {
        for (int i = 0; i < c.getContainerSize(); i++) if (c.getItem(i).isEmpty()) return true;
        return false;
    }

    static int countOf(Container c, Predicate<ItemStack> what) {
        int n = 0;
        for (int i = 0; i < c.getContainerSize(); i++) if (!c.getItem(i).isEmpty() && what.test(c.getItem(i))) n += c.getItem(i).getCount();
        return n;
    }

    /** Takes up to max items matching from the container into the bag. Returns how many. */
    static int takeInto(Container c, Predicate<ItemStack> what, int max, List<ItemStack> bag) {
        int got = 0;
        for (int i = 0; i < c.getContainerSize() && got < max; i++) {
            ItemStack in = c.getItem(i);
            if (in.isEmpty() || !what.test(in)) continue;
            ItemStack part = c.removeItem(i, Math.min(in.getCount(), max - got));
            got += part.getCount();
            addToBag(bag, part);
        }
        if (got > 0) {
            c.setChanged();
            dirty(); // what a golem carries is saved with it
        }
        return got;
    }

    static void addToBag(List<ItemStack> bag, ItemStack s) {
        for (ItemStack b : bag) {
            if (s.isEmpty()) return;
            if (ItemStack.isSameItemSameComponents(b, s) && b.getCount() < b.getMaxStackSize()) {
                int n = Math.min(s.getCount(), b.getMaxStackSize() - b.getCount());
                b.grow(n);
                s.shrink(n);
            }
        }
        while (!s.isEmpty()) bag.add(s.split(Math.min(s.getCount(), s.getMaxStackSize())));
    }

    static int bagCount(List<ItemStack> bag) {
        int n = 0;
        for (ItemStack s : bag) n += s.getCount();
        return n;
    }

    /** Every block entity position in range of center on this island whose block matches. Only loaded chunks. */
    static List<BlockPos> scan(ServerLevel level, Islands.Island is, BlockPos c, int range, Predicate<BlockState> what) {
        List<BlockPos> out = new ArrayList<>();
        long r2 = (long) range * range;
        for (int cx = (c.getX() - range) >> 4; cx <= (c.getX() + range) >> 4; cx++)
            for (int cz = (c.getZ() - range) >> 4; cz <= (c.getZ() + range) >> 4; cz++) {
                if (!level.hasChunk(cx, cz)) continue;
                for (BlockEntity be : new ArrayList<>(level.getChunk(cx, cz).getBlockEntities().values())) {
                    BlockPos p = be.getBlockPos();
                    if (p.distSqr(c) > r2 || !what.test(be.getBlockState())) continue;
                    if (Islands.islandAt(p.getX(), p.getZ()) != is || !is.inBorder(p.getX() + 0.5, p.getZ() + 0.5)) continue;
                    out.add(p.immutable());
                }
            }
        return out;
    }

    /** The half of a chest that labels are saved under (the same for both halves of a double chest). */
    static BlockPos canonical(ServerLevel level, BlockPos pos) {
        BlockPos best = null;
        for (BlockPos h : ChestPages.halfPositions(level, pos))
            if (best == null || h.getX() < best.getX() || h.getX() == best.getX() && (h.getZ() < best.getZ() || h.getZ() == best.getZ() && h.getY() < best.getY()))
                best = h;
        return best == null ? pos.immutable() : best;
    }

    /** Regular chests in range of the hub, one entry per chest (doubles once). */
    static List<BlockPos> chests(ServerLevel level, Islands.Island is, BlockPos hub, int range) {
        LinkedHashSet<BlockPos> out = new LinkedHashSet<>();
        for (BlockPos p : scan(level, is, hub, range, st -> st.is(Blocks.CHEST))) out.add(canonical(level, p));
        return new ArrayList<>(out);
    }

    /** A chest's label (either half; moved to the canonical half). create = make an empty one if there's none. */
    static Label label(IslandData d, ServerLevel level, BlockPos pos, boolean create) {
        BlockPos key = canonical(level, pos);
        Label l = d.labels.get(key.asLong());
        if (l == null) for (BlockPos h : ChestPages.halfPositions(level, pos)) {
            Label o = d.labels.remove(h.asLong());
            if (o != null) {
                if (l == null) l = o;
                else { l.items.addAll(o.items); l.cats.addAll(o.cats); l.overflow |= o.overflow; }
            }
        }
        if (l != null) d.labels.put(key.asLong(), l);
        if (l == null && create) {
            l = new Label();
            d.labels.put(key.asLong(), l);
        }
        return l;
    }

    // =====================================================================
    // Where things go
    // =====================================================================

    /** 0 = no good, 1 overflow, 2 unlabelled chest already holding it, 3 category label, 4 item label. */
    static int fit(Label l, Container c, ItemStack s) {
        if (l != null && l.items.contains(id(s.getItem()))) return 4;
        if (l != null && l.cats.contains(ChestPages.category(s))) return 3;
        if ((l == null || l.size() == 0) && countOf(c, x -> x.is(s.getItem())) > 0) return 2;
        if (l != null && l.overflow) return 1;
        return 0;
    }

    /** The best chest in range of the hub for this stack (with room for at least some of it), or null. */
    static BlockPos destination(ServerLevel level, Islands.Island is, IslandData d, BlockPos hub, ItemStack s) {
        return destination(level, d, hub, s, chests(level, is, hub, RANGE[d.level - 1]));
    }

    /** Same, with the chest list already worked out (sorting checks many items in one go). */
    static BlockPos destination(ServerLevel level, IslandData d, BlockPos hub, ItemStack s, List<BlockPos> cands) {
        BlockPos best = null;
        int bestFit = 0;
        double bestDist = 0;
        for (BlockPos p : cands) {
            Container c = container(level, p);
            if (c == null) continue;
            int f = fit(label(d, level, p, false), c, s);
            if (f == 0) continue;
            if (room(c, s) <= 0) {
                if (f >= 3) d.fullChests.add(p.asLong());
                continue;
            }
            d.fullChests.remove(p.asLong());
            double dist = p.distSqr(hub);
            if (f > bestFit || f == bestFit && dist < bestDist) {
                best = p;
                bestFit = f;
                bestDist = dist;
            }
        }
        return best;
    }

    /** The hub (nearest copper chest) for a golem, cached for 5 s. */
    static BlockPos hubFor(GolemRec g, ServerLevel level, Islands.Island is) {
        if (g.hub != null && ticks < g.hubUntil && isCopperChest(level.getBlockState(g.hub))) return g.hub;
        g.hub = nearestHub(level, is, g.mob.blockPosition());
        g.hubUntil = ticks + 100;
        return g.hub;
    }

    static BlockPos nearestHub(ServerLevel level, Islands.Island is, BlockPos near) {
        BlockPos best = null;
        for (BlockPos p : scan(level, is, near, HUB_SEARCH, Golems::isCopperChest))
            if (best == null || p.distSqr(near) < best.distSqr(near)) best = p;
        return best;
    }

    // =====================================================================
    // Smelting
    // =====================================================================

    private static final Set<String> ORES = Set.of("raw_iron", "raw_gold", "raw_copper", "iron_ore", "gold_ore", "copper_ore", "deepslate_iron_ore",
            "deepslate_gold_ore", "deepslate_copper_ore", "nether_gold_ore", "ancient_debris");
    private static final Set<String> FOODS = Set.of("beef", "porkchop", "chicken", "mutton", "rabbit", "cod", "salmon", "potato", "kelp");
    private static final Set<String> OTHER_SMELT = Set.of("sand", "red_sand", "clay_ball", "cactus", "wet_sponge", "cobblestone");
    private static final Set<String> FUELS = Set.of("coal", "charcoal", "coal_block", "blaze_rod");

    static boolean smeltable(ItemStack s) {
        String p = path(s.getItem());
        return ORES.contains(p) || FOODS.contains(p) || OTHER_SMELT.contains(p) && !p.equals("cobblestone");
    }

    /** Can this furnace (furnace / blast_furnace / smoker) smelt this item? */
    static boolean accepts(BlockState furnace, ItemStack s) {
        String f = blockPath(furnace), p = path(s.getItem());
        if (f.equals("blast_furnace")) return ORES.contains(p);
        if (f.equals("smoker")) return FOODS.contains(p);
        return smeltable(s);
    }

    static boolean isFuel(ItemStack s) {
        return FUELS.contains(path(s.getItem()));
    }

    /** Puts as much as fits into one slot. Returns how many went in. */
    static int putInSlot(Container f, int slot, ItemStack s) {
        if (s.isEmpty()) return 0;
        ItemStack in = f.getItem(slot);
        int n;
        if (in.isEmpty()) {
            if (!f.canPlaceItem(slot, s)) return 0;
            n = Math.min(s.getCount(), s.getMaxStackSize());
            f.setItem(slot, s.split(n));
        } else if (ItemStack.isSameItemSameComponents(in, s)) {
            n = Math.min(s.getCount(), in.getMaxStackSize() - in.getCount());
            if (n <= 0) return 0;
            in.grow(n);
            s.shrink(n);
        } else return 0;
        f.setChanged();
        return n;
    }

    /** Members on the island share skill XP for what the Furnace Tender takes out. */
    static void furnaceXp(Islands.Island is, ItemStack out) {
        String p = path(out.getItem());
        Skills.Skill skill = p.endsWith("_ingot") || p.equals("netherite_scrap") ? Skills.Skill.MINING
                : out.has(net.minecraft.core.component.DataComponents.FOOD) || p.equals("dried_kelp") ? Skills.Skill.FARMING : null;
        if (skill == null || server == null) return;
        List<ServerPlayer> here = new ArrayList<>();
        List<UUID> crew = new ArrayList<>(is.members);
        crew.add(is.owner);
        for (UUID u : crew) {
            ServerPlayer m = server.getPlayerList().getPlayer(u);
            if (m != null && Islands.isIsland(m.level()) && Islands.islandAt(m.getX(), m.getZ()) == is) here.add(m);
        }
        if (here.isEmpty()) return;
        double each = FURNACE_XP * out.getCount() / here.size();
        for (ServerPlayer m : here) Skills.add(m, skill, each);
    }

    // =====================================================================
    // Golems joining / leaving
    // =====================================================================

    static GolemRec recOf(UUID uuid) {
        load();
        for (Map.Entry<Integer, IslandData> e : DATA.entrySet())
            for (GolemRec g : e.getValue().golems) if (g.uuid.equals(uuid)) {
                g.islandId = e.getKey();
                return g;
            }
        return null;
    }

    private static void onLoad(Mob m, ServerLevel level) {
        if (m.isRemoved()) return;
        Islands.Island is = Islands.islandAt(m.getX(), m.getZ());
        if (is == null) return;
        GolemRec g = recOf(m.getUUID());
        if (g != null) {
            tame(g, m);
            return;
        }
        IslandData d = data(is.id);
        if (d.golems.size() >= MAX_GOLEMS) {
            refuse(m, level, is);
            return;
        }
        g = new GolemRec();
        g.uuid = m.getUUID();
        g.islandId = is.id;
        d.golems.add(g);
        tame(g, m);
        dirty();
        Islands.islandChat(is, "§6⚙ A copper golem joined your island's crew §7(" + d.golems.size() + "/" + MAX_GOLEMS
                + "). §fSneak-click a copper chest §7for the golem hub, §fsneak-click a chest §7to label it.");
    }

    /** Our golem: vanilla behaviours off (we walk it), never despawns, shows what it carries. */
    private static void tame(GolemRec g, Mob m) {
        g.mob = m;
        m.addTag(TAG);
        m.setPersistenceRequired();
        m.getBrain().removeAllBehaviors();
        m.getNavigation().stop();
        showBag(g);
    }

    /** A 4th golem: it falls apart into what it was built from. */
    private static void refuse(Mob m, ServerLevel level, Islands.Island is) {
        double x = m.getX(), y = m.getY(), z = m.getZ();
        m.discard();
        level.sendParticles(ParticleTypes.CLOUD, x, y + 0.5, z, 20, 0.3, 0.3, 0.3, 0.02);
        ItemStack block = new ItemStack(item("minecraft:copper_block"));
        level.addFreshEntity(new ItemEntity(level, x, y + 0.3, z, block));
        level.addFreshEntity(new ItemEntity(level, x, y + 0.3, z, new ItemStack(Items.CARVED_PUMPKIN)));
        for (ServerPlayer p : level.players())
            if (p.distanceToSqr(x, y, z) < 32 * 32) Lobby.msg(p, "§cAn island can have at most " + MAX_GOLEMS + " copper golems. §7This one fell apart into its copper block and pumpkin.");
    }

    /** A golem is gone for good: its bag goes back to the hub (or Lost & Found). */
    private static void forget(GolemRec g, String why) {
        IslandData d = data(g.islandId);
        d.golems.remove(g);
        ServerLevel level = Islands.level();
        Islands.Island is = islandById(g.islandId);
        if (!g.bag.isEmpty()) {
            if (level != null && is != null && g.mob != null) {
                BlockPos hub = nearestHub(level, is, g.mob.blockPosition());
                Container c = hub == null ? null : container(level, hub);
                if (c != null) for (ItemStack s : g.bag) ChestPages.insertAll(c, s);
            }
            for (ItemStack s : g.bag) if (!s.isEmpty()) d.lost.add(s);
            g.bag.clear();
        }
        g.mob = null;
        dirty();
        if (is != null && why != null) Islands.islandChat(is, why);
    }

    /** After a golem was removed: did it freeze into a statue? */
    private static void afterRemoved(GolemRec g, ServerLevel level, BlockPos at) {
        BlockPos statue = null;
        for (BlockPos p : BlockPos.betweenClosed(at.offset(-1, -1, -1), at.offset(1, 1, 1)))
            if (isStatue(level.getBlockState(p))) { statue = p.immutable(); break; }
        IslandData d = data(g.islandId);
        if (statue == null) {
            forget(g, null);
            return;
        }
        if (d.level >= NO_FREEZE_LEVEL) {
            // Level 3: it shakes the rust off and keeps working.
            EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getValue(Identifier.withDefaultNamespace("copper_golem"));
            Entity e = type.create(level, EntitySpawnReason.EVENT);
            if (e instanceof Mob m) {
                level.removeBlock(statue, false);
                m.snapTo(statue.getX() + 0.5, statue.getY(), statue.getZ() + 0.5, 0f, 0f);
                if (g.mob != null && g.mob.hasCustomName()) m.setCustomName(g.mob.getCustomName());
                g.uuid = m.getUUID();
                g.mob = null;
                g.cur = null;
                g.plan.clear();
                level.addFreshEntity(m);
                level.sendParticles(ParticleTypes.WAX_ON, m.getX(), m.getY() + 0.6, m.getZ(), 25, 0.4, 0.4, 0.4, 0.2);
                dirty();
                return;
            }
        }
        forget(g, "§7One of your copper golems froze into a statue. §8(Golem level " + NO_FREEZE_LEVEL
                + " stops that. Scrape the statue with an axe to wake it.)");
    }

    static void showBag(GolemRec g) {
        if (g.mob == null) return;
        g.mob.setItemSlot(EquipmentSlot.MAINHAND, g.bag.isEmpty() ? ItemStack.EMPTY : g.bag.get(0).copyWithCount(1));
    }

    // =====================================================================
    // Ticking: walking and thinking
    // =====================================================================

    private static void tick(MinecraftServer s) {
        server = s;
        ticks++;
        if (dirty && ticks % 600 == 0) saveNow(true);
        ServerLevel level = Islands.level();
        if (level == null || !loaded) return;
        for (Map.Entry<Integer, IslandData> e : DATA.entrySet()) {
            IslandData d = e.getValue();
            if (d.golems.isEmpty()) continue;
            Islands.Island is = null;
            for (GolemRec g : new ArrayList<>(d.golems)) {
                g.islandId = e.getKey();
                if (g.mob == null || g.mob.isRemoved()) {
                    g.mob = null;
                    if (ticks % 20 != 0) continue;
                    if (!(level.getEntity(g.uuid) instanceof Mob m) || !m.isAlive()) continue;
                    tame(g, m);
                }
                if (is == null) is = islandById(e.getKey());
                if (is == null) break;
                GolemRec gg = g;
                Islands.Island isl = is;
                Safe.run("golem", () -> step(gg, level, isl, d));
            }
        }
    }

    private static void step(GolemRec g, ServerLevel level, Islands.Island is, IslandData d) {
        if (g.cur == null) {
            g.cur = g.plan.poll();
            if (g.cur == null) {
                if (ticks >= g.nextThink) {
                    g.nextThink = ticks + 20;
                    think(g, level, is, d);
                    g.cur = g.plan.poll();
                }
                if (g.cur == null) return;
            }
            g.status = g.cur.status;
        }
        Stop st = g.cur;
        Mob m = g.mob;
        if (st.done) {
            // standing still after the action
            m.getNavigation().stop();
            if (++st.waited >= st.wait) g.cur = null;
            return;
        }
        double tx, ty, tz;
        if (st.follow != null) {
            ServerPlayer p = server.getPlayerList().getPlayer(st.follow);
            if (p == null || p.level() != level || Islands.islandAt(p.getX(), p.getZ()) != is || st.age > st.timeout) {
                g.cur = null; // gave up following - whatever it carries goes home on its next think
                return;
            }
            tx = p.getX();
            ty = p.getY();
            tz = p.getZ();
        } else {
            tx = st.pos.getX() + 0.5;
            ty = st.pos.getY();
            tz = st.pos.getZ() + 0.5;
        }
        double dx = m.getX() - tx, dy = m.getY() - ty, dz = m.getZ() - tz;
        double h2 = dx * dx + dz * dz;
        if (h2 <= st.reach * st.reach && Math.abs(dy) <= 2.5) {
            m.getNavigation().stop();
            m.getLookControl().setLookAt(tx, ty + 0.5, tz);
            st.done = true;
            if (st.arrive != null) st.arrive.accept(g);
            showBag(g);
            return;
        }
        st.age++;
        if (st.follow == null && st.age > st.timeout) {
            hop(m, level, st.pos);
            return;
        }
        if (st.age % 10 == 1) {
            boolean ok = m.getNavigation().moveTo(tx, ty, tz, SPEED[d.level - 1]);
            st.noPath = ok ? 0 : st.noPath + 1;
            if (st.noPath >= 3) {
                if (st.follow == null) hop(m, level, st.pos);
                else hop(m, level, BlockPos.containing(tx, ty, tz));
                st.noPath = 0;
                return;
            }
        }
        if (st.age % 40 == 0) {
            double dist = Math.sqrt(h2 + dy * dy);
            if (st.best - dist >= 0.5) {
                st.best = dist;
                st.stall = 0;
            } else if (++st.stall >= 2) {
                if (st.follow == null) hop(m, level, st.pos);
                else hop(m, level, BlockPos.containing(tx, ty, tz));
                st.stall = 0;
            }
        }
    }

    /** No path or stuck: the golem hops next to the spot in a puff of smoke. */
    static void hop(Mob m, ServerLevel level, BlockPos target) {
        BlockPos to = standSpot(level, target);
        level.sendParticles(ParticleTypes.CLOUD, m.getX(), m.getY() + 0.5, m.getZ(), 12, 0.3, 0.3, 0.3, 0.02);
        m.getNavigation().stop();
        m.teleportTo(to.getX() + 0.5, to.getY(), to.getZ() + 0.5);
        level.sendParticles(ParticleTypes.CLOUD, to.getX() + 0.5, to.getY() + 0.5, to.getZ() + 0.5, 12, 0.3, 0.3, 0.3, 0.02);
    }

    /** A free spot to stand next to (or on) a block. */
    static BlockPos standSpot(ServerLevel level, BlockPos pos) {
        for (int dy : new int[]{0, -1, 1})
            for (Direction dir : Direction.Plane.HORIZONTAL) {
                BlockPos p = pos.relative(dir).above(dy);
                if (free(level, p) && free(level, p.above()) && !level.getBlockState(p.below()).getCollisionShape(level, p.below()).isEmpty()) return p;
            }
        return pos.above();
    }

    private static boolean free(ServerLevel level, BlockPos p) {
        return level.getBlockState(p).getCollisionShape(level, p).isEmpty();
    }

    // ---- Deciding what to do next ----

    private static void think(GolemRec g, ServerLevel level, Islands.Island is, IslandData d) {
        if (!g.bag.isEmpty()) {
            deliverBag(g, level, is, d);
            return;
        }
        Errand e = d.errands.poll();
        while (e != null) {
            if (e.start(g, level, is, d)) return;
            e = d.errands.poll();
        }
        BlockPos hub = hubFor(g, level, is);
        if (hub == null) {
            g.status = "§cNo copper chest within " + HUB_SEARCH + " blocks";
            return;
        }
        if (g.job == Job.FURNACE && d.level >= FURNACE_LEVEL) {
            if (furnaceThink(g, level, is, d, hub)) return;
        } else if (sortThink(g, level, is, d, hub)) return;
        // Nothing to do: wait by the hub.
        if (g.mob.blockPosition().distSqr(hub) > 5 * 5) {
            Stop back = new Stop(hub, "Going back to the copper chest", null);
            back.reach = 3;
            back.wait = 0;
            g.plan.add(back);
        } else g.status = g.job == Job.FURNACE ? "Watching the furnaces" : "Waiting for items in the copper chest";
    }

    /** Sorter: take something out of the hub that has somewhere to go, and take it there. */
    private static boolean sortThink(GolemRec g, ServerLevel level, Islands.Island is, IslandData d, BlockPos hub) {
        Container hc = container(level, hub);
        if (hc == null) return false;
        boolean leaveSmeltables = hasTender(d) && !scan(level, is, hub, RANGE[d.level - 1], st -> st.getBlock() instanceof AbstractFurnaceBlock).isEmpty();
        int homeless = 0;
        List<BlockPos> cands = chests(level, is, hub, RANGE[d.level - 1]);
        Set<String> tried = new HashSet<>();
        for (int i = 0; i < hc.getContainerSize(); i++) {
            ItemStack s = hc.getItem(i);
            if (s.isEmpty() || leaveSmeltables && smeltable(s)) continue;
            if (!tried.add(id(s.getItem()) + s.getComponentsPatch())) {
                homeless += s.getCount(); // same thing as a stack we already found no home for
                continue;
            }
            BlockPos dest = destination(level, d, hub, s, cands);
            if (dest == null) {
                homeless += s.getCount();
                continue;
            }
            ItemStack sample = s.copyWithCount(1);
            int carry = CARRY[d.level - 1];
            g.plan.add(new Stop(hub, "Picking up " + s.getHoverName().getString(), gg -> {
                Container c = container(level, hub);
                if (c == null) return;
                ChestPages.golemLid(level, hub, 10);
                takeInto(c, x -> ItemStack.isSameItemSameComponents(x, sample), carry, gg.bag);
                if (!gg.bag.isEmpty()) gg.plan.addFirst(dropStop(gg, level, is, d, dest));
            }));
            return true;
        }
        d.homeless = homeless;
        return false;
    }

    /** Walk to a chest and put the bag in; whatever doesn't fit gets delivered somewhere else on the next think. */
    static Stop dropStop(GolemRec g, ServerLevel level, Islands.Island is, IslandData d, BlockPos dest) {
        String what = g.bag.isEmpty() ? "items" : g.bag.get(0).getHoverName().getString();
        return new Stop(dest, "Taking " + what + " to the chest at " + where(dest), gg -> {
            Container c = container(level, dest);
            if (c == null) return;
            ChestPages.golemLid(level, dest, 10);
            int before = bagCount(gg.bag);
            for (ItemStack s : gg.bag) ChestPages.insertAll(c, s);
            gg.bag.removeIf(ItemStack::isEmpty);
            if (bagCount(gg.bag) < before) {
                gg.trips++;
                dirty();
            }
        });
    }

    /** Whatever the golem is carrying goes to a chest that wants it, or back to the hub, or Lost & Found. */
    static void deliverBag(GolemRec g, ServerLevel level, Islands.Island is, IslandData d) {
        g.bag.removeIf(ItemStack::isEmpty);
        if (g.bag.isEmpty()) return;
        BlockPos hub = hubFor(g, level, is);
        if (hub != null) {
            BlockPos dest = destination(level, is, d, hub, g.bag.get(0));
            if (dest != null) {
                g.plan.add(dropStop(g, level, is, d, dest));
                return;
            }
        }
        BlockPos home = hub;
        g.plan.add(new Stop(home != null ? home : g.mob.blockPosition(), "Bringing things back", gg -> {
            Container c = home == null ? null : container(level, home);
            if (c != null) {
                ChestPages.golemLid(level, home, 10);
                for (ItemStack s : gg.bag) ChestPages.insertAll(c, s);
            }
            for (ItemStack s : gg.bag) if (!s.isEmpty()) d.lost.add(s);
            gg.bag.clear();
            dirty();
        }));
    }

    static boolean hasTender(IslandData d) {
        if (d.level < FURNACE_LEVEL) return false;
        for (GolemRec g : d.golems) if (g.job == Job.FURNACE) return true;
        return false;
    }

    /** Furnace Tender: results out, fuel in, smeltables in - in that order. */
    private static boolean furnaceThink(GolemRec g, ServerLevel level, Islands.Island is, IslandData d, BlockPos hub) {
        List<BlockPos> furnaces = scan(level, is, hub, RANGE[d.level - 1], st -> st.getBlock() instanceof AbstractFurnaceBlock);
        if (furnaces.isEmpty()) {
            g.status = "§cNo furnaces within " + RANGE[d.level - 1] + " blocks of the copper chest";
            return false;
        }
        int carry = CARRY[d.level - 1];
        // 1. Finished items out.
        for (BlockPos f : furnaces) {
            Container fc = container(level, f);
            if (fc == null || fc.getItem(2).isEmpty()) continue;
            g.plan.add(new Stop(f, "Taking finished items out of a furnace", gg -> {
                Container c = container(level, f);
                if (c == null || c.getItem(2).isEmpty()) return;
                ItemStack out = c.removeItem(2, Math.min(carry, c.getItem(2).getCount()));
                c.setChanged();
                level.playSound(null, f, SoundEvents.ITEM_PICKUP, SoundSource.BLOCKS, 0.5f, 1.2f);
                furnaceXp(is, out);
                addToBag(gg.bag, out);
                gg.trips++;
                dirty();
            }));
            return true;
        }
        // 2. Fuel for furnaces that have something to smelt but are (nearly) out.
        for (BlockPos f : furnaces) {
            Container fc = container(level, f);
            if (fc == null || fc.getItem(0).isEmpty()) continue;
            ItemStack fuel = fc.getItem(1);
            if (!fuel.isEmpty() && fuel.getCount() >= 8) continue;
            BlockPos src = null;
            Container hc = container(level, hub);
            if (hc != null && countOf(hc, x -> isFuel(x) && (fuel.isEmpty() || ItemStack.isSameItemSameComponents(x, fuel))) > 0) src = hub;
            if (src == null) for (BlockPos c : chests(level, is, hub, RANGE[d.level - 1])) {
                Container cc = container(level, c);
                if (cc != null && countOf(cc, x -> isFuel(x) && (fuel.isEmpty() || ItemStack.isSameItemSameComponents(x, fuel))) > 0) { src = c; break; }
            }
            if (src == null) continue;
            BlockPos from = src;
            g.plan.add(new Stop(from, "Getting fuel", gg -> {
                Container c = container(level, from);
                if (c == null) return;
                ChestPages.golemLid(level, from, 10);
                ItemStack now = container(level, f) == null ? ItemStack.EMPTY : container(level, f).getItem(1);
                takeInto(c, x -> isFuel(x) && (now.isEmpty() || ItemStack.isSameItemSameComponents(x, now)), 16, gg.bag);
                if (!gg.bag.isEmpty()) gg.plan.addFirst(new Stop(f, "Putting fuel in a furnace", g2 -> {
                    Container fc2 = container(level, f);
                    if (fc2 == null) return;
                    for (ItemStack s : g2.bag) putInSlot(fc2, 1, s);
                    g2.bag.removeIf(ItemStack::isEmpty);
                }));
            }));
            return true;
        }
        // 3. Smeltables from the hub into a furnace that takes them (same item first, then an empty one).
        Container hc = container(level, hub);
        if (hc == null) return false;
        for (int i = 0; i < hc.getContainerSize(); i++) {
            ItemStack s = hc.getItem(i);
            if (s.isEmpty() || !smeltable(s)) continue;
            BlockPos target = null;
            for (BlockPos f : furnaces) {
                Container fc = container(level, f);
                if (fc == null || !accepts(level.getBlockState(f), s)) continue;
                ItemStack in = fc.getItem(0);
                if (!in.isEmpty() && ItemStack.isSameItemSameComponents(in, s) && in.getCount() < in.getMaxStackSize()) { target = f; break; }
                if (in.isEmpty() && target == null) target = f;
            }
            if (target == null) continue;
            BlockPos to = target;
            ItemStack sample = s.copyWithCount(1);
            g.plan.add(new Stop(hub, "Picking up " + s.getHoverName().getString() + " to smelt", gg -> {
                Container c = container(level, hub);
                Container fc = container(level, to);
                if (c == null || fc == null) return;
                ItemStack in = fc.getItem(0);
                int room = in.isEmpty() ? sample.getMaxStackSize() : in.getMaxStackSize() - in.getCount();
                ChestPages.golemLid(level, hub, 10);
                takeInto(c, x -> ItemStack.isSameItemSameComponents(x, sample), Math.min(carry, room), gg.bag);
                if (!gg.bag.isEmpty()) gg.plan.addFirst(new Stop(to, "Loading a furnace", g2 -> {
                    Container fc2 = container(level, to);
                    if (fc2 == null) return;
                    for (ItemStack b : g2.bag) putInSlot(fc2, 0, b);
                    g2.bag.removeIf(ItemStack::isEmpty);
                }));
            }));
            return true;
        }
        return false;
    }

    // =====================================================================
    // Errands (from the menus)
    // =====================================================================

    /** All chests in range of the hub (and the hub itself) holding this item, most first. */
    static List<BlockPos> holding(ServerLevel level, Islands.Island is, IslandData d, BlockPos hub, Item item) {
        Map<BlockPos, Integer> n = new HashMap<>();
        List<BlockPos> all = chests(level, is, hub, RANGE[d.level - 1]);
        all.add(hub);
        for (BlockPos p : all) {
            Container c = container(level, p);
            if (c == null) continue;
            int k = countOf(c, x -> x.is(item));
            if (k > 0) n.put(p, k);
        }
        List<BlockPos> out = new ArrayList<>(n.keySet());
        out.sort((a, b) -> n.get(b) - n.get(a));
        return out;
    }

    /** Find it: sparkles on the chest right away, and a golem walks over and holds the lid open. */
    static void find(ServerPlayer p, Islands.Island is, BlockPos hub, Item item) {
        ServerLevel level = (ServerLevel) p.level();
        IslandData d = data(is.id);
        List<BlockPos> at = holding(level, is, d, hub, item);
        if (at.isEmpty()) {
            Lobby.msg(p, "§7None of that in the chests your golems can reach.");
            return;
        }
        BlockPos c = at.get(0);
        for (int i = 0; i < 6; i++) Lobby.runLaterTicks(i * 20, () -> level.sendParticles(ParticleTypes.WAX_ON, c.getX() + 0.5, c.getY() + 1.1, c.getZ() + 0.5, 15, 0.4, 0.3, 0.4, 0.05));
        Lobby.msg(p, "§6⚙ §fIt's in the chest at §e" + where(c) + (at.size() > 1 ? " §7(and " + (at.size() - 1) + " more)" : "") + "§f. A golem is going there.");
        d.errands.add((g, lvl, isl, dd) -> {
            Stop s = new Stop(c, "Showing where " + new ItemStack(item).getHoverName().getString() + " is", gg -> {
                ChestPages.golemLid(lvl, c, 100);
                lvl.sendParticles(ParticleTypes.WAX_ON, c.getX() + 0.5, c.getY() + 1.1, c.getZ() + 0.5, 25, 0.4, 0.3, 0.4, 0.05);
            });
            s.wait = 120;
            g.plan.add(s);
            return true;
        });
    }

    /** Fetch: a golem takes a stack from the fullest chest and brings it to the player. */
    static void fetch(ServerPlayer p, Islands.Island is, BlockPos hub, Item item) {
        ServerLevel level = (ServerLevel) p.level();
        IslandData d = data(is.id);
        List<BlockPos> at = holding(level, is, d, hub, item);
        if (at.isEmpty()) {
            Lobby.msg(p, "§7None of that in the chests your golems can reach.");
            return;
        }
        BlockPos c = at.get(0);
        UUID who = p.getUUID();
        String name = new ItemStack(item).getHoverName().getString();
        Lobby.msg(p, "§6⚙ §fA golem is fetching §e" + name + "§f for you. §7(Stay on the island.)");
        d.errands.add((g, lvl, isl, dd) -> {
            g.plan.add(new Stop(c, "Fetching " + name, gg -> {
                Container cc = container(lvl, c);
                if (cc == null) return;
                ChestPages.golemLid(lvl, c, 10);
                takeInto(cc, x -> x.is(item), item.getDefaultMaxStackSize(), gg.bag);
                if (gg.bag.isEmpty()) return;
                Stop bring = new Stop(null, "Bringing " + name + " to a player", g2 -> {
                    ServerPlayer to = server.getPlayerList().getPlayer(who);
                    if (to == null) return;
                    for (ItemStack s : g2.bag) {
                        to.getInventory().add(s);
                        if (!s.isEmpty()) to.drop(s.copy(), false, net.minecraft.util.Prediction.SERVER_ONLY);
                        s.setCount(0);
                    }
                    g2.bag.clear();
                    to.level().playSound(null, to.getX(), to.getY(), to.getZ(), SoundEvents.ITEM_PICKUP, SoundSource.PLAYERS, 0.6f, 1.4f);
                    Announce.actionBar(to, "§6⚙ §fYour golem brought you §e" + name);
                });
                bring.follow = who;
                bring.reach = 2.0;
                bring.timeout = 20 * 60;
                gg.plan.addFirst(bring);
            }));
            return true;
        });
    }

    /**
     * Tribute Runner: gathers what the island pool still needs for that Sunken Monument difficulty from the
     * hub and the chests in range, then carries it all to Captain Wren's ship room.
     */
    static String tribute(ServerPlayer p, Islands.Island is, BlockPos hub, int diff) {
        if (is.wrenRoom == null) return "§cPlace Captain Wren's ship room first (§e/is shiproom place§c).";
        ServerLevel level = (ServerLevel) p.level();
        IslandData d = data(is.id);
        Map<Tribute.Need, Integer> want = new LinkedHashMap<>();
        for (Tribute.Need n : Tribute.NEEDS) {
            int w = n.amount(diff) - Tribute.poolCount(is.id, n);
            if (w > 0) want.put(n, w);
        }
        if (want.isEmpty()) return "§aThe island pool already has that whole fare.";
        List<BlockPos> sources = new ArrayList<>();
        sources.add(hub);
        sources.addAll(chests(level, is, hub, RANGE[d.level - 1]));
        int available = 0;
        Map<Tribute.Need, Integer> left = new LinkedHashMap<>(want);
        List<BlockPos> stops = new ArrayList<>();
        for (BlockPos s : sources) {
            Container c = container(level, s);
            if (c == null) continue;
            boolean use = false;
            for (Map.Entry<Tribute.Need, Integer> e : left.entrySet()) {
                if (e.getValue() <= 0) continue;
                int have = countOf(c, x -> e.getKey().match().test(x.getItem()));
                if (have <= 0) continue;
                int take = Math.min(have, e.getValue());
                e.setValue(e.getValue() - take);
                available += take;
                use = true;
            }
            if (use) stops.add(s);
        }
        if (stops.isEmpty()) return "§7Your golems can't find any of the missing fare items in their chests.";
        BlockPos ship = new BlockPos(is.wrenRoom[0], is.wrenRoom[1], is.wrenRoom[2]);
        Map<Tribute.Need, Integer> still = new LinkedHashMap<>(want);
        UUID who = p.getUUID();
        d.errands.add((g, lvl, isl, dd) -> {
            for (BlockPos s : stops) g.plan.add(new Stop(s, "Gathering Sunken fare items", gg -> {
                Container c = container(lvl, s);
                if (c == null) return;
                ChestPages.golemLid(lvl, s, 10);
                for (Map.Entry<Tribute.Need, Integer> e : still.entrySet()) {
                    if (e.getValue() <= 0) continue;
                    int got = takeInto(c, x -> e.getKey().match().test(x.getItem()), e.getValue(), gg.bag);
                    e.setValue(e.getValue() - got);
                }
            }));
            Stop drop = new Stop(ship, "Carrying the fare to Captain Wren's ship room", gg -> {
                int moved = 0;
                for (ItemStack s : gg.bag) moved += Tribute.poolDeposit(isl.id, s);
                gg.bag.removeIf(ItemStack::isEmpty);
                lvl.playSound(null, ship, SoundEvents.BUNDLE_INSERT, SoundSource.BLOCKS, 0.8f, 1f);
                List<String> miss = new ArrayList<>();
                for (Tribute.Need n : Tribute.NEEDS) {
                    int need = n.amount(diff) - Tribute.poolCount(isl.id, n);
                    if (need > 0) miss.add(n.name() + " " + need);
                }
                String[] dn = {"Easy", "Normal", "Hard", "Nightmare"};
                Islands.islandChat(isl, "§6⚙ §fA golem put §e" + moved + " §ffare items in the island pool (Tribute Chests). "
                        + (miss.isEmpty() ? "§aThe " + dn[diff] + " fare is ready for anyone on the island!" : "§7Still missing for " + dn[diff] + ": §c" + String.join(", ", miss)));
                gg.trips++;
                dirty();
            });
            drop.reach = 3;
            drop.timeout = 20 * 20;
            g.plan.add(drop);
            return true;
        });
        return "§6⚙ §fA golem will gather §e" + available + " §ffare items and take them to Wren's ship room."
                + (available < sum(want) ? " §7(Your chests don't have everything - " + (sum(want) - available) + " still missing.)" : "");
    }

    private static int sum(Map<Tribute.Need, Integer> m) {
        int t = 0;
        for (int v : m.values()) t += v;
        return t;
    }

    // =====================================================================
    // Upgrades
    // =====================================================================

    /** Copper ingots the player carries (a copper block counts as 9). */
    static int copperIn(ServerPlayer p) {
        int n = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack s = p.getInventory().getItem(i);
            if (s.isEmpty() || s.has(net.minecraft.core.component.DataComponents.CUSTOM_DATA)) continue;
            String id = path(s.getItem());
            if (id.equals("copper_ingot")) n += s.getCount();
            else if (id.equals("copper_block")) n += s.getCount() * 9;
        }
        return n;
    }

    /** Takes this much copper (ingots first, then blocks; change comes back as ingots). False = not enough, nothing taken. */
    static boolean takeCopper(ServerPlayer p, int ingots) {
        if (copperIn(p) < ingots) return false;
        int left = ingots;
        for (String want : new String[]{"copper_ingot", "copper_block"})
            for (int i = 0; i < 36 && left > 0; i++) {
                ItemStack s = p.getInventory().getItem(i);
                if (s.isEmpty() || s.has(net.minecraft.core.component.DataComponents.CUSTOM_DATA) || !path(s.getItem()).equals(want)) continue;
                int per = want.equals("copper_block") ? 9 : 1;
                int use = Math.min(s.getCount(), (left + per - 1) / per);
                s.shrink(use);
                left -= use * per;
            }
        if (left < 0) {
            ItemStack change = new ItemStack(Items.COPPER_INGOT, -left);
            if (!p.getInventory().add(change) || !change.isEmpty()) p.drop(change, false, net.minecraft.util.Prediction.SERVER_ONLY);
        }
        p.getInventory().setChanged();
        return true;
    }

    public static String buyUpgrade(ServerPlayer p, Islands.Island is) {
        IslandData d = data(is.id);
        if (d.level >= MAX_LEVEL) return "§7Your copper golems are fully upgraded.";
        int next = d.level + 1;
        int copper = COPPER[next - 1];
        if (copperIn(p) < copper) return "§cGolem level " + next + " needs §6" + copper + " copper ingots §c(blocks count as 9) in your inventory. §7You have " + copperIn(p) + ".";
        if (!Islands.payUpgrade(p, is, PRICES[next - 1])) return "§cGolem level " + next + " costs " + com.aidan.skyserver.lobby.Economy.format(PRICES[next - 1]) + Islands.fundNote(is) + ".";
        takeCopper(p, copper);
        d.level = next;
        dirty();
        Islands.islandChat(is, "§6⚙ §f" + p.getName().getString() + " upgraded the island's copper golems to §elevel " + next + "§f! §7" + unlockText(next));
        return null;
    }

    static String unlockText(int lvl) {
        String base = "Carry " + CARRY[lvl - 1] + ", range " + RANGE[lvl - 1] + " blocks" + (SPEED[lvl - 1] > SPEED[Math.max(0, lvl - 2)] ? ", faster" : "");
        return switch (lvl) {
            case FIND_LEVEL -> base + ", unlocks Find it.";
            case FETCH_LEVEL -> base + ", unlocks Fetch and no more freezing into statues.";
            case FURNACE_LEVEL -> base + ", unlocks the Furnace Tender job.";
            case COURIER_LEVEL -> base + ", unlocks the Golem Courier (parcels).";
            default -> base + ".";
        };
    }

    // =====================================================================
    // Courier parcels
    // =====================================================================

    static List<Parcel> parcelsFor(UUID who) {
        load();
        List<Parcel> out = new ArrayList<>();
        for (Parcel p : PARCELS) if (p.to.equals(who)) out.add(p);
        return out;
    }

    static void send(ServerPlayer from, UUID to, String toName, List<ItemStack> items) {
        Parcel p = new Parcel();
        p.id = nextParcel++;
        p.from = from.getUUID();
        p.fromName = from.getName().getString();
        p.to = to;
        p.toName = toName;
        p.sent = System.currentTimeMillis();
        for (ItemStack s : items) if (!s.isEmpty()) p.items.add(s.copy());
        PARCELS.add(p);
        saveNow(true); // right away: these items exist nowhere else now
        ServerPlayer t = server.getPlayerList().getPlayer(to);
        if (t != null) {
            Lobby.msg(t, "§6📦 " + p.fromName + " sent you a parcel by copper golem! §7Sneak-click your copper chest (or §e/parcels§7) on your island to take it.");
            t.level().playSound(null, t.getX(), t.getY(), t.getZ(), SoundEvents.ITEM_PICKUP, SoundSource.PLAYERS, 0.8f, 0.8f);
        }
        // A golem on the sender's island sees it off.
        Islands.Island is = Islands.islandOf(from.getUUID());
        if (is != null && Islands.isIsland(from.level())) {
            BlockPos hub = nearestHub((ServerLevel) from.level(), is, from.blockPosition());
            if (hub != null) data(is.id).errands.add((g, lvl, isl, dd) -> {
                Stop s = new Stop(hub, "Sending a parcel to " + toName, gg -> {
                    lvl.sendParticles(ParticleTypes.CLOUD, gg.mob.getX(), gg.mob.getY() + 0.6, gg.mob.getZ(), 20, 0.4, 0.4, 0.4, 0.05);
                    lvl.playSound(null, hub, SoundEvents.BUNDLE_INSERT, SoundSource.BLOCKS, 1f, 0.8f);
                });
                s.wait = 20;
                g.plan.add(s);
                return true;
            });
        }
    }

    /** Main-inventory slots that are empty. */
    static int freeSlots(ServerPlayer p) {
        int n = 0;
        for (int i = 0; i < 36; i++) if (p.getInventory().getItem(i).isEmpty()) n++;
        return n;
    }

    static boolean canTakeParcels(ServerPlayer p) {
        return Islands.isIsland(p.level()) && Islands.islandAt(p.getX(), p.getZ()) != null
                && !com.aidan.skyserver.expedition.ExpeditionTest.isTesting(p.getUUID());
    }

    static String accept(ServerPlayer p, Parcel parcel) {
        if (!PARCELS.contains(parcel) || !parcel.to.equals(p.getUUID())) return "§7That parcel is gone.";
        if (!canTakeParcels(p)) return "§cTake parcels on a Sky Island.";
        if (freeSlots(p) < parcel.items.size()) return "§cMake room first: that parcel needs " + parcel.items.size() + " empty inventory slots.";
        PARCELS.remove(parcel);
        for (ItemStack s : parcel.items) {
            ItemStack c = s.copy();
            if (!p.getInventory().add(c) || !c.isEmpty()) p.drop(c, false, net.minecraft.util.Prediction.SERVER_ONLY);
        }
        saveNow(true);
        p.level().playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.ITEM_PICKUP, SoundSource.PLAYERS, 0.8f, 1.2f);
        ServerPlayer from = server.getPlayerList().getPlayer(parcel.from);
        if (from != null && !parcel.returned) Lobby.msg(from, "§6📦 " + p.getName().getString() + " took your parcel.");
        return "§aYou took the parcel from " + parcel.fromName + ".";
    }

    static String decline(ServerPlayer p, Parcel parcel) {
        if (!PARCELS.contains(parcel) || !parcel.to.equals(p.getUUID())) return "§7That parcel is gone.";
        if (parcel.returned) return "§7That's your own parcel coming back - take it.";
        parcel.returned = true;
        UUID f = parcel.from;
        String fn = parcel.fromName;
        parcel.from = parcel.to;
        parcel.fromName = p.getName().getString();
        parcel.to = f;
        parcel.toName = fn;
        saveNow(true);
        ServerPlayer o = server.getPlayerList().getPlayer(f);
        if (o != null) Lobby.msg(o, "§6📦 " + p.getName().getString() + " sent your parcel back. §7Sneak-click your copper chest (or §e/parcels§7) to take it.");
        return "§7Sent the parcel back to " + fn + ".";
    }

    // =====================================================================
    // Saving
    // =====================================================================

    static void load() {
        if (loaded) return;
        if (server == null) {
            ServerLevel l = Islands.level();
            if (l == null) return;
            server = l.getServer();
        }
        loaded = true;
        DATA.clear();
        PARCELS.clear();
        JsonObject root = Stacks.readFile("golems.json");
        try {
            if (root.has("islands")) for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("islands").entrySet()) {
                JsonObject o = e.getValue().getAsJsonObject();
                IslandData d = new IslandData();
                d.level = Math.max(1, Math.min(MAX_LEVEL, o.has("level") ? o.get("level").getAsInt() : 1));
                if (o.has("golems")) for (JsonElement ge : o.getAsJsonArray("golems")) {
                    JsonObject go = ge.getAsJsonObject();
                    GolemRec g = new GolemRec();
                    g.uuid = UUID.fromString(go.get("uuid").getAsString());
                    g.job = go.has("job") && "FURNACE".equals(go.get("job").getAsString()) ? Job.FURNACE : Job.SORT;
                    g.trips = go.has("trips") ? go.get("trips").getAsInt() : 0;
                    g.islandId = Integer.parseInt(e.getKey());
                    if (go.has("bag")) for (JsonElement b : go.getAsJsonArray("bag")) addToBag(g.bag, Stacks.fromJson(server, b));
                    d.golems.add(g);
                }
                if (o.has("labels")) for (Map.Entry<String, JsonElement> le : o.getAsJsonObject("labels").entrySet()) {
                    JsonObject lo = le.getValue().getAsJsonObject();
                    Label l = new Label();
                    if (lo.has("items")) for (JsonElement x : lo.getAsJsonArray("items")) l.items.add(x.getAsString());
                    if (lo.has("cats")) for (JsonElement x : lo.getAsJsonArray("cats")) l.cats.add(x.getAsInt());
                    l.overflow = lo.has("overflow") && lo.get("overflow").getAsBoolean();
                    if (!l.isEmpty()) d.labels.put(Long.parseLong(le.getKey()), l);
                }
                if (o.has("lost")) for (JsonElement x : o.getAsJsonArray("lost")) {
                    ItemStack s = Stacks.fromJson(server, x);
                    if (!s.isEmpty()) d.lost.add(s);
                }
                DATA.put(Integer.parseInt(e.getKey()), d);
            }
            if (root.has("parcels")) for (JsonElement pe : root.getAsJsonArray("parcels")) {
                JsonObject o = pe.getAsJsonObject();
                Parcel p = new Parcel();
                p.id = o.get("id").getAsLong();
                p.from = UUID.fromString(o.get("from").getAsString());
                p.to = UUID.fromString(o.get("to").getAsString());
                p.fromName = o.has("fromName") ? o.get("fromName").getAsString() : "";
                p.toName = o.has("toName") ? o.get("toName").getAsString() : "";
                p.returned = o.has("returned") && o.get("returned").getAsBoolean();
                p.sent = o.has("sent") ? o.get("sent").getAsLong() : 0;
                for (JsonElement x : o.getAsJsonArray("items")) {
                    ItemStack s = Stacks.fromJson(server, x);
                    if (!s.isEmpty()) p.items.add(s);
                }
                PARCELS.add(p);
                nextParcel = Math.max(nextParcel, p.id + 1);
            }
        } catch (RuntimeException ex) {
            Skyserver.LOGGER.warn("Couldn't read golems.json fully: {}", ex.toString());
        }
        Skyserver.LOGGER.info("[Golems] {} islands with golem data, {} parcels waiting", DATA.size(), PARCELS.size());
    }

    private static JsonObject snapshot() {
        JsonObject root = new JsonObject();
        JsonObject isl = new JsonObject();
        DATA.forEach((id, d) -> {
            if (d.golems.isEmpty() && d.labels.isEmpty() && d.lost.isEmpty() && d.level <= 1) return;
            JsonObject o = new JsonObject();
            o.addProperty("level", d.level);
            JsonArray ga = new JsonArray();
            for (GolemRec g : d.golems) {
                JsonObject go = new JsonObject();
                go.addProperty("uuid", g.uuid.toString());
                go.addProperty("job", g.job.name());
                go.addProperty("trips", g.trips);
                JsonArray bag = new JsonArray();
                for (ItemStack s : g.bag) if (!s.isEmpty()) bag.add(Stacks.toJson(server, s));
                if (!bag.isEmpty()) go.add("bag", bag);
                ga.add(go);
            }
            o.add("golems", ga);
            JsonObject lo = new JsonObject();
            d.labels.forEach((k, l) -> {
                if (l.isEmpty()) return;
                JsonObject x = new JsonObject();
                JsonArray items = new JsonArray();
                l.items.forEach(items::add);
                JsonArray cats = new JsonArray();
                l.cats.forEach(cats::add);
                x.add("items", items);
                x.add("cats", cats);
                x.addProperty("overflow", l.overflow);
                lo.add(String.valueOf(k), x);
            });
            o.add("labels", lo);
            JsonArray lost = new JsonArray();
            for (ItemStack s : d.lost) if (!s.isEmpty()) lost.add(Stacks.toJson(server, s));
            o.add("lost", lost);
            isl.add(String.valueOf(id), o);
        });
        root.add("islands", isl);
        JsonArray pa = new JsonArray();
        for (Parcel p : PARCELS) {
            JsonObject o = new JsonObject();
            o.addProperty("id", p.id);
            o.addProperty("from", p.from.toString());
            o.addProperty("to", p.to.toString());
            o.addProperty("fromName", p.fromName);
            o.addProperty("toName", p.toName);
            o.addProperty("returned", p.returned);
            o.addProperty("sent", p.sent);
            JsonArray items = new JsonArray();
            for (ItemStack s : p.items) items.add(Stacks.toJson(server, s));
            o.add("items", items);
            pa.add(o);
        }
        root.add("parcels", pa);
        return root;
    }

    static void saveNow(boolean background) {
        if (!loaded || server == null) return;
        dirty = false;
        JsonObject snap = snapshot();
        if (background) PlayerCache.io(() -> Stacks.writeFile("golems.json", snap));
        else Stacks.writeFile("golems.json", snap);
    }

    // =====================================================================
    // Setup
    // =====================================================================

    public static void init() {
        ServerLifecycleEvents.SERVER_STARTED.register(s -> {
            server = s;
            Safe.run("golems load", Golems::load);
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(s -> Safe.run("golems save", () -> saveNow(false)));
        ServerTickEvents.END_SERVER_TICK.register(s -> Safe.run("golems tick", () -> tick(s)));

        // Golems appearing on an island: ours (again), a new crew member, or one too many.
        ServerEntityEvents.ENTITY_LOAD.register((entity, world) -> {
            if (!isGolem(entity) || !Islands.isIsland(world)) return;
            Lobby.runLater(() -> Safe.run("golem load", () -> onLoad((Mob) entity, (ServerLevel) world)));
        });
        ServerEntityEvents.ENTITY_UNLOAD.register((entity, world) -> {
            if (!isOurs(entity) || !Islands.isIsland(world)) return;
            GolemRec g = recOf(entity.getUUID());
            if (g == null) return;
            Entity.RemovalReason why = entity.getRemovalReason();
            g.cur = null;
            g.plan.clear();
            if (why == Entity.RemovalReason.DISCARDED) {
                BlockPos at = entity.blockPosition();
                Lobby.runLater(() -> Safe.run("golem statue", () -> afterRemoved(g, (ServerLevel) world, at)));
            } else g.mob = null; // unloaded with the island - it carries on when it loads again
        });
        // Only island members can hurt their golems (to get rid of one); /kill and the void still work.
        ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> {
            if (!isOurs(entity)) return true;
            if (source.is(DamageTypeTags.BYPASSES_INVULNERABILITY)) return true;
            Islands.Island is = Islands.islandAt(entity.getX(), entity.getZ());
            return source.getEntity() instanceof ServerPlayer p && is != null && is.isMember(p.getUUID());
        });
        ServerLivingEntityEvents.ALLOW_DEATH.register((entity, source, amount) -> {
            if (!isOurs(entity)) return true;
            Safe.run("golem death", () -> {
                ((Mob) entity).setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY); // the bag is the real thing, not what it holds
                GolemRec g = recOf(entity.getUUID());
                if (g != null) forget(g, "§7One of your copper golems broke. §8(" + (data(g.islandId).golems.size()) + "/" + MAX_GOLEMS + " left)");
            });
            return true;
        });
        // Right-click a golem with an empty hand: what is it doing? (Also stops vanilla taking its item.)
        UseEntityCallback.EVENT.register((player, level, hand, entity, hit) -> {
            if (level.isClientSide() || !isOurs(entity) || !(player instanceof ServerPlayer sp)) return InteractionResult.PASS;
            if (!sp.getItemInHand(hand).isEmpty()) return InteractionResult.PASS; // name tags, honeycomb, axes...
            if (hand == InteractionHand.MAIN_HAND) {
                GolemRec g = recOf(entity.getUUID());
                if (g != null) Announce.actionBar(sp, "§6⚙ §f" + (g.job == Job.FURNACE ? "Furnace Tender" : "Sorter") + "§7: " + g.status
                        + (g.bag.isEmpty() ? "" : " §8(carrying " + bagCount(g.bag) + ")"));
            }
            return InteractionResult.FAIL;
        });
        // Sneak-click a copper chest: the golem hub.
        UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
            if (level.isClientSide() || !Islands.isIsland(level) || !(player instanceof ServerPlayer sp) || !sp.isSecondaryUseActive()) return InteractionResult.PASS;
            BlockPos pos = hit.getBlockPos();
            if (!isCopperChest(level.getBlockState(pos)) || !opensMenu(sp.getItemInHand(hand))) return InteractionResult.PASS;
            if (hand != InteractionHand.MAIN_HAND) return InteractionResult.SUCCESS;
            return GolemMenus.openHub(sp, (ServerLevel) level, pos) ? InteractionResult.SUCCESS : InteractionResult.PASS;
        });
        ServerPlayConnectionEvents.JOIN.register((h, sender, s) -> {
            ServerPlayer p = h.getPlayer();
            Lobby.runLaterTicks(120, () -> Safe.run("parcel notice", () -> {
                int n = parcelsFor(p.getUUID()).size();
                if (n > 0 && !p.hasDisconnected()) Lobby.msg(p, "§6📦 You have " + n + " parcel" + (n == 1 ? "" : "s") + " waiting! §7Sneak-click your copper chest (or §e/parcels§7) on your island.");
            }));
        });
        CommandRegistrationCallback.EVENT.register((d, r, e) -> d.register(Commands.literal("parcels").executes(c -> {
            ServerPlayer p = c.getSource().getPlayerOrException();
            if (!canTakeParcels(p)) {
                Lobby.msg(p, "§cOpen your parcels on a Sky Island (§e/is§c).");
                return 0;
            }
            GolemMenus.openParcels(p, null);
            return 1;
        })));
    }
}
