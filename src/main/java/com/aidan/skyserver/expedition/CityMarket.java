package com.aidan.skyserver.expedition;

import com.aidan.skyserver.Safe;
import com.aidan.skyserver.Skyserver;
import com.aidan.skyserver.island.Islands;
import com.aidan.skyserver.island.WrenRoom;
import com.aidan.skyserver.lobby.*;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.minecraft.commands.Commands;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;

import java.time.*;
import java.util.*;
import java.util.function.Function;

/**
 * 5.21: CITY CASH and OLD VES'S MARKET (the Archivist's Stall).
 *
 *  - City Cash (CC) is earned only in the Ancient City (escapes, Silent Escape, Clean Sweep, the Deep Vault,
 *    first stamps, weekly leaderboards, selling City loot to Ves). It can't be swapped for money either way.
 *    Kept as the "city_cash" token (Tokens). /citycash (/cc) shows it.
 *  - Old Ves stands in Captain Wren's ship room (he appears when the room is placed) and has a tab in Wren's menu.
 *    His shelves: City gear, Progression (unlocked by Ancient City stamps: Easy = basics, Normal = books,
 *    Hard = Mending and the enchanted golden apple), Collecting, Decoration, Cosmetics, and Sell.
 *  - SOFT WEEKLY ALLOWANCE on progression items: list price up to the allowance, then double price up to the
 *    weekly maximum. Mending and the enchanted golden apple: 1 a week. Everything resets Thursday 00:00 Eastern.
 *  - Enchanted books also cost ECHO SHARDS (1-6 by value), taken from the inventory.
 *  - OLD VES'S REQUESTS: 3 a week (Easy-Normal, Normal-Hard, Hard-Nightmare). Do it at that difficulty or
 *    harder, escape, and he hands over an item worth no more than one perfect run's CC at that difficulty.
 *  - ARCHIVIST'S FIND: one rare item a week at 25% off, once per player.
 *  - THE MAPS: 3 fixed Ancient City maps, one a week (map 1, 2, 3, 1...), switching Thursday 00:00 Eastern.
 *  - WEEKLY LEADERBOARD per difficulty (fastest escape, ties: quieter): top 3 get 500 / 300 / 150 CC at the
 *    reset, and each difficulty's winner wears the [Shadow] tag for the next week.
 *  - Ancient Shards: 6 collectible designs (chests and the Deep Vault). All 6 = "Archivist of the Deep" + a Rare key.
 * Saved in config/skyserver/citymarket.json.
 */
public final class CityMarket {
    private CityMarket() {}

    public static final String CC = "city_cash";
    public static final String VES_TAG = "skyserver_ves";
    public static final String SHADOW_TAG = "§8[§3Shadow§8] ";
    static final String SHARD_TAG = "ancient_shard_";
    /** Map rotation and weeks: week 0 starts Thursday 2026-11-12 00:00 Eastern. */
    private static final ZoneId ET = ZoneId.of("America/New_York");
    private static final LocalDate WEEK0 = LocalDate.of(2026, 11, 12);
    static final int[] BOARD_PAY = {500, 300, 150};
    /** CC worth of one perfect run (escape + Silent + Clean Sweep + vault): the most a request may give. */
    static final int[] RUN_VALUE = {473, 945, 1_900, 3_800};
    static final String[] SHARD_NAMES = {"The Warden's Eye", "The Silent Choir", "The Buried King", "The Hollow Heart", "The Last Lantern", "The Deep Path"};
    static final String[] SHARD_ITEMS = {"danger_pottery_sherd", "howl_pottery_sherd", "skull_pottery_sherd", "heartbreak_pottery_sherd",
            "mourner_pottery_sherd", "explorer_pottery_sherd"};
    static final String TITLE_ARCHIVIST = "title_archivist", TITLE_WHISPER = "title_whisper", TITLE_SAINT = "title_hollow_saint", TRAIL_SCULK = "trail_sculk";
    static final ResourceKey<Enchantment> HUSH = ResourceKey.create(Registries.ENCHANTMENT, Identifier.fromNamespaceAndPath(Skyserver.MOD_ID, "hush"));

    private static MinecraftServer server;
    private static JsonObject data;
    private static long ticks = 0;

    // =====================================================================
    // Setup
    // =====================================================================

    /** From AncientCity.init (registered before Islands, so Ves's right-click is handled here first). */
    static void init() {
        ServerLifecycleEvents.SERVER_STARTED.register(s -> {
            server = s;
            Safe.run("city market load", () -> { load(); rollover(); });
        });
        ServerTickEvents.END_SERVER_TICK.register(s -> {
            ticks++;
            if (ticks % 1200 == 77) Safe.run("city market week", CityMarket::rollover);
        });
        UseEntityCallback.EVENT.register((player, level, hand, entity, hit) -> {
            if (!isVes(entity)) return InteractionResult.PASS;
            if (hand == net.minecraft.world.InteractionHand.MAIN_HAND && player instanceof ServerPlayer sp) Lobby.later(sp, () -> open(sp));
            return InteractionResult.SUCCESS;
        });
        AttackEntityCallback.EVENT.register((player, level, hand, entity, hit) -> isVes(entity) ? InteractionResult.FAIL : InteractionResult.PASS);
        CommandRegistrationCallback.EVENT.register((d, r, e) -> {
            d.register(Commands.literal("citycash").executes(c -> { balanceMsg(c.getSource().getPlayerOrException()); return 1; }));
            d.register(Commands.literal("cc").executes(c -> { balanceMsg(c.getSource().getPlayerOrException()); return 1; }));
            d.register(Commands.literal("citymarket").executes(c -> { openCommand(c.getSource().getPlayerOrException()); return 1; }));
        });
    }

    public static boolean isVes(Entity e) {
        return e != null && e.entityTags().contains(VES_TAG);
    }

    static void balanceMsg(ServerPlayer p) {
        Lobby.msg(p, "§3§lCity Cash: §b" + fmt(balance(p.getUUID())) + " CC §7- earned in the Ancient City, spent at §3Old Ves's stall §7in Wren's ship room.");
    }

    private static void openCommand(ServerPlayer p) {
        Islands.Island is = Islands.islandOf(p.getUUID());
        if (is == null || is.wrenRoom == null) {
            Lobby.msg(p, "§7Old Ves keeps his stall in Captain Wren's ship room. §e/is shiproom place §7to build it.");
            return;
        }
        if (!Islands.isIsland(p.level())) {
            Lobby.msg(p, "§7Old Ves is in your ship room - go to your island first (§e/is shiproom§7).");
            return;
        }
        open(p);
    }

    // =====================================================================
    // City Cash
    // =====================================================================

    public static int balance(UUID id) {
        return Tokens.count(id, CC);
    }

    /** Pays City Cash (and counts where it came from for /ss economy). */
    static void earn(UUID id, int amount, String source) {
        if (amount <= 0) return;
        Tokens.add(id, CC, amount);
        stat("earned", source, amount);
    }

    static boolean spend(UUID id, int amount, String what) {
        if (amount <= 0) return true;
        if (!Tokens.take(id, CC, amount)) return false;
        stat("spent", what, amount);
        return true;
    }

    public static String fmt(long v) {
        return String.format("%,d", v);
    }

    // =====================================================================
    // Weeks, the map rotation, the reset
    // =====================================================================

    /** Weeks since Thursday 2026-11-12 00:00 Eastern. */
    public static int week() {
        LocalDateTime now = LocalDateTime.now(ET);
        long days = Duration.between(WEEK0.atStartOfDay(), now).toDays();
        if (now.isBefore(WEEK0.atStartOfDay())) days = -1;
        return (int) Math.floorDiv(days, 7L);
    }

