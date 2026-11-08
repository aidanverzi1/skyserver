package com.aidan.skyserver.bedwars;

import com.aidan.skyserver.lobby.Stacks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.entity.projectile.hurtingprojectile.LargeFireball;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEgg;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.*;

/**
 * The Bedwars items that need the server's help: fireballs and TNT (our own explosions, which only
 * break blocks people placed, never glass or obsidian), bridge eggs, pop-up towers, and the
 * SkyServer specials: Wren's Grapple, the Decoy Bed, the Bed Blanket, the Rescue Cloud and the
 * Spring Pad.
 * 5.9: fireballs hurt other players 50% more (5 -> 7.5 at the centre). Knockback, block breaking,
 * self-damage and TNT are unchanged.
 */
final class BwItems {
    static final int GRAPPLE_USES = 5;
    private static final float FIREBALL_RADIUS = 2.6f, TNT_RADIUS = 4.0f;
    /** 5.9: fireball damage to other players at the centre of the blast (was 5). */
    static final double FIREBALL_DAMAGE = 7.5, TNT_DAMAGE = 8;

    private record Ball(LargeFireball entity, UUID owner, long firedAt, Vec3[] last) {}
    private record Bomb(PrimedTnt tnt, UUID owner, long at) {}
    private record Egg(ThrownEgg egg, BwTeam team, int y, int[] placed, BlockPos[] last, long thrown) {}
    private record Cloud(List<BlockPos> cells, long until) {}
    private record Throw(net.minecraft.world.entity.projectile.throwableitemprojectile.Snowball ball, BwTeam team, UUID owner, Vec3[] last) {}
    private record Mite(net.minecraft.world.entity.Mob mob, BwTeam team, UUID owner, long until) {}
    public static final String MITE_TAG = "skyserver_bw_mite";

    private static final List<Ball> BALLS = new ArrayList<>();
    private static final List<Bomb> BOMBS = new ArrayList<>();
    private static final List<Egg> EGGS = new ArrayList<>();
    private static final List<Cloud> CLOUDS = new ArrayList<>();
    private static final List<Cloud> PLANKS = new ArrayList<>();
    private static final List<Throw> THROWS = new ArrayList<>();
    private static final List<Mite> MITES = new ArrayList<>();
    private static final Set<Long> SPRINGS = new HashSet<>();
    private static final Map<Long, BwTeam> DECOYS = new HashMap<>();
    private static final Map<UUID, Long> SPRING_COOLDOWN = new HashMap<>();

    private BwItems() {}

    static void init() {}

    static void reset() {
        for (Ball b : BALLS) b.entity.discard();
        for (Bomb b : BOMBS) b.tnt.discard();
        for (Egg e : EGGS) e.egg.discard();
        for (Throw t : THROWS) t.ball.discard();
        for (Mite m : MITES) m.mob.discard();
        THROWS.clear();
        MITES.clear();
        PLANKS.clear();
        BALLS.clear();
        BOMBS.clear();
        EGGS.clear();
        CLOUDS.clear();
        SPRINGS.clear();
        DECOYS.clear();
        SPRING_COOLDOWN.clear();
    }

    /** A placed block was removed: forget it was a spring pad. */
    static void forget(BlockPos pos) {
        SPRINGS.remove(pos.asLong());
    }

    // =====================================================================
    // Using items
    // =====================================================================

    /** Right-click in the air. Null = not ours (let the game handle it). */
    static InteractionResult useItem(ServerPlayer p, BwPlayer bp, ItemStack held) {
        if (Stacks.is(held, "bw_fireball")) return fireball(p, held);
        if (Stacks.is(held, "bw_blanket")) return blanket(p, bp, held);
        if (Stacks.is(held, "bw_grapple") && p.fishing != null) {
            grapple(p, bp, p.fishing, held);
            return null; // let the rod reel in as normal
        }
        if (Stacks.is(held, "bw_milk")) {
            bp.trapImmuneUntil = BedwarsGame.now() + BwRules.MAGIC_MILK_SECONDS * 20L;
            BedwarsGame.msg(p, "§fCalm Milk: §aenemy traps ignore you for " + BwRules.MAGIC_MILK_SECONDS + " seconds.");
            return null; // drink it as normal
        }
        if (Stacks.is(held, "bw_egg") || Stacks.is(held, "bw_mite")) return null; // thrown as normal, picked up in tick()
        if (Stacks.is(held, "bw_plank")) {
            if (plank(p, bp)) held.shrink(1);
            return InteractionResult.SUCCESS;
        }
        if (held.is(Items.WATER_BUCKET) || held.is(Items.BUCKET)) return null;
        return null;
    }

