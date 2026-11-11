package com.aidan.skyserver.lobby;

import com.aidan.skyserver.Skyserver;
import com.aidan.skyserver.island.Islands;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.*;

/**
 * 5.13: Wren's Weekly Voyage. Every Thursday 00:00 to Sunday 23:59 (Eastern time) Captain Wren puts a
 * twist on ONE of the loved modes, plus a small server-wide perk. Play the featured mode to earn a
 * Voyage Stamp (3 games / 3 finishes of the featured course / 1 hour on your island / 1 cleared
 * expedition). Stamps fill a lifetime stamp book. The top 3 of the weekly board get keys.
 *
 * The games read is(...) when a match starts (BwVoyage, SwVoyage, GwVoyage, ExpVoyage, ExpeditionLoot).
 * Progress comes from Quests.progress (onQuest), Skills XP (Harvest Moon), island time and boss kills.
 * Saved in config/skyserver/voyage.json. Ops: /ss voyage ... ; players: /voyage.
 */
public final class Voyage {
    private Voyage() {}

    public static final ZoneId ZONE = ZoneId.of("America/New_York");

    public enum Mode {
        BEDWARS("Bedwars"), SKYWARS("Skywars"), GOLDWARS("Gold Wars"), ELYTRA("Elytra"), ISLAND("Sky Island"), EXPEDITION("Nether Expedition");

        public final String display;

        Mode(String display) {
            this.display = display;
        }
    }

    public enum Twist {
        FIREBALL_FRENZY(Mode.BEDWARS, "Fireball Frenzy", "Fireballs cost half and hit 25% harder.",
                "Fireballs at half price this week, sailors! Mind your eyebrows."),
        RUSH_HOUR(Mode.BEDWARS, "Rush Hour", "Generators run double, events every 4 minutes, beds break at 20.",
                "Fierce winds this week - everything moves twice as fast!"),
        ARMISTICE(Mode.BEDWARS, "Armistice", "Beds can't be broken for the first 5 minutes.",
                "A truce for the first five minutes. Build well - then it's every crew for itself!"),
        EMERALD_TIDE(Mode.BEDWARS, "Emerald Tide", "Team forges drop emeralds from the start.",
                "Emeralds are washing up on every island this week!"),
        LEGENDARY_SKIES(Mode.SKYWARS, "Legendary Skies", "Every chest holds the best loot.",
                "I've stocked every chest with treasure. Don't make me regret it!"),
        SHRINKING_SKY(Mode.SKYWARS, "Shrinking Sky", "A storm wall closes in after 3 minutes.",
                "A storm wall closes in after three minutes. Stay near the middle!"),
        GOLD_RUSH(Mode.GOLDWARS, "Gold Rush", "Gold blocks pay and give XP twice.",
                "Every gold block is worth double this week. Dig in!"),
        CLASS_ROULETTE(Mode.GOLDWARS, "Class Roulette", "A random class every life.",
                "No choosing this week - the wind picks your class every time you respawn!"),
        TIME_TRIAL(Mode.ELYTRA, "Time Trial", "One featured elytra course, weekly best times.",
                "Fastest flyer on the featured course gets a prize from my own chest!"),
        HARVEST_MOON(Mode.ISLAND, "Harvest Moon", "+50% Farming and Shepherd skill XP.",
                "The harvest moon is up - crops and flocks grow fast on every island."),
        WRENS_BOUNTY(Mode.EXPEDITION, "Wren's Bounty", "Expedition tickets 20% off, an extra roll in every room chest. Board points per boss: Easy 1, Normal 2, Hard 3, Nightmare 4.",
                "Twenty percent off every trip, and extra loot in the rooms. My treat!");

        public final Mode mode;
        public final String display, about, wren;

        Twist(Mode mode, String display, String about, String wren) {
            this.mode = mode;
            this.display = display;
            this.about = about;
            this.wren = wren;
        }

        public String id() {
            return name().toLowerCase(Locale.ROOT);
        }

        static Twist byId(String id) {
            for (Twist t : values()) if (t.id().equals(id)) return t;
            return null;
        }
    }

    public enum Perk { PASS_XP, MONEY, KEY }

    /** Stamp-book milestones (lifetime stamps). */
    public static final int[] BOOK = {1, 3, 6, 10, 15, 20};
    public static final int STAMP_PASS_XP = 1_000;
    /** Weekly board prizes: Rare, Uncommon, Common key. */
    public static final int[] PRIZE_TIER = {2, 1, 0};

    // =====================================================================
    // Data
    // =====================================================================

    static final class Prog {
        String name = "?";
        int count;          // games / finishes / island minutes / runs
        double score;       // wins / best time (ticks) / skill XP / boss kills
        long tie, tieBase = -1, firstAt;
        boolean stamp, keyPerk, wrenTold;
    }

    static final class Week {
        final String key; // the Thursday, yyyy-mm-dd
        Twist twist;
        Perk perk = Perk.MONEY;
        int course;
        boolean paid, off, announced;
        final Map<UUID, Prog> prog = new HashMap<>();

        Week(String key) {
            this.key = key;
        }
    }

    private static final Map<String, Week> WEEKS = new TreeMap<>();
    private static final List<String> BAG = new ArrayList<>();
    private static final Map<UUID, Integer> STAMPS = new HashMap<>();
    private static final Map<UUID, Set<Integer>> CLAIMED = new HashMap<>();
    private static final Map<UUID, String> CHOICE = new HashMap<>();
    private static final Map<UUID, String> NAMES = new HashMap<>();
    private static final Random RNG = new Random();
    private static boolean loaded = false, dirty = false;
    private static MinecraftServer server;
    private static Holograms.Holo holo;

