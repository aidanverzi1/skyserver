package com.aidan.skyserver.expedition;

import com.aidan.skyserver.Safe;
import com.aidan.skyserver.Skyserver;
import com.aidan.skyserver.bots.BotPlayer;
import com.aidan.skyserver.island.Islands;
import com.aidan.skyserver.lobby.*;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
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
import net.minecraft.core.GlobalPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.warden.Warden;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.LodestoneTracker;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.SculkSensorPhase;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/**
 * 5.19 / 5.21: the ANCIENT CITY EXPEDITION - a stealth trip through a 201 x 201 deepslate maze (AncientCityMap).
 *
 * HOW A TRIP GOES (5.21)
 *  - Talk to Captain Wren -> Ancient City. Price per player: $5k / $10k / $20k / $40k, or 1,000 / 2,000 / 4,000 /
 *    8,000 City Cash. Everyone must carry a RECOVERY COMPASS (Wren sells one for $2,500, Old Ves for 250 CC).
 *    Nightmare: every crew member must wear boots with HUSH II or better. Up to 3 (island and faction mates).
 *  - THE NOISE METER: one meter for the whole crew (0-100%, +35% room per extra player). Walking makes noise
 *    (less on carpet, much less crouching, none crouching on carpet); so do landing, placing and breaking blocks,
 *    chests, urns, sensors going off, shriekers, the relic's heartbeat, eating and getting hurt. Every number is
 *    x0.6 / x0.8 / x1.0 / x1.3 by difficulty. It drops 1 point every 20 seconds of silence, never below a
 *    milestone already passed:
 *      25% - nearby house candles go out (no more telling real from decoy there), darkness pulses
 *      50% - the Wardens go after the loudest player
 *      75% - shriekers go off by themselves, and a second Warden digs up by the relic
 *      90% - Wren starts pulling the rope up: 60 seconds to reach it (anyone may climb out, keeping their things)
 *      100% - THE TRIP IS LOST: everyone loses everything they carry (a Recovery Charm saves its holder).
 *  - No sprinting, ever (food is held at 6 - no natural healing in the City). Night vision doesn't work.
 *    No light sources, carpet or wool can be placed. Every player starts with 3 Bone Rattles.
 *  - Houses: real ones have LIT candles inside, decoys UNLIT ones (a decoy chest: 15 noise and darkness).
 *  - Taking the relic: its carrier is slowed and can't sprint; it beats like a heart (Wardens nearby come to look);
 *    one more Warden digs up 20 / 8 / 8 / 5 seconds later; WREN'S LIFT JAMS and he drops a rope at one of the 3 rope
 *    shafts - your recovery compass points to it. The carrier at the rope = everyone still in the city escapes.
 *    The relic can be thrown to a teammate (5 noise where it lands).
 *  - The DEEP VAULT (optional) under the plug south of the frame: a Warden's sonic boom through the plug breaks it
 *    (6 noise), or mine it (12 noise a block). Climb out by building up or with ladders.
 *  - Rewards: City Cash (escape, Silent Escape, Clean Sweep, weekly first escape, first stamp of a map, the vault),
 *    echo shards, a key, Key Shards, XP, a random music disc, maybe a trim / Hush book, Old Ves's requests, the
 *    weekly leaderboard. Urns pay real money.
 *  - Dying drops everything you carry where you fell (a Recovery Charm saves it). Downed first if a teammate can
 *    pick you up (once per team) or you can pay the revive (20%, once per player).
 */
public final class AncientCity {
    private AncientCity() {}

    public static final ResourceKey<Level> DIM = ResourceKey.create(Registries.DIMENSION, Identifier.fromNamespaceAndPath(Skyserver.MOD_ID, "ancient_city"));
    public static final String[] DIFF_NAMES = Tokens.DIFF_NAMES;
    public static final String[] DIFF_COLORS = {"§a", "§e", "§c", "§4"};
    public static final long[] PRICES = {5_000, 10_000, 20_000, 40_000};
    /** 5.21: the same trip paid in City Cash. */
    public static final int[] PRICES_CC = {1_000, 2_000, 4_000, 8_000};
    public static final long COMPASS_PRICE = 2_500;
    /** Time limits by difficulty (seconds). */
    static final int[] TIME_LIMITS = {25 * 60, 30 * 60, 35 * 60, 40 * 60};
    static final int CREW_MAX = 3, MUSTER_SECONDS = 30, MAX_RUNS = 4;
    /** Seconds after the relic is taken before one more Warden digs up. */
    static final int[] RELIC_WARDEN_SECONDS = {20, 8, 8, 5};
    /** Roaming Wardens from the start. */
    static final int[] ROAMING = {1, 1, 2, 2};
    /** Revives (like the Nether). */
    static final double REVIVE_SHARE = 0.20, TEAM_REVIVE_HEALTH = 0.5, TEAM_REVIVE_RANGE = 2.5;
    static final int TEAM_REVIVE_SECONDS = 30, REVIVE_SECONDS = 30, TEAM_REVIVE_HOLD_TICKS = 100, REVIVE_PROTECT_TICKS = 60;

    // ---- The noise meter ----
    static final double[] NOISE_MULT = {0.6, 0.8, 1.0, 1.3};
    static final double CREW_ROOM = 0.35;
    static final double WALK = 1.0, CROUCH = 0.3, CARPET_WALK = 0.2, CARPET_CROUCH = 0.0;
    /** Crouch noise on bare floor with Hush I / II / III boots. */
    static final double[] HUSH_CROUCH = {0.3, 0.27, 0.24, 0.20};
    static final double LAND = 2, PLACE = 2, LADDER = 1, BREAK = 8, PLUG = 12, BOOM = 6, SENSOR = 3, URN = 3, CHEST = 4, DECOY = 15,
            SHRIEK = 15, HEART = 1, EAT = 1, HURT = 3, RATTLE = 1, THROW = 5, TONIC = 1;
    static final int[] HEARTBEAT_SECONDS = {8, 6, 5, 4};
    static final int[] MILESTONES = {25, 50, 75, 90, 100};
    static final int DECAY_SECONDS = 20, DARKNESS_EVERY = 20, SELF_SHRIEK_EVERY = 25, ROPE_PULL_SECONDS = 60, SILENT_PCT = 33;

    // ---- Rewards ----
    static final int[] ESCAPE_CC = {175, 350, 700, 1_400}, SWEEP_CC = {60, 120, 250, 500}, VAULT_CC = {150, 300, 600, 1_200};
    static final double SILENT_BONUS = 0.5, WEEKLY_BONUS = 0.25;
    static final int FIRST_STAMP_CC = 200;
    static final int[] ESCAPE_ECHO = {2, 3, 4, 6}, SNEAK_BOOK_CHANCE = {0, 0, 25, 50};
    /** Trims, per thousand: on escape, and per chest. */
    static final int[] WARD_ESCAPE = {70, 150, 280, 400}, SILENCE_ESCAPE = {10, 30, 50, 90};
    static final int[] WARD_CHEST = {10, 30, 50, 80}, SILENCE_CHEST = {0, 5, 15, 25};
    /** Deep Vault trims (%). */
    static final int[] WARD_VAULT = {10, 15, 20, 25}, SILENCE_VAULT = {2, 4, 6, 8};
    /** Hush book on escape: chance (%) and the share (%) that are the higher of the two levels. */
    static final int[] HUSH_ESCAPE = {3, 4, 5, 6}, HUSH_ESCAPE_HIGH = {0, 25, 30, 50};
    static final int[][] HUSH_ESCAPE_LEVELS = {{1, 1}, {1, 2}, {2, 3}, {2, 3}};
    /** Hush per chest: {chance of the low level, chance of the high level} (%), and the levels. */
    static final int[][] HUSH_CHEST = {{3, 0}, {2, 1}, {3, 1}, {4, 2}};
    static final int[][] HUSH_VAULT = {{20, 0}, {20, 15}, {25, 15}, {25, 25}};
    static final int[][] HUSH_LEVELS = {{1, 1}, {1, 2}, {2, 3}, {2, 3}};
    static final int[] SHARD_CHEST = {3, 4, 6, 8};
    static final int[] ESCAPE_KEY_TIER = {1, 2, 3, 3}, ESCAPE_SHARDS = {5, 11, 21, 42};
    static final int[] ESCAPE_PASS_XP = {315, 525, 840, 1_260}, ESCAPE_XP = {158, 263, 420, 630};
    /** Urns: real money (min, max). */
    static final int[][] URN_CASH = {{75, 225}, {100, 300}, {150, 450}, {250, 750}};
    static final int START_RATTLES = 3;
    static final String WREN_TAG = "skyserver_city_wren", RELIC_TAG = "city_relic", SILENT_TITLE = "title_silent";
    static final String RATTLE_TAG = "city_rattle", LENS_TAG = "city_lens", TONIC_TAG = "city_tonic", ROPE_COMPASS_TAG = "city_rope_compass";

    static final class Run {
        final int id, diff, islandId, map;
        final UUID leader;
        final List<UUID> crew = new ArrayList<>();
        /** Who paid in City Cash (refunds go back the same way). */
        final Set<UUID> paidCc = new HashSet<>();
        final Set<UUID> inside = new LinkedHashSet<>();
        final Set<UUID> revived = new HashSet<>();
        final Set<Long> placed = new HashSet<>();
        final Map<Long, Set<UUID>> looted = new HashMap<>();
        final Set<UUID> decoyOpened = new HashSet<>(), vaultLooted = new HashSet<>(), tonicUsed = new HashSet<>();
        final Map<UUID, Integer> urnsBroken = new HashMap<>();
        final Map<UUID, Long> tonicUntil = new HashMap<>();
        final Map<Long, Boolean> shrieking = new HashMap<>(), sensing = new HashMap<>();
        final Set<Long> urnsLeft = new HashSet<>();
        final List<CityWardens.W> wardens = new ArrayList<>();
        final List<Long> wardenRespawns = new ArrayList<>();
        AncientCityMap.Layout layout;
        boolean building = true, over = false, teamReviveUsed = false, relicTaken = false, escaped = false, anyDown = false;
        long startTick, relicWardenAt = -1;
        int timeLimit, warned = 0;
        UUID wren;
        // Noise.
        double noise = 0, cap = 100;
        int milestone = 0;
        long lastNoise = 0, nextDecay = 0, nextDarkness = -1, nextSelfShriek = -1, ropeDeadline = -1, nextHeartbeat = -1;
        final Map<UUID, Double> loud = new HashMap<>();
        // The rope.
        int ropeShaft = -1;
        // Movement tracking.
        final Map<UUID, double[]> lastPos = new HashMap<>();
        final Map<UUID, Double> falling = new HashMap<>();
        // Thrown things: rattles and the relic.
        final Map<UUID, Vec3> rattles = new HashMap<>();
        final Set<UUID> relicItems = new HashSet<>();
        final List<Object[]> lenses = new ArrayList<>(); // {player uuid, until tick}

        Run(int id, int diff, int islandId, UUID leader, int map) {
            this.id = id;
            this.diff = diff;
            this.islandId = islandId;
            this.leader = leader;
            this.map = map;
        }

        int pct() {
            return (int) Math.min(100, Math.floor(100 * noise / cap));
        }
    }

    private static final class Muster {
        final UUID leader;
        final int islandId, diff;
        final LinkedHashSet<UUID> paid = new LinkedHashSet<>();
        /** Who paid in City Cash (refunds go back the same way). */
        final Set<UUID> paidCc = new HashSet<>();
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
    /** 5.21: finished trips are wiped back to air (a chunk at a time) so the next city at that spot starts clean. */
    private static final List<AncientCityMap.Builder> CLEANERS = new ArrayList<>();
    private static final Random RNG = new Random();
    private static long ticks = 0;
    private static int runCounter = 0;

