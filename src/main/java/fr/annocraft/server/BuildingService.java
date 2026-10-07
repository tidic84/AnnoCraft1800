package fr.annocraft.server;

import fr.annocraft.AnnoCraft;
import fr.annocraft.building.*;
import fr.annocraft.world.*;
import net.minecraft.core.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.templatesystem.*;
import java.util.*;

public final class BuildingService {
    private BuildingService() { }
    public record Result(boolean success, String message) {
        static Result ok() { return new Result(true, "message.annocraft1800.success"); }
        static Result fail(String key) { return new Result(false, "message.annocraft1800." + key); }
    }
    public static Result place(ServerPlayer player, ResourceLocation id, BlockPos origin, int rotation) {
        if (!allowed(player)) return Result.fail("wrong_region");
        BuildingDefinition def = BuildingDefinitions.get(id);
        if (def == null || def.level() != 1 || rotation < 0 || rotation > 3) return Result.fail("invalid_building");
        ServerLevel level = player.serverLevel(); ColonyData data = ColonyData.get(player.server);
        Result validation = validate(level, data, def, origin, rotation, null);
        if (!validation.success()) return validation;
        StructureTemplate template = template(level, def);
        if (template == null) return Result.fail("missing_structure");
        IslandLayout.Island isle = generator(level).layout().islandAt(origin.getX(), origin.getZ()).orElseThrow();
        String island = isle.id();
        Result rules = rules(data, def, isle, generator(level).layout().world());
        if (!rules.success()) return rules;
        String cost = data.economy().checkCost(island, def.economy());
        if (cost != null) return Result.fail(cost);
        ListTag backup = backup(level, origin, def.width(rotation), def.height(), def.depth(rotation));
        BuildingInstance instance = new BuildingInstance(UUID.randomUUID(), id, origin, rotation, island,
                def.width(rotation), def.height(), def.depth(rotation), backup);
        if (!placeTemplate(level, template, def, origin, rotation)) {
            restore(level, backup); return Result.fail("placement_failed");
        }
        data.economy().pay(island, def.economy());
        data.put(instance); CameraSessions.refresh(level, origin, instance.width(), instance.depth()); return Result.ok();
    }
    /** Economic placement rules: world, island ownership, founding order, fertility, deposits and unlocks. */
    public static Result rules(ColonyData data, BuildingDefinition def, IslandLayout.Island island, String world) {
        var e = def.economy(); var economy = data.economy();
        if (!e.buildableIn(world)) return Result.fail("wrong_world");
        if (!data.diplomacy().playerMayBuild(island.id())) return Result.fail("foreign_island");
        if (!economy.sandbox() && data.diplomacy().owner(island.id()) == null && !e.storageNode()) return Result.fail("needs_trading_post");
        if (e.fertility() != null && !island.hasFertility(e.fertility())) return Result.fail("no_fertility");
        if (e.deposit() != null && !island.hasDeposit(e.deposit())) return Result.fail("no_deposit");
        if (!economy.unlocked(e)) return Result.fail("locked");
        return Result.ok();
    }
    public static Result demolish(ServerPlayer player, UUID id) {
        if (!allowed(player)) return Result.fail("wrong_region");
        ColonyData data = ColonyData.get(player.server); BuildingInstance old = data.buildings().get(id);
        if (old == null || !loaded(player.serverLevel(), old.origin(), old.width(), old.depth())) return Result.fail("not_loaded");
        restore(player.serverLevel(), old.originalBlocks()); data.remove(id);
        // Half of the building materials are recovered into the island's storage.
        ColonyData.profile(old).cost().forEach((good, amount) -> { if (!good.equals(fr.annocraft.economy.EconomyProfile.COINS) && amount / 2 > 0) data.economy().store(old.island(), good, amount / 2); });
        CameraSessions.refresh(player.serverLevel(), old.origin(), old.width(), old.depth()); return Result.ok();
    }
    public static Result upgrade(ServerPlayer player, UUID id) {
        if (!allowed(player)) return Result.fail("wrong_region");
        ColonyData data = ColonyData.get(player.server); BuildingInstance old = data.buildings().get(id);
        if (old == null) return Result.fail("invalid_building");
        BuildingDefinition current = BuildingDefinitions.get(old.definition());
        BuildingDefinition next = current == null || current.upgrade() == null ? null : BuildingDefinitions.get(current.upgrade());
        if (next == null) return Result.fail("no_upgrade");
        if (next.width(old.rotation()) != old.width() || next.depth(old.rotation()) != old.depth()) return Result.fail("invalid_building");
        if (!data.economy().upgradeReady(old.id(), current.economy())) return Result.fail("upgrade_not_ready");
        String cost = data.economy().checkCost(old.island(), next.economy());
        if (cost != null) return Result.fail(cost);
        ServerLevel level = player.serverLevel();
        Result validation = validate(level, data, next, old.origin(), old.rotation(), old.id());
        if (!validation.success()) return validation;
        StructureTemplate template = template(level, next);
        if (template == null) return Result.fail("missing_structure");
        ListTag rollback = backup(level, old.origin(), old.width(), Math.max(old.height(), next.height()), old.depth());
        ListTag originals = old.originalBlocks().copy();
        for (int x = 0; x < old.width(); x++) for (int z = 0; z < old.depth(); z++)
            for (int y = old.height(); y < next.height(); y++) originals.add(blockBackup(level, old.origin().offset(x, y, z)));
        restore(level, old.originalBlocks());
        if (!placeTemplate(level, template, next, old.origin(), old.rotation())) {
            restore(level, rollback); return Result.fail("placement_failed");
        }
        data.economy().pay(old.island(), next.economy());
        data.put(new BuildingInstance(old.id(), next.id(), old.origin(), old.rotation(), old.island(), old.width(), next.height(), old.depth(), originals));
        CameraSessions.refresh(level, old.origin(), old.width(), old.depth());
        return Result.ok();
    }
    public static boolean allowed(ServerPlayer p) { return AnnoCraft.isColony(p.level().dimension()) && !p.isSpectator(); }
    public static ArchipelagoGenerator generator(ServerLevel level) { return (ArchipelagoGenerator) level.getChunkSource().getGenerator(); }
    public static Result validate(ServerLevel level, ColonyData data, BuildingDefinition def, BlockPos p, int rotation, UUID except) {
        int w = def.width(rotation), d = def.depth(rotation);
        IslandLayout layout = generator(level).layout();
        if (!layout.inBounds(p.getX(), p.getZ()) || !layout.inBounds(p.getX() + w - 1, p.getZ() + d - 1)
                || p.getY() < 1 || p.getY() + def.height() >= level.getMaxBuildHeight()) return Result.fail("outside_bounds");
        if (!loaded(level, p, w, d)) return Result.fail("not_loaded");
        for (BuildingInstance b : data.buildings().values())
            if (!b.id().equals(except) && fr.annocraft.economy.ColonyEconomy.worldOf(b.island()).equals(layout.world()) && b.overlaps(p, w, d)) return Result.fail("overlap");
        if (except == null) for (int x = 0; x < w; x++) for (int z = 0; z < d; z++)
            if (data.roadTiles().contains(ColonyData.tile(layout.world(), p.getX() + x, p.getZ() + z))) return Result.fail("overlap");
        Optional<IslandLayout.Island> candidate = layout.islandAt(p.getX(), p.getZ());
        if (candidate.isEmpty()) return Result.fail("invalid_terrain");
        IslandLayout.Island island = candidate.get();
        for (int x = 0; x < w; x++) for (int z = 0; z < d; z++) {
            int wx = p.getX() + x, wz = p.getZ() + z;
            if ((!def.coastal() && !island.buildable(wx, wz)) || layout.height(wx, wz) <= IslandLayout.SEA_LEVEL)
                return Result.fail("invalid_terrain");
            BlockPos ground = new BlockPos(wx, p.getY() - 1, wz);
            if (!level.getBlockState(ground).isSolidRender(level, ground) || !level.getFluidState(ground).isEmpty()) return Result.fail("invalid_terrain");
            BuildingInstance replacing = except == null ? null : data.buildings().get(except);
            for (int y = 0; y < def.height(); y++) {
                BlockPos q = p.offset(x, y, z);
                if (replacing != null && replacing.contains(q)) continue;
                if (!level.getBlockState(q).isAir()) return Result.fail("obstructed");
            }
        }
        if (def.coastal()) {
            boolean water = false;
            // Deterministic coast test, avoids generating neighboring chunks from a packet.
            for (int x = -16; x < w + 16 && !water; x++) for (int z = -16; z < d + 16; z++)
                if (layout.height(p.getX() + x, p.getZ() + z) < IslandLayout.SEA_LEVEL) { water = true; break; }
            if (!water) return Result.fail("needs_coast");
        }
        return Result.ok();
    }
    static boolean loaded(ServerLevel level, BlockPos pos, int w, int d) {
        for (int x = pos.getX() >> 4; x <= (pos.getX() + w - 1) >> 4; x++)
            for (int z = pos.getZ() >> 4; z <= (pos.getZ() + d - 1) >> 4; z++) if (!level.hasChunk(x, z)) return false;
        return true;
    }
    private static StructureTemplate template(ServerLevel level, BuildingDefinition def) {
        StructureTemplate t = level.getStructureManager().get(def.structure()).orElse(null);
        return t != null && t.getSize().equals(new Vec3i(def.width(), def.height(), def.depth())) ? t : null;
    }
    private static boolean placeTemplate(ServerLevel level, StructureTemplate t, BuildingDefinition def, BlockPos p, int turn) {
        Rotation rotation = Rotation.values()[turn];
        BlockPos offset = switch (turn) {
            case 1 -> new BlockPos(def.depth() - 1, 0, 0);
            case 2 -> new BlockPos(def.width() - 1, 0, def.depth() - 1);
            case 3 -> new BlockPos(0, 0, def.width() - 1);
            default -> BlockPos.ZERO;
        };
        return t.placeInWorld(level, p.offset(offset), p.offset(offset), new StructurePlaceSettings().setRotation(rotation).setIgnoreEntities(true), level.random, 2);
    }
    private static ListTag backup(ServerLevel level, BlockPos p, int w, int h, int d) {
        ListTag result = new ListTag();
        for (int x = 0; x < w; x++) for (int z = 0; z < d; z++) for (int y = 0; y < h; y++) result.add(blockBackup(level, p.offset(x, y, z)));
        return result;
    }
    static CompoundTag blockBackup(ServerLevel level, BlockPos p) {
        CompoundTag t = new CompoundTag(); t.putLong("pos", p.asLong()); t.put("state", NbtUtils.writeBlockState(level.getBlockState(p))); return t;
    }
    static void restore(ServerLevel level, ListTag backup) {
        for (Tag entry : backup) {
            CompoundTag t = (CompoundTag) entry;
            level.setBlock(BlockPos.of(t.getLong("pos")), NbtUtils.readBlockState(level.holderLookup(Registries.BLOCK), t.getCompound("state")), 2);
        }
    }
}
