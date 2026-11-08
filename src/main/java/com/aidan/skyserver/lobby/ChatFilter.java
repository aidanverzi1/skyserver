package com.aidan.skyserver.lobby;

import com.aidan.skyserver.Skyserver;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.phys.AABB;

import java.text.Normalizer;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Slur filter for chat and anything else players write: chat, team/match/party/island/faction
 * chat, /msg and /me, signs, renamed items (and the mobs they name), books, faction and warp names.
 * Normal swear words are allowed - only racial and homophobic slurs are blocked.
 *
 * It sees through tricks: spaces, dots or other characters between letters ("n i g", "f.a.g"),
 * repeated letters, numbers/symbols for letters (1=i, 3=e, 0=o, @=a, $=s...) and look-alike
 * letters from other alphabets. To avoid blocking normal words ("night", "Nigeria", "spicy",
 * "raccoon") some words only count as a whole word.
 *
 * A blocked message isn't sent at all; ops and moderators are told. Signs, names and books with a
 * slur are wiped.
 * Mutes: 2 tries within 30 minutes = muted 5 minutes. Then 2 more tries within 24 hours of that
 * mute = 1 hour, then 2 more within 24 hours of that = 24 hours (and 24 hours again after that).
 * 5.9: moderators can also mute someone by hand (/mod mute) - that doesn't move the slur ladder.
 * Saved in config/skyserver/chat-filter.json.
 */
public final class ChatFilter {
    private ChatFilter() {}

    // ---- The word list (normalized: lowercase letters only) ----

    /** Found anywhere, even inside a longer word. "+" lets letters repeat. */
    private static final String[] ANYWHERE = {
            "(?<!s)n+[i]+g{2,}(e+r+|a+h*|a+z+|u+h+|r+o+)",   // the n-word (needs "gg", so "Nigeria"/"Niger" are fine)
            "(?<!s)n+[i]+g+[g]+e+r+",
            "f+a+g{2,}o+t+", "f+a+g+o+t+s+", "f+a+g{2,}(i+t+|e+t+)",
            "k+i+k+e+s*$", "w+e+t+b+a+c+k+", "j+i+g+a+b+o+o+", "p+o+r+c+h+m+o+n+k+e+y+", "s+a+n+d+n+i+g+",
            "t+o+w+e+l+h+e+a+d+", "r+a+g+h+e+a+d+", "s+h+e+m+a+l+e+", "t+r+a+n+n+(y+|i+e+s+)", "b+e+a+n+e+r+",
            "c+h+i+n+k+s*$", "z+i+p+p+e+r+h+e+a+d+", "s+l+a+n+t+e+y+e+", "k+n+e+e+g+r+o+w+",
    };
    /** Only as a whole word (with an optional plural s). */
    private static final String[] WHOLE = {
            "f+a+g+s*", "f+a+g+g+(y+|s+)?", "d+y+k+e+s*", "h+o+m+o+s*", "l+e+s+b+o+s*", "s+p+i+c+k*s*", "g+o+o+k+s*", "c+o+o+n+s*",
            "p+a+k+i+s*", "n+i+g+s*", "n+i+g+g+s*", "w+o+g+s*", "j+a+p+s*", "c+h+i+n+k+y*", "t+r+a+n+y+",
    };
    private static final List<Pattern> ANY = new ArrayList<>(), WHOLE_P = new ArrayList<>();

    static {
        for (String w : ANYWHERE) ANY.add(Pattern.compile(w));
        for (String w : WHOLE) WHOLE_P.add(Pattern.compile("^(" + w + ")$"));
    }

    private static final Map<Character, Character> LEET = new HashMap<>();

    static {
        String from = "0134578@$!|9¡€£", to = "oieastbasiigiel";
        for (int i = 0; i < from.length(); i++) LEET.put(from.charAt(i), to.charAt(i));
        // Look-alike letters from other alphabets (Cyrillic / Greek).
        String cy = "аеорсухкмтвнгіїјѕԁɡ", la = "aeopcyxkmtbhriijsdg";
        for (int i = 0; i < cy.length(); i++) LEET.put(cy.charAt(i), la.charAt(i));
    }

