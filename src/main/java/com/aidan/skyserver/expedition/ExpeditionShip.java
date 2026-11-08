package com.aidan.skyserver.expedition;

import com.aidan.skyserver.island.Islands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LanternBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.*;

/**
 * 5.8: Captain Wren's airship, which comes to an island for a Nether Expedition.
 *
 * It's only ever built into AIR above the island (inside the island's border), so nothing on the
 * island is replaced; when it leaves, only the blocks it placed are removed. A rope of chains hangs
 * from the deck toward the ground: right-click it (or talk to Wren by the path) to board. Ship blocks
 * can't be broken or used. Nothing is saved: a restart sends the ship away (see Expedition).
 */
public final class ExpeditionShip {
    public static final String WREN_TAG = "skyserver_shipwren";

    /** One ship over one island. */
    public static final class Ship {
        public final int islandId;
        public final ServerLevel level;
        public final BlockPos center; // deck level, middle of the ship
        final List<BlockPos> placed = new ArrayList<>();
        final Set<Long> rope = new HashSet<>();
        UUID wren;

        Ship(int islandId, ServerLevel level, BlockPos center) {
            this.islandId = islandId;
            this.level = level;
            this.center = center;
        }

        /** Where people stand when they come aboard. */
        public double[] deckSpot() {
            return new double[]{center.getX() + 0.5, center.getY() + 1, center.getZ() + 2.5, 180f};
        }

        /** True if (x, y, z) is on or just above the deck. */
        public boolean onDeck(double x, double y, double z) {
            return Math.abs(x - center.getX() - 0.5) <= 9.5 && Math.abs(z - center.getZ() - 0.5) <= 4.5 && y >= center.getY() - 0.5 && y <= center.getY() + 5;
        }
    }

    private static final Map<Long, Ship> BLOCKS = new HashMap<>(); // every ship block -> its ship

    private ExpeditionShip() {}

    public static boolean isShipBlock(BlockPos pos) {
        return BLOCKS.containsKey(pos.asLong());
    }

    public static boolean isRope(BlockPos pos) {
        Ship s = BLOCKS.get(pos.asLong());
        return s != null && s.rope.contains(pos.asLong());
    }

    // ---- The design (relative to the deck centre; x = length, z = width) ----

    private record Part(int dx, int dy, int dz, BlockState state) {}

    private static List<Part> design() {
        List<Part> parts = new ArrayList<>();
        BlockState hull = Blocks.DARK_OAK_PLANKS.defaultBlockState(), deck = Blocks.SPRUCE_PLANKS.defaultBlockState();
        BlockState rail = Blocks.SPRUCE_FENCE.defaultBlockState(), wool = Blocks.WOOL.pick(net.minecraft.world.item.DyeColor.WHITE).defaultBlockState();
        BlockState stripe = Blocks.WOOL.pick(net.minecraft.world.item.DyeColor.RED).defaultBlockState(), chain = Blocks.IRON_CHAIN.defaultBlockState();
        BlockState trim = Blocks.STRIPPED_DARK_OAK_LOG.defaultBlockState();
        // Hull (two layers under the deck, narrowing at the bottom).
        for (int dx = -7; dx <= 7; dx++) for (int dz = -1; dz <= 1; dz++) parts.add(new Part(dx, -2, dz, hull));
        for (int dx = -8; dx <= 8; dx++) for (int dz = -2; dz <= 2; dz++) parts.add(new Part(dx, -1, dz, Math.abs(dz) == 2 ? trim : hull));
        // Deck.
        for (int dx = -9; dx <= 9; dx++)
            for (int dz = -3; dz <= 3; dz++) {
                if (Math.abs(dx) == 9 && Math.abs(dz) > 1) continue; // rounded bow and stern
                parts.add(new Part(dx, 0, dz, deck));
            }
        parts.add(new Part(10, 0, 0, trim)); // bowsprit
        // Rails, with a gap on the +z side in the middle for the rope.
        for (int dx = -9; dx <= 9; dx++)
            for (int dz = -3; dz <= 3; dz++) {
                boolean edge = Math.abs(dz) == 3 || (Math.abs(dx) == 9 && Math.abs(dz) <= 1) || (Math.abs(dx) == 8 && Math.abs(dz) == 2);
                if (Math.abs(dx) == 9 && Math.abs(dz) > 1) continue;
                if (!edge || (dz == 3 && Math.abs(dx) <= 1)) continue;
                parts.add(new Part(dx, 1, dz, rail));
            }
        // Chains up to the balloon.
        for (int sx : new int[]{-6, 6}) for (int sz : new int[]{-2, 2}) for (int dy = 1; dy <= 5; dy++) parts.add(new Part(sx, dy, sz, chain));
        // Balloon.
        for (int dy = 6; dy <= 9; dy++) {
            int lx = dy == 6 || dy == 9 ? 6 : 8, lz = dy == 6 || dy == 9 ? 2 : 3;
            for (int dx = -lx; dx <= lx; dx++)
                for (int dz = -lz; dz <= lz; dz++) {
                    if (Math.abs(dx) == lx && Math.abs(dz) == lz) continue;
                    parts.add(new Part(dx, dy, dz, dy == 7 && Math.abs(dz) == lz ? stripe : wool));
                }
        }
        // Lanterns on the deck.
        BlockState lantern = Blocks.LANTERN.defaultBlockState().setValue(LanternBlock.HANGING, false);
        parts.add(new Part(-8, 1, 0, lantern));
        parts.add(new Part(8, 1, 0, lantern));
        return parts;
    }

