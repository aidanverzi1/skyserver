package com.aidan.skyserver.lobby;

import com.aidan.skyserver.Skyserver;
import com.google.common.collect.LinkedHashMultimap;
import com.google.gson.*;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.mojang.authlib.properties.PropertyMap;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.Commands;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ResolvableProfile;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;

/**
 * The Skin Shop (in the Town, or /skins in the lobby). Players buy skins and switch between the ones
 * they own; "My own skin" puts their real skin back. Everyone sees the new skin right away.
 *
 * Normal Minecraft clients only show skins signed by Mojang, so every skin in the shop is a real
 * uploaded skin: the server fetches its signed data from MineSkin (a free public skin library,
 * mineskin.org) or from a Minecraft account. Set skins up with:
 *     /skinshop set <id> <mineskin link or id>    e.g. /skinshop set knight https://mineskin.org/skins/<id>
 *     /skinshop set <id> player:<MinecraftName>   copies that account's current skin
 *     /skinshop add <id> <price> <name...>        a new shop slot
 *     /skinshop remove <id>, /skinshop list
 * The catalogue is saved in config/skyserver/skins.json (you can also edit it by hand).
 */
public final class SkinShop {
    record Skin(String id, String name, long price, String rarity, String source, String value, String signature) {
        boolean ready() {
            return value != null && !value.isEmpty() && signature != null && !signature.isEmpty();
        }
    }

    private static final LinkedHashMap<String, Skin> CATALOGUE = new LinkedHashMap<>();
    private static final Map<UUID, Set<String>> OWNED = new HashMap<>();
    private static final Map<UUID, String> WORN = new HashMap<>();
    private static final Map<UUID, Property> REAL = new HashMap<>(); // their real skin, to put back
    /** Everyone's starter look, picked the first time they join: "starter_male" or "starter_female". */
    private static final Map<UUID, String> BASE = new HashMap<>();
    static final String MALE = "starter_male", FEMALE = "starter_female";
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private static boolean loaded = false;

    /** The starting catalogue: names and prices; the skins themselves are set with /skinshop set. */
    private static final String[][] DEFAULTS = {
            {"knight", "Knight", "Common"}, {"pirate", "Pirate", "Common"}, {"farmer", "Farmer", "Common"}, {"miner", "Miner", "Common"},
            {"chef", "Chef", "Common"}, {"astronaut", "Astronaut", "Common"}, {"ninja", "Ninja", "Rare"}, {"wizard", "Wizard", "Rare"},
            {"robot", "Robot", "Rare"}, {"viking", "Viking", "Rare"}, {"samurai", "Samurai", "Rare"}, {"archer", "Archer", "Rare"},
            {"sky_captain", "Sky Captain", "Rare"}, {"diver", "Deep Sea Diver", "Rare"}, {"vampire", "Vampire", "Epic"},
            {"frost_king", "Frost King", "Epic"}, {"lava_golem", "Lava Golem", "Epic"}, {"cyber_hacker", "Cyber Hacker", "Epic"},
            {"forest_spirit", "Forest Spirit", "Epic"}, {"phoenix", "Phoenix", "Legendary"}, {"void_walker", "Void Walker", "Legendary"},
            {"dragon_knight", "Dragon Knight", "Legendary"}, {"golden_emperor", "Golden Emperor", "Legendary"}, {"cosmic", "Cosmic Traveller", "Legendary"}};

    static long priceFor(String rarity) {
        return switch (rarity) { case "Rare" -> 12_000; case "Epic" -> 25_000; case "Legendary" -> 40_000; default -> 5_000; };
    }

    static String colorFor(String rarity) {
        return switch (rarity) { case "Rare" -> "§9"; case "Epic" -> "§5"; case "Legendary" -> "§6"; default -> "§f"; };
    }

    private SkinShop() {}

