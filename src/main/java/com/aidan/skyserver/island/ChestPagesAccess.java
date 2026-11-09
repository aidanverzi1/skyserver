package com.aidan.skyserver.island;

/**
 * 5.10: added to every chest block entity by ChestPagesMixin. A chest can have up to
 * ChestPages.MAX_PAGES extra pages of 27 slots, saved with the chest.
 */
public interface ChestPagesAccess {
    int skyserver$getPages();

    /** Grows (or on load, sets) the chest's slots to 27 x (1 + pages). Items already inside stay put. */
    void skyserver$setPages(int pages);
}
