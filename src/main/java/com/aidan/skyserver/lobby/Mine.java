package com.aidan.skyserver.lobby;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LanternBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * The lobby Mine (under the Town). You get a pickaxe and a shovel when you walk in. Mining an ore gives
 * its drop (sent to your Sky Island - type /claim there) and Mining XP; the ore turns to stone and grows
 * back later. Each gallery opens at a Mining level; rarer ores have fewer blocks and grow back slower.
 *
 * 5.17:
 *  - ORES GROW BACK ONE AT A TIME per gallery, oldest first, on a steady clock: every
 *    REGROW_SECONDS[k] x 1.125 / COUNTS[k] seconds (the same most-per-hour as before, when each ore had
 *    its own 1x-1.25x timer). A hologram at each gallery entrance shows when the next ore comes back.
 *  - GRAVEL PIT: a gallery before the Coal Seam (x 214-222), open to everyone. Gravel never drops gravel;
 *    each block has a FLINT_CHANCE % chance of one flint (sent to your island like ores), at most
 *    FLINT_PER_HOUR flint per player per real clock hour (resets on the hour). Mining XP only for flint
 *    (FLINT_XP, far less than ores). One broken gravel block grows back every second. The pit is dug out
 *    by this class when the lobby loads (and dug again if the lobby is ever rebuilt over it).
 */
public final class Mine {
    public static final String[] GALLERY_NAMES = TownDesign.GALLERY_NAMES;
    public static final int[] LEVELS = TownDesign.MINE_LEVELS;
    public static final int[] COUNTS = TownDesign.MINE_COUNTS;
    public static final int[] REGROW_SECONDS = TownDesign.MINE_REGROW_SECONDS;
    public static final int[] XP = TownDesign.MINE_XP;
    public static final String[] ORE_BLOCKS = TownDesign.MINE_ORES;
    static final String[] MARKERS = TownDesign.MINE_MARKERS;
    private static final Item[] DROPS = {Items.COAL, Items.RAW_IRON, Items.RAW_COPPER, Items.RAW_GOLD, Items.REDSTONE, Items.LAPIS_LAZULI, Items.DIAMOND, Items.EMERALD};
    private static final int[][] DROP_COUNT = {{1, 1}, {1, 1}, {2, 4}, {1, 1}, {3, 5}, {3, 6}, {1, 1}, {1, 1}};
    static final String[][] UNLOCK_TEXT = {{"5", "Iron in the Mine"}, {"10", "Copper in the Mine, an iron pickaxe"}, {"15", "Gold in the Mine"},
            {"20", "Redstone in the Mine"}, {"25", "Lapis in the Mine"}, {"30", "a diamond pickaxe"}, {"35", "Diamonds in the Mine"},
            {"45", "a netherite pickaxe"}, {"50", "Emeralds in the Mine"}};

    /** 5.17: one ore per gallery every (regrow x this / count) seconds. 1.125 = the old average timer (1x to 1.25x). */
    static final double REGROW_SPREAD = 1.125;

    // ---- 5.17 Gravel Pit (edit here) ----
    public static final int FLINT_CHANCE = 20;      // % per gravel block
    public static final int FLINT_PER_HOUR = 15;    // per player per clock hour
    public static final int FLINT_XP = 12;          // Mining XP per flint (coal ore gives 36)
    public static final int GRAVEL_REGROW_TICKS = 20;
    public static final int GRAVEL_COUNT = 20;
    static final int PIT_X1 = TownDesign.GALLERY_X0 - TownDesign.GALLERY_STEP;     // 214
    static final int PIT_X2 = PIT_X1 + TownDesign.GALLERY_W - 1;                   // 222
    static final int PIT_Z1 = TownDesign.HALL_Z2 + 1, PIT_Z2 = TownDesign.GALLERY_Z2; // 132 .. 146
    static final int FLOOR = TownDesign.MINE_FLOOR;

