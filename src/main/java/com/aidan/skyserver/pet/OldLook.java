package com.aidan.skyserver.pet;

import com.aidan.skyserver.Safe;
import com.aidan.skyserver.Skyserver;
import com.aidan.skyserver.lobby.Lobby;
import com.aidan.skyserver.lobby.Stacks;
import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.ClientboundResourcePackPopPacket;
import net.minecraft.network.protocol.common.ClientboundResourcePackPushPacket;
import net.minecraft.server.level.ServerPlayer;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 5.24: /oldlook - an optional resource pack with the 2015 (1.8) block textures, block sounds and C418 music.
 * Each player turns it on or off for themselves; it's remembered (config/skyserver/oldlook-players.json) and
 * sent again when they join.
 *
 * The pack itself is NOT on the server: it's downloaded by each player's game from the link in
 * config/skyserver/oldlook.json ("url" = a Dropbox link ending in ?dl=1, "sha1" = the pack's SHA-1 that
 * Make2015Pack printed). If the url is blank, /oldlook says it isn't set up yet. Rebuild the pack -> new sha1.
 * Vanilla clients always ask "use this pack?" the first time (that prompt can't be skipped).
 */
public final class OldLook {
    private OldLook() {}

    private static final String FILE = "oldlook.json", PLAYERS = "oldlook-players.json";
    private static final String DEFAULT_SHA1 = "8de7b93b85951456d8db8096ac00fa744f912e02";
    /** Fixed id, so turning it off removes exactly this pack. */
    private static final UUID PACK_ID = UUID.nameUUIDFromBytes("skyserver:oldlook-2015".getBytes(StandardCharsets.UTF_8));
    private static final Set<UUID> ON = new HashSet<>();
    private static String url = "", sha1 = DEFAULT_SHA1;

    private static void load() {
        JsonObject cfg = Stacks.readFile(FILE);
        if (!cfg.has("url") || !cfg.has("sha1")) {
            if (!cfg.has("url")) cfg.addProperty("url", "");
            if (!cfg.has("sha1")) cfg.addProperty("sha1", DEFAULT_SHA1);
            cfg.addProperty("_help", "url = your Dropbox share link with ?dl=1 at the end (not ?dl=0). sha1 = what Make2015Pack printed. Restart after editing.");
            Stacks.writeFile(FILE, cfg);
        }
        url = cfg.get("url").getAsString().trim();
        sha1 = cfg.get("sha1").getAsString().trim().toLowerCase();
        if (url.contains("dropbox.com") && url.contains("dl=0")) url = url.replace("dl=0", "dl=1"); // the usual mistake
        ON.clear();
        JsonObject pl = Stacks.readFile(PLAYERS);
        for (String k : pl.keySet()) if (pl.get(k).getAsBoolean()) ON.add(UUID.fromString(k));
        Skyserver.LOGGER.info("[OldLook] {} ({} players have it on)", url.isEmpty() ? "no pack link set in config/skyserver/oldlook.json" : "pack link set", ON.size());
    }

    private static void savePlayers() {
        JsonObject o = new JsonObject();
        for (UUID id : ON) o.addProperty(id.toString(), true);
        Stacks.writeFile(PLAYERS, o);
    }

    private static void send(ServerPlayer p) {
        p.connection.send(new ClientboundResourcePackPushPacket(PACK_ID, url, sha1, false,
                Optional.of(Component.literal("§6SkyServer 2015 look §7- blocks, sounds and music like 1.8. Turn it off any time with §e/oldlook§7."))));
    }

    private static int toggle(ServerPlayer p) {
        if (ON.remove(p.getUUID())) {
            p.connection.send(new ClientboundResourcePackPopPacket(Optional.of(PACK_ID)));
            savePlayers();
            Lobby.msg(p, "§7The 2015 look is §coff§7. §8(/oldlook turns it back on.)");
            return 1;
        }
        if (url.isEmpty()) {
            Lobby.msg(p, "§7The 2015 look isn't set up yet - ask the server owner.");
            return 0;
        }
        ON.add(p.getUUID());
        savePlayers();
        send(p);
        Lobby.msg(p, "§6The 2015 look is §aon§6! §7Click §fYes §7on the pack screen. It stays on every time you join - §e/oldlook §7turns it off.");
        return 1;
    }

    public static void init() {
        ServerLifecycleEvents.SERVER_STARTED.register(s -> Safe.run("oldlook load", OldLook::load));
        CommandRegistrationCallback.EVENT.register((d, r, e) -> d.register(Commands.literal("oldlook")
                .executes(c -> toggle(c.getSource().getPlayerOrException()))));
        ServerPlayConnectionEvents.JOIN.register((handler, sender, s) -> {
            ServerPlayer p = handler.getPlayer();
            if (!url.isEmpty() && ON.contains(p.getUUID())) Lobby.runLaterTicks(40, () -> Safe.run("oldlook join", () -> {
                if (!p.hasDisconnected()) send(p);
            }));
        });
    }
}
