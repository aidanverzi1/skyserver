package com.aidan.skyserver.expedition;

import com.aidan.skyserver.Safe;
import com.aidan.skyserver.Skyserver;
import com.aidan.skyserver.bots.BotPlayer;
import com.aidan.skyserver.island.Islands;
import com.aidan.skyserver.lobby.*;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.datafixers.util.Pair;
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
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
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
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.BuiltinStructures;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/**
 * The Nether Expedition, a paid co-op minigame that starts on your Sky Island (5.8, reworked in 5.9).
 *
 *  1. /is menu -> Nether Expedition: pay the $5,000 deposit (or use a ticket). Captain Wren's airship
 *     arrives in empty sky above the island (ExpeditionShip).
 *  2. Gear up with your OWN armor, weapons and food, then board (right-click the rope, or talk to Wren by the path).
 *  3. Right-click Wren on the deck: pick the difficulty (the deposit counts toward the price) and
 *     Fortress / Bastion / Surprise, read the warning, set sail. Island members on the deck come along.
 *     Only the player who sets sail pays.
 *  4. 5.10: Wren flies you (~10 seconds while the map is built) to a custom arena map floating over a
 *     lava sea (ExpeditionRooms): Wren's Landing in the middle (safe, unbreakable, no mobs; talk to
 *     Wren there to go home), big chest arenas on the platforms around it (3/4/5/6 by difficulty),
 *     joined by railed bridges and an outer ring path. Walk into an arena and the first wave comes.
 *     5.11 (much harder): 3 waves on Easy/Normal, 4 on Hard/Nightmare, and they OVERLAP - the next wave
 *     comes on a timer even if the last one isn't dead. Guards wear armor and carry enchanted weapons,
 *     archers (skeletons, blazes, crossbow piglins) take the balconies, the last wave brings a captain
 *     (Wither Knight / Brute Warlord), and every arena has a twist: Blazing, Ambush or Withering.
 *     No doors lock, but if you leave a fight your health and absorption can't go up until you walk back in.
 *     Beat every wave and the chest opens. After every arena (Easy/Normal) or all but one (Hard/Nightmare)
 *     the Boss Arena's gate opens: the Cinder Colossus (Fortress) or Gilded Tyrant (Bastion), custom
 *     bosses (ExpeditionBoss). Beat it for the Treasure Vault. 1-2 secret alcoves hide behind cracked bricks.
 *     The line above your hotbar points to the nearest arena you haven't cleared.
 *  5. Break and build freely (except Wren's Landing) - everything you carry comes home. After 30 minutes
 *     Wren pulls everyone up.
 *  6. DYING LOSES EVERYTHING YOU CARRIED (it drops where you died; teammates can grab it until the run
 *     ends). Your ender chest is safe. Totems of Undying work.
 *
 * The expedition world is wiped every server start (fresh terrain, no disk build-up). Nothing about
 * runs is saved: a restart sends everyone home with what they carry. Nothing spawns there naturally
 * (IslandSpawnMixin, 5.11).
 */
public final class Expedition {
    public static final ResourceKey<Level> DIM = ResourceKey.create(Registries.DIMENSION, Identifier.fromNamespaceAndPath(Skyserver.MOD_ID, "expedition"));

    // ---- Every number for the expedition (rewards are in ExpeditionLoot) ----
    public static final String[] DIFF_NAMES = Tokens.DIFF_NAMES;
    public static final String[] DIFF_COLORS = {"§a", "§e", "§c", "§4"};
    public static final long[] PRICES = {5_000, 10_000, 20_000, 40_000};
    /** Chest rooms per trip. */
    static final int[] ROOMS = {3, 4, 5, 6};
    /** 5.11: guards in each wave (every extra crew member in the Nether adds 1 to every wave). */
    static final int[][] WAVES = {{3, 4, 5}, {4, 5, 6}, {4, 5, 6, 7}, {5, 6, 7, 8}};
    /** 5.11: seconds until the next wave comes anyway (Ambush arenas: 5 fewer). */
    static final int[] WAVE_SECONDS = {25, 22, 20, 18};
    /** Old 5.10 guard counts (no longer used for waves; kept so nothing that reads it breaks). */
    static final int[] GUARDS = {3, 4, 6, 8};
    /** Extra health / damage for every guard and the boss (0.5 = +50%). 5.11: raised. */
    static final double[] HEALTH = {0.2, 0.4, 0.75, 1.3}, DAMAGE = {0.1, 0.3, 0.6, 1.0};
    /** 5.11: the last wave's captain gets this much more health on top (2.0 = +200%). */
    static final double[] CAPTAIN_HEALTH = {2.0, 2.5, 3.0, 3.5};
    /** 5.11 arena twists. */
    static final int BLAZING = 0, AMBUSH = 1, WITHERING = 2;
    static final String[] TWIST_NAMES = {"§6§lBlazing", "§c§lAmbush", "§8§lWithering"};
    static final String[] TWIST_TIPS = {"§7The floor burns - fire resistance helps", "§7They come through every doorway at once", "§7Every hit withers you"};
    /** Seconds a guard may spend outside its room before it's sent back. */
    static final int LEASH_SECONDS = 3;
    static final int TIME_LIMIT_SECONDS = 30 * 60;
    /** An airship nobody sails within this many seconds leaves and the deposit/ticket comes back. */
    static final int SHIP_WAIT_SECONDS = 10 * 60;
    /** Quitting within this many seconds of being hurt counts as dying. */
    static final int COMBAT_LOG_SECONDS = 15;
    static final String GUARD_TAG = "skyserver_expguard";
    public static final String NETHER_WREN_TAG = "skyserver_expwren";

    /** A called airship that hasn't sailed yet. */
    static final class Pending {
        UUID payer;
        int ticket = -1;        // ticket difficulty used to call it, or -1 = paid the deposit
        int structure = 2;      // 0 Fortress, 1 Bastion, 2 Surprise
        long since;
        boolean sailing = false;
    }

