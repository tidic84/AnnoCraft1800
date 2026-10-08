package fr.annocraft.server;

import fr.annocraft.building.BuildingInstance;
import fr.annocraft.economy.*;
import fr.annocraft.world.IslandLayout;
import net.minecraft.nbt.*;
import java.util.*;

/**
 * One company's colony: its treasury and island stocks, its fleet, its relations and its campaign, over the islands
 * it holds and the buildings on them. A cooperative game has one, shared by every player ({@link ColonyData#COLONY});
 * a competitive game one per player, side by side on the same archipelago.
 */
public final class CompanyColony implements Colony {
    final ColonyData world;
    final String id;
    ColonyEconomy economy = new ColonyEconomy();
    Diplomacy diplomacy;
    Maritime maritime = new Maritime();
    Campaign campaign = new Campaign();
    private long portsRevision = -1;
    private Set<String> ports = Set.of();
    private final Map<String, Long> alerts = new HashMap<>();

    CompanyColony(ColonyData world, String id, Diplomacy diplomacy) { this.world = world; this.id = id; this.diplomacy = diplomacy; }
    public String id() { return id; }
    @Override public ColonyEconomy economy() { return economy; }
    @Override public Diplomacy diplomacy() { return diplomacy; }
    @Override public Maritime maritime() { return maritime; }
    @Override public Geography geography() { return world.geography(); }
    @Override public Navigation navigation() { return world.navigation(); }
    public Campaign campaign() { return campaign; }
    @Override public void event(Event event) { world.event(id, event); }

    /** Whether a building belongs to this company: it stands on an island the company holds (or a free one, in a cooperative game). */
    public boolean owns(BuildingInstance b) {
        String owner = diplomacy.owner(b.island());
        return id.equals(owner) || owner == null && !world.competitive();
    }
    public List<BuildingInstance> buildings() { return world.buildings().values().stream().filter(this::owns).toList(); }
    public List<ColonyEconomy.Site> sites() { return buildings().stream().map(ColonyData::site).toList(); }
    @Override public Set<String> ports() {
        if (portsRevision != world.revision()) {
            Set<String> result = new TreeSet<>();
            for (BuildingInstance b : world.buildings().values())
                if (ColonyData.profile(b).storageNode() && id.equals(diplomacy.owner(b.island()))) result.add(b.island());
            ports = Collections.unmodifiableSet(result); portsRevision = world.revision();
        }
        return ports;
    }
    @Override public boolean hasShipyard(String island) {
        return island != null && buildings().stream().anyMatch(b -> b.island().equals(island) && ColonyData.profile(b).shipyard());
    }
    @Override public int defense(String island) {
        return buildings().stream().filter(b -> b.island().equals(island)).mapToInt(b -> ColonyData.profile(b).defense()).sum();
    }
    @Override public Collection<Maritime.Ship> foreignShips() { return world.foreignShips(id); }
    @Override public boolean atWar(String company) { return world.atWar(id, company); }
    @Override public void sinkForeign(Maritime.Ship ship) { world.sinkForeign(id, ship); }
    @Override public String companyOf(Maritime.Ship ship) { return world.companyOfShip(ship); }

    public boolean newWorldOpen() {
        return economy.sandbox() || campaign.newWorldUnlocked() || economy.population("artisans") > 0
                || ports().stream().anyMatch(i -> IslandLayout.NEW_WORLD.equals(world.geography().world(i)));
    }
    /** Warns the company when an island runs out of a good its residents need (at most every ten minutes per good). */
    void shortages(long simulated) {
        Map<String, Set<String>> wanted = new TreeMap<>();
        for (BuildingInstance b : buildings()) {
            EconomyProfile p = ColonyData.profile(b); var s = economy.state(b.id());
            if (p.housing() && s != null && s.connected) wanted.computeIfAbsent(b.island(), k -> new TreeSet<>()).addAll(p.needs().keySet());
        }
        wanted.forEach((island, goods) -> goods.forEach(good -> {
            String key = island + "/" + good; Long last = alerts.get(key);
            if (economy.stock(island, good) < 1 && (last == null || simulated - last >= 600)) {
                alerts.put(key, simulated);
                event(Event.of(false, "event.annocraft1800.shortage", island, "#good.annocraft1800." + good));
            }
        }));
    }
    void step(double seconds, long simulated) {
        if (simulated % 60 == 0) shortages(simulated);
        maritime.step(this, seconds);
        economy.step(sites(), world.roadTiles(), world.revision(), seconds);
        diplomacy.step(this, seconds);
        campaign.step(this, this::progress);
    }
    /** Current value of a campaign objective for this company. */
    public int progress(Campaign.Objective o) {
        String t = o.target();
        return switch (o.type()) {
            case "residents" -> economy.population(t);
            case "population" -> economy.totalPopulation();
            case "buildings" -> (int) buildings().stream().filter(b -> b.definition().getPath().equals(t)).count();
            case "islands" -> ports().size();
            case "world_islands" -> (int) ports().stream().filter(i -> t.equals(world.geography().world(i))).count();
            case "ships" -> maritime.fleet().size();
            case "routes" -> maritime.routes();
            case "stock" -> (int) Math.floor(ports().stream().mapToDouble(i -> economy.stock(i, t)).sum());
            case "victories" -> diplomacy.victories();
            case "conquests" -> diplomacy.conquests();
            case "stance" -> diplomacy.faction(t) == null ? 0 : diplomacy.faction(t).stance.ordinal();
            case "eliminated" -> diplomacy.faction(t) != null && diplomacy.faction(t).eliminated ? 1 : 0;
            case "coins" -> (int) Math.min(Integer.MAX_VALUE, Math.max(0, economy.coins()));
            case "pirates" -> {
                var f = diplomacy.faction("corsairs");
                yield diplomacy.victories() > 0 || f == null || f.eliminated || f.stance != Diplomacy.Stance.WAR ? 1 : 0;
            }
            case "balance" -> (int) Math.round(economy.incomePerMinute() - economy.upkeepPerMinute());
            default -> 0;
        };
    }
    /** What this company's players see of the colony: its own economy, fleet, relations and campaign, and the others' ships. */
    public CompoundTag snapshot() {
        CompoundTag t = economy.snapshot(sites());
        CompoundTag sea = maritime.snapshot();
        ListTag ships = sea.getList("ships", Tag.TAG_COMPOUND);
        for (Maritime.Ship s : foreignShips()) {
            CompoundTag c = Maritime.save(s); c.putString("owner", world.companyOfShip(s)); c.putBoolean("company", true); ships.add(c);
        }
        t.put("maritime", sea);
        t.put("diplomacy", diplomacy.snapshot());
        t.put("campaign", campaign.snapshot(this::progress));
        ListTag p = new ListTag(); ports().forEach(i -> p.add(StringTag.valueOf(i))); t.put("ports", p);
        t.putBoolean("new_world_open", newWorldOpen());
        t.putString("company", id);
        t.putBoolean("competitive", world.competitive());
        world.rivalry(id, t);
        return t;
    }
    CompoundTag save() {
        CompoundTag t = new CompoundTag();
        t.putString("id", id); t.put("economy", economy.save()); t.put("diplomacy", diplomacy.save()); t.put("maritime", maritime.save()); t.put("campaign", campaign.save());
        return t;
    }
}
