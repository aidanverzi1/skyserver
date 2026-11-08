package com.aidan.skyserver.lobby;

import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.server.level.ServerPlayer;

import java.util.*;

/**
 * Map voting for Skywars and Gold Wars. While waiting for a match, everyone in it can click a map
 * name in chat (or type /sw vote <map>, /gw vote <map>). When the match starts, the map with the most
 * votes is used (ties go to the map that's already built, so there's no rebuild if it's tied).
 * One vote each; you can change it. Votes reset every match.
 */
public final class MapVote {
    private final String command;          // "sw" or "gw"
    private final String[] ids, names;
    private final Map<UUID, Integer> votes = new HashMap<>();

    public MapVote(String command, String[] ids, String[] names) {
        this.command = command;
        this.ids = ids;
        this.names = names;
    }

    public String[] ids() {
        return ids;
    }

    /** The clickable "Vote for the map" line. */
    public void offer(ServerPlayer p, int current) {
        MutableComponent line = Component.literal("§eVote for the map: ");
        int[] counts = counts();
        for (int i = 0; i < ids.length; i++) {
            String color = i == current ? "§a" : "§b";
            line.append(Component.literal(color + "[" + names[i] + (counts[i] > 0 ? " " + counts[i] : "") + "] ")
                    .withStyle(Style.EMPTY.withClickEvent(new ClickEvent.RunCommand("/" + command + " vote " + ids[i]))
                            .withHoverEvent(new HoverEvent.ShowText(Component.literal("Vote for " + names[i] + (i == current ? " (already built)" : ""))))));
        }
        p.sendSystemMessage(line);
    }

    /** Returns a message for everyone, or null if the vote didn't count. */
    public String vote(ServerPlayer p, String id) {
        for (int i = 0; i < ids.length; i++) {
            if (!ids[i].equalsIgnoreCase(id) && !names[i].equalsIgnoreCase(id)) continue;
            Integer before = votes.put(p.getUUID(), i);
            if (before != null && before == i) return null;
            int[] c = counts();
            StringBuilder sb = new StringBuilder("§e" + p.getName().getString() + " voted for §f" + names[i] + "§7 (");
            for (int k = 0; k < ids.length; k++) sb.append(k > 0 ? ", " : "").append(names[k]).append(" ").append(c[k]);
            return sb.append(")").toString();
        }
        Lobby.msg(p, "§cNo map called " + id + ". Maps: " + String.join(", ", ids));
        return null;
    }

    public void remove(UUID id) {
        votes.remove(id);
    }

    private int[] counts() {
        int[] c = new int[ids.length];
        for (int v : votes.values()) c[v]++;
        return c;
    }

    /** The winning map (index), or current if no votes / tied with current. Clears the votes. */
    public int winner(int current) {
        int[] c = counts();
        votes.clear();
        int best = current;
        for (int i = 0; i < c.length; i++) if (c[i] > c[best]) best = i;
        return best;
    }
}
