package com.aidan.skyserver.lobby;

import com.aidan.skyserver.Skyserver;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.*;

/**
 * The battle pass (replaces the old 30-day level seasons). Numbers are in Progression.java.
 *
 *  - A season lasts 6 weeks and has 50 tiers of 1,000 pass XP. Seasons start on a Monday.
 *  - Pass XP: weekly challenges (7 new every Monday: 3 easy, 3 medium, 1 hard - the same for
 *    everyone; missed weeks stay open until the season ends), daily quests, and 15% of all server XP.
 *  - Free track for everyone; the Captain's Pass is bought with in-game money only ($30,000) and
 *    adds a second reward on every tier. Rewards are handed out automatically when a tier is reached
 *    (buying the Captain's Pass later pays its rewards for tiers already reached).
 *  - After tier 50: up to 10 bonus tiers (5,000 pass XP each): a Common Key + $250.
 *  - End of season: the top 3 by pass XP get Legendary, Epic and Rare keys; everyone starts fresh.
 * Commands: /pass, /pass challenges, /pass buy, /pass top, /cosmetics; ops: /pass admin ...
 * Saved in config/skyserver/battlepass.json (season + leaderboard) and each player's profile.
 */
public final class BattlePass {
    private static int season = 0;
    private static long startDay = 0;
    /** This season's pass XP for everyone (offline too), for the leaderboard and end-of-season prizes. */
    private static final Map<UUID, Long> XP = new HashMap<>();
    private static final Map<UUID, String> NAMES = new HashMap<>();
    private static final Map<UUID, Double> FRACTION = new HashMap<>();
    private static boolean loaded = false, dirty = false;
    private static MinecraftServer server;

    private BattlePass() {}

    // =====================================================================
    // Weekly challenges
    // =====================================================================

    /** key = the event name the games/island report (same names as daily quests), %d = target. */
    public record Challenge(String key, String text, int target, int difficulty) {
        public String describe() {
            return String.format(text, target);
        }
    }

    private static final List<List<Challenge>> POOL = List.of(
            List.of( // easy
                    c("sw_play", "Play %d Skywars matches", 3, 0), c("gw_play", "Play %d Gold Wars matches", 2, 0),
                    c("bw_play", "Play %d Bedwars matches", 2, 0), c("island_break", "Mine %d blocks on your Sky Island", 300, 0),
                    c("island_place", "Place %d blocks on your Sky Island", 200, 0), c("harvest", "Harvest %d grown crops on your island", 150, 0),
                    c("fish", "Catch %d fish at the lobby pond", 15, 0), c("parkour", "Finish %d obstacle courses", 2, 0),
                    c("crate", "Open %d mystery chests", 2, 0), c("daily_quest", "Complete %d daily quests", 4, 0),
                    c("elytra", "Finish the elytra ring course %d times", 2, 0), c("archery", "Score %d points in total at the archery range", 150, 0)),
            List.of( // medium
                    c("sw_win", "Win %d Skywars matches", 2, 1), c("gw_win", "Win %d Gold Wars match", 1, 1), c("bw_win", "Win %d Bedwars match", 1, 1),
                    c("sw_kill", "Get %d kills in Skywars", 10, 1), c("bw_final", "Get %d final kills in Bedwars", 5, 1),
                    c("bw_bed", "Break %d beds in Bedwars", 3, 1), c("gw_gold", "Break %d gold blocks in Gold Wars", 8, 1),
                    c("island_ore", "Mine %d ores on your Sky Island", 150, 1), c("challenge", "Complete %d island challenges", 3, 1),
                    c("duel", "Win %d duels", 3, 1), c("wren_quest", "Finish %d of Captain Wren's quests", 2, 1),
                    c("daily_quest", "Complete %d daily quests", 8, 1), c("harvest", "Harvest %d grown crops on your island", 600, 1)),
            List.of( // hard
                    c("sw_win", "Win %d Skywars matches", 5, 2), c("gw_win", "Win %d Gold Wars matches", 3, 2), c("bw_win", "Win %d Bedwars matches", 3, 2),
                    c("bw_final", "Get %d final kills in Bedwars", 15, 2), c("bw_bed", "Break %d beds in Bedwars", 8, 2),
                    c("island_ore", "Mine %d ores on your Sky Island", 500, 2), c("wren_quest", "Finish %d of Captain Wren's quests", 4, 2),
                    c("daily_quest", "Complete %d daily quests", 15, 2), c("sw_kill", "Get %d kills in Skywars", 30, 2),
                    c("gw_gold", "Break %d gold blocks in Gold Wars", 25, 2)));

