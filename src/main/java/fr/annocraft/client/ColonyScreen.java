package fr.annocraft.client;

import fr.annocraft.economy.*;
import fr.annocraft.network.AnnoNetwork;
import fr.annocraft.world.IslandLayout;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.nbt.*;
import net.minecraft.network.chat.*;
import org.lwjgl.glfw.GLFW;
import java.util.*;
import java.util.function.*;

/**
 * Colony-wide management windows, after Anno 1800's menus: a framed window over the management view (the HUD stays
 * visible around it) with its tabs as icons down the left edge. Statistics; the fleet as a list of ship cards with
 * the selected ship's hold, orders, trade route and escort; trade with a merchant's goods grid; diplomacy as one
 * card per rival with its relation and proposals; the campaign. Choices open small drop-down lists of islands,
 * goods or ships instead of cycling through them.
 */
public final class ColonyScreen extends Screen {
    static final String[] TABS = {"overview", "fleet", "trade", "diplomacy", "campaign"};
    private static final String[] TAB_ICONS = {"colony", "ship", "coins", "diplomacy", "quest"};
    static String tab = "overview";
    private static final int HEADER = 18, RAIL = 24;
    // Selections survive reopening the window; they are checked against the current lists when used.
    private static UUID ship, escort;
    private static String shipType = "schooner", yard, destination, routeA, routeB, routeOut, routeBack, partner = "ashby", market, good = "fish";
    private static int shipScroll;
    private final Screen parent;
    private CompoundTag lastEconomy;
    private int wx, wy, ww, wh, cx, cy, cw, ch;
    private Picker picker;
    private final List<Object[]> tips = new ArrayList<>();
    public ColonyScreen(Screen parent) { super(Component.translatable("colony.annocraft1800.title")); this.parent = parent; }
    Screen parent() { return parent; }
    /** Opens the fleet tab on this ship. */
    static void focus(UUID id) { ship = id; }

    // ---------------------------------------------------------------- data

    static List<CompoundTag> ships() {
        List<CompoundTag> list = new ArrayList<>();
        // The player's own ships; enemy warships at sea carry their faction as owner.
        ClientState.economy.getCompound("maritime").getList("ships", Tag.TAG_COMPOUND).forEach(t -> { if (!((CompoundTag) t).contains("owner")) list.add((CompoundTag) t); });
        return list;
    }
    static CompoundTag selectedShip() {
        for (CompoundTag s : ships()) if (ship != null && s.getUUID("id").equals(ship)) return s;
        List<CompoundTag> all = ships(); if (all.isEmpty()) return null;
        ship = all.get(0).getUUID("id"); return all.get(0);
    }
    static List<String> shipyards() {
        Set<String> result = new TreeSet<>();
        for (var b : ClientState.BUILDINGS.values()) {
            var def = ClientState.DEFINITIONS.get(b.definition());
            if (def != null && def.economy().shipyard()) result.add(b.island());
        }
        return new ArrayList<>(result);
    }
    static List<String> islands() {
        List<String> result = new ArrayList<>();
        for (String world : List.of(IslandLayout.OLD_WORLD, IslandLayout.NEW_WORLD)) {
            IslandLayout l = ClientState.LAYOUTS.get(world); if (l != null) l.islands().forEach(i -> result.add(i.id()));
        }
        return result;
    }
    static List<String> goods() { List<String> result = new ArrayList<>(Diplomacy.PRICES.keySet()); Collections.sort(result); return result; }
    static List<String> factions() { List<String> result = new ArrayList<>(); Diplomacy.FACTIONS.forEach(f -> result.add(f.id())); return result; }
    static CompoundTag faction(String id) { return ClientState.economy.getCompound("diplomacy").getCompound("factions").getCompound(id); }
    static Component islandLabel(String id) { return islandLabel(id, true); }
    static Component islandLabel(String id, boolean withOwner) {
        if (id == null) return Component.translatable("colony.annocraft1800.no_island");
        String owner = ClientState.owner(id);
        MutableComponent c = Component.translatable(id.startsWith("nw_") ? "colony.annocraft1800.island_new" : "colony.annocraft1800.island_old", id.replaceAll("\\D+", ""));
        if (withOwner && !owner.isEmpty() && !ClientState.mine(owner)) c.append(" (").append(ClientState.ownerName(owner)).append(")");
        return c;
    }
    static Component goodLabel(String g) { return g == null ? Component.translatable("colony.annocraft1800.none") : Component.translatable("good.annocraft1800." + g); }
    static Component shipTooltip(Maritime.ShipType t) {
        MutableComponent c = Component.translatable("ship.annocraft1800." + t.id()).withStyle(ChatFormatting.GOLD);
        c.append("\n").append(Component.translatable("tooltip.annocraft1800.cost", RtsScreen.amounts(new TreeMap<>(t.cost()))));
        c.append("\n").append(Component.translatable("colony.annocraft1800.ship_stats", t.slots(), t.slotSize(), t.hp(), t.attack(), t.upkeep()));
        if (t.unlockTier() != null) c.append("\n").append(Component.translatable("tooltip.annocraft1800.unlock", 1, Component.translatable("tier.annocraft1800." + t.unlockTier())));
        return c;
    }
    /** Portrait of a rival in the speakers' gallery, or null when it has none (its banner is drawn instead). */
    static String portrait(String faction) { return switch (faction) { case "ashby" -> "speaker:ashby"; case "corsairs" -> "speaker:rook"; default -> null; }; }
    static int stanceColor(String stance) {
        return switch (stance) { case "WAR" -> UiKit.BAD; case "CEASEFIRE" -> UiKit.WARN; case "TRADE", "ALLIANCE" -> UiKit.GOOD; default -> UiKit.TEXT; };
    }
    private static <T> T valid(T value, List<T> list) { return value != null && list.contains(value) ? value : list.isEmpty() ? null : list.get(0); }

    // ---------------------------------------------------------------- layout

