package fr.annocraft.economy;

import java.util.*;

/**
 * Sea lanes: shortest paths over open water between two points of a world, around the islands. The sea is a grid
 * of {@link #CELL}-block cells searched with A*, then the path is straightened wherever the water allows a direct
 * line. Pure: water is a predicate (the seed's island layout on the server, a fake sea in tests).
 */
public final class SeaRoutes {
    public static final int CELL = 8;
    /** Navigable water at a block column. */
    public interface Water { boolean at(int x, int z); }
    private SeaRoutes() { }

    /**
     * Path from (x0, z0) to (x1, z1) as a list of {x, z} points, both ends included. Ends on land (a quay) are
     * reached from the nearest water. Falls back to the straight line when no path exists within the bounds.
     */
    public static List<double[]> path(Water water, int half, double x0, double z0, double x1, double z1) {
        List<double[]> result = new ArrayList<>();
        result.add(new double[]{x0, z0});
        // Open sea between the two: straight there, no search.
        if (clear(water, result.get(0), new double[]{x1, z1}, true, true)) { result.add(new double[]{x1, z1}); return result; }
        int[] a = nearestWater(water, half, cell(x0), cell(z0)), b = nearestWater(water, half, cell(x1), cell(z1));
        if (a != null && b != null && !(a[0] == b[0] && a[1] == b[1])) {
            List<int[]> cells = search(water, half, a, b);
            if (cells != null) for (int[] c : cells) result.add(new double[]{c[0] * CELL + CELL / 2.0, c[1] * CELL + CELL / 2.0});
        }
        result.add(new double[]{x1, z1});
        return straighten(water, result);
    }
    public static double length(List<double[]> path) {
        double total = 0;
        for (int i = 1; i < path.size(); i++) total += Math.hypot(path.get(i)[0] - path.get(i - 1)[0], path.get(i)[1] - path.get(i - 1)[1]);
        return total;
    }
    /** Point and heading {x, z, dx, dz} at fraction t of a path's length. */
    public static double[] along(List<double[]> path, double t) {
        if (path.isEmpty()) return new double[]{0, 0, 1, 0};
        if (path.size() == 1) return new double[]{path.get(0)[0], path.get(0)[1], 1, 0};
        double left = Math.max(0, Math.min(1, t)) * length(path);
        for (int i = 1; i < path.size(); i++) {
            double[] p = path.get(i - 1), q = path.get(i);
            double dx = q[0] - p[0], dz = q[1] - p[1], l = Math.hypot(dx, dz);
            if (left <= l || i == path.size() - 1) { double k = l < 1e-9 ? 0 : Math.min(1, left / l); return new double[]{p[0] + dx * k, p[1] + dz * k, dx, dz}; }
            left -= l;
        }
        return new double[]{path.get(0)[0], path.get(0)[1], 1, 0};
    }

