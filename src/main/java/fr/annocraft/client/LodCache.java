package fr.annocraft.client;

import com.mojang.blaze3d.platform.NativeImage;
import fr.annocraft.AnnoCraft;
import fr.annocraft.world.IslandLayout;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.core.*;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.level.ChunkEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.*;

/**
 * The terrain the player has really seen, column by column, as Voxy and Distant Horizons keep it: the top block's
 * height and its colour, averaged from the block's own texture and tinted like the biome draws it. Chunks are
 * sampled as the client receives them and again while the camera watches them (buildings rise, forests fall),
 * then kept on disk per server and world, so the distant view shows the real towns and forests in every session.
 * Columns never seen fall back to the seed's terrain (LodRenderer).
 */
@Mod.EventBusSubscriber(modid = AnnoCraft.ID, value = Dist.CLIENT)
public final class LodCache {
    public static final int REGION = LodRenderer.REGION;
    /** Heights: a column never seen, and a column of open water. */
    public static final short UNKNOWN = -1, WATER = -2;
    private static final int MAGIC = 0x414e4c44, FORMAT = 1;

    static final class Region {
        final short[] height = new short[REGION * REGION];
        final int[] color = new int[REGION * REGION];
        volatile long version;
        long saved;
        Region() { Arrays.fill(height, UNKNOWN); }
        int index(int x, int z) { return Math.floorMod(x, REGION) + Math.floorMod(z, REGION) * REGION; }
    }
    private static final Map<Long, Region> REGIONS = new ConcurrentHashMap<>();
    private static final Set<Long> LOADING = ConcurrentHashMap.newKeySet();
    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "AnnoCraft distant view storage"); t.setDaemon(true); return t; });
    private static final ArrayDeque<ChunkPos> QUEUE = new ArrayDeque<>();
    private static final Set<Long> QUEUED = new HashSet<>();
    private static final Map<BlockState, int[]> LOOKS = new HashMap<>();
    private static Path folder;
    private static String world;
    private static int ticks;
    private LodCache() { }

    static long key(int rx, int rz) { return (long) rx << 32 | (rz & 0xffffffffL); }
    /** The region's samples, or null while they are unknown or still being read from disk. */
    static Region region(int rx, int rz) {
        long k = key(rx, rz);
        Region r = REGIONS.get(k);
        if (r == null && folder != null && LOADING.add(k)) {
            Path file = file(folder, rx, rz);
            IO.submit(() -> { try { load(file, k); } finally { LOADING.remove(k); } });
        }
        return r;
    }
    /** The region's samples if they are in memory, without reading the disk: for the meshing threads. */
    static Region peek(int rx, int rz) { return REGIONS.get(key(rx, rz)); }
    /** Height of a column (UNKNOWN, WATER or the top block's y) and its colour. */
    static short height(Region r, int x, int z) { return r.height[r.index(x, z)]; }
    static int color(Region r, int x, int z) { return r.color[r.index(x, z)]; }

    // ---------------------------------------------------------------- sampling

    @SubscribeEvent public static void loaded(ChunkEvent.Load event) {
        if (event.getLevel().isClientSide() && event.getChunk() instanceof LevelChunk chunk) enqueue(chunk.getPos());
    }
    private static void enqueue(ChunkPos pos) { if (QUEUED.add(pos.toLong())) QUEUE.add(pos); }
    @SubscribeEvent public static void tick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();
        IslandLayout layout = ClientState.layout;
        if (mc.level == null || layout == null || !RtsController.inRegion()) return;
        String current = name(mc, layout);
        if (!current.equals(world)) { flush(); world = current; folder = mc.gameDirectory.toPath().resolve("annocraft1800").resolve("lod").resolve(current); }
        // What the camera watches is sampled again from time to time: construction, roads and felling change it.
        if (++ticks % 100 == 0) {
            double cx = RtsController.active ? RtsController.x : mc.player.getX(), cz = RtsController.active ? RtsController.z : mc.player.getZ();
            int r = 12, ox = (int) Math.floor(cx) >> 4, oz = (int) Math.floor(cz) >> 4;
            for (int x = -r; x <= r; x++) for (int z = -r; z <= r; z++) if (mc.level.hasChunk(ox + x, oz + z)) enqueue(new ChunkPos(ox + x, oz + z));
        }
        for (int n = 0; n < 12 && !QUEUE.isEmpty(); n++) {
            ChunkPos pos = QUEUE.poll(); QUEUED.remove(pos.toLong());
            if (mc.level.hasChunk(pos.x, pos.z)) sample(mc, mc.level.getChunk(pos.x, pos.z));
        }
        if (ticks % 1200 == 0) save(false);
    }
    private static String name(Minecraft mc, IslandLayout layout) {
        String server = mc.getSingleplayerServer() != null ? "solo-" + mc.getSingleplayerServer().getWorldData().getLevelName()
                : mc.getCurrentServer() != null ? "server-" + mc.getCurrentServer().ip : "unknown";
        return (server + "-" + Long.toHexString(layout.seed())).replaceAll("[^A-Za-z0-9._-]", "_") + "/" + layout.world();
    }
    private static void sample(Minecraft mc, LevelChunk chunk) {
        int x0 = chunk.getPos().getMinBlockX(), z0 = chunk.getPos().getMinBlockZ();
        int rx = Math.floorDiv(x0, REGION), rz = Math.floorDiv(z0, REGION);
        // Read what the disk knows of the region first; it merges under the new samples when it arrives.
        if (!REGIONS.containsKey(key(rx, rz))) region(rx, rz);
        Region region = REGIONS.computeIfAbsent(key(rx, rz), k -> new Region());
        Map<Long, Integer> tints = new HashMap<>();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        boolean changed = false;
        synchronized (region) {
            for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) {
                int y = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
                short h; int c = 0;
                if (y <= chunk.getMinBuildHeight()) h = WATER;
                else {
                    BlockState s = chunk.getBlockState(pos.set(x0 + x, y, z0 + z));
                    int plant = -1;
                    // Grass and flowers tint the ground they grow on rather than standing on it.
                    if ((s.is(BlockTags.REPLACEABLE) || s.is(BlockTags.FLOWERS)) && s.getFluidState().isEmpty() && y > chunk.getMinBuildHeight() + 1) {
                        plant = look(mc, s, pos, tints); y--; s = chunk.getBlockState(pos.set(x0 + x, y, z0 + z));
                    }
                    if (!s.getFluidState().isEmpty() && !s.isSolid()) h = WATER;
                    else {
                        h = (short) y; c = look(mc, s, pos, tints);
                        if (plant != -1) c = LodRenderer.mix(c, plant, .35);
                    }
                }
                int i = region.index(x0 + x, z0 + z);
                if (region.height[i] != h || region.color[i] != c) { region.height[i] = h; region.color[i] = c; changed = true; }
            }
            if (changed) region.version++;
        }
    }
    /** Colour of a block as seen from above, with the biome's tint for this place. */
    private static int look(Minecraft mc, BlockState s, BlockPos pos, Map<Long, Integer> tints) {
        int[] layers = LOOKS.computeIfAbsent(s, state -> layers(mc, state));
        int color = 0;
        for (int i = 0; i + 2 < layers.length; i += 3) {
            int rgb = layers[i], tint = layers[i + 2];
            if (tint >= 0) {
                // One biome lookup per chunk, block and tint: blending them per column costs too much.
                long k = (long) System.identityHashCode(s.getBlock()) << 8 | tint;
                int t = tints.computeIfAbsent(k, kk -> mc.getBlockColors().getColor(s, mc.level, pos, tint));
                if (t != -1) rgb = multiply(rgb, t);
            }
            color = i == 0 ? rgb : LodRenderer.mix(color, rgb, layers[i + 1] / 255.0);
        }
        return color;
    }
    /** Layers of a block's top face: {average colour, coverage 0..255, tint index} each, bottom layer first. */
    private static int[] layers(Minecraft mc, BlockState s) {
        var model = mc.getBlockRenderer().getBlockModel(s);
        RandomSource random = RandomSource.create(42);
        List<BakedQuad> quads = new ArrayList<>(model.getQuads(s, Direction.UP, random));
        if (quads.isEmpty()) quads.addAll(model.getQuads(s, null, random));
        List<int[]> result = new ArrayList<>();
        if (quads.isEmpty()) result.add(average(model.getParticleIcon().contents().getOriginalImage(), -1));
        for (BakedQuad q : quads) {
            int[] layer = average(q.getSprite().contents().getOriginalImage(), q.getTintIndex());
            if (layer[1] > 0) result.add(layer);
            if (result.size() >= 3) break;
        }
        if (result.isEmpty()) result.add(new int[]{s.getMapColor(mc.level, BlockPos.ZERO).col, 255, -1});
        int[] packed = new int[result.size() * 3];
        for (int i = 0; i < result.size(); i++) System.arraycopy(result.get(i), 0, packed, i * 3, 3);
        return packed;
    }
    private static int[] average(NativeImage image, int tint) {
        long r = 0, g = 0, b = 0, n = 0, total = 0;
        // The first frame of animated textures: a square as wide as the image.
        int w = image.getWidth(), h = Math.min(image.getHeight(), w);
        for (int x = 0; x < w; x++) for (int y = 0; y < h; y++) {
            int abgr = image.getPixelRGBA(x, y); total++;
            if ((abgr >>> 24) < 32) continue;
            r += abgr & 255; g += abgr >> 8 & 255; b += abgr >> 16 & 255; n++;
        }
        if (n == 0) return new int[]{0, 0, tint};
        return new int[]{(int) (r / n) << 16 | (int) (g / n) << 8 | (int) (b / n), (int) (255 * n / Math.max(1, total)), tint};
    }
    private static int multiply(int a, int b) {
        return ((a >> 16 & 255) * (b >> 16 & 255) / 255) << 16 | ((a >> 8 & 255) * (b >> 8 & 255) / 255) << 8 | (a & 255) * (b & 255) / 255;
    }

    // ---------------------------------------------------------------- storage

    private static Path file(Path folder, int rx, int rz) { return folder.resolve("r." + rx + "." + rz + ".lod"); }
    private static void load(Path file, long k) {
        if (!Files.exists(file)) { REGIONS.putIfAbsent(k, new Region()); return; }
        Region read = new Region();
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new InflaterInputStream(Files.newInputStream(file))))) {
            if (in.readInt() != MAGIC || in.readInt() != FORMAT) return;
            for (int i = 0; i < read.height.length; i++) read.height[i] = in.readShort();
            for (int i = 0; i < read.color.length; i++) read.color[i] = in.readInt();
        } catch (IOException damaged) { return; }
        Region current = REGIONS.putIfAbsent(k, read);
        if (current != null) synchronized (current) {
            // Sampled while the file was read: the fresher samples win.
            for (int i = 0; i < current.height.length; i++) if (current.height[i] == UNKNOWN) { current.height[i] = read.height[i]; current.color[i] = read.color[i]; }
            current.version++;
        } else read.version++;
    }
    /** Writes the regions changed since their last save, in the background. */
    static void save(boolean all) {
        if (folder == null) return;
        Path dir = folder;
        for (var e : REGIONS.entrySet()) {
            Region r = e.getValue();
            if (r.version == r.saved && !all) continue;
            short[] h; int[] c;
            synchronized (r) { h = r.height.clone(); c = r.color.clone(); r.saved = r.version; }
            boolean known = false; for (short v : h) if (v != UNKNOWN) { known = true; break; }
            if (!known) continue;
            int rx = (int) (e.getKey() >> 32), rz = (int) (long) e.getKey();
            IO.submit(() -> {
                try {
                    Files.createDirectories(dir);
                    Path tmp = dir.resolve("r." + rx + "." + rz + ".tmp");
                    try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new DeflaterOutputStream(Files.newOutputStream(tmp))))) {
                        out.writeInt(MAGIC); out.writeInt(FORMAT);
                        for (short v : h) out.writeShort(v);
                        for (int v : c) out.writeInt(v);
                    }
                    Files.move(tmp, file(dir, rx, rz), StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException ignored) { }
            });
        }
    }
    private static void flush() {
        save(false);
        REGIONS.clear(); QUEUE.clear(); QUEUED.clear(); LOOKS.clear(); folder = null; world = null;
    }
    @SubscribeEvent public static void logout(ClientPlayerNetworkEvent.LoggingOut event) { flush(); }
}
