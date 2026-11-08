package com.aidan.skyserver.bedwars;

import com.aidan.skyserver.Skyserver;
import com.aidan.skyserver.goldwars.GoldWarsMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.util.*;

/**
 * Puts the Bedwars maps into their own dimension (skyserver:bedwars, an empty void world that is
 * always day). The designs are in BwMapDesigns; this turns them into blocks, puts the shopkeepers
 * in place and keeps the floating signs over the diamond and emerald generators up to date.
 */
public final class BwMap {
    public static final ResourceKey<Level> DIM = ResourceKey.create(Registries.DIMENSION, Identifier.fromNamespaceAndPath(Skyserver.MOD_ID, "bedwars"));
    /** Where the middle of the map is (y = the floor). */
    public static final int OX = 0, OY = 100, OZ = 0;
    /** Big enough to clear either map. */
    private static final int CLEAR_HALF = 92, CLEAR_DOWN = 40, CLEAR_UP = 45;
    private static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;
    public static final String SHOP_TAG = "skyserver_bw_shop", UPGRADES_TAG = "skyserver_bw_upgrades", HOLO_TAG = "skyserver_bw_holo";

    private static int index = 0;
    private static BwMapDesigns.MapData data;
    private static final Map<String, BwMapDesigns.MapData> CACHE = new HashMap<>();
    /** Cells nobody may build in (spawns, generators, shopkeepers), as BlockPos longs. */
    private static final Set<Long> PROTECTED = new HashSet<>();
    private static final List<ArmorStand[]> GEN_SIGNS = new ArrayList<>(); // diamonds first, then emeralds
    private static boolean forced = false;

    private BwMap() {}

    public static ServerLevel level(MinecraftServer server) {
        return server == null ? null : server.getLevel(DIM);
    }

    public static BwMapDesigns.MapData data() {
        if (data == null) data = design(BwMapDesigns.IDS[index]);
        return data;
    }

    private static BwMapDesigns.MapData design(String id) {
        return CACHE.computeIfAbsent(id, BwMapDesigns::build);
    }

    public static int index() {
        return index;
    }

    public static String id() {
        return BwMapDesigns.IDS[index];
    }

    public static String display() {
        return BwMapDesigns.NAMES[index];
    }

    public static void select(int i) {
        index = Math.floorMod(i, BwMapDesigns.IDS.length);
        data = design(BwMapDesigns.IDS[index]);
    }

    public static void next() {
        select(index + 1);
    }

    // ---- Positions ----

    public static BlockPos pos(int[] rel) {
        return new BlockPos(OX + rel[0], OY + rel[1], OZ + rel[2]);
    }

    public static int voidY() {
        return OY + data().voidY;
    }

    public static boolean inBuildArea(BlockPos p) {
        BwMapDesigns.MapData m = data();
        return Math.abs(p.getX() - OX) <= m.half && Math.abs(p.getZ() - OZ) <= m.half
                && p.getY() <= OY + m.buildTop && p.getY() >= OY + m.buildBottom;
    }

    public static boolean isProtected(BlockPos p) {
        return PROTECTED.contains(p.asLong());
    }

    public static AABB box() {
        return new AABB(OX - CLEAR_HALF, OY - CLEAR_DOWN - 40, OZ - CLEAR_HALF, OX + CLEAR_HALF + 1, OY + CLEAR_UP + 1, OZ + CLEAR_HALF + 1);
    }

    // =====================================================================
    // Building
    // =====================================================================

    /** Clears the area and builds the selected map, with its shopkeepers and generator signs. */
    public static void build(ServerLevel level) {
        BwMapDesigns.MapData m = data();
        forceChunks(level);
        clear(level);
        m.canvas.forEach((x, y, z, s) -> level.setBlock(new BlockPos(OX + x, OY + y, OZ + z), GoldWarsMap.toState(s), FLAGS));
        // Empty chests.
        for (int t = 0; t < 4; t++) {
            if (level.getBlockEntity(pos(m.teamChest[t])) instanceof Container c) c.clearContent();
        }
        protect(m);
        for (int t = 0; t < 4; t++) {
            shopkeeper(level, m.shop[t], m.shopYaw[t], "§b§lITEM SHOP", SHOP_TAG);
            shopkeeper(level, m.upgrades[t], m.upgradesYaw[t], "§e§lTEAM UPGRADES", UPGRADES_TAG);
        }
        GEN_SIGNS.clear();
        for (int[] g : m.diamondGens) GEN_SIGNS.add(sign(level, g));
        for (int[] g : m.emeraldGens) GEN_SIGNS.add(sign(level, g));
        Skyserver.LOGGER.info("Bedwars: built {} ({} blocks)", m.display, m.canvas.size());
    }

