package fr.annocraft.server;

import fr.annocraft.building.BuildingInstance;
import fr.annocraft.economy.ColonyEconomy;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import java.util.*;

/**
 * The islands' nature: what construction may clear (trees, plants, loose rock), felling whole trees as Anno does
 * when a building or a road goes through a forest, and the forest that lumberjacks need around them.
 */
public final class Nature {
    /** Trees within this radius of a lumberjack's centre feed it; this many give full productivity. */
    public static final int FOREST_RADIUS = 11, FOREST_TREES = 12;
    private Nature() { }

    /** Blocks that grow or lie on the land and give way to construction. */
    public static boolean natural(BlockState s) {
        return s.is(BlockTags.LOGS) || s.is(BlockTags.LEAVES) || s.is(BlockTags.FLOWERS) || s.is(BlockTags.REPLACEABLE)
                || s.is(BlockTags.SAPLINGS) || s.is(Blocks.BROWN_MUSHROOM) || s.is(Blocks.RED_MUSHROOM) || s.is(Blocks.LARGE_FERN)
                || s.is(Blocks.STONE) || s.is(Blocks.ANDESITE) || s.is(Blocks.COBBLESTONE) || s.is(Blocks.TUFF) || s.is(BlockTags.COAL_ORES)
                || s.is(BlockTags.IRON_ORES) || s.is(BlockTags.GOLD_ORES) || s.is(Blocks.PACKED_MUD) || s.is(Blocks.SMOOTH_SANDSTONE);
    }
    /** Removes a natural block; a tree trunk takes the whole tree (connected logs and nearby leaves) with it. */
    public static void clear(ServerLevel level, BlockPos pos) {
        BlockState s = level.getBlockState(pos);
        if (!natural(s)) return;
        if (!s.is(BlockTags.LOGS)) { remove(level, pos); return; }
        Deque<BlockPos> open = new ArrayDeque<>(List.of(pos)); Set<BlockPos> seen = new HashSet<>(open);
        List<BlockPos> logs = new ArrayList<>();
        while (!open.isEmpty() && logs.size() < 64) {
            BlockPos p = open.poll();
            if (!level.getBlockState(p).is(BlockTags.LOGS)) continue;
            logs.add(p);
            for (BlockPos n : BlockPos.betweenClosed(p.offset(-1, -1, -1), p.offset(1, 1, 1)))
                if (seen.add(n.immutable()) && level.getBlockState(n).is(BlockTags.LOGS)) open.add(n.immutable());
        }
        for (BlockPos log : logs) {
            for (BlockPos n : BlockPos.betweenClosed(log.offset(-3, -1, -3), log.offset(3, 3, 3)))
                if (level.getBlockState(n).is(BlockTags.LEAVES)) remove(level, n);
            remove(level, log);
        }
    }
    private static void remove(ServerLevel level, BlockPos pos) {
        BlockState s = level.getBlockState(pos);
        if (s.is(Blocks.LARGE_FERN) || s.is(Blocks.TALL_GRASS)) {
            BlockPos other = level.getBlockState(pos.above()).is(s.getBlock()) ? pos.above() : pos.below();
            if (level.getBlockState(other).is(s.getBlock())) level.setBlock(other, Blocks.AIR.defaultBlockState(), 2 | 16);
        }
        level.setBlock(pos, Blocks.AIR.defaultBlockState(), 2 | 16);
        CameraSessions.blockChanged(level, pos);
    }
    /** Trees (trunks standing on soil) around a point. */
    public static int trees(ServerLevel level, double cx, double cz, int y) {
        int count = 0;
        for (int x = (int) cx - FOREST_RADIUS; x <= cx + FOREST_RADIUS; x++) for (int z = (int) cz - FOREST_RADIUS; z <= cz + FOREST_RADIUS; z++) {
            if ((x - cx) * (x - cx) + (z - cz) * (z - cz) > FOREST_RADIUS * FOREST_RADIUS || !level.hasChunk(x >> 4, z >> 4)) continue;
            for (int k = y - 2; k <= y + 3; k++) {
                BlockPos p = new BlockPos(x, k, z);
                if (level.getBlockState(p).is(BlockTags.LOGS) && level.getBlockState(p.below()).is(BlockTags.DIRT)) { count++; break; }
            }
        }
        return count;
    }
    /** Refreshes the forest factor of every lumberjack whose surroundings are loaded. */
    public static void tick(MinecraftServer server, ColonyData data) {
        for (BuildingInstance b : data.buildings().values()) {
            if (!b.definition().getPath().equals("lumberjack")) continue;
            ServerLevel level = server.getLevel(fr.annocraft.AnnoCraft.dimension(ColonyEconomy.worldOf(b.island())));
            if (level == null || !BuildingService.loaded(level, b.origin().offset(-FOREST_RADIUS, 0, -FOREST_RADIUS), b.width() + 2 * FOREST_RADIUS, b.depth() + 2 * FOREST_RADIUS)) continue;
            int trees = trees(level, b.origin().getX() + b.width() / 2.0, b.origin().getZ() + b.depth() / 2.0, b.origin().getY() + 1);
            data.colonyOf(b).economy().setNature(b.id(), Math.min(1, trees / (double) FOREST_TREES));
        }
    }
}
