package fr.annocraft.world;

import fr.annocraft.client.CameraMath;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class IslandLayoutTest {
    @Test void threeSeedsHaveEightSeparatedBoundedBuildableIslands() {
        for (long seed : new long[]{0, 1800, -719331L}) {
            IslandLayout layout = new IslandLayout(seed, 4096, 8);
            assertEquals(8, layout.islands().size());
            assertEquals(8, layout.islands().stream().map(IslandLayout.Island::id).distinct().count());
            for (IslandLayout.Island a : layout.islands()) {
                assertTrue(a.buildable(a.x(), a.z())); assertEquals(73, layout.height(a.x(), a.z()));
                assertTrue(layout.inBounds(a.x() + a.radiusX(), a.z() + a.radiusZ()));
                assertTrue(layout.inBounds(a.x() - a.radiusX(), a.z() - a.radiusZ()));
                for (IslandLayout.Island b : layout.islands()) if (a != b)
                    assertTrue(Math.hypot(a.x() - b.x(), a.z() - b.z()) > 1.12 * (Math.max(a.radiusX(), a.radiusZ()) + Math.max(b.radiusX(), b.radiusZ())));
            }
        }
    }
    @Test void generationIsIndependentOfQueryOrder() {
        IslandLayout a = new IslandLayout(1800, 4096, 8), b = new IslandLayout(1800, 4096, 8);
        assertEquals(a.islands(), b.islands());
        List<int[]> points = new ArrayList<>(); Random random = new Random(2);
        for (int i = 0; i < 10000; i++) points.add(new int[]{random.nextInt(4096) - 2048, random.nextInt(4096) - 2048});
        int[] expected = points.stream().mapToInt(p -> a.height(p[0], p[1])).toArray();
        List<Integer> order = new ArrayList<>(); for (int i = 0; i < points.size(); i++) order.add(i);
        Collections.shuffle(order, random);
        for (int i : order) assertEquals(expected[i], b.height(points.get(i)[0], points.get(i)[1]));
    }
    @Test void eachIslandHasAFlatCoastalFootprint() {
        for (long seed : new long[]{0, 1800, -719331L}) {
            IslandLayout l = new IslandLayout(seed, 4096, 8);
            for (IslandLayout.Island island : l.islands()) {
                boolean found = false;
                for (int x = island.x() - island.radiusX(); x < island.x() + island.radiusX() && !found; x += 2)
                    for (int z = island.z() - island.radiusZ(); z < island.z() + island.radiusZ() && !found; z += 2) {
                        boolean flat = true;
                        for (int dx : new int[]{0, 5, 10}) for (int dz : new int[]{0, 4, 8}) if (l.height(x + dx, z + dz) != 66) flat = false;
                        if (!flat) continue;
                        for (int dx = -16; dx < 27 && !found; dx++) for (int dz = -16; dz < 25; dz++)
                            if (l.height(x + dx, z + dz) < IslandLayout.SEA_LEVEL) { found = true; break; }
                    }
                assertTrue(found, "Coastal plot missing for " + island.id() + " seed=" + seed);
            }
        }
    }
    @Test void settingsAndOceanBoundsAreExplicit() {
        assertThrows(IllegalArgumentException.class, () -> new IslandLayout(0, 100, 8));
        assertThrows(IllegalArgumentException.class, () -> new IslandLayout(0, 4096, 33));
        IslandLayout l = new IslandLayout(0, 4096, 8);
        assertEquals(40, l.height(2048, 0)); assertFalse(l.inBounds(Integer.MIN_VALUE, 0));
        assertNotEquals(l.islands(), new IslandLayout(1, 4096, 8).islands());
    }
    @Test void cameraMathClampsZoomAndPreservesPanLength() {
        assertEquals(10, CameraMath.zoom(-100)); assertEquals(90, CameraMath.zoom(200));
        double[] v = CameraMath.rotateCoords(3, 4, 137);
        assertEquals(5, Math.hypot(v[0], v[1]), 1e-9);
        double[] back = CameraMath.rotateCoords(v[0], v[1], -137);
        assertEquals(3, back[0], 1e-9); assertEquals(4, back[1], 1e-9);
    }
}
