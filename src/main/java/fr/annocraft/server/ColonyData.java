package fr.annocraft.server;

import fr.annocraft.building.*;
import fr.annocraft.world.IslandLayout;
import net.minecraft.nbt.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.storage.LevelResource;
import java.io.*;
import java.nio.file.*;
import java.util.*;

public final class ColonyData extends SavedData {
    public static final int VERSION = 1;
    private static final String FILE = "annocraft1800_colony";
    private static final Map<MinecraftServer, ColonyData> CACHE = new WeakHashMap<>();
    private final Map<UUID, BuildingInstance> buildings = new LinkedHashMap<>();
    private CompoundTag archipelago;
    private long revision;
    public Map<UUID, BuildingInstance> buildings() { return Collections.unmodifiableMap(buildings); }
    public long revision() { return revision; }
    public void put(BuildingInstance building) { buildings.put(building.id(), building); changed(); }
    public void remove(UUID id) { buildings.remove(id); changed(); }
    private void changed() { revision++; setDirty(); }
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
        if (!tag.contains("version", Tag.TAG_INT) || tag.getInt("version") != VERSION)
            throw new IllegalStateException("Unsupported AnnoCraft save format " + tag.getInt("version") + "; expected " + VERSION);
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
        return t;
    }
    @Override public CompoundTag save(CompoundTag tag) {
        tag.putInt("version", VERSION); tag.putLong("revision", revision);
        if (archipelago != null) tag.put("archipelago", archipelago.copy());
        ListTag entries = new ListTag(); buildings.values().forEach(b -> entries.add(b.toTag(true))); tag.put("buildings", entries);
        return tag;
    }
}
