package com.aidan.skyserver;

import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;

/**
 * 5.17: Protection VI and VII do something on a full set.
 *
 * Vanilla caps enchantment protection at 20 points (80% less damage), which four Protection V pieces
 * already reach. Here every Protection level ABOVE that cap (adding the four armour pieces together)
 * takes off another EXTRA_PER_LEVEL of the damage: four Protection VI = 24 levels = +3% (83%), four
 * Protection VII = 28 levels = +6% (86%). Partial sets work as in vanilla (VI/VII still help there).
 *
 * How: no mixin. After a player is hurt, the extra share is healed straight back (taken x extra / 0.20,
 * since what got through is the 20% vanilla let past). Damage that ignores enchantments (void, /kill,
 * starving...) is left alone. A hit that kills at 80% still kills - the heal comes after the hit.
 * Started from Activities.init().
 */
public final class ExtraProtection {
    public static final double EXTRA_PER_LEVEL = 0.0075;
    public static final int VANILLA_CAP = 20;

    private ExtraProtection() {}

    public static void init() {
        ServerLivingEntityEvents.AFTER_DAMAGE.register((entity, source, base, taken, blocked) -> {
            if (!(entity instanceof ServerPlayer p) || blocked || taken <= 0 || !p.isAlive()) return;
            if (source.is(DamageTypeTags.BYPASSES_ENCHANTMENTS)) return;
            Safe.run("extra protection", () -> {
                double extra = extra(p);
                if (extra <= 0) return;
                float heal = (float) (taken * extra / 0.20);
                if (heal > 0) p.setHealth(Math.min(p.getMaxHealth(), p.getHealth() + heal));
            });
        });
    }

    /** The extra share of damage taken off for this player's armour (0 below the vanilla cap). */
    public static double extra(ServerPlayer p) {
        Holder<Enchantment> prot = p.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.PROTECTION);
        int total = 0;
        for (EquipmentSlot slot : new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
            ItemStack s = p.getItemBySlot(slot);
            if (!s.isEmpty()) total += s.getEnchantments().getLevel(prot);
        }
        return total > VANILLA_CAP ? (total - VANILLA_CAP) * EXTRA_PER_LEVEL : 0;
    }
}
