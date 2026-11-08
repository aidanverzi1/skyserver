package com.aidan.skyserver.lobby;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetActionBarTextPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;

import java.time.LocalDate;
import java.util.*;

/**
 * The four obstacle courses (Easy, Medium, Hard, Extreme). Step on the green block to start the timer, light-blue platforms
 * are checkpoints, the gold block is the finish. Fall (touch the ground) and you go back to your
 * last checkpoint. Best times show on a leaderboard by each start. The first finish each day
 * gives a key and some money.
 */
public final class Parkour {
    /**
     * Rewards for the first finish each day, per course (easy, medium, hard, extreme). A Rare Key is
     * the most a course ever gives: Epic and Legendary keys stay hard to get.
     * 5.8: money is 70% of what it was (250 / 500 / 900 / 1,500).
     */
    public static final int[] REWARD_KEY_TIER = {0, 1, 1, 2};
    public static final long[] REWARD_MONEY = {175, 350, 630, 1_050};
    public static final int[] REWARD_XP = {50, 100, 180, 300};
    /** Best times are kept per course design (the courses were rebuilt, so old times don't count). */
    private static final String BEST_KEY = "best_v4";

    private static final class Run {
        final LobbyDesign.Course course;
        final long startTick;
        int checkpoint = 0;

        Run(LobbyDesign.Course course, long startTick) {
            this.course = course;
            this.startTick = startTick;
        }
    }

    private static final Map<UUID, Run> RUNS = new HashMap<>();
    private static final Map<Long, Object[]> STATIONS = new HashMap<>(); // block -> {course, station}
    // course id -> (player -> best ticks); names for the leaderboards; last reward day per course.
    private static final Map<String, Map<UUID, Integer>> BEST = new HashMap<>();
    private static final Map<UUID, String> NAMES = new HashMap<>();
    private static final Map<String, Map<UUID, String>> REWARDED = new HashMap<>();
    private static final Map<String, Holograms.Holo> BOARDS = new HashMap<>();
    private static boolean loaded = false;

    private Parkour() {}

    static void setup() {
        for (LobbyDesign.Course c : LobbyDesign.COURSES) {
            for (Map.Entry<Long, Integer> e : c.stationOf().entrySet()) STATIONS.put(e.getKey(), new Object[]{c, e.getValue()});
            int[] s = c.start();
            BOARDS.put(c.id(), Holograms.add(s[0] + 3.5, s[1] + 3.2, s[2] + 3.5, List.of("§e§lBEST TIMES", "§7nobody yet")));
        }
        load();
        refreshBoards();
    }

