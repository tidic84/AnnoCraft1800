package fr.annocraft.client;

import fr.annocraft.economy.*;
import fr.annocraft.network.AnnoNetwork;
import fr.annocraft.world.IslandLayout;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.nbt.*;
import net.minecraft.network.chat.*;
import java.util.*;

/** Colony-wide management: overview, fleet and trade routes, merchants, diplomacy and the campaign. */
public final class ColonyScreen extends Screen {
    static final String[] TABS = {"overview", "fleet", "trade", "diplomacy", "campaign"};
    static String tab = "overview";
    // Selections survive reopening the screen; indexes are clamped to the current lists.
    private static UUID ship;
    private static int type, yard, target, from, to, out, back, escort, partner, market, good;
    private final Screen parent;
    private CompoundTag lastEconomy;
    public ColonyScreen(Screen parent) { super(Component.translatable("colony.annocraft1800.title")); this.parent = parent; }

    @Override protected void init() {
        int w = (width - 16) / TABS.length;
        for (int i = 0; i < TABS.length; i++) {
            String id = TABS[i];
            addRenderableWidget(new UiKit.AnnoButton(8 + i * w, 4, w - 2, 14, Component.translatable("colony.annocraft1800.tab." + id), null, () -> { tab = id; rebuild(); })
                    .highlight(() -> id.equals(tab)));
        }
        switch (tab) {
            case "overview" -> overviewWidgets();
            case "fleet" -> fleetWidgets();
            case "trade" -> tradeWidgets();
            case "diplomacy" -> diplomacyWidgets();
            case "campaign" -> campaignWidgets();
        }
    }
    private void rebuild() { clearWidgets(); init(); }
    private AbstractButton button(Component label, int x, int y, int w, Runnable action) {
        return addRenderableWidget(new UiKit.AnnoButton(x, y, w, 14, label, null, () -> { action.run(); rebuild(); }));
    }
    private static int cycle(int index, int size) { return size == 0 ? 0 : (index + 1) % size; }
    private static <T> T pick(List<T> list, int index) { return list.isEmpty() ? null : list.get(Math.floorMod(index, list.size())); }

