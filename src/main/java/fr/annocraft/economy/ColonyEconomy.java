package fr.annocraft.economy;

import net.minecraft.nbt.*;
import java.util.*;

/**
 * Server-side colony economy: island stocks, road logistics, services, workforce, production, needs and finances.
 * Pure data simulation, independent of loaded chunks, so islands keep running while nobody watches them.
 */
public final class ColonyEconomy {
    public static final double START_COINS = 5000;
    public static final Map<String, Integer> START_CARGO = Map.of("timber", 30, "fish", 10);
    public static final List<String> TIERS = List.of("farmers", "workers", "artisans", "engineers", "investors", "laborers", "overseers");
    /** Residents gained or lost per second while moving toward the target population. */
    public static final double GROWTH_PER_SECOND = .2;
    public enum Status { OK, NO_ROAD, NO_WORKFORCE, NO_INPUT, STORAGE_FULL, NEEDS_UNMET, NO_FOREST }
    public record Site(UUID id, String island, int x, int z, int width, int depth, EconomyProfile profile) {
        double centerX() { return x + width / 2.0; }
        double centerZ() { return z + depth / 2.0; }
    }
    public static final class SiteState {
        public double residents, progress, productivity, supply = 1, luxury, boost;
        /** Share of the natural surroundings a producer needs (the forest of a lumberjack), 1 when complete. */
        public double nature = 1;
        public boolean connected;
        public Status status = Status.OK;
    }
    private double coins = START_COINS;
    private boolean sandbox;
    private final Set<String> cargoWorlds = new TreeSet<>();
    private String homeIsland;
    private final Map<String, Map<String, Double>> stock = new TreeMap<>();
    private final Map<UUID, SiteState> states = new HashMap<>();
    // Derived each step; never saved.
    private final Map<String, Map<String, Double>> rates = new TreeMap<>();
    private final Map<String, Map<String, int[]>> workforce = new TreeMap<>();
    private final Map<String, Integer> capacity = new TreeMap<>();
    private final Map<String, Integer> population = new TreeMap<>();
    private double incomePerMinute, upkeepPerMinute, extraUpkeepPerMinute;
    private long topology = Long.MIN_VALUE;
    private Set<UUID> connected = Set.of();
    private Map<UUID, Set<String>> coverage = Map.of();

    public double coins() { return coins; }
    public void addCoins(double amount) { coins += amount; }
    public boolean sandbox() { return sandbox; }
    public void setSandbox(boolean value) { sandbox = value; }
    public double incomePerMinute() { return incomePerMinute; }
    public double upkeepPerMinute() { return upkeepPerMinute; }
    /** Ship and other non-building upkeep, charged by {@link #step} and shown in the balance. */
    public void setExtraUpkeep(double perMinute) { extraUpkeepPerMinute = perMinute; }
    public String homeIsland() { return homeIsland; }
    public double stock(String island, String good) { return stock.getOrDefault(island, Map.of()).getOrDefault(good, 0.0); }
    public Map<String, Double> stocks(String island) { return Collections.unmodifiableMap(stock.getOrDefault(island, Map.of())); }
    public Set<String> stockedIslands() { return Collections.unmodifiableSet(stock.keySet()); }
    public int capacity(String island) { return capacity.getOrDefault(island, 0); }
    public SiteState state(UUID id) { return states.get(id); }
    public void setNature(UUID id, double share) { SiteState s = states.get(id); if (s != null) s.nature = Math.max(0, Math.min(1, share)); }
    public int[] workforce(String island, String tier) { return workforce.getOrDefault(island, Map.of()).getOrDefault(tier, new int[2]); }
    public double rate(String island, String good) { return rates.getOrDefault(island, Map.of()).getOrDefault(good, 0.0); }
    public int population(String tier) { return population.getOrDefault(tier, 0); }
    public int totalPopulation() { return population.values().stream().mapToInt(Integer::intValue).sum(); }
    public void addStock(String island, String good, double amount) {
        stock.computeIfAbsent(island, k -> new TreeMap<>()).merge(good, amount, Double::sum);
    }
    /** Adds goods without exceeding the island's storage; returns the amount actually stored. */
    public double store(String island, String good, double amount) {
        double room = Math.max(0, capacity(island) - stock(island, good)), stored = Math.min(room, amount);
        if (stored > 0) addStock(island, good, stored);
        return stored;
    }
    public static String worldOf(String island) { return island != null && island.startsWith("nw_") ? "new" : "old"; }

