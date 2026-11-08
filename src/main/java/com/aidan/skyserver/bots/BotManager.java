package com.aidan.skyserver.bots;

import com.aidan.skyserver.Skyserver;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.storage.LevelResource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Creates and removes bots, and holds the shared bot settings. The games decide when bots join
 * (Gold Wars, Skywars and Bedwars fill empty slots after 3 minutes of waiting).
 */
public final class BotManager {
    // ---- Settings you can change ----
    public static final boolean ENABLED = true;
    /** Seconds of waiting with at least one real player before bots fill the empty slots. */
    public static final int FILL_AFTER_SECONDS = 180;
    /** Kill rewards for killing a bot are multiplied by this. */
    public static final double BOT_KILL_PAY = 0.5;
    /** Win rewards drop by this much for every bot on the other side (0.15 = 15%). */
    public static final double WIN_PAY_CUT_PER_BOT = 0.15;
    /** Most path searches per tick across all bots (keeps the server smooth). */
    private static final int PATHS_PER_TICK = 2;

    private static MinecraftServer server;
    private static final Map<UUID, BotPlayer> bots = new LinkedHashMap<>();
    private static final List<Runnable> nextTick = new ArrayList<>();
    private static final Random RNG = new Random();
    private static int pathTokens = PATHS_PER_TICK;

    private BotManager() {}

