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

public final class ColonyData extends SavedData {
    /** Version 2 adds the economy and roads. Version 1 saves are migrated on load. */
    public static final int VERSION = 2;
    private static final String FILE = "annocraft1800_colony";
    private static final Map<MinecraftServer, ColonyData> CACHE = new WeakHashMap<>();
    private final Map<UUID, BuildingInstance> buildings = new LinkedHashMap<>();
    /** Road ground block position to the original block state it replaced. */
    private final Map<Long, CompoundTag> roads = new LinkedHashMap<>();
    private Set<Long> roadTiles;
    private ColonyEconomy economy = new ColonyEconomy();
    private CompoundTag archipelago;
    private long revision;
    public Map<UUID, BuildingInstance> buildings() { return Collections.unmodifiableMap(buildings); }
    public Map<Long, CompoundTag> roads() { return Collections.unmodifiableMap(roads); }
    public ColonyEconomy economy() { return economy; }
    public long revision() { return revision; }
    public void put(BuildingInstance building) {
        boolean added = !buildings.containsKey(building.id());
        buildings.put(building.id(), building);
        if (added) economy.placed(site(building));
        changed();
    }
    public void remove(UUID id) { buildings.remove(id); economy.removed(id); changed(); }
    public void putRoad(BlockPos ground, CompoundTag original) { roads.put(ground.asLong(), original); roadTiles = null; changed(); }
    public void removeRoad(BlockPos ground) { roads.remove(ground.asLong()); roadTiles = null; changed(); }
    public boolean road(BlockPos ground) { return roads.containsKey(ground.asLong()); }
    public Set<Long> roadTiles() {
        if (roadTiles == null) {
            Set<Long> tiles = new HashSet<>();
            for (long pos : roads.keySet()) { BlockPos p = BlockPos.of(pos); tiles.add(ColonyEconomy.pack(p.getX(), p.getZ())); }
            roadTiles = tiles;
        }
        return roadTiles;
    }
    private void changed() { revision++; setDirty(); }
    public static ColonyEconomy.Site site(BuildingInstance b) {
        BuildingDefinition def = BuildingDefinitions.get(b.definition());
        return new ColonyEconomy.Site(b.id(), b.island(), b.origin().getX(), b.origin().getZ(), b.width(), b.depth(),
                def == null ? EconomyProfile.NONE : def.economy());
    }
    public List<ColonyEconomy.Site> sites() { return buildings.values().stream().map(ColonyData::site).toList(); }
    public void tickEconomy(double seconds) { economy.step(sites(), roadTiles(), revision, seconds); setDirty(); }
    public void setSandbox(boolean value) { economy.setSandbox(value); setDirty(); }
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
        if (version != 1 && version != VERSION)
            throw new IllegalStateException("Unsupported AnnoCraft save format " + version + "; expected 1 or " + VERSION);
    }
    public void initialize(IslandLayout layout) {
        if (archipelago != null) {
            if (archipelago.getLong("seed") != layout.seed() || archipelago.getInt("size") != layout.size()
                    || archipelago.getList("islands", Tag.TAG_COMPOUND).size() != layout.islands().size())
                throw new IllegalStateException("Archipelago settings changed in an existing world. Restore its datapack or create a new world.");
            return;
        }
        archipelago = new CompoundTag(); archipelago.putLong("seed", layout.seed()); archipelago.putInt("size", layout.size());
        ListTag islands = new ListTag();
        for (IslandLayout.Island island : layout.islands()) {
            CompoundTag t = new CompoundTag(); t.putString("id", island.id()); t.putInt("x", island.x()); t.putInt("z", island.z());
            t.putInt("radiusX", island.radiusX()); t.putInt("radiusZ", island.radiusZ()); t.putDouble("phase", island.phase());
            t.putString("fertility", island.fertility()); t.putString("deposit", island.deposit()); islands.add(t);
        }
        archipelago.put("islands", islands); changed();
    }
    public static ColonyData load(CompoundTag tag) {
        checkVersion(tag); ColonyData data = new ColonyData(); data.revision = tag.getLong("revision");
        data.archipelago = tag.getCompound("archipelago").copy();
        for (Tag entry : tag.getList("buildings", Tag.TAG_COMPOUND)) {
            BuildingInstance b = BuildingInstance.fromTag((CompoundTag) entry);
            if (data.buildings.put(b.id(), b) != null) throw new IllegalStateException("Duplicate building ID in save");
        }
        if (tag.getInt("version") == 1) {
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
        return data;
    }
    public CompoundTag snapshot(MinecraftServer server) {
        CompoundTag t = new CompoundTag(); t.putInt("version", VERSION); t.putLong("revision", revision);
        if (Boolean.getBoolean("annocraft1800.networkSmoke")) t.putBoolean("test_clients_ready", fr.annocraft.testing.NetworkGameTest.ready());
        if (archipelago != null) t.put("archipelago", archipelago.copy());
        ListTag b = new ListTag(); buildings.values().forEach(v -> b.add(v.toTag(false))); t.put("buildings", b);
        ListTag defs = new ListTag();
        var level = server.getLevel(fr.annocraft.AnnoCraft.ARCHIPELAGO);
        BuildingDefinitions.all().forEach(v -> {
            CompoundTag definition = v.toTag();
            if (level != null) level.getStructureManager().get(v.structure()).ifPresent(template -> definition.put("preview", template.save(new CompoundTag())));
            defs.add(definition);
        }); t.put("definitions", defs);
        t.put("economy", economySnapshot());
        return t;
    }
    public CompoundTag economySnapshot() { return economy.snapshot(sites()); }
    @Override public CompoundTag save(CompoundTag tag) {
        tag.putInt("version", VERSION); tag.putLong("revision", revision);
        if (archipelago != null) tag.put("archipelago", archipelago.copy());
        ListTag entries = new ListTag(); buildings.values().forEach(b -> entries.add(b.toTag(true))); tag.put("buildings", entries);
        ListTag roadEntries = new ListTag();
        roads.forEach((pos, original) -> { CompoundTag r = new CompoundTag(); r.putLong("pos", pos); r.put("original", original.copy()); roadEntries.add(r); });
        tag.put("roads", roadEntries);
        tag.put("economy", economy.save());
        return tag;
    }
}