    /** @return null when affordable, otherwise the message suffix explaining why not. */
    public String checkCost(String island, Map<String, Integer> cost) {
        if (sandbox) return null;
        if (coins < cost.getOrDefault(EconomyProfile.COINS, 0)) return "no_coins";
        for (var e : cost.entrySet())
            if (!e.getKey().equals(EconomyProfile.COINS) && stock(island, e.getKey()) + 1e-9 < e.getValue()) return "no_goods";
        return null;
    }
    public String checkCost(String island, EconomyProfile profile) { return checkCost(island, profile.cost()); }
    public void pay(String island, Map<String, Integer> cost) {
        if (sandbox) return;
        coins -= cost.getOrDefault(EconomyProfile.COINS, 0);
        for (var e : cost.entrySet()) if (!e.getKey().equals(EconomyProfile.COINS)) addStock(island, e.getKey(), -e.getValue());
    }
    public void pay(String island, EconomyProfile profile) { pay(island, profile.cost()); }
    public boolean unlocked(EconomyProfile p) {
        return sandbox || p.unlockTier() == null || population(p.unlockTier()) >= p.unlockResidents();
    }
    public void placed(Site site) {
        SiteState s = states.computeIfAbsent(site.id(), id -> new SiteState());
        if (site.profile().housing() && s.residents < 1) s.residents = 1;
        // The first storage building of each world receives a founding ship's cargo, so the island can start building.
        if (site.profile().storageNode() && cargoWorlds.add(worldOf(site.island()))) {
            START_CARGO.forEach((good, amount) -> addStock(site.island(), good, amount));
            if (homeIsland == null) homeIsland = site.island();
        }
        topology = Long.MIN_VALUE;
    }
    public void removed(UUID id) { states.remove(id); topology = Long.MIN_VALUE; }
    public boolean upgradeReady(UUID id, EconomyProfile current) {
        if (sandbox || !current.housing()) return true;
        SiteState s = states.get(id);
        return s != null && s.connected && s.residents >= current.capacity() - 1e-6 && s.supply >= .9;
    }

