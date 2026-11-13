package com.aidan.skyserver.skywars;

import com.aidan.skyserver.Skyserver;
import com.aidan.skyserver.bots.BotManager;
import com.aidan.skyserver.bots.BotPlayer;
import com.aidan.skyserver.bots.Difficulty;
import com.aidan.skyserver.bots.SkywarsBrain;
import com.aidan.skyserver.goldwars.GoldWarsGame;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.level.GameType;

import java.util.*;

/** Runs one solo Skywars match at a time for up to 8 players. */
public final class SkywarsGame {

    // ---- Settings you can change ----
    public static final int MAX_PLAYERS = SkywarsMap.SPAWN_COUNT; // 8
    public static final int MIN_PLAYERS = 2;
    public static final int COUNTDOWN_SECONDS = 30;
    public static final int FULL_COUNTDOWN_SECONDS = 10;          // when all 8 slots fill
    public static final int MAX_GAME_MINUTES = 15;
    public static final int ITEM_EVERY_SECONDS = 20;   // instead of chest refills: one new item each
    public static final int SUPPLY_DROP_MINUTE = 5;
    public static final int TNT_RAIN_MINUTE = 9;
    public static final int TNT_EVERY_TICKS = 20;      // 1 TNT per alive player per second
    public static final int TNT_FUSE_TICKS = 50;       // roughly explodes as it lands
    // Money and XP amounts are in lobby/Rewards.java.
    // Runs as the server console when someone wins. %s = winner's name, %d = amount.
    public static final String PAY_COMMAND = "eco addmoney %s %d";

    private enum State { WAITING, COUNTDOWN, PLAYING, ENDING }

    private static final Random RNG = new Random();
    private static MinecraftServer server;
    private static State state = State.WAITING;
    private static int timer;               // ticks (20 per second)
    private static int startedWith;         // players when the match began
    private static boolean mapReady = false;
    private static long ticks = 0;
    private static long waitStart = -1;     // when the first real player started waiting (bot fill)
    private static int botsAtStart = 0;
    private static int matchNumber = 0;

    private static final Map<UUID, PlayerSnapshot> players = new LinkedHashMap<>();
    private static final Map<UUID, Integer> spawnOf = new HashMap<>();
    private static final com.aidan.skyserver.lobby.MapVote VOTE = new com.aidan.skyserver.lobby.MapVote("sw",
            java.util.Arrays.stream(SkywarsDesigns.Theme.values()).map(t -> t.id).toArray(String[]::new),
            java.util.Arrays.stream(SkywarsDesigns.Theme.values()).map(t -> t.display).toArray(String[]::new));
    private static final Set<UUID> alive = new HashSet<>();

    private SkywarsGame() {}

    public static boolean isInGame(UUID id) {
        return players.containsKey(id);
    }

