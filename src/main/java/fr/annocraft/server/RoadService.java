package fr.annocraft.server;

import fr.annocraft.building.BuildingInstance;
import fr.annocraft.world.IslandLayout;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.*;
import net.minecraft.server.level.*;
import net.minecraft.world.level.block.Blocks;
import java.util.*;

/**
 * Roads are two blocks wide ground-level paths, as in Anno. They link buildings to the island's storage network.
 * Free to build. The dragged line is the road's spine; its second lane is laid wherever the ground allows it.
 */
public final class RoadService {
    public static final int MAX_LENGTH = 96;
    private RoadService() { }
    /** L-shaped spine: along X first, then along Z. */
    public static List<int[]> line(int x0, int z0, int x1, int z1) {
        List<int[]> tiles = new ArrayList<>();
        int sx = Integer.signum(x1 - x0), sz = Integer.signum(z1 - z0);
        for (int x = x0; ; x += sx) { tiles.add(new int[]{x, z0}); if (x == x1) break; }
        for (int z = z0 + sz; sz != 0; z += sz) { tiles.add(new int[]{x1, z}); if (z == z1) break; }
        return tiles;
    }
    /**
     * The road's tiles, two wide: {x, z, lane} where lane 0 is the spine and 1 the second lane, beside the spine on
     * the +Z side of the X run and the +X side of the Z run. Shared by the client preview.
     */
    public static List<int[]> path(int x0, int z0, int x1, int z1) {
        List<int[]> spine = line(x0, z0, x1, z1), tiles = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        int xRun = Math.abs(x1 - x0) + 1;
        boolean straightZ = x0 == x1 && z0 != z1;
        for (int[] t : spine) if (seen.add(key(t[0], t[1]))) tiles.add(new int[]{t[0], t[1], 0});
        for (int i = 0; i < spine.size(); i++) {
            int[] t = spine.get(i);
            boolean alongZ = straightZ || i >= xRun;
            List<int[]> lane = new ArrayList<>();
            lane.add(alongZ ? new int[]{t[0] + 1, t[1]} : new int[]{t[0], t[1] + 1});
            // The corner of an L joins both second lanes.
            if (!straightZ && i == xRun - 1 && z1 != z0) { lane.add(new int[]{t[0] + 1, t[1]}); lane.add(new int[]{t[0] + 1, t[1] + 1}); }
            for (int[] l : lane) if (seen.add(key(l[0], l[1]))) tiles.add(new int[]{l[0], l[1], 1});
        }
        return tiles;
    }
    private static long key(int x, int z) { return (long) x << 32 | (z & 0xffffffffL); }
    public static BuildingService.Result place(ServerPlayer player, BlockPos from, BlockPos to) {
        if (!BuildingService.allowed(player)) return BuildingService.Result.fail("wrong_region");
        if (Math.abs((long) to.getX() - from.getX()) + Math.abs((long) to.getZ() - from.getZ()) >= MAX_LENGTH) return BuildingService.Result.fail("road_too_long");
        ServerLevel level = player.serverLevel(); ColonyData data = ColonyData.get(player.server);
        IslandLayout layout = BuildingService.generator(level).layout();
        List<BlockPos> grounds = new ArrayList<>();
        for (int[] t : path(from.getX(), from.getZ(), to.getX(), to.getZ())) {
            BlockPos ground = new BlockPos(t[0], layout.height(t[0], t[1]), t[1]);
            if (data.road(layout.world(), ground)) continue;
            BuildingService.Result check = validate(level, data, layout, ground);
            // The spine must be valid; the second lane simply narrows where buildings or the coast are in the way.
            if (!check.success()) { if (t[2] == 0) return check; continue; }
            grounds.add(ground);
        }
        if (grounds.isEmpty()) return BuildingService.Result.fail("road_exists");
        for (BlockPos ground : grounds) {
            for (int k = 1; k <= 2; k++) Nature.clear(level, ground.above(k));
            CompoundTag original = BuildingService.blockBackup(level, ground);
            level.setBlock(ground, fr.annocraft.world.AnnoBlocks.EARTH_ROAD.get().defaultBlockState(), 2);
            data.putRoad(layout.world(), ground, original);
        }
        // The road's worn verges stop short of the buildings, whose plots keep a clean edge.
        List<BuildingInstance> near = data.buildings().values().stream().filter(b -> fr.annocraft.economy.ColonyEconomy.worldOf(b.island()).equals(layout.world())
                && b.overlaps(new BlockPos(Math.min(from.getX(), to.getX()) - 4, 0, Math.min(from.getZ(), to.getZ()) - 4), Math.abs(to.getX() - from.getX()) + 9, Math.abs(to.getZ() - from.getZ()) + 9)).toList();
        for (BlockPos ground : grounds) Wear.around(level, ground.getX(), ground.getZ(), 1, 1, ground.getY(), 2,
                (x, z) -> near.stream().anyMatch(b -> b.overlaps(new BlockPos(x - 1, 0, z - 1), 3, 3)));
        refresh(level, from, to); return BuildingService.Result.ok();
    }
    public static BuildingService.Result remove(ServerPlayer player, BlockPos from, BlockPos to) {
        if (!BuildingService.allowed(player)) return BuildingService.Result.fail("wrong_region");
        if (Math.abs((long) to.getX() - from.getX()) + Math.abs((long) to.getZ() - from.getZ()) >= MAX_LENGTH) return BuildingService.Result.fail("road_too_long");
        ServerLevel level = player.serverLevel(); ColonyData data = ColonyData.get(player.server);
        IslandLayout layout = BuildingService.generator(level).layout();
        List<BlockPos> grounds = new ArrayList<>();
        for (int[] t : path(from.getX(), from.getZ(), to.getX(), to.getZ())) {
            BlockPos ground = new BlockPos(t[0], layout.height(t[0], t[1]), t[1]);
            if (!data.road(layout.world(), ground)) continue;
            if (!BuildingService.loaded(level, ground, 1, 1)) return BuildingService.Result.fail("not_loaded");
            grounds.add(ground);
        }
        if (grounds.isEmpty()) return BuildingService.Result.fail("no_road");
        for (BlockPos ground : grounds) {
            ListTag original = new ListTag(); original.add(data.roadOriginal(layout.world(), ground).copy());
            BuildingService.restore(level, original); data.removeRoad(layout.world(), ground);
        }
        refresh(level, from, to); return BuildingService.Result.ok();
    }
    static BuildingService.Result validate(ServerLevel level, ColonyData data, IslandLayout layout, BlockPos ground) {
        int x = ground.getX(), z = ground.getZ();
        if (!layout.inBounds(x, z)) return BuildingService.Result.fail("outside_bounds");
        if (layout.islandAt(x, z).isEmpty() || ground.getY() <= IslandLayout.SEA_LEVEL) return BuildingService.Result.fail("invalid_terrain");
        if (!BuildingService.loaded(level, ground, 1, 1)) return BuildingService.Result.fail("not_loaded");
        for (BuildingInstance b : data.buildings().values()) if (fr.annocraft.economy.ColonyEconomy.worldOf(b.island()).equals(layout.world()) && b.overlaps(ground, 1, 1)) return BuildingService.Result.fail("overlap");
        if (!level.getBlockState(ground).isSolidRender(level, ground) || !level.getFluidState(ground).isEmpty()) return BuildingService.Result.fail("invalid_terrain");
        var above = level.getBlockState(ground.above());
        if (!above.isAir() && !Nature.natural(above)) return BuildingService.Result.fail("obstructed");
        return BuildingService.Result.ok();
    }
    private static void refresh(ServerLevel level, BlockPos from, BlockPos to) {
        BlockPos min = new BlockPos(Math.min(from.getX(), to.getX()), 0, Math.min(from.getZ(), to.getZ()));
        CameraSessions.refresh(level, min, Math.abs(to.getX() - from.getX()) + 2, Math.abs(to.getZ() - from.getZ()) + 2);
    }
}
