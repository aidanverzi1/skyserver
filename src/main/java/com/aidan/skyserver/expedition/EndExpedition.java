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
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/**
 * 5.18: THE END EXPEDITION - a paid wave survival on a bigger copy of the End's main island.
 *
 * HOW A TRIP GOES
 *  - From /is (End Expedition button) or /endexp call: pay $15,000 or use an End ticket (Epic key chest). Up to 2
 *    island or faction mates on the island world get MUSTER_SECONDS to join (each pays full or uses a ticket).
 *    Everyone must WEAR FULL NETHERITE armour (it's made for it: Nether Expeditions first).
 *    5.18b: TWO crews can go at once - each gets its own End world (end_expedition / end_expedition_2).
 *  - Start waves with the BUTTON on top of the bedrock podium in the middle (anyone can press it), or with Wren.
 *  - OMENS (EndOmens): before waves 1, 11, 21... Wren offers 2 omens; the crew may take either or both for the
 *    next 10 waves - each taken = +25% Void Fragments for those waves.
 *  - The island is rebuilt (a second or two), then the crew lands on Wren's Pad. Talk to Captain Wren to start
 *    each wave. Between waves you can rest, use the Void Shop, or go home with everything you carry. A break can't
 *    last more than BREAK_SECONDS (Wren starts the next wave).
 *  - Each wave (per player): place at most BUILD_LIMIT blocks and break at most BREAK_LIMIT (the counts reset when
 *    the next wave starts). No buckets. Wren's Pad, the podium and the ledge can't be broken.
 *  - Endermen roam all the time (at most ENDERMAN_MAX, not part of the waves) and drop pearls at the vanilla rate,
 *    but each player can only get PEARL_CAP pearls per round (a wave and the break after it).
 *  - Waves ending in 5 have a mini boss, every 10th a boss (Ender Dragon, Hollow King, The Eye, then round again).
 *    Monsters get tougher every 10 waves (health +50%, damage +40%). Rewards: EndRewards. Monsters: EndMobs.
 *  - After every 10th wave, Wren's Strongbox opens until the next wave starts: whatever you put in goes home.
 *  - Dying loses everything you carry (it drops where you fell; the void drops it on Wren's Pad) - unless you have
 *    a Recovery Charm. Revives work like the Nether: one free teammate pick-up per crew per trip, then a paid revive
 *    (20% of the price) once per player.
 */
public final class EndExpedition {
    private EndExpedition() {}

    /** 5.18b: one End world per crew slot (two crews at once). */
    @SuppressWarnings("unchecked")
    public static final ResourceKey<Level>[] DIMS = new ResourceKey[]{
            ResourceKey.create(Registries.DIMENSION, Identifier.fromNamespaceAndPath(Skyserver.MOD_ID, "end_expedition")),
            ResourceKey.create(Registries.DIMENSION, Identifier.fromNamespaceAndPath(Skyserver.MOD_ID, "end_expedition_2"))};
    public static final ResourceKey<Level> DIM = DIMS[0];
    public static final long PRICE = 15_000, MONEY_PER_TEN = 1_000;
    static final int CREW_MAX = 3, MUSTER_SECONDS = 30;
    static final int BUILD_LIMIT = 5, BREAK_LIMIT = 15, PEARL_CAP = 4, ENDERMAN_MAX = 5;
    static final int BREAK_SECONDS = 300, WAVE_COUNTDOWN_TICKS = 60;
    static final double REVIVE_SHARE = 0.20, TEAM_REVIVE_HEALTH = 0.5, TEAM_REVIVE_RANGE = 2.5;
    static final int TEAM_REVIVE_SECONDS = 30, REVIVE_SECONDS = 30, TEAM_REVIVE_HOLD_TICKS = 100, REVIVE_PROTECT_TICKS = 60;
    static final String WREN_TAG = "skyserver_end_wren", ENDERMAN_TAG = "skyserver_end_enderman";

    static final class Run {
        final UUID leader;
        final int islandId, slot;
        final ResourceKey<Level> dim;
        final List<UUID> crew = new ArrayList<>();
        final Set<UUID> inside = new LinkedHashSet<>();
        final Map<UUID, Boolean> paidWithTicket = new HashMap<>();
        int crewSize = 1, wave = 0, bossPhase = 0, startingIn = -1, breakWarned = 0;
        boolean fighting = false, over = false, strongbox = false, teamReviveUsed = false, bossRewarded = true;
        long waveStartSec = 0, breakStartSec = 0;
        final Set<UUID> mobs = new HashSet<>(), endermen = new HashSet<>(), dragonCrystals = new HashSet<>(), wardenCrystals = new HashSet<>();
        final Set<Long> placed = new HashSet<>();
        /** Per player this round: blocks placed, blocks broken, pearls picked up. */
        final Map<UUID, int[]> counts = new HashMap<>();
        final Set<UUID> revived = new HashSet<>();
        UUID wren, bossId;
        EndMobs.Kind bossKind;
        ServerBossEvent bar;
        EndIsland.Builder builder;
        /** Omens: the 2 Wren offers for the next 10 waves, the ones the crew picked, and the ones in force now. */
        final int[] offered = {-1, -1};
        final Set<Integer> chosen = new LinkedHashSet<>(), active = new LinkedHashSet<>();
        /** Void Fragments per guard this wave (keeps the per-wave total the same with more guards). */
        double fragScale = 1.0;

        Run(UUID leader, int islandId, int slot) {
            this.leader = leader;
            this.islandId = islandId;
            this.slot = slot;
            this.dim = DIMS[slot];
        }

        boolean omen(int id) {
            return active.contains(id);
        }

        int buildLimit() {
            return BUILD_LIMIT + (omen(EndOmens.SHIFTING_GROUND) ? EndOmens.SHIFTING_EXTRA_BLOCKS : 0);
        }

        int pearlCap() {
            return PEARL_CAP + (omen(EndOmens.ENDERSTORM) ? EndOmens.ENDERSTORM_EXTRA_PEARLS : 0);
        }

        int endermanMax() {
            return omen(EndOmens.ENDERSTORM) ? EndOmens.ENDERSTORM_MAX : ENDERMAN_MAX;
        }

        /** Void Fragment bonus from the omens in force (+25% each). */
        double omenBonus() {
            return 1.0 + EndOmens.FRAGMENT_BONUS * active.size();
        }

        int[] counts(UUID id) {
            return counts.computeIfAbsent(id, k -> new int[3]);
        }
    }

    private static final class Muster {
        final UUID leader;
        final String leaderName;
        final int islandId;
        final Map<UUID, Boolean> paid = new LinkedHashMap<>(); // player -> used a ticket
        long deadline;

