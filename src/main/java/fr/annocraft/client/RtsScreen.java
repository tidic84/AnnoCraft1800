package fr.annocraft.client;

import fr.annocraft.AnnoCraft;
import fr.annocraft.building.*;
import fr.annocraft.economy.*;
import fr.annocraft.network.AnnoNetwork;
import fr.annocraft.world.IslandLayout;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.*;
import net.minecraft.network.chat.*;
import org.lwjgl.glfw.GLFW;
import java.util.*;

/**
 * Management view, laid out after Anno 1800: resource bar on top, island goods below it, quest tracker on the
 * right, minimap bottom left, construction menu with building cards bottom centre and the object menu of the
 * selected building bottom right. The world stays clickable everywhere else.
 */
public final class RtsScreen extends Screen {
    static final List<String> CATEGORIES = List.of("infrastructure", "housing", "services", "farmers", "workers", "artisans", "engineers", "investors", "new_world");
    private static final int TOP = 18, TAB = 22, CARD = 44, OBJECT_W = 184;
    static String openCategory = "housing";
    private static int cardScroll;
    /** Set while a child screen (colony management, map) replaces this one, so the camera stays in RTS mode. */
    static boolean keepCamera;
    private CompoundTag lastEconomy;
    private final List<int[]> ui = new ArrayList<>();
    private final List<Object[]> tips = new ArrayList<>();
    private int minimap, barY, cardsY, trackerX, trackerW, trackerH, objectY, objectH;
    private int[] dialogueRect;
    private boolean rotating, draggingMap;
    public RtsScreen() { super(Component.translatable("screen.annocraft1800.title")); }

    static String category(BuildingDefinition d) {
        EconomyProfile e = d.economy();
        if (e.housing()) return "housing";
        if (e.storageNode() || e.shipyard() || e.defense() > 0 || e.booster()) return "infrastructure";
        if (e.serviceProvider()) return "services";
        if (IslandLayout.NEW_WORLD.equals(e.world())) return "new_world";
        return e.unlockTier() == null ? "farmers" : e.unlockTier();
    }
    static List<BuildingDefinition> catalogue(String cat) {
        return ClientState.DEFINITIONS.values().stream()
                .filter(d -> d.level() == 1 && d.economy().buildableIn(ClientState.world) && category(d).equals(cat)).toList();
    }
    static List<String> categories() { return CATEGORIES.stream().filter(c -> !catalogue(c).isEmpty()).toList(); }
    static boolean unlocked(EconomyProfile e) {
        CompoundTag eco = ClientState.economy;
        return eco.getBoolean("sandbox") || e.unlockTier() == null || eco.getCompound("population").getInt(e.unlockTier()) >= e.unlockResidents();
    }
    /** Whether the cost can be paid now: colony coins and the current island's stock. */
    static boolean affordable(Map<String, Integer> cost, String island) {
        CompoundTag eco = ClientState.economy; if (eco.getBoolean("sandbox")) return true;
        for (var e : cost.entrySet()) {
            double have = e.getKey().equals(EconomyProfile.COINS) ? eco.getDouble("coins")
                    : island == null ? 0 : ClientState.island(island).getCompound("stock").getDouble(e.getKey());
            if (have + 1e-6 < e.getValue()) return false;
        }
        return true;
    }

