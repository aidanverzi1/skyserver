package com.aidan.skyserver.expedition;

import com.aidan.skyserver.Safe;
import com.aidan.skyserver.lobby.Announce;
import com.aidan.skyserver.lobby.Lobby;
import com.aidan.skyserver.lobby.Stacks;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ClientboundMapItemDataPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.saveddata.maps.MapDecoration;
import net.minecraft.world.level.saveddata.maps.MapDecorationType;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import net.minecraft.world.phys.AABB;

import java.util.*;

/**
 * 5.23: WREN'S CHALK MAP for the Ancient City - a vanilla map item the server draws itself (no mods needed).
 *
 *  - Everyone gets one on arrival (it can't be dropped; it's taken back when they leave). It draws ONLY where that
 *    player has walked: the floor they crossed (pale on carpet, white on bare floor), what they could see a couple
 *    of blocks around them (dark grey floor, tan carpet) and the walls beside it (light grey). Never through walls.
 *  - Close-up (default): the quarter of the city you're in, 2 pixels per block. Right-click it for the whole city
 *    (1 pixel per block) and back.
 *  - A gold banner marker on every room whose chest YOU looted. Nothing else about rooms is shown (no decoys, no
 *    sealed doors). A small red X on dead ends you walked into. Sensors an Echo Lens showed you: cyan dots.
 *  - Your arrow only shows after you've stood still for STILL_TICKS holding the map.
 *  - Stand within MERGE_RANGE of a crewmate for MERGE_TICKS: your maps merge (paths, walls, dead ends, sensors -
 *    not the gold markers, those stay your own).
 *  - Wren's lift has a 3 x 3 WALL MAP (item frames on the back wall, 3 pixels per block) showing the whole crew's
 *    chalk put together.
 *  - The map starts blank every trip (the maze is the same all week, so a map kept between trips would give it away).
 *
 * The map ids are far above anything vanilla hands out, and no saved map data exists for them: the server only
 * ever sends the pictures to the players in the city.
 */
final class CityChalk {
    private CityChalk() {}

    static final String MAP_TAG = "city_chalk_map", FRAME_TAG = "skyserver_city_chalk";
    static final int STILL_TICKS = 40, MERGE_TICKS = 60;
    static final double MERGE_RANGE = 3.0;
    /** Floor seen within this many blocks of where you walk (not through walls) - enough for a 3-wide corridor. */
    static final int SEE = 3;
    private static final int MAP_ID_BASE = 1_900_000_000;

    // Cell kinds in the plan.
    private static final byte UNKNOWN = 0, FLOOR = 1, CARPET = 2, WALL = 3;

    private static byte col(MapColor c, MapColor.Brightness b) {
        return c.getPackedId(b);
    }

    /** One player's chalk. */
    static final class Chalk {
        final int mapId;
        final byte[] seen;         // per block: UNKNOWN / FLOOR / CARPET / WALL
        final boolean[] walked;
        final Set<Integer> deadEnds = new HashSet<>();   // cell index i * N + j
        final Set<Long> sensors = new HashSet<>();
        boolean whole = false, dirty = true, arrow = false;
        int lastQ = -1;
        long stillSince = -1, lastSent = -100;
        int lastBx = Integer.MIN_VALUE, lastBz = Integer.MIN_VALUE;
        float lastYaw;

        Chalk(int mapId, int size) {
            this.mapId = mapId;
            seen = new byte[size * size];
            walked = new boolean[size * size];
        }
    }

    /** Per trip. */
    static final class Board {
        final int size, ox, oz, cx, cz;
        final byte[] plan;
        final boolean[] deadCell;
        final Map<UUID, Chalk> chalks = new HashMap<>();
        final Map<String, Long> together = new HashMap<>();
        final int wallBase;
        final List<UUID> frames = new ArrayList<>();
        boolean wallDirty = true;
        long wallSent = -1000;

        Board(int size, int ox, int oz, int cx, int cz, int wallBase) {
            this.size = size;
            this.ox = ox;
            this.oz = oz;
            this.cx = cx;
            this.cz = cz;
            this.plan = new byte[size * size];
            this.deadCell = new boolean[AncientCityMap.N * AncientCityMap.N];
            this.wallBase = wallBase;
        }

