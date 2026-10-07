package fr.annocraft.economy;

import com.google.gson.*;
import fr.annocraft.world.IslandLayout;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Checks the generated building data as a whole: every need, input, cost and service can actually be supplied. */
class ContentConsistencyTest {
    record Def(String id, JsonObject json, EconomyProfile e) { }
    static List<Def> load() throws Exception {
        List<Def> defs = new ArrayList<>();
        try (var files = Files.list(Path.of("src/main/resources/data/annocraft1800/annocraft_buildings"))) {
            for (Path p : files.toList()) {
                JsonObject j = JsonParser.parseString(Files.readString(p)).getAsJsonObject();
                defs.add(new Def(p.getFileName().toString().replace(".json", ""), j, EconomyProfile.parse(j.getAsJsonObject("economy"))));
            }
        }
        return defs;
    }

    @Test void everyGoodServiceAndUpgradeIsSupplied() throws Exception {
        List<Def> defs = load();
        assertTrue(defs.size() >= 60, "Content table not generated");
        Map<String, Set<String>> producedIn = new HashMap<>();
        Set<String> services = new HashSet<>(), tiers = new HashSet<>(), structures = new HashSet<>();
        for (Def d : defs) {
            d.e.outputs().keySet().forEach(g -> producedIn.computeIfAbsent(g, k -> new HashSet<>()).add(d.e.world()));
            if (d.e.serviceProvider()) services.add(d.e.service());
            if (d.e.housing()) tiers.add(d.e.houseTier());
            assertTrue(Files.exists(Path.of("src/main/resources/data/annocraft1800/structures/" + d.id + ".nbt")), "Missing structure " + d.id);
        }
        for (Def d : defs) {
            List<String> goods = new ArrayList<>(d.e.inputs().keySet());
            goods.addAll(d.e.needs().keySet()); goods.addAll(d.e.luxury().keySet()); goods.addAll(d.e.boostInputs().keySet());
            d.e.cost().keySet().stream().filter(g -> !g.equals(EconomyProfile.COINS)).forEach(goods::add);
            for (String g : goods) assertTrue(producedIn.containsKey(g), d.id + " uses " + g + " but nothing produces it");
            for (String s : d.e.services()) assertTrue(services.contains(s), d.id + " needs service " + s + " that no building provides");
            for (String s : d.e.luxuryServices()) assertTrue(services.contains(s), d.id + " wants service " + s + " that no building provides");
            if (d.e.unlockTier() != null) assertTrue(tiers.contains(d.e.unlockTier()), d.id + " unlocks with unknown tier " + d.e.unlockTier());
            if (d.e.workforce() > 0) assertTrue(tiers.contains(d.e.workTier()), d.id + " employs unknown tier " + d.e.workTier());
            assertTrue(Diplomacy.PRICES.keySet().containsAll(d.e.outputs().keySet()), d.id + " produces a good merchants do not price");
            if (d.json.has("upgrade")) {
                String target = d.json.get("upgrade").getAsString().replace("annocraft1800:", "");
                Def next = defs.stream().filter(x -> x.id.equals(target)).findFirst().orElseThrow(() -> new AssertionError("Missing upgrade " + target));
                assertEquals(d.json.get("width").getAsInt(), next.json.get("width").getAsInt());
                assertEquals(d.json.get("depth").getAsInt(), next.json.get("depth").getAsInt());
                assertTrue(next.json.get("level").getAsInt() > d.json.get("level").getAsInt());
            }
        }
        // Every tier's basic needs are produced in its own world or can be shipped from the other one.
        for (Def d : defs) if (d.e.housing()) for (String g : d.e.needs().keySet()) assertFalse(producedIn.get(g).isEmpty());
    }

    @Test void resourceRequirementsExistOnTheDefaultArchipelagos() throws Exception {
        IslandLayout old = new IslandLayout(1800, 4096, 8), neu = new IslandLayout(1800, 4096, 8, IslandLayout.NEW_WORLD);
        for (Def d : load()) {
            List<IslandLayout> worlds = d.e.world().equals("new") ? List.of(neu) : d.e.world().equals("old") ? List.of(old) : List.of(old, neu);
            if (d.e.fertility() != null) assertTrue(worlds.stream().flatMap(l -> l.islands().stream()).anyMatch(i -> i.hasFertility(d.e.fertility())), d.id + ": no island has " + d.e.fertility());
            if (d.e.deposit() != null) assertTrue(worlds.stream().flatMap(l -> l.islands().stream()).anyMatch(i -> i.hasDeposit(d.e.deposit())), d.id + ": no island has " + d.e.deposit());
        }
        // The first island of each world can start a colony: food and its first chain.
        assertTrue(old.islands().get(0).hasFertility("potato") && old.islands().get(0).hasDeposit("clay"));
        assertTrue(neu.islands().get(0).hasFertility("plantain"));
    }
}
