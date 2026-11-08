package com.aidan.skyserver.bots;

import com.aidan.skyserver.Skyserver;
import com.mojang.authlib.GameProfile;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * A bot: a real server player with nobody behind it. It shows up in the tab list, wears its
 * kit, takes damage and dies like anyone else, so the games treat it like a normal player.
 * The server doesn't normally move players (their game client does), so we run the bot's
 * movement and physics ourselves every tick.
 */
public final class BotPlayer extends ServerPlayer {
    private final FakeConnection fake;
    private Difficulty difficulty;
    private BotBrain brain;
    private boolean loggedError = false;

    private BotPlayer(MinecraftServer server, ServerLevel level, GameProfile profile, Difficulty difficulty, FakeConnection fake) {
        super(server, level, profile, ClientInformation.createDefault());
        this.difficulty = difficulty;
        this.fake = fake;
    }

    /** Logs a new bot into the server. */
    static BotPlayer create(MinecraftServer server, String name, Difficulty difficulty) {
        // Same name -> same id, so the server doesn't pile up save files for bots.
        UUID id = UUID.nameUUIDFromBytes(("skyserver-bot:" + name.toLowerCase(Locale.ROOT)).getBytes(StandardCharsets.UTF_8));
        GameProfile profile = new GameProfile(id, name);
        FakeConnection connection = new FakeConnection();
        BotPlayer bot = new BotPlayer(server, server.overworld(), profile, difficulty, connection);
        server.getPlayerList().placeNewPlayer(connection, bot, CommonListenerCookie.createInitial(profile, false));
        return bot;
    }

    /** Logs the bot out (shows "left the game" like a normal player). */
    void logout() {
        brain = null;
        connection.onDisconnect(new DisconnectionDetails(Component.literal("Bot removed")));
    }

    // =====================================================================
    // Pretending a game client is attached
    // =====================================================================
    //
    // A real player is invulnerable until their game client says "I've loaded in" (and again after
    // every dimension change until it confirms the teleport). Bots have no client, so without this
    // they'd be unhittable forever. The server also streams map chunks to players, which bots don't
    // need (and which made other mods throw errors). The method/field names below are Mojang's;
    // they are looked up once at runtime so a rename can't break the build.

    private static boolean scanned = false;
    private static final List<Method> PLAYER_CALLS = new ArrayList<>();
    private static final List<Method> CONNECTION_CALLS = new ArrayList<>();
    private static final List<Field> PLAYER_TRUE = new ArrayList<>();
    private static final List<Field> PLAYER_FALSE = new ArrayList<>();
    private static final List<Field> CONNECTION_TRUE = new ArrayList<>();
    private static final List<Field> CONNECTION_NULL = new ArrayList<>();
    private static final List<Field> CHUNK_INT_BIG = new ArrayList<>();
    private static final List<Field> CHUNK_FLOAT_ZERO = new ArrayList<>();
    private static Field chunkSenderField;