        int idx(int x, int z) {
            int lx = x - ox, lz = z - oz;
            return lx < 0 || lz < 0 || lx >= size || lz >= size ? -1 : lx * size + lz;
        }
    }

    private static final Map<Integer, Board> BOARDS = new HashMap<>(); // run id -> board

    // =====================================================================
    // Setup
    // =====================================================================

    static void init() {
        UseItemCallback.EVENT.register((player, level, hand) -> {
            if (!AncientCity.isCity(level) || !(player instanceof ServerPlayer sp)) return InteractionResult.PASS;
            ItemStack held = sp.getItemInHand(hand);
            if (!Stacks.is(held, MAP_TAG)) return InteractionResult.PASS;
            AncientCity.Run run = AncientCity.runOf(sp.getUUID());
            Board b = run == null ? null : BOARDS.get(run.id);
            Chalk c = b == null ? null : b.chalks.get(sp.getUUID());
            if (c != null) {
                c.whole = !c.whole;
                c.dirty = true;
                Announce.actionBar(sp, c.whole ? "§6Chalk map: §fwhole city" : "§6Chalk map: §fclose-up");
            }
            return InteractionResult.FAIL;
        });
        UseEntityCallback.EVENT.register((player, level, hand, entity, hit) ->
                AncientCity.isCity(level) && entity.entityTags().contains(FRAME_TAG) ? InteractionResult.FAIL : InteractionResult.PASS);
        AttackEntityCallback.EVENT.register((player, level, hand, entity, hit) ->
                AncientCity.isCity(level) && entity.entityTags().contains(FRAME_TAG) ? InteractionResult.FAIL : InteractionResult.PASS);
        ServerTickEvents.END_SERVER_TICK.register(s -> {
            if (s.getTickCount() % 20 == 3) Safe.run("city chalk sweep", () -> sweep(s));
        });
    }

    /** The crew landed: plan the board, hand out maps, hang the wall map. */
    static void start(ServerLevel l, AncientCity.Run run) {
        AncientCityMap.Layout lay = run.layout;
        int r = AncientCityMap.R, size = 2 * r + 1;
        int cx = lay.center.getX(), cz = lay.center.getZ();
        int base = MAP_ID_BASE + (run.id % 100_000) * 16;
        Board b = new Board(size, cx - r, cz - r, cx, cz, base + 4);
        BlockState carpet = Blocks.CARPET.pick(DyeColor.GRAY).defaultBlockState();
        int fy = AncientCityMap.FLOOR_Y;
        for (int x = 0; x < size; x++)
            for (int z = 0; z < size; z++) {
                long up = BlockPos.asLong(b.ox + x, fy + 1, b.oz + z);
                BlockState s1 = lay.blocks.get(up);
                byte k;
                if (s1 != null && !s1.isAir() && s1.isSolid()) k = WALL;
                else {
                    BlockState s0 = lay.blocks.get(BlockPos.asLong(b.ox + x, fy, b.oz + z));
                    k = s0 != null && s0 == carpet ? CARPET : FLOOR;
                }
                b.plan[x * size + z] = k;
            }
        // Dead ends: maze cells with three closed sides.
        int n = AncientCityMap.N;
        for (int i = 0; i < n; i++)
            for (int j = 0; j < n; j++) {
                int open = 0;
                int mi = cx + AncientCityMap.mid(i), mj = cz + AncientCityMap.mid(j);
                int[][] sides = {{cx + AncientCityMap.line(i), mj}, {cx + AncientCityMap.line(i + 1), mj},
                        {mi, cz + AncientCityMap.line(j)}, {mi, cz + AncientCityMap.line(j + 1)}};
                for (int[] sd : sides) {
                    int id = b.idx(sd[0], sd[1]);
                    if (id >= 0 && b.plan[id] != WALL) open++;
                }
                int mid = b.idx(mi, mj);
                b.deadCell[i * n + j] = open == 1 && mid >= 0 && b.plan[mid] != WALL && !lay.inLift(mi + 0.5, fy, mj + 0.5);
            }
        BOARDS.put(run.id, b);
        int k = 0;
        for (ServerPlayer p : AncientCity.inside(run)) {
            Chalk c = new Chalk(base + k++, size);
            b.chalks.put(p.getUUID(), c);
            give(p, c);
            reveal(b, c, p.blockPosition().getX(), p.blockPosition().getZ());
        }
        hangWallMap(l, run, b);
    }