    public static void init() {
        ServerLifecycleEvents.SERVER_STARTED.register(s -> {
            server = s;
            com.aidan.skyserver.lobby.Economy.purgeBots(s); // old bot accounts off the money board
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(s -> removeAll());
        BotChatFilter.init();
        ServerTickEvents.START_SERVER_TICK.register(s -> com.aidan.skyserver.Safe.run("bots", () -> {
            pathTokens = PATHS_PER_TICK;
            // Bots get an EconomyCraft account when they log in: remove it so they're not on the money board.
            if (s.getTickCount() % 100 == 0 && !bots.isEmpty()) com.aidan.skyserver.lobby.Economy.purgeBots(s);
            if (!nextTick.isEmpty()) {
                List<Runnable> jobs = new ArrayList<>(nextTick);
                nextTick.clear();
                for (Runnable j : jobs) com.aidan.skyserver.Safe.run("bot job", j);
            }
        }));

        // Sword hits normally push a player through their own game client. Bots have none,
        // so we push them ourselves.
        ServerLivingEntityEvents.AFTER_DAMAGE.register((entity, source, baseDamage, damageTaken, blocked) -> {
            if (!(entity instanceof BotPlayer bot) || damageTaken <= 0) return;
            if (!(source.getEntity() instanceof Player attacker) || source.getDirectEntity() != attacker) return;
            double strength = 0.4 + (attacker.isSprinting() ? 0.5 : 0);
            double dx = attacker.getX() - bot.getX();
            double dz = attacker.getZ() - bot.getZ();
            nextTick.add(() -> {
                if (bot.isRemoved()) return;
                // Same push as vanilla knockback: away from the attacker, and a little up.
                double len = Math.sqrt(dx * dx + dz * dz);
                if (len < 1e-4) return;
                var v = bot.getDeltaMovement();
                bot.setDeltaMovement(v.x / 2 - dx / len * strength,
                        bot.onGround() ? Math.min(0.4, v.y / 2 + strength) : v.y,
                        v.z / 2 - dz / len * strength);
            });
        });

        // /bots (admins): list bots, or remove them all.
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> dispatcher.register(
                Commands.literal("bots").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .executes(c -> {
                            StringBuilder sb = new StringBuilder("Bots online: " + bots.size());
                            for (BotPlayer b : bots.values()) {
                                sb.append("\n - ").append(b.getName().getString()).append(" (").append(b.difficulty().name().toLowerCase()).append(")");
                            }
                            c.getSource().sendSuccess(() -> Component.literal(sb.toString()), false);
                            return 1;
                        })
                        .then(Commands.literal("debug").executes(c -> {
                            // What every bot is doing right now, and what each team's commander thinks.
                            StringBuilder sb = new StringBuilder("§eBots:");
                            for (com.aidan.skyserver.goldwars.GwTeam t : com.aidan.skyserver.goldwars.GwTeam.values()) {
                                if (com.aidan.skyserver.goldwars.GoldWarsBots.inMatch())
                                    sb.append("\n§6").append(t.display).append(" commander: §f").append(GoldWarsCommander.of(t).situation());
                            }
                            for (BotPlayer b : bots.values()) {
                                String what = b.brain() == null ? "" : b.brain().describe();
                                var team = com.aidan.skyserver.goldwars.GoldWarsBots.team(b.getUUID());
                                var bwTeam = com.aidan.skyserver.bedwars.BedwarsBots.team(b.getUUID());
                                sb.append("\n§7").append(team == null ? "" : team.display + " ").append(bwTeam == null ? "" : "[Bedwars] ").append("§f").append(b.getName().getString())
                                        .append(" §8(").append(b.difficulty().name().toLowerCase()).append(")§7: ").append(what);
                            }
                            c.getSource().sendSuccess(() -> Component.literal(sb.toString()), false);
                            return 1;
                        }))
                        .then(Commands.literal("routes").executes(c -> {
                            // What the bots have learned about each route on the current Gold Wars map.
                            String map = com.aidan.skyserver.goldwars.GoldWarsBots.mapId();
                            c.getSource().sendSuccess(() -> Component.literal("§eRoute memory for " + map + ":" + LaneMemory.summary(map)), false);
                            return 1;
                        }))
                        .then(Commands.literal("learned")
                                .executes(c -> {
                                    // Everything the bots have learned so far.
                                    StringBuilder sb = new StringBuilder("§eWhat the bots have learned:");
                                    for (String map : new String[]{"sky", "village", "caves"}) {
                                        sb.append("\n§6Gold Wars - ").append(map).append(":").append(GwLearning.summary(map));
                                        sb.append("\n§7 Routes for bot attackers:").append(LaneMemory.summary(map));
                                    }
                                    sb.append("\n§6Skywars:").append(SwLearning.summary());
                                    sb.append(BwLearning.summary());
                                    sb.append(MiniLearning.summary());
                                    c.getSource().sendSuccess(() -> Component.literal(sb.toString()), false);
                                    return 1;
                                })
                                .then(Commands.literal("reset").executes(c -> {
                                    BotMemory.reset();
                                    LaneMemory.reset();
                                    c.getSource().sendSuccess(() -> Component.literal("§eBots have forgotten everything they learned."), true);
                                    return 1;
                                })))
                        .then(Commands.literal("test").executes(c -> {
                            // Diagnostics: why can't a bot be hurt? Tests the nearest bot.
                            ServerPlayer me = c.getSource().getPlayerOrException();
                            BotPlayer nearest = null;
                            for (BotPlayer b : bots.values()) {
                                if (b.level() != me.level()) continue;
                                if (nearest == null || b.distanceTo(me) < nearest.distanceTo(me)) nearest = b;
                            }
                            if (nearest == null) {
                                c.getSource().sendFailure(Component.literal("No bot in this world."));
                                return 0;
                            }
                            String report = diagnose(nearest, me);
                            Skyserver.LOGGER.info("Bot test report:\n{}", report);
                            c.getSource().sendSuccess(() -> Component.literal(report), false);
                            return 1;
                        }))
                        .then(Commands.literal("removeall").executes(c -> {
                            int n = bots.size();
                            removeAll();
                            c.getSource().sendSuccess(() -> Component.literal("Removed " + n + " bots."), true);
                            return 1;
                        }))));

        Skyserver.LOGGER.info("Bots ready (fill after {}s of waiting)", FILL_AFTER_SECONDS);
    }

    private static String diagnose(BotPlayer bot, ServerPlayer me) {
        StringBuilder sb = new StringBuilder("Bot test: " + bot.getName().getString());
        sb.append("\n distance ").append(String.format("%.1f", bot.distanceTo(me)))
          .append(", mode ").append(bot.gameMode.getGameModeForPlayer())
          .append(", health ").append(bot.getHealth())
          .append(", invulnerable flag ").append(bot.isInvulnerable())
          .append(", creative-style ").append(bot.getAbilities().invulnerable);
        // Any flags that sound like they could block damage.
        String[] keys = {"load", "dimension", "invul", "spawn", "teleport", "awaiting"};
        dumpFields(sb, "player", bot, net.minecraft.server.level.ServerPlayer.class, keys);
        dumpFields(sb, "conn", bot.connection, bot.connection.getClass(), keys);
        // Now actually try to hurt it, as if this player hit it.
        float before = bot.getHealth();
        boolean hurt;
        try {
            hurt = bot.hurtServer((net.minecraft.server.level.ServerLevel) bot.level(), me.damageSources().playerAttack(me), 1f);
        } catch (RuntimeException e) {
            hurt = false;
            sb.append("\n hurt threw ").append(e);
        }
        sb.append("\n test hit: accepted=").append(hurt).append(", health ").append(before).append(" -> ").append(bot.getHealth());
        return sb.toString();
    }

    private static void dumpFields(StringBuilder sb, String label, Object target, Class<?> top, String[] keys) {
        for (Class<?> c = top; c != null && c != Object.class; c = c.getSuperclass()) {
            for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                if (f.getType() != boolean.class && f.getType() != int.class && f.getType() != long.class) continue;
                String n = f.getName().toLowerCase(Locale.ROOT);
                boolean match = false;
                for (String k : keys) if (n.contains(k)) match = true;
                if (!match) continue;
                try {
                    f.setAccessible(true);
                    sb.append("\n ").append(label).append('.').append(c.getSimpleName()).append('.').append(f.getName())
                      .append(" = ").append(f.get(target));
                } catch (ReflectiveOperationException | RuntimeException e) {
                    sb.append("\n ").append(label).append('.').append(f.getName()).append(" = ?");
                }
            }
        }
    }

