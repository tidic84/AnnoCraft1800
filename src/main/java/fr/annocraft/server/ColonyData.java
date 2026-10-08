package fr.annocraft.server;

import fr.annocraft.building.*;
import fr.annocraft.economy.*;
import fr.annocraft.world.IslandLayout;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.storage.LevelResource;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/**
 * The archipelago's save: buildings, roads, both worlds' layouts, who holds which island, and the companies.
 * A cooperative game has one company colony shared by every player ({@link #COLONY}); a competitive game, chosen
 * when the world is created (game rule {@code annocraftCompetitive}), gives every player a colony of their own,
 * with its treasury, islands, fleet and relations, and treaties or wars between the companies.
 * As a {@link Colony} it stands for the shared colony, which is also the one tests and old saves use.
 */
public final class ColonyData extends SavedData implements Colony {
    /** Version 2 added the economy and roads; version 3 adds both worlds, ships, diplomacy and the campaign. */
    public static final int VERSION = 3;
    private static final String FILE = "annocraft1800_colony";
    private static final Map<MinecraftServer, ColonyData> CACHE = new WeakHashMap<>();
    /** The cooperative colony's company id; in a competitive game, the archipelago's steward of the factions. */
    public static final String COLONY = Diplomacy.PLAYER;
    private final Map<UUID, BuildingInstance> buildings = new LinkedHashMap<>();
    /** Road ground block position to the original block state it replaced. */
    private final Map<Long, CompoundTag> roads = new LinkedHashMap<>();
    private Set<Long> roadTiles;
    /** Company colonies by company id, the shared one first. */
    private final Map<String, CompanyColony> colonies = new LinkedHashMap<>();
    private boolean competitive, modeChosen;
    /** World name ("old", "new") to its archipelago settings. */
    private final Map<String, CompoundTag> worlds = new TreeMap<>();
    private Geography geography = Geography.of(List.of());
    /** Pending news, each for one company ({@link #COLONY}: everyone). */
    private final List<Map.Entry<String, Event>> events = new ArrayList<>();
    /** Faction islands whose visible settlement was built ("built") or removed after conquest ("cleared"). */
    private final Map<String, String> decorations = new TreeMap<>();
    /** Companies' looks by id and the players taking part. */
    private final Map<String, Company> companies = new LinkedHashMap<>();
    private final Map<UUID, Company.Member> members = new LinkedHashMap<>();
    /** Treaties between companies ("a|b", a before b): peace unless listed; proposals waiting for an answer ("from|to" to the proposal). */
    private final Map<String, Diplomacy.Stance> pacts = new TreeMap<>();
    private final Map<String, String> proposals = new TreeMap<>();
    private long revision, simulated;

    public ColonyData() { colonies.put(COLONY, new CompanyColony(this, COLONY, new Diplomacy())); }
    CompanyColony shared() { return colonies.get(COLONY); }
    public Diplomacy.Territory territory() { return shared().diplomacy.territory(); }
    public Map<UUID, BuildingInstance> buildings() { return Collections.unmodifiableMap(buildings); }
    public Map<Long, CompoundTag> roads() { return Collections.unmodifiableMap(roads); }
    @Override public ColonyEconomy economy() { return shared().economy; }
    @Override public Diplomacy diplomacy() { return shared().diplomacy; }
    @Override public Maritime maritime() { return shared().maritime; }
    @Override public Geography geography() { return geography; }
    public Campaign campaign() { return shared().campaign; }
    public Map<String, Company> companies() { return Collections.unmodifiableMap(companies); }
    public Map<UUID, Company.Member> members() { return Collections.unmodifiableMap(members); }
    public boolean competitive() { return competitive; }
    public Collection<CompanyColony> colonies() { return Collections.unmodifiableCollection(colonies.values()); }

    // ---------------------------------------------------------------- companies and players

