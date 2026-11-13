package com.aidan.skyserver.bedwars;

import com.aidan.skyserver.DamageCredit;
import com.aidan.skyserver.Skyserver;
import com.aidan.skyserver.bots.BedwarsBrain;
import com.aidan.skyserver.bots.BotManager;
import com.aidan.skyserver.bots.BotPlayer;
import com.aidan.skyserver.bots.Difficulty;
import com.aidan.skyserver.lobby.*;
import com.aidan.skyserver.skywars.PlayerSnapshot;
import com.google.gson.JsonObject;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
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
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Unit;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.DyedItemColor;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.TeamColor;

import java.util.*;

/**
 * Bedwars: four teams of three, each with a bed. While your bed stands you respawn; once it's
 * broken, your next death is final. Iron and gold come from your home generator, diamonds from
 * the islands between bases (team upgrades and traps), emeralds from the middle (the best gear).
 * Generators speed up on a timer, every bed breaks at 30 minutes, and at 35 the sudden-death
 * border starts closing in. Bots fill empty slots (see BedwarsBrain).
 *
 * Commands: /bw join, /bw leave, /bw stats, /bw vote <map>; ops: /bw start, /bw fill [count],
 * /bw autofill on|off, /bw stop, /bw skip, /bw map <id>, /bw rebuild.
 */
public final class BedwarsGame {
    enum State { WAITING, COUNTDOWN, PLAYING, ENDING }

    private static MinecraftServer server;
    private static State state = State.WAITING;
    private static long ticks = 0, matchTicks = 0;
    private static int timer = 0;
    private static boolean mapReady = false, rewards = false, forcePay = false;
    private static boolean autofill = true;
    private static long joinCounter = 0, waitStart = -1;
    private static int matchNumber = 0;
    private static final Random RNG = new Random();

    static final Map<UUID, BwPlayer> players = new LinkedHashMap<>();
    static final Map<BwTeam, BwTeamState> teams = new EnumMap<>(BwTeam.class);
    /** Blocks people placed this match (only these, and beds, can be broken). */
    static final Set<Long> placed = new HashSet<>();
    private static final List<Runnable> nextTick = new ArrayList<>();
    private static final long[] diamondAt = new long[8], emeraldAt = new long[8];
    private static int diamondTier = 0, emeraldTier = 0;
    private static boolean suddenDeath = false;
    private static long suddenStart = 0;
    private static final MapVote VOTE = new MapVote("bw", BwMapDesigns.IDS, BwMapDesigns.NAMES);

    static {
        for (BwTeam t : BwTeam.values()) teams.put(t, new BwTeamState(t));
    }

    private BedwarsGame() {}

    // =====================================================================
    // Small public API (lobby, sidebar, chat, bots)
    // =====================================================================

    public static boolean isInGame(UUID id) {
        return players.containsKey(id);
    }

    public static String lobbyStatus() {
        if (state == State.PLAYING || state == State.ENDING) return "§cMatch in progress §7(" + BwRules.mode.display + ", " + players.size() + " playing)";
        if (players.isEmpty()) return "§a0 waiting §7(pick 3v3v3v3 or 2v2v2v2)";
        return "§a" + players.size() + "/" + (BwRules.maxPlayers()) + " waiting §7(" + BwRules.mode.display + ")";
    }

    /** The mode of the current or next match. */
    public static BwRules.Mode mode() {
        return BwRules.mode;
    }

    public static BwTeam teamOf(UUID id) {
        BwPlayer bp = players.get(id);
        return bp == null ? null : bp.team;
    }

    static boolean playing() {
        return state == State.PLAYING;
    }

    static long now() {
        return ticks;
    }

    static long matchTicks() {
        return matchTicks;
    }

    static int matchNumber() {
        return matchNumber;
    }

    static MinecraftServer server() {
        return server;
    }

    static ServerLevel level() {
        return BwMap.level(server);
    }

    static boolean alive(UUID id) {
        BwPlayer bp = players.get(id);
        return bp != null && bp.alive() && state == State.PLAYING;
    }

    static BwPlayer bp(UUID id) {
        return players.get(id);
    }

    static ServerPlayer online(UUID id) {
        return server == null || id == null ? null : server.getPlayerList().getPlayer(id);
    }

    /** Living players in the match (online, not respawning). */
    static List<ServerPlayer> alivePlayers() {
        List<ServerPlayer> list = new ArrayList<>();
        if (state != State.PLAYING) return list;
        for (Map.Entry<UUID, BwPlayer> e : players.entrySet()) {
            if (!e.getValue().alive()) continue;
            ServerPlayer p = online(e.getKey());
            if (p != null && p.isAlive()) list.add(p);
        }
        return list;
    }

    // =====================================================================
    // Setup
    // =====================================================================

    public static void init() {
        ServerLifecycleEvents.SERVER_STARTED.register(s -> {
            server = s;
            JsonObject cfg = Stacks.readFile("bedwars.json");
            autofill = !cfg.has("autofill") || cfg.get("autofill").getAsBoolean();
            try {
                BwBotLog.stream = cfg.has("botlog") ? BwBotLog.Stream.valueOf(cfg.get("botlog").getAsString()) : BwBotLog.Stream.OFF;
            } catch (IllegalArgumentException e) {
                BwBotLog.stream = BwBotLog.Stream.OFF;
            }
            BwBotLog.console = Skyserver.LOGGER::info;
            BwBotLog.clock = () -> matchTicks;
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(s -> {
            if (BwBotLog.isOpen()) BwBotLog.end("server stopping at " + clock((int) matchTicks));
            restoreEveryone();
        });
        ServerTickEvents.END_SERVER_TICK.register(s -> com.aidan.skyserver.Safe.run("bedwars tick", BedwarsGame::tick));
        ServerPlayConnectionEvents.DISCONNECT.register((h, s) -> onDisconnect(h.getPlayer()));
        ServerPlayConnectionEvents.JOIN.register((h, sender, s) -> onReconnect(h.getPlayer()));
        BwItems.init();

        // Nobody really dies: we respawn them ourselves.
        ServerLivingEntityEvents.ALLOW_DEATH.register((entity, source, amount) -> {
            if (!(entity instanceof ServerPlayer p)) return true;
            BwPlayer bp = players.get(p.getUUID());
            if (bp == null) return true;
            p.setHealth(p.getMaxHealth());
            if (state == State.PLAYING && bp.alive()) {
                UUID killer = source.getEntity() instanceof ServerPlayer k && k != p ? k.getUUID() : recentAttacker(bp);
                handleDeath(p, bp, killer, false);
            } else {
                toSpawn(p, bp);
            }
            return false;
        });

        ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> {
            if (!(entity instanceof ServerPlayer victim)) return true;
            BwPlayer v = players.get(victim.getUUID());
            if (v == null) return true;
            if (state != State.PLAYING || !v.alive() || ticks < v.invulnUntil) return false;
            if (ticks < v.noFallUntil && source.is(net.minecraft.tags.DamageTypeTags.IS_FALL)) return false;
            if (source.getEntity() != null && source.getEntity().entityTags().contains(BwItems.MITE_TAG)) {
                BwTeam mt = BwItems.miteTeam(source.getEntity());
                if (mt == null || mt == v.team) return false; // a Sky Mite never bites its own team
                v.lastAttacker = BwItems.miteOwner(source.getEntity());
                v.lastAttackTick = ticks;
                return true;
            }
            if (source.getEntity() instanceof ServerPlayer attacker && attacker != victim) {
                BwPlayer a = players.get(attacker.getUUID());
                if (a == null || !a.alive() || a.team == v.team) return false;
                v.lastAttacker = attacker.getUUID();
                v.lastAttackTick = ticks;
                v.active = a.active = true;
            }
            return true;
        });

        PlayerBlockBreakEvents.BEFORE.register((world, player, pos, blockState, be) -> {
            if (world.dimension() != BwMap.DIM) return true;
            BwPlayer bp = players.get(player.getUUID());
            if (bp == null) return com.aidan.skyserver.lobby.Lobby.isStaff(player); // only staff edit the map outside matches
            if (state != State.PLAYING || !bp.alive()) return false;
            ServerPlayer sp = (ServerPlayer) player;
            bp.active = true;
            if (blockState.getBlock() instanceof BedBlock) {
                if (BwItems.breakDecoy(sp, bp, pos)) return false;
                BwTeam owner = bedOwner(pos);
                if (owner == null) return false;
                if (owner == bp.team) {
                    msg(sp, "§cYou can't break your own bed!");
                    return false;
                }
                BwTeamState ts = teams.get(owner);
                if (ticks < ts.blanketUntil) {
                    msg(sp, "§b" + owner.display + "'s bed is wrapped in a Blanket! §7(" + ((ts.blanketUntil - ticks + 19) / 20) + "s)");
                    return false;
                }
                bedBroken(owner, sp, bp);
                return false;
            }
            if (!placed.contains(pos.asLong())) {
                return false; // the map itself can't be broken
            }
            removePlaced((ServerLevel) world, pos);
            BwItems.bedBell(pos, bp.team, sp.getName().getString());
            return false;
        });

        UseBlockCallback.EVENT.register((player, world, hand, hit) -> {
            if (world.isClientSide() || world.dimension() != BwMap.DIM || !(player instanceof ServerPlayer sp)) return InteractionResult.PASS;
            BwPlayer bp = players.get(sp.getUUID());
            if (bp == null) return Lobby.isStaff(player) ? InteractionResult.PASS : InteractionResult.FAIL;
            BlockPos clicked = hit.getBlockPos();
            BlockState clickedState = world.getBlockState(clicked);
            ItemStack held = sp.getItemInHand(hand);
            if (state != State.PLAYING || !bp.alive()) return InteractionResult.FAIL;
            // Using a block (not crouching, or nothing to place).
            if (!sp.isSecondaryUseActive() || held.isEmpty()) {
                if (clickedState.getBlock() instanceof BedBlock) return InteractionResult.FAIL; // no sleeping
                if (clickedState.is(Blocks.ENDER_CHEST)) {
                    if (hand == net.minecraft.world.InteractionHand.MAIN_HAND) openEnder(sp, bp);
                    return InteractionResult.FAIL;
                }
                if (clickedState.is(Blocks.CHEST)) {
                    BwTeam owner = chestOwner(clicked);
                    if (owner != null && owner != bp.team && !teams.get(owner).out) {
                        msg(sp, "§cThat's " + owner.colored() + "§c's team chest.");
                        return InteractionResult.FAIL;
                    }
                    return InteractionResult.PASS;
                }
                if (clickedState.hasBlockEntity() || clickedState.is(Blocks.ANVIL) || clickedState.is(Blocks.SMITHING_TABLE)
                        || clickedState.is(Blocks.CRAFTING_TABLE)) return InteractionResult.FAIL;
            }
            if (held.isEmpty()) return InteractionResult.PASS;
            BlockPos target = clickedState.canBeReplaced() ? clicked : clicked.relative(hit.getDirection());
            InteractionResult special = BwItems.useOnBlock(sp, bp, held, clicked, target, hit.getDirection());
            if (special != null) {
                resync(sp, world, target);
                return special;
            }
            if (!(held.getItem() instanceof BlockItem)) return InteractionResult.PASS; // bows, food, buckets...
            if (!placeable(held) || !canPlace(target)) {
                resync(sp, world, target);
                if (!placeable(held)) return InteractionResult.FAIL;
                if (BwMap.isProtected(target)) msg(sp, "§cYou can't build here.");
                return InteractionResult.FAIL;
            }
            placed.add(target.asLong());
            bp.active = true;
            return InteractionResult.PASS;
        });

        UseItemCallback.EVENT.register((player, world, hand) -> {
            if (world.isClientSide() || !(player instanceof ServerPlayer sp)) return InteractionResult.PASS;
            BwPlayer bp = players.get(sp.getUUID());
            if (bp == null) return InteractionResult.PASS;
            if (state != State.PLAYING || !bp.alive()) return InteractionResult.FAIL;
            InteractionResult r = BwItems.useItem(sp, bp, sp.getItemInHand(hand));
            return r == null ? InteractionResult.PASS : r;
        });

        UseEntityCallback.EVENT.register((player, world, hand, entity, hit) -> {
            if (world.isClientSide() || world.dimension() != BwMap.DIM) return InteractionResult.PASS;
            if (!(player instanceof ServerPlayer sp) || hand != net.minecraft.world.InteractionHand.MAIN_HAND) return InteractionResult.PASS;
            boolean shop = entity.entityTags().contains(BwMap.SHOP_TAG), upg = entity.entityTags().contains(BwMap.UPGRADES_TAG);
            if (!shop && !upg) return InteractionResult.PASS;
            BwPlayer bp = players.get(sp.getUUID());
            if (bp == null || state != State.PLAYING || !bp.alive()) return InteractionResult.FAIL;
            runNextTick(() -> {
                if (shop) BwShop.open(sp);
                else BwUpgrades.open(sp);
            });
            return InteractionResult.FAIL;
        });

        AttackEntityCallback.EVENT.register((player, world, hand, entity, hit) -> {
            if (world.dimension() != BwMap.DIM) return InteractionResult.PASS;
            if (entity.entityTags().contains("skyserver_npc") || entity.entityTags().contains(BwMap.HOLO_TAG)) return InteractionResult.FAIL;
            if (!players.containsKey(player.getUUID()) && !Lobby.isStaff(player)) return InteractionResult.FAIL;
            return InteractionResult.PASS;
        });

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> dispatcher.register(command()));
        Skyserver.LOGGER.info("Bedwars ready. Players join with /bw join");
    }

