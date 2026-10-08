package fr.annocraft.client;

import fr.annocraft.AnnoCraft;
import fr.annocraft.building.*;
import fr.annocraft.economy.ColonyEconomy;
import fr.annocraft.world.IslandLayout;
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
    /** Both worlds' island layouts, rebuilt from their seeds. */
    public static final Map<String, IslandLayout> LAYOUTS = new HashMap<>();
    public static int regionSize = 4096;
    public static IslandLayout layout;
    public static String world = IslandLayout.OLD_WORLD;
    public static String message = "";
    public static boolean messageSuccess;
    public static boolean networkTestReady;
    private static long revision = -1;
    /** Revision of the last snapshot: changes when buildings are placed, upgraded or demolished. */
    public static long revision() { return revision; }
    public static UUID selected;
    public static void receive(CompoundTag tag) {
        if (tag == null || tag.getInt("version") != fr.annocraft.server.ColonyData.VERSION || tag.getLong("revision") < revision) return;
        revision = tag.getLong("revision");
        networkTestReady = tag.getBoolean("test_clients_ready");
        LAYOUTS.clear();
        CompoundTag worlds = tag.getCompound("worlds");
        for (String name : worlds.getAllKeys()) LAYOUTS.put(name, fr.annocraft.server.ColonyData.layout(worlds.getCompound(name), name));
        updateWorld();
        BUILDINGS.clear();
        if (tag.contains("definitions")) { DEFINITIONS.clear(); PREVIEWS.clear(); }
        for (Tag t : tag.getList("definitions", Tag.TAG_COMPOUND)) {
            CompoundTag definition = (CompoundTag)t; BuildingDefinition d = BuildingDefinition.fromTag(definition); DEFINITIONS.put(d.id(), d);
            CompoundTag preview = definition.getCompound("preview"); ListTag palette = preview.getList("palette", Tag.TAG_COMPOUND);
            List<PreviewBlock> blocks = new ArrayList<>(); Minecraft mc = Minecraft.getInstance();
            if (mc.level != null) for (int packed : preview.getIntArray("packed")) {
                int index = packed >>> 15; if (index >= palette.size()) continue;
                var state = NbtUtils.readBlockState(mc.level.holderLookup(net.minecraft.core.registries.Registries.BLOCK), palette.getCompound(index));
                if (!state.isAir()) blocks.add(new PreviewBlock(new net.minecraft.core.BlockPos(packed & 31, packed >> 5 & 31, packed >> 10 & 31), state));
            }
            PREVIEWS.put(d.id(), List.copyOf(blocks));
        }
        for (Tag t : tag.getList("buildings", Tag.TAG_COMPOUND)) { BuildingInstance b = BuildingInstance.fromTag((CompoundTag)t); BUILDINGS.put(b.id(), b); }
        receiveEconomy(tag.getCompound("economy"));
        if (selected != null && !BUILDINGS.containsKey(selected)) selected = null;
    }
    /** Picks the layout of the dimension the player is in. */
    public static void updateWorld() {
        Minecraft mc = Minecraft.getInstance();
        world = mc.level == null ? IslandLayout.OLD_WORLD : AnnoCraft.worldName(mc.level.dimension());
        layout = LAYOUTS.get(world);
        regionSize = layout == null ? 4096 : layout.size();
    }
    /** Buildings of the world the player is currently in. */
    public static Collection<BuildingInstance> visible() {
        return BUILDINGS.values().stream().filter(b -> ColonyEconomy.worldOf(b.island()).equals(world)).toList();
    }
    public static CompoundTag economy = new CompoundTag();
    /** When the last economy snapshot arrived, to extrapolate ship voyages smoothly. */
    public static long economyTime = System.nanoTime();
    public static final Map<UUID, CompoundTag> SITES = new HashMap<>();
    public static void receiveEconomy(CompoundTag tag) {
        if (tag == null) return;
        economyTime = System.nanoTime();
        economy = tag; SITES.clear();
        for (Tag t : tag.getList("sites", Tag.TAG_COMPOUND)) { CompoundTag s = (CompoundTag) t; SITES.put(s.getUUID("id"), s); }
    }
    public static CompoundTag island(String id) { return economy.getCompound("islands").getCompound(id); }
    public static List<String> ports() {
        List<String> result = new ArrayList<>(); economy.getList("ports", Tag.TAG_STRING).forEach(t -> result.add(t.getAsString())); return result;
    }
    /** Companies and players of the snapshot. */
    public static fr.annocraft.economy.Company company(String id) {
        for (Tag t : economy.getCompound("companies").getList("companies", Tag.TAG_COMPOUND)) if (((CompoundTag) t).getString("id").equals(id)) return fr.annocraft.economy.Company.load((CompoundTag) t);
        return null;
    }
    public static List<fr.annocraft.economy.Company.Member> members() {
        List<fr.annocraft.economy.Company.Member> list = new ArrayList<>();
        for (Tag t : economy.getCompound("companies").getList("members", Tag.TAG_COMPOUND)) list.add(fr.annocraft.economy.Company.Member.load((CompoundTag) t));
        return list;
    }
    public static fr.annocraft.economy.Company.Member me() {
        Minecraft mc = Minecraft.getInstance(); if (mc.player == null) return null;
        return members().stream().filter(m -> m.player().equals(mc.player.getUUID())).findFirst().orElse(null);
    }
    /** The player's own company: its look on the map, the sails and the interface. */
    public static fr.annocraft.economy.Company myCompany() { return company(myCompanyId()); }
    /** The company this player manages (the shared colony in a cooperative game). */
    public static String myCompanyId() { String c = economy.getString("company"); return c.isEmpty() ? fr.annocraft.economy.Diplomacy.PLAYER : c; }
    /** Whether an island owner (or a ship's) is this player's company. */
    public static boolean mine(String owner) { return owner != null && owner.equals(myCompanyId()); }
    /** Whether an owner is a company (a player's) rather than a faction. */
    public static boolean isCompany(String owner) { return owner != null && !owner.isEmpty() && fr.annocraft.economy.Diplomacy.type(owner) == null; }
    /** Display name of an owner: a company's chosen name or a faction's. */
    public static Component ownerName(String owner) {
        if (isCompany(owner)) { var c = company(owner); return Component.literal(c == null ? owner : c.name()); }
        return Component.translatable("faction.annocraft1800." + owner);
    }
    public static boolean competitive() { return economy.getBoolean("competitive"); }
    public static int myColor() { var c = myCompany(); return c == null ? 0x2f6fd0 : c.color(); }
    public static int colorOf(String company) { var c = company(company); return c == null ? 0x2f6fd0 : c.color(); }
    public static String owner(String island) { return economy.getCompound("diplomacy").getCompound("owners").getString(island); }
    public static void feedback(boolean success, String key) {
        message = key; messageSuccess = success;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) mc.player.displayClientMessage(Component.translatable(key).withStyle(success ? net.minecraft.ChatFormatting.GREEN : net.minecraft.ChatFormatting.RED), true);
    }
    public static void clear() { economy = new CompoundTag(); SITES.clear(); DEFINITIONS.clear(); BUILDINGS.clear(); PREVIEWS.clear(); LAYOUTS.clear(); selected = null; revision = -1; regionSize = 4096; layout = null; message = ""; networkTestReady = false; }
}
