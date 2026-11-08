package com.aidan.skyserver;

import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.commands.Commands;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetActionBarTextPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.*;

/**
 * The Longbow: an official server item that works anywhere (Gold Wars, Skywars, survival).
 *
 *  - Hold for 3.5 seconds for a full charge: an instant beam with infinite range (stopped by
 *    blocks) that deals 21.2 damage, reduced by armor. That exactly kills a full-health
 *    player in full leather; better armor survives.
 *  - Release early: a weak normal arrow (1 to 4 hearts, reduced by armor).
 *  - No reload: shoot again as soon as you like (the long charge is the price).
 *  - Hits players and mobs. Games can add their own rules (Gold Wars blocks shooting from
 *    spawn and hitting teammates).
 *
 * It's a normal bow with Infinity plus a hidden marker in its lore, which players can't add
 * in survival, so renaming a bow in an anvil won't fake one. Admins get one with /longbow.
 */
public final class Longbow {
    public static final String NAME = "Longbow";
    private static final String MARKER = "Skyserver Longbow";

    public static final int FULL_CHARGE_TICKS = 70; // 3.5 seconds
    public static final int RELOAD_TICKS = 0; // no reload: the long charge is the cost
    public static final float FULL_DAMAGE = 21.2f;
    public static final float WEAK_MIN = 2f;
    public static final float WEAK_MAX = 8f;
    public static final double RANGE = 256;

    /** A rule that can block a shot: return a message to show the player, or null to allow it. */
    public interface ShotRule {
        String check(ServerPlayer shooter);
    }

    /** A rule that decides whether the beam can hit a target. */
    public interface TargetRule {
        boolean canHit(ServerPlayer shooter, LivingEntity target);
    }

    private static final List<ShotRule> shotRules = new ArrayList<>();
    private static final List<TargetRule> targetRules = new ArrayList<>();

    private static long ticks = 0;
    private static final Map<UUID, Integer> drawTicks = new HashMap<>();
    private static final Map<UUID, Long> lastDrawTick = new HashMap<>();
    private static final Map<UUID, Long> readyAt = new HashMap<>();
    private static final List<AbstractArrow> toRemove = new ArrayList<>();
    /** Full-charge shots keep a harmless arrow flying for looks; removed when it reaches the hit. */
    private static final Map<AbstractArrow, Long> visualArrows = new HashMap<>();
    private static final double VISUAL_SPEED = 5.0; // blocks per tick

    private Longbow() {}

    public static void addShotRule(ShotRule rule) {
        shotRules.add(rule);
    }

    public static void addTargetRule(TargetRule rule) {
        targetRules.add(rule);
    }

    private static boolean initialized = false;

    /** Safe to call more than once (Skyserver and Gold Wars both call it). */
    public static void init() {
        if (initialized) return;
        initialized = true;
        Skyserver.LOGGER.info("Longbow loaded");
        ServerTickEvents.END_SERVER_TICK.register(s -> Safe.run("longbow", () -> tick(s)));

        // Catch the Longbow's arrow the moment it's fired.
        ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> {
            if (!(entity instanceof AbstractArrow arrow)) return;
            if (!(arrow.getOwner() instanceof ServerPlayer p)) return;
            if (!isLongbow(p.getMainHandItem()) && !isLongbow(p.getOffhandItem())) return;
            onRelease(p, arrow, level);
        });

