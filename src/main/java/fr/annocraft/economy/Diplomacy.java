package fr.annocraft.economy;

import net.minecraft.nbt.*;
import java.util.*;

/**
 * Rival factions, island ownership, treaties, trade prices, raids and conquest.
 * Factions are abstract: their islands have a defence and a garrison, not simulated buildings.
 */
public final class Diplomacy {
    public static final String PLAYER = "player";
    public enum Stance { WAR, CEASEFIRE, PEACE, TRADE, ALLIANCE }
    /** @param homes islands owned at the start, when present in the world */
    public record FactionType(String id, boolean pirate, int baseRelation, int level, List<String> homes) { }
    public static final List<FactionType> FACTIONS = List.of(
            new FactionType("ashby", false, 10, 2, List.of("island_7", "nw_island_8")),
            new FactionType("dravek", false, -20, 3, List.of("island_8")),
            new FactionType("corsairs", true, -40, 2, List.of("island_6", "nw_island_7")));
    public static final int TRIBUTE = 2000;
    public static final Map<String, Integer> PRICES = Map.ofEntries(
            Map.entry("fish", 10), Map.entry("wood", 5), Map.entry("timber", 12), Map.entry("wool", 10), Map.entry("work_clothes", 30),
            Map.entry("potatoes", 8), Map.entry("schnapps", 30), Map.entry("grain", 10), Map.entry("flour", 20), Map.entry("bread", 45),
            Map.entry("pigs", 15), Map.entry("sausages", 45), Map.entry("tallow", 25), Map.entry("soap", 55), Map.entry("hops", 12),
            Map.entry("beer", 60), Map.entry("clay", 8), Map.entry("bricks", 35), Map.entry("iron", 20), Map.entry("coal", 15),
            Map.entry("steel", 60), Map.entry("steel_beams", 110), Map.entry("weapons", 140), Map.entry("sails", 45), Map.entry("beef", 25),
            Map.entry("canned_food", 120), Map.entry("sewing_machines", 160), Map.entry("sand", 10), Map.entry("glass", 40),
            Map.entry("windows", 120), Map.entry("light_bulbs", 180), Map.entry("grapes", 20), Map.entry("champagne", 220),
            Map.entry("gold", 80), Map.entry("pocket_watches", 350), Map.entry("jewelry", 400), Map.entry("plantains", 10),
            Map.entry("alpaca_wool", 12), Map.entry("ponchos", 35), Map.entry("sugar_cane", 12), Map.entry("rum", 80),
            Map.entry("coffee_beans", 20), Map.entry("coffee", 100), Map.entry("tobacco", 25), Map.entry("cigars", 180));
    public static final class Faction {
        public double relation, timer;
        public Stance stance;
        public boolean eliminated;
    }
    private final Map<String, Faction> factions = new LinkedHashMap<>();
    /** Island id to owner: a faction id or {@link #PLAYER}. Unlisted islands are free to settle. */
    private final Map<String, String> owners = new TreeMap<>();
    /** Remaining garrison of faction islands, 0..1 of their maximum. */
    private final Map<String, Double> garrison = new TreeMap<>();
    private int victories, conquests;
    private Random random = new Random();

    public Diplomacy() {
        for (FactionType t : FACTIONS) {
            Faction f = new Faction(); f.relation = t.baseRelation(); f.stance = t.pirate() ? Stance.WAR : Stance.PEACE; factions.put(t.id(), f);
        }
    }
    public void setRandom(Random random) { this.random = random; }
    public static FactionType type(String id) { return FACTIONS.stream().filter(t -> t.id().equals(id)).findFirst().orElse(null); }
    public Faction faction(String id) { return factions.get(id); }
    public Map<String, String> owners() { return Collections.unmodifiableMap(owners); }
    public String owner(String island) { return owners.get(island); }
    public int victories() { return victories; }
    public int conquests() { return conquests; }
    public void victory() { victories++; }
    /** Gives each faction its home islands that exist and are not already settled. */
    public void claimHomes(Geography geo) {
        for (FactionType t : FACTIONS) for (String island : t.homes())
            if (geo.exists(island) && !owners.containsKey(island)) { owners.put(island, t.id()); garrison.put(island, 1.0); }
    }
    public void claim(String island) { owners.putIfAbsent(island, PLAYER); }
    public boolean playerMayBuild(String island) { String o = owners.get(island); return o == null || PLAYER.equals(o); }
    public boolean hostile(String faction) { Faction f = factions.get(faction); return f != null && !f.eliminated && f.stance == Stance.WAR; }
    public List<String> factionIslands(String faction) { return owners.entrySet().stream().filter(e -> e.getValue().equals(faction)).map(Map.Entry::getKey).toList(); }
    public double garrison(String island) { return garrison.getOrDefault(island, 0.0); }
    public static int garrisonStrength(String faction) { FactionType t = type(faction); return t == null ? 0 : t.level() * 300; }