    public static boolean isCity(Level l) {
        return l != null && l.dimension() == DIM;
    }

    static ServerLevel level() {
        return server == null ? null : server.getLevel(DIM);
    }

    static Run runOf(UUID id) {
        for (Run r : RUNS) if (r.inside.contains(id)) return r;
        return null;
    }

    static boolean isDowned(UUID id) {
        return DOWNED.containsKey(id);
    }

    private static Muster musterOf(UUID id) {
        for (Muster m : MUSTERS) if (m.paid.contains(id)) return m;
        return null;
    }

    // =====================================================================
    // Setup (started from Expedition.init, before Lobby)
    // =====================================================================

    public static void init() {
        CityMarket.init();
        ServerLifecycleEvents.SERVER_STARTING.register(s -> Safe.run("city wipe", () -> {
            try {
                wipe(s);
            } catch (IOException e) {
                Skyserver.LOGGER.warn("Couldn't wipe the Ancient City world: {}", e.toString());
            }
        }));
        ServerLifecycleEvents.SERVER_STARTED.register(s -> {
            server = s;
            if (s.getLevel(DIM) == null) Skyserver.LOGGER.error("Ancient City dimension skyserver:ancient_city is missing (resources/data/skyserver/dimension/ancient_city.json).");
            if (CityMarket.hush(s) == null) Skyserver.LOGGER.error("The Hush enchantment (resources/data/skyserver/enchantment/hush.json) didn't load - Nightmare's boot check is off.");
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(s -> Safe.run("city stop", AncientCity::shutdown));
        ServerTickEvents.END_SERVER_TICK.register(s -> {
            ticks++;
            if (!CLEANERS.isEmpty()) Safe.run("city clean", () -> CLEANERS.removeIf(AncientCityMap.Builder::tick));
            if (MUSTERS.isEmpty() && RUNS.isEmpty() && DOWNED.isEmpty()) return;
            Safe.run("city fast tick", AncientCity::fastTick);
            if (ticks % 20 == 0) for (Run r : new ArrayList<>(RUNS)) Safe.run("city tick", () -> tick(r));
        });

        ServerLivingEntityEvents.ALLOW_DEATH.register((entity, source, amount) -> {
            if (!(entity instanceof ServerPlayer p) || p instanceof BotPlayer || !isCity(p.level()) || runOf(p.getUUID()) == null) return true;
            if (p.getMainHandItem().is(Items.TOTEM_OF_UNDYING) || p.getOffhandItem().is(Items.TOTEM_OF_UNDYING)) return true;
            if (DOWNED.containsKey(p.getUUID())) {
                p.setHealth(1f);
                return false;
            }
            Safe.run("city death", () -> {
                if (!goDown(p)) died(p, "died");
            });
            return false;
        });
        ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> {
            if (!isCity(entity.level())) return true;
            // The Warden can never be killed (or even hurt). Captain Wren neither.
            if (entity.getType() == EntityTypes.WARDEN || entity.entityTags().contains(WREN_TAG)) return false;
            if (entity instanceof ServerPlayer p) {
                if (DOWNED.containsKey(p.getUUID())) return false;
                Long safe = PROTECTED.get(p.getUUID());
                if (safe != null) {
                    if (ticks < safe) return false;
                    PROTECTED.remove(p.getUUID());
                }
                if (source.getEntity() instanceof Player) return false; // no player-vs-player
                Run run = runOf(p.getUUID());
                if (run == null || run.layout == null) return true;
                if (run.layout.inLift(p.getX(), p.getY(), p.getZ())) return false; // the lift is safe
                // A Warden's sonic boom through the Deep Vault plug breaks it open.
                if (source.is(DamageTypes.SONIC_BOOM) && source.getEntity() instanceof Warden w) Safe.run("city boom", () -> boom(run, w, p));
                if (amount > 0) Safe.run("city hurt", () -> noise(run, p, HURT, null));
                return true;
            }
            return true;
        });
        UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
            if (level.isClientSide() || !isCity(level) || !(player instanceof ServerPlayer sp)) return InteractionResult.PASS;
            Run run = runOf(sp.getUUID());
            if (run == null || run.layout == null || sp.isCreative()) return InteractionResult.PASS;
            boolean main = hand == net.minecraft.world.InteractionHand.MAIN_HAND;
            BlockPos pos = hit.getBlockPos();
            if (pos.equals(run.layout.relic)) {
                if (main) Safe.run("city relic", () -> takeRelic(sp, run));
                return InteractionResult.SUCCESS;
            }
            if (run.layout.houseAt(pos) != null || pos.equals(run.layout.vaultChest)) {
                if (main) Safe.run("city chest", () -> openChest(sp, run, pos));
                return InteractionResult.SUCCESS;
            }
            ItemStack held = player.getItemInHand(hand);
            if (held.getItem() instanceof BucketItem && !held.is(Items.BUCKET)) {
                tip(sp, "§cNo buckets in the Ancient City.");
                return InteractionResult.FAIL;
            }
            if (held.getItem() instanceof BlockItem bi) {
                BlockPos target = level.getBlockState(pos).canBeReplaced() ? pos : pos.relative(hit.getDirection());
                if (run.layout.inLift(target.getX() + 0.5, target.getY(), target.getZ() + 0.5)) {
                    tip(sp, "§7Keep Wren's lift clear, captain.");
                    return InteractionResult.FAIL;
                }
                String path = BuiltInRegistries.ITEM.getKey(held.getItem()).getPath();
                if (path.contains("wool") || path.endsWith("carpet")) {
                    tip(sp, "§7No extra carpet here - the city's carpet is all you get.");
                    return InteractionResult.FAIL;
                }
                if (bi.getBlock().defaultBlockState().getLightEmission() > 0) {
                    tip(sp, "§7No light in the Ancient City - it would wake everything.");
                    return InteractionResult.FAIL;
                }
                boolean wasAir = level.getBlockState(target).canBeReplaced();
                boolean ladder = held.is(Items.LADDER);
                Lobby.runLaterTicks(1, () -> Safe.run("city place", () -> {
                    if (!run.over && wasAir && !level.getBlockState(target).canBeReplaced()) {
                        run.placed.add(target.asLong());
                        noise(run, sp, ladder ? LADDER : PLACE, target);
                    }
                }));
            }
            return InteractionResult.PASS;
        });
        UseItemCallback.EVENT.register((player, level, hand) -> {
            if (level.isClientSide() || !isCity(level) || !(player instanceof ServerPlayer sp)) return InteractionResult.PASS;
            ItemStack held = player.getItemInHand(hand);
            if (held.getItem() instanceof BucketItem && !held.is(Items.BUCKET)) {
                tip(sp, "§cNo buckets in the Ancient City.");
                return InteractionResult.FAIL;
            }
            Run run = runOf(sp.getUUID());
            if (run == null || run.layout == null || run.building) return InteractionResult.PASS;
            if (held.is(Items.SNOWBALL)) {
                if (!Stacks.is(held, RATTLE_TAG)) {
                    tip(sp, "§7Only Wren's Bone Rattles work down here.");
                    return InteractionResult.FAIL;
                }
                noise(run, sp, RATTLE, null);
                // The snowball exists a tick later: remember it so we know where it lands.
                Lobby.runLaterTicks(1, () -> Safe.run("city rattle", () -> {
                    ServerLevel l2 = level();
                    if (l2 == null || run.over) return;
                    for (Entity e : l2.getEntities((Entity) null, sp.getBoundingBox().inflate(4), e -> e.getType() == EntityTypes.SNOWBALL))
                        if (!run.rattles.containsKey(e.getUUID())) run.rattles.put(e.getUUID(), e.position());
                }));
                return InteractionResult.PASS;
            }
            if (Stacks.is(held, LENS_TAG)) {
                held.shrink(1);
                run.lenses.add(new Object[]{sp.getUUID(), ticks + 8 * 20L});
                tip(sp, "§bThe Echo Lens hums - sensors nearby glow for 8 seconds.");
                return InteractionResult.FAIL;
            }
            if (Stacks.is(held, TONIC_TAG)) {
                if (!run.tonicUsed.add(sp.getUUID())) {
                    tip(sp, "§7One Hush Tonic per trip.");
                    return InteractionResult.FAIL;
                }
                held.shrink(1);
                run.tonicUntil.put(sp.getUUID(), ticks + 60 * 20L);
                noise(run, sp, TONIC, null);
                tip(sp, "§dHush Tonic: crouching is half as loud for 60 seconds.");
                return InteractionResult.FAIL;
            }
            return InteractionResult.PASS;
        });
        UseEntityCallback.EVENT.register((player, level, hand, entity, hit) -> {
            if (!isCity(level) || !entity.entityTags().contains(WREN_TAG)) return InteractionResult.PASS;
            if (hand == net.minecraft.world.InteractionHand.MAIN_HAND && player instanceof ServerPlayer sp) Safe.run("city wren", () -> talk(sp));
            return InteractionResult.SUCCESS;
        });
        AttackEntityCallback.EVENT.register((player, level, hand, entity, hit) ->
                isCity(level) && entity.entityTags().contains(WREN_TAG) ? InteractionResult.FAIL : InteractionResult.PASS);
        PlayerBlockBreakEvents.BEFORE.register((level, player, pos, state, be) -> {
            if (!isCity(level) || !(player instanceof ServerPlayer sp) || sp.isCreative()) return true;
            Run run = runOf(sp.getUUID());
            if (run == null || run.layout == null) return true;
            if (run.urnsLeft.contains(pos.asLong())) {
                Safe.run("city urn", () -> breakUrn(sp, run, pos));
                return false;
            }
            if (run.placed.remove(pos.asLong())) {
                noise(run, sp, BREAK, pos);
                return true;
            }
            if (run.layout.plug.contains(pos.asLong())) {
                run.layout.plug.remove(pos.asLong());
                noise(run, sp, PLUG, pos);
                return true;
            }
            if (run.layout.isCityBlock(pos) || pos.equals(run.layout.relic)) {
                tip(sp, "§7The ancient city is older than any pickaxe - it won't break.");
                return false;
            }
            return true;
        });
        // Registered before Lobby's (Expedition.init runs before Lobby), so things drop before anything saves.
        ServerPlayConnectionEvents.DISCONNECT.register((handler, s) -> Safe.run("city logout", () -> loggedOut(handler.player)));