    /** This week's map (0, 1 or 2): map 1, 2, 3, 1... */
    public static int mapIndex() {
        return Math.floorMod(week(), 3);
    }

    /** Time until the next reset, e.g. "2d 5h". */
    static String untilReset() {
        LocalDateTime now = LocalDateTime.now(ET);
        LocalDateTime next = WEEK0.atStartOfDay().plusWeeks(week() + 1L);
        Duration d = Duration.between(now, next);
        return d.toDays() + "d " + (d.toHours() % 24) + "h";
    }

    private static void load() {
        if (data != null) return;
        data = Stacks.readFile("citymarket.json");
        for (String k : new String[]{"players", "perm", "board", "stats"}) if (!data.has(k)) data.add(k, new JsonObject());
        if (!data.has("shadow")) data.add("shadow", new JsonArray());
        if (!data.has("week")) data.addProperty("week", week());
    }

    private static void save() {
        if (data == null) return;
        JsonObject copy = data.deepCopy();
        PlayerCache.io(() -> Stacks.writeFile("citymarket.json", copy));
    }

    /** At the weekly reset: pay the leaderboards, hand out [Shadow], clear the week's allowances/requests. */
    static void rollover() {
        load();
        int now = week(), was = data.get("week").getAsInt();
        if (now == was) return;
        JsonArray shadow = new JsonArray();
        JsonObject board = data.getAsJsonObject("board");
        for (int d = 0; d < 4; d++) {
            List<JsonObject> top = topOf(board, d);
            for (int i = 0; i < Math.min(3, top.size()); i++) {
                JsonObject e = top.get(i);
                UUID id = UUID.fromString(e.get("id").getAsString());
                earn(id, BOARD_PAY[i], "leaderboard");
                if (i == 0) shadow.add(id.toString());
                ServerPlayer p = server == null ? null : server.getPlayerList().getPlayer(id);
                if (p != null) Lobby.msg(p, "§3§lCITY LEADERBOARD §7- you finished §f#" + (i + 1) + " §7on " + AncientCity.DIFF_COLORS[d] + AncientCity.DIFF_NAMES[d]
                        + " §7last week: §b+" + BOARD_PAY[i] + " CC" + (i == 0 ? " §7and the " + SHADOW_TAG.trim() + " §7tag this week!" : "§7."));
            }
        }
        data.add("shadow", shadow);
        data.add("board", new JsonObject());
        data.add("players", new JsonObject());
        data.add("stats", new JsonObject());
        data.addProperty("week", now);
        save();
        if (server != null) server.getPlayerList().broadcastSystemMessage(Component.literal("§3§l✦ The Ancient City shifts... §7Map §f" + (mapIndex() + 1)
                + " §7this week. Old Ves has new requests and a new find."), false);
        Skyserver.LOGGER.info("[City] week {} -> {}: map {}", was, now, mapIndex() + 1);
    }

    public static boolean isShadow(UUID id) {
        if (data == null) return false;
        for (JsonElement e : data.getAsJsonArray("shadow")) if (e.getAsString().equals(id.toString())) return true;
        return false;
    }

    /** This week's per-player object (allowances, first escape, requests done). */
    private static JsonObject wk(UUID id) {
        load();
        JsonObject players = data.getAsJsonObject("players");
        if (!players.has(id.toString())) players.add(id.toString(), new JsonObject());
        return players.getAsJsonObject(id.toString());
    }

    /** Things kept forever (first stamps, Ancient Shards). */
    private static JsonObject perm(UUID id) {
        load();
        JsonObject perm = data.getAsJsonObject("perm");
        if (!perm.has(id.toString())) perm.add(id.toString(), new JsonObject());
        return perm.getAsJsonObject(id.toString());
    }

    private static int getInt(JsonObject o, String k) {
        return o.has(k) ? o.get(k).getAsInt() : 0;
    }

    private static void addInt(JsonObject o, String k, int v) {
        o.addProperty(k, getInt(o, k) + v);
    }

    private static void stat(String kind, String what, int amount) {
        load();
        JsonObject stats = data.getAsJsonObject("stats");
        if (!stats.has(kind)) stats.add(kind, new JsonObject());
        addInt(stats.getAsJsonObject(kind), what, amount);
        save();
    }

