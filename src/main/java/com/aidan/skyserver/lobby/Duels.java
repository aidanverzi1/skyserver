package com.aidan.skyserver.lobby;

import com.mojang.brigadier.arguments.LongArgumentType;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;

import java.util.*;

/**
 * 1v1 duels in the lobby's Duel Arena, with an optional money bet.
 *
 *  - /duel <player> [bet] sends a challenge; they click Accept (or /duel accept).
 *  - Both players pay the bet when the duel starts; the winner gets both bets. A draw (time runs
 *    out with equal health, or the server stops) gives the bets back.
 *  - 5.17: everyone gets the same kit: unenchanted diamond armour, a diamond sword, 1 golden apple and
 *    1 golden carrot (unbreakable). The lobby hotbar comes back when the duel ends.
 *  - Leaving the pit or logging out loses the duel. Duels last up to 3 minutes; after that the
 *    one with more health wins.
 */
public final class Duels {
    public static final long MAX_BET = 1_000_000;
    private static final int COUNTDOWN = 3 * 20, MAX_TICKS = 180 * 20, REQUEST_TICKS = 60 * 20;

    private record Request(UUID from, String fromName, long bet, long expires) {}

    private static final class Duel {
        final UUID a, b;
        final String aName, bName;
        final int pit;
        final long bet, start;
        boolean over = false;

        Duel(ServerPlayer a, ServerPlayer b, int pit, long bet, long start) {
            this.a = a.getUUID();
            this.b = b.getUUID();
            this.aName = a.getName().getString();
            this.bName = b.getName().getString();
            this.pit = pit;
            this.bet = bet;
            this.start = start;
        }

        UUID other(UUID u) {
            return u.equals(a) ? b : a;
        }

        boolean fighting(long now) {
            return !over && now - start >= COUNTDOWN;
        }
    }

    private static final Map<UUID, Request> REQUESTS = new HashMap<>(); // target -> request
    private static final Map<UUID, Duel> DUELS = new HashMap<>();       // both players -> duel
    private static final Duel[] PITS = new Duel[ActivityDesign.DUEL_PITS.length];
    private static MinecraftServer server;
    private static long now = 0;

    private Duels() {}

    static void init() {
        ServerLifecycleEvents.SERVER_STARTED.register(s -> server = s);
        ServerLifecycleEvents.SERVER_STOPPING.register(s -> {
            // Give the bets back for anything still going.
            for (Duel d : new HashSet<>(DUELS.values())) refund(d);
        });
        // A duel never kills anyone: the loser is caught at "death" and the duel ends.
        ServerLivingEntityEvents.ALLOW_DEATH.register((entity, source, amount) -> {
            Duel d = entity instanceof ServerPlayer p ? DUELS.get(p.getUUID()) : null;
            if (d == null || d.over) return true;
            ServerPlayer loser = (ServerPlayer) entity;
            loser.setHealth(loser.getMaxHealth());
            ServerPlayer winner = server.getPlayerList().getPlayer(d.other(loser.getUUID()));
            end(d, winner, loser, "won the duel");
            return false;
        });
        CommandRegistrationCallback.EVENT.register((disp, reg, env) -> disp.register(Commands.literal("duel")
                .then(Commands.literal("bot")
                        .executes(c -> { botDuel(c.getSource().getPlayerOrException(), (String) null); return 1; })
                        .then(Commands.argument("difficulty", com.mojang.brigadier.arguments.StringArgumentType.word())
                                .suggests((c, b) -> net.minecraft.commands.SharedSuggestionProvider.suggest(List.of("easy", "medium", "hard"), b))
                                .executes(c -> {
                                    botDuel(c.getSource().getPlayerOrException(), com.mojang.brigadier.arguments.StringArgumentType.getString(c, "difficulty"));
                                    return 1;
                                })))
                .then(Commands.literal("accept").executes(c -> { accept(c.getSource().getPlayerOrException()); return 1; }))
                .then(Commands.literal("deny").executes(c -> { deny(c.getSource().getPlayerOrException()); return 1; }))
                .then(Commands.argument("player", EntityArgument.player())
                        .executes(c -> { challenge(c.getSource().getPlayerOrException(), EntityArgument.getPlayer(c, "player"), 0); return 1; })
                        .then(Commands.argument("bet", LongArgumentType.longArg(0, MAX_BET))
                                .executes(c -> {
                                    challenge(c.getSource().getPlayerOrException(), EntityArgument.getPlayer(c, "player"), LongArgumentType.getLong(c, "bet"));
                                    return 1;
                                })))));
    }

