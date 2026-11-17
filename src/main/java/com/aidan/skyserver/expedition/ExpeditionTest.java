package com.aidan.skyserver.expedition;

import com.aidan.skyserver.Safe;
import com.aidan.skyserver.Skyserver;
import com.aidan.skyserver.bots.BotPlayer;
import com.aidan.skyserver.island.Islands;
import com.aidan.skyserver.lobby.Lobby;
import com.aidan.skyserver.lobby.Stacks;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.Level;

import java.util.*;

/**
 * 5.25: OP TEST TRIPS for every expedition - /ss test <nether|end|city|sunken> [easy|normal|hard|nightmare] [player] [player] [player]
 * (no players = the op alone; the End has no difficulty, the word is ignored there).
 *
 *  - Every test player must be on the island world. Their island inventory, XP and the rest are SAVED (to
 *    expedition-tests.json, so a crash or restart loses nothing) and their inventory is emptied.
 *  - The trip starts right away: no fare, no deposit, no ticket, no ship room, no muster.
 *  - When they land, they get the GEAR a player should bring for that expedition and difficulty (kit()).
 *  - NO REWARDS of any kind: money, keys, tokens, pass XP, server XP, skill XP, City Cash, stamps, stats, quests and
 *    mail are all blocked for a test player (isTesting() is checked in each of those places), and anything they
 *    carry home is wiped when their own inventory comes back. Money they spend inside an expedition (revives) is free.
 *  - As soon as they're back on the island world (trip over, went home, died, logged out and back in), their own
 *    inventory comes back. /ss untest <player> forces that (only on the island world).
 */
public final class ExpeditionTest {
    private ExpeditionTest() {}

    static final String FILE = "expedition-tests.json";
    static final String[] KINDS = {"nether", "end", "city", "sunken"};
    static final String[] DIFFS = {"easy", "normal", "hard", "nightmare"};

    private static final class T {
        String kind;
        int diff;
        boolean arrived, kit;
        long started;
        JsonObject saved;
    }

    private static final Map<UUID, T> TESTS = new HashMap<>();
    private static boolean loaded;
    private static MinecraftServer server;

    /** Is this player on a test trip (from the command until their own inventory is back)? */
    public static boolean isTesting(UUID id) {
        if (id == null) return false;
        load();
        return TESTS.containsKey(id);
    }

    /** Money a test player spends inside an expedition (a revive) is free. */
    public static boolean freeSpend(UUID id) {
        if (!isTesting(id) || server == null) return false;
        ServerPlayer p = server.getPlayerList().getPlayer(id);
        return p != null && expeditionWorld(p.level()) != null;
    }

    public static void init() {
        ServerLifecycleEvents.SERVER_STARTED.register(s -> server = s);
        ServerTickEvents.END_SERVER_TICK.register(s -> {
            if (s.getTickCount() % 5 != 0) return;
            load();
            if (!TESTS.isEmpty()) Safe.run("expedition test tick", ExpeditionTest::tick);
        });
        CommandRegistrationCallback.EVENT.register((d, r, e) -> d.register(Commands.literal("ss")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(Commands.literal("test").then(Commands.argument("expedition", StringArgumentType.word())
                        .suggests((c, b) -> { for (String x : KINDS) b.suggest(x); return b.buildFuture(); })
                        .executes(c -> start(c, "easy", 0))
                        .then(Commands.argument("difficulty", StringArgumentType.word())
                                .suggests((c, b) -> { for (String x : DIFFS) b.suggest(x); return b.buildFuture(); })
                                .executes(c -> start(c, StringArgumentType.getString(c, "difficulty"), 0))
                                .then(Commands.argument("p1", EntityArgument.player())
                                        .executes(c -> start(c, StringArgumentType.getString(c, "difficulty"), 1))
                                        .then(Commands.argument("p2", EntityArgument.player())
                                                .executes(c -> start(c, StringArgumentType.getString(c, "difficulty"), 2))
                                                .then(Commands.argument("p3", EntityArgument.player())
                                                        .executes(c -> start(c, StringArgumentType.getString(c, "difficulty"), 3))))))))
                .then(Commands.literal("untest").then(Commands.argument("player", EntityArgument.player()).executes(c -> {
                    ServerPlayer p = EntityArgument.getPlayer(c, "player");
                    if (!isTesting(p.getUUID())) {
                        c.getSource().sendFailure(Component.literal(p.getName().getString() + " isn't on a test trip."));
                        return 0;
                    }
                    if (!Islands.isIsland(p.level())) {
                        c.getSource().sendFailure(Component.literal(p.getName().getString() + " must be on the island world first (end their trip: /sunken admin end, /city admin end, /endexp admin end, /expedition admin endall)."));
                        return 0;
                    }
                    restore(p);
                    c.getSource().sendSuccess(() -> Component.literal("Gave " + p.getName().getString() + " their own inventory back."), true);
                    return 1;
                })))));
    }