    private static void resync(ServerPlayer sp, net.minecraft.world.level.Level world, BlockPos target) {
        sp.connection.send(new ClientboundBlockUpdatePacket(world, target));
        sp.containerMenu.sendAllDataToRemote();
    }

    /** Blocks people may place (from the shop). */
    static boolean placeable(ItemStack s) {
        Item i = s.getItem();
        if (s.is(Items.END_STONE) || s.is(Items.OAK_PLANKS) || s.is(Items.OBSIDIAN) || s.is(Items.LADDER)) return true;
        String id = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(i).getPath();
        return id.endsWith("_wool") || id.endsWith("_terracotta") && !id.contains("glazed") || id.endsWith("_stained_glass");
    }

    /** Can a block be put here right now? */
    static boolean canPlace(BlockPos pos) {
        if (state != State.PLAYING || !BwMap.inBuildArea(pos) || BwMap.isProtected(pos)) return false;
        if (suddenDeath) {
            int half = borderHalf();
            if (Math.abs(pos.getX() - BwMap.OX) > half || Math.abs(pos.getZ() - BwMap.OZ) > half) return false;
        }
        ServerLevel level = level();
        if (level == null) return false;
        BlockState s = level.getBlockState(pos);
        return s.isAir() || s.canBeReplaced();
    }

    static boolean isPlaced(BlockPos pos) {
        return placed.contains(pos.asLong());
    }

    static void markPlaced(BlockPos pos) {
        placed.add(pos.asLong());
    }

    static void removePlaced(ServerLevel level, BlockPos pos) {
        placed.remove(pos.asLong());
        BwItems.forget(pos);
        level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
    }

    static BwTeam bedOwner(BlockPos pos) {
        BwMapDesigns.MapData m = BwMap.data();
        for (BwTeam t : BwTeam.values()) {
            int i = t.ordinal();
            if (pos.equals(BwMap.pos(m.bedFoot[i])) || pos.equals(BwMap.pos(m.bedHead[i]))) return t;
        }
        return null;
    }

    private static BwTeam chestOwner(BlockPos pos) {
        BwMapDesigns.MapData m = BwMap.data();
        for (BwTeam t : BwTeam.values()) if (pos.equals(BwMap.pos(m.teamChest[t.ordinal()]))) return t;
        return null;
    }

    private static void openEnder(ServerPlayer p, BwPlayer bp) {
        runNextTick(() -> p.openMenu(new SimpleMenuProvider((id, inv, pl) -> ChestMenu.threeRows(id, inv, bp.ender), Component.literal("Ender Chest"))));
    }

    // =====================================================================
    // Commands
    // =====================================================================

