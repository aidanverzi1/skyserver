package com.aidan.skyserver.expedition;

import com.aidan.skyserver.Safe;
import com.aidan.skyserver.Skyserver;
import com.aidan.skyserver.bots.BotPlayer;
import com.aidan.skyserver.island.Islands;
import com.aidan.skyserver.lobby.*;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
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
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
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
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/**
 * 5.19: the ANCIENT CITY EXPEDITION - a stealth trip. Captain Wren lowers the crew into a dark deepslate
 * city (AncientCityMap). Sneak to the houses for loot, take the RELIC from the portal frame in the middle
 * and bring it back to Wren's lift. Everything that makes noise is vanilla: sculk sensors hear footsteps,
 * opening chests, breaking and placing; sculk shriekers raise your warning level, and the 4th warning
 * brings the WARDEN. The Warden can never be killed - hide, sneak away, or run.
 *
 *  - Called from the island menu (/is) or /city call <difficulty>. Same prices as the Nether:
 *    Easy $5,000, Normal $10,000, Hard $20,000, Nightmare $40,000 per player.
 *  - Up to 3 (island and faction mates): everyone pays their own place, gets their own loot from every
 *    chest (each chest opens once per player) and their own escape reward.
 *  - Time limits (to test): Easy 15, Normal 18, Hard 22, Nightmare 25 minutes. When time runs out Wren
 *    pulls everyone up with what they carry, but there's no escape reward.
 *  - Taking the relic: on Normal and up, a Warden climbs out near the frame 8 seconds later (if none is
 *    close by). Nightmare: a Warden is awake and patrolling from the start (a new one if it digs away).
 *  - Escape: whoever carries the relic walks into Wren's lift - everyone still in the city escapes with
 *    the escape reward (money, echo shards, keys, shards, trims, books, XP, a Wren's Charts stamp).
 *  - Dying drops everything you carry where you fell (a Recovery Charm saves it). Downed first if a
 *    teammate can pick you up (once per team) or you can pay the revive (20%, once per player).
 *  - No player-vs-player hits, no buckets. The city can't be broken; blocks you place can be.
 *  - THE MOLE (opt-in, crews of 2+): the captain turns it on before sailing. One random crew member is
 *    secretly paid for every shriek the crew sets off. Anyone else can /city accuse <name> once: right,
 *    and the mole's pay goes to them instead; wrong, and that accusation is used up.
 */
public final class AncientCity {
    private AncientCity() {}

    public static final ResourceKey<Level> DIM = ResourceKey.create(Registries.DIMENSION, Identifier.fromNamespaceAndPath(Skyserver.MOD_ID, "ancient_city"));
    public static final String[] DIFF_NAMES = Tokens.DIFF_NAMES;
    public static final String[] DIFF_COLORS = {"§a", "§e", "§c", "§4"};
    public static final long[] PRICES = {5_000, 10_000, 20_000, 40_000};
    /** Time limits by difficulty (seconds). */
    static final int[] TIME_LIMITS = {15 * 60, 18 * 60, 22 * 60, 25 * 60};
    static final int CREW_MAX = 3, MUSTER_SECONDS = 30, MAX_RUNS = 4;
    /** Seconds after the relic is taken before a Warden climbs out (Normal and up). */
    static final int RELIC_WARDEN_SECONDS = 8;
    /** Revives (like the Nether). */
    static final double REVIVE_SHARE = 0.20, TEAM_REVIVE_HEALTH = 0.5, TEAM_REVIVE_RANGE = 2.5;
    static final int TEAM_REVIVE_SECONDS = 30, REVIVE_SECONDS = 30, TEAM_REVIVE_HOLD_TICKS = 100, REVIVE_PROTECT_TICKS = 60;
    /** Escape reward: money (share of the price), echo shards, trims (%), Swift Sneak III book (%), keys {tier}, shards. */
    static final double ESCAPE_MONEY = 0.25;
    static final int[] ESCAPE_ECHO = {2, 3, 4, 6}, WARD_CHANCE = {20, 30, 45, 60}, SILENCE_CHANCE = {4, 6, 9, 14}, SNEAK_BOOK_CHANCE = {0, 0, 25, 50};
    static final int[] ESCAPE_KEY_TIER = {1, 2, 3, 3}, ESCAPE_SHARDS = {5, 11, 21, 42};
    static final int[] ESCAPE_PASS_XP = {315, 525, 840, 1_260}, ESCAPE_XP = {158, 263, 420, 630};
    /** The mole's pay per shriek: this share of the trip's price. */
    static final double MOLE_SHARE = 0.05;
    static final String WREN_TAG = "skyserver_city_wren", RELIC_TAG = "city_relic", SILENT_TITLE = "title_silent";

