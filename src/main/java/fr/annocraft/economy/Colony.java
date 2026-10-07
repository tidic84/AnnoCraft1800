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

    /**
     * A notification for every player. Arguments starting with '#' are translation keys, others are literal text.
     * @param good whether the news is good (green) or bad (red)
     */
    record Event(String key, List<String> args, boolean good) {
        public static Event of(boolean good, String key, String... args) { return new Event(key, List.of(args), good); }
    }
}