    /** For /ss economy (runs on a background thread; reads a copy). */
    public static List<String> reportLines() {
        List<String> out = new ArrayList<>();
        JsonObject stats;
        synchronized (CityMarket.class) {
            if (data == null) return out;
            stats = data.getAsJsonObject("stats").deepCopy();
        }
        out.add("§3§lCITY CASH §7(this week, map " + (mapIndex() + 1) + ")");
        for (String kind : new String[]{"earned", "spent"}) {
            if (!stats.has(kind)) continue;
            long sum = 0;
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, JsonElement> e : stats.getAsJsonObject(kind).entrySet()) {
                sum += e.getValue().getAsLong();
                sb.append(" §f").append(e.getKey()).append(" §7").append(fmt(e.getValue().getAsLong()));
            }
            out.add("  " + (kind.equals("earned") ? "§aEarned " : "§cSpent ") + fmt(sum) + " CC§7:" + sb);
        }
        return out;
    }

    // =====================================================================
    // Hooks from AncientCity
    // =====================================================================

    /** The weekly first-escape bonus: true once per player per week. */
    static boolean firstEscapeThisWeek(UUID id) {
        JsonObject w = wk(id);
        if (w.has("escaped")) return false;
        w.addProperty("escaped", true);
        save();
        return true;
    }

    /** First stamp of this map at this difficulty (forever): true once. */
    static boolean firstStamp(UUID id, int map, int d) {
        JsonObject pm = perm(id);
        String k = "stamp_" + map + "_" + d;
        if (pm.has(k)) return false;
        pm.addProperty(k, true);
        save();
        return true;
    }

    /** A leaderboard time (seconds) and the noise meter at the rope (percent). Keeps each player's best. */
    static void recordEscape(ServerPlayer p, int d, int seconds, int meter) {
        load();
        JsonObject board = data.getAsJsonObject("board");
        String key = String.valueOf(d);
        if (!board.has(key)) board.add(key, new JsonObject());
        JsonObject b = board.getAsJsonObject(key);
        String id = p.getUUID().toString();
        JsonObject old = b.has(id) ? b.getAsJsonObject(id) : null;
        if (old != null && (old.get("secs").getAsInt() < seconds || (old.get("secs").getAsInt() == seconds && old.get("meter").getAsInt() <= meter))) return;
        JsonObject e = new JsonObject();
        e.addProperty("id", id);
        e.addProperty("name", p.getName().getString());
        e.addProperty("secs", seconds);
        e.addProperty("meter", meter);
        b.add(id, e);
        save();
        List<JsonObject> top = topOf(board, d);
        for (int i = 0; i < Math.min(3, top.size()); i++)
            if (top.get(i).get("id").getAsString().equals(id))
                Lobby.msg(p, "§3§lCITY LEADERBOARD §7- you're §f#" + (i + 1) + " §7on " + AncientCity.DIFF_COLORS[d] + AncientCity.DIFF_NAMES[d] + " §7this week (" + time(seconds) + ").");
    }

    private static List<JsonObject> topOf(JsonObject board, int d) {
        List<JsonObject> out = new ArrayList<>();
        String key = String.valueOf(d);
        if (!board.has(key)) return out;
        for (Map.Entry<String, JsonElement> e : board.getAsJsonObject(key).entrySet()) out.add(e.getValue().getAsJsonObject());
        out.sort(Comparator.<JsonObject>comparingInt(o -> o.get("secs").getAsInt()).thenComparingInt(o -> o.get("meter").getAsInt()));
        return out;
    }

    static String time(int secs) {
        return (secs / 60) + ":" + String.format("%02d", secs % 60);
    }

    /** /city top */
    static void showBoard(ServerPlayer p) {
        load();
        Lobby.msg(p, "§3§lCITY LEADERBOARD §7- map §f" + (mapIndex() + 1) + "§7, resets in §f" + untilReset() + " §7(top 3 get 500 / 300 / 150 CC, #1 the "
                + SHADOW_TAG.trim() + "§7 tag)");
        JsonObject board = data.getAsJsonObject("board");
        for (int d = 0; d < 4; d++) {
            List<JsonObject> top = topOf(board, d);
            StringBuilder sb = new StringBuilder(" " + AncientCity.DIFF_COLORS[d] + AncientCity.DIFF_NAMES[d] + "§7: ");
            if (top.isEmpty()) sb.append("§8no escapes yet");
            for (int i = 0; i < Math.min(5, top.size()); i++) {
                JsonObject e = top.get(i);
                sb.append("§f").append(i + 1).append(". ").append(e.get("name").getAsString()).append(" §7").append(time(e.get("secs").getAsInt()))
                        .append(" §8(").append(e.get("meter").getAsInt()).append("%)  ");
            }
            Lobby.msg(p, sb.toString());
        }
    }

    // ---- Ancient Shards ----

    static ItemStack ancientShard(int i) {
        ItemStack s = new ItemStack(EndRewards.item(SHARD_ITEMS[i]));
        Stacks.named(s, "§3§lAncient Shard: §b" + SHARD_NAMES[i], "§7A piece of the Ancient City's story (" + (i + 1) + "/6).",
                "§7Collect all six: the §3Archivist of the Deep §7title", "§7and a Rare key. Ves buys spares.");
        return Stacks.tag(s, SHARD_TAG + i);
    }

    static int shardIndex(ItemStack s) {
        for (int i = 0; i < SHARD_NAMES.length; i++) if (Stacks.is(s, SHARD_TAG + i)) return i;
        return -1;
    }

    /** Gives an Ancient Shard and records it in the player's collection. */
    static void giveShard(ServerPlayer p, int i) {
        EndRewards.give(p, ancientShard(i));
        JsonObject pm = perm(p.getUUID());
        String k = "shard_" + i;
        if (pm.has(k)) {
            Lobby.msg(p, "§3Ancient Shard: §b" + SHARD_NAMES[i] + " §8(you already have this one - Ves buys spares)");
            return;
        }
        pm.addProperty(k, true);
        save();
        int have = shardsOwned(p.getUUID());
        Lobby.msg(p, "§3§lNEW ANCIENT SHARD: §b" + SHARD_NAMES[i] + " §7(" + have + "/6)");
        if (have >= SHARD_NAMES.length && !pm.has("archivist")) {
            pm.addProperty("archivist", true);
            save();
            PlayerProfile prof = PlayerCache.get(p);
            if (prof.owned.add(TITLE_ARCHIVIST)) prof.touch();
            Keys.reward(server, p.getUUID(), 2, 1, "(Ancient Shards complete)");
            Announce.title(p, "§3§lTHE STORY IS WHOLE", "§7Title: " + Progression.cosmeticName(TITLE_ARCHIVIST) + " §7+ a Rare key", 10, 70, 20);
        }
    }

    static int shardsOwned(UUID id) {
        JsonObject pm = perm(id);
        int n = 0;
        for (int i = 0; i < SHARD_NAMES.length; i++) if (pm.has("shard_" + i)) n++;
        return n;
    }

    static int randomShard(Random rng) {
        return rng.nextInt(SHARD_NAMES.length);
    }

    // ---- Hush ----

    static Holder<Enchantment> hush(MinecraftServer s) {
        try {
            return s.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(HUSH);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** The Hush level of the boots this player wears (0 if none, or if the enchantment isn't loaded). */
    static int hushLevel(ServerPlayer p) {
        Holder<Enchantment> h = hush(p.level().getServer());
        if (h == null) return 0;
        ItemStack boots = p.getItemBySlot(EquipmentSlot.FEET);
        return boots.isEmpty() ? 0 : boots.getEnchantments().getLevel(h);
    }

    static ItemStack hushBook(MinecraftServer s, int lvl) {
        return EndRewards.book(s, HUSH, lvl);
    }

    // ---- Discs ----

    private static final List<String> COMMON = List.of("13", "cat", "blocks", "chirp", "far", "mall", "mellohi", "stal", "strad", "ward", "wait");
    private static final List<String> RARE = List.of("otherside", "pigstep", "precipice", "creator", "relic");
    /** Disc tier chances by difficulty: {common, uncommon, rare, legendary}. */
    static final int[][] DISC_ODDS = {{85, 15, 0, 0}, {60, 32, 8, 0}, {35, 40, 22, 3}, {15, 35, 40, 10}};
    static final String[] DISC_TIERS = {"§fCommon", "§aUncommon", "§9Rare", "§6Legendary"};

    /** Every music disc in the game, by tier (0 common, 1 uncommon, 2 rare, 3 = disc 5). New discs count as uncommon. */
    static List<Item> discs(int tier) {
        List<Item> out = new ArrayList<>();
        for (Identifier id : BuiltInRegistries.ITEM.keySet()) {
            String path = id.getPath();
            if (!id.getNamespace().equals("minecraft") || !path.startsWith("music_disc_")) continue;
            String n = path.substring("music_disc_".length());
            int t = n.equals("5") ? 3 : COMMON.contains(n) ? 0 : RARE.contains(n) ? 2 : 1;
            if (t == tier) out.add(BuiltInRegistries.ITEM.getValue(id));
        }
        return out;
    }

    static ItemStack randomDisc(int tier, Random rng) {
        List<Item> l = discs(tier);
        if (l.isEmpty()) l = discs(0);
        return l.isEmpty() ? ItemStack.EMPTY : new ItemStack(l.get(rng.nextInt(l.size())));
    }

    /** The escape disc: a tier rolled by difficulty. */
    static ItemStack escapeDisc(int d, Random rng) {
        int r = rng.nextInt(100), tier = 0, acc = 0;
        for (int t = 0; t < 4; t++) {
            acc += DISC_ODDS[d][t];
            if (r < acc) { tier = t; break; }
        }
        return randomDisc(tier, rng);
    }

    // =====================================================================
    // Old Ves himself (lives in the ship room)
    // =====================================================================

    /** Called by WrenRoom when Wren is checked: puts Ves at his spot, or removes him if there's no room. */
    public static void ensureVes(ServerLevel level, Islands.Island is, double[] spot) {
        double half = is.size / 2.0 + 30;
        net.minecraft.world.phys.AABB island = new net.minecraft.world.phys.AABB(is.cx() - half, Islands.VOID_Y, is.cz() - half, is.cx() + half, Islands.TOP_Y + 100, is.cz() + half);
        List<net.minecraft.world.entity.npc.villager.Villager> found = level.getEntitiesOfClass(net.minecraft.world.entity.npc.villager.Villager.class, island, CityMarket::isVes);
        if (spot == null) {
            for (var v : found) v.discard();
            return;
        }
        if (!level.isLoaded(net.minecraft.core.BlockPos.containing(spot[0], spot[1], spot[2]))) return;
        if (!found.isEmpty()) {
            for (int i = 1; i < found.size(); i++) found.get(i).discard();
            var v = found.get(0);
            if (v.distanceToSqr(spot[0], spot[1], spot[2]) > 1.0) {
                v.teleportTo(spot[0], spot[1], spot[2]);
                v.setYRot((float) spot[3]);
                v.setYHeadRot((float) spot[3]);
            }
            return;
        }
        var v = net.minecraft.world.entity.EntityTypes.VILLAGER.create(level, net.minecraft.world.entity.EntitySpawnReason.COMMAND);
        if (v == null) return;
        v.snapTo(spot[0], spot[1], spot[2], (float) spot[3], 0f);
        v.setYHeadRot((float) spot[3]);
        v.setNoAi(true);
        v.setPermanentlyInvulnerable(true);
        v.setSilent(true);
        v.setPersistenceRequired();
        v.setCustomName(Component.literal("§3Old Ves §7(the Archivist)"));
        v.setCustomNameVisible(true);
        v.addTag(VES_TAG);
        level.addFreshEntity(v);
    }

    // =====================================================================
    // The stall: offers
    // =====================================================================

    /** Allowance groups: {units at list price per week, most units per week}. */
    private static final Map<String, int[]> ALLOW = Map.of(
            "diamond", new int[]{16, 32}, "gapple", new int[]{8, 16}, "xp", new int[]{64, 128}, "keyshard", new int[]{50, 100},
            "book", new int[]{2, 4}, "mending", new int[]{1, 1}, "egapple", new int[]{1, 1});

    /**
     * One thing Ves sells. units = how many count toward the allowance group per purchase. unlock: null, or a
     * Charts stamp ("city_1" = a Normal escape...), or "hard_escape" / "normal_escape".
     */
    private record Offer(int slot, String name, int price, int echo, String group, int units, String unlock, Function<ServerPlayer, ItemStack> make,
                         Item icon, String cosmetic, String... lore) {}

    private static Offer o(int slot, String name, int price, int echo, String group, int units, String unlock, Function<ServerPlayer, ItemStack> make, Item icon, String... lore) {
        return new Offer(slot, name, price, echo, group, units, unlock, make, icon, null, lore);
    }

    private static ItemStack book(ServerPlayer p, ResourceKey<Enchantment> k, int lvl) {
        return EndRewards.book(p.level().getServer(), k, lvl);
    }

    static ItemStack rattles(int n) {
        ItemStack s = Stacks.tag(new ItemStack(Items.SNOWBALL, n), AncientCity.RATTLE_TAG);
        return Stacks.named(s, "§fBone Rattle", "§7Throw it: the Warden goes to where", "§7it lands (+1 noise). Only these", "§7work in the Ancient City.");
    }

    static ItemStack lens(int n) {
        ItemStack s = Stacks.tag(new ItemStack(Items.GLOW_INK_SAC, n), AncientCity.LENS_TAG);
        return Stacks.named(s, "§bEcho Lens", "§7Right-click in the Ancient City:", "§7sculk sensors within 12 blocks", "§7glow for 8 seconds. One use each.");
    }

    static ItemStack tonic() {
        ItemStack s = Stacks.tag(new ItemStack(Items.HONEY_BOTTLE), AncientCity.TONIC_TAG);
        return Stacks.named(s, "§dHush Tonic", "§7Right-click in the Ancient City:", "§7your crouching noise is halved", "§7for 60 seconds. One per trip.");
    }

    static ItemStack ladders() {
        return Stacks.named(new ItemStack(Items.LADDER, 8), "§6Vault Ladder", "§7Placing a ladder in the Ancient City", "§7makes half the noise of a block.");
    }

    private static List<Offer> gear() {
        List<Offer> l = new ArrayList<>();
        l.add(o(10, "§fBone Rattles §7x2", 60, 0, null, 0, null, p -> rattles(2), Items.SNOWBALL, "§7Lure the Warden away. Carry up to 6."));
        l.add(o(11, "§bEcho Lens §7x3", 150, 0, null, 0, null, p -> lens(3), Items.GLOW_INK_SAC, "§7Show the sculk sensors near you."));
        l.add(o(12, "§dHush Tonic", 250, 0, null, 0, null, p -> tonic(), Items.HONEY_BOTTLE, "§7Half crouching noise for 60 s (1 a trip)."));
        l.add(o(13, "§6Vault Ladder §7x8", 80, 0, null, 0, null, p -> ladders(), Items.LADDER, "§7Climb out of the Deep Vault quietly."));
        l.add(o(14, "§bRecovery Compass", 250, 0, null, 0, null, p -> new ItemStack(Items.RECOVERY_COMPASS), Items.RECOVERY_COMPASS,
                "§7Needed to find Wren's rope. Not used up.", "§8(Wren also sells it for $2,500.)"));
        l.add(o(19, "§3Hush I Book", 600, 1, null, 0, null, p -> hushBook(p.level().getServer(), 1), Items.ENCHANTED_BOOK, "§7Boots: quieter crouching (-10%)."));
        l.add(o(20, "§3Hush II Book", 2_000, 2, null, 0, null, p -> hushBook(p.level().getServer(), 2), Items.ENCHANTED_BOOK, "§7Boots: -20%. Needed for Nightmare."));
        l.add(o(21, "§3Hush III Book", 6_000, 5, null, 0, "city_2", p -> hushBook(p.level().getServer(), 3), Items.ENCHANTED_BOOK, "§7Boots: -33%."));
        l.add(o(23, "§bSwift Sneak I Book", 400, 1, null, 0, null, p -> book(p, Enchantments.SWIFT_SNEAK, 1), Items.ENCHANTED_BOOK));
        l.add(o(24, "§bSwift Sneak II Book", 1_200, 1, null, 0, null, p -> book(p, Enchantments.SWIFT_SNEAK, 2), Items.ENCHANTED_BOOK));
        l.add(o(25, "§bSwift Sneak III Book", 3_500, 3, null, 0, null, p -> book(p, Enchantments.SWIFT_SNEAK, 3), Items.ENCHANTED_BOOK));
        return l;
    }

    private static List<Offer> progression() {
        List<Offer> l = new ArrayList<>();
        l.add(o(10, "§b4 Diamonds", 240, 0, "diamond", 4, "city_0", p -> new ItemStack(Items.DIAMOND, 4), Items.DIAMOND));
        l.add(o(11, "§62 Golden Apples", 100, 0, "gapple", 2, "city_0", p -> new ItemStack(Items.GOLDEN_APPLE, 2), Items.GOLDEN_APPLE));
        l.add(o(12, "§a16 Bottles o' Enchanting", 240, 0, "xp", 16, "city_0", p -> new ItemStack(Items.EXPERIENCE_BOTTLE, 16), Items.EXPERIENCE_BOTTLE));
        l.add(o(13, "§d10 Key Shards", 100, 0, "keyshard", 10, "city_0", p -> ItemStack.EMPTY, Items.AMETHYST_SHARD, "§7Straight to your shards (§e/shards§7)."));
        Object[][] books = {
                {19, "§fUnbreaking III Book", 2_400, 2, Enchantments.UNBREAKING, 3}, {20, "§fFeather Falling IV Book", 2_100, 2, Enchantments.FEATHER_FALLING, 4},
                {21, "§fEfficiency V Book", 2_700, 2, Enchantments.EFFICIENCY, 5}, {22, "§fSilk Touch Book", 2_700, 2, Enchantments.SILK_TOUCH, 1},
                {23, "§fPower V Book", 3_000, 3, Enchantments.POWER, 5}, {24, "§fProtection IV Book", 3_600, 3, Enchantments.PROTECTION, 4},
                {25, "§fSharpness V Book", 3_600, 3, Enchantments.SHARPNESS, 5}, {28, "§fLooting III Book", 4_000, 4, Enchantments.LOOTING, 3},
                {29, "§fFortune III Book", 6_000, 5, Enchantments.FORTUNE, 3}};
        for (Object[] b : books) {
            @SuppressWarnings("unchecked") ResourceKey<Enchantment> k = (ResourceKey<Enchantment>) b[4];
            int lvl = (int) b[5];
            l.add(o((int) b[0], (String) b[1], (int) b[2], (int) b[3], "book", 1, "city_1", p -> book(p, k, lvl), Items.ENCHANTED_BOOK));
        }
        l.add(o(32, "§d§lMending Book", 10_000, 6, "mending", 1, "city_2", p -> book(p, Enchantments.MENDING, 1), Items.ENCHANTED_BOOK, "§71 a week."));
        l.add(o(33, "§6§lEnchanted Golden Apple", 1_500, 0, "egapple", 1, "city_2", p -> new ItemStack(Items.ENCHANTED_GOLDEN_APPLE), Items.ENCHANTED_GOLDEN_APPLE, "§71 a week."));
        return l;
    }

    private static List<Offer> collecting() {
        List<Offer> l = new ArrayList<>();
        Random rng = new Random();
        l.add(o(10, "§fCommon Disc §7(random)", 150, 0, null, 0, null, p -> randomDisc(0, rng), Items.MUSIC_DISC_CAT));
        l.add(o(11, "§aUncommon Disc §7(random)", 400, 0, null, 0, null, p -> randomDisc(1, rng), Items.MUSIC_DISC_11));
        l.add(o(12, "§9Rare Disc §7(random)", 1_000, 0, null, 0, null, p -> randomDisc(2, rng), Items.MUSIC_DISC_OTHERSIDE));
        l.add(o(13, "§6Disc 5", 2_500, 0, null, 0, null, p -> randomDisc(3, rng), Items.MUSIC_DISC_5));
        l.add(o(15, "§3Ancient Shard §7(one you're missing)", 1_500, 0, null, 0, null, p -> ItemStack.EMPTY, Items.SKULL_POTTERY_SHERD));
        l.add(o(19, "§bWard Armor Trim", 6_000, 0, null, 0, null, p -> EndRewards.trim("ward"), EndRewards.item("ward_armor_trim_smithing_template")));
        l.add(o(20, "§d§lSilence Armor Trim", 20_000, 0, null, 0, "city_1", p -> EndRewards.trim("silence"), EndRewards.item("silence_armor_trim_smithing_template"),
                "§8Needs a Normal or harder escape."));
        return l;
    }

    private static List<Offer> decoration() {
        List<Offer> l = new ArrayList<>();
        l.add(o(10, "§316 Sculk", 32, 0, null, 0, null, p -> new ItemStack(Items.SCULK, 16), Items.SCULK));
        l.add(o(11, "§3Sculk Sensor", 20, 0, null, 0, null, p -> new ItemStack(Items.SCULK_SENSOR), Items.SCULK_SENSOR));
        l.add(o(12, "§3Calibrated Sculk Sensor", 40, 0, null, 0, null, p -> new ItemStack(Items.CALIBRATED_SCULK_SENSOR), Items.CALIBRATED_SCULK_SENSOR));
        l.add(o(13, "§3Sculk Shrieker", 120, 0, null, 0, null, p -> new ItemStack(Items.SCULK_SHRIEKER), Items.SCULK_SHRIEKER, "§8(Can't summon anything.)"));
        l.add(o(14, "§3Sculk Catalyst", 200, 0, null, 0, null, p -> new ItemStack(Items.SCULK_CATALYST), Items.SCULK_CATALYST));
        l.add(o(15, "§f16 Candles §7(mixed colours)", 48, 0, null, 0, null, p -> candles(16), EndRewards.item("candle")));
        l.add(o(16, "§3Echo Shard", 25, 0, null, 0, null, p -> new ItemStack(Items.ECHO_SHARD), Items.ECHO_SHARD));
        l.add(o(22, "§8§lReinforced Deepslate", 1_500, 0, null, 0, null, p -> new ItemStack(Items.REINFORCED_DEEPSLATE), Items.REINFORCED_DEEPSLATE,
                "§7The block nobody else can get."));
        return l;
    }

    private static ItemStack candles(int n) {
        // One colour per bundle (random), or plain candles.
        String[] all = {"white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray",
                "light_gray", "cyan", "purple", "blue", "brown", "green", "red", "black"};
        Random r = new Random();
        if (r.nextInt(all.length + 1) == 0) return new ItemStack(EndRewards.item("candle"), n);
        return new ItemStack(EndRewards.item(all[r.nextInt(all.length)] + "_candle"), n);
    }

    private static List<Offer> cosmetics() {
        List<Offer> l = new ArrayList<>();
        l.add(new Offer(11, "§3Title: Whisper", 2_000, 0, null, 0, null, p -> ItemStack.EMPTY, Items.NAME_TAG, TITLE_WHISPER));
        l.add(new Offer(12, "§3§lTitle: Hollow Saint", 10_000, 0, null, 0, null, p -> ItemStack.EMPTY, Items.NAME_TAG, TITLE_SAINT));
        l.add(new Offer(14, "§3Ocean Name Colour", 3_000, 0, null, 0, null, p -> ItemStack.EMPTY, Items.DYE.pick(DyeColor.CYAN), "color_ocean"));
        l.add(new Offer(15, "§aSculk Soul Trail", 5_000, 0, null, 0, null, p -> ItemStack.EMPTY, Items.SCULK_VEIN, TRAIL_SCULK));
        return l;
    }

    // =====================================================================
    // Requests and the Archivist's Find
    // =====================================================================

    /** One request: difficulty, condition, reward. */
    record Request(int diff, int cond, String rewardName, Function<ServerPlayer, ItemStack> reward, int shards) {}

    static final String[] COND_TEXT = {"Escape with the noise meter at 33% or less", "Clean Sweep: loot every real house, open no decoy",
            "Loot the Deep Vault and escape", "Escape within %d minutes", "Break %d urns and escape", "Escape without anyone going down"};
    static final int[] FAST_MIN = {14, 17, 21, 25}, URN_GOAL = {8, 10, 13, 16};

    static String condText(Request r) {
        String t = COND_TEXT[r.cond()];
        if (r.cond() == 3) return String.format(t, FAST_MIN[r.diff()]);
        if (r.cond() == 4) return String.format(t, URN_GOAL[r.diff()]);
        return t;
    }

    /** This week's 3 requests (the same for everyone, from the week number). */
    static List<Request> requests() {
        int w = week();
        Random rng = new Random(w * 7919L + 101);
        List<Request> out = new ArrayList<>();
        int[][] bands = {{0, 1}, {1, 2}, {2, 3}};
        List<Integer> conds = new ArrayList<>(List.of(0, 1, 2, 3, 4, 5));
        Collections.shuffle(conds, rng);
        for (int i = 0; i < 3; i++) {
            int d = bands[i][rng.nextInt(2)];
            out.add(reward(d, conds.get(i), rng));
        }
        return out;
    }

    /** A reward worth at most one perfect run's CC at that difficulty. */
    private static Request reward(int d, int cond, Random rng) {
        int pick = rng.nextInt(4);
        switch (d) {
            case 0 -> {
                return switch (pick) {
                    case 0 -> new Request(d, cond, "§b6 Diamonds", p -> new ItemStack(Items.DIAMOND, 6), 0);
                    case 1 -> new Request(d, cond, "§68 Golden Apples", p -> new ItemStack(Items.GOLDEN_APPLE, 8), 0);
                    case 2 -> new Request(d, cond, "§a30 Bottles o' Enchanting", p -> new ItemStack(Items.EXPERIENCE_BOTTLE, 30), 0);
                    default -> new Request(d, cond, "§d40 Key Shards", p -> ItemStack.EMPTY, 40);
                };
            }
            case 1 -> {
                return switch (pick) {
                    case 0 -> new Request(d, cond, "§b15 Diamonds", p -> new ItemStack(Items.DIAMOND, 15), 0);
                    case 1 -> new Request(d, cond, "§3a Hush I Book", p -> hushBook(p.level().getServer(), 1), 0);
                    case 2 -> new Request(d, cond, "§616 Golden Apples", p -> new ItemStack(Items.GOLDEN_APPLE, 16), 0);
                    default -> new Request(d, cond, "§d90 Key Shards", p -> ItemStack.EMPTY, 90);
                };
            }
            case 2 -> {
                return switch (pick) {
                    case 0 -> new Request(d, cond, "§6§lan Enchanted Golden Apple", p -> new ItemStack(Items.ENCHANTED_GOLDEN_APPLE), 0);
                    case 1 -> new Request(d, cond, "§b30 Diamonds", p -> new ItemStack(Items.DIAMOND, 30), 0);
                    case 2 -> new Request(d, cond, "§ba Swift Sneak II Book", p -> book(p, Enchantments.SWIFT_SNEAK, 2), 0);
                    default -> new Request(d, cond, "§d180 Key Shards", p -> ItemStack.EMPTY, 180);
                };
            }
            default -> {
                return switch (pick) {
                    case 0 -> new Request(d, cond, "§fa Protection IV Book", p -> book(p, Enchantments.PROTECTION, 4), 0);
                    case 1 -> new Request(d, cond, "§fa Sharpness V Book", p -> book(p, Enchantments.SHARPNESS, 5), 0);
                    case 2 -> new Request(d, cond, "§ba Swift Sneak III Book", p -> book(p, Enchantments.SWIFT_SNEAK, 3), 0);
                    default -> new Request(d, cond, "§fa Power V Book", p -> book(p, Enchantments.POWER, 5), 0);
                };
            }
        }
    }

    /** What happened on one escape, for the requests. */
    record EscapeFacts(int diff, int meterPct, boolean sweep, boolean vault, int seconds, int urns, boolean nobodyDown) {}

    /** At an escape: hands out every request this escape completes (once each per week). */
    static void checkRequests(ServerPlayer p, EscapeFacts f) {
        List<Request> reqs = requests();
        JsonObject w = wk(p.getUUID());
        for (int i = 0; i < reqs.size(); i++) {
            Request r = reqs.get(i);
            if (w.has("req_" + i) || f.diff() < r.diff()) continue;
            boolean ok = switch (r.cond()) {
                case 0 -> f.meterPct() <= 33;
                case 1 -> f.sweep();
                case 2 -> f.vault();
                case 3 -> f.seconds() <= FAST_MIN[r.diff()] * 60;
                case 4 -> f.urns() >= URN_GOAL[r.diff()];
                default -> f.nobodyDown();
            };
            if (!ok) continue;
            w.addProperty("req_" + i, true);
            save();
            if (r.shards() > 0) Tokens.add(p.getUUID(), Tokens.SHARDS, r.shards());
            else EndRewards.give(p, r.reward().apply(p));
            Lobby.msg(p, "§3§lOLD VES'S REQUEST DONE! §7\"" + condText(r) + "\" - he sends you " + r.rewardName() + "§7.");
        }
    }

    /** This week's Archivist's Find: {name, list price, echo, item maker}. 25% off, one per player. */
    private static Offer find() {
        int w = Math.floorMod(week(), 4);
        Random rng = new Random();
        return switch (w) {
            case 0 -> o(31, "§6Disc 5", 2_500 * 3 / 4, 0, null, 0, null, p -> randomDisc(3, rng), Items.MUSIC_DISC_5);
            case 1 -> o(31, "§3Hush III Book", 6_000 * 3 / 4, 5, null, 0, null, p -> hushBook(p.level().getServer(), 3), Items.ENCHANTED_BOOK);
            case 2 -> o(31, "§bWard Armor Trim", 6_000 * 3 / 4, 0, null, 0, null, p -> EndRewards.trim("ward"), EndRewards.item("ward_armor_trim_smithing_template"));
            default -> o(31, "§d§lMending Book", 10_000 * 3 / 4, 6, null, 0, null, p -> book(p, Enchantments.MENDING, 1), Items.ENCHANTED_BOOK);
        };
    }

    // =====================================================================
    // Buying
    // =====================================================================

    /** unlock "city_N": an Ancient City escape at difficulty N or harder (a Wren's Charts stamp). */
    private static boolean unlocked(ServerPlayer p, String unlock) {
        if (unlock == null) return true;
        int need = unlock.charAt(unlock.length() - 1) - '0';
        for (int d = Math.max(0, need); d < 4; d++) if (Charts.has(p.getUUID(), "city_" + d)) return true;
        return false;
    }

    private static String unlockText(String unlock) {
        return switch (unlock) {
            case "city_0" -> "an Easy Ancient City escape";
            case "city_1" -> "a Normal Ancient City escape";
            case "city_2" -> "a Hard Ancient City escape";
            default -> "a Nightmare Ancient City escape";
        };
    }

    /** {price now (0 = sold out this week), units bought this week} */
    private static int[] priceNow(ServerPlayer p, Offer o) {
        if (o.group() == null) return new int[]{o.price(), 0};
        int[] a = ALLOW.get(o.group());
        int bought = getInt(wk(p.getUUID()), "buy_" + o.group());
        if (bought + o.units() > a[1]) return new int[]{0, bought};
        return new int[]{bought + o.units() > a[0] ? o.price() * 2 : o.price(), bought};
    }

    private static int echoShards(ServerPlayer p) {
        Inventory inv = p.getInventory();
        int n = 0;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (s.is(Items.ECHO_SHARD) && !s.has(net.minecraft.core.component.DataComponents.CUSTOM_DATA)) n += s.getCount();
        }
        return n;
    }

    private static void takeEchoShards(ServerPlayer p, int n) {
        Inventory inv = p.getInventory();
        for (int i = 0; i < inv.getContainerSize() && n > 0; i++) {
            ItemStack s = inv.getItem(i);
            if (!s.is(Items.ECHO_SHARD) || s.has(net.minecraft.core.component.DataComponents.CUSTOM_DATA)) continue;
            int t = Math.min(n, s.getCount());
            s.shrink(t);
            n -= t;
        }
    }

    private static int countTagged(ServerPlayer p, String tag) {
        Inventory inv = p.getInventory();
        int n = 0;
        for (int i = 0; i < inv.getContainerSize(); i++) if (Stacks.is(inv.getItem(i), tag)) n += inv.getItem(i).getCount();
        return n;
    }

    private static void buy(ServerPlayer p, Offer o, boolean isFind) {
        if (!Islands.isIsland(p.level())) {
            Lobby.msg(p, "§cOld Ves only trades in the ship room.");
            return;
        }
        UUID id = p.getUUID();
        if (o.unlock() != null && !unlocked(p, o.unlock())) {
            Lobby.msg(p, "§7Ves: §f\"Come back after " + unlockText(o.unlock()) + ", friend.\"");
            return;
        }
        if (o.cosmetic() != null && PlayerCache.get(p).owned.contains(o.cosmetic())) {
            Lobby.msg(p, "§7You already own that.");
            return;
        }
        if (isFind && wk(id).has("find")) {
            Lobby.msg(p, "§7You've had this week's find. A new one comes Thursday.");
            return;
        }
        if (o.slot() == 10 && o.name().contains("Bone Rattles") && countTagged(p, AncientCity.RATTLE_TAG) + 2 > 6) {
            Lobby.msg(p, "§7You can carry 6 Bone Rattles at most.");
            return;
        }
        int[] pn = priceNow(p, o);
        int price = pn[0];
        if (price == 0) {
            Lobby.msg(p, "§7That's all Ves will sell you this week (resets in " + untilReset() + ").");
            return;
        }
        if (o.echo() > 0 && echoShards(p) < o.echo()) {
            Lobby.msg(p, "§cThat also costs " + o.echo() + " echo shard" + (o.echo() == 1 ? "" : "s") + " (in your inventory).");
            return;
        }
        if (balance(id) < price) {
            Lobby.msg(p, "§cThat costs " + fmt(price) + " CC - you have " + fmt(balance(id)) + ".");
            return;
        }
        // The missing Ancient Shard: needs one to be missing.
        int shard = -1;
        if (o.name().startsWith("§3Ancient Shard")) {
            List<Integer> miss = new ArrayList<>();
            JsonObject pm = perm(id);
            for (int i = 0; i < SHARD_NAMES.length; i++) if (!pm.has("shard_" + i)) miss.add(i);
            shard = miss.isEmpty() ? new Random().nextInt(SHARD_NAMES.length) : miss.get(new Random().nextInt(miss.size()));
        }
        if (!spend(id, price, strip(o.name()))) return;
        if (o.echo() > 0) takeEchoShards(p, o.echo());
        if (o.group() != null) {
            addInt(wk(id), "buy_" + o.group(), o.units());
            save();
        }
        if (isFind) {
            wk(id).addProperty("find", true);
            save();
        }
        if (o.cosmetic() != null) {
            PlayerProfile prof = PlayerCache.get(p);
            prof.owned.add(o.cosmetic());
            prof.touch();
            Lobby.msg(p, "§aYou own " + Progression.cosmeticName(o.cosmetic()) + "§a now - put it on with §e/cosmetics§a.");
        } else if (o.group() != null && o.group().equals("keyshard")) {
            Tokens.add(id, Tokens.SHARDS, o.units());
        } else if (shard >= 0) {
            giveShard(p, shard);
        } else {
            EndRewards.give(p, o.make().apply(p));
        }
        p.level().playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.PLAYERS, 1f, 0.8f);
        Lobby.msg(p, "§3Ves: §f\"A fine choice.\" §7(" + strip(o.name()) + " for §b" + fmt(price) + " CC" + (o.echo() > 0 ? " §7+ §3" + o.echo() + " echo" : "") + "§7)");
    }

    private static String strip(String s) {
        return s.replaceAll("§.", "");
    }

    // ---- Selling City loot to Ves ----

    private record Sale(int slot, String name, Item item, int per, int each) {}

    private static List<Sale> sales() {
        return List.of(new Sale(10, "Echo Shards", Items.ECHO_SHARD, 1, 10), new Sale(11, "Sculk", Items.SCULK, 8, 1),
                new Sale(12, "Sculk Sensors", Items.SCULK_SENSOR, 1, 5), new Sale(13, "Sculk Shriekers", Items.SCULK_SHRIEKER, 1, 25),
                new Sale(14, "Sculk Catalysts", Items.SCULK_CATALYST, 1, 50), new Sale(15, "Disc 5 Fragments", Items.DISC_FRAGMENT_5, 1, 15),
                new Sale(16, "Ancient Shards (spares)", null, 1, 300));
    }

    private static void sell(ServerPlayer p, Sale s) {
        if (!Islands.isIsland(p.level())) return;
        Inventory inv = p.getInventory();
        int units = 0;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack st = inv.getItem(i);
            if (st.isEmpty()) continue;
            boolean match = s.item() == null ? shardIndex(st) >= 0 : st.is(s.item()) && !st.has(net.minecraft.core.component.DataComponents.CUSTOM_DATA);
            if (!match) continue;
            units += st.getCount();
        }
        int lots = units / s.per();
        if (lots == 0) {
            Lobby.msg(p, "§7You have no " + s.name() + " to sell" + (s.per() > 1 ? " (" + s.per() + " at a time)" : "") + ".");
            return;
        }
        int left = lots * s.per();
        for (int i = 0; i < inv.getContainerSize() && left > 0; i++) {
            ItemStack st = inv.getItem(i);
            boolean match = s.item() == null ? shardIndex(st) >= 0 : st.is(s.item()) && !st.has(net.minecraft.core.component.DataComponents.CUSTOM_DATA);
            if (!match) continue;
            int t = Math.min(left, st.getCount());
            st.shrink(t);
            left -= t;
        }
        int pay = lots * s.each();
        earn(p.getUUID(), pay, "sold to Ves");
        Lobby.msg(p, "§3Ves takes your " + s.name() + ": §b+" + fmt(pay) + " CC");
    }

    // =====================================================================
    // Menus
    // =====================================================================

    static void open(ServerPlayer p) {
        LockedMenu.open(p, "§8Old Ves's Stall", 6, Main::new);
    }

    /** From Wren's menu (island world only). */
    public static void openStall(ServerPlayer p) {
        if (!Islands.isIsland(p.level())) return;
        open(p);
    }

    static ItemStack offerIcon(ServerPlayer p, Offer o, boolean isFind) {
        List<String> lore = new ArrayList<>(Arrays.asList(o.lore()));
        int[] pn = priceNow(p, o);
        boolean locked = o.unlock() != null && !unlocked(p, o.unlock());
        boolean owned = o.cosmetic() != null && PlayerCache.get(p).owned.contains(o.cosmetic());
        if (o.group() != null) {
            int[] a = ALLOW.get(o.group());
            if (a[0] == a[1]) lore.add("§8Weekly: " + pn[1] + "/" + a[1]);
            else lore.add("§8This week: " + pn[1] + " bought · list price up to " + a[0] + ", then 2x up to " + a[1]);
        }
        if (isFind) lore.add("§a25% off this week only · one per player");
        if (locked) lore.add("§cUnlocks after " + unlockText(o.unlock()));
        if (owned) lore.add("§aYou own this");
        else if (pn[0] == 0) lore.add("§cSold out for you this week");
        else lore.add("§7Price: §b" + fmt(pn[0]) + " CC" + (o.echo() > 0 ? " §7+ §3" + o.echo() + " echo shard" + (o.echo() == 1 ? "" : "s") : "")
                + (o.group() != null && pn[0] > o.price() ? " §c(2x)" : ""));
        if (!locked && !owned && pn[0] > 0) lore.add("§eClick to buy");
        return Stacks.named(new ItemStack(o.icon()), o.name(), lore.toArray(new String[0]));
    }

    private static final class Main extends LockedMenu {
        private static final int GEAR = 19, PROG = 20, COLL = 21, DECO = 23, COSM = 24, SELL = 25, REQ = 29, FIND = 31, BOARD = 33;

        Main(int id, Inventory inv, SimpleContainer box, ServerPlayer viewer) {
            super(id, inv, box, 6, viewer);
            draw();
        }

        private void draw() {
            for (int i = 0; i < box.getContainerSize(); i++) box.setItem(i, ItemStack.EMPTY);
            box.setItem(4, Stacks.named(new ItemStack(Items.ECHO_SHARD), "§3§lCity Cash: §b" + fmt(balance(viewer.getUUID())) + " CC",
                    "§7Earned in the Ancient City.", "§7Echo shards in your bag: §3" + echoShards(viewer), "§8Weekly reset in " + untilReset() + " · map " + (mapIndex() + 1)));
            box.setItem(GEAR, Stacks.named(new ItemStack(Items.SNOWBALL), "§f§lCity Gear", "§7Rattles, lenses, tonics, ladders,", "§7compasses, Hush and Swift Sneak books", "§eClick"));
            box.setItem(PROG, Stacks.named(new ItemStack(Items.DIAMOND), "§b§lProgression", "§7Diamonds, golden apples, XP, Key Shards,", "§7enchanted books, Mending...",
                    "§8Shelves unlock with Ancient City escapes", "§eClick"));
            box.setItem(COLL, Stacks.named(new ItemStack(Items.MUSIC_DISC_OTHERSIDE), "§d§lCollecting", "§7Discs, Ancient Shards, trims", "§eClick"));
            box.setItem(DECO, Stacks.named(new ItemStack(Items.SCULK_CATALYST), "§3§lDecoration", "§7Sculk, candles, reinforced deepslate", "§eClick"));
            box.setItem(COSM, Stacks.named(new ItemStack(Items.NAME_TAG), "§3§lCosmetics", "§7Titles, a name colour, a trail", "§eClick"));
            box.setItem(SELL, Stacks.named(new ItemStack(Items.EMERALD), "§a§lSell to Ves", "§7Spare City loot for City Cash", "§eClick"));
            List<String> rl = new ArrayList<>();
            List<Request> reqs = requests();
            JsonObject w = wk(viewer.getUUID());
            for (int i = 0; i < reqs.size(); i++) {
                Request r = reqs.get(i);
                rl.add((w.has("req_" + i) ? "§a✔ " : "§7• ") + AncientCity.DIFF_COLORS[r.diff()] + AncientCity.DIFF_NAMES[r.diff()] + "+§7: " + condText(r));
                rl.add("   §7Reward: " + r.rewardName());
            }
            rl.add("§8Done at that difficulty or harder. New ones Thursday.");
            box.setItem(REQ, Stacks.named(new ItemStack(Items.WRITABLE_BOOK), "§6§lOld Ves's Requests", rl.toArray(new String[0])));
            Offer f = find();
            ItemStack fi = offerIcon(viewer, f, true);
            fi.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME, Component.literal("§6§lArchivist's Find: §r" + f.name()));
            box.setItem(FIND, fi);
            box.setItem(BOARD, Stacks.named(new ItemStack(Items.CLOCK), "§3§lThis Week's Leaderboard", "§7Fastest escapes per difficulty.", "§7Top 3: 500 / 300 / 150 CC.",
                    "§7#1 wears " + SHADOW_TAG + "§7next week.", "§eClick for the board in chat"));
            box.setItem(49, Stacks.named(new ItemStack(Items.BOOK), "§3Old Ves", "§7\"I keep what the city forgets.", "§7Bring me its echoes, and I'll", "§7make it worth your while.\""));
            frame(Stacks.named(new ItemStack(Items.STAINED_GLASS_PANE.pick(DyeColor.CYAN)), " "));
            broadcastChanges();
        }

        @Override
        protected void onClick(int slot, int button, boolean shift) {
            switch (slot) {
                case GEAR -> shelf(viewer, "§8City Gear", 0);
                case PROG -> shelf(viewer, "§8Progression", 1);
                case COLL -> shelf(viewer, "§8Collecting", 2);
                case DECO -> shelf(viewer, "§8Decoration", 3);
                case COSM -> shelf(viewer, "§8Cosmetics", 4);
                case SELL -> LockedMenu.openWithBack(viewer, () -> CityMarket.open(viewer), () -> LockedMenu.open(viewer, "§8Sell to Old Ves", 4, SellMenu::new));
                case FIND -> {
                    buy(viewer, find(), true);
                    draw();
                }
                case BOARD -> {
                    viewer.closeContainer();
                    Lobby.later(viewer, () -> showBoard(viewer));
                }
                default -> {}
            }
        }
    }

    private static List<Offer> shelfOffers(int shelf) {
        return switch (shelf) {
            case 0 -> gear();
            case 1 -> progression();
            case 2 -> collecting();
            case 3 -> decoration();
            default -> cosmetics();
        };
    }

    private static void shelf(ServerPlayer p, String title, int shelf) {
        LockedMenu.openWithBack(p, () -> CityMarket.open(p), () -> LockedMenu.open(p, title, 5, (id, inv, box, v) -> new Shelf(id, inv, box, v, shelf)));
    }

    private static final class Shelf extends LockedMenu {
        private final List<Offer> offers;

        Shelf(int id, Inventory inv, SimpleContainer box, ServerPlayer viewer, int shelf) {
            super(id, inv, box, 5, viewer);
            this.offers = shelfOffers(shelf);
            draw();
        }

        private void draw() {
            for (int i = 0; i < box.getContainerSize(); i++) box.setItem(i, ItemStack.EMPTY);
            box.setItem(4, Stacks.named(new ItemStack(Items.ECHO_SHARD), "§3City Cash: §b" + fmt(balance(viewer.getUUID())) + " CC",
                    "§7Echo shards in your bag: §3" + echoShards(viewer)));
            for (Offer o : offers) box.setItem(o.slot(), offerIcon(viewer, o, false));
            frame(Stacks.named(new ItemStack(Items.STAINED_GLASS_PANE.pick(DyeColor.CYAN)), " "));
            broadcastChanges();
        }

        @Override
        protected void onClick(int slot, int button, boolean shift) {
            for (Offer o : offers) if (o.slot() == slot) {
                buy(viewer, o, false);
                draw();
                return;
            }
        }
    }

    private static final class SellMenu extends LockedMenu {
        SellMenu(int id, Inventory inv, SimpleContainer box, ServerPlayer viewer) {
            super(id, inv, box, 4, viewer);
            draw();
        }

        private void draw() {
            for (int i = 0; i < box.getContainerSize(); i++) box.setItem(i, ItemStack.EMPTY);
            box.setItem(4, Stacks.named(new ItemStack(Items.ECHO_SHARD), "§3City Cash: §b" + fmt(balance(viewer.getUUID())) + " CC"));
            for (Sale s : sales())
                box.setItem(s.slot(), Stacks.named(new ItemStack(s.item() == null ? Items.SKULL_POTTERY_SHERD : s.item()), "§f" + s.name(),
                        "§7Ves pays §b" + s.each() + " CC §7per " + (s.per() > 1 ? s.per() : "one") + ".", "§eClick to sell all you carry"));
            frame(Stacks.named(new ItemStack(Items.STAINED_GLASS_PANE.pick(DyeColor.GREEN)), " "));
            broadcastChanges();
        }

        @Override
        protected void onClick(int slot, int button, boolean shift) {
            for (Sale s : sales()) if (s.slot() == slot) {
                sell(viewer, s);
                draw();
                return;
            }
        }
    }
}
