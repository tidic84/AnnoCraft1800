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
 * Management view, laid out like Anno 1800's HUD:
 * <ul>
 * <li>top left, the treasury: coins, balance and population;</li>
 * <li>top centre, the island bar with its construction materials over the island's name plate, which opens the
 *     island storage;</li>
 * <li>left edge, the quest book, colony, fleet, trade and diplomacy buttons; the quest panel beside them;</li>
 * <li>bottom left, the minimap under the island's fertilities and deposits;</li>
 * <li>bottom centre, the construction menu: tools and logistics on top, one row of buildings, the population
 *     tabs below (1–9);</li>
 * <li>bottom right, the information panel of the hovered building card or of the selected building.</li>
 * </ul>
 */
public final class RtsScreen extends Screen {
    private static final int TOP = 18, CARD = 26, INFO_W = 168, STRIP = 20, TOOL = 15, TAB_H = 12;
    /** The player's portrait in the top left corner, as Anno's medallion; the left strip starts under it. */
    private static final int PORTRAIT = 36, LEFT = PORTRAIT + 3;
    private static final List<String> MATERIALS = List.of("timber", "bricks", "steel_beams", "windows");
    /** Selected population tab of the construction menu. */
    static String openCategory;
    private static int cardScroll;
    private static boolean questOpen = true, storageOpen;
    /** Final good of the production chain whose menu is open above the construction row, or null. */
    static String openGroup;
    private int[] popup;
    private Entry hoveredGroup;
    /** Set while a child screen (colony management, map) replaces this one, so the camera stays in RTS mode. */
    static boolean keepCamera;
    private CompoundTag lastEconomy;
    /** Hit areas of the interface: fixed ones from init (4 values), the others recorded each frame (5 values). */
    private final List<int[]> ui = new ArrayList<>();
    private final List<Object[]> tips = new ArrayList<>();
    private int minimap, menuX, menuY, menuW, menuH, infoX, infoY, infoH, questW, questH;
    private int[] dialogueRect, plateRect;
    private BuildingDefinition hoveredCard;
    private boolean rotating, draggingMap;
    public RtsScreen() { super(Component.translatable("screen.annocraft1800.title")); }

    // ---------------------------------------------------------------- catalogue

    /** Population tabs of the current world. */
    static List<String> tabs() {
        return IslandLayout.NEW_WORLD.equals(ClientState.world) ? List.of("laborers", "overseers") : List.of("farmers", "workers", "artisans", "engineers", "investors");
    }
    /** The tab a building belongs to, as in Anno: residences with their residents, the rest with the tier unlocking them. */
    static String category(BuildingDefinition d) {
        EconomyProfile e = d.economy();
        if (e.housing()) return e.houseTier();
        if (e.storageNode() || e.shipyard() || e.defense() > 0 || e.booster()) return "infrastructure";
        List<String> tabs = tabs();
        if (e.unlockTier() == null) return tabs.get(0);
        // New World buildings unlocked by Old World tiers belong to its upper tab.
        return tabs.contains(e.unlockTier()) ? e.unlockTier() : tabs.get(tabs.size() - 1);
    }
    /** Buildings of a tab: residence first, then public buildings, then production. */
    static List<BuildingDefinition> catalogue(String cat) {
        return ClientState.DEFINITIONS.values().stream()
                .filter(d -> d.level() == 1 && d.economy().buildableIn(ClientState.world) && category(d).equals(cat))
                .sorted(Comparator.comparingInt(d -> d.economy().housing() ? 0 : d.economy().serviceProvider() ? 1 : 2)).toList();
    }
    /** A slot of the construction row: one building, or a production chain shown by its final good (good != null). */
    record Entry(String good, List<BuildingDefinition> members) { boolean group() { return good != null; } }
    /**
     * The construction row of a tab, organised as in Anno: residences and public buildings one by one, then the
     * production chains, each behind the good it ends in (timber: lumberjack, sawmill). A building feeding several
     * chains of the tab appears in each, as grain farms do for bread and beer.
     */
    static List<Entry> entries(String tab) {
        List<BuildingDefinition> list = catalogue(tab), producers = list.stream().filter(d -> d.economy().producer()).toList();
        List<Entry> result = new ArrayList<>();
        for (BuildingDefinition d : list) if (!d.economy().producer()) result.add(new Entry(null, List.of(d)));
        Map<BuildingDefinition, Set<String>> finals = new HashMap<>();
        Map<String, List<BuildingDefinition>> chains = new LinkedHashMap<>();
        for (BuildingDefinition p : producers) for (String good : finals(p, producers, finals, new HashSet<>())) chains.computeIfAbsent(good, k -> new ArrayList<>()).add(p);
        Map<BuildingDefinition, Integer> depth = new HashMap<>();
        for (var chain : chains.entrySet()) {
            List<BuildingDefinition> members = new ArrayList<>(chain.getValue());
            // Raw materials first, the final workshop last.
            members.sort(Comparator.comparingInt((BuildingDefinition d) -> -depth(d, producers, depth, new HashSet<>())));
            result.add(members.size() == 1 ? new Entry(null, members) : new Entry(chain.getKey(), List.copyOf(members)));
        }
        return result;
    }
    private static Set<String> finals(BuildingDefinition p, List<BuildingDefinition> producers, Map<BuildingDefinition, Set<String>> memo, Set<BuildingDefinition> visiting) {
        if (memo.containsKey(p)) return memo.get(p);
        Set<String> result = new LinkedHashSet<>();
        if (!visiting.add(p)) { result.addAll(p.economy().outputs().keySet()); return result; }
        for (String good : p.economy().outputs().keySet()) {
            List<BuildingDefinition> consumers = producers.stream().filter(c -> c != p && c.economy().inputs().containsKey(good)).toList();
            if (consumers.isEmpty()) result.add(good);
            else for (BuildingDefinition c : consumers) result.addAll(finals(c, producers, memo, visiting));
        }
        memo.put(p, result);
        return result;
    }
    private static int depth(BuildingDefinition p, List<BuildingDefinition> producers, Map<BuildingDefinition, Integer> memo, Set<BuildingDefinition> visiting) {
        if (memo.containsKey(p)) return memo.get(p);
        if (!visiting.add(p)) return 0;
        int d = 0;
        for (String good : p.economy().outputs().keySet())
            for (BuildingDefinition c : producers) if (c != p && c.economy().inputs().containsKey(good)) d = Math.max(d, 1 + depth(c, producers, memo, visiting));
        memo.put(p, d);
        return d;
    }
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
    /** Icon of a fertility or deposit: the good it yields. */
    static String resourceIcon(String resource) {
        return switch (resource) {
            case "potato" -> "potatoes"; case "plantain" -> "plantains"; case "sugar" -> "sugar_cane"; case "coffee" -> "coffee_beans";
            case "quartz" -> "sand"; default -> resource;
        };
    }

    // ---------------------------------------------------------------- layout

