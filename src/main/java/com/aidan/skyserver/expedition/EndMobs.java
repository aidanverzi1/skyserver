package com.aidan.skyserver.expedition;

import com.aidan.skyserver.Safe;
import com.aidan.skyserver.Skyserver;
import com.aidan.skyserver.lobby.Announce;
import com.aidan.skyserver.lobby.Lobby;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

import java.util.*;

/**
 * 5.18: the End Expedition's monsters. All vanilla mobs (vanilla clients), with their own attacks added here.
 *
 * WAVE GUARDS (unlocked as the waves of each block of 10 go on; from wave 11 every kind can turn up):
 *  Void Thrall      (zombie)          - their hits sometimes make you float for a moment.          from wave 1
 *  Blink Archer     (skeleton)        - teleport away when hit; their arrows can swap places with you. wave 1
 *  Riftwing         (phantom)         - dive at you and knock you back hard.                       wave 2
 *  Void Gust        (breeze)          - wind charges, and a gust that shoves everyone near it away.  wave 3
 *  Chorus Bomber    (creeper)         - doesn't blow up the island: the blast hurts and throws you somewhere random on it. wave 4
 *  Shulker Sentry   (shulker)         - levitation bullets, some sit on the pillar tops.          wave 6
 *  Watcher          (guardian)        - a beam that hits if you stay in its sight.                 wave 7
 *  Obsidian Golem   (big zombie)      - slow, can't be knocked back; its slam smashes the blocks you placed. wave 8 (one a wave)
 *  CRYSTAL WARDEN waves (3rd and 7th of every 10): an end crystal on a pillar heals the whole wave until someone
 *  breaks it (shoot it, or pearl up to it).
 *
 * MINI BOSSES (waves 5, 15, 25, then round again): Shulker Matriarch (lifts everyone into the air, calls sentries),
 *  Void Herald (swaps places with you, tears rifts in the floor), The Tempest (pulls everyone in, then blasts them away).
 * BOSSES (waves 10, 20, 30, then round again): the Ender Dragon (crystals on every pillar heal it), The Hollow King
 *  (steals the blocks you placed, calls shades at 2/3 and 1/3 health, gets faster at the end), The Eye (floats over
 *  the podium, sweeping beams, mining fatigue that eats your block-breaking).
 */
final class EndMobs {
    private EndMobs() {}

    enum Kind {
        THRALL("§5Void Thrall"), BLINK_ARCHER("§dBlink Archer"), RIFTWING("§9Riftwing"), VOID_GUST("§fVoid Gust"),
        CHORUS_BOMBER("§dChorus Bomber"), SHULKER_SENTRY("§5Shulker Sentry"), WATCHER("§3Watcher"), OBSIDIAN_GOLEM("§8§lObsidian Golem"),
        SHADE("§8Hollow Shade"),
        MATRIARCH("§5§lShulker Matriarch"), HERALD("§8§lVoid Herald"), TEMPEST("§f§lThe Tempest"),
        DRAGON("§5§lEnder Dragon"), HOLLOW_KING("§8§lThe Hollow King"), THE_EYE("§3§lThe Eye");

        final String display;

        Kind(String display) {
            this.display = display;
        }

        boolean mini() {
            return this == MATRIARCH || this == HERALD || this == TEMPEST;
        }

        boolean boss() {
            return this == DRAGON || this == HOLLOW_KING || this == THE_EYE;
        }
    }

    static final String GUARD_TAG = "skyserver_end_guard", CRYSTAL_TAG = "skyserver_end_crystal";

    /** Extra health / damage at wave 1 (the End is harder than a Nether trip), and per 10 waves (damage a bit less). */
    static final double BASE_HEALTH = 0.25, BASE_DAMAGE = 0.20;
    static final double TIER_HEALTH = 0.50, TIER_DAMAGE = 0.40;
    /** Crew (same as the Nether): per extra player +1 guard a wave, +70% guard health, +10% guard damage; bosses CREW_HP. */
    static final int CREW_EXTRA_GUARDS = 1;
    static final double CREW_HEALTH = 0.70, CREW_DAMAGE = 0.10;
    static final double[] BOSS_CREW_HP = {1.0, 1.75, 2.3};
    /** Most guards in one wave. */
    static final int GUARD_CAP = 20;
    /** Health at wave 1-10 (before the multipliers). */
    static final double MATRIARCH_HP = 250, HERALD_HP = 300, TEMPEST_HP = 280, DRAGON_HP = 300, KING_HP = 450, EYE_HP = 400;
    /** Crystal Warden heal per second for every guard (2 = 1 heart). */
    static final float CRYSTAL_HEAL = 2f;

    static final Map<UUID, Kind> KIND = new HashMap<>();
    /** Bombers: ticks they've been fusing. */
    private static final Map<UUID, Integer> FUSE = new HashMap<>();
    private static final Random RNG = new Random();

    static Kind miniFor(int wave) {
        return new Kind[]{Kind.MATRIARCH, Kind.HERALD, Kind.TEMPEST}[Math.floorMod((wave - 5) / 10, 3)];
    }

