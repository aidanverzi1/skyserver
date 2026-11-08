package com.aidan.skyserver.goldwars;

import com.aidan.skyserver.Skyserver;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.AABB;

import java.util.*;

/**
 * Places the Gold Wars maps in their own dimension (skyserver:arena): an empty void world
 * that is always noon and where no mobs spawn.
 *
 * The map designs themselves live in MapDesigns (plain Java, no Minecraft code), which
 * describes every block as a text like "oak_stairs[facing=east,half=bottom]". This class
 * turns that text into real blocks and handles the build-phase barriers and map rules.
 */
public final class GoldWarsMap {

    public enum Layout {
        SKY_ISLANDS("sky", "Skyreach Isles"),
        VILLAGE("village", "Harvest Village"),
        CAVES("caves", "Deepforge Caves");

        public final String id;
        public final String display;

        Layout(String id, String display) {
            this.id = id;
            this.display = display;
        }
    }

    // ---- Position and size (inside the skyserver:arena dimension) ----
    public static final int OX = 0;
    public static final int OZ = 0;
    public static final int FLOOR_Y = 200;          // y = 0 in MapDesigns
    public static final int VOID_Y = FLOOR_Y - 30;  // below this = dead
    public static final int SPAWN_PROTECT = MapDesigns.SPAWN_PROTECT;

    private static final int BUILD_X = MapDesigns.HALF_LENGTH + 10;
    private static final int BUILD_Z = MapDesigns.HALF_WIDTH + 12;
    private static final int BUILD_TOP = FLOOR_Y + 60;

    private static final int WALL_Z = BUILD_Z + 2;
    private static final int WALL_TOP = FLOOR_Y + 80;

    private static final int CLEAR_X = BUILD_X + 10;
    private static final int CLEAR_Z = BUILD_Z + 9;
    private static final int CLEAR_MIN_Y = FLOOR_Y - 40;
    private static final int CLEAR_MAX_Y = WALL_TOP + 2;

    /**
     * Place blocks without updating their neighbours. Otherwise a flower, wheat or mushroom placed
     * before the ground under it (or left behind when the old map is cleared) pops off as an item.
     */
    private static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;

    private static Layout current = Layout.SKY_ISLANDS;

    private GoldWarsMap() {}

    // =====================================================================
    // Layout selection
    // =====================================================================

    public static Layout current() {
        return current;
    }

    public static void setLayout(Layout layout) {
        current = layout;
    }

    public static void nextLayout() {
        Layout[] all = Layout.values();
        current = all[(current.ordinal() + 1) % all.length];
    }

    // =====================================================================
    // Key positions and rules (the same on every map)
    // =====================================================================

    public static BlockPos spawn(GwTeam team) {
        return new BlockPos(OX + team.side * MapDesigns.SPAWN_X, FLOOR_Y + 1, OZ);
    }

    public static float spawnYaw(GwTeam team) {
        return team == GwTeam.RED ? -90f : 90f;
    }

    public static List<BlockPos> goldBlocks(GwTeam team) {
        List<BlockPos> list = new ArrayList<>();
        for (int[] g : MapDesigns.goldPositions(team.side)) list.add(new BlockPos(OX + g[0], FLOOR_Y + g[1], OZ + g[2]));
        return list;
    }

    /** Middle of each of a team's two vaults (north first, then south), at the vault's middle height. */
    public static List<BlockPos> vaults(GwTeam team) {
        int x = OX + team.side * MapDesigns.VAULT_X + (team.side > 0 ? 0 : -1);
        return List.of(new BlockPos(x, FLOOR_Y + 2, OZ - MapDesigns.VAULT_Z - 1),
                new BlockPos(x, FLOOR_Y + 2, OZ + MapDesigns.VAULT_Z));
    }

    public static boolean inSpawnZone(BlockPos p) {
        for (GwTeam t : GwTeam.values()) {
            BlockPos s = spawn(t);
            if (Math.abs(p.getX() - s.getX()) <= SPAWN_PROTECT && Math.abs(p.getZ() - s.getZ()) <= SPAWN_PROTECT) return true;
        }
        return false;
    }

    public static boolean inBuildArea(BlockPos p) {
        int dx = Math.abs(p.getX() - OX);
        int dz = Math.abs(p.getZ() - OZ);
        if (current == Layout.CAVES) {
            return dx <= MapDesigns.CAVE_X && dz <= MapDesigns.CAVE_Z
                    && p.getY() >= FLOOR_Y - MapDesigns.CAVE_DIG && p.getY() <= FLOOR_Y + MapDesigns.CAVE_BUILD_TOP;
        }
        return dx <= BUILD_X && dz <= BUILD_Z && p.getY() <= BUILD_TOP && p.getY() > VOID_Y;
    }

