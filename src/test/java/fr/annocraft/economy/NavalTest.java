package fr.annocraft.economy;

import fr.annocraft.world.IslandLayout;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Ships as units on a real sea: lanes around islands, free sailing, hunting and enemy fleets. */
class NavalTest {
    /** The seed's sea of both worlds, quays off each island's coast. */
    static final class SeaColony implements Colony {
        final ColonyEconomy economy = new ColonyEconomy();
        final Diplomacy diplomacy = new Diplomacy();
        final Maritime maritime = new Maritime();
        final IslandLayout old = new IslandLayout(1800, 4096, 8), neu = new IslandLayout(1800, 4096, 8, IslandLayout.NEW_WORLD);
        final Geography geography = Geography.of(List.of(old, neu));
        final Set<String> ports = new TreeSet<>(Set.of("island_1", "island_2"));
        final List<Event> events = new ArrayList<>();
        SeaColony() { diplomacy.claimHomes(geography); diplomacy.setRandom(new Random(1)); maritime.setRandom(new Random(1)); economy.setSandbox(true); }
        public ColonyEconomy economy() { return economy; }
        public Diplomacy diplomacy() { return diplomacy; }
        public Maritime maritime() { return maritime; }
        public Geography geography() { return geography; }
        public Set<String> ports() { return ports; }
        public boolean hasShipyard(String island) { return "island_1".equals(island); }
        public int defense(String island) { return 0; }
        public void event(Event event) { events.add(event); }
        final SeaRoutes.Water oldSea = (x, z) -> old.inBounds(x, z) && old.height(x, z) < IslandLayout.SEA_LEVEL - 1, newSea = (x, z) -> neu.inBounds(x, z) && neu.height(x, z) < IslandLayout.SEA_LEVEL - 1;
        @Override public Navigation navigation() {
            return new Navigation() {
                public SeaRoutes.Water water(String world) { return world.equals(IslandLayout.NEW_WORLD) ? newSea : oldSea; }
                public int half(String world) { return 2048 - 16; }
                public double[] dock(String island, int slot) { return fr.annocraft.building.Harbours.dock(List.of(), id -> null, geography, island, slot); }
            };
        }
    }

    @Test void lanesGoAroundIslandsOverWater() {
        IslandLayout l = new IslandLayout(1800, 4096, 8);
        SeaRoutes.Water water = (x, z) -> l.inBounds(x, z) && l.height(x, z) < IslandLayout.SEA_LEVEL - 1;
        IslandLayout.Island i = l.islands().get(0);
        // From one side of the island to the other: the straight line would cross it.
        double ax = i.x() - i.radiusX() * 1.3, bx = i.x() + i.radiusX() * 1.3;
        List<double[]> path = SeaRoutes.path(water, 2032, ax, i.z(), bx, i.z());
        assertTrue(path.size() > 2, "Lane did not bend around the island");
        double length = SeaRoutes.length(path);
        assertTrue(length > bx - ax && length < (bx - ax) * 3, "Unexpected lane length " + length);
        for (double t = 0; t <= 1; t += .005) {
            double[] p = SeaRoutes.along(path, t);
            assertTrue(l.height((int) Math.floor(p[0]), (int) Math.floor(p[1])) < IslandLayout.SEA_LEVEL, "Lane crosses land at " + p[0] + ", " + p[1]);
        }
    }

    @Test void shipsSailWhereTheyAreSentAndHuntPirates() {
        SeaColony c = new SeaColony();
        assertNull(c.maritime.build(c, "island_1", "frigate"));
        Maritime.Ship ship = c.maritime.fleet().get(0);
        assertEquals(IslandLayout.OLD_WORLD, ship.world, "Ship not moored in its world");
        double[] dock = c.navigation().dock("island_1", 0);
        assertEquals(dock[0], ship.x, 1e-6);
        // Free sailing to a point of the open sea.
        double tx = ship.x + dock[4] * 120, tz = ship.z + dock[5] * 120;
        assertEquals("not_water", c.maritime.sailTo(c, ship.id, c.geography.islands().get("island_1").x(), c.geography.islands().get("island_1").z()));
        assertNull(c.maritime.sailTo(c, ship.id, tx, tz));
        for (int i = 0; i < 120 && "goto".equals(ship.order); i++) c.maritime.step(c, 1);
        assertEquals("idle", ship.order);
        assertEquals(tx, ship.x, 1); assertEquals(tz, ship.z, 1);
        // The corsairs are at war: their warship comes out, and ours hunts it down.
        Maritime.Ship pirate = null;
        for (int i = 0; i < 400 && pirate == null; i++) {
            c.maritime.step(c, 1);
            pirate = c.maritime.ships().stream().filter(s -> "corsairs".equals(s.owner) && IslandLayout.OLD_WORLD.equals(s.world)).findFirst().orElse(null);
        }
        assertNotNull(pirate, "No pirate warship mustered");
        assertTrue(c.maritime.fleet().size() == 1 && c.maritime.upkeep() == Maritime.type("frigate").upkeep(), "Enemy ships counted as the player's");
        ship.hp = 1e6;
        assertNull(c.maritime.hunt(c, ship.id, pirate.id));
        UUID prey = pirate.id;
        for (int i = 0; i < 3000 && c.maritime.ship(prey) != null; i++) c.maritime.step(c, 1);
        assertNull(c.maritime.ship(prey), "The hunted pirate was not sunk");
        assertTrue(c.diplomacy.victories() > 0);
        // Peace sends the rivals home.
        Maritime m = Maritime.load(c.maritime.save());
        assertEquals(c.maritime.save(), m.save(), "Positions and lanes changed in a save round trip");
    }
}
