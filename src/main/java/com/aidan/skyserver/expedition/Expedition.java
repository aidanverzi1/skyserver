package com.aidan.skyserver.expedition;

import com.aidan.skyserver.Safe;
import com.aidan.skyserver.Skyserver;
import com.aidan.skyserver.bots.BotPlayer;
import com.aidan.skyserver.island.Islands;
import com.aidan.skyserver.lobby.*;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.protocol.game.ClientboundInitializeBorderPacket;
import net.minecraft.network.protocol.game.ClientboundSetActionBarTextPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.RandomizableContainer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/**
 * The Nether Expedition, a paid co-op minigame that starts on your Sky Island (5.8; reworked 5.9 - 5.17).
 *
 *  1. 5.20: talk to Captain Wren in his ship room -> Nether Expedition: pay the $5,000 deposit (or use a
 *     ticket). (5.8 - 5.19 an airship came to the island; it's gone - the crew gathers in the ship room.)
 *  2. Gear up with your OWN armor, weapons and food. Faction mates come to the room with /expedition board.
 *  3. Pick the difficulty at Wren's helm menu (the deposit counts toward the price) and
 *     Fortress / Bastion / Surprise, read the warning, set sail.
 *  4. Wren flies you (~10 seconds while the map is built) to a custom arena map floating over a lava sea
 *     (ExpeditionRooms): Wren's Landing in the middle (safe, unbreakable, no mobs; talk to Wren there to go
 *     home), chest arenas on the platforms around it (3/4/5/6 by difficulty). Walk into an arena and the
 *     first wave comes: 3 waves on Easy/Normal, 4 on Hard/Nightmare, the next one on a 40-second timer even
 *     if the last isn't dead. Archers take the balconies, a captain leads the last wave, and every arena has
 *     a twist (ExpeditionTwists). If you leave a fight your health can't go up until you walk back in.
 *     Beat every wave and the chest opens. After every arena (Easy/Normal) or all but one (Hard/Nightmare)
 *     the Boss Arena's gate opens (ExpeditionBoss). Beat it for the Treasure Vault.
 *  5. Break and build freely (except Wren's Landing) - everything you carry comes home.
 *  6. DYING LOSES EVERYTHING YOU CARRIED (it drops where you died; teammates can grab it). Totems work.
 *
 * The expedition world is wiped every server start. Nothing about runs is saved. Nothing spawns there
 * naturally (IslandSpawnMixin), and any mob that isn't ours is removed as it appears (5.14).
 * 5.15: one paid revive per player per trip (20% of the price); time limit 30 / 35 / 45 / 60 minutes.
 *
 * 5.17:
 *  - CO-OP: up to 3 players. Island members AND the caller's faction mates can board (/expedition board).
 *    When the captain sets sail, everyone else on the deck gets 30 seconds to join: each pays the FULL price
 *    (or uses a ticket of that difficulty or better); the captain keeps the deposit discount. Anyone who
 *    doesn't pay stays behind. Everyone gets the full rewards: their own loot in every chest and the vault
 *    (ExpeditionChests), the vault money, key and shards.
 *  - Scaling by crew (1 / 2 / 3): guards x1 / x2 / x2.5, time +0 / +25% / +40%, boss health x1 / x1.75 / x2.3,
 *    one more captain (75% health) per extra player. At most 24 guards alive in an arena: the ones that
 *    don't fit become extra health for the ones that do. Guards each chase the player nearest to THEM.
 *  - Twists: Gloom, Volatile, War Banner, Swarm, Rain of Fire and Soul Shield join Ambush and Withering.
 *  - Wither skeletons no longer wither you by themselves: only a Withering arena does. Never in the Boss Arena.
 *  - TEAMMATE REVIVE: once per trip for the whole team. A downed player first gets 30 seconds in which a
 *    teammate can crouch next to them for 5 seconds (back up at half health); if nobody does, the paid
 *    revive offer follows (30 more seconds, full health, once per player). A failed attempt uses nothing.
 *  - A gold-trading piglin stands in Wren's Landing (ExpeditionBarter).
 *  - The one-block gaps where bridges meet the open plazas are filled.
 */
public final class Expedition {
    public static final ResourceKey<Level> DIM = ResourceKey.create(Registries.DIMENSION, Identifier.fromNamespaceAndPath(Skyserver.MOD_ID, "expedition"));

    // ---- Every number for the expedition (rewards: ExpeditionLoot, twists: ExpeditionTwists, boss: ExpeditionBoss) ----
    public static final String[] DIFF_NAMES = Tokens.DIFF_NAMES;
    public static final String[] DIFF_COLORS = {"§a", "§e", "§c", "§4"};
    public static final long[] PRICES = {5_000, 10_000, 20_000, 40_000};
    /** Chest rooms per trip. */
    static final int[] ROOMS = {3, 4, 5, 6};
    /** Guards in each wave for ONE player (5.18: each extra player adds CREW_EXTRA_GUARDS to every wave but the last). */
    static final int[][] WAVES = {{3, 4, 5}, {4, 5, 6}, {4, 5, 6, 7}, {5, 6, 7, 8}};
    /** Seconds until the next wave comes anyway (Ambush arenas: 5 fewer). */
    static final int[] WAVE_SECONDS = {40, 40, 40, 40};
    /** Old 5.10 guard counts (no longer used for waves; kept so nothing that reads it breaks). */
    static final int[] GUARDS = {3, 4, 6, 8};
    /** Extra health / damage for every guard (0.5 = +50%). */
    static final double[] HEALTH = {0.2, 0.4, 0.75, 1.3}, DAMAGE = {0.1, 0.3, 0.6, 1.0};
    /** The last wave's captain gets this much more health on top (2.0 = +200%). */
    static final double[] CAPTAIN_HEALTH = {2.0, 2.5, 3.0, 3.5};
    /** 5.17 crew scaling, by crew size (1, 2, 3 players). */
    static final int CREW_MAX = 3;
    static final double[] TIME_MULT = {1.0, 1.25, 1.4};
    /**
     * 5.18 crew scaling, for EACH extra player: this many more guards in every wave except the last (the
     * last wave gets an extra captain instead), this much more guard health and damage (0.70 = +70%), and
     * this much longer until the next wave (0.25 = +25%). Captains and the boss are not touched by these.
     */
    static final int CREW_EXTRA_GUARDS = 1;
    static final double CREW_HEALTH = 0.70, CREW_DAMAGE = 0.10, CREW_WAVE_TIME = 0.25;
    /** 5.17: the extra captain each extra player brings has this share of a captain's health. */
    static final double EXTRA_CAPTAIN_HEALTH = 0.75;
    /** Most guards alive in one arena, by difficulty; the rest of a wave becomes extra health instead. */
    static final int[] ROOM_GUARD_CAPS = {15, 15, 18, 24};
    /** 5.17: seconds the rest of the deck has to pay and join once the captain sets sail. */
    static final int MUSTER_SECONDS = 30;
    /** The old twist names (5.17: all twists are in ExpeditionTwists; kept so anything that reads these compiles). */
    static final int AMBUSH = ExpeditionTwists.AMBUSH, WITHERING = ExpeditionTwists.WITHERING;
    static final String[] TWIST_NAMES = ExpeditionTwists.NAMES, TWIST_TIPS = ExpeditionTwists.TIPS;
    /** Chance (%) that a guard's hit in a Withering arena withers you. */
    static final int WITHER_CHANCE = 50;
    /** Seconds a guard may spend outside its room before it's sent back. */
    static final int LEASH_SECONDS = 3;
    /** The time limit by difficulty for one player (Easy 30, Normal 35, Hard 45, Nightmare 60 minutes). */
    static final int[] TIME_LIMITS = {30 * 60, 35 * 60, 45 * 60, 60 * 60};
    static final int TIME_LIMIT_SECONDS = TIME_LIMITS[0];
    /** The paid revive: its price as a share of the trip's price, seconds to decide, and ticks of protection afterwards. */
    static final double REVIVE_SHARE = 0.20;
    static final int REVIVE_SECONDS = 30, REVIVE_PROTECT_TICKS = 100;
    /** 5.17 teammate revive: seconds a teammate has, ticks they must crouch, how close, and the health you get back. */
    static final int TEAM_REVIVE_SECONDS = 30, TEAM_REVIVE_HOLD_TICKS = 100;
    static final double TEAM_REVIVE_RANGE = 2.5, TEAM_REVIVE_HEALTH = 0.5;
    /** An airship nobody sails within this many seconds leaves and the deposit/ticket comes back. */
    static final int SHIP_WAIT_SECONDS = 10 * 60;
    /** Quitting within this many seconds of being hurt counts as dying. */
    static final int COMBAT_LOG_SECONDS = 15;
    static final String GUARD_TAG = "skyserver_expguard";
    public static final String NETHER_WREN_TAG = "skyserver_expwren";
    /** How many stray-mob removals are written to the log per server start. */
    static final int STRAY_LOG_LIMIT = 40;

    /** A called airship that hasn't sailed yet. */
    static final class Pending {
        UUID payer;
        int ticket = -1;        // ticket difficulty used to call it, or -1 = paid the deposit
        int structure = 2;      // 0 Fortress, 1 Bastion, 2 Surprise
        long since;
        boolean sailing = false;
        /** 5.17: the muster. diff = the difficulty the captain chose (-1 = not setting sail yet). */
        int diff = -1;
        UUID captain;
        long musterEnd;
        /** Crew who paid to join: player -> the ticket difficulty they used, or -1 = money. */
        final Map<UUID, Integer> joined = new LinkedHashMap<>();
    }

    /** One expedition in progress. */
    static final class Run {
        int islandId, diff;
        boolean fortress;
        UUID leader;
        final Set<UUID> crew = new LinkedHashSet<>();
        final Set<UUID> inside = new LinkedHashSet<>();
        /** Players who already used their one PAID revive on this trip. */
        final Set<UUID> revived = new HashSet<>();
        /** 5.17: the team's one teammate revive is gone. */
        boolean teamReviveUsed = false;
        /** 5.17: players who landed (1-3), and the time limit that gives (seconds). */
        int crewSize = 1, timeLimit = TIME_LIMITS[0];
        /** 5.17: twists this trip already had, gold each player traded, scrap each player's room chests gave. */
        final Set<Integer> usedTwists = new HashSet<>();
        final Map<UUID, Integer> bartered = new HashMap<>();
        final Map<UUID, int[]> scrap = new HashMap<>();
        long startTick;
        BlockPos landing;
        double cx, cz;
        int half;
        ExpeditionRooms.Layout layout;
        UUID wren, piglin;
        int cleared = 0, unlockAt = 0, kills = 0;
        boolean arenaOpen = false;
        UUID boss;
        boolean bossSpawned = false, bossDead = false, over = false;
        BlockPos vault;
        int warned = 0;
        /** The map is being built (the crew waits on the airship), then they arrive. */
        boolean building = true;
        long buildStart;
        ExpeditionBoss.Fight fight;
    }

    /** 5.15 / 5.17: a player who is down. phase 0 = waiting for a teammate, 1 = the paid offer. */
    private static final class Downed {
        final Run run;
        final double x, z;
        long deadline;
        int phase, hold = 0;

        Downed(Run run, double x, double z, int phase, long deadline) {
            this.run = run;
            this.x = x;
            this.z = z;
            this.phase = phase;
            this.deadline = deadline;
        }
    }

    private static MinecraftServer server;
    private static final Map<Integer, ExpeditionShip.Ship> SHIPS = new HashMap<>();
    private static final Map<Integer, Pending> PENDING = new HashMap<>();
    private static final Map<Integer, Run> RUNS = new HashMap<>();
    private static final Map<UUID, Run> PLAYER_RUN = new HashMap<>();
    /** mob -> {run, room (ExpeditionRooms.Room)} for chest-room guards, the boss and its minions. */
    private static final Map<UUID, Object[]> GUARD_OF = new HashMap<>();
    private static final Map<UUID, Integer> OUTSIDE = new HashMap<>(); // guard -> seconds spent outside its room
    private static final Map<UUID, Long> LAST_HURT = new HashMap<>();
    private static final Map<UUID, Long> LAST_TIP = new HashMap<>();
    /** Health lock: player -> {fight they walked out of -> {health, absorption} when they left}. */
    private static final Map<UUID, Map<ExpeditionRooms.Room, float[]>> HEALTH_LOCK = new HashMap<>();
    /** The active fights each player was standing in last tick. */
    private static final Map<UUID, Set<ExpeditionRooms.Room>> IN_FIGHT = new HashMap<>();
    /** Each woken arena's twist, when its next wave is due (server tick), and big magma cubes about to split. */
    private static final Map<ExpeditionRooms.Room, Integer> TWIST = new HashMap<>();
    private static final Map<ExpeditionRooms.Room, Long> WAVE_DUE = new HashMap<>();
    private static final Map<ExpeditionRooms.Room, Integer> SPLITTING = new HashMap<>();
    private static final Map<UUID, Downed> DOWNED = new HashMap<>();
    /** Player -> server tick until which nothing can hurt them (just revived). */
    private static final Map<UUID, Long> PROTECTED = new HashMap<>();
    /** 5.17: player -> server tick until which a wither effect on them is meant to be there (a Withering arena). */
    private static final Map<UUID, Long> WITHER_OK = new HashMap<>();
    private static int runCounter = 0;
    private static long ticks = 0;
    private static int strayLogs = 0;
    private static final Random RNG = new Random();

    private Expedition() {}

    public static boolean isExpedition(Level l) {
        return l != null && l.dimension() == DIM;
    }

    public static boolean isShipWren(Entity e) {
        return e != null && e.entityTags().contains(ExpeditionShip.WREN_TAG);
    }

    public static boolean hasShip(Islands.Island is) {
        return is != null && SHIPS.containsKey(is.id);
    }

    static ExpeditionShip.Ship shipOf(Islands.Island is) {
        return is == null ? null : SHIPS.get(is.id);
    }

    static Pending pendingOf(Islands.Island is) {
        return is == null ? null : PENDING.get(is.id);
    }

    static boolean running(Islands.Island is) {
        return is != null && RUNS.containsKey(is.id);
    }

    static ServerLevel level() {
        return server == null ? null : server.getLevel(DIM);
    }

    /** 5.20: sameFaction for other packages (the ship room). */
    public static boolean sameFactionPublic(UUID a, UUID b) {
        return sameFaction(a, b);
    }

    /** 5.17: are these two players in the same faction? */
    static boolean sameFaction(UUID a, UUID b) {
        if (a == null || b == null) return false;
        if (a.equals(b)) return true;
        String fa = Factions.factionOf(a), fb = Factions.factionOf(b);
        return fa != null && fb != null && !fa.isEmpty() && fa.equalsIgnoreCase(fb);
    }

    // =====================================================================
    // Setup
    // =====================================================================

