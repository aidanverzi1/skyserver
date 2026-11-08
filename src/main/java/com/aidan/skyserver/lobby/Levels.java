package com.aidan.skyserver.lobby;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.network.protocol.game.ClientboundSetExperiencePacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.time.LocalDate;
import java.util.*;

/**
 * The server level: one level for the whole server (1-99, then Prestige 1-99), earned everywhere
 * and never reset. All numbers are in Progression.java.
 *
 *  - XP for the next level: 500 + 35 per level (215,000 XP to reach 99: about 5 months for
 *    someone who plays 1.5 hours a day, 2-3 months for heavy players). After 99, every 4,000 XP
 *    is a prestige level; every 10 prestige levels gives a new label before your name.
 *  - Level-up reward: $100 + $10 x level (paid once ever) and keys at milestones.
 *  - The big modes (Skywars, Gold Wars, Bedwars), Sky Island quests/challenges and daily quests
 *    give full XP. Island activity gives 1 XP per 12 skill XP (max 450/hour, 1,500/day). Side
 *    activities (parkour, spleef, duels, archery, maze, elytra, fishing, the Mine, logins, chests)
 *    share a daily cap of 600.
 *  - Daily boost: the first 300 XP each day counts double.
 *  - Seasons now belong to the battle pass (BattlePass); levels never reset.
 * Saved in config/skyserver/levels.json (written in the background, not on every XP gain).
 */
public final class Levels {
    public static final int DAILY_BOOST_XP = Progression.DAILY_BOOST_XP;

    /** {total xp, highest level paid, highest prestige paid} per player, everyone (offline too). */
    private static final Map<UUID, long[]> DATA = new HashMap<>();
    private static final Map<UUID, String> NAMES = new HashMap<>();
    private static boolean loaded = false, dirty = false;

    private Levels() {}

    public static void init() {
        ServerLifecycleEvents.SERVER_STARTED.register(s -> load());
    }

    // =====================================================================
    // Numbers
    // =====================================================================

    public static int xpForNext(int level) {
        return Progression.xpForNext(level);
    }

    /**
     * {level, xp into the level, xp needed for the next level}. At level 99 the last two show
     * progress toward the next prestige level instead.
     */
    public static int[] levelOf(long total) {
        int[] l = Progression.levelOf(total);
        if (l[0] < Progression.MAX_LEVEL) return l;
        int[] pp = Progression.prestigeProgress(total);
        return new int[]{Progression.MAX_LEVEL, pp[0], Math.max(1, pp[1])};
    }

    private static long[] get(UUID id) {
        load();
        return DATA.computeIfAbsent(id, k -> new long[]{0, 1, 0});
    }

    /** Server level 1..99 (works for offline players too). */
    public static int level(UUID id) {
        return Progression.levelOf(get(id)[0])[0];
    }

    /** Prestige level (0 = not prestiged yet). */
    public static int prestige(UUID id) {
        return Progression.prestigeOf(get(id)[0]);
    }

    public static long totalXp(UUID id) {
        return get(id)[0];
    }

    /** "Level 42" or "Prestige 12" (no colours). */
    public static String shortName(UUID id) {
        int p = prestige(id);
        return p > 0 ? "Prestige " + p : "Level " + level(id);
    }

    /** The tag before a player's name: "§e[42] " or "§bCloudwalker " (with a trailing space). */
    public static String chatTag(UUID id) {
        int p = prestige(id);
        if (p > 0) return "§8[" + Progression.label(p) + "§8] ";
        int l = level(id);
        return Progression.levelColor(l) + "[" + l + "] ";
    }

    // =====================================================================
    // Earning XP
    // =====================================================================

    private enum Source { FULL, SIDE }

    /** Classes whose XP is never capped (big modes, island quests/challenges, daily quests, the pass). */
    private static final String[] FULL_XP = {"Skywars", "Sw", "GoldWars", "Gw", "Bedwars", "Bw", "DamageCredit", "Longbow",
            "Quests", "IslandQuests", "Challenges", "BattlePass", "Islands", "Expedition"};

