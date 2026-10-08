package fr.annocraft.world;

import fr.annocraft.AnnoCraft;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.*;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.pathfinder.PathComputationType;
import net.minecraft.world.phys.shapes.*;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.*;

/**
 * Anno's ground: building plots of packed earth, dirt roads, and the worn grass that fades from them into the
 * meadow. Worn grass grows back on its own once no plot, road or building is left near it.
 */
public final class AnnoBlocks {
    public static final DeferredRegister<Block> BLOCKS = DeferredRegister.create(ForgeRegistries.BLOCKS, AnnoCraft.ID);
    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, AnnoCraft.ID);
    private static BlockBehaviour.Properties earth() { return BlockBehaviour.Properties.of().mapColor(MapColor.DIRT).strength(.6f).sound(SoundType.ROOTED_DIRT); }
    public static final RegistryObject<Block> PACKED_EARTH = block("packed_earth", () -> new Block(earth()));
    public static final RegistryObject<Block> EARTH_ROAD = block("earth_road", () -> new RoadBlock(earth().mapColor(MapColor.TERRACOTTA_BROWN)));
    /** Mostly earth with tufts of grass: the first ring around plots and roads. */
    public static final RegistryObject<Block> TRODDEN_GRASS = block("trodden_grass", () -> new WornGrassBlock(earth().mapColor(MapColor.DIRT).sound(SoundType.GRASS), 1));
    /** Mostly grass with bare patches: the outer ring, where the meadow takes over. */
    public static final RegistryObject<Block> WORN_GRASS = block("worn_grass", () -> new WornGrassBlock(earth().mapColor(MapColor.GRASS).sound(SoundType.GRASS), 0));
    private AnnoBlocks() { }
    private static RegistryObject<Block> block(String id, java.util.function.Supplier<Block> factory) {
        RegistryObject<Block> block = BLOCKS.register(id, factory);
        ITEMS.register(id, () -> new BlockItem(block.get(), new Item.Properties()));
        return block;
    }
    public static void register(IEventBus bus) { BLOCKS.register(bus); ITEMS.register(bus); }

    /** Level of wear of a ground block: 0 meadow, 1 worn grass, 2 trodden grass, 3 earth; -1 if it is not soft ground. */
    public static int wear(BlockState s) {
        if (s.is(Blocks.GRASS_BLOCK)) return 0;
        if (s.is(WORN_GRASS.get())) return 1;
        if (s.is(TRODDEN_GRASS.get())) return 2;
        return -1;
    }
    public static BlockState worn(int level) {
        return (level <= 0 ? Blocks.GRASS_BLOCK : level == 1 ? WORN_GRASS.get() : TRODDEN_GRASS.get()).defaultBlockState();
    }
    /** Ground the meadow does not reclaim: plots, roads, paving, fields and every other made surface. */
    static boolean anchors(BlockState s) {
        return !(s.isAir() || s.is(Blocks.GRASS_BLOCK) || s.is(Blocks.DIRT) || s.is(Blocks.COARSE_DIRT) || s.is(Blocks.SAND) || s.is(Blocks.SANDSTONE)
                || s.is(Blocks.GRAVEL) || s.is(Blocks.PODZOL) || s.is(Blocks.WATER) || s.is(WORN_GRASS.get()) || s.is(TRODDEN_GRASS.get())
                || s.is(net.minecraft.tags.BlockTags.LOGS) || s.is(net.minecraft.tags.BlockTags.LEAVES) || s.is(net.minecraft.tags.BlockTags.REPLACEABLE)
                || s.is(net.minecraft.tags.BlockTags.FLOWERS));
    }

    /** Dirt road, a little lower than the ground like a vanilla path. */
    public static final class RoadBlock extends Block {
        private static final VoxelShape SHAPE = Block.box(0, 0, 0, 16, 15, 16);
        public RoadBlock(Properties p) { super(p); }
        @Override public VoxelShape getShape(BlockState s, BlockGetter level, BlockPos pos, CollisionContext context) { return SHAPE; }
        @Override public boolean useShapeForLightOcclusion(BlockState s) { return true; }
        @Override public boolean isPathfindable(BlockState s, BlockGetter level, BlockPos pos, PathComputationType type) { return false; }
    }

    /** Grass worn by traffic. Away from any made ground it slowly grows back, one level at a time. */
    public static final class WornGrassBlock extends Block {
        private final int level;
        public WornGrassBlock(Properties p, int level) { super(p.randomTicks()); this.level = level; }
        @Override public void randomTick(BlockState s, ServerLevel world, BlockPos pos, RandomSource random) {
            if (random.nextInt(3) != 0) return;
            for (int dx = -3; dx <= 3; dx++) for (int dz = -3; dz <= 3; dz++) for (int dy = -1; dy <= 1; dy++) {
                if (dx == 0 && dz == 0 && dy == 0) continue;
                BlockPos q = pos.offset(dx, dy, dz);
                if (!world.isLoaded(q)) return;
                BlockState n = world.getBlockState(q);
                if (dy == 0 ? anchors(n) : n.is(PACKED_EARTH.get()) || n.is(EARTH_ROAD.get())) return;
            }
            world.setBlock(pos, worn(level), 2);
            fr.annocraft.server.CameraSessions.blockChanged(world, pos);
        }
    }
}
