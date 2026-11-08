package com.aidan.skyserver.lobby;

import com.aidan.skyserver.bots.BotPlayer;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Unit;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.Fireworks;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.time.LocalDate;
import java.util.*;

import static com.aidan.skyserver.lobby.ActivityDesign.*;
import static com.aidan.skyserver.lobby.LobbyDesign.G;

/**
 * The lobby activities (the layout is in ActivityDesign; duels are in Duels):
 *
 *  - Spleef Pit: /spleef to queue. Rounds swap between Spleef (left-click the snow to break it)
 *    and TNT Run (the floor falls away behind you). Last one standing wins.
 *  - Archery Range: step onto the shooting line for a 60-second round.
 *  - Hedge Maze: the clock starts at the entrance, stops at the golden fountain in the middle.
 *  - Elytra courses (5.9: six of them, see ElytraCourses; /elytra picks one): step on the gold pad
 *    for an elytra, then fly through every ring in order and land on the landing pad. The first
 *    finish of any course each day pays (same for all). Parkour skill XP: a full amount for the
 *    first finish of each course each day, a little for repeats (they only take ~10 seconds),
 *    capped each day.
 *  - Fishing Pond: walk up to the lake for a rod. Every catch pays a little; any catch can be a key.
 *  - Daily quests: /quests, the compass menu, or the quest board by spawn.
 *
 * Every activity has a best-score board and a once-a-day reward. Saved in activities.json.
 */
public final class Activities {
    // ---- Rewards (first time each day, unless noted) ----
    // 5.8: archery goals pay 150 / 250 / 400 (were 200 / 300 / 500); paid pond catches 60 a day (was 100).
    static final long MAZE_MONEY = 200, ELYTRA_MONEY = 250, ARCHERY_MONEY = 150, SPLEEF_WIN_MONEY = 150, SPLEEF_FIRST_WIN = 250;
    static final int MAZE_XP = 40, ELYTRA_XP = 50, ARCHERY_XP = 40, SPLEEF_WIN_XP = 30;
    /** 5.9: Parkour skill XP from elytra courses: first finish of each course per day, then per repeat, and the most repeats pay per day. */
    static final int ELYTRA_FIRST_SKILL_XP = 50, ELYTRA_REPEAT_SKILL_XP = 5, ELYTRA_REPEAT_SKILL_XP_PER_DAY = 150;
    static final int ARCHERY_REWARD_SCORE = 40;
    /** Daily archery goals: score these (once each per day) for the rewards. */
    static final int[] ARCHERY_GOALS = {40, 70, 100};
    static final long FISH_MONEY = 10, JUNK_MONEY = 2, TREASURE_MONEY = 15;
    static final int FISH_PAID_PER_DAY = 60;
    /** Key chances per catch (any catch), rolled on the paid catches each day: common .. legendary. */
    static final double[] FISH_KEY_CHANCE = {1 / 250.0, 1 / 500.0, 1 / 2500.0, 1 / 5000.0, 1 / 10000.0};

    private static MinecraftServer server;
    private static long now = 0;
    private static final Random RNG = new Random();

    private Activities() {}

    // =====================================================================
    // Setup
    // =====================================================================

    public static void init() {
        Duels.init();
        ElytraCourses.init();
        AttackBlockCallback.EVENT.register((player, level, hand, pos, dir) -> {
            if (level.isClientSide() || !Lobby.isLobby(level) || !(player instanceof ServerPlayer sp)) return InteractionResult.PASS;
            if (spleefBreak(sp, (ServerLevel) level, pos)) return InteractionResult.FAIL;
            return InteractionResult.PASS;
        });
        CommandRegistrationCallback.EVENT.register((d, r, e) -> {
            d.register(Commands.literal("spleef")
                    .executes(c -> { spleefJoin(c.getSource().getPlayerOrException()); return 1; })
                    .then(Commands.literal("join").executes(c -> { spleefJoin(c.getSource().getPlayerOrException()); return 1; }))
                    .then(Commands.literal("leave").executes(c -> {
                        ServerPlayer p = c.getSource().getPlayerOrException();
                        boolean was = SPLEEF_QUEUE.remove(p.getUUID()) | ALIVE.contains(p.getUUID());
                        if (ALIVE.contains(p.getUUID())) spleefOut(p, "left the round");
                        Lobby.msg(p, was ? "§7You left the Spleef queue." : "§7You weren't in the Spleef queue.");
                        return 1;
                    }))
                    .then(Commands.literal("bots").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                            .executes(c -> {
                                int n = addSpleefBots(Math.max(1, SPLEEF_FILL - SPLEEF_QUEUE.size()));
                                c.getSource().sendSuccess(() -> net.minecraft.network.chat.Component.literal("Added " + n + " bots to the Spleef queue."), false);
                                return 1;
                            })));
            d.register(Commands.literal("quests").executes(c -> {
                ServerPlayer p = c.getSource().getPlayerOrException();
                Lobby.msg(p, "§6§lDAILY QUESTS §7(new ones every day)");
                for (String line : Quests.describe(p.getUUID())) Lobby.msg(p, " " + line);
                return 1;
            }));
            d.register(Commands.literal("guide").executes(c -> {
                ServerPlayer p = c.getSource().getPlayerOrException();
                Guide.open(p);
                return 1;
            }));
        });
    }

    private static final Map<String, Holograms.Holo> BOARDS = new HashMap<>();
    private static Holograms.Holo questBoard, islandBoard;

    /** Called once the lobby is built: leaderboards and boards. */
    static void setup(MinecraftServer s) {
        server = s;
        load();
        BOARDS.put("maze", Holograms.add(MAZE_SPOT.x() + 6, G + 4.2, MAZE_SPOT.z() - 1, List.of()));
        BOARDS.put("archery", Holograms.add(ARCHERY_SPOT.x() + 12, G + 4.2, ARCHERY_SPOT.z(), List.of()));
        for (ElytraCourses.Course c : ElytraCourses.ALL)
            BOARDS.put(c.recordKey(), Holograms.add(c.board().x(), c.board().y(), c.board().z(), List.of()));
        BOARDS.put("spleef", Holograms.add(SPLEEF_STANDS.x() + 5, SPLEEF_STANDS.y() + 3.5, SPLEEF_STANDS.z() + 2, List.of()));
        BOARDS.put("fish", Holograms.add(POND_SPOT.x() - 6, G + 4.2, POND_SPOT.z(), List.of()));
        questBoard = Holograms.add(LobbyDesign.SPAWN[0] - 9, G + 4.5, LobbyDesign.SPAWN[2] - 4, List.of(
                "§6§lDAILY QUESTS", "§7Three new quests every day", "§7See yours: §e/quests §7or the compass", "§7Finish all 3 for a §fCommon Key"));
        islandBoard = Holograms.add(LobbyDesign.SPAWN[0], G + 5.5, -18.5, List.of("§a§lTOP SKY ISLANDS", "§7nobody yet"));
        refreshBoards();
    }

    // =====================================================================
    // What the lobby asks
    // =====================================================================

    /** True while the player is doing something whose items must not be reset (rounds, flights, fishing...). */
    public static boolean busy(ServerPlayer p) {
        UUID id = p.getUUID();
        return ALIVE.contains(id) || ARCHERY.containsKey(id) || ELYTRA.containsKey(id) || FISHING.contains(id) || Duels.inDuel(id);
    }

    /** Activity gear added to the lobby hotbar. */
    static void kit(ServerPlayer p, Inventory inv) {
        UUID id = p.getUUID();
        if (Duels.inDuel(id)) {
            Duels.kit(p);
            return;
        }
        if (ALIVE.contains(id) && spleefMode == 0) {
            ItemStack shovel = Stacks.canBreak(unbreakable(new ItemStack(Items.DIAMOND_SHOVEL)), Blocks.SNOW_BLOCK);
            inv.setItem(1, Stacks.tag(Stacks.named(shovel, "§bSpleef Shovel §7(left-click the snow)"), "act"));
        }
        if (ARCHERY.containsKey(id)) {
            inv.setItem(1, Stacks.tag(Stacks.named(unbreakable(new ItemStack(Items.BOW)), "§6Range Bow"), "act"));
            inv.setItem(2, Stacks.tag(new ItemStack(Items.ARROW, ARCHERY.get(id).arrows), "act"));
        }
        Flight f = ELYTRA.get(id);
        if (f != null) {
            ElytraCourses.Course c = ElytraCourses.course(f.course);
            p.setItemSlot(EquipmentSlot.CHEST, Stacks.tag(Stacks.named(unbreakable(new ItemStack(Items.ELYTRA)), c.color() + c.name() + " Elytra"), "act"));
            ItemStack rockets = new ItemStack(Items.FIREWORK_ROCKET, f.rockets);
            rockets.set(DataComponents.FIREWORKS, new Fireworks(1, List.of()));
            if (f.rockets > 0) inv.setItem(1, Stacks.tag(Stacks.named(rockets, "§eBoost Rocket §7(right-click while flying)"), "act"));
        } else if (!(p.getItemBySlot(EquipmentSlot.CHEST).isEmpty())) {
            p.setItemSlot(EquipmentSlot.CHEST, ItemStack.EMPTY);
        }
        if (FISHING.contains(id)) {
            inv.setItem(1, Stacks.tag(Stacks.named(unbreakable(new ItemStack(Items.FISHING_ROD)), "§bPond Rod §7(right-click the water)"), "act"));
        }
        if (FLY_AGAIN.containsKey(id) && f == null) {
            ElytraCourses.Course c = ElytraCourses.course(LAST_COURSE.getOrDefault(id, 0));
            inv.setItem(4, Stacks.tag(Stacks.named(new ItemStack(Items.PHANTOM_MEMBRANE), "§dFly again §7(right-click)",
                    "§7Back to the start of " + c.color() + c.name(), "§7Other courses: §e/elytra"), "flyagain"));
        }
    }

