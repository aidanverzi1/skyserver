package com.aidan.skyserver.pet;

import com.aidan.skyserver.Safe;
import com.aidan.skyserver.Skyserver;
import com.aidan.skyserver.island.Islands;
import com.aidan.skyserver.lobby.Economy;
import com.aidan.skyserver.lobby.Lobby;
import com.aidan.skyserver.lobby.Mailbox;
import com.aidan.skyserver.lobby.Stacks;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FarmlandBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * 5.24b: the Little Snack Planter - a block of farmland with a pie growing on it.
 *
 *  - Place it on your island (max 3 per island). The pie on top grows bigger as it grows; it grows in REAL time,
 *    even with nobody online or the server off (2 hours, -15 minutes per island upgrade, 3 upgrades).
 *  - Left-click the pie when it's ready: 3 Little Snacks (pies) and it starts again. Left-click early: how long is left.
 *    Crouch + left-click the pie, or break the farmland: the planter goes straight to your inventory (or /claim if
 *    it's full) and keeps its growth.
 *  - The first one comes from Captain Wren's quest (step SNACK_PLANT_STEP; players past it get it the next time they
 *    talk to him). After that, the island can buy a 2nd ($15,000) and a 3rd ($30,000) in the /is menu. Grow-time
 *    upgrades in /is too ($15,000 / $30,000 / $50,000). Both are per island (shared by the crew).
 *  - The pie is an item_display entity, the click box an interaction entity (both summoned by command and tagged).
 *    Their ids are saved, so the same entities are kept when a chunk loads again; anything tagged that isn't ours
 *    is removed. The farmland is kept wet so it never turns to dirt, and nothing can be placed on top of it.
 */
public final class Planters {
    private Planters() {}

    public static final String ITEM_TAG = "pet_planter", ENTITY_TAG = "skyserver_planter";
    public static final int MAX_PER_ISLAND = 3;
    public static final long[] BUY_PRICES = {15_000, 30_000};
    public static final long[] SPEED_PRICES = {15_000, 30_000, 50_000};
    static final long BASE_GROW_MS = 2 * 60 * 60 * 1000L, UPGRADE_MS = 15 * 60 * 1000L;
    static final int STAGES = 8;

    static final class Planter {
        UUID placer;
        int island;
        long pos;
        long start; // epoch ms when this growth began
        UUID display, click;
        // not saved
        Entity displayE, clickE;
        int shownStage = -1;
    }

    static final List<Planter> PLANTERS = new ArrayList<>();
    /** island id -> {extra planters bought, grow-time upgrades} */
    static final Map<Integer, int[]> ISLAND = new HashMap<>();
    private static int summonSeq = 0;

    // =====================================================================
    // Saving (inside pets.json)
    // =====================================================================

    static JsonObject save() {
        JsonObject o = new JsonObject();
        JsonArray a = new JsonArray();
        for (Planter pl : PLANTERS) {
            JsonObject j = new JsonObject();
            j.addProperty("placer", pl.placer.toString());
            j.addProperty("island", pl.island);
            j.addProperty("pos", pl.pos);
            j.addProperty("start", pl.start);
            if (pl.display != null) j.addProperty("display", pl.display.toString());
            if (pl.click != null) j.addProperty("click", pl.click.toString());
            a.add(j);
        }
        o.add("list", a);
        JsonObject isl = new JsonObject();
        for (Map.Entry<Integer, int[]> e : ISLAND.entrySet()) {
            JsonArray v = new JsonArray();
            v.add(e.getValue()[0]);
            v.add(e.getValue()[1]);
            isl.add(String.valueOf(e.getKey()), v);
        }
        o.add("islands", isl);
        return o;
    }