    private void actLikeLoadedClient() {
        if (!scanned) scan();
        // Each step on its own, so one failing can't stop the others.
        for (Field f : PLAYER_FALSE) setQuietly(f, this, false);   // isChangingDimension -> false
        for (Field f : PLAYER_TRUE) setQuietly(f, this, true);
        for (Field f : CONNECTION_TRUE) setQuietly(f, connection, true);
        for (Field f : CONNECTION_NULL) setQuietly(f, connection, null); // "waiting for the client to confirm a teleport"
        for (Method m : PLAYER_CALLS) callQuietly(m, this);
        for (Method m : CONNECTION_CALLS) callQuietly(m, connection);
        try {
            Object sender = chunkSenderField == null ? null : chunkSenderField.get(connection);
            if (sender != null) {
                for (Field f : CHUNK_INT_BIG) f.setInt(sender, 1_000_000);
                for (Field f : CHUNK_FLOAT_ZERO) f.setFloat(sender, 0f);
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            warnOnce("chunk sender", e);
        }
    }

    private void setQuietly(Field f, Object target, Object value) {
        try {
            if (value instanceof Boolean b) f.setBoolean(target, b);
            else f.set(target, value);
        } catch (ReflectiveOperationException | RuntimeException e) {
            warnOnce(f.getName(), e);
        }
    }

    private void callQuietly(Method m, Object target) {
        try {
            invoke(m, target);
        } catch (ReflectiveOperationException | RuntimeException e) {
            warnOnce(m.getName() + "()", e);
        }
    }

    private static final java.util.Set<String> WARNED = new java.util.HashSet<>();

    private static void warnOnce(String what, Exception e) {
        if (WARNED.add(what)) Skyserver.LOGGER.warn("Bots: client stand-in '{}' failed (shown once)", what, e);
    }

    private static void invoke(Method m, Object target) throws ReflectiveOperationException {
        if (m.getParameterCount() == 0) {
            m.invoke(target);
        } else {
            m.invoke(target, true);
        }
    }

    private void scan() {
        scanned = true;
        for (Class<?> c = ServerPlayer.class; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                String n = m.getName();
                boolean noArgs = m.getParameterCount() == 0;
                boolean oneBool = m.getParameterCount() == 1 && m.getParameterTypes()[0] == boolean.class;
                if ((n.equals("hasChangedDimension") && noArgs) || (n.equals("setClientLoaded") && oneBool)
                        || (n.equals("markClientLoaded") && noArgs)) {
                    m.setAccessible(true);
                    PLAYER_CALLS.add(m);
                }
            }
            for (Field f : c.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers()) || f.getType() != boolean.class) continue;
                if (f.getName().equals("clientLoaded")) { f.setAccessible(true); PLAYER_TRUE.add(f); }
                if (f.getName().equals("isChangingDimension")) { f.setAccessible(true); PLAYER_FALSE.add(f); }
            }
        }
        for (Class<?> c = connection.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                String n = m.getName();
                if ((n.equals("markClientLoaded") || n.equals("markClientLoadedAndClearTimeout")) && m.getParameterCount() == 0) {
                    m.setAccessible(true);
                    CONNECTION_CALLS.add(m);
                }
            }
            for (Field f : c.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                if (f.getType() == boolean.class && f.getName().equals("clientLoaded")) {
                    f.setAccessible(true);
                    CONNECTION_TRUE.add(f);
                }
                if (!f.getType().isPrimitive() && f.getName().equals("awaitingPositionFromClient")) {
                    f.setAccessible(true);
                    CONNECTION_NULL.add(f);
                }
                if (f.getName().equals("chunkSender")) {
                    f.setAccessible(true);
                    chunkSenderField = f;
                    for (Field cf : f.getType().getDeclaredFields()) {
                        if (Modifier.isStatic(cf.getModifiers())) continue;
                        if (cf.getType() == int.class && cf.getName().equals("unacknowledgedBatches")) {
                            cf.setAccessible(true);
                            CHUNK_INT_BIG.add(cf);
                        }
                        if (cf.getType() == float.class && (cf.getName().equals("desiredChunksPerTick") || cf.getName().equals("batchQuota"))) {
                            cf.setAccessible(true);
                            CHUNK_FLOAT_ZERO.add(cf);
                        }
                    }
                }
            }
        }
        Skyserver.LOGGER.info("Bots: client stand-ins found: {} player calls, {} connection calls, {} flags, chunk sender {}",
                PLAYER_CALLS.size(), CONNECTION_CALLS.size(), PLAYER_TRUE.size() + PLAYER_FALSE.size() + CONNECTION_TRUE.size() + CONNECTION_NULL.size(),
                chunkSenderField != null && (!CHUNK_INT_BIG.isEmpty() || !CHUNK_FLOAT_ZERO.isEmpty()) ? "paused" : "NOT FOUND");
    }

    public Difficulty difficulty() {
        return difficulty;
    }

    public void setDifficulty(Difficulty difficulty) {
        this.difficulty = difficulty;
    }

    public BotBrain brain() {
        return brain;
    }

    public void setBrain(BotBrain brain) {
        this.brain = brain;
    }

    @Override
    public void tick() {
        ServerLevel level = (ServerLevel) level();
        if (level.getServer().getTickCount() % 10 == 0) {
            connection.resetPosition();
            level.getChunkSource().move(this);
        }
        resetLastActionTime(); // never kicked for being idle
        actLikeLoadedClient();
        if (getFoodData().getFoodLevel() < 18) getFoodData().setFoodLevel(20);

        if (brain != null && isAlive() && !isSpectator()) {
            try {
                brain.tick();
            } catch (RuntimeException e) {
                if (!loggedError) {
                    loggedError = true;
                    Skyserver.LOGGER.error("Bot {} AI error (shown once)", getName().getString(), e);
                }
                BotBrain.stopInputs(this);
            }
        } else {
            BotBrain.stopInputs(this);
        }

        try {
            super.tick();
            doTick(); // movement and physics, normally driven by the player's client
        } catch (NullPointerException ignored) {
            // Can happen for a tick while the bot changes dimension.
        }
        fake.drain();
    }
}
