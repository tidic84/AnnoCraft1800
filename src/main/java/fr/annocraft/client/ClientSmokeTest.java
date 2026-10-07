package fr.annocraft.client;

import fr.annocraft.AnnoCraft;
import fr.annocraft.building.BuildingInstance;
import fr.annocraft.network.AnnoNetwork;
import net.minecraft.client.*;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.*;
import net.minecraft.world.level.levelgen.*;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import java.nio.file.*;
import java.util.UUID;

/** Opt-in real renderer/integrated-server smoke test. Inert in normal client launches. */
@Mod.EventBusSubscriber(modid = AnnoCraft.ID, value = Dist.CLIENT)
public final class ClientSmokeTest {
    private static int stage, elapsed, total, toggles;
    private static Vec3 body;
    private static UUID residence;
    private static boolean roadSent;
    private static void next() { stage++; elapsed = 0; System.out.println("ANNOCRAFT_CLIENT_SMOKE_STAGE " + stage); }
    private static void check(boolean condition, String message) { if (!condition) throw new IllegalStateException(message); }
    private static void clickAt(Vec3 target) {
        Minecraft mc = Minecraft.getInstance(); Vec3 delta = target.subtract(mc.gameRenderer.getMainCamera().getPosition());
        double yaw = Math.toRadians(RtsController.yaw), pitch = Math.toRadians(55);
        Vec3 forward = new Vec3(-Math.sin(yaw) * Math.cos(pitch), -Math.sin(pitch), Math.cos(yaw) * Math.cos(pitch));
        Vec3 right = forward.cross(new Vec3(0, 1, 0)).normalize(), up = right.cross(forward).normalize();
        double w = mc.getWindow().getGuiScaledWidth(), h = mc.getWindow().getGuiScaledHeight();
        double scale = Math.tan(Math.toRadians(22.5)), distance = delta.dot(forward);
        double x = w * .5 * (1 + delta.dot(right) / (distance * scale * w / h));
        double y = h * .5 * (1 - delta.dot(up) / (distance * scale));
        check(mc.screen instanceof RtsScreen, "Management screen closed unexpectedly");
        check(mc.screen.mouseClicked(x, y, 0), "World click was not handled");
    }
    @SubscribeEvent public static void tick(TickEvent.ClientTickEvent event) {
        if (!Boolean.getBoolean("annocraft1800.clientSmoke") || event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance(); elapsed++; total++;
        mc.options.pauseOnLostFocus = false;
        try {
            if (total > 6000) throw new IllegalStateException("Client smoke timeout at stage " + stage);
            switch (stage) {
                case 0 -> {
                    if (elapsed == 40 && mc.player == null && !(mc.screen instanceof TitleScreen)) {
                        System.out.println("ANNOCRAFT_CLIENT_SMOKE_INITIAL_SCREEN " + (mc.screen == null ? "null" : mc.screen.getClass().getSimpleName()));
                        mc.setScreen(new TitleScreen());
                    }
                    if (mc.screen instanceof TitleScreen && mc.getOverlay() == null && elapsed > 60) {
                        if (System.getProperty("annocraft1800.networkRole") != null) {
                            net.minecraft.client.gui.screens.ConnectScreen.startConnecting(mc.screen, mc,
                                    new net.minecraft.client.multiplayer.resolver.ServerAddress("127.0.0.1", 25575),
                                    new net.minecraft.client.multiplayer.ServerData("AnnoCraft network smoke", "127.0.0.1:25575", false), false);
                            next(); break;
                        }
                        String name = "annocraft-smoke-" + System.currentTimeMillis();
                        mc.createWorldOpenFlows().createFreshLevel(name, new LevelSettings("AnnoCraft smoke", GameType.CREATIVE, false,
                                Difficulty.PEACEFUL, true, new GameRules(), WorldDataConfiguration.DEFAULT),
                                new WorldOptions(1800, false, false), WorldPresets::createNormalWorldDimensions);
                        next();
                    }
                }
                case 1 -> {
                    if (mc.screen instanceof net.minecraft.client.gui.screens.DisconnectedScreen) throw new IllegalStateException("Development client disconnected during login");
                    if (mc.player != null && mc.getConnection() != null && elapsed > 20) { mc.player.connection.sendCommand("anno join"); next(); }
                }
                case 2 -> {
                    if (RtsController.inRegion() && !ClientState.DEFINITIONS.isEmpty() && elapsed > 20 &&
                            (System.getProperty("annocraft1800.networkRole") == null || ClientState.networkTestReady)) {
                        body = mc.player.position(); mc.setScreen(null); RtsController.enter();
                        check(RtsController.active && mc.screen instanceof RtsScreen, "RTS did not activate"); next();
                    }
                }
                case 3 -> {
                    if (elapsed >= 30 && RtsController.bodyPosition != null) {
                        body = RtsController.bodyPosition;
                        check(mc.player.position().distanceToSqr(body) < .001, "Camera moved the authoritative body from " + body + " to " + mc.player.position());
                        var def = ClientState.DEFINITIONS.get(AnnoCraft.id("residence"));
                        if (ClientState.BUILDINGS.isEmpty()) {
                            RtsController.placement = def; RtsController.rotation = 1;
                            BlockPos origin = BlockPos.containing(body.x + 10, 74, body.z);
                            clickAt(new Vec3(origin.getX() + .5, 73.5, origin.getZ() + .5));
                            check(origin.equals(RtsController.hover), "Cursor ray did not select the expected construction tile");
                        }
                        next();
                    }
                }
                case 4 -> {
                    if (ClientState.BUILDINGS.size() == 1) {
                        // Centre the camera on the building so panels never cover it.
                        var target = ClientState.BUILDINGS.values().iterator().next();
                        RtsController.x = target.origin().getX() + target.width() / 2.0; RtsController.z = target.origin().getZ() + target.depth() / 2.0;
                    }
                    if (ClientState.BUILDINGS.size() == 1 && elapsed > 20) {
                        RtsController.placement = null;
                        residence = ClientState.BUILDINGS.keySet().iterator().next();
                        var building = ClientState.BUILDINGS.get(residence);
                        clickAt(new Vec3(building.origin().getX() + building.width() / 2.0, building.origin().getY() + building.height() - .5, building.origin().getZ() + building.depth() / 2.0));
                        check(residence.equals(ClientState.selected), "Cursor did not select the shared building");
                        if (!building.definition().equals(AnnoCraft.id("residence_2"))) AnnoNetwork.CHANNEL.sendToServer(new AnnoNetwork.BuildCommand(2, AnnoCraft.id("residence"), BlockPos.ZERO, 0, residence));
                        next();
                    }
                }
                case 5 -> {
                    BuildingInstance b = ClientState.BUILDINGS.get(residence);
                    if (b != null && b.definition().equals(AnnoCraft.id("residence_2")) && elapsed > 20 && !roadSent) {
                        // Road along the residence's west side, through the real network command.
                        AnnoNetwork.CHANNEL.sendToServer(new AnnoNetwork.RoadCommand(false, b.origin().offset(-1, 0, 0), b.origin().offset(-1, 0, 6))); roadSent = true; elapsed = 0;
                        ClientState.selected = b.id();
                    }
                    BlockPos road = b == null ? null : new BlockPos(b.origin().getX() - 1, ClientState.layout.height(b.origin().getX() - 1, b.origin().getZ() + 3), b.origin().getZ() + 3);
                    if (roadSent && elapsed > 20 && road != null && mc.level.getBlockState(road).is(net.minecraft.world.level.block.Blocks.DIRT_PATH)) {
                        check(mc.level.getBlockState(b.origin()).is(net.minecraft.world.level.block.Blocks.STONE_BRICKS), "Network did not update construction blocks");
                        check(ClientState.economy.contains("coins") && ClientState.SITES.containsKey(b.id()), "Economy state was not received");
                        Screenshot.grab(mc.gameDirectory, "annocraft-rts.png", mc.getMainRenderTarget(), c -> {});
                        RtsController.exit(); mc.setScreen(null); next();
                    }
                }
                case 6 -> {
                    if (elapsed > 10) {
                        check(mc.player.position().distanceToSqr(body) < .001, "Visit position was not restored");
                        if (toggles++ < 10) { RtsController.enter(); next(); }
                        else { next(); next(); }
                    }
                }
                case 7 -> {
                    if (elapsed > 10) { check(RtsController.active, "Repeated RTS toggle failed"); RtsController.exit(); mc.setScreen(null); stage = 6; elapsed = 0; }
                }
                case 8 -> {
                    if (elapsed > 20) {
                        check(!RtsController.active && mc.getCameraEntity() == mc.player, "Player camera was not restored");
                        Screenshot.grab(mc.gameDirectory, "annocraft-visit.png", mc.getMainRenderTarget(), c -> {});
                        Files.writeString(mc.gameDirectory.toPath().resolve("smoke-result.txt"), "PASS: client boot, world creation, archipelago join, RTS, network construction, upgrade and road, economy sync, 10 toggles, body restoration.\n");
                        System.out.println("ANNOCRAFT_CLIENT_SMOKE_PASS"); next();
                        if (System.getProperty("annocraft1800.networkRole") != null) mc.player.connection.sendCommand("anno_test_report");
                    }
                }
                case 9 -> {
                    // Colony screen: one screenshot per tab (not in the two-client network bench).
                    if (System.getProperty("annocraft1800.networkRole") != null) { if (elapsed > 40) mc.stop(); break; }
                    int index = elapsed / 30;
                    if (elapsed == 1) AnnoNetwork.action("campaign_start");
                    if (index < ColonyScreen.TABS.length && elapsed % 30 == 5) { ColonyScreen.tab = ColonyScreen.TABS[index]; mc.setScreen(new ColonyScreen(null)); }
                    if (index < ColonyScreen.TABS.length && elapsed % 30 == 28) Screenshot.grab(mc.gameDirectory, "colony-" + ColonyScreen.tab + ".png", mc.getMainRenderTarget(), c -> {});
                    if (index > ColonyScreen.TABS.length) mc.stop();
                }
            }
        } catch (Exception error) {
            try { Files.writeString(mc.gameDirectory.toPath().resolve("smoke-result.txt"), "FAIL stage=" + stage + ": " + error + "\n"); } catch (Exception ignored) { }
            error.printStackTrace(); mc.stop();
        }
    }
}
