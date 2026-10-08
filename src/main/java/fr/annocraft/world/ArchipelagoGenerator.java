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
        // Flat, buildable plateaus without caves; forests, ground cover and deposits are drawn by decorate().
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
        decorate(chunk, ocean, surface);
        return CompletableFuture.completedFuture(chunk);
    }

    // ------------------------------------------------------------------------------------------- nature
    // Deposits, forests and ground cover are pure functions of the seed (IslandLayout), drawn column by column
    // and clipped to the chunk, so features spanning chunk borders join seamlessly.

    private static final java.util.Map<String, BlockState> NAMED = new java.util.concurrent.ConcurrentHashMap<>();
    private static BlockState named(String id) {
        return NAMED.computeIfAbsent(id, k -> net.minecraft.core.registries.BuiltInRegistries.BLOCK.get(net.minecraft.resources.ResourceLocation.withDefaultNamespace(k)).defaultBlockState());
    }
    private static BlockState leaves(Block block) { return block.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true); }
    private static double hash(int x, int y, int z, int salt) { return IslandLayout.hash(x, y, z, salt); }
    private static void put(ChunkAccess chunk, Heightmap ocean, Heightmap surface, int x, int y, int z, BlockState state, boolean onlyAir) {
        int lx = x - chunk.getPos().getMinBlockX(), lz = z - chunk.getPos().getMinBlockZ();
        if (lx < 0 || lx > 15 || lz < 0 || lz > 15 || y < 1 || y > 250) return;
        BlockPos pos = new BlockPos(x, y, z);
        if (onlyAir && !chunk.getBlockState(pos).isAir()) return;
        chunk.setBlockState(pos, state, false);
        ocean.update(lx, y, lz, state); surface.update(lx, y, lz, state);
    }
    private void decorate(ChunkAccess chunk, Heightmap ocean, Heightmap surface) {
        IslandLayout l = layout();
        int bx = chunk.getPos().getMinBlockX(), bz = chunk.getPos().getMinBlockZ();
        if (l.islandAt(bx + 8, bz + 8).isEmpty() && l.islandAt(bx, bz).isEmpty() && l.islandAt(bx + 15, bz + 15).isEmpty()
                && l.islandAt(bx + 15, bz).isEmpty() && l.islandAt(bx, bz + 15).isEmpty()) return;
        for (IslandLayout.Deposit d : l.depositsNear(bx, bz, bx + 15, bz + 15, 13)) deposit(chunk, ocean, surface, l, d);
        for (int x = bx; x < bx + 16; x++) for (int z = bz; z < bz + 16; z++) {
            int h = l.height(x, z);
            if (h != 73 || !chunk.getBlockState(new BlockPos(x, h, z)).is(Blocks.GRASS_BLOCK) || !chunk.getBlockState(new BlockPos(x, h + 1, z)).isAir()) continue;
            String plant = l.plant(x, z); if (plant == null) continue;
            if (plant.equals("large_fern")) {
                put(chunk, ocean, surface, x, h + 1, z, Blocks.LARGE_FERN.defaultBlockState().setValue(DoublePlantBlock.HALF, net.minecraft.world.level.block.state.properties.DoubleBlockHalf.LOWER), true);
                put(chunk, ocean, surface, x, h + 2, z, Blocks.LARGE_FERN.defaultBlockState().setValue(DoublePlantBlock.HALF, net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER), true);
            } else put(chunk, ocean, surface, x, h + 1, z, named(plant), true);
        }
        for (int x = bx - 3; x < bx + 19; x++) for (int z = bz - 3; z < bz + 19; z++) {
            IslandLayout.Tree t = l.tree(x, z);
            if (t != null && l.height(x, z) == 73) tree(chunk, ocean, surface, x, 74, z, t);
        }
    }
    private void tree(ChunkAccess c, Heightmap o, Heightmap s, int x, int y, int z, IslandLayout.Tree t) {
        BlockState log, leaf;
        switch (t.kind()) {
            case "birch" -> { log = Blocks.BIRCH_LOG.defaultBlockState(); leaf = leaves(Blocks.BIRCH_LEAVES); }
            case "spruce" -> { log = Blocks.SPRUCE_LOG.defaultBlockState(); leaf = leaves(Blocks.SPRUCE_LEAVES); }
            case "jungle" -> { log = Blocks.JUNGLE_LOG.defaultBlockState(); leaf = leaves(Blocks.JUNGLE_LEAVES); }
            case "acacia" -> { log = Blocks.ACACIA_LOG.defaultBlockState(); leaf = leaves(Blocks.ACACIA_LEAVES); }
            default -> { log = Blocks.OAK_LOG.defaultBlockState(); leaf = leaves(Blocks.OAK_LEAVES); }
        }
        int top = y + t.height() - 1;
        // Forest floor: podzol under some trunks.
        if (hash(x, y, z, 5) < .3) put(c, o, s, x, y - 1, z, Blocks.PODZOL.defaultBlockState(), false);
        for (int k = y; k <= top; k++) put(c, o, s, x, k, z, log, false);
        switch (t.kind()) {
            case "spruce" -> {
                for (int k = y + 2; k <= top; k++) {
                    int r = k == top ? 0 : (top - k) % 2 == 0 ? 1 : top - k > 2 ? 2 : 1;
                    disc(c, o, s, x, k, z, r, leaf, false);
                }
                put(c, o, s, x, top + 1, z, leaf, true); put(c, o, s, x, top + 2, z, leaf, true);
            }
            case "jungle" -> { disc(c, o, s, x, top - 1, z, 3, leaf, true); disc(c, o, s, x, top, z, 2, leaf, false); disc(c, o, s, x, top + 1, z, 1, leaf, false); }
            case "acacia" -> { disc(c, o, s, x, top, z, 3, leaf, true); disc(c, o, s, x, top + 1, z, 1, leaf, false); }
            default -> {
                disc(c, o, s, x, top - 2, z, 2, leaf, true); disc(c, o, s, x, top - 1, z, 2, leaf, true);
                disc(c, o, s, x, top, z, 1, leaf, false); disc(c, o, s, x, top + 1, z, 1, leaf, true);
            }
        }
    }
    /** Leaf layer around a trunk; corners are left out at random so crowns look round. */
    private static void disc(ChunkAccess c, Heightmap o, Heightmap s, int x, int y, int z, int r, BlockState leaf, boolean ragged) {
        for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) {
            boolean corner = Math.abs(dx) == r && Math.abs(dz) == r && r > 0;
            if (dx * dx + dz * dz > r * r + 1) continue;
            if (corner && (!ragged || hash(x + dx, y, z + dz, 7) < .6)) continue;
            put(c, o, s, x + dx, y, z + dz, leaf, true);
        }
    }
    /** A deposit: a flat slot showing the ore at ground level, with its hill or pit around it. */
    private void deposit(ChunkAccess c, Heightmap o, Heightmap s, IslandLayout l, IslandLayout.Deposit d) {
        BlockState ore = named(switch (d.type()) { case "iron" -> "iron_ore"; case "coal" -> "coal_ore"; case "gold" -> "gold_ore"; case "clay" -> "clay"; default -> "sand"; });
        int bx = c.getPos().getMinBlockX(), bz = c.getPos().getMinBlockZ();
        for (int x = bx; x < bx + 16; x++) for (int z = bz; z < bz + 16; z++) {
            int dx = x - d.x(), dz = z - d.z(); double r = Math.hypot(dx, dz), roll = hash(x, 0, z, 9);
            boolean slot = Math.abs(dx) <= IslandLayout.SLOT && Math.abs(dz) <= IslandLayout.SLOT;
            int ground = l.height(x, z); if (ground != 73) continue;
            if (d.mountain()) {
                if (slot) put(c, o, s, x, ground, z, roll < .35 ? ore : roll < .6 ? Blocks.GRAVEL.defaultBlockState() : Blocks.COARSE_DIRT.defaultBlockState(), false);
                else if (r <= 12.5) {
                    // Shared with the distant view: IslandLayout.hill shapes the hill behind the slot.
                    int height = l.hill(d, x, z);
                    for (int k = 1; k <= height; k++) {
                        double q = hash(x, ground + k, z, 10);
                        BlockState rock = q < .12 ? ore : q < .45 ? Blocks.STONE.defaultBlockState() : q < .7 ? Blocks.ANDESITE.defaultBlockState()
                                : q < .85 ? Blocks.COBBLESTONE.defaultBlockState() : Blocks.TUFF.defaultBlockState();
                        put(c, o, s, x, ground + k, z, rock, false);
                    }
                    if (height < 1 && r <= 9 && roll < .45) put(c, o, s, x, ground, z, Blocks.COARSE_DIRT.defaultBlockState(), false);
                }
            } else if (r <= 7.5) {
                boolean clay = d.type().equals("clay");
                BlockState floor = slot || roll < .5 ? ore : clay ? (roll < .8 ? Blocks.MUD.defaultBlockState() : Blocks.COARSE_DIRT.defaultBlockState())
                        : (roll < .8 ? Blocks.SANDSTONE.defaultBlockState() : Blocks.SAND.defaultBlockState());
                put(c, o, s, x, ground, z, floor, false);
                if (l.hill(d, x, z) > 0) put(c, o, s, x, ground + 1, z, clay ? Blocks.PACKED_MUD.defaultBlockState() : Blocks.SMOOTH_SANDSTONE.defaultBlockState(), false);
            }
        }
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