        Muster(ServerPlayer leader, int islandId) {
            this.leader = leader.getUUID();
            this.leaderName = leader.getName().getString();
            this.islandId = islandId;
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
    private static final Run[] RUNS = new Run[DIMS.length];
    private static final List<Muster> MUSTERS = new ArrayList<>();
    private static final Map<UUID, Downed> DOWNED = new HashMap<>();
    private static final Map<UUID, Long> PROTECTED = new HashMap<>();
    private static long ticks = 0;
    private static final Random RNG = new Random();

    public static boolean isEnd(Level l) {
        return slotOf(l) >= 0;
    }

    static int slotOf(Level l) {
        if (l == null) return -1;
        for (int i = 0; i < DIMS.length; i++) if (l.dimension() == DIMS[i]) return i;
        return -1;
    }

    /** The trip running in this End world (null if none). */
    static Run runIn(Level l) {
        int s = slotOf(l);
        return s < 0 ? null : RUNS[s];
    }

    /** The trip this player is on (null if none). */
    static Run runOf(UUID id) {
        for (Run r : RUNS) if (r != null && r.inside.contains(id)) return r;
        return null;
    }

    static boolean fighting(Run run) {
        return run != null && (run.fighting || run.startingIn >= 0);
    }

    static ServerLevel level(Run run) {
        return server == null || run == null ? null : server.getLevel(run.dim);
    }

    private static int running() {
        int n = 0;
        for (Run r : RUNS) if (r != null) n++;
        return n;
    }

    private static int freeSlot() {
        for (int i = 0; i < RUNS.length; i++) if (RUNS[i] == null && server.getLevel(DIMS[i]) != null) return i;
        return -1;
    }

    private static long sec() {
        return ticks / 20;
    }

    // =====================================================================
    // Setup (started from Expedition.init)
    // =====================================================================

    public static void init() {
        ServerLifecycleEvents.SERVER_STARTING.register(s -> Safe.run("end wipe", () -> {
            try {
                wipe(s);
            } catch (IOException e) {
                Skyserver.LOGGER.warn("Couldn't wipe the End Expedition world: {}", e.toString());
            }
        }));
        ServerLifecycleEvents.SERVER_STARTED.register(s -> {
            server = s;
            for (ResourceKey<Level> d : DIMS)
                if (s.getLevel(d) == null) Skyserver.LOGGER.error("End Expedition dimension {} is missing (resources/data/skyserver/dimension/).", d.identifier());
            Safe.run("end strongbox delivery", () -> EndRewards.deliverAll(s));
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(s -> Safe.run("end stop", EndExpedition::shutdown));
        ServerTickEvents.END_SERVER_TICK.register(s -> {
            ticks++;
            if (!MUSTERS.isEmpty() || running() > 0) Safe.run("end fast tick", EndExpedition::fastTick);
            if (ticks % 20 == 0) for (Run r : RUNS) if (r != null) Safe.run("end tick", () -> tick(r));
        });

        ServerLivingEntityEvents.ALLOW_DEATH.register((entity, source, amount) -> {
            if (!(entity instanceof ServerPlayer p) || p instanceof BotPlayer || runIn(p.level()) == null) return true;
            if (p.getMainHandItem().is(Items.TOTEM_OF_UNDYING) || p.getOffhandItem().is(Items.TOTEM_OF_UNDYING)) return true;
            if (DOWNED.containsKey(p.getUUID())) {
                p.setHealth(1f);
                return false;
            }
            Safe.run("end death", () -> {
                if (!goDown(p)) died(p, "died");
            });
            return false;
        });
        ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> {
            if (!isEnd(entity.level())) return true;
            if (entity instanceof ServerPlayer p) {
                if (DOWNED.containsKey(p.getUUID())) return false;
                Long safe = PROTECTED.get(p.getUUID());
                if (safe != null) {
                    if (ticks < safe) return false;
                    PROTECTED.remove(p.getUUID());
                }
                return true;
            }
            if (entity.entityTags().contains(WREN_TAG)) return false;
            Run run = runIn(entity.level());
            Entity attacker = source.getEntity();
            // Guards never hurt each other (stray arrows, wind charges, bomber blasts).
            return run == null || attacker == null || attacker == entity || !run.mobs.contains(attacker.getUUID()) || !run.mobs.contains(entity.getUUID());
        });
        ServerLivingEntityEvents.AFTER_DAMAGE.register((entity, source, base, taken, blocked) -> {
            Run run = runIn(entity.level());
            if (run == null || blocked || taken <= 0) return;
            Safe.run("end after damage", () -> {
                ServerLevel l = (ServerLevel) entity.level();
                Entity attacker = source.getEntity();
                if (entity instanceof ServerPlayer p && attacker instanceof Mob m && run.mobs.contains(m.getUUID())) {
                    EndMobs.Kind k = EndMobs.KIND.get(m.getUUID());
                    if (k != null) EndMobs.hitPlayer(l, run, p, m, k);
                } else if (entity instanceof Mob m && attacker instanceof ServerPlayer && run.mobs.contains(m.getUUID()) && m.isAlive()) {
                    EndMobs.Kind k = EndMobs.KIND.get(m.getUUID());
                    if (k != null) EndMobs.guardHurt(l, run, m, k);
                }
            });
        });
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
            if (!isEnd(entity.level()) || entity instanceof Player) return;
            Safe.run("end mob death", () -> mobDied(entity, source.getEntity()));
        });
        // Nothing lives here but our own: anything else is removed as it appears.
        ServerEntityEvents.ENTITY_LOAD.register((entity, world) -> {
            if (!isEnd(world)) return;
            if (entity.getType() == EntityTypes.ENDER_PEARL) {
                Run pr = runIn(world);
                if (pr != null && pr.omen(EndOmens.UNSTABLE_PEARLS)) Safe.run("end unstable pearl", () -> EndOmens.wobble(entity));
                return;
            }
            boolean crystal = entity.getType() == EntityTypes.END_CRYSTAL;
            if (!(entity instanceof Mob) && !crystal) return;
            if (ours(entity)) return;
            Lobby.runLaterTicks(1, () -> Safe.run("end stray", () -> {
                if (!entity.isRemoved() && !ours(entity)) entity.discard();
            }));
        });
        UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
            Run run = level.isClientSide() ? null : runIn(level);
            if (run == null || !(player instanceof ServerPlayer sp)) return InteractionResult.PASS;
            // The wave button on top of the podium: anyone on the trip can press it.
            if (hit.getBlockPos().equals(EndIsland.buttonPos())) {
                if (run.inside.contains(sp.getUUID()) && !DOWNED.containsKey(sp.getUUID())) Safe.run("end button", () -> pressButton(sp, run));
                return InteractionResult.PASS;
            }
            ItemStack held = player.getItemInHand(hand);
            if (held.getItem() instanceof BucketItem && !held.is(Items.BUCKET)) {
                tip(sp, "§cNo buckets on the End island!");
                return InteractionResult.FAIL;
            }
            if (!(held.getItem() instanceof BlockItem) || sp.isCreative()) return InteractionResult.PASS;
            BlockPos target = level.getBlockState(hit.getBlockPos()).canBeReplaced() ? hit.getBlockPos() : hit.getBlockPos().relative(hit.getDirection());
            if (EndIsland.locked(target) || EndIsland.onPad(target.getX(), target.getZ(), 0) && target.getY() <= EndIsland.Y0 + 3) {
                tip(sp, "§7Keep Wren's Pad clear, captain.");
                return InteractionResult.FAIL;
            }
            int[] c = run.counts(sp.getUUID());
            if (c[0] >= run.buildLimit()) {
                tip(sp, "§cYou've placed your " + run.buildLimit() + " blocks this wave! §7(More when the next wave starts.)");
                return InteractionResult.FAIL;
            }
            boolean wasAir = level.getBlockState(target).canBeReplaced();
            Lobby.runLaterTicks(1, () -> Safe.run("end place count", () -> {
                if (run.over || !wasAir || level.getBlockState(target).canBeReplaced()) return;
                int[] cc = run.counts(sp.getUUID());
                cc[0]++;
                run.placed.add(target.asLong());
                tip(sp, "§7Blocks placed this wave: §f" + cc[0] + "/" + run.buildLimit());
            }));
            return InteractionResult.PASS;
        });
        UseItemCallback.EVENT.register((player, level, hand) -> {
            if (level.isClientSide() || !(player instanceof ServerPlayer sp)) return InteractionResult.PASS;
            ItemStack held = player.getItemInHand(hand);
            if (held.is(Items.ENDER_PEARL)) Safe.run("voidwalker pearl", () -> EndRewards.pearlThrown(sp, hand, held));
            if (isEnd(level) && held.getItem() instanceof BucketItem && !held.is(Items.BUCKET)) {
                tip(sp, "§cNo buckets on the End island!");
                return InteractionResult.FAIL;
            }
            return InteractionResult.PASS;
        });
        UseEntityCallback.EVENT.register((player, level, hand, entity, hit) -> {
            if (!isEnd(level) || !entity.entityTags().contains(WREN_TAG)) return InteractionResult.PASS;
            if (hand == net.minecraft.world.InteractionHand.MAIN_HAND && player instanceof ServerPlayer sp) Safe.run("end wren", () -> talk(sp));
            return InteractionResult.SUCCESS;
        });
        AttackEntityCallback.EVENT.register((player, level, hand, entity, hit) ->
                isEnd(level) && entity.entityTags().contains(WREN_TAG) ? InteractionResult.FAIL : InteractionResult.PASS);
        PlayerBlockBreakEvents.BEFORE.register((level, player, pos, state, be) -> {
            Run run = runIn(level);
            if (run == null || !(player instanceof ServerPlayer sp) || sp.isCreative()) return true;
            if (EndIsland.locked(pos)) {
                tip(sp, "§7Wren's Pad, the podium and the ledge are built to last.");
                return false;
            }
            int[] c = run.counts(sp.getUUID());
            if (c[1] >= BREAK_LIMIT) {
                tip(sp, "§cYou've broken your " + BREAK_LIMIT + " blocks this wave! §7(More when the next wave starts.)");
                return false;
            }
            c[1]++;
            run.placed.remove(pos.asLong());
            tip(sp, "§7Blocks broken this wave: §f" + c[1] + "/" + BREAK_LIMIT);
            return true;
        });
        // Registered before Lobby's (Expedition.init runs before Lobby), so they're off the island before anything saves.
        ServerPlayConnectionEvents.DISCONNECT.register((handler, s) -> Safe.run("end logout", () -> loggedOut(handler.player)));

