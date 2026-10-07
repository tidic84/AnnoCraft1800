package fr.annocraft.economy;

import fr.annocraft.world.IslandLayout;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class MaritimeDiplomacyTest {
    /** Minimal colony: two player ports, the second with a shipyard. */
    static final class TestColony implements Colony {
        final ColonyEconomy economy = new ColonyEconomy();
        final Diplomacy diplomacy = new Diplomacy();
        final Maritime maritime = new Maritime();
        final Geography geography = Geography.of(List.of(new IslandLayout(1800, 4096, 8), new IslandLayout(1800, 4096, 8, IslandLayout.NEW_WORLD)));
        final Set<String> ports = new TreeSet<>(Set.of("island_1", "island_2"));
        final List<Event> events = new ArrayList<>();
        int defense;
        TestColony() { diplomacy.claimHomes(geography); diplomacy.setRandom(new Random(1)); maritime.setRandom(new Random(1)); }
        public ColonyEconomy economy() { return economy; }
        public Diplomacy diplomacy() { return diplomacy; }
        public Maritime maritime() { return maritime; }
        public Geography geography() { return geography; }
        public Set<String> ports() { return ports; }
        public boolean hasShipyard(String island) { return "island_1".equals(island); }
        public int defense(String island) { return defense; }
        public void event(Event event) { events.add(event); }
    }
    static void run(TestColony c, int seconds) {
        for (int i = 0; i < seconds; i++) { c.maritime.step(c, 1); c.diplomacy.step(c, 1); }
    }

    @Test void factionsOwnTheirHomeIslandsAndBlockSettlement() {
        TestColony c = new TestColony();
        assertEquals("ashby", c.diplomacy.owner("island_7"));
        assertEquals("corsairs", c.diplomacy.owner("nw_island_7"));
        assertFalse(c.diplomacy.playerMayBuild("island_8"));
        assertTrue(c.diplomacy.playerMayBuild("island_3"));
        assertTrue(c.diplomacy.hostile("corsairs"));
        assertFalse(c.diplomacy.hostile("ashby"));
    }

    @Test void tradeRouteCarriesGoodsBetweenIslands() {
        TestColony c = new TestColony();
        c.economy.setSandbox(true);
        // Storage capacity comes from storage buildings; simulate two ports of 60.
        EconomyProfile port = EconomyProfile.parse(com.google.gson.JsonParser.parseString("{\"storage\":60}").getAsJsonObject());
        List<ColonyEconomy.Site> sites = List.of(new ColonyEconomy.Site(UUID.randomUUID(), "island_1", 0, 0, 3, 3, port),
                new ColonyEconomy.Site(UUID.randomUUID(), "island_2", 0, 0, 3, 3, port));
        c.economy.step(sites, Set.of(), 1, 1);
        c.economy.addStock("island_1", "timber", 40);
        assertNull(c.maritime.build(c, "island_1", "schooner"));
        UUID ship = c.maritime.ships().iterator().next().id;
        // Peace with the pirates for this test: no ambushes.
        c.diplomacy.faction("corsairs").stance = Diplomacy.Stance.CEASEFIRE; c.diplomacy.faction("corsairs").timer = 1e9;
        double before = c.economy.stock("island_1", "timber");
        assertNull(c.maritime.assignRoute(c, ship, "island_1", "island_2", "timber", null));
        for (int i = 0; i < 2000 && c.economy.stock("island_2", "timber") < 1; i++) { c.maritime.step(c, 1); c.economy.step(sites, Set.of(), 1, 1); }
        assertTrue(c.economy.stock("island_2", "timber") > 0, "Route delivered nothing");
        assertTrue(c.economy.stock("island_1", "timber") < before);
        assertEquals("invalid_route", c.maritime.assignRoute(c, ship, "island_1", "island_1", null, null));
    }

    @Test void shipsCostUnlockAndUpkeep() {
        TestColony c = new TestColony();
        assertEquals("no_goods", c.maritime.build(c, "island_1", "schooner"));
        assertEquals("no_shipyard", c.maritime.build(c, "island_2", "schooner"));
        assertEquals("locked", c.maritime.build(c, "island_1", "frigate"));
        c.economy.addStock("island_1", "timber", 10);
        assertNull(c.maritime.build(c, "island_1", "schooner"));
        assertEquals(ColonyEconomy.START_COINS - 500, c.economy.coins(), 1e-9);
        assertEquals(10, c.maritime.upkeep(), 1e-9);
    }

    @Test void diplomacyTreatiesFollowRelations() {
        TestColony c = new TestColony();
        assertEquals("refused", c.diplomacy.act(c, "ashby", "trade"));
        for (int i = 0; i < 2; i++) assertNull(c.diplomacy.act(c, "ashby", "gift"));
        assertNull(c.diplomacy.act(c, "ashby", "trade"));
        assertEquals(Diplomacy.Stance.TRADE, c.diplomacy.faction("ashby").stance);
        assertTrue(c.diplomacy.buyPrice("ashby", "fish") < c.diplomacy.buyPrice("dravek", "fish"));
        assertEquals("refused", c.diplomacy.act(c, "corsairs", "peace"));
        assertNull(c.diplomacy.act(c, "corsairs", "ceasefire"));
        assertEquals(ColonyEconomy.START_COINS - 2000 - Diplomacy.TRIBUTE, c.economy.coins(), 1e-9);
        assertFalse(c.diplomacy.hostile("corsairs"));
    }

    @Test void warshipsBesiegeAndConquerAnIsland() {
        TestColony c = new TestColony();
        c.economy.setSandbox(true);
        assertEquals("not_at_war", null == c.maritime.build(c, "island_1", "battleship") ? c.maritime.attack(c, c.maritime.ships().iterator().next().id, "island_8") : "build failed");
        assertNull(c.diplomacy.act(c, "dravek", "war"));
        for (int i = 0; i < 3; i++) assertNull(c.maritime.build(c, "island_1", "battleship"));
        for (Maritime.Ship s : List.copyOf(c.maritime.ships())) assertNull(c.maritime.attack(c, s.id, "island_8"));
        run(c, 3000);
        assertEquals(Diplomacy.PLAYER, c.diplomacy.owner("island_8"), "Siege failed: " + c.events);
        assertTrue(c.diplomacy.faction("dravek").eliminated);
        assertEquals(1, c.diplomacy.conquests());
    }

    @Test void undefendedPortsAreRaidedAndDefendedOnesRepel() {
        TestColony c = new TestColony();
        c.economy.addStock("island_1", "fish", 100); c.economy.addStock("island_2", "fish", 100);
        run(c, 20000);
        assertTrue(c.events.stream().anyMatch(e -> e.key().endsWith(".raid")), "No raid in 20000 s of war with the pirates");
        TestColony d = new TestColony(); d.defense = 500;
        d.economy.addStock("island_1", "fish", 100); d.economy.addStock("island_2", "fish", 100);
        run(d, 20000);
        assertTrue(d.events.stream().noneMatch(e -> e.key().endsWith(".raid")) && d.events.stream().anyMatch(e -> e.key().endsWith("raid_repelled")));
    }

    @Test void campaignAdvancesAndRewards() {
        TestColony c = new TestColony();
        Campaign.setMissions(List.of(
                new Campaign.Mission("a", 1, "agnes", List.of(new Campaign.Objective("coins", null, 1)), 100, Map.of("timber", 5), false),
                new Campaign.Mission("b", 1, "agnes", List.of(new Campaign.Objective("coins", null, 1_000_000)), 0, Map.of(), true)));
        Campaign campaign = new Campaign();
        campaign.start(c);
        campaign.step(c, o -> (int) c.economy.coins());
        assertEquals(1, campaign.index());
        assertEquals(ColonyEconomy.START_COINS + 100, c.economy.coins(), 1e-9);
        campaign.step(c, o -> (int) c.economy.coins());
        assertEquals(1, campaign.index(), "Unmet objective completed the mission");
        assertEquals(campaign.save(), Campaign.load(campaign.save()).save());
    }

    @Test void saveRoundTrips() {
        TestColony c = new TestColony(); c.economy.setSandbox(true);
        c.maritime.build(c, "island_1", "clipper"); c.diplomacy.act(c, "dravek", "war");
        assertEquals(c.maritime.save(), Maritime.load(c.maritime.save()).save());
        assertEquals(c.diplomacy.save(), Diplomacy.load(c.diplomacy.save()).save());
    }
}
