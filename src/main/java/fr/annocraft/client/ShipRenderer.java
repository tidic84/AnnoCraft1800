package fr.annocraft.client;

import com.mojang.blaze3d.vertex.PoseStack;
import fr.annocraft.economy.*;
import fr.annocraft.world.IslandLayout;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.phys.*;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import java.util.*;

/** Draws the colony's ships at their simulated positions: hull, mast and sail outlines on the sea. */
public final class ShipRenderer {
    private static Geography geography = Geography.of(List.of());
    private static Map<String, IslandLayout> source;
    private ShipRenderer() { }
    static Geography geography() {
        if (source == null || !source.equals(ClientState.LAYOUTS)) { source = new HashMap<>(ClientState.LAYOUTS); geography = Geography.of(ClientState.LAYOUTS.values()); }
        return geography;
    }
    /** World position of a ship in the current world, or null when it sails elsewhere. */
    static double[] position(CompoundTag s, int index) {
        Geography geo = geography(); String world = ClientState.world;
        if (!s.contains("to")) {
            String at = s.getString("at");
            if (!geo.exists(at) || !geo.world(at).equals(world)) return null;
            double[] h = geo.harbour(at, null); double angle = index * 0.9;
            return new double[]{h[0] + Math.cos(angle) * 14, h[1] + Math.sin(angle) * 14, 0, 1};
        }
        String from = s.getString("from"), to = s.getString("to"); double t = s.getDouble("progress");
        if (!geo.exists(from) || !geo.exists(to)) return null;
        boolean fromHere = geo.world(from).equals(world), toHere = geo.world(to).equals(world);
        if (fromHere && toHere) {
            double[] a = geo.harbour(from, to), b = geo.harbour(to, from);
            return new double[]{a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t, b[0] - a[0], b[1] - a[1]};
        }
        // Crossing the ocean between worlds: sail out of (or into) this world's region.
        String here = fromHere ? from : toHere ? to : null; if (here == null) return null;
        double[] h = geo.harbour(here, null); IslandLayout.Island i = geo.islands().get(here);
        double out = (fromHere ? t : 1 - t) * 600, dx = h[0] - i.x(), dz = h[1] - i.z(), len = Math.max(1, Math.hypot(dx, dz));
        return new double[]{h[0] + dx / len * out, h[1] + dz / len * out, dx, dz};
    }
    public static void render(RenderLevelStageEvent event) {
        List<CompoundTag> ships = ColonyScreen.ships(); if (ships.isEmpty()) return;
        PoseStack pose = event.getPoseStack(); Vec3 camera = event.getCamera().getPosition();
        pose.pushPose(); pose.translate(-camera.x, -camera.y, -camera.z);
        var buffers = Minecraft.getInstance().renderBuffers().bufferSource(); var lines = buffers.getBuffer(RenderType.lines());
        int index = 0;
        for (CompoundTag s : ships) {
            double[] p = position(s, index++); if (p == null) continue;
            if (Math.hypot(p[0] - camera.x, p[1] - camera.z) > 400) continue;
            Maritime.ShipType type = Maritime.type(s.getString("type"));
            boolean military = type != null && type.military();
            int length = type == null ? 7 : 6 + type.slots() * 2;
            boolean alongX = Math.abs(p[2]) >= Math.abs(p[3]);
            double lx = alongX ? length / 2.0 : 1.5, lz = alongX ? 1.5 : length / 2.0, sea = IslandLayout.SEA_LEVEL + 1;
            float r = military ? .85f : .55f, g = military ? .2f : .35f, b = military ? .2f : .15f;
            LevelRenderer.renderLineBox(pose, lines, new AABB(p[0] - lx, sea - .4, p[1] - lz, p[0] + lx, sea + 1, p[1] + lz), r, g, b, 1);
            LevelRenderer.renderLineBox(pose, lines, new AABB(p[0] - .15, sea + 1, p[1] - .15, p[0] + .15, sea + 8, p[1] + .15), .4f, .3f, .2f, 1);
            double sx = alongX ? .1 : 2, sz = alongX ? 2 : .1;
            LevelRenderer.renderLineBox(pose, lines, new AABB(p[0] - sx, sea + 3, p[1] - sz, p[0] + sx, sea + 7, p[1] + sz), .95f, .95f, .9f, 1);
        }
        buffers.endBatch(RenderType.lines()); pose.popPose();
    }
}