    /** May this item be used in the lobby right now (bow at the range, rod at the pond, rockets in flight...)? */
    public static boolean allowUse(ServerPlayer p, ItemStack held) {
        UUID id = p.getUUID();
        if (Duels.inDuel(id)) return Duels.allowUse(id, held);
        if (held.is(Items.BOW) && ARCHERY.containsKey(id)) return true;
        if (held.is(Items.FISHING_ROD) && FISHING.contains(id)) return true;
        if (held.is(Items.FIREWORK_ROCKET) && ELYTRA.containsKey(id)) {
            Flight f = ELYTRA.get(id);
            if (p.isFallFlying() && f.rockets > 0) {
                f.rockets--;
                return true;
            }
        }
        return false;
    }

    /** Right-click on one of our lobby items; true if it was ours. */
    static boolean useItem(ServerPlayer p, ItemStack held) {
        if (Stacks.is(held, "flyagain")) {
            int course = LAST_COURSE.getOrDefault(p.getUUID(), 0);
            Lobby.later(p, () -> toCourse(p, course));
            return true;
        }
        if (Stacks.is(held, "guide")) {
            Guide.open(p);
            return true;
        }
        return false;
    }

    /** Leaves every activity (going to a game, island, etc.). */
    public static void leaveAll(ServerPlayer p) {
        UUID id = p.getUUID();
        SPLEEF_QUEUE.remove(id);
        if (ALIVE.remove(id)) checkSpleefEnd();
        ARCHERY.remove(id);
        MAZE.remove(id);
        ELYTRA.remove(id);
        FISHING.remove(id);
        FLY_AGAIN.remove(id);
        Duels.forfeit(p);
        if (!p.getItemBySlot(EquipmentSlot.CHEST).isEmpty() && Lobby.isLobby(p.level())) p.setItemSlot(EquipmentSlot.CHEST, ItemStack.EMPTY);
    }

    // =====================================================================
    // Every tick
    // =====================================================================

    /** Once per tick, before the players. */
    static void tick(ServerLevel lobby, long ticks) {
        now = ticks;
        if (server == null) server = lobby.getServer();
        spleefTick(lobby);
        archeryTargets(lobby);
        archeryArrows(lobby);
        Duels.tick(lobby, ticks);
        if (ticks % 4 == 0) {
            // Fishing XP orbs would mess up the level bar: the lobby gives no vanilla XP.
            for (ExperienceOrb orb : lobby.getEntitiesOfClass(ExperienceOrb.class, new AABB(POND_X - 60, G - 5, POND_Z - 60, POND_X + 60, G + 20, POND_Z + 60))) orb.discard();
        }
        if (ticks % 200 == 0) refreshBoards();
    }

    /** Every tick for each (non-bot) player in the lobby. */
    static void tick(ServerPlayer p) {
        UUID id = p.getUUID();
        double x = p.getX(), y = p.getY(), z = p.getZ();
        if (Duels.inDuel(id)) return;

        // Elytra tower lift.
        if (TOWER_LIFT.contains(x, y, z) && p.onGround()) {
            toTower(p);
            return;
        }
        elytraTick(p);
        archeryTick(p);
        mazeTick(p);
        fishingTick(p);
        if (SPLEEF_QUEUE.contains(id) && Math.hypot(x - SPLEEF_X, z - SPLEEF_Z) > 45) {
            SPLEEF_QUEUE.remove(id);
            Lobby.msg(p, "§7You walked away from the Spleef Pit, so you left the queue.");
        }
    }

    static ItemStack unbreakable(ItemStack s) {
        s.set(DataComponents.UNBREAKABLE, Unit.INSTANCE);
        return s;
    }

    private static void sound(ServerPlayer p, net.minecraft.sounds.SoundEvent e, float pitch) {
        p.level().playSound(null, p.getX(), p.getY(), p.getZ(), e, SoundSource.PLAYERS, 1f, pitch);
    }

    private static void teleport(ServerPlayer p, Spot s) {
        Parkour.cancel(p, false);
        p.teleportTo(Lobby.level(), s.x(), s.y(), s.z(), Set.of(), s.yaw(), 0f, true);
        p.setDeltaMovement(0, 0, 0);
        p.resetFallDistance();
    }

    /** Menu shortcuts. */
    public static void goTo(ServerPlayer p, String what) {
        if (!Lobby.isLobby(p.level())) return;
        leaveAll(p);
        switch (what) {
            case "maze" -> teleport(p, MAZE_SPOT);
            case "spleef" -> spleefJoin(p);
            case "duels" -> teleport(p, DUEL_SPOT);
            case "archery" -> teleport(p, ARCHERY_SPOT);
            case "pond" -> teleport(p, POND_SPOT);
            case "elytra" -> ElytraCourses.openMenu(p); // 5.9: pick one of the courses
            case "town" -> teleport(p, TownDesign.TOWN_SPOT);
            case "mine" -> teleport(p, TownDesign.MINE_SPOT);
            default -> {}
        }
        Lobby.refreshKit(p);
    }

    // =====================================================================
    // Saving: best scores, names, daily rewards, fishing counts
    // =====================================================================

    private static final Map<String, Map<UUID, Integer>> BEST = new HashMap<>(); // activity -> player -> best (ticks or points)
    private static final Map<UUID, String> NAMES = new HashMap<>();
    private static final Map<String, String> REWARDED = new HashMap<>();       // "activity:uuid" -> day
    private static final Map<UUID, int[]> FISH_TODAY = new HashMap<>();        // {paid catches, keys} today
    private static String fishDay = "";
    private static boolean loaded = false;

