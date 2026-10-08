package fr.annocraft.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import fr.annocraft.AnnoCraft;
import fr.annocraft.building.*;
import fr.annocraft.world.IslandLayout;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.*;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.*;
import org.lwjgl.opengl.GL11;
import java.lang.Math;
import java.util.*;
import java.util.concurrent.*;

/**
 * Distant view of the archipelago in the management camera, in the spirit of Distant Horizons and Voxy. What the
 * player has seen comes from the terrain cache (LodCache: real heights and block colours, towns and forests as
 * they are); the rest is drawn from the seed (IslandLayout: relief, forests, deposit hills) and the colony
 * snapshot (building roofs). Regions of 128 × 128 blocks are meshed on worker threads as columns of 1 to 16
 * blocks depending on their distance from the eye, with soft shading in hollows, and shallow water coloured by
 * its depth around the islands.
 * <p>
 * Without shaders the view is drawn before the terrain with its own far projection, then the depth buffer is
 * cleared so the real chunks draw over it. A shader pack (Oculus/Iris) fogs everything beyond the player's render
 * distance, far short of the management camera: there the game's far plane is pushed out, the scene's depth is kept
 * at the end of the level pass, and the distant view is drawn over the shader's finished picture against that depth,
 * leaving to the real blocks the chunks the shader still shows clearly.
 */
