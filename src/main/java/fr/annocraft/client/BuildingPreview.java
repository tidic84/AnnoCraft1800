package fr.annocraft.client;

import com.mojang.blaze3d.vertex.*;
import fr.annocraft.building.BuildingDefinition;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.*;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Rotation;
import java.util.*;

/** Real-block preview of a building being placed: a translucent ghost in the world. */
public final class BuildingPreview {
    private BuildingPreview() { }

    /** Translucent tinted ghost of the template at its future position and rotation. */
    public static void ghost(PoseStack pose, BuildingDefinition def, BlockPos origin, int rotation, boolean valid, double time) {
        var mc = Minecraft.getInstance(); var blocksRenderer = mc.getBlockRenderer(); var buffers = mc.renderBuffers().bufferSource();
        float alpha = (float) (.62 + .1 * Math.sin(time * 4));
        float r = valid ? .6f : 1f, gr = valid ? 1f : .45f, b = valid ? .7f : .4f;
        MultiBufferSource tinted = type -> new Tinted(buffers.getBuffer(RenderType.translucent()), r, gr, b, alpha);
        Rotation turn = Rotation.values()[rotation & 3];
        for (ClientState.PreviewBlock block : ClientState.PREVIEWS.getOrDefault(def.id(), List.of())) {
            BlockPos local = block.position();
            BlockPos q = switch (rotation) {
                case 1 -> new BlockPos(def.depth() - 1 - local.getZ(), local.getY(), local.getX());
                case 2 -> new BlockPos(def.width() - 1 - local.getX(), local.getY(), def.depth() - 1 - local.getZ());
                case 3 -> new BlockPos(local.getZ(), local.getY(), def.width() - 1 - local.getX());
                default -> local;
            };
            pose.pushPose(); pose.translate(origin.getX() + q.getX(), origin.getY() + q.getY(), origin.getZ() + q.getZ());
            // Slightly shrunk so the ghost never z-fights with terrain.
            pose.translate(.01, .01, .01); pose.scale(.98f, .98f, .98f);
            blocksRenderer.renderSingleBlock(block.state().rotate(turn), pose, tinted, LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY);
            pose.popPose();
        }
        buffers.endBatch(RenderType.translucent());
    }

    /** Recolours and fades every vertex written through it. */
    private record Tinted(VertexConsumer delegate, float r, float g, float b, float a) implements VertexConsumer {
        @Override public VertexConsumer vertex(double x, double y, double z) { delegate.vertex(x, y, z); return this; }
        @Override public VertexConsumer color(int red, int green, int blue, int alpha) {
            delegate.color((int) (red * r), (int) (green * g), (int) (blue * b), (int) (255 * a)); return this;
        }
        @Override public VertexConsumer uv(float u, float v) { delegate.uv(u, v); return this; }
        @Override public VertexConsumer overlayCoords(int u, int v) { delegate.overlayCoords(u, v); return this; }
        @Override public VertexConsumer uv2(int u, int v) { delegate.uv2(u, v); return this; }
        @Override public VertexConsumer normal(float x, float y, float z) { delegate.normal(x, y, z); return this; }
        @Override public void endVertex() { delegate.endVertex(); }
        @Override public void defaultColor(int red, int green, int blue, int alpha) { delegate.defaultColor(red, green, blue, alpha); }
        @Override public void unsetDefaultColor() { delegate.unsetDefaultColor(); }
    }
}