    private static void load() {
        if (loaded) return;
        loaded = true;
        JsonObject root = Stacks.readFile("parkour.json");
        if (root.has(BEST_KEY)) for (Map.Entry<String, JsonElement> c : root.getAsJsonObject(BEST_KEY).entrySet()) {
            Map<UUID, Integer> m = new HashMap<>();
            for (Map.Entry<String, JsonElement> e : c.getValue().getAsJsonObject().entrySet()) m.put(UUID.fromString(e.getKey()), e.getValue().getAsInt());
            BEST.put(c.getKey(), m);
        }
        if (root.has("names")) for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("names").entrySet()) NAMES.put(UUID.fromString(e.getKey()), e.getValue().getAsString());
        if (root.has("rewarded")) for (Map.Entry<String, JsonElement> c : root.getAsJsonObject("rewarded").entrySet()) {
            Map<UUID, String> m = new HashMap<>();
            for (Map.Entry<String, JsonElement> e : c.getValue().getAsJsonObject().entrySet()) m.put(UUID.fromString(e.getKey()), e.getValue().getAsString());
            REWARDED.put(c.getKey(), m);
        }
    }

    private static void save() {
        JsonObject root = new JsonObject(), best = new JsonObject(), names = new JsonObject(), rewarded = new JsonObject();
        for (Map.Entry<String, Map<UUID, Integer>> c : BEST.entrySet()) {
            JsonObject o = new JsonObject();
            c.getValue().forEach((k, v) -> o.addProperty(k.toString(), v));
            best.add(c.getKey(), o);
        }
        NAMES.forEach((k, v) -> names.addProperty(k.toString(), v));
        for (Map.Entry<String, Map<UUID, String>> c : REWARDED.entrySet()) {
            JsonObject o = new JsonObject();
            c.getValue().forEach((k, v) -> o.addProperty(k.toString(), v));
            rewarded.add(c.getKey(), o);
        }
        root.add(BEST_KEY, best);
        root.add("names", names);
        root.add("rewarded", rewarded);
        Stacks.writeFile("parkour.json", root);
    }

    /** Someone's best time on a course (ticks), or null. */
    public static Integer best(String courseId, java.util.UUID id) {
        Map<java.util.UUID, Integer> m = BEST.get(courseId);
        return m == null ? null : m.get(id);
    }

    static String time(int ticks) {
        int ms = ticks * 50;
        return String.format("%d:%02d.%02d", ms / 60000, (ms / 1000) % 60, (ms % 1000) / 10);
    }

    public static boolean running(ServerPlayer p) {
        return RUNS.containsKey(p.getUUID());
    }

    public static void cancel(ServerPlayer p, boolean tell) {
        if (RUNS.remove(p.getUUID()) != null) {
            if (tell) Lobby.msg(p, "§7You left the obstacle course.");
            Lobby.refreshKit(p);
        }
    }

    /** Back to the last checkpoint (the feather item, or after a fall). */
    public static void toCheckpoint(ServerPlayer p) {
        Run run = RUNS.get(p.getUUID());
        if (run == null) return;
        int[] st = run.course.stations().get(run.checkpoint);
        int[] next = run.checkpoint + 1 < run.course.stations().size() ? run.course.stations().get(run.checkpoint + 1) : st;
        float yaw = (float) Math.toDegrees(Math.atan2(-(next[0] - st[0]), next[2] - st[2]));
        p.teleportTo((ServerLevel) p.level(), st[0] + 0.5, st[1], st[2] + 0.5, Set.of(), yaw, 0f, true);
        p.resetFallDistance();
    }

    /** Teleports to a course start (from the game menu). */
    public static void teleportToStart(ServerPlayer p, String id) {
        for (LobbyDesign.Course c : LobbyDesign.COURSES) {
            if (!c.id().equals(id)) continue;
            cancel(p, false);
            int[] s = c.start();
            int[] next = c.jumps().get(1);
            float yaw = (float) Math.toDegrees(Math.atan2(-(next[0] - s[0]), next[2] - s[2]));
            p.teleportTo((ServerLevel) p.level(), s[0] + 0.5, s[1], s[2] + 0.5, Set.of(), yaw, 0f, true);
        }
    }

    /** Called every tick for each player in the lobby. */
    static void tick(ServerPlayer p, long now) {
        int bx = (int) Math.floor(p.getX()), by = (int) Math.floor(p.getY() - 0.2), bz = (int) Math.floor(p.getZ());
        Object[] st = p.onGround() ? STATIONS.get(LobbyCanvas.key(bx, by, bz)) : null;
        Run run = RUNS.get(p.getUUID());
        if (st != null) {
            LobbyDesign.Course c = (LobbyDesign.Course) st[0];
            int s = (int) st[1];
            int last = c.stations().size() - 1;
            if (s == 0) {
                // Standing on the start: the clock starts the moment they step off.
                boolean fresh = run == null || run.course != c || run.checkpoint > 0;
                RUNS.put(p.getUUID(), new Run(c, now));
                if (fresh) {
                    Lobby.msg(p, "§a" + c.name() + "! §7The timer starts when you jump. Light-blue platforms are checkpoints. §eFeather§7 = back to checkpoint, §cbarrier§7 = quit.");
                    p.level().playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.NOTE_BLOCK_PLING, SoundSource.PLAYERS, 1f, 1.5f);
                    Lobby.refreshKit(p);
                }
                return;
            }
            if (run != null && run.course == c && s > run.checkpoint) {
                if (s > run.checkpoint + 1) {
                    if (now % 40 == 0) Lobby.msg(p, "§cYou skipped a checkpoint! Go back to checkpoint " + (run.checkpoint + 1) + ".");
                } else if (s == last) {
                    finish(p, run, now);
                    return;
                } else {
                    run.checkpoint = s;
                    Lobby.msg(p, "§bCheckpoint " + s + "/" + (last - 1) + " §7(" + time((int) (now - run.startTick)) + ")");
                    p.level().playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.EXPERIENCE_ORB_PICKUP, SoundSource.PLAYERS, 1f, 1.2f);
                }
            }
        }
        run = RUNS.get(p.getUUID());
        if (run == null) return;
        // Fell: back to the checkpoint.
        if (p.getY() < run.course.failYFor(run.checkpoint) + 0.5) {
            toCheckpoint(p);
            return;
        }
        if (now % 2 == 0) {
            p.connection.send(new ClientboundSetActionBarTextPacket(Component.literal("§e" + run.course.name() + "  §f" + time((int) (now - run.startTick))
                    + "  §7checkpoint " + run.checkpoint + "/" + (run.course.stations().size() - 2))));
        }
    }

    private static void finish(ServerPlayer p, Run run, long now) {
        RUNS.remove(p.getUUID());
        int ticks = (int) (now - run.startTick);
        String id = run.course.id();
        Map<UUID, Integer> best = BEST.computeIfAbsent(id, k -> new HashMap<>());
        Integer old = best.get(p.getUUID());
        boolean record = old == null || ticks < old;
        if (record) best.put(p.getUUID(), ticks);
        NAMES.put(p.getUUID(), p.getName().getString());
        Lobby.msg(p, "§6§lFINISHED! §e" + run.course.name() + " in §f" + time(ticks) + (record && old != null ? " §a(new personal best!)" : old != null ? " §7(best " + time(old) + ")" : ""));
        p.level().playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 1f, 1f);
        Quests.progress(p, "parkour", 1);
        PlayerStats.add(p, "course_" + run.course.id(), 1);
        SkillHalls.courseFinished(p, run.course, ticks);
        // First finish today: key + money.
        String today = LocalDate.now().toString();
        Map<UUID, String> rewarded = REWARDED.computeIfAbsent(id, k -> new HashMap<>());
        int tier = run.course.tier();
        MinecraftServer server = p.level().getServer();
        if (!today.equals(rewarded.get(p.getUUID()))) {
            rewarded.put(p.getUUID(), today);
            Keys.reward(server, p.getUUID(), REWARD_KEY_TIER[tier], 1, "(first " + run.course.name() + " finish today)");
            Skills.add(p, Skills.Skill.PARKOUR, 2 * new int[]{120, 250, 450, 800}[tier]); // first finish today counts double
            Rewards.reward(server, p, REWARD_MONEY[tier], REWARD_XP[tier], "(first " + run.course.name() + " finish today)");
        } else {
            Levels.addXp(server, p, 10);
            Skills.add(p, Skills.Skill.PARKOUR, new int[]{120, 250, 450, 800}[tier]);
            Lobby.msg(p, "§7(You've already had today's key and money for this course. Come back tomorrow!)");
        }
        save();
        refreshBoards();
        Lobby.refreshKit(p);
    }

    static void refreshBoards() {
        for (LobbyDesign.Course c : LobbyDesign.COURSES) {
            Holograms.Holo h = BOARDS.get(c.id());
            if (h == null) continue;
            List<Map.Entry<UUID, Integer>> top = new ArrayList<>(BEST.getOrDefault(c.id(), Map.of()).entrySet());
            top.sort(Map.Entry.comparingByValue());
            List<String> lines = new ArrayList<>();
            lines.add("§e§lBEST TIMES §7- " + c.name());
            if (top.isEmpty()) lines.add("§7nobody yet");
            for (int i = 0; i < Math.min(5, top.size()); i++) {
                String name = NAMES.getOrDefault(top.get(i).getKey(), "?");
                lines.add("§6" + (i + 1) + ". §f" + name + " §7" + time(top.get(i).getValue()));
            }
            h.setLines(lines);
        }
    }
}