    /** Where this XP came from, judged by which part of SkyServer asked for it. */
    private static Source source() {
        String cls = StackWalker.getInstance().walk(s -> s
                .map(f -> f.getClassName())
                .filter(c -> !c.endsWith(".Levels") && !c.contains(".Levels$") && !c.endsWith(".Rewards") && !c.contains(".Rewards$"))
                .findFirst().orElse(""));
        String simple = cls.substring(cls.lastIndexOf('.') + 1).replaceAll("\\$.*", "");
        for (String f : FULL_XP) if (simple.startsWith(f)) return Source.FULL;
        return Source.SIDE;
    }

    /** Adds XP (with the daily double-XP boost and the caps above) and hands out level-up rewards. */
    public static void addXp(MinecraftServer server, ServerPlayer p, int amount) {
        if (amount <= 0 || p == null || p instanceof com.aidan.skyserver.bots.BotPlayer) return;
        amount = Boosters.xp(p, Events.xp(amount)); // 5.8: XP boosters
        PlayerProfile prof = PlayerCache.get(p);
        if (source() == Source.SIDE) {
            long today = LocalDate.now().toEpochDay();
            if (prof.sideDay != today) {
                prof.sideDay = today;
                prof.sideToday = 0;
            }
            int allowed = Math.min(amount, Progression.SIDE_DAY_CAP - prof.sideToday);
            if (allowed <= 0) return;
            prof.sideToday += allowed;
            prof.touch();
            amount = allowed;
        }
        grant(server, p, prof, amount, false);
    }

    /**
     * Island activity (mining, farming, animals, building on your island): 1 server XP for every
     * 12 skill XP, at most 450 an hour and 1,500 a day. Called by Islands.
     */
    public static void addIslandSkillXp(ServerPlayer p, int skillXp) {
        if (skillXp <= 0 || p == null || p instanceof com.aidan.skyserver.bots.BotPlayer) return;
        PlayerProfile prof = PlayerCache.get(p);
        prof.islandFrac += skillXp / (double) Progression.ISLAND_SKILL_PER_XP;
        int whole = (int) Math.floor(prof.islandFrac);
        if (whole <= 0) return;
        prof.islandFrac -= whole;
        long now = System.currentTimeMillis(), today = LocalDate.now().toEpochDay();
        if (now - prof.islandHourStart >= 3_600_000L) {
            prof.islandHourStart = now;
            prof.islandHour = 0;
        }
        if (prof.islandDay != today) {
            prof.islandDay = today;
            prof.islandToday = 0;
        }
        int allowed = Math.min(whole, Math.min(Progression.ISLAND_HOUR_CAP - prof.islandHour, Progression.ISLAND_DAY_CAP - prof.islandToday));
        prof.touch();
        if (allowed <= 0) return;
        prof.islandHour += allowed;
        prof.islandToday += allowed;
        grant(p.level().getServer(), p, prof, Events.xp(allowed), true);
    }

    private static void grant(MinecraftServer server, ServerPlayer p, PlayerProfile prof, int amount, boolean quiet) {
        long today = LocalDate.now().toEpochDay();
        if (prof.boostDay != today) {
            prof.boostDay = today;
            prof.boosted = 0;
        }
        int bonus = Math.min(amount, Math.max(0, Progression.DAILY_BOOST_XP - prof.boosted));
        prof.boosted += bonus;
        prof.touch();
        int total = amount + bonus;
        long[] d = get(p.getUUID());
        d[0] += total;
        NAMES.put(p.getUUID(), p.getName().getString());
        dirty = true;
        boolean inMatch = Rewards.tallyXp(p.getUUID(), total);
        if (!quiet || bonus > 0 && !inMatch) {
            p.connection.send(new net.minecraft.network.protocol.game.ClientboundSetActionBarTextPacket(
                    net.minecraft.network.chat.Component.literal("§b+" + total + " XP" + (bonus > 0 ? " §e(daily double XP)" : ""))));
        }
        payLevelUps(server, p);
        BattlePass.fromServerXp(p, total);
        showBar(p);
    }

