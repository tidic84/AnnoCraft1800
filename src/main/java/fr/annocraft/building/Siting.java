package fr.annocraft.building;

import fr.annocraft.world.IslandLayout;

/**
 * Site geometry shared by the server's checks and the client's preview. Coastal buildings stand half on the
 * shore and half over the sea, as Anno's harbour buildings do: the template's front row (z = 0, the street side)
 * on dry land at the deck's height, its back row over water, and the deck carried by a quay or piles in between.
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
        for (int lx = 0; lx < width; lx++) for (int lz = 0; lz < depth; lz++) {
            int[] q = turn(width, depth, rotation, lx, lz);
            int h = l.height(x + q[0], z + q[1]);
            if (h > y) return "invalid_terrain";
            if (lz == 0 && h != y) return "coast_land";
            if (lz == depth - 1 && h >= IslandLayout.SEA_LEVEL) return "coast_water";
        }
        return null;
    }
    public static String coast(IslandLayout l, BuildingDefinition def, int x, int y, int z, int rotation) { return coast(l, def.width(), def.depth(), x, y, z, rotation); }
    /** Height of the deck for a coastal building whose corner is at (x, z): the ground under its front row. */
    public static int deck(IslandLayout l, int width, int depth, int x, int z, int rotation) {
        int y = IslandLayout.SEA_LEVEL + 1;
        for (int lx = 0; lx < width; lx++) { int[] q = turn(width, depth, rotation, lx, 0); y = Math.max(y, l.height(x + q[0], z + q[1])); }
        return y;
    }
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