    static void load(JsonObject o) {
        PLANTERS.clear();
        ISLAND.clear();
        if (o == null) return;
        if (o.has("list")) for (JsonElement e : o.getAsJsonArray("list")) {
            JsonObject j = e.getAsJsonObject();
            Planter pl = new Planter();
            pl.placer = UUID.fromString(j.get("placer").getAsString());
            pl.island = j.get("island").getAsInt();
            pl.pos = j.get("pos").getAsLong();
            pl.start = j.get("start").getAsLong();
            if (j.has("display")) pl.display = UUID.fromString(j.get("display").getAsString());
            if (j.has("click")) pl.click = UUID.fromString(j.get("click").getAsString());
            PLANTERS.add(pl);
        }
        if (o.has("islands")) for (Map.Entry<String, JsonElement> e : o.getAsJsonObject("islands").entrySet()) {
            JsonArray v = e.getValue().getAsJsonArray();
            ISLAND.put(Integer.parseInt(e.getKey()), new int[]{v.get(0).getAsInt(), v.get(1).getAsInt()});
        }
    }

    static int[] islandData(int id) {
        return ISLAND.computeIfAbsent(id, k -> new int[]{0, 0});
    }

    // =====================================================================
    // Items and timing
    // =====================================================================

    /** A planter item; grown = ms of growth it already has (kept when picked up). */
    public static ItemStack item(long grown) {
        ItemStack s = new ItemStack(Items.FARMLAND);
        CompoundTag t = new CompoundTag();
        t.putString("skyserver", ITEM_TAG);
        t.putLong("pet_grown", Math.max(0, grown));
        s.set(DataComponents.CUSTOM_DATA, CustomData.of(t));
        s.set(DataComponents.MAX_STACK_SIZE, 1);
        Stacks.named(s, "§2§lLittle Snack Planter", "§7Place it on your island: a pie grows on it.",
                "§7Ready in §f2 hours §7(faster with §e/is §7upgrades),", "§7even while you're offline.",
                "§7Left-click the pie: §f3 Little Snacks§7.", "§7Crouch + left-click: pick it up.", "§8Max " + MAX_PER_ISLAND + " per island.");
        return s;
    }

    static long grownOf(ItemStack s) {
        CustomData cd = s.get(DataComponents.CUSTOM_DATA);
        return cd == null ? 0 : cd.copyTag().getLongOr("pet_grown", 0L);
    }

    static long growMs(int island) {
        return BASE_GROW_MS - UPGRADE_MS * Math.max(0, Math.min(SPEED_PRICES.length, islandData(island)[1]));
    }

    static double progress(Planter pl) {
        return Math.max(0, Math.min(1, (System.currentTimeMillis() - pl.start) / (double) growMs(pl.island)));
    }

    static String timeLeft(Planter pl) {
        long left = Math.max(0, pl.start + growMs(pl.island) - System.currentTimeMillis()) / 1000;
        return left >= 3600 ? (left / 3600) + "h " + (left % 3600 / 60) + "m" : Math.max(1, left / 60) + " min";
    }

    /** Into the inventory, or the island mailbox (/claim) if it doesn't fit or they aren't in an island world. */
    public static void giveOrClaim(ServerPlayer p, ItemStack s) {
        if (s.isEmpty()) return;
        if (Lobby.isIsland(p.level()) && p.getInventory().add(s) && s.isEmpty()) return;
        if (s.isEmpty()) return;
        Mailbox.send(p.level().getServer(), p.getUUID(), s);
        Lobby.msg(p, "§7(Your inventory is full - it's waiting for you: §e/claim§7.)");
    }

    // =====================================================================
    // The pie and click box
    // =====================================================================

    private static Entity summon(ServerLevel level, String type, double x, double y, double z, String nbtOpen) {
        String tag = ENTITY_TAG + "_" + (summonSeq++);
        String cmd = String.format(Locale.ROOT, "summon minecraft:%s %.3f %.3f %.3f %s,Tags:[\"%s\",\"%s\"]}", type, x, y, z, nbtOpen, ENTITY_TAG, tag);
        try {
            CommandSourceStack src = level.getServer().createCommandSourceStack().withLevel(level).withSuppressedOutput();
            level.getServer().getCommands().performPrefixedCommand(src, cmd);
        } catch (RuntimeException e) {
            Skyserver.LOGGER.warn("[Planter] summon threw {} for: {}", e.toString(), cmd);
            return null;
        }
        List<Entity> found = level.getEntitiesOfClass(Entity.class, new AABB(x, y, z, x, y, z).inflate(2), e -> e.entityTags().contains(tag));
        if (found.isEmpty()) Skyserver.LOGGER.warn("[Planter] entity did not appear: {}", cmd);
        return found.isEmpty() ? null : found.get(0);
    }