    /** Pays every level / prestige reward this player has reached but not been paid for. */
    private static void payLevelUps(MinecraftServer server, ServerPlayer p) {
        long[] d = get(p.getUUID());
        int lvl = Progression.levelOf(d[0])[0];
        for (int l = (int) d[1] + 1; l <= lvl; l++) levelUp(server, p, l);
        if (lvl > d[1]) d[1] = lvl;
        int pr = Progression.prestigeOf(d[0]);
        for (int x = (int) d[2] + 1; x <= pr; x++) prestigeUp(server, p, x);
        if (pr > d[2]) d[2] = pr;
    }

    /** Money paid for reaching this level. */
    public static long rewardMoney(int lvl) {
        return Progression.levelMoney(lvl);
    }

    /** Key tier given for reaching this level (-1 = none). */
    public static int rewardTier(int lvl) {
        return Progression.levelKeyTier(lvl);
    }

    /** "$520 + Rare Key" style text for a level's reward. */
    public static String rewardText(int lvl) {
        int t = rewardTier(lvl);
        return "§6" + Economy.format(rewardMoney(lvl)) + (t >= 0 ? " §7+ " + Keys.COLORS[t] + Keys.NAMES[t] + " Key" : "");
    }

    private static String prestigeRewardText(int pr) {
        int t = Progression.prestigeKeyTier(pr);
        return "§6" + Economy.format(Progression.prestigeMoney(pr)) + (t >= 0 ? " §7+ " + Keys.COLORS[t] + Keys.NAMES[t] + " Key" : "")
                + (Progression.labelIndex(pr) != Progression.labelIndex(pr - 1) ? " §7+ label " + Progression.label(pr) : "");
    }

    private static void levelUp(MinecraftServer server, ServerPlayer p, int lvl) {
        long money = rewardMoney(lvl);
        int tier = rewardTier(lvl);
        Economy.add(server, p.getUUID(), p.getName().getString(), money);
        if (tier >= 0) Keys.give(p.getUUID(), tier, 1);
        Lobby.msg(p, "§6§lLEVEL UP! §eYou are now level §f" + lvl + "§e. §a+" + Economy.format(money)
                + (tier >= 0 ? " §7and a " + Keys.COLORS[tier] + Keys.NAMES[tier] + " Key§7." : "§7."));
        p.level().playSound(null, p.getX(), p.getY(), p.getZ(), net.minecraft.sounds.SoundEvents.PLAYER_LEVELUP,
                net.minecraft.sounds.SoundSource.PLAYERS, 1f, 0.8f);
        if (lvl % 10 == 0 || lvl == Progression.MAX_LEVEL) {
            server.getPlayerList().broadcastSystemMessage(net.minecraft.network.chat.Component.literal(
                    "§6✦ §e" + p.getName().getString() + " §7reached §6level " + lvl + "§7!"
                            + (lvl == Progression.MAX_LEVEL ? " §eNext stop: Prestige!" : "")), false);
        }
        Lobby.refreshKit(p);
    }

    private static void prestigeUp(MinecraftServer server, ServerPlayer p, int pr) {
        long money = Progression.prestigeMoney(pr);
        int tier = Progression.prestigeKeyTier(pr);
        Economy.add(server, p.getUUID(), p.getName().getString(), money);
        if (tier >= 0) Keys.give(p.getUUID(), tier, 1);
        boolean newLabel = Progression.labelIndex(pr) != Progression.labelIndex(pr - 1);
        Lobby.msg(p, "§d§lPRESTIGE " + pr + "! §a+" + Economy.format(money)
                + (tier >= 0 ? " §7and a " + Keys.COLORS[tier] + Keys.NAMES[tier] + " Key" : "")
                + (newLabel ? " §7- new label: " + Progression.label(pr) : "") + "§7.");
        p.level().playSound(null, p.getX(), p.getY(), p.getZ(), net.minecraft.sounds.SoundEvents.UI_TOAST_CHALLENGE_COMPLETE,
                net.minecraft.sounds.SoundSource.PLAYERS, 1f, 1f);
        if (newLabel) {
            server.getPlayerList().broadcastSystemMessage(net.minecraft.network.chat.Component.literal(
                    "§d✦ §e" + p.getName().getString() + " §7reached §dPrestige " + pr + " §7and is now " + Progression.label(pr) + "§7!"), false);
        }
        Lobby.refreshKit(p);
    }

    // =====================================================================
    // Showing it
    // =====================================================================