    public double buyPrice(String faction, String good) {
        Faction f = factions.get(faction); int base = PRICES.getOrDefault(good, 0);
        return base * (f != null && f.stance.ordinal() >= Stance.TRADE.ordinal() ? 1.0 : 1.25);
    }
    public double sellPrice(String faction, String good) {
        Faction f = factions.get(faction); int base = PRICES.getOrDefault(good, 0);
        return base * (f != null && f.stance.ordinal() >= Stance.TRADE.ordinal() ? .8 : .6);
    }
    public boolean trades(String faction) { Faction f = factions.get(faction); return f != null && !f.eliminated && f.stance != Stance.WAR; }

    /** Applies a diplomatic action. @return null on success, otherwise a message suffix. */
    public String act(Colony colony, String faction, String action) {
        Faction f = factions.get(faction); FactionType t = type(faction);
        if (f == null || f.eliminated) return "invalid_faction";
        ColonyEconomy e = colony.economy();
        switch (action) {
            case "gift" -> {
                if (!e.sandbox() && e.coins() < 1000) return "no_coins";
                if (!e.sandbox()) e.addCoins(-1000);
                f.relation = Math.min(100, f.relation + 15);
            }
            case "war" -> {
                if (f.stance == Stance.WAR) return "already";
                f.stance = Stance.WAR; f.relation = Math.max(-100, f.relation - 30);
            }
            case "ceasefire" -> {
                if (f.stance != Stance.WAR) return "already";
                if (t.pirate()) {
                    if (!e.sandbox() && e.coins() < TRIBUTE) return "no_coins";
                    if (!e.sandbox()) e.addCoins(-TRIBUTE);
                } else if (f.relation < -50) return "refused";
                f.stance = Stance.CEASEFIRE; f.timer = 1200;
            }
            case "peace" -> {
                if (t.pirate()) return "refused";
                if (f.stance != Stance.WAR && f.stance != Stance.CEASEFIRE) return "already";
                if (f.relation < 0) return "refused";
                f.stance = Stance.PEACE;
            }
            case "trade" -> {
                if (t.pirate() || f.stance != Stance.PEACE) return "refused";
                if (f.relation < 30) return "refused";
                f.stance = Stance.TRADE;
            }
            case "alliance" -> {
                if (t.pirate() || f.stance != Stance.TRADE) return "refused";
                if (f.relation < 70) return "refused";
                f.stance = Stance.ALLIANCE;
            }
            default -> { return "invalid_faction"; }
        }
        return null;
    }

