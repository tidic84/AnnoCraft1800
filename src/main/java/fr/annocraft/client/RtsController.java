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
        yaw = prevYaw = renderYaw = 135; zoom = zoomTarget = prevZoom = renderZoom = CameraMath.DEFAULT_ZOOM;
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
        KeyMapping.releaseAll();
    }
    /** Set by the strategic map before entering, so the camera opens where the player clicked. */
    static boolean jumped;
    private static double[] forward(float yaw, float zoom) {
        double a = Math.toRadians(yaw), b = Math.toRadians(CameraMath.pitch(zoom));
        return new double[]{-Math.sin(a) * Math.cos(b), -Math.sin(b), Math.cos(a) * Math.cos(b)};
    }
    private static double[] forward() { return forward(renderYaw, renderZoom); }
    /** Eye position for a camera looking at (cx, 74, cz). */
    private static Vec3 eye(double cx, double cz, float yaw, float zoom) {
        double[] f = forward(yaw, zoom); double distance = zoom / Math.sin(Math.toRadians(CameraMath.pitch(zoom)));
        return new Vec3(cx - f[0] * distance, 74 - f[1] * distance, cz - f[2] * distance);
    }
    private static void updateAnchor() {
        if (anchor == null) return;
        Vec3 e = eye(x, z, yaw, zoom);
        anchor.setPos(e.x, e.y - anchor.getEyeHeight(), e.z);
        anchor.xo = anchor.getX(); anchor.yo = anchor.getY(); anchor.zo = anchor.getZ();
        float pitch = CameraMath.pitch(zoom);
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
        while (TOGGLE.consumeClick()) { if (active) { exit(); mc.setScreen(null); } else enter(); }
        while (COLONY.consumeClick()) if (mc.screen == null && !ClientState.DEFINITIONS.isEmpty()) mc.setScreen(new ColonyScreen(null));
        while (MAP.consumeClick()) if (mc.screen == null && !ClientState.DEFINITIONS.isEmpty()) mc.setScreen(new StrategicMapScreen(null));
        if (ClientState.DEFINITIONS.isEmpty() && mc.player.tickCount % 20 == 0) AnnoNetwork.CHANNEL.sendToServer(new AnnoNetwork.SyncRequest());
        if (!active) return;
        if (!mc.player.isAlive()) { exit(); mc.setScreen(null); return; }
        prevX = x; prevZ = z; prevYaw = yaw; prevZoom = zoom;
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
        }
        zoom += (zoomTarget - zoom) * .35f;
        if (Math.abs(zoomTarget - zoom) < .01f) zoom = zoomTarget;
        updateAnchor();
        mc.player.setYRot(bodyYaw); mc.player.setXRot(bodyPitch); mc.player.setDeltaMovement(Vec3.ZERO);
        if (++ticks % 5 == 0) AnnoNetwork.CHANNEL.sendToServer(new AnnoNetwork.CameraCommand(true, x, z, zoom));
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
        renderYaw = prevYaw + (yaw - prevYaw) * t; renderZoom = prevZoom + (zoom - prevZoom) * t;
        Vec3 e = eye(renderX, renderZ, renderYaw, renderZoom);
        event.setYaw(renderYaw); event.setPitch(CameraMath.pitch(renderZoom)); event.setRoll(0);
        event.getCamera().setPosition(e.x, e.y, e.z);
    }
    /** Pushes the fog back in the management view so the zoomed-out city stays readable. */
    @SubscribeEvent public static void fog(ViewportEvent.RenderFog event) {
        if (!active) return;
        float far = Math.max(event.getFarPlaneDistance(), renderZoom * 3.2f);
        event.setFarPlaneDistance(far); event.setNearPlaneDistance(far * .75f); event.setCanceled(true);
    }
    /** Middle mouse drag rotates the camera, as in Anno. */
    public static void rotateBy(double dx) { yaw += (float) dx * .5f; prevYaw = yaw; }
    @SubscribeEvent public static void fov(ViewportEvent.ComputeFov event) { if (active) event.setFOV(45); }
    @SubscribeEvent public static void interaction(InputEvent.InteractionKeyMappingTriggered event) {
        if (active) { event.setCanceled(true); event.setSwingHand(false); }
    }
    @SubscribeEvent public static void hand(RenderHandEvent event) { if (active) event.setCanceled(true); }
    @SubscribeEvent public static void logout(ClientPlayerNetworkEvent.LoggingOut event) { if (active) exit(); ClientState.clear(); }
    public static void pick(double mouseX, double mouseY, int width, int height) {
        if (forcedHover != null) { hover = forcedHover; hoverValid = placement != null && previewValid(hover, placement); return; }
        hover = null; hoverValid = false; Minecraft mc = Minecraft.getInstance(); if (mc.level == null || anchor == null) return;
        double[] f = forward(); Vec3 forward = new Vec3(f[0], f[1], f[2]);
        Vec3 right = forward.cross(new Vec3(0, 1, 0)).normalize(), up = right.cross(forward).normalize();
        double scale = Math.tan(Math.toRadians(45 / 2.0));
        Vec3 ray = forward.add(right.scale((2 * mouseX / width - 1) * (double)width / height * scale))
                .add(up.scale((1 - 2 * mouseY / height) * scale)).normalize();
        Vec3 start = eye(renderX, renderZ, renderYaw, renderZoom);
        BlockHitResult hit = mc.level.clip(new ClipContext(start, start.add(ray.scale(400)), ClipContext.Block.OUTLINE, ClipContext.Fluid.ANY, anchor));
        if (hit.getType() != HitResult.Type.BLOCK) return;
        BlockPos ground = hit.getBlockPos();
        if (roadMode != 0) { hover = ground; hoverValid = true; return; }
        if (placement == null) { hover = ground; return; }
        hover = ground.above(); hoverValid = previewValid(hover, placement);
    }
    private static boolean previewValid(BlockPos p, BuildingDefinition def) {
        Minecraft mc = Minecraft.getInstance(); int w = def.width(rotation), d = def.depth(rotation), half = ClientState.regionSize / 2;
        if (Math.abs(p.getX()) >= half || Math.abs(p.getZ()) >= half || p.getX() + w >= half || p.getZ() + d >= half) return false;
        if (ClientState.layout == null) return false;
        var island = ClientState.layout.islandAt(p.getX(), p.getZ()).orElse(null); if (island == null) return false;
        var e = def.economy(); String owner = ClientState.owner(island.id());
        if (!e.buildableIn(ClientState.world) || (!owner.isEmpty() && !owner.equals(fr.annocraft.economy.Diplomacy.PLAYER))) return false;
        if ((e.fertility() != null && !island.hasFertility(e.fertility())) || (e.deposit() != null && !island.hasDeposit(e.deposit()))) return false;
        for (BuildingInstance b : ClientState.visible()) if (b.overlaps(p, w, d)) return false;
        for (int cx = 0; cx < w; cx++) for (int cz = 0; cz < d; cz++) {
            BlockPos ground = p.offset(cx, -1, cz);
            if ((!def.coastal() && !island.buildable(ground.getX(), ground.getZ())) || ClientState.layout.height(ground.getX(), ground.getZ()) <= 64) return false;
            if (!mc.level.hasChunkAt(ground) || !mc.level.getBlockState(ground).isSolidRender(mc.level, ground) || !mc.level.getFluidState(ground).isEmpty()) return false;
            for (int y = 0; y < def.height(); y++) if (!mc.level.getBlockState(p.offset(cx, y, cz)).isAir()) return false;
        }
        if (def.coastal()) {
            boolean coast = false;
            for (int cx = -16; cx < w + 16 && !coast; cx++) for (int cz = -16; cz < d + 16; cz++)
                if (ClientState.layout.height(p.getX() + cx, p.getZ() + cz) < 64) { coast = true; break; }
            if (!coast) return false;
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
    private static void roadPreview(RenderLevelStageEvent event) {
        if (hover == null) return;
        BlockPos from = roadStart == null ? hover : roadStart;
        boolean tooLong = Math.abs(hover.getX() - from.getX()) + Math.abs(hover.getZ() - from.getZ()) >= fr.annocraft.server.RoadService.MAX_LENGTH;
        float r = roadMode == 2 || tooLong ? 1 : .95f, g = roadMode == 2 || tooLong ? .25f : .8f, b = .2f;
        PoseStack pose = event.getPoseStack(); Vec3 camera = event.getCamera().getPosition();
        pose.pushPose(); pose.translate(-camera.x, -camera.y, -camera.z);
        var buffers = Minecraft.getInstance().renderBuffers().bufferSource(); var lines = buffers.getBuffer(RenderType.lines());
        for (int[] t : fr.annocraft.server.RoadService.path(from.getX(), from.getZ(), hover.getX(), hover.getZ())) {
            int y = ClientState.layout == null ? hover.getY() : ClientState.layout.height(t[0], t[1]);
            LevelRenderer.renderLineBox(pose, lines, new AABB(t[0], y + 1.01, t[1], t[0] + 1, y + 1.05, t[1] + 1), r, g, b, .9f);
        }
        buffers.endBatch(RenderType.lines()); pose.popPose();
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
        BlockPos p; int w, h, d; boolean valid;
        if (placement != null && hover != null) { p = hover; w = placement.width(rotation); h = placement.height(); d = placement.depth(rotation); valid = hoverValid; }
        else if (selected != null) { p = selected.origin(); w = selected.width(); h = selected.height(); d = selected.depth(); valid = true; }
        else return;
        PoseStack pose = event.getPoseStack(); Vec3 camera = event.getCamera().getPosition();
        pose.pushPose(); pose.translate(-camera.x, -camera.y, -camera.z);
        var buffers = Minecraft.getInstance().renderBuffers().bufferSource(); var lines = buffers.getBuffer(RenderType.lines());
        LevelRenderer.renderLineBox(pose, lines, new AABB(p.getX(), p.getY(), p.getZ(), p.getX() + w, p.getY() + h, p.getZ() + d), valid ? .2f : 1, valid ? .9f : .2f, .3f, .85f);
        if (placement != null) {
            // Translucent ghost of the real server-provided template, green when valid and red otherwise.
            buffers.endBatch(RenderType.lines());
            BuildingPreview.ghost(pose, placement, p, rotation, valid, System.nanoTime() / 1e9);
            lines = buffers.getBuffer(RenderType.lines());
        }
        // Floor grid and asymmetrical entrance marker show the footprint and orientation.
        for (int ix = 0; ix < w; ix++) for (int iz = 0; iz < d; iz++)
            LevelRenderer.renderLineBox(pose, lines, new AABB(p.getX() + ix, p.getY() + .02, p.getZ() + iz, p.getX() + ix + 1, p.getY() + .04, p.getZ() + iz + 1), valid ? .2f : 1, valid ? .9f : .2f, .3f, .5f);
        buffers.endBatch(RenderType.lines()); pose.popPose();
    }
}
