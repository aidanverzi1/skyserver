package com.aidan.skyserver.lobby;

import com.aidan.skyserver.island.Islands;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;

import java.time.LocalDate;
import java.time.temporal.IsoFields;
import java.util.*;
import java.util.function.Function;

import static com.aidan.skyserver.lobby.LobbyDesign.G;

/**
 * The people of the Town and their side activities, plus the skill buildings' daily crates.
 *
 *  Daily crates: right-click the crate in a skill building (once a day): prizes spin past like the
 *  key chests and it stops on one. Small chance of a key: Common 2%, Uncommon 0.5%, Rare 0.1%.
 *  You also get $25 x your skill level (max $750) every time.
 *
 *  NPCs (right-click them):
 *   Farmer Hal (Farmers' Market)    - three daily orders: earn Farming XP, collect money
 *   Shepherd Mae (Shepherd's Fields) - sells spawn eggs; three daily Shepherd orders
 *   Old Gil the fisherman (pond)    - weekly biggest-fish contest (every pond fish is weighed)
 *   Smith Brann (Mine Head)         - upgrades your Mine pickaxe
 *   Sergeant Vex (Fighters' Guild)  - the Gauntlet: 5 duels in a row against harder and harder bots
 *   Curator Ivy (Builders' Gallery) - weekly island build contest: vote the theme, enter, rate
 *   Coach Pip (castle courtyard)    - a daily obstacle course time trial
 */
public final class SkillHalls {
    private static final String TAG = "skyserver_npc";
    private static final Random RNG = new Random();

    record Npc(String id, String name, double x, double y, double z, float yaw) {}

    record Crate(String area, int x, int y, int z) {}

    static final List<Npc> NPCS = new ArrayList<>();
    static final List<Crate> CRATES = new ArrayList<>();

    private SkillHalls() {}

    /** Where everyone stands (worked out from the Town layout). Called from TownDesign so the builder can leave room. */
    static void plan() {
        if (!NPCS.isEmpty()) return;
        for (String id : new String[]{"market", "guild", "gallery"}) {
            TownDesign.Area a = TownDesign.area(id);
            int cx = (a.inside().x1() + a.inside().x2()) / 2;
            CRATES.add(new Crate(id, cx, G + 1, 79));
            String[] who = switch (id) {
                case "market" -> new String[]{"farmer", "§aFarmer Hal"};
                case "guild" -> new String[]{"sergeant", "§cSergeant Vex"};
                default -> new String[]{"curator", "§6Curator Ivy"};
            };
            NPCS.add(new Npc(who[0], who[1], cx + 4.5, G + 1, 80.5, 180f));
        }
        CRATES.add(new Crate("fields", 167, G + 1, 112));
        NPCS.add(new Npc("shepherd", "§fShepherd Mae", 171.5, G + 1, 110.5, 180f));
        NPCS.add(new Npc("fisher", "§bOld Gil", ActivityDesign.POND_SPOT.x() + 4, G + 1, ActivityDesign.POND_SPOT.z() - 1, 180f));
        NPCS.add(new Npc("smith", "§7Smith Brann", 224.5, G + 1, 103.5, 90f));
        NPCS.add(new Npc("coach", "§dCoach Pip", LobbyDesign.SPAWN[0] + 6, G + 1, LobbyDesign.SPAWN[2] + 2, 90f));
    }

    static boolean isNpcSpot(int x, int z) {
        plan();
        for (Npc n : NPCS) if ((int) Math.floor(n.x()) == x && (int) Math.floor(n.z()) == z) return true;
        return false;
    }

    public static void init() {
        plan();
        CommandRegistrationCallback.EVENT.register((d, r, e) -> {
            d.register(Commands.literal("ssnpc")
                    .then(Commands.argument("who", StringArgumentType.word())
                            .then(Commands.argument("what", StringArgumentType.word())
                                    .executes(c -> { action(c.getSource().getPlayerOrException(), StringArgumentType.getString(c, "who"), StringArgumentType.getString(c, "what"), 0); return 1; })
                                    .then(Commands.argument("n", IntegerArgumentType.integer(0, 99))
                                            .executes(c -> { action(c.getSource().getPlayerOrException(), StringArgumentType.getString(c, "who"),
                                                    StringArgumentType.getString(c, "what"), IntegerArgumentType.getInteger(c, "n")); return 1; })))));
            d.register(Commands.literal("build").executes(c -> { talk(c.getSource().getPlayerOrException(), "curator"); return 1; })
                    .then(Commands.literal("submit").executes(c -> { buildSubmit(c.getSource().getPlayerOrException()); return 1; }))
                    .then(Commands.literal("rate").then(Commands.argument("stars", IntegerArgumentType.integer(1, 5))
                            .executes(c -> { buildRate(c.getSource().getPlayerOrException(), IntegerArgumentType.getInteger(c, "stars")); return 1; })))
                    .then(Commands.literal("entries").executes(c -> { buildEntries(c.getSource().getPlayerOrException()); return 1; })));
            d.register(Commands.literal("fishcontest").executes(c -> { talk(c.getSource().getPlayerOrException(), "fisher"); return 1; }));
        });
    }

    // =====================================================================
    // NPC upkeep
    // =====================================================================

    static void tick(ServerLevel lobby, long ticks) {
        if (ticks % 200 == 37) {
            for (Npc n : NPCS) ensure(lobby, n);
            weekly(lobby.getServer());
        }
    }

    private static boolean isNpc(Entity e, String id) {
        return e.entityTags().contains(TAG) && e.entityTags().contains(TAG + ":" + id);
    }

