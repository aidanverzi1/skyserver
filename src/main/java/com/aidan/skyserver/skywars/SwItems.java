package com.aidan.skyserver.skywars;

import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.entity.projectile.hurtingprojectile.LargeFireball;
import net.minecraft.world.entity.projectile.throwableitemprojectile.Snowball;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Random;

/**
 * Skywars' special items. They're normal vanilla items with a name (so players don't need a mod);
 * the server makes them do their thing:
 *  - Fireball: right-click to launch an exploding fireball.
 *  - Knockback Stick: a stick with Knockback II.
 *  - Grappling Hook: cast at a block, reel in to be pulled toward it.
 *  - Healing Soup: right-click to heal 3 hearts instantly.
 *  - Instant TNT: lights itself the moment it's placed.
 *  - Bridge Egg: throw it to build a 20-block bridge (see BridgeEggs).
 *  - Snowballs knock players back hard in Skywars.
 * Plus vanilla ender pearls, speed and jump potions, and "lucky" enchanted gear.
 */
public final class SwItems {
    public static final String FIREBALL = "Fireball", KB_STICK = "Knockback Stick", HOOK = "Grappling Hook",
            SOUP = "Healing Soup", INSTANT_TNT = "Instant TNT";
    private static final Random RNG = new Random();
    private static MinecraftServer server;

    private SwItems() {}

    public static void init(MinecraftServer s) {
        server = s;
    }

