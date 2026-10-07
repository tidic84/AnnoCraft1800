package fr.annocraft.client;

import fr.annocraft.AnnoCraft;
import fr.annocraft.building.*;
import fr.annocraft.economy.*;
import fr.annocraft.network.AnnoNetwork;
import fr.annocraft.world.IslandLayout;
import net.minecraft.client.gui.*;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.*;
import net.minecraft.network.chat.*;
import org.lwjgl.glfw.GLFW;
import java.util.*;

public final class RtsScreen extends Screen {
    private static final int SIDE = 142, BAR = 48, ROW = 16, LIST_TOP = 46;
    static final List<String> CATEGORIES = List.of("infrastructure", "housing", "services", "farmers", "workers", "artisans", "engineers", "investors", "new_world");
    private static String category = "infrastructure";
    private static int scroll;
    /** Set while a child screen (colony management) replaces this one, so the camera stays in RTS mode. */
    static boolean keepCamera;
    private int panel;
    private Button upgrade, demolish;
    public RtsScreen() { super(Component.translatable("screen.annocraft1800.title")); }
    static String category(BuildingDefinition d) {
        EconomyProfile e = d.economy();
        if (e.housing()) return "housing";
        if (e.storageNode() || e.shipyard() || e.defense() > 0) return "infrastructure";
        if (e.serviceProvider()) return "services";
        if (IslandLayout.NEW_WORLD.equals(e.world())) return "new_world";
        return e.unlockTier() == null ? "farmers" : e.unlockTier();
    }
    private static List<BuildingDefinition> catalogue(String cat) {
        return ClientState.DEFINITIONS.values().stream()
                .filter(d -> d.level() == 1 && d.economy().buildableIn(ClientState.world) && category(d).equals(cat)).toList();
    }
    private static List<String> categories() { return CATEGORIES.stream().filter(c -> !catalogue(c).isEmpty()).toList(); }
    static boolean unlocked(EconomyProfile e) {
        CompoundTag eco = ClientState.economy;
        return eco.getBoolean("sandbox") || e.unlockTier() == null || eco.getCompound("population").getInt(e.unlockTier()) >= e.unlockResidents();
    }
    private int rows() { return Math.max(1, (height - 42 - LIST_TOP) / ROW); }
    @Override protected void init() {
        panel = Math.min(210, Math.max(SIDE, width / 3));
        addRenderableWidget(Button.builder(Component.translatable("screen.annocraft1800.colony"), b -> openColony()).bounds(panel - 58, 3, 50, 12)
                .tooltip(Tooltip.create(Component.translatable("screen.annocraft1800.colony_help"))).build());
        List<String> cats = categories();
        if (!cats.contains(category) && !cats.isEmpty()) category = cats.get(0);
        addRenderableWidget(Button.builder(Component.literal("‹"), b -> shiftCategory(-1)).bounds(8, 28, 14, 14).build());
        addRenderableWidget(Button.builder(Component.translatable("category.annocraft1800." + category), b -> shiftCategory(1)).bounds(24, 28, panel - 48, 14).build());
        addRenderableWidget(Button.builder(Component.literal("›"), b -> shiftCategory(1)).bounds(panel - 22, 28, 14, 14).build());
        List<BuildingDefinition> list = catalogue(category);
        scroll = Math.max(0, Math.min(scroll, list.size() - rows()));
        int y = LIST_TOP;
        for (BuildingDefinition def : list.subList(scroll, Math.min(list.size(), scroll + rows()))) {
            Button b = addRenderableWidget(Button.builder(Component.translatable(def.name()), x -> {
                        RtsController.placement = def; RtsController.rotation = 0; RtsController.roadMode = 0; RtsController.roadStart = null; ClientState.selected = null; })
                    .bounds(8, y, panel - 16, ROW - 2).tooltip(Tooltip.create(describe(def))).build());
            b.active = unlocked(def.economy());
            y += ROW;
        }
        addRenderableWidget(Button.builder(Component.translatable("screen.annocraft1800.road"), b -> road(1))
                .bounds(8, height - 40, (panel - 20) / 2, ROW - 2).tooltip(Tooltip.create(Component.translatable("screen.annocraft1800.road_help"))).build());
        addRenderableWidget(Button.builder(Component.translatable("screen.annocraft1800.road_remove"), b -> road(2))
                .bounds(12 + (panel - 20) / 2, height - 40, (panel - 20) / 2, ROW - 2).build());
        int right = width - SIDE - 6, buttonsY = height - BAR - 20;
        upgrade = addRenderableWidget(Button.builder(Component.translatable("screen.annocraft1800.upgrade"), b -> command(2)).bounds(right + 4, buttonsY, SIDE / 2 - 6, ROW - 2).build());
        demolish = addRenderableWidget(Button.builder(Component.translatable("screen.annocraft1800.demolish"), b -> command(1)).bounds(right + SIDE / 2 + 1, buttonsY, SIDE / 2 - 6, ROW - 2).build());
        addRenderableWidget(Button.builder(Component.translatable("screen.annocraft1800.visit"), b -> onClose()).bounds(8, height - 22, panel - 16, ROW + 2).build());
    }
    private void shiftCategory(int delta) {
        List<String> cats = categories(); if (cats.isEmpty()) return;
        category = cats.get(Math.floorMod(cats.indexOf(category) + delta, cats.size())); scroll = 0;
        clearWidgets(); init();
    }
    void openColony() { keepCamera = true; minecraft.setScreen(new ColonyScreen(this)); keepCamera = false; }
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
    static MutableComponent names(List<String> ids, String prefix) {
        MutableComponent c = Component.empty();
        for (int i = 0; i < ids.size(); i++) { if (i > 0) c.append(", "); c.append(Component.translatable(prefix + ids.get(i))); }
        return c;
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
            if (!e.needs().isEmpty()) c.append("\n").append(Component.translatable("tooltip.annocraft1800.needs", names(List.copyOf(e.needs().keySet()), "good.annocraft1800.")));
            if (!e.services().isEmpty()) c.append("\n").append(Component.translatable("tooltip.annocraft1800.services", names(e.services(), "building.annocraft1800.")));
            List<String> luxury = new ArrayList<>(); e.luxury().keySet().forEach(k -> luxury.add("good.annocraft1800." + k)); e.luxuryServices().forEach(k -> luxury.add("building.annocraft1800." + k));
            if (!luxury.isEmpty()) c.append("\n").append(Component.translatable("tooltip.annocraft1800.luxury", names(luxury, "")));
        }
        if (e.serviceProvider()) c.append("\n").append(Component.translatable("tooltip.annocraft1800.service", e.radius()));
        if (e.fertility() != null) c.append("\n").append(Component.translatable("tooltip.annocraft1800.fertility", Component.translatable("resource.annocraft1800." + e.fertility())));
        if (e.deposit() != null) c.append("\n").append(Component.translatable("tooltip.annocraft1800.deposit", Component.translatable("resource.annocraft1800." + e.deposit())));
        if (e.shipyard()) c.append("\n").append(Component.translatable("tooltip.annocraft1800.shipyard"));
        if (e.defense() > 0) c.append("\n").append(Component.translatable("tooltip.annocraft1800.defense", e.defense()));
        if (def.coastal()) c.append("\n").append(Component.translatable("tooltip.annocraft1800.coastal"));
        if (e.unlockTier() != null) c.append("\n").append(Component.translatable("tooltip.annocraft1800.unlock", e.unlockResidents(), Component.translatable("tier.annocraft1800." + e.unlockTier()))
                .withStyle(unlocked(e) ? net.minecraft.ChatFormatting.GRAY : net.minecraft.ChatFormatting.RED));
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
        return 3 + goods(island).size() + island.getCompound("workforce").getAllKeys().size();
    }
    private static Set<String> goods(CompoundTag island) {
        Set<String> goods = new TreeSet<>(island.getCompound("stock").getAllKeys()); goods.addAll(island.getCompound("rates").getAllKeys()); return goods;
    }
    static String islandName(String id) {
        if (id.startsWith("nw_island_")) return "NM " + id.substring(10);
        return id.startsWith("island_") ? id.substring(7) : id;
    }
    private static IslandLayout.Island islandData(String id) {
        for (IslandLayout l : ClientState.LAYOUTS.values()) { var i = l.island(id); if (i.isPresent()) return i.get(); }
        return null;
    }
    private static List<String> split(String list) { return list == null || list.isEmpty() ? List.of() : Arrays.asList(list.split(",")); }
    @Override public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        if (!overUi(mouseX, mouseY)) RtsController.pick(mouseX, mouseY, width, height);
        else RtsController.hover = null;
        CompoundTag economy = ClientState.economy;
        // Left: finances and catalogue.
        g.fill(0, 0, panel, height, 0xe6192730);
        g.fill(panel - 2, 0, panel, height, 0xffc9a85c);
        g.drawString(font, Component.literal("ANNOCRAFT"), 8, 5, 0xffdfc783, false);
        if (economy.getBoolean("sandbox")) g.drawString(font, font.plainSubstrByWidth(Component.translatable("screen.annocraft1800.sandbox").getString(), panel - 16), 8, 17, 0xffa2b6bf, false);
        else {
            long balance = Math.round(economy.getDouble("income") - economy.getDouble("upkeep"));
            g.drawString(font, font.plainSubstrByWidth(Component.translatable("screen.annocraft1800.balance", (long) Math.floor(economy.getDouble("coins")), (balance >= 0 ? "+" : "") + balance).getString(), panel - 16),
                    8, 17, balance >= 0 ? 0xff88d4a0 : 0xffff8b7a, false);
        }
        List<BuildingDefinition> list = catalogue(category);
        if (list.size() > rows()) g.drawString(font, (scroll + 1) + "-" + Math.min(list.size(), scroll + rows()) + "/" + list.size(), panel - 40, height - 52, 0xff8090a0, false);
        // Top right: island ownership, resources, stocks, flows and workforce.
        String islandId = currentIsland(); int right = width - SIDE - 6;
        g.fill(right, 6, width - 6, 12 + 10 * islandLines(), 0xd0192730);
        if (islandId == null) g.drawString(font, Component.translatable("screen.annocraft1800.ocean"), right + 4, 9, 0xffa2b6bf, false);
        else {
            CompoundTag island = ClientState.island(islandId); int y = 9;
            String owner = ClientState.owner(islandId);
            Component title = owner.isEmpty() ? Component.translatable("screen.annocraft1800.island_free", islandName(islandId))
                    : owner.equals(Diplomacy.PLAYER) ? Component.translatable("screen.annocraft1800.island_stock", islandName(islandId), island.getInt("capacity"))
                    : Component.translatable("screen.annocraft1800.island_owner", islandName(islandId), Component.translatable("faction.annocraft1800." + owner));
            g.drawString(font, font.plainSubstrByWidth(title.getString(), SIDE - 8), right + 4, y, 0xffdfc783, false);
            IslandLayout.Island data = islandData(islandId);
            if (data != null) {
                y += 10; g.drawString(font, font.plainSubstrByWidth(Component.translatable("screen.annocraft1800.fertility", names(split(data.fertility()), "resource.annocraft1800.")).getString(), SIDE - 8), right + 4, y, 0xffa2d18a, false);
                y += 10; g.drawString(font, font.plainSubstrByWidth(Component.translatable("screen.annocraft1800.deposits", names(split(data.deposit()), "resource.annocraft1800.")).getString(), SIDE - 8), right + 4, y, 0xffc9b38a, false);
            } else y += 20;
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
                    second = Component.translatable("screen.annocraft1800.supply", Math.round(site.getDouble("supply") * 100), Math.round(site.getDouble("luxury") * 100));
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
        g.drawString(font, font.plainSubstrByWidth(Component.translatable("screen.annocraft1800.stats", ClientState.visible().size(), (int)RtsController.zoom).getString(), width - panel - 16), panel + 8, height - 14, 0xffc1d1d7, false);
        super.render(g, mouseX, mouseY, partialTick);
    }
    @Override public boolean mouseClicked(double x, double y, int button) {
        if (super.mouseClicked(x, y, button)) return true;
        if (!overUi(x, y) && button == 0) { RtsController.pick(x, y, width, height); RtsController.clickWorld(); return true; }
        if (button == 1) { RtsController.placement = null; RtsController.roadMode = 0; RtsController.roadStart = null; ClientState.selected = null; return true; }
        return false;
    }
    @Override public boolean mouseScrolled(double x, double y, double delta) {
        if (x <= panel) {
            int before = scroll; scroll = Math.max(0, Math.min(catalogue(category).size() - rows(), scroll - (int) Math.signum(delta)));
            if (scroll != before) { clearWidgets(); init(); }
            return true;
        }
        RtsController.zoom = CameraMath.zoom(RtsController.zoom - (float)delta * 3); return true;
    }
    @Override public boolean keyPressed(int key, int scan, int modifiers) {
        if (key == GLFW.GLFW_KEY_R) { RtsController.rotation = (RtsController.rotation + 1) % 4; return true; }
        if (key == GLFW.GLFW_KEY_F6) { onClose(); return true; }
        if (RtsController.COLONY.matches(key, scan)) { openColony(); return true; }
        return super.keyPressed(key, scan, modifiers);
    }
    @Override public void tick() {
        // Unlocks change with population: refresh the catalogue when the economy snapshot changes.
        if (ClientState.economy != lastEconomy) { lastEconomy = ClientState.economy; clearWidgets(); init(); }
    }
    private CompoundTag lastEconomy;
    @Override public boolean isPauseScreen() { return false; }
    @Override public void onClose() { RtsController.exit(); super.onClose(); }
    @Override public void removed() { if (RtsController.active && !keepCamera) RtsController.exit(); }
}