    private static ItemStack mapItem(int id, String name, String... lore) {
        ItemStack m = new ItemStack(Items.FILLED_MAP);
        m.set(DataComponents.MAP_ID, new MapId(id));
        return Stacks.tag(Stacks.named(m, name, lore), MAP_TAG);
    }

    private static void give(ServerPlayer p, Chalk c) {
        ItemStack m = mapItem(c.mapId, "§6§lWren's Chalk Map", "§7Draws where you've walked.", "§7Gold flag = a room you looted.",
                "§7Stand still holding it to see yourself.", "§eRight-click: §fclose-up / whole city");
        Inventory inv = p.getInventory();
        if (inv.getItem(8).isEmpty()) inv.setItem(8, m);
        else if (!inv.add(m)) inv.setItem(8, m);
        c.dirty = true;
    }

    /** 3 x 3 item frames with maps on the lift's back wall (beside Wren). */
    private static void hangWallMap(ServerLevel l, AncientCity.Run run, Board b) {
        AncientCityMap.Layout lay = run.layout;
        int fy = AncientCityMap.FLOOR_Y, back = lay.lift[3] + 1, frontZ = lay.lift[3];
        // Try the east side of Wren first, then the west.
        int[][] options = {{b.cx + 4, -1}, {b.cx - 2, -1}};
        for (int[] o : options) {
            boolean ok = true;
            for (int c = 0; c < 3 && ok; c++)
                for (int r = 0; r < 3 && ok; r++) {
                    int x = o[0] - c, y = fy + 3 - r;
                    BlockState behind = lay.blocks.get(BlockPos.asLong(x, y, back));
                    BlockState front = lay.blocks.get(BlockPos.asLong(x, y, frontZ));
                    if (behind == null || behind.isAir() || !behind.isSolid() || front != null && !front.isAir()) ok = false;
                }
            if (!ok) continue;
            for (int c = 0; c < 3; c++)
                for (int r = 0; r < 3; r++) {
                    int x = o[0] - c, y = fy + 3 - r;
                    ItemFrame f = new ItemFrame(l, new BlockPos(x, y, frontZ), Direction.NORTH);
                    f.setItem(mapItem(b.wallBase + r * 3 + c, "§6Crew chalk map"), false);
                    f.addTag(FRAME_TAG);
                    if (l.addFreshEntity(f)) b.frames.add(f.getUUID());
                }
            return;
        }
        com.aidan.skyserver.Skyserver.LOGGER.warn("[City] trip #{}: no wall for the crew chalk map at the lift", run.id);
    }

    static void end(AncientCity.Run run) {
        BOARDS.remove(run.id);
    }

    // =====================================================================
    // Ticking (from AncientCity.fastTick, every tick)
    // =====================================================================

