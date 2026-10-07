package fr.annocraft.economy;

import net.minecraft.nbt.*;
import java.util.*;

/**
 * Ships, trade routes, pirate raids, escorts and sieges. Ships are abstract: their positions are computed from
 * island harbours, so voyages continue in unloaded chunks and across both worlds.
 */
public final class Maritime {
    public record ShipType(String id, Map<String, Integer> cost, int slots, int slotSize, double speed, int hp, int attack, int upkeep, String unlockTier) {
        public int capacity() { return slots * slotSize; }
        public boolean military() { return attack >= 40; }
    }
    public static final List<ShipType> TYPES = List.of(
            new ShipType("schooner", Map.of("coins", 500, "timber", 10), 2, 50, 6, 100, 0, 10, null),
            new ShipType("clipper", Map.of("coins", 1500, "timber", 15, "sails", 10), 4, 50, 9, 150, 10, 25, "workers"),
            new ShipType("frigate", Map.of("coins", 3000, "timber", 20, "sails", 10, "weapons", 10), 1, 30, 9, 400, 50, 60, "workers"),
            new ShipType("steam_freighter", Map.of("coins", 6000, "steel_beams", 20), 6, 60, 12, 300, 10, 70, "engineers"),
            new ShipType("battleship", Map.of("coins", 9000, "steel_beams", 30, "weapons", 25), 1, 30, 8, 1000, 140, 150, "engineers"));
    private static final String[] NAMES = {"Aurore", "Mistral", "Albatros", "Hirondelle", "Persévérance", "Ondine", "Cormoran", "Étoile du Nord", "Belle Marée", "Fortune"};
    public static ShipType type(String id) { return TYPES.stream().filter(t -> t.id().equals(id)).findFirst().orElse(null); }

    public record Stop(String island, String load, String unload) { }
    public static final class Ship {
        public UUID id; public String type, name, at, from, to, order = "idle", target;
        public double hp, progress, duration, dwell;
        public final Map<String, Double> cargo = new TreeMap<>();
        public final List<Stop> route = new ArrayList<>();
        public int stop;
        public ShipType kind() { return type(type); }
        public double load() { return cargo.values().stream().mapToDouble(Double::doubleValue).sum(); }
        public boolean sailing() { return to != null; }
    }
    private final Map<UUID, Ship> ships = new LinkedHashMap<>();
    private int launched;
    private Random random = new Random();
    public void setRandom(Random random) { this.random = random; }
    public Collection<Ship> ships() { return Collections.unmodifiableCollection(ships.values()); }
    public Ship ship(UUID id) { return ships.get(id); }
    public int routes() { return (int) ships.values().stream().filter(s -> "route".equals(s.order)).count(); }
    public double upkeep() { return ships.values().stream().mapToInt(s -> s.kind().upkeep()).sum(); }
    public int dockedAttack(String island) {
        return ships.values().stream().filter(s -> island.equals(s.at)).mapToInt(s -> s.kind().attack()).sum();
    }

