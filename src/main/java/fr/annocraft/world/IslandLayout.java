package fr.annocraft.world;

import java.util.*;

/** Pure, seed-based geometry. No chunk state or shared random number generator. */
public final class IslandLayout {
    public static final int SEA_LEVEL = 64;
    public static final String OLD_WORLD = "old", NEW_WORLD = "new";
    private static final String[] OLD_FERTILITIES = {"grain", "potato", "hops", "grapes"};
    private static final String[] OLD_DEPOSITS = {"clay", "iron", "quartz", "coal"};
    private static final String[] NEW_FERTILITIES = {"plantain", "sugar", "coffee", "tobacco"};
    private static final String[] NEW_DEPOSITS = {"gold", "clay"};
    /** Fertility and deposit lists are comma-separated, e.g. "grain,potato". */
    public record Island(String id, int x, int z, int radiusX, int radiusZ,
                         double phase, String fertility, String deposit) {
        public double distance(double px, double pz) {
            double dx = (px - x) / radiusX, dz = (pz - z) / radiusZ;
            double angle = Math.atan2(dz, dx);
            double coast = 1 + .07 * Math.sin(angle * 3 + phase) + .04 * Math.cos(angle * 5 - phase);
            return Math.sqrt(dx * dx + dz * dz) / coast;
        }
        public boolean buildable(int px, int pz) { return distance(px, pz) < .73; }
        public boolean hasFertility(String f) { return Arrays.asList(fertility.split(",")).contains(f); }
        public boolean hasDeposit(String d) { return Arrays.asList(deposit.split(",")).contains(d); }
    }
    private final long seed;
    private final int size;
    private final String world;
    private final List<Island> islands;

    public IslandLayout(long seed, int size, int count) { this(seed, size, count, OLD_WORLD); }
    public IslandLayout(long seed, int size, int count, String world) {
        if (size < 1024 || size > 16384 || count < 1 || count > 32) throw new IllegalArgumentException("Invalid archipelago settings");
        if (!OLD_WORLD.equals(world) && !NEW_WORLD.equals(world)) throw new IllegalArgumentException("Unknown world " + world);
        this.seed = seed;
        this.size = size;
        this.world = world;
        boolean old = OLD_WORLD.equals(world);
        // The New World uses a derived seed so its islands differ from the Old World's.
        Random random = new Random(old ? seed : seed ^ 0x4E57_1800L);
        int grid = (int) Math.ceil(Math.sqrt(count));
        double cell = (double) size / grid;
        if (cell < 512) throw new IllegalArgumentException("Region too small for this island count; each island cell requires at least 512 blocks");
        String[] fertilities = old ? OLD_FERTILITIES : NEW_FERTILITIES, deposits = old ? OLD_DEPOSITS : NEW_DEPOSITS;
        List<Island> result = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            int x = (int) (-size / 2.0 + cell * (i % grid + .5) + (random.nextDouble() - .5) * cell * .12);
            int z = (int) (-size / 2.0 + cell * (i / grid + .5) + (random.nextDouble() - .5) * cell * .12);
            int rx = (int) (cell * (.23 + random.nextDouble() * .055));
            int rz = (int) (cell * (.23 + random.nextDouble() * .055));
            double phase = random.nextDouble() * Math.PI * 2;
            // Resources come from a separate stream so island geometry stays identical to earlier versions.
            Random resources = new Random(seed * 31 + i + (old ? 0 : 977));
            int f = i == 0 ? 0 : resources.nextInt(fertilities.length), d = i == 0 ? 0 : resources.nextInt(deposits.length);
            String fertility = fertilities[f] + "," + fertilities[(f + 1) % fertilities.length];
            String deposit = deposits[d] + (i == 0 || resources.nextBoolean() ? "," + deposits[(d + 1) % deposits.length] : "");
            result.add(new Island((old ? "island_" : "nw_island_") + (i + 1), x, z, rx, rz, phase, fertility, deposit));
        }
        islands = List.copyOf(result);
    }
    public long seed() { return seed; }
    public int size() { return size; }
    public String world() { return world; }
    public List<Island> islands() { return islands; }
    public Optional<Island> island(String id) { return islands.stream().filter(i -> i.id().equals(id)).findFirst(); }
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