    /** A 20-segment progress bar like ■■■■■■□□□□ 62%. */
    public static String progressBar(UUID id) {
        int[] l = levelOf(get(id)[0]);
        if (l[2] <= 0) return "§6■■■■■■■■■■■■■■■■■■■■ §fMAX";
        int filled = (int) (20L * l[1] / l[2]);
        return "§a" + "■".repeat(filled) + "§8" + "■".repeat(20 - filled) + " §f" + (100L * l[1] / l[2]) + "%";
    }

    /** Lines describing the next few rewards plus the next big milestones. */
    public static List<String> upcoming(UUID id, int count) {
        List<String> lines = new ArrayList<>();
        long total = totalXp(id);
        int pr = Progression.prestigeOf(total);
        int lvl = Progression.levelOf(total)[0];
        if (lvl < Progression.MAX_LEVEL) {
            for (int n = lvl + 1; n <= Math.min(Progression.MAX_LEVEL, lvl + count); n++) lines.add("§eLevel " + n + ": " + rewardText(n));
            int[] tiers = {2, 3, 4};
            for (int t : tiers) {
                int next = lvl + 1;
                while (next <= Progression.MAX_LEVEL && rewardTier(next) != t) next++;
                if (next > Progression.MAX_LEVEL || next <= lvl + count) continue;
                long xp = Math.max(0, Progression.totalFor(next) - total);
                lines.add("§7Next " + Keys.COLORS[t] + Keys.NAMES[t] + " Key §7at level §e" + next + " §8(" + String.format("%,d", xp) + " XP away)");
            }
            if (lvl + count < Progression.MAX_LEVEL)
                lines.add("§7Prestige starts after level 99 §8(" + String.format("%,d", Math.max(0, Progression.XP_TO_99 - total)) + " XP away)");
        } else if (pr < Progression.MAX_PRESTIGE) {
            for (int n = pr + 1; n <= Math.min(Progression.MAX_PRESTIGE, pr + count); n++) lines.add("§dPrestige " + n + ": " + prestigeRewardText(n));
            int nextLabel = pr + 1;
            while (nextLabel <= Progression.MAX_PRESTIGE && Progression.labelIndex(nextLabel) == Progression.labelIndex(pr)) nextLabel++;
            if (nextLabel <= Progression.MAX_PRESTIGE && nextLabel > pr + count) {
                long xp = Progression.XP_TO_99 + (long) nextLabel * Progression.PRESTIGE_XP - total;
                lines.add("§7Next label " + Progression.label(nextLabel) + " §7at Prestige §d" + nextLabel + " §8(" + String.format("%,d", xp) + " XP away)");
            }
        } else {
            lines.add("§6You've reached the top: §6§l✦Eternal✦§6. Legendary!");
        }
        return lines;
    }

    public static String describe(UUID id) {
        long total = get(id)[0];
        int pr = Progression.prestigeOf(total);
        PlayerProfile prof = PlayerCache.ifOnline(id);
        long today = LocalDate.now().toEpochDay();
        int boostLeft = prof == null ? 0 : prof.boostDay == today ? Math.max(0, Progression.DAILY_BOOST_XP - prof.boosted) : Progression.DAILY_BOOST_XP;
        String boost = boostLeft > 0 ? " §b" + boostLeft + " daily double XP left today" : "";
        if (pr > 0) {
            int[] pp = Progression.prestigeProgress(total);
            return "§dPrestige " + pr + " §7(" + Progression.label(pr) + "§7)"
                    + (pp[1] > 0 ? " §7(" + pp[0] + "/" + pp[1] + " XP to Prestige " + (pr + 1) + ")" : " §6max!") + boost;
        }
        int[] l = Progression.levelOf(total);
        if (l[0] >= Progression.MAX_LEVEL) {
            int[] pp = Progression.prestigeProgress(total);
            return "§eLevel §f99 §7(" + pp[0] + "/" + pp[1] + " XP to Prestige 1)" + boost;
        }
        return "§eLevel §f" + l[0] + " §7(" + l[1] + "/" + l[2] + " XP to level " + (l[0] + 1) + ")" + boost;
    }

