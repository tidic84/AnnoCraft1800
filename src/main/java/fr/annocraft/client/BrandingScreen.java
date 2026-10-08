package fr.annocraft.client;

import fr.annocraft.economy.Company;
import fr.annocraft.network.AnnoNetwork;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import java.util.*;

/**
 * The company's identity, as on Anno's profile screen: the player's portrait among painted characters (or their
 * own skin), the company's name and colour, and its flag, painted cell by cell or started from a pattern.
 * Opened on arrival in the archipelago while the company has no identity yet, and from the portrait in the HUD.
 */
public final class BrandingScreen extends Screen {
    private static final int CELL = 6, DESIGN_W = 580, DESIGN_H = 290;
    /** The screen is laid out on a fixed canvas, scaled to fit the window at every interface scale. */
    private float k = 1; private int ox, oy;
    private final Screen parent;
    private EditBox name;
    private int color, avatar, primary = 2, secondary = 0, pattern;
    private byte[] flag;
    private boolean fill;
    private int px, py, fx, fy, cx;
    private boolean painting;
    private boolean hint;
    public BrandingScreen(Screen parent) {
        super(Component.translatable("brand.annocraft1800.title")); this.parent = parent;
        Company c = ClientState.myCompany(); Company.Member m = ClientState.me();
        Random random = new Random();
        if (c == null) c = Company.random("player", "", random);
        color = c.color(); flag = c.flag().clone();
        avatar = Math.max(0, Company.Member.AVATARS.indexOf(m == null ? "captain" : m.avatar()));
        initialName = c.name().isEmpty() && minecraftName() != null ? Component.translatable("brand.annocraft1800.default_name", minecraftName()).getString() : c.name();
    }
    private final String initialName;
    private static String minecraftName() { var p = net.minecraft.client.Minecraft.getInstance().player; return p == null ? null : p.getGameProfile().getName(); }

    @Override protected void init() {
        k = Math.min(1f, Math.min((width - 8) / (float) DESIGN_W, (height - 8) / (float) DESIGN_H));
        ox = (int) ((width - DESIGN_W * k) / 2); oy = (int) ((height - DESIGN_H * k) / 2);
        int w = 560, left = 10, top = 28;
        px = left; py = top;
        cx = left + 150;
        fx = left + w - Company.FLAG_W * CELL - 4; fy = top + 18;
        // Portrait carousel.
        addRenderableWidget(new UiKit.AnnoButton(px, py + 166, 18, 16, Component.literal("<"), null, () -> avatar = Math.floorMod(avatar - 1, Company.Member.AVATARS.size())));
        addRenderableWidget(new UiKit.AnnoButton(px + 122, py + 166, 18, 16, Component.literal(">"), null, () -> avatar = Math.floorMod(avatar + 1, Company.Member.AVATARS.size())));
        // Name.
        String typed = name == null ? initialName : name.getValue();
        name = addRenderableWidget(new EditBox(font, cx, py + 18, fx - cx - 16, 16, Component.translatable("brand.annocraft1800.name")));
        name.setMaxLength(Company.NAME_LENGTH); name.setValue(typed);
        // Flag tools and patterns.
        int ty = fy + Company.FLAG_H * CELL + 34;
        addRenderableWidget(new UiKit.AnnoButton(fx, ty, 70, 15, Component.translatable("brand.annocraft1800.brush"), null, () -> fill = false).highlight(() -> !fill));
        addRenderableWidget(new UiKit.AnnoButton(fx + 74, ty, 70, 15, Component.translatable("brand.annocraft1800.fill"), null, () -> fill = true).highlight(() -> fill));
        addRenderableWidget(new UiKit.AnnoButton(cx, py + 200, 100, 16, Component.translatable("brand.annocraft1800.random"), null, this::randomise));
        addRenderableWidget(new UiKit.AnnoButton(left + w - 170, py + 224, 170, 18, Component.translatable("brand.annocraft1800.found"), "coins", this::save));
        addRenderableWidget(new UiKit.AnnoButton(left, py + 224, 90, 18, Component.translatable("gui.cancel"), null, this::onClose));
    }
    private void randomise() {
        Company c = Company.random("player", name.getValue(), new Random());
        color = c.color(); flag = c.flag(); avatar = new Random().nextInt(Company.Member.AVATARS.size() - 1);
    }
    private void save() {
        StringBuilder hex = new StringBuilder(); for (byte b : flag) hex.append(Character.forDigit(b, 16));
        AnnoNetwork.action("brand", "name", name.getValue(), "color", color, "flag", hex.toString(), "avatar", Company.Member.AVATARS.get(avatar));
        onClose();
    }