    static void tick(ServerLevel l, AncientCity.Run run, long ticks) {
        Board b = BOARDS.get(run.id);
        if (b == null) return;
        List<ServerPlayer> in = AncientCity.inside(run);
        if (ticks % 5 == 0) {
            for (ServerPlayer p : in) {
                Chalk c = b.chalks.get(p.getUUID());
                if (c == null) {
                    c = new Chalk(b.wallBase - 4 + Math.min(3, b.chalks.size()), b.size);
                    b.chalks.put(p.getUUID(), c);
                }
                int bx = p.blockPosition().getX(), bz = p.blockPosition().getZ();
                if (bx != c.lastBx || bz != c.lastBz) {
                    c.lastBx = bx;
                    c.lastBz = bz;
                    c.stillSince = ticks;
                    if (reveal(b, c, bx, bz)) b.wallDirty = true;
                    if (c.arrow) {
                        c.arrow = false;
                        c.dirty = true;
                    }
                    int q = quarter(b, bx, bz);
                    if (q != c.lastQ) {
                        c.lastQ = q;
                        c.dirty = true;
                    }
                }
                boolean holding = Stacks.is(p.getMainHandItem(), MAP_TAG) || Stacks.is(p.getOffhandItem(), MAP_TAG);
                boolean arrow = holding && c.stillSince >= 0 && ticks - c.stillSince >= STILL_TICKS;
                if (arrow != c.arrow || arrow && Math.abs(p.getYRot() - c.lastYaw) > 10) {
                    c.arrow = arrow;
                    c.lastYaw = p.getYRot();
                    c.dirty = true;
                }
            }
        }
        // Echo Lens sensors go on the lens owner's map.
        if (ticks % 10 == 0) for (Object[] ln : run.lenses) {
            Chalk c = b.chalks.get((UUID) ln[0]);
            ServerPlayer p = find(in, (UUID) ln[0]);
            if (c == null || p == null) continue;
            for (BlockPos s : run.layout.sensors)
                if (s.distSqr(p.blockPosition()) <= 144 && c.sensors.add(s.asLong())) {
                    c.dirty = true;
                    b.wallDirty = true;
                }
        }
        // Comparing chalk.
        if (ticks % 20 == 0) merges(b, in, ticks);
        // Stars change when a chest is looted: cheap to check by count.
        if (ticks % 20 == 0) for (ServerPlayer p : in) {
            Chalk c = b.chalks.get(p.getUUID());
            if (c != null) c.dirty |= starsChanged(run, p.getUUID(), c);
        }
        // Send.
        for (ServerPlayer p : in) {
            Chalk c = b.chalks.get(p.getUUID());
            if (c == null || !c.dirty || ticks - c.lastSent < 10) continue;
            c.dirty = false;
            c.lastSent = ticks;
            sendHand(run, b, c, p);
        }
        if (b.wallDirty && ticks - b.wallSent >= 40 && !b.frames.isEmpty()) {
            b.wallDirty = false;
            b.wallSent = ticks;
            sendWall(b, in);
        }
        if (ticks % 100 == 0) keepFrames(l, b);
    }

    private static final Map<UUID, Integer> STAR_COUNT = new HashMap<>();

    private static boolean starsChanged(AncientCity.Run run, UUID id, Chalk c) {
        int n = 0;
        for (AncientCityMap.House h : run.layout.houses)
            if (run.looted.getOrDefault(h.chest.asLong(), Set.of()).contains(id)) n++;
        Integer before = STAR_COUNT.put(id, n);
        return before == null || before != n;
    }

    private static ServerPlayer find(List<ServerPlayer> in, UUID id) {
        for (ServerPlayer p : in) if (p.getUUID().equals(id)) return p;
        return null;
    }

    /** 0..3: which quarter of the city a block is in. */
    private static int quarter(Board b, int x, int z) {
        return (x < b.cx ? 0 : 1) + (z < b.cz ? 0 : 2);
    }

    /**
     * Marks the block walked and reveals floor reachable within SEE blocks (without passing walls), plus the walls
     * touching it. Returns true if anything new was drawn.
     */
    private static boolean reveal(Board b, Chalk c, int bx, int bz) {
        int start = b.idx(bx, bz);
        if (start < 0) return false;
        boolean changed = false;
        if (!c.walked[start]) {
            c.walked[start] = true;
            changed = true;
        }
        ArrayDeque<int[]> q = new ArrayDeque<>();
        Set<Integer> done = new HashSet<>();
        q.add(new int[]{bx, bz});
        done.add(start);
        while (!q.isEmpty()) {
            int[] cur = q.poll();
            for (int dx = -1; dx <= 1; dx++)
                for (int dz = -1; dz <= 1; dz++) {
                    int x = cur[0] + dx, z = cur[1] + dz;
                    int id = b.idx(x, z);
                    if (id < 0) continue;
                    byte k = b.plan[id];
                    if (c.seen[id] == UNKNOWN) {
                        c.seen[id] = k;
                        changed = true;
                    }
                    if (k == WALL || Math.max(Math.abs(x - bx), Math.abs(z - bz)) >= SEE || !done.add(id)) continue;
                    q.add(new int[]{x, z});
                }
        }
        // Dead end?
        int n = AncientCityMap.N, i = (bx - b.cx + AncientCityMap.R) / AncientCityMap.P, j = (bz - b.cz + AncientCityMap.R) / AncientCityMap.P;
        if (i >= 0 && j >= 0 && i < n && j < n && b.deadCell[i * n + j] && c.deadEnds.add(i * n + j)) changed = true;
        if (changed) c.dirty = true;
        return changed;
    }

