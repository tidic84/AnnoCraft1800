package fr.annocraft.economy;

import net.minecraft.nbt.*;
import java.util.*;

/**
 * Ships, trade routes, fleets at sea, naval battles and sieges. The simulation runs on the server whether or not
 * the chunks are loaded, across both worlds. With a sea to sail on ({@link Colony#navigation()}), every ship has a
 * real position: it moors at its island's quay, sails along sea lanes around the islands, can be sent anywhere on
 * the water and can hunt enemy ships, as Anno's ships do; pirates and rivals at war send their own warships.
 * Without a sea (pure tests), voyages stay abstract and pirates ambush merchants on the way.
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
    private static final String[] RIVAL_NAMES = {"Vipère", "Corbeau", "Sanglier", "Requin", "Tempête", "Brûlot", "Maraudeur", "Écueil"};
    /** Cannon range and the distance at which warships notice an enemy, in blocks. */
    public static final double RANGE = 26, SIGHT = 70;
    public static ShipType type(String id) { return TYPES.stream().filter(t -> t.id().equals(id)).findFirst().orElse(null); }

    public record Stop(String island, String load, String unload) { }
    public static final class Ship {
        public UUID id; public String type, name, at, from, to, order = "idle", target;
        /** Faction of an enemy ship; null for the player's own. */
        public String owner;
        public double hp, progress, duration, dwell;
        /** Real position on the sea (with a navigation): world, block coordinates, heading, and the lane being sailed. */
        public String world; public double x, z, hx = 1, hz; public List<double[]> path;
        /** The ship it is firing at this moment, for the clients' smoke and thunder. */
        public UUID firing;
        double repath, think;
        public final Map<String, Double> cargo = new TreeMap<>();
        public final List<Stop> route = new ArrayList<>();
        public int stop;
        public ShipType kind() { return type(type); }
        public double load() { return cargo.values().stream().mapToDouble(Double::doubleValue).sum(); }
        public boolean sailing() { return to != null; }
        public boolean mine() { return owner == null; }
    }
    private final Map<UUID, Ship> ships = new LinkedHashMap<>();
    private int launched;
    private final Map<String, Double> musters = new HashMap<>();
    private Random random = new Random();
    public void setRandom(Random random) { this.random = random; }
    /** Every ship, the player's and the enemies' at sea. */
    public Collection<Ship> ships() { return Collections.unmodifiableCollection(ships.values()); }
    /** The player's ships only. */
    public List<Ship> fleet() { return ships.values().stream().filter(Ship::mine).toList(); }
    public Ship ship(UUID id) { return ships.get(id); }
    public int routes() { return (int) fleet().stream().filter(s -> "route".equals(s.order)).count(); }
    public double upkeep() { return fleet().stream().mapToInt(s -> s.kind().upkeep()).sum(); }
    public int dockedAttack(String island) {
        return fleet().stream().filter(s -> island.equals(s.at)).mapToInt(s -> s.kind().attack()).sum();
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
        moor(colony, s);
        colony.event(Colony.Event.of(true, "event.annocraft1800.ship_launched", s.name, "#ship.annocraft1800." + t.id()));
        return null;
    }
    public String scrap(UUID id) { Ship s = ships.get(id); if (s == null || !s.mine()) return "invalid_ship"; ships.remove(id); return null; }
    /** Two-stop trade route: carries {@code goodOut} from A to B and {@code goodBack} from B to A. */
    public String assignRoute(Colony colony, UUID id, String a, String b, String goodOut, String goodBack) {
        Ship s = mine(id);
        if (s == null) return "invalid_ship";
        if (a.equals(b) || !colony.ports().contains(a) || !colony.ports().contains(b)) return "invalid_route";
        s.route.clear(); s.route.add(new Stop(a, goodOut, goodBack)); s.route.add(new Stop(b, goodBack, goodOut));
        s.order = "route"; s.target = null; s.stop = 0;
        sail(colony, s, a);
        return null;
    }
    public String move(Colony colony, UUID id, String island) {
        Ship s = mine(id);
        if (s == null || !colony.geography().exists(island)) return "invalid_ship";
        s.route.clear(); s.order = "move"; s.target = island; sail(colony, s, island); return null;
    }
    public String attack(Colony colony, UUID id, String island) {
        Ship s = mine(id);
        if (s == null || !s.kind().military()) return "not_military";
        String owner = colony.diplomacy().owner(island);
        if (owner == null || colony.diplomacy().mine(owner) || !colony.diplomacy().hostile(owner)) return "not_at_war";
        s.route.clear(); s.order = "attack"; s.target = island; sail(colony, s, island); return null;
    }
    public String escort(UUID id, UUID protectedShip) {
        Ship s = mine(id), p = mine(protectedShip);
        if (s == null || p == null || s == p) return "invalid_ship";
        if (!s.kind().military()) return "not_military";
        s.route.clear(); s.order = "escort"; s.target = protectedShip.toString(); return null;
    }
    /** Sends a ship to a point of the open sea in its world, as a right click on the water does in Anno. */
    public String sailTo(Colony colony, UUID id, double x, double z) {
        Ship s = mine(id); Colony.Navigation nav = colony.navigation();
        if (s == null || nav == null || s.world == null) return "invalid_ship";
        if (s.sailing() && !s.world.equals(colony.geography().world(s.to))) return "ship_away";
        SeaRoutes.Water water = nav.water(s.world);
        if (!water.at((int) Math.floor(x), (int) Math.floor(z))) {
            // A click on the shallows: the nearest water deep enough, if close.
            double[] near = null;
            for (int r = 1; r <= 16 && near == null; r++) for (int a = 0; a < 16 && near == null; a++) {
                double px = x + Math.cos(a * Math.PI / 8) * r, pz = z + Math.sin(a * Math.PI / 8) * r;
                if (water.at((int) Math.floor(px), (int) Math.floor(pz))) near = new double[]{px, pz};
            }
            if (near == null) return "not_water";
            x = near[0]; z = near[1];
        }
        s.route.clear(); s.order = "goto"; s.target = null; s.at = null; s.from = null; s.to = null;
        lane(colony, s, x, z);
        return null;
    }
    /** Orders a warship to hunt an enemy ship until one of them sinks. */
    public String hunt(Colony colony, UUID id, UUID prey) {
        Ship s = mine(id), p = find(colony, prey);
        if (s == null || p == null || p == s || ships.containsKey(prey) && p.mine()) return "invalid_ship";
        if (!s.kind().military()) return "not_military";
        if (!enemy(colony, s, p)) return "not_at_war";
        if (s.sailing() || s.world == null || !s.world.equals(p.world)) return "ship_away";
        s.route.clear(); s.order = "hunt"; s.target = prey.toString(); s.at = null; s.repath = 0;
        return null;
    }
    private Ship mine(UUID id) { Ship s = id == null ? null : ships.get(id); return s != null && s.mine() ? s : null; }
    /** A ship of this sea: ours, an enemy faction's, or another company's. */
    private Ship find(Colony colony, UUID id) {
        Ship s = ships.get(id); if (s != null) return s;
        for (Ship o : colony.foreignShips()) if (o.id.equals(id)) return o;
        return null;
    }
    /** Whether two ships fight: ours against factions at war and companies at war; faction ships against ours. */
    private boolean enemy(Colony colony, Ship s, Ship o) {
        boolean ours = ships.get(o.id) == o;
        if (s.mine()) return ours ? !o.mine() && colony.diplomacy().hostile(o.owner) : colony.atWar(colony.companyOf(o));
        return ours && o.mine() && colony.diplomacy().hostile(s.owner);
    }

    // ---------------------------------------------------------------- movement

    /** Puts a ship at its berth: the n-th place along its island's quay. */
    private void moor(Colony colony, Ship s) {
        Colony.Navigation nav = colony.navigation();
        if (nav == null || s.at == null) return;
        s.world = colony.geography().world(s.at);
        int slot = 0;
        for (Ship o : ships.values()) { if (o == s) break; if (s.at.equals(o.at) && !o.sailing() && o.path == null) slot++; }
        double[] d = nav.dock(s.at, slot);
        s.x = d[0]; s.z = d[1]; s.hx = d[2]; s.hz = d[3]; s.path = null;
    }
    /** Starts sailing along a sea lane from where the ship is to (x, z). */
    private void lane(Colony colony, Ship s, double x, double z) {
        Colony.Navigation nav = colony.navigation();
        s.path = SeaRoutes.path(nav.water(s.world), nav.half(s.world), s.x, s.z, x, z);
        s.progress = 0; s.duration = Math.max(2, SeaRoutes.length(s.path) / speed(s));
    }
    /** Blocks per second on the water; the economy's speeds are sped up a little for the eye. */
    private static double speed(Ship s) { return s.kind().speed() * 1.2; }
    private void sail(Colony colony, Ship s, String island) {
        String origin = s.at != null ? s.at : s.to;
        if (island.equals(s.at)) { arrive(colony, s); return; }
        Colony.Navigation nav = colony.navigation();
        s.from = origin; s.to = island; s.at = null; s.progress = 0;
        String there = colony.geography().world(island);
        if (nav != null && s.world != null && s.world.equals(there)) {
            // Out of the harbour, along the lanes, into the other harbour.
            double[] d = nav.dock(island, 0);
            s.path = SeaRoutes.path(nav.water(s.world), nav.half(s.world), s.x, s.z, d[0], d[1]);
            s.duration = Math.max(5, SeaRoutes.length(s.path) / speed(s));
        } else {
            s.path = null;
            s.duration = origin == null ? 30 : colony.geography().travelSeconds(origin, island, s.kind().speed());
        }
    }

    public void step(Colony colony, double dt) {
        colony.economy().setExtraUpkeep(upkeep());
        Colony.Navigation nav = colony.navigation();
        if (nav != null) { muster(colony, dt); fight(colony, dt); }
        for (Ship s : List.copyOf(ships.values())) {
            if (!ships.containsKey(s.id)) continue;
            s.firing = null;
            if (!s.mine()) { if (nav != null) rival(colony, s, dt); continue; }
            // Ships from saves made before real positions: moored at their island on the first step.
            if (nav != null && s.world == null && s.at != null) moor(colony, s);
            if ("escort".equals(s.order)) { follow(s); continue; }
            if ("hunt".equals(s.order)) { if (nav != null) chase(colony, s, dt); continue; }
            if ("goto".equals(s.order)) {
                if (s.path != null) {
                    s.progress = Math.min(1, s.progress + dt / s.duration); place(s);
                    if (s.progress >= 1) { s.path = null; s.order = "idle"; }
                }
                continue;
            }
            if (s.sailing()) {
                s.progress += dt / s.duration;
                if (s.path != null) place(s);
                if (nav == null && !s.kind().military()) for (Diplomacy.FactionType f : Diplomacy.FACTIONS)
                    if (colony.diplomacy().hostile(f.id()) && random.nextDouble() < .5 * dt / 600 && !ambush(colony, s, f)) break;
                if (ships.containsKey(s.id) && s.progress >= 1) {
                    s.at = s.to; s.to = null; s.progress = 0; s.path = null;
                    if (nav != null) { s.world = colony.geography().world(s.at); moor(colony, s); }
                    arrive(colony, s);
                }
                continue;
            }
            if (colony.hasShipyard(s.at)) s.hp = Math.min(s.kind().hp(), s.hp + 5 * dt);
            if ("route".equals(s.order) && !s.route.isEmpty() && (s.dwell -= dt) <= 0) {
                s.stop = (s.stop + 1) % s.route.size(); sail(colony, s, s.route.get(s.stop).island());
            }
        }
        // Sieges: every warship ordered to attack and anchored at its target.
        Map<String, List<Ship>> sieges = new TreeMap<>();
        for (Ship s : fleet()) if ("attack".equals(s.order) && s.at != null && s.at.equals(s.target)) sieges.computeIfAbsent(s.at, k -> new ArrayList<>()).add(s);
        sieges.forEach((island, besiegers) -> {
            double fire = colony.diplomacy().garrisonFire(island) * dt / besiegers.size();
            int attack = besiegers.stream().mapToInt(s -> s.kind().attack()).sum();
            if (colony.diplomacy().siege(colony, island, attack, dt)) besiegers.forEach(s -> { s.order = "idle"; s.target = null; });
            else for (Ship s : besiegers) if ((s.hp -= fire) <= 0) sink(colony, s);
        });
    }
    private static void place(Ship s) {
        double[] p = SeaRoutes.along(s.path, s.progress);
        s.x = p[0]; s.z = p[1];
        double l = Math.hypot(p[2], p[3]); if (l > 1e-6) { s.hx = p[2] / l; s.hz = p[3] / l; }
    }
    private void follow(Ship s) {
        Ship p;
        try { p = ships.get(UUID.fromString(s.target)); } catch (RuntimeException e) { p = null; }
        if (p == null) { s.order = "idle"; s.target = null; if (s.at == null && s.to != null) { s.at = s.to; s.to = null; } return; }
        s.at = p.at; s.from = p.from; s.to = p.to; s.progress = p.progress; s.duration = p.duration;
        // Sails a ship's length off the protected ship's quarter.
        s.world = p.world; s.hx = p.hx; s.hz = p.hz; s.x = p.x - p.hz * 9 - p.hx * 6; s.z = p.z + p.hx * 9 - p.hz * 6;
    }
    /** A hunting warship closes in on its prey, re-plotting its course as the prey moves, and stops in range. */
    private void chase(Colony colony, Ship s, double dt) {
        Ship prey;
        try { prey = find(colony, UUID.fromString(s.target)); } catch (RuntimeException e) { prey = null; }
        if (prey == null || prey.world == null || !prey.world.equals(s.world)) { s.order = "idle"; s.target = null; s.path = null; return; }
        double d = Math.hypot(prey.x - s.x, prey.z - s.z);
        if (d <= RANGE * .8) { s.path = null; return; }
        if (s.path == null || (s.repath -= dt) <= 0) { lane(colony, s, prey.x, prey.z); s.repath = 3; }
        s.progress = Math.min(1, s.progress + dt / s.duration); place(s);
    }
    /** Naval combat: every warship fires at the nearest enemy in range, its hunted prey first. */
    private void fight(Colony colony, double dt) {
        List<Ship> all = List.copyOf(ships.values());
        for (Ship s : all) {
            if (!ships.containsKey(s.id) || s.world == null || s.kind().attack() <= 0 || s.sailing() && s.path == null) continue;
            Ship prey = null;
            if ("hunt".equals(s.order)) try { prey = find(colony, UUID.fromString(s.target)); } catch (RuntimeException ignored) { }
            if (prey == null || Math.hypot(prey.x - s.x, prey.z - s.z) > RANGE) prey = nearestEnemy(colony, s, RANGE);
            if (prey == null) continue;
            s.firing = prey.id;
            prey.hp -= s.kind().attack() * .3 * dt;
            if (prey.hp <= 0) {
                if (ships.get(prey.id) != prey) {
                    // Another company's ship: its own fleet loses it.
                    colony.sinkForeign(prey); colony.diplomacy().victory();
                    colony.event(Colony.Event.of(true, "event.annocraft1800.rival_sunk", prey.name));
                } else if (prey.mine()) sink(colony, prey);
                else {
                    ships.remove(prey.id); colony.diplomacy().victory();
                    int level = Diplomacy.type(prey.owner) == null ? 1 : Diplomacy.type(prey.owner).level();
                    colony.economy().addCoins(250 * level);
                    colony.event(Colony.Event.of(true, "event.annocraft1800.enemy_sunk", prey.name, "#faction.annocraft1800." + prey.owner, String.valueOf(250 * level)));
                }
            }
        }
    }
    private Ship nearestEnemy(Colony colony, Ship s, double within) {
        Ship best = null; double bestD = within;
        List<Ship> around = new ArrayList<>(ships.values()); if (s.mine()) around.addAll(colony.foreignShips());
        for (Ship o : around) {
            if (o == s || o.world == null || !o.world.equals(s.world) || o.sailing() && o.path == null) continue;
            if (!enemy(colony, s, o)) continue;
            double d = Math.hypot(o.x - s.x, o.z - s.z);
            if (d < bestD) { bestD = d; best = o; }
        }
        return best;
    }
    /**
     * Rivals at war send warships out of their harbours, a few at a time, in the worlds where the player has ports;
     * when peace returns, their ships sail home and disappear.
     */
    private void muster(Colony colony, double dt) {
        Colony.Navigation nav = colony.navigation();
        for (Diplomacy.FactionType f : Diplomacy.FACTIONS) {
            boolean hostile = colony.diplomacy().hostile(f.id());
            if (!hostile) { ships.values().removeIf(s -> f.id().equals(s.owner)); continue; }
            long afloat = ships.values().stream().filter(s -> f.id().equals(s.owner)).count();
            double wait = musters.merge(f.id(), -dt, Double::sum);
            if (afloat >= (f.pirate() ? 2 : f.level()) || wait > 0) continue;
            for (String island : colony.diplomacy().factionIslands(f.id())) {
                String world = colony.geography().world(island);
                if (colony.ports().stream().noneMatch(p -> colony.geography().world(p).equals(world))) continue;
                Ship s = new Ship(); s.id = UUID.randomUUID(); s.owner = f.id();
                s.type = f.level() >= 3 ? "battleship" : "frigate"; s.hp = s.kind().hp();
                s.name = RIVAL_NAMES[random.nextInt(RIVAL_NAMES.length)];
                s.world = world; double[] d = nav.dock(island, 0); s.x = d[0]; s.z = d[1]; s.hx = d[4]; s.hz = d[5];
                ships.put(s.id, s);
                colony.event(Colony.Event.of(false, "event.annocraft1800.enemy_fleet", "#faction.annocraft1800." + f.id(), island));
                // The next warship sails out five minutes later.
                musters.put(f.id(), 300.0);
                break;
            }
        }
    }
    /** An enemy warship hunts the nearest of the player's ships it sees, otherwise prowls off a player harbour. */
    private void rival(Colony colony, Ship s, double dt) {
        Ship prey = nearestEnemy(colony, s, SIGHT);
        if (prey != null) {
            double d = Math.hypot(prey.x - s.x, prey.z - s.z);
            if (d > RANGE * .8 && (s.path == null || (s.repath -= dt) <= 0)) { lane(colony, s, prey.x, prey.z); s.repath = 4; }
            if (d <= RANGE * .8) s.path = null;
        } else if (s.path == null && (s.think -= dt) <= 0) {
            List<String> ports = colony.ports().stream().filter(p -> colony.geography().world(p).equals(s.world)).toList();
            if (ports.isEmpty()) return;
            double[] d = colony.navigation().dock(ports.get(random.nextInt(ports.size())), 0);
            double a = random.nextDouble() * Math.PI * 2, r = 60 + random.nextDouble() * 60;
            double tx = d[0] + d[4] * 50 + Math.cos(a) * r, tz = d[1] + d[5] * 50 + Math.sin(a) * r;
            if (colony.navigation().water(s.world).at((int) tx, (int) tz)) lane(colony, s, tx, tz);
            s.think = 20;
        }
        if (s.path != null) { s.progress = Math.min(1, s.progress + dt / s.duration); place(s); if (s.progress >= 1) s.path = null; }
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
    /** A hostile squadron intercepts a merchant ship (abstract voyages only). @return false if the ship sank. */
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
    /** One of our ships sunk by another company's. */
    public void lose(Colony colony, UUID id) { Ship s = ships.get(id); if (s != null) sink(colony, s); }
    private void sink(Colony colony, Ship s) {
        ships.remove(s.id);
        colony.event(Colony.Event.of(false, "event.annocraft1800.ship_sunk", s.name));
    }

    // ---------------------------------------------------------------- persistence

    public CompoundTag save() {
        CompoundTag t = new CompoundTag(); t.putInt("launched", launched);
        ListTag list = new ListTag();
        for (Ship s : ships.values()) list.add(save(s));
        t.put("ships", list);
        return t;
    }
    /** One ship, as saved and as the clients see it. */
    public static CompoundTag save(Ship s) {
            CompoundTag c = new CompoundTag(); c.putUUID("id", s.id); c.putString("type", s.type); c.putString("name", s.name);
            c.putString("order", s.order); if (s.target != null) c.putString("target", s.target);
            if (s.owner != null) c.putString("owner", s.owner);
            if (s.at != null) c.putString("at", s.at); if (s.from != null) c.putString("from", s.from); if (s.to != null) c.putString("to", s.to);
            c.putDouble("hp", s.hp); c.putDouble("progress", s.progress); c.putDouble("duration", s.duration); c.putDouble("dwell", s.dwell);
            if (s.world != null) {
                c.putString("world", s.world); c.putDouble("x", s.x); c.putDouble("z", s.z); c.putDouble("hx", s.hx); c.putDouble("hz", s.hz);
                if (s.path != null) { ListTag p = new ListTag(); for (double[] q : s.path) { p.add(DoubleTag.valueOf(q[0])); p.add(DoubleTag.valueOf(q[1])); } c.put("path", p); }
            }
            if (s.firing != null) c.putUUID("firing", s.firing);
            CompoundTag cargo = new CompoundTag(); s.cargo.forEach(cargo::putDouble); c.put("cargo", cargo);
            ListTag route = new ListTag();
            for (Stop stop : s.route) {
                CompoundTag r = new CompoundTag(); r.putString("island", stop.island());
                if (stop.load() != null) r.putString("load", stop.load()); if (stop.unload() != null) r.putString("unload", stop.unload());
                route.add(r);
            }
            c.put("route", route); c.putInt("stop", s.stop);
        return c;
    }
    public static Maritime load(CompoundTag t) {
        Maritime m = new Maritime(); m.launched = t.getInt("launched");
        for (Tag entry : t.getList("ships", Tag.TAG_COMPOUND)) {
            CompoundTag c = (CompoundTag) entry;
            if (type(c.getString("type")) == null) continue;
            Ship s = new Ship(); s.id = c.getUUID("id"); s.type = c.getString("type"); s.name = c.getString("name");
            s.order = c.getString("order"); s.target = c.contains("target") ? c.getString("target") : null;
            s.owner = c.contains("owner") ? c.getString("owner") : null;
            s.at = c.contains("at") ? c.getString("at") : null; s.from = c.contains("from") ? c.getString("from") : null; s.to = c.contains("to") ? c.getString("to") : null;
            s.hp = c.getDouble("hp"); s.progress = c.getDouble("progress"); s.duration = c.getDouble("duration"); s.dwell = c.getDouble("dwell");
            if (c.contains("world")) {
                s.world = c.getString("world"); s.x = c.getDouble("x"); s.z = c.getDouble("z"); s.hx = c.getDouble("hx"); s.hz = c.getDouble("hz");
                if (c.contains("path")) { ListTag p = c.getList("path", Tag.TAG_DOUBLE); s.path = new ArrayList<>(); for (int i = 0; i + 1 < p.size(); i += 2) s.path.add(new double[]{p.getDouble(i), p.getDouble(i + 1)}); }
            }
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