    private static void showPie(ServerLevel level, Planter pl, int stage) {
        if (pl.displayE != null && !pl.displayE.isRemoved()) pl.displayE.discard();
        BlockPos b = BlockPos.of(pl.pos);
        boolean ready = stage >= STAGES;
        double s = ready ? 0.9 : 0.2 + 0.08 * stage;
        String nbt = String.format(Locale.ROOT, "{item:{id:\"minecraft:pumpkin_pie\",count:1},billboard:\"vertical\",transformation:{left_rotation:[0f,0f,0f,1f],"
                + "right_rotation:[0f,0f,0f,1f],translation:[0f,0f,0f],scale:[%.2ff,%.2ff,%.2ff]}%s", s, s, s, ready ? ",Glowing:1b,glow_color_override:16766720" : "");
        Entity e = summon(level, "item_display", b.getX() + 0.5, b.getY() + 0.94 + s / 2, b.getZ() + 0.5, nbt);
        pl.displayE = e;
        pl.display = e == null ? null : e.getUUID();
        pl.shownStage = stage;
        Companion.dirty();
    }

    private static void showClickBox(ServerLevel level, Planter pl) {
        if (pl.clickE != null && !pl.clickE.isRemoved()) pl.clickE.discard();
        BlockPos b = BlockPos.of(pl.pos);
        Entity e = summon(level, "interaction", b.getX() + 0.5, b.getY() + 0.94, b.getZ() + 0.5, "{width:0.9f,height:0.8f,response:1b");
        pl.clickE = e;
        pl.click = e == null ? null : e.getUUID();
        Companion.dirty();
    }

    private static void removeEntities(Planter pl) {
        if (pl.displayE != null && !pl.displayE.isRemoved()) pl.displayE.discard();
        if (pl.clickE != null && !pl.clickE.isRemoved()) pl.clickE.discard();
        pl.displayE = pl.clickE = null;
    }

    private static Planter byPos(BlockPos pos) {
        long k = pos.asLong();
        for (Planter pl : PLANTERS) if (pl.pos == k) return pl;
        return null;
    }

    private static Planter byEntity(Entity e) {
        UUID id = e.getUUID();
        for (Planter pl : PLANTERS) if (id.equals(pl.click) || id.equals(pl.display)) return pl;
        return null;
    }

    static int countOn(int island) {
        int n = 0;
        for (Planter pl : PLANTERS) if (pl.island == island) n++;
        return n;
    }

    // =====================================================================
    // Place, harvest, pick up
    // =====================================================================

    private static InteractionResult place(ServerPlayer p, ServerLevel level, BlockPos clicked, net.minecraft.core.Direction face, ItemStack held) {
        BlockPos at = level.getBlockState(clicked).canBeReplaced() ? clicked : clicked.relative(face);
        Islands.Island is = Islands.islandAt(at.getX(), at.getZ());
        if (!Islands.isIsland(level) || is == null || !is.isMember(p.getUUID())) {
            Lobby.msg(p, "§7Little Snack Planters go on your own island.");
            return InteractionResult.FAIL;
        }
        if (!level.getBlockState(at).canBeReplaced() || !level.getBlockState(at.above()).isAir()) {
            Lobby.msg(p, "§7There's no room there (it needs an empty block above it too).");
            return InteractionResult.FAIL;
        }
        if (countOn(is.id) >= MAX_PER_ISLAND) {
            Lobby.msg(p, "§cYour island already has " + MAX_PER_ISLAND + " Little Snack Planters (the most allowed).");
            return InteractionResult.FAIL;
        }
        long grown = grownOf(held);
        level.setBlock(at, Blocks.FARMLAND.defaultBlockState().setValue(FarmlandBlock.MOISTURE, 7), Block.UPDATE_ALL);
        Planter pl = new Planter();
        pl.placer = p.getUUID();
        pl.island = is.id;
        pl.pos = at.asLong();
        pl.start = System.currentTimeMillis() - grown;
        PLANTERS.add(pl);
        if (!p.isCreative()) held.shrink(1);
        showClickBox(level, pl);
        showPie(level, pl, stage(pl));
        level.playSound(null, at, SoundEvents.CROP_PLANTED, SoundSource.BLOCKS, 1f, 1f);
        Companion.dirty();
        Lobby.msg(p, "§2Planter placed! §7The pie is ready in §f" + timeLeft(pl) + "§7 (it grows even while you're offline).");
        return InteractionResult.SUCCESS;
    }

