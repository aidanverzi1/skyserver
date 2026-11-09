package com.aidan.skyserver.expedition;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.BossEvent;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.projectile.hurtingprojectile.SmallFireball;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.entity.projectile.arrow.Arrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.*;

/**
 * The Nether Expedition's own bosses, not vanilla mobs. Each is a body made of display entities that
 * follows a hidden, invisible mob - the mob does the walking and takes the hits; the body is what
 * players see.
 *
 * 5.11 (much harder):
 *  - The body is built from ITEM displays (block items render as full blocks). 26.3 rejected the old
 *    block_display "block_state" text, so the 5.10 body never appeared.
 *  - Health {600, 900, 1300, 1900}, +35% for every extra player in the arena when it wakes.
 *  - No hit counts for more than 10 damage (Sharpness spam can't melt it) - except in its weak window.
 *  - Weak window: after a ground slam (Colossus) or after charging into a wall (Tyrant) it's stunned for
 *    3 seconds and glows: hits count double (up to 20).
 *  - Erupting floor: glowing, smoking spots appear under the players, then blow up 1.5-2 s later.
 *  - Help keeps coming the whole fight (up to 2 + difficulty helpers alive at once).
 *  - Enrage: after 5 minutes it gets much faster.
 *
 *  Cinder Colossus (Fortress): slam (knocks you away), fireball volleys, eruptions, and a ring of fire
 *    that rolls out across the floor - JUMP over it. Wither skeletons at 2/3 and 1/3 health, plus
 *    wither skeletons / blazes all fight.
 *  Gilded Tyrant (Bastion): charges (hitting a wall stuns it), gold-shard fans (shields block them),
 *    gold eruptions. At 2/3 and 1/3 health it raises a shield: break its 3 gold pillars - each guarded -
 *    within 30 seconds or every broken pillar rises again.
 *
 * Three phases each (full, below 2/3, below 1/3): attacks come faster as it gets hurt.
 */
final class ExpeditionBoss {
    private ExpeditionBoss() {}

    static final String PART_TAG = "skyserver_bosspart";
    /** Health by difficulty (Easy, Normal, Hard, Nightmare), for one player. */
    static final double[] HP = {600, 900, 1300, 1900};
    /** Extra health for every extra player in the arena (0.35 = +35%). */
    static final double HP_PER_EXTRA_PLAYER = 0.35;
    /** Most damage one hit can do (doubled in the weak window). */
    static final float HIT_CAP = 10f;
    /** Ticks of the weak window, ticks before it enrages, ticks the Tyrant's shield lasts before broken pillars rise again. */
    static final int WEAK_TICKS = 60, ENRAGE_TICKS = 20 * 60 * 5, SHIELD_TICKS = 20 * 30;
    /** Ticks between helpers (by difficulty). */
    static final int[] HELPER_TICKS = {500, 420, 360, 300};
    private static int partCounter = 0;
    /** 5.12: how many failed part summons were logged (capped so the log stays readable). */
    private static int summonWarnings = 0;
    /** 5.12: how often a missing body may be rebuilt in one fight. */
    static final int MAX_REBUILDS = 4;

    /** What the boss needs from the expedition: who's fighting, and calling in help. */
    interface Hooks {
        List<ServerPlayer> fighters();

        void minions(EntityType<? extends Mob> type, int count);

        void minionAt(EntityType<? extends Mob> type, BlockPos at);

        /** Helpers (not the boss) still alive in the arena. */
        int minionsAlive();
    }

    static String name(boolean fortress) {
        return fortress ? "§6§lCinder Colossus" : "§e§lGilded Tyrant";
    }

    static EntityType<? extends Mob> hitboxType(boolean fortress) {
        return fortress ? net.minecraft.world.entity.EntityTypes.RAVAGER : net.minecraft.world.entity.EntityTypes.PIGLIN_BRUTE;
    }

    // =====================================================================

    /** A spot of floor about to erupt. */
    private static final class Eruption {
        final Vec3 at;
        int ticks;

        Eruption(Vec3 at, int ticks) {
            this.at = at;
            this.ticks = ticks;
        }
    }

    static final class Fight {
        final ServerLevel level;
        final boolean colossus;
        final int diff;
        final Mob body;
        final ExpeditionRooms.Room arena;
        final Hooks hooks;
        final List<Display> parts = new ArrayList<>();
        /** Parts that always glow (the Tyrant's crown) - left glowing when the weak window ends. */
        final Set<Display> alwaysGlow = new HashSet<>();
        final List<Display> orbit = new ArrayList<>();
        final ServerBossEvent bar;
        final List<BlockPos> pillarSpots;
        /** Each pillar's blocks, same order as pillarSpots (empty list = broken). */
        final List<List<BlockPos>> pillars = new ArrayList<>();
        final List<Eruption> eruptions = new ArrayList<>();
        int phase = 1, t = 0;
        boolean shielded = false, over = false, enraged = false, glowing = false, hurting = false;
        int cdA = 60, cdB = 100, cdC = 160, cdD = 240, helperCd, action = 0, actionTicks = 0, weak = 0, shieldLeft = 0;
        double lift = 0;
        /** 5.12: times the body was rebuilt after parts went missing. */
        int rebuilds = 0;
        /** The ring of fire: its radius (-1 = none) and who it already burned. */
        double ring = -1;
        final Set<UUID> ringHit = new HashSet<>();
        Vec3 ringAt = Vec3.ZERO;
        Vec3 chargeDir = Vec3.ZERO;
        final Set<UUID> hitThisCharge = new HashSet<>();