    private static void merges(Board b, List<ServerPlayer> in, long ticks) {
        for (int a = 0; a < in.size(); a++)
            for (int d = a + 1; d < in.size(); d++) {
                ServerPlayer p = in.get(a), o = in.get(d);
                String key = p.getUUID() + "|" + o.getUUID();
                if (p.distanceToSqr(o) > MERGE_RANGE * MERGE_RANGE) {
                    b.together.remove(key);
                    continue;
                }
                Long since = b.together.get(key);
                if (since == null) {
                    b.together.put(key, ticks);
                    continue;
                }
                if (since < 0 || ticks - since < MERGE_TICKS) continue;
                Chalk cp = b.chalks.get(p.getUUID()), co = b.chalks.get(o.getUUID());
                if (cp == null || co == null) continue;
                merge(cp, co);
                merge(co, cp);
                b.together.put(key, -1L); // merged; again only after they've been apart
                Lobby.msg(p, "§6You compared chalk with §f" + o.getName().getString() + "§6.");
                Lobby.msg(o, "§6You compared chalk with §f" + p.getName().getString() + "§6.");
            }
    }

    private static void merge(Chalk into, Chalk from) {
        for (int i = 0; i < into.seen.length; i++) {
            if (into.seen[i] == UNKNOWN && from.seen[i] != UNKNOWN) into.seen[i] = from.seen[i];
            if (from.walked[i]) into.walked[i] = true;
        }
        into.deadEnds.addAll(from.deadEnds);
        into.sensors.addAll(from.sensors);
        into.dirty = true;
    }

    // =====================================================================
    // Drawing
    // =====================================================================

    /** The colour of one block on someone's chalk (or the crew's, for the wall). */
    private static byte pixel(byte seen, boolean walked) {
        if (seen == UNKNOWN) return col(MapColor.COLOR_BLACK, MapColor.Brightness.NORMAL);
        if (seen == WALL) return col(MapColor.STONE, MapColor.Brightness.HIGH);
        if (walked) return seen == CARPET ? col(MapColor.SAND, MapColor.Brightness.HIGH) : col(MapColor.SNOW, MapColor.Brightness.NORMAL);
        return seen == CARPET ? col(MapColor.COLOR_BROWN, MapColor.Brightness.LOW) : col(MapColor.COLOR_GRAY, MapColor.Brightness.LOW);
    }

    /** View: blocks per map. Close-up = 64 blocks at 2 px, whole = 128 blocks at 1 px. Returns {originX, originZ, scale}. */
    private static int[] view(Board b, Chalk c) {
        if (c.whole) return new int[]{b.cx - 63, b.cz - 63, 1};
        int q = Math.max(0, c.lastQ);
        return new int[]{(q & 1) == 0 ? b.cx - 63 : b.cx - 1, (q & 2) == 0 ? b.cz - 63 : b.cz - 1, 2};
    }

