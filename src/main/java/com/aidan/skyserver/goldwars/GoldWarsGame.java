package com.aidan.skyserver.goldwars;

import com.aidan.skyserver.Skyserver;
import com.aidan.skyserver.bots.BotManager;
import com.aidan.skyserver.bots.BotPlayer;
import com.aidan.skyserver.bots.Difficulty;
import com.aidan.skyserver.bots.GoldWarsBrain;
import com.aidan.skyserver.skywars.PlayerSnapshot;
import com.aidan.skyserver.skywars.SkywarsGame;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundSetActionBarTextPacket;
import net.minecraft.world.scores.TeamColor;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;

import java.util.*;

/** Gold Wars: two teams, each defending a 3x3x3 core of gold blocks. */
public final class GoldWarsGame {

    // ---- Settings you can change ----
    public static final int MAX_PER_TEAM = 6;
    public static final int MIN_PLAYERS = 2;
    public static final int COUNTDOWN_SECONDS = 30;
    public static final int FULL_COUNTDOWN_SECONDS = 10;
    public static final int BUILD_SECONDS = 150;
    public static final int FIGHT_MINUTES = 25;
    public static final int OVERTIME_MINUTES = 5;
    public static final int RESPAWN_SECONDS = 5;
    public static final int SPAWN_INVULN_SECONDS = 3;
    // Money and XP amounts are in lobby/Rewards.java (one place for the whole economy).
    public static final String PAY_COMMAND = "eco addmoney %s %d";
    public static final String SELECTOR_NAME = "Choose Class (right-click)";

    private enum State { WAITING, COUNTDOWN, BUILD, FIGHT, OVERTIME, ENDING }

    private static final class GwPlayer {
        PlayerSnapshot snapshot;
        final long joinOrder;
        final boolean bot;
        boolean offline = false;     // real player who disconnected mid-match (can rejoin)
        GwTeam team;
        GwClass cls = GwClass.KNIGHT;
        GwClass pending = null;      // class to switch to on next respawn
        int kills = 0;
        int goldBroken = 0;
        int deaths = 0;
        long respawnAt = -1;         // tick to respawn at, -1 = alive
        long invulnUntil = 0;
        UUID lastAttacker = null;
        long lastAttackTick = 0;
        int[] blocksGiven = new int[3]; // sandstone, planks, glass handed out this life (stops block farming by class switching)

        GwPlayer(PlayerSnapshot snapshot, long joinOrder, GwTeam team, boolean bot) {
            this.snapshot = snapshot;
            this.joinOrder = joinOrder;
            this.team = team;
            this.bot = bot;
        }

        GwClass effectiveClass() {
            return pending != null ? pending : cls;
        }
    }

    private static MinecraftServer server;

    /** Gold Wars has its own dimension (defined in resources/data/skyserver/dimension). */
    private static final ResourceKey<Level> ARENA = ResourceKey.create(
            Registries.DIMENSION, Identifier.fromNamespaceAndPath(Skyserver.MOD_ID, "arena"));
    private static State state = State.WAITING;
    private static final com.aidan.skyserver.lobby.MapVote VOTE = new com.aidan.skyserver.lobby.MapVote("gw",
            java.util.Arrays.stream(GoldWarsMap.Layout.values()).map(l -> l.id).toArray(String[]::new),
            java.util.Arrays.stream(GoldWarsMap.Layout.values()).map(l -> l.display).toArray(String[]::new));
    private static long ticks = 0;
    private static int timer = 0;           // ticks left in the current phase
    private static boolean mapReady = false;
    private static boolean rewards = false; // only pay out in real matches (both teams had players)
    private static boolean forcePay = false; // set by "/gw forcestart pay" for testing
    private static long joinCounter = 0;
    private static long waitStart = -1;      // tick the first real player started waiting (for bot fill)
    private static final Random RNG = new Random();

    private static final Map<UUID, GwPlayer> players = new LinkedHashMap<>();
    private static final Map<GwTeam, Set<BlockPos>> gold = new EnumMap<>(GwTeam.class);
    private static final List<Runnable> nextTick = new ArrayList<>();

    static {
        for (GwTeam t : GwTeam.values()) gold.put(t, new HashSet<>());
    }

    private GoldWarsGame() {}

    /** One line for the lobby portal and menu. */
    public static String lobbyStatus() {
        if (inMatch() || state == State.ENDING) return "§cMatch in progress §7(" + players.size() + " playing)";
        return "§a" + players.size() + "/" + (MAX_PER_TEAM * 2) + " waiting";
    }

    public static boolean isInGame(UUID id) {
        return players.containsKey(id);
    }

    // =====================================================================
    // Setup
    // =====================================================================