    @Override protected void init() {
        if (parent != null) parent.init(minecraft, width, height);
        // The window leaves the HUD's top bar, left strip and construction menu visible around it.
        ww = Math.min(width - 56, 460); wh = Math.min(height - 44, 270);
        wx = Math.max(28, (width - ww) / 2); wy = Math.max(24, (height - wh) / 2 - 6);
        cx = wx + RAIL + 6; cy = wy + HEADER + 5; cw = ww - RAIL - 12; ch = wh - HEADER - 10;
        for (int i = 0; i < TABS.length; i++) {
            String id = TABS[i];
            addRenderableWidget(new UiKit.AnnoButton(wx + 3, wy + HEADER + 4 + i * 22, RAIL - 4, 20, Component.empty(), TAB_ICONS[i], () -> { tab = id; picker = null; rebuild(); })
                    .highlight(() -> id.equals(tab))).setTooltip(Tooltip.create(Component.translatable("colony.annocraft1800.tab." + id)));
        }
        addRenderableWidget(new UiKit.AnnoButton(wx + ww - 16, wy + 3, 13, 12, Component.literal("✕"), null, this::onClose))
                .setTooltip(Tooltip.create(Component.translatable("colony.annocraft1800.close")));
        switch (tab) {
            case "overview" -> overviewWidgets();
            case "fleet" -> fleetWidgets();
            case "trade" -> tradeWidgets();
            case "diplomacy" -> diplomacyWidgets();
            case "campaign" -> campaignWidgets();
        }
    }
    private void rebuild() { clearWidgets(); init(); }
    private UiKit.AnnoButton button(Component label, String icon, int x, int y, int w, Runnable action) {
        return addRenderableWidget(new UiKit.AnnoButton(x, y, w, 15, label, icon, () -> { action.run(); rebuild(); }));
    }
    private void field(int x, int y, int w, Component label, Supplier<Component> value, Supplier<String> icon, Supplier<List<Option>> options, Consumer<String> choose) {
        addRenderableWidget(new Field(x, y, w, label, value, icon, options, choose));
    }
    /** An entry of a drop-down list. */
    record Option(String id, Component label, String icon, boolean enabled) {
        Option(String id, Component label, String icon) { this(id, label, icon, true); }
    }
    private static List<Option> islandOptions(List<String> ids) { List<Option> o = new ArrayList<>(); for (String id : ids) o.add(new Option(id, islandLabel(id), null)); return o; }
    private static List<Option> goodOptions(boolean none) {
        List<Option> o = new ArrayList<>(); if (none) o.add(new Option("", Component.translatable("colony.annocraft1800.none"), null));
        for (String g : goods()) o.add(new Option(g, goodLabel(g), g));
        return o;
    }

    // ---------------------------------------------------------------- overview

    private void overviewWidgets() {
        boolean open = ClientState.economy.getBoolean("new_world_open"), inNew = IslandLayout.NEW_WORLD.equals(ClientState.world);
        var travel = button(Component.translatable(inNew ? "colony.annocraft1800.travel_old" : "colony.annocraft1800.travel_new"), "ship", cx, cy + ch - 16, 150,
                () -> AnnoNetwork.action("travel", "world", inNew ? IslandLayout.OLD_WORLD : IslandLayout.NEW_WORLD));
        travel.active = inNew || open;
        if (!open && !inNew) travel.setTooltip(Tooltip.create(Component.translatable("message.annocraft1800.new_world_locked")));
        button(Component.translatable("colony.annocraft1800.open_map"), "map", cx + 154, cy + ch - 16, 130, () -> minecraft.setScreen(new StrategicMapScreen(this)));
    }
    private void renderOverview(GuiGraphics g, int mouseX, int mouseY) {
        CompoundTag e = ClientState.economy; int col = cw / 2 - 6, y = cy;
        UiKit.icon(g, "coins", cx, y, 18);
        String coins = e.getBoolean("sandbox") ? Component.translatable("screen.annocraft1800.sandbox").getString() : String.format(Locale.ROOT, "%,d", (long) Math.floor(e.getDouble("coins"))).replace(',', ' ');
        g.drawString(font, coins, cx + 22, y + 1, UiKit.GOLD_TEXT, false);
        long income = Math.round(e.getDouble("income")), upkeep = Math.round(e.getDouble("upkeep"));
        UiKit.text(g, Component.translatable("colony.annocraft1800.income", income, upkeep, (income - upkeep >= 0 ? "+" : "") + (income - upkeep)), cx + 22, y + 10, income >= upkeep ? UiKit.GOOD : UiKit.BAD, col - 22);
        y += 24;
        heading(g, Component.translatable("screen.annocraft1800.population"), cx, y, col); y += 12;
        CompoundTag pop = e.getCompound("population"); int total = 0, most = 1;
        for (String tier : ColonyEconomy.TIERS) { total += pop.getInt(tier); most = Math.max(most, pop.getInt(tier)); }
        for (String tier : ColonyEconomy.TIERS) {
            int n = pop.getInt(tier); if (n == 0) continue;
            UiKit.icon(g, "tier:" + tier, cx, y - 1, 11);
            UiKit.text(g, Component.translatable("tier.annocraft1800." + tier), cx + 14, y + 1, UiKit.TEXT, col - 70);
            g.drawString(font, String.valueOf(n), cx + col - 30, y + 1, UiKit.TEXT, false);
            UiKit.bar(g, cx + 14, y + 10, col - 18, 2, (double) n / most, UiKit.GOLD);
            y += 15;
        }
        UiKit.text(g, Component.translatable("colony.annocraft1800.population", total), cx, y + 2, UiKit.MUTED, col);
        UiKit.text(g, Component.translatable("colony.annocraft1800.ports", ClientState.ports().size(), ships().size()), cx, y + 13, UiKit.MUTED, col);
        // Colony-wide goods: stock in all ports and net flow per minute.
        int gx = cx + col + 12, gw = cw - col - 12; y = cy;
        heading(g, Component.translatable("colony.annocraft1800.goods"), gx, y, gw); y += 12;
        Map<String, double[]> totals = new TreeMap<>();
        CompoundTag islands = e.getCompound("islands");
        for (String island : ClientState.ports()) {
            CompoundTag i = islands.getCompound(island);
            for (String k : i.getCompound("stock").getAllKeys()) totals.computeIfAbsent(k, x -> new double[2])[0] += i.getCompound("stock").getDouble(k);
            for (String k : i.getCompound("rates").getAllKeys()) totals.computeIfAbsent(k, x -> new double[2])[1] += i.getCompound("rates").getDouble(k);
        }
        if (totals.isEmpty()) UiKit.text(g, Component.translatable("colony.annocraft1800.no_port"), gx, y, UiKit.MUTED, gw);
        int cell = 44, perRow = Math.max(1, gw / cell), n = 0;
        for (var entry : totals.entrySet()) {
            int x = gx + (n % perRow) * cell, yy = y + (n / perRow) * 18;
            if (yy > cy + ch - 36) break;
            double[] v = entry.getValue();
            UiKit.icon(g, entry.getKey(), x, yy, 14);
            g.drawString(font, String.valueOf((long) Math.floor(v[0] + 1e-6)), x + 16, yy + 3, UiKit.TEXT, false);
            if (Math.abs(v[1]) >= .05) g.drawString(font, v[1] > 0 ? "▲" : "▼", x + 16 + font.width(String.valueOf((long) Math.floor(v[0] + 1e-6))) + 1, yy + 3, v[1] > 0 ? UiKit.GOOD : UiKit.BAD, false);
            tip(x, yy, cell, 16, goodLabel(entry.getKey()).copy().withStyle(ChatFormatting.GOLD).append("\n")
                    .append(Component.translatable("screen.annocraft1800.good_tip", (long) Math.floor(v[0] + 1e-6), "∞", String.format(Locale.ROOT, "%+.1f", v[1]))));
            n++;
        }
    }

    // ---------------------------------------------------------------- fleet