        Fight(ServerLevel level, boolean colossus, int diff, Mob body, ExpeditionRooms.Room arena, List<BlockPos> pillarSpots, Hooks hooks) {
            this.level = level;
            this.colossus = colossus;
            this.diff = diff;
            this.body = body;
            this.arena = arena;
            this.pillarSpots = pillarSpots;
            this.hooks = hooks;
            this.helperCd = HELPER_TICKS[diff];
            bar = new ServerBossEvent(UUID.randomUUID(), Component.literal(name(colossus)), colossus ? BossEvent.BossBarColor.RED : BossEvent.BossBarColor.YELLOW,
                    BossEvent.BossBarOverlay.NOTCHED_10);
        }

        boolean isPillar(BlockPos p) {
            for (List<BlockPos> pl : pillars) if (pl.contains(p)) return true;
            return false;
        }

        boolean weak() {
            return weak > 0;
        }

        /**
         * Called after the boss took damage: no hit counts for more than HIT_CAP; in the weak window hits
         * count double (up to twice the cap).
         */
        void afterHurt(float taken) {
            if (over || hurting || taken <= 0 || !body.isAlive()) return;
            float counted = weak() ? Math.min(taken * 2, HIT_CAP * 2) : Math.min(taken, HIT_CAP);
            float delta = counted - taken;
            if (delta < 0) {
                body.heal(-delta);
            } else if (delta > 0) {
                float left = body.getHealth() - delta;
                if (left > 0) body.setHealth(left);
                else {
                    hurting = true;
                    try {
                        body.hurtServer(level, level.damageSources().magic(), 10_000f);
                    } finally {
                        hurting = false;
                    }
                }
            }
            if (weak() && taken > 0)
                level.sendParticles(ParticleTypes.CRIT, body.getX(), body.getY() + 2.5, body.getZ(), 12, 1, 1, 1, 0.3);
        }