    /** Lowercase, accents off, leet and look-alikes turned into letters; everything else kept as-is. */
    private static String normalize(String s) {
        s = Normalizer.normalize(s.toLowerCase(Locale.ROOT), Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        StringBuilder b = new StringBuilder(s.length());
        for (char c : s.toCharArray()) b.append(LEET.getOrDefault(c, c));
        return b.toString();
    }

    private static String lettersOnly(String s) {
        return s.replaceAll("[^a-z]", "");
    }

    private static boolean badWord(String w) {
        if (w.isEmpty()) return false;
        for (Pattern p : ANY) if (p.matcher(w).find()) return true;
        for (Pattern p : WHOLE_P) if (p.matcher(w).matches()) return true;
        return false;
    }

    private static boolean badJoined(String w) {
        if (w.length() < 3) return false;
        for (Pattern p : ANY) if (p.matcher(w).find()) return true;
        return false;
    }

    /** True if the text contains a slur (however it's disguised). */
    public static boolean hasSlur(String text) {
        if (text == null || text.isBlank()) return false;
        String n = normalize(text.replaceAll("§.", ""));
        String[] raw = n.split("\\s+");
        List<String> tok = new ArrayList<>();
        for (String r : raw) {
            String l = lettersOnly(r);
            if (!l.isEmpty()) tok.add(l);
            // Words glued with symbols ("you_f4g", "go.f.a.g") also checked piece by piece.
            for (String part : r.split("[^a-z]+")) if (!part.isEmpty() && !part.equals(l) && badWord(part)) return true;
        }
        for (String t : tok) if (badWord(t)) return true;
        // Spelled out with spaces: "n i g g e r", "f ag" - join runs of short pieces.
        StringBuilder run = new StringBuilder();
        for (int i = 0; i <= tok.size(); i++) {
            String t = i < tok.size() ? tok.get(i) : null;
            if (t != null && t.length() <= 2) {
                run.append(t);
                continue;
            }
            if (run.length() >= 3 && (badWord(run.toString()) || badJoined(run.toString()))) return true;
            // A short run joined onto the next or previous word ("ni gger", "fa ggot").
            if (run.length() > 0 && t != null && badJoined(run + t)) return true;
            run.setLength(0);
        }
        for (int i = 0; i + 1 < tok.size(); i++) if (badJoined(tok.get(i) + tok.get(i + 1)) && !badJoined(tok.get(i)) && !badJoined(tok.get(i + 1))) {
            // Two halves of one slur ("nig ger"): only the strongest words, to avoid joining normal words.
            String j = tok.get(i) + tok.get(i + 1);
            if (j.matches(".*n+i+g{2,}.*|.*f+a+g{2,}o+t+.*")) return true;
        }
        return false;
    }

    // ---- Mutes and tries ----

    private static final class Record {
        final List<Long> tries = new ArrayList<>();
        int level = 0;            // 0 none yet, 1 had a 5-min mute, 2 had 1 hour, 3 had 24 hours
        long lastMuteAt = 0, mutedUntil = 0;
        boolean byMod = false;    // 5.9: the current mute came from a moderator
    }

    private static final Map<UUID, Record> RECORDS = new HashMap<>();
    private static boolean loaded = false;
    private static final long MIN = 60_000L, HOUR = 60 * MIN, DAY = 24 * HOUR;

    public static boolean isMuted(UUID id) {
        load();
        Record r = RECORDS.get(id);
        return r != null && r.mutedUntil > System.currentTimeMillis();
    }

    private static String left(long ms) {
        long m = (ms + 59_999) / 60_000;
        return m >= 120 ? (m / 60) + " hours" : m + " minute" + (m == 1 ? "" : "s");
    }

    /**
     * Check text a player wants to send/write. Returns true if it's fine. If it's blocked (or they're
     * muted), they're told why and staff are alerted; a slur counts as a try toward a mute.
     */
    public static boolean allow(ServerPlayer p, String text, String where) {
        if (p == null || p instanceof com.aidan.skyserver.bots.BotPlayer) return true;
        load();
        Record r = RECORDS.computeIfAbsent(p.getUUID(), k -> new Record());
        long now = System.currentTimeMillis();
        boolean chatLike = where.equals("chat");
        if (chatLike && r.mutedUntil > now) {
            Lobby.msg(p, "§cYou're muted for " + left(r.mutedUntil - now) + (r.byMod ? " §7(by a moderator)." : " §7(for using slurs)."));
            return false;
        }
        if (!hasSlur(text)) return true;
        strike(p, r, now, where);
        return false;
    }

    private static void strike(ServerPlayer p, Record r, long now, String where) {
        r.tries.add(now);
        String muted = null;
        if (r.level > 0 && now - r.lastMuteAt > DAY) r.level = 0; // a clean day resets the ladder
        if (r.level == 0) {
            int recent = 0;
            for (long t : r.tries) if (now - t <= 30 * MIN) recent++;
            if (recent >= 2) muted = mute(r, now, 5 * MIN, 1);
        } else {
            int since = 0;
            for (long t : r.tries) if (t > r.lastMuteAt) since++;
            if (since >= 2) muted = r.level == 1 ? mute(r, now, HOUR, 2) : mute(r, now, DAY, 3);
        }
        r.tries.removeIf(t -> now - t > DAY);
        save();
        Lobby.msg(p, "§cThat " + (where.equals("chat") ? "message wasn't sent" : where + " was removed") + ": slurs aren't allowed here."
                + (muted != null ? " §4You're muted for " + muted + "." : " §7Do it again and you'll be muted."));
        Skyserver.LOGGER.info("[ChatFilter] {} used a slur ({}){}", p.getName().getString(), where, muted != null ? " - muted " + muted : "");
        MinecraftServer server = p.level().getServer();
        for (ServerPlayer o : server.getPlayerList().getPlayers())
            if (o != p && !(o instanceof com.aidan.skyserver.bots.BotPlayer) && (server.getPlayerList().isOp(o.nameAndId()) || Mods.isMod(o.getUUID())))
                Lobby.msg(o, "§c[Filter] §f" + p.getName().getString() + " §7tried to use a slur (" + where + ")" + (muted != null ? " §c- muted " + muted : "") + ".");
    }

    private static String mute(Record r, long now, long length, int level) {
        r.level = level;
        r.lastMuteAt = now;
        r.mutedUntil = now + length;
        r.byMod = false;
        r.tries.clear();
        return left(length);
    }

    /** 5.9: a moderator's mute (/mod mute). Longer than any mute they already have wins; the slur ladder isn't touched. */
    public static void muteFor(UUID id, long ms) {
        load();
        Record r = RECORDS.computeIfAbsent(id, k -> new Record());
        long until = System.currentTimeMillis() + ms;
        if (until > r.mutedUntil) {
            r.mutedUntil = until;
            r.byMod = true;
        }
        save();
    }

    // ---- Hooks ----

    public static void init() {
        // Registered before the chat channels, so a blocked message stops here.
        ServerMessageEvents.ALLOW_CHAT_MESSAGE.register((message, sender, params) -> allow(sender, message.signedContent(), "chat"));
        ServerMessageEvents.ALLOW_COMMAND_MESSAGE.register((message, source, params) -> {
            ServerPlayer p = source.getPlayer();
            return p == null || allow(p, message.signedContent(), "chat");
        });
    }

    /** Every 2 seconds: signs, renamed items, books and named mobs near real players. */
    static void tick(MinecraftServer server, long ticks) {
        if (ticks % 40 != 21) return;
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (p instanceof com.aidan.skyserver.bots.BotPlayer) continue;
            checkItems(p);
            checkSigns(p);
            checkMobs(p);
        }
    }

