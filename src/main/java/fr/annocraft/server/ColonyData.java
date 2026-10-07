package fr.annocraft.server;

import fr.annocraft.building.*;
import fr.annocraft.economy.*;
import fr.annocraft.world.IslandLayout;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.storage.LevelResource;
import java.io.*;
import java.nio.file.*;
import java.util.*;

public final class ColonyData extends SavedData implements Colony {
    /** Version 2 added the economy and roads; version 3 adds both worlds, ships, diplomacy and the campaign. */
    public static final int VERSION = 3;
    private static final String FILE = "annocraft1800_colony";
    private static final Map<MinecraftServer, ColonyData> CACHE = new WeakHashMap<>();
    private final Map<UUID, BuildingInstance> buildings = new LinkedHashMap<>();
    /** Road ground block position to the original block state it replaced. */
    private final Map<Long, CompoundTag> roads = new LinkedHashMap<>();
    private Set<Long> roadTiles;
    private ColonyEconomy economy = new ColonyEconomy();
    private Diplomacy diplomacy = new Diplomacy();
    private Maritime maritime = new Maritime();
    private Campaign campaign = new Campaign();
    /** World name ("old", "new") to its archipelago settings. */
    private final Map<String, CompoundTag> worlds = new TreeMap<>();
    private Geography geography = Geography.of(List.of());
    private final List<Event> events = new ArrayList<>();
    /** Faction islands whose visible settlement was built ("built") or removed after conquest ("cleared"). */
    private final Map<String, String> decorations = new TreeMap<>();
    private long revision;
    private long portsRevision = -1;
    private Set<String> ports = Set.of();
    public Map<UUID, BuildingInstance> buildings() { return Collections.unmodifiableMap(buildings); }
    public Map<Long, CompoundTag> roads() { return Collections.unmodifiableMap(roads); }
    @Override public ColonyEconomy economy() { return economy; }
    @Override public Diplomacy diplomacy() { return diplomacy; }
    @Override public Maritime maritime() { return maritime; }
    @Override public Geography geography() { return geography; }
    public Campaign campaign() { return campaign; }
    public long revision() { return revision; }
    public void put(BuildingInstance building) {
        boolean added = !buildings.containsKey(building.id());
        buildings.put(building.id(), building);
        if (added) {
            ColonyEconomy.Site site = site(building);
            economy.placed(site);
            if (site.profile().storageNode()) diplomacy.claim(building.island());
        }
        changed();
    }
    public void remove(UUID id) { buildings.remove(id); economy.removed(id); changed(); }
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
    public List<ColonyEconomy.Site> sites() { return buildings.values().stream().map(ColonyData::site).toList(); }

    @Override public Set<String> ports() {
        if (portsRevision != revision) {
            Set<String> result = new TreeSet<>();
            for (BuildingInstance b : buildings.values())
                if (profile(b).storageNode() && diplomacy.playerMayBuild(b.island())) result.add(b.island());
            ports = Collections.unmodifiableSet(result); portsRevision = revision;
        }
        return ports;
    }
    @Override public boolean hasShipyard(String island) {
        return island != null && buildings.values().stream().anyMatch(b -> b.island().equals(island) && profile(b).shipyard());
    }
    @Override public int defense(String island) {
        return buildings.values().stream().filter(b -> b.island().equals(island)).mapToInt(b -> profile(b).defense()).sum();
    }
    @Override public void event(Event event) { events.add(event); }
    public boolean decorated(String island) { return decorations.containsKey(island); }
    public String decoration(String island) { return decorations.get(island); }
    public void markDecorated(String island) { decorations.put(island, "built"); setDirty(); }
    public void markCleared(String island) { decorations.put(island, "cleared"); setDirty(); }
    public List<Event> drainEvents() { List<Event> out = List.copyOf(events); events.clear(); return out; }
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
    public boolean newWorldOpen() {
        return economy.sandbox() || campaign.newWorldUnlocked() || economy.population("artisans") > 0
                || ports().stream().anyMatch(i -> IslandLayout.NEW_WORLD.equals(geography.world(i)));
    }

