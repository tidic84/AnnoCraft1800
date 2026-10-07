package fr.annocraft.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import fr.annocraft.economy.*;
import fr.annocraft.world.IslandLayout;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.*;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import java.util.*;

/**
 * Draws the colony's ships as small block models at their simulated positions. Ships are not entities:
 * the server simulates voyages abstractly and clients extrapolate between economy updates.
 */
public final class ShipRenderer {
    private static Geography geography = Geography.of(List.of());
    private static Map<String, IslandLayout> source;
    private record Part(int x, int y, int z, BlockState state) { }
    private static final Map<String, List<Part>> MODELS = new HashMap<>();
    private ShipRenderer() { }
    static Geography geography() {
        if (source == null || !source.equals(ClientState.LAYOUTS)) { source = new HashMap<>(ClientState.LAYOUTS); geography = Geography.of(ClientState.LAYOUTS.values()); }
        return geography;
    }
    /** Hull along +X, centred on the origin: deck, gunwales, masts with sails, guns or a funnel by type. */
    private static List<Part> model(Maritime.ShipType type) {
        return MODELS.computeIfAbsent(type.id(), id -> {
            List<Part> parts = new ArrayList<>();
            int length = 6 + type.slots() * 2, half = length / 2;
            boolean steam = id.startsWith("steam") || id.equals("battleship"), military = type.military();
            BlockState hull = (steam ? Blocks.POLISHED_DEEPSLATE : Blocks.DARK_OAK_PLANKS).defaultBlockState();
            BlockState rail = (steam ? Blocks.DEEPSLATE_TILES : Blocks.SPRUCE_PLANKS).defaultBlockState();
            for (int x = -half; x < length - half; x++) {
                boolean bow = x == length - half - 1, stern = x == -half;
                for (int z = -1; z <= 1; z++) {
                    if (bow && z != 0) continue;
                    parts.add(new Part(x, 0, z, hull));
                    if (z != 0 || stern) parts.add(new Part(x, 1, z, rail));
                }
            }
            if (steam) {
                for (int y = 1; y <= 4; y++) parts.add(new Part(-1, y, 0, Blocks.BRICKS.defaultBlockState()));
                parts.add(new Part(-1, 5, 0, Blocks.CAMPFIRE.defaultBlockState()));
            } else {
                int masts = Math.max(1, type.slots() / 2);
                for (int m = 0; m < masts; m++) {
                    int mx = masts == 1 ? 0 : -half / 2 + m * half;
                    for (int y = 1; y <= 7; y++) parts.add(new Part(mx, y, 0, Blocks.SPRUCE_FENCE.defaultBlockState()));
                    BlockState sail = (military ? Blocks.RED_WOOL : Blocks.WHITE_WOOL).defaultBlockState();
                    for (int y = 3; y <= 6; y++) for (int z = -2; z <= 2; z++) if (Math.abs(z) < 2 || (y > 3 && y < 6)) parts.add(new Part(mx + 1, y, z, sail));
                }
            }
            if (military) for (int x = -half + 1; x < length - half - 1; x += 2) for (int z : new int[]{-2, 2})
                parts.add(new Part(x, 1, z, Blocks.POLISHED_BLACKSTONE.defaultBlockState()));
            return List.copyOf(parts);
        });
    }
    /** World position and heading of a ship in the current world, or null when it sails elsewhere. */
    static double[] position(CompoundTag s, int index) {
        Geography geo = geography(); String world = ClientState.world;
        if (!s.contains("to")) {
            String at = s.getString("at");
            if (!geo.exists(at) || !geo.world(at).equals(world)) return null;
            double[] h = geo.harbour(at, null); double angle = index * 0.9;
            return new double[]{h[0] + Math.cos(angle) * 14, h[1] + Math.sin(angle) * 14, -Math.sin(angle), Math.cos(angle)};
        }
        String from = s.getString("from"), to = s.getString("to");
        double duration = Math.max(1, s.getDouble("duration"));
        double t = Math.min(1, s.getDouble("progress") + (System.nanoTime() - ClientState.economyTime) / 1e9 / duration);
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
        return new double[]{h[0] + dx / len * out, h[1] + dz / len * out, fromHere ? dx : -dx, fromHere ? dz : -dz};
    }
    public static void render(RenderLevelStageEvent event) {
        List<CompoundTag> ships = ColonyScreen.ships(); Minecraft mc = Minecraft.getInstance();
        if (ships.isEmpty() || mc.level == null) return;
        PoseStack pose = event.getPoseStack(); Vec3 camera = event.getCamera().getPosition();
        var buffers = mc.renderBuffers().bufferSource(); var blocks = mc.getBlockRenderer();
        double time = (System.nanoTime() % 1_000_000_000_000L) / 1e9;
        int index = 0;
        for (CompoundTag s : ships) {
            double[] p = position(s, index++); if (p == null) continue;
            if (Math.hypot(p[0] - camera.x, p[1] - camera.z) > 320) continue;
            Maritime.ShipType type = Maritime.type(s.getString("type")); if (type == null) continue;
            double sea = IslandLayout.SEA_LEVEL + .55 + Math.sin(time * 1.3 + index) * .08;
            int light = LevelRenderer.getLightColor(mc.level, BlockPos.containing(p[0], sea + 2, p[1]));
            pose.pushPose();
            pose.translate(p[0] - camera.x, sea - camera.y, p[1] - camera.z);
            pose.mulPose(Axis.YP.rotation((float) -Math.atan2(p[3], p[2])));
            pose.mulPose(Axis.XP.rotationDegrees((float) Math.sin(time * 1.1 + index) * 2));
            for (Part part : model(type)) {
                pose.pushPose(); pose.translate(part.x() - .5, part.y(), part.z() - .5);
                blocks.renderSingleBlock(part.state(), pose, buffers, light, OverlayTexture.NO_OVERLAY);
                pose.popPose();
            }
            pose.popPose();
        }
        buffers.endBatch();
    }
}
