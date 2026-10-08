package fr.annocraft.client;

import com.mojang.blaze3d.systems.RenderSystem;
import fr.annocraft.AnnoCraft;
import fr.annocraft.building.BuildingDefinition;
import fr.annocraft.economy.EconomyProfile;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.*;
import java.util.*;
import java.util.function.Supplier;

/** Shared visual language of the management interface, after Anno 1800: warm charcoal panels with bronze edges, parchment text, painted icons. */
public final class UiKit {
    public static final int PANEL = 0xec1f1c17, PANEL_LIGHT = 0xf02a2620, WELL = 0xc0141210, EDGE = 0xff4b4232, SELECTED = 0xf04a3f2a, HOVER = 0xf0393226,
            GOLD = 0xffc8a868, GOLD_TEXT = 0xfff0d898, TEXT = 0xffece0bc, MUTED = 0xffa79a7c, GOOD = 0xff9ad48a, BAD = 0xffff8b6e, WARN = 0xffffc070;
    private UiKit() { }

    /** Dark panel with a thin bronze rim and a lighter top edge. */
    public static void panel(GuiGraphics g, int x, int y, int w, int h) {
        g.fill(x, y, x + w, y + h, PANEL);
        g.fill(x, y, x + w, y + 1, 0xff6e5f44); g.fill(x, y + h - 1, x + w, y + h, 0xff2c271e);
        g.fill(x, y, x + 1, y + h, EDGE); g.fill(x + w - 1, y, x + w, y + h, EDGE);
        g.fill(x + 1, y + 1, x + w - 1, y + 2, 0x30ffffff);
    }
    /** Name plate, like the island banner under Anno's resource bar: a lighter slab framed in gold. */
    public static void plate(GuiGraphics g, int x, int y, int w, int h) {
        g.fill(x, y, x + w, y + h, 0xf2332e25);
        g.fill(x, y, x + w, y + 1, GOLD); g.fill(x, y + h - 1, x + w, y + h, 0xff8a7448);
        g.fill(x, y, x + 1, y + h, 0xff8a7448); g.fill(x + w - 1, y, x + w, y + h, 0xff8a7448);
        g.fill(x + 3, y + 2, x + 4, y + h - 2, 0x60c8a868); g.fill(x + w - 4, y + 2, x + w - 3, y + h - 2, 0x60c8a868);
    }
    public static void bar(GuiGraphics g, int x, int y, int w, int h, double fraction, int color) {
        g.fill(x, y, x + w, y + h, 0xff12100c);
        g.fill(x, y, x + (int) Math.round(w * Math.max(0, Math.min(1, fraction))), y + h, color);
    }
    public static void text(GuiGraphics g, Component c, int x, int y, int color, int maxWidth) {
        Font font = Minecraft.getInstance().font;
        g.drawString(font, font.plainSubstrByWidth(c.getString(), maxWidth), x, y, color, false);
    }
    public static void centered(GuiGraphics g, Component c, int cx, int y, int color) {
        Font font = Minecraft.getInstance().font;
        g.drawString(font, c, cx - font.width(c) / 2, y, color, false);
    }
    /**
     * Draws the painted icon of a good, tier, public building or tool (textures/gui/icons, made by
     * tools/GenerateIcons.java) scaled into a square of {@code size} pixels, smoothly filtered.
     */
    public static void icon(GuiGraphics g, String id, int x, int y, int size) {
        ResourceLocation texture = TEXTURES.computeIfAbsent(id, key -> {
            ResourceLocation loc = AnnoCraft.id("textures/gui/icons/" + key.replace(':', '/') + ".png");
            if (Minecraft.getInstance().getResourceManager().getResource(loc).isEmpty()) return MISSING;
            Minecraft.getInstance().getTextureManager().getTexture(loc).setFilter(true, false);
            return loc;
        });
        if (texture == MISSING) {
            g.pose().pushPose(); g.pose().translate(x, y, 0); g.pose().scale(size / 16f, size / 16f, 1);
            g.renderItem(new ItemStack(Items.PAPER), 0, 0); g.pose().popPose();
            return;
        }
        RenderSystem.enableBlend(); RenderSystem.defaultBlendFunc();
        g.blit(texture, x, y, size, size, 0, 0, 64, 64, 64, 64);
        RenderSystem.disableBlend();
    }
    private static final ResourceLocation MISSING = AnnoCraft.id("missing");
    private static final Map<String, ResourceLocation> TEXTURES = new HashMap<>();

    /** What a building stands for in the construction menu, as in Anno: its product, its residents or its own emblem. */
    public static String icon(BuildingDefinition def) {
        EconomyProfile e = def.economy();
        if (!e.outputs().isEmpty()) return e.outputs().keySet().iterator().next();
        if (e.housing()) return "tier:" + e.houseTier();
        return "building:" + def.id().getPath();
    }

    /** Flat gilded button with an optional painted icon, in the management-screen style. */
    public static final class AnnoButton extends AbstractButton {
        private final Runnable action;
        private final String icon;
        private Supplier<Boolean> highlighted = () -> false;
        public AnnoButton(int x, int y, int w, int h, Component label, String icon, Runnable action) {
            super(x, y, w, h, label); this.action = action; this.icon = icon;
        }
        public AnnoButton highlight(Supplier<Boolean> when) { highlighted = when; return this; }
        @Override public void onPress() { action.run(); }
        @Override protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
            boolean hover = isHoveredOrFocused() && active, on = highlighted.get();
            g.fill(getX(), getY(), getX() + width, getY() + height, !active ? 0xc01a1814 : on ? SELECTED : hover ? HOVER : PANEL_LIGHT);
            int edge = on || hover ? GOLD : EDGE;
            g.fill(getX(), getY(), getX() + width, getY() + 1, edge); g.fill(getX(), getY() + height - 1, getX() + width, getY() + height, edge);
            g.fill(getX(), getY(), getX() + 1, getY() + height, edge); g.fill(getX() + width - 1, getY(), getX() + width, getY() + height, edge);
            Font font = Minecraft.getInstance().font;
            int textX = getX() + (icon == null ? 1 : 4);
            if (icon != null) {
                if (getMessage().getString().isEmpty()) {
                    // Icon-only buttons (tabs, shortcuts) show the icon as large as they can, centred.
                    int size = Math.min(width, height) - 3;
                    icon(g, icon, getX() + (width - size) / 2, getY() + (height - size) / 2, size);
                    return;
                }
                int size = Math.min(16, height - 2);
                icon(g, icon, getX() + 2, getY() + (height - size) / 2, size);
                textX = getX() + size + 5;
            }
            int color = active ? (on ? GOLD_TEXT : TEXT) : 0xff6e6656, available = getX() + width - textX - (icon == null ? 1 : 3);
            String s = font.plainSubstrByWidth(getMessage().getString(), available);
            int tx = icon == null ? getX() + (width - font.width(s)) / 2 : textX;
            g.drawString(font, s, tx, getY() + (height - 8) / 2, color, false);
        }
        @Override protected void updateWidgetNarration(NarrationElementOutput output) { defaultButtonNarrationText(output); }
    }
}
