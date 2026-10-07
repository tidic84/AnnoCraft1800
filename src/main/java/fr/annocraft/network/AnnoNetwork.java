package fr.annocraft.network;

import fr.annocraft.AnnoCraft;
import fr.annocraft.client.ClientState;
import fr.annocraft.server.*;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.*;
import net.minecraftforge.network.simple.SimpleChannel;
import java.util.*;
import java.util.function.Supplier;

public final class AnnoNetwork {
    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(AnnoCraft.id("main"), () -> "3", "3"::equals, "3"::equals);
    public record BuildCommand(int action, ResourceLocation definition, BlockPos origin, int rotation, UUID target) {
        public void encode(FriendlyByteBuf b) { b.writeVarInt(action); b.writeResourceLocation(definition); b.writeBlockPos(origin); b.writeVarInt(rotation); b.writeUUID(target); }
        public static BuildCommand decode(FriendlyByteBuf b) { return new BuildCommand(b.readVarInt(), b.readResourceLocation(), b.readBlockPos(), b.readVarInt(), b.readUUID()); }
        public static void handle(BuildCommand m, Supplier<NetworkEvent.Context> ctx) {
            ctx.get().enqueueWork(() -> {
                ServerPlayer p = ctx.get().getSender(); if (p == null) return;
                if (!ServerEvents.acceptCommand(p)) {
                    CHANNEL.send(PacketDistributor.PLAYER.with(() -> p), new Feedback(false, "message.annocraft1800.rate_limited")); return;
                }
                BuildingService.Result result = switch (m.action) {
                    case 0 -> BuildingService.place(p, m.definition, m.origin, m.rotation);
                    case 1 -> BuildingService.demolish(p, m.target);
                    case 2 -> BuildingService.upgrade(p, m.target);
                    default -> new BuildingService.Result(false, "message.annocraft1800.invalid_building");
                };
                CHANNEL.send(PacketDistributor.PLAYER.with(() -> p), new Feedback(result.success(), result.message()));
                if (result.success()) syncAll(p.server);
            }); ctx.get().setPacketHandled(true);
        }
    }
    public record CameraCommand(boolean active, double x, double z) {
        public void encode(FriendlyByteBuf b) { b.writeBoolean(active); b.writeDouble(x); b.writeDouble(z); }
        public static CameraCommand decode(FriendlyByteBuf b) { return new CameraCommand(b.readBoolean(), b.readDouble(), b.readDouble()); }
        public static void handle(CameraCommand m, Supplier<NetworkEvent.Context> ctx) {
            ctx.get().enqueueWork(() -> { ServerPlayer p = ctx.get().getSender(); if (p != null) CameraSessions.update(p, m.active, m.x, m.z); });
            ctx.get().setPacketHandled(true);
        }
    }
    public record Snapshot(CompoundTag data) {
        public void encode(FriendlyByteBuf b) { b.writeNbt(data); }
        public static Snapshot decode(FriendlyByteBuf b) { return new Snapshot(b.readNbt()); }
        public static void handle(Snapshot m, Supplier<NetworkEvent.Context> ctx) {
            ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> ClientState.receive(m.data)));
            ctx.get().setPacketHandled(true);
        }
    }
    public record Feedback(boolean success, String message) {
        public void encode(FriendlyByteBuf b) { b.writeBoolean(success); b.writeUtf(message, 128); }
        public static Feedback decode(FriendlyByteBuf b) { return new Feedback(b.readBoolean(), b.readUtf(128)); }
        public static void handle(Feedback m, Supplier<NetworkEvent.Context> ctx) {
            ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> ClientState.feedback(m.success, m.message)));
            ctx.get().setPacketHandled(true);
        }
    }
    public record SyncRequest() {
        public void encode(FriendlyByteBuf b) { }
        public static SyncRequest decode(FriendlyByteBuf b) { return new SyncRequest(); }
        public static void handle(SyncRequest m, Supplier<NetworkEvent.Context> ctx) {
            ctx.get().enqueueWork(() -> {
                ServerPlayer p = ctx.get().getSender();
                if (p != null && AnnoCraft.isColony(p.level().dimension()) && ServerEvents.acceptSync(p)) sync(p);
            }); ctx.get().setPacketHandled(true);
        }
    }
    public record CameraAnchor(double x, double y, double z, float yaw, float pitch) {
        public void encode(FriendlyByteBuf b) { b.writeDouble(x); b.writeDouble(y); b.writeDouble(z); b.writeFloat(yaw); b.writeFloat(pitch); }
        public static CameraAnchor decode(FriendlyByteBuf b) { return new CameraAnchor(b.readDouble(), b.readDouble(), b.readDouble(), b.readFloat(), b.readFloat()); }
        public static void handle(CameraAnchor m, Supplier<NetworkEvent.Context> ctx) {
            ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> fr.annocraft.client.RtsController.acceptAnchor(m.x, m.y, m.z, m.yaw, m.pitch)));
            ctx.get().setPacketHandled(true);
        }
    }
    public record RoadCommand(boolean remove, BlockPos from, BlockPos to) {
        public void encode(FriendlyByteBuf b) { b.writeBoolean(remove); b.writeBlockPos(from); b.writeBlockPos(to); }
        public static RoadCommand decode(FriendlyByteBuf b) { return new RoadCommand(b.readBoolean(), b.readBlockPos(), b.readBlockPos()); }
        public static void handle(RoadCommand m, Supplier<NetworkEvent.Context> ctx) {
            ctx.get().enqueueWork(() -> {
                ServerPlayer p = ctx.get().getSender(); if (p == null) return;
                if (!ServerEvents.acceptCommand(p)) {
                    CHANNEL.send(PacketDistributor.PLAYER.with(() -> p), new Feedback(false, "message.annocraft1800.rate_limited")); return;
                }
                BuildingService.Result result = m.remove ? RoadService.remove(p, m.from, m.to) : RoadService.place(p, m.from, m.to);
                CHANNEL.send(PacketDistributor.PLAYER.with(() -> p), new Feedback(result.success(), result.message()));
                if (result.success()) syncAll(p.server);
            }); ctx.get().setPacketHandled(true);
        }
    }
    /** Periodic economy state; much smaller than the full snapshot, which carries structure previews. */
    public record EconomyUpdate(CompoundTag data) {
        public void encode(FriendlyByteBuf b) { b.writeNbt(data); }
        public static EconomyUpdate decode(FriendlyByteBuf b) { return new EconomyUpdate(b.readNbt()); }
        public static void handle(EconomyUpdate m, Supplier<NetworkEvent.Context> ctx) {
            ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> ClientState.receiveEconomy(m.data)));
            ctx.get().setPacketHandled(true);
        }
    }
    /** Management-screen action: fleet, trade, diplomacy, campaign or travel. */
    public record ActionCommand(String action, CompoundTag args) {
        public void encode(FriendlyByteBuf b) { b.writeUtf(action, 32); b.writeNbt(args); }
        public static ActionCommand decode(FriendlyByteBuf b) { return new ActionCommand(b.readUtf(32), b.readNbt()); }
        public static void handle(ActionCommand m, Supplier<NetworkEvent.Context> ctx) {
            ctx.get().enqueueWork(() -> {
                ServerPlayer p = ctx.get().getSender(); if (p == null) return;
                if (!ServerEvents.acceptCommand(p)) {
                    CHANNEL.send(PacketDistributor.PLAYER.with(() -> p), new Feedback(false, "message.annocraft1800.rate_limited")); return;
                }
                BuildingService.Result result = ColonyActions.handle(p, m.action, m.args == null ? new CompoundTag() : m.args);
                CHANNEL.send(PacketDistributor.PLAYER.with(() -> p), new Feedback(result.success(), result.message()));
                if (result.success()) syncEconomy(p.server);
            }); ctx.get().setPacketHandled(true);
        }
    }
    public static void action(String action, Object... args) { CHANNEL.sendToServer(new ActionCommand(action, ColonyActions.args(args))); }
    public static void syncEconomy(net.minecraft.server.MinecraftServer server) {
        EconomyUpdate update = null;
        for (ServerPlayer p : server.getPlayerList().getPlayers()) if (AnnoCraft.isColony(p.level().dimension())) {
            if (update == null) update = new EconomyUpdate(ColonyData.get(server).economySnapshot());
            EconomyUpdate message = update;
            CHANNEL.send(PacketDistributor.PLAYER.with(() -> p), message);
        }
    }
    public static void register() {
        CHANNEL.registerMessage(0, BuildCommand.class, BuildCommand::encode, BuildCommand::decode, BuildCommand::handle, Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(1, CameraCommand.class, CameraCommand::encode, CameraCommand::decode, CameraCommand::handle, Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(2, Snapshot.class, Snapshot::encode, Snapshot::decode, Snapshot::handle, Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CHANNEL.registerMessage(3, Feedback.class, Feedback::encode, Feedback::decode, Feedback::handle, Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CHANNEL.registerMessage(4, SyncRequest.class, SyncRequest::encode, SyncRequest::decode, SyncRequest::handle, Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(5, CameraAnchor.class, CameraAnchor::encode, CameraAnchor::decode, CameraAnchor::handle, Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CHANNEL.registerMessage(6, RoadCommand.class, RoadCommand::encode, RoadCommand::decode, RoadCommand::handle, Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(8, ActionCommand.class, ActionCommand::encode, ActionCommand::decode, ActionCommand::handle, Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(7, EconomyUpdate.class, EconomyUpdate::encode, EconomyUpdate::decode, EconomyUpdate::handle, Optional.of(NetworkDirection.PLAY_TO_CLIENT));
    }
    public static void sync(ServerPlayer p) { CHANNEL.send(PacketDistributor.PLAYER.with(() -> p), new Snapshot(ColonyData.get(p.server).snapshot(p.server))); }
    /** Buildings, roads and economy after a change; definitions are not resent. */
    public static void syncAll(net.minecraft.server.MinecraftServer server) {
        Snapshot update = null;
        for (ServerPlayer p : server.getPlayerList().getPlayers()) if (AnnoCraft.isColony(p.level().dimension())) {
            if (update == null) update = new Snapshot(ColonyData.get(server).snapshot(server, false));
            Snapshot message = update;
            CHANNEL.send(PacketDistributor.PLAYER.with(() -> p), message);
        }
    }
}