        /** A player broke into a gold pillar: the whole pillar crumbles (no drops). */
        void breakPillar(BlockPos p) {
            for (List<BlockPos> pl : pillars) {
                if (!pl.contains(p)) continue;
                for (BlockPos b : pl) {
                    level.sendParticles(ParticleTypes.WAX_ON, b.getX() + 0.5, b.getY() + 0.5, b.getZ() + 0.5, 12, 0.4, 0.4, 0.4, 0.1);
                    level.setBlock(b, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                }
                pl.clear();
                level.playSound(null, p, SoundEvents.GLASS_BREAK, SoundSource.HOSTILE, 1.5f, 0.6f);
                int left = 0;
                for (List<BlockPos> o : pillars) if (!o.isEmpty()) left++;
                for (ServerPlayer f : hooks.fighters())
                    com.aidan.skyserver.lobby.Announce.actionBar(f, left > 0 ? "§6Gold pillar down! §f" + left + " §6left §7(" + (shieldLeft / 20) + "s)" : "§a§lThe Tyrant's shield shatters!");
                if (left == 0) dropShield();
                return;
            }
        }

        // ---- Every tick ----

        void tick() {
            if (over) return;
            if (body.isRemoved() || !body.isAlive()) {
                end();
                return;
            }
            t++;
            List<ServerPlayer> fighters = hooks.fighters();
            // The bar shows for everyone in the arena.
            Set<ServerPlayer> want = new HashSet<>(fighters);
            for (ServerPlayer p : new ArrayList<>(bar.getPlayers())) if (!want.contains(p)) bar.removePlayer(p);
            for (ServerPlayer p : want) if (!bar.getPlayers().contains(p)) bar.addPlayer(p);
            float frac = body.getHealth() / body.getMaxHealth();
            bar.setProgress(Math.max(0f, Math.min(1f, frac)));
            if (phase == 1 && frac <= 2f / 3f) enterPhase(2);
            if (phase == 2 && frac <= 1f / 3f) enterPhase(3);
            if (!enraged && t >= ENRAGE_TICKS) enrage();
            ServerPlayer target = nearest(fighters);
            tickWeak();
            tickRing(fighters);
            tickEruptions(fighters);
            tickHelpers();
            if (t % 40 == 0) checkBody();
            if (weak()) freeze();
            else if (colossus) colossusTick(target, fighters);
            else tyrantTick(target, fighters);
            moveBody();
        }

        /** Sends a player's new speed to their game (26.3: no hurtMarked). */
        private void push(ServerPlayer p) {
            p.syncVelocity = true;
            if (!(p instanceof com.aidan.skyserver.bots.BotPlayer))
                p.connection.send(new net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket(p));
        }

        private ServerPlayer nearest(List<ServerPlayer> list) {
            ServerPlayer best = null;
            double bd = Double.MAX_VALUE;
            for (ServerPlayer p : list) {
                double d = p.distanceToSqr(body);
                if (d < bd && !p.isSpectator() && !p.isCreative()) {
                    bd = d;
                    best = p;
                }
            }
            return best;
        }

        private int speedUp(int ticks) {
            double mul = (phase == 3 ? 0.6 : phase == 2 ? 0.8 : 1.0) * (enraged ? 0.55 : 1.0);
            return Math.max(16, (int) (ticks * mul) - diff * 6);
        }

        private void freeze() {
            body.getNavigation().stop();
            body.setDeltaMovement(0, body.getDeltaMovement().y, 0);
        }

        private void enterPhase(int p) {
            phase = p;
            level.playSound(null, body.blockPosition(), SoundEvents.ENDER_DRAGON_GROWL, SoundSource.HOSTILE, 2f, colossus ? 0.6f : 1.1f);
            level.sendParticles(colossus ? ParticleTypes.LAVA : ParticleTypes.WAX_ON, body.getX(), body.getY() + 2.5, body.getZ(), 60, 1.5, 1.5, 1.5, 0.3);
            AttributeInstance speed = body.getAttribute(Attributes.MOVEMENT_SPEED);
            if (speed != null) speed.setBaseValue(speed.getBaseValue() * 1.12);
            String nm = name(colossus);
            for (ServerPlayer f : hooks.fighters())
                com.aidan.skyserver.lobby.Announce.title(f, nm, p == 2 ? "§7It's getting angry..." : "§c§lFURIOUS!", 5, 40, 10);
            if (colossus) hooks.minions(net.minecraft.world.entity.EntityTypes.WITHER_SKELETON, diff <= 1 ? 2 : diff == 2 ? 3 : 4);
            else raiseShield();
        }

        private void enrage() {
            enraged = true;
            AttributeInstance speed = body.getAttribute(Attributes.MOVEMENT_SPEED);
            if (speed != null) speed.setBaseValue(speed.getBaseValue() * 1.4);
            level.playSound(null, body.blockPosition(), SoundEvents.ENDER_DRAGON_GROWL, SoundSource.HOSTILE, 2.5f, 0.5f);
            level.sendParticles(ParticleTypes.LAVA, body.getX(), body.getY() + 2.5, body.getZ(), 80, 2, 2, 2, 0.4);
            for (ServerPlayer f : hooks.fighters())
                com.aidan.skyserver.lobby.Announce.title(f, "§4§lENRAGED!", "§7You took too long - it's much faster now!", 5, 50, 10);
        }

        // ---- Weak window ----

        private void startWeak(String why) {
            weak = WEAK_TICKS;
            action = 0;
            lift = 0;
            setGlow(true);
            level.playSound(null, body.blockPosition(), SoundEvents.ANVIL_LAND, SoundSource.HOSTILE, 1.5f, 0.4f);
            for (ServerPlayer f : hooks.fighters()) com.aidan.skyserver.lobby.Announce.actionBar(f, "§e§l" + why + " §6Hits count double!");
        }

        private void tickWeak() {
            if (weak <= 0) return;
            weak--;
            if (weak % 4 == 0) level.sendParticles(ParticleTypes.CRIT, body.getX(), body.getY() + 3, body.getZ(), 6, 1.2, 1.2, 1.2, 0.05);
            if (weak == 0) setGlow(false);
        }

        private void setGlow(boolean on) {
            if (glowing == on) return;
            glowing = on;
            for (Display d : parts) if (!alwaysGlow.contains(d)) d.setGlowingTag(on);
        }

        // ---- Help arriving all fight ----

        private void tickHelpers() {
            if (--helperCd > 0) return;
            helperCd = speedUp(HELPER_TICKS[diff]);
            int cap = 2 + diff;
            int alive = hooks.minionsAlive();
            if (alive >= cap) return;
            int n = Math.min(cap - alive, phase == 3 ? 2 : 1);
            EntityType<? extends Mob> type;
            if (colossus) type = (t / 7) % 2 == 0 ? net.minecraft.world.entity.EntityTypes.WITHER_SKELETON : net.minecraft.world.entity.EntityTypes.BLAZE;
            else type = diff == 0 || (t / 7) % 2 == 0 ? net.minecraft.world.entity.EntityTypes.PIGLIN : net.minecraft.world.entity.EntityTypes.PIGLIN_BRUTE;
            hooks.minions(type, n);
            level.playSound(null, body.blockPosition(), colossus ? SoundEvents.BLAZE_SHOOT : SoundEvents.PIGLIN_BRUTE_ANGRY, SoundSource.HOSTILE, 1.5f, 0.6f);
        }

        // ---- Erupting floor (both bosses) ----

        private void startEruptions(List<ServerPlayer> fighters) {
            int per = 1 + (phase >= 2 ? 1 : 0) + diff / 2;
            int warn = Math.max(24, 40 - diff * 4);
            for (ServerPlayer p : fighters) {
                for (int i = 0; i < per; i++) {
                    double ox = i == 0 ? 0 : (body.getRandom().nextDouble() - 0.5) * 6, oz = i == 0 ? 0 : (body.getRandom().nextDouble() - 0.5) * 6;
                    eruptions.add(new Eruption(new Vec3(p.getX() + ox, arena.y0, p.getZ() + oz), warn));
                }
            }
            level.playSound(null, body.blockPosition(), SoundEvents.BLAZE_SHOOT, SoundSource.HOSTILE, 2f, 0.4f);
        }

        private void tickEruptions(List<ServerPlayer> fighters) {
            for (Iterator<Eruption> it = eruptions.iterator(); it.hasNext(); ) {
                Eruption e = it.next();
                e.ticks--;
                Vec3 a = e.at;
                if (e.ticks > 0) {
                    if (e.ticks % 3 == 0) {
                        level.sendParticles(colossus ? ParticleTypes.LAVA : ParticleTypes.WAX_ON, a.x, a.y + 0.1, a.z, 3, 0.7, 0.05, 0.7, 0.01);
                        level.sendParticles(ParticleTypes.SMOKE, a.x, a.y + 0.1, a.z, 6, 0.8, 0.05, 0.8, 0.01);
                        level.sendParticles(colossus ? ParticleTypes.FLAME : ParticleTypes.END_ROD, a.x, a.y + 0.1, a.z, 4, 1.0, 0.02, 1.0, 0.0);
                    }
                    continue;
                }
                it.remove();
                level.sendParticles(ParticleTypes.EXPLOSION, a.x, a.y + 0.5, a.z, 2, 0.4, 0.3, 0.4, 0);
                level.sendParticles(colossus ? ParticleTypes.LAVA : ParticleTypes.WAX_ON, a.x, a.y + 0.5, a.z, 20, 0.8, 0.8, 0.8, 0.3);
                level.playSound(null, BlockPos.containing(a), SoundEvents.DRAGON_FIREBALL_EXPLODE, SoundSource.HOSTILE, 1.2f, 1.2f);
                float dmg = 6 + 2 * diff;
                for (ServerPlayer p : fighters) {
                    double d = Math.hypot(p.getX() - a.x, p.getZ() - a.z);
                    if (d > 2.4 || Math.abs(p.getY() - a.y) > 2.5) continue;
                    p.hurtServer(level, level.damageSources().explosion(body, body), (float) (dmg * (1 - d / 4.0)));
                    if (colossus) p.setRemainingFireTicks(Math.max(p.getRemainingFireTicks(), 60));
                    p.setDeltaMovement(p.getDeltaMovement().x, 0.8, p.getDeltaMovement().z);
                    push(p);
                }
            }
        }

        // ---- Cinder Colossus ----

        private void colossusTick(ServerPlayer target, List<ServerPlayer> fighters) {
            cdA--;
            cdB--;
            cdC--;
            cdD--;
            if (action == 1) { // slam wind-up: rises, then crashes down
                freeze();
                actionTicks--;
                lift = Math.min(1.4, lift + 0.08);
                if (actionTicks % 4 == 0) level.sendParticles(ParticleTypes.FLAME, body.getX(), body.getY() + 1, body.getZ(), 10, 1.5, 0.3, 1.5, 0.02);
                if (actionTicks <= 0) {
                    slam();
                    action = 0;
                    lift = 0;
                    startWeak("It's stuck in the floor - its core is exposed!");
                }
                return;
            }
            if (action == 4) { // ring wind-up: arms raised, flames gather
                freeze();
                actionTicks--;
                lift = Math.min(0.6, lift + 0.05);
                if (actionTicks % 2 == 0) level.sendParticles(ParticleTypes.FLAME, body.getX(), body.getY() + 0.3, body.getZ(), 16, 2.5, 0.1, 2.5, 0.05);
                if (actionTicks <= 0) {
                    action = 0;
                    lift = 0;
                    ring = 1.5;
                    ringHit.clear();
                    ringAt = body.position();
                    level.playSound(null, body.blockPosition(), SoundEvents.DRAGON_FIREBALL_EXPLODE, SoundSource.HOSTILE, 2f, 0.5f);
                }
                return;
            }
            if (target == null) return;
            double dist = target.distanceTo(body);
            if (cdA <= 0 && dist < 7) {
                action = 1;
                actionTicks = phase == 3 ? 14 : 20;
                cdA = speedUp(100);
                level.playSound(null, body.blockPosition(), SoundEvents.RAVAGER_ROAR, SoundSource.HOSTILE, 2f, 0.6f);
                return;
            }
            if (cdD <= 0 && ring < 0 && dist < 20) {
                action = 4;
                actionTicks = 20;
                cdD = speedUp(240);
                level.playSound(null, body.blockPosition(), SoundEvents.BLAZE_SHOOT, SoundSource.HOSTILE, 2f, 0.3f);
                for (ServerPlayer f : fighters) com.aidan.skyserver.lobby.Announce.actionBar(f, "§c§lA ring of fire is coming - JUMP!");
                return;
            }
            if (cdC <= 0) {
                startEruptions(fighters);
                cdC = speedUp(160);
                return;
            }
            if (cdB <= 0 && dist > 4) {
                volley(target);
                cdB = speedUp(80);
            }
        }

        private void slam() {
            double radius = 6.5;
            level.playSound(null, body.blockPosition(), SoundEvents.DRAGON_FIREBALL_EXPLODE, SoundSource.HOSTILE, 2f, 0.6f);
            level.sendParticles(ParticleTypes.EXPLOSION, body.getX(), body.getY() + 0.3, body.getZ(), 8, 2.5, 0.2, 2.5, 0);
            level.sendParticles(ParticleTypes.LAVA, body.getX(), body.getY() + 0.3, body.getZ(), 40, 3, 0.3, 3, 0.2);
            float dmg = 10 + 3 * diff;
            for (ServerPlayer p : hooks.fighters()) {
                double d = p.distanceTo(body);
                if (d > radius || p.getY() > body.getY() + 3) continue;
                float scaled = (float) (dmg * (1 - d / (radius * 1.6)));
                p.hurtServer(level, level.damageSources().mobAttack(body), scaled);
                Vec3 away = p.position().subtract(body.position()).multiply(1, 0, 1);
                if (away.lengthSqr() < 0.01) away = new Vec3(1, 0, 0);
                away = away.normalize().scale(1.6 - d / radius * 0.6);
                p.setDeltaMovement(away.x, 0.65, away.z);
                push(p);
            }
        }

        /** The ring of fire rolls outward along the floor; anyone standing on the floor when it passes burns. */
        private void tickRing(List<ServerPlayer> fighters) {
            if (ring < 0) return;
            ring += 0.55;
            if (ring > 24) {
                ring = -1;
                return;
            }
            if (t % 2 == 0) {
                int pts = (int) (ring * 5);
                for (int i = 0; i < pts; i++) {
                    double a = i * Math.PI * 2 / pts;
                    level.sendParticles(ParticleTypes.FLAME, ringAt.x + Math.cos(a) * ring, ringAt.y + 0.2, ringAt.z + Math.sin(a) * ring, 1, 0, 0.15, 0, 0);
                }
            }
            float dmg = 6 + 2 * diff;
            for (ServerPlayer p : fighters) {
                if (ringHit.contains(p.getUUID())) continue;
                double d = Math.hypot(p.getX() - ringAt.x, p.getZ() - ringAt.z);
                if (Math.abs(d - ring) > 0.9) continue;
                if (!p.onGround() || p.getY() > ringAt.y + 1.2) continue; // jumped it, or up high
                ringHit.add(p.getUUID());
                p.hurtServer(level, level.damageSources().mobAttack(body), dmg);
                p.setRemainingFireTicks(Math.max(p.getRemainingFireTicks(), 80));
                Vec3 away = p.position().subtract(ringAt).multiply(1, 0, 1);
                if (away.lengthSqr() < 0.01) away = new Vec3(1, 0, 0);
                away = away.normalize().scale(0.6);
                p.setDeltaMovement(away.x, 0.45, away.z);
                push(p);
            }
        }

        private void volley(ServerPlayer target) {
            int n = 4 + diff + (phase - 1) + (enraged ? 2 : 0);
            Vec3 from = body.position().add(0, 3.6, 0);
            level.playSound(null, body.blockPosition(), SoundEvents.BLAZE_SHOOT, SoundSource.HOSTILE, 2f, 0.7f);
            for (int i = 0; i < n; i++) {
                Vec3 aim = target.position().add(0, 1, 0).subtract(from);
                aim = aim.add((body.getRandom().nextDouble() - 0.5) * 3, (body.getRandom().nextDouble() - 0.5), (body.getRandom().nextDouble() - 0.5) * 3);
                SmallFireball fb = new SmallFireball(level, body, aim.normalize());
                fb.setPos(from.x, from.y, from.z);
                level.addFreshEntity(fb);
            }
        }

        // ---- Gilded Tyrant ----

        private void tyrantTick(ServerPlayer target, List<ServerPlayer> fighters) {
            cdA--;
            cdB--;
            cdC--;
            if (shielded) {
                freeze();
                if (t % 10 == 0) beams();
                if (--shieldLeft <= 0) rebuildPillars();
                if (cdB <= 0 && target != null) {
                    spray(target);
                    cdB = speedUp(70);
                }
                if (cdC <= 0) {
                    startEruptions(fighters);
                    cdC = speedUp(180);
                }
                return;
            }
            if (action == 2) { // charge wind-up: paws the ground
                freeze();
                actionTicks--;
                if (actionTicks % 3 == 0) level.sendParticles(ParticleTypes.CRIT, body.getX(), body.getY() + 0.2, body.getZ(), 10, 0.6, 0.1, 0.6, 0.1);
                if (actionTicks <= 0) {
                    action = 3;
                    actionTicks = 22;
                    hitThisCharge.clear();
                    level.playSound(null, body.blockPosition(), SoundEvents.RAVAGER_ROAR, SoundSource.HOSTILE, 1.6f, 1.3f);
                }
                return;
            }
            if (action == 3) { // charging
                actionTicks--;
                double speed = 0.85 + 0.1 * diff + (phase == 3 ? 0.15 : 0) + (enraged ? 0.2 : 0);
                body.setDeltaMovement(chargeDir.x * speed, body.getDeltaMovement().y, chargeDir.z * speed);
                body.syncVelocity = true;
                body.setYRot((float) Math.toDegrees(Math.atan2(-chargeDir.x, chargeDir.z)));
                body.yBodyRot = body.getYRot();
                level.sendParticles(ParticleTypes.WAX_ON, body.getX(), body.getY() + 1, body.getZ(), 4, 0.5, 0.5, 0.5, 0.05);
                for (ServerPlayer p : fighters) {
                    if (hitThisCharge.contains(p.getUUID()) || p.distanceTo(body) > 2.4) continue;
                    hitThisCharge.add(p.getUUID());
                    p.hurtServer(level, level.damageSources().mobAttack(body), 11 + 3 * diff);
                    p.setDeltaMovement(chargeDir.x * 1.5, 0.5, chargeDir.z * 1.5);
                    push(p);
                }
                if (actionTicks <= 0 || body.horizontalCollision) {
                    boolean wall = body.horizontalCollision;
                    action = 0;
                    freeze();
                    if (wall) startWeak("It smashed into the wall - it's stunned!");
                }
                return;
            }
            if (target == null) return;
            double dist = target.distanceTo(body);
            if (cdA <= 0 && dist > 4 && dist < 22) {
                action = 2;
                actionTicks = phase == 3 ? 10 : 16;
                Vec3 d = target.position().subtract(body.position()).multiply(1, 0, 1);
                chargeDir = d.lengthSqr() < 0.01 ? new Vec3(1, 0, 0) : d.normalize();
                cdA = speedUp(130);
                level.playSound(null, body.blockPosition(), SoundEvents.PIGLIN_BRUTE_ANGRY, SoundSource.HOSTILE, 2f, 0.6f);
                return;
            }
            if (cdC <= 0) {
                startEruptions(fighters);
                cdC = speedUp(170);
                return;
            }
            if (cdB <= 0) {
                spray(target);
                cdB = speedUp(85);
            }
        }

        /** A fan of gold shards (arrows) toward the target. */
        private void spray(ServerPlayer target) {
            int n = 6 + 2 * diff + (phase - 1) * 2 + (enraged ? 2 : 0);
            Vec3 from = body.position().add(0, 3.2, 0);
            Vec3 aim = target.position().add(0, 1.2, 0).subtract(from);
            double baseYaw = Math.atan2(aim.z, aim.x);
            double horiz = Math.hypot(aim.x, aim.z);
            level.playSound(null, body.blockPosition(), SoundEvents.ARROW_SHOOT, SoundSource.HOSTILE, 2f, 0.6f);
            for (int i = 0; i < n; i++) {
                double spread = Math.toRadians(50) * ((i / (double) Math.max(1, n - 1)) - 0.5);
                double yaw = baseYaw + spread;
                Arrow a = new Arrow(level, body, new ItemStack(Items.ARROW), null);
                a.setPos(from.x, from.y, from.z);
                a.pickup = AbstractArrow.Pickup.DISALLOWED;
                a.setBaseDamage(3 + 0.5 * diff);
                a.shoot(Math.cos(yaw) * horiz, aim.y + horiz * 0.08, Math.sin(yaw) * horiz, 1.7f, 2f);
                level.addFreshEntity(a);
            }
            level.sendParticles(ParticleTypes.WAX_ON, from.x, from.y, from.z, 20, 0.6, 0.4, 0.6, 0.2);
        }

        private void buildPillar(int i) {
            BlockPos s = pillarSpots.get(i);
            List<BlockPos> pl = pillars.get(i);
            pl.clear();
            for (int y = 0; y < 3; y++) {
                BlockPos b = s.above(y);
                level.setBlock(b, (y == 1 ? Blocks.GOLD_BLOCK : Blocks.GILDED_BLACKSTONE).defaultBlockState(), Block.UPDATE_ALL);
                pl.add(b);
            }
            level.sendParticles(ParticleTypes.WAX_ON, s.getX() + 0.5, s.getY() + 1.5, s.getZ() + 0.5, 30, 0.5, 1, 0.5, 0.2);
        }

        private void raiseShield() {
            shielded = true;
            action = 0;
            weak = 0;
            setGlow(false);
            shieldLeft = SHIELD_TICKS;
            pillars.clear();
            for (int i = 0; i < pillarSpots.size(); i++) {
                pillars.add(new ArrayList<>());
                buildPillar(i);
                // Every pillar gets a guard (piglins on Easy, brutes after that).
                hooks.minionAt(diff == 0 ? net.minecraft.world.entity.EntityTypes.PIGLIN : net.minecraft.world.entity.EntityTypes.PIGLIN_BRUTE,
                        pillarSpots.get(i).offset(2, 0, 0));
            }
            level.playSound(null, body.blockPosition(), SoundEvents.BEACON_ACTIVATE, SoundSource.HOSTILE, 2f, 0.7f);
            for (ServerPlayer f : hooks.fighters())
                com.aidan.skyserver.lobby.Lobby.msg(f, "§e§lThe Gilded Tyrant raises a golden shield! §7Break its §63 guarded gold pillars §7within §f"
                        + (SHIELD_TICKS / 20) + " seconds§7, or they rise again.");
        }

        /** Time's up with the shield still standing: every broken pillar rises again. */
        private void rebuildPillars() {
            shieldLeft = SHIELD_TICKS;
            boolean any = false;
            for (int i = 0; i < pillars.size(); i++) {
                if (!pillars.get(i).isEmpty()) continue;
                buildPillar(i);
                any = true;
            }
            if (!any) return;
            level.playSound(null, body.blockPosition(), SoundEvents.BEACON_ACTIVATE, SoundSource.HOSTILE, 2f, 1.2f);
            for (ServerPlayer f : hooks.fighters())
                com.aidan.skyserver.lobby.Announce.title(f, "§6§lTHE PILLARS RISE AGAIN", "§7Break all 3 within " + (SHIELD_TICKS / 20) + " seconds!", 5, 40, 10);
        }

        private void dropShield() {
            shielded = false;
            level.playSound(null, body.blockPosition(), SoundEvents.GLASS_BREAK, SoundSource.HOSTILE, 2f, 0.5f);
            level.sendParticles(ParticleTypes.WAX_ON, body.getX(), body.getY() + 2.5, body.getZ(), 80, 1.5, 1.5, 1.5, 0.4);
            cdA = 40;
        }

        /** Golden beams from the pillars to the Tyrant while the shield is up. */
        private void beams() {
            Vec3 to = body.position().add(0, 3, 0);
            for (List<BlockPos> pl : pillars) {
                if (pl.isEmpty()) continue;
                Vec3 from = Vec3.atCenterOf(pl.get(2));
                for (int k = 0; k <= 12; k++) {
                    Vec3 p = from.lerp(to, k / 12.0);
                    level.sendParticles(ParticleTypes.END_ROD, p.x, p.y, p.z, 1, 0, 0, 0, 0);
                }
            }
        }

        /**
         * 5.12: if body parts are missing (summon refused, chunk reload...), take what's left away and
         * build the whole body again on the boss. A few tries per fight, each one logged.
         */
        private void checkBody() {
            int missing = 0;
            for (Display d : parts) if (d.isRemoved()) missing++;
            if (!parts.isEmpty() && missing == 0) return;
            if (rebuilds >= MAX_REBUILDS) return;
            rebuilds++;
            com.aidan.skyserver.Skyserver.LOGGER.warn("[Expedition] {} body: {} of {} parts missing - rebuilding (try {}/{})",
                    colossus ? "Cinder Colossus" : "Gilded Tyrant", parts.isEmpty() ? "all" : missing, parts.size(), rebuilds, MAX_REBUILDS);
            for (Display d : parts) d.discard();
            for (Display d : orbit) d.discard();
            parts.clear();
            orbit.clear();
            alwaysGlow.clear();
            for (Display d : level.getEntitiesOfClass(Display.class, body.getBoundingBox().inflate(8), e -> e.entityTags().contains(PART_TAG))) d.discard();
            glowing = false;
            if (colossus) buildColossus(this, body.position());
            else buildTyrant(this, body.position());
        }

        // ---- The body follows the hidden mob ----

        private void moveBody() {
            double bob = colossus ? Math.sin(t * 0.15) * 0.05 : Math.sin(t * 0.2) * 0.04;
            float yaw = body.yBodyRot;
            double x = body.getX(), y = body.getY() + lift + bob, z = body.getZ();
            for (Display d : parts) {
                d.setPos(x, y, z);
                d.setYRot(yaw);
            }
            // Blaze rods orbit the Colossus (faster when hurt or enraged).
            double spin = t * (enraged ? 0.3 : phase == 3 ? 0.22 : phase == 2 ? 0.15 : 0.1);
            for (int i = 0; i < orbit.size(); i++) {
                double a = spin + i * Math.PI * 2 / orbit.size();
                Display d = orbit.get(i);
                d.setPos(x + Math.cos(a) * 3.0, y + 3.0 + Math.sin(t * 0.1 + i) * 0.4, z + Math.sin(a) * 3.0);
                d.setYRot((float) Math.toDegrees(a));
            }
            if (colossus && t % 5 == 0)
                level.sendParticles(ParticleTypes.FLAME, x, y + 2.8, z, 3, 0.8, 0.8, 0.8, 0.01);
            if (!colossus && shielded && t % 4 == 0)
                level.sendParticles(ParticleTypes.WAX_ON, x, y + 2.5, z, 6, 1.6, 1.6, 1.6, 0.02);
        }

        /** Removes the body, the bar and any pillars. Safe to call more than once. */
        void end() {
            if (over) return;
            over = true;
            for (Display d : parts) d.discard();
            for (Display d : orbit) d.discard();
            parts.clear();
            orbit.clear();
            alwaysGlow.clear();
            eruptions.clear();
            ring = -1;
            bar.removeAllPlayers();
            for (List<BlockPos> pl : pillars) for (BlockPos b : pl) level.setBlock(b, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            pillars.clear();
            // Anything tagged as a part that got left behind (chunk reload etc.).
            for (Display d : level.getEntitiesOfClass(Display.class, new AABB(arena.x0 - 4, arena.y0 - 4, arena.z0 - 4, arena.x1 + 5, arena.y1 + 5, arena.z1 + 5),
                    e -> e.entityTags().contains(PART_TAG))) d.discard();
        }
    }

    // =====================================================================
    // Making a boss
    // =====================================================================

    /** Turns a freshly spawned hidden mob into the boss and builds its body. crew = players on the expedition. */
    static Fight start(ServerLevel level, Mob body, boolean fortress, int diff, int crew, ExpeditionRooms.Room arena, List<BlockPos> pillarSpots, Hooks hooks) {
        AttributeInstance hp = body.getAttribute(Attributes.MAX_HEALTH);
        if (hp != null) {
            hp.removeModifiers();
            hp.setBaseValue(HP[diff] * (1 + HP_PER_EXTRA_PLAYER * Math.max(0, crew - 1)));
        }
        AttributeInstance dmg = body.getAttribute(Attributes.ATTACK_DAMAGE);
        if (dmg != null) {
            dmg.removeModifiers();
            dmg.setBaseValue((fortress ? 12 : 10) + 3 * diff);
        }
        AttributeInstance kb = body.getAttribute(Attributes.KNOCKBACK_RESISTANCE);
        if (kb != null) kb.setBaseValue(1.0);
        AttributeInstance scale = body.getAttribute(Attributes.SCALE);
        if (scale != null) scale.setBaseValue(fortress ? 2.3 : 2.4);
        AttributeInstance follow = body.getAttribute(Attributes.FOLLOW_RANGE);
        if (follow != null) follow.setBaseValue(48);
        body.setHealth(body.getMaxHealth());
        body.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, ItemStack.EMPTY); // invisible mobs still show what they hold
        body.setItemSlot(net.minecraft.world.entity.EquipmentSlot.OFFHAND, ItemStack.EMPTY);
        body.addEffect(new MobEffectInstance(MobEffects.INVISIBILITY, 20 * 60 * 60, 0, false, false));
        body.addEffect(new MobEffectInstance(MobEffects.FIRE_RESISTANCE, 20 * 60 * 60, 0, false, false));
        body.setSilent(true);
        body.setCustomName(Component.literal(name(fortress)));
        body.setCustomNameVisible(false);
        Fight f = new Fight(level, fortress, diff, body, arena, pillarSpots, hooks);
        Vec3 at = body.position();
        if (fortress) buildColossus(f, at);
        else buildTyrant(f, at);
        com.aidan.skyserver.Skyserver.LOGGER.info("[Expedition] {} spawned at {} {} {} ({} HP, {} body parts)", fortress ? "Cinder Colossus" : "Gilded Tyrant",
                Math.round(at.x), Math.round(at.y), Math.round(at.z), Math.round(body.getMaxHealth()), f.parts.size());
        return f;
    }