    private int listW() { return Math.min(170, cw / 3 + 24); }
    private void fleetWidgets() {
        List<CompoundTag> all = ships();
        int lw = listW(), rows = Math.max(1, (ch - 86) / 28);
        shipScroll = Math.max(0, Math.min(shipScroll, all.size() - rows));
        int y = cy + 12;
        for (CompoundTag s : all.subList(shipScroll, Math.min(all.size(), shipScroll + rows))) { addRenderableWidget(new ShipCard(cx, y, lw, s)); y += 28; }
        // Shipyard: what to build and where.
        int by = cy + ch - 60;
        List<String> yards = shipyards(); yard = valid(yard, yards);
        field(cx, by + 12, lw, Component.translatable("colony.annocraft1800.field_type"), () -> Component.translatable("ship.annocraft1800." + shipType), () -> "ship", () -> {
            List<Option> o = new ArrayList<>(); for (Maritime.ShipType t : Maritime.TYPES) o.add(new Option(t.id(), Component.translatable("ship.annocraft1800." + t.id()), "ship")); return o;
        }, id -> shipType = id);
        field(cx, by + 28, lw, Component.translatable("colony.annocraft1800.field_yard"), () -> yards.isEmpty() ? Component.translatable("colony.annocraft1800.no_shipyard") : islandLabel(yard), () -> null, () -> islandOptions(shipyards()), id -> yard = id);
        Maritime.ShipType t = Maritime.type(shipType);
        var build = button(Component.translatable("colony.annocraft1800.build_ship"), "ship", cx, by + 44, lw, () -> AnnoNetwork.action("ship_build", "island", yard, "type", shipType));
        build.active = yard != null && t != null;
        if (t != null) build.setTooltip(Tooltip.create(shipTooltip(t)));
        CompoundTag s = selectedShip();
        if (s == null) return;
        UUID id = s.getUUID("id"); Maritime.ShipType kind = Maritime.type(s.getString("type"));
        int rx = cx + lw + 10, rw = cw - lw - 10, half = (rw - 4) / 2;
        int oy = cy + 74;
        List<String> islands = islands(), ports = ClientState.ports();
        destination = valid(destination, islands);
        field(rx, oy, rw - 2 * 56 - 4, Component.translatable("colony.annocraft1800.destination"), () -> islandLabel(destination), () -> null, () -> islandOptions(islands()), v -> destination = v);
        button(Component.translatable("colony.annocraft1800.sail"), null, rx + rw - 112, oy, 54, () -> AnnoNetwork.action("ship_move", "ship", id, "island", destination));
        var siege = button(Component.translatable("colony.annocraft1800.attack"), null, rx + rw - 56, oy, 56, () -> AnnoNetwork.action("ship_attack", "ship", id, "island", destination));
        siege.active = kind != null && kind.military();
        siege.setTooltip(Tooltip.create(Component.translatable(siege.active ? "colony.annocraft1800.siege_help" : "colony.annocraft1800.not_military")));
        oy += 30;
        routeA = valid(routeA, ports); routeB = valid(routeB, ports.stream().filter(p -> !p.equals(routeA)).toList());
        field(rx, oy, half, Component.translatable("colony.annocraft1800.route_from"), () -> islandLabel(routeA), () -> null, () -> islandOptions(ClientState.ports()), v -> routeA = v);
        field(rx + half + 4, oy, half, Component.translatable("colony.annocraft1800.route_to"), () -> islandLabel(routeB), () -> null, () -> islandOptions(ClientState.ports()), v -> routeB = v);
        field(rx, oy + 16, half, Component.translatable("colony.annocraft1800.route_out_good"), () -> goodLabel(routeOut), () -> routeOut, () -> goodOptions(true), v -> routeOut = v.isEmpty() ? null : v);
        field(rx + half + 4, oy + 16, half, Component.translatable("colony.annocraft1800.route_back_good"), () -> goodLabel(routeBack), () -> routeBack, () -> goodOptions(true), v -> routeBack = v.isEmpty() ? null : v);
        var route = button(Component.translatable("colony.annocraft1800.route_create"), "coins", rx, oy + 32, rw, () -> AnnoNetwork.action("ship_route", "ship", id, "a", routeA, "b", routeB,
                "out", routeOut == null ? "" : routeOut, "back", routeBack == null ? "" : routeBack));
        route.active = routeA != null && routeB != null && !routeA.equals(routeB);
        route.setTooltip(Tooltip.create(Component.translatable(ports.size() < 2 ? "colony.annocraft1800.route_needs_ports" : "colony.annocraft1800.route_help")));
        oy += 62;
        List<CompoundTag> others = all.stream().filter(o -> !o.getUUID("id").equals(id)).toList();
        CompoundTag guarded = others.stream().filter(o -> o.getUUID("id").equals(escort)).findFirst().orElse(others.isEmpty() ? null : others.get(0));
        escort = guarded == null ? null : guarded.getUUID("id");
        if (oy + 15 < cy + ch - 18) {
            field(rx, oy, rw - 84, Component.translatable("colony.annocraft1800.protect_field"), () -> guarded == null ? Component.translatable("colony.annocraft1800.none") : Component.literal(guarded.getString("name")), () -> "ship", () -> {
                List<Option> o = new ArrayList<>(); for (CompoundTag x : ships()) if (!x.getUUID("id").equals(id)) o.add(new Option(x.getUUID("id").toString(), Component.literal(x.getString("name")), "ship")); return o;
            }, v -> escort = UUID.fromString(v));
            var guard = button(Component.translatable("colony.annocraft1800.escort"), null, rx + rw - 80, oy, 80, () -> { if (escort != null) AnnoNetwork.action("ship_escort", "ship", id, "target", escort); });
            guard.active = guarded != null && kind != null && kind.military();
            guard.setTooltip(Tooltip.create(Component.translatable(kind != null && kind.military() ? "colony.annocraft1800.escort_help" : "colony.annocraft1800.not_military")));
        }
        button(Component.translatable("colony.annocraft1800.scrap"), null, rx + rw - 80, cy + ch - 16, 80, () -> { AnnoNetwork.action("ship_scrap", "ship", id); ship = null; })
                .setTooltip(Tooltip.create(Component.translatable("colony.annocraft1800.scrap_help")));
    }
    private void renderFleet(GuiGraphics g) {
        int lw = listW();
        heading(g, Component.translatable("colony.annocraft1800.ships_title", ships().size()), cx, cy, lw);
        if (ships().isEmpty()) g.drawWordWrap(font, Component.translatable("colony.annocraft1800.no_ships"), cx, cy + 14, lw, UiKit.MUTED);
        heading(g, Component.translatable("colony.annocraft1800.shipyard_title"), cx, cy + ch - 60, lw);
        CompoundTag s = selectedShip(); if (s == null) return;
        int rx = cx + lw + 10, rw = cw - lw - 10, y = cy;
        g.fill(rx - 6, cy, rx - 5, cy + ch, UiKit.EDGE);
        Maritime.ShipType t = Maritime.type(s.getString("type"));
        UiKit.icon(g, "ship", rx, y, 20);
        UiKit.text(g, Component.literal(s.getString("name")), rx + 24, y + 1, UiKit.GOLD_TEXT, rw - 24);
        UiKit.text(g, Component.translatable("ship.annocraft1800." + s.getString("type")).append(" · ").append(Component.translatable("colony.annocraft1800.order." + s.getString("order"))), rx + 24, y + 11, UiKit.MUTED, rw - 24);
        y += 24;
        double hp = s.getDouble("hp"), max = t == null ? 1 : t.hp();
        UiKit.bar(g, rx, y, rw, 4, hp / max, hp / max > .5 ? UiKit.GOOD : hp / max > .25 ? UiKit.WARN : UiKit.BAD);
        tip(rx, y - 2, rw, 8, Component.translatable("colony.annocraft1800.ship_hp", (int) hp, (int) max));
        y += 7;
        Component where = s.contains("to")
                ? Component.translatable("colony.annocraft1800.ship_sailing", islandLabel(s.getString("to")), Math.round(s.getDouble("progress") * 100))
                : Component.translatable("colony.annocraft1800.ship_at", islandLabel(s.getString("at")));
        UiKit.text(g, where, rx, y, UiKit.TEXT, rw); y += 11;
        // The hold: one box per slot, filled with the cargo in slot-sized lots.
        if (t != null) {
            List<String[]> lots = new ArrayList<>();
            CompoundTag cargo = s.getCompound("cargo");
            for (String k : cargo.getAllKeys()) { double left = Math.floor(cargo.getDouble(k)); while (left > 0 && lots.size() < t.slots()) { double lot = Math.min(left, t.slotSize()); lots.add(new String[]{k, String.valueOf((long) lot)}); left -= lot; } }
            int bx = rx;
            for (int i = 0; i < t.slots(); i++) {
                g.fill(bx, y, bx + 20, y + 20, UiKit.WELL); MapView.frame(g, bx, y, 20, 20, UiKit.EDGE);
                if (i < lots.size()) {
                    UiKit.icon(g, lots.get(i)[0], bx + 2, y + 2, 16);
                    g.drawString(font, lots.get(i)[1], bx + 20 - font.width(lots.get(i)[1]), y + 13, UiKit.TEXT, true);
                    tip(bx, y, 20, 20, goodLabel(lots.get(i)[0]).copy().append(" × " + lots.get(i)[1]));
                } else tip(bx, y, 20, 20, Component.translatable("colony.annocraft1800.empty_slot", t.slotSize()));
                bx += 22;
            }
        }
        heading(g, Component.translatable("colony.annocraft1800.orders"), rx, cy + 64, rw);
        heading(g, Component.translatable("colony.annocraft1800.route_title"), rx, cy + 94, rw);
        ListTag route = s.getList("route", Tag.TAG_COMPOUND);
        if (route.size() >= 2) {
            CompoundTag a = route.getCompound(0), b = route.getCompound(1);
            Component line = Component.translatable("colony.annocraft1800.route_current", islandLabel(a.getString("island"), false), islandLabel(b.getString("island"), false));
            UiKit.text(g, line, rx + font.width(Component.translatable("colony.annocraft1800.route_title")) + 8, cy + 94, UiKit.GOOD, rw - font.width(Component.translatable("colony.annocraft1800.route_title")) - 8);
        }
        if (cy + 156 + 15 < cy + ch - 18) heading(g, Component.translatable("colony.annocraft1800.escort_title"), rx, cy + 146, rw);
    }