        CommandRegistrationCallback.EVENT.register((d, r, e) -> d.register(Commands.literal("city")
                .executes(c -> { status(c.getSource().getPlayerOrException()); return 1; })
                .then(Commands.literal("call")
                        .executes(c -> { offer(c.getSource().getPlayerOrException()); return 1; })
                        .then(Commands.argument("difficulty", IntegerArgumentType.integer(0, 3))
                                .executes(c -> { call(c.getSource().getPlayerOrException(), IntegerArgumentType.getInteger(c, "difficulty"), false); return 1; })))
                .then(Commands.literal("callcc").then(Commands.argument("difficulty", IntegerArgumentType.integer(0, 3))
                        .executes(c -> { call(c.getSource().getPlayerOrException(), IntegerArgumentType.getInteger(c, "difficulty"), true); return 1; })))
                .then(Commands.literal("join").executes(c -> { join(c.getSource().getPlayerOrException(), false); return 1; }))
                .then(Commands.literal("joincc").executes(c -> { join(c.getSource().getPlayerOrException(), true); return 1; }))
                .then(Commands.literal("go").executes(c -> { goNow(c.getSource().getPlayerOrException()); return 1; }))
                .then(Commands.literal("compass").executes(c -> { buyCompass(c.getSource().getPlayerOrException()); return 1; }))
                .then(Commands.literal("top").executes(c -> { CityMarket.showBoard(c.getSource().getPlayerOrException()); return 1; }))
                .then(Commands.literal("home").executes(c -> { goHome(c.getSource().getPlayerOrException()); return 1; }))
                .then(Commands.literal("revive").executes(c -> { revive(c.getSource().getPlayerOrException()); return 1; }))
                .then(Commands.literal("giveup").executes(c -> { giveUp(c.getSource().getPlayerOrException()); return 1; }))
                .then(Commands.literal("help").executes(c -> { help(c.getSource().getPlayerOrException()); return 1; }))
                .then(Commands.literal("admin").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .then(Commands.literal("noise").then(Commands.argument("percent", IntegerArgumentType.integer(0, 100))
                                .executes(c -> {
                                    ServerPlayer p = c.getSource().getPlayerOrException();
                                    Run rn = runOf(p.getUUID());
                                    if (rn == null) { Lobby.msg(p, "§7You're not in the Ancient City."); return 0; }
                                    double want = rn.cap * IntegerArgumentType.getInteger(c, "percent") / 100.0;
                                    noise(rn, p, Math.max(0, want - rn.noise) / NOISE_MULT[rn.diff], null);
                                    return 1;
                                })))
                        .then(Commands.literal("givecc").then(Commands.argument("amount", IntegerArgumentType.integer(1, 1_000_000))
                                .executes(c -> {
                                    ServerPlayer p = c.getSource().getPlayerOrException();
                                    CityMarket.earn(p.getUUID(), IntegerArgumentType.getInteger(c, "amount"), "admin");
                                    CityMarket.balanceMsg(p);
                                    return 1;
                                })))
                        .then(Commands.literal("end").executes(c -> {
                            int n = RUNS.size() + MUSTERS.size();
                            for (Run rn : new ArrayList<>(RUNS)) {
                                for (ServerPlayer p : inside(rn)) extract(p, "§6[Captain Wren] §fAn admin called us home!");
                                endRun(rn, "ended by an admin");
                            }
                            for (Muster m : new ArrayList<>(MUSTERS)) cancelMuster(m, "ended by an admin");
                            c.getSource().sendSuccess(() -> Component.literal("Ended " + n + " Ancient City trip(s)."), true);
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
        Skyserver.LOGGER.info("Ancient City world wiped (a new city is built for every trip).");
    }

    // =====================================================================
    // Wren's menu button, calling Wren, the muster
    // =====================================================================

    /** Wren's menu button. */
    public static ItemStack islandButton(ServerPlayer p) {
        Run mine = runOf(p.getUUID());
        String state = mine != null ? "§eYou're in the Ancient City" : MUSTERS.size() + RUNS.size() >= MAX_RUNS ? "§cWren's lift is busy - try again soon" : "§eClick to call Wren";
        return Stacks.named(new ItemStack(Items.SCULK_SHRIEKER), "§3§lAncient City Expedition",
                "§7Sneak through a dark deepslate maze, loot", "§7its houses (lit candles = real), steal the", "§7relic and follow your compass to Wren's rope.",
                "§7One crew §fnoise meter§7: at 100% you lose", "§7everything. The §lWarden§7 can't be killed.",
                "§7Price: " + DIFF_COLORS[0] + "$5k " + DIFF_COLORS[1] + "$10k " + DIFF_COLORS[2] + "$20k " + DIFF_COLORS[3] + "$40k §7each",
                "§7  or §b1k / 2k / 4k / 8k City Cash", "§7Bring a §frecovery compass§7. Nightmare: §fHush II §7boots.",
                "§7This week: §fmap " + (CityMarket.mapIndex() + 1) + " §7of 3", "§c§lDying loses everything you carry!", state);
    }

    /** From Wren's menu or /city call: the difficulty choice in chat. */
    public static void offer(ServerPlayer p) {
        p.closeContainer();
        MutableComponent line = Component.literal("§6[Captain Wren] §fThe Ancient City, captain? Quiet as a mouse, mind. §7(map " + (CityMarket.mapIndex() + 1) + " this week) Pick how deep:");
        p.sendSystemMessage(line);
        for (int d = 0; d < 4; d++) {
            String hover = DIFF_NAMES[d] + ": " + AncientCityMap.HOUSES[d] + " houses + " + AncientCityMap.DECOYS[d] + " decoys, " + AncientCityMap.SENSORS[d] + " sensors, "
                    + AncientCityMap.SHRIEKERS[d] + " shriekers, " + ROAMING[d] + " roaming Warden" + (ROAMING[d] == 1 ? "" : "s") + ", noise x" + NOISE_MULT[d] + ", "
                    + (TIME_LIMITS[d] / 60) + " minutes" + (d == 3 ? ". Needs Hush II boots." : "");
            p.sendSystemMessage(Component.literal("  " + DIFF_COLORS[d] + "§l" + DIFF_NAMES[d] + " ")
                    .append(Expedition.button("§6[" + Economy.format(PRICES[d]) + "]", "/city call " + d, hover))
                    .append(Component.literal(" §7or "))
                    .append(Expedition.button("§b[" + CityMarket.fmt(PRICES_CC[d]) + " CC]", "/city callcc " + d, hover + " (paid in City Cash)")));
        }
        p.sendSystemMessage(Component.literal("  ").append(Expedition.button("§7[How it works]", "/city help", "What happens in the Ancient City"))
                .append(Component.literal(" ")).append(Expedition.button("§b[Buy a recovery compass " + Economy.format(COMPASS_PRICE) + "]", "/city compass", "Needed to find Wren's rope"))
                .append(Component.literal(" ")).append(Expedition.button("§3[Leaderboard]", "/city top", "This week's fastest escapes")));
    }

    static void buyCompass(ServerPlayer p) {
        if (!Islands.isIsland(p.level())) {
            Lobby.msg(p, "§7Buy it from Captain Wren on your island.");
            return;
        }
        if (!Economy.take(server, p.getUUID(), COMPASS_PRICE)) {
            Lobby.msg(p, "§cA recovery compass costs " + Economy.format(COMPASS_PRICE) + ". §7(Old Ves sells one for 250 City Cash.)");
            return;
        }
        EndRewards.give(p, new ItemStack(Items.RECOVERY_COMPASS));
        Lobby.msg(p, "§6[Captain Wren] §fThere you are - it'll point to my rope once the relic's taken. §7(Keep it: it isn't used up.)");
    }

    private static boolean busy(ServerPlayer p) {
        if (runOf(p.getUUID()) != null || musterOf(p.getUUID()) != null) {
            Lobby.msg(p, "§7You're already on an Ancient City crew.");
            return true;
        }
        if (MUSTERS.size() + RUNS.size() >= MAX_RUNS) {
            Lobby.msg(p, "§cWren's lift is busy (" + MAX_RUNS + " crews at once). §7Try again soon.");
            return true;
        }
        return false;
    }

    /** A real recovery compass (not a Recovery Charm), or the rope compass. */
    static boolean hasCompass(ServerPlayer p) {
        Inventory inv = p.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (Stacks.is(s, ROPE_COMPASS_TAG)) return true;
            if (s.is(Items.RECOVERY_COMPASS) && !Stacks.is(s, EndRewards.CHARM_TAG)) return true;
        }
        return false;
    }

    /** The compass and (Nightmare) Hush II boots. Says what's missing. */
    private static boolean ready(ServerPlayer p, int d) {
        if (!hasCompass(p)) {
            p.sendSystemMessage(Component.literal("§cYou need a recovery compass to find Wren's rope. ")
                    .append(Expedition.button("§b[Buy one - " + Economy.format(COMPASS_PRICE) + "]", "/city compass", "Or 250 City Cash from Old Ves")));
            return false;
        }
        if (d == 3 && CityMarket.hush(server) != null && CityMarket.hushLevel(p) < 2) {
            Lobby.msg(p, "§6[Captain Wren] §fNightmare's no place for loud boots! §7Wear boots with §3Hush II §7or better (Old Ves sells the books).");
            return false;
        }
        return true;
    }

    private static boolean pay(ServerPlayer p, int d, boolean cc) {
        if (cc) {
            if (CityMarket.spend(p.getUUID(), PRICES_CC[d], "city tickets")) return true;
            Lobby.msg(p, "§cA " + DIFF_NAMES[d] + " place costs " + CityMarket.fmt(PRICES_CC[d]) + " City Cash - you have " + CityMarket.fmt(CityMarket.balance(p.getUUID())) + ".");
            return false;
        }
        if (Economy.take(server, p.getUUID(), PRICES[d])) return true;
        Lobby.msg(p, "§cA " + DIFF_NAMES[d] + " place in the Ancient City costs " + Economy.format(PRICES[d]) + ".");
        return false;
    }

    private static void refund(UUID id, int d, boolean cc) {
        ServerPlayer p = server.getPlayerList().getPlayer(id);
        if (cc) CityMarket.earn(id, PRICES_CC[d], "ticket refund");
        else Economy.add(server, id, p == null ? "" : p.getName().getString(), PRICES[d]);
        if (p != null) Lobby.msg(p, "§7Your Ancient City " + (cc ? "City Cash" : "money") + " was given back.");
    }

    static void call(ServerPlayer p, int d, boolean cc) {
        if (!Islands.isIsland(p.level())) {
            Lobby.msg(p, "§cCall Captain Wren from the island world (/is).");
            return;
        }
        Islands.Island is = Islands.islandOf(p.getUUID());
        if (is == null) {
            Lobby.msg(p, "§cYou need a Sky Island first.");
            return;
        }
        if (busy(p) || !ready(p, d) || !pay(p, d, cc)) return;
        Muster mu = new Muster(p.getUUID(), is.id, d);
        mu.paid.add(p.getUUID());
        if (cc) mu.paidCc.add(p.getUUID());
        mu.deadline = ticks + MUSTER_SECONDS * 20L;
        MUSTERS.add(mu);
        Lobby.msg(p, "§6[Captain Wren] §fDown to the " + DIFF_COLORS[d] + DIFF_NAMES[d] + " §fcity we go! §7Your crew has §f" + MUSTER_SECONDS + " seconds §7to join (up to " + (CREW_MAX - 1) + ").");
        p.sendSystemMessage(Component.literal("§7").append(Expedition.button("§a[Sail now]", "/city go", "Leave without waiting")));
        for (ServerPlayer m : eligible(p)) {
            MutableComponent line = Component.literal("§3" + p.getName().getString() + " §7is going to the " + DIFF_COLORS[d] + DIFF_NAMES[d] + " §3§lAncient City§7! Join in " + MUSTER_SECONDS + "s: ");
            line.append(Expedition.button("§a[Join - " + Economy.format(PRICES[d]) + "]", "/city join", "Pay for your own place (bring a recovery compass)"));
            line.append(Component.literal(" ")).append(Expedition.button("§b[Join - " + CityMarket.fmt(PRICES_CC[d]) + " CC]", "/city joincc", "Pay in City Cash"));
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

    static void join(ServerPlayer p, boolean cc) {
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
            Lobby.msg(p, "§7No crew of your island or faction is getting ready for the Ancient City right now.");
            return;
        }
        if (mu.paid.size() >= CREW_MAX) {
            Lobby.msg(p, "§cThe crew is full (" + CREW_MAX + " at most).");
            return;
        }
        if (!ready(p, mu.diff) || !pay(p, mu.diff, cc)) return;
        mu.paid.add(p.getUUID());
        if (cc) mu.paidCc.add(p.getUUID());
        for (UUID id : mu.paid) {
            ServerPlayer m = server.getPlayerList().getPlayer(id);
            if (m != null) Lobby.msg(m, "§a" + p.getName().getString() + " joined the Ancient City crew §7(" + mu.paid.size() + "/" + CREW_MAX + ").");
        }
        if (mu.paid.size() >= CREW_MAX) depart(mu);
    }

    static void goNow(ServerPlayer p) {
        Muster mu = musterOf(p.getUUID());
        if (mu == null || !mu.leader.equals(p.getUUID())) {
            Lobby.msg(p, "§7Only the captain who called Wren can set off early.");
            return;
        }
        depart(mu);
    }

    private static void cancelMuster(Muster mu, String why) {
        if (!MUSTERS.remove(mu)) return;
        for (UUID id : mu.paid) refund(id, mu.diff, mu.paidCc.contains(id));
        Skyserver.LOGGER.info("[City] muster cancelled: {}", why);
    }

    /** Everyone who paid, is still on the island world and is still ready goes; the rest get their money back. */
    private static void depart(Muster mu) {
        if (!MUSTERS.remove(mu)) return;
        ServerLevel l = level();
        if (l == null) {
            for (UUID id : mu.paid) refund(id, mu.diff, mu.paidCc.contains(id));
            return;
        }
        Run run = new Run(runCounter++, mu.diff, mu.islandId, mu.leader, CityMarket.mapIndex());
        for (UUID id : mu.paid) {
            ServerPlayer m = server.getPlayerList().getPlayer(id);
            if (m != null && Islands.isIsland(m.level()) && run.crew.size() < CREW_MAX && ready(m, mu.diff)) {
                run.crew.add(id);
                if (mu.paidCc.contains(id)) run.paidCc.add(id);
            } else refund(id, mu.diff, mu.paidCc.contains(id));
        }
        if (run.crew.isEmpty()) return;
        BlockPos center = new BlockPos(2_000 + (run.id % 16) * 400, AncientCityMap.FLOOR_Y, 2_000 + (run.id / 16 % 16) * 400);
        run.layout = AncientCityMap.plan(l, center, run.map, run.diff, RNG);
        for (BlockPos u : run.layout.urns) run.urnsLeft.add(u.asLong());
        run.timeLimit = TIME_LIMITS[run.diff];
        RUNS.add(run);
        Skyserver.LOGGER.info("[City] trip #{} ({}, map {}, crew of {}): {} blocks to build", run.id, DIFF_NAMES[run.diff], run.map + 1, run.crew.size(), run.layout.builder.order.length);
        for (UUID id : run.crew) {
            ServerPlayer m = server.getPlayerList().getPlayer(id);
            if (m != null) Announce.title(m, "§3§lTHE ANCIENT CITY", "§7Captain Wren is lowering the lift...", 5, 60, 10);
        }
    }

    /** The city is built: the crew steps off the lift. */
    private static void arrive(Run run) {
        ServerLevel l = level();
        run.building = false;
        run.startTick = ticks;
        run.lastNoise = ticks;
        run.nextDecay = ticks + DECAY_SECONDS * 20L;
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
        double[] at = run.layout.landing;
        for (UUID id : run.crew) {
            ServerPlayer p = server.getPlayerList().getPlayer(id);
            if (p == null || !Islands.isIsland(p.level())) {
                refund(id, run.diff, run.paidCc.contains(id));
                continue;
            }
            p.teleportTo(l, at[0], at[1], at[2], Set.of(), (float) at[3], 0f, true);
            p.resetFallDistance();
            run.inside.add(id);
            int have = countTagged(p, RATTLE_TAG);
            if (have < START_RATTLES) EndRewards.give(p, CityMarket.rattles(START_RATTLES - have));
            Announce.title(p, "§3§lTHE ANCIENT CITY", "§7Sneak. Take the relic. Find the rope.", 10, 70, 20);
        }
        if (run.inside.isEmpty()) {
            endRun(run, "nobody boarded");
            return;
        }
        run.cap = 100 * (1 + CREW_ROOM * (run.inside.size() - 1));
        for (ServerPlayer p : inside(run)) help(p);
        for (int i = 0; i < ROAMING[run.diff]; i++) {
            BlockPos s = CityWardens.farSpot(run, 40);
            if (s != null) CityWardens.spawn(l, run, s, true, null);
        }
        Skyserver.LOGGER.info("[City] trip #{} started with {} player(s)", run.id, run.inside.size());
    }

    static void help(ServerPlayer p) {
        Lobby.msg(p, "§3§lANCIENT CITY §7- take the §frelic §7(the sculk catalyst on the frame in the middle). The lift then jams: follow your §frecovery compass §7to §6Wren's rope§7.");
        Lobby.msg(p, "§7§lNoise meter§7 (the whole crew): walking is loud, §fcrouching §7quieter, §fgrey carpet §7quietest - crouching on carpet is silent. "
                + "Landing, chests, blocks, urns, sensors and shriekers add more. §c§l100% = everyone loses everything.");
        Lobby.msg(p, "§7Houses with §flit candles §7inside are real - unlit ones are decoys. No sprinting, no night vision, no torches. "
                + "§fBone Rattles §7lure the §lWarden§7 (it §ccan't be killed§7; it gives up after 15 s out of sight).");
        Lobby.msg(p, "§7The §3Deep Vault §7under the cracked plug south of the frame: lure a Warden's sonic boom through it, or mine it (loud). "
                + "§c§lDying loses everything you carry §7(a §bRecovery Charm §7saves it).");
    }

    static void status(ServerPlayer p) {
        Run run = runOf(p.getUUID());
        if (run == null) {
            Lobby.msg(p, "§3§lANCIENT CITY EXPEDITIONS §7- a stealth trip for up to " + CREW_MAX + ". Talk to Captain Wren in his ship room. Map §f"
                    + (CityMarket.mapIndex() + 1) + " §7this week. §e/city top §7for the leaderboard.");
            Lobby.msg(p, "§7Prices per player: " + DIFF_COLORS[0] + "Easy $5k§7, " + DIFF_COLORS[1] + "Normal $10k§7, " + DIFF_COLORS[2] + "Hard $20k§7, "
                    + DIFF_COLORS[3] + "Nightmare $40k §7(or 1k / 2k / 4k / 8k City Cash).");
            return;
        }
        long left = Math.max(0, run.timeLimit - (ticks - run.startTick) / 20);
        Lobby.msg(p, "§3" + DIFF_NAMES[run.diff] + " Ancient City §7- noise §f" + run.pct() + "% §7- " + (run.relicTaken ? "§ethe relic is taken - to Wren's rope!" : "the relic is still on the frame")
                + " §7(" + (left / 60) + ":" + String.format("%02d", left % 60) + " left)");
    }

    static void talk(ServerPlayer p) {
        Run run = runOf(p.getUUID());
        if (run == null) return;
        if (hasRelic(p)) {
            Lobby.msg(p, "§6[Captain Wren] §fThe lift's jammed, captain! §7Follow your compass to my rope.");
            return;
        }
        p.sendSystemMessage(Component.literal("§6[Captain Wren] §f" + (run.relicTaken ? "The lift's jammed - get the relic to my rope! " : "The relic's on the frame in the middle, captain. ") + "§7")
                .append(Expedition.button("§c[Go home now - keep what you carry, no reward]", "/city home", "Leave the city early")));
    }

    // =====================================================================
    // The noise meter
    // =====================================================================

    /** Adds noise (raw points; the difficulty multiplier is applied here). src may be null; at = where (or null). */
    static void noise(Run run, ServerPlayer src, double raw, BlockPos at) {
        if (run.over || run.building || raw <= 0) return;
        double v = raw * NOISE_MULT[run.diff];
        run.noise += v;
        run.lastNoise = ticks;
        if (src != null) run.loud.merge(src.getUUID(), v, Double::sum);
        ServerLevel l = level();
        if (l != null && raw >= 5) CityWardens.heard(l, run, src, raw, at != null ? at : src == null ? null : src.blockPosition(), ticks);
        milestones(run);
    }

    private static void milestones(Run run) {
        int pct = run.pct();
        while (run.milestone < MILESTONES.length && pct >= MILESTONES[run.milestone]) {
            int m = run.milestone++;
            Safe.run("city milestone", () -> milestone(run, m));
            if (run.over) return;
        }
    }

    private static void milestone(Run run, int m) {
        ServerLevel l = level();
        if (l == null) return;
        switch (m) {
            case 0 -> {
                // Candles near anyone go out (the real / decoy tell is gone there).
                for (AncientCityMap.House h : run.layout.houses)
                    for (BlockPos c : h.candles) {
                        boolean near = false;
                        for (ServerPlayer p : inside(run)) if (p.blockPosition().distSqr(c) < 32 * 32) near = true;
                        BlockState st = l.getBlockState(c);
                        if (near && st.hasProperty(BlockStateProperties.LIT) && st.getValue(BlockStateProperties.LIT))
                            l.setBlock(c, st.setValue(BlockStateProperties.LIT, false), Block.UPDATE_ALL);
                    }
                run.nextDarkness = ticks;
                for (ServerPlayer p : inside(run)) Announce.title(p, "§8§lNOISE 25%", "§7The candles nearby gutter out...", 5, 50, 10);
            }
            case 1 -> {
                ServerPlayer loudest = loudest(run);
                CityWardens.engageAll(l, run, loudest);
                for (ServerPlayer p : inside(run))
                    Announce.title(p, "§c§lNOISE 50%", "§7The Wardens are coming for " + (loudest == null ? "someone" : loudest == p ? "§cYOU" : loudest.getName().getString()), 5, 60, 10);
            }
            case 2 -> {
                run.nextSelfShriek = ticks;
                ServerPlayer loudest = loudest(run);
                CityWardens.spawn(l, run, run.layout.center.offset(RNG.nextBoolean() ? 10 : -10, 0, RNG.nextInt(9) - 4), false, loudest);
                for (ServerPlayer p : inside(run)) Announce.title(p, "§4§lNOISE 75%", "§7The city is waking - another Warden digs up!", 5, 60, 10);
            }
            case 3 -> {
                if (run.ropeShaft < 0) dropRope(run);
                run.ropeDeadline = ticks + ROPE_PULL_SECONDS * 20L;
                for (ServerPlayer p : inside(run)) {
                    Announce.title(p, "§4§lNOISE 90%", "§7Wren is pulling the rope up - " + ROPE_PULL_SECONDS + " seconds!", 5, 70, 10);
                    Lobby.msg(p, "§6[Captain Wren] §fThat's too loud - I'm hauling the rope up! §7Get to it in " + ROPE_PULL_SECONDS
                            + " seconds (anyone can climb out now, keeping their things). Still down there after that, and it's over.");
                }
            }
            default -> lose(run, "the city woke (noise 100%)");
        }
    }

    private static ServerPlayer loudest(Run run) {
        ServerPlayer best = null;
        double v = -1;
        for (ServerPlayer p : inside(run)) {
            if (DOWNED.containsKey(p.getUUID())) continue;
            double n = run.loud.getOrDefault(p.getUUID(), 0.0);
            if (n > v) {
                v = n;
                best = p;
            }
        }
        return best;
    }

    /** Footsteps and landings (every 5 ticks). */
    private static void movement(Run run) {
        for (ServerPlayer p : inside(run)) {
            UUID id = p.getUUID();
            double[] last = run.lastPos.put(id, new double[]{p.getX(), p.getZ()});
            if (DOWNED.containsKey(id) || run.layout.inLift(p.getX(), p.getY(), p.getZ())) continue;
            // Landing (a jump or a fall).
            double fd = p.fallDistance;
            if (!p.onGround()) {
                if (fd > 0.6) run.falling.put(id, fd);
                continue;
            }
            if (run.falling.remove(id) != null) noise(run, p, LAND, null);
            if (last == null) continue;
            double dx = p.getX() - last[0], dz = p.getZ() - last[1];
            double dist = Math.sqrt(dx * dx + dz * dz);
            if (dist < 0.05 || dist > 3) continue;
            boolean crouch = p.isShiftKeyDown();
            BlockState feet = p.level().getBlockState(BlockPos.containing(p.getX(), p.getY(), p.getZ()));
            boolean carpet = BuiltInRegistries.BLOCK.getKey(feet.getBlock()).getPath().endsWith("carpet");
            double per;
            if (carpet) per = crouch ? CARPET_CROUCH : CARPET_WALK;
            else if (crouch) {
                per = HUSH_CROUCH[Math.max(0, Math.min(3, CityMarket.hushLevel(p)))];
                Long t = run.tonicUntil.get(id);
                if (t != null && ticks < t) per *= 0.5;
            } else per = WALK;
            if (per > 0) noise(run, p, per * dist, null);
        }
    }

    /** Sensors and shriekers going off (every 10 ticks). */
    private static void sculk(Run run) {
        ServerLevel l = level();
        if (l == null) return;
        for (BlockPos s : run.layout.sensors) {
            if (!l.isLoaded(s)) continue;
            BlockState st = l.getBlockState(s);
            boolean now = st.hasProperty(BlockStateProperties.SCULK_SENSOR_PHASE) && st.getValue(BlockStateProperties.SCULK_SENSOR_PHASE) == SculkSensorPhase.ACTIVE;
            Boolean before = run.sensing.put(s.asLong(), now);
            if (now && (before == null || !before)) noise(run, nearest(run, s, 16), SENSOR, s);
        }
        for (BlockPos s : run.layout.shriekers) {
            if (!l.isLoaded(s)) continue;
            BlockState st = l.getBlockState(s);
            boolean now = st.hasProperty(BlockStateProperties.SHRIEKING) && st.getValue(BlockStateProperties.SHRIEKING);
            Boolean before = run.shrieking.put(s.asLong(), now);
            if (now && (before == null || !before)) noise(run, nearest(run, s, 24), SHRIEK, s);
        }
    }

    private static ServerPlayer nearest(Run run, BlockPos at, double range) {
        ServerPlayer best = null;
        double bd = range * range;
        for (ServerPlayer p : inside(run)) {
            double d = p.blockPosition().distSqr(at);
            if (d < bd) {
                bd = d;
                best = p;
            }
        }
        return best;
    }

    // =====================================================================
    // Ticking
    // =====================================================================

    private static void fastTick() {
        for (Muster m : new ArrayList<>(MUSTERS)) if (ticks >= m.deadline) depart(m);
        ServerLevel l = level();
        for (Run run : new ArrayList<>(RUNS)) {
            if (run.building) {
                if (run.layout.builder.tick()) arrive(run);
                else if (ticks % 20 == 0) for (UUID id : run.crew) {
                    ServerPlayer m = server.getPlayerList().getPlayer(id);
                    if (m != null) Announce.actionBar(m, "§3Lowering the lift... §f" + Math.round(run.layout.builder.progress() * 100) + "%");
                }
                continue;
            }
            if (run.over || l == null) continue;
            CityWardens.fastTick(l, run);
            rattles(l, run);
            if (ticks % 5 == 0) movement(run);
            if (run.over) continue;
            if (ticks % 10 == 0) {
                CityWardens.tick(l, run, ticks);
                sculk(run);
                if (run.over) continue;
                thrown(l, run);
                // Escape: the relic carrier at the rope.
                if (run.ropeShaft >= 0) {
                    BlockPos shaft = run.layout.shafts.get(run.ropeShaft);
                    for (ServerPlayer p : inside(run)) {
                        if (DOWNED.containsKey(p.getUUID()) || !run.layout.atShaft(shaft, p.getX(), p.getY(), p.getZ())) continue;
                        if (hasRelic(p) && run.relicTaken) {
                            escape(run, p);
                            break;
                        }
                        if (run.ropeDeadline > 0) extract(p, "§6[Captain Wren] §fGot you! §7(No relic, no reward - but you keep what you carry.)");
                        else if (ticks % 40 == 0) tip(p, "§6Wren's rope §7- the relic carrier has to reach it.");
                    }
                    if (run.over) continue;
                }
                meterBar(run);
            }
        }
        tickDowned();
    }

    private static void meterBar(Run run) {
        int pct = run.pct();
        StringBuilder bar = new StringBuilder();
        String col = pct >= 90 ? "§4" : pct >= 75 ? "§c" : pct >= 50 ? "§6" : pct >= 25 ? "§e" : "§a";
        for (int i = 0; i < 10; i++) bar.append(i < pct / 10 ? col + "▮" : "§8▯");
        long left = Math.max(0, run.timeLimit - (ticks - run.startTick) / 20);
        String rope = run.ropeDeadline > 0 ? " §8| §4ROPE " + Math.max(0, (run.ropeDeadline - ticks) / 20) + "s" : run.relicTaken ? " §8| §eRelic taken" : "";
        for (ServerPlayer m : inside(run)) {
            if (DOWNED.containsKey(m.getUUID())) continue;
            Announce.actionBar(m, "§3Noise " + bar + " " + col + pct + "% §8| §f" + (left / 60) + ":" + String.format("%02d", left % 60) + rope);
        }
    }

    /** Bone Rattles in flight (every tick): where one lands, nearby Wardens go to look. */
    private static void rattles(ServerLevel l, Run run) {
        if (run.rattles.isEmpty()) return;
        for (Iterator<Map.Entry<UUID, Vec3>> it = run.rattles.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, Vec3> en = it.next();
            Entity e = l.getEntity(en.getKey());
            if (e != null && e.isAlive()) {
                en.setValue(e.position());
                continue;
            }
            it.remove();
            BlockPos at = BlockPos.containing(en.getValue());
            l.playSound(null, at, SoundEvents.SKELETON_HURT, SoundSource.BLOCKS, 1.2f, 0.5f);
            l.sendParticles(ParticleTypes.SCULK_SOUL, at.getX() + 0.5, at.getY() + 0.5, at.getZ() + 0.5, 8, 0.3, 0.3, 0.3, 0.02);
            CityWardens.rattle(l, run, at, ticks);
        }
    }

    /** The dropped relic and Echo Lenses (every 10 ticks). */
    private static void thrown(ServerLevel l, Run run) {
        // The relic on the ground: 5 noise where it first lands.
        int[] b = run.layout.box();
        AABB box = new AABB(b[0], AncientCityMap.FLOOR_Y - 10, b[1], b[2], AncientCityMap.FLOOR_Y + AncientCityMap.DOME_HEIGHT + 2, b[3]);
        if (run.relicTaken) for (ItemEntity ie : l.getEntitiesOfClass(ItemEntity.class, box, ie -> Stacks.is(ie.getItem(), RELIC_TAG))) {
            if (ie.onGround() && run.relicItems.add(ie.getUUID())) noise(run, nearest(run, ie.blockPosition(), 32), THROW, ie.blockPosition());
            else if (!ie.onGround()) run.relicItems.remove(ie.getUUID());
        }
        // Echo Lenses.
        for (Iterator<Object[]> it = run.lenses.iterator(); it.hasNext(); ) {
            Object[] ln = it.next();
            if (ticks > (long) ln[1]) {
                it.remove();
                continue;
            }
            ServerPlayer p = server.getPlayerList().getPlayer((UUID) ln[0]);
            if (p == null || !run.inside.contains(p.getUUID())) {
                it.remove();
                continue;
            }
            for (BlockPos s : run.layout.sensors)
                if (s.distSqr(p.blockPosition()) <= 144) l.sendParticles(ParticleTypes.GLOW, s.getX() + 0.5, s.getY() + 0.7, s.getZ() + 0.5, 4, 0.25, 0.2, 0.25, 0.0);
        }
    }

    private static void tick(Run run) {
        if (run.over || run.building) return;
        ServerLevel l = level();
        long left = run.timeLimit - (ticks - run.startTick) / 20;
        if (left <= 0) {
            for (ServerPlayer p : inside(run)) extract(p, "§6[Captain Wren] §fTime's up! I'm pulling you up, relic or not.");
            endRun(run, "time ran out");
            return;
        }
        if (left <= 300 && run.warned < 1 || left <= 60 && run.warned < 2) {
            run.warned = left <= 60 ? 2 : 1;
            for (ServerPlayer m : inside(run)) Lobby.msg(m, "§e§l" + (left <= 60 ? "1 MINUTE" : "5 MINUTES") + " LEFT! §7Wren pulls everyone up when time runs out (you keep what you carry).");
        }
        // The rope being hauled up (90%).
        if (run.ropeDeadline > 0) {
            long r = (run.ropeDeadline - ticks) / 20;
            if (r == 30 || r == 10) for (ServerPlayer m : inside(run)) Announce.title(m, "§4§l" + r + " SECONDS", "§7to reach Wren's rope!", 0, 30, 5);
            if (ticks >= run.ropeDeadline) {
                lose(run, "the rope was pulled up");
                return;
            }
        }
        // Rules every second: no sprinting (food at 6), no night vision, the relic carrier is slow.
        for (ServerPlayer p : inside(run)) {
            if (p.hasEffect(MobEffects.NIGHT_VISION)) {
                p.removeEffect(MobEffects.NIGHT_VISION);
                tip(p, "§7Night vision doesn't work in the Ancient City.");
            }
            int food = p.getFoodData().getFoodLevel();
            if (food > 6 && ticks - run.startTick > 40) noise(run, p, EAT, null);
            p.getFoodData().setFoodLevel(6);
            p.getFoodData().setSaturation(0f);
            if (hasRelic(p)) p.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 40, 0, false, false));
        }
        // Decay: 1 point every 20 seconds of silence, never below a milestone already passed.
        if (ticks >= run.nextDecay) {
            run.nextDecay = ticks + DECAY_SECONDS * 20L;
            if (ticks - run.lastNoise >= DECAY_SECONDS * 20L && run.milestone < MILESTONES.length) {
                double floor = run.milestone == 0 ? 0 : run.cap * MILESTONES[run.milestone - 1] / 100.0;
                run.noise = Math.max(floor, run.noise - 1);
            }
        }
        // Darkness pulses (25%+), shriekers going off by themselves (75%+).
        if (run.nextDarkness >= 0 && ticks >= run.nextDarkness) {
            run.nextDarkness = ticks + DARKNESS_EVERY * 20L;
            for (ServerPlayer p : inside(run)) if (!run.layout.inLift(p.getX(), p.getY(), p.getZ())) p.addEffect(new MobEffectInstance(MobEffects.DARKNESS, 60, 0, false, false));
        }
        if (run.nextSelfShriek >= 0 && ticks >= run.nextSelfShriek && l != null && !run.layout.shriekers.isEmpty()) {
            run.nextSelfShriek = ticks + SELF_SHRIEK_EVERY * 20L;
            BlockPos s = run.layout.shriekers.get(RNG.nextInt(run.layout.shriekers.size()));
            l.playSound(null, s, SoundEvents.SCULK_SHRIEKER_SHRIEK, SoundSource.BLOCKS, 3f, 1f);
            l.sendParticles(ParticleTypes.SCULK_SOUL, s.getX() + 0.5, s.getY() + 1, s.getZ() + 0.5, 20, 0.3, 0.6, 0.3, 0.05);
            for (ServerPlayer p : inside(run)) if (p.blockPosition().distSqr(s) < 24 * 24) p.addEffect(new MobEffectInstance(MobEffects.DARKNESS, 100, 0, false, false));
            CityWardens.lookAt(l, run, s, ticks);
        }
        // The relic's heartbeat.
        if (run.relicTaken && ticks >= run.nextHeartbeat && l != null) {
            run.nextHeartbeat = ticks + HEARTBEAT_SECONDS[run.diff] * 20L;
            for (ServerPlayer p : inside(run))
                if (hasRelic(p)) {
                    l.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.WARDEN_HEARTBEAT, SoundSource.PLAYERS, 1.2f, 1.4f);
                    noise(run, p, HEART, null);
                    CityWardens.heartbeat(l, run, p, ticks);
                }
        }
        // The relic's Warden.
        if (run.relicWardenAt > 0 && ticks >= run.relicWardenAt && l != null) {
            run.relicWardenAt = -1;
            CityWardens.W w = CityWardens.spawn(l, run, run.layout.center.offset(RNG.nextBoolean() ? 10 : -10, 0, RNG.nextInt(9) - 4), false, null);
            for (ServerPlayer p : inside(run)) {
                if (w != null && hasRelic(p)) CityWardens.investigate(w, p.blockPosition(), ticks);
                Lobby.msg(p, "§4§lA Warden is climbing out by the frame! §7Quietly now...");
                p.level().playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.WARDEN_EMERGE, SoundSource.HOSTILE, 1.5f, 1f);
            }
        }
        // The rope's chain sparkles so it can be found.
        if (run.ropeShaft >= 0 && l != null) {
            BlockPos s = run.layout.shafts.get(run.ropeShaft);
            l.sendParticles(ParticleTypes.END_ROD, s.getX() + 0.5, s.getY() + 1.5, s.getZ() + 0.5, 6, 0.2, 1.5, 0.2, 0.01);
        }
    }

    // =====================================================================
    // The rope, losing
    // =====================================================================

    /** Wren drops the rope at a random shaft; recovery compasses become rope compasses. */
    private static void dropRope(Run run) {
        ServerLevel l = level();
        if (l == null || run.ropeShaft >= 0 || run.layout.shafts.isEmpty()) return;
        run.ropeShaft = RNG.nextInt(run.layout.shafts.size());
        BlockPos s = run.layout.shafts.get(run.ropeShaft);
        BlockState chain = Blocks.IRON_CHAIN.defaultBlockState();
        for (int y = 1; y < AncientCityMap.HEIGHT; y++) l.setBlock(s.above(y), chain, Block.UPDATE_ALL);
        l.playSound(null, s, SoundEvents.IRON_DOOR_OPEN, SoundSource.BLOCKS, 2f, 0.7f);
        for (ServerPlayer p : inside(run)) {
            boolean got = toRopeCompass(p, s);
            Lobby.msg(p, "§6[Captain Wren] §fThe lift's jammed! I've dropped a rope down one of the old shafts. §7"
                    + (got ? "Your compass points to it now." : "§cYou have no compass - stay close to someone who does!"));
        }
    }

    private static ItemStack ropeCompass(BlockPos s) {
        ItemStack c = Stacks.tag(new ItemStack(Items.COMPASS), ROPE_COMPASS_TAG);
        c.set(DataComponents.LODESTONE_TRACKER, new LodestoneTracker(Optional.of(GlobalPos.of(DIM, s)), false));
        return Stacks.named(c, "§6§lWren's Rope", "§7Points to Captain Wren's rope.", "§8(Turns back into your recovery compass when you leave.)");
    }

    /** Swaps one recovery compass for a compass pointing at the rope. */
    private static boolean toRopeCompass(ServerPlayer p, BlockPos s) {
        Inventory inv = p.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack st = inv.getItem(i);
            if (Stacks.is(st, ROPE_COMPASS_TAG)) return true;
            if (st.is(Items.RECOVERY_COMPASS) && !Stacks.is(st, EndRewards.CHARM_TAG)) {
                st.shrink(1);
                ItemStack rc = ropeCompass(s);
                if (st.isEmpty()) inv.setItem(i, rc);
                else EndRewards.give(p, rc);
                return true;
            }
        }
        return false;
    }