    /** The colony of a company, opened on first use (competitive games). */
    public CompanyColony colony(String company) {
        return colonies.computeIfAbsent(company, id -> {
            setDirty();
            CompanyColony c = new CompanyColony(this, id, new Diplomacy(territory(), id, false));
            // Free construction is the server's choice, for every company.
            c.economy.setSandbox(shared().economy.sandbox());
            return c;
        });
    }
    /** The colony a player manages. */
    public CompanyColony colony(ServerPlayer player) { return colony(enroll(player.getUUID(), player.getGameProfile().getName()).company()); }
    /** The colony a building belongs to: the company holding its island (the shared colony on free islands). */
    public CompanyColony colonyOf(BuildingInstance b) {
        CompanyColony c = colonies.get(territory().owners.get(b.island()));
        return c != null ? c : shared();
    }
    /** Makes sure a player arriving in the archipelago has a portrait and a company, with a random look until they choose one. */
    public Company.Member enroll(UUID player, String name) {
        Company.Member m = members.get(player);
        String company = competitive ? "co_" + player.toString().replace("-", "") : COLONY;
        if (m == null || !m.name().equals(name) || !m.company().equals(company)) {
            m = new Company.Member(player, name, m == null ? Company.Member.AVATARS.get(Math.floorMod(player.hashCode(), 8)) : m.avatar(), company);
            members.put(player, m); setDirty();
        }
        companies.computeIfAbsent(m.company(), id -> { setDirty(); return Company.random(id, name, new Random(player.getLeastSignificantBits())); });
        colony(m.company());
        return m;
    }
    /** A player's choice of name, colour, flag and portrait. @return null on success, otherwise a message suffix */
    public String brand(UUID player, String name, int color, byte[] flag, String avatar) {
        Company.Member m = members.get(player); if (m == null) return "invalid_action";
        if (Company.clean(name).isEmpty()) return "company_name";
        if (flag == null || flag.length != Company.FLAG_W * Company.FLAG_H || !Company.Member.AVATARS.contains(avatar)) return "invalid_action";
        companies.put(m.company(), companies.get(m.company()).with(name, color, flag));
        members.put(player, new Company.Member(player, m.name(), avatar, m.company()));
        setDirty(); return null;
    }
    /** The game mode, chosen once: by the world's game rule for a new archipelago, cooperative for older saves. */
    public void adoptMode(boolean competitiveRule) {
        if (modeChosen) return;
        competitive = competitiveRule && members.isEmpty() && buildings.isEmpty();
        modeChosen = true; setDirty();
    }

    // ---------------------------------------------------------------- treaties between companies

