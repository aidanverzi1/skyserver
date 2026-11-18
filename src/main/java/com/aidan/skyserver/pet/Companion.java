package com.aidan.skyserver.pet;

import com.aidan.skyserver.Safe;
import com.aidan.skyserver.Skyserver;
import com.aidan.skyserver.expedition.AncientCity;
import com.aidan.skyserver.expedition.EndExpedition;
import com.aidan.skyserver.expedition.Expedition;
import com.aidan.skyserver.expedition.SunkenMonument;
import com.aidan.skyserver.island.Islands;
import com.aidan.skyserver.lobby.Duels;
import com.aidan.skyserver.lobby.Lobby;
import com.aidan.skyserver.lobby.LockedMenu;
import com.aidan.skyserver.lobby.PlayerCache;
import com.aidan.skyserver.lobby.Stacks;
import com.google.gson.JsonObject;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 5.24: every player's chicken companion ("pocket companion").
 *
 * One chicken per player, kept as player data (config/skyserver/pets.json), never as a saved entity: a real
 * chicken is spawned next to you only where it's allowed, and removed again when you leave, so it can't be lost
 * or duplicated. Every new island comes with one; /ss givechicken gives existing players theirs.
 *
 *  - Fights with you on the Nether, End and Sunken Monument expeditions. Stays home in the Ancient City.
 *    Follows you (no fighting) in the lobby and on islands. Never in minigames or duels.
 *  - 680 health (a pool of its own, the same on every difficulty; /ss chicken hp to tune). Pecks 1 heart every
 *    1.5 s. Distracts ONE monster at a time (the one nearest you) and only that monster can hurt it - nothing
 *    else does (explosions, fire, other mobs, falling). It can peck bosses but never distracts them.
 *  - At 0 health it's DOWNED: stops fighting, follows slowly, smokes. A Little Treat gets it up at 75%;
 *    15 minutes online on your own island (adds up, doesn't have to be in one go) gets it up at full health.
 *  - Lays one Nest Egg a day after NEST_SECONDS on your island. Armour (PetItems) cuts the damage it takes.
 *  - /pet: call / send home, rename, health, armour, Little Snack Plant shop. /ss henparty (op): the chickens
 *    near you merge into one giant chicken for 20 s that hits everything within 3 blocks.
 */
public final class Companion {
    private Companion() {}

    public static final String TAG = "skyserver_pet", GIANT_TAG = "skyserver_pet_giant";
    /** Health pool (no armour). /ss chicken hp <n> changes it (saved). */
    static double maxHp = 680;
    static final float PECK_DAMAGE = 2f;
    static final int PECK_TICKS = 30;
    static final double TREAT_REVIVE = 0.75, SNACK_HEAL = 0.10;
    static final int REVIVE_ISLAND_SECONDS = 15 * 60;
    /** Island time each day before the chicken lays that day's Nest Egg (10 minutes). */
    static final int NEST_SECONDS = 10 * 60;
    static final int FEED_COOLDOWN_TICKS = 5; // 0.25 s
    static final double DISTRACT_RANGE = 10, PECK_RANGE = 2.2;
    static final int HEN_PARTY_TICKS = 20 * 20, HEN_HIT_TICKS = 30, HEN_STUN_TICKS = 10;
    static final float HEN_DAMAGE = 3f;

    enum Zone { FIGHT, FOLLOW, HOME }

    static final class Pet {
        UUID owner;
        String ownerName = "";
        String name = "Chicken";
        double hp;
        boolean downed;
        int downSec;
        String armor = "";
        int armorDur;
        int nestSec;
        String nestDay = "";
        String treatQuestDay = "", treatClearDay = "";
        int treatsKnown;
        int plate20Week = -999, plate40Week = -999;
        boolean wrenPlanter; // 5.24b: got Wren's first Little Snack Planter
        boolean away;
        // not saved
        Mob entity;
        UUID target;
        int peckCd;
        long feedReady;
        long henUntil;
    }

    static final Map<UUID, Pet> PETS = new HashMap<>();
    /** chicken entity id -> owner id */
    private static final Map<UUID, UUID> OWNER_OF = new HashMap<>();
    private static boolean dirty = false;
    private static long ticks = 0;
    private static MinecraftServer server;

    // hen party
    private static Mob giant;
    private static ServerPlayer giantLeader;
    private static long giantEnds, giantNextHit;

    static void dirty() {
        dirty = true;
    }

    public static boolean isPet(Entity e) {
        return e != null && e.entityTags().contains(TAG);
    }

    // =====================================================================
    // Saving
    // =====================================================================

    private static void load() {
        PETS.clear();
        JsonObject root = Stacks.readFile("pets.json");
        if (root.has("maxHp")) maxHp = root.get("maxHp").getAsDouble();
        if (root.has("pets")) for (Map.Entry<String, com.google.gson.JsonElement> e : root.getAsJsonObject("pets").entrySet()) {
            JsonObject o = e.getValue().getAsJsonObject();
            Pet p = new Pet();
            p.owner = UUID.fromString(e.getKey());
            p.ownerName = str(o, "ownerName", "");
            p.name = str(o, "name", "Chicken");
            p.hp = o.has("hp") ? o.get("hp").getAsDouble() : maxHp;
            p.downed = o.has("downed") && o.get("downed").getAsBoolean();
            p.downSec = num(o, "downSec");
            p.armor = str(o, "armor", "");
            p.armorDur = num(o, "armorDur");
            p.nestSec = num(o, "nestSec");
            p.nestDay = str(o, "nestDay", "");
            p.treatQuestDay = str(o, "treatQuestDay", "");
            p.treatClearDay = str(o, "treatClearDay", "");
            p.treatsKnown = num(o, "treatsKnown");
            p.wrenPlanter = o.has("wrenPlanter") && o.get("wrenPlanter").getAsBoolean();
            p.plate20Week = o.has("plate20Week") ? o.get("plate20Week").getAsInt() : -999;
            p.plate40Week = o.has("plate40Week") ? o.get("plate40Week").getAsInt() : -999;
            p.away = o.has("away") && o.get("away").getAsBoolean();
            PETS.put(p.owner, p);
        }
        Planters.load(root.has("planters") ? root.getAsJsonObject("planters") : null);
        Skyserver.LOGGER.info("[Chicken] {} chickens, {} snack planters loaded", PETS.size(), Planters.PLANTERS.size());
    }

    private static String str(JsonObject o, String k, String def) {
        return o.has(k) ? o.get(k).getAsString() : def;
    }

    private static int num(JsonObject o, String k) {
        return o.has(k) ? o.get(k).getAsInt() : 0;
    }

    private static JsonObject snapshot() {
        JsonObject root = new JsonObject();
        root.addProperty("maxHp", maxHp);
        JsonObject pets = new JsonObject();
        for (Pet p : PETS.values()) {
            JsonObject o = new JsonObject();
            o.addProperty("ownerName", p.ownerName);
            o.addProperty("name", p.name);
            o.addProperty("hp", Math.round(p.hp * 10) / 10.0);
            o.addProperty("downed", p.downed);
            o.addProperty("downSec", p.downSec);
            o.addProperty("armor", p.armor);
            o.addProperty("armorDur", p.armorDur);
            o.addProperty("nestSec", p.nestSec);
            o.addProperty("nestDay", p.nestDay);
            o.addProperty("treatQuestDay", p.treatQuestDay);
            o.addProperty("treatClearDay", p.treatClearDay);
            o.addProperty("treatsKnown", p.treatsKnown);
            o.addProperty("wrenPlanter", p.wrenPlanter);
            o.addProperty("plate20Week", p.plate20Week);
            o.addProperty("plate40Week", p.plate40Week);
            o.addProperty("away", p.away);
            pets.add(p.owner.toString(), o);
        }
        root.add("pets", pets);
        root.add("planters", Planters.save());
        return root;
    }

    private static void saveNow(boolean background) {
        dirty = false;
        JsonObject snap = snapshot();
        if (background) PlayerCache.io(() -> Stacks.writeFile("pets.json", snap));
        else Stacks.writeFile("pets.json", snap);
    }

    // =====================================================================
    // Giving chickens
    // =====================================================================

    /** Gives this player their chicken (once ever). Returns false if they already have one. */
    public static boolean grant(UUID id, String name, boolean newIsland) {
        if (PETS.containsKey(id) || com.aidan.skyserver.bots.BotManager.isBot(id)) return false;
        Pet p = new Pet();
        p.owner = id;
        p.ownerName = name == null ? "" : name;
        p.hp = maxHp;
        PETS.put(id, p);
        dirty();
        ServerPlayer sp = server == null ? null : server.getPlayerList().getPlayer(id);
        if (sp != null) {
            Lobby.msg(sp, "§e§lYOU GOT A CHICKEN! §7It follows you around and fights with you on the Nether, End and Sunken expeditions. §e/pet §7for its menu.");
            sp.level().playSound(null, sp.getX(), sp.getY(), sp.getZ(), SoundEvents.CHICKEN_EGG, SoundSource.PLAYERS, 1f, 1f);
        }
        return true;
    }

    // =====================================================================
    // Where it can be
    // =====================================================================

    static Zone zone(ServerPlayer p) {
        if (p.isSpectator() || !p.isAlive()) return Zone.HOME;
        Level l = p.level();
        if (Expedition.isExpedition(l) || EndExpedition.isEnd(l) || SunkenMonument.isSunken(l)) return Zone.FIGHT;
        if (AncientCity.isCity(l)) return Zone.HOME; // stays home in the Ancient City (for now)
        if (Islands.isIsland(l)) return Zone.FOLLOW;
        if (Lobby.isLobby(l) && !Duels.inDuel(p.getUUID())) return Zone.FOLLOW;
        return Zone.HOME; // minigames, duels, anywhere else
    }

    /** Standing on your own island (owner or member). */
    static boolean onOwnIsland(ServerPlayer p) {
        if (!Islands.isIsland(p.level())) return false;
        Islands.Island mine = Islands.islandOf(p.getUUID());
        return mine != null && Islands.islandAt(p.getX(), p.getZ()) == mine;
    }

    private static void despawn(Pet pet) {
        if (pet.entity != null) {
            releaseTarget(pet);
            OWNER_OF.remove(pet.entity.getUUID());
            if (!pet.entity.isRemoved()) pet.entity.discard();
        }
        pet.entity = null;
    }

    private static Mob spawn(Pet pet, ServerPlayer p) {
        despawn(pet);
        ServerLevel level = (ServerLevel) p.level();
        Mob m = EntityTypes.CHICKEN.create(level, EntitySpawnReason.EVENT);
        if (m == null) return null;
        double a = Math.toRadians(p.getYRot() + 140);
        m.snapTo(p.getX() + Math.sin(a) * 1.2, p.getY(), p.getZ() - Math.cos(a) * 1.2, p.getYRot(), 0f);
        m.setPersistenceRequired();
        m.addTag(TAG);
        AttributeInstance speed = m.getAttribute(Attributes.MOVEMENT_SPEED);
        if (speed != null) speed.setBaseValue(0.42); // 5.24b: faster, keeps up with sprinting
        m.setCustomNameVisible(true);
        pet.entity = m;
        OWNER_OF.put(m.getUUID(), pet.owner);
        nameTag(pet);
        level.addFreshEntity(m);
        return m;
    }

    private static void nameTag(Pet pet) {
        if (pet.entity == null) return;
        int pct = (int) Math.ceil(100.0 * Math.max(0, pet.hp) / maxHp);
        String color = pct > 60 ? "§a" : pct > 30 ? "§e" : "§c";
        String shield = pet.armor.isEmpty() ? "" : " §b⛨";
        String text = pet.downed ? "§7" + pet.name + " §8(downed)" : "§f" + pet.name + " §c❤ " + color + pct + "%" + shield;
        Component now = pet.entity.getCustomName();
        if (now == null || !now.getString().equals(text)) pet.entity.setCustomName(Component.literal(text));
    }

    // =====================================================================
    // Every tick
    // =====================================================================

    private static void tick(MinecraftServer s) {
        ticks++;
        if (giant != null) henTick();
        if (ticks % 5 == 0) {
            for (ServerPlayer p : s.getPlayerList().getPlayers()) {
                Pet pet = PETS.get(p.getUUID());
                if (pet != null && pet.entity != null && !pet.entity.isRemoved()) Safe.run("chicken move", () -> move(pet, p));
            }
        }
        if (ticks % 20 == 0) {
            for (ServerPlayer p : s.getPlayerList().getPlayers()) {
                Pet pet = PETS.get(p.getUUID());
                if (pet != null) Safe.run("chicken second", () -> second(pet, p));
            }
            Safe.run("snack planters", () -> Planters.secondTick(s));
        }
        if (ticks % 600 == 0 && dirty) saveNow(true);
    }

    /** Once a second: spawn / remove, island time, revive, nest egg, name tag. */
    private static void second(Pet pet, ServerPlayer p) {
        pet.ownerName = p.getName().getString();
        Zone z = pet.away || pet.henUntil > ticks ? Zone.HOME : zone(p);
        if (z == Zone.HOME) despawn(pet);
        else {
            Mob m = pet.entity;
            if (m == null || m.isRemoved() || !m.isAlive() || m.level() != p.level()) spawn(pet, p);
            else if (m.distanceToSqr(p) > 12 * 12) { // 5.24b: 20 -> 12 blocks (elytra, pearls)
                releaseTarget(pet);
                m.snapTo(p.getX(), p.getY(), p.getZ(), p.getYRot(), 0f);
                m.getNavigation().stop();
            }
            if (pet.entity != null) {
                pet.entity.setRemainingFireTicks(0);
                if (pet.downed) ((ServerLevel) p.level()).sendParticles(ParticleTypes.SMOKE, pet.entity.getX(), pet.entity.getY() + 0.6, pet.entity.getZ(), 4, 0.15, 0.15, 0.15, 0.01);
                if (z != Zone.FIGHT) releaseTarget(pet);
            }
        }
        // Treats carried (for the 8-treat limit when rewards come while you're in the lobby).
        if (Lobby.isIsland(p.level())) {
            int t = PetItems.countTagged(p, PetItems.TREAT);
            if (t != pet.treatsKnown) {
                pet.treatsKnown = t;
                dirty();
            }
        }
        if (onOwnIsland(p)) {
            if (pet.downed && ++pet.downSec >= REVIVE_ISLAND_SECONDS) {
                revive(pet, 1.0);
                Lobby.msg(p, "§a" + pet.name + " is back on its feet §7(15 minutes on your island) §a- full health!");
            }
            if (pet.downed && pet.downSec % 60 == 0) dirty();
            String day = PetItems.today();
            if (!day.equals(pet.nestDay) && !pet.away) {
                if (++pet.nestSec >= NEST_SECONDS) {
                    pet.nestSec = 0;
                    pet.nestDay = day;
                    PetItems.give(p, PetItems.nestEgg());
                    p.level().playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.CHICKEN_EGG, SoundSource.PLAYERS, 1f, 1f);
                    Lobby.msg(p, "§e" + pet.name + " laid today's §lNest Egg§e! §7Right-click it to crack it open.");
                    dirty();
                } else if (pet.nestSec % 60 == 0) dirty();
            }
        }
        nameTag(pet);
    }

    /** Every 5 ticks: follow, pick a monster to distract, peck. */
    private static void move(Pet pet, ServerPlayer p) {
        Mob m = pet.entity;
        ServerLevel level = (ServerLevel) m.level();
        if (pet.peckCd > 0) pet.peckCd -= 5;
        boolean fight = !pet.downed && zone(p) == Zone.FIGHT;
        LivingEntity chase = null;
        if (fight) {
            Mob d = distracted(pet, level);
            if (d == null || d.distanceToSqr(m) > 20 * 20) {
                releaseTarget(pet);
                d = pickTarget(p, level);
                pet.target = d == null ? null : d.getUUID();
            }
            if (d != null) {
                if (d.getTarget() != m) d.setTarget(m);
                chase = d;
            } else {
                // Nothing to distract: help with whatever's close to you (bosses too).
                chase = nearestEnemy(level, p, 6, true);
            }
        }
        if (chase != null) {
            if (m.distanceToSqr(chase) > PECK_RANGE * PECK_RANGE) m.getNavigation().moveTo(chase, 1.4);
            else m.getNavigation().stop();
            LivingEntity hit = m.distanceToSqr(chase) <= PECK_RANGE * PECK_RANGE ? chase : nearestEnemy(level, m, PECK_RANGE, true);
            if (hit != null && pet.peckCd <= 0) {
                pet.peckCd = PECK_TICKS;
                hit.hurtServer(level, m.damageSources().mobAttack(m), PECK_DAMAGE);
                level.sendParticles(ParticleTypes.CRIT, hit.getX(), hit.getY() + hit.getBbHeight() * 0.6, hit.getZ(), 5, 0.2, 0.2, 0.2, 0.1);
            }
            return;
        }
        double d2 = m.distanceToSqr(p);
        if (d2 > 3.5 * 3.5) m.getNavigation().moveTo(p, pet.downed ? 0.7 : d2 > 7 * 7 ? 2.3 : d2 > 5 * 5 ? 1.9 : 1.5); // 5.24b: runs faster the further behind it is
        else if (d2 < 2 * 2) m.getNavigation().stop();
    }

    private static Mob distracted(Pet pet, ServerLevel level) {
        if (pet.target == null) return null;
        Entity e = level.getEntity(pet.target);
        if (e instanceof Mob mob && mob.isAlive() && !mob.isRemoved()) return mob;
        pet.target = null;
        return null;
    }

    private static void releaseTarget(Pet pet) {
        if (pet.target == null || pet.entity == null) {
            pet.target = null;
            return;
        }
        Entity e = ((ServerLevel) pet.entity.level()).getEntity(pet.target);
        if (e instanceof Mob mob && mob.getTarget() == pet.entity) mob.setTarget(null);
        pet.target = null;
    }

    /** The monster nearest the player that can be distracted (not a boss, not ours, not already on another chicken). */
    private static Mob pickTarget(ServerPlayer p, ServerLevel level) {
        Mob best = null;
        double bestD = Double.MAX_VALUE;
        for (Mob mob : level.getEntitiesOfClass(Mob.class, p.getBoundingBox().inflate(DISTRACT_RANGE), x -> fightable(x) && !isBoss(x))) {
            if (mob.getTarget() != null && isPet(mob.getTarget())) continue; // another chicken has it
            double d = mob.distanceToSqr(p);
            if (d < bestD) {
                bestD = d;
                best = mob;
            }
        }
        return best;
    }

    private static LivingEntity nearestEnemy(ServerLevel level, Entity from, double range, boolean bosses) {
        Mob best = null;
        double bestD = range * range;
        for (Mob mob : level.getEntitiesOfClass(Mob.class, from.getBoundingBox().inflate(range), x -> fightable(x) && (bosses || !isBoss(x)))) {
            double d = mob.distanceToSqr(from);
            if (d <= bestD) {
                bestD = d;
                best = mob;
            }
        }
        return best;
    }

    private static boolean fightable(Mob m) {
        if (!(m instanceof Enemy) || !m.isAlive() || isPet(m)) return false;
        return m.entityTags().contains("skyserver_bosspart") || !m.isNoAi() && !m.isInvisible();
    }

    /** Bosses can be pecked, never distracted. */
    static boolean isBoss(Mob m) {
        return m.getMaxHealth() >= 150 || m.getType() == EntityTypes.ENDER_DRAGON || m.getType() == EntityTypes.WITHER
                || m.getType() == EntityTypes.WARDEN || m.getType() == EntityTypes.ELDER_GUARDIAN || m.entityTags().contains("skyserver_bosspart");
    }

    // =====================================================================
    // Damage, downed, revive
    // =====================================================================

    /** Called for every hit on a chicken of ours. The real chicken never takes damage; its own pool does. */
    private static void hurt(Entity chicken, Entity attacker, float amount) {
        UUID owner = OWNER_OF.get(chicken.getUUID());
        Pet pet = owner == null ? null : PETS.get(owner);
        if (pet == null || pet.downed || pet.entity != chicken || attacker == null || pet.target == null) return;
        if (!attacker.getUUID().equals(pet.target)) return; // only the monster it's distracting
        if (amount <= 0) return;
        double red = PetItems.reduction(pet.armor);
        pet.hp -= amount * (1 - red);
        ServerPlayer p = server.getPlayerList().getPlayer(owner);
        if (!pet.armor.isEmpty()) {
            pet.armorDur -= (int) Math.ceil(amount);
            if (pet.armorDur <= 0) {
                if (p != null) {
                    Lobby.msg(p, "§c" + pet.name + "'s " + PetItems.armorName(pet.armor).replaceAll("§.", "") + " broke!");
                    p.level().playSound(null, chicken.getX(), chicken.getY(), chicken.getZ(), SoundEvents.GLASS_BREAK, SoundSource.PLAYERS, 1f, 1.2f);
                }
                pet.armor = "";
                pet.armorDur = 0;
            }
        }
        if (pet.hp <= 0) down(pet, p);
        dirty();
        nameTag(pet);
    }

    private static void down(Pet pet, ServerPlayer p) {
        pet.hp = 0;
        pet.downed = true;
        pet.downSec = 0;
        releaseTarget(pet);
        if (pet.entity != null) pet.entity.getNavigation().stop();
        if (p != null) {
            Lobby.msg(p, "§c§l" + pet.name + " is DOWNED! §7Give it a §dLittle Treat§7, or spend 15 minutes on your island, to get it back up.");
            Lobby.msg(p, "§8(It keeps following you, but it won't fight.)");
        }
    }

    private static void revive(Pet pet, double frac) {
        pet.downed = false;
        pet.downSec = 0;
        pet.hp = maxHp * frac;
        dirty();
        nameTag(pet);
        if (pet.entity != null) {
            ServerLevel l = (ServerLevel) pet.entity.level();
            l.sendParticles(ParticleTypes.HEART, pet.entity.getX(), pet.entity.getY() + 0.7, pet.entity.getZ(), 6, 0.3, 0.2, 0.3, 0.05);
            l.playSound(null, pet.entity.getX(), pet.entity.getY(), pet.entity.getZ(), SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 0.6f, 1.8f);
        }
    }

    // =====================================================================
    // Right-clicking the chicken
    // =====================================================================

    private static InteractionResult use(ServerPlayer p, Entity e, InteractionHand hand) {
        UUID owner = OWNER_OF.get(e.getUUID());
        Pet pet = owner == null ? null : PETS.get(owner);
        if (pet == null) return InteractionResult.FAIL;
        if (!owner.equals(p.getUUID())) {
            if (hand == InteractionHand.MAIN_HAND) bar(p, "§7That's " + pet.ownerName + "'s chicken, §f" + pet.name + "§7.");
            return InteractionResult.FAIL;
        }
        if (hand != InteractionHand.MAIN_HAND) return InteractionResult.SUCCESS;
        ItemStack held = p.getMainHandItem();
        if (Stacks.is(held, PetItems.TREAT)) {
            if (!pet.downed) {
                bar(p, "§7" + pet.name + " isn't downed - save the treat! §8(Little Snacks heal it.)");
                return InteractionResult.SUCCESS;
            }
            held.shrink(1);
            revive(pet, TREAT_REVIVE);
            Lobby.msg(p, "§d" + pet.name + " gobbles the Little Treat and jumps back up §7(75% health)§d!");
            return InteractionResult.SUCCESS;
        }
        if (Stacks.is(held, PetItems.SNACK)) {
            if (pet.downed) {
                bar(p, "§7" + pet.name + " is downed - it needs a §dLittle Treat §7first.");
                return InteractionResult.SUCCESS;
            }
            if (pet.hp >= maxHp) {
                bar(p, "§a" + pet.name + " is already at full health.");
                return InteractionResult.SUCCESS;
            }
            if (ticks < pet.feedReady) return InteractionResult.SUCCESS;
            pet.feedReady = ticks + FEED_COOLDOWN_TICKS;
            held.shrink(1);
            pet.hp = Math.min(maxHp, pet.hp + maxHp * SNACK_HEAL);
            com.aidan.skyserver.lobby.PlayerStats.add(p, "snack_fed", 1); // 5.27: Wren's quest
            dirty();
            nameTag(pet);
            p.level().playSound(null, e.getX(), e.getY(), e.getZ(), SoundEvents.ITEM_PICKUP, SoundSource.PLAYERS, 0.8f, 1.4f);
            ((ServerLevel) e.level()).sendParticles(ParticleTypes.HAPPY_VILLAGER, e.getX(), e.getY() + 0.5, e.getZ(), 3, 0.2, 0.2, 0.2, 0);
            bar(p, "§a" + pet.name + " §c❤ §f" + (int) Math.ceil(100 * pet.hp / maxHp) + "%");
            return InteractionResult.SUCCESS;
        }
        String kind = PetItems.plateKind(held);
        if (kind != null) {
            int dur = PetItems.plateDur(held);
            held.shrink(1);
            if (!pet.armor.isEmpty()) PetItems.give(p, PetItems.plateWithDur(pet.armor, pet.armorDur));
            pet.armor = kind;
            pet.armorDur = dur;
            dirty();
            nameTag(pet);
            Lobby.msg(p, "§a" + pet.name + " is wearing the " + PetItems.armorName(kind) + "§a now §7(-" + Math.round(PetItems.reduction(kind) * 100) + "% damage).");
            return InteractionResult.SUCCESS;
        }
        openMenu(p);
        return InteractionResult.SUCCESS;
    }

    private static void bar(ServerPlayer p, String text) {
        com.aidan.skyserver.lobby.Announce.actionBar(p, text);
    }

    // =====================================================================
    // /pet menu
    // =====================================================================

    static void openMenu(ServerPlayer p) {
        if (!PETS.containsKey(p.getUUID())) {
            Lobby.msg(p, "§7You don't have a chicken yet - you get one with your Sky Island (§e/is§7).");
            return;
        }
        LockedMenu.open(p, "§8Your Chicken", 3, PetMenu::new);
    }

    private static final class PetMenu extends LockedMenu {
        PetMenu(int id, Inventory inv, SimpleContainer box, ServerPlayer viewer) {
            super(id, inv, box, 3, viewer);
            draw();
        }

        private void draw() {
            Pet pet = PETS.get(viewer.getUUID());
            for (int i = 0; i < box.getContainerSize(); i++) box.setItem(i, ItemStack.EMPTY);
            if (pet == null) return;
            int pct = (int) Math.ceil(100 * Math.max(0, pet.hp) / maxHp);
            List<String> info = new ArrayList<>();
            info.add("§7Health: " + (pet.downed ? "§cDOWNED" : "§f" + (int) Math.ceil(pet.hp) + " §8/ " + (int) maxHp + " §7(" + pct + "%)"));
            if (pet.downed) info.add("§7Back up in §f" + Math.max(0, (REVIVE_ISLAND_SECONDS - pet.downSec) / 60) + " min §7on your island, or use a §dLittle Treat§7.");
            info.add("§7Armour: " + PetItems.armorName(pet.armor) + (pet.armor.isEmpty() ? "" : " §8(" + String.format("%,d", pet.armorDur) + " / " + String.format("%,d", PetItems.maxDur(pet.armor)) + ")"));
            info.add(PetItems.today().equals(pet.nestDay) ? "§7Today's Nest Egg: §alaid" : "§7Today's Nest Egg: §f" + Math.max(0, (NEST_SECONDS - pet.nestSec) / 60) + " min §7more on your island");
            info.add("§7Status: " + (pet.away ? "§8at home (sent away)" : "§awith you"));
            box.setItem(4, Stacks.named(new ItemStack(Items.FEATHER), "§e§l" + pet.name, info.toArray(new String[0])));
            box.setItem(10, Stacks.named(new ItemStack(Items.LEAD), pet.away ? "§a§lCall " + pet.name : "§c§lSend " + pet.name + " home",
                    pet.away ? "§7It comes back to your side." : "§7It waits at home until you call it.", "§eClick"));
            box.setItem(12, Stacks.named(new ItemStack(Items.NAME_TAG), "§fRename", "§7Type §e/pet name <name>", "§8(up to 16 letters)"));
            box.setItem(14, Stacks.named(new ItemStack(pet.armor.isEmpty() ? Items.LEATHER_CHESTPLATE : Items.GOLDEN_CHESTPLATE), "§bArmour",
                    pet.armor.isEmpty() ? "§7Right-click your chicken with a" : "§7Wearing: " + PetItems.armorName(pet.armor),
                    pet.armor.isEmpty() ? "§7Feather Plate to put it on." : "§eClick to take it off §8(keeps its durability)"));
            box.setItem(22, Stacks.named(new ItemStack(Items.BOOK), "§fHow it works",
                    "§7Fights with you: §fNether, End, Sunken", "§7Stays home: §fAncient City, games, duels",
                    "§7Keeps §fone §7monster busy (the one nearest", "§7you). Pecks 1 heart every 1.5 s.",
                    "§dLittle Treat§7: downed -> 75% (2 a day:", "§7daily quests + first expedition clear)",
                    "§aLittle Snack§7: +10% health (grown on", "§7Snack Planters - §e/is §7menu)", "§715 min on your island: downed -> full"));
            frame(Stacks.named(new ItemStack(Items.STAINED_GLASS_PANE.pick(DyeColor.YELLOW)), " "));
            broadcastChanges();
        }

        @Override
        protected void onClick(int slot, int button, boolean shift) {
            Pet pet = PETS.get(viewer.getUUID());
            if (pet == null) return;
            switch (slot) {
                case 10 -> {
                    pet.away = !pet.away;
                    dirty();
                    Lobby.msg(viewer, pet.away ? "§7" + pet.name + " went home. §e/pet call §7brings it back." : "§a" + pet.name + " is coming!");
                    draw();
                }
                case 12 -> Lobby.msg(viewer, "§7Type §e/pet name <name> §7to rename your chicken.");
                case 14 -> {
                    if (pet.armor.isEmpty()) return;
                    PetItems.give(viewer, PetItems.plateWithDur(pet.armor, pet.armorDur));
                    pet.armor = "";
                    pet.armorDur = 0;
                    dirty();
                    nameTag(pet);
                    draw();
                }
                default -> {
                }
            }
        }
    }

    // =====================================================================
    // Hen Party (op)
    // =====================================================================

    private static int henParty(ServerPlayer op) {
        if (giant != null) {
            Lobby.msg(op, "§7A Hen Party is already going.");
            return 0;
        }
        ServerLevel level = (ServerLevel) op.level();
        List<Pet> crew = new ArrayList<>();
        for (Pet pet : PETS.values())
            if (pet.entity != null && !pet.entity.isRemoved() && pet.entity.level() == level && pet.entity.distanceToSqr(op) < 24 * 24 && !pet.downed) crew.add(pet);
        if (crew.isEmpty()) {
            Lobby.msg(op, "§7No chickens near you to throw a party with.");
            return 0;
        }
        Mob g = EntityTypes.CHICKEN.create(level, EntitySpawnReason.EVENT);
        if (g == null) return 0;
        for (Pet pet : crew) {
            ((ServerLevel) pet.entity.level()).sendParticles(ParticleTypes.CLOUD, pet.entity.getX(), pet.entity.getY() + 0.4, pet.entity.getZ(), 10, 0.3, 0.3, 0.3, 0.05);
            pet.henUntil = ticks + HEN_PARTY_TICKS;
            despawn(pet);
        }
        g.snapTo(op.getX(), op.getY(), op.getZ(), op.getYRot(), 0f);
        g.addTag(TAG);
        g.addTag(GIANT_TAG);
        g.setNoAi(true);
        g.setPersistenceRequired();
        AttributeInstance scale = g.getAttribute(Attributes.SCALE);
        if (scale != null) scale.setBaseValue(Math.min(6, 2.5 + crew.size()));
        g.setCustomName(Component.literal("§6§lHEN PARTY §7(" + crew.size() + ")"));
        g.setCustomNameVisible(true);
        giant = g;
        giantLeader = op;
        level.addFreshEntity(g);
        giantEnds = ticks + HEN_PARTY_TICKS;
        giantNextHit = ticks + 10;
        level.playSound(null, op.getX(), op.getY(), op.getZ(), SoundEvents.TOTEM_USE, SoundSource.PLAYERS, 1f, 1.6f);
        for (ServerPlayer p : level.players()) if (p.distanceToSqr(op) < 32 * 32) Lobby.msg(p, "§6§lHEN PARTY! §7" + crew.size() + " chicken" + (crew.size() == 1 ? "" : "s") + " became one giant hen for 20 seconds!");
        return 1;
    }

    private static void henTick() {
        Mob g = giant;
        if (g.isRemoved() || ticks >= giantEnds || giantLeader == null || giantLeader.hasDisconnected() || giantLeader.level() != g.level()) {
            if (!g.isRemoved()) {
                ((ServerLevel) g.level()).sendParticles(ParticleTypes.CLOUD, g.getX(), g.getY() + 1, g.getZ(), 30, 0.8, 0.8, 0.8, 0.05);
                g.discard();
            }
            giant = null;
            giantLeader = null;
            for (Pet pet : PETS.values()) pet.henUntil = 0;
            return;
        }
        ServerLevel level = (ServerLevel) g.level();
        if (ticks % 2 == 0) {
            double a = Math.toRadians(giantLeader.getYRot() + 180);
            double tx = giantLeader.getX() + Math.sin(a) * -2.5, tz = giantLeader.getZ() + Math.cos(a) * 2.5;
            g.snapTo(tx, giantLeader.getY(), tz, giantLeader.getYRot(), 0f);
        }
        if (ticks >= giantNextHit) {
            giantNextHit = ticks + HEN_HIT_TICKS;
            int hits = 0;
            for (Mob m : level.getEntitiesOfClass(Mob.class, g.getBoundingBox().inflate(3), x -> x instanceof Enemy && x.isAlive() && !isPet(x))) {
                m.hurtServer(level, g.damageSources().mobAttack(g), HEN_DAMAGE);
                m.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, HEN_STUN_TICKS, 9, false, false));
                m.getNavigation().stop();
                hits++;
            }
            if (hits > 0) {
                level.sendParticles(ParticleTypes.CRIT, g.getX(), g.getY() + 0.5, g.getZ(), 20, 2, 0.5, 2, 0.2);
                level.playSound(null, g.getX(), g.getY(), g.getZ(), SoundEvents.CHICKEN_EGG, SoundSource.PLAYERS, 1.5f, 0.5f);
            }
        }
    }

    // =====================================================================
    // Commands
    // =====================================================================

    private static UUID find(CommandSourceStack s, String name) {
        ServerPlayer p = s.getServer().getPlayerList().getPlayerByName(name);
        if (p != null) return p.getUUID();
        try {
            Optional<NameAndId> n = s.getServer().services().nameToIdCache().get(name);
            if (n.isPresent()) return n.get().id();
        } catch (RuntimeException ignored) {
        }
        s.sendSystemMessage(Component.literal("§cNo player called " + name + " has been on this server."));
        return null;
    }

    private static int giveChicken(CommandSourceStack s, String who) {
        int n = 0;
        if (who.equalsIgnoreCase("all")) {
            for (Islands.Island is : Islands.all()) {
                if (is.owner != null && grant(is.owner, is.ownerName, false)) n++;
                for (UUID m : is.members) if (grant(m, is.memberNames.getOrDefault(m, ""), false)) n++;
            }
            for (ServerPlayer p : s.getServer().getPlayerList().getPlayers())
                if (Islands.islandOf(p.getUUID()) != null && grant(p.getUUID(), p.getName().getString(), false)) n++;
            s.sendSystemMessage(Component.literal("§aGave " + n + " chicken" + (n == 1 ? "" : "s") + " §7(everyone with an island who didn't have one)."));
        } else {
            UUID id = find(s, who);
            if (id == null) return 0;
            if (grant(id, who, false)) n = 1;
            s.sendSystemMessage(Component.literal(n == 1 ? "§aGave " + who + " a chicken." : "§7" + who + " already has a chicken."));
        }
        saveNow(true);
        return n;
    }

    private static int giveItem(CommandSourceStack s, String who, String kind, int count) {
        ServerPlayer p = s.getServer().getPlayerList().getPlayerByName(who);
        if (p == null) {
            s.sendSystemMessage(Component.literal("§c" + who + " isn't online."));
            return 0;
        }
        ItemStack st = switch (kind.toLowerCase()) {
            case "treat" -> PetItems.treat(count);
            case "snack" -> PetItems.snack(count);
            case "plant", "planter" -> Planters.item(0);
            case "egg" -> {
                ItemStack e = PetItems.nestEgg();
                e.setCount(Math.min(16, count));
                yield e;
            }
            case "feather" -> PetItems.featherPlate();
            case "gilded" -> PetItems.gildedPlate();
            default -> ItemStack.EMPTY;
        };
        if (st.isEmpty()) {
            s.sendSystemMessage(Component.literal("§cKinds: treat, snack, plant, egg, feather, gilded"));
            return 0;
        }
        PetItems.give(p, st);
        s.sendSystemMessage(Component.literal("§aGave " + who + " " + kind + "."));
        return 1;
    }

    private static void registerCommands() {
        CommandRegistrationCallback.EVENT.register((d, r, e) -> {
            d.register(Commands.literal("pet")
                    .executes(c -> {
                        openMenu(c.getSource().getPlayerOrException());
                        return 1;
                    })
                    .then(Commands.literal("call").executes(c -> {
                        ServerPlayer p = c.getSource().getPlayerOrException();
                        Pet pet = PETS.get(p.getUUID());
                        if (pet == null) return 0;
                        pet.away = false;
                        dirty();
                        Lobby.msg(p, zone(p) == Zone.HOME ? "§7" + pet.name + " can't come here, but it'll join you when it can." : "§a" + pet.name + " is coming!");
                        return 1;
                    }))
                    .then(Commands.literal("away").executes(c -> {
                        ServerPlayer p = c.getSource().getPlayerOrException();
                        Pet pet = PETS.get(p.getUUID());
                        if (pet == null) return 0;
                        pet.away = true;
                        dirty();
                        Lobby.msg(p, "§7" + pet.name + " went home. §e/pet call §7brings it back.");
                        return 1;
                    }))
                    .then(Commands.literal("name").then(Commands.argument("name", StringArgumentType.greedyString()).executes(c -> {
                        ServerPlayer p = c.getSource().getPlayerOrException();
                        Pet pet = PETS.get(p.getUUID());
                        if (pet == null) return 0;
                        String n = StringArgumentType.getString(c, "name").replace("§", "").trim();
                        if (n.isEmpty()) return 0;
                        if (n.length() > 16) n = n.substring(0, 16);
                        pet.name = n;
                        dirty();
                        nameTag(pet);
                        Lobby.msg(p, "§aYour chicken is called §f" + n + " §anow.");
                        return 1;
                    }))));
            d.register(Commands.literal("ss")
                    .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                    .then(Commands.literal("givechicken").then(Commands.argument("player", StringArgumentType.word())
                            .executes(c -> giveChicken(c.getSource(), StringArgumentType.getString(c, "player")))))
                    .then(Commands.literal("henparty").executes(c -> henParty(c.getSource().getPlayerOrException())))
                    .then(Commands.literal("chicken")
                            .then(Commands.literal("hp").then(Commands.argument("hp", DoubleArgumentType.doubleArg(1, 100_000)).executes(c -> {
                                double old = maxHp, now = DoubleArgumentType.getDouble(c, "hp");
                                maxHp = now;
                                for (Pet pet : PETS.values()) if (!pet.downed) pet.hp = Math.max(1, pet.hp / old * now);
                                saveNow(true);
                                c.getSource().sendSystemMessage(Component.literal("§aChicken health is now " + (int) now + " §7(was " + (int) old + "; current health scaled to match)."));
                                return 1;
                            })))
                            .then(Commands.literal("item").then(Commands.argument("player", StringArgumentType.word())
                                    .then(Commands.argument("kind", StringArgumentType.word())
                                            .executes(c -> giveItem(c.getSource(), StringArgumentType.getString(c, "player"), StringArgumentType.getString(c, "kind"), 1))
                                            .then(Commands.argument("count", IntegerArgumentType.integer(1, 64))
                                                    .executes(c -> giveItem(c.getSource(), StringArgumentType.getString(c, "player"), StringArgumentType.getString(c, "kind"),
                                                            IntegerArgumentType.getInteger(c, "count")))))))));
        });
    }

    // =====================================================================
    // Setup
    // =====================================================================

    public static void init() {
        PetItems.init();
        Planters.init();
        registerCommands();
        ServerLifecycleEvents.SERVER_STARTED.register(s -> {
            server = s;
            Safe.run("chicken load", Companion::load);
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(s -> Safe.run("chicken save", () -> {
            for (Pet pet : PETS.values()) despawn(pet);
            if (giant != null && !giant.isRemoved()) giant.discard();
            giant = null;
            saveNow(false);
        }));
        ServerTickEvents.END_SERVER_TICK.register(s -> Safe.run("chicken tick", () -> tick(s)));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, s) -> Safe.run("chicken leave", () -> {
            Pet pet = PETS.get(handler.getPlayer().getUUID());
            if (pet != null) despawn(pet);
        }));
        // Old chickens of ours left in a world (crash, restart) go; so do eggs our chickens lay.
        ServerEntityEvents.ENTITY_LOAD.register((entity, world) -> {
            if (isPet(entity)) {
                UUID owner = OWNER_OF.get(entity.getUUID());
                boolean live = entity == giant || owner != null && PETS.containsKey(owner) && PETS.get(owner).entity == entity;
                if (!live) Lobby.runLaterTicks(1, () -> {
                    if (!entity.isRemoved() && entity != giant && !OWNER_OF.containsKey(entity.getUUID())) entity.discard();
                });
                return;
            }
            if (entity instanceof ItemEntity ie && ie.getItem().is(Items.EGG) && !Stacks.is(ie.getItem(), PetItems.EGG)
                    && !world.getEntitiesOfClass(Mob.class, ie.getBoundingBox().inflate(1.5), Companion::isPet).isEmpty())
                Lobby.runLaterTicks(1, ie::discard);
        });
        // Nothing hurts the real chicken; hits from the monster it's distracting come off its own health.
        ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> {
            if (!isPet(entity)) return true;
            if (!entity.entityTags().contains(GIANT_TAG)) Safe.run("chicken hurt", () -> hurt(entity, source.getEntity(), amount));
            return false;
        });
        UseEntityCallback.EVENT.register((player, level, hand, entity, hit) -> {
            if (level.isClientSide() || !isPet(entity)) return InteractionResult.PASS;
            if (!(player instanceof ServerPlayer sp) || entity.entityTags().contains(GIANT_TAG)) return InteractionResult.FAIL;
            InteractionResult[] out = {InteractionResult.FAIL};
            Safe.run("chicken use", () -> out[0] = use(sp, entity, hand));
            return out[0];
        });
        AttackEntityCallback.EVENT.register((player, level, hand, entity, hit) -> isPet(entity) ? InteractionResult.FAIL : InteractionResult.PASS);
    }
}
