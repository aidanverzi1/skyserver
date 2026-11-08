package com.aidan.skyserver.bots.record;

import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * One real player's recording in one game (from entering the game world until leaving it).
 *
 * Only the interesting parts are kept: whenever something happens (an enemy close by, a bow drawn,
 * a hit, a block placed...) the last second before it and the second after it are saved. Walking around
 * alone isn't. Plain Java, so it can be tested without Minecraft.
 */
public final class RecordingSession {
    /** Ticks kept before something happens. */
    public static final int LEAD_TICKS = 20;
    /** Ticks kept after it. */
    public static final int TAIL_TICKS = 20;

    public final UUID player;
    public final String name;
    public final String game;
    public final String started;
    public final int part;
    public String map = "unknown";
    public String mode = "";

    final List<FrameSample> frames = new ArrayList<>();
    final List<CombatEvent> events = new ArrayList<>();
    private final ArrayDeque<FrameSample> lead = new ArrayDeque<>();
    private int keepUntil = -1;
    private int tick = 0;
    private boolean mapSet = false;

    public RecordingSession(UUID player, String name, String game, int part) {
        this.player = player;
        this.name = name;
        this.game = game;
        this.part = part;
        this.started = LocalDateTime.now().withNano(0).toString();
    }

    /** The tick number for the next frame. */
    public int tick() {
        return tick;
    }

    /** Adds this tick's frame. trigger = something worth keeping is happening right now. */
    public void offer(FrameSample s, boolean trigger) {
        if (trigger) keepAround(s.tick());
        if (s.tick() <= keepUntil) {
            frames.add(s);
        } else {
            lead.addLast(s);
            if (lead.size() > LEAD_TICKS) lead.removeFirst();
        }
        tick++;
    }

    /** Adds an event (it also counts as "something happening"). */
    public void event(CombatEvent e) {
        events.add(e);
        keepAround(e.tick());
    }

    private void keepAround(int t) {
        if (!lead.isEmpty()) {
            frames.addAll(lead);
            lead.clear();
        }
        keepUntil = Math.max(keepUntil, t + TAIL_TICKS);
    }

    /** Sets the map and mode the first time something worth keeping happens (maps change between matches). */
    public boolean needsMap() {
        return !mapSet && !frames.isEmpty();
    }

    public void setMap(String map, String mode) {
        this.map = map == null ? "unknown" : map;
        this.mode = mode == null ? "" : mode;
        this.mapSet = true;
    }

    public int keptFrames() {
        return frames.size();
    }

    public int eventCount() {
        return events.size();
    }
}
