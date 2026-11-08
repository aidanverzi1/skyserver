package com.aidan.skyserver.lobby;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.commands.Commands;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.time.LocalDate;
import java.util.*;

/**
 * The Bank (in the Town, or /bank anywhere). Money in the bank earns 1% interest once every 7 days,
 * on the first $250,000. Interest is paid when you're online; if you've been away longer than a
 * week you get one payment, not one for every week you missed. It's paid on the smallest amount
 * you had in the bank during the week, so depositing the day before doesn't count for the full week.
 * Withdraw any time. Saved in config/skyserver/bank.json.
 */
public final class Bank {
    public static final double RATE = 0.01;
    public static final int DAYS = 7;
    public static final long CAP = 250_000;

    /** {balance, lowest balance this week, day the week started}. */
    private static final Map<UUID, long[]> ACCOUNTS = new HashMap<>();
    private static boolean loaded = false;

    private Bank() {}

    public static void init() {
        CommandRegistrationCallback.EVENT.register((d, r, e) -> d.register(Commands.literal("bank")
                .executes(c -> { show(c.getSource().getPlayerOrException()); return 1; })
                .then(Commands.literal("deposit").then(Commands.argument("amount", StringArgumentType.word())
                        .executes(c -> { deposit(c.getSource().getPlayerOrException(), StringArgumentType.getString(c, "amount")); return 1; })))
                .then(Commands.literal("withdraw").then(Commands.argument("amount", StringArgumentType.word())
                        .executes(c -> { withdraw(c.getSource().getPlayerOrException(), StringArgumentType.getString(c, "amount")); return 1; })))));
        // Interest check every minute for online players.
        ServerTickEvents.END_SERVER_TICK.register(s -> {
            if (s.getTickCount() % 1200 != 0) return;
            com.aidan.skyserver.Safe.run("bank interest", () -> {
                for (ServerPlayer p : s.getPlayerList().getPlayers()) if (!(p instanceof com.aidan.skyserver.bots.BotPlayer)) interest(p);
            });
        });
    }

    private static long today() {
        return LocalDate.now().toEpochDay();
    }

    private static long[] account(UUID id) {
        load();
        return ACCOUNTS.computeIfAbsent(id, k -> new long[]{0, 0, today()});
    }

    public static long balance(UUID id) {
        return account(id)[0];
    }

    /** Pays the week's interest if a week has passed (only ever one payment at a time). */
    static void interest(ServerPlayer p) {
        long[] a = account(p.getUUID());
        if (today() - a[2] < DAYS) return;
        long base = Math.min(a[1], CAP);
        long pay = Math.round(base * RATE);
        a[2] = today();
        a[1] = a[0];
        if (pay > 0) {
            a[0] += pay;
            a[1] = a[0];
            Lobby.msg(p, "§6§lBANK: §aYou earned " + Economy.format(pay) + " interest §7(1% of " + Economy.format(base) + "). Balance: §6" + Economy.format(a[0]));
        }
        save();
    }

    private static long parse(ServerPlayer p, String text, long max) {
        if (text.equalsIgnoreCase("all")) return max;
        try {
            String t = text.replace(",", "").replace("$", "").toLowerCase(Locale.ROOT);
            double mult = t.endsWith("k") ? 1_000 : t.endsWith("m") ? 1_000_000 : 1;
            if (mult > 1) t = t.substring(0, t.length() - 1);
            return Math.round(Double.parseDouble(t) * mult);
        } catch (NumberFormatException e) {
            Lobby.msg(p, "§cThat's not an amount. Try §e/bank deposit 5000§c, §e5k§c or §eall§c.");
            return -1;
        }
    }

    static void deposit(ServerPlayer p, String text) {
        MinecraftServer server = p.level().getServer();
        long wallet = Math.max(0, Economy.balance(server, p.getUUID()));
        long amount = parse(p, text, wallet);
        if (amount <= 0) return;
        if (!Economy.take(server, p.getUUID(), amount)) {
            Lobby.msg(p, "§cYou only have " + Economy.format(wallet) + ".");
            return;
        }
        long[] a = account(p.getUUID());
        boolean fresh = a[0] == 0;
        a[0] += amount;
        if (fresh) {
            a[2] = today(); // a new week starts with your first deposit
            a[1] = a[0];
        }
        save();
        Lobby.msg(p, "§6Deposited " + Economy.format(amount) + ". §7Bank balance: §6" + Economy.format(a[0]) + "§7. " + nextText(a));
    }

    static void withdraw(ServerPlayer p, String text) {
        long[] a = account(p.getUUID());
        long amount = parse(p, text, a[0]);
        if (amount <= 0) return;
        if (amount > a[0]) {
            Lobby.msg(p, "§cYou only have " + Economy.format(a[0]) + " in the bank.");
            return;
        }
        a[0] -= amount;
        a[1] = Math.min(a[1], a[0]);
        Economy.add(p.level().getServer(), p.getUUID(), p.getName().getString(), amount);
        save();
        Lobby.msg(p, "§6Withdrew " + Economy.format(amount) + ". §7Bank balance: §6" + Economy.format(a[0]));
    }

    private static String nextText(long[] a) {
        long days = Math.max(0, a[2] + DAYS - today());
        return "Next interest in " + days + " day" + (days == 1 ? "" : "s") + " (1% of the lowest balance this week, up to " + Economy.format(CAP) + ").";
    }

    public static void show(ServerPlayer p) {
        interest(p);
        long[] a = account(p.getUUID());
        Lobby.msg(p, "§6§lBANK §7- balance §6" + Economy.format(a[0]) + "§7. " + nextText(a));
        Lobby.msg(p, "§e/bank deposit <amount|all> §7| §e/bank withdraw <amount|all>");
    }

    private static void load() {
        if (loaded) return;
        loaded = true;
        JsonObject root = Stacks.readFile("bank.json");
        for (Map.Entry<String, JsonElement> e : root.entrySet()) {
            var arr = e.getValue().getAsJsonArray();
            ACCOUNTS.put(UUID.fromString(e.getKey()), new long[]{arr.get(0).getAsLong(), arr.get(1).getAsLong(), arr.get(2).getAsLong()});
        }
    }

    private static void save() {
        JsonObject root = new JsonObject();
        ACCOUNTS.forEach((k, v) -> {
            var arr = new com.google.gson.JsonArray();
            for (long x : v) arr.add(x);
            root.add(k.toString(), arr);
        });
        Stacks.writeFile("bank.json", root);
    }
}
