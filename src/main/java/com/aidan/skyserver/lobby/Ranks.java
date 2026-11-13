package com.aidan.skyserver.lobby;

import com.aidan.skyserver.island.Islands;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.TeamColor;

import java.util.*;

/**
 * What other players see around your name, and your trail:
 *  - Before your name (chat, tab list, name tag): "[MOD]" for moderators (5.9), then your level
 *    "[42]" (colour changes every 10 levels), or your prestige label ("[Cloudwalker]").
 *  - After your name: the title you picked in /cosmetics.
 *  - 5.13: your name colour (CosmeticsPlus); the Legendary rainbow name changes colour every 2 seconds.
 *  - Trail: particles behind you while you walk in the lobby or on the islands, and behind your
 *    arrows in games.
 * Done with vanilla scoreboard teams named "ss...". In a game, the game's own team (Gold Wars /
 * Bedwars colours) takes over; you get your tag back when the game ends.
 */
public final class Ranks {
    private static final Map<UUID, double[]> LAST_POS = new HashMap<>();
    public static final String MOD_TAG = "§b[MOD] ";
    /** 5.13: the colours the rainbow name cycles through. */
    private static final TeamColor[] RAINBOW = {TeamColor.RED, TeamColor.GOLD, TeamColor.YELLOW, TeamColor.GREEN,
            TeamColor.AQUA, TeamColor.BLUE, TeamColor.LIGHT_PURPLE};

    private Ranks() {}

    public static void init() {
        ServerTickEvents.END_SERVER_TICK.register(s -> com.aidan.skyserver.Safe.run("ranks tick", () -> tick(s)));
    }

    private static void tick(MinecraftServer s) {
        long t = s.getTickCount();
        if (t % 40 == 13) for (ServerPlayer p : s.getPlayerList().getPlayers()) {
            if (p instanceof com.aidan.skyserver.bots.BotPlayer) continue;
            updateTeam(s, p);
        }
        if (t % 3 == 0) trails(s, t);
    }

    // ---- Name tags ----

    /** 5.13: team colour for a name colour cosmetic (null = none / rainbow). */
    private static TeamColor teamColor(String id) {
        if (id == null) return null;
        return switch (id) {
            case "color_red" -> TeamColor.RED;
            case "color_gold" -> TeamColor.GOLD;
            case "color_yellow" -> TeamColor.YELLOW;
            case "color_green" -> TeamColor.GREEN;
            case "color_aqua" -> TeamColor.AQUA;
            case "color_pink" -> TeamColor.LIGHT_PURPLE;
            case "color_crimson" -> TeamColor.DARK_RED;
            case "color_ocean" -> TeamColor.DARK_AQUA;
            case "color_royal" -> TeamColor.DARK_PURPLE;
            case "color_midnight" -> TeamColor.BLUE;
            default -> null;
        };
    }

    private static void updateTeam(MinecraftServer s, ServerPlayer p) {
        Scoreboard sb = s.getScoreboard();
        String holder = p.getScoreboardName();
        PlayerTeam current = sb.getPlayersTeam(holder);
        if (current != null && !current.getName().startsWith("ss")) return; // in a game team
        PlayerProfile prof = PlayerCache.ifOnline(p.getUUID());
        String title = prof == null ? null : prof.title;
        int pr = Levels.prestige(p.getUUID());
        boolean mod = Mods.isMod(p.getUUID());
        boolean patron = Boosters.isPatron(p.getUUID()); // 5.19: [Patron] while their booster runs
        String color = CosmeticsPlus.color(p.getUUID());
        boolean rainbow = "color_rainbow".equals(color);
        String key = (mod ? "ssM" : "ss") + (patron ? "B" : "") + (pr > 0 ? "P" + Progression.labelIndex(pr) : "L" + Levels.level(p.getUUID()))
                + (title == null ? "" : "_" + Math.abs(title.hashCode() % 100000))
                + (color == null ? "" : rainbow ? "_r" + Math.abs(p.getUUID().hashCode() % 1000000) : "_" + color.substring(6));
        if (current != null && current.getName().equals(key)) {
            if (rainbow) current.setColor(Optional.of(RAINBOW[(int) ((s.getTickCount() / 40) % RAINBOW.length)]));
            return;
        }
        PlayerTeam team = sb.getPlayerTeam(key);
        if (team == null) {
            team = sb.addPlayerTeam(key);
            team.setPlayerPrefix(Component.literal((mod ? MOD_TAG : "") + (patron ? Boosters.PATRON_TAG : "") + Levels.chatTag(p.getUUID())));
            if (title != null) team.setPlayerSuffix(Component.literal(" " + Progression.cosmeticName(title)));
            TeamColor c = rainbow ? RAINBOW[0] : teamColor(color);
            if (c != null) team.setColor(Optional.of(c));
        }
        sb.addPlayerToTeam(holder, team);
    }

