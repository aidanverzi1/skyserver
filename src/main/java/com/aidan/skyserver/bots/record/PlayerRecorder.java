package com.aidan.skyserver.bots.record;

import com.aidan.skyserver.Skyserver;
import com.aidan.skyserver.bedwars.BedwarsBots;
import com.aidan.skyserver.bedwars.BedwarsGame;
import com.aidan.skyserver.bedwars.BwMap;
import com.aidan.skyserver.bedwars.BwTeam;
import com.aidan.skyserver.bots.BotManager;
import com.aidan.skyserver.bots.BotPlayer;
import com.aidan.skyserver.goldwars.GoldWarsBots;
import com.aidan.skyserver.goldwars.GoldWarsGame;
import com.aidan.skyserver.goldwars.GwTeam;
import com.aidan.skyserver.lobby.Duels;
import com.aidan.skyserver.lobby.Lobby;
import com.aidan.skyserver.lobby.Stacks;
import com.aidan.skyserver.skywars.SkywarsGame;
import com.aidan.skyserver.skywars.SkywarsMap;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.commands.Commands;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import java.util.*;

/**
 * Records how real players move, aim and fight in the minigames, so the bots can later learn to
 * play like people (see BOT-AI-PLAN.md, phase 2). It only watches: nothing here changes a game or a bot.
 *
 * Who: real players only (never bots), in a Bedwars, Gold Wars or Skywars match, or a lobby duel.
 *      Players can opt out with /recording off. Everyone is told once when they join.
 * What: position, look direction, movement keys (worked out from movement), sprint/sneak/jump,
 *       hand item, health, the nearest enemy, and events (swings, hits, damage, bow, eating, throws,
 *       blocks placed/broken, kills, deaths). Only the busy moments are kept (see RecordingSession).
 * Where: config/skyserver-recordings/*.json.gz (see RecordingWriter). Settings: config/skyserver/recording.json.
 * Ops: /record on|off|status.
 */
public final class PlayerRecorder {
    private static final String SETTINGS = "recording.json";
    /** An enemy this close means "fighting", worth keeping. */
    private static final double FIGHT_RANGE = 10.0;
    /** Nearest enemy within this range is written into each frame. */
    private static final double TARGET_RANGE = 16.0;
    /** Long recordings are saved in parts of this many kept frames (about 30 minutes of action). */
    private static final int MAX_FRAMES_PER_FILE = 36_000;

    private static boolean enabled = true;
    private static final Set<UUID> optOut = new HashSet<>();
    private static final Set<UUID> told = new HashSet<>();
    private static final Map<UUID, Live> LIVE = new HashMap<>();
    private static boolean swingHookWorks = false;
    private static int savedThisRun = 0;

    /** What we remember about a player between ticks. */
    private static final class Live {
        final String game;
        final Level level;
        RecordingSession session;
        int part = 1;
        double lastX, lastY, lastZ;
        boolean first = true, wasOnGround = true, wasUsing = false;
        int useItemCode = 0, useTicks = 0, lastSlot = -1, lastCount = 0, lastItemCode = 0;
        net.minecraft.world.item.Item lastItem = null;

        Live(String game, Level level, RecordingSession session) {
            this.game = game;
            this.level = level;
            this.session = session;
        }
    }

    private PlayerRecorder() {}

    // =====================================================================
    // Setup
    // =====================================================================