    private static void ensure(ServerLevel lobby, Npc n) {
        BlockPos at = BlockPos.containing(n.x(), n.y(), n.z());
        if (!lobby.isLoaded(at)) return;
        AABB box = new AABB(n.x() - 40, n.y() - 20, n.z() - 40, n.x() + 40, n.y() + 20, n.z() + 40);
        List<Villager> found = lobby.getEntitiesOfClass(Villager.class, box, v -> isNpc(v, n.id()));
        for (int i = 1; i < found.size(); i++) found.get(i).discard();
        if (!found.isEmpty()) {
            Villager v = found.get(0);
            if (v.distanceToSqr(n.x(), n.y(), n.z()) > 1) v.snapTo(n.x(), n.y(), n.z(), n.yaw(), 0f);
            return;
        }
        Villager v = net.minecraft.world.entity.EntityTypes.VILLAGER.create(lobby, EntitySpawnReason.COMMAND);
        if (v == null) return;
        v.snapTo(n.x(), n.y(), n.z(), n.yaw(), 0f);
        v.setNoAi(true);
        v.setPermanentlyInvulnerable(true);
        v.setSilent(true);
        v.setPersistenceRequired();
        v.setCustomName(Component.literal(n.name() + " §7(right-click)"));
        v.setCustomNameVisible(true);
        v.addTag(TAG);
        v.addTag(TAG + ":" + n.id());
        lobby.addFreshEntity(v);
    }

    /** Right-clicked an entity in the lobby. True if it was one of ours. */
    public static boolean interact(ServerPlayer p, Entity e) {
        if (!e.entityTags().contains(TAG)) return false;
        for (Npc n : NPCS) if (isNpc(e, n.id())) {
            talk(p, n.id());
            return true;
        }
        return true;
    }

    // ---- Chat helpers ----

    private static void say(ServerPlayer p, String who, String text) {
        Lobby.msg(p, who + "§7: §f" + text);
    }

    private static MutableComponent button(String label, String command, String hover) {
        return Component.literal(label).withStyle(Style.EMPTY.withClickEvent(new ClickEvent.RunCommand(command))
                .withHoverEvent(new HoverEvent.ShowText(Component.literal(hover))));
    }

    private static Npc npc(String id) {
        for (Npc n : NPCS) if (n.id().equals(id)) return n;
        return null;
    }

    static void talk(ServerPlayer p, String id) {
        Npc n = npc(id);
        if (n == null) return;
        switch (id) {
            case "farmer" -> orders(p, n, "farmer", Skills.Skill.FARMING, "Harvest grown crops on your island");
            case "shepherd" -> {
                orders(p, n, "shepherd", Skills.Skill.SHEPHERD, "Shear, breed and milk animals on your island");
                MutableComponent shop = Component.literal("§fSpawn eggs §7(sent to your island): ");
                int lvl = Skills.level(p.getUUID(), Skills.Skill.SHEPHERD);
                for (int i = 0; i < EGGS.length; i++) {
                    if (lvl < EGG_LEVEL[i]) continue;
                    shop.append(button("§a[" + EGG_NAMES[i] + " " + Economy.format(EGG_PRICES[i]) + "] ", "/ssnpc shepherd egg " + i, "Buy one " + EGG_NAMES[i] + " spawn egg"));
                }
                p.sendSystemMessage(shop);
            }
            case "fisher" -> fisher(p, n);
            case "smith" -> smith(p, n);
            case "sergeant" -> sergeant(p, n);
            case "curator" -> curator(p, n);
            case "coach" -> coach(p, n);
            default -> {}
        }
    }

    static void action(ServerPlayer p, String who, String what, int n) {
        switch (who + ":" + what) {
            case "farmer:claim" -> claimOrder(p, "farmer", Skills.Skill.FARMING, n);
            case "shepherd:claim" -> claimOrder(p, "shepherd", Skills.Skill.SHEPHERD, n);
            case "shepherd:egg" -> buyEgg(p, n);
            case "smith:upgrade" -> Lobby.msg(p, Mine.buyUpgrade(p));
            case "sergeant:start" -> gauntletStart(p);
            case "curator:vote" -> buildVote(p, n);
            case "coach:go" -> {
                Trial t = trial(p);
                Parkour.teleportToStart(p, t.course().id());
            }
            default -> {}
        }
    }

    // =====================================================================
    // Daily crates
    // =====================================================================

    private static final Map<String, String> OPENED = new HashMap<>(); // "area:uuid" -> day
    private static boolean cratesLoaded = false;

    public static String crateAt(BlockPos pos) {
        for (Crate c : CRATES) if (c.x() == pos.getX() && c.y() == pos.getY() && c.z() == pos.getZ()) return c.area();
        return null;
    }

    private static void loadCrates() {
        if (cratesLoaded) return;
        cratesLoaded = true;
        JsonObject o = Stacks.readFile("daily-crates.json");
        String today = LocalDate.now().toString();
        for (Map.Entry<String, JsonElement> e : o.entrySet()) if (today.equals(e.getValue().getAsString())) OPENED.put(e.getKey(), today);
    }

    private static void saveCrates() {
        JsonObject o = new JsonObject();
        String today = LocalDate.now().toString();
        OPENED.forEach((k, v) -> { if (today.equals(v)) o.addProperty(k, v); });
        Stacks.writeFile("daily-crates.json", o);
    }

