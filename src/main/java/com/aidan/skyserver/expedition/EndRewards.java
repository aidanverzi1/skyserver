package com.aidan.skyserver.expedition;

import com.aidan.skyserver.lobby.*;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Prediction;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.item.enchantment.ItemEnchantments;

import java.util.*;
import java.util.function.Function;

/**
 * 5.18: everything the End Expedition gives out, and the things you spend it on.
 *
 * Every crew member gets their OWN rolls for every kill, straight into their inventory (full inventory = at their
 * feet). Everything carried is lost on death (unless a Recovery Charm saves it), so going deeper is a gamble.
 *
 *  Wave guards  - Void Fragments (more per guard every 10 waves), chorus flowers, a rare mob head; Void Gusts
 *                 also drop wind charges and breeze rods.
 *  Mini bosses  - (waves 5, 15, 25...) Void Fragments, a shulker shell (50% at wave 5, 75% at 15, 100% from 25),
 *                 an echo shard (25%), a Swift Sneak III or Soul Speed III book (20%).
 *  Bosses       - (waves 10, 20, 30...) Void Fragments, an echo shard (50%). The FIRST Ender Dragon a player beats
 *                 gives the Dragon Egg and Dragon Head (one per player, ever). Every other boss kill gives dragon's
 *                 breath, an Elytra chance (5% at wave 10, 10% at 20, 20% from 30) and a Wind Burst III book chance;
 *                 bosses from wave 20 on also give one random armour trim, guaranteed.
 *  Every 10th wave cleared - $1,000 for each player, paid on the spot.
 *  Wave 30 cleared - the Voidwalker Pearl (a reusable ender pearl, 64 throws), once per player.
 *
 * VOID FRAGMENTS are items while you carry them. They turn into your Void Fragment balance when you get home, when
 * you put them in Wren's Strongbox, or when you open the Void Shop. Spend them in the Void Shop (/voidshop).
 * The RECOVERY CHARM is made in the Void Shop from 5 echo shards: if you die on the End Expedition while carrying
 * one, it breaks and you go home with everything you had.
 */
public final class EndRewards {
    private EndRewards() {}

    public static final String VOID_TOKEN = "void_fragments";
    static final String FRAGMENT_TAG = "void_fragment", CHARM_TAG = "recovery_charm", PEARL_TAG = "voidwalker_pearl";
    static final int PEARL_USES = 64;
    static final int CHARM_ECHO_SHARDS = 5;

    /** Mini boss: shulker shell chance (%) by which mini boss of the trip it is (wave 5, 15, 25 and later). */
    static final int[] SHELL_CHANCE = {50, 75, 100};
    static final int MINI_ECHO_CHANCE = 25, MINI_BOOK_CHANCE = 20;
    static final int BOSS_ECHO_CHANCE = 50, BOSS_WIND_BURST_CHANCE = 15;
    /** Boss: Elytra chance (%) at wave 10, 20, and 30 and later. */
    static final int[] ELYTRA_CHANCE = {5, 10, 20};
    /** Guards: chorus flower and mob head chances (%), Void Gust wind charge / breeze rod chances (%). */
    static final int CHORUS_CHANCE = 5, HEAD_CHANCE = 1, WIND_CHARGE_CHANCE = 30, BREEZE_ROD_CHANCE = 10;

    static final String[] TRIMS = {"bolt", "coast", "dune", "eye", "flow", "host", "raiser", "rib", "sentry",
            "shaper", "silence", "snout", "spire", "tide", "vex", "ward", "wayfinder", "wild"};
    static final Set<String> RARE_TRIMS = Set.of("bolt", "eye", "flow", "silence", "spire", "tide", "vex", "ward");
    static final int TRIM_PRICE = 100, RARE_TRIM_PRICE = 140;

    private static final Random RNG = new Random();

    // =====================================================================
    // Special items
    // =====================================================================

    static Item item(String id) {
        return BuiltInRegistries.ITEM.getValue(Identifier.withDefaultNamespace(id));
    }

