package fr.annocraft.world;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.*;
import net.minecraft.world.level.*;
import net.minecraft.world.level.biome.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.*;
import net.minecraft.world.level.levelgen.*;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.server.level.*;
import java.util.List;
import java.util.concurrent.*;

public final class ArchipelagoGenerator extends ChunkGenerator {
    public static final Codec<ArchipelagoGenerator> CODEC = RecordCodecBuilder.create(i -> i.group(
            BiomeSource.CODEC.fieldOf("biome_source").forGetter(g -> g.biomeSource),
            Codec.intRange(1024, 16384).optionalFieldOf("region_size", 4096).forGetter(g -> g.size),
            Codec.intRange(1, 32).optionalFieldOf("island_count", 8).forGetter(g -> g.count),
            Codec.STRING.optionalFieldOf("world", IslandLayout.OLD_WORLD).forGetter(g -> g.world)
    ).apply(i, ArchipelagoGenerator::new));
    private final int size, count;
    private final String world;
    private volatile IslandLayout layout;
    public ArchipelagoGenerator(BiomeSource biomeSource, int size, int count, String world) {
        super(biomeSource); this.size = size; this.count = count; this.world = world;
    }
    @Override public ChunkGeneratorStructureState createState(HolderLookup<StructureSet> sets, RandomState state, long seed) {
        layout = new IslandLayout(seed, size, count, world);
        return ChunkGeneratorStructureState.createForNormal(state, seed, biomeSource, sets);
    }
    public IslandLayout layout() {
        if (layout == null) throw new IllegalStateException("Archipelago seed has not been initialized");
        return layout;
    }
    @Override protected Codec<? extends ChunkGenerator> codec() { return CODEC; }
    @Override public CompletableFuture<ChunkAccess> fillFromNoise(Executor executor, Blender blender, RandomState random,
                                                                  StructureManager structures, ChunkAccess chunk) {
        // No structures, caves or decorations: first milestone guarantees flat, buildable islands.
        Heightmap ocean = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.OCEAN_FLOOR_WG);
        Heightmap surface = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.WORLD_SURFACE_WG);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) {
            int wx = chunk.getPos().getMinBlockX() + x, wz = chunk.getPos().getMinBlockZ() + z;
            int height = layout().height(wx, wz);
            for (int y = 0; y <= Math.max(height, IslandLayout.SEA_LEVEL); y++) {
                BlockState block = blockAt(y, height);
                chunk.setBlockState(pos.set(wx, y, wz), block, false);
                ocean.update(x, y, z, block); surface.update(x, y, z, block);
            }
        }
        return CompletableFuture.completedFuture(chunk);
    }
    private static BlockState blockAt(int y, int height) {
        if (y == 0) return Blocks.BEDROCK.defaultBlockState();
        if (y > height) return y <= IslandLayout.SEA_LEVEL ? Blocks.WATER.defaultBlockState() : Blocks.AIR.defaultBlockState();
        if (y == height) return (height <= 67 ? Blocks.SAND : Blocks.GRASS_BLOCK).defaultBlockState();
        if (y >= height - 3) return (height <= 67 ? Blocks.SANDSTONE : Blocks.DIRT).defaultBlockState();
        return Blocks.STONE.defaultBlockState();
    }
    @Override public int getBaseHeight(int x, int z, Heightmap.Types type, LevelHeightAccessor level, RandomState random) {
        int h = layout().height(x, z);
        return (type == Heightmap.Types.WORLD_SURFACE_WG || type == Heightmap.Types.WORLD_SURFACE ? Math.max(h, getSeaLevel()) : h) + 1;
    }
    @Override public NoiseColumn getBaseColumn(int x, int z, LevelHeightAccessor level, RandomState random) {
        BlockState[] states = new BlockState[getGenDepth()];
        int h = layout().height(x, z);
        for (int y = 0; y < states.length; y++) states[y] = blockAt(y, h);
        return new NoiseColumn(0, states);
    }
    @Override public void applyCarvers(WorldGenRegion region, long seed, RandomState random, BiomeManager biomes, StructureManager structures, ChunkAccess chunk, GenerationStep.Carving step) { }
    @Override public void buildSurface(WorldGenRegion region, StructureManager structures, RandomState random, ChunkAccess chunk) { }
    @Override public void spawnOriginalMobs(WorldGenRegion region) { }
    @Override public void applyBiomeDecoration(WorldGenLevel level, ChunkAccess chunk, StructureManager structures) { }
    @Override public void addDebugScreenInfo(List<String> lines, RandomState random, BlockPos pos) { lines.add("AnnoCraft archipelago / " + layout().islandAt(pos.getX(), pos.getZ()).map(IslandLayout.Island::id).orElse("ocean")); }
    @Override public int getGenDepth() { return 256; }
    @Override public int getSeaLevel() { return IslandLayout.SEA_LEVEL; }
    @Override public int getMinY() { return 0; }
}
