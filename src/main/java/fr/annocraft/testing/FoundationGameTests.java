package fr.annocraft.testing;

import com.mojang.authlib.GameProfile;
import fr.annocraft.AnnoCraft;
import fr.annocraft.building.*;
import fr.annocraft.server.*;
import fr.annocraft.world.*;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.*;
import net.minecraft.server.level.*;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import java.util.*;

/** Registered only by the isolated runGameTestServer configuration. Never runs in a player's world. */
@PrefixGameTestTemplate(false)
public final class FoundationGameTests {
    private static ServerLevel region(GameTestHelper h) {
        ServerLevel level = Objects.requireNonNull(h.getLevel().getServer().getLevel(AnnoCraft.ARCHIPELAGO));
        ColonyData data = ColonyData.get(level.getServer());
        data.initialize(BuildingService.generator(level).layout());
        // Foundation scenarios test placement rules, not finances. The economy test disables this synchronously.
        data.setSandbox(true); return level;
    }
    private static ServerPlayer player(ServerLevel level, String name) {
        return FakePlayerFactory.get(level, new GameProfile(UUID.nameUUIDFromBytes(name.getBytes(java.nio.charset.StandardCharsets.UTF_8)), name));
    }
    private static BlockPos center(ServerLevel level, int index) {
        var island = BuildingService.generator(level).layout().islands().get(index);
        return new BlockPos(island.x(), 74, island.z());
    }
    private static void load(ServerLevel level, BlockPos p, int radius) {
        for (int x = -radius; x <= radius; x++) for (int z = -radius; z <= radius; z++) level.getChunk((p.getX() >> 4) + x, (p.getZ() >> 4) + z);
    }
    @GameTest(template = "empty", templateNamespace = AnnoCraft.ID, timeoutTicks = 400)
    public static void sharedPlacementUpgradeAndDemolition(GameTestHelper h) {
        ServerLevel l = region(h); ServerPlayer a = player(l, "builder_a"), b = player(l, "builder_b");
        BlockPos p = center(l, 0); load(l, p, 1); ColonyData data = ColonyData.get(l.getServer());
        int before = data.buildings().size();
        h.assertTrue(BuildingService.place(a, AnnoCraft.id("residence"), p, 1).success(), "Initial residence placement failed");
        h.assertFalse(BuildingService.place(b, AnnoCraft.id("warehouse"), p, 0).success(), "Concurrent overlapping placement was accepted");
        BuildingInstance instance = data.buildings().values().stream().filter(v -> v.origin().equals(p)).findFirst().orElseThrow();
        h.assertTrue(BuildingService.upgrade(b, instance.id()).success(), "Cooperative upgrade failed");
        h.assertTrue(data.buildings().get(instance.id()).definition().equals(AnnoCraft.id("residence_2")), "Upgrade not recorded");
        h.assertTrue(BuildingService.demolish(a, instance.id()).success(), "Demolition failed");
        h.assertTrue(data.buildings().size() == before && l.getBlockState(p).isAir(), "Demolition did not restore terrain"); h.succeed();
    }
    @GameTest(template = "empty", templateNamespace = AnnoCraft.ID, timeoutTicks = 400)
    public static void rotationsAndInvalidCommands(GameTestHelper h) {
        ServerLevel l = region(h); ServerPlayer p = player(l, "rotation_tester"); BlockPos origin = center(l, 1); load(l, origin, 3);
        ColonyData data = ColonyData.get(l.getServer());
        for (int turn = 0; turn < 4; turn++) {
            BlockPos at = origin.offset(turn * 20, 0, 0);
            load(l, at, 1);
            var result = BuildingService.place(p, AnnoCraft.id("warehouse"), at, turn);
            h.assertTrue(result.success(), "Rotation " + turn + " failed: " + result.message());
            BuildingInstance b = data.buildings().values().stream().filter(v -> v.origin().equals(at)).findFirst().orElseThrow();
            h.assertTrue(!l.getBlockState(at).isAir(), "Rotated template escaped footprint");
            h.assertTrue(BuildingService.demolish(p, b.id()).success(), "Rotated demolition failed");
        }
        h.assertFalse(BuildingService.place(p, AnnoCraft.id("warehouse"), origin, -1).success(), "Invalid rotation accepted");
        h.assertFalse(BuildingService.place(p, AnnoCraft.id("unknown"), origin, 0).success(), "Unknown building accepted");
        h.assertFalse(BuildingService.place(p, AnnoCraft.id("trading_post"), origin, 0).success(), "Inland port accepted");
        h.assertFalse(BuildingService.place(p, AnnoCraft.id("residence"), new BlockPos(Integer.MAX_VALUE, 74, 0), 0).success(), "Outside boundary accepted");
        h.succeed();
    }
    @GameTest(template = "empty", templateNamespace = AnnoCraft.ID, timeoutTicks = 400)
    public static void saveRoundTripAndVersionRefusal(GameTestHelper h) {
        ServerLevel l = region(h); ServerPlayer p = player(l, "save_tester"); BlockPos at = center(l, 2); load(l, at, 1);
        h.assertTrue(BuildingService.place(p, AnnoCraft.id("residence"), at, 0).success(), "Save test placement failed");
        ColonyData data = ColonyData.get(l.getServer()); CompoundTag tag = data.save(new CompoundTag());
        ColonyData restored = ColonyData.load(tag);
        h.assertTrue(restored.buildings().keySet().equals(data.buildings().keySet()), "Building IDs changed during reload");
        h.assertTrue(restored.save(new CompoundTag()).equals(tag), "Save content changed during round trip");
        CompoundTag future = tag.copy(); future.putInt("version", 999); boolean refused = false;
        try { ColonyData.load(future); } catch (IllegalStateException expected) { refused = true; }
        h.assertTrue(refused, "Future save version accepted");
        BuildingInstance building = data.buildings().values().stream().filter(v -> v.origin().equals(at)).findFirst().orElseThrow();
        BuildingService.demolish(p, building.id()); h.succeed();
    }
    @GameTest(template = "empty", templateNamespace = AnnoCraft.ID, timeoutTicks = 400)
    public static void coastalPlacementAndBlockProtection(GameTestHelper h) {
        ServerLevel l = region(h); ServerPlayer p = player(l, "coast_tester");
        IslandLayout layout = BuildingService.generator(l).layout(); var island = layout.islands().get(4);
        BlockPos found = null;
        for (int angle = 0; angle < 360 && found == null; angle += 3) {
            double a = Math.toRadians(angle), coast = 1 + .07 * Math.sin(a * 3 + island.phase()) + .04 * Math.cos(a * 5 - island.phase());
            int x = (int)Math.round(island.x() + Math.cos(a) * island.radiusX() * .87 * coast) - 5;
            int z = (int)Math.round(island.z() + Math.sin(a) * island.radiusZ() * .87 * coast) - 4;
            boolean flat = true;
            for (int dx = 0; dx < 11; dx++) for (int dz = 0; dz < 9; dz++) if (layout.height(x + dx, z + dz) != 66) flat = false;
            if (!flat) continue;
            BlockPos at = new BlockPos(x, 67, z); load(l, at, 1);
            if (BuildingService.place(p, AnnoCraft.id("trading_post"), at, 0).success()) found = at;
        }
        h.assertTrue(found != null, "No valid coastal trading post placement");
        var event = new net.minecraftforge.event.level.BlockEvent.BreakEvent(l, found, l.getBlockState(found), p);
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.post(event);
        h.assertTrue(event.isCanceled(), "Managed structure could be broken manually");
        final BlockPos placed = found;
        BuildingInstance b = ColonyData.get(l.getServer()).buildings().values().stream().filter(v -> v.origin().equals(placed)).findFirst().orElseThrow();
        h.assertTrue(BuildingService.demolish(p, b.id()).success(), "Coastal demolition failed"); h.succeed();
    }
    @GameTest(template = "empty", templateNamespace = AnnoCraft.ID, timeoutTicks = 400)
    public static void economyCostsRoadsAndMigration(GameTestHelper h) {
        ServerLevel l = region(h); ServerPlayer p = player(l, "economy_tester"); BlockPos at = center(l, 5); load(l, at, 2);
        ColonyData data = ColonyData.get(l.getServer()); var economy = data.economy();
        String island = BuildingService.generator(l).layout().islandAt(at.getX(), at.getZ()).orElseThrow().id();
        // Synchronous section: other GameTests in the batch expect sandbox mode between ticks.
        data.setSandbox(false);
        try {
            economy.addStock(island, "timber", -economy.stock(island, "timber"));
            var refused = BuildingService.place(p, AnnoCraft.id("residence"), at, 0);
            h.assertTrue(!refused.success() && refused.message().endsWith("no_goods"), "Residence built without timber: " + refused.message());
            economy.addStock(island, "timber", 10);
            double coins = economy.coins();
            h.assertTrue(BuildingService.place(p, AnnoCraft.id("residence"), at, 0).success(), "Paid residence placement failed");
            h.assertTrue(Math.abs(economy.stock(island, "timber") - 8) < 1e-9, "Residence timber cost not deducted");
            BlockPos hut = at.offset(10, 0, 0);
            h.assertTrue(BuildingService.place(p, AnnoCraft.id("lumberjack"), hut, 0).success(), "Lumberjack placement failed");
            h.assertTrue(Math.abs(economy.coins() - (coins - 50)) < 1e-9, "Lumberjack coin cost not deducted");
            BuildingInstance house = data.buildings().values().stream().filter(v -> v.origin().equals(at)).findFirst().orElseThrow();
            var early = BuildingService.upgrade(p, house.id());
            h.assertTrue(!early.success() && early.message().endsWith("upgrade_not_ready"), "Unfilled residence upgraded: " + early.message());

            BlockPos from = new BlockPos(at.getX() - 1, 0, at.getZ()), to = new BlockPos(at.getX() - 1, 0, at.getZ() + 14);
            h.assertTrue(RoadService.place(p, from, to).success(), "Road placement failed");
            BlockPos ground = new BlockPos(at.getX() - 1, 73, at.getZ() + 3);
            h.assertTrue(data.road(ground) && l.getBlockState(ground).is(net.minecraft.world.level.block.Blocks.DIRT_PATH), "Road block missing");
            h.assertTrue(data.roads().size() == 15, "Road length incorrect: " + data.roads().size());
            var event = new net.minecraftforge.event.level.BlockEvent.BreakEvent(l, ground, l.getBlockState(ground), p);
            net.minecraftforge.common.MinecraftForge.EVENT_BUS.post(event);
            h.assertTrue(event.isCanceled(), "Road could be broken manually");
            var blocked = BuildingService.place(p, AnnoCraft.id("residence"), new BlockPos(at.getX() - 4, 74, at.getZ() + 12), 0);
            h.assertTrue(!blocked.success() && blocked.message().endsWith("overlap"), "Residence accepted over a road: " + blocked.message());
            h.assertFalse(RoadService.place(p, from, new BlockPos(at.getX() - 1, 0, at.getZ() + RoadService.MAX_LENGTH)).success(), "Overlong road accepted");

            CompoundTag saved = data.save(new CompoundTag());
            h.assertTrue(ColonyData.load(saved).save(new CompoundTag()).equals(saved), "Economy or roads changed during save round trip");
            CompoundTag legacy = saved.copy(); legacy.putInt("version", 1); legacy.remove("economy"); legacy.remove("roads");
            ColonyData migrated = ColonyData.load(legacy);
            h.assertTrue(migrated.economy().sandbox() && migrated.buildings().keySet().equals(data.buildings().keySet()), "Version 1 migration lost buildings or budget mode");

            h.assertTrue(RoadService.remove(p, from, to).success(), "Road removal failed");
            h.assertTrue(data.roads().isEmpty() && l.getBlockState(ground).is(net.minecraft.world.level.block.Blocks.GRASS_BLOCK), "Road removal did not restore terrain");
            for (BuildingInstance b : List.copyOf(data.buildings().values())) if (b.origin().equals(at) || b.origin().equals(hut)) BuildingService.demolish(p, b.id());
        } finally { data.setSandbox(true); }
        h.succeed();
    }
    @GameTest(template = "empty", templateNamespace = AnnoCraft.ID, timeoutTicks = 800)
    public static void hundredBuildingsAndTwoCameraSessions(GameTestHelper h) {
        ServerLevel l = region(h); BlockPos origin = center(l, 3); load(l, origin, 5);
        l.getChunkSource().addRegionTicket(TicketType.PORTAL, new net.minecraft.world.level.ChunkPos(origin), 7, origin);
        ServerPlayer a = player(l, "stress_a"), b = player(l, "stress_b"); a.setPos(origin.getX(), 74, origin.getZ()); b.setPos(origin.getX(), 74, origin.getZ());
        ColonyData data = ColonyData.get(l.getServer()); int before = data.buildings().size(); List<UUID> added = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            BlockPos at = origin.offset((i % 10 - 5) * 10, 0, (i / 10 - 5) * 10);
            h.assertTrue(BuildingService.place(i % 2 == 0 ? a : b, AnnoCraft.id("residence"), at, i % 4).success(), "Stress placement " + i + " failed");
            added.add(data.buildings().values().stream().filter(v -> v.origin().equals(at)).findFirst().orElseThrow().id());
        }
        h.assertTrue(data.buildings().size() == before + 100, "Stress building count incorrect");
        CameraSessions.update(a, true, origin.getX(), origin.getZ()); CameraSessions.update(b, true, origin.getX(), origin.getZ());
        h.assertTrue(CameraSessions.ticketCount() == CameraSessions.MAX_CHUNKS * 2, "Per-player ticket accounting failed");
        h.runAfterDelay(6, () -> {
            CameraSessions.update(a, true, origin.getX() + 32, origin.getZ());
            CameraSessions.update(b, true, origin.getX() - 32, origin.getZ());
            h.assertTrue(CameraSessions.ticketCount() == CameraSessions.MAX_CHUNKS * 2, "Moving cameras accumulated tickets");
            CameraSessions.close(a);
            h.assertTrue(CameraSessions.ticketCount() == CameraSessions.MAX_CHUNKS, "One player removed the other's tickets");
            // Exercise the expiry path as well as explicit close/disconnect cleanup.
            h.runAfterDelay(105, () -> {
                CameraSessions.tick(b); h.assertTrue(CameraSessions.ticketCount() == 0, "Expired camera tickets leaked");
                for (UUID id : added) BuildingService.demolish(a, id);
                l.getChunkSource().removeRegionTicket(TicketType.PORTAL, new net.minecraft.world.level.ChunkPos(origin), 7, origin);
                h.assertTrue(data.buildings().size() == before, "Stress cleanup failed"); h.succeed();
            });
        });
    }
}
