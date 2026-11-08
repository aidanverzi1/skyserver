package com.aidan.skyserver.lobby;

import com.aidan.skyserver.Skyserver;
import com.aidan.skyserver.bots.BotPlayer;
import com.aidan.skyserver.goldwars.GoldWarsGame;
import com.aidan.skyserver.skywars.SkywarsGame;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.*;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetActionBarTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetExperiencePacket;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;

import java.util.*;

/**
 * The lobby: where everyone spawns. Its own dimension (skyserver:lobby) with a castle, portals to
 * the games and survival, obstacle courses and mystery chests. Nothing can be broken or placed,
 * nobody can be hurt.
 *
 *  - Logging in always puts you in the lobby (except rejoining a Gold Wars match in progress).
 *  - /lobby works anywhere, but you must stand still for 5 seconds first (so nobody escapes a fight).
 *  - /survival (or the green portal) takes you back to survival with your own inventory and spot.
 *  - The games send everyone back here when a match ends.
 */
public final class Lobby {
    public static final ResourceKey<Level> LOBBY = dim("hub");
    /** The first version of the lobby (built with bad lighting). Anyone still there is moved to the new one. */
    public static final ResourceKey<Level> OLD_LOBBY = dim("lobby");
    public static final ResourceKey<Level> SKYWARS = dim("skywars");
    public static final ResourceKey<Level> ARENA = dim("arena");
    private static final int WARMUP_TICKS = 100;

    private static MinecraftServer server;
    private static long ticks = 0;
    private static boolean holosReady = false;
    private static final List<Runnable> LATER = new ArrayList<>();
    private static final Map<UUID, double[]> WARMUPS = new HashMap<>(); // x, y, z, startTick, health
    private static final Map<UUID, Long> PORTAL_COOLDOWN = new HashMap<>();
    private static final Set<UUID> BUILDERS = new HashSet<>();
    private static final Map<String, Holograms.Holo> PORTAL_HOLOS = new HashMap<>();
    private static Holograms.Holo levelBoard;
    private static final Set<UUID> DISCONNECTING = new HashSet<>();

    private Lobby() {}

    private static ResourceKey<Level> dim(String name) {
        return ResourceKey.create(Registries.DIMENSION, Identifier.fromNamespaceAndPath(Skyserver.MOD_ID, name));
    }

    /**
     * Ops in creative or spectator mode are left alone in the lobby: their game mode isn't reset,
     * their hotbar isn't replaced, and they can break, place and use anything (like build mode).
     * Switch back to adventure (/gamemode adventure) to get the normal lobby hotbar again.
     */
    public static boolean isStaff(net.minecraft.world.entity.player.Player p) {
        if (!(p instanceof ServerPlayer sp) || sp instanceof BotPlayer || server == null) return false;
        GameType mode = sp.gameMode.getGameModeForPlayer();
        return (mode == GameType.CREATIVE || mode == GameType.SPECTATOR) && server.getPlayerList().isOp(sp.nameAndId());
    }

    private static boolean canEdit(net.minecraft.world.entity.player.Player p) {
        return BUILDERS.contains(p.getUUID()) || isStaff(p);
    }

    public static MinecraftServer server() {
        return server;
    }

    public static ServerLevel level() {
        return server == null ? null : server.getLevel(LOBBY);
    }

    public static boolean isLobby(Level l) {
        return l != null && (l.dimension() == LOBBY || l.dimension() == OLD_LOBBY);
    }

    public static boolean isGameLevel(Level l) {
        return l != null && (l.dimension() == SKYWARS || l.dimension() == ARENA || l.dimension() == com.aidan.skyserver.bedwars.BwMap.DIM);
    }

    public static boolean isSurvival(Level l) {
        return l != null && !isLobby(l) && !isGameLevel(l) && !isIsland(l);
    }

    /** Both on the islands, or both in survival: the only places items can change hands. */
    public static boolean sameItemWorld(Level a, Level b) {
        return (isIsland(a) && isIsland(b)) || (isSurvival(a) && isSurvival(b));
    }

    public static boolean isItemWorld(Level l) {
        return isIsland(l) || isSurvival(l);
    }

    /**
     * The islands, and (5.8) the Nether Expedition world: what you carry there is your island
     * inventory, so it's saved as one (and never mixed up with survival).
     */
    public static boolean isIsland(Level l) {
        return com.aidan.skyserver.island.Islands.isIsland(l) || com.aidan.skyserver.expedition.Expedition.isExpedition(l);
    }

    /** Survival is on hold for now (Sky Island is the main mode). Set to true to open it again. */
    public static final boolean SURVIVAL_OPEN = false;

    public static void msg(ServerPlayer p, String text) {
        p.sendSystemMessage(Component.literal(text));
    }

