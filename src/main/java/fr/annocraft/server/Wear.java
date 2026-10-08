package fr.annocraft.server;

import fr.annocraft.world.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The ground along roads, worn by traffic: trodden grass next to them, fading through worn grass into the meadow.
 * Plots are never worn around: their beaten earth stops at their edge and marks out each building's land. Purely cosmetic; it grows back by itself once the plot or road is gone (AnnoBlocks).
 */
public final class Wear {
    private Wear() { }
    /** Wears the meadow in {@code rings} rings around the rectangle (x0, z0, w, d) of made ground at height y, sparing the cells {@code spare} accepts. */
    public static void around(ServerLevel level, int x0, int z0, int w, int d, int y, int rings, java.util.function.BiPredicate<Integer, Integer> spare) {
        for (int x = x0 - rings; x < x0 + w + rings; x++) for (int z = z0 - rings; z < z0 + d + rings; z++) {
            int ring = Math.max(Math.max(x0 - x, x - (x0 + w - 1)), Math.max(z0 - z, z - (z0 + d - 1)));
            if (ring <= 0 || spare.test(x, z)) continue;
            double roll = IslandLayout.hash(x, y, z, 77), share = (double) ring / (rings + 1);
            // Near the made ground most cells are trodden; farther out, fewer and lighter.
            int target = roll < 1 - share * .9 ? (roll < .6 - share * .8 ? 2 : 1) : 0;
            if (target == 0) continue;
            for (int k = 1; k >= -1; k--) {
                BlockPos pos = new BlockPos(x, y + k, z);
                if (!level.isLoaded(pos)) break;
                BlockState s = level.getBlockState(pos);
                int current = AnnoBlocks.wear(s);
                if (current < 0) { if (!s.isAir() && !Nature.natural(s)) break; continue; }
                BlockState above = level.getBlockState(pos.above());
                if (!above.isAir() && !Nature.natural(above)) break;
                if (target > current) { level.setBlock(pos, AnnoBlocks.worn(target), 2); CameraSessions.blockChanged(level, pos); }
                break;
            }
        }
    }
    /** Gives back its grass to the strip around a new plot, so the plot's edge stays sharp even beside a worn road verge. */
    public static void clean(ServerLevel level, int x0, int z0, int w, int d, int y) {
        for (int x = x0 - 1; x <= x0 + w; x++) for (int z = z0 - 1; z <= z0 + d; z++) {
            if (x >= x0 && x < x0 + w && z >= z0 && z < z0 + d) continue;
            for (int k = 1; k >= -1; k--) {
                BlockPos pos = new BlockPos(x, y + k, z);
                if (level.isLoaded(pos) && AnnoBlocks.wear(level.getBlockState(pos)) > 0) { level.setBlock(pos, AnnoBlocks.worn(0), 2); CameraSessions.blockChanged(level, pos); }
            }
        }
    }
}
