package com.aidan.skyserver.expedition;

import com.aidan.skyserver.Safe;
import com.aidan.skyserver.Skyserver;
import com.aidan.skyserver.bots.BotPlayer;
import com.aidan.skyserver.island.Islands;
import com.aidan.skyserver.island.Tribute;
import com.aidan.skyserver.island.WrenRoom;
import com.aidan.skyserver.lobby.*;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.BossEvent;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BellAttachType;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;
import java.nio.file.*;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;

/**
 * 5.20: the SUNKEN MONUMENT EXPEDITION - a flooded prismarine monument (SunkenMap), paid in farm items.
 *
 * HOW A TRIP GOES
 *  - Talk to Captain Wren in his ship room -> Sunken Monument, pick a difficulty. The fare is ITEMS, put in
 *    the room's Tribute Chests (see Tribute): 64 / 128 / 192 / 256 each of iron, copper, coal, redstone,
 *    cooked chicken, wool, seeds, wheat and carrots, plus dried kelp blocks, pumpkins and sugar cane
 *    (and 16 diamonds on Nightmare). Up to 3 (island and faction mates) sail; everyone puts their own fare in
 *    the HOST's chests, and gets their own loot and rewards. Wren takes the items when the crew leaves.
 *  - Land in WREN'S DOCK (dry). Wren gives Water Breathing for the first minute only - bring your own after.
 *    Then the crew opens the FLOODGATES one wing at a time, in the order they choose (the first wing's chests
 *    each give a bonus roll).
 *  - Each ROOM starts flooded: walk in and its guards come (guardians and drowned, two waves). Every player
 *    gets 2 sponges in each room (they disappear when you leave). RISING TIDE: right-click the pump to drain
 *    some water; the water keeps rising, faster with more players and with every LEAK - a surge opens one
 *    every so often: crouch next to it for 3 seconds to plug it. Beat the guards and the room drains and the
 *    chest opens (each player loots it once).
 *  - RIPTIDE CURRENTS: the tube above each wing's corridor sweeps you back to the hub - unless it's a trap
 *    current (random, per current), which dumps you in the Guardian Pit under the hub.
 *  - THE SUNKEN BELL tolls every 2 minutes: 10 seconds of enraged guards (a chime warns you 3 seconds early).
 *    INK BLACKOUT: every 45 seconds, 5 seconds of darkness outside the dock and drained rooms.
 *  - All three wings done: the VAULT GATE opens. Down the shaft waits WREN'S DIVING BELL: push it (stand
 *    behind it) along the Deep Passage. Near it you can breathe. Guards attack it; it's very hard to sink.
 *    At the end, the TIDEMOTHER (an Elder Guardian) rises. Her charged beam hits everyone she can see -
 *    hide behind the vault's pillars. No Mining Fatigue on this trip.
 *  - Beat her: CASH on a clear (5x the fare's shop value on Easy up to 7x on Nightmare; each player's first 2
 *    clears a day pay full, later ones half), keys, shards, pass XP, a Wren's Charts stamp. If the bell is
 *    still afloat, the VAULT too: heart of the sea, Tide trim, conduits (Hard+), real sponges.
 *  - Drowned killed by a player drop a trident 0.1% of the time.
 *  - Dying drops what you carry where you fell (Recovery Charm saves it). Downed first if a teammate can pick
 *    you up (once per team) or you can pay the revive (once per player). No buckets, no player-vs-player.
 */
public final class SunkenMonument {
    private SunkenMonument() {}

    public static final ResourceKey<Level> DIM = ResourceKey.create(Registries.DIMENSION, Identifier.fromNamespaceAndPath(Skyserver.MOD_ID, "sunken"));
    public static final String[] DIFF_NAMES = Tokens.DIFF_NAMES;
    public static final String[] DIFF_COLORS = {"§a", "§e", "§c", "§4"};

    // ---- Every number (index 0 Easy .. 3 Nightmare) ----
    /** Cash on a clear: the fare's shop value x5 (Easy) .. x7 (Nightmare). */
    static final long[] CASH = {5_000, 11_500, 19_000, 35_000};
    /** Each player's first FULL_CLEARS clears a day pay the full cash, later ones CASH_LATER of it. */
    static final int FULL_CLEARS = 2;
    static final double CASH_LATER = 0.5;
    /** Time limits (seconds), longer with a crew. */
    static final int[] TIME_LIMITS = {20 * 60, 25 * 60, 30 * 60, 35 * 60};
    static final double[] TIME_MULT = {1.0, 1.25, 1.4};
    static final int CREW_MAX = 3, MUSTER_SECONDS = 30, MAX_RUNS = 4;
    /** Guards per wave (two waves per room), one more per wave per extra player. */
    static final int[][] WAVES = {{3, 3}, {3, 4}, {4, 5}, {5, 6}};
    static final int WAVE_SECONDS = 45;
    static final double[] HEALTH = {0.2, 0.4, 0.75, 1.3}, DAMAGE = {0.1, 0.3, 0.6, 1.0};
    static final double CREW_HEALTH = 0.70, CREW_DAMAGE = 0.10;
    /** Rising Tide: layers a second for one player (x1.5 per extra player), +50% per open leak. A pump click drains PUMP layers. */
    static final double[] FLOOD = {0.05, 0.06, 0.075, 0.09};
    static final double CREW_FLOOD = 0.5, LEAK_FLOOD = 0.5, PUMP = 0.15;
    static final int PUMP_COOLDOWN = 10, LEAK_SECONDS = 25, MAX_LEAKS = 3, PLUG_TICKS = 60;
    /** Water Breathing from Wren at the start (first room only). */
    static final int START_BREATH_TICKS = 60 * 20;
    static final int BELL_TOLL_SECONDS = 120, BELL_RAGE_SECONDS = 10, INK_EVERY = 45, INK_SECONDS = 5;
    /** The diving bell: health (x1.25 per extra player), push speed (blocks a second per pusher, up to 3 count). */
    static final int BELL_HP = 600;
    static final double BELL_PUSH = 0.6;
    /** The Tidemother: health (x1.75 with 2, x2.3 with 3), beam damage, seconds between beams / helpers. */
    static final double[] BOSS_HP = {300, 450, 650, 900};
    static final double[] BOSS_CREW = {1.0, 1.75, 2.3};
    static final float[] BEAM = {8, 10, 12, 15};
    static final int BEAM_EVERY = 12, BEAM_CHARGE = 2, HELPERS_EVERY = 30;
    /** Revives: the team's one free pick-up, and a paid revive (once per player). */
    static final long[] REVIVE_COST = {1_000, 2_300, 3_800, 7_000};
    static final double TEAM_REVIVE_HEALTH = 0.5, TEAM_REVIVE_RANGE = 2.5;
    static final int TEAM_REVIVE_SECONDS = 30, REVIVE_SECONDS = 30, TEAM_REVIVE_HOLD_TICKS = 100, REVIVE_PROTECT_TICKS = 60;
    /** Vault (only if the bell made it): Tide trim %, heart of the sea %, conduit %, real sponges. */
    static final int[] TIDE_CHANCE = {10, 20, 40, 60}, HEART_CHANCE = {50, 50, 100, 100}, CONDUIT_CHANCE = {0, 0, 10, 20}, VAULT_SPONGES = {1, 1, 2, 2};
    static final int[] KEY_TIER = {1, 2, 3, 3}, SHARDS = {5, 11, 21, 42}, PASS_XP = {315, 525, 840, 1_260}, XP = {158, 263, 420, 630};
    /** A drowned killed by a player drops a trident 1 time in TRIDENT_ODDS. */
    static final int TRIDENT_ODDS = 1000;
    static final String GUARD_TAG = "sunken_guard", WREN_TAG = "skyserver_sunken_wren", SPONGE_TAG = "sunken_sponge", TITLE = "title_tidebreaker";

    static final class Run {
        final int id, diff, islandId;
        final UUID leader;
        final List<UUID> crew = new ArrayList<>();
        final Set<UUID> inside = new LinkedHashSet<>();
        final Set<UUID> revived = new HashSet<>();
        final Set<Long> placed = new HashSet<>();
        final Map<Long, Boolean> sponges = new HashMap<>(); // placed sponge -> was it ours (tagged)
        final Map<UUID, Map<String, Integer>> fares = new HashMap<>();
        final Map<UUID, Long> pumpUsed = new HashMap<>();
        final Map<UUID, Integer> plugging = new HashMap<>();
        SunkenMap.Layout layout;
        boolean building = true, over = false, teamReviveUsed = false, won = false;
        long startTick;
        int timeLimit, warned = 0, crewSize = 1;
        UUID wren, boss;
        /** -1 = waiting for the crew to choose; otherwise the open wing. */
        int openWing = -1, firstWing = -1;
        final boolean[] wingDone = new boolean[3];
        boolean vaultOpen = false, bossUp = false, bellLost = false;
        int bellU = SunkenMap.BELL_START;
        double bellProgress = 0, bellHp, bellMax;
        final List<BlockPos> bellBlocks = new ArrayList<>();
        long nextToll, nextInk, rageUntil = 0, nextBeam = 0, beamAt = -1, nextHelpers = 0, nextPassageGuards = 0;
        ServerBossEvent bar;
        /** 5.21: an op's test trip (/sunken admin test): no fare, no rewards, test commands work. testCrew = crew size it pretends to have. */
        boolean test = false, god = false;
        int testCrew = 1;

        Run(int id, int diff, int islandId, UUID leader) {
            this.id = id;
            this.diff = diff;
            this.islandId = islandId;
            this.leader = leader;
        }
    }

    private static final class Muster {
        final UUID leader;
        final int islandId, diff;
        final LinkedHashSet<UUID> joined = new LinkedHashSet<>();
        long deadline;

        Muster(UUID leader, int islandId, int diff) {
            this.leader = leader;
            this.islandId = islandId;
            this.diff = diff;
        }
    }

    private static final class Downed {
        final double x, y, z;
        long deadline;
        int phase, hold = 0;