    private static int cell(double v) { return (int) Math.floor(v / CELL); }
    /** A cell is open when its centre and corners are water, so ships keep clear of the shore. */
    private static final Map<Water, byte[]> GRIDS = Collections.synchronizedMap(new WeakHashMap<>());
    /** Open cells, remembered per sea: 0 not yet known, 1 open, 2 land or shore. */
    private static boolean open(Water water, int half, int cx, int cz) {
        int n = half * 2 / CELL + 2, ix = cx + n / 2, iz = cz + n / 2;
        if (ix < 0 || iz < 0 || ix >= n || iz >= n) return false;
        byte[] grid = GRIDS.computeIfAbsent(water, w -> new byte[n * n]);
        if (grid.length != n * n) return openCell(water, half, cx, cz);
        byte known = grid[ix * n + iz];
        if (known == 0) { known = openCell(water, half, cx, cz) ? (byte) 1 : (byte) 2; grid[ix * n + iz] = known; }
        return known == 1;
    }
    private static boolean openCell(Water water, int half, int cx, int cz) {
        int x = cx * CELL, z = cz * CELL;
        if (Math.abs(x) >= half || Math.abs(z) >= half || Math.abs(x + CELL) >= half || Math.abs(z + CELL) >= half) return false;
        return water.at(x + CELL / 2, z + CELL / 2) && water.at(x, z) && water.at(x + CELL - 1, z) && water.at(x, z + CELL - 1) && water.at(x + CELL - 1, z + CELL - 1);
    }
    private static int[] nearestWater(Water water, int half, int cx, int cz) {
        for (int r = 0; r <= 12; r++) for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++)
            if (Math.max(Math.abs(dx), Math.abs(dz)) == r && open(water, half, cx + dx, cz + dz)) return new int[]{cx + dx, cz + dz};
        return null;
    }
    private static long key(int x, int z) { return (long) x << 32 | (z & 0xffffffffL); }
    private static List<int[]> search(Water water, int half, int[] from, int[] to) {
        record Node(int x, int z, double f) { }
        PriorityQueue<Node> open = new PriorityQueue<>(Comparator.comparingDouble(Node::f));
        Map<Long, Double> cost = new HashMap<>(); Map<Long, Long> came = new HashMap<>();
        Map<Long, Boolean> water2 = new HashMap<>();
        long start = key(from[0], from[1]), goal = key(to[0], to[1]);
        cost.put(start, 0.0); open.add(new Node(from[0], from[1], Math.hypot(to[0] - from[0], to[1] - from[1])));
        int expanded = 0;
        while (!open.isEmpty() && expanded++ < 120_000) {
            Node n = open.poll(); long k = key(n.x, n.z);
            if (k == goal) {
                LinkedList<int[]> cells = new LinkedList<>();
                for (Long c = k; c != null; c = came.get(c)) cells.addFirst(new int[]{(int) (c >> 32), (int) (long) c});
                return cells;
            }
            double g = cost.get(k);
            if (n.f > g + Math.hypot(to[0] - n.x, to[1] - n.z) + 1e-6) continue;
            for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) continue;
                int nx = n.x + dx, nz = n.z + dz; long nk = key(nx, nz);
                if (!water2.computeIfAbsent(nk, kk -> open(water, half, nx, nz))) continue;
                // No cutting across a corner of land.
                int sx = n.x + dx, sz = n.z + dz;
                if (dx != 0 && dz != 0 && (!water2.computeIfAbsent(key(sx, n.z), kk -> open(water, half, sx, n.z))
                        || !water2.computeIfAbsent(key(n.x, sz), kk -> open(water, half, n.x, sz)))) continue;
                double ng = g + (dx != 0 && dz != 0 ? 1.4142 : 1);
                Double old = cost.get(nk);
                if (old != null && old <= ng) continue;
                cost.put(nk, ng); came.put(nk, k);
                open.add(new Node(nx, nz, ng + Math.hypot(to[0] - nx, to[1] - nz)));
            }
        }
        return null;
    }
    /** Drops every waypoint the ship can skip by sailing straight to a later one over water. */
    private static List<double[]> straighten(Water water, List<double[]> path) {
        if (path.size() <= 2) return path;
        List<double[]> out = new ArrayList<>(); out.add(path.get(0));
        int i = 0;
        while (i < path.size() - 1) {
            int j = path.size() - 1;
            // The ends may lie on a quay: only the open-sea part has to be clear.
            while (j > i + 1 && !clear(water, path.get(i), path.get(j), i == 0, j == path.size() - 1)) j--;
            out.add(path.get(j)); i = j;
        }
        return out;
    }
    private static boolean clear(Water water, double[] a, double[] b, boolean fromQuay, boolean toQuay) {
        double l = Math.hypot(b[0] - a[0], b[1] - a[1]);
        for (double s = 0; s <= l; s += 3) {
            if ((fromQuay && s < 14) || (toQuay && l - s < 14)) continue;
            double x = a[0] + (b[0] - a[0]) * s / l, z = a[1] + (b[1] - a[1]) * s / l;
            if (!water.at((int) Math.floor(x), (int) Math.floor(z)) || !water.at((int) Math.floor(x + 3), (int) Math.floor(z)) || !water.at((int) Math.floor(x), (int) Math.floor(z + 3))
                    || !water.at((int) Math.floor(x - 3), (int) Math.floor(z)) || !water.at((int) Math.floor(x), (int) Math.floor(z - 3))) return false;
        }
        return true;
    }
}