    public void step(Colony colony, double dt) {
        for (FactionType t : FACTIONS) {
            Faction f = factions.get(t.id()); if (f.eliminated) continue;
            // Relations drift back toward the faction's temperament; treaties make it warmer.
            double target = t.baseRelation() + (f.stance == Stance.TRADE ? 20 : f.stance == Stance.ALLIANCE ? 35 : 0);
            f.relation += Math.signum(target - f.relation) * Math.min(Math.abs(target - f.relation), dt / 60);
            if (f.stance == Stance.CEASEFIRE && (f.timer -= dt) <= 0)
                f.stance = t.pirate() || f.relation < -20 ? Stance.WAR : Stance.PEACE;
            if (f.stance == Stance.PEACE && f.relation < -40 && random.nextDouble() < dt / 600) {
                f.stance = Stance.WAR; colony.event(Colony.Event.of(false, "event.annocraft1800.war_declared", "#faction.annocraft1800." + t.id()));
            }
            if (f.stance == Stance.WAR && random.nextDouble() < dt / 900) raid(colony, t);
            if (!t.pirate() && !colony.economy().sandbox() && random.nextDouble() < dt / 1500) expand(colony, t);
        }
        // Garrisons recover when nobody besieges them.
        garrison.replaceAll((island, g) -> Math.min(1, g + dt / 600));
    }
    /** Rivals settle free islands of the worlds they already hold, three islands at most: the archipelago is contested. */
    private void expand(Colony colony, FactionType t) {
        List<String> held = factionIslands(t.id()); if (held.size() >= 3 || held.isEmpty()) return;
        Set<String> worlds = new HashSet<>(); held.forEach(i -> worlds.add(colony.geography().world(i)));
        List<String> free = colony.geography().islands().keySet().stream()
                .filter(i -> !owners.containsKey(i) && !colony.ports().contains(i) && worlds.contains(colony.geography().world(i))).toList();
        if (free.isEmpty()) return;
        String island = free.get(random.nextInt(free.size()));
        owners.put(island, t.id()); garrison.put(island, 1.0);
        colony.event(Colony.Event.of(false, "event.annocraft1800.island_claimed", "#faction.annocraft1800." + t.id(), island));
    }
    private void raid(Colony colony, FactionType t) {
        List<String> ports = new ArrayList<>(colony.ports()); if (ports.isEmpty()) return;
        String island = ports.get(random.nextInt(ports.size()));
        int strength = t.level() * 40, defense = colony.defense(island) + colony.maritime().dockedAttack(island);
        if (defense >= strength) {
            colony.event(Colony.Event.of(true, "event.annocraft1800.raid_repelled", "#faction.annocraft1800." + t.id(), island));
            return;
        }
        ColonyEconomy e = colony.economy();
        e.stocks(island).entrySet().stream().max(Map.Entry.comparingByValue()).ifPresent(top -> {
            double stolen = Math.floor(top.getValue() * .25);
            if (stolen < 1) return;
            e.addStock(island, top.getKey(), -stolen);
            colony.event(Colony.Event.of(false, "event.annocraft1800.raid", "#faction.annocraft1800." + t.id(), island, String.valueOf((long) stolen), "#good.annocraft1800." + top.getKey()));
        });
    }
    /** Warships besieging a faction island. @return true when the island falls. */
    public boolean siege(Colony colony, String island, int attack, double dt) {
        String owner = owners.get(island);
        if (owner == null || PLAYER.equals(owner) || !hostile(owner) || attack <= 0) return false;
        double g = garrison.getOrDefault(island, 1.0) - attack * dt / garrisonStrength(owner) / 10;
        // Undo this step's regeneration so a siege never stalls on recovery.
        garrison.put(island, Math.max(0, g - dt / 600));
        if (g > 0) return false;
        owners.put(island, PLAYER); garrison.remove(island); conquests++;
        Faction f = factions.get(owner); f.relation = Math.max(-100, f.relation - 40);
        colony.economy().addCoins(1500 * type(owner).level());
        colony.event(Colony.Event.of(true, "event.annocraft1800.conquered", island, "#faction.annocraft1800." + owner));
        if (factionIslands(owner).isEmpty()) {
            f.eliminated = true;
            colony.event(Colony.Event.of(true, "event.annocraft1800.eliminated", "#faction.annocraft1800." + owner));
        }
        return true;
    }
    /** Damage a garrison deals per second to besieging ships. */
    public double garrisonFire(String island) {
        String owner = owners.get(island); FactionType t = owner == null ? null : type(owner);
        return t == null ? 0 : t.level() * 4 * garrison.getOrDefault(island, 0.0);
    }

    public CompoundTag save() {
        CompoundTag t = new CompoundTag();
        CompoundTag fs = new CompoundTag();
        factions.forEach((id, f) -> {
            CompoundTag c = new CompoundTag(); c.putDouble("relation", f.relation); c.putString("stance", f.stance.name());
            c.putDouble("timer", f.timer); c.putBoolean("eliminated", f.eliminated); fs.put(id, c);
        });
        t.put("factions", fs);
        CompoundTag o = new CompoundTag(); owners.forEach(o::putString); t.put("owners", o);
        CompoundTag g = new CompoundTag(); garrison.forEach(g::putDouble); t.put("garrison", g);
        t.putInt("victories", victories); t.putInt("conquests", conquests);
        return t;
    }
    public static Diplomacy load(CompoundTag t) {
        Diplomacy d = new Diplomacy();
        CompoundTag fs = t.getCompound("factions");
        for (String id : fs.getAllKeys()) {
            Faction f = d.factions.get(id); if (f == null) continue;
            CompoundTag c = fs.getCompound(id);
            f.relation = c.getDouble("relation"); f.timer = c.getDouble("timer"); f.eliminated = c.getBoolean("eliminated");
            try { f.stance = Stance.valueOf(c.getString("stance")); } catch (IllegalArgumentException ignored) { }
        }
        CompoundTag o = t.getCompound("owners"); for (String island : o.getAllKeys()) d.owners.put(island, o.getString(island));
        CompoundTag g = t.getCompound("garrison"); for (String island : g.getAllKeys()) d.garrison.put(island, g.getDouble(island));
        d.victories = t.getInt("victories"); d.conquests = t.getInt("conquests");
        return d;
    }
    public CompoundTag snapshot() {
        CompoundTag t = save();
        CompoundTag fs = t.getCompound("factions");
        for (FactionType type : FACTIONS) fs.getCompound(type.id()).putBoolean("pirate", type.pirate());
        return t;
    }
}