    private static void sendHand(AncientCity.Run run, Board b, Chalk c, ServerPlayer p) {
        int[] v = view(b, c);
        int ox = v[0], oz = v[1], sc = v[2];
        byte[] px = new byte[128 * 128];
        for (int x = 0; x < 128; x++)
            for (int y = 0; y < 128; y++) {
                int bx = ox + x / sc, bz = oz + y / sc;
                int id = b.idx(bx, bz);
                px[x + y * 128] = id < 0 ? 0 : pixel(c.seen[id], c.walked[id]);
            }
        byte red = col(MapColor.COLOR_RED, MapColor.Brightness.HIGH), cyan = col(MapColor.COLOR_CYAN, MapColor.Brightness.HIGH);
        // Dead ends: a small X in the middle of the cell.
        int n = AncientCityMap.N;
        for (int cell : c.deadEnds) {
            int bx = b.cx + AncientCityMap.mid(cell / n), bz = b.cz + AncientCityMap.mid(cell % n);
            int pxX = (bx - ox) * sc + sc / 2, pxY = (bz - oz) * sc + sc / 2, r = sc == 2 ? 2 : 1;
            for (int t = -r; t <= r; t++) {
                put(px, pxX + t, pxY + t, red);
                put(px, pxX + t, pxY - t, red);
            }
        }
        for (long s : c.sensors) {
            BlockPos sp = BlockPos.of(s);
            int pxX = (sp.getX() - ox) * sc, pxY = (sp.getZ() - oz) * sc;
            for (int dx = 0; dx < sc; dx++) for (int dy = 0; dy < sc; dy++) put(px, pxX + dx, pxY + dy, cyan);
        }
        List<MapDecoration> decos = new ArrayList<>();
        Holder<MapDecorationType> flag = deco("banner_yellow"), me = deco("player");
        for (AncientCityMap.House h : run.layout.houses) {
            if (!run.looted.getOrDefault(h.chest.asLong(), Set.of()).contains(p.getUUID())) continue;
            double mx = (h.door.getX() + h.chest.getX()) / 2.0 + 0.5, mz = (h.door.getZ() + h.chest.getZ()) / 2.0 + 0.5;
            MapDecoration d = decoAt(flag, mx, mz, ox, oz, sc, 0);
            if (d != null) decos.add(d);
        }
        if (c.arrow) {
            float yaw = p.getYRot();
            int rot = ((int) ((yaw < 0 ? yaw - 8 : yaw + 8) * 16.0 / 360.0)) & 15;
            MapDecoration d = decoAt(me, p.getX(), p.getZ(), ox, oz, sc, rot);
            if (d != null) decos.add(d);
        }
        p.connection.send(new ClientboundMapItemDataPacket(new MapId(c.mapId), (byte) 0, true, Optional.of(decos),
                Optional.of(new MapItemSavedData.MapPatch(0, 0, 128, 128, px))));
    }

    /** The crew's chalk on the 3 x 3 wall map (3 px per block). */
    private static void sendWall(Board b, List<ServerPlayer> in) {
        int size = b.size;
        byte[] seen = new byte[size * size];
        boolean[] walked = new boolean[size * size];
        Set<Integer> dead = new HashSet<>();
        for (Chalk c : b.chalks.values()) {
            for (int i = 0; i < seen.length; i++) {
                if (seen[i] == UNKNOWN) seen[i] = c.seen[i];
                walked[i] |= c.walked[i];
            }
            dead.addAll(c.deadEnds);
        }
        int sc = 3, margin = (384 - size * sc) / 2;
        byte red = col(MapColor.COLOR_RED, MapColor.Brightness.HIGH);
        byte[][] tiles = new byte[9][128 * 128];
        for (int X = 0; X < 384; X++)
            for (int Y = 0; Y < 384; Y++) {
                int lx = (X - margin) / sc, lz = (Y - margin) / sc;
                byte v = X < margin || Y < margin || lx >= size || lz >= size ? 0 : pixel(seen[lx * size + lz], walked[lx * size + lz]);
                tiles[(Y / 128) * 3 + X / 128][(X % 128) + (Y % 128) * 128] = v;
            }
        int n = AncientCityMap.N;
        for (int cell : dead) {
            int lx = AncientCityMap.mid(cell / n) + AncientCityMap.R, lz = AncientCityMap.mid(cell % n) + AncientCityMap.R;
            int X0 = margin + lx * sc + 1, Y0 = margin + lz * sc + 1;
            for (int t = -2; t <= 2; t++) {
                putWall(tiles, X0 + t, Y0 + t, red);
                putWall(tiles, X0 + t, Y0 - t, red);
            }
        }
        for (int t = 0; t < 9; t++) {
            ClientboundMapItemDataPacket pk = new ClientboundMapItemDataPacket(new MapId(b.wallBase + t), (byte) 0, true, Optional.of(List.of()),
                    Optional.of(new MapItemSavedData.MapPatch(0, 0, 128, 128, tiles[t])));
            for (ServerPlayer p : in) p.connection.send(pk);
        }
    }

