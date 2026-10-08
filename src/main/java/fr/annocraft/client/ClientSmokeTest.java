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
    private static boolean buildShot;
    private static double exitX, exitZ;
    private static void next() { stage++; elapsed = 0; System.out.println("ANNOCRAFT_CLIENT_SMOKE_STAGE " + stage); }
    private static void check(boolean condition, String message) { if (!condition) throw new IllegalStateException(message); }
    /** The smoke world's rules: competitive companies when asked with -Pcompetitive. */
    private static GameRules rules() {
        GameRules rules = new GameRules();
        if (Boolean.getBoolean("annocraft1800.competitive")) rules.getRule(AnnoCraft.COMPETITIVE).set(true, null);
        return rules;
    }
    private static int[] harbour;
    /** A coastal site {x, z, rotation} for a harbour building on the island at (x, z), away from {@code avoid}. */
    private static int[] coast(fr.annocraft.building.BuildingDefinition def, double x, double z, int[] avoid) {
        var island = ClientState.layout.islandAt((int) x, (int) z).orElseThrow();
        double start = Math.atan2(z - island.z(), x - island.x());
        for (int step = 0; step < 360; step += 2) for (int sign : new int[]{1, -1}) for (double r = .82; r <= .96; r += .015) {
            double a = start + Math.toRadians(step * sign), coast = 1 + .07 * Math.sin(a * 3 + island.phase()) + .04 * Math.cos(a * 5 - island.phase());
            int px = (int) Math.round(island.x() + Math.cos(a) * island.radiusX() * r * coast) - def.width() / 2;
            int pz = (int) Math.round(island.z() + Math.sin(a) * island.radiusZ() * r * coast) - def.depth() / 2;
            if (avoid != null && Math.abs(px - avoid[0]) < 16 && Math.abs(pz - avoid[1]) < 16) continue;
            int rotation = fr.annocraft.building.Siting.coastRotation(ClientState.layout, def, px, pz, 0);
            if (rotation >= 0) return new int[]{px, pz, rotation};
        }
        return null;
    }
    private static void clickAt(Vec3 target) {
        Minecraft mc = Minecraft.getInstance(); Vec3 delta = target.subtract(mc.gameRenderer.getMainCamera().getPosition());
        double yaw = Math.toRadians(RtsController.yaw), pitch = Math.toRadians(CameraMath.pitch(RtsController.zoom, RtsController.tilt));
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
                                Difficulty.PEACEFUL, true, rules(), WorldDataConfiguration.DEFAULT),
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
                        body = mc.player.position(); mc.setScreen(null); RtsScreen.openCategory = null; RtsController.enter();
                        check(RtsController.active && mc.screen instanceof RtsScreen, "RTS did not activate"); next();
                    }
                }
                case 3 -> {
                    if (elapsed >= 30 && RtsController.bodyPosition != null) {
                        body = RtsController.bodyPosition;
                        var server = mc.getSingleplayerServer(); var serverBody = server == null ? null : server.getPlayerList().getPlayer(mc.player.getUUID());
                        System.out.println("ANNOCRAFT_CLIENT_SMOKE_BODY client=" + mc.player.isInvisible() + " server=" + (serverBody == null ? "?" : serverBody.isInvisible() + " at " + serverBody.position()));
                        check(serverBody == null || serverBody.isInvisible(), "Body was not hidden while the camera carries it");
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
                        clickAt(new Vec3(building.origin().getX() + building.width() / 2.0, building.origin().getY() + .5, building.origin().getZ() + building.depth() / 2.0));
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
                    if (!buildShot && roadSent && elapsed > 20 && road != null && mc.level.getBlockState(road).is(fr.annocraft.world.AnnoBlocks.EARTH_ROAD.get())) {
                        check(mc.level.getBlockState(b.origin().offset(3, 0, 3)).is(net.minecraft.world.level.block.Blocks.STONE_BRICKS), "Network did not update construction blocks");
                        check(ClientState.economy.contains("coins") && ClientState.SITES.containsKey(b.id()), "Economy state was not received");
                        Screenshot.grab(mc.gameDirectory, "annocraft-rts.png", mc.getMainRenderTarget(), c -> {});
                        // Then the construction menu and the ghost of a building being placed.
                        buildShot = true; elapsed = 0; ClientState.selected = null;
                        RtsScreen.openCategory = "farmers"; RtsScreen.openGroup = "timber"; mc.screen.init(mc, mc.getWindow().getGuiScaledWidth(), mc.getWindow().getGuiScaledHeight());
                        RtsController.placement = ClientState.DEFINITIONS.get(AnnoCraft.id("residence"));
                        RtsController.forceHover(b.origin().offset(-12, 0, 0));
                    } else if (buildShot && elapsed == 15) Screenshot.grab(mc.gameDirectory, "annocraft-build.png", mc.getMainRenderTarget(), c -> {});
                    else if (buildShot && elapsed == 16) {
                        // Then a clay deposit of the first island, with the clay pit being placed: frames, grid and forest.
                        var island = ClientState.layout.islands().get(0);
                        var site = ClientState.layout.deposits(island).stream().filter(s -> s.type().equals("clay")).findFirst().orElseThrow();
                        RtsController.jump(site.x() + .5, site.z() + .5); RtsController.zoomTarget = 40;
                        RtsScreen.openCategory = "workers"; RtsScreen.openGroup = null; mc.screen.init(mc, mc.getWindow().getGuiScaledWidth(), mc.getWindow().getGuiScaledHeight());
                        RtsController.placement = ClientState.DEFINITIONS.get(AnnoCraft.id("clay_pit"));
                        RtsController.forceHover(new BlockPos(site.x() - 3, 74, site.z() - 3));
                    } else if (buildShot && elapsed == 110) Screenshot.grab(mc.gameDirectory, "annocraft-deposit.png", mc.getMainRenderTarget(), c -> {});
                    else if (buildShot && elapsed == 111) {
                        // Far out: the distant view (LOD) draws the whole island beyond the loaded chunks.
                        RtsController.forceHover(null); RtsController.placement = null; RtsController.zoomTarget = 650;
                    } else if (buildShot && elapsed == 230) Screenshot.grab(mc.gameDirectory, "annocraft-lod.png", mc.getMainRenderTarget(), c -> {});
                    else if (buildShot && elapsed == 231) {
                        // Harbour: a trading post and a shipyard half on the beach, half in the sea, seen with a lower tilt.
                        harbour = coast(ClientState.DEFINITIONS.get(AnnoCraft.id("trading_post")), body.x, body.z, null);
                        check(harbour != null, "No harbour site found on the island");
                        RtsController.jump(harbour[0] + 5, harbour[1] + 5); RtsController.zoomTarget = 34; RtsController.tilt = -14;
                    } else if (buildShot && elapsed == 300) {
                        var post = ClientState.DEFINITIONS.get(AnnoCraft.id("trading_post"));
                        AnnoNetwork.CHANNEL.sendToServer(new AnnoNetwork.BuildCommand(0, post.id(), new BlockPos(harbour[0], fr.annocraft.building.Siting.deck(ClientState.layout, post, harbour[0], harbour[1], harbour[2]) + 1, harbour[1]), harbour[2], new UUID(0, 0)));
                    } else if (buildShot && elapsed == 320) {
                        // One command per second: the server rate-limits them.
                        var yard = ClientState.DEFINITIONS.get(AnnoCraft.id("shipyard"));
                        int[] y = coast(yard, harbour[0], harbour[1], harbour);
                        if (y != null) AnnoNetwork.CHANNEL.sendToServer(new AnnoNetwork.BuildCommand(0, yard.id(), new BlockPos(y[0], fr.annocraft.building.Siting.deck(ClientState.layout, yard, y[0], y[1], y[2]) + 1, y[1]), y[2], new UUID(0, 0)));
                    } else if (buildShot && elapsed == 340) {
                        System.out.println("ANNOCRAFT_CLIENT_SMOKE_YARD " + ClientState.message);
                        String island = ClientState.layout.islandAt((int) body.x, (int) body.z).orElseThrow().id();
                        AnnoNetwork.action("ship_build", "island", island, "type", "schooner");
                    } else if (buildShot && elapsed == 370) {
                        AnnoNetwork.action("ship_build", "island", ClientState.layout.islandAt((int) body.x, (int) body.z).orElseThrow().id(), "type", "frigate");
                    } else if (buildShot && elapsed == 395) {
                        // The frigate selected like a unit and sent out to sea: ring, course and object menu.
                        String island = ClientState.layout.islandAt((int) body.x, (int) body.z).orElseThrow().id();
                        var frigate = ColonyScreen.ships().stream().filter(s -> s.getString("type").equals("frigate")).findFirst().orElse(null);
                        if (frigate != null) {
                            double[] d = ShipRenderer.dock(island, 0);
                            RtsController.selectedShips.clear(); RtsController.selectedShips.add(frigate.getUUID("id"));
                            AnnoNetwork.action("ship_goto", "ships", frigate.getUUID("id").toString(), "x", d[0] + d[4] * 70 + d[2] * 25, "z", d[1] + d[5] * 70 + d[3] * 25);
                            mc.screen.init(mc, mc.getWindow().getGuiScaledWidth(), mc.getWindow().getGuiScaledHeight());
                        }
                    } else if (buildShot && elapsed == 420) {
                        System.out.println("ANNOCRAFT_CLIENT_SMOKE_HARBOUR " + ClientState.BUILDINGS.values().stream().map(v -> v.definition().getPath()).toList() + " ships=" + ColonyScreen.ships().size() + " last=" + ClientState.message);
                        Screenshot.grab(mc.gameDirectory, "annocraft-harbour.png", mc.getMainRenderTarget(), c -> {});
                    } else if (buildShot && elapsed == 421) {
                        // Low over the island: real chunks meet the distant view without fog.
                        RtsController.jump(body.x, body.z); RtsController.zoomTarget = 90; RtsController.tilt = -32;
                    } else if (buildShot && elapsed == 449) Screenshot.grab(mc.gameDirectory, "annocraft-horizon.png", mc.getMainRenderTarget(), c -> {});
                    else if (buildShot && elapsed == 451) {
                        // A residence being placed in a forest: the ghost shows through the trees.
                        var island = ClientState.layout.islandAt((int) body.x, (int) body.z).orElseThrow();
                        int[] wood = null;
                        for (int r = 30; r < 260 && wood == null; r += 4) for (int a = 0; a < 360 && wood == null; a += 7) {
                            int x = island.x() + (int) (Math.cos(Math.toRadians(a)) * r), z = island.z() + (int) (Math.sin(Math.toRadians(a)) * r);
                            if (ClientState.layout.wild(x, z) && ClientState.layout.forest(x, z) > .8) wood = new int[]{x, z};
                        }
                        check(wood != null, "No forest found on the island");
                        RtsController.tilt = 0; RtsController.jump(wood[0] + 3, wood[1] + 3); RtsController.zoomTarget = 30;
                        RtsScreen.openCategory = "farmers"; mc.screen.init(mc, mc.getWindow().getGuiScaledWidth(), mc.getWindow().getGuiScaledHeight());
                        RtsController.placement = ClientState.DEFINITIONS.get(AnnoCraft.id("residence"));
                        RtsController.forceHover(new BlockPos(wood[0], 74, wood[1]));
                    } else if (buildShot && elapsed == 530) Screenshot.grab(mc.gameDirectory, "annocraft-forest.png", mc.getMainRenderTarget(), c -> {});
                    else if (buildShot && elapsed == 531) {
                        // A pirate warship out at sea: clicked, it shows who sails it and how strong it is.
                        RtsController.forceHover(null); RtsController.placement = null;
                        var pirate = ShipRenderer.all().stream().filter(s -> s.contains("owner") && s.contains("world")).findFirst().orElse(null);
                        System.out.println("ANNOCRAFT_CLIENT_SMOKE_PIRATE " + (pirate == null ? "none" : pirate.getString("owner") + " at " + pirate.getDouble("x") + ", " + pirate.getDouble("z")));
                        if (pirate != null) {
                            RtsController.jump(pirate.getDouble("x"), pirate.getDouble("z")); RtsController.zoomTarget = 45;
                            RtsController.selectedShips.clear(); RtsController.inspected = pirate.getUUID("id");
                        }
                    } else if (buildShot && elapsed == 600) Screenshot.grab(mc.gameDirectory, "annocraft-enemy.png", mc.getMainRenderTarget(), c -> {});
                    else if (buildShot && elapsed > 605) {
                        RtsController.forceHover(null); RtsController.placement = null; RtsScreen.openCategory = null;
                        exitX = RtsController.x; exitZ = RtsController.z;
                        RtsController.exit(); mc.setScreen(null); next();
                    }
                }
                case 6 -> {
                    if (elapsed > 10) {
                        // Leaving the management view sets the player down on the island under the camera, not in the sea.
                        check(Math.hypot(mc.player.getX() - exitX, mc.player.getZ() - exitZ) < 64 && !mc.player.isInWater() && mc.player.getY() > 64,
                                "Player was not set down on the island under the camera: " + mc.player.position() + " camera " + exitX + ", " + exitZ);
                        exitX = RtsController.x; exitZ = RtsController.z;
                        if (toggles++ < 10) { RtsController.enter(); next(); }
                        else { next(); next(); }
                    }
                }
                case 7 -> {
                    if (elapsed > 10) { check(RtsController.active, "Repeated RTS toggle failed"); exitX = RtsController.x; exitZ = RtsController.z; RtsController.exit(); mc.setScreen(null); stage = 6; elapsed = 0; }
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
                    if (elapsed == 1) { AnnoNetwork.action("campaign_start"); RtsController.enter(); }
                    // The windows open over the management view, whose HUD stays visible around them.
                    if (index < ColonyScreen.TABS.length && elapsed % 30 == 5) {
                        ColonyScreen.tab = ColonyScreen.TABS[index];
                        if (mc.screen instanceof RtsScreen rts) rts.openChild(new ColonyScreen(rts));
                        else if (mc.screen instanceof ColonyScreen open) mc.setScreen(new ColonyScreen(open.parent()));
                    }
                    if (index < ColonyScreen.TABS.length && elapsed % 30 == 28) Screenshot.grab(mc.gameDirectory, "colony-" + ColonyScreen.tab + ".png", mc.getMainRenderTarget(), c -> {});
                    // The company's identity: portrait, colour and flag editor.
                    if (index == ColonyScreen.TABS.length && elapsed % 30 == 5) mc.setScreen(new BrandingScreen(mc.screen));
                    if (index == ColonyScreen.TABS.length && elapsed % 30 == 28) Screenshot.grab(mc.gameDirectory, "branding.png", mc.getMainRenderTarget(), c -> {});
                    if (index == ColonyScreen.TABS.length + 1 && elapsed % 30 == 5) mc.setScreen(new StrategicMapScreen(null));
                    if (index == ColonyScreen.TABS.length + 1 && elapsed % 30 == 28) Screenshot.grab(mc.gameDirectory, "strategic-map.png", mc.getMainRenderTarget(), c -> {});
                    if (index > ColonyScreen.TABS.length + 1) mc.stop();
                }
            }
        } catch (Exception error) {
            try { Files.writeString(mc.gameDirectory.toPath().resolve("smoke-result.txt"), "FAIL stage=" + stage + ": " + error + "\n"); } catch (Exception ignored) { }
            error.printStackTrace(); mc.stop();
        }
    }
}
