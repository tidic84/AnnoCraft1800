package fr.annocraft.economy;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ColonyEconomyTest {
    static EconomyProfile profile(String json) { return EconomyProfile.parse(JsonParser.parseString(json).getAsJsonObject()); }
    static final EconomyProfile PORT = profile("{\"cost\":{\"coins\":500},\"storage\":60}");
    static final EconomyProfile HOUSE = profile("{\"cost\":{\"timber\":2},\"housing\":{\"tier\":\"farmers\",\"capacity\":10,\"tax\":1.0,\"needs\":{\"fish\":0.05}}}");
    static final EconomyProfile FISHERY = profile("{\"upkeep\":15,\"workforce\":{\"tier\":\"farmers\",\"amount\":15},\"production\":{\"cycle\":30,\"outputs\":{\"fish\":1}}}");
    static final EconomyProfile LUMBERJACK = profile("{\"upkeep\":5,\"workforce\":{\"tier\":\"farmers\",\"amount\":5},\"production\":{\"cycle\":15,\"outputs\":{\"wood\":1}}}");
    static final EconomyProfile SAWMILL = profile("{\"upkeep\":10,\"workforce\":{\"tier\":\"farmers\",\"amount\":10},\"production\":{\"cycle\":15,\"inputs\":{\"wood\":1},\"outputs\":{\"timber\":1}}}");

    static ColonyEconomy.Site site(String island, int x, int z, int w, int d, EconomyProfile p) {
        return new ColonyEconomy.Site(UUID.randomUUID(), island, x, z, w, d, p);
    }
    static Set<Long> road(int x0, int x1, int z) {
        Set<Long> tiles = new HashSet<>(); for (int x = x0; x <= x1; x++) tiles.add(ColonyEconomy.pack(x, z)); return tiles;
    }
    static void run(ColonyEconomy e, List<ColonyEconomy.Site> sites, Set<Long> roads, int seconds) {
        for (int i = 0; i < seconds; i++) e.step(sites, roads, 1, 1);
    }

    @Test void farmerFishLoopGrowsPopulationAndPaysForItself() {
        ColonyEconomy e = new ColonyEconomy();
        List<ColonyEconomy.Site> sites = new ArrayList<>();
        sites.add(site("a", 0, 0, 11, 9, PORT));
        List<ColonyEconomy.Site> houses = new ArrayList<>();
        for (int i = 0; i < 4; i++) houses.add(site("a", 12 + 8 * i, 10, 7, 7, HOUSE));
        sites.addAll(houses);
        sites.add(site("a", 44, 10, 7, 7, FISHERY)); sites.add(site("a", 52, 10, 5, 5, LUMBERJACK)); sites.add(site("a", 58, 10, 9, 7, SAWMILL));
        ColonyEconomy.Site lonely = site("a", 100, 100, 7, 7, HOUSE); sites.add(lonely);
        sites.forEach(e::placed);
        assertEquals(30, e.stock("a", "timber"), 1e-9, "Founding cargo delivered to the first storage island");
        Set<Long> roads = road(0, 70, 9);
        run(e, sites, roads, 15 * 60);
        for (ColonyEconomy.Site h : houses) {
            assertTrue(e.state(h.id()).residents >= 9, "Supplied residence did not fill: " + e.state(h.id()).residents);
            assertEquals(ColonyEconomy.Status.OK, e.state(h.id()).status);
        }
        assertEquals(ColonyEconomy.Status.NO_ROAD, e.state(lonely.id()).status);
        assertTrue(e.state(lonely.id()).residents <= 1);
        assertTrue(e.stock("a", "timber") > 30, "Sawmill chain produced no timber");
        assertArrayEquals(new int[]{e.workforce("a", "farmers")[0], 30}, e.workforce("a", "farmers"));
        assertTrue(e.workforce("a", "farmers")[0] >= 36);
        assertTrue(e.incomePerMinute() > e.upkeepPerMinute(), "Loop runs at a loss: " + e.incomePerMinute() + " vs " + e.upkeepPerMinute());
        assertTrue(e.rate("a", "timber") > 0);
    }

    @Test void productionNeedsRoadsWorkforceInputsAndSpace() {
        ColonyEconomy e = new ColonyEconomy();
        ColonyEconomy.Site port = site("a", 0, 0, 11, 9, PORT), fishery = site("a", 12, 10, 7, 7, FISHERY), saw = site("a", 20, 10, 9, 7, SAWMILL);
        ColonyEconomy.Site far = site("a", 40, 40, 7, 7, FISHERY);
        List<ColonyEconomy.Site> sites = List.of(port, fishery, saw, far);
        sites.forEach(e::placed);
        run(e, sites, road(0, 30, 9), 60);
        assertEquals(ColonyEconomy.Status.NO_WORKFORCE, e.state(fishery.id()).status);
        assertEquals(ColonyEconomy.Status.NO_ROAD, e.state(far.id()).status);
        assertEquals(10, e.stock("a", "fish"), 1e-9, "Production without workers");

        EconomyProfile free = profile("{\"storage\":0,\"production\":{\"cycle\":1,\"outputs\":{\"wood\":1}}}");
        EconomyProfile mill = profile("{\"production\":{\"cycle\":1,\"inputs\":{\"stone\":1},\"outputs\":{\"timber\":1}}}");
        ColonyEconomy f = new ColonyEconomy();
        ColonyEconomy.Site depot = site("b", 0, 0, 3, 3, PORT), cutter = site("b", 4, 2, 1, 1, free), idle = site("b", 6, 2, 1, 1, mill);
        List<ColonyEconomy.Site> b = List.of(depot, cutter, idle);
        b.forEach(f::placed);
        Set<Long> roads = road(0, 7, 3);
        run(f, b, roads, 100);
        assertEquals(60, f.stock("b", "wood"), 1e-9, "Storage capacity not enforced");
        assertEquals(ColonyEconomy.Status.STORAGE_FULL, f.state(cutter.id()).status);
        assertEquals(ColonyEconomy.Status.NO_INPUT, f.state(idle.id()).status);
    }

    @Test void unmetNeedsHalvePopulationAndBlockUpgrades() {
        ColonyEconomy e = new ColonyEconomy();
        ColonyEconomy.Site port = site("a", 0, 0, 11, 9, PORT), house = site("a", 12, 10, 7, 7, HOUSE);
        List<ColonyEconomy.Site> sites = List.of(port, house);
        sites.forEach(e::placed);
        e.addStock("a", "fish", -e.stock("a", "fish"));
        run(e, sites, road(0, 20, 9), 300);
        assertEquals(5, e.state(house.id()).residents, .3);
        assertEquals(ColonyEconomy.Status.NEEDS_UNMET, e.state(house.id()).status);
        assertFalse(e.upgradeReady(house.id(), HOUSE));
        e.addStock("a", "fish", 60);
        run(e, sites, road(0, 20, 9), 300);
        assertTrue(e.upgradeReady(house.id(), HOUSE), "Full, supplied residence cannot upgrade");
    }

    @Test void costsSandboxAndIslandSeparation() {
        ColonyEconomy e = new ColonyEconomy();
        assertEquals("no_goods", e.checkCost("a", HOUSE));
        assertNull(e.checkCost("a", PORT));
        e.pay("a", PORT); assertEquals(ColonyEconomy.START_COINS - 500, e.coins(), 1e-9);
        e.setSandbox(true); assertNull(e.checkCost("a", HOUSE)); e.pay("a", HOUSE); assertEquals(0, e.stock("a", "timber"), 1e-9);
        // A shared road component never links buildings to another island's warehouse.
        ColonyEconomy.Site port = site("a", 0, 0, 3, 3, PORT), other = site("b", 4, 0, 1, 1, FISHERY);
        assertEquals(Set.of(port.id()), ColonyEconomy.connectivity(List.of(port, other), road(0, 5, 3)));
    }

    @Test void poweredProducersWorkTwiceAsFastAndBurnCoal() {
        EconomyProfile mill = profile("{\"production\":{\"cycle\":10,\"outputs\":{\"timber\":1}}}");
        EconomyProfile plant = profile("{\"boost\":{\"radius\":40,\"productivity\":1.0,\"inputs\":{\"coal\":6}}}");
        ColonyEconomy plain = new ColonyEconomy(), powered = new ColonyEconomy();
        ColonyEconomy.Site port = site("a", 0, 0, 3, 3, PORT), saw = site("a", 4, 2, 1, 1, mill), power = site("a", 6, 2, 1, 1, plant);
        List<ColonyEconomy.Site> without = List.of(port, saw), with = List.of(port, saw, power);
        without.forEach(plain::placed); with.forEach(powered::placed);
        powered.addStock("a", "coal", 10);
        run(plain, without, road(0, 8, 3), 120); run(powered, with, road(0, 8, 3), 120);
        double base = plain.stock("a", "timber") - 30, boosted = powered.stock("a", "timber") - 30;
        assertTrue(boosted >= 2 * base - 1, "Power did not double output: " + base + " vs " + boosted);
        assertTrue(powered.stock("a", "coal") < 1, "Power plant burnt no coal");
        assertEquals(ColonyEconomy.Status.NO_INPUT, powered.state(power.id()).status, "Power plant kept running without coal");
    }

    @Test void saveRoundTripKeepsStocksAndResidents() {
        ColonyEconomy e = new ColonyEconomy();
        ColonyEconomy.Site port = site("a", 0, 0, 11, 9, PORT), house = site("a", 12, 10, 7, 7, HOUSE);
        List<ColonyEconomy.Site> sites = List.of(port, house);
        sites.forEach(e::placed); run(e, sites, road(0, 20, 9), 30);
        var tag = e.save();
        ColonyEconomy restored = ColonyEconomy.load(tag);
        assertEquals(tag, restored.save());
        assertEquals(e.state(house.id()).residents, restored.state(house.id()).residents, 1e-12);
        assertEquals(e.stock("a", "fish"), restored.stock("a", "fish"), 1e-12);
    }
}