    /**
     * A block part: size (sx, sy, sz) with its bottom-middle at (cx, cy, cz) relative to the boss's feet.
     * 5.11: an ITEM display of the block's item - a block item with no display transform renders as a
     * full block centred on its spot, so the translation is the part's centre.
     */
    private static void block(Fight f, Vec3 at, String block, double cx, double cy, double cz, double sx, double sy, double sz, int glow) {
        String nbt = String.format(Locale.ROOT, "{item:{id:\"%s\",count:1},item_display:\"none\",transformation:{left_rotation:[0f,0f,0f,1f],right_rotation:[0f,0f,0f,1f],"
                        + "translation:[%.3ff,%.3ff,%.3ff],scale:[%.3ff,%.3ff,%.3ff]},teleport_duration:2,brightness:{sky:15,block:15}%s",
                block, cx, cy + sy / 2, cz, sx, sy, sz, glow >= 0 ? ",Glowing:1b,glow_color_override:" + glow : "");
        Display d = summon(f.level, "item_display", at, nbt);
        if (d == null) return;
        f.parts.add(d);
        if (glow >= 0) f.alwaysGlow.add(d);
    }

    private static void orbitItem(Fight f, Vec3 at, String item, double scale) {
        String nbt = String.format(Locale.ROOT, "{item:{id:\"%s\",count:1},transformation:{left_rotation:[0f,0f,0f,1f],right_rotation:[0f,0f,0f,1f],"
                + "translation:[0f,0f,0f],scale:[%.2ff,%.2ff,%.2ff]},teleport_duration:2,brightness:{sky:15,block:15}", item, scale, scale, scale);
        Display d = summon(f.level, "item_display", at, nbt);
        if (d != null) f.orbit.add(d);
    }