    private static void load() {
        if (loaded) return;
        loaded = true;
        JsonObject root = Stacks.readFile("activities.json");
        if (root.has("best")) for (Map.Entry<String, JsonElement> a : root.getAsJsonObject("best").entrySet()) {
            Map<UUID, Integer> m = new HashMap<>();
            for (Map.Entry<String, JsonElement> e : a.getValue().getAsJsonObject().entrySet()) m.put(UUID.fromString(e.getKey()), e.getValue().getAsInt());
            BEST.put(a.getKey(), m);
        }
        if (root.has("names")) for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("names").entrySet()) NAMES.put(UUID.fromString(e.getKey()), e.getValue().getAsString());
        if (root.has("rewarded")) for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("rewarded").entrySet()) REWARDED.put(e.getKey(), e.getValue().getAsString());
        if (root.has("fishDay")) fishDay = root.get("fishDay").getAsString();
        if (root.has("fish")) for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("fish").entrySet()) {
            var a = e.getValue().getAsJsonArray();
            FISH_TODAY.put(UUID.fromString(e.getKey()), new int[]{a.get(0).getAsInt(), a.get(1).getAsInt()});
        }
    }

    private static void save() {
        JsonObject root = new JsonObject(), best = new JsonObject(), names = new JsonObject(), rewarded = new JsonObject(), fish = new JsonObject();
        BEST.forEach((a, m) -> {
            JsonObject o = new JsonObject();
            m.forEach((k, v) -> o.addProperty(k.toString(), v));
            best.add(a, o);
        });
        NAMES.forEach((k, v) -> names.addProperty(k.toString(), v));
        String today = LocalDate.now().toString();
        REWARDED.forEach((k, v) -> { if (v.equals(today)) rewarded.addProperty(k, v); });
        FISH_TODAY.forEach((k, v) -> {
            var a = new com.google.gson.JsonArray();
            a.add(v[0]);
            a.add(v[1]);
            fish.add(k.toString(), a);
        });
        root.add("best", best);
        root.add("names", names);
        root.add("rewarded", rewarded);
        root.addProperty("fishDay", fishDay);
        root.add("fish", fish);
        Stacks.writeFile("activities.json", root);
    }

    /** True the first time today for this activity (and marks it). */
    private static boolean firstToday(String activity, UUID id) {
        load();
        String key = activity + ":" + id, today = LocalDate.now().toString();
        if (today.equals(REWARDED.get(key))) return false;
        REWARDED.put(key, today);
        return true;
    }

    /** Records a score; lower = better for times, higher = better for points. Returns the old best (or null). */
    private static Integer record(String activity, ServerPlayer p, int value, boolean lowerBetter) {
        load();
        Map<UUID, Integer> m = BEST.computeIfAbsent(activity, k -> new HashMap<>());
        Integer old = m.get(p.getUUID());
        if (old == null || (lowerBetter ? value < old : value > old)) m.put(p.getUUID(), value);
        NAMES.put(p.getUUID(), p.getName().getString());
        save();
        refreshBoards();
        return old;
    }

    /** 5.9: a player's best for an activity (ticks or points), or null. */
    static Integer bestTime(String activity, UUID id) {
        load();
        return BEST.getOrDefault(activity, Map.of()).get(id);
    }

    private static void refreshBoards() {
        board("maze", "§2§lMAZE - BEST TIMES", true, true);
        board("archery", "§6§lARCHERY - HIGH SCORES", false, false);
        for (ElytraCourses.Course c : ElytraCourses.ALL)
            board(c.recordKey(), c.color() + "§l" + (c.tower() ? "ELYTRA RINGS" : c.name().toUpperCase()) + " - BEST TIMES", true, true);
        board("spleef", "§f§lSPLEEF - MOST WINS", false, false);
        board("fish", "§b§lFISHING - MOST CATCHES", false, false);
        if (islandBoard != null) {
            List<String> lines = new ArrayList<>(List.of("§a§lTOP SKY ISLANDS", "§7Island level grows with what you build"));
            List<String> top = com.aidan.skyserver.island.Islands.top(5);
            if (top.isEmpty()) lines.add("§7nobody yet - type §e/is");
            lines.addAll(top);
            islandBoard.setLines(lines);
        }
    }

    private static void board(String activity, String title, boolean lowerBetter, boolean isTime) {
        Holograms.Holo h = BOARDS.get(activity);
        if (h == null) return;
        List<Map.Entry<UUID, Integer>> top = new ArrayList<>(BEST.getOrDefault(activity, Map.of()).entrySet());
        top.sort(lowerBetter ? Map.Entry.comparingByValue() : Map.Entry.<UUID, Integer>comparingByValue().reversed());
        List<String> lines = new ArrayList<>();
        lines.add(title);
        if (top.isEmpty()) lines.add("§7nobody yet");
        for (int i = 0; i < Math.min(5, top.size()); i++) {
            int v = top.get(i).getValue();
            lines.add("§6" + (i + 1) + ". §f" + NAMES.getOrDefault(top.get(i).getKey(), "?") + " §7" + (isTime ? Parkour.time(v) : String.valueOf(v)));
        }
        h.setLines(lines);
    }

    // =====================================================================
    // Spleef / TNT Run
    // =====================================================================

    private static final Set<UUID> SPLEEF_QUEUE = new LinkedHashSet<>();
    private static final Set<UUID> ALIVE = new HashSet<>();
    private static final Set<UUID> IN_ROUND = new HashSet<>();
    private static final Map<Long, Long> FALLING = new HashMap<>(); // floor block -> tick it vanishes (TNT Run)
    private static final Map<UUID, Integer> TNT_FLOOR_SEEN = new HashMap<>();
    private static int spleefState = 0; // 0 idle, 1 countdown, 2 running
    private static long spleefTimer = 0, roundStart = 0;
    private static int spleefMode = 1;  // 0 spleef, 1 TNT run (swaps every round; first round is spleef)
    private static final int SPLEEF_COUNTDOWN = 15 * 20, SPLEEF_GRACE = 3 * 20, SPLEEF_DECAY_AFTER = 90 * 20, SPLEEF_MAX = 240 * 20;
    /** Bots fill the pit up to this many players once a real player has waited a few seconds. */
    static final int SPLEEF_FILL = 8, SPLEEF_FILL_AFTER = 8 * 20;
    /** Most players in one round (the rest wait for the next one). */
    static final int SPLEEF_MAX_PLAYERS = 8;
    private static final Set<UUID> SPLEEF_BOTS = new HashSet<>();
    private static final List<UUID> ELIMINATED = new ArrayList<>();
    private static long humanWaitingSince = -1;
    private static int spleefRound = 0;

    // ---- For the Spleef bots ----

    public static boolean spleefAlive(ServerPlayer p) {
        return spleefState == 2 && ALIVE.contains(p.getUUID());
    }

    public static int spleefMode() {
        return spleefMode;
    }

    public static int spleefRound() {
        return spleefRound;
    }

    /** True once the round's grace period is over. */
    public static boolean spleefStarted() {
        return spleefState == 2 && now - roundStart >= SPLEEF_GRACE;
    }

    public static List<ServerPlayer> spleefOpponents(ServerPlayer self) {
        List<ServerPlayer> l = alive();
        l.removeIf(o -> o.getUUID().equals(self.getUUID()));
        return l;
    }

    /** A bot "left-clicks" a floor block (same rules as a player, within reach). */
    public static boolean botBreak(ServerPlayer bot, BlockPos pos) {
        if (bot.getEyePosition().distanceTo(net.minecraft.world.phys.Vec3.atCenterOf(pos)) > 4.6) return false;
        ServerLevel lobby = Lobby.level();
        if (lobby == null || !lobby.getBlockState(pos).is(Blocks.SNOW_BLOCK)) return false;
        return spleefBreak(bot, lobby, pos);
    }

    private static boolean isBot(UUID id) {
        return com.aidan.skyserver.bots.BotManager.isBot(id);
    }

    private static int humans(Collection<UUID> ids) {
        int n = 0;
        for (UUID id : ids) if (!isBot(id)) n++;
        return n;
    }

    /** Adds bots so a real player (or two) can play with a full pit. */
    static int addSpleefBots(int count) {
        ServerLevel lobby = Lobby.level();
        if (lobby == null || !com.aidan.skyserver.bots.BotManager.ENABLED) return 0;
        List<com.aidan.skyserver.bots.Difficulty> mix = com.aidan.skyserver.bots.Difficulty.randomMix(count, RNG);
        for (com.aidan.skyserver.bots.Difficulty d : mix) {
            com.aidan.skyserver.bots.BotPlayer bot = com.aidan.skyserver.bots.BotManager.spawn(d);
            bot.setBrain(new com.aidan.skyserver.bots.SpleefBrain(bot));
            SPLEEF_BOTS.add(bot.getUUID());
            teleport(bot, SPLEEF_STANDS);
            SPLEEF_QUEUE.add(bot.getUUID());
        }
        return mix.size();
    }

    private static void removeSpleefBots() {
        for (UUID id : new ArrayList<>(SPLEEF_BOTS)) {
            ServerPlayer b = server.getPlayerList().getPlayer(id);
            SPLEEF_QUEUE.remove(id);
            ALIVE.remove(id);
            if (b != null) com.aidan.skyserver.bots.BotManager.remove(b);
        }
        SPLEEF_BOTS.clear();
    }

    static void spleefJoin(ServerPlayer p) {
        if (!Lobby.isLobby(p.level())) {
            Lobby.msg(p, "§cGo to the lobby first (§e/lobby§c).");
            return;
        }
        if (ALIVE.contains(p.getUUID())) return;
        Duels.forfeit(p);
        ARCHERY.remove(p.getUUID());
        MAZE.remove(p.getUUID());
        ELYTRA.remove(p.getUUID());
        FISHING.remove(p.getUUID());
        SPLEEF_QUEUE.add(p.getUUID());
        teleport(p, SPLEEF_STANDS);
        Lobby.refreshKit(p);
        int n = SPLEEF_QUEUE.size();
        Lobby.msg(p, "§fYou're in the §lSpleef§f queue §7(" + n + " waiting). §7Rounds start when 2 or more are waiting. §e/spleef leave §7to stop.");
    }

    private static void spleefTick(ServerLevel lobby) {
        // Drop anyone who's gone.
        SPLEEF_QUEUE.removeIf(id -> {
            ServerPlayer q = server.getPlayerList().getPlayer(id);
            return q == null || !Lobby.isLobby(q.level());
        });
        // Bots: fill the pit when a real player is waiting; leave when no real players are left.
        int waiting = humans(SPLEEF_QUEUE);
        if (spleefState != 2) {
            if (waiting == 0) {
                humanWaitingSince = -1;
                if (!SPLEEF_BOTS.isEmpty()) removeSpleefBots();
            } else {
                if (humanWaitingSince < 0) humanWaitingSince = now;
                if (now - humanWaitingSince >= SPLEEF_FILL_AFTER && SPLEEF_QUEUE.size() < SPLEEF_FILL) {
                    int added = addSpleefBots(SPLEEF_FILL - SPLEEF_QUEUE.size());
                    if (added > 0) for (ServerPlayer q : queued())
                        if (!isBot(q.getUUID())) Lobby.msg(q, "§7" + added + " bot" + (added == 1 ? "" : "s") + " joined the Spleef queue to fill the pit.");
                }
            }
        }
        switch (spleefState) {
            case 0 -> {
                if (SPLEEF_QUEUE.size() >= 2) {
                    spleefState = 1;
                    spleefTimer = SPLEEF_COUNTDOWN;
                }
            }
            case 1 -> {
                if (SPLEEF_QUEUE.size() < 2) {
                    spleefState = 0;
                    for (ServerPlayer q : queued()) Announce.actionBar(q, "§7Waiting for one more player...");
                    return;
                }
                spleefTimer--;
                if (spleefTimer % 20 == 0) {
                    int sec = (int) (spleefTimer / 20);
                    String next = spleefMode == 1 ? "Spleef" : "TNT Run";
                    for (ServerPlayer q : queued()) Announce.actionBar(q, "§f" + next + " §7starts in §e" + sec + "s §7(" + SPLEEF_QUEUE.size() + " players)");
                    Announce.countdown(queued(), sec, "§f", "§7" + next);
                }
                if (spleefTimer <= 0) startRound(lobby);
            }
            case 2 -> runRound(lobby);
            default -> spleefState = 0;
        }
    }

    private static List<ServerPlayer> queued() {
        List<ServerPlayer> l = new ArrayList<>();
        for (UUID id : SPLEEF_QUEUE) {
            ServerPlayer q = server.getPlayerList().getPlayer(id);
            if (q != null) l.add(q);
        }
        return l;
    }

    private static List<ServerPlayer> alive() {
        List<ServerPlayer> l = new ArrayList<>();
        for (UUID id : ALIVE) {
            ServerPlayer q = server.getPlayerList().getPlayer(id);
            if (q != null) l.add(q);
        }
        return l;
    }

    private static void resetFloor(ServerLevel lobby, BlockState state) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        boolean tnt = state.is(Blocks.TNT);
        for (int x = SPLEEF_X - SPLEEF_R; x <= SPLEEF_X + SPLEEF_R; x++)
            for (int z = SPLEEF_Z - SPLEEF_R; z <= SPLEEF_Z + SPLEEF_R; z++)
                if (Math.hypot(x - SPLEEF_X, z - SPLEEF_Z) <= SPLEEF_R + 0.5) {
                    lobby.setBlock(pos.set(x, SPLEEF_FLOOR, z), state, Block.UPDATE_CLIENTS);
                    // The lower floors are only there for TNT Run (Spleef is one floor: fall = out).
                    for (int i = 1; i < TNT_FLOORS; i++)
                        lobby.setBlock(pos.set(x, tntFloorY(i), z), tnt ? state : Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
                }
    }

    /** TNT Run: has someone stepped on this block already (it's about to drop)? For the bots. */
    public static boolean tntDoomed(BlockPos pos) {
        return FALLING.containsKey(pos.asLong());
    }

    private static void startRound(ServerLevel lobby) {
        spleefMode = 1 - spleefMode;
        resetFloor(lobby, spleefMode == 0 ? Blocks.SNOW_BLOCK.defaultBlockState() : Blocks.TNT.defaultBlockState());
        FALLING.clear();
        TNT_FLOOR_SEEN.clear();
        ALIVE.clear();
        IN_ROUND.clear();
        ELIMINATED.clear();
        spleefRound++;
        List<ServerPlayer> players = queued();
        // Up to 8 per round: real players first, the rest wait for the next one.
        players.sort(Comparator.comparing(q -> isBot(q.getUUID())));
        if (players.size() > SPLEEF_MAX_PLAYERS) players = new ArrayList<>(players.subList(0, SPLEEF_MAX_PLAYERS));
        for (ServerPlayer q : players) SPLEEF_QUEUE.remove(q.getUUID());
        for (UUID id : SPLEEF_QUEUE) {
            ServerPlayer q = server.getPlayerList().getPlayer(id);
            if (q != null) Lobby.msg(q, "§7The pit is full (8) - you're first in line for the next round.");
        }
        for (int i = 0; i < players.size(); i++) {
            ServerPlayer q = players.get(i);
            double ang = i * Math.PI * 2 / players.size();
            double r = SPLEEF_R * 0.6;
            Parkour.cancel(q, false);
            q.teleportTo(lobby, SPLEEF_X + 0.5 + Math.cos(ang) * r, SPLEEF_FLOOR + 1, SPLEEF_Z + 0.5 + Math.sin(ang) * r, Set.of(),
                    (float) Math.toDegrees(Math.atan2(Math.cos(ang), -Math.sin(ang))), 0f, true);
            q.setDeltaMovement(0, 0, 0);
            ALIVE.add(q.getUUID());
            IN_ROUND.add(q.getUUID());
            Lobby.refreshKit(q);
        }
        roundStart = now;
        spleefState = 2;
        String how = spleefMode == 0 ? "§7Left-click the snow to break it under the others!" : "§7The floor falls away behind you - keep moving! §e4 floors§7: fall through all of them and you're out.";
        Announce.go(players, spleefMode == 0 ? "§f§lSPLEEF!" : "§c§lTNT RUN!", how);
        for (ServerPlayer q : players) Lobby.msg(q, (spleefMode == 0 ? "§f§lSpleef! " : "§c§lTNT Run! ") + how + " §7Last one standing wins.");
    }

    private static boolean spleefBreak(ServerPlayer p, ServerLevel level, BlockPos pos) {
        if (spleefState != 2 || spleefMode != 0 || !ALIVE.contains(p.getUUID())) return false;
        if (pos.getY() != SPLEEF_FLOOR || Math.hypot(pos.getX() - SPLEEF_X, pos.getZ() - SPLEEF_Z) > SPLEEF_R + 0.5) return false;
        if (now - roundStart < SPLEEF_GRACE) return true;
        if (level.getBlockState(pos).is(Blocks.SNOW_BLOCK)) {
            level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
            level.playSound(null, pos, SoundEvents.SNOW_BREAK, SoundSource.BLOCKS, 1f, 1f);
        }
        return true;
    }

    private static void runRound(ServerLevel lobby) {
        long t = now - roundStart;
        // Watch how real players play spleef (for the bots).
        if (spleefMode == 0 && t > SPLEEF_GRACE && t % 20 == 0) {
            List<ServerPlayer> all = alive();
            for (ServerPlayer q : all) {
                if (isBot(q.getUUID())) continue;
                double nearest = 99;
                for (ServerPlayer o : all) if (o != q) nearest = Math.min(nearest, q.distanceTo(o));
                if (nearest < 99) com.aidan.skyserver.bots.MiniLearning.observeSpleef(nearest < 4);
            }
        }
        // Only bots left standing: no need to make the real players watch them finish.
        if (humans(IN_ROUND) > 0 && humans(ALIVE) == 0 && !ALIVE.isEmpty()) {
            for (UUID id : IN_ROUND) {
                ServerPlayer q = server.getPlayerList().getPlayer(id);
                if (q != null && !isBot(id)) Lobby.msg(q, "§7Only bots are left - round over.");
            }
            endRound(lobby, null);
            return;
        }
        for (ServerPlayer q : alive()) {
            if (!Lobby.isLobby(q.level()) || Math.hypot(q.getX() - SPLEEF_X, q.getZ() - SPLEEF_Z) > SPLEEF_R + 4 || q.getY() > SPLEEF_FLOOR + 6) {
                spleefOut(q, "left the pit");
                continue;
            }
            if (q.getY() < (spleefMode == 0 ? SPLEEF_OUT_Y : TNT_OUT_Y)) {
                spleefOut(q, spleefMode == 0 ? "fell" : "fell through every floor");
                continue;
            }
            // TNT Run: the blocks under your feet go shortly after you step on them (on whichever floor you're on).
            if (spleefMode == 1 && t > SPLEEF_GRACE) {
                int fl = tntFloorAt(q.getY());
                if (fl < TNT_FLOORS && q.getY() < tntFloorY(fl) + 1.6) {
                    AABB box = q.getBoundingBox();
                    for (double ox : new double[]{box.minX + 0.01, box.maxX - 0.01})
                        for (double oz : new double[]{box.minZ + 0.01, box.maxZ - 0.01}) {
                            long k = BlockPos.asLong((int) Math.floor(ox), tntFloorY(fl), (int) Math.floor(oz));
                            FALLING.putIfAbsent(k, now + 7);
                        }
                }
                Integer seen = TNT_FLOOR_SEEN.put(q.getUUID(), fl);
                if (seen != null && fl > seen && fl < TNT_FLOORS)
                    Announce.actionBar(q, "§eFloor " + (fl + 1) + " of " + TNT_FLOORS + (fl == TNT_FLOORS - 1 ? " §c- last one!" : ""));
            }
        }
        // ALIVE may have changed.
        if (spleefState != 2) return;
        if (!FALLING.isEmpty()) {
            BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
            for (Iterator<Map.Entry<Long, Long>> it = FALLING.entrySet().iterator(); it.hasNext(); ) {
                Map.Entry<Long, Long> e = it.next();
                if (e.getValue() > now) continue;
                it.remove();
                pos.set(e.getKey());
                if (lobby.getBlockState(pos).is(Blocks.TNT)) lobby.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
            }
        }
        // Long spleef rounds: the floor starts to crumble so nobody can stall forever.
        if (t > SPLEEF_DECAY_AFTER && t % 10 == 0) {
            Random rng = RNG;
            BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
            for (int i = 0; i < 4; i++) {
                int x = SPLEEF_X + rng.nextInt(SPLEEF_R * 2 + 1) - SPLEEF_R, z = SPLEEF_Z + rng.nextInt(SPLEEF_R * 2 + 1) - SPLEEF_R;
                pos.set(x, SPLEEF_FLOOR, z);
                if (!lobby.getBlockState(pos).isAir()) lobby.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
            }
        }
        if (t > SPLEEF_MAX) {
            for (ServerPlayer q : alive()) Lobby.msg(q, "§7Time's up - nobody wins this round.");
            ALIVE.clear();
            endRound(lobby, null);
        }
    }

    private static void spleefOut(ServerPlayer q, String why) {
        if (!ALIVE.remove(q.getUUID())) return;
        ELIMINATED.add(q.getUUID());
        if (Lobby.isLobby(q.level())) {
            teleport(q, SPLEEF_STANDS);
            Lobby.refreshKit(q);
        }
        int left = ALIVE.size();
        for (ServerPlayer o : alive()) Announce.actionBar(o, "§e" + q.getName().getString() + " §7" + why + " - §f" + left + " left");
        Announce.actionBar(q, "§cYou're out! §7" + left + " still standing.");
        checkSpleefEnd();
    }

    private static void checkSpleefEnd() {
        if (spleefState != 2 || ALIVE.size() > 1) return;
        ServerPlayer winner = alive().isEmpty() ? null : alive().get(0);
        ServerLevel lobby = Lobby.level();
        if (lobby != null) endRound(lobby, winner);
    }

    private static void endRound(ServerLevel lobby, ServerPlayer winner) {
        int players = IN_ROUND.size();
        int realPlayers = humans(IN_ROUND);
        String name = spleefMode == 0 ? "Spleef" : "TNT Run";
        // Bots learn how their plan did: winner 1st, then in reverse order of going out.
        List<UUID> order = new ArrayList<>();
        if (winner != null) order.add(winner.getUUID());
        for (UUID id : ALIVE) if (!order.contains(id)) order.add(id);
        for (int i = ELIMINATED.size() - 1; i >= 0; i--) if (!order.contains(ELIMINATED.get(i))) order.add(ELIMINATED.get(i));
        boolean learned = false;
        for (int i = 0; i < order.size(); i++) {
            ServerPlayer q = server.getPlayerList().getPlayer(order.get(i));
            if (q instanceof com.aidan.skyserver.bots.BotPlayer bp && bp.brain() instanceof com.aidan.skyserver.bots.SpleefBrain sb) {
                int place = ALIVE.contains(order.get(i)) && winner == null ? 1 : i + 1;
                if (spleefMode == 0) com.aidan.skyserver.bots.MiniLearning.spleefResult(sb.spleefPlan(), place, players);
                else com.aidan.skyserver.bots.MiniLearning.runResult(sb.runPlan(), place, players);
                learned = true;
            }
        }
        if (learned) com.aidan.skyserver.bots.MiniLearning.save();
        if (winner != null && !isBot(winner.getUUID())) {
            Announce.title(winner, "§6§lYOU WIN!", "§7" + name, 5, 50, 10);
            sound(winner, SoundEvents.PLAYER_LEVELUP, 1f);
            if (realPlayers >= 2) {
                Rewards.reward(server, winner, SPLEEF_WIN_MONEY, SPLEEF_WIN_XP, "(won a " + name + " round)");
                if (firstToday("spleef", winner.getUUID())) Rewards.pay(server, winner, SPLEEF_FIRST_WIN, "(first Spleef Pit win today)");
                load();
                BEST.computeIfAbsent("spleef", k -> new HashMap<>()).merge(winner.getUUID(), 1, Integer::sum);
                Events.spleefWin(winner);
                NAMES.put(winner.getUUID(), winner.getName().getString());
                save();
            } else if (players >= 2) {
                Levels.addXp(server, winner, 10);
                Lobby.msg(winner, "§7(Wins against bots give XP and quest progress; money needs 2+ real players.)");
            }
            if (players >= 2) {
                Quests.progress(winner, "spleef", 1);
                Skills.add(winner, Skills.Skill.COMBAT, realPlayers >= 2 ? 100 : 40);
                PlayerStats.add(winner, "spleef_win", 1);
            }
        }
        if (winner != null) {
            for (UUID id : IN_ROUND) {
                ServerPlayer q = server.getPlayerList().getPlayer(id);
                if (q != null && q != winner) Lobby.msg(q, "§e" + winner.getName().getString() + " §7won the " + name + " round.");
            }
        }
        ALIVE.clear();
        FALLING.clear();
        resetFloor(lobby, Blocks.SNOW_BLOCK.defaultBlockState());
        // Everyone from the round who's still at the pit goes back in the queue for the next one.
        for (UUID id : IN_ROUND) {
            ServerPlayer q = server.getPlayerList().getPlayer(id);
            if (q == null || !Lobby.isLobby(q.level())) continue;
            if (Math.hypot(q.getX() - SPLEEF_X, q.getZ() - SPLEEF_Z) < 30) {
                if (Math.hypot(q.getX() - SPLEEF_X, q.getZ() - SPLEEF_Z) <= SPLEEF_R + 1.5) teleport(q, SPLEEF_STANDS); // anyone still in the pit (any floor)
                SPLEEF_QUEUE.add(id);
                Lobby.refreshKit(q);
            }
        }
        IN_ROUND.clear();
        spleefState = 0;
        refreshBoards();
    }

    // =====================================================================
    // Archery
    // =====================================================================

    private static final class Round {
        final long start;
        int score = 0, arrows = 24, hits = 0;

        Round(long start) {
            this.start = start;
        }
    }

    private static final Map<UUID, Round> ARCHERY = new HashMap<>();
    private static final Map<UUID, Long> ARCHERY_COOLDOWN = new HashMap<>();
    private static final Map<Integer, double[]> ARROWS = new HashMap<>(); // arrow id -> {x, y, z, vx, vy, vz, stillTicks}
    private static final int ARCHERY_TICKS = 60 * 20;

    private static void archeryTick(ServerPlayer p) {
        UUID id = p.getUUID();
        Round r = ARCHERY.get(id);
        if (r == null) {
            Long cd = ARCHERY_COOLDOWN.get(id);
            if (ARCHERY_LINE.contains(p.getX(), p.getY(), p.getZ()) && (cd == null || cd < now) && !ELYTRA.containsKey(id) && !ALIVE.contains(id)) {
                FISHING.remove(id);
                MAZE.remove(id);
                ARCHERY.put(id, new Round(now));
                Lobby.refreshKit(p);
                Announce.title(p, "§6§lARCHERY", "§760 seconds - go!", 0, 30, 10);
                Lobby.msg(p, "§6Archery round! §760 seconds, 24 arrows. Fixed targets 1, §bsliding §72 and §dpop-up §73 points, "
                        + "the §egold bonus target §75. Centre block = double (moving ones only). They speed up as the round goes on. §fGoals: 40 / 70 / 100.");
            }
            return;
        }
        long left = ARCHERY_TICKS - (now - r.start);
        if (!ARCHERY_RANGE.contains(p.getX(), p.getY(), p.getZ())) {
            endArchery(p, r, "§7You left the range.");
            return;
        }
        if (left <= 0 || (r.arrows <= 0 && now % 20 == 0 && noArrowsFlying(id))) {
            endArchery(p, r, null);
            return;
        }
        // Keep count of arrows actually in the inventory (shooting uses them).
        int have = 0;
        Inventory inv = p.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) if (inv.getItem(i).is(Items.ARROW)) have += inv.getItem(i).getCount();
        r.arrows = have;
        if (now % 5 == 0) Announce.actionBar(p, "§6Score §f" + r.score + "  §7time §f" + (left / 20) + "s  §7arrows §f" + r.arrows);
    }

    private static boolean noArrowsFlying(UUID owner) {
        ServerLevel lobby = Lobby.level();
        if (lobby == null) return true;
        AABB box = new AABB(ARCHERY_RANGE.x1(), ARCHERY_RANGE.y1(), ARCHERY_RANGE.z1(), ARCHERY_RANGE.x2() + 1, ARCHERY_RANGE.y2() + 1, ARCHERY_RANGE.z2() + 1);
        for (AbstractArrow a : lobby.getEntitiesOfClass(AbstractArrow.class, box))
            if (a.getOwner() != null && a.getOwner().getUUID().equals(owner)) return false;
        return true;
    }

    private static void endArchery(ServerPlayer p, Round r, String why) {
        ARCHERY.remove(p.getUUID());
        ARCHERY_COOLDOWN.put(p.getUUID(), now + 60); // step off the line before the next round
        if (why != null) Lobby.msg(p, why);
        Lobby.msg(p, "§6§lArchery: §f" + r.score + " points §7(" + r.hits + " hits)");
        Integer old = record("archery", p, r.score, false);
        if (old != null && r.score > old) Lobby.msg(p, "§aNew personal best! §7(was " + old + ")");
        Quests.progress(p, "archery", r.score);
        if (r.score >= ARCHERY_GOALS[0] && firstToday("archery", p.getUUID()))
            Rewards.reward(server, p, ARCHERY_MONEY, ARCHERY_XP, "(archery goal " + ARCHERY_GOALS[0] + " - today)");
        if (r.score >= ARCHERY_GOALS[1] && firstToday("archery70", p.getUUID()))
            Rewards.reward(server, p, 250, 60, "(archery goal " + ARCHERY_GOALS[1] + " - today)");
        if (r.score >= ARCHERY_GOALS[2] && firstToday("archery100", p.getUUID())) {
            Rewards.reward(server, p, 400, 100, "(archery goal " + ARCHERY_GOALS[2] + " - today)");
            Keys.reward(server, p.getUUID(), 0, 1, "(archery goal " + ARCHERY_GOALS[2] + ")");
        }
        int next = -1;
        for (int g : ARCHERY_GOALS) if (r.score < g) { next = g; break; }
        if (next > 0) Lobby.msg(p, "§7Next goal: §f" + next + " §7points.");
        Skills.add(p, Skills.Skill.COMBAT, Math.min(150, r.score));
        sound(p, SoundEvents.PLAYER_LEVELUP, 1.2f);
        Lobby.refreshKit(p);
    }

    // ---- Moving targets ----

    /** A target that moves: a 3x3 slider, a 3x3 pop-up, or the single-block gold bonus target. */
    private static final class Mover {
        final int kind;          // 0 slider, 1 pop-up, 2 bonus
        final int minX, maxX;    // where it can be
        int x, y, z, dir = 1;
        int px, py;              // where it was just before its last move (an arrow that hit it there still counts)
        long movedAt = -100;
        boolean shown = false;
        long nextMove = 0, hideAt = 0;

        Mover(int kind, int minX, int maxX, int x, int y, int z) {
            this.kind = kind;
            this.minX = minX;
            this.maxX = maxX;
            this.x = x;
            this.y = y;
            this.z = z;
        }

        int points() {
            return kind == 0 ? 2 : kind == 1 ? 3 : 5;
        }

        int half() {
            return kind == 2 ? 0 : 1;
        }

        boolean hits(int hx, int hy, int hz) {
            if (hz != z) return false;
            if (shown && Math.abs(hx - x) <= half() && Math.abs(hy - y) <= half()) return true;
            return now - movedAt <= 3 && Math.abs(hx - px) <= half() && Math.abs(hy - py) <= half();
        }

        boolean centre(int hx, int hy) {
            return (hx == x && hy == y) || (now - movedAt <= 3 && hx == px && hy == py);
        }
    }

    private static final List<Mover> MOVERS = new ArrayList<>();
    private static boolean moversCleared = false;
    private static long archeryBusySince = -1;

    private static void initMovers() {
        if (!MOVERS.isEmpty()) return;
        for (int lane = 0; lane < 5; lane++) {
            int lx = ARCH_X1 + 5 + lane * 9;
            Mover m = new Mover(0, lx - 2, lx + 2, lx, ARCH_SLIDE_Y + 1, ARCH_SLIDE_Z);
            m.dir = lane % 2 == 0 ? 1 : -1;
            MOVERS.add(m);
        }
        for (int i = 0; i < 3; i++) MOVERS.add(new Mover(1, ARCH_X1 + 2, ARCH_X2 - 2, ARCH_X1 + 8 + i * 15, G + 4, ARCH_POP_Z));
        MOVERS.add(new Mover(2, ARCH_X1 - 2, ARCH_X2 + 2, ARCH_X1, ARCH_BONUS_Y, ARCH_BONUS_Z));
    }

    private static void drawMover(ServerLevel lobby, Mover m, boolean show) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int h = m.half();
        for (int dx = -h; dx <= h; dx++)
            for (int dy = -h; dy <= h; dy++) {
                BlockState st = Blocks.AIR.defaultBlockState();
                if (show) {
                    if (dx == 0 && dy == 0) st = m.kind == 2 ? Blocks.GOLD_BLOCK.defaultBlockState() : Blocks.TARGET.defaultBlockState();
                    else if (dx == 0 || dy == 0) st = Blocks.WOOL.pick(m.kind == 0 ? net.minecraft.world.item.DyeColor.LIGHT_BLUE : net.minecraft.world.item.DyeColor.MAGENTA).defaultBlockState();
                    else st = Blocks.WOOL.pick(net.minecraft.world.item.DyeColor.WHITE).defaultBlockState();
                }
                lobby.setBlock(pos.set(m.x + dx, m.y + dy, m.z), st, Block.UPDATE_CLIENTS);
            }
        if (!show && m.shown) {
            m.px = m.x;
            m.py = m.y;
            m.movedAt = now;
        }
        m.shown = show;
    }

    /** Moves the targets while anyone is shooting; parks them when nobody is. */
    private static void archeryTargets(ServerLevel lobby) {
        initMovers();
        if (!moversCleared) {
            // After a restart a target may have been left half-way: wipe the tracks once.
            moversCleared = true;
            BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
            for (Mover m : MOVERS)
                for (int x = m.minX - 1; x <= m.maxX + 1; x++)
                    for (int y = (m.kind == 1 ? G + 3 : m.y - m.half()); y <= (m.kind == 1 ? G + 12 : m.y + m.half()); y++)
                        if (lobby.isLoaded(pos.set(x, y, m.z))) lobby.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
        }
        if (ARCHERY.isEmpty()) {
            archeryBusySince = -1;
            for (Mover m : MOVERS) if (m.shown && m.kind != 0) drawMover(lobby, m, false);
            if (now % 100 == 0) for (Mover m : MOVERS) if (m.kind == 0 && !m.shown) drawMover(lobby, m, true); // sliders wait, visible
            return;
        }
        if (archeryBusySince < 0) archeryBusySince = now;
        // Everything speeds up over the 60 seconds (the newest round sets the pace).
        long newest = 0;
        for (Round r : ARCHERY.values()) newest = Math.max(newest, r.start);
        double t = Math.min(1, (now - newest) / (double) ARCHERY_TICKS);
        for (Mover m : MOVERS) {
            if (now < m.nextMove) continue;
            switch (m.kind) {
                case 0 -> { // slider: back and forth along its rail
                    if (m.shown) drawMover(lobby, m, false);
                    if (m.x + m.dir > m.maxX || m.x + m.dir < m.minX) m.dir = -m.dir;
                    m.x += m.dir;
                    drawMover(lobby, m, true);
                    m.nextMove = now + Math.max(2, Math.round(7 - 5 * t)) + (RNG.nextInt(6) == 0 ? 6 : 0); // the odd pause
                }
                case 1 -> { // pop-up: shows somewhere for a moment, hides, shows somewhere else
                    if (m.shown) {
                        drawMover(lobby, m, false);
                        m.nextMove = now + 10 + RNG.nextInt(20);
                    } else {
                        m.x = m.minX + RNG.nextInt(m.maxX - m.minX + 1);
                        m.y = G + 3 + RNG.nextInt(8);
                        drawMover(lobby, m, true);
                        m.nextMove = now + Math.round(60 - 30 * t) + RNG.nextInt(10);
                    }
                }
                case 2 -> { // bonus: zips along the chain, wraps around
                    if (m.shown) drawMover(lobby, m, false);
                    m.x += m.dir;
                    if (m.x > m.maxX) m.x = m.minX;
                    drawMover(lobby, m, true);
                    m.nextMove = now + (t < 0.5 ? 2 : 1);
                }
                default -> {}
            }
        }
    }

    /** Watches arrows on the range: when one stops in a target, it scores. */
    private static void archeryArrows(ServerLevel lobby) {
        if (ARCHERY.isEmpty() && ARROWS.isEmpty()) return;
        AABB box = new AABB(ARCHERY_RANGE.x1(), ARCHERY_RANGE.y1(), ARCHERY_RANGE.z1(), ARCHERY_RANGE.x2() + 1, ARCHERY_RANGE.y2() + 1, ARCHERY_RANGE.z2() + 1);
        Set<Integer> seen = new HashSet<>();
        for (AbstractArrow a : lobby.getEntitiesOfClass(AbstractArrow.class, box)) {
            seen.add(a.getId());
            double[] st = ARROWS.get(a.getId());
            Vec3 pos = a.position();
            if (st == null) {
                ARROWS.put(a.getId(), new double[]{pos.x, pos.y, pos.z, 0, 0, 0, 0});
                continue;
            }
            double dx = pos.x - st[0], dy = pos.y - st[1], dz = pos.z - st[2];
            double moved = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (moved > 0.02) {
                st[0] = pos.x;
                st[1] = pos.y;
                st[2] = pos.z;
                st[3] = dx;
                st[4] = dy;
                st[5] = dz;
                st[6] = 0;
                continue;
            }
            if (++st[6] < 1) continue;
            // Stopped: find what it hit by looking a little further along its last direction.
            ARROWS.remove(a.getId());
            double len = Math.max(1e-6, Math.sqrt(st[3] * st[3] + st[4] * st[4] + st[5] * st[5]));
            Target hit = null;
            Mover moving = null;
            int hx = 0, hy = 0, hz = 0;
            for (int k = 0; k <= 6 && hit == null && moving == null; k++) {
                double t = k * 0.1;
                hx = (int) Math.floor(pos.x + st[3] / len * t);
                hy = (int) Math.floor(pos.y + st[4] / len * t);
                hz = (int) Math.floor(pos.z + st[5] / len * t);
                for (Mover m : MOVERS) if (m.hits(hx, hy, hz)) moving = m;
                if (moving == null) for (Target tg : TARGETS) if (tg.face().contains(hx + 0.5, hy + 0.5, hz + 0.5)) hit = tg;
            }
            ServerPlayer owner = a.getOwner() instanceof ServerPlayer sp ? sp : null;
            Round r = owner == null ? null : ARCHERY.get(owner.getUUID());
            if ((hit != null || moving != null) && r != null) {
                // Only moving targets give double for the centre (no camping on a fixed bullseye).
                boolean bull = moving != null && (moving.kind == 2 || moving.centre(hx, hy));
                int pts = moving != null ? moving.points() * (bull && moving.kind != 2 ? 2 : 1) : hit.points();
                if (moving != null && moving.kind == 1) {
                    // A hit pop-up drops straight away.
                    ServerLevel lv = Lobby.level();
                    if (lv != null) drawMover(lv, moving, false);
                    moving.nextMove = now + 15;
                }
                r.score += pts;
                r.hits++;
                Announce.actionBar(owner, (bull ? "§c§lBULLSEYE! " : "§a") + "+" + pts + " §7(score " + r.score + ")");
                sound(owner, SoundEvents.ARROW_HIT_PLAYER, bull ? 1.6f : 1.1f);
            }
            a.discard();
        }
        ARROWS.keySet().retainAll(seen);
    }

    // =====================================================================
    // Hedge maze
    // =====================================================================

    private static final Map<UUID, Long> MAZE = new HashMap<>();

    private static void mazeTick(ServerPlayer p) {
        UUID id = p.getUUID();
        double x = p.getX(), y = p.getY(), z = p.getZ();
        Long start = MAZE.get(id);
        if (start == null) {
            if (MAZE_START.contains(x, y, z) && z < MAZE_START.z2() + 0.7) {
                MAZE.put(id, now);
                Announce.actionBar(p, "§2Maze: §fthe clock is running! §7Find the golden fountain in the middle.");
            }
            return;
        }
        boolean inside = x >= MAZE_X && x <= MAZE_X + MAZE_SIZE && z >= MAZE_Z && z <= MAZE_Z + MAZE_SIZE + 0.5;
        if (!inside || y > G + MAZE_H + 1) {
            MAZE.remove(id);
            Announce.actionBar(p, "§7You left the maze - walk back in to try again.");
            return;
        }
        int ticks = (int) (now - start);
        if (MAZE_FINISH.contains(x, y, z)) {
            MAZE.remove(id);
            Lobby.msg(p, "§2§lMAZE SOLVED! §fin " + Parkour.time(ticks));
            Integer old = record("maze", p, ticks, true);
            if (old != null && ticks < old) Lobby.msg(p, "§aNew personal best! §7(was " + Parkour.time(old) + ")");
            Quests.progress(p, "maze", 1);
            Skills.add(p, Skills.Skill.PARKOUR, 150);
            PlayerStats.add(p, "maze", 1);
            if (firstToday("maze", p.getUUID())) Rewards.reward(server, p, MAZE_MONEY, MAZE_XP, "(first maze finish today)");
            sound(p, SoundEvents.PLAYER_LEVELUP, 1f);
            Announce.title(p, "§2§lSOLVED!", "§f" + Parkour.time(ticks), 5, 40, 10);
            return;
        }
        if (now % 4 == 0) Announce.actionBar(p, "§2Maze §f" + Parkour.time(ticks));
    }

    // =====================================================================
    // Elytra courses (5.9: any of ElytraCourses.ALL; course 0 is the tower)
    // =====================================================================

    private static final class Flight {
        final int course;
        boolean flying = false;
        long start;
        int next = 0, rockets;
        double px, py, pz;

        Flight(int course) {
            this.course = course;
            this.rockets = ElytraCourses.course(course).rockets();
        }
    }

    private static final Map<UUID, Flight> ELYTRA = new HashMap<>();
    private static final Map<UUID, Long> FLY_AGAIN = new HashMap<>(); // shows the "fly again" item for a while
    private static final Map<UUID, Integer> LAST_COURSE = new HashMap<>();
    /** 5.9: Parkour XP from repeat elytra finishes today: player -> {day, xp}. */
    private static final Map<UUID, long[]> ELYTRA_REPEAT_XP = new HashMap<>();

    static void toTower(ServerPlayer p) {
        toCourse(p, 0);
    }

    /** Teleports to a course's start (the top of the tower for course 0). */
    static void toCourse(ServerPlayer p, int course) {
        if (!Lobby.isLobby(p.level())) return;
        ElytraCourses.Course c = ElytraCourses.course(course);
        ELYTRA.remove(p.getUUID());
        FLY_AGAIN.remove(p.getUUID());
        FISHING.remove(p.getUUID());
        ARCHERY.remove(p.getUUID());
        MAZE.remove(p.getUUID());
        LAST_COURSE.put(p.getUUID(), course);
        teleport(p, c.launch());
        ((ServerLevel) p.level()).sendParticles(ParticleTypes.PORTAL, p.getX(), p.getY() + 1, p.getZ(), 40, 0.5, 1, 0.5, 0.2);
        sound(p, SoundEvents.ENDERMAN_TELEPORT, 1.2f);
        Lobby.refreshKit(p);
        Lobby.msg(p, c.color() + "§l" + c.name() + " §7(" + c.diff() + ", " + c.rings().size() + " rings): step on the §6gold pad§7 for your elytra, "
                + "jump off" + (c.tower() ? " the open (west) side" : " the open side") + ", press jump to glide and fly through every ring. You have "
                + c.rockets() + " boost rockets. §8(/elytra for the other courses)");
    }

    private static void elytraTick(ServerPlayer p) {
        UUID id = p.getUUID();
        Flight f = ELYTRA.get(id);
        Long showUntil = FLY_AGAIN.get(id);
        if (showUntil != null && showUntil < now) {
            FLY_AGAIN.remove(id);
            Lobby.refreshKit(p);
        }
        if (f == null) {
            if (!p.onGround()) return;
            for (int i = 0; i < ElytraCourses.ALL.size(); i++) {
                if (!ElytraCourses.ALL.get(i).launchPad().contains(p.getX(), p.getY(), p.getZ())) continue;
                f = new Flight(i);
                ELYTRA.put(id, f);
                LAST_COURSE.put(id, i);
                FLY_AGAIN.remove(id);
                FISHING.remove(id);
                Lobby.refreshKit(p);
                Announce.title(p, "§d§lREADY", "§7Jump off and glide through the rings", 0, 40, 10);
                return;
            }
            return;
        }
        ElytraCourses.Course c = ElytraCourses.course(f.course);
        List<Ring> rings = c.rings();
        double x = p.getX(), y = p.getY(), z = p.getZ();
        if (!f.flying) {
            if (c.launchPad().contains(x, y, z) || (p.onGround() && y > c.launchY() - 1.5)) {
                f.px = x;
                f.py = y;
                f.pz = z;
                return;
            }
            if (p.onGround()) { // walked off somewhere else
                ELYTRA.remove(id);
                Lobby.refreshKit(p);
                return;
            }
            f.flying = true;
            f.start = now;
            f.px = x;
            f.py = y;
            f.pz = z;
        }
        // Did we pass through the next ring between last tick and now?
        while (f.next < rings.size()) {
            Ring r = rings.get(f.next);
            double plane = (r.alongX() ? r.cx() : r.cz()) + 0.5;
            double a0 = r.alongX() ? f.px : f.pz, a1 = r.alongX() ? x : z;
            if ((a0 - plane) * (a1 - plane) > 0 || a0 == a1) break;
            double t = (plane - a0) / (a1 - a0);
            double cy = f.py + (y - f.py) * t + 0.9, cs = (r.alongX() ? f.pz + (z - f.pz) * t : f.px + (x - f.px) * t);
            double dist = Math.hypot(cy - (r.cy() + 0.5), cs - ((r.alongX() ? r.cz() : r.cx()) + 0.5));
            if (dist <= r.r() + 0.4) {
                f.next++;
                boolean done = f.next == rings.size();
                Announce.actionBar(p, done ? "§6§lAll rings! §7Now land on the landing pad." : "§dRing " + f.next + "/" + rings.size() + " §f" + Parkour.time((int) (now - f.start)));
                sound(p, SoundEvents.EXPERIENCE_ORB_PICKUP, 0.8f + f.next * 0.08f);
                continue;
            }
            if (dist < r.r() * 4) {
                failFlight(p, "§cMissed ring " + (f.next + 1) + "!");
                return;
            }
            break;
        }
        f.px = x;
        f.py = y;
        f.pz = z;
        if (p.onGround() || p.isInWater()) {
            if (f.next == rings.size() && c.landing().contains(x, y, z)) finishFlight(p, f);
            else failFlight(p, f.next == rings.size() ? "§cYou have to land on the landing pad!" : "§cYou landed before the last ring.");
            return;
        }
        if (y < G - 30) {
            failFlight(p, "§cYou fell.");
            return;
        }
        if (now % 4 == 0 && f.next < rings.size()) Announce.actionBar(p, "§dRing " + f.next + "/" + rings.size() + " §f" + Parkour.time((int) (now - f.start)) + " §7rockets " + f.rockets);
    }

    private static void failFlight(ServerPlayer p, String why) {
        Flight f = ELYTRA.remove(p.getUUID());
        FLY_AGAIN.put(p.getUUID(), now + 20 * 60);
        Lobby.msg(p, why + " §7Right-click the §dFly again§7 item to try again.");
        Lobby.refreshKit(p);
        if (p.getY() < G - 20) {
            ElytraCourses.Course c = ElytraCourses.course(f == null ? 0 : f.course);
            teleport(p, c.tower() ? TOWER_BASE : c.launch());
        }
    }

    private static void finishFlight(ServerPlayer p, Flight f) {
        ELYTRA.remove(p.getUUID());
        FLY_AGAIN.put(p.getUUID(), now + 20 * 60);
        ElytraCourses.Course c = ElytraCourses.course(f.course);
        int ticks = (int) (now - f.start);
        Lobby.msg(p, c.color() + "§l" + c.name().toUpperCase() + " DONE! §fin " + Parkour.time(ticks));
        Announce.title(p, "§d§lFINISHED!", "§f" + Parkour.time(ticks), 5, 40, 10);
        Integer old = record(c.recordKey(), p, ticks, true);
        if (old != null && ticks < old) Lobby.msg(p, "§aNew personal best! §7(was " + Parkour.time(old) + ")");
        Quests.progress(p, "elytra", 1);
        // Parkour XP: the full amount for the first finish of each course today, a little for repeats (capped per day).
        int xp;
        if (firstToday("elytraxp_" + c.id(), p.getUUID())) {
            xp = ELYTRA_FIRST_SKILL_XP;
        } else {
            long day = LocalDate.now().toEpochDay();
            long[] got = ELYTRA_REPEAT_XP.computeIfAbsent(p.getUUID(), k -> new long[]{day, 0});
            if (got[0] != day) {
                got[0] = day;
                got[1] = 0;
            }
            xp = (int) Math.max(0, Math.min(ELYTRA_REPEAT_SKILL_XP, ELYTRA_REPEAT_SKILL_XP_PER_DAY - got[1]));
            got[1] += xp;
        }
        if (xp > 0) Skills.add(p, Skills.Skill.PARKOUR, xp);
        PlayerStats.add(p, "elytra", 1);
        // Money: only the first finish of ANY course each day (the same for every course).
        if (firstToday("elytra", p.getUUID())) Rewards.reward(server, p, ELYTRA_MONEY, ELYTRA_XP, "(first elytra course finish today)");
        sound(p, SoundEvents.PLAYER_LEVELUP, 1f);
        Lobby.refreshKit(p);
    }

    // =====================================================================
    // Fishing
    // =====================================================================

    private static final Set<UUID> FISHING = new HashSet<>();
    private static final Set<String> FISH = Set.of("cod", "salmon", "tropical_fish", "pufferfish");
    private static final Set<String> TREASURE = Set.of("enchanted_book", "name_tag", "saddle", "nautilus_shell", "bow", "fishing_rod");

    private static void fishingTick(ServerPlayer p) {
        UUID id = p.getUUID();
        boolean near = atPond(p.getX(), p.getZ()) && p.getY() < G + 8;
        if (!FISHING.contains(id)) {
            if (near && !ARCHERY.containsKey(id) && !ELYTRA.containsKey(id) && !ALIVE.contains(id)) {
                FISHING.add(id);
                Lobby.refreshKit(p);
                Announce.actionBar(p, "§bYou got a fishing rod! §7Cast into the lake - every catch pays.");
            }
            return;
        }
        if (!near) {
            FISHING.remove(id);
            Lobby.refreshKit(p);
            return;
        }
        // Anything new in the inventory is a catch.
        Inventory inv = p.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (s.isEmpty() || Stacks.is(s, "act") || Stacks.is(s, "menu") || Stacks.is(s, "keys") || Stacks.is(s, "guide")
                    || Stacks.is(s, "checkpoint") || Stacks.is(s, "quit") || Stacks.is(s, "flyagain")) continue;
            inv.setItem(i, ItemStack.EMPTY);
            caught(p, s);
        }
    }

    private static void caught(ServerPlayer p, ItemStack s) {
        load();
        String today = LocalDate.now().toString();
        if (!today.equals(fishDay)) {
            fishDay = today;
            FISH_TODAY.clear();
        }
        int[] day = FISH_TODAY.computeIfAbsent(p.getUUID(), k -> new int[2]);
        String path = BuiltInRegistries.ITEM.getKey(s.getItem()).getPath();
        boolean fish = FISH.contains(path), treasure = TREASURE.contains(path) && (!path.equals("bow") && !path.equals("fishing_rod") || s.isEnchanted());
        String name = s.getHoverName().getString();
        long money = Math.round((fish ? FISH_MONEY : treasure ? TREASURE_MONEY : JUNK_MONEY) * Skills.fishingPay(p.getUUID()));
        String msg;
        boolean paid = day[0] < FISH_PAID_PER_DAY;
        if (paid) {
            day[0]++;
            Economy.add(server, p.getUUID(), p.getName().getString(), money);
            msg = (treasure ? "§6§lTreasure! " : "§b") + "Caught " + name + "!" + (fish ? SkillHalls.weigh(p, path, name) : "") + " §a+" + Economy.format(money);
        } else {
            msg = "§bCaught " + name + "!" + (fish ? SkillHalls.weigh(p, path, name) : "") + " §7(daily pay used up - still counts for quests and the contest)";
        }
        Announce.actionBar(p, msg);
        if (fish) Quests.progress(p, "fish", 1);
        if (paid) {
            // Best key first: one roll per catch.
            double roll = p.getRandom().nextDouble(), acc = 0;
            for (int tier = 4; tier >= 0; tier--) {
                acc += FISH_KEY_CHANCE[tier];
                if (roll < acc) {
                    day[1]++;
                    Keys.reward(server, p.getUUID(), tier, 1, "(fished up a key!)");
                    if (tier >= 2) for (ServerPlayer o : p.level().getServer().getPlayerList().getPlayers())
                        if (o != p) Lobby.msg(o, "§b🎣 §e" + p.getName().getString() + " §7fished up a " + Keys.COLORS[tier] + Keys.NAMES[tier] + " Key§7!");
                    break;
                }
            }
        }
        load();
        BEST.computeIfAbsent("fish", k -> new HashMap<>()).merge(p.getUUID(), 1, Integer::sum);
        NAMES.put(p.getUUID(), p.getName().getString());
        save();
        Levels.showBar(p);
    }

    /** Fish flying toward a player at the pond shouldn't be cleaned up by the lobby. */
    static boolean keepItem(ItemEntity item) {
        return atPond(item.getX(), item.getZ());
    }
}
