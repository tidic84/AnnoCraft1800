package fr.annocraft.economy;

import fr.annocraft.world.IslandLayout;
import java.util.*;

/** Island positions of both worlds, used for sailing times. Pure data derived from the seed. */
public record Geography(Map<String, IslandLayout.Island> islands, Map<String, String> worlds) {
    /** Sailing between the Old and New World crosses the open ocean: a fixed passage at schooner speed. */
    public static final double OCEAN_PASSAGE = 300;
    public static Geography of(Collection<IslandLayout> layouts) {
        Map<String, IslandLayout.Island> islands = new LinkedHashMap<>(); Map<String, String> worlds = new HashMap<>();
        for (IslandLayout layout : layouts) for (IslandLayout.Island i : layout.islands()) { islands.put(i.id(), i); worlds.put(i.id(), layout.world()); }
        return new Geography(Collections.unmodifiableMap(islands), Collections.unmodifiableMap(worlds));
    }
    public boolean exists(String island) { return islands.containsKey(island); }
    public String world(String island) { return worlds.getOrDefault(island, ColonyEconomy.worldOf(island)); }
    /** Seconds needed by a ship of the given speed (blocks per second) to sail between two islands. */
    public double travelSeconds(String from, String to, double speed) {
        IslandLayout.Island a = islands.get(from), b = islands.get(to);
        if (a == null || b == null || from.equals(to)) return 5;
        if (!world(from).equals(world(to))) return OCEAN_PASSAGE * 6 / speed;
        return 10 + Math.hypot(a.x() - b.x(), a.z() - b.z()) / speed;
    }
    /** A point on the island's coast facing the destination, for drawing ships. */
    public double[] harbour(String island, String toward) {
        IslandLayout.Island a = islands.get(island); if (a == null) return new double[]{0, 0};
        IslandLayout.Island b = toward == null ? null : islands.get(toward);
        double angle = b == null || !world(island).equals(world(toward)) ? a.phase() : Math.atan2(b.z() - a.z(), b.x() - a.x());
        return new double[]{a.x() + Math.cos(angle) * a.radiusX() * 1.12, a.z() + Math.sin(angle) * a.radiusZ() * 1.12};
    }
}