    /** /level and /rewards: progress, the next rewards and the next big milestones. */
    public static void showLevel(ServerPlayer p) {
        msg(p, "§6§l--- Your Level ---");
        msg(p, Levels.describe(p.getUUID()));
        msg(p, Levels.progressBar(p.getUUID()));
        msg(p, "§6Coming up:");
        for (String line : Levels.upcoming(p.getUUID(), 5)) msg(p, " " + line);
        msg(p, "§7Earn XP by playing games, winning, kills, breaking gold, obstacle courses and daily logins.");
    }

    /** Runs something at the start of the next tick. */
    public static void runLater(Runnable r) {
        LATER.add(r);
    }

    private static final List<long[]> TIMED_IDS = new ArrayList<>();
    private static final Map<Long, Runnable> TIMED = new HashMap<>();
    private static long timedSeq = 0;

    /** Runs something after a delay (in ticks) on the server thread. */
    public static void runLaterTicks(int delay, Runnable r) {
        long id = timedSeq++;
        TIMED.put(id, r);
        TIMED_IDS.add(new long[]{id, ticks + delay});
    }

    private static void runTimed() {
        if (TIMED_IDS.isEmpty()) return;
        for (Iterator<long[]> it = TIMED_IDS.iterator(); it.hasNext(); ) {
            long[] t = it.next();
            if (t[1] > ticks) continue;
            it.remove();
            Runnable r = TIMED.remove(t[0]);
            if (r != null) com.aidan.skyserver.Safe.run("delayed task", r);
        }
    }

    /** Next tick, if the player is still online (used from menus, which shouldn't teleport mid-click). */
    public static void later(ServerPlayer p, Runnable r) {
        runLater(() -> {
            if (!p.hasDisconnected()) {
                p.closeContainer();
                r.run();
            }
        });
    }

    // =====================================================================
    // Setup
    // =====================================================================

