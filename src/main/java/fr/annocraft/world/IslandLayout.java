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
            // The first resource cycles through the pool so every fertility and deposit exists whatever the seed.
            int f = i % fertilities.length, d = i % deposits.length;
            int f2 = i == 0 ? 1 : (f + 1 + resources.nextInt(fertilities.length - 1)) % fertilities.length;
            String fertility = fertilities[f] + "," + fertilities[f2];
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
    // ------------------------------------------------------------------------------------------- natural resources

    /** Mine slot of a natural deposit: a flat 9 × 9 site centred on (x, z), its rocky hill behind it. */
    public record Deposit(String type, String island, int x, int z) {
        public boolean mountain() { return !type.equals("clay") && !type.equals("quartz"); }
    }
    /** Half-size of a deposit's flat slot; mines must cover its centre. */
    public static final int SLOT = 4;
    /** Radius of the town clearing around each island's centre, kept free of forest. */
    public static final int CLEARING = 44;
    private final Map<String, List<Deposit>> deposits = new HashMap<>();

    /** Deposit sites of an island: three per deposit kind it has, on the plateau, spaced apart. */
    public List<Deposit> deposits(Island island) {
        synchronized (deposits) { return deposits.computeIfAbsent(island.id(), k -> placeDeposits(island)); }
    }
    private List<Deposit> placeDeposits(Island island) {
        List<Deposit> result = new ArrayList<>();
        Random random = new Random(seed * 7919 + island.id().hashCode());
        for (String type : island.deposit().split(",")) {
            int placed = 0;
            for (int attempt = 0; attempt < 200 && placed < 3; attempt++) {
                double angle = random.nextDouble() * Math.PI * 2, reach = .3 + random.nextDouble() * .32;
                int x = (int) Math.round(island.x() + Math.cos(angle) * island.radiusX() * reach);
                int z = (int) Math.round(island.z() + Math.sin(angle) * island.radiusZ() * reach);
                if (island.distance(x, z) > .6 || Math.hypot(x - island.x(), z - island.z()) < CLEARING + 16) continue;
                if (result.stream().anyMatch(d -> Math.hypot(d.x() - x, d.z() - z) < 34)) continue;
                result.add(new Deposit(type, island.id(), x, z)); placed++;
            }
        }
        return List.copyOf(result);
    }
    /** Deposits whose slot or hill may touch the given rectangle. */
    public List<Deposit> depositsNear(int x0, int z0, int x1, int z1, int margin) {
        List<Deposit> result = new ArrayList<>();
        for (Island island : islands) {
            if (island.x() + island.radiusX() < x0 - margin || island.x() - island.radiusX() > x1 + margin
                    || island.z() + island.radiusZ() < z0 - margin || island.z() - island.radiusZ() > z1 + margin) continue;
            for (Deposit d : deposits(island))
                if (d.x() >= x0 - margin && d.x() <= x1 + margin && d.z() >= z0 - margin && d.z() <= z1 + margin) result.add(d);
        }
        return result;
    }
    /** Whether a footprint covers the centre of a deposit of this kind, as mines must. */
    public boolean depositIn(String type, int x, int z, int width, int depth) {
        for (Deposit d : depositsNear(x, z, x + width - 1, z + depth - 1, 0))
            if (d.type().equals(type) && d.x() >= x && d.x() < x + width && d.z() >= z && d.z() < z + depth) return true;
        return false;
    }

    /** Stateless block hash in [0, 1), shared by the world generator and the client's distant view. */
    public static double hash(int x, int y, int z, int salt) {
        long h = x * 0x9E3779B97F4A7C15L ^ y * 0xC2B2AE3D27D4EB4FL ^ z * 0x165667B19E3779F9L ^ salt * 0x27D4EB2F165667C5L;
        h ^= h >>> 33; h *= 0xff51afd7ed558ccdL; h ^= h >>> 33;
        return (h >>> 11) * 0x1.0p-53;
    }
    /** Height of a deposit's hill (iron, coal, gold) or rim (clay, quartz) above the plateau at this column; 0 elsewhere. */
    public int hill(int x, int z) {
        int best = 0;
        for (Deposit d : depositsNear(x, z, x, z, 13)) best = Math.max(best, hill(d, x, z));
        return best;
    }
    /** Hill height of one deposit; the generator stacks rock up to it. */
    public int hill(Deposit d, int x, int z) {
        int dx = x - d.x(), dz = z - d.z(); double r = Math.hypot(dx, dz), roll = hash(x, 0, z, 9);
        if (Math.abs(dx) <= SLOT && Math.abs(dz) <= SLOT) return 0;
        if (!d.mountain()) return r > 5.5 && r <= 7.5 && roll < .5 ? 1 : 0;
        if (r > 12.5) return 0;
        Island island = island(d.island()).orElseThrow();
        double tx = island.x() - d.x(), tz = island.z() - d.z(), tl = Math.max(1, Math.hypot(tx, tz));
        double facing = (dx * tx / tl + dz * tz / tl) / r;
        // The hill rises behind the slot, away from the island centre, so the mine faces the town.
        if (facing >= .25) return 0;
        return (int) Math.max(0, Math.min(9, Math.round((12.5 - r) * .8 + roll * 1.6 - Math.max(0, facing) * 3)));
    }

    // ------------------------------------------------------------------------------------------- vegetation

    /** A tree of the generated forests: its kind ("oak", "birch", "spruce", "jungle", "acacia") and trunk height. */
    public record Tree(String kind, int height) { }
    private static long mix(long h) { h ^= h >>> 33; h *= 0xff51afd7ed558ccdL; h ^= h >>> 33; h *= 0xc4ceb9fe1a85ec53L; h ^= h >>> 33; return h; }
    private double random(int x, int z, int salt) { return (mix(seed * 31 + x * 0x9E3779B97F4A7C15L + z * 0xC2B2AE3D27D4EB4FL + salt) >>> 11) * 0x1.0p-53; }
    private double noise(int x, int z, double scale, int salt) {
        double fx = x / scale, fz = z / scale; int ix = (int) Math.floor(fx), iz = (int) Math.floor(fz);
        double tx = fx - ix, tz = fz - iz; tx = tx * tx * (3 - 2 * tx); tz = tz * tz * (3 - 2 * tz);
        double a = random(ix, iz, salt), b = random(ix + 1, iz, salt), c = random(ix, iz + 1, salt), d = random(ix + 1, iz + 1, salt);
        return (a + (b - a) * tx) * (1 - tz) + (c + (d - c) * tx) * tz;
    }
    /** Forest density, 0 in meadows to about 1 in deep woods. */
    public double forest(int x, int z) {
        double n = noise(x, z, 46, 11) * .7 + noise(x, z, 13, 12) * .3;
        return Math.max(0, Math.min(1, (n - .42) / .2));
    }
    /** Where nothing grows: the sea, beaches, slopes, town clearings and deposit sites. */
    public boolean wild(int x, int z) {
        Optional<Island> island = islandAt(x, z);
        if (island.isEmpty() || island.get().distance(x, z) > .71) return false;
        Island i = island.get();
        if (Math.hypot(x - i.x(), z - i.z()) < CLEARING) return false;
        for (Deposit d : deposits(i)) if (Math.abs(d.x() - x) <= 13 && Math.abs(d.z() - z) <= 13) return false;
        return true;
    }
    /** The tree whose trunk stands on this column, if any: one candidate per 4 × 4 cell. */
    public Tree tree(int x, int z) {
        int cx = Math.floorDiv(x, 4), cz = Math.floorDiv(z, 4);
        if (x != cx * 4 + (int) (random(cx, cz, 21) * 4) || z != cz * 4 + (int) (random(cx, cz, 22) * 4)) return null;
        double density = forest(x, z) * .95 + .03;
        if (random(cx, cz, 23) >= density || !wild(x, z)) return null;
        double kind = noise(x, z, 60, 24), roll = random(x, z, 25);
        boolean old = OLD_WORLD.equals(world);
        String name = old ? (kind < .38 ? "spruce" : kind > .64 && roll < .7 ? "birch" : "oak") : (kind < .3 && roll < .6 ? "acacia" : "jungle");
        int height = switch (name) { case "spruce" -> 6 + (int) (roll * 3); case "birch" -> 5 + (int) (roll * 2); case "jungle" -> 6 + (int) (roll * 3); default -> 4 + (int) (roll * 2); };
        return new Tree(name, height);
    }
    /** Ground cover on a column without a tree: a block name, or null for bare grass. */
    public String plant(int x, int z) {
        if (!wild(x, z) && !(islandAt(x, z).map(i -> i.distance(x, z) <= .71).orElse(false))) return null;
        double r = random(x, z, 31), f = forest(x, z);
        boolean old = OLD_WORLD.equals(world);
        if (f > .3) return r < .16 ? "fern" : r < .34 ? "grass" : r < .36 ? (old ? "lily_of_the_valley" : "large_fern") : null;
        if (r < .2) return "grass";
        double patch = noise(x, z, 9, 32);
        if (patch > .72 && r < .45) {
            String[] flowers = old ? new String[]{"poppy", "dandelion", "cornflower", "oxeye_daisy", "azure_bluet"} : new String[]{"orange_tulip", "allium", "dandelion", "red_tulip"};
            return flowers[(int) (noise(x, z, 17, 33) * flowers.length * .999)];
        }
        return null;
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
        // The shelf drops into the sea within two blocks, so harbour buildings can stand half on the sand, half in the water.
        double t = Math.min(1, (d - .90) / .20), lip = Math.min(1, (d - .90) / .012);
        return (int) Math.round(66 - 3.6 * lip - 22.4 * t);
    }
}