        // /longbow (admins): get a Longbow and an arrow.
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> dispatcher.register(
                Commands.literal("longbow").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS)).executes(c -> {
                    ServerPlayer p = c.getSource().getPlayerOrException();
                    p.getInventory().add(create(p));
                    p.getInventory().add(new ItemStack(Items.ARROW));
                    c.getSource().sendSuccess(() -> Component.literal("Gave you a Longbow."), false);
                    return 1;
                })));
    }

    // =====================================================================
    // The item
    // =====================================================================

    public static ItemStack create(ServerPlayer p) {
        ItemStack bow = new ItemStack(Items.BOW);
        enchant(p, bow, Enchantments.INFINITY, 1);
        enchant(p, bow, Enchantments.UNBREAKING, 3);
        bow.set(DataComponents.CUSTOM_NAME, Component.literal("§b" + NAME));
        bow.set(DataComponents.LORE, new ItemLore(List.of(
                Component.literal("§7Hold 3.5s for a full-charge beam"),
                Component.literal("§7Release early for a weak arrow"),
                Component.literal("§7No reload"),
                Component.literal("§8" + MARKER))));
        return bow;
    }

    public static boolean isLongbow(ItemStack stack) {
        if (stack == null || !stack.is(Items.BOW)) return false;
        ItemLore lore = stack.get(DataComponents.LORE);
        if (lore == null) return false;
        for (Component line : lore.lines()) {
            if (line.getString().contains(MARKER)) return true;
        }
        return false;
    }

    /** True if this player's Longbow has finished reloading. */
    public static boolean isReady(ServerPlayer p) {
        Long ready = readyAt.get(p.getUUID());
        return ready == null || ticks >= ready;
    }

    /** "ready" or "2s", for status lines. */
    public static String cooldownText(ServerPlayer p) {
        Long ready = readyAt.get(p.getUUID());
        if (ready == null || ticks >= ready) return "§aready";
        return "§c" + ((ready - ticks + 19) / 20) + "s";
    }

    private static void enchant(ServerPlayer p, ItemStack stack, ResourceKey<Enchantment> key, int level) {
        Holder<Enchantment> holder = p.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(key);
        stack.enchant(holder, level);
    }

    // =====================================================================
    // Shooting
    // =====================================================================

    private static void tick(MinecraftServer server) {
        ticks++;
        for (AbstractArrow arrow : toRemove) arrow.discard();
        toRemove.clear();
        visualArrows.entrySet().removeIf(e -> {
            if (ticks < e.getValue() && !e.getKey().isRemoved()) return false;
            e.getKey().discard();
            return true;
        });

        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (p.isUsingItem() && isLongbow(p.getUseItem())) {
                int t = p.getTicksUsingItem();
                drawTicks.put(p.getUUID(), t);
                lastDrawTick.put(p.getUUID(), ticks);
                showCharge(p, t);
            }
        }
    }

    private static void onRelease(ServerPlayer p, AbstractArrow arrow, ServerLevel level) {
        UUID id = p.getUUID();
        // Only a fresh shot counts (not, say, an old arrow loading in with a chunk).
        Long last = lastDrawTick.get(id);
        if (last == null || ticks - last > 2) return;
        lastDrawTick.remove(id);
        int drawn = drawTicks.getOrDefault(id, 0);

        Long ready = readyAt.get(id);
        if (ready != null && ticks < ready) {
            cancel(arrow);
            p.sendSystemMessage(Component.literal("§cLongbow is reloading (" + ((ready - ticks + 19) / 20) + "s)."));
            return;
        }
        for (ShotRule rule : shotRules) {
            String blocked = rule.check(p);
            if (blocked != null) {
                cancel(arrow);
                p.sendSystemMessage(Component.literal(blocked));
                return;
            }
        }
        readyAt.put(id, ticks + RELOAD_TICKS);

        if (drawn >= FULL_CHARGE_TICKS) {
            fireBeam(p, level, arrow);
        } else {
            double charge = Math.max(0, Math.min(1, drawn / (double) FULL_CHARGE_TICKS));
            float damage = WEAK_MIN + (float) ((WEAK_MAX - WEAK_MIN) * charge);
            double speed = Math.max(0.5, arrow.getDeltaMovement().length());
            arrow.setCritArrow(false);
            arrow.setBaseDamage(damage / speed); // vanilla arrow damage = speed x base damage
            arrow.pickup = AbstractArrow.Pickup.DISALLOWED;
        }
    }

    private static void cancel(AbstractArrow arrow) {
        arrow.setDeltaMovement(Vec3.ZERO);
        toRemove.add(arrow);
    }

    /** Full-charge shot: an instant hit on the first creature in line, shown as a fast arrow. */
    private static void fireBeam(ServerPlayer shooter, ServerLevel level, AbstractArrow arrow) {
        Vec3 start = shooter.getEyePosition();
        Vec3 dir = shooter.getLookAngle();
        Vec3 end = start.add(dir.scale(RANGE));

        BlockHitResult wall = level.clip(new ClipContext(start, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, shooter));
        double reach = wall.getType() == HitResult.Type.MISS ? RANGE : wall.getLocation().distanceTo(start);
        Vec3 wallEnd = start.add(dir.scale(reach));

        LivingEntity target = null;
        AABB search = new AABB(start, wallEnd).inflate(1.0);
        for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, search, e -> e != shooter && e.isAlive())) {
            if (e instanceof ServerPlayer sp && sp.isSpectator()) continue;
            boolean allowed = true;
            for (TargetRule rule : targetRules) if (!rule.canHit(shooter, e)) { allowed = false; break; }
            if (!allowed) continue;
            Optional<Vec3> hit = e.getBoundingBox().inflate(0.15).clip(start, wallEnd);
            if (hit.isPresent()) {
                double d = hit.get().distanceTo(start);
                if (d < reach) {
                    reach = d;
                    target = e;
                }
            }
        }

        // The arrow just flies along the shot for looks: it passes through everything and does
        // no damage itself, and disappears where the shot landed.
        arrow.setNoPhysics(true);
        arrow.setNoGravity(true);
        arrow.setCritArrow(false);
        arrow.setBaseDamage(0);
        arrow.pickup = AbstractArrow.Pickup.DISALLOWED;
        arrow.setDeltaMovement(dir.scale(VISUAL_SPEED));
        visualArrows.put(arrow, ticks + (long) Math.ceil(reach / VISUAL_SPEED) + 1);

        level.playSound(null, shooter.getX(), shooter.getY(), shooter.getZ(),
                SoundEvents.FIREWORK_ROCKET_BLAST, SoundSource.PLAYERS, 2f, 0.6f);

        if (target != null) {
            target.hurtServer(level, shooter.damageSources().playerAttack(shooter), FULL_DAMAGE);
            level.sendParticles(ParticleTypes.CRIT, target.getX(), target.getY() + 1, target.getZ(), 20, 0.3, 0.5, 0.3, 0.2);
            level.playSound(null, target.getX(), target.getY(), target.getZ(),
                    SoundEvents.ARROW_HIT_PLAYER, SoundSource.PLAYERS, 1.5f, 0.8f);
        }
    }

    private static void showCharge(ServerPlayer p, int t) {
        int bars = Math.min(10, t * 10 / FULL_CHARGE_TICKS);
        String bar = "§a" + "|".repeat(bars) + "§7" + "|".repeat(10 - bars);
        String label = t >= FULL_CHARGE_TICKS ? "§b§lFULL CHARGE" : "§eCharging";
        p.connection.send(new ClientboundSetActionBarTextPacket(Component.literal(label + " " + bar)));
    }
}