    public static boolean onPlatform(BlockPos p) {
        int dx = Math.abs(p.getX() - OX);
        int dz = Math.abs(p.getZ() - OZ);
        if (current == Layout.CAVES) return dx <= MapDesigns.CAVE_X && dz <= MapDesigns.CAVE_Z;
        return dx <= MapDesigns.HALF_LENGTH && dz <= MapDesigns.HALF_WIDTH;
    }

    public static boolean onWallLine(BlockPos p) {
        return p.getX() == OX;
    }

    public static AABB box() {
        return new AABB(OX - CLEAR_X, CLEAR_MIN_Y - 30, OZ - CLEAR_Z, OX + CLEAR_X, CLEAR_MAX_Y + 1, OZ + CLEAR_Z);
    }

    // =====================================================================
    // Building
    // =====================================================================

    /** Wipes the area and builds the current map with the build-phase barriers up. */
    public static void rebuild(ServerLevel level) {
        clear(level);
        MapCanvas canvas = MapDesigns.build(current.id);
        canvas.forEach((x, y, z, text) ->
                level.setBlock(new BlockPos(OX + x, FLOOR_Y + y, OZ + z), toState(text), FLAGS));
        setWall(level, true);
    }

    // ---- Turning "oak_stairs[facing=east]" into a real block ----

    private static final Map<String, BlockState> STATE_CACHE = new HashMap<>();
    private static final Set<String> MISSING = new HashSet<>();

    public static BlockState toState(String text) {
        BlockState cached = STATE_CACHE.get(text);
        if (cached == null) {
            cached = parseState(text);
            STATE_CACHE.put(text, cached);
        }
        return cached;
    }

    private static BlockState parseState(String text) {
        String name = MapCanvas.name(text);
        Identifier id = Identifier.withDefaultNamespace(name);
        if (!BuiltInRegistries.BLOCK.containsKey(id)) {
            if (MISSING.add(name)) Skyserver.LOGGER.warn("Gold Wars map: unknown block '{}', using stone instead", name);
            return Blocks.STONE.defaultBlockState();
        }
        BlockState state = BuiltInRegistries.BLOCK.getValue(id).defaultBlockState();
        for (Map.Entry<String, String> e : MapCanvas.props(text).entrySet()) {
            Property<?> prop = state.getBlock().getStateDefinition().getProperty(e.getKey());
            if (prop != null) state = withValue(state, prop, e.getValue());
        }
        return state;
    }

    private static <T extends Comparable<T>> BlockState withValue(BlockState state, Property<T> prop, String value) {
        Optional<T> v = prop.getValue(value);
        return v.isPresent() ? state.setValue(prop, v.get()) : state;
    }

    // =====================================================================
    // Build-phase barriers
    // =====================================================================

    /**
     * Raises (true) or drops (false) the invisible wall down the middle, plus (outdoor maps)
     * an invisible fence around the edges so nobody falls or bridges off during the build phase.
     */
    public static void setWall(ServerLevel level, boolean up) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int z = -WALL_Z; z <= WALL_Z; z++)
            for (int y = VOID_Y; y <= WALL_TOP; y++)
                barrier(level, pos.set(OX, y, OZ + z), up);

        if (current == Layout.CAVES) return;

        int ex = MapDesigns.HALF_LENGTH + 1;
        int ez = MapDesigns.HALF_WIDTH + 1;
        for (int y = FLOOR_Y - 5; y <= WALL_TOP; y++) {
            for (int x = -ex; x <= ex; x++) {
                barrier(level, pos.set(OX + x, y, OZ - ez), up);
                barrier(level, pos.set(OX + x, y, OZ + ez), up);
            }
            for (int z = -ez; z <= ez; z++) {
                barrier(level, pos.set(OX - ex, y, OZ + z), up);
                barrier(level, pos.set(OX + ex, y, OZ + z), up);
            }
        }
    }

    private static void barrier(ServerLevel level, BlockPos pos, boolean up) {
        BlockState current = level.getBlockState(pos);
        if (up && current.isAir()) {
            level.setBlock(pos, Blocks.BARRIER.defaultBlockState(), FLAGS);
        } else if (!up && current.is(Blocks.BARRIER)) {
            level.setBlock(pos, Blocks.AIR.defaultBlockState(), FLAGS);
        }
    }

    private static void clear(ServerLevel level) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        BlockState air = Blocks.AIR.defaultBlockState();
        for (int x = OX - CLEAR_X; x <= OX + CLEAR_X; x++)
            for (int z = OZ - CLEAR_Z; z <= OZ + CLEAR_Z; z++)
                for (int y = CLEAR_MIN_Y; y <= CLEAR_MAX_Y; y++) {
                    pos.set(x, y, z);
                    BlockState current = level.getBlockState(pos);
                    if (current.isAir()) continue;
                    if (current.hasBlockEntity() && level.getBlockEntity(pos) instanceof Container c) c.clearContent();
                    level.setBlock(pos, air, FLAGS);
                }
        for (Entity e : level.getEntitiesOfClass(Entity.class, box(), e -> !(e instanceof Player))) e.discard();
    }
}
