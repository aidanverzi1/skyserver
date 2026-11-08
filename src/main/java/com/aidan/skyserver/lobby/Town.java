package com.aidan.skyserver.lobby;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;

import java.time.LocalDate;
import java.util.*;

/**
 * The Town's buildings (layout in TownDesign):
 *  - Locked areas push you back out to the street until your skill is high enough.
 *  - Each skill building has a gold plate in the back: step on it once a day for a crate, sent to
 *    your Sky Island. Better crates at higher levels.
 *  - The Bank and Skin Shop plates open those; the Faction Hall shows the top factions.
 */
public final class Town {
    private static final String ANIMAL_TAG = "skyserver_pasture";
    private static final Map<String, String> CLAIMED = new HashMap<>(); // "area:uuid" -> day
    private static final Map<UUID, Long> LAST_MSG = new HashMap<>();
    private static final Map<UUID, Long> PAD_COOLDOWN = new HashMap<>();
    private static final Random RNG = new Random();
    private static Holograms.Holo factionBoard;
    private static boolean loaded = false;

    private Town() {}

    static void setup() {
        TownDesign.Area f = TownDesign.area("factions");
        ActivityDesign.Area p = f.pad();
        factionBoard = Holograms.add((p.x1() + p.x2() + 1) / 2.0, LobbyDesign.G + 4.2, p.z1() + 4.5, List.of("§9§lTOP FACTIONS", "§7nobody yet"));
        Mine.setup();
    }

    static void tick(ServerLevel lobby, long ticks) {
        Mine.tick(lobby, ticks);
        if (ticks % 200 == 0 && factionBoard != null) {
            List<String> lines = new ArrayList<>(List.of("§9§lTOP FACTIONS", "§7Make one: §e/f create <name>"));
            lines.addAll(Factions.top(5));
            factionBoard.setLines(lines);
        }
        if (ticks % 1200 == 0) pasture(lobby);
    }

    private static void msg(ServerPlayer p, long ticks, String text) {
        Long last = LAST_MSG.get(p.getUUID());
        if (last != null && ticks - last < 60) return;
        LAST_MSG.put(p.getUUID(), ticks);
        Lobby.msg(p, text);
    }

    static void tick(ServerPlayer p, long ticks) {
        Mine.tick(p, ticks);
        double x = p.getX(), y = p.getY(), z = p.getZ();
        if (x < 120 || x > 336 || z < 70 || z > 156) return;
        for (TownDesign.Area a : TownDesign.AREAS) {
            if (!a.inside().contains(x, y, z)) continue;
            if (a.skill() != null) {
                Skills.Skill s = Skills.Skill.valueOf(a.skill());
                int have = Skills.level(p.getUUID(), s);
                if (have < a.level()) {
                    ActivityDesign.Spot d = a.door();
                    p.teleportTo((ServerLevel) p.level(), d.x(), d.y(), d.z(), Set.of(), d.yaw(), 0f, true);
                    msg(p, ticks, a.color() + "The " + a.name() + " §cneeds " + s.title + " level " + a.level() + ". §7(Yours: " + have
                            + ". " + howToLevel(s) + ")");
                    return;
                }
            }
            if (a.pad() != null && a.pad().contains(x, y, z) && p.onGround()) {
                Long cd = PAD_COOLDOWN.get(p.getUUID());
                if (cd != null && cd > ticks) return;
                PAD_COOLDOWN.put(p.getUUID(), ticks + 60);
                pad(p, a);
            }
            return;
        }
    }

    private static String howToLevel(Skills.Skill s) {
        return switch (s) {
            case FARMING -> "Harvest grown crops on your island";
            case SHEPHERD -> "Shear, breed and milk animals on your island";
            case COMBAT -> "Win fights in the games, duels and Spleef";
            case BUILDING -> "Build on your island";
            case MINING -> "Mine in the lobby Mine or on your island";
            case FISHING -> "Fish at the pond or on your island";
            case PARKOUR -> "Run the obstacle courses";
        };
    }

    private static void pad(ServerPlayer p, TownDesign.Area a) {
        switch (a.id()) {
            case "bank" -> Bank.show(p);
            case "skins" -> Lobby.later(p, () -> SkinShop.open(p));
            case "factions" -> Factions.info(p, null);
            default -> Lobby.msg(p, "§7Right-click the §ecrate §7(barrel) for today's " + a.name() + " crate, and talk to the person here for more to do!");
        }
    }

