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
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.templatesystem.*;
import java.util.*;

public final class BuildingService {
    private BuildingService() { }
    public record Result(boolean success, String message) {
        static Result ok() { return new Result(true, "message.annocraft1800.success"); }
        static Result fail(String key) { return new Result(false, "message.annocraft1800." + key); }
    }
    /**
     * Places a building. {@code origin} is the first air block above the ground at the footprint's corner: the
     * template's bottom layer (foundations, yards, fields) replaces the ground itself, one block lower.
     */
    public static Result place(ServerPlayer player, ResourceLocation id, BlockPos origin, int rotation) {
        if (!allowed(player)) return Result.fail("wrong_region");
        BuildingDefinition def = BuildingDefinitions.get(id);
        if (def == null || def.level() != 1 || rotation < 0 || rotation > 3) return Result.fail("invalid_building");
        ServerLevel level = player.serverLevel(); ColonyData data = ColonyData.get(player.server);
        IslandLayout layout = generator(level).layout();
        // A coastal deck lies at the height of the shore under its front row, wherever the cursor touched the water.
        BlockPos base = def.coastal() ? new BlockPos(origin.getX(), Siting.deck(layout, def, origin.getX(), origin.getZ(), rotation), origin.getZ()) : origin.below();
        Result validation = validate(level, data, def, base, rotation, null);
        if (!validation.success()) return validation;
        StructureTemplate template = template(level, def);
        if (template == null) return Result.fail("missing_structure");
        IslandLayout.Island isle = islandOf(layout, def, base, rotation).orElseThrow();
        String island = isle.id();
        CompanyColony colony = data.colony(player);
        Result rules = rules(colony, def, isle, layout.world());
        if (!rules.success()) return rules;
        int w = def.width(rotation), d = def.depth(rotation);
        if (def.economy().deposit() != null && !layout.depositIn(def.economy().deposit(), base.getX(), base.getZ(), w, d)) return Result.fail("no_deposit_site");
        String cost = colony.economy().checkCost(island, def.economy());
        if (cost != null) return Result.fail(cost);
        // As in Anno, construction fells the trees and clears the plants and loose rock in its way.
        for (int x = 0; x < w; x++) for (int z = 0; z < d; z++) for (int y = 1; y < def.height(); y++) Nature.clear(level, base.offset(x, y, z));
        ListTag backup = backup(level, base, w, def.height(), d);
        List<BlockPos> under = under(layout, base, w, d);
        for (BlockPos q : under) backup.add(blockBackup(level, q));
        BuildingInstance instance = new BuildingInstance(UUID.randomUUID(), id, base, rotation, island, w, def.height(), d, backup);
        if (!placeTemplate(level, template, def, base, rotation)) {
            restore(level, backup); return Result.fail("placement_failed");
        }
        if (def.coastal()) { shore(level, layout, base, w, d, backup); support(level, base, w, d, under); }
        Wear.clean(level, base.getX(), base.getZ(), w, d, base.getY());
        colony.economy().pay(island, def.economy());
        data.put(instance, colony);
        ConstructionAnimator.start(level, instance.id(), base, instance.width(), instance.height(), instance.depth());
        CameraSessions.refresh(level, base, instance.width(), instance.depth()); return Result.ok();
    }
    /** Economic placement rules: world, island ownership, founding order, fertility, deposits and unlocks. */
    public static Result rules(fr.annocraft.economy.Colony colony, BuildingDefinition def, IslandLayout.Island island, String world) {
        var e = def.economy(); var economy = colony.economy();
        if (!e.buildableIn(world)) return Result.fail("wrong_world");
        if (!colony.diplomacy().playerMayBuild(island.id())) return Result.fail("foreign_island");
        if (!economy.sandbox() && colony.diplomacy().owner(island.id()) == null && !e.storageNode()) return Result.fail("needs_trading_post");
        if (e.fertility() != null && !island.hasFertility(e.fertility())) return Result.fail("no_fertility");
        if (e.deposit() != null && !island.hasDeposit(e.deposit())) return Result.fail("no_deposit");
        if (!economy.unlocked(e)) return Result.fail("locked");
        return Result.ok();
    }
    public static Result demolish(ServerPlayer player, UUID id) {
        if (!allowed(player)) return Result.fail("wrong_region");
        ColonyData data = ColonyData.get(player.server); BuildingInstance old = data.buildings().get(id);
        if (old != null && data.colonyOf(old) != data.colony(player)) return Result.fail("foreign_island");
        if (old == null || !loaded(player.serverLevel(), old.origin(), old.width(), old.depth())) return Result.fail("not_loaded");
        ConstructionAnimator.cancel(id);
        ConstructionAnimator.dust(player.serverLevel(), old.origin().offset(old.width() / 2, 1, old.depth() / 2), player.serverLevel().getBlockState(old.origin()), 40, old.width() / 2.0);
        restore(player.serverLevel(), old.originalBlocks()); data.remove(id);
        // Half of the building materials are recovered into the island's storage.
        CompanyColony owner = data.colonyOf(old);
        ColonyData.profile(old).cost().forEach((good, amount) -> { if (!good.equals(fr.annocraft.economy.EconomyProfile.COINS) && amount / 2 > 0) owner.economy().store(old.island(), good, amount / 2); });
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
        CompanyColony colony = data.colonyOf(old);
        if (colony != data.colony(player)) return Result.fail("foreign_island");
        if (!colony.economy().upgradeReady(old.id(), current.economy())) return Result.fail("upgrade_not_ready");
        String cost = colony.economy().checkCost(old.island(), next.economy());
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
        ConstructionAnimator.cancel(old.id());
        restore(level, old.originalBlocks());
        if (!placeTemplate(level, template, next, old.origin(), old.rotation())) {
            restore(level, rollback); return Result.fail("placement_failed");
        }
        colony.economy().pay(old.island(), next.economy());
        data.put(new BuildingInstance(old.id(), next.id(), old.origin(), old.rotation(), old.island(), old.width(), next.height(), old.depth(), originals));
        ConstructionAnimator.start(level, old.id(), old.origin(), old.width(), next.height(), old.depth());
        CameraSessions.refresh(level, old.origin(), old.width(), old.depth());
        return Result.ok();
    }
    public static boolean allowed(ServerPlayer p) { return AnnoCraft.isColony(p.level().dimension()) && !p.isSpectator(); }
    public static ArchipelagoGenerator generator(ServerLevel level) { return (ArchipelagoGenerator) level.getChunkSource().getGenerator(); }
    /**
     * Site checks for a building whose bottom layer lies at {@code p}: every bottom cell must be solid island ground,
     * everything above it free, apart from nature that construction clears.
     */
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
        Optional<IslandLayout.Island> candidate = islandOf(layout, def, p, rotation);
        if (candidate.isEmpty()) return Result.fail("invalid_terrain");
        IslandLayout.Island island = candidate.get();
        if (def.coastal()) { String coast = Siting.coast(layout, def, p.getX(), p.getY(), p.getZ(), rotation); if (coast != null) return Result.fail(coast); }
        BuildingInstance replacing = except == null ? null : data.buildings().get(except);
        for (int x = 0; x < w; x++) for (int z = 0; z < d; z++) {
            int wx = p.getX() + x, wz = p.getZ() + z;
            if (!def.coastal() && (!island.buildable(wx, wz) || layout.height(wx, wz) <= IslandLayout.SEA_LEVEL))
                return Result.fail("invalid_terrain");
            BlockPos ground = new BlockPos(wx, p.getY(), wz);
            // Over the shore and the sea a coastal deck rests on its quay or piles, not on the ground.
            boolean deckOnly = def.coastal() && layout.height(wx, wz) < p.getY();
            if (!deckOnly && (replacing == null || !replacing.contains(ground))) {
                var state = level.getBlockState(ground);
                if (state.isAir() || !level.getFluidState(ground).isEmpty() || !state.isSolidRender(level, ground)) return Result.fail("invalid_terrain");
            }
            for (int y = 1; y < def.height(); y++) {
                BlockPos q = p.offset(x, y, z);
                if (replacing != null && replacing.contains(q)) continue;
                var state = level.getBlockState(q);
                if (!state.isAir() && !Nature.natural(state)) return Result.fail("obstructed");
            }
        }
        return Result.ok();
    }
    /** The island a site belongs to, judged from the middle of its front row (a coastal back row lies in the sea). */
    public static Optional<IslandLayout.Island> islandOf(IslandLayout layout, BuildingDefinition def, BlockPos p, int rotation) {
        int[] front = Siting.turn(def, rotation, def.width() / 2, 0);
        return layout.islandAt(p.getX() + front[0], p.getZ() + front[1]);
    }
    /** Blocks between the ground and a coastal deck raised over the shore or the sea, bottom to top. */
    private static List<BlockPos> under(IslandLayout layout, BlockPos base, int w, int d) {
        List<BlockPos> result = new ArrayList<>();
        for (int x = 0; x < w; x++) for (int z = 0; z < d; z++) {
            int h = layout.height(base.getX() + x, base.getZ() + z);
            for (int y = Math.max(1, h + 1); y < base.getY(); y++) result.add(new BlockPos(base.getX() + x, y, base.getZ() + z));
        }
        return result;
    }
    /** Where a jetty leaves open water over the beach, the beach itself stays: the template's air does not dig into it. */
    private static void shore(ServerLevel level, IslandLayout layout, BlockPos base, int w, int d, ListTag backup) {
        Map<Long, CompoundTag> original = new HashMap<>();
        for (Tag t : backup) original.put(((CompoundTag) t).getLong("pos"), (CompoundTag) t);
        for (int x = 0; x < w; x++) for (int z = 0; z < d; z++) {
            BlockPos q = base.offset(x, 0, z);
            if (layout.height(q.getX(), q.getZ()) < base.getY() || !level.getBlockState(q).isAir() || !original.containsKey(q.asLong())) continue;
            level.setBlock(q, NbtUtils.readBlockState(level.holderLookup(Registries.BLOCK), original.get(q.asLong()).getCompound("state")), 2);
        }
    }
    /**
     * Carries a coastal deck: under stone, a quay wall down to the sea bed; under planks, piles on a grid and along
     * the edges, with the water flowing between them; on dry shore, an earth bank.
     */
    private static void support(ServerLevel level, BlockPos base, int w, int d, List<BlockPos> under) {
        BlockState pile = Blocks.STRIPPED_SPRUCE_LOG.defaultBlockState(), quay = Blocks.STONE_BRICKS.defaultBlockState(), bank = fr.annocraft.world.AnnoBlocks.PACKED_EARTH.get().defaultBlockState();
        for (BlockPos q : under) {
            BlockState deck = level.getBlockState(new BlockPos(q.getX(), base.getY(), q.getZ()));
            if (deck.isAir()) continue;
            int ox = q.getX() - base.getX(), oz = q.getZ() - base.getZ();
            boolean wet = !level.getFluidState(q).isEmpty() || q.getY() <= IslandLayout.SEA_LEVEL;
            boolean wood = deck.is(net.minecraft.tags.BlockTags.PLANKS) || deck.is(net.minecraft.tags.BlockTags.LOGS) || deck.is(net.minecraft.tags.BlockTags.WOODEN_SLABS)
                    || deck.is(net.minecraft.tags.BlockTags.WOODEN_STAIRS) || deck.getSoundType() == net.minecraft.world.level.block.SoundType.WOOD;
            BlockState fill;
            if (!wood) fill = wet ? quay : bank;
            else if ((ox % 3 == 0 || ox == w - 1) && (oz % 3 == 0 || oz == d - 1)) fill = pile;
            else if (!wet) fill = bank;
            else continue;
            level.setBlock(q, fill, 2);
        }
        CameraSessions.refresh(level, base, w, d);
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