    /** Rope compasses turn back into recovery compasses (leaving the city). */
    private static void restoreCompass(ServerPlayer p) {
        Inventory inv = p.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++)
            if (Stacks.is(inv.getItem(i), ROPE_COMPASS_TAG)) inv.setItem(i, new ItemStack(Items.RECOVERY_COMPASS, inv.getItem(i).getCount()));
    }

    /** The trip is lost: everyone still inside loses everything (a Recovery Charm saves its holder). */
    private static void lose(Run run, String why) {
        if (run.over) return;
        for (ServerPlayer p : inside(run)) {
            UUID id = p.getUUID();
            DOWNED.remove(id);
            PROTECTED.remove(id);
            boolean saved = EndRewards.useCharm(p);
            if (saved) {
                removeRelicIfCarried(p, run);
                restoreCompass(p);
            } else {
                p.getInventory().clearContent();
                for (EquipmentSlot slot : EquipmentSlot.values()) {
                    if (slot.getType() == EquipmentSlot.Type.HAND && slot != EquipmentSlot.OFFHAND) continue;
                    p.setItemSlot(slot, ItemStack.EMPTY);
                }
            }
            p.removeAllEffects();
            p.setHealth(p.getMaxHealth());
            p.getFoodData().setFoodLevel(20);
            run.inside.remove(id);
            sendHome(p, run);
            Announce.title(p, "§4§lTHE CITY WOKE", saved ? "§bYour Recovery Charm saved your things" : "§cEverything you carried is lost", 10, 80, 20);
            Lobby.msg(p, "§4§lTrip lost §7(" + why + "). " + (saved ? "§bYour Recovery Charm broke - you kept everything." : "§cEverything you carried is gone."));
        }
        endRun(run, why);
    }