    // =====================================================================
    // Asking
    // =====================================================================

    private static MutableComponent button(String text, String command, String hover) {
        return Component.literal(text).withStyle(Style.EMPTY.withClickEvent(new ClickEvent.RunCommand(command))
                .withHoverEvent(new HoverEvent.ShowText(Component.literal(hover))));
    }

    private static void challenge(ServerPlayer p, ServerPlayer target, long bet) {
        if (server == null) server = p.level().getServer();
        if (target == p) {
            Lobby.msg(p, "§cYou can't duel yourself.");
            return;
        }
        if (target instanceof com.aidan.skyserver.bots.BotPlayer) {
            Lobby.msg(p, "§cYou can only duel real players.");
            return;
        }
        if (!Lobby.isLobby(p.level()) || !Lobby.isLobby(target.level())) {
            Lobby.msg(p, "§cYou both need to be in the lobby to duel.");
            return;
        }
        if (inDuel(p.getUUID()) || inDuel(target.getUUID())) {
            Lobby.msg(p, "§cOne of you is already in a duel.");
            return;
        }
        if (bet > 0 && Economy.balance(server, p.getUUID()) < bet) {
            Lobby.msg(p, "§cYou don't have " + Economy.format(bet) + " to bet.");
            return;
        }
        REQUESTS.put(target.getUUID(), new Request(p.getUUID(), p.getName().getString(), bet, now + REQUEST_TICKS));
        String betText = bet > 0 ? " §7for §6" + Economy.format(bet) + " §7each (winner takes " + Economy.format(bet * 2) + ")" : " §7(no bet)";
        Lobby.msg(p, "§eDuel challenge sent to §f" + target.getName().getString() + betText + "§e.");
        target.sendSystemMessage(Component.literal("§c§l⚔ §e" + p.getName().getString() + " §echallenges you to a duel" + betText + "§e! ")
                .append(button("§a§l[Accept]", "/duel accept", "Accept the duel" + (bet > 0 ? " and bet " + Economy.format(bet) : "")))
                .append(Component.literal(" "))
                .append(button("§c§l[Deny]", "/duel deny", "Say no")));
    }

    private static void deny(ServerPlayer p) {
        Request r = REQUESTS.remove(p.getUUID());
        if (r == null) {
            Lobby.msg(p, "§7Nobody has challenged you.");
            return;
        }
        Lobby.msg(p, "§7You turned down the duel.");
        ServerPlayer from = server.getPlayerList().getPlayer(r.from());
        if (from != null) Lobby.msg(from, "§7" + p.getName().getString() + " turned down your duel.");
    }

    private static void accept(ServerPlayer p) {
        Request r = REQUESTS.remove(p.getUUID());
        if (r == null || r.expires() < now) {
            Lobby.msg(p, "§7You don't have a duel challenge (they last 60 seconds).");
            return;
        }
        ServerPlayer from = server.getPlayerList().getPlayer(r.from());
        if (from == null || !Lobby.isLobby(from.level()) || !Lobby.isLobby(p.level())) {
            Lobby.msg(p, "§cYou both need to be in the lobby.");
            return;
        }
        if (inDuel(from.getUUID()) || inDuel(p.getUUID())) {
            Lobby.msg(p, "§cOne of you is already in a duel.");
            return;
        }
        int pit = -1;
        for (int i = 0; i < PITS.length; i++) if (PITS[i] == null) { pit = i; break; }
        if (pit < 0) {
            Lobby.msg(p, "§cAll duel pits are busy - try again in a minute.");
            Lobby.msg(from, "§cAll duel pits are busy - try again in a minute.");
            return;
        }
        long bet = r.bet();
        if (bet > 0) {
            if (!Economy.take(server, from.getUUID(), bet)) {
                Lobby.msg(p, "§c" + from.getName().getString() + " doesn't have the " + Economy.format(bet) + " any more.");
                Lobby.msg(from, "§cYou don't have the " + Economy.format(bet) + " bet any more.");
                return;
            }
            if (!Economy.take(server, p.getUUID(), bet)) {
                Economy.add(server, from.getUUID(), from.getName().getString(), bet);
                Lobby.msg(p, "§cYou don't have " + Economy.format(bet) + " to bet.");
                Lobby.msg(from, "§c" + p.getName().getString() + " couldn't cover the bet.");
                return;
            }
        }
        start(from, p, pit, bet);
    }