    private static String pair(String a, String b) { return a.compareTo(b) < 0 ? a + "|" + b : b + "|" + a; }
    public Diplomacy.Stance stance(String a, String b) { return pacts.getOrDefault(pair(a, b), Diplomacy.Stance.PEACE); }
    public boolean atWar(String a, String b) { return a != null && b != null && !a.equals(b) && stance(a, b) == Diplomacy.Stance.WAR; }
    /**
     * A company's move towards another, as on Anno's diplomacy screen: war is declared at once; peace, a trade treaty
     * or an alliance is proposed and waits for the other company's answer ("accept" or "decline"); a gift of 1 000
     * coins arrives at once. @return null on success, otherwise a message suffix
     */
    public String pact(String from, String to, String action) {
        if (from.equals(to) || !colonies.containsKey(to) || !companies.containsKey(to)) return "invalid_faction";
        Diplomacy.Stance now = stance(from, to);
        String name = companies.get(from).name(), other = companies.get(to).name();
        switch (action) {
            case "gift" -> {
                ColonyEconomy e = colonies.get(from).economy;
                if (!e.sandbox() && e.coins() < 1000) return "no_coins";
                if (!e.sandbox()) e.addCoins(-1000);
                colonies.get(to).economy.addCoins(1000);
                event(to, Event.of(true, "event.annocraft1800.company_gift", name, "1000"));
            }
            case "war" -> {
                if (now == Diplomacy.Stance.WAR) return "already";
                pacts.put(pair(from, to), Diplomacy.Stance.WAR); proposals.remove(from + "|" + to); proposals.remove(to + "|" + from);
                event(to, Event.of(false, "event.annocraft1800.company_war", name)); event(from, Event.of(false, "event.annocraft1800.company_war_declared", other));
            }
            case "peace", "trade", "alliance" -> {
                Diplomacy.Stance wanted = switch (action) { case "trade" -> Diplomacy.Stance.TRADE; case "alliance" -> Diplomacy.Stance.ALLIANCE; default -> Diplomacy.Stance.PEACE; };
                if (now == wanted) return "already";
                if (wanted == Diplomacy.Stance.ALLIANCE && now != Diplomacy.Stance.TRADE) return "refused";
                proposals.put(from + "|" + to, action);
                event(to, Event.of(true, "event.annocraft1800.company_proposal", name, "#colony.annocraft1800." + (action.equals("trade") ? "trade_treaty" : action)));
            }
            case "accept", "decline" -> {
                String offer = proposals.remove(to + "|" + from);
                if (offer == null) return "invalid_action";
                if (action.equals("decline")) { event(to, Event.of(false, "event.annocraft1800.company_declined", name)); break; }
                Diplomacy.Stance wanted = switch (offer) { case "trade" -> Diplomacy.Stance.TRADE; case "alliance" -> Diplomacy.Stance.ALLIANCE; default -> Diplomacy.Stance.PEACE; };
                if (wanted == Diplomacy.Stance.PEACE) pacts.remove(pair(from, to)); else pacts.put(pair(from, to), wanted);
                event(to, Event.of(true, "event.annocraft1800.company_accepted", name, "#stance.annocraft1800." + wanted.name().toLowerCase(Locale.ROOT)));
                event(from, Event.of(true, "event.annocraft1800.company_accepted", other, "#stance.annocraft1800." + wanted.name().toLowerCase(Locale.ROOT)));
            }
            default -> { return "invalid_faction"; }
        }
        setDirty(); return null;
    }
    /** The other companies as one company sees them: stance, proposals both ways, players and looks. */
    void rivalry(String self, CompoundTag t) {
        ListTag list = new ListTag();
        for (CompanyColony c : colonies.values()) {
            if (c.id.equals(self) || !companies.containsKey(c.id)) continue;
            CompoundTag r = new CompoundTag(); r.putString("id", c.id);
            r.putString("stance", stance(self, c.id).name());
            if (proposals.containsKey(self + "|" + c.id)) r.putString("offered", proposals.get(self + "|" + c.id));
            if (proposals.containsKey(c.id + "|" + self)) r.putString("offer", proposals.get(c.id + "|" + self));
            r.putInt("islands", c.ports().size()); r.putInt("population", c.economy.totalPopulation());
            list.add(r);
        }
        t.put("rivals", list);
        t.put("companies", brands());
    }
    /** The other companies' ships at sea, for a competitive game's naval battles. */
    Collection<Maritime.Ship> foreignShips(String self) {
        if (!competitive) return List.of();
        List<Maritime.Ship> list = new ArrayList<>();
        for (CompanyColony c : colonies.values()) if (!c.id.equals(self)) list.addAll(c.maritime.fleet());
        return list;
    }
    String companyOfShip(Maritime.Ship ship) {
        for (CompanyColony c : colonies.values()) if (c.maritime.ship(ship.id) == ship) return c.id;
        return null;
    }
    void sinkForeign(String by, Maritime.Ship ship) {
        String owner = companyOfShip(ship); if (owner == null) return;
        colonies.get(owner).maritime.lose(colonies.get(owner), ship.id);
    }

    // ---------------------------------------------------------------- buildings and roads