        CommandRegistrationCallback.EVENT.register((d, r, e) -> {
            d.register(Commands.literal("voidshop").executes(c -> {
                EndRewards.openShop(c.getSource().getPlayerOrException());
                return 1;
            }));
            d.register(Commands.literal("endexp")
                    .executes(c -> { status(c.getSource().getPlayerOrException()); return 1; })
                    .then(Commands.literal("call").executes(c -> { call(c.getSource().getPlayerOrException(), false); return 1; }))
                    .then(Commands.literal("callticket").executes(c -> { call(c.getSource().getPlayerOrException(), true); return 1; }))
                    .then(Commands.literal("join").executes(c -> { join(c.getSource().getPlayerOrException(), false); return 1; }))
                    .then(Commands.literal("jointicket").executes(c -> { join(c.getSource().getPlayerOrException(), true); return 1; }))
                    .then(Commands.literal("go").executes(c -> { goNow(c.getSource().getPlayerOrException()); return 1; }))
                    .then(Commands.literal("wave").executes(c -> { startWave(c.getSource().getPlayerOrException()); return 1; }))
                    .then(Commands.literal("home").executes(c -> { goHome(c.getSource().getPlayerOrException()); return 1; }))
                    .then(Commands.literal("strongbox").executes(c -> { strongbox(c.getSource().getPlayerOrException()); return 1; }))
                    .then(Commands.literal("omen").then(Commands.argument("which", IntegerArgumentType.integer(1, 2))
                            .executes(c -> { omen(c.getSource().getPlayerOrException(), IntegerArgumentType.getInteger(c, "which")); return 1; })))
                    .then(Commands.literal("revive").executes(c -> { revive(c.getSource().getPlayerOrException()); return 1; }))
                    .then(Commands.literal("giveup").executes(c -> { giveUp(c.getSource().getPlayerOrException()); return 1; }))
                    .then(Commands.literal("help").executes(c -> { help(c.getSource().getPlayerOrException()); return 1; }))
                    .then(Commands.literal("admin").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                            .then(Commands.literal("end").executes(c -> {
                                if (running() == 0 && MUSTERS.isEmpty()) c.getSource().sendFailure(Component.literal("No End Expedition is running."));
                                for (Run rn : RUNS) {
                                    if (rn == null) continue;
                                    for (ServerPlayer p : inside(rn)) extract(p, "§6[Captain Wren] §fAn admin called us home!");
                                    if (RUNS[rn.slot] == rn) endRun(rn, "ended by an admin");
                                }
                                for (Muster m : new ArrayList<>(MUSTERS)) cancelMuster(m, "ended by an admin");
                                return 1;
                            }))
                            .then(Commands.literal("ticket").executes(c -> {
                                ServerPlayer p = c.getSource().getPlayerOrException();
                                Tokens.add(p.getUUID(), Tokens.END_TICKET, 1);
                                c.getSource().sendSuccess(() -> Component.literal("Gave yourself an End Expedition ticket."), false);
                                return 1;
                            }))
                            .then(Commands.literal("setwave").then(Commands.argument("wave", IntegerArgumentType.integer(1, 999)).executes(c -> {
                                int w = IntegerArgumentType.getInteger(c, "wave");
                                Run rr = runOf(c.getSource().getPlayerOrException().getUUID());
                                if (rr == null || fighting(rr)) c.getSource().sendFailure(Component.literal("Only between waves of an End Expedition you're on."));
                                else {
                                    rr.wave = w - 1;
                                    EndOmens.offerIfDue(rr);
                                    c.getSource().sendSuccess(() -> Component.literal("The next wave will be wave " + w + "."), true);
                                }
                                return 1;
                            })))
                            .then(Commands.literal("fragments").then(Commands.argument("amount", IntegerArgumentType.integer(1, 100_000)).executes(c -> {
                                ServerPlayer p = c.getSource().getPlayerOrException();
                                int n = IntegerArgumentType.getInteger(c, "amount");
                                Tokens.add(p.getUUID(), EndRewards.VOID_TOKEN, n);
                                c.getSource().sendSuccess(() -> Component.literal("Gave yourself " + n + " Void Fragments."), false);
                                return 1;
                            })))));
        });
    }

    private static boolean ours(Entity e) {
        Run run = runIn(e.level());
        if (e.entityTags().contains(WREN_TAG)) return run != null && e.getUUID().equals(run.wren);
        if (run == null) return false;
        UUID id = e.getUUID();
        return run.mobs.contains(id) || run.endermen.contains(id) || run.dragonCrystals.contains(id) || run.wardenCrystals.contains(id);
    }

    private static void wipe(MinecraftServer s) throws IOException {
        for (ResourceKey<Level> d : DIMS) {
            Path dir = s.getWorldPath(LevelResource.ROOT).resolve("dimensions").resolve(Skyserver.MOD_ID).resolve(d.identifier().getPath());
            if (!Files.isDirectory(dir)) continue;
            try (var walk = Files.walk(dir)) {
                List<Path> all = new ArrayList<>(walk.toList());
                all.sort(Comparator.reverseOrder());
                for (Path p : all) Files.deleteIfExists(p);
            }
        }
        Skyserver.LOGGER.info("End Expedition worlds wiped (the island is rebuilt for every trip).");
    }

    // =====================================================================
    // Calling Wren, the muster, leaving
    // =====================================================================

    /** The /is menu button. */
    public static ItemStack islandButton(ServerPlayer p) {
        Run mine = runOf(p.getUUID());
        int busy = running() + MUSTERS.size();
        String state = mine != null ? "§eYou're in the End (wave " + mine.wave + ")"
                : busy >= DIMS.length ? "§cBoth End islands are busy - try again soon" : "§eClick to call Wren to the End §8(" + busy + "/" + DIMS.length + " islands in use)";
        return Stacks.named(new ItemStack(Items.END_STONE), "§5§lEnd Expedition",
                "§7Survive waves of End monsters on a big", "§7copy of the End island. You start each wave.",
                "§7Mini boss every 5 waves, boss every 10", "§7(the first is the Ender Dragon).",
                "§7Price: §6" + Economy.format(PRICE) + " §7each, or an §5End ticket §7(you have " + Tokens.count(p.getUUID(), Tokens.END_TICKET) + ")",
                "§7Up to " + CREW_MAX + " sail (island or faction mates).", "§5Everyone must wear full netherite armour.", "§c§lDying loses everything you carry!", state);
    }

    /** Clicked in the /is menu: offers pay / ticket in chat. */
    public static void offer(ServerPlayer p) {
        p.closeContainer();
        MutableComponent line = Component.literal("§6[Captain Wren] §fThe End, captain? Waves, endermen and a dragon - §5full netherite only§f. §7Each place costs ");
        line.append(Expedition.button("§a[Pay " + Economy.format(PRICE) + "]", "/endexp call", "Pay and call the crew"));
        int t = Tokens.count(p.getUUID(), Tokens.END_TICKET);
        if (t > 0) line.append(Component.literal(" ")).append(Expedition.button("§5[Use an End ticket (" + t + ")]", "/endexp callticket", "Use a ticket instead of paying"));
        line.append(Component.literal(" ")).append(Expedition.button("§7[How it works]", "/endexp help", "What happens on the End Expedition"));
        p.sendSystemMessage(line);
    }

    private static boolean pay(ServerPlayer p, boolean ticket) {
        if (ticket) {
            if (Tokens.take(p.getUUID(), Tokens.END_TICKET, 1)) return true;
            Lobby.msg(p, "§cYou don't have an End Expedition ticket.");
            return false;
        }
        if (Economy.take(server, p.getUUID(), PRICE)) return true;
        Lobby.msg(p, "§cA place on the End Expedition costs " + Economy.format(PRICE) + ".");
        return false;
    }

    private static void refund(UUID id, boolean ticket) {
        ServerPlayer p = server.getPlayerList().getPlayer(id);
        if (ticket) Tokens.add(id, Tokens.END_TICKET, 1);
        else Economy.add(server, id, p == null ? "" : p.getName().getString(), PRICE);
        if (p != null) Lobby.msg(p, "§7Your End Expedition " + (ticket ? "ticket was" : "money was") + " given back.");
    }

    private static boolean busy(ServerPlayer p) {
        if (runOf(p.getUUID()) != null || musterOf(p.getUUID()) != null) {
            Lobby.msg(p, "§7You're already on an End Expedition crew.");
            return true;
        }
        if (running() + MUSTERS.size() >= DIMS.length || freeSlot() < 0) {
            Lobby.msg(p, "§cBoth End islands are busy. §7Try again soon, or ask a crew that's mustering to let you join.");
            return true;
        }
        return false;
    }

    private static Muster musterOf(UUID id) {
        for (Muster m : MUSTERS) if (m.paid.containsKey(id)) return m;
        return null;
    }

    /** 5.18b: the End is made for netherite - all 4 armour pieces must be netherite. */
    static boolean netherite(ServerPlayer p) {
        EquipmentSlot[] slots = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};
        for (EquipmentSlot s : slots) {
            ItemStack a = p.getItemBySlot(s);
            if (a.isEmpty() || !net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(a.getItem()).getPath().startsWith("netherite_")) return false;
        }
        return true;
    }

    private static boolean geared(ServerPlayer p) {
        if (netherite(p)) return true;
        Lobby.msg(p, "§6[Captain Wren] §fNot in that, captain. §7The End is for §5full netherite armour §7(helmet, chestplate, leggings, boots) - get it from Nether Expeditions first.");
        return false;
    }

    static void call(ServerPlayer p, boolean ticket) {
        if (!Islands.isIsland(p.level())) {
            Lobby.msg(p, "§cCall Captain Wren from the island world (/is).");
            return;
        }
        Islands.Island is = Islands.islandOf(p.getUUID());
        if (is == null) {
            Lobby.msg(p, "§cYou need a Sky Island first.");
            return;
        }
        if (busy(p) || !geared(p) || !pay(p, ticket)) return;
        Muster mu = new Muster(p, is.id);
        mu.paid.put(p.getUUID(), ticket);
        mu.deadline = ticks + MUSTER_SECONDS * 20L;
        MUSTERS.add(mu);
        Lobby.msg(p, "§6[Captain Wren] §fRight, to the End! §7Your crew has §f" + MUSTER_SECONDS + " seconds §7to join (up to " + (CREW_MAX - 1) + "). ");
        p.sendSystemMessage(Component.literal("§7").append(Expedition.button("§a[Sail now]", "/endexp go", "Leave without waiting")));
        for (ServerPlayer m : eligible(p)) {
            MutableComponent line = Component.literal("§5" + p.getName().getString() + " §7is sailing to the §5§lEnd Expedition§7 (full netherite)! Join in " + MUSTER_SECONDS + "s: ");
            line.append(Expedition.button("§a[Join - " + Economy.format(PRICE) + "]", "/endexp join", "Pay for your own place"));
            if (Tokens.count(m.getUUID(), Tokens.END_TICKET) > 0) line.append(Component.literal(" ")).append(Expedition.button("§5[Join with a ticket]", "/endexp jointicket", "Use an End ticket"));
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

    static void join(ServerPlayer p, boolean ticket) {
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
            Lobby.msg(p, "§7No crew of your island or faction is getting ready to sail to the End right now.");
            return;
        }
        if (!geared(p)) return;
        if (mu.paid.size() >= CREW_MAX) {
            Lobby.msg(p, "§cThe crew is full (" + CREW_MAX + " at most).");
            return;
        }
        if (!pay(p, ticket)) return;
        mu.paid.put(p.getUUID(), ticket);
        for (UUID id : mu.paid.keySet()) {
            ServerPlayer m = server.getPlayerList().getPlayer(id);
            if (m != null) Lobby.msg(m, "§a" + p.getName().getString() + " joined the End crew §7(" + mu.paid.size() + "/" + CREW_MAX + ").");
        }
        if (mu.paid.size() >= CREW_MAX) depart(mu);
    }

    static void goNow(ServerPlayer p) {
        Muster mu = musterOf(p.getUUID());
        if (mu == null || !mu.leader.equals(p.getUUID())) {
            Lobby.msg(p, "§7Only the captain who called Wren can set sail early.");
            return;
        }
        depart(mu);
    }

    private static void cancelMuster(Muster mu, String why) {
        if (!MUSTERS.remove(mu)) return;
        for (Map.Entry<UUID, Boolean> e : mu.paid.entrySet()) refund(e.getKey(), e.getValue());
        Skyserver.LOGGER.info("[End] muster cancelled: {}", why);
    }

    /** Everyone who paid, is still on the island world and wears netherite goes; the rest get their money / ticket back. */
    private static void depart(Muster mu) {
        if (!MUSTERS.remove(mu)) return;
        int slot = freeSlot();
        if (slot < 0) {
            for (Map.Entry<UUID, Boolean> e : mu.paid.entrySet()) refund(e.getKey(), e.getValue());
            return;
        }
        ServerLevel l = server.getLevel(DIMS[slot]);
        Run run = new Run(mu.leader, mu.islandId, slot);
        for (Map.Entry<UUID, Boolean> e : mu.paid.entrySet()) {
            ServerPlayer m = server.getPlayerList().getPlayer(e.getKey());
            if (m != null && Islands.isIsland(m.level()) && netherite(m) && run.crew.size() < CREW_MAX) {
                run.crew.add(m.getUUID());
                run.paidWithTicket.put(m.getUUID(), e.getValue());
            } else refund(e.getKey(), e.getValue());
        }
        if (run.crew.isEmpty()) return;
        run.crewSize = run.crew.size();
        run.builder = new EndIsland.Builder(l);
        RUNS[slot] = run;
        for (UUID id : run.crew) {
            ServerPlayer m = server.getPlayerList().getPlayer(id);
            if (m != null) Announce.title(m, "§5§lTO THE END", "§7Captain Wren is charting the course...", 5, 60, 10);
        }
    }

    /** The island is ready: the crew lands on Wren's Pad. */
    private static void arrive(Run run) {
        ServerLevel l = level(run);
        run.builder = null;
        double[] w = EndIsland.wrenSpot();
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
        double[] at = EndIsland.landing();
        for (UUID id : run.crew) {
            ServerPlayer p = server.getPlayerList().getPlayer(id);
            if (p == null || !Islands.isIsland(p.level())) {
                refund(id, run.paidWithTicket.getOrDefault(id, false));
                continue;
            }
            p.teleportTo(l, at[0], at[1], at[2], Set.of(), (float) at[3], 0f, true);
            p.resetFallDistance();
            run.inside.add(id);
            Announce.title(p, "§5§lTHE END EXPEDITION", "§7Press the button on the bedrock podium to start wave 1", 10, 70, 20);
        }
        run.crewSize = Math.max(1, Math.min(CREW_MAX, run.inside.size()));
        run.breakStartSec = sec();
        if (run.inside.isEmpty()) {
            endRun(run, "nobody boarded");
            return;
        }
        for (ServerPlayer p : inside(run)) help(p);
        EndOmens.offerIfDue(run);
        Skyserver.LOGGER.info("[End] trip started with {} player(s)", run.inside.size());
    }

    static void help(ServerPlayer p) {
        Lobby.msg(p, "§5§lEND EXPEDITION §7- start each wave with the §fbutton on top of the bedrock podium §7in the middle (or talk to §6Captain Wren§7).");
        Lobby.msg(p, "§7Each wave you may §fplace " + BUILD_LIMIT + " §7and §fbreak " + BREAK_LIMIT + " §7blocks. No buckets. Endermen drop pearls (§f" + PEARL_CAP + " per round§7) - pearls are how you get around.");
        Lobby.msg(p, "§7Knocked off? There's a §fledge §7under the rim all the way round - pearl back up. The tallest pillar has a §fstaircase§7.");
        Lobby.msg(p, "§7Mini boss every 5 waves, boss every 10. §6$1,000 §7per 10 waves. After each boss, §5Wren's Strongbox §7sends things home.");
        Lobby.msg(p, "§c§lDying loses everything you carry §7(a §bRecovery Charm §7saves it once). Go home between waves with Wren. Spend §5Void Fragments §7back on your island (§e/voidshop§7).");
    }

    static void status(ServerPlayer p) {
        Run run = runOf(p.getUUID());
        if (run != null) {
            int[] c = run.counts(p.getUUID());
            Lobby.msg(p, "§5End Expedition §7- wave §f" + run.wave + (run.fighting ? " §c(fighting)" : " §a(break)") + "§7. Placed §f" + c[0] + "/" + run.buildLimit()
                    + "§7, broken §f" + c[1] + "/" + BREAK_LIMIT + "§7, pearls §f" + c[2] + "/" + run.pearlCap() + "§7. Omens: " + EndOmens.list(run.active));
            return;
        }
        int busy = running() + MUSTERS.size();
        Lobby.msg(p, "§5End Expedition §7- " + busy + "/" + DIMS.length + " islands in use"
                + ". §7Your best wave: §f" + EndRewards.Progress.best(p.getUUID()) + "§7. Void Fragments: §5" + EndRewards.balance(p) + " §8(/voidshop)");
        offer(p);
    }

    // =====================================================================
    // Captain Wren on the island
    // =====================================================================

    static void talk(ServerPlayer p) {
        Run run = runOf(p.getUUID());
        if (run == null) return;
        if (fighting(run)) {
            Lobby.msg(p, "§6[Captain Wren] §fNo time to chat - fight! §7(" + aliveMobs(run) + " left)");
            return;
        }
        int next = run.wave + 1;
        Lobby.msg(p, "§6[Captain Wren] §fReady when you are. §7Next: wave §f" + next + " §7- " + EndMobs.preview(next));
        MutableComponent line = Component.literal("§7");
        line.append(Expedition.button("§a§l[Start wave " + next + "]", "/endexp wave", "Send in the next wave"));
        if (run.strongbox) line.append(Component.literal(" ")).append(Expedition.button("§5[Strongbox]", "/endexp strongbox", "Send things home (open until the next wave)"));
        line.append(Component.literal(" ")).append(Expedition.button("§6[Go home]", "/endexp home", "Leave with everything you carry"));
        p.sendSystemMessage(line);
        if (run.offered[0] >= 0) EndOmens.showOffer(run, p);
    }

    static void startWave(ServerPlayer p) {
        Run run = runOf(p.getUUID());
        if (run == null || run.builder != null) {
            Lobby.msg(p, "§7You're not on the End Expedition.");
            return;
        }
        if (fighting(run)) {
            Lobby.msg(p, "§7A wave is already on!");
            return;
        }
        beginWave(run, p.getName().getString());
    }

    /** The button on top of the podium. */
    private static void pressButton(ServerPlayer p, Run run) {
        if (run.builder != null) return;
        if (fighting(run)) {
            Announce.actionBar(p, "§7A wave is already on! §f" + run.mobs.size() + " §7left");
            return;
        }
        beginWave(run, p.getName().getString());
    }

    static void omen(ServerPlayer p, int which) {
        Run run = runOf(p.getUUID());
        if (run == null) return;
        if (fighting(run) || run.offered[which - 1] < 0) {
            Lobby.msg(p, "§7Omens can only be picked before waves 1, 11, 21... (between waves).");
            return;
        }
        EndOmens.toggle(run, p, which - 1);
    }

    private static void beginWave(Run run, String by) {
        run.wave++;
        run.counts.clear();
        run.strongbox = false;
        run.startingIn = WAVE_COUNTDOWN_TICKS;
        if (run.wave % 10 == 1) EndOmens.blockStarts(level(run), run);
        String what = EndMobs.preview(run.wave);
        for (ServerPlayer m : inside(run)) {
            Announce.title(m, "§5§lWAVE " + run.wave, what, 5, 50, 10);
            Lobby.msg(m, "§7" + by + " started §fwave " + run.wave + "§7 - " + what + "§7. Your block and pearl limits are fresh.");
        }
    }

    static void goHome(ServerPlayer p) {
        Run run = runOf(p.getUUID());
        if (run == null) return;
        if (fighting(run)) {
            Lobby.msg(p, "§cNot in the middle of a wave! §7Finish it, then talk to Wren.");
            return;
        }
        if (DOWNED.containsKey(p.getUUID())) return;
        extract(p, "§6[Captain Wren] §fHome you go, captain. §7You made it to wave §f" + run.wave + "§7.");
    }

    static void strongbox(ServerPlayer p) {
        Run run = runOf(p.getUUID());
        if (run == null || DOWNED.containsKey(p.getUUID())) return;
        if (!run.strongbox || fighting(run)) {
            Lobby.msg(p, "§7Wren's Strongbox only opens after every 10th wave (until the next wave starts).");
            return;
        }
        EndRewards.openStrongbox(p);
    }

    // =====================================================================
    // The loop
    // =====================================================================

    private static void fastTick() {
        for (Muster mu : new ArrayList<>(MUSTERS)) if (ticks >= mu.deadline) depart(mu);
        for (Run run : RUNS) if (run != null) fastTick(run);
        tickDowned();
    }

    private static void fastTick(Run run) {
        ServerLevel l = level(run);
        if (l == null) return;
        if (run.builder != null) {
            if (run.builder.step()) arrive(run);
            return;
        }
        if (run.startingIn >= 0) {
            if (run.startingIn % 20 == 0 && run.startingIn > 0)
                for (ServerPlayer m : inside(run)) Announce.actionBar(m, "§5Wave " + run.wave + " in §f" + run.startingIn / 20 + "...");
            if (run.startingIn-- == 0) {
                run.fighting = true;
                run.waveStartSec = sec();
                run.bossRewarded = true;
                run.bossId = null;
                run.fragScale = EndMobs.fragmentScale(run, run.wave);
                EndMobs.spawnWave(l, run, run.wave);
                if (run.bossId != null) {
                    run.bossRewarded = false;
                    run.bar = new ServerBossEvent(UUID.randomUUID(), Component.literal(run.bossKind.display), BossEvent.BossBarColor.PURPLE, BossEvent.BossBarOverlay.NOTCHED_10);
                    for (ServerPlayer m : inside(run)) run.bar.addPlayer(m);
                    l.playSound(null, BlockPos.containing(EndIsland.landing()[0], EndIsland.Y0, EndIsland.landing()[2]), SoundEvents.ENDER_DRAGON_GROWL, SoundSource.HOSTILE, 2f, 0.8f);
                }
            }
        }
        if (run.fighting) {
            EndMobs.fastTick(l, run);
            EndOmens.fastTick(l, run);
        }
    }

    private static void tick(Run run) {
        ServerLevel l = level(run);
        if (l == null || run.builder != null || run.over) return;
        // Anyone gone from the island (logged out, teleported away) is out of the trip.
        for (UUID id : new ArrayList<>(run.inside)) {
            ServerPlayer p = server.getPlayerList().getPlayer(id);
            if (p != null && isEnd(p.level())) continue;
            run.inside.remove(id);
            DOWNED.remove(id);
            EndRewards.deliverStrongbox(server, id);
            if (p != null && run.bar != null) run.bar.removePlayer(p);
        }
        if (run.inside.isEmpty()) {
            endRun(run, "nobody is left");
            return;
        }
        long s = sec();
        if (s % 2 == 0) endermen(l, run);
        EndIsland.keepButton(l);
        if (run.fighting) {
            for (UUID id : new ArrayList<>(run.mobs)) {
                Entity e = l.getEntity(id);
                if (e != null && e.isAlive()) continue;
                if (id.equals(run.bossId) && !run.bossRewarded) bossDown(l, run);
                run.mobs.remove(id);
                EndMobs.KIND.remove(id);
            }
            EndMobs.second(l, run, s);
            EndOmens.second(l, run, s);
            if (run.bar != null && run.bossId != null && l.getEntity(run.bossId) instanceof Mob b && b.isAlive())
                run.bar.setProgress(Math.max(0f, Math.min(1f, b.getHealth() / b.getMaxHealth())));
            if (run.mobs.isEmpty()) waveCleared(l, run);
            else for (ServerPlayer p : fighters(run)) if (s % 3 == 0) Announce.actionBar(p, "§5Wave " + run.wave + " §7- §f" + run.mobs.size() + " §7left");
            return;
        }
        if (run.startingIn >= 0) return;
        long rest = s - run.breakStartSec;
        if (rest >= BREAK_SECONDS - 60 && run.breakWarned == 0) {
            run.breakWarned = 1;
            for (ServerPlayer p : inside(run)) Lobby.msg(p, "§6[Captain Wren] §fOne minute, captain - then the next wave comes whether we're ready or not.");
        } else if (rest >= BREAK_SECONDS - 10 && run.breakWarned == 1) {
            run.breakWarned = 2;
            for (ServerPlayer p : inside(run)) Announce.title(p, "§c10 seconds", "§7until the next wave", 0, 30, 10);
        } else if (rest >= BREAK_SECONDS) {
            beginWave(run, "Captain Wren");
        }
    }

    private static int aliveMobs(Run run) {
        ServerLevel l = level(run);
        int n = 0;
        if (l != null) for (UUID id : run.mobs) if (l.getEntity(id) instanceof Mob m && m.isAlive()) n++;
        return n;
    }

    /** Keeps up to ENDERMAN_MAX endermen roaming the island. */
    private static void endermen(ServerLevel l, Run run) {
        run.endermen.removeIf(id -> !(l.getEntity(id) instanceof Mob m) || !m.isAlive());
        for (UUID id : run.endermen) {
            if (l.getEntity(id) instanceof Mob m && (m.getY() < EndIsland.Y0 - 25 || Math.hypot(m.getX(), m.getZ()) > EndIsland.R + 14)) {
                BlockPos g = EndMobs.ground(l, run, 10, EndIsland.R - 8, 8);
                m.teleportTo(g.getX() + 0.5, g.getY(), g.getZ() + 0.5);
            }
        }
        if (run.omen(EndOmens.ENDERSTORM) && run.fighting) {
            for (UUID id : run.endermen) {
                if (l.getEntity(id) instanceof Mob m && m.getTarget() == null) {
                    ServerPlayer t = nearest(run, m.position());
                    if (t != null) m.setTarget(t);
                }
            }
        }
        if (run.endermen.size() >= run.endermanMax()) return;
        BlockPos g = EndMobs.ground(l, run, 10, EndIsland.R - 6, 12);
        Mob m = EntityTypes.ENDERMAN.create(l, EntitySpawnReason.EVENT);
        if (m == null) return;
        m.snapTo(g.getX() + 0.5, g.getY(), g.getZ() + 0.5, RNG.nextFloat() * 360f, 0f);
        m.setPersistenceRequired();
        m.addTag(ENDERMAN_TAG);
        run.endermen.add(m.getUUID());
        l.addFreshEntity(m);
    }

    private static void waveCleared(ServerLevel l, Run run) {
        run.fighting = false;
        run.breakStartSec = sec();
        run.breakWarned = 0;
        int n = run.wave;
        removeCrystals(l, run);
        if (run.bar != null) {
            run.bar.removeAllPlayers();
            run.bar = null;
        }
        List<ServerPlayer> crew = inside(run);
        for (ServerPlayer p : crew) EndRewards.Progress.best(p.getUUID(), n);
        if (n % 10 == 0) {
            EndRewards.milestone(server, crew, n);
            run.strongbox = true;
            EndOmens.blockEnds(l, run);
            EndOmens.offerIfDue(run);
        }
        for (ServerPlayer p : crew) {
            Announce.title(p, "§a§lWAVE " + n + " CLEARED", n % 10 == 0 ? "§5Wren's Strongbox is open" : "§7Talk to Wren when you're ready", 5, 50, 10);
            p.level().playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 1f, 1f);
            talk(p);
        }
        Skyserver.LOGGER.info("[End] wave {} cleared", n);
    }

    private static void bossDown(ServerLevel l, Run run) {
        run.bossRewarded = true;
        EndMobs.Kind k = run.bossKind;
        if (k == null) return;
        List<ServerPlayer> crew = inside(run);
        int t = EndMobs.tier(run.wave);
        for (ServerPlayer p : crew) Announce.title(p, k.display, "§a§ldefeated!", 5, 60, 15);
        if (k.mini()) EndRewards.miniBoss(server, crew, Math.max(0, (run.wave - 5) / 10), t, run.omenBonus());
        else EndRewards.boss(server, crew, run.wave, t, k == EndMobs.Kind.DRAGON, run.omenBonus());
        removeCrystals(l, run);
        Skyserver.LOGGER.info("[End] {} beaten on wave {}", k, run.wave);
    }

    private static void removeCrystals(ServerLevel l, Run run) {
        for (UUID id : run.dragonCrystals) {
            Entity e = l.getEntity(id);
            if (e != null) e.discard();
        }
        for (UUID id : run.wardenCrystals) {
            Entity e = l.getEntity(id);
            if (e != null) e.discard();
        }
        run.dragonCrystals.clear();
        run.wardenCrystals.clear();
    }

    /** A mob died on the island: wave guard rewards, the enderman pearl cap, and no vanilla loot from guards. */
    private static void mobDied(Entity e, Entity killer) {
        Run run = runIn(e.level());
        ServerLevel l = (ServerLevel) e.level();
        List<ItemEntity> fresh = l.getEntitiesOfClass(ItemEntity.class, e.getBoundingBox().inflate(2.0), ie -> ie.tickCount <= 1);
        if (run == null) {
            fresh.forEach(Entity::discard);
            return;
        }
        if (run.endermen.contains(e.getUUID())) {
            ServerPlayer p = killer instanceof ServerPlayer sp && run.inside.contains(sp.getUUID()) ? sp : null;
            for (ItemEntity ie : fresh) {
                if (!ie.getItem().is(Items.ENDER_PEARL)) continue;
                if (p == null) {
                    ie.discard();
                    continue;
                }
                int[] c = run.counts(p.getUUID());
                int allowed = run.pearlCap() - c[2];
                if (allowed <= 0) {
                    ie.discard();
                    Announce.actionBar(p, "§7Pearl limit reached this round (" + run.pearlCap() + ").");
                    continue;
                }
                int n = Math.min(allowed, ie.getItem().getCount());
                ie.getItem().setCount(n);
                c[2] += n;
                Announce.actionBar(p, "§dEnder pearl! §7(" + c[2] + "/" + run.pearlCap() + " this round)");
            }
            return;
        }
        if (!run.mobs.contains(e.getUUID())) return;
        fresh.forEach(Entity::discard);
        EndMobs.Kind k = EndMobs.KIND.get(e.getUUID());
        if (k == null || k.mini() || k.boss()) return; // bosses are paid out by tick() once they're gone
        EndRewards.guard(inside(run), k, EndMobs.tier(run.wave), run.fragScale * run.omenBonus());
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

    /** Home with everything they carry (between waves, an admin, or the server stopping). */
    static void extract(ServerPlayer p, String msg) {
        Run run = runOf(p.getUUID());
        UUID id = p.getUUID();
        DOWNED.remove(id);
        PROTECTED.remove(id);
        if (run != null) {
            run.inside.remove(id);
            if (run.bar != null) run.bar.removePlayer(p);
        }
        EndRewards.bank(p);
        sendHome(p, run);
        p.resetFallDistance();
        p.clearFire();
        EndRewards.deliverStrongbox(server, id);
        Lobby.msg(p, msg + " §7Everything you're carrying is yours.");
        if (run != null && run.inside.isEmpty()) endRun(run, "everyone went home");
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
            e.setDeltaMovement((RNG.nextDouble() - 0.5) * 0.3, 0.2, (RNG.nextDouble() - 0.5) * 0.3);
            level.addFreshEntity(e);
        }
    }

    /** Where a dead player's things land: where they fell, or Wren's Pad if they fell into the void. */
    private static Vec3 dropSpot(ServerPlayer p) {
        if (p.getY() >= EndIsland.LEDGE_Y - 4) return p.position();
        double[] at = EndIsland.landing();
        return new Vec3(at[0], at[1], at[2]);
    }

    static void died(ServerPlayer p, String why) {
        Run run = runOf(p.getUUID());
        UUID id = p.getUUID();
        DOWNED.remove(id);
        PROTECTED.remove(id);
        ServerLevel level = (ServerLevel) p.level();
        boolean saved = EndRewards.useCharm(p);
        if (!saved) dropAll(p, level, dropSpot(p));
        p.setHealth(p.getMaxHealth());
        p.clearFire();
        p.removeAllEffects();
        p.getFoodData().setFoodLevel(20);
        p.resetFallDistance();
        p.setDeltaMovement(0, 0, 0);
        if (run != null) {
            run.inside.remove(id);
            if (run.bar != null) run.bar.removePlayer(p);
            for (ServerPlayer m : inside(run))
                Lobby.msg(m, "§c" + p.getName().getString() + " " + why + "! §7" + (saved ? "Their Recovery Charm saved their things." : "Their things are where they fell."));
        }
        if (saved) EndRewards.bank(p);
        EndRewards.deliverStrongbox(server, id);
        // Moved home a tick later, outside the damage that killed them (like the Nether).
        Lobby.runLater(() -> Safe.run("end send home", () -> {
            if (p.hasDisconnected()) return;
            sendHome(p, run);
            Lobby.msg(p, saved ? "§b§lYour Recovery Charm broke §7- you " + why + " but kept everything you carried."
                    : "§c§lYou " + why + " on the End Expedition §7and lost everything you were carrying. §8(Your Strongbox items are in your mail.)");
        }));
        if (run != null && run.inside.isEmpty()) endRun(run, "nobody is left");
    }

    private static void loggedOut(ServerPlayer p) {
        Run run = runOf(p.getUUID());
        if (run == null || !isEnd(p.level())) {
            Muster mu = musterOf(p.getUUID());
            if (mu != null && !mu.leader.equals(p.getUUID())) refund(p.getUUID(), mu.paid.remove(p.getUUID()));
            return;
        }
        boolean mid = fighting(run) || DOWNED.containsKey(p.getUUID());
        DOWNED.remove(p.getUUID());
        PROTECTED.remove(p.getUUID());
        if (mid && !EndRewards.useCharm(p)) {
            dropAll(p, (ServerLevel) p.level(), dropSpot(p));
            for (ServerPlayer m : inside(run)) if (m != p) Lobby.msg(m, "§c" + p.getName().getString() + " quit in the middle of a wave - their things are where they were.");
        } else EndRewards.bank(p);
        run.inside.remove(p.getUUID());
        if (run.bar != null) run.bar.removePlayer(p);
        sendHome(p, run);
        EndRewards.deliverStrongbox(server, p.getUUID());
        if (run.inside.isEmpty()) endRun(run, "nobody is left");
    }

    static long reviveCost() {
        return Math.round(PRICE * REVIVE_SHARE);
    }

    private static boolean canPay(Run run, UUID id) {
        return !run.revived.contains(id) && Economy.balance(server, id) >= reviveCost();
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
            if (!run.revived.contains(id)) Lobby.msg(p, "§7(A revive costs " + Economy.format(reviveCost()) + " - you didn't have enough.)");
            return false;
        }
        // Fell into the void: wake up on Wren's Pad instead.
        if (p.getY() < EndIsland.LEDGE_Y - 4) {
            double[] at = EndIsland.landing();
            p.teleportTo(at[0], at[1], at[2]);
            p.setDeltaMovement(0, 0, 0);
            p.resetFallDistance();
        }
        Downed d = new Downed(p.getX(), p.getY(), p.getZ(), team ? 0 : 1, ticks + (team ? TEAM_REVIVE_SECONDS : REVIVE_SECONDS) * 20L);
        DOWNED.put(id, d);
        p.setHealth(1f);
        p.clearFire();
        p.setDeltaMovement(0, 0, 0);
        p.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, (TEAM_REVIVE_SECONDS + REVIVE_SECONDS) * 20, 9, false, false));
        p.level().playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.WITHER_SPAWN, SoundSource.PLAYERS, 0.6f, 1.6f);
        if (team) {
            Announce.title(p, "§c§lYOU'RE DOWN", "§7A teammate can pick you up (" + TEAM_REVIVE_SECONDS + "s)", 5, 80, 10);
            MutableComponent line = Component.literal("§c§lYou're down! §7A teammate has §f" + TEAM_REVIVE_SECONDS + " seconds §7to crouch next to you for "
                    + (TEAM_REVIVE_HOLD_TICKS / 20) + " seconds (the team's one pick-up this trip). ");
            if (paid) line.append(Expedition.button("§a[Revive now for " + Economy.format(reviveCost()) + "]", "/endexp revive", "Pay and get up at full health")).append(Component.literal(" "));
            line.append(Expedition.button("§c[Give up]", "/endexp giveup", "Die: drop everything you carry and go home"));
            p.sendSystemMessage(line);
            for (ServerPlayer m : inside(run)) {
                if (m == p) continue;
                Announce.title(m, "§c§l" + p.getName().getString() + " IS DOWN", "§7Crouch next to them for " + (TEAM_REVIVE_HOLD_TICKS / 20) + " seconds!", 5, 60, 10);
            }
        } else {
            offerPaid(p);
            for (ServerPlayer m : inside(run)) if (m != p) Lobby.msg(m, "§c" + p.getName().getString() + " is down! §7They have " + REVIVE_SECONDS + " seconds to revive.");
        }
        return true;
    }

    private static void offerPaid(ServerPlayer p) {
        long cost = reviveCost();
        Announce.title(p, "§c§lYOU'RE DOWN", "§7Revive for §6" + Economy.format(cost) + "§7? Click in chat (" + REVIVE_SECONDS + "s)", 5, 80, 10);
        p.sendSystemMessage(Component.literal("§7Once per trip you can pay to revive: full health, right here, nothing lost. ")
                .append(Expedition.button("§a§l[Revive for " + Economy.format(cost) + "]", "/endexp revive", "Pay " + Economy.format(cost) + " and get back up"))
                .append(Component.literal(" "))
                .append(Expedition.button("§c§l[Give up]", "/endexp giveup", "Die: drop everything you carry and go home")));
    }

    private static void standUp(ServerPlayer p, double share) {
        UUID id = p.getUUID();
        DOWNED.remove(id);
        p.removeEffect(MobEffects.SLOWNESS);
        p.removeEffect(MobEffects.LEVITATION);
        p.clearFire();
        p.setHealth((float) Math.max(1.0, p.getMaxHealth() * share));
        p.getFoodData().setFoodLevel(20);
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
        if (!Economy.take(server, p.getUUID(), reviveCost())) {
            Lobby.msg(p, "§cYou don't have " + Economy.format(reviveCost()) + " for the revive.");
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
            if (p == null || run == null || !isEnd(p.level())) {
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
                        offerPaid(p);
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
        if (run == null || RUNS[run.slot] != run) return;
        run.over = true;
        RUNS[run.slot] = null;
        ServerLevel l = level(run);
        EndOmens.runOver(l, run);
        if (run.bar != null) run.bar.removeAllPlayers();
        for (UUID id : run.inside) {
            ServerPlayer p = server.getPlayerList().getPlayer(id);
            if (p != null && isEnd(p.level())) {
                EndRewards.bank(p);
                sendHome(p, run);
            }
            EndRewards.deliverStrongbox(server, id);
        }
        for (UUID id : run.crew) DOWNED.remove(id);
        if (l != null) {
            int r = EndIsland.R + EndIsland.LEDGE_WIDTH + 30;
            AABB box = new AABB(-r, EndIsland.Y0 - 90, -r, r, EndIsland.Y0 + 160, r);
            for (Entity e : l.getEntities((Entity) null, box, e -> !(e instanceof Player))) e.discard();
            for (long pos : run.placed) {
                BlockPos p = BlockPos.of(pos);
                if (!l.getBlockState(p).isAir()) l.setBlock(p, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
            }
        }
        EndMobs.clear(run);
        Skyserver.LOGGER.info("[End] trip on island {} over ({}), reached wave {}", run.slot + 1, why, run.wave);
    }

    private static void shutdown() {
        for (Muster m : new ArrayList<>(MUSTERS)) cancelMuster(m, "server stopping");
        for (Run run : RUNS) {
            if (run == null) continue;
            for (ServerPlayer p : inside(run)) extract(p, "§6[Captain Wren] §fThe server is going down - home we go!");
            if (RUNS[run.slot] == run) endRun(run, "server stopping");
        }
    }

    // =====================================================================
    // Helpers
    // =====================================================================

    /** Crew members on the island right now. */
    static List<ServerPlayer> inside(Run run) {
        List<ServerPlayer> out = new ArrayList<>();
        if (server == null) return out;
        for (UUID id : run.inside) {
            ServerPlayer p = server.getPlayerList().getPlayer(id);
            if (p != null && isEnd(p.level())) out.add(p);
        }
        return out;
    }

    /** Crew members who can fight (not down). */
    static List<ServerPlayer> fighters(Run run) {
        List<ServerPlayer> out = inside(run);
        out.removeIf(p -> DOWNED.containsKey(p.getUUID()));
        return out;
    }

    static ServerPlayer nearest(Run run, Vec3 at) {
        ServerPlayer best = null;
        double bd = Double.MAX_VALUE;
        for (ServerPlayer p : fighters(run)) {
            double d = p.distanceToSqr(at);
            if (d < bd) {
                bd = d;
                best = p;
            }
        }
        return best;
    }

    private static void tip(ServerPlayer p, String text) {
        Announce.actionBar(p, text);
    }
}