    // Live tracking (not saved).
    private static final Map<UUID, Long> LAUNCH = new HashMap<>();
    private static final Map<UUID, long[]> SKILL_SEEN = new HashMap<>();
    private static final Map<UUID, Long> PASS_SEEN = new HashMap<>();
    private static final Map<UUID, Double> PASS_FRAC = new HashMap<>();

    // =====================================================================
    // Time
    // =====================================================================

    static LocalDate today() {
        return ZonedDateTime.now(ZONE).toLocalDate();
    }

    /** Thursday to Sunday. */
    public static boolean windowOpen() {
        DayOfWeek d = today().getDayOfWeek();
        return d == DayOfWeek.THURSDAY || d == DayOfWeek.FRIDAY || d == DayOfWeek.SATURDAY || d == DayOfWeek.SUNDAY;
    }

    /** The Thursday of the Voyage running now, or null on Monday-Wednesday. */
    static String currentKey() {
        return windowOpen() ? today().with(TemporalAdjusters.previousOrSame(DayOfWeek.THURSDAY)).toString() : null;
    }

    /** The Thursday of the next Voyage that hasn't started. */
    static String upcomingKey() {
        return today().with(TemporalAdjusters.next(DayOfWeek.THURSDAY)).toString();
    }

    /** Seconds until the window opens (when closed) or closes (when open). */
    static long secondsToChange() {
        ZonedDateTime now = ZonedDateTime.now(ZONE);
        LocalDate target = windowOpen() ? now.toLocalDate().with(TemporalAdjusters.next(DayOfWeek.MONDAY))
                : now.toLocalDate().with(TemporalAdjusters.next(DayOfWeek.THURSDAY));
        return Math.max(0, java.time.Duration.between(now, target.atStartOfDay(ZONE)).getSeconds());
    }

    static String duration(long s) {
        long d = s / 86400, h = (s % 86400) / 3600, m = (s % 3600) / 60;
        return d > 0 ? d + "d " + h + "h" : h > 0 ? h + "h " + m + "m" : m + "m";
    }

    // =====================================================================
    // Asking about the Voyage (used by the games and menus)
    // =====================================================================

    static Week current() {
        load();
        String k = currentKey();
        if (k == null) return null;
        Week w = week(k);
        return w.off || w.twist == null ? null : w;
    }

    static Week upcoming() {
        load();
        return week(upcomingKey());
    }

    /** Is this twist running right now? */
    public static boolean is(Twist t) {
        Week w = current();
        return w != null && w.twist == t;
    }

    public static Twist twist() {
        Week w = current();
        return w == null ? null : w.twist;
    }

    /** The featured elytra course index (Time Trial). */
    public static int featuredCourse() {
        Week w = current();
        int n = ElytraCourses.ALL.size();
        return w == null || n == 0 ? 0 : Math.floorMod(w.course, n);
    }

    private static Week week(String key) {
        Week w = WEEKS.get(key);
        if (w == null) {
            w = new Week(key);
            w.twist = pick();
            w.perk = Perk.values()[RNG.nextInt(Perk.values().length)];
            w.course = RNG.nextInt(Math.max(1, ElytraCourses.ALL.size()));
            WEEKS.put(key, w);
            trim();
            dirty = true;
        }
        return w;
    }

    /** Next twist from the shuffled bag (every twist runs once before any repeats). */
    private static Twist pick() {
        if (BAG.isEmpty()) {
            for (Twist t : Twist.values()) BAG.add(t.id());
            Collections.shuffle(BAG, RNG);
            String last = null;
            for (Week w : WEEKS.values()) if (w.twist != null) last = w.twist.id();
            if (last != null && BAG.size() > 1 && BAG.get(0).equals(last)) Collections.swap(BAG, 0, 1);
        }
        Twist t = Twist.byId(BAG.remove(0));
        return t == null ? Twist.RUSH_HOUR : t;
    }

    private static void trim() {
        while (WEEKS.size() > 10) {
            String first = WEEKS.keySet().iterator().next();
            WEEKS.remove(first);
        }
    }

    static String perkText(Week w) {
        if (w == null || w.twist == null) return "";
        return switch (w.perk) {
            case PASS_XP -> "+10% battle pass XP for everyone";
            case MONEY -> "+10% money in " + w.twist.mode.display;
            case KEY -> "a Common Key for your first " + w.twist.mode.display + (w.twist.mode == Mode.ISLAND ? " hour" : w.twist.mode == Mode.ELYTRA ? " finish" : " game");
        };
    }

    static String stampGoal(Twist t) {
        return switch (t.mode) {
            case BEDWARS, SKYWARS, GOLDWARS -> "Play 3 " + t.mode.display + " games";
            case ELYTRA -> "Finish the featured course 3 times";
            case ISLAND -> "Spend 1 hour on your Sky Island";
            case EXPEDITION -> "Clear 1 Nether Expedition";
        };
    }

    static int stampTarget(Twist t) {
        return switch (t.mode) {
            case ISLAND -> 60;
            case EXPEDITION -> 1;
            default -> 3;
        };
    }

    public static int stamps(UUID id) {
        load();
        return STAMPS.getOrDefault(id, 0);
    }

    // =====================================================================
    // Setup
    // =====================================================================

