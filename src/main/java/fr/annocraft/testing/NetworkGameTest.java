package fr.annocraft.testing;

import fr.annocraft.AnnoCraft;
import fr.annocraft.server.*;
import net.minecraft.gametest.framework.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import java.nio.file.*;
import java.net.InetAddress;
import java.util.*;

/** Loopback-only, opt-in network test. No authentication changes in normal server configurations. */
@PrefixGameTestTemplate(false)
public final class NetworkGameTest {
    private static final Set<UUID> reports = new HashSet<>();
    private static boolean ready;
    public static boolean ready() { return ready; }
    public static void report(ServerPlayer player) { reports.add(player.getUUID()); }
    @GameTest(template = "empty", templateNamespace = AnnoCraft.ID, timeoutTicks = 6000)
    public static void twoRealDevelopmentClients(GameTestHelper h) throws Exception {
        var server = h.getLevel().getServer();
        // Vanilla's GameTest PlayerList allows only one player; change only this isolated fixture.
        net.minecraftforge.fml.util.ObfuscationReflectionHelper.setPrivateValue(net.minecraft.server.players.PlayerList.class, server.getPlayerList(), 4, "f_11193_");
        ready = false;
        server.getPlayerList().setViewDistance(8);
        server.getPlayerList().setSimulationDistance(5);
        var region = Objects.requireNonNull(server.getLevel(AnnoCraft.ARCHIPELAGO));
        var fixturePlayer = net.minecraftforge.common.util.FakePlayerFactory.getMinecraft(region);
        boolean reload = Boolean.getBoolean("annocraft1800.networkReload");
        if (reload) {
            var expected = net.minecraft.nbt.NbtIo.readCompressed(Path.of("network-expected-colony.dat").toFile()).getCompound("data");
            h.assertTrue(ColonyData.get(server).save(new net.minecraft.nbt.CompoundTag()).equals(expected), "Server restart changed colony IDs, geometry, rotations, upgrades or backups");
        }
        for (var existing : reload ? List.<fr.annocraft.building.BuildingInstance>of() : List.copyOf(ColonyData.get(server).buildings().values())) {
            for (int x = existing.origin().getX() >> 4; x <= (existing.origin().getX() + existing.width() - 1) >> 4; x++)
                for (int z = existing.origin().getZ() >> 4; z <= (existing.origin().getZ() + existing.depth() - 1) >> 4; z++) region.getChunk(x, z);
            BuildingService.demolish(fixturePlayer, existing.id());
        }
        reports.clear(); server.setUsesAuthentication(false);
        server.getConnection().startTcpServerListener(InetAddress.getByName("127.0.0.1"), 25575);
        Files.writeString(Path.of("network-ready.txt"), "127.0.0.1:25575\n");
        h.onEachTick(() -> {
            // GameTestServer runs ticks without pacing. Network clients need normal login/heartbeat time.
            java.util.concurrent.locks.LockSupport.parkNanos(50_000_000L);
            if (!ready && server.getPlayerList().getPlayers().stream().filter(p -> p.level().dimension().equals(AnnoCraft.ARCHIPELAGO)).count() >= 2) {
                ready = true; fr.annocraft.network.AnnoNetwork.syncAll(server);
            }
            if (reports.size() < 2) return;
            h.assertTrue(ready, "Two clients never overlapped in the shared region");
            var data = ColonyData.get(server);
            h.assertTrue(data.buildings().size() == 1, "Concurrent placement created duplicate buildings");
            h.assertTrue(data.buildings().values().iterator().next().definition().equals(AnnoCraft.id("residence_2")), "Shared upgrade did not converge");
            h.assertTrue(CameraSessions.ticketCount() == 0, "Client exits left camera tickets active");
            try { Files.writeString(Path.of("network-result.txt"), "PASS: two real clients, loopback TCP, shared placement/upgrade, synchronized blocks, repeated camera toggles, zero leaked tickets." + (reload ? " Server restart and client reconnection preserved the complete colony save." : "") + "\n"); }
            catch (java.io.IOException error) { throw new IllegalStateException(error); }
            h.succeed();
        });
    }
}