    /** Advances the colony by {@code dt} seconds. {@code topologyRevision} changes whenever buildings or roads change. */
    public void step(Collection<Site> sites, Set<Long> roads, long topologyRevision, double dt) {
        if (topologyRevision != topology) {
            connected = connectivity(sites, roads);
            coverage = coverage(sites, connected);
            topology = topologyRevision;
        }
        capacity.clear(); workforce.clear(); population.clear();
        Map<String, Map<String, Double>> flow = new TreeMap<>();
        for (Site site : sites) {
            states.computeIfAbsent(site.id(), id -> new SiteState()).connected = connected.contains(site.id());
            if (site.profile().storageNode()) capacity.merge(site.island(), site.profile().storage(), Integer::sum);
        }
        for (Site site : sites) {
            SiteState s = states.get(site.id()); EconomyProfile p = site.profile();
            if (p.housing()) population.merge(p.houseTier(), (int) Math.floor(s.residents + 1e-9), Integer::sum);
            if (p.housing() && s.connected) tier(site.island(), p.houseTier())[0] += (int) Math.floor(s.residents + 1e-9);
            if (p.producer() && p.workforce() > 0 && s.connected) tier(site.island(), p.workTier())[1] += p.workforce();
        }
        boosts(sites, dt, flow);
        double income = 0, upkeep = extraUpkeepPerMinute / 60 * dt;
        for (Site site : sites) {
            SiteState s = states.get(site.id()); EconomyProfile p = site.profile();
            upkeep += p.upkeep() / 60.0 * dt;
            if (p.producer()) produce(site, s, dt, flow);
            if (p.housing()) income += house(site, s, dt, flow);
        }
        coins += income - upkeep;
        incomePerMinute = income * 60 / dt; upkeepPerMinute = upkeep * 60 / dt;
        double smoothing = Math.min(1, dt / 30);
        Set<String> islands = new TreeSet<>(rates.keySet()); islands.addAll(flow.keySet());
        for (String island : islands) {
            Map<String, Double> r = rates.computeIfAbsent(island, k -> new TreeMap<>()), f = flow.getOrDefault(island, Map.of());
            Set<String> goods = new TreeSet<>(r.keySet()); goods.addAll(f.keySet());
            for (String good : goods) r.merge(good, (f.getOrDefault(good, 0.0) * 60 / dt - r.getOrDefault(good, 0.0)) * smoothing, Double::sum);
        }
    }
    /** Records goods moved by ships or trade in the island's flow statistics. */
    public void recordFlow(String island, String good, double amountPerMinute) {
        rates.computeIfAbsent(island, k -> new TreeMap<>()).merge(good, amountPerMinute / 30, Double::sum);
    }
    private int[] tier(String island, String tier) { return workforce.computeIfAbsent(island, k -> new TreeMap<>()).computeIfAbsent(tier, k -> new int[2]); }
    private void produce(Site site, SiteState s, double dt, Map<String, Map<String, Double>> flow) {
        EconomyProfile p = site.profile();
        if (!s.connected) { s.productivity = 0; s.status = Status.NO_ROAD; return; }
        int[] w = p.workforce() > 0 ? workforce(site.island(), p.workTier()) : null;
        double ratio = w == null ? 1 : w[1] == 0 ? 0 : Math.min(1, (double) w[0] / w[1]);
        if (ratio <= 0) { s.productivity = 0; s.status = Status.NO_WORKFORCE; return; }
        s.productivity = ratio * (1 + s.boost) * s.nature;
        if (s.productivity <= 0) { s.status = Status.NO_FOREST; return; }
        s.progress = Math.min(1, s.progress + dt * s.productivity / p.cycle());
        s.status = ratio < 1 ? Status.NO_WORKFORCE : s.nature < .5 ? Status.NO_FOREST : Status.OK;
        if (s.progress < 1) return;
        for (var in : p.inputs().entrySet()) if (stock(site.island(), in.getKey()) + 1e-9 < in.getValue()) { s.status = Status.NO_INPUT; return; }
        int cap = capacity(site.island());
        for (var out : p.outputs().entrySet()) if (stock(site.island(), out.getKey()) + out.getValue() > cap + 1e-9) { s.status = Status.STORAGE_FULL; return; }
        Map<String, Double> f = flow.computeIfAbsent(site.island(), k -> new TreeMap<>());
        p.inputs().forEach((good, amount) -> { addStock(site.island(), good, -amount); f.merge(good, (double) -amount, Double::sum); });
        p.outputs().forEach((good, amount) -> { addStock(site.island(), good, amount); f.merge(good, (double) amount, Double::sum); });
        s.progress = 0;
    }
    /** Active boosters (fed with their inputs) raise producers of the same island within their radius. */
    private void boosts(Collection<Site> sites, double dt, Map<String, Map<String, Double>> flow) {
        for (Site site : sites) states.get(site.id()).boost = 0;
        for (Site b : sites) {
            EconomyProfile p = b.profile(); SiteState bs = states.get(b.id());
            if (!p.booster() || !bs.connected) continue;
            boolean fed = p.boostInputs().entrySet().stream().allMatch(e -> stock(b.island(), e.getKey()) + 1e-9 >= e.getValue() / 60 * dt);
            bs.status = fed ? Status.OK : Status.NO_INPUT; bs.productivity = fed ? 1 : 0;
            if (!fed) continue;
            Map<String, Double> f = flow.computeIfAbsent(b.island(), k -> new TreeMap<>());
            p.boostInputs().forEach((good, rate) -> { addStock(b.island(), good, -rate / 60 * dt); f.merge(good, -rate / 60 * dt, Double::sum); });
            for (Site s : sites)
                if (s.profile().producer() && s.island().equals(b.island()) && Math.hypot(s.centerX() - b.centerX(), s.centerZ() - b.centerZ()) <= p.boostRadius())
                    states.get(s.id()).boost += p.boost();
        }
    }
    private double consume(Site site, SiteState s, Map<String, Double> needs, double dt, Map<String, Double> flow, double[] fulfilled) {
        for (var need : needs.entrySet()) {
            double demand = s.residents * need.getValue() / 60 * dt, take = Math.max(0, Math.min(stock(site.island(), need.getKey()), demand));
            addStock(site.island(), need.getKey(), -take); flow.merge(need.getKey(), -take, Double::sum);
            fulfilled[0] += demand <= 0 ? 1 : take / demand; fulfilled[1]++;
        }
        return fulfilled[0];
    }
    private double house(Site site, SiteState s, double dt, Map<String, Map<String, Double>> flow) {
        EconomyProfile p = site.profile();
        double target;
        if (!s.connected) { s.supply = 0; s.luxury = 0; s.status = Status.NO_ROAD; target = Math.min(1, p.capacity()); }
        else {
            Map<String, Double> f = flow.computeIfAbsent(site.island(), k -> new TreeMap<>());
            Set<String> covered = coverage.getOrDefault(site.id(), Set.of());
            double[] basic = new double[2], lux = new double[2];
            consume(site, s, p.needs(), dt, f, basic);
            for (String service : p.services()) { basic[0] += covered.contains(service) ? 1 : 0; basic[1]++; }
            consume(site, s, p.luxury(), dt, f, lux);
            for (String service : p.luxuryServices()) { lux[0] += covered.contains(service) ? 1 : 0; lux[1]++; }
            double fulfilled = basic[1] == 0 ? 1 : basic[0] / basic[1], luxury = lux[1] == 0 ? 0 : lux[0] / lux[1];
            // Smoothed so a single empty tick does not empty a house.
            s.supply += (fulfilled - s.supply) * Math.min(1, dt / 20);
            s.luxury += (luxury - s.luxury) * Math.min(1, dt / 20);
            target = p.capacity() * Math.min(1, .5 + .5 * s.supply / .95);
            s.status = s.supply < .9 ? Status.NEEDS_UNMET : Status.OK;
        }
        double step = GROWTH_PER_SECOND * dt;
        s.residents = s.residents < target ? Math.min(target, s.residents + step) : Math.max(target, s.residents - step);
        // Basic needs keep residents paying; luxury goods and services raise taxes by up to 50 %.
        return s.connected ? s.residents * p.tax() / 60 * dt * (.5 + .5 * s.supply) * (1 + .5 * s.luxury) : 0;
    }