    public static ItemStack fragments(int n) {
        ItemStack s = Stacks.tag(new ItemStack(Items.AMETHYST_SHARD, Math.max(1, n)), FRAGMENT_TAG);
        s.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
        return Stacks.named(s, "§5§lVoid Fragment", "§7Spend in the Void Shop (§e/voidshop§7).",
                "§7Becomes your balance when you get home", "§7or put it in Wren's Strongbox.", "§cLost if you die carrying it!");
    }

    public static ItemStack charm() {
        ItemStack s = Stacks.tag(new ItemStack(Items.RECOVERY_COMPASS), CHARM_TAG);
        s.set(DataComponents.MAX_STACK_SIZE, 1);
        return Stacks.named(s, "§b§lRecovery Charm", "§7If you die on the End Expedition", "§7while carrying this, it breaks and",
                "§7you go home with EVERYTHING you had.", "§8(One charm saves one death.)");
    }

    public static ItemStack voidwalkerPearl() {
        ItemStack s = Stacks.tag(new ItemStack(Items.ENDER_PEARL), PEARL_TAG);
        s.set(DataComponents.MAX_STACK_SIZE, 1);
        s.set(DataComponents.MAX_DAMAGE, PEARL_USES);
        s.set(DataComponents.DAMAGE, 0);
        s.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
        return Stacks.named(s, "§d§lVoidwalker Pearl", "§7An ender pearl that comes back.", "§7" + PEARL_USES + " throws before it breaks.",
                "§8Wave 30 prize of the End Expedition");
    }

    static ItemStack book(MinecraftServer server, ResourceKey<Enchantment> key, int lvl) {
        ItemStack s = new ItemStack(Items.ENCHANTED_BOOK);
        try {
            ItemEnchantments.Mutable m = new ItemEnchantments.Mutable(ItemEnchantments.EMPTY);
            Holder<Enchantment> h = server.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(key);
            m.set(h, lvl);
            s.set(DataComponents.STORED_ENCHANTMENTS, m.toImmutable());
        } catch (RuntimeException ignored) {
        }
        return s;
    }

    static ItemStack trim(String name) {
        return new ItemStack(item(name + "_armor_trim_smithing_template"));
    }

    // =====================================================================
    // Giving things out
    // =====================================================================

    /** Into the inventory; whatever doesn't fit drops at their feet. */
    static void give(ServerPlayer p, ItemStack s) {
        if (s.isEmpty()) return;
        String name = s.getHoverName().getString();
        int count = s.getCount();
        if (!p.getInventory().add(s) || !s.isEmpty()) p.drop(s, false, Prediction.SERVER_ONLY);
        if (!s.is(Items.AMETHYST_SHARD)) Announce.actionBar(p, "§a+ " + count + " " + name);
    }

    private static boolean roll(int percent) {
        return RNG.nextInt(100) < percent;
    }

    /** Void Fragments for one guard kill: (tier + 1) / 2 on average (wave 1-10: half a fragment, 11-20: 1, 21-30: 1.5...). */
    static int guardFragments(int tier) {
        int twice = tier + 1;
        return twice / 2 + (twice % 2 == 1 && RNG.nextBoolean() ? 1 : 0);
    }

    /** A wave guard died: every crew member gets their own rolls. */
    static void guard(List<ServerPlayer> crew, EndMobs.Kind kind, int tier) {
        for (ServerPlayer p : crew) {
            int f = guardFragments(tier);
            if (f > 0) give(p, fragments(f));
            if (roll(CHORUS_CHANCE)) give(p, new ItemStack(Items.CHORUS_FLOWER));
            if (roll(HEAD_CHANCE)) give(p, new ItemStack(item(new String[]{"zombie_head", "skeleton_skull", "creeper_head"}[RNG.nextInt(3)])));
            if (kind == EndMobs.Kind.VOID_GUST) {
                if (roll(WIND_CHARGE_CHANCE)) give(p, new ItemStack(Items.WIND_CHARGE, 2));
                if (roll(BREEZE_ROD_CHANCE)) give(p, new ItemStack(Items.BREEZE_ROD));
            }
        }
    }