    private static int stage(Planter pl) {
        double pr = progress(pl);
        return pr >= 1 ? STAGES : (int) (pr * STAGES);
    }

    private static void pickUp(ServerPlayer p, ServerLevel level, Planter pl) {
        long grown = Math.min(growMs(pl.island), System.currentTimeMillis() - pl.start);
        PLANTERS.remove(pl);
        removeEntities(pl);
        BlockPos b = BlockPos.of(pl.pos);
        if (level.getBlockState(b).is(Blocks.FARMLAND) || level.getBlockState(b).is(Blocks.DIRT)) level.setBlock(b, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        Companion.dirty();
        giveOrClaim(p, item(grown));
        level.playSound(null, b, SoundEvents.ITEM_PICKUP, SoundSource.PLAYERS, 1f, 1f);
        Lobby.msg(p, "§7Picked up your Little Snack Planter §8(its growth is kept)§7.");
    }

    private static void leftClick(ServerPlayer p, ServerLevel level, Planter pl) {
        BlockPos b = BlockPos.of(pl.pos);
        if (!Islands.canBuild(p, b)) {
            com.aidan.skyserver.lobby.Announce.actionBar(p, "§7That's someone else's planter.");
            return;
        }
        if (p.isShiftKeyDown()) {
            pickUp(p, level, pl);
            return;
        }
        if (progress(pl) < 1) {
            com.aidan.skyserver.lobby.Announce.actionBar(p, "§2Little Snack Planter: §f" + (int) (progress(pl) * 100) + "% §7- ready in §f" + timeLeft(pl)
                    + " §8(crouch + left-click to pick it up)");
            return;
        }
        pl.start = System.currentTimeMillis();
        showPie(level, pl, 0);
        giveOrClaim(p, PetItems.snack(PetItems.SNACKS_PER_HARVEST));
        level.playSound(null, b, SoundEvents.ITEM_PICKUP, SoundSource.PLAYERS, 1f, 1.2f);
        level.sendParticles(ParticleTypes.HAPPY_VILLAGER, b.getX() + 0.5, b.getY() + 1.3, b.getZ() + 0.5, 8, 0.3, 0.2, 0.3, 0);
        Lobby.msg(p, "§a+" + PetItems.SNACKS_PER_HARVEST + " Little Snacks! §7A new pie starts growing (§f" + timeLeft(pl) + "§7).");
        Companion.dirty();
    }

    // =====================================================================
    // Every second
    // =====================================================================

    static void secondTick(MinecraftServer server) {
        if (PLANTERS.isEmpty()) return;
        ServerLevel level = Islands.level();
        if (level == null) return;
        for (Iterator<Planter> it = PLANTERS.iterator(); it.hasNext(); ) {
            Planter pl = it.next();
            BlockPos pos = BlockPos.of(pl.pos);
            if (!level.isLoaded(pos)) continue;
            BlockState st = level.getBlockState(pos);
            if (st.is(Blocks.DIRT) || st.is(Blocks.FARMLAND) && st.getValue(FarmlandBlock.MOISTURE) < 7) {
                level.setBlock(pos, Blocks.FARMLAND.defaultBlockState().setValue(FarmlandBlock.MOISTURE, 7), Block.UPDATE_CLIENTS);
            } else if (!st.is(Blocks.FARMLAND)) {
                // Gone some other way (explosion, island reset...): the planter isn't lost.
                it.remove();
                removeEntities(pl);
                Companion.dirty();
                ServerPlayer owner = server.getPlayerList().getPlayer(pl.placer);
                if (owner != null) {
                    giveOrClaim(owner, item(0));
                    Lobby.msg(owner, "§7Your Little Snack Planter was knocked out - you have it back.");
                } else Mailbox.send(server, pl.placer, item(0));
                continue;
            }
            // Wait a few seconds after the chunk loads so our saved entities can come back before making new ones.
            if (pl.clickE == null || pl.clickE.isRemoved()) {
                if (pl.click != null && level.getEntity(pl.click) != null) pl.clickE = level.getEntity(pl.click);
                else showClickBox(level, pl);
            }
            int stage = stage(pl);
            if (pl.displayE == null || pl.displayE.isRemoved()) {
                Entity old = pl.display == null ? null : level.getEntity(pl.display);
                if (old != null) {
                    pl.displayE = old;
                    if (pl.shownStage < 0) pl.shownStage = stage; // assume it was right when saved; fixed below if not
                } else showPie(level, pl, stage);
            }
            if (stage != pl.shownStage) showPie(level, pl, stage);
            if (stage >= STAGES && server.getTickCount() % 60 < 20)
                level.sendParticles(ParticleTypes.HAPPY_VILLAGER, pos.getX() + 0.5, pos.getY() + 1.4, pos.getZ() + 0.5, 2, 0.25, 0.2, 0.25, 0);
        }
    }

    // =====================================================================
    // Captain Wren and the /is menu
    // =====================================================================

    /** Wren's quest step: the first planter (once per player). */
    public static void wrenGift(ServerPlayer p) {
        Companion.Pet pet = Companion.PETS.get(p.getUUID());
        if (pet == null || pet.wrenPlanter) return;
        pet.wrenPlanter = true;
        Companion.dirty();
        giveOrClaim(p, item(0));
        Lobby.msg(p, "§6[Captain Wren] §fHere - a §2Little Snack Planter§f. Set it down on your island and it'll bake pies for your chicken, even while you sleep!");
    }

    /** Talking to Wren: players already past that quest step get the planter now. */
    public static void wrenCatchUp(ServerPlayer p, Islands.Island is) {
        if (is != null && is.isMember(p.getUUID()) && is.quest > PetItems.SNACK_PLANT_STEP) wrenGift(p);
    }

    public static String buy(ServerPlayer p, Islands.Island is) {
        if (is.quest <= PetItems.SNACK_PLANT_STEP) return "§7Finish Captain Wren's quest #" + PetItems.SNACK_PLANT_STEP + " first - he gives you the first planter.";
        int[] d = islandData(is.id);
        if (d[0] >= BUY_PRICES.length) return "§7Your island has bought all the extra planters it can (" + MAX_PER_ISLAND + " in total).";
        long price = BUY_PRICES[d[0]];
        if (!Economy.take(p.level().getServer(), p.getUUID(), price)) return "§cThat costs " + Economy.format(price) + ".";
        d[0]++;
        Companion.dirty();
        giveOrClaim(p, item(0));
        return "§aBought a Little Snack Planter for " + Economy.format(price) + "!";
    }

    public static String upgrade(ServerPlayer p, Islands.Island is) {
        int[] d = islandData(is.id);
        if (d[1] >= SPEED_PRICES.length) return "§7Your planters already grow as fast as they can.";
        long price = SPEED_PRICES[d[1]];
        if (!Economy.take(p.level().getServer(), p.getUUID(), price)) return "§cThat costs " + Economy.format(price) + ".";
        d[1]++;
        Companion.dirty();
        return "§aPlanter upgrade " + d[1] + "! §7Pies now take §f" + (growMs(is.id) / 60_000) + " minutes§7.";
    }

    public static ItemStack buyIcon(Islands.Island is) {
        int[] d = islandData(is.id);
        boolean max = d[0] >= BUY_PRICES.length;
        return Stacks.named(new ItemStack(Items.PUMPKIN_PIE), "§2§lLittle Snack Planter",
                "§7Grows pies (Little Snacks) for your chicken.", "§7Your island has bought §f" + d[0] + "§7/" + BUY_PRICES.length + " extra (max " + MAX_PER_ISLAND + " placed).",
                is.quest <= PetItems.SNACK_PLANT_STEP ? "§cFirst one: Captain Wren's quest #" + PetItems.SNACK_PLANT_STEP : max ? "§a(all bought)" : "§7Price: §6" + Economy.format(BUY_PRICES[d[0]]),
                max || is.quest <= PetItems.SNACK_PLANT_STEP ? "" : "§eClick to buy");
    }

    public static ItemStack upgradeIcon(Islands.Island is) {
        int[] d = islandData(is.id);
        boolean max = d[1] >= SPEED_PRICES.length;
        return Stacks.named(new ItemStack(Items.CLOCK), "§2§lPlanter Speed " + d[1],
                "§7Pies take §f" + (growMs(is.id) / 60_000) + " minutes" + (max ? " §a(max)" : ""),
                max ? "" : "§7Next: §f" + ((growMs(is.id) - UPGRADE_MS) / 60_000) + " minutes §7for §6" + Economy.format(SPEED_PRICES[d[1]]),
                max ? "" : "§eClick to upgrade");
    }

    // =====================================================================
    // Listeners
    // =====================================================================

    static void init() {
        UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
            if (level.isClientSide() || !(player instanceof ServerPlayer sp)) return InteractionResult.PASS;
            ItemStack held = player.getItemInHand(hand);
            if (Stacks.is(held, ITEM_TAG)) {
                InteractionResult[] out = {InteractionResult.FAIL};
                Safe.run("planter place", () -> out[0] = place(sp, (ServerLevel) level, hit.getBlockPos(), hit.getDirection(), held));
                return out[0];
            }
            if (PLANTERS.isEmpty() || !Islands.isIsland(level)) return InteractionResult.PASS;
            // Nothing goes on top of a planter (it would turn the farmland to dirt).
            BlockPos c = hit.getBlockPos();
            BlockPos at = level.getBlockState(c).canBeReplaced() ? c : c.relative(hit.getDirection());
            if (!held.isEmpty() && byPos(at.below()) != null) return InteractionResult.FAIL;
            return InteractionResult.PASS;
        });
        PlayerBlockBreakEvents.BEFORE.register((level, player, pos, state, be) -> {
            if (level.isClientSide() || !(player instanceof ServerPlayer sp) || PLANTERS.isEmpty()) return true;
            Planter pl = byPos(pos);
            if (pl == null) return true;
            Safe.run("planter break", () -> pickUp(sp, (ServerLevel) level, pl));
            return false;
        });
        AttackEntityCallback.EVENT.register((player, level, hand, entity, hit) -> {
            if (level.isClientSide() || !entity.entityTags().contains(ENTITY_TAG)) return InteractionResult.PASS;
            Planter pl = byEntity(entity);
            if (pl != null && player instanceof ServerPlayer sp) Safe.run("planter click", () -> leftClick(sp, (ServerLevel) level, pl));
            return InteractionResult.FAIL;
        });
        UseEntityCallback.EVENT.register((player, level, hand, entity, hit) -> {
            if (level.isClientSide() || !entity.entityTags().contains(ENTITY_TAG)) return InteractionResult.PASS;
            Planter pl = byEntity(entity);
            if (pl != null && hand == InteractionHand.MAIN_HAND && player instanceof ServerPlayer sp)
                com.aidan.skyserver.lobby.Announce.actionBar(sp, progress(pl) >= 1 ? "§aReady! §7Left-click the pie to collect 3 Little Snacks."
                        : "§2Little Snack Planter: §f" + (int) (progress(pl) * 100) + "% §7- ready in §f" + timeLeft(pl));
            return InteractionResult.FAIL;
        });
        // A tagged pie / click box that isn't one of ours (left over from a crash) goes; ours are picked back up.
        ServerEntityEvents.ENTITY_LOAD.register((entity, world) -> {
            if (!entity.entityTags().contains(ENTITY_TAG)) return;
            Lobby.runLaterTicks(1, () -> Safe.run("planter entity load", () -> {
                if (entity.isRemoved()) return;
                Planter pl = byEntity(entity);
                if (pl == null) {
                    entity.discard();
                    return;
                }
                if (entity.getUUID().equals(pl.click)) {
                    if (pl.clickE != null && pl.clickE != entity && !pl.clickE.isRemoved()) entity.discard();
                    else pl.clickE = entity;
                } else {
                    if (pl.displayE != null && pl.displayE != entity && !pl.displayE.isRemoved()) entity.discard();
                    else pl.displayE = entity;
                }
            }));
        });
    }
}