    // ---------------------------------------------------------------- trade

    private void tradeWidgets() {
        List<String> partners = factions(), ports = ClientState.ports(), goods = goods();
        partner = valid(partner, partners); market = valid(market, ports); good = valid(good, goods);
        int pw = (cw - 4 * (partners.size() - 1)) / partners.size();
        for (int i = 0; i < partners.size(); i++) addRenderableWidget(new PartnerTab(cx + i * (pw + 4), cy, pw, partners.get(i)));
        int gy = cy + 40, panel = 150, gw = cw - panel - 8;
        field(cx, gy, gw, Component.translatable("colony.annocraft1800.your_island"), () -> islandLabel(market), () -> null, () -> islandOptions(ClientState.ports()), v -> market = v);
        int cell = 22, perRow = Math.max(1, gw / cell), n = 0;
        for (String id : goods) {
            int x = cx + (n % perRow) * cell, y = gy + 20 + (n / perRow) * cell;
            if (y + cell > cy + ch) break;
            addRenderableWidget(new GoodCell(x, y, id)); n++;
        }
        CompoundTag f = faction(partner);
        boolean open = market != null && f.getString("stance").length() > 0 && !"WAR".equals(f.getString("stance")) && !f.getBoolean("eliminated");
        int px = cx + cw - panel, py = gy + 66;
        boolean deal = f.getString("stance").equals("TRADE") || f.getString("stance").equals("ALLIANCE");
        int base = Diplomacy.PRICES.getOrDefault(good, 0);
        long buy = Math.round(base * (deal ? 1.0 : 1.25)), sell = Math.round(base * (deal ? .8 : .6));
        for (int amount : new int[]{10, 50}) {
            var b = button(Component.translatable("colony.annocraft1800.buy_total", amount, buy * amount), null, px, py, panel, () -> AnnoNetwork.action("trade_buy", "faction", partner, "island", market, "good", good, "amount", amount));
            var s = button(Component.translatable("colony.annocraft1800.sell_total", amount, sell * amount), null, px, py + 36, panel, () -> AnnoNetwork.action("trade_sell", "faction", partner, "island", market, "good", good, "amount", amount));
            b.active = s.active = open;
            py += 17;
        }
    }
    private void renderTrade(GuiGraphics g) {
        int panel = 150, px = cx + cw - panel, gy = cy + 40;
        g.fill(px - 5, gy, px - 4, cy + ch, UiKit.EDGE);
        CompoundTag f = faction(partner);
        UiKit.icon(g, good, px, gy, 24);
        UiKit.text(g, goodLabel(good), px + 28, gy + 2, UiKit.GOLD_TEXT, panel - 28);
        CompoundTag i = market == null ? new CompoundTag() : ClientState.island(market);
        UiKit.text(g, Component.translatable("colony.annocraft1800.stock_short", (long) Math.floor(i.getCompound("stock").getDouble(good) + 1e-6), i.getInt("capacity")), px + 28, gy + 13, UiKit.MUTED, panel - 28);
        boolean deal = f.getString("stance").equals("TRADE") || f.getString("stance").equals("ALLIANCE");
        int base = Diplomacy.PRICES.getOrDefault(good, 0);
        UiKit.icon(g, "coins", px, gy + 30, 10);
        UiKit.text(g, Component.translatable("colony.annocraft1800.buy_price", Math.round(base * (deal ? 1.0 : 1.25))), px + 13, gy + 31, UiKit.TEXT, panel - 13);
        UiKit.icon(g, "coins", px, gy + 41, 10);
        UiKit.text(g, Component.translatable("colony.annocraft1800.sell_price", Math.round(base * (deal ? .8 : .6))), px + 13, gy + 42, UiKit.TEXT, panel - 13);
        if (!deal) UiKit.text(g, Component.translatable("colony.annocraft1800.treaty_hint"), px, gy + 53, UiKit.MUTED, panel);
        Component blocked = ClientState.ports().isEmpty() ? Component.translatable("colony.annocraft1800.no_port")
                : f.getBoolean("eliminated") ? Component.translatable("colony.annocraft1800.eliminated")
                : "WAR".equals(f.getString("stance")) ? Component.translatable("colony.annocraft1800.no_trade_war") : null;
        if (blocked != null) g.drawWordWrap(font, blocked, px, gy + 136, panel, UiKit.BAD);
    }

    // ---------------------------------------------------------------- diplomacy