@Mod.EventBusSubscriber(modid = AnnoCraft.ID, value = Dist.CLIENT)
public final class LodRenderer {
    public static final int REGION = 128;
    private static final int SAND = 0xd8cc94, GRASS_OLD = 0x6e9e3c, GRASS_NEW = 0x5c9a36, FOREST_OLD = 0x3d6a2a, FOREST_DARK = 0x2c5230,
            JUNGLE = 0x2e7a2a, ROCK = 0x84837f, SLOT = 0x6e5a45, CLAY = 0xa36a4e, QUARTZ = 0xe3d9b5, OCEAN = 0x2a5d8f, HAZE = 0xa9c4dc,
            SHALLOW = 0x3fa7b4, LAGOON = 0x3584a8;
    private record Key(String world, int rx, int rz) { }
    /** Colours carry the face they belong to in their top byte (0 for tops), turned into normals at upload. */
    private record Mesh(float[] positions, int[] colors, int vertices) { }
    private static final int WEST = 1 << 24, EAST = 2 << 24, NORTH = 3 << 24, SOUTH = 4 << 24;
    private static final float[][] NORMALS = {{0, 1, 0}, {-1, 0, 0}, {1, 0, 0}, {0, 0, -1}, {0, 0, 1}};
    private static ShaderInstance lodShader;
    /** The distant view's own shader: sun and sky light, aerial haze, and a colour grade under shader packs. */
    @Mod.EventBusSubscriber(modid = AnnoCraft.ID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
    public static final class Shaders {
        @SubscribeEvent public static void register(RegisterShadersEvent event) throws java.io.IOException {
            event.registerShader(new ShaderInstance(event.getResourceProvider(), AnnoCraft.id("lod"), DefaultVertexFormat.POSITION_COLOR_NORMAL), s -> lodShader = s);
        }
    }
    private static final class Region {
        VertexBuffer buffer; int vertices, step = -1; long revision = Long.MIN_VALUE, seen = Long.MIN_VALUE, mask; boolean structures;
        long meshedAt;
        Future<Mesh> pending; int pendingStep; long pendingRevision, pendingSeen, pendingMask; boolean pendingStructures;
    }
    private static final Map<Key, Region> REGIONS = new HashMap<>();
    private static final ExecutorService WORKERS = Executors.newFixedThreadPool(2, r -> { Thread t = new Thread(r, "AnnoCraft distant view"); t.setDaemon(true); t.setPriority(Thread.MIN_PRIORITY); return t; });
    private static final Map<ResourceLocation, int[]> ROOFS = new HashMap<>();
    private static BufferBuilder builder;
    private static VertexBuffer ocean;
    private static IslandLayout built;
    private static boolean builtForShaders;
    private LodRenderer() { }

    /** Mesh resolution by distance from the eye: single blocks nearby, 16-block columns at the horizon. */
    static int step(double distance) { return distance < 300 ? 1 : distance < 700 ? 2 : distance < 1500 ? 4 : distance < 3000 ? 8 : 16; }
    /** How far the distant view reaches for a zoom level. */
    static double reach(float zoom, int size) { return Math.min(size * .8, Math.max(640, zoom * 7)); }

    // ---------------------------------------------------------------- shader packs

    private static Object irisApi;
    private static java.lang.reflect.Method shaderInUse;
    private static boolean irisChecked;
    /** Whether an Oculus/Iris shader pack is active, through its public API (no compile-time dependency). */
    public static boolean shaders() {
        if (!irisChecked) {
            irisChecked = true;
            try {
                Class<?> api = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
                irisApi = api.getMethod("getInstance").invoke(null); shaderInUse = api.getMethod("isShaderPackInUse");
            } catch (ReflectiveOperationException | LinkageError absent) { irisApi = null; }
        }
        if (irisApi == null) return false;
        try { return (Boolean) shaderInUse.invoke(irisApi); } catch (ReflectiveOperationException e) { return false; }
    }
    /**
     * With shaders the game's own projection draws the distant view, so its far plane (four times the render
     * distance) must reach the horizon of the management camera. The game renderer resets it at the start of every
     * frame; the field-of-view event comes after that and just before the projection is built.
     */
    @SubscribeEvent public static void farPlane(ViewportEvent.ComputeFov event) {
        if (!RtsController.active || ClientState.layout == null || !shaders()) return;
        Minecraft mc = Minecraft.getInstance();
        double eye = RtsController.zoom / Math.sin(Math.toRadians(CameraMath.pitch(RtsController.zoom, RtsController.tilt)));
        float needed = (float) ((eye + reach(RtsController.zoom, ClientState.layout.size())) / 4);
        if (mc.gameRenderer.renderDistance < needed) mc.gameRenderer.renderDistance = needed;
    }

    // ---------------------------------------------------------------- drawing

    /** With shaders: what the level pass prepared for the distant view, drawn once the shader's image is complete. */
    private static Matrix4f laterView, laterProjection;
    private static Vec3 laterCamera;
    private static List<Key> laterRegions;
    /** Copy of the scene's depth: its own framebuffer with a depth texture in the main one's format (shader packs change it). */
    private static int depthFbo = -1, depthTex = -1, depthW, depthH, depthFormat;
    private static boolean depthLogged;
    private static boolean sceneCaptured;

    @SubscribeEvent public static void render(RenderLevelStageEvent event) {
        if (!RtsController.active) return;
        boolean shaders = shaders();
        if (shaders && event.getStage() == RenderLevelStageEvent.Stage.AFTER_LEVEL) { captureDepth(); return; }
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_SKY) return;
        IslandLayout layout = ClientState.layout; if (layout == null) return;
        if (built != layout || builtForShaders != shaders) { clear(); built = layout; builtForShaders = shaders; }
        Minecraft mc = Minecraft.getInstance();
        Vec3 cam = event.getCamera().getPosition();
        double lookX = RtsController.x, lookZ = RtsController.z, reach = reach(RtsController.zoom, layout.size());
        Matrix4f projection = new Matrix4f(event.getProjectionMatrix());
        if (!shaders) {
            // The game's projection with a far plane that reaches the horizon and a near plane that keeps depth precision.
            float far = (float) (reach + Math.max(400, cam.y * 2)), near = 4;
            projection.m22((far + near) / (near - far)); projection.m32(2 * far * near / (near - far));
        }
        Matrix4f view = event.getPoseStack().last().pose();
        FrustumIntersection frustum = new FrustumIntersection(new Matrix4f(projection).mul(view));
        long revision = ClientState.revision();
        List<Key> wanted = new ArrayList<>();
        int r0 = Math.floorDiv((int) (lookX - reach), REGION), r1 = Math.floorDiv((int) (lookX + reach), REGION);
        int s0 = Math.floorDiv((int) (lookZ - reach), REGION), s1 = Math.floorDiv((int) (lookZ + reach), REGION);
        for (int rx = r0; rx <= r1; rx++) for (int rz = s0; rz <= s1; rz++) {
            double cx = rx * REGION + REGION / 2.0, cz = rz * REGION + REGION / 2.0;
            if (Math.hypot(cx - lookX, cz - lookZ) > reach + REGION) continue;
            if (!frustum.testAab((float) (rx * REGION - cam.x), (float) (40 - cam.y), (float) (rz * REGION - cam.z),
                    (float) (rx * REGION + REGION - cam.x), (float) (150 - cam.y), (float) (rz * REGION + REGION - cam.z))) continue;
            if (!land(layout, rx, rz)) continue;
            wanted.add(new Key(ClientState.world, rx, rz));
        }
        wanted.sort(Comparator.comparingDouble(k -> distance(k, cam)));
        int submitted = 0; long now = System.currentTimeMillis();
        for (Key k : wanted) {
            Region region = REGIONS.computeIfAbsent(k, key -> new Region());
            int step = step(distance(k, cam));
            collect(region);
            LodCache.Region seenRegion = LodCache.region(k.rx, k.rz);
            long seen = seenRegion == null ? Long.MIN_VALUE : seenRegion.version;
            long mask = shaders ? clearChunks(mc, k, cam) : 0;
            boolean stale = region.step != step || region.revision != revision || region.mask != mask
                    // Fresh samples are folded in at most every two seconds per region.
                    || (region.seen != seen && now - region.meshedAt > 2000);
            if (stale && region.pending == null && submitted < 6) {
                Map<Long, int[]> roofs = roofs(k);
                // A new colony revision only matters to regions with buildings, now or before.
                if (region.step == step && region.seen == seen && region.mask == mask && roofs.isEmpty() && !region.structures) { region.revision = revision; continue; }
                region.pendingStep = step; region.pendingRevision = revision; region.pendingStructures = !roofs.isEmpty(); region.pendingSeen = seen; region.pendingMask = mask;
                double haze = distance(k, cam);
                region.pending = WORKERS.submit(() -> mesh(layout, k.rx, k.rz, step, roofs, haze, mask));
                submitted++;
            }
        }
        if (ocean == null) ocean = upload(oceanMesh(layout));
        if (shaders) {
            laterView = new Matrix4f(view); laterProjection = projection; laterCamera = cam; laterRegions = wanted; sceneCaptured = false;
        } else {
            drawAll(view, projection, cam, wanted);
            RenderSystem.clear(GL11.GL_DEPTH_BUFFER_BIT, Minecraft.ON_OSX);
        }
        // Forget regions far behind the camera so memory stays bounded.
        if (REGIONS.size() > wanted.size() * 3 + 256) {
            REGIONS.entrySet().removeIf(e -> {
                boolean far2 = Math.hypot(e.getKey().rx * REGION - lookX, e.getKey().rz * REGION - lookZ) > reach * 1.6 || !e.getKey().world.equals(ClientState.world);
                if (far2) release(e.getValue());
                return far2;
            });
        }
    }
    private static double distance(Key k, Vec3 cam) {
        double dx = k.rx * REGION + REGION / 2.0 - cam.x, dz = k.rz * REGION + REGION / 2.0 - cam.z, dy = cam.y - 70;
        return Math.sqrt(dx * dx + dz * dz + dy * dy);
    }
    /**
     * Light of the moment for the distant view: the sun's direction and colour from the time of day (warm at
     * dawn and dusk, moonlight at night), the sky's colour for ambient light and haze, the haze's range from the zoom.
     */
    private static void light(ShaderInstance s, Vec3 cam) {
        Minecraft mc = Minecraft.getInstance(); float pt = mc.getFrameTime();
        double angle = mc.level.getTimeOfDay(pt) * Math.PI * 2;
        float sx = (float) -Math.sin(angle), sy = (float) Math.cos(angle);
        boolean day = sy > -.08f;
        float low = 1 - (float) smooth(0, .35, sy), bright = (float) smooth(-.08, .25, sy);
        float[] sun = day ? new float[]{.80f + .12f * low, .76f - .22f * low, .68f - .40f * low} : new float[]{.10f, .12f, .19f};
        for (int i = 0; i < 3; i++) sun[i] *= day ? .35f + .65f * bright : 1;
        s.safeGetUniform("SunDir").set(day ? sx : -sx, day ? Math.max(.05f, sy) : Math.max(.05f, -sy), .3f);
        s.safeGetUniform("SunColor").set(sun[0], sun[1], sun[2]);
        Vec3 sky = mc.level.getSkyColor(cam, pt);
        float k = .25f + .55f * bright;
        s.safeGetUniform("SkyLight").set((float) sky.x * k + .05f, (float) sky.y * k + .05f, (float) sky.z * k + .07f);
        float haze = .35f + .4f * bright;
        s.safeGetUniform("HazeColor").set((float) (sky.x * .6 + haze * .4), (float) (sky.y * .6 + haze * .42), (float) (sky.z * .6 + haze * .45));
        double eye = cam.y - CameraMath.GROUND, reach = reach(RtsController.zoom, ClientState.regionSize);
        s.safeGetUniform("HazeRange").set((float) (eye + 350), (float) (eye + reach * 1.3 + 800));
        boolean shaders = shaders();
        s.safeGetUniform("Grade").set(shaders ? 1f : 0f);
        s.safeGetUniform("Vanilla").set(shaders ? 0f : 1f);
        s.safeGetUniform("Daylight").set(mc.level.getSkyDarken(pt));
        if (!shaders) {
            // Vanilla look: the haze is the game's own horizon colour, and only begins past the real chunks, which have no fog here.
            float[] fog = RenderSystem.getShaderFogColor();
            s.safeGetUniform("HazeColor").set(fog[0], fog[1], fog[2]);
            double chunks = mc.options.getEffectiveRenderDistance() * 16;
            s.safeGetUniform("HazeRange").set((float) (eye + chunks + 200), (float) (eye + chunks + reach * 1.5 + 1500));
        }
    }
    private static double smooth(double a, double b, double x) { double t = Math.max(0, Math.min(1, (x - a) / (b - a))); return t * t * (3 - 2 * t); }
    private static void drawAll(Matrix4f view, Matrix4f projection, Vec3 cam, List<Key> wanted) {
        RenderSystem.enableDepthTest(); RenderSystem.disableBlend(); RenderSystem.disableCull();
        var shader = lodShader != null ? lodShader : GameRenderer.getPositionColorShader();
        if (lodShader != null) light(lodShader, cam);
        // The open sea first, without depth: shallows and land always lie over it.
        RenderSystem.depthMask(false);
        draw(ocean, view, projection, shader, -cam.x, -cam.y, -cam.z);
        RenderSystem.depthMask(true);
        for (Key k : wanted) {
            Region region = REGIONS.get(k);
            if (region != null && region.buffer != null && region.vertices > 0)
                draw(region.buffer, view, projection, shader, k.rx * REGION - cam.x, -cam.y, k.rz * REGION - cam.z);
        }
        VertexBuffer.unbind();
        RenderSystem.enableCull();
    }
    /**
     * Chunks the shader shows clearly, one bit each: loaded and nearer than the shader's fog. Their real blocks stand
     * in for the distant view; farther chunks, fogged away by the shader, are covered by it.
     */
    private static long clearChunks(Minecraft mc, Key k, Vec3 cam) {
        double fog = mc.options.getEffectiveRenderDistance() * 16;
        long mask = 0; int c0 = k.rx * (REGION / 16), d0 = k.rz * (REGION / 16);
        for (int i = 0; i < 8; i++) for (int j = 0; j < 8; j++) {
            double dx = (c0 + i) * 16 + 8 - cam.x, dz = (d0 + j) * 16 + 8 - cam.z, dy = cam.y - 70;
            if (mc.level.hasChunk(c0 + i, d0 + j) && dx * dx + dy * dy + dz * dz < fog * fog) mask |= 1L << (i * 8 + j);
        }
        return mask;
    }
    /** With shaders, keeps the scene's depth at the end of the level pass, before the shader's composite overwrites the picture. */
    private static void captureDepth() {
        var main = Minecraft.getInstance().getMainRenderTarget();
        prepareDepthCopy(main);
        blitDepth(main.frameBufferId, depthFbo, main.width, main.height);
        if (!depthLogged) { depthLogged = true; com.mojang.logging.LogUtils.getLogger().info("AnnoCraft distant view: scene depth copied in format 0x{} (GL error {})", Integer.toHexString(depthFormat), GL11.glGetError()); }
        sceneCaptured = true;
    }
    private static void prepareDepthCopy(com.mojang.blaze3d.pipeline.RenderTarget main) {
        int bound = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        com.mojang.blaze3d.platform.GlStateManager._bindTexture(main.getDepthTextureId());
        int format = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, org.lwjgl.opengl.GL30.GL_TEXTURE_INTERNAL_FORMAT);
        com.mojang.blaze3d.platform.GlStateManager._bindTexture(bound);
        if (depthFbo >= 0 && format == depthFormat && main.width == depthW && main.height == depthH) return;
        if (depthFbo >= 0) { com.mojang.blaze3d.platform.GlStateManager._glDeleteFramebuffers(depthFbo); com.mojang.blaze3d.platform.GlStateManager._deleteTexture(depthTex); }
        depthFormat = format; depthW = main.width; depthH = main.height;
        depthTex = com.mojang.blaze3d.platform.GlStateManager._genTexture();
        com.mojang.blaze3d.platform.GlStateManager._bindTexture(depthTex);
        com.mojang.blaze3d.platform.GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        com.mojang.blaze3d.platform.GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        boolean floating = format == org.lwjgl.opengl.GL30.GL_DEPTH_COMPONENT32F || format == org.lwjgl.opengl.GL30.GL_DEPTH32F_STENCIL8;
        boolean stencil = format == org.lwjgl.opengl.GL30.GL_DEPTH24_STENCIL8 || format == org.lwjgl.opengl.GL30.GL_DEPTH32F_STENCIL8;
        com.mojang.blaze3d.platform.GlStateManager._texImage2D(GL11.GL_TEXTURE_2D, 0, format, depthW, depthH, 0, stencil ? org.lwjgl.opengl.GL30.GL_DEPTH_STENCIL : GL11.GL_DEPTH_COMPONENT,
                stencil ? (floating ? org.lwjgl.opengl.GL30.GL_FLOAT_32_UNSIGNED_INT_24_8_REV : org.lwjgl.opengl.GL30.GL_UNSIGNED_INT_24_8) : (floating ? GL11.GL_FLOAT : GL11.GL_UNSIGNED_INT), null);
        com.mojang.blaze3d.platform.GlStateManager._bindTexture(bound);
        int previous = GL11.glGetInteger(org.lwjgl.opengl.GL30.GL_FRAMEBUFFER_BINDING);
        depthFbo = com.mojang.blaze3d.platform.GlStateManager.glGenFramebuffers();
        com.mojang.blaze3d.platform.GlStateManager._glBindFramebuffer(org.lwjgl.opengl.GL30.GL_FRAMEBUFFER, depthFbo);
        com.mojang.blaze3d.platform.GlStateManager._glFramebufferTexture2D(org.lwjgl.opengl.GL30.GL_FRAMEBUFFER, stencil ? org.lwjgl.opengl.GL30.GL_DEPTH_STENCIL_ATTACHMENT : org.lwjgl.opengl.GL30.GL_DEPTH_ATTACHMENT, GL11.GL_TEXTURE_2D, depthTex, 0);
        GL11.glDrawBuffer(GL11.GL_NONE); GL11.glReadBuffer(GL11.GL_NONE);
        com.mojang.blaze3d.platform.GlStateManager._glBindFramebuffer(org.lwjgl.opengl.GL30.GL_FRAMEBUFFER, previous);
    }
    private static void blitDepth(int from, int to, int w, int h) {
        int read = GL11.glGetInteger(org.lwjgl.opengl.GL30.GL_READ_FRAMEBUFFER_BINDING), drawn = GL11.glGetInteger(org.lwjgl.opengl.GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        com.mojang.blaze3d.platform.GlStateManager._glBindFramebuffer(org.lwjgl.opengl.GL30.GL_READ_FRAMEBUFFER, from);
        com.mojang.blaze3d.platform.GlStateManager._glBindFramebuffer(org.lwjgl.opengl.GL30.GL_DRAW_FRAMEBUFFER, to);
        org.lwjgl.opengl.GL30.glBlitFramebuffer(0, 0, w, h, 0, 0, w, h, GL11.GL_DEPTH_BUFFER_BIT, GL11.GL_NEAREST);
        com.mojang.blaze3d.platform.GlStateManager._glBindFramebuffer(org.lwjgl.opengl.GL30.GL_READ_FRAMEBUFFER, read);
        com.mojang.blaze3d.platform.GlStateManager._glBindFramebuffer(org.lwjgl.opengl.GL30.GL_DRAW_FRAMEBUFFER, drawn);
    }
    /**
     * With shaders, the distant view goes over the finished picture, before the interface: the shader's own fog stops
     * at the player's render distance, far short of the management camera. The scene's depth, put back first, keeps
     * the clear nearby buildings and relief in front of it; the distant view wins over what the shader fogged away.
     */
    @SubscribeEvent public static void afterShader(net.minecraftforge.client.event.ScreenEvent.Render.Pre event) {
        if (!RtsController.active || laterRegions == null || !sceneCaptured || ocean == null || depthFbo < 0) return;
        var main = Minecraft.getInstance().getMainRenderTarget();
        main.bindWrite(false);
        blitDepth(depthFbo, main.frameBufferId, main.width, main.height);
        main.bindWrite(false);
        RenderSystem.depthFunc(GL11.GL_LEQUAL);
        RenderSystem.enablePolygonOffset(); RenderSystem.polygonOffset(-2, -40);
        Matrix4f saved = RenderSystem.getProjectionMatrix();
        drawAll(laterView, laterProjection, laterCamera, laterRegions);
        RenderSystem.disablePolygonOffset();
        RenderSystem.setProjectionMatrix(saved, com.mojang.blaze3d.vertex.VertexSorting.ORTHOGRAPHIC_Z);
        RenderSystem.clear(GL11.GL_DEPTH_BUFFER_BIT, Minecraft.ON_OSX);
        laterRegions = null;
    }
    private static void draw(VertexBuffer buffer, Matrix4f view, Matrix4f projection, net.minecraft.client.renderer.ShaderInstance shader, double dx, double dy, double dz) {
        Matrix4f model = new Matrix4f(view).translate((float) dx, (float) dy, (float) dz);
        buffer.bind(); buffer.drawWithShader(model, projection, shader);
    }
    /** Whether a region touches any island; open sea is drawn by the single ocean quad. */
    private static boolean land(IslandLayout layout, int rx, int rz) {
        int x0 = rx * REGION, z0 = rz * REGION;
        for (IslandLayout.Island i : layout.islands())
            if (x0 <= i.x() + i.radiusX() * 1.25 && x0 + REGION >= i.x() - i.radiusX() * 1.25 && z0 <= i.z() + i.radiusZ() * 1.25 && z0 + REGION >= i.z() - i.radiusZ() * 1.25) return true;
        return false;
    }
    private static void collect(Region region) {
        if (region.pending == null || !region.pending.isDone()) return;
        try {
            Mesh mesh = region.pending.get();
            if (region.buffer != null) region.buffer.close();
            region.buffer = mesh.vertices == 0 ? null : upload(mesh); region.vertices = mesh.vertices;
            region.step = region.pendingStep; region.revision = region.pendingRevision; region.structures = region.pendingStructures;
            region.seen = region.pendingSeen; region.mask = region.pendingMask; region.meshedAt = System.currentTimeMillis();
        } catch (InterruptedException | ExecutionException | CancellationException e) {
            region.step = region.pendingStep; region.revision = region.pendingRevision; region.seen = region.pendingSeen; region.mask = region.pendingMask;
        }
        region.pending = null;
    }
    private static VertexBuffer upload(Mesh mesh) {
        if (builder == null) builder = new BufferBuilder(1 << 20);
        builder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR_NORMAL);
        for (int i = 0; i < mesh.vertices; i++) {
            int c = mesh.colors[i];
            builder.vertex(mesh.positions[i * 3], mesh.positions[i * 3 + 1], mesh.positions[i * 3 + 2]).color(c >> 16 & 255, c >> 8 & 255, c & 255, 255)
                    .normal(NORMALS[c >>> 24][0], NORMALS[c >>> 24][1], NORMALS[c >>> 24][2]).endVertex();
        }
        VertexBuffer buffer = new VertexBuffer(VertexBuffer.Usage.STATIC);
        buffer.bind(); buffer.upload(builder.end()); VertexBuffer.unbind();
        return buffer;
    }
    private static void release(Region region) {
        if (region.buffer != null) region.buffer.close();
        region.buffer = null;
        if (region.pending != null) region.pending.cancel(false);
    }
    public static void clear() {
        REGIONS.values().forEach(LodRenderer::release); REGIONS.clear();
        if (ocean != null) ocean.close();
        ocean = null; built = null; ROOFS.clear();
    }
    @SubscribeEvent public static void logout(ClientPlayerNetworkEvent.LoggingOut event) { clear(); }

    // ---------------------------------------------------------------- meshing

    /** Building roofs in a region: block column (x, z) → {top y, colour}, from the buildings' real blocks. */
    private static Map<Long, int[]> roofs(Key k) {
        Map<Long, int[]> result = new HashMap<>();
        int x0 = k.rx * REGION, z0 = k.rz * REGION;
        for (BuildingInstance b : ClientState.visible()) {
            if (b.origin().getX() > x0 + REGION || b.origin().getX() + b.width() < x0 || b.origin().getZ() > z0 + REGION || b.origin().getZ() + b.depth() < z0) continue;
            BuildingDefinition def = ClientState.DEFINITIONS.get(b.definition()); if (def == null) continue;
            int[] roof = ROOFS.computeIfAbsent(def.id(), id -> roof(def));
            for (int i = 0; i + 3 < roof.length; i += 4) {
                int[] q = Siting.turn(def, b.rotation(), roof[i], roof[i + 1]);
                int wx = b.origin().getX() + q[0], wz = b.origin().getZ() + q[1];
                if (wx < x0 || wx >= x0 + REGION || wz < z0 || wz >= z0 + REGION) continue;
                result.put(key(wx, wz), new int[]{b.origin().getY() + roof[i + 2], roof[i + 3]});
            }
        }
        return result;
    }
    /** Top block of every column of a building's template: {x, z, y, colour} quadruples. */
    private static int[] roof(BuildingDefinition def) {
        Map<Long, int[]> tops = new HashMap<>();
        for (ClientState.PreviewBlock block : ClientState.PREVIEWS.getOrDefault(def.id(), List.of())) {
            MapColor color = block.state().getMapColor(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
            if (color == MapColor.NONE) continue;
            BlockPos p = block.position();
            int[] top = tops.get(key(p.getX(), p.getZ()));
            if (top == null || p.getY() > top[2]) tops.put(key(p.getX(), p.getZ()), new int[]{p.getX(), p.getZ(), p.getY(), color.col});
        }
        int[] packed = new int[tops.size() * 4]; int i = 0;
        for (int[] t : tops.values()) { System.arraycopy(t, 0, packed, i, 4); i += 4; }
        return packed;
    }
    private static long key(int x, int z) { return (long) x << 32 | (z & 0xffffffffL); }

    /** Column height and colour of the natural island at a point, as the world generator builds it. */
    private static long natural(IslandLayout l, int x, int z) {
        int h = l.height(x, z);
        if (h < IslandLayout.SEA_LEVEL) return -1;
        boolean old = IslandLayout.OLD_WORLD.equals(l.world());
        int color = h <= 67 ? SAND : old ? GRASS_OLD : GRASS_NEW;
        if (h == 73) {
            int hill = l.hill(x, z);
            if (hill > 0) { h += hill; color = shade(ROCK, .9 + IslandLayout.hash(x, 1, z, 3) * .2); }
            else {
                IslandLayout.Deposit slot = null;
                for (IslandLayout.Deposit d : l.depositsNear(x, z, x, z, IslandLayout.SLOT))
                    if (Math.abs(d.x() - x) <= IslandLayout.SLOT && Math.abs(d.z() - z) <= IslandLayout.SLOT) slot = d;
                if (slot != null) color = slot.type().equals("clay") ? CLAY : slot.type().equals("quartz") ? QUARTZ : SLOT;
                else if (l.wild(x, z)) {
                    double f = l.forest(x, z);
                    // Woods read as a canopy a few blocks above the ground, like the crowns seen from far away.
                    if (f > .32) { h += 4 + (int) Math.round(f * 3); color = old ? mix(FOREST_OLD, FOREST_DARK, l.forest(x + 997, z - 331)) : JUNGLE; }
                }
            }
        }
        return (long) h << 32 | (color & 0xffffffL);
    }
    /**
     * One cell of {@code step} × {@code step} columns: the highest seen column and the colours of those near its
     * top, so roofs and crowns stay visible from afar; unseen cells come from the seed and the building roofs.
     * @return height << 32 | colour, -1 for sea, -2 for a cell hidden behind loaded chunks
     */
    private static long cell(IslandLayout l, int x0, int z0, int step, Map<Long, int[]> roofs, LodCache.Region[] seen, int rx, int rz) {
        int best = Integer.MIN_VALUE, water = 0, known = 0;
        long r = 0, g = 0, b = 0, n = 0;
        for (int pass = 0; pass < 2; pass++) for (int a = 0; a < step; a++) for (int c = 0; c < step; c++) {
            int x = x0 + a, z = z0 + c;
            LodCache.Region region = seen[(Math.floorDiv(x, REGION) - rx + 1) * 3 + (Math.floorDiv(z, REGION) - rz + 1)];
            if (region == null) continue;
            short h = LodCache.height(region, x, z);
            if (h == LodCache.UNKNOWN) continue;
            if (pass == 0) { known++; if (h == LodCache.WATER) water++; else best = Math.max(best, h); continue; }
            if (h != LodCache.WATER && h >= best - 1) {
                int col = LodCache.color(region, x, z);
                r += col >> 16 & 255; g += col >> 8 & 255; b += col & 255; n++;
            }
        }
        if (known * 2 > step * step || (known > 0 && step == 1)) {
            if (water * 2 >= known || n == 0) return -1;
            return (long) best << 32 | ((r / n) << 16 | (g / n) << 8 | (b / n));
        }
        int px = x0 + step / 2, pz = z0 + step / 2;
        long v = natural(l, px, pz);
        int h = v < 0 ? -1 : (int) (v >> 32), col = (int) (v & 0xffffff);
        if (!roofs.isEmpty())
            for (int a = 0; a < step; a++) for (int c = 0; c < step; c++) {
                int[] roof = roofs.get(key(x0 + a, z0 + c));
                if (roof != null && roof[0] >= h) { h = roof[0]; col = roof[1]; }
            }
        if (h < 0) return -1;
        return (long) h << 32 | (shade(col, .94 + IslandLayout.hash(px, 2, pz, 4) * .12) & 0xffffffL);
    }
    private static Mesh mesh(IslandLayout l, int rx, int rz, int step, Map<Long, int[]> roofs, double distance, long hidden) {
        int n = REGION / step, x0 = rx * REGION, z0 = rz * REGION;
        LodCache.Region[] seen = new LodCache.Region[9];
        for (int i = -1; i <= 1; i++) for (int j = -1; j <= 1; j++) seen[(i + 1) * 3 + j + 1] = LodCache.peek(rx + i, rz + j);
        int[] height = new int[(n + 2) * (n + 2)], color = new int[(n + 2) * (n + 2)];
        for (int i = -1; i <= n; i++) for (int j = -1; j <= n; j++) {
            long v = cell(l, x0 + i * step, z0 + j * step, step, roofs, seen, rx, rz);
            int index = (i + 1) * (n + 2) + (j + 1);
            height[index] = v < 0 ? -1 : (int) (v >> 32); color[index] = (int) (v & 0xffffff);
        }
        float[] positions = new float[Math.min(n * n * 5, 120_000) * 4 * 3]; int[] colors = new int[positions.length / 3]; int[] count = {0};
        for (int i = 0; i < n; i++) for (int j = 0; j < n; j++) {
            int wx = x0 + i * step, wz = z0 + j * step;
            // With shaders, loaded chunks show their real blocks; the distant view leaves a hole for them.
            if (hidden != 0 && (hidden >>> (((wx - x0) >> 4) * 8 + ((wz - z0) >> 4)) & 1) != 0) continue;
            if (count[0] + 24 > colors.length) { positions = Arrays.copyOf(positions, positions.length * 2); colors = Arrays.copyOf(colors, colors.length * 2); }
            int index = (i + 1) * (n + 2) + (j + 1), h = height[index];
            if (h < 0) {
                // Shallow water around the islands, lighter where the sea bed rises.
                int bed = l.height(wx + step / 2, wz + step / 2);
                if (bed <= 46) continue;
                int c = bed >= 58 ? mix(LAGOON, SHALLOW, (bed - 58) / 6.0) : mix(OCEAN, LAGOON, (bed - 46) / 12.0);
                float y = IslandLayout.SEA_LEVEL + .88f, xa = i * step, xb = xa + step, za = j * step, zb = za + step;
                quad(positions, colors, count, c, xa, y, za, xa, y, zb, xb, y, zb, xb, y, za);
                continue;
            }
            // Hollows between higher neighbours are a little darker, as with ambient occlusion.
            int higher = 0;
            for (int di = -1; di <= 1; di++) for (int dj = -1; dj <= 1; dj++) if ((di != 0 || dj != 0) && height[index + di * (n + 2) + dj] > h) higher++;
            int c = shade(color[index], 1 - higher * .035);
            float xa = i * step, xb = xa + step, za = j * step, zb = za + step, top = h + 1;
            quad(positions, colors, count, c, xa, top, za, xa, top, zb, xb, top, zb, xb, top, za);
            // Sides down to lower neighbours (or the sea), shaded like terrain lit from above.
            int west = height[index - (n + 2)], east = height[index + (n + 2)], north = height[index - 1], south = height[index + 1];
            float bw = Math.max(west, IslandLayout.SEA_LEVEL - 1) + 1, be = Math.max(east, IslandLayout.SEA_LEVEL - 1) + 1;
            float bn = Math.max(north, IslandLayout.SEA_LEVEL - 1) + 1, bs = Math.max(south, IslandLayout.SEA_LEVEL - 1) + 1;
            if (bw < top) quad(positions, colors, count, c | WEST, xa, bw, za, xa, top, za, xa, top, zb, xa, bw, zb);
            if (be < top) quad(positions, colors, count, c | EAST, xb, be, za, xb, be, zb, xb, top, zb, xb, top, za);
            if (bn < top) quad(positions, colors, count, c | NORTH, xa, bn, za, xb, bn, za, xb, top, za, xa, top, za);
            if (bs < top) quad(positions, colors, count, c | SOUTH, xa, bs, zb, xa, top, zb, xb, top, zb, xb, bs, zb);
        }
        return new Mesh(positions, colors, count[0]);
    }
    private static Mesh oceanMesh(IslandLayout l) {
        float half = l.size() / 2f + 3000, y = IslandLayout.SEA_LEVEL + .86f;
        float[] p = new float[12]; int[] c = new int[4]; int[] count = {0};
        quad(p, c, count, OCEAN, -half, y, -half, -half, y, half, half, y, half, half, y, -half);
        return new Mesh(p, c, 4);
    }
    private static void quad(float[] p, int[] c, int[] count, int color, float... v) {
        for (int k = 0; k < 4; k++) {
            int i = count[0]++;
            p[i * 3] = v[k * 3]; p[i * 3 + 1] = v[k * 3 + 1]; p[i * 3 + 2] = v[k * 3 + 2]; c[i] = color;
        }
    }
    static int shade(int rgb, double k) {
        return (int) Math.min(255, (rgb >> 16 & 255) * k) << 16 | (int) Math.min(255, (rgb >> 8 & 255) * k) << 8 | (int) Math.min(255, (rgb & 255) * k);
    }
    static int mix(int a, int b, double t) {
        t = Math.max(0, Math.min(1, t));
        return (int) ((a >> 16 & 255) * (1 - t) + (b >> 16 & 255) * t) << 16 | (int) ((a >> 8 & 255) * (1 - t) + (b >> 8 & 255) * t) << 8 | (int) ((a & 255) * (1 - t) + (b & 255) * t);
    }
}
