package fr.annocraft.server;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.sounds.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import java.util.*;

/**
 * Construction animation: a building is registered and protected at once, but its blocks above the floor are
 * raised layer by layer over about two seconds, with dust and placement sounds. Pending blocks are always
 * completed when the server stops, and dropped when the building is demolished or upgraded.
 */
public final class ConstructionAnimator {
    private static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;
    private record Pending(BlockPos pos, BlockState state) { }
    private static final class Job {
        final ServerLevel level; final List<Pending> blocks; final int perTick; int next;
        Job(ServerLevel level, List<Pending> blocks) { this.level = level; this.blocks = blocks; perTick = Math.max(3, (int) Math.ceil(blocks.size() / 40.0)); }
    }
    private static final Map<UUID, Job> JOBS = new LinkedHashMap<>();
    private ConstructionAnimator() { }

    public static void start(ServerLevel level, UUID building, BlockPos origin, int width, int height, int depth) {
        List<Pending> blocks = new ArrayList<>();
        for (int y = 1; y < height; y++) for (int x = 0; x < width; x++) for (int z = 0; z < depth; z++) {
            BlockPos pos = origin.offset(x, y, z); BlockState state = level.getBlockState(pos);
            if (!state.isAir()) blocks.add(new Pending(pos.immutable(), state));
        }
        if (blocks.isEmpty()) return;
        // Bottom to top, and within a layer from one corner, like masons working along the walls.
        blocks.sort(Comparator.comparingInt((Pending p) -> p.pos.getY()).thenComparingInt(p -> p.pos.getX() + p.pos.getZ()));
        for (int i = blocks.size() - 1; i >= 0; i--) level.setBlock(blocks.get(i).pos, Blocks.AIR.defaultBlockState(), FLAGS);
        JOBS.put(building, new Job(level, blocks));
        dust(level, origin.offset(width / 2, 0, depth / 2), Blocks.STONE_BRICKS.defaultBlockState(), 20, width / 2.0);
    }
    public static void cancel(UUID building) { JOBS.remove(building); }
    public static boolean running(UUID building) { return JOBS.containsKey(building); }

    public static void tick() {
        for (Iterator<Map.Entry<UUID, Job>> it = JOBS.entrySet().iterator(); it.hasNext(); ) {
            Job job = it.next().getValue();
            for (int i = 0; i < job.perTick && job.next < job.blocks.size(); i++) {
                Pending p = job.blocks.get(job.next++);
                job.level.setBlock(p.pos, p.state, FLAGS);
                CameraSessions.blockChanged(job.level, p.pos);
                if (i == 0) {
                    dust(job.level, p.pos, p.state, 4, .4);
                    if (job.next % 3 == 1) job.level.playSound(null, p.pos, p.state.getSoundType().getPlaceSound(), SoundSource.BLOCKS, .6f, .9f + job.level.random.nextFloat() * .2f);
                }
            }
            if (job.next >= job.blocks.size()) {
                BlockPos top = job.blocks.get(job.blocks.size() - 1).pos;
                job.level.playSound(null, top, SoundEvents.VILLAGER_WORK_MASON, SoundSource.BLOCKS, .8f, 1);
                job.level.sendParticles(ParticleTypes.HAPPY_VILLAGER, top.getX() + .5, top.getY() + 1, top.getZ() + .5, 12, 1.5, .5, 1.5, 0);
                it.remove();
            }
        }
    }
    /** Places everything still pending: used when the server stops. */
    public static void finishAll() {
        for (Job job : JOBS.values()) for (int i = job.next; i < job.blocks.size(); i++) job.level.setBlock(job.blocks.get(i).pos, job.blocks.get(i).state, FLAGS);
        JOBS.clear();
    }
    /** Block dust, sent with long range so remote RTS cameras see it too. */
    static void dust(ServerLevel level, BlockPos pos, BlockState state, int count, double spread) {
        var particle = new BlockParticleOption(ParticleTypes.BLOCK, state.isAir() ? Blocks.DIRT.defaultBlockState() : state);
        for (ServerPlayer p : level.players())
            level.sendParticles(p, particle, true, pos.getX() + .5, pos.getY() + .5, pos.getZ() + .5, count, spread, .4, spread, 0);
    }
    public static void clear(MinecraftServer server) { finishAll(); }
}
