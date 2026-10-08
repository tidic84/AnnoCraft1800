package fr.annocraft.building;

import fr.annocraft.economy.Geography;
import fr.annocraft.world.IslandLayout;
import net.minecraft.resources.ResourceLocation;
import java.util.function.Function;

/** Where ships moor: along the quay of an island's shipyard, else of its trading post or another harbour building. Shared by server and client. */
public final class Harbours {
    private Harbours() { }
    /**
     * Mooring {x, z, heading x, heading z, out x, out z} of the n-th ship at an island: side by side along the quay,
     * three abreast then a row farther out; off the seed's coast when the island has no harbour building.
     */
    public static double[] dock(Iterable<BuildingInstance> buildings, Function<ResourceLocation, BuildingDefinition> definitions, Geography geo, String island, int slot) {
        BuildingInstance port = null; BuildingDefinition portDef = null; int rank = 9;
        for (BuildingInstance b : buildings) {
            BuildingDefinition def = definitions.apply(b.definition());
            if (def == null || !def.coastal() || !b.island().equals(island)) continue;
            int r = def.economy().shipyard() ? 0 : def.economy().storageNode() ? 1 : 2;
            if (r < rank) { rank = r; port = b; portDef = def; }
        }
        if (port == null) {
            IslandLayout.Island i = geo.islands().get(island);
            double[] h = geo.harbour(island, null);
            double ox = i == null ? 1 : h[0] - i.x(), oz = i == null ? 0 : h[1] - i.z(), l = Math.max(1, Math.hypot(ox, oz));
            return moor(h[0], h[1], ox / l, oz / l, slot);
        }
        int[] front = Siting.turn(portDef, port.rotation(), portDef.width() / 2, 0), back = Siting.turn(portDef, port.rotation(), portDef.width() / 2, portDef.depth() - 1);
        double ox = back[0] - front[0], oz = back[1] - front[1], l = Math.max(1, Math.hypot(ox, oz));
        return moor(port.origin().getX() + back[0] + .5 + ox / l * 5, port.origin().getZ() + back[1] + .5 + oz / l * 5, ox / l, oz / l, slot);
    }
    private static double[] moor(double x, double z, double ox, double oz, int slot) {
        double side = (slot % 3 == 2 ? -1 : slot % 3) * 15, out = (slot / 3) * 9;
        return new double[]{x - oz * side + ox * out, z + ox * side + oz * out, -oz, ox, ox, oz};
    }
}