    public long revision() { return revision; }
    public void put(BuildingInstance building) { put(building, colonyOf(building)); }
    /** A building placed by a company; storage claims its island for that company. */
    public void put(BuildingInstance building, CompanyColony owner) {
        boolean added = !buildings.containsKey(building.id());
        buildings.put(building.id(), building);
        if (added) {
            ColonyEconomy.Site site = site(building);
            // Storage claims an island; in a competitive game any first building does (free construction).
            if (site.profile().storageNode() || competitive) owner.diplomacy.claim(building.island());
            colonyOf(building).economy.placed(site);
        }
        changed();
    }
    public void remove(UUID id) {
        BuildingInstance b = buildings.get(id);
        if (b != null) colonyOf(b).economy.removed(id);
        buildings.remove(id); changed();
    }
    /** New World positions are shifted so both worlds share one road and footprint index without colliding. */
    public static final int NEW_WORLD_OFFSET = 100_000_000, NEW_WORLD_Y = 1024;
    public static long roadKey(String world, BlockPos ground) { return (IslandLayout.NEW_WORLD.equals(world) ? ground.above(NEW_WORLD_Y) : ground).asLong(); }
    public static long tile(String world, int x, int z) { return ColonyEconomy.pack(IslandLayout.NEW_WORLD.equals(world) ? x + NEW_WORLD_OFFSET : x, z); }
    public void putRoad(String world, BlockPos ground, CompoundTag original) { roads.put(roadKey(world, ground), original); roadTiles = null; changed(); }
    public void removeRoad(String world, BlockPos ground) { roads.remove(roadKey(world, ground)); roadTiles = null; changed(); }
    public boolean road(String world, BlockPos ground) { return roads.containsKey(roadKey(world, ground)); }
    public CompoundTag roadOriginal(String world, BlockPos ground) { return roads.get(roadKey(world, ground)); }
    public Set<Long> roadTiles() {
        if (roadTiles == null) {
            Set<Long> tiles = new HashSet<>();
            for (long pos : roads.keySet()) { BlockPos p = BlockPos.of(pos); tiles.add(tile(p.getY() >= NEW_WORLD_Y ? IslandLayout.NEW_WORLD : IslandLayout.OLD_WORLD, p.getX(), p.getZ())); }
            roadTiles = tiles;
        }
        return roadTiles;
    }
    private void changed() { revision++; setDirty(); }
    public void markDirty() { setDirty(); }
    public static ColonyEconomy.Site site(BuildingInstance b) {
        int x = b.origin().getX() + (IslandLayout.NEW_WORLD.equals(ColonyEconomy.worldOf(b.island())) ? NEW_WORLD_OFFSET : 0);
        return new ColonyEconomy.Site(b.id(), b.island(), x, b.origin().getZ(), b.width(), b.depth(), profile(b));
    }
    public static EconomyProfile profile(BuildingInstance b) {
        BuildingDefinition def = BuildingDefinitions.get(b.definition());
        return def == null ? EconomyProfile.NONE : def.economy();
    }
    public List<ColonyEconomy.Site> sites() { return shared().sites(); }

    // ---------------------------------------------------------------- the shared colony, as a Colony

    @Override public Set<String> ports() { return shared().ports(); }
    @Override public boolean hasShipyard(String island) { return shared().hasShipyard(island); }
    @Override public int defense(String island) { return shared().defense(island); }
    @Override public void event(Event event) { event(COLONY, event); }
    /** News for one company's players ({@link #COLONY}, in a cooperative game or for the factions' moves: everyone). */
    void event(String company, Event event) { events.add(Map.entry(company, event)); }
    public List<Map.Entry<String, Event>> drainEvents() { List<Map.Entry<String, Event>> out = List.copyOf(events); events.clear(); return out; }
    public boolean decorated(String island) { return decorations.containsKey(island); }
    public String decoration(String island) { return decorations.get(island); }
    public void markDecorated(String island) { decorations.put(island, "built"); setDirty(); }
    public void markCleared(String island) { decorations.put(island, "cleared"); setDirty(); }
    private long indexRevision = -1;
    private Map<Long, List<BuildingInstance>> chunkIndex = Map.of();
    /** Managed building containing a block, looked up through a per-chunk index (block events are frequent). */
    public boolean managed(String world, BlockPos pos) {
        if (indexRevision != revision) {
            Map<Long, List<BuildingInstance>> index = new HashMap<>();
            for (BuildingInstance b : buildings.values()) {
                String w = ColonyEconomy.worldOf(b.island());
                for (int x = b.origin().getX() >> 4; x <= (b.origin().getX() + b.width() - 1) >> 4; x++)
                    for (int z = b.origin().getZ() >> 4; z <= (b.origin().getZ() + b.depth() - 1) >> 4; z++)
                        index.computeIfAbsent(tile(w, x, z), k -> new ArrayList<>()).add(b);
            }
            chunkIndex = index; indexRevision = revision;
        }
        for (BuildingInstance b : chunkIndex.getOrDefault(tile(world, pos.getX() >> 4, pos.getZ() >> 4), List.of())) if (b.contains(pos)) return true;
        return false;
    }
    public boolean newWorldOpen() { return shared().newWorldOpen(); }
    public void tickEconomy(double seconds) {
        simulated += Math.round(seconds);
        for (CompanyColony c : List.copyOf(colonies.values())) c.step(seconds, simulated);
        setDirty();
    }
    /** Free construction, for every company. */
    public void setSandbox(boolean value) { colonies.values().forEach(c -> c.economy.setSandbox(value)); setDirty(); }
    public void startCampaign() { startCampaign(shared()); }
    public void startCampaign(CompanyColony colony) { colony.campaign.start(colony); setDirty(); }
    public int progress(Campaign.Objective o) { return shared().progress(o); }

