package com.aidan.skyserver.island;

import com.aidan.skyserver.Skyserver;
import com.aidan.skyserver.bots.BotPlayer;
import com.aidan.skyserver.goldwars.GoldWarsMap;
import com.aidan.skyserver.goldwars.MapCanvas;
import com.aidan.skyserver.lobby.*;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.*;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.protocol.game.ClientboundInitializeBorderPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.border.WorldBorder;

import java.util.*;

/**
 * Sky Island: everyone's own island in the skyserver:islands dimension, the main way to make
 * progress. Island inventories are kept apart from survival (see IslandStore).
 *
 *  - /is takes you to your island (building one the first time). Islands are 1,000 blocks apart.
 *  - You can build out to 100 x 100 for free; bigger areas (up to 500 x 500) are bought with
 *    /is upgrade. The edge shows as a world border.
 *  - You can't fall off: dropping into the void puts you back at the island's home, items and all.
 *  - Dying on your island (a long fall, lava, drowning...) also keeps everything: you're healed and
 *    sent to the island home instead.
 *  - No hostile mobs spawn on the islands (the islands use the "void" biome, which spawns none).
 *  - Visitors need the owner's OK (or an open island) and can't fight, break, place or open
 *    containers; they can use doors, trapdoors, gates, buttons and crafting tables.
 *  - Co-op: the owner can add up to 4 members who share the island and build with them.
 *    5.9: a co-op member who is also a moderator (/mod) can invite new members too - to the
 *    island they're on only. Nobody else but the owner can.
 *  - Trust: owners and members can let someone build for a while without joining
 *    (/is trust <player> [minutes], 60 by default, up to 24 hours; /is untrust <player>).
 *    Trusted players can't open or break chests, furnaces and other containers, or use lava/fire.
 *  - Each island has its own time of day: sleeping only brings morning to your island (IslandTime).
 *  - Island worth comes from the value of the blocks placed on it (/is top). Challenges unlock by
 *    server level. Upgrades (generator ores, crop growth, island size) are in the /is menu.
 */
public final class Islands {
    public static final ResourceKey<Level> ISLANDS = ResourceKey.create(Registries.DIMENSION, Identifier.fromNamespaceAndPath(Skyserver.MOD_ID, "islands"));
    public static final int SPACING = 1000, GRID = 64, TOP_Y = 100, VOID_Y = 30;
    public static final int START_SIZE = 100, MAX_SIZE = 500, SIZE_STEP = 50;
    public static final int MAX_MEMBERS = 4;
    /** Trust: default and longest time, in minutes. */
    public static final int TRUST_DEFAULT_MIN = 60, TRUST_MAX_MIN = 24 * 60;
    /** Prices for each size upgrade (100 -> 150 -> ... -> 500). */
    public static final long[] SIZE_PRICES = {15_000, 30_000, 50_000, 75_000, 110_000, 150_000, 200_000, 275_000};
    /** Generator levels 1..5 (index = level - 1; level 1 is free). */
    public static final long[] GEN_PRICES = {0, 5_000, 15_000, 40_000, 90_000};
    /** Crop growth boost levels 0..3. */
    public static final long[] CROP_PRICES = {0, 8_000, 25_000, 60_000};
    public static final long EXTRA_GENERATOR_PRICE = 10_000;
    /** Most working generators one island can have (keeps generator income in line with the Mine). */
    public static final int MAX_GENERATORS = 3;
    /** 5.8: most mob spawners that work on one island (the rest sit idle). */
    public static final int MAX_SPAWNERS = 6;

    /** Called by SpawnerCapMixin every spawner tick: may this spawner spawn mobs? */
    public static boolean spawnerMayRun(ServerLevel level, BlockPos pos) {
        if (!isIsland(level)) return true;
        Island is = islandAt(pos.getX(), pos.getZ());
        if (is == null) return false;
        long k = pos.asLong();
        if (is.spawners.contains(k)) return true;
        is.spawners.removeIf(x -> level.isLoaded(BlockPos.of(x)) && !level.getBlockState(BlockPos.of(x)).is(Blocks.SPAWNER));
        if (is.spawners.size() >= MAX_SPAWNERS) return false;
        is.spawners.add(k);
        return true;
    }
    /**
     * Generator speed: ticks before a mined block grows back, by speed level (1s, 0.75s, 0.5s).
     * Faster speeds need a higher generator level and cost a lot. Income is limited by how fast a
     * player can actually mine (about 2 blocks a second at best), so even the top speed can't make
     * more than roughly $3,500 an hour - about what the Mine pays.
     */
    public static final int[] GEN_SPEED_TICKS = {20, 15, 10};
    public static final long[] GEN_SPEED_PRICES = {0, 25_000, 75_000};
    public static final int[] GEN_SPEED_NEEDS_LEVEL = {0, 3, 5};

    public static int regrowTicks(Island is) {
        return GEN_SPEED_TICKS[is == null ? 0 : Math.max(0, Math.min(GEN_SPEED_TICKS.length - 1, is.genSpeed))];
    }

    public static String buyGenSpeed(ServerPlayer p, Island is) {
        if (is.genSpeed >= GEN_SPEED_TICKS.length - 1) return "§7Your generators are already at top speed.";
        int next = is.genSpeed + 1;
        if (is.genLevel < GEN_SPEED_NEEDS_LEVEL[next]) return "§cSpeed " + next + " needs generator level " + GEN_SPEED_NEEDS_LEVEL[next] + " first.";
        if (!payUpgrade(p, is, GEN_SPEED_PRICES[next])) return "§cThat costs " + Economy.format(GEN_SPEED_PRICES[next]) + fundNote(is) + ".";
        is.genSpeed = next;
        save();
        return "§aGenerator speed upgraded! §7Blocks now grow back every §f" + (GEN_SPEED_TICKS[next] / 20.0) + "s§7.";
    }

    // =====================================================================
    // Data
    // =====================================================================

    public static final class Island {
        public final int id;
        public UUID owner;
        public String ownerName;
        public final List<UUID> members = new ArrayList<>();
        public final Map<UUID, String> memberNames = new HashMap<>();
        public int size = START_SIZE, genLevel = 1, cropLevel = 0, genSpeed = 0;
        public long points = 0;
        public double[] home;
        public boolean open = false;
        public final Set<String> completed = new HashSet<>();
        public final Map<String, String> repeatedOn = new HashMap<>();
        /** Captain Wren's quest line: 0 = not started, 1.. = current step, past the end = all done. */
        public int quest = 0, questCount = 0;
        /** Which version of Captain Wren's quest line "quest" is numbered for (see IslandQuests.migrate). */
        public int questLine = IslandQuests.LINE_VERSION;
        public final Map<UUID, Long> questBase = new HashMap<>(), questBanked = new HashMap<>();
        /** When each person (owner included) joined the island, in epoch milliseconds. */
        public final Map<UUID, Long> memberSince = new HashMap<>();
        /** How much each person did toward Captain Wren's Legend quests (chapter 3), in quests. */
        public final Map<UUID, Double> questCredit = new HashMap<>();
        /** 1 = original starter, 2 = has the little island and Captain Wren. */
        public int version = 1;
        /** Shared upgrade fund (/is contribute): upgrades are paid from here first. */
        public long fund = 0;
        /** Named warp spots on the island (/is setwarp), up to MAX_WARPS. */
        public final Map<String, double[]> warps = new LinkedHashMap<>();
        /** Positions of this island's working generators (lodestones), up to MAX_GENERATORS. */
        public final Set<Long> gens = new LinkedHashSet<>();
        /** 5.8: spawners that are allowed to run (first MAX_SPAWNERS found; not saved, rebuilt as they tick). */
        public final Set<Long> spawners = new LinkedHashSet<>();
        /** Captain Wren has handed over the starter animals. */
        public boolean herd = false;
        /** Lowest block anyone has built on this island (starts at the starter island's bottom). */
        public int minY = designMinY();
        /** Players allowed to build for a while (/is trust): player -> when it ends (epoch ms). */
        public final Map<UUID, Long> trusted = new HashMap<>();
        public final Map<UUID, String> trustedNames = new HashMap<>();
        /** Where Captain Wren stands {x, y, z, yaw}, or null for his default spot by the path. */
        public double[] wren = null;
        /** This island's own time of day: ticks added to the shared clock (0..23999). */
        public long timeOffset = 0;

        Island(int id) {
            this.id = id;
        }

        public int cx() { return SPACING * (id % GRID); }
        public int cz() { return SPACING * (id / GRID); }

        /**
         * The island's level for challenges and Captain Wren's level quests: the highest server
         * level of anyone on the island (owner or co-op member, online or not).
         */
        public int level() {
            int best = Levels.level(owner);
            for (UUID m : members) best = Math.max(best, Levels.level(m));
            return best;
        }

        /** Island worth: every 100 points of placed-block value (for /is top bragging rights). */
        public int worth() {
            return (int) (points / 100);
        }

        public boolean isMember(UUID u) {
            return owner.equals(u) || members.contains(u);
        }

        /** Has build permission from /is trust right now. */
        public boolean isTrusted(UUID u) {
            Long until = trusted.get(u);
            return until != null && until > System.currentTimeMillis();
        }

        /** Members, or trusted players. */
        public boolean canModify(UUID u) {
            return isMember(u) || isTrusted(u);
        }

        public boolean inBorder(double x, double z) {
            double h = size / 2.0;
            return Math.abs(x - cx() - 0.5) <= h && Math.abs(z - cz() - 0.5) <= h;
        }

        public String name() {
            return ownerName + "'s island";
        }
    }

    private static final Map<Integer, Island> ISLANDS_BY_ID = new HashMap<>();
    private static final Map<UUID, Integer> ISLAND_OF = new HashMap<>(); // which island a player belongs to
    private static final Map<UUID, Integer> OWNS = new HashMap<>();      // the island a player owns (kept while they co-op elsewhere)
    private static int nextId = 1;
    private static boolean loaded = false;

    private static MinecraftServer server;
    private static long ticks = 0;
    // Pending things: visit requests (visitor -> island, time), co-op invites (invitee -> island, time).
    private static final Map<UUID, long[]> VISIT_REQUESTS = new HashMap<>();
    private static final Map<UUID, long[]> INVITES = new HashMap<>();
    private static final Map<Long, Long> REGEN = new HashMap<>(); // generator top pos -> tick to refill
    private static final Map<UUID, Integer> BORDER_SENT = new HashMap<>();

    private Islands() {}

    public static ServerLevel level() {
        return server == null ? null : server.getLevel(ISLANDS);
    }

    public static boolean isIsland(Level l) {
        return l != null && l.dimension() == ISLANDS;
    }

    public static Island islandOf(UUID player) {
        load();
        Integer id = ISLAND_OF.get(player);
        return id == null ? null : ISLANDS_BY_ID.get(id);
    }

    /** The island whose area contains this spot (or null). */
    public static Island islandAt(double x, double z) {
        load();
        int ix = Math.floorDiv((int) Math.floor(x) + SPACING / 2, SPACING), iz = Math.floorDiv((int) Math.floor(z) + SPACING / 2, SPACING);
        if (ix < 0 || ix >= GRID || iz < 0) return null;
        return ISLANDS_BY_ID.get(iz * GRID + ix);
    }

    public static Collection<Island> all() {
        load();
        return ISLANDS_BY_ID.values();
    }

    // =====================================================================
    // Setup
    // =====================================================================

