package fr.annocraft.building;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.*;
import net.minecraft.resources.ResourceLocation;
import java.util.UUID;

public record BuildingInstance(UUID id, ResourceLocation definition, BlockPos origin, int rotation,
                               String island, int width, int height, int depth, ListTag originalBlocks) {
    public boolean contains(BlockPos pos) {
        return pos.getX() >= origin.getX() && pos.getX() < origin.getX() + width &&
                pos.getZ() >= origin.getZ() && pos.getZ() < origin.getZ() + depth &&
                pos.getY() >= origin.getY() - 1 && pos.getY() < origin.getY() + height;
    }
    public boolean overlaps(BlockPos p, int w, int d) {
        return origin.getX() < p.getX() + w && origin.getX() + width > p.getX()
                && origin.getZ() < p.getZ() + d && origin.getZ() + depth > p.getZ();
    }
    public CompoundTag toTag(boolean backup) {
        CompoundTag t = new CompoundTag(); t.putUUID("id", id); t.putString("definition", definition.toString());
        t.putLong("origin", origin.asLong()); t.putInt("rotation", rotation); t.putString("island", island);
        t.putInt("width", width); t.putInt("height", height); t.putInt("depth", depth);
        if (backup) t.put("original", originalBlocks.copy());
        return t;
    }
    public static BuildingInstance fromTag(CompoundTag t) {
        return new BuildingInstance(t.getUUID("id"), new ResourceLocation(t.getString("definition")),
                BlockPos.of(t.getLong("origin")), t.getInt("rotation"), t.getString("island"),
                t.getInt("width"), t.getInt("height"), t.getInt("depth"), t.getList("original", Tag.TAG_COMPOUND));
    }
}
