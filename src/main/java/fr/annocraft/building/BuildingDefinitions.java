package fr.annocraft.building;

import com.google.gson.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.*;
import net.minecraft.util.profiling.ProfilerFiller;
import java.util.*;

public final class BuildingDefinitions extends SimpleJsonResourceReloadListener {
    private static volatile Map<ResourceLocation, BuildingDefinition> definitions = Map.of();
    public BuildingDefinitions() { super(new Gson(), "annocraft_buildings"); }
    @Override protected void apply(Map<ResourceLocation, JsonElement> json, ResourceManager resources, ProfilerFiller profiler) {
        Map<ResourceLocation, BuildingDefinition> next = new TreeMap<>();
        json.forEach((id, value) -> next.put(id, BuildingDefinition.parse(id, value.getAsJsonObject())));
        if (next.isEmpty()) throw new IllegalStateException("No AnnoCraft building definitions");
        for (BuildingDefinition def : next.values()) {
            if (def.level() < 1) throw new IllegalStateException("Invalid level: " + def.id());
            if (def.upgrade() != null) {
                BuildingDefinition target = next.get(def.upgrade());
                if (target == null || target.level() <= def.level() || target.width() != def.width() || target.depth() != def.depth())
                    throw new IllegalStateException("Invalid upgrade: " + def.id());
            }
        }
        definitions = Collections.unmodifiableMap(next);
    }
    public static Collection<BuildingDefinition> all() { return definitions.values(); }
    public static BuildingDefinition get(ResourceLocation id) { return definitions.get(id); }
}