    /** Places a bed (both halves) for a team, facing from foot to head. */
    public static void placeBed(ServerLevel level, BwTeam team, BlockPos foot, BlockPos head) {
        String facing = facingOf(foot, head);
        level.setBlock(head, GoldWarsMap.toState(team.block("bed") + "[facing=" + facing + ",part=head]"), FLAGS);
        level.setBlock(foot, GoldWarsMap.toState(team.block("bed") + "[facing=" + facing + ",part=foot]"), FLAGS);
    }

    public static String facingOf(BlockPos foot, BlockPos head) {
        int dx = head.getX() - foot.getX(), dz = head.getZ() - foot.getZ();
        return dx > 0 ? "east" : dx < 0 ? "west" : dz > 0 ? "south" : "north";
    }

    private static void protect(BwMapDesigns.MapData m) {
        PROTECTED.clear();
        for (int t = 0; t < 4; t++) {
            column(m.spawn[t], 1, 4);
            column(m.gen[t], 1, 3);
            column(m.shop[t], 0, 2);
            column(m.upgrades[t], 0, 2);
        }
        for (int[] g : m.diamondGens) column(g, 1, 3);
        for (int[] g : m.emeraldGens) column(g, 1, 3);
    }

    private static void column(int[] rel, int radius, int height) {
        for (int dx = -radius; dx <= radius; dx++)
            for (int dz = -radius; dz <= radius; dz++)
                for (int dy = 0; dy < height; dy++)
                    PROTECTED.add(new BlockPos(OX + rel[0] + dx, OY + rel[1] + dy, OZ + rel[2] + dz).asLong());
    }

    private static void shopkeeper(ServerLevel level, int[] rel, float yaw, String name, String tag) {
        Villager v = net.minecraft.world.entity.EntityTypes.VILLAGER.create(level, EntitySpawnReason.COMMAND);
        if (v == null) return;
        v.snapTo(OX + rel[0] + 0.5, OY + rel[1], OZ + rel[2] + 0.5, yaw, 0f);
        v.setYHeadRot(yaw);
        v.setNoAi(true);
        v.setPermanentlyInvulnerable(true);
        v.setSilent(true);
        v.setPersistenceRequired();
        v.setCustomName(Component.literal(name));
        v.setCustomNameVisible(true);
        v.addTag(tag);
        v.addTag("skyserver_npc");
        level.addFreshEntity(v);
    }

    private static ArmorStand[] sign(ServerLevel level, int[] rel) {
        ArmorStand[] lines = new ArmorStand[2];
        for (int i = 0; i < 2; i++) {
            ArmorStand s = new ArmorStand(level, OX + rel[0] + 0.5, OY + rel[1] + 2.6 - i * 0.3, OZ + rel[2] + 0.5);
            s.setInvisible(true);
            s.setNoGravity(true);
            s.setPermanentlyInvulnerable(true);
            s.setSilent(true);
            s.setCustomNameVisible(true);
            s.setCustomName(Component.literal(" "));
            s.addTag(HOLO_TAG);
            marker(s);
            level.addFreshEntity(s);
            lines[i] = s;
        }
        return lines;
    }

    /** Updates a generator sign (index: diamonds first, then emeralds). */
    public static void setSign(int i, String top, String bottom) {
        if (i < 0 || i >= GEN_SIGNS.size()) return;
        ArmorStand[] s = GEN_SIGNS.get(i);
        if (!s[0].isRemoved()) s[0].setCustomName(Component.literal(top));
        if (!s[1].isRemoved()) s[1].setCustomName(Component.literal(bottom));
    }

    private static void marker(ArmorStand s) {
        try {
            var m = ArmorStand.class.getDeclaredMethod("setMarker", boolean.class);
            m.setAccessible(true);
            m.invoke(s, true);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // Not important: it's invisible and invulnerable anyway.
        }
    }

    /** Keeps the whole map loaded (generators and shopkeepers keep working with nobody nearby). */
    private static void forceChunks(ServerLevel level) {
        if (forced) return;
        forced = true;
        for (int cx = (OX - CLEAR_HALF) >> 4; cx <= (OX + CLEAR_HALF) >> 4; cx++)
            for (int cz = (OZ - CLEAR_HALF) >> 4; cz <= (OZ + CLEAR_HALF) >> 4; cz++) level.setChunkForced(cx, cz, true);
    }

    private static void clear(ServerLevel level) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        BlockState air = Blocks.AIR.defaultBlockState();
        for (int x = OX - CLEAR_HALF; x <= OX + CLEAR_HALF; x++)
            for (int z = OZ - CLEAR_HALF; z <= OZ + CLEAR_HALF; z++)
                for (int y = OY - CLEAR_DOWN; y <= OY + CLEAR_UP; y++) {
                    pos.set(x, y, z);
                    BlockState cur = level.getBlockState(pos);
                    if (cur.isAir()) continue;
                    if (cur.hasBlockEntity() && level.getBlockEntity(pos) instanceof Container c) c.clearContent();
                    level.setBlock(pos, air, FLAGS);
                }
        for (Entity e : level.getEntitiesOfClass(Entity.class, box(), e -> !(e instanceof Player))) e.discard();
    }
}
