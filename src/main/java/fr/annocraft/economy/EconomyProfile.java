package fr.annocraft.economy;

import com.google.gson.*;
import java.util.*;

/**
 * Economic role of a building definition, loaded from the optional "economy" JSON object.
 * Rates are expressed per minute of server time; production cycles in seconds.
 */
public record EconomyProfile(Map<String, Integer> cost, int upkeep, int storage,
                             String workTier, int workforce, int cycle,
                             Map<String, Integer> inputs, Map<String, Integer> outputs,
                             String houseTier, int capacity, double tax, Map<String, Double> needs,
                             Map<String, Double> luxury, List<String> services, List<String> luxuryServices,
                             String service, int radius, String fertility, String deposit, String world,
                             String unlockTier, int unlockResidents, boolean shipyard, int defense,
                             int boostRadius, double boost, Map<String, Double> boostInputs) {
    public static final String COINS = "coins", ANY_WORLD = "any";
    public static final EconomyProfile NONE = new EconomyProfile(Map.of(), 0, 0, "farmers", 0, 0, Map.of(), Map.of(), null, 0, 0, Map.of(),
            Map.of(), List.of(), List.of(), null, 0, null, null, ANY_WORLD, null, 0, false, 0, 0, 0, Map.of());
    public boolean producer() { return cycle > 0 && !outputs.isEmpty(); }
    public boolean housing() { return capacity > 0 && houseTier != null; }
    public boolean storageNode() { return storage > 0; }
    public boolean serviceProvider() { return service != null && radius > 0; }
    /** Raises the productivity of producers of its island within its radius (electricity, trade unions). */
    public boolean booster() { return boostRadius > 0 && boost > 0; }
    public int coinCost() { return cost.getOrDefault(COINS, 0); }
    public boolean buildableIn(String worldName) { return ANY_WORLD.equals(world) || world.equals(worldName); }

    public static EconomyProfile parse(JsonObject json) {
        if (json == null) return NONE;
        Map<String, Integer> cost = ints(json, "cost");
        int upkeep = json.has("upkeep") ? json.get("upkeep").getAsInt() : 0;
        int storage = json.has("storage") ? json.get("storage").getAsInt() : 0;
        String workTier = "farmers"; int workforce = 0;
        if (json.has("workforce")) {
            JsonObject w = json.getAsJsonObject("workforce");
            workTier = w.get("tier").getAsString(); workforce = w.get("amount").getAsInt();
        }
        int cycle = 0; Map<String, Integer> inputs = Map.of(), outputs = Map.of();
        if (json.has("production")) {
            JsonObject p = json.getAsJsonObject("production");
            cycle = p.get("cycle").getAsInt(); inputs = ints(p, "inputs"); outputs = ints(p, "outputs");
            if (cycle < 1 || outputs.isEmpty()) throw new IllegalArgumentException("Production needs a positive cycle and outputs");
        }
        String houseTier = null; int capacity = 0; double tax = 0;
        Map<String, Double> needs = Map.of(), luxury = Map.of(); List<String> services = List.of(), luxuryServices = List.of();
        if (json.has("housing")) {
            JsonObject h = json.getAsJsonObject("housing");
            houseTier = h.get("tier").getAsString(); capacity = h.get("capacity").getAsInt();
            tax = h.has("tax") ? h.get("tax").getAsDouble() : 0;
            needs = doubles(h, "needs"); luxury = doubles(h, "luxury");
            services = strings(h, "services"); luxuryServices = strings(h, "luxury_services");
            if (capacity < 1) throw new IllegalArgumentException("Housing needs a positive capacity");
        }
        String service = null; int radius = 0;
        if (json.has("service")) {
            JsonObject s = json.getAsJsonObject("service");
            service = s.get("id").getAsString(); radius = s.get("radius").getAsInt();
            if (radius < 1) throw new IllegalArgumentException("Service needs a positive radius");
        }
        String fertility = null, deposit = null;
        if (json.has("requires")) {
            JsonObject r = json.getAsJsonObject("requires");
            if (r.has("fertility")) fertility = r.get("fertility").getAsString();
            if (r.has("deposit")) deposit = r.get("deposit").getAsString();
        }
        String world = json.has("world") ? json.get("world").getAsString() : ANY_WORLD;
        String unlockTier = null; int unlockResidents = 0;
        if (json.has("unlock")) {
            JsonObject u = json.getAsJsonObject("unlock");
            unlockTier = u.get("tier").getAsString(); unlockResidents = u.get("residents").getAsInt();
        }
        boolean shipyard = json.has("shipyard") && json.get("shipyard").getAsBoolean();
        int defense = json.has("defense") ? json.get("defense").getAsInt() : 0;
        int boostRadius = 0; double boost = 0; Map<String, Double> boostInputs = Map.of();
        if (json.has("boost")) {
            JsonObject b = json.getAsJsonObject("boost");
            boostRadius = b.get("radius").getAsInt(); boost = b.get("productivity").getAsDouble(); boostInputs = doubles(b, "inputs");
        }
        if (upkeep < 0 || storage < 0 || workforce < 0 || tax < 0 || defense < 0 || unlockResidents < 0) throw new IllegalArgumentException("Negative economy value");
        return new EconomyProfile(cost, upkeep, storage, workTier, workforce, cycle, inputs, outputs, houseTier, capacity, tax, needs,
                luxury, services, luxuryServices, service, radius, fertility, deposit, world, unlockTier, unlockResidents, shipyard, defense,
                boostRadius, boost, boostInputs);
    }
    private static Map<String, Integer> ints(JsonObject json, String key) {
        if (!json.has(key)) return Map.of();
        Map<String, Integer> result = new TreeMap<>();
        for (Map.Entry<String, JsonElement> e : json.getAsJsonObject(key).entrySet()) {
            int v = e.getValue().getAsInt();
            if (v < 0) throw new IllegalArgumentException("Negative amount for " + e.getKey());
            result.put(e.getKey(), v);
        }
        return Collections.unmodifiableMap(result);
    }
    private static Map<String, Double> doubles(JsonObject json, String key) {
        if (!json.has(key)) return Map.of();
        Map<String, Double> result = new TreeMap<>();
        json.getAsJsonObject(key).entrySet().forEach(e -> result.put(e.getKey(), e.getValue().getAsDouble()));
        return Collections.unmodifiableMap(result);
    }
    private static List<String> strings(JsonObject json, String key) {
        if (!json.has(key)) return List.of();
        List<String> result = new ArrayList<>();
        json.getAsJsonArray(key).forEach(e -> result.add(e.getAsString()));
        return List.copyOf(result);
    }
    public JsonObject toJson() {
        JsonObject j = new JsonObject();
        JsonObject c = new JsonObject(); cost.forEach(c::addProperty); j.add("cost", c);
        j.addProperty("upkeep", upkeep); j.addProperty("storage", storage);
        if (workforce > 0) { JsonObject w = new JsonObject(); w.addProperty("tier", workTier); w.addProperty("amount", workforce); j.add("workforce", w); }
        if (producer()) {
            JsonObject p = new JsonObject(); p.addProperty("cycle", cycle);
            JsonObject in = new JsonObject(); inputs.forEach(in::addProperty); p.add("inputs", in);
            JsonObject out = new JsonObject(); outputs.forEach(out::addProperty); p.add("outputs", out);
            j.add("production", p);
        }
        if (housing()) {
            JsonObject h = new JsonObject(); h.addProperty("tier", houseTier); h.addProperty("capacity", capacity); h.addProperty("tax", tax);
            JsonObject n = new JsonObject(); needs.forEach(n::addProperty); h.add("needs", n);
            JsonObject l = new JsonObject(); luxury.forEach(l::addProperty); h.add("luxury", l);
            JsonArray s = new JsonArray(); services.forEach(s::add); h.add("services", s);
            JsonArray ls = new JsonArray(); luxuryServices.forEach(ls::add); h.add("luxury_services", ls);
            j.add("housing", h);
        }
        if (serviceProvider()) { JsonObject s = new JsonObject(); s.addProperty("id", service); s.addProperty("radius", radius); j.add("service", s); }
        if (fertility != null || deposit != null) {
            JsonObject r = new JsonObject();
            if (fertility != null) r.addProperty("fertility", fertility);
            if (deposit != null) r.addProperty("deposit", deposit);
            j.add("requires", r);
        }
        j.addProperty("world", world);
        if (unlockTier != null) { JsonObject u = new JsonObject(); u.addProperty("tier", unlockTier); u.addProperty("residents", unlockResidents); j.add("unlock", u); }
        if (shipyard) j.addProperty("shipyard", true);
        if (defense > 0) j.addProperty("defense", defense);
        if (booster()) {
            JsonObject b = new JsonObject(); b.addProperty("radius", boostRadius); b.addProperty("productivity", boost);
            JsonObject in = new JsonObject(); boostInputs.forEach(in::addProperty); b.add("inputs", in); j.add("boost", b);
        }
        return j;
    }
}