    // =====================================================================
    // Chests, urns, the vault plug
    // =====================================================================

    private static void openChest(ServerPlayer p, Run run, BlockPos pos) {
        if (DOWNED.containsKey(p.getUUID())) return;
        ServerLevel l = (ServerLevel) p.level();
        l.gameEvent(p, GameEvent.CONTAINER_OPEN, pos);
        l.playSound(null, pos, SoundEvents.CHEST_OPEN, SoundSource.BLOCKS, 0.6f, 0.9f);
        if (pos.equals(run.layout.vaultChest)) {
            if (!run.vaultLooted.add(p.getUUID())) {
                tip(p, "§7You've already emptied the vault.");
                return;
            }
            noise(run, p, CHEST, pos);
            vaultLoot(p, run);
            return;
        }
        AncientCityMap.House h = run.layout.houseAt(pos);
        if (h == null || h.kind == 2) return;
        Set<UUID> got = run.looted.computeIfAbsent(pos.asLong(), k -> new HashSet<>());
        if (!got.add(p.getUUID())) {
            tip(p, "§7You've already searched this chest.");
            return;
        }
        if (h.kind == 1) {
            run.decoyOpened.add(p.getUUID());
            p.addEffect(new MobEffectInstance(MobEffects.DARKNESS, 120, 0, false, false));
            l.playSound(null, pos, SoundEvents.SCULK_SHRIEKER_SHRIEK, SoundSource.BLOCKS, 1.5f, 0.6f);
            Lobby.msg(p, "§8§lA DECOY! §7Cobwebs and dust - and a shriek that echoes through the city. §8(The candles here were unlit.)");
            noise(run, p, DECOY, pos);
            return;
        }
        noise(run, p, CHEST, pos);
        List<ItemStack> loot = chestLoot(l, run.diff);
        StringBuilder sb = new StringBuilder();
        for (ItemStack s : loot) {
            if (sb.length() > 0) sb.append("§7, ");
            sb.append("§f").append(s.getCount() > 1 ? s.getCount() + " " : "").append(s.getHoverName().getString());
            EndRewards.give(p, s);
        }
        Lobby.msg(p, "§3Chest: " + (sb.length() == 0 ? "§7nothing but dust" : sb.toString()));
        if (roll(SHARD_CHEST[run.diff])) CityMarket.giveShard(p, CityMarket.randomShard(RNG));
    }

