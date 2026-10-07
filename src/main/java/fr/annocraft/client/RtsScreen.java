package fr.annocraft.client;

import fr.annocraft.AnnoCraft;
import fr.annocraft.building.*;
import fr.annocraft.economy.*;
import fr.annocraft.network.AnnoNetwork;
import net.minecraft.client.gui.*;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.*;
import net.minecraft.network.chat.*;
import org.lwjgl.glfw.GLFW;
import java.util.*;

public final class RtsScreen extends Screen {
    private static final int SIDE = 142, BAR = 48, ROW = 16;
    private int panel;
    private Button upgrade, demolish;
    public RtsScreen() { super(Component.translatable("screen.annocraft1800.title")); }
    /** Storage first, then housing, then production: the order a new island is founded in. */
    private static int menuOrder(BuildingDefinition d) {
        EconomyProfile e = d.economy();
        return e.storageNode() ? (d.coastal() ? 0 : 1) : e.housing() ? 2 : e.producer() ? 3 : 4;
    }
    @Override protected void init() {
        panel = Math.min(210, Math.max(SIDE, width / 3));
        int y = 44;
        List<BuildingDefinition> catalogue = ClientState.DEFINITIONS.values().stream().filter(d -> d.level() == 1)
                .sorted(Comparator.comparingInt(RtsScreen::menuOrder).thenComparing(d -> d.id().toString())).toList();
        for (BuildingDefinition def : catalogue) {
            addRenderableWidget(Button.builder(Component.translatable(def.name()), b -> {
                        RtsController.placement = def; RtsController.rotation = 0; RtsController.roadMode = 0; RtsController.roadStart = null; ClientState.selected = null; })
                    .bounds(8, y, panel - 16, ROW - 2).tooltip(Tooltip.create(describe(def))).build()); y += ROW;
        }
        addRenderableWidget(Button.builder(Component.translatable("screen.annocraft1800.road"), b -> road(1))
                .bounds(8, y, (panel - 20) / 2, ROW - 2).tooltip(Tooltip.create(Component.translatable("screen.annocraft1800.road_help"))).build());
        addRenderableWidget(Button.builder(Component.translatable("screen.annocraft1800.road_remove"), b -> road(2))
                .bounds(12 + (panel - 20) / 2, y, (panel - 20) / 2, ROW - 2).build());
        int right = width - SIDE - 6, buttonsY = height - BAR - 20;
        upgrade = addRenderableWidget(Button.builder(Component.translatable("screen.annocraft1800.upgrade"), b -> command(2)).bounds(right + 4, buttonsY, SIDE / 2 - 6, ROW - 2).build());
        demolish = addRenderableWidget(Button.builder(Component.translatable("screen.annocraft1800.demolish"), b -> command(1)).bounds(right + SIDE / 2 + 1, buttonsY, SIDE / 2 - 6, ROW - 2).build());
        addRenderableWidget(Button.builder(Component.translatable("screen.annocraft1800.visit"), b -> onClose()).bounds(8, height - 22, panel - 16, ROW + 2).build());
    }
    private static void road(int mode) { RtsController.roadMode = mode; RtsController.roadStart = null; RtsController.placement = null; ClientState.selected = null; }
    private void command(int action) {
        if (ClientState.selected != null) AnnoNetwork.CHANNEL.sendToServer(new AnnoNetwork.BuildCommand(action, AnnoCraft.id("residence"), BlockPos.ZERO, 0, ClientState.selected));
    }
    static MutableComponent good(String id) { return Component.translatable("good.annocraft1800." + id); }
    static MutableComponent amounts(Map<String, ?> amounts) {
        MutableComponent c = Component.empty(); boolean first = true;
        for (var e : amounts.entrySet()) {
            if (!first) c.append(", "); first = false;
            c.append(e.getValue() + " ").append(e.getKey().equals(EconomyProfile.COINS) ? Component.translatable("screen.annocraft1800.coins") : good(e.getKey()));
        }
        return first ? Component.translatable("screen.annocraft1800.free") : c;
    }
    static Component describe(BuildingDefinition def) {
        EconomyProfile e = def.economy();
        MutableComponent c = Component.translatable(def.name()).withStyle(net.minecraft.ChatFormatting.GOLD)
                .append("\n").append(Component.translatable("tooltip.annocraft1800.cost", amounts(e.cost())));
        if (e.upkeep() > 0) c.append("\n").append(Component.translatable("tooltip.annocraft1800.upkeep", e.upkeep()));
        if (e.storageNode()) c.append("\n").append(Component.translatable("tooltip.annocraft1800.storage", e.storage()));
        if (e.workforce() > 0) c.append("\n").append(Component.translatable("tooltip.annocraft1800.workforce", e.workforce(), Component.translatable("tier.annocraft1800." + e.workTier())));
        if (e.producer()) c.append("\n").append(e.inputs().isEmpty()
                ? Component.translatable("tooltip.annocraft1800.produces", amounts(e.outputs()), e.cycle())
                : Component.translatable("tooltip.annocraft1800.converts", amounts(e.inputs()), amounts(e.outputs()), e.cycle()));
        if (e.housing()) {
            c.append("\n").append(Component.translatable("tooltip.annocraft1800.housing", e.capacity(), Component.translatable("tier.annocraft1800." + e.houseTier())));
            Map<String, String> needs = new TreeMap<>(); e.needs().forEach((k, v) -> needs.put(k, String.format(Locale.ROOT, "%.2f", v)));
            if (!needs.isEmpty()) c.append("\n").append(Component.translatable("tooltip.annocraft1800.needs", amounts(needs)));
        }
        if (def.coastal()) c.append("\n").append(Component.translatable("tooltip.annocraft1800.coastal"));
        return c;
    }
    private boolean overUi(double x, double y) {
        if (x <= panel || y >= height - BAR) return true;
        if (x >= width - SIDE - 6 && y <= 12 + 10 * islandLines()) return true;
        return ClientState.selected != null && x >= width - SIDE - 6 && y >= height - BAR - 76;
    }
    private String currentIsland() {
        BuildingInstance selected = ClientState.BUILDINGS.get(ClientState.selected);
        if (selected != null) return selected.island();
        return ClientState.layout == null ? null : ClientState.layout.islandAt((int) RtsController.x, (int) RtsController.z).map(i -> i.id()).orElse(null);
    }
    private int islandLines() {
        String id = currentIsland(); if (id == null) return 1;
        CompoundTag island = ClientState.island(id);
        return 1 + goods(island).size() + island.getCompound("workforce").getAllKeys().size();
    }
    private static Set<String> goods(CompoundTag island) {
        Set<String> goods = new TreeSet<>(island.getCompound("stock").getAllKeys()); goods.addAll(island.getCompound("rates").getAllKeys()); return goods;
    }
    private static String islandName(String id) { return id.startsWith("island_") ? id.substring(7) : id; }
    @Override public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        if (!overUi(mouseX, mouseY)) RtsController.pick(mouseX, mouseY, width, height);
        else RtsController.hover = null;
        CompoundTag economy = ClientState.economy;
        // Left: finances and catalogue.
        g.fill(0, 0, panel, height, 0xe6192730);
        g.fill(panel - 2, 0, panel, height, 0xffc9a85c);
        g.drawString(font, Component.literal("ANNOCRAFT 1800"), 8, 6, 0xffdfc783, false);
        if (economy.getBoolean("sandbox")) g.drawString(font, font.plainSubstrByWidth(Component.translatable("screen.annocraft1800.sandbox").getString(), panel - 16), 8, 18, 0xffa2b6bf, false);
        else {
            long balance = Math.round(economy.getDouble("income") - economy.getDouble("upkeep"));
            g.drawString(font, font.plainSubstrByWidth(Component.translatable("screen.annocraft1800.balance", (long) Math.floor(economy.getDouble("coins")), (balance >= 0 ? "+" : "") + balance).getString(), panel - 16),
                    8, 18, balance >= 0 ? 0xff88d4a0 : 0xffff8b7a, false);
        }
        g.drawString(font, Component.translatable("screen.annocraft1800.catalogue"), 8, 32, 0xffffffff, false);
        // Top right: island stocks, flows and workforce.
        String islandId = currentIsland(); int right = width - SIDE - 6;
        g.fill(right, 6, width - 6, 12 + 10 * islandLines(), 0xd0192730);
        if (islandId == null) g.drawString(font, Component.translatable("screen.annocraft1800.ocean"), right + 4, 9, 0xffa2b6bf, false);
        else {
            CompoundTag island = ClientState.island(islandId); int y = 9;
            g.drawString(font, Component.translatable("screen.annocraft1800.island_stock", islandName(islandId), island.getInt("capacity")), right + 4, y, 0xffdfc783, false);
            for (String id : goods(island)) {
                double rate = island.getCompound("rates").getDouble(id);
                String flow = Math.abs(rate) < .05 ? "" : String.format(Locale.ROOT, " (%+.1f/min)", rate);
                y += 10; g.drawString(font, font.plainSubstrByWidth(good(id).getString() + " " + (long) Math.floor(island.getCompound("stock").getDouble(id) + 1e-6) + flow, SIDE - 8),
                        right + 4, y, rate < -.05 ? 0xffffb38a : 0xffffffff, false);
            }
            CompoundTag workforce = island.getCompound("workforce");
            for (String tier : workforce.getAllKeys()) {
                int[] w = workforce.getIntArray(tier); if (w.length < 2) continue;
                y += 10; g.drawString(font, font.plainSubstrByWidth(Component.translatable("screen.annocraft1800.workforce", Component.translatable("tier.annocraft1800." + tier), w[0], w[1]).getString(), SIDE - 8),
                        right + 4, y, w[0] >= w[1] ? 0xffc1d1d7 : 0xffff8b7a, false);
            }
        }
        // Bottom right: selected building.
        BuildingInstance selected = ClientState.BUILDINGS.get(ClientState.selected);
        BuildingDefinition def = selected == null ? null : ClientState.DEFINITIONS.get(selected.definition());
        upgrade.visible = demolish.visible = selected != null;
        upgrade.active = def != null && def.upgrade() != null; demolish.active = selected != null;
        if (def != null && def.upgrade() != null && ClientState.DEFINITIONS.containsKey(def.upgrade()))
            upgrade.setTooltip(Tooltip.create(describe(ClientState.DEFINITIONS.get(def.upgrade()))));
        if (selected != null && def != null) {
            int top = height - BAR - 76;
            g.fill(right, top, width - 6, height - BAR - 2, 0xd0192730);
            g.drawString(font, font.plainSubstrByWidth(Component.translatable(def.name()).getString(), SIDE - 8), right + 4, top + 4, 0xffdfc783, false);
            g.drawString(font, Component.translatable("screen.annocraft1800.island_level", islandName(selected.island()), def.level()), right + 4, top + 15, 0xffffffff, false);
            CompoundTag site = ClientState.SITES.get(selected.id());
            if (site != null) {
                int status = site.getByte("status");
                ColonyEconomy.Status s = ColonyEconomy.Status.values()[Math.max(0, Math.min(status, ColonyEconomy.Status.values().length - 1))];
                g.drawString(font, font.plainSubstrByWidth(Component.translatable("status.annocraft1800." + s.name().toLowerCase(Locale.ROOT)).getString(), SIDE - 8),
                        right + 4, top + 26, s == ColonyEconomy.Status.OK ? 0xff88d4a0 : 0xffff8b7a, false);
                EconomyProfile e = def.economy(); Component first = null, second = null;
                if (e.housing()) {
                    first = Component.translatable("screen.annocraft1800.residents", (int) Math.floor(site.getDouble("residents") + 1e-6), e.capacity());
                    second = Component.translatable("screen.annocraft1800.supply", Math.round(site.getDouble("supply") * 100));
                } else if (e.producer()) {
                    first = Component.translatable("screen.annocraft1800.productivity", Math.round(site.getDouble("productivity") * 100));
                    second = Component.translatable("screen.annocraft1800.progress", Math.round(site.getDouble("progress") * 100));
                }
                if (first != null) g.drawString(font, font.plainSubstrByWidth(first.getString(), SIDE - 8), right + 4, top + 37, 0xffffffff, false);
                if (second != null) g.drawString(font, font.plainSubstrByWidth(second.getString(), SIDE - 8), right + 4, top + 47, 0xffc1d1d7, false);
            }
        }
        // Bottom bar: help, server feedback, stats.
        g.fill(panel, height - BAR, width, height, 0xd0192730);
        Component help = RtsController.placement != null ? Component.translatable(RtsController.placement.name()).append(" · " + RtsController.rotation * 90 + "° · ").append(Component.translatable("screen.annocraft1800.place"))
                : RtsController.roadMode != 0 ? Component.translatable(RtsController.roadMode == 1 ? "screen.annocraft1800.road_place" : "screen.annocraft1800.road_erase")
                : Component.translatable("screen.annocraft1800.controls");
        g.drawString(font, font.plainSubstrByWidth(help.getString(), width - panel - 16), panel + 8, height - 42, 0xffdfc783, false);
        if (!ClientState.message.isEmpty()) g.drawString(font, font.plainSubstrByWidth(Component.translatable(ClientState.message).getString(), width - panel - 16), panel + 8, height - 28, ClientState.messageSuccess ? 0xff88d4a0 : 0xffff8b7a, false);
        g.drawString(font, font.plainSubstrByWidth(Component.translatable("screen.annocraft1800.stats", ClientState.BUILDINGS.size(), (int)RtsController.zoom).getString(), width - panel - 16), panel + 8, height - 14, 0xffc1d1d7, false);
        super.render(g, mouseX, mouseY, partialTick);
    }
    @Override public boolean mouseClicked(double x, double y, int button) {
        if (super.mouseClicked(x, y, button)) return true;
        if (!overUi(x, y) && button == 0) { RtsController.pick(x, y, width, height); RtsController.clickWorld(); return true; }
        if (button == 1) { RtsController.placement = null; RtsController.roadMode = 0; RtsController.roadStart = null; ClientState.selected = null; return true; }
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