    @Override public void render(GuiGraphics g, int realX, int realY, float partialTick) {
        renderBackground(g);
        g.pose().pushPose(); g.pose().translate(ox, oy, 0); g.pose().scale(k, k, 1);
        int mouseX = (int) ((realX - ox) / k), mouseY = (int) ((realY - oy) / k);
        int w = 560, left = 10;
        UiKit.panel(g, left - 8, py - 22, w + 16, 270);
        UiKit.centered(g, Component.translatable("brand.annocraft1800.title"), DESIGN_W / 2, py - 15, UiKit.GOLD_TEXT);
        // Portrait.
        String id = Company.Member.AVATARS.get(avatar);
        var player = minecraft.player;
        Avatars.portrait(g, id, player == null ? null : player.getUUID(), px, py, 140, 160, color, false, 0);
        UiKit.centered(g, Component.translatable("avatar.annocraft1800." + id), px + 70, py + 170, UiKit.TEXT);
        // Name and colour.
        UiKit.text(g, Component.translatable("brand.annocraft1800.name"), cx, py + 6, UiKit.GOLD, 200);
        UiKit.text(g, Component.translatable("brand.annocraft1800.color"), cx, py + 46, UiKit.GOLD, 200);
        for (int i = 0; i < Company.COLORS.length; i++) {
            int sx = cx + (i % 6) * 20, sy = py + 58 + (i / 6) * 20;
            g.fill(sx, sy, sx + 16, sy + 16, 0xff000000 | Company.COLORS[i]);
            MapView.frame(g, sx - 1, sy - 1, 18, 18, Company.COLORS[i] == color ? UiKit.GOLD_TEXT : 0xff2a2620);
        }
        // Preview: the flag on a pole and a sail in the company's colour.
        UiKit.text(g, Component.translatable("brand.annocraft1800.preview"), cx, py + 106, UiKit.GOLD, 200);
        Company preview = new Company("preview", name.getValue(), color, flag, true);
        g.fill(cx + 4, py + 118, cx + 6, py + 190, 0xff6b4a2a);
        Avatars.drawFlag(g, preview, cx + 6, py + 120, 48, 32);
        g.fill(cx + 70, py + 128, cx + 110, py + 176, 0xff000000 | color);
        g.fill(cx + 89, py + 120, cx + 91, py + 190, 0xff6b4a2a);
        g.fill(cx + 66, py + 184, cx + 116, py + 190, 0xff4a3220);
        // Flag editor: canvas, palette, patterns.
        UiKit.text(g, Component.translatable("brand.annocraft1800.flag"), fx, fy - 12, UiKit.GOLD, 200);
        if (mouseX >= fx && mouseY >= fy && mouseX < fx + Company.FLAG_W * CELL && mouseY < fy + Company.FLAG_H * CELL) hint = true;
        for (int x = 0; x < Company.FLAG_W; x++) for (int y = 0; y < Company.FLAG_H; y++)
            g.fill(fx + x * CELL, fy + y * CELL, fx + x * CELL + CELL, fy + y * CELL + CELL, 0xff000000 | Company.PALETTE[flag[y * Company.FLAG_W + x]]);
        MapView.frame(g, fx - 1, fy - 1, Company.FLAG_W * CELL + 2, Company.FLAG_H * CELL + 2, UiKit.GOLD);
        int pyy = fy + Company.FLAG_H * CELL + 6;
        for (int i = 0; i < Company.PALETTE.length; i++) {
            int sx = fx + (i % 8) * 18, sy = pyy + (i / 8) * 13;
            g.fill(sx, sy, sx + 16, sy + 11, 0xff000000 | Company.PALETTE[i]);
            int edge = i == primary ? UiKit.GOLD_TEXT : i == secondary ? 0xff8ac0ff : 0xff2a2620;
            MapView.frame(g, sx - 1, sy - 1, 18, 13, edge);
        }
        int ry = pyy + 46;
        UiKit.text(g, Component.translatable("brand.annocraft1800.patterns"), fx, ry, UiKit.GOLD, 200);
        for (int i = 0; i < Company.PRESETS.length; i++) {
            int sx = fx + (i % 5) * 30, sy = ry + 11 + (i / 5) * 20;
            byte[] p = Company.preset(i, primary, secondary, contrast());
            for (int x = 0; x < Company.FLAG_W; x++) for (int y = 0; y < Company.FLAG_H; y++) g.fill(sx + x, sy + y, sx + x + 1, sy + y + 1, 0xff000000 | Company.PALETTE[p[y * Company.FLAG_W + x]]);
            MapView.frame(g, sx - 1, sy - 1, 26, 18, mouseX >= sx && mouseY >= sy && mouseX < sx + 24 && mouseY < sy + 16 ? UiKit.GOLD : 0xff2a2620);
        }
        super.render(g, mouseX, mouseY, partialTick);
        g.pose().popPose();
        if (hint) g.renderTooltip(font, font.split(Component.translatable("brand.annocraft1800.hint"), 200), realX, realY);
        hint = false;
    }
    /** Third colour of a pattern: whichever of white and black stands out from the two chosen. */
    private int contrast() { return primary != 0 && secondary != 0 ? 0 : primary != 1 && secondary != 1 ? 1 : 5; }