    public static void openCrate(ServerPlayer p, String areaId) {
        TownDesign.Area a = TownDesign.area(areaId);
        if (a == null) return;
        Skills.Skill s = Skills.Skill.valueOf(a.skill());
        int lvl = Skills.level(p.getUUID(), s);
        List<Loot.Prize> pool = table(areaId, lvl);
        loadCrates();
        String key = areaId + ":" + p.getUUID(), today = LocalDate.now().toString();
        if (today.equals(OPENED.get(key))) {
            Crates.previewList(p, a.color() + a.name() + " crate", pool, "§7Opened today - come back tomorrow!");
            return;
        }
        OPENED.put(key, today);
        saveCrates();
        long money = Math.min(750, 25L * lvl); // 5.8: was $50 x level with no cap
        Economy.add(p.level().getServer(), p.getUUID(), p.getName().getString(), money);
        Lobby.msg(p, a.color() + "§l" + a.name() + " crate! §a+" + Economy.format(money) + " §7(" + s.title + " level " + lvl + ") - and a prize:");
        PlayerStats.add(p, "daily_crate", 1);
        Crates.spin(p, a.color() + a.name() + " crate", a.color(), pool);
    }

    private static Loot.Prize item(int w, int count, Item item) {
        String label = count + "x " + new ItemStack(item).getHoverName().getString();
        return new Loot.Prize(w, Loot.Kind.ITEM, label, 0, 0, 0, s -> new ItemStack(item, count), null);
    }

    private static Loot.Prize key(int w, int tier) {
        return new Loot.Prize(w, Loot.Kind.KEYS, Keys.COLORS[tier] + "1 " + Keys.NAMES[tier] + " Key", 0, tier, 1, null, Items.TRIPWIRE_HOOK);
    }

    /** One building's prizes at a skill level. Weights add up to 1000: keys 20 / 5 / 1 (2%, 0.5%, 0.1%). */
    static List<Loot.Prize> table(String areaId, int lvl) {
        List<Loot.Prize> t = new ArrayList<>();
        int spare = 0; // weight of prizes not unlocked yet goes to the first prize
        switch (areaId) {
            case "market" -> {
                t.add(item(250, 16 + lvl, Items.BONE_MEAL));
                t.add(item(120, 8, Items.CARROT));
                t.add(item(120, 8, Items.POTATO));
                t.add(item(100, 8, Items.SUGAR_CANE));
                t.add(item(100, 8, Items.PUMPKIN_SEEDS));
                t.add(item(100, 8, Items.MELON_SEEDS));
                if (lvl >= 15) { t.add(item(40, 8, Items.COCOA_BEANS)); t.add(item(40, 8, Items.GLOW_BERRIES)); } else spare += 80;
                if (lvl >= 25) { t.add(item(30, 4, Items.NETHER_WART)); t.add(item(20, 2, Items.TORCHFLOWER_SEEDS)); } else spare += 50;
                if (lvl >= 40) t.add(item(24, 8, Items.GOLDEN_CARROT)); else spare += 24;
            }
            case "fields" -> {
                t.add(item(240, 16, Items.WHEAT));
                t.add(item(10, 1, Items.SNIFFER_EGG)); // 5.18: 1% sniffer egg
                t.add(item(140, 1, Items.SHEEP_SPAWN_EGG));
                t.add(item(140, 1, Items.COW_SPAWN_EGG));
                t.add(item(120, 1, Items.CHICKEN_SPAWN_EGG));
                t.add(item(100, 1, Items.PIG_SPAWN_EGG));
                if (lvl >= 10) t.add(item(80, 1, Items.SHEARS)); else spare += 80;
                if (lvl >= 15) { t.add(item(50, 1, Items.RABBIT_SPAWN_EGG)); t.add(item(40, 1, Items.GOAT_SPAWN_EGG)); } else spare += 90;
                if (lvl >= 30) { t.add(item(30, 1, Items.HORSE_SPAWN_EGG)); t.add(item(24, 1, Items.MOOSHROOM_SPAWN_EGG)); } else spare += 54;
            }
            case "guild" -> {
                t.add(item(250, 16 + lvl, Items.ARROW));
                t.add(item(170, 8, Items.COOKED_BEEF));
                t.add(item(120, 1, Items.IRON_SWORD));
                t.add(item(120, 1, Items.BOW));
                t.add(item(90, 1, Items.SHIELD));
                if (lvl >= 15) t.add(item(80, 1 + lvl / 20, Items.GOLDEN_APPLE)); else spare += 80;
                if (lvl >= 25) { t.add(item(50, 1, Items.IRON_CHESTPLATE)); t.add(item(40, 1, Items.IRON_LEGGINGS)); } else spare += 90;
                if (lvl >= 40) { t.add(item(30, 1, Items.DIAMOND_SWORD)); t.add(item(24, 1, Items.TOTEM_OF_UNDYING)); } else spare += 54;
            }
            default -> { // gallery
                t.add(item(200, 64, Items.CHERRY_PLANKS));
                t.add(item(130, 64, Items.MUD_BRICKS));
                t.add(item(130, 64, Items.QUARTZ_BRICKS));
                t.add(item(120, 64, Items.DEEPSLATE_TILES));
                t.add(item(100, 32, Items.GLASS));
                t.add(item(80, 8, Items.LANTERN));
                if (lvl >= 20) t.add(item(80, 8, Items.SEA_LANTERN)); else spare += 80;
                if (lvl >= 35) { t.add(item(64, 16, Items.AMETHYST_BLOCK)); t.add(item(70, 16, Items.SHROOMLIGHT)); } else spare += 134;
            }
        }
        if (spare > 0) {
            Loot.Prize first = t.get(0);
            t.set(0, new Loot.Prize(first.weight() + spare, first.kind(), first.label(), first.money(), first.keyTier(), first.keyCount(), first.item(), first.icon()));
        }
        t.add(key(20, 0));
        t.add(key(5, 1));
        t.add(key(1, 2));
        return t;
    }