    public static void init() {
        ServerLifecycleEvents.SERVER_STARTED.register(s -> server = s);
        ServerLifecycleEvents.SERVER_STOPPING.register(s -> { if (dirty) saveNow(); });
        ServerTickEvents.END_SERVER_TICK.register(s -> com.aidan.skyserver.Safe.run("voyage", () -> tick(s)));
        ServerPlayConnectionEvents.JOIN.register((h, sender, s) -> {
            ServerPlayer p = h.getPlayer();
            if (p instanceof com.aidan.skyserver.bots.BotPlayer) return;
            Lobby.runLaterTicks(80, () -> com.aidan.skyserver.Safe.run("voyage join", () -> joinMessage(p)));
        });
        CommandRegistrationCallback.EVENT.register((d, r, e) -> {
            d.register(Commands.literal("voyage")
                    .executes(c -> { VoyageMenu.open(c.getSource().getPlayerOrException()); return 1; })
                    .then(Commands.literal("choose").then(Commands.argument("which", StringArgumentType.word())
                            .suggests((c, b) -> SharedSuggestionProvider.suggest(List.of("cage", "bed"), b))
                            .executes(c -> { choose(c.getSource().getPlayerOrException(), StringArgumentType.getString(c, "which")); return 1; }))));
            List<String> ids = new ArrayList<>();
            for (Twist t : Twist.values()) ids.add(t.id());
            d.register(Commands.literal("ss").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                    .then(Commands.literal("voyage")
                            .executes(c -> { for (String l : status()) c.getSource().sendSuccess(() -> Component.literal(l), false); return 1; })
                            .then(Commands.literal("list").executes(c -> {
                                for (Twist t : Twist.values()) c.getSource().sendSuccess(() -> Component.literal("§e" + t.id() + " §7- " + t.mode.display + ": " + t.about), false);
                                return 1;
                            }))
                            .then(Commands.literal("set").then(Commands.argument("twist", StringArgumentType.word())
                                    .suggests((c, b) -> SharedSuggestionProvider.suggest(ids, b)).executes(c -> {
                                        Twist t = Twist.byId(StringArgumentType.getString(c, "twist"));
                                        if (t == null) {
                                            c.getSource().sendFailure(Component.literal("Unknown twist. /ss voyage list"));
                                            return 0;
                                        }
                                        Week w = adminWeek();
                                        w.twist = t;
                                        w.off = false;
                                        dirty = true;
                                        c.getSource().sendSuccess(() -> Component.literal("Voyage of " + w.key + " is now " + t.display + "."), true);
                                        return 1;
                                    })))
                            .then(Commands.literal("next").executes(c -> {
                                Week w = adminWeek();
                                w.twist = pick();
                                w.off = false;
                                dirty = true;
                                c.getSource().sendSuccess(() -> Component.literal("Voyage of " + w.key + " re-rolled: " + w.twist.display + "."), true);
                                return 1;
                            }))
                            .then(Commands.literal("off").executes(c -> {
                                Week w = adminWeek();
                                w.off = true;
                                dirty = true;
                                c.getSource().sendSuccess(() -> Component.literal("Voyage of " + w.key + " is OFF."), true);
                                return 1;
                            }))
                            .then(Commands.literal("on").executes(c -> {
                                Week w = adminWeek();
                                w.off = false;
                                dirty = true;
                                c.getSource().sendSuccess(() -> Component.literal("Voyage of " + w.key + " is ON (" + w.twist.display + ")."), true);
                                return 1;
                            }))
                            .then(Commands.literal("give").then(Commands.argument("player", EntityArgument.player()).executes(c -> {
                                ServerPlayer t = EntityArgument.getPlayer(c, "player");
                                Week w = current();
                                if (w == null) {
                                    c.getSource().sendFailure(Component.literal("No Voyage is running right now."));
                                    return 0;
                                }
                                Prog g = prog(w, t);
                                if (g.stamp) {
                                    c.getSource().sendFailure(Component.literal(t.getName().getString() + " already has this week's stamp."));
                                    return 0;
                                }
                                g.count = Math.max(g.count, stampTarget(w.twist));
                                award(t, w, g);
                                c.getSource().sendSuccess(() -> Component.literal("Gave " + t.getName().getString() + " this week's stamp."), true);
                                return 1;
                            })))));
        });
    }

    /** The week an admin command changes: the one running, or else the next one. */
    private static Week adminWeek() {
        load();
        String k = currentKey();
        return week(k != null ? k : upcomingKey());
    }

    private static List<String> status() {
        List<String> out = new ArrayList<>();
        Week cur = current(), up = upcoming();
        out.add("§6§lWREN'S WEEKLY VOYAGE");
        if (cur != null) out.add("§aRunning: §f" + cur.twist.display + " §7(" + cur.twist.mode.display + ", ends in " + duration(secondsToChange()) + "), perk: " + perkText(cur)
                + ", " + cur.prog.values().stream().filter(g -> g.stamp).count() + " stamps so far");
        else out.add("§7Nothing running. " + (currentKey() != null ? "§c(this week is OFF)" : "Starts in " + duration(secondsToChange())));
        if (up != null && up.twist != null) out.add("§eNext: §f" + up.twist.display + " §7(" + up.key + ")" + (up.off ? " §cOFF" : ""));
        out.add("§7/ss voyage set <twist> | next | off | on | list | give <player>");
        return out;
    }

    // =====================================================================
    // Progress (hooks)
    // =====================================================================

    static Prog prog(Week w, ServerPlayer p) {
        Prog g = w.prog.computeIfAbsent(p.getUUID(), k -> new Prog());
        g.name = p.getName().getString();
        NAMES.put(p.getUUID(), g.name);
        if (g.tieBase < 0) g.tieBase = PlayerStats.get(p.getUUID(), "game_kill");
        return g;
    }

    private static Mode modeOfKey(String key) {
        if (key.startsWith("bw_")) return Mode.BEDWARS;
        if (key.startsWith("sw_")) return Mode.SKYWARS;
        if (key.startsWith("gw_")) return Mode.GOLDWARS;
        return null;
    }

