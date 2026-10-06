package fr.annocraft.server;

import fr.annocraft.AnnoCraft;
import fr.annocraft.building.BuildingInstance;
import fr.annocraft.network.AnnoNetwork;
import fr.annocraft.world.*;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.*;
import net.minecraft.world.level.*;
import net.minecraftforge.event.*;
import net.minecraftforge.event.entity.player.*;
import net.minecraftforge.event.level.*;
import net.minecraftforge.event.server.*;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import java.util.*;

public final class ServerEvents {
    private static final Map<UUID, Long> LAST_COMMAND = new HashMap<>();
    private static final Map<UUID, Long> LAST_SYNC = new HashMap<>();
    public static boolean acceptSync(ServerPlayer p) {
        long now = p.serverLevel().getGameTime(); Long last = LAST_SYNC.get(p.getUUID());
        if (last != null && now - last < 100) return false;
        LAST_SYNC.put(p.getUUID(), now); return true;
    }
    public static boolean acceptCommand(ServerPlayer p) {
        long now = p.serverLevel().getGameTime();
        Long last = LAST_COMMAND.get(p.getUUID());
        if (last != null && now - last < 2) return false;
        LAST_COMMAND.put(p.getUUID(), now); return true;
    }
    @SubscribeEvent public static void started(ServerStartedEvent event) {
        ServerLevel level = event.getServer().getLevel(AnnoCraft.ARCHIPELAGO);
        if (level == null || !(level.getChunkSource().getGenerator() instanceof ArchipelagoGenerator))
            throw new IllegalStateException("AnnoCraft archipelago dimension missing or generator invalid");
        IslandLayout layout = BuildingService.generator(level).layout();
        ColonyData.get(event.getServer()).initialize(layout);
        level.getWorldBorder().setCenter(0, 0); level.getWorldBorder().setSize(layout.size());
        IslandLayout.Island island = layout.islands().get(0);
        level.setDefaultSpawnPos(new BlockPos(island.x(), 74, island.z()), 0);
    }
    @SubscribeEvent public static void stopping(ServerStoppingEvent event) { CameraSessions.clear(event.getServer()); LAST_COMMAND.clear(); LAST_SYNC.clear(); }
    @SubscribeEvent public static void stopped(ServerStoppedEvent event) { ColonyData.release(event.getServer()); }
    @SubscribeEvent public static void commands(RegisterCommandsEvent event) {
        if (Boolean.getBoolean("annocraft1800.networkSmoke")) event.getDispatcher().register(Commands.literal("anno_test_report")
                .executes(ctx -> { fr.annocraft.testing.NetworkGameTest.report(ctx.getSource().getPlayerOrException()); return 1; }));
        event.getDispatcher().register(Commands.literal("anno")
                .then(Commands.literal("join").executes(ctx -> { join(ctx.getSource().getPlayerOrException()); return 1; }))
                .then(Commands.literal("leave").executes(ctx -> { leave(ctx.getSource().getPlayerOrException()); return 1; }))
                .then(Commands.literal("status").executes(ctx -> {
                    ColonyData data = ColonyData.get(ctx.getSource().getServer());
                    ctx.getSource().sendSuccess(() -> Component.literal("AnnoCraft: " + data.buildings().size() + " buildings, revision " + data.revision() + ", camera tickets " + CameraSessions.ticketCount()), false); return 1;
                })));
    }
    public static void join(ServerPlayer player) {
        if (player.level().dimension().equals(AnnoCraft.ARCHIPELAGO)) { AnnoNetwork.sync(player); return; }
        CompoundTag back = new CompoundTag();
        back.putString("dimension", player.level().dimension().location().toString());
        back.putDouble("x", player.getX()); back.putDouble("y", player.getY()); back.putDouble("z", player.getZ());
        back.putFloat("yaw", player.getYRot()); back.putFloat("pitch", player.getXRot());
        back.putInt("mode", player.gameMode.getGameModeForPlayer().getId());
        player.getPersistentData().put("annocraft_return", back);
        ServerLevel level = Objects.requireNonNull(player.server.getLevel(AnnoCraft.ARCHIPELAGO));
        IslandLayout.Island first = BuildingService.generator(level).layout().islands().get(0);
        player.setGameMode(GameType.ADVENTURE);
        player.teleportTo(level, first.x() + .5, 74, first.z() + .5, 0, 0);
        AnnoNetwork.sync(player);
    }
    private static void leave(ServerPlayer p) {
        if (!p.level().dimension().equals(AnnoCraft.ARCHIPELAGO)) return;
        CameraSessions.close(p);
        CompoundTag back = p.getPersistentData().getCompound("annocraft_return");
        ServerLevel target = back.contains("dimension") ? p.server.getLevel(ResourceKey.create(Registries.DIMENSION, new ResourceLocation(back.getString("dimension")))) : p.server.overworld();
        if (target == null) target = p.server.overworld();
        BlockPos spawn = target.getSharedSpawnPos();
        p.teleportTo(target, back.contains("x") ? back.getDouble("x") : spawn.getX() + .5,
                back.contains("y") ? back.getDouble("y") : spawn.getY(), back.contains("z") ? back.getDouble("z") : spawn.getZ() + .5,
                back.getFloat("yaw"), back.getFloat("pitch"));
        p.setGameMode(back.contains("mode") ? GameType.byId(back.getInt("mode")) : GameType.SURVIVAL);
        p.getPersistentData().remove("annocraft_return");
    }
    @SubscribeEvent public static void login(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer p && p.level().dimension().equals(AnnoCraft.ARCHIPELAGO)) { p.setGameMode(GameType.ADVENTURE); AnnoNetwork.sync(p); }
    }
    @SubscribeEvent public static void logout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer p) { CameraSessions.close(p); LAST_COMMAND.remove(p.getUUID()); LAST_SYNC.remove(p.getUUID()); }
    }
    @SubscribeEvent public static void dimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer p) {
            CameraSessions.close(p);
            if (p.level().dimension().equals(AnnoCraft.ARCHIPELAGO)) { p.setGameMode(GameType.ADVENTURE); AnnoNetwork.sync(p); }
        }
    }
    @SubscribeEvent public static void tick(TickEvent.PlayerTickEvent event) {
        if (event.phase == TickEvent.Phase.END && event.player instanceof ServerPlayer p) CameraSessions.tick(p);
    }
    private static boolean protectedAt(LevelAccessor world, BlockPos pos) {
        if (!(world instanceof ServerLevel level) || !level.dimension().equals(AnnoCraft.ARCHIPELAGO)) return false;
        return ColonyData.get(level.getServer()).buildings().values().stream().anyMatch(b -> b.contains(pos));
    }
    @SubscribeEvent public static void breaking(BlockEvent.BreakEvent event) {
        if (protectedAt(event.getLevel(), event.getPos())) event.setCanceled(true);
    }
    @SubscribeEvent public static void placing(BlockEvent.EntityPlaceEvent event) {
        if (protectedAt(event.getLevel(), event.getPos())) event.setCanceled(true);
    }
    @SubscribeEvent public static void interact(PlayerInteractEvent.RightClickBlock event) {
        if (protectedAt(event.getLevel(), event.getPos()) || protectedAt(event.getLevel(), event.getPos().relative(event.getFace() == null ? net.minecraft.core.Direction.UP : event.getFace()))) event.setCanceled(true);
    }
    @SubscribeEvent public static void explosion(ExplosionEvent.Detonate event) {
        if (event.getLevel() instanceof ServerLevel level && level.dimension().equals(AnnoCraft.ARCHIPELAGO))
            event.getAffectedBlocks().removeIf(pos -> protectedAt(level, pos));
    }
    @SubscribeEvent public static void piston(PistonEvent.Pre event) {
        if (event.getLevel() instanceof ServerLevel level && level.dimension().equals(AnnoCraft.ARCHIPELAGO)) event.setCanceled(true);
    }
    @SubscribeEvent public static void fluids(BlockEvent.FluidPlaceBlockEvent event) {
        if (protectedAt(event.getLevel(), event.getPos())) event.setCanceled(true);
    }
}