    private static void put(byte[] px, int x, int y, byte v) {
        if (x >= 0 && y >= 0 && x < 128 && y < 128) px[x + y * 128] = v;
    }

    private static void putWall(byte[][] tiles, int X, int Y, byte v) {
        if (X < 0 || Y < 0 || X >= 384 || Y >= 384) return;
        tiles[(Y / 128) * 3 + X / 128][(X % 128) + (Y % 128) * 128] = v;
    }

    private static Holder<MapDecorationType> deco(String id) {
        return BuiltInRegistries.MAP_DECORATION_TYPE.wrapAsHolder(BuiltInRegistries.MAP_DECORATION_TYPE.getValue(Identifier.withDefaultNamespace(id)));
    }

    /** A marker at world x/z on a view, or null if it's off the map. Map markers use -128..127 for the 128 pixels. */
    private static MapDecoration decoAt(Holder<MapDecorationType> type, double x, double z, int ox, int oz, int sc, int rot) {
        double pxX = (x - ox) * sc, pxY = (z - oz) * sc;
        if (pxX < 0 || pxY < 0 || pxX >= 128 || pxY >= 128) return null;
        byte dx = (byte) Math.max(-128, Math.min(127, (int) Math.round(pxX * 2 - 128))), dy = (byte) Math.max(-128, Math.min(127, (int) Math.round(pxY * 2 - 128)));
        return new MapDecoration(type, dx, dy, (byte) rot, Optional.empty());
    }

    // =====================================================================
    // Housekeeping
    // =====================================================================

    /** Frames knocked off or missing their map get fixed. */
    private static void keepFrames(ServerLevel l, Board b) {
        for (int t = 0; t < b.frames.size(); t++) {
            Entity e = l.getEntity(b.frames.get(t));
            if (e instanceof ItemFrame f && !Stacks.is(f.getItem(), MAP_TAG)) {
                f.setItem(mapItem(b.wallBase + t, "§6Crew chalk map"), false);
                b.wallDirty = true;
            }
        }
    }

    /**
     * Once a second: chalk maps belong in the city only (taken from anyone not on a trip), a player on a trip
     * without theirs gets it back, and dropped ones vanish.
     */
    private static void sweep(MinecraftServer s) {
        for (ServerPlayer p : s.getPlayerList().getPlayers()) {
            AncientCity.Run run = AncientCity.runOf(p.getUUID());
            Board b = run == null ? null : BOARDS.get(run.id);
            Inventory inv = p.getInventory();
            boolean has = false;
            for (int i = 0; i < inv.getContainerSize(); i++) {
                if (!Stacks.is(inv.getItem(i), MAP_TAG)) continue;
                if (b == null || !AncientCity.isCity(p.level())) inv.setItem(i, ItemStack.EMPTY);
                else has = true;
            }
            if (Stacks.is(p.containerMenu.getCarried(), MAP_TAG)) has = true;
            if (b != null && !has && AncientCity.isCity(p.level()) && !AncientCity.isDowned(p.getUUID())) {
                Chalk c = b.chalks.get(p.getUUID());
                if (c != null) give(p, c);
            }
        }
        ServerLevel l = AncientCity.level();
        if (l == null) return;
        for (Board b : BOARDS.values()) {
            int r = AncientCityMap.R + 3;
            AABB box = new AABB(b.cx - r, AncientCityMap.FLOOR_Y - 10, b.cz - r, b.cx + r, AncientCityMap.FLOOR_Y + AncientCityMap.DOME_HEIGHT + 2, b.cz + r);
            for (ItemEntity ie : l.getEntitiesOfClass(ItemEntity.class, box, ie -> Stacks.is(ie.getItem(), MAP_TAG))) ie.discard();
        }
        BOARDS.keySet().removeIf(id -> {
            for (AncientCity.Run r : AncientCity.runs()) if (r.id == id) return false;
            return true;
        });
        STAR_COUNT.keySet().removeIf(id -> AncientCity.runOf(id) == null);
    }
}
