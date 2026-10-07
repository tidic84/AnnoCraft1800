package fr.annocraft.server;

import fr.annocraft.AnnoCraft;
import fr.annocraft.building.BuildingInstance;
import fr.annocraft.economy.*;
import fr.annocraft.world.IslandLayout;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.npc.*;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.structure.templatesystem.*;
import net.minecraft.world.phys.AABB;
import java.util.*;

/**
 * Life in the world: citizens walking around inhabited residences near players and cameras, and the visible
 * settlements of rival factions on their islands. Citizens are ordinary villagers tagged as colony residents.
 */
public final class WorldLife {
    public static final String CITIZEN = "annocraft_citizen";
    private static final Random RANDOM = new Random();
    private WorldLife() { }
    public static boolean citizen(Entity e) { return e.getTags().contains(CITIZEN); }

    public static void tick(MinecraftServer server) {
        ColonyData data = ColonyData.get(server);
        for (ResourceKey<Level> key : AnnoCraft.WORLDS) {
            ServerLevel level = server.getLevel(key); if (level == null) continue;
            String world = AnnoCraft.worldName(key);
            List<double[]> focus = new ArrayList<>();
            for (ServerPlayer p : level.players()) {
                if (p.isSpectator()) continue;
                focus.add(new double[]{p.getX(), p.getZ()});
                CameraSessions.focus(p).ifPresent(focus::add);
            }
            cleanup(level, data, world, focus);
            for (double[] f : focus) populate(level, data, world, f);
            decorate(level, data, world);
        }
    }
    private static List<BuildingInstance> homes(ColonyData data, String world, double x, double z, double range) {
        List<BuildingInstance> result = new ArrayList<>();
        for (BuildingInstance b : data.buildings().values())
            if (ColonyEconomy.worldOf(b.island()).equals(world) && ColonyData.profile(b).housing()
                    && Math.abs(b.origin().getX() - x) < range && Math.abs(b.origin().getZ() - z) < range) result.add(b);
        return result;
    }
    private static void cleanup(ServerLevel level, ColonyData data, String world, List<double[]> focus) {
        for (Entity e : level.getAllEntities()) {
            if (!citizen(e)) continue;
            boolean watched = focus.stream().anyMatch(f -> Math.abs(f[0] - e.getX()) < 96 && Math.abs(f[1] - e.getZ()) < 96);
            if (!watched || homes(data, world, e.getX(), e.getZ(), 48).isEmpty()) e.discard();
        }
    }
    private static void populate(ServerLevel level, ColonyData data, String world, double[] f) {
        List<BuildingInstance> homes = homes(data, world, f[0], f[1], 64);
        if (homes.isEmpty()) return;
        double residents = 0;
        for (BuildingInstance h : homes) { var s = data.economy().state(h.id()); if (s != null) residents += s.residents; }
        int target = (int) Math.min(24, residents / 6), present = level.getEntitiesOfClass(Villager.class,
                new AABB(f[0] - 64, 0, f[1] - 64, f[0] + 64, 256, f[1] + 64), WorldLife::citizen).size();
        for (int i = 0; i < Math.min(3, target - present); i++) {
            BuildingInstance home = homes.get(RANDOM.nextInt(homes.size()));
            BlockPos pos = doorstep(level, home); if (pos == null) continue;
            Villager v = EntityType.VILLAGER.create(level); if (v == null) return;
            String tier = ColonyData.profile(home).houseTier();
            v.setVillagerData(v.getVillagerData().setType(IslandLayout.NEW_WORLD.equals(world) ? VillagerType.JUNGLE : VillagerType.PLAINS)
                    .setProfession(profession(tier)).setLevel(1 + Math.max(0, ColonyEconomy.TIERS.indexOf(tier) % 5)));
            v.moveTo(pos.getX() + .5, pos.getY(), pos.getZ() + .5, RANDOM.nextFloat() * 360, 0);
            v.addTag(CITIZEN); v.setInvulnerable(true); v.setSilent(RANDOM.nextInt(3) > 0);
            level.addFreshEntity(v);
        }
    }
    private static VillagerProfession profession(String tier) {
        return switch (tier == null ? "" : tier) {
            case "workers" -> VillagerProfession.MASON;
            case "artisans" -> VillagerProfession.LEATHERWORKER;
            case "engineers" -> VillagerProfession.LIBRARIAN;
            case "investors" -> VillagerProfession.CLERIC;
            case "overseers" -> VillagerProfession.SHEPHERD;
            default -> VillagerProfession.FARMER;
        };
    }
    /** A free, loaded block next to the residence where a citizen can stand. */
    private static BlockPos doorstep(ServerLevel level, BuildingInstance b) {
        for (int attempt = 0; attempt < 8; attempt++) {
            int side = RANDOM.nextInt(4);
            int x = switch (side) { case 0 -> b.origin().getX() - 1; case 1 -> b.origin().getX() + b.width(); default -> b.origin().getX() + RANDOM.nextInt(b.width()); };
            int z = switch (side) { case 2 -> b.origin().getZ() - 1; case 3 -> b.origin().getZ() + b.depth(); default -> b.origin().getZ() + RANDOM.nextInt(b.depth()); };
            BlockPos pos = new BlockPos(x, b.origin().getY(), z);
            if (!level.hasChunkAt(pos)) return null;
            if (level.getBlockState(pos).isAir() && level.getBlockState(pos.above()).isAir() && !level.getBlockState(pos.below()).isAir()) return pos;
        }
        return null;
    }