    /** Builds a ship at a player island with a shipyard. @return null on success, otherwise a message suffix. */
    public String build(Colony colony, String island, String typeId) {
        ShipType t = type(typeId); ColonyEconomy e = colony.economy();
        if (t == null) return "invalid_ship";
        if (!colony.hasShipyard(island)) return "no_shipyard";
        if (!e.sandbox() && t.unlockTier() != null && e.population(t.unlockTier()) < 1) return "locked";
        String cost = e.checkCost(island, t.cost()); if (cost != null) return cost;
        e.pay(island, t.cost());
        Ship s = new Ship(); s.id = UUID.randomUUID(); s.type = t.id(); s.hp = t.hp(); s.at = island;
        s.name = NAMES[launched % NAMES.length] + (launched >= NAMES.length ? " " + (launched / NAMES.length + 1) : "");
        launched++; ships.put(s.id, s);
        colony.event(Colony.Event.of(true, "event.annocraft1800.ship_launched", s.name, "#ship.annocraft1800." + t.id()));
        return null;
    }
    public String scrap(UUID id) { return ships.remove(id) == null ? "invalid_ship" : null; }
    /** Two-stop trade route: carries {@code goodOut} from A to B and {@code goodBack} from B to A. */
    public String assignRoute(Colony colony, UUID id, String a, String b, String goodOut, String goodBack) {
        Ship s = ships.get(id);
        if (s == null) return "invalid_ship";
        if (a.equals(b) || !colony.ports().contains(a) || !colony.ports().contains(b)) return "invalid_route";
        s.route.clear(); s.route.add(new Stop(a, goodOut, goodBack)); s.route.add(new Stop(b, goodBack, goodOut));
        s.order = "route"; s.target = null; s.stop = 0;
        sail(colony, s, a);
        return null;
    }
    public String move(Colony colony, UUID id, String island) {
        Ship s = ships.get(id);
        if (s == null || !colony.geography().exists(island)) return "invalid_ship";
        s.route.clear(); s.order = "move"; s.target = island; sail(colony, s, island); return null;
    }
    public String attack(Colony colony, UUID id, String island) {
        Ship s = ships.get(id);
        if (s == null || !s.kind().military()) return "not_military";
        String owner = colony.diplomacy().owner(island);
        if (owner == null || Diplomacy.PLAYER.equals(owner) || !colony.diplomacy().hostile(owner)) return "not_at_war";
        s.route.clear(); s.order = "attack"; s.target = island; sail(colony, s, island); return null;
    }
    public String escort(UUID id, UUID protectedShip) {
        Ship s = ships.get(id), p = ships.get(protectedShip);
        if (s == null || p == null || s == p) return "invalid_ship";
        if (!s.kind().military()) return "not_military";
        s.route.clear(); s.order = "escort"; s.target = protectedShip.toString(); return null;
    }
    private void sail(Colony colony, Ship s, String island) {
        String origin = s.at != null ? s.at : s.to;
        if (island.equals(s.at)) { arrive(colony, s); return; }
        s.from = origin; s.to = island; s.at = null; s.progress = 0;
        s.duration = colony.geography().travelSeconds(origin, island, s.kind().speed());
    }

    public void step(Colony colony, double dt) {
        colony.economy().setExtraUpkeep(upkeep());
        for (Ship s : List.copyOf(ships.values())) {
            if (!ships.containsKey(s.id)) continue;
            if ("escort".equals(s.order)) { follow(s); continue; }
            if (s.sailing()) {
                s.progress += dt / s.duration;
                if (!s.kind().military()) for (Diplomacy.FactionType f : Diplomacy.FACTIONS)
                    if (colony.diplomacy().hostile(f.id()) && random.nextDouble() < .5 * dt / 600 && !ambush(colony, s, f)) break;
                if (ships.containsKey(s.id) && s.progress >= 1) { s.at = s.to; s.to = null; s.progress = 0; arrive(colony, s); }
                continue;
            }
            if (colony.hasShipyard(s.at)) s.hp = Math.min(s.kind().hp(), s.hp + 5 * dt);
            if ("route".equals(s.order) && !s.route.isEmpty() && (s.dwell -= dt) <= 0) {
                s.stop = (s.stop + 1) % s.route.size(); sail(colony, s, s.route.get(s.stop).island());
            }
        }
        // Sieges: every warship ordered to attack and anchored at its target.
        Map<String, List<Ship>> sieges = new TreeMap<>();
        for (Ship s : ships.values()) if ("attack".equals(s.order) && s.at != null && s.at.equals(s.target)) sieges.computeIfAbsent(s.at, k -> new ArrayList<>()).add(s);
        sieges.forEach((island, fleet) -> {
            double fire = colony.diplomacy().garrisonFire(island) * dt / fleet.size();
            int attack = fleet.stream().mapToInt(s -> s.kind().attack()).sum();
            if (colony.diplomacy().siege(colony, island, attack, dt)) fleet.forEach(s -> { s.order = "idle"; s.target = null; });
            else for (Ship s : fleet) if ((s.hp -= fire) <= 0) sink(colony, s);
        });
    }
    private void follow(Ship s) {
        Ship p;
        try { p = ships.get(UUID.fromString(s.target)); } catch (RuntimeException e) { p = null; }
        if (p == null) { s.order = "idle"; s.target = null; if (s.at == null) { s.at = s.to != null ? s.to : s.from; s.to = null; } return; }
        s.at = p.at; s.from = p.from; s.to = p.to; s.progress = p.progress; s.duration = p.duration;
    }
    private void arrive(Colony colony, Ship s) {
        if (!"route".equals(s.order) || s.route.isEmpty()) return;
        Stop stop = s.route.get(s.stop); ColonyEconomy e = colony.economy();
        if (!colony.ports().contains(stop.island())) return;
        if (stop.unload() != null) {
            double carried = s.cargo.getOrDefault(stop.unload(), 0.0), stored = e.store(stop.island(), stop.unload(), carried);
            if (stored > 0) { s.cargo.merge(stop.unload(), -stored, Double::sum); e.recordFlow(stop.island(), stop.unload(), stored); }
            s.cargo.values().removeIf(v -> v < 1e-6);
        }
        if (stop.load() != null) {
            double room = s.kind().capacity() - s.load(), take = Math.floor(Math.min(room, e.stock(stop.island(), stop.load())));
            if (take > 0) { e.addStock(stop.island(), stop.load(), -take); s.cargo.merge(stop.load(), take, Double::sum); e.recordFlow(stop.island(), stop.load(), -take); }
        }
        s.dwell = 5;
    }
    /** A hostile squadron intercepts a merchant ship. @return false if the ship sank. */
    private boolean ambush(Colony colony, Ship s, Diplomacy.FactionType f) {
        int attack = s.kind().attack();
        for (Ship escort : ships.values()) if ("escort".equals(escort.order) && s.id.toString().equals(escort.target)) attack += escort.kind().attack();
        double enemy = f.level() * 120;
        for (int round = 0; round < 8 && enemy > 0 && s.hp > 0; round++) { enemy -= attack; s.hp -= f.level() * 25 * (attack > s.kind().attack() ? .4 : 1); }
        String faction = "#faction.annocraft1800." + f.id();
        if (s.hp <= 0) { sink(colony, s); return false; }
        if (enemy <= 0) {
            colony.diplomacy().victory();
            colony.event(Colony.Event.of(true, "event.annocraft1800.ambush_won", s.name, faction));
        } else {
            s.cargo.clear();
            colony.event(Colony.Event.of(false, "event.annocraft1800.plundered", s.name, faction));
        }
        return true;
    }
    private void sink(Colony colony, Ship s) {
        ships.remove(s.id);
        colony.event(Colony.Event.of(false, "event.annocraft1800.ship_sunk", s.name));
    }