    public void tickEconomy(double seconds) {
        maritime.step(this, seconds);
        economy.step(sites(), roadTiles(), revision, seconds);
        diplomacy.step(this, seconds);
        campaign.step(this, this::progress);
        setDirty();
    }
    public void setSandbox(boolean value) { economy.setSandbox(value); setDirty(); }
    public void startCampaign() { campaign.start(this); setDirty(); }
    /** Current value of a campaign objective. */
    public int progress(Campaign.Objective o) {
        String t = o.target();
        return switch (o.type()) {
            case "residents" -> economy.population(t);
            case "population" -> economy.totalPopulation();
            case "buildings" -> (int) buildings.values().stream().filter(b -> b.definition().getPath().equals(t)).count();
            case "islands" -> ports().size();
            case "world_islands" -> (int) ports().stream().filter(i -> t.equals(geography.world(i))).count();
            case "ships" -> maritime.ships().size();
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
        diplomacy.claimHomes(geography);
    }
    private void rebuildGeography() {
        List<IslandLayout> layouts = new ArrayList<>();
        worlds.forEach((name, a) -> layouts.add(layout(a, name)));
        geography = Geography.of(layouts);
        portsRevision = -1;
    }
    public static IslandLayout layout(CompoundTag a, String world) {
        return new IslandLayout(a.getLong("seed"), a.getInt("size"), a.getList("islands", Tag.TAG_COMPOUND).size(), world);
    }
    public static ColonyData load(CompoundTag tag) {
        checkVersion(tag); ColonyData data = new ColonyData(); data.revision = tag.getLong("revision");
        int version = tag.getInt("version");
        if (tag.contains("archipelago")) data.worlds.put(IslandLayout.OLD_WORLD, tag.getCompound("archipelago").copy());
        if (tag.contains("new_world")) data.worlds.put(IslandLayout.NEW_WORLD, tag.getCompound("new_world").copy());
        for (Tag entry : tag.getList("buildings", Tag.TAG_COMPOUND)) {
            BuildingInstance b = BuildingInstance.fromTag((CompoundTag) entry);
            if (data.buildings.put(b.id(), b) != null) throw new IllegalStateException("Duplicate building ID in save");
        }
        if (version == 1) {
            // Version 1 colonies were built with an unlimited budget: keep them playable as they are.
            data.economy.setSandbox(true);
            data.setDirty();
        } else {
            data.economy = ColonyEconomy.load(tag.getCompound("economy"));
            for (Tag entry : tag.getList("roads", Tag.TAG_COMPOUND)) {
                CompoundTag r = (CompoundTag) entry;
                if (data.roads.put(r.getLong("pos"), r.getCompound("original").copy()) != null) throw new IllegalStateException("Duplicate road in save");
            }
        }
        if (version >= 3) {
            data.diplomacy = Diplomacy.load(tag.getCompound("diplomacy"));
            data.maritime = Maritime.load(tag.getCompound("maritime"));
            data.campaign = Campaign.load(tag.getCompound("campaign"));
            CompoundTag d = tag.getCompound("decorations"); for (String island : d.getAllKeys()) data.decorations.put(island, d.getString(island));
        } else {
            // Earlier colonies own the islands they built storage on.
            for (BuildingInstance b : data.buildings.values()) if (b.definition().getPath().equals("trading_post") || b.definition().getPath().equals("warehouse")) data.diplomacy.claim(b.island());
        }
        data.rebuildGeography();
        return data;
    }
    public CompoundTag snapshot(MinecraftServer server) { return snapshot(server, true); }
    /**  definitions include building definitions and structure previews (sent on join and datapack reload only) */
    public CompoundTag snapshot(MinecraftServer server, boolean definitions) {
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
        t.put("economy", economySnapshot());
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
    public CompoundTag economySnapshot() {
        CompoundTag t = economy.snapshot(sites());
        t.put("maritime", maritime.snapshot());
        t.put("diplomacy", diplomacy.snapshot());
        t.put("campaign", campaign.snapshot(this::progress));
        ListTag p = new ListTag(); ports().forEach(i -> p.add(StringTag.valueOf(i))); t.put("ports", p);
        t.putBoolean("new_world_open", newWorldOpen());
        return t;
    }
    @Override public CompoundTag save(CompoundTag tag) {
        tag.putInt("version", VERSION); tag.putLong("revision", revision);
        if (worlds.containsKey(IslandLayout.OLD_WORLD)) tag.put("archipelago", worlds.get(IslandLayout.OLD_WORLD).copy());
        if (worlds.containsKey(IslandLayout.NEW_WORLD)) tag.put("new_world", worlds.get(IslandLayout.NEW_WORLD).copy());
        ListTag entries = new ListTag(); buildings.values().forEach(b -> entries.add(b.toTag(true))); tag.put("buildings", entries);
        ListTag roadEntries = new ListTag();
        roads.forEach((pos, original) -> { CompoundTag r = new CompoundTag(); r.putLong("pos", pos); r.put("original", original.copy()); roadEntries.add(r); });
        tag.put("roads", roadEntries);
        tag.put("economy", economy.save());
        tag.put("diplomacy", diplomacy.save());
        tag.put("maritime", maritime.save());
        tag.put("campaign", campaign.save());
        CompoundTag d = new CompoundTag(); decorations.forEach(d::putString); tag.put("decorations", d);
        return tag;
    }
}