    /** One expedition in progress. */
    static final class Run {
        int islandId, diff;
        boolean fortress;
        UUID leader;
        final Set<UUID> crew = new LinkedHashSet<>();
        final Set<UUID> inside = new LinkedHashSet<>();
        long startTick;
        BlockPos landing;
        double cx, cz;
        int half;
        ExpeditionRooms.Layout layout;
        UUID wren;
        int cleared = 0, unlockAt = 0, kills = 0;
        final int[] roomScrap = {0};
        boolean arenaOpen = false;
        UUID boss;
        boolean bossSpawned = false, bossDead = false, over = false;
        BlockPos vault;
        int warned = 0;
        /** 5.10: the map is being built (the crew waits on the airship), then they arrive. */
        boolean building = true;
        long buildStart;
        ExpeditionBoss.Fight fight;
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
    /** 5.10 health lock: player -> {fight they walked out of -> {health, absorption} when they left}. */
    private static final Map<UUID, Map<ExpeditionRooms.Room, float[]>> HEALTH_LOCK = new HashMap<>();
    /** The active fights each player was standing in last tick. */
    private static final Map<UUID, Set<ExpeditionRooms.Room>> IN_FIGHT = new HashMap<>();
    /** 5.11: each woken arena's twist, when its next wave is due (server tick), and big magma cubes about to split. */
    private static final Map<ExpeditionRooms.Room, Integer> TWIST = new HashMap<>();
    private static final Map<ExpeditionRooms.Room, Long> WAVE_DUE = new HashMap<>();
    private static final Map<ExpeditionRooms.Room, Integer> SPLITTING = new HashMap<>();
    private static int runCounter = 0;
    private static long ticks = 0;
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

    // =====================================================================
    // Setup
    // =====================================================================