    /** Right-click on a block with something in hand. Null = not a special item. */
    static InteractionResult useOnBlock(ServerPlayer p, BwPlayer bp, ItemStack held, BlockPos clicked, BlockPos target, Direction face) {
        if (Stacks.is(held, "bw_tnt")) {
            if (BedwarsGame.canPlace(target)) {
                ServerLevel level = (ServerLevel) p.level();
                PrimedTnt tnt = new PrimedTnt(level, target.getX() + 0.5, target.getY(), target.getZ() + 0.5, p);
                tnt.setFuse(60);
                level.addFreshEntity(tnt);
                level.playSound(null, target, SoundEvents.TNT_PRIMED, SoundSource.BLOCKS, 1f, 1f);
                BOMBS.add(new Bomb(tnt, p.getUUID(), BedwarsGame.now() + 52));
                held.shrink(1);
                bp.active = true;
            }
            return InteractionResult.FAIL;
        }
        if (Stacks.is(held, "bw_tower")) {
            if (tower(p, bp, target)) held.shrink(1);
            return InteractionResult.FAIL;
        }
        if (Stacks.is(held, "bw_decoy")) {
            if (decoy(p, bp, target)) held.shrink(1);
            return InteractionResult.FAIL;
        }
        if (Stacks.is(held, "bw_spring")) {
            if (!BedwarsGame.canPlace(target)) return InteractionResult.FAIL;
            ServerLevel level = (ServerLevel) p.level();
            level.setBlock(target, Blocks.SLIME_BLOCK.defaultBlockState(), Block.UPDATE_ALL);
            BedwarsGame.markPlaced(target);
            SPRINGS.add(target.asLong());
            held.shrink(1);
            level.playSound(null, target, SoundEvents.SLIME_BLOCK_PLACE, SoundSource.BLOCKS, 1f, 1f);
            return InteractionResult.FAIL;
        }
        if (Stacks.is(held, "bw_plank")) {
            if (plank(p, bp)) held.shrink(1);
            return InteractionResult.FAIL;
        }
        if (Stacks.is(held, "bw_blanket")) return blanket(p, bp, held);
        if (Stacks.is(held, "bw_fireball")) return fireball(p, held);
        if (Stacks.is(held, "bw_cloud")) return InteractionResult.FAIL;
        if (held.is(Items.CHEST)) return InteractionResult.FAIL;
        return null;
    }

    // ---- Fireball ----

