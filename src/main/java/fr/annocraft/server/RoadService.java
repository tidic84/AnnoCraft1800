package fr.annocraft.server;

import fr.annocraft.building.BuildingInstance;
import fr.annocraft.world.IslandLayout;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.*;
import net.minecraft.server.level.*;
import net.minecraft.world.level.block.Blocks;
import java.util.*;

/** Roads are ground-level path blocks. They link buildings to the island's storage network. Free to build. */
public final class RoadService {
    public static final int MAX_LENGTH = 96;
    private RoadService() { }
    /** L-shaped path: along X first, then along Z. Shared by the client preview. */
    public static List<int[]> path(int x0, int z0, int x1, int z1) {
        List<int[]> tiles = new ArrayList<>();
        int sx = Integer.signum(x1 - x0), sz = Integer.signum(z1 - z0);
        for (int x = x0; ; x += sx) { tiles.add(new int[]{x, z0}); if (x == x1) break; }
        for (int z = z0 + sz; sz != 0; z += sz) { tiles.add(new int[]{x1, z}); if (z == z1) break; }
        return tiles;
    }
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
            if (!check.success()) return check;
            grounds.add(ground);
        }
        if (grounds.isEmpty()) return BuildingService.Result.fail("road_exists");
        for (BlockPos ground : grounds) {
            CompoundTag original = BuildingService.blockBackup(level, ground);
            level.setBlock(ground, Blocks.DIRT_PATH.defaultBlockState(), 2);
            data.putRoad(layout.world(), ground, original);
        }
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
        if (!level.getBlockState(ground.above()).isAir()) return BuildingService.Result.fail("obstructed");
        return BuildingService.Result.ok();
    }
    private static void refresh(ServerLevel level, BlockPos from, BlockPos to) {
        BlockPos min = new BlockPos(Math.min(from.getX(), to.getX()), 0, Math.min(from.getZ(), to.getZ()));
        CameraSessions.refresh(level, min, Math.abs(to.getX() - from.getX()) + 1, Math.abs(to.getZ() - from.getZ()) + 1);
    }
}