    private static Challenge c(String key, String text, int target, int diff) {
        return new Challenge(key, text, target, diff);
    }

    private static final Map<String, List<Challenge>> WEEK_CACHE = new HashMap<>();

    /** The 7 challenges of a week (0-based) this season: the same for everyone. */
    public static List<Challenge> week(int w) {
        return WEEK_CACHE.computeIfAbsent(season + ":" + w, k -> {
            List<Challenge> out = new ArrayList<>();
            for (int d = 0; d < 3; d++) {
                List<Challenge> pool = new ArrayList<>(POOL.get(d));
                Collections.shuffle(pool, new Random(season * 1_000L + w * 10L + d));
                Set<String> keys = new HashSet<>();
                for (Challenge ch : pool) {
                    if (out.stream().filter(x -> x.difficulty() == ch.difficulty()).count() >= Progression.CHALLENGES_PER_WEEK[d]) break;
                    if (keys.add(ch.key())) out.add(ch);
                }
            }
            return out;
        });
    }

    /** Current week of the season, 0-based (0..5). */
    public static int weekNow() {
        load();
        long d = LocalDate.now().toEpochDay() - startDay;
        return (int) Math.max(0, Math.min(Progression.WEEKS - 1, d / 7));
    }

    /** Something happened (same event names as daily quests). Counts toward every open week's challenges. */
    public static void event(ServerPlayer p, String key, int amount) {
        if (p == null || amount <= 0 || p instanceof com.aidan.skyserver.bots.BotPlayer) return;
        load();
        PlayerProfile prof = PlayerCache.get(p);
        sync(prof);
        int now = weekNow();
        for (int w = 0; w <= now; w++) {
            List<Challenge> list = week(w);
            for (int i = 0; i < list.size(); i++) {
                Challenge ch = list.get(i);
                if (!ch.key().equals(key)) continue;
                String id = w + ":" + i;
                if (prof.challengeDone.contains(id)) continue;
                int before = prof.challenge.getOrDefault(id, 0);
                int after = Math.min(ch.target(), before + amount);
                prof.challenge.put(id, after);
                prof.touch();
                if (after >= ch.target()) {
                    prof.challengeDone.add(id);
                    int xp = Progression.CHALLENGE_PASS_XP[ch.difficulty()];
                    Lobby.msg(p, "§6§lWEEKLY CHALLENGE DONE: §e" + ch.describe() + " §b+" + xp + " pass XP");
                    addPassXp(p, xp);
                } else if (ch.target() > 1 && after * 4 / ch.target() > before * 4 / ch.target()) {
                    Announce.actionBar(p, "§6Weekly: §f" + ch.describe() + " §7(" + after + "/" + ch.target() + ")");
                }
            }
        }
    }

    /** A daily quest was finished (and whether that made all 3). Called by Quests. */
    public static void dailyQuestDone(ServerPlayer p, boolean allThree) {
        addPassXp(p, Progression.DAILY_QUEST_PASS_XP + (allThree ? Progression.DAILY_ALL_PASS_XP : 0));
        event(p, "daily_quest", 1);
    }

    /** A share of every server XP gain also counts toward the pass. Called by Levels. */
    static void fromServerXp(ServerPlayer p, int serverXp) {
        if (p == null || p instanceof com.aidan.skyserver.bots.BotPlayer) return;
        double f = FRACTION.getOrDefault(p.getUUID(), 0.0) + serverXp * Progression.PASS_SHARE_OF_XP;
        int whole = (int) Math.floor(f);
        FRACTION.put(p.getUUID(), f - whole);
        if (whole > 0) addPassXp(p, whole);
    }

    // =====================================================================
    // Pass XP and tiers
    // =====================================================================

    /** A profile from an earlier season starts fresh for this one. */
    private static void sync(PlayerProfile prof) {
        if (prof.passSeason == season) return;
        prof.passSeason = season;
        prof.passXp = 0;
        prof.premium = false;
        prof.freePaid = prof.premiumPaid = prof.bonusPaid = 0;
        prof.challenge.clear();
        prof.challengeDone.clear();
        prof.touch();
    }

