package com.aidan.skyserver.lobby;

import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetActionBarTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;

import java.util.Collection;

/**
 * Less chat spam: countdowns and quick notices go on screen (big title, or the line above the
 * hotbar) instead of a chat line every second.
 */
public final class Announce {
    private Announce() {}

    public static void title(ServerPlayer p, String title, String subtitle, int fadeIn, int stay, int fadeOut) {
        if (p == null || p instanceof com.aidan.skyserver.bots.BotPlayer) return;
        p.connection.send(new ClientboundSetTitlesAnimationPacket(fadeIn, stay, fadeOut));
        p.connection.send(new ClientboundSetSubtitleTextPacket(Component.literal(subtitle == null ? "" : subtitle)));
        p.connection.send(new ClientboundSetTitleTextPacket(Component.literal(title == null ? "" : title)));
    }

    public static void actionBar(ServerPlayer p, String text) {
        if (p == null || p instanceof com.aidan.skyserver.bots.BotPlayer) return;
        p.connection.send(new ClientboundSetActionBarTextPacket(Component.literal(text)));
    }

    public static void ding(ServerPlayer p, float pitch) {
        if (p == null || p instanceof com.aidan.skyserver.bots.BotPlayer) return;
        p.level().playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.NOTE_BLOCK_PLING.value(), SoundSource.MASTER, 0.8f, pitch);
    }

    /**
     * One second of a countdown. 5..1 show as a big number in the middle of the screen with a
     * sound; the rest is silent (the status line above the hotbar already shows the time).
     * Returns true when it's worth a chat line too (30 and 10 seconds).
     */
    public static boolean countdown(Collection<ServerPlayer> players, int sec, String color, String subtitle) {
        if (sec > 0 && sec <= 5) {
            for (ServerPlayer p : players) {
                title(p, color + sec, subtitle, 0, 22, 4);
                ding(p, 1.0f + (5 - sec) * 0.1f);
            }
        }
        return sec == 30 || sec == 10;
    }

    /** "GO!" style title for everyone. */
    public static void go(Collection<ServerPlayer> players, String title, String subtitle) {
        for (ServerPlayer p : players) {
            title(p, title, subtitle, 0, 30, 10);
            ding(p, 2.0f);
        }
    }
}