    // ---- Placing and removing ----

    /** Finds empty sky above the island and builds the ship there. Null if there's no room. */
    public static Ship place(ServerLevel level, Islands.Island is) {
        List<Part> parts = design();
        double half = is.size / 2.0 - 12;
        double[] home = is.home;
        int hx = (int) Math.floor(home[0]), hz = (int) Math.floor(home[2]);
        // Try spots near the island home first, then further out.
        List<int[]> spots = new ArrayList<>();
        spots.add(new int[]{hx, hz});
        for (int r = 8; r <= 64; r += 8)
            for (int a = 0; a < 8; a++)
                spots.add(new int[]{hx + (int) Math.round(Math.cos(a * Math.PI / 4) * r), hz + (int) Math.round(Math.sin(a * Math.PI / 4) * r)});
        for (int[] s : spots) {
            if (Math.abs(s[0] + 0.5 - is.cx() - 0.5) > half || Math.abs(s[1] + 0.5 - is.cz() - 0.5) > half) continue;
            int top = Integer.MIN_VALUE;
            for (int dx = -11; dx <= 11; dx += 2)
                for (int dz = -5; dz <= 5; dz += 2) {
                    level.getChunk((s[0] + dx) >> 4, (s[1] + dz) >> 4);
                    top = Math.max(top, level.getHeight(Heightmap.Types.MOTION_BLOCKING, s[0] + dx, s[1] + dz));
                }
            int deckY = Math.max(top + 10, (int) home[1] + 12);
            if (deckY + 11 > level.getMaxY() - 2) continue;
            BlockPos c = new BlockPos(s[0], deckY, s[1]);
            boolean clear = true;
            for (Part p : parts) {
                if (!level.getBlockState(c.offset(p.dx, p.dy, p.dz)).isAir()) { clear = false; break; }
            }
            if (!clear) continue;
            Ship ship = new Ship(is.id, level, c);
            for (Part p : parts) {
                BlockPos at = c.offset(p.dx, p.dy, p.dz);
                level.setBlock(at, p.state, Block.UPDATE_ALL);
                ship.placed.add(at);
                BLOCKS.put(at.asLong(), ship);
            }
            // The rope: chains hanging from the rail gap down toward the ground (only through air).
            for (int dy = 0; dy >= -40; dy--) {
                BlockPos at = c.offset(0, dy, 4);
                if (!level.getBlockState(at).isAir()) break;
                level.setBlock(at, Blocks.IRON_CHAIN.defaultBlockState(), Block.UPDATE_ALL);
                ship.placed.add(at);
                ship.rope.add(at.asLong());
                BLOCKS.put(at.asLong(), ship);
            }
            // Captain Wren at the helm.
            Villager v = net.minecraft.world.entity.EntityTypes.VILLAGER.create(level, EntitySpawnReason.COMMAND);
            if (v != null) {
                v.snapTo(c.getX() - 4.5, c.getY() + 1, c.getZ() + 0.5, -90f, 0f);
                v.setNoAi(true);
                v.setPermanentlyInvulnerable(true);
                v.setSilent(true);
                v.setPersistenceRequired();
                v.setCustomName(Component.literal("§6Captain Wren §7(right-click to set sail)"));
                v.setCustomNameVisible(true);
                v.addTag(WREN_TAG);
                level.addFreshEntity(v);
                ship.wren = v.getUUID();
            }
            return ship;
        }
        return null;
    }

    /** Takes the ship away: only blocks it placed (and that are still there) are removed. */
    public static void remove(Ship ship) {
        if (ship == null) return;
        // Top down, so chains and lanterns don't drop as items.
        List<BlockPos> list = new ArrayList<>(ship.placed);
        list.sort((a, b) -> Integer.compare(b.getY(), a.getY()));
        for (BlockPos at : list) {
            BLOCKS.remove(at.asLong());
            if (!ship.level.getBlockState(at).isAir()) ship.level.setBlock(at, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
        }
        ship.placed.clear();
        if (ship.wren != null) {
            var e = ship.level.getEntity(ship.wren);
            if (e != null) e.discard();
        }
    }

    /** Facing used by stairs etc. (kept for future designs). */
    static Direction facing() {
        return Direction.EAST;
    }
}