    /** /duel bot [easy|medium|hard]: a practice duel against a bot (no bets). */
    /** A practice duel against a bot of this difficulty (the Gauntlet uses this). False if it couldn't start. */
    public static boolean botDuel(ServerPlayer p, com.aidan.skyserver.bots.Difficulty diff) {
        if (server == null) server = p.level().getServer();
        if (!Lobby.isLobby(p.level()) || inDuel(p.getUUID())) return false;
        int pit = -1;
        for (int i = 0; i < PITS.length; i++) if (PITS[i] == null) { pit = i; break; }
        if (pit < 0) {
            Lobby.msg(p, "§cAll duel pits are busy - try again in a minute.");
            return false;
        }
        Activities.leaveAll(p);
        com.aidan.skyserver.bots.BotPlayer bot = com.aidan.skyserver.bots.BotManager.spawn(diff);
        bot.setBrain(new com.aidan.skyserver.bots.DuelBrain(bot));
        start(bot, p, pit, 0);
        return true;
    }

    private static void botDuel(ServerPlayer p, String diffName) {
        if (server == null) server = p.level().getServer();
        if (!Lobby.isLobby(p.level())) {
            Lobby.msg(p, "§cGo to the lobby first (§e/lobby§c).");
            return;
        }
        if (inDuel(p.getUUID())) return;
        com.aidan.skyserver.bots.Difficulty diff = com.aidan.skyserver.bots.BotManager.randomDifficulty();
        if (diffName != null) {
            try {
                diff = com.aidan.skyserver.bots.Difficulty.valueOf(diffName.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                Lobby.msg(p, "§cDifficulty is easy, medium or hard.");
                return;
            }
        }
        int pit = -1;
        for (int i = 0; i < PITS.length; i++) if (PITS[i] == null) { pit = i; break; }
        if (pit < 0) {
            Lobby.msg(p, "§cAll duel pits are busy - try again in a minute.");
            return;
        }
        com.aidan.skyserver.bots.BotPlayer bot = com.aidan.skyserver.bots.BotManager.spawn(diff);
        bot.setBrain(new com.aidan.skyserver.bots.DuelBrain(bot));
        Lobby.msg(p, "§7Practice duel against a §f" + diff.name().toLowerCase() + "§7 bot (no bet).");
        start(bot, p, pit, 0);
    }

    // ---- For the duel bots ----

    public static ServerPlayer opponentOf(ServerPlayer p) {
        Duel d = DUELS.get(p.getUUID());
        return d == null || server == null ? null : server.getPlayerList().getPlayer(d.other(p.getUUID()));
    }

    public static boolean fighting(ServerPlayer p) {
        Duel d = DUELS.get(p.getUUID());
        return d != null && d.fighting(now);
    }

    public static ActivityDesign.Area pitOf(ServerPlayer p) {
        Duel d = DUELS.get(p.getUUID());
        return d == null ? null : ActivityDesign.duelInterior(d.pit);
    }

    // =====================================================================
    // Fighting
    // =====================================================================

    private static void start(ServerPlayer a, ServerPlayer b, int pit, long bet) {
        Duel d = new Duel(a, b, pit, bet, now);
        PITS[pit] = d;
        int side = 0;
        for (ServerPlayer p : new ServerPlayer[]{a, b}) {
            Activities.leaveAll(p);
            Parkour.cancel(p, false);
            Trade.cancel(p);
            DUELS.put(p.getUUID(), d);
            ActivityDesign.Spot s = ActivityDesign.duelSpawn(pit, side++);
            p.teleportTo(Lobby.level(), s.x(), s.y(), s.z(), Set.of(), s.yaw(), 0f, true);
            p.setDeltaMovement(0, 0, 0);
            p.removeAllEffects();
            p.setHealth(p.getMaxHealth());
            p.getFoodData().setFoodLevel(20);
            Lobby.refreshKit(p);
            String betText = bet > 0 ? " §7for §6" + Economy.format(bet * 2) : "";
            Lobby.msg(p, "§c§lDUEL §fvs " + (p == a ? d.bName : d.aName) + betText + "§7. 3 minutes. Leaving the pit loses the duel.");
        }
    }

    /** The duel kit (called from the lobby hotbar refresh). 5.17: diamond gear, 1 golden apple, 1 golden carrot. */
    static void kit(ServerPlayer p) {
        Inventory inv = p.getInventory();
        inv.clearContent();
        inv.setItem(0, Activities.unbreakable(new ItemStack(Items.DIAMOND_SWORD)));
        inv.setItem(1, new ItemStack(Items.GOLDEN_APPLE, 1));
        inv.setItem(2, new ItemStack(Items.GOLDEN_CARROT, 1));
        p.setItemSlot(EquipmentSlot.OFFHAND, ItemStack.EMPTY);
        p.setItemSlot(EquipmentSlot.HEAD, Activities.unbreakable(new ItemStack(Items.DIAMOND_HELMET)));
        p.setItemSlot(EquipmentSlot.CHEST, Activities.unbreakable(new ItemStack(Items.DIAMOND_CHESTPLATE)));
        p.setItemSlot(EquipmentSlot.LEGS, Activities.unbreakable(new ItemStack(Items.DIAMOND_LEGGINGS)));
        p.setItemSlot(EquipmentSlot.FEET, Activities.unbreakable(new ItemStack(Items.DIAMOND_BOOTS)));
    }

    public static boolean inDuel(UUID id) {
        return DUELS.containsKey(id);
    }

    static boolean allowUse(UUID id, ItemStack held) {
        Duel d = DUELS.get(id);
        if (d == null || !d.fighting(now)) return false;
        return held.is(Items.GOLDEN_APPLE) || held.is(Items.GOLDEN_CARROT);
    }

    /** Can this player hit that entity in the lobby? Only your opponent, once the fight starts. */
    public static boolean canHit(Entity attacker, Entity victim) {
        Duel d = attacker == null ? null : DUELS.get(attacker.getUUID());
        return d != null && d.fighting(now) && victim.getUUID().equals(d.other(attacker.getUUID()));
    }

    /** Lobby damage: only between the two duellists (melee, arrows, and falls in the pit). */
    public static boolean allowDamage(LivingEntity victim, DamageSource source) {
        Duel d = DUELS.get(victim.getUUID());
        if (d == null || !d.fighting(now)) return false;
        Entity src = source.getEntity();
        if (src == null && source.getDirectEntity() instanceof Projectile pr) src = pr.getOwner();
        boolean ok = src == null || src.getUUID().equals(d.other(victim.getUUID()));
        if (ok && src instanceof ServerPlayer sp && !(sp instanceof com.aidan.skyserver.bots.BotPlayer))
            com.aidan.skyserver.bots.MiniLearning.observeDuelHit(source.getDirectEntity() instanceof Projectile);
        return ok;
    }

    /** Leaving the duel any other way (logging out, going to a game) loses it. */
    static void forfeit(ServerPlayer p) {
        Duel d = DUELS.get(p.getUUID());
        if (d == null || d.over) return;
        ServerPlayer winner = server.getPlayerList().getPlayer(d.other(p.getUUID()));
        end(d, winner, p, "won - the other player left");
    }

    static void tick(ServerLevel lobby, long ticks) {
        now = ticks;
        if (server == null) server = lobby.getServer();
        REQUESTS.values().removeIf(r -> r.expires() < now);
        for (int i = 0; i < PITS.length; i++) {
            Duel d = PITS[i];
            ActivityDesign.Area inside = ActivityDesign.duelInterior(i);
            // Spectators who jump in are put back outside.
            for (ServerPlayer p : lobby.players()) {
                if (p instanceof com.aidan.skyserver.bots.BotPlayer || DUELS.containsKey(p.getUUID())) continue;
                if (inside.contains(p.getX(), p.getY(), p.getZ())) {
                    ActivityDesign.Spot s = ActivityDesign.DUEL_SPOT;
                    p.teleportTo(lobby, s.x(), s.y(), s.z(), Set.of(), s.yaw(), 0f, true);
                }
            }
            if (d == null) continue;
            ServerPlayer a = server.getPlayerList().getPlayer(d.a), b = server.getPlayerList().getPlayer(d.b);
            if (a == null || !Lobby.isLobby(a.level())) { end(d, b, a, "won - the other player left"); continue; }
            if (b == null || !Lobby.isLobby(b.level())) { end(d, a, b, "won - the other player left"); continue; }
            long t = now - d.start;
            if (t < COUNTDOWN) {
                // Hold still at the spawns until the fight starts.
                int side = 0;
                for (ServerPlayer p : new ServerPlayer[]{a, b}) {
                    ActivityDesign.Spot s = ActivityDesign.duelSpawn(i, side++);
                    if (Math.hypot(p.getX() - s.x(), p.getZ() - s.z()) > 0.6) p.teleportTo(lobby, s.x(), s.y(), s.z(), Set.of(), p.getYRot(), p.getXRot(), true);
                    if (t % 20 == 0) {
                        int sec = (int) ((COUNTDOWN - t) / 20);
                        Announce.title(p, "§c" + sec, "§7Duel starting", 0, 22, 4);
                        Announce.ding(p, 1.0f + (3 - sec) * 0.15f);
                    }
                }
                continue;
            }
            if (t == COUNTDOWN) for (ServerPlayer p : new ServerPlayer[]{a, b}) {
                Announce.title(p, "§c§lFIGHT!", "", 0, 20, 10);
                Announce.ding(p, 2f);
            }
            if (!inside.contains(a.getX(), a.getY(), a.getZ())) { end(d, b, a, "won - the other player left the pit"); continue; }
            if (!inside.contains(b.getX(), b.getY(), b.getZ())) { end(d, a, b, "won - the other player left the pit"); continue; }
            if (t >= COUNTDOWN + MAX_TICKS) {
                float ha = a.getHealth(), hb = b.getHealth();
                if (Math.abs(ha - hb) < 0.5f) {
                    for (ServerPlayer p : new ServerPlayer[]{a, b}) Lobby.msg(p, "§eTime's up - it's a draw!" + (d.bet > 0 ? " §7Bets returned." : ""));
                    refund(d);
                } else if (ha > hb) end(d, a, b, "won on health (time ran out)");
                else end(d, b, a, "won on health (time ran out)");
                continue;
            }
            long left = (COUNTDOWN + MAX_TICKS - t) / 20;
            if (t % 10 == 0) {
                Announce.actionBar(a, "§cDuel §7vs §f" + d.bName + "  §7their health §c" + (int) Math.ceil(b.getHealth()) + "  §7time §f" + left + "s");
                Announce.actionBar(b, "§cDuel §7vs §f" + d.aName + "  §7their health §c" + (int) Math.ceil(a.getHealth()) + "  §7time §f" + left + "s");
            }
        }
    }

    private static void cleanup(Duel d, UUID winner) {
        d.over = true;
        DUELS.remove(d.a);
        DUELS.remove(d.b);
        if (PITS[d.pit] == d) PITS[d.pit] = null;
        ServerLevel lobby = Lobby.level();
        if (lobby != null) {
            ActivityDesign.Area in = ActivityDesign.duelInterior(d.pit);
            for (AbstractArrow arrow : lobby.getEntitiesOfClass(AbstractArrow.class, new AABB(in.x1(), in.y1(), in.z1(), in.x2() + 1, in.y2() + 1, in.z2() + 1))) arrow.discard();
        }
        boolean botDuel = false;
        for (UUID u : new UUID[]{d.a, d.b}) {
            ServerPlayer p = server.getPlayerList().getPlayer(u);
            if (p instanceof com.aidan.skyserver.bots.BotPlayer bot) {
                // Bots learn how their plan did, then log out.
                botDuel = true;
                if (bot.brain() instanceof com.aidan.skyserver.bots.DuelBrain brain && winner != null)
                    com.aidan.skyserver.bots.MiniLearning.duelResult(brain.plan(), winner.equals(u), winner.equals(u) ? bot.getHealth() / bot.getMaxHealth() : 0);
                Lobby.runLater(() -> com.aidan.skyserver.bots.BotManager.remove(bot));
                continue;
            }
            if (p == null || !Lobby.isLobby(p.level())) continue;
            p.setHealth(p.getMaxHealth());
            p.removeAllEffects();
            p.setRemainingFireTicks(0);
            for (EquipmentSlot slot : new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET, EquipmentSlot.OFFHAND})
                p.setItemSlot(slot, ItemStack.EMPTY);
            ActivityDesign.Spot s = ActivityDesign.DUEL_SPOT;
            p.teleportTo(lobby, s.x(), s.y(), s.z(), Set.of(), s.yaw(), 0f, true);
            Lobby.refreshKit(p);
        }
        if (botDuel) com.aidan.skyserver.bots.MiniLearning.save();
    }

