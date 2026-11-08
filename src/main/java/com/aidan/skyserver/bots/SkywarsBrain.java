package com.aidan.skyserver.bots;

import com.aidan.skyserver.skywars.SkywarsGame;
import com.aidan.skyserver.skywars.SkywarsLoot;
import com.aidan.skyserver.skywars.SkywarsMap;
import com.aidan.skyserver.skywars.SwItems;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.*;

/**
 * Skywars bot. Plays at a human pace:
 *  1. Loots all three chests on its own island, taking items one at a time (about as long as a
 *     person takes to open a chest and grab what they want), then puts on its gear.
 *  2. Only leaves the island once it has enough blocks for the trip; otherwise it holds the
 *     island with a bow and waits for blocks from the item drops (one every 20 seconds).
 *  3. Bridges at sneak-bridging speed to its mid island, then the middle, then hunts players.
 * Fights with swords, bows and fireballs, eats golden apples / healing soup when hurt, and
 * throws bridge eggs when it's short of blocks.
 */
public final class SkywarsBrain extends BotBrain {
    private int match = -1;
    private final Map<BlockPos, Long> lootedAt = new HashMap<>();
    private long eatingUntil = 0;
    private long nextEgg = 0;
    private long nextBow = 0;
    private long nextFireball = 0;
    private SwLearning.Plan plan = SwLearning.Plan.MIDDLE;
    private long matchStart = 0;
    private int kills = 0;
    private boolean placed = false;
    // Looting a chest item by item.
    private BlockPos openChest = null;
    private long nextGrab = 0;

    public SkywarsBrain(BotPlayer bot) {
        super(bot);
    }

    @Override
    protected LevelWorld world() {
        AABB arena = SkywarsMap.arenaBox();
        return new LevelWorld(level(), SkywarsMap.VOID_Y,
                pos -> false, // bots don't dig in Skywars
                pos -> arena.contains(Vec3.atCenterOf(pos)) && pos.getY() < SkywarsMap.ISLAND_Y + 40);
    }

    /** Sneak-bridging speed, not instant: easy ~1.4, medium ~2, hard ~2.9 blocks a second. */
    @Override
    protected int bridgeDelay() {
        return switch (d()) { case EASY -> 14; case MEDIUM -> 10; case HARD -> 7; };
    }

    /** Ticks per item when taking things out of a chest. */
    private int grabDelay() {
        return switch (d()) { case EASY -> 16; case MEDIUM -> 11; case HARD -> 8; };
    }

    private Pathfinder.Options travelOptions() {
        Pathfinder.Options o = new Pathfinder.Options().bridge(Math.min(countBlocks(), 40)).nodes(5000);
        o.bridgeCost = 1.6; // bridging over the void is normal here, so plan straight bridges
        return o;
    }

    @Override
    protected void think() {
        if (!SkywarsGame.isAlive(bot.getUUID())) return;
        if (match != SkywarsGame.matchNumber()) {
            match = SkywarsGame.matchNumber();
            lootedAt.clear();
            resetBody();
            plan = SwLearning.pickPlan(rng);
            matchStart = age;
            kills = 0;
            placed = false;
            openChest = null;
        }
        if (age % 20 == 0) equipBestArmor();

        // Finish eating before doing anything else.
        if (age < eatingUntil) {
            if (!bot.isUsingItem()) eatingUntil = 0;
            return;
        }
        if (bot.getHealth() < 9 && bot.difficulty() != Difficulty.EASY && heal()) return;

        LivingEntity enemy = nearestEnemy();
        double dist = enemy == null ? 999 : distanceTo(enemy);
        boolean hurtByThem = enemy != null && bot.getLastHurtByMob() == enemy
                && bot.tickCount - bot.getLastHurtByMobTimestamp() < 100;

        // Fight anyone close, or anyone hitting us.
        if (enemy != null && (dist < 7 || hurtByThem)) {
            openChest = null;
            fight(enemy, dist);
            return;
        }
        stopShooting();

        // 1. Our own island's chests, one item at a time.
        if (openChest != null) {
            lootSlowly(openChest);
            return;
        }
        BlockPos home = nextHomeChest();
        if (home != null) {
            walkAndOpen(home);
            return;
        }

        long sinceStart = age - matchStart;
        int blocks = countBlocks();
        boolean canTravel = blocks >= 18 || findSlot(SkywarsLoot::isBridgeEgg) >= 0;

        // 2. Not enough blocks to go anywhere: hold the island and wait for item drops.
        if (!canTravel) {
            camp();
            return;
        }
        // Learned opening plans (after looting): rush someone, or hold the island for a bit.
        if (plan == SwLearning.Plan.CAMP && sinceStart < 50 * 20) {
            camp();
            return;
        }
        if (plan == SwLearning.Plan.RUSH && sinceStart < 100 * 20) {
            hunt(enemy);
            return;
        }

        // 3. Mid island, then the middle, then the supply drop.
        BlockPos chest = nextAwayChest();
        if (chest != null) {
            walkAndOpen(chest);
            return;
        }
        hunt(enemy);
    }

