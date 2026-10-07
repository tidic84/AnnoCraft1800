package fr.annocraft.economy;

import net.minecraft.nbt.*;
import java.util.*;

/**
 * Server-side colony economy: island stocks, road logistics, workforce, production, needs and finances.
 * Pure data simulation, independent of loaded chunks, so islands keep running while nobody watches them.
 */
public final class ColonyEconomy {
    public static final double START_COINS = 5000;
    public static final Map<String, Integer> START_CARGO = Map.of("timber", 30, "fish", 10);
    /** Seconds to fill one resident slot when needs are met. */
    public static final double GROWTH_PER_SECOND = .2;
    public enum Status { OK, NO_ROAD, NO_WORKFORCE, NO_INPUT, STORAGE_FULL, NEEDS_UNMET }
    public record Site(UUID id, String island, int x, int z, int width, int depth, EconomyProfile profile) { }
    public static final class SiteState {
        public double residents, progress, productivity, supply = 1;
        public boolean connected;
        public Status status = Status.OK;
    }
    private double coins = START_COINS;
    private boolean sandbox, cargoDelivered;
    private final Map<String, Map<String, Double>> stock = new TreeMap<>();
    private final Map<UUID, SiteState> states = new HashMap<>();
    // Derived each step; never saved.
    private final Map<String, Map<String, Double>> rates = new TreeMap<>();
    private final Map<String, Map<String, int[]>> workforce = new TreeMap<>();
    private final Map<String, Integer> capacity = new TreeMap<>();
    private double incomePerMinute, upkeepPerMinute;
    private long topology = Long.MIN_VALUE;
    private Set<UUID> connected = Set.of();

    public double coins() { return coins; }
    public boolean sandbox() { return sandbox; }
    public void setSandbox(boolean value) { sandbox = value; }
    public double incomePerMinute() { return incomePerMinute; }
    public double upkeepPerMinute() { return upkeepPerMinute; }
    public double stock(String island, String good) { return stock.getOrDefault(island, Map.of()).getOrDefault(good, 0.0); }
    public int capacity(String island) { return capacity.getOrDefault(island, 0); }
    public SiteState state(UUID id) { return states.get(id); }
    public int[] workforce(String island, String tier) { return workforce.getOrDefault(island, Map.of()).getOrDefault(tier, new int[2]); }
    public double rate(String island, String good) { return rates.getOrDefault(island, Map.of()).getOrDefault(good, 0.0); }
    public void addStock(String island, String good, double amount) {
        stock.computeIfAbsent(island, k -> new TreeMap<>()).merge(good, amount, Double::sum);
    }