    public static void init() {
        ServerLifecycleEvents.SERVER_STARTED.register(s -> server = s);
        ServerLifecycleEvents.SERVER_STOPPING.register(s -> restoreEveryone());
        GoldWarsSkills.init();
        ServerTickEvents.END_SERVER_TICK.register(s -> com.aidan.skyserver.Safe.run("gold wars tick", () -> tick()));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, s) -> onDisconnect(handler.getPlayer()));
        ServerPlayConnectionEvents.JOIN.register((handler, sender, s) -> onReconnect(handler.getPlayer()));

        // Deaths never really happen: we respawn the player ourselves.
        ServerLivingEntityEvents.ALLOW_DEATH.register((entity, source, amount) -> {
            if (!(entity instanceof ServerPlayer player)) return true;
            GwPlayer gp = players.get(player.getUUID());
            if (gp == null) return true;
            player.setHealth(player.getMaxHealth());
            if (inMatch()) {
                UUID killer = source.getEntity() instanceof ServerPlayer k ? k.getUUID() : recentAttacker(gp);
                handleDeath(player, gp, killer);
            } else {
                sendToSpawn(player, gp);
            }
            return false;
        });

        // Friendly fire, spawn invulnerability, no PvP in the build phase.
        ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> {
            if (!(entity instanceof ServerPlayer victim)) return true;
            GwPlayer v = players.get(victim.getUUID());
            if (v == null) return true;
            if (!inMatch()) return false;
            if (v.respawnAt >= 0 || ticks < v.invulnUntil) return false;
            if (source.getEntity() instanceof ServerPlayer attacker && attacker != victim) {
                GwPlayer a = players.get(attacker.getUUID());
                if (a == null) return false;                 // outsiders can't hurt players
                if (a.team == v.team) return false;          // friendly fire off
                if (state == State.BUILD) return false;      // no PvP during build phase
                v.lastAttacker = attacker.getUUID();
                v.lastAttackTick = ticks;
            }
            return true;
        });

        // Breaking blocks: gold rules, spawn protection, middle wall, build limits.
        PlayerBlockBreakEvents.BEFORE.register((world, player, pos, blockState, blockEntity) -> {
            GwPlayer gp = players.get(player.getUUID());
            if (gp == null) return true;
            if (!inMatch() || gp.respawnAt >= 0) return false;
            ServerPlayer sp = (ServerPlayer) player;

            for (GwTeam t : GwTeam.values()) {
                if (gold.get(t).contains(pos)) {
                    if (state == State.BUILD) {
                        msg(sp, "§cGold can't be broken during the build phase.");
                        return false;
                    }
                    if (t == gp.team) {
                        msg(sp, "§cYou can't break your own team's gold!");
                        return false;
                    }
                    // Remove it ourselves so it drops nothing.
                    world.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                    onGoldBroken(sp, gp, t, pos);
                    return false;
                }
            }
            if (GoldWarsMap.inSpawnZone(pos)) {
                msg(sp, "§cSpawn areas are protected.");
                return false;
            }
            if (state == State.BUILD && GoldWarsMap.onWallLine(pos)) return false;
            if (!GoldWarsMap.inBuildArea(pos)) return false;
            // Remove the block ourselves so nothing drops.
            if (blockEntity instanceof Container container) container.clearContent();
            world.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            return false;
        });

        // Placing blocks: same protections.
        UseBlockCallback.EVENT.register((player, world, hand, hit) -> {
            GwPlayer gp = players.get(player.getUUID());
            if (gp == null) return InteractionResult.PASS;
            ItemStack held = player.getItemInHand(hand);
            if (!(held.getItem() instanceof BlockItem)) return InteractionResult.PASS;

            BlockPos clicked = hit.getBlockPos();
            BlockPos target = world.getBlockState(clicked).canBeReplaced()
                    ? clicked : clicked.relative(hit.getDirection());

            // TNT is never placed as a block: it's lit on the spot instead.
            if (held.is(Items.TNT) && player instanceof ServerPlayer tntPlayer) {
                if (gp.respawnAt < 0 && isFighting() && canEdit(target)) {
                    GoldWarsSkills.placeTnt(tntPlayer, target, held);
                } else if (state == State.BUILD) {
                    msg(tntPlayer, "§cExplosives only work once the fight starts.");
                }
                tntPlayer.connection.send(new ClientboundBlockUpdatePacket(world, target));
                tntPlayer.containerMenu.sendAllDataToRemote();
                return InteractionResult.FAIL;
            }
            boolean denied = !inMatch()
                    || gp.respawnAt >= 0
                    || GoldWarsMap.inSpawnZone(target)
                    || !GoldWarsMap.inBuildArea(target)
                    || (state == State.BUILD && GoldWarsMap.onWallLine(target))
                    || (state == State.BUILD && !GoldWarsMap.onPlatform(target));
            if (denied) {
                if (player instanceof ServerPlayer sp) {
                    // Undo the "ghost block" the client shows, and fix its item count.
                    sp.connection.send(new ClientboundBlockUpdatePacket(world, target));
                    sp.containerMenu.sendAllDataToRemote();
                }
                return InteractionResult.FAIL;
            }
            return InteractionResult.PASS;
        });

        // Right-click items: class selector, skills, fire staff.
        UseItemCallback.EVENT.register((player, world, hand) -> {
            if (!(player instanceof ServerPlayer sp) || !players.containsKey(sp.getUUID())) {
                return InteractionResult.PASS;
            }
            ItemStack held = player.getItemInHand(hand);
            if (isSelector(held)) {
                ClassMenu.open(sp);
                return InteractionResult.SUCCESS;
            }
            if (GoldWarsSkills.onUseItem(sp, held)) return InteractionResult.SUCCESS;
            return InteractionResult.PASS;
        });

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> dispatcher.register(
                Commands.literal("gw")
                        .then(Commands.literal("join").executes(c -> {
                            join(c.getSource().getPlayerOrException());
                            return 1;
                        }))
                        .then(Commands.literal("leave").executes(c -> {
                            leave(c.getSource().getPlayerOrException(), true);
                            return 1;
                        }))
                        .then(Commands.literal("vote").then(Commands.argument("map", com.mojang.brigadier.arguments.StringArgumentType.word()).executes(c -> {
                            ServerPlayer p = c.getSource().getPlayerOrException();
                            if (!players.containsKey(p.getUUID()) || (state != State.WAITING && state != State.COUNTDOWN)) {
                                msg(p, "§cYou can vote for the map while waiting for a Gold Wars match.");
                                return 0;
                            }
                            String r = VOTE.vote(p, com.mojang.brigadier.arguments.StringArgumentType.getString(c, "map"));
                            if (r != null) broadcast(r);
                            return 1;
                        })))
                        .then(classCommand())
                        .then(Commands.literal("forcestart").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                                .executes(c -> forceStart(c.getSource(), false))
                                // "/gw forcestart pay" pays out even in a solo test match.
                                .then(Commands.literal("pay").executes(c -> forceStart(c.getSource(), true))))
                        .then(Commands.literal("skip").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS)).executes(c -> {
                            // Testing helper: jump to the end of the current phase.
                            switch (state) {
                                case COUNTDOWN -> start();
                                case BUILD, FIGHT, OVERTIME -> timer = 1;
                                default -> {
                                    c.getSource().sendFailure(Component.literal("Nothing to skip."));
                                    return 0;
                                }
                            }
                            return 1;
                        }))
                        .then(Commands.literal("stop").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS)).executes(c -> {
                            broadcast("§cThe match was stopped by an admin.");
                            finish();
                            c.getSource().sendSuccess(() -> Component.literal("Gold Wars stopped and reset."), true);
                            return 1;
                        }))
                        .then(mapCommand())
                        .then(Commands.literal("fillbots").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS)).executes(c -> {
                            // Testing helper: fill the empty slots with bots right now.
                            if ((state != State.WAITING && state != State.COUNTDOWN) || realCount() == 0) {
                                c.getSource().sendFailure(Component.literal("Join first, and only before a match starts."));
                                return 0;
                            }
                            fillBots();
                            return 1;
                        }))
                        .then(Commands.literal("rebuild").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS)).executes(c -> {
                            if (state != State.WAITING || !players.isEmpty()) {
                                c.getSource().sendFailure(Component.literal("Stop the match first with /gw stop."));
                                return 0;
                            }
                            rebuildMap();
                            c.getSource().sendSuccess(() -> Component.literal("Gold Wars map rebuilt."), true);
                            return 1;
                        }))
        ));

        Skyserver.LOGGER.info("Gold Wars ready. Players join with /gw join");
    }

    private static int forceStart(CommandSourceStack source, boolean pay) {
        if (players.isEmpty()) {
            source.sendFailure(Component.literal("Nobody has joined yet."));
            return 0;
        }
        if (state != State.WAITING && state != State.COUNTDOWN) {
            source.sendFailure(Component.literal("A match is already running."));
            return 0;
        }
        forcePay = pay;
        start();
        return 1;
    }

    /** /gw map sky|village|caves (admins): switch maps while nobody is playing. */
    private static LiteralArgumentBuilder<CommandSourceStack> mapCommand() {
        LiteralArgumentBuilder<CommandSourceStack> cmd = Commands.literal("map")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .executes(c -> {
                    c.getSource().sendSuccess(() -> Component.literal(
                            "Current map: " + GoldWarsMap.current().display + ". Use /gw map sky|village|caves"), false);
                    return 1;
                });
        for (GoldWarsMap.Layout layout : GoldWarsMap.Layout.values()) {
            cmd.then(Commands.literal(layout.id).executes(c -> {
                if (state != State.WAITING || !players.isEmpty()) {
                    c.getSource().sendFailure(Component.literal("Everyone must leave first (or use /gw stop)."));
                    return 0;
                }
                GoldWarsMap.setLayout(layout);
                rebuildMap();
                c.getSource().sendSuccess(() -> Component.literal("Gold Wars map set to " + layout.display + "."), true);
                return 1;
            }));
        }
        return cmd;
    }

    /** /gw class opens the menu; /gw class knight etc. picks directly. */
    private static LiteralArgumentBuilder<CommandSourceStack> classCommand() {
        LiteralArgumentBuilder<CommandSourceStack> cmd = Commands.literal("class").executes(c -> {
            ServerPlayer p = c.getSource().getPlayerOrException();
            if (!players.containsKey(p.getUUID())) {
                msg(p, "§cJoin first with /gw.");
                return 0;
            }
            ClassMenu.open(p);
            return 1;
        });
        for (GwClass cls : GwClass.values()) {
            cmd.then(Commands.literal(cls.id).executes(c -> {
                chooseClass(c.getSource().getPlayerOrException(), cls);
                return 1;
            }));
        }
        return cmd;
    }

    // =====================================================================
    // Joining, leaving, classes
    // =====================================================================

    public static void join(ServerPlayer player) {
        UUID id = player.getUUID();
        if (players.containsKey(id)) {
            msg(player, "§eYou're already in Gold Wars. Use /leave to quit.");
            return;
        }
        if (SkywarsGame.isInGame(id)) {
            msg(player, "§cLeave Skywars first with /leave.");
            return;
        }
        if (com.aidan.skyserver.bedwars.BedwarsGame.isInGame(id)) {
            msg(player, "§cLeave Bedwars first with /leave.");
            return;
        }
        if (com.aidan.skyserver.lobby.Lobby.level() != null && !com.aidan.skyserver.lobby.Lobby.isLobby(player.level())) {
            msg(player, "§cJoin Gold Wars from the lobby (§e/lobby§c).");
            return;
        }
        if (state == State.ENDING) {
            msg(player, "§cA match is just ending. Try again in a few seconds.");
            return;
        }
        if (inMatch()) {
            msg(player, "§cA match is in progress. You can join the next one.");
            return;
        }
        if (arena() == null) {
            msg(player, "§cThe Gold Wars arena dimension didn't load. Check the server log for errors.");
            Skyserver.LOGGER.error("Dimension skyserver:arena is missing. Is data/skyserver/dimension/arena.json in the mod's resources?");
            return;
        }
        // Teams are picked by real players; a bot steps aside if the team is full of bots.
        GwTeam team = realCount(GwTeam.RED) <= realCount(GwTeam.BLUE) ? GwTeam.RED : GwTeam.BLUE;
        // Party members go on the same team as their party (when there's room).
        for (UUID mate : com.aidan.skyserver.lobby.Parties.membersOf(id)) {
            GwPlayer mp = players.get(mate);
            if (mp != null && realCount(mp.team) < MAX_PER_TEAM) {
                team = mp.team;
                break;
            }
        }
        if (realCount(team) >= MAX_PER_TEAM) team = team.other();
        if (realCount(team) >= MAX_PER_TEAM) {
            msg(player, "§cGold Wars is full.");
            return;
        }
        if (!mapReady) rebuildMap();

        GwPlayer gp = new GwPlayer(new PlayerSnapshot(player), joinCounter++, team, false);
        players.put(id, gp);
        addToScoreboardTeam(player, team);
        if (botCount() > 0) reconcileBots(); // a bot leaves to make room

        sendToSpawn(player, gp);
        broadcast("§a" + player.getName().getString() + " joined " + team.colored()
                + " §a(" + players.size() + "/" + (MAX_PER_TEAM * 2) + ")");
        msg(player, "§eMap: §f" + GoldWarsMap.current().display
                + "§e. Right-click the nether star or use §f/class §e(or §f/builder§e, §f/knight§e, §f/archer§e...) to choose your class.");
        if (state == State.WAITING || state == State.COUNTDOWN) VOTE.offer(player, GoldWarsMap.current().ordinal());
        com.aidan.skyserver.lobby.Parties.followLeader(player, "goldwars");

        if (!BotManager.ENABLED && state == State.WAITING && players.size() >= MIN_PLAYERS) {
            state = State.COUNTDOWN;
            timer = COUNTDOWN_SECONDS * 20;
            broadcast("§eMatch starting in " + COUNTDOWN_SECONDS + " seconds!");
        }
        if (BotManager.ENABLED && state == State.WAITING && botCount() == 0) {
            int left = (int) Math.max(0, (BotManager.FILL_AFTER_SECONDS * 20L - (waitStart < 0 ? 0 : ticks - waitStart)) / 20);
            msg(player, "§7If the teams aren't full in " + clock(left * 20) + ", bots will fill the empty slots.");
        }
        if (state == State.WAITING && players.size() >= MAX_PER_TEAM * 2) {
            state = State.COUNTDOWN;
            timer = FULL_COUNTDOWN_SECONDS * 20;
        }
        if (state == State.COUNTDOWN && players.size() >= MAX_PER_TEAM * 2 && timer > FULL_COUNTDOWN_SECONDS * 20) {
            timer = FULL_COUNTDOWN_SECONDS * 20;
            broadcast("§eTeams are full! Starting in " + FULL_COUNTDOWN_SECONDS + " seconds!");
        }
    }

    private static void leave(ServerPlayer player, boolean voluntary) {
        leave(player, voluntary, false);
    }

    private static void leave(ServerPlayer player, boolean voluntary, boolean disconnecting) {
        UUID id = player.getUUID();
        GwPlayer gp = players.remove(id);
        VOTE.remove(id);
        if (gp == null) {
            if (voluntary) msg(player, "§eYou're not in Gold Wars.");
            return;
        }
        removeFromScoreboardTeam(player, gp.team);
        GoldWarsSkills.cleanup(player);
        com.aidan.skyserver.lobby.Rewards.matchEnd(player, "Gold Wars");
        if (!disconnecting) gp.snapshot.restore(player);
        if (voluntary) msg(player, "§eYou left Gold Wars.");
        if (!gp.bot) broadcast("§7" + player.getName().getString() + " left Gold Wars.");
        if (gp.bot) return; // bots only leave when the game removes them

        if (players.isEmpty()) {
            if (state != State.WAITING) finish();
            return;
        }
        if (!gp.bot && onlineRealCount() == 0) {
            // Only bots left: end the match, or empty the lobby.
            if (inMatch() || state == State.ENDING) {
                finish();
            } else {
                removeAllBots();
                state = State.WAITING;
                waitStart = -1;
            }
            return;
        }
        if (!gp.bot && !inMatch() && state != State.ENDING && botCount() > 0) reconcileBots(); // a bot takes the slot
        if (state == State.COUNTDOWN && players.size() < MIN_PLAYERS) {
            state = State.WAITING;
            broadcast("§cNot enough players. Countdown cancelled.");
        }
        if (inMatch() && count(gp.team) == 0) {
            broadcast(gp.team.colored() + " §chas no players left!");
            endMatch(gp.team.other());
        }
    }

    /** A real player disconnected. Mid-match they keep their slot and can rejoin. */
    private static void onDisconnect(ServerPlayer player) {
        GwPlayer gp = players.get(player.getUUID());
        if (gp == null) return;
        if (gp.bot || !inMatch()) {
            leave(player, false, true);
            return;
        }
        gp.offline = true;
        gp.respawnAt = -1;
        removeFromScoreboardTeam(player, gp.team);
        GoldWarsSkills.cleanup(player);
        // (No teleport while disconnecting: if the match is over when they log back in, the lobby takes them.)
        broadcast("§7" + player.getName().getString() + " disconnected. They can rejoin this match.");
        if (onlineRealCount() == 0) finish();
    }

    /** A player logged back in: put them back into the match they left. */
    private static void onReconnect(ServerPlayer player) {
        GwPlayer gp = players.get(player.getUUID());
        if (gp == null || !gp.offline) return;
        if (!inMatch()) {
            players.remove(player.getUUID());
            return;
        }
        runNextTick(() -> {
            if (!inMatch() || players.get(player.getUUID()) != gp || player.isRemoved()) return;
            gp.offline = false;
            gp.snapshot = new PlayerSnapshot(player);
            addToScoreboardTeam(player, gp.team);
            spawnPlayer(player, gp);
            broadcast(gp.team.colored() + " §a" + player.getName().getString() + " rejoined the match!");
        });
    }

    // =====================================================================
    // Bots
    // =====================================================================

    private static int realCount() {
        int n = 0;
        for (GwPlayer gp : players.values()) if (!gp.bot) n++;
        return n;
    }

    private static int realCount(GwTeam team) {
        int n = 0;
        for (GwPlayer gp : players.values()) if (!gp.bot && gp.team == team) n++;
        return n;
    }

    private static int onlineRealCount() {
        int n = 0;
        for (GwPlayer gp : players.values()) if (!gp.bot && !gp.offline) n++;
        return n;
    }

    private static int botCount() {
        int n = 0;
        for (GwPlayer gp : players.values()) if (gp.bot) n++;
        return n;
    }

    private static int botCount(GwTeam team) {
        int n = 0;
        for (GwPlayer gp : players.values()) if (gp.bot && gp.team == team) n++;
        return n;
    }

    private static int botCount(GwTeam team, Difficulty d) {
        int n = 0;
        for (Map.Entry<UUID, GwPlayer> e : players.entrySet()) {
            if (!e.getValue().bot || e.getValue().team != team) continue;
            if (server.getPlayerList().getPlayer(e.getKey()) instanceof BotPlayer b && b.difficulty() == d) n++;
        }
        return n;
    }

    /** 3 minutes are up (or an admin used /gw fillbots): fill both teams with bots and start. */
    private static void fillBots() {
        rebalanceTeams();
        mix.clear();
        mix.addAll(Difficulty.randomMix(MAX_PER_TEAM, RNG));
        reconcileBots();
        state = State.COUNTDOWN;
        timer = FULL_COUNTDOWN_SECONDS * 20;
        broadcast("§eBots have filled the empty slots! Starting in " + FULL_COUNTDOWN_SECONDS + " seconds.");
    }

    private static final List<Difficulty> mix = new ArrayList<>();

    /**
     * Makes both teams full (6) with bots. Both teams get the same mix of bot difficulties;
     * the team with fewer real players gets Hard bots to make up the difference.
     */
    private static void reconcileBots() {
        int red = realCount(GwTeam.RED), blue = realCount(GwTeam.BLUE);
        if (red + blue == 0) {
            removeAllBots();
            return;
        }
        for (GwTeam t : GwTeam.values()) {
            while (count(t) > MAX_PER_TEAM && botCount(t) > 0) removeBot(botToRemove(t));
        }
        GwTeam big = red >= blue ? GwTeam.RED : GwTeam.BLUE;
        for (GwTeam t : new GwTeam[]{big, big.other()}) {
            while (count(t) < MAX_PER_TEAM) {
                if (!addBot(t, difficultyToAdd(t))) return;
            }
        }
    }

    private static Difficulty difficultyToAdd(GwTeam t) {
        // Mirror the other team's bots first.
        Difficulty best = null;
        int bestNeed = 0;
        for (Difficulty d : Difficulty.values()) {
            int need = botCount(t.other(), d) - botCount(t, d);
            if (need > bestNeed) {
                bestNeed = need;
                best = d;
            }
        }
        if (best != null) return best;
        // Fewer real players than the other team: a Hard bot evens it out.
        if (realCount(t) < realCount(t.other())) return Difficulty.HARD;
        if (mix.isEmpty()) mix.addAll(Difficulty.randomMix(MAX_PER_TEAM, RNG));
        return mix.remove(0);
    }

    private static ServerPlayer botToRemove(GwTeam t) {
        ServerPlayer best = null;
        int bestExcess = Integer.MIN_VALUE;
        for (Map.Entry<UUID, GwPlayer> e : players.entrySet()) {
            if (!e.getValue().bot || e.getValue().team != t) continue;
            if (!(server.getPlayerList().getPlayer(e.getKey()) instanceof BotPlayer b)) continue;
            int excess = botCount(t, b.difficulty()) - botCount(t.other(), b.difficulty()) * 2
                    + (b.difficulty() == Difficulty.HARD ? 1 : 0);
            if (excess > bestExcess) {
                bestExcess = excess;
                best = b;
            }
        }
        return best;
    }

    private static boolean addBot(GwTeam team, Difficulty difficulty) {
        BotPlayer bot;
        try {
            bot = BotManager.spawn(difficulty);
        } catch (RuntimeException e) {
            Skyserver.LOGGER.error("Gold Wars: couldn't create a bot", e);
            return false;
        }
        GwPlayer gp = new GwPlayer(new PlayerSnapshot(bot), joinCounter++, team, true);
        players.put(bot.getUUID(), gp);
        addToScoreboardTeam(bot, team);
        gp.cls = botClass(gp);
        bot.setBrain(new GoldWarsBrain(bot));
        sendToSpawn(bot, gp);
        return true; // no chat line per bot: one "bots have filled" message is shown instead
    }

    private static void removeBot(ServerPlayer bot) {
        if (bot == null) return;
        GwPlayer gp = players.remove(bot.getUUID());
        if (gp != null) removeFromScoreboardTeam(bot, gp.team);
        BotManager.remove(bot);
    }

    private static void removeAllBots() {
        for (Map.Entry<UUID, GwPlayer> e : new ArrayList<>(players.entrySet())) {
            if (!e.getValue().bot) continue;
            ServerPlayer p = server.getPlayerList().getPlayer(e.getKey());
            players.remove(e.getKey());
            if (p != null) {
                removeFromScoreboardTeam(p, e.getValue().team);
                BotManager.remove(p);
            }
        }
    }

    private static final net.minecraft.world.item.Item[] KIT_BLOCKS = {Items.SANDSTONE, Items.OAK_PLANKS, Items.GLASS};

    /**
     * Build phase class change: new kit on the spot. Building blocks carry over instead of
     * refilling: you get the new class's extra blocks only once per life, and switching to a
     * class with fewer blocks caps you at its amount.
     */
    private static void switchClassNow(ServerPlayer player, GwPlayer gp, GwClass cls) {
        int[] held = new int[3];
        var inv = player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack st = inv.getItem(i);
            for (int b = 0; b < 3; b++) if (st.is(KIT_BLOCKS[b])) held[b] += st.getCount();
        }
        int allot = cls.blockCount();
        gp.cls = cls;
        gp.pending = null;
        cls.giveKit(player, gp.team);
        for (int b = 0; b < 3; b++) {
            int extra = Math.max(0, allot - gp.blocksGiven[b]);
            gp.blocksGiven[b] = Math.max(gp.blocksGiven[b], allot);
            int count = Math.min(held[b] + extra, allot);
            inv.setItem(3 + b, count > 0 ? new ItemStack(KIT_BLOCKS[b], count) : ItemStack.EMPTY);
        }
        player.removeAllEffects();
        GoldWarsSkills.onSpawn(player, cls);
    }

    /** Real players get priority for limited classes: a bot teammate switches class. */
    private static boolean bumpBotFromClass(GwTeam team, GwClass cls) {
        for (GwPlayer b : players.values()) {
            if (!b.bot || b.team != team || b.effectiveClass() != cls) continue;
            GwClass other = botClass(b, cls);
            if (inMatch()) {
                b.pending = other;
            } else {
                b.cls = other;
            }
            return true;
        }
        return false;
    }

    private static GwClass botClass(GwPlayer gp) {
        return botClass(gp, null);
    }

    /** Bots pick a random class (respecting the 1-per-team limits). */
    private static GwClass botClass(GwPlayer gp, GwClass exclude) {
        Map<GwClass, Integer> weights = new LinkedHashMap<>();
        weights.put(GwClass.BUILDER, 3);
        weights.put(GwClass.KNIGHT, 3);
        weights.put(GwClass.ARCHER, 2);
        weights.put(GwClass.SCOUT, 2);
        weights.put(GwClass.MAGE, 1);
        weights.put(GwClass.LONGBOW, 1);
        weights.keySet().removeIf(c -> c == exclude || classFull(gp, c));
        int total = 0;
        for (int w : weights.values()) total += w;
        int roll = RNG.nextInt(Math.max(1, total));
        for (Map.Entry<GwClass, Integer> e : weights.entrySet()) {
            roll -= e.getValue();
            if (roll < 0) return e.getKey();
        }
        return GwClass.KNIGHT;
    }

    /** Returns true if the class was accepted. */
    static boolean chooseClass(ServerPlayer player, GwClass cls) {
        GwPlayer gp = players.get(player.getUUID());
        if (gp == null) {
            msg(player, "§cJoin first with /gw.");
            return false;
        }
        if (classFull(gp, cls) && !(!gp.bot && bumpBotFromClass(gp.team, cls))) {
            msg(player, "§cYour team already has the max number of " + cls.display + "s.");
            return false;
        }
        if (state == State.BUILD && gp.respawnAt < 0 && !gp.offline) {
            // Build phase: switch right away, no respawn needed.
            switchClassNow(player, gp, cls);
            msg(player, "§aYou are now a §f" + cls.display + "§a.");
        } else if (inMatch()) {
            gp.pending = cls == gp.cls ? null : cls;
            msg(player, "§aYou'll respawn as a §f" + cls.display + "§a.");
        } else {
            gp.cls = cls;
            gp.pending = null;
            msg(player, "§aYou are now a §f" + cls.display + "§a.");
        }
        return true;
    }

    private static boolean classFull(GwPlayer gp, GwClass cls) {
        if (cls.limit <= 0) return false;
        int taken = 0;
        for (GwPlayer other : players.values()) {
            if (other != gp && other.team == gp.team && other.effectiveClass() == cls) taken++;
        }
        return taken >= cls.limit;
    }

    static ItemStack classIcon(ServerPlayer player, GwClass cls) {
        GwPlayer gp = players.get(player.getUUID());
        ItemStack stack = new ItemStack(cls.icon);
        String name;
        if (gp != null && classFull(gp, cls)) {
            name = "§c" + cls.display + " §7(full)";
        } else {
            name = "§a" + cls.display + " §7- " + cls.description;
        }
        if (gp != null && gp.effectiveClass() == cls) name += " §e(selected)";
        stack.set(DataComponents.CUSTOM_NAME, Component.literal(name));
        return stack;
    }

    public static ItemStack classSelector() {
        ItemStack stack = new ItemStack(Items.NETHER_STAR);
        stack.set(DataComponents.CUSTOM_NAME, Component.literal("§e" + SELECTOR_NAME));
        return stack;
    }

    private static boolean isSelector(ItemStack stack) {
        if (!stack.is(Items.NETHER_STAR)) return false;
        Component name = stack.get(DataComponents.CUSTOM_NAME);
        return name != null && name.getString().contains(SELECTOR_NAME);
    }

    // =====================================================================
    // Game loop
    // =====================================================================

    private static void tick() {
        ticks++;
        if (server == null) return;
        if (ticks % 20 == 0 && com.aidan.skyserver.bots.BotRecorder.recording()) recordUnits();
        if (ticks % 40 == 13 && (state == State.BUILD || state == State.FIGHT || state == State.OVERTIME)) fitBotClasses();
        if (ticks % 10 == 3 && (state == State.FIGHT || state == State.OVERTIME)) stripStrayArrows();

        if (!nextTick.isEmpty()) {
            List<Runnable> jobs = new ArrayList<>(nextTick);
            nextTick.clear();
            jobs.forEach(Runnable::run);
        }
        // Clear out monsters that spawn on the map (checked every second).
        if (ticks % 20 == 0 && arena() != null) {
            for (Entity e : arena().getEntitiesOfClass(Entity.class, GoldWarsMap.box(),
                    e -> e.getType().getCategory() == MobCategory.MONSTER)) {
                e.discard();
            }
        }
        if (players.isEmpty()) {
            waitStart = -1;
            return;
        }
        tickBotFill();

        // Void deaths and respawns.
        List<ServerPlayer> fell = new ArrayList<>();
        List<ServerPlayer> ready = new ArrayList<>();
        for (Map.Entry<UUID, GwPlayer> e : players.entrySet()) {
            ServerPlayer p = server.getPlayerList().getPlayer(e.getKey());
            if (p == null) continue;
            GwPlayer gp = e.getValue();
            if (gp.respawnAt >= 0) {
                if (ticks >= gp.respawnAt) ready.add(p);
            } else if (p.getY() < GoldWarsMap.VOID_Y) {
                fell.add(p);
            }
        }
        for (ServerPlayer p : fell) {
            GwPlayer gp = players.get(p.getUUID());
            if (state == State.FIGHT || state == State.OVERTIME) {
                handleDeath(p, gp, recentAttacker(gp));
            } else if (state == State.BUILD) {
                // Safety net: no deaths in the build phase, just go back to spawn.
                BlockPos s = GoldWarsMap.spawn(gp.team);
                p.teleportTo(arena(), s.getX() + 0.5, s.getY(), s.getZ() + 0.5,
                        Set.of(), GoldWarsMap.spawnYaw(gp.team), 0f, true);
                p.resetFallDistance();
            } else {
                sendToSpawn(p, gp);
            }
        }
        for (ServerPlayer p : ready) respawn(p, players.get(p.getUUID()));
        if (inMatch()) GoldWarsSkills.tick();

        switch (state) {
            case COUNTDOWN -> {
                timer--;
                if (timer % 20 == 0) {
                    int sec = timer / 20;
                    if (com.aidan.skyserver.lobby.Announce.countdown(onlinePlayers(), sec, "§e", "§7Gold Wars is starting")) broadcast("§eStarting in " + sec + " seconds.");
                }
                if (timer <= 0) start();
            }
            case BUILD -> {
                timer--;
                if (timer % 20 == 0) {
                    int sec = timer / 20;
                    boolean chat = com.aidan.skyserver.lobby.Announce.countdown(onlinePlayers(), sec, "§c", "§7The wall drops!");
                    if (chat || sec == 60) broadcast("§eThe wall drops in " + sec + " seconds!");
                }
                if (timer <= 0) beginFight();
            }
            case FIGHT -> {
                timer--;
                if (timer == 10 * 60 * 20) broadcast("§e10 minutes left!");
                if (timer == 5 * 60 * 20) broadcast("§e5 minutes left!");
                if (timer == 60 * 20) broadcast("§c1 minute left!");
                if (timer <= 0) fightOver();
            }
            case OVERTIME -> {
                timer--;
                if (timer == 60 * 20) broadcast("§c1 minute of overtime left!");
                if (timer <= 0) {
                    broadcast("§7Overtime is over. The match is a §ftie§7.");
                    endMatch(null);
                }
            }
            case ENDING -> {
                timer--;
                if (timer <= 0) finish();
            }
            default -> {}
        }

        if (ticks % 20 == 0) showStatus();
    }

    /** After 3 minutes of waiting with at least one real player, bots fill the empty slots. */
    private static void tickBotFill() {
        if (!BotManager.ENABLED || (state != State.WAITING && state != State.COUNTDOWN)) return;
        if (realCount() == 0) {
            waitStart = -1;
            if (botCount() > 0) removeAllBots();
            if (state == State.COUNTDOWN) state = State.WAITING;
            return;
        }
        if (botCount() > 0 || players.size() >= MAX_PER_TEAM * 2) return;
        if (waitStart < 0) waitStart = ticks;
        long left = BotManager.FILL_AFTER_SECONDS * 20L - (ticks - waitStart);
        if (left == 60 * 20 || left == 30 * 20 || left == 10 * 20) {
            broadcast("§7Bots will fill the empty slots in " + (left / 20) + " seconds.");
        }
        if (left <= 0) fillBots();
    }

    private static void start() {
        // Map vote: switch maps if another one won (everyone is teleported to their spawn below anyway).
        int voted = VOTE.winner(GoldWarsMap.current().ordinal());
        if (voted != GoldWarsMap.current().ordinal()) {
            GoldWarsMap.setLayout(GoldWarsMap.Layout.values()[voted]);
            rebuildMap();
            com.aidan.skyserver.bots.GoldWarsCommander.mapChanged();
            broadcast("§eThe vote picked §f" + GoldWarsMap.current().display + "§e!");
        }
        rebalanceTeams();
        resetGold();
        com.aidan.skyserver.bots.BotRecorder.start(GoldWarsMap.current().display);
        rewards = forcePay || (count(GwTeam.RED) > 0 && count(GwTeam.BLUE) > 0);
        forcePay = false;
        state = State.BUILD;
        timer = BUILD_SECONDS * 20;

        for (Map.Entry<UUID, GwPlayer> e : players.entrySet()) {
            ServerPlayer p = server.getPlayerList().getPlayer(e.getKey());
            if (p != null) spawnPlayer(p, e.getValue());
            if (p != null && rewards) com.aidan.skyserver.lobby.Rewards.matchStart(p);
        }
        broadcast("§6§lBUILD PHASE! §r§eMap: §f" + GoldWarsMap.current().display
                + "§e. You have " + BUILD_SECONDS + " seconds to defend your gold.");
        if (!rewards) broadcast("§7(Test match: one team is empty, so no money is paid out. Use /gw forcestart pay to test payouts.)");
    }

    private static void beginFight() {
        GoldWarsMap.setWall(arena(), false);
        state = State.FIGHT;
        timer = FIGHT_MINUTES * 60 * 20;
        broadcast("§c§lFIGHT! §r§cThe wall is down. Break the enemy's gold!");
        com.aidan.skyserver.lobby.Announce.go(onlinePlayers(), "§c§lFIGHT!", "§7Break the enemy's gold");
    }

    /** 25 minutes are up: most gold left wins, otherwise overtime. */
    private static void fightOver() {
        int red = gold.get(GwTeam.RED).size();
        int blue = gold.get(GwTeam.BLUE).size();
        if (red == blue) {
            state = State.OVERTIME;
            timer = OVERTIME_MINUTES * 60 * 20;
            broadcast("§6§lOVERTIME! §r§eIt's tied " + red + "-" + blue
                    + ". The first team to break a gold block wins!");
        } else {
            GwTeam winner = red > blue ? GwTeam.RED : GwTeam.BLUE;
            broadcast("§eTime's up! " + winner.colored() + " §ehas more gold left (" + red + " vs " + blue + ").");
            endMatch(winner);
        }
    }

    private static void onGoldBroken(ServerPlayer breaker, GwPlayer gp, GwTeam owner, BlockPos pos) {
        gold.get(owner).remove(pos);
        gp.goldBroken++;
        int left = gold.get(owner).size();
        com.aidan.skyserver.bots.BotRecorder.event(breaker.getName().getString() + " broke " + owner.name() + " gold at " + pos.toShortString() + " (" + left + " left)");
        pay(breaker, com.aidan.skyserver.lobby.Rewards.GW_GOLD, "for breaking a gold block");
        if (rewards) com.aidan.skyserver.lobby.Levels.addXp(server, breaker, com.aidan.skyserver.lobby.Rewards.GW_GOLD_XP);
        if (rewards) com.aidan.skyserver.lobby.Quests.progress(breaker, "gw_gold", 1);
        if (rewards) com.aidan.skyserver.lobby.PlayerStats.add(breaker, "gw_gold", 1);
        if (rewards) com.aidan.skyserver.lobby.Skills.add(breaker, com.aidan.skyserver.lobby.Skills.Skill.COMBAT, 40);
        if (breaker instanceof BotPlayer b && b.brain() instanceof GoldWarsBrain brain) brain.onGoldBroken();
        else if (!(breaker instanceof BotPlayer)) com.aidan.skyserver.bots.GwLearning.humanBrokeGold(GoldWarsMap.current().id, pos.getZ() < 0 ? 0 : 1);

        broadcast(gp.team.colored() + " §f" + breaker.getName().getString() + " §ebroke a "
                + owner.colored() + " §egold block! §7(" + left + " left)");
        teamMessage(owner, "§c§lYOUR GOLD IS UNDER ATTACK!");

        if (state == State.OVERTIME || left == 0) endMatch(gp.team);
    }

    /** Arrows only belong to Archers and Longbows: anyone else who picks some up (e.g. dropped by a dead Archer) loses them. */
    private static void stripStrayArrows() {
        for (Map.Entry<UUID, GwPlayer> e : players.entrySet()) {
            GwPlayer gp = e.getValue();
            if (gp.cls == GwClass.ARCHER || gp.cls == GwClass.LONGBOW) continue;
            ServerPlayer p = server.getPlayerList().getPlayer(e.getKey());
            if (p == null) continue;
            var inv = p.getInventory();
            for (int i = 0; i < inv.getContainerSize(); i++) if (inv.getItem(i).is(Items.ARROW)) inv.setItem(i, ItemStack.EMPTY);
        }
    }

    // ---- Bots take the class their job needs ----
    // Gold-room defenders are Knights; flank/middle hunters are Archer, Mage or Longbow; attack
    // "miners" are Scouts or Builders and their escorts fighters. Inside a category the class is
    // picked by what has been winning (learned per category), never a class that's full.

    private static final Map<String, GwClass[]> CLASS_CATS = Map.of(
            "knight", new GwClass[]{GwClass.KNIGHT},
            "ranged", new GwClass[]{GwClass.ARCHER, GwClass.MAGE, GwClass.LONGBOW},
            "miner", new GwClass[]{GwClass.BUILDER, GwClass.SCOUT},
            "fighter", new GwClass[]{GwClass.KNIGHT, GwClass.ARCHER, GwClass.MAGE, GwClass.LONGBOW});
    private static final Map<UUID, String> BOT_CAT = new HashMap<>();

    private static void fitBotClasses() {
        for (Map.Entry<UUID, GwPlayer> e : players.entrySet()) {
            GwPlayer gp = e.getValue();
            if (!gp.bot) continue;
            String want = com.aidan.skyserver.bots.GoldWarsCommander.of(gp.team).wantedClass(e.getKey());
            GwClass[] cat = CLASS_CATS.get(want);
            if (cat == null) continue;
            GwClass cur = gp.effectiveClass();
            if (Arrays.asList(cat).contains(cur)) continue;
            int idx = com.aidan.skyserver.bots.BotMemory.pickPlan("gwclass:" + want, cat.length, null, RNG);
            GwClass pick = null;
            for (int k = 0; k < cat.length && pick == null; k++) {
                GwClass c = cat[(idx + k) % cat.length];
                if (!classFull(gp, c)) pick = c;
            }
            if (pick == null || pick == cur) continue;
            BOT_CAT.put(e.getKey(), want);
            ServerPlayer p = server.getPlayerList().getPlayer(e.getKey());
            // Build phase, or standing in spawn: switch right away. Otherwise on the next respawn.
            boolean atSpawn = p != null && p.isAlive() && gp.respawnAt < 0 && GoldWarsMap.inSpawnZone(p.blockPosition());
            if (p != null && (state == State.BUILD || atSpawn) && gp.respawnAt < 0 && !gp.offline) switchClassNow(p, gp, pick);
            else gp.pending = pick;
        }
    }

    /** Bots learn which class works best for each job: a win counts for the class they ended as. */
    private static void learnBotClasses(GwTeam winner) {
        for (Map.Entry<UUID, GwPlayer> e : players.entrySet()) {
            GwPlayer gp = e.getValue();
            String cat = BOT_CAT.get(e.getKey());
            if (!gp.bot || cat == null) continue;
            GwClass[] arr = CLASS_CATS.get(cat);
            int idx = Arrays.asList(arr).indexOf(gp.cls);
            if (idx < 0) continue;
            com.aidan.skyserver.bots.BotMemory.planResult("gwclass:" + cat, arr.length, idx, winner == null ? 0.5 : winner == gp.team ? 1 : 0);
        }
        BOT_CAT.clear();
    }

    /** Match recorder: one line per player/bot per second. */
    private static void recordUnits() {
        for (Map.Entry<UUID, GwPlayer> e : players.entrySet()) {
            ServerPlayer p = server.getPlayerList().getPlayer(e.getKey());
            if (p == null) continue;
            GwPlayer gp = e.getValue();
            boolean bot = p instanceof BotPlayer;
            String doing = !p.isAlive() || gp.respawnAt >= 0 ? "dead" : bot && ((BotPlayer) p).brain() != null ? ((BotPlayer) p).brain().describe() : "(real player)";
            com.aidan.skyserver.bots.BotRecorder.unit(p.getName().getString(), gp.team.name(), gp.cls.id,
                    bot ? String.valueOf(((BotPlayer) p).difficulty()) : "player", p.getX(), p.getY(), p.getZ(), p.getHealth(), doing);
        }
    }

    private static void endMatch(GwTeam winner) {
        com.aidan.skyserver.bots.BotRecorder.end(winner == null ? "tie" : winner.name() + " wins");
        learnBotClasses(winner);
        if (state == State.ENDING) return;
        com.aidan.skyserver.bots.GoldWarsCommander.matchResult(winner);
        state = State.ENDING;
        timer = 10 * 20;

        if (winner != null) {
            broadcast("§6§l" + winner.display.toUpperCase() + " TEAM WINS GOLD WARS!");
        }
        // Pay everyone (winners more, losers a participation reward). Wins against bots pay less.
        for (Map.Entry<UUID, GwPlayer> e : players.entrySet()) {
            GwPlayer gp = e.getValue();
            ServerPlayer p = server.getPlayerList().getPlayer(e.getKey());
            if (p == null || gp.bot || !rewards) continue;
            // AFK players don't get match pay: you have to have fought, died, or broken gold.
            if (gp.kills + gp.goldBroken + gp.deaths == 0 && gp.lastAttackTick == 0) {
                msg(p, "§7No match reward: you weren't active this match.");
                continue;
            }
            int enemyBots = botCount(gp.team.other());
            double scale = com.aidan.skyserver.lobby.Rewards.botScale(enemyBots);
            String bots = enemyBots > 0 ? " §8(" + enemyBots + " bots on the other team)" : "";
            if (winner == gp.team) {
                pay(p, Math.round(com.aidan.skyserver.lobby.Rewards.GW_WIN * scale), "for winning" + bots);
                com.aidan.skyserver.lobby.Levels.addXp(server, p, com.aidan.skyserver.lobby.Rewards.GW_WIN_XP);
                com.aidan.skyserver.lobby.Quests.progress(p, "gw_win", 1);
                com.aidan.skyserver.lobby.PlayerStats.add(p, "gw_win", 1);
                com.aidan.skyserver.lobby.Skills.add(p, com.aidan.skyserver.lobby.Skills.Skill.COMBAT, 300);
                com.aidan.skyserver.lobby.Quests.progress(p, "gw_play", 1);
                if (com.aidan.skyserver.lobby.Rewards.firstWinToday("goldwars", e.getKey())) {
                    pay(p, Math.round(com.aidan.skyserver.lobby.Rewards.GW_FIRST_WIN * scale), "first Gold Wars win of the day!");
                    boolean realOpponents = realCount(winner.other()) > 0;
                    com.aidan.skyserver.lobby.Keys.reward(server, e.getKey(), realOpponents ? 1 : 0, 1, "(first Gold Wars win today)");
                }
            } else {
                pay(p, Math.round(com.aidan.skyserver.lobby.Rewards.GW_LOSS * scale), winner == null ? "for a tie" : "for playing");
                com.aidan.skyserver.lobby.Levels.addXp(server, p, com.aidan.skyserver.lobby.Rewards.GW_LOSS_XP);
                com.aidan.skyserver.lobby.Quests.progress(p, "gw_play", 1);
            }
        }

        for (ServerPlayer p : onlinePlayers()) com.aidan.skyserver.lobby.Rewards.matchEnd(p, "Gold Wars");

        // Match summary.
        GwPlayer topGold = null, topKills = null;
        String topGoldName = "", topKillsName = "";
        for (Map.Entry<UUID, GwPlayer> e : players.entrySet()) {
            ServerPlayer p = server.getPlayerList().getPlayer(e.getKey());
            String name = p != null ? p.getName().getString() : "?";
            GwPlayer gp = e.getValue();
            if (topGold == null || gp.goldBroken > topGold.goldBroken) { topGold = gp; topGoldName = name; }
            if (topKills == null || gp.kills > topKills.kills) { topKills = gp; topKillsName = name; }
        }
        if (topGold != null && topGold.goldBroken > 0) {
            broadcast("§eMost gold broken: §f" + topGoldName + " §7(" + topGold.goldBroken + ")");
        }
        if (topKills != null && topKills.kills > 0) {
            broadcast("§eMost kills: §f" + topKillsName + " §7(" + topKills.kills + ")");
        }
        broadcast("§7Returning to the lobby in 10 seconds...");
    }

    /** Sends everyone home, restores their stuff, and resets the map. */
    private static void finish() {
        GoldWarsSkills.reset();
        com.aidan.skyserver.bots.GoldWarsCommander.matchOver();
        if (com.aidan.skyserver.bots.BotRecorder.recording()) com.aidan.skyserver.bots.BotRecorder.end("stopped");
        List<ServerPlayer> bots = new ArrayList<>();
        for (Map.Entry<UUID, GwPlayer> e : players.entrySet()) {
            ServerPlayer p = server.getPlayerList().getPlayer(e.getKey());
            if (e.getValue().bot && p != null) bots.add(p);
        }
        restoreEveryone();
        for (ServerPlayer bot : bots) BotManager.remove(bot);
        waitStart = -1;
        GoldWarsMap.nextLayout(); // rotate maps every match
        rebuildMap();
        state = State.WAITING;
    }

    private static void restoreEveryone() {
        if (server == null) return;
        for (Map.Entry<UUID, GwPlayer> e : new ArrayList<>(players.entrySet())) {
            ServerPlayer p = server.getPlayerList().getPlayer(e.getKey());
            if (p == null) continue;
            removeFromScoreboardTeam(p, e.getValue().team);
            GoldWarsSkills.cleanup(p);
            e.getValue().snapshot.restore(p);
        }
        players.clear();
    }

    private static void rebuildMap() {
        if (arena() == null) {
            Skyserver.LOGGER.error("Can't build the Gold Wars map: dimension skyserver:arena is missing.");
            return;
        }
        GoldWarsMap.rebuild(arena());
        resetGold();
        mapReady = true;
    }

    private static void resetGold() {
        for (GwTeam t : GwTeam.values()) {
            gold.get(t).clear();
            gold.get(t).addAll(GoldWarsMap.goldBlocks(t));
        }
    }

    // =====================================================================
    // Deaths and respawns
    // =====================================================================

    private static void handleDeath(ServerPlayer player, GwPlayer gp, UUID killerId) {
        gp.deaths++;
        if (gp == null || gp.respawnAt >= 0) return;

        if (player instanceof BotPlayer b && b.brain() instanceof GoldWarsBrain brain) brain.onDeath();
        ServerPlayer killedBy = killerId == null ? null : server.getPlayerList().getPlayer(killerId);
        com.aidan.skyserver.bots.BotRecorder.event(player.getName().getString() + " (" + gp.team.name() + ") was killed"
                + (killedBy != null ? " by " + killedBy.getName().getString() : "") + " at " + (int) player.getX() + " " + (int) player.getY() + " " + (int) player.getZ());
        String victimName = gp.team.color + player.getName().getString() + "§r";
        GwPlayer killer = killerId == null ? null : players.get(killerId);
        ServerPlayer killerPlayer = killerId == null ? null : server.getPlayerList().getPlayer(killerId);

        // Kill money is shared by everyone who hurt them (since they last healed 2 hearts), by damage.
        long killPay = gp.bot ? Math.round(com.aidan.skyserver.lobby.Rewards.GW_KILL * com.aidan.skyserver.lobby.Rewards.BOT_KILL_PAY) : com.aidan.skyserver.lobby.Rewards.GW_KILL;
        for (Map.Entry<UUID, Double> sh : com.aidan.skyserver.DamageCredit.shares(player.getUUID(), killerId).entrySet()) {
            GwPlayer who = players.get(sh.getKey());
            ServerPlayer wp = server.getPlayerList().getPlayer(sh.getKey());
            if (who == null || wp == null || who.team == gp.team) continue;
            long amt = Math.max(1, Math.round(killPay * sh.getValue()));
            pay(wp, amt, sh.getValue() >= 0.999 ? (gp.bot ? "for a kill (bot)" : "for a kill")
                    : "for " + Math.round(sh.getValue() * 100) + "% of the damage on " + player.getName().getString());
        }
        if (killer != null && killerPlayer != null && killer.team != gp.team) {
            killer.kills++;
            if (gp.bot) {
                com.aidan.skyserver.lobby.Skills.add(killerPlayer, com.aidan.skyserver.lobby.Skills.Skill.COMBAT, 60);
                com.aidan.skyserver.lobby.PlayerStats.add(killerPlayer, "game_kill", 1);
                if (rewards) com.aidan.skyserver.lobby.Levels.addXp(server, killerPlayer, com.aidan.skyserver.lobby.Rewards.GW_KILL_XP / 2);
            } else {
                com.aidan.skyserver.lobby.Skills.add(killerPlayer, com.aidan.skyserver.lobby.Skills.Skill.COMBAT, 150);
                com.aidan.skyserver.lobby.PlayerStats.add(killerPlayer, "game_kill", 1);
                if (rewards) com.aidan.skyserver.lobby.Levels.addXp(server, killerPlayer, com.aidan.skyserver.lobby.Rewards.GW_KILL_XP);
            }
            // Kill rewards: a few blocks, plus arrows for archers.
            killerPlayer.getInventory().add(new ItemStack(killer.team.wool, 8));
            if (killer.cls == GwClass.ARCHER && !killer.bot) killerPlayer.getInventory().add(new ItemStack(Items.ARROW, 6));
            broadcast(victimName + " §7was killed by " + killer.team.color + killerPlayer.getName().getString());
        } else {
            broadcast(victimName + " §7died.");
        }

        player.getInventory().clearContent();
        player.setGameMode(GameType.SPECTATOR);
        BlockPos s = GoldWarsMap.spawn(gp.team);
        player.teleportTo(arena(), s.getX() + 0.5, s.getY() + 10, s.getZ() + 0.5,
                Set.of(), GoldWarsMap.spawnYaw(gp.team), 60f, true);
        resetVitals(player);
        gp.respawnAt = ticks + RESPAWN_SECONDS * 20L;
        gp.lastAttacker = null;
        msg(player, "§eRespawning in " + RESPAWN_SECONDS + " seconds. §7Change class with /class (or /builder, /knight...).");
    }

    private static void respawn(ServerPlayer player, GwPlayer gp) {
        if (gp.pending != null) {
            if (classFull(gp, gp.pending)) {
                msg(player, "§c" + gp.pending.display + " is full now, so you stay a " + gp.cls.display + ".");
            } else {
                gp.cls = gp.pending;
            }
            gp.pending = null;
        }
        spawnPlayer(player, gp);
    }

    /** Puts a player into the fight at their team's spawn with their kit. */
    private static void spawnPlayer(ServerPlayer player, GwPlayer gp) {
        gp.respawnAt = -1;
        gp.cls.giveKit(player, gp.team);
        java.util.Arrays.fill(gp.blocksGiven, gp.cls.blockCount());
        player.setGameMode(GameType.SURVIVAL);
        resetVitals(player);
        GoldWarsSkills.onSpawn(player, gp.cls);
        BlockPos s = GoldWarsMap.spawn(gp.team);
        player.teleportTo(arena(), s.getX() + 0.5, s.getY(), s.getZ() + 0.5,
                Set.of(), GoldWarsMap.spawnYaw(gp.team), 0f, true);
        gp.invulnUntil = ticks + SPAWN_INVULN_SECONDS * 20L;
    }

    /** Lobby: stand on your spawn pad with just the class selector. */
    private static void sendToSpawn(ServerPlayer player, GwPlayer gp) {
        player.getInventory().clearContent();
        player.getInventory().setItem(4, classSelector());
        player.setGameMode(GameType.ADVENTURE);
        resetVitals(player);
        BlockPos s = GoldWarsMap.spawn(gp.team);
        player.teleportTo(arena(), s.getX() + 0.5, s.getY(), s.getZ() + 0.5,
                Set.of(), GoldWarsMap.spawnYaw(gp.team), 0f, true);
    }

    private static UUID recentAttacker(GwPlayer gp) {
        if (gp == null || gp.lastAttacker == null) return null;
        return ticks - gp.lastAttackTick <= 10 * 20 ? gp.lastAttacker : null;
    }

    // =====================================================================
    // Teams
    // =====================================================================

    private static int count(GwTeam team) {
        int n = 0;
        for (GwPlayer gp : players.values()) if (gp.team == team) n++;
        return n;
    }

    /** Before the match, move the most recent joiners until teams differ by at most 1. */
    private static void rebalanceTeams() {
        while (Math.abs(count(GwTeam.RED) - count(GwTeam.BLUE)) > 1) {
            GwTeam big = count(GwTeam.RED) > count(GwTeam.BLUE) ? GwTeam.RED : GwTeam.BLUE;
            UUID moveId = null;
            GwPlayer move = null;
            for (Map.Entry<UUID, GwPlayer> e : players.entrySet()) {
                if (e.getValue().team == big && (move == null || e.getValue().joinOrder > move.joinOrder)) {
                    moveId = e.getKey();
                    move = e.getValue();
                }
            }
            if (move == null) break;
            move.team = big.other();
            ServerPlayer p = server.getPlayerList().getPlayer(moveId);
            if (p != null) {
                removeFromScoreboardTeam(p, big);
                addToScoreboardTeam(p, move.team);
                msg(p, "§eYou were moved to " + move.team.colored() + " §eto balance the teams.");
            }
        }
    }

    /** Vanilla scoreboard teams give colored name tags and block friendly fire. */
    private static PlayerTeam scoreboardTeam(GwTeam team) {
        Scoreboard sb = server.getScoreboard();
        String name = "gw_" + team.name().toLowerCase();
        PlayerTeam pt = sb.getPlayerTeam(name);
        if (pt == null) pt = sb.addPlayerTeam(name);
        pt.setColor(Optional.of(team == GwTeam.RED ? TeamColor.RED : TeamColor.BLUE));
        pt.setAllowFriendlyFire(false);
        pt.setSeeFriendlyInvisibles(true);
        pt.setPlayerPrefix(Component.literal("[" + team.display + "] ").withStyle(team.color));
        return pt;
    }

    private static void addToScoreboardTeam(ServerPlayer p, GwTeam team) {
        server.getScoreboard().addPlayerToTeam(p.getScoreboardName(), scoreboardTeam(team));
    }

    /** Takes a player off the Red/Blue name colours if they're not in a match (e.g. left over from before a restart). */
    public static void clearStaleTeam(ServerPlayer p) {
        if (server == null || players.containsKey(p.getUUID())) return;
        for (GwTeam t : GwTeam.values()) removeFromScoreboardTeam(p, t);
    }

    private static void removeFromScoreboardTeam(ServerPlayer p, GwTeam team) {
        PlayerTeam pt = server.getScoreboard().getPlayerTeam("gw_" + team.name().toLowerCase());
        if (pt != null && pt.getPlayers().contains(p.getScoreboardName())) {
            server.getScoreboard().removePlayerFromTeam(p.getScoreboardName(), pt);
        }
    }

    // =====================================================================
    // Helpers
    // =====================================================================

    private static boolean inMatch() {
        return state == State.BUILD || state == State.FIGHT || state == State.OVERTIME;
    }

    private static ServerLevel arena() {
        return server.getLevel(ARENA);
    }

    // ---- Used by GoldWarsSkills ----

    static ServerLevel arenaLevel() {
        return server == null ? null : arena();
    }

    static long now() {
        return ticks;
    }

    static boolean inMatchNow() {
        return inMatch();
    }

    /** Ticks left in the current phase. */
    static int timerTicks() {
        return timer;
    }

    static boolean isOvertime() {
        return state == State.OVERTIME;
    }

    /** Everyone on a team (alive, respawning or offline). */
    static List<UUID> members(GwTeam team) {
        List<UUID> list = new ArrayList<>();
        for (Map.Entry<UUID, GwPlayer> e : players.entrySet()) if (e.getValue().team == team) list.add(e.getKey());
        return list;
    }

    static Set<BlockPos> goldOf(GwTeam team) {
        return Collections.unmodifiableSet(new HashSet<>(gold.get(team)));
    }

    static boolean isFighting() {
        return state == State.FIGHT || state == State.OVERTIME;
    }

    static GwTeam teamOf(UUID id) {
        GwPlayer gp = players.get(id);
        return gp == null ? null : gp.team;
    }

    static GwClass classOf(UUID id) {
        GwPlayer gp = players.get(id);
        return gp == null ? null : gp.cls;
    }

    static boolean isAlive(UUID id) {
        GwPlayer gp = players.get(id);
        return gp != null && gp.respawnAt < 0;
    }

    static ServerPlayer player(UUID id) {
        return server.getPlayerList().getPlayer(id);
    }

    static List<ServerPlayer> alivePlayers() {
        List<ServerPlayer> list = new ArrayList<>();
        for (Map.Entry<UUID, GwPlayer> e : players.entrySet()) {
            if (e.getValue().respawnAt >= 0) continue;
            ServerPlayer p = server.getPlayerList().getPlayer(e.getKey());
            if (p != null) list.add(p);
        }
        return list;
    }

    static void message(ServerPlayer p, String text) {
        msg(p, text);
    }

    /** Can this block be broken or placed by a player or a skill right now? */
    static boolean isGold(BlockPos pos) {
        for (GwTeam t : GwTeam.values()) if (gold.get(t).contains(pos)) return true;
        return false;
    }

    static boolean canEdit(BlockPos pos) {
        if (!inMatch()) return false;
        for (GwTeam t : GwTeam.values()) {
            if (gold.get(t).contains(pos)) return false;
        }
        if (GoldWarsMap.inSpawnZone(pos) || !GoldWarsMap.inBuildArea(pos)) return false;
        if (state == State.BUILD && (GoldWarsMap.onWallLine(pos) || !GoldWarsMap.onPlatform(pos))) return false;
        return true;
    }

    static void runNextTick(Runnable job) {
        nextTick.add(job);
    }

    private static void pay(ServerPlayer p, long amount, String reason) {
        if (!rewards || amount <= 0 || BotManager.isBot(p.getUUID())) return;
        com.aidan.skyserver.lobby.Rewards.pay(server, p, amount, reason);
    }

    /** Status line above the hotbar, updated every second. */
    private static void showStatus() {
        String phase = switch (state) {
            case WAITING -> "Waiting for players (" + players.size() + "/" + (MAX_PER_TEAM * 2) + ")"
                    + (BotManager.ENABLED && waitStart >= 0 ? "  §7Bots in " + clock((int) Math.max(0, BotManager.FILL_AFTER_SECONDS * 20L - (ticks - waitStart))) : "");
            case COUNTDOWN -> "Starting in " + (timer / 20);
            case BUILD -> "Build phase " + clock(timer);
            case FIGHT -> "Fight " + clock(timer);
            case OVERTIME -> "§6OVERTIME " + clock(timer);
            case ENDING -> "Game over";
        };
        int total = GoldWarsMap.goldBlocks(GwTeam.RED).size();
        String goldLine = "§cRed §f" + gold.get(GwTeam.RED).size() + "§7/" + total + "   §9Blue §f"
                + gold.get(GwTeam.BLUE).size() + "§7/" + total + "   §e" + phase;
        for (Map.Entry<UUID, GwPlayer> e : players.entrySet()) {
            ServerPlayer p = server.getPlayerList().getPlayer(e.getKey());
            if (p == null) continue;
            GwPlayer gp = e.getValue();
            if (gp.cls == GwClass.LONGBOW && p.isUsingItem()) continue; // the charge bar is showing instead
            String extra = gp.respawnAt >= 0
                    ? "   §cRespawning " + Math.max(1, (gp.respawnAt - ticks + 19) / 20)
                    : "   §7" + gp.cls.display + (inMatch() && !GoldWarsSkills.status(p, gp.cls).isEmpty()
                            ? "  §7" + GoldWarsSkills.status(p, gp.cls) : "");
            p.connection.send(new ClientboundSetActionBarTextPacket(Component.literal(goldLine + extra)));
        }
    }

    private static String clock(int ticksLeft) {
        int total = Math.max(0, (ticksLeft + 19) / 20);
        return String.format("%d:%02d", total / 60, total % 60);
    }

    private static void resetVitals(ServerPlayer p) {
        p.setHealth(p.getMaxHealth());
        p.getFoodData().setFoodLevel(20);
        p.getFoodData().setSaturation(5f);
        p.removeAllEffects();
        p.setRemainingFireTicks(0);
        p.resetFallDistance();
    }

    private static void msg(ServerPlayer p, String text) {
        if (p instanceof BotPlayer) return;
        p.sendSystemMessage(Component.literal(text));
    }

    /** Real players in the match who are online. */
    private static List<ServerPlayer> onlinePlayers() {
        List<ServerPlayer> list = new ArrayList<>();
        for (UUID id : players.keySet()) {
            ServerPlayer p = server.getPlayerList().getPlayer(id);
            if (p != null && !(p instanceof BotPlayer)) list.add(p);
        }
        return list;
    }

    private static void broadcast(String text) {
        for (UUID id : players.keySet()) {
            ServerPlayer p = server.getPlayerList().getPlayer(id);
            if (p != null) msg(p, "§6[Gold Wars] §r" + text);
        }
    }

    private static void teamMessage(GwTeam team, String text) {
        for (Map.Entry<UUID, GwPlayer> e : players.entrySet()) {
            if (e.getValue().team != team) continue;
            ServerPlayer p = server.getPlayerList().getPlayer(e.getKey());
            if (p != null) msg(p, "§6[Gold Wars] §r" + text);
        }
    }
}
