package com.aidan.skyserver.lobby;

import com.aidan.skyserver.Skyserver;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

import java.lang.reflect.Method;
import java.time.LocalDate;
import java.util.*;

/**
 * Money, through the EconomyCraft mod's API (found at runtime, so the mod is not needed to compile).
 * If EconomyCraft isn't installed, paying still works through its "eco addmoney" command if some
 * other economy mod provides it, but balances can't be checked, so purchases are refused.
 */
public final class Economy {
    private static Object balances;      // com.reazip.economycraft.api.v1.BalanceApi
    private static Method getBalance, addMoney, removeMoney, successful;
    private static boolean looked = false;

    private Economy() {}

    private static boolean ready(MinecraftServer server) {
        if (balances != null) return true;
        if (looked && server == null) return false;
        looked = true;
        try {
            Class<?> api = Class.forName("com.reazip.economycraft.api.v1.EconomyCraftApi");
            Object instance = api.getMethod("get", MinecraftServer.class).invoke(null, server);
            if (instance == null) return false;
            balances = api.getMethod("balances").invoke(instance);
            Class<?> b = Class.forName("com.reazip.economycraft.api.v1.BalanceApi");
            getBalance = b.getMethod("getBalance", UUID.class);
            addMoney = b.getMethod("addMoney", UUID.class, long.class);
            removeMoney = b.getMethod("removeMoney", UUID.class, long.class);
            successful = Class.forName("com.reazip.economycraft.api.v1.BalanceMutationResult").getMethod("successful");
            Skyserver.LOGGER.info("Lobby: using EconomyCraft for money");
            return true;
        } catch (Throwable t) {
            balances = null;
            Skyserver.LOGGER.warn("Lobby: EconomyCraft API not found ({}); money rewards use the eco command instead", t.toString());
            return false;
        }
    }

    /** Balance, or -1 if unknown. */
    public static long balance(MinecraftServer server, UUID id) {
        if (!ready(server)) return -1;
        try {
            return (long) getBalance.invoke(balances, id);
        } catch (Throwable t) {
            return -1;
        }
    }