    static Kind bossFor(int wave) {
        return new Kind[]{Kind.DRAGON, Kind.HOLLOW_KING, Kind.THE_EYE}[Math.floorMod(wave / 10 - 1, 3)];
    }

    static int tier(int wave) {
        return Math.max(0, (wave - 1) / 10);
    }

    static double healthFactor(int wave) {
        return 1 + BASE_HEALTH + TIER_HEALTH * tier(wave);
    }

    static double damageFactor(int wave) {
        return 1 + BASE_DAMAGE + TIER_DAMAGE * tier(wave);
    }

    /** What's coming in this wave, for Wren's chat line. */
    static String preview(int wave) {
        int pos = (wave - 1) % 10 + 1;
        if (pos == 10) return "§c§lBOSS: " + bossFor(wave).display;
        if (pos == 5) return "§d§lMINI BOSS: " + miniFor(wave).display;
        return (pos == 3 || pos == 7 ? "§bCrystal Warden wave §7- " : "§7") + "guards";
    }

    // =====================================================================
    // Spawning
    // =====================================================================

    /** Sends in the whole wave. */
    static void spawnWave(ServerLevel l, EndExpedition.Run run, int wave) {
        int pos = (wave - 1) % 10 + 1, t = tier(wave), extra = run.crewSize - 1;
        if (pos == 10) {
            spawnBoss(l, run, bossFor(wave), wave);
            return;
        }
        int count;
        if (pos == 5) {
            spawnMini(l, run, miniFor(wave), wave);
            count = 2 + t + extra * CREW_EXTRA_GUARDS;
        } else count = 3 + (pos - 1) / 2 + t + extra * CREW_EXTRA_GUARDS;
        count = Math.min(GUARD_CAP, count);
        List<Kind> pool = new ArrayList<>(List.of(Kind.THRALL, Kind.BLINK_ARCHER));
        if (pos >= 2 || t > 0) pool.add(Kind.RIFTWING);
        if (pos >= 3 || t > 0) pool.add(Kind.VOID_GUST);
        if (pos >= 4 || t > 0) pool.add(Kind.CHORUS_BOMBER);
        if (pos >= 6 || t > 0) pool.add(Kind.SHULKER_SENTRY);
        if (pos >= 7 || t > 0) pool.add(Kind.WATCHER);
        int golems = (pos >= 8 || t > 0) ? (t >= 2 ? 2 : 1) : 0;
        for (int i = 0; i < count; i++) {
            Kind k;
            if (golems > 0 && RNG.nextInt(count) < 2) {
                k = Kind.OBSIDIAN_GOLEM;
                golems--;
            } else k = pool.get(RNG.nextInt(pool.size()));
            guard(l, run, k, wave);
        }
        if (pos == 3 || pos == 7) wardenCrystal(l, run);
    }