    static final class Run {
        final int id, diff, islandId;
        final UUID leader;
        final List<UUID> crew = new ArrayList<>();
        final Set<UUID> inside = new LinkedHashSet<>();
        final Set<UUID> revived = new HashSet<>();
        final Set<Long> placed = new HashSet<>();
        final Map<Long, Set<UUID>> looted = new HashMap<>();
        final Map<Long, Boolean> shrieking = new HashMap<>();
        AncientCityMap.Layout layout;
        boolean building = true, over = false, teamReviveUsed = false, relicTaken = false, escaped = false;
        long startTick, relicWardenAt = -1;
        int timeLimit, warned = 0;
        UUID wren, patrol;
        /** The mole (null = no mole game), their pay so far, who has accused, and whether they were caught. */
        boolean moleOn = false, moleCaught = false;
        UUID mole, moleCatcher;
        long molePay = 0;
        final Set<UUID> accused = new HashSet<>();

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
        final LinkedHashSet<UUID> paid = new LinkedHashSet<>();
        boolean mole = false;
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

    private static Muster musterOf(UUID id) {
        for (Muster m : MUSTERS) if (m.paid.contains(id)) return m;
        return null;
    }

    // =====================================================================
    // Setup (started from Expedition.init, before Lobby)
    // =====================================================================