    private static boolean roll(int pct) {
        return RNG.nextInt(100) < pct;
    }

    private static boolean rollMille(int permille) {
        return RNG.nextInt(1000) < permille;
    }

    /** A Hush book from a {low%, high%} table, or nothing. */
    private static ItemStack hushRoll(int[] chances, int[] levels) {
        int r = RNG.nextInt(100);
        if (r < chances[1]) return CityMarket.hushBook(server, levels[1]);
        if (r < chances[0] + chances[1]) return CityMarket.hushBook(server, levels[0]);
        return ItemStack.EMPTY;
    }

    /** What ONE player finds in ONE real house chest. */
    static List<ItemStack> chestLoot(ServerLevel level, int d) {
        List<ItemStack> out = new ArrayList<>();
        int echoRolls = new int[]{2, 2, 3, 3}[d], echoPct = new int[]{25, 35, 40, 50}[d];
        int echo = 0;
        for (int i = 0; i < echoRolls; i++) if (roll(echoPct)) echo++;
        if (echo > 0) out.add(new ItemStack(Items.ECHO_SHARD, echo));
        out.add(new ItemStack(Items.SCULK, 4 + RNG.nextInt(7)));
        out.add(new ItemStack(Items.AMETHYST_SHARD, 2 + RNG.nextInt(5)));
        if (roll(new int[]{50, 60, 70, 80}[d])) out.add(new ItemStack(Items.EXPERIENCE_BOTTLE, 2 + RNG.nextInt(4)));
        if (roll(40)) out.add(new ItemStack(EndRewards.item("candle"), 2 + RNG.nextInt(3)));
        if (roll(30)) out.add(new ItemStack(Items.GLOW_BERRIES, 3 + RNG.nextInt(6)));
        if (roll(30)) out.add(new ItemStack(Items.SCULK_SENSOR));
        if (roll(new int[]{5, 8, 12, 16}[d])) out.add(new ItemStack(Items.SCULK_CATALYST));
        if (roll(new int[]{3, 5, 8, 12}[d])) out.add(new ItemStack(Items.SCULK_SHRIEKER));
        if (roll(new int[]{15, 20, 25, 30}[d])) out.add(new ItemStack(Items.DISC_FRAGMENT_5, 1 + RNG.nextInt(2)));
        if (roll(new int[]{30, 40, 50, 60}[d])) out.add(new ItemStack(Items.GOLDEN_APPLE));
        if (roll(new int[]{2, 3, 5, 8}[d])) out.add(new ItemStack(Items.ENCHANTED_GOLDEN_APPLE));
        if (roll(new int[]{25, 35, 45, 55}[d])) out.add(new ItemStack(Items.DIAMOND, 1 + RNG.nextInt(2)));
        if (roll(new int[]{10, 14, 18, 24}[d])) {
            int lvl = d == 0 ? 1 : d == 1 ? 2 : d == 2 ? 2 + RNG.nextInt(2) : 3;
            out.add(EndRewards.book(level.getServer(), Enchantments.SWIFT_SNEAK, lvl));
        }
        if (roll(new int[]{5, 8, 12, 16}[d])) {
            ItemStack legs = new ItemStack(Items.DIAMOND_LEGGINGS);
            ExpeditionLoot.enchant(level, legs, Enchantments.SWIFT_SNEAK, 1 + Math.min(2, d));
            ExpeditionLoot.enchant(level, legs, Enchantments.PROTECTION, 1 + RNG.nextInt(3));
            out.add(legs);
        }
        ItemStack hush = hushRoll(HUSH_CHEST[d], HUSH_LEVELS[d]);
        if (!hush.isEmpty()) out.add(hush);
        if (rollMille(WARD_CHEST[d])) out.add(EndRewards.trim("ward"));
        if (rollMille(SILENCE_CHEST[d])) out.add(EndRewards.trim("silence"));
        return out;
    }

