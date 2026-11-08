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

import java.util.*;

/**
 * The lobby Mine (under the Town). You get a pickaxe when you walk in. Mining an ore gives its drop
 * (sent to your Sky Island - type /claim there) and Mining XP; the ore turns to stone and grows back
 * after a while. Each gallery opens at a Mining level; rarer ores have fewer blocks and take longer
 * to grow back. Edit the tables below to change it.
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

    private static final Map<Long, Integer> SLOT_GALLERY = new HashMap<>(); // ore slot -> gallery
    private static final Map<Long, Long> REGROW = new HashMap<>();          // slot -> tick it grows back
    private static final Set<UUID> IN_MINE = new HashSet<>();
    private static final Map<UUID, Map<Item, Integer>> PENDING = new HashMap<>();
    private static final Map<UUID, Long> LAST_DENY = new HashMap<>();
    private static final Random RNG = new Random();
    private static Block[] oreBlocks;
    private static long now = 0;

    private Mine() {}

    static void setup() {
        oreBlocks = new Block[ORE_BLOCKS.length];
        for (int k = 0; k < ORE_BLOCKS.length; k++) {
            oreBlocks[k] = BuiltInRegistries.BLOCK.getValue(Identifier.withDefaultNamespace(ORE_BLOCKS[k]));
            for (int[] s : TownDesign.oreSlots(k, COUNTS[k])) {
                long key = BlockPos.asLong(s[0], s[1], s[2]);
                SLOT_GALLERY.put(key, k);
                // After a restart, anything mined before grows back soon (the timers aren't saved).
                REGROW.put(key, 200L + RNG.nextInt(400));
            }
        }
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
        if (IN_MINE.contains(p.getUUID())) inv.setItem(1, pickaxe(p, Skills.level(p.getUUID(), Skills.Skill.MINING)));
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
            Announce.actionBar(p, "§7You got a pickaxe. §fMine the ores §7- what you mine is sent to your Sky Island.");
        } else if (!inside && IN_MINE.remove(id)) {
            flush(p);
            Lobby.refreshKit(p);
        }
        if (!inside) return;
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

    /** Once a tick: ores growing back. */
    static void tick(ServerLevel lobby, long ticks) {
        now = ticks;
        if (REGROW.isEmpty() || ticks % 10 != 0) return;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (Iterator<Map.Entry<Long, Long>> it = REGROW.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Long, Long> e = it.next();
            if (e.getValue() > ticks) continue;
            pos.set(e.getKey());
            if (!lobby.isLoaded(pos)) {
                e.setValue(ticks + 200); // try again when someone's near
                continue;
            }
            it.remove();
            Integer k = SLOT_GALLERY.get(e.getKey());
            if (k != null && lobby.getBlockState(pos).is(Blocks.STONE)) {
                lobby.setBlock(pos, oreBlocks[k].defaultBlockState(), Block.UPDATE_CLIENTS);
                lobby.playSound(null, pos, SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.BLOCKS, 0.4f, 1.4f);
            }
        }
    }

    private static void deny(ServerPlayer p, String text) {
        Long last = LAST_DENY.get(p.getUUID());
        if (last != null && now - last < 60) return;
        LAST_DENY.put(p.getUUID(), now);
        Lobby.msg(p, text);
    }

    /**
     * A player finished breaking a block in the lobby. Returns true if it was a mine ore (handled
     * here: stone goes in its place, the drop is saved for their island).
     */
    static boolean mined(ServerPlayer p, ServerLevel level, BlockPos pos) {
        Integer k = SLOT_GALLERY.get(pos.asLong());
        if (k == null || !level.getBlockState(pos).is(oreBlocks[k])) return false;
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
        // Rarer ores take longer; a little random so they don't all pop back at once.
        long delay = REGROW_SECONDS[k] * 20L;
        REGROW.put(pos.asLong(), now + delay + RNG.nextInt((int) (delay / 4) + 1));
        int n = DROP_COUNT[k][0] + RNG.nextInt(DROP_COUNT[k][1] - DROP_COUNT[k][0] + 1);
        if (k <= 2 && RNG.nextInt(100) < UPGRADE_BONUS[upgrade(p.getUUID())]) n++;
        PENDING.computeIfAbsent(p.getUUID(), x -> new LinkedHashMap<>()).merge(DROPS[k], n, Integer::sum);
        Skills.add(p, Skills.Skill.MINING, XP[k]);
        PlayerStats.add(p, "mine_ore", 1);
        int total = PENDING.get(p.getUUID()).getOrDefault(DROPS[k], 0);
        Announce.actionBar(p, "§a+" + n + " " + new ItemStack(DROPS[k]).getHoverName().getString() + " §7(" + total + " this trip, sent to your island)");
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