    /** Rival islands show a small town (warehouse and houses) once their chunks are loaded by a player or camera. */
    private static void decorate(ServerLevel level, ColonyData data, String world) {
        IslandLayout layout = BuildingService.generator(level).layout();
        for (var entry : data.diplomacy().owners().entrySet()) {
            String island = entry.getKey(), owner = entry.getValue();
            boolean conquered = Diplomacy.PLAYER.equals(owner);
            if (!ColonyEconomy.worldOf(island).equals(world)) continue;
            if (conquered ? !"built".equals(data.decoration(island)) : data.decorated(island)) continue;
            var isle = layout.island(island).orElse(null); if (isle == null) continue;
            int cx = isle.x(), cz = isle.z();
            boolean loaded = true;
            for (int x = (cx - 32) >> 4; x <= (cx + 32) >> 4 && loaded; x++) for (int z = (cz - 32) >> 4; z <= (cz + 32) >> 4; z++) if (!level.hasChunk(x, z)) { loaded = false; break; }
            if (!loaded) continue;
            if (conquered) {
                // The rival town is razed after conquest, freeing the island centre for the player's colony.
                BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
                for (int x = cx - 20; x <= cx + 26; x++) for (int z = cz - 20; z <= cz + 26; z++) for (int y = 74; y < 92; y++) {
                    pos.set(x, y, z);
                    if (!level.getBlockState(pos).isAir() && !data.managed(world, pos) && !data.road(world, pos)) level.setBlock(pos, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 2);
                }
                data.markCleared(island);
                CameraSessions.refresh(level, new BlockPos(cx - 20, 0, cz - 20), 47, 47);
                continue;
            }
            String house = IslandLayout.NEW_WORLD.equals(world) ? "laborer_house" : Diplomacy.type(owner) != null && Diplomacy.type(owner).pirate() ? "residence" : "residence_3";
            place(level, "warehouse", new BlockPos(cx - 5, 74, cz - 4));
            for (int[] o : new int[][]{{-18, -18}, {12, -18}, {-18, 12}, {12, 12}, {-3, 16}}) place(level, house, new BlockPos(cx + o[0], 74, cz + o[1]));
            if (Diplomacy.type(owner) != null && !Diplomacy.type(owner).pirate()) place(level, "church", new BlockPos(cx + 16, 74, cz - 3));
            data.markDecorated(island);
            CameraSessions.refresh(level, new BlockPos(cx - 20, 0, cz - 20), 47, 47);
        }
    }
    private static void place(ServerLevel level, String id, BlockPos at) {
        level.getStructureManager().get(AnnoCraft.id(id)).ifPresent(t -> t.placeInWorld(level, at, at, new StructurePlaceSettings().setIgnoreEntities(true), level.random, 2));
    }
}