    /** The Deep Vault chest (once per player). Its City Cash is paid only if you escape. */
    private static void vaultLoot(ServerPlayer p, Run run) {
        int d = run.diff;
        List<ItemStack> loot = new ArrayList<>();
        loot.add(new ItemStack(Items.ECHO_SHARD, 3 + RNG.nextInt(4)));
        if (roll(WARD_VAULT[d])) loot.add(EndRewards.trim("ward"));
        if (roll(SILENCE_VAULT[d])) loot.add(EndRewards.trim("silence"));
        ItemStack hush = hushRoll(HUSH_VAULT[d], HUSH_LEVELS[d]);
        if (!hush.isEmpty()) loot.add(hush);
        if (roll(10)) loot.add(new ItemStack(Items.MUSIC_DISC_5));
        StringBuilder sb = new StringBuilder();
        for (ItemStack s : loot) {
            if (sb.length() > 0) sb.append("§7, ");
            sb.append("§f").append(s.getCount() > 1 ? s.getCount() + " " : "").append(s.getHoverName().getString());
            EndRewards.give(p, s);
        }
        Announce.title(p, "§3§lTHE DEEP VAULT", "§b+" + VAULT_CC[d] + " City Cash if you escape", 5, 60, 10);
        Lobby.msg(p, "§3§lDeep Vault: " + sb + " §7- and §b" + VAULT_CC[d] + " City Cash §7waiting for you if you get out.");
        CityMarket.giveShard(p, CityMarket.randomShard(RNG));
    }

    private static void breakUrn(ServerPlayer p, Run run, BlockPos pos) {
        ServerLevel l = (ServerLevel) p.level();
        if (!run.urnsLeft.remove(pos.asLong())) return;
        l.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        l.playSound(null, pos, SoundEvents.DECORATED_POT_SHATTER, SoundSource.BLOCKS, 1f, 0.9f);
        l.sendParticles(ParticleTypes.SCULK_SOUL, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, 6, 0.2, 0.2, 0.2, 0.02);
        int[] cash = URN_CASH[run.diff];
        long money = cash[0] + RNG.nextInt(cash[1] - cash[0] + 1);
        Economy.add(server, p.getUUID(), p.getName().getString(), money);
        run.urnsBroken.merge(p.getUUID(), 1, Integer::sum);
        StringBuilder sb = new StringBuilder("§6Urn: " + Economy.format(money));
        if (roll(10)) { EndRewards.give(p, new ItemStack(Items.ECHO_SHARD)); sb.append("§7, an echo shard"); }
        if (roll(20)) { EndRewards.give(p, new ItemStack(Items.EXPERIENCE_BOTTLE, 1 + RNG.nextInt(3))); sb.append("§7, XP"); }
        if (roll(20)) { EndRewards.give(p, new ItemStack(Items.GLOW_BERRIES, 2 + RNG.nextInt(3))); sb.append("§7, glow berries"); }
        tip(p, sb.toString());
        noise(run, p, URN, pos);
    }

    /** A sonic boom from w at p: every plug block along its path (seen from above) breaks. */
    private static void boom(Run run, Warden w, ServerPlayer p) {
        ServerLevel l = level();
        if (l == null || run.layout.plug.isEmpty()) return;
        double ax = w.getX(), az = w.getZ(), bx = p.getX(), bz = p.getZ();
        double vx = bx - ax, vz = bz - az, len2 = vx * vx + vz * vz;
        if (len2 < 1e-6) return;
        int broken = 0;
        for (Long k : new ArrayList<>(run.layout.plug)) {
            BlockPos b = BlockPos.of(k);
            double cx = b.getX() + 0.5, cz = b.getZ() + 0.5;
            double t = Math.max(0, Math.min(1, ((cx - ax) * vx + (cz - az) * vz) / len2));
            double dx = ax + t * vx - cx, dz = az + t * vz - cz;
            if (dx * dx + dz * dz > 0.85 * 0.85) continue;
            run.layout.plug.remove(k);
            l.setBlock(b, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            l.sendParticles(ParticleTypes.SONIC_BOOM, cx, b.getY() + 0.5, cz, 1, 0, 0, 0, 0);
            broken++;
        }
        if (broken > 0) {
            noise(run, p, BOOM, BlockPos.containing(bx, p.getY(), bz));
            for (ServerPlayer m : inside(run)) Lobby.msg(m, "§3§lThe Warden's boom cracks the Deep Vault plug open! §7(" + broken + " blocks)");
        }
    }

    // =====================================================================
    // The relic, escaping
    // =====================================================================

    private static ItemStack relicItem() {
        return Stacks.tag(Stacks.named(new ItemStack(Items.ECHO_SHARD), "§3§lThe Ancient Relic",
                "§7Bring it to Captain Wren's rope!", "§7(Your compass points there.)", "§8It beats like a heart..."), RELIC_TAG);
    }

    static boolean hasRelic(ServerPlayer p) {
        Inventory inv = p.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) if (Stacks.is(inv.getItem(i), RELIC_TAG)) return true;
        return false;
    }

