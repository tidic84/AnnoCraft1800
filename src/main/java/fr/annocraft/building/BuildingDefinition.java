package fr.annocraft.building;

import com.google.gson.*;
import fr.annocraft.economy.EconomyProfile;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;

public record BuildingDefinition(ResourceLocation id, String name, ResourceLocation structure,
                                 int width, int height, int depth, boolean coastal,
                                 ResourceLocation upgrade, int level, EconomyProfile economy) {
    public static BuildingDefinition parse(ResourceLocation id, JsonObject json) {
        int w = json.get("width").getAsInt(), h = json.get("height").getAsInt(), d = json.get("depth").getAsInt();
        if (w < 1 || d < 1 || h < 1 || w > 32 || d > 32 || h > 32) throw new IllegalArgumentException("Invalid dimensions: " + id);
        EconomyProfile economy;
        try { economy = EconomyProfile.parse(json.has("economy") ? json.getAsJsonObject("economy") : null); }
        catch (RuntimeException e) { throw new IllegalArgumentException("Invalid economy for " + id + ": " + e.getMessage(), e); }
        return new BuildingDefinition(id, json.get("name").getAsString(), new ResourceLocation(json.get("structure").getAsString()),
                w, h, d, json.has("coastal") && json.get("coastal").getAsBoolean(),
                json.has("upgrade") ? new ResourceLocation(json.get("upgrade").getAsString()) : null,
                json.get("level").getAsInt(), economy);
    }
    public int width(int turn) { return turn % 2 == 0 ? width : depth; }
    public int depth(int turn) { return turn % 2 == 0 ? depth : width; }
    public CompoundTag toTag() {
        CompoundTag t = new CompoundTag();
        t.putString("id", id.toString()); t.putString("name", name); t.putString("structure", structure.toString());
        t.putInt("width", width); t.putInt("height", height); t.putInt("depth", depth);
        t.putBoolean("coastal", coastal); t.putInt("level", level);
        if (upgrade != null) t.putString("upgrade", upgrade.toString());
        t.putString("economy", economy.toJson().toString());
        return t;
    }
    public static BuildingDefinition fromTag(CompoundTag t) {
        return new BuildingDefinition(new ResourceLocation(t.getString("id")), t.getString("name"), new ResourceLocation(t.getString("structure")),
                t.getInt("width"), t.getInt("height"), t.getInt("depth"), t.getBoolean("coastal"),
                t.contains("upgrade") ? new ResourceLocation(t.getString("upgrade")) : null, t.getInt("level"),
                t.contains("economy") ? EconomyProfile.parse(JsonParser.parseString(t.getString("economy")).getAsJsonObject()) : EconomyProfile.NONE);
    }
}