    // =====================================================================
    // Starting
    // =====================================================================

    private static int start(CommandContext<CommandSourceStack> c, String diffWord, int players) throws CommandSyntaxException {
        CommandSourceStack src = c.getSource();
        String kind = StringArgumentType.getString(c, "expedition").toLowerCase(Locale.ROOT);
        if (!Arrays.asList(KINDS).contains(kind)) {
            src.sendFailure(Component.literal("Unknown expedition '" + kind + "'. Use nether, end, city or sunken."));
            return 0;
        }
        int d = switch (diffWord.toLowerCase(Locale.ROOT)) {
            case "easy", "0" -> 0;
            case "normal", "medium", "1" -> 1;
            case "hard", "2" -> 2;
            case "nightmare", "3" -> 3;
            default -> -1;
        };
        if (d < 0) {
            src.sendFailure(Component.literal("Unknown difficulty '" + diffWord + "'. Use easy, normal, hard or nightmare."));
            return 0;
        }
        List<ServerPlayer> crew = new ArrayList<>();
        if (players == 0) crew.add(src.getPlayerOrException());
        for (int i = 1; i <= players; i++) {
            ServerPlayer p = EntityArgument.getPlayer(c, "p" + i);
            if (!crew.contains(p)) crew.add(p);
        }
        for (ServerPlayer p : crew) {
            String why = p instanceof BotPlayer ? "is a bot" : isTesting(p.getUUID()) ? "is already on a test trip"
                    : !Islands.isIsland(p.level()) ? "must be on the island world" : onAnyTrip(p.getUUID()) ? "is already on an expedition" : null;
            if (why != null) {
                src.sendFailure(Component.literal(p.getName().getString() + " " + why + "."));
                return 0;
            }
        }
        // Save everyone first (so nothing they earn from here on counts), then start the trip.
        for (ServerPlayer p : crew) save(p, kind, d);
        String err;
        try {
            err = switch (kind) {
                case "nether" -> Expedition.startTest(crew, d);
                case "end" -> EndExpedition.startTest(crew);
                case "city" -> AncientCity.startTest(crew, d);
                default -> SunkenMonument.startTest(crew, d);
            };
        } catch (RuntimeException e) {
            Skyserver.LOGGER.error("[Test] couldn't start a {} test", kind, e);
            err = "it crashed (" + e.getClass().getSimpleName() + ") - see the console";
        }
        if (err != null) {
            for (ServerPlayer p : crew) restore(p);
            src.sendFailure(Component.literal("The test didn't start: " + err));
            return 0;
        }
        String what = kind.equals("end") ? "End Expedition" : DIFFS[d] + " " + (kind.equals("nether") ? "Nether Expedition" : kind.equals("city") ? "Ancient City" : "Sunken Monument");
        List<String> names = new ArrayList<>();
        for (ServerPlayer p : crew) names.add(p.getName().getString());
        for (ServerPlayer p : crew) {
            Lobby.msg(p, "§b§l[TEST TRIP] §f" + what + " §7- your own inventory is saved and you'll get it back when you're home. "
                    + "The gear for this trip arrives when you land. §cNo rewards §7on a test trip, and anything you bring home is wiped.");
            p.closeContainer();
        }
        src.sendSuccess(() -> Component.literal("Started a test " + what + " for " + String.join(", ", names) + "."), true);
        Skyserver.LOGGER.info("[Test] {} test started for {}", what, names);
        return 1;
    }

