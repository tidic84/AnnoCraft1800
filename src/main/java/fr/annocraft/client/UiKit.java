package fr.annocraft.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.*;
import java.util.*;
import java.util.function.Supplier;

/** Shared visual language of the management interface: navy panels with gilded edges, item icons for goods and tiers. */
public final class UiKit {
    public static final int PANEL = 0xe8121c26, PANEL_LIGHT = 0xf0213140, GOLD = 0xffc9a85c, GOLD_TEXT = 0xffe7cf8a,
            TEXT = 0xffe8e2d4, MUTED = 0xff9fb0b8, GOOD = 0xff8fd49a, BAD = 0xffff8b7a, WARN = 0xffffc070;
    private UiKit() { }

    public static void panel(GuiGraphics g, int x, int y, int w, int h) {
        g.fill(x, y, x + w, y + h, PANEL);
        g.fill(x, y, x + w, y + 1, GOLD); g.fill(x, y + h - 1, x + w, y + h, 0xff7d6636);
        g.fill(x, y, x + 1, y + h, 0xff7d6636); g.fill(x + w - 1, y, x + w, y + h, 0xff7d6636);
    }
    public static void bar(GuiGraphics g, int x, int y, int w, int h, double fraction, int color) {
        g.fill(x, y, x + w, y + h, 0xff0b1218);
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
    /** Draws an item icon scaled into a square of {@code size} pixels. */
    public static void icon(GuiGraphics g, ItemStack stack, int x, int y, int size) {
        g.pose().pushPose(); g.pose().translate(x, y, 0); g.pose().scale(size / 16f, size / 16f, 1);
        g.renderItem(stack, 0, 0); g.pose().popPose();
    }

    private static final Map<String, ItemStack> GOODS = new HashMap<>();
    private static void good(String id, Item item) { GOODS.put(id, new ItemStack(item)); }
    static {
        good("coins", Items.GOLD_INGOT); good("fish", Items.COD); good("wood", Items.OAK_LOG); good("timber", Items.OAK_PLANKS);
        good("wool", Items.WHITE_WOOL); good("work_clothes", Items.LEATHER_CHESTPLATE); good("potatoes", Items.POTATO);
        good("schnapps", Items.GLASS_BOTTLE); good("grain", Items.WHEAT); good("flour", Items.SUGAR); good("bread", Items.BREAD);
        good("pigs", Items.PORKCHOP); good("sausages", Items.COOKED_PORKCHOP); good("tallow", Items.HONEYCOMB); good("soap", Items.WHITE_DYE);
        good("hops", Items.FERN); good("beer", Items.HONEY_BOTTLE); good("clay", Items.CLAY_BALL); good("bricks", Items.BRICK);
        good("iron", Items.RAW_IRON); good("coal", Items.COAL); good("steel", Items.IRON_INGOT); good("steel_beams", Items.IRON_BARS);
        good("weapons", Items.IRON_SWORD); good("sails", Items.WHITE_BANNER); good("beef", Items.BEEF); good("canned_food", Items.RABBIT_STEW);
        good("sewing_machines", Items.LOOM); good("sand", Items.SAND); good("glass", Items.GLASS); good("windows", Items.GLASS_PANE);
        good("light_bulbs", Items.LANTERN); good("grapes", Items.SWEET_BERRIES); good("champagne", Items.EXPERIENCE_BOTTLE);
        good("gold", Items.RAW_GOLD); good("pocket_watches", Items.CLOCK); good("jewelry", Items.DIAMOND); good("plantains", Items.MELON_SLICE);
        good("alpaca_wool", Items.BROWN_WOOL); good("ponchos", Items.ORANGE_CARPET); good("sugar_cane", Items.SUGAR_CANE);
        good("rum", Items.DRAGON_BREATH); good("coffee_beans", Items.COCOA_BEANS); good("coffee", Items.MUSHROOM_STEW);
        good("tobacco", Items.DRIED_KELP); good("cigars", Items.BROWN_CANDLE);
        // Tiers and categories.
        good("tier:farmers", Items.WOODEN_HOE); good("tier:workers", Items.IRON_PICKAXE); good("tier:artisans", Items.ANVIL);
        good("tier:engineers", Items.COMPASS); good("tier:investors", Items.GOLD_BLOCK); good("tier:laborers", Items.BAMBOO);
        good("tier:overseers", Items.SPYGLASS);
        good("cat:infrastructure", Items.BARREL); good("cat:housing", Items.RED_BED); good("cat:services", Items.BELL);
        good("cat:farmers", Items.WHEAT); good("cat:workers", Items.IRON_PICKAXE); good("cat:artisans", Items.ANVIL);
        good("cat:engineers", Items.COMPASS); good("cat:investors", Items.GOLD_BLOCK); good("cat:new_world", Items.JUNGLE_SAPLING);
        // Speakers of the campaign.
        good("speaker:agnes", Items.SPYGLASS); good("speaker:linnell", Items.CLOCK); good("speaker:rook", Items.IRON_SWORD);
        good("speaker:ashby", Items.WRITABLE_BOOK); good("speaker:julien", Items.PAPER);
        good("ship", Items.OAK_BOAT); good("map", Items.FILLED_MAP); good("colony", Items.WRITABLE_BOOK); good("visit", Items.ARMOR_STAND);
        good("road", Items.DIRT_PATH); good("road_remove", Items.IRON_SHOVEL); good("quest", Items.BOOK);
    }
    public static ItemStack item(String id) { return GOODS.getOrDefault(id, new ItemStack(Items.PAPER)); }

    /** Flat gilded button with an optional item icon, in the management-screen style. */
    public static final class AnnoButton extends AbstractButton {
        private final Runnable action;
        private final ItemStack icon;
        private Supplier<Boolean> highlighted = () -> false;
        public AnnoButton(int x, int y, int w, int h, Component label, ItemStack icon, Runnable action) {
            super(x, y, w, h, label); this.action = action; this.icon = icon;
        }
        public AnnoButton highlight(Supplier<Boolean> when) { highlighted = when; return this; }
        @Override public void onPress() { action.run(); }
        @Override protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
            boolean hover = isHoveredOrFocused() && active, on = highlighted.get();
            g.fill(getX(), getY(), getX() + width, getY() + height, !active ? 0xc0182027 : on ? 0xf0384a5a : hover ? 0xf02c3d4c : PANEL_LIGHT);
            int edge = on || hover ? GOLD : 0xff5b4a28;
            g.fill(getX(), getY(), getX() + width, getY() + 1, edge); g.fill(getX(), getY() + height - 1, getX() + width, getY() + height, edge);
            g.fill(getX(), getY(), getX() + 1, getY() + height, edge); g.fill(getX() + width - 1, getY(), getX() + width, getY() + height, edge);
            Font font = Minecraft.getInstance().font;
            int textX = getX() + 4;
            if (icon != null && !icon.isEmpty()) {
                int size = Math.min(16, height - 2);
                icon(g, icon, getX() + 2, getY() + (height - size) / 2, size);
                textX = getX() + size + 5;
                if (getMessage().getString().isEmpty()) return;
            }
            int color = active ? (on ? GOLD_TEXT : TEXT) : 0xff6c7880, available = getX() + width - textX - 3;
            String s = font.plainSubstrByWidth(getMessage().getString(), available);
            int tx = icon == null ? getX() + (width - font.width(s)) / 2 : textX;
            g.drawString(font, s, tx, getY() + (height - 8) / 2, color, false);
        }
        @Override protected void updateWidgetNarration(NarrationElementOutput output) { defaultButtonNarrationText(output); }
    }
}