    public static void init() {
        ServerLifecycleEvents.SERVER_STARTED.register(s -> {
            server = s;
            load();
            if (level() == null) Skyserver.LOGGER.error("Sky Island dimension skyserver:islands is missing (resources/data/skyserver/dimension/islands.json).");
        });
        ServerTickEvents.END_SERVER_TICK.register(s -> com.aidan.skyserver.Safe.run("islands tick", () -> tick()));
        ServerLifecycleEvents.SERVER_STOPPING.register(s -> {
            if (loaded) save(); // runs before the background saver stops (registered first), or right away after it
        });
        ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> {
            if (alive || !isIsland(oldPlayer.level())) return;
            // Died on the islands: back to the island home (or their own island).
            Island at = islandAt(oldPlayer.getX(), oldPlayer.getZ());
            Island own = islandOf(newPlayer.getUUID());
            Island target = at != null && (at.isMember(newPlayer.getUUID()) || at.open) ? at : own;
            if (target != null) Lobby.runLater(() -> teleportHome(newPlayer, target));
        });
        // Dying on the islands keeps everything: heal and go home instead (no death screen, nothing drops).
        ServerLivingEntityEvents.ALLOW_DEATH.register((entity, source, amount) -> {
            if (!(entity instanceof ServerPlayer p) || p instanceof BotPlayer || !isIsland(p.level())) return true;
            saveFromDeath(p);
            return false;
        });
        IslandTime.init();
        protection();
        ChestPages.init(); // 5.10: paged chests with a sort button (after protection, so its checks run first)
        net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback.EVENT.register((d, r, e) -> registerCommands(d));
        net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback.EVENT.register((d, r, e) -> d.register(Commands.literal("ic")
                .then(Commands.argument("message", StringArgumentType.greedyString())
                        .executes(c -> { chat(c.getSource().getPlayerOrException(), StringArgumentType.getString(c, "message")); return 1; }))));
        Skyserver.LOGGER.info("Sky Island ready: /is");
    }

    /** Instead of dying on the islands: full health, fire out, bad effects gone, back to the island home. */
    private static void saveFromDeath(ServerPlayer p) {
        p.setHealth(p.getMaxHealth());
        p.clearFire();
        p.resetFallDistance();
        p.setDeltaMovement(0, 0, 0);
        List<MobEffectInstance> bad = new ArrayList<>();
        for (MobEffectInstance e : p.getActiveEffects()) if (e.getEffect().value().getCategory() == MobEffectCategory.HARMFUL) bad.add(e);
        for (MobEffectInstance e : bad) p.removeEffect(e.getEffect());
        Island at = islandAt(p.getX(), p.getZ());
        Island own = islandOf(p.getUUID());
        Island target = at != null && (at.canModify(p.getUUID()) || at.open) ? at : own;
        Lobby.runLater(() -> {
            if (target != null) teleportHome(p, target);
            else Lobby.goToLobby(p);
            p.setHealth(p.getMaxHealth());
            p.resetFallDistance();
        });
        Lobby.msg(p, "§7Ouch! That would have hurt. §aYou keep everything on your island §7- back home you go.");
    }

    // =====================================================================
    // Saving
    // =====================================================================

    private static void load() {
        if (loaded) return;
        loaded = true;
        JsonObject root = Stacks.readFile("islands.json");
        if (root.has("next")) nextId = root.get("next").getAsInt();
        if (root.has("islands")) for (JsonElement e : root.getAsJsonArray("islands")) {
            JsonObject o = e.getAsJsonObject();
            Island is = new Island(o.get("id").getAsInt());
            is.owner = UUID.fromString(o.get("owner").getAsString());
            is.ownerName = o.has("ownerName") ? o.get("ownerName").getAsString() : "?";
            if (o.has("members")) for (Map.Entry<String, JsonElement> m : o.getAsJsonObject("members").entrySet()) {
                UUID u = UUID.fromString(m.getKey());
                is.members.add(u);
                is.memberNames.put(u, m.getValue().getAsString());
            }
            is.size = o.get("size").getAsInt();
            is.genLevel = o.get("gen").getAsInt();
            is.cropLevel = o.get("crops").getAsInt();
            is.points = o.get("points").getAsLong();
            is.open = o.has("open") && o.get("open").getAsBoolean();
            if (o.has("home")) {
                JsonArray h = o.getAsJsonArray("home");
                is.home = new double[]{h.get(0).getAsDouble(), h.get(1).getAsDouble(), h.get(2).getAsDouble(), h.get(3).getAsDouble()};
            }
            if (o.has("done")) for (JsonElement d : o.getAsJsonArray("done")) is.completed.add(d.getAsString());
            if (o.has("repeated")) for (Map.Entry<String, JsonElement> r : o.getAsJsonObject("repeated").entrySet()) is.repeatedOn.put(r.getKey(), r.getValue().getAsString());
            if (o.has("quest")) is.quest = o.get("quest").getAsInt();
            int line = o.has("questLine") ? o.get("questLine").getAsInt() : 1;
            is.quest = IslandQuests.migrate(is.quest, line);
            is.questLine = IslandQuests.LINE_VERSION;
            if (o.has("questCount")) is.questCount = o.get("questCount").getAsInt();
            if (o.has("version")) is.version = o.get("version").getAsInt();
            if (o.has("fund")) is.fund = o.get("fund").getAsLong();
            if (o.has("genSpeed")) is.genSpeed = o.get("genSpeed").getAsInt();
            is.herd = o.has("herd") && o.get("herd").getAsBoolean();
            if (o.has("minY")) is.minY = o.get("minY").getAsInt();
            if (o.has("gens")) for (JsonElement g : o.getAsJsonArray("gens")) is.gens.add(g.getAsLong());
            if (o.has("warps")) for (Map.Entry<String, JsonElement> w : o.getAsJsonObject("warps").entrySet()) {
                JsonArray a = w.getValue().getAsJsonArray();
                is.warps.put(w.getKey(), new double[]{a.get(0).getAsDouble(), a.get(1).getAsDouble(), a.get(2).getAsDouble(), a.get(3).getAsDouble()});
            }
            if (o.has("questBase")) for (Map.Entry<String, JsonElement> q : o.getAsJsonObject("questBase").entrySet()) is.questBase.put(UUID.fromString(q.getKey()), q.getValue().getAsLong());
            if (o.has("questBanked")) for (Map.Entry<String, JsonElement> q : o.getAsJsonObject("questBanked").entrySet()) is.questBanked.put(UUID.fromString(q.getKey()), q.getValue().getAsLong());
            if (o.has("memberSince")) for (Map.Entry<String, JsonElement> q : o.getAsJsonObject("memberSince").entrySet()) is.memberSince.put(UUID.fromString(q.getKey()), q.getValue().getAsLong());
            if (o.has("questCredit")) for (Map.Entry<String, JsonElement> q : o.getAsJsonObject("questCredit").entrySet()) is.questCredit.put(UUID.fromString(q.getKey()), q.getValue().getAsDouble());
            if (o.has("trusted")) for (Map.Entry<String, JsonElement> q : o.getAsJsonObject("trusted").entrySet()) {
                JsonObject t = q.getValue().getAsJsonObject();
                UUID u = UUID.fromString(q.getKey());
                is.trusted.put(u, t.get("until").getAsLong());
                is.trustedNames.put(u, t.has("name") ? t.get("name").getAsString() : "?");
            }
            if (o.has("wren")) {
                JsonArray a = o.getAsJsonArray("wren");
                is.wren = new double[]{a.get(0).getAsDouble(), a.get(1).getAsDouble(), a.get(2).getAsDouble(), a.get(3).getAsDouble()};
            }
            if (o.has("timeOffset")) is.timeOffset = o.get("timeOffset").getAsLong();
            // Islands from before join dates were saved: count from now.
            long nowMs = System.currentTimeMillis();
            is.memberSince.putIfAbsent(is.owner, nowMs);
            for (UUID m : is.members) is.memberSince.putIfAbsent(m, nowMs);
            ISLANDS_BY_ID.put(is.id, is);
        }
        // Generators that were growing back when the server stopped.
        if (root.has("regen")) for (JsonElement e : root.getAsJsonArray("regen")) REGEN.put(e.getAsLong(), 40L + RNG.nextInt(40));
        if (root.has("islandOf")) for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("islandOf").entrySet())
            ISLAND_OF.put(UUID.fromString(e.getKey()), e.getValue().getAsInt());
        for (Island is : ISLANDS_BY_ID.values()) OWNS.put(is.owner, is.id);
    }

    public static void save() {
        JsonObject root = new JsonObject();
        root.addProperty("next", nextId);
        JsonArray list = new JsonArray();
        for (Island is : ISLANDS_BY_ID.values()) {
            JsonObject o = new JsonObject();
            o.addProperty("id", is.id);
            o.addProperty("owner", is.owner.toString());
            o.addProperty("ownerName", is.ownerName);
            JsonObject m = new JsonObject();
            for (UUID u : is.members) m.addProperty(u.toString(), is.memberNames.getOrDefault(u, "?"));
            o.add("members", m);
            o.addProperty("size", is.size);
            o.addProperty("gen", is.genLevel);
            o.addProperty("crops", is.cropLevel);
            o.addProperty("points", is.points);
            o.addProperty("open", is.open);
            if (is.home != null) {
                JsonArray h = new JsonArray();
                for (double v : is.home) h.add(v);
                o.add("home", h);
            }
            JsonArray done = new JsonArray();
            is.completed.forEach(done::add);
            o.add("done", done);
            JsonObject rep = new JsonObject();
            is.repeatedOn.forEach(rep::addProperty);
            o.add("repeated", rep);
            o.addProperty("quest", is.quest);
            o.addProperty("questLine", IslandQuests.LINE_VERSION);
            o.addProperty("questCount", is.questCount);
            o.addProperty("version", is.version);
            o.addProperty("fund", is.fund);
            o.addProperty("genSpeed", is.genSpeed);
            o.addProperty("herd", is.herd);
            o.addProperty("minY", is.minY);
            JsonArray ga = new JsonArray();
            for (long g : is.gens) ga.add(g);
            o.add("gens", ga);
            JsonObject wj = new JsonObject();
            is.warps.forEach((k, v) -> {
                JsonArray a = new JsonArray();
                for (double x : v) a.add(x);
                wj.add(k, a);
            });
            o.add("warps", wj);
            JsonObject qb = new JsonObject(), qk = new JsonObject();
            is.questBase.forEach((k, v) -> qb.addProperty(k.toString(), v));
            is.questBanked.forEach((k, v) -> qk.addProperty(k.toString(), v));
            o.add("questBase", qb);
            o.add("questBanked", qk);
            JsonObject ms = new JsonObject(), qc = new JsonObject();
            is.memberSince.forEach((k, v) -> ms.addProperty(k.toString(), v));
            is.questCredit.forEach((k, v) -> qc.addProperty(k.toString(), v));
            o.add("memberSince", ms);
            o.add("questCredit", qc);
            JsonObject tr = new JsonObject();
            is.trusted.forEach((k, v) -> {
                JsonObject t = new JsonObject();
                t.addProperty("until", v);
                t.addProperty("name", is.trustedNames.getOrDefault(k, "?"));
                tr.add(k.toString(), t);
            });
            o.add("trusted", tr);
            if (is.wren != null) {
                JsonArray a = new JsonArray();
                for (double x : is.wren) a.add(x);
                o.add("wren", a);
            }
            o.addProperty("timeOffset", is.timeOffset);
            list.add(o);
        }
        root.add("islands", list);
        JsonArray regen = new JsonArray();
        for (long k : REGEN.keySet()) regen.add(k);
        root.add("regen", regen);
        JsonObject of = new JsonObject();
        ISLAND_OF.forEach((k, v) -> of.addProperty(k.toString(), v));
        root.add("islandOf", of);
        // Built here on the main thread, written in the background (no lag spike on big servers).
        PlayerCache.io(() -> Stacks.writeFile("islands.json", root));
    }

    // =====================================================================
    // Creating islands and moving players
    // =====================================================================

    /** Builds a brand-new starter island for this player. */
    private static Island create(ServerPlayer p) {
        ServerLevel level = level();
        int id = nextId++;
        while (id % GRID == 0 || ISLANDS_BY_ID.containsKey(id)) id = nextId++; // column 0 is skipped
        Island is = new Island(id);
        is.owner = p.getUUID();
        is.ownerName = p.getName().getString();
        int cx = is.cx(), cz = is.cz();
        MapCanvas canvas = IslandDesign.build();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        canvas.forEach((x, y, z, text) -> level.setBlock(pos.set(cx + x, TOP_Y + y, cz + z), GoldWarsMap.toState(text), Block.UPDATE_CLIENTS));
        fillChest(level, new BlockPos(cx + IslandDesign.SURFACE_CHEST[0], TOP_Y + IslandDesign.SURFACE_CHEST[1], cz + IslandDesign.SURFACE_CHEST[2]), true);
        fillChest(level, new BlockPos(cx + IslandDesign.CAVE_CHEST[0], TOP_Y + IslandDesign.CAVE_CHEST[1], cz + IslandDesign.CAVE_CHEST[2]), false);
        buildIslet(level, is);
        is.memberSince.put(p.getUUID(), System.currentTimeMillis());
        is.home = new double[]{cx + IslandDesign.SPAWN[0] + 0.5, TOP_Y + IslandDesign.SPAWN[1], cz + IslandDesign.SPAWN[2] + 0.5, 180};
        ISLANDS_BY_ID.put(is.id, is);
        ISLAND_OF.put(p.getUUID(), is.id);
        OWNS.put(p.getUUID(), is.id);
        save();
        Skyserver.LOGGER.info("Built Sky Island #{} for {} at {}, {}", is.id, is.ownerName, cx, cz);
        return is;
    }

    /** The little second island with its chest (only into empty space, so nobody's build gets overwritten). */
    private static int designMin = Integer.MAX_VALUE;

    /** The bottom of the starter island and the little islet. */
    static int designMinY() {
        if (designMin == Integer.MAX_VALUE) {
            int[] m = {Integer.MAX_VALUE};
            IslandDesign.build().forEach((x, y, z, t) -> m[0] = Math.min(m[0], y));
            IslandDesign.buildIslet().forEach((x, y, z, t) -> m[0] = Math.min(m[0], y));
            designMin = TOP_Y + (m[0] == Integer.MAX_VALUE ? -20 : m[0]);
        }
        return designMin;
    }

    /**
     * Falling off: once you're 5 blocks below the lowest block ever built on the island and there's
     * nothing under you to land on, you go straight back (no long fall to the bottom of the world).
     */
    private static boolean fellOff(ServerPlayer p, Island at, ServerLevel level) {
        if (p.getY() < VOID_Y) return true;
        if (at == null || p.onGround() || p.getDeltaMovement().y >= 0 || p.isFallFlying()) return false;
        if (p.getY() >= at.minY - 5) return false;
        BlockPos.MutableBlockPos c = new BlockPos.MutableBlockPos();
        int bx = p.getBlockX(), bz = p.getBlockZ();
        for (int y = p.getBlockY(); y > VOID_Y; y--) if (!level.getBlockState(c.set(bx, y, bz)).isAir()) return false; // something to land on
        return true;
    }

    private static void buildIslet(ServerLevel level, Island is) {
        int ox = is.cx() + IslandDesign.ISLET[0], oz = is.cz() + IslandDesign.ISLET[2];
        MapCanvas islet = IslandDesign.buildIslet();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        islet.forEach((x, y, z, text) -> {
            pos.set(ox + x, TOP_Y + y, oz + z);
            if (level.getBlockState(pos).isAir()) level.setBlock(pos, GoldWarsMap.toState(text), Block.UPDATE_CLIENTS);
        });
        BlockPos chest = new BlockPos(is.cx() + IslandDesign.ISLET_CHEST[0], TOP_Y + IslandDesign.ISLET_CHEST[1], is.cz() + IslandDesign.ISLET_CHEST[2]);
        if (level.getBlockState(chest).isAir()) fillIsletChest(level, chest);
        is.version = 2;
        WREN_CHECKS.put(is.id, ticks + 40);
    }

    /** The little island's chest: a bit better than the starter chests. */
    private static void fillIsletChest(ServerLevel level, BlockPos pos) {
        level.setBlock(pos, Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, net.minecraft.core.Direction.WEST), Block.UPDATE_CLIENTS);
        if (!(level.getBlockEntity(pos) instanceof Container c)) return;
        List<ItemStack> items = new ArrayList<>(List.of(
                new ItemStack(Items.IRON_INGOT, 8), new ItemStack(Items.GOLD_INGOT, 4), new ItemStack(Items.BUCKET),
                new ItemStack(Items.BONE_MEAL, 16), new ItemStack(Items.SAND, 16), new ItemStack(Items.MELON_SEEDS, 2),
                new ItemStack(Items.SUGAR_CANE, 4), new ItemStack(Items.APPLE, 4), new ItemStack(Items.DIAMOND, 1)));
        List<ItemStack> extras = new ArrayList<>(List.of(
                new ItemStack(Items.NAME_TAG), new ItemStack(Items.ENDER_PEARL, 2), new ItemStack(Items.GLOWSTONE_DUST, 8),
                new ItemStack(Items.COCOA_BEANS, 3), new ItemStack(Items.SLIME_BALL, 4), new ItemStack(Items.REDSTONE, 12),
                new ItemStack(Items.EMERALD, 2), new ItemStack(Items.CHERRY_SAPLING, 2), new ItemStack(Items.PUMPKIN_SEEDS, 4),
                new ItemStack(Items.EGG, 4), new ItemStack(Items.LAPIS_LAZULI, 8)));
        Collections.shuffle(extras, new Random());
        items.addAll(extras.subList(0, 3));
        for (int i = 0; i < items.size() && i < c.getContainerSize(); i++) c.setItem(i, items.get(i));
    }

    private static final Map<Integer, Long> WREN_CHECKS = new HashMap<>(); // island id -> tick to make sure Wren is there

    private static void fillChest(ServerLevel level, BlockPos pos, boolean surface) {
        level.setBlock(pos, Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, net.minecraft.core.Direction.SOUTH), Block.UPDATE_CLIENTS);
        if (!(level.getBlockEntity(pos) instanceof Container c)) return;
        List<ItemStack> items = new ArrayList<>();
        Random rng = new Random();
        if (surface) {
            items.add(new ItemStack(Items.OAK_LOG, 16));
            items.add(new ItemStack(Items.SPRUCE_LOG, 16));
            items.add(new ItemStack(Items.BIRCH_LOG, 16));
            items.add(new ItemStack(Items.JUNGLE_LOG, 16));
            items.add(new ItemStack(Items.COBBLESTONE, 32));
            items.add(new ItemStack(Items.DIRT, 32));
            items.add(new ItemStack(Items.COAL, 32));
            // A few random starter extras.
            List<ItemStack> extras = new ArrayList<>(List.of(
                    new ItemStack(Items.WHEAT_SEEDS, 6), new ItemStack(Items.OAK_SAPLING, 2), new ItemStack(Items.BIRCH_SAPLING, 2),
                    new ItemStack(Items.BONE_MEAL, 8), new ItemStack(Items.BREAD, 6), new ItemStack(Items.SUGAR_CANE, 3),
                    new ItemStack(Items.PUMPKIN_SEEDS, 2), new ItemStack(Items.MELON_SEEDS, 2), new ItemStack(Items.CARROT, 3),
                    new ItemStack(Items.POTATO, 3), new ItemStack(Items.CACTUS, 1), new ItemStack(Items.TORCH, 12),
                    new ItemStack(Items.SWEET_BERRIES, 4), new ItemStack(Items.SAND, 8), new ItemStack(Items.CLAY_BALL, 8)));
            Collections.shuffle(extras, rng);
            items.addAll(extras.subList(0, 5));
        } else {
            items.add(new ItemStack(Items.WATER_BUCKET));
            items.add(new ItemStack(Items.LAVA_BUCKET));
            items.add(new ItemStack(Items.IRON_INGOT, 4));
            items.add(new ItemStack(Items.TORCH, 16));
            items.add(new ItemStack(Items.STONE_PICKAXE));
            items.add(new ItemStack(Items.ICE, 2));
            items.add(new ItemStack(Items.FLINT, 3));
            items.add(new ItemStack(Items.STRING, 4));
        }
        for (int i = 0; i < items.size() && i < c.getContainerSize(); i++) c.setItem(i, items.get(i));
    }

    /** /is: go to your island (makes one the first time). Works from the lobby or the islands. */
    public static void goHome(ServerPlayer p) {
        if (!canTravel(p)) return;
        Island is = islandOf(p.getUUID());
        if (is == null) {
            Lobby.msg(p, "§aBuilding your Sky Island...");
            is = create(p);
            final Island fresh = is;
            arrive(p, fresh, true);
            Lobby.msg(p, "§a§lWelcome to your Sky Island! §7Two chests are waiting: one by the path, one in the cave (open the trapdoor). "
                    + "Break the cobblestone on the §elodestone§7 and it grows back. §eYou can't fall off§7 - the void sends you home. "
                    + "Type §e/is §7for the island menu, §e/is help §7for commands.");
            return;
        }
        arrive(p, is, false);
    }

    private static boolean canTravel(ServerPlayer p) {
        if (level() == null) {
            Lobby.msg(p, "§cSky Island isn't available (the islands dimension didn't load).");
            return false;
        }
        if (!Lobby.isLobby(p.level()) && !isIsland(p.level())) {
            Lobby.msg(p, "§cGo to the lobby first (§e/lobby§c), then use §e/is§c.");
            return false;
        }
        return true;
    }

    /** Moves a player onto an island (swapping in their island inventory if they came from the lobby). */
    private static void arrive(ServerPlayer p, Island is, boolean first) {
        boolean fromLobby = !isIsland(p.level());
        if (fromLobby) {
            Lobby.leaveLobbyStuff(p);
            IslandStore.restore(p);
        }
        teleportHome(p, is);
        if (!Lobby.isStaff(p)) p.setGameMode(GameType.SURVIVAL); // ops keep creative/spectator
        if (is.version < 2) {
            // Islands made before the little island and Captain Wren existed get them now.
            final Island fix = is;
            Lobby.runLater(() -> {
                buildIslet(level(), fix);
                save();
                Lobby.msg(p, "§eNew: §7a little island appeared to the east, and §6Captain Wren §7is waiting by the path with quests for you!");
            });
        } else {
            WREN_CHECKS.put(is.id, ticks + 40);
        }
        if (fromLobby) {
            int n = Mailbox.deliver(p);
            if (n > 0) Lobby.msg(p, "§aDelivered " + n + " prize" + (n == 1 ? "" : "s") + " to your inventory.");
            int left = Mailbox.waiting(p.getUUID());
            if (left > 0) Lobby.msg(p, "§e" + left + " more prize" + (left == 1 ? "" : "s") + " waiting: make room and type /claim.");
        }
        if (!first) Lobby.msg(p, "§aWelcome to " + (is.isMember(p.getUUID()) ? "your island" : is.name()) + "! §7(§e/lobby §7to leave)");
    }

    public static void teleportHome(ServerPlayer p, Island is) {
        double[] h = is.home;
        ServerLevel level = level();
        // Make sure there's something to stand on (someone may have dug out the home spot).
        BlockPos below = BlockPos.containing(h[0], h[1] - 1, h[2]);
        if (level.getBlockState(below).isAir()) level.setBlock(below, Blocks.COBBLESTONE.defaultBlockState(), Block.UPDATE_ALL);
        p.teleportTo(level, h[0], h[1], h[2], Set.of(), (float) h[3], 0f, true);
        p.setDeltaMovement(0, 0, 0);
        p.resetFallDistance();
        sendBorder(p, is);
    }

    public static void sendBorder(ServerPlayer p, Island is) {
        WorldBorder b = new WorldBorder();
        b.setCenter(is.cx() + 0.5, is.cz() + 0.5);
        b.setSize(is.size);
        b.setWarningBlocks(2);
        p.connection.send(new ClientboundInitializeBorderPacket(b));
        BORDER_SENT.put(p.getUUID(), is.id * 10_000 + is.size);
    }

    // =====================================================================
    // Visiting
    // =====================================================================

    public static void visit(ServerPlayer p, String targetName) {
        if (!canTravel(p)) return;
        Island is = findByName(targetName);
        if (is == null) {
            Lobby.msg(p, "§c" + targetName + " doesn't have an island.");
            return;
        }
        if (is.isMember(p.getUUID())) {
            goHome(p);
            return;
        }
        boolean partyFriend = false;
        for (UUID m : is.members) if (Parties.sameParty(p.getUUID(), m)) partyFriend = true;
        if (Parties.sameParty(p.getUUID(), is.owner)) partyFriend = true;
        if (is.open || partyFriend || is.isTrusted(p.getUUID())) {
            arrive(p, is, false);
            return;
        }
        List<ServerPlayer> deciders = onlineMembers(is);
        if (deciders.isEmpty()) {
            Lobby.msg(p, "§cNobody from " + is.name() + " is online to let you in. §7(Owners can use §e/is open§7 to allow visitors anytime.)");
            return;
        }
        VISIT_REQUESTS.put(p.getUUID(), new long[]{is.id, ticks});
        Lobby.msg(p, "§eAsked " + is.name() + " if you can visit. §7(Expires in 60 seconds.)");
        String name = p.getName().getString();
        for (ServerPlayer d : deciders) {
            d.sendSystemMessage(Component.literal("§e" + name + " §7wants to visit your island. ")
                    .append(button("§a[Accept]", "/is accept " + name, "Let them visit"))
                    .append(Component.literal(" "))
                    .append(button("§c[Deny]", "/is deny " + name, "Say no")));
        }
    }

    private static MutableComponent button(String text, String command, String hover) {
        return Component.literal(text).withStyle(Style.EMPTY.withClickEvent(new ClickEvent.RunCommand(command))
                .withHoverEvent(new HoverEvent.ShowText(Component.literal(hover))));
    }

    private static void answerVisit(ServerPlayer decider, String visitorName, boolean yes) {
        Island is = islandOf(decider.getUUID());
        ServerPlayer visitor = visitorName == null ? null : server.getPlayerList().getPlayerByName(visitorName);
        if (visitorName == null) {
            // Most recent request for this island.
            long best = -1;
            for (Map.Entry<UUID, long[]> e : VISIT_REQUESTS.entrySet()) {
                if (is != null && e.getValue()[0] == is.id && e.getValue()[1] > best) {
                    best = e.getValue()[1];
                    visitor = server.getPlayerList().getPlayer(e.getKey());
                }
            }
        }
        if (is == null || visitor == null) {
            Lobby.msg(decider, "§7No visit request to answer.");
            return;
        }
        long[] req = VISIT_REQUESTS.get(visitor.getUUID());
        if (req == null || req[0] != is.id) {
            Lobby.msg(decider, "§7" + visitor.getName().getString() + " hasn't asked to visit (or the request expired).");
            return;
        }
        VISIT_REQUESTS.remove(visitor.getUUID());
        if (!yes) {
            Lobby.msg(visitor, "§c" + decider.getName().getString() + " said no to your visit.");
            Lobby.msg(decider, "§7Denied.");
            return;
        }
        if (!Lobby.isLobby(visitor.level()) && !isIsland(visitor.level())) {
            Lobby.msg(decider, "§7They're busy (in a game) right now.");
            return;
        }
        Lobby.msg(decider, "§a" + visitor.getName().getString() + " is on the way.");
        final ServerPlayer v = visitor;
        Lobby.runLater(() -> arrive(v, is, false));
    }

    private static Island findByName(String name) {
        ServerPlayer online = server.getPlayerList().getPlayerByName(name);
        if (online != null) return islandOf(online.getUUID());
        for (Island is : all()) {
            if (is.ownerName.equalsIgnoreCase(name)) return is;
            for (String m : is.memberNames.values()) if (m.equalsIgnoreCase(name)) return is;
        }
        return null;
    }

    private static List<ServerPlayer> onlineMembers(Island is) {
        List<ServerPlayer> list = new ArrayList<>();
        ServerPlayer o = server.getPlayerList().getPlayer(is.owner);
        if (o != null) list.add(o);
        for (UUID m : is.members) {
            ServerPlayer p = server.getPlayerList().getPlayer(m);
            if (p != null) list.add(p);
        }
        return list;
    }

    // =====================================================================
    // Co-op
    // =====================================================================

    /**
     * 5.9: who may invite new co-op members to an island: the owner, or a co-op member (co-owner)
     * of THAT island who is also a moderator. Anyone else - including mods who aren't on the
     * island - can't.
     */
    public static boolean canInvite(Island is, UUID who) {
        if (is == null) return false;
        if (is.owner.equals(who)) return true;
        return is.members.contains(who) && Mods.isMod(who);
    }

    private static void invite(ServerPlayer inviter, ServerPlayer target) {
        // Always the inviter's OWN island (the one they belong to) - never somebody else's.
        Island is = islandOf(inviter.getUUID());
        if (is == null) {
            Lobby.msg(inviter, "§cYou don't have an island yet. Type §e/is§c.");
            return;
        }
        if (!canInvite(is, inviter.getUUID())) {
            Lobby.msg(inviter, "§cOnly the island owner can invite co-op members. §7(Co-op members who are moderators can too.)");
            return;
        }
        if (target == inviter || target instanceof BotPlayer) return;
        if (is.members.size() >= MAX_MEMBERS) {
            Lobby.msg(inviter, "§cThis island already has " + MAX_MEMBERS + " co-op members.");
            return;
        }
        if (is.isMember(target.getUUID())) {
            Lobby.msg(inviter, "§7They're already on this island.");
            return;
        }
        boolean owner = is.owner.equals(inviter.getUUID());
        INVITES.put(target.getUUID(), new long[]{is.id, ticks});
        Lobby.msg(inviter, "§aInvited " + target.getName().getString() + " to co-op on " + (owner ? "your island" : is.name()) + ".");
        String from = owner ? "§e" + inviter.getName().getString() + " §7invited you to co-op on their island"
                : "§e" + inviter.getName().getString() + " §7invited you to co-op on §e" + is.name();
        target.sendSystemMessage(Component.literal(from + " (you'd share it and build together). ")
                .append(button("§a[Join]", "/is join " + is.ownerName, "Join " + is.name())));
        if (!owner) islandChat(is, "§7" + inviter.getName().getString() + " invited " + target.getName().getString() + " to join the island.");
    }

    private static void join(ServerPlayer p, String ownerName) {
        long[] inv = INVITES.get(p.getUUID());
        Island is = inv == null ? null : ISLANDS_BY_ID.get((int) inv[0]);
        if (is == null || (ownerName != null && !is.ownerName.equalsIgnoreCase(ownerName))) {
            Lobby.msg(p, "§cYou don't have an invite from them (invites last 5 minutes).");
            return;
        }
        if (is.members.size() >= MAX_MEMBERS) {
            Lobby.msg(p, "§cThat island is full.");
            return;
        }
        INVITES.remove(p.getUUID());
        Island old = islandOf(p.getUUID());
        if (old != null && !old.owner.equals(p.getUUID())) removeMember(old, p.getUUID());
        is.members.add(p.getUUID());
        is.memberSince.put(p.getUUID(), System.currentTimeMillis());
        is.memberNames.put(p.getUUID(), p.getName().getString());
        is.trusted.remove(p.getUUID()); // a member doesn't need trust
        is.trustedNames.remove(p.getUUID());
        ISLAND_OF.put(p.getUUID(), is.id);
        save();
        Lobby.msg(p, "§aYou joined " + is.name() + "!" + (OWNS.containsKey(p.getUUID()) ? " §7(Your own island is kept: §e/is leave §7to go back to it.)" : ""));
        for (ServerPlayer m : onlineMembers(is)) if (m != p) Lobby.msg(m, "§a" + p.getName().getString() + " joined the island as a co-op member.");
        if (isIsland(p.level())) teleportHome(p, is);
    }

    /**
     * /ss resetisland: the player (and their members) lose this island; their next /is builds a fresh
     * one somewhere new. The old blocks stay where they were but nobody can get there any more.
     * Backups keep the old one if it's ever needed back.
     */
    public static String adminReset(UUID player) {
        Integer id = OWNS.get(player);
        if (id == null) id = ISLAND_OF.get(player);
        Island is = id == null ? null : ISLANDS_BY_ID.get(id);
        if (is == null) return "§7They don't have an island.";
        if (!is.owner.equals(player)) return "§eThey're a member of " + is.name() + ", not the owner. Reset the owner (" + is.ownerName + ") instead.";
        ISLANDS_BY_ID.remove(is.id);
        OWNS.remove(is.owner);
        int n = 0;
        for (UUID u : new ArrayList<>(ISLAND_OF.keySet())) {
            if (ISLAND_OF.get(u) != is.id) continue;
            Integer own = OWNS.get(u);
            if (own != null && ISLANDS_BY_ID.containsKey(own)) ISLAND_OF.put(u, own);
            else ISLAND_OF.remove(u);
            n++;
        }
        if (server != null) for (ServerPlayer p : new ArrayList<>(server.getPlayerList().getPlayers())) {
            if (isIsland(p.level()) && is.inBorder(p.getX(), p.getZ())) {
                Lobby.goToLobby(p);
                Lobby.msg(p, "§eThat island was reset by an admin.");
            }
        }
        save();
        return "§aReset " + is.name() + " (" + n + " player" + (n == 1 ? "" : "s") + " moved off it). They get a fresh island with /is.";
    }

    private static void removeMember(Island is, UUID u) {
        is.memberSince.remove(u);
        is.questCredit.remove(u);
        is.members.remove(u);
        is.memberNames.remove(u);
        Integer own = OWNS.get(u);
        if (own != null && own != is.id) ISLAND_OF.put(u, own);
        else ISLAND_OF.remove(u);
        save();
    }

    private static void leave(ServerPlayer p) {
        Island is = islandOf(p.getUUID());
        if (is == null) return;
        if (is.owner.equals(p.getUUID())) {
            Lobby.msg(p, "§cYou own this island. §7(Owners can't leave it; you can remove co-op members with §e/is kick§7.)");
            return;
        }
        removeMember(is, p.getUUID());
        Lobby.msg(p, "§eYou left " + is.name() + ".");
        if (isIsland(p.level())) {
            Island now = islandOf(p.getUUID());
            if (now != null) teleportHome(p, now);
            else Lobby.goToLobby(p);
        }
    }

    private static void kick(ServerPlayer owner, String name) {
        Island is = islandOf(owner.getUUID());
        if (is == null || !is.owner.equals(owner.getUUID())) {
            Lobby.msg(owner, "§cOnly the island owner can do that.");
            return;
        }
        for (UUID m : new ArrayList<>(is.members)) {
            if (is.memberNames.getOrDefault(m, "").equalsIgnoreCase(name)) {
                removeMember(is, m);
                Lobby.msg(owner, "§eRemoved " + name + " from the island.");
                ServerPlayer mp = server.getPlayerList().getPlayer(m);
                if (mp != null) {
                    Lobby.msg(mp, "§cYou were removed from " + is.name() + ".");
                    if (isIsland(mp.level()) && is.inBorder(mp.getX(), mp.getZ())) Lobby.goToLobby(mp);
                }
                return;
            }
        }
        // A visitor: send them away (and end any trust).
        ServerPlayer v = server.getPlayerList().getPlayerByName(name);
        if (v != null && isIsland(v.level()) && islandAt(v.getX(), v.getZ()) == is && !is.isMember(v.getUUID())) {
            is.trusted.remove(v.getUUID());
            is.trustedNames.remove(v.getUUID());
            save();
            Lobby.msg(v, "§cThe owner sent you back to the lobby.");
            Lobby.goToLobby(v);
            Lobby.msg(owner, "§eSent " + name + " away.");
            return;
        }
        Lobby.msg(owner, "§c" + name + " isn't a member or a visitor on your island.");
    }

    // =====================================================================
    // Trust (build permission without joining)
    // =====================================================================

    private static void trust(ServerPlayer p, ServerPlayer target, int minutes) {
        Island is = islandOf(p.getUUID());
        if (is == null) {
            Lobby.msg(p, "§cYou don't have an island.");
            return;
        }
        if (target == p || target instanceof BotPlayer) {
            Lobby.msg(p, "§cPick another player.");
            return;
        }
        if (is.isMember(target.getUUID())) {
            Lobby.msg(p, "§7" + target.getName().getString() + " is already a member of your island.");
            return;
        }
        minutes = Math.max(1, Math.min(TRUST_MAX_MIN, minutes));
        is.trusted.put(target.getUUID(), System.currentTimeMillis() + minutes * 60_000L);
        is.trustedNames.put(target.getUUID(), target.getName().getString());
        save();
        String time = minutes % 60 == 0 ? (minutes / 60) + " hour" + (minutes == 60 ? "" : "s") : minutes + " minute" + (minutes == 1 ? "" : "s");
        islandChat(is, "§a" + p.getName().getString() + " trusted " + target.getName().getString() + " to build on the island for " + time + ". §7(/is untrust "
                + target.getName().getString() + " to stop)");
        Lobby.msg(target, "§a" + p.getName().getString() + " lets you build on " + is.name() + " for " + time
                + "§a. §7You can build, break and use doors - but not chests, furnaces or lava. §e/is visit " + is.ownerName + " §7to go there.");
    }

    private static void untrust(ServerPlayer p, String name) {
        Island is = islandOf(p.getUUID());
        if (is == null) {
            Lobby.msg(p, "§cYou don't have an island.");
            return;
        }
        UUID who = null;
        for (Map.Entry<UUID, String> e : is.trustedNames.entrySet()) if (e.getValue().equalsIgnoreCase(name)) who = e.getKey();
        if (who == null) {
            Lobby.msg(p, "§c" + name + " isn't trusted on your island. §7(/is trusted)");
            return;
        }
        is.trusted.remove(who);
        String real = is.trustedNames.remove(who);
        save();
        islandChat(is, "§e" + p.getName().getString() + " removed " + real + "'s build access.");
        ServerPlayer t = server.getPlayerList().getPlayer(who);
        if (t != null) Lobby.msg(t, "§eYour build access on " + is.name() + " was removed.");
    }

    private static void listTrusted(ServerPlayer p) {
        Island is = islandOf(p.getUUID());
        if (is == null) {
            Lobby.msg(p, "§cYou don't have an island.");
            return;
        }
        expireTrust(is);
        if (is.trusted.isEmpty()) {
            Lobby.msg(p, "§7Nobody is trusted on your island. §e/is trust <player> [minutes]§7 lets someone build for a while.");
            return;
        }
        Lobby.msg(p, "§aTrusted on your island:");
        long now = System.currentTimeMillis();
        for (Map.Entry<UUID, Long> e : is.trusted.entrySet()) {
            long mins = Math.max(1, (e.getValue() - now + 59_999) / 60_000);
            Lobby.msg(p, " §f" + is.trustedNames.getOrDefault(e.getKey(), "?") + " §7- " + (mins >= 60 ? (mins / 60) + "h " + (mins % 60) + "m" : mins + "m") + " left");
        }
    }

    /** Removes finished trusts and tells the player their access ended. */
    private static void expireTrust(Island is) {
        if (is.trusted.isEmpty()) return;
        long now = System.currentTimeMillis();
        boolean changed = false;
        for (Iterator<Map.Entry<UUID, Long>> it = is.trusted.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, Long> e = it.next();
            if (e.getValue() > now) continue;
            it.remove();
            String name = is.trustedNames.remove(e.getKey());
            changed = true;
            ServerPlayer t = server == null ? null : server.getPlayerList().getPlayer(e.getKey());
            if (t != null) Lobby.msg(t, "§7Your build access on " + is.name() + " has ended.");
            islandChat(is, "§7" + name + "'s build access ended.");
        }
        if (changed) dirty = true;
    }

    /** Things trusted (non-member) players may not open or break. */
    private static boolean protectedFromTrusted(BlockState state, BlockEntity be) {
        Block b = state.getBlock();
        return be instanceof Container || b instanceof EnderChestBlock || b instanceof ShulkerBoxBlock || b instanceof AbstractFurnaceBlock
                || b instanceof ChestBlock || b instanceof BarrelBlock || b instanceof HopperBlock || b instanceof DispenserBlock
                || b instanceof BrewingStandBlock || b == Blocks.LODESTONE;
    }

    private static boolean dangerousForTrusted(ItemStack held) {
        return held.is(Items.LAVA_BUCKET) || held.is(Items.FLINT_AND_STEEL) || held.is(Items.FIRE_CHARGE) || held.is(Items.TNT);
    }

    // =====================================================================
    // Upgrades
    // =====================================================================

    public static String buySize(ServerPlayer p, Island is) {
        if (is.size >= MAX_SIZE) return "§7Your island is already the biggest size (" + MAX_SIZE + "x" + MAX_SIZE + ").";
        int step = (is.size - START_SIZE) / SIZE_STEP;
        long price = SIZE_PRICES[step];
        if (!payUpgrade(p, is, price)) return "§cThat costs " + Economy.format(price) + fundNote(is) + ".";
        is.size += SIZE_STEP;
        save();
        for (ServerPlayer o : level().players()) if (islandAt(o.getX(), o.getZ()) == is) sendBorder(o, is);
        return "§aYour island can now be built out to §f" + is.size + "x" + is.size + "§a!";
    }

    public static String buyGenerator(ServerPlayer p, Island is) {
        if (is.genLevel >= GEN_PRICES.length) return "§7Your generator is already maxed out.";
        long price = GEN_PRICES[is.genLevel];
        if (!payUpgrade(p, is, price)) return "§cThat costs " + Economy.format(price) + fundNote(is) + ".";
        is.genLevel++;
        save();
        return "§aGenerator upgraded to level " + is.genLevel + "! §7Better ores now appear.";
    }

    public static String buyCrops(ServerPlayer p, Island is) {
        if (is.cropLevel >= CROP_PRICES.length - 1) return "§7Crop growth is already maxed out.";
        long price = CROP_PRICES[is.cropLevel + 1];
        if (!payUpgrade(p, is, price)) return "§cThat costs " + Economy.format(price) + fundNote(is) + ".";
        is.cropLevel++;
        save();
        return "§aCrop growth upgraded to level " + is.cropLevel + "!";
    }

    // ---- Shared upgrade fund ----

    private static String fundNote(Island is) {
        return is.fund > 0 ? " §7(island fund has " + Economy.format(is.fund) + ", you pay the rest)" : "";
    }

    /**
     * Pays for an upgrade from the island fund first, then the buyer's own money for whatever's left.
     * 5.8: an Island Upgrade Voucher (Epic chest prize) takes 25% off the next upgrade.
     */
    private static boolean payUpgrade(ServerPlayer p, Island is, long price) {
        boolean voucher = price > 0 && Tokens.count(p.getUUID(), Tokens.UPGRADE_VOUCHER) > 0;
        if (voucher) price = Math.round(price * 0.75);
        long fromFund = Math.min(is.fund, price);
        long rest = price - fromFund;
        if (rest > 0 && !Economy.take(server, p.getUUID(), rest)) return false;
        if (fromFund > 0) {
            is.fund -= fromFund;
            islandChat(is, "§7" + p.getName().getString() + " spent " + Economy.format(fromFund) + " from the island fund §8(" + Economy.format(is.fund) + " left)");
        }
        if (voucher) {
            Tokens.add(p.getUUID(), Tokens.UPGRADE_VOUCHER, -1);
            Lobby.msg(p, "§dIsland Upgrade Voucher used: §a25% off! §7(paid " + Economy.format(price) + ")");
        }
        return true;
    }

    public static void contribute(ServerPlayer p, long amount) {
        Island is = islandOf(p.getUUID());
        if (is == null) {
            Lobby.msg(p, "§cYou don't have an island.");
            return;
        }
        if (amount <= 0) {
            Lobby.msg(p, "§cAmount must be more than 0.");
            return;
        }
        if (!Economy.take(server, p.getUUID(), amount)) {
            Lobby.msg(p, "§cYou don't have " + Economy.format(amount) + ".");
            return;
        }
        is.fund += amount;
        PlayerStats.add(p, "island_fund", amount);
        save();
        islandChat(is, "§a" + p.getName().getString() + " put " + Economy.format(amount) + " into the island fund! §7Total: §f" + Economy.format(is.fund)
                + " §8(upgrades in /is menu use it first)");
    }

    // ---- Island chat and warps ----

    public static final int MAX_WARPS = 5;

    /** A message to every online member of an island. */
    public static void islandChat(Island is, String text) {
        if (server == null) return;
        ServerPlayer o = server.getPlayerList().getPlayer(is.owner);
        if (o != null) Lobby.msg(o, text);
        for (UUID u : is.members) {
            ServerPlayer m = server.getPlayerList().getPlayer(u);
            if (m != null) Lobby.msg(m, text);
        }
    }

    static void chat(ServerPlayer p, String text) {
        if (!ChatFilter.allow(p, text, "chat")) return;
        Island is = islandOf(p.getUUID());
        if (is == null) {
            Lobby.msg(p, "§cYou don't have an island.");
            return;
        }
        islandChat(is, "§a[ISLAND] §f" + p.getName().getString() + "§7: §f" + text.replace("§", ""));
    }

    static void setWarp(ServerPlayer p, String name) {
        if (!ChatFilter.allow(p, name, "warp name")) return;
        Island is = islandOf(p.getUUID());
        if (is == null || !isIsland(p.level()) || islandAt(p.getX(), p.getZ()) != is || !is.inBorder(p.getX(), p.getZ())) {
            Lobby.msg(p, "§cStand on your own island to set a warp.");
            return;
        }
        name = name.toLowerCase(Locale.ROOT);
        if (!is.warps.containsKey(name) && is.warps.size() >= MAX_WARPS) {
            Lobby.msg(p, "§cYour island has " + MAX_WARPS + " warps already. Remove one with §e/is delwarp <name>§c.");
            return;
        }
        is.warps.put(name, new double[]{p.getX(), p.getY(), p.getZ(), p.getYRot()});
        save();
        islandChat(is, "§a" + p.getName().getString() + " set island warp §f" + name + "§a. §7/is warp " + name);
    }

    static void delWarp(ServerPlayer p, String name) {
        Island is = islandOf(p.getUUID());
        if (is == null || is.warps.remove(name.toLowerCase(Locale.ROOT)) == null) {
            Lobby.msg(p, "§cNo warp called " + name + ".");
            return;
        }
        save();
        Lobby.msg(p, "§7Removed warp " + name + ".");
    }

    static void warps(ServerPlayer p) {
        Island is = islandOf(p.getUUID());
        if (is == null) {
            Lobby.msg(p, "§cYou don't have an island.");
            return;
        }
        if (is.warps.isEmpty()) {
            Lobby.msg(p, "§7No warps yet. Stand somewhere on your island and type §e/is setwarp <name> §7(up to " + MAX_WARPS + ").");
            return;
        }
        net.minecraft.network.chat.MutableComponent line = net.minecraft.network.chat.Component.literal("§aIsland warps: ");
        for (String w : is.warps.keySet())
            line.append(net.minecraft.network.chat.Component.literal("§b[" + w + "] ").withStyle(net.minecraft.network.chat.Style.EMPTY
                    .withClickEvent(new net.minecraft.network.chat.ClickEvent.RunCommand("/is warp " + w))));
        p.sendSystemMessage(line);
    }

    static void warp(ServerPlayer p, String name) {
        Island is = islandOf(p.getUUID());
        double[] w = is == null ? null : is.warps.get(name.toLowerCase(Locale.ROOT));
        if (w == null) {
            Lobby.msg(p, "§cNo warp called " + name + ". §7/is warps");
            return;
        }
        if (!isIsland(p.level())) {
            goHome(p);
            if (!isIsland(p.level())) return; // couldn't go (in a game, etc.)
        }
        ServerLevel level = level();
        BlockPos below = BlockPos.containing(w[0], w[1] - 1, w[2]);
        if (level.getBlockState(below).isAir()) level.setBlock(below, Blocks.COBBLESTONE.defaultBlockState(), Block.UPDATE_ALL);
        p.teleportTo(level, w[0], w[1], w[2], Set.of(), (float) w[3], 0f, true);
        p.setDeltaMovement(0, 0, 0);
        p.resetFallDistance();
        sendBorder(p, is);
        Lobby.msg(p, "§aWarped to §f" + name.toLowerCase(Locale.ROOT) + "§a.");
    }

    public static String buyExtraGenerator(ServerPlayer p) {
        Island own = islandOf(p.getUUID());
        if (own != null && own.gens.size() >= MAX_GENERATORS)
            return "§cYour island already has " + MAX_GENERATORS + " working generators (the most allowed).";
        if (!Economy.take(server, p.getUUID(), EXTRA_GENERATOR_PRICE)) return "§cThat costs " + Economy.format(EXTRA_GENERATOR_PRICE) + ".";
        ItemStack gen = Stacks.named(new ItemStack(Items.LODESTONE), "§bIsland Generator", "§7Place it and cobblestone (or ore) appears", "§7on top. Mine it and it grows back.");
        if (!p.getInventory().add(gen)) p.spawnAtLocation((ServerLevel) p.level(), gen);
        return "§aBought an Island Generator! §7Place it anywhere on your island.";
    }

    /** What the generator makes, by generator level: {cobble, coal, iron, copper, gold, redstone, lapis, diamond, emerald} weights. */
    private static final int[][] GEN_TABLE = {
            {1000, 0, 0, 0, 0, 0, 0, 0, 0},
            {950, 35, 15, 0, 0, 0, 0, 0, 0},
            {925, 40, 25, 7, 3, 0, 0, 0, 0},
            {900, 42, 30, 12, 8, 4, 3, 1, 0},
            {880, 45, 35, 15, 12, 6, 4, 2, 1}};
    private static final Block[] GEN_BLOCKS = {Blocks.COBBLESTONE, Blocks.COAL_ORE, Blocks.IRON_ORE, Blocks.COPPER_ORE, Blocks.GOLD_ORE,
            Blocks.REDSTONE_ORE, Blocks.LAPIS_ORE, Blocks.DIAMOND_ORE, Blocks.EMERALD_ORE};
    private static final Random RNG = new Random();

    public static String genOdds(int level) {
        int[] w = GEN_TABLE[Math.max(0, Math.min(GEN_TABLE.length - 1, level - 1))];
        String[] names = {"Cobble", "Coal", "Iron", "Copper", "Gold", "Redstone", "Lapis", "Diamond", "Emerald"};
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < w.length; i++) if (w[i] > 0) {
            double pct = w[i] / 10.0;
            sb.append(sb.length() == 0 ? "" : ", ").append(names[i]).append(" ").append(pct == Math.floor(pct) ? String.valueOf((int) pct) : String.valueOf(pct)).append("%");
        }
        return sb.toString();
    }

    private static Block rollGenerator(int level) {
        int[] w = GEN_TABLE[Math.max(0, Math.min(GEN_TABLE.length - 1, level - 1))];
        int total = 0;
        for (int v : w) total += v;
        int r = RNG.nextInt(total);
        for (int i = 0; i < w.length; i++) {
            r -= w[i];
            if (r < 0) return GEN_BLOCKS[i];
        }
        return Blocks.COBBLESTONE;
    }

    // =====================================================================
    // Island worth (block values)
    // =====================================================================

    /** How many island points a block is worth. */
    public static int value(BlockState s) {
        Block b = s.getBlock();
        if (s.isAir() || b instanceof LiquidBlock) return 0;
        if (b == Blocks.NETHERITE_BLOCK) return 1000;
        if (b == Blocks.BEACON) return 500;
        if (b == Blocks.SPAWNER) return 200;
        if (b == Blocks.DIAMOND_BLOCK) return 150;
        if (b == Blocks.EMERALD_BLOCK) return 120;
        if (b == Blocks.ANCIENT_DEBRIS) return 100;
        if (b == Blocks.ENCHANTING_TABLE) return 50;
        if (b == Blocks.GOLD_BLOCK) return 40;
        if (b == Blocks.IRON_BLOCK) return 30;
        if (b == Blocks.ANVIL || b == Blocks.CHIPPED_ANVIL || b == Blocks.DAMAGED_ANVIL) return 20;
        if (b == Blocks.LAPIS_BLOCK) return 15;
        if (b == Blocks.REDSTONE_BLOCK || b == Blocks.LODESTONE) return 10;
        if (b == Blocks.COAL_BLOCK || Blocks.COPPER_BLOCK.asList().contains(b) || b == Blocks.CRYING_OBSIDIAN) return 8;
        if (b == Blocks.OBSIDIAN || b == Blocks.SEA_LANTERN || b == Blocks.GLOWSTONE || b == Blocks.BOOKSHELF) return 5;
        if (b == Blocks.QUARTZ_BLOCK || b == Blocks.PRISMARINE || b == Blocks.PURPUR_BLOCK) return 3;
        String id = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(b).getPath();
        if (id.contains("concrete") || id.contains("terracotta") || id.contains("glass") || id.contains("bricks") || id.endsWith("_wool")) return 2;
        if (id.contains("ore")) return 3;
        if (s.isCollisionShapeFullBlock(level(), BlockPos.ZERO)) return 1;
        return 1;
    }

    private static void addPoints(BlockPos pos, int delta) {
        Island is = islandAt(pos.getX(), pos.getZ());
        if (is == null || delta == 0) return;
        is.points = Math.max(0, is.points + delta);
        dirty = true;
    }

    private static boolean dirty = false;

    // =====================================================================
    // Protection
    // =====================================================================

    /** May this player change blocks here? Members (or trusted players) inside the border, or ops in creative. */
    public static boolean canBuild(Player p, BlockPos pos) {
        if (p.isCreative() && p instanceof ServerPlayer sp && server.getPlayerList().isOp(sp.nameAndId())) return true;
        Island is = islandAt(pos.getX(), pos.getZ());
        return is != null && is.canModify(p.getUUID()) && is.inBorder(pos.getX() + 0.5, pos.getZ() + 0.5);
    }

    private static boolean isVisitorSafe(BlockState s) {
        Block b = s.getBlock();
        return b instanceof DoorBlock || b instanceof TrapDoorBlock || b instanceof FenceGateBlock || b instanceof ButtonBlock
                || b == Blocks.CRAFTING_TABLE || b instanceof LecternBlock || b instanceof BellBlock;
    }

    private static boolean isAdmin(Player p) {
        return p.isCreative() && p instanceof ServerPlayer sp && server.getPlayerList().isOp(sp.nameAndId());
    }

    private static void protection() {
        PlayerBlockBreakEvents.BEFORE.register((level, player, pos, state, be) -> {
            if (!isIsland(level)) return true;
            if (com.aidan.skyserver.expedition.ExpeditionShip.isShipBlock(pos)) return false; // Captain Wren's airship
            if (!canBuild(player, pos)) {
                if (player instanceof ServerPlayer sp) deny(sp, pos);
                return false;
            }
            Island is = islandAt(pos.getX(), pos.getZ());
            if (is != null && !is.isMember(player.getUUID()) && !isAdmin(player) && protectedFromTrusted(state, be)) {
                if (player instanceof ServerPlayer sp) denyTrusted(sp);
                return false;
            }
            return true;
        });
        PlayerBlockBreakEvents.AFTER.register((level, player, pos, state, be) -> {
            if (!isIsland(level)) return;
            addPoints(pos, -value(state));
            // Broke the block on top of a generator: it grows back.
            if (level.getBlockState(pos.below()).is(Blocks.LODESTONE)) {
                REGEN.put(pos.asLong(), ticks + regrowTicks(islandAt(pos.getX(), pos.getZ())));
                dirty = true;
            }
            if (state.is(Blocks.LODESTONE)) {
                Island gi = islandAt(pos.getX(), pos.getZ());
                if (gi != null && gi.gens.remove(pos.asLong())) dirty = true;
            }
            if (state.is(Blocks.SPAWNER)) {
                Island si = islandAt(pos.getX(), pos.getZ());
                if (si != null) si.spawners.remove(pos.asLong());
            }
            if (player instanceof ServerPlayer sp) Quests.progress(sp, "island_break", 1);
            if (player instanceof ServerPlayer sp) skillForBreak(sp, state);
        });
        UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
            if (!isIsland(level) || level.isClientSide() || !(player instanceof ServerPlayer sp)) return InteractionResult.PASS;
            BlockPos clicked = hit.getBlockPos();
            BlockState state = level.getBlockState(clicked);
            Island is = islandAt(clicked.getX(), clicked.getZ());
            boolean member = is != null && is.isMember(sp.getUUID());
            boolean trusted = !member && is != null && is.isTrusted(sp.getUUID());
            boolean admin = isAdmin(sp);
            if (com.aidan.skyserver.expedition.ExpeditionShip.isShipBlock(clicked)) {
                // Captain Wren's airship: the rope boards it, nothing on it can be used or built on.
                if (hand == net.minecraft.world.InteractionHand.MAIN_HAND && com.aidan.skyserver.expedition.ExpeditionShip.isRope(clicked))
                    com.aidan.skyserver.expedition.Expedition.board(sp);
                return InteractionResult.FAIL;
            }
            ItemStack held = sp.getItemInHand(hand);
            if (!member && !trusted && !admin) {
                // Visitors: doors, gates, buttons, crafting tables... nothing else (no chests, no placing).
                if (isVisitorSafe(state) && !sp.isSecondaryUseActive()) return InteractionResult.PASS;
                if (hand == net.minecraft.world.InteractionHand.MAIN_HAND) deny(sp, clicked);
                return InteractionResult.FAIL;
            }
            if (trusted && !admin) {
                // Trusted builders: no chests/furnaces/containers, no lava or fire.
                boolean opening = !sp.isSecondaryUseActive() || held.isEmpty();
                if (opening && protectedFromTrusted(state, level.getBlockEntity(clicked))) {
                    if (hand == net.minecraft.world.InteractionHand.MAIN_HAND) denyTrusted(sp);
                    return InteractionResult.FAIL;
                }
                if (dangerousForTrusted(held)) {
                    if (hand == net.minecraft.world.InteractionHand.MAIN_HAND) denyTrusted(sp);
                    return InteractionResult.FAIL;
                }
            }
            // Right-click a generator with nothing on top: it starts again.
            if (state.is(Blocks.LODESTONE) && level.getBlockState(clicked.above()).isAir() && !REGEN.containsKey(clicked.above().asLong())
                    && !(held.getItem() instanceof BlockItem)) {
                REGEN.put(clicked.above().asLong(), ticks + 10);
                dirty = true;
            }
            // Members: placing outside the border isn't allowed.
            if (held.getItem() instanceof BlockItem || held.getItem() instanceof BucketItem) {
                BlockPos target = state.canBeReplaced() ? clicked : clicked.relative(hit.getDirection());
                if (!admin && !canBuild(sp, target)) {
                    Lobby.msg(sp, "§cThat's outside your island's area. §7Buy more space in §e/is §7(upgrades).");
                    return InteractionResult.FAIL;
                }
                // Count the block's value once it's actually placed.
                if (held.getItem() instanceof BlockItem) {
                    BlockState before = level.getBlockState(target);
                    Lobby.runLater(() -> {
                        BlockState after = level.getBlockState(target);
                        if (after.is(Blocks.LODESTONE) && level.getBlockState(target.above()).isAir()) {
                            REGEN.put(target.above().asLong(), ticks + 20); // a moved/new generator starts by itself
                            dirty = true;
                        }
                        if (after != before && !after.isAir()) {
                            if (is != null && target.getY() < is.minY) {
                                is.minY = target.getY();
                                dirty = true;
                            }
                            addPoints(target, value(after) - value(before));
                            Quests.progress(sp, "island_place", 1);
                            if (is != null && is.isMember(sp.getUUID())) IslandQuests.onPlace(sp, is, after);
                            if (!isPlanting(after)) islandSkill(sp, Skills.Skill.BUILDING, 3); // 5.8: planting isn't building
                            PlayerStats.add(sp, "island_place", 1);
                        }
                    });
                }
            }
            return InteractionResult.PASS;
        });
        UseItemCallback.EVENT.register((player, level, hand) -> {
            if (!isIsland(level) || level.isClientSide() || !(player instanceof ServerPlayer sp)) return InteractionResult.PASS;
            Island is = islandAt(sp.getX(), sp.getZ());
            boolean member = is != null && is.isMember(sp.getUUID());
            boolean trusted = !member && is != null && is.isTrusted(sp.getUUID());
            ItemStack held = sp.getItemInHand(hand);
            if (sp.isCreative()) return InteractionResult.PASS;
            if (trusted && dangerousForTrusted(held)) return InteractionResult.FAIL;
            if (!member && !trusted && (held.getItem() instanceof BucketItem || held.is(Items.ENDER_PEARL) || held.is(Items.FLINT_AND_STEEL)
                    || held.is(Items.FIRE_CHARGE) || held.getItem() instanceof BlockItem)) return InteractionResult.FAIL;
            return InteractionResult.PASS;
        });
        AttackEntityCallback.EVENT.register((player, level, hand, entity, hit) -> {
            if (!isIsland(level)) return InteractionResult.PASS;
            if (entity instanceof Player) return InteractionResult.FAIL; // no PvP on the islands
            if (IslandQuests.isWren(entity) || com.aidan.skyserver.expedition.Expedition.isShipWren(entity)) return InteractionResult.FAIL;
            Island is = islandAt(entity.getX(), entity.getZ());
            return is != null && is.canModify(player.getUUID()) || player.isCreative() ? InteractionResult.PASS : InteractionResult.FAIL;
        });
        UseEntityCallback.EVENT.register((player, level, hand, entity, hit) -> {
            if (!isIsland(level)) return InteractionResult.PASS;
            if (player instanceof ServerPlayer sp && hand == net.minecraft.world.InteractionHand.MAIN_HAND) skillForAnimal(sp, entity, sp.getItemInHand(hand));
            if (com.aidan.skyserver.expedition.Expedition.isShipWren(entity)) {
                if (hand == net.minecraft.world.InteractionHand.MAIN_HAND && player instanceof ServerPlayer sp)
                    com.aidan.skyserver.expedition.ExpeditionMenu.open(sp);
                return InteractionResult.SUCCESS;
            }
            if (IslandQuests.isWren(entity)) {
                if (hand == net.minecraft.world.InteractionHand.MAIN_HAND && player instanceof ServerPlayer sp)
                    IslandQuests.talk(sp, islandAt(entity.getX(), entity.getZ()));
                return InteractionResult.SUCCESS;
            }
            Island is = islandAt(entity.getX(), entity.getZ());
            return is != null && is.canModify(player.getUUID()) || player.isCreative() ? InteractionResult.PASS : InteractionResult.FAIL;
        });
        ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> {
            if (!isIsland(entity.level())) return true;
            if (entity instanceof Player && source.getEntity() instanceof Player) return false;
            if (entity instanceof Player && source.getDirectEntity() != null && source.getDirectEntity().getType().toString().contains("arrow")
                    && source.getEntity() instanceof Player) return false;
            return true;
        });
    }

    /** 5.8: crops, saplings and other plants don't count as building (they give Farming XP when harvested). */
    static boolean isPlanting(BlockState s) {
        Block b = s.getBlock();
        return b instanceof CropBlock || b instanceof StemBlock || b instanceof SaplingBlock || b instanceof SugarCaneBlock
                || b instanceof CactusBlock || b instanceof BambooStalkBlock || b instanceof BambooSaplingBlock || b instanceof CocoaBlock
                || b instanceof NetherWartBlock || b instanceof SweetBerryBushBlock || b instanceof MushroomBlock || b instanceof FlowerBlock
                || b instanceof PitcherCropBlock || b instanceof KelpBlock || b instanceof SeagrassBlock;
    }

    /** Skill XP for something done on the islands; also counts toward the server level (capped, see Levels). */
    private static void islandSkill(ServerPlayer p, Skills.Skill skill, int amount) {
        Skills.add(p, skill, amount);
        Levels.addIslandSkillXp(p, amount);
    }

    /** Mining and Farming skill XP for blocks broken on the islands. */
    private static void skillForBreak(ServerPlayer p, BlockState state) {
        Block b = state.getBlock();
        String id = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(b).getPath();
        if (b instanceof CropBlock crop) {
            if (crop.isMaxAge(state)) {
                islandSkill(p, Skills.Skill.FARMING, 15);
                PlayerStats.add(p, "harvest", 1);
                BattlePass.event(p, "harvest", 1);
            }
        } else if (b == Blocks.MELON || b == Blocks.PUMPKIN || b instanceof SugarCaneBlock || b instanceof CactusBlock
                || b instanceof BambooStalkBlock || b instanceof CocoaBlock || b instanceof NetherWartBlock) {
            islandSkill(p, Skills.Skill.FARMING, 8);
            PlayerStats.add(p, "harvest", 1);
            BattlePass.event(p, "harvest", 1);
        } else if (id.endsWith("_ore")) {
            islandSkill(p, Skills.Skill.MINING, id.contains("diamond") || id.contains("emerald") ? 60 : 15);
            PlayerStats.add(p, "island_ore", 1);
            BattlePass.event(p, "island_ore", 1);
        } else if (b == Blocks.STONE || b == Blocks.COBBLESTONE || b == Blocks.DEEPSLATE || b == Blocks.ANDESITE || b == Blocks.TUFF) {
            islandSkill(p, Skills.Skill.MINING, 3);
        }
    }

    /** Shepherd skill XP: shearing, breeding and milking animals on your island. */
    private static void skillForAnimal(ServerPlayer p, net.minecraft.world.entity.Entity e, ItemStack held) {
        Island is = islandAt(e.getX(), e.getZ());
        if (is == null || !is.canModify(p.getUUID())) return;
        // Each animal only counts once every 30 seconds (no spamming one cow with a bucket).
        Long last = ANIMAL_XP.get(e.getUUID());
        if (last != null && ticks - last < 600) return;
        if (e instanceof net.minecraft.world.entity.animal.sheep.Sheep sheep && held.is(Items.SHEARS) && sheep.readyForShearing()) {
            islandSkill(p, Skills.Skill.SHEPHERD, 12);
        } else if (e instanceof net.minecraft.world.entity.animal.Animal a && a.isFood(held) && a.canFallInLove() && a.getAge() == 0) {
            islandSkill(p, Skills.Skill.SHEPHERD, 15);
        } else if (e instanceof net.minecraft.world.entity.animal.cow.Cow && held.is(Items.BUCKET)) {
            islandSkill(p, Skills.Skill.SHEPHERD, 6);
        } else {
            return;
        }
        ANIMAL_XP.put(e.getUUID(), ticks);
        if (ANIMAL_XP.size() > 5000) ANIMAL_XP.entrySet().removeIf(x -> ticks - x.getValue() > 600);
    }

    private static final Map<UUID, Long> ANIMAL_XP = new HashMap<>();
    private static final Map<UUID, Long> LAST_DENY = new HashMap<>();

    private static void deny(ServerPlayer p, BlockPos pos) {
        Long last = LAST_DENY.get(p.getUUID());
        if (last != null && ticks - last < 40) return;
        LAST_DENY.put(p.getUUID(), ticks);
        Island is = islandAt(pos.getX(), pos.getZ());
        if (is != null && is.canModify(p.getUUID())) Lobby.msg(p, "§cThat's outside your island's area. §7Buy more space in §e/is §7(upgrades).");
        else Lobby.msg(p, "§cYou're visiting: you can look around and use doors and crafting tables, but not build or open chests.");
    }

    private static void denyTrusted(ServerPlayer p) {
        Long last = LAST_DENY.get(p.getUUID());
        if (last != null && ticks - last < 40) return;
        LAST_DENY.put(p.getUUID(), ticks);
        Lobby.msg(p, "§cYou're trusted to build here, but chests, furnaces, other storage, lava and fire are off-limits.");
    }

    // =====================================================================
    // Every tick
    // =====================================================================

    private static void tick() {
        if (server == null) return;
        ticks++;
        ServerLevel level = level();
        if (level == null) return;
        // Generators growing back.
        if (!REGEN.isEmpty()) {
            for (Iterator<Map.Entry<Long, Long>> it = REGEN.entrySet().iterator(); it.hasNext(); ) {
                Map.Entry<Long, Long> e = it.next();
                if (e.getValue() > ticks) continue;
                BlockPos pos = BlockPos.of(e.getKey());
                if (!level.isLoaded(pos)) {
                    e.setValue(ticks + 100); // nobody nearby: grow it when someone comes back
                    continue;
                }
                it.remove();
                dirty = true;
                if (!level.getBlockState(pos).isAir() || !level.getBlockState(pos.below()).is(Blocks.LODESTONE)) continue;
                Island is = islandAt(pos.getX(), pos.getZ());
                if (is != null) {
                    long gk = pos.below().asLong();
                    is.gens.removeIf(g -> !level.isLoaded(BlockPos.of(g)) ? false : !level.getBlockState(BlockPos.of(g)).is(Blocks.LODESTONE));
                    if (!is.gens.contains(gk)) {
                        if (is.gens.size() >= MAX_GENERATORS) {
                            for (ServerPlayer o : level.players())
                                if (o.distanceToSqr(pos.getX(), pos.getY(), pos.getZ()) < 100)
                                    Lobby.msg(o, "§cThis island already has " + MAX_GENERATORS + " working generators. §7Break one to move it here.");
                            continue;
                        }
                        is.gens.add(gk);
                    }
                }
                level.setBlock(pos, rollGenerator(is == null ? 1 : is.genLevel).defaultBlockState(), Block.UPDATE_ALL);
            }
        }
        // Make sure Captain Wren is standing on islands that players just arrived at.
        if (!WREN_CHECKS.isEmpty()) {
            for (Iterator<Map.Entry<Integer, Long>> it = WREN_CHECKS.entrySet().iterator(); it.hasNext(); ) {
                Map.Entry<Integer, Long> e = it.next();
                if (e.getValue() > ticks) continue;
                it.remove();
                Island is = ISLANDS_BY_ID.get(e.getKey());
                if (is != null) IslandQuests.ensureWren(level, is);
            }
        }
        for (ServerPlayer p : level.players()) {
            if (p instanceof BotPlayer) continue;
            Island at = islandAt(p.getX(), p.getZ());
            if (at != null && ticks % 40 == 0 && at.isMember(p.getUUID())) IslandQuests.tick(p, at);
            // Can't fall off: the void sends you home with everything you had.
            // Standing somewhere lower than we knew about (built before this was tracked): remember it.
            if (at != null && p.onGround() && p.getBlockY() - 1 < at.minY) {
                at.minY = p.getBlockY() - 1;
                dirty = true;
            }
            if (fellOff(p, at, level)) {
                Island target = at != null ? at : islandOf(p.getUUID());
                if (target == null) {
                    Lobby.goToLobby(p);
                } else {
                    teleportHome(p, target);
                    Lobby.msg(p, "§7Whoops! You fell - back to the island.");
                }
                continue;
            }
            // Keep the border right if they walked/teleported to another island somehow.
            if (at != null && ticks % 40 == 0) {
                Integer sent = BORDER_SENT.get(p.getUUID());
                if (sent == null || sent != at.id * 10_000 + at.size) sendBorder(p, at);
            }
            // Crop growth upgrade: extra growth ticks around members on their island.
            if (at != null && at.cropLevel > 0 && ticks % 20 == 0 && at.isMember(p.getUUID())) {
                BlockPos c = p.blockPosition();
                for (int i = 0; i < at.cropLevel * 8; i++) {
                    BlockPos pos = c.offset(RNG.nextInt(33) - 16, RNG.nextInt(9) - 4, RNG.nextInt(33) - 16);
                    BlockState s = level.getBlockState(pos);
                    Block b = s.getBlock();
                    if ((b instanceof CropBlock || b instanceof StemBlock || b instanceof SugarCaneBlock || b instanceof CactusBlock
                            || b instanceof SaplingBlock || b instanceof NetherWartBlock || b instanceof CocoaBlock
                            || b instanceof SweetBerryBushBlock || b instanceof BambooStalkBlock) && s.isRandomlyTicking()) {
                        s.randomTick(level, pos, level.getRandom());
                    }
                }
            }
        }
        // Islands where everyone is asleep skip to morning (only that island).
        if (ticks % 20 == 0) IslandTime.tick(level);
        // Old requests expire; finished trusts end; save if anything changed.
        if (ticks % 100 == 0) {
            VISIT_REQUESTS.entrySet().removeIf(e -> ticks - e.getValue()[1] > 1200);
            INVITES.entrySet().removeIf(e -> ticks - e.getValue()[1] > 6000);
            for (Island is : ISLANDS_BY_ID.values()) expireTrust(is);
            if (dirty) {
                dirty = false;
                save();
            }
        }
    }

    // =====================================================================
    // Leaderboard
    // =====================================================================

    /** Richest islands by worth (placed-block value). Bragging rights only. */
    public static List<String> top(int n) {
        List<Island> list = new ArrayList<>(all());
        list.sort((a, b) -> Long.compare(b.points, a.points));
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < Math.min(n, list.size()); i++) {
            if (list.get(i).points <= 0) break;
            lines.add("§6" + (i + 1) + ". §f" + list.get(i).ownerName + " §7worth §e" + list.get(i).worth());
        }
        if (lines.isEmpty()) lines.add("§7nobody yet - type §e/is");
        return lines;
    }

    // =====================================================================
    // Commands
    // =====================================================================

    public static void registerCommands(CommandDispatcher<CommandSourceStack> d) {
        for (String root : new String[]{"is", "island"}) {
            LiteralArgumentBuilder<CommandSourceStack> is = Commands.literal(root).executes(c -> {
                ServerPlayer p = c.getSource().getPlayerOrException();
                if (isIsland(p.level()) && islandOf(p.getUUID()) != null) IslandMenu.open(p);
                else goHome(p);
                return 1;
            });
            is.then(Commands.literal("home").executes(c -> { goHome(c.getSource().getPlayerOrException()); return 1; }));
            is.then(Commands.literal("go").executes(c -> { goHome(c.getSource().getPlayerOrException()); return 1; }));
            is.then(Commands.literal("menu").executes(c -> { menu(c.getSource().getPlayerOrException()); return 1; }));
            is.then(Commands.literal("upgrade").executes(c -> { menu(c.getSource().getPlayerOrException()); return 1; }));
            is.then(Commands.literal("challenges").executes(c -> {
                ServerPlayer p = c.getSource().getPlayerOrException();
                if (islandOf(p.getUUID()) == null) Lobby.msg(p, "§cYou don't have an island yet. Type §e/is§c.");
                else ChallengeMenu.open(p, 0);
                return 1;
            }));
            is.then(Commands.literal("contribute").then(Commands.argument("amount", com.mojang.brigadier.arguments.LongArgumentType.longArg(1))
                    .executes(c -> { contribute(c.getSource().getPlayerOrException(), com.mojang.brigadier.arguments.LongArgumentType.getLong(c, "amount")); return 1; })));
            is.then(Commands.literal("fund").executes(c -> {
                ServerPlayer p = c.getSource().getPlayerOrException();
                Island i = islandOf(p.getUUID());
                Lobby.msg(p, i == null ? "§cYou don't have an island." : "§aIsland fund: §f" + Economy.format(i.fund)
                        + " §7- anyone on the island can add with §e/is contribute <amount>§7. Upgrades use it first.");
                return 1;
            }));
            is.then(Commands.literal("setwarp").then(Commands.argument("name", StringArgumentType.word())
                    .executes(c -> { setWarp(c.getSource().getPlayerOrException(), StringArgumentType.getString(c, "name")); return 1; })));
            is.then(Commands.literal("delwarp").then(Commands.argument("name", StringArgumentType.word())
                    .executes(c -> { delWarp(c.getSource().getPlayerOrException(), StringArgumentType.getString(c, "name")); return 1; })));
            is.then(Commands.literal("warps").executes(c -> { warps(c.getSource().getPlayerOrException()); return 1; }));
            is.then(Commands.literal("warp").executes(c -> { warps(c.getSource().getPlayerOrException()); return 1; })
                    .then(Commands.argument("name", StringArgumentType.word())
                            .suggests((c, b) -> {
                                Island i = c.getSource().getPlayer() == null ? null : islandOf(c.getSource().getPlayer().getUUID());
                                return SharedSuggestionProvider.suggest(i == null ? List.of() : i.warps.keySet(), b);
                            })
                            .executes(c -> { warp(c.getSource().getPlayerOrException(), StringArgumentType.getString(c, "name")); return 1; })));
            is.then(Commands.literal("chat").then(Commands.argument("message", StringArgumentType.greedyString())
                    .executes(c -> { chat(c.getSource().getPlayerOrException(), StringArgumentType.getString(c, "message")); return 1; })));
            is.then(Commands.literal("sethome").executes(c -> {
                ServerPlayer p = c.getSource().getPlayerOrException();
                Island i = islandOf(p.getUUID());
                if (i == null || !isIsland(p.level()) || !i.inBorder(p.getX(), p.getZ()) || islandAt(p.getX(), p.getZ()) != i) {
                    Lobby.msg(p, "§cStand on your own island to set its home.");
                    return 0;
                }
                i.home = new double[]{p.getX(), p.getY(), p.getZ(), p.getYRot()};
                save();
                Lobby.msg(p, "§aIsland home set here.");
                return 1;
            }));
            is.then(Commands.literal("visit").then(Commands.argument("player", StringArgumentType.word())
                    .suggests((c, b) -> {
                        List<String> names = new ArrayList<>();
                        for (ServerPlayer p : server.getPlayerList().getPlayers()) if (!(p instanceof BotPlayer)) names.add(p.getName().getString());
                        for (Island i : all()) if (i.open) names.add(i.ownerName);
                        return SharedSuggestionProvider.suggest(names, b);
                    })
                    .executes(c -> { visit(c.getSource().getPlayerOrException(), StringArgumentType.getString(c, "player")); return 1; })));
            is.then(Commands.literal("accept")
                    .executes(c -> { answerVisit(c.getSource().getPlayerOrException(), null, true); return 1; })
                    .then(Commands.argument("player", StringArgumentType.word()).executes(c -> {
                        answerVisit(c.getSource().getPlayerOrException(), StringArgumentType.getString(c, "player"), true);
                        return 1;
                    })));
            is.then(Commands.literal("deny")
                    .executes(c -> { answerVisit(c.getSource().getPlayerOrException(), null, false); return 1; })
                    .then(Commands.argument("player", StringArgumentType.word()).executes(c -> {
                        answerVisit(c.getSource().getPlayerOrException(), StringArgumentType.getString(c, "player"), false);
                        return 1;
                    })));
            is.then(Commands.literal("open").executes(c -> { setOpen(c.getSource().getPlayerOrException(), true); return 1; }));
            is.then(Commands.literal("close").executes(c -> { setOpen(c.getSource().getPlayerOrException(), false); return 1; }));
            is.then(Commands.literal("invite").then(Commands.argument("player", net.minecraft.commands.arguments.EntityArgument.player()).executes(c -> {
                invite(c.getSource().getPlayerOrException(), net.minecraft.commands.arguments.EntityArgument.getPlayer(c, "player"));
                return 1;
            })));
            is.then(Commands.literal("join")
                    .executes(c -> { join(c.getSource().getPlayerOrException(), null); return 1; })
                    .then(Commands.argument("owner", StringArgumentType.word()).executes(c -> {
                        join(c.getSource().getPlayerOrException(), StringArgumentType.getString(c, "owner"));
                        return 1;
                    })));
            is.then(Commands.literal("kick").then(Commands.argument("player", StringArgumentType.word()).executes(c -> {
                kick(c.getSource().getPlayerOrException(), StringArgumentType.getString(c, "player"));
                return 1;
            })));
            is.then(Commands.literal("leave").executes(c -> { leave(c.getSource().getPlayerOrException()); return 1; }));
            is.then(Commands.literal("trust").then(Commands.argument("player", net.minecraft.commands.arguments.EntityArgument.player())
                    .executes(c -> {
                        trust(c.getSource().getPlayerOrException(), net.minecraft.commands.arguments.EntityArgument.getPlayer(c, "player"), TRUST_DEFAULT_MIN);
                        return 1;
                    })
                    .then(Commands.argument("minutes", IntegerArgumentType.integer(1, TRUST_MAX_MIN)).executes(c -> {
                        trust(c.getSource().getPlayerOrException(), net.minecraft.commands.arguments.EntityArgument.getPlayer(c, "player"),
                                IntegerArgumentType.getInteger(c, "minutes"));
                        return 1;
                    }))));
            is.then(Commands.literal("untrust").then(Commands.argument("player", StringArgumentType.word())
                    .suggests((c, b) -> {
                        Island i = c.getSource().getPlayer() == null ? null : islandOf(c.getSource().getPlayer().getUUID());
                        return SharedSuggestionProvider.suggest(i == null ? List.of() : i.trustedNames.values(), b);
                    })
                    .executes(c -> { untrust(c.getSource().getPlayerOrException(), StringArgumentType.getString(c, "player")); return 1; })));
            is.then(Commands.literal("trusted").executes(c -> { listTrusted(c.getSource().getPlayerOrException()); return 1; }));
            is.then(Commands.literal("movewren").executes(c -> { IslandQuests.moveWren(c.getSource().getPlayerOrException()); return 1; }));
            is.then(Commands.literal("members").executes(c -> {
                ServerPlayer p = c.getSource().getPlayerOrException();
                Island i = islandOf(p.getUUID());
                if (i == null) {
                    Lobby.msg(p, "§7You don't have an island yet.");
                    return 0;
                }
                Lobby.msg(p, "§e" + i.name() + " §7- owner §f" + i.ownerName + "§7, members: §f"
                        + (i.members.isEmpty() ? "none" : String.join(", ", i.memberNames.values())) + " §7(" + i.members.size() + "/" + MAX_MEMBERS + ")");
                return 1;
            }));
            is.then(Commands.literal("level").executes(c -> {
                ServerPlayer p = c.getSource().getPlayerOrException();
                Island i = islandOf(p.getUUID());
                if (i == null) Lobby.msg(p, "§7You don't have an island yet.");
                else {
                    Lobby.msg(p, "§eYour island's level is §f" + i.level() + " §7(the highest server level of anyone on it). It unlocks challenges. §7(/level)");
                    Lobby.msg(p, "§eIsland worth §f" + i.worth() + " §7(" + String.format("%,d", i.points) + " points from placed blocks - see §e/is top§7)");
                }
                return 1;
            }));
            is.then(Commands.literal("top").executes(c -> {
                ServerPlayer p = c.getSource().getPlayerOrException();
                Lobby.msg(p, "§6§lRICHEST ISLANDS §7(island worth - just for bragging)");
                for (String line : top(10)) Lobby.msg(p, line);
                return 1;
            }));
            is.then(Commands.literal("help").executes(c -> { help(c.getSource().getPlayerOrException()); return 1; }));
            is.then(Commands.literal("quest").executes(c -> { IslandQuests.status(c.getSource().getPlayerOrException()); return 1; }));
            is.then(Commands.literal("theme").executes(c -> { IslandThemes.open(c.getSource().getPlayerOrException()); return 1; }));
            is.then(Commands.literal("expedition").executes(c -> {
                com.aidan.skyserver.expedition.ExpeditionMenu.openSummon(c.getSource().getPlayerOrException());
                return 1;
            }));
            d.register(is);
        }
    }

    private static void menu(ServerPlayer p) {
        if (islandOf(p.getUUID()) == null) Lobby.msg(p, "§cYou don't have an island yet. Type §e/is§c.");
        else IslandMenu.open(p);
    }

    private static void setOpen(ServerPlayer p, boolean open) {
        Island i = islandOf(p.getUUID());
        if (i == null || !i.owner.equals(p.getUUID())) {
            Lobby.msg(p, "§cOnly the island owner can change that.");
            return;
        }
        i.open = open;
        save();
        Lobby.msg(p, open ? "§aYour island is now §lopen§a: anyone can visit without asking." : "§eYour island is now closed: visitors have to ask.");
    }

    public static void help(ServerPlayer p) {
        String[] lines = {
                "§6§lSKY ISLAND COMMANDS",
                "§e/is §7- go to your island (or open the menu when you're there)",
                "§e/is sethome §7- set where you appear on your island",
                "§e/is visit <player> §7- ask to visit someone's island",
                "§e/is accept §7/ §e/is deny §7- answer a visit request",
                "§e/is open §7/ §e/is close §7- let anyone visit, or make them ask",
                "§e/is invite <player> §7- invite a co-op member (up to " + MAX_MEMBERS + "; owner, or a co-op member who's a moderator)",
                "§e/is join <owner> §7- accept a co-op invite",
                "§e/is kick <player> §7- remove a member or send a visitor away",
                "§e/is leave §7- leave a co-op island",
                "§e/is trust <player> [minutes] §7- let someone build for a while (60 min default; no chests)",
                "§e/is untrust <player> §7| §e/is trusted §7- take it back / see who has it",
                "§e/is movewren §7- move Captain Wren to where you're standing",
                "§e/is members §7| §e/is level §7| §e/is top §7| §e/is challenges",
                "§e/is quest §7- Captain Wren's current quest (talk to him by the path)",
                "§e/is theme §7- change your island's look (needs a theme token)",
                "§e/is expedition §7- call Captain Wren's airship for a Nether Expedition",
                "§e/claim §7- collect mystery chest prizes on your island",
                "§e/lobby §7- back to the lobby (stand still 5 seconds)"};
        for (String l : lines) Lobby.msg(p, l);
    }
}