    private static void checkItems(ServerPlayer p) {
        var inv = p.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (s.isEmpty()) continue;
            Component name = s.get(DataComponents.CUSTOM_NAME);
            if (name != null && hasSlur(name.getString())) {
                s.remove(DataComponents.CUSTOM_NAME);
                strikeQuiet(p, "item name");
            }
            var written = s.get(DataComponents.WRITTEN_BOOK_CONTENT);
            if (written != null) {
                StringBuilder b = new StringBuilder(written.title().raw());
                written.pages().forEach(pg -> b.append(' ').append(pg.raw().getString()));
                if (hasSlur(b.toString())) {
                    inv.setItem(i, ItemStack.EMPTY);
                    strikeQuiet(p, "book");
                }
            }
            var writable = s.get(DataComponents.WRITABLE_BOOK_CONTENT);
            if (writable != null) {
                StringBuilder b = new StringBuilder();
                writable.pages().forEach(pg -> b.append(' ').append(pg.raw()));
                if (hasSlur(b.toString())) {
                    s.remove(DataComponents.WRITABLE_BOOK_CONTENT);
                    strikeQuiet(p, "book");
                }
            }
        }
    }

    private static final Map<Long, Integer> SIGNS_SEEN = new HashMap<>(); // pos -> hash of text checked

    private static String signText(SignText t) {
        StringBuilder b = new StringBuilder();
        for (Component c : t.getMessages(false)) b.append(c.getString()).append(' ');
        return b.toString();
    }

    private static void checkSigns(ServerPlayer p) {
        ServerLevel level = (ServerLevel) p.level();
        int cx = p.blockPosition().getX() >> 4, cz = p.blockPosition().getZ() >> 4;
        for (int dx = -1; dx <= 1; dx++)
            for (int dz = -1; dz <= 1; dz++) {
                if (!level.hasChunk(cx + dx, cz + dz)) continue;
                for (BlockEntity be : new ArrayList<>(level.getChunk(cx + dx, cz + dz).getBlockEntities().values())) {
                    if (!(be instanceof SignBlockEntity sign)) continue;
                    String all = signText(sign.getText(net.minecraft.world.level.block.entity.SignTextSlot.FRONT)) + "| " + signText(sign.getText(net.minecraft.world.level.block.entity.SignTextSlot.BACK));
                    long key = be.getBlockPos().asLong();
                    Integer seen = SIGNS_SEEN.get(key);
                    if (seen != null && seen == all.hashCode()) continue;
                    SIGNS_SEEN.put(key, all.hashCode());
                    if (!hasSlur(signText(sign.getText(net.minecraft.world.level.block.entity.SignTextSlot.FRONT))) && !hasSlur(signText(sign.getText(net.minecraft.world.level.block.entity.SignTextSlot.BACK)))) continue;
                    sign.setText(SignText.EMPTY, net.minecraft.world.level.block.entity.SignTextSlot.FRONT);
                    sign.setText(SignText.EMPTY, net.minecraft.world.level.block.entity.SignTextSlot.BACK);
                    sign.setChanged();
                    level.sendBlockUpdated(be.getBlockPos(), be.getBlockState(), be.getBlockState(), 3);
                    SIGNS_SEEN.put(key, (signText(SignText.EMPTY) + "| " + signText(SignText.EMPTY)).hashCode());
                    // Blame whoever is closest (the one who just wrote it).
                    ServerPlayer writer = null;
                    double best = 64;
                    for (ServerPlayer o : level.players()) {
                        double d = o.distanceToSqr(be.getBlockPos().getX() + 0.5, be.getBlockPos().getY() + 0.5, be.getBlockPos().getZ() + 0.5);
                        if (d < best && !(o instanceof com.aidan.skyserver.bots.BotPlayer)) {
                            best = d;
                            writer = o;
                        }
                    }
                    if (writer != null) strikeQuiet(writer, "sign");
                }
            }
        if (SIGNS_SEEN.size() > 20_000) SIGNS_SEEN.clear();
    }

    private static void checkMobs(ServerPlayer p) {
        ServerLevel level = (ServerLevel) p.level();
        for (Entity e : level.getEntities((Entity) null, new AABB(p.blockPosition()).inflate(24), x -> x.hasCustomName() && !(x instanceof ServerPlayer))) {
            Component n = e.getCustomName();
            if (n == null || !hasSlur(n.getString())) continue;
            e.setCustomName(null);
            e.setCustomNameVisible(false);
            strikeQuiet(p, "mob name");
        }
    }

    private static void strikeQuiet(ServerPlayer p, String where) {
        load();
        strike(p, RECORDS.computeIfAbsent(p.getUUID(), k -> new Record()), System.currentTimeMillis(), where);
    }

    // ---- Saving ----

    private static void load() {
        if (loaded) return;
        loaded = true;
        JsonObject o = Stacks.readFile("chat-filter.json");
        for (Map.Entry<String, JsonElement> e : o.entrySet()) {
            try {
                JsonObject j = e.getValue().getAsJsonObject();
                Record r = new Record();
                r.level = j.get("level").getAsInt();
                r.lastMuteAt = j.get("lastMuteAt").getAsLong();
                r.mutedUntil = j.get("mutedUntil").getAsLong();
                r.byMod = j.has("byMod") && j.get("byMod").getAsBoolean();
                for (JsonElement t : j.getAsJsonArray("tries")) r.tries.add(t.getAsLong());
                RECORDS.put(UUID.fromString(e.getKey()), r);
            } catch (RuntimeException ignored) {
            }
        }
    }

    private static void save() {
        JsonObject o = new JsonObject();
        RECORDS.forEach((id, r) -> {
            JsonObject j = new JsonObject();
            j.addProperty("level", r.level);
            j.addProperty("lastMuteAt", r.lastMuteAt);
            j.addProperty("mutedUntil", r.mutedUntil);
            if (r.byMod) j.addProperty("byMod", true);
            JsonArray a = new JsonArray();
            r.tries.forEach(a::add);
            j.add("tries", a);
            o.add(id.toString(), j);
        });
        Stacks.writeFile("chat-filter.json", o);
    }

    /** /ss unmute and /mod unmute. True if they were muted. */
    public static boolean unmute(UUID id) {
        load();
        Record r = RECORDS.get(id);
        if (r == null || r.mutedUntil <= System.currentTimeMillis()) return false;
        r.mutedUntil = 0;
        r.byMod = false;
        save();
        return true;
    }
}