    public static void addPassXp(ServerPlayer p, int amount) {
        if (p == null || amount <= 0 || p instanceof com.aidan.skyserver.bots.BotPlayer) return;
        load();
        PlayerProfile prof = PlayerCache.get(p);
        sync(prof);
        prof.passXp += amount;
        prof.touch();
        XP.merge(p.getUUID(), (long) amount, Long::sum);
        NAMES.put(p.getUUID(), p.getName().getString());
        dirty = true;
        if (PlayerCache.isLoaded(p.getUUID())) payTiers(p, prof);
    }

    public static int tierOf(long passXp) {
        return (int) Math.min(Progression.TIERS, passXp / Progression.TIER_XP);
    }

    /** Hands out every reward this player has reached but not been given. */
    private static void payTiers(ServerPlayer p, PlayerProfile prof) {
        int tier = tierOf(prof.passXp);
        for (int t = prof.freePaid + 1; t <= tier; t++) {
            Lobby.msg(p, "§6§lPASS TIER " + t + "! §7Free reward: " + give(p, Progression.freeReward(season, t), t));
            if (t == Progression.TIERS) give(p, new Progression.Reward(Progression.Kind.KEY, Progression.FREE_FINAL_KEY, null), t);
            prof.freePaid = t;
        }
        if (prof.premium) for (int t = prof.premiumPaid + 1; t <= tier; t++) {
            Lobby.msg(p, "§6§lCAPTAIN'S PASS TIER " + t + ": §7" + give(p, Progression.premiumReward(season, t), t));
            if (t == Progression.TIERS) give(p, new Progression.Reward(Progression.Kind.KEY, Progression.PREMIUM_FINAL_KEY, null), t);
            prof.premiumPaid = t;
        }
        long over = prof.passXp - (long) Progression.TIERS * Progression.TIER_XP;
        int bonus = over <= 0 ? 0 : (int) Math.min(Progression.MAX_BONUS_TIERS, over / Progression.BONUS_TIER_XP);
        for (int b = prof.bonusPaid + 1; b <= bonus; b++) {
            Economy.add(p.level().getServer(), p.getUUID(), p.getName().getString(), Progression.BONUS_MONEY);
            Keys.give(p.getUUID(), 0, 1);
            Lobby.msg(p, "§6§lBONUS TIER " + b + "/" + Progression.MAX_BONUS_TIERS + "! §a+" + Economy.format(Progression.BONUS_MONEY) + " §7and a "
                    + Keys.COLORS[0] + Keys.NAMES[0] + " Key");
            prof.bonusPaid = b;
        }
        prof.touch();
    }

    /** Gives one reward; returns its description. */
    private static String give(ServerPlayer p, Progression.Reward r, int tier) {
        MinecraftServer s = p.level().getServer();
        switch (r.kind()) {
            case MONEY -> {
                Economy.add(s, p.getUUID(), p.getName().getString(), r.value());
                return "§a+" + Economy.format(r.value());
            }
            case KEY -> {
                int t = (int) r.value();
                Keys.give(p.getUUID(), t, 1);
                Lobby.refreshKit(p);
                return Keys.COLORS[t] + Keys.NAMES[t] + " Key";
            }
            case COSMETIC -> {
                PlayerProfile prof = PlayerCache.get(p);
                String name = Progression.cosmeticName(r.cosmetic());
                if (!prof.owned.add(r.cosmetic())) {
                    Economy.add(s, p.getUUID(), p.getName().getString(), 500);
                    return name + " §7(already owned: §a+$500 §7instead)";
                }
                prof.touch();
                p.sendSystemMessage(Component.literal("§7New cosmetic: " + name + " ")
                        .append(button("§a[Equip]", "/cosmetics equip " + r.cosmetic(), "Use it now")));
                return name;
            }
            default -> {
                return "§7nothing";
            }
        }
    }

    /** Short text for a reward (menus). */
    public static String rewardText(Progression.Reward r) {
        return switch (r.kind()) {
            case MONEY -> "§6" + Economy.format(r.value());
            case KEY -> Keys.COLORS[(int) r.value()] + Keys.NAMES[(int) r.value()] + " Key";
            case COSMETIC -> Progression.cosmeticName(r.cosmetic());
            default -> "§7-";
        };
    }

