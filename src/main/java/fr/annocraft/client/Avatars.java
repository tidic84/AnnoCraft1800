package fr.annocraft.client;

import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.math.Axis;
import fr.annocraft.AnnoCraft;
import fr.annocraft.economy.Company;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import java.util.*;

/**
 * Portraits as in Anno: each player and character is a living bust in a framed portrait, breathing, glancing
 * around and nodding while it speaks. Players pick a painted character or their own Minecraft skin; rivals and the
 * campaign's characters have their own. Also the companies' flags, painted into small textures.
 */
public final class Avatars {
    private static PlayerModel<LivingEntity> model;
    private Avatars() { }

    /** Texture of a portrait: a painted character, or a player's own skin. */
    public static ResourceLocation texture(String avatar, UUID player) {
        if ("skin".equals(avatar)) {
            var connection = Minecraft.getInstance().getConnection();
            var info = connection == null || player == null ? null : connection.getPlayerInfo(player);
            return info != null ? info.getSkinLocation() : DefaultPlayerSkin.getDefaultSkin(player == null ? new UUID(0, 0) : player);
        }
        return AnnoCraft.id("textures/entity/avatar/" + avatar + ".png");
    }
    /** The portrait of a rival or campaign character. */
    public static String character(String id) { return "corsairs".equals(id) ? "rook" : id; }

    /**
     * An animated bust framed in a portrait of the given colour. {@code speaking} makes it nod and move its lips'
     * shadow, {@code seed} desynchronises portraits side by side.
     */
    public static void portrait(GuiGraphics g, String avatar, UUID player, int x, int y, int w, int h, int color, boolean speaking, float seed) {
        // Backdrop: the company's colour, darker at the bottom, with a soft halo behind the head.
        int top = 0xff000000 | LodRenderer.mix(color, 0xffffff, .25), bottom = 0xff000000 | LodRenderer.shade(color, .45);
        g.fillGradient(x, y, x + w, y + h, top, bottom);
        g.fill(x + w / 4, y + h / 6, x + w * 3 / 4, y + h * 2 / 3, 0x18ffffff);
        Minecraft mc = Minecraft.getInstance();
        if (model == null) {
            model = new PlayerModel<>(mc.getEntityModels().bakeLayer(ModelLayers.PLAYER), false);
            model.leftLeg.visible = model.rightLeg.visible = model.leftPants.visible = model.rightPants.visible = false;
        }
        float t = (System.nanoTime() % 3_600_000_000_000L) / 1e9f + seed * 7.3f;
        model.young = false; model.crouching = false; model.riding = false;
        // Idle life: the head turns and tilts slowly, the shoulders rise with the breath.
        model.head.yRot = (float) (Math.sin(t * .45) * .32 + Math.sin(t * 1.3) * .05);
        model.head.xRot = (float) (-.08 + Math.sin(t * .7) * .06 + (speaking ? Math.sin(t * 9) * .05 : 0));
        model.head.zRot = (float) (Math.sin(t * .33) * .04);
        model.body.yRot = (float) (model.head.yRot * .25);
        float breath = (float) Math.sin(t * 1.6) * .015f;
        model.rightArm.xRot = (float) (-.05 + Math.sin(t * .8) * .04); model.rightArm.zRot = .07f + breath;
        model.leftArm.xRot = (float) (-.05 - Math.sin(t * .8) * .04); model.leftArm.zRot = -.07f - breath;
        model.rightArm.yRot = model.leftArm.yRot = model.body.yRot;
        model.hat.copyFrom(model.head); model.jacket.copyFrom(model.body);
        model.rightSleeve.copyFrom(model.rightArm); model.leftSleeve.copyFrom(model.leftArm);
        // The clip follows the screen's own scaling, if any.
        org.joml.Vector3f corner = g.pose().last().pose().transformPosition(new org.joml.Vector3f(x + 1, y + 1, 0)), far = g.pose().last().pose().transformPosition(new org.joml.Vector3f(x + w - 1, y + h - 1, 0));
        g.enableScissor((int) corner.x(), (int) corner.y(), (int) Math.ceil(far.x()), (int) Math.ceil(far.y()));
        var pose = g.pose();
        pose.pushPose();
        // Head and shoulders fill the frame: the model's neck sits a little below its middle.
        float scale = h / 1.1f;
        pose.translate(x + w / 2f, y + h * .58f + breath * scale * .3f, 150);
        pose.scale(scale, scale, -scale);
        Lighting.setupForEntityInInventory();
        var buffers = mc.renderBuffers().bufferSource();
        model.renderToBuffer(pose, buffers.getBuffer(RenderType.entityTranslucent(texture(avatar, player))), 0xf000f0, OverlayTexture.NO_OVERLAY, 1, 1, 1, 1);
        buffers.endBatch();
        Lighting.setupFor3DItems();
        pose.popPose();
        g.disableScissor();
        MapView.frame(g, x, y, w, h, UiKit.GOLD);
        g.fill(x + 1, y + 1, x + w - 1, y + 2, 0x40ffffff);
    }

    // ---------------------------------------------------------------- flags

    private record Painted(DynamicTexture texture, ResourceLocation location, int hash) { }
    private static final Map<String, Painted> FLAGS = new HashMap<>();
    /** The texture of a company's flag, repainted when the flag changes. */
    public static ResourceLocation flag(Company c) {
        int hash = Arrays.hashCode(c.flag());
        Painted p = FLAGS.get(c.id());
        if (p == null) {
            DynamicTexture texture = new DynamicTexture(new NativeImage(Company.FLAG_W, Company.FLAG_H, false));
            ResourceLocation location = AnnoCraft.id("flag/" + c.id().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_.-]", "_"));
            Minecraft.getInstance().getTextureManager().register(location, texture);
            p = new Painted(texture, location, hash + 1);
            FLAGS.put(c.id(), p);
        }
        if (p.hash() != hash) {
            NativeImage pixels = p.texture().getPixels();
            for (int x = 0; x < Company.FLAG_W; x++) for (int y = 0; y < Company.FLAG_H; y++) {
                int rgb = c.rgb(x, y);
                pixels.setPixelRGBA(x, y, 0xff000000 | (rgb & 255) << 16 | (rgb >> 8 & 255) << 8 | rgb >> 16 & 255);
            }
            p.texture().upload();
            p = new Painted(p.texture(), p.location(), hash); FLAGS.put(c.id(), p);
        }
        return p.location();
    }
    /** A company's flag, framed. */
    public static void drawFlag(GuiGraphics g, Company c, int x, int y, int w, int h) {
        if (c == null) return;
        g.blit(flag(c), x, y, w, h, 0, 0, Company.FLAG_W, Company.FLAG_H, Company.FLAG_W, Company.FLAG_H);
        MapView.frame(g, x - 1, y - 1, w + 2, h + 2, 0xff2a2620);
    }
}