    public static void init() {
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
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(s -> Safe.run("city stop", AncientCity::shutdown));
        ServerTickEvents.END_SERVER_TICK.register(s -> {
            ticks++;
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
                return run == null || run.layout == null || !run.layout.inLift(p.getX(), p.getY(), p.getZ()); // the lift is safe
            }
            return true;
        });
        UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
            if (level.isClientSide() || !isCity(level) || !(player instanceof ServerPlayer sp)) return InteractionResult.PASS;
            Run run = runOf(sp.getUUID());
            if (run == null || run.layout == null || sp.isCreative()) return InteractionResult.PASS;
            BlockPos pos = hit.getBlockPos();
            if (pos.equals(run.layout.relic)) {
                if (hand == net.minecraft.world.InteractionHand.MAIN_HAND) Safe.run("city relic", () -> takeRelic(sp, run));
                return InteractionResult.SUCCESS;
            }
            if (run.layout.chests.contains(pos)) {
                if (hand == net.minecraft.world.InteractionHand.MAIN_HAND) Safe.run("city chest", () -> openChest(sp, run, pos));
                return InteractionResult.SUCCESS;
            }
            ItemStack held = player.getItemInHand(hand);
            if (held.getItem() instanceof BucketItem && !held.is(Items.BUCKET)) {
                tip(sp, "§cNo buckets in the Ancient City.");
                return InteractionResult.FAIL;
            }
            if (held.getItem() instanceof BlockItem) {
                BlockPos target = level.getBlockState(pos).canBeReplaced() ? pos : pos.relative(hit.getDirection());
                if (run.layout.inLift(target.getX() + 0.5, target.getY(), target.getZ() + 0.5)) {
                    tip(sp, "§7Keep Wren's lift clear, captain.");
                    return InteractionResult.FAIL;
                }
                boolean wasAir = level.getBlockState(target).canBeReplaced();
                Lobby.runLaterTicks(1, () -> Safe.run("city place", () -> {
                    if (!run.over && wasAir && !level.getBlockState(target).canBeReplaced()) run.placed.add(target.asLong());
                }));
            }
            return InteractionResult.PASS;
        });
        UseItemCallback.EVENT.register((player, level, hand) -> {
            if (level.isClientSide() || !isCity(level)) return InteractionResult.PASS;
            ItemStack held = player.getItemInHand(hand);
            if (held.getItem() instanceof BucketItem && !held.is(Items.BUCKET) && player instanceof ServerPlayer sp) {
                tip(sp, "§cNo buckets in the Ancient City.");
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
            if (run.placed.remove(pos.asLong())) return true;
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
                                .executes(c -> { call(c.getSource().getPlayerOrException(), IntegerArgumentType.getInteger(c, "difficulty")); return 1; })))
                .then(Commands.literal("join").executes(c -> { join(c.getSource().getPlayerOrException()); return 1; }))
                .then(Commands.literal("go").executes(c -> { goNow(c.getSource().getPlayerOrException()); return 1; }))
                .then(Commands.literal("mole").executes(c -> { toggleMole(c.getSource().getPlayerOrException()); return 1; }))
                .then(Commands.literal("accuse").then(Commands.argument("player", StringArgumentType.word())
                        .executes(c -> { accuse(c.getSource().getPlayerOrException(), StringArgumentType.getString(c, "player")); return 1; })))
                .then(Commands.literal("home").executes(c -> { goHome(c.getSource().getPlayerOrException()); return 1; }))
                .then(Commands.literal("revive").executes(c -> { revive(c.getSource().getPlayerOrException()); return 1; }))
                .then(Commands.literal("giveup").executes(c -> { giveUp(c.getSource().getPlayerOrException()); return 1; }))
                .then(Commands.literal("help").executes(c -> { help(c.getSource().getPlayerOrException()); return 1; }))
                .then(Commands.literal("admin").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
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
    // The island menu, calling Wren, the muster
    // =====================================================================

    /** The /is menu button. */
    public static ItemStack islandButton(ServerPlayer p) {
        Run mine = runOf(p.getUUID());
        String state = mine != null ? "§eYou're in the Ancient City" : MUSTERS.size() + RUNS.size() >= MAX_RUNS ? "§cWren's lift is busy - try again soon" : "§eClick to call Wren";
        return Stacks.named(new ItemStack(Items.SCULK_SHRIEKER), "§3§lAncient City Expedition",
                "§7Sneak through a dark deepslate city,", "§7loot its houses and steal the relic", "§7from the portal frame - then get out.",
                "§7Sculk hears footsteps. Shriekers call", "§7the §lWarden§7, who can't be killed.",
                "§7Price: " + DIFF_COLORS[0] + "$5k " + DIFF_COLORS[1] + "$10k " + DIFF_COLORS[2] + "$20k " + DIFF_COLORS[3] + "$40k §7each",
                "§7Up to " + CREW_MAX + " sail (island or faction mates).", "§c§lDying loses everything you carry!", state);
    }

    /** From the island menu or /city call: the difficulty choice in chat. */
    public static void offer(ServerPlayer p) {
        p.closeContainer();
        MutableComponent line = Component.literal("§6[Captain Wren] §fThe Ancient City, captain? Quiet as a mouse, mind. §7Pick how deep: ");
        for (int d = 0; d < 4; d++)
            line.append(Expedition.button(DIFF_COLORS[d] + "[" + DIFF_NAMES[d] + " " + Economy.format(PRICES[d]) + "]", "/city call " + d,
                    DIFF_NAMES[d] + ": " + AncientCityMap.SENSORS[d] + " sensors, " + AncientCityMap.SHRIEKERS[d] + " shriekers, " + AncientCityMap.HOUSES[d]
                            + " houses, " + (TIME_LIMITS[d] / 60) + " minutes" + (d == 3 ? ", a Warden awake from the start" : d >= 1 ? ", a Warden wakes when you take the relic" : "")))
                    .append(Component.literal(" "));
        line.append(Expedition.button("§7[How it works]", "/city help", "What happens in the Ancient City"));
        p.sendSystemMessage(line);
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

    private static boolean pay(ServerPlayer p, int d) {
        if (Economy.take(server, p.getUUID(), PRICES[d])) return true;
        Lobby.msg(p, "§cA " + DIFF_NAMES[d] + " place in the Ancient City costs " + Economy.format(PRICES[d]) + ".");
        return false;
    }

    private static void refund(UUID id, int d) {
        ServerPlayer p = server.getPlayerList().getPlayer(id);
        Economy.add(server, id, p == null ? "" : p.getName().getString(), PRICES[d]);
        if (p != null) Lobby.msg(p, "§7Your Ancient City money was given back.");
    }

    static void call(ServerPlayer p, int d) {
        if (!Islands.isIsland(p.level())) {
            Lobby.msg(p, "§cCall Captain Wren from the island world (/is).");
            return;
        }
        Islands.Island is = Islands.islandOf(p.getUUID());
        if (is == null) {
            Lobby.msg(p, "§cYou need a Sky Island first.");
            return;
        }
        if (busy(p) || !pay(p, d)) return;
        Muster mu = new Muster(p.getUUID(), is.id, d);
        mu.paid.add(p.getUUID());
        mu.deadline = ticks + MUSTER_SECONDS * 20L;
        MUSTERS.add(mu);
        Lobby.msg(p, "§6[Captain Wren] §fDown to the " + DIFF_COLORS[d] + DIFF_NAMES[d] + " §fcity we go! §7Your crew has §f" + MUSTER_SECONDS + " seconds §7to join (up to " + (CREW_MAX - 1) + ").");
        p.sendSystemMessage(Component.literal("§7").append(Expedition.button("§a[Sail now]", "/city go", "Leave without waiting"))
                .append(Component.literal(" ")).append(Expedition.button("§5[Mole game: off]", "/city mole", "Crews of 2+: one of you is secretly paid for every shriek")));
        for (ServerPlayer m : eligible(p)) {
            MutableComponent line = Component.literal("§3" + p.getName().getString() + " §7is going to the " + DIFF_COLORS[d] + DIFF_NAMES[d] + " §3§lAncient City§7! Join in " + MUSTER_SECONDS + "s: ");
            line.append(Expedition.button("§a[Join - " + Economy.format(PRICES[d]) + "]", "/city join", "Pay for your own place"));
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
            Lobby.msg(p, "§7No crew of your island or faction is getting ready for the Ancient City right now.");
            return;
        }
        if (mu.paid.size() >= CREW_MAX) {
            Lobby.msg(p, "§cThe crew is full (" + CREW_MAX + " at most).");
            return;
        }
        if (!pay(p, mu.diff)) return;
        mu.paid.add(p.getUUID());
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

    static void toggleMole(ServerPlayer p) {
        Muster mu = musterOf(p.getUUID());
        if (mu == null || !mu.leader.equals(p.getUUID())) {
            Lobby.msg(p, "§7Only the captain can turn the mole game on, before setting off.");
            return;
        }
        mu.mole = !mu.mole;
        for (UUID id : mu.paid) {
            ServerPlayer m = server.getPlayerList().getPlayer(id);
            if (m != null) Lobby.msg(m, mu.mole ? "§5§lMOLE GAME ON! §7One of the crew will be secretly paid for every shriek. §8(Needs 2+ players.)"
                    : "§7The mole game is off.");
        }
    }

    private static void cancelMuster(Muster mu, String why) {
        if (!MUSTERS.remove(mu)) return;
        for (UUID id : mu.paid) refund(id, mu.diff);
        Skyserver.LOGGER.info("[City] muster cancelled: {}", why);
    }

    /** Everyone who paid and is still on the island world goes; the rest get their money back. */
    private static void depart(Muster mu) {
        if (!MUSTERS.remove(mu)) return;
        ServerLevel l = level();
        if (l == null) {
            for (UUID id : mu.paid) refund(id, mu.diff);
            return;
        }
        Run run = new Run(runCounter++, mu.diff, mu.islandId, mu.leader);
        for (UUID id : mu.paid) {
            ServerPlayer m = server.getPlayerList().getPlayer(id);
            if (m != null && Islands.isIsland(m.level()) && run.crew.size() < CREW_MAX) run.crew.add(id);
            else refund(id, mu.diff);
        }
        if (run.crew.isEmpty()) return;
        run.moleOn = mu.mole && run.crew.size() >= 2;
        BlockPos center = new BlockPos(2_000 + (run.id % 16) * 400, AncientCityMap.FLOOR_Y, 2_000 + (run.id / 16) * 400);
        run.layout = AncientCityMap.plan(l, center, run.diff, RNG);
        run.timeLimit = TIME_LIMITS[run.diff];
        RUNS.add(run);
        Skyserver.LOGGER.info("[City] trip #{} ({}, crew of {}): {} blocks to build", run.id, DIFF_NAMES[run.diff], run.crew.size(), run.layout.builder.order.length);
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
                refund(id, run.diff);
                continue;
            }
            p.teleportTo(l, at[0], at[1], at[2], Set.of(), (float) at[3], 0f, true);
            p.resetFallDistance();
            run.inside.add(id);
            Announce.title(p, "§3§lTHE ANCIENT CITY", "§7Sneak. Take the relic. Get out.", 10, 70, 20);
        }
        if (run.inside.isEmpty()) {
            endRun(run, "nobody boarded");
            return;
        }
        for (ServerPlayer p : inside(run)) help(p);
        // The mole, picked in secret.
        if (run.moleOn && run.inside.size() >= 2) {
            List<UUID> in = new ArrayList<>(run.inside);
            run.mole = in.get(RNG.nextInt(in.size()));
            long per = molePer(run);
            for (ServerPlayer p : inside(run)) {
                if (p.getUUID().equals(run.mole)) {
                    Announce.title(p, "§5§lYOU ARE THE MOLE", "§7Secretly paid §6" + Economy.format(per) + " §7for every shriek", 10, 80, 20);
                    Lobby.msg(p, "§5§lYou're the MOLE. §7You get §6" + Economy.format(per) + " §7for every shriek this crew sets off (paid at the end). §cIf someone guesses it's you (/city accuse), your pay goes to them.");
                } else {
                    Lobby.msg(p, "§5§lThere's a MOLE in your crew! §7Someone is paid for every shriek. Think you know who? §e/city accuse <name> §7(one guess each).");
                }
            }
        } else run.moleOn = false;
        if (run.diff >= 3) spawnWarden(l, run, run.layout.center.offset(0, 0, -20), true);
        Skyserver.LOGGER.info("[City] trip #{} started with {} player(s)", run.id, run.inside.size());
    }

    static void help(ServerPlayer p) {
        Lobby.msg(p, "§3§lANCIENT CITY §7- take the §frelic §7(the sculk catalyst on the portal frame in the middle) and bring it back to §6Wren's lift§7.");
        Lobby.msg(p, "§7Sculk sensors hear footsteps, chests, breaking and placing. §fSneak §7to stay quiet. §fGrey carpet §7muffles footsteps (mind the gaps). Shriekers bring the §lWarden§7 - it §ccan't be killed§7: hide or run.");
        Lobby.msg(p, "§7Every house has a chest: each opens once for each of you. It's dark - bring torches or night vision. The lift is safe.");
        Lobby.msg(p, "§c§lDying loses everything you carry §7(a §bRecovery Charm §7saves it). Talk to Wren in the lift to go home early (no escape reward).");
    }

    static void status(ServerPlayer p) {
        Run run = runOf(p.getUUID());
        if (run == null) {
            Lobby.msg(p, "§3§lANCIENT CITY EXPEDITIONS §7- a stealth trip for up to " + CREW_MAX + ". Call Wren from §e/is §7(or §e/city call§7).");
            Lobby.msg(p, "§7Prices per player: " + DIFF_COLORS[0] + "Easy $5k§7, " + DIFF_COLORS[1] + "Normal $10k§7, " + DIFF_COLORS[2] + "Hard $20k§7, " + DIFF_COLORS[3] + "Nightmare $40k§7.");
            return;
        }
        long left = Math.max(0, run.timeLimit - (ticks - run.startTick) / 20);
        Lobby.msg(p, "§3" + DIFF_NAMES[run.diff] + " Ancient City §7- " + (run.relicTaken ? "§ethe relic is taken - get to the lift!" : "the relic is still on the frame") + " §7(" + (left / 60) + ":" + String.format("%02d", left % 60) + " left)");
    }

    static void talk(ServerPlayer p) {
        Run run = runOf(p.getUUID());
        if (run == null) return;
        if (hasRelic(p)) {
            escape(run, p);
            return;
        }
        p.sendSystemMessage(Component.literal("§6[Captain Wren] §f" + (run.relicTaken ? "Where's that relic got to? " : "The relic's on the frame in the middle, captain. ") + "§7")
                .append(Expedition.button("§c[Go home now - keep what you carry, no reward]", "/city home", "Leave the city early")));
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
                    if (m != null) Announce.actionBar(m, "§3Lowering the lift... §f" + Math.round(run.layout.builder.progress() * 100) + "%");
                }
                continue;
            }
            if (ticks % 10 == 0) {
                // Escape: the relic carrier is in the lift.
                for (ServerPlayer p : inside(run))
                    if (!DOWNED.containsKey(p.getUUID()) && hasRelic(p) && run.layout.inLift(p.getX(), p.getY(), p.getZ())) {
                        escape(run, p);
                        break;
                    }
                if (!run.over) shrieks(run);
            }
        }
        tickDowned();
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
        if (ticks % 100 == 0) for (ServerPlayer m : inside(run))
            Announce.actionBar(m, "§3Ancient City §8| §f" + (left / 60) + ":" + String.format("%02d", left % 60) + (run.relicTaken ? " §8| §eRelic taken - to the lift!" : ""));
        // The relic's Warden.
        if (run.relicWardenAt > 0 && ticks >= run.relicWardenAt) {
            run.relicWardenAt = -1;
            BlockPos at = run.layout.center.offset(RNG.nextBoolean() ? 10 : -10, 0, RNG.nextInt(9) - 4);
            if (l != null && l.getEntitiesOfClass(Mob.class, new AABB(run.layout.center).inflate(48), m -> m.getType() == EntityTypes.WARDEN).isEmpty())
                spawnWarden(l, run, at, false);
        }
        // Nightmare: the patrol Warden. If it dug away, another comes a minute later.
        if (run.diff >= 3 && l != null && ticks % (60 * 20) == 0) {
            Entity e = run.patrol == null ? null : l.getEntity(run.patrol);
            if (e == null || !e.isAlive()) spawnWarden(l, run, run.layout.center.offset(RNG.nextInt(41) - 20, 0, -10 - RNG.nextInt(20)), true);
        }
    }

    private static void spawnWarden(ServerLevel l, Run run, BlockPos at, boolean patrol) {
        Mob w = EntityTypes.WARDEN.create(l, EntitySpawnReason.TRIGGERED);
        if (w == null) return;
        // A free spot on the floor near there (not inside a pillar, house or the frame).
        BlockPos spot = freeSpot(l, new BlockPos(at.getX(), AncientCityMap.FLOOR_Y, at.getZ()));
        w.snapTo(spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5, RNG.nextFloat() * 360f, 0f);
        w.finalizeSpawn(l, l.getCurrentDifficultyAt(spot), EntitySpawnReason.TRIGGERED, null);
        w.setPersistenceRequired();
        l.addFreshEntity(w);
        if (patrol) run.patrol = w.getUUID();
        for (ServerPlayer m : inside(run)) {
            Lobby.msg(m, patrol ? "§4§lThe Warden prowls the city... §7Stay quiet." : "§4§lThe Warden is climbing out by the frame! §7Get to the lift - quietly.");
            m.level().playSound(null, m.getX(), m.getY(), m.getZ(), SoundEvents.WARDEN_HEARTBEAT, SoundSource.HOSTILE, 1.5f, 1f);
        }
    }

    /** The nearest spot (up to 10 blocks away) with 3 blocks of air above the floor. */
    private static BlockPos freeSpot(ServerLevel l, BlockPos at) {
        for (int r = 0; r <= 10; r++)
            for (int dx = -r; dx <= r; dx++)
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                    BlockPos p = at.offset(dx, 0, dz);
                    if (l.getBlockState(p).isAir() && l.getBlockState(p.above()).isAir() && l.getBlockState(p.above(2)).isAir()
                            && !l.getBlockState(p.below()).isAir()) return p;
                }
        return at;
    }

    /** Counts new shrieks (for the mole). */
    private static void shrieks(Run run) {
        ServerLevel l = level();
        if (l == null) return;
        for (BlockPos s : run.layout.shriekers) {
            if (!l.isLoaded(s)) continue;
            BlockState st = l.getBlockState(s);
            boolean now = st.hasProperty(BlockStateProperties.SHRIEKING) && st.getValue(BlockStateProperties.SHRIEKING);
            Boolean before = run.shrieking.put(s.asLong(), now);
            if (now && (before == null || !before) && run.moleOn && run.mole != null && !run.moleCaught) {
                run.molePay += molePer(run);
                ServerPlayer mole = server.getPlayerList().getPlayer(run.mole);
                if (mole != null) Announce.actionBar(mole, "§5Mole pay: §6" + Economy.format(run.molePay) + " §7(+" + Economy.format(molePer(run)) + ")");
            }
        }
    }

    private static long molePer(Run run) {
        return Math.round(PRICES[run.diff] * MOLE_SHARE);
    }

    // =====================================================================
    // Chests, the relic, escaping
    // =====================================================================

    private static void openChest(ServerPlayer p, Run run, BlockPos pos) {
        if (DOWNED.containsKey(p.getUUID())) return;
        ServerLevel l = (ServerLevel) p.level();
        // Opening a chest makes a sound the sculk can hear, just like a real chest.
        l.gameEvent(p, GameEvent.CONTAINER_OPEN, pos);
        l.playSound(null, pos, SoundEvents.CHEST_OPEN, SoundSource.BLOCKS, 0.6f, 0.9f);
        Set<UUID> got = run.looted.computeIfAbsent(pos.asLong(), k -> new HashSet<>());
        if (!got.add(p.getUUID())) {
            tip(p, "§7You've already emptied this chest.");
            return;
        }
        List<ItemStack> loot = chestLoot(l, run.diff);
        StringBuilder sb = new StringBuilder();
        for (ItemStack s : loot) {
            if (sb.length() > 0) sb.append("§7, ");
            sb.append("§f").append(s.getCount() > 1 ? s.getCount() + " " : "").append(s.getHoverName().getString());
            EndRewards.give(p, s);
        }
        Lobby.msg(p, "§3Chest: " + (sb.length() == 0 ? "§7nothing but dust" : sb.toString()));
    }

    private static boolean roll(int pct) {
        return RNG.nextInt(100) < pct;
    }

    /** What ONE player finds in ONE house chest. */
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
        if (roll(new int[]{8, 12, 16, 20}[d])) out.add(EndRewards.trim("ward"));
        if (roll(new int[]{1, 2, 3, 4}[d])) out.add(EndRewards.trim("silence"));
        return out;
    }

    private static ItemStack relicItem() {
        return Stacks.tag(Stacks.named(new ItemStack(Items.ECHO_SHARD), "§3§lThe Ancient Relic",
                "§7Bring it to Captain Wren's lift!", "§8It hums in your hands..."), RELIC_TAG);
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
            Announce.title(m, "§3§lTHE RELIC IS TAKEN", "§7" + p.getName().getString() + " has it - get to Wren's lift!", 5, 60, 10);
            m.level().playSound(null, m.getX(), m.getY(), m.getZ(), SoundEvents.WARDEN_NEARBY_CLOSER, SoundSource.HOSTILE, 1f, 1f);
        }
        if (run.diff >= 1) run.relicWardenAt = ticks + RELIC_WARDEN_SECONDS * 20L;
    }

    /** The relic reached the lift: everyone still in the city escapes with the reward. */
    private static void escape(Run run, ServerPlayer carrier) {
        if (run.over || run.escaped) return;
        run.escaped = true;
        int d = run.diff;
        long money = Math.round(PRICES[d] * ESCAPE_MONEY);
        for (ServerPlayer m : inside(run)) {
            if (DOWNED.containsKey(m.getUUID())) continue;
            removeRelics(m);
            StringBuilder got = new StringBuilder("§3§lESCAPED! §7You got §6" + Economy.format(money));
            Economy.add(server, m.getUUID(), m.getName().getString(), money);
            EndRewards.give(m, new ItemStack(Items.ECHO_SHARD, ESCAPE_ECHO[d]));
            got.append("§7, §3").append(ESCAPE_ECHO[d]).append(" echo shards");
            if (d >= 1) {
                EndRewards.give(m, new ItemStack(Items.RECOVERY_COMPASS));
                got.append("§7, a recovery compass");
            }
            if (roll(WARD_CHANCE[d])) {
                EndRewards.give(m, EndRewards.trim("ward"));
                got.append("§7, §bthe Ward trim");
            }
            if (roll(SILENCE_CHANCE[d])) {
                EndRewards.give(m, EndRewards.trim("silence"));
                got.append("§7, §d§lthe Silence trim!");
            }
            if (roll(SNEAK_BOOK_CHANCE[d])) {
                EndRewards.give(m, EndRewards.book(server, Enchantments.SWIFT_SNEAK, 3));
                got.append("§7, a Swift Sneak III book");
            }
            int k = ESCAPE_KEY_TIER[d];
            Keys.reward(server, m.getUUID(), k, 1, "(Ancient City)");
            got.append("§7, ").append(Keys.COLORS[k]).append(Keys.NAMES[k]).append(" Key");
            Tokens.add(m.getUUID(), Tokens.SHARDS, ESCAPE_SHARDS[d]);
            got.append("§7, §d").append(ESCAPE_SHARDS[d]).append(" shards");
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
        }
        server.getPlayerList().broadcastSystemMessage(Component.literal("§3§l✦ §e" + names(run) + " §7escaped a " + DIFF_COLORS[d] + DIFF_NAMES[d]
                + " §7Ancient City with the relic!"), false);
        for (ServerPlayer m : inside(run)) if (!DOWNED.containsKey(m.getUUID())) extract(m, "§6[Captain Wren] §fUp we go - and not a squeak!");
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
            escape(run, p);
            return;
        }
        extract(p, "§6[Captain Wren] §fHome early? Fair enough, captain.");
    }

    // =====================================================================
    // The mole
    // =====================================================================

    static void accuse(ServerPlayer p, String name) {
        Run run = runOf(p.getUUID());
        if (run == null || !run.moleOn || run.mole == null) {
            Lobby.msg(p, "§7There's no mole game on your trip.");
            return;
        }
        if (run.moleCaught) {
            Lobby.msg(p, "§7The mole has already been caught.");
            return;
        }
        if (p.getUUID().equals(run.mole)) {
            Lobby.msg(p, "§7Nice try. §8(You're the mole - you can't accuse.)");
            return;
        }
        if (!run.accused.add(p.getUUID())) {
            Lobby.msg(p, "§7You've used your one guess this trip.");
            return;
        }
        ServerPlayer target = server.getPlayerList().getPlayerByName(name);
        if (target == null || !run.crew.contains(target.getUUID())) {
            run.accused.remove(p.getUUID());
            Lobby.msg(p, "§c" + name + " isn't on your crew.");
            return;
        }
        if (target.getUUID().equals(run.mole)) {
            run.moleCaught = true;
            run.moleCatcher = p.getUUID();
            for (UUID id : run.crew) {
                ServerPlayer m = server.getPlayerList().getPlayer(id);
                if (m != null) Lobby.msg(m, "§5§lMOLE CAUGHT! §e" + p.getName().getString() + " §7named §e" + target.getName().getString()
                        + " §7- the mole's pay (§6" + Economy.format(run.molePay) + " §7so far, and every shriek from now) goes to " + p.getName().getString() + ".");
            }
        } else {
            for (UUID id : run.crew) {
                ServerPlayer m = server.getPlayerList().getPlayer(id);
                if (m != null) Lobby.msg(m, "§5" + p.getName().getString() + " §7accused §e" + target.getName().getString() + " §7- §cwrong! §7The mole is still out there.");
            }
        }
    }

    /** At the end of a trip: pay the mole (or whoever caught them) and tell the crew who it was. */
    private static void settleMole(Run run) {
        if (!run.moleOn || run.mole == null) return;
        UUID payee = run.moleCaught ? run.moleCatcher : run.mole;
        ServerPlayer pp = server.getPlayerList().getPlayer(payee);
        String moleName = nameOf(run.mole);
        if (run.molePay > 0 && payee != null) Economy.add(server, payee, pp == null ? nameOf(payee) : pp.getName().getString(), run.molePay);
        for (UUID id : run.crew) {
            ServerPlayer m = server.getPlayerList().getPlayer(id);
            if (m == null) continue;
            Lobby.msg(m, "§5§lTHE MOLE WAS " + moleName.toUpperCase(Locale.ROOT) + "! §7" + (run.moleCaught ? "Caught by §e" + nameOf(payee) + "§7, who gets " : "Never caught - they pocket ")
                    + "§6" + Economy.format(run.molePay) + "§7.");
        }
    }

    private static String nameOf(UUID id) {
        if (id == null) return "nobody";
        ServerPlayer p = server.getPlayerList().getPlayer(id);
        return p != null ? p.getName().getString() : "someone";
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
        if (run != null) run.inside.remove(id);
        p.removeEffect(MobEffects.DARKNESS);
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
        return net.minecraft.core.registries.BuiltInRegistries.BLOCK.getValue(Identifier.withDefaultNamespace("sculk_catalyst")).defaultBlockState();
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
        if (saved) removeRelicIfCarried(p, run); // the charm keeps your things, but not the relic
        else dropAll(p, level, p.position());   // the relic drops with everything else - a teammate can grab it
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
            if (mu != null && !mu.leader.equals(p.getUUID()) && mu.paid.remove(p.getUUID())) refund(p.getUUID(), mu.diff);
            return;
        }
        DOWNED.remove(p.getUUID());
        PROTECTED.remove(p.getUUID());
        boolean safe = run.layout != null && run.layout.inLift(p.getX(), p.getY(), p.getZ());
        if (!safe && !EndRewards.useCharm(p)) {
            dropAll(p, (ServerLevel) p.level(), p.position());
            for (ServerPlayer m : inside(run)) if (m != p) Lobby.msg(m, "§c" + p.getName().getString() + " left the city - their things are where they were.");
        } else removeRelicIfCarried(p, run);
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
        settleMole(run);
        for (UUID id : new ArrayList<>(run.inside)) {
            ServerPlayer p = server.getPlayerList().getPlayer(id);
            if (p != null && isCity(p.level())) {
                removeRelicIfCarried(p, run);
                sendHome(p, run);
            }
        }
        for (UUID id : run.crew) DOWNED.remove(id);
        if (run.layout != null && run.layout.builder != null) run.layout.builder.release();
        if (l != null && run.layout != null) {
            int[] b = run.layout.box();
            AABB box = new AABB(b[0], AncientCityMap.FLOOR_Y - 10, b[1], b[2], AncientCityMap.FLOOR_Y + AncientCityMap.HEIGHT + 10, b[3]);
            for (Entity e : l.getEntities((Entity) null, box, e -> !(e instanceof Player))) e.discard();
        }
        Skyserver.LOGGER.info("[City] trip #{} over ({})", run.id, why);
    }

    private static void shutdown() {
        for (Muster m : new ArrayList<>(MUSTERS)) cancelMuster(m, "server stopping");
        for (Run run : new ArrayList<>(RUNS)) {
            for (ServerPlayer p : inside(run)) extract(p, "§6[Captain Wren] §fThe server is going down - up we go!");
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
