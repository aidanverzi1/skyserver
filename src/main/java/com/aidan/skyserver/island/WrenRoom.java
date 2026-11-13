package com.aidan.skyserver.island;

import com.aidan.skyserver.Safe;
import com.aidan.skyserver.lobby.*;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LanternBlock;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

import java.util.*;

/**
 * 5.20: CAPTAIN WREN'S SHIP ROOM - a pirate ship cabin on every Sky Island, where Wren lives now.
 *
 *  - Talk to Wren inside for his quests (as before) and to start ANY expedition (Nether, End, Ancient City,
 *    Sunken Monument). The old airship that came for the Nether is gone.
 *  - Three Tribute Chests (the Sunken Monument's fare, see Tribute), a lectern (the island menu and
 *    Wren's Charts), and a map table (Wren's Charts).
 *  - New islands get the room automatically, north of the island with a gangplank. Islands made before
 *    5.20 place it themselves: /is shiproom place shows an outline in front of you, /is shiproom confirm
 *    builds it (only into empty space, inside your border). /is shiproom place again moves it.
 *  - The room can't be broken, built in or changed, and it repairs itself (explosions, fluids...).
 *  - It's the expedition "deck": the crew gathers inside before a trip, and comes back to it.
 * The room's spot is saved with the island ({x, y, z, rotation}, Islands.Island.wrenRoom).
 */
public final class WrenRoom {
    private WrenRoom() {}

    /** Local room design: x = along the ship (bow at +x), z = across (door at +z), y = 0 is the floor. */
    private static final Map<Long, BlockState> DESIGN = new LinkedHashMap<>();
    private static final BlockState AIR = Blocks.AIR.defaultBlockState();
    /** Local spots of the special blocks. */
    private static final int[][] CHESTS = {{-3, 1, -3}, {0, 1, -3}, {3, 1, -3}};
    private static final int[] LECTERN = {-6, 1, -3}, MAP_TABLE = {-6, 1, 3};
    /** Wren at the helm (local x, y, z, yaw) and where people stand when they arrive. */
    private static final double[] WREN = {6.5, 1, 0.5, 90}, LANDING = {0.5, 1, 2.5, 180};
    /** The protected box (local, inclusive). */
    private static final int BX0 = -8, BX1 = 11, BY0 = -3, BY1 = 15, BZ0 = -4, BZ1 = 4;
    /** Where the room goes on a brand-new island (relative to the island centre) and its rotation. */
    private static final int[] DEFAULT = {0, 0, -22, 0};

    private record Preview(int islandId, int[] spot, long until, boolean ok) {}

    private static final Map<UUID, Preview> PREVIEWS = new HashMap<>();
    private static MinecraftServer server;
    private static long ticks = 0;

    // =====================================================================
    // The design
    // =====================================================================

    private static long key(int x, int y, int z) {
        return BlockPos.asLong(x, y, z);
    }

    private static void put(int x, int y, int z, BlockState s) {
        DESIGN.put(key(x, y, z), s);
    }

