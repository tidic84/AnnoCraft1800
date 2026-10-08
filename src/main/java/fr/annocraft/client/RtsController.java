package fr.annocraft.client;

import fr.annocraft.AnnoCraft;
import fr.annocraft.building.*;
import fr.annocraft.network.AnnoNetwork;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.vertex.*;
import net.minecraft.client.*;
import net.minecraft.client.renderer.*;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.*;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.*;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.*;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;
import java.util.*;

@Mod.EventBusSubscriber(modid = AnnoCraft.ID, value = Dist.CLIENT)
public final class RtsController {
    public static boolean active;
    public static BuildingDefinition placement;
    public static int rotation;
    /** 0: none, 1: build roads, 2: remove roads. */
    public static int roadMode;
    public static BlockPos roadStart;
    public static double x, z;
    public static float yaw = 135, zoom = CameraMath.DEFAULT_ZOOM, zoomTarget = CameraMath.DEFAULT_ZOOM;
    /** The player's tilt of the camera from its automatic pitch (middle mouse drag, Page Up / Page Down). */
    public static float tilt;
    private static float prevTilt, renderTilt;
    // Previous tick values and the last rendered frame, for smooth camera motion between ticks.
    private static double prevX, prevZ;
    private static float prevYaw = 135, prevZoom = CameraMath.DEFAULT_ZOOM;
    private static double renderX, renderZ; private static float renderYaw = 135, renderZoom = CameraMath.DEFAULT_ZOOM;
    /** Moves the camera instantly, e.g. from the minimap or the strategic map. */
    public static void jump(double nx, double nz) { x = prevX = renderX = nx; z = prevZ = renderZ = nz; }
    private static Entity anchor;
    private static Entity previousCamera;
    private static CameraType previousPerspective;
    private static boolean previousHideGui;
    private static float bodyYaw, bodyPitch;
    public static Vec3 bodyPosition;
    public static void acceptAnchor(double x, double y, double z, float yaw, float pitch) {
        if (!active) return;
        bodyPosition = new Vec3(x, y, z); bodyYaw = yaw; bodyPitch = pitch;
    }
    private static int ticks;
    public static BlockPos hover;
    /** Test hook: a fixed hovered tile instead of the mouse ray. */
    static BlockPos forcedHover;
    static void forceHover(BlockPos pos) { forcedHover = pos; }
    public static boolean hoverValid;
    public static final KeyMapping TOGGLE = new KeyMapping("key.annocraft1800.rts", GLFW.GLFW_KEY_F6, "key.categories.annocraft1800");
    public static final KeyMapping COLONY = new KeyMapping("key.annocraft1800.colony", GLFW.GLFW_KEY_J, "key.categories.annocraft1800");
    public static final KeyMapping MAP = new KeyMapping("key.annocraft1800.map", GLFW.GLFW_KEY_M, "key.categories.annocraft1800");
    @Mod.EventBusSubscriber(modid = AnnoCraft.ID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
    public static final class Keys {
        @SubscribeEvent public static void register(RegisterKeyMappingsEvent event) { event.register(TOGGLE); event.register(COLONY); event.register(MAP); }
    }
    public static boolean inRegion() { return Minecraft.getInstance().level != null && AnnoCraft.isColony(Minecraft.getInstance().level.dimension()); }
    public static void enter() {
        Minecraft mc = Minecraft.getInstance(); if (mc.player == null || !inRegion() || ClientState.DEFINITIONS.isEmpty()) return;
        active = true; if (!jumped) jump(mc.player.getX(), mc.player.getZ()); jumped = false;
        yaw = prevYaw = renderYaw = 135; zoom = zoomTarget = prevZoom = renderZoom = CameraMath.DEFAULT_ZOOM; tilt = prevTilt = renderTilt = 0;
        bodyPosition = null;
        bodyYaw = mc.player.getYRot(); bodyPitch = mc.player.getXRot(); previousCamera = mc.getCameraEntity(); previousPerspective = mc.options.getCameraType();
        previousHideGui = mc.options.hideGui; mc.options.hideGui = true;
        anchor = EntityType.ARMOR_STAND.create(mc.level); if (anchor == null) { active = false; return; }
        anchor.setInvisible(true); anchor.setNoGravity(true); updateAnchor();
        mc.options.setCameraType(CameraType.FIRST_PERSON); mc.setCameraEntity(anchor);
        AnnoNetwork.CHANNEL.sendToServer(new AnnoNetwork.CameraCommand(true, x, z));
        mc.setScreen(new RtsScreen());
    }
    public static void exit() {
        Minecraft mc = Minecraft.getInstance();
        if (active && mc.getConnection() != null) AnnoNetwork.CHANNEL.sendToServer(new AnnoNetwork.CameraCommand(false, x, z));
        if (mc.player != null) { mc.player.setYRot(bodyYaw); mc.player.setXRot(bodyPitch); }
        mc.setCameraEntity(previousCamera != null && previousCamera.level() == mc.level ? previousCamera : mc.player);
        if (previousPerspective != null) mc.options.setCameraType(previousPerspective);
        mc.options.hideGui = previousHideGui;
        active = false; anchor = null; previousCamera = null; previousPerspective = null; placement = null; hover = null; roadMode = 0; roadStart = null;
        selectedShips.clear(); hoverShip = null;
        KeyMapping.releaseAll();
    }
    /** Set by the strategic map before entering, so the camera opens where the player clicked. */
    static boolean jumped;
    private static double[] forward() { return CameraMath.forward(renderYaw, renderZoom, renderTilt); }
    /** Eye position for a camera looking at (cx, 74, cz). */
    private static Vec3 eye(double cx, double cz, float yaw, float zoom, float tilt) {
        double[] e = CameraMath.eye(cx, cz, yaw, zoom, tilt); return new Vec3(e[0], e[1], e[2]);
    }
    private static void updateAnchor() {
        if (anchor == null) return;
        Vec3 e = eye(x, z, yaw, zoom, tilt);
        anchor.setPos(e.x, e.y - anchor.getEyeHeight(), e.z);
        anchor.xo = anchor.getX(); anchor.yo = anchor.getY(); anchor.zo = anchor.getZ();
        float pitch = CameraMath.pitch(zoom, tilt);
        anchor.setYRot(yaw); anchor.yRotO = yaw; anchor.setXRot(pitch); anchor.xRotO = pitch;
    }
    @SubscribeEvent public static void tick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || !inRegion()) {
            if (active) exit();
            ClientState.clear(); return;
        }
        ClientState.updateWorld();
        ShipRenderer.tick(mc);
        // On arrival, a company without an identity yet asks for one (name, colour, flag, portrait), as Anno does at the start.
        if (!brandAsked && !Boolean.getBoolean("annocraft1800.clientSmoke")) {
            var company = ClientState.myCompany();
            if (company != null && !company.configured() && (mc.screen == null || mc.screen instanceof RtsScreen)) {
                brandAsked = true;
                if (mc.screen instanceof RtsScreen rts) rts.openChild(new BrandingScreen(rts)); else mc.setScreen(new BrandingScreen(null));
            }
        }
        while (TOGGLE.consumeClick()) { if (active) { exit(); mc.setScreen(null); } else enter(); }
        while (COLONY.consumeClick()) if (mc.screen == null && !ClientState.DEFINITIONS.isEmpty()) mc.setScreen(new ColonyScreen(null));
        while (MAP.consumeClick()) if (mc.screen == null && !ClientState.DEFINITIONS.isEmpty()) mc.setScreen(new StrategicMapScreen(null));
        if (ClientState.DEFINITIONS.isEmpty() && mc.player.tickCount % 20 == 0) AnnoNetwork.CHANNEL.sendToServer(new AnnoNetwork.SyncRequest());
        if (!active) return;
        if (!mc.player.isAlive()) { exit(); mc.setScreen(null); return; }
        prevX = x; prevZ = z; prevYaw = yaw; prevZoom = zoom; prevTilt = tilt;
        if (mc.screen instanceof RtsScreen && mc.isWindowActive()) {
            double side = (down(GLFW.GLFW_KEY_D) ? 1 : 0) - (down(GLFW.GLFW_KEY_A) ? 1 : 0);
            double front = (down(GLFW.GLFW_KEY_S) || down(GLFW.GLFW_KEY_DOWN) ? 1 : 0) - (down(GLFW.GLFW_KEY_W) || down(GLFW.GLFW_KEY_UP) ? 1 : 0);
            side += (down(GLFW.GLFW_KEY_RIGHT) ? 1 : 0) - (down(GLFW.GLFW_KEY_LEFT) ? 1 : 0);
            // Edge scrolling: the cursor against a window border pans the camera.
            double mx = mc.mouseHandler.xpos(), my = mc.mouseHandler.ypos();
            int ww = mc.getWindow().getScreenWidth(), wh = mc.getWindow().getScreenHeight();
            if (mx <= 2) side -= 1; else if (mx >= ww - 3) side += 1;
            if (my <= 2) front -= 1; else if (my >= wh - 3) front += 1;
            side = Math.max(-1, Math.min(1, side)); front = Math.max(-1, Math.min(1, front));
            double[] move = CameraMath.rotateCoords(-side, -front, yaw);
            double speed = Math.max(.6, zoom * .055) * (down(GLFW.GLFW_KEY_LEFT_SHIFT) ? 2.5 : 1);
            int half = ClientState.regionSize / 2 - 8;
            x = Math.max(-half, Math.min(half, x + move[0] * speed)); z = Math.max(-half, Math.min(half, z + move[1] * speed));
            if (down(GLFW.GLFW_KEY_Q)) yaw -= 3;
            if (down(GLFW.GLFW_KEY_E)) yaw += 3;
            if (down(GLFW.GLFW_KEY_PAGE_UP)) tilt = CameraMath.tilt(tilt - 1.5f);
            if (down(GLFW.GLFW_KEY_PAGE_DOWN)) tilt = CameraMath.tilt(tilt + 1.5f);
        }
        zoom += (zoomTarget - zoom) * .35f;
        if (Math.abs(zoomTarget - zoom) < .01f) zoom = zoomTarget;
        updateAnchor();
        mc.player.setYRot(bodyYaw); mc.player.setXRot(bodyPitch); mc.player.setDeltaMovement(Vec3.ZERO);
        if (++ticks % 5 == 0) AnnoNetwork.CHANNEL.sendToServer(new AnnoNetwork.CameraCommand(true, x, z, zoom, yaw, tilt));
    }
    private static boolean down(int key) { return InputConstants.isKeyDown(Minecraft.getInstance().getWindow().getWindow(), key); }
    @SubscribeEvent public static void movement(MovementInputUpdateEvent event) {
        if (!active) return;
        var i = event.getInput(); i.leftImpulse = 0; i.forwardImpulse = 0; i.jumping = false; i.shiftKeyDown = false;
        i.up = i.down = i.left = i.right = false;
    }
    @SubscribeEvent public static void camera(ViewportEvent.ComputeCameraAngles event) {
        if (!active || anchor == null) return;
        float t = (float) event.getPartialTick();
        renderX = prevX + (x - prevX) * t; renderZ = prevZ + (z - prevZ) * t;
        renderYaw = prevYaw + (yaw - prevYaw) * t; renderZoom = prevZoom + (zoom - prevZoom) * t; renderTilt = prevTilt + (tilt - prevTilt) * t;
        Vec3 e = eye(renderX, renderZ, renderYaw, renderZoom, renderTilt);
        event.setYaw(renderYaw); event.setPitch(CameraMath.pitch(renderZoom, renderTilt)); event.setRoll(0);
        event.getCamera().setPosition(e.x, e.y, e.z);
    }
    /**
     * No render-distance fog over the terrain in the management view: the real chunks meet the distant view (LOD)
     * directly, and the distant view carries its own haze. The sky keeps its horizon blend.
     */
    @SubscribeEvent public static void fog(ViewportEvent.RenderFog event) {
        if (!active || event.getType() != net.minecraft.world.level.material.FogType.NONE) return;
        float far = event.getMode() == FogRenderer.FogMode.FOG_TERRAIN ? 1_000_000 : Math.max(event.getFarPlaneDistance(), renderZoom * 3.2f);
        event.setFarPlaneDistance(far); event.setNearPlaneDistance(far * .75f); event.setCanceled(true);
    }
    /** Middle mouse drag turns the camera (sideways) and tilts it towards the horizon (up and down), as in Anno. */
    public static void rotateBy(double dx, double dy) {
        yaw += (float) dx * .5f; prevYaw = yaw;
        tilt = CameraMath.tilt(tilt + (float) dy * .4f); prevTilt = tilt;
    }
    public static void tiltBy(float degrees) { tilt = CameraMath.tilt(tilt + degrees); }
    @SubscribeEvent public static void fov(ViewportEvent.ComputeFov event) { if (active) event.setFOV(45); }
    @SubscribeEvent public static void interaction(InputEvent.InteractionKeyMappingTriggered event) {
        if (active) { event.setCanceled(true); event.setSwingHand(false); }
    }
    @SubscribeEvent public static void hand(RenderHandEvent event) { if (active) event.setCanceled(true); }
    private static boolean brandAsked;
    @SubscribeEvent public static void logout(ClientPlayerNetworkEvent.LoggingOut event) { if (active) exit(); ClientState.clear(); brandAsked = false; }
    public static void pick(double mouseX, double mouseY, int width, int height) {
        if (forcedHover != null) { hover = forcedHover; hoverValid = placement != null && previewValid(hover, placement); return; }
        hover = null; hoverValid = false; Minecraft mc = Minecraft.getInstance(); if (mc.level == null || anchor == null) return;
        double[] f = forward(); Vec3 forward = new Vec3(f[0], f[1], f[2]);
        Vec3 right = forward.cross(new Vec3(0, 1, 0)).normalize(), up = right.cross(forward).normalize();
        double scale = Math.tan(Math.toRadians(45 / 2.0));
        Vec3 ray = forward.add(right.scale((2 * mouseX / width - 1) * (double)width / height * scale))
                .add(up.scale((1 - 2 * mouseY / height) * scale)).normalize();
        Vec3 start = eye(renderX, renderZ, renderYaw, renderZoom, renderTilt), end = start.add(ray.scale(Math.max(400, renderZoom * 3)));
        // Ships first: the one whose hull lies nearest to the cursor's ray.
        hoverShip = null;
        if (placement == null && roadMode == 0) {
            double best = Math.max(4, renderZoom * .06);
            for (var e : ShipRenderer.SEEN.entrySet()) {
                Vec3 d = new Vec3(e.getValue()[0], fr.annocraft.world.IslandLayout.SEA_LEVEL + 2.5, e.getValue()[1]).subtract(start);
                double along = d.dot(ray); if (along <= 0) continue;
                double off = d.subtract(ray.scale(along)).length();
                if (off < best) { best = off; hoverShip = e.getKey(); }
            }
        }
        // The cursor passes through trees and plants, as in Anno: they are felled by whatever is built there.
        BlockHitResult hit = null;
        for (int tries = 0; tries < 48; tries++) {
            hit = mc.level.clip(new ClipContext(start, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.ANY, anchor));
            if (hit.getType() != HitResult.Type.BLOCK || !foliage(mc.level.getBlockState(hit.getBlockPos()))) break;
            Vec3 past = hit.getLocation(); BlockPos through = hit.getBlockPos();
            for (int k = 0; k < 40 && BlockPos.containing(past).equals(through); k++) past = past.add(ray.scale(.05));
            start = past; hit = null;
        }
        if (hit == null || hit.getType() != HitResult.Type.BLOCK) return;
        BlockPos ground = hit.getBlockPos();
        if (roadMode != 0) { hover = ground; hoverValid = true; return; }
        if (placement == null) { hover = ground; return; }
        if (placement.coastal() && ClientState.layout != null) {
            // Harbour buildings turn to face the sea by themselves and sit at the height of the beach.
            int turn = fr.annocraft.building.Siting.coastRotation(ClientState.layout, placement, ground.getX(), ground.getZ(), rotation);
            if (turn >= 0) rotation = turn;
            hover = new BlockPos(ground.getX(), fr.annocraft.building.Siting.deck(ClientState.layout, placement, ground.getX(), ground.getZ(), rotation) + 1, ground.getZ());
        } else hover = ground.above();
        hoverValid = previewValid(hover, placement);
    }
    /** Trees and plants, which the cursor sees through. */
    static boolean foliage(net.minecraft.world.level.block.state.BlockState s) {
        return s.is(net.minecraft.tags.BlockTags.LOGS) || s.is(net.minecraft.tags.BlockTags.LEAVES) || s.is(net.minecraft.tags.BlockTags.REPLACEABLE)
                || s.is(net.minecraft.tags.BlockTags.FLOWERS) || s.is(net.minecraft.tags.BlockTags.SAPLINGS) || s.is(net.minecraft.world.level.block.Blocks.LARGE_FERN);
    }
    /** Client mirror of the server's site checks; {@code p} is the first air block above the footprint's corner. */
    private static boolean previewValid(BlockPos p, BuildingDefinition def) {
        Minecraft mc = Minecraft.getInstance(); int w = def.width(rotation), d = def.depth(rotation), half = ClientState.regionSize / 2;
        if (Math.abs(p.getX()) >= half || Math.abs(p.getZ()) >= half || p.getX() + w >= half || p.getZ() + d >= half) return false;
        if (ClientState.layout == null) return false;
        var island = fr.annocraft.server.BuildingService.islandOf(ClientState.layout, def, p.below(), rotation).orElse(null); if (island == null) return false;
        if (def.coastal() && fr.annocraft.building.Siting.coast(ClientState.layout, def, p.getX(), p.getY() - 1, p.getZ(), rotation) != null) return false;
        var e = def.economy(); String owner = ClientState.owner(island.id());
        if (!e.buildableIn(ClientState.world) || (!owner.isEmpty() && !ClientState.mine(owner))) return false;
        if ((e.fertility() != null && !island.hasFertility(e.fertility())) || (e.deposit() != null && !island.hasDeposit(e.deposit()))) return false;
        if (e.deposit() != null && !ClientState.layout.depositIn(e.deposit(), p.getX(), p.getZ(), w, d)) return false;
        for (BuildingInstance b : ClientState.visible()) if (b.overlaps(p.below(), w, d)) return false;
        for (int cx = 0; cx < w; cx++) for (int cz = 0; cz < d; cz++) {
            BlockPos ground = p.offset(cx, -1, cz);
            if (!def.coastal() && (!island.buildable(ground.getX(), ground.getZ()) || ClientState.layout.height(ground.getX(), ground.getZ()) <= 64)) return false;
            if (!mc.level.hasChunkAt(ground)) return false;
            boolean deckOnly = def.coastal() && ClientState.layout.height(ground.getX(), ground.getZ()) < ground.getY();
            if (!deckOnly && (!mc.level.getBlockState(ground).isSolidRender(mc.level, ground) || !mc.level.getFluidState(ground).isEmpty())) return false;
            for (int y = 0; y < def.height() - 1; y++) {
                var state = mc.level.getBlockState(p.offset(cx, y, cz));
                if (!state.isAir() && !fr.annocraft.server.Nature.natural(state)) return false;
            }
        }
        return true;
    }
    /** End of a road drag: lays the segment when the cursor left the starting tile. */
    public static void releaseRoad() {
        if (roadMode == 0 || roadStart == null || hover == null || hover.equals(roadStart)) return;
        AnnoNetwork.CHANNEL.sendToServer(new AnnoNetwork.RoadCommand(roadMode == 2, roadStart, hover));
        roadStart = null;
    }
    public static void clickWorld() {
        if (hover == null) return;
        if (roadMode != 0) {
            if (roadStart == null) { roadStart = hover; return; }
            AnnoNetwork.CHANNEL.sendToServer(new AnnoNetwork.RoadCommand(roadMode == 2, roadStart, hover));
            roadStart = null; return;
        }
        if (placement != null) {
            AnnoNetwork.CHANNEL.sendToServer(new AnnoNetwork.BuildCommand(0, placement.id(), hover, rotation, new java.util.UUID(0, 0)));
        } else ClientState.selected = ClientState.visible().stream().filter(b -> b.contains(hover)).map(BuildingInstance::id).findFirst().orElse(null);
    }

    // ---------------------------------------------------------------- ships as units, as in Anno

    /** The player's ships selected in the management view, and the ship under the cursor. */
    public static final Set<java.util.UUID> selectedShips = new LinkedHashSet<>();
    public static java.util.UUID hoverShip;
    /** Last order's destination, shown a moment on the water. */
    static double[] orderMark; static long orderTime;
    static boolean own(java.util.UUID id) { var s = ShipRenderer.find(id); return s != null && !s.contains("owner"); }
    /** Left click on a ship: selects it (Shift adds it to the selection). @return whether a ship was clicked */
    public static boolean clickShip(boolean add) {
        if (hoverShip == null || !own(hoverShip)) return false;
        if (!add) selectedShips.clear();
        if (!selectedShips.remove(hoverShip) || !add) selectedShips.add(hoverShip);
        ClientState.selected = null;
        return true;
    }
    /** Box selection: the player's ships whose hull lies inside the dragged rectangle on screen. */
    public static void selectBox(double x0, double y0, double x1, double y1, int width, int height, boolean add) {
        if (!add) selectedShips.clear();
        for (var e : ShipRenderer.SEEN.entrySet()) {
            double[] s = project(e.getValue()[0], fr.annocraft.world.IslandLayout.SEA_LEVEL + 2, e.getValue()[1], width, height);
            if (s != null && own(e.getKey()) && s[0] >= Math.min(x0, x1) && s[0] <= Math.max(x0, x1) && s[1] >= Math.min(y0, y1) && s[1] <= Math.max(y0, y1)) selectedShips.add(e.getKey());
        }
        if (!selectedShips.isEmpty()) ClientState.selected = null;
    }
    /**
     * Right click with ships selected, as in Anno: on an enemy ship, hunt it; on open water, sail there; on one of
     * the player's ports, moor there; on a rival's island at war, besiege it. @return whether an order was given
     */
    public static boolean orderShips() {
        selectedShips.removeIf(id -> !own(id));
        if (selectedShips.isEmpty()) return false;
        String ids = String.join(",", selectedShips.stream().map(java.util.UUID::toString).toList());
        var target = hoverShip == null ? null : ShipRenderer.find(hoverShip);
        if (target != null && target.contains("owner")) {
            AnnoNetwork.action("ship_hunt", "ships", ids, "target", hoverShip.toString());
            double[] p = ShipRenderer.SEEN.get(hoverShip); if (p != null) mark(p[0], p[1]);
            return true;
        }
        var layout = ClientState.layout;
        if (hover == null || layout == null) return true;
        int h = layout.height(hover.getX(), hover.getZ());
        if (h < fr.annocraft.world.IslandLayout.SEA_LEVEL - 1) {
            AnnoNetwork.action("ship_goto", "ships", ids, "x", hover.getX() + .5, "z", hover.getZ() + .5);
            mark(hover.getX() + .5, hover.getZ() + .5);
            return true;
        }
        var island = layout.islandAt(hover.getX(), hover.getZ()).orElse(null);
        if (island == null) return true;
        String owner = ClientState.owner(island.id());
        if (!owner.isEmpty() && !ClientState.mine(owner)) AnnoNetwork.action("ship_siege", "ships", ids, "island", island.id());
        else AnnoNetwork.action("ship_dock", "ships", ids, "island", island.id());
        mark(hover.getX() + .5, hover.getZ() + .5);
        return true;
    }
    private static void mark(double x, double z) { orderMark = new double[]{x, z}; orderTime = System.currentTimeMillis(); }
    /** Screen position (GUI pixels) of a world point, or null behind the camera. */
    public static double[] project(double x, double y, double z, int width, int height) {
        double[] f = forward(); Vec3 forward = new Vec3(f[0], f[1], f[2]);
        Vec3 right = forward.cross(new Vec3(0, 1, 0)).normalize(), up = right.cross(forward).normalize();
        Vec3 d = new Vec3(x, y, z).subtract(eye(renderX, renderZ, renderYaw, renderZoom, renderTilt));
        double depth = d.dot(forward); if (depth < .5) return null;
        double scale = Math.tan(Math.toRadians(45 / 2.0));
        return new double[]{width / 2.0 * (1 + d.dot(right) / (depth * scale * width / height)), height / 2.0 * (1 - d.dot(up) / (depth * scale))};
    }
    /** Rings under the selected and hovered ships, the selected ships' courses, and the last order's mark. */
    private static void shipOverlays(PoseStack pose, VertexConsumer lines) {
        double sea = fr.annocraft.world.IslandLayout.SEA_LEVEL + 1.05;
        for (var e : ShipRenderer.SEEN.entrySet()) {
            boolean chosen = selectedShips.contains(e.getKey()), hovered = e.getKey().equals(hoverShip);
            if (!chosen && !hovered) continue;
            boolean enemy = !own(e.getKey());
            float r = enemy ? 1 : chosen ? .35f : 1, g = enemy ? .25f : chosen ? 1 : 1, b = enemy ? .2f : chosen ? .45f : 1;
            ring(pose, lines, e.getValue()[0], sea, e.getValue()[1], 6.5, r, g, b);
            if (chosen) {
                var s = ShipRenderer.find(e.getKey());
                if (s != null && s.contains("path")) {
                    List<double[]> path = ShipRenderer.path(s); double t = ShipRenderer.progress(s);
                    double[] from = e.getValue(); double total = fr.annocraft.economy.SeaRoutes.length(path), walked = 0;
                    for (int i = 1; i < path.size(); i++) {
                        walked += Math.hypot(path.get(i)[0] - path.get(i - 1)[0], path.get(i)[1] - path.get(i - 1)[1]);
                        if (walked < t * total) continue;
                        segment(pose, lines, from[0], sea, from[1], path.get(i)[0], sea, path.get(i)[1], .95f, .85f, .45f);
                        from = path.get(i);
                    }
                }
            }
        }
        if (orderMark != null && System.currentTimeMillis() - orderTime < 1500) {
            double grow = (System.currentTimeMillis() - orderTime) / 1500.0;
            ring(pose, lines, orderMark[0], sea, orderMark[1], 2 + grow * 5, .95f, .85f, .45f);
        }
    }
    private static void ring(PoseStack pose, VertexConsumer lines, double x, double y, double z, double radius, float r, float g, float b) {
        int n = 40;
        for (int i = 0; i < n; i++) {
            double a0 = Math.PI * 2 * i / n, a1 = Math.PI * 2 * (i + 1) / n;
            segment(pose, lines, x + Math.cos(a0) * radius, y, z + Math.sin(a0) * radius, x + Math.cos(a1) * radius, y, z + Math.sin(a1) * radius, r, g, b);
        }
    }
    private static void segment(PoseStack pose, VertexConsumer lines, double x0, double y0, double z0, double x1, double y1, double z1, float r, float g, float b) {
        var m = pose.last().pose(); var n = pose.last().normal();
        float nx = (float) (x1 - x0), ny = (float) (y1 - y0), nz = (float) (z1 - z0), l = (float) Math.max(1e-6, Math.sqrt(nx * nx + ny * ny + nz * nz));
        lines.vertex(m, (float) x0, (float) y0, (float) z0).color(r, g, b, 1f).normal(n, nx / l, ny / l, nz / l).endVertex();
        lines.vertex(m, (float) x1, (float) y1, (float) z1).color(r, g, b, 1f).normal(n, nx / l, ny / l, nz / l).endVertex();
    }
    private static void roadPreview(RenderLevelStageEvent event) {
        if (hover == null) return;
        BlockPos from = roadStart == null ? hover : roadStart;
        boolean tooLong = Math.abs(hover.getX() - from.getX()) + Math.abs(hover.getZ() - from.getZ()) >= fr.annocraft.server.RoadService.MAX_LENGTH;
        float r = roadMode == 2 || tooLong ? 1 : .95f, g = roadMode == 2 || tooLong ? .25f : .8f, b = .2f;
        PoseStack pose = event.getPoseStack(); Vec3 camera = event.getCamera().getPosition();
        pose.pushPose(); pose.translate(-camera.x, -camera.y, -camera.z);
        var buffers = Minecraft.getInstance().renderBuffers().bufferSource(); var lines = buffers.getBuffer(RenderType.lines());
        grid(pose, lines, hover.getX(), hover.getZ());
        for (int[] t : fr.annocraft.server.RoadService.path(from.getX(), from.getZ(), hover.getX(), hover.getZ())) {
            int y = ClientState.layout == null ? hover.getY() : ClientState.layout.height(t[0], t[1]);
            LevelRenderer.renderLineBox(pose, lines, new AABB(t[0], y + 1.01, t[1], t[0] + 1, y + 1.05, t[1] + 1), r, g, b, t[2] == 0 ? .9f : .5f);
        }
        buffers.endBatch(RenderType.lines()); pose.popPose();
    }
    /** Anno's construction grid: tile lines on the ground around the cursor, fading with distance. */
    private static void grid(PoseStack pose, VertexConsumer lines, int cx, int cz) {
        if (ClientState.layout == null) return;
        int radius = 14; var m = pose.last().pose(); var n = pose.last().normal();
        for (int x = cx - radius; x <= cx + radius; x++) for (int z = cz - radius; z <= cz + radius; z++) {
            double distance = Math.hypot(x + .5 - cx, z + .5 - cz); if (distance > radius) continue;
            float alpha = (float) (.45 * (1 - distance / radius));
            int h = ClientState.layout.height(x, z); if (h <= 64) continue;
            float y = h + 1.02f;
            // Two edges per tile; the neighbours draw the other two.
            lines.vertex(m, x, y, z).color(1f, 1f, 1f, alpha).normal(n, 1, 0, 0).endVertex();
            lines.vertex(m, x + 1, y, z).color(1f, 1f, 1f, alpha).normal(n, 1, 0, 0).endVertex();
            lines.vertex(m, x, y, z).color(1f, 1f, 1f, alpha).normal(n, 0, 0, 1).endVertex();
            lines.vertex(m, x, y, z + 1).color(1f, 1f, 1f, alpha).normal(n, 0, 0, 1).endVertex();
        }
    }
    /** Deposit sites of the kind a mine needs: gold frames around the slots, with a marker post. */
    private static void deposits(PoseStack pose, VertexConsumer lines, String type, double cx, double cz) {
        if (ClientState.layout == null) return;
        int range = 220;
        for (var d : ClientState.layout.depositsNear((int) cx - range, (int) cz - range, (int) cx + range, (int) cz + range, 0)) {
            if (!d.type().equals(type)) continue;
            int s = fr.annocraft.world.IslandLayout.SLOT, y = ClientState.layout.height(d.x(), d.z());
            LevelRenderer.renderLineBox(pose, lines, new AABB(d.x() - s, y + 1, d.z() - s, d.x() + s + 1, y + 1.3, d.z() + s + 1), 1, .8f, .25f, 1);
            LevelRenderer.renderLineBox(pose, lines, new AABB(d.x() + .4, y + 1, d.z() + .4, d.x() + .6, y + 14, d.z() + .6), 1, .8f, .25f, 1);
        }
    }
    /** RTS overlays: buildings cut off from the road network, and service or booster radii. */
    @SubscribeEvent public static void overlays(RenderLevelStageEvent event) {
        if (!active || event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;
        PoseStack pose = event.getPoseStack(); Vec3 camera = event.getCamera().getPosition();
        pose.pushPose(); pose.translate(-camera.x, -camera.y, -camera.z);
        var buffers = Minecraft.getInstance().renderBuffers().bufferSource(); var lines = buffers.getBuffer(RenderType.lines());
        for (BuildingInstance b : ClientState.visible()) {
            var site = ClientState.SITES.get(b.id()); var def = ClientState.DEFINITIONS.get(b.definition());
            if (site == null || def == null || site.getBoolean("connected") || def.economy().storageNode()) continue;
            if (Math.abs(b.origin().getX() - camera.x) > 160 || Math.abs(b.origin().getZ() - camera.z) > 160) continue;
            LevelRenderer.renderLineBox(pose, lines, new AABB(b.origin().getX() - .05, b.origin().getY(), b.origin().getZ() - .05,
                    b.origin().getX() + b.width() + .05, b.origin().getY() + .3, b.origin().getZ() + b.depth() + .05), 1, .25f, .2f, 1);
        }
        BuildingInstance selected = ClientState.BUILDINGS.get(ClientState.selected);
        if (placement != null && hover != null) circle(pose, lines, placement, hover.getX() + placement.width(rotation) / 2.0, hover.getY(), hover.getZ() + placement.depth(rotation) / 2.0);
        else if (selected != null && ClientState.DEFINITIONS.containsKey(selected.definition()))
            circle(pose, lines, ClientState.DEFINITIONS.get(selected.definition()), selected.origin().getX() + selected.width() / 2.0, selected.origin().getY(), selected.origin().getZ() + selected.depth() / 2.0);
        shipOverlays(pose, lines);
        buffers.endBatch(RenderType.lines()); pose.popPose();
    }
    private static void circle(PoseStack pose, com.mojang.blaze3d.vertex.VertexConsumer lines, BuildingDefinition def, double cx, double y, double cz) {
        var e = def.economy(); int radius = e.serviceProvider() ? e.radius() : e.booster() ? e.boostRadius() : 0;
        if (radius <= 0) return;
        float r = e.booster() ? .4f : .3f, g = e.booster() ? .7f : .9f, b = e.booster() ? 1 : .5f;
        int segments = Math.max(48, radius * 3);
        for (int i = 0; i < segments; i++) {
            double a = Math.PI * 2 * i / segments, x = cx + Math.cos(a) * radius, z = cz + Math.sin(a) * radius;
            LevelRenderer.renderLineBox(pose, lines, new AABB(x - .15, y + .05, z - .15, x + .15, y + .25, z + .15), r, g, b, .9f);
        }
    }
    @SubscribeEvent public static void preview(RenderLevelStageEvent event) {
        if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS && inRegion()) ShipRenderer.render(event);
        if (!active || event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;
        if (roadMode != 0) { roadPreview(event); return; }
        BuildingInstance selected = ClientState.BUILDINGS.get(ClientState.selected);
        // The bottom layer of a building replaces the ground: its box starts one block below the hovered air block.
        BlockPos p; int w, h, d; boolean valid;
        if (placement != null && hover != null) { p = hover.below(); w = placement.width(rotation); h = placement.height(); d = placement.depth(rotation); valid = hoverValid; }
        else if (selected != null) { p = selected.origin(); w = selected.width(); h = selected.height(); d = selected.depth(); valid = true; }
        else return;
        PoseStack pose = event.getPoseStack(); Vec3 camera = event.getCamera().getPosition();
        pose.pushPose(); pose.translate(-camera.x, -camera.y, -camera.z);
        // A building being placed shows through the trees in its way: its depth is squeezed in front of the scene's.
        if (placement != null) org.lwjgl.opengl.GL11.glDepthRange(0, .03);
        var buffers = Minecraft.getInstance().renderBuffers().bufferSource(); var lines = buffers.getBuffer(RenderType.lines());
        if (placement != null) {
            grid(pose, lines, p.getX() + w / 2, p.getZ() + d / 2);
            if (placement.economy().deposit() != null) deposits(pose, lines, placement.economy().deposit(), p.getX(), p.getZ());
        }
        LevelRenderer.renderLineBox(pose, lines, new AABB(p.getX(), p.getY() + 1, p.getZ(), p.getX() + w, p.getY() + h, p.getZ() + d), valid ? .2f : 1, valid ? .9f : .2f, .3f, .85f);
        if (placement != null) {
            // Translucent ghost of the real server-provided template, green when valid and red otherwise.
            buffers.endBatch(RenderType.lines());
            BuildingPreview.ghost(pose, placement, p, rotation, valid, System.nanoTime() / 1e9);
            lines = buffers.getBuffer(RenderType.lines());
        }
        // Footprint tiles, drawn on the ground's surface.
        for (int ix = 0; ix < w; ix++) for (int iz = 0; iz < d; iz++)
            LevelRenderer.renderLineBox(pose, lines, new AABB(p.getX() + ix, p.getY() + 1.02, p.getZ() + iz, p.getX() + ix + 1, p.getY() + 1.04, p.getZ() + iz + 1), valid ? .2f : 1, valid ? .9f : .2f, .3f, .5f);
        buffers.endBatch(RenderType.lines()); pose.popPose();
        if (placement != null) org.lwjgl.opengl.GL11.glDepthRange(0, 1);
    }
}
