package fr.annocraft.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import fr.annocraft.economy.*;
import fr.annocraft.world.IslandLayout;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.*;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import java.util.*;

/**
 * Draws the colony's ships as small block models at their simulated positions. Ships are not entities:
 * the server simulates voyages abstractly and clients extrapolate between economy updates.
 */
public final class ShipRenderer {
    private static Geography geography = Geography.of(List.of());
    private static Map<String, IslandLayout> source;
    private record Part(int x, int y, int z, BlockState state) { }
    private static final Map<String, List<Part>> MODELS = new HashMap<>();
    private ShipRenderer() { }
    static Geography geography() {
        if (source == null || !source.equals(ClientState.LAYOUTS)) { source = new HashMap<>(ClientState.LAYOUTS); geography = Geography.of(ClientState.LAYOUTS.values()); }
        return geography;
    }
    /** Hull along +X, centred on the origin: deck, gunwales, masts with sails, guns or a funnel by type. */
    private static List<Part> model(Maritime.ShipType type) {
        return MODELS.computeIfAbsent(type.id(), id -> {
            List<Part> parts = new ArrayList<>();
            int length = 6 + type.slots() * 2, half = length / 2;
            boolean steam = id.startsWith("steam") || id.equals("battleship"), military = type.military();
            BlockState hull = (steam ? Blocks.POLISHED_DEEPSLATE : Blocks.DARK_OAK_PLANKS).defaultBlockState();
            BlockState rail = (steam ? Blocks.DEEPSLATE_TILES : Blocks.SPRUCE_PLANKS).defaultBlockState();
            for (int x = -half; x < length - half; x++) {
                boolean bow = x == length - half - 1, stern = x == -half;
                for (int z = -1; z <= 1; z++) {
                    if (bow && z != 0) continue;
                    parts.add(new Part(x, 0, z, hull));
                    if (z != 0 || stern) parts.add(new Part(x, 1, z, rail));
                }
            }
            if (steam) {
                for (int y = 1; y <= 4; y++) parts.add(new Part(-1, y, 0, Blocks.BRICKS.defaultBlockState()));
                parts.add(new Part(-1, 5, 0, Blocks.CAMPFIRE.defaultBlockState()));
            } else {
                int masts = Math.max(1, type.slots() / 2);
                for (int m = 0; m < masts; m++) {
                    int mx = masts == 1 ? 0 : -half / 2 + m * half;
                    for (int y = 1; y <= 7; y++) parts.add(new Part(mx, y, 0, Blocks.SPRUCE_FENCE.defaultBlockState()));
                    BlockState sail = (military ? Blocks.RED_WOOL : Blocks.WHITE_WOOL).defaultBlockState();
                    for (int y = 3; y <= 6; y++) for (int z = -2; z <= 2; z++) if (Math.abs(z) < 2 || (y > 3 && y < 6)) parts.add(new Part(mx + 1, y, z, sail));
                }
            }
            if (military) for (int x = -half + 1; x < length - half - 1; x += 2) for (int z : new int[]{-2, 2})
                parts.add(new Part(x, 1, z, Blocks.POLISHED_BLACKSTONE.defaultBlockState()));
            return List.copyOf(parts);
        });
    }
    /**
     * Mooring of a ship at an island, {x, z, heading x, heading z, out x, out z}: along the quay of the island's
     * shipyard, else of its trading post or another harbour building, side by side; the seed's coast otherwise.
     */
    static double[] dock(String island, int slot) {
        return fr.annocraft.building.Harbours.dock(ClientState.BUILDINGS.values(), ClientState.DEFINITIONS::get, geography(), island, slot);
    }
    /** The ship's sea lane, from the snapshot. */
    static List<double[]> path(CompoundTag s) {
        ListTag p = s.getList("path", Tag.TAG_DOUBLE); List<double[]> out = new ArrayList<>();
        for (int i = 0; i + 1 < p.size(); i += 2) out.add(new double[]{p.getDouble(i), p.getDouble(i + 1)});
        return out;
    }
    /** How far along its lane a moving ship is now, extrapolated from the last snapshot. */
    static double progress(CompoundTag s) {
        double duration = Math.max(1, s.getDouble("duration"));
        return Math.min(1, s.getDouble("progress") + (System.nanoTime() - ClientState.economyTime) / 1e9 / duration);
    }
    /** World position and heading {x, z, dx, dz} of a ship in the current world, or null when it sails elsewhere. */
    static double[] position(CompoundTag s, Map<String, Integer> moored) {
        Geography geo = geography(); String world = ClientState.world;
        if (s.contains("world")) {
            // The server knows where it is: at its berth, at anchor, or along its lane.
            if (!s.getString("world").equals(world)) return null;
            if (s.contains("path")) return SeaRoutes.along(path(s), progress(s));
            if (!s.contains("to")) return new double[]{s.getDouble("x"), s.getDouble("z"), s.getDouble("hx"), s.getDouble("hz")};
        }
        if (!s.contains("to")) {
            String at = s.getString("at");
            if (!geo.exists(at) || !geo.world(at).equals(world)) return null;
            double[] d = dock(at, moored.merge(at, 1, Integer::sum) - 1);
            return new double[]{d[0], d[1], d[2], d[3]};
        }
        String from = s.getString("from"), to = s.getString("to");
        double t = progress(s);
        if (!geo.exists(to)) return null;
        boolean fromHere = geo.exists(from) && geo.world(from).equals(world), toHere = geo.world(to).equals(world);
        // Crossing the ocean between worlds: sail out of (or into) this world's region.
        String here = fromHere ? from : toHere ? to : null; if (here == null) return null;
        double[] h = dock(here, 0);
        List<double[]> out = List.of(new double[]{h[0], h[1]}, new double[]{h[0] + h[4] * 700, h[1] + h[5] * 700});
        double[] p = SeaRoutes.along(out, fromHere ? t : 1 - t);
        if (!fromHere) { p[2] = -p[2]; p[3] = -p[3]; }
        return p;
    }
    /** Where every ship in sight is this frame, by id: {x, z, dx, dz}. Kept for picking, rings and bars. */
    static final Map<UUID, double[]> SEEN = new HashMap<>();
    /** Every ship of the snapshot, the enemies' included. */
    static List<CompoundTag> all() {
        List<CompoundTag> list = new ArrayList<>();
        ClientState.economy.getCompound("maritime").getList("ships", Tag.TAG_COMPOUND).forEach(t -> list.add((CompoundTag) t));
        return list;
    }
    static CompoundTag find(UUID id) {
        for (CompoundTag s : all()) if (s.getUUID("id").equals(id)) return s;
        return null;
    }
    /** Sails in the colours of whoever owns the ship: the player's company colour, a rival's colour, black for pirates. */
    private static BlockState sails(CompoundTag s, Maritime.ShipType type) {
        return (switch (s.getString("owner")) {
            case "corsairs" -> Blocks.BLACK_WOOL; case "dravek" -> Blocks.RED_WOOL; case "ashby" -> Blocks.PURPLE_WOOL;
            // A company's warships sail under its colour, its merchants under white canvas.
            default -> type.military() ? companyWool(s.getBoolean("company") ? ClientState.colorOf(s.getString("owner")) : ClientState.myColor()) : Blocks.WHITE_WOOL;
        }).defaultBlockState();
    }
    /** Wool nearest to the company's colour: warships sail under it. */
    private static net.minecraft.world.level.block.Block companyWool(int c) { net.minecraft.world.item.DyeColor best = net.minecraft.world.item.DyeColor.RED; double bestD = Double.MAX_VALUE;
        for (var dye : net.minecraft.world.item.DyeColor.values()) {
            int d = dye.getFireworkColor();
            double dist = Math.pow((d >> 16 & 255) - (c >> 16 & 255), 2) + Math.pow((d >> 8 & 255) - (c >> 8 & 255), 2) + Math.pow((d & 255) - (c & 255), 2);
            if (dist < bestD) { bestD = dist; best = dye; }
        }
        return net.minecraft.core.registries.BuiltInRegistries.BLOCK.get(net.minecraft.resources.ResourceLocation.withDefaultNamespace(best.getName() + "_wool"));
    }
    public static void render(RenderLevelStageEvent event) {
        List<CompoundTag> ships = all(); Minecraft mc = Minecraft.getInstance();
        SEEN.clear();
        if (ships.isEmpty() || mc.level == null) return;
        PoseStack pose = event.getPoseStack(); Vec3 camera = event.getCamera().getPosition();
        var buffers = mc.renderBuffers().bufferSource(); var blocks = mc.getBlockRenderer();
        double time = (System.nanoTime() % 1_000_000_000_000L) / 1e9;
        int index = 0; Map<String, Integer> moored = new HashMap<>();
        for (CompoundTag s : ships) {
            double[] p = position(s, moored); index++; if (p == null) continue;
            if (Math.hypot(p[0] - camera.x, p[1] - camera.z) > 1200) continue;
            Maritime.ShipType type = Maritime.type(s.getString("type")); if (type == null) continue;
            SEEN.put(s.getUUID("id"), p);
            double sea = IslandLayout.SEA_LEVEL + .55 + Math.sin(time * 1.3 + index) * .08;
            int light = LevelRenderer.getLightColor(mc.level, BlockPos.containing(p[0], sea + 2, p[1]));
            BlockState sail = sails(s, type);
            pose.pushPose();
            pose.translate(p[0] - camera.x, sea - camera.y, p[1] - camera.z);
            pose.mulPose(Axis.YP.rotation((float) -Math.atan2(p[3], p[2])));
            pose.mulPose(Axis.XP.rotationDegrees((float) Math.sin(time * 1.1 + index) * 2));
            for (Part part : model(type)) {
                pose.pushPose(); pose.translate(part.x() - .5, part.y(), part.z() - .5);
                BlockState state = part.state().is(Blocks.WHITE_WOOL) || part.state().is(Blocks.RED_WOOL) ? sail : part.state();
                blocks.renderSingleBlock(state, pose, buffers, light, OverlayTexture.NO_OVERLAY);
                pose.popPose();
            }
            pose.popPose();
        }
        buffers.endBatch();
    }
    /** Cannon fire: smoke at the guns and splashes around the target, with the thunder of the broadside. */
    static void tick(Minecraft mc) {
        if (mc.level == null) return;
        for (CompoundTag s : all()) {
            if (!s.hasUUID("firing")) continue;
            double[] p = SEEN.get(s.getUUID("id")), q = SEEN.get(s.getUUID("firing"));
            if (p == null || q == null || mc.level.random.nextInt(12) != 0) continue;
            double hl = Math.max(1e-6, Math.hypot(p[2], p[3])), sx = -p[3] / hl, sz = p[2] / hl;
            double side = (q[0] - p[0]) * sx + (q[1] - p[1]) * sz > 0 ? 1 : -1;
            for (int i = 0; i < 6; i++) {
                double along = (mc.level.random.nextDouble() - .5) * 6;
                double x = p[0] + sx * 2.5 * side + p[2] / hl * along, z = p[1] + sz * 2.5 * side + p[3] / hl * along;
                mc.level.addParticle(net.minecraft.core.particles.ParticleTypes.CAMPFIRE_COSY_SMOKE, x, IslandLayout.SEA_LEVEL + 2.2, z, sx * side * .05, .02, sz * side * .05);
            }
            for (int i = 0; i < 4; i++) {
                double x = q[0] + (mc.level.random.nextDouble() - .5) * 10, z = q[1] + (mc.level.random.nextDouble() - .5) * 10;
                mc.level.addParticle(net.minecraft.core.particles.ParticleTypes.SPLASH, x, IslandLayout.SEA_LEVEL + 1.1, z, 0, .3, 0);
            }
            mc.level.addParticle(net.minecraft.core.particles.ParticleTypes.EXPLOSION, q[0], IslandLayout.SEA_LEVEL + 2.5, q[1], 0, 0, 0);
            mc.level.playLocalSound(p[0], IslandLayout.SEA_LEVEL + 2, p[1], net.minecraft.sounds.SoundEvents.GENERIC_EXPLODE, net.minecraft.sounds.SoundSource.NEUTRAL, .6f, .7f + mc.level.random.nextFloat() * .2f, false);
        }
    }
}
