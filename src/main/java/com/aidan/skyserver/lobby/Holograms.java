package com.aidan.skyserver.lobby;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.phys.AABB;

import java.util.*;

/**
 * Floating text in the lobby (portal names, chest labels, leaderboards). Each line is an invisible
 * armor stand with a visible name, which every vanilla client can show. Old copies left over
 * from before a restart are cleaned up (the lobby has no other invisible armor stands).
 */
public final class Holograms {
    private static final double LINE = 0.28;

    /** A placed hologram whose lines can be changed. */
    public static final class Holo {
        final double x, y, z;
        final List<ArmorStand> stands = new ArrayList<>();
        List<String> lines;

        Holo(double x, double y, double z, List<String> lines) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.lines = new ArrayList<>(lines);
        }

        public void setLines(List<String> newLines) {
            if (newLines.equals(lines) && stands.size() == newLines.size()) return;
            lines = new ArrayList<>(newLines);
            dirty = true;
        }

        boolean dirty = true;
    }

    private static final List<Holo> ALL = new ArrayList<>();
    private static final Set<UUID> OURS = new HashSet<>();
    private static final Set<Long> FORCED = new HashSet<>();

    private Holograms() {}

    public static Holo add(double x, double y, double z, List<String> lines) {
        Holo h = new Holo(x, y, z, lines);
        ALL.add(h);
        return h;
    }

    /** Keeps the stands in sync with the text. Call every second or so. */
    public static void tick(ServerLevel level) {
        if (level == null) return;
        for (Holo h : ALL) {
            long chunk = ((long) ((int) Math.floor(h.x) >> 4) << 32) ^ (((int) Math.floor(h.z) >> 4) & 0xffffffffL);
            if (FORCED.add(chunk)) level.setChunkForced((int) Math.floor(h.x) >> 4, (int) Math.floor(h.z) >> 4, true);
            boolean broken = false;
            for (ArmorStand s : h.stands) if (s.isRemoved()) broken = true;
            if (!h.dirty && !broken) continue;
            h.dirty = false;
            if (h.stands.size() != h.lines.size() || broken) {
                for (ArmorStand s : h.stands) {
                    OURS.remove(s.getUUID());
                    s.discard();
                }
                h.stands.clear();
                for (int i = 0; i < h.lines.size(); i++) {
                    ArmorStand s = new ArmorStand(level, h.x, h.y - i * LINE, h.z);
                    s.setInvisible(true);
                    s.setNoGravity(true);
                    s.setPermanentlyInvulnerable(true);
                    s.setSilent(true);
                    s.setCustomNameVisible(true);
                    marker(s);
                    level.addFreshEntity(s);
                    h.stands.add(s);
                    OURS.add(s.getUUID());
                }
            }
            for (int i = 0; i < h.lines.size(); i++) {
                String line = h.lines.get(i);
                h.stands.get(i).setCustomName(Component.literal(line.isEmpty() ? " " : line));
            }
        }
        // Remove leftovers from before a restart.
        for (Holo h : ALL) {
            AABB box = new AABB(h.x - 2, h.y - 6, h.z - 2, h.x + 2, h.y + 2, h.z + 2);
            for (Entity e : level.getEntitiesOfClass(ArmorStand.class, box, e -> e.isInvisible() && !OURS.contains(e.getUUID()))) e.discard();
        }
    }

    /** Marker armor stands have no hitbox (players can't bump into or click them). */
    private static void marker(ArmorStand s) {
        try {
            var m = ArmorStand.class.getDeclaredMethod("setMarker", boolean.class);
            m.setAccessible(true);
            m.invoke(s, true);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // Not important: it's invisible and invulnerable anyway.
        }
    }

    public static void removeAll() {
        for (Holo h : ALL) for (ArmorStand s : h.stands) s.discard();
        ALL.clear();
        OURS.clear();
    }
}
