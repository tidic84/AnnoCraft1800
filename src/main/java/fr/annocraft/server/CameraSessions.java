package fr.annocraft.server;

import fr.annocraft.AnnoCraft;
import net.minecraft.network.protocol.game.*;
import net.minecraft.server.level.*;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;
import java.util.*;

public final class CameraSessions {
    public static final int RADIUS = 6;
    public static final int MAX_CHUNKS = (2 * RADIUS + 1) * (2 * RADIUS + 1);
    private static final TicketType<UUID> TICKET = TicketType.create("annocraft_camera", UUID::compareTo);
    private static final Map<UUID, Session> sessions = new HashMap<>();
    private static final class Session {
        final ServerLevel level; final Vec3 body; final float yaw, pitch;
        final Set<ChunkPos> chunks = new HashSet<>();
        double x, z; long lastUpdate, heartbeat;
        boolean acknowledged;
        Session(ServerPlayer p) { level = p.serverLevel(); body = p.position(); yaw = p.getYRot(); pitch = p.getXRot(); x = body.x; z = body.z; lastUpdate = -100; }
    }
    public static void update(ServerPlayer p, boolean active, double x, double z) {
        if (!active) { close(p); return; }
        if (!p.level().dimension().equals(AnnoCraft.ARCHIPELAGO) || !Double.isFinite(x) || !Double.isFinite(z)) return;
        Session s = sessions.computeIfAbsent(p.getUUID(), id -> new Session(p));
        long now = p.serverLevel().getGameTime();
        if (now - s.lastUpdate < 5) return;
        if (!BuildingService.generator(s.level).layout().inBounds((int)x, (int)z) || Math.hypot(x - s.x, z - s.z) > 64) return;
        s.lastUpdate = now; s.heartbeat = now; s.x = x; s.z = z;
        if (!s.acknowledged) {
            s.acknowledged = true;
            p.connection.teleport(s.body.x, s.body.y, s.body.z, s.yaw, s.pitch);
            fr.annocraft.network.AnnoNetwork.CHANNEL.send(net.minecraftforge.network.PacketDistributor.PLAYER.with(() -> p),
                    new fr.annocraft.network.AnnoNetwork.CameraAnchor(s.body.x, s.body.y, s.body.z, s.yaw, s.pitch));
        }
        p.connection.send(new ClientboundSetChunkCacheRadiusPacket(Math.max(RADIUS, p.server.getPlayerList().getViewDistance())));
        ChunkPos center = new ChunkPos(net.minecraft.core.BlockPos.containing(x, 80, z));
        Set<ChunkPos> next = new HashSet<>();
        for (int cx = -RADIUS; cx <= RADIUS; cx++) for (int cz = -RADIUS; cz <= RADIUS; cz++) next.add(new ChunkPos(center.x + cx, center.z + cz));
        p.connection.send(new ClientboundSetChunkCacheCenterPacket(center.x, center.z));
        for (ChunkPos old : Set.copyOf(s.chunks)) if (!next.contains(old)) {
            s.level.getChunkSource().removeRegionTicket(TICKET, old, 2, p.getUUID());
            forgetIfRemote(p, old); s.chunks.remove(old);
        }
        for (ChunkPos pos : next) if (s.chunks.add(pos)) {
            s.level.getChunkSource().addRegionTicket(TICKET, pos, 2, p.getUUID());
            var chunk = s.level.getChunk(pos.x, pos.z);
            p.connection.send(new ClientboundLevelChunkWithLightPacket(chunk, s.level.getLightEngine(), null, null));
        }
    }
    private static void forgetIfRemote(ServerPlayer p, ChunkPos pos) {
        int range = p.server.getPlayerList().getViewDistance(); ChunkPos body = p.chunkPosition();
        if (Math.max(Math.abs(pos.x - body.x), Math.abs(pos.z - body.z)) > range) p.connection.send(new ClientboundForgetLevelChunkPacket(pos.x, pos.z));
    }
    public static void tick(ServerPlayer p) {
        Session s = sessions.get(p.getUUID()); if (s == null) return;
        if (p.serverLevel() != s.level || !p.isAlive() || s.level.getGameTime() - s.heartbeat > 100) { close(p); return; }
        p.setDeltaMovement(Vec3.ZERO); p.fallDistance = 0;
        if (p.position().distanceToSqr(s.body) > .000001) p.connection.teleport(s.body.x, s.body.y, s.body.z, s.yaw, s.pitch);
    }
    public static void close(ServerPlayer p) {
        Session s = sessions.remove(p.getUUID()); if (s == null) return;
        for (ChunkPos pos : s.chunks) { s.level.getChunkSource().removeRegionTicket(TICKET, pos, 2, p.getUUID()); forgetIfRemote(p, pos); }
        p.connection.send(new ClientboundSetChunkCacheCenterPacket(p.chunkPosition().x, p.chunkPosition().z));
        p.connection.send(new ClientboundSetChunkCacheRadiusPacket(p.server.getPlayerList().getViewDistance()));
        // Moving the client cache to a remote camera can evict its original body chunks.
        // Vanilla's player tracking never changed, so it would otherwise not resend them.
        if (p.serverLevel() == s.level) {
            int range = p.server.getPlayerList().getViewDistance(); ChunkPos center = p.chunkPosition();
            for (int x = -range; x <= range; x++) for (int z = -range; z <= range; z++) {
                var chunk = s.level.getChunkSource().getChunkNow(center.x + x, center.z + z);
                if (chunk != null) p.connection.send(new ClientboundLevelChunkWithLightPacket(chunk, s.level.getLightEngine(), null, null));
            }
            if (p.isAlive()) p.connection.teleport(s.body.x, s.body.y, s.body.z, s.yaw, s.pitch);
        }
    }
    public static int ticketCount() { return sessions.values().stream().mapToInt(s -> s.chunks.size()).sum(); }
    public static void refresh(ServerLevel level, net.minecraft.core.BlockPos origin, int width, int depth) {
        // Vanilla tracks the player's body. Remote camera subscribers need explicit world updates.
        for (ServerPlayer p : level.getServer().getPlayerList().getPlayers()) {
            Session s = sessions.get(p.getUUID()); if (s == null || s.level != level) continue;
            for (int x = origin.getX() >> 4; x <= (origin.getX() + width - 1) >> 4; x++)
                for (int z = origin.getZ() >> 4; z <= (origin.getZ() + depth - 1) >> 4; z++) {
                    ChunkPos chunk = new ChunkPos(x, z);
                    if (s.chunks.contains(chunk)) p.connection.send(new ClientboundLevelChunkWithLightPacket(level.getChunk(x, z), level.getLightEngine(), null, null));
                }
        }
    }
    public static void clear(net.minecraft.server.MinecraftServer server) {
        for (ServerPlayer p : server.getPlayerList().getPlayers()) close(p);
        sessions.clear();
    }
}