    // ---- Trails ----

    private static SimpleParticleType particle(String trail) {
        return switch (trail) {
            case "trail_cloud" -> ParticleTypes.CLOUD;
            case "trail_heart" -> ParticleTypes.HEART;
            case "trail_note" -> ParticleTypes.NOTE;
            case "trail_flame" -> ParticleTypes.FLAME;
            case "trail_snow" -> ParticleTypes.SNOWFLAKE;
            case "trail_magic" -> ParticleTypes.ENCHANT;
            case "trail_star" -> ParticleTypes.END_ROD;
            case "trail_leaf" -> ParticleTypes.HAPPY_VILLAGER;
            case "trail_soul" -> ParticleTypes.SOUL_FIRE_FLAME;
            case "trail_cherry" -> ParticleTypes.SPORE_BLOSSOM_AIR;
            case "trail_glow" -> ParticleTypes.GLOW;
            case "trail_crimson" -> ParticleTypes.CRIMSON_SPORE;
            case "trail_portal" -> ParticleTypes.PORTAL;
            case "trail_spark" -> ParticleTypes.ELECTRIC_SPARK;
            case "trail_witch" -> ParticleTypes.WITCH;
            case "trail_totem" -> ParticleTypes.TOTEM_OF_UNDYING;
            case "trail_voyage" -> ParticleTypes.FIREWORK; // 5.13: Voyage stamp book
            default -> null;
        };
    }

    private static void trails(MinecraftServer s, long t) {
        for (ServerPlayer p : s.getPlayerList().getPlayers()) {
            if (p instanceof com.aidan.skyserver.bots.BotPlayer || p.isSpectator() || p.isInvisible()) continue;
            PlayerProfile prof = PlayerCache.ifOnline(p.getUUID());
            if (prof == null || prof.trail == null) continue;
            SimpleParticleType type = particle(prof.trail);
            if (type == null) continue;
            ServerLevel level = (ServerLevel) p.level();
            if (Lobby.isLobby(level) || Islands.isIsland(level)) {
                double[] last = LAST_POS.put(p.getUUID(), new double[]{p.getX(), p.getZ()});
                boolean moving = last != null && (Math.abs(last[0] - p.getX()) + Math.abs(last[1] - p.getZ())) > 0.08;
                if (moving) level.sendParticles(type, p.getX(), p.getY() + 0.15, p.getZ(), 2, 0.15, 0.05, 0.15, 0.0);
            } else if (Lobby.isGameLevel(level)) {
                for (AbstractArrow a : level.getEntitiesOfClass(AbstractArrow.class, p.getBoundingBox().inflate(80),
                        a -> a.getOwner() == p && a.getDeltaMovement().lengthSqr() > 0.05)) {
                    level.sendParticles(type, a.getX(), a.getY(), a.getZ(), 1, 0, 0, 0, 0.0);
                }
            }
        }
        if (t % 6000 == 0) LAST_POS.keySet().removeIf(id -> s.getPlayerList().getPlayer(id) == null);
    }
}