    private static boolean onAnyTrip(UUID id) {
        return Expedition.onTrip(id) || EndExpedition.onTrip(id) || AncientCity.onTrip(id) || SunkenMonument.onTrip(id);
    }

    private static boolean onTrip(String kind, UUID id) {
        return switch (kind) {
            case "nether" -> Expedition.onTrip(id);
            case "end" -> EndExpedition.onTrip(id);
            case "city" -> AncientCity.onTrip(id);
            default -> SunkenMonument.onTrip(id);
        };
    }

    /** Which expedition this world belongs to (null = none). */
    private static String expeditionWorld(Level l) {
        if (Expedition.isExpedition(l)) return "nether";
        if (EndExpedition.isEnd(l)) return "end";
        if (AncientCity.isCity(l)) return "city";
        if (SunkenMonument.isSunken(l)) return "sunken";
        return null;
    }

    // =====================================================================
    // Every quarter second: the kit on landing, the inventory back at home
    // =====================================================================

    private static void tick() {
        if (server == null) return;
        boolean dirty = false;
        for (Map.Entry<UUID, T> e : new ArrayList<>(TESTS.entrySet())) {
            ServerPlayer p = server.getPlayerList().getPlayer(e.getKey());
            if (p == null) continue;
            T t = e.getValue();
            String world = expeditionWorld(p.level());
            if (t.kind.equals(world)) {
                if (!t.arrived) {
                    t.arrived = true;
                    dirty = true;
                }
                if (!t.kit && p.isAlive()) {
                    t.kit = true;
                    dirty = true;
                    giveKit(p, t.kind, t.diff);
                }
                continue;
            }
            if (!Islands.isIsland(p.level())) continue; // the inventory only ever comes back on the island world
            if (t.arrived || !onTrip(t.kind, p.getUUID())) restore(p);
        }
        if (dirty) write();
    }

    // =====================================================================
    // Saving and restoring the player's own things
    // =====================================================================

