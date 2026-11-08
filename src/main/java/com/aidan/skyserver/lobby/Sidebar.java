package com.aidan.skyserver.lobby;

import com.aidan.skyserver.island.Islands;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.numbers.BlankFormat;
import net.minecraft.network.protocol.game.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.criteria.ObjectiveCriteria;

import java.util.*;

/**
 * The sidebar on the right of the screen (money, bank, keys, level, battle pass, island, quest,
 * party, events) and the tab-list header/footer (players online, game status). Each player gets
 * their own sidebar, sent straight to them as packets, so it doesn't touch the server's real
 * scoreboard (Gold Wars team colours keep working). Updates every 2 seconds, only sending lines
 * that changed. /sidebar turns it off or on for you.
 */
public final class Sidebar {
    private static final String OBJ = "ss_side";
    private static final Scoreboard DUMMY_BOARD = new Scoreboard();
    private static final Objective OBJECTIVE = new Objective(DUMMY_BOARD, OBJ, ObjectiveCriteria.DUMMY,
            Component.literal("§6§lSKYSERVER"), ObjectiveCriteria.RenderType.INTEGER, false, BlankFormat.INSTANCE);

    private static final Map<UUID, List<String>> SHOWN = new HashMap<>();
    private static final Set<UUID> HIDDEN = new HashSet<>();
    private static final Map<UUID, String> TAB_SHOWN = new HashMap<>();

    private Sidebar() {}

    public static void init() {
        CommandRegistrationCallback.EVENT.register((d, r, e) -> d.register(Commands.literal("sidebar").executes(c -> {
            ServerPlayer p = c.getSource().getPlayerOrException();
            if (HIDDEN.remove(p.getUUID())) Lobby.msg(p, "§aSidebar on.");
            else {
                HIDDEN.add(p.getUUID());
                hide(p);
                Lobby.msg(p, "§7Sidebar off. §e/sidebar §7to turn it back on.");
            }
            return 1;
        })));
        ServerPlayConnectionEvents.DISCONNECT.register((h, s) -> {
            SHOWN.remove(h.getPlayer().getUUID());
            TAB_SHOWN.remove(h.getPlayer().getUUID());
        });
    }