    public static void init() {
        ServerLifecycleEvents.SERVER_STARTED.register(s -> {
            server = s;
            SwItems.init(s);
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(s -> restoreEveryone());
        SwItems.registerEvents();
        ServerTickEvents.END_SERVER_TICK.register(s -> com.aidan.skyserver.Safe.run("skywars tick", () -> tick()));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, s) -> leave(handler.getPlayer(), false, true));

        ServerLivingEntityEvents.ALLOW_DEATH.register((entity, source, amount) -> {
            if (!(entity instanceof ServerPlayer player) || !players.containsKey(player.getUUID())) {
                return true; // not our player, normal death
            }
            player.setHealth(player.getMaxHealth());
            if (state == State.PLAYING && alive.contains(player.getUUID())) {
                if (source.getEntity() instanceof BotPlayer kb && kb.brain() instanceof SkywarsBrain sb) sb.onKill();
                // Kill pay for real players (half for a bot), shared by everyone who hurt them, by damage.
                boolean victimBot = player instanceof BotPlayer;
                long killPay = victimBot ? Math.round(com.aidan.skyserver.lobby.Rewards.SW_KILL * com.aidan.skyserver.lobby.Rewards.BOT_KILL_PAY) : com.aidan.skyserver.lobby.Rewards.SW_KILL;
                UUID fallback = source.getEntity() instanceof ServerPlayer k0 && k0 != player ? k0.getUUID() : null;
                if (startedWith > 1) for (Map.Entry<UUID, Double> sh : com.aidan.skyserver.DamageCredit.shares(player.getUUID(), fallback).entrySet()) {
                    ServerPlayer wp = server.getPlayerList().getPlayer(sh.getKey());
                    if (wp == null || wp == player || wp instanceof BotPlayer || !players.containsKey(wp.getUUID())) continue;
                    com.aidan.skyserver.lobby.Rewards.pay(server, wp, Math.max(1, Math.round(killPay * sh.getValue())), sh.getValue() >= 0.999
                            ? (victimBot ? "for a kill (bot)" : "for a kill") : "for " + Math.round(sh.getValue() * 100) + "% of the damage on " + player.getName().getString());
                }
                if (source.getEntity() instanceof ServerPlayer killer && !(killer instanceof BotPlayer) && killer != player && startedWith > 1) {
                    boolean bot = player instanceof BotPlayer;
                    com.aidan.skyserver.lobby.Levels.addXp(server, killer, bot ? com.aidan.skyserver.lobby.Rewards.SW_KILL_XP / 2 : com.aidan.skyserver.lobby.Rewards.SW_KILL_XP);
                    com.aidan.skyserver.lobby.Quests.progress(killer, "sw_kill", 1);
                    com.aidan.skyserver.lobby.Skills.add(killer, com.aidan.skyserver.lobby.Skills.Skill.COMBAT, bot ? 60 : 150);
                    com.aidan.skyserver.lobby.PlayerStats.add(killer, "game_kill", 1);
                }
                String msg = source.getEntity() instanceof ServerPlayer killer
                        ? player.getName().getString() + " was killed by " + killer.getName().getString()
                        : player.getName().getString() + " died";
                eliminate(player, msg, true);
            }
            return false; // never let a Skywars player actually die
        });

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> dispatcher.register(
                Commands.literal("sw")
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
                                msg(p, "§cYou can vote for the map while waiting for a Skywars match.");
                                return 0;
                            }
                            String r = VOTE.vote(p, com.mojang.brigadier.arguments.StringArgumentType.getString(c, "map"));
                            if (r != null) broadcast(r);
                            return 1;
                        })))
                        .then(Commands.literal("forcestart").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS)).executes(c -> {
                            forceStart(c.getSource());
                            return 1;
                        }))
                        .then(Commands.literal("fillbots").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS)).executes(c -> {
                            // Testing helper: fill the empty slots with bots right now.
                            if ((state != State.WAITING && state != State.COUNTDOWN) || realCount() == 0) {
                                c.getSource().sendFailure(Component.literal("Join first, and only before a match starts."));
                                return 0;
                            }
                            fillBots();
                            return 1;
                        }))
                        .then(Commands.literal("stop").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS)).executes(c -> {
                            broadcast("§cThe match was stopped by an admin.");
                            finish();
                            c.getSource().sendSuccess(() -> Component.literal("Skywars stopped and reset."), true);
                            return 1;
                        }))
                        .then(Commands.literal("rebuild").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS)).executes(c -> {
                            if (state != State.WAITING || !players.isEmpty()) {
                                c.getSource().sendFailure(Component.literal("Stop the match first with /sw stop."));
                                return 0;
                            }
                            SkywarsMap.rebuild(arenaLevel());
                            mapReady = true;
                            c.getSource().sendSuccess(() -> Component.literal("Skywars map rebuilt."), true);
                            return 1;
                        }))
        ));

        Skyserver.LOGGER.info("Skywars ready. Players join with /sw join");
    }

    // ---------------- Joining and leaving ----------------

    public static void join(ServerPlayer player) {
        UUID id = player.getUUID();
        if (players.containsKey(id)) {
            msg(player, "§eYou're already in Skywars. Use /leave to quit.");
            return;
        }
        if (GoldWarsGame.isInGame(id)) {
            msg(player, "§cLeave Gold Wars first with /leave.");
            return;
        }
        if (com.aidan.skyserver.bedwars.BedwarsGame.isInGame(id)) {
            msg(player, "§cLeave Bedwars first with /leave.");
            return;
        }
        if (com.aidan.skyserver.lobby.Lobby.level() != null && !com.aidan.skyserver.lobby.Lobby.isLobby(player.level())) {
            msg(player, "§cJoin Skywars from the lobby (§e/lobby§c).");
            return;
        }
        if (state == State.PLAYING || state == State.ENDING) {
            msg(player, "§cA match is in progress. Try again in a minute.");
            return;
        }
        if (players.size() >= MAX_PLAYERS) {
            UUID botId = anyBot();
            if (botId == null) {
                msg(player, "§cSkywars is full.");
                return;
            }
            removeBot(botId); // a bot steps aside for the real player
        }

        ServerLevel arena = arenaLevel();
        if (!mapReady) {
            SkywarsMap.rebuild(arena);
            mapReady = true;
        }

        players.put(id, new PlayerSnapshot(player));
        putInCage(player, arena);

        broadcast("§a" + player.getName().getString() + " joined Skywars (" + players.size() + "/" + MAX_PLAYERS + ")");
        msg(player, "§bMap: §f" + SkywarsMap.theme().display + " §7- loot your 3 chests, mine the island for blocks, and watch for a new item every " + ITEM_EVERY_SECONDS + "s.");
        VOTE.offer(player, SkywarsMap.themeIndex());
        com.aidan.skyserver.lobby.Parties.followLeader(player, "skywars");

        if (!BotManager.ENABLED && state == State.WAITING && players.size() >= MIN_PLAYERS) {
            state = State.COUNTDOWN;
            timer = COUNTDOWN_SECONDS * 20;
            broadcast("§eMatch starting in " + COUNTDOWN_SECONDS + " seconds!");
        }
        if (BotManager.ENABLED && state == State.WAITING && botCount() == 0) {
            long left = BotManager.FILL_AFTER_SECONDS - (waitStart < 0 ? 0 : (ticks - waitStart) / 20);
            msg(player, "§7If the lobby isn't full in " + (left / 60) + ":" + String.format("%02d", left % 60)
                    + ", bots will fill the empty slots.");
        }
        if (state == State.WAITING && players.size() >= MAX_PLAYERS) {
            state = State.COUNTDOWN;
            timer = FULL_COUNTDOWN_SECONDS * 20;
        }
        if (state == State.COUNTDOWN && players.size() >= MAX_PLAYERS && timer > FULL_COUNTDOWN_SECONDS * 20) {
            timer = FULL_COUNTDOWN_SECONDS * 20;
            broadcast("§eLobby full! Starting in " + FULL_COUNTDOWN_SECONDS + " seconds!");
        }
    }

    /** Puts a player (or bot) on a free spawn island inside its glass cage. */
    private static void putInCage(ServerPlayer player, ServerLevel arena) {
        int spawn = freeSpawn();
        spawnOf.put(player.getUUID(), spawn);

        player.getInventory().clearContent();
        player.setGameMode(GameType.ADVENTURE); // can't break the cage
        resetVitals(player);
        BlockPos s = SkywarsMap.spawn(spawn);
        player.teleportTo(arena, s.getX() + 0.5, s.getY(), s.getZ() + 0.5, Set.of(), player.getYRot(), 0f, true);
    }

    private static void leave(ServerPlayer player, boolean voluntary) {
        leave(player, voluntary, false);
    }

    /** disconnecting: don't teleport them now; the lobby sorts them out when they log back in. */
    private static void leave(ServerPlayer player, boolean voluntary, boolean disconnecting) {
        UUID id = player.getUUID();
        PlayerSnapshot snap = players.get(id);
        if (snap == null) {
            if (voluntary) msg(player, "§eYou're not in Skywars.");
            return;
        }

        boolean wasAlive = alive.remove(id);
        players.remove(id);
        VOTE.remove(id);
        spawnOf.remove(id);
        if (!disconnecting) snap.restore(player);

        if (voluntary) msg(player, "§eYou left Skywars.");
        boolean wasBot = player instanceof BotPlayer;
        if (!wasBot) {
            com.aidan.skyserver.lobby.Rewards.matchEnd(player, "Skywars");
            broadcast("§7" + player.getName().getString() + " left Skywars.");
        }
        if (wasBot) {
            if (state == State.PLAYING && wasAlive) checkForWinner();
            return;
        }
        if (!wasBot && realCount() == 0) {
            // Only bots left: end the match, or empty the lobby.
            if (state == State.PLAYING || state == State.ENDING) {
                finish();
            } else {
                removeAllBots();
                state = State.WAITING;
                waitStart = -1;
            }
            return;
        }
        if (!BotManager.ENABLED && state == State.COUNTDOWN && players.size() < MIN_PLAYERS) {
            state = State.WAITING;
            broadcast("§cNot enough players. Countdown cancelled.");
        }
        if (!wasBot && state == State.COUNTDOWN && botCount() > 0) {
            addBot(BotManager.randomDifficulty()); // a bot takes the empty slot
        }
        if (state == State.PLAYING && wasAlive) checkForWinner();
        if (players.isEmpty() && state != State.WAITING) finish();
    }

    private static void forceStart(CommandSourceStack source) {
        if (state == State.PLAYING || state == State.ENDING) {
            source.sendFailure(Component.literal("A match is already running."));
            return;
        }
        if (players.isEmpty()) {
            source.sendFailure(Component.literal("Nobody has joined yet."));
            return;
        }
        start();
        source.sendSuccess(() -> Component.literal("Skywars force-started."), true);
    }

    // ---------------- Game loop ----------------

    private static void tick() {
        if (server == null) return;
        ticks++;
        tickBotFill();
        switch (state) {
            case COUNTDOWN -> {
                timer--;
                if (timer % 20 == 0) {
                    int sec = timer / 20;
                    if (com.aidan.skyserver.lobby.Announce.countdown(onlinePlayers(), sec, "§b", "§7Skywars is starting")) broadcast("§eStarting in " + sec + " seconds.");
                }
                if (timer <= 0) start();
            }
            case PLAYING -> {
                timer++;
                List<ServerPlayer> fell = new ArrayList<>();
                for (UUID id : alive) {
                    ServerPlayer p = server.getPlayerList().getPlayer(id);
                    if (p != null && p.getY() < SkywarsMap.VOID_Y) fell.add(p);
                }
                for (ServerPlayer p : fell) eliminate(p, p.getName().getString() + " fell into the void", false);
                if (state != State.PLAYING) return;

                ServerLevel arena = arenaLevel();
                BridgeEggs.tick(arena);
                runTimeline(arena);
                if (botsAtStart > 0) watchOpenings();

                if (state == State.PLAYING && timer >= MAX_GAME_MINUTES * 60 * 20) {
                    broadcast("§cTime's up! The match is a draw.");
                    endMatch(null);
                }
            }
            case ENDING -> {
                timer--;
                if (timer <= 0) finish();
            }
            default -> {}
        }
    }

    // ---------------- Bots ----------------

    /** Bots learn how real players open: seconds 10-60, once a second, where is each real player? */
    private static void watchOpenings() {
        if (timer < 200 || timer > 1200 || timer % 20 != 0) return;
        for (UUID id : alive) {
            if (BotManager.isBot(id)) continue;
            ServerPlayer p = server.getPlayerList().getPlayer(id);
            Integer spawn = spawnOf.get(id);
            if (p == null || spawn == null) continue;
            BlockPos home = SkywarsMap.spawn(spawn);
            double toHome = Math.hypot(p.getX() - home.getX(), p.getZ() - home.getZ());
            double toMiddle = Math.hypot(p.getX() - SkywarsMap.CENTER_X, p.getZ() - SkywarsMap.CENTER_Z);
            int where = toHome < 9 ? com.aidan.skyserver.bots.SwLearning.HOME
                    : toMiddle < 14 ? com.aidan.skyserver.bots.SwLearning.MIDDLE : com.aidan.skyserver.bots.SwLearning.OUT;
            com.aidan.skyserver.bots.SwLearning.observeOpening(where);
        }
    }

    /** After 3 minutes of waiting with at least one real player, bots fill the empty slots. */
    private static void tickBotFill() {
        if (!BotManager.ENABLED || (state != State.WAITING && state != State.COUNTDOWN)) return;
        if (realCount() == 0) {
            waitStart = -1;
            if (botCount() > 0) removeAllBots();
            return;
        }
        if (botCount() > 0 || players.size() >= MAX_PLAYERS) return;
        if (waitStart < 0) waitStart = ticks;
        long left = BotManager.FILL_AFTER_SECONDS * 20L - (ticks - waitStart);
        if (left == 60 * 20 || left == 30 * 20 || left == 10 * 20) {
            broadcast("§7Bots will fill the empty slots in " + (left / 20) + " seconds.");
        }
        if (left <= 0) fillBots();
    }

    private static void fillBots() {
        int needed = MAX_PLAYERS - players.size();
        for (Difficulty d : Difficulty.randomMix(needed, RNG)) {
            if (!addBot(d)) break;
        }
        state = State.COUNTDOWN;
        timer = FULL_COUNTDOWN_SECONDS * 20;
        broadcast("§eBots have filled the empty slots! Starting in " + FULL_COUNTDOWN_SECONDS + " seconds.");
    }

    private static boolean addBot(Difficulty difficulty) {
        if (players.size() >= MAX_PLAYERS) return false;
        BotPlayer bot;
        try {
            bot = BotManager.spawn(difficulty);
        } catch (RuntimeException e) {
            Skyserver.LOGGER.error("Skywars: couldn't create a bot", e);
            return false;
        }
        players.put(bot.getUUID(), new PlayerSnapshot(bot));
        putInCage(bot, arenaLevel());
        bot.setBrain(new SkywarsBrain(bot));
        return true; // no chat line per bot: one "bots have filled" message instead
    }

    private static void removeBot(UUID id) {
        ServerPlayer p = server.getPlayerList().getPlayer(id);
        players.remove(id);
        spawnOf.remove(id);
        alive.remove(id);
        if (p != null) BotManager.remove(p);
    }

    private static void removeAllBots() {
        for (UUID id : new ArrayList<>(players.keySet())) {
            if (BotManager.isBot(id)) removeBot(id);
        }
    }

    private static UUID anyBot() {
        for (UUID id : players.keySet()) if (BotManager.isBot(id)) return id;
        return null;
    }

    private static int botCount() {
        int n = 0;
        for (UUID id : players.keySet()) if (BotManager.isBot(id)) n++;
        return n;
    }

    private static int realCount() {
        return players.size() - botCount();
    }

    private static boolean realAlive() {
        for (UUID id : alive) if (!BotManager.isBot(id)) return true;
        return false;
    }

    // ---- Read-only info for the bot code ----

    /** Skywars has its own dimension (resources/data/skyserver/dimension/skywars.json). */
    private static ServerLevel arenaLevel() {
        ServerLevel l = server.getLevel(com.aidan.skyserver.lobby.Lobby.SKYWARS);
        return l != null ? l : server.overworld();
    }

    /** One line for the lobby portal and menu. */
    public static String lobbyStatus() {
        return switch (state) {
            case PLAYING, ENDING -> "§cMatch in progress §7(" + alive.size() + " alive)";
            default -> "§a" + players.size() + "/" + MAX_PLAYERS + " waiting";
        };
    }

    public static boolean isPlaying() {
        return state == State.PLAYING;
    }

    public static boolean isAlive(UUID id) {
        return state == State.PLAYING && alive.contains(id);
    }

    /** Changes every match, so bots know when a new one began. */
    public static int matchNumber() {
        return matchNumber;
    }

    public static List<ServerPlayer> alivePlayers() {
        List<ServerPlayer> list = new ArrayList<>();
        for (UUID id : alive) {
            ServerPlayer p = server.getPlayerList().getPlayer(id);
            if (p != null) list.add(p);
        }
        return list;
    }

    /** The three chests on this player's own spawn island (empty list if none). */
    public static List<BlockPos> ownChests(UUID id) {
        Integer spawn = spawnOf.get(id);
        return spawn == null ? List.of() : SkywarsMap.islandChests(spawn);
    }

    /** This player's spawn point. */
    public static BlockPos ownSpawn(UUID id) {
        Integer spawn = spawnOf.get(id);
        return spawn == null ? null : SkywarsMap.spawn(spawn);
    }

    /** Chest refills, the supply drop, and TNT rain, based on match time. */
    private static void runTimeline(ServerLevel arena) {
        int minute = 60 * 20;

        // One new item for everyone every 20 seconds (better items as the match goes on).
        if (timer > 0 && timer % (ITEM_EVERY_SECONDS * 20) == 0) {
            for (UUID id : alive) {
                ServerPlayer p = server.getPlayerList().getPlayer(id);
                if (p == null) continue;
                net.minecraft.world.item.ItemStack item = SkywarsLoot.dripItem(timer / 20);
                String what = SkywarsLoot.describe(item);
                if (!p.getInventory().add(item)) p.spawnAtLocation((ServerLevel) p.level(), item);
                if (!(p instanceof BotPlayer)) {
                    com.aidan.skyserver.lobby.Announce.actionBar(p, "§e+ " + what);
                    p.level().playSound(null, p.getX(), p.getY(), p.getZ(), net.minecraft.sounds.SoundEvents.ITEM_PICKUP, net.minecraft.sounds.SoundSource.PLAYERS, 0.6f, 1.3f);
                }
            }
        }

        // Supply drop warning and drop.
        if (timer == SUPPLY_DROP_MINUTE * minute - 30 * 20) {
            broadcast("§6A supply drop lands on the center island in 30 seconds!");
        }
        if (timer == SUPPLY_DROP_MINUTE * minute) {
            BlockPos drop = SkywarsMap.placeSupplyDrop(arena);
            if (arena.getBlockEntity(drop) instanceof Container c) {
                SkywarsLoot.fill(c, SkywarsLoot.Table.SUPPLY);
            }
            broadcast("§6§lSUPPLY DROP! §r§6Diamond gear on the center island!");
        }

        // TNT rain warning and rain.
        if (timer == TNT_RAIN_MINUTE * minute - 60 * 20) {
            broadcast("§cTNT starts raining in 1 minute! Finish the fight!");
        }
        if (timer == TNT_RAIN_MINUTE * minute) {
            broadcast("§4§lTNT RAIN HAS STARTED!");
        }
        if (timer > TNT_RAIN_MINUTE * minute && timer % TNT_EVERY_TICKS == 0) {
            for (UUID id : alive) {
                ServerPlayer p = server.getPlayerList().getPlayer(id);
                if (p == null) continue;
                double x = p.getX() + (RNG.nextDouble() * 8 - 4);
                double z = p.getZ() + (RNG.nextDouble() * 8 - 4);
                double y = Math.min(p.getY() + 15, 318);
                PrimedTnt tnt = new PrimedTnt(arena, x, y, z, null);
                tnt.setFuse(TNT_FUSE_TICKS);
                arena.addFreshEntity(tnt);
            }
        }
    }

    private static void fillAllChests(ServerLevel arena) {
        SkywarsLoot.Table[] island = {SkywarsLoot.Table.ISLAND_SPAWN, SkywarsLoot.Table.ISLAND_HUT, SkywarsLoot.Table.ISLAND_LEDGE};
        for (int i = 0; i < SkywarsMap.SPAWN_COUNT; i++) {
            List<BlockPos> chests = SkywarsMap.islandChests(i);
            for (int k = 0; k < chests.size(); k++)
                if (arena.getBlockEntity(chests.get(k)) instanceof Container c) SkywarsLoot.fill(c, island[Math.min(k, 2)]);
        }
        for (BlockPos pos : SkywarsMap.midChests())
            if (arena.getBlockEntity(pos) instanceof Container c) SkywarsLoot.fill(c, SkywarsLoot.Table.MID);
        for (BlockPos pos : SkywarsMap.centerChests())
            if (arena.getBlockEntity(pos) instanceof Container c) SkywarsLoot.fill(c, SkywarsLoot.Table.CENTER);
    }

    private static void start() {
        ServerLevel arena = arenaLevel();
        // Map vote: switch maps if another one won (everyone is moved to the same spawn on the new map).
        int voted = VOTE.winner(SkywarsMap.themeIndex());
        if (voted != SkywarsMap.themeIndex()) {
            SkywarsMap.setTheme(voted);
            SkywarsMap.rebuild(arena);
            for (UUID id : players.keySet()) {
                ServerPlayer p = server.getPlayerList().getPlayer(id);
                Integer sp = spawnOf.get(id);
                if (p == null || sp == null) continue;
                BlockPos s = SkywarsMap.spawn(sp);
                p.teleportTo(arena, s.getX() + 0.5, s.getY(), s.getZ() + 0.5, Set.of(), p.getYRot(), 0f, true);
            }
            broadcast("§eThe vote picked §f" + SkywarsMap.theme().display + "§e!");
        }
        state = State.PLAYING;
        timer = 0;
        startedWith = players.size();
        botsAtStart = botCount();
        matchNumber++;
        alive.clear();
        alive.addAll(players.keySet());

        SkywarsMap.setCages(arena, false);
        BridgeEggs.clearAll();
        fillAllChests(arena);

        for (UUID id : alive) {
            ServerPlayer p = server.getPlayerList().getPlayer(id);
            if (p == null) continue;
            p.setGameMode(GameType.SURVIVAL);
            resetVitals(p);
            if (startedWith > 1) com.aidan.skyserver.lobby.Rewards.matchStart(p);
        }
        broadcast("§a§lGO! §r§aLast player standing wins! §7(" + SkywarsMap.theme().display + ")");
        com.aidan.skyserver.lobby.Announce.go(onlinePlayers(), "§a§lGO!", "§7Last player standing wins");
    }

    private static void eliminate(ServerPlayer player, String message, boolean dropItems) {
        if (!alive.remove(player.getUUID())) return;
        if (player instanceof BotPlayer b && b.brain() instanceof SkywarsBrain sb) sb.onPlaced(alive.size() + 1, startedWith);
        if (!(player instanceof BotPlayer)) placementPay(player, alive.size() + 1);
        if (dropItems) {
            player.getInventory().dropAll();
        } else {
            player.getInventory().clearContent();
        }
        player.setGameMode(GameType.SPECTATOR);
        BlockPos spec = SkywarsMap.spectatorPoint();
        player.teleportTo(arenaLevel(), spec.getX() + 0.5, spec.getY(), spec.getZ() + 0.5,
                Set.of(), player.getYRot(), 30f, true);
        resetVitals(player);

        broadcast("§c" + message + " §7(" + alive.size() + " left)");
        checkForWinner();
        if (state == State.PLAYING && botsAtStart > 0 && !realAlive()) {
            broadcast("§7No real players are left alive, so the match is over.");
            endMatch(null);
        }
    }

    private static void checkForWinner() {
        if (state != State.PLAYING) return;
        if (alive.isEmpty()) {
            endMatch(null);
        } else if (alive.size() == 1 && startedWith > 1) {
            endMatch(alive.iterator().next());
        }
    }

    /** Placement pay when a real player is out (2nd and 3rd), plus a little for playing. */
    private static void placementPay(ServerPlayer p, int place) {
        if (startedWith < 2 || state != State.PLAYING) return;
        double scale = com.aidan.skyserver.lobby.Rewards.skywarsBotScale(botsAtStart);
        if (timer >= 60 * 20) {  // played at least a minute (no pay for jumping off at the start)
            com.aidan.skyserver.lobby.Rewards.reward(server, p, com.aidan.skyserver.lobby.Rewards.SW_PLAY, com.aidan.skyserver.lobby.Rewards.SW_PLAY_XP, "for playing");
            com.aidan.skyserver.lobby.Quests.progress(p, "sw_play", 1);
        }
        if (startedWith >= 3 && place == 2) com.aidan.skyserver.lobby.Rewards.reward(server, p, Math.round(com.aidan.skyserver.lobby.Rewards.SW_SECOND * scale), com.aidan.skyserver.lobby.Rewards.SW_SECOND_XP, "for 2nd place");
        if (startedWith >= 4 && place == 3) com.aidan.skyserver.lobby.Rewards.reward(server, p, Math.round(com.aidan.skyserver.lobby.Rewards.SW_THIRD * scale), com.aidan.skyserver.lobby.Rewards.SW_THIRD_XP, "for 3rd place");
    }

    private static void endMatch(UUID winnerId) {
        state = State.ENDING;
        timer = 5 * 20;

        ServerPlayer winner = winnerId == null ? null : server.getPlayerList().getPlayer(winnerId);
        if (winner instanceof BotPlayer b && b.brain() instanceof SkywarsBrain sb) sb.onPlaced(1, startedWith);
        if (winner != null) {
            String name = winner.getName().getString();
            broadcast("§6§l" + name + " wins Skywars!");
            // Win pay drops 15% for every bot in the match (never below 25%). Bots never get paid.
            if (startedWith > 1 && !BotManager.isBot(winnerId)) {
                double scale = com.aidan.skyserver.lobby.Rewards.skywarsBotScale(botsAtStart);
                String bots = botsAtStart > 0 ? " §8(" + botsAtStart + " bots in the match)" : "";
                com.aidan.skyserver.lobby.Rewards.reward(server, winner, Math.round(com.aidan.skyserver.lobby.Rewards.SW_WIN * scale) + com.aidan.skyserver.lobby.Rewards.SW_PLAY, com.aidan.skyserver.lobby.Rewards.SW_WIN_XP + com.aidan.skyserver.lobby.Rewards.SW_PLAY_XP, "for winning" + bots);
                com.aidan.skyserver.lobby.Quests.progress(winner, "sw_win", 1);
                com.aidan.skyserver.lobby.PlayerStats.add(winner, "sw_win", 1);
                com.aidan.skyserver.lobby.Skills.add(winner, com.aidan.skyserver.lobby.Skills.Skill.COMBAT, 250);
                com.aidan.skyserver.lobby.Quests.progress(winner, "sw_play", 1);
                if (com.aidan.skyserver.lobby.Rewards.firstWinToday("skywars", winnerId)) {
                    com.aidan.skyserver.lobby.Rewards.pay(server, winner, Math.round(com.aidan.skyserver.lobby.Rewards.SW_FIRST_WIN * scale), "first Skywars win of the day!");
                    // Key: Uncommon for beating real players, Common if every opponent was a bot.
                    boolean realOpponents = startedWith - 1 > botsAtStart;
                    com.aidan.skyserver.lobby.Keys.reward(server, winnerId, realOpponents ? 1 : 0, 1, "(first Skywars win today)");
                }
            }
        } else {
            broadcast("§7The match ended with no winner.");
        }
        for (ServerPlayer p : onlinePlayers()) com.aidan.skyserver.lobby.Rewards.matchEnd(p, "Skywars");
        broadcast("§7Returning to the lobby in 5 seconds...");
    }

    /** Sends everyone back, restores their stuff, and rebuilds the map. */
    private static void finish() {
        if (botsAtStart > 0) com.aidan.skyserver.bots.BotMemory.matchEnded(); // save what the bots learned
        List<UUID> bots = new ArrayList<>();
        for (UUID id : players.keySet()) if (BotManager.isBot(id)) bots.add(id);
        restoreEveryone();
        for (UUID id : bots) {
            ServerPlayer p = server.getPlayerList().getPlayer(id);
            if (p != null) BotManager.remove(p);
        }
        waitStart = -1;
        BridgeEggs.clearAll();
        SkywarsMap.nextMap(); // maps rotate every match
        SkywarsMap.rebuild(arenaLevel());
        mapReady = true;
        state = State.WAITING;
    }

    private static void restoreEveryone() {
        if (server == null) return;
        for (Map.Entry<UUID, PlayerSnapshot> e : new ArrayList<>(players.entrySet())) {
            ServerPlayer p = server.getPlayerList().getPlayer(e.getKey());
            if (p != null) e.getValue().restore(p);
        }
        players.clear();
        spawnOf.clear();
        alive.clear();
    }

    // ---------------- Helpers ----------------

    private static int freeSpawn() {
        for (int i = 0; i < MAX_PLAYERS; i++) {
            if (!spawnOf.containsValue(i)) return i;
        }
        return 0;
    }

    static void resetVitals(ServerPlayer p) {
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
            if (p != null) msg(p, "§b[Skywars] §r" + text);
        }
    }
}
