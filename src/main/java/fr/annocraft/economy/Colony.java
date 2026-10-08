package fr.annocraft.economy;

import java.util.*;

/** What the maritime and diplomatic simulations need from the colony. Implemented by the server save data and by tests. */
public interface Colony {
    ColonyEconomy economy();
    Diplomacy diplomacy();
    Maritime maritime();
    Geography geography();
    /** Islands owned by the player that have at least one storage building. */
    Set<String> ports();
    boolean hasShipyard(String island);
    /** Attack strength of defensive buildings on a player island. */
    int defense(String island);
    void event(Event event);
    /** Other companies' ships on the same sea (competitive games), whose company they belong to, and whether we are at war with it. */
    default Collection<Maritime.Ship> foreignShips() { return List.of(); }
    default String companyOf(Maritime.Ship ship) { return null; }
    default boolean atWar(String company) { return false; }
    /** Another company's ship sunk by ours. */
    default void sinkForeign(Maritime.Ship ship) { }
    /** Where ships really are: the sea and the quays of each world. Null in abstract simulations (tests without a sea). */
    default Navigation navigation() { return null; }
    interface Navigation {
        /** Navigable water of a world and half its width (the region's bounds). */
        SeaRoutes.Water water(String world);
        int half(String world);
        /** Mooring {x, z, heading x, heading z, out x, out z} of the n-th ship at an island's harbour. */
        double[] dock(String island, int slot);
    }

    /**
     * A notification for every player. Arguments starting with '#' are translation keys, others are literal text.
     * @param good whether the news is good (green) or bad (red)
     */
    record Event(String key, List<String> args, boolean good) {
        public static Event of(boolean good, String key, String... args) { return new Event(key, List.of(args), good); }
    }
}
