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
                             String houseTier, int capacity, double tax, Map<String, Double> needs) {
    public static final String COINS = "coins";
    public static final EconomyProfile NONE = new EconomyProfile(Map.of(), 0, 0, "farmers", 0, 0, Map.of(), Map.of(), null, 0, 0, Map.of());
    public boolean producer() { return cycle > 0 && !outputs.isEmpty(); }
    public boolean housing() { return capacity > 0 && houseTier != null; }
    public boolean storageNode() { return storage > 0; }
    public int coinCost() { return cost.getOrDefault(COINS, 0); }

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
        String houseTier = null; int capacity = 0; double tax = 0; Map<String, Double> needs = Map.of();
        if (json.has("housing")) {
            JsonObject h = json.getAsJsonObject("housing");
            houseTier = h.get("tier").getAsString(); capacity = h.get("capacity").getAsInt();
            tax = h.has("tax") ? h.get("tax").getAsDouble() : 0;
            Map<String, Double> n = new TreeMap<>();
            if (h.has("needs")) h.getAsJsonObject("needs").entrySet().forEach(e -> n.put(e.getKey(), e.getValue().getAsDouble()));
            needs = Collections.unmodifiableMap(n);
            if (capacity < 1) throw new IllegalArgumentException("Housing needs a positive capacity");
        }
        if (upkeep < 0 || storage < 0 || workforce < 0 || tax < 0) throw new IllegalArgumentException("Negative economy value");
        return new EconomyProfile(cost, upkeep, storage, workTier, workforce, cycle, inputs, outputs, houseTier, capacity, tax, needs);
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
            JsonObject n = new JsonObject(); needs.forEach(n::addProperty); h.add("needs", n); j.add("housing", h);
        }
        return j;
    }
}