    /** Summons a display entity with a unique tag (nbt is open: this closes it with the tags) and returns it. */
    private static Display summon(ServerLevel level, String type, Vec3 at, String nbtOpen) {
        String tag = "skyserver_bp_" + (partCounter++);
        String cmd = String.format(Locale.ROOT, "summon minecraft:%s %.3f %.3f %.3f %s,Tags:[\"%s\",\"%s\"]}", type, at.x, at.y, at.z, nbtOpen, PART_TAG, tag);
        try {
            CommandSourceStack src = level.getServer().createCommandSourceStack().withLevel(level).withSuppressedOutput();
            level.getServer().getCommands().performPrefixedCommand(src, cmd);
        } catch (RuntimeException e) {
            com.aidan.skyserver.Skyserver.LOGGER.warn("[Expedition] boss part summon threw {} for: {}", e.toString(), cmd);
            return null;
        }
        List<Display> found = level.getEntitiesOfClass(Display.class, new AABB(at, at).inflate(2), e -> e.entityTags().contains(tag));
        // 5.12: say why a boss shows no body (the summon text was refused, or the entity didn't appear).
        if (found.isEmpty() && summonWarnings++ < 6) com.aidan.skyserver.Skyserver.LOGGER.warn("[Expedition] boss part did not appear: {}", cmd);
        return found.isEmpty() ? null : found.get(0);
    }