    /** Diplomacy shows the factions or the other companies (competitive game) / fellow players (cooperative game). */
    private static boolean showCompanies;
    private void diplomacyWidgets() {
        int tw = Math.min(150, cw / 2 - 3);
        addRenderableWidget(new UiKit.AnnoButton(cx, cy, tw, 15, Component.translatable("colony.annocraft1800.factions_tab"), "diplomacy", () -> { showCompanies = false; rebuild(); }).highlight(() -> !showCompanies));
        addRenderableWidget(new UiKit.AnnoButton(cx + tw + 6, cy, tw, 15, Component.translatable(ClientState.competitive() ? "colony.annocraft1800.companies_tab" : "colony.annocraft1800.members_tab"), "colony", () -> { showCompanies = true; rebuild(); }).highlight(() -> showCompanies));
        if (showCompanies) { companiesWidgets(); return; }
        List<String> all = factions(); int w = (cw - 6 * (all.size() - 1)) / all.size();
        for (int n = 0; n < all.size(); n++) {
            String f = all.get(n); CompoundTag c = faction(f);
            boolean pirate = c.getBoolean("pirate"), dead = c.getBoolean("eliminated");
            String[][] actions = pirate
                    ? new String[][]{{"gift", "gift"}, {"war", "war"}, {"ceasefire", "tribute"}}
                    : new String[][]{{"gift", "gift"}, {"war", "war"}, {"ceasefire", "ceasefire"}, {"peace", "peace"}, {"trade", "trade_treaty"}, {"alliance", "alliance"}};
            int x = cx + n * (w + 6), y = cy + 104, gap = Math.min(17, (ch - 106) / actions.length);
            for (String[] a : actions) {
                String action = a[0];
                var b = button(Component.translatable("colony.annocraft1800." + a[1]), null, x + 4, y, w - 8, () -> AnnoNetwork.action("diplomacy", "faction", f, "action", action));
                String stance = c.getString("stance"); double rel = c.getDouble("relation");
                // Mirrors the server rules so impossible proposals are greyed out with their condition.
                boolean possible = switch (action) {
                    case "war" -> !stance.equals("WAR");
                    case "ceasefire" -> stance.equals("WAR") && (pirate || rel >= -50);
                    case "peace" -> !pirate && (stance.equals("WAR") || stance.equals("CEASEFIRE")) && rel >= 0;
                    case "trade" -> stance.equals("PEACE") && rel >= 30;
                    case "alliance" -> stance.equals("TRADE") && rel >= 70;
                    default -> true;
                };
                b.active = !dead && possible;
                b.setTooltip(Tooltip.create(Component.translatable("colony.annocraft1800.help." + (pirate && action.equals("ceasefire") ? "tribute" : action))));
                y += gap;
            }
        }
    }
    private void renderDiplomacy(GuiGraphics g) {
        if (showCompanies) { renderCompanies(g); return; }
        List<String> all = factions(); int w = (cw - 6 * (all.size() - 1)) / all.size();
        CompoundTag owners = ClientState.economy.getCompound("diplomacy").getCompound("owners");
        for (int n = 0; n < all.size(); n++) {
            String f = all.get(n); CompoundTag c = faction(f);
            int x = cx + n * (w + 6), y = cy + 20;
            g.fill(x, y, x + w, cy + ch, 0x40000000); MapView.frame(g, x, y, w, ch - 20, UiKit.EDGE);
            // Portrait, or the rival's banner in its map colour.
            // The rival in person, living, framed in its colours.
            Avatars.portrait(g, Avatars.character(f), null, x + w / 2 - 15, y + 3, 30, 30, MapView.ownerColor(f) & 0xffffff, false, n);
            UiKit.centered(g, Component.literal(font.plainSubstrByWidth(Component.translatable("faction.annocraft1800." + f).getString(), w - 6)), x + w / 2, y + 35, UiKit.GOLD_TEXT);
            String stance = c.getString("stance");
            Component state = c.getBoolean("eliminated") ? Component.translatable("colony.annocraft1800.eliminated")
                    : Component.translatable("stance.annocraft1800." + stance.toLowerCase(Locale.ROOT));
            UiKit.centered(g, state, x + w / 2, y + 46, c.getBoolean("eliminated") ? UiKit.MUTED : stanceColor(stance));
            double rel = c.getDouble("relation");
            int bx = x + 6, bw = w - 12;
            UiKit.bar(g, bx, y + 58, bw, 5, (rel + 100) / 200, rel >= 30 ? UiKit.GOOD : rel >= 0 ? UiKit.GOLD : UiKit.BAD);
            g.fill(bx + bw / 2, y + 57, bx + bw / 2 + 1, y + 64, 0xffffffff);
            tip(bx, y + 55, bw, 10, Component.translatable("colony.annocraft1800.relation", (int) rel).append("\n").append(Component.translatable("faction.annocraft1800." + f + ".about")));
            List<String> owned = new ArrayList<>();
            for (String island : owners.getAllKeys()) if (owners.getString(island).equals(f)) owned.add(islandLabel(island, false).getString());
            UiKit.text(g, owned.isEmpty() ? Component.translatable("colony.annocraft1800.no_islands") : Component.translatable("colony.annocraft1800.islands_owned", String.join(", ", owned)), x + 5, y + 70, UiKit.MUTED, w - 10);
        }
    }

    // ---------------------------------------------------------------- other companies and fellow players