    static void buy(ServerPlayer p, boolean confirmed) {
        PlayerProfile prof = PlayerCache.get(p);
        sync(prof);
        if (prof.premium) {
            Lobby.msg(p, "§7You already have this season's Captain's Pass.");
            return;
        }
        if (!PlayerCache.isLoaded(p.getUUID())) {
            Lobby.msg(p, "§7Your data is still loading - try again in a second.");
            return;
        }
        if (!confirmed) {
            p.sendSystemMessage(Component.literal("§6Captain's Pass §7for Season " + season + " costs §6" + Economy.format(Progression.PREMIUM_PRICE)
                    + "§7 (in-game money). It adds a second reward on every tier, including tiers you've already reached. ")
                    .append(button("§a[Buy it]", "/pass buy confirm", "Pay " + Economy.format(Progression.PREMIUM_PRICE))));
            return;
        }
        if (!Economy.take(p.level().getServer(), p.getUUID(), Progression.PREMIUM_PRICE)) {
            Lobby.msg(p, "§cThe Captain's Pass costs " + Economy.format(Progression.PREMIUM_PRICE) + ".");
            return;
        }
        prof.premium = true;
        prof.touch();
        Lobby.msg(p, "§6§lCAPTAIN'S PASS UNLOCKED! §7Welcome aboard, captain.");
        payTiers(p, prof);
    }

    // =====================================================================
    // Seasons
    // =====================================================================

    public static int season() {
        load();
        return season;
    }

    public static long daysLeft() {
        load();
        return Math.max(0, startDay + Progression.SEASON_DAYS - LocalDate.now().toEpochDay());
    }

    /** Days until the next weekly challenges (1..7). */
    public static long daysToNextWeek() {
        long d = LocalDate.now().toEpochDay() - startDay;
        return 7 - Math.floorMod(d, 7);
    }

    /** The Monday on or before today. */
    private static long thisMonday() {
        LocalDate t = LocalDate.now();
        return t.minusDays(t.getDayOfWeek().getValue() - DayOfWeek.MONDAY.getValue()).toEpochDay();
    }

    /** Called every minute: ends the season when 6 weeks are up and starts the next. */
    public static void checkSeason(MinecraftServer s) {
        load();
        server = s;
        long today = LocalDate.now().toEpochDay();
        if (today < startDay + Progression.SEASON_DAYS) return;
        endSeason(s, false);
        while (startDay + Progression.SEASON_DAYS <= today) startDay += Progression.SEASON_DAYS;
        dirty = true;
        saveSoon();
    }

    private static void endSeason(MinecraftServer s, boolean now) {
        List<Map.Entry<UUID, Long>> all = new ArrayList<>(XP.entrySet());
        all.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
        StringBuilder msg = new StringBuilder("§6§lSEASON " + season + " (" + Progression.seasonName(season) + ") IS OVER!");
        for (int i = 0; i < Math.min(3, all.size()); i++) {
            if (all.get(i).getValue() <= 0) break;
            UUID id = all.get(i).getKey();
            int tier = Progression.SEASON_PRIZE_TIER[i];
            Keys.give(id, tier, 1);
            msg.append("\n§6").append(i + 1).append(". §f").append(NAMES.getOrDefault(id, "?")).append(" §7(tier ")
                    .append(tierOf(all.get(i).getValue())).append(") wins a ").append(Keys.COLORS[tier]).append(Keys.NAMES[tier]).append(" Key");
        }
        season++;
        if (now) startDay = thisMonday();
        XP.clear();
        FRACTION.clear();
        WEEK_CACHE.clear();
        dirty = true;
        saveSoon();
        msg.append("\n§eSeason ").append(season).append(": §6").append(Progression.seasonName(season))
                .append(" §estarts now! §7/pass for the new rewards and challenges.");
        s.getPlayerList().broadcastSystemMessage(Component.literal(msg.toString()), false);
        for (ServerPlayer p : s.getPlayerList().getPlayers()) {
            if (p instanceof com.aidan.skyserver.bots.BotPlayer) continue;
            sync(PlayerCache.get(p));
            Lobby.refreshKit(p);
        }
    }

    // =====================================================================
    // Setup and commands
    // =====================================================================