    public static boolean add(MinecraftServer server, UUID id, String name, long amount) {
        if (amount <= 0) return true;
        TxLog.money(id, name, amount);
        if (ready(server)) {
            try {
                return (boolean) successful.invoke(addMoney.invoke(balances, id, amount));
            } catch (Throwable ignored) {
            }
        }
        try {
            server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(), "eco addmoney " + name + " " + amount);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Takes money if they have enough. False = not enough (or economy unavailable). */
    public static boolean take(MinecraftServer server, UUID id, long amount) {
        if (amount <= 0) return true;
        if (!ready(server)) return false;
        try {
            if ((long) getBalance.invoke(balances, id) < amount) return false;
            boolean ok = (boolean) successful.invoke(removeMoney.invoke(balances, id, amount));
            if (ok) TxLog.money(id, null, -amount);
            return ok;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Bots log in like players, so EconomyCraft gives them an account and shows them on the money
     * board on the right. This deletes every bot account (bot ids are made from their names, so old
     * ones from earlier matches are found too) and refreshes the board.
     */
    public static void purgeBots(MinecraftServer server) {
        try {
            Class<?> ec = Class.forName("com.reazip.economycraft.EconomyCraft");
            Object manager = ec.getMethod("getManager", MinecraftServer.class).invoke(null, server);
            if (manager == null) return;
            java.lang.reflect.Field f = manager.getClass().getDeclaredField("balances");
            f.setAccessible(true);
            java.util.Map<?, ?> all = (java.util.Map<?, ?>) f.get(manager);
            Method bestName = manager.getClass().getMethod("getBestName", UUID.class);
            Method remove = manager.getClass().getMethod("removePlayer", UUID.class);
            int removed = 0;
            for (Object key : new java.util.ArrayList<>(all.keySet())) {
                if (!(key instanceof UUID id)) continue;
                boolean bot = com.aidan.skyserver.bots.BotManager.isBot(id);
                if (!bot) {
                    Object name = bestName.invoke(manager, id);
                    if (name instanceof String n && !n.isEmpty()) bot = id.equals(UUID.nameUUIDFromBytes(
                            ("skyserver-bot:" + n.toLowerCase(java.util.Locale.ROOT)).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
                }
                if (bot) {
                    remove.invoke(manager, id);
                    removed++;
                }
            }
            if (removed > 0) manager.getClass().getMethod("refreshLeaderboard").invoke(manager);
        } catch (Throwable t) {
            if (!purgeWarned) Skyserver.LOGGER.warn("Couldn't remove bot accounts from EconomyCraft ({})", t.toString());
            purgeWarned = true;
        }
    }

    private static boolean purgeWarned = false;

    // ---- Shop commands only where items are real ----

    /**
     * 5.8: "sell" and "servershop" added. Before this, /sell worked anywhere, so lobby and game kit
     * items (the compass, Skywars loot...) could be sold for money.
     */
    private static final java.util.Set<String> ITEM_COMMANDS = java.util.Set.of("shop", "ah", "auction", "orders", "deliveries", "menu", "sell", "servershop");

    /** Where EconomyCraft's item commands work: Sky Island (not the Nether Expedition), or survival when it's open. */
    private static boolean shopAllowedIn(net.minecraft.world.level.Level l) {
        return com.aidan.skyserver.island.Islands.isIsland(l) || (Lobby.SURVIVAL_OPEN && Lobby.isSurvival(l)
                && !com.aidan.skyserver.expedition.Expedition.isExpedition(l));
    }

    /** A command wrapper that only runs on Sky Island (or in survival when it's open). */
    private record Guarded(com.mojang.brigadier.Command<net.minecraft.commands.CommandSourceStack> inner)
            implements com.mojang.brigadier.Command<net.minecraft.commands.CommandSourceStack> {
        @Override
        public int run(com.mojang.brigadier.context.CommandContext<net.minecraft.commands.CommandSourceStack> c)
                throws com.mojang.brigadier.exceptions.CommandSyntaxException {
            net.minecraft.commands.CommandSourceStack src = c.getSource();
            ServerPlayerHolder: {
                net.minecraft.server.level.ServerPlayer p = src.getPlayer();
                if (p == null || shopAllowedIn(p.level())) break ServerPlayerHolder;
                if (net.minecraft.commands.Commands.hasPermission(net.minecraft.commands.Commands.LEVEL_GAMEMASTERS).test(src)) break ServerPlayerHolder;
                src.sendFailure(net.minecraft.network.chat.Component.literal(
                        "The shop, selling, auction house and orders work on your Sky Island (type /is). Items from the lobby, the games and Nether Expeditions in progress can't be sold."));
                return 0;
            }
            return inner.run(c);
        }
    }

    // ---- 5.8: per-item daily /sell caps (config/skyserver/sell-caps.json) ----

    private static final Map<String, Integer> SELL_CAPS = new LinkedHashMap<>();
    private static final Map<String, Integer> SOLD_TODAY = new HashMap<>(); // "uuid|item" -> count
    private static String soldDay = "";
    private static boolean capsLoaded = false;

    private static void loadCaps() {
        if (capsLoaded) return;
        capsLoaded = true;
        JsonObject o = Stacks.readFile("sell-caps.json");
        if (!o.has("caps")) {
            JsonObject def = new JsonObject();
            def.addProperty("_help", "Most of each item one player can /sell per day (on top of EconomyCraft's overall daily sell limit). Item id -> count.");
            JsonObject caps = new JsonObject();
            caps.addProperty("minecraft:wheat", 320);
            def.add("caps", caps);
            Stacks.writeFile("sell-caps.json", def);
            o = def;
        }
        for (Map.Entry<String, JsonElement> e : o.getAsJsonObject("caps").entrySet()) SELL_CAPS.put(e.getKey(), e.getValue().getAsInt());
        JsonObject sold = Stacks.readFile("sell-today.json");
        if (sold.has("day") && sold.get("day").getAsString().equals(LocalDate.now().toString())) {
            soldDay = sold.get("day").getAsString();
            for (Map.Entry<String, JsonElement> e : sold.getAsJsonObject("sold").entrySet()) SOLD_TODAY.put(e.getKey(), e.getValue().getAsInt());
        }
    }

    private static void rollDay() {
        String today = LocalDate.now().toString();
        if (!today.equals(soldDay)) {
            soldDay = today;
            SOLD_TODAY.clear();
        }
    }

    private static Map<String, Integer> cappedCounts(Inventory inv) {
        Map<String, Integer> out = new HashMap<>();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (s.isEmpty()) continue;
            String id = BuiltInRegistries.ITEM.getKey(s.getItem()).toString();
            if (SELL_CAPS.containsKey(id)) out.merge(id, s.getCount(), Integer::sum);
        }
        return out;
    }

    /** 5.20: never bought by the shop (Sunken Monument treasures, so the expedition can't be farmed for cash). */
    public static final java.util.Set<String> UNSELLABLE = java.util.Set.of("minecraft:heart_of_the_sea", "minecraft:conduit",
            "minecraft:tide_armor_trim_smithing_template", "minecraft:nautilus_shell", "minecraft:sea_lantern", "minecraft:sponge",
            "minecraft:wet_sponge", "minecraft:trident");

    /** Wraps /sell: refuses an item that hit its daily cap, and counts what was sold. */
    private record SellCapped(com.mojang.brigadier.Command<net.minecraft.commands.CommandSourceStack> inner)
            implements com.mojang.brigadier.Command<net.minecraft.commands.CommandSourceStack> {
        @Override
        public int run(com.mojang.brigadier.context.CommandContext<net.minecraft.commands.CommandSourceStack> c)
                throws com.mojang.brigadier.exceptions.CommandSyntaxException {
            ServerPlayer p = c.getSource().getPlayer();
            if (p == null) return inner.run(c);
            // 5.20: Sunken Monument treasures can't be sold to the shop at all: they're set aside while /sell runs.
            ItemStack heldNow = p.getMainHandItem();
            if (!heldNow.isEmpty() && UNSELLABLE.contains(BuiltInRegistries.ITEM.getKey(heldNow.getItem()).toString())) {
                c.getSource().sendFailure(net.minecraft.network.chat.Component.literal(heldNow.getHoverName().getString()
                        + " can't be sold to the shop. Trade it to players with /shop or /orders instead."));
                return 0;
            }
            Map<Integer, ItemStack> hidden = new HashMap<>();
            Inventory hinv = p.getInventory();
            for (int i = 0; i < hinv.getContainerSize(); i++) {
                ItemStack st = hinv.getItem(i);
                if (!st.isEmpty() && UNSELLABLE.contains(BuiltInRegistries.ITEM.getKey(st.getItem()).toString())) {
                    hidden.put(i, st.copy());
                    hinv.setItem(i, ItemStack.EMPTY);
                }
            }
            try {
                return capped(c, p);
            } finally {
                for (Map.Entry<Integer, ItemStack> h : hidden.entrySet()) {
                    if (hinv.getItem(h.getKey()).isEmpty()) hinv.setItem(h.getKey(), h.getValue());
                    else if (!hinv.add(h.getValue()) || !h.getValue().isEmpty()) p.drop(h.getValue(), false, net.minecraft.util.Prediction.SERVER_ONLY);
                }
            }
        }

        private int capped(com.mojang.brigadier.context.CommandContext<net.minecraft.commands.CommandSourceStack> c, ServerPlayer p)
                throws com.mojang.brigadier.exceptions.CommandSyntaxException {
            loadCaps();
            rollDay();
            ItemStack held = p.getMainHandItem();
            String heldId = held.isEmpty() ? null : BuiltInRegistries.ITEM.getKey(held.getItem()).toString();
            if (heldId != null && SELL_CAPS.containsKey(heldId) && SOLD_TODAY.getOrDefault(p.getUUID() + "|" + heldId, 0) >= SELL_CAPS.get(heldId)) {
                c.getSource().sendFailure(net.minecraft.network.chat.Component.literal("You've sold the most " + held.getHoverName().getString()
                        + " allowed today (" + SELL_CAPS.get(heldId) + "). Try again tomorrow, or sell it to players with /shop or /orders."));
                return 0;
            }
            Map<String, Integer> before = cappedCounts(p.getInventory());
            int r = inner.run(c);
            Map<String, Integer> after = cappedCounts(p.getInventory());
            boolean changed = false;
            for (Map.Entry<String, Integer> e : before.entrySet()) {
                int sold = e.getValue() - after.getOrDefault(e.getKey(), 0);
                if (sold > 0) {
                    SOLD_TODAY.merge(p.getUUID() + "|" + e.getKey(), sold, Integer::sum);
                    changed = true;
                }
            }
            if (changed) {
                JsonObject o = new JsonObject(), sold = new JsonObject();
                o.addProperty("day", soldDay);
                SOLD_TODAY.forEach(sold::addProperty);
                o.add("sold", sold);
                PlayerCache.io(() -> Stacks.writeFile("sell-today.json", o));
            }
            return r;
        }
    }

    /**
     * EconomyCraft's shop, selling, auction house, orders and deliveries move real items, so they only
     * work on Sky Island: otherwise lobby items (the compass!) and game kits could be sold for money,
     * and bought items would vanish in the lobby. Balance, /pay and /daily still work anywhere.
     * Called at startup and after /reload (which rebuilds the command tree).
     */
    public static void guardShopCommands(MinecraftServer server) {
        try {
            var root = server.getCommands().getDispatcher().getRoot();
            java.lang.reflect.Field cmd = com.mojang.brigadier.tree.CommandNode.class.getDeclaredField("command");
            cmd.setAccessible(true);
            int n = 0;
            for (var node : root.getChildren()) {
                if (ITEM_COMMANDS.contains(node.getName())) n += guardTree(node, cmd, new java.util.HashSet<>(), node.getName().equals("sell"));
                if (node.getName().equals("eco")) {
                    n += guardNode(node, cmd, false); // "/eco" on its own opens the menu (which has the shop in it)
                    for (var child : node.getChildren()) if (ITEM_COMMANDS.contains(child.getName()))
                        n += guardTree(child, cmd, new java.util.HashSet<>(), child.getName().equals("sell"));
                }
            }
            if (n > 0) Skyserver.LOGGER.info("EconomyCraft shop commands limited to Sky Island ({} command parts).", n);
        } catch (Throwable t) {
            Skyserver.LOGGER.warn("Couldn't limit the EconomyCraft shop to Sky Island: {}", t.toString());
        }
    }

    @SuppressWarnings("unchecked")
    private static int guardNode(com.mojang.brigadier.tree.CommandNode<net.minecraft.commands.CommandSourceStack> node, java.lang.reflect.Field cmd, boolean sellCaps)
            throws IllegalAccessException {
        var c = (com.mojang.brigadier.Command<net.minecraft.commands.CommandSourceStack>) cmd.get(node);
        if (c == null || c instanceof Guarded) return 0;
        cmd.set(node, new Guarded(sellCaps ? new SellCapped(c) : c));
        return 1;
    }

    private static int guardTree(com.mojang.brigadier.tree.CommandNode<net.minecraft.commands.CommandSourceStack> node, java.lang.reflect.Field cmd,
                                 java.util.Set<Object> seen, boolean sellCaps) throws IllegalAccessException {
        if (!seen.add(node)) return 0;
        int n = guardNode(node, cmd, sellCaps);
        for (var child : node.getChildren()) n += guardTree(child, cmd, seen, sellCaps);
        return n;
    }

    public static String format(long amount) {
        return "$" + String.format("%,d", amount);
    }
}
