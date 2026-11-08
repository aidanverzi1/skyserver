package com.aidan.skyserver.lobby;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Money and key log. Every time SkyServer pays or charges money, or gives or takes keys, a line goes
 * into config/skyserver/logs/transactions-YYYY-MM.txt (one file per month), with what did it.
 * /ss log <player> shows someone's last 15. (Money moved by EconomyCraft's own commands - /pay,
 * /eco, the shop - is in EconomyCraft's files, not here.)
 */
public final class TxLog {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final Deque<String[]> RECENT = new ArrayDeque<>(); // {uuid, line}
    private static final int KEEP = 4000;

    private TxLog() {}

    public static void money(UUID id, String name, long amount) {
        write(id, (name == null ? id.toString() : name) + " " + (amount >= 0 ? "+" : "") + Economy.format(amount) + " money");
    }

    public static void keys(UUID id, int tier, int amount) {
        write(id, id + " " + (amount >= 0 ? "+" : "") + amount + " " + Keys.NAMES[tier] + " key");
    }

    private static void write(UUID id, String what) {
        if (id == null || com.aidan.skyserver.bots.BotManager.isBot(id)) return;
        String line = LocalDateTime.now().format(TIME) + "  " + what + "  [" + caller() + "]";
        synchronized (RECENT) {
            RECENT.addLast(new String[]{id.toString(), line});
            while (RECENT.size() > KEEP) RECENT.removeFirst();
        }
        try {
            Path dir = Stacks.DIR.resolve("logs");
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("transactions-" + LocalDateTime.now().toString().substring(0, 7) + ".txt"),
                    line + "  " + id + System.lineSeparator(), StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception ignored) {
        }
    }

    /** Which part of SkyServer made the change, e.g. "Parkour.finish". */
    private static String caller() {
        return StackWalker.getInstance().walk(s -> s
                .filter(f -> !f.getClassName().endsWith(".TxLog") && !f.getClassName().endsWith(".Economy") && !f.getClassName().endsWith(".Keys")
                        && !f.getClassName().endsWith(".Rewards"))
                .findFirst()
                .map(f -> f.getClassName().substring(f.getClassName().lastIndexOf('.') + 1).replaceAll("\\$.*", "") + "." + f.getMethodName().replaceAll("lambda\\$(\\w+)\\$\\d+", "$1"))
                .orElse("?"));
    }

    /** Last n lines for one player (newest last). */
    public static List<String> recent(UUID id, int n) {
        List<String> out = new ArrayList<>();
        synchronized (RECENT) {
            for (Iterator<String[]> it = RECENT.descendingIterator(); it.hasNext() && out.size() < n; ) {
                String[] e = it.next();
                if (e[0].equals(id.toString())) out.add(0, e[1]);
            }
        }
        return out;
    }
}