    private static void save(ServerPlayer p, String kind, int d) {
        load();
        MinecraftServer s = p.level().getServer();
        JsonObject o = new JsonObject();
        JsonArray items = new JsonArray();
        Inventory inv = p.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack st = inv.getItem(i);
            if (st.isEmpty()) continue;
            JsonObject it = new JsonObject();
            it.addProperty("slot", i);
            it.add("item", Stacks.toJson(s, st));
            items.add(it);
        }
        o.add("items", items);
        o.addProperty("xpLevel", p.experienceLevel);
        o.addProperty("xpProgress", p.experienceProgress);
        o.addProperty("xpTotal", p.totalExperience);
        o.addProperty("health", p.getHealth());
        o.addProperty("food", p.getFoodData().getFoodLevel());
        T t = new T();
        t.kind = kind;
        t.diff = d;
        t.started = System.currentTimeMillis();
        t.saved = o;
        TESTS.put(p.getUUID(), t);
        write();
        inv.clearContent();
        p.removeAllEffects();
        p.setHealth(p.getMaxHealth());
        p.getFoodData().setFoodLevel(20);
        p.getFoodData().setSaturation(5f);
    }

    static void restore(ServerPlayer p) {
        T t = TESTS.remove(p.getUUID());
        if (t == null) return;
        write();
        MinecraftServer s = p.level().getServer();
        Inventory inv = p.getInventory();
        inv.clearContent();
        JsonObject o = t.saved;
        try {
            if (o.has("items")) for (JsonElement e : o.getAsJsonArray("items")) {
                JsonObject it = e.getAsJsonObject();
                int slot = it.get("slot").getAsInt();
                if (slot >= 0 && slot < inv.getContainerSize()) inv.setItem(slot, Stacks.fromJson(s, it.get("item")));
            }
            p.experienceLevel = o.get("xpLevel").getAsInt();
            p.experienceProgress = o.get("xpProgress").getAsFloat();
            p.totalExperience = o.get("xpTotal").getAsInt();
            p.connection.send(new net.minecraft.network.protocol.game.ClientboundSetExperiencePacket(p.experienceProgress, p.totalExperience, p.experienceLevel));
        } catch (RuntimeException ex) {
            Skyserver.LOGGER.error("[Test] couldn't fully restore {}'s inventory - their saved copy is in the log line below", p.getName().getString(), ex);
            Skyserver.LOGGER.error("[Test] saved copy: {}", o);
        }
        p.removeAllEffects();
        p.clearFire();
        p.setHealth(p.getMaxHealth());
        p.getFoodData().setFoodLevel(20);
        p.setAirSupply(p.getMaxAirSupply());
        p.inventoryMenu.broadcastChanges();
        Lobby.msg(p, "§b§l[TEST TRIP] §7Over - your own inventory is back.");
    }

    private static void load() {
        if (loaded) return;
        loaded = true;
        JsonObject root = Stacks.readFile(FILE);
        for (Map.Entry<String, JsonElement> e : root.entrySet()) {
            try {
                JsonObject o = e.getValue().getAsJsonObject();
                T t = new T();
                t.kind = o.get("kind").getAsString();
                t.diff = o.get("diff").getAsInt();
                t.arrived = o.get("arrived").getAsBoolean();
                t.kit = o.get("kit").getAsBoolean();
                t.started = o.get("started").getAsLong();
                t.saved = o.getAsJsonObject("saved");
                TESTS.put(UUID.fromString(e.getKey()), t);
            } catch (RuntimeException ex) {
                Skyserver.LOGGER.error("[Test] a saved test inventory in {} couldn't be read: {}", FILE, e.getKey());
            }
        }
    }

    private static void write() {
        JsonObject root = new JsonObject();
        TESTS.forEach((id, t) -> {
            JsonObject o = new JsonObject();
            o.addProperty("kind", t.kind);
            o.addProperty("diff", t.diff);
            o.addProperty("arrived", t.arrived);
            o.addProperty("kit", t.kit);
            o.addProperty("started", t.started);
            o.add("saved", t.saved);
            root.add(id.toString(), o);
        });
        Stacks.writeFile(FILE, root);
    }

    // =====================================================================
    // The gear a player should bring (given on landing)
    // =====================================================================

    private static void giveKit(ServerPlayer p, String kind, int d) {
        ServerLevel l = (ServerLevel) p.level();
        List<ItemStack> items = new ArrayList<>();
        ItemStack[] armor;
        switch (kind) {
            case "nether" -> {
                boolean nh = d >= 2;
                int prot = new int[]{3, 4, 4, 4}[d];
                armor = armor(l, nh, prot, d >= 1 ? 4 : 0);
                ItemStack sword = ench(l, new ItemStack(nh ? Items.NETHERITE_SWORD : Items.DIAMOND_SWORD), Enchantments.SHARPNESS, new int[]{3, 4, 5, 5}[d]);
                ench(l, sword, Enchantments.UNBREAKING, 3);
                if (d >= 2) ench(l, sword, Enchantments.FIRE_ASPECT, 2);
                if (d >= 3) ench(l, sword, Enchantments.LOOTING, 3);
                items.add(sword);
                items.add(bow(l, new int[]{3, 4, 5, 5}[d], d >= 2 ? 1 : 0));
                items.add(new ItemStack(Items.DIAMOND_PICKAXE));
                items.add(new ItemStack(Items.GOLDEN_APPLE, new int[]{5, 5, 10, 16}[d]));
                if (d >= 2) items.add(new ItemStack(Items.ENCHANTED_GOLDEN_APPLE, d == 2 ? 2 : 4));
                stacks(items, Items.GOLDEN_CARROT, new int[]{48, 48, 64, 128}[d]);
                stacks(items, Items.COOKED_BEEF, 32);
                stacks(items, Items.ARROW, 128);
                stacks(items, Items.COBBLESTONE, 256);
            }
            case "end" -> {
                armor = armor(l, true, 4, 4);
                ItemStack sword = ench(l, new ItemStack(Items.NETHERITE_SWORD), Enchantments.SHARPNESS, 5);
                ench(l, sword, Enchantments.UNBREAKING, 3);
                items.add(sword);
                items.add(bow(l, 5, 1));
                items.add(new ItemStack(Items.DIAMOND_PICKAXE));
                items.add(new ItemStack(Items.ENDER_PEARL, 16));
                items.add(new ItemStack(Items.GOLDEN_APPLE, 10));
                stacks(items, Items.GOLDEN_CARROT, 64);
                stacks(items, Items.COOKED_BEEF, 32);
                stacks(items, Items.ARROW, 128);
                stacks(items, Items.COBBLESTONE, 128);
            }
            case "city" -> {
                armor = armor(l, false, 2, 0);
                Holder<Enchantment> hush = CityMarket.hush(l.getServer());
                if (hush != null) armor[3].enchant(hush, new int[]{1, 2, 2, 3}[d]);
                items.add(new ItemStack(Items.RECOVERY_COMPASS));
                items.add(CityMarket.rattles(2));
                items.add(CityMarket.ladders());
                items.add(new ItemStack(Items.COBBLESTONE, 16));
                items.add(new ItemStack(Items.DIAMOND_PICKAXE));
                items.add(new ItemStack(Items.GOLDEN_APPLE, 3));
                items.add(new ItemStack(Items.COOKED_BEEF, 16));
            }
            default -> { // sunken
                armor = armor(l, false, new int[]{2, 3, 4, 4}[d], 0);
                ench(l, armor[0], Enchantments.RESPIRATION, 3);
                ench(l, armor[0], Enchantments.AQUA_AFFINITY, 1);
                ench(l, armor[3], Enchantments.DEPTH_STRIDER, 3);
                if (d >= 2) {
                    ItemStack t = ench(l, new ItemStack(Items.TRIDENT), Enchantments.IMPALING, d == 2 ? 4 : 5);
                    ench(l, t, Enchantments.LOYALTY, 3);
                    ench(l, t, Enchantments.UNBREAKING, 3);
                    items.add(t);
                } else {
                    ItemStack sword = ench(l, new ItemStack(Items.DIAMOND_SWORD), Enchantments.SHARPNESS, d == 0 ? 3 : 4);
                    ench(l, sword, Enchantments.UNBREAKING, 3);
                    items.add(sword);
                }
                items.add(new ItemStack(Items.DIAMOND_PICKAXE));
                items.add(new ItemStack(Items.GOLDEN_APPLE, new int[]{5, 5, 10, 16}[d]));
                stacks(items, Items.GOLDEN_CARROT, new int[]{48, 48, 64, 128}[d]);
            }
        }
        EquipmentSlot[] slots = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};
        for (int i = 0; i < 4; i++) {
            if (p.getItemBySlot(slots[i]).isEmpty()) p.setItemSlot(slots[i], armor[i]);
            else items.add(armor[i]);
        }
        for (ItemStack s : items) EndRewards.give(p, s);
        Lobby.msg(p, "§b§l[TEST TRIP] §7Here's the gear for this trip. Good luck!");
    }

    private static ItemStack[] armor(ServerLevel l, boolean netherite, int prot, int featherFalling) {
        Item[] pieces = netherite ? new Item[]{Items.NETHERITE_HELMET, Items.NETHERITE_CHESTPLATE, Items.NETHERITE_LEGGINGS, Items.NETHERITE_BOOTS}
                : new Item[]{Items.DIAMOND_HELMET, Items.DIAMOND_CHESTPLATE, Items.DIAMOND_LEGGINGS, Items.DIAMOND_BOOTS};
        ItemStack[] out = new ItemStack[4];
        for (int i = 0; i < 4; i++) {
            out[i] = new ItemStack(pieces[i]);
            if (prot > 0) ench(l, out[i], Enchantments.PROTECTION, prot);
            ench(l, out[i], Enchantments.UNBREAKING, 3);
        }
        if (featherFalling > 0) ench(l, out[3], Enchantments.FEATHER_FALLING, featherFalling);
        return out;
    }

    private static ItemStack bow(ServerLevel l, int power, int punch) {
        ItemStack b = ench(l, new ItemStack(Items.BOW), Enchantments.POWER, power);
        if (punch > 0) ench(l, b, Enchantments.PUNCH, punch);
        return ench(l, b, Enchantments.UNBREAKING, 3);
    }

    private static ItemStack ench(ServerLevel l, ItemStack s, ResourceKey<Enchantment> key, int lvl) {
        ExpeditionLoot.enchant(l, s, key, lvl);
        return s;
    }

    private static void stacks(List<ItemStack> out, Item item, int count) {
        while (count > 0) {
            int n = Math.min(64, count);
            out.add(new ItemStack(item, n));
            count -= n;
        }
    }
}