    public static void init() {
        ServerLifecycleEvents.SERVER_STARTED.register(s -> {
            server = s;
            load();
        });
        PlayerCache.onLoad(p -> {
            PlayerProfile prof = PlayerCache.get(p);
            sync(prof);
            XP.put(p.getUUID(), prof.passXp);
            NAMES.put(p.getUUID(), p.getName().getString());
            payTiers(p, prof);
        });
        CommandRegistrationCallback.EVENT.register((d, r, e) -> {
            d.register(Commands.literal("pass")
                    .executes(c -> { PassMenu.open(c.getSource().getPlayerOrException(), -1); return 1; })
                    .then(Commands.literal("challenges").executes(c -> { showChallenges(c.getSource().getPlayerOrException()); return 1; }))
                    .then(Commands.literal("buy").executes(c -> { buy(c.getSource().getPlayerOrException(), false); return 1; })
                            .then(Commands.literal("confirm").executes(c -> { buy(c.getSource().getPlayerOrException(), true); return 1; })))
                    .then(Commands.literal("top").executes(c -> {
                        ServerPlayer p = c.getSource().getPlayerOrException();
                        Lobby.msg(p, "§6§lSEASON " + season + " TOP §7(" + daysLeft() + " days left; top 3 win Legendary/Epic/Rare keys)");
                        for (String line : top(10)) Lobby.msg(p, line);
                        return 1;
                    }))
                    .then(Commands.literal("admin").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                            .then(Commands.literal("endseason").executes(c -> {
                                endSeason(c.getSource().getServer(), true);
                                c.getSource().sendSuccess(() -> Component.literal("Season ended; season " + season + " started."), true);
                                return 1;
                            }))
                            .then(Commands.literal("givexp").then(Commands.argument("player", EntityArgument.player())
                                    .then(Commands.argument("amount", IntegerArgumentType.integer(1, 1_000_000)).executes(c -> {
                                        ServerPlayer t = EntityArgument.getPlayer(c, "player");
                                        int a = IntegerArgumentType.getInteger(c, "amount");
                                        addPassXp(t, a);
                                        c.getSource().sendSuccess(() -> Component.literal("Gave " + a + " pass XP to " + t.getName().getString()), true);
                                        return 1;
                                    }))))
                            .then(Commands.literal("givepremium").then(Commands.argument("player", EntityArgument.player()).executes(c -> {
                                ServerPlayer t = EntityArgument.getPlayer(c, "player");
                                PlayerProfile prof = PlayerCache.get(t);
                                sync(prof);
                                prof.premium = true;
                                prof.touch();
                                if (PlayerCache.isLoaded(t.getUUID())) payTiers(t, prof);
                                c.getSource().sendSuccess(() -> Component.literal("Gave the Captain's Pass to " + t.getName().getString()), true);
                                return 1;
                            })))));
            d.register(Commands.literal("cosmetics")
                    .executes(c -> { showCosmetics(c.getSource().getPlayerOrException()); return 1; })
                    .then(Commands.literal("equip").then(Commands.argument("id", StringArgumentType.word())
                            .suggests((c, b) -> SharedSuggestionProvider.suggest(c.getSource().getPlayer() == null ? List.of()
                                    : PlayerCache.get(c.getSource().getPlayer()).owned, b))
                            .executes(c -> { equip(c.getSource().getPlayerOrException(), StringArgumentType.getString(c, "id")); return 1; })))
                    .then(Commands.literal("clear").executes(c -> {
                        ServerPlayer p = c.getSource().getPlayerOrException();
                        PlayerProfile prof = PlayerCache.get(p);
                        prof.title = null;
                        prof.trail = null;
                        prof.killFx = null;
                        prof.winFx = null;
                        prof.touch();
                        Lobby.msg(p, "§7Title, trail and effects removed.");
                        return 1;
                    })));
        });
    }

