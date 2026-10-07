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
    public static float yaw = 135, zoom = CameraMath.DEFAULT_ZOOM;
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
    public static boolean hoverValid;
    public static final KeyMapping TOGGLE = new KeyMapping("key.annocraft1800.rts", GLFW.GLFW_KEY_F6, "key.categories.annocraft1800");
    public static final KeyMapping COLONY = new KeyMapping("key.annocraft1800.colony", GLFW.GLFW_KEY_J, "key.categories.annocraft1800");
    @Mod.EventBusSubscriber(modid = AnnoCraft.ID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
    public static final class Keys {
        @SubscribeEvent public static void register(RegisterKeyMappingsEvent event) { event.register(TOGGLE); event.register(COLONY); }
    }
    public static boolean inRegion() { return Minecraft.getInstance().level != null && AnnoCraft.isColony(Minecraft.getInstance().level.dimension()); }
    public static void enter() {
        Minecraft mc = Minecraft.getInstance(); if (mc.player == null || !inRegion() || ClientState.DEFINITIONS.isEmpty()) return;
        active = true; x = mc.player.getX(); z = mc.player.getZ(); yaw = 135; zoom = CameraMath.DEFAULT_ZOOM;
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
    private static double[] forward() {
        double a = Math.toRadians(yaw), b = Math.toRadians(55);
        return new double[]{-Math.sin(a) * Math.cos(b), -Math.sin(b), Math.cos(a) * Math.cos(b)};
    }
    private static void updateAnchor() {
        if (anchor == null) return;
        double[] f = forward(); double distance = zoom / Math.sin(Math.toRadians(55));
        anchor.setPos(x - f[0] * distance, 74 - f[1] * distance - anchor.getEyeHeight(), z - f[2] * distance);
        anchor.xo = anchor.getX(); anchor.yo = anchor.getY(); anchor.zo = anchor.getZ();
        anchor.setYRot(yaw); anchor.yRotO = yaw; anchor.setXRot(55); anchor.xRotO = 55;
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
        if (ClientState.DEFINITIONS.isEmpty() && mc.player.tickCount % 20 == 0) AnnoNetwork.CHANNEL.sendToServer(new AnnoNetwork.SyncRequest());
        if (!active) return;
        if (!mc.player.isAlive()) { exit(); mc.setScreen(null); return; }
        if (mc.screen instanceof RtsScreen && mc.isWindowActive()) {
            double side = (down(GLFW.GLFW_KEY_D) ? 1 : 0) - (down(GLFW.GLFW_KEY_A) ? 1 : 0);
            double front = (down(GLFW.GLFW_KEY_S) || down(GLFW.GLFW_KEY_DOWN) ? 1 : 0) - (down(GLFW.GLFW_KEY_W) || down(GLFW.GLFW_KEY_UP) ? 1 : 0);
            side += (down(GLFW.GLFW_KEY_RIGHT) ? 1 : 0) - (down(GLFW.GLFW_KEY_LEFT) ? 1 : 0);
            double[] move = CameraMath.rotateCoords(-side, -front, yaw);
            double speed = Math.min(3, Math.sqrt(zoom) * .3);
            int half = ClientState.regionSize / 2 - 8;
            x = Math.max(-half, Math.min(half, x + move[0] * speed)); z = Math.max(-half, Math.min(half, z + move[1] * speed));
            if (down(GLFW.GLFW_KEY_Q)) yaw -= 2;
            if (down(GLFW.GLFW_KEY_E)) yaw += 2;
            updateAnchor();
        }
        mc.player.setYRot(bodyYaw); mc.player.setXRot(bodyPitch); mc.player.setDeltaMovement(Vec3.ZERO);
        if (++ticks % 5 == 0) AnnoNetwork.CHANNEL.sendToServer(new AnnoNetwork.CameraCommand(true, x, z));
    }
    private static boolean down(int key) { return InputConstants.isKeyDown(Minecraft.getInstance().getWindow().getWindow(), key); }
    @SubscribeEvent public static void movement(MovementInputUpdateEvent event) {
        if (!active) return;
        var i = event.getInput(); i.leftImpulse = 0; i.forwardImpulse = 0; i.jumping = false; i.shiftKeyDown = false;
        i.up = i.down = i.left = i.right = false;
    }
    @SubscribeEvent public static void camera(ViewportEvent.ComputeCameraAngles event) {
        if (!active || anchor == null) return;
        event.setYaw(yaw); event.setPitch(55); event.setRoll(0);
        event.getCamera().setPosition(anchor.getX(), anchor.getY() + anchor.getEyeHeight(), anchor.getZ());
    }
    @SubscribeEvent public static void fov(ViewportEvent.ComputeFov event) { if (active) event.setFOV(45); }
    @SubscribeEvent public static void interaction(InputEvent.InteractionKeyMappingTriggered event) {
        if (active) { event.setCanceled(true); event.setSwingHand(false); }
    }
    @SubscribeEvent public static void hand(RenderHandEvent event) { if (active) event.setCanceled(true); }
    @SubscribeEvent public static void logout(ClientPlayerNetworkEvent.LoggingOut event) { if (active) exit(); ClientState.clear(); }
    public static void pick(double mouseX, double mouseY, int width, int height) {
        hover = null; hoverValid = false; Minecraft mc = Minecraft.getInstance(); if (mc.level == null || anchor == null) return;
        double[] f = forward(); Vec3 forward = new Vec3(f[0], f[1], f[2]);
        Vec3 right = forward.cross(new Vec3(0, 1, 0)).normalize(), up = right.cross(forward).normalize();
        double scale = Math.tan(Math.toRadians(45 / 2.0));
        Vec3 ray = forward.add(right.scale((2 * mouseX / width - 1) * (double)width / height * scale))
                .add(up.scale((1 - 2 * mouseY / height) * scale)).normalize();
        Vec3 start = new Vec3(anchor.getX(), anchor.getY() + anchor.getEyeHeight(), anchor.getZ());
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
    public static void clickWorld() {
        if (hover == null) return;
        if (roadMode != 0) {
            if (roadStart == null) { roadStart = hover; return; }
            AnnoNetwork.CHANNEL.sendToServer(new AnnoNetwork.RoadCommand(roadMode == 2, roadStart, hover));
            roadStart = roadMode == 1 ? hover : null; return;
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
            // A wireframe of the real server-provided template, including datapack overrides.
            for (ClientState.PreviewBlock block : ClientState.PREVIEWS.getOrDefault(placement.id(), java.util.List.of())) {
                BlockPos local = block.position();
                BlockPos q = switch (rotation) {
                    case 1 -> new BlockPos(placement.depth() - 1 - local.getZ(), local.getY(), local.getX());
                    case 2 -> new BlockPos(placement.width() - 1 - local.getX(), local.getY(), placement.depth() - 1 - local.getZ());
                    case 3 -> new BlockPos(local.getZ(), local.getY(), placement.width() - 1 - local.getX());
                    default -> local;
                };
                LevelRenderer.renderLineBox(pose, lines, new AABB(p.offset(q)), valid ? .4f : 1, valid ? .8f : .3f, .6f, .35f);
            }
        }
        // Floor grid and asymmetrical entrance marker show the footprint and orientation.
        for (int ix = 0; ix < w; ix++) for (int iz = 0; iz < d; iz++)
            LevelRenderer.renderLineBox(pose, lines, new AABB(p.getX() + ix, p.getY() + .02, p.getZ() + iz, p.getX() + ix + 1, p.getY() + .04, p.getZ() + iz + 1), valid ? .2f : 1, valid ? .9f : .2f, .3f, .5f);
        buffers.endBatch(RenderType.lines()); pose.popPose();
    }
}
