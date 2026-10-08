package fr.annocraft.building;

import fr.annocraft.world.IslandLayout;

/**
 * Site geometry shared by the server's checks and the client's preview. Coastal buildings stand half on the
 * shore and half over the sea, as Anno's harbour buildings do: the template's front rows (z = 0 is the street side)
 * mostly on dry land, its back rows mostly over water, and the deck carried by a bank, a quay or piles below it.
 * Sizes are the template's own (unturned) width and depth.
 */
public final class Siting {
    private Siting() { }
    /** World offset {x, z} of the template cell (lx, lz) once turned; matches StructureTemplate rotation. */
    public static int[] turn(int width, int depth, int rotation, int lx, int lz) {
        return switch (rotation & 3) {
            case 1 -> new int[]{depth - 1 - lz, lx};
            case 2 -> new int[]{width - 1 - lx, depth - 1 - lz};
            case 3 -> new int[]{lz, width - 1 - lx};
            default -> new int[]{lx, lz};
        };
    }
    public static int[] turn(BuildingDefinition def, int rotation, int lx, int lz) { return turn(def.width(), def.depth(), rotation, lx, lz); }
    /**
     * Coastal site check for a deck at height {@code y} with its footprint corner at (x, z).
     * @return null when the site fits, otherwise a message key suffix
     */
    public static String coast(IslandLayout l, int width, int depth, int x, int y, int z, int rotation) {
        int[] s = survey(l, width, depth, x, y, z, rotation);
        if (s == null) return "invalid_terrain";
        int land = landRows(depth);
        // Majorities rather than exact rows, so a harbour also fits a coast running at a slant across its footprint.
        if (s[0] * 2 < width || s[2] * 2 < width * land) return "coast_land";
        if (s[1] * 2 < width || s[3] * 2 < width * (depth - land)) return "coast_water";
        return null;
    }
    public static String coast(IslandLayout l, BuildingDefinition def, int x, int y, int z, int rotation) { return coast(l, def.width(), def.depth(), x, y, z, rotation); }
    /** Rows of a coastal template, from its front, that stand on the shore; the rest reaches out over the sea. */
    public static int landRows(int depth) { return Math.max(1, Math.round(depth * .4f)); }
    /**
     * Counts {front row dry, back row wet, shore rows dry, quay rows wet} for a deck at height {@code y}; null when
     * the ground rises above the deck or the deck stands too high over the sea.
     */
    private static int[] survey(IslandLayout l, int width, int depth, int x, int y, int z, int rotation) {
        if (y > IslandLayout.SEA_LEVEL + 4) return null;
        int land = landRows(depth); int[] s = new int[4];
        for (int lx = 0; lx < width; lx++) for (int lz = 0; lz < depth; lz++) {
            int[] q = turn(width, depth, rotation, lx, lz);
            int h = l.height(x + q[0], z + q[1]);
            if (h > y) return null;
            boolean dry = h >= IslandLayout.SEA_LEVEL;
            if (lz == 0 && dry) s[0]++;
            if (lz == depth - 1 && !dry) s[1]++;
            if (lz < land && dry) s[2]++;
            if (lz >= land && !dry) s[3]++;
        }
        return s;
    }
    /** Height of the deck for a coastal building whose corner is at (x, z): the highest ground under it, lower shore banked up. */
    public static int deck(IslandLayout l, int width, int depth, int x, int z, int rotation) {
        int y = IslandLayout.SEA_LEVEL + 1;
        for (int lx = 0; lx < width; lx++) for (int lz = 0; lz < depth; lz++) { int[] q = turn(width, depth, rotation, lx, lz); y = Math.max(y, l.height(x + q[0], z + q[1])); }
        return y;
    }
    /**
     * The coastal site {x, z, rotation} nearest to fitting around the point (x, z), its footprint centred there:
     * among the valid sites within {@code reach} blocks, the one whose shore rows lie on land and quay rows on
     * water most exactly, the nearest and the {@code preferred} rotation first among equals; null if none fits.
     */
    public static int[] snap(IslandLayout l, int width, int depth, int x, int z, int preferred, int reach) {
        int[] best = null; double bestScore = Double.NEGATIVE_INFINITY;
        for (int k = 0; k < 4; k++) {
            int r = (preferred + k) & 3, w = (r & 1) == 0 ? width : depth, d = (r & 1) == 0 ? depth : width;
            for (int dx = -reach; dx <= reach; dx++) for (int dz = -reach; dz <= reach; dz++) {
                int ox = x - w / 2 + dx, oz = z - d / 2 + dz, y = deck(l, width, depth, ox, oz, r);
                if (coast(l, width, depth, ox, y, oz, r) != null) continue;
                int[] s = survey(l, width, depth, ox, y, oz, r);
                double score = s[2] + s[3] - Math.hypot(dx, dz) * .3 - (k == 0 ? 0 : .5);
                if (score > bestScore) { bestScore = score; best = new int[]{ox, oz, r}; }
            }
        }
        return best;
    }
    public static int[] snap(IslandLayout l, BuildingDefinition def, int x, int z, int preferred, int reach) { return snap(l, def.width(), def.depth(), x, z, preferred, reach); }
    public static int deck(IslandLayout l, BuildingDefinition def, int x, int z, int rotation) { return deck(l, def.width(), def.depth(), x, z, rotation); }
    /** First rotation, starting from {@code preferred}, that fits the coast here; -1 if none. */
    public static int coastRotation(IslandLayout l, int width, int depth, int x, int z, int preferred) {
        for (int k = 0; k < 4; k++) {
            int r = (preferred + k) & 3;
            if (coast(l, width, depth, x, deck(l, width, depth, x, z, r), z, r) == null) return r;
        }
        return -1;
    }
    public static int coastRotation(IslandLayout l, BuildingDefinition def, int x, int z, int preferred) { return coastRotation(l, def.width(), def.depth(), x, z, preferred); }
}
