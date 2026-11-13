package com.aidan.skyserver.expedition;

import com.aidan.skyserver.island.Islands;
import com.aidan.skyserver.island.WrenRoom;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/**
 * The Nether Expedition's "deck" - where the crew gathers before setting sail and comes back to.
 *
 * 5.8 - 5.19 this was an airship built in the sky above the island. 5.20: the airship is gone. The deck is
 * now Captain Wren's ship room on the island (WrenRoom; islands without one yet: the space around Wren).
 * Nothing is built or removed any more; this class only answers "is this player on the deck?" and
 * "where do people stand?" so the rest of the Nether Expedition works as before.
 */
public final class ExpeditionShip {
    /** Kept for old saved entities: villagers with this tag (the airship's Wren) are no longer made. */
    public static final String WREN_TAG = "skyserver_shipwren";

    /** The deck of one island. */
    public static final class Ship {
        public final int islandId;
        public final ServerLevel level;
        public final BlockPos center;

        Ship(int islandId, ServerLevel level, BlockPos center) {
            this.islandId = islandId;
            this.level = level;
            this.center = center;
        }

        private Islands.Island island() {
            for (Islands.Island is : Islands.all()) if (is.id == islandId) return is;
            return null;
        }

        /** Where people stand when they come aboard (the ship room's landing spot). */
        public double[] deckSpot() {
            Islands.Island is = island();
            return is == null ? new double[]{center.getX() + 0.5, center.getY(), center.getZ() + 0.5, 180} : WrenRoom.landing(is);
        }

        /** True if (x, y, z) is inside the ship room (the crew's gathering spot). */
        public boolean onDeck(double x, double y, double z) {
            Islands.Island is = island();
            return is != null && WrenRoom.inStaging(is, x, y, z);
        }
    }

    private ExpeditionShip() {}

    /** 5.20: nothing on the islands is an airship any more. */
    public static boolean isShipBlock(BlockPos pos) {
        return false;
    }

    public static boolean isRope(BlockPos pos) {
        return false;
    }

    /** The deck for this island (never null: the ship room is always there to gather in). */
    public static Ship place(ServerLevel level, Islands.Island is) {
        double[] l = WrenRoom.landing(is);
        return new Ship(is.id, level, BlockPos.containing(l[0], l[1], l[2]));
    }

    /** Nothing to take down any more. */
    public static void remove(Ship ship) {
    }
}
