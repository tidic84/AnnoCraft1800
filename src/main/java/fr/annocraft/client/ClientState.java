package fr.annocraft.client;

import fr.annocraft.building.*;
import net.minecraft.client.Minecraft;
import net.minecraft.nbt.*;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import java.util.*;

public final class ClientState {
    public static final Map<ResourceLocation, BuildingDefinition> DEFINITIONS = new LinkedHashMap<>();
    public static final Map<UUID, BuildingInstance> BUILDINGS = new LinkedHashMap<>();
    public record PreviewBlock(net.minecraft.core.BlockPos position, net.minecraft.world.level.block.state.BlockState state) { }
    public static final Map<ResourceLocation, List<PreviewBlock>> PREVIEWS = new HashMap<>();
    public static int regionSize = 4096;
    public static CompoundTag archipelago = new CompoundTag();
    public static fr.annocraft.world.IslandLayout layout;
    public static String message = "";
    public static boolean messageSuccess;
    public static boolean networkTestReady;
    private static long revision = -1;
    public static UUID selected;
    public static void receive(CompoundTag tag) {
        if (tag == null || tag.getInt("version") != 1 || tag.getLong("revision") < revision) return;
        revision = tag.getLong("revision"); archipelago = tag.getCompound("archipelago").copy(); regionSize = archipelago.getInt("size");
        networkTestReady = tag.getBoolean("test_clients_ready");
        layout = new fr.annocraft.world.IslandLayout(archipelago.getLong("seed"), regionSize, archipelago.getList("islands", Tag.TAG_COMPOUND).size());
        DEFINITIONS.clear(); BUILDINGS.clear(); PREVIEWS.clear();
        for (Tag t : tag.getList("definitions", Tag.TAG_COMPOUND)) {
            CompoundTag definition = (CompoundTag)t; BuildingDefinition d = BuildingDefinition.fromTag(definition); DEFINITIONS.put(d.id(), d);
            CompoundTag preview = definition.getCompound("preview"); ListTag palette = preview.getList("palette", Tag.TAG_COMPOUND);
            List<PreviewBlock> blocks = new ArrayList<>(); Minecraft mc = Minecraft.getInstance();
            if (mc.level != null) for (Tag entry : preview.getList("blocks", Tag.TAG_COMPOUND)) {
                CompoundTag block = (CompoundTag)entry; int index = block.getInt("state"); if (index < 0 || index >= palette.size()) continue;
                var state = NbtUtils.readBlockState(mc.level.holderLookup(net.minecraft.core.registries.Registries.BLOCK), palette.getCompound(index));
                ListTag pos = block.getList("pos", Tag.TAG_INT);
                if (!state.isAir() && pos.size() == 3) blocks.add(new PreviewBlock(new net.minecraft.core.BlockPos(pos.getInt(0), pos.getInt(1), pos.getInt(2)), state));
            }
            PREVIEWS.put(d.id(), List.copyOf(blocks));
        }
        for (Tag t : tag.getList("buildings", Tag.TAG_COMPOUND)) { BuildingInstance b = BuildingInstance.fromTag((CompoundTag)t); BUILDINGS.put(b.id(), b); }
        if (selected != null && !BUILDINGS.containsKey(selected)) selected = null;
    }
    public static void feedback(boolean success, String key) {
        message = key; messageSuccess = success;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) mc.player.displayClientMessage(Component.translatable(key).withStyle(success ? net.minecraft.ChatFormatting.GREEN : net.minecraft.ChatFormatting.RED), true);
        if (success) RtsController.placement = null;
    }
    public static void clear() { DEFINITIONS.clear(); BUILDINGS.clear(); PREVIEWS.clear(); selected = null; revision = -1; archipelago = new CompoundTag(); regionSize = 4096; layout = null; message = ""; networkTestReady = false; }
}