    @Override protected void init() {
        ui.clear(); popup = null;
        minimap = Math.max(60, Math.min(100, (int) (height / 3.2)));
        // Top right: strategic map, colony statistics, visit.
        int bx = width - 2;
        bx -= 20; topButton(bx, "visit", "screen.annocraft1800.visit_help", this::onClose);
        bx -= 20; topButton(bx, "colony", "screen.annocraft1800.colony_help", this::openColony);
        bx -= 20; topButton(bx, "map", "screen.annocraft1800.map_help", this::openMap);
        // Camera controls, named after the keys of the player's own keyboard layout (ZQSD and A/E on AZERTY).
        bx -= 20; addRenderableWidget(new UiKit.AnnoButton(bx, 1, 18, 16, Component.literal("?"), null, () -> { }))
                .setTooltip(Tooltip.create(Component.translatable("screen.annocraft1800.controls", key(GLFW.GLFW_KEY_W), key(GLFW.GLFW_KEY_A), key(GLFW.GLFW_KEY_S), key(GLFW.GLFW_KEY_D), key(GLFW.GLFW_KEY_Q), key(GLFW.GLFW_KEY_E))));
        // Left edge: quest book, colony, fleet, trade, diplomacy.
        int sy = LEFT + 3;
        sy = strip(sy, "quest", "screen.annocraft1800.quest_help", () -> { questOpen = !questOpen; rebuild(); }, () -> questOpen);
        sy = strip(sy, "colony", "colony.annocraft1800.tab.overview", () -> colony("overview"), () -> false);
        sy = strip(sy, "ship", "colony.annocraft1800.tab.fleet", () -> colony("fleet"), () -> false);
        sy = strip(sy, "coins", "colony.annocraft1800.tab.trade", () -> colony("trade"), () -> false);
        sy = strip(sy, "diplomacy", "colony.annocraft1800.tab.diplomacy", () -> colony("diplomacy"), () -> false);
        ui.add(new int[]{0, LEFT, STRIP + 2, sy - LEFT + 2});
        ui.add(new int[]{0, 0, PORTRAIT, PORTRAIT});

        // Construction menu, bottom centre; information panel bottom right (above the menu on narrow screens).
        List<String> tabs = tabs();
        if (openCategory == null || !tabs.contains(openCategory)) openCategory = tabs.get(0);
        boolean selected = ClientState.BUILDINGS.containsKey(ClientState.selected);
        int left = minimap + 10;
        boolean side = width - left - INFO_W - 8 >= 6 * (CARD + 2) + 24;
        int available = (side ? width - INFO_W - 8 : width - 4) - left;
        List<Entry> list = entries(openCategory); List<BuildingDefinition> logistics = catalogue("infrastructure");
        int fit = Math.max(1, (available - 26) / (CARD + 2));
        cardScroll = Math.max(0, Math.min(cardScroll, list.size() - fit));
        int shown = Math.min(fit, list.size() - cardScroll);
        int tabsW = 0; for (String t : tabs) tabsW += font.width(Component.translatable("tier.annocraft1800." + t)) + 12;
        int toolsW = (2 + logistics.size()) * (TOOL + 2) + 16;
        menuW = Math.min(available, Math.max(Math.max(shown * (CARD + 2) + 24, tabsW + 8), toolsW));
        menuH = TOOL + 4 + CARD + 4 + TAB_H + 2;
        menuX = left + (available - menuW) / 2; menuY = height - menuH - 2;
        ui.add(new int[]{menuX, menuY, menuW, menuH});
        // Tools on the left of the top row, logistics buildings on the right.
        int ty = menuY + 2;
        addRenderableWidget(new UiKit.AnnoButton(menuX + 3, ty, TOOL, TOOL, Component.empty(), "road", () -> road(1)).highlight(() -> RtsController.roadMode == 1))
                .setTooltip(Tooltip.create(Component.translatable("screen.annocraft1800.road_help")));
        addRenderableWidget(new UiKit.AnnoButton(menuX + 5 + TOOL, ty, TOOL, TOOL, Component.empty(), "road_remove", () -> road(2)).highlight(() -> RtsController.roadMode == 2))
                .setTooltip(Tooltip.create(Component.translatable("screen.annocraft1800.road_remove_help")));
        int lx = menuX + menuW - 3 - logistics.size() * (TOOL + 2);
        for (BuildingDefinition def : logistics) { addRenderableWidget(new Card(lx, ty, TOOL, def, false)); lx += TOOL + 2; }
        // The row of buildings of the open tab.
        int cy = menuY + TOOL + 5, cx = menuX + (menuW - shown * (CARD + 2)) / 2;
        int groupX = -1;
        for (Entry entry : list.subList(cardScroll, cardScroll + shown)) {
            if (entry.group()) { addRenderableWidget(new GroupCard(cx, cy, entry)); if (entry.good().equals(openGroup)) groupX = cx; }
            else addRenderableWidget(new Card(cx, cy, CARD, entry.members().get(0), false));
            cx += CARD + 2;
        }
        // The open chain's buildings, in a row above its card, as Anno's ▲ menus.
        Entry open = list.stream().filter(e -> e.group() && e.good().equals(openGroup)).findFirst().orElse(null);
        if (open == null) openGroup = null;
        else {
            int pw = open.members().size() * (CARD + 2) + 6, px = Math.max(2, Math.min(width - pw - 2, (groupX < 0 ? menuX + menuW / 2 : groupX + CARD / 2) - pw / 2)), py = menuY - CARD - 20;
            popup = new int[]{px, py, pw, CARD + 16}; ui.add(new int[]{px, py, pw, CARD + 16});
            int mx = px + 4;
            for (BuildingDefinition def : open.members()) { addRenderableWidget(new Card(mx, py + 12, CARD, def, true)); mx += CARD + 2; }
        }
        if (cardScroll > 0) addRenderableWidget(new UiKit.AnnoButton(menuX + 2, cy + CARD / 2 - 8, 9, 16, Component.literal("<"), null, () -> { cardScroll--; rebuild(); }));
        if (cardScroll + shown < list.size()) addRenderableWidget(new UiKit.AnnoButton(menuX + menuW - 11, cy + CARD / 2 - 8, 9, 16, Component.literal(">"), null, () -> { cardScroll++; rebuild(); }));
        // Population tabs along the bottom edge.
        int tabX = menuX + (menuW - tabsW) / 2;
        for (int i = 0; i < tabs.size(); i++) {
            String tab = tabs.get(i); int w = font.width(Component.translatable("tier.annocraft1800." + tab)) + 10;
            addRenderableWidget(new Tab(tabX, menuY + menuH - TAB_H - 1, w, tab, i + 1)); tabX += w + 2;
        }
        // Information panel and the selected building's actions.
        infoH = 112; infoX = width - INFO_W - 2; infoY = side ? height - infoH - 2 : menuY - infoH - 4;
        if (!selected && !RtsController.selectedShips.isEmpty()) {
            UUID first = RtsController.selectedShips.iterator().next();
            addRenderableWidget(new UiKit.AnnoButton(infoX + 4, infoY + infoH - 19, INFO_W - 8, 15, Component.translatable("screen.annocraft1800.manage_fleet"), "ship",
                    () -> { ColonyScreen.focus(first); colony("fleet"); }));
        }
        if (selected) {
            int y = infoY + infoH - 19, w = (INFO_W - 12) / 3;
            BuildingInstance b = ClientState.BUILDINGS.get(ClientState.selected);
            BuildingDefinition def = ClientState.DEFINITIONS.get(b.definition());
            var up = addRenderableWidget(new UiKit.AnnoButton(infoX + 4, y, w, 15, Component.translatable("screen.annocraft1800.upgrade"), null, () -> command(2)));
            up.active = def != null && def.upgrade() != null;
            if (up.active && ClientState.DEFINITIONS.containsKey(def.upgrade())) up.setTooltip(Tooltip.create(describe(ClientState.DEFINITIONS.get(def.upgrade()))));
            addRenderableWidget(new UiKit.AnnoButton(infoX + 6 + w, y, w, 15, Component.translatable("screen.annocraft1800.copy"), null, this::copy))
                    .setTooltip(Tooltip.create(Component.translatable("screen.annocraft1800.copy_help")));
            addRenderableWidget(new UiKit.AnnoButton(infoX + 8 + 2 * w, y, w, 15, Component.translatable("screen.annocraft1800.demolish"), null, () -> command(1)))
                    .setTooltip(Tooltip.create(Component.translatable("screen.annocraft1800.demolish_help")));
        }
    }
    /** The label printed on a key of the player's keyboard (GLFW key codes follow the US layout's positions). */
    static String key(int glfw) { String name = GLFW.glfwGetKeyName(glfw, 0); return name == null ? "?" : name.toUpperCase(Locale.ROOT); }
    private void topButton(int x, String icon, String help, Runnable action) {
        addRenderableWidget(new UiKit.AnnoButton(x, 1, 18, 16, Component.empty(), icon, action)).setTooltip(Tooltip.create(Component.translatable(help)));
    }
    private int strip(int y, String icon, String help, Runnable action, java.util.function.Supplier<Boolean> on) {
        addRenderableWidget(new UiKit.AnnoButton(2, y, STRIP - 2, STRIP - 2, Component.empty(), icon, action).highlight(on)).setTooltip(Tooltip.create(Component.translatable(help)));
        return y + STRIP;
    }
    private void colony(String tab) { ColonyScreen.tab = tab; openColony(); }
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