    /** Logs in a new bot with a random name. */
    public static BotPlayer spawn(Difficulty difficulty) {
        Set<String> taken = new HashSet<>();
        for (ServerPlayer p : server.getPlayerList().getPlayers()) taken.add(p.getName().getString());
        String name = BotNames.next(taken);
        BotChatFilter.botName(name);
        BotPlayer bot = BotPlayer.create(server, name, difficulty);
        bots.put(bot.getUUID(), bot);
        return bot;
    }

    /** Logs a bot out and deletes its save file. */
    public static void remove(ServerPlayer player) {
        if (!(player instanceof BotPlayer bot)) return;
        if (!bots.containsKey(bot.getUUID())) return;
        UUID id = bot.getUUID();
        bot.logout();
        bots.remove(id);
        nextTick.add(() -> deleteSaveFile(id));
    }

    public static void removeAll() {
        for (BotPlayer bot : new ArrayList<>(bots.values())) remove(bot);
    }

    public static boolean isBot(UUID id) {
        return bots.containsKey(id);
    }

    public static boolean isBotEntity(Entity e) {
        return e instanceof BotPlayer;
    }

    public static Difficulty randomDifficulty() {
        Difficulty[] all = Difficulty.values();
        return all[RNG.nextInt(all.length)];
    }

    /** Pathfinding is the expensive part, so only a couple of searches run per tick. */
    static boolean takePathToken() {
        if (pathTokens <= 0) return false;
        pathTokens--;
        return true;
    }

    private static void deleteSaveFile(UUID id) {
        if (server == null) return;
        try {
            Path dir = server.getWorldPath(LevelResource.PLAYER_DATA_DIR);
            Files.deleteIfExists(dir.resolve(id + ".dat"));
            Files.deleteIfExists(dir.resolve(id + ".dat_old"));
        } catch (Exception ignored) {
            // Not important if it fails.
        }
    }
}