    public static void init() {
        EndExpedition.init(); // 5.18: the End Expedition (its own listeners, registered first like ours)
        ExpeditionGold.init(); // 5.19: the 5 trapped gold blocks on the bridges
        AncientCity.init();    // 5.19: the Ancient City Expedition (registered before Lobby, like the End)
        SunkenMonument.init(); // 5.20: the Sunken Monument Expedition
        com.aidan.skyserver.island.WrenRoom.init(); // 5.20: Captain Wren's ship room (before Islands' own handlers)
        // Fresh Nether every start: delete the expedition world before it loads.
        ServerLifecycleEvents.SERVER_STARTING.register(s -> Safe.run("expedition wipe", () -> { try { wipe(s); } catch (java.io.IOException e) { Skyserver.LOGGER.warn("Couldn't wipe the Nether Expedition world: {}", e.toString()); } }));
        ServerLifecycleEvents.SERVER_STARTED.register(s -> {
            server = s;
            if (level() == null) Skyserver.LOGGER.error("Nether Expedition dimension skyserver:expedition is missing (resources/data/skyserver/dimension/expedition.json).");
            guardCommands(s);
        });
        ServerLifecycleEvents.END_DATA_PACK_RELOAD.register((s, rm, ok) -> guardCommands(s));
        ServerLifecycleEvents.SERVER_STOPPING.register(s -> Safe.run("expedition stop", Expedition::shutdown));
        ServerTickEvents.END_SERVER_TICK.register(s -> {
            ticks++;
            if (!RUNS.isEmpty()) Safe.run("expedition fast tick", Expedition::fastTick);
            if (ticks % 20 == 0) Safe.run("expedition tick", Expedition::tick);
        });
        // Dying in the Nether: everything you carried drops there, you wake up on your island.
        ServerLivingEntityEvents.ALLOW_DEATH.register((entity, source, amount) -> {
            if (!(entity instanceof ServerPlayer p) || p instanceof BotPlayer || !isExpedition(p.level())) return true;
            if (p.getMainHandItem().is(Items.TOTEM_OF_UNDYING) || p.getOffhandItem().is(Items.TOTEM_OF_UNDYING)) return true; // the totem saves them
            // Already down - nothing can finish them off.
            if (DOWNED.containsKey(p.getUUID())) {
                p.setHealth(1f);
                return false;
            }
            // They go down if a teammate could still revive them or they could pay; otherwise it's a normal death.
            Safe.run("expedition death", () -> {
                if (!goDown(p)) died(p, "died");
            });
            return false;
        });
        // Wren's Landing is safe; guards can only be hurt by someone standing in their room.
        ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> {
            if (!isExpedition(entity.level())) return true;
            if (entity instanceof ServerPlayer p) {
                // Down, or just revived: nothing hurts them.
                if (DOWNED.containsKey(p.getUUID())) return false;
                Long safeUntil = PROTECTED.get(p.getUUID());
                if (safeUntil != null) {
                    if (ticks < safeUntil) return false;
                    PROTECTED.remove(p.getUUID());
                }
                Run run = PLAYER_RUN.get(p.getUUID());
                return run == null || run.layout == null || !run.layout.spawn.inside(p.getX(), p.getY(), p.getZ(), 0.3);
            }
            Object[] g = GUARD_OF.get(entity.getUUID());
            if (g == null) return true;
            Run gr = (Run) g[0];
            if (gr.fight != null && gr.fight.body == entity && gr.fight.shielded) {
                if (source.getEntity() instanceof ServerPlayer sp) tip(sp, "§6The Tyrant's shield is up! §fBreak the 3 gold pillars.");
                return false;
            }
            Entity attacker = source.getEntity();
            // Guards, minions and the boss never hurt each other (stray fireballs, arrows, eruptions).
            if (attacker != null && attacker != entity && GUARD_OF.containsKey(attacker.getUUID())) return false;
            if (!(attacker instanceof ServerPlayer ap)) return true; // lava, falling etc. still hurt
            ExpeditionRooms.Room room = (ExpeditionRooms.Room) g[1];
            if (!room.inside(ap.getX(), ap.getY(), ap.getZ(), 1.0)) {
                tip(ap, "§cStep inside the room to fight its guards!");
                return false;
            }
            // 5.17 Soul Shield: the first hit on a guard only breaks its shield.
            return !ExpeditionTwists.blocksHit((ServerLevel) entity.level(), entity, ap);
        });
        ServerLivingEntityEvents.AFTER_DAMAGE.register((entity, source, base, taken, blocked) -> {
            if (!isExpedition(entity.level())) return;
            Safe.run("expedition after damage", () -> afterDamage(entity, source.getEntity(), taken));
        });
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
            if (!isExpedition(entity.level())) return;
            Safe.run("expedition mob death", () -> mobDied(entity, source.getEntity()));
        });
        // The little magma cubes a guard splits into become guards of the same arena. Any OTHER mob that
        // isn't ours is removed a tick after it appears (and logged).
        ServerEntityEvents.ENTITY_LOAD.register((entity, world) -> {
            if (!isExpedition(world) || !(entity instanceof Mob m) || GUARD_OF.containsKey(entity.getUUID())) return;
            if (ours(m)) return;
            if (entity.getType() == net.minecraft.world.entity.EntityTypes.MAGMA_CUBE) {
                Safe.run("expedition split", () -> adoptSplit(m));
                return;
            }
            Lobby.runLaterTicks(1, () -> Safe.run("expedition stray", () -> {
                if (m.isRemoved() || ours(m)) return;
                removeStray(m, "as it appeared");
            }));
        });
        UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
            if (!isExpedition(level) || level.isClientSide() || !(player instanceof ServerPlayer sp)) return InteractionResult.PASS;
            return useBlock(sp, (ServerLevel) level, hit.getBlockPos());
        });
        UseEntityCallback.EVENT.register((player, level, hand, entity, hit) -> {
            if (!isExpedition(level)) return InteractionResult.PASS;
            boolean wren = entity.entityTags().contains(NETHER_WREN_TAG), trader = entity.entityTags().contains(ExpeditionBarter.PIGLIN_TAG);
            if (!wren && !trader) return InteractionResult.PASS;
            if (hand == net.minecraft.world.InteractionHand.MAIN_HAND && player instanceof ServerPlayer sp) {
                if (wren) talkToWren(sp);
                else {
                    Run run = PLAYER_RUN.get(sp.getUUID());
                    if (run != null) Safe.run("expedition barter", () -> ExpeditionBarter.trade(sp, run, server, RNG));
                }
            }
            return InteractionResult.SUCCESS;
        });
        AttackEntityCallback.EVENT.register((player, level, hand, entity, hit) ->
                isExpedition(level) && (entity.entityTags().contains(NETHER_WREN_TAG) || entity.entityTags().contains(ExpeditionBarter.PIGLIN_TAG))
                        ? InteractionResult.FAIL : InteractionResult.PASS);
        PlayerBlockBreakEvents.BEFORE.register((level, player, pos, state, be) -> {
            if (!isExpedition(level)) return true;
            Run run = PLAYER_RUN.get(player.getUUID());
            if (run == null || run.layout == null) return true;
            if (run.fight != null && run.fight.isPillar(pos)) {
                run.fight.breakPillar(pos);
                return false;
            }
            // 5.17: a war banner comes down (nothing drops).
            if (player instanceof ServerPlayer sp && ExpeditionTwists.breakBanner((ServerLevel) level, pos, sp, run)) return false;
            if (run.layout.locked.contains(pos.asLong())) {
                tip((ServerPlayer) player, run.layout.spawn.inside(pos.getX(), pos.getY(), pos.getZ(), 2)
                        ? "§7Wren's Landing is built to last - it can't be broken." : "§7The Boss Arena's walls are enchanted - they can't be broken.");
                return false;
            }
            // 5.17: the chests hold everyone's own loot - they can't be broken at all.
            if (pos.equals(run.vault)) {
                tip((ServerPlayer) player, "§7The Treasure Vault can't be broken - right-click it for your share.");
                return false;
            }
            for (ExpeditionRooms.Room r : run.layout.rooms) {
                if (!pos.equals(r.chest)) continue;
                tip((ServerPlayer) player, r.cleared ? "§7Right-click the chest for your own loot." : "§cThat chest is guarded - beat the room's guards first.");
                return false;
            }
            return true;
        });
        // Registered before Lobby's (Skyserver init order), so items drop before anything saves the inventory.
        ServerPlayConnectionEvents.DISCONNECT.register((handler, s) -> Safe.run("expedition logout", () -> loggedOut(handler.player)));
        CommandRegistrationCallback.EVENT.register((d, r, e) -> d.register(Commands.literal("expedition")
                .executes(c -> { status(c.getSource().getPlayerOrException()); return 1; })
                .then(Commands.literal("board").executes(c -> { board(c.getSource().getPlayerOrException()); return 1; }))
                .then(Commands.literal("leaveship").executes(c -> { leaveShip(c.getSource().getPlayerOrException()); return 1; }))
                .then(Commands.literal("join").executes(c -> { join(c.getSource().getPlayerOrException(), false); return 1; }))
                .then(Commands.literal("jointicket").executes(c -> { join(c.getSource().getPlayerOrException(), true); return 1; }))
                .then(Commands.literal("sail").executes(c -> { sailNow(c.getSource().getPlayerOrException()); return 1; }))
                .then(Commands.literal("leave").executes(c -> { leaveCommand(c.getSource().getPlayerOrException()); return 1; }))
                .then(Commands.literal("stay").executes(c -> {
                    Lobby.msg(c.getSource().getPlayerOrException(), "§6[Captain Wren] §fRight you are! I'll keep the kettle on. Come back when you're ready.");
                    return 1;
                }))
                .then(Commands.literal("revive").executes(c -> { revive(c.getSource().getPlayerOrException()); return 1; }))
                .then(Commands.literal("giveup").executes(c -> { giveUp(c.getSource().getPlayerOrException()); return 1; }))
                .then(Commands.literal("help").executes(c -> { howItWorks(c.getSource().getPlayerOrException()); return 1; }))
                .then(Commands.literal("call").executes(c -> { ExpeditionMenu.openSummon(c.getSource().getPlayerOrException()); return 1; }))
                .then(Commands.literal("admin").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .then(Commands.literal("endall").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS)).executes(c -> {
                            int n = endAll("ended by an admin");
                            c.getSource().sendSuccess(() -> Component.literal("Ended " + n + " expedition(s)."), true);
                            return 1;
                        }))
                        .then(Commands.literal("ticket").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                                .then(Commands.argument("difficulty", IntegerArgumentType.integer(0, 3)).executes(c -> {
                                    ServerPlayer p = c.getSource().getPlayerOrException();
                                    int dd = IntegerArgumentType.getInteger(c, "difficulty");
                                    Tokens.add(p.getUUID(), Tokens.TICKET[dd], 1);
                                    c.getSource().sendSuccess(() -> Component.literal("Gave yourself a " + DIFF_NAMES[dd] + " expedition ticket."), false);
                                    return 1;
                                }))))));
    }

    /** Is this mob one of the expedition's own (a recorded or tagged guard / boss / helper, Captain Wren, or the gold trader)? */
    /** 5.19: the map of the trip this player is on (null if none, or still being planned). */
    static ExpeditionRooms.Layout layoutOf(ServerPlayer p) {
        Run run = PLAYER_RUN.get(p.getUUID());
        return run == null ? null : run.layout;
    }

    /** 5.19: the difficulty of the trip this player is on, or -1. */
    static int diffOf(ServerPlayer p) {
        Run run = PLAYER_RUN.get(p.getUUID());
        return run == null ? -1 : run.diff;
    }

    private static boolean ours(Mob m) {
        return GUARD_OF.containsKey(m.getUUID()) || m.entityTags().contains(GUARD_TAG) || m.entityTags().contains(NETHER_WREN_TAG)
                || m.entityTags().contains(ExpeditionBarter.PIGLIN_TAG) || m.entityTags().contains(ExpeditionGold.TRAP_TAG);
    }

    /** Takes a mob that isn't ours out of the expedition world and says so in the log (first STRAY_LOG_LIMIT only). */
    private static void removeStray(Mob m, String when) {
        if (strayLogs < STRAY_LOG_LIMIT) {
            strayLogs++;
            Skyserver.LOGGER.info("[Expedition] removed a stray {} at {} {} {} ({}){}", m.getType().getDescriptionId(),
                    Math.round(m.getX()), Math.round(m.getY()), Math.round(m.getZ()), when,
                    strayLogs == STRAY_LOG_LIMIT ? " - no more of these will be logged until the next restart" : "");
        }
        m.discard();
    }

    /** Ends every running expedition (everyone keeps what they carry). Also used by /mod endexpeditions. */
    public static int endAll(String why) {
        int n = RUNS.size();
        for (Run run : new ArrayList<>(RUNS.values())) endRun(run, why);
        return n;
    }

    private static void wipe(MinecraftServer s) throws IOException {
        Path dir = s.getWorldPath(LevelResource.ROOT).resolve("dimensions").resolve(Skyserver.MOD_ID).resolve("expedition");
        if (!Files.isDirectory(dir)) return;
        try (var walk = Files.walk(dir)) {
            List<Path> all = new ArrayList<>(walk.toList());
            all.sort(Comparator.reverseOrder());
            for (Path p : all) Files.deleteIfExists(p);
        }
        Skyserver.LOGGER.info("Nether Expedition world wiped (fresh terrain this session).");
    }

    // ---- /lobby can't be used to escape with the loot ----

    private static final Set<String> BLOCKED = Set.of("lobby", "hub", "spawn", "survival", "l");

    private record Blocked(com.mojang.brigadier.Command<net.minecraft.commands.CommandSourceStack> inner)
            implements com.mojang.brigadier.Command<net.minecraft.commands.CommandSourceStack> {
        @Override
        public int run(com.mojang.brigadier.context.CommandContext<net.minecraft.commands.CommandSourceStack> c)
                throws com.mojang.brigadier.exceptions.CommandSyntaxException {
            ServerPlayer p = c.getSource().getPlayer();
            if (p != null && (isExpedition(p.level()) || EndExpedition.isEnd(p.level()) || AncientCity.isCity(p.level()) || SunkenMonument.isSunken(p.level()))) {
                c.getSource().sendFailure(Component.literal("You're on an expedition! Talk to Captain Wren to go home."));
                return 0;
            }
            return inner.run(c);
        }
    }

    @SuppressWarnings("unchecked")
    private static void guardCommands(MinecraftServer s) {
        try {
            var root = s.getCommands().getDispatcher().getRoot();
            java.lang.reflect.Field cmd = com.mojang.brigadier.tree.CommandNode.class.getDeclaredField("command");
            cmd.setAccessible(true);
            for (var node : root.getChildren()) {
                if (!BLOCKED.contains(node.getName())) continue;
                Deque<com.mojang.brigadier.tree.CommandNode<net.minecraft.commands.CommandSourceStack>> todo = new ArrayDeque<>();
                todo.add(node);
                Set<Object> seen = new HashSet<>();
                while (!todo.isEmpty()) {
                    var n = todo.poll();
                    if (!seen.add(n)) continue;
                    var c = (com.mojang.brigadier.Command<net.minecraft.commands.CommandSourceStack>) cmd.get(n);
                    if (c != null && !(c instanceof Blocked)) cmd.set(n, new Blocked(c));
                    todo.addAll(n.getChildren());
                }
            }
        } catch (Throwable t) {
            Skyserver.LOGGER.warn("Couldn't block /lobby during expeditions: {}", t.toString());
        }
    }

    // =====================================================================
    // Calling the airship, boarding
    // =====================================================================

    /**
     * Talk to Wren -> Nether Expedition (or /expedition call): pay the deposit or use a ticket, then the helm
     * menu opens. ticket = difficulty of the ticket to use, or -1 to pay the deposit. 5.20: no airship - the
     * crew gathers in Captain Wren's ship room. True if the deposit / ticket was taken.
     */
    public static boolean summon(ServerPlayer p, int ticket) {
        Islands.Island is = Islands.islandOf(p.getUUID());
        if (is == null || !Islands.isIsland(p.level()) || Islands.islandAt(p.getX(), p.getZ()) != is) {
            Lobby.msg(p, "§cTalk to Captain Wren on your own Sky Island to start a Nether Expedition.");
            return false;
        }
        if (SHIPS.containsKey(is.id)) {
            Lobby.msg(p, "§7Your crew is already getting ready - talk to Captain Wren to choose a difficulty.");
            return false;
        }
        if (level() == null) {
            Lobby.msg(p, "§cThe Nether Expedition world didn't load - tell an admin.");
            return false;
        }
        if (ticket >= 0) {
            if (!Tokens.take(p.getUUID(), Tokens.TICKET[ticket], 1)) {
                Lobby.msg(p, "§cYou don't have a " + DIFF_NAMES[ticket] + " ticket.");
                return false;
            }
        } else if (!Economy.take(server, p.getUUID(), PRICES[0])) {
            Lobby.msg(p, "§cA Nether Expedition takes a " + Economy.format(PRICES[0]) + " deposit (it counts toward the trip).");
            return false;
        }
        ExpeditionShip.Ship ship = ExpeditionShip.place(Islands.level(), is);
        SHIPS.put(is.id, ship);
        Pending pd = new Pending();
        pd.payer = p.getUUID();
        pd.ticket = ticket;
        pd.since = ticks;
        PENDING.put(is.id, pd);
        p.level().playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.BELL_BLOCK, SoundSource.PLAYERS, 1.5f, 0.8f);
        Islands.islandChat(is, "§6§l⚓ " + p.getName().getString() + " is getting a Nether Expedition ready in Captain Wren's ship room!");
        for (ServerPlayer m : islandOnline(is)) {
            if (m == p) continue;
            m.sendSystemMessage(Component.literal("§7Put on your §farmor§7, bring §fweapons and food§7, then ")
                    .append(button("§a§l[Go to the ship room]", "/expedition board", "Join the crew in Captain Wren's ship room")));
        }
        // 5.17: faction mates can come along (each pays for their own place).
        for (ServerPlayer m : server.getPlayerList().getPlayers()) {
            if (m instanceof BotPlayer || is.isMember(m.getUUID()) || !sameFaction(p.getUUID(), m.getUUID())) continue;
            m.sendSystemMessage(Component.literal("§9" + p.getName().getString() + " §7is getting a Nether Expedition ready. Up to " + CREW_MAX
                            + " can sail, each paying their own way. Go to your island (§e/is§7), gear up, then ")
                    .append(button("§a§l[Go to their ship room]", "/expedition board", "Join your faction mate's crew")));
        }
        Lobby.msg(p, "§7" + (ticket >= 0 ? "Your " + DIFF_NAMES[ticket] + " ticket pays for the trip." : "Your " + Economy.format(PRICES[0]) + " deposit counts toward the trip.")
                + " Wren waits " + (SHIP_WAIT_SECONDS / 60) + " minutes; if nobody sets sail you get it back.");
        return true;
    }

    private static void refund(UUID id, int ticket) {
        if (ticket >= 0) Tokens.add(id, Tokens.TICKET[ticket], 1);
        else {
            ServerPlayer p = server.getPlayerList().getPlayer(id);
            Economy.add(server, id, p == null ? "?" : p.getName().getString(), PRICES[0]);
        }
    }

    /** Kept for old callers: a Nether crew is getting ready - to the helm menu. */
    public static void offerBoard(ServerPlayer p) {
        ExpeditionMenu.open(p);
    }

    /** Onto the deck (the rope, Wren by the path, or /expedition board). 5.17: your island's airship, or a faction mate's. */
    public static void board(ServerPlayer p) {
        Islands.Island is = Islands.islandOf(p.getUUID());
        ExpeditionShip.Ship ship = shipOf(is);
        if (ship == null || running(is)) {
            for (Map.Entry<Integer, Pending> e : PENDING.entrySet()) {
                Pending pd = e.getValue();
                if (pd.sailing || !sameFaction(pd.payer, p.getUUID())) continue;
                Islands.Island other = islandById(e.getKey());
                ExpeditionShip.Ship s = SHIPS.get(e.getKey());
                if (other == null || s == null) continue;
                is = other;
                ship = s;
                break;
            }
        }
        if (ship == null) {
            Lobby.msg(p, "§7No Nether crew is getting ready for you. §7Talk to Captain Wren in your ship room (§e/is shiproom§7) to start one.");
            return;
        }
        if (!Islands.isIsland(p.level())) {
            Lobby.msg(p, "§cGo to your island first (§e/is§c), then join.");
            return;
        }
        if (running(is)) {
            Lobby.msg(p, "§7The expedition has already set sail - wait for the crew to come back.");
            return;
        }
        double[] d = ship.deckSpot();
        p.teleportTo(ship.level, d[0], d[1], d[2], Set.of(), (float) d[3], 0f, true);
        p.resetFallDistance();
        if (!is.isMember(p.getUUID())) Islands.sendBorder(p, is);
        Lobby.msg(p, is.isMember(p.getUUID())
                ? "§6You're in the ship room! §7Talk to §6Captain Wren §7to choose a difficulty and set sail."
                : "§6You're in the ship room! §7When the captain sets sail you'll be asked to pay for your place. §8(/expedition leaveship to go home)");
    }

    /** The island whose airship deck this player is standing on, or null. */
    private static Islands.Island deckIsland(ServerPlayer p) {
        if (!Islands.isIsland(p.level())) return null;
        for (Map.Entry<Integer, ExpeditionShip.Ship> e : SHIPS.entrySet())
            if (e.getValue().onDeck(p.getX(), p.getY(), p.getZ())) return islandById(e.getKey());
        return null;
    }

    /** Off the deck: to their own island (or, without one, down onto this one). */
    private static void sendHome(ServerPlayer p, Islands.Island deck) {
        Islands.Island home = Islands.islandOf(p.getUUID());
        if (home == null) home = deck;
        if (home != null) Islands.teleportHome(p, home);
    }

    static void leaveShip(ServerPlayer p) {
        Islands.Island deck = deckIsland(p);
        if (deck == null) {
            Lobby.msg(p, "§7You're not in a Nether crew's ship room.");
            return;
        }
        sendHome(p, deck);
    }

    // =====================================================================
    // Setting sail
    // =====================================================================

    /** What the captain still has to pay for this difficulty (the deposit / ticket already counts). */
    static long owed(Pending pd, int diff) {
        long credit = pd.ticket >= 0 ? PRICES[pd.ticket] : PRICES[0];
        return Math.max(0, PRICES[diff] - credit);
    }

    /** Everyone on the deck right now who may sail: island members and the caller's faction mates. */
    static List<ServerPlayer> crewOnDeck(Islands.Island is) {
        ExpeditionShip.Ship ship = shipOf(is);
        List<ServerPlayer> out = new ArrayList<>();
        if (ship == null || server == null) return out;
        Pending pd = pendingOf(is);
        for (ServerPlayer m : server.getPlayerList().getPlayers()) {
            if (m instanceof BotPlayer || !Islands.isIsland(m.level()) || !ship.onDeck(m.getX(), m.getY(), m.getZ())) continue;
            if (is.isMember(m.getUUID()) || (pd != null && sameFaction(pd.payer, m.getUUID()))) out.add(m);
        }
        return out;
    }

    /**
     * The captain chose a difficulty and confirmed the warning. Alone on the deck: sail now. With others:
     * they get MUSTER_SECONDS to pay for their own place (5.17), then the airship sails with whoever paid.
     */
    static void launch(ServerPlayer leader, int diff) {
        Islands.Island is = Islands.islandOf(leader.getUUID());
        Pending pd = pendingOf(is);
        ExpeditionShip.Ship ship = shipOf(is);
        if (is == null || pd == null || ship == null || pd.sailing || running(is)) return;
        if (pd.diff >= 0) {
            Lobby.msg(leader, "§7The crew is already paying up - you sail in a moment.");
            return;
        }
        List<ServerPlayer> deck = crewOnDeck(is);
        if (!deck.contains(leader)) {
            Lobby.msg(leader, "§cStand in Captain Wren's ship room to set sail.");
            return;
        }
        long owe = owed(pd, diff);
        if (owe > 0 && Economy.balance(server, leader.getUUID()) < owe) {
            Lobby.msg(leader, "§c" + DIFF_NAMES[diff] + " costs " + Economy.format(PRICES[diff]) + " - you still need " + Economy.format(owe) + ".");
            return;
        }
        pd.diff = diff;
        pd.captain = leader.getUUID();
        pd.joined.clear();
        if (deck.size() <= 1) {
            sail(is, pd);
            return;
        }
        pd.musterEnd = ticks + MUSTER_SECONDS * 20L;
        String what = DIFF_COLORS[diff] + DIFF_NAMES[diff] + " §7expedition";
        for (ServerPlayer m : deck) {
            if (m == leader) continue;
            Lobby.msg(m, "§6[Captain Wren] §f" + leader.getName().getString() + " is setting sail for a " + what + "§f. Everyone pays for their own place - and gets their own loot and rewards. §c§lIf you die you lose everything you carry.");
            MutableComponent line = Component.literal("§7You have " + MUSTER_SECONDS + " seconds: ")
                    .append(button("§a§l[Join for " + Economy.format(PRICES[diff]) + "]", "/expedition join", "Pay " + Economy.format(PRICES[diff]) + " and come along"));
            if (ticketFor(m.getUUID(), diff) >= 0)
                line.append(Component.literal(" ")).append(button("§b§l[Use a ticket]", "/expedition jointicket", "Use a " + DIFF_NAMES[ticketFor(m.getUUID(), diff)] + " ticket instead"));
            line.append(Component.literal(" ")).append(button("§c[Stay behind]", "/expedition leaveship", "Go home"));
            m.sendSystemMessage(line);
        }
        leader.sendSystemMessage(Component.literal("§6[Captain Wren] §fThe crew has " + MUSTER_SECONDS + " seconds to pay for their places (up to " + (CREW_MAX - 1) + " can join you). ")
                .append(button("§a§l[Set sail now]", "/expedition sail", "Leave with whoever has paid")));
    }

    /** The lowest ticket this player owns that covers `diff` (that difficulty or a harder one), or -1. */
    private static int ticketFor(UUID id, int diff) {
        for (int t = diff; t < 4; t++) if (Tokens.count(id, Tokens.TICKET[t]) > 0) return t;
        return -1;
    }

    /** /expedition join | jointicket: pay for your own place on the airship you're standing on. */
    static void join(ServerPlayer p, boolean ticket) {
        Islands.Island is = deckIsland(p);
        Pending pd = pendingOf(is);
        if (pd == null || pd.diff < 0 || pd.sailing) {
            Lobby.msg(p, "§7Nobody is setting sail right now. Go to the ship room and wait for the captain.");
            return;
        }
        UUID id = p.getUUID();
        if (id.equals(pd.captain) || pd.joined.containsKey(id)) {
            Lobby.msg(p, "§7You're already on the crew.");
            return;
        }
        if (!crewOnDeck(is).contains(p)) {
            Lobby.msg(p, "§cOnly island members and the captain's faction mates can join this crew.");
            return;
        }
        if (pd.joined.size() >= CREW_MAX - 1) {
            Lobby.msg(p, "§cThe crew is full (" + CREW_MAX + " at most).");
            return;
        }
        int used = -1;
        if (ticket) {
            used = ticketFor(id, pd.diff);
            if (used < 0 || !Tokens.take(id, Tokens.TICKET[used], 1)) {
                Lobby.msg(p, "§cYou don't have a ticket for " + DIFF_NAMES[pd.diff] + " or harder.");
                return;
            }
        } else if (!Economy.take(server, id, PRICES[pd.diff])) {
            Lobby.msg(p, "§cA place on a " + DIFF_NAMES[pd.diff] + " expedition costs " + Economy.format(PRICES[pd.diff]) + ".");
            return;
        }
        pd.joined.put(id, used);
        for (ServerPlayer m : crewOnDeck(is))
            Lobby.msg(m, "§a" + p.getName().getString() + " joined the crew §7(" + (used >= 0 ? "a " + DIFF_NAMES[used] + " ticket" : Economy.format(PRICES[pd.diff])) + ").");
    }

    /** /expedition sail: the captain leaves with whoever has paid. */
    static void sailNow(ServerPlayer p) {
        Islands.Island is = Islands.islandOf(p.getUUID());
        Pending pd = pendingOf(is);
        if (pd == null || pd.diff < 0 || pd.sailing || !p.getUUID().equals(pd.captain)) {
            Lobby.msg(p, "§7Pick a difficulty with Captain Wren at the helm first.");
            return;
        }
        sail(is, pd);
    }

    /** Gives a crew member who paid to join their money or ticket back. */
    private static void refundJoin(UUID id, int usedTicket, int diff) {
        ServerPlayer p = server.getPlayerList().getPlayer(id);
        if (usedTicket >= 0) Tokens.add(id, Tokens.TICKET[usedTicket], 1);
        else Economy.add(server, id, p == null ? "?" : p.getName().getString(), PRICES[diff]);
        if (p != null) Lobby.msg(p, "§7You didn't sail - your " + (usedTicket >= 0 ? "ticket" : Economy.format(PRICES[diff])) + " is back.");
    }

    /** The muster is off: everyone who paid to join gets it back. */
    private static void cancelMuster(Islands.Island is, Pending pd, String why) {
        int diff = pd.diff;
        for (Map.Entry<UUID, Integer> j : pd.joined.entrySet()) refundJoin(j.getKey(), j.getValue(), diff);
        pd.joined.clear();
        pd.diff = -1;
        pd.captain = null;
        if (is != null && why != null) for (ServerPlayer m : crewOnDeck(is)) Lobby.msg(m, "§6[Captain Wren] §fWe're not sailing just yet (" + why + ").");
    }

    /** The airship leaves with the captain and everyone who paid and is still on the deck. */
    private static void sail(Islands.Island is, Pending pd) {
        int diff = pd.diff;
        ServerPlayer leader = pd.captain == null ? null : server.getPlayerList().getPlayer(pd.captain);
        List<ServerPlayer> deck = crewOnDeck(is);
        if (leader == null || !deck.contains(leader)) {
            cancelMuster(is, pd, "the captain left the deck");
            return;
        }
        long owe = owed(pd, diff);
        if (owe > 0 && !Economy.take(server, leader.getUUID(), owe)) {
            Lobby.msg(leader, "§c" + DIFF_NAMES[diff] + " costs " + Economy.format(PRICES[diff]) + " - you still need " + Economy.format(owe) + ".");
            cancelMuster(is, pd, "the captain couldn't pay");
            return;
        }
        List<ServerPlayer> crew = new ArrayList<>();
        crew.add(leader);
        for (Map.Entry<UUID, Integer> j : new ArrayList<>(pd.joined.entrySet())) {
            ServerPlayer m = server.getPlayerList().getPlayer(j.getKey());
            if (m != null && deck.contains(m) && crew.size() < CREW_MAX) crew.add(m);
            else {
                refundJoin(j.getKey(), j.getValue(), diff);
                pd.joined.remove(j.getKey());
            }
        }
        pd.sailing = true;
        boolean fortress = pd.structure == 0 || (pd.structure == 2 && RNG.nextBoolean());
        for (ServerPlayer m : crew) Lobby.msg(m, "§6[Captain Wren] §fHold on tight! Setting a course for the Nether...");
        Run run = null;
        try {
            run = startRun(is, diff, fortress, leader, crew);
            if (run == null && !fortress) run = startRun(is, diff, true, leader, crew);
            if (run == null && fortress) run = startRun(is, diff, false, leader, crew);
        } catch (RuntimeException e) {
            Skyserver.LOGGER.error("Nether Expedition: couldn't start the run", e);
        }
        if (run == null) {
            pd.sailing = false;
            if (owe > 0) Economy.add(server, leader.getUUID(), leader.getName().getString(), owe);
            cancelMuster(is, pd, null);
            Lobby.msg(leader, "§cWren couldn't find a safe place to land this time - try again.");
            return;
        }
        // Whoever else is in the room didn't pay: they stay behind (visitors from other islands go home).
        for (ServerPlayer m : deck) {
            if (crew.contains(m)) continue;
            Lobby.msg(m, "§6[Captain Wren] §fNo fare, no flight! §7You stay behind this time.");
            if (!is.isMember(m.getUUID())) sendHome(m, is);
        }
        PENDING.remove(is.id);
    }

    private static Run startRun(Islands.Island is, int diff, boolean fortress, ServerPlayer leader, List<ServerPlayer> crew) {
        ServerLevel nl = level();
        if (nl == null) return null;
        int n = runCounter++;
        // Every trip gets its own spot in the flat lava-sea world, far from the others.
        int spacing = ExpeditionRooms.HALF * 2 + 200;
        BlockPos center = new BlockPos(3_000 + (n % 20) * spacing, ExpeditionRooms.FLOOR_Y, 3_000 + (n / 20) * spacing);
        Run run = new Run();
        run.islandId = is.id;
        run.diff = diff;
        run.fortress = fortress;
        run.leader = leader.getUUID();
        run.layout = ExpeditionRooms.plan(nl, center, fortress, ROOMS[diff], RNG);
        run.landing = run.layout.landing;
        run.cx = center.getX() + 0.5;
        run.cz = center.getZ() + 0.5;
        run.half = ExpeditionRooms.HALF;
        int rooms = run.layout.rooms.size();
        run.unlockAt = diff <= 1 ? rooms : Math.max(1, rooms - 1);
        if (rooms == 0) run.unlockAt = 0;
        run.building = true;
        run.buildStart = ticks;
        run.startTick = ticks;
        run.timeLimit = TIME_LIMITS[diff];
        for (ServerPlayer m : crew) {
            run.crew.add(m.getUUID());
            PLAYER_RUN.put(m.getUUID(), run);
        }
        RUNS.put(is.id, run);
        Skyserver.LOGGER.info("Nether Expedition #{} ({} {}, crew of {}): {} chest arenas, {} blocks to build", n, DIFF_NAMES[diff],
                fortress ? "Fortress" : "Bastion", crew.size(), rooms, run.layout.builder.order.length);
        for (ServerPlayer m : crew) {
            m.closeContainer();
            Lobby.msg(m, "§6[Captain Wren] §fWe're off! Hold onto your hats - it's a short flight to the " + (fortress ? "§4Fortress" : "§6Bastion") + "§f...");
            PlayerStats.add(m, "expedition", 1);
        }
        return run;
    }

    /** The map is built: the crew lands in Wren's Landing. */
    private static void arrive(Run run) {
        ServerLevel nl = level();
        run.building = false;
        run.startTick = ticks; // the clock starts on landing
        if (nl == null) {
            endRun(run, "the Nether world is missing");
            return;
        }
        fillGaps(nl, run);
        run.wren = spawnNetherWren(nl, run.layout.wren);
        // 5.17: the gold trader, a few steps from Wren.
        double[] w = run.layout.wren;
        run.piglin = ExpeditionBarter.spawn(nl, w[0] + 3, w[1], w[2], (float) w[3]);
        // The secret alcoves' little chests (shared: whoever finds them).
        for (ExpeditionRooms.Secret sec : run.layout.secrets) {
            if (!(nl.getBlockEntity(sec.chest) instanceof Container c)) continue;
            List<ItemStack> items = secretLoot(run);
            for (int i = 0; i < items.size() && i < c.getContainerSize(); i++) c.setItem(RNG.nextInt(c.getContainerSize()), items.get(i));
            c.setChanged();
        }
        int crewSize = 0;
        for (UUID id : run.crew) {
            ServerPlayer m = server.getPlayerList().getPlayer(id);
            if (m != null && PLAYER_RUN.get(id) == run) crewSize++;
        }
        run.crewSize = Math.max(1, Math.min(CREW_MAX, crewSize));
        run.timeLimit = (int) Math.round(TIME_LIMITS[run.diff] * TIME_MULT[run.crewSize - 1]);
        int rooms = run.layout.rooms.size();
        String where = run.fortress ? "a §4Nether Fortress" : "a §6Bastion";
        BlockPos landing = run.landing;
        int waves = WAVES[run.diff].length;
        for (UUID id : run.crew) {
            ServerPlayer m = server.getPlayerList().getPlayer(id);
            if (m == null || PLAYER_RUN.get(id) != run) continue;
            run.inside.add(id);
            m.closeContainer();
            m.teleportTo(nl, landing.getX() + 0.5, landing.getY(), landing.getZ() + 0.5, Set.of(), 180f, 0f, true);
            m.resetFallDistance();
            sendBorder(m, run);
            Announce.title(m, DIFF_COLORS[run.diff] + "§l" + DIFF_NAMES[run.diff].toUpperCase() + " EXPEDITION", "§7" + where.replace("§4", "").replace("§6", ""), 10, 60, 20);
            Lobby.msg(m, "§6§lNETHER EXPEDITION: §f" + DIFF_COLORS[run.diff] + DIFF_NAMES[run.diff] + " §7- " + where + "§7!");
            Lobby.msg(m, "§7You're in §6Wren's Landing §7- safe from everything. §f" + rooms + " chest arenas §7wait on the platforms around you"
                    + " (the line above your hotbar points the way). Walk in to start the fight: §f" + waves + " waves§7, and the next one §ccomes on a timer§7"
                    + " even if the last isn't dead. A captain leads the last wave, and every arena has its own twist.");
            Lobby.msg(m, "§7Clear §f" + run.unlockAt + " §7arena" + (run.unlockAt == 1 ? "" : "s") + " to open the §cBoss Arena§7's gate. "
                    + "§eLeaving a fight locks your health until you go back in. §c§lIf you die, you lose everything you're carrying. §7Talk to §6Captain Wren §7here to go home. ("
                    + (run.timeLimit / 60) + " minutes)");
            Lobby.msg(m, "§7The §6Gold Trader §7here swaps gold ingots for goods. Once this trip, if you go down, you can §arevive §7for §6" + Economy.format(reviveCost(run)) + "§7."
                    + (run.crewSize > 1 ? " §bCrew of " + run.crewSize + ": §7one more guard a wave, tougher guards, a stronger boss, everyone gets their own loot - and once per trip a teammate can pick a downed player up (crouch next to them for "
                    + (TEAM_REVIVE_HOLD_TICKS / 20) + " seconds)." : ""));
        }
        if (run.inside.isEmpty()) endRun(run, "nobody came");
    }

    /**
     * 5.17: the open plazas' floors stopped one block short of the bridges. Fills that one-block strip
     * (floor and the layer under it) wherever a bridge arrives at a platform.
     */
    private static void fillGaps(ServerLevel nl, Run run) {
        BlockPos c = run.layout.center;
        int[][] sides = {{0, -1}, {1, 0}, {0, 1}, {-1, 0}};
        int half = ExpeditionRooms.ROOM / 2;
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int i = -1; i <= 1; i++) {
            for (int j = -1; j <= 1; j++) {
                if (i == 0 && j == 0) continue;
                int px = c.getX() + i * ExpeditionRooms.PITCH, pz = c.getZ() + j * ExpeditionRooms.PITCH;
                for (int[] s : sides) {
                    for (int a = -3; a <= 3; a++) {
                        int x = px + s[0] * half + (s[1] != 0 ? a : 0), z = pz + s[1] * half + (s[0] != 0 ? a : 0);
                        for (int y = ExpeditionRooms.FLOOR_Y - 1; y >= ExpeditionRooms.FLOOR_Y - 2; y--) {
                            if (!nl.getBlockState(m.set(x, y, z)).isAir()) continue;
                            BlockState in = nl.getBlockState(m.set(x - s[0], y, z - s[1])), out = nl.getBlockState(m.set(x + s[0], y, z + s[1]));
                            if (in.isAir() || out.isAir()) continue; // no floor on one side: not a bridge mouth
                            nl.setBlock(m.set(x, y, z), in, Block.UPDATE_ALL);
                        }
                    }
                }
            }
        }
    }

    /** A small bonus in a secret alcove. */
    private static List<ItemStack> secretLoot(Run run) {
        List<ItemStack> out = new ArrayList<>();
        out.add(new ItemStack(Items.GOLDEN_CARROT, 4 + RNG.nextInt(5)));
        out.add(new ItemStack(Items.ARROW, 8 + RNG.nextInt(9)));
        out.add(net.minecraft.world.item.alchemy.PotionContents.createItemStack(Items.POTION, net.minecraft.world.item.alchemy.Potions.FIRE_RESISTANCE));
        out.add(new ItemStack(run.fortress ? Items.BLAZE_ROD : Items.GOLD_INGOT, 2 + RNG.nextInt(3 + run.diff)));
        if (run.diff >= 2) out.add(new ItemStack(Items.GOLDEN_APPLE, 1));
        return out;
    }

    private static UUID spawnNetherWren(ServerLevel nl, double[] at) {
        Villager v = net.minecraft.world.entity.EntityTypes.VILLAGER.create(nl, EntitySpawnReason.COMMAND);
        if (v == null) return null;
        v.snapTo(at[0], at[1], at[2], (float) at[3], 0f);
        v.setYHeadRot((float) at[3]);
        v.setNoAi(true);
        v.setPermanentlyInvulnerable(true);
        v.setSilent(true);
        v.setPersistenceRequired();
        v.setCustomName(Component.literal("§6Captain Wren §7(right-click to go home)"));
        v.setCustomNameVisible(true);
        v.addTag(NETHER_WREN_TAG);
        nl.addFreshEntity(v);
        return v.getUUID();
    }

    private static void sendBorder(ServerPlayer p, Run run) {
        WorldBorder b = new WorldBorder();
        b.setCenter(run.cx, run.cz);
        b.setSize(run.half * 2);
        b.setWarningBlocks(4);
        p.connection.send(new ClientboundInitializeBorderPacket(b));
    }

    // =====================================================================
    // Captain Wren in the Nether
    // =====================================================================

    private static void talkToWren(ServerPlayer p) {
        Run run = PLAYER_RUN.get(p.getUUID());
        if (run == null) return;
        Lobby.msg(p, "§6[Captain Wren] §fBack already, captain? " + (run.bossDead ? "That's the Vault cleaned out - fine work!"
                : "There's still loot out there: " + run.cleared + " of " + run.layout.rooms.size() + " rooms cleared."));
        p.sendSystemMessage(Component.literal("§fShall I take you home? ")
                .append(button("§a§l[Yes, take me home]", "/expedition leave", "Back to the ship room with everything you carry"))
                .append(Component.literal(" "))
                .append(button("§c§l[No, not yet]", "/expedition stay", "Keep exploring"))
                .append(Component.literal(" "))
                .append(button("§e[How does this work?]", "/expedition help", "Wren explains the trip")));
    }

    static void howItWorks(ServerPlayer p) {
        Run run = PLAYER_RUN.get(p.getUUID());
        int waves = WAVES[run != null ? run.diff : 0].length;
        Lobby.msg(p, "§6[Captain Wren] §fHere's the lay of the land:");
        Lobby.msg(p, " §7- This pavilion is §6Wren's Landing§7: nothing can hurt you in here and no beast gets in. The §6Gold Trader §7swaps gold ingots for goods.");
        Lobby.msg(p, " §7- The platforms around it hold §cchest arenas §7(coloured stripes over the doorways). Walk in and the first wave comes;"
                + " " + waves + " waves in all, and §cthe next one won't wait §7for you to finish the last. Archers take the balconies,"
                + " and a §4captain §7leads the last wave. Guards stay in their arena, and you can only hurt them from inside.");
        StringBuilder tw = new StringBuilder(" §7- Every arena has a twist: ");
        for (int i = 0; i < ExpeditionTwists.NAMES.length; i++) tw.append(i == 0 ? "" : "§7, ").append(ExpeditionTwists.NAMES[i]);
        Lobby.msg(p, tw + "§7. The arena tells you which when you walk in.");
        Lobby.msg(p, " §7- Doors never lock - but if you walk out of a fight, your §chealth can't go up §7until you walk back in.");
        Lobby.msg(p, " §7- Beat every wave and the chest opens: §feveryone finds their own loot in it§7. The stripes turn §agreen §7when an arena is done.");
        Lobby.msg(p, " §7- Clear " + (run == null ? "enough" : String.valueOf(run.unlockAt)) + " arenas and the §cBoss Arena§7's gate opens. Beat the boss for the §6Treasure Vault§7."
                + " Watch the floor, and hit it hard when it's stunned.");
        Lobby.msg(p, " §7- Bridges have rails, but break a rail or the floor and it's a long drop into lava. §dCracked bricks at floor level hide secrets.");
        Lobby.msg(p, " §7- The line above your hotbar points to the next arena. Mine and build all you like - what you carry comes home.");
        Lobby.msg(p, " §c- If you die, you drop everything you carry. §7Talk to me in here to go home safe.");
        Lobby.msg(p, " §a- Going down: §7once per trip a §bteammate §7can pick ONE downed player up (crouch next to them for " + (TEAM_REVIVE_HOLD_TICKS / 20)
                + " seconds, half health). After that - or alone - each player has one §fpaid revive §7for " + (int) Math.round(REVIVE_SHARE * 100)
                + "% of the trip's price" + (run == null ? "" : " (" + Economy.format(reviveCost(run)) + ")") + ": full health, right where you fell.");
    }

    // =====================================================================
    // During the run
    // =====================================================================

    private static void tip(ServerPlayer p, String text) {
        Long last = LAST_TIP.get(p.getUUID());
        if (last != null && ticks - last < 40) return;
        LAST_TIP.put(p.getUUID(), ticks);
        Announce.actionBar(p, text);
    }

    private static InteractionResult useBlock(ServerPlayer p, ServerLevel level, BlockPos pos) {
        Run run = PLAYER_RUN.get(p.getUUID());
        BlockEntity be = level.getBlockEntity(pos);
        // Any vanilla loot chest that slipped through: it crumbles (all loot is in the rooms).
        if (be instanceof RandomizableContainer rc && rc.getLootTable() != null) {
            level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            tip(p, "§7The old chest crumbles to dust. §eThe real loot is in the chest rooms!");
            return InteractionResult.FAIL;
        }
        if (run == null || run.layout == null) return InteractionResult.PASS;
        if (DOWNED.containsKey(p.getUUID())) return InteractionResult.FAIL;
        for (ExpeditionRooms.Room r : run.layout.rooms) {
            if (!pos.equals(r.chest)) continue;
            if (!r.cleared) {
                int left = guardsLeft(level, r);
                tip(p, r.woke ? "§cGuarded: §f" + left + " guards left" : "§cThis chest is guarded - the guards wake when you step in!");
                return InteractionResult.FAIL;
            }
            // 5.17: everyone has their own chest in here.
            final int[] scrap = run.scrap.computeIfAbsent(p.getUUID(), k -> new int[1]);
            ExpeditionChests.open(p, r, "§8Your loot - " + r.name(), () -> ExpeditionLoot.chestLoot(level, run.diff, run.fortress, RNG, scrap));
            return InteractionResult.SUCCESS;
        }
        if (run.vault != null && pos.equals(run.vault)) {
            int[] scrap = run.scrap.get(p.getUUID());
            final int had = scrap == null ? 0 : scrap[0];
            ExpeditionChests.open(p, run, "§8Your share of the §6Treasure Vault", () -> ExpeditionLoot.vault(level, run.diff, RNG, had));
            return InteractionResult.SUCCESS;
        }
        return InteractionResult.PASS;
    }

    private static int guardsLeft(ServerLevel level, ExpeditionRooms.Room r) {
        r.guards.removeIf(id -> { Entity e = level.getEntity(id); return e == null || !e.isAlive(); });
        return r.guards.size();
    }

    private static int twist(ExpeditionRooms.Room r) {
        return TWIST.getOrDefault(r, -1);
    }

    private static EntityType<? extends Mob> guardType(Run run, int i) {
        List<EntityType<? extends Mob>> fort = List.of(net.minecraft.world.entity.EntityTypes.WITHER_SKELETON, net.minecraft.world.entity.EntityTypes.BLAZE,
                net.minecraft.world.entity.EntityTypes.WITHER_SKELETON, net.minecraft.world.entity.EntityTypes.SKELETON, net.minecraft.world.entity.EntityTypes.MAGMA_CUBE);
        List<EntityType<? extends Mob>> bast = List.of(net.minecraft.world.entity.EntityTypes.PIGLIN, net.minecraft.world.entity.EntityTypes.PIGLIN_BRUTE,
                net.minecraft.world.entity.EntityTypes.PIGLIN, net.minecraft.world.entity.EntityTypes.HOGLIN, net.minecraft.world.entity.EntityTypes.MAGMA_CUBE);
        List<EntityType<? extends Mob>> list = run.fortress ? fort : bast;
        if (run.diff == 3 && i % 4 == 3) return run.fortress ? net.minecraft.world.entity.EntityTypes.BLAZE : net.minecraft.world.entity.EntityTypes.PIGLIN_BRUTE;
        return list.get(i % list.size());
    }

    /** Archers (skeletons, blazes, and every other piglin - those get crossbows) take the high spots. */
    private static boolean isRanged(EntityType<?> type, int i) {
        return type == net.minecraft.world.entity.EntityTypes.SKELETON || type == net.minecraft.world.entity.EntityTypes.BLAZE
                || (type == net.minecraft.world.entity.EntityTypes.PIGLIN && i % 2 == 0);
    }

    private static boolean standable(ServerLevel level, BlockPos.MutableBlockPos m, int x, int y, int z) {
        if (!level.getBlockState(m.set(x, y, z)).isAir() || !level.getBlockState(m.set(x, y + 1, z)).isAir()) return false;
        return level.getBlockState(m.set(x, y - 1, z)).isFaceSturdy(level, m, net.minecraft.core.Direction.UP);
    }

    /** A random free floor spot inside a room. */
    private static BlockPos roomSpot(ServerLevel level, ExpeditionRooms.Room r) {
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int tries = 0; tries < 30; tries++) {
            int x = r.x0 + RNG.nextInt(r.x1 - r.x0 + 1), z = r.z0 + RNG.nextInt(r.z1 - r.z0 + 1);
            for (int y = r.y0; y <= r.y1 - 1; y++) {
                if (standable(level, m, x, y, z)) return new BlockPos(x, y, z);
            }
        }
        return r.floorCenter();
    }

    /**
     * The nearest spot to `near` (within 12 blocks, up to 3 up or down) where a BIG mob fits: a sturdy
     * floor under, and nothing to bump into in, a box (2 x half + 1) wide and `height` tall. If there is
     * none: a normal room spot, and without a room, `near` itself.
     */
    private static BlockPos bigSpot(ServerLevel level, ExpeditionRooms.Room room, BlockPos near, int half, int height) {
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int ring = 0; ring <= 12; ring++) {
            for (int dx = -ring; dx <= ring; dx++) {
                for (int dz = -ring; dz <= ring; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) continue; // only the edge of this ring (nearest first)
                    for (int dy = 0; dy <= 3; dy++) {
                        for (int sign = 1; sign >= -1; sign -= 2) {
                            if (dy == 0 && sign < 0) continue;
                            int x = near.getX() + dx, y = near.getY() + dy * sign, z = near.getZ() + dz;
                            if (clearBox(level, m, x, y, z, half, height)) return new BlockPos(x, y, z);
                        }
                    }
                }
            }
        }
        return room != null ? roomSpot(level, room) : near;
    }

    /** Is there a sturdy floor under, and no collision shape inside, the box around (x, y, z)? */
    private static boolean clearBox(ServerLevel level, BlockPos.MutableBlockPos m, int x, int y, int z, int half, int height) {
        for (int dx = -half; dx <= half; dx++) {
            for (int dz = -half; dz <= half; dz++) {
                if (!level.getBlockState(m.set(x + dx, y - 1, z + dz)).isFaceSturdy(level, m, net.minecraft.core.Direction.UP)) return false;
                for (int dy = 0; dy < height; dy++) {
                    BlockState st = level.getBlockState(m.set(x + dx, y + dy, z + dz));
                    if (!st.getCollisionShape(level, m).isEmpty() || !st.getFluidState().isEmpty()) return false;
                }
            }
        }
        return true;
    }

    /** A spot up high (balconies, walkways, broken pillar tops), or a floor spot if there's none. */
    private static BlockPos highSpot(ServerLevel level, ExpeditionRooms.Room r) {
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int tries = 0; tries < 40; tries++) {
            int x = r.x0 + RNG.nextInt(r.x1 - r.x0 + 1), z = r.z0 + RNG.nextInt(r.z1 - r.z0 + 1);
            for (int y = r.y1 - 2; y >= r.y0 + 4; y--) {
                if (standable(level, m, x, y, z)) return new BlockPos(x, y, z);
            }
        }
        return roomSpot(level, r);
    }

    /** Spawns one guard (or the boss) inside a room, made stronger for the difficulty. */
    private static Mob spawnGuard(ServerLevel level, Run run, ExpeditionRooms.Room room, EntityType<? extends Mob> type, boolean boss) {
        return spawnGuard(level, run, room, type, boss, roomSpot(level, room));
    }

    private static Mob spawnGuard(ServerLevel level, Run run, ExpeditionRooms.Room room, EntityType<? extends Mob> type, boolean boss, BlockPos spot) {
        Mob m = type.create(level, EntitySpawnReason.EVENT);
        if (m == null) return null;
        m.snapTo(spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5, RNG.nextFloat() * 360f, 0f);
        m.finalizeSpawn(level, level.getCurrentDifficultyAt(spot), EntitySpawnReason.EVENT, null);
        double hp = HEALTH[run.diff] + (boss ? 2 + 2 * run.diff : 0), dmg = DAMAGE[run.diff] + (boss ? 0.5 : 0);
        boost(m.getAttribute(Attributes.MAX_HEALTH), "exp_health", hp);
        boost(m.getAttribute(Attributes.ATTACK_DAMAGE), "exp_damage", dmg);
        m.setHealth(m.getMaxHealth());
        m.setPersistenceRequired();
        m.addTag(GUARD_TAG);
        ServerPlayer target = nearestIn(run, room);
        if (target != null) m.setTarget(target);
        // Recorded before it joins the world, so the split-adoption hook never mistakes it for a stray.
        GUARD_OF.put(m.getUUID(), new Object[]{run, room});
        room.guards.add(m.getUUID());
        if (!boss) ExpeditionTwists.guardSpawned(m, twist(room));
        level.addFreshEntity(m);
        return m;
    }

    private static void boost(AttributeInstance a, String id, double amount) {
        if (a == null || amount <= 0) return;
        a.addPermanentModifier(new AttributeModifier(Identifier.fromNamespaceAndPath(Skyserver.MOD_ID, id), amount, AttributeModifier.Operation.ADD_MULTIPLIED_BASE));
    }

    /** 5.17: multiplies a guard's health (0.6 = 40% less, 1.5 = half again) and fills it up. */
    private static void scaleHealth(Mob m, String id, double factor) {
        AttributeInstance a = m.getAttribute(Attributes.MAX_HEALTH);
        if (a == null || Math.abs(factor - 1.0) < 0.001) return;
        a.addPermanentModifier(new AttributeModifier(Identifier.fromNamespaceAndPath(Skyserver.MOD_ID, id), factor - 1.0, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
        m.setHealth(m.getMaxHealth());
    }

    // ---- Gear for guards ----

    private static final EquipmentSlot[] ARMOR_SLOTS = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};
    private static final Item[] GOLD_ARMOR = {Items.GOLDEN_HELMET, Items.GOLDEN_CHESTPLATE, Items.GOLDEN_LEGGINGS, Items.GOLDEN_BOOTS};
    private static final Item[] IRON_ARMOR = {Items.IRON_HELMET, Items.IRON_CHESTPLATE, Items.IRON_LEGGINGS, Items.IRON_BOOTS};
    private static final Item[] DIAMOND_ARMOR = {Items.DIAMOND_HELMET, Items.DIAMOND_CHESTPLATE, Items.DIAMOND_LEGGINGS, Items.DIAMOND_BOOTS};

    /** Armor piece i (helmet, chest, legs, boots) for this guard, or null. Easy: helmet + chest only. */
    private static Item armorPiece(boolean piglin, int d, int i) {
        if (d == 0 && i >= 2) return null;
        if (piglin) return d <= 1 ? GOLD_ARMOR[i] : d == 2 ? IRON_ARMOR[i] : DIAMOND_ARMOR[i];
        if (d <= 1) return IRON_ARMOR[i];
        if (d == 2) return i <= 1 ? DIAMOND_ARMOR[i] : IRON_ARMOR[i];
        return DIAMOND_ARMOR[i];
    }

    /** Puts an item on a guard; guards never drop their gear. */
    private static void equip(Mob m, EquipmentSlot slot, ItemStack s) {
        m.setItemSlot(slot, s);
        m.setDropChance(slot, 0f);
    }

    private static ItemStack enchanted(ServerLevel level, Item item, Object... keysAndLevels) {
        ItemStack s = new ItemStack(item);
        for (int i = 0; i + 1 < keysAndLevels.length; i += 2) {
            @SuppressWarnings("unchecked")
            ResourceKey<net.minecraft.world.item.enchantment.Enchantment> k = (ResourceKey<net.minecraft.world.item.enchantment.Enchantment>) keysAndLevels[i];
            int lvl = (Integer) keysAndLevels[i + 1];
            if (lvl > 0) ExpeditionLoot.enchant(level, s, k, lvl);
        }
        return s;
    }

    /** Armor, an enchanted weapon and potion effects, all by difficulty. ranged = piglins get a crossbow. */
    private static void gear(ServerLevel level, Run run, Mob m, boolean ranged) {
        int d = run.diff;
        EntityType<?> t = m.getType();
        boolean piglin = t == net.minecraft.world.entity.EntityTypes.PIGLIN || t == net.minecraft.world.entity.EntityTypes.PIGLIN_BRUTE;
        boolean skel = t == net.minecraft.world.entity.EntityTypes.WITHER_SKELETON || t == net.minecraft.world.entity.EntityTypes.SKELETON;
        if (piglin || skel) {
            int prot = new int[]{0, piglin ? 1 : 0, 1, 2}[d];
            for (int i = 0; i < 4; i++) {
                Item a = armorPiece(piglin, d, i);
                equip(m, ARMOR_SLOTS[i], a == null ? ItemStack.EMPTY : enchanted(level, a, Enchantments.PROTECTION, prot));
            }
            ItemStack hand;
            if (t == net.minecraft.world.entity.EntityTypes.WITHER_SKELETON)
                hand = enchanted(level, d <= 1 ? Items.IRON_SWORD : Items.DIAMOND_SWORD, Enchantments.SHARPNESS, new int[]{0, 1, 1, 3}[d]);
            else if (t == net.minecraft.world.entity.EntityTypes.SKELETON)
                hand = enchanted(level, Items.BOW, Enchantments.POWER, d + 1, Enchantments.FLAME, d == 3 ? 1 : 0);
            else if (t == net.minecraft.world.entity.EntityTypes.PIGLIN)
                hand = ranged ? enchanted(level, Items.CROSSBOW, Enchantments.QUICK_CHARGE, new int[]{1, 1, 2, 3}[d], Enchantments.PIERCING, new int[]{0, 0, 1, 2}[d])
                        : enchanted(level, Items.GOLDEN_SWORD, Enchantments.SHARPNESS, new int[]{0, 1, 2, 3}[d]);
            else
                hand = enchanted(level, new Item[]{Items.GOLDEN_AXE, Items.GOLDEN_AXE, Items.IRON_AXE, Items.DIAMOND_AXE}[d], Enchantments.SHARPNESS, new int[]{0, 1, 1, 2}[d]);
            equip(m, EquipmentSlot.MAINHAND, hand);
        }
        int dur = 20 * 60 * 60;
        if (d >= 1) m.addEffect(new MobEffectInstance(MobEffects.RESISTANCE, dur, 0, false, false));
        if (d == 2) m.addEffect(new MobEffectInstance(MobEffects.STRENGTH, dur, 0, false, false));
        if (d == 3) {
            m.addEffect(new MobEffectInstance(MobEffects.STRENGTH, dur, 1, false, false));
            m.addEffect(new MobEffectInstance(MobEffects.SPEED, dur, 0, false, false));
        }
    }

    /** Spawns a guard with its gear: archers up high, the rest at `floor`. */
    private static Mob spawnFighter(ServerLevel level, Run run, ExpeditionRooms.Room r, EntityType<? extends Mob> type, boolean ranged, BlockPos floor) {
        BlockPos spot = ranged ? highSpot(level, r) : floor;
        Mob m = spawnGuard(level, run, r, type, false, spot);
        if (m == null) return null;
        gear(level, run, m, ranged);
        if (twist(r) == ExpeditionTwists.SWARM) scaleHealth(m, "exp_swarm", ExpeditionTwists.SWARM_HEALTH);
        // 5.18: a crew's guards are tougher and hit harder for every extra player (captains are not).
        int more = run.crewSize - 1;
        if (more > 0) {
            scaleHealth(m, "exp_crew", 1.0 + CREW_HEALTH * more);
            AttributeInstance atk = m.getAttribute(Attributes.ATTACK_DAMAGE);
            if (atk != null) atk.addPermanentModifier(new AttributeModifier(Identifier.fromNamespaceAndPath(Skyserver.MOD_ID, "exp_crew_damage"),
                    CREW_DAMAGE * more, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
        }
        return m;
    }

    /**
     * The last wave's captain - a Wither Knight (Fortress) or Brute Warlord (Bastion) - on a spot where it
     * fits (3 x 3 wide, 4 tall) near a doorway. 5.17: healthFactor (the extra captains a bigger crew
     * brings have 75% of a captain's health); announce = show its title to the players in the arena.
     */
    private static void captain(ServerLevel level, Run run, ExpeditionRooms.Room r, double healthFactor, boolean announce) {
        int d = run.diff;
        EntityType<? extends Mob> type = run.fortress ? net.minecraft.world.entity.EntityTypes.WITHER_SKELETON : net.minecraft.world.entity.EntityTypes.PIGLIN_BRUTE;
        String plain = run.fortress ? "Wither Knight" : "Brute Warlord";
        BlockPos door = r.doorsIn.isEmpty() ? r.floorCenter() : r.doorsIn.get(RNG.nextInt(r.doorsIn.size()));
        BlockPos spot = bigSpot(level, r, door, 1, 4);
        Mob m = spawnGuard(level, run, r, type, false, spot);
        if (m == null) {
            Skyserver.LOGGER.warn("[Expedition] the {} could not be created at {} {} {}", plain, spot.getX(), spot.getY(), spot.getZ());
            return;
        }
        gear(level, run, m, false);
        Item weapon = run.fortress ? (d >= 2 ? Items.NETHERITE_SWORD : Items.DIAMOND_SWORD) : (d >= 2 ? Items.NETHERITE_AXE : Items.DIAMOND_AXE);
        equip(m, EquipmentSlot.MAINHAND, enchanted(level, weapon, Enchantments.SHARPNESS, d + 1));
        boost(m.getAttribute(Attributes.MAX_HEALTH), "exp_captain", CAPTAIN_HEALTH[d]);
        m.setHealth(m.getMaxHealth());
        scaleHealth(m, "exp_extra_captain", healthFactor);
        AttributeInstance scale = m.getAttribute(Attributes.SCALE);
        if (scale != null) scale.setBaseValue(1.3);
        AttributeInstance kbRes = m.getAttribute(Attributes.KNOCKBACK_RESISTANCE);
        if (kbRes != null) kbRes.setBaseValue(0.7);
        AttributeInstance kb = m.getAttribute(Attributes.ATTACK_KNOCKBACK);
        if (kb != null) kb.setBaseValue(1.5);
        m.addEffect(new MobEffectInstance(MobEffects.STRENGTH, 20 * 60 * 60, d >= 1 ? 1 : 0, false, false));
        String nm = run.fortress ? "§4§lWither Knight" : "§6§lBrute Warlord";
        m.setCustomName(Component.literal(nm));
        m.setCustomNameVisible(true);
        m.setGlowingTag(true);
        Skyserver.LOGGER.info("[Expedition] {} spawned at {} {} {} ({} HP)", plain, spot.getX(), spot.getY(), spot.getZ(), Math.round(m.getMaxHealth()));
        if (!announce) return;
        level.playSound(null, m.blockPosition(), SoundEvents.ENDER_DRAGON_GROWL, SoundSource.HOSTILE, 1.2f, 1.4f);
        for (ServerPlayer p : inside(run))
            if (r.inside(p.getX(), p.getY(), p.getZ(), 1)) Announce.title(p, nm, run.crewSize > 1 ? "§7and " + (run.crewSize - 1) + " more lead the last wave!" : "§7leads the last wave!", 5, 40, 10);
    }

    /** Someone walked into a chest room for the first time: its guards wake. */
    private static void wakeRoom(ServerLevel level, Run run, ExpeditionRooms.Room r) {
        r.woke = true;
        // A twist this trip hasn't had yet. 5.18: every wave but the last gets one more guard per extra player
        // (the last wave's extra captain is that wave's extra guard); a Swarm multiplies the result.
        int tw = ExpeditionTwists.pick(RNG, run.usedTwists);
        TWIST.put(r, tw);
        double mult = tw == ExpeditionTwists.SWARM ? ExpeditionTwists.SWARM_COUNT : 1.0;
        int extra = (run.crewSize - 1) * CREW_EXTRA_GUARDS;
        int[] base = WAVES[run.diff];
        r.waves = base.length;
        r.waveSizes = new int[r.waves];
        for (int w = 0; w < r.waves; w++) r.waveSizes[w] = Math.max(1, (int) Math.round((base[w] + (w < r.waves - 1 ? extra : 0)) * mult));
        r.wave = 0;
        for (ServerPlayer m : inside(run))
            if (r.inside(m.getX(), m.getY(), m.getZ(), 1)) Lobby.msg(m, ExpeditionTwists.NAMES[tw] + " arena! " + ExpeditionTwists.TIPS[tw] + "§7.");
        nextWave(level, run, r);
    }

    /**
     * Sends the next wave into a room. Archers spawn up high; the first wave appears around the room
     * (Ambush: at every doorway), later ones come through the doorways. The wave after this one is due in
     * WAVE_SECONDS (longer for a crew) whether this one is dead or not. At most ROOM_GUARD_CAPS guards alive at once - the
     * rest of the wave becomes extra health for the ones that came.
     */
    private static void nextWave(ServerLevel level, Run run, ExpeditionRooms.Room r) {
        r.waveComing = false;
        if (run.over || r.cleared || r.wave >= r.waves) return;
        int w = r.wave++;
        boolean last = r.wave >= r.waves;
        int tw = twist(r);
        int count = r.waveSizes[w];
        ExpeditionTwists.waveStarted(level, run, r, tw, RNG);
        int spawn = Math.max(1, Math.min(count, ROOM_GUARD_CAPS[run.diff] - guardsLeft(level, r)));
        double pooled = count / (double) spawn;
        int offset = RNG.nextInt(8);
        boolean fromDoors = (w > 0 || tw == ExpeditionTwists.AMBUSH) && !r.doorsIn.isEmpty();
        for (int i = 0; i < spawn; i++) {
            EntityType<? extends Mob> type = guardType(run, i + offset + w * 2);
            BlockPos floor;
            if (!fromDoors) floor = roomSpot(level, r);
            else {
                BlockPos door = r.doorsIn.get((i + w) % r.doorsIn.size());
                floor = door.offset(RNG.nextInt(3) - 1, 0, RNG.nextInt(3) - 1);
            }
            Mob m = spawnFighter(level, run, r, type, isRanged(type, i), floor);
            if (m != null) scaleHealth(m, "exp_pooled", pooled);
        }
        if (last) {
            captain(level, run, r, 1.0, true);
            for (int i = 1; i < run.crewSize; i++) captain(level, run, r, EXTRA_CAPTAIN_HEALTH, false);
        } else {
            int gap = (int) Math.round(WAVE_SECONDS[run.diff] * (1.0 + CREW_WAVE_TIME * (run.crewSize - 1)));
            WAVE_DUE.put(r, ticks + (gap - (tw == ExpeditionTwists.AMBUSH ? 5 : 0)) * 20L);
        }
        level.playSound(null, r.floorCenter(), w == 0 ? SoundEvents.WITHER_SKELETON_AMBIENT : SoundEvents.RAVAGER_ROAR, SoundSource.HOSTILE, 1.5f, 0.7f);
        String sub = w == 0 ? (tw >= 0 ? ExpeditionTwists.NAMES[tw] + " §7- beat every wave to open the chest" : "§7Beat every wave to open the chest")
                : last ? "§4The captain is here!" : "§7They're coming through the doorways!";
        for (ServerPlayer m : inside(run))
            if (r.inside(m.getX(), m.getY(), m.getZ(), 1))
                Announce.title(m, "§c§lWAVE " + (w + 1) + "/" + r.waves, sub, 5, 40, 10);
        if (r.guards.isEmpty()) checkRoom(level, run, r); // nothing could spawn: don't lock it forever
    }

    /** After a guard dies (or nothing spawned): next wave early, or the room is cleared. */
    private static void checkRoom(ServerLevel level, Run run, ExpeditionRooms.Room room) {
        if (run.over || room.cleared) return;
        int left = guardsLeft(level, room);
        if (left > 0 || SPLITTING.getOrDefault(room, 0) > 0) {
            if (left > 0)
                for (ServerPlayer m : inside(run)) if (room.inside(m.getX(), m.getY(), m.getZ(), 1)) Announce.actionBar(m, "§cWave " + room.wave + "/" + room.waves + " §7- guards left: §f" + left);
            return;
        }
        if (room.wave < room.waves) {
            if (!room.waveComing) {
                room.waveComing = true;
                for (ServerPlayer m : inside(run)) if (room.inside(m.getX(), m.getY(), m.getZ(), 1)) Announce.actionBar(m, "§aWave cleared! §eThe next wave is coming...");
                Lobby.runLaterTicks(60, () -> Safe.run("expedition wave", () -> nextWave(level, run, room)));
            }
        } else clearRoom(level, run, room);
    }

    /** A little magma cube split from a guard joins that guard's arena. */
    private static void adoptSplit(Mob m) {
        for (Run run : RUNS.values()) {
            if (run.over || run.layout == null) continue;
            for (ExpeditionRooms.Room r : run.layout.rooms) {
                if (!r.active() || !r.inside(m.getX(), m.getY(), m.getZ(), 1.5)) continue;
                m.addTag(GUARD_TAG);
                m.setPersistenceRequired();
                GUARD_OF.put(m.getUUID(), new Object[]{run, r});
                r.guards.add(m.getUUID());
                ServerPlayer target = nearestIn(run, r);
                if (target != null) m.setTarget(target);
                return;
            }
        }
        // Not in any fight: it's a stray like any other.
        Lobby.runLaterTicks(1, () -> Safe.run("expedition stray", () -> {
            if (!m.isRemoved() && !ours(m)) removeStray(m, "as it appeared");
        }));
    }

    private static void clearRoom(ServerLevel level, Run run, ExpeditionRooms.Room r) {
        if (r.cleared) return;
        r.cleared = true;
        run.cleared++;
        WAVE_DUE.remove(r);
        ExpeditionTwists.roomDone(level, r);
        // 5.17: the chest block stays empty - everyone finds their own loot when they open it (ExpeditionChests).
        ExpeditionRooms.markCleared(level, r);
        level.playSound(null, r.floorCenter(), SoundEvents.CHEST_OPEN, SoundSource.BLOCKS, 1f, 0.8f);
        for (ServerPlayer m : inside(run))
            Lobby.msg(m, "§a§lRoom cleared! §7The " + r.name() + "§7's chest is open §8(" + run.cleared + "/" + run.layout.rooms.size() + ")§7."
                    + (run.crewSize > 1 ? " §fEveryone has their own loot in it." : ""));
        if (!run.arenaOpen && run.cleared >= run.unlockAt) openArena(run);
    }

    private static void openArena(Run run) {
        run.arenaOpen = true;
        ServerLevel level = level();
        if (level != null) {
            ExpeditionRooms.openGate(level, run.layout);
            level.playSound(null, run.layout.arena.floorCenter(), SoundEvents.WITHER_SPAWN, SoundSource.HOSTILE, 2f, 0.6f);
        }
        for (ServerPlayer m : inside(run)) {
            Announce.title(m, "§c§lTHE GATE IS OPEN", "§7Follow the arrow - the boss is waiting", 5, 60, 15);
            Lobby.msg(m, "§c§lThe Boss Arena's gate is open! §7Walk in to face the " + ExpeditionBoss.name(run.fortress) + "§7.");
        }
    }

    private static void spawnBoss(ServerLevel level, Run run) {
        if (run.bossSpawned || run.over) return;
        run.bossSpawned = true;
        ExpeditionRooms.Room arena = run.layout.arena;
        // On a spot where the big body fits (5 x 5 wide, 6 tall) near the middle; if it can't be made, try again.
        Mob body = spawnGuard(level, run, arena, ExpeditionBoss.hitboxType(run.fortress), true, bigSpot(level, arena, arena.floorCenter(), 2, 6));
        if (body == null) {
            Skyserver.LOGGER.warn("[Expedition] the boss could not be created - trying again");
            run.bossSpawned = false;
            return;
        }
        run.boss = body.getUUID();
        run.fight = ExpeditionBoss.start(level, body, run.fortress, run.diff, run.crewSize, arena, run.layout.pillarSpots, new ExpeditionBoss.Hooks() {
            @Override
            public List<ServerPlayer> fighters() {
                List<ServerPlayer> out = new ArrayList<>();
                for (ServerPlayer p : inside(run))
                    if (isExpedition(p.level()) && !DOWNED.containsKey(p.getUUID()) && arena.inside(p.getX(), p.getY(), p.getZ(), 1)) out.add(p);
                return out;
            }

            @Override
            public void minions(EntityType<? extends Mob> type, int count) {
                for (int i = 0; i < count; i++) {
                    Mob m = spawnGuard(level, run, arena, type, false);
                    if (m != null) gear(level, run, m, type == net.minecraft.world.entity.EntityTypes.PIGLIN && RNG.nextBoolean());
                }
            }

            @Override
            public void minionAt(EntityType<? extends Mob> type, BlockPos at) {
                Mob m = spawnGuard(level, run, arena, type, false, at);
                if (m != null) gear(level, run, m, false);
            }

            @Override
            public int minionsAlive() {
                int n = 0;
                for (Map.Entry<UUID, Object[]> e : GUARD_OF.entrySet()) {
                    if (e.getValue()[0] != run || e.getValue()[1] != arena || e.getKey().equals(run.boss)) continue;
                    Entity ent = level.getEntity(e.getKey());
                    if (ent != null && ent.isAlive()) n++;
                }
                return n;
            }
        });
        level.playSound(null, body.blockPosition(), SoundEvents.WITHER_SPAWN, SoundSource.HOSTILE, 1.2f, 0.8f);
        for (ServerPlayer m : inside(run)) {
            Announce.title(m, ExpeditionBoss.name(run.fortress), "§7The boss has woken!", 5, 50, 10);
            Lobby.msg(m, "§c§lTHE BOSS IS HERE! §7Beat the " + ExpeditionBoss.name(run.fortress) + " §7for the §6Treasure Vault§7. "
                    + (run.fortress ? "§8(Back off when it rises for a slam, jump its ring of fire, and hit it hard while it's stuck.)"
                    : "§8(Sidestep its charge - into a wall stuns it. Shields block its gold shards.)")
                    + " §8Glowing, smoking floor is about to erupt - move!");
        }
    }

    /** After anything in the expedition takes damage. */
    private static void afterDamage(Entity entity, Entity attacker, float taken) {
        if (taken <= 0) return;
        if (entity instanceof ServerPlayer p) {
            final UUID id = p.getUUID();
            LAST_HURT.put(id, ticks);
            if (attacker != null) {
                Object[] g = GUARD_OF.get(attacker.getUUID());
                // Withering arenas: a guard's hit withers you half the time.
                if (g != null && g[1] instanceof ExpeditionRooms.Room room && twist(room) == ExpeditionTwists.WITHERING && RNG.nextInt(100) < WITHER_CHANCE) {
                    p.addEffect(new MobEffectInstance(MobEffects.WITHER, 80, ((Run) g[0]).diff >= 2 ? 1 : 0));
                    WITHER_OK.put(id, ticks + 80);
                } else if (attacker.getType() == net.minecraft.world.entity.EntityTypes.WITHER_SKELETON) {
                    // 5.17: wither skeletons no longer wither by themselves (anywhere - the Boss Arena too).
                    // Vanilla adds its effect right after this hit, so it's taken off again a tick later.
                    Lobby.runLaterTicks(1, () -> Safe.run("expedition no wither", () -> {
                        Long ok = WITHER_OK.get(id);
                        if (ok == null || ticks > ok) p.removeEffect(MobEffects.WITHER);
                    }));
                }
            }
            return;
        }
        Object[] g = GUARD_OF.get(entity.getUUID());
        if (g == null) return;
        Run run = (Run) g[0];
        if (run.fight != null && run.fight.body == entity) run.fight.afterHurt(taken);
    }

    private static void mobDied(Entity e, Entity killer) {
        if (killer instanceof net.minecraft.world.entity.projectile.Projectile pr && pr.getOwner() != null) killer = pr.getOwner();
        Object[] g = GUARD_OF.remove(e.getUUID());
        OUTSIDE.remove(e.getUUID());
        Run run = g != null ? (Run) g[0] : killer instanceof ServerPlayer sp ? PLAYER_RUN.get(sp.getUUID()) : null;
        if (run == null || run.over) return;
        if (killer instanceof ServerPlayer sp && e instanceof Enemy) {
            run.kills++;
            Skills.add(sp, Skills.Skill.COMBAT, 20);
        }
        ServerLevel level = (ServerLevel) e.level();
        if (e.getUUID().equals(run.boss)) {
            bossDown(level, run);
            return;
        }
        if (g != null && g[1] instanceof ExpeditionRooms.Room room && room.kind == ExpeditionRooms.CHEST) {
            room.guards.remove(e.getUUID());
            ExpeditionTwists.guardDied(level, run, room, twist(room), e);
            // A big magma cube splits about a second after it dies: wait for the little ones before deciding.
            if (e.getType() == net.minecraft.world.entity.EntityTypes.MAGMA_CUBE && e.getBbWidth() > 0.8f) {
                SPLITTING.merge(room, 1, Integer::sum);
                Lobby.runLaterTicks(30, () -> Safe.run("expedition split check", () -> {
                    SPLITTING.merge(room, -1, Integer::sum);
                    checkRoom(level, run, room);
                }));
                return;
            }
            checkRoom(level, run, room);
        }
    }

    private static void bossDown(ServerLevel level, Run run) {
        if (run.bossDead) return;
        run.bossDead = true;
        if (run.fight != null) run.fight.end();
        final int d = run.diff;
        Safe.run("expedition voyage boss", () -> ExpVoyage.bossKilled(level, d));
        // The Treasure Vault appears in the middle of the arena. 5.17: it stays empty - everyone finds their
        // own share when they open it (ExpeditionChests).
        BlockPos chest = run.layout.arena.floorCenter();
        for (int i = 0; i < 6 && !level.getBlockState(chest).isAir(); i++) chest = chest.above();
        level.setBlock(chest, Blocks.CHEST.defaultBlockState(), Block.UPDATE_ALL);
        run.vault = chest;
        level.playSound(null, chest, SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, SoundSource.PLAYERS, 1.5f, 1f);
        List<ServerPlayer> in = inside(run);
        // 5.17: everyone paid for their own place, so everyone gets the whole reward.
        long money = ExpeditionLoot.vaultMoney(d);
        for (ServerPlayer m : in) {
            Announce.title(m, "§6§lVICTORY!", "§7The §6Treasure Vault §7is open!", 5, 60, 15);
            Economy.add(server, m.getUUID(), m.getName().getString(), money);
            StringBuilder got = new StringBuilder("§6§lTREASURE VAULT! §a+" + Economy.format(money));
            int[] k = ExpeditionLoot.VAULT_KEYS[d];
            Keys.reward(server, m.getUUID(), k[0], k[1], "(Treasure Vault)");
            got.append("§7, ").append(Keys.COLORS[k[0]]).append(Keys.NAMES[k[0]]).append(" Key");
            if (ExpeditionLoot.BONUS_KEY_CHANCE[d] > 0 && RNG.nextInt(100) < ExpeditionLoot.BONUS_KEY_CHANCE[d]) {
                int bt = ExpeditionLoot.BONUS_KEY_TIER[d];
                Keys.reward(server, m.getUUID(), bt, 1, "(Treasure Vault - lucky!)");
                got.append("§7, ").append(Keys.COLORS[bt]).append("§l").append(Keys.NAMES[bt]).append(" Key!");
            }
            int shards = ExpeditionLoot.VAULT_SHARDS[d];
            if (d == 3 && RNG.nextInt(100) < ExpeditionLoot.NIGHTMARE_SHARD_BUNDLE_CHANCE) shards += ExpeditionLoot.NIGHTMARE_SHARD_BUNDLE;
            Tokens.add(m.getUUID(), Tokens.SHARDS, shards);
            got.append("§7, §d").append(shards).append(" Key Shards");
            BattlePass.addPassXp(m, ExpeditionLoot.WIN_PASS_XP[d]);
            Levels.addXp(server, m, ExpeditionLoot.WIN_XP[d]);
            got.append("§7, §b").append(ExpeditionLoot.WIN_PASS_XP[d]).append(" pass XP");
            if (d == 2 && RNG.nextInt(100) < ExpeditionLoot.HARD_BOOSTER_CHANCE) got.append("§7, ").append(Tokens.grant(m, ExpeditionLoot.HARD_BOOSTER));
            if (d == 3 && RNG.nextInt(100) < ExpeditionLoot.NIGHTMARE_THEME_CHANCE) got.append("§7, ").append(Tokens.grant(m, "theme"));
            if (d == 3) {
                PlayerProfile prof = PlayerCache.get(m);
                if (prof.owned.add(ExpeditionLoot.CONQUEROR_TITLE)) {
                    prof.touch();
                    m.sendSystemMessage(Component.literal("§4§l✦ First Nightmare clear! §7New title: " + Progression.cosmeticName(ExpeditionLoot.CONQUEROR_TITLE) + " ")
                            .append(button("§a[Equip]", "/cosmetics equip " + ExpeditionLoot.CONQUEROR_TITLE, "Use it now")));
                }
            }
            PlayerStats.add(m, "expedition_win", 1);
            com.aidan.skyserver.lobby.Charts.stamp(m, "nether_" + d); // 5.19: Wren's Charts
            Lobby.msg(m, got + " §7- and your own share in the vault chest in the arena. §eTalk to Captain Wren in Wren's Landing §7when you're ready to go home.");
        }
        server.getPlayerList().broadcastSystemMessage(Component.literal("§6§l⚔ §e" + names(in) + " §7beat a " + DIFF_COLORS[d] + DIFF_NAMES[d]
                + " §7Nether Expedition (" + (run.fortress ? "Fortress" : "Bastion") + ")!"), false);
    }

    // =====================================================================
    // Leaving, dying, logging out
    // =====================================================================

    static void leaveCommand(ServerPlayer p) {
        Run run = PLAYER_RUN.get(p.getUUID());
        if (run == null || !isExpedition(p.level())) {
            Lobby.msg(p, "§7You're not on an expedition.");
            return;
        }
        if (DOWNED.containsKey(p.getUUID())) {
            Lobby.msg(p, "§cYou're down! §7Wait for a teammate, revive or give up first (the buttons in chat).");
            return;
        }
        if (run.layout != null && !run.layout.spawn.inside(p.getX(), p.getY(), p.getZ(), 0.5)) {
            Lobby.msg(p, "§cGo back to §6Wren's Landing §cfirst - Captain Wren takes you home from there §7("
                    + (int) Math.sqrt(p.distanceToSqr(run.landing.getX(), run.landing.getY(), run.landing.getZ())) + " blocks away).");
            return;
        }
        extract(p, run, "§6Captain Wren brings you back to his ship room.");
    }

    /** Home safely with everything carried: onto the deck (or the island home if the ship is gone). */
    static void extract(ServerPlayer p, Run run, String msg) {
        DOWNED.remove(p.getUUID());
        PROTECTED.remove(p.getUUID());
        WITHER_OK.remove(p.getUUID());
        run.inside.remove(p.getUUID());
        HEALTH_LOCK.remove(p.getUUID());
        IN_FIGHT.remove(p.getUUID());
        PLAYER_RUN.remove(p.getUUID());
        Islands.Island is = islandById(run.islandId);
        ExpeditionShip.Ship ship = SHIPS.get(run.islandId);
        if (ship != null) {
            double[] d = ship.deckSpot();
            p.teleportTo(ship.level, d[0], d[1], d[2], Set.of(), (float) d[3], 0f, true);
            if (is != null) Islands.sendBorder(p, is);
        } else if (is != null) {
            Islands.teleportHome(p, is);
        } else {
            Lobby.goToLobby(p);
        }
        p.resetFallDistance();
        p.clearFire();
        Lobby.msg(p, msg + " §7Everything you're carrying is yours.");
        if (run.inside.isEmpty()) endRun(run, "everyone is back");
    }

    /** Dying (or quitting mid-fight): everything carried drops where they are, then home. */
    static void died(ServerPlayer p, String why) {
        DOWNED.remove(p.getUUID());
        PROTECTED.remove(p.getUUID());
        WITHER_OK.remove(p.getUUID());
        Run run = PLAYER_RUN.remove(p.getUUID());
        HEALTH_LOCK.remove(p.getUUID());
        IN_FIGHT.remove(p.getUUID());
        ServerLevel level = (ServerLevel) p.level();
        // 5.18: a Recovery Charm (End Expedition, Void Shop) breaks instead and they keep everything.
        boolean saved = EndRewards.useCharm(p);
        if (!saved) dropAll(p, level);
        p.setHealth(p.getMaxHealth());
        p.clearFire();
        p.removeAllEffects();
        p.getFoodData().setFoodLevel(20);
        p.resetFallDistance();
        p.setDeltaMovement(0, 0, 0);
        // 5.17: a faction mate goes back to their own island, not the captain's.
        Islands.Island own = Islands.islandOf(p.getUUID());
        Islands.Island is = own != null ? own : run != null ? islandById(run.islandId) : null;
        if (run != null) {
            run.inside.remove(p.getUUID());
            for (ServerPlayer m : inside(run)) Lobby.msg(m, "§c" + p.getName().getString() + " " + why + "! §7"
                    + (saved ? "Their Recovery Charm saved their things." : "Their things are where they fell."));
        }
        Lobby.runLater(() -> {
            if (p.hasDisconnected()) return;
            if (is != null) Islands.teleportHome(p, is);
            else Lobby.goToLobby(p);
            Lobby.msg(p, saved ? "§b§lYour Recovery Charm broke §7- you " + why + " in the Nether but kept everything you carried."
                    : "§c§lYou " + why + " in the Nether §7and lost everything you were carrying. §8(Your ender chest is safe.)");
        });
        if (run != null && run.inside.isEmpty()) endRun(run, "nobody is left");
    }

    // ---- Going down: the teammate revive (5.17), then the paid revive (5.15) ----

    /** What a paid revive costs on this trip: REVIVE_SHARE of the difficulty's price. */
    static long reviveCost(Run run) {
        return Math.round(PRICES[run.diff] * REVIVE_SHARE);
    }

    /** Could this player still buy their one paid revive? */
    private static boolean canPay(Run run, UUID id) {
        return !run.revived.contains(id) && Economy.balance(server, id) >= reviveCost(run);
    }

    /** Is any other crew member still on their feet in the Nether? */
    private static boolean teammateUp(Run run, UUID id) {
        for (ServerPlayer m : inside(run))
            if (!m.getUUID().equals(id) && !DOWNED.containsKey(m.getUUID()) && isExpedition(m.level())) return true;
        return false;
    }

    /**
     * Called when a player would die. They go DOWN (can't move or be hurt, guards ignore them) if the team's
     * one teammate revive is still there and someone could use it, or if they could pay for their own revive.
     * Returns false if neither is possible (= they die).
     */
    private static boolean goDown(ServerPlayer p) {
        UUID id = p.getUUID();
        Run run = PLAYER_RUN.get(id);
        if (run == null || run.over) return false;
        boolean team = !run.teamReviveUsed && teammateUp(run, id);
        boolean paid = canPay(run, id);
        if (!team && !paid) {
            if (!run.revived.contains(id)) Lobby.msg(p, "§7(A revive costs " + Economy.format(reviveCost(run)) + " - you didn't have enough.)");
            return false;
        }
        Downed d = new Downed(run, p.getX(), p.getZ(), team ? 0 : 1, ticks + (team ? TEAM_REVIVE_SECONDS : REVIVE_SECONDS) * 20L);
        DOWNED.put(id, d);
        p.setHealth(1f);
        p.clearFire();
        p.setDeltaMovement(0, 0, 0);
        p.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, (TEAM_REVIVE_SECONDS + REVIVE_SECONDS) * 20, 9, false, false));
        p.level().playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.WITHER_SPAWN, SoundSource.PLAYERS, 0.6f, 1.6f);
        if (team) {
            Announce.title(p, "§c§lYOU'RE DOWN", "§7A teammate can pick you up (" + TEAM_REVIVE_SECONDS + "s)", 5, 80, 10);
            MutableComponent line = Component.literal("§c§lYou're down! §7A teammate has §f" + TEAM_REVIVE_SECONDS + " seconds §7to crouch next to you for "
                    + (TEAM_REVIVE_HOLD_TICKS / 20) + " seconds (half health, the team's one pick-up this trip). ");
            if (paid) line.append(button("§a[Revive now for " + Economy.format(reviveCost(run)) + "]", "/expedition revive", "Don't wait: pay and get up at full health")).append(Component.literal(" "));
            line.append(button("§c[Give up]", "/expedition giveup", "Die: drop everything you carry and go home"));
            p.sendSystemMessage(line);
            for (ServerPlayer m : inside(run)) {
                if (m == p) continue;
                Announce.title(m, "§c§l" + p.getName().getString() + " IS DOWN", "§7Crouch next to them for " + (TEAM_REVIVE_HOLD_TICKS / 20) + " seconds!", 5, 60, 10);
                Lobby.msg(m, "§c" + p.getName().getString() + " is down! §7You have §f" + TEAM_REVIVE_SECONDS + " seconds §7to §bcrouch next to them for "
                        + (TEAM_REVIVE_HOLD_TICKS / 20) + " seconds §7(the team's one pick-up this trip).");
            }
        } else {
            offerPaid(p, d, false);
            for (ServerPlayer m : inside(run)) if (m != p) Lobby.msg(m, "§c" + p.getName().getString() + " is down! §7They have " + REVIVE_SECONDS + " seconds to revive.");
        }
        return true;
    }

    /** The paid offer (the second half of being down, or all of it when no teammate can help). */
    private static void offerPaid(ServerPlayer p, Downed d, boolean afterTeam) {
        long cost = reviveCost(d.run);
        Announce.title(p, "§c§lYOU'RE DOWN", "§7Revive for §6" + Economy.format(cost) + "§7? Click in chat (" + REVIVE_SECONDS + "s)", 5, 80, 10);
        p.sendSystemMessage(Component.literal((afterTeam ? "§7Nobody reached you. " : "§c§lYou're down! ") + "§7Once per trip you can pay to revive: full health, right here, nothing lost. ")
                .append(button("§a§l[Revive for " + Economy.format(cost) + "]", "/expedition revive", "Pay " + Economy.format(cost) + " and get back up"))
                .append(Component.literal(" "))
                .append(button("§c§l[Give up]", "/expedition giveup", "Die: drop everything you carry and go home")));
        Lobby.msg(p, "§7You have §f" + REVIVE_SECONDS + " seconds§7. Nothing can hurt you while you choose. No answer = you die.");
    }

    /** Back on their feet: health (a share of full), a few safe seconds, out of the lava if need be. */
    private static void standUp(ServerPlayer p, Downed d, double healthShare) {
        UUID id = p.getUUID();
        DOWNED.remove(id);
        HEALTH_LOCK.remove(id); // even if they fell outside a fight
        p.removeEffect(MobEffects.SLOWNESS);
        p.removeEffect(MobEffects.WITHER);
        p.clearFire();
        p.setHealth((float) Math.max(1.0, p.getMaxHealth() * healthShare));
        p.getFoodData().setFoodLevel(20);
        p.resetFallDistance();
        PROTECTED.put(id, ticks + REVIVE_PROTECT_TICKS);
        ServerLevel nl = level();
        // Fell into lava: coming back there would just kill them again, so Wren's Landing it is.
        if (p.isInLava() && nl != null && d.run.landing != null) {
            BlockPos l = d.run.landing;
            p.teleportTo(nl, l.getX() + 0.5, l.getY(), l.getZ() + 0.5, Set.of(), p.getYRot(), 0f, true);
            Lobby.msg(p, "§6[Captain Wren] §fFished you out of the lava, captain! Back on your feet.");
        }
        p.level().playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.TOTEM_USE, SoundSource.PLAYERS, 1f, 1f);
        ((ServerLevel) p.level()).sendParticles(ParticleTypes.TOTEM_OF_UNDYING, p.getX(), p.getY() + 1, p.getZ(), 40, 0.5, 0.8, 0.5, 0.3);
    }

    /** /expedition revive (the chat button): pay and get back up. Works in either half of being down. */
    static void revive(ServerPlayer p) {
        UUID id = p.getUUID();
        Downed d = DOWNED.get(id);
        if (d == null) {
            Lobby.msg(p, "§7You're not down.");
            return;
        }
        if (d.run.revived.contains(id)) {
            Lobby.msg(p, "§cYou've already used your paid revive on this trip.");
            return;
        }
        long cost = reviveCost(d.run);
        if (!Economy.take(server, id, cost)) {
            Lobby.msg(p, "§cYou don't have " + Economy.format(cost) + " for the revive.");
            if (d.phase == 1) died(p, "died");
            return;
        }
        d.run.revived.add(id);
        standUp(p, d, 1.0);
        Announce.title(p, "§a§lREVIVED!", "§7Nothing can hurt you for " + (REVIVE_PROTECT_TICKS / 20) + " seconds", 5, 40, 10);
        Lobby.msg(p, "§a§lRevived! §7-" + Economy.format(cost) + ". That was your one paid revive for this trip.");
        for (ServerPlayer m : inside(d.run)) if (m != p) Lobby.msg(m, "§a" + p.getName().getString() + " is back up!");
    }

    /** 5.17: a teammate crouched next to them long enough. Uses the team's one pick-up. */
    private static void teamRevive(ServerPlayer p, Downed d, ServerPlayer helper) {
        d.run.teamReviveUsed = true;
        standUp(p, d, TEAM_REVIVE_HEALTH);
        Announce.title(p, "§a§lBACK UP!", "§7" + helper.getName().getString() + " picked you up", 5, 40, 10);
        for (ServerPlayer m : inside(d.run))
            Lobby.msg(m, "§b" + helper.getName().getString() + " §apicked §b" + p.getName().getString() + " §aup! §7(That was the team's one pick-up this trip; paid revives still work.)");
    }

    /** /expedition giveup (the chat button): die now instead of waiting. */
    static void giveUp(ServerPlayer p) {
        if (DOWNED.remove(p.getUUID()) == null) {
            Lobby.msg(p, "§7You're not down.");
            return;
        }
        died(p, "died");
    }

    /** Every tick: downed players stay where they fell; teammates crouching next to them; time up. */
    private static void tickDowned() {
        for (Map.Entry<UUID, Downed> e : new ArrayList<>(DOWNED.entrySet())) {
            ServerPlayer p = server.getPlayerList().getPlayer(e.getKey());
            if (p == null) {
                DOWNED.remove(e.getKey());
                continue;
            }
            Downed d = e.getValue();
            if (!isExpedition(p.level())) {
                DOWNED.remove(e.getKey());
                continue;
            }
            p.clearFire();
            if (Math.abs(p.getX() - d.x) > 0.3 || Math.abs(p.getZ() - d.z) > 0.3) p.teleportTo(d.x, p.getY(), d.z);
            long left = Math.max(0, (d.deadline - ticks) / 20);
            if (d.phase == 0) {
                // 5.17: a teammate crouching next to them.
                ServerPlayer helper = null;
                for (ServerPlayer m : inside(d.run)) {
                    if (m == p || DOWNED.containsKey(m.getUUID()) || !isExpedition(m.level()) || !m.isShiftKeyDown()) continue;
                    if (m.distanceToSqr(p) <= TEAM_REVIVE_RANGE * TEAM_REVIVE_RANGE) {
                        helper = m;
                        break;
                    }
                }
                if (helper != null && !d.run.teamReviveUsed) {
                    d.hold++;
                    if (d.hold >= TEAM_REVIVE_HOLD_TICKS) {
                        teamRevive(p, d, helper);
                        continue;
                    }
                    if (d.hold % 5 == 0) {
                        String bar = "§bPicking up... §f" + (d.hold * 100 / TEAM_REVIVE_HOLD_TICKS) + "%";
                        Announce.actionBar(helper, bar + " §7(keep crouching)");
                        Announce.actionBar(p, bar);
                    }
                } else {
                    d.hold = 0; // a failed attempt uses nothing up
                    if (ticks % 20 == 0) Announce.actionBar(p, "§c§lDOWN §7- a teammate can crouch next to you §f" + left + "s");
                }
                if (ticks >= d.deadline || d.run.teamReviveUsed) {
                    // Nobody came (or the team's pick-up went to someone else): the paid offer, if they can.
                    if (canPay(d.run, e.getKey())) {
                        d.phase = 1;
                        d.hold = 0;
                        d.deadline = ticks + REVIVE_SECONDS * 20L;
                        offerPaid(p, d, true);
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

    private static void dropAll(ServerPlayer p, ServerLevel level) {
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
            ItemEntity e = new ItemEntity(level, p.getX(), p.getY() + 0.5, p.getZ(), s);
            e.setDefaultPickUpDelay();
            e.setDeltaMovement((RNG.nextDouble() - 0.5) * 0.3, 0.2, (RNG.nextDouble() - 0.5) * 0.3);
            level.addFreshEntity(e);
        }
    }

    private static void loggedOut(ServerPlayer p) {
        Run run = PLAYER_RUN.get(p.getUUID());
        if (run == null || !isExpedition(p.level())) return;
        Long hurt = LAST_HURT.get(p.getUUID());
        boolean wasDown = DOWNED.remove(p.getUUID()) != null; // quitting while down is dying
        PROTECTED.remove(p.getUUID());
        WITHER_OK.remove(p.getUUID());
        if (wasDown || (hurt != null && ticks - hurt <= COMBAT_LOG_SECONDS * 20L)) {
            dropAll(p, (ServerLevel) p.level());
            for (ServerPlayer m : inside(run)) if (m != p) Lobby.msg(m, "§c" + p.getName().getString() + " quit in the middle of a fight - their things are where they were.");
        }
        run.inside.remove(p.getUUID());
        PLAYER_RUN.remove(p.getUUID());
        // Off the Nether before the inventory is saved, so it's saved as their island inventory.
        Islands.Island own = Islands.islandOf(p.getUUID());
        Islands.Island is = own != null ? own : islandById(run.islandId);
        if (is != null) Islands.teleportHome(p, is);
        if (run.inside.isEmpty()) endRun(run, "nobody is left");
    }

    static void endRun(Run run, String why) {
        if (run.over) return;
        run.over = true;
        RUNS.remove(run.islandId);
        // Anyone still down when the trip ends didn't get up in time.
        for (UUID id : new ArrayList<>(run.inside)) {
            if (DOWNED.remove(id) == null) continue;
            ServerPlayer dp = server.getPlayerList().getPlayer(id);
            if (dp != null) died(dp, "died");
        }
        if (run.fight != null) run.fight.end();
        if (run.layout != null && run.layout.builder != null) run.layout.builder.release();
        ServerLevel nl = level();
        if (run.layout != null) {
            for (ExpeditionRooms.Room r : run.layout.rooms) {
                TWIST.remove(r);
                WAVE_DUE.remove(r);
                SPLITTING.remove(r);
                ExpeditionTwists.roomDone(nl, r);
                ExpeditionChests.forget(r);
            }
        }
        ExpeditionChests.forget(run);
        for (UUID id : run.crew) {
            HEALTH_LOCK.remove(id);
            IN_FIGHT.remove(id);
            WITHER_OK.remove(id);
        }
        for (UUID id : new ArrayList<>(run.inside)) {
            ServerPlayer p = server.getPlayerList().getPlayer(id);
            if (p != null) extract(p, run, "§6Captain Wren hauls you back up!");
        }
        run.inside.clear();
        for (UUID id : run.crew) PLAYER_RUN.remove(id);
        // Guards, the boss and dropped things are left in the expedition world (it's wiped next start);
        // forget our records of them.
        GUARD_OF.entrySet().removeIf(en -> en.getValue()[0] == run);
        if (nl != null) {
            for (UUID id : new UUID[]{run.wren, run.piglin}) {
                Entity w = id == null ? null : nl.getEntity(id);
                if (w != null) w.discard();
            }
        }
        Islands.Island is = islandById(run.islandId);
        if (is != null) Islands.islandChat(is, "§6[Captain Wren] §fThat's the crew home (" + why + ").");
        Lobby.runLaterTicks(20 * 20, () -> departShip(run.islandId));
        Skyserver.LOGGER.info("Nether Expedition on island {} over: {}", run.islandId, why);
    }

    /** The airship leaves: anyone on the deck goes home first. */
    static void departShip(int islandId) {
        if (RUNS.containsKey(islandId)) return;
        ExpeditionShip.Ship ship = SHIPS.remove(islandId);
        Pending pd = PENDING.remove(islandId);
        Islands.Island is = islandById(islandId);
        if (pd != null && pd.diff >= 0 && !pd.sailing) cancelMuster(null, pd, null);
        if (ship == null) return;
        ExpeditionShip.remove(ship);
    }

    private static void shutdown() {
        for (Run run : new ArrayList<>(RUNS.values())) endRun(run, "the server is restarting");
        for (Map.Entry<Integer, Pending> e : new ArrayList<>(PENDING.entrySet())) refund(e.getValue().payer, e.getValue().ticket);
        for (int id : new ArrayList<>(SHIPS.keySet())) departShip(id);
        PENDING.clear();
    }

    // =====================================================================
    // Every second
    // =====================================================================

    /** Every tick: building maps, the boss fights, falling fireballs, downed players and the health lock. */
    private static void fastTick() {
        if (server == null) return;
        if (!DOWNED.isEmpty()) tickDowned();
        ExpeditionTwists.fastTick();
        for (Run run : new ArrayList<>(RUNS.values())) {
            if (run.over) continue;
            if (run.building) {
                boolean done = run.layout.builder.tick();
                if (ticks % 10 == 0) {
                    int pct = (int) Math.round(run.layout.builder.progress() * 100);
                    for (UUID id : run.crew) {
                        ServerPlayer m = server.getPlayerList().getPlayer(id);
                        if (m != null) Announce.actionBar(m, "§6✈ Captain Wren is flying you to the Nether... §f" + pct + "%");
                    }
                }
                if (done) arrive(run);
                continue;
            }
            if (run.fight != null) run.fight.tick();
            healthLock(run);
        }
    }

    /**
     * Leaving an active fight (a woken arena, or the boss) locks your health and absorption at what they
     * were when you walked out, until you walk back in (or that fight ends). Breaks are fine; healing up isn't.
     */
    private static void healthLock(Run run) {
        List<ExpeditionRooms.Room> fights = new ArrayList<>();
        for (ExpeditionRooms.Room r : run.layout.rooms) if (r.active()) fights.add(r);
        if (run.bossSpawned && !run.bossDead) fights.add(run.layout.arena);
        for (ServerPlayer p : inside(run)) {
            if (!isExpedition(p.level())) continue;
            UUID id = p.getUUID();
            Set<ExpeditionRooms.Room> was = IN_FIGHT.computeIfAbsent(id, k -> new HashSet<>());
            Map<ExpeditionRooms.Room, float[]> locks = HEALTH_LOCK.computeIfAbsent(id, k -> new HashMap<>());
            locks.keySet().removeIf(r -> !fights.contains(r));
            was.removeIf(r -> !fights.contains(r));
            for (ExpeditionRooms.Room r : fights) {
                boolean in = r.inside(p.getX(), p.getY(), p.getZ(), 0.5);
                if (in) {
                    if (locks.remove(r) != null) tip(p, "§aBack in the fight - your health can go up again.");
                    was.add(r);
                } else if (was.remove(r)) {
                    locks.put(r, new float[]{p.getHealth(), p.getAbsorptionAmount()});
                    tip(p, "§eYou left the fight: §cyour health can't go up §euntil you go back into the " + r.name() + "§e.");
                }
            }
            if (locks.isEmpty()) continue;
            float capH = Float.MAX_VALUE, capA = Float.MAX_VALUE;
            for (float[] v : locks.values()) {
                capH = Math.min(capH, v[0]);
                capA = Math.min(capA, v[1]);
            }
            if (p.getHealth() > capH) p.setHealth(capH);
            if (p.getAbsorptionAmount() > capA) p.setAbsorptionAmount(capA);
        }
    }

    private static String healthLockNote(ServerPlayer p) {
        Map<ExpeditionRooms.Room, float[]> locks = HEALTH_LOCK.get(p.getUUID());
        return locks == null || locks.isEmpty() ? "" : "§c❤ locked §8| ";
    }

    private static void tick() {
        if (server == null) return;
        for (Map.Entry<Integer, Pending> e : new ArrayList<>(PENDING.entrySet())) {
            Pending pd = e.getValue();
            if (pd.sailing || RUNS.containsKey(e.getKey())) continue;
            Islands.Island is = islandById(e.getKey());
            // 5.17: the crew is paying up - sail when the time is up.
            if (pd.diff >= 0) {
                if (ticks >= pd.musterEnd) {
                    if (is != null) sail(is, pd);
                    else cancelMuster(null, pd, null);
                }
                continue;
            }
            // Airships nobody used.
            if (ticks - pd.since >= SHIP_WAIT_SECONDS * 20L) {
                PENDING.remove(e.getKey());
                refund(pd.payer, pd.ticket);
                if (is != null) Islands.islandChat(is, "§6[Captain Wren] §fNobody's sailing to the Nether? Your " + (pd.ticket >= 0 ? "ticket" : "deposit") + " is back.");
                departShip(e.getKey());
            }
        }
        for (Run run : new ArrayList<>(RUNS.values())) tickRun(run);
    }

    private static void tickRun(Run run) {
        if (run.building) return; // fastTick handles the flight
        long left = run.timeLimit - (ticks - run.startTick) / 20;
        if (left <= 0) {
            endRun(run, "time ran out");
            return;
        }
        if (left <= 300 && run.warned < 1 || left <= 60 && run.warned < 2) {
            run.warned = left <= 60 ? 2 : 1;
            for (ServerPlayer m : inside(run)) Lobby.msg(m, "§e§l" + (left <= 60 ? "1 MINUTE" : "5 MINUTES") + " LEFT! §7Wren pulls everyone up when time runs out (you keep what you carry).");
        }
        ServerLevel nl = level();
        if (nl == null || run.layout == null) return;
        ExpeditionRooms.Layout lay = run.layout;
        // Only our own mobs live on the map - anything else that turns up goes (the safety net; it logs too).
        for (Mob mob : nl.getEntitiesOfClass(Mob.class, new AABB(run.cx - run.half, ExpeditionRooms.SEA_Y - 10, run.cz - run.half,
                run.cx + run.half, 200, run.cz + run.half), m -> !GUARD_OF.containsKey(m.getUUID()) && !m.entityTags().contains(NETHER_WREN_TAG)
                && !m.entityTags().contains(ExpeditionBarter.PIGLIN_TAG) && !m.entityTags().contains(ExpeditionGold.TRAP_TAG)))
            removeStray(mob, mob.entityTags().contains(GUARD_TAG) ? "an old guard nobody owns, found by the sweep" : "found by the sweep");
        // Guards can't get into Wren's Landing either.
        ExpeditionRooms.Room sp = lay.spawn;
        for (Mob mob : nl.getEntitiesOfClass(Mob.class, new AABB(sp.x0 - 1, sp.y0 - 1, sp.z0 - 1, sp.x1 + 2, sp.y1 + 2, sp.z1 + 2),
                m -> GUARD_OF.containsKey(m.getUUID()))) {
            Object[] g = GUARD_OF.get(mob.getUUID());
            BlockPos back = roomSpot(nl, (ExpeditionRooms.Room) g[1]);
            mob.teleportTo(back.getX() + 0.5, back.getY(), back.getZ() + 0.5);
        }
        // Waves on a timer - the next one comes even if the last isn't dead. 5.17: and the twists' own clocks.
        long sec = ticks / 20;
        for (ExpeditionRooms.Room r : lay.rooms) {
            if (!r.active()) continue;
            ExpeditionTwists.second(nl, run, r, twist(r), sec, RNG);
            if (r.waveComing || r.wave >= r.waves) continue;
            Long due = WAVE_DUE.get(r);
            if (due != null && ticks >= due) nextWave(nl, run, r);
        }
        // Watchdog: the boss fight ended without the boss being killed (its body vanished) - start it over.
        if (run.bossSpawned && !run.bossDead && run.fight != null && run.fight.over) {
            Skyserver.LOGGER.warn("[Expedition] the boss disappeared without being killed - it will come back");
            Entity leftover = run.boss != null ? nl.getEntity(run.boss) : null;
            if (leftover != null) leftover.discard();
            if (run.boss != null) GUARD_OF.remove(run.boss);
            run.boss = null;
            run.fight = null;
            run.bossSpawned = false;
        }
        // Rooms wake when someone walks in; the arena once it's open.
        List<ServerPlayer> players = inside(run);
        for (ServerPlayer p : players) {
            if (!isExpedition(p.level())) continue;
            for (ExpeditionRooms.Room r : lay.rooms) {
                if (!r.woke && r.inside(p.getX(), p.getY(), p.getZ(), 0)) wakeRoom(nl, run, r);
            }
            if (lay.arena.inside(p.getX(), p.getY(), p.getZ(), 0)) {
                if (run.arenaOpen && !run.bossSpawned) spawnBoss(nl, run);
            } else if (!run.arenaOpen && lay.arena.inside(p.getX(), p.getY(), p.getZ(), 6)) {
                tip(p, "§cThe gate opens after §f" + (run.unlockAt - run.cleared) + " §cmore arena" + (run.unlockAt - run.cleared == 1 ? "" : "s") + ".");
            }
            for (ExpeditionRooms.Secret sec2 : lay.secrets) {
                if (sec2.found || !sec2.inside(p.getX(), p.getY(), p.getZ())) continue;
                sec2.found = true;
                nl.playSound(null, sec2.chest, SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, SoundSource.PLAYERS, 1f, 1.4f);
                for (ServerPlayer m : inside(run)) Lobby.msg(m, "§d§l✦ SECRET FOUND! §7" + p.getName().getString() + " found a hidden alcove with a little chest.");
            }
        }
        leashGuards(nl, run);
        // Status line and staying inside the area.
        Entity boss = run.boss != null ? nl.getEntity(run.boss) : null;
        for (ServerPlayer p : players) {
            if (!isExpedition(p.level())) {
                run.inside.remove(p.getUUID());
                PLAYER_RUN.remove(p.getUUID());
                continue;
            }
            if (Math.abs(p.getX() - run.cx) > run.half + 3 || Math.abs(p.getZ() - run.cz) > run.half + 3) {
                p.teleportTo(nl, run.landing.getX() + 0.5, run.landing.getY(), run.landing.getZ() + 0.5, Set.of(), p.getYRot(), 0f, true);
                Lobby.msg(p, "§7Wren: §f\"Too far, captain! Stay near the structure.\"");
            }
            if (DOWNED.containsKey(p.getUUID())) continue; // the revive countdown is showing
            Long lastTip = LAST_TIP.get(p.getUUID());
            if (lastTip != null && ticks - lastTip < 40) continue; // a tip is showing
            p.connection.send(new ClientboundSetActionBarTextPacket(Component.literal(statusLine(run, p, boss, left))));
        }
        if (run.inside.isEmpty()) endRun(run, "nobody is left");
    }

    /** Guards (and the boss) stay in their room: 3 seconds outside and they're put back (no heal). */
    private static void leashGuards(ServerLevel nl, Run run) {
        for (Map.Entry<UUID, Object[]> e : new ArrayList<>(GUARD_OF.entrySet())) {
            if (e.getValue()[0] != run) continue;
            Entity ent = nl.getEntity(e.getKey());
            if (!(ent instanceof Mob mob) || !mob.isAlive()) continue;
            ExpeditionRooms.Room room = (ExpeditionRooms.Room) e.getValue()[1];
            // Only fight people who are in the room. 5.17: each guard goes for the player nearest to IT
            // (before, every guard chased the one player nearest the middle of the room).
            ServerPlayer target = nearestTo(run, room, mob);
            if (target != null) {
                if (mob.getTarget() != target) mob.setTarget(target);
            } else if (mob.getTarget() != null) {
                mob.setTarget(null);
            }
            if (room.inside(mob.getX(), mob.getY(), mob.getZ(), 0.2)) {
                OUTSIDE.remove(mob.getUUID());
                continue;
            }
            int out = OUTSIDE.merge(mob.getUUID(), 1, Integer::sum);
            if (out < LEASH_SECONDS) continue;
            OUTSIDE.remove(mob.getUUID());
            BlockPos spot = roomSpot(nl, room);
            mob.teleportTo(spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5);
            mob.setDeltaMovement(0, 0, 0);
            mob.clearFire();
            mob.resetFallDistance();
        }
    }

    private static final String[] ARROWS = {"⬆", "⬈", "➡", "⬊", "⬇", "⬋", "⬅", "⬉"};

    private static String statusLine(Run run, ServerPlayer p, Entity boss, long left) {
        String head = DIFF_COLORS[run.diff] + DIFF_NAMES[run.diff] + " §7" + (left / 60) + ":" + String.format("%02d", left % 60) + " §8| ";
        if (boss instanceof Mob b && b.isAlive()) {
            String extra = "";
            if (run.fight != null) {
                if (run.fight.shielded) extra = " §6§l[SHIELD " + (run.fight.shieldLeft / 20) + "s]";
                else if (run.fight.weak()) extra = " §e§l[STUNNED - x2]";
                if (run.fight.enraged) extra += " §4§l[ENRAGED]";
            }
            return head + ExpeditionBoss.name(run.fortress) + " §f" + (int) Math.ceil(b.getHealth()) + "/" + (int) b.getMaxHealth() + "❤" + extra;
        }
        if (run.bossDead) return head + "§6Vault open! §7Go back to §6Wren's Landing §7to go home " + pointer(p, run.layout.spawn);
        ExpeditionRooms.Layout lay = run.layout;
        if (run.layout.spawn.inside(p.getX(), p.getY(), p.getZ(), 0.3) && run.cleared == 0)
            return head + "§aSafe in Wren's Landing §8| §7arenas " + run.cleared + "/" + lay.rooms.size() + " " + nextTarget(run, p);
        for (ExpeditionRooms.Room r : lay.rooms)
            if (r.woke && !r.cleared && r.inside(p.getX(), p.getY(), p.getZ(), 1)) {
                int tw = twist(r);
                String next = "";
                Long due = WAVE_DUE.get(r);
                if (r.wave < r.waves && due != null && !r.waveComing) next = " §8| §7next wave §f" + Math.max(0, (due - ticks) / 20) + "s";
                return head + r.name() + (tw >= 0 ? " " + ExpeditionTwists.NAMES[tw] : "") + " §cwave " + r.wave + "/" + r.waves + " §7- guards: §f" + r.guards.size() + next;
            }
        return head + healthLockNote(p) + "§7arenas " + run.cleared + "/" + lay.rooms.size() + " " + nextTarget(run, p);
    }

    private static String nextTarget(Run run, ServerPlayer p) {
        ExpeditionRooms.Room best = null;
        if (run.arenaOpen && !run.bossDead) best = run.layout.arena;
        else {
            double bd = Double.MAX_VALUE;
            for (ExpeditionRooms.Room r : run.layout.rooms) {
                if (r.cleared) continue;
                double d = r.center().distanceTo(p.position());
                if (d < bd) {
                    bd = d;
                    best = r;
                }
            }
        }
        if (best == null) return "";
        return "§8| §fnext: " + best.name() + " " + pointer(p, best);
    }

    /** Arrow (relative to where the player looks), distance, and up/down hint. */
    private static String pointer(ServerPlayer p, ExpeditionRooms.Room r) {
        Vec3 t = r.target(p.getX(), p.getZ());
        double dx = t.x - p.getX(), dz = t.z - p.getZ();
        double dist = Math.hypot(dx, dz);
        if (r.inside(p.getX(), p.getY(), p.getZ(), 0)) return "§a(you're here)";
        float targetYaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float rel = net.minecraft.util.Mth.wrapDegrees(targetYaw - p.getYRot());
        int idx = Math.floorMod(Math.round(rel / 45f), 8);
        double dy = t.y - p.getY();
        String vert = dy > 4 ? " §7(above)" : dy < -4 ? " §7(below)" : "";
        return "§e" + ARROWS[idx] + " §f" + (int) Math.round(dist) + "m" + vert;
    }

    // =====================================================================
    // Little helpers
    // =====================================================================

    static Islands.Island islandById(int id) {
        for (Islands.Island is : Islands.all()) if (is.id == id) return is;
        return null;
    }

    static List<ServerPlayer> islandOnline(Islands.Island is) {
        List<ServerPlayer> out = new ArrayList<>();
        if (server == null) return out;
        for (ServerPlayer p : server.getPlayerList().getPlayers()) if (!(p instanceof BotPlayer) && is.isMember(p.getUUID())) out.add(p);
        return out;
    }

    private static List<ServerPlayer> inside(Run run) {
        List<ServerPlayer> out = new ArrayList<>();
        for (UUID id : run.inside) {
            ServerPlayer p = server.getPlayerList().getPlayer(id);
            if (p != null) out.add(p);
        }
        return out;
    }

    /** 5.17: the crew members standing in this room right now (not the downed). Used by the twists. */
    static List<ServerPlayer> inRoom(Run run, ExpeditionRooms.Room room) {
        List<ServerPlayer> out = new ArrayList<>();
        if (server == null) return out;
        for (ServerPlayer p : inside(run))
            if (isExpedition(p.level()) && !DOWNED.containsKey(p.getUUID()) && room.inside(p.getX(), p.getY(), p.getZ(), 1)) out.add(p);
        return out;
    }

    /** The crew member in this room nearest its middle, or null (used when a guard is first made). */
    private static ServerPlayer nearestIn(Run run, ExpeditionRooms.Room room) {
        ServerPlayer best = null;
        double bd = Double.MAX_VALUE;
        Vec3 c = room.center();
        for (ServerPlayer p : inRoom(run, room)) {
            double d = p.position().distanceToSqr(c);
            if (d < bd) {
                bd = d;
                best = p;
            }
        }
        return best;
    }

    /** 5.17: the crew member in this room nearest to this guard, or null. */
    private static ServerPlayer nearestTo(Run run, ExpeditionRooms.Room room, Mob mob) {
        ServerPlayer best = null;
        double bd = Double.MAX_VALUE;
        for (ServerPlayer p : inRoom(run, room)) {
            double d = p.distanceToSqr(mob);
            if (d < bd) {
                bd = d;
                best = p;
            }
        }
        return best;
    }

    private static String names(List<ServerPlayer> ps) {
        List<String> n = new ArrayList<>();
        for (ServerPlayer p : ps) n.add(p.getName().getString());
        return n.isEmpty() ? "A crew" : String.join(", ", n);
    }

    static MutableComponent button(String text, String command, String hover) {
        return Component.literal(text).withStyle(Style.EMPTY.withClickEvent(new ClickEvent.RunCommand(command))
                .withHoverEvent(new HoverEvent.ShowText(Component.literal(hover))));
    }

    static void status(ServerPlayer p) {
        Lobby.msg(p, "§c§lNETHER EXPEDITIONS §7- a paid co-op trip (up to " + CREW_MAX + ") to a Nether Fortress or Bastion.");
        Lobby.msg(p, "§7Talk to Captain Wren in his ship room (§e/is shiproom§7) -> Nether Expedition (or §e/expedition call§7). Prices, per player: "
                + DIFF_COLORS[0] + "Easy " + Economy.format(PRICES[0]) + "§7, " + DIFF_COLORS[1] + "Normal " + Economy.format(PRICES[1]) + "§7, "
                + DIFF_COLORS[2] + "Hard " + Economy.format(PRICES[2]) + "§7, " + DIFF_COLORS[3] + "Nightmare " + Economy.format(PRICES[3]) + "§7.");
        Lobby.msg(p, "§7Island members and faction mates can come (§e/expedition board §7takes you to the ship room): everyone pays for their own place and gets their own loot and rewards.");
        Lobby.msg(p, "§7Bring your own gear - §cdiamond armor and golden apples are a must, even on Easy. §cDying loses everything you carry. §7Talk to Captain Wren in Wren's Landing to go home with your loot.");
        Run run = PLAYER_RUN.get(p.getUUID());
        if (run != null) Lobby.msg(p, "§eYou're on a " + DIFF_NAMES[run.diff] + " expedition: rooms cleared " + run.cleared + "/" + run.layout.rooms.size()
                + (run.bossDead ? ", boss beaten!" : run.bossSpawned ? ", the boss is out!" : run.arenaOpen ? ", the arena is open!" : ""));
    }
}