    private static final Map<Long, Integer> SLOT_GALLERY = new HashMap<>();   // ore slot -> gallery
    private static final List<ArrayDeque<Long>> MINED = new ArrayList<>();     // per gallery: mined slots, oldest first
    private static long[] nextRegrow;                                          // per gallery: tick the next ore comes back
    private static boolean[] restored;                                         // per gallery: checked after a restart
    private static final Set<Long> GRAVEL_SLOTS = new HashSet<>();
    private static final ArrayDeque<Long> GRAVEL_MINED = new ArrayDeque<>();
    private static long nextGravel = 0;
    private static boolean pitDug = false;
    private static final Map<UUID, Object[]> FLINT_HOUR = new HashMap<>();    // player -> {hour key, flint this hour}
    private static final List<Holograms.Holo> TIMERS = new ArrayList<>();

    private static final Set<UUID> IN_MINE = new HashSet<>();
    private static final Map<UUID, Map<Item, Integer>> PENDING = new HashMap<>();
    private static final Map<UUID, Long> LAST_DENY = new HashMap<>();
    private static final Random RNG = new Random();
    private static Block[] oreBlocks;
    private static long now = 0;

    private Mine() {}

    static void setup() {
        oreBlocks = new Block[ORE_BLOCKS.length];
        nextRegrow = new long[ORE_BLOCKS.length];
        restored = new boolean[ORE_BLOCKS.length];
        MINED.clear();
        for (int k = 0; k < ORE_BLOCKS.length; k++) {
            oreBlocks[k] = BuiltInRegistries.BLOCK.getValue(Identifier.withDefaultNamespace(ORE_BLOCKS[k]));
            MINED.add(new ArrayDeque<>());
            for (int[] s : TownDesign.oreSlots(k, COUNTS[k])) SLOT_GALLERY.put(BlockPos.asLong(s[0], s[1], s[2]), k);
            // Next-ore timer at the gallery entrance, just under its name.
            ActivityDesign.Area g = TownDesign.gallery(k);
            TIMERS.add(Holograms.add((g.x1() + g.x2() + 1) / 2.0, FLOOR + 3.7, TownDesign.HALL_Z2 - 0.5, List.of("§aEvery ore is ready")));
        }
        for (int[] s : gravelSlots()) GRAVEL_SLOTS.add(BlockPos.asLong(s[0], s[1], s[2]));
        Holograms.add((PIT_X1 + PIT_X2 + 1) / 2.0, FLOOR + 4.6, TownDesign.HALL_Z2 - 0.5,
                List.of("§7§lGRAVEL PIT", "§7Open to everyone", "§7Shovel gravel for flint §8(" + FLINT_PER_HOUR + " an hour)"));
    }

    /** Seconds (in ticks) between ores coming back in gallery k. */
    static long regrowInterval(int k) {
        return Math.max(20, Math.round(REGROW_SECONDS[k] * 20.0 * REGROW_SPREAD / COUNTS[k]));
    }

    /** The gravel blocks: in the pit's side walls and end wall, always the same ones. */
    static List<int[]> gravelSlots() {
        List<int[]> all = new ArrayList<>();
        for (int z = PIT_Z1 + 1; z <= PIT_Z2; z++)
            for (int y = FLOOR + 1; y <= FLOOR + 3; y++) {
                all.add(new int[]{PIT_X1 - 1, y, z});
                all.add(new int[]{PIT_X2 + 1, y, z});
            }
        for (int x = PIT_X1; x <= PIT_X2; x++)
            for (int y = FLOOR + 1; y <= FLOOR + 3; y++) all.add(new int[]{x, y, PIT_Z2 + 1});
        Collections.shuffle(all, new Random(899));
        return all.subList(0, Math.min(GRAVEL_COUNT, all.size()));
    }

    private static boolean inMine(double x, double y, double z) {
        return TownDesign.MINE.contains(x, y, z);
    }