    /** @return null when affordable, otherwise the message suffix explaining why not. */
    public String checkCost(String island, EconomyProfile profile) {
        if (sandbox) return null;
        if (coins < profile.coinCost()) return "no_coins";
        for (var e : profile.cost().entrySet())
            if (!e.getKey().equals(EconomyProfile.COINS) && stock(island, e.getKey()) + 1e-9 < e.getValue()) return "no_goods";
        return null;
    }
    public void pay(String island, EconomyProfile profile) {
        if (sandbox) return;
        coins -= profile.coinCost();
        for (var e : profile.cost().entrySet()) if (!e.getKey().equals(EconomyProfile.COINS)) addStock(island, e.getKey(), -e.getValue());
    }
    public void placed(Site site) {
        SiteState s = states.computeIfAbsent(site.id(), id -> new SiteState());
        if (site.profile().housing() && s.residents < 1) s.residents = 1;
        // The first storage building receives the founding ship's cargo, so the first island can start building.
        if (site.profile().storageNode() && !cargoDelivered) {
            START_CARGO.forEach((good, amount) -> addStock(site.island(), good, amount));
            cargoDelivered = true;
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
        if (topologyRevision != topology) { connected = connectivity(sites, roads); topology = topologyRevision; }
        capacity.clear(); workforce.clear();
        Map<String, Map<String, Double>> flow = new TreeMap<>();
        for (Site site : sites) {
            states.computeIfAbsent(site.id(), id -> new SiteState()).connected = connected.contains(site.id());
            if (site.profile().storageNode()) capacity.merge(site.island(), site.profile().storage(), Integer::sum);
        }
        for (Site site : sites) {
            SiteState s = states.get(site.id()); EconomyProfile p = site.profile();
            if (p.housing() && s.connected) tier(site.island(), p.houseTier())[0] += (int) Math.floor(s.residents + 1e-9);
            if (p.producer() && p.workforce() > 0 && s.connected) tier(site.island(), p.workTier())[1] += p.workforce();
        }
        double income = 0, upkeep = 0;
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
    private int[] tier(String island, String tier) { return workforce.computeIfAbsent(island, k -> new TreeMap<>()).computeIfAbsent(tier, k -> new int[2]); }
    private void produce(Site site, SiteState s, double dt, Map<String, Map<String, Double>> flow) {
        EconomyProfile p = site.profile();
        if (!s.connected) { s.productivity = 0; s.status = Status.NO_ROAD; return; }
        int[] w = p.workforce() > 0 ? workforce(site.island(), p.workTier()) : null;
        double ratio = w == null ? 1 : w[1] == 0 ? 0 : Math.min(1, (double) w[0] / w[1]);
        s.productivity = ratio;
        if (ratio <= 0) { s.status = Status.NO_WORKFORCE; return; }
        s.progress = Math.min(1, s.progress + dt * ratio / p.cycle());
        s.status = ratio < 1 ? Status.NO_WORKFORCE : Status.OK;
        if (s.progress < 1) return;
        for (var in : p.inputs().entrySet()) if (stock(site.island(), in.getKey()) + 1e-9 < in.getValue()) { s.status = Status.NO_INPUT; return; }
        int cap = capacity(site.island());
        for (var out : p.outputs().entrySet()) if (stock(site.island(), out.getKey()) + out.getValue() > cap + 1e-9) { s.status = Status.STORAGE_FULL; return; }
        Map<String, Double> f = flow.computeIfAbsent(site.island(), k -> new TreeMap<>());
        p.inputs().forEach((good, amount) -> { addStock(site.island(), good, -amount); f.merge(good, (double) -amount, Double::sum); });
        p.outputs().forEach((good, amount) -> { addStock(site.island(), good, amount); f.merge(good, (double) amount, Double::sum); });
        s.progress = 0;
    }
    private double house(Site site, SiteState s, double dt, Map<String, Map<String, Double>> flow) {
        EconomyProfile p = site.profile();
        double target;
        if (!s.connected) { s.supply = 0; s.status = Status.NO_ROAD; target = Math.min(1, p.capacity()); }
        else {
            double fulfilled = 0;
            Map<String, Double> f = flow.computeIfAbsent(site.island(), k -> new TreeMap<>());
            for (var need : p.needs().entrySet()) {
                double demand = s.residents * need.getValue() / 60 * dt, take = Math.max(0, Math.min(stock(site.island(), need.getKey()), demand));
                addStock(site.island(), need.getKey(), -take); f.merge(need.getKey(), -take, Double::sum);
                fulfilled += demand <= 0 ? 1 : take / demand;
            }
            fulfilled = p.needs().isEmpty() ? 1 : fulfilled / p.needs().size();
            // Smoothed so a single empty tick does not empty a house.
            s.supply += (fulfilled - s.supply) * Math.min(1, dt / 20);
            target = p.capacity() * Math.min(1, .5 + .5 * s.supply / .95);
            s.status = s.supply < .9 ? Status.NEEDS_UNMET : Status.OK;
        }
        double step = GROWTH_PER_SECOND * dt;
        s.residents = s.residents < target ? Math.min(target, s.residents + step) : Math.max(target, s.residents - step);
        return s.connected ? s.residents * p.tax() / 60 * dt * (.5 + .5 * s.supply) : 0;
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
    private static void add(Set<Integer> comps, Map<Long, Integer> component, int x, int z) {
        Integer c = component.get(pack(x, z)); if (c != null) comps.add(c);
    }
    public static long pack(int x, int z) { return ((long) x << 32) | (z & 0xffffffffL); }
    public static int x(long tile) { return (int) (tile >> 32); }
    public static int z(long tile) { return (int) tile; }

    public CompoundTag save() {
        CompoundTag t = new CompoundTag();
        t.putDouble("coins", coins); t.putBoolean("sandbox", sandbox); t.putBoolean("cargo", cargoDelivered);
        CompoundTag islands = new CompoundTag();
        stock.forEach((island, goods) -> { CompoundTag g = new CompoundTag(); goods.forEach(g::putDouble); islands.put(island, g); });
        t.put("stock", islands);
        ListTag sites = new ListTag();
        states.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e -> {
            CompoundTag s = new CompoundTag(); s.putUUID("id", e.getKey());
            s.putDouble("residents", e.getValue().residents); s.putDouble("progress", e.getValue().progress); s.putDouble("supply", e.getValue().supply);
            sites.add(s);
        });
        t.put("sites", sites);
        return t;
    }
    public static ColonyEconomy load(CompoundTag t) {
        ColonyEconomy e = new ColonyEconomy();
        if (t.contains("coins")) e.coins = t.getDouble("coins");
        e.sandbox = t.getBoolean("sandbox"); e.cargoDelivered = t.getBoolean("cargo");
        CompoundTag islands = t.getCompound("stock");
        for (String island : islands.getAllKeys()) {
            CompoundTag g = islands.getCompound(island);
            for (String good : g.getAllKeys()) e.addStock(island, good, g.getDouble(good));
        }
        for (Tag entry : t.getList("sites", Tag.TAG_COMPOUND)) {
            CompoundTag s = (CompoundTag) entry; SiteState state = new SiteState();
            state.residents = s.getDouble("residents"); state.progress = s.getDouble("progress"); state.supply = s.getDouble("supply");
            e.states.put(s.getUUID("id"), state);
        }
        return e;
    }
    /** Compact state sent to clients every couple of seconds. */
    public CompoundTag snapshot(Collection<Site> sites) {
        CompoundTag t = new CompoundTag();
        t.putDouble("coins", coins); t.putBoolean("sandbox", sandbox);
        t.putDouble("income", incomePerMinute); t.putDouble("upkeep", upkeepPerMinute);
        CompoundTag islands = new CompoundTag();
        Set<String> ids = new TreeSet<>(stock.keySet()); ids.addAll(capacity.keySet()); ids.addAll(workforce.keySet());
        for (String island : ids) {
            CompoundTag i = new CompoundTag(); i.putInt("capacity", capacity(island));
            CompoundTag goods = new CompoundTag(); stock.getOrDefault(island, Map.of()).forEach(goods::putDouble); i.put("stock", goods);
            CompoundTag r = new CompoundTag(); rates.getOrDefault(island, Map.of()).forEach(r::putDouble); i.put("rates", r);
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
            list.add(b);
        }
        t.put("sites", list);
        return t;
    }
}
