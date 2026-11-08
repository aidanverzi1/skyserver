package com.aidan.skyserver.bedwars;

/** The four Bedwars teams. Plain Java (the offline simulator uses it too). */
public enum BwTeam {
    RED("Red", "§c", "red", 0xB02E26),
    BLUE("Blue", "§9", "blue", 0x3C44AA),
    GREEN("Green", "§a", "lime", 0x5E9D1A),
    YELLOW("Yellow", "§e", "yellow", 0xF9D23C);

    public final String display;
    /** Chat colour code. */
    public final String color;
    /** Minecraft colour word for blocks: red_wool, lime_bed... */
    public final String dye;
    /** Leather armour colour. */
    public final int leather;

    BwTeam(String display, String color, String dye, int leather) {
        this.display = display;
        this.color = color;
        this.dye = dye;
        this.leather = leather;
    }

    public String colored() {
        return color + display;
    }

    /** One letter for the sidebar and name tags. */
    public String letter() {
        return color + display.charAt(0);
    }

    /** "red_wool", "lime_stained_glass"... */
    public String block(String kind) {
        return dye + "_" + kind;
    }

    /** Team i is placed by turning the map design i quarter-turns around the middle. */
    public int turns() {
        return ordinal();
    }
}