    @Override protected void init() {
        ui.clear();
        minimap = Math.max(64, Math.min(110, height / 3));
        trackerW = Math.min(170, width / 3); trackerX = width - trackerW - 4;
        // Top-right shortcuts.
        int bx = width - 2;
        bx -= 20; topButton(bx, "visit", "screen.annocraft1800.visit_help", this::onClose);
        bx -= 20; topButton(bx, "colony", "screen.annocraft1800.colony_help", this::openColony);
        bx -= 20; topButton(bx, "map", "screen.annocraft1800.map_help", this::openMap);
        // Construction tabs.
        List<String> cats = categories();
        if (openCategory != null && !cats.contains(openCategory)) openCategory = null;
        int count = cats.size() + 2, tabsW = count * (TAB + 2);
        int tx = Math.max(minimap + 12, (width - tabsW) / 2); barY = height - TAB - 4;
        ui.add(new int[]{tx - 3, barY - 3, tabsW + 4, TAB + 6});
        addTab(tx, "road", Component.translatable("screen.annocraft1800.road_help"), () -> road(1), () -> RtsController.roadMode == 1); tx += TAB + 2;
        addTab(tx, "road_remove", Component.translatable("screen.annocraft1800.road_remove_help"), () -> road(2), () -> RtsController.roadMode == 2); tx += TAB + 2;
        for (int i = 0; i < cats.size(); i++) {
            String cat = cats.get(i);
            addTab(tx, "cat:" + cat, Component.translatable("category.annocraft1800." + cat).append(" (" + (i + 1) + ")"),
                    () -> { openCategory = cat.equals(openCategory) ? null : cat; cardScroll = 0; rebuild(); }, () -> cat.equals(openCategory));
            tx += TAB + 2;
        }
        // Building cards of the open category.
        boolean selected = ClientState.BUILDINGS.containsKey(ClientState.selected);
        int right = selected ? width - OBJECT_W - 8 : width - 4, left = minimap + 12;
        cardsY = barY - CARD - 16;
        if (openCategory != null) {
            List<BuildingDefinition> list = catalogue(openCategory);
            int fit = Math.max(1, (right - left - 16) / (CARD + 2));
            cardScroll = Math.max(0, Math.min(cardScroll, list.size() - fit));
            int shown = Math.min(fit, list.size() - cardScroll), cx = Math.max(left + 8, (left + right) / 2 - shown * (CARD + 2) / 2);
            ui.add(new int[]{cx - 6, cardsY - 12, shown * (CARD + 2) + 10, CARD + 14});
            for (BuildingDefinition def : list.subList(cardScroll, cardScroll + shown)) { addRenderableWidget(new Card(cx, cardsY, def)); cx += CARD + 2; }
            if (cardScroll > 0) addRenderableWidget(new UiKit.AnnoButton(left, cardsY + CARD / 2 - 8, 10, 16, Component.literal("‹"), null, () -> { cardScroll--; rebuild(); }));
            if (cardScroll + shown < list.size()) addRenderableWidget(new UiKit.AnnoButton(cx + 2, cardsY + CARD / 2 - 8, 10, 16, Component.literal("›"), null, () -> { cardScroll++; rebuild(); }));
        }
        // Object menu actions.
        objectH = 118; objectY = barY - objectH - 6;
        if (selected) {
            int ox = width - OBJECT_W - 4, y = objectY + objectH - 20, w = (OBJECT_W - 12) / 3;
            BuildingInstance b = ClientState.BUILDINGS.get(ClientState.selected);
            BuildingDefinition def = ClientState.DEFINITIONS.get(b.definition());
            var up = addRenderableWidget(new UiKit.AnnoButton(ox + 4, y, w, 16, Component.translatable("screen.annocraft1800.upgrade"), null, () -> command(2)));
            up.active = def != null && def.upgrade() != null;
            if (up.active && ClientState.DEFINITIONS.containsKey(def.upgrade())) up.setTooltip(Tooltip.create(describe(ClientState.DEFINITIONS.get(def.upgrade()))));
            addRenderableWidget(new UiKit.AnnoButton(ox + 6 + w, y, w, 16, Component.translatable("screen.annocraft1800.copy"), null, this::copy))
                    .setTooltip(Tooltip.create(Component.translatable("screen.annocraft1800.copy_help")));
            addRenderableWidget(new UiKit.AnnoButton(ox + 8 + 2 * w, y, w, 16, Component.translatable("screen.annocraft1800.demolish"), null, () -> command(1)))
                    .setTooltip(Tooltip.create(Component.translatable("screen.annocraft1800.demolish_help")));
        }
    }
    private void topButton(int x, String icon, String help, Runnable action) {
        addRenderableWidget(new UiKit.AnnoButton(x, 1, 18, 16, Component.empty(), UiKit.item(icon), action)).setTooltip(Tooltip.create(Component.translatable(help)));
    }
    private void addTab(int x, String icon, Component tip, Runnable action, java.util.function.Supplier<Boolean> on) {
        addRenderableWidget(new UiKit.AnnoButton(x, barY, TAB, TAB, Component.empty(), UiKit.item(icon), action).highlight(on)).setTooltip(Tooltip.create(tip));
    }
    private void rebuild() { clearWidgets(); init(); }
    void openColony() { openChild(new ColonyScreen(this)); }
    void openMap() { openChild(new StrategicMapScreen(this)); }
    void openChild(Screen child) { keepCamera = true; minecraft.setScreen(child); keepCamera = false; }
    private static void road(int mode) {
        RtsController.roadMode = RtsController.roadMode == mode ? 0 : mode; RtsController.roadStart = null; RtsController.placement = null; ClientState.selected = null;
    }
    private void command(int action) {
        if (ClientState.selected != null) AnnoNetwork.CHANNEL.sendToServer(new AnnoNetwork.BuildCommand(action, AnnoCraft.id("residence"), BlockPos.ZERO, 0, ClientState.selected));
        if (action == 1) { ClientState.selected = null; rebuild(); }
    }
    private void copy() {
        BuildingInstance b = ClientState.BUILDINGS.get(ClientState.selected);
        BuildingDefinition def = b == null ? null : ClientState.DEFINITIONS.get(b.definition());
        for (int guard = 0; def != null && def.level() > 1 && guard < 8; guard++) {
            BuildingDefinition current = def;
            def = ClientState.DEFINITIONS.values().stream().filter(d -> current.id().equals(d.upgrade())).findFirst().orElse(null);
        }
        if (def != null && unlocked(def.economy())) { RtsController.placement = def; RtsController.rotation = b.rotation(); ClientState.selected = null; rebuild(); }
    }