    /** A mini boss died. which = 0 for the trip's first mini boss (wave 5), 1 for wave 15, 2+ after. */
    static void miniBoss(MinecraftServer server, List<ServerPlayer> crew, int which, int tier) {
        for (ServerPlayer p : crew) {
            give(p, fragments(3 + 2 * tier));
            if (roll(SHELL_CHANCE[Math.min(which, SHELL_CHANCE.length - 1)])) give(p, new ItemStack(Items.SHULKER_SHELL));
            else Lobby.msg(p, "§7No shulker shell this time.");
            if (roll(MINI_ECHO_CHANCE)) give(p, new ItemStack(Items.ECHO_SHARD));
            if (roll(MINI_BOOK_CHANCE)) give(p, RNG.nextBoolean() ? book(server, Enchantments.SWIFT_SNEAK, 3) : book(server, Enchantments.SOUL_SPEED, 3));
        }
    }

    /** A boss died at this wave. dragon = it was the Ender Dragon. */
    static void boss(MinecraftServer server, List<ServerPlayer> crew, int wave, int tier, boolean dragon) {
        for (ServerPlayer p : crew) {
            give(p, fragments(5 + 3 * tier));
            if (roll(BOSS_ECHO_CHANCE)) give(p, new ItemStack(Items.ECHO_SHARD));
            if (dragon && !Progress.egg(p.getUUID())) {
                Progress.setEgg(p.getUUID());
                give(p, new ItemStack(item("dragon_egg")));
                give(p, new ItemStack(item("dragon_head")));
                Announce.title(p, "§5§lTHE DRAGON EGG", "§7Your first Ender Dragon - the egg and head are yours", 10, 70, 20);
                for (ServerPlayer all : server.getPlayerList().getPlayers())
                    Lobby.msg(all, "§5§l" + p.getName().getString() + " §dbeat the Ender Dragon for the first time and claimed a Dragon Egg!");
                continue;
            }
            give(p, new ItemStack(Items.DRAGON_BREATH, 2 + RNG.nextInt(3)));
            int ec = ELYTRA_CHANCE[Math.min(ELYTRA_CHANCE.length - 1, Math.max(0, wave / 10 - 1))];
            if (roll(ec)) {
                give(p, new ItemStack(Items.ELYTRA));
                for (ServerPlayer all : server.getPlayerList().getPlayers())
                    Lobby.msg(all, "§d" + p.getName().getString() + " found an §lElytra §don the End Expedition (wave " + wave + ")!");
            }
            if (roll(BOSS_WIND_BURST_CHANCE)) give(p, book(server, Enchantments.WIND_BURST, 3));
            if (wave >= 20) give(p, trim(TRIMS[RNG.nextInt(TRIMS.length)]));
        }
    }

    /** Every 10th wave: money on the spot; wave 30: the Voidwalker Pearl (once per player). */
    static void milestone(MinecraftServer server, List<ServerPlayer> crew, int wave) {
        for (ServerPlayer p : crew) {
            Economy.add(server, p.getUUID(), p.getName().getString(), EndExpedition.MONEY_PER_TEN);
            Lobby.msg(p, "§6+" + Economy.format(EndExpedition.MONEY_PER_TEN) + " §7for reaching wave " + wave + "! §8(already in your account)");
            if (wave >= 30 && !Progress.pearl(p.getUUID())) {
                Progress.setPearl(p.getUUID());
                give(p, voidwalkerPearl());
                Announce.title(p, "§d§lVOIDWALKER PEARL", "§7A pearl that comes back - 64 throws", 10, 70, 20);
            }
        }
    }

    // =====================================================================
    // Void Fragments -> balance
    // =====================================================================