    // =====================================================================
    // Daily crates
    // =====================================================================

    private static void load() {
        if (loaded) return;
        loaded = true;
        var root = Stacks.readFile("town.json");
        String today = LocalDate.now().toString();
        for (var e : root.entrySet()) if (today.equals(e.getValue().getAsString())) CLAIMED.put(e.getKey(), e.getValue().getAsString());
    }

    private static void save() {
        var root = new com.google.gson.JsonObject();
        String today = LocalDate.now().toString();
        CLAIMED.forEach((k, v) -> { if (v.equals(today)) root.addProperty(k, v); });
        Stacks.writeFile("town.json", root);
    }

    private static void crate(ServerPlayer p, TownDesign.Area a) {
        load();
        String key = a.id() + ":" + p.getUUID(), today = LocalDate.now().toString();
        if (today.equals(CLAIMED.get(key))) {
            Lobby.msg(p, "§7You've had today's " + a.name() + " crate. Come back tomorrow!");
            return;
        }
        CLAIMED.put(key, today);
        save();
        Skills.Skill s = Skills.Skill.valueOf(a.skill());
        int lvl = Skills.level(p.getUUID(), s);
        List<ItemStack> items = new ArrayList<>();
        switch (a.id()) {
            case "market" -> {
                items.add(new ItemStack(Items.BONE_MEAL, 16 + lvl));
                items.add(pick(Items.CARROT, Items.POTATO, Items.BEETROOT_SEEDS, Items.SUGAR_CANE));
                items.add(pick(Items.PUMPKIN_SEEDS, Items.MELON_SEEDS, Items.WHEAT_SEEDS));
                if (lvl >= 15) items.add(pick(Items.COCOA_BEANS, Items.SWEET_BERRIES, Items.GLOW_BERRIES, Items.CACTUS));
                if (lvl >= 25) items.add(pick(Items.NETHER_WART, Items.CHORUS_FRUIT, Items.TORCHFLOWER_SEEDS, Items.PITCHER_POD));
                if (lvl >= 40) items.add(new ItemStack(Items.GOLDEN_CARROT, 8));
            }
            case "fields" -> {
                items.add(new ItemStack(pickItem(Items.SHEEP_SPAWN_EGG, Items.COW_SPAWN_EGG, Items.CHICKEN_SPAWN_EGG, Items.PIG_SPAWN_EGG)));
                if (lvl >= 15) items.add(new ItemStack(pickItem(Items.SHEEP_SPAWN_EGG, Items.COW_SPAWN_EGG, Items.RABBIT_SPAWN_EGG, Items.GOAT_SPAWN_EGG)));
                if (lvl >= 30) items.add(new ItemStack(pickItem(Items.HORSE_SPAWN_EGG, Items.MOOSHROOM_SPAWN_EGG, Items.LLAMA_SPAWN_EGG, Items.BEE_SPAWN_EGG)));
                items.add(new ItemStack(Items.WHEAT, 16));
                if (lvl >= 10) items.add(new ItemStack(Items.SHEARS));
                if (lvl >= 20) items.add(new ItemStack(Items.LEAD, 2));
            }
            case "guild" -> {
                items.add(new ItemStack(Items.ARROW, 16 + lvl));
                items.add(new ItemStack(Items.COOKED_BEEF, 8));
                items.add(pick(Items.IRON_SWORD, Items.BOW, Items.SHIELD, Items.CROSSBOW));
                if (lvl >= 15) items.add(new ItemStack(Items.GOLDEN_APPLE, 1 + lvl / 20));
                if (lvl >= 25) items.add(pick(Items.IRON_CHESTPLATE, Items.IRON_LEGGINGS, Items.IRON_HELMET, Items.IRON_BOOTS));
                if (lvl >= 40) items.add(pick(Items.DIAMOND_SWORD, Items.DIAMOND_CHESTPLATE, Items.TOTEM_OF_UNDYING));
            }
            case "gallery" -> {
                Item[] deco = {Items.CHERRY_PLANKS, Items.MUD_BRICKS, Items.PRISMARINE_BRICKS, Items.QUARTZ_BRICKS, Items.CALCITE, Items.DEEPSLATE_TILES,
                        Items.BAMBOO_MOSAIC, Items.POLISHED_BLACKSTONE_BRICKS, Items.TERRACOTTA, Items.SMOOTH_SANDSTONE, Items.DRIPSTONE_BLOCK, Items.TUFF_BRICKS};
                items.add(new ItemStack(deco[RNG.nextInt(deco.length)], 64));
                items.add(new ItemStack(deco[RNG.nextInt(deco.length)], 32 + lvl));
                items.add(new ItemStack(Items.GLASS, 32));
                items.add(new ItemStack(Items.LANTERN, 4));
                if (lvl >= 20) items.add(new ItemStack(Items.SEA_LANTERN, 8));
                if (lvl >= 35) items.add(new ItemStack(pickItem(Items.AMETHYST_BLOCK, Items.SHROOMLIGHT, Items.OCHRE_FROGLIGHT), 16));
            }
            default -> {}
        }
        MinecraftServer server = p.level().getServer();
        for (ItemStack it : items) if (!it.isEmpty()) Mailbox.send(server, p.getUUID(), it);
        long money = 50L * lvl;
        Economy.add(server, p.getUUID(), p.getName().getString(), money);
        p.level().playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.CHEST_OPEN, SoundSource.PLAYERS, 1f, 1f);
        Lobby.msg(p, a.color() + "§l" + a.name() + " crate! §a+" + Economy.format(money) + " §7and " + items.size()
                + " items sent to your Sky Island §e(/claim there)§7. Bigger crates as your " + s.title + " level grows.");
    }

    private static ItemStack pick(Item... items) {
        return new ItemStack(items[RNG.nextInt(items.length)], 4 + RNG.nextInt(5));
    }

    private static Item pickItem(Item... items) {
        return items[RNG.nextInt(items.length)];
    }

    // =====================================================================
    // Animals in the Shepherd's Fields
    // =====================================================================

    private static void pasture(ServerLevel lobby) {
        ActivityDesign.Area f = TownDesign.area("fields").inside();
        if (!lobby.isLoaded(new BlockPos((f.x1() + f.x2()) / 2, LobbyDesign.G, (f.z1() + f.z2()) / 2))) return;
        AABB box = new AABB(f.x1(), f.y1() - 2, f.z1(), f.x2() + 1, f.y2(), f.z2() + 1);
        int have = lobby.getEntitiesOfClass(Mob.class, box, m -> m.entityTags().contains(ANIMAL_TAG)).size();
        List<EntityType<? extends Mob>> kinds = List.of(net.minecraft.world.entity.EntityTypes.SHEEP, net.minecraft.world.entity.EntityTypes.SHEEP, net.minecraft.world.entity.EntityTypes.SHEEP, net.minecraft.world.entity.EntityTypes.COW, net.minecraft.world.entity.EntityTypes.PIG, net.minecraft.world.entity.EntityTypes.CHICKEN);
        for (int i = have; i < 12; i++) {
            Mob m = kinds.get(RNG.nextInt(kinds.size())).create(lobby, EntitySpawnReason.COMMAND);
            if (m == null) continue;
            double x = f.x1() + 2 + RNG.nextInt(f.x2() - f.x1() - 3) + 0.5, z = f.z1() + 2 + RNG.nextInt(f.z2() - f.z1() - 3) + 0.5;
            m.snapTo(x, LobbyDesign.G + 1, z, RNG.nextFloat() * 360, 0);
            m.setPersistenceRequired();
            m.setPermanentlyInvulnerable(true);
            m.addTag(ANIMAL_TAG);
            lobby.addFreshEntity(m);
        }
    }

    /** Lines for the menus. */
    static List<String> areaLines(ServerPlayer p) {
        List<String> out = new ArrayList<>();
        for (TownDesign.Area a : TownDesign.AREAS) {
            if (a.skill() == null) continue;
            Skills.Skill s = Skills.Skill.valueOf(a.skill());
            boolean open = Skills.level(p.getUUID(), s) >= a.level();
            out.add((open ? "§a✔ " : "§c✘ ") + a.color() + a.name() + " §7(" + s.title + " " + a.level() + ")");
        }
        return out;
    }
}
