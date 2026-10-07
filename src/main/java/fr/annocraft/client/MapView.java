package fr.annocraft.client;

import com.mojang.blaze3d.platform.NativeImage;
import fr.annocraft.AnnoCraft;
import fr.annocraft.building.BuildingInstance;
import fr.annocraft.economy.*;
import fr.annocraft.world.IslandLayout;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.nbt.*;
import net.minecraft.resources.ResourceLocation;
import java.util.*;

/**
 * Cartography of both archipelagos, computed from the seed-based island layout instead of loaded chunks:
 * works on every computer, at any distance, without a level-of-detail mod.
 */
public final class MapView {
    public static final int SIZE = 256;
    private static final Map<String, ResourceLocation> TEXTURES = new HashMap<>();
    private static final Map<String, IslandLayout> BUILT = new HashMap<>();
    private MapView() { }
    static int ownerColor(String owner) {
        if (owner == null || owner.isEmpty()) return 0xffd8d0b0;
        return switch (owner) { case Diplomacy.PLAYER -> 0xff5fa8ff; case "ashby" -> 0xffc070e0; case "dravek" -> 0xffe05050; case "corsairs" -> 0xff303030; default -> 0xffffffff; };
    }
    /** Map texture of a world, regenerated only when its layout changes. */
    static ResourceLocation texture(String world) {
        IslandLayout layout = ClientState.LAYOUTS.get(world);
        if (layout == null) return null;
        if (BUILT.get(world) == layout) return TEXTURES.get(world);
        NativeImage image = new NativeImage(SIZE, SIZE, false);
        boolean neu = IslandLayout.NEW_WORLD.equals(world);
        for (int px = 0; px < SIZE; px++) for (int pz = 0; pz < SIZE; pz++) {
            int x = (int) ((px + .5) / SIZE * layout.size() - layout.size() / 2.0), z = (int) ((pz + .5) / SIZE * layout.size() - layout.size() / 2.0);
            int h = layout.height(x, z);
            int argb;
            if (h <= 40) argb = 0xff1b3a5e;
            else if (h < IslandLayout.SEA_LEVEL) argb = blend(0xff1b3a5e, 0xff3f88b0, (h - 40) / 24.0);
            else if (h <= 67) argb = neu ? 0xffe2cf98 : 0xffd8c690;
            else if (h < 73) argb = 0xff8da65a;
            else argb = neu ? 0xff3f8f3a : 0xff6e9e4a;
            image.setPixelRGBA(px, pz, abgr(argb));
        }
        ResourceLocation id = TEXTURES.get(world);
        DynamicTexture texture = new DynamicTexture(image);
        if (id == null) id = AnnoCraft.id("map_" + world);
        Minecraft.getInstance().getTextureManager().register(id, texture);
        TEXTURES.put(world, id); BUILT.put(world, layout);
        return id;
    }
    private static int abgr(int argb) { return argb & 0xff00ff00 | (argb >> 16 & 0xff) | (argb & 0xff) << 16; }
    private static int blend(int a, int b, double t) {
        int r = (int) ((a >> 16 & 255) * (1 - t) + (b >> 16 & 255) * t), g = (int) ((a >> 8 & 255) * (1 - t) + (b >> 8 & 255) * t), bl = (int) ((a & 255) * (1 - t) + (b & 255) * t);
        return 0xff000000 | r << 16 | g << 8 | bl;
    }
    /** Map pixel of a world position, for a map drawn at (x, y) with side {@code side}. */
    static double[] toMap(IslandLayout layout, double wx, double wz, int x, int y, int side) {
        return new double[]{x + (wx + layout.size() / 2.0) / layout.size() * side, y + (wz + layout.size() / 2.0) / layout.size() * side};
    }
    static double[] toWorld(IslandLayout layout, double mx, double my, int x, int y, int side) {
        return new double[]{(mx - x) / side * layout.size() - layout.size() / 2.0, (my - y) / side * layout.size() - layout.size() / 2.0};
    }
    /** Draws the map with ownership rings, buildings, ships and optionally routes and the camera frame. */
    static void draw(GuiGraphics g, String world, int x, int y, int side, boolean details) {
        IslandLayout layout = ClientState.LAYOUTS.get(world); ResourceLocation tex = texture(world);
        if (layout == null || tex == null) return;
        g.blit(tex, x, y, side, side, 0, 0, SIZE, SIZE, SIZE, SIZE);
        for (IslandLayout.Island i : layout.islands()) {
            String owner = ClientState.owner(i.id());
            double[] c = toMap(layout, i.x(), i.z(), x, y, side);
            int r = Math.max(2, (int) (Math.max(i.radiusX(), i.radiusZ()) * .9 / layout.size() * side));
            if (!owner.isEmpty()) ring(g, c[0], c[1], r, ownerColor(owner), details ? 2 : 1);
        }
        for (BuildingInstance b : ClientState.BUILDINGS.values()) {
            if (!ColonyEconomy.worldOf(b.island()).equals(world)) continue;
            double[] p = toMap(layout, b.origin().getX() + b.width() / 2.0, b.origin().getZ() + b.depth() / 2.0, x, y, side);
            g.fill((int) p[0], (int) p[1], (int) p[0] + 1, (int) p[1] + 1, 0xffffffff);
        }
        Geography geo = ShipRenderer.geography(); int index = 0;
        String saved = ClientState.world; ClientState.world = world;
        for (CompoundTag s : ColonyScreen.ships()) {
            double[] w = ShipRenderer.position(s, index++);
            if (details && s.getString("order").equals("route")) for (Tag t : s.getList("route", Tag.TAG_COMPOUND)) {
                String island = ((CompoundTag) t).getString("island");
                if (!geo.exists(island) || !geo.world(island).equals(world) || w == null) continue;
                double[] h = geo.harbour(island, null), a = toMap(layout, w[0], w[1], x, y, side), b = toMap(layout, h[0], h[1], x, y, side);
                dotted(g, a[0], a[1], b[0], b[1], 0x90e7cf8a);
            }
            if (w == null) continue;
            double[] p = toMap(layout, w[0], w[1], x, y, side);
            Maritime.ShipType type = Maritime.type(s.getString("type"));
            int color = type != null && type.military() ? 0xffff6050 : 0xfffff2c0;
            g.fill((int) p[0] - 1, (int) p[1] - 1, (int) p[0] + 2, (int) p[1] + 2, color);
        }
        ClientState.world = saved;
        if (RtsController.active && world.equals(ClientState.world)) {
            double[] cam = toMap(layout, RtsController.x, RtsController.z, x, y, side);
            int half = Math.max(2, (int) (RtsController.zoom * 1.6 / layout.size() * side));
            frame(g, (int) cam[0] - half, (int) cam[1] - half, half * 2, half * 2, 0xffffffff);
        }
    }
    static void frame(GuiGraphics g, int x, int y, int w, int h, int color) {
        g.fill(x, y, x + w, y + 1, color); g.fill(x, y + h - 1, x + w, y + h, color);
        g.fill(x, y, x + 1, y + h, color); g.fill(x + w - 1, y, x + w, y + h, color);
    }
    static void ring(GuiGraphics g, double cx, double cy, int r, int color, int thickness) {
        int steps = Math.max(24, r * 6);
        for (int i = 0; i < steps; i++) {
            double a = Math.PI * 2 * i / steps; int px = (int) (cx + Math.cos(a) * r), py = (int) (cy + Math.sin(a) * r);
            g.fill(px, py, px + thickness, py + thickness, color);
        }
    }
    static void dotted(GuiGraphics g, double x0, double y0, double x1, double y1, int color) {
        double length = Math.hypot(x1 - x0, y1 - y0); int steps = (int) (length / 3);
        for (int i = 0; i <= steps; i++) { double t = steps == 0 ? 0 : (double) i / steps; int px = (int) (x0 + (x1 - x0) * t), py = (int) (y0 + (y1 - y0) * t); g.fill(px, py, px + 1, py + 1, color); }
    }
}
