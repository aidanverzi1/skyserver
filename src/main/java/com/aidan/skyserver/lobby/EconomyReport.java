package com.aidan.skyserver.lobby;

import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.*;

/**
 * /ss economy (ops): how much money and how many keys SkyServer handed out today and over the
 * last 7 days, by where it came from (games, island, level-ups, battle pass, quests...). Read from
 * the money log (TxLog) on the background thread, so it never slows the server down.
 * Money moved by EconomyCraft itself (the shop, /pay) isn't in the log.
 */
public final class EconomyReport {
    private EconomyReport() {}

    public static void init() {
        CommandRegistrationCallback.EVENT.register((d, r, e) -> d.register(Commands.literal("ss")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(Commands.literal("economy").executes(c -> {
                    run(c.getSource());
                    return 1;
                }))));
    }

    private static final class Bucket {
        long in, out;
        final long[] keys = new long[5];
    }

    private static void run(CommandSourceStack src) {
        MinecraftServer server = src.getServer();
        src.sendSuccess(() -> Component.literal("§7Reading the money log..."), false);
        PlayerCache.io(() -> {
            List<String> lines = report();
            server.execute(() -> {
                for (String l : lines) src.sendSuccess(() -> Component.literal(l), false);
            });
        });
    }

    /** Builds the report (runs on the background thread). */
    static List<String> report() {
        LocalDate today = LocalDate.now(), weekStart = today.minusDays(6);
        Map<String, Bucket> day = new TreeMap<>(), week = new TreeMap<>();
        Set<String> files = new LinkedHashSet<>(List.of(weekStart.toString().substring(0, 7), today.toString().substring(0, 7)));
        for (String month : files) {
            Path f = Stacks.DIR.resolve("logs").resolve("transactions-" + month + ".txt");
            if (!Files.exists(f)) continue;
            List<String> all;
            try {
                all = Files.readAllLines(f, StandardCharsets.UTF_8);
            } catch (Exception ex) {
                continue;
            }
            for (String line : all) {
                if (line.length() < 12) continue;
                LocalDate when;
                try {
                    when = LocalDate.parse(line.substring(0, 10));
                } catch (Exception ex) {
                    continue;
                }
                if (when.isBefore(weekStart) || when.isAfter(today)) continue;
                int lb = line.lastIndexOf('['), rb = line.lastIndexOf(']');
                String src = lb >= 0 && rb > lb ? line.substring(lb + 1, rb) : "?";
                int dot = src.indexOf('.');
                if (dot > 0) src = src.substring(0, dot);
                String what = lb > 0 ? line.substring(0, lb).trim() : line;
                Bucket w = week.computeIfAbsent(src, k -> new Bucket());
                Bucket dd = when.equals(today) ? day.computeIfAbsent(src, k -> new Bucket()) : null;
                if (what.endsWith(" money")) {
                    String[] parts = what.substring(0, what.length() - 6).trim().split(" ");
                    String amt = parts[parts.length - 1];
                    long v;
                    try {
                        v = Long.parseLong(amt.replaceAll("[^0-9]", ""));
                    } catch (Exception ex) {
                        continue;
                    }
                    boolean neg = amt.contains("-");
                    if (neg) { w.out += v; if (dd != null) dd.out += v; } else { w.in += v; if (dd != null) dd.in += v; }
                } else if (what.endsWith(" key")) {
                    String[] parts = what.substring(0, what.length() - 4).trim().split(" ");
                    if (parts.length < 2) continue;
                    String tierName = parts[parts.length - 1];
                    int tier = -1;
                    for (int i = 0; i < Keys.NAMES.length; i++) if (Keys.NAMES[i].equalsIgnoreCase(tierName)) tier = i;
                    long n;
                    try {
                        n = Long.parseLong(parts[parts.length - 2].replace("+", ""));
                    } catch (Exception ex) {
                        continue;
                    }
                    if (tier >= 0 && n > 0) { w.keys[tier] += n; if (dd != null) dd.keys[tier] += n; }
                }
            }
        }
        List<String> out = new ArrayList<>();
        out.add("§6§lECONOMY REPORT §7(SkyServer payouts; EconomyCraft shop/pay not included)");
        section(out, "§eToday", day);
        section(out, "§eLast 7 days", week);
        out.add("§7Target: about §f$2,500 §7per hour of active play per player. Numbers to tune are in Rewards.java and Progression.java.");
        return out;
    }

    private static void section(List<String> out, String title, Map<String, Bucket> map) {
        long in = 0, outSum = 0;
        long[] keys = new long[5];
        for (Bucket b : map.values()) {
            in += b.in;
            outSum += b.out;
            for (int i = 0; i < 5; i++) keys[i] += b.keys[i];
        }
        out.add(title + ": §a+" + Economy.format(in) + " §7paid out, §c-" + Economy.format(outSum) + " §7spent, keys " + keyText(keys));
        List<Map.Entry<String, Bucket>> sorted = new ArrayList<>(map.entrySet());
        sorted.sort((a, b) -> Long.compare(b.getValue().in, a.getValue().in));
        for (Map.Entry<String, Bucket> e : sorted) {
            Bucket b = e.getValue();
            if (b.in == 0 && b.out == 0 && Arrays.stream(b.keys).sum() == 0) continue;
            out.add("  §f" + e.getKey() + "§7: §a+" + Economy.format(b.in) + (b.out > 0 ? " §c-" + Economy.format(b.out) : "") + " §7" + keyText(b.keys));
        }
    }

    private static String keyText(long[] k) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 5; i++) if (k[i] > 0) sb.append(Keys.COLORS[i]).append(k[i]).append(Keys.NAMES[i].charAt(0)).append(" ");
        return sb.length() == 0 ? "§8none" : sb.toString().trim();
    }
}
