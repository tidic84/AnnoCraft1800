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
    @Test void everyIslandHasHarbourSitesHalfInTheSea() {
        for (long seed : new long[]{0, 1800, -719331L}) for (String world : List.of(IslandLayout.OLD_WORLD, IslandLayout.NEW_WORLD)) {
            IslandLayout l = new IslandLayout(seed, 4096, 8, world);
            // Trading post and shipyard, fishery, harbour battery.
            for (int[] size : new int[][]{{11, 9}, {7, 7}, {5, 5}}) for (IslandLayout.Island island : l.islands()) {
                int sites = 0;
                for (int x = island.x() - island.radiusX() - 20; x < island.x() + island.radiusX() + 20 && sites < 3; x += 3)
                    for (int z = island.z() - island.radiusZ() - 20; z < island.z() + island.radiusZ() + 20 && sites < 3; z += 3)
                        if (fr.annocraft.building.Siting.coastRotation(l, size[0], size[1], x, z, 0) >= 0) sites++;
                assertTrue(sites >= 3, "Harbour site " + size[0] + "x" + size[1] + " missing for " + island.id() + " seed=" + seed);
            }
        }
    }
    @Test void depositSitesLieOnThePlateauAndForestsLeaveTownsFree() {
        for (long seed : new long[]{0, 1800, -719331L}) for (String world : List.of(IslandLayout.OLD_WORLD, IslandLayout.NEW_WORLD)) {
            IslandLayout l = new IslandLayout(seed, 4096, 8, world);
            for (IslandLayout.Island island : l.islands()) {
                List<IslandLayout.Deposit> sites = l.deposits(island);
                for (String type : island.deposit().split(","))
                    assertTrue(sites.stream().anyMatch(d -> d.type().equals(type)), island.id() + " has no " + type + " site, seed=" + seed);
                for (IslandLayout.Deposit d : sites) {
                    // The whole slot is flat plateau, and a 7 × 7 mine covering its centre fits inside.
                    for (int dx = -IslandLayout.SLOT; dx <= IslandLayout.SLOT; dx++) for (int dz = -IslandLayout.SLOT; dz <= IslandLayout.SLOT; dz++)
                        assertEquals(73, l.height(d.x() + dx, d.z() + dz));
                    assertTrue(l.depositIn(d.type(), d.x() - 3, d.z() - 3, 7, 7));
                    assertFalse(l.depositIn(d.type(), d.x() + 1, d.z() + 1, 7, 7));
                    assertNull(l.tree(d.x(), d.z()));
                }
                assertEquals(sites, new IslandLayout(seed, 4096, 8, world).deposits(island));
                for (int dx = -20; dx <= 20; dx++) for (int dz = -20; dz <= 20; dz++) assertNull(l.tree(island.x() + dx, island.z() + dz), "Tree in the town clearing");
                int trees = 0;
                for (int x = island.x() - island.radiusX(); x < island.x() + island.radiusX(); x += 1)
                    for (int z = island.z() - 40; z < island.z() + 40; z++) if (l.tree(x, z) != null) trees++;
                assertTrue(trees > 40, "Too few trees on " + island.id() + ": " + trees);
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
    @Test void cameraFootprintCoversWhatTheCameraSeesAndNothingBehind() {
        for (float yaw : new float[]{0, 135, 270}) for (float zoom : new float[]{CameraMath.MIN_ZOOM, CameraMath.DEFAULT_ZOOM, 150, CameraMath.MAX_ZOOM}) {
            List<double[]> quad = CameraMath.footprint(100, -50, yaw, zoom, 16 / 9.0, 176);
            assertTrue(CameraMath.inside(quad, 100, -50, 0), "Look-at point outside the footprint");
            double[] eye = CameraMath.eye(100, -50, yaw, zoom), f = CameraMath.forward(yaw, zoom);
            double fl = Math.hypot(f[0], f[2]);
            // Well behind the eye, on the ground: never loaded.
            assertFalse(CameraMath.inside(quad, eye[0] - f[0] / fl * 60, eye[2] - f[2] / fl * 60, 0), "Ground behind the camera is in its footprint");
            for (double[] c : quad) assertTrue(Math.hypot(c[0] - 100, c[1] + 50) <= 176 + 1e-6);
        }
        assertTrue(CameraMath.pitch(CameraMath.MAX_ZOOM) > CameraMath.pitch(150) && CameraMath.pitch(150) > CameraMath.pitch(CameraMath.DEFAULT_ZOOM));
    }
    @Test void cameraMathClampsZoomAndPreservesPanLength() {
        assertEquals(CameraMath.MIN_ZOOM, CameraMath.zoom(-100)); assertEquals(CameraMath.MAX_ZOOM, CameraMath.zoom(5000));
        double[] v = CameraMath.rotateCoords(3, 4, 137);
        assertEquals(5, Math.hypot(v[0], v[1]), 1e-9);
        double[] back = CameraMath.rotateCoords(v[0], v[1], -137);
        assertEquals(3, back[0], 1e-9); assertEquals(4, back[1], 1e-9);
    }
}