    static {
        BlockState hull = Blocks.DARK_OAK_PLANKS.defaultBlockState(), deck = Blocks.SPRUCE_PLANKS.defaultBlockState();
        BlockState log = Blocks.STRIPPED_DARK_OAK_LOG.defaultBlockState(), glass = Blocks.GLASS.defaultBlockState();
        BlockState fence = Blocks.SPRUCE_FENCE.defaultBlockState(), mast = Blocks.STRIPPED_SPRUCE_LOG.defaultBlockState();
        BlockState sail = Blocks.WOOL.pick(DyeColor.WHITE).defaultBlockState(), stripe = Blocks.WOOL.pick(DyeColor.RED).defaultBlockState();
        BlockState flag = Blocks.WOOL.pick(DyeColor.BLACK).defaultBlockState();
        // Hull, narrowing to a keel.
        for (int x = -7; x <= 7; x++) for (int z = -3; z <= 3; z++) put(x, -1, z, hull);
        for (int x = -6; x <= 6; x++) for (int z = -2; z <= 2; z++) put(x, -2, z, hull);
        for (int x = -4; x <= 4; x++) for (int z = -1; z <= 1; z++) put(x, -3, z, hull);
        // Bowsprit.
        put(9, 0, 0, hull);
        put(10, 0, 0, fence);
        put(11, 0, 0, fence);
        // Floor, walls, interior air.
        for (int x = -8; x <= 8; x++)
            for (int z = -4; z <= 4; z++) {
                boolean edge = Math.abs(x) == 8 || Math.abs(z) == 4;
                put(x, 0, z, edge ? hull : deck);
                for (int y = 1; y <= 4; y++) {
                    if (!edge) {
                        put(x, y, z, AIR);
                        continue;
                    }
                    boolean pillar = (Math.abs(x) == 8 && Math.abs(z) == 4) || (Math.abs(z) == 4 && x % 4 == 0 && x != 0);
                    boolean port = y == 2 && ((Math.abs(z) == 4 && (x == -6 || x == -2 || x == 2 || x == 6)) || (Math.abs(x) == 8 && z == 0));
                    boolean door = z == 4 && x == 0 && y <= 2;
                    put(x, y, z, door ? AIR : pillar ? log : port ? glass : hull);
                }
                // Roof deck with rails.
                put(x, 5, z, deck);
                if (edge && !(x == 2 && z == 0)) put(x, 6, z, fence);
            }
        // Mast, sail and flag.
        for (int y = 6; y <= 14; y++) put(2, y, 0, mast);
        for (int y = 8; y <= 12; y++) for (int z = -3; z <= 3; z++) put(3, y, z, y == 10 ? stripe : sail);
        put(3, 14, 0, flag);
        put(4, 14, 0, flag);
        // Inside: a red runner, lanterns, the helm, barrels, the chests, the desk.
        for (int x = -5; x <= 5; x++) put(x, 1, 0, Blocks.CARPET.pick(DyeColor.RED).defaultBlockState());
        BlockState lantern = Blocks.LANTERN.defaultBlockState().setValue(LanternBlock.HANGING, true);
        put(-4, 4, 0, lantern);
        put(0, 4, 0, lantern);
        put(4, 4, 0, lantern);
        put(7, 1, 0, fence);
        put(7, 2, 0, Blocks.SPRUCE_TRAPDOOR.defaultBlockState());
        BlockState barrel = Blocks.BARREL.defaultBlockState().setValue(BlockStateProperties.FACING, Direction.UP);
        put(7, 1, -3, barrel);
        put(7, 1, 3, barrel);
        put(-7, 1, 3, barrel);
        put(-7, 1, -3, barrel);
        for (int[] c : CHESTS) put(c[0], c[1], c[2], Blocks.CHEST.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.SOUTH));
        put(LECTERN[0], LECTERN[1], LECTERN[2], Blocks.LECTERN.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.SOUTH));
        put(MAP_TABLE[0], MAP_TABLE[1], MAP_TABLE[2], Blocks.CARTOGRAPHY_TABLE.defaultBlockState());
    }

    // ---- Rotation: 0 = door faces south (+z), 1 = west, 2 = north, 3 = east ----

    private static int[] rot(int r, int x, int z) {
        return switch (r & 3) {
            case 1 -> new int[]{-z, x};
            case 2 -> new int[]{-x, -z};
            case 3 -> new int[]{z, -x};
            default -> new int[]{x, z};
        };
    }

    private static double[] rot(int r, double x, double z) {
        return switch (r & 3) {
            case 1 -> new double[]{-z, x};
            case 2 -> new double[]{-x, -z};
            case 3 -> new double[]{z, -x};
            default -> new double[]{x, z};
        };
    }

    private static final Rotation[] ROTATIONS = {Rotation.NONE, Rotation.CLOCKWISE_90, Rotation.CLOCKWISE_180, Rotation.COUNTERCLOCKWISE_90};

    private static BlockPos world(int[] spot, int x, int y, int z) {
        int[] xz = rot(spot[3], x, z);
        return new BlockPos(spot[0] + xz[0], spot[1] + y, spot[2] + xz[1]);
    }

    /** Local block coords of a world position for this room spot (inverse rotation). */
    private static int[] local(int[] spot, BlockPos p) {
        int dx = p.getX() - spot[0], dz = p.getZ() - spot[2];
        int[] xz = rot((4 - (spot[3] & 3)) & 3, dx, dz);
        return new int[]{xz[0], p.getY() - spot[1], xz[1]};
    }

    private static double[] worldSpot(int[] spot, double[] l) {
        // Block-centre local coords -> world (rotate around the block grid, not the origin corner).
        double[] xz = rot(spot[3], l[0] - 0.5, l[2] - 0.5);
        return new double[]{spot[0] + xz[0] + 0.5, spot[1] + l[1], spot[2] + xz[1] + 0.5, (l[3] + 90 * (spot[3] & 3)) % 360};
    }

    // =====================================================================
    // Questions other code asks
    // =====================================================================

    public static boolean hasRoom(Islands.Island is) {
        return is != null && is.wrenRoom != null;
    }

    private static boolean inBox(int[] spot, BlockPos p) {
        int[] l = local(spot, p);
        return l[0] >= BX0 && l[0] <= BX1 && l[1] >= BY0 && l[1] <= BY1 && l[2] >= BZ0 && l[2] <= BZ1;
    }

    /** Is this block part of (or inside) a ship room? */
    public static boolean isRoom(BlockPos p) {
        Islands.Island is = Islands.islandAt(p.getX(), p.getZ());
        return is != null && is.wrenRoom != null && inBox(is.wrenRoom, p);
    }

    /** Inside the cabin (where the crew gathers). Islands without a room: within 8 blocks of Wren. */
    public static boolean inStaging(Islands.Island is, double x, double y, double z) {
        if (is == null) return false;
        if (is.wrenRoom == null) {
            double[] w = IslandQuests.wrenSpot(is);
            return (x - w[0]) * (x - w[0]) + (y - w[1]) * (y - w[1]) + (z - w[2]) * (z - w[2]) <= 64;
        }
        int[] l = local(is.wrenRoom, BlockPos.containing(x, y, z));
        return l[0] >= -7 && l[0] <= 7 && l[2] >= -3 && l[2] <= 3 && l[1] >= 1 && l[1] <= 4;
    }

    /** Where people arrive in the room {x, y, z, yaw} (or next to Wren without a room). */
    public static double[] landing(Islands.Island is) {
        if (is.wrenRoom == null) {
            double[] w = IslandQuests.wrenSpot(is);
            return new double[]{w[0] + 1, w[1], w[2], w[3]};
        }
        return worldSpot(is.wrenRoom, LANDING);
    }

    private static int chestAt(int[] spot, BlockPos p) {
        int[] l = local(spot, p);
        for (int i = 0; i < CHESTS.length; i++) if (l[0] == CHESTS[i][0] && l[1] == CHESTS[i][1] && l[2] == CHESTS[i][2]) return i;
        return -1;
    }

    private static boolean at(int[] spot, BlockPos p, int[] what) {
        int[] l = local(spot, p);
        return l[0] == what[0] && l[1] == what[1] && l[2] == what[2];
    }

    // =====================================================================
    // Setup
    // =====================================================================

    public static void init() {
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STARTED.register(s -> server = s);
        // Registered before Islands' own handlers (Expedition.init runs first), so the room is handled here.
        UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
            if (level.isClientSide() || !Islands.isIsland(level) || !(player instanceof ServerPlayer sp)) return InteractionResult.PASS;
            BlockPos pos = hit.getBlockPos();
            Islands.Island is = Islands.islandAt(pos.getX(), pos.getZ());
            if (is == null || is.wrenRoom == null) return InteractionResult.PASS;
            ItemStack held = sp.getItemInHand(hand);
            boolean main = hand == net.minecraft.world.InteractionHand.MAIN_HAND;
            if (!inBox(is.wrenRoom, pos)) {
                if (held.getItem() instanceof BlockItem || held.getItem() instanceof BucketItem) {
                    BlockPos target = level.getBlockState(pos).canBeReplaced() ? pos : pos.relative(hit.getDirection());
                    if (inBox(is.wrenRoom, target)) {
                        if (main) Announce.actionBar(sp, "§7Captain Wren's ship can't be built on.");
                        return InteractionResult.FAIL;
                    }
                }
                return InteractionResult.PASS;
            }
            if (sp.isCreative() && sp.isSecondaryUseActive()) return InteractionResult.PASS; // admins can still fix things
            int chest = chestAt(is.wrenRoom, pos);
            if (chest >= 0) {
                if (main) Lobby.later(sp, () -> Tribute.open(sp, is, chest));
                return InteractionResult.SUCCESS;
            }
            if (at(is.wrenRoom, pos, LECTERN)) {
                if (main) Lobby.later(sp, () -> openDesk(sp));
                return InteractionResult.SUCCESS;
            }
            if (at(is.wrenRoom, pos, MAP_TABLE)) {
                if (main) Lobby.later(sp, () -> Charts.show(sp));
                return InteractionResult.SUCCESS;
            }
            if (main && (held.getItem() instanceof BlockItem || held.getItem() instanceof BucketItem))
                Announce.actionBar(sp, "§7Captain Wren's ship can't be built in.");
            return InteractionResult.FAIL;
        });
        PlayerBlockBreakEvents.BEFORE.register((level, player, pos, state, be) -> {
            if (!Islands.isIsland(level) || !isRoom(pos)) return true;
            if (player instanceof ServerPlayer sp) Announce.actionBar(sp, "§7Captain Wren's ship is built to last - it can't be broken.");
            return false;
        });
        ServerTickEvents.END_SERVER_TICK.register(s -> {
            ticks++;
            if (ticks % 10 == 0 && !PREVIEWS.isEmpty()) Safe.run("ship room preview", WrenRoom::drawPreviews);
            if (ticks % 100 == 37) Safe.run("ship room repair", WrenRoom::repairAll);
        });
    }

    // =====================================================================
    // Building, repairing, removing
    // =====================================================================

    private static void build(ServerLevel level, int[] spot) {
        Rotation r = ROTATIONS[spot[3] & 3];
        // Solid blocks bottom-up, then the air (so lanterns and the carpet have something to sit on).
        List<Map.Entry<Long, BlockState>> list = new ArrayList<>(DESIGN.entrySet());
        list.sort(Comparator.comparingInt(e -> BlockPos.getY(e.getKey())));
        for (Map.Entry<Long, BlockState> e : list) {
            if (e.getValue().isAir()) continue;
            long k = e.getKey();
            level.setBlock(world(spot, BlockPos.getX(k), BlockPos.getY(k), BlockPos.getZ(k)), e.getValue().rotate(r), Block.UPDATE_ALL);
        }
        for (Map.Entry<Long, BlockState> e : list) {
            if (!e.getValue().isAir()) continue;
            long k = e.getKey();
            BlockPos p = world(spot, BlockPos.getX(k), BlockPos.getY(k), BlockPos.getZ(k));
            if (!level.getBlockState(p).isAir()) level.setBlock(p, AIR, Block.UPDATE_ALL);
        }
    }

    private static void remove(ServerLevel level, int[] spot) {
        List<Long> keys = new ArrayList<>(DESIGN.keySet());
        keys.sort((a, b) -> Integer.compare(BlockPos.getY(b), BlockPos.getY(a))); // top down: nothing drops
        for (long k : keys) {
            if (DESIGN.get(k).isAir()) continue;
            BlockPos p = world(spot, BlockPos.getX(k), BlockPos.getY(k), BlockPos.getZ(k));
            if (!level.getBlockState(p).isAir()) level.setBlock(p, AIR, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
        }
    }

    /** Puts back anything that isn't how the design says (only rooms with a player nearby). */
    private static void repairAll() {
        ServerLevel level = Islands.level();
        if (level == null || level.players().isEmpty()) return;
        for (Islands.Island is : Islands.all()) {
            if (is.wrenRoom == null) continue;
            int[] s = is.wrenRoom;
            boolean near = false;
            for (ServerPlayer p : level.players()) if (p.distanceToSqr(s[0], s[1], s[2]) < 80 * 80) { near = true; break; }
            if (!near || !level.isLoaded(new BlockPos(s[0], s[1], s[2]))) continue;
            Rotation r = ROTATIONS[s[3] & 3];
            int fixed = 0;
            for (Map.Entry<Long, BlockState> e : DESIGN.entrySet()) {
                long k = e.getKey();
                BlockPos p = world(s, BlockPos.getX(k), BlockPos.getY(k), BlockPos.getZ(k));
                BlockState now = level.getBlockState(p), want = e.getValue();
                if (now.getBlock() == want.getBlock()) continue;
                level.setBlock(p, want.rotate(r), Block.UPDATE_ALL);
                fixed++;
            }
            if (fixed > 0) com.aidan.skyserver.Skyserver.LOGGER.info("[Ship room] repaired {} block(s) on island {}", fixed, is.id);
        }
    }

    /** Can the room go here? (Everything empty, inside the island's border.) */
    private static boolean fits(ServerLevel level, Islands.Island is, int[] spot) {
        for (long k : DESIGN.keySet()) {
            BlockPos p = world(spot, BlockPos.getX(k), BlockPos.getY(k), BlockPos.getZ(k));
            if (Islands.islandAt(p.getX(), p.getZ()) != is || !is.inBorder(p.getX() + 0.5, p.getZ() + 0.5)) return false;
            if (p.getY() <= Islands.VOID_Y + 2 || p.getY() >= level.getMaxY() - 2) return false;
            BlockState st = level.getBlockState(p);
            boolean oldRoom = is.wrenRoom != null && inBox(is.wrenRoom, p);
            if (!oldRoom && !st.isAir() && !(st.canBeReplaced() && st.getFluidState().isEmpty())) return false;
        }
        return true;
    }

    /** Moves Wren into the room and remembers where it is. */
    private static void settle(ServerLevel level, Islands.Island is, int[] spot) {
        is.wrenRoom = spot;
        is.wren = worldSpot(spot, WREN);
        Islands.save();
        IslandQuests.ensureWren(level, is);
    }

    /** A brand-new island: the room north of the island, with a gangplank to the grass. */
    static void buildDefault(ServerLevel level, Islands.Island is) {
        int[] spot = {is.cx() + DEFAULT[0], Islands.TOP_Y + DEFAULT[1], is.cz() + DEFAULT[2], DEFAULT[3]};
        build(level, spot);
        BlockState plank = Blocks.SPRUCE_PLANKS.defaultBlockState();
        for (int z = spot[2] + 5; z <= spot[2] + 16; z++) {
            if (!level.getBlockState(new BlockPos(spot[0], spot[1], z)).isAir()) break; // reached the island
            for (int x = -1; x <= 1; x++) {
                BlockPos p = new BlockPos(spot[0] + x, spot[1], z);
                if (level.getBlockState(p).isAir()) level.setBlock(p, plank, Block.UPDATE_ALL);
            }
        }
        settle(level, is, spot);
    }

    // =====================================================================
    // /is shiproom
    // =====================================================================

    /** /is shiproom: to your island's ship room (or how to get one). */
    public static void goTo(ServerPlayer p) {
        Islands.Island is = Islands.islandOf(p.getUUID());
        if (is == null) {
            Lobby.msg(p, "§cYou need a Sky Island first. §7/is");
            return;
        }
        if (!Islands.isIsland(p.level())) {
            Lobby.msg(p, "§cGo to your island first (§e/is§c).");
            return;
        }
        if (is.wrenRoom == null) {
            Lobby.msg(p, "§6[Captain Wren] §fI need a proper cabin, captain! §7Stand where the door should be, face where the ship should go, and type §e/is shiproom place§7.");
            return;
        }
        double[] l = landing(is);
        p.teleportTo(Islands.level(), l[0], l[1], l[2], Set.of(), (float) l[3], 0f, true);
        p.resetFallDistance();
    }

    /** /is shiproom visit <player>: a faction mate's or island mate's ship room (to put in their tribute). */
    public static void visit(ServerPlayer p, String name) {
        Islands.Island is = Islands.findByName(name);
        if (is == null || is.wrenRoom == null) {
            Lobby.msg(p, "§c" + name + " doesn't have a ship room.");
            return;
        }
        if (!is.isMember(p.getUUID()) && !com.aidan.skyserver.expedition.Expedition.sameFactionPublic(is.owner, p.getUUID())) {
            Lobby.msg(p, "§cOnly island and faction mates can visit someone's ship room.");
            return;
        }
        if (!Islands.isIsland(p.level())) {
            Lobby.msg(p, "§cGo to your island first (§e/is§c), then visit.");
            return;
        }
        double[] l = landing(is);
        p.teleportTo(Islands.level(), l[0], l[1], l[2], Set.of(), (float) l[3], 0f, true);
        p.resetFallDistance();
        if (!is.isMember(p.getUUID())) Islands.sendBorder(p, is);
        Lobby.msg(p, "§6Welcome aboard " + is.name() + "'s ship! §7The Tribute Chests are at the back.");
    }

    /** /is shiproom place: an outline where the room would go (you stand at the door, facing the ship). */
    public static void place(ServerPlayer p) {
        Islands.Island is = Islands.islandOf(p.getUUID());
        if (is == null || !Islands.isIsland(p.level()) || Islands.islandAt(p.getX(), p.getZ()) != is) {
            Lobby.msg(p, "§cStand on your own island to place Captain Wren's ship room.");
            return;
        }
        if (!is.owner.equals(p.getUUID())) {
            Lobby.msg(p, "§cOnly the island owner can place or move the ship room.");
            return;
        }
        Direction f = p.getDirection();
        int r = switch (f) {
            case EAST -> 1;
            case SOUTH -> 2;
            case WEST -> 3;
            default -> 0; // facing north: the door faces south, toward you
        };
        BlockPos feet = p.blockPosition();
        BlockPos doorAt = feet.relative(f);
        int[] doorXZ = rot(r, 0, 4);
        int[] spot = {doorAt.getX() - doorXZ[0], feet.getY() - 1, doorAt.getZ() - doorXZ[1], r};
        boolean ok = fits((ServerLevel) p.level(), is, spot);
        PREVIEWS.put(p.getUUID(), new Preview(is.id, spot, ticks + 30 * 20, ok));
        if (ok) {
            p.sendSystemMessage(net.minecraft.network.chat.Component.literal("§aThe ship fits here! §7(the outline shows it for 30 seconds) ")
                    .append(button("§a§l[Build it here]", "/is shiproom confirm", "Build Captain Wren's ship room here"))
                    .append(net.minecraft.network.chat.Component.literal(" "))
                    .append(button("§c[Cancel]", "/is shiproom cancel", "Don't build it")));
            if (is.wrenRoom != null) Lobby.msg(p, "§7(Building it here moves the ship: the old one disappears. The Tribute Chests keep everything.)");
        } else {
            Lobby.msg(p, "§cThe ship doesn't fit there §7(the red outline). It needs about 20 x 9 blocks of empty space and 19 high, inside your border. "
                    + "Stand where the door should be and face where the ship should go, then try again.");
        }
    }

    public static void confirm(ServerPlayer p) {
        Preview pv = PREVIEWS.remove(p.getUUID());
        Islands.Island is = Islands.islandOf(p.getUUID());
        if (pv == null || is == null || pv.islandId != is.id || ticks > pv.until) {
            Lobby.msg(p, "§7No ship room outline to build. §e/is shiproom place §7first.");
            return;
        }
        ServerLevel level = Islands.level();
        if (!fits(level, is, pv.spot)) {
            Lobby.msg(p, "§cSomething is in the way now - try §e/is shiproom place §cagain.");
            return;
        }
        boolean moved = is.wrenRoom != null;
        if (moved) remove(level, is.wrenRoom);
        build(level, pv.spot);
        settle(level, is, pv.spot);
        Islands.islandChat(is, "§6§l⚓ Captain Wren's ship room " + (moved ? "moved" : "is ready") + "! §7Talk to Wren inside for quests and expeditions.");
        Lobby.msg(p, "§6[Captain Wren] §fNow THAT'S a cabin! Come aboard any time - expeditions start from here.");
    }

    public static void cancel(ServerPlayer p) {
        Lobby.msg(p, PREVIEWS.remove(p.getUUID()) != null ? "§7Outline cancelled." : "§7Nothing to cancel.");
    }

    private static void drawPreviews() {
        ServerLevel level = Islands.level();
        if (level == null) return;
        for (Iterator<Map.Entry<UUID, Preview>> it = PREVIEWS.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, Preview> e = it.next();
            Preview pv = e.getValue();
            ServerPlayer p = server == null ? null : server.getPlayerList().getPlayer(e.getKey());
            if (p == null || ticks > pv.until || !Islands.isIsland(p.level())) {
                it.remove();
                continue;
            }
            var particle = pv.ok ? ParticleTypes.HAPPY_VILLAGER : ParticleTypes.FLAME;
            int[][] corners = {{BX0, BZ0}, {BX1, BZ0}, {BX1, BZ1}, {BX0, BZ1}};
            for (int y : new int[]{0, 5}) {
                for (int i = 0; i < 4; i++) {
                    int[] a = corners[i], b = corners[(i + 1) % 4];
                    int steps = Math.max(Math.abs(b[0] - a[0]), Math.abs(b[1] - a[1]));
                    for (int s = 0; s <= steps; s += 2) {
                        int x = a[0] + (b[0] - a[0]) * s / Math.max(1, steps), z = a[1] + (b[1] - a[1]) * s / Math.max(1, steps);
                        BlockPos w = world(pv.spot, x, y, z);
                        level.sendParticles(particle, w.getX() + 0.5, w.getY() + 0.5, w.getZ() + 0.5, 1, 0, 0, 0, 0);
                    }
                }
            }
            BlockPos door = world(pv.spot, 0, 1, 4);
            level.sendParticles(ParticleTypes.END_ROD, door.getX() + 0.5, door.getY() + 0.5, door.getZ() + 0.5, 2, 0.1, 0.4, 0.1, 0);
        }
    }

    // =====================================================================
    // The captain's desk (lectern)
    // =====================================================================

    static void openDesk(ServerPlayer p) {
        LockedMenu.open(p, "§8Captain's Desk", 3, Desk::new);
    }

    static final class Desk extends LockedMenu {
        private static final int ISLAND = 11, CHARTS = 13, EXPEDITIONS = 15;

        Desk(int id, Inventory inv, SimpleContainer box, ServerPlayer viewer) {
            super(id, inv, box, 3, viewer);
            box.setItem(ISLAND, Stacks.named(new ItemStack(Items.GRASS_BLOCK), "§a§lIsland Menu", "§7Upgrades, challenges, visitors...", "§8(same as /is menu)", "§eClick"));
            box.setItem(CHARTS, Stacks.named(new ItemStack(Items.FILLED_MAP), "§6§lWren's Charts", "§7Every expedition at every difficulty.", "§eClick to see yours"));
            box.setItem(EXPEDITIONS, Stacks.named(new ItemStack(Items.COMPASS), "§b§lExpeditions", "§7Talk to Captain Wren to start one.", "§eClick for Wren's list"));
            frame(Stacks.named(new ItemStack(Items.STAINED_GLASS_PANE.pick(DyeColor.BROWN)), " "));
            broadcastChanges();
        }

        @Override
        protected void onClick(int slot, int button, boolean shift) {
            if (slot == ISLAND) Lobby.later(viewer, () -> IslandMenu.open(viewer));
            else if (slot == CHARTS) {
                viewer.closeContainer();
                Lobby.later(viewer, () -> Charts.show(viewer));
            } else if (slot == EXPEDITIONS) Lobby.later(viewer, () -> WrenMenu.open(viewer));
        }
    }

    static net.minecraft.network.chat.MutableComponent button(String text, String command, String hover) {
        return net.minecraft.network.chat.Component.literal(text).withStyle(net.minecraft.network.chat.Style.EMPTY
                .withClickEvent(new net.minecraft.network.chat.ClickEvent.RunCommand(command))
                .withHoverEvent(new net.minecraft.network.chat.HoverEvent.ShowText(net.minecraft.network.chat.Component.literal(hover))));
    }
}