    // ---- Called by the game (for learning) ----

    public void onKill() {
        kills++;
    }

    /** place 1 = won. */
    public void onPlaced(int place, int players) {
        if (placed) return;
        placed = true;
        SwLearning.result(plan, place, players, kills);
    }

    @Override
    public String describe() {
        return "plan: " + plan.text + (openChest != null ? ", looting" : "") + ", blocks " + countBlocks();
    }

    // =====================================================================
    // Looting
    // =====================================================================

    private BlockPos nextHomeChest() {
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (BlockPos c : SkywarsGame.ownChests(bot.getUUID())) {
            if (lootedAt.containsKey(c)) continue;
            if (!(level().getBlockEntity(c) instanceof Container container) || container.isEmpty()) continue;
            double d = bot.position().distanceTo(Vec3.atCenterOf(c));
            if (d < bestDist) { bestDist = d; best = c; }
        }
        return best;
    }

    /** Mid island chests first (the nearest), then the centre, then the supply drop. */
    private BlockPos nextAwayChest() {
        List<BlockPos> chests = new ArrayList<>(SkywarsMap.midChests());
        chests.addAll(SkywarsMap.centerChests());
        BlockPos drop = SkywarsMap.supplyDropSpot();
        if (level().getBlockEntity(drop) instanceof Container) chests.add(drop);
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (BlockPos c : chests) {
            if (lootedAt.containsKey(c)) continue;
            if (!(level().getBlockEntity(c) instanceof Container container) || container.isEmpty()) continue;
            double d = bot.position().distanceTo(Vec3.atCenterOf(c));
            if (d > 45) continue; // don't cross the whole map for a chest
            if (d < bestDist) { bestDist = d; best = c; }
        }
        return best;
    }

    private void walkAndOpen(BlockPos chest) {
        Vec3 eye = bot.getEyePosition();
        if (eye.distanceTo(Vec3.atCenterOf(chest)) <= 3.3) {
            openChest = chest;
            nextGrab = age + 8 + rng.nextInt(8); // a moment to "open" it and look
            return;
        }
        PathStatus status = goTo(chest, Pathfinder.near(chest.getX(), chest.getY(), chest.getZ(), 2.2), travelOptions());
        if (status == PathStatus.STUCK) {
            if (!tryBridgeEgg(Vec3.atCenterOf(chest))) lootedAt.put(chest, age); // unreachable: skip it
        }
    }

    /** Takes one useful item at a time, like a player clicking through the chest. */
    private void lootSlowly(BlockPos pos) {
        lookAt(Vec3.atCenterOf(pos), 0.2);
        if (!(level().getBlockEntity(pos) instanceof Container chest) || bot.getEyePosition().distanceTo(Vec3.atCenterOf(pos)) > 4.5) {
            openChest = null;
            return;
        }
        if (age < nextGrab) return;
        nextGrab = age + grabDelay() + rng.nextInt(4);
        for (int i = 0; i < chest.getContainerSize(); i++) {
            ItemStack stack = chest.getItem(i);
            if (stack.isEmpty() || !worthTaking(stack)) continue;
            ItemStack copy = stack.copy();
            bot.getInventory().add(copy); // shrinks copy by however much fit
            chest.setItem(i, copy.isEmpty() ? ItemStack.EMPTY : copy);
            chest.setChanged();
            swingArm();
            equipBestArmor();
            selectBestWeapon();
            return; // one item per grab
        }
        // Nothing left worth taking.
        lootedAt.put(pos, age);
        openChest = null;
    }

    /** Skip things the bot can't use (and keep armor only if it's an upgrade). */
    private boolean worthTaking(ItemStack s) {
        if (s.is(Items.LAVA_BUCKET) || s.is(Items.WATER_BUCKET) || s.is(Items.ENDER_PEARL) || s.is(Items.SNOWBALL)
                || s.is(Items.COBWEB) || s.is(Items.EGG) && !SkywarsLoot.isBridgeEgg(s) || SwItems.botIgnores(s)) return false;
        var slot = armorSlot(s);
        if (slot != null) return armorScore(s) > armorScore(bot.getItemBySlot(slot));
        return true;
    }

    // =====================================================================
    // Fighting and moving
    // =====================================================================