    /** Called by Quests.progress for every event (real players only). */
    public static void onQuest(ServerPlayer p, String key, int amount) {
        Week w = current();
        if (w == null || p == null) return;
        Mode m = modeOfKey(key);
        if (m != null && m == w.twist.mode) {
            Prog g = prog(w, p);
            if (key.endsWith("_play")) {
                g.count += amount;
                perkKey(p, w, g);
                checkStamp(p, w, g);
                dirty = true;
            } else if (key.endsWith("_win")) {
                g.score += amount;
                if (g.firstAt == 0) g.firstAt = System.currentTimeMillis();
                dirty = true;
            }
        }
        // Gold Rush: every gold block pays and gives XP a second time.
        if (key.equals("gw_gold") && w.twist == Twist.GOLD_RUSH && server != null) {
            Rewards.pay(server, p, Rewards.GW_GOLD, "(Gold Rush: double gold)");
            Levels.addXp(server, p, Rewards.GW_GOLD_XP);
        }
        if (key.equals("elytra") && w.twist == Twist.TIME_TRIAL) elytraFinish(p, w);
    }

    /** A Nether Expedition boss died: everyone given counts a cleared run (ExpVoyage). */
    /** 5.13: board points by difficulty: Easy 1, Normal 2, Hard 3, Nightmare 4. A stamp still needs 1 clear. */
    public static void onBossKilled(List<ServerPlayer> crew, int difficulty) {
        Week w = current();
        if (w == null || w.twist.mode != Mode.EXPEDITION) return;
        for (ServerPlayer p : crew) {
            if (p instanceof com.aidan.skyserver.bots.BotPlayer) continue;
            Prog g = prog(w, p);
            g.count++;
            g.score += Math.max(0, Math.min(3, difficulty)) + 1;
            if (g.firstAt == 0) g.firstAt = System.currentTimeMillis();
            perkKey(p, w, g);
            checkStamp(p, w, g);
        }
        dirty = true;
    }

    private static void elytraFinish(ServerPlayer p, Week w) {
        if (ElytraCourses.ALL.isEmpty()) return;
        ElytraCourses.Course c = ElytraCourses.ALL.get(featuredCourse());
        double x = p.getX(), y = p.getY(), z = p.getZ();
        boolean onPad = c.landing().contains(x, y, z) || c.landing().contains(x, y - 0.5, z) || c.landing().contains(x, y + 0.5, z);
        if (!onPad) return;
        Long start = LAUNCH.remove(p.getUUID());
        Prog g = prog(w, p);
        g.count++;
        if (start != null && server != null) {
            long ticks = server.getTickCount() - start;
            if (ticks > 0 && ticks < 20 * 60 * 10 && (g.score <= 0 || ticks < g.score)) {
                g.score = ticks;
                g.firstAt = System.currentTimeMillis();
                Lobby.msg(p, "§6[Voyage] §eNew best on " + c.name() + ": §f" + Parkour.time((int) ticks));
            }
        }
        perkKey(p, w, g);
        checkStamp(p, w, g);
        dirty = true;
    }

    /** Key perk: a Common Key for the first counted game of the week. */
    private static void perkKey(ServerPlayer p, Week w, Prog g) {
        if (w.perk != Perk.KEY || g.keyPerk || server == null) return;
        g.keyPerk = true;
        Keys.reward(server, p.getUUID(), 0, 1, "(Voyage perk)");
    }

    /** Money perk: +10% money paid in the featured mode. Called by Rewards.pay. */
    public static long moneyPerk(ServerPlayer p, long amount) {
        Week w = current();
        if (w == null || w.perk != Perk.MONEY || amount <= 0) return amount;
        if (!inMode(p, w.twist.mode)) return amount;
        return Math.round(amount * 1.10);
    }

    static boolean inMode(ServerPlayer p, Mode m) {
        UUID id = p.getUUID();
        return switch (m) {
            case BEDWARS -> com.aidan.skyserver.bedwars.BedwarsGame.isInGame(id);
            case SKYWARS -> com.aidan.skyserver.skywars.SkywarsGame.isInGame(id);
            case GOLDWARS -> com.aidan.skyserver.goldwars.GoldWarsGame.isInGame(id);
            case ELYTRA -> Lobby.isLobby(p.level());
            case ISLAND -> Islands.isIsland(p.level()) && !isExpedition(p);
            case EXPEDITION -> isExpedition(p);
        };
    }

    static boolean isExpedition(ServerPlayer p) {
        return p.level().dimension().identifier().getPath().equals("expedition");
    }

    private static void checkStamp(ServerPlayer p, Week w, Prog g) {
        if (g.stamp) return;
        int target = stampTarget(w.twist);
        if (g.count >= target) award(p, w, g);
        else if (w.twist.mode != Mode.ISLAND) Announce.actionBar(p, "§6Voyage stamp: §f" + g.count + "/" + target + " §7(" + stampGoal(w.twist) + ")");
    }