    private static final int GOLD_GLOW = 0xFFD700;

    /** ~6 blocks of nether brick and magma: legs, belted torso with a glowing core, big arms, horned head. */
    private static void buildColossus(Fight f, Vec3 at) {
        // Legs and feet.
        for (int s = -1; s <= 1; s += 2) {
            block(f, at, "minecraft:nether_bricks", 0.95 * s, 0.3, 0, 1.2, 1.9, 1.2, -1);
            block(f, at, "minecraft:magma_block", 0.95 * s, 0, 0.15, 1.45, 0.45, 1.6, -1);
        }
        // Torso, belt, glowing core.
        block(f, at, "minecraft:red_nether_bricks", 0, 2.0, 0, 3.3, 0.45, 2.3, -1);
        block(f, at, "minecraft:nether_bricks", 0, 2.2, 0, 3.0, 2.2, 2.0, -1);
        block(f, at, "minecraft:magma_block", 0, 2.7, 0.9, 1.3, 1.2, 0.3, -1);
        block(f, at, "minecraft:shroomlight", 0, 3.0, 1.02, 0.5, 0.5, 0.1, -1);
        // Shoulders, arms, magma fists.
        for (int s = -1; s <= 1; s += 2) {
            block(f, at, "minecraft:chiseled_nether_bricks", 2.05 * s, 3.55, 0, 1.3, 1.0, 1.5, -1);
            block(f, at, "minecraft:nether_bricks", 2.1 * s, 1.35, 0, 0.95, 2.3, 0.95, -1);
            block(f, at, "minecraft:magma_block", 2.1 * s, 0.35, 0.05, 1.25, 1.05, 1.25, -1);
        }
        // Head, glowing eyes, horns.
        block(f, at, "minecraft:nether_bricks", 0, 4.4, 0.1, 1.45, 1.25, 1.45, -1);
        for (int s = -1; s <= 1; s += 2) {
            block(f, at, "minecraft:shroomlight", 0.35 * s, 4.95, 0.83, 0.32, 0.2, 0.1, -1);
            block(f, at, "minecraft:blackstone", 0.62 * s, 5.6, 0.1, 0.25, 0.75, 0.25, -1);
        }
        block(f, at, "minecraft:magma_block", 0, 5.65, 0.1, 0.5, 0.3, 0.5, -1);
        for (int i = 0; i < 4; i++) orbitItem(f, at, "minecraft:blaze_rod", 1.6);
    }