    static List<CompoundTag> rivals() {
        List<CompoundTag> list = new ArrayList<>(); ClientState.economy.getList("rivals", Tag.TAG_COMPOUND).forEach(t -> list.add((CompoundTag) t)); return list;
    }
    /** The players of a company, its face first. */
    static List<fr.annocraft.economy.Company.Member> crew(String company) { return ClientState.members().stream().filter(m -> m.company().equals(company)).toList(); }
    private void companiesWidgets() {
        if (!ClientState.competitive()) return;
        List<CompoundTag> rivals = rivals(); if (rivals.isEmpty()) return;
        int n = Math.min(3, rivals.size()), w = (cw - 6 * (n - 1)) / n;
        for (int i = 0; i < n; i++) {
            CompoundTag r = rivals.get(i); String id = r.getString("id"), stance = r.getString("stance");
            int x = cx + i * (w + 6), y = cy + 112;
            if (r.contains("offer")) {
                int half = (w - 10) / 2;
                button(Component.translatable("colony.annocraft1800.accept"), null, x + 4, y, half, () -> AnnoNetwork.action("pact", "company", id, "action", "accept"));
                button(Component.translatable("colony.annocraft1800.decline"), null, x + 6 + half, y, half, () -> AnnoNetwork.action("pact", "company", id, "action", "decline"));
                y += 19;
            }
            String[][] actions = {{"gift", "gift"}, {"war", "war"}, {"peace", "peace"}, {"trade", "trade_treaty"}, {"alliance", "alliance"}};
            int gap = Math.min(16, (cy + ch - y) / actions.length);
            for (String[] a : actions) {
                String action = a[0];
                var b = button(Component.translatable("colony.annocraft1800." + a[1]), null, x + 4, y, w - 8, () -> AnnoNetwork.action("pact", "company", id, "action", action));
                b.active = switch (action) {
                    case "war" -> !stance.equals("WAR");
                    case "peace" -> stance.equals("WAR");
                    case "trade" -> stance.equals("PEACE");
                    case "alliance" -> stance.equals("TRADE");
                    default -> true;
                } && !action.equals(r.getString("offered"));
                b.setTooltip(Tooltip.create(Component.translatable("colony.annocraft1800.help.company_" + action)));
                y += gap;
            }
        }
    }
    private void renderCompanies(GuiGraphics g) {
        if (!ClientState.competitive()) {
            // A cooperative colony: the players running it together.
            UiKit.text(g, Component.translatable("colony.annocraft1800.members_title"), cx, cy + 22, UiKit.GOLD, cw);
            var company = ClientState.myCompany();
            if (company != null) { Avatars.drawFlag(g, company, cx + cw - 37, cy + 21, 36, 24); UiKit.text(g, Component.literal(company.name()), cx, cy + 34, UiKit.TEXT, cw - 44); }
            int x = cx, y = cy + 52, n = 0;
            for (var m : ClientState.members()) {
                Avatars.portrait(g, m.avatar(), m.player(), x, y, 44, 50, ClientState.myColor(), false, n);
                UiKit.centered(g, Component.literal(font.plainSubstrByWidth(m.name(), 52)), x + 22, y + 53, UiKit.TEXT);
                x += 54; n++;
                if (x + 44 > cx + cw) { x = cx; y += 66; }
                if (y + 50 > cy + ch) break;
            }
            g.drawWordWrap(font, Component.translatable("colony.annocraft1800.coop_hint"), cx, cy + ch - 20, cw, UiKit.MUTED);
            return;
        }
        List<CompoundTag> rivals = rivals();
        if (rivals.isEmpty()) { g.drawWordWrap(font, Component.translatable("colony.annocraft1800.no_rivals"), cx, cy + 24, cw, UiKit.MUTED); return; }
        int n = Math.min(3, rivals.size()), w = (cw - 6 * (n - 1)) / n;
        for (int i = 0; i < n; i++) {
            CompoundTag r = rivals.get(i); String id = r.getString("id"), stance = r.getString("stance");
            var company = ClientState.company(id); int color = ClientState.colorOf(id);
            int x = cx + i * (w + 6), y = cy + 20;
            g.fill(x, y, x + w, cy + ch, 0x40000000); MapView.frame(g, x, y, w, ch - 20, UiKit.EDGE);
            // Its player in person, its flag beside.
            var crew = crew(id);
            if (!crew.isEmpty()) Avatars.portrait(g, crew.get(0).avatar(), crew.get(0).player(), x + w / 2 - 22, y + 4, 36, 36, color, r.contains("offer"), i);
            if (company != null) Avatars.drawFlag(g, company, x + w / 2 + 17, y + 6, 18, 12);
            UiKit.centered(g, Component.literal(font.plainSubstrByWidth(company == null ? id : company.name(), w - 6)), x + w / 2, y + 44, UiKit.GOLD_TEXT);
            if (!crew.isEmpty()) UiKit.centered(g, Component.literal(font.plainSubstrByWidth(crew.get(0).name(), w - 6)), x + w / 2, y + 54, UiKit.MUTED);
            UiKit.centered(g, Component.translatable("stance.annocraft1800." + stance.toLowerCase(Locale.ROOT)), x + w / 2, y + 65, stanceColor(stance));
            UiKit.centered(g, Component.translatable("colony.annocraft1800.company_size", r.getInt("islands"), r.getInt("population")), x + w / 2, y + 76, UiKit.MUTED);
            if (r.contains("offer")) UiKit.text(g, Component.translatable("colony.annocraft1800.offer_received", Component.translatable("colony.annocraft1800." + (r.getString("offer").equals("trade") ? "trade_treaty" : r.getString("offer")))), x + 5, y + 86, UiKit.GOOD, w - 10);
            else if (r.contains("offered")) UiKit.text(g, Component.translatable("colony.annocraft1800.offer_sent", Component.translatable("colony.annocraft1800." + (r.getString("offered").equals("trade") ? "trade_treaty" : r.getString("offered")))), x + 5, y + 86, UiKit.MUTED, w - 10);
        }
    }

    // ---------------------------------------------------------------- campaign

    private void campaignWidgets() {
        CompoundTag c = ClientState.economy.getCompound("campaign");
        if (!c.getBoolean("active")) button(Component.translatable("colony.annocraft1800.campaign_start"), "quest", cx + cw / 2 - 80, cy + ch - 18, 160, () -> AnnoNetwork.action("campaign_start"));
    }
    private void renderCampaign(GuiGraphics g) {
        CompoundTag c = ClientState.economy.getCompound("campaign"); int y = cy, w = cw;
        if (!c.getBoolean("active")) { g.drawWordWrap(font, Component.translatable("colony.annocraft1800.campaign_intro"), cx, y, w, UiKit.TEXT); return; }
        if (c.getBoolean("finished") || !c.contains("mission")) { g.drawWordWrap(font, Component.translatable("campaign.annocraft1800.ending"), cx, y, w, UiKit.GOLD_TEXT); return; }
        String id = c.getString("mission"), speaker = c.getString("speaker");
        Avatars.portrait(g, speaker, null, cx, y, 28, 28, 0x5a4a32, true, 0);
        UiKit.text(g, Component.translatable("colony.annocraft1800.chapter", c.getInt("chapter"), Component.translatable("campaign.annocraft1800." + id + ".title")), cx + 32, y + 3, UiKit.GOLD_TEXT, w - 32);
        UiKit.text(g, Component.translatable("speaker.annocraft1800." + speaker), cx + 32, y + 15, UiKit.MUTED, w - 32);
        y += 34;
        Component text = Component.translatable("campaign.annocraft1800." + id + ".text");
        g.drawWordWrap(font, text, cx, y, w, UiKit.TEXT);
        y += font.wordWrapHeight(text, w) + 8;
        heading(g, Component.translatable("colony.annocraft1800.objectives"), cx, y, w); y += 12;
        for (Tag t : c.getList("objectives", Tag.TAG_COMPOUND)) {
            CompoundTag o = (CompoundTag) t;
            int done = o.getInt("progress"), amount = Math.max(1, o.getInt("amount"));
            boolean ok = done >= amount;
            g.drawString(font, ok ? "✔" : "•", cx, y, ok ? UiKit.GOOD : UiKit.GOLD, false);
            UiKit.text(g, objective(o), cx + 10, y, ok ? UiKit.GOOD : UiKit.TEXT, w - 60);
            g.drawString(font, done + "/" + amount, cx + w - font.width(done + "/" + amount), y, UiKit.MUTED, false);
            UiKit.bar(g, cx + 10, y + 9, w - 10, 2, (double) done / amount, ok ? UiKit.GOOD : UiKit.GOLD);
            y += 14;
        }
        UiKit.icon(g, "coins", cx, y + 3, 10);
        UiKit.text(g, Component.translatable("colony.annocraft1800.reward", c.getInt("reward")), cx + 13, y + 4, UiKit.GOLD_TEXT, w - 13);
    }
    static Component objective(CompoundTag o) {
        String type = o.getString("type"), target = o.getString("target"); int amount = o.getInt("amount");
        Component name = switch (type) {
            case "residents" -> Component.translatable("tier.annocraft1800." + target);
            case "buildings" -> Component.translatable("building.annocraft1800." + target);
            case "stock" -> Component.translatable("good.annocraft1800." + target);
            case "stance", "eliminated" -> Component.translatable("faction.annocraft1800." + target);
            case "world_islands" -> Component.translatable("world.annocraft1800." + target);
            default -> Component.empty();
        };
        Object count = type.equals("stance") ? Component.translatable("stance.annocraft1800." + Diplomacy.Stance.values()[Math.min(amount, 4)].name().toLowerCase(Locale.ROOT)) : amount;
        return Component.translatable("objective.annocraft1800." + type, name, count);
    }

