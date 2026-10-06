package fr.annocraft.client;

import fr.annocraft.AnnoCraft;
import fr.annocraft.building.*;
import fr.annocraft.network.AnnoNetwork;
import net.minecraft.client.gui.*;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;
import java.util.*;

public final class RtsScreen extends Screen {
    private int panel;
    private Button upgrade, demolish;
    public RtsScreen() { super(Component.translatable("screen.annocraft1800.title")); }
    @Override protected void init() {
        panel = Math.min(210, Math.max(140, width / 3));
        int y = 54;
        for (BuildingDefinition def : ClientState.DEFINITIONS.values()) if (def.level() == 1) {
            addRenderableWidget(Button.builder(Component.translatable(def.name()), b -> { RtsController.placement = def; RtsController.rotation = 0; ClientState.selected = null; })
                    .bounds(10, y, panel - 20, 20).build()); y += 24;
        }
        upgrade = addRenderableWidget(Button.builder(Component.translatable("screen.annocraft1800.upgrade"), b -> command(2)).bounds(10, height - 70, panel - 20, 20).build());
        demolish = addRenderableWidget(Button.builder(Component.translatable("screen.annocraft1800.demolish"), b -> command(1)).bounds(10, height - 46, panel - 20, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("screen.annocraft1800.visit"), b -> onClose()).bounds(10, height - 22, panel - 20, 20).build());
    }
    private void command(int action) {
        if (ClientState.selected != null) AnnoNetwork.CHANNEL.sendToServer(new AnnoNetwork.BuildCommand(action, AnnoCraft.id("residence"), BlockPos.ZERO, 0, ClientState.selected));
    }
    @Override public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        if (mouseX > panel && mouseY < height - 48) RtsController.pick(mouseX, mouseY, width, height);
        else RtsController.hover = null;
        g.fill(0, 0, panel, height, 0xe6192730);
        g.fill(panel - 2, 0, panel, height, 0xffc9a85c);
        g.drawString(font, Component.literal("ANNOCRAFT 1800"), 10, 10, 0xffdfc783, false);
        g.drawString(font, font.plainSubstrByWidth(Component.translatable("screen.annocraft1800.shared").getString(), panel - 20), 10, 26, 0xffa2b6bf, false);
        g.drawString(font, Component.translatable("screen.annocraft1800.catalogue"), 10, 42, 0xffffffff, false);
        BuildingInstance selected = ClientState.BUILDINGS.get(ClientState.selected);
        BuildingDefinition def = selected == null ? null : ClientState.DEFINITIONS.get(selected.definition());
        upgrade.active = def != null && def.upgrade() != null; demolish.active = selected != null;
        if (selected != null && def != null) {
            int top = height - 112;
            g.drawString(font, font.plainSubstrByWidth(Component.translatable(def.name()).getString(), panel - 20), 10, top, 0xffdfc783, false);
            g.drawString(font, Component.translatable("screen.annocraft1800.island", selected.island()), 10, top + 12, 0xffffffff, false);
            g.drawString(font, Component.translatable("screen.annocraft1800.level", def.level()), 10, top + 24, 0xffffffff, false);
        }
        g.fill(panel, height - 48, width, height, 0xd0192730);
        g.drawWordWrap(font, Component.translatable("screen.annocraft1800.controls"), panel + 8, height - 42, width - panel - 16, 0xffdfc783);
        if (!ClientState.message.isEmpty()) g.drawString(font, font.plainSubstrByWidth(Component.translatable(ClientState.message).getString(), width - panel - 16), panel + 8, height - 24, ClientState.messageSuccess ? 0xff88d4a0 : 0xffff8b7a, false);
        g.drawString(font, font.plainSubstrByWidth(Component.translatable("screen.annocraft1800.stats", ClientState.BUILDINGS.size(), (int)RtsController.zoom).getString(), width - panel - 16), panel + 8, height - 12, 0xffc1d1d7, false);
        if (RtsController.placement != null) {
            g.fill(panel + 8, 8, width - 8, 64, 0xd0192730);
            g.drawString(font, Component.translatable(RtsController.placement.name()).append(" · " + RtsController.rotation * 90 + "°"), panel + 16, 16, 0xffffffff, false);
            g.drawWordWrap(font, Component.translatable("screen.annocraft1800.place"), panel + 16, 30, width - panel - 32, 0xffdfc783);
        }
        super.render(g, mouseX, mouseY, partialTick);
    }
    @Override public boolean mouseClicked(double x, double y, int button) {
        if (super.mouseClicked(x, y, button)) return true;
        if (x > panel && y < height - 48 && button == 0) { RtsController.pick(x, y, width, height); RtsController.clickWorld(); return true; }
        if (button == 1) { RtsController.placement = null; ClientState.selected = null; return true; }
        return false;
    }
    @Override public boolean mouseScrolled(double x, double y, double delta) { RtsController.zoom = CameraMath.zoom(RtsController.zoom - (float)delta * 3); return true; }
    @Override public boolean keyPressed(int key, int scan, int modifiers) {
        if (key == GLFW.GLFW_KEY_R) { RtsController.rotation = (RtsController.rotation + 1) % 4; return true; }
        if (key == GLFW.GLFW_KEY_F6) { onClose(); return true; }
        return super.keyPressed(key, scan, modifiers);
    }
    @Override public boolean isPauseScreen() { return false; }
    @Override public void onClose() { RtsController.exit(); super.onClose(); }
    @Override public void removed() { if (RtsController.active) RtsController.exit(); }
}