    public static ColonyData get(MinecraftServer server) {
        return CACHE.computeIfAbsent(server, s -> {
            // DimensionDataStorage catches load errors and falls back to a fresh save. Preflight outside it.
            Path file = s.getWorldPath(LevelResource.ROOT).resolve("data/" + FILE + ".dat");
            if (Files.exists(file)) {
                try { load(NbtIo.readCompressed(file.toFile()).getCompound("data")); }
                catch (IOException e) { throw new IllegalStateException("Cannot read AnnoCraft save; refusing to overwrite it", e); }
            }
            return s.overworld().getDataStorage().computeIfAbsent(ColonyData::load, ColonyData::new, FILE);
        });
    }
    public static void release(MinecraftServer server) { CACHE.remove(server); }
    public static void checkVersion(CompoundTag tag) {
        int version = tag.contains("version", Tag.TAG_INT) ? tag.getInt("version") : -1;
        if (version < 1 || version > VERSION)
            throw new IllegalStateException("Unsupported AnnoCraft save format " + version + "; expected 1 to " + VERSION);
    }
    public void initialize(IslandLayout layout) {
        CompoundTag existing = worlds.get(layout.world());
        if (existing != null) {
            if (existing.getLong("seed") != layout.seed() || existing.getInt("size") != layout.size()
                    || existing.getList("islands", Tag.TAG_COMPOUND).size() != layout.islands().size())
                throw new IllegalStateException("Archipelago settings changed in an existing world. Restore its datapack or create a new world.");
        } else {
            CompoundTag a = new CompoundTag(); a.putLong("seed", layout.seed()); a.putInt("size", layout.size()); a.putString("world", layout.world());
            ListTag islands = new ListTag();
            for (IslandLayout.Island island : layout.islands()) {
                CompoundTag t = new CompoundTag(); t.putString("id", island.id()); t.putInt("x", island.x()); t.putInt("z", island.z());
                t.putInt("radiusX", island.radiusX()); t.putInt("radiusZ", island.radiusZ()); t.putDouble("phase", island.phase());
                t.putString("fertility", island.fertility()); t.putString("deposit", island.deposit()); islands.add(t);
            }
            a.put("islands", islands); worlds.put(layout.world(), a); changed();
        }
        rebuildGeography();
        shared().diplomacy.claimHomes(geography);
    }
    private void rebuildGeography() {
        List<IslandLayout> layouts = new ArrayList<>();
        worlds.forEach((name, a) -> layouts.add(layout(a, name)));
        geography = Geography.of(layouts);
        Map<String, IslandLayout> byWorld = new HashMap<>(); layouts.forEach(l -> byWorld.put(l.world(), l));
        // Ships sail on the seed's sea (two blocks deep at least) and moor at the colonies' quays.
        navigation = byWorld.isEmpty() ? null : new Navigation() {
            private final Map<String, SeaRoutes.Water> seas = new HashMap<>();
            @Override public SeaRoutes.Water water(String world) {
                // One predicate per world, so the sea lanes' grid is computed once.
                return seas.computeIfAbsent(world, w -> { IslandLayout l = byWorld.get(w); return (x, z) -> l != null && l.inBounds(x, z) && l.height(x, z) < IslandLayout.SEA_LEVEL - 1; });
            }
            @Override public int half(String world) { IslandLayout l = byWorld.get(world); return l == null ? 2048 : l.size() / 2 - 16; }
            @Override public double[] dock(String island, int slot) {
                return Harbours.dock(buildings.values(), BuildingDefinitions::get, geography, island, slot);
            }
        };
    }
    private Navigation navigation;
    @Override public Navigation navigation() { return navigation; }
    public static IslandLayout layout(CompoundTag a, String world) {
        return new IslandLayout(a.getLong("seed"), a.getInt("size"), a.getList("islands", Tag.TAG_COMPOUND).size(), world);
    }
    public static ColonyData load(CompoundTag tag) {
        checkVersion(tag); ColonyData data = new ColonyData(); data.revision = tag.getLong("revision");
        int version = tag.getInt("version");
        CompanyColony shared = data.shared();
        if (tag.contains("archipelago")) data.worlds.put(IslandLayout.OLD_WORLD, tag.getCompound("archipelago").copy());
        if (tag.contains("new_world")) data.worlds.put(IslandLayout.NEW_WORLD, tag.getCompound("new_world").copy());
        for (Tag entry : tag.getList("buildings", Tag.TAG_COMPOUND)) {
            BuildingInstance b = BuildingInstance.fromTag((CompoundTag) entry);
            if (data.buildings.put(b.id(), b) != null) throw new IllegalStateException("Duplicate building ID in save");
        }
        if (version == 1) {
            // Version 1 colonies were built with an unlimited budget: keep them playable as they are.
            shared.economy.setSandbox(true);
            data.setDirty();
        } else {
            shared.economy = ColonyEconomy.load(tag.getCompound("economy"));
            for (Tag entry : tag.getList("roads", Tag.TAG_COMPOUND)) {
                CompoundTag r = (CompoundTag) entry;
                if (data.roads.put(r.getLong("pos"), r.getCompound("original").copy()) != null) throw new IllegalStateException("Duplicate road in save");
            }
        }
        if (version >= 3) {
            shared.diplomacy = Diplomacy.load(tag.getCompound("diplomacy"));
            shared.maritime = Maritime.load(tag.getCompound("maritime"));
            shared.campaign = Campaign.load(tag.getCompound("campaign"));
            CompoundTag d = tag.getCompound("decorations"); for (String island : d.getAllKeys()) data.decorations.put(island, d.getString(island));
            CompoundTag brands = tag.getCompound("companies");
            for (Tag c : brands.getList("companies", Tag.TAG_COMPOUND)) { Company company = Company.load((CompoundTag) c); data.companies.put(company.id(), company); }
            for (Tag m : brands.getList("members", Tag.TAG_COMPOUND)) { Company.Member member = Company.Member.load((CompoundTag) m); data.members.put(member.player(), member); }
            data.competitive = tag.getBoolean("competitive"); data.modeChosen = tag.getBoolean("mode_chosen") || !data.buildings.isEmpty();
            // The other companies' colonies share the archipelago's territory.
            for (Tag entry : tag.getList("colonies", Tag.TAG_COMPOUND)) {
                CompoundTag c = (CompoundTag) entry; String id = c.getString("id");
                CompanyColony colony = new CompanyColony(data, id, Diplomacy.load(c.getCompound("diplomacy"), data.territory(), id, false));
                colony.economy = ColonyEconomy.load(c.getCompound("economy")); colony.maritime = Maritime.load(c.getCompound("maritime")); colony.campaign = Campaign.load(c.getCompound("campaign"));
                data.colonies.put(id, colony);
            }
            CompoundTag p = tag.getCompound("pacts");
            for (String key : p.getAllKeys()) try { data.pacts.put(key, Diplomacy.Stance.valueOf(p.getString(key))); } catch (IllegalArgumentException ignored) { }
            CompoundTag o = tag.getCompound("proposals"); for (String key : o.getAllKeys()) data.proposals.put(key, o.getString(key));
        } else {
            // Earlier colonies own the islands they built storage on.
            for (BuildingInstance b : data.buildings.values()) if (b.definition().getPath().equals("trading_post") || b.definition().getPath().equals("warehouse")) shared.diplomacy.claim(b.island());
        }
        data.rebuildGeography();
        return data;
    }
    public CompoundTag snapshot(MinecraftServer server) { return snapshot(server, true); }
    public CompoundTag snapshot(MinecraftServer server, boolean definitions) { return snapshot(server, definitions, COLONY); }
    /** definitions include building definitions and structure previews (sent on join and datapack reload only); the economy is the given company's. */
    public CompoundTag snapshot(MinecraftServer server, boolean definitions, String company) {
        CompoundTag t = new CompoundTag(); t.putInt("version", VERSION); t.putLong("revision", revision);
        if (Boolean.getBoolean("annocraft1800.networkSmoke")) t.putBoolean("test_clients_ready", fr.annocraft.testing.NetworkGameTest.ready());
        CompoundTag w = new CompoundTag(); worlds.forEach((name, a) -> w.put(name, a.copy())); t.put("worlds", w);
        ListTag b = new ListTag(); buildings.values().forEach(v -> b.add(v.toTag(false))); t.put("buildings", b);
        ListTag defs = new ListTag();
        var level = server.getLevel(fr.annocraft.AnnoCraft.ARCHIPELAGO);
        if (definitions) BuildingDefinitions.all().forEach(v -> {
            CompoundTag definition = v.toTag();
            if (level != null) level.getStructureManager().get(v.structure()).ifPresent(template -> definition.put("preview", compactPreview(template.save(new CompoundTag()))));
            defs.add(definition);
        });
        if (definitions) t.put("definitions", defs);
        t.put("economy", economySnapshot(company));
        return t;
    }
    /** Non-air blocks only, packed as x | y << 5 | z << 10 | palette index << 15, to keep the snapshot small. */
    static CompoundTag compactPreview(CompoundTag template) {
        ListTag palette = template.getList("palette", Tag.TAG_COMPOUND), kept = new ListTag();
        Map<Integer, Integer> remap = new HashMap<>(); List<Integer> packed = new ArrayList<>();
        for (Tag entry : template.getList("blocks", Tag.TAG_COMPOUND)) {
            CompoundTag block = (CompoundTag) entry; int state = block.getInt("state");
            if (state < 0 || state >= palette.size() || palette.getCompound(state).getString("Name").equals("minecraft:air")) continue;
            ListTag pos = block.getList("pos", Tag.TAG_INT); if (pos.size() != 3) continue;
            int index = remap.computeIfAbsent(state, s -> { kept.add(palette.getCompound(s).copy()); return kept.size() - 1; });
            packed.add(pos.getInt(0) & 31 | (pos.getInt(1) & 31) << 5 | (pos.getInt(2) & 31) << 10 | index << 15);
        }
        CompoundTag t = new CompoundTag(); t.put("palette", kept); t.putIntArray("packed", packed.stream().mapToInt(Integer::intValue).toArray());
        return t;
    }
    public CompoundTag economySnapshot() { return economySnapshot(COLONY); }
    public CompoundTag economySnapshot(String company) { return colony(company).snapshot(); }
    /** Companies and members, for the save and for the clients. */
    CompoundTag brands() {
        CompoundTag t = new CompoundTag(); ListTag c = new ListTag(), m = new ListTag();
        companies.values().forEach(v -> c.add(v.save())); members.values().forEach(v -> m.add(v.save()));
        t.put("companies", c); t.put("members", m); return t;
    }
    @Override public CompoundTag save(CompoundTag tag) {
        CompanyColony shared = shared();
        tag.putInt("version", VERSION); tag.putLong("revision", revision);
        if (worlds.containsKey(IslandLayout.OLD_WORLD)) tag.put("archipelago", worlds.get(IslandLayout.OLD_WORLD).copy());
        if (worlds.containsKey(IslandLayout.NEW_WORLD)) tag.put("new_world", worlds.get(IslandLayout.NEW_WORLD).copy());
        ListTag entries = new ListTag(); buildings.values().forEach(b -> entries.add(b.toTag(true))); tag.put("buildings", entries);
        ListTag roadEntries = new ListTag();
        roads.forEach((pos, original) -> { CompoundTag r = new CompoundTag(); r.putLong("pos", pos); r.put("original", original.copy()); roadEntries.add(r); });
        tag.put("roads", roadEntries);
        tag.put("economy", shared.economy.save());
        tag.put("diplomacy", shared.diplomacy.save());
        tag.put("maritime", shared.maritime.save());
        tag.put("campaign", shared.campaign.save());
        CompoundTag d = new CompoundTag(); decorations.forEach(d::putString); tag.put("decorations", d);
        tag.put("companies", brands());
        tag.putBoolean("competitive", competitive); tag.putBoolean("mode_chosen", modeChosen);
        ListTag others = new ListTag(); colonies.values().forEach(c -> { if (c != shared) others.add(c.save()); }); tag.put("colonies", others);
        CompoundTag p = new CompoundTag(); pacts.forEach((k, v) -> p.putString(k, v.name())); tag.put("pacts", p);
        CompoundTag o = new CompoundTag(); proposals.forEach(o::putString); tag.put("proposals", o);
        return tag;
    }
}
