package fr.annocraft.server;

import fr.annocraft.AnnoCraft;
import fr.annocraft.economy.Colony;
import fr.annocraft.network.AnnoNetwork;
import fr.annocraft.world.*;
import com.mojang.brigadier.arguments.BoolArgumentType;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
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
        ColonyData colony = ColonyData.get(event.getServer());
        for (ResourceKey<Level> key : AnnoCraft.WORLDS) {
            ServerLevel level = event.getServer().getLevel(key);
            if (level == null || !(level.getChunkSource().getGenerator() instanceof ArchipelagoGenerator))
                throw new IllegalStateException("AnnoCraft dimension " + key.location() + " missing or generator invalid");
            IslandLayout layout = BuildingService.generator(level).layout();
            colony.initialize(layout);
            level.getWorldBorder().setCenter(0, 0); level.getWorldBorder().setSize(layout.size());
            IslandLayout.Island island = layout.islands().get(0);
            level.setDefaultSpawnPos(new BlockPos(island.x(), 74, island.z()), 0);
        }
        // Graphical smoke benches exercise construction and rendering, not finances.
        if (Boolean.getBoolean("annocraft1800.clientSmoke") || Boolean.getBoolean("annocraft1800.networkSmoke")) colony.setSandbox(true);
        String first = BuildingService.generator(event.getServer().getLevel(AnnoCraft.ARCHIPELAGO)).layout().islands().get(0).id();
        // Gives the graphical smoke test island stock lines to render.
        if (Boolean.getBoolean("annocraft1800.clientSmoke") && colony.economy().stock(first, "fish") == 0) { colony.economy().addStock(first, "fish", 12); colony.economy().addStock(first, "timber", 30); }
    }
    /** Datapack reloads can change building definitions: resend the full snapshot. */
    @SubscribeEvent public static void datapack(OnDatapackSyncEvent event) {
        if (event.getPlayer() != null) { if (AnnoCraft.isColony(event.getPlayer().level().dimension())) AnnoNetwork.sync(event.getPlayer()); return; }
        for (ServerPlayer p : event.getPlayerList().getPlayers()) if (AnnoCraft.isColony(p.level().dimension())) AnnoNetwork.sync(p);
    }
    @SubscribeEvent public static void stopping(ServerStoppingEvent event) { CameraSessions.clear(event.getServer()); LAST_COMMAND.clear(); LAST_SYNC.clear(); }
    @SubscribeEvent public static void stopped(ServerStoppedEvent event) { ColonyData.release(event.getServer()); }
    @SubscribeEvent public static void commands(RegisterCommandsEvent event) {
        if (Boolean.getBoolean("annocraft1800.networkSmoke")) event.getDispatcher().register(Commands.literal("anno_test_report")
                .executes(ctx -> { fr.annocraft.testing.NetworkGameTest.report(ctx.getSource().getPlayerOrException()); return 1; }));
        event.getDispatcher().register(Commands.literal("anno")
                .then(Commands.literal("join").executes(ctx -> { join(ctx.getSource().getPlayerOrException(), AnnoCraft.ARCHIPELAGO); return 1; })
                        .then(Commands.literal("new_world").executes(ctx -> {
                            ServerPlayer p = ctx.getSource().getPlayerOrException();
                            if (!ColonyData.get(p.server).newWorldOpen()) { ctx.getSource().sendFailure(Component.translatable("message.annocraft1800.new_world_locked")); return 0; }
                            join(p, AnnoCraft.NEW_WORLD); return 1;
                        })))
                .then(Commands.literal("leave").executes(ctx -> { leave(ctx.getSource().getPlayerOrException()); return 1; }))
                .then(Commands.literal("status").executes(ctx -> {
                    ColonyData data = ColonyData.get(ctx.getSource().getServer());
                    var e = data.economy();
                    ctx.getSource().sendSuccess(() -> Component.literal("AnnoCraft: " + data.buildings().size() + " buildings, " + data.roads().size() + " roads, "
                            + data.maritime().ships().size() + " ships, " + e.totalPopulation() + " residents, revision " + data.revision()
                            + ", camera tickets " + CameraSessions.ticketCount() + ", coins " + (long) e.coins() + " (" + Math.round(e.incomePerMinute() - e.upkeepPerMinute()) + "/min)"
                            + (e.sandbox() ? ", sandbox" : "")), false); return 1;
                }))
                .then(Commands.literal("campaign").then(Commands.literal("start").executes(ctx -> {
                    ColonyData.get(ctx.getSource().getServer()).startCampaign(); return 1;
                })))
                .then(Commands.literal("sandbox").requires(source -> source.hasPermission(2))
                        .then(Commands.argument("enabled", BoolArgumentType.bool()).executes(ctx -> {
                            boolean enabled = BoolArgumentType.getBool(ctx, "enabled");
                            ColonyData.get(ctx.getSource().getServer()).setSandbox(enabled);
                            AnnoNetwork.syncEconomy(ctx.getSource().getServer());
                            ctx.getSource().sendSuccess(() -> Component.translatable(enabled ? "message.annocraft1800.sandbox_on" : "message.annocraft1800.sandbox_off"), true); return 1;
                        }))));
    }
    @SubscribeEvent public static void serverTick(TickEvent.ServerTickEvent event) {
        // The network smoke bench compares the complete save across a restart: keep its simulation frozen.
        if (event.phase != TickEvent.Phase.END || Boolean.getBoolean("annocraft1800.networkSmoke")) return;
        var server = event.getServer();
        if (server.getTickCount() % 20 != 0 || server.getLevel(AnnoCraft.ARCHIPELAGO) == null) return;
        ColonyData data = ColonyData.get(server);
        data.tickEconomy(1);
        for (Colony.Event e : data.drainEvents()) broadcast(server, e);
        if (server.getTickCount() % 40 == 0) AnnoNetwork.syncEconomy(server);
        if (server.getTickCount() % 100 == 0) WorldLife.tick(server);
    }
    public static Component render(Colony.Event e) {
        Object[] args = e.args().stream().map(a -> a.startsWith("#") ? Component.translatable(a.substring(1)) : (Object) Component.literal(islandName(a))).toArray();
        return Component.translatable(e.key(), args).withStyle(e.good() ? net.minecraft.ChatFormatting.GREEN : net.minecraft.ChatFormatting.GOLD);
    }
    /** "island_3" reads as "3" and "nw_island_2" as "NM 2" inside sentences that already say "island". */
    public static String islandName(String id) {
        if (id.startsWith("nw_island_")) return "NM " + id.substring(10);
        return id.startsWith("island_") ? id.substring(7) : id;
    }
    private static void broadcast(MinecraftServer server, Colony.Event e) {
        Component message = render(e);
        for (ServerPlayer p : server.getPlayerList().getPlayers()) if (AnnoCraft.isColony(p.level().dimension())) p.sendSystemMessage(message);
    }
    public static void join(ServerPlayer player, ResourceKey<Level> world) {
        if (player.level().dimension().equals(world)) { AnnoNetwork.sync(player); return; }
        if (!AnnoCraft.isColony(player.level().dimension())) {
            CompoundTag back = new CompoundTag();
            back.putString("dimension", player.level().dimension().location().toString());
            back.putDouble("x", player.getX()); back.putDouble("y", player.getY()); back.putDouble("z", player.getZ());
            back.putFloat("yaw", player.getYRot()); back.putFloat("pitch", player.getXRot());
            back.putInt("mode", player.gameMode.getGameModeForPlayer().getId());
            player.getPersistentData().put("annocraft_return", back);
        }
        CameraSessions.close(player);
        teleportToColony(player, world);
    }
    /** Lands the player on the first island of a colony world, in adventure mode. */
    public static void teleportToColony(ServerPlayer player, ResourceKey<Level> world) {
        ServerLevel level = Objects.requireNonNull(player.server.getLevel(world));
        IslandLayout.Island first = BuildingService.generator(level).layout().islands().get(0);
        player.setGameMode(GameType.ADVENTURE);
        player.teleportTo(level, first.x() + .5, 74, first.z() + .5, 0, 0);
        AnnoNetwork.sync(player);
        player.sendSystemMessage(Component.translatable("message.annocraft1800.welcome", Component.translatable("world.annocraft1800." + AnnoCraft.worldName(world))).withStyle(net.minecraft.ChatFormatting.GOLD));
    }
    private static void leave(ServerPlayer p) {
        if (!AnnoCraft.isColony(p.level().dimension())) return;
        CameraSessions.close(p);
        CompoundTag back = p.getPersistentData().getCompound("annocraft_return");
        ServerLevel target = back.contains("dimension") ? p.server.getLevel(ResourceKey.create(Registries.DIMENSION, new ResourceLocation(back.getString("dimension")))) : p.server.overworld();
        if (target == null || AnnoCraft.isColony(target.dimension())) target = p.server.overworld();
        BlockPos spawn = target.getSharedSpawnPos();
        p.teleportTo(target, back.contains("x") ? back.getDouble("x") : spawn.getX() + .5,
                back.contains("y") ? back.getDouble("y") : spawn.getY(), back.contains("z") ? back.getDouble("z") : spawn.getZ() + .5,
                back.getFloat("yaw"), back.getFloat("pitch"));
        p.setGameMode(back.contains("mode") ? GameType.byId(back.getInt("mode")) : GameType.SURVIVAL);
        p.getPersistentData().remove("annocraft_return");
    }
    @SubscribeEvent public static void login(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer p && AnnoCraft.isColony(p.level().dimension())) { p.setGameMode(GameType.ADVENTURE); AnnoNetwork.sync(p); }
    }
    @SubscribeEvent public static void logout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer p) { CameraSessions.close(p); LAST_COMMAND.remove(p.getUUID()); LAST_SYNC.remove(p.getUUID()); }
    }
    @SubscribeEvent public static void dimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer p) {
            CameraSessions.close(p);
            if (AnnoCraft.isColony(p.level().dimension())) { p.setGameMode(GameType.ADVENTURE); AnnoNetwork.sync(p); }
        }
    }
    @SubscribeEvent public static void tick(TickEvent.PlayerTickEvent event) {
        if (event.phase == TickEvent.Phase.END && event.player instanceof ServerPlayer p) CameraSessions.tick(p);
    }
    private static boolean protectedAt(LevelAccessor world, BlockPos pos) {
        if (!(world instanceof ServerLevel level) || !AnnoCraft.isColony(level.dimension())) return false;
        ColonyData data = ColonyData.get(level.getServer());
        // Both worlds share one colony; buildings belong to the level they were placed in.
        String name = AnnoCraft.worldName(level.dimension());
        return data.road(name, pos) || data.managed(name, pos);
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
    /** Citizens are scenery: no trading, no leads, no name tags. */
    @SubscribeEvent public static void citizen(PlayerInteractEvent.EntityInteract event) { if (WorldLife.citizen(event.getTarget())) event.setCanceled(true); }
    @SubscribeEvent public static void citizenSpecific(PlayerInteractEvent.EntityInteractSpecific event) { if (WorldLife.citizen(event.getTarget())) event.setCanceled(true); }
    @SubscribeEvent public static void trample(BlockEvent.FarmlandTrampleEvent event) {
        if (protectedAt(event.getLevel(), event.getPos())) event.setCanceled(true);
    }
    @SubscribeEvent public static void explosion(ExplosionEvent.Detonate event) {
        if (event.getLevel() instanceof ServerLevel level && AnnoCraft.isColony(level.dimension()))
            event.getAffectedBlocks().removeIf(pos -> protectedAt(level, pos));
    }
    @SubscribeEvent public static void piston(PistonEvent.Pre event) {
        if (event.getLevel() instanceof ServerLevel level && AnnoCraft.isColony(level.dimension())) event.setCanceled(true);
    }
    @SubscribeEvent public static void fluids(BlockEvent.FluidPlaceBlockEvent event) {
        if (protectedAt(event.getLevel(), event.getPos())) event.setCanceled(true);
    }
}