    public static void init() {
        CommandRegistrationCallback.EVENT.register((d, r, e) -> {
            d.register(Commands.literal("skins").executes(c -> {
                ServerPlayer p = c.getSource().getPlayerOrException();
                if (!Lobby.isLobby(p.level())) Lobby.msg(p, "§cThe Skin Shop is in the lobby (the Town). §7Type §e/lobby§7.");
                else open(p);
                return 1;
            }));
            var admin = Commands.literal("skinshop").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS));
            admin.then(Commands.literal("list").executes(c -> {
                load();
                for (Skin s : CATALOGUE.values())
                    c.getSource().sendSuccess(() -> Component.literal((s.ready() ? "§a✔ " : "§c✘ ") + colorFor(s.rarity()) + s.id() + " §7" + s.name() + " " + Economy.format(s.price())
                            + (s.source() == null || s.source().isEmpty() ? " §8(not set: /skinshop set " + s.id() + " <mineskin link>)" : " §8" + s.source())), false);
                return 1;
            }));
            admin.then(Commands.literal("set").then(Commands.argument("id", StringArgumentType.word())
                    .suggests((c, b) -> net.minecraft.commands.SharedSuggestionProvider.suggest(CATALOGUE.keySet(), b))
                    .then(Commands.argument("source", StringArgumentType.greedyString()).executes(c -> {
                        String id = StringArgumentType.getString(c, "id"), src = StringArgumentType.getString(c, "source").trim();
                        load();
                        Skin s = CATALOGUE.get(id);
                        if (s == null) {
                            c.getSource().sendFailure(Component.literal("No skin slot '" + id + "'. Add it with /skinshop add " + id + " <price> <name>."));
                            return 0;
                        }
                        c.getSource().sendSuccess(() -> Component.literal("Fetching the skin for " + id + "..."), false);
                        fetch(c.getSource().getServer(), src, (value, sig, err) -> {
                            if (err != null) {
                                c.getSource().sendFailure(Component.literal("Couldn't get that skin: " + err));
                                return;
                            }
                            CATALOGUE.put(id, new Skin(id, s.name(), s.price(), s.rarity(), src, value, sig));
                            save();
                            c.getSource().sendSuccess(() -> Component.literal("§aSkin '" + s.name() + "' is ready in the shop."), true);
                        });
                        return 1;
                    }))));
            admin.then(Commands.literal("add").then(Commands.argument("id", StringArgumentType.word())
                    .then(Commands.argument("price", IntegerArgumentType.integer(0))
                            .then(Commands.argument("name", StringArgumentType.greedyString()).executes(c -> {
                                load();
                                String id = StringArgumentType.getString(c, "id");
                                int price = IntegerArgumentType.getInteger(c, "price");
                                String rarity = price >= 40_000 ? "Legendary" : price >= 25_000 ? "Epic" : price >= 12_000 ? "Rare" : "Common";
                                CATALOGUE.put(id, new Skin(id, StringArgumentType.getString(c, "name"), price, rarity, "", "", ""));
                                save();
                                c.getSource().sendSuccess(() -> Component.literal("Added skin slot " + id + ". Now: /skinshop set " + id + " <mineskin link>"), true);
                                return 1;
                            })))));
            admin.then(Commands.literal("remove").then(Commands.argument("id", StringArgumentType.word()).executes(c -> {
                load();
                boolean ok = CATALOGUE.remove(StringArgumentType.getString(c, "id")) != null;
                save();
                c.getSource().sendSuccess(() -> Component.literal(ok ? "Removed." : "No such skin."), true);
                return 1;
            })));
            d.register(admin);
        });
    }

    // =====================================================================
    // Fetching signed skins
    // =====================================================================

    interface Done {
        void accept(String value, String signature, String error);
    }

    private static void fetch(MinecraftServer server, String source, Done done) {
        try {
            if (source.toLowerCase(Locale.ROOT).startsWith("player:")) {
                String name = source.substring(7).trim();
                get("https://api.mojang.com/users/profiles/minecraft/" + name).thenCompose(body -> {
                    String id = JsonParser.parseString(body).getAsJsonObject().get("id").getAsString();
                    return get("https://sessionserver.mojang.com/session/minecraft/profile/" + id + "?unsigned=false");
                }).whenComplete((body, err) -> server.execute(() -> finish(body, err, done)));
                return;
            }
            // A MineSkin link (mineskin.org/skins/<id>, minesk.in/<id>) or a bare id.
            String id = source.replaceAll("[?#].*$", "").replaceAll("/+$", "");
            id = id.substring(id.lastIndexOf('/') + 1);
            final String key = id;
            get("https://api.mineskin.org/v2/skins/" + key)
                    .exceptionallyCompose(t -> get("https://api.mineskin.org/get/uuid/" + key))
                    .whenComplete((body, err) -> server.execute(() -> finish(body, err, done)));
        } catch (Exception e) {
            done.accept(null, null, e.toString());
        }
    }

    private static java.util.concurrent.CompletableFuture<String> get(String url) {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(15))
                .header("User-Agent", "SkyServer-Minecraft/1.0").header("Accept", "application/json").GET().build();
        return HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString()).thenApply(r -> {
            if (r.statusCode() / 100 != 2) throw new RuntimeException("HTTP " + r.statusCode() + " from " + url);
            return r.body();
        });
    }

    private static void finish(String body, Throwable err, Done done) {
        if (err != null) {
            done.accept(null, null, err.getMessage() == null ? err.toString() : err.getMessage());
            return;
        }
        try {
            JsonObject found = findTexture(JsonParser.parseString(body));
            if (found == null) {
                done.accept(null, null, "no signed texture in the answer");
                return;
            }
            done.accept(found.get("value").getAsString(), found.get("signature").getAsString(), null);
        } catch (Exception e) {
            done.accept(null, null, e.toString());
        }
    }

    /** Finds an object with "value" and "signature" anywhere in the JSON (works for MineSkin and Mojang answers). */
    private static JsonObject findTexture(JsonElement e) {
        if (e.isJsonObject()) {
            JsonObject o = e.getAsJsonObject();
            if (o.has("value") && o.has("signature") && o.get("value").isJsonPrimitive() && o.get("signature").isJsonPrimitive()) return o;
            for (Map.Entry<String, JsonElement> c : o.entrySet()) {
                JsonObject f = findTexture(c.getValue());
                if (f != null) return f;
            }
        } else if (e.isJsonArray()) {
            for (JsonElement c : e.getAsJsonArray()) {
                JsonObject f = findTexture(c);
                if (f != null) return f;
            }
        }
        return null;
    }

    // =====================================================================
    // Wearing skins
    // =====================================================================

    private static void setTextures(ServerPlayer p, Property textures) {
        try {
            GameProfile old = p.getGameProfile();
            LinkedHashMultimap<String, Property> map = LinkedHashMultimap.create();
            old.properties().forEach((k, v) -> { if (!k.equals("textures")) map.put(k, v); });
            if (textures != null) map.put("textures", textures);
            GameProfile now = new GameProfile(old.id(), old.name(), new PropertyMap(map));
            java.lang.reflect.Field f = Player.class.getDeclaredField("gameProfile");
            f.setAccessible(true);
            f.set(p, now);
        } catch (Throwable t) {
            Skyserver.LOGGER.warn("Couldn't change {}'s skin: {}", p.getName().getString(), t.toString());
            return;
        }
        // Tell everyone about the new look (the tab list carries the skin).
        MinecraftServer server = p.level().getServer();
        server.getPlayerList().broadcastAll(new ClientboundPlayerInfoRemovePacket(List.of(p.getUUID())));
        server.getPlayerList().broadcastAll(ClientboundPlayerInfoUpdatePacket.createPlayerInitializing(List.of(p)));
    }

    /** Re-shows the player to everyone, including themselves, with a quick hop to another dimension and back. */
    private static void refresh(ServerPlayer p) {
        MinecraftServer server = p.level().getServer();
        ServerLevel here = (ServerLevel) p.level();
        ServerLevel other = server.getLevel(Lobby.isLobby(here) && here.dimension() == Lobby.LOBBY ? Lobby.OLD_LOBBY : Lobby.LOBBY);
        if (other == null || other == here) return;
        double x = p.getX(), y = p.getY(), z = p.getZ();
        float yaw = p.getYRot(), pitch = p.getXRot();
        p.teleportTo(other, x, 300, z, Set.of(), yaw, pitch, true);
        Lobby.runLater(() -> {
            if (p.hasDisconnected()) return;
            p.teleportTo(here, x, y, z, Set.of(), yaw, pitch, true);
            p.resetFallDistance();
            Lobby.refreshKit(p);
        });
    }

    /** The skin someone wears when they're not wearing a shop skin: their starter look (or their own skin until an admin sets the starters up). */
    private static Property baseTextures(ServerPlayer p) {
        Skin b = CATALOGUE.get(BASE.getOrDefault(p.getUUID(), ""));
        if (b != null && b.ready()) return new Property("textures", b.value(), b.signature());
        return REAL.get(p.getUUID());
    }

    private static void wear(ServerPlayer p, String id) {
        load();
        REAL.putIfAbsent(p.getUUID(), realTextures(p));
        if (id == null) {
            WORN.remove(p.getUUID());
            setTextures(p, baseTextures(p));
        } else {
            Skin s = CATALOGUE.get(id);
            if (s == null || !s.ready()) return;
            WORN.put(p.getUUID(), id);
            setTextures(p, new Property("textures", s.value(), s.signature()));
        }
        save();
        refresh(p);
    }

    private static Property realTextures(ServerPlayer p) {
        for (Property prop : p.getGameProfile().properties().get("textures")) return prop;
        return null;
    }

    /** On login: put their chosen skin back on (called after they arrive in the lobby). */
    public static void onJoin(ServerPlayer p) {
        load();
        if (p instanceof com.aidan.skyserver.bots.BotPlayer) return;
        REAL.put(p.getUUID(), realTextures(p));
        if (!BASE.containsKey(p.getUUID())) {
            // First time here: pick a starter look (male or female).
            Lobby.runLaterTicks(40, () -> { if (!p.hasDisconnected()) chooseStarter(p); });
            return;
        }
        String id = WORN.get(p.getUUID());
        Skin s = id == null ? null : CATALOGUE.get(id);
        Property tex = s != null && s.ready() ? new Property("textures", s.value(), s.signature()) : baseTextures(p);
        if (tex == null || tex == REAL.get(p.getUUID())) return;
        setTextures(p, tex);
        Lobby.runLater(() -> refresh(p));
    }

    /** The "choose your look" screen: two heads, male and female. */
    public static void chooseStarter(ServerPlayer p) {
        load();
        LockedMenu.open(p, "§8Choose your look", 3, StarterMenu::new);
    }

    private static final class StarterMenu extends LockedMenu {
        StarterMenu(int id, Inventory inv, SimpleContainer box, ServerPlayer viewer) {
            super(id, inv, box, 3, viewer);
            box.setItem(11, icon(MALE, "§b§lMale", "§7The classic male look"));
            box.setItem(15, icon(FEMALE, "§d§lFemale", "§7The classic female look"));
            box.setItem(22, Stacks.named(new ItemStack(Items.PAPER), "§fPick your look", "§7You can change it later in the", "§7Skin Shop (§e/skins§7), and buy more skins there."));
            frame(Stacks.named(new ItemStack(Items.STAINED_GLASS_PANE.pick(DyeColor.LIGHT_BLUE)), " "));
            broadcastChanges();
        }

        private ItemStack icon(String skinId, String name, String lore) {
            ItemStack head = new ItemStack(Items.PLAYER_HEAD);
            Skin s = CATALOGUE.get(skinId);
            if (s != null && s.ready()) {
                LinkedHashMultimap<String, Property> m = LinkedHashMultimap.create();
                m.put("textures", new Property("textures", s.value(), s.signature()));
                head.set(DataComponents.PROFILE, ResolvableProfile.createResolved(new GameProfile(UUID.nameUUIDFromBytes(skinId.getBytes()), "skin", new PropertyMap(m))));
            }
            return Stacks.named(head, name, lore, "§eClick to choose");
        }

        @Override
        protected void onClick(int slot, int button, boolean shift) {
            if (slot != 11 && slot != 15) return;
            String pick = slot == 11 ? MALE : FEMALE;
            BASE.put(viewer.getUUID(), pick);
            save();
            Lobby.later(viewer, () -> {
                if (!WORN.containsKey(viewer.getUUID())) wear(viewer, null);
                Lobby.msg(viewer, "§aLooking good! §7(Change it any time in the Skin Shop: §e/skins§7)");
            });
        }

        @Override
        public void removed(Player player) {
            super.removed(player);
            if (!BASE.containsKey(viewer.getUUID()) && !viewer.hasDisconnected())
                Lobby.msg(viewer, "§7You can pick your look any time with §e/skins§7 (it'll ask again next time you join).");
        }
    }

    // =====================================================================
    // The shop menu
    // =====================================================================

    public static void open(ServerPlayer p) {
        load();
        LockedMenu.open(p, "§8Skin Shop", 6, Menu::new);
    }

    private static final class Menu extends LockedMenu {
        private final List<String> ids = new ArrayList<>();

        Menu(int id, Inventory inv, SimpleContainer box, ServerPlayer viewer) {
            super(id, inv, box, 6, viewer);
            draw();
        }

        private void draw() {
            ids.clear();
            Set<String> owned = OWNED.getOrDefault(viewer.getUUID(), Set.of());
            String worn = WORN.get(viewer.getUUID());
            long bal = Economy.balance(viewer.level().getServer(), viewer.getUUID());
            int slot = 0;
            for (Skin s : CATALOGUE.values()) {
                if (slot >= 45) break;
                if (s.id().equals(MALE) || s.id().equals(FEMALE)) continue; // the starter looks have their own buttons
                ids.add(s.id());
                ItemStack head = new ItemStack(Items.PLAYER_HEAD);
                if (s.ready()) {
                    LinkedHashMultimap<String, Property> m = LinkedHashMultimap.create();
                    m.put("textures", new Property("textures", s.value(), s.signature()));
                    head.set(DataComponents.PROFILE, ResolvableProfile.createResolved(new GameProfile(UUID.nameUUIDFromBytes(s.id().getBytes()), "skin", new PropertyMap(m))));
                }
                String status = !s.ready() ? "§8Coming soon" : s.id().equals(worn) ? "§a§lWEARING" : owned.contains(s.id()) ? "§aOwned - click to wear"
                        : "§7Price: §6" + Economy.format(s.price()) + (bal >= s.price() ? " §e(click to buy)" : " §c(not enough money)");
                box.setItem(slot++, Stacks.named(head, colorFor(s.rarity()) + s.name(), "§7" + s.rarity() + " skin", status));
            }
            String base = BASE.getOrDefault(viewer.getUUID(), MALE);
            box.setItem(48, Stacks.named(new ItemStack(Items.DYE.pick(DyeColor.LIGHT_BLUE)), "§bStarter look: Male", base.equals(MALE) ? "§a(your starter)" : "§eClick to switch"));
            box.setItem(49, Stacks.named(new ItemStack(Items.ARMOR_STAND), "§fWear my starter look", "§7Take off your shop skin", worn == null ? "§a§lWEARING" : "§eClick"));
            box.setItem(50, Stacks.named(new ItemStack(Items.DYE.pick(DyeColor.PINK)), "§dStarter look: Female", base.equals(FEMALE) ? "§a(your starter)" : "§eClick to switch"));
            box.setItem(53, Stacks.named(new ItemStack(Items.GOLD_INGOT), "§6Your money: " + Economy.format(bal)));
            frame(Stacks.named(new ItemStack(Items.STAINED_GLASS_PANE.pick(DyeColor.MAGENTA)), " "));
            broadcastChanges();
        }

        @Override
        protected void onClick(int slot, int button, boolean shift) {
            if (slot == 48 || slot == 50) {
                BASE.put(viewer.getUUID(), slot == 48 ? MALE : FEMALE);
                save();
                if (!WORN.containsKey(viewer.getUUID())) Lobby.later(viewer, () -> wear(viewer, null));
                else draw();
                return;
            }
            if (slot == 49) {
                Lobby.later(viewer, () -> {
                    wear(viewer, null);
                    Lobby.msg(viewer, "§dBack to your starter look.");
                });
                return;
            }
            if (slot < 0 || slot >= ids.size()) return;
            Skin s = CATALOGUE.get(ids.get(slot));
            if (s == null || !s.ready()) {
                Lobby.msg(viewer, "§7That skin isn't in the shop yet.");
                return;
            }
            Set<String> owned = OWNED.computeIfAbsent(viewer.getUUID(), k -> new HashSet<>());
            if (!owned.contains(s.id())) {
                if (!Economy.take(viewer.level().getServer(), viewer.getUUID(), s.price())) {
                    Lobby.msg(viewer, "§cThe " + s.name() + " skin costs " + Economy.format(s.price()) + ".");
                    return;
                }
                owned.add(s.id());
                save();
                Lobby.msg(viewer, "§d§lNew skin: " + colorFor(s.rarity()) + s.name() + "§d! §7Putting it on...");
            }
            Lobby.later(viewer, () -> wear(viewer, s.id()));
        }
    }

    // =====================================================================
    // Saving
    // =====================================================================

    private static void load() {
        if (loaded) return;
        loaded = true;
        JsonObject cat = Stacks.readFile("skins.json");
        if (!cat.has("skins")) {
            for (String[] d : DEFAULTS) CATALOGUE.put(d[0], new Skin(d[0], d[1], priceFor(d[2]), d[2], "", "", ""));
        } else {
            for (JsonElement e : cat.getAsJsonArray("skins")) {
                JsonObject o = e.getAsJsonObject();
                String rarity = o.has("rarity") ? o.get("rarity").getAsString() : "Common";
                CATALOGUE.put(o.get("id").getAsString(), new Skin(o.get("id").getAsString(), o.get("name").getAsString(),
                        o.has("price") ? o.get("price").getAsLong() : priceFor(rarity), rarity,
                        str(o, "source"), str(o, "value"), str(o, "signature")));
            }
        }
        CATALOGUE.putIfAbsent(MALE, new Skin(MALE, "Starter (male)", 0, "Starter", "", "", ""));
        CATALOGUE.putIfAbsent(FEMALE, new Skin(FEMALE, "Starter (female)", 0, "Starter", "", "", ""));
        if (!cat.has("skins")) save();
        JsonObject own = Stacks.readFile("skin-owners.json");
        for (Map.Entry<String, JsonElement> e : own.entrySet()) {
            JsonObject o = e.getValue().getAsJsonObject();
            UUID id = UUID.fromString(e.getKey());
            Set<String> set = new HashSet<>();
            if (o.has("owned")) for (JsonElement x : o.getAsJsonArray("owned")) set.add(x.getAsString());
            OWNED.put(id, set);
            if (o.has("worn")) WORN.put(id, o.get("worn").getAsString());
            if (o.has("base")) BASE.put(id, o.get("base").getAsString());
        }
    }

    private static String str(JsonObject o, String k) {
        return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : "";
    }

    private static void save() {
        JsonObject cat = new JsonObject();
        cat.addProperty("_help", "Skins for the shop. Set a skin in game with /skinshop set <id> <mineskin link | player:Name>.");
        JsonArray arr = new JsonArray();
        for (Skin s : CATALOGUE.values()) {
            JsonObject o = new JsonObject();
            o.addProperty("id", s.id());
            o.addProperty("name", s.name());
            o.addProperty("price", s.price());
            o.addProperty("rarity", s.rarity());
            o.addProperty("source", s.source());
            o.addProperty("value", s.value());
            o.addProperty("signature", s.signature());
            arr.add(o);
        }
        cat.add("skins", arr);
        Stacks.writeFile("skins.json", cat);
        JsonObject own = new JsonObject();
        Set<UUID> all = new HashSet<>(OWNED.keySet());
        all.addAll(WORN.keySet());
        all.addAll(BASE.keySet());
        for (UUID u : all) {
            JsonObject o = new JsonObject();
            JsonArray a = new JsonArray();
            OWNED.getOrDefault(u, Set.of()).forEach(a::add);
            o.add("owned", a);
            if (WORN.containsKey(u)) o.addProperty("worn", WORN.get(u));
            if (BASE.containsKey(u)) o.addProperty("base", BASE.get(u));
            own.add(u.toString(), o);
        }
        Stacks.writeFile("skin-owners.json", own);
    }
}