    static void guard(ServerLevel l, EndExpedition.Run run, Kind k, int wave) {
        BlockPos g = ground(l, run, 14, EndIsland.R - 8, 10);
        double x = g.getX() + 0.5, y = g.getY(), z = g.getZ() + 0.5;
        Mob m;
        switch (k) {
            case THRALL -> {
                m = make(l, run, EntityTypes.ZOMBIE, k, x, y, z);
                if (m == null) return;
                m.setBaby(false);
                equip(m, EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET));
                equip(m, EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_SWORD));
            }
            case BLINK_ARCHER -> {
                m = make(l, run, EntityTypes.SKELETON, k, x, y, z);
                if (m == null) return;
                ItemStack bow = new ItemStack(Items.BOW);
                if (tier(wave) > 0) ExpeditionLoot.enchant(l, bow, Enchantments.POWER, Math.min(5, tier(wave) * 2));
                equip(m, EquipmentSlot.MAINHAND, bow);
            }
            case RIFTWING -> {
                m = make(l, run, EntityTypes.PHANTOM, k, x, y + 10 + RNG.nextInt(6), z);
                if (m == null) return;
            }
            case VOID_GUST -> {
                m = make(l, run, EntityTypes.BREEZE, k, x, y, z);
                if (m == null) return;
            }
            case CHORUS_BOMBER -> {
                m = make(l, run, EntityTypes.CREEPER, k, x, y, z);
                if (m == null) return;
            }
            case SHULKER_SENTRY -> {
                if (RNG.nextBoolean()) {
                    int[] p = EndIsland.PILLARS.get(RNG.nextInt(EndIsland.PILLARS.size()));
                    x = p[0] + 0.5;
                    y = p[3] + 1;
                    z = p[1] + 0.5;
                }
                m = make(l, run, EntityTypes.SHULKER, k, x, y, z);
                if (m == null) return;
            }
            case WATCHER -> {
                m = make(l, run, EntityTypes.GUARDIAN, k, x, y, z);
                if (m == null) return;
            }
            case OBSIDIAN_GOLEM -> {
                m = make(l, run, EntityTypes.ZOMBIE, k, x, y, z);
                if (m == null) return;
                m.setBaby(false);
                Item[] armor = {Items.NETHERITE_HELMET, Items.NETHERITE_CHESTPLATE, Items.NETHERITE_LEGGINGS, Items.NETHERITE_BOOTS};
                EquipmentSlot[] slots = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};
                for (int i = 0; i < 4; i++) equip(m, slots[i], new ItemStack(armor[i]));
                base(m, Attributes.SCALE, 1.8);
                base(m, Attributes.KNOCKBACK_RESISTANCE, 1.0);
                base(m, Attributes.MOVEMENT_SPEED, 0.17);
                base(m, Attributes.MAX_HEALTH, 60);
                base(m, Attributes.ATTACK_DAMAGE, 8);
            }
            case SHADE -> {
                m = make(l, run, EntityTypes.ENDERMAN, k, x, y, z);
                if (m == null) return;
            }
            default -> {
                return;
            }
        }
        int extra = run.crewSize - 1;
        finish(l, run, m, healthFactor(wave) * (1 + CREW_HEALTH * extra), damageFactor(wave) * (1 + CREW_DAMAGE * extra));
    }

    private static void spawnMini(ServerLevel l, EndExpedition.Run run, Kind k, int wave) {
        BlockPos g = ground(l, run, 16, 32, 12);
        double x = g.getX() + 0.5, y = g.getY(), z = g.getZ() + 0.5;
        double hp;
        Mob m;
        switch (k) {
            case MATRIARCH -> {
                m = make(l, run, EntityTypes.SHULKER, k, x, y, z);
                if (m == null) return;
                base(m, Attributes.SCALE, 3.0);
                hp = MATRIARCH_HP;
            }
            case HERALD -> {
                m = make(l, run, EntityTypes.ENDERMAN, k, x, y, z);
                if (m == null) return;
                base(m, Attributes.SCALE, 2.5);
                base(m, Attributes.ATTACK_DAMAGE, 10);
                base(m, Attributes.KNOCKBACK_RESISTANCE, 0.6);
                hp = HERALD_HP;
            }
            default -> {
                m = make(l, run, EntityTypes.BREEZE, k, x, y, z);
                if (m == null) return;
                base(m, Attributes.SCALE, 3.0);
                hp = TEMPEST_HP;
            }
        }
        bossHealth(m, hp, wave, run);
        finish(l, run, m, 1.0, damageFactor(wave));
        run.bossId = m.getUUID();
        run.bossKind = k;
        run.bossPhase = 0;
    }

    private static void spawnBoss(ServerLevel l, EndExpedition.Run run, Kind k, int wave) {
        Mob m;
        switch (k) {
            case DRAGON -> {
                m = make(l, run, EntityTypes.ENDER_DRAGON, k, 0.5, EndIsland.Y0 + 24, 0.5);
                if (m == null) return;
                bossHealth(m, DRAGON_HP, wave, run);
                l.addFreshEntity(m);
                // A dragon made by the game starts out hovering in place: phase 0 makes it fly its circles.
                String cmd = "data merge entity " + m.getUUID() + " {DragonPhase:0}";
                Safe.run("end dragon phase", () -> l.getServer().getCommands().performPrefixedCommand(l.getServer().createCommandSourceStack().withSuppressedOutput(), cmd));
                for (int[] p : EndIsland.PILLARS) {
                    Entity c = crystal(l, p);
                    if (c != null) run.dragonCrystals.add(c.getUUID());
                }
            }
            case HOLLOW_KING -> {
                BlockPos g = ground(l, run, 16, 28, 12);
                m = make(l, run, EntityTypes.WITHER_SKELETON, k, g.getX() + 0.5, g.getY(), g.getZ() + 0.5);
                if (m == null) return;
                Item[] armor = {Items.NETHERITE_HELMET, Items.NETHERITE_CHESTPLATE, Items.NETHERITE_LEGGINGS, Items.NETHERITE_BOOTS};
                EquipmentSlot[] slots = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};
                for (int i = 0; i < 4; i++) equip(m, slots[i], new ItemStack(armor[i]));
                ItemStack sword = new ItemStack(Items.NETHERITE_SWORD);
                ExpeditionLoot.enchant(l, sword, Enchantments.SHARPNESS, 3);
                equip(m, EquipmentSlot.MAINHAND, sword);
                base(m, Attributes.SCALE, 2.2);
                base(m, Attributes.KNOCKBACK_RESISTANCE, 0.8);
                bossHealth(m, KING_HP, wave, run);
                finish(l, run, m, 1.0, damageFactor(wave));
            }
            default -> {
                m = make(l, run, EntityTypes.ELDER_GUARDIAN, k, 0.5, EndIsland.podiumTop() + 3, 0.5);
                if (m == null) return;
                m.setNoAi(true);
                m.setNoGravity(true);
                bossHealth(m, EYE_HP, wave, run);
                finish(l, run, m, 1.0, damageFactor(wave));
            }
        }
        run.bossId = m.getUUID();
        run.bossKind = k;
        run.bossPhase = 0;
    }

    /** An end crystal on a pillar top (on its bedrock). */
    private static Entity crystal(ServerLevel l, int[] pillar) {
        Entity c = EntityTypes.END_CRYSTAL.create(l, EntitySpawnReason.EVENT);
        if (c == null) return null;
        c.snapTo(pillar[0] + 0.5, pillar[3] + 1, pillar[1] + 0.5, 0f, 0f);
        c.addTag(CRYSTAL_TAG);
        l.addFreshEntity(c);
        return c;
    }

    private static void wardenCrystal(ServerLevel l, EndExpedition.Run run) {
        List<int[]> open = new ArrayList<>();
        for (int[] p : EndIsland.PILLARS) if (!EndIsland.caged(p)) open.add(p);
        int[] p = open.get(RNG.nextInt(open.size()));
        Entity c = crystal(l, p);
        if (c == null) return;
        run.wardenCrystals.add(c.getUUID());
        for (ServerPlayer pl : EndExpedition.inside(run))
            Lobby.msg(pl, "§b§lCrystal Warden! §7An end crystal on a pillar is healing every guard - §fbreak it §7(shoot it or pearl up).");
    }

    private static Mob make(ServerLevel l, EndExpedition.Run run, EntityType<? extends Mob> type, Kind k, double x, double y, double z) {
        Mob m = type.create(l, EntitySpawnReason.EVENT);
        if (m == null) {
            Skyserver.LOGGER.warn("[End] couldn't create {}", k);
            return null;
        }
        m.snapTo(x, y, z, RNG.nextFloat() * 360f, 0f);
        m.setPersistenceRequired();
        m.addTag(GUARD_TAG);
        m.setCustomName(Component.literal(k.display));
        m.setCustomNameVisible(k.mini() || k.boss() || k == Kind.OBSIDIAN_GOLEM);
        if (k != Kind.DRAGON) m.addEffect(new MobEffectInstance(MobEffects.FIRE_RESISTANCE, 20 * 60 * 60 * 6, 0, false, false));
        KIND.put(m.getUUID(), k);
        run.mobs.add(m.getUUID());
        if (k == Kind.CHORUS_BOMBER) FUSE.put(m.getUUID(), 0);
        return m;
    }

    private static void finish(ServerLevel l, EndExpedition.Run run, Mob m, double hp, double dmg) {
        scale(m, Attributes.MAX_HEALTH, "end_health", hp);
        scale(m, Attributes.ATTACK_DAMAGE, "end_damage", dmg);
        m.setHealth(m.getMaxHealth());
        ServerPlayer t = EndExpedition.nearest(run, m.position());
        if (t != null) m.setTarget(t);
        l.addFreshEntity(m);
    }

    private static void bossHealth(Mob m, double baseHp, int wave, EndExpedition.Run run) {
        double hp = baseHp * (1 + TIER_HEALTH * tier(wave)) * BOSS_CREW_HP[Math.max(0, Math.min(2, run.crewSize - 1))];
        base(m, Attributes.MAX_HEALTH, hp);
        m.setHealth((float) hp);
    }

    private static void equip(Mob m, EquipmentSlot slot, ItemStack s) {
        m.setItemSlot(slot, s);
        m.setDropChance(slot, 0f);
    }

    private static void base(Mob m, Holder<Attribute> attr, double v) {
        AttributeInstance a = m.getAttribute(attr);
        if (a != null) a.setBaseValue(v);
    }

    private static void scale(Mob m, Holder<Attribute> attr, String id, double factor) {
        AttributeInstance a = m.getAttribute(attr);
        if (a == null || Math.abs(factor - 1.0) < 0.001) return;
        a.addPermanentModifier(new AttributeModifier(net.minecraft.resources.Identifier.fromNamespaceAndPath(Skyserver.MOD_ID, id), factor - 1.0,
                AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
    }

    /** A random spot on the island's surface between minR and maxR from the middle, at least `away` from every player. */
    static BlockPos ground(ServerLevel l, EndExpedition.Run run, double minR, double maxR, double away) {
        List<ServerPlayer> ps = run == null ? List.of() : EndExpedition.inside(run);
        for (int tries = 0; tries < 60; tries++) {
            double a = RNG.nextDouble() * Math.PI * 2, d = minR + RNG.nextDouble() * Math.max(1, maxR - minR);
            int x = (int) Math.round(Math.cos(a) * d), z = (int) Math.round(Math.sin(a) * d);
            if (!EndIsland.onIsland(x, z) || EndIsland.onPad(x, z, 2) || nearPillar(x, z)) continue;
            int y = l.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            if (y > EndIsland.Y0 + 4 || y < EndIsland.Y0 - 12) continue;
            boolean ok = true;
            for (ServerPlayer p : ps) if (tries < 40 && p.distanceToSqr(x, y, z) < away * away) ok = false;
            if (ok) return new BlockPos(x, y, z);
        }
        return new BlockPos(0, EndIsland.Y0 + 1, -20);
    }

    private static boolean nearPillar(int x, int z) {
        for (int[] p : EndIsland.PILLARS) {
            int dx = x - p[0], dz = z - p[1];
            if (dx * dx + dz * dz <= (p[2] + 2) * (p[2] + 2)) return true;
        }
        return false;
    }

    // =====================================================================
    // Every second: leashes, targets, special attacks, the Crystal Warden
    // =====================================================================

    static void second(ServerLevel l, EndExpedition.Run run, long sec) {
        List<ServerPlayer> ps = EndExpedition.fighters(run);
        double dmg = damageFactor(run.wave);
        int left = 0;
        for (UUID id : run.mobs) {
            Entity e = l.getEntity(id);
            if (e instanceof Mob m && m.isAlive()) left++;
        }
        for (UUID id : new ArrayList<>(run.mobs)) {
            if (!(l.getEntity(id) instanceof Mob m) || !m.isAlive()) continue;
            Kind k = KIND.get(id);
            if (k == null) continue;
            // Back onto the island if it wandered off (or fell).
            if (k != Kind.DRAGON && (horizontal(m.position()) > EndIsland.R + 12 || m.getY() < EndIsland.Y0 - 25)) {
                BlockPos g = ground(l, run, 10, EndIsland.R - 10, 6);
                m.teleportTo(g.getX() + 0.5, g.getY() + (k == Kind.RIFTWING ? 10 : 0), g.getZ() + 0.5);
                m.setDeltaMovement(0, 0, 0);
            }
            LivingEntity tgt = m.getTarget();
            if (k != Kind.DRAGON && !ps.isEmpty() && (tgt == null || !tgt.isAlive() || (tgt instanceof ServerPlayer sp && !ps.contains(sp)))) {
                ServerPlayer n = EndExpedition.nearest(run, m.position());
                if (n != null) m.setTarget(n);
            }
            if (left <= 3 && sec - run.waveStartSec >= 20 && !k.boss()) m.setGlowingTag(true);
            ability(l, run, m, k, sec, ps, dmg);
        }
        // Crystal Warden: heals every guard of the wave while it stands.
        for (UUID cid : new ArrayList<>(run.wardenCrystals)) {
            Entity c = l.getEntity(cid);
            if (c == null || !c.isAlive()) {
                run.wardenCrystals.remove(cid);
                for (ServerPlayer p : EndExpedition.inside(run)) Lobby.msg(p, "§aThe warden crystal is down! §7The guards stop healing.");
                continue;
            }
            for (UUID id : run.mobs) {
                if (l.getEntity(id) instanceof LivingEntity le && le.isAlive() && le.getHealth() < le.getMaxHealth()) {
                    le.heal(CRYSTAL_HEAL);
                    l.sendParticles(ParticleTypes.END_ROD, le.getX(), le.getY() + 1.2, le.getZ(), 2, 0.3, 0.4, 0.3, 0);
                }
            }
            l.sendParticles(ParticleTypes.END_ROD, c.getX(), c.getY() + 1, c.getZ(), 10, 0.3, 1.0, 0.3, 0.02);
            for (ServerPlayer p : ps) Announce.actionBar(p, "§bThe warden crystal heals the guards! §fBreak it §7(on a pillar)");
        }
    }

    private static double horizontal(Vec3 v) {
        return Math.sqrt(v.x * v.x + v.z * v.z);
    }

    private static void ability(ServerLevel l, EndExpedition.Run run, Mob m, Kind k, long sec, List<ServerPlayer> ps, double dmg) {
        if (ps.isEmpty()) return;
        long t = sec - run.waveStartSec;
        switch (k) {
            case VOID_GUST -> {
                if (t % 6 != 3) return;
                boolean any = false;
                for (ServerPlayer p : ps) {
                    if (p.distanceToSqr(m) > 16) continue;
                    push(p, away(m.position(), p.position(), 1.1, 0.35));
                    any = true;
                }
                if (any) l.sendParticles(ParticleTypes.GUST, m.getX(), m.getY() + 0.5, m.getZ(), 3, 1, 0.3, 1, 0);
            }
            case OBSIDIAN_GOLEM -> {
                if (t % 6 != 0) return;
                boolean near = false;
                for (ServerPlayer p : ps) if (p.distanceToSqr(m) <= 16) near = true;
                if (!near) return;
                l.playSound(null, m.blockPosition(), SoundEvents.DRAGON_FIREBALL_EXPLODE, SoundSource.HOSTILE, 1.2f, 0.6f);
                l.sendParticles(ParticleTypes.EXPLOSION, m.getX(), m.getY() + 0.3, m.getZ(), 4, 1.5, 0.2, 1.5, 0);
                for (ServerPlayer p : ps) {
                    if (p.distanceToSqr(m) > 16) continue;
                    p.hurtServer(l, l.damageSources().mobAttack(m), (float) (6 * dmg));
                    push(p, away(m.position(), p.position(), 0.6, 0.7));
                }
                smashPlaced(l, run, m.blockPosition(), 5, Integer.MAX_VALUE);
            }
            case WATCHER -> {
                if (t % 4 != 1) return;
                ServerPlayer p = EndExpedition.nearest(run, m.position());
                if (p == null || p.distanceToSqr(m) > 16 * 16 || !m.hasLineOfSight(p)) return;
                beam(l, run, m, p, 20, (float) (4 * dmg), 1.2);
            }
            case MATRIARCH -> {
                if (t % 10 == 5) {
                    l.playSound(null, m.blockPosition(), SoundEvents.SHULKER_SHOOT, SoundSource.HOSTILE, 2f, 0.5f);
                    for (ServerPlayer p : ps) {
                        if (p.distanceToSqr(m) > 20 * 20) continue;
                        p.addEffect(new MobEffectInstance(MobEffects.LEVITATION, 40, 1));
                        Announce.actionBar(p, "§5The Matriarch lifts you! §7Don't drift over the edge.");
                    }
                }
                if (t % 20 == 10 && adds(l, run, Kind.SHULKER_SENTRY) < 4) {
                    for (int i = 0; i < 2; i++) guard(l, run, Kind.SHULKER_SENTRY, run.wave);
                    for (ServerPlayer p : ps) Lobby.msg(p, "§5The Matriarch calls her sentries!");
                }
            }
            case HERALD -> {
                if (t % 8 == 4) {
                    ServerPlayer p = ps.get(RNG.nextInt(ps.size()));
                    if (p.distanceToSqr(m) <= 30 * 30) {
                        swap(l, p, m);
                        Announce.actionBar(p, "§8The Herald swapped places with you!");
                    }
                }
                if (t % 6 == 0) {
                    List<ServerPlayer> targets = new ArrayList<>(ps);
                    Collections.shuffle(targets, RNG);
                    for (int i = 0; i < Math.min(2, targets.size()); i++) rift(l, run, targets.get(i).position(), (float) (6 * dmg));
                }
            }
            case TEMPEST -> {
                if (t % 7 == 2) {
                    List<ServerPlayer> pulled = new ArrayList<>();
                    for (ServerPlayer p : ps) {
                        if (p.distanceToSqr(m) > 18 * 18) continue;
                        Vec3 v = m.position().subtract(p.position()).normalize().scale(1.2);
                        push(p, new Vec3(v.x, 0.25, v.z));
                        pulled.add(p);
                        Announce.actionBar(p, "§fThe Tempest pulls you in... §7get ready to be blown away!");
                    }
                    l.playSound(null, m.blockPosition(), SoundEvents.BLAZE_SHOOT, SoundSource.HOSTILE, 2f, 0.4f);
                    Lobby.runLaterTicks(30, () -> Safe.run("end tempest blast", () -> {
                        if (!m.isAlive() || run.over) return;
                        l.sendParticles(ParticleTypes.GUST_EMITTER_LARGE, m.getX(), m.getY() + 1, m.getZ(), 1, 0, 0, 0, 0);
                        for (ServerPlayer p : EndExpedition.fighters(run)) {
                            if (p.distanceToSqr(m) > 36) continue;
                            p.hurtServer(l, l.damageSources().mobAttack(m), (float) (4 * dmg));
                            push(p, away(m.position(), p.position(), 1.8, 0.5));
                        }
                    }));
                }
            }
            case HOLLOW_KING -> {
                float share = m.getHealth() / m.getMaxHealth();
                if ((run.bossPhase == 0 && share < 0.66f) || (run.bossPhase == 1 && share < 0.33f)) {
                    run.bossPhase++;
                    for (int i = 0; i < 3; i++) guard(l, run, Kind.SHADE, run.wave);
                    for (ServerPlayer p : ps) Announce.title(p, "§8§lTHE HOLLOW KING", "§7calls his shades!", 5, 40, 10);
                    if (run.bossPhase == 2) {
                        m.addEffect(new MobEffectInstance(MobEffects.SPEED, 20 * 60 * 30, 1, false, false));
                        m.addEffect(new MobEffectInstance(MobEffects.STRENGTH, 20 * 60 * 30, 0, false, false));
                    }
                }
                if (t % 8 == 3 && smashPlaced(l, run, m.blockPosition(), 24, 4) > 0)
                    for (ServerPlayer p : ps) Announce.actionBar(p, "§8The Hollow King steals the blocks you placed!");
            }
            case THE_EYE -> {
                m.teleportTo(0.5, EndIsland.podiumTop() + 3, 0.5);
                ServerPlayer near = EndExpedition.nearest(run, m.position());
                if (near != null) m.lookAt(net.minecraft.commands.arguments.EntityAnchorArgument.Anchor.EYES, near.getEyePosition());
                float share = m.getHealth() / m.getMaxHealth();
                int quarter = (int) ((1 - share) * 4);
                if (quarter > run.bossPhase && quarter < 4) {
                    run.bossPhase = quarter;
                    for (int i = 0; i < 2; i++) guard(l, run, Kind.WATCHER, run.wave);
                    for (ServerPlayer p : ps) Lobby.msg(p, "§3The Eye calls its Watchers!");
                }
                if (t % 5 == 2) {
                    List<ServerPlayer> seen = new ArrayList<>();
                    for (ServerPlayer p : ps) if (p.distanceToSqr(m) <= 40 * 40 && m.hasLineOfSight(p)) seen.add(p);
                    if (!seen.isEmpty()) beam(l, run, m, seen.get(RNG.nextInt(seen.size())), 25, (float) (8 * dmg), 1.5);
                }
                if (t % 30 == 15) {
                    for (ServerPlayer p : EndExpedition.inside(run)) {
                        p.addEffect(new MobEffectInstance(MobEffects.MINING_FATIGUE, 200, 1));
                        Announce.actionBar(p, "§3The Eye's gaze slows your hands...");
                    }
                }
            }
            default -> {
            }
        }
    }

    private static int adds(ServerLevel l, EndExpedition.Run run, Kind k) {
        int n = 0;
        for (UUID id : run.mobs) if (KIND.get(id) == k && l.getEntity(id) instanceof Mob m && m.isAlive()) n++;
        return n;
    }

    /** A warned beam from a mob to where the player stood: after `warn` ticks it hurts everyone near that line. */
    private static void beam(ServerLevel l, EndExpedition.Run run, Mob m, ServerPlayer target, int warn, float damage, double width) {
        Vec3 from = m.getEyePosition(), to = target.position().add(0, 1, 0);
        for (int i = 0; i < warn; i += 5) {
            Lobby.runLaterTicks(i, () -> Safe.run("end beam warn", () -> line(l, from, to, ParticleTypes.END_ROD)));
        }
        l.playSound(null, m.blockPosition(), SoundEvents.BEACON_ACTIVATE, SoundSource.HOSTILE, 1f, 1.8f);
        Lobby.runLaterTicks(warn, () -> Safe.run("end beam", () -> {
            if (!m.isAlive() || run.over) return;
            line(l, from, to, ParticleTypes.ELECTRIC_SPARK);
            l.playSound(null, BlockPos.containing(to), SoundEvents.GUARDIAN_ATTACK, SoundSource.HOSTILE, 1.5f, 1f);
            Vec3 dir = to.subtract(from);
            double len = dir.length();
            Vec3 end = from.add(dir.normalize().scale(len + 6));
            for (ServerPlayer p : EndExpedition.fighters(run)) {
                if (segmentDistance(p.position().add(0, 1, 0), from, end) > width) continue;
                p.hurtServer(l, l.damageSources().magic(), damage);
            }
        }));
    }

    private static void line(ServerLevel l, Vec3 a, Vec3 b, net.minecraft.core.particles.SimpleParticleType type) {
        Vec3 d = b.subtract(a);
        int steps = (int) Math.max(2, d.length() * 2);
        for (int i = 0; i <= steps; i++) {
            Vec3 p = a.add(d.scale(i / (double) steps));
            l.sendParticles(type, p.x, p.y, p.z, 1, 0, 0, 0, 0);
        }
    }

    private static double segmentDistance(Vec3 p, Vec3 a, Vec3 b) {
        Vec3 ab = b.subtract(a);
        double t = Math.max(0, Math.min(1, p.subtract(a).dot(ab) / Math.max(1e-6, ab.lengthSqr())));
        return p.distanceTo(a.add(ab.scale(t)));
    }

    /** A rift: marked for 1.5 seconds, then it hurts and blinds anyone still standing in it. */
    private static void rift(ServerLevel l, EndExpedition.Run run, Vec3 at, float damage) {
        for (int i = 0; i < 30; i += 5) {
            Lobby.runLaterTicks(i, () -> Safe.run("end rift warn", () ->
                    l.sendParticles(ParticleTypes.REVERSE_PORTAL, at.x, at.y + 0.1, at.z, 25, 1.2, 0.05, 1.2, 0.02)));
        }
        Lobby.runLaterTicks(30, () -> Safe.run("end rift", () -> {
            if (run.over) return;
            l.sendParticles(ParticleTypes.PORTAL, at.x, at.y + 0.5, at.z, 60, 1, 0.5, 1, 0.5);
            l.playSound(null, BlockPos.containing(at), SoundEvents.ENDERMAN_TELEPORT, SoundSource.HOSTILE, 1.5f, 0.5f);
            for (ServerPlayer p : EndExpedition.fighters(run)) {
                if (Math.hypot(p.getX() - at.x, p.getZ() - at.z) > 2.5 || Math.abs(p.getY() - at.y) > 2.5) continue;
                p.hurtServer(l, l.damageSources().magic(), damage);
                p.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, 40, 0));
            }
        }));
    }

    /** Breaks blocks players placed within `radius` (at most `max`). Returns how many. */
    static int smashPlaced(ServerLevel l, EndExpedition.Run run, BlockPos at, int radius, int max) {
        int n = 0;
        for (Iterator<Long> it = run.placed.iterator(); it.hasNext() && n < max; ) {
            BlockPos p = BlockPos.of(it.next());
            if (p.distSqr(at) > radius * radius) continue;
            it.remove();
            if (l.getBlockState(p).isAir()) continue;
            l.setBlock(p, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            l.sendParticles(ParticleTypes.PORTAL, p.getX() + 0.5, p.getY() + 0.5, p.getZ() + 0.5, 12, 0.3, 0.3, 0.3, 0.3);
            n++;
        }
        return n;
    }

    // =====================================================================
    // Every tick: Chorus Bombers pop before a vanilla creeper could explode
    // =====================================================================

    static void fastTick(ServerLevel l, EndExpedition.Run run) {
        if (FUSE.isEmpty()) return;
        for (Iterator<Map.Entry<UUID, Integer>> it = FUSE.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, Integer> e = it.next();
            if (!(l.getEntity(e.getKey()) instanceof Mob m) || !m.isAlive()) {
                if (l.getEntity(e.getKey()) == null || !run.mobs.contains(e.getKey())) it.remove();
                continue;
            }
            ServerPlayer p = EndExpedition.nearest(run, m.position());
            double d = p == null ? 99 : Math.sqrt(p.distanceToSqr(m));
            int f = e.getValue();
            // Vanilla starts fusing under 3 blocks and keeps going until 7: we pop at 20 ticks (vanilla needs 30).
            if (f == 0 && d < 3) f = 1;
            else if (f > 0 && d < 7) f++;
            else if (d >= 7) f = 0;
            e.setValue(f);
            if (f >= 20) {
                it.remove();
                popBomber(l, run, m);
            }
        }
    }

    private static void popBomber(ServerLevel l, EndExpedition.Run run, Mob m) {
        Vec3 at = m.position();
        double dmg = damageFactor(run.wave);
        l.sendParticles(ParticleTypes.EXPLOSION, at.x, at.y + 0.5, at.z, 3, 0.6, 0.4, 0.6, 0);
        l.sendParticles(ParticleTypes.PORTAL, at.x, at.y + 0.5, at.z, 80, 1.5, 1, 1.5, 0.5);
        l.playSound(null, m.blockPosition(), SoundEvents.DRAGON_FIREBALL_EXPLODE, SoundSource.HOSTILE, 1.3f, 1.2f);
        m.discard();
        for (ServerPlayer p : EndExpedition.fighters(run)) {
            if (p.distanceToSqr(at.x, at.y, at.z) > 9) continue;
            p.hurtServer(l, l.damageSources().magic(), (float) (6 * dmg));
            BlockPos g = ground(l, run, 8, EndIsland.R - 6, 0);
            p.teleportTo(g.getX() + 0.5, g.getY(), g.getZ() + 0.5);
            l.playSound(null, g, SoundEvents.ENDERMAN_TELEPORT, SoundSource.PLAYERS, 1f, 1f);
            Announce.actionBar(p, "§dThe Chorus Bomber threw you across the island!");
        }
    }

    // =====================================================================
    // Hit effects (called from EndExpedition's damage hooks)
    // =====================================================================

    /** A guard (or its arrow) hurt a player. */
    static void hitPlayer(ServerLevel l, ServerPlayer p, Mob attacker, Kind k) {
        switch (k) {
            case THRALL -> {
                if (RNG.nextInt(100) < 30) p.addEffect(new MobEffectInstance(MobEffects.LEVITATION, 20, 0));
            }
            case BLINK_ARCHER -> {
                if (RNG.nextInt(100) < 20 && attacker.distanceToSqr(p) < 40 * 40) {
                    swap(l, p, attacker);
                    Announce.actionBar(p, "§dThe Blink Archer's arrow swapped your places!");
                }
            }
            case RIFTWING -> push(p, away(attacker.position(), p.position(), 1.2, 0.4));
            default -> {
            }
        }
    }

    /** A player hurt one of these guards. */
    static void guardHurt(ServerLevel l, EndExpedition.Run run, Mob m, Kind k) {
        if (k == Kind.BLINK_ARCHER && RNG.nextInt(100) < 35) {
            BlockPos g = ground(l, run, Math.max(8, horizontal(m.position()) - 10), Math.min(EndIsland.R - 8, horizontal(m.position()) + 10), 6);
            l.sendParticles(ParticleTypes.PORTAL, m.getX(), m.getY() + 1, m.getZ(), 30, 0.4, 0.8, 0.4, 0.3);
            m.teleportTo(g.getX() + 0.5, g.getY(), g.getZ() + 0.5);
            l.playSound(null, g, SoundEvents.ENDERMAN_TELEPORT, SoundSource.HOSTILE, 1f, 1.3f);
        }
    }

    private static void swap(ServerLevel l, ServerPlayer p, Mob m) {
        Vec3 a = p.position(), b = m.position();
        p.teleportTo(b.x, b.y, b.z);
        m.teleportTo(a.x, a.y, a.z);
        l.playSound(null, p.blockPosition(), SoundEvents.ENDERMAN_TELEPORT, SoundSource.HOSTILE, 1f, 1f);
        l.sendParticles(ParticleTypes.PORTAL, b.x, b.y + 1, b.z, 30, 0.4, 0.8, 0.4, 0.3);
    }

    private static Vec3 away(Vec3 from, Vec3 to, double horizontal, double up) {
        Vec3 d = new Vec3(to.x - from.x, 0, to.z - from.z);
        if (d.lengthSqr() < 1e-4) d = new Vec3(RNG.nextDouble() - 0.5, 0, RNG.nextDouble() - 0.5);
        d = d.normalize().scale(horizontal);
        return new Vec3(d.x, up, d.z);
    }

    /** Sends a player's new speed to their game (26.3: no hurtMarked). */
    static void push(ServerPlayer p, Vec3 v) {
        p.setDeltaMovement(v);
        p.syncVelocity = true;
        if (!(p instanceof com.aidan.skyserver.bots.BotPlayer))
            p.connection.send(new net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket(p));
    }

    /** Forget everything about a finished trip. */
    static void clear() {
        KIND.clear();
        FUSE.clear();
    }
}
