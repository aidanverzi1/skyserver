package com.aidan.skyserver.lobby;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.*;

/**
 * One player's per-player progress, kept in memory while they're online (see PlayerCache):
 * daily double-XP use, the island / side-activity XP caps, the battle pass, weekly challenges and
 * cosmetics. Server level XP itself lives in Levels (it's needed for offline players too).
 * Saved to config/skyserver/players/<uuid>.json.
 *
 * During play everything changes only this object; PlayerCache writes it to disk in the
 * background.
 */
public final class PlayerProfile {
    public final UUID id;
    public String name = "?";

    /** True once the saved file has been read in (until then, changes are kept and merged in after). */
    volatile boolean loaded = false;
    /** Changed since the last save. */
    volatile boolean dirty = false;

    // ---- Daily double XP ----
    public long boostDay = 0;
    public int boosted = 0;

    // ---- Caps ----
    public long islandDay = 0;
    public int islandToday = 0;
    public long islandHourStart = 0;
    public int islandHour = 0;
    /** Leftover skill XP that hasn't added up to a whole server XP yet. */
    public double islandFrac = 0;
    public long sideDay = 0;
    public int sideToday = 0;

    // ---- Battle pass ----
    public int passSeason = 0;
    public long passXp = 0;
    public boolean premium = false;
    /** Highest tier whose free / Captain's Pass reward was handed out, and bonus tiers paid. */
    public int freePaid = 0, premiumPaid = 0, bonusPaid = 0;
    /** Weekly challenge progress: "week:index" -> amount. */
    public final Map<String, Integer> challenge = new HashMap<>();
    /** Weekly challenges finished: "week:index". */
    public final Set<String> challengeDone = new HashSet<>();

    // ---- Cosmetics ----
    public final Set<String> owned = new LinkedHashSet<>();
    public String title = null, trail = null;
    /** 5.8: kill effect and victory effect in use. */
    public String killFx = null, winFx = null;

    PlayerProfile(UUID id) {
        this.id = id;
    }

    public void touch() {
        dirty = true;
    }

    // =====================================================================
    // Saving
    // =====================================================================

    JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("name", name);
        o.addProperty("boostDay", boostDay);
        o.addProperty("boosted", boosted);
        o.addProperty("islandDay", islandDay);
        o.addProperty("islandToday", islandToday);
        o.addProperty("islandHourStart", islandHourStart);
        o.addProperty("islandHour", islandHour);
        o.addProperty("islandFrac", islandFrac);
        o.addProperty("sideDay", sideDay);
        o.addProperty("sideToday", sideToday);
        o.addProperty("passSeason", passSeason);
        o.addProperty("passXp", passXp);
        o.addProperty("premium", premium);
        o.addProperty("freePaid", freePaid);
        o.addProperty("premiumPaid", premiumPaid);
        o.addProperty("bonusPaid", bonusPaid);
        JsonObject ch = new JsonObject();
        challenge.forEach(ch::addProperty);
        o.add("challenge", ch);
        JsonArray done = new JsonArray();
        challengeDone.forEach(done::add);
        o.add("challengeDone", done);
        JsonArray own = new JsonArray();
        owned.forEach(own::add);
        o.add("owned", own);
        if (title != null) o.addProperty("title", title);
        if (trail != null) o.addProperty("trail", trail);
        if (killFx != null) o.addProperty("killFx", killFx);
        if (winFx != null) o.addProperty("winFx", winFx);
        return o;
    }

    static PlayerProfile fromJson(UUID id, JsonObject o) {
        PlayerProfile p = new PlayerProfile(id);
        if (o == null) return p;
        p.name = str(o, "name", "?");
        p.boostDay = lng(o, "boostDay");
        p.boosted = (int) lng(o, "boosted");
        p.islandDay = lng(o, "islandDay");
        p.islandToday = (int) lng(o, "islandToday");
        p.islandHourStart = lng(o, "islandHourStart");
        p.islandHour = (int) lng(o, "islandHour");
        p.islandFrac = o.has("islandFrac") ? o.get("islandFrac").getAsDouble() : 0;
        p.sideDay = lng(o, "sideDay");
        p.sideToday = (int) lng(o, "sideToday");
        p.passSeason = (int) lng(o, "passSeason");
        p.passXp = lng(o, "passXp");
        p.premium = o.has("premium") && o.get("premium").getAsBoolean();
        p.freePaid = (int) lng(o, "freePaid");
        p.premiumPaid = (int) lng(o, "premiumPaid");
        p.bonusPaid = (int) lng(o, "bonusPaid");
        if (o.has("challenge")) for (Map.Entry<String, JsonElement> e : o.getAsJsonObject("challenge").entrySet())
            p.challenge.put(e.getKey(), e.getValue().getAsInt());
        if (o.has("challengeDone")) for (JsonElement e : o.getAsJsonArray("challengeDone")) p.challengeDone.add(e.getAsString());
        if (o.has("owned")) for (JsonElement e : o.getAsJsonArray("owned")) p.owned.add(e.getAsString());
        p.title = o.has("title") ? o.get("title").getAsString() : null;
        p.trail = o.has("trail") ? o.get("trail").getAsString() : null;
        p.killFx = o.has("killFx") ? o.get("killFx").getAsString() : null;
        p.winFx = o.has("winFx") ? o.get("winFx").getAsString() : null;
        return p;
    }

    /**
     * The saved data arrived after some changes were already made in memory (the player earned
     * something in their first moments online): add the saved values to the new ones.
     */
    void absorb(PlayerProfile saved) {
        if (saved.boostDay == boostDay) boosted += saved.boosted;
        else if (saved.boostDay > boostDay) { boostDay = saved.boostDay; boosted = saved.boosted; }
        if (saved.islandDay == islandDay) islandToday += saved.islandToday;
        else if (saved.islandDay > islandDay) { islandDay = saved.islandDay; islandToday = saved.islandToday; }
        if (saved.islandHourStart == islandHourStart) islandHour += saved.islandHour;
        else if (saved.islandHourStart > islandHourStart) { islandHourStart = saved.islandHourStart; islandHour = saved.islandHour; }
        islandFrac += saved.islandFrac;
        if (saved.sideDay == sideDay) sideToday += saved.sideToday;
        else if (saved.sideDay > sideDay) { sideDay = saved.sideDay; sideToday = saved.sideToday; }
        if (saved.passSeason == passSeason || passSeason == 0) {
            passSeason = Math.max(passSeason, saved.passSeason);
            passXp += saved.passXp;
            premium |= saved.premium;
            freePaid = Math.max(freePaid, saved.freePaid);
            premiumPaid = Math.max(premiumPaid, saved.premiumPaid);
            bonusPaid = Math.max(bonusPaid, saved.bonusPaid);
            saved.challenge.forEach((k, v) -> challenge.merge(k, v, Integer::sum));
            challengeDone.addAll(saved.challengeDone);
        }
        owned.addAll(saved.owned);
        if (title == null) title = saved.title;
        if (trail == null) trail = saved.trail;
        if (killFx == null) killFx = saved.killFx;
        if (winFx == null) winFx = saved.winFx;
        if ("?".equals(name)) name = saved.name;
    }

    private static long lng(JsonObject o, String k) {
        return o.has(k) ? o.get(k).getAsLong() : 0;
    }

    private static String str(JsonObject o, String k, String def) {
        return o.has(k) ? o.get(k).getAsString() : def;
    }
}