    // ---- data helpers ----
    static List<CompoundTag> ships() {
        List<CompoundTag> list = new ArrayList<>();
        ClientState.economy.getCompound("maritime").getList("ships", Tag.TAG_COMPOUND).forEach(t -> list.add((CompoundTag) t));
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
    static List<String> goods() {
        List<String> result = new ArrayList<>(Diplomacy.PRICES.keySet()); Collections.sort(result); return result;
    }
    static List<String> factions() {
        List<String> result = new ArrayList<>(); Diplomacy.FACTIONS.forEach(f -> result.add(f.id())); return result;
    }
    static CompoundTag faction(String id) { return ClientState.economy.getCompound("diplomacy").getCompound("factions").getCompound(id); }
    static Component islandLabel(String id) { return islandLabel(id, true); }
    static Component islandLabel(String id, boolean withOwner) {
        if (id == null) return Component.translatable("colony.annocraft1800.no_island");
        String owner = ClientState.owner(id);
        MutableComponent c = Component.translatable(id.startsWith("nw_") ? "colony.annocraft1800.island_new" : "colony.annocraft1800.island_old", id.replaceAll("\\D+", ""));
        if (withOwner && !owner.isEmpty() && !owner.equals(Diplomacy.PLAYER)) c.append(" (").append(Component.translatable("faction.annocraft1800." + owner)).append(")");
        return c;
    }
    static Component goodLabel(String g) { return g == null ? Component.translatable("colony.annocraft1800.none") : Component.translatable("good.annocraft1800." + g); }

    // ---- tabs ----
    private void overviewWidgets() {
        boolean open = ClientState.economy.getBoolean("new_world_open");
        boolean inNew = IslandLayout.NEW_WORLD.equals(ClientState.world);
        AbstractButton travel = button(Component.translatable(inNew ? "colony.annocraft1800.travel_old" : "colony.annocraft1800.travel_new"), 8, height - 20, 150,
                () -> AnnoNetwork.action("travel", "world", inNew ? IslandLayout.OLD_WORLD : IslandLayout.NEW_WORLD));
        travel.active = inNew || open;
        if (!open && !inNew) travel.setTooltip(Tooltip.create(Component.translatable("message.annocraft1800.new_world_locked")));
    }
    private void fleetWidgets() {
        int x = 8, y = 24;
        for (CompoundTag s : ships()) {
            if (y > height - 30) break;
            UUID id = s.getUUID("id");
            AbstractButton b = button(Component.literal(s.getString("name")), x, y, 110, () -> ship = id);
            b.active = !id.equals(ship); y += 16;
        }
        int rx = 124, cw = (width - rx - 8) / 3;
        List<String> yards = shipyards();
        Maritime.ShipType t = Maritime.TYPES.get(Math.floorMod(type, Maritime.TYPES.size()));
        AbstractButton typeButton = button(Component.translatable("ship.annocraft1800." + t.id()), rx, 24, cw - 2, () -> type = cycle(type, Maritime.TYPES.size()));
        typeButton.setTooltip(Tooltip.create(shipTooltip(t)));
        button(yards.isEmpty() ? Component.translatable("colony.annocraft1800.no_shipyard") : islandLabel(pick(yards, yard)), rx + cw, 24, cw - 2, () -> yard = cycle(yard, yards.size()));
        AbstractButton build = button(Component.translatable("colony.annocraft1800.build_ship"), rx + 2 * cw, 24, cw - 2,
                () -> AnnoNetwork.action("ship_build", "island", pick(yards, yard), "type", t.id()));
        build.active = !yards.isEmpty();
        CompoundTag s = selectedShip();
        if (s == null) return;
        UUID id = s.getUUID("id");
        List<String> islands = islands(), ports = ClientState.ports(), goods = goods();
        int oy = height - 90;
        button(Component.translatable("colony.annocraft1800.target", islandLabel(pick(islands, target))), rx, oy, cw - 2, () -> target = cycle(target, islands.size()));
        button(Component.translatable("colony.annocraft1800.send"), rx + cw, oy, cw / 2 - 2, () -> AnnoNetwork.action("ship_move", "ship", id, "island", pick(islands, target)));
        button(Component.translatable("colony.annocraft1800.attack"), rx + cw + cw / 2, oy, cw / 2 - 2, () -> AnnoNetwork.action("ship_attack", "ship", id, "island", pick(islands, target)));
        button(Component.translatable("colony.annocraft1800.scrap"), rx + 2 * cw, oy, cw - 2, () -> { AnnoNetwork.action("ship_scrap", "ship", id); ship = null; });
        oy += 16;
        button(Component.translatable("colony.annocraft1800.route_a", islandLabel(pick(ports, from))), rx, oy, cw - 2, () -> from = cycle(from, ports.size()));
        button(Component.translatable("colony.annocraft1800.route_b", islandLabel(pick(ports, to))), rx + cw, oy, cw - 2, () -> to = cycle(to, ports.size()));
        AbstractButton route = button(Component.translatable("colony.annocraft1800.route_create"), rx + 2 * cw, oy, cw - 2,
                () -> AnnoNetwork.action("ship_route", "ship", id, "a", pick(ports, from), "b", pick(ports, to),
                        "out", out == 0 ? "" : goods.get(out - 1), "back", back == 0 ? "" : goods.get(back - 1)));
        route.active = ports.size() >= 2;
        oy += 16;
        button(Component.translatable("colony.annocraft1800.route_out", goodLabel(out == 0 ? null : goods.get(out - 1))), rx, oy, cw - 2, () -> out = cycle(out, goods.size() + 1));
        button(Component.translatable("colony.annocraft1800.route_back", goodLabel(back == 0 ? null : goods.get(back - 1))), rx + cw, oy, cw - 2, () -> back = cycle(back, goods.size() + 1));
        List<CompoundTag> others = ships().stream().filter(o -> !o.getUUID("id").equals(id)).toList();
        CompoundTag protectedShip = pick(others, escort);
        oy += 16;
        button(Component.translatable("colony.annocraft1800.protect", protectedShip == null ? Component.translatable("colony.annocraft1800.none") : Component.literal(protectedShip.getString("name"))),
                rx, oy, cw - 2, () -> escort = cycle(escort, others.size()));
        AbstractButton escortButton = button(Component.translatable("colony.annocraft1800.escort"),
                rx + cw, oy, cw - 2, () -> {
                    if (protectedShip != null) AnnoNetwork.action("ship_escort", "ship", id, "target", protectedShip.getUUID("id"));
                });
        escortButton.active = protectedShip != null;
        escortButton.setTooltip(Tooltip.create(Component.translatable("colony.annocraft1800.escort_help")));
    }
    static Component shipTooltip(Maritime.ShipType t) {
        MutableComponent c = Component.translatable("ship.annocraft1800." + t.id()).withStyle(net.minecraft.ChatFormatting.GOLD);
        c.append("\n").append(Component.translatable("tooltip.annocraft1800.cost", RtsScreen.amounts(new TreeMap<>(t.cost()))));
        c.append("\n").append(Component.translatable("colony.annocraft1800.ship_stats", t.slots(), t.slotSize(), t.hp(), t.attack(), t.upkeep()));
        if (t.unlockTier() != null) c.append("\n").append(Component.translatable("tooltip.annocraft1800.unlock", 1, Component.translatable("tier.annocraft1800." + t.unlockTier())));
        return c;
    }
    private void tradeWidgets() {
        List<String> partners = factions(), ports = ClientState.ports(), goods = goods();
        int cw = (width - 16) / 3, y = 24;
        String f = pick(partners, partner), island = pick(ports, market), g = pick(goods, good);
        button(Component.translatable("colony.annocraft1800.partner", Component.translatable("faction.annocraft1800." + f)), 8, y, cw - 2, () -> partner = cycle(partner, partners.size()));
        button(Component.translatable("colony.annocraft1800.market_island", islandLabel(island)), 8 + cw, y, cw - 2, () -> market = cycle(market, ports.size()));
        button(Component.literal("‹"), 8 + 2 * cw, y, 14, () -> good = Math.floorMod(good - 1, goods.size()));
        button(Component.translatable("colony.annocraft1800.good", goodLabel(g)), 8 + 2 * cw + 16, y, cw - 34, () -> good = cycle(good, goods.size()));
        button(Component.literal("›"), 8 + 3 * cw - 16, y, 14, () -> good = cycle(good, goods.size()));
        y = 74;
        for (int amount : new int[]{10, 50}) {
            AbstractButton buy = button(Component.translatable("colony.annocraft1800.buy", amount), 8, y, cw - 2,
                    () -> AnnoNetwork.action("trade_buy", "faction", f, "island", island, "good", g, "amount", amount));
            AbstractButton sell = button(Component.translatable("colony.annocraft1800.sell", amount), 8 + cw, y, cw - 2,
                    () -> AnnoNetwork.action("trade_sell", "faction", f, "island", island, "good", g, "amount", amount));
            boolean ok = island != null && faction(f).getString("stance").length() > 0 && !"WAR".equals(faction(f).getString("stance")) && !faction(f).getBoolean("eliminated");
            buy.active = sell.active = ok;
            y += 16;
        }
    }
    private void diplomacyWidgets() {
        int y = 24, cw = (width - 16) / 6;
        for (String f : factions()) {
            CompoundTag c = faction(f);
            boolean pirate = c.getBoolean("pirate"), dead = c.getBoolean("eliminated");
            String[][] actions = pirate
                    ? new String[][]{{"gift", "gift"}, {"war", "war"}, {"ceasefire", "tribute"}}
                    : new String[][]{{"gift", "gift"}, {"war", "war"}, {"ceasefire", "ceasefire"}, {"peace", "peace"}, {"trade", "trade_treaty"}, {"alliance", "alliance"}};
            for (int i = 0; i < actions.length; i++) {
                String action = actions[i][0];
                AbstractButton b = button(Component.translatable("colony.annocraft1800." + actions[i][1]), 8 + i * cw, y + 22, cw - 2,
                        () -> AnnoNetwork.action("diplomacy", "faction", f, "action", action));
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
            }
            y += 44;
        }
    }
    private void campaignWidgets() {
        CompoundTag c = ClientState.economy.getCompound("campaign");
        if (!c.getBoolean("active")) button(Component.translatable("colony.annocraft1800.campaign_start"), width / 2 - 70, height - 24, 140, () -> AnnoNetwork.action("campaign_start"));
    }

    @Override public void tick() {
        // Live data: refresh the widgets when a new economy snapshot arrives.
        if (ClientState.economy != lastEconomy) { lastEconomy = ClientState.economy; rebuild(); }
    }
    @Override public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        renderBackground(g);
        UiKit.panel(g, 4, 20, width - 8, height - 24);
        switch (tab) {
            case "overview" -> renderOverview(g);
            case "fleet" -> renderFleet(g);
            case "trade" -> renderTrade(g);
            case "diplomacy" -> renderDiplomacy(g);
            case "campaign" -> renderCampaign(g);
        }
        if (!ClientState.message.isEmpty())
            g.drawString(font, font.plainSubstrByWidth(Component.translatable(ClientState.message).getString(), width - 170), 160, height - 16, ClientState.messageSuccess ? 0xff88d4a0 : 0xffff8b7a, false);
        super.render(g, mouseX, mouseY, partialTick);
    }
    private void line(GuiGraphics g, Component text, int x, int y, int color, int maxWidth) {
        g.drawString(font, font.plainSubstrByWidth(text.getString(), maxWidth), x, y, color, false);
    }
    private void renderOverview(GuiGraphics g) {
        CompoundTag e = ClientState.economy; int y = 26, col = (width - 24) / 2;
        long income = Math.round(e.getDouble("income")), upkeep = Math.round(e.getDouble("upkeep"));
        line(g, Component.translatable("colony.annocraft1800.treasury", (long) Math.floor(e.getDouble("coins"))), 10, y, 0xffdfc783, col); y += 11;
        line(g, Component.translatable("colony.annocraft1800.income", income, upkeep, income - upkeep), 10, y, income >= upkeep ? 0xff88d4a0 : 0xffff8b7a, col); y += 11;
        line(g, Component.translatable("colony.annocraft1800.ports", ClientState.ports().size(), ships().size()), 10, y, 0xffffffff, col); y += 14;
        CompoundTag pop = e.getCompound("population"); int total = 0;
        for (String tier : ColonyEconomy.TIERS) {
            int n = pop.getInt(tier); total += n; if (n == 0) continue;
            line(g, Component.translatable("tier.annocraft1800." + tier).append(" : " + n), 14, y, 0xffc1d1d7, col); y += 10;
        }
        line(g, Component.translatable("colony.annocraft1800.population", total), 10, y + 2, 0xffffffff, col);
        // Colony-wide goods: total stock in all ports and net flow per minute.
        Map<String, double[]> totals = new TreeMap<>();
        CompoundTag islands = e.getCompound("islands");
        for (String island : ClientState.ports()) {
            CompoundTag i = islands.getCompound(island);
            for (String good : i.getCompound("stock").getAllKeys()) totals.computeIfAbsent(good, k -> new double[2])[0] += i.getCompound("stock").getDouble(good);
            for (String good : i.getCompound("rates").getAllKeys()) totals.computeIfAbsent(good, k -> new double[2])[1] += i.getCompound("rates").getDouble(good);
        }
        int x = 16 + col; y = 26;
        line(g, Component.translatable("colony.annocraft1800.goods"), x, y, 0xffdfc783, col); y += 12;
        for (var entry : totals.entrySet()) {
            if (y > height - 16) { x += col / 2; y = 38; if (x > width - 40) break; }
            double[] v = entry.getValue();
            String flow = Math.abs(v[1]) < .05 ? "" : String.format(Locale.ROOT, " %+.1f", v[1]);
            line(g, Component.translatable("good.annocraft1800." + entry.getKey()).append(" " + (long) Math.floor(v[0] + 1e-6) + flow), x, y, v[1] < -.05 ? 0xffffb38a : 0xffffffff, col / 2 - 4);
            y += 10;
        }
    }
    private void renderFleet(GuiGraphics g) {
        int rx = 124, y = 42;
        CompoundTag s = selectedShip();
        if (s == null) { line(g, Component.translatable("colony.annocraft1800.no_ships"), rx, y, 0xffa2b6bf, width - rx - 8); return; }
        Maritime.ShipType t = Maritime.type(s.getString("type"));
        line(g, Component.literal(s.getString("name")).append(" · ").append(Component.translatable("ship.annocraft1800." + s.getString("type"))), rx, y, 0xffdfc783, width - rx - 8); y += 11;
        line(g, Component.translatable("colony.annocraft1800.ship_hp", (int) s.getDouble("hp"), t == null ? 0 : t.hp()), rx, y, 0xffffffff, width - rx - 8); y += 11;
        Component where = s.contains("to")
                ? Component.translatable("colony.annocraft1800.ship_sailing", islandLabel(s.getString("to")), Math.round(s.getDouble("progress") * 100))
                : Component.translatable("colony.annocraft1800.ship_at", islandLabel(s.getString("at")));
        line(g, where, rx, y, 0xffffffff, width - rx - 8); y += 11;
        line(g, Component.translatable("colony.annocraft1800.order." + s.getString("order")), rx, y, 0xffc1d1d7, width - rx - 8); y += 11;
        CompoundTag cargo = s.getCompound("cargo");
        Map<String, Long> load = new TreeMap<>(); for (String k : cargo.getAllKeys()) load.put(k, (long) Math.floor(cargo.getDouble(k)));
        line(g, Component.translatable("colony.annocraft1800.ship_cargo", load.isEmpty() ? Component.translatable("colony.annocraft1800.none") : RtsScreen.amounts(load)), rx, y, 0xffffffff, width - rx - 8);
    }
    private void renderTrade(GuiGraphics g) {
        List<String> partners = factions(), ports = ClientState.ports(), goods = goods();
        String f = pick(partners, partner), island = pick(ports, market), good = pick(goods, ColonyScreen.good);
        int y = 42;
        if (ports.isEmpty()) { line(g, Component.translatable("colony.annocraft1800.no_port"), 10, y, 0xffa2b6bf, width - 20); return; }
        CompoundTag c = faction(f);
        line(g, Component.translatable("colony.annocraft1800.stance_line", Component.translatable("faction.annocraft1800." + f),
                Component.translatable("stance.annocraft1800." + c.getString("stance").toLowerCase(Locale.ROOT)), (int) c.getDouble("relation")), 10, y, 0xffdfc783, width - 20); y += 11;
        boolean deal = c.getString("stance").equals("TRADE") || c.getString("stance").equals("ALLIANCE");
        int base = Diplomacy.PRICES.getOrDefault(good, 0);
        line(g, Component.translatable("colony.annocraft1800.prices", Math.round(base * (deal ? 1.0 : 1.25)), Math.round(base * (deal ? .8 : .6))), 10, y, 0xffffffff, width - 20);
        CompoundTag i = ClientState.island(island);
        line(g, Component.translatable("colony.annocraft1800.stock", (long) Math.floor(i.getCompound("stock").getDouble(good) + 1e-6), i.getInt("capacity")), 10, 108, 0xffc1d1d7, width - 20);
    }
    private void renderDiplomacy(GuiGraphics g) {
        int y = 26;
        for (String f : factions()) {
            CompoundTag c = faction(f);
            Component state = c.getBoolean("eliminated") ? Component.translatable("colony.annocraft1800.eliminated")
                    : Component.translatable("stance.annocraft1800." + c.getString("stance").toLowerCase(Locale.ROOT));
            line(g, Component.translatable("faction.annocraft1800." + f).append(" · ").append(state).append(" · ")
                    .append(Component.translatable("colony.annocraft1800.relation", (int) c.getDouble("relation"))), 10, y, 0xffdfc783, width - 20);
            double rel = c.getDouble("relation"); int bw = Math.min(120, width / 4), bx = width - bw - 12;
            UiKit.bar(g, bx, y + 2, bw, 5, (rel + 100) / 200, rel >= 30 ? UiKit.GOOD : rel >= 0 ? UiKit.GOLD : UiKit.BAD);
            g.fill(bx + bw / 2, y + 1, bx + bw / 2 + 1, y + 8, 0xffffffff);
            List<String> owned = new ArrayList<>();
            CompoundTag owners = ClientState.economy.getCompound("diplomacy").getCompound("owners");
            for (String island : owners.getAllKeys()) if (owners.getString(island).equals(f)) owned.add(islandLabel(island, false).getString());
            line(g, Component.translatable("faction.annocraft1800." + f + ".about").append(owned.isEmpty() ? "" : " · " + String.join(", ", owned)), 10, y + 11, 0xffc1d1d7, width - 20);
            y += 44;
        }
    }
    private void renderCampaign(GuiGraphics g) {
        CompoundTag c = ClientState.economy.getCompound("campaign"); int y = 26, w = width - 24;
        if (!c.getBoolean("active")) {
            g.drawWordWrap(font, Component.translatable("colony.annocraft1800.campaign_intro"), 12, y, w, 0xffffffff); return;
        }
        if (c.getBoolean("finished") || !c.contains("mission")) {
            g.drawWordWrap(font, Component.translatable("campaign.annocraft1800.ending"), 12, y, w, 0xffdfc783); return;
        }
        String id = c.getString("mission");
        line(g, Component.translatable("colony.annocraft1800.chapter", c.getInt("chapter"), Component.translatable("campaign.annocraft1800." + id + ".title")), 12, y, 0xffdfc783, w); y += 12;
        line(g, Component.translatable("speaker.annocraft1800." + c.getString("speaker")), 12, y, 0xffa2b6bf, w); y += 11;
        Component text = Component.translatable("campaign.annocraft1800." + id + ".text");
        g.drawWordWrap(font, text, 12, y, w, 0xffffffff);
        y += font.wordWrapHeight(text, w) + 6;
        for (Tag t : c.getList("objectives", Tag.TAG_COMPOUND)) {
            CompoundTag o = (CompoundTag) t;
            boolean done = o.getInt("progress") >= o.getInt("amount");
            line(g, Component.literal(done ? "✔ " : "• ").append(objective(o)).append(" (" + o.getInt("progress") + "/" + o.getInt("amount") + ")"), 16, y, done ? 0xff88d4a0 : 0xffffffff, w - 4);
            y += 10;
        }
        line(g, Component.translatable("colony.annocraft1800.reward", c.getInt("reward")), 12, y + 4, 0xffc1d1d7, w);
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
    @Override public boolean isPauseScreen() { return false; }
    @Override public void onClose() { minecraft.setScreen(parent); }
}
