package com.aidan.skyserver.lobby;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 5.13: the new cosmetic kinds as plain lists (no Minecraft classes, so Progression and the offline
 * balance sim stay plain Java). The effects and what players wear are in CosmeticsPlus.
 */
public final class CosmeticLists {
    private CosmeticLists() {}

    /** id, display, rarity, exclusive (0 shop+chest, 1 Legendary chest only, 2 stamp book only). */
    public static final String[][] CAGES = {
            {"cage_oak", "Oak Cage", "0", "0"}, {"cage_ice", "Ice Cage", "0", "0"}, {"cage_moss", "Mossy Stone Cage", "0", "0"},
            {"cage_honey", "Honey Cage", "1", "0"}, {"cage_amethyst", "Amethyst Cage", "1", "0"}, {"cage_nether", "Nether Brick Cage", "1", "0"},
            {"cage_gold", "Gold & Diamond Cage", "2", "0"}, {"cage_crowsnest", "Voyager's Crow's Nest", "2", "2"}};
    public static final String[][] BED_FX = {
            {"bedfx_feather", "Feather Puff", "0", "0"}, {"bedfx_splash", "Splash", "0", "0"}, {"bedfx_note", "Note Burst", "0", "0"},
            {"bedfx_lightning", "Lightning", "1", "0"}, {"bedfx_firework", "Firework", "1", "0"}, {"bedfx_ender", "Ender Swirl", "1", "0"},
            {"bedfx_dragon", "Dragon's Roar", "2", "0"}, {"bedfx_cannon", "Wren's Cannon Volley", "2", "2"}};
    public static final String[][] COLORS = {
            {"color_red", "§cRed Name", "0", "0"}, {"color_gold", "§6Gold Name", "0", "0"}, {"color_yellow", "§eYellow Name", "0", "0"},
            {"color_green", "§aGreen Name", "0", "0"}, {"color_aqua", "§bAqua Name", "0", "0"}, {"color_pink", "§dPink Name", "0", "0"},
            {"color_crimson", "§4Crimson Name", "1", "0"}, {"color_ocean", "§3Ocean Name", "1", "0"},
            {"color_royal", "§5Royal Name", "1", "0"}, {"color_midnight", "§9Midnight Name", "1", "0"},
            {"color_rainbow", "§c§lR§6§la§e§li§a§ln§b§lb§d§lo§5§lw Name", "2", "0"}};
    /** 5.13 Voyage stamp-book titles and trail (only from the stamp book). */
    public static final String[][] VOYAGE_EXTRAS = {
            {"title_v_deckhand", "§3Voyage Deckhand"}, {"title_v_navigator", "§b§lVoyage Navigator"}, {"trail_voyage", "Voyage Trail"}};

    /** Chat colour code(s) for a name colour ("" if not one / rainbow handled by Ranks). */
    public static String colorCode(String id) {
        if (id == null) return "";
        return switch (id) {
            case "color_red" -> "§c";
            case "color_gold" -> "§6";
            case "color_yellow" -> "§e";
            case "color_green" -> "§a";
            case "color_aqua" -> "§b";
            case "color_pink" -> "§d";
            case "color_crimson" -> "§4";
            case "color_ocean" -> "§3";
            case "color_royal" -> "§5";
            case "color_midnight" -> "§9";
            default -> "";
        };
    }

    public static boolean isCage(String id) {
        return id != null && id.startsWith("cage_");
    }

    public static boolean isBedFx(String id) {
        return id != null && id.startsWith("bedfx_");
    }

    public static boolean isColor(String id) {
        return id != null && id.startsWith("color_");
    }

    /** True for the three 5.13 kinds handled here. */
    public static boolean isExtraKind(String id) {
        return isCage(id) || isBedFx(id) || isColor(id);
    }

    public static List<String[]> all() {
        List<String[]> out = new ArrayList<>();
        out.addAll(Arrays.asList(CAGES));
        out.addAll(Arrays.asList(BED_FX));
        out.addAll(Arrays.asList(COLORS));
        return out;
    }

    /** Display name for the new kinds ("" if not one of them). */
    public static String name(String id) {
        for (String[] c : CAGES) if (c[0].equals(id)) return "§b" + c[1] + " §7(Skywars cage)";
        for (String[] c : BED_FX) if (c[0].equals(id)) return "§c" + c[1] + " §7(bed-break effect)";
        for (String[] c : COLORS) if (c[0].equals(id)) return c[1] + " §7(name colour)";
        for (String[] c : VOYAGE_EXTRAS) if (c[0].equals(id)) return c[0].startsWith("trail_") ? "§a" + c[1] : c[1];
        return "";
    }
}