    static void registerEvents() {
        UseItemCallback.EVENT.register((player, level, hand) -> {
            if (level.isClientSide() || !(player instanceof ServerPlayer sp) || !SkywarsGame.isAlive(sp.getUUID())) return InteractionResult.PASS;
            ItemStack held = sp.getItemInHand(hand);
            if (is(held, FIREBALL)) return fireball(sp, held);
            if (is(held, SOUP)) return soup(sp, held);
            if (is(held, HOOK) && sp.fishing != null) grapple(sp, sp.fishing);
            return InteractionResult.PASS;
        });
        UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
            if (level.isClientSide() || !(player instanceof ServerPlayer sp) || !SkywarsGame.isAlive(sp.getUUID())) return InteractionResult.PASS;
            ItemStack held = sp.getItemInHand(hand);
            if (!is(held, INSTANT_TNT)) return InteractionResult.PASS;
            BlockPos at = hit.getBlockPos().relative(hit.getDirection());
            if (!level.getBlockState(at).canBeReplaced()) return InteractionResult.FAIL;
            PrimedTnt tnt = new PrimedTnt(level, at.getX() + 0.5, at.getY(), at.getZ() + 0.5, sp);
            tnt.setFuse(40);
            level.addFreshEntity(tnt);
            level.playSound(null, at, SoundEvents.TNT_PRIMED, SoundSource.BLOCKS, 1f, 1f);
            if (!sp.isCreative()) held.shrink(1);
            return InteractionResult.SUCCESS;
        });
        // Snowballs push players back much harder than normal.
        ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> {
            if (entity instanceof ServerPlayer target && source.getDirectEntity() instanceof Snowball ball
                    && SkywarsGame.isAlive(target.getUUID())) {
                Vec3 push = ball.getDeltaMovement().multiply(1, 0, 1).normalize().scale(0.9);
                target.setDeltaMovement(target.getDeltaMovement().add(push.x, 0.35, push.z));
                target.connection.send(new ClientboundSetEntityMotionPacket(target));
            }
            return true;
        });
    }

    public static boolean is(ItemStack stack, String name) {
        Component n = stack.get(DataComponents.CUSTOM_NAME);
        return n != null && name.equals(n.getString());
    }

    private static ItemStack named(Item item, int count, String name, String... lore) {
        ItemStack s = new ItemStack(item, count);
        s.set(DataComponents.CUSTOM_NAME, Component.literal(name));
        if (lore.length > 0) {
            List<Component> lines = new java.util.ArrayList<>();
            for (String l : lore) lines.add(Component.literal(l));
            s.set(DataComponents.LORE, new ItemLore(lines));
        }
        return s;
    }

    // ---- The items ----

    public static ItemStack fireball(int n) {
        return named(Items.FIRE_CHARGE, n, FIREBALL, "§7Right-click to launch it.");
    }

    public static ItemStack kbStick() {
        ItemStack s = named(Items.STICK, 1, KB_STICK, "§7Sends them flying.");
        enchant(s, Enchantments.KNOCKBACK, 2);
        return s;
    }

    public static ItemStack hook() {
        return named(Items.FISHING_ROD, 1, HOOK, "§7Cast at a block, then reel in", "§7to be pulled toward it.");
    }

    public static ItemStack soup(int n) {
        return named(Items.MUSHROOM_STEW, n, SOUP, "§7Right-click: heal 3 hearts instantly.");
    }

    public static ItemStack instantTnt(int n) {
        return named(Items.TNT, n, INSTANT_TNT, "§7Lights itself when placed.");
    }

    public static ItemStack speedPotion() {
        return PotionContents.createItemStack(Items.POTION, Potions.SWIFTNESS);
    }

    public static ItemStack jumpPotion() {
        return PotionContents.createItemStack(Items.POTION, Potions.LEAPING);
    }

    public static ItemStack regenPotion() {
        return PotionContents.createItemStack(Items.SPLASH_POTION, Potions.REGENERATION);
    }

    /** Adds an enchantment (quietly does nothing if the registry isn't ready). */
    public static void enchant(ItemStack s, ResourceKey<Enchantment> key, int level) {
        if (server == null) return;
        try {
            Holder<Enchantment> e = server.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(key);
            s.enchant(e, level);
        } catch (RuntimeException ignored) {
        }
    }

    /** A random piece of enchanted gear ("lucky" loot). better = diamond. */
    public static ItemStack luckyGear(boolean better) {
        int r = RNG.nextInt(6);
        ItemStack s;
        switch (r) {
            case 0 -> { s = new ItemStack(better ? Items.DIAMOND_SWORD : Items.IRON_SWORD); enchant(s, Enchantments.SHARPNESS, 1 + RNG.nextInt(2)); }
            case 1 -> { s = new ItemStack(Items.BOW); enchant(s, Enchantments.POWER, 1 + RNG.nextInt(2)); if (better) enchant(s, Enchantments.PUNCH, 1); }
            case 2 -> { s = new ItemStack(better ? Items.DIAMOND_CHESTPLATE : Items.IRON_CHESTPLATE); enchant(s, Enchantments.PROTECTION, 1 + RNG.nextInt(2)); }
            case 3 -> { s = new ItemStack(better ? Items.DIAMOND_HELMET : Items.IRON_HELMET); enchant(s, Enchantments.PROTECTION, 1 + RNG.nextInt(2)); }
            case 4 -> { s = new ItemStack(better ? Items.DIAMOND_BOOTS : Items.IRON_BOOTS); enchant(s, Enchantments.FEATHER_FALLING, 2); enchant(s, Enchantments.PROTECTION, 1); }
            default -> { s = new ItemStack(better ? Items.DIAMOND_AXE : Items.IRON_AXE); enchant(s, Enchantments.SHARPNESS, 1); }
        }
        s.set(DataComponents.CUSTOM_NAME, Component.literal("§bLucky " + s.getHoverName().getString()));
        return s;
    }

    // ---- What they do ----

    private static InteractionResult fireball(ServerPlayer p, ItemStack held) {
        Vec3 look = p.getLookAngle();
        LargeFireball ball = new LargeFireball(p.level(), p, look.scale(1.0), 1);
        ball.setPos(p.getX() + look.x * 1.2, p.getEyeY() - 0.2 + look.y * 1.2, p.getZ() + look.z * 1.2);
        p.level().addFreshEntity(ball);
        p.level().playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.GHAST_SHOOT, SoundSource.PLAYERS, 0.8f, 1.2f);
        if (!p.isCreative()) held.shrink(1);
        p.getCooldowns().addCooldown(held, 15);
        return InteractionResult.SUCCESS;
    }

    private static InteractionResult soup(ServerPlayer p, ItemStack held) {
        if (p.getHealth() >= p.getMaxHealth()) return InteractionResult.FAIL;
        p.heal(6f);
        p.level().playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.GENERIC_DRINK.value(), SoundSource.PLAYERS, 0.8f, 1.1f);
        if (!p.isCreative()) held.shrink(1);
        return InteractionResult.SUCCESS;
    }

    /** Reeling in a hook that's stuck in (or resting on) a block pulls the player to it. */
    private static void grapple(ServerPlayer p, FishingHook hook) {
        if (hook.getHookedIn() != null) return;
        boolean resting = hook.onGround() || hook.getDeltaMovement().lengthSqr() < 0.01 || !hook.level().noCollision(hook, hook.getBoundingBox().inflate(0.1));
        if (!resting) return;
        Vec3 to = hook.position().subtract(p.position());
        double dist = to.length();
        if (dist < 2 || dist > 32) return;
        Vec3 pull = to.normalize().scale(Math.min(2.2, 0.35 + dist * 0.09)).add(0, 0.35, 0);
        p.setDeltaMovement(pull);
        p.connection.send(new ClientboundSetEntityMotionPacket(p));
        p.resetFallDistance();
        p.level().playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.FISHING_BOBBER_RETRIEVE, SoundSource.PLAYERS, 1f, 1.4f);
    }

    /** True for special items that bots shouldn't bother picking up. */
    public static boolean botIgnores(ItemStack s) {
        return is(s, HOOK) || is(s, INSTANT_TNT) || s.is(Items.POTION) || s.is(Items.SPLASH_POTION);
    }
}