    /** Top players by level (prestige counts above 99) for leaderboards. */
    public static List<String> top(int n) {
        load();
        List<Map.Entry<UUID, long[]>> all = new ArrayList<>(DATA.entrySet());
        all.sort((a, b) -> Long.compare(b.getValue()[0], a.getValue()[0]));
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < Math.min(n, all.size()); i++) {
            if (all.get(i).getValue()[0] <= 0) break;
            UUID id = all.get(i).getKey();
            long xp = all.get(i).getValue()[0];
            int pr = Progression.prestigeOf(xp);
            String what = pr > 0 ? Progression.label(pr) + " §7P" + pr : "§7level §e" + Progression.levelOf(xp)[0];
            lines.add("§6" + (i + 1) + ". §f" + NAMES.getOrDefault(id, "?") + " " + what);
        }
        return lines;
    }

    /** In the lobby the XP bar shows the server level (or prestige level) and progress. */
    public static void showBar(ServerPlayer p) {
        if (!Lobby.isLobby(p.level())) return;
        long total = get(p.getUUID())[0];
        int pr = Progression.prestigeOf(total);
        int[] l = levelOf(total);
        float progress = l[2] <= 0 ? 1f : Math.min(1f, l[1] / (float) l[2]);
        int shown = pr > 0 ? pr : l[0];
        p.experienceLevel = shown;
        p.experienceProgress = progress;
        p.totalExperience = 0;
        p.connection.send(new ClientboundSetExperiencePacket(progress, 0, shown));
    }

    // ---- Seasons now belong to the battle pass ----

    public static int season() {
        return BattlePass.season();
    }

    public static long daysLeft() {
        return BattlePass.daysLeft();
    }

    /** Called every minute by the lobby: ends the battle pass season when it's over. */
    public static void checkSeason(MinecraftServer server) {
        BattlePass.checkSeason(server);
    }

    // =====================================================================
    // Saving
    // =====================================================================

    private static void load() {
        if (loaded) return;
        loaded = true;
        JsonObject root = Stacks.readFile("levels.json");
        boolean old = !root.has("version");
        if (root.has("players")) for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("players").entrySet()) {
            JsonObject o = e.getValue().getAsJsonObject();
            UUID id = UUID.fromString(e.getKey());
            long xp = o.has("xp") ? o.get("xp").getAsLong() : 0;
            long paid, paidP;
            if (old) {
                // From the old 30-day system: keep the XP, and only pay rewards for levels above the old level.
                paid = Math.min(Progression.levelOf(xp)[0], oldLevelOf(xp));
                paidP = 0;
            } else {
                paid = o.has("paid") ? o.get("paid").getAsLong() : Progression.levelOf(xp)[0];
                paidP = o.has("paidP") ? o.get("paidP").getAsLong() : Progression.prestigeOf(xp);
            }
            DATA.put(id, new long[]{xp, Math.max(1, paid), paidP});
            if (o.has("name")) NAMES.put(id, o.get("name").getAsString());
        }
        if (old && !DATA.isEmpty()) {
            dirty = true;
            com.aidan.skyserver.Skyserver.LOGGER.info("Levels: moved {} players to the new 1-99 level system (XP kept).", DATA.size());
            saveSoon();
        }
    }

    /** Level under the old curve (250 + 75 per level), used once when moving to the new system. */
    private static int oldLevelOf(long total) {
        int level = 1;
        long left = total;
        while (left >= 250 + 75L * (level - 1)) {
            left -= 250 + 75L * (level - 1);
            level++;
        }
        return level;
    }

    /** Writes levels.json in the background if anything changed (call from the main thread). */
    static void saveSoon() {
        if (!dirty) return;
        dirty = false;
        JsonObject root = new JsonObject(), players = new JsonObject();
        root.addProperty("version", 2);
        for (Map.Entry<UUID, long[]> e : DATA.entrySet()) {
            JsonObject o = new JsonObject();
            o.addProperty("xp", e.getValue()[0]);
            o.addProperty("paid", e.getValue()[1]);
            o.addProperty("paidP", e.getValue()[2]);
            String name = NAMES.get(e.getKey());
            if (name != null) o.addProperty("name", name);
            players.add(e.getKey().toString(), o);
        }
        root.add("players", players);
        PlayerCache.io(() -> Stacks.writeFile("levels.json", root));
    }
}