    /** Buildings touching a road network that also touches a storage building of the same island. */
    public static Set<UUID> connectivity(Collection<Site> sites, Set<Long> roads) {
        Map<Long, Integer> component = new HashMap<>(); int next = 0;
        for (long start : roads) {
            if (component.containsKey(start)) continue;
            ArrayDeque<Long> queue = new ArrayDeque<>(List.of(start)); component.put(start, next);
            while (!queue.isEmpty()) {
                long tile = queue.poll(); int x = x(tile), z = z(tile);
                for (long n : new long[]{pack(x + 1, z), pack(x - 1, z), pack(x, z + 1), pack(x, z - 1)})
                    if (roads.contains(n) && component.putIfAbsent(n, next) == null) queue.add(n);
            }
            next++;
        }
        Map<UUID, Set<Integer>> touching = new HashMap<>();
        Set<String> served = new HashSet<>();
        Set<UUID> result = new HashSet<>();
        for (Site s : sites) {
            Set<Integer> comps = new HashSet<>();
            for (int i = 0; i < s.width(); i++) {
                add(comps, component, s.x() + i, s.z() - 1); add(comps, component, s.x() + i, s.z() + s.depth());
            }
            for (int i = 0; i < s.depth(); i++) {
                add(comps, component, s.x() - 1, s.z() + i); add(comps, component, s.x() + s.width(), s.z() + i);
            }
            touching.put(s.id(), comps);
            if (s.profile().storageNode()) { result.add(s.id()); for (int c : comps) served.add(s.island() + "#" + c); }
        }
        for (Site s : sites) for (int c : touching.get(s.id())) if (served.contains(s.island() + "#" + c)) { result.add(s.id()); break; }
        return result;
    }
    /** Services reaching each residence: connected service buildings of the same island within their radius. */
    public static Map<UUID, Set<String>> coverage(Collection<Site> sites, Set<UUID> connected) {
        List<Site> providers = sites.stream().filter(s -> s.profile().serviceProvider() && connected.contains(s.id())).toList();
        Map<UUID, Set<String>> result = new HashMap<>();
        for (Site house : sites) {
            if (!house.profile().housing()) continue;
            Set<String> covered = new HashSet<>();
            for (Site p : providers)
                if (p.island().equals(house.island()) && Math.hypot(p.centerX() - house.centerX(), p.centerZ() - house.centerZ()) <= p.profile().radius())
                    covered.add(p.profile().service());
            result.put(house.id(), covered);
        }
        return result;
    }
    private static void add(Set<Integer> comps, Map<Long, Integer> component, int x, int z) {
        Integer c = component.get(pack(x, z)); if (c != null) comps.add(c);
    }
    public static long pack(int x, int z) { return ((long) x << 32) | (z & 0xffffffffL); }
    public static int x(long tile) { return (int) (tile >> 32); }
    public static int z(long tile) { return (int) tile; }