    @Override public boolean mouseClicked(double realX, double realY, int button) {
        double mx = (realX - ox) / k, my = (realY - oy) / k;
        if (super.mouseClicked(mx, my, button)) return true;
        for (int i = 0; i < Company.COLORS.length; i++) {
            int sx = cx + (i % 6) * 20, sy = py + 58 + (i / 6) * 20;
            if (mx >= sx && my >= sy && mx < sx + 16 && my < sy + 16) { color = Company.COLORS[i]; return true; }
        }
        int pyy = fy + Company.FLAG_H * CELL + 6;
        for (int i = 0; i < Company.PALETTE.length; i++) {
            int sx = fx + (i % 8) * 18, sy = pyy + (i / 8) * 13;
            if (mx >= sx && my >= sy && mx < sx + 16 && my < sy + 11) { if (button == 1) secondary = i; else primary = i; return true; }
        }
        int ry = pyy + 46;
        for (int i = 0; i < Company.PRESETS.length; i++) {
            int sx = fx + (i % 5) * 30, sy = ry + 11 + (i / 5) * 20;
            if (mx >= sx && my >= sy && mx < sx + 24 && my < sy + 16) { flag = Company.preset(i, primary, secondary, contrast()); return true; }
        }
        if (paint(mx, my, button)) { painting = !fill; return true; }
        return false;
    }
    @Override public boolean mouseDragged(double rx, double ry, int button, double dx, double dy) {
        double mx = (rx - ox) / k, my = (ry - oy) / k;
        if (painting) { paint(mx, my, button); return true; }
        return super.mouseDragged(mx, my, button, dx / k, dy / k);
    }
    @Override public boolean mouseReleased(double rx, double ry, int button) { painting = false; return super.mouseReleased((rx - ox) / k, (ry - oy) / k, button); }
    /** Left button paints the primary colour, right button the secondary; the bucket fills a whole area. */
    private boolean paint(double mx, double my, int button) {
        int x = (int) Math.floor((mx - fx) / CELL), y = (int) Math.floor((my - fy) / CELL);
        if (x < 0 || y < 0 || x >= Company.FLAG_W || y >= Company.FLAG_H) return false;
        byte c = (byte) (button == 1 ? secondary : primary);
        if (!fill) { flag[y * Company.FLAG_W + x] = c; return true; }
        byte from = flag[y * Company.FLAG_W + x]; if (from == c) return true;
        Deque<int[]> open = new ArrayDeque<>(List.of(new int[]{x, y}));
        while (!open.isEmpty()) {
            int[] p = open.poll();
            if (p[0] < 0 || p[1] < 0 || p[0] >= Company.FLAG_W || p[1] >= Company.FLAG_H || flag[p[1] * Company.FLAG_W + p[0]] != from) continue;
            flag[p[1] * Company.FLAG_W + p[0]] = c;
            open.add(new int[]{p[0] + 1, p[1]}); open.add(new int[]{p[0] - 1, p[1]}); open.add(new int[]{p[0], p[1] + 1}); open.add(new int[]{p[0], p[1] - 1});
        }
        return true;
    }
    @Override public boolean isPauseScreen() { return false; }
    @Override public void onClose() { if (parent instanceof RtsScreen) RtsScreen.keepCamera = false; minecraft.setScreen(parent); }
}