    public CompoundTag save() {
        CompoundTag t = new CompoundTag(); t.putInt("launched", launched);
        ListTag list = new ListTag();
        for (Ship s : ships.values()) {
            CompoundTag c = new CompoundTag(); c.putUUID("id", s.id); c.putString("type", s.type); c.putString("name", s.name);
            c.putString("order", s.order); if (s.target != null) c.putString("target", s.target);
            if (s.at != null) c.putString("at", s.at); if (s.from != null) c.putString("from", s.from); if (s.to != null) c.putString("to", s.to);
            c.putDouble("hp", s.hp); c.putDouble("progress", s.progress); c.putDouble("duration", s.duration); c.putDouble("dwell", s.dwell);
            CompoundTag cargo = new CompoundTag(); s.cargo.forEach(cargo::putDouble); c.put("cargo", cargo);
            ListTag route = new ListTag();
            for (Stop stop : s.route) {
                CompoundTag r = new CompoundTag(); r.putString("island", stop.island());
                if (stop.load() != null) r.putString("load", stop.load()); if (stop.unload() != null) r.putString("unload", stop.unload());
                route.add(r);
            }
            c.put("route", route); c.putInt("stop", s.stop);
            list.add(c);
        }
        t.put("ships", list);
        return t;
    }
    public static Maritime load(CompoundTag t) {
        Maritime m = new Maritime(); m.launched = t.getInt("launched");
        for (Tag entry : t.getList("ships", Tag.TAG_COMPOUND)) {
            CompoundTag c = (CompoundTag) entry;
            if (type(c.getString("type")) == null) continue;
            Ship s = new Ship(); s.id = c.getUUID("id"); s.type = c.getString("type"); s.name = c.getString("name");
            s.order = c.getString("order"); s.target = c.contains("target") ? c.getString("target") : null;
            s.at = c.contains("at") ? c.getString("at") : null; s.from = c.contains("from") ? c.getString("from") : null; s.to = c.contains("to") ? c.getString("to") : null;
            s.hp = c.getDouble("hp"); s.progress = c.getDouble("progress"); s.duration = c.getDouble("duration"); s.dwell = c.getDouble("dwell");
            CompoundTag cargo = c.getCompound("cargo"); for (String g : cargo.getAllKeys()) s.cargo.put(g, cargo.getDouble(g));
            for (Tag r : c.getList("route", Tag.TAG_COMPOUND)) {
                CompoundTag rc = (CompoundTag) r;
                s.route.add(new Stop(rc.getString("island"), rc.contains("load") ? rc.getString("load") : null, rc.contains("unload") ? rc.getString("unload") : null));
            }
            s.stop = c.getInt("stop");
            m.ships.put(s.id, s);
        }
        return m;
    }
    public CompoundTag snapshot() { return save(); }
}
