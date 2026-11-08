package com.aidan.skyserver.bots;

import com.aidan.skyserver.lobby.ActivityDesign;
import com.aidan.skyserver.lobby.Duels;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

/**
 * A duel bot (/duel bot [easy|medium|hard]). Picks a plan each duel (rush, kite with the bow, or
 * counter-attack), learns which plans win, and leans toward whatever beats how real players duel.
 * Eats its golden apples when low.
 */
public final class DuelBrain extends BotBrain {
    private final MiniLearning.DuelPlan plan;
    private long nextApple = 0;

    public DuelBrain(BotPlayer bot) {
        super(bot);
        this.plan = MiniLearning.pickDuel(rng);
    }

    public MiniLearning.DuelPlan plan() {
        return plan;
    }

    @Override
    protected LevelWorld world() {
        return new LevelWorld(level(), com.aidan.skyserver.lobby.LobbyDesign.G - 5, pos -> false, pos -> false);
    }

    @Override
    public String describe() {
        return "duel: " + plan.name().toLowerCase();
    }

    @Override
    protected void think() {
        ServerPlayer foe = Duels.opponentOf(bot);
        if (foe == null || !Duels.fighting(bot)) {
            stopShooting();
            if (foe != null) lookAt(foe.getEyePosition(), 1.0);
            return;
        }
        double dist = distanceTo(foe);
        // Eat a golden apple when low and not right next to them.
        if (bot.isUsingItem() && bot.getUseItem().is(Items.GOLDEN_APPLE)) {
            move(bot.getX() - foe.getX(), bot.getZ() - foe.getZ(), 0.6);
            return;
        }
        float hpLow = switch (d()) { case EASY -> 5f; case MEDIUM -> 8f; case HARD -> 10f; };
        if (bot.getHealth() < hpLow && dist > 3.5 && age >= nextApple) {
            int slot = findSlot(s -> s.is(Items.GOLDEN_APPLE));
            if (slot >= 0) {
                stopShooting();
                select(slot);
                bot.startUsingItem(InteractionHand.MAIN_HAND);
                nextApple = age + 60;
                return;
            }
        }
        var chase = new Pathfinder.Options().nodes(1500);
        switch (plan) {
            case RUSH -> {
                if (dist > 14 && shoot(foe, s -> s.is(Items.BOW), 20, false, true)) return;
                melee(foe, chase);
            }
            case KITE -> {
                if (dist > 6 && shoot(foe, s -> s.is(Items.BOW), d() == Difficulty.HARD ? 18 : 22, false, true)) {
                    // Back away a bit while drawing if they're getting close.
                    if (dist < 9) move(bot.getX() - foe.getX(), bot.getZ() - foe.getZ(), 0.5);
                    return;
                }
                melee(foe, chase);
            }
            case COUNTER -> {
                // Circle just outside their reach until they swing, then punish.
                boolean theyJustSwung = foe.getAttackStrengthScale(0.5f) < 0.4f;
                if (dist > 4.5 || theyJustSwung || bot.getHealth() > foe.getHealth() + 6) {
                    melee(foe, chase);
                } else {
                    stopShooting();
                    Vec3 to = foe.position().subtract(bot.position());
                    Vec3 side = new Vec3(-to.z, 0, to.x).normalize();
                    Vec3 back = to.normalize().scale(dist < 3.4 ? -0.7 : 0.1);
                    Vec3 m = side.add(back);
                    move(m.x, m.z, 0.9);
                    lookAt(foe.getEyePosition(), 1.0);
                }
            }
        }
        // Stay inside the pit (leaving it loses the duel).
        ActivityDesign.Area in = Duels.pitOf(bot);
        if (in != null) {
            double cx = (in.x1() + in.x2() + 1) / 2.0, cz = (in.z1() + in.z2() + 1) / 2.0;
            if (Math.abs(bot.getX() - cx) > ActivityDesign.DUEL_HALF - 1.5 || Math.abs(bot.getZ() - cz) > ActivityDesign.DUEL_HALF - 1.5)
                move(cx - bot.getX(), cz - bot.getZ(), 1.0);
        }
    }
}