    private static LiteralArgumentBuilder<CommandSourceStack> command() {
        LiteralArgumentBuilder<CommandSourceStack> bw = Commands.literal("bw").executes(c -> {
            ServerPlayer p = c.getSource().getPlayerOrException();
            msg(p, "§c§lBEDWARS §7- " + lobbyStatus());
            msg(p, "§e/bw §7or §e/bw join [trios|duos] §7- join the next match   §e/leave §7- leave");
            msg(p, "§e/stats §7- your Bedwars stats   §e/bw vote <map> §7- vote for a map");
            return 1;
        });
        bw.then(Commands.literal("join").executes(c -> {
            join(c.getSource().getPlayerOrException(), null);
            return 1;
        }).then(Commands.argument("mode", StringArgumentType.word())
                .suggests((c, b) -> net.minecraft.commands.SharedSuggestionProvider.suggest(new String[]{"trios", "duos"}, b)).executes(c -> {
                    ServerPlayer p = c.getSource().getPlayerOrException();
                    BwRules.Mode m = BwRules.Mode.parse(StringArgumentType.getString(c, "mode"));
                    if (m == null) {
                        msg(p, "§cModes: §etrios §7(3v3v3v3) or §eduos §7(2v2v2v2).");
                        return 0;
                    }
                    if (Lobby.level() != null && !Lobby.isLobby(p.level())) {
                        msg(p, "§cJoin Bedwars from the lobby (§e/lobby§c).");
                        return 0;
                    }
                    join(p, m);
                    return 1;
                })));
        bw.then(Commands.literal("mode").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS)).executes(c -> {
            c.getSource().sendSuccess(() -> Component.literal("Bedwars mode: " + BwRules.mode.display + " (" + BwRules.mode.text + "). Use /bw mode trios|duos while nobody is in it."), false);
            return 1;
        }).then(Commands.argument("mode", StringArgumentType.word())
                .suggests((c, b) -> net.minecraft.commands.SharedSuggestionProvider.suggest(new String[]{"trios", "duos"}, b)).executes(c -> {
                    BwRules.Mode m = BwRules.Mode.parse(StringArgumentType.getString(c, "mode"));
                    if (m == null) {
                        c.getSource().sendFailure(Component.literal("Modes: trios (3v3v3v3) or duos (2v2v2v2)."));
                        return 0;
                    }
                    if (state != State.WAITING || !players.isEmpty()) {
                        c.getSource().sendFailure(Component.literal("Everyone must leave first (or /bw stop)."));
                        return 0;
                    }
                    BwRules.mode = m;
                    MIX.clear();
                    c.getSource().sendSuccess(() -> Component.literal("Bedwars mode set to " + m.display + " (" + m.text + ")."), true);
                    return 1;
                })));
        bw.then(Commands.literal("leave").executes(c -> {
            leave(c.getSource().getPlayerOrException(), true, false);
            return 1;
        }));
        bw.then(Commands.literal("stats").executes(c -> {
            ServerPlayer p = c.getSource().getPlayerOrException();
            showStats(c.getSource(), p.getUUID(), p.getName().getString());
            return 1;
        }).then(Commands.argument("player", EntityArgument.player()).executes(c -> {
            ServerPlayer t = EntityArgument.getPlayer(c, "player");
            showStats(c.getSource(), t.getUUID(), t.getName().getString());
            return 1;
        })));
        bw.then(Commands.literal("vote").then(Commands.argument("map", StringArgumentType.word())
                .suggests((c, b) -> net.minecraft.commands.SharedSuggestionProvider.suggest(BwMapDesigns.IDS, b)).executes(c -> {
                    ServerPlayer p = c.getSource().getPlayerOrException();
                    if (!players.containsKey(p.getUUID()) || (state != State.WAITING && state != State.COUNTDOWN)) {
                        msg(p, "§cYou can vote for the map while waiting for a Bedwars match.");
                        return 0;
                    }
                    String r = VOTE.vote(p, StringArgumentType.getString(c, "map"));
                    if (r != null) broadcast(r);
                    return 1;
                })));
        // ---- Ops ----
        bw.then(Commands.literal("start").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS)).executes(c -> forceStart(c.getSource(), false))
                .then(Commands.literal("pay").executes(c -> forceStart(c.getSource(), true))));
        bw.then(Commands.literal("fill").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS)).executes(c -> fillCommand(c.getSource(), -1))
                .then(Commands.argument("count", IntegerArgumentType.integer(1, 12)).executes(c -> fillCommand(c.getSource(), IntegerArgumentType.getInteger(c, "count")))));
        bw.then(Commands.literal("autofill").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS)).executes(c -> {
            c.getSource().sendSuccess(() -> Component.literal("Bedwars bot autofill is " + (autofill ? "ON" : "OFF")
                    + " (bots fill empty slots after " + BotManager.FILL_AFTER_SECONDS + "s of waiting)."), false);
            return 1;
        }).then(Commands.literal("on").executes(c -> setAutofill(c.getSource(), true)))
                .then(Commands.literal("off").executes(c -> setAutofill(c.getSource(), false))));
        bw.then(Commands.literal("botlog").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS)).executes(c -> {
            c.getSource().sendSuccess(() -> Component.literal("Bedwars bot log streaming: " + BwBotLog.stream.name().toLowerCase()
                    + ". Every match is also saved to config/skyserver-bot-logs/bw-*.txt. Use /bw botlog on|verbose|off."), false);
            return 1;
        }).then(Commands.literal("on").executes(c -> setBotLog(c.getSource(), BwBotLog.Stream.ON)))
                .then(Commands.literal("verbose").executes(c -> setBotLog(c.getSource(), BwBotLog.Stream.VERBOSE)))
                .then(Commands.literal("off").executes(c -> setBotLog(c.getSource(), BwBotLog.Stream.OFF))));
        bw.then(Commands.literal("stop").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS)).executes(c -> {
            broadcast("§cThe match was stopped by an admin.");
            finish();
            c.getSource().sendSuccess(() -> Component.literal("Bedwars stopped and reset."), true);
            return 1;
        }));
        bw.then(Commands.literal("skip").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS)).executes(c -> {
            if (state == State.COUNTDOWN) {
                start();
                return 1;
            }
            if (state != State.PLAYING) {
                c.getSource().sendFailure(Component.literal("Nothing to skip."));
                return 0;
            }
            for (BwRules.Event e : BwRules.Event.values()) {
                if (e.tick() > matchTicks) {
                    matchTicks = e.tick() - 1;
                    c.getSource().sendSuccess(() -> Component.literal("Skipping to " + e.display + "."), true);
                    return 1;
                }
            }
            return 0;
        }));
        LiteralArgumentBuilder<CommandSourceStack> map = Commands.literal("map").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS)).executes(c -> {
            c.getSource().sendSuccess(() -> Component.literal("Current map: " + BwMap.display() + ". Use /bw map " + String.join("|", BwMapDesigns.IDS)), false);
            return 1;
        });
        for (int i = 0; i < BwMapDesigns.IDS.length; i++) {
            final int idx = i;
            map.then(Commands.literal(BwMapDesigns.IDS[i]).executes(c -> {
                if (state != State.WAITING || !players.isEmpty()) {
                    c.getSource().sendFailure(Component.literal("Everyone must leave first (or use /bw stop)."));
                    return 0;
                }
                BwMap.select(idx);
                rebuild();
                c.getSource().sendSuccess(() -> Component.literal("Bedwars map set to " + BwMap.display() + "."), true);
                return 1;
            }));
        }
        bw.then(map);
        bw.then(Commands.literal("rebuild").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS)).executes(c -> {
            if (state != State.WAITING || !players.isEmpty()) {
                c.getSource().sendFailure(Component.literal("Stop the match first with /bw stop."));
                return 0;
            }
            rebuild();
            c.getSource().sendSuccess(() -> Component.literal("Bedwars map rebuilt."), true);
            return 1;
        }));
        return bw;
    }

    private static int setBotLog(CommandSourceStack src, BwBotLog.Stream s) {
        BwBotLog.stream = s;
        JsonObject cfg = Stacks.readFile("bedwars.json");
        cfg.addProperty("botlog", s.name());
        Stacks.writeFile("bedwars.json", cfg);
        src.sendSuccess(() -> Component.literal("Bedwars bot log streaming to the console: " + s.name().toLowerCase() + "."), true);
        return 1;
    }

    private static int setAutofill(CommandSourceStack src, boolean on) {
        autofill = on;
        JsonObject cfg = Stacks.readFile("bedwars.json");
        cfg.addProperty("autofill", on);
        Stacks.writeFile("bedwars.json", cfg);
        src.sendSuccess(() -> Component.literal("Bedwars bot autofill " + (on ? "ON" : "OFF") + "."), true);
        return 1;
    }

    private static int forceStart(CommandSourceStack src, boolean pay) {
        if (players.isEmpty()) {
            src.sendFailure(Component.literal("Nobody has joined yet (/bw join, then /bw fill to add bots)."));
            return 0;
        }
        if (state != State.WAITING && state != State.COUNTDOWN) {
            src.sendFailure(Component.literal("A match is already running."));
            return 0;
        }
        forcePay = pay;
        start();
        return 1;
    }

    private static int fillCommand(CommandSourceStack src, int count) {
        if (state != State.WAITING && state != State.COUNTDOWN) {
            src.sendFailure(Component.literal("Bots can only be added before a match starts."));
            return 0;
        }
        if (realCount() == 0) {
            src.sendFailure(Component.literal("Join first (/bw join): bots fill in around real players."));
            return 0;
        }
        if (count < 0) filled = true;
        int added = addBots(count < 0 ? 99 : count);
        src.sendSuccess(() -> Component.literal("Added " + added + " bot" + (added == 1 ? "" : "s") + "."), true);
        if (added > 0) broadcast("§e" + added + " bot" + (added == 1 ? " has" : "s have") + " joined to fill the teams.");
        startCountdownIfReady(players.size() >= BwRules.maxPlayers() ? BwRules.FULL_COUNTDOWN_SECONDS : BwRules.COUNTDOWN_SECONDS);
        return 1;
    }

    private static void showStats(CommandSourceStack src, UUID id, String name) {
        long games = PlayerStats.get(id, "bw_play"), wins = PlayerStats.get(id, "bw_win");
        src.sendSuccess(() -> Component.literal("§c§lBedwars stats: §f" + name
                + "\n§7Games: §f" + games + "  §7Wins: §a" + wins + (games > 0 ? " §8(" + Math.round(100.0 * wins / games) + "%)" : "")
                + "\n§7Beds broken: §e" + PlayerStats.get(id, "bw_bed")
                + "\n§7Final kills: §c" + PlayerStats.get(id, "bw_final") + "  §7Kills: §f" + PlayerStats.get(id, "bw_kill")), false);
    }

    // =====================================================================
    // Joining and leaving
    // =====================================================================

    public static void join(ServerPlayer p) {
        join(p, null);
    }

    /**
     * Joins the queue. want = the team size asked for (null = whatever is queued, or 3v3v3v3 if
     * Bedwars is empty). The first player into an empty Bedwars sets the mode for that match.
     */
    public static void join(ServerPlayer p, BwRules.Mode want) {
        UUID id = p.getUUID();
        if (want != null && !players.containsKey(id) && state == State.WAITING && players.isEmpty() && want != BwRules.mode) {
            BwRules.mode = want;
            MIX.clear();
        } else if (want == null && players.isEmpty() && state == State.WAITING) {
            BwRules.mode = BwRules.Mode.TRIOS;
        }
        if (want != null && want != BwRules.mode && !players.containsKey(id)) {
            msg(p, "§cBedwars is " + (state == State.PLAYING ? "playing" : "queuing") + " §f" + BwRules.mode.display + " §cright now. §7Join that ("
                    + "§e/bw join§7) or wait for it to finish.");
            return;
        }
        if (players.containsKey(id)) {
            msg(p, "§eYou're already in Bedwars. §7/leave to quit.");
            return;
        }
        if (com.aidan.skyserver.goldwars.GoldWarsGame.isInGame(id) || com.aidan.skyserver.skywars.SkywarsGame.isInGame(id)) {
            msg(p, "§cLeave your other game first.");
            return;
        }
        if (Lobby.level() != null && !Lobby.isLobby(p.level())) {
            msg(p, "§cJoin Bedwars from the lobby (§e/lobby§c).");
            return;
        }
        if (state == State.ENDING) {
            msg(p, "§cA match is just ending. Try again in a few seconds.");
            return;
        }
        if (state == State.PLAYING) {
            msg(p, "§cA match is in progress. You can join the next one.");
            return;
        }
        if (level() == null) {
            msg(p, "§cThe Bedwars dimension didn't load. Check the server log.");
            Skyserver.LOGGER.error("Dimension skyserver:bedwars is missing (data/skyserver/dimension/bedwars.json).");
            return;
        }
        BwTeam team = pickTeam(id);
        if (team == null) {
            msg(p, "§cBedwars is full.");
            return;
        }
        if (!mapReady) rebuild();
        BwPlayer bp = new BwPlayer(new PlayerSnapshot(p), joinCounter++, team, false);
        players.put(id, bp);
        addToTeam(p, team);
        if (botCount() > 0) reconcileBots();
        toSpawn(p, bp);
        broadcast("§a" + p.getName().getString() + " joined " + team.colored() + " §a(" + players.size() + "/" + BwRules.maxPlayers() + ")");
        msg(p, "§eMode: §f" + BwRules.mode.display + " §8(" + BwRules.mode.text + ")§e. Map: §f" + BwMap.display() + "§e. Protect your bed, break theirs! §7(right-click the shopkeepers to buy)");
        VOTE.offer(p, BwMap.index());
        Parties.followLeader(p, "bedwars");
        if (BotManager.ENABLED && autofill && botCount() == 0) {
            int left = (int) Math.max(0, (BotManager.FILL_AFTER_SECONDS * 20L - (waitStart < 0 ? 0 : ticks - waitStart)) / 20);
            msg(p, "§7If the teams aren't full in " + clock(left * 20) + ", bots will fill the empty slots.");
        }
        if (!autofill || !BotManager.ENABLED) startCountdownIfReady(BwRules.COUNTDOWN_SECONDS);
        if (players.size() >= BwRules.maxPlayers()) startCountdownIfReady(BwRules.FULL_COUNTDOWN_SECONDS);
    }

    private static void startCountdownIfReady(int seconds) {
        if (players.size() < BwRules.MIN_PLAYERS) return;
        if (state == State.WAITING) {
            state = State.COUNTDOWN;
            timer = seconds * 20;
            broadcast("§eMatch starting in " + seconds + " seconds!");
        } else if (state == State.COUNTDOWN && timer > seconds * 20) {
            timer = seconds * 20;
            broadcast("§eStarting in " + seconds + " seconds!");
        }
    }

    /** Party first, then the team with the fewest real players (then fewest people). */
    private static BwTeam pickTeam(UUID id) {
        for (UUID mate : Parties.membersOf(id)) {
            BwPlayer mp = players.get(mate);
            if (mp != null && realCount(mp.team) < BwRules.perTeam()) return mp.team;
        }
        BwTeam best = null;
        for (BwTeam t : BwTeam.values()) {
            if (realCount(t) >= BwRules.perTeam()) continue;
            if (best == null || realCount(t) < realCount(best) || (realCount(t) == realCount(best) && count(t) < count(best))) best = t;
        }
        return best;
    }

    static void leave(ServerPlayer p, boolean voluntary, boolean disconnecting) {
        UUID id = p.getUUID();
        BwPlayer bp = players.remove(id);
        VOTE.remove(id);
        if (bp == null) {
            if (voluntary) msg(p, "§eYou're not in Bedwars.");
            return;
        }
        removeFromTeam(p, bp.team);
        DamageCredit.clear(id);
        p.setGlowingTag(false);
        Rewards.matchEnd(p, "Bedwars");
        if (!disconnecting) bp.snapshot.restore(p);
        if (voluntary) msg(p, "§eYou left Bedwars.");
        if (!bp.bot) broadcast("§7" + p.getName().getString() + " left Bedwars.");
        if (bp.bot) return;
        if (onlineRealCount() == 0) {
            if (state == State.PLAYING || state == State.ENDING) finish();
            else {
                removeAllBots();
                state = State.WAITING;
                waitStart = -1;
            }
            return;
        }
        if (state == State.WAITING || state == State.COUNTDOWN) {
            if (botCount() > 0) reconcileBots();
            if (state == State.COUNTDOWN && players.size() < BwRules.MIN_PLAYERS) {
                state = State.WAITING;
                broadcast("§cNot enough players. Countdown cancelled.");
            }
        }
        if (state == State.PLAYING) checkTeams();
    }

    private static void onDisconnect(ServerPlayer p) {
        BwPlayer bp = players.get(p.getUUID());
        if (bp == null) return;
        if (bp.bot || state != State.PLAYING) {
            leave(p, false, true);
            return;
        }
        bp.offline = true;
        bp.offlineSince = ticks;
        bp.respawnAt = -1;
        removeFromTeam(p, bp.team);
        if (!teams.get(bp.team).bed) bp.eliminated = true;
        broadcast("§7" + p.getName().getString() + " disconnected." + (bp.eliminated ? "" : " They can rejoin while their bed stands."));
        if (onlineRealCount() == 0) finish();
        else checkTeams();
    }

    private static void onReconnect(ServerPlayer p) {
        BwPlayer bp = players.get(p.getUUID());
        if (bp == null || !bp.offline) return;
        if (state != State.PLAYING) {
            players.remove(p.getUUID());
            return;
        }
        runNextTick(() -> {
            if (state != State.PLAYING || players.get(p.getUUID()) != bp || p.isRemoved()) return;
            bp.offline = false;
            bp.snapshot = new PlayerSnapshot(p);
            addToTeam(p, bp.team);
            if (bp.eliminated || teams.get(bp.team).out) {
                bp.eliminated = true;
                spectate(p, bp);
                msg(p, "§7Your team's bed is gone, so you're watching. §e/leave §7to go back.");
            } else {
                bp.respawnAt = ticks + BwRules.RESPAWN_SECONDS * 20L;
                spectate(p, bp);
                broadcast(bp.team.colored() + " §a" + p.getName().getString() + " rejoined the match!");
            }
        });
    }

    // =====================================================================
    // Bots
    // =====================================================================

    static int realCount() {
        int n = 0;
        for (BwPlayer bp : players.values()) if (!bp.bot) n++;
        return n;
    }

    private static int realCount(BwTeam t) {
        int n = 0;
        for (BwPlayer bp : players.values()) if (!bp.bot && bp.team == t) n++;
        return n;
    }

    private static int onlineRealCount() {
        int n = 0;
        for (BwPlayer bp : players.values()) if (!bp.bot && !bp.offline) n++;
        return n;
    }

    static int botCount() {
        int n = 0;
        for (BwPlayer bp : players.values()) if (bp.bot) n++;
        return n;
    }

    private static int botCount(BwTeam t) {
        int n = 0;
        for (BwPlayer bp : players.values()) if (bp.bot && bp.team == t) n++;
        return n;
    }

    /** Other teams (not this one) that have at least one bot. 5.8: Bedwars pay drops 15% per team like this. */
    private static Set<BwTeam> botTeamsExcept(BwTeam mine) {
        Set<BwTeam> s = EnumSet.noneOf(BwTeam.class);
        for (BwPlayer o : players.values()) if (o.bot && o.team != mine) s.add(o.team);
        return s;
    }

    static int count(BwTeam t) {
        int n = 0;
        for (BwPlayer bp : players.values()) if (bp.team == t) n++;
        return n;
    }

    private static final List<Difficulty> MIX = new ArrayList<>();

    /** Adds up to n bots to the emptiest teams. Every team gets the same mix of difficulties. */
    private static int addBots(int n) {
        if (MIX.isEmpty()) MIX.addAll(Difficulty.randomMix(BwRules.perTeam(), RNG));
        int added = 0;
        while (added < n) {
            BwTeam best = null;
            for (BwTeam t : BwTeam.values())
                if (count(t) < BwRules.perTeam() && (best == null || count(t) < count(best))) best = t;
            if (best == null) break;
            int slot = count(best);
            if (!addBot(best, MIX.get(Math.min(slot, MIX.size() - 1)))) break;
            added++;
        }
        return added;
    }

    /** True once bots have filled every slot: from then on leavers are replaced by bots too. */
    private static boolean filled = false;

    /** Fills every slot with bots. */
    private static void fillBots() {
        filled = true;
        int added = addBots(99);
        if (added > 0) broadcast("§eBots have filled the empty slots!");
        startCountdownIfReady(BwRules.FULL_COUNTDOWN_SECONDS);
    }

    /** A real player joined or left before the match: keep teams at 3 by adding or removing bots. */
    private static void reconcileBots() {
        if (realCount() == 0) {
            removeAllBots();
            return;
        }
        for (BwTeam t : BwTeam.values()) {
            while (count(t) > BwRules.perTeam() && botCount(t) > 0) removeBot(anyBot(t));
            while (count(t) < BwRules.perTeam() && botCount() > 0 && botsWanted()) {
                if (!addBot(t, MIX.isEmpty() ? Difficulty.MEDIUM : MIX.get(Math.min(count(t), MIX.size() - 1)))) break;
            }
        }
    }

    /** Once bots have filled every slot, it stays filled. */
    private static boolean botsWanted() {
        return filled;
    }

    private static ServerPlayer anyBot(BwTeam t) {
        for (Map.Entry<UUID, BwPlayer> e : players.entrySet())
            if (e.getValue().bot && e.getValue().team == t) return online(e.getKey());
        return null;
    }

    private static boolean addBot(BwTeam team, Difficulty d) {
        BotPlayer bot;
        try {
            bot = BotManager.spawn(d);
        } catch (RuntimeException e) {
            Skyserver.LOGGER.error("Bedwars: couldn't create a bot", e);
            return false;
        }
        BwPlayer bp = new BwPlayer(new PlayerSnapshot(bot), joinCounter++, team, true);
        players.put(bot.getUUID(), bp);
        addToTeam(bot, team);
        bot.setBrain(new BedwarsBrain(bot));
        toSpawn(bot, bp);
        return true;
    }

    private static void removeBot(ServerPlayer bot) {
        if (bot == null) return;
        BwPlayer bp = players.remove(bot.getUUID());
        if (bp != null) removeFromTeam(bot, bp.team);
        BotManager.remove(bot);
    }

    private static void removeAllBots() {
        filled = false;
        for (Map.Entry<UUID, BwPlayer> e : new ArrayList<>(players.entrySet())) {
            if (!e.getValue().bot) continue;
            ServerPlayer p = online(e.getKey());
            players.remove(e.getKey());
            if (p != null) {
                removeFromTeam(p, e.getValue().team);
                BotManager.remove(p);
            }
        }
        MIX.clear();
    }

    private static void tickBotFill() {
        if (!BotManager.ENABLED || !autofill || (state != State.WAITING && state != State.COUNTDOWN)) return;
        if (realCount() == 0) {
            waitStart = -1;
            if (botCount() > 0) removeAllBots();
            if (state == State.COUNTDOWN) state = State.WAITING;
            return;
        }
        if (filled || players.size() >= BwRules.maxPlayers()) return;
        if (waitStart < 0) waitStart = ticks;
        long left = BotManager.FILL_AFTER_SECONDS * 20L - (ticks - waitStart);
        if (left == 60 * 20 || left == 30 * 20 || left == 10 * 20) broadcast("§7Bots will fill the empty slots in " + (left / 20) + " seconds.");
        if (left <= 0) fillBots();
    }

    // =====================================================================
    // The match
    // =====================================================================

    private static void tick() {
        ticks++;
        if (server == null) return;
        if (!nextTick.isEmpty()) {
            List<Runnable> jobs = new ArrayList<>(nextTick);
            nextTick.clear();
            for (Runnable r : jobs) com.aidan.skyserver.Safe.run("bedwars job", r);
        }
        ServerLevel level = level();
        if (level != null && ticks % 20 == 0) {
            for (Entity e : level.getEntitiesOfClass(Entity.class, BwMap.box(), e -> (e.getType().getCategory() == MobCategory.MONSTER
                    && !e.entityTags().contains(BwItems.MITE_TAG)) || e.getType() == net.minecraft.world.entity.EntityTypes.CHICKEN)) e.discard();
        }
        if (players.isEmpty()) {
            waitStart = -1;
            filled = false;
            return;
        }
        tickBotFill();
        // Void and respawns.
        for (Map.Entry<UUID, BwPlayer> e : new ArrayList<>(players.entrySet())) {
            ServerPlayer p = online(e.getKey());
            BwPlayer bp = e.getValue();
            if (p == null || players.get(e.getKey()) != bp) continue;
            if (state == State.PLAYING && bp.respawnAt >= 0 && ticks >= bp.respawnAt) respawn(p, bp);
            else if (p.getY() < BwMap.voidY()) {
                if (state == State.PLAYING && bp.alive()) handleDeath(p, bp, recentAttacker(bp), true);
                else if (state != State.PLAYING) toSpawn(p, bp);
                else if (!p.isSpectator()) spectate(p, bp);
            }
        }
        switch (state) {
            case COUNTDOWN -> {
                timer--;
                if (timer % 20 == 0) {
                    int sec = timer / 20;
                    if (Announce.countdown(onlineReal(), sec, "§c", "§7Bedwars is starting")) broadcast("§eStarting in " + sec + " seconds.");
                }
                if (timer <= 0) start();
            }
            case PLAYING -> {
                matchTicks++;
                game(level);
            }
            case ENDING -> {
                timer--;
                if (timer <= 0) finish();
            }
            default -> {}
        }
        if (ticks % 20 == 0) actionBars();
    }

    private static void start() {
        int voted = VOTE.winner(BwMap.index());
        if (voted != BwMap.index() || !mapReady) {
            BwMap.select(voted);
            rebuild();
            broadcast("§eThe map is §f" + BwMap.display() + "§e!");
        }
        ServerLevel level = level();
        if (level == null) return;
        matchNumber++;
        placed.clear();
        BwItems.reset();
        diamondTier = emeraldTier = 0;
        suddenDeath = false;
        Arrays.fill(diamondAt, 20L * BwRules.DIAMOND_SECONDS[0]);
        Arrays.fill(emeraldAt, 20L * BwRules.EMERALD_SECONDS[0]);
        int withPlayers = 0;
        BwMapDesigns.MapData m = BwMap.data();
        for (BwTeam t : BwTeam.values()) {
            BwTeamState fresh = new BwTeamState(t);
            teams.put(t, fresh);
            if (count(t) > 0) {
                withPlayers++;
                BwMap.placeBed(level, t, BwMap.pos(m.bedFoot[t.ordinal()]), BwMap.pos(m.bedHead[t.ordinal()]));
            } else {
                fresh.bed = false;
                fresh.out = true;
            }
        }
        rewards = forcePay || withPlayers >= 2;
        forcePay = false;
        state = State.PLAYING;
        matchTicks = 0;
        for (Map.Entry<UUID, BwPlayer> e : players.entrySet()) {
            BwPlayer bp = e.getValue();
            bp.armor = bp.sword = bp.pick = bp.axe = 0;
            bp.shears = false;
            bp.kills = bp.finals = bp.beds = bp.deaths = 0;
            bp.eliminated = false;
            bp.respawnAt = -1;
            bp.active = false;
            bp.ender.clearContent();
            ServerPlayer p = online(e.getKey());
            if (p == null) continue;
            spawn(p, bp);
            if (rewards) Rewards.matchStart(p);
        }
        com.aidan.skyserver.bots.BwLearning.matchStarted(BwMap.id());
        startLog();
        broadcast("§c§lBEDWARS! §r§eMap: §f" + BwMap.display() + "§e. Protect your bed and destroy the others!");
        broadcast("§7Iron & gold at your generator, diamonds on the side islands, emeralds in the middle. Right-click the shopkeepers.");
        if (!rewards) broadcast("§7(Test match: only one team has players, so no money is paid. /bw start pay to test payouts.)");
        Announce.go(onlineReal(), "§c§lBEDWARS", "§7Protect your bed!");
    }

    /** Opens the match's training log with the map, the mode, who's on each team and where everything is. */
    private static void startLog() {
        BwMapDesigns.MapData m = BwMap.data();
        List<String> h = new ArrayList<>();
        h.add("MATCH " + matchNumber + " map " + BwMap.display() + " (" + BwMap.id() + "), mode " + BwRules.mode.display + ", rewards " + (rewards ? "on" : "off"));
        h.add("WORLD floor y=" + BwMap.OY + ", void below y=" + BwMap.voidY() + ", build area |x|,|z| <= " + m.half
                + ", y " + (BwMap.OY + m.buildBottom) + ".." + (BwMap.OY + m.buildTop));
        for (BwTeam t : BwTeam.values()) {
            int i = t.ordinal();
            if (count(t) == 0) {
                h.add("TEAM " + t.display + ": empty (out from the start)");
                continue;
            }
            h.add("TEAM " + t.display + ": spawn " + xyz(m.spawn[i]) + " bed " + xyz(m.bedFoot[i]) + "/" + xyz(m.bedHead[i]) + " generator " + xyz(m.gen[i])
                    + " shop " + xyz(m.shop[i]) + " upgrades " + xyz(m.upgrades[i]));
        }
        StringBuilder g = new StringBuilder("GENERATORS diamonds");
        for (int[] d : m.diamondGens) g.append(" ").append(xyz(d));
        g.append(" | emeralds");
        for (int[] e : m.emeraldGens) g.append(" ").append(xyz(e));
        h.add(g.toString());
        BwBotLog.start(BwMap.id() + "-" + BwRules.mode.display, h);
        for (Map.Entry<UUID, BwPlayer> e : players.entrySet()) {
            ServerPlayer p = online(e.getKey());
            if (p == null) continue;
            String extra = p instanceof BotPlayer b ? "difficulty " + b.difficulty().name().toLowerCase() : "";
            BwBotLog.member(p.getName().getString(), e.getValue().bot, e.getValue().team.display, extra);
        }
    }

    private static String xyz(int[] rel) {
        return (BwMap.OX + rel[0]) + "," + (BwMap.OY + rel[1]) + "," + (BwMap.OZ + rel[2]);
    }

    /** Every 2 seconds: where everyone is and what they're doing, for the training log. */
    private static void logStates() {
        for (Map.Entry<UUID, BwPlayer> e : players.entrySet()) {
            ServerPlayer p = online(e.getKey());
            BwPlayer bp = e.getValue();
            if (p == null || !bp.alive()) continue;
            int[] r = BwShop.resources(p);
            String gear = "hp" + Math.round(p.getHealth()) + " i" + r[0] + " g" + r[1] + " d" + r[2] + " e" + r[3]
                    + " gear a" + bp.armor + "/s" + bp.sword + "/p" + bp.pick + "/x" + bp.axe;
            if (p instanceof BotPlayer b && b.brain() instanceof BedwarsBrain brain) {
                BwBotLog.state(p.getName().getString(), true, bp.team.display + " " + brain.roleName(), p.getX(), p.getY(), p.getZ(),
                        brain.doing(), gear + " blk" + brain.blocks() + " | " + brain.pathDebug());
            } else {
                BwBotLog.state(p.getName().getString(), false, bp.team.display + " player", p.getX(), p.getY(), p.getZ(),
                        p.isSprinting() ? "running" : "moving", gear);
            }
        }
    }

    /** Everything that happens every tick of a match. */
    private static void game(ServerLevel level) {
        if (level == null) return;
        for (BwRules.Event e : BwRules.Event.values()) if (matchTicks == e.tick()) event(e);
        generators(level);
        BwItems.tick(level);
        if (ticks % 10 == 0) traps(level);
        if (ticks % 20 == 0) {
            for (Map.Entry<UUID, BwPlayer> e : players.entrySet()) {
                if (!e.getValue().alive()) continue;
                ServerPlayer p = online(e.getKey());
                if (p != null) enforceGear(p, e.getValue());
            }
            signs();
            checkTeams();
        }
        if (ticks % 40 == 0 && BwBotLog.isOpen()) logStates();
        if (ticks % 40 == 0) teamEffects();
        if (suddenDeath && ticks % 10 == 0) border(level);
    }

    private static void event(BwRules.Event e) {
        BwBotLog.line("EVENT " + e.display);
        switch (e) {
            case DIAMOND_2, DIAMOND_3 -> {
                diamondTier = e == BwRules.Event.DIAMOND_2 ? 1 : 2;
                broadcast("§bDiamond generators §eare now §cTier " + roman(diamondTier + 1) + "§e!");
            }
            case EMERALD_2, EMERALD_3 -> {
                emeraldTier = e == BwRules.Event.EMERALD_2 ? 1 : 2;
                broadcast("§2Emerald generators §eare now §cTier " + roman(emeraldTier + 1) + "§e!");
            }
            case BEDS_GONE -> {
                boolean any = false;
                for (BwTeamState ts : teams.values()) {
                    if (!ts.bed) continue;
                    destroyBed(ts.team);
                    any = true;
                }
                if (any) {
                    broadcast("§c§lALL BEDS HAVE BEEN DESTROYED! §r§7Every death is final now.");
                    for (ServerPlayer p : onlineReal()) Announce.title(p, "§c§lBED DESTRUCTION", "§7All beds have been destroyed", 5, 50, 10);
                    sound(SoundEvents.ENDER_DRAGON_GROWL, 1f);
                }
            }
            case SUDDEN_DEATH -> {
                suddenDeath = true;
                suddenStart = matchTicks;
                broadcast("§4§lSUDDEN DEATH! §r§cThe border is closing in on the middle and everyone glows. Stay inside!");
                for (ServerPlayer p : onlineReal()) Announce.title(p, "§4§lSUDDEN DEATH", "§cThe border is closing in!", 5, 50, 10);
                sound(SoundEvents.WITHER_SPAWN, 0.7f);
            }
            case GAME_END -> {
                BwTeam best = null;
                boolean tie = false;
                for (BwTeamState ts : teams.values()) {
                    if (ts.out) continue;
                    if (best == null || ts.finals > teams.get(best).finals) {
                        best = ts.team;
                        tie = false;
                    } else if (ts.finals == teams.get(best).finals) tie = true;
                }
                broadcast("§eTime's up! " + (best == null || tie ? "§7It's a §ftie§7." : best.colored() + " §ewins with the most final kills (" + teams.get(best).finals + ")."));
                endMatch(tie ? null : best);
            }
        }
    }

    private static String roman(int n) {
        return n <= 1 ? "I" : n == 2 ? "II" : n == 3 ? "III" : "IV";
    }

    // ---- Generators ----

    private static void generators(ServerLevel level) {
        BwMapDesigns.MapData m = BwMap.data();
        for (BwTeamState ts : teams.values()) {
            if (ts.out) continue;
            int forge = ts.level(BwRules.Upgrade.FORGE);
            BlockPos g = BwMap.pos(m.gen[ts.team.ordinal()]);
            if (matchTicks >= ts.ironAt) {
                ts.ironAt = matchTicks + BwRules.IRON_TICKS[forge];
                drop(level, g, Items.IRON_INGOT, BwRules.IRON_CAP);
            }
            if (matchTicks >= ts.goldAt) {
                ts.goldAt = matchTicks + BwRules.GOLD_TICKS[forge];
                drop(level, g, Items.GOLD_INGOT, BwRules.GOLD_CAP);
            }
            if (forge >= 3 && matchTicks >= ts.emeraldAt) {
                ts.emeraldAt = matchTicks + BwRules.HOME_EMERALD_TICKS;
                drop(level, g, Items.EMERALD, BwRules.HOME_EMERALD_CAP);
            }
        }
        for (int i = 0; i < m.diamondGens.size(); i++) {
            if (matchTicks >= diamondAt[i]) {
                diamondAt[i] = matchTicks + 20L * BwRules.DIAMOND_SECONDS[diamondTier];
                drop(level, BwMap.pos(m.diamondGens.get(i)), Items.DIAMOND, BwRules.DIAMOND_CAP);
            }
        }
        for (int i = 0; i < m.emeraldGens.size(); i++) {
            if (matchTicks >= emeraldAt[i]) {
                emeraldAt[i] = matchTicks + 20L * BwRules.EMERALD_SECONDS[emeraldTier];
                drop(level, BwMap.pos(m.emeraldGens.get(i)), Items.EMERALD, BwRules.EMERALD_CAP);
            }
        }
    }

    private static void drop(ServerLevel level, BlockPos at, Item item, int cap) {
        int have = 0;
        for (ItemEntity ie : level.getEntitiesOfClass(ItemEntity.class, new AABB(at).inflate(1.5), ie -> ie.getItem().is(item))) have += ie.getItem().getCount();
        if (have >= cap) return;
        ItemEntity ie = new ItemEntity(level, at.getX() + 0.5, at.getY() + 0.05, at.getZ() + 0.5, new ItemStack(item), 0, 0, 0);
        ie.setPickUpDelay(0);
        level.addFreshEntity(ie);
    }

    private static void signs() {
        BwMapDesigns.MapData m = BwMap.data();
        int n = m.diamondGens.size();
        for (int i = 0; i < n; i++)
            BwMap.setSign(i, "§b§lDiamond §7Tier §e" + roman(diamondTier + 1), "§eSpawns in §c" + Math.max(0, (diamondAt[i] - matchTicks + 19) / 20) + "§es");
        for (int i = 0; i < m.emeraldGens.size(); i++)
            BwMap.setSign(n + i, "§2§lEmerald §7Tier §e" + roman(emeraldTier + 1), "§eSpawns in §c" + Math.max(0, (emeraldAt[i] - matchTicks + 19) / 20) + "§es");
    }

    // ---- Traps, the bed alarm, heal pool and haste ----

    private static void traps(ServerLevel level) {
        BwMapDesigns.MapData m = BwMap.data();
        List<ServerPlayer> alive = alivePlayers();
        for (BwTeamState ts : teams.values()) {
            if (ts.out) continue;
            int i = ts.team.ordinal();
            BlockPos base = BwMap.pos(m.base[i]), bed = BwMap.pos(m.bedFoot[i]);
            for (ServerPlayer e : alive) {
                BwPlayer ep = players.get(e.getUUID());
                if (ep == null || ep.team == ts.team) continue;
                if (ts.bed && e.position().distanceTo(Vec3.atCenterOf(bed)) < BwRules.BED_ALARM_RADIUS && ticks - ts.lastAlarm > 200) {
                    ts.lastAlarm = ticks;
                    teamMsg(ts.team, "§c⚠ An enemy is near your bed! §7(" + ep.team.color + e.getName().getString() + "§7)");
                    BwBotLog.event(e.getName().getString(), "ALARM", "near " + ts.team.display + "'s bed");
                    for (ServerPlayer mate : teamOnline(ts.team)) mate.level().playSound(null, mate.getX(), mate.getY(), mate.getZ(), SoundEvents.NOTE_BLOCK_BELL.value(), SoundSource.MASTER, 1f, 0.8f);
                    com.aidan.skyserver.bots.BwLearning.enemyNearBed(BwMap.id(), e instanceof BotPlayer, matchTicks);
                }
                if (!ts.traps.isEmpty() && e.position().distanceTo(Vec3.atCenterOf(base)) < BwRules.TRAP_RADIUS && ticks - ts.lastTrap > 200 && ticks >= ep.trapImmuneUntil) {
                    ts.lastTrap = ticks;
                    trigger(ts, e, ep);
                }
            }
        }
    }

    private static void trigger(BwTeamState ts, ServerPlayer intruder, BwPlayer ip) {
        BwRules.Trap trap = ts.traps.poll();
        if (trap == null) return;
        switch (trap) {
            case ITS_A_TRAP -> {
                intruder.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, 160, 0));
                intruder.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 160, 1));
            }
            case COUNTER -> {
                BlockPos base = BwMap.pos(BwMap.data().base[ts.team.ordinal()]);
                for (ServerPlayer mate : teamAlive(ts.team)) {
                    if (mate.position().distanceTo(Vec3.atCenterOf(base)) > 18) continue;
                    mate.addEffect(new MobEffectInstance(MobEffects.SPEED, 300, 0));
                    mate.addEffect(new MobEffectInstance(MobEffects.JUMP_BOOST, 300, 1));
                }
            }
            case ALARM -> {
                intruder.removeEffect(MobEffects.INVISIBILITY);
                intruder.addEffect(new MobEffectInstance(MobEffects.GLOWING, 200, 0));
                for (ServerPlayer mate : teamOnline(ts.team)) Announce.title(mate, "§c§lALARM!", ip.team.color + intruder.getName().getString() + " §7is in your base", 0, 40, 10);
            }
            case MINER_FATIGUE -> intruder.addEffect(new MobEffectInstance(MobEffects.MINING_FATIGUE, 200, 0));
        }
        for (ServerPlayer mate : teamOnline(ts.team)) {
            if (trap != BwRules.Trap.ALARM) Announce.title(mate, "§c§lTRAP TRIGGERED!", "§7" + trap.display + " §8- " + ip.team.color + intruder.getName().getString(), 0, 40, 10);
            mate.level().playSound(null, mate.getX(), mate.getY(), mate.getZ(), SoundEvents.NOTE_BLOCK_PLING.value(), SoundSource.MASTER, 1f, 0.5f);
        }
        msg(intruder, "§cYou set off " + ts.team.colored() + "§c's " + trap.display + "!");
        BwBotLog.event(intruder.getName().getString(), "TRAP", "set off " + ts.team.display + "'s " + trap.display);
    }

    private static void teamEffects() {
        BwMapDesigns.MapData m = BwMap.data();
        for (BwTeamState ts : teams.values()) {
            if (ts.out) continue;
            int haste = ts.level(BwRules.Upgrade.HASTE);
            boolean pool = ts.level(BwRules.Upgrade.HEAL_POOL) > 0;
            BlockPos base = BwMap.pos(m.base[ts.team.ordinal()]);
            for (ServerPlayer p : teamAlive(ts.team)) {
                if (haste > 0) p.addEffect(new MobEffectInstance(MobEffects.HASTE, 100, haste - 1, true, false));
                if (pool && p.position().distanceTo(Vec3.atCenterOf(base)) < BwRules.HEAL_POOL_RADIUS)
                    p.addEffect(new MobEffectInstance(MobEffects.REGENERATION, 80, 0, true, true));
                if (suddenDeath) p.setGlowingTag(true);
            }
            if (ticks < ts.blanketUntil && ts.bed) {
                BlockPos bed = BwMap.pos(m.bedFoot[ts.team.ordinal()]);
                level().sendParticles(ParticleTypes.HAPPY_VILLAGER, bed.getX() + 0.5, bed.getY() + 0.8, bed.getZ() + 0.5, 12, 0.8, 0.4, 0.8, 0.02);
            }
        }
    }

    // ---- Sudden death border ----

    static int borderHalf() {
        BwMapDesigns.MapData m = BwMap.data();
        double f = Math.min(1, (matchTicks - suddenStart) / (BwRules.BORDER_SHRINK_MINUTES * 60.0 * 20.0));
        return (int) Math.round(m.half - (m.half - BwRules.BORDER_END_HALF) * f);
    }

    private static void border(ServerLevel level) {
        int half = borderHalf();
        for (ServerPlayer p : alivePlayers()) {
            double dx = Math.abs(p.getX() - BwMap.OX - 0.5), dz = Math.abs(p.getZ() - BwMap.OZ - 0.5);
            if (dx > half || dz > half) {
                p.hurtServer(level, level.damageSources().magic(), 1f); // a heart a second
                Announce.actionBar(p, "§c§lYou're outside the border! §7Get to the middle!");
            }
            // Show the nearby part of the border as a wall of particles.
            double edgeX = Math.signum(p.getX() - BwMap.OX) * half + BwMap.OX, edgeZ = Math.signum(p.getZ() - BwMap.OZ) * half + BwMap.OZ;
            if (Math.abs(dx - half) < 12)
                for (int k = -6; k <= 6; k += 2) level.sendParticles(p, ParticleTypes.FLAME, true, false, edgeX, p.getY() + 1, p.getZ() + k, 2, 0, 1.2, 0.3, 0);
            if (Math.abs(dz - half) < 12)
                for (int k = -6; k <= 6; k += 2) level.sendParticles(p, ParticleTypes.FLAME, true, false, p.getX() + k, p.getY() + 1, edgeZ, 2, 0.3, 1.2, 0, 0);
        }
    }

    // =====================================================================
    // Beds
    // =====================================================================

    private static void bedBroken(BwTeam owner, ServerPlayer breaker, BwPlayer bp) {
        destroyBed(owner);
        bp.beds++;
        BwTeamState ts = teams.get(owner);
        broadcast("§f§lBED DESTRUCTION > " + owner.colored() + " Bed §7was destroyed by " + bp.team.color + breaker.getName().getString() + "§7!");
        BwBotLog.bed(breaker.getName().getString(), owner.display);
        for (Map.Entry<UUID, BwPlayer> e : players.entrySet()) {
            if (e.getValue().team != owner) continue;
            ServerPlayer p = online(e.getKey());
            if (p != null) Announce.title(p, "§c§lBED DESTROYED!", "§7You will no longer respawn!", 5, 50, 10);
        }
        sound(SoundEvents.WITHER_DEATH, 0.5f);
        if (rewards && !bp.bot) {
            pay(breaker, Math.round(Rewards.BW_BED * Rewards.bedwarsTeamScale(botTeamsExcept(bp.team).size())), "for breaking " + owner.display + "'s bed");
            Levels.addXp(server, breaker, Rewards.BW_BED_XP);
            Quests.progress(breaker, "bw_bed", 1);
            PlayerStats.add(breaker, "bw_bed", 1);
            Skills.add(breaker, Skills.Skill.COMBAT, 150);
        }
        if (breaker instanceof BotPlayer b && b.brain() instanceof BedwarsBrain brain) brain.onBedBroken(owner);
        com.aidan.skyserver.bots.BwLearning.bedBroken(BwMap.id(), !(breaker instanceof BotPlayer), matchTicks);
        if (ts.out) return;
        checkTeams();
    }

    private static void destroyBed(BwTeam team) {
        BwTeamState ts = teams.get(team);
        ts.bed = false;
        ServerLevel level = level();
        BwMapDesigns.MapData m = BwMap.data();
        if (level != null) {
            BlockPos foot = BwMap.pos(m.bedFoot[team.ordinal()]), head = BwMap.pos(m.bedHead[team.ordinal()]);
            level.setBlock(head, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
            level.setBlock(foot, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
            level.sendParticles(ParticleTypes.EXPLOSION, foot.getX() + 0.5, foot.getY() + 0.5, foot.getZ() + 0.5, 3, 0.4, 0.3, 0.4, 0);
        }
    }

    static boolean bedAlive(BwTeam t) {
        BwTeamState ts = teams.get(t);
        return ts != null && ts.bed;
    }

    // =====================================================================
    // Deaths and respawns
    // =====================================================================

    private static UUID recentAttacker(BwPlayer bp) {
        if (bp.lastAttacker == null) return null;
        return ticks - bp.lastAttackTick <= 10 * 20 ? bp.lastAttacker : null;
    }

    static void handleDeath(ServerPlayer p, BwPlayer bp, UUID killerId, boolean voidDeath) {
        if (!bp.alive()) return;
        bp.deaths++;
        BwTeamState ts = teams.get(bp.team);
        boolean fin = !ts.bed;
        BwPlayer kp = killerId == null ? null : players.get(killerId);
        ServerPlayer killer = kp == null || kp.team == bp.team ? null : online(killerId);
        if (killer == null) kp = null;

        // Their resources go to whoever killed them.
        if (killer != null && kp.alive()) giveResources(p, killer);

        String victim = bp.team.color + p.getName().getString() + "§7";
        String how = killer == null ? (voidDeath ? " fell into the void." : " died.")
                : (voidDeath ? " was knocked into the void by " : " was killed by ") + kp.team.color + killer.getName().getString() + "§7.";
        broadcast(victim + how + (fin ? " §b§lFINAL KILL!" : ""));
        BwBotLog.death(p.getName().getString(), killer == null ? null : killer.getName().getString(),
                (voidDeath ? "void" : "killed") + String.format(" at %.0f %.0f %.0f", p.getX(), p.getY(), p.getZ()), fin);

        // Kill money, shared by everyone on other teams who hurt them.
        long base = fin ? Rewards.BW_FINAL : Rewards.BW_KILL;
        if (bp.bot) base = Math.round(base * Rewards.BOT_KILL_PAY);
        for (Map.Entry<UUID, Double> sh : DamageCredit.shares(p.getUUID(), killer == null ? null : killer.getUUID()).entrySet()) {
            BwPlayer who = players.get(sh.getKey());
            ServerPlayer wp = online(sh.getKey());
            if (who == null || wp == null || who.team == bp.team) continue;
            long amt = Math.max(1, Math.round(base * sh.getValue()));
            pay(wp, amt, sh.getValue() >= 0.999 ? (fin ? "final kill" : "kill") + (bp.bot ? " (bot)" : "")
                    : Math.round(sh.getValue() * 100) + "% of a " + (fin ? "final " : "") + "kill on " + p.getName().getString());
        }
        if (killer != null) {
            kp.kills++;
            if (fin) {
                kp.finals++;
                teams.get(kp.team).finals++;
            }
            if (!kp.bot && rewards) {
                PlayerStats.add(killer, fin ? "bw_final" : "bw_kill", 1);
                PlayerStats.add(killer, "game_kill", 1);
                if (fin) Quests.progress(killer, "bw_final", 1);
                Levels.addXp(server, killer, (fin ? Rewards.BW_FINAL_XP : Rewards.BW_KILL_XP) / (bp.bot ? 2 : 1));
                Skills.add(killer, Skills.Skill.COMBAT, (fin ? 150 : 100) / (bp.bot ? 2 : 1));
            }
            killer.level().playSound(null, killer.getX(), killer.getY(), killer.getZ(), SoundEvents.EXPERIENCE_ORB_PICKUP, SoundSource.PLAYERS, 0.8f, 1.2f);
        }
        if (p instanceof BotPlayer b && b.brain() instanceof BedwarsBrain brain) brain.onDeath(killer);
        // Tools drop a level (never below the first), the sword goes back to wood.
        // 5.19: armor drops one tier too (diamond -> iron -> chain), never below chain. Bots the same.
        if (bp.armor > BwRules.ARMOR_FLOOR) bp.armor--;
        if (bp.pick > 1) bp.pick--;
        if (bp.axe > 1) bp.axe--;
        bp.sword = 0;
        bp.lastAttacker = null;
        p.getInventory().clearContent();
        p.removeAllEffects();
        p.setGlowingTag(false);
        if (fin) {
            bp.eliminated = true;
            bp.respawnAt = -1;
            spectate(p, bp);
            Announce.title(p, "§c§lELIMINATED", "§7Your bed was gone. §e/leave §7to go back", 5, 60, 10);
            checkTeams();
        } else {
            bp.respawnAt = ticks + BwRules.RESPAWN_SECONDS * 20L;
            spectate(p, bp);
            Announce.title(p, "§c§lYOU DIED", "§eRespawning in " + BwRules.RESPAWN_SECONDS + " seconds", 0, 40, 10);
        }
    }

    /** Moves the victim's iron, gold, diamonds and emeralds into the killer's inventory. */
    private static void giveResources(ServerPlayer from, ServerPlayer to) {
        int[] got = new int[4];
        Inventory inv = from.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            int r = resource(s);
            if (r < 0) continue;
            got[r] += s.getCount();
            give(to, s.copy());
            inv.setItem(i, ItemStack.EMPTY);
        }
        StringBuilder sb = new StringBuilder();
        for (int r = 0; r < 4; r++) if (got[r] > 0) sb.append(BwRules.CURRENCY_COLOR[r]).append("+").append(got[r]).append(" ").append(BwRules.CURRENCY[r]).append(" ");
        if (sb.length() > 0) msg(to, sb.toString().trim());
    }

    static int resource(ItemStack s) {
        if (s.is(Items.IRON_INGOT)) return BwRules.IRON;
        if (s.is(Items.GOLD_INGOT)) return BwRules.GOLD;
        if (s.is(Items.DIAMOND)) return BwRules.DIAMOND;
        if (s.is(Items.EMERALD)) return BwRules.EMERALD;
        return -1;
    }

    static void give(ServerPlayer p, ItemStack s) {
        if (!p.getInventory().add(s) && !s.isEmpty()) p.drop(s, false, net.minecraft.util.Prediction.SERVER_ONLY);
    }

    private static void spectate(ServerPlayer p, BwPlayer bp) {
        p.setGameMode(GameType.SPECTATOR);
        int[] s = BwMap.data().spawn[bp.team.ordinal()];
        p.teleportTo(level(), BwMap.OX + s[0] + 0.5, BwMap.OY + s[1] + 9, BwMap.OZ + s[2] + 0.5, Set.of(), BwMap.data().spawnYaw[bp.team.ordinal()], 50f, true);
        resetVitals(p);
    }

    private static void respawn(ServerPlayer p, BwPlayer bp) {
        if (!teams.get(bp.team).bed && bp.offline) return;
        spawn(p, bp);
        if (p instanceof BotPlayer b && b.brain() instanceof BedwarsBrain brain) brain.onRespawn();
    }

    /** Into the match at the team's spawn with the starting kit plus kept gear. */
    private static void spawn(ServerPlayer p, BwPlayer bp) {
        bp.respawnAt = -1;
        p.getInventory().clearContent();
        p.setGameMode(GameType.SURVIVAL);
        resetVitals(p);
        giveKit(p, bp);
        BwMapDesigns.MapData m = BwMap.data();
        int i = bp.team.ordinal();
        p.teleportTo(level(), BwMap.OX + m.spawn[i][0] + 0.5, BwMap.OY + m.spawn[i][1], BwMap.OZ + m.spawn[i][2] + 0.5, Set.of(), m.spawnYaw[i], 0f, true);
        bp.invulnUntil = ticks + BwRules.SPAWN_INVULN_SECONDS * 20L;
        if (suddenDeath) p.setGlowingTag(true);
    }

    /** Waiting for the match: on your team's spawn pad, nothing in your hands. */
    private static void toSpawn(ServerPlayer p, BwPlayer bp) {
        p.getInventory().clearContent();
        p.setGameMode(GameType.ADVENTURE);
        resetVitals(p);
        BwMapDesigns.MapData m = BwMap.data();
        int i = bp.team.ordinal();
        if (level() == null) return;
        p.teleportTo(level(), BwMap.OX + m.spawn[i][0] + 0.5, BwMap.OY + m.spawn[i][1], BwMap.OZ + m.spawn[i][2] + 0.5, Set.of(), m.spawnYaw[i], 0f, true);
    }

    // =====================================================================
    // Gear
    // =====================================================================

    static Holder<Enchantment> ench(ResourceKey<Enchantment> key) {
        return server.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(key);
    }

    static ItemStack unbreakable(ItemStack s) {
        s.set(DataComponents.UNBREAKABLE, Unit.INSTANCE);
        return s;
    }

    static DyeColor dye(BwTeam t) {
        return switch (t) {
            case RED -> DyeColor.RED;
            case BLUE -> DyeColor.BLUE;
            case GREEN -> DyeColor.LIME;
            case YELLOW -> DyeColor.YELLOW;
        };
    }

    static final Item[] SWORDS = {Items.WOODEN_SWORD, Items.STONE_SWORD, Items.IRON_SWORD, Items.DIAMOND_SWORD};
    static final Item[] PICKS = {null, Items.WOODEN_PICKAXE, Items.IRON_PICKAXE, Items.GOLDEN_PICKAXE, Items.DIAMOND_PICKAXE};
    static final Item[] AXES = {null, Items.WOODEN_AXE, Items.STONE_AXE, Items.IRON_AXE, Items.DIAMOND_AXE};
    private static final Item[][] LEGS_BOOTS = {{Items.LEATHER_LEGGINGS, Items.LEATHER_BOOTS}, {Items.CHAINMAIL_LEGGINGS, Items.CHAINMAIL_BOOTS},
            {Items.IRON_LEGGINGS, Items.IRON_BOOTS}, {Items.DIAMOND_LEGGINGS, Items.DIAMOND_BOOTS}};

    static ItemStack sword(int tier, BwTeam team) {
        ItemStack s = unbreakable(new ItemStack(SWORDS[tier]));
        int sharp = teams.get(team).level(BwRules.Upgrade.SHARPNESS);
        if (sharp > 0) s.enchant(ench(Enchantments.SHARPNESS), sharp);
        return s;
    }

    static ItemStack pickaxe(int level) {
        ItemStack s = unbreakable(new ItemStack(PICKS[level]));
        s.enchant(ench(Enchantments.EFFICIENCY), level == 1 ? 1 : level == 2 ? 2 : 3);
        return s;
    }

    static ItemStack axe(int level, BwTeam team) {
        ItemStack s = unbreakable(new ItemStack(AXES[level]));
        s.enchant(ench(Enchantments.EFFICIENCY), level <= 2 ? 1 : level == 3 ? 2 : 3);
        int sharp = teams.get(team).level(BwRules.Upgrade.SHARPNESS);
        if (sharp > 0) s.enchant(ench(Enchantments.SHARPNESS), sharp);
        return s;
    }

    private static void giveKit(ServerPlayer p, BwPlayer bp) {
        Inventory inv = p.getInventory();
        inv.setItem(0, sword(bp.sword, bp.team));
        if (bp.pick > 0) give(p, pickaxe(bp.pick));
        if (bp.axe > 0) give(p, axe(bp.axe, bp.team));
        if (bp.shears) give(p, unbreakable(new ItemStack(Items.SHEARS)));
        equipArmor(p, bp);
    }

    private static ItemStack armorPiece(Item item, BwPlayer bp) {
        ItemStack s = unbreakable(new ItemStack(item));
        if (item == Items.LEATHER_HELMET || item == Items.LEATHER_CHESTPLATE || item == Items.LEATHER_LEGGINGS || item == Items.LEATHER_BOOTS)
            s.set(DataComponents.DYED_COLOR, new DyedItemColor(bp.team.leather));
        int prot = teams.get(bp.team).level(BwRules.Upgrade.PROTECTION);
        if (prot > 0) s.enchant(ench(Enchantments.PROTECTION), prot);
        return s;
    }

    static void equipArmor(ServerPlayer p, BwPlayer bp) {
        p.setItemSlot(EquipmentSlot.HEAD, armorPiece(Items.LEATHER_HELMET, bp));
        p.setItemSlot(EquipmentSlot.CHEST, armorPiece(Items.LEATHER_CHESTPLATE, bp));
        p.setItemSlot(EquipmentSlot.LEGS, armorPiece(LEGS_BOOTS[bp.armor][0], bp));
        p.setItemSlot(EquipmentSlot.FEET, armorPiece(LEGS_BOOTS[bp.armor][1], bp));
    }

    /** Every second: armor stays on (and up to date), swords get Sharpness, nobody is left without a sword. */
    private static void enforceGear(ServerPlayer p, BwPlayer bp) {
        int prot = teams.get(bp.team).level(BwRules.Upgrade.PROTECTION);
        Item[] want = {Items.LEATHER_HELMET, Items.LEATHER_CHESTPLATE, LEGS_BOOTS[bp.armor][0], LEGS_BOOTS[bp.armor][1]};
        EquipmentSlot[] slots = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};
        boolean fix = false;
        for (int k = 0; k < 4; k++) {
            ItemStack worn = p.getItemBySlot(slots[k]);
            if (!worn.is(want[k]) || EnchantmentHelper.getItemEnchantmentLevel(ench(Enchantments.PROTECTION), worn) != prot) fix = true;
        }
        Inventory inv = p.getInventory();
        boolean hasSword = false;
        int sharp = teams.get(bp.team).level(BwRules.Upgrade.SHARPNESS);
        for (int i = 0; i < 36; i++) {
            ItemStack s = inv.getItem(i);
            if (s.isEmpty()) continue;
            if (com.aidan.skyserver.bots.BotBrain.armorSlot(s) != null) {
                inv.setItem(i, ItemStack.EMPTY); // armor taken off: it goes back on below
                fix = true;
                continue;
            }
            String id = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(s.getItem()).getPath();
            boolean blade = id.endsWith("_sword") || id.endsWith("_axe");
            if (id.endsWith("_sword")) hasSword = true;
            if (blade && sharp > 0 && EnchantmentHelper.getItemEnchantmentLevel(ench(Enchantments.SHARPNESS), s) < sharp) s.enchant(ench(Enchantments.SHARPNESS), sharp);
        }
        if (fix) equipArmor(p, bp);
        if (!hasSword && p.containerMenu == p.inventoryMenu && p.containerMenu.getCarried().isEmpty()) give(p, sword(0, bp.team));
    }

    // =====================================================================
    // Teams going out, and the end of the match
    // =====================================================================

    /** Knocks out teams with nobody left, and ends the match when one team remains. */
    static void checkTeams() {
        if (state != State.PLAYING) return;
        for (BwTeamState ts : teams.values()) {
            if (ts.out) continue;
            boolean anyone = false, anyOnline = false;
            boolean waiting = false; // someone dropped out less than a minute ago: give them time to come back
            for (BwPlayer bp : players.values()) {
                if (bp.team != ts.team) continue;
                if (!bp.offline) anyOnline = true;
                if (!bp.eliminated && !bp.offline) anyone = true;
                if (bp.offline && !bp.eliminated && ts.bed && ticks - bp.offlineSince < 60 * 20) waiting = true;
            }
            if (waiting && !anyOnline) continue;
            if (!anyOnline && ts.bed) {
                destroyBed(ts.team);
                broadcast(ts.team.colored() + " §7has nobody left online, so their bed is gone.");
            }
            if (!anyone) {
                int left = 0;
                for (BwTeamState o : teams.values()) if (!o.out) left++;
                ts.out = true;
                ts.place = left;
                if (ts.bed) destroyBed(ts.team);
                broadcast("§f§lTEAM ELIMINATED > " + ts.team.colored() + " Team §chas been eliminated!");
                BwBotLog.line("TEAM " + ts.team.display + " eliminated (place " + ts.place + ")");
            }
        }
        BwTeam last = null;
        int left = 0;
        for (BwTeamState ts : teams.values()) if (!ts.out) {
            left++;
            last = ts.team;
        }
        if (left <= 1) endMatch(last);
    }

    private static void endMatch(BwTeam winner) {
        if (state != State.PLAYING) return;
        state = State.ENDING;
        timer = 10 * 20;
        if (winner != null) teams.get(winner).place = 1;
        broadcast(winner == null ? "§7The match ended in a §ftie§7." : "§6§l" + winner.display.toUpperCase() + " TEAM WINS BEDWARS!");
        for (ServerPlayer p : onlineReal()) {
            BwPlayer bp = players.get(p.getUUID());
            boolean won = bp != null && bp.team == winner;
            Announce.title(p, won ? "§6§lVICTORY!" : "§c§lGAME OVER", winner == null ? "§7Tie" : winner.colored() + " §7won", 5, 70, 15);
        }
        // Bots learn which plan worked.
        Map<BwTeam, Integer> places = new EnumMap<>(BwTeam.class);
        for (BwTeamState ts : teams.values()) places.put(ts.team, ts.place == 0 ? 1 : ts.place);
        com.aidan.skyserver.bots.BwLearning.matchEnded(BwMap.id(), places, teamPlans(), teamsInMatch());
        StringBuilder res = new StringBuilder(winner == null ? "tie" : winner.display + " wins");
        for (BwTeamState ts : teams.values()) if (count(ts.team) > 0)
            res.append(", ").append(ts.team.display).append(" place ").append(ts.place == 0 ? 1 : ts.place)
                    .append(ts.botPlan >= 0 ? " (bot plan " + com.aidan.skyserver.bots.BwStrategy.Plan.values()[ts.botPlan].name().toLowerCase() + ")" : "");
        res.append(", match time ").append(clock((int) matchTicks));
        BwBotLog.end(res.toString());

        for (Map.Entry<UUID, BwPlayer> e : players.entrySet()) {
            BwPlayer bp = e.getValue();
            ServerPlayer p = online(e.getKey());
            if (p == null || bp.bot || !rewards) continue;
            if (!bp.active) {
                msg(p, "§7No match reward: you weren't active this match.");
                continue;
            }
            // 5.8: win 700 / loss 100, minus 15% for each other team that has bots (never below 25%).
            Set<BwTeam> botTeams = botTeamsExcept(bp.team);
            double scale = Rewards.bedwarsTeamScale(botTeams.size());
            String note = botTeams.isEmpty() ? "" : " §8(" + botTeams.size() + " team" + (botTeams.size() == 1 ? "" : "s") + " with bots: -" + Math.round((1 - scale) * 100) + "%)";
            Levels.addXp(server, p, Rewards.BW_PLAY_XP);
            Quests.progress(p, "bw_play", 1);
            PlayerStats.add(p, "bw_play", 1);
            if (bp.team == winner) {
                pay(p, Math.round(Rewards.BW_WIN * scale), "for winning" + note);
                Levels.addXp(server, p, Rewards.BW_WIN_XP);
                Quests.progress(p, "bw_win", 1);
                PlayerStats.add(p, "bw_win", 1);
                Skills.add(p, Skills.Skill.COMBAT, 300);
                if (Rewards.firstWinToday("bedwars", e.getKey())) {
                    pay(p, Rewards.BW_FIRST_WIN, "(first Bedwars win today)");
                    Keys.reward(server, e.getKey(), 0, 1, "(first Bedwars win today)");
                }
            } else {
                pay(p, Math.round(Rewards.BW_LOSS * scale), "for playing" + note);
            }
        }
        for (ServerPlayer p : onlineReal()) Rewards.matchEnd(p, "Bedwars");
        // Summary.
        BwPlayer topF = null, topK = null, topB = null;
        String nf = "", nk = "", nb = "";
        for (Map.Entry<UUID, BwPlayer> e : players.entrySet()) {
            ServerPlayer p = online(e.getKey());
            String name = p == null ? "?" : p.getName().getString();
            BwPlayer bp = e.getValue();
            if (topF == null || bp.finals > topF.finals) { topF = bp; nf = name; }
            if (topK == null || bp.kills > topK.kills) { topK = bp; nk = name; }
            if (topB == null || bp.beds > topB.beds) { topB = bp; nb = name; }
        }
        if (topF != null && topF.finals > 0) broadcast("§eMost final kills: §f" + nf + " §7(" + topF.finals + ")");
        if (topK != null && topK.kills > 0) broadcast("§eMost kills: §f" + nk + " §7(" + topK.kills + ")");
        if (topB != null && topB.beds > 0) broadcast("§eMost beds broken: §f" + nb + " §7(" + topB.beds + ")");
        broadcast("§7Returning to the lobby in 10 seconds...");
    }

    private static Map<BwTeam, Integer> teamPlans() {
        Map<BwTeam, Integer> m = new EnumMap<>(BwTeam.class);
        for (BwTeamState ts : teams.values()) if (ts.botPlan >= 0) m.put(ts.team, ts.botPlan);
        return m;
    }

    private static int teamsInMatch() {
        int n = 0;
        for (BwTeam t : BwTeam.values()) if (count(t) > 0) n++;
        return n;
    }

    private static void finish() {
        if (BwBotLog.isOpen()) BwBotLog.end("stopped at " + clock((int) matchTicks) + " (admin stop, or everyone left)");
        BwItems.reset();
        List<ServerPlayer> bots = new ArrayList<>();
        for (Map.Entry<UUID, BwPlayer> e : players.entrySet()) {
            ServerPlayer p = online(e.getKey());
            if (e.getValue().bot && p != null) bots.add(p);
        }
        restoreEveryone();
        for (ServerPlayer b : bots) BotManager.remove(b);
        MIX.clear();
        filled = false;
        waitStart = -1;
        state = State.WAITING;
        BwMap.next();
        rebuild();
    }

    private static void restoreEveryone() {
        if (server == null) return;
        for (Map.Entry<UUID, BwPlayer> e : new ArrayList<>(players.entrySet())) {
            ServerPlayer p = online(e.getKey());
            if (p == null) continue;
            removeFromTeam(p, e.getValue().team);
            p.setGlowingTag(false);
            e.getValue().snapshot.restore(p);
        }
        players.clear();
    }

    private static void rebuild() {
        ServerLevel level = level();
        if (level == null) {
            Skyserver.LOGGER.error("Can't build the Bedwars map: dimension skyserver:bedwars is missing.");
            return;
        }
        BwMap.build(level);
        placed.clear();
        mapReady = true;
    }

    // =====================================================================
    // Scoreboard teams (coloured names, no friendly fire)
    // =====================================================================

    private static PlayerTeam board(BwTeam t) {
        Scoreboard sb = server.getScoreboard();
        String name = "bw_" + t.name().toLowerCase(Locale.ROOT);
        PlayerTeam pt = sb.getPlayerTeam(name);
        if (pt == null) pt = sb.addPlayerTeam(name);
        pt.setColor(Optional.of(switch (t) {
            case RED -> TeamColor.RED;
            case BLUE -> TeamColor.BLUE;
            case GREEN -> TeamColor.GREEN;
            case YELLOW -> TeamColor.YELLOW;
        }));
        pt.setAllowFriendlyFire(false);
        pt.setSeeFriendlyInvisibles(true);
        pt.setPlayerPrefix(Component.literal(t.letter() + " "));
        return pt;
    }

    private static void addToTeam(ServerPlayer p, BwTeam t) {
        server.getScoreboard().addPlayerToTeam(p.getScoreboardName(), board(t));
    }

    private static void removeFromTeam(ServerPlayer p, BwTeam t) {
        PlayerTeam pt = server.getScoreboard().getPlayerTeam("bw_" + t.name().toLowerCase(Locale.ROOT));
        if (pt != null && pt.getPlayers().contains(p.getScoreboardName())) server.getScoreboard().removePlayerFromTeam(p.getScoreboardName(), pt);
    }

    /** Takes a player off the Bedwars name colours if they're not in a match (after a restart). */
    public static void clearStaleTeam(ServerPlayer p) {
        if (server == null || players.containsKey(p.getUUID())) return;
        for (BwTeam t : BwTeam.values()) removeFromTeam(p, t);
    }

    // =====================================================================
    // The sidebar and the line above the hotbar
    // =====================================================================

    /** Sidebar lines for someone in Bedwars (null = not in Bedwars). */
    public static List<String> sidebar(UUID id) {
        BwPlayer me = players.get(id);
        if (me == null) return null;
        List<String> l = new ArrayList<>();
        l.add("§7" + BwMap.display());
        if (state == State.PLAYING) {
            BwRules.Event next = null;
            for (BwRules.Event e : BwRules.Event.values()) if (e.tick() > matchTicks) { next = e; break; }
            if (next != null) l.add("§f" + next.display + " §7in §a" + clock((int) (next.tick() - matchTicks)));
            else l.add("§fMatch time §a" + clock((int) matchTicks));
        } else if (state == State.COUNTDOWN) {
            l.add("§fStarting in §a" + (timer / 20) + "s");
        } else {
            l.add("§fWaiting §7(" + players.size() + "/" + BwRules.maxPlayers() + ")");
        }
        l.add(" ");
        for (BwTeam t : BwTeam.values()) {
            BwTeamState ts = teams.get(t);
            String status;
            if (state != State.PLAYING && state != State.ENDING) status = "§7" + count(t) + "/" + BwRules.perTeam();
            else if (ts.out) status = "§c✘";
            else if (ts.bed) status = "§a✔";
            else {
                int alive = 0;
                for (BwPlayer bp : players.values()) if (bp.team == t && bp.alive()) alive++;
                status = "§e" + alive;
            }
            l.add(t.letter() + " §f" + t.display + ": " + status + (t == me.team ? " §7YOU" : ""));
        }
        l.add("  ");
        if (state == State.PLAYING || state == State.ENDING) {
            l.add("§fKills: §a" + me.kills + "  §fFinals: §a" + me.finals);
            l.add("§fBeds broken: §a" + me.beds);
            if (suddenDeath) l.add("§4Border: §c" + (borderHalf() * 2) + "§7 wide");
        } else {
            l.add("§fMode: §a" + BwRules.mode.text);
        }
        l.add("   ");
        l.add("§7/leave");
        return l;
    }

    private static void actionBars() {
        for (Map.Entry<UUID, BwPlayer> e : players.entrySet()) {
            ServerPlayer p = online(e.getKey());
            if (p == null || p instanceof BotPlayer) continue;
            BwPlayer bp = e.getValue();
            String text;
            if (state == State.WAITING) text = "§eWaiting for players §7(" + players.size() + "/" + BwRules.maxPlayers() + ")" + (autofill && BotManager.ENABLED && waitStart >= 0
                    ? "  §7Bots in " + clock((int) Math.max(0, BotManager.FILL_AFTER_SECONDS * 20L - (ticks - waitStart))) : "");
            else if (state == State.COUNTDOWN) text = "§eStarting in " + (timer / 20);
            else if (state == State.ENDING) text = "§6Game over";
            else if (bp.eliminated) text = "§7Spectating §8- §e/leave §7to go back";
            else if (bp.respawnAt >= 0) text = "§cRespawning in " + Math.max(1, (bp.respawnAt - ticks + 19) / 20);
            else {
                int[] res = BwShop.resources(p);
                text = "§f⬛ " + res[0] + "  §6⬛ " + res[1] + "  §b◆ " + res[2] + "  §2◆ " + res[3]
                        + (teams.get(bp.team).bed ? "   §aBed ✔" : "   §cBed ✘");
            }
            Announce.actionBar(p, text);
        }
    }

    // =====================================================================
    // Helpers
    // =====================================================================

    static void runNextTick(Runnable r) {
        nextTick.add(r);
    }

    static void pay(ServerPlayer p, long amount, String why) {
        if (!rewards || amount <= 0 || BotManager.isBot(p.getUUID())) return;
        Rewards.pay(server, p, amount, why);
    }

    static String clock(int ticksLeft) {
        int total = Math.max(0, (ticksLeft + 19) / 20);
        return String.format("%d:%02d", total / 60, total % 60);
    }

    static void resetVitals(ServerPlayer p) {
        p.setHealth(p.getMaxHealth());
        p.getFoodData().setFoodLevel(20);
        p.getFoodData().setSaturation(5f);
        p.removeAllEffects();
        p.setRemainingFireTicks(0);
        p.resetFallDistance();
    }

    static void msg(ServerPlayer p, String text) {
        if (p == null || p instanceof BotPlayer) return;
        p.sendSystemMessage(Component.literal(text));
    }

    static List<ServerPlayer> onlineReal() {
        List<ServerPlayer> list = new ArrayList<>();
        for (UUID id : players.keySet()) {
            ServerPlayer p = online(id);
            if (p != null && !(p instanceof BotPlayer)) list.add(p);
        }
        return list;
    }

    static List<ServerPlayer> teamOnline(BwTeam t) {
        List<ServerPlayer> list = new ArrayList<>();
        for (Map.Entry<UUID, BwPlayer> e : players.entrySet()) {
            if (e.getValue().team != t) continue;
            ServerPlayer p = online(e.getKey());
            if (p != null) list.add(p);
        }
        return list;
    }

    static List<ServerPlayer> teamAlive(BwTeam t) {
        List<ServerPlayer> list = new ArrayList<>();
        for (Map.Entry<UUID, BwPlayer> e : players.entrySet()) {
            if (e.getValue().team != t || !e.getValue().alive()) continue;
            ServerPlayer p = online(e.getKey());
            if (p != null) list.add(p);
        }
        return list;
    }

    static List<UUID> members(BwTeam t) {
        List<UUID> list = new ArrayList<>();
        for (Map.Entry<UUID, BwPlayer> e : players.entrySet()) if (e.getValue().team == t) list.add(e.getKey());
        return list;
    }

    static void broadcast(String text) {
        for (ServerPlayer p : onlineReal()) msg(p, "§c[Bedwars] §r" + text);
    }

    static void teamMsg(BwTeam t, String text) {
        for (ServerPlayer p : teamOnline(t)) msg(p, "§c[Bedwars] §r" + text);
    }

    private static void sound(net.minecraft.sounds.SoundEvent s, float pitch) {
        for (ServerPlayer p : onlineReal()) p.level().playSound(null, p.getX(), p.getY(), p.getZ(), s, SoundSource.MASTER, 0.8f, pitch);
    }
}
