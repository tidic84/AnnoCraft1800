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
    /** First path where two saves differ, for readable round-trip failures. */
    static String diff(String path, net.minecraft.nbt.Tag a, net.minecraft.nbt.Tag b) {
        if (a instanceof CompoundTag ca && b instanceof CompoundTag cb) {
            Set<String> keys = new TreeSet<>(ca.getAllKeys()); keys.addAll(cb.getAllKeys());
            for (String k : keys) if (!Objects.equals(ca.get(k), cb.get(k))) return ca.get(k) == null || cb.get(k) == null ? path + "/" + k + " missing" : diff(path + "/" + k, ca.get(k), cb.get(k));
        } else if (a instanceof net.minecraft.nbt.ListTag la && b instanceof net.minecraft.nbt.ListTag lb) {
            if (la.size() != lb.size()) return path + " size " + la.size() + " vs " + lb.size();
            for (int i = 0; i < la.size(); i++) if (!la.get(i).equals(lb.get(i))) return diff(path + "[" + i + "]", la.get(i), lb.get(i));
        }
        String sa = String.valueOf(a), sb = String.valueOf(b);
        return path + ": " + sa.substring(0, Math.min(160, sa.length())) + " vs " + sb.substring(0, Math.min(160, sb.length()));
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
        BuildingInstance instance = data.buildings().values().stream().filter(v -> v.origin().equals(p.below())).findFirst().orElseThrow();
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
            BuildingInstance b = data.buildings().values().stream().filter(v -> v.origin().equals(at.below())).findFirst().orElseThrow();
            h.assertTrue(!l.getBlockState(at.below()).isAir(), "Rotated template escaped footprint");
            h.assertTrue(BuildingService.demolish(p, b.id()).success(), "Rotated demolition failed");
        }
        h.assertFalse(BuildingService.place(p, AnnoCraft.id("warehouse"), origin, -1).success(), "Invalid rotation accepted");
        h.assertFalse(BuildingService.place(p, AnnoCraft.id("unknown"), origin, 0).success(), "Unknown building accepted");
        h.assertFalse(BuildingService.place(p, AnnoCraft.id("trading_post"), origin, 0).success(), "Inland port accepted");
        h.assertFalse(BuildingService.place(p, AnnoCraft.id("residence"), new BlockPos(Integer.MAX_VALUE, 74, 0), 0).success(), "Outside boundary accepted");
        h.succeed();
    }
    /** A competitive archipelago: two companies with their own islands, treaties and wars, kept across a save. */
    @GameTest(template = "empty", templateNamespace = AnnoCraft.ID, timeoutTicks = 200)
    public static void competitiveCompaniesKeepTheirOwnColonies(GameTestHelper h) {
        ServerLevel l = region(h);
        ColonyData world = new ColonyData();
        world.initialize(BuildingService.generator(l).layout());
        world.adoptMode(true);
        h.assertTrue(world.competitive(), "Competitive rule not adopted by a new archipelago");
        UUID alice = UUID.randomUUID(), bob = UUID.randomUUID();
        String a = world.enroll(alice, "Alice").company(), b = world.enroll(bob, "Bob").company();
        h.assertTrue(!a.equals(b) && world.companies().containsKey(a) && world.companies().containsKey(b), "Players did not get companies of their own");
        // Alice founds a port on island 2, Bob on island 3.
        var post = fr.annocraft.building.BuildingDefinitions.get(AnnoCraft.id("trading_post"));
        BuildingInstance pa = new BuildingInstance(UUID.randomUUID(), post.id(), new BlockPos(0, 66, 0), 0, "island_2", post.width(), post.height(), post.depth(), new net.minecraft.nbt.ListTag());
        BuildingInstance pb = new BuildingInstance(UUID.randomUUID(), post.id(), new BlockPos(400, 66, 0), 0, "island_3", post.width(), post.height(), post.depth(), new net.minecraft.nbt.ListTag());
        world.put(pa, world.colony(a)); world.put(pb, world.colony(b));
        h.assertTrue(world.colony(a).ports().equals(java.util.Set.of("island_2")) && world.colony(b).ports().equals(java.util.Set.of("island_3")), "Ports were not kept apart");
        h.assertTrue(world.colonyOf(pb) == world.colony(b) && !world.colony(a).diplomacy().playerMayBuild("island_3"), "Alice may build on Bob's island");
        h.assertTrue(world.colony(a).economy() != world.colony(b).economy(), "Companies share a treasury");
        // War is declared at once; an alliance needs a trade treaty, proposed and accepted.
        h.assertTrue(world.pact(a, b, "war") == null && world.atWar(a, b), "War not declared");
        h.assertTrue(world.pact(b, a, "peace") == null && world.atWar(a, b), "Peace applied before it was accepted");
        h.assertTrue(world.pact(a, b, "accept") == null && !world.atWar(a, b), "Accepted peace not applied");
        h.assertTrue(world.pact(a, b, "alliance") != null, "Alliance accepted without a trade treaty");
        world.pact(a, b, "trade"); world.pact(b, a, "accept");
        h.assertTrue(world.stance(a, b) == fr.annocraft.economy.Diplomacy.Stance.TRADE, "Trade treaty not concluded");
        var snapshot = world.economySnapshot(a);
        h.assertTrue(snapshot.getList("rivals", net.minecraft.nbt.Tag.TAG_COMPOUND).size() == 1 && snapshot.getString("company").equals(a), "Snapshot does not show the rival company");
        CompoundTag saved = world.save(new CompoundTag());
        ColonyData reloaded = ColonyData.load(saved);
        h.assertTrue(reloaded.competitive() && reloaded.colony(b).ports().equals(java.util.Set.of("island_3")) && reloaded.stance(a, b) == fr.annocraft.economy.Diplomacy.Stance.TRADE,
                "Competitive save round trip lost companies or treaties");
        h.assertTrue(reloaded.save(new CompoundTag()).equals(saved), "Competitive save changed in a round trip: " + diff("", saved, reloaded.save(new CompoundTag())));
        h.succeed();
    }
    @GameTest(template = "empty", templateNamespace = AnnoCraft.ID, timeoutTicks = 400)
    public static void saveRoundTripAndVersionRefusal(GameTestHelper h) {
        ServerLevel l = region(h); ServerPlayer p = player(l, "save_tester"); BlockPos at = center(l, 2); load(l, at, 1);
        h.assertTrue(BuildingService.place(p, AnnoCraft.id("residence"), at, 0).success(), "Save test placement failed");
        ColonyData data = ColonyData.get(l.getServer()); CompoundTag tag = data.save(new CompoundTag());
        ColonyData restored = ColonyData.load(tag);
        h.assertTrue(restored.buildings().keySet().equals(data.buildings().keySet()), "Building IDs changed during reload");
        CompoundTag again = restored.save(new CompoundTag());
        h.assertTrue(again.equals(tag), "Save content changed during round trip: " + diff("", tag, again));
        CompoundTag future = tag.copy(); future.putInt("version", 999); boolean refused = false;
        try { ColonyData.load(future); } catch (IllegalStateException expected) { refused = true; }
        h.assertTrue(refused, "Future save version accepted");
        BuildingInstance building = data.buildings().values().stream().filter(v -> v.origin().equals(at.below())).findFirst().orElseThrow();
        BuildingService.demolish(p, building.id()); h.succeed();
    }
    @GameTest(template = "empty", templateNamespace = AnnoCraft.ID, timeoutTicks = 400)
    public static void coastalPlacementAndBlockProtection(GameTestHelper h) {
        ServerLevel l = region(h); ServerPlayer p = player(l, "coast_tester");
        IslandLayout layout = BuildingService.generator(l).layout(); var island = layout.islands().get(4);
        BlockPos found = null; int turn = 0;
        var post = fr.annocraft.building.BuildingDefinitions.get(AnnoCraft.id("trading_post"));
        // Harbour buildings straddle the shore: front row on the beach, back row over the sea.
        for (int angle = 0; angle < 360 && found == null; angle += 3) for (double r = .82; r <= .95 && found == null; r += .02) {
            double a = Math.toRadians(angle), coast = 1 + .07 * Math.sin(a * 3 + island.phase()) + .04 * Math.cos(a * 5 - island.phase());
            int x = (int)Math.round(island.x() + Math.cos(a) * island.radiusX() * r * coast);
            int z = (int)Math.round(island.z() + Math.sin(a) * island.radiusZ() * r * coast);
            int[] site = fr.annocraft.building.Siting.snap(layout, post, x, z, 0, 6);
            if (site == null) continue;
            BlockPos at = new BlockPos(site[0], 99, site[1]); load(l, at, 1);
            if (BuildingService.place(p, AnnoCraft.id("trading_post"), at, site[2]).success()) { found = at; turn = site[2]; }
        }
        h.assertTrue(found != null, "No valid coastal trading post placement");
        int deck = fr.annocraft.building.Siting.deck(layout, post, found.getX(), found.getZ(), turn);
        found = new BlockPos(found.getX(), deck + 1, found.getZ());
        int[] back = fr.annocraft.building.Siting.turn(post, turn, post.width() / 2, post.depth() - 1);
        h.assertTrue(layout.height(found.getX() + back[0], found.getZ() + back[1]) < IslandLayout.SEA_LEVEL, "Harbour does not reach the sea");
        boolean carried = false;
        for (int lx = 0; lx < post.width(); lx++) {
            int[] q = fr.annocraft.building.Siting.turn(post, turn, lx, post.depth() - 1);
            if (l.getFluidState(new BlockPos(found.getX() + q[0], IslandLayout.SEA_LEVEL, found.getZ() + q[1])).isEmpty()) carried = true;
        }
        h.assertTrue(carried, "Quay over the sea is not supported");
        var event = new net.minecraftforge.event.level.BlockEvent.BreakEvent(l, found, l.getBlockState(found), p);
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.post(event);
        h.assertTrue(event.isCanceled(), "Managed structure could be broken manually");
        final BlockPos placed = found;
        BuildingInstance b = ColonyData.get(l.getServer()).buildings().values().stream().filter(v -> v.origin().equals(placed.below())).findFirst().orElseThrow();
        h.assertTrue(BuildingService.demolish(p, b.id()).success(), "Coastal demolition failed"); h.succeed();
    }
    @GameTest(template = "empty", templateNamespace = AnnoCraft.ID, timeoutTicks = 400)
    public static void economyCostsRoadsAndMigration(GameTestHelper h) {
        ServerLevel l = region(h); ServerPlayer p = player(l, "economy_tester"); BlockPos at = center(l, 4).offset(-20, 0, -20); load(l, at, 2);
        ColonyData data = ColonyData.get(l.getServer()); var economy = data.economy();
        String island = BuildingService.generator(l).layout().islandAt(at.getX(), at.getZ()).orElseThrow().id();
        data.diplomacy().claim(island);
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
            BuildingInstance house = data.buildings().values().stream().filter(v -> v.origin().equals(at.below())).findFirst().orElseThrow();
            var early = BuildingService.upgrade(p, house.id());
            h.assertTrue(!early.success() && early.message().endsWith("upgrade_not_ready"), "Unfilled residence upgraded: " + early.message());

            BlockPos from = new BlockPos(at.getX() - 1, 0, at.getZ()), to = new BlockPos(at.getX() - 1, 0, at.getZ() + 14);
            h.assertTrue(RoadService.place(p, from, to).success(), "Road placement failed");
            BlockPos ground = new BlockPos(at.getX() - 1, 73, at.getZ() + 3);
            h.assertTrue(data.road("old", ground) && l.getBlockState(ground).is(fr.annocraft.world.AnnoBlocks.EARTH_ROAD.get()), "Road block missing");
            // 15 spine tiles; the second lane runs beside the residence only past its end (8 tiles).
            h.assertTrue(data.roads().size() == 23, "Road length incorrect: " + data.roads().size());
            var event = new net.minecraftforge.event.level.BlockEvent.BreakEvent(l, ground, l.getBlockState(ground), p);
            net.minecraftforge.common.MinecraftForge.EVENT_BUS.post(event);
            h.assertTrue(event.isCanceled(), "Road could be broken manually");
            var blocked = BuildingService.place(p, AnnoCraft.id("residence"), new BlockPos(at.getX() - 4, 74, at.getZ() + 12), 0);
            h.assertTrue(!blocked.success() && blocked.message().endsWith("overlap"), "Residence accepted over a road: " + blocked.message());
            h.assertFalse(RoadService.place(p, from, new BlockPos(at.getX() - 1, 0, at.getZ() + RoadService.MAX_LENGTH)).success(), "Overlong road accepted");

            CompoundTag saved = data.save(new CompoundTag());
            CompoundTag resaved = ColonyData.load(saved).save(new CompoundTag());
            h.assertTrue(resaved.equals(saved), "Economy or roads changed during save round trip: " + diff("", saved, resaved));
            CompoundTag legacy = saved.copy(); legacy.putInt("version", 1); legacy.remove("economy"); legacy.remove("roads");
            ColonyData migrated = ColonyData.load(legacy);
            h.assertTrue(migrated.economy().sandbox() && migrated.buildings().keySet().equals(data.buildings().keySet()), "Version 1 migration lost buildings or budget mode");

            var bytes = new java.io.ByteArrayOutputStream();
            try { NbtIo.write(data.snapshot(l.getServer()), new java.io.DataOutputStream(bytes)); } catch (java.io.IOException e) { throw new IllegalStateException(e); }
            h.assertTrue(bytes.size() < 512 * 1024, "Snapshot too large for one packet: " + bytes.size());
            h.assertTrue(RoadService.remove(p, from, to).success(), "Road removal failed");
            // The meadow under the road comes back, possibly worn by the buildings next to it.
            h.assertTrue(data.roads().isEmpty() && fr.annocraft.world.AnnoBlocks.wear(l.getBlockState(ground)) >= 0, "Road removal did not restore terrain: " + l.getBlockState(ground));
            for (BuildingInstance b : List.copyOf(data.buildings().values())) if (b.origin().equals(at.below()) || b.origin().equals(hut.below())) BuildingService.demolish(p, b.id());
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
            added.add(data.buildings().values().stream().filter(v -> v.origin().equals(at.below())).findFirst().orElseThrow().id());
        }
        h.assertTrue(data.buildings().size() == before + 100, "Stress building count incorrect");
        CameraSessions.update(a, true, origin.getX(), origin.getZ()); CameraSessions.update(b, true, origin.getX(), origin.getZ());
        int one = CameraSessions.ticketCount(a);
        h.assertTrue(one > 0 && one <= CameraSessions.MAX_CHUNKS && CameraSessions.ticketCount() == one + CameraSessions.ticketCount(b), "Per-player ticket accounting failed");
        h.runAfterDelay(6, () -> {
            CameraSessions.update(a, true, origin.getX() + 32, origin.getZ());
            CameraSessions.update(b, true, origin.getX() - 32, origin.getZ());
            h.assertTrue(CameraSessions.ticketCount(a) == one && CameraSessions.ticketCount() == CameraSessions.ticketCount(a) + CameraSessions.ticketCount(b), "Moving cameras accumulated tickets");
            CameraSessions.close(a);
            h.assertTrue(CameraSessions.ticketCount() == CameraSessions.ticketCount(b) && CameraSessions.ticketCount(b) > 0, "One player removed the other's tickets");
            // Exercise the expiry path as well as explicit close/disconnect cleanup.
            h.runAfterDelay(105, () -> {
                CameraSessions.tick(b); h.assertTrue(CameraSessions.ticketCount() == 0, "Expired camera tickets leaked");
                for (UUID id : added) BuildingService.demolish(a, id);
                l.getChunkSource().removeRegionTicket(TicketType.PORTAL, new net.minecraft.world.level.ChunkPos(origin), 7, origin);
                h.assertTrue(data.buildings().size() == before, "Stress cleanup failed: " + data.buildings().size() + " vs " + before); h.succeed();
            });
        });
    }
}