    private static void removeRelics(ServerPlayer p) {
        Inventory inv = p.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) if (Stacks.is(inv.getItem(i), RELIC_TAG)) inv.setItem(i, ItemStack.EMPTY);
    }

    private static int countTagged(ServerPlayer p, String tag) {
        Inventory inv = p.getInventory();
        int n = 0;
        for (int i = 0; i < inv.getContainerSize(); i++) if (Stacks.is(inv.getItem(i), tag)) n += inv.getItem(i).getCount();
        return n;
    }

    private static void takeRelic(ServerPlayer p, Run run) {
        if (run.relicTaken || DOWNED.containsKey(p.getUUID())) return;
        ServerLevel l = (ServerLevel) p.level();
        BlockPos pos = run.layout.relic;
        run.relicTaken = true;
        l.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        l.gameEvent(p, GameEvent.BLOCK_DESTROY, pos);
        EndRewards.give(p, relicItem());
        l.sendParticles(ParticleTypes.SCULK_SOUL, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, 30, 0.4, 0.4, 0.4, 0.05);
        l.playSound(null, pos, SoundEvents.SCULK_CATALYST_BLOOM, SoundSource.BLOCKS, 1.5f, 0.6f);
        for (ServerPlayer m : inside(run)) {
            Announce.title(m, "§3§lTHE RELIC IS TAKEN", "§7" + p.getName().getString() + " has it - follow the compass to Wren's rope!", 5, 60, 10);
            m.level().playSound(null, m.getX(), m.getY(), m.getZ(), SoundEvents.WARDEN_NEARBY_CLOSER, SoundSource.HOSTILE, 1f, 1f);
        }
        run.relicWardenAt = ticks + RELIC_WARDEN_SECONDS[run.diff] * 20L;
        run.nextHeartbeat = ticks + HEARTBEAT_SECONDS[run.diff] * 20L;
        dropRope(run);
    }

    /** The relic reached the rope: everyone still in the city escapes with the reward. */
    private static void escape(Run run, ServerPlayer carrier) {
        if (run.over || run.escaped) return;
        run.escaped = true;
        int d = run.diff, pct = run.pct();
        int seconds = (int) ((ticks - run.startTick) / 20);
        boolean silent = pct <= SILENT_PCT;
        for (ServerPlayer m : inside(run)) {
            if (DOWNED.containsKey(m.getUUID())) continue;
            UUID id = m.getUUID();
            removeRelics(m);
            boolean sweep = run.layout.realCount() > 0 && !run.decoyOpened.contains(id);
            if (sweep) for (AncientCityMap.House h : run.layout.houses)
                if (h.kind == 0 && !run.looted.getOrDefault(h.chest.asLong(), Set.of()).contains(id)) sweep = false;
            // City Cash.
            int cc = ESCAPE_CC[d];
            StringBuilder parts = new StringBuilder("§7escape " + ESCAPE_CC[d]);
            if (silent) {
                int s = (int) Math.round(ESCAPE_CC[d] * SILENT_BONUS);
                cc += s;
                parts.append(", silent +").append(s);
            }
            if (sweep) {
                cc += SWEEP_CC[d];
                parts.append(", clean sweep +").append(SWEEP_CC[d]);
            }
            if (run.vaultLooted.contains(id)) {
                cc += VAULT_CC[d];
                parts.append(", vault +").append(VAULT_CC[d]);
            }
            if (CityMarket.firstEscapeThisWeek(id)) {
                int w = (int) Math.round(cc * WEEKLY_BONUS);
                cc += w;
                parts.append(", first this week +").append(w);
            }
            if (CityMarket.firstStamp(id, run.map, d)) {
                cc += FIRST_STAMP_CC;
                parts.append(", first map ").append(run.map + 1).append(" stamp +").append(FIRST_STAMP_CC);
            }
            CityMarket.earn(id, cc, "escapes");
            StringBuilder got = new StringBuilder("§3§lESCAPED! §7You got §b" + CityMarket.fmt(cc) + " City Cash §8(" + parts + "§8)");
            EndRewards.give(m, new ItemStack(Items.ECHO_SHARD, ESCAPE_ECHO[d]));
            got.append("§7, §3").append(ESCAPE_ECHO[d]).append(" echo shards");
            if (rollMille(WARD_ESCAPE[d])) {
                EndRewards.give(m, EndRewards.trim("ward"));
                got.append("§7, §bthe Ward trim");
            }
            if (rollMille(SILENCE_ESCAPE[d])) {
                EndRewards.give(m, EndRewards.trim("silence"));
                got.append("§7, §d§lthe Silence trim!");
            }
            if (roll(SNEAK_BOOK_CHANCE[d])) {
                EndRewards.give(m, EndRewards.book(server, Enchantments.SWIFT_SNEAK, 3));
                got.append("§7, a Swift Sneak III book");
            }
            if (roll(HUSH_ESCAPE[d])) {
                int lvl = roll(HUSH_ESCAPE_HIGH[d]) ? HUSH_ESCAPE_LEVELS[d][1] : HUSH_ESCAPE_LEVELS[d][0];
                EndRewards.give(m, CityMarket.hushBook(server, lvl));
                got.append("§7, §3a Hush ").append(lvl == 1 ? "I" : lvl == 2 ? "II" : "III").append(" book");
            }
            ItemStack disc = CityMarket.escapeDisc(d, RNG);
            if (!disc.isEmpty()) {
                got.append("§7, a disc (§f").append(disc.getHoverName().getString()).append("§7)");
                EndRewards.give(m, disc);
            }
            int k = ESCAPE_KEY_TIER[d];
            Keys.reward(server, id, k, 1, "(Ancient City)");
            got.append("§7, ").append(Keys.COLORS[k]).append(Keys.NAMES[k]).append(" Key");
            Tokens.add(id, Tokens.SHARDS, ESCAPE_SHARDS[d]);
            got.append("§7, §d").append(ESCAPE_SHARDS[d]).append(" Key Shards");
            BattlePass.addPassXp(m, ESCAPE_PASS_XP[d]);
            Levels.addXp(server, m, ESCAPE_XP[d]);
            got.append("§7, §b").append(ESCAPE_PASS_XP[d]).append(" pass XP");
            if (d == 3) {
                PlayerProfile prof = PlayerCache.get(m);
                if (prof.owned.add(SILENT_TITLE)) {
                    prof.touch();
                    got.append("§7, §3§lthe title ").append(Progression.cosmeticName(SILENT_TITLE));
                }
            }
            PlayerStats.add(m, "city_win", 1);
            Charts.stamp(m, "city_" + d);
            Lobby.msg(m, got.toString());
            Lobby.msg(m, "§7Noise at the rope: §f" + pct + "%" + (silent ? " §a(silent!)" : "") + " §7· time §f" + CityMarket.time(seconds) + (sweep ? " §7· §aclean sweep" : ""));
            CityMarket.recordEscape(m, d, seconds, pct);
            CityMarket.checkRequests(m, new CityMarket.EscapeFacts(d, pct, sweep, run.vaultLooted.contains(id), seconds,
                    run.urnsBroken.getOrDefault(id, 0), !run.anyDown));
        }
        server.getPlayerList().broadcastSystemMessage(Component.literal("§3§l✦ §e" + names(run) + " §7escaped a " + DIFF_COLORS[d] + DIFF_NAMES[d]
                + " §7Ancient City with the relic §8(" + CityMarket.time(seconds) + ", noise " + pct + "%)§7!"), false);
        for (ServerPlayer m : inside(run)) if (!DOWNED.containsKey(m.getUUID())) extract(m, "§6[Captain Wren] §fUp the rope - and not a squeak!");
        endRun(run, "escaped with the relic");
    }

    static void goHome(ServerPlayer p) {
        Run run = runOf(p.getUUID());
        if (run == null || run.layout == null) {
            Lobby.msg(p, "§7You're not in the Ancient City.");
            return;
        }
        if (!run.layout.inLift(p.getX(), p.getY(), p.getZ())) {
            Lobby.msg(p, "§7Walk back to §6Wren's lift §7first.");
            return;
        }
        if (hasRelic(p)) {
            Lobby.msg(p, "§6[Captain Wren] §fThe lift's jammed with that relic aboard! §7Take it to my rope instead.");
            return;
        }
        extract(p, "§6[Captain Wren] §fHome early? Fair enough, captain.");
    }

    // =====================================================================
    // Going home, dying, revives
    // =====================================================================

    private static void sendHome(ServerPlayer p, Run run) {
        Islands.Island own = Islands.islandOf(p.getUUID());
        Islands.Island is = own != null ? own : run == null ? null : Expedition.islandById(run.islandId);
        if (is != null) Islands.teleportHome(p, is);
        else Lobby.goToLobby(p);
    }

    /** Home with everything they carry (except the relic). */
    static void extract(ServerPlayer p, String msg) {
        Run run = runOf(p.getUUID());
        UUID id = p.getUUID();
        DOWNED.remove(id);
        PROTECTED.remove(id);
        removeRelicIfCarried(p, run);
        restoreCompass(p);
        if (run != null) run.inside.remove(id);
        p.removeEffect(MobEffects.DARKNESS);
        p.removeEffect(MobEffects.SLOWNESS);
        p.getFoodData().setFoodLevel(20);
        sendHome(p, run);
        p.resetFallDistance();
        p.clearFire();
        Lobby.msg(p, msg + " §7Everything you're carrying is yours.");
        if (run != null && !run.over && run.inside.isEmpty()) endRun(run, "everyone went home");
    }

    /** Leaving without escaping: the relic stays behind (back on its pedestal). */
    private static void removeRelicIfCarried(ServerPlayer p, Run run) {
        if (!hasRelic(p)) return;
        removeRelics(p);
        if (run != null && !run.over && !run.escaped) {
            ServerLevel l = level();
            if (l != null) l.setBlock(run.layout.relic, catalyst(), Block.UPDATE_ALL);
            run.relicTaken = false;
            for (ServerPlayer m : inside(run)) if (m != p) Lobby.msg(m, "§3The relic went back to its pedestal.");
        }
    }

    private static BlockState catalyst() {
        return BuiltInRegistries.BLOCK.getValue(Identifier.withDefaultNamespace("sculk_catalyst")).defaultBlockState();
    }

    private static void dropAll(ServerPlayer p, ServerLevel level, Vec3 at) {
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
        if (saved) {
            removeRelicIfCarried(p, run); // the charm keeps your things, but not the relic
            restoreCompass(p);
        } else dropAll(p, level, p.position()); // the relic drops with everything else - a teammate can grab it
        p.setHealth(p.getMaxHealth());
        p.clearFire();
        p.removeAllEffects();
        p.getFoodData().setFoodLevel(20);
        p.resetFallDistance();
        p.setDeltaMovement(0, 0, 0);
        if (run != null) {
            run.inside.remove(id);
            for (ServerPlayer m : inside(run))
                Lobby.msg(m, "§c" + p.getName().getString() + " " + why + "! §7" + (saved ? "Their Recovery Charm saved their things." : "Their things are where they fell."));
        }
        Lobby.runLater(() -> Safe.run("city send home", () -> {
            if (p.hasDisconnected()) return;
            sendHome(p, run);
            Lobby.msg(p, saved ? "§b§lYour Recovery Charm broke §7- you " + why + " but kept everything you carried."
                    : "§c§lYou " + why + " in the Ancient City §7and lost everything you were carrying.");
        }));
        if (run != null && !run.over && run.inside.isEmpty()) endRun(run, "nobody is left");
    }

    private static void loggedOut(ServerPlayer p) {
        Run run = runOf(p.getUUID());
        if (run == null || !isCity(p.level())) {
            Muster mu = musterOf(p.getUUID());
            if (mu != null && !mu.leader.equals(p.getUUID()) && mu.paid.remove(p.getUUID())) refund(p.getUUID(), mu.diff, mu.paidCc.remove(p.getUUID()));
            return;
        }
        DOWNED.remove(p.getUUID());
        PROTECTED.remove(p.getUUID());
        boolean safe = run.layout != null && run.layout.inLift(p.getX(), p.getY(), p.getZ());
        if (!safe && !EndRewards.useCharm(p)) {
            dropAll(p, (ServerLevel) p.level(), p.position());
            for (ServerPlayer m : inside(run)) if (m != p) Lobby.msg(m, "§c" + p.getName().getString() + " left the city - their things are where they were.");
        } else {
            removeRelicIfCarried(p, run);
            restoreCompass(p);
        }
        p.getFoodData().setFoodLevel(20);
        run.inside.remove(p.getUUID());
        sendHome(p, run);
        if (!run.over && run.inside.isEmpty()) endRun(run, "nobody is left");
    }

    static long reviveCost(Run run) {
        return Math.round(PRICES[run.diff] * REVIVE_SHARE);
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
        run.anyDown = true;
        Downed d = new Downed(p.getX(), p.getY(), p.getZ(), team ? 0 : 1, ticks + (team ? TEAM_REVIVE_SECONDS : REVIVE_SECONDS) * 20L);
        DOWNED.put(id, d);
        p.setHealth(1f);
        p.clearFire();
        p.setDeltaMovement(0, 0, 0);
        p.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, (TEAM_REVIVE_SECONDS + REVIVE_SECONDS) * 20, 9, false, false));
        if (team) {
            Announce.title(p, "§c§lYOU'RE DOWN", "§7A teammate can pick you up (" + TEAM_REVIVE_SECONDS + "s)", 5, 80, 10);
            MutableComponent line = Component.literal("§c§lYou're down! §7A teammate has §f" + TEAM_REVIVE_SECONDS + " seconds §7to crouch next to you for "
                    + (TEAM_REVIVE_HOLD_TICKS / 20) + " seconds (the team's one pick-up this trip). ");
            if (paid) line.append(Expedition.button("§a[Revive now for " + Economy.format(reviveCost(run)) + "]", "/city revive", "Pay and get up at full health")).append(Component.literal(" "));
            line.append(Expedition.button("§c[Give up]", "/city giveup", "Die: drop everything you carry and go home"));
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
                .append(Expedition.button("§a§l[Revive for " + Economy.format(cost) + "]", "/city revive", "Pay " + Economy.format(cost) + " and get back up"))
                .append(Component.literal(" "))
                .append(Expedition.button("§c§l[Give up]", "/city giveup", "Die: drop everything you carry and go home")));
    }

    private static void standUp(ServerPlayer p, double share) {
        UUID id = p.getUUID();
        DOWNED.remove(id);
        p.removeEffect(MobEffects.SLOWNESS);
        p.clearFire();
        p.setHealth((float) Math.max(1.0, p.getMaxHealth() * share));
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
            if (p == null || run == null || !isCity(p.level())) {
                DOWNED.remove(e.getKey());
                continue;
            }
            p.clearFire();
            if (Math.abs(p.getX() - d.x) > 0.3 || Math.abs(p.getZ() - d.z) > 0.3 || p.getY() < d.y - 2) p.teleportTo(d.x, d.y, d.z);
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
            if (p != null && isCity(p.level())) {
                removeRelicIfCarried(p, run);
                restoreCompass(p);
                p.getFoodData().setFoodLevel(20);
                sendHome(p, run);
            }
        }
        for (UUID id : run.crew) DOWNED.remove(id);
        if (run.layout != null && run.layout.builder != null) run.layout.builder.release();
        if (l != null && run.layout != null) {
            int[] b = run.layout.box();
            AABB box = new AABB(b[0], AncientCityMap.FLOOR_Y - 10, b[1], b[2], AncientCityMap.FLOOR_Y + AncientCityMap.DOME_HEIGHT + 10, b[3]);
            for (Entity e : l.getEntities((Entity) null, box, e -> !(e instanceof Player))) e.discard();
            // Wipe this city back to air (a chunk at a time) so the next trip built here starts clean.
            if (!run.building || run.layout.builder.next > 0) {
                Long2ObjectOpenHashMap<BlockState> air = new Long2ObjectOpenHashMap<>();
                BlockState a = Blocks.AIR.defaultBlockState();
                for (long k : run.layout.blocks.keySet()) air.put(k, a);
                for (long k : run.placed) air.put(k, a);
                for (BlockPos s : run.layout.shafts) for (int y = 1; y < AncientCityMap.HEIGHT; y++) air.put(s.above(y).asLong(), a);
                CLEANERS.add(new AncientCityMap.Builder(l, air));
            }
        }
        Skyserver.LOGGER.info("[City] trip #{} over ({})", run.id, why);
    }

    private static void shutdown() {
        for (Muster m : new ArrayList<>(MUSTERS)) cancelMuster(m, "server stopping");
        for (Run run : new ArrayList<>(RUNS)) {
            for (ServerPlayer p : inside(run)) extract(p, "§6[Captain Wren] §fThe server is going down - up we go!");
            endRun(run, "server stopping");
        }
        for (AncientCityMap.Builder b : CLEANERS) b.release();
        CLEANERS.clear();
    }

    // =====================================================================
    // Helpers
    // =====================================================================

    static List<ServerPlayer> inside(Run run) {
        List<ServerPlayer> out = new ArrayList<>();
        if (server == null) return out;
        for (UUID id : run.inside) {
            ServerPlayer p = server.getPlayerList().getPlayer(id);
            if (p != null && isCity(p.level())) out.add(p);
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
