package fr.annocraft.world;

import java.util.*;

/** Pure, seed-based geometry. No chunk state or shared random number generator. */
public final class IslandLayout {
    public static final int SEA_LEVEL = 64;
    public record Island(String id, int x, int z, int radiusX, int radiusZ,
                         double phase, String fertility, String deposit) {
        public double distance(double px, double pz) {
            double dx = (px - x) / radiusX, dz = (pz - z) / radiusZ;
            double angle = Math.atan2(dz, dx);
            double coast = 1 + .07 * Math.sin(angle * 3 + phase) + .04 * Math.cos(angle * 5 - phase);
            return Math.sqrt(dx * dx + dz * dz) / coast;
        }
        public boolean buildable(int px, int pz) { return distance(px, pz) < .73; }
    }
    private final long seed;
    private final int size;
    private final List<Island> islands;

    public IslandLayout(long seed, int size, int count) {
        if (size < 1024 || size > 16384 || count < 1 || count > 32) throw new IllegalArgumentException("Invalid archipelago settings");
        this.seed = seed;
        this.size = size;
        Random random = new Random(seed);
        int grid = (int) Math.ceil(Math.sqrt(count));
        double cell = (double) size / grid;
        if (cell < 512) throw new IllegalArgumentException("Region too small for this island count; each island cell requires at least 512 blocks");
        List<Island> result = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            int x = (int) (-size / 2.0 + cell * (i % grid + .5) + (random.nextDouble() - .5) * cell * .12);
            int z = (int) (-size / 2.0 + cell * (i / grid + .5) + (random.nextDouble() - .5) * cell * .12);
            int rx = (int) (cell * (.23 + random.nextDouble() * .055));
            int rz = (int) (cell * (.23 + random.nextDouble() * .055));
            result.add(new Island("island_" + (i + 1), x, z, rx, rz, random.nextDouble() * Math.PI * 2,
                    i % 2 == 0 ? "grain" : "potato", i % 3 == 0 ? "iron" : "clay"));
        }
        islands = List.copyOf(result);
    }
    public long seed() { return seed; }
    public int size() { return size; }
    public List<Island> islands() { return islands; }
    public boolean inBounds(int x, int z) { return Math.abs((long)x) < size / 2 && Math.abs((long)z) < size / 2; }
    public Optional<Island> islandAt(int x, int z) {
        return islands.stream().filter(i -> i.distance(x, z) <= 1.1).findFirst();
    }
    public int height(int x, int z) {
        if (!inBounds(x, z)) return 40;
        Optional<Island> candidate = islandAt(x, z);
        if (candidate.isEmpty()) return 40;
        Island island = candidate.get();
        double d = island.distance(x, z);
        if (d <= .73) return 73;
        if (d < .79) return (int) Math.round(73 - (d - .73) / .06 * 7);
        // A broad, flat beach shelf makes coastal structures possible without terraforming.
        if (d <= .90) return 66;
        double t = Math.min(1, (d - .90) / .20);
        return (int) Math.round(66 - 26 * t);
    }
}