    /** Blackstone and gold: legs, torso with gold belt and pauldrons, gilded fists, piglin head, glowing crown. */
    private static void buildTyrant(Fight f, Vec3 at) {
        for (int s = -1; s <= 1; s += 2) {
            block(f, at, "minecraft:blackstone", 0.7 * s, 0, 0, 1.0, 1.9, 1.0, -1);
            block(f, at, "minecraft:polished_blackstone_bricks", 1.65 * s, 1.5, 0, 0.85, 2.0, 0.85, -1);
            block(f, at, "minecraft:gilded_blackstone", 1.65 * s, 0.75, 0.05, 1.05, 0.85, 1.05, -1);
            block(f, at, "minecraft:gold_block", 1.6 * s, 3.45, 0, 1.1, 0.6, 1.3, -1);
        }
        block(f, at, "minecraft:polished_blackstone_bricks", 0, 1.8, 0, 2.5, 2.0, 1.6, -1);
        block(f, at, "minecraft:gold_block", 0, 1.8, 0, 2.7, 0.3, 1.8, -1);
        block(f, at, "minecraft:gilded_blackstone", 0, 2.5, 0.75, 0.9, 0.9, 0.2, -1);
        // Head: wide piglin face with a snout and tusks.
        block(f, at, "minecraft:polished_blackstone", 0, 3.8, 0.05, 1.7, 1.35, 1.45, -1);
        block(f, at, "minecraft:gilded_blackstone", 0, 4.05, 0.8, 0.75, 0.55, 0.3, -1);
        for (int s = -1; s <= 1; s += 2) {
            block(f, at, "minecraft:bone_block", 0.45 * s, 3.85, 0.85, 0.14, 0.42, 0.14, -1);
            block(f, at, "minecraft:redstone_block", 0.42 * s, 4.65, 0.79, 0.26, 0.18, 0.08, -1);
        }
        // The glowing crown.
        block(f, at, "minecraft:gold_block", 0, 5.15, 0.05, 1.8, 0.28, 1.55, GOLD_GLOW);
        for (int sx = -1; sx <= 1; sx += 2)
            for (int sz = -1; sz <= 1; sz += 2) block(f, at, "minecraft:gold_block", 0.72 * sx, 5.4, 0.05 + 0.6 * sz, 0.32, 0.5, 0.32, GOLD_GLOW);
        block(f, at, "minecraft:gold_block", 0, 5.4, 0.7, 0.3, 0.65, 0.3, GOLD_GLOW);
    }
}