    // =====================================================================
    // Farmer Hal and Shepherd Mae: daily orders
    // =====================================================================

    static final int[][] ORDER_XP = {{400, 1200, 2500}, {300, 900, 2000}};   // farmer, shepherd
    static final long[] ORDER_PAY = {300, 700, 1500};
    private static final Map<String, String> CLAIMED = new HashMap<>(); // "who:n:uuid" -> day

    private static void orders(ServerPlayer p, Npc n, String who, Skills.Skill s, String how) {
        int idx = who.equals("farmer") ? 0 : 1;
        long have = Skills.xpToday(p.getUUID(), s);
        say(p, n.name(), who.equals("farmer") ? "Got three orders for today. Grow 'em on your island and come back!" : "My flock needs tending! Three jobs for today:");
        String today = LocalDate.now().toString();
        for (int i = 0; i < 3; i++) {
            boolean done = today.equals(CLAIMED.get(who + ":" + i + ":" + p.getUUID()));
            long need = ORDER_XP[idx][i];
            MutableComponent line = Component.literal(" §7" + (i + 1) + ". Earn §f" + need + " " + s.title + " XP §7today §8(" + Math.min(have, need) + "/" + need + ") §6" + Economy.format(ORDER_PAY[i]) + " ");
            if (done) line.append(Component.literal("§a✔ done"));
            else if (have >= need) line.append(button("§a§l[Claim]", "/ssnpc " + who + " claim " + i, "Collect " + Economy.format(ORDER_PAY[i])));
            else line.append(Component.literal("§8(" + how.toLowerCase() + ")"));
            p.sendSystemMessage(line);
        }
    }

    private static void claimOrder(ServerPlayer p, String who, Skills.Skill s, int i) {
        if (i < 0 || i > 2) return;
        int idx = who.equals("farmer") ? 0 : 1;
        String key = who + ":" + i + ":" + p.getUUID(), today = LocalDate.now().toString();
        if (today.equals(CLAIMED.get(key))) return;
        if (Skills.xpToday(p.getUUID(), s) < ORDER_XP[idx][i]) {
            Lobby.msg(p, "§cNot done yet.");
            return;
        }
        CLAIMED.put(key, today);
        Economy.add(p.level().getServer(), p.getUUID(), p.getName().getString(), ORDER_PAY[i]);
        Lobby.msg(p, "§aOrder done! §6+" + Economy.format(ORDER_PAY[i]));
        Quests.progress(p, "npc_order", 1);
    }

    static final Item[] EGGS = {Items.SHEEP_SPAWN_EGG, Items.COW_SPAWN_EGG, Items.CHICKEN_SPAWN_EGG, Items.PIG_SPAWN_EGG,
            Items.RABBIT_SPAWN_EGG, Items.GOAT_SPAWN_EGG, Items.HORSE_SPAWN_EGG, Items.MOOSHROOM_SPAWN_EGG};
    static final String[] EGG_NAMES = {"Sheep", "Cow", "Chicken", "Pig", "Rabbit", "Goat", "Horse", "Mooshroom"};
    static final long[] EGG_PRICES = {400, 400, 250, 350, 500, 700, 2_000, 3_500};
    static final int[] EGG_LEVEL = {0, 0, 0, 0, 10, 15, 25, 35};

    private static void buyEgg(ServerPlayer p, int i) {
        if (i < 0 || i >= EGGS.length) return;
        if (Skills.level(p.getUUID(), Skills.Skill.SHEPHERD) < EGG_LEVEL[i]) {
            Lobby.msg(p, "§cThat egg needs Shepherd level " + EGG_LEVEL[i] + ".");
            return;
        }
        if (!Economy.take(p.level().getServer(), p.getUUID(), EGG_PRICES[i])) {
            Lobby.msg(p, "§cThat costs " + Economy.format(EGG_PRICES[i]) + ".");
            return;
        }
        Mailbox.send(p.level().getServer(), p.getUUID(), new ItemStack(EGGS[i]));
        Lobby.msg(p, "§aBought a " + EGG_NAMES[i] + " Spawn Egg §7(delivered on your island: §e/is§7)");
    }

    // =====================================================================
    // Old Gil: weekly biggest-fish contest
    // =====================================================================

    private static final Map<String, double[]> FISH_RANGE = Map.of(
            "cod", new double[]{2, 12}, "salmon", new double[]{3, 20}, "tropical_fish", new double[]{0.3, 2.5}, "pufferfish", new double[]{1, 6});
    private static final Map<UUID, Object[]> FISH_BEST = new HashMap<>(); // uuid -> {weight, fish, name}
    private static String fishWeek = null;
    private static boolean fishLoaded = false;