    static void tick(MinecraftServer server, long ticks) {
        if (ticks % 40 != 7) return;
        int online = 0;
        for (ServerPlayer p : server.getPlayerList().getPlayers()) if (!(p instanceof com.aidan.skyserver.bots.BotPlayer)) online++;
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (p instanceof com.aidan.skyserver.bots.BotPlayer) continue;
            try {
                tab(p, online);
                if (HIDDEN.contains(p.getUUID())) continue;
                update(p, lines(server, p));
            } catch (RuntimeException e) {
                com.aidan.skyserver.Skyserver.LOGGER.warn("Sidebar update failed for {}: {}", p.getName().getString(), e.toString());
            }
        }
    }

    private static List<String> lines(MinecraftServer server, ServerPlayer p) {
        UUID id = p.getUUID();
        List<String> bw = com.aidan.skyserver.bedwars.BedwarsGame.sidebar(id);
        if (bw != null) return bw;
        List<String> l = new ArrayList<>();
        long cash = Economy.balance(server, id);
        l.add("§fMoney: §a" + (cash < 0 ? "?" : Economy.format(cash)));
        long bank = Bank.balance(id);
        if (bank > 0) l.add("§fBank: §a" + Economy.format(bank));
        int[] k = Keys.of(id);
        int keys = 0;
        for (int x : k) keys += x;
        l.add("§fKeys: §e" + keys + (k[2] + k[3] + k[4] > 0 ? " §7(" + (k[2] + k[3] + k[4]) + " rare+)" : ""));
        int pr = Levels.prestige(id);
        l.add("§fLevel: " + (pr > 0 ? Progression.label(pr) + " §7P" + pr : "§e" + Levels.level(id)));
        PlayerProfile prof = PlayerCache.ifOnline(id);
        int tier = prof == null || prof.passSeason != BattlePass.season() ? 0 : BattlePass.tierOf(prof.passXp);
        l.add("§fPass: §6tier " + tier + " §7S" + BattlePass.season() + " §8/pass");
        l.add("");
        Islands.Island is = Islands.islandOf(id);
        if (is == null) l.add("§fIsland: §7none §8/is");
        else {
            l.add("§fIsland: §bworth " + is.worth() + " §7" + is.size + "x" + is.size);
            l.add("§fWren: " + com.aidan.skyserver.island.IslandQuests.progressShort(is));
        }
        String f = Factions.factionOf(id);
        if (f != null) l.add("§fFaction: §c" + f);
        Set<UUID> party = Parties.membersOf(id);
        if (!party.isEmpty()) l.add("§fParty: §d" + party.size() + " §7/pc");
        String ev = Events.sidebarLine();
        if (ev != null) {
            l.add("  ");
            l.add(ev);
            if (Events.tournamentOn()) l.add("§fYour points: §b" + Events.tournamentPoints(id));
        }
        l.add("   ");
        l.add("§7/guide §8• §7/pass §8• §7/party");
        return l;
    }

    private static void update(ServerPlayer p, List<String> lines) {
        List<String> old = SHOWN.get(p.getUUID());
        if (old == null) {
            p.connection.send(new ClientboundSetObjectivePacket(OBJECTIVE, ClientboundSetObjectivePacket.METHOD_ADD));
            p.connection.send(new ClientboundSetDisplayObjectivePacket(DisplaySlot.SIDEBAR, OBJECTIVE));
            old = new ArrayList<>();
        } else if (p.tickCount % 200 < 40) {
            // Another mod (EconomyCraft's money board) can grab the sidebar slot: take it back now and then.
            p.connection.send(new ClientboundSetDisplayObjectivePacket(DisplaySlot.SIDEBAR, OBJECTIVE));
        }
        // Each line is an invisible owner "#<n>" with its text as the display name; higher score = higher up.
        for (int i = 0; i < lines.size(); i++) {
            if (i < old.size() && old.get(i).equals(lines.get(i))) continue;
            p.connection.send(new ClientboundSetScorePacket("§" + Integer.toHexString(i / 16) + "§" + Integer.toHexString(i % 16) + "§r", OBJ, lines.size() - i,
                    Optional.of(Component.literal(lines.get(i))), Optional.of(BlankFormat.INSTANCE)));
        }
        if (old.size() != lines.size()) {
            // Line count changed: fix every score so the order stays right, and drop extra lines.
            for (int i = 0; i < lines.size(); i++)
                p.connection.send(new ClientboundSetScorePacket("§" + Integer.toHexString(i / 16) + "§" + Integer.toHexString(i % 16) + "§r", OBJ, lines.size() - i,
                        Optional.of(Component.literal(lines.get(i))), Optional.of(BlankFormat.INSTANCE)));
            for (int i = lines.size(); i < old.size(); i++) p.connection.send(new ClientboundResetScorePacket("§" + Integer.toHexString(i / 16) + "§" + Integer.toHexString(i % 16) + "§r", OBJ));
        }
        SHOWN.put(p.getUUID(), new ArrayList<>(lines));
    }

    private static void hide(ServerPlayer p) {
        if (SHOWN.remove(p.getUUID()) != null)
            p.connection.send(new ClientboundSetObjectivePacket(OBJECTIVE, ClientboundSetObjectivePacket.METHOD_REMOVE));
    }

    private static void tab(ServerPlayer p, int online) {
        String where = Lobby.isLobby(p.level()) ? "Lobby" : Islands.isIsland(p.level()) ? "Sky Islands"
                : com.aidan.skyserver.goldwars.GoldWarsGame.isInGame(p.getUUID()) ? "Gold Wars"
                : com.aidan.skyserver.bedwars.BedwarsGame.isInGame(p.getUUID()) ? "Bedwars"
                : com.aidan.skyserver.skywars.SkywarsGame.isInGame(p.getUUID()) ? "Skywars" : "Survival";
        String header = "§6§lSKYSERVER\n§7" + online + " player" + (online == 1 ? "" : "s") + " online §8• §f" + where;
        String sw = Lobby.gameStatus("skywars"), gw = Lobby.gameStatus("goldwars"), bwStatus = Lobby.gameStatus("bedwars");
        String ev = Events.sidebarLine();
        String footer = "§bSkywars: §f" + (sw.isEmpty() ? "-" : sw) + "\n§6Gold Wars: §f" + (gw.isEmpty() ? "-" : gw)
                + "\n§cBedwars: §f" + (bwStatus.isEmpty() ? "-" : bwStatus)
                + (ev != null ? "\n" + ev : "") + "\n§7/guide  /pass  /party  /events";
        String both = header + "|" + footer;
        if (both.equals(TAB_SHOWN.get(p.getUUID()))) return;
        TAB_SHOWN.put(p.getUUID(), both);
        p.connection.send(new ClientboundTabListPacket(Component.literal(header), Component.literal(footer)));
    }
}