    public static void init() {
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
            Safe.run("expedition death", () -> died(p, "died"));
            return false;
        });
        // Wren's Landing is safe; guards can only be hurt by someone standing in their room.
        ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> {
            if (!isExpedition(entity.level())) return true;
            if (entity instanceof ServerPlayer p) {
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
            // 5.11: guards, minions and the boss never hurt each other (stray fireballs, arrows, eruptions).
            if (attacker != null && attacker != entity && GUARD_OF.containsKey(attacker.getUUID())) return false;
            if (!(attacker instanceof ServerPlayer ap)) return true; // lava, falling etc. still hurt
            ExpeditionRooms.Room room = (ExpeditionRooms.Room) g[1];
            if (room.inside(ap.getX(), ap.getY(), ap.getZ(), 1.0)) return true;
            tip(ap, "§cStep inside the room to fight its guards!");
            return false;
        });
        ServerLivingEntityEvents.AFTER_DAMAGE.register((entity, source, base, taken, blocked) -> {
            if (!isExpedition(entity.level())) return;
            Safe.run("expedition after damage", () -> afterDamage(entity, source.getEntity(), taken));
        });
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
            if (!isExpedition(entity.level())) return;
            Safe.run("expedition mob death", () -> mobDied(entity, source.getEntity()));
        });
        // 5.11: the little magma cubes a guard splits into become guards of the same arena.
        ServerEntityEvents.ENTITY_LOAD.register((entity, world) -> {
            if (!isExpedition(world) || entity.getType() != net.minecraft.world.entity.EntityTypes.MAGMA_CUBE || GUARD_OF.containsKey(entity.getUUID())) return;
            if (entity instanceof Mob m) Safe.run("expedition split", () -> adoptSplit(m));
        });
        UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
            if (!isExpedition(level) || level.isClientSide() || !(player instanceof ServerPlayer sp)) return InteractionResult.PASS;
            return useBlock(sp, (ServerLevel) level, hit.getBlockPos());
        });
        UseEntityCallback.EVENT.register((player, level, hand, entity, hit) -> {
            if (!isExpedition(level) || !entity.entityTags().contains(NETHER_WREN_TAG)) return InteractionResult.PASS;
            if (hand == net.minecraft.world.InteractionHand.MAIN_HAND && player instanceof ServerPlayer sp) talkToWren(sp);
            return InteractionResult.SUCCESS;
        });
        AttackEntityCallback.EVENT.register((player, level, hand, entity, hit) ->
                isExpedition(level) && entity.entityTags().contains(NETHER_WREN_TAG) ? InteractionResult.FAIL : InteractionResult.PASS);
        PlayerBlockBreakEvents.BEFORE.register((level, player, pos, state, be) -> {
            if (!isExpedition(level)) return true;
            Run run = PLAYER_RUN.get(player.getUUID());
            if (run == null || run.layout == null) return true;
            if (run.fight != null && run.fight.isPillar(pos)) {
                run.fight.breakPillar(pos);
                return false;
            }
            if (run.layout.locked.contains(pos.asLong())) {
                tip((ServerPlayer) player, run.layout.spawn.inside(pos.getX(), pos.getY(), pos.getZ(), 2)
                        ? "§7Wren's Landing is built to last - it can't be broken." : "§7The Boss Arena's walls are enchanted - they can't be broken.");
                return false;
            }
            for (ExpeditionRooms.Room r : run.layout.rooms) {
                if (pos.equals(r.chest) && !r.cleared) {
                    tip((ServerPlayer) player, "§cThat chest is guarded - beat the room's guards first.");
                    return false;
                }
            }
            return true;
        });
        // Registered before Lobby's (Skyserver init order), so items drop before anything saves the inventory.
        ServerPlayConnectionEvents.DISCONNECT.register((handler, s) -> Safe.run("expedition logout", () -> loggedOut(handler.player)));
        CommandRegistrationCallback.EVENT.register((d, r, e) -> d.register(Commands.literal("expedition")
                .executes(c -> { status(c.getSource().getPlayerOrException()); return 1; })
                .then(Commands.literal("board").executes(c -> { board(c.getSource().getPlayerOrException()); return 1; }))
                .then(Commands.literal("leaveship").executes(c -> { leaveShip(c.getSource().getPlayerOrException()); return 1; }))
                .then(Commands.literal("leave").executes(c -> { leaveCommand(c.getSource().getPlayerOrException()); return 1; }))
                .then(Commands.literal("stay").executes(c -> {
                    Lobby.msg(c.getSource().getPlayerOrException(), "§6[Captain Wren] §fRight you are! I'll keep the kettle on. Come back when you're ready.");
                    return 1;
                }))
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
            if (p != null && isExpedition(p.level())) {
                c.getSource().sendFailure(Component.literal("You're on a Nether Expedition! Go back to Wren's Landing and talk to Captain Wren to go home."));
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

    /** /is menu -> Nether Expedition. ticket = difficulty of the ticket to use, or -1 to pay the deposit. */
    public static void summon(ServerPlayer p, int ticket) {
        Islands.Island is = Islands.islandOf(p.getUUID());
        if (is == null || !Islands.isIsland(p.level()) || Islands.islandAt(p.getX(), p.getZ()) != is) {
            Lobby.msg(p, "§cStand on your own Sky Island to call Captain Wren's airship.");
            return;
        }
        if (SHIPS.containsKey(is.id)) {
            Lobby.msg(p, "§7The airship is already here! §eRight-click its rope §7or talk to Wren by the path to board.");
            return;
        }
        if (level() == null) {
            Lobby.msg(p, "§cThe Nether Expedition world didn't load - tell an admin.");
            return;
        }
        if (ticket >= 0) {
            if (!Tokens.take(p.getUUID(), Tokens.TICKET[ticket], 1)) {
                Lobby.msg(p, "§cYou don't have a " + DIFF_NAMES[ticket] + " ticket.");
                return;
            }
        } else if (!Economy.take(server, p.getUUID(), PRICES[0])) {
            Lobby.msg(p, "§cCalling the airship takes a " + Economy.format(PRICES[0]) + " deposit (it counts toward the trip).");
            return;
        }
        ExpeditionShip.Ship ship = ExpeditionShip.place(Islands.level(), is);
        if (ship == null) {
            refund(p.getUUID(), ticket);
            Lobby.msg(p, "§cWren couldn't find open sky above your island to park the airship. §7(It needs about 21 x 11 blocks of empty air above your builds.)");
            return;
        }
        SHIPS.put(is.id, ship);
        Pending pd = new Pending();
        pd.payer = p.getUUID();
        pd.ticket = ticket;
        pd.since = ticks;
        PENDING.put(is.id, pd);
        p.level().playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.BELL_BLOCK, SoundSource.PLAYERS, 1.5f, 0.8f);
        Islands.islandChat(is, "§6§l⚓ Captain Wren's airship has arrived above the island!");
        for (ServerPlayer m : islandOnline(is)) {
            m.sendSystemMessage(Component.literal("§7Put on your §farmor§7, bring §fweapons and food§7, then ")
                    .append(button("§a§l[Board the airship]", "/expedition board", "Climb aboard (or right-click the rope)")));
        }
        Lobby.msg(p, "§7" + (ticket >= 0 ? "Your " + DIFF_NAMES[ticket] + " ticket pays for the trip." : "Your " + Economy.format(PRICES[0]) + " deposit counts toward the trip.")
                + " The airship waits " + (SHIP_WAIT_SECONDS / 60) + " minutes; if nobody sets sail it leaves and you get it back.");
    }

    private static void refund(UUID id, int ticket) {
        if (ticket >= 0) Tokens.add(id, Tokens.TICKET[ticket], 1);
        else {
            ServerPlayer p = server.getPlayerList().getPlayer(id);
            Economy.add(server, id, p == null ? "?" : p.getName().getString(), PRICES[0]);
        }
    }

    /** Wren by the path, when the airship is here. */
    public static void offerBoard(ServerPlayer p) {
        Lobby.msg(p, "§6[Captain Wren] §fShe's patched up and ready to fly! Gear up - armor, a good blade, some food - and come aboard.");
        p.sendSystemMessage(Component.literal(" ").append(button("§a§l[Board the airship]", "/expedition board", "Climb aboard")));
    }

    /** Onto the deck (the rope, Wren by the path, or /expedition board). */
    public static void board(ServerPlayer p) {
        Islands.Island is = Islands.islandOf(p.getUUID());
        ExpeditionShip.Ship ship = shipOf(is);
        if (ship == null) {
            Lobby.msg(p, "§7There's no airship at your island. §7Call one from §e/is §7-> Nether Expedition.");
            return;
        }
        if (!Islands.isIsland(p.level())) {
            Lobby.msg(p, "§cGo to your island first (§e/is§c).");
            return;
        }
        if (running(is)) {
            Lobby.msg(p, "§7The expedition has already set sail - wait for the crew to come back.");
            return;
        }
        double[] d = ship.deckSpot();
        p.teleportTo(ship.level, d[0], d[1], d[2], Set.of(), (float) d[3], 0f, true);
        p.resetFallDistance();
        Lobby.msg(p, "§6You're aboard! §7Right-click §6Captain Wren §7at the helm to choose a difficulty and set sail. §8(/expedition leaveship to go back down)");
    }

    static void leaveShip(ServerPlayer p) {
        Islands.Island is = Islands.islandOf(p.getUUID());
        ExpeditionShip.Ship ship = shipOf(is);
        if (ship == null || !Islands.isIsland(p.level()) || !ship.onDeck(p.getX(), p.getY(), p.getZ())) {
            Lobby.msg(p, "§7You're not on the airship.");
            return;
        }
        Islands.teleportHome(p, is);
    }

    // =====================================================================
    // Setting sail
    // =====================================================================

    /** What the leader still has to pay for this difficulty (the deposit / ticket already counts). */
    static long owed(Pending pd, int diff) {
        long credit = pd.ticket >= 0 ? PRICES[pd.ticket] : PRICES[0];
        return Math.max(0, PRICES[diff] - credit);
    }

    /** Island members standing on the deck right now. */
    static List<ServerPlayer> crewOnDeck(Islands.Island is) {
        ExpeditionShip.Ship ship = shipOf(is);
        List<ServerPlayer> out = new ArrayList<>();
        if (ship == null) return out;
        for (ServerPlayer m : islandOnline(is))
            if (Islands.isIsland(m.level()) && ship.onDeck(m.getX(), m.getY(), m.getZ())) out.add(m);
        return out;
    }

    static void launch(ServerPlayer leader, int diff) {
        Islands.Island is = Islands.islandOf(leader.getUUID());
        Pending pd = pendingOf(is);
        ExpeditionShip.Ship ship = shipOf(is);
        if (is == null || pd == null || ship == null || pd.sailing || running(is)) return;
        List<ServerPlayer> crew = crewOnDeck(is);
        if (!crew.contains(leader)) {
            Lobby.msg(leader, "§cStand on the deck to set sail.");
            return;
        }
        long owe = owed(pd, diff);
        if (owe > 0 && !Economy.take(server, leader.getUUID(), owe)) {
            Lobby.msg(leader, "§c" + DIFF_NAMES[diff] + " costs " + Economy.format(PRICES[diff]) + " - you still need " + Economy.format(owe) + ".");
            return;
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
            Lobby.msg(leader, "§cWren couldn't find a safe place to land this time - try again.");
            return;
        }
        PENDING.remove(is.id);
    }

    private static Run startRun(Islands.Island is, int diff, boolean fortress, ServerPlayer leader, List<ServerPlayer> crew) {
        ServerLevel nl = level();
        if (nl == null) return null;
        int n = runCounter++;
        // 5.10: every trip gets its own spot in the flat lava-sea world, far from the others.
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
        for (ServerPlayer m : crew) {
            run.crew.add(m.getUUID());
            PLAYER_RUN.put(m.getUUID(), run);
        }
        RUNS.put(is.id, run);
        Skyserver.LOGGER.info("Nether Expedition #{} ({} {}): {} chest arenas, {} blocks to build", n, DIFF_NAMES[diff],
                fortress ? "Fortress" : "Bastion", rooms, run.layout.builder.order.length);
        for (ServerPlayer m : crew) {
            m.closeContainer();
            Lobby.msg(m, "§6[Captain Wren] §fWe're off! Hold onto your hats - it's a short flight to the " + (fortress ? "§4Fortress" : "§6Bastion") + "§f...");
        }
        PlayerStats.add(leader, "expedition", 1);
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
        run.wren = spawnNetherWren(nl, run.layout.wren);
        // The secret alcoves' little chests.
        for (ExpeditionRooms.Secret sec : run.layout.secrets) {
            if (!(nl.getBlockEntity(sec.chest) instanceof Container c)) continue;
            List<ItemStack> items = secretLoot(run);
            for (int i = 0; i < items.size() && i < c.getContainerSize(); i++) c.setItem(RNG.nextInt(c.getContainerSize()), items.get(i));
            c.setChanged();
        }
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
                    + " even if the last isn't dead. A captain leads the last wave.");
            Lobby.msg(m, "§7Clear §f" + run.unlockAt + " §7arena" + (run.unlockAt == 1 ? "" : "s") + " to open the §cBoss Arena§7's gate. "
                    + "§eLeaving a fight locks your health until you go back in. §c§lIf you die, you lose everything you're carrying. §7Talk to §6Captain Wren §7here to go home. ("
                    + (TIME_LIMIT_SECONDS / 60) + " minutes)");
        }
        if (run.inside.isEmpty()) endRun(run, "nobody came");
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
                .append(button("§a§l[Yes, take me home]", "/expedition leave", "Back to the airship with everything you carry"))
                .append(Component.literal(" "))
                .append(button("§c§l[No, not yet]", "/expedition stay", "Keep exploring"))
                .append(Component.literal(" "))
                .append(button("§e[How does this work?]", "/expedition help", "Wren explains the trip")));
    }

    static void howItWorks(ServerPlayer p) {
        Run run = PLAYER_RUN.get(p.getUUID());
        int waves = WAVES[run != null ? run.diff : 0].length;
        Lobby.msg(p, "§6[Captain Wren] §fHere's the lay of the land:");
        Lobby.msg(p, " §7- This pavilion is §6Wren's Landing§7: nothing can hurt you in here and no beast gets in.");
        Lobby.msg(p, " §7- The platforms around it hold §cchest arenas §7(coloured stripes over the doorways). Walk in and the first wave comes;"
                + " " + waves + " waves in all, and §cthe next one won't wait §7for you to finish the last. Archers take the balconies,"
                + " and a §4captain §7leads the last wave. Guards stay in their arena, and you can only hurt them from inside.");
        Lobby.msg(p, " §7- Every arena has a twist: " + TWIST_NAMES[BLAZING] + " §7(burning floor - fire resistance helps), "
                + TWIST_NAMES[AMBUSH] + " §7(they pour in through every doorway), " + TWIST_NAMES[WITHERING] + " §7(their hits wither you).");
        Lobby.msg(p, " §7- Doors never lock - but if you walk out of a fight, your §chealth can't go up §7until you walk back in.");
        Lobby.msg(p, " §7- Beat every wave and the chest opens. The stripes turn §agreen §7when an arena is done.");
        Lobby.msg(p, " §7- Clear " + (run == null ? "enough" : String.valueOf(run.unlockAt)) + " arenas and the §cBoss Arena§7's gate opens. Beat the boss for the §6Treasure Vault§7."
                + " Watch the floor, and hit it hard when it's stunned.");
        Lobby.msg(p, " §7- Bridges have rails, but break a rail or the floor and it's a long drop into lava. §dCracked bricks at floor level hide secrets.");
        Lobby.msg(p, " §7- The line above your hotbar points to the next arena. Mine and build all you like - what you carry comes home.");
        Lobby.msg(p, " §c- If you die, you drop everything you carry. §7Talk to me in here to go home safe.");
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
        for (ExpeditionRooms.Room r : run.layout.rooms) {
            if (!pos.equals(r.chest) || r.cleared) continue;
            int left = guardsLeft(level, r);
            tip(p, r.woke ? "§cGuarded: §f" + left + " guards left" : "§cThis chest is guarded - the guards wake when you step in!");
            return InteractionResult.FAIL;
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

    /** 5.11: archers (skeletons, blazes, and every other piglin - those get crossbows) take the high spots. */
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

    /** 5.11: a spot up high (balconies, walkways, broken pillar tops), or a floor spot if there's none. */
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
        level.addFreshEntity(m);
        return m;
    }

    private static void boost(AttributeInstance a, String id, double amount) {
        if (a == null || amount <= 0) return;
        a.addPermanentModifier(new AttributeModifier(Identifier.fromNamespaceAndPath(Skyserver.MOD_ID, id), amount, AttributeModifier.Operation.ADD_MULTIPLIED_BASE));
    }

    // ---- 5.11: gear for guards ----

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
        if (m != null) gear(level, run, m, ranged);
        return m;
    }

    /** 5.11: the last wave's captain - a Wither Knight (Fortress) or Brute Warlord (Bastion). */
    private static void captain(ServerLevel level, Run run, ExpeditionRooms.Room r) {
        int d = run.diff;
        EntityType<? extends Mob> type = run.fortress ? net.minecraft.world.entity.EntityTypes.WITHER_SKELETON : net.minecraft.world.entity.EntityTypes.PIGLIN_BRUTE;
        BlockPos spot = r.doorsIn.isEmpty() ? roomSpot(level, r) : r.doorsIn.get(RNG.nextInt(r.doorsIn.size()));
        Mob m = spawnGuard(level, run, r, type, false, spot);
        if (m == null) return;
        gear(level, run, m, false);
        Item weapon = run.fortress ? (d >= 2 ? Items.NETHERITE_SWORD : Items.DIAMOND_SWORD) : (d >= 2 ? Items.NETHERITE_AXE : Items.DIAMOND_AXE);
        equip(m, EquipmentSlot.MAINHAND, enchanted(level, weapon, Enchantments.SHARPNESS, d + 1));
        boost(m.getAttribute(Attributes.MAX_HEALTH), "exp_captain", CAPTAIN_HEALTH[d]);
        m.setHealth(m.getMaxHealth());
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
        level.playSound(null, m.blockPosition(), SoundEvents.ENDER_DRAGON_GROWL, SoundSource.HOSTILE, 1.2f, 1.4f);
        for (ServerPlayer p : inside(run))
            if (r.inside(p.getX(), p.getY(), p.getZ(), 1)) Announce.title(p, nm, "§7leads the last wave!", 5, 40, 10);
    }

    /** Someone walked into a chest room for the first time: its guards wake. */
    private static void wakeRoom(ServerLevel level, Run run, ExpeditionRooms.Room r) {
        r.woke = true;
        // 5.11: fixed wave sizes by difficulty, +1 per wave for every extra crew member.
        int extra = Math.max(0, run.inside.size() - 1);
        int[] base = WAVES[run.diff];
        r.waves = base.length;
        r.waveSizes = new int[r.waves];
        for (int w = 0; w < r.waves; w++) r.waveSizes[w] = base[w] + extra;
        r.wave = 0;
        int tw = RNG.nextInt(3);
        TWIST.put(r, tw);
        for (ServerPlayer m : inside(run))
            if (r.inside(m.getX(), m.getY(), m.getZ(), 1)) Lobby.msg(m, TWIST_NAMES[tw] + " arena! " + TWIST_TIPS[tw] + "§7.");
        nextWave(level, run, r);
    }

    /**
     * Sends the next wave into a room. 5.11: archers spawn up high; the first wave appears around the
     * room (Ambush: at every doorway), later ones come through the doorways. The wave after this one
     * is due in WAVE_SECONDS whether this one is dead or not.
     */
    private static void nextWave(ServerLevel level, Run run, ExpeditionRooms.Room r) {
        r.waveComing = false;
        if (run.over || r.cleared || r.wave >= r.waves) return;
        int w = r.wave++;
        boolean last = r.wave >= r.waves;
        int tw = twist(r);
        int count = r.waveSizes[w];
        int offset = RNG.nextInt(8);
        boolean fromDoors = (w > 0 || tw == AMBUSH) && !r.doorsIn.isEmpty();
        for (int i = 0; i < count; i++) {
            EntityType<? extends Mob> type = guardType(run, i + offset + w * 2);
            BlockPos floor;
            if (!fromDoors) floor = roomSpot(level, r);
            else {
                BlockPos door = r.doorsIn.get((i + w) % r.doorsIn.size());
                floor = door.offset(RNG.nextInt(3) - 1, 0, RNG.nextInt(3) - 1);
            }
            spawnFighter(level, run, r, type, isRanged(type, i), floor);
        }
        // Blazing arenas: one more fire mob every wave.
        if (tw == BLAZING) {
            if (run.fortress) spawnFighter(level, run, r, net.minecraft.world.entity.EntityTypes.BLAZE, true, roomSpot(level, r));
            else spawnFighter(level, run, r, net.minecraft.world.entity.EntityTypes.MAGMA_CUBE, false, roomSpot(level, r));
        }
        if (last) captain(level, run, r);
        else WAVE_DUE.put(r, ticks + (WAVE_SECONDS[run.diff] - (tw == AMBUSH ? 5 : 0)) * 20L);
        level.playSound(null, r.floorCenter(), w == 0 ? SoundEvents.WITHER_SKELETON_AMBIENT : SoundEvents.RAVAGER_ROAR, SoundSource.HOSTILE, 1.5f, 0.7f);
        String sub = w == 0 ? (tw >= 0 ? TWIST_NAMES[tw] + " §7- beat every wave to open the chest" : "§7Beat every wave to open the chest")
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

    /** 5.11: a little magma cube split from a guard joins that guard's arena. */
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
    }

    private static void clearRoom(ServerLevel level, Run run, ExpeditionRooms.Room r) {
        if (r.cleared) return;
        r.cleared = true;
        run.cleared++;
        WAVE_DUE.remove(r);
        if (r.chest != null && level.getBlockEntity(r.chest) instanceof Container c) {
            List<ItemStack> loot = ExpeditionLoot.chestLoot(level, run.diff, run.fortress, RNG, run.roomScrap);
            List<Integer> slots = new ArrayList<>();
            for (int i = 0; i < c.getContainerSize(); i++) slots.add(i);
            Collections.shuffle(slots, RNG);
            for (int i = 0; i < loot.size(); i++) {
                if (i < slots.size()) c.setItem(slots.get(i), loot.get(i));
                else level.addFreshEntity(new ItemEntity(level, r.chest.getX() + 0.5, r.chest.getY() + 1, r.chest.getZ() + 0.5, loot.get(i)));
            }
            c.setChanged();
        }
        ExpeditionRooms.markCleared(level, r);
        level.playSound(null, r.floorCenter(), SoundEvents.CHEST_OPEN, SoundSource.BLOCKS, 1f, 0.8f);
        for (ServerPlayer m : inside(run))
            Lobby.msg(m, "§a§lRoom cleared! §7The " + r.name() + "§7's chest is open §8(" + run.cleared + "/" + run.layout.rooms.size() + ")§7.");
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
        Mob body = spawnGuard(level, run, arena, ExpeditionBoss.hitboxType(run.fortress), true, arena.floorCenter());
        if (body == null) return;
        run.boss = body.getUUID();
        run.fight = ExpeditionBoss.start(level, body, run.fortress, run.diff, Math.max(1, run.inside.size()), arena, run.layout.pillarSpots, new ExpeditionBoss.Hooks() {
            @Override
            public List<ServerPlayer> fighters() {
                List<ServerPlayer> out = new ArrayList<>();
                for (ServerPlayer p : inside(run)) if (isExpedition(p.level()) && arena.inside(p.getX(), p.getY(), p.getZ(), 1)) out.add(p);
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

    /** 5.11: after anything in the expedition takes damage. */
    private static void afterDamage(Entity entity, Entity attacker, float taken) {
        if (taken <= 0) return;
        if (entity instanceof ServerPlayer p) {
            LAST_HURT.put(p.getUUID(), ticks);
            // Withering arenas: every guard hit withers you.
            if (attacker != null) {
                Object[] g = GUARD_OF.get(attacker.getUUID());
                if (g != null && g[1] instanceof ExpeditionRooms.Room room && twist(room) == WITHERING)
                    p.addEffect(new MobEffectInstance(MobEffects.WITHER, 80, ((Run) g[0]).diff >= 2 ? 1 : 0));
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
        // The Treasure Vault appears in the middle of the arena.
        BlockPos chest = run.layout.arena.floorCenter();
        for (int i = 0; i < 6 && !level.getBlockState(chest).isAir(); i++) chest = chest.above();
        level.setBlock(chest, Blocks.CHEST.defaultBlockState(), Block.UPDATE_ALL);
        run.vault = chest;
        if (level.getBlockEntity(chest) instanceof Container c) {
            List<ItemStack> items = ExpeditionLoot.vault(level, run.diff, RNG, run.roomScrap[0]);
            for (int i = 0; i < items.size() && i < c.getContainerSize(); i++) c.setItem(i, items.get(i));
            c.setChanged();
        }
        level.playSound(null, chest, SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, SoundSource.PLAYERS, 1.5f, 1f);
        List<ServerPlayer> in = inside(run);
        long each = Math.round(ExpeditionLoot.vaultMoneyTotal(run.diff) / (double) Math.max(1, in.size()));
        int d = run.diff;
        for (ServerPlayer m : in) {
            boolean payer = m.getUUID().equals(run.leader);
            Announce.title(m, "§6§lVICTORY!", "§7The §6Treasure Vault §7is open!", 5, 60, 15);
            Economy.add(server, m.getUUID(), m.getName().getString(), each);
            StringBuilder got = new StringBuilder("§6§lTREASURE VAULT! §a+" + Economy.format(each));
            int[] k = ExpeditionLoot.VAULT_KEYS[d];
            if (payer || RNG.nextInt(100) < ExpeditionLoot.CREW_KEY_CHANCE) {
                Keys.reward(server, m.getUUID(), k[0], k[1], "(Treasure Vault)");
                got.append("§7, ").append(Keys.COLORS[k[0]]).append(Keys.NAMES[k[0]]).append(" Key");
            }
            if (payer && ExpeditionLoot.BONUS_KEY_CHANCE[d] > 0 && RNG.nextInt(100) < ExpeditionLoot.BONUS_KEY_CHANCE[d]) {
                int bt = ExpeditionLoot.BONUS_KEY_TIER[d];
                Keys.reward(server, m.getUUID(), bt, 1, "(Treasure Vault - lucky!)");
                got.append("§7, ").append(Keys.COLORS[bt]).append("§l").append(Keys.NAMES[bt]).append(" Key!");
            }
            int shards = payer ? ExpeditionLoot.VAULT_SHARDS[d] : ExpeditionLoot.VAULT_SHARDS[d] / 2;
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
            Lobby.msg(m, got + " §7- and the vault chest in the arena. §eTalk to Captain Wren in Wren's Landing §7when you're ready to go home.");
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
        if (run.layout != null && !run.layout.spawn.inside(p.getX(), p.getY(), p.getZ(), 0.5)) {
            Lobby.msg(p, "§cGo back to §6Wren's Landing §cfirst - Captain Wren takes you home from there §7("
                    + (int) Math.sqrt(p.distanceToSqr(run.landing.getX(), run.landing.getY(), run.landing.getZ())) + " blocks away).");
            return;
        }
        extract(p, run, "§6Captain Wren hauls you back aboard the airship.");
    }

    /** Home safely with everything carried: onto the deck (or the island home if the ship is gone). */
    static void extract(ServerPlayer p, Run run, String msg) {
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

    /** Dying (or quitting mid-fight): everything carried drops where they are, then home to the island. */
    static void died(ServerPlayer p, String why) {
        Run run = PLAYER_RUN.remove(p.getUUID());
        HEALTH_LOCK.remove(p.getUUID());
        IN_FIGHT.remove(p.getUUID());
        ServerLevel level = (ServerLevel) p.level();
        dropAll(p, level);
        p.setHealth(p.getMaxHealth());
        p.clearFire();
        p.removeAllEffects();
        p.getFoodData().setFoodLevel(20);
        p.resetFallDistance();
        p.setDeltaMovement(0, 0, 0);
        Islands.Island is = run != null ? islandById(run.islandId) : Islands.islandOf(p.getUUID());
        if (run != null) {
            run.inside.remove(p.getUUID());
            for (ServerPlayer m : inside(run)) Lobby.msg(m, "§c" + p.getName().getString() + " " + why + "! §7Their things are where they fell.");
        }
        Lobby.runLater(() -> {
            if (p.hasDisconnected()) return;
            if (is != null) Islands.teleportHome(p, is);
            else Lobby.goToLobby(p);
            Lobby.msg(p, "§c§lYou " + why + " in the Nether §7and lost everything you were carrying. §8(Your ender chest is safe.)");
        });
        if (run != null && run.inside.isEmpty()) endRun(run, "nobody is left");
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
        if (hurt != null && ticks - hurt <= COMBAT_LOG_SECONDS * 20L) {
            dropAll(p, (ServerLevel) p.level());
            for (ServerPlayer m : inside(run)) if (m != p) Lobby.msg(m, "§c" + p.getName().getString() + " quit in the middle of a fight - their things are where they were.");
        }
        run.inside.remove(p.getUUID());
        PLAYER_RUN.remove(p.getUUID());
        // Off the Nether before the inventory is saved, so it's saved as their island inventory.
        Islands.Island is = islandById(run.islandId);
        if (is != null) Islands.teleportHome(p, is);
        if (run.inside.isEmpty()) endRun(run, "nobody is left");
    }

    static void endRun(Run run, String why) {
        if (run.over) return;
        run.over = true;
        RUNS.remove(run.islandId);
        if (run.fight != null) run.fight.end();
        if (run.layout != null && run.layout.builder != null) run.layout.builder.release();
        if (run.layout != null) {
            for (ExpeditionRooms.Room r : run.layout.rooms) {
                TWIST.remove(r);
                WAVE_DUE.remove(r);
                SPLITTING.remove(r);
            }
        }
        for (UUID id : run.crew) {
            HEALTH_LOCK.remove(id);
            IN_FIGHT.remove(id);
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
        ServerLevel nl = level();
        if (nl != null && run.wren != null) {
            Entity w = nl.getEntity(run.wren);
            if (w != null) w.discard();
        }
        Islands.Island is = islandById(run.islandId);
        if (is != null) Islands.islandChat(is, "§6[Captain Wren] §fThat's the crew home (" + why + "). Weighing anchor in 20 seconds - step off the deck!");
        Lobby.runLaterTicks(20 * 20, () -> departShip(run.islandId));
        Skyserver.LOGGER.info("Nether Expedition on island {} over: {}", run.islandId, why);
    }

    /** The airship leaves: anyone on the deck goes down to the island home first. */
    static void departShip(int islandId) {
        if (RUNS.containsKey(islandId)) return;
        ExpeditionShip.Ship ship = SHIPS.remove(islandId);
        PENDING.remove(islandId);
        if (ship == null) return;
        Islands.Island is = islandById(islandId);
        for (ServerPlayer p : ship.level.players())
            if (ship.onDeck(p.getX(), p.getY(), p.getZ()) && is != null) Islands.teleportHome(p, is);
        ExpeditionShip.remove(ship);
        if (is != null) Islands.islandChat(is, "§7Captain Wren's airship sails off into the clouds.");
    }

    private static void shutdown() {
        for (Run run : new ArrayList<>(RUNS.values())) endRun(run, "the server is restarting");
        for (Map.Entry<Integer, Pending> e : new ArrayList<>(PENDING.entrySet())) refund(e.getValue().payer, e.getValue().ticket);
        PENDING.clear();
        for (int id : new ArrayList<>(SHIPS.keySet())) departShip(id);
    }

    // =====================================================================
    // Every second
    // =====================================================================

    /** Every tick: building maps, the boss fights, and the health lock. */
    private static void fastTick() {
        if (server == null) return;
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
        // Airships nobody used.
        for (Map.Entry<Integer, Pending> e : new ArrayList<>(PENDING.entrySet())) {
            Pending pd = e.getValue();
            if (pd.sailing || RUNS.containsKey(e.getKey())) continue;
            if (ticks - pd.since >= SHIP_WAIT_SECONDS * 20L) {
                PENDING.remove(e.getKey());
                refund(pd.payer, pd.ticket);
                Islands.Island is = islandById(e.getKey());
                if (is != null) Islands.islandChat(is, "§6[Captain Wren] §fNobody's coming? I'll be off then - your " + (pd.ticket >= 0 ? "ticket" : "deposit") + " is back.");
                departShip(e.getKey());
            }
        }
        for (Run run : new ArrayList<>(RUNS.values())) tickRun(run);
    }

    private static void tickRun(Run run) {
        if (run.building) return; // fastTick handles the flight
        long left = TIME_LIMIT_SECONDS - (ticks - run.startTick) / 20;
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
        // 5.10: only our guards live on the map - anything else that turns up goes.
        for (Mob mob : nl.getEntitiesOfClass(Mob.class, new AABB(run.cx - run.half, ExpeditionRooms.SEA_Y - 10, run.cz - run.half,
                run.cx + run.half, 200, run.cz + run.half), m -> !GUARD_OF.containsKey(m.getUUID()) && !m.entityTags().contains(NETHER_WREN_TAG))) mob.discard();
        // Guards can't get into Wren's Landing either.
        ExpeditionRooms.Room sp = lay.spawn;
        for (Mob mob : nl.getEntitiesOfClass(Mob.class, new AABB(sp.x0 - 1, sp.y0 - 1, sp.z0 - 1, sp.x1 + 2, sp.y1 + 2, sp.z1 + 2),
                m -> GUARD_OF.containsKey(m.getUUID()))) {
            Object[] g = GUARD_OF.get(mob.getUUID());
            BlockPos back = roomSpot(nl, (ExpeditionRooms.Room) g[1]);
            mob.teleportTo(back.getX() + 0.5, back.getY(), back.getZ() + 0.5);
        }
        // 5.11: waves on a timer - the next one comes even if the last isn't dead.
        for (ExpeditionRooms.Room r : lay.rooms) {
            if (!r.active() || r.waveComing || r.wave >= r.waves) continue;
            Long due = WAVE_DUE.get(r);
            if (due != null && ticks >= due) nextWave(nl, run, r);
        }
        // Rooms wake when someone walks in; the arena once it's open.
        List<ServerPlayer> players = inside(run);
        boolean burn = (ticks / 20) % 4 == 0;
        for (ServerPlayer p : players) {
            if (!isExpedition(p.level())) continue;
            for (ExpeditionRooms.Room r : lay.rooms) {
                if (!r.woke && r.inside(p.getX(), p.getY(), p.getZ(), 0)) wakeRoom(nl, run, r);
                // 5.11: Blazing arenas set you alight now and then (fire resistance stops it).
                if (burn && r.active() && twist(r) == BLAZING && r.inside(p.getX(), p.getY(), p.getZ(), 0.5) && !p.hasEffect(MobEffects.FIRE_RESISTANCE))
                    p.setRemainingFireTicks(Math.max(p.getRemainingFireTicks(), 50));
            }
            if (lay.arena.inside(p.getX(), p.getY(), p.getZ(), 0)) {
                if (run.arenaOpen && !run.bossSpawned) spawnBoss(nl, run);
            } else if (!run.arenaOpen && lay.arena.inside(p.getX(), p.getY(), p.getZ(), 6)) {
                tip(p, "§cThe gate opens after §f" + (run.unlockAt - run.cleared) + " §cmore arena" + (run.unlockAt - run.cleared == 1 ? "" : "s") + ".");
            }
            for (ExpeditionRooms.Secret sec : lay.secrets) {
                if (sec.found || !sec.inside(p.getX(), p.getY(), p.getZ())) continue;
                sec.found = true;
                nl.playSound(null, sec.chest, SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, SoundSource.PLAYERS, 1f, 1.4f);
                for (ServerPlayer m : inside(run)) Lobby.msg(m, "§d§l✦ SECRET FOUND! §7" + p.getName().getString() + " found a hidden alcove with a little chest.");
            }
        }
        // Blazing arenas smoulder while their fight is on.
        for (ExpeditionRooms.Room r : lay.rooms) {
            if (!r.active() || twist(r) != BLAZING) continue;
            Vec3 c = r.center();
            nl.sendParticles(ParticleTypes.FLAME, c.x, c.y + 0.2, c.z, 40, 14, 0.1, 14, 0.01);
            nl.sendParticles(ParticleTypes.LAVA, c.x, c.y + 0.2, c.z, 6, 14, 0.1, 14, 0.0);
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
            // Only fight people who are in the room.
            ServerPlayer target = nearestIn(run, room);
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
                return head + r.name() + (tw >= 0 ? " " + TWIST_NAMES[tw] : "") + " §cwave " + r.wave + "/" + r.waves + " §7- guards: §f" + r.guards.size() + next;
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

    /** The nearest crew member standing in this room, or null. */
    private static ServerPlayer nearestIn(Run run, ExpeditionRooms.Room room) {
        ServerPlayer best = null;
        double bd = Double.MAX_VALUE;
        Vec3 c = room.center();
        for (ServerPlayer p : inside(run)) {
            if (!isExpedition(p.level()) || !room.inside(p.getX(), p.getY(), p.getZ(), 1)) continue;
            double d = p.position().distanceToSqr(c);
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
        Lobby.msg(p, "§c§lNETHER EXPEDITIONS §7- a paid co-op trip to a Nether Fortress or Bastion.");
        Lobby.msg(p, "§7Call Captain Wren's airship from §e/is §7-> Nether Expedition (or §e/expedition call§7). Prices: "
                + DIFF_COLORS[0] + "Easy " + Economy.format(PRICES[0]) + "§7, " + DIFF_COLORS[1] + "Normal " + Economy.format(PRICES[1]) + "§7, "
                + DIFF_COLORS[2] + "Hard " + Economy.format(PRICES[2]) + "§7, " + DIFF_COLORS[3] + "Nightmare " + Economy.format(PRICES[3]) + "§7.");
        Lobby.msg(p, "§7Bring your own gear - §cdiamond armor and golden apples are a must, even on Easy. §cDying loses everything you carry. §7Talk to Captain Wren in Wren's Landing to go home with your loot.");
        Run run = PLAYER_RUN.get(p.getUUID());
        if (run != null) Lobby.msg(p, "§eYou're on a " + DIFF_NAMES[run.diff] + " expedition: rooms cleared " + run.cleared + "/" + run.layout.rooms.size()
                + (run.bossDead ? ", boss beaten!" : run.bossSpawned ? ", the boss is out!" : run.arenaOpen ? ", the arena is open!" : ""));
    }
}