    private boolean heal() {
        int apple = findSlot(s -> s.is(Items.GOLDEN_APPLE));
        if (apple >= 0) {
            stopShooting();
            useItem(apple);
            eatingUntil = age + 40;
            return true;
        }
        int soup = findSlot(s -> SwItems.is(s, SwItems.SOUP));
        if (soup >= 0) {
            useItem(soup);
            return true;
        }
        return false;
    }

    /** Stay on our island with gear on and shoot anyone bridging over. */
    private void camp() {
        LivingEntity target = nearestAnywhere();
        if (target != null && distanceTo(target) < 32 && age >= nextBow && hasBow() && bot.hasLineOfSight(target)) {
            if (shoot(target, s -> s.is(Items.BOW), 20, false, true)) return;
        }
        stopShooting();
        BlockPos home = SkywarsGame.ownSpawn(bot.getUUID());
        if (home != null && bot.position().distanceTo(Vec3.atBottomCenterOf(home)) > 3)
            goTo(home, Pathfinder.near(home.getX(), home.getY(), home.getZ(), 2.5), travelOptions());
        if (target != null) lookAt(target.getEyePosition(), 1.0);
    }

    /** Hunt the nearest player, shooting them from range if we have a bow or fireballs. */
    private void hunt(LivingEntity enemy) {
        if (enemy == null) enemy = nearestAnywhere();
        if (enemy == null) return;
        double dist = distanceTo(enemy);
        if (dist > 10 && dist < 30 && age >= nextFireball && bot.hasLineOfSight(enemy) && throwFireball(enemy)) return;
        if (dist > 12 && dist < 40 && age >= nextBow && hasBow() && bot.hasLineOfSight(enemy)) {
            if (shoot(enemy, s -> s.is(Items.BOW), 20, false, true)) return;
        }
        PathStatus status = goTo(enemy.blockPosition(),
                Pathfinder.near(enemy.getBlockX(), enemy.getBlockY(), enemy.getBlockZ(), 2.0), travelOptions());
        if (status == PathStatus.STUCK) tryBridgeEgg(enemy.position());
    }

    private void fight(LivingEntity enemy, double dist) {
        if (dist > 7 && hasBow() && bot.hasLineOfSight(enemy)) {
            if (shoot(enemy, s -> s.is(Items.BOW), 20, false, true)) return;
        }
        Pathfinder.Options chase = new Pathfinder.Options().bridge(Math.min(countBlocks(), 8)).nodes(1500);
        chase.bridgeCost = 1.6;
        melee(enemy, chase);
        nextBow = age + 30;
    }

    /** Medium and hard bots throw fireballs at players across a gap. */
    private boolean throwFireball(LivingEntity enemy) {
        if (d() == Difficulty.EASY) return false;
        int slot = findSlot(s -> SwItems.is(s, SwItems.FIREBALL));
        if (slot < 0) return false;
        lookAt(enemy.getEyePosition().add(0, -0.6, 0), 1.0);
        if (aimOff(enemy.getEyePosition()) < 6) {
            useItem(slot);
            nextFireball = age + 60 + rng.nextInt(60);
        }
        return true;
    }

    private boolean hasBow() {
        return findArrowCount() > 0 && findSlot(s -> s.is(Items.BOW)) >= 0;
    }

    /** Throws a bridge egg toward a spot we can't reach (out of blocks, or a long gap). */
    private boolean tryBridgeEgg(Vec3 toward) {
        if (age < nextEgg || !bot.onGround()) return false;
        int slot = findSlot(SkywarsLoot::isBridgeEgg);
        if (slot < 0) return false;
        double dx = toward.x - bot.getX(), dz = toward.z - bot.getZ();
        lookDirection(yawTo(dx, dz), -5, 0.2);
        if (aimOff(bot.getEyePosition().add(new Vec3(dx, 0, dz).normalize().scale(10))) < 12) {
            useItem(slot);
            nextEgg = age + 100;
            clearPath();
        }
        return true;
    }

    private LivingEntity nearestEnemy() {
        LivingEntity best = null;
        double bestDist = d().sight;
        for (ServerPlayer p : SkywarsGame.alivePlayers()) {
            if (p == bot || p.isSpectator()) continue;
            double d = distanceTo(p);
            if (d < bestDist && (d < 8 || bot.hasLineOfSight(p))) {
                bestDist = d;
                best = p;
            }
        }
        return best;
    }

    private LivingEntity nearestAnywhere() {
        LivingEntity best = null;
        double bestDist = Double.MAX_VALUE;
        for (ServerPlayer p : SkywarsGame.alivePlayers()) {
            if (p == bot || p.isSpectator()) continue;
            double d = distanceTo(p);
            if (d < bestDist) {
                bestDist = d;
                best = p;
            }
        }
        return best;
    }
}