    private static InteractionResult fireball(ServerPlayer p, ItemStack held) {
        if (p.getCooldowns().isOnCooldown(held)) return InteractionResult.FAIL;
        ServerLevel level = (ServerLevel) p.level();
        Vec3 look = p.getLookAngle();
        LargeFireball ball = new LargeFireball(level, p, look, 0);
        ball.setPos(p.getX() + look.x * 1.3, p.getEyeY() - 0.2 + look.y * 1.3, p.getZ() + look.z * 1.3);
        level.addFreshEntity(ball);
        BALLS.add(new Ball(ball, p.getUUID(), BedwarsGame.now(), new Vec3[]{ball.position()}));
        level.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.GHAST_SHOOT, SoundSource.PLAYERS, 0.8f, 1.1f);
        held.shrink(1);
        p.getCooldowns().addCooldown(held, 10);
        BwPlayer bp = BedwarsGame.bp(p.getUUID());
        if (bp != null) bp.active = true;
        return InteractionResult.SUCCESS;
    }

    /**
     * Our explosion: pushes everyone nearby away (so you can fireball-jump), hurts enemies (and you a
     * little), and removes placed blocks in range - except blast-proof glass, obsidian, beds, and
     * (for fireballs) end stone, and anything shielded behind glass or obsidian.
     */
    static void blast(ServerLevel level, Vec3 c, float radius, boolean tnt, UUID ownerId) {
        level.sendParticles(tnt ? ParticleTypes.EXPLOSION_EMITTER : ParticleTypes.EXPLOSION, c.x, c.y, c.z, tnt ? 1 : 3, 0.3, 0.3, 0.3, 0);
        level.playSound(null, c.x, c.y, c.z, SoundEvents.GENERIC_EXPLODE.value(), SoundSource.BLOCKS, tnt ? 1.2f : 0.8f, 1f);
        ServerPlayer owner = BedwarsGame.online(ownerId);
        BwTeam ownerTeam = BedwarsGame.teamOf(ownerId);
        double reach = radius + 1.5;
        for (ServerPlayer t : BedwarsGame.alivePlayers()) {
            Vec3 body = t.position().add(0, 0.6, 0);
            double d = body.distanceTo(c);
            if (d > reach) continue;
            double f = 1 - d / reach;
            Vec3 push = body.subtract(c);
            push = push.lengthSqr() < 1e-4 ? new Vec3(0, 1, 0) : push.normalize();
            boolean self = t.getUUID().equals(ownerId);
            double strength = (tnt ? 1.5 : 1.25) * f;
            Vec3 v = t.getDeltaMovement().add(push.x * strength * 1.4, Math.max(0.3, push.y + 0.55) * strength * (self ? 1.35 : 1.0), push.z * strength * 1.4);
            t.setDeltaMovement(v);
            t.syncVelocity = true;
            if (!(t instanceof com.aidan.skyserver.bots.BotPlayer)) t.connection.send(new ClientboundSetEntityMotionPacket(t));
            BwTeam tt = BedwarsGame.teamOf(t.getUUID());
            float dmg = self ? (float) (1.0 * f) : tt == ownerTeam ? 0 : (float) ((tnt ? TNT_DAMAGE : FIREBALL_DAMAGE) * f);
            if (dmg > 0.2f) t.hurtServer(level, level.damageSources().explosion(null, owner), dmg);
        }
        int r = (int) Math.ceil(radius);
        BlockPos center = BlockPos.containing(c);
        for (BlockPos f : BlockPos.betweenClosed(center.offset(-2, -2, -2), center.offset(2, 2, 2)))
            if (level.getBlockState(f).is(Blocks.FIRE)) level.setBlock(f, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        for (int dx = -r; dx <= r; dx++)
            for (int dy = -r; dy <= r; dy++)
                for (int dz = -r; dz <= r; dz++) {
                    if (dx * dx + dy * dy + dz * dz > radius * radius) continue;
                    BlockPos pos = center.offset(dx, dy, dz);
                    if (!BedwarsGame.isPlaced(pos)) continue;
                    BlockState s = level.getBlockState(pos);
                    if (blastProof(s) || (!tnt && s.is(Blocks.END_STONE))) continue;
                    if (shielded(level, c, pos)) continue;
                    BedwarsGame.removePlaced(level, pos);
                    bedBell(pos, ownerTeam, owner == null ? null : owner.getName().getString());
                }
    }

    private static boolean blastProof(BlockState s) {
        if (s.is(Blocks.OBSIDIAN) || s.getBlock() instanceof BedBlock) return true;
        String id = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(s.getBlock()).getPath();
        return id.endsWith("stained_glass");
    }

    /** Is there glass or obsidian between the blast and this block? */
    private static boolean shielded(ServerLevel level, Vec3 from, BlockPos to) {
        Vec3 target = Vec3.atCenterOf(to);
        Vec3 step = target.subtract(from);
        double len = step.length();
        if (len < 1) return false;
        step = step.scale(0.4 / len);
        Vec3 at = from;
        for (double t = 0.4; t < len - 0.6; t += 0.4) {
            at = at.add(step);
            BlockPos b = BlockPos.containing(at);
            if (b.equals(to)) break;
            if (blastProof(level.getBlockState(b))) return true;
        }
        return false;
    }

    // ---- Pop-up tower ----

    private static boolean tower(ServerPlayer p, BwPlayer bp, BlockPos at) {
        ServerLevel level = (ServerLevel) p.level();
        Direction front = p.getDirection(); // the open side faces where you were looking
        BlockState wool = net.minecraft.world.level.block.Blocks.WOOL.pick(BedwarsGame.dye(bp.team)).defaultBlockState();
        Direction back = front.getOpposite();
        List<BlockPos> cells = new ArrayList<>();
        List<BlockState> states = new ArrayList<>();
        // Walls 5 high around a 1x1 shaft with a ladder (door on the side you faced).
        for (int y = 0; y < 5; y++) {
            for (int dx = -1; dx <= 1; dx++)
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dz == 0) {
                        cells.add(at.above(y));
                        states.add(Blocks.LADDER.defaultBlockState().setValue(net.minecraft.world.level.block.LadderBlock.FACING, front));
                        continue;
                    }
                    if (y < 2 && dx == front.getStepX() && dz == front.getStepZ()) continue;
                    cells.add(at.offset(dx, y, dz));
                    states.add(wool);
                }
        }
        // A 5x5 platform on top (hole over the ladder) with battlements.
        for (int dx = -2; dx <= 2; dx++)
            for (int dz = -2; dz <= 2; dz++) {
                if (dx == 0 && dz == 0) continue;
                cells.add(at.offset(dx, 5, dz));
                states.add(wool);
                if ((Math.abs(dx) == 2 || Math.abs(dz) == 2) && Math.floorMod(dx + dz, 2) == 0) {
                    cells.add(at.offset(dx, 6, dz));
                    states.add(wool);
                }
            }
        int built = 0;
        for (int i = 0; i < cells.size(); i++) {
            BlockPos c = cells.get(i);
            if (!BedwarsGame.canPlace(c)) continue;
            if (states.get(i).is(Blocks.LADDER)) {
                BlockPos wall = c.relative(back);
                if (!level.getBlockState(wall).isSolid() && !cells.contains(wall)) continue;
            }
            level.setBlock(c, states.get(i), Block.UPDATE_CLIENTS);
            BedwarsGame.markPlaced(c);
            built++;
        }
        if (built == 0) {
            BedwarsGame.msg(p, "§cNo room for a tower here.");
            return false;
        }
        level.playSound(null, at, SoundEvents.WOOL_PLACE, SoundSource.BLOCKS, 1f, 0.8f);
        bp.active = true;
        return true;
    }

    // ---- Decoy bed ----

    private static boolean decoy(ServerPlayer p, BwPlayer bp, BlockPos foot) {
        Direction facing = p.getDirection();
        BlockPos head = foot.relative(facing);
        ServerLevel level = (ServerLevel) p.level();
        if (!BedwarsGame.canPlace(foot) || !BedwarsGame.canPlace(head) || !level.getBlockState(foot.below()).isSolid()) {
            BedwarsGame.msg(p, "§cNo room for the Decoy Bed here (it needs 2 blocks of floor).");
            return false;
        }
        BwMap.placeBed(level, bp.team, foot, head);
        DECOYS.put(foot.asLong(), bp.team);
        DECOYS.put(head.asLong(), bp.team);
        BedwarsGame.msg(p, "§aDecoy Bed placed. §7Enemies who break it are in for a surprise.");
        return true;
    }

    /** A bed was hit: if it's a decoy, spring it. Returns true if it was a decoy. */
    static boolean breakDecoy(ServerPlayer breaker, BwPlayer bp, BlockPos pos) {
        BwTeam owner = DECOYS.get(pos.asLong());
        if (owner == null) return false;
        ServerLevel level = (ServerLevel) breaker.level();
        // Remove both halves.
        for (Direction d : Direction.Plane.HORIZONTAL) {
            BlockPos o = pos.relative(d);
            if (DECOYS.get(o.asLong()) == owner && level.getBlockState(o).getBlock() instanceof BedBlock) {
                DECOYS.remove(o.asLong());
                level.setBlock(o, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
            }
        }
        DECOYS.remove(pos.asLong());
        level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
        if (owner == bp.team) return true; // your own decoy: just picked up
        UUID ownerPlayer = null;
        for (UUID u : BedwarsGame.members(owner)) { ownerPlayer = u; break; }
        blast(level, Vec3.atCenterOf(pos), 0.1f, false, ownerPlayer);
        breaker.addEffect(new MobEffectInstance(MobEffects.GLOWING, 300, 0));
        breaker.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 60, 1));
        BedwarsGame.msg(breaker, "§c§lDECOY! §7That was a fake bed - and now everyone can see you.");
        BedwarsGame.teamMsg(owner, "§aYour Decoy Bed fooled " + bp.team.color + breaker.getName().getString() + "§a! They're glowing.");
        return true;
    }

    // ---- Airship Plank ----

    /** An 8-block plank walk straight out from where you stand, level with your feet. Crumbles after 10 s. */
    private static boolean plank(ServerPlayer p, BwPlayer bp) {
        ServerLevel level = (ServerLevel) p.level();
        Direction dir = p.getDirection();
        BlockPos start = BlockPos.containing(p.getX(), p.getY() - 0.5, p.getZ());
        List<BlockPos> cells = new ArrayList<>();
        BlockState plank = Blocks.SPRUCE_PLANKS.defaultBlockState();
        for (int i = 1; i <= BwRules.PLANK_LENGTH; i++) {
            BlockPos c = start.relative(dir, i);
            if (!level.getBlockState(c).isAir()) continue;
            if (!BedwarsGame.canPlace(c)) break;
            level.setBlock(c, plank, Block.UPDATE_CLIENTS);
            BedwarsGame.markPlaced(c);
            cells.add(c);
        }
        if (cells.isEmpty()) {
            BedwarsGame.msg(p, "§cNo room for the plank here. §7Stand at an edge and face the gap.");
            return false;
        }
        PLANKS.add(new Cloud(cells, BedwarsGame.now() + BwRules.PLANK_SECONDS * 20L));
        level.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.WOOD_PLACE, SoundSource.PLAYERS, 1f, 0.8f);
        bp.active = true;
        return true;
    }

    // ---- Bed Bell ----

    /** Someone broke (or blew up) a placed block: ring the bell of any team with a Bed Bell whose bed is near. */
    static void bedBell(BlockPos pos, BwTeam breaker, String breakerName) {
        BwMapDesigns.MapData m = BwMap.data();
        for (BwTeamState ts : BedwarsGame.teams.values()) {
            if (!ts.bedBell || !ts.bed || ts.out || ts.team == breaker) continue;
            BlockPos bed = BwMap.pos(m.bedFoot[ts.team.ordinal()]);
            if (Math.sqrt(bed.distSqr(pos)) > BwRules.BED_BELL_RADIUS) continue;
            long now = BedwarsGame.now();
            if (now - ts.lastBell < 100) continue;
            ts.lastBell = now;
            for (ServerPlayer mate : BedwarsGame.teamOnline(ts.team)) {
                com.aidan.skyserver.lobby.Announce.title(mate, "§6§lBED BELL!", "§7" + (breakerName == null ? "Someone" : breakerName) + " is breaking your bed defence", 0, 40, 10);
                mate.level().playSound(null, mate.getX(), mate.getY(), mate.getZ(), SoundEvents.BELL_BLOCK, SoundSource.MASTER, 1.2f, 1f);
            }
            BedwarsGame.teamMsg(ts.team, "§6🔔 Bed Bell: §c" + (breakerName == null ? "someone" : breakerName) + " is breaking your bed defence!");
            BwBotLog.event(breakerName == null ? "?" : breakerName, "BELL", ts.team.display + "'s bed bell rang");
        }
    }

    // ---- Sky Mite ----

    /** The team a Sky Mite fights for, or null if this isn't one. */
    static BwTeam miteTeam(net.minecraft.world.entity.Entity e) {
        for (Mite m : MITES) if (m.mob == e) return m.team;
        return null;
    }

    static UUID miteOwner(net.minecraft.world.entity.Entity e) {
        for (Mite m : MITES) if (m.mob == e) return m.owner;
        return null;
    }

    private static void mites(ServerLevel level, long now) {
        for (var ball : level.getEntitiesOfClass(net.minecraft.world.entity.projectile.throwableitemprojectile.Snowball.class, BwMap.box(),
                e -> Stacks.is(e.getItem(), "bw_mite"))) {
            boolean known = false;
            for (Throw t : THROWS) if (t.ball == ball) known = true;
            if (known || !(ball.getOwner() instanceof ServerPlayer owner)) continue;
            BwTeam team = BedwarsGame.teamOf(owner.getUUID());
            if (team == null) {
                ball.discard();
                continue;
            }
            THROWS.add(new Throw(ball, team, owner.getUUID(), new Vec3[]{ball.position()}));
        }
        for (Iterator<Throw> it = THROWS.iterator(); it.hasNext(); ) {
            Throw t = it.next();
            if (!t.ball.isRemoved()) {
                t.last[0] = t.ball.position();
                continue;
            }
            it.remove();
            var mob = net.minecraft.world.entity.EntityTypes.SILVERFISH.create(level, net.minecraft.world.entity.EntitySpawnReason.COMMAND);
            if (mob == null) continue;
            mob.snapTo(t.last[0].x, t.last[0].y, t.last[0].z, 0f, 0f);
            mob.addTag(MITE_TAG);
            mob.setCustomName(net.minecraft.network.chat.Component.literal(t.team.color + t.team.display + "'s Sky Mite"));
            mob.setCustomNameVisible(true);
            level.addFreshEntity(mob);
            MITES.add(new Mite(mob, t.team, t.owner, now + BwRules.SKY_MITE_SECONDS * 20L));
        }
        for (Iterator<Mite> it = MITES.iterator(); it.hasNext(); ) {
            Mite m = it.next();
            if (m.mob.isRemoved() || now >= m.until || m.mob.getY() < BwMap.voidY()) {
                m.mob.discard();
                it.remove();
                continue;
            }
            if (now % 10 != 0) continue;
            // Always chase the nearest enemy (never its own team).
            ServerPlayer best = null;
            double bd = 16 * 16;
            for (ServerPlayer p : BedwarsGame.alivePlayers()) {
                if (BedwarsGame.teamOf(p.getUUID()) == m.team) continue;
                double d = p.distanceToSqr(m.mob);
                if (d < bd) { bd = d; best = p; }
            }
            m.mob.setTarget(best);
        }
    }

    // ---- Blanket ----

    private static InteractionResult blanket(ServerPlayer p, BwPlayer bp, ItemStack held) {
        BwTeamState ts = BedwarsGame.teams.get(bp.team);
        if (!ts.bed) {
            BedwarsGame.msg(p, "§cYour bed is already gone.");
            return InteractionResult.FAIL;
        }
        ts.blanketUntil = BedwarsGame.now() + BwRules.BLANKET_SECONDS * 20L;
        held.shrink(1);
        BedwarsGame.teamMsg(bp.team, "§b" + p.getName().getString() + " wrapped your bed in a Blanket! §7It can't be broken for " + BwRules.BLANKET_SECONDS + " seconds.");
        p.level().playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.WOOL_PLACE, SoundSource.PLAYERS, 1f, 1.4f);
        return InteractionResult.SUCCESS;
    }

    // ---- Wren's Grapple ----

    private static void grapple(ServerPlayer p, BwPlayer bp, FishingHook hook, ItemStack rod) {
        if (hook.getHookedIn() != null) return;
        boolean resting = hook.onGround() || hook.getDeltaMovement().lengthSqr() < 0.01 || !hook.level().noCollision(hook, hook.getBoundingBox().inflate(0.1));
        if (!resting) return;
        Vec3 to = hook.position().subtract(p.position());
        double dist = to.length();
        if (dist < 2 || dist > 30) return;
        Vec3 pull = to.normalize().scale(Math.min(2.1, 0.35 + dist * 0.09)).add(0, 0.38, 0);
        p.setDeltaMovement(pull);
        p.syncVelocity = true;
        if (!(p instanceof com.aidan.skyserver.bots.BotPlayer)) p.connection.send(new ClientboundSetEntityMotionPacket(p));
        p.resetFallDistance();
        bp.noFallUntil = BedwarsGame.now() + 60;
        p.level().playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.FISHING_BOBBER_RETRIEVE, SoundSource.PLAYERS, 1f, 1.4f);
        int used = rod.getOrDefault(DataComponents.DAMAGE, 0) + 1;
        if (used >= GRAPPLE_USES) {
            BedwarsGame.runNextTick(() -> rod.shrink(1)); // after the rod has reeled in
            BedwarsGame.msg(p, "§7Wren's Grapple wore out.");
        } else {
            rod.set(DataComponents.DAMAGE, used);
            int left = GRAPPLE_USES - used;
            rod.set(DataComponents.CUSTOM_NAME, net.minecraft.network.chat.Component.literal("§6Wren's Grapple §7(" + left + " use" + (left == 1 ? "" : "s") + ")"));
        }
    }

    // =====================================================================
    // Every tick
    // =====================================================================

    static void tick(ServerLevel level) {
        long now = BedwarsGame.now();
        // Fireballs: explode when they hit something.
        for (Iterator<Ball> it = BALLS.iterator(); it.hasNext(); ) {
            Ball b = it.next();
            if (b.entity.isRemoved()) {
                blast(level, b.last[0], FIREBALL_RADIUS, false, b.owner);
                it.remove();
            } else if (now - b.firedAt > 100 || !BwMap.box().contains(b.entity.position())) {
                b.entity.discard();
                it.remove();
            } else {
                b.last[0] = b.entity.position();
            }
        }
        // TNT goes off just before vanilla would (so the vanilla explosion never happens).
        for (Iterator<Bomb> it = BOMBS.iterator(); it.hasNext(); ) {
            Bomb b = it.next();
            if (b.tnt.isRemoved()) {
                it.remove();
            } else if (now >= b.at) {
                Vec3 pos = b.tnt.position().add(0, 0.5, 0);
                b.tnt.discard();
                blast(level, pos, TNT_RADIUS, true, b.owner);
                it.remove();
            }
        }
        eggs(level, now);
        springs(level, now);
        clouds(level, now);
        mites(level, now);
        for (Iterator<Cloud> it = PLANKS.iterator(); it.hasNext(); ) {
            Cloud c = it.next();
            if (now < c.until) continue;
            for (BlockPos b : c.cells) if (BedwarsGame.isPlaced(b) && level.getBlockState(b).is(Blocks.SPRUCE_PLANKS)) BedwarsGame.removePlaced(level, b);
            it.remove();
        }
    }

    private static void eggs(ServerLevel level, long now) {
        for (ThrownEgg egg : level.getEntitiesOfClass(ThrownEgg.class, BwMap.box(), e -> Stacks.is(e.getItem(), "bw_egg"))) {
            boolean known = false;
            for (Egg e : EGGS) if (e.egg == egg) known = true;
            if (known || !(egg.getOwner() instanceof ServerPlayer owner)) continue;
            BwTeam team = BedwarsGame.teamOf(owner.getUUID());
            if (team == null) {
                egg.discard();
                continue;
            }
            EGGS.add(new Egg(egg, team, (int) Math.floor(owner.getY()) - 1, new int[1], new BlockPos[1], now));
        }
        for (Iterator<Egg> it = EGGS.iterator(); it.hasNext(); ) {
            Egg e = it.next();
            if (e.egg.isRemoved() || e.placed[0] >= 30 || now - e.thrown > 40) {
                e.egg.discard(); // no chickens
                it.remove();
                continue;
            }
            // Lay wool under the egg's path, a little behind it, filling diagonal corners.
            if (now - e.thrown < 2) continue;
            BlockPos at = BlockPos.containing(e.egg.getX(), e.y, e.egg.getZ());
            BlockState wool = Blocks.WOOL.pick(BedwarsGame.dye(e.team)).defaultBlockState();
            BlockPos last = e.last[0];
            if (last != null && last.getX() != at.getX() && last.getZ() != at.getZ()) lay(level, new BlockPos(at.getX(), e.y, last.getZ()), wool, e);
            lay(level, at, wool, e);
            e.last[0] = at;
        }
    }

    private static void lay(ServerLevel level, BlockPos pos, BlockState s, Egg e) {
        if (!BedwarsGame.canPlace(pos)) return;
        level.setBlock(pos, s, Block.UPDATE_ALL);
        BedwarsGame.markPlaced(pos);
        e.placed[0]++;
    }

    private static void springs(ServerLevel level, long now) {
        if (SPRINGS.isEmpty()) return;
        for (ServerPlayer p : BedwarsGame.alivePlayers()) {
            if (!p.onGround()) continue;
            BlockPos under = BlockPos.containing(p.getX(), p.getY() - 0.2, p.getZ());
            if (!SPRINGS.contains(under.asLong())) continue;
            Long cd = SPRING_COOLDOWN.get(p.getUUID());
            if (cd != null && now < cd) continue;
            SPRING_COOLDOWN.put(p.getUUID(), now + 15);
            Vec3 look = p.getLookAngle().multiply(1, 0, 1);
            look = look.lengthSqr() < 1e-4 ? Vec3.ZERO : look.normalize();
            p.setDeltaMovement(look.x * 1.15, 1.3, look.z * 1.15);
            p.syncVelocity = true;
            if (!(p instanceof com.aidan.skyserver.bots.BotPlayer)) p.connection.send(new ClientboundSetEntityMotionPacket(p));
            BwPlayer bp = BedwarsGame.bp(p.getUUID());
            if (bp != null) bp.noFallUntil = now + 80;
            level.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.SLIME_JUMP, SoundSource.PLAYERS, 1f, 1.2f);
        }
    }

    private static void clouds(ServerLevel level, long now) {
        // Catch anyone falling into the void who carries a Rescue Cloud.
        for (ServerPlayer p : BedwarsGame.alivePlayers()) {
            if (p.getY() > BwMap.OY - 8 || p.getDeltaMovement().y > -0.4) continue;
            BwPlayer bp = BedwarsGame.bp(p.getUUID());
            if (bp == null) continue;
            int slot = -1;
            var inv = p.getInventory();
            for (int i = 0; i < inv.getContainerSize(); i++) if (Stacks.is(inv.getItem(i), "bw_cloud")) { slot = i; break; }
            if (slot < 0) continue;
            boolean ground = false;
            BlockPos feet = p.blockPosition();
            for (int k = 1; k <= 10 && !ground; k++) if (!level.getBlockState(feet.below(k)).isAir()) ground = true;
            if (ground) continue;
            inv.getItem(slot).shrink(1);
            List<BlockPos> cells = new ArrayList<>();
            BlockPos center = feet.below(1);
            for (int dx = -2; dx <= 2; dx++)
                for (int dz = -2; dz <= 2; dz++) {
                    if (Math.abs(dx) == 2 && Math.abs(dz) == 2) continue;
                    BlockPos c = center.offset(dx, 0, dz);
                    if (!level.getBlockState(c).isAir()) continue;
                    level.setBlock(c, Blocks.STAINED_GLASS.pick(net.minecraft.world.item.DyeColor.WHITE).defaultBlockState(), Block.UPDATE_CLIENTS);
                    BedwarsGame.markPlaced(c);
                    cells.add(c);
                }
            p.setDeltaMovement(0, 0, 0);
            p.syncVelocity = true;
            p.teleportTo(p.getX(), center.getY() + 1.0, p.getZ());
            p.resetFallDistance();
            bp.noFallUntil = now + 40;
            CLOUDS.add(new Cloud(cells, now + 120));
            level.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.PLAYERS, 1f, 1.5f);
            BedwarsGame.msg(p, "§fRescue Cloud! §7It fades in 6 seconds - bridge back to safety!");
        }
        for (Iterator<Cloud> it = CLOUDS.iterator(); it.hasNext(); ) {
            Cloud c = it.next();
            if (now < c.until) continue;
            for (BlockPos b : c.cells) if (BedwarsGame.isPlaced(b) && level.getBlockState(b).is(Blocks.STAINED_GLASS.pick(net.minecraft.world.item.DyeColor.WHITE))) BedwarsGame.removePlaced(level, b);
            it.remove();
        }
    }
}
