package com.aidan.skyserver.bots;

import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Keeps bots out of chat: no "joined the game", "left the game" or advancement messages for bots.
 * (Their kills and deaths in a match still show, since that's part of the game.)
 */
final class BotChatFilter {
    private static final Set<String> NAMES = new HashSet<>();

    private BotChatFilter() {}

    static void botName(String name) {
        NAMES.add(name.toLowerCase(Locale.ROOT));
    }

    static void init() {
        ServerMessageEvents.ALLOW_GAME_MESSAGE.register((server, message, overlay) -> !aboutBot(message));
    }

    private static boolean aboutBot(Component message) {
        if (!(message.getContents() instanceof TranslatableContents t)) return false;
        String key = t.getKey();
        boolean hidden = key.startsWith("multiplayer.player.joined") || key.startsWith("multiplayer.player.left")
                || key.startsWith("chat.type.advancement");
        if (!hidden || t.getArgs().length == 0) return false;
        Object who = t.getArgs()[0];
        String name = who instanceof Component c ? c.getString() : String.valueOf(who);
        return NAMES.contains(name.toLowerCase(Locale.ROOT));
    }
}