    /** The pickaxe for your Mining level (can only break the mine's ores). */
    private static ItemStack pickaxe(ServerPlayer p, int level) {
        Item item = level >= 45 ? Items.NETHERITE_PICKAXE : level >= 30 ? Items.DIAMOND_PICKAXE : level >= 10 ? Items.IRON_PICKAXE : Items.STONE_PICKAXE;
        ItemStack s = Activities.unbreakable(new ItemStack(item));
        if (oreBlocks != null) Stacks.canBreak(s, oreBlocks);
        int up = upgrade(p.getUUID());
        if (up > 0) {
            var holder = p.registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.ENCHANTMENT)
                    .getOrThrow(net.minecraft.world.item.enchantment.Enchantments.EFFICIENCY);
            s.enchant(holder, UPGRADE_EFFICIENCY[up]);
        }
        return Stacks.tag(Stacks.named(s, "§7Mine Pickaxe" + (up > 0 ? " §6" + UPGRADE_NAMES[up] : "") + " §8(only works in the Mine)",
                up > 0 ? "§7+" + UPGRADE_BONUS[up] + "% chance of an extra coal, iron or copper" : "§7Upgrade it with the Smith in the Mine Head"), "act");
    }

    /** 5.17: the Gravel Pit shovel (can only break gravel). */
    private static ItemStack shovel() {
        ItemStack s = Stacks.canBreak(Activities.unbreakable(new ItemStack(Items.IRON_SHOVEL)), Blocks.GRAVEL);
        return Stacks.tag(Stacks.named(s, "§7Mine Shovel §8(gravel in the Gravel Pit)",
                "§7" + FLINT_CHANCE + "% chance of flint per gravel", "§7Up to " + FLINT_PER_HOUR + " flint an hour"), "act");
    }

    // ---- Smith upgrades (bought from the Smith NPC) ----
    /**
     * Pickaxe upgrades: faster mining and a small chance of one extra drop - only on coal, iron and
     * copper (the cheap ores), so the most it can add is roughly +$600 an hour at full Mine output.
     */
    public static final String[] UPGRADE_NAMES = {"", "I", "II", "III"};
    public static final int[] UPGRADE_EFFICIENCY = {0, 2, 4, 5};
    public static final int[] UPGRADE_BONUS = {0, 8, 16, 24};
    public static final long[] UPGRADE_PRICE = {0, 4_000, 12_000, 30_000};
    public static final int[] UPGRADE_LEVEL = {0, 10, 25, 40};

    public static int upgrade(java.util.UUID id) {
        return (int) Math.min(3, PlayerStats.get(id, "pick_upgrade"));
    }

    /** Buys the next upgrade. Returns a message. */
    public static String buyUpgrade(ServerPlayer p) {
        int cur = upgrade(p.getUUID());
        if (cur >= 3) return "§7Your pickaxe is fully upgraded.";
        int next = cur + 1;
        if (Skills.level(p.getUUID(), Skills.Skill.MINING) < UPGRADE_LEVEL[next])
            return "§cUpgrade " + UPGRADE_NAMES[next] + " needs Mining level " + UPGRADE_LEVEL[next] + ".";
        if (!Economy.take(p.level().getServer(), p.getUUID(), UPGRADE_PRICE[next]))
            return "§cUpgrade " + UPGRADE_NAMES[next] + " costs " + Economy.format(UPGRADE_PRICE[next]) + ".";
        PlayerStats.add(p, "pick_upgrade", 1);
        if (IN_MINE.contains(p.getUUID())) p.getInventory().setItem(1, pickaxe(p, Skills.level(p.getUUID(), Skills.Skill.MINING)));
        return "§aPickaxe upgraded to " + UPGRADE_NAMES[next] + "! §7Efficiency " + UPGRADE_EFFICIENCY[next] + ", +" + UPGRADE_BONUS[next] + "% extra coal/iron/copper.";
    }

    static void kit(ServerPlayer p, Inventory inv) {
        if (!IN_MINE.contains(p.getUUID())) return;
        inv.setItem(1, pickaxe(p, Skills.level(p.getUUID(), Skills.Skill.MINING)));
        inv.setItem(2, shovel());
    }

    static boolean busy(ServerPlayer p) {
        return IN_MINE.contains(p.getUUID());
    }

    /** Every tick for each lobby player. */
    static void tick(ServerPlayer p, long ticks) {
        now = ticks;
        UUID id = p.getUUID();
        boolean inside = inMine(p.getX(), p.getY(), p.getZ());
        if (inside && !IN_MINE.contains(id)) {
            IN_MINE.add(id);
            Lobby.refreshKit(p);
            Announce.actionBar(p, "§7You got a pickaxe and a shovel. §fMine the ores §7- what you mine is sent to your Sky Island.");
        } else if (!inside && IN_MINE.remove(id)) {
            flush(p);
            Lobby.refreshKit(p);
        }
        if (!inside) return;
        // Someone dropped or lost the shovel: put it back (every player in the Mine has one).
        if (ticks % 40 == 0 && !hasShovel(p)) Lobby.refreshKit(p);
        // Locked galleries: step back out into the hall.
        for (int k = 1; k < LEVELS.length; k++) {
            ActivityDesign.Area g = TownDesign.gallery(k);
            if (!g.contains(p.getX(), p.getY(), p.getZ())) continue;
            if (Skills.level(id, Skills.Skill.MINING) >= LEVELS[k]) break;
            p.teleportTo((ServerLevel) p.level(), p.getX(), p.getY(), TownDesign.HALL_Z2 - 1.5, Set.of(), p.getYRot(), p.getXRot(), true);
            deny(p, "§cThe " + GALLERY_NAMES[k] + " needs Mining level " + LEVELS[k] + ". §7(Yours: " + Skills.level(id, Skills.Skill.MINING) + ")");
        }
        if (ticks % 600 == 0) flush(p);
    }

    private static boolean hasShovel(ServerPlayer p) {
        Inventory inv = p.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) if (inv.getItem(i).is(Items.IRON_SHOVEL)) return true;
        return false;
    }

    /** Once a tick: ores and gravel growing back, the entrance timers, digging the Gravel Pit. */
    static void tick(ServerLevel lobby, long ticks) {
        now = ticks;
        if (oreBlocks == null) return;
        if (ticks % 100 == 0) digPit(lobby);
        if (ticks % 10 != 0) return;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int k = 0; k < oreBlocks.length; k++) {
            if (!restored[k]) restore(lobby, k);
            ArrayDeque<Long> q = MINED.get(k);
            if (q.isEmpty() || ticks < nextRegrow[k]) continue;
            long key = q.peekFirst();
            pos.set(key);
            if (!lobby.isLoaded(pos)) {
                nextRegrow[k] = ticks + 200; // try again when someone's near
                continue;
            }
            q.pollFirst();
            if (lobby.getBlockState(pos).is(Blocks.STONE)) {
                lobby.setBlock(pos, oreBlocks[k].defaultBlockState(), Block.UPDATE_CLIENTS);
                lobby.playSound(null, pos, SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.BLOCKS, 0.4f, 1.4f);
            }
            if (!q.isEmpty()) nextRegrow[k] = ticks + regrowInterval(k);
        }
        // Gravel: one block a second.
        if (!GRAVEL_MINED.isEmpty() && ticks >= nextGravel) {
            pos.set(GRAVEL_MINED.peekFirst());
            if (lobby.isLoaded(pos)) {
                GRAVEL_MINED.pollFirst();
                if (lobby.getBlockState(pos).is(Blocks.STONE)) lobby.setBlock(pos, Blocks.GRAVEL.defaultBlockState(), Block.UPDATE_CLIENTS);
                nextGravel = ticks + GRAVEL_REGROW_TICKS;
            } else nextGravel = ticks + 200;
        }
        if (ticks % 20 == 0) timers();
    }

    /** After a restart nothing about mined ores is saved: any slot left as stone grows back straight away. */
    private static void restore(ServerLevel lobby, int k) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        List<Long> slots = new ArrayList<>();
        for (Map.Entry<Long, Integer> e : SLOT_GALLERY.entrySet()) if (e.getValue() == k) slots.add(e.getKey());
        for (long key : slots) if (!lobby.isLoaded(pos.set(key))) return;
        restored[k] = true;
        for (long key : slots) {
            pos.set(key);
            if (lobby.getBlockState(pos).is(Blocks.STONE)) lobby.setBlock(pos, oreBlocks[k].defaultBlockState(), Block.UPDATE_CLIENTS);
        }
    }

    private static void timers() {
        for (int k = 0; k < TIMERS.size(); k++) {
            ArrayDeque<Long> q = MINED.get(k);
            String line;
            if (q.isEmpty()) line = "§aEvery ore is ready";
            else {
                long sec = Math.max(0, (nextRegrow[k] - now + 19) / 20);
                line = "§7Next ore in §f" + (sec / 60) + ":" + String.format("%02d", sec % 60) + " §8(" + q.size() + " mined)";
            }
            TIMERS.get(k).setLines(List.of(line));
        }
    }

    /** Digs the Gravel Pit out of the rock (once, and again if the lobby builder ever paints rock over it). */
    private static void digPit(ServerLevel lobby) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int cx = (PIT_X1 + PIT_X2) / 2;
        for (int[] c : new int[][]{{PIT_X1 - 1, PIT_Z1}, {PIT_X2 + 1, PIT_Z2 + 1}, {cx, (PIT_Z1 + PIT_Z2) / 2}})
            if (!lobby.isLoaded(pos.set(c[0], FLOOR, c[1]))) return;
        if (pitDug && lobby.getBlockState(pos.set(cx, FLOOR + 2, PIT_Z1 + 3)).isAir()) return;
        pitDug = true;
        BlockState stone = Blocks.STONE.defaultBlockState(), air = Blocks.AIR.defaultBlockState();
        for (int x = PIT_X1 - 1; x <= PIT_X2 + 1; x++)
            for (int z = PIT_Z1; z <= PIT_Z2 + 1; z++) {
                boolean wall = x == PIT_X1 - 1 || x == PIT_X2 + 1 || z == PIT_Z2 + 1;
                lobby.setBlock(pos.set(x, FLOOR, z), z == PIT_Z1 && !wall ? Blocks.POLISHED_ANDESITE.defaultBlockState() : stone, Block.UPDATE_CLIENTS);
                for (int y = FLOOR + 1; y <= FLOOR + 4; y++) lobby.setBlock(pos.set(x, y, z), wall ? stone : air, Block.UPDATE_CLIENTS);
                lobby.setBlock(pos.set(x, FLOOR + 5, z), stone, Block.UPDATE_CLIENTS);
            }
        for (int z = PIT_Z1 + 3; z <= PIT_Z2; z += 5)
            lobby.setBlock(pos.set(cx, FLOOR + 4, z), Blocks.LANTERN.defaultBlockState().setValue(LanternBlock.HANGING, true), Block.UPDATE_CLIENTS);
        for (long key : GRAVEL_SLOTS) lobby.setBlock(pos.set(key), Blocks.GRAVEL.defaultBlockState(), Block.UPDATE_CLIENTS);
        GRAVEL_MINED.clear();
    }

    private static void deny(ServerPlayer p, String text) {
        Long last = LAST_DENY.get(p.getUUID());
        if (last != null && now - last < 60) return;
        LAST_DENY.put(p.getUUID(), now);
        Lobby.msg(p, text);
    }

    /**
     * A player finished breaking a block in the lobby. Returns true if it was a mine ore or pit gravel
     * (handled here: stone goes in its place, the drop is saved for their island).
     */
    static boolean mined(ServerPlayer p, ServerLevel level, BlockPos pos) {
        if (GRAVEL_SLOTS.contains(pos.asLong())) return gravel(p, level, pos);
        Integer k = SLOT_GALLERY.get(pos.asLong());
        if (k == null || oreBlocks == null || !level.getBlockState(pos).is(oreBlocks[k])) return false;
        int lvl = Skills.level(p.getUUID(), Skills.Skill.MINING);
        if (lvl < LEVELS[k]) {
            deny(p, "§cYou need Mining level " + LEVELS[k] + " for " + ORE_BLOCKS[k].replace("_", " ") + ".");
            return true;
        }
        // Air for a moment first: a player still holding the mouse stops "breaking" this spot
        // (otherwise the crack animation carries on over the stone that replaces the ore).
        level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
        final BlockPos at = pos.immutable();
        Lobby.runLaterTicks(3, () -> {
            if (level.getBlockState(at).isAir()) level.setBlock(at, Blocks.STONE.defaultBlockState(), Block.UPDATE_CLIENTS);
        });
        // 5.17: into the gallery's queue; its clock starts if nothing was waiting.
        ArrayDeque<Long> q = MINED.get(k);
        if (q.isEmpty()) nextRegrow[k] = now + regrowInterval(k);
        if (!q.contains(at.asLong())) q.addLast(at.asLong());
        int n = DROP_COUNT[k][0] + RNG.nextInt(DROP_COUNT[k][1] - DROP_COUNT[k][0] + 1);
        if (k <= 2 && RNG.nextInt(100) < UPGRADE_BONUS[upgrade(p.getUUID())]) n++;
        PENDING.computeIfAbsent(p.getUUID(), x -> new LinkedHashMap<>()).merge(DROPS[k], n, Integer::sum);
        Skills.add(p, Skills.Skill.MINING, XP[k]);
        PlayerStats.add(p, "mine_ore", 1);
        int total = PENDING.get(p.getUUID()).getOrDefault(DROPS[k], 0);
        Announce.actionBar(p, "§a+" + n + " " + new ItemStack(DROPS[k]).getHoverName().getString() + " §7(" + total + " this trip, sent to your island)");
        return true;
    }

    /** 5.17: a gravel block in the Gravel Pit. Stone straight away (so nothing above it falls), maybe flint. */
    private static boolean gravel(ServerPlayer p, ServerLevel level, BlockPos pos) {
        if (!level.getBlockState(pos).is(Blocks.GRAVEL)) return true;
        level.setBlock(pos, Blocks.STONE.defaultBlockState(), Block.UPDATE_CLIENTS);
        if (GRAVEL_MINED.isEmpty()) nextGravel = now + GRAVEL_REGROW_TICKS;
        if (!GRAVEL_MINED.contains(pos.asLong())) GRAVEL_MINED.addLast(pos.asLong());
        level.playSound(null, pos, SoundEvents.GRAVEL_BREAK, SoundSource.BLOCKS, 0.8f, 1f);
        String hour = LocalDateTime.now().truncatedTo(ChronoUnit.HOURS).toString();
        Object[] h = FLINT_HOUR.get(p.getUUID());
        if (h == null || !hour.equals(h[0])) {
            h = new Object[]{hour, 0};
            FLINT_HOUR.put(p.getUUID(), h);
        }
        int got = (Integer) h[1];
        if (got >= FLINT_PER_HOUR) {
            int mins = 60 - LocalDateTime.now().getMinute();
            Announce.actionBar(p, "§7No more flint this hour §8(" + FLINT_PER_HOUR + "/" + FLINT_PER_HOUR + ") §7- more in §f" + mins + " min");
            return true;
        }
        if (RNG.nextInt(100) >= FLINT_CHANCE) {
            Announce.actionBar(p, "§7Just gravel. §8(flint this hour: " + got + "/" + FLINT_PER_HOUR + ")");
            return true;
        }
        h[1] = got + 1;
        PENDING.computeIfAbsent(p.getUUID(), x -> new LinkedHashMap<>()).merge(Items.FLINT, 1, Integer::sum);
        Skills.add(p, Skills.Skill.MINING, FLINT_XP);
        Announce.actionBar(p, "§a+1 Flint §7(" + (got + 1) + "/" + FLINT_PER_HOUR + " this hour, sent to your island)");
        return true;
    }

    /** Sends what they've mined to their island mailbox (in full stacks). */
    static void flush(ServerPlayer p) {
        Map<Item, Integer> m = PENDING.remove(p.getUUID());
        if (m == null || m.isEmpty()) return;
        int stacks = 0;
        for (Map.Entry<Item, Integer> e : m.entrySet()) {
            int left = e.getValue();
            while (left > 0) {
                int c = Math.min(left, 64);
                Mailbox.send(p.level().getServer(), p.getUUID(), new ItemStack(e.getKey(), c));
                left -= c;
                stacks++;
            }
        }
        if (stacks > 0) Lobby.msg(p, "§7Your mining haul was sent to your Sky Island. §eType /claim there §7to collect it.");
    }

    static void leave(ServerPlayer p) {
        if (IN_MINE.remove(p.getUUID())) flush(p);
    }
}