    private static void award(ServerPlayer p, Week w, Prog g) {
        g.stamp = true;
        int n = STAMPS.merge(p.getUUID(), 1, Integer::sum);
        dirty = true;
        BattlePass.addPassXp(p, STAMP_PASS_XP);
        PlayerStats.add(p, "voyage_stamp", 1);
        Announce.title(p, "§6§lVOYAGE STAMP!", "§7" + w.twist.display + " §8- stamp #" + n, 5, 50, 10);
        Lobby.msg(p, "§6[Captain Wren] §fWell flown! That's a stamp for your book - number §e" + n + "§f. §b+" + STAMP_PASS_XP + " pass XP");
        p.level().playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 1f, 1.3f);
        book(p, n);
    }

    // =====================================================================
    // The stamp book
    // =====================================================================

    private static void book(ServerPlayer p, int n) {
        Set<Integer> got = CLAIMED.computeIfAbsent(p.getUUID(), k -> new HashSet<>());
        for (int m : BOOK) {
            if (n < m || got.contains(m)) continue;
            got.add(m);
            dirty = true;
            switch (m) {
                case 1 -> grant(p, "title_v_deckhand");
                case 3 -> grant(p, "trail_voyage");
                case 6 -> {
                    grant(p, "title_v_navigator");
                    if (server != null) Keys.reward(server, p.getUUID(), 2, 1, "(stamp book: 6 stamps)");
                }
                case 10 -> {
                    String ch = CHOICE.get(p.getUUID());
                    if (ch == null) offerChoice(p);
                    else grant(p, ch.equals("cage") ? "cage_crowsnest" : "bedfx_cannon");
                }
                case 15 -> {
                    String ch = CHOICE.get(p.getUUID());
                    if (ch == null || !ch.equals("cage")) grant(p, "cage_crowsnest");
                    if (ch == null || !ch.equals("bed")) grant(p, "bedfx_cannon");
                    if (ch == null) CHOICE.put(p.getUUID(), "both");
                }
                default -> {}
            }
        }
        if (n >= 20 && n % 5 == 0 && got.add(n)) {
            dirty = true;
            if (server != null) Keys.reward(server, p.getUUID(), 3, 1, "(stamp book: " + n + " stamps)");
        }
    }

    private static void offerChoice(ServerPlayer p) {
        Lobby.msg(p, "§6[Captain Wren] §fTen stamps! Pick a treasure from my own hold (you get the other at 15):");
        p.sendSystemMessage(Component.literal(" ").append(BattlePass.button("§b§l[Voyager's Crow's Nest cage]", "/voyage choose cage", "A Skywars cage"))
                .append(Component.literal("  ")).append(BattlePass.button("§c§l[Wren's Cannon Volley]", "/voyage choose bed", "A bed-break effect")));
    }

    static void choose(ServerPlayer p, String which) {
        if (!which.equals("cage") && !which.equals("bed")) {
            Lobby.msg(p, "§c/voyage choose cage §7or §c/voyage choose bed");
            return;
        }
        if (stamps(p.getUUID()) < 10) {
            Lobby.msg(p, "§7You choose at §e10 stamps§7. You have §e" + stamps(p.getUUID()) + "§7.");
            return;
        }
        if (CHOICE.containsKey(p.getUUID())) {
            Lobby.msg(p, "§7You already chose. The other one comes at 15 stamps.");
            return;
        }
        CHOICE.put(p.getUUID(), which);
        dirty = true;
        grant(p, which.equals("cage") ? "cage_crowsnest" : "bedfx_cannon");
    }

    static String choice(UUID id) {
        return CHOICE.get(id);
    }

    static Set<Integer> claimed(UUID id) {
        return CLAIMED.getOrDefault(id, Set.of());
    }

    private static void grant(ServerPlayer p, String id) {
        PlayerProfile prof = PlayerCache.get(p);
        if (prof.owned.add(id)) {
            prof.touch();
            CosmeticsPlus.announce(p, id);
        }
    }

    // =====================================================================
    // The weekly board
    // =====================================================================

    record Entry(UUID id, String name, double score, long tie, long firstAt) {}

    static List<Entry> board(Week w) {
        List<Entry> list = new ArrayList<>();
        if (w == null || w.twist == null) return list;
        for (Map.Entry<UUID, Prog> e : w.prog.entrySet()) {
            Prog g = e.getValue();
            if (g.score <= 0) continue;
            long kills = w.twist.mode == Mode.BEDWARS || w.twist.mode == Mode.SKYWARS || w.twist.mode == Mode.GOLDWARS ? g.tie : 0;
            list.add(new Entry(e.getKey(), g.name, g.score, kills, g.firstAt == 0 ? Long.MAX_VALUE : g.firstAt));
        }
        boolean lowWins = w.twist.mode == Mode.ELYTRA;
        list.sort((a, b) -> {
            if (a.score() != b.score()) return lowWins ? Double.compare(a.score(), b.score()) : Double.compare(b.score(), a.score());
            if (a.tie() != b.tie()) return Long.compare(b.tie(), a.tie());
            return Long.compare(a.firstAt(), b.firstAt());
        });
        return list;
    }

    static String scoreText(Week w, Entry e) {
        return switch (w.twist.mode) {
            case ELYTRA -> Parkour.time((int) e.score());
            case ISLAND -> String.format("%,d", (long) e.score()) + " skill XP";
            case EXPEDITION -> (long) e.score() + " pt" + (e.score() == 1 ? "" : "s");
            default -> (long) e.score() + " win" + (e.score() == 1 ? "" : "s") + (e.tie() > 0 ? " §8(" + e.tie() + " kills)" : "");
        };
    }

    private static void payout(Week w) {
        w.paid = true;
        dirty = true;
        if (w.twist == null || w.off) return;
        List<Entry> top = board(w);
        StringBuilder sb = new StringBuilder("§6[Voyage] §eThe " + w.twist.display + " Voyage is over! ");
        if (top.isEmpty()) sb.append("§7Nobody made the board this time.");
        for (int i = 0; i < Math.min(3, top.size()); i++) {
            Entry e = top.get(i);
            Keys.give(e.id(), PRIZE_TIER[i], 1);
            sb.append("§6").append(i + 1).append(". §f").append(e.name()).append(" §7(").append(scoreText(w, e)).append("§7) ");
            ServerPlayer p = server == null ? null : server.getPlayerList().getPlayer(e.id());
            if (p != null) Lobby.msg(p, "§6[Captain Wren] §fYou placed §e#" + (i + 1) + "§f on this week's Voyage board! A "
                    + Keys.COLORS[PRIZE_TIER[i]] + Keys.NAMES[PRIZE_TIER[i]] + " Key §fis yours (§e/keys§f).");
        }
        if (server != null) for (ServerPlayer p : server.getPlayerList().getPlayers()) if (!(p instanceof com.aidan.skyserver.bots.BotPlayer)) Lobby.msg(p, sb.toString());
        Skyserver.LOGGER.info("Voyage {} ({}) paid: {}", w.key, w.twist.display, top.size());
    }

    // =====================================================================
    // Every tick
    // =====================================================================

    private static long ticks = 0;

    private static void tick(MinecraftServer s) {
        server = s;
        ticks++;
        load();
        Week w = current();
        if (w != null && w.twist == Twist.TIME_TRIAL && !ElytraCourses.ALL.isEmpty()) trackLaunches(s, w);
        if (ticks % 20 == 0) {
            // Pay any week whose window has closed.
            LocalDate today = today();
            String cur = currentKey();
            for (Week old : new ArrayList<>(WEEKS.values())) {
                if (old.paid || old.key.equals(cur)) continue;
                if (LocalDate.parse(old.key).plusDays(4).isAfter(today)) continue;
                payout(old);
            }
            if (w != null && !w.announced) {
                w.announced = true;
                dirty = true;
                for (ServerPlayer p : s.getPlayerList().getPlayers()) {
                    if (p instanceof com.aidan.skyserver.bots.BotPlayer) continue;
                    Announce.title(p, "§6§lWREN'S VOYAGE", "§f" + w.twist.display + " §7- " + w.twist.mode.display, 10, 70, 20);
                    voyageLines(p, w);
                }
            }
            if (upcomingKey() != null) upcoming(); // picks the next twist early so it can be shown
        }
        if (w != null && ticks % 40 == 7) {
            if (w.twist == Twist.HARVEST_MOON) harvest(s, w);
            wrenOnIslands(s, w);
        }
        if (w != null && w.twist == Twist.HARVEST_MOON && ticks % 1200 == 300) islandMinute(s, w);
        if (w != null && w.perk == Perk.PASS_XP && ticks % 100 == 51) passPerk(s);
        if (ticks % 100 == 3) hologram();
        if (dirty && ticks % 600 == 0) save();
    }

    private static void trackLaunches(MinecraftServer s, Week w) {
        ElytraCourses.Course c = ElytraCourses.ALL.get(featuredCourse());
        for (ServerPlayer p : s.getPlayerList().getPlayers()) {
            if (p instanceof com.aidan.skyserver.bots.BotPlayer || !Lobby.isLobby(p.level())) continue;
            if (c.launchPad().contains(p.getX(), p.getY(), p.getZ()) || c.launchPad().contains(p.getX(), p.getY() - 0.5, p.getZ()))
                LAUNCH.put(p.getUUID(), (long) s.getTickCount());
        }
    }

    /** Harvest Moon: +50% Farming and Shepherd XP, and that XP is the board score. */
    private static void harvest(MinecraftServer s, Week w) {
        Skills.Skill[] skills = {Skills.Skill.FARMING, Skills.Skill.SHEPHERD};
        for (ServerPlayer p : s.getPlayerList().getPlayers()) {
            if (p instanceof com.aidan.skyserver.bots.BotPlayer) continue;
            long[] seen = SKILL_SEEN.computeIfAbsent(p.getUUID(), k -> new long[]{-1, -1});
            for (int i = 0; i < 2; i++) {
                long now = Skills.xpToday(p.getUUID(), skills[i]);
                if (seen[i] < 0 || now < seen[i]) {
                    seen[i] = now; // first look, or a new day
                    continue;
                }
                long delta = now - seen[i];
                if (delta > 0) {
                    Prog g = prog(w, p);
                    g.score += delta;
                    if (g.firstAt == 0) g.firstAt = System.currentTimeMillis();
                    int raw = (int) Math.round(delta * 0.5 / Math.max(0.01, skills[i].rate));
                    if (raw > 0) Skills.add(p, skills[i], raw);
                    dirty = true;
                }
                seen[i] = Skills.xpToday(p.getUUID(), skills[i]);
            }
        }
    }

    /** Harvest Moon: island time counts toward the stamp (one minute at a time). */
    private static void islandMinute(MinecraftServer s, Week w) {
        for (ServerPlayer p : s.getPlayerList().getPlayers()) {
            if (p instanceof com.aidan.skyserver.bots.BotPlayer || !Islands.isIsland(p.level()) || isExpedition(p)) continue;
            Prog g = prog(w, p);
            g.count++;
            if (g.count == 1) perkKey(p, w, g);
            if (!g.stamp && g.count % 15 == 0 && g.count < 60) Announce.actionBar(p, "§6Voyage stamp: §f" + g.count + "/60 island minutes");
            checkStamp(p, w, g);
            dirty = true;
        }
    }

    /** Pass XP perk: +10% of all pass XP earned (watched every 5 seconds). */
    private static void passPerk(MinecraftServer s) {
        for (ServerPlayer p : s.getPlayerList().getPlayers()) {
            if (p instanceof com.aidan.skyserver.bots.BotPlayer) continue;
            PlayerProfile prof = PlayerCache.ifOnline(p.getUUID());
            if (prof == null) continue;
            Long seen = PASS_SEEN.get(p.getUUID());
            if (seen != null && prof.passXp > seen) {
                double f = PASS_FRAC.getOrDefault(p.getUUID(), 0.0) + (prof.passXp - seen) * 0.10;
                int whole = (int) Math.floor(f);
                PASS_FRAC.put(p.getUUID(), f - whole);
                if (whole > 0) BattlePass.addPassXp(p, whole);
            }
            PASS_SEEN.put(p.getUUID(), prof.passXp);
        }
    }

    /** Captain Wren greets each player the first time they're on an island during a Voyage. */
    private static void wrenOnIslands(MinecraftServer s, Week w) {
        for (ServerPlayer p : s.getPlayerList().getPlayers()) {
            if (p instanceof com.aidan.skyserver.bots.BotPlayer || !Islands.isIsland(p.level()) || isExpedition(p)) continue;
            Prog g = prog(w, p);
            if (g.wrenTold) continue;
            g.wrenTold = true;
            dirty = true;
            Lobby.msg(p, "§6[Captain Wren] §fAhoy! This week's voyage: §e" + w.twist.display + "§f. " + w.twist.wren);
            Lobby.msg(p, "§7" + stampGoal(w.twist) + " for a stamp. §e/voyage §7for the board.");
        }
    }

    // =====================================================================
    // Messages, the hologram and the compass button
    // =====================================================================

    private static void voyageLines(ServerPlayer p, Week w) {
        Lobby.msg(p, "§6§l⚓ WREN'S WEEKLY VOYAGE: §e§l" + w.twist.display + " §7(" + w.twist.mode.display + ")");
        Lobby.msg(p, "§6[Captain Wren] §f" + w.twist.wren);
        Lobby.msg(p, "§7" + w.twist.about + " §8| §7Perk: §f" + perkText(w));
        if (w.twist == Twist.TIME_TRIAL && !ElytraCourses.ALL.isEmpty())
            Lobby.msg(p, "§7Featured course: " + ElytraCourses.ALL.get(featuredCourse()).color() + ElytraCourses.ALL.get(featuredCourse()).name());
        Prog g = w.prog.get(p.getUUID());
        int have = g == null ? 0 : g.count;
        Lobby.msg(p, "§7" + stampGoal(w.twist) + " for a stamp " + (g != null && g.stamp ? "§a✔ done!" : "§7(" + have + "/" + stampTarget(w.twist) + ")")
                + " §8| §e/voyage");
    }

    private static void joinMessage(ServerPlayer p) {
        if (p.hasDisconnected()) return;
        Week w = current();
        if (w != null) {
            voyageLines(p, w);
            return;
        }
        Week up = upcoming();
        if (up != null && up.twist != null && !up.off)
            Lobby.msg(p, "§6⚓ §7Next Voyage §fThursday§7: §e" + up.twist.display + " §7(" + up.twist.mode.display + ") §8- in " + duration(secondsToChange()));
    }

    private static void hologram() {
        if (Lobby.level() == null) return;
        if (holo == null) holo = Holograms.add(LobbyDesign.SPAWN[0] - 9, LobbyDesign.G + 4.5, LobbyDesign.SPAWN[2] + 4, List.of("§6§lWREN'S WEEKLY VOYAGE"));
        holo.setLines(holoLines());
    }

    private static List<String> holoLines() {
        List<String> l = new ArrayList<>();
        l.add("§6§l⚓ WREN'S WEEKLY VOYAGE");
        Week w = current();
        if (w != null) {
            l.add("§e" + w.twist.display + " §7- " + w.twist.mode.display);
            l.add("§7" + w.twist.about);
            l.add("§7Perk: §f" + perkText(w));
            l.add("§7Ends in §f" + duration(secondsToChange()) + " §8| §e/voyage");
        } else {
            Week up = upcoming();
            l.add("§7Next Voyage: §fThursday");
            if (up != null && up.twist != null && !up.off) l.add("§e" + up.twist.display + " §7- " + up.twist.mode.display);
            l.add("§7Starts in §f" + duration(secondsToChange()) + " §8| §e/voyage");
        }
        return l;
    }

    /** The compass button. */
    public static ItemStack compassIcon(ServerPlayer p) {
        Week w = current();
        List<String> lore = new ArrayList<>();
        if (w != null) {
            Prog g = w.prog.get(p.getUUID());
            lore.add("§e" + w.twist.display + " §7- " + w.twist.mode.display);
            lore.add("§7" + w.twist.about);
            lore.add("§7Perk: §f" + perkText(w));
            lore.add("§7Stamp: " + (g != null && g.stamp ? "§a✔ earned" : "§f" + (g == null ? 0 : g.count) + "/" + stampTarget(w.twist) + " §7(" + stampGoal(w.twist) + ")"));
            lore.add("§7Ends in §f" + duration(secondsToChange()));
        } else {
            Week up = upcoming();
            lore.add("§7Thursday to Sunday, every week.");
            if (up != null && up.twist != null && !up.off) lore.add("§7Next: §e" + up.twist.display + " §7(" + up.twist.mode.display + ")");
            lore.add("§7Starts in §f" + duration(secondsToChange()));
        }
        lore.add("§7Your stamps: §e" + stamps(p.getUUID()));
        lore.add("§eClick to open");
        return Stacks.named(new ItemStack(Items.FILLED_MAP), "§6§l⚓ Wren's Weekly Voyage", lore.toArray(new String[0]));
    }

    /** One line for /ss usage. */
    public static String usageLine() {
        Week w = current();
        if (w == null) return "§7Voyage: not running";
        long stamped = w.prog.values().stream().filter(g -> g.stamp).count();
        return "§7Voyage: §f" + w.twist.display + " §7- " + w.prog.size() + " players took part, " + stamped + " stamps";
    }

    // =====================================================================
    // Saving
    // =====================================================================

    private static void load() {
        if (loaded) return;
        loaded = true;
        JsonObject root = Stacks.readFile("voyage.json");
        try {
            if (root.has("bag")) for (JsonElement e : root.getAsJsonArray("bag")) BAG.add(e.getAsString());
            if (root.has("stamps")) for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("stamps").entrySet())
                STAMPS.put(UUID.fromString(e.getKey()), e.getValue().getAsInt());
            if (root.has("book")) for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("book").entrySet()) {
                Set<Integer> set = new HashSet<>();
                for (JsonElement x : e.getValue().getAsJsonArray()) set.add(x.getAsInt());
                CLAIMED.put(UUID.fromString(e.getKey()), set);
            }
            if (root.has("choice")) for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("choice").entrySet())
                CHOICE.put(UUID.fromString(e.getKey()), e.getValue().getAsString());
            if (root.has("names")) for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("names").entrySet())
                NAMES.put(UUID.fromString(e.getKey()), e.getValue().getAsString());
            if (root.has("weeks")) for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("weeks").entrySet()) {
                JsonObject o = e.getValue().getAsJsonObject();
                Week w = new Week(e.getKey());
                w.twist = o.has("twist") ? Twist.byId(o.get("twist").getAsString()) : null;
                try {
                    w.perk = Perk.valueOf(o.get("perk").getAsString());
                } catch (RuntimeException ex) {
                    w.perk = Perk.MONEY;
                }
                w.course = o.has("course") ? o.get("course").getAsInt() : 0;
                w.paid = o.has("paid") && o.get("paid").getAsBoolean();
                w.off = o.has("off") && o.get("off").getAsBoolean();
                w.announced = o.has("announced") && o.get("announced").getAsBoolean();
                if (o.has("prog")) for (Map.Entry<String, JsonElement> pe : o.getAsJsonObject("prog").entrySet()) {
                    JsonObject po = pe.getValue().getAsJsonObject();
                    Prog g = new Prog();
                    g.name = po.has("n") ? po.get("n").getAsString() : "?";
                    g.count = po.has("c") ? po.get("c").getAsInt() : 0;
                    g.score = po.has("s") ? po.get("s").getAsDouble() : 0;
                    g.tie = po.has("t") ? po.get("t").getAsLong() : 0;
                    g.tieBase = po.has("tb") ? po.get("tb").getAsLong() : -1;
                    g.firstAt = po.has("f") ? po.get("f").getAsLong() : 0;
                    g.stamp = po.has("st") && po.get("st").getAsBoolean();
                    g.keyPerk = po.has("k") && po.get("k").getAsBoolean();
                    g.wrenTold = po.has("w") && po.get("w").getAsBoolean();
                    w.prog.put(UUID.fromString(pe.getKey()), g);
                }
                WEEKS.put(w.key, w);
            }
        } catch (RuntimeException ex) {
            Skyserver.LOGGER.warn("voyage.json couldn't be read fully: {}", ex.toString());
        }
    }

    private static JsonObject toJson() {
        // Kill counts for the tie-break: refreshed for online players at save time.
        Week cur = current();
        if (cur != null && server != null) for (Map.Entry<UUID, Prog> e : cur.prog.entrySet()) {
            if (server.getPlayerList().getPlayer(e.getKey()) == null || e.getValue().tieBase < 0) continue;
            e.getValue().tie = Math.max(0, PlayerStats.get(e.getKey(), "game_kill") - e.getValue().tieBase);
        }
        JsonObject root = new JsonObject();
        JsonArray bag = new JsonArray();
        BAG.forEach(bag::add);
        root.add("bag", bag);
        JsonObject st = new JsonObject();
        STAMPS.forEach((k, v) -> st.addProperty(k.toString(), v));
        root.add("stamps", st);
        JsonObject bk = new JsonObject();
        CLAIMED.forEach((k, v) -> {
            JsonArray a = new JsonArray();
            v.forEach(a::add);
            bk.add(k.toString(), a);
        });
        root.add("book", bk);
        JsonObject ch = new JsonObject();
        CHOICE.forEach((k, v) -> ch.addProperty(k.toString(), v));
        root.add("choice", ch);
        JsonObject nm = new JsonObject();
        NAMES.forEach((k, v) -> nm.addProperty(k.toString(), v));
        root.add("names", nm);
        JsonObject weeks = new JsonObject();
        for (Week w : WEEKS.values()) {
            JsonObject o = new JsonObject();
            if (w.twist != null) o.addProperty("twist", w.twist.id());
            o.addProperty("perk", w.perk.name());
            o.addProperty("course", w.course);
            o.addProperty("paid", w.paid);
            o.addProperty("off", w.off);
            o.addProperty("announced", w.announced);
            JsonObject pr = new JsonObject();
            w.prog.forEach((k, g) -> {
                JsonObject po = new JsonObject();
                po.addProperty("n", g.name);
                po.addProperty("c", g.count);
                po.addProperty("s", g.score);
                po.addProperty("t", g.tie);
                po.addProperty("tb", g.tieBase);
                po.addProperty("f", g.firstAt);
                po.addProperty("st", g.stamp);
                po.addProperty("k", g.keyPerk);
                po.addProperty("w", g.wrenTold);
                pr.add(k.toString(), po);
            });
            o.add("prog", pr);
            weeks.add(w.key, o);
        }
        root.add("weeks", weeks);
        return root;
    }

    private static void save() {
        dirty = false;
        JsonObject root = toJson();
        PlayerCache.io(() -> Stacks.writeFile("voyage.json", root));
    }

    private static void saveNow() {
        dirty = false;
        Stacks.writeFile("voyage.json", toJson());
    }

    // ---- For the menu ----

    static List<Week> history(int n) {
        load();
        List<Week> list = new ArrayList<>(WEEKS.values());
        Collections.reverse(list);
        List<Week> out = new ArrayList<>();
        String cur = currentKey();
        for (Week w : list) {
            if (w.key.equals(cur) || LocalDate.parse(w.key).isAfter(today())) continue;
            out.add(w);
            if (out.size() >= n) break;
        }
        return out;
    }
}