        Downed(double x, double y, double z, int phase, long deadline) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.phase = phase;
            this.deadline = deadline;
        }
    }

    private static MinecraftServer server;
    private static final List<Run> RUNS = new ArrayList<>();
    private static final List<Muster> MUSTERS = new ArrayList<>();
    private static final Map<UUID, Downed> DOWNED = new HashMap<>();
    private static final Map<UUID, Long> PROTECTED = new HashMap<>();
    /** guard -> {run, room or null} */
    private static final Map<UUID, Object[]> GUARD_OF = new HashMap<>();
    private static final Random RNG = new Random();
    private static long ticks = 0;
    private static int runCounter = 0;

    public static boolean isSunken(Level l) {
        return l != null && l.dimension() == DIM;
    }

    static ServerLevel level() {
        return server == null ? null : server.getLevel(DIM);
    }

    static Run runOf(UUID id) {
        for (Run r : RUNS) if (r.inside.contains(id)) return r;
        return null;
    }

    private static Muster musterOf(UUID id) {
        for (Muster m : MUSTERS) if (m.leader.equals(id) || m.joined.contains(id)) return m;
        return null;
    }

    // =====================================================================
    // Setup (from Expedition.init, before Lobby)
    // =====================================================================

    public static void init() {
        ServerLifecycleEvents.SERVER_STARTING.register(s -> Safe.run("sunken wipe", () -> {
            try {
                wipe(s);
            } catch (IOException e) {
                Skyserver.LOGGER.warn("Couldn't wipe the Sunken Monument world: {}", e.toString());
            }
        }));
        ServerLifecycleEvents.SERVER_STARTED.register(s -> {
            server = s;
            if (s.getLevel(DIM) == null) Skyserver.LOGGER.error("Sunken Monument dimension skyserver:sunken is missing (resources/data/skyserver/dimension/sunken.json).");
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(s -> Safe.run("sunken stop", SunkenMonument::shutdown));
        ServerTickEvents.END_SERVER_TICK.register(s -> {
            ticks++;
            if (MUSTERS.isEmpty() && RUNS.isEmpty() && DOWNED.isEmpty()) return;
            Safe.run("sunken fast tick", SunkenMonument::fastTick);
            if (ticks % 20 == 0) for (Run r : new ArrayList<>(RUNS)) Safe.run("sunken tick", () -> tick(r));
        });

        ServerLivingEntityEvents.ALLOW_DEATH.register((entity, source, amount) -> {
            if (!(entity instanceof ServerPlayer p) || p instanceof BotPlayer || !isSunken(p.level()) || runOf(p.getUUID()) == null) return true;
            if (p.getMainHandItem().is(Items.TOTEM_OF_UNDYING) || p.getOffhandItem().is(Items.TOTEM_OF_UNDYING)) return true;
            if (DOWNED.containsKey(p.getUUID())) {
                p.setHealth(1f);
                return false;
            }
            Safe.run("sunken death", () -> {
                if (!goDown(p)) died(p, "died");
            });
            return false;
        });
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
            if (!isSunken(entity.level())) return;
            Safe.run("sunken mob death", () -> mobDied(entity, source.getEntity()));
        });
        ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> {
            if (!isSunken(entity.level())) return true;
            if (entity.entityTags().contains(WREN_TAG)) return false;
            if (entity instanceof ServerPlayer p) {
                if (DOWNED.containsKey(p.getUUID())) return false;
                Long safe = PROTECTED.get(p.getUUID());
                if (safe != null) {
                    if (ticks < safe) return false;
                    PROTECTED.remove(p.getUUID());
                }
                if (source.getEntity() instanceof Player) return false; // no player-vs-player
                Run run = runOf(p.getUUID());
                if (run != null && run.test && run.god) return false; // 5.21: test trip god mode
                return run == null || run.layout == null || !run.layout.inDock(p.getX(), p.getY(), p.getZ()); // the dock is safe
            }
            return true;
        });
        UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
            if (level.isClientSide() || !isSunken(level) || !(player instanceof ServerPlayer sp)) return InteractionResult.PASS;
            Run run = runOf(sp.getUUID());
            if (run == null || run.layout == null || sp.isCreative()) return InteractionResult.PASS;
            BlockPos pos = hit.getBlockPos();
            boolean main = hand == net.minecraft.world.InteractionHand.MAIN_HAND;
            if (pos.equals(run.layout.dryer)) {
                if (main) Safe.run("sunken dryer", () -> drySponges(sp));
                return InteractionResult.SUCCESS;
            }
            for (SunkenMap.Room r : run.layout.rooms) {
                if (pos.equals(r.pump)) {
                    if (main) Safe.run("sunken pump", () -> pump(sp, run, r));
                    return InteractionResult.SUCCESS;
                }
                if (pos.equals(r.chest)) {
                    if (main) Safe.run("sunken chest", () -> openChest(sp, run, r));
                    return InteractionResult.SUCCESS;
                }
            }
            ItemStack held = player.getItemInHand(hand);
            if (held.getItem() instanceof BucketItem) {
                tip(sp, "§cNo buckets in the Sunken Monument - use the pumps and sponges.");
                return InteractionResult.FAIL;
            }
            if (held.getItem() instanceof BlockItem) {
                BlockPos target = level.getBlockState(pos).canBeReplaced() ? pos : pos.relative(hit.getDirection());
                if (run.layout.isMapBlock(target) || run.bellBlocks.contains(target)) return InteractionResult.FAIL;
                boolean sponge = held.is(Items.SPONGE) || held.is(Items.WET_SPONGE);
                boolean ours = Stacks.is(held, SPONGE_TAG);
                Lobby.runLaterTicks(1, () -> Safe.run("sunken place", () -> {
                    if (run.over) return;
                    BlockState now = level.getBlockState(target);
                    if (sponge && (now.is(Blocks.SPONGE) || now.is(Blocks.WET_SPONGE))) run.sponges.put(target.asLong(), ours);
                    else if (!now.canBeReplaced()) run.placed.add(target.asLong());
                }));
            }
            return InteractionResult.PASS;
        });
        UseItemCallback.EVENT.register((player, level, hand) -> {
            if (level.isClientSide() || !isSunken(level)) return InteractionResult.PASS;
            if (player.getItemInHand(hand).getItem() instanceof BucketItem && player instanceof ServerPlayer sp && !sp.isCreative()) {
                tip(sp, "§cNo buckets in the Sunken Monument - use the pumps and sponges.");
                return InteractionResult.FAIL;
            }
            return InteractionResult.PASS;
        });
        UseEntityCallback.EVENT.register((player, level, hand, entity, hit) -> {
            if (!isSunken(level) || !entity.entityTags().contains(WREN_TAG)) return InteractionResult.PASS;
            if (hand == net.minecraft.world.InteractionHand.MAIN_HAND && player instanceof ServerPlayer sp) Safe.run("sunken wren", () -> talk(sp));
            return InteractionResult.SUCCESS;
        });
        AttackEntityCallback.EVENT.register((player, level, hand, entity, hit) ->
                isSunken(level) && entity.entityTags().contains(WREN_TAG) ? InteractionResult.FAIL : InteractionResult.PASS);
        PlayerBlockBreakEvents.BEFORE.register((level, player, pos, state, be) -> {
            if (!isSunken(level) || !(player instanceof ServerPlayer sp) || sp.isCreative()) return true;
            Run run = runOf(sp.getUUID());
            if (run == null || run.layout == null) return true;
            Boolean ours = run.sponges.remove(pos.asLong());
            if (ours != null) {
                // A placed sponge comes back as it went in (tagged sponges stay tagged).
                ItemStack back = new ItemStack(state.is(Blocks.WET_SPONGE) ? Items.WET_SPONGE : Items.SPONGE);
                level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                EndRewards.give(sp, ours ? sponge(back) : back);
                return false;
            }
            if (run.placed.remove(pos.asLong())) return true;
            if (run.layout.isMapBlock(pos) || run.bellBlocks.contains(pos)) {
                tip(sp, "§7The monument's stone has stood for ages - it won't break.");
                return false;
            }
            return true;
        });
        // Registered before Lobby's (Expedition.init runs before Lobby), so things drop before anything saves.
        ServerPlayConnectionEvents.DISCONNECT.register((handler, s) -> Safe.run("sunken logout", () -> loggedOut(handler.player)));

        CommandRegistrationCallback.EVENT.register((d, r, e) -> d.register(Commands.literal("sunken")
                .executes(c -> { status(c.getSource().getPlayerOrException()); return 1; })
                .then(Commands.literal("call").then(Commands.argument("difficulty", IntegerArgumentType.integer(0, 3))
                        .executes(c -> { call(c.getSource().getPlayerOrException(), IntegerArgumentType.getInteger(c, "difficulty")); return 1; })))
                .then(Commands.literal("join").executes(c -> { join(c.getSource().getPlayerOrException()); return 1; }))
                .then(Commands.literal("go").executes(c -> { goNow(c.getSource().getPlayerOrException()); return 1; }))
                .then(Commands.literal("gate").then(Commands.argument("wing", IntegerArgumentType.integer(0, 2))
                        .executes(c -> { openGate(c.getSource().getPlayerOrException(), IntegerArgumentType.getInteger(c, "wing")); return 1; })))
                .then(Commands.literal("home").executes(c -> { goHome(c.getSource().getPlayerOrException()); return 1; }))
                .then(Commands.literal("revive").executes(c -> { revive(c.getSource().getPlayerOrException()); return 1; }))
                .then(Commands.literal("giveup").executes(c -> { giveUp(c.getSource().getPlayerOrException()); return 1; }))
                .then(Commands.literal("help").executes(c -> { help(c.getSource().getPlayerOrException()); return 1; }))
                .then(Commands.literal("admin").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        // 5.21: op test mode.
                        .then(Commands.literal("test").then(Commands.argument("difficulty", IntegerArgumentType.integer(0, 3))
                                .executes(c -> { startTest(c.getSource().getPlayerOrException(), IntegerArgumentType.getInteger(c, "difficulty"), 1); return 1; })
                                .then(Commands.argument("crew", IntegerArgumentType.integer(1, CREW_MAX))
                                        .executes(c -> { startTest(c.getSource().getPlayerOrException(), IntegerArgumentType.getInteger(c, "difficulty"),
                                                IntegerArgumentType.getInteger(c, "crew")); return 1; }))))
                        .then(Commands.literal("skip").executes(c -> { testSkip(c.getSource().getPlayerOrException(), false); return 1; }))
                        .then(Commands.literal("wing").executes(c -> { testSkip(c.getSource().getPlayerOrException(), true); return 1; }))
                        .then(Commands.literal("vault").executes(c -> { testVault(c.getSource().getPlayerOrException(), false); return 1; }))
                        .then(Commands.literal("boss").executes(c -> { testVault(c.getSource().getPlayerOrException(), true); return 1; }))
                        .then(Commands.literal("god").executes(c -> { testGod(c.getSource().getPlayerOrException()); return 1; }))
                        .then(Commands.literal("time").then(Commands.argument("minutes", IntegerArgumentType.integer(1, 120))
                                .executes(c -> { testTime(c.getSource().getPlayerOrException(), IntegerArgumentType.getInteger(c, "minutes")); return 1; })))
                        .then(Commands.literal("end").executes(c -> {
                            int n = RUNS.size() + MUSTERS.size();
                            for (Run rn : new ArrayList<>(RUNS)) {
                                for (ServerPlayer p : inside(rn)) extract(p, "§6[Captain Wren] §fAn admin called us home!");
                                endRun(rn, "ended by an admin");
                            }
                            MUSTERS.clear();
                            c.getSource().sendSuccess(() -> Component.literal("Ended " + n + " Sunken Monument trip(s)."), true);
                            return 1;
                        })))));
    }

    private static void wipe(MinecraftServer s) throws IOException {
        Path dir = s.getWorldPath(LevelResource.ROOT).resolve("dimensions").resolve(Skyserver.MOD_ID).resolve(DIM.identifier().getPath());
        if (!Files.isDirectory(dir)) return;
        try (var walk = Files.walk(dir)) {
            List<Path> all = new ArrayList<>(walk.toList());
            all.sort(Comparator.reverseOrder());
            for (Path p : all) Files.deleteIfExists(p);
        }
        Skyserver.LOGGER.info("Sunken Monument world wiped (a new monument is built for every trip).");
    }

    // =====================================================================
    // Wren's menu button, the difficulty menu, the muster
    // =====================================================================

    public static ItemStack islandButton(ServerPlayer p) {
        Run mine = runOf(p.getUUID());
        String state = mine != null ? "§eYou're in the Sunken Monument" : MUSTERS.size() + RUNS.size() >= MAX_RUNS ? "§cWren's diving crew is busy - try soon" : "§eClick to choose a difficulty";
        return Stacks.named(new ItemStack(Items.PRISMARINE_BRICKS), "§b§lSunken Monument Expedition",
                "§7Drain flooded rooms, beat their guards,", "§7then push Wren's diving bell to the vault", "§7and the §lTidemother§7.",
                "§7Fare: §ffarm items §7in the Tribute Chests", "§8(iron, copper, coal, redstone, chicken, wool...)",
                "§7Cash on a clear: " + DIFF_COLORS[0] + k(CASH[0]) + " " + DIFF_COLORS[1] + k(CASH[1]) + " " + DIFF_COLORS[2] + k(CASH[2]) + " " + DIFF_COLORS[3] + k(CASH[3]),
                "§7Up to " + CREW_MAX + " sail (island or faction mates).", "§c§lDying loses everything you carry!", state);
    }

    private static String k(long v) {
        return v % 1000 == 0 ? "$" + (v / 1000) + "k" : "$" + (v / 100) / 10.0 + "k";
    }

    public static void openMenu(ServerPlayer p) {
        Islands.Island is = Islands.islandOf(p.getUUID());
        if (is == null) {
            Lobby.msg(p, "§cYou need a Sky Island first.");
            return;
        }
        LockedMenu.open(p, "§8Sunken Monument Expedition", 3, (id, inv, box, v) -> new DiffMenu(id, inv, box, v, is.id));
    }

    static final class DiffMenu extends LockedMenu {
        private static final int[] DIFFS = {10, 12, 14, 16};
        private static final int HELP = 22;
        private final int island;

        DiffMenu(int id, Inventory inv, SimpleContainer box, ServerPlayer viewer, int island) {
            super(id, inv, box, 3, viewer);
            this.island = island;
            net.minecraft.world.item.Item[] icons = {Items.PRISMARINE_SHARD, Items.PRISMARINE_CRYSTALS, Items.NAUTILUS_SHELL, Items.HEART_OF_THE_SEA};
            for (int d = 0; d < 4; d++) {
                List<String> lore = new ArrayList<>();
                lore.add("§7Fare (yours, in the Tribute Chests):");
                for (Tribute.Need n : Tribute.NEEDS) {
                    int need = n.amount(d);
                    if (need == 0) continue;
                    int have = Tribute.total(island, viewer.getUUID(), n);
                    lore.add((have >= need ? " §a✔ " : " §c✘ ") + "§f" + need + " " + n.name() + (have >= need ? "" : " §8(" + have + ")"));
                }
                lore.add("§7Rooms: §f" + (SunkenMap.ROOMS[d][0] + SunkenMap.ROOMS[d][1] + SunkenMap.ROOMS[d][2]) + " §7in 3 wings, then the vault");
                lore.add("§7Time: §f" + (TIME_LIMITS[d] / 60) + " minutes §8(more with a crew)");
                lore.add("§7Cash on a clear: §6" + Economy.format(CASH[d]) + " §8(half after 2 clears a day)");
                lore.add("§7Vault: Tide trim §f" + TIDE_CHANCE[d] + "%§7, heart of the sea §f" + HEART_CHANCE[d] + "%" + (CONDUIT_CHANCE[d] > 0 ? "§7, conduit §f" + CONDUIT_CHANCE[d] + "%" : ""));
                lore.add(Tribute.ready(island, viewer.getUUID(), d) ? "§aYour fare is ready - click to set sail!" : "§cPut the missing items in the Tribute Chests");
                box.setItem(DIFFS[d], Stacks.named(new ItemStack(icons[d]), DIFF_COLORS[d] + "§l" + DIFF_NAMES[d], lore.toArray(new String[0])));
            }
            box.setItem(HELP, Stacks.named(new ItemStack(Items.BOOK), "§fHow it works", "§7Click for the rules in chat."));
            frame(Stacks.named(new ItemStack(Items.STAINED_GLASS_PANE.pick(DyeColor.CYAN)), " "));
            broadcastChanges();
        }

        @Override
        protected void onClick(int slot, int button, boolean shift) {
            if (slot == HELP) {
                viewer.closeContainer();
                Lobby.later(viewer, () -> help(viewer));
                return;
            }
            for (int d = 0; d < 4; d++) if (slot == DIFFS[d]) {
                final int dd = d;
                viewer.closeContainer();
                Lobby.later(viewer, () -> call(viewer, dd));
            }
        }
    }

    private static boolean busy(ServerPlayer p) {
        if (runOf(p.getUUID()) != null || musterOf(p.getUUID()) != null) {
            Lobby.msg(p, "§7You're already on a Sunken Monument crew.");
            return true;
        }
        if (MUSTERS.size() + RUNS.size() >= MAX_RUNS) {
            Lobby.msg(p, "§cWren's diving crew is busy (" + MAX_RUNS + " trips at once). §7Try again soon.");
            return true;
        }
        return false;
    }

    private static boolean fareReady(ServerPlayer p, int island, int d) {
        List<String> miss = Tribute.missing(island, p.getUUID(), d);
        if (miss.isEmpty()) return true;
        Lobby.msg(p, "§6[Captain Wren] §fThe fare's short, captain! §7Put these in the ship room's Tribute Chests:");
        for (String m : miss) Lobby.msg(p, " §c" + m);
        return false;
    }

    static void call(ServerPlayer p, int d) {
        Islands.Island is = Islands.islandOf(p.getUUID());
        if (is == null || !Islands.isIsland(p.level())) {
            Lobby.msg(p, "§cTalk to Captain Wren on your own island to start a Sunken Monument trip.");
            return;
        }
        if (!WrenRoom.inStaging(is, p.getX(), p.getY(), p.getZ())) {
            Lobby.msg(p, "§cStand in Captain Wren's ship room to set sail. §7(/is shiproom)");
            return;
        }
        if (busy(p) || !fareReady(p, is.id, d)) return;
        Muster mu = new Muster(p.getUUID(), is.id, d);
        mu.deadline = ticks + MUSTER_SECONDS * 20L;
        MUSTERS.add(mu);
        Lobby.msg(p, "§6[Captain Wren] §fTo the " + DIFF_COLORS[d] + DIFF_NAMES[d] + " §fSunken Monument! §7Your crew has §f" + MUSTER_SECONDS
                + " seconds §7to join (up to " + (CREW_MAX - 1) + ") - they need their fare in §fyour §7Tribute Chests and to stand in your ship room.");
        p.sendSystemMessage(Component.literal("§7").append(Expedition.button("§a[Sail now]", "/sunken go", "Leave without waiting")));
        for (ServerPlayer m : eligible(p)) {
            MutableComponent line = Component.literal("§b" + p.getName().getString() + " §7is diving to the " + DIFF_COLORS[d] + DIFF_NAMES[d] + " §b§lSunken Monument§7! Join in " + MUSTER_SECONDS + "s: ");
            line.append(Expedition.button("§a[Join]", "/sunken join", "Your fare must be in their Tribute Chests, and you in their ship room"));
            if (!is.isMember(m.getUUID()))
                line.append(Component.literal(" ")).append(Expedition.button("§e[Go to their ship room]", "/is shiproom visit " + is.ownerName, "Put your fare in their Tribute Chests"));
            m.sendSystemMessage(line);
        }
    }

    /** Island members and faction mates of the leader who are on the island world right now. */
    private static List<ServerPlayer> eligible(ServerPlayer leader) {
        List<ServerPlayer> out = new ArrayList<>();
        Islands.Island is = Islands.islandOf(leader.getUUID());
        for (ServerPlayer m : server.getPlayerList().getPlayers()) {
            if (m == leader || m instanceof BotPlayer || !Islands.isIsland(m.level())) continue;
            Islands.Island mi = Islands.islandOf(m.getUUID());
            if ((is != null && mi != null && mi.id == is.id) || Expedition.sameFaction(leader.getUUID(), m.getUUID())) out.add(m);
        }
        return out;
    }

    static void join(ServerPlayer p) {
        if (musterOf(p.getUUID()) != null || runOf(p.getUUID()) != null) {
            Lobby.msg(p, "§7You're already in a crew.");
            return;
        }
        Muster mu = null;
        for (Muster m : MUSTERS) {
            ServerPlayer leader = server.getPlayerList().getPlayer(m.leader);
            if (leader != null && eligible(leader).contains(p)) {
                mu = m;
                break;
            }
        }
        if (mu == null) {
            Lobby.msg(p, "§7No crew of your island or faction is diving to the Sunken Monument right now.");
            return;
        }
        Islands.Island host = Expedition.islandById(mu.islandId);
        if (host == null || !WrenRoom.inStaging(host, p.getX(), p.getY(), p.getZ())) {
            p.sendSystemMessage(Component.literal("§cStand in the captain's ship room to join. ")
                    .append(Expedition.button("§e[Go there]", "/is shiproom visit " + (host == null ? "" : host.ownerName), "To their ship room")));
            return;
        }
        if (mu.joined.size() + 1 >= CREW_MAX) {
            Lobby.msg(p, "§cThe crew is full (" + CREW_MAX + " at most).");
            return;
        }
        if (!fareReady(p, mu.islandId, mu.diff)) return;
        mu.joined.add(p.getUUID());
        for (UUID id : crewOf(mu)) {
            ServerPlayer m = server.getPlayerList().getPlayer(id);
            if (m != null) Lobby.msg(m, "§a" + p.getName().getString() + " joined the Sunken Monument crew §7(" + (mu.joined.size() + 1) + "/" + CREW_MAX + ").");
        }
        if (mu.joined.size() + 1 >= CREW_MAX) depart(mu);
    }

    private static List<UUID> crewOf(Muster mu) {
        List<UUID> out = new ArrayList<>();
        out.add(mu.leader);
        out.addAll(mu.joined);
        return out;
    }

    static void goNow(ServerPlayer p) {
        Muster mu = musterOf(p.getUUID());
        if (mu == null || !mu.leader.equals(p.getUUID())) {
            Lobby.msg(p, "§7Only the captain who started the trip can set sail early.");
            return;
        }
        depart(mu);
    }

    /** Everyone still in the host's ship room with their fare goes; Wren takes the fare now. */
    private static void depart(Muster mu) {
        if (!MUSTERS.remove(mu)) return;
        ServerLevel l = level();
        Islands.Island host = Expedition.islandById(mu.islandId);
        if (l == null || host == null) {
            for (UUID id : crewOf(mu)) {
                ServerPlayer m = server.getPlayerList().getPlayer(id);
                if (m != null) Lobby.msg(m, "§cThe Sunken Monument world didn't load - tell an admin. §7Nothing was taken.");
            }
            return;
        }
        Run run = new Run(runCounter++, mu.diff, mu.islandId, mu.leader);
        for (UUID id : crewOf(mu)) {
            ServerPlayer m = server.getPlayerList().getPlayer(id);
            if (m == null || !Islands.isIsland(m.level()) || !WrenRoom.inStaging(host, m.getX(), m.getY(), m.getZ())) {
                if (m != null) Lobby.msg(m, "§7You weren't in the ship room when the crew left - you stay behind (your fare wasn't taken).");
                continue;
            }
            Map<String, Integer> fare = Tribute.take(mu.islandId, id, mu.diff);
            if (fare == null) {
                Lobby.msg(m, "§cYour fare isn't all in the Tribute Chests any more - you stay behind.");
                continue;
            }
            run.crew.add(id);
            run.fares.put(id, fare);
        }
        if (run.crew.isEmpty()) return;
        run.crewSize = Math.min(CREW_MAX, run.crew.size());
        BlockPos center = new BlockPos(2_000 + (run.id % 16) * 300, SunkenMap.FLOOR_Y, 2_000 + (run.id / 16) * 300);
        run.layout = SunkenMap.plan(l, center, run.diff, RNG);
        for (SunkenMap.Current c : run.layout.currents) c.trap = RNG.nextInt(100) < 30;
        run.timeLimit = (int) Math.round(TIME_LIMITS[run.diff] * TIME_MULT[run.crewSize - 1]);
        RUNS.add(run);
        Skyserver.LOGGER.info("[Sunken] trip #{} ({}, crew of {}): {} blocks to build", run.id, DIFF_NAMES[run.diff], run.crew.size(), run.layout.builder.order.length);
        for (UUID id : run.crew) {
            ServerPlayer m = server.getPlayerList().getPlayer(id);
            if (m != null) Announce.title(m, "§b§lTHE SUNKEN MONUMENT", "§7Wren is taking your fare and charting a course...", 5, 60, 10);
        }
    }

    /** The monument is built: the crew lands in Wren's dock. */
    private static void arrive(Run run) {
        ServerLevel l = level();
        run.building = false;
        run.startTick = ticks;
        run.nextToll = ticks + BELL_TOLL_SECONDS * 20L;
        run.nextInk = ticks + INK_EVERY * 20L;
        double[] w = run.layout.wren;
        Villager v = EntityTypes.VILLAGER.create(l, EntitySpawnReason.COMMAND);
        if (v != null) {
            v.snapTo(w[0], w[1], w[2], (float) w[3], 0f);
            v.setYHeadRot((float) w[3]);
            v.setNoAi(true);
            v.setPermanentlyInvulnerable(true);
            v.setSilent(true);
            v.setPersistenceRequired();
            v.setCustomName(Component.literal("§6Captain Wren §7(right-click)"));
            v.setCustomNameVisible(true);
            v.addTag(WREN_TAG);
            run.wren = v.getUUID();
            l.addFreshEntity(v);
        }
        Islands.Island host = Expedition.islandById(run.islandId);
        double[] at = run.layout.landing;
        for (UUID id : run.crew) {
            ServerPlayer p = server.getPlayerList().getPlayer(id);
            if (p == null || (!run.test && !Islands.isIsland(p.level()))) {
                if (host != null && !run.test) Tribute.giveBack(run.islandId, id, run.fares.remove(id));
                if (p != null) Lobby.msg(p, "§7You weren't on your island when the crew dived - your fare is back in the Tribute Chests.");
                continue;
            }
            p.teleportTo(l, at[0], at[1], at[2], Set.of(), (float) at[3], 0f, true);
            p.resetFallDistance();
            p.addEffect(new MobEffectInstance(MobEffects.WATER_BREATHING, START_BREATH_TICKS, 0));
            run.inside.add(id);
            Announce.title(p, "§b§lTHE SUNKEN MONUMENT", "§7Wren's Water Breathing lasts one minute - make it count", 10, 70, 20);
        }
        if (run.inside.isEmpty()) {
            endRun(run, "nobody boarded");
            return;
        }
        run.crewSize = Math.max(1, Math.min(CREW_MAX, run.inside.size()));
        if (run.test) {
            run.crewSize = run.testCrew;
            for (ServerPlayer p : inside(run)) testHelp(p);
        }
        for (ServerPlayer p : inside(run)) help(p);
        offerGates(run);
        Skyserver.LOGGER.info("[Sunken] trip #{} started with {} player(s)", run.id, run.inside.size());
    }

    static void help(ServerPlayer p) {
        Lobby.msg(p, "§b§lSUNKEN MONUMENT §7- open the §ffloodgates §7one wing at a time (ask Wren), drain and clear every room, then push §6Wren's diving bell §7to the vault and beat the §lTidemother§7.");
        Lobby.msg(p, "§7§lRising Tide: §7right-click a room's §fpump §7(sticky piston) to drain some water. Surges open §fleaks§7: crouch next to one for 3 seconds to plug it. You get §f2 sponges §7in each room.");
        Lobby.msg(p, "§7§lCurrents §7above each corridor rush you back to the hub (some are traps). The §lSunken Bell §7enrages the guards every 2 minutes; §lInk Blackouts §7blind you for 5 seconds every 45.");
        Lobby.msg(p, "§7Wren's Water Breathing only lasts the first minute - bring potions, a turtle shell or Respiration. Near the diving bell you can breathe. §c§lDying loses everything you carry §7(a §bRecovery Charm §7saves it).");
    }

    static void status(ServerPlayer p) {
        Run run = runOf(p.getUUID());
        if (run == null) {
            Lobby.msg(p, "§b§lSUNKEN MONUMENT EXPEDITIONS §7- paid in farm items (the Tribute Chests in Captain Wren's ship room). Talk to Wren to start one.");
            Lobby.msg(p, "§7Cash on a clear: " + DIFF_COLORS[0] + "Easy " + Economy.format(CASH[0]) + "§7, " + DIFF_COLORS[1] + "Normal " + Economy.format(CASH[1]) + "§7, "
                    + DIFF_COLORS[2] + "Hard " + Economy.format(CASH[2]) + "§7, " + DIFF_COLORS[3] + "Nightmare " + Economy.format(CASH[3]) + " §8(each player's first 2 clears a day; half after)");
            return;
        }
        long left = Math.max(0, run.timeLimit - (ticks - run.startTick) / 20);
        int done = 0, total = run.layout.rooms.size();
        for (SunkenMap.Room r : run.layout.rooms) if (r.cleared) done++;
        Lobby.msg(p, "§b" + DIFF_NAMES[run.diff] + " Sunken Monument §7- rooms " + done + "/" + total + (run.vaultOpen ? ", §6the vault gate is open" : "")
                + " §7(" + (left / 60) + ":" + String.format("%02d", left % 60) + " left)");
    }

    static void talk(ServerPlayer p) {
        Run run = runOf(p.getUUID());
        if (run == null) return;
        if (run.openWing < 0 && !run.vaultOpen) {
            offerGates(run, p);
            return;
        }
        p.sendSystemMessage(Component.literal("§6[Captain Wren] §f" + (run.vaultOpen ? "The vault gate's open - down the shaft, push my bell! " : "Clear the " + SunkenMap.WING_SHORT[run.openWing] + " wing, captain. ") + "§7")
                .append(Expedition.button("§c[Go home now - keep what you carry, no reward]", "/sunken home", "Leave the monument early")));
    }

    // =====================================================================
    // Floodgates
    // =====================================================================

    private static void offerGates(Run run) {
        for (ServerPlayer p : inside(run)) offerGates(run, p);
    }

    private static void offerGates(Run run, ServerPlayer p) {
        MutableComponent line = Component.literal("§6[Captain Wren] §fWhich floodgate first, captain? §7");
        boolean any = false;
        for (int w = 0; w < 3; w++) {
            if (run.wingDone[w]) continue;
            any = true;
            String tip = SunkenMap.WING_NAMES[w] + " - " + run.layout.wingRooms(w).size() + " room(s). " + (w == 0 ? "More guardians; prismarine and sea lanterns."
                    : w == 1 ? "More drowned; nautilus shells." : "Guardians and drowned; more enchanted books.")
                    + (run.firstWing < 0 ? " The first wing's chests give a bonus roll." : "");
            line.append(Expedition.button("§b[" + SunkenMap.WING_SHORT[w] + "]", "/sunken gate " + w, tip)).append(Component.literal(" "));
        }
        if (any) p.sendSystemMessage(line);
    }

    static void openGate(ServerPlayer p, int wing) {
        Run run = runOf(p.getUUID());
        if (run == null || run.layout == null || run.building) {
            Lobby.msg(p, "§7You're not in the Sunken Monument.");
            return;
        }
        if (run.openWing >= 0) {
            Lobby.msg(p, "§7Finish the " + SunkenMap.WING_SHORT[run.openWing] + " wing first.");
            return;
        }
        if (run.wingDone[wing]) {
            Lobby.msg(p, "§7That wing is already cleared.");
            return;
        }
        run.openWing = wing;
        if (run.firstWing < 0) run.firstWing = wing;
        ServerLevel l = level();
        for (BlockPos g : run.layout.gates.get(wing)) {
            l.setBlock(g, Blocks.WATER.defaultBlockState(), Block.UPDATE_ALL);
            run.layout.blocks.remove(g.asLong());
        }
        BlockPos g0 = run.layout.gates.get(wing).get(0);
        l.playSound(null, g0, SoundEvents.IRON_DOOR_OPEN, SoundSource.BLOCKS, 2f, 0.6f);
        for (ServerPlayer m : inside(run)) Announce.title(m, "§b§lFLOODGATE OPEN", "§7" + SunkenMap.WING_NAMES[wing], 5, 50, 10);
    }

    // =====================================================================
    // Ticking
    // =====================================================================

    private static void fastTick() {
        for (Muster m : new ArrayList<>(MUSTERS)) if (ticks >= m.deadline) depart(m);
        for (Run run : new ArrayList<>(RUNS)) {
            if (run.building) {
                if (run.layout.builder.tick()) arrive(run);
                else if (ticks % 20 == 0) for (UUID id : run.crew) {
                    ServerPlayer m = server.getPlayerList().getPlayer(id);
                    if (m != null) Announce.actionBar(m, "§bCharting the course... §f" + Math.round(run.layout.builder.progress() * 100) + "%");
                }
                continue;
            }
            if (run.over) continue;
            currents(run);
            plugging(run);
            if (run.beamAt > 0 && ticks >= run.beamAt) beam(run);
        }
        tickDowned();
    }

    private static void tick(Run run) {
        if (run.over || run.building) return;
        ServerLevel l = level();
        if (l == null) return;
        long left = run.timeLimit - (ticks - run.startTick) / 20;
        if (left <= 0) {
            for (ServerPlayer p : inside(run)) extract(p, "§6[Captain Wren] §fTime's up! I'm hauling you up - no clear this time.");
            endRun(run, "time ran out");
            return;
        }
        if (left <= 300 && run.warned < 1 || left <= 60 && run.warned < 2) {
            run.warned = left <= 60 ? 2 : 1;
            for (ServerPlayer m : inside(run)) Lobby.msg(m, "§e§l" + (left <= 60 ? "1 MINUTE" : "5 MINUTES") + " LEFT! §7Wren hauls everyone up when time runs out (you keep what you carry).");
        }
        // No Mining Fatigue on this trip (the Tidemother and guardians give it).
        for (ServerPlayer p : inside(run)) if (p.hasEffect(MobEffects.MINING_FATIGUE)) p.removeEffect(MobEffects.MINING_FATIGUE);
        rooms(l, run);
        bellAndInk(l, run);
        if (run.vaultOpen && !run.bossUp) divingBell(l, run);
        if (run.bossUp) bossTick(l, run);
        if (run.bossUp || run.vaultOpen) {
            if (run.bellHp > 0 && run.bar == null) {
                run.bar = new ServerBossEvent(UUID.randomUUID(), Component.literal("§6Wren's Diving Bell"), BossEvent.BossBarColor.YELLOW, BossEvent.BossBarOverlay.NOTCHED_10);
                for (ServerPlayer p : inside(run)) run.bar.addPlayer(p);
            }
            if (run.bar != null) run.bar.setProgress((float) Math.max(0, Math.min(1, run.bellHp / run.bellMax)));
        }
        keepGuardsHome(l, run);
        if (ticks % 100 == 0) for (ServerPlayer m : inside(run)) {
            int done = 0;
            for (SunkenMap.Room r : run.layout.rooms) if (r.cleared) done++;
            Announce.actionBar(m, "§bSunken Monument §8| §f" + (left / 60) + ":" + String.format("%02d", left % 60) + " §8| §7rooms " + done + "/" + run.layout.rooms.size());
        }
    }

    // ---- Rooms: waves, the Rising Tide, leaks ----

    private static void rooms(ServerLevel l, Run run) {
        if (run.openWing < 0) return;
        boolean wingDone = true;
        for (SunkenMap.Room r : run.layout.rooms) {
            if (r.wing != run.openWing) continue;
            if (!r.cleared) wingDone = false;
            if (r.cleared) continue;
            List<ServerPlayer> in = new ArrayList<>();
            for (ServerPlayer p : inside(run)) if (r.inside(p.getX(), p.getY(), p.getZ(), 0)) in.add(p);
            if (!r.active) {
                if (in.isEmpty()) continue;
                r.active = true;
                r.nextLeak = ticks + LEAK_SECONDS * 20L;
                spawnWave(l, run, r);
            }
            // Sponges for everyone who walks in.
            for (ServerPlayer p : in) if (r.sponged.add(p.getUUID())) {
                EndRewards.give(p, sponge(new ItemStack(Items.SPONGE, 2)));
                Lobby.msg(p, "§7Wren's crew left §f2 sponges §7here for you §8(they stay in the monument)§7.");
            }
            r.guards.removeIf(id -> {
                Entity e = l.getEntity(id);
                return e == null || !e.isAlive();
            });
            if (r.guards.isEmpty() && r.wave >= 2) {
                roomCleared(l, run, r);
                continue;
            }
            if (r.wave < 2 && (r.guards.isEmpty() || ticks >= r.nextWave)) spawnWave(l, run, r);
            // Rising Tide.
            double rate = FLOOD[run.diff] * (1 + CREW_FLOOD * (run.crewSize - 1)) * (1 + LEAK_FLOOD * r.leaks.size());
            r.level = Math.min(SunkenMap.ROOM_HEIGHT, r.level + rate);
            SunkenMap.applyLevel(l, r, (int) Math.ceil(r.level - 0.001));
            if (ticks >= r.nextLeak && r.leaks.size() < MAX_LEAKS && r.level < SunkenMap.ROOM_HEIGHT - 0.5) {
                r.nextLeak = ticks + LEAK_SECONDS * 20L;
                openLeak(l, run, r);
            }
            for (ServerPlayer p : in) {
                int pct = (int) Math.round(100 * r.level / SunkenMap.ROOM_HEIGHT);
                Announce.actionBar(p, "§bWater: §f" + pct + "% §8| " + (r.leaks.isEmpty() ? "§7no leaks" : "§c" + r.leaks.size() + " leak" + (r.leaks.size() == 1 ? "" : "s") + " (crouch next to it)")
                        + " §8| §7guards §f" + r.guards.size());
            }
            for (long lk : r.leaks) {
                BlockPos lp = BlockPos.of(lk);
                l.sendParticles(ParticleTypes.SPLASH, lp.getX() + 0.5, lp.getY() + 0.5, lp.getZ() + 0.5, 12, 0.3, 0.3, 0.3, 0.1);
            }
        }
        if (wingDone) {
            int w = run.openWing;
            run.wingDone[w] = true;
            run.openWing = -1;
            boolean all = run.wingDone[0] && run.wingDone[1] && run.wingDone[2];
            for (ServerPlayer m : inside(run)) Announce.title(m, "§a§lWING CLEARED", "§7" + SunkenMap.WING_NAMES[w], 5, 50, 10);
            if (all) openVault(l, run);
            else offerGates(run);
        }
    }

    private static void spawnWave(ServerLevel l, Run run, SunkenMap.Room r) {
        int n = WAVES[run.diff][Math.min(1, r.wave)] + (run.crewSize - 1);
        for (int i = 0; i < n; i++) {
            boolean guardian = switch (r.wing) {
                case 0 -> RNG.nextInt(100) < 60;
                case 1 -> RNG.nextInt(100) < 25;
                default -> RNG.nextBoolean();
            };
            int[] b = r.box;
            BlockPos at = new BlockPos(b[0] + 1 + RNG.nextInt(b[3] - b[0] - 1), b[1] + 1 + RNG.nextInt(3), b[2] + 1 + RNG.nextInt(b[5] - b[2] - 1));
            Mob m = spawnGuard(l, run, guardian ? EntityTypes.GUARDIAN : EntityTypes.DROWNED, at, r);
            if (m != null) r.guards.add(m.getUUID());
        }
        r.wave++;
        r.nextWave = ticks + WAVE_SECONDS * 20L;
        if (r.wave == 1) for (ServerPlayer p : inside(run))
            if (r.inside(p.getX(), p.getY(), p.getZ(), 1)) Announce.title(p, "§c§lGUARDS!", "§7Drain the room with the pump - beat them all", 5, 40, 10);
    }

    private static Mob spawnGuard(ServerLevel l, Run run, EntityType<? extends Mob> type, BlockPos at, SunkenMap.Room room) {
        Mob m = type.create(l, EntitySpawnReason.EVENT);
        if (m == null) return null;
        m.snapTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, RNG.nextFloat() * 360f, 0f);
        m.finalizeSpawn(l, l.getCurrentDifficultyAt(at), EntitySpawnReason.EVENT, null);
        double hp = HEALTH[run.diff] + CREW_HEALTH * (run.crewSize - 1), dmg = DAMAGE[run.diff] + CREW_DAMAGE * (run.crewSize - 1);
        boost(m.getAttribute(Attributes.MAX_HEALTH), "sunken_health", hp);
        boost(m.getAttribute(Attributes.ATTACK_DAMAGE), "sunken_damage", dmg);
        m.setHealth(m.getMaxHealth());
        m.setPersistenceRequired();
        for (EquipmentSlot s : EquipmentSlot.values()) m.setDropChance(s, 0f);
        if (type == EntityTypes.DROWNED && run.diff >= 2 && RNG.nextInt(100) < 25) m.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.TRIDENT));
        m.addTag(GUARD_TAG);
        GUARD_OF.put(m.getUUID(), new Object[]{run, room});
        ServerPlayer target = nearest(run, at);
        if (target != null) m.setTarget(target);
        l.addFreshEntity(m);
        return m;
    }

    private static void boost(AttributeInstance a, String id, double amount) {
        if (a == null || amount <= 0) return;
        a.addPermanentModifier(new AttributeModifier(Identifier.fromNamespaceAndPath(Skyserver.MOD_ID, id), amount, AttributeModifier.Operation.ADD_MULTIPLIED_BASE));
    }

    private static ServerPlayer nearest(Run run, BlockPos at) {
        ServerPlayer best = null;
        double bd = Double.MAX_VALUE;
        for (ServerPlayer p : inside(run)) {
            if (DOWNED.containsKey(p.getUUID())) continue;
            double d = p.distanceToSqr(at.getX(), at.getY(), at.getZ());
            if (d < bd) {
                bd = d;
                best = p;
            }
        }
        return best;
    }

    /** Guards that wander far from their room come back to it. */
    private static void keepGuardsHome(ServerLevel l, Run run) {
        for (SunkenMap.Room r : run.layout.rooms) {
            if (r.guards.isEmpty()) continue;
            for (UUID id : r.guards) {
                Entity e = l.getEntity(id);
                if (e == null || r.inside(e.getX(), e.getY(), e.getZ(), 6)) continue;
                e.teleportTo((r.box[0] + r.box[3]) / 2.0 + 0.5, r.box[1] + 1, (r.box[2] + r.box[5]) / 2.0 + 0.5);
            }
        }
    }

    private static void pump(ServerPlayer p, Run run, SunkenMap.Room r) {
        if (DOWNED.containsKey(p.getUUID()) || r.cleared) {
            if (r.cleared) tip(p, "§7This room is already drained.");
            return;
        }
        Long last = run.pumpUsed.get(p.getUUID());
        if (last != null && ticks - last < PUMP_COOLDOWN) return;
        run.pumpUsed.put(p.getUUID(), ticks);
        r.level = Math.max(0, r.level - PUMP);
        SunkenMap.applyLevel(level(), r, (int) Math.ceil(r.level - 0.001));
        p.level().playSound(null, r.pump, SoundEvents.PISTON_EXTEND, SoundSource.BLOCKS, 0.6f, 0.7f + RNG.nextFloat() * 0.3f);
        ((ServerLevel) p.level()).sendParticles(ParticleTypes.BUBBLE_COLUMN_UP, r.pump.getX() + 0.5, r.pump.getY() + 1.2, r.pump.getZ() + 0.5, 8, 0.2, 0.3, 0.2, 0.05);
        tip(p, "§bPumping... water §f" + Math.round(100 * r.level / SunkenMap.ROOM_HEIGHT) + "%");
    }

    private static void openLeak(ServerLevel l, Run run, SunkenMap.Room r) {
        List<BlockPos> free = new ArrayList<>();
        for (BlockPos s : r.leakSpots) if (!r.leaks.contains(s.asLong())) free.add(s);
        if (free.isEmpty()) return;
        BlockPos s = free.get(RNG.nextInt(free.size()));
        r.leaks.add(s.asLong());
        l.setBlock(s, Blocks.WATER.defaultBlockState(), Block.UPDATE_ALL);
        l.playSound(null, s, SoundEvents.BUCKET_EMPTY, SoundSource.BLOCKS, 1.5f, 0.6f);
        for (ServerPlayer p : inside(run)) if (r.inside(p.getX(), p.getY(), p.getZ(), 1)) Announce.title(p, "", "§c§lA LEAK! §7Crouch next to it for 3 seconds", 0, 30, 10);
    }

    /** Crouching next to a leak for PLUG_TICKS plugs it. */
    private static void plugging(Run run) {
        if (run.openWing < 0) return;
        for (ServerPlayer p : inside(run)) {
            if (!p.isShiftKeyDown() || DOWNED.containsKey(p.getUUID())) {
                run.plugging.remove(p.getUUID());
                continue;
            }
            SunkenMap.Room room = null;
            BlockPos leak = null;
            for (SunkenMap.Room r : run.layout.rooms) {
                if (r.leaks.isEmpty() || !r.inside(p.getX(), p.getY(), p.getZ(), 0)) continue;
                for (long lk : r.leaks) {
                    BlockPos lp = BlockPos.of(lk);
                    if (p.distanceToSqr(lp.getX() + 0.5, lp.getY() + 0.5, lp.getZ() + 0.5) <= 3.5 * 3.5) {
                        room = r;
                        leak = lp;
                        break;
                    }
                }
                if (leak != null) break;
            }
            if (leak == null) {
                run.plugging.remove(p.getUUID());
                continue;
            }
            int t = run.plugging.merge(p.getUUID(), 1, Integer::sum);
            if (t % 5 == 0) Announce.actionBar(p, "§bPlugging the leak... §f" + Math.min(100, t * 100 / PLUG_TICKS) + "%");
            if (t >= PLUG_TICKS) {
                run.plugging.remove(p.getUUID());
                room.leaks.remove(leak.asLong());
                ServerLevel l = level();
                boolean wet = leak.getY() - room.box[1] < room.placedLevel;
                l.setBlock(leak, wet ? Blocks.WATER.defaultBlockState() : Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                l.playSound(null, leak, SoundEvents.WOOD_PLACE, SoundSource.BLOCKS, 1f, 0.8f);
                tip(p, "§aLeak plugged!");
            }
        }
    }

    private static void roomCleared(ServerLevel l, Run run, SunkenMap.Room r) {
        r.cleared = true;
        for (long lk : r.leaks) l.setBlock(BlockPos.of(lk), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        r.leaks.clear();
        r.level = 0;
        SunkenMap.applyLevel(l, r, 0);
        l.playSound(null, r.chest, SoundEvents.CHEST_OPEN, SoundSource.BLOCKS, 1f, 1f);
        for (ServerPlayer p : inside(run)) {
            if (r.inside(p.getX(), p.getY(), p.getZ(), 2)) Announce.title(p, "§a§lROOM CLEARED", "§7The water drains away - open the chest", 5, 40, 10);
            else Lobby.msg(p, "§aA room in the " + SunkenMap.WING_SHORT[r.wing] + " wing was cleared!");
        }
    }

    private static void openChest(ServerPlayer p, Run run, SunkenMap.Room r) {
        if (DOWNED.containsKey(p.getUUID())) return;
        if (!r.cleared) {
            tip(p, "§cBeat this room's guards first.");
            return;
        }
        if (!r.looted.add(p.getUUID())) {
            tip(p, "§7You've already emptied this chest.");
            return;
        }
        List<ItemStack> loot = roomLoot((ServerLevel) p.level(), run.diff, r.wing, r.wing == run.firstWing);
        StringBuilder sb = new StringBuilder();
        for (ItemStack s : loot) {
            if (sb.length() > 0) sb.append("§7, ");
            sb.append("§f").append(s.getCount() > 1 ? s.getCount() + " " : "").append(s.getHoverName().getString());
            EndRewards.give(p, s);
        }
        Lobby.msg(p, "§bChest: " + (sb.length() == 0 ? "§7just seaweed" : sb.toString()));
    }

    private static boolean roll(int pct) {
        return RNG.nextInt(100) < pct;
    }

    /** What ONE player finds in ONE room chest. The first wing opened gets one bonus roll of its special. */
    static List<ItemStack> roomLoot(ServerLevel level, int d, int wing, boolean bonus) {
        List<ItemStack> out = new ArrayList<>();
        out.add(new ItemStack(Items.PRISMARINE_SHARD, 4 + RNG.nextInt(7) + (wing == 0 ? 4 : 0)));
        out.add(new ItemStack(Items.PRISMARINE_CRYSTALS, 3 + RNG.nextInt(6)));
        if (roll(wing == 0 ? 70 : 40)) out.add(new ItemStack(Items.SEA_LANTERN, 1 + RNG.nextInt(3)));
        if (roll(new int[]{25, 30, 35, 40}[d] + (wing == 1 ? 25 : 0))) out.add(new ItemStack(Items.NAUTILUS_SHELL));
        if (roll(30)) out.add(new ItemStack(Items.GLOW_INK_SAC, 2 + RNG.nextInt(4)));
        if (roll(new int[]{10, 15, 20, 25}[d])) out.add(new ItemStack(Items.TURTLE_SCUTE, 1 + RNG.nextInt(2)));
        if (roll(new int[]{30, 40, 50, 60}[d])) out.add(new ItemStack(Items.GOLDEN_APPLE));
        if (roll(new int[]{50, 60, 70, 80}[d])) out.add(new ItemStack(Items.EXPERIENCE_BOTTLE, 2 + RNG.nextInt(4)));
        if (roll(new int[]{20, 30, 40, 50}[d])) out.add(new ItemStack(Items.DIAMOND, 1 + RNG.nextInt(2)));
        double books = wing == 2 ? 1.5 : 1.0;
        if (roll((int) (new int[]{10, 14, 18, 24}[d] * books))) out.add(EndRewards.book(level.getServer(), Enchantments.DEPTH_STRIDER, Math.min(3, 1 + d / 2 + RNG.nextInt(2))));
        if (roll((int) (new int[]{8, 12, 16, 20}[d] * books))) out.add(EndRewards.book(level.getServer(), Enchantments.RESPIRATION, Math.min(3, 1 + d / 2 + RNG.nextInt(2))));
        if (roll((int) (new int[]{6, 9, 12, 15}[d] * books))) out.add(EndRewards.book(level.getServer(), Enchantments.AQUA_AFFINITY, 1));
        if (bonus) {
            if (wing == 0) out.add(new ItemStack(Items.SEA_LANTERN, 2 + RNG.nextInt(3)));
            else if (wing == 1) out.add(new ItemStack(Items.NAUTILUS_SHELL));
            else out.add(EndRewards.book(level.getServer(), RNG.nextBoolean() ? Enchantments.DEPTH_STRIDER : Enchantments.RESPIRATION, Math.min(3, 2 + d / 2)));
        }
        return out;
    }

    // ---- Currents, the Sunken Bell, ink ----

    private static void currents(Run run) {
        if (run.layout == null) return;
        ServerLevel l = level();
        for (ServerPlayer p : inside(run)) {
            if (DOWNED.containsKey(p.getUUID())) continue;
            for (SunkenMap.Current c : run.layout.currents) {
                if (!run.wingDone[c.wing] && run.openWing != c.wing) continue;
                if (!c.inside(p.getX(), p.getY(), p.getZ())) continue;
                if (c.trap && c.atExit(p.getX(), p.getZ(), run.layout.center.getX(), run.layout.center.getZ())) {
                    double[] pl = run.layout.pitLanding;
                    p.teleportTo(l, pl[0], pl[1], pl[2], Set.of(), p.getYRot(), p.getXRot(), true);
                    Announce.title(p, "§c§lTRAP CURRENT!", "§7The Guardian Pit - swim up and out!", 5, 50, 10);
                    for (int i = 0; i < 2 + run.diff / 2; i++) {
                        int[] b = run.layout.pit;
                        Mob m = spawnGuard(l, run, EntityTypes.GUARDIAN, new BlockPos(b[0] + 1 + RNG.nextInt(5), b[1] + 1, b[2] + 1 + RNG.nextInt(5)), null);
                        if (m != null) m.setTarget(p);
                    }
                    break;
                }
                p.setDeltaMovement(c.vx * 0.9, 0.0, c.vz * 0.9);
                p.connection.send(new net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket(p));
                if (ticks % 4 == 0) l.sendParticles(ParticleTypes.BUBBLE, p.getX(), p.getY() + 0.5, p.getZ(), 6, 0.4, 0.4, 0.4, 0.05);
                break;
            }
        }
    }

    private static void bellAndInk(ServerLevel l, Run run) {
        if (ticks >= run.nextToll - 60 && ticks < run.nextToll - 40)
            for (ServerPlayer p : inside(run)) p.level().playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.NOTE_BLOCK_CHIME.value(), SoundSource.AMBIENT, 1.5f, 0.6f);
        if (ticks >= run.nextToll) {
            run.nextToll = ticks + BELL_TOLL_SECONDS * 20L;
            run.rageUntil = ticks + BELL_RAGE_SECONDS * 20L;
            for (ServerPlayer p : inside(run)) {
                p.level().playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.BELL_BLOCK, SoundSource.AMBIENT, 2f, 0.5f);
                Announce.title(p, "§3§lTHE SUNKEN BELL TOLLS", "§7The guards are enraged for " + BELL_RAGE_SECONDS + " seconds", 5, 40, 10);
            }
            for (UUID id : new ArrayList<>(GUARD_OF.keySet())) {
                Object[] o = GUARD_OF.get(id);
                if (o == null || o[0] != run) continue;
                if (l.getEntity(id) instanceof Mob m) {
                    m.addEffect(new MobEffectInstance(MobEffects.STRENGTH, BELL_RAGE_SECONDS * 20, 0));
                    m.addEffect(new MobEffectInstance(MobEffects.SPEED, BELL_RAGE_SECONDS * 20, 1));
                }
            }
        }
        if (ticks >= run.nextInk) {
            run.nextInk = ticks + INK_EVERY * 20L;
            for (ServerPlayer p : inside(run)) {
                if (run.layout.inDock(p.getX(), p.getY(), p.getZ()) || inDrainedRoom(run, p)) continue;
                p.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, INK_SECONDS * 20, 0, false, false));
                l.sendParticles(ParticleTypes.SQUID_INK, p.getX(), p.getY() + 1, p.getZ(), 40, 1.5, 1, 1.5, 0.05);
                tip(p, "§8§lINK BLACKOUT");
            }
        }
    }

    private static boolean inDrainedRoom(Run run, ServerPlayer p) {
        for (SunkenMap.Room r : run.layout.rooms) if (r.inside(p.getX(), p.getY(), p.getZ(), 0) && r.placedLevel < SunkenMap.ROOM_HEIGHT) return true;
        return false;
    }

    // ---- The vault gate and the diving bell ----

    private static void openVault(ServerLevel l, Run run) {
        run.vaultOpen = true;
        for (BlockPos g : run.layout.vaultGate) {
            l.setBlock(g, Blocks.WATER.defaultBlockState(), Block.UPDATE_ALL);
            run.layout.blocks.remove(g.asLong());
        }
        run.bellMax = BELL_HP * (1 + 0.25 * (run.crewSize - 1));
        run.bellHp = run.bellMax;
        placeBell(l, run);
        run.nextPassageGuards = ticks + 15 * 20L;
        for (ServerPlayer p : inside(run)) {
            Announce.title(p, "§6§lTHE VAULT GATE IS OPEN", "§7Down the shaft - push Wren's diving bell to the vault", 10, 70, 20);
            Lobby.msg(p, "§6[Captain Wren] §fMy diving bell's at the bottom of the shaft. §7Stand behind it to push it along the passage - you can breathe near it. Keep the guards off it!");
        }
    }

    private static void placeBell(ServerLevel l, Run run) {
        for (BlockPos p : run.bellBlocks) if (!l.getBlockState(p).isAir()) l.setBlock(p, Blocks.WATER.defaultBlockState(), Block.UPDATE_CLIENTS);
        run.bellBlocks.clear();
        BlockPos c = run.layout.bellPos(run.bellU);
        BlockState glass = Blocks.GLASS.defaultBlockState(), copper = Blocks.COPPER_BLOCK.asList().get(0).defaultBlockState();
        BlockState bell = Blocks.BELL.defaultBlockState().setValue(BlockStateProperties.BELL_ATTACHMENT, BellAttachType.CEILING);
        for (int x = -1; x <= 1; x++)
            for (int y = -1; y <= 1; y++)
                for (int z = -1; z <= 1; z++) {
                    BlockPos p = c.offset(x, y, z);
                    int edges = (Math.abs(x) == 1 ? 1 : 0) + (Math.abs(y) == 1 ? 1 : 0) + (Math.abs(z) == 1 ? 1 : 0);
                    BlockState s = x == 0 && y == 0 && z == 0 ? bell : edges >= 2 ? copper : glass;
                    l.setBlock(p, s, Block.UPDATE_CLIENTS);
                    run.bellBlocks.add(p);
                }
    }

    private static void divingBell(ServerLevel l, Run run) {
        BlockPos c = run.layout.bellPos(run.bellU);
        Vec3 bc = Vec3.atCenterOf(c);
        // Breathing near the bell.
        for (ServerPlayer p : inside(run)) if (p.distanceToSqr(bc) <= 5.5 * 5.5) p.addEffect(new MobEffectInstance(MobEffects.WATER_BREATHING, 60, 0, false, false));
        if (run.bellLost) return;
        // Guards gnaw at it.
        int hits = 0;
        for (Mob m : l.getEntitiesOfClass(Mob.class, new AABB(c).inflate(3), m -> m.entityTags().contains(GUARD_TAG))) hits++;
        if (hits > 0) damageBell(l, run, 2.0 * hits);
        if (run.bellLost) return;
        // Pushing: crew behind it (toward the shaft) push it on.
        int pushers = 0;
        for (ServerPlayer p : inside(run)) {
            if (DOWNED.containsKey(p.getUUID()) || !run.layout.inPassage(p.getX(), p.getY(), p.getZ())) continue;
            double behind = p.getZ() - (c.getZ() + 0.5); // the bell moves toward -z
            if (behind > 0.5 && behind <= 6 && Math.abs(p.getX() - bc.x) <= 3.5) pushers++;
        }
        if (pushers > 0 && run.bellU < SunkenMap.BELL_END) {
            run.bellProgress += BELL_PUSH * Math.min(3, pushers);
            int steps = (int) run.bellProgress;
            if (steps > 0) {
                run.bellProgress -= steps;
                run.bellU = Math.min(SunkenMap.BELL_END, run.bellU + steps);
                placeBell(l, run);
                l.playSound(null, c, SoundEvents.PISTON_CONTRACT, SoundSource.BLOCKS, 1f, 0.6f);
            }
        }
        // Guards come out of the dark ahead of it.
        if (ticks >= run.nextPassageGuards && run.bellU < SunkenMap.BELL_END) {
            run.nextPassageGuards = ticks + 15 * 20L;
            for (int i = 0; i < 1 + run.diff / 2 + (run.crewSize - 1); i++) {
                BlockPos at = run.layout.bellPos(Math.min(47, run.bellU + 10 + RNG.nextInt(5))).offset(RNG.nextInt(5) - 2, RNG.nextInt(3) - 1, 0);
                spawnGuard(l, run, RNG.nextBoolean() ? EntityTypes.GUARDIAN : EntityTypes.DROWNED, at, null);
            }
        }
        if (ticks % 40 == 0) for (ServerPlayer p : inside(run)) if (run.layout.inPassage(p.getX(), p.getY(), p.getZ()))
            Announce.actionBar(p, "§6Diving bell: §f" + Math.round(100.0 * (run.bellU - SunkenMap.BELL_START) / (SunkenMap.BELL_END - SunkenMap.BELL_START)) + "% §7of the way"
                    + (pushers == 0 ? " §8(stand behind it to push)" : " §8(" + pushers + " pushing)"));
        if (run.bellU >= SunkenMap.BELL_END) bossRises(l, run);
    }

    private static void damageBell(ServerLevel l, Run run, double dmg) {
        if (run.bellLost || run.bellHp <= 0) return;
        run.bellHp -= dmg;
        if (run.bellHp > 0) return;
        run.bellHp = 0;
        run.bellLost = true;
        BlockPos c = run.layout.bellPos(run.bellU);
        l.playSound(null, c, SoundEvents.GLASS_BREAK, SoundSource.BLOCKS, 2f, 0.5f);
        for (ServerPlayer p : inside(run)) Announce.title(p, "§c§lTHE BELL IS LOST", "§7No vault haul this time - you can still beat the Tidemother", 5, 70, 10);
        if (!run.bossUp) bossRises(l, run); // no bell to push: she comes anyway
    }

    // ---- The Tidemother ----

    private static void bossRises(ServerLevel l, Run run) {
        if (run.bossUp) return;
        run.bossUp = true;
        Mob boss = EntityTypes.ELDER_GUARDIAN.create(l, EntitySpawnReason.EVENT);
        if (boss == null) return;
        BlockPos at = run.layout.bossSpot;
        boss.snapTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 180f, 0f);
        double hp = BOSS_HP[run.diff] * BOSS_CREW[run.crewSize - 1];
        AttributeInstance a = boss.getAttribute(Attributes.MAX_HEALTH);
        if (a != null) a.setBaseValue(hp);
        boss.setHealth((float) hp);
        boss.setPersistenceRequired();
        boss.setCustomName(Component.literal("§3§lThe Tidemother"));
        boss.setCustomNameVisible(true);
        boss.addTag(GUARD_TAG);
        GUARD_OF.put(boss.getUUID(), new Object[]{run, null});
        run.boss = boss.getUUID();
        l.addFreshEntity(boss);
        run.nextBeam = ticks + BEAM_EVERY * 20L;
        run.nextHelpers = ticks + HELPERS_EVERY * 20L;
        for (ServerPlayer p : inside(run)) {
            Announce.title(p, "§3§lTHE TIDEMOTHER RISES", "§7Hide behind the pillars when she charges her beam", 10, 70, 20);
            p.level().playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.ELDER_GUARDIAN_CURSE, SoundSource.HOSTILE, 1f, 0.8f);
        }
    }

    private static void bossTick(ServerLevel l, Run run) {
        Entity e = run.boss == null ? null : l.getEntity(run.boss);
        if (e == null || !e.isAlive()) return;
        // Breathing near the bell still works in the vault.
        if (!run.bellLost) {
            Vec3 bc = Vec3.atCenterOf(run.layout.bellPos(run.bellU));
            for (ServerPlayer p : inside(run)) if (p.distanceToSqr(bc) <= 5.5 * 5.5) p.addEffect(new MobEffectInstance(MobEffects.WATER_BREATHING, 60, 0, false, false));
            int hits = l.getEntitiesOfClass(Mob.class, new AABB(run.layout.bellPos(run.bellU)).inflate(3), m -> m.entityTags().contains(GUARD_TAG) && m != e).size();
            if (hits > 0) damageBell(l, run, 2.0 * hits);
        }
        if (ticks >= run.nextBeam && run.beamAt < 0) {
            run.nextBeam = ticks + BEAM_EVERY * 20L;
            run.beamAt = ticks + BEAM_CHARGE * 20L;
            for (ServerPlayer p : inside(run)) {
                tip(p, "§c§lThe Tidemother is charging her beam - get behind a pillar!");
                p.level().playSound(null, e.getX(), e.getY(), e.getZ(), SoundEvents.GUARDIAN_ATTACK, SoundSource.HOSTILE, 2f, 0.5f);
            }
        }
        if (ticks >= run.nextHelpers) {
            run.nextHelpers = ticks + HELPERS_EVERY * 20L;
            for (int i = 0; i < 2; i++) spawnGuard(l, run, EntityTypes.GUARDIAN, BlockPos.containing(e.getX() + RNG.nextInt(7) - 3, e.getY(), e.getZ() + RNG.nextInt(7) - 3), null);
        }
        if (run.bar != null) run.bar.setName(Component.literal("§6Wren's Diving Bell §8| §3The Tidemother §f" + Math.round(((Mob) e).getHealth()) + "❤"));
    }

    /** The charged beam: everyone in the vault she can see takes the hit. The bell too, if she can see it. */
    private static void beam(Run run) {
        run.beamAt = -1;
        ServerLevel l = level();
        Entity e = run.boss == null ? null : l.getEntity(run.boss);
        if (e == null || !e.isAlive()) return;
        Vec3 eye = e.getEyePosition();
        for (ServerPlayer p : inside(run)) {
            if (DOWNED.containsKey(p.getUUID()) || !run.layout.inVault(p.getX(), p.getY(), p.getZ())) continue;
            HitResult hit = l.clip(new ClipContext(eye, p.getEyePosition(), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, e));
            if (hit.getType() == HitResult.Type.BLOCK) {
                tip(p, "§aThe beam hit the pillar!");
                continue;
            }
            p.hurtServer(l, l.damageSources().mobAttack((Mob) e), BEAM[run.diff]);
            l.sendParticles(ParticleTypes.ELECTRIC_SPARK, p.getX(), p.getY() + 1, p.getZ(), 20, 0.3, 0.6, 0.3, 0.1);
        }
        if (!run.bellLost) {
            Vec3 bc = Vec3.atCenterOf(run.layout.bellPos(run.bellU).offset(0, 2, 0));
            HitResult hit = l.clip(new ClipContext(eye, bc, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, e));
            if (hit.getType() != HitResult.Type.BLOCK || run.bellBlocks.contains(BlockPos.containing(hit.getLocation()))) damageBell(l, run, 25);
        }
        l.playSound(null, e.getX(), e.getY(), e.getZ(), SoundEvents.ELDER_GUARDIAN_HURT, SoundSource.HOSTILE, 2f, 0.6f);
    }

    private static void mobDied(Entity e, Entity killer) {
        Object[] o = GUARD_OF.remove(e.getUUID());
        if (o == null) return;
        Run run = (Run) o[0];
        if (run.over) return;
        if (e.getType() == EntityTypes.DROWNED && killer instanceof ServerPlayer p && RNG.nextInt(TRIDENT_ODDS) == 0) {
            EndRewards.give(p, new ItemStack(Items.TRIDENT));
            server.getPlayerList().broadcastSystemMessage(Component.literal("§b§l✦ §e" + p.getName().getString() + " §7pulled a §b§lTRIDENT §7from a drowned in the Sunken Monument!"), false);
        }
        if (e.getUUID().equals(run.boss)) win(run);
    }

    // =====================================================================
    // 5.21: op test mode (/sunken admin test <difficulty> [crew])
    // =====================================================================

    /** Starts a test trip for this op right away: no fare, no ship room, no muster, no rewards. */
    static void startTest(ServerPlayer p, int d, int crew) {
        if (runOf(p.getUUID()) != null || musterOf(p.getUUID()) != null) {
            Lobby.msg(p, "§7You're already on a Sunken Monument trip (/sunken admin end).");
            return;
        }
        ServerLevel l = level();
        if (l == null) {
            Lobby.msg(p, "§cThe Sunken Monument world isn't loaded.");
            return;
        }
        Islands.Island is = Islands.islandOf(p.getUUID());
        Run run = new Run(runCounter++, d, is == null ? -1 : is.id, p.getUUID());
        run.test = true;
        run.testCrew = Math.max(1, Math.min(CREW_MAX, crew));
        run.crew.add(p.getUUID());
        run.crewSize = run.testCrew;
        BlockPos center = new BlockPos(2_000 + (run.id % 16) * 300, SunkenMap.FLOOR_Y, 2_000 + (run.id / 16) * 300);
        run.layout = SunkenMap.plan(l, center, run.diff, RNG);
        for (SunkenMap.Current c : run.layout.currents) c.trap = RNG.nextInt(100) < 30;
        run.timeLimit = (int) Math.round(TIME_LIMITS[run.diff] * TIME_MULT[run.crewSize - 1]);
        RUNS.add(run);
        Lobby.msg(p, "§b[Test] §7Building a " + DIFF_COLORS[d] + DIFF_NAMES[d] + " §7monument scaled for a crew of §f" + run.testCrew + "§7...");
        Skyserver.LOGGER.info("[Sunken] TEST trip #{} ({}, crew size {}) by {}", run.id, DIFF_NAMES[d], run.testCrew, p.getName().getString());
    }

    private static void testHelp(ServerPlayer p) {
        Lobby.msg(p, "§b§l[TEST TRIP] §7No fare taken; room chests still give loot (to check it), the clear pays nothing. Op commands: §e/sunken admin skip §7(clear the room you're fighting), "
                + "§e/sunken admin wing §7(clear the open wing), §e/sunken admin vault §7(open the vault), §e/sunken admin boss §7(the Tidemother now), "
                + "§e/sunken admin god §7(no damage), §e/sunken admin time <min>§7, §e/sunken admin end§7.");
    }

    private static Run testRun(ServerPlayer p) {
        Run run = runOf(p.getUUID());
        if (run == null || !run.test || run.building || run.over) {
            Lobby.msg(p, "§7That only works on your own test trip (§e/sunken admin test <difficulty> [crew]§7).");
            return null;
        }
        return run;
    }

    /** Clears the active room of the open wing (or every room of it with wholeWing). */
    static void testSkip(ServerPlayer p, boolean wholeWing) {
        Run run = testRun(p);
        if (run == null) return;
        ServerLevel l = level();
        if (run.openWing < 0) {
            Lobby.msg(p, "§7No wing is open - open a floodgate first (talk to Wren), or use §e/sunken admin vault§7.");
            return;
        }
        int n = 0;
        for (SunkenMap.Room r : run.layout.rooms) {
            if (r.wing != run.openWing || r.cleared) continue;
            if (!wholeWing && !r.active) continue;
            for (UUID g : r.guards) {
                Entity e = l.getEntity(g);
                if (e != null) e.discard();
                GUARD_OF.remove(g);
            }
            r.guards.clear();
            r.active = true;
            r.wave = 2;
            roomCleared(l, run, r);
            n++;
            if (!wholeWing) break;
        }
        Lobby.msg(p, n == 0 ? "§7No room is being fought right now - walk into one, or use §e/sunken admin wing§7." : "§b[Test] §7Cleared " + n + " room(s).");
    }

    /** Opens the vault (marks every wing done), and with boss also raises the Tidemother. */
    static void testVault(ServerPlayer p, boolean boss) {
        Run run = testRun(p);
        if (run == null) return;
        ServerLevel l = level();
        if (!run.vaultOpen) {
            for (SunkenMap.Room r : run.layout.rooms) {
                for (UUID g : r.guards) {
                    Entity e = l.getEntity(g);
                    if (e != null) e.discard();
                    GUARD_OF.remove(g);
                }
                r.guards.clear();
                if (!r.cleared) {
                    r.active = true;
                    r.wave = 2;
                    roomCleared(l, run, r);
                }
            }
            run.wingDone[0] = run.wingDone[1] = run.wingDone[2] = true;
            run.openWing = -1;
            openVault(l, run);
        }
        if (boss && !run.bossUp) bossRises(l, run);
        Lobby.msg(p, "§b[Test] §7" + (boss ? "The Tidemother is up." : "The vault gate is open."));
    }

    static void testGod(ServerPlayer p) {
        Run run = testRun(p);
        if (run == null) return;
        run.god = !run.god;
        Lobby.msg(p, "§b[Test] §7God mode " + (run.god ? "§aON" : "§cOFF") + "§7.");
    }

    static void testTime(ServerPlayer p, int minutes) {
        Run run = testRun(p);
        if (run == null) return;
        run.timeLimit = (int) ((ticks - run.startTick) / 20) + minutes * 60;
        run.warned = 0;
        Lobby.msg(p, "§b[Test] §7" + minutes + " minutes left.");
    }

    // =====================================================================
    // Winning
    // =====================================================================

    private static void win(Run run) {
        if (run.over || run.won) return;
        run.won = true;
        int d = run.diff;
        if (run.test) { // 5.21: test trips pay nothing
            for (ServerPlayer m : inside(run)) {
                removeSponges(m);
                Lobby.msg(m, "§b§lTEST CLEAR! §7(" + DIFF_NAMES[d] + ", crew size " + run.crewSize + ", bell " + (run.bellLost ? "lost" : "afloat")
                        + ", " + ((ticks - run.startTick) / 20 / 60) + " min) §8No rewards on a test trip.");
            }
            Lobby.runLaterTicks(5 * 20, () -> Safe.run("sunken test home", () -> {
                for (ServerPlayer m : inside(run)) if (!DOWNED.containsKey(m.getUUID())) extract(m, "§6[Captain Wren] §fTest over, captain.");
                endRun(run, "test clear");
            }));
            return;
        }
        for (ServerPlayer m : inside(run)) {
            if (DOWNED.containsKey(m.getUUID())) continue;
            removeSponges(m);
            int cleared = clearsToday(m.getUUID());
            long cash = cleared < FULL_CLEARS ? CASH[d] : Math.round(CASH[d] * CASH_LATER);
            countClear(m.getUUID());
            Economy.add(server, m.getUUID(), m.getName().getString(), cash);
            StringBuilder got = new StringBuilder("§b§lMONUMENT CLEARED! §7You got §6" + Economy.format(cash) + (cleared >= FULL_CLEARS ? " §8(half: clear " + (cleared + 1) + " today)" : ""));
            int kt = KEY_TIER[d];
            Keys.reward(server, m.getUUID(), kt, 1, "(Sunken Monument)");
            got.append("§7, ").append(Keys.COLORS[kt]).append(Keys.NAMES[kt]).append(" Key");
            Tokens.add(m.getUUID(), Tokens.SHARDS, SHARDS[d]);
            got.append("§7, §d").append(SHARDS[d]).append(" shards");
            BattlePass.addPassXp(m, PASS_XP[d]);
            Levels.addXp(server, m, XP[d]);
            got.append("§7, §b").append(PASS_XP[d]).append(" pass XP");
            if (!run.bellLost) {
                if (roll(HEART_CHANCE[d])) {
                    EndRewards.give(m, new ItemStack(Items.HEART_OF_THE_SEA));
                    got.append("§7, §ba heart of the sea");
                }
                if (roll(TIDE_CHANCE[d])) {
                    EndRewards.give(m, EndRewards.trim("tide"));
                    got.append("§7, §3§lthe Tide trim!");
                }
                if (roll(CONDUIT_CHANCE[d])) {
                    EndRewards.give(m, new ItemStack(Items.CONDUIT));
                    got.append("§7, §b§la conduit!");
                }
                EndRewards.give(m, new ItemStack(Items.SPONGE, VAULT_SPONGES[d]));
                EndRewards.give(m, new ItemStack(Items.NAUTILUS_SHELL, 1 + RNG.nextInt(2)));
                EndRewards.give(m, new ItemStack(Items.SEA_LANTERN, 4));
                got.append("§7, ").append(VAULT_SPONGES[d]).append(" sponge").append(VAULT_SPONGES[d] == 1 ? "" : "s").append(", nautilus shells, sea lanterns");
            } else got.append(" §8(the bell was lost - no vault haul)");
            if (d == 3) {
                PlayerProfile prof = PlayerCache.get(m);
                if (prof.owned.add(TITLE)) {
                    prof.touch();
                    got.append("§7, §b§lthe title ").append(Progression.cosmeticName(TITLE));
                }
            }
            PlayerStats.add(m, "sunken_win", 1);
            Charts.stamp(m, "sunken_" + d);
            Lobby.msg(m, got.toString());
        }
        server.getPlayerList().broadcastSystemMessage(Component.literal("§b§l✦ §e" + names(run) + " §7beat the Tidemother in a " + DIFF_COLORS[d] + DIFF_NAMES[d]
                + " §7Sunken Monument" + (run.bellLost ? "" : " §7and brought the vault home") + "!"), false);
        Lobby.runLaterTicks(10 * 20, () -> Safe.run("sunken win home", () -> {
            for (ServerPlayer m : inside(run)) if (!DOWNED.containsKey(m.getUUID())) extract(m, "§6[Captain Wren] §fUp we come - what a haul!");
            endRun(run, "the Tidemother was beaten");
        }));
        for (ServerPlayer m : inside(run)) Lobby.msg(m, "§7Wren brings everyone up in 10 seconds.");
    }

    // ---- Each player's clears today (for the cash rule), saved in sunken.json ----

    private static String day = "";
    private static final Map<UUID, Integer> CLEARS = new HashMap<>();
    private static boolean clearsLoaded = false;

    private static String today() {
        return LocalDate.now(ZoneId.of("America/New_York")).toString();
    }

    private static int clearsToday(UUID id) {
        loadClears();
        if (!today().equals(day)) {
            day = today();
            CLEARS.clear();
        }
        return CLEARS.getOrDefault(id, 0);
    }

    private static void countClear(UUID id) {
        clearsToday(id);
        CLEARS.merge(id, 1, Integer::sum);
        JsonObject o = new JsonObject(), c = new JsonObject();
        o.addProperty("day", day);
        CLEARS.forEach((k, v) -> c.addProperty(k.toString(), v));
        o.add("clears", c);
        PlayerCache.io(() -> Stacks.writeFile("sunken.json", o));
    }

    private static void loadClears() {
        if (clearsLoaded) return;
        clearsLoaded = true;
        JsonObject o = Stacks.readFile("sunken.json");
        if (!o.has("day")) return;
        day = o.get("day").getAsString();
        if (o.has("clears")) for (Map.Entry<String, JsonElement> e : o.getAsJsonObject("clears").entrySet()) CLEARS.put(UUID.fromString(e.getKey()), e.getValue().getAsInt());
    }

    // =====================================================================
    // Sponges (they stay in the monument)
    // =====================================================================

    static ItemStack sponge(ItemStack s) {
        return Stacks.tag(Stacks.named(s, "§e§lWren's Sponge", "§7Soaks up water around it.", "§7Dry it at the furnace in Wren's dock.", "§8Stays in the monument when you leave."), SPONGE_TAG);
    }

    private static void removeSponges(ServerPlayer p) {
        Inventory inv = p.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) if (Stacks.is(inv.getItem(i), SPONGE_TAG)) inv.setItem(i, ItemStack.EMPTY);
    }

    private static void drySponges(ServerPlayer p) {
        Inventory inv = p.getInventory();
        int n = 0;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (!s.is(Items.WET_SPONGE) || !Stacks.is(s, SPONGE_TAG)) continue;
            n += s.getCount();
            inv.setItem(i, sponge(new ItemStack(Items.SPONGE, s.getCount())));
        }
        tip(p, n > 0 ? "§aDried " + n + " sponge" + (n == 1 ? "" : "s") + "!" : "§7No wet Wren's Sponges to dry.");
        if (n > 0) p.level().playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.FIRE_EXTINGUISH, SoundSource.BLOCKS, 0.8f, 1.2f);
    }

    // =====================================================================
    // Going home, dying, revives
    // =====================================================================

    private static void sendHome(ServerPlayer p, Run run) {
        Islands.Island own = Islands.islandOf(p.getUUID());
        Islands.Island is = own != null ? own : run == null ? null : Expedition.islandById(run.islandId);
        if (is == null) {
            Lobby.goToLobby(p);
            return;
        }
        // Back to the host's ship room if it's ours, otherwise our own island home.
        Islands.Island host = run == null ? null : Expedition.islandById(run.islandId);
        if (host != null && host == own && WrenRoom.hasRoom(host)) {
            double[] l = WrenRoom.landing(host);
            p.teleportTo(Islands.level(), l[0], l[1], l[2], Set.of(), (float) l[3], 0f, true);
            Islands.sendBorder(p, host);
        } else Islands.teleportHome(p, is);
    }

    static void goHome(ServerPlayer p) {
        Run run = runOf(p.getUUID());
        if (run == null || run.layout == null) {
            Lobby.msg(p, "§7You're not in the Sunken Monument.");
            return;
        }
        if (!run.layout.inDock(p.getX(), p.getY(), p.getZ())) {
            Lobby.msg(p, "§7Swim back to §6Wren's dock §7first.");
            return;
        }
        extract(p, "§6[Captain Wren] §fHome early? Fair enough, captain.");
    }

    static void extract(ServerPlayer p, String msg) {
        Run run = runOf(p.getUUID());
        UUID id = p.getUUID();
        DOWNED.remove(id);
        PROTECTED.remove(id);
        removeSponges(p);
        if (run != null) {
            run.inside.remove(id);
            if (run.bar != null) run.bar.removePlayer(p);
        }
        p.removeEffect(MobEffects.BLINDNESS);
        sendHome(p, run);
        p.resetFallDistance();
        p.clearFire();
        p.setAirSupply(p.getMaxAirSupply());
        Lobby.msg(p, msg + " §7Everything you're carrying is yours.");
        if (run != null && !run.over && run.inside.isEmpty()) endRun(run, "everyone went home");
    }

    private static void dropAll(ServerPlayer p, ServerLevel level, Vec3 at) {
        removeSponges(p);
        Inventory inv = p.getInventory();
        List<ItemStack> drops = new ArrayList<>();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (!s.isEmpty()) drops.add(s.copy());
        }
        inv.clearContent();
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            if (slot.getType() == EquipmentSlot.Type.HAND && slot != EquipmentSlot.OFFHAND) continue;
            ItemStack s = p.getItemBySlot(slot);
            if (s.isEmpty()) continue;
            drops.add(s.copy());
            p.setItemSlot(slot, ItemStack.EMPTY);
        }
        for (ItemStack s : drops) {
            ItemEntity e = new ItemEntity(level, at.x, at.y + 0.5, at.z, s);
            e.setDefaultPickUpDelay();
            e.setUnlimitedLifetime();
            e.setDeltaMovement((RNG.nextDouble() - 0.5) * 0.3, 0.2, (RNG.nextDouble() - 0.5) * 0.3);
            level.addFreshEntity(e);
        }
    }

    static void died(ServerPlayer p, String why) {
        Run run = runOf(p.getUUID());
        UUID id = p.getUUID();
        DOWNED.remove(id);
        PROTECTED.remove(id);
        ServerLevel level = (ServerLevel) p.level();
        boolean saved = EndRewards.useCharm(p);
        if (saved) removeSponges(p);
        else dropAll(p, level, p.position());
        p.setHealth(p.getMaxHealth());
        p.clearFire();
        p.removeAllEffects();
        p.getFoodData().setFoodLevel(20);
        p.setAirSupply(p.getMaxAirSupply());
        p.resetFallDistance();
        p.setDeltaMovement(0, 0, 0);
        if (run != null) {
            run.inside.remove(id);
            if (run.bar != null) run.bar.removePlayer(p);
            for (ServerPlayer m : inside(run))
                Lobby.msg(m, "§c" + p.getName().getString() + " " + why + "! §7" + (saved ? "Their Recovery Charm saved their things." : "Their things are where they fell."));
        }
        Lobby.runLater(() -> Safe.run("sunken send home", () -> {
            if (p.hasDisconnected()) return;
            sendHome(p, run);
            Lobby.msg(p, saved ? "§b§lYour Recovery Charm broke §7- you " + why + " but kept everything you carried."
                    : "§c§lYou " + why + " in the Sunken Monument §7and lost everything you were carrying.");
        }));
        if (run != null && !run.over && run.inside.isEmpty()) endRun(run, "nobody is left");
    }

    private static void loggedOut(ServerPlayer p) {
        Run run = runOf(p.getUUID());
        if (run == null || !isSunken(p.level())) {
            Muster mu = musterOf(p.getUUID());
            if (mu != null) {
                if (mu.leader.equals(p.getUUID())) MUSTERS.remove(mu);
                else mu.joined.remove(p.getUUID());
            }
            return;
        }
        DOWNED.remove(p.getUUID());
        PROTECTED.remove(p.getUUID());
        boolean safe = run.layout != null && run.layout.inDock(p.getX(), p.getY(), p.getZ());
        if (!safe && !EndRewards.useCharm(p)) {
            dropAll(p, (ServerLevel) p.level(), p.position());
            for (ServerPlayer m : inside(run)) if (m != p) Lobby.msg(m, "§c" + p.getName().getString() + " left the monument - their things are where they were.");
        } else removeSponges(p);
        run.inside.remove(p.getUUID());
        if (run.bar != null) run.bar.removePlayer(p);
        sendHome(p, run);
        if (!run.over && run.inside.isEmpty()) endRun(run, "nobody is left");
    }

    private static long reviveCost(Run run) {
        return REVIVE_COST[run.diff];
    }

    private static boolean canPay(Run run, UUID id) {
        return !run.revived.contains(id) && Economy.balance(server, id) >= reviveCost(run);
    }

    private static boolean teammateUp(Run run, UUID id) {
        for (ServerPlayer m : inside(run)) if (!m.getUUID().equals(id) && !DOWNED.containsKey(m.getUUID())) return true;
        return false;
    }

    private static boolean goDown(ServerPlayer p) {
        UUID id = p.getUUID();
        Run run = runOf(id);
        if (run == null) return false;
        boolean team = !run.teamReviveUsed && teammateUp(run, id);
        boolean paid = canPay(run, id);
        if (!team && !paid) {
            if (!run.revived.contains(id)) Lobby.msg(p, "§7(A revive costs " + Economy.format(reviveCost(run)) + " - you didn't have enough.)");
            return false;
        }
        Downed d = new Downed(p.getX(), p.getY(), p.getZ(), team ? 0 : 1, ticks + (team ? TEAM_REVIVE_SECONDS : REVIVE_SECONDS) * 20L);
        DOWNED.put(id, d);
        p.setHealth(1f);
        p.clearFire();
        p.setDeltaMovement(0, 0, 0);
        p.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, (TEAM_REVIVE_SECONDS + REVIVE_SECONDS) * 20, 9, false, false));
        p.addEffect(new MobEffectInstance(MobEffects.WATER_BREATHING, (TEAM_REVIVE_SECONDS + REVIVE_SECONDS) * 20, 0, false, false));
        if (team) {
            Announce.title(p, "§c§lYOU'RE DOWN", "§7A teammate can pick you up (" + TEAM_REVIVE_SECONDS + "s)", 5, 80, 10);
            MutableComponent line = Component.literal("§c§lYou're down! §7A teammate has §f" + TEAM_REVIVE_SECONDS + " seconds §7to crouch next to you for "
                    + (TEAM_REVIVE_HOLD_TICKS / 20) + " seconds (the team's one pick-up this trip). ");
            if (paid) line.append(Expedition.button("§a[Revive now for " + Economy.format(reviveCost(run)) + "]", "/sunken revive", "Pay and get up at full health")).append(Component.literal(" "));
            line.append(Expedition.button("§c[Give up]", "/sunken giveup", "Die: drop everything you carry and go home"));
            p.sendSystemMessage(line);
            for (ServerPlayer m : inside(run)) {
                if (m == p) continue;
                Announce.title(m, "§c§l" + p.getName().getString() + " IS DOWN", "§7Crouch next to them for " + (TEAM_REVIVE_HOLD_TICKS / 20) + " seconds!", 5, 60, 10);
            }
        } else {
            offerPaid(p, run);
            for (ServerPlayer m : inside(run)) if (m != p) Lobby.msg(m, "§c" + p.getName().getString() + " is down! §7They have " + REVIVE_SECONDS + " seconds to revive.");
        }
        return true;
    }

    private static void offerPaid(ServerPlayer p, Run run) {
        long cost = reviveCost(run);
        Announce.title(p, "§c§lYOU'RE DOWN", "§7Revive for §6" + Economy.format(cost) + "§7? Click in chat (" + REVIVE_SECONDS + "s)", 5, 80, 10);
        p.sendSystemMessage(Component.literal("§7Once per trip you can pay to revive: full health, right here, nothing lost. ")
                .append(Expedition.button("§a§l[Revive for " + Economy.format(cost) + "]", "/sunken revive", "Pay " + Economy.format(cost) + " and get back up"))
                .append(Component.literal(" "))
                .append(Expedition.button("§c§l[Give up]", "/sunken giveup", "Die: drop everything you carry and go home")));
    }

    private static void standUp(ServerPlayer p, double share) {
        UUID id = p.getUUID();
        DOWNED.remove(id);
        p.removeEffect(MobEffects.SLOWNESS);
        p.clearFire();
        p.setHealth((float) Math.max(1.0, p.getMaxHealth() * share));
        p.getFoodData().setFoodLevel(20);
        p.setAirSupply(p.getMaxAirSupply());
        p.resetFallDistance();
        PROTECTED.put(id, ticks + REVIVE_PROTECT_TICKS);
        p.level().playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.TOTEM_USE, SoundSource.PLAYERS, 1f, 1f);
        ((ServerLevel) p.level()).sendParticles(ParticleTypes.TOTEM_OF_UNDYING, p.getX(), p.getY() + 1, p.getZ(), 40, 0.5, 0.8, 0.5, 0.3);
    }

    static void revive(ServerPlayer p) {
        Run run = runOf(p.getUUID());
        Downed d = DOWNED.get(p.getUUID());
        if (run == null || d == null) {
            Lobby.msg(p, "§7You're not down.");
            return;
        }
        if (run.revived.contains(p.getUUID())) {
            Lobby.msg(p, "§cYou've already used your paid revive on this trip.");
            return;
        }
        if (!Economy.take(server, p.getUUID(), reviveCost(run))) {
            Lobby.msg(p, "§cYou don't have " + Economy.format(reviveCost(run)) + " for the revive.");
            if (d.phase == 1) died(p, "died");
            return;
        }
        run.revived.add(p.getUUID());
        standUp(p, 1.0);
        Announce.title(p, "§a§lREVIVED!", "§7Nothing can hurt you for " + (REVIVE_PROTECT_TICKS / 20) + " seconds", 5, 40, 10);
        for (ServerPlayer m : inside(run)) if (m != p) Lobby.msg(m, "§a" + p.getName().getString() + " is back up!");
    }

    static void giveUp(ServerPlayer p) {
        if (DOWNED.remove(p.getUUID()) == null) {
            Lobby.msg(p, "§7You're not down.");
            return;
        }
        died(p, "died");
    }

    private static void tickDowned() {
        if (DOWNED.isEmpty()) return;
        for (Map.Entry<UUID, Downed> e : new ArrayList<>(DOWNED.entrySet())) {
            ServerPlayer p = server.getPlayerList().getPlayer(e.getKey());
            Downed d = e.getValue();
            Run run = runOf(e.getKey());
            if (p == null || run == null || !isSunken(p.level())) {
                DOWNED.remove(e.getKey());
                continue;
            }
            p.clearFire();
            p.setAirSupply(p.getMaxAirSupply());
            if (Math.abs(p.getX() - d.x) > 0.3 || Math.abs(p.getZ() - d.z) > 0.3 || Math.abs(p.getY() - d.y) > 1.5) p.teleportTo(d.x, d.y, d.z);
            long left = Math.max(0, (d.deadline - ticks) / 20);
            if (d.phase == 0) {
                ServerPlayer helper = null;
                for (ServerPlayer m : inside(run)) {
                    if (m == p || DOWNED.containsKey(m.getUUID()) || !m.isShiftKeyDown()) continue;
                    if (m.distanceToSqr(p) <= TEAM_REVIVE_RANGE * TEAM_REVIVE_RANGE) {
                        helper = m;
                        break;
                    }
                }
                if (helper != null && !run.teamReviveUsed) {
                    d.hold++;
                    if (d.hold >= TEAM_REVIVE_HOLD_TICKS) {
                        run.teamReviveUsed = true;
                        standUp(p, TEAM_REVIVE_HEALTH);
                        for (ServerPlayer m : inside(run))
                            Lobby.msg(m, "§b" + helper.getName().getString() + " §apicked §b" + p.getName().getString() + " §aup! §7(The team's one pick-up this trip; paid revives still work.)");
                        continue;
                    }
                    if (d.hold % 5 == 0) {
                        String bar = "§bPicking up... §f" + (d.hold * 100 / TEAM_REVIVE_HOLD_TICKS) + "%";
                        Announce.actionBar(helper, bar + " §7(keep crouching)");
                        Announce.actionBar(p, bar);
                    }
                } else {
                    d.hold = 0;
                    if (ticks % 20 == 0) Announce.actionBar(p, "§c§lDOWN §7- a teammate can crouch next to you §f" + left + "s");
                }
                if (ticks >= d.deadline || run.teamReviveUsed) {
                    if (canPay(run, e.getKey())) {
                        d.phase = 1;
                        d.hold = 0;
                        d.deadline = ticks + REVIVE_SECONDS * 20L;
                        offerPaid(p, run);
                    } else {
                        DOWNED.remove(e.getKey());
                        died(p, "died");
                    }
                }
                continue;
            }
            if (ticks >= d.deadline) {
                DOWNED.remove(e.getKey());
                died(p, "died");
                continue;
            }
            if (ticks % 20 == 0) Announce.actionBar(p, "§c§lDOWN §7- revive or give up in chat §f" + left + "s");
        }
    }

    // =====================================================================
    // Ending a trip
    // =====================================================================

    static void endRun(Run run, String why) {
        if (run == null || run.over) return;
        run.over = true;
        RUNS.remove(run);
        ServerLevel l = level();
        for (UUID id : new ArrayList<>(run.inside)) {
            ServerPlayer p = server.getPlayerList().getPlayer(id);
            if (p != null && isSunken(p.level())) {
                removeSponges(p);
                sendHome(p, run);
            }
        }
        for (UUID id : run.crew) DOWNED.remove(id);
        if (run.bar != null) run.bar.removeAllPlayers();
        GUARD_OF.entrySet().removeIf(en -> en.getValue()[0] == run);
        if (run.layout != null && run.layout.builder != null) run.layout.builder.release();
        if (l != null && run.layout != null) {
            int[] b = run.layout.box();
            AABB box = new AABB(b[0], SunkenMap.FLOOR_Y - 20, b[1], b[2], SunkenMap.FLOOR_Y + 20, b[3]);
            for (Entity e : l.getEntities((Entity) null, box, e -> !(e instanceof Player))) e.discard();
        }
        Skyserver.LOGGER.info("[Sunken] trip #{} over ({})", run.id, why);
    }

    private static void shutdown() {
        MUSTERS.clear();
        for (Run run : new ArrayList<>(RUNS)) {
            for (ServerPlayer p : inside(run)) extract(p, "§6[Captain Wren] §fThe server is going down - up we come!");
            endRun(run, "server stopping");
        }
    }

    // =====================================================================
    // Helpers
    // =====================================================================

    static List<ServerPlayer> inside(Run run) {
        List<ServerPlayer> out = new ArrayList<>();
        if (server == null) return out;
        for (UUID id : run.inside) {
            ServerPlayer p = server.getPlayerList().getPlayer(id);
            if (p != null && isSunken(p.level())) out.add(p);
        }
        return out;
    }

    private static String names(Run run) {
        List<String> n = new ArrayList<>();
        for (ServerPlayer p : inside(run)) n.add(p.getName().getString());
        return n.isEmpty() ? "A crew" : String.join(", ", n);
    }

    private static void tip(ServerPlayer p, String text) {
        Announce.actionBar(p, text);
    }
}