    /** Call first in Skyserver.onInitialize, so the death/damage watchers run before the games change anything. */
    public static void init() {
        loadSettings();
        ServerLifecycleEvents.SERVER_STOPPING.register(s -> {
            for (UUID id : new ArrayList<>(LIVE.keySet())) end(id, true);
            RecordingWriter.shutdown();
        });
        ServerTickEvents.END_SERVER_TICK.register(s -> com.aidan.skyserver.Safe.run("player recorder", () -> tick(s)));

        ServerPlayConnectionEvents.JOIN.register((handler, sender, s) -> {
            ServerPlayer p = handler.getPlayer();
            if (p instanceof BotPlayer || !enabled || told.contains(p.getUUID())) return;
            told.add(p.getUUID());
            saveSettings();
            p.sendSystemMessage(Component.literal("§7Heads up: minigame matches are recorded (movement, aim and clicks only) "
                    + "to teach the bots to play like real people. Type §e/recording off §7to opt out."));
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, s) -> end(handler.getPlayer().getUUID(), true));

        // Damage dealt and taken (watch only).
        ServerLivingEntityEvents.AFTER_DAMAGE.register((entity, source, baseDamage, damageTaken, blocked) -> com.aidan.skyserver.Safe.run("recorder damage", () -> {
            if (damageTaken <= 0) return;
            Entity attacker = source.getEntity();
            if (attacker != null && attacker != entity) {
                Live a = LIVE.get(attacker.getUUID());
                if (a != null) {
                    boolean shot = source.getDirectEntity() instanceof Projectile;
                    event(a, shot ? CombatEvent.Kind.SHOT_HIT : CombatEvent.Kind.HIT, entity.getId(), damageTaken);
                }
            }
            Live v = LIVE.get(entity.getUUID());
            if (v != null) event(v, CombatEvent.Kind.HURT, attacker == null ? -1 : attacker.getId(), damageTaken);
        }));

        // Deaths. The games catch deaths with ALLOW_DEATH (and cancel them), so we watch that too.
        // Registered first and always says "allow", so it never changes what the games decide.
        ServerLivingEntityEvents.ALLOW_DEATH.register((entity, source, amount) -> {
            com.aidan.skyserver.Safe.run("recorder death", () -> {
                Entity killer = source.getEntity();
                Live v = LIVE.get(entity.getUUID());
                if (v != null) event(v, CombatEvent.Kind.DEATH, killer == null ? -1 : killer.getId(), 0);
                if (killer != null && killer != entity) {
                    Live k = LIVE.get(killer.getUUID());
                    if (k != null) event(k, CombatEvent.Kind.KILL, entity.getId(), 0);
                }
            });
            return true;
        });

        PlayerBlockBreakEvents.AFTER.register((level, player, pos, state, be) -> {
            Live l = LIVE.get(player.getUUID());
            if (l != null) event(l, CombatEvent.Kind.BREAK, -1, 0);
        });

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            dispatcher.register(Commands.literal("record").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                    .executes(c -> {
                        c.getSource().sendSuccess(() -> Component.literal(status()), false);
                        return 1;
                    })
                    .then(Commands.literal("status").executes(c -> {
                        c.getSource().sendSuccess(() -> Component.literal(status()), false);
                        return 1;
                    }))
                    .then(Commands.literal("on").executes(c -> {
                        enabled = true;
                        saveSettings();
                        c.getSource().sendSuccess(() -> Component.literal("§aRecording is ON §7(real players in minigames, unless they opted out)."), true);
                        return 1;
                    }))
                    .then(Commands.literal("off").executes(c -> {
                        enabled = false;
                        for (UUID id : new ArrayList<>(LIVE.keySet())) end(id, true);
                        saveSettings();
                        c.getSource().sendSuccess(() -> Component.literal("§eRecording is OFF. §7What was recorded so far was saved."), true);
                        return 1;
                    })));
            dispatcher.register(Commands.literal("recording")
                    .executes(c -> {
                        ServerPlayer p = c.getSource().getPlayerOrException();
                        p.sendSystemMessage(Component.literal(playerStatus(p.getUUID())));
                        return 1;
                    })
                    .then(Commands.literal("off").executes(c -> {
                        ServerPlayer p = c.getSource().getPlayerOrException();
                        optOut.add(p.getUUID());
                        end(p.getUUID(), false); // throw away anything from this match
                        saveSettings();
                        p.sendSystemMessage(Component.literal("§eYou won't be recorded any more. §7(/recording on to help train the bots again.)"));
                        return 1;
                    }))
                    .then(Commands.literal("on").executes(c -> {
                        ServerPlayer p = c.getSource().getPlayerOrException();
                        optOut.remove(p.getUUID());
                        saveSettings();
                        p.sendSystemMessage(Component.literal("§aThanks! §7Your minigame matches will help train the bots."));
                        return 1;
                    })));
        });
        Skyserver.LOGGER.info("Player recorder ready ({})", enabled ? "on" : "off");
    }

    // =====================================================================
    // Every tick
    // =====================================================================

    private static void tick(MinecraftServer s) {
        if (!enabled) return;
        for (ServerPlayer p : s.getPlayerList().getPlayers()) {
            UUID id = p.getUUID();
            if (p instanceof BotPlayer || optOut.contains(id)) {
                if (LIVE.containsKey(id)) end(id, !optOut.contains(id));
                continue;
            }
            String game = gameOf(p);
            Live l = LIVE.get(id);
            if (game == null) {
                if (l != null) end(id, true);
                continue;
            }
            if (l == null || !l.game.equals(game) || l.level != p.level()) {
                if (l != null) end(id, true);
                l = new Live(game, p.level(), new RecordingSession(id, p.getName().getString(), game, 1));
                LIVE.put(id, l);
            }
            sample(p, l);
        }
    }

    private static void sample(ServerPlayer p, Live l) {
        RecordingSession s = l.session;
        int t = s.tick();
        double x = p.getX(), y = p.getY(), z = p.getZ();
        double dx = l.first ? 0 : x - l.lastX, dy = l.first ? 0 : y - l.lastY, dz = l.first ? 0 : z - l.lastZ;
        boolean teleported = Math.sqrt(dx * dx + dy * dy + dz * dz) > InputInference.TELEPORT_DIST;
        if (teleported) dx = dy = dz = 0;
        l.lastX = x;
        l.lastY = y;
        l.lastZ = z;
        l.first = false;

        boolean spectating = p.isSpectator() || !p.isAlive();
        boolean ground = p.onGround();
        boolean jumped = !teleported && InputInference.jumped(l.wasOnGround, ground, dy);
        l.wasOnGround = ground;
        float[] keys = spectating ? new float[2] : InputInference.forwardStrafe(dx, dz, p.getYRot());

        // ---- Hand item: right-click use, throws, block placing ----
        ItemStack held = p.getMainHandItem();
        int code = itemCode(held);
        int slot = p.getInventory().getSelectedSlot();
        boolean using = p.isUsingItem();
        boolean trigger = false;
        if (using && !l.wasUsing) {
            int useCode = itemCode(p.getUseItem());
            l.useItemCode = useCode;
            CombatEvent.Kind kind = useCode == ITEM_BOW || useCode == ITEM_CROSSBOW ? CombatEvent.Kind.BOW_DRAW
                    : useCode == ITEM_FOOD ? CombatEvent.Kind.EAT : CombatEvent.Kind.ITEM_USE;
            event(l, kind, -1, useCode);
        } else if (!using && l.wasUsing) {
            if (l.useItemCode == ITEM_BOW || l.useItemCode == ITEM_CROSSBOW) event(l, CombatEvent.Kind.BOW_RELEASE, -1, l.useTicks);
        }
        if (using) l.useTicks++;
        else l.useTicks = 0;
        l.wasUsing = using;
        // Same slot, same item, one fewer: placed a block or threw something.
        if (slot == l.lastSlot && held.getItem() == l.lastItem && held.getCount() == l.lastCount - 1) {
            if (l.lastItemCode == ITEM_BLOCK) event(l, CombatEvent.Kind.PLACE, -1, 0);
            else if (l.lastItemCode == ITEM_THROWABLE) event(l, CombatEvent.Kind.THROW, -1, l.lastItemCode);
        } else if (l.lastItem != null && slot == l.lastSlot && held.isEmpty() && l.lastCount == 1) {
            // Used the last one up.
            if (l.lastItemCode == ITEM_BLOCK) event(l, CombatEvent.Kind.PLACE, -1, 0);
            else if (l.lastItemCode == ITEM_THROWABLE) event(l, CombatEvent.Kind.THROW, -1, l.lastItemCode);
        }
        l.lastSlot = slot;
        l.lastItem = held.isEmpty() ? null : held.getItem();
        l.lastCount = held.getCount();
        l.lastItemCode = code;

        // ---- Nearest enemy ----
        ServerPlayer target = spectating ? null : nearestEnemy(p, l.game);
        float tdx = 0, tdy = 0, tdz = 0, thp = 0;
        int targetId = -1;
        int flags = 0;
        if (target != null) {
            targetId = target.getId();
            tdx = (float) (target.getX() - x);
            tdy = (float) (target.getY() - y);
            tdz = (float) (target.getZ() - z);
            thp = target.getHealth();
            if (target instanceof BotPlayer || BotManager.isBot(target.getUUID())) flags |= FrameSample.TARGET_IS_BOT;
            if (Math.sqrt(tdx * tdx + tdy * tdy + tdz * tdz) <= FIGHT_RANGE) trigger = true;
        }
        if (using) trigger = true;

        if (ground) flags |= FrameSample.ON_GROUND;
        if (p.isSprinting()) flags |= FrameSample.SPRINTING;
        if (p.isShiftKeyDown()) flags |= FrameSample.SNEAKING;
        if (jumped) flags |= FrameSample.JUMPED;
        if (using) flags |= FrameSample.USING_ITEM;
        if (teleported) flags |= FrameSample.TELEPORTED;
        if (p.isInWater()) flags |= FrameSample.IN_WATER;

        if (spectating) {
            // Dead or watching: count the time but don't keep frames.
            s.offer(new FrameSample(t, (float) x, (float) y, (float) z, p.getYRot(), p.getXRot(), 0, 0, flags | FrameSample.TELEPORTED,
                    slot, code, 0, -1, 0, 0, 0, 0), false);
        } else {
            s.offer(new FrameSample(t, (float) x, (float) y, (float) z, p.getYRot(), p.getXRot(), keys[0], keys[1], flags,
                    slot, code, p.getHealth(), targetId, tdx, tdy, tdz, thp), trigger);
        }

        if (s.needsMap()) {
            String[] mm = mapOf(l.game);
            s.setMap(mm[0], mm[1]);
        }
        if (s.keptFrames() >= MAX_FRAMES_PER_FILE) {
            // Very long match: save this part and carry on in a new file.
            save(s);
            l.part++;
            RecordingSession next = new RecordingSession(s.player, s.name, s.game, l.part);
            next.setMap(s.map, s.mode);
            l.session = next;
        }
    }

    // =====================================================================
    // Hooks
    // =====================================================================

    /** From the swing mixin: the player swung their arm (any left click). */
    public static void onSwing(ServerPlayer p) {
        try {
            swingHookWorks = true;
            Live l = LIVE.get(p.getUUID());
            if (l != null) event(l, CombatEvent.Kind.SWING, -1, itemCode(p.getMainHandItem()));
        } catch (RuntimeException e) {
            // never break packet handling
        }
    }

    private static void event(Live l, CombatEvent.Kind kind, int other, float value) {
        l.session.event(new CombatEvent(l.session.tick(), kind, other, value));
    }

    // =====================================================================
    // Which game, which map, who's an enemy
    // =====================================================================

    /** The minigame this real player is playing right now, or null. */
    static String gameOf(ServerPlayer p) {
        UUID id = p.getUUID();
        Level level = p.level();
        if (level.dimension() == BwMap.DIM && BedwarsGame.isInGame(id)) return "bedwars";
        if (GoldWarsGame.isInGame(id) && level == GoldWarsBots.level()) return "goldwars";
        if (level.dimension() == Lobby.SKYWARS && SkywarsGame.isInGame(id)) return "skywars";
        if (Lobby.isLobby(level) && Duels.inDuel(id)) return "duel";
        return null;
    }

    /** {map, mode} for the game right now. */
    private static String[] mapOf(String game) {
        try {
            return switch (game) {
                case "bedwars" -> new String[]{BedwarsBots.mapId(), BedwarsBots.mode().name().toLowerCase(Locale.ROOT)};
                case "goldwars" -> new String[]{GoldWarsBots.mapId(), ""};
                case "skywars" -> new String[]{SkywarsMap.theme().id, ""};
                case "duel" -> new String[]{"pit", ""};
                default -> new String[]{"unknown", ""};
            };
        } catch (RuntimeException e) {
            return new String[]{"unknown", ""};
        }
    }

    private static ServerPlayer nearestEnemy(ServerPlayer me, String game) {
        ServerPlayer best = null;
        double bestD = TARGET_RANGE * TARGET_RANGE;
        for (ServerPlayer e : ((net.minecraft.server.level.ServerLevel) me.level()).players()) {
            if (e == me || e.isSpectator() || !e.isAlive()) continue;
            if (!isEnemy(me, e, game)) continue;
            double d = e.distanceToSqr(me);
            if (d < bestD) {
                bestD = d;
                best = e;
            }
        }
        return best;
    }

    private static boolean isEnemy(ServerPlayer me, ServerPlayer e, String game) {
        return switch (game) {
            case "bedwars" -> {
                BwTeam a = BedwarsBots.team(me.getUUID()), b = BedwarsBots.team(e.getUUID());
                yield b != null && a != b;
            }
            case "goldwars" -> {
                GwTeam a = GoldWarsBots.team(me.getUUID()), b = GoldWarsBots.team(e.getUUID());
                yield b != null && a != b;
            }
            case "skywars" -> SkywarsGame.isInGame(e.getUUID());
            case "duel" -> Duels.canHit(me, e);
            default -> false;
        };
    }

    // =====================================================================
    // Item codes (what's in the hand), shared with the profile builder later
    // =====================================================================

    public static final int ITEM_EMPTY = 0, ITEM_SWORD = 1, ITEM_AXE = 2, ITEM_BOW = 3, ITEM_CROSSBOW = 4, ITEM_BLOCK = 5,
            ITEM_FOOD = 6, ITEM_THROWABLE = 7, ITEM_TOOL = 8, ITEM_SHIELD = 9, ITEM_OTHER = 10;

    public static int itemCode(ItemStack s) {
        if (s == null || s.isEmpty()) return ITEM_EMPTY;
        String id = BuiltInRegistries.ITEM.getKey(s.getItem()).getPath();
        if (id.endsWith("_sword")) return ITEM_SWORD;
        if (id.endsWith("_axe")) return ITEM_AXE;
        if (id.equals("bow")) return ITEM_BOW;
        if (id.equals("crossbow")) return ITEM_CROSSBOW;
        if (id.equals("shield")) return ITEM_SHIELD;
        if (id.endsWith("_pickaxe") || id.endsWith("_shovel") || id.endsWith("_hoe") || id.equals("shears")) return ITEM_TOOL;
        if (id.equals("snowball") || id.equals("egg") || id.equals("ender_pearl") || id.equals("fire_charge")
                || id.endsWith("splash_potion") || id.endsWith("lingering_potion") || id.equals("wind_charge")) return ITEM_THROWABLE;
        if (s.has(DataComponents.FOOD) || id.equals("potion") || id.equals("milk_bucket")) return ITEM_FOOD;
        if (s.getItem() instanceof BlockItem) return ITEM_BLOCK;
        return ITEM_OTHER;
    }

    // =====================================================================
    // Saving
    // =====================================================================

    /** Stops recording a player; save = write the file (false = throw it away). */
    private static void end(UUID id, boolean save) {
        Live l = LIVE.remove(id);
        if (l != null && save) save(l.session);
    }

    private static void save(RecordingSession s) {
        if (RecordingWriter.writeAsync(s, msg -> Skyserver.LOGGER.warn("[SkyServer] {}", msg))) savedThisRun++;
    }

    private static String status() {
        long[] st = RecordingWriter.stats();
        StringBuilder sb = new StringBuilder("§6§lRecording: ").append(enabled ? "§aON" : "§cOFF");
        sb.append("\n§7Recording now: §f").append(LIVE.size()).append(" player").append(LIVE.size() == 1 ? "" : "s");
        for (Live l : LIVE.values()) {
            sb.append("\n §7- §f").append(l.session.name).append(" §7(").append(l.game).append(", ")
                    .append(l.session.keptFrames() / 20).append("s kept, ").append(l.session.eventCount()).append(" events)");
        }
        sb.append("\n§7Saved this run: §f").append(savedThisRun).append(" §7| Files: §f").append(st[0])
                .append(" §7(").append(String.format(Locale.ROOT, "%.1f", st[1] / 1048576.0)).append(" MB of ").append(RecordingWriter.KEEP_MB).append(")");
        sb.append("\n§7Opted out: §f").append(optOut.size());
        sb.append("\n§7Swing hook: ").append(swingHookWorks ? "§aworking" : "§enot seen yet §7(swing in a game, then check again)");
        return sb.toString();
    }

    private static String playerStatus(UUID id) {
        if (!enabled) return "§7Recording is turned off on this server right now.";
        if (optOut.contains(id)) return "§7You're opted out: your matches aren't recorded. §e/recording on §7to help train the bots.";
        return "§7Your minigame matches are recorded (movement, aim and clicks only) to train the bots. §e/recording off §7to opt out.";
    }

    // =====================================================================
    // Settings file (config/skyserver/recording.json)
    // =====================================================================

    private static void loadSettings() {
        JsonObject o = Stacks.readFile(SETTINGS);
        if (o.has("enabled")) enabled = o.get("enabled").getAsBoolean();
        readSet(o, "optOut", optOut);
        readSet(o, "told", told);
    }

    private static void saveSettings() {
        JsonObject o = new JsonObject();
        o.addProperty("enabled", enabled);
        o.add("optOut", writeSet(optOut));
        o.add("told", writeSet(told));
        Stacks.writeFile(SETTINGS, o);
    }

    private static void readSet(JsonObject o, String key, Set<UUID> into) {
        if (!o.has(key) || !o.get(key).isJsonArray()) return;
        for (JsonElement e : o.getAsJsonArray(key)) {
            try {
                into.add(UUID.fromString(e.getAsString()));
            } catch (RuntimeException ignored) {
            }
        }
    }

    private static JsonArray writeSet(Set<UUID> set) {
        JsonArray a = new JsonArray();
        for (UUID id : set) a.add(id.toString());
        return a;
    }
}