    private static String week() {
        LocalDate d = LocalDate.now();
        return d.get(IsoFields.WEEK_BASED_YEAR) + "-W" + d.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR);
    }

    private static void loadFish() {
        if (fishLoaded) return;
        fishLoaded = true;
        JsonObject o = Stacks.readFile("fish-contest.json");
        fishWeek = o.has("week") ? o.get("week").getAsString() : week();
        if (o.has("best")) for (Map.Entry<String, JsonElement> e : o.getAsJsonObject("best").entrySet()) {
            JsonArray a = e.getValue().getAsJsonArray();
            FISH_BEST.put(UUID.fromString(e.getKey()), new Object[]{a.get(0).getAsDouble(), a.get(1).getAsString(), a.get(2).getAsString()});
        }
    }

    private static void saveFish() {
        JsonObject o = new JsonObject();
        o.addProperty("week", fishWeek);
        JsonObject b = new JsonObject();
        FISH_BEST.forEach((k, v) -> {
            JsonArray a = new JsonArray();
            a.add((Double) v[0]);
            a.add((String) v[1]);
            a.add((String) v[2]);
            b.add(k.toString(), a);
        });
        o.add("best", b);
        Stacks.writeFile("fish-contest.json", o);
    }

    /** A fish caught at the lobby pond: weigh it. Returns text for the catch message. */
    static String weigh(ServerPlayer p, String fishId, String fishName) {
        double[] r = FISH_RANGE.get(fishId);
        if (r == null) return "";
        loadFish();
        double roll = RNG.nextDouble();
        double w = r[0] + (r[1] - r[0]) * roll * roll;          // most fish are small
        boolean trophy = RNG.nextInt(40) == 0;
        if (trophy) w *= 1.5;
        w = Math.round(w * 10) / 10.0;
        Object[] best = FISH_BEST.get(p.getUUID());
        String extra = "";
        if (best == null || w > (Double) best[0]) {
            FISH_BEST.put(p.getUUID(), new Object[]{w, fishName, p.getName().getString()});
            saveFish();
            extra = " §e(your best this week!)";
        }
        return " §f" + w + " lb" + (trophy ? " §6§lTROPHY!" : "") + extra;
    }

    private static List<Map.Entry<UUID, Object[]>> fishTop() {
        List<Map.Entry<UUID, Object[]>> l = new ArrayList<>(FISH_BEST.entrySet());
        l.sort((a, b) -> Double.compare((Double) b.getValue()[0], (Double) a.getValue()[0]));
        return l;
    }

    private static void fisher(ServerPlayer p, Npc n) {
        loadFish();
        say(p, n.name(), "Biggest fish of the week wins! Every fish you catch here gets weighed. Contest ends Sunday night.");
        Lobby.msg(p, " §7Prizes: §61st Rare Key + $3,000 §7| §f2nd Uncommon Key + $1,500 §7| §c3rd Common Key + $750");
        List<Map.Entry<UUID, Object[]>> top = fishTop();
        if (top.isEmpty()) Lobby.msg(p, " §7No fish weighed yet this week.");
        for (int i = 0; i < Math.min(5, top.size()); i++) {
            Object[] v = top.get(i).getValue();
            Lobby.msg(p, " §e" + (i + 1) + ". §f" + v[2] + " §7- " + v[0] + " lb " + v[1]);
        }
        Object[] mine = FISH_BEST.get(p.getUUID());
        Lobby.msg(p, " §7Your best: " + (mine == null ? "none yet - grab a rod by the water!" : "§f" + mine[0] + " lb " + mine[1]));
    }

    // =====================================================================
    // Smith Brann: Mine pickaxe upgrades
    // =====================================================================

    private static void smith(ServerPlayer p, Npc n) {
        int up = Mine.upgrade(p.getUUID());
        say(p, n.name(), up >= 3 ? "That pick's as good as I can make it. Swing it with pride!" : "I can make that pick of yours sharper. Faster mining, and now and then an extra lump of coal, iron or copper.");
        for (int i = 1; i <= 3; i++) {
            MutableComponent line = Component.literal(" §7Upgrade " + Mine.UPGRADE_NAMES[i] + ": §fEfficiency " + Mine.UPGRADE_EFFICIENCY[i]
                    + ", +" + Mine.UPGRADE_BONUS[i] + "% extra §7- needs Mining " + Mine.UPGRADE_LEVEL[i] + ", §6" + Economy.format(Mine.UPGRADE_PRICE[i]) + " ");
            if (i <= up) line.append(Component.literal("§a✔"));
            else if (i == up + 1) line.append(button("§a§l[Upgrade]", "/ssnpc smith upgrade", "Buy upgrade " + Mine.UPGRADE_NAMES[i]));
            p.sendSystemMessage(line);
        }
    }

    // =====================================================================
    // Sergeant Vex: the Gauntlet
    // =====================================================================

    static final com.aidan.skyserver.bots.Difficulty[] WAVES = {com.aidan.skyserver.bots.Difficulty.EASY, com.aidan.skyserver.bots.Difficulty.MEDIUM,
            com.aidan.skyserver.bots.Difficulty.MEDIUM, com.aidan.skyserver.bots.Difficulty.HARD, com.aidan.skyserver.bots.Difficulty.HARD};
    private static final Map<UUID, Integer> GAUNTLET = new HashMap<>(); // player -> wave they're on (0-based)

    private static void sergeant(ServerPlayer p, Npc n) {
        say(p, n.name(), "Think you're tough? The Gauntlet: five duels back to back - easy, medium, medium, hard, hard. Lose once and it's over.");
        Lobby.msg(p, " §7Each wave you clear pays §6$100 x the wave §7(first time each day). Clear all five for a §fCommon Key§7.");
        p.sendSystemMessage(button("§c§l[Start the Gauntlet]", "/ssnpc sergeant start", "Fight wave 1 now"));
    }

    private static void gauntletStart(ServerPlayer p) {
        if (GAUNTLET.containsKey(p.getUUID()) || Duels.inDuel(p.getUUID())) return;
        if (!Duels.botDuel(p, WAVES[0])) return;
        GAUNTLET.put(p.getUUID(), 0);
        Announce.title(p, "§c§lTHE GAUNTLET", "§7Wave 1 of " + WAVES.length, 5, 40, 10);
    }

    /** Called by Duels when a duel ends. */
    static void duelOver(ServerPlayer winner, ServerPlayer loser) {
        ServerPlayer human = winner != null && GAUNTLET.containsKey(winner.getUUID()) ? winner : loser != null && GAUNTLET.containsKey(loser.getUUID()) ? loser : null;
        if (human == null) return;
        int wave = GAUNTLET.get(human.getUUID());
        if (human != winner) {
            GAUNTLET.remove(human.getUUID());
            Lobby.msg(human, "§cThe Gauntlet is over: you cleared §f" + wave + "§c of " + WAVES.length + " waves.");
            return;
        }
        String today = LocalDate.now().toString();
        String key = "gauntlet:" + (wave + 1) + ":" + human.getUUID();
        MinecraftServer server = human.level().getServer();
        if (!today.equals(CLAIMED.get(key))) {
            CLAIMED.put(key, today);
            Economy.add(server, human.getUUID(), human.getName().getString(), 100L * (wave + 1));
            Lobby.msg(human, "§aWave " + (wave + 1) + " cleared! §6+" + Economy.format(100L * (wave + 1)));
        }
        if (wave + 1 >= WAVES.length) {
            GAUNTLET.remove(human.getUUID());
            Announce.title(human, "§6§lGAUNTLET CLEARED!", "§7All " + WAVES.length + " waves", 5, 60, 10);
            if (!today.equals(CLAIMED.get("gauntlet:all:" + human.getUUID()))) {
                CLAIMED.put("gauntlet:all:" + human.getUUID(), today);
                Keys.reward(server, human.getUUID(), 0, 1, "(cleared the Gauntlet)");
            }
            PlayerStats.add(human, "gauntlet_clear", 1);
            return;
        }
        GAUNTLET.put(human.getUUID(), wave + 1);
        Lobby.msg(human, "§7Next: wave §f" + (wave + 2) + " §7(" + WAVES[wave + 1].name().toLowerCase() + " bot) in 3 seconds...");
        UUID id = human.getUUID();
        int next = wave + 1;
        Lobby.runLaterTicks(60, () -> {
            ServerPlayer h = server.getPlayerList().getPlayer(id);
            if (h == null || !GAUNTLET.containsKey(id)) return;
            if (!Duels.botDuel(h, WAVES[next])) GAUNTLET.remove(id);
            else Announce.title(h, "§c§lWAVE " + (next + 1), "§7" + WAVES[next].name().toLowerCase() + " bot", 5, 30, 10);
        });
    }

    // =====================================================================
    // Curator Ivy: weekly build contest
    // =====================================================================

    static final String[] THEMES = {"Floating Castle", "Treehouse", "Pirate Ship", "Ancient Temple", "Cozy Cottage", "Steampunk Factory",
            "Underwater Base", "Dragon's Lair", "Market Square", "Lighthouse", "Japanese Garden", "Wizard Tower", "Train Station", "Volcano Lab", "Winter Village"};
    private static String buildWeek = null, theme = null;
    private static final List<String> NEXT_OPTIONS = new ArrayList<>();
    private static final Map<UUID, Integer> THEME_VOTES = new HashMap<>();
    private static final Map<UUID, String> ENTRY_NAMES = new HashMap<>();            // island owner -> name
    private static final Map<UUID, Map<UUID, Integer>> RATINGS = new HashMap<>();   // owner -> rater -> stars
    private static boolean buildLoaded = false;

    private static void loadBuild() {
        if (buildLoaded) return;
        buildLoaded = true;
        JsonObject o = Stacks.readFile("build-contest.json");
        buildWeek = o.has("week") ? o.get("week").getAsString() : week();
        theme = o.has("theme") ? o.get("theme").getAsString() : THEMES[RNG.nextInt(THEMES.length)];
        if (o.has("options")) for (JsonElement e : o.getAsJsonArray("options")) NEXT_OPTIONS.add(e.getAsString());
        if (NEXT_OPTIONS.isEmpty()) pickOptions();
        if (o.has("votes")) for (Map.Entry<String, JsonElement> e : o.getAsJsonObject("votes").entrySet()) THEME_VOTES.put(UUID.fromString(e.getKey()), e.getValue().getAsInt());
        if (o.has("entries")) for (Map.Entry<String, JsonElement> e : o.getAsJsonObject("entries").entrySet()) ENTRY_NAMES.put(UUID.fromString(e.getKey()), e.getValue().getAsString());
        if (o.has("ratings")) for (Map.Entry<String, JsonElement> e : o.getAsJsonObject("ratings").entrySet()) {
            Map<UUID, Integer> m = new HashMap<>();
            for (Map.Entry<String, JsonElement> r : e.getValue().getAsJsonObject().entrySet()) m.put(UUID.fromString(r.getKey()), r.getValue().getAsInt());
            RATINGS.put(UUID.fromString(e.getKey()), m);
        }
        saveBuild();
    }

    private static void pickOptions() {
        NEXT_OPTIONS.clear();
        List<String> all = new ArrayList<>(Arrays.asList(THEMES));
        all.remove(theme);
        Collections.shuffle(all, RNG);
        NEXT_OPTIONS.addAll(all.subList(0, 3));
    }

    private static void saveBuild() {
        JsonObject o = new JsonObject();
        o.addProperty("week", buildWeek);
        o.addProperty("theme", theme);
        JsonArray opts = new JsonArray();
        NEXT_OPTIONS.forEach(opts::add);
        o.add("options", opts);
        JsonObject v = new JsonObject();
        THEME_VOTES.forEach((k, x) -> v.addProperty(k.toString(), x));
        o.add("votes", v);
        JsonObject en = new JsonObject();
        ENTRY_NAMES.forEach((k, x) -> en.addProperty(k.toString(), x));
        o.add("entries", en);
        JsonObject ra = new JsonObject();
        RATINGS.forEach((k, m) -> {
            JsonObject r = new JsonObject();
            m.forEach((u, s) -> r.addProperty(u.toString(), s));
            ra.add(k.toString(), r);
        });
        o.add("ratings", ra);
        Stacks.writeFile("build-contest.json", o);
    }

    private static void curator(ServerPlayer p, Npc n) {
        loadBuild();
        say(p, n.name(), "This week's build contest theme is §e§l" + theme + "§f! Build it on your island, stand by it and type §e/build submit§f.");
        Lobby.msg(p, " §7Everyone else rates entries 1-5 stars: §e/build entries §7to visit, §e/build rate <1-5> §7while standing on one.");
        Lobby.msg(p, " §7Prizes (best average, 2+ ratings): §61st Rare Key + $4,000 §7| §f2nd Uncommon + $2,000 §7| §c3rd Common + $1,000");
        MutableComponent vote = Component.literal(" §7Vote next week's theme: ");
        int[] c = new int[NEXT_OPTIONS.size()];
        for (int v : THEME_VOTES.values()) if (v >= 0 && v < c.length) c[v]++;
        for (int i = 0; i < NEXT_OPTIONS.size(); i++)
            vote.append(button("§b[" + NEXT_OPTIONS.get(i) + " " + c[i] + "] ", "/ssnpc curator vote " + i, "Vote for " + NEXT_OPTIONS.get(i)));
        p.sendSystemMessage(vote);
    }

    private static void buildVote(ServerPlayer p, int i) {
        loadBuild();
        if (i < 0 || i >= NEXT_OPTIONS.size()) return;
        THEME_VOTES.put(p.getUUID(), i);
        saveBuild();
        Lobby.msg(p, "§aVoted for §f" + NEXT_OPTIONS.get(i) + " §afor next week's theme.");
    }

    static void buildSubmit(ServerPlayer p) {
        loadBuild();
        Islands.Island is = Islands.islandOf(p.getUUID());
        if (is == null || !Islands.isIsland(p.level()) || Islands.islandAt(p.getX(), p.getZ()) != is) {
            Lobby.msg(p, "§cStand on your own island, next to your build, then type §e/build submit§c.");
            return;
        }
        is.home = new double[]{p.getX(), p.getY(), p.getZ(), p.getYRot()};
        is.open = true;
        Islands.save();
        ENTRY_NAMES.put(is.owner, is.ownerName);
        saveBuild();
        Lobby.msg(p, "§aEntered §f" + is.name() + " §ain this week's contest (§e" + theme + "§a)! §7Your island home was set here and visitors can now come in without asking.");
    }

    static void buildEntries(ServerPlayer p) {
        loadBuild();
        if (ENTRY_NAMES.isEmpty()) {
            Lobby.msg(p, "§7No entries yet this week (theme: §e" + theme + "§7).");
            return;
        }
        MutableComponent line = Component.literal("§6Contest entries (" + theme + "): ");
        for (Map.Entry<UUID, String> e : ENTRY_NAMES.entrySet())
            line.append(button("§b[" + e.getValue() + "] ", "/is visit " + e.getValue(), "Visit " + e.getValue() + "'s island, then /build rate <1-5>"));
        p.sendSystemMessage(line);
    }

    static void buildRate(ServerPlayer p, int stars) {
        loadBuild();
        Islands.Island is = Islands.isIsland(p.level()) ? Islands.islandAt(p.getX(), p.getZ()) : null;
        if (is == null || !ENTRY_NAMES.containsKey(is.owner)) {
            Lobby.msg(p, "§cStand on an island that's in the contest (§e/build entries§c).");
            return;
        }
        if (is.isMember(p.getUUID())) {
            Lobby.msg(p, "§cYou can't rate your own island!");
            return;
        }
        RATINGS.computeIfAbsent(is.owner, k -> new HashMap<>()).put(p.getUUID(), stars);
        saveBuild();
        Lobby.msg(p, "§aYou gave " + is.name() + " §e" + "★".repeat(stars) + "§7" + "☆".repeat(5 - stars));
    }

    // =====================================================================
    // Weekly results (fish contest + build contest)
    // =====================================================================

    private static void weekly(MinecraftServer server) {
        String now = week();
        loadFish();
        if (!now.equals(fishWeek)) {
            List<Map.Entry<UUID, Object[]>> top = fishTop();
            if (!top.isEmpty()) Events.broadcast(server, "§b§l🎣 FISHING CONTEST RESULTS §7(biggest fish of the week)");
            long[] pay = {3_000, 1_500, 750};
            for (int i = 0; i < Math.min(3, top.size()); i++) {
                UUID id = top.get(i).getKey();
                Object[] v = top.get(i).getValue();
                Events.broadcast(server, " §e" + (i + 1) + ". §f" + v[2] + " §7- " + v[0] + " lb " + v[1]);
                Economy.add(server, id, (String) v[2], pay[i]);
                Keys.reward(server, id, 2 - i, 1, "(fishing contest " + (i + 1) + (i == 0 ? "st" : i == 1 ? "nd" : "rd") + ")");
            }
            FISH_BEST.clear();
            fishWeek = now;
            saveFish();
        }
        loadBuild();
        if (!now.equals(buildWeek)) {
            List<Object[]> scored = new ArrayList<>(); // {owner, avg, name}
            for (Map.Entry<UUID, String> e : ENTRY_NAMES.entrySet()) {
                Map<UUID, Integer> r = RATINGS.getOrDefault(e.getKey(), Map.of());
                if (r.size() < 2) continue;
                double avg = r.values().stream().mapToInt(Integer::intValue).average().orElse(0);
                scored.add(new Object[]{e.getKey(), avg, e.getValue()});
            }
            scored.sort((a, b) -> Double.compare((Double) b[1], (Double) a[1]));
            if (!ENTRY_NAMES.isEmpty()) Events.broadcast(server, "§6§l🏠 BUILD CONTEST RESULTS §7(" + theme + ")");
            long[] pay = {4_000, 2_000, 1_000};
            for (int i = 0; i < Math.min(3, scored.size()); i++) {
                UUID id = (UUID) scored.get(i)[0];
                Events.broadcast(server, String.format(" §e%d. §f%s §7- %.1f stars", i + 1, scored.get(i)[2], (Double) scored.get(i)[1]));
                Economy.add(server, id, (String) scored.get(i)[2], pay[i]);
                Keys.reward(server, id, 2 - i, 1, "(build contest " + (i + 1) + (i == 0 ? "st" : i == 1 ? "nd" : "rd") + ")");
            }
            // Next week's theme: the most votes (random among the options if nobody voted).
            int[] c = new int[NEXT_OPTIONS.size()];
            for (int v : THEME_VOTES.values()) if (v >= 0 && v < c.length) c[v]++;
            int best = RNG.nextInt(Math.max(1, c.length));
            for (int i = 0; i < c.length; i++) if (c[i] > c[best]) best = i;
            theme = NEXT_OPTIONS.isEmpty() ? THEMES[RNG.nextInt(THEMES.length)] : NEXT_OPTIONS.get(best);
            Events.broadcast(server, "§6New build contest theme this week: §e§l" + theme + " §7(Curator Ivy, Builders' Gallery)");
            pickOptions();
            THEME_VOTES.clear();
            ENTRY_NAMES.clear();
            RATINGS.clear();
            buildWeek = now;
            saveBuild();
        }
    }

    // =====================================================================
    // Coach Pip: daily time trial
    // =====================================================================

    record Trial(LobbyDesign.Course course, int targetTicks) {}

    static final int[] DEFAULT_TARGET_SECONDS = {180, 300, 420, 540};
    static final long[] TRIAL_PAY = {250, 400, 600, 900};
    static final int[] TRIAL_XP = {300, 500, 800, 1_200};

    static Trial trial(ServerPlayer p) {
        int lvl = Skills.level(p.getUUID(), Skills.Skill.PARKOUR);
        int maxTier = lvl < 10 ? 1 : lvl < 25 ? 2 : 3;
        // Same trial all day for a player (seeded by the date and their id).
        Random r = new Random(LocalDate.now().toEpochDay() * 31 + p.getUUID().hashCode());
        int tier = r.nextInt(maxTier + 1);
        LobbyDesign.Course course = null;
        for (LobbyDesign.Course c : LobbyDesign.COURSES) if (c.tier() == tier) course = c;
        if (course == null) course = LobbyDesign.COURSES.get(0);
        Integer best = Parkour.best(course.id(), p.getUUID());
        int target = best == null ? DEFAULT_TARGET_SECONDS[course.tier()] * 20 : (int) Math.round(best * 1.10); // within 10% of your best
        return new Trial(course, target);
    }

    private static void coach(ServerPlayer p, Npc n) {
        Trial t = trial(p);
        boolean done = LocalDate.now().toString().equals(CLAIMED.get("trial:" + p.getUUID()));
        say(p, n.name(), done ? "You already beat today's trial - nice running! New one tomorrow." :
                "Today's time trial: finish §e" + t.course().name() + "§f in under §e" + Parkour.time(t.targetTicks()) + "§f!");
        if (!done) {
            Lobby.msg(p, " §7Reward: §6" + Economy.format(TRIAL_PAY[t.course().tier()]) + " §7and §d" + TRIAL_XP[t.course().tier()] + " Parkour XP");
            p.sendSystemMessage(button("§d§l[Take me to the start]", "/ssnpc coach go", "Teleport to " + t.course().name()));
        }
    }

    /** Called by Parkour when someone finishes a course. */
    static void courseFinished(ServerPlayer p, LobbyDesign.Course course, int ticks) {
        String key = "trial:" + p.getUUID(), today = LocalDate.now().toString();
        if (today.equals(CLAIMED.get(key))) return;
        Trial t = trial(p);
        if (!t.course().id().equals(course.id())) return;
        if (ticks > t.targetTicks()) {
            Lobby.msg(p, "§dCoach Pip's trial: §7" + Parkour.time(ticks) + " - you needed under " + Parkour.time(t.targetTicks()) + ". Try again!");
            return;
        }
        CLAIMED.put(key, today);
        Economy.add(p.level().getServer(), p.getUUID(), p.getName().getString(), TRIAL_PAY[course.tier()]);
        Skills.add(p, Skills.Skill.PARKOUR, TRIAL_XP[course.tier()]);
        Lobby.msg(p, "§d§lTIME TRIAL BEATEN! §6+" + Economy.format(TRIAL_PAY[course.tier()]) + " §d+" + TRIAL_XP[course.tier()] + " Parkour XP");
    }
}