    // ---- text helpers shared with other screens ----
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
        if (e.booster()) {
            c.append("\n").append(Component.translatable("tooltip.annocraft1800.boost", Math.round(e.boost() * 100), e.boostRadius()));
            if (!e.boostInputs().isEmpty()) c.append("\n").append(Component.translatable("tooltip.annocraft1800.boost_input", amounts(e.boostInputs())));
        }
        if (def.coastal()) c.append("\n").append(Component.translatable("tooltip.annocraft1800.coastal"));
        if (e.unlockTier() != null) c.append("\n").append(Component.translatable("tooltip.annocraft1800.unlock", e.unlockResidents(), Component.translatable("tier.annocraft1800." + e.unlockTier()))
                .withStyle(unlocked(e) ? net.minecraft.ChatFormatting.GRAY : net.minecraft.ChatFormatting.RED));
        return c;
    }
    static String islandName(String id) {
        if (id.startsWith("nw_island_")) return "NM " + id.substring(10);
        return id.startsWith("island_") ? id.substring(7) : id;
    }
    static IslandLayout.Island islandData(String id) {
        for (IslandLayout l : ClientState.LAYOUTS.values()) { var i = l.island(id); if (i.isPresent()) return i.get(); }
        return null;
    }
    static String currentIsland() {
        BuildingInstance selected = ClientState.BUILDINGS.get(ClientState.selected);
        if (selected != null) return selected.island();
        return ClientState.layout == null ? null : ClientState.layout.islandAt((int) RtsController.x, (int) RtsController.z).map(i -> i.id()).orElse(null);
    }

    private boolean overUi(double x, double y) {
        for (int[] r : ui) if (x >= r[0] && y >= r[1] && x < r[0] + r[2] && y < r[1] + r[3]) return true;
        return false;
    }
    private void tip(int x, int y, int w, int h, Component text) { tips.add(new Object[]{new int[]{x, y, w, h}, text}); }

    @Override public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        Hud.watchMission();
        tips.clear();
        // Hit areas: fixed ones come from init, the others were recorded by the previous frame.
        if (RtsController.forcedHover != null || !overUi(mouseX, mouseY)) RtsController.pick(mouseX, mouseY, width, height);
        else RtsController.hover = null;
        ui.removeIf(r -> r.length == 5);
        CompoundTag eco = ClientState.economy;
        String island = currentIsland();
        // ---- top bar ----
        g.fill(0, 0, width, TOP, UiKit.PANEL); g.fill(0, TOP - 1, width, TOP, UiKit.GOLD);
        int x = 4;
        UiKit.icon(g, UiKit.item("coins"), x, 1, 14); x += 16;
        if (eco.getBoolean("sandbox")) { UiKit.text(g, Component.translatable("screen.annocraft1800.sandbox"), x, 5, UiKit.MUTED, 110); x += font.width(Component.translatable("screen.annocraft1800.sandbox")) + 8; }
        else {
            long coins = (long) Math.floor(eco.getDouble("coins")), balance = Math.round(eco.getDouble("income") - eco.getDouble("upkeep"));
            String money = String.format(Locale.ROOT, "%,d", coins).replace(',', ' ');
            g.drawString(font, money, x, 5, UiKit.GOLD_TEXT, false); x += font.width(money) + 3;
            String flow = (balance >= 0 ? "+" : "") + balance;
            g.drawString(font, flow, x, 5, balance >= 0 ? UiKit.GOOD : UiKit.BAD, false);
            tip(4, 0, x + font.width(flow) - 4, TOP, Component.translatable("colony.annocraft1800.income", Math.round(eco.getDouble("income")), Math.round(eco.getDouble("upkeep")), balance));
            x += font.width(flow) + 10;
        }
        CompoundTag pop = eco.getCompound("population");
        for (String tier : ColonyEconomy.TIERS) {
            int n = pop.getInt(tier); if (n <= 0) continue;
            UiKit.icon(g, UiKit.item("tier:" + tier), x, 2, 12);
            g.drawString(font, String.valueOf(n), x + 14, 5, UiKit.TEXT, false);
            int w = 16 + font.width(String.valueOf(n));
            tip(x, 0, w, TOP, Component.translatable("tier.annocraft1800." + tier).append(" : " + n)); x += w + 6;
        }
        if (island != null) {
            String owner = ClientState.owner(island);
            Component name = Component.translatable("screen.annocraft1800.island_title", islandName(island));
            if (!owner.isEmpty() && !owner.equals(Diplomacy.PLAYER)) name = Component.translatable("screen.annocraft1800.island_owner", islandName(island), Component.translatable("faction.annocraft1800." + owner));
            int cx = Math.max(x + font.width(name) / 2 + 4, width / 2);
            if (cx + font.width(name) / 2 < width - 70) UiKit.centered(g, name, cx, 5, UiKit.GOLD_TEXT);
        }
        // ---- island goods strip ----
        int stripW = trackerX - 8;
        if (island != null) stripH = goods(g, island, 4, TOP + 2, stripW); else stripH = 0;
        // ---- quest tracker ----
        trackerH = Hud.tracker(g, trackerX, TOP + 2, trackerW, true);
        dyn(trackerX, TOP + 2, trackerW, trackerH);
        // ---- notifications ----
        Hud.notices(g, 4, TOP + 6 + stripH, Math.min(190, width / 3));
        // ---- minimap ----
        int my = height - minimap - 4;
        UiKit.panel(g, 2, my - 2, minimap + 4, minimap + 4);
        MapView.draw(g, ClientState.world, 4, my, minimap, false);
        dyn(2, my - 2, minimap + 4, minimap + 4);
        tip(4, my, minimap, minimap, Component.translatable("screen.annocraft1800.minimap_help"));
        dialogueRect = Hud.dialogue(g, 4, my - 6, Math.min(260, width / 2));
        if (dialogueRect != null) dyn(dialogueRect[0], dialogueRect[1], dialogueRect[2], dialogueRect[3]);
        // ---- construction menu backgrounds ----
        for (int[] r : ui) if (r.length == 4) UiKit.panel(g, r[0], r[1], r[2], r[3]);
        if (openCategory != null) UiKit.text(g, Component.translatable("category.annocraft1800." + openCategory), cardsTitleX(), cardsY - 10, UiKit.GOLD_TEXT, 200);
        // ---- object menu ----
        BuildingInstance selected = ClientState.BUILDINGS.get(ClientState.selected);
        if (selected != null) objectMenu(g, selected, mouseX, mouseY);
        // ---- tool hint and cursor cost ----
        if (RtsController.placement != null || RtsController.roadMode != 0) {
            Component hint = RtsController.placement != null
                    ? Component.translatable(RtsController.placement.name()).append(" · ").append(Component.translatable("screen.annocraft1800.place"))
                    : Component.translatable(RtsController.roadMode == 1 ? "screen.annocraft1800.road_place" : "screen.annocraft1800.road_erase");
            // Just above the construction menu, where the eye already is.
            int w = Math.min(width - 2 * minimap - 24, font.width(hint) + 12), hy = (openCategory != null ? cardsY - 14 : barY - 6) - 16;
            UiKit.panel(g, width / 2 - w / 2, hy, w, 14);
            UiKit.centered(g, Component.literal(font.plainSubstrByWidth(hint.getString(), w - 8)), width / 2, hy + 3, UiKit.TEXT);
        }
        if (RtsController.placement != null && RtsController.hover != null) cursorCost(g, RtsController.placement, island, mouseX, mouseY);
        super.render(g, mouseX, mouseY, partialTick);
        for (Object[] t : tips) {
            int[] r = (int[]) t[0];
            if (mouseX >= r[0] && mouseY >= r[1] && mouseX < r[0] + r[2] && mouseY < r[1] + r[3]) { g.renderTooltip(font, font.split((Component) t[1], 220), mouseX, mouseY); break; }
        }
    }
    private int stripH;
    private int cardsTitleX() { for (int[] r : ui) if (r.length == 4 && r[1] == cardsY - 12) return r[0] + 4; return minimap + 16; }
    private void dyn(int x, int y, int w, int h) { ui.add(new int[]{x, y, w, h, 1}); }

    /** Island goods as icons with amounts, then workforce. @return the strip height */
    private int goods(GuiGraphics g, String island, int x, int y, int maxW) {
        CompoundTag i = ClientState.island(island), stock = i.getCompound("stock"), rates = i.getCompound("rates");
        Set<String> ids = new TreeSet<>(stock.getAllKeys()); ids.addAll(rates.getAllKeys());
        List<String> sorted = new ArrayList<>(ids); sorted.sort(Comparator.comparingDouble((String k) -> -stock.getDouble(k)));
        CompoundTag workforce = i.getCompound("workforce");
        if (sorted.isEmpty() && workforce.isEmpty()) return 0;
        int cell = 34, perRow = Math.max(1, (maxW - 8) / cell), rows = (sorted.size() + perRow - 1) / perRow;
        int lines = Math.min(2, Math.max(1, rows)), h = lines * 16 + (workforce.isEmpty() ? 0 : 12) + 4;
        int w = Math.min(maxW, Math.max(Math.min(sorted.size(), perRow) * cell, 120) + 8);
        UiKit.panel(g, x, y, w, h); dyn(x, y, w, h);
        int cap = i.getInt("capacity");
        for (int n = 0; n < sorted.size() && n < perRow * lines; n++) {
            String good = sorted.get(n);
            int gx = x + 4 + (n % perRow) * cell, gy = y + 2 + (n / perRow) * 16;
            double amount = stock.getDouble(good), rate = rates.getDouble(good);
            UiKit.icon(g, UiKit.item(good), gx, gy, 14);
            g.drawString(font, String.valueOf((long) Math.floor(amount + 1e-6)), gx + 15, gy + 4, amount < 1 ? UiKit.BAD : rate < -.05 ? UiKit.WARN : UiKit.TEXT, false);
            tip(gx, gy, cell, 16, good(good).withStyle(net.minecraft.ChatFormatting.GOLD).append("\n")
                    .append(Component.translatable("screen.annocraft1800.good_tip", (long) Math.floor(amount + 1e-6), cap, String.format(Locale.ROOT, "%+.1f", rate))));
        }
        int wx = x + 4, wy = y + 2 + lines * 16;
        for (String tier : workforce.getAllKeys()) {
            int[] wf = workforce.getIntArray(tier); if (wf.length < 2) continue;
            UiKit.icon(g, UiKit.item("tier:" + tier), wx, wy, 10);
            String s = wf[0] + "/" + wf[1];
            g.drawString(font, s, wx + 12, wy + 1, wf[0] >= wf[1] ? UiKit.MUTED : UiKit.BAD, false);
            tip(wx, wy, 14 + font.width(s), 10, Component.translatable("screen.annocraft1800.workforce", Component.translatable("tier.annocraft1800." + tier), wf[0], wf[1]));
            wx += 18 + font.width(s);
        }
        return h;
    }

    /** Anno-style object menu: production chain or needs of the selected building. */
    private void objectMenu(GuiGraphics g, BuildingInstance b, int mouseX, int mouseY) {
        BuildingDefinition def = ClientState.DEFINITIONS.get(b.definition()); if (def == null) return;
        int x = width - OBJECT_W - 4, y = objectY, w = OBJECT_W;
        UiKit.panel(g, x, y, w, objectH); dyn(x, y, w, objectH);
        BuildingPreview.gui(g, def, x + 3, y + 3, 26, (System.currentTimeMillis() % 36000) / 100f);
        UiKit.text(g, Component.translatable(def.name()), x + 32, y + 4, UiKit.GOLD_TEXT, w - 36);
        UiKit.text(g, Component.translatable("screen.annocraft1800.island_level", islandName(b.island()), def.level()), x + 32, y + 14, UiKit.MUTED, w - 36);
        CompoundTag site = ClientState.SITES.get(b.id()); EconomyProfile e = def.economy();
        int ly = y + 32;
        if (site != null) {
            int status = Math.max(0, Math.min(site.getByte("status"), ColonyEconomy.Status.values().length - 1));
            ColonyEconomy.Status s = ColonyEconomy.Status.values()[status];
            UiKit.text(g, Component.translatable("status.annocraft1800." + s.name().toLowerCase(Locale.ROOT)), x + 4, ly, s == ColonyEconomy.Status.OK ? UiKit.GOOD : UiKit.BAD, w - 8);
            ly += 11;
            CompoundTag stock = ClientState.island(b.island()).getCompound("stock");
            if (e.producer()) {
                // Inputs → outputs, like Anno's production chain line.
                int cx = x + 6;
                for (String in : e.inputs().keySet()) { cx = chainIcon(g, in, cx, ly, stock.getDouble(in) >= 1); }
                if (!e.inputs().isEmpty()) { g.drawString(font, "→", cx + 1, ly + 4, UiKit.GOLD, false); cx += 10; }
                for (String out : e.outputs().keySet()) cx = chainIcon(g, out, cx, ly, true);
                UiKit.text(g, Component.translatable("screen.annocraft1800.cycle_seconds", e.cycle()), cx + 4, ly + 4, UiKit.MUTED, x + w - cx - 8);
                ly += 20;
                UiKit.text(g, Component.translatable("screen.annocraft1800.productivity", Math.round(site.getDouble("productivity") * 100)), x + 4, ly, UiKit.TEXT, w - 8); ly += 10;
                UiKit.bar(g, x + 4, ly, w - 8, 4, Math.min(1, site.getDouble("productivity") / 2), site.getDouble("productivity") >= 1 ? UiKit.GOOD : UiKit.WARN); ly += 6;
                UiKit.bar(g, x + 4, ly, w - 8, 2, site.getDouble("progress"), UiKit.GOLD); ly += 6;
                if (e.workforce() > 0) {
                    UiKit.icon(g, UiKit.item("tier:" + e.workTier()), x + 4, ly, 10);
                    UiKit.text(g, Component.translatable("tooltip.annocraft1800.workforce", e.workforce(), Component.translatable("tier.annocraft1800." + e.workTier())), x + 16, ly + 1, UiKit.MUTED, w - 20);
                }
            } else if (e.housing()) {
                int residents = (int) Math.floor(site.getDouble("residents") + 1e-6);
                UiKit.text(g, Component.translatable("screen.annocraft1800.residents", residents, e.capacity()), x + 4, ly, UiKit.TEXT, w - 8); ly += 10;
                UiKit.bar(g, x + 4, ly, w - 8, 4, (double) residents / e.capacity(), UiKit.GOOD); ly += 7;
                List<String> covered = new ArrayList<>(); site.getList("services", Tag.TAG_STRING).forEach(t -> covered.add(t.getAsString()));
                int cx = x + 4;
                for (String need : e.needs().keySet()) cx = chainIcon(g, need, cx, ly, stock.getDouble(need) >= 1 && site.getBoolean("connected"));
                for (String service : e.services()) cx = serviceIcon(g, service, cx, ly, covered.contains(service));
                ly += 18;
                int lx = x + 4;
                for (String lux : e.luxury().keySet()) lx = chainIcon(g, lux, lx, ly, stock.getDouble(lux) >= 1 && site.getBoolean("connected"));
                for (String service : e.luxuryServices()) lx = serviceIcon(g, service, lx, ly, covered.contains(service));
                if (lx > x + 4) UiKit.text(g, Component.translatable("screen.annocraft1800.luxury_short", Math.round(site.getDouble("luxury") * 100)), lx + 2, ly + 4, UiKit.MUTED, x + w - lx - 6);
            } else if (e.storageNode()) UiKit.text(g, Component.translatable("tooltip.annocraft1800.storage", e.storage()), x + 4, ly, UiKit.TEXT, w - 8);
            else if (e.serviceProvider()) UiKit.text(g, Component.translatable("tooltip.annocraft1800.service", e.radius()), x + 4, ly, UiKit.TEXT, w - 8);
            else if (e.booster()) UiKit.text(g, Component.translatable("tooltip.annocraft1800.boost", Math.round(e.boost() * 100), e.boostRadius()), x + 4, ly, UiKit.TEXT, w - 8);
        }
    }
    private int chainIcon(GuiGraphics g, String good, int x, int y, boolean ok) {
        g.fill(x, y, x + 16, y + 16, ok ? 0x40406a40 : 0x60803030);
        UiKit.icon(g, UiKit.item(good), x, y, 16);
        g.fill(x, y + 15, x + 16, y + 16, ok ? UiKit.GOOD : UiKit.BAD);
        tip(x, y, 16, 16, good(good).append(ok ? " ✔" : " ✘"));
        return x + 18;
    }
    private int serviceIcon(GuiGraphics g, String service, int x, int y, boolean ok) {
        g.fill(x, y, x + 16, y + 16, ok ? 0x40406a40 : 0x60803030);
        UiKit.icon(g, UiKit.item("cat:services"), x, y, 16);
        g.fill(x, y + 15, x + 16, y + 16, ok ? UiKit.GOOD : UiKit.BAD);
        tip(x, y, 16, 16, Component.translatable("building.annocraft1800." + service).append(ok ? " ✔" : " ✘"));
        return x + 18;
    }
    /** Cost of the building being placed, next to the cursor, red where the island cannot pay. */
    private void cursorCost(GuiGraphics g, BuildingDefinition def, String island, int mx, int my) {
        Map<String, Integer> cost = def.economy().cost(); if (cost.isEmpty()) return;
        int w = cost.size() * 34 + 4, x = Math.min(width - w - 2, mx + 12), y = Math.min(height - 20, my + 12);
        UiKit.panel(g, x, y, w, 18);
        int cx = x + 3;
        for (var e : cost.entrySet()) {
            boolean ok = affordable(Map.of(e.getKey(), e.getValue()), island);
            UiKit.icon(g, UiKit.item(e.getKey()), cx, y + 2, 14);
            g.drawString(font, String.valueOf(e.getValue()), cx + 15, y + 6, ok ? UiKit.TEXT : UiKit.BAD, false);
            cx += 34;
        }
    }

    @Override public boolean mouseClicked(double x, double y, int button) {
        if (super.mouseClicked(x, y, button)) return true;
        int my = height - minimap - 4;
        if (button == 0 && x >= 4 && y >= my && x < 4 + minimap && y < my + minimap) { draggingMap = true; mapJump(x, y); return true; }
        if (button == 0 && x >= trackerX && y >= TOP + 2 && x < trackerX + trackerW && y < TOP + 2 + trackerH) {
            CompoundTag c = Hud.campaign();
            if (y < TOP + 15) Hud.toggleTracker();
            else if (!c.getBoolean("active")) AnnoNetwork.action("campaign_start");
            else { ColonyScreen.tab = "campaign"; openColony(); }
            return true;
        }
        if (dialogueRect != null && x >= dialogueRect[0] && y >= dialogueRect[1] && x < dialogueRect[0] + dialogueRect[2] && y < dialogueRect[1] + dialogueRect[3]) { Hud.closeDialogue(); return true; }
        if (button == 2) { rotating = true; return true; }
        if (overUi(x, y)) return true;
        if (button == 0) {
            RtsController.pick(x, y, width, height); RtsController.clickWorld();
            if (RtsController.placement == null && RtsController.roadMode == 0) rebuild();
            return true;
        }
        if (button == 1) { cancelTool(); return true; }
        return false;
    }
    private void mapJump(double x, double y) {
        IslandLayout layout = ClientState.layout; if (layout == null) return;
        double[] w = MapView.toWorld(layout, x, y, 4, height - minimap - 4, minimap);
        int half = layout.size() / 2 - 8;
        RtsController.jump(Math.max(-half, Math.min(half, w[0])), Math.max(-half, Math.min(half, w[1])));
    }
    @Override public boolean mouseDragged(double x, double y, int button, double dx, double dy) {
        if (rotating && button == 2) { RtsController.rotateBy(dx); return true; }
        if (draggingMap && button == 0) { mapJump(Math.max(4, Math.min(3 + minimap, x)), Math.max(height - minimap - 4, Math.min(height - 5, y))); return true; }
        return super.mouseDragged(x, y, button, dx, dy);
    }
    @Override public boolean mouseReleased(double x, double y, int button) {
        rotating = false; draggingMap = false;
        // Roads are drawn by dragging, as in Anno: release ends the segment.
        if (button == 0 && RtsController.roadMode != 0 && RtsController.roadStart != null && !overUi(x, y)) {
            RtsController.pick(x, y, width, height); RtsController.releaseRoad();
        }
        return super.mouseReleased(x, y, button);
    }
    private void cancelTool() {
        RtsController.placement = null; RtsController.roadMode = 0; RtsController.roadStart = null; ClientState.selected = null; rebuild();
    }
    @Override public boolean mouseScrolled(double x, double y, double delta) {
        if (openCategory != null && y >= cardsY - 12 && y < cardsY + CARD + 2 && x > minimap + 8) {
            cardScroll = Math.max(0, cardScroll - (int) Math.signum(delta)); rebuild(); return true;
        }
        float before = RtsController.zoomTarget;
        RtsController.zoomTarget = CameraMath.zoom(RtsController.zoomTarget - (float) delta * Math.max(2, RtsController.zoomTarget * .14f));
        // Scrolling out past the widest city view opens the strategic map, as in Anno.
        if (delta < 0 && before >= CameraMath.MAX_ZOOM) openMap();
        return true;
    }
    @Override public boolean keyPressed(int key, int scan, int modifiers) {
        if (key == GLFW.GLFW_KEY_ESCAPE && (RtsController.placement != null || RtsController.roadMode != 0 || ClientState.selected != null)) { cancelTool(); return true; }
        if (key == GLFW.GLFW_KEY_R) { RtsController.rotation = (RtsController.rotation + 1) % 4; return true; }
        if (key == GLFW.GLFW_KEY_F6) { onClose(); return true; }
        if (key == GLFW.GLFW_KEY_M) { openMap(); return true; }
        if (RtsController.COLONY.matches(key, scan)) { openColony(); return true; }
        if (key == GLFW.GLFW_KEY_C && ClientState.selected != null) { copy(); return true; }
        if (key == GLFW.GLFW_KEY_DELETE && ClientState.selected != null) { command(1); return true; }
        if (key >= GLFW.GLFW_KEY_1 && key <= GLFW.GLFW_KEY_9) {
            List<String> cats = categories(); int i = key - GLFW.GLFW_KEY_1;
            if (i < cats.size()) { openCategory = cats.get(i).equals(openCategory) ? null : cats.get(i); cardScroll = 0; rebuild(); }
            return true;
        }
        return super.keyPressed(key, scan, modifiers);
    }
    @Override public void tick() {
        // Unlocks, affordability and the object menu follow the live economy.
        if (ClientState.economy != lastEconomy) { lastEconomy = ClientState.economy; rebuild(); }
    }
    @Override public boolean isPauseScreen() { return false; }
    @Override public void renderBackground(GuiGraphics g) { }
    @Override public void onClose() { RtsController.exit(); super.onClose(); }
    @Override public void removed() { if (RtsController.active && !keepCamera) RtsController.exit(); }

    /** A building card: spinning miniature, lock and affordability, full description as tooltip. */
    private final class Card extends AbstractButton {
        private final BuildingDefinition def;
        Card(int x, int y, BuildingDefinition def) {
            super(x, y, CARD, CARD, Component.translatable(def.name())); this.def = def;
            active = unlocked(def.economy());
            setTooltip(Tooltip.create(describe(def)));
        }
        @Override public void onPress() {
            RtsController.placement = def; RtsController.rotation = 0; RtsController.roadMode = 0; RtsController.roadStart = null; ClientState.selected = null;
        }
        @Override protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
            boolean chosen = RtsController.placement == def, hover = isHoveredOrFocused();
            boolean pay = affordable(def.economy().cost(), currentIsland());
            g.fill(getX(), getY(), getX() + width, getY() + height, chosen ? 0xf0384a5a : hover ? 0xf02c3d4c : UiKit.PANEL_LIGHT);
            int edge = chosen ? UiKit.GOLD : !active ? 0xff3a3a3a : !pay ? 0xff9a4a40 : hover ? UiKit.GOLD : 0xff5b4a28;
            MapView.frame(g, getX(), getY(), width, height, edge);
            float spin = hover || chosen ? (System.currentTimeMillis() % 36000) / 60f : 0;
            BuildingPreview.gui(g, def, getX() + 2, getY() + 1, width - 4, spin);
            if (!active) {
                g.fill(getX() + 1, getY() + 1, getX() + width - 1, getY() + height - 1, 0xa0101418);
                if (def.economy().unlockTier() != null) UiKit.icon(g, UiKit.item("tier:" + def.economy().unlockTier()), getX() + width / 2 - 6, getY() + height / 2 - 6, 12);
            }
        }
        @Override protected void updateWidgetNarration(NarrationElementOutput output) { defaultButtonNarrationText(output); }
    }
}