    static List<String> top(int n) {
        load();
        List<Map.Entry<UUID, Long>> all = new ArrayList<>(XP.entrySet());
        all.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < Math.min(n, all.size()); i++) {
            if (all.get(i).getValue() <= 0) break;
            lines.add("§6" + (i + 1) + ". §f" + NAMES.getOrDefault(all.get(i).getKey(), "?") + " §7tier §e" + tierOf(all.get(i).getValue())
                    + " §8(" + String.format("%,d", all.get(i).getValue()) + " pass XP)");
        }
        if (lines.isEmpty()) lines.add("§7nobody yet - /pass challenges");
        return lines;
    }

    static void showChallenges(ServerPlayer p) {
        PlayerProfile prof = PlayerCache.get(p);
        sync(prof);
        int now = weekNow();
        String[] diff = {"§aEasy", "§eMedium", "§cHard"};
        Lobby.msg(p, "§6§lWEEKLY CHALLENGES §7- Season " + season + ", week " + (now + 1) + "/" + Progression.WEEKS
                + " §8(new ones in " + daysToNextWeek() + " day" + (daysToNextWeek() == 1 ? "" : "s") + ")");
        for (int w = now; w >= 0; w--) {
            List<Challenge> list = week(w);
            int open = 0;
            for (int i = 0; i < list.size(); i++) if (!prof.challengeDone.contains(w + ":" + i)) open++;
            if (w < now && open == 0) continue;
            if (w < now) Lobby.msg(p, "§7Week " + (w + 1) + " §8(still open until the season ends)");
            for (int i = 0; i < list.size(); i++) {
                Challenge ch = list.get(i);
                String id = w + ":" + i;
                boolean done = prof.challengeDone.contains(id);
                Lobby.msg(p, (done ? " §a✔ " : " §e• ") + diff[ch.difficulty()] + " §f" + ch.describe() + " §7("
                        + Math.min(ch.target(), prof.challenge.getOrDefault(id, 0)) + "/" + ch.target() + ") §b+"
                        + Progression.CHALLENGE_PASS_XP[ch.difficulty()] + " pass XP");
            }
        }
    }

    static void showCosmetics(ServerPlayer p) {
        PlayerProfile prof = PlayerCache.get(p);
        if (prof.owned.isEmpty()) {
            Lobby.msg(p, "§7You don't have any cosmetics yet. They come from the battle pass (§e/pass§7), mystery chests, the §e/cshop §7and the §e/shards §7shop.");
            return;
        }
        Lobby.msg(p, "§6§lYOUR COSMETICS §7(click to use; §e/cosmetics clear §7to remove)");
        for (String id : prof.owned) {
            boolean on = id.equals(prof.title) || id.equals(prof.trail) || id.equals(prof.killFx) || id.equals(prof.winFx);
            String hover = Progression.isTitle(id) ? "Show this title after your name" : Progression.isKillEffect(id) ? "Plays where players you kill fall"
                    : Progression.isWinEffect(id) ? "Plays around you when you win a game" : "Particles behind you and your arrows";
            p.sendSystemMessage(Component.literal(on ? " §a✔ " : " §7• ").append(button(Progression.cosmeticName(id) + (on ? " §7(in use)" : ""),
                    "/cosmetics equip " + id, hover)));
        }
    }

    static void equip(ServerPlayer p, String id) {
        PlayerProfile prof = PlayerCache.get(p);
        if (!prof.owned.contains(id)) {
            Lobby.msg(p, "§cYou don't own that cosmetic. §7/cosmetics");
            return;
        }
        if (Progression.isTitle(id)) prof.title = id;
        else if (Progression.isTrail(id)) prof.trail = id;
        else if (Progression.isKillEffect(id)) prof.killFx = id;
        else if (Progression.isWinEffect(id)) prof.winFx = id;
        prof.touch();
        Lobby.msg(p, "§aNow using " + Progression.cosmeticName(id) + "§a.");
    }

    static MutableComponent button(String text, String command, String hover) {
        return Component.literal(text).withStyle(Style.EMPTY.withClickEvent(new ClickEvent.RunCommand(command))
                .withHoverEvent(new HoverEvent.ShowText(Component.literal(hover))));
    }

    // =====================================================================
    // Saving
    // =====================================================================

    private static void load() {
        if (loaded) return;
        loaded = true;
        JsonObject root = Stacks.readFile("battlepass.json");
        season = root.has("season") ? root.get("season").getAsInt() : 0;
        startDay = root.has("start") ? root.get("start").getAsLong() : 0;
        if (root.has("xp")) for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("xp").entrySet())
            XP.put(UUID.fromString(e.getKey()), e.getValue().getAsLong());
        if (root.has("names")) for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("names").entrySet())
            NAMES.put(UUID.fromString(e.getKey()), e.getValue().getAsString());
        if (season <= 0) {
            season = 1;
            startDay = thisMonday();
            dirty = true;
            Skyserver.LOGGER.info("Battle pass: Season 1 ({}) started.", Progression.seasonName(1));
            saveSoon();
        }
    }

    /** Writes battlepass.json in the background if anything changed (call from the main thread). */
    static void saveSoon() {
        if (!dirty) return;
        dirty = false;
        JsonObject root = new JsonObject(), xp = new JsonObject(), names = new JsonObject();
        root.addProperty("season", season);
        root.addProperty("start", startDay);
        XP.forEach((k, v) -> xp.addProperty(k.toString(), v));
        NAMES.forEach((k, v) -> names.addProperty(k.toString(), v));
        root.add("xp", xp);
        root.add("names", names);
        PlayerCache.io(() -> Stacks.writeFile("battlepass.json", root));
    }
}