    // ---------------------------------------------------------------- frame

    @Override public void tick() {
        // Live data: refresh the widgets when a new economy snapshot arrives.
        if (ClientState.economy != lastEconomy) { lastEconomy = ClientState.economy; rebuild(); }
    }
    @Override public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        tips.clear();
        // The management view's HUD stays in place behind the window, as in Anno.
        if (parent instanceof RtsScreen) parent.render(g, -1000, -1000, partialTick);
        else g.fill(0, 0, width, height, 0x90101418);
        g.fill(wx + 3, wy + 3, wx + ww + 3, wy + wh + 3, 0x70000000);
        g.fill(wx, wy, wx + ww, wy + wh, 0xff1f1c17);
        UiKit.panel(g, wx, wy, ww, wh);
        // Title bar with the tab's emblem; the rail of tabs down the left edge.
        g.fill(wx + 1, wy + 1, wx + ww - 1, wy + HEADER, 0xf0332c22); g.fill(wx + 1, wy + HEADER, wx + ww - 1, wy + HEADER + 1, UiKit.GOLD);
        int index = Arrays.asList(TABS).indexOf(tab);
        UiKit.icon(g, TAB_ICONS[Math.max(0, index)], wx + 4, wy + 2, 14);
        UiKit.text(g, Component.translatable("colony.annocraft1800.title").append(" · ").append(Component.translatable("colony.annocraft1800.tab." + tab)), wx + 22, wy + 5, UiKit.GOLD_TEXT, ww - 44);
        g.fill(wx + RAIL, wy + HEADER + 1, wx + RAIL + 1, wy + wh - 1, UiKit.EDGE);
        g.fill(wx + 1, wy + HEADER + 1, wx + RAIL, wy + wh - 1, 0x40000000);
        switch (tab) {
            case "overview" -> renderOverview(g, mouseX, mouseY);
            case "fleet" -> renderFleet(g);
            case "trade" -> renderTrade(g);
            case "diplomacy" -> renderDiplomacy(g);
            case "campaign" -> renderCampaign(g);
        }
        super.render(g, mouseX, mouseY, partialTick);
        if (!ClientState.message.isEmpty()) {
            Component m = Component.translatable(ClientState.message);
            int mw = Math.min(ww - 20, font.width(m) + 12), mx = wx + ww / 2 - mw / 2, my = wy + wh + 3;
            UiKit.plate(g, mx, my, mw, 13);
            UiKit.centered(g, Component.literal(font.plainSubstrByWidth(m.getString(), mw - 8)), wx + ww / 2, my + 3, ClientState.messageSuccess ? UiKit.GOOD : UiKit.BAD);
        }
        if (picker != null) picker.render(g, mouseX, mouseY);
        else for (Object[] t : tips) {
            int[] r = (int[]) t[0];
            if (mouseX >= r[0] && mouseY >= r[1] && mouseX < r[0] + r[2] && mouseY < r[1] + r[3]) { g.renderTooltip(font, font.split((Component) t[1], 220), mouseX, mouseY); break; }
        }
    }
    private void heading(GuiGraphics g, Component text, int x, int y, int w) {
        UiKit.text(g, text, x, y, UiKit.GOLD, w);
        int tw = Math.min(w, font.width(text)) + 4;
        if (tw < w) g.fill(x + tw, y + 4, x + w, y + 5, 0x60c8a868);
    }
    private void tip(int x, int y, int w, int h, Component text) { tips.add(new Object[]{new int[]{x, y, w, h}, text}); }
    @Override public boolean mouseClicked(double x, double y, int button) {
        if (picker != null) { picker.click(x, y); picker = null; return true; }
        if (super.mouseClicked(x, y, button)) return true;
        // A click outside the window closes it, so the city underneath is one click away.
        if (button == 0 && (x < wx || y < wy || x >= wx + ww || y >= wy + wh)) { onClose(); return true; }
        return false;
    }
    @Override public boolean mouseScrolled(double x, double y, double delta) {
        if (picker != null) { picker.scroll(delta); return true; }
        if (tab.equals("fleet") && x < cx + listW()) { shipScroll = Math.max(0, shipScroll - (int) Math.signum(delta)); rebuild(); return true; }
        return super.mouseScrolled(x, y, delta);
    }
    @Override public boolean keyPressed(int key, int scan, int modifiers) {
        if (key == GLFW.GLFW_KEY_ESCAPE && picker != null) { picker = null; return true; }
        if (RtsController.COLONY.matches(key, scan)) { onClose(); return true; }
        return super.keyPressed(key, scan, modifiers);
    }
    @Override public boolean isPauseScreen() { return false; }
    @Override public void renderBackground(GuiGraphics g) { }
    @Override public void onClose() { minecraft.setScreen(parent); }

    // ---------------------------------------------------------------- widgets

    /** A labelled choice: the current value in a box with ▾; a click opens its drop-down list. */
    private final class Field extends AbstractButton {
        private final Component label; private final Supplier<Component> value; private final Supplier<String> icon;
        private final Supplier<List<Option>> options; private final Consumer<String> choose;
        Field(int x, int y, int w, Component label, Supplier<Component> value, Supplier<String> icon, Supplier<List<Option>> options, Consumer<String> choose) {
            super(x, y, w, 15, label); this.label = label; this.value = value; this.icon = icon; this.options = options; this.choose = choose;
        }
        @Override public void onPress() { picker = new Picker(getX(), getY() + height, Math.max(width, 120), options.get(), v -> { choose.accept(v); rebuild(); }); }
        @Override protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
            int lw = Math.min(width / 2, font.width(label) + 6);
            UiKit.text(g, label, getX(), getY() + 4, UiKit.MUTED, lw - 2);
            int bx = getX() + lw, bw = width - lw;
            g.fill(bx, getY(), bx + bw, getY() + height, isHovered() ? UiKit.HOVER : UiKit.WELL);
            MapView.frame(g, bx, getY(), bw, height, isHovered() ? UiKit.GOLD : UiKit.EDGE);
            int tx = bx + 3; String ic = icon.get();
            if (ic != null) { UiKit.icon(g, ic, bx + 2, getY() + 1, 13); tx += 14; }
            UiKit.text(g, value.get(), tx, getY() + 4, UiKit.TEXT, bx + bw - tx - 10);
            g.drawString(font, "▾", bx + bw - 8, getY() + 4, UiKit.GOLD, false);
        }
        @Override protected void updateWidgetNarration(NarrationElementOutput output) { defaultButtonNarrationText(output); }
    }
    /** Drop-down list under a field: one entry per row, scrolled with the wheel when long. */
    private final class Picker {
        final int x, w; final List<Option> options; final Consumer<String> choose; final int rows; int y, scroll;
        Picker(int x, int y, int w, List<Option> options, Consumer<String> choose) {
            this.x = Math.min(x, width - w - 2); this.w = w; this.options = options; this.choose = choose;
            rows = Math.max(1, Math.min(options.size(), (height - 8) / 13 - 1));
            this.y = y + rows * 13 + 4 > height - 2 ? Math.max(2, y - 15 - rows * 13 - 4) : y;
        }
        void render(GuiGraphics g, int mouseX, int mouseY) {
            g.pose().pushPose(); g.pose().translate(0, 0, 400);
            int h = rows * 13 + 4;
            UiKit.panel(g, x, y, w, h);
            if (options.isEmpty()) UiKit.text(g, Component.translatable("colony.annocraft1800.none"), x + 4, y + 4, UiKit.MUTED, w - 8);
            for (int i = 0; i < rows && i + scroll < options.size(); i++) {
                Option o = options.get(i + scroll); int ry = y + 2 + i * 13;
                boolean over = mouseX >= x && mouseX < x + w && mouseY >= ry && mouseY < ry + 13;
                if (over && o.enabled()) g.fill(x + 1, ry, x + w - 1, ry + 13, UiKit.SELECTED);
                int tx = x + 4;
                if (o.icon() != null) { UiKit.icon(g, o.icon(), x + 3, ry + 1, 11); tx += 14; }
                UiKit.text(g, o.label(), tx, ry + 3, o.enabled() ? (over ? UiKit.GOLD_TEXT : UiKit.TEXT) : 0xff6e6656, x + w - tx - 4);
            }
            if (options.size() > rows) {
                int bar = Math.max(8, h * rows / options.size()), by = y + (h - bar) * scroll / Math.max(1, options.size() - rows);
                g.fill(x + w - 3, by, x + w - 1, by + bar, UiKit.GOLD);
            }
            g.pose().popPose();
        }
        void click(double mx, double my) {
            if (mx < x || mx >= x + w || my < y + 2) return;
            int i = (int) ((my - y - 2) / 13) + scroll;
            if (i >= scroll && i < scroll + rows && i < options.size() && options.get(i).enabled()) choose.accept(options.get(i).id());
        }
        void scroll(double delta) { scroll = Math.max(0, Math.min(options.size() - rows, scroll - (int) Math.signum(delta))); }
    }
    /** A ship in the fleet list: name, type, hull and where it is. */
    private final class ShipCard extends AbstractButton {
        private final CompoundTag s;
        ShipCard(int x, int y, int w, CompoundTag s) { super(x, y, w, 26, Component.literal(s.getString("name"))); this.s = s; }
        @Override public void onPress() { ship = s.getUUID("id"); rebuild(); }
        @Override protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
            boolean chosen = s.getUUID("id").equals(ship);
            g.fill(getX(), getY(), getX() + width, getY() + height, chosen ? UiKit.SELECTED : isHovered() ? UiKit.HOVER : UiKit.PANEL_LIGHT);
            MapView.frame(g, getX(), getY(), width, height, chosen || isHovered() ? UiKit.GOLD : UiKit.EDGE);
            UiKit.icon(g, "ship", getX() + 3, getY() + 4, 18);
            UiKit.text(g, getMessage(), getX() + 24, getY() + 3, chosen ? UiKit.GOLD_TEXT : UiKit.TEXT, width - 28);
            Component where = s.contains("to") ? Component.literal("⛵ ").append(islandLabel(s.getString("to"), false)) : Component.literal("⚓ ").append(islandLabel(s.getString("at"), false));
            UiKit.text(g, Component.translatable("ship.annocraft1800." + s.getString("type")).append(" · ").append(where), getX() + 24, getY() + 12, UiKit.MUTED, width - 28);
            Maritime.ShipType t = Maritime.type(s.getString("type"));
            double hp = t == null ? 0 : s.getDouble("hp") / t.hp();
            UiKit.bar(g, getX() + 24, getY() + 22, width - 28, 2, hp, hp > .5 ? UiKit.GOOD : hp > .25 ? UiKit.WARN : UiKit.BAD);
        }
        @Override protected void updateWidgetNarration(NarrationElementOutput output) { defaultButtonNarrationText(output); }
    }
    /** A trading partner along the top of the trade window: portrait, name and stance. */
    private final class PartnerTab extends AbstractButton {
        private final String f;
        PartnerTab(int x, int y, int w, String f) { super(x, y, w, 34, Component.translatable("faction.annocraft1800." + f)); this.f = f; }
        @Override public void onPress() { partner = f; rebuild(); }
        @Override protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
            boolean on = f.equals(partner);
            g.fill(getX(), getY(), getX() + width, getY() + height, on ? UiKit.SELECTED : isHovered() ? UiKit.HOVER : UiKit.PANEL_LIGHT);
            MapView.frame(g, getX(), getY(), width, height, on || isHovered() ? UiKit.GOLD : UiKit.EDGE);
            Avatars.portrait(g, Avatars.character(f), null, getX() + 3, getY() + 3, 28, 28, MapView.ownerColor(f) & 0xffffff, f.equals(partner), f.hashCode() % 7);
            UiKit.text(g, getMessage(), getX() + 34, getY() + 7, on ? UiKit.GOLD_TEXT : UiKit.TEXT, width - 38);
            String stance = faction(f).getString("stance");
            UiKit.text(g, faction(f).getBoolean("eliminated") ? Component.translatable("colony.annocraft1800.eliminated") : Component.translatable("stance.annocraft1800." + stance.toLowerCase(Locale.ROOT)),
                    getX() + 34, getY() + 19, stanceColor(stance), width - 38);
        }
        @Override protected void updateWidgetNarration(NarrationElementOutput output) { defaultButtonNarrationText(output); }
    }
    /** A good in the merchant's grid, with the island's stock under it. */
    private final class GoodCell extends AbstractButton {
        private final String id;
        GoodCell(int x, int y, String id) { super(x, y, 20, 20, goodLabel(id)); this.id = id; setTooltip(Tooltip.create(goodLabel(id))); }
        @Override public void onPress() { good = id; rebuild(); }
        @Override protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
            boolean on = id.equals(good);
            g.fill(getX(), getY(), getX() + width, getY() + height, on ? UiKit.SELECTED : isHovered() ? UiKit.HOVER : UiKit.WELL);
            MapView.frame(g, getX(), getY(), width, height, on ? UiKit.GOLD : isHovered() ? UiKit.GOLD : UiKit.EDGE);
            UiKit.icon(g, id, getX() + 2, getY() + 2, 16);
            if (market != null) {
                long stock = (long) Math.floor(ClientState.island(market).getCompound("stock").getDouble(id) + 1e-6);
                if (stock > 0) {
                    g.pose().pushPose(); g.pose().translate(getX() + width, getY() + height - 6, 200); g.pose().scale(.5f, .5f, 1);
                    String s = String.valueOf(stock); g.drawString(font, s, -font.width(s) - 1, 0, UiKit.TEXT, true); g.pose().popPose();
                }
            }
        }
        @Override protected void updateWidgetNarration(NarrationElementOutput output) { defaultButtonNarrationText(output); }
    }
}
