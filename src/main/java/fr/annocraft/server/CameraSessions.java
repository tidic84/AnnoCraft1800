package fr.annocraft.server;

import fr.annocraft.AnnoCraft;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.*;
import net.minecraft.server.level.*;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;
import java.util.*;

/**
 * RTS cameras. While the management view is open the player's body is hidden and carried along with the camera,
 * high above the islands, so the vanilla chunk, entity and block tracking follow wherever the player looks.
 * Region tickets keep the chunks inside the camera's field of view loaded, nothing behind it; the client draws everything
 * farther as a distant view (LodRenderer). Closing the view puts
 * the body back where it stood; the stored body also restores players who disconnected or crashed in the view.
 */
public final class CameraSessions {
    /** Detailed chunks are those the camera sees, within this many blocks of the point looked at; the client draws the rest as a distant view. */
    public static final int DETAIL = 176;
    /** At most 21 × 21 chunks per player. */
    public static final int MAX_CHUNKS = 441;
    /** Altitude of the hidden body, above every building. */
    public static final double BODY_Y = 128;
    private static final String SAVED = "annocraft_rts_body";
    /** Chunks in the camera's field of view, nearest first, bounded in number. */
    public static List<ChunkPos> visible(double x, double z, float zoom, float yaw) { return visible(x, z, zoom, yaw, 0); }
    public static List<ChunkPos> visible(double x, double z, float zoom, float yaw, float tilt) {
        var quad = fr.annocraft.client.CameraMath.footprint(x, z, yaw, fr.annocraft.client.CameraMath.zoom(zoom), fr.annocraft.client.CameraMath.tilt(tilt), 16 / 9.0, DETAIL);
        double minX = x, maxX = x, minZ = z, maxZ = z;
        for (double[] c : quad) { minX = Math.min(minX, c[0]); maxX = Math.max(maxX, c[0]); minZ = Math.min(minZ, c[1]); maxZ = Math.max(maxZ, c[1]); }
        ChunkPos center = new ChunkPos(net.minecraft.core.BlockPos.containing(x, 80, z));
        List<ChunkPos> result = new ArrayList<>();
        for (int cx = ((int) Math.floor(minX) >> 4) - 1; cx <= ((int) Math.floor(maxX) >> 4) + 1; cx++)
            for (int cz = ((int) Math.floor(minZ) >> 4) - 1; cz <= ((int) Math.floor(maxZ) >> 4) + 1; cz++)
                if (Math.max(Math.abs(cx - center.x), Math.abs(cz - center.z)) <= 1 || fr.annocraft.client.CameraMath.inside(quad, cx * 16 + 8, cz * 16 + 8, 12))
                    result.add(new ChunkPos(cx, cz));
        result.sort(Comparator.comparingDouble(c -> Math.hypot(c.x * 16 + 8 - x, c.z * 16 + 8 - z)));
        return result.size() > MAX_CHUNKS ? List.copyOf(result.subList(0, MAX_CHUNKS)) : result;
    }
    private static final TicketType<UUID> TICKET = TicketType.create("annocraft_camera", UUID::compareTo);
    private static final Map<UUID, Session> sessions = new HashMap<>();
    private static final class Session {
        final ServerLevel level; final Vec3 body; final float yaw, pitch;
        final Set<ChunkPos> chunks = new HashSet<>();
        double x, z; long lastUpdate, heartbeat;
        boolean acknowledged;
        Session(ServerPlayer p) { level = p.serverLevel(); body = p.position(); yaw = p.getYRot(); pitch = p.getXRot(); x = body.x; z = body.z; lastUpdate = -100; }
    }
    public static void update(ServerPlayer p, boolean active, double x, double z) { update(p, active, x, z, 0, 135, 0); }
    public static void update(ServerPlayer p, boolean active, double x, double z, float zoom, float yaw, float tilt) {
        if (!active) { close(p); return; }
        if (!AnnoCraft.isColony(p.level().dimension()) || !Double.isFinite(x) || !Double.isFinite(z)) return;
        Session s = sessions.get(p.getUUID());
        if (s == null) { recover(p); s = new Session(p); sessions.put(p.getUUID(), s); }
        long now = p.serverLevel().getGameTime();
        if (now - s.lastUpdate < 5) return;
        if (!BuildingService.generator(s.level).layout().inBounds((int) x, (int) z) || !Float.isFinite(zoom) || !Float.isFinite(yaw) || !Float.isFinite(tilt)) return;
        s.lastUpdate = now; s.heartbeat = now; s.x = x; s.z = z;
        if (!s.acknowledged) {
            s.acknowledged = true;
            hide(p, s);
            fr.annocraft.network.AnnoNetwork.CHANNEL.send(net.minecraftforge.network.PacketDistributor.PLAYER.with(() -> p),
                    new fr.annocraft.network.AnnoNetwork.CameraAnchor(s.body.x, s.body.y, s.body.z, s.yaw, s.pitch));
        }
        follow(p, s);
        ChunkPos center = new ChunkPos(net.minecraft.core.BlockPos.containing(x, 80, z));
        List<ChunkPos> wanted = visible(x, z, zoom, yaw, tilt);
        int radius = 0; for (ChunkPos c : wanted) radius = Math.max(radius, Math.max(Math.abs(c.x - center.x), Math.abs(c.z - center.z)));
        p.connection.send(new ClientboundSetChunkCacheRadiusPacket(Math.max(radius + 1, p.server.getPlayerList().getViewDistance())));
        Set<ChunkPos> next = new HashSet<>(wanted);
        p.connection.send(new ClientboundSetChunkCacheCenterPacket(center.x, center.z));
        for (ChunkPos old : Set.copyOf(s.chunks)) if (!next.contains(old)) {
            s.level.getChunkSource().removeRegionTicket(TICKET, old, 2, p.getUUID());
            forgetIfRemote(p, old); s.chunks.remove(old);
        }
        int view = p.server.getPlayerList().getViewDistance();
        for (ChunkPos pos : next) if (s.chunks.add(pos)) {
            s.level.getChunkSource().addRegionTicket(TICKET, pos, 2, p.getUUID());
            // Vanilla already sends what lies within the view distance of the (moved) body.
            if (Math.max(Math.abs(pos.x - center.x), Math.abs(pos.z - center.z)) > view)
                p.connection.send(new ClientboundLevelChunkWithLightPacket(s.level.getChunk(pos.x, pos.z), s.level.getLightEngine(), null, null));
        }
    }
    /** Hides the body and lets it float: invisible, flying, invulnerable; remembers how to undo it. */
    private static void hide(ServerPlayer p, Session s) {
        CompoundTag saved = new CompoundTag();
        saved.putString("dimension", s.level.dimension().location().toString());
        saved.putDouble("x", s.body.x); saved.putDouble("y", s.body.y); saved.putDouble("z", s.body.z);
        saved.putFloat("yaw", s.yaw); saved.putFloat("pitch", s.pitch);
        saved.putBoolean("effect", !p.hasEffect(net.minecraft.world.effect.MobEffects.INVISIBILITY)); saved.putBoolean("gravity", p.isNoGravity());
        saved.putBoolean("mayfly", p.getAbilities().mayfly); saved.putBoolean("flying", p.getAbilities().flying);
        saved.putBoolean("invulnerable", p.getAbilities().invulnerable);
        p.getPersistentData().put(SAVED, saved);
        // The effect, not the bare flag: vanilla recomputes the flag from effects.
        if (!p.hasEffect(net.minecraft.world.effect.MobEffects.INVISIBILITY))
            p.addEffect(new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.INVISIBILITY, net.minecraft.world.effect.MobEffectInstance.INFINITE_DURATION, 0, false, false, false));
        p.setNoGravity(true);
        p.getAbilities().mayfly = true; p.getAbilities().flying = true; p.getAbilities().invulnerable = true; p.onUpdateAbilities();
    }
    /** Carries the hidden body above the camera's centre. */
    private static void follow(ServerPlayer p, Session s) {
        p.setDeltaMovement(Vec3.ZERO); p.fallDistance = 0;
        if (Math.abs(p.getX() - s.x) > 1 || Math.abs(p.getZ() - s.z) > 1 || Math.abs(p.getY() - BODY_Y) > 1)
            p.connection.teleport(s.x, BODY_Y, s.z, s.yaw, s.pitch);
    }
    private static void forgetIfRemote(ServerPlayer p, ChunkPos pos) {
        int range = p.server.getPlayerList().getViewDistance(); ChunkPos body = p.chunkPosition();
        if (Math.max(Math.abs(pos.x - body.x), Math.abs(pos.z - body.z)) > range) p.connection.send(new ClientboundForgetLevelChunkPacket(pos.x, pos.z));
    }
    public static void tick(ServerPlayer p) {
        Session s = sessions.get(p.getUUID()); if (s == null) return;
        if (p.serverLevel() != s.level || !p.isAlive() || s.level.getGameTime() - s.heartbeat > 100) { close(p); return; }
        if (s.acknowledged) follow(p, s);
    }
    public static void close(ServerPlayer p) {
        Session s = sessions.remove(p.getUUID()); if (s == null) { recover(p); return; }
        for (ChunkPos pos : s.chunks) s.level.getChunkSource().removeRegionTicket(TICKET, pos, 2, p.getUUID());
        boolean sameLevel = p.serverLevel() == s.level;
        restore(p, sameLevel);
        // Leaving the management view sets the player down where the camera looked: on the island below, never
        // in the sea, and beside the buildings rather than on their roofs.
        if (sameLevel && p.isAlive()) {
            Vec3 land = landing(s.level, s.x, s.z);
            if (land != null) p.connection.teleport(land.x, land.y, land.z, p.getYRot(), p.getXRot());
        }
        p.connection.send(new ClientboundSetChunkCacheRadiusPacket(p.server.getPlayerList().getViewDistance()));
        if (sameLevel) {
            // The client cache followed the camera; resend the chunks around the restored body.
            p.connection.send(new ClientboundSetChunkCacheCenterPacket(p.chunkPosition().x, p.chunkPosition().z));
            int range = p.server.getPlayerList().getViewDistance(); ChunkPos center = p.chunkPosition();
            for (int x = -range; x <= range; x++) for (int z = -range; z <= range; z++) {
                var chunk = s.level.getChunkSource().getChunkNow(center.x + x, center.z + z);
                if (chunk != null) p.connection.send(new ClientboundLevelChunkWithLightPacket(chunk, s.level.getLightEngine(), null, null));
            }
        }
    }
    /**
     * Free dry ground nearest to (x, z): first the column itself, then rings around it; over the open sea, the
     * shore of the nearest island in the camera's direction. @return the feet position, or null if none was found
     */
    public static Vec3 landing(ServerLevel level, double x, double z) {
        var layout = BuildingService.generator(level).layout();
        var data = ColonyData.get(level.getServer());
        int cx = (int) Math.floor(x), cz = (int) Math.floor(z);
        if (layout.height(cx, cz) <= fr.annocraft.world.IslandLayout.SEA_LEVEL) {
            var island = layout.islands().stream().min(Comparator.comparingDouble(i -> i.distance(x, z) * Math.max(i.radiusX(), i.radiusZ()))).orElse(null);
            if (island == null) return null;
            // Walk towards the island's centre until the beach.
            double dx = island.x() - x, dz = island.z() - z, length = Math.max(1, Math.hypot(dx, dz));
            for (double t = 0; t <= length; t += 2) {
                int px = (int) Math.floor(x + dx / length * t), pz = (int) Math.floor(z + dz / length * t);
                if (layout.height(px, pz) > fr.annocraft.world.IslandLayout.SEA_LEVEL) { cx = px; cz = pz; break; }
            }
        }
        for (int r = 0; r <= 48; r++) for (int i = -r; i <= r; i++) for (int[] c : new int[][]{{cx + i, cz - r}, {cx + i, cz + r}, {cx - r, cz + i}, {cx + r, cz + i}}) {
            if (layout.height(c[0], c[1]) <= fr.annocraft.world.IslandLayout.SEA_LEVEL || !layout.inBounds(c[0], c[1])) continue;
            net.minecraft.core.BlockPos column = new net.minecraft.core.BlockPos(c[0], 0, c[1]);
            boolean built = false;
            for (var b : data.buildings().values()) if (b.overlaps(column, 1, 1) && fr.annocraft.economy.ColonyEconomy.worldOf(b.island()).equals(layout.world())) { built = true; break; }
            if (built) continue;
            int y = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, c[0], c[1]);
            net.minecraft.core.BlockPos feet = new net.minecraft.core.BlockPos(c[0], y, c[1]);
            if (!level.getFluidState(feet.below()).isEmpty() || !level.getBlockState(feet).isAir() || !level.getBlockState(feet.above()).isAir()) continue;
            return new Vec3(c[0] + .5, y, c[1] + .5);
        }
        return null;
    }
    /** Puts back a body hidden by an earlier session (logout or crash during the management view). */
    public static void recover(ServerPlayer p) { if (p.getPersistentData().contains(SAVED)) restore(p, true); }
    private static void restore(ServerPlayer p, boolean move) {
        CompoundTag saved = p.getPersistentData().getCompound(SAVED);
        if (saved.isEmpty()) return;
        p.getPersistentData().remove(SAVED);
        if (saved.getBoolean("effect")) p.removeEffect(net.minecraft.world.effect.MobEffects.INVISIBILITY);
        p.setNoGravity(saved.getBoolean("gravity"));
        p.getAbilities().mayfly = saved.getBoolean("mayfly") || p.isCreative() || p.isSpectator();
        p.getAbilities().flying = saved.getBoolean("flying") && p.getAbilities().mayfly;
        p.getAbilities().invulnerable = saved.getBoolean("invulnerable") || p.isCreative() || p.isSpectator();
        p.onUpdateAbilities();
        p.setDeltaMovement(Vec3.ZERO); p.fallDistance = 0;
        if (move && p.isAlive() && p.level().dimension().location().toString().equals(saved.getString("dimension")))
            p.connection.teleport(saved.getDouble("x"), saved.getDouble("y"), saved.getDouble("z"), saved.getFloat("yaw"), saved.getFloat("pitch"));
    }
    /** Centre of the player's RTS camera, when one is open. */
    public static Optional<double[]> focus(ServerPlayer p) { Session s = sessions.get(p.getUUID()); return s == null ? Optional.empty() : Optional.of(new double[]{s.x, s.z}); }
    /** Single block change for cameras watching a chunk the player's body does not track. */
    public static void blockChanged(ServerLevel level, net.minecraft.core.BlockPos pos) {
        if (sessions.isEmpty()) return;
        ChunkPos chunk = new ChunkPos(pos);
        for (ServerPlayer p : level.players()) {
            Session s = sessions.get(p.getUUID());
            if (s != null && s.level == level && s.chunks.contains(chunk) && remote(p, chunk)) p.connection.send(new ClientboundBlockUpdatePacket(level, pos));
        }
    }
    private static boolean remote(ServerPlayer p, ChunkPos chunk) {
        ChunkPos body = p.chunkPosition();
        return Math.max(Math.abs(chunk.x - body.x), Math.abs(chunk.z - body.z)) > p.server.getPlayerList().getViewDistance();
    }
    public static int ticketCount() { return sessions.values().stream().mapToInt(s -> s.chunks.size()).sum(); }
    public static int ticketCount(ServerPlayer p) { Session s = sessions.get(p.getUUID()); return s == null ? 0 : s.chunks.size(); }
    public static void refresh(ServerLevel level, net.minecraft.core.BlockPos origin, int width, int depth) {
        // Vanilla tracks the body, which follows the camera; the wide zoomed-out ring needs explicit updates.
        for (ServerPlayer p : level.getServer().getPlayerList().getPlayers()) {
            Session s = sessions.get(p.getUUID()); if (s == null || s.level != level) continue;
            for (int x = origin.getX() >> 4; x <= (origin.getX() + width - 1) >> 4; x++)
                for (int z = origin.getZ() >> 4; z <= (origin.getZ() + depth - 1) >> 4; z++) {
                    ChunkPos chunk = new ChunkPos(x, z);
                    if (s.chunks.contains(chunk) && remote(p, chunk)) p.connection.send(new ClientboundLevelChunkWithLightPacket(level.getChunk(x, z), level.getLightEngine(), null, null));
                }
        }
    }
    public static void clear(net.minecraft.server.MinecraftServer server) {
        for (ServerPlayer p : server.getPlayerList().getPlayers()) close(p);
        sessions.clear();
    }
}