    public static void init() {
        SpawnerItems.init();
        Activities.init();
        Skills.init();
        Bank.init();
        Factions.init();
        SkinShop.init();
        ChatFilter.init(); // before the chat channels, so blocked messages stop first
        ChatChannels.init();
        PlayerStats.init();
        Parties.init();
        Events.init();
        Sidebar.init();
        Admin.init();
        SkillHalls.init();

        ServerLifecycleEvents.SERVER_STARTED.register(s -> {
            server = s;
            ServerLevel lobby = level();
            if (lobby == null) {
                Skyserver.LOGGER.error("The lobby dimension skyserver:lobby is missing (resources/data/skyserver/dimension/lobby.json). Lobby is off.");
                return;
            }
            if (!LobbyBuilder.isBuilt()) LobbyBuilder.start(lobby);
            else lobbyBuilt();
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(s -> Holograms.removeAll());
        ServerLifecycleEvents.SERVER_STARTED.register(Economy::guardShopCommands);
        ServerLifecycleEvents.END_DATA_PACK_RELOAD.register((s, resources, ok) -> Economy.guardShopCommands(s));

        ServerTickEvents.START_SERVER_TICK.register(s -> {
            if (LATER.isEmpty()) return;
            List<Runnable> jobs = new ArrayList<>(LATER);
            LATER.clear();
            for (Runnable r : jobs) {
                try {
                    r.run();
                } catch (RuntimeException e) {
                    Skyserver.LOGGER.error("Lobby task failed", e);
                }
            }
        });
        ServerTickEvents.END_SERVER_TICK.register(s -> com.aidan.skyserver.Safe.run("lobby tick", () -> tick()));

        ServerPlayConnectionEvents.JOIN.register((handler, sender, s) -> {
            ServerPlayer p = handler.getPlayer();
            DISCONNECTING.remove(p.getUUID());
            runLater(() -> onJoin(p));
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, s) -> {
            ServerPlayer p = handler.getPlayer();
            DISCONNECTING.add(p.getUUID()); // games leaving them now must not teleport them
            Trade.cancel(p);
            Parkour.cancel(p, false);
            Activities.leaveAll(p);
            Mine.leave(p);
            WARMUPS.remove(p.getUUID());
        });

        // ---- Nothing in the lobby can be broken, placed, used or hurt ----
        PlayerBlockBreakEvents.BEFORE.register((level, player, pos, state, be) -> {
            if (!isLobby(level) || canEdit(player)) return true;
            if (player instanceof ServerPlayer sp) Mine.mined(sp, (ServerLevel) level, pos); // the Mine's ores (handled there)
            return false;
        });
        UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
            if (!isLobby(level) || level.isClientSide() || !(player instanceof ServerPlayer sp)) return InteractionResult.PASS;
            if (canEdit(player)) return InteractionResult.PASS;
            int crate = Crates.crateAt(hit.getBlockPos());
            if (crate >= 0) {
                if (hand == net.minecraft.world.InteractionHand.MAIN_HAND) later(sp, () -> Crates.use(sp, crate));
                return InteractionResult.FAIL;
            }
            String daily = SkillHalls.crateAt(hit.getBlockPos());
            if (daily != null) {
                if (hand == net.minecraft.world.InteractionHand.MAIN_HAND) later(sp, () -> SkillHalls.openCrate(sp, daily));
                return InteractionResult.FAIL;
            }
            if (hand == net.minecraft.world.InteractionHand.MAIN_HAND) useKitItem(sp, player.getItemInHand(hand));
            return InteractionResult.FAIL;
        });
        UseItemCallback.EVENT.register((player, level, hand) -> {
            if (!isLobby(level) || level.isClientSide() || !(player instanceof ServerPlayer sp)) return InteractionResult.PASS;
            if (canEdit(player)) return InteractionResult.PASS;
            if (Activities.allowUse(sp, player.getItemInHand(hand))) return InteractionResult.PASS; // bow, rod, rockets, duel kit
            if (hand == net.minecraft.world.InteractionHand.MAIN_HAND) useKitItem(sp, player.getItemInHand(hand));
            return InteractionResult.FAIL;
        });
        AttackEntityCallback.EVENT.register((player, level, hand, entity, hit) ->
                isLobby(level) && !canEdit(player) && !Duels.canHit(player, entity) ? InteractionResult.FAIL : InteractionResult.PASS);
        UseEntityCallback.EVENT.register((player, level, hand, entity, hit) -> {
            if (!isLobby(level) || level.isClientSide()) return InteractionResult.PASS;
            // Town people: right-click to talk (works for builders too).
            if (player instanceof ServerPlayer sp && hand == net.minecraft.world.InteractionHand.MAIN_HAND && SkillHalls.interact(sp, entity)) return InteractionResult.FAIL;
            if (entity.entityTags().contains("skyserver_npc")) return InteractionResult.FAIL;
            return !canEdit(player) ? InteractionResult.FAIL : InteractionResult.PASS;
        });
        ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> !isLobby(entity.level()) || Duels.allowDamage(entity, source));

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            for (String name : new String[]{"lobby", "hub", "spawn"}) {
                dispatcher.register(Commands.literal(name).executes(c -> {
                    lobbyCommand(c.getSource().getPlayerOrException());
                    return 1;
                }));
            }
            dispatcher.register(Commands.literal("survival").executes(c -> {
                ServerPlayer p = c.getSource().getPlayerOrException();
                if (!SURVIVAL_OPEN) msg(p, "§eSurvival is closed for now. §7Try your §aSky Island§7: type §e/is§7.");
                else if (isSurvival(p.level())) msg(p, "§eYou're already in survival.");
                else if (!isLobby(p.level())) msg(p, "§cUse §e/lobby §cfirst.");
                else toSurvival(p);
                return 1;
            }));
            dispatcher.register(Commands.literal("claim").executes(c -> {
                ServerPlayer p = c.getSource().getPlayerOrException();
                if (!isIsland(p.level()) && !isSurvival(p.level())) {
                    msg(p, "§cClaim prizes on your Sky Island (§e/is§c).");
                    return 0;
                }
                int n = Mailbox.deliver(p);
                int left = Mailbox.waiting(p.getUUID());
                msg(p, n == 0 && left == 0 ? "§7Nothing waiting for you." : "§aClaimed " + n + " item" + (n == 1 ? "" : "s") + "." + (left > 0 ? " §e" + left + " more waiting (make room and /claim again)." : ""));
                return 1;
            }));
            LiteralArgumentBuilder<CommandSourceStack> keys = Commands.literal("keys").executes(c -> {
                ServerPlayer p = c.getSource().getPlayerOrException();
                msg(p, "§eYour keys: " + Keys.summary(p.getUUID()));
                return 1;
            });
            // Admin: /keys add|give|take <player> <tier> [amount]. Tier = common, uncommon, rare, epic,
            // legendary, or a number 0-4. Amount defaults to 1. Also /addkeys <player> <tier> [amount].
            for (String action : new String[]{"add", "give", "take"}) {
                keys.then(Commands.literal(action).requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS)).then(keyAdminArgs(!action.equals("take"))));
            }
            keys.then(Commands.literal("check").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                    .then(Commands.argument("player", EntityArgument.player()).executes(c -> {
                        ServerPlayer target = EntityArgument.getPlayer(c, "player");
                        c.getSource().sendSuccess(() -> Component.literal(target.getName().getString() + "'s keys: " + Keys.summary(target.getUUID()).replaceAll("§.", "")), false);
                        return 1;
                    })));
            dispatcher.register(keys);
            dispatcher.register(Commands.literal("addkeys").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS)).then(keyAdminArgs(true)));
            dispatcher.register(Commands.literal("rewards").executes(c -> {
                showLevel(c.getSource().getPlayerOrException());
                return 1;
            }));
            dispatcher.register(Commands.literal("level")
                    .executes(c -> {
                        showLevel(c.getSource().getPlayerOrException());
                        return 1;
                    })
                    .then(Commands.argument("player", EntityArgument.player()).executes(c -> {
                        ServerPlayer target = EntityArgument.getPlayer(c, "player");
                        c.getSource().sendSuccess(() -> Component.literal(target.getName().getString() + ": " + Levels.describe(target.getUUID()).replaceAll("§.", "")), false);
                        return 1;
                    })));
            dispatcher.register(Commands.literal("trade")
                    .then(Commands.literal("accept").executes(c -> {
                        Trade.accept(c.getSource().getPlayerOrException(), null);
                        return 1;
                    }))
                    .then(Commands.argument("player", EntityArgument.player()).executes(c -> {
                        Trade.request(c.getSource().getPlayerOrException(), EntityArgument.getPlayer(c, "player"));
                        return 1;
                    })));
            LiteralArgumentBuilder<CommandSourceStack> admin = Commands.literal("lobbyadmin").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS));
            admin.then(Commands.literal("build").executes(c -> {
                ServerPlayer p = c.getSource().getPlayerOrException();
                if (BUILDERS.remove(p.getUUID())) {
                    p.setGameMode(GameType.ADVENTURE);
                    refreshKit(p);
                    msg(p, "§eLobby build mode §cOFF§e.");
                } else {
                    BUILDERS.add(p.getUUID());
                    p.getInventory().clearContent();
                    p.setGameMode(GameType.CREATIVE);
                    msg(p, "§eLobby build mode §aON§e: you can break and place blocks in the lobby. Run it again to stop.");
                }
                return 1;
            }));
            admin.then(Commands.literal("rebuild").executes(c -> {
                if (level() == null) return 0;
                LobbyBuilder.start(level());
                c.getSource().sendSuccess(() -> Component.literal("Rebuilding the lobby (it places the design over what's there)."), true);
                return 1;
            }));
            admin.then(Commands.literal("status").executes(c -> {
                String s = LobbyBuilder.isBuilding() ? "building, " + LobbyBuilder.percentDone() + "%" : "built (version " + LobbyBuilder.VERSION + ")";
                c.getSource().sendSuccess(() -> Component.literal("Lobby: " + s), false);
                return 1;
            }));
            dispatcher.register(admin);
        });
        Skyserver.LOGGER.info("Lobby ready");
    }

    /** <player> <tier> [amount] for the admin key commands. */
    private static com.mojang.brigadier.builder.RequiredArgumentBuilder<CommandSourceStack, ?> keyAdminArgs(boolean add) {
        return Commands.argument("player", EntityArgument.player())
                .then(Commands.argument("tier", com.mojang.brigadier.arguments.StringArgumentType.word())
                        .suggests((c, b) -> net.minecraft.commands.SharedSuggestionProvider.suggest(
                                List.of("common", "uncommon", "rare", "epic", "legendary"), b))
                        .executes(c -> keyAdmin(c, add, 1))
                        .then(Commands.argument("amount", IntegerArgumentType.integer(1, 10000))
                                .executes(c -> keyAdmin(c, add, IntegerArgumentType.getInteger(c, "amount")))));
    }

    private static int keyAdmin(com.mojang.brigadier.context.CommandContext<CommandSourceStack> c, boolean add, int amount)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer target = EntityArgument.getPlayer(c, "player");
        String word = com.mojang.brigadier.arguments.StringArgumentType.getString(c, "tier").toLowerCase(Locale.ROOT);
        int tier = switch (word) {
            case "0", "common", "c" -> 0;
            case "1", "uncommon", "u" -> 1;
            case "2", "rare", "r" -> 2;
            case "3", "epic", "e" -> 3;
            case "4", "legendary", "legend", "l" -> 4;
            default -> -1;
        };
        if (tier < 0) {
            c.getSource().sendFailure(Component.literal("Unknown key tier '" + word + "'. Use common, uncommon, rare, epic or legendary."));
            return 0;
        }
        if (add) {
            Keys.reward(server, target.getUUID(), tier, amount, "(from an admin)");
        } else {
            Keys.give(target.getUUID(), tier, -Math.min(Keys.count(target.getUUID(), tier), amount));
            refreshKit(target);
        }
        final int t = tier;
        c.getSource().sendSuccess(() -> Component.literal((add ? "Gave " : "Took ") + amount + " " + Keys.NAMES[t] + " key(s) "
                + (add ? "to " : "from ") + target.getName().getString() + ". They now have: "
                + Keys.summary(target.getUUID()).replaceAll("§.", "")), true);
        return 1;
    }

    /** Called when the lobby is built (at startup if it already was). */
    static void lobbyBuilt() {
        if (holosReady) return;
        holosReady = true;
        for (LobbyDesign.Holo h : LobbyDesign.HOLOS) Holograms.add(h.x(), h.y(), h.z(), h.lines());
        for (LobbyDesign.Portal p : LobbyDesign.PORTALS) {
            LobbyDesign.Holo h = p.label();
            PORTAL_HOLOS.put(p.id(), Holograms.add(h.x(), h.y(), h.z(), h.lines()));
        }
        Parkour.setup();
        Activities.setup(server);
        Town.setup();
        levelBoard = Holograms.add(LobbyDesign.SPAWN[0] + 9, LobbyDesign.G + 4.5, LobbyDesign.SPAWN[2] - 4, List.of("§6§lTOP LEVELS", "§7nobody yet"));
    }

    // =====================================================================
    // Moving players around
    // =====================================================================

    private static void onJoin(ServerPlayer p) {
        if (p instanceof BotPlayer || p.hasDisconnected() || level() == null) return;
        UUID id = p.getUUID();
        if (GoldWarsGame.isInGame(id) || SkywarsGame.isInGame(id) || com.aidan.skyserver.bedwars.BedwarsGame.isInGame(id)) return; // rejoining a match in progress
        GoldWarsGame.clearStaleTeam(p);
        com.aidan.skyserver.bedwars.BedwarsGame.clearStaleTeam(p);
        if (isSurvival(p.level())) SurvivalStore.save(p);
        else if (isIsland(p.level())) com.aidan.skyserver.island.IslandStore.save(p);
        toLobby(p);
        SkinShop.onJoin(p);
        Bank.interest(p);
        if (Keys.dailyLogin(server, p)) Levels.addXp(server, p, Rewards.DAILY_LOGIN_XP);
        int mail = Mailbox.waiting(id);
        if (mail > 0) msg(p, "§eYou have " + mail + " prize" + (mail == 1 ? "" : "s") + " waiting. §7They're delivered when you go to your Sky Island (§e/is§7).");
        com.google.gson.JsonObject seen = Stacks.readFile("guide-seen.json");
        if (!seen.has(id.toString())) {
            seen.addProperty(id.toString(), true);
            Stacks.writeFile("guide-seen.json", seen);
            msg(p, "§6Welcome! §7Your §eServer Guide §7book (in your hotbar) lists every command. §aType /is to start your Sky Island!");
        }
    }

    /** Puts a player in the lobby with the lobby hotbar (their survival stuff must already be saved). */
    public static void toLobby(ServerPlayer p) {
        if (p instanceof BotPlayer) {
            p.getInventory().clearContent();
            return;
        }
        ServerLevel lobby = level();
        if (lobby == null || DISCONNECTING.contains(p.getUUID())) return; // logging out: sorted out when they log back in
        Trade.cancel(p);
        Parkour.cancel(p, false);
        Activities.leaveAll(p);
        WARMUPS.remove(p.getUUID());
        p.closeContainer();
        p.getInventory().clearContent();
        p.removeAllEffects();
        p.setHealth(p.getMaxHealth());
        p.getFoodData().setFoodLevel(20);
        p.getFoodData().setSaturation(5f);
        p.setRemainingFireTicks(0);
        p.experienceLevel = 0;
        p.experienceProgress = 0;
        p.totalExperience = 0;
        p.connection.send(new ClientboundSetExperiencePacket(0f, 0, 0));
        BUILDERS.remove(p.getUUID());
        // Everyone arrives in the lobby's normal mode, ops too (so nobody's left in spectator after
        // a match). Ops can switch to creative afterwards and the lobby leaves them alone.
        p.setGameMode(GameType.ADVENTURE);
        p.resetFallDistance();
        p.teleportTo(lobby, LobbyDesign.SPAWN[0], LobbyDesign.SPAWN[1], LobbyDesign.SPAWN[2], Set.of(), LobbyDesign.SPAWN_YAW, 0f, true);
        refreshKit(p);
    }

    public static void toSurvival(ServerPlayer p) {
        if (!isLobby(p.level())) return;
        Trade.cancel(p);
        Parkour.cancel(p, false);
        p.closeContainer();
        SurvivalStore.restore(p);
        int n = Mailbox.deliver(p);
        if (n > 0) msg(p, "§aDelivered " + n + " prize" + (n == 1 ? "" : "s") + " to your inventory.");
        int left = Mailbox.waiting(p.getUUID());
        if (left > 0) msg(p, "§e" + left + " more prize" + (left == 1 ? "" : "s") + " waiting: make room and type /claim.");
        msg(p, "§aWelcome back to survival! §7Type §e/lobby §7to return (stand still 5 seconds).");
    }

    public static void toGreatHall(ServerPlayer p) {
        if (!isLobby(p.level())) return;
        Parkour.cancel(p, false);
        p.teleportTo((ServerLevel) p.level(), 0.5, LobbyDesign.G + 1, LobbyDesign.KEEP_Z1 + 13.5, Set.of(), 180f, 0f, true);
    }

    /** Joins a game from the lobby (portal or menu). */
    public static void joinGame(ServerPlayer p, String game) {
        if (!isLobby(p.level())) {
            msg(p, "§cGo to the lobby first (§e/lobby§c).");
            return;
        }
        Parkour.cancel(p, false);
        Trade.cancel(p);
        Activities.leaveAll(p);
        try {
            if (game.equals("skywars")) SkywarsGame.join(p);
            else if (game.equals("bedwars")) com.aidan.skyserver.bedwars.BedwarsGame.join(p);
            else if (game.equals("bedwars_trios")) com.aidan.skyserver.bedwars.BedwarsGame.join(p, com.aidan.skyserver.bedwars.BwRules.Mode.TRIOS);
            else if (game.equals("bedwars_duos")) com.aidan.skyserver.bedwars.BedwarsGame.join(p, com.aidan.skyserver.bedwars.BwRules.Mode.DUOS);
            else GoldWarsGame.join(p);
        } catch (RuntimeException e) {
            Skyserver.LOGGER.error("Joining {} failed", game, e);
            msg(p, "§cSomething went wrong joining " + game + ". §7(Logged for the admin.)");
        }
    }

    private static void lobbyCommand(ServerPlayer p) {
        if (isLobby(p.level())) {
            Parkour.cancel(p, false);
            p.teleportTo((ServerLevel) p.level(), LobbyDesign.SPAWN[0], LobbyDesign.SPAWN[1], LobbyDesign.SPAWN[2], Set.of(), LobbyDesign.SPAWN_YAW, 0f, true);
            return;
        }
        if (level() == null) {
            msg(p, "§cThe lobby isn't available.");
            return;
        }
        WARMUPS.put(p.getUUID(), new double[]{p.getX(), p.getY(), p.getZ(), ticks, p.getHealth()});
        msg(p, "§eTeleporting to the lobby in 5 seconds. §7Don't move or take damage.");
    }

    /** Cancels lobby things (courses, trades, activities) before a player leaves the lobby. */
    public static void leaveLobbyStuff(ServerPlayer p) {
        Trade.cancel(p);
        Parkour.cancel(p, false);
        WARMUPS.remove(p.getUUID());
        Activities.leaveAll(p);
        Mine.leave(p);
        p.closeContainer();
    }

    /** Leaves whatever the player is in (saving survival or island, leaving a game) and goes to the lobby. */
    public static void goToLobby(ServerPlayer p) {
        UUID id = p.getUUID();
        if (SkywarsGame.isInGame(id)) {
            server.getCommands().performPrefixedCommand(p.createCommandSourceStack(), "sw leave");
            if (isLobby(p.level())) return; // the game sent them here
        } else if (GoldWarsGame.isInGame(id)) {
            server.getCommands().performPrefixedCommand(p.createCommandSourceStack(), "gw leave");
            if (isLobby(p.level())) return;
        } else if (com.aidan.skyserver.bedwars.BedwarsGame.isInGame(id)) {
            server.getCommands().performPrefixedCommand(p.createCommandSourceStack(), "bw leave");
            if (isLobby(p.level())) return;
        } else if (isSurvival(p.level())) {
            SurvivalStore.save(p);
        } else if (isIsland(p.level())) {
            com.aidan.skyserver.island.IslandStore.save(p);
        }
        toLobby(p);
    }

    // =====================================================================
    // The lobby hotbar
    // =====================================================================

    /** Gives the lobby hotbar (compass, keys, and course items while running a course). */
    public static void refreshKit(ServerPlayer p) {
        if (!isLobby(p.level()) || canEdit(p)) return;
        Inventory inv = p.getInventory();
        inv.clearContent();
        inv.setItem(0, Stacks.tag(Stacks.named(new ItemStack(Items.COMPASS), "§a§lGame Menu §7(right-click)",
                "§7Play games, go to survival, obstacle", "§7courses, mystery chests and the key shop."), "menu"));
        int[] k = Keys.of(p.getUUID());
        Levels.showBar(p);
        inv.setItem(8, Stacks.tag(Stacks.named(new ItemStack(Items.TRIPWIRE_HOOK), "§e§lYour Keys",
                Keys.COLORS[0] + "Common: §f" + k[0], Keys.COLORS[1] + "Uncommon: §f" + k[1], Keys.COLORS[2] + "Rare: §f" + k[2],
                Keys.COLORS[3] + "Epic: §f" + k[3], Keys.COLORS[4] + "Legendary: §f" + k[4], "§7Use them on the chests in the Great Hall."), "keys"));
        inv.setItem(7, Guide.book());
        if (Parkour.running(p)) {
            inv.setItem(3, Stacks.tag(Stacks.named(new ItemStack(Items.FEATHER), "§bBack to checkpoint §7(right-click)"), "checkpoint"));
            inv.setItem(5, Stacks.tag(Stacks.named(new ItemStack(Items.BARRIER), "§cQuit course §7(right-click)"), "quit"));
        }
        Activities.kit(p, inv);
        Mine.kit(p, inv);
    }

    private static void useKitItem(ServerPlayer p, ItemStack held) {
        if (Activities.useItem(p, held)) return;
        if (Stacks.is(held, "menu")) later(p, () -> GameMenu.open(p));
        else if (Stacks.is(held, "keys")) msg(p, "§eYour keys: " + Keys.summary(p.getUUID()));
        else if (Stacks.is(held, "checkpoint")) Parkour.toCheckpoint(p);
        else if (Stacks.is(held, "quit")) {
            Parkour.cancel(p, true);
            later(p, () -> lobbyCommand(p));
        }
    }

    // =====================================================================
    // Status text for the portals and the menu
    // =====================================================================

    public static String gameStatus(String game) {
        try {
            if (game.equals("bedwars")) return com.aidan.skyserver.bedwars.BedwarsGame.lobbyStatus();
            return game.equals("skywars") ? SkywarsGame.lobbyStatus() : GoldWarsGame.lobbyStatus();
        } catch (RuntimeException e) {
            return "";
        }
    }

    // =====================================================================
    // Every tick
    // =====================================================================

    private static void tick() {
        if (server == null) return;
        ticks++;
        ServerLevel lobby = level();
        if (lobby == null) return;
        final long t = ticks;
        runTimed();
        com.aidan.skyserver.Safe.run("town people", () -> SkillHalls.tick(lobby, t));
        com.aidan.skyserver.Safe.run("chat filter", () -> ChatFilter.tick(server, t));
        com.aidan.skyserver.Safe.run("lobby builder", () -> LobbyBuilder.tick(lobby));
        com.aidan.skyserver.Safe.run("crates", Crates::tick);
        com.aidan.skyserver.Safe.run("trades", Trade::tick);
        com.aidan.skyserver.Safe.run("activities", () -> Activities.tick(lobby, t));
        // No hunger in Skywars and Gold Wars (full food bar, no extra saturation, so healing stays normal).
        if (ticks % 20 == 0) for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (!isGameLevel(p.level())) continue;
            p.getFoodData().setFoodLevel(20);
            p.getFoodData().setSaturation(0f);
        }
        com.aidan.skyserver.Safe.run("town", () -> Town.tick(lobby, t));
        com.aidan.skyserver.Safe.run("parties", () -> Parties.tick(server, t));
        com.aidan.skyserver.Safe.run("events", () -> Events.tick(server, t));
        com.aidan.skyserver.Safe.run("sidebar", () -> Sidebar.tick(server, t));
        com.aidan.skyserver.Safe.run("backups", () -> Backups.tick(server, t));

        // /lobby warm-ups: stand still, don't get hurt.
        for (Iterator<Map.Entry<UUID, double[]>> it = WARMUPS.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, double[]> e = it.next();
            ServerPlayer p = server.getPlayerList().getPlayer(e.getKey());
            double[] w = e.getValue();
            if (p == null) {
                it.remove();
                continue;
            }
            double moved = Math.sqrt(Math.pow(p.getX() - w[0], 2) + Math.pow(p.getY() - w[1], 2) + Math.pow(p.getZ() - w[2], 2));
            if (moved > 0.6 || p.getHealth() < w[4] - 0.01) {
                it.remove();
                msg(p, "§cTeleport cancelled: you " + (moved > 0.6 ? "moved" : "took damage") + ". §7Type /lobby to try again.");
                continue;
            }
            w[4] = Math.max(w[4], p.getHealth()); // healing is fine
            long left = WARMUP_TICKS - (ticks - (long) w[3]);
            if (left <= 0) {
                it.remove();
                goToLobby(p);
            } else if (left % 20 == 0) {
                p.connection.send(new ClientboundSetActionBarTextPacket(Component.literal("§eTeleporting to the lobby in " + (left / 20) + "...")));
            }
        }

        for (ServerPlayer p : new ArrayList<>(lobby.players())) { // a copy: portals move people out mid-loop
            if (p instanceof BotPlayer) continue;
            boolean builder = canEdit(p);
            // Fell off the island.
            if (p.getY() < LobbyDesign.G - 40) {
                if (Parkour.running(p)) Parkour.toCheckpoint(p);
                else p.teleportTo(lobby, LobbyDesign.SPAWN[0], LobbyDesign.SPAWN[1], LobbyDesign.SPAWN[2], Set.of(), LobbyDesign.SPAWN_YAW, 0f, true);
                p.resetFallDistance();
            }
            // Portals work for everyone except people in /lobbyadmin build mode (ops in creative too).
            if (!BUILDERS.contains(p.getUUID())) portals(p);
            if (!p.isAlive() || !isLobby(p.level())) continue; // went through a portal
            if (builder) continue;
            if (p.gameMode.getGameModeForPlayer() != GameType.ADVENTURE) p.setGameMode(GameType.ADVENTURE);
            Activities.tick(p);
            Town.tick(p, ticks);
            if (ticks % 40 == 0) {
                p.getFoodData().setFoodLevel(20);
                // Keep the hotbar tidy (in case something was moved or dropped) when no screen is open.
                if (p.containerMenu == p.inventoryMenu && !Activities.busy(p) && !Mine.busy(p)) refreshKit(p);
                for (ItemEntity item : lobby.getEntitiesOfClass(ItemEntity.class, p.getBoundingBox().inflate(16)))
                    if (!Activities.keepItem(item)) item.discard();
            }
            Parkour.tick(p, ticks);
        }

        if (holosReady && ticks % 20 == 0) {
            for (LobbyDesign.Portal portal : LobbyDesign.PORTALS) {
                Holograms.Holo h = PORTAL_HOLOS.get(portal.id());
                if (h == null) continue;
                List<String> lines = new ArrayList<>(portal.label().lines());
                lines.set(lines.size() - 1, portal.id().equals("island") ? "§7Type §e/is §7or walk in" : gameStatus(portal.id()));
                h.setLines(lines);
            }
            if (ticks % 1200 == 0) Levels.checkSeason(server);
            if (levelBoard != null && ticks % 200 == 0) {
                List<String> lines = new ArrayList<>(List.of("§6§lTOP LEVELS §7- Season " + Levels.season(),
                        "§7" + Levels.daysLeft() + " days left. Top 3 win Legendary/Epic/Rare keys!"));
                lines.addAll(Levels.top(5));
                levelBoard.setLines(lines);
            }
            Holograms.tick(lobby);
        }
        if (holosReady && ticks % 8 == 0) {
            for (LobbyDesign.Portal portal : LobbyDesign.PORTALS) {
                double[] c = portal.particleCenter();
                LobbyDesign.Box a = portal.area();
                double sx = (a.x2() - a.x1() + 1) / 2.5, sz = (a.z2() - a.z1() + 1) / 2.5;
                lobby.sendParticles(ParticleTypes.PORTAL, c[0], c[1], c[2], 25, sx, 2.0, sz, 0.3);
            }
        }
    }

    private static final Map<UUID, double[]> LAST_POS = new HashMap<>();

    /** True if the player is in the portal, or crossed through it since last tick (fast movers skip thin portals). */
    private static boolean inPortal(LobbyDesign.Portal portal, ServerPlayer p, double[] last) {
        LobbyDesign.Box a = portal.area();
        double x = p.getX(), y = p.getY(), z = p.getZ();
        if (a.contains(x, y, z)) return true;
        if (last == null) return false;
        // Sample the path from last tick's spot to now.
        for (int i = 1; i <= 8; i++) {
            double t = i / 8.0;
            if (a.contains(last[0] + (x - last[0]) * t, last[1] + (y - last[1]) * t, last[2] + (z - last[2]) * t)) return true;
        }
        return false;
    }

    private static void portals(ServerPlayer p) {
        double[] last = LAST_POS.put(p.getUUID(), new double[]{p.getX(), p.getY(), p.getZ()});
        for (LobbyDesign.Portal portal : LobbyDesign.PORTALS) {
            if (!inPortal(portal, p, last)) continue;
            Long until = PORTAL_COOLDOWN.get(p.getUUID());
            if (until != null && until > ticks) return;
            PORTAL_COOLDOWN.put(p.getUUID(), ticks + 40);
            LAST_POS.remove(p.getUUID());
            if (!portal.id().equals("survival") || SURVIVAL_OPEN) {
                // Leaving creative etc. for the game: the games set their own mode.
                Activities.leaveAll(p);
                Parkour.cancel(p, false);
            }
            switch (portal.id()) {
                case "survival" -> { if (SURVIVAL_OPEN) toSurvival(p); }
                case "island" -> com.aidan.skyserver.island.Islands.goHome(p);
                case "skywars", "goldwars", "bedwars" -> joinGame(p, portal.id());
                default -> {}
            }
            if (isLobby(p.level()) && portal.area().contains(p.getX(), p.getY(), p.getZ())) {
                // Couldn't join (match running, full...): step back out of the arch.
                double[] c = portal.particleCenter();
                double dx = p.getX() - c[0], dz = p.getZ() - c[2];
                boolean alongZ = portal.area().x1() == portal.area().x2();
                double nx = alongZ ? c[0] + (dx >= 0 ? 3 : -3) : p.getX();
                double nz = alongZ ? p.getZ() : c[2] + (dz >= 0 ? 3 : -3);
                p.teleportTo((ServerLevel) p.level(), nx, p.getY(), nz, Set.of(), p.getYRot() + 180f, 0f, true);
            }
            return;
        }
    }
}