    private static void refund(Duel d) {
        if (d.over) return;
        if (d.bet > 0) {
            Economy.add(server, d.a, d.aName, d.bet);
            Economy.add(server, d.b, d.bName, d.bet);
        }
        cleanup(d, null);
    }

    private static void end(Duel d, ServerPlayer winner, ServerPlayer loser, String how) {
        if (d.over) return;
        if (winner == null) { // both gone
            refund(d);
            return;
        }
        final ServerPlayer w0 = winner, l0 = loser;
        Lobby.runLater(() -> SkillHalls.duelOver(w0, l0));
        UUID w = winner.getUUID();
        String wName = w.equals(d.a) ? d.aName : d.bName, lName = w.equals(d.a) ? d.bName : d.aName;
        // Bot health is read before cleanup heals it.
        cleanup(d, w);
        if (d.bet > 0) Economy.add(server, w, wName, d.bet * 2);
        String prize = d.bet > 0 ? " §7and takes §6" + Economy.format(d.bet * 2) : "";
        Lobby.msg(winner, "§6§lYOU WIN! §e" + wName + " " + how + prize + "§7.");
        Announce.title(winner, "§6§lVICTORY", "§7vs " + lName, 5, 40, 10);
        winner.level().playSound(null, winner.getX(), winner.getY(), winner.getZ(), SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 1f, 1f);
        if (loser != null) {
            Lobby.msg(loser, "§c§lDEFEAT. §e" + wName + " " + how + prize + "§7.");
            Announce.title(loser, "§c§lDEFEAT", "§7vs " + wName, 5, 40, 10);
        }
        Quests.progress(winner, "duel", 1);
        Skills.add(winner, Skills.Skill.COMBAT, loser instanceof com.aidan.skyserver.bots.BotPlayer ? 60 : 200);
        PlayerStats.add(winner, "duel_win", 1);
        // Big bets are worth telling the lobby about.
        if (d.bet >= 5_000 && Lobby.level() != null) {
            for (ServerPlayer p : Lobby.level().players())
                if (!p.getUUID().equals(w) && (loser == null || !p.getUUID().equals(loser.getUUID())))
                    Lobby.msg(p, "§c⚔ §e" + wName + " §7beat §e" + lName + " §7in a §6" + Economy.format(d.bet * 2) + " §7duel!");
        }
    }
}