    public CompoundTag save() {
        CompoundTag t = new CompoundTag();
        t.putDouble("coins", coins); t.putBoolean("sandbox", sandbox);
        ListTag cargo = new ListTag(); cargoWorlds.forEach(w -> cargo.add(StringTag.valueOf(w))); t.put("cargo_worlds", cargo);
        // Saves without a home island get the same deterministic one the loader would infer.
        if (homeIsland == null && !stock.isEmpty()) homeIsland = new TreeSet<>(stock.keySet()).first();
        if (homeIsland != null) t.putString("home", homeIsland);
        CompoundTag islands = new CompoundTag();
        stock.forEach((island, goods) -> { CompoundTag g = new CompoundTag(); goods.forEach(g::putDouble); islands.put(island, g); });
        t.put("stock", islands);
        ListTag sites = new ListTag();
        states.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e -> {
            CompoundTag s = new CompoundTag(); s.putUUID("id", e.getKey());
            s.putDouble("residents", e.getValue().residents); s.putDouble("progress", e.getValue().progress);
            s.putDouble("supply", e.getValue().supply); s.putDouble("luxury", e.getValue().luxury);
            sites.add(s);
        });
        t.put("sites", sites);
        return t;
    }
    public static ColonyEconomy load(CompoundTag t) {
        ColonyEconomy e = new ColonyEconomy();
        if (t.contains("coins")) e.coins = t.getDouble("coins");
        e.sandbox = t.getBoolean("sandbox");
        if (t.getBoolean("cargo")) e.cargoWorlds.add("old");
        for (Tag w : t.getList("cargo_worlds", Tag.TAG_STRING)) e.cargoWorlds.add(w.getAsString());
        if (t.contains("home")) e.homeIsland = t.getString("home");
        CompoundTag islands = t.getCompound("stock");
        for (String island : islands.getAllKeys()) {
            CompoundTag g = islands.getCompound(island);
            for (String good : g.getAllKeys()) e.addStock(island, good, g.getDouble(good));
        }
        if (e.homeIsland == null && !e.stock.isEmpty()) e.homeIsland = new TreeSet<>(e.stock.keySet()).first();
        for (Tag entry : t.getList("sites", Tag.TAG_COMPOUND)) {
            CompoundTag s = (CompoundTag) entry; SiteState state = new SiteState();
            state.residents = s.getDouble("residents"); state.progress = s.getDouble("progress");
            state.supply = s.getDouble("supply"); state.luxury = s.getDouble("luxury");
            e.states.put(s.getUUID("id"), state);
        }
        return e;
    }
    /** Compact state sent to clients every couple of seconds. */
    public CompoundTag snapshot(Collection<Site> sites) {
        CompoundTag t = new CompoundTag();
        t.putDouble("coins", coins); t.putBoolean("sandbox", sandbox);
        t.putDouble("income", incomePerMinute); t.putDouble("upkeep", upkeepPerMinute);
        CompoundTag pop = new CompoundTag(); population.forEach(pop::putInt); t.put("population", pop);
        CompoundTag islands = new CompoundTag();
        Set<String> ids = new TreeSet<>(stock.keySet()); ids.addAll(capacity.keySet()); ids.addAll(workforce.keySet());
        for (String island : ids) {
            CompoundTag i = new CompoundTag(); i.putInt("capacity", capacity(island));
            CompoundTag goods = new CompoundTag(); stock.getOrDefault(island, Map.of()).forEach((g, v) -> { if (Math.abs(v) > 1e-6) goods.putDouble(g, v); }); i.put("stock", goods);
            CompoundTag r = new CompoundTag(); rates.getOrDefault(island, Map.of()).forEach((g, v) -> { if (Math.abs(v) > .01) r.putDouble(g, v); }); i.put("rates", r);
            CompoundTag w = new CompoundTag(); workforce.getOrDefault(island, Map.of()).forEach((tier, v) -> w.putIntArray(tier, v.clone())); i.put("workforce", w);
            islands.put(island, i);
        }
        t.put("islands", islands);
        ListTag list = new ListTag();
        for (Site site : sites) {
            SiteState s = states.get(site.id()); if (s == null) continue;
            CompoundTag b = new CompoundTag(); b.putUUID("id", site.id()); b.putBoolean("connected", s.connected);
            b.putByte("status", (byte) s.status.ordinal()); b.putDouble("residents", s.residents);
            b.putDouble("productivity", s.productivity); b.putDouble("supply", s.supply); b.putDouble("progress", s.progress);
            b.putDouble("luxury", s.luxury); b.putDouble("boost", s.boost);
            ListTag covered = new ListTag(); coverage.getOrDefault(site.id(), Set.of()).stream().sorted().forEach(c -> covered.add(StringTag.valueOf(c)));
            b.put("services", covered);
            list.add(b);
        }
        t.put("sites", list);
        return t;
    }
}