    // ---------------------------------------------------------------- text helpers shared with other screens

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
    private void dyn(int x, int y, int w, int h) { ui.add(new int[]{x, y, w, h, 1}); }

    // ---------------------------------------------------------------- drawing

    @Override public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        Hud.watchMission();
        tips.clear();
        // Hit areas: fixed ones come from init, the others were recorded by the previous frame.
        if (RtsController.forcedHover != null || !overUi(mouseX, mouseY)) RtsController.pick(mouseX, mouseY, width, height);
        else RtsController.hover = null;
        ui.removeIf(r -> r.length == 5);
        String island = currentIsland();
        int treasuryW = treasury(g);
        islandBar(g, island, treasuryW);
        // Left strip and quest book.
        UiKit.panel(g, 0, LEFT, STRIP + 2, 5 * STRIP + 4);
        portrait(g);
        int feedY = LEFT + 3;
        questW = Math.min(150, width / 3);
        if (questOpen) {
            questH = Hud.tracker(g, STRIP + 5, LEFT + 3, questW, true);
            dyn(STRIP + 5, LEFT + 3, questW, questH); feedY += questH + 4;
        }
        Hud.notices(g, STRIP + 5, feedY, questW, height - minimap - 34);
        // Minimap under the island's fertilities and deposits.
        int my = height - minimap - 4;
        islandResources(g, island, my);
        UiKit.panel(g, 2, my - 2, minimap + 4, minimap + 4);
        MapView.draw(g, ClientState.world, 4, my, minimap, false);
        dyn(2, my - 2, minimap + 4, minimap + 4);
        tip(4, my, minimap, minimap, Component.translatable("screen.annocraft1800.minimap_help"));
        dialogueRect = Hud.dialogue(g, 4, my - 30, Math.min(260, width / 2));
        if (dialogueRect != null) dyn(dialogueRect[0], dialogueRect[1], dialogueRect[2], dialogueRect[3]);
        // Construction menu body: tool row, card well, tab rail.
        UiKit.panel(g, menuX, menuY, menuW, menuH);
        g.fill(menuX + 1, menuY + TOOL + 3, menuX + menuW - 1, menuY + TOOL + 4, UiKit.EDGE);
        g.fill(menuX + 2, menuY + TOOL + 4, menuX + menuW - 2, menuY + TOOL + 6 + CARD, UiKit.WELL);
        g.fill(menuX + 1, menuY + menuH - TAB_H - 3, menuX + menuW - 1, menuY + menuH - TAB_H - 2, UiKit.EDGE);
        // Placement hint above the menu.
        if (RtsController.placement != null || RtsController.roadMode != 0) {
            Component hint = RtsController.placement != null
                    ? Component.translatable(RtsController.placement.name()).append(" · ").append(Component.translatable("screen.annocraft1800.place"))
                    : Component.translatable(RtsController.roadMode == 1 ? "screen.annocraft1800.road_place" : "screen.annocraft1800.road_erase");
            int w = Math.min(menuW + 40, font.width(hint) + 12), hx = menuX + menuW / 2 - w / 2, hy = (popup != null ? popup[1] : menuY) - 16;
            UiKit.plate(g, hx, hy, w, 13);
            UiKit.centered(g, Component.literal(font.plainSubstrByWidth(hint.getString(), w - 8)), menuX + menuW / 2, hy + 3, UiKit.TEXT);
        }
        // Information panel: the selected building, else the hovered or chosen card.
        hoveredCard = null; hoveredGroup = null;
        if (popup != null) {
            UiKit.panel(g, popup[0], popup[1], popup[2], popup[3]);
            UiKit.centered(g, good(openGroup), popup[0] + popup[2] / 2, popup[1] + 3, UiKit.GOLD_TEXT);
        }
        // The selected building's panel goes under its action buttons; card information over everything.
        BuildingInstance selected = ClientState.BUILDINGS.get(ClientState.selected);
        if (selected != null) objectMenu(g, selected);
        else if (!RtsController.selectedShips.isEmpty()) shipMenu(g);
        else if (RtsController.inspected != null) enemyMenu(g);
        shipBars(g);
        if (box != null) { int x0 = (int) Math.min(box[0], box[2]), y0 = (int) Math.min(box[1], box[3]), x1 = (int) Math.max(box[0], box[2]), y1 = (int) Math.max(box[1], box[3]); g.fill(x0, y0, x1, y1, 0x30f0d898); MapView.frame(g, x0, y0, x1 - x0, y1 - y0, UiKit.GOLD); }
        super.render(g, mouseX, mouseY, partialTick);
        BuildingDefinition shown = hoveredCard != null ? hoveredCard : RtsController.placement;
        // On narrow screens the panel stands above the placement hint, at the right.
        if (selected == null && hoveredCard == null && hoveredGroup != null) groupInfo(g, hoveredGroup, infoBottom());
        else if (selected == null && shown != null) cardInfo(g, shown, island, infoBottom());
        if (RtsController.placement != null && RtsController.hover != null) cursorCost(g, RtsController.placement, island, mouseX, mouseY);
        for (Object[] t : tips) {
            int[] r = (int[]) t[0];
            if (mouseX >= r[0] && mouseY >= r[1] && mouseX < r[0] + r[2] && mouseY < r[1] + r[3]) { g.renderTooltip(font, font.split((Component) t[1], 220), mouseX, mouseY); break; }
        }
    }
    /** Top left corner: the player's living portrait with the company's flag; a click opens the company's identity. */
    private void portrait(GuiGraphics g) {
        var me = ClientState.me(); var company = ClientState.myCompany();
        String avatar = me == null ? "captain" : me.avatar();
        Avatars.portrait(g, avatar, minecraft.player == null ? null : minecraft.player.getUUID(), 0, 0, PORTRAIT, PORTRAIT, ClientState.myColor(), false, 0);
        if (company != null) Avatars.drawFlag(g, company, PORTRAIT - 15, PORTRAIT - 10, 13, 8);
        tip(0, 0, PORTRAIT, PORTRAIT, company == null ? Component.translatable("brand.annocraft1800.title")
                : Component.literal(company.name()).withStyle(net.minecraft.ChatFormatting.GOLD).append("\n").append(Component.translatable("brand.annocraft1800.edit")));
    }
    /** Top left: treasury, balance and population. @return its width */
    private int treasury(GuiGraphics g) {
        CompoundTag eco = ClientState.economy;
        int x = PORTRAIT + 4, w;
        List<Runnable> draw = new ArrayList<>();
        String money, flow = ""; long balance = 0;
        if (eco.getBoolean("sandbox")) money = Component.translatable("screen.annocraft1800.sandbox").getString();
        else {
            money = String.format(Locale.ROOT, "%,d", (long) Math.floor(eco.getDouble("coins"))).replace(',', ' ');
            balance = Math.round(eco.getDouble("income") - eco.getDouble("upkeep"));
            flow = (balance >= 0 ? "+" : "") + balance;
        }
        CompoundTag pop = eco.getCompound("population"); int total = 0; String top = "farmers";
        for (String tier : ColonyEconomy.TIERS) if (pop.getInt(tier) > 0) { total += pop.getInt(tier); if (!tier.equals("laborers") && !tier.equals("overseers")) top = tier; }
        String people = String.format(Locale.ROOT, "%,d", total).replace(',', ' ');
        w = PORTRAIT + 4 + 16 + font.width(money) + (flow.isEmpty() ? 0 : 6 + font.width(flow)) + 12 + 14 + font.width(people) + 8;
        UiKit.panel(g, PORTRAIT, 0, w - PORTRAIT, TOP);
        UiKit.icon(g, "coins", x, 1, 15); x += 18;
        g.drawString(font, money, x, 5, UiKit.GOLD_TEXT, false); x += font.width(money) + 6;
        if (!flow.isEmpty()) {
            g.drawString(font, flow, x, 5, balance >= 0 ? UiKit.GOOD : UiKit.BAD, false);
            tip(0, 0, x + font.width(flow), TOP, Component.translatable("colony.annocraft1800.income", Math.round(eco.getDouble("income")), Math.round(eco.getDouble("upkeep")), balance));
            x += font.width(flow) + 12;
        }
        int px = x;
        UiKit.icon(g, "tier:" + top, x, 2, 13); x += 15;
        g.drawString(font, people, x, 5, UiKit.TEXT, false);
        MutableComponent detail = Component.translatable("screen.annocraft1800.population");
        for (String tier : ColonyEconomy.TIERS) if (pop.getInt(tier) > 0) detail.append("\n").append(Component.translatable("tier.annocraft1800." + tier)).append(" : " + pop.getInt(tier));
        tip(px, 0, w - px, TOP, detail);
        dyn(0, 0, w, TOP);
        return w;
    }
    /** Top centre: the island's construction materials over its name plate; the plate opens the storage. */
    private void islandBar(GuiGraphics g, String island, int treasuryW) {
        plateRect = null;
        if (island == null) return;
        CompoundTag stock = ClientState.island(island).getCompound("stock");
        List<String> shown = new ArrayList<>();
        for (String m : MATERIALS) if (m.equals("timber") || stock.getDouble(m) >= 1) shown.add(m);
        int cell = 38, barW = shown.size() * cell + 12, bx = Math.max(treasuryW + 4, width / 2 - barW / 2);
        if (bx + barW > width - 64) bx = width - 64 - barW;
        UiKit.panel(g, bx, 0, barW, TOP); dyn(bx, 0, barW, TOP);
        int x = bx + 6;
        for (String m : shown) {
            double amount = stock.getDouble(m);
            UiKit.icon(g, m, x, 1, 15);
            g.drawString(font, String.valueOf((long) Math.floor(amount + 1e-6)), x + 17, 5, amount < 1 ? UiKit.MUTED : UiKit.TEXT, false);
            tip(x, 0, cell, TOP, good(m));
            x += cell;
        }
        // Name plate, as Anno's island banner.
        String owner = ClientState.owner(island);
        Component name = Component.translatable("screen.annocraft1800.island_title", islandName(island));
        Component sub = !owner.isEmpty() && !ClientState.mine(owner) ? ClientState.ownerName(owner)
                : Component.translatable("world.annocraft1800." + (island.startsWith("nw_") ? IslandLayout.NEW_WORLD : IslandLayout.OLD_WORLD));
        int pw = Math.max(font.width(name), font.width(sub)) + 24, px = bx + barW / 2 - pw / 2, py = TOP - 1;
        UiKit.plate(g, px, py, pw, 21);
        UiKit.centered(g, name, px + pw / 2, py + 3, UiKit.GOLD_TEXT);
        UiKit.centered(g, sub, px + pw / 2, py + 12, UiKit.MUTED);
        g.drawString(font, storageOpen ? "▴" : "▾", px + pw - 9, py + 7, UiKit.MUTED, false);
        plateRect = new int[]{px, py, pw, 21}; dyn(px, py, pw, 21);
        tip(px, py, pw, 21, Component.translatable("screen.annocraft1800.storage_help"));
        if (storageOpen) {
            int gw = Math.min(width - 2 * (STRIP + 8), 260);
            goods(g, island, width / 2 - gw / 2, py + 23, gw);
        }
    }
    /** Above the minimap: the island under the camera, its fertilities and its deposit sites. */
    private void islandResources(GuiGraphics g, String island, int my) {
        IslandLayout.Island data = island == null ? null : islandData(island);
        int x = 2, y = my - 26, w = minimap + 4;
        UiKit.panel(g, x, y, w, 23); dyn(x, y, w, 23);
        if (data == null) { UiKit.centered(g, Component.translatable("screen.annocraft1800.open_sea"), x + w / 2, y + 8, UiKit.MUTED); return; }
        UiKit.centered(g, Component.translatable("screen.annocraft1800.island_title", islandName(island)), x + w / 2, y + 2, UiKit.GOLD_TEXT);
        List<String> fert = Arrays.asList(data.fertility().split(",")), deps = Arrays.asList(data.deposit().split(","));
        int count = fert.size() + deps.size(), ix = x + w / 2 - (count * 12 + 4) / 2;
        for (String f : fert) {
            UiKit.icon(g, resourceIcon(f), ix, y + 12, 10);
            tip(ix, y + 12, 10, 10, Component.translatable("screen.annocraft1800.fertility_one", Component.translatable("resource.annocraft1800." + f)));
            ix += 12;
        }
        ix += 4;
        IslandLayout layout = ClientState.layout;
        for (String d : deps) {
            long sites = layout == null ? 0 : layout.deposits(data).stream().filter(s -> s.type().equals(d)).count();
            UiKit.icon(g, resourceIcon(d), ix, y + 12, 10);
            g.fill(ix, y + 21, ix + 10, y + 22, UiKit.GOLD);
            tip(ix, y + 12, 10, 10, Component.translatable("screen.annocraft1800.deposit_one", Component.translatable("resource.annocraft1800." + d), sites));
            ix += 12;
        }
    }
    /** Island storage: goods with amounts and trends, then workforce. @return the panel height */
    private int goods(GuiGraphics g, String island, int x, int y, int maxW) {
        CompoundTag i = ClientState.island(island), stock = i.getCompound("stock"), rates = i.getCompound("rates");
        Set<String> ids = new TreeSet<>(stock.getAllKeys()); ids.addAll(rates.getAllKeys());
        List<String> sorted = new ArrayList<>(ids); sorted.sort(Comparator.comparingDouble((String k) -> -stock.getDouble(k)));
        CompoundTag workforce = i.getCompound("workforce");
        int cell = 40, perRow = Math.max(1, (maxW - 8) / cell), rows = Math.max(1, (sorted.size() + perRow - 1) / perRow);
        int h = rows * 16 + 18 + (workforce.isEmpty() ? 0 : 12);
        UiKit.panel(g, x, y, maxW, h); dyn(x, y, maxW, h);
        int cap = i.getInt("capacity");
        UiKit.text(g, Component.translatable("screen.annocraft1800.storage_title", cap), x + 5, y + 4, UiKit.GOLD_TEXT, maxW - 10);
        if (sorted.isEmpty()) UiKit.text(g, Component.translatable("screen.annocraft1800.storage_empty"), x + 5, y + 16, UiKit.MUTED, maxW - 10);
        for (int n = 0; n < sorted.size(); n++) {
            String good = sorted.get(n);
            int gx = x + 5 + (n % perRow) * cell, gy = y + 15 + (n / perRow) * 16;
            double amount = stock.getDouble(good), rate = rates.getDouble(good);
            UiKit.icon(g, good, gx, gy, 14);
            g.drawString(font, String.valueOf((long) Math.floor(amount + 1e-6)), gx + 15, gy + 4, amount < 1 ? UiKit.BAD : rate < -.05 ? UiKit.WARN : UiKit.TEXT, false);
            tip(gx, gy, cell, 16, good(good).withStyle(net.minecraft.ChatFormatting.GOLD).append("\n")
                    .append(Component.translatable("screen.annocraft1800.good_tip", (long) Math.floor(amount + 1e-6), cap, String.format(Locale.ROOT, "%+.1f", rate))));
        }
        int wx = x + 5, wy = y + 15 + rows * 16 + 1;
        for (String tier : workforce.getAllKeys()) {
            int[] wf = workforce.getIntArray(tier); if (wf.length < 2) continue;
            UiKit.icon(g, "tier:" + tier, wx, wy, 10);
            String s = wf[0] + "/" + wf[1];
            g.drawString(font, s, wx + 12, wy + 1, wf[0] >= wf[1] ? UiKit.MUTED : UiKit.BAD, false);
            tip(wx, wy, 14 + font.width(s), 10, Component.translatable("screen.annocraft1800.workforce", Component.translatable("tier.annocraft1800." + tier), wf[0], wf[1]));
            wx += 18 + font.width(s);
        }
        return h;
    }
    /**
     * Information panel of a building card: costs, upkeep, production chain or residents, requirements. Measured
     * first, then drawn with its bottom edge at {@code bottom}, so it is only as tall as its content.
     */
    private void cardInfo(GuiGraphics g, BuildingDefinition def, String island, int bottom) {
        int h = cardInfo(g, def, island, 0, false);
        cardInfo(g, def, island, bottom - h, true);
    }
    private int cardInfo(GuiGraphics g, BuildingDefinition def, String island, int y, boolean draw) {
        int x = infoX, w = INFO_W;
        EconomyProfile e = def.economy();
        List<Runnable> ops = new ArrayList<>();
        int[] ly = {y + 28};
        String tier = e.housing() ? e.houseTier() : category(def).equals("infrastructure") ? null : category(def);
        ops.add(() -> {
            UiKit.icon(g, UiKit.icon(def), x + 4, y + 4, 20);
            UiKit.text(g, Component.translatable(def.name()), x + 28, y + 5, UiKit.GOLD_TEXT, w - 32);
            UiKit.text(g, tier == null ? Component.translatable("category.annocraft1800.infrastructure") : Component.translatable("tier.annocraft1800." + tier), x + 28, y + 15, UiKit.MUTED, w - 32);
        });
        int costY = ly[0];
        ops.add(() -> {
            UiKit.text(g, Component.translatable("screen.annocraft1800.construction_cost"), x + 5, costY, UiKit.MUTED, w - 10);
            int cx = x + 5;
            for (var c : e.cost().entrySet()) {
                boolean ok = affordable(Map.of(c.getKey(), c.getValue()), island);
                UiKit.icon(g, c.getKey(), cx, costY + 10, 12);
                g.drawString(font, String.valueOf(c.getValue()), cx + 13, costY + 12, ok ? UiKit.TEXT : UiKit.BAD, false);
                cx += 17 + font.width(String.valueOf(c.getValue())) + 2;
            }
            if (e.cost().isEmpty()) UiKit.text(g, Component.translatable("screen.annocraft1800.free"), x + 5, costY + 12, UiKit.TEXT, w - 10);
        });
        ly[0] += 25;
        if (e.upkeep() > 0) {
            int yy = ly[0];
            ops.add(() -> {
                UiKit.text(g, Component.translatable("screen.annocraft1800.maintenance"), x + 5, yy, UiKit.MUTED, w - 60);
                UiKit.icon(g, "coins", x + w - 40, yy - 1, 10);
                g.drawString(font, "-" + e.upkeep(), x + w - 28, yy, UiKit.BAD, false);
            });
            ly[0] += 11;
        }
        if (e.workforce() > 0) {
            int yy = ly[0];
            ops.add(() -> {
                UiKit.icon(g, "tier:" + e.workTier(), x + 5, yy - 1, 10);
                UiKit.text(g, Component.translatable("tooltip.annocraft1800.workforce", e.workforce(), Component.translatable("tier.annocraft1800." + e.workTier())), x + 17, yy, UiKit.TEXT, w - 22);
            });
            ly[0] += 11;
        }
        if (e.producer()) {
            int yy = ly[0];
            ops.add(() -> {
                int px = x + 5;
                for (String in : e.inputs().keySet()) { UiKit.icon(g, in, px, yy, 14); px += 16; }
                if (!e.inputs().isEmpty()) { g.drawString(font, "→", px + 1, yy + 3, UiKit.GOLD, false); px += 10; }
                for (String out : e.outputs().keySet()) { UiKit.icon(g, out, px, yy, 14); px += 16; }
                UiKit.text(g, Component.translatable("screen.annocraft1800.cycle_seconds", e.cycle()), px + 3, yy + 3, UiKit.MUTED, x + w - px - 8);
            });
            ly[0] += 17;
        } else if (e.housing()) {
            int yy = ly[0];
            ops.add(() -> {
                UiKit.text(g, Component.translatable("tooltip.annocraft1800.housing", e.capacity(), Component.translatable("tier.annocraft1800." + e.houseTier())), x + 5, yy, UiKit.TEXT, w - 10);
                int px = x + 5;
                for (String need : e.needs().keySet()) { UiKit.icon(g, need, px, yy + 10, 12); px += 14; }
                for (String s : e.services()) { UiKit.icon(g, "building:" + s, px, yy + 10, 12); px += 14; }
            });
            ly[0] += 25;
        } else {
            Component line = e.serviceProvider() ? Component.translatable("tooltip.annocraft1800.service", e.radius())
                    : e.storageNode() ? Component.translatable("tooltip.annocraft1800.storage", e.storage())
                    : e.booster() ? Component.translatable("tooltip.annocraft1800.boost", Math.round(e.boost() * 100), e.boostRadius()) : null;
            if (line != null) { int yy = ly[0]; ops.add(() -> UiKit.text(g, line, x + 5, yy, UiKit.TEXT, w - 10)); ly[0] += 11; }
        }
        // Requirements, wrapped, red when the island cannot meet them.
        IslandLayout.Island data = island == null ? null : islandData(island);
        Map<Component, Boolean> needs = new LinkedHashMap<>();
        if (e.fertility() != null) needs.put(Component.translatable("tooltip.annocraft1800.fertility", Component.translatable("resource.annocraft1800." + e.fertility())), data != null && data.hasFertility(e.fertility()));
        if (e.deposit() != null) needs.put(Component.translatable("screen.annocraft1800.needs_deposit", Component.translatable("resource.annocraft1800." + e.deposit())), data != null && data.hasDeposit(e.deposit()));
        if (def.id().getPath().equals("lumberjack")) needs.put(Component.translatable("screen.annocraft1800.needs_forest"), true);
        if (def.coastal()) needs.put(Component.translatable("tooltip.annocraft1800.coastal"), true);
        if (e.unlockTier() != null) needs.put(Component.translatable("tooltip.annocraft1800.unlock", e.unlockResidents(), Component.translatable("tier.annocraft1800." + e.unlockTier())), unlocked(e));
        for (var n : needs.entrySet()) for (var line : font.split(n.getKey(), w - 10)) {
            int yy = ly[0]; boolean ok = n.getValue();
            ops.add(() -> g.drawString(font, line, x + 5, yy, ok ? UiKit.MUTED : UiKit.BAD, false));
            ly[0] += 9;
        }
        int h = ly[0] - y + 4;
        if (draw) {
            UiKit.panel(g, x, y, w, h); dyn(x, y, w, h);
            ops.forEach(Runnable::run);
        }
        return h;
    }
    /** Bottom edge of the floating information panel: the screen corner, or above the hint and chain menu on narrow screens. */
    private int infoBottom() {
        if (infoY + infoH == height - 2) return height - 2;
        return (popup != null ? Math.min(popup[1] - 18, menuY - 19) : menuY - 19);
    }
    /** Information panel of a production chain: its good and the buildings behind it, raw materials first. */
    private void groupInfo(GuiGraphics g, Entry entry, int bottom) {
        int x = infoX, w = INFO_W, h = 30 + entry.members().size() * 13 + 12, y = bottom - h;
        UiKit.panel(g, x, y, w, h); dyn(x, y, w, h);
        UiKit.icon(g, entry.good(), x + 4, y + 4, 20);
        UiKit.text(g, good(entry.good()), x + 28, y + 5, UiKit.GOLD_TEXT, w - 32);
        UiKit.text(g, Component.translatable("screen.annocraft1800.chain", entry.members().size()), x + 28, y + 15, UiKit.MUTED, w - 32);
        int ly = y + 29;
        for (BuildingDefinition d : entry.members()) {
            UiKit.icon(g, UiKit.icon(d), x + 5, ly - 1, 11);
            UiKit.text(g, Component.translatable(d.name()), x + 19, ly + 1, unlocked(d.economy()) ? UiKit.TEXT : UiKit.BAD, w - 24);
            ly += 13;
        }
        UiKit.text(g, Component.translatable("screen.annocraft1800.chain_open"), x + 5, ly + 1, UiKit.MUTED, w - 10);
    }
    /** Anno-style object menu: production chain or needs of the selected building. */
    private void objectMenu(GuiGraphics g, BuildingInstance b) {
        BuildingDefinition def = ClientState.DEFINITIONS.get(b.definition()); if (def == null) return;
        int x = infoX, y = infoY, w = INFO_W;
        UiKit.panel(g, x, y, w, infoH); dyn(x, y, w, infoH);
        UiKit.icon(g, UiKit.icon(def), x + 4, y + 4, 20);
        UiKit.text(g, Component.translatable(def.name()), x + 28, y + 5, UiKit.GOLD_TEXT, w - 32);
        UiKit.text(g, Component.translatable("screen.annocraft1800.island_level", islandName(b.island()), def.level()), x + 28, y + 15, UiKit.MUTED, w - 32);
        CompoundTag site = ClientState.SITES.get(b.id()); EconomyProfile e = def.economy();
        int ly = y + 29;
        if (site == null) return;
        int status = Math.max(0, Math.min(site.getByte("status"), ColonyEconomy.Status.values().length - 1));
        ColonyEconomy.Status s = ColonyEconomy.Status.values()[status];
        UiKit.text(g, Component.translatable("status.annocraft1800." + s.name().toLowerCase(Locale.ROOT)), x + 5, ly, s == ColonyEconomy.Status.OK ? UiKit.GOOD : UiKit.BAD, w - 10);
        ly += 11;
        CompoundTag stock = ClientState.island(b.island()).getCompound("stock");
        if (e.producer()) {
            // Inputs → outputs, like Anno's production chain line.
            int cx = x + 6;
            for (String in : e.inputs().keySet()) cx = chainIcon(g, in, cx, ly, stock.getDouble(in) >= 1);
            if (!e.inputs().isEmpty()) { g.drawString(font, "→", cx + 1, ly + 4, UiKit.GOLD, false); cx += 10; }
            for (String out : e.outputs().keySet()) cx = chainIcon(g, out, cx, ly, true);
            UiKit.text(g, Component.translatable("screen.annocraft1800.cycle_seconds", e.cycle()), cx + 4, ly + 4, UiKit.MUTED, x + w - cx - 8);
            ly += 20;
            UiKit.text(g, Component.translatable("screen.annocraft1800.productivity", Math.round(site.getDouble("productivity") * 100)), x + 5, ly, UiKit.TEXT, w - 10); ly += 10;
            UiKit.bar(g, x + 5, ly, w - 10, 4, Math.min(1, site.getDouble("productivity") / 2), site.getDouble("productivity") >= 1 ? UiKit.GOOD : UiKit.WARN); ly += 6;
            UiKit.bar(g, x + 5, ly, w - 10, 2, site.getDouble("progress"), UiKit.GOLD); ly += 6;
            if (e.workforce() > 0) {
                UiKit.icon(g, "tier:" + e.workTier(), x + 5, ly, 10);
                UiKit.text(g, Component.translatable("tooltip.annocraft1800.workforce", e.workforce(), Component.translatable("tier.annocraft1800." + e.workTier())), x + 17, ly + 1, UiKit.MUTED, w - 22);
            }
        } else if (e.housing()) {
            int residents = (int) Math.floor(site.getDouble("residents") + 1e-6);
            UiKit.text(g, Component.translatable("screen.annocraft1800.residents", residents, e.capacity()), x + 5, ly, UiKit.TEXT, w - 10); ly += 10;
            UiKit.bar(g, x + 5, ly, w - 10, 4, (double) residents / e.capacity(), UiKit.GOOD); ly += 7;
            List<String> covered = new ArrayList<>(); site.getList("services", Tag.TAG_STRING).forEach(t -> covered.add(t.getAsString()));
            int cx = x + 5;
            for (String need : e.needs().keySet()) cx = chainIcon(g, need, cx, ly, stock.getDouble(need) >= 1 && site.getBoolean("connected"));
            for (String service : e.services()) cx = serviceIcon(g, service, cx, ly, covered.contains(service));
            ly += 18;
            int lx = x + 5;
            for (String lux : e.luxury().keySet()) lx = chainIcon(g, lux, lx, ly, stock.getDouble(lux) >= 1 && site.getBoolean("connected"));
            for (String service : e.luxuryServices()) lx = serviceIcon(g, service, lx, ly, covered.contains(service));
            if (lx > x + 5) UiKit.text(g, Component.translatable("screen.annocraft1800.luxury_short", Math.round(site.getDouble("luxury") * 100)), lx + 2, ly + 4, UiKit.MUTED, x + w - lx - 6);
        } else if (e.storageNode()) UiKit.text(g, Component.translatable("tooltip.annocraft1800.storage", e.storage()), x + 5, ly, UiKit.TEXT, w - 10);
        else if (e.serviceProvider()) UiKit.text(g, Component.translatable("tooltip.annocraft1800.service", e.radius()), x + 5, ly, UiKit.TEXT, w - 10);
        else if (e.booster()) UiKit.text(g, Component.translatable("tooltip.annocraft1800.boost", Math.round(e.boost() * 100), e.boostRadius()), x + 5, ly, UiKit.TEXT, w - 10);
    }
    /** Object menu of the selected ships, as Anno's: one ship's hull, orders and hold, or the list of a squadron. */
    private void shipMenu(GuiGraphics g) {
        List<CompoundTag> ships = new ArrayList<>();
        for (UUID id : RtsController.selectedShips) { CompoundTag s = ShipRenderer.find(id); if (s != null) ships.add(s); }
        if (ships.isEmpty()) return;
        int x = infoX, y = infoY, w = INFO_W;
        UiKit.panel(g, x, y, w, infoH); dyn(x, y, w, infoH);
        UiKit.icon(g, "ship", x + 4, y + 4, 20);
        int ly = y + 29;
        if (ships.size() == 1) {
            CompoundTag s = ships.get(0); Maritime.ShipType t = Maritime.type(s.getString("type"));
            UiKit.text(g, Component.literal(s.getString("name")), x + 28, y + 5, UiKit.GOLD_TEXT, w - 32);
            UiKit.text(g, Component.translatable("ship.annocraft1800." + s.getString("type")).append(" · ").append(Component.translatable("colony.annocraft1800.order." + s.getString("order"))), x + 28, y + 15, UiKit.MUTED, w - 32);
            double hp = t == null ? 0 : s.getDouble("hp") / t.hp();
            UiKit.bar(g, x + 5, ly, w - 10, 4, hp, hp > .5 ? UiKit.GOOD : hp > .25 ? UiKit.WARN : UiKit.BAD);
            tip(x + 5, ly - 2, w - 10, 8, Component.translatable("colony.annocraft1800.ship_hp", (int) s.getDouble("hp"), t == null ? 0 : t.hp()));
            ly += 8;
            if (t != null) {
                CompoundTag cargo = s.getCompound("cargo"); List<String> goods = new ArrayList<>(cargo.getAllKeys());
                int bx = x + 5;
                for (int i = 0; i < t.slots(); i++) {
                    g.fill(bx, ly, bx + 16, ly + 16, UiKit.WELL); MapView.frame(g, bx, ly, 16, 16, UiKit.EDGE);
                    if (i < goods.size()) {
                        UiKit.icon(g, goods.get(i), bx + 1, ly + 1, 14);
                        tip(bx, ly, 16, 16, good(goods.get(i)).append(" × " + (long) Math.floor(cargo.getDouble(goods.get(i)))));
                    }
                    bx += 18;
                }
                if (t.attack() > 0) UiKit.text(g, Component.translatable("screen.annocraft1800.ship_attack", t.attack()), bx + 3, ly + 4, UiKit.TEXT, x + w - bx - 8);
                ly += 19;
            }
        } else {
            UiKit.text(g, Component.translatable("screen.annocraft1800.squadron", ships.size()), x + 28, y + 9, UiKit.GOLD_TEXT, w - 32);
            for (CompoundTag s : ships.subList(0, Math.min(4, ships.size()))) {
                Maritime.ShipType t = Maritime.type(s.getString("type"));
                UiKit.text(g, Component.literal(s.getString("name")), x + 5, ly, UiKit.TEXT, w / 2 - 6);
                UiKit.bar(g, x + w / 2, ly + 3, w / 2 - 6, 3, t == null ? 0 : s.getDouble("hp") / t.hp(), UiKit.GOOD);
                ly += 10;
            }
            ly += 2;
        }
        for (var line : font.split(Component.translatable("screen.annocraft1800.ship_orders_hint"), w - 10)) {
            if (ly > y + infoH - 28) break;
            g.drawString(font, line, x + 5, ly, UiKit.MUTED, false); ly += 9;
        }
    }
    /** Information on an enemy or rival ship: who sails it, in person, its hull and guns, how things stand with them. */
    private void enemyMenu(GuiGraphics g) {
        CompoundTag s = ShipRenderer.find(RtsController.inspected);
        if (s == null) { RtsController.inspected = null; return; }
        String owner = s.getString("owner"); Maritime.ShipType t = Maritime.type(s.getString("type"));
        int x = infoX, y = infoY, w = INFO_W;
        UiKit.panel(g, x, y, w, infoH); dyn(x, y, w, infoH);
        boolean company = s.getBoolean("company");
        var crew = company ? ColonyScreen.crew(owner) : List.<fr.annocraft.economy.Company.Member>of();
        int color = company ? ClientState.colorOf(owner) : MapView.ownerColor(owner) & 0xffffff;
        if (!crew.isEmpty()) Avatars.portrait(g, crew.get(0).avatar(), crew.get(0).player(), x + 4, y + 4, 30, 30, color, false, 3);
        else if (!company) Avatars.portrait(g, Avatars.character(owner), null, x + 4, y + 4, 30, 30, color, false, 3);
        UiKit.text(g, Component.literal(s.getString("name")), x + 38, y + 5, UiKit.BAD, w - 42);
        UiKit.text(g, Component.translatable("ship.annocraft1800." + s.getString("type")), x + 38, y + 15, UiKit.MUTED, w - 42);
        UiKit.text(g, ClientState.ownerName(owner), x + 38, y + 25, UiKit.TEXT, w - 42);
        int ly = y + 40;
        String stance = company ? ColonyScreen.rivals().stream().filter(r -> r.getString("id").equals(owner)).map(r -> r.getString("stance")).findFirst().orElse("PEACE")
                : ColonyScreen.faction(owner).getString("stance");
        if (!stance.isEmpty()) { UiKit.text(g, Component.translatable("stance.annocraft1800." + stance.toLowerCase(Locale.ROOT)), x + 5, ly, ColonyScreen.stanceColor(stance), w - 10); ly += 11; }
        if (t != null) {
            double hp = s.getDouble("hp") / t.hp();
            UiKit.bar(g, x + 5, ly, w - 10, 4, hp, UiKit.BAD);
            tip(x + 5, ly - 2, w - 10, 8, Component.translatable("colony.annocraft1800.ship_hp", (int) s.getDouble("hp"), t.hp()));
            ly += 8;
            UiKit.text(g, Component.translatable("screen.annocraft1800.ship_attack", t.attack()), x + 5, ly, UiKit.TEXT, w - 10); ly += 12;
        }
        for (var line : font.split(Component.translatable("screen.annocraft1800.enemy_hint"), w - 10)) {
            if (ly > y + infoH - 10) break;
            g.drawString(font, line, x + 5, ly, UiKit.MUTED, false); ly += 9;
        }
    }
    /** Hull bars over the ships near the camera: the player's in green, enemies in red with their name. */
    private void shipBars(GuiGraphics g) {
        if (RtsController.zoom > 400) return;
        for (var e : ShipRenderer.SEEN.entrySet()) {
            CompoundTag s = ShipRenderer.find(e.getKey()); if (s == null) continue;
            Maritime.ShipType t = Maritime.type(s.getString("type")); if (t == null) continue;
            double[] p = RtsController.project(e.getValue()[0], IslandLayout.SEA_LEVEL + 10, e.getValue()[1], width, height);
            if (p == null) continue;
            boolean enemy = s.contains("owner"), chosen = RtsController.selectedShips.contains(e.getKey());
            if (!enemy && !chosen && !e.getKey().equals(RtsController.hoverShip) && s.getDouble("hp") >= t.hp()) continue;
            int bx = (int) p[0] - 12, by = (int) p[1];
            g.fill(bx - 1, by - 1, bx + 25, by + 4, 0xc0000000);
            UiKit.bar(g, bx, by, 24, 3, s.getDouble("hp") / t.hp(), enemy ? UiKit.BAD : UiKit.GOOD);
            if (enemy && e.getKey().equals(RtsController.hoverShip)) {
                Component name = Component.literal(s.getString("name") + " · ").append(ClientState.ownerName(s.getString("owner")));
                UiKit.centered(g, name, (int) p[0], by - 10, UiKit.BAD);
            }
        }
    }
    private int chainIcon(GuiGraphics g, String good, int x, int y, boolean ok) {
        g.fill(x, y, x + 16, y + 16, ok ? 0x40406a40 : 0x60803030);
        UiKit.icon(g, good, x, y, 16);
        g.fill(x, y + 15, x + 16, y + 16, ok ? UiKit.GOOD : UiKit.BAD);
        tip(x, y, 16, 16, good(good).append(ok ? " ✔" : " ✘"));
        return x + 18;
    }
    private int serviceIcon(GuiGraphics g, String service, int x, int y, boolean ok) {
        g.fill(x, y, x + 16, y + 16, ok ? 0x40406a40 : 0x60803030);
        UiKit.icon(g, "building:" + service, x, y, 16);
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
            UiKit.icon(g, e.getKey(), cx, y + 2, 14);
            g.drawString(font, String.valueOf(e.getValue()), cx + 15, y + 6, ok ? UiKit.TEXT : UiKit.BAD, false);
            cx += 34;
        }
    }

    // ---------------------------------------------------------------- input

    @Override public boolean mouseClicked(double x, double y, int button) {
        if (super.mouseClicked(x, y, button)) return true;
        int my = height - minimap - 4;
        if (button == 0 && x >= 4 && y >= my && x < 4 + minimap && y < my + minimap) { draggingMap = true; mapJump(x, y); return true; }
        if (button == 0 && x < PORTRAIT && y < PORTRAIT) { openChild(new BrandingScreen(this)); return true; }
        if (button == 0 && plateRect != null && x >= plateRect[0] && y >= plateRect[1] && x < plateRect[0] + plateRect[2] && y < plateRect[1] + plateRect[3]) { storageOpen = !storageOpen; return true; }
        if (button == 0 && questOpen && x >= STRIP + 5 && y >= LEFT + 3 && x < STRIP + 5 + questW && y < LEFT + 3 + questH) {
            CompoundTag c = Hud.campaign();
            if (y < LEFT + 16) Hud.toggleTracker();
            else if (!c.getBoolean("active")) AnnoNetwork.action("campaign_start");
            else colony("campaign");
            return true;
        }
        if (dialogueRect != null && x >= dialogueRect[0] && y >= dialogueRect[1] && x < dialogueRect[0] + dialogueRect[2] && y < dialogueRect[1] + dialogueRect[3]) { Hud.closeDialogue(); return true; }
        if (button == 2) { rotating = true; return true; }
        if (overUi(x, y)) return true;
        if (button == 0) {
            RtsController.pick(x, y, width, height);
            // Ships are selected like units: a click on one, Shift to add, or a box dragged over several.
            if (RtsController.placement == null && RtsController.roadMode == 0) {
                if (RtsController.clickShip(hasShiftDown())) { rebuild(); return true; }
                if (!hasShiftDown()) RtsController.selectedShips.clear();
                RtsController.inspected = null;
                box = new double[]{x, y, x, y};
            }
            RtsController.clickWorld();
            if (openGroup != null) { openGroup = null; rebuild(); return true; }
            if (RtsController.placement == null && RtsController.roadMode == 0) rebuild();
            return true;
        }
        if (button == 1) {
            RtsController.pick(x, y, width, height);
            if (RtsController.placement == null && RtsController.roadMode == 0 && RtsController.orderShips()) return true;
            cancelTool(); return true;
        }
        return false;
    }
    /** Box selection being dragged: {x0, y0, x1, y1} in GUI pixels. */
    private double[] box;
    private void mapJump(double x, double y) {
        IslandLayout layout = ClientState.layout; if (layout == null) return;
        double[] w = MapView.toWorld(layout, x, y, 4, height - minimap - 4, minimap);
        int half = layout.size() / 2 - 8;
        RtsController.jump(Math.max(-half, Math.min(half, w[0])), Math.max(-half, Math.min(half, w[1])));
    }
    @Override public boolean mouseDragged(double x, double y, int button, double dx, double dy) {
        if (rotating && button == 2) { RtsController.rotateBy(dx, dy); return true; }
        if (box != null && button == 0) { box[2] = x; box[3] = y; return true; }
        if (draggingMap && button == 0) { mapJump(Math.max(4, Math.min(3 + minimap, x)), Math.max(height - minimap - 4, Math.min(height - 5, y))); return true; }
        return super.mouseDragged(x, y, button, dx, dy);
    }
    @Override public boolean mouseReleased(double x, double y, int button) {
        rotating = false; draggingMap = false;
        if (box != null && button == 0) {
            if (Math.abs(box[2] - box[0]) > 5 || Math.abs(box[3] - box[1]) > 5) { RtsController.selectBox(box[0], box[1], box[2], box[3], width, height, hasShiftDown()); rebuild(); }
            box = null;
        }
        // Roads are drawn by dragging, as in Anno: release ends the segment.
        if (button == 0 && RtsController.roadMode != 0 && RtsController.roadStart != null && !overUi(x, y)) {
            RtsController.pick(x, y, width, height); RtsController.releaseRoad();
        }
        return super.mouseReleased(x, y, button);
    }
    private void cancelTool() {
        RtsController.placement = null; RtsController.roadMode = 0; RtsController.roadStart = null; ClientState.selected = null; RtsController.selectedShips.clear(); RtsController.inspected = null; rebuild();
    }
    @Override public boolean mouseScrolled(double x, double y, double delta) {
        if (x >= menuX && x < menuX + menuW && y >= menuY && y < menuY + menuH) {
            cardScroll = Math.max(0, cardScroll - (int) Math.signum(delta)); rebuild(); return true;
        }
        // Ctrl + wheel tilts the camera; the wheel alone zooms, all the way out to the whole archipelago (M opens the map).
        if (hasControlDown()) { RtsController.tiltBy((float) -delta * 4); return true; }
        RtsController.zoomTarget = CameraMath.zoom(RtsController.zoomTarget - (float) delta * Math.max(2, RtsController.zoomTarget * .14f));
        return true;
    }
    @Override public boolean keyPressed(int key, int scan, int modifiers) {
        if (key == GLFW.GLFW_KEY_ESCAPE && openGroup != null) { openGroup = null; rebuild(); return true; }
        if (key == GLFW.GLFW_KEY_ESCAPE && (RtsController.placement != null || RtsController.roadMode != 0 || ClientState.selected != null)) { cancelTool(); return true; }
        if (key == GLFW.GLFW_KEY_R) { RtsController.rotation = (RtsController.rotation + 1) % 4; return true; }
        if (key == GLFW.GLFW_KEY_F6) { onClose(); return true; }
        if (key == GLFW.GLFW_KEY_M) { openMap(); return true; }
        if (RtsController.COLONY.matches(key, scan)) { openColony(); return true; }
        if (key == GLFW.GLFW_KEY_C && ClientState.selected != null) { copy(); return true; }
        if (key == GLFW.GLFW_KEY_DELETE && ClientState.selected != null) { command(1); return true; }
        if (key == GLFW.GLFW_KEY_B) { road(1); return true; }
        if (key >= GLFW.GLFW_KEY_1 && key <= GLFW.GLFW_KEY_9) {
            List<String> tabs = tabs(); int i = key - GLFW.GLFW_KEY_1;
            if (i < tabs.size()) { openCategory = tabs.get(i); cardScroll = 0; openGroup = null; rebuild(); }
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

    /** A building card: the painted icon of what it stands for, greyed while locked, red-rimmed when unaffordable. */
    private final class Card extends AbstractButton {
        private final BuildingDefinition def;
        private final boolean closes;
        Card(int x, int y, int size, BuildingDefinition def, boolean closes) {
            super(x, y, size, size, Component.translatable(def.name())); this.def = def; this.closes = closes;
            active = unlocked(def.economy());
        }
        @Override public void onPress() {
            RtsController.placement = def; RtsController.rotation = 0; RtsController.roadMode = 0; RtsController.roadStart = null; ClientState.selected = null;
            // Picking from a chain menu closes it, as in Anno.
            if (closes) { openGroup = null; rebuild(); }
        }
        @Override protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
            boolean chosen = RtsController.placement == def, hover = isHovered();
            if (hover) hoveredCard = def;
            boolean pay = affordable(def.economy().cost(), currentIsland());
            g.fill(getX(), getY(), getX() + width, getY() + height, chosen ? UiKit.SELECTED : hover ? UiKit.HOVER : UiKit.PANEL_LIGHT);
            int edge = chosen ? UiKit.GOLD : !active ? 0xff3a352c : !pay ? 0xffa04a3c : hover ? UiKit.GOLD : UiKit.EDGE;
            MapView.frame(g, getX(), getY(), width, height, edge);
            int pad = width > 20 ? 3 : 1;
            UiKit.icon(g, UiKit.icon(def), getX() + pad, getY() + pad, width - 2 * pad);
            if (!active) {
                g.fill(getX() + 1, getY() + 1, getX() + width - 1, getY() + height - 1, 0xb0141210);
                if (def.economy().unlockTier() != null) UiKit.icon(g, "tier:" + def.economy().unlockTier(), getX() + width / 2 - 5, getY() + height / 2 - 5, 10);
            }
        }
        @Override protected void updateWidgetNarration(NarrationElementOutput output) { defaultButtonNarrationText(output); }
    }

    /** A production chain in the construction row: its final good with Anno's ▲ marker; a click opens its buildings. */
    private final class GroupCard extends AbstractButton {
        private final Entry entry;
        GroupCard(int x, int y, Entry entry) {
            super(x, y, CARD, CARD, good(entry.good())); this.entry = entry;
            active = entry.members().stream().anyMatch(d -> unlocked(d.economy()));
        }
        @Override public void onPress() { openGroup = entry.good().equals(openGroup) ? null : entry.good(); rebuild(); }
        @Override protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
            boolean open = entry.good().equals(openGroup), chosen = RtsController.placement != null && entry.members().contains(RtsController.placement), hover = isHovered();
            if (hover) hoveredGroup = entry;
            g.fill(getX(), getY(), getX() + width, getY() + height, open || chosen ? UiKit.SELECTED : hover ? UiKit.HOVER : UiKit.PANEL_LIGHT);
            MapView.frame(g, getX(), getY(), width, height, open || chosen || hover ? UiKit.GOLD : !active ? 0xff3a352c : UiKit.EDGE);
            UiKit.icon(g, entry.good(), getX() + 3, getY() + 3, width - 6);
            // The small arrow above the icon: this card opens a menu.
            int cx = getX() + width / 2, ty = getY() - 3;
            for (int i = 0; i < 3; i++) g.fill(cx - i, ty + i, cx + i + 1, ty + i + 1, open ? UiKit.GOLD_TEXT : UiKit.GOLD);
            g.drawString(font, String.valueOf(entry.members().size()), getX() + width - 6, getY() + height - 9, UiKit.MUTED, false);
            if (!active) g.fill(getX() + 1, getY() + 1, getX() + width - 1, getY() + height - 1, 0xb0141210);
        }
        @Override protected void updateWidgetNarration(NarrationElementOutput output) { defaultButtonNarrationText(output); }
    }

    /** Population tab along the bottom edge of the construction menu, as in Anno ("Farmers", "Workers"…). */
    private final class Tab extends AbstractButton {
        private final String tier; private final int key;
        Tab(int x, int y, int w, String tier, int key) {
            super(x, y, w, TAB_H, Component.translatable("tier.annocraft1800." + tier)); this.tier = tier; this.key = key;
            setTooltip(Tooltip.create(Component.translatable("tier.annocraft1800." + tier).append(" (" + key + ")")));
        }
        @Override public void onPress() { openCategory = tier; cardScroll = 0; openGroup = null; rebuild(); }
        @Override protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
            boolean on = tier.equals(openCategory), hover = isHovered();
            boolean any = catalogue(tier).stream().anyMatch(d -> unlocked(d.economy()));
            if (on) { g.fill(getX(), getY(), getX() + width, getY() + height, UiKit.SELECTED); g.fill(getX(), getY(), getX() + width, getY() + 1, UiKit.GOLD); }
            else if (hover) g.fill(getX(), getY(), getX() + width, getY() + height, UiKit.HOVER);
            UiKit.centered(g, getMessage(), getX() + width / 2, getY() + 2, on ? UiKit.GOLD_TEXT : any ? UiKit.TEXT : 0xff6e6656);
        }
        @Override protected void updateWidgetNarration(NarrationElementOutput output) { defaultButtonNarrationText(output); }
    }
}
