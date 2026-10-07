package fr.annocraft.client;

import fr.annocraft.economy.Diplomacy;
import fr.annocraft.network.AnnoNetwork;
import fr.annocraft.world.IslandLayout;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.*;
import org.lwjgl.glfw.GLFW;
import java.util.*;

/**
 * Anno's strategic view: the whole archipelago from above, opened by zooming out fully or with M.
 * Clicking an island moves the camera there; ownership, ships and trade routes are drawn on the map.
 */
public final class StrategicMapScreen extends Screen {
    private final Screen parent;
    private String world;
    private int mapX, mapY, side;
    public StrategicMapScreen(Screen parent) { super(Component.translatable("map.annocraft1800.title")); this.parent = parent; world = ClientState.world; }
    @Override protected void init() {
        side = Math.min(height - 34, width - 150);
        mapX = 8; mapY = 26;
        int bx = mapX + side + 8, bw = width - bx - 8;
        addRenderableWidget(new UiKit.AnnoButton(bx, 26, bw, 16, Component.translatable("world.annocraft1800.old"), null, () -> { world = IslandLayout.OLD_WORLD; clearWidgets(); init(); }).highlight(() -> IslandLayout.OLD_WORLD.equals(world)));
        addRenderableWidget(new UiKit.AnnoButton(bx, 44, bw, 16, Component.translatable("world.annocraft1800.new"), null, () -> { world = IslandLayout.NEW_WORLD; clearWidgets(); init(); }).highlight(() -> IslandLayout.NEW_WORLD.equals(world)));
        boolean open = ClientState.economy.getBoolean("new_world_open");
        var travel = addRenderableWidget(new UiKit.AnnoButton(bx, 66, bw, 16, Component.translatable("map.annocraft1800.travel"), UiKit.item("ship"), () -> {
            AnnoNetwork.action("travel", "world", world); onClose();
        }));
        travel.active = !world.equals(ClientState.world) && (open || IslandLayout.OLD_WORLD.equals(world));
        addRenderableWidget(new UiKit.AnnoButton(bx, height - 24, bw, 16, Component.translatable("map.annocraft1800.close"), null, this::onClose));
    }
    private IslandLayout.Island islandAt(double mx, double my) {
        IslandLayout layout = ClientState.LAYOUTS.get(world); if (layout == null) return null;
        double[] w = MapView.toWorld(layout, mx, my, mapX, mapY, side);
        return layout.islands().stream().filter(i -> i.distance(w[0], w[1]) <= 1.2).findFirst().orElse(null);
    }
    @Override public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        g.fill(0, 0, width, height, 0xf00b1218);
        UiKit.text(g, Component.translatable("map.annocraft1800.title").append(" · ").append(Component.translatable("world.annocraft1800." + world)), 8, 9, UiKit.GOLD_TEXT, width - 16);
        UiKit.panel(g, mapX - 2, mapY - 2, side + 4, side + 4);
        MapView.draw(g, world, mapX, mapY, side, true);
        IslandLayout layout = ClientState.LAYOUTS.get(world);
        if (layout != null) for (IslandLayout.Island i : layout.islands()) {
            double[] c = MapView.toMap(layout, i.x(), i.z(), mapX, mapY, side);
            UiKit.centered(g, Component.literal(RtsScreen.islandName(i.id())), (int) c[0], (int) c[1] - 4, 0xffffffff);
        }
        // Legend and hovered island.
        int lx = mapX + side + 8, ly = 90, lw = width - lx - 8;
        legend(g, lx, ly, Diplomacy.PLAYER, Component.translatable("map.annocraft1800.you"), lw); ly += 11;
        for (Diplomacy.FactionType f : Diplomacy.FACTIONS) { legend(g, lx, ly, f.id(), Component.translatable("faction.annocraft1800." + f.id()), lw); ly += 11; }
        IslandLayout.Island hovered = islandAt(mouseX, mouseY);
        if (hovered != null) {
            List<Component> lines = new ArrayList<>();
            String owner = ClientState.owner(hovered.id());
            lines.add(Component.translatable("colony.annocraft1800.island_" + (hovered.id().startsWith("nw_") ? "new" : "old"), RtsScreen.islandName(hovered.id()).replace("NM ", ""))
                    .withStyle(net.minecraft.ChatFormatting.GOLD));
            lines.add(owner.isEmpty() ? Component.translatable("map.annocraft1800.free")
                    : owner.equals(Diplomacy.PLAYER) ? Component.translatable("map.annocraft1800.yours") : Component.translatable("faction.annocraft1800." + owner));
            lines.add(Component.translatable("screen.annocraft1800.fertility", RtsScreen.names(Arrays.asList(hovered.fertility().split(",")), "resource.annocraft1800.")));
            lines.add(Component.translatable("screen.annocraft1800.deposits", RtsScreen.names(Arrays.asList(hovered.deposit().split(",")), "resource.annocraft1800.")));
            CompoundTag stock = ClientState.island(hovered.id()).getCompound("stock");
            if (!stock.isEmpty()) lines.add(Component.translatable("map.annocraft1800.goods", stock.getAllKeys().size()));
            if (world.equals(ClientState.world) && RtsController.active) lines.add(Component.translatable("map.annocraft1800.click").withStyle(net.minecraft.ChatFormatting.GRAY));
            g.renderComponentTooltip(font, lines, mouseX, mouseY);
        }
        super.render(g, mouseX, mouseY, partialTick);
    }
    private void legend(GuiGraphics g, int x, int y, String owner, Component label, int w) {
        g.fill(x, y + 1, x + 7, y + 8, MapView.ownerColor(owner)); UiKit.text(g, label, x + 10, y, UiKit.TEXT, w - 10);
    }
    @Override public boolean mouseClicked(double mx, double my, int button) {
        if (super.mouseClicked(mx, my, button)) return true;
        IslandLayout layout = ClientState.LAYOUTS.get(world);
        if (button == 0 && layout != null && world.equals(ClientState.world) && mx >= mapX && my >= mapY && mx < mapX + side && my < mapY + side) {
            double[] w = MapView.toWorld(layout, mx, my, mapX, mapY, side);
            IslandLayout.Island island = islandAt(mx, my);
            RtsController.jump(island == null ? w[0] : island.x(), island == null ? w[1] : island.z());
            if (!RtsController.active) { minecraft.setScreen(null); RtsController.jumped = true; RtsController.enter(); } else onClose();
            return true;
        }
        return false;
    }
    @Override public boolean mouseScrolled(double x, double y, double delta) {
        // Zooming in from the strategic map returns to the city view.
        if (delta > 0) { onClose(); return true; }
        return false;
    }
    @Override public boolean keyPressed(int key, int scan, int modifiers) {
        if (key == GLFW.GLFW_KEY_M) { onClose(); return true; }
        return super.keyPressed(key, scan, modifiers);
    }
    @Override public boolean isPauseScreen() { return false; }
    @Override public void onClose() { minecraft.setScreen(parent); }
}