    /** Turns every Void Fragment item a player carries into their balance. Returns how many. */
    public static int bank(ServerPlayer p) {
        Inventory inv = p.getInventory();
        int n = 0;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (Stacks.is(s, FRAGMENT_TAG)) {
                n += s.getCount();
                inv.setItem(i, ItemStack.EMPTY);
            }
        }
        if (n > 0) {
            Tokens.add(p.getUUID(), VOID_TOKEN, n);
            Lobby.msg(p, "§5+" + n + " Void Fragments §7banked (you have §5" + balance(p) + "§7). Spend them: §e/voidshop");
        }
        return n;
    }

    public static int balance(ServerPlayer p) {
        return Tokens.count(p.getUUID(), VOID_TOKEN);
    }

    /** Uses up one Recovery Charm from the inventory. True if there was one. */
    static boolean useCharm(ServerPlayer p) {
        Inventory inv = p.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (Stacks.is(s, CHARM_TAG)) {
                s.shrink(1);
                return true;
            }
        }
        return false;
    }

    // =====================================================================
    // The Voidwalker Pearl: comes back after every throw
    // =====================================================================

    /** Called when a player throws an ender pearl. If it's the Voidwalker Pearl, it comes back a tick later, a little worn. */
    static void pearlThrown(ServerPlayer p, net.minecraft.world.InteractionHand hand, ItemStack held) {
        if (!Stacks.is(held, PEARL_TAG)) return;
        ItemStack back = held.copy();
        back.setCount(1);
        int used = back.getDamageValue() + 1;
        Lobby.runLaterTicks(1, () -> com.aidan.skyserver.Safe.run("voidwalker pearl", () -> {
            if (p.hasDisconnected()) return;
            if (Stacks.is(p.getItemInHand(hand), PEARL_TAG)) return;
            if (used >= PEARL_USES) {
                p.level().playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.GLASS_BREAK, SoundSource.PLAYERS, 1f, 0.8f);
                Lobby.msg(p, "§dYour Voidwalker Pearl has crumbled after " + PEARL_USES + " throws.");
                return;
            }
            ItemStack now = p.getItemInHand(hand);
            if (Stacks.is(now, PEARL_TAG)) return; // the throw didn't happen (cooldown): it's still in their hand
            back.setDamageValue(used);
            if (now.isEmpty()) p.setItemInHand(hand, back);
            else give(p, back);
        }));
    }

    // =====================================================================
    // Wren's Strongbox: send things home (you can't take them back out until you're home)
    // =====================================================================

    private static final Map<UUID, List<JsonElement>> STRONGBOX = new HashMap<>();
    private static boolean strongboxLoaded = false;

    private static void loadStrongbox() {
        if (strongboxLoaded) return;
        strongboxLoaded = true;
        JsonObject root = Stacks.readFile("end-strongbox.json");
        for (String k : root.keySet()) {
            try {
                List<JsonElement> list = new ArrayList<>();
                root.getAsJsonArray(k).forEach(list::add);
                STRONGBOX.put(UUID.fromString(k), list);
            } catch (RuntimeException ignored) {
            }
        }
    }

    private static void saveStrongbox() {
        JsonObject root = new JsonObject();
        STRONGBOX.forEach((id, list) -> {
            if (list.isEmpty()) return;
            JsonArray a = new JsonArray();
            list.forEach(a::add);
            root.add(id.toString(), a);
        });
        PlayerCache.io(() -> Stacks.writeFile("end-strongbox.json", root));
    }

    /** Opens the Strongbox: whatever you put in goes home when you close it (fragments go straight to your balance). */
    static void openStrongbox(ServerPlayer p) {
        SimpleContainer box = new SimpleContainer(27);
        p.openMenu(new SimpleMenuProvider((id, inv, pl) -> new ChestMenu(MenuType.GENERIC_9x3, id, inv, box, 3) {
            @Override
            public void removed(Player player) {
                super.removed(player);
                if (player instanceof ServerPlayer sp) com.aidan.skyserver.Safe.run("end strongbox", () -> stow(sp, box));
            }
        }, Component.literal("§5Wren's Strongbox §8(goes home)")));
        Lobby.msg(p, "§6[Captain Wren] §fPut in whatever you want sent home. §7Once it's in, it stays locked up until you're home.");
    }

    private static void stow(ServerPlayer p, SimpleContainer box) {
        loadStrongbox();
        MinecraftServer server = p.level().getServer();
        int frags = 0, items = 0;
        List<JsonElement> list = STRONGBOX.computeIfAbsent(p.getUUID(), k -> new ArrayList<>());
        for (int i = 0; i < box.getContainerSize(); i++) {
            ItemStack s = box.getItem(i);
            if (s.isEmpty()) continue;
            if (Stacks.is(s, FRAGMENT_TAG)) frags += s.getCount();
            else {
                list.add(Stacks.toJson(server, s.copy()));
                items += s.getCount();
            }
            box.setItem(i, ItemStack.EMPTY);
        }
        if (frags > 0) Tokens.add(p.getUUID(), VOID_TOKEN, frags);
        if (items > 0) saveStrongbox();
        if (frags > 0 || items > 0)
            Lobby.msg(p, "§6[Captain Wren] §fLocked away" + (items > 0 ? " §e" + items + " item(s)§f" : "") + (frags > 0 ? (items > 0 ? " and" : "") + " §5" + frags + " Void Fragments §8(banked now)" : "")
                    + "§f. §7Your items wait in your mail (§e/claim§7) once you're home.");
    }

    /** Sends a player's locked-up Strongbox items to their mail. Call once they're off the End island. */
    static void deliverStrongbox(MinecraftServer server, UUID id) {
        loadStrongbox();
        List<JsonElement> list = STRONGBOX.remove(id);
        if (list == null || list.isEmpty()) return;
        for (JsonElement e : list) Mailbox.send(server, id, Stacks.fromJson(server, e));
        saveStrongbox();
    }

    /** At startup no End trip is running: everything still locked up goes to its owner's mail. */
    static void deliverAll(MinecraftServer server) {
        loadStrongbox();
        for (UUID id : new ArrayList<>(STRONGBOX.keySet())) deliverStrongbox(server, id);
    }

    // =====================================================================
    // Progress (dragon egg / pearl once per player, best wave) - config/skyserver/end-progress.json
    // =====================================================================

    static final class Progress {
        private static JsonObject root;

        private static JsonObject of(UUID id) {
            if (root == null) root = Stacks.readFile("end-progress.json");
            String k = id.toString();
            if (!root.has(k)) root.add(k, new JsonObject());
            return root.getAsJsonObject(k);
        }

        private static void save() {
            JsonObject copy = root.deepCopy();
            PlayerCache.io(() -> Stacks.writeFile("end-progress.json", copy));
        }

        static boolean egg(UUID id) {
            return of(id).has("egg");
        }

        static void setEgg(UUID id) {
            of(id).addProperty("egg", true);
            save();
        }

        static boolean pearl(UUID id) {
            return of(id).has("pearl");
        }

        static void setPearl(UUID id) {
            of(id).addProperty("pearl", true);
            save();
        }

        static int best(UUID id) {
            JsonObject o = of(id);
            return o.has("best") ? o.get("best").getAsInt() : 0;
        }

        static void best(UUID id, int wave) {
            if (wave <= best(id)) return;
            of(id).addProperty("best", wave);
            save();
        }
    }

    // =====================================================================
    // The Void Shop (/voidshop)
    // =====================================================================

    private record Offer(int slot, String name, int price, int echo, Function<MinecraftServer, ItemStack> make, Item icon) {}

    private static List<Offer> offers() {
        List<Offer> o = new ArrayList<>();
        for (int i = 0; i < TRIMS.length; i++) {
            String t = TRIMS[i];
            String nice = Character.toUpperCase(t.charAt(0)) + t.substring(1);
            int price = RARE_TRIMS.contains(t) ? RARE_TRIM_PRICE : TRIM_PRICE;
            o.add(new Offer(9 + i, "§f" + nice + " Armor Trim", price, 0, s -> trim(t), item(t + "_armor_trim_smithing_template")));
        }
        o.add(new Offer(28, "§dElytra", 150, 0, s -> new ItemStack(Items.ELYTRA), Items.ELYTRA));
        o.add(new Offer(30, "§bRecovery Charm", 0, CHARM_ECHO_SHARDS, s -> charm(), Items.RECOVERY_COMPASS));
        o.add(new Offer(32, "§54 Dragon's Breath", 15, 0, s -> new ItemStack(Items.DRAGON_BREATH, 4), Items.DRAGON_BREATH));
        o.add(new Offer(34, "§54 Chorus Flowers", 20, 0, s -> new ItemStack(Items.CHORUS_FLOWER, 4), Items.CHORUS_FLOWER));
        o.add(new Offer(37, "§f16 Wind Charges", 15, 0, s -> new ItemStack(Items.WIND_CHARGE, 16), Items.WIND_CHARGE));
        o.add(new Offer(39, "§bSwift Sneak III Book", 60, 0, s -> book(s, Enchantments.SWIFT_SNEAK, 3), Items.ENCHANTED_BOOK));
        o.add(new Offer(41, "§bSoul Speed III Book", 60, 0, s -> book(s, Enchantments.SOUL_SPEED, 3), Items.ENCHANTED_BOOK));
        o.add(new Offer(43, "§bWind Burst III Book", 90, 0, s -> book(s, Enchantments.WIND_BURST, 3), Items.ENCHANTED_BOOK));
        return o;
    }

    /** Where the Void Shop opens: the islands, the lobby, or the End island between waves. */
    public static void openShop(ServerPlayer p) {
        boolean end = EndExpedition.isEnd(p.level());
        if (end && EndExpedition.fighting()) {
            Lobby.msg(p, "§cNot in the middle of a wave! §7Open the Void Shop between waves.");
            return;
        }
        if (!end && !com.aidan.skyserver.island.Islands.isIsland(p.level()) && !Lobby.isLobby(p.level())) {
            Lobby.msg(p, "§cThe Void Shop opens on your island, in the lobby, or between End waves.");
            return;
        }
        bank(p);
        LockedMenu.open(p, "§5Void Shop", 6, ShopMenu::new);
    }

    private static int echoShards(ServerPlayer p) {
        Inventory inv = p.getInventory();
        int n = 0;
        for (int i = 0; i < inv.getContainerSize(); i++) if (inv.getItem(i).is(Items.ECHO_SHARD)) n += inv.getItem(i).getCount();
        return n;
    }

    private static void takeEchoShards(ServerPlayer p, int n) {
        Inventory inv = p.getInventory();
        for (int i = 0; i < inv.getContainerSize() && n > 0; i++) {
            ItemStack s = inv.getItem(i);
            if (!s.is(Items.ECHO_SHARD)) continue;
            int t = Math.min(n, s.getCount());
            s.shrink(t);
            n -= t;
        }
    }

    private static final class ShopMenu extends LockedMenu {
        private final List<Offer> offers = offers();

        ShopMenu(int id, Inventory inv, SimpleContainer box, ServerPlayer viewer) {
            super(id, inv, box, 6, viewer);
            draw();
        }

        private void draw() {
            for (int i = 0; i < box.getContainerSize(); i++) box.setItem(i, ItemStack.EMPTY);
            int bal = balance(viewer);
            box.setItem(4, Stacks.named(fragments(1), "§5§lVoid Fragments: " + bal,
                    "§7Earned from End Expedition guards and bosses.", "§7Carried fragments are banked when you get home,",
                    "§7put them in Wren's Strongbox, or open this shop."));
            for (Offer o : offers) {
                ItemStack icon = new ItemStack(o.icon());
                String cost = o.echo() > 0 ? "§7Cost: §3" + o.echo() + " echo shards §8(from your inventory; you have " + echoShards(viewer) + ")"
                        : "§7Cost: §5" + o.price() + " Void Fragments";
                boolean can = o.echo() > 0 ? echoShards(viewer) >= o.echo() : bal >= o.price();
                box.setItem(o.slot(), Stacks.named(icon, o.name(), cost, can ? "§eClick to buy" : "§8Not enough yet"));
            }
            frame(Stacks.named(new ItemStack(item("purple_stained_glass_pane")), " "));
        }

        @Override
        protected void onClick(int slot, int button, boolean shift) {
            for (Offer o : offers) {
                if (o.slot() != slot) continue;
                MinecraftServer server = viewer.level().getServer();
                if (o.echo() > 0) {
                    if (echoShards(viewer) < o.echo()) {
                        Lobby.msg(viewer, "§cYou need " + o.echo() + " echo shards in your inventory (from End mini bosses and bosses).");
                        return;
                    }
                    takeEchoShards(viewer, o.echo());
                } else if (!Tokens.take(viewer.getUUID(), VOID_TOKEN, o.price())) {
                    Lobby.msg(viewer, "§cThat costs " + o.price() + " Void Fragments - you have " + balance(viewer) + ".");
                    return;
                }
                give(viewer, o.make().apply(server));
                viewer.level().playSound(null, viewer.getX(), viewer.getY(), viewer.getZ(), SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.PLAYERS, 1f, 1.2f);
                Lobby.msg(viewer, "§aBought " + o.name() + "§a!");
                draw();
                return;
            }
        }
    }
}
