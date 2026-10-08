import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.GZIPOutputStream;

/**
 * Generates AnnoCraft1800 content from tools/content.txt: building definitions (JSON), the buildings themselves
 * (vanilla-block NBT structures, each designed after its Anno 1800 counterpart) and the content language files.
 * JDK standard library only.
 * Run from the project root with JDK 17: {@code java tools/GenerateContent.java}
 */
public final class GenerateContent {
    static final Path DATA = Path.of("src/main/resources/data/annocraft1800");
    static final Path LANG = Path.of("src/main/resources/assets/annocraft_content/lang");
    record Pos(int x, int y, int z) implements Comparable<Pos> {
        public int compareTo(Pos o) { return x != o.x ? Integer.compare(x, o.x) : y != o.y ? Integer.compare(y, o.y) : Integer.compare(z, o.z); }
    }

    public static void main(String[] args) throws IOException {
        Map<String, String> fr = new TreeMap<>(), en = new TreeMap<>();
        Path buildings = DATA.resolve("annocraft_buildings");
        Files.createDirectories(buildings);
        try (var old = Files.list(buildings)) { for (Path p : old.toList()) if (p.toString().endsWith(".json")) Files.delete(p); }
        int count = 0;
        for (String raw : Files.readAllLines(Path.of("tools/content.txt"), StandardCharsets.UTF_8)) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) continue;
            String[] f = line.split("\\|");
            switch (f[0]) {
                case "tier" -> { fr.put("tier.annocraft1800." + f[1], f[2]); en.put("tier.annocraft1800." + f[1], f[3]); }
                case "good" -> { fr.put("good.annocraft1800." + f[1], f[2]); en.put("good.annocraft1800." + f[1], f[3]); }
                case "building" -> {
                    String id = f[1];
                    fr.put("building.annocraft1800." + id, f[2]); en.put("building.annocraft1800." + id, f[3]);
                    String[] size = f[4].split("x");
                    int w = Integer.parseInt(size[0]), d = Integer.parseInt(size[1]);
                    int h = structure(id, w, d);
                    Files.writeString(buildings.resolve(id + ".json"), definition(id, w, h, d, options(f.length > 5 ? f[5] : "")), StandardCharsets.UTF_8);
                    count++;
                }
                default -> throw new IllegalArgumentException("Unknown line: " + line);
            }
        }
        Map<Pos, String> empty = new TreeMap<>(); empty.put(new Pos(0, 0, 0), "air");
        write("empty", 1, 1, 1, empty);
        Files.createDirectories(LANG);
        Files.writeString(LANG.resolve("fr_fr.json"), json(fr), StandardCharsets.UTF_8);
        Files.writeString(LANG.resolve("en_us.json"), json(en), StandardCharsets.UTF_8);
        System.out.println("Generated " + count + " buildings, their structures and " + fr.size() + " translations.");
    }

    static Map<String, String> options(String text) {
        Map<String, String> o = new LinkedHashMap<>();
        for (String token : text.trim().split("\\s+")) {
            if (token.isEmpty()) continue;
            int eq = token.indexOf('=');
            o.put(eq < 0 ? token : token.substring(0, eq), eq < 0 ? "true" : token.substring(eq + 1));
        }
        return o;
    }
    static String definition(String id, int w, int h, int d, Map<String, String> o) {
        StringBuilder s = new StringBuilder("{\n");
        s.append("  \"name\": \"building.annocraft1800.").append(id).append("\",\n");
        s.append("  \"structure\": \"annocraft1800:").append(id).append("\",\n");
        s.append("  \"width\": ").append(w).append(", \"height\": ").append(h).append(", \"depth\": ").append(d).append(",\n");
        s.append("  \"coastal\": ").append(o.containsKey("coastal")).append(", \"level\": ").append(o.getOrDefault("level", "1")).append(",\n");
        if (o.containsKey("upgrade")) s.append("  \"upgrade\": \"annocraft1800:").append(o.get("upgrade")).append("\",\n");
        List<String> e = new ArrayList<>();
        e.add("\"cost\": " + amounts(o.getOrDefault("cost", "")));
        if (o.containsKey("upkeep")) e.add("\"upkeep\": " + o.get("upkeep"));
        if (o.containsKey("storage")) e.add("\"storage\": " + o.get("storage"));
        if (o.containsKey("work")) { String[] t = o.get("work").split(":"); e.add("\"workforce\": {\"tier\": \"" + t[0] + "\", \"amount\": " + t[1] + "}"); }
        if (o.containsKey("cycle")) e.add("\"production\": {\"cycle\": " + o.get("cycle") + ", \"inputs\": " + amounts(o.getOrDefault("in", "")) + ", \"outputs\": " + amounts(o.get("out")) + "}");
        if (o.containsKey("house")) {
            String[] t = o.get("house").split(":");
            e.add("\"housing\": {\"tier\": \"" + t[0] + "\", \"capacity\": " + t[1] + ", \"tax\": " + t[2]
                    + ", \"needs\": " + amounts(o.getOrDefault("needs", "")) + ", \"luxury\": " + amounts(o.getOrDefault("lux", ""))
                    + ", \"services\": " + list(o.getOrDefault("svc", "")) + ", \"luxury_services\": " + list(o.getOrDefault("lsvc", "")) + "}");
        }
        if (o.containsKey("service")) { String[] t = o.get("service").split(":"); e.add("\"service\": {\"id\": \"" + t[0] + "\", \"radius\": " + t[1] + "}"); }
        if (o.containsKey("fert")) e.add("\"requires\": {\"fertility\": \"" + o.get("fert") + "\"}");
        if (o.containsKey("dep")) e.add("\"requires\": {\"deposit\": \"" + o.get("dep") + "\"}");
        e.add("\"world\": \"" + o.getOrDefault("world", "any") + "\"");
        if (o.containsKey("unlock")) { String[] t = o.get("unlock").split(":"); e.add("\"unlock\": {\"tier\": \"" + t[0] + "\", \"residents\": " + t[1] + "}"); }
        if (o.containsKey("shipyard")) e.add("\"shipyard\": true");
        if (o.containsKey("defense")) e.add("\"defense\": " + o.get("defense"));
        if (o.containsKey("boost")) {
            String[] t = o.get("boost").split(":");
            e.add("\"boost\": {\"radius\": " + t[0] + ", \"productivity\": " + t[1] + ", \"inputs\": " + (t.length > 3 ? "{\"" + t[2] + "\": " + t[3] + "}" : "{}") + "}");
        }
        s.append("  \"economy\": {\n    ").append(String.join(",\n    ", e)).append("\n  }\n}\n");
        return s.toString();
    }
    static String amounts(String text) {
        if (text == null || text.isEmpty()) return "{}";
        List<String> parts = new ArrayList<>();
        for (String pair : text.split(",")) { String[] kv = pair.split(":"); parts.add("\"" + kv[0] + "\": " + kv[1]); }
        return "{" + String.join(", ", parts) + "}";
    }
    static String list(String text) {
        if (text.isEmpty()) return "[]";
        List<String> parts = new ArrayList<>(); for (String s : text.split(",")) parts.add("\"" + s + "\"");
        return "[" + String.join(", ", parts) + "]";
    }
    static String json(Map<String, String> entries) {
        List<String> lines = new ArrayList<>();
        entries.forEach((k, v) -> lines.add("  \"" + k + "\": \"" + v.replace("\\", "\\\\").replace("\"", "\\\"") + "\""));
        return "{\n" + String.join(",\n", lines) + "\n}\n";
    }

    // ------------------------------------------------------------------------------------------------ architecture
    // Every building is designed after its Anno 1800 counterpart, with vanilla blocks only. The plot's front
    // (entrance, street side) is z = 0; y = 0 is the ground layer that replaces the terrain under the footprint.

    /** A building site: block map with bounds, plus the vocabulary of Anno's architecture. */
    static final class Plot {
        final int w, d;
        final Map<Pos, String> b = new TreeMap<>();
        Plot(int w, int d) { this.w = w; this.d = d; }

        void set(int x, int y, int z, String s) { if (x >= 0 && x < w && z >= 0 && z < d && y >= 0 && y < 32) b.put(new Pos(x, y, z), s); }
        String get(int x, int y, int z) { return b.get(new Pos(x, y, z)); }
        void fill(int x0, int y0, int z0, int x1, int y1, int z1, String s) {
            for (int x = Math.min(x0, x1); x <= Math.max(x0, x1); x++) for (int y = Math.min(y0, y1); y <= Math.max(y0, y1); y++)
                for (int z = Math.min(z0, z1); z <= Math.max(z0, z1); z++) set(x, y, z, s);
        }
        void ground(String s) { fill(0, 0, 0, w - 1, 0, d - 1, s); }
        /** Perimeter of a rectangle, from y0 to y1. */
        void ring(int x0, int z0, int x1, int z1, int y0, int y1, String s) {
            for (int y = y0; y <= y1; y++) for (int x = x0; x <= x1; x++) for (int z = z0; z <= z1; z++)
                if (x == x0 || x == x1 || z == z0 || z == z1) set(x, y, z, s);
        }
        void posts(int x0, int z0, int x1, int z1, int y0, int y1, String s) {
            for (int[] c : new int[][]{{x0, z0}, {x1, z0}, {x0, z1}, {x1, z1}}) fill(c[0], y0, c[1], c[0], y1, c[1], s);
        }
        /**
         * Walls of a building: corner posts, a band at the floor of every upper storey (logs lie along their wall),
         * windows on every storey. The inside stays hollow. @return the y just above the walls
         */
        int body(int x0, int z0, int x1, int z1, int y0, int storeys, int storeyH, String[] walls, String post, String band, String window) {
            for (int s = 0; s < storeys; s++) {
                int ys = y0 + s * storeyH; String wall = walls[Math.min(s, walls.length - 1)];
                ring(x0, z0, x1, z1, ys, ys + storeyH - 1, wall);
                if (band != null && s > 0) {
                    for (int x = x0; x <= x1; x++) { set(x, ys, z0, axis(band, "x")); set(x, ys, z1, axis(band, "x")); }
                    for (int z = z0; z <= z1; z++) { set(x0, ys, z, axis(band, "z")); set(x1, ys, z, axis(band, "z")); }
                }
                if (window != null) {
                    int y = ys + Math.min(1, storeyH - 2) + (storeyH >= 4 ? 1 : 0);
                    for (int x = x0 + 1; x < x1; x++) if ((x - x0) % 2 == 1) { set(x, y, z0, window); set(x, y, z1, window); }
                    for (int z = z0 + 1; z < z1; z++) if ((z - z0) % 2 == 1) { set(x0, y, z, window); set(x1, y, z, window); }
                }
            }
            if (post != null) posts(x0, z0, x1, z1, y0, y0 + storeys * storeyH - 1, axis(post, "y"));
            return y0 + storeys * storeyH;
        }
        static String axis(String block, String axis) {
            return block.endsWith("_log") || block.endsWith("_wood") || block.endsWith("_pillar") || block.equals("hay_block") || block.endsWith("basalt")
                    || block.endsWith("_stem") || block.equals("bone_block") || block.equals("chain") ? block + "[axis=" + axis + "]" : block;
        }
        /** A closed wooden door: walking through it towards {@code facing} enters the building. */
        void door(int x, int y, int z, String wood, String facing) {
            set(x, y, z, wood + "_door[facing=" + facing + ",half=lower,hinge=left,open=false]");
            set(x, y + 1, z, wood + "_door[facing=" + facing + ",half=upper,hinge=left,open=false]");
        }
        static String stair(String mat, String facing) { return mat + "_stairs[facing=" + facing + ",half=bottom]"; }
        static String stairTop(String mat, String facing) { return mat + "_stairs[facing=" + facing + ",half=top]"; }
        static String slab(String mat) { return mat + "_slab[type=bottom]"; }
        static String slabTop(String mat) { return mat + "_slab[type=top]"; }
        /**
         * Pitched roof of stairs over a rectangle (eaves included), ridge along X or Z, rising from y. The gable
         * triangles and the attic inside the wall rectangle {@code fx0..fx1 × fz0..fz1} are filled with {@code fill}.
         */
        int roof(int x0, int z0, int x1, int z1, int y, String mat, boolean alongX, String fill, int fx0, int fz0, int fx1, int fz1) {
            int a0 = alongX ? z0 : x0, a1 = alongX ? z1 : x1, t0 = alongX ? x0 : z0, t1 = alongX ? x1 : z1, top = y;
            for (int k = 0; a0 + k <= a1 - k; k++) {
                int lo = a0 + k, hi = a1 - k, yy = y + k; top = yy;
                for (int t = t0; t <= t1; t++) {
                    if (lo == hi) { put(alongX, t, yy, lo, slab(mat)); continue; }
                    put(alongX, t, yy, lo, stair(mat, alongX ? "south" : "east"));
                    put(alongX, t, yy, hi, stair(mat, alongX ? "north" : "west"));
                    if (fill != null) for (int m = lo + 1; m < hi; m++) {
                        int x = alongX ? t : m, z = alongX ? m : t;
                        if (x >= fx0 && x <= fx1 && z >= fz0 && z <= fz1) set(x, yy, z, fill);
                    }
                }
            }
            return top;
        }
        private void put(boolean alongX, int t, int y, int a, String s) { if (alongX) set(t, y, a, s); else set(a, y, t, s); }
        /** Hipped roof (a pyramid on a square): stairs on all four sides, filled inside. @return the top y */
        int hip(int x0, int z0, int x1, int z1, int y, String mat, String fill) {
            int top = y;
            for (int k = 0; ; k++) {
                int xa = x0 + k, xb = x1 - k, za = z0 + k, zb = z1 - k, yy = y + k;
                if (xa > xb || za > zb) break;
                top = yy;
                if (xa == xb || za == zb) { fill(xa, yy, za, xb, yy, zb, slab(mat)); break; }
                for (int z = za; z <= zb; z++) { set(xa, yy, z, stair(mat, "east")); set(xb, yy, z, stair(mat, "west")); }
                for (int x = xa; x <= xb; x++) { set(x, yy, za, stair(mat, "south")); set(x, yy, zb, stair(mat, "north")); }
                if (fill != null) fill(xa + 1, yy, za + 1, xb - 1, yy, zb - 1, fill);
            }
            return top;
        }
        /** Upside-down stairs all around a rectangle: the moulded cornice of town houses. */
        void cornice(int x0, int z0, int x1, int z1, int y, String mat) {
            for (int x = x0; x <= x1; x++) { set(x, y, z0, stairTop(mat, "south")); set(x, y, z1, stairTop(mat, "north")); }
            for (int z = z0 + 1; z < z1; z++) { set(x0, y, z, stairTop(mat, "east")); set(x1, y, z, stairTop(mat, "west")); }
        }
        /** Chimney stack; working buildings smoke through a lit campfire on top. */
        void chimney(int x, int z, int y0, int y1, String mat, boolean smoke) {
            fill(x, y0, z, x, y1, z, mat);
            if (smoke) set(x, y1 + 1, z, "campfire[lit=true,signal_fire=false,facing=north]");
        }
        /** Open window shutters flush against the wall behind them (the wall is on the far side of {@code facing}). */
        void shutter(int x, int y, int z, String wood, String facing) { set(x, y, z, wood + "_trapdoor[facing=" + facing + ",half=bottom,open=true]"); }
        void lamp(int x, int z, String post) { fill(x, 1, z, x, 2, z, post); set(x, 3, z, "lantern[hanging=false]"); }
        /** Pile of logs lying along an axis. */
        void logs(int x0, int z0, int x1, int z1, int y0, int y1, String log, String axis) { fill(x0, y0, z0, x1, y1, z1, log + "[axis=" + axis + "]"); }
        /** Field beds: crop rows on tilled soil, a furrow path every third row. */
        void field(int x0, int z0, int x1, int z1, String crop, String soil, boolean rowsAlongX) {
            for (int x = x0; x <= x1; x++) for (int z = z0; z <= z1; z++) {
                int row = rowsAlongX ? z - z0 : x - x0;
                if (row % 3 == 2) { set(x, 0, z, "dirt_path"); set(x, 1, z, "air"); }
                else { set(x, 0, z, soil); set(x, 1, z, crop); }
            }
        }
        void fence(int x0, int z0, int x1, int z1, String fence, int gateX, int gateZ) {
            ring(x0, z0, x1, z1, 1, 1, fence);
            set(gateX, 1, gateZ, "air");
        }
        void tree(int x, int z, int y, String log, String leaves, int trunk) {
            fill(x, y, z, x, y + trunk - 1, z, log + "[axis=y]");
            String l = leaves + "[persistent=true]";
            int c = y + trunk;
            for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) { set(x + dx, c - 1, z + dz, x + dx == x && z + dz == z ? log + "[axis=y]" : l); set(x + dx, c, z + dz, l); }
            set(x, c + 1, z, l);
        }
        /** Hand-cart: a plank bed on wheels with a load on it. */
        void cart(int x, int z, String load) {
            set(x, 1, z, "spruce_slab[type=top]"); set(x + 1, 1, z, "spruce_slab[type=top]");
            set(x, 2, z, load); set(x + 2, 1, z, "spruce_fence");
        }
        /** Wooden jib crane, as on Anno's quays and building sites. */
        void crane(int x, int z, int height, int armZ) {
            fill(x, 1, z, x, height, z, "stripped_spruce_log[axis=y]");
            int dz = Integer.signum(armZ - z);
            for (int k = z + dz; k != armZ + dz; k += dz) set(x, height, k, "spruce_fence");
            fill(x, height - 1, armZ, x, height - 2, armZ, "chain[axis=y]");
            set(x, height - 3, armZ, "hay_block[axis=y]");
        }
        /** Flag pole with a cloth of wool at its top. */
        void flag(int x, int z, int top, String cloth) {
            fill(x, 1, z, x, top, z, "spruce_fence"); set(x + (x + 1 < w ? 1 : -1), top, z, cloth); set(x + (x + 1 < w ? 1 : -1), top - 1, z, cloth);
        }
        /** Copper still on a brick hearth: the schnapps and rum stills. */
        void still(int x, int z) {
            set(x, 1, z, "bricks"); set(x, 2, z, "waxed_copper_block"); set(x, 3, z, "waxed_cut_copper_slab[type=bottom]");
            set(x, 4, z, "lightning_rod[facing=up]");
        }
        void kegs(int x0, int z0, int x1, int z1, int y0, int y1) { fill(x0, y0, z0, x1, y1, z1, "stripped_spruce_log[axis=x]"); }
        /** Mountain mine: a rocky hill over the back of the plot, a timbered portal and an ore heap. */
        void mine(String ore, String rock) {
            ground("coarse_dirt");
            for (int y = 1; y < d; y++) for (int x = 0; x < w; x++) for (int z = 2; z < d; z++) {
                int height = (d - z) <= 1 ? 6 : Math.min(6, 2 + (z - 1) * 5 / (d - 2));
                int edge = Math.min(x, w - 1 - x);
                if (y <= height - Math.max(0, 1 - edge) * 2 && y <= 6) set(x, y, z, (x * 7 + y * 3 + z * 5) % 9 == 0 ? ore : (x + y + z) % 4 == 0 ? "andesite" : rock);
            }
            int c = w / 2;
            fill(c - 1, 1, 2, c + 1, 3, 4, "air");
            fill(c - 1, 1, 4, c + 1, 3, 4, "black_concrete");
            fill(c - 2, 1, 2, c - 2, 3, 2, "stripped_spruce_log[axis=y]"); fill(c + 2, 1, 2, c + 2, 3, 2, "stripped_spruce_log[axis=y]");
            fill(c - 2, 4, 2, c + 2, 4, 2, "stripped_spruce_log[axis=x]");
            set(c - 1, 3, 3, "spruce_planks"); set(c + 1, 3, 3, "spruce_planks"); set(c, 3, 2, "lantern[hanging=true]");
            fill(c - 2, 2, 3, c - 2, 3, 3, rock); fill(c + 2, 2, 3, c + 2, 3, 3, rock);
            for (int z = 0; z <= 3; z++) set(c, 1, z, "rail[shape=north_south]");
            set(w - 1, 1, 0, ore); set(w - 2, 1, 0, ore); set(w - 1, 1, 1, ore); set(w - 1, 2, 0, rock);
            set(0, 1, 0, "stripped_spruce_log[axis=x]"); set(1, 1, 0, "stripped_spruce_log[axis=x]");
            lamp(0, 1, "spruce_fence");
        }
    }

    static Plot design(String id, int w, int d) {
        Plot p = new Plot(w, d);
        switch (id) {
            // ---------------------------------------------------------------- logistics
            case "trading_post" -> {
                // The harbour office on the shore, a clock tower with the company flag, and behind them a stone
                // quay running out over the sea, with bollards, cargo and a crane swinging over the water.
                p.ground("stone_bricks");
                for (int x = 0; x < w; x++) for (int z = 0; z < 5; z++) if ((x * 3 + z) % 4 == 0) p.set(x, 0, z, "polished_andesite");
                p.fill(0, 0, 5, w - 1, 0, d - 1, "spruce_planks"); p.fill(0, 0, 5, w - 1, 0, 5, "stone_bricks");
                int top = p.body(0, 0, 6, 3, 1, 2, 3, new String[]{"stone_bricks", "polished_andesite"}, "chiseled_stone_bricks", "stone_bricks", "glass_pane");
                p.fill(2, 1, 0, 4, 2, 0, "air"); p.fill(2, 3, 0, 4, 3, 0, "stone_brick_stairs[facing=south,half=top]");
                p.door(3, 1, 0, "dark_oak", "south");
                p.roof(0, 0, 6, 3, top, "deepslate_tile", true, "polished_andesite", 0, 0, 6, 3);
                int t = p.body(8, 0, 10, 2, 1, 3, 3, new String[]{"stone_bricks"}, "chiseled_stone_bricks", null, "glass_pane");
                p.ring(8, 0, 10, 2, t, t, "stone_brick_wall"); p.set(9, t - 2, 0, "white_concrete");
                int spire = p.hip(8, 0, 10, 2, t + 1, "deepslate_tile", "deepslate_tiles");
                p.flag(9, 1, spire + 4, "red_wool");
                // Quay: bollards along the edge, crates and bales, the crane over the water.
                for (int x : new int[]{0, 4, 8}) { p.set(x, 1, d - 1, "stone_brick_wall"); p.set(x, 2, d - 1, "chain[axis=y]"); }
                for (int z = 8; z < d - 1; z += 3) { p.set(0, 1, z, "stone_brick_wall"); p.set(w - 1, 1, z, "stone_brick_wall"); }
                p.fill(1, 1, 6, 2, 1, 7, "barrel[facing=up,open=false]"); p.set(1, 2, 6, "barrel[facing=up,open=false]");
                p.set(4, 1, 6, "hay_block[axis=x]"); p.set(5, 1, 6, "hay_block[axis=x]"); p.set(5, 2, 6, "white_wool"); p.set(6, 1, 7, "brown_wool");
                p.set(3, 1, 4, "spruce_planks"); p.set(3, 2, 4, "spruce_slab[type=bottom]");
                // Cargo waiting at the end of the quay for the next ship.
                p.fill(2, 1, 9, 3, 1, 10, "oak_planks"); p.set(2, 2, 9, "spruce_slab[type=bottom]");
                p.set(5, 1, 10, "barrel[facing=up,open=false]"); p.set(6, 1, 10, "white_wool"); p.set(6, 2, 10, "white_wool");
                p.crane(9, 6, 7, 10);
                p.lamp(w - 1, d - 1, "spruce_fence");
                p.lamp(7, 4, "spruce_fence"); p.lamp(0, 4, "spruce_fence");
            }
            case "warehouse" -> {
                // Anno's warehouse: an open timber shed on posts, crammed with crates, sacks and bales, and a crane.
                p.ground("coarse_dirt"); p.fill(1, 0, 2, 9, 0, 7, "spruce_planks");
                p.posts(1, 2, 9, 7, 1, 3, "stripped_spruce_log[axis=y]"); p.fill(5, 1, 2, 5, 3, 2, "stripped_spruce_log[axis=y]"); p.fill(5, 1, 7, 5, 3, 7, "stripped_spruce_log[axis=y]");
                p.fill(1, 1, 7, 9, 3, 7, "spruce_planks"); p.posts(1, 2, 9, 7, 1, 3, "stripped_spruce_log[axis=y]");
                p.roof(1, 1, 9, 8, 4, "spruce", true, "spruce_planks", 1, 2, 9, 7);
                p.fill(2, 1, 5, 3, 2, 6, "spruce_planks"); p.fill(6, 1, 6, 8, 1, 6, "hay_block[axis=x]"); p.set(7, 2, 6, "hay_block[axis=x]");
                p.set(2, 1, 3, "white_wool"); p.set(3, 1, 3, "brown_wool"); p.set(2, 2, 3, "light_gray_wool"); p.set(7, 1, 3, "oak_planks"); p.set(8, 1, 4, "oak_planks"); p.set(8, 2, 4, "spruce_slab[type=bottom]");
                p.crane(10, 4, 6, 1);
                p.cart(1, 0, "oak_planks");
            }
            case "market" -> {
                // Anno's marketplace: a paved square with striped market stalls around a well.
                p.ground("stone_bricks");
                for (int x = 0; x < w; x++) for (int z = 0; z < d; z++) if ((x + z) % 2 == 0) p.set(x, 0, z, "polished_andesite");
                int[][] stalls = {{0, 0}, {w - 3, 0}, {0, d - 3}, {w - 3, d - 3}};
                String[] colours = {"red_wool", "blue_wool", "green_wool", "yellow_wool"};
                String[] goods = {"melon", "pumpkin", "hay_block[axis=y]", "white_wool"};
                for (int i = 0; i < 4; i++) {
                    int sx = stalls[i][0], sz = stalls[i][1];
                    p.posts(sx, sz, sx + 2, sz + 2, 1, 2, "spruce_fence");
                    for (int x = 0; x < 3; x++) for (int z = 0; z < 3; z++) p.set(sx + x, 3, sz + z, (x % 2 == 0) ? colours[i] : "white_wool");
                    p.set(sx + 1, 1, sz + (sz == 0 ? 2 : 0), "spruce_planks"); p.set(sx + 1, 2, sz + (sz == 0 ? 2 : 0), goods[i]);
                }
                int c = w / 2;
                p.ring(c - 1, d / 2 - 1, c + 1, d / 2 + 1, 1, 1, "stone_brick_wall"); p.set(c, 1, d / 2, "water_cauldron[level=3]");
                p.set(c - 1, 2, d / 2 - 1, "spruce_fence"); p.set(c + 1, 2, d / 2 + 1, "spruce_fence"); p.set(c - 1, 2, d / 2 + 1, "spruce_fence"); p.set(c + 1, 2, d / 2 - 1, "spruce_fence");
                p.roof(c - 1, d / 2 - 1, c + 1, d / 2 + 1, 3, "spruce", true, null, 0, 0, 0, 0);
                p.set(c, 3, d / 2, "chain[axis=y]"); p.set(c, 2, d / 2, "lantern[hanging=true]");
                p.cart(c - 1, 0, "melon");
            }
            case "shipyard" -> {
                // The carpenters' hall and timber yard on the shore; behind them the slipway runs down into the sea
                // with a hull standing in its ribs, under a gantry crane.
                p.ground("annocraft1800:packed_earth"); p.fill(0, 0, 4, w - 1, 0, d - 1, "spruce_planks");
                int top = p.body(0, 0, 4, 3, 1, 1, 4, new String[]{"oak_planks"}, "stripped_spruce_log", null, null);
                p.fill(1, 1, 3, 3, 3, 3, "air");
                p.roof(0, 0, 4, 3, top, "spruce", true, "oak_planks", 0, 0, 4, 3);
                p.door(2, 1, 0, "spruce", "south");
                p.fill(6, 1, 1, 9, 1, 2, "stripped_oak_log[axis=x]"); p.fill(7, 2, 1, 9, 2, 2, "stripped_oak_log[axis=x]"); p.set(10, 1, 0, "spruce_planks");
                // Slipway: the keel along it, ribs every other block, planking half done.
                for (int z = 4; z < d; z++) {
                    p.set(5, 1, z, "stripped_spruce_log[axis=z]");
                    if (z % 2 == 0) { p.set(4, 2, z, "spruce_fence"); p.set(6, 2, z, "spruce_fence"); p.set(3, 3, z, "spruce_fence"); p.set(7, 3, z, "spruce_fence"); p.set(5, 2, z, "spruce_planks"); }
                    else if (z < d - 2) { p.set(4, 2, z, "spruce_planks"); p.set(6, 2, z, "spruce_planks"); }
                }
                p.set(5, 2, 4, "stripped_spruce_log[axis=y]"); p.set(5, 3, 4, "stripped_spruce_log[axis=y]"); p.set(5, 2, d - 1, "spruce_stairs[facing=north,half=bottom]");
                // Gantry crane straddling the slipway.
                p.fill(1, 1, 6, 1, 7, 6, "stripped_spruce_log[axis=y]"); p.fill(9, 1, 6, 9, 7, 6, "stripped_spruce_log[axis=y]");
                p.fill(1, 8, 6, 9, 8, 6, "stripped_spruce_log[axis=x]"); p.set(5, 7, 6, "chain[axis=y]"); p.set(5, 6, 6, "chain[axis=y]"); p.set(5, 5, 6, "hay_block[axis=y]");
                p.lamp(10, 4, "spruce_fence"); p.set(0, 1, 5, "barrel[facing=up,open=false]"); p.set(0, 1, 7, "barrel[facing=up,open=false]");
            }
            case "harbor_defense" -> {
                // Coastal battery: a stone bastion built out into the sea, crenels, a cannon aimed at the open water
                // and the flag; steps lead up from the shore.
                p.ground("stone_bricks");
                p.fill(0, 1, 1, 4, 3, 4, "stone_bricks"); p.fill(0, 1, 1, 4, 1, 4, "cobblestone");
                p.fill(1, 1, 0, 3, 1, 0, "stone_brick_stairs[facing=south,half=bottom]"); p.fill(1, 2, 1, 3, 2, 1, "stone_brick_stairs[facing=south,half=bottom]");
                p.fill(0, 4, 1, 4, 4, 4, "stone_brick_slab[type=bottom]");
                for (int[] c : new int[][]{{0, 1}, {0, 4}, {4, 1}, {4, 4}, {0, 3}, {4, 3}}) p.set(c[0], 4, c[1], "stone_bricks");
                p.set(0, 5, 4, "stone_brick_wall"); p.set(4, 5, 4, "stone_brick_wall"); p.set(0, 5, 1, "stone_brick_wall"); p.set(4, 5, 1, "stone_brick_wall");
                p.set(2, 4, 4, "polished_basalt[axis=z]"); p.set(2, 4, 3, "polished_basalt[axis=z]"); p.set(2, 4, 2, "dark_oak_slab[type=bottom]");
                p.set(1, 4, 3, "dark_oak_trapdoor[facing=east,half=bottom,open=true]"); p.set(3, 4, 3, "dark_oak_trapdoor[facing=west,half=bottom,open=true]");
                p.flag(1, 2, 9, "blue_wool");
                p.set(1, 3, 4, "chiseled_stone_bricks"); p.set(3, 3, 4, "chiseled_stone_bricks");
            }
            case "trade_union" -> {
                // Trade union: a brick guild hall, gable to the street, red banners over the entrance.
                p.ground("stone_bricks");
                int top = p.body(1, 1, 7, 7, 1, 2, 4, new String[]{"bricks"}, "stone_bricks", "stone_bricks", "glass_pane");
                p.door(4, 1, 1, "dark_oak", "south"); p.fill(3, 1, 1, 3, 3, 1, "chiseled_stone_bricks"); p.fill(5, 1, 1, 5, 3, 1, "chiseled_stone_bricks");
                p.set(4, 3, 1, "stone_bricks");
                p.set(2, 6, 0, "red_wall_banner[facing=north]"); p.set(6, 6, 0, "red_wall_banner[facing=north]");
                int r = p.roof(0, 0, 8, 8, top, "deepslate_tile", false, "bricks", 1, 1, 7, 7);
                p.set(4, top + 1, 1, "glass_pane"); p.set(4, top + 2, 1, "chiseled_stone_bricks");
                p.chimney(6, 6, top, r + 1, "bricks", false);
                p.lamp(0, 0, "dark_oak_fence"); p.lamp(8, 0, "dark_oak_fence");
            }
            case "power_plant" -> {
                // Power plant: a long brick turbine hall with tall smoking stacks and a coal heap.
                p.ground("stone_bricks");
                int top = p.body(0, 1, 6, 7, 1, 2, 4, new String[]{"bricks"}, "polished_blackstone_bricks", "polished_blackstone_bricks", "glass_pane");
                p.fill(2, 1, 1, 4, 3, 1, "air"); p.fill(2, 4, 1, 4, 4, 1, "polished_blackstone_brick_stairs[facing=north,half=top]");
                p.roof(0, 1, 6, 7, top, "deepslate_tile", false, "bricks", 0, 1, 6, 7);
                p.set(3, top + 4, 4, "lightning_rod[facing=up]");
                for (int[] c : new int[][]{{7, 2}, {7, 6}}) { p.fill(c[0], 1, c[1], c[0] + 1, 2, c[1] + 1, "polished_blackstone_bricks"); p.chimney(c[0], c[1], 3, 15, "bricks", true); }
                p.fill(7, 1, 4, 8, 1, 4, "coal_block"); p.set(8, 1, 0, "coal_block"); p.set(8, 2, 0, "coal_block"); p.set(7, 1, 0, "coal_block");
            }

            // ---------------------------------------------------------------- residences (7 x 7, same footprint for upgrades)
            case "residence" -> {
                // Farmers: a small timber cottage with a shingle roof, gable to the street, smoke from the hearth.
                p.ground("grass_block"); p.fill(1, 0, 1, 5, 0, 5, "cobblestone"); p.set(3, 0, 0, "dirt_path");
                int top = p.body(1, 1, 5, 5, 1, 1, 3, new String[]{"spruce_planks"}, "stripped_spruce_log", null, "glass_pane");
                p.door(3, 1, 1, "spruce", "south");
                p.roof(0, 0, 6, 6, top, "spruce", false, "spruce_planks", 1, 1, 5, 5);
                p.set(3, top + 1, 1, "glass_pane");
                p.chimney(2, 4, top, top + 2, "cobblestone", true);
                p.set(1, 1, 0, "poppy"); p.set(2, 1, 0, "dandelion"); p.set(4, 1, 0, "oxeye_daisy"); p.lamp(5, 0, "spruce_fence");
                p.logs(6, 2, 6, 4, 1, 1, "spruce_log", "z"); p.set(6, 2, 3, "spruce_log[axis=z]");
                p.set(0, 1, 5, "oak_leaves[persistent=true]"); p.set(0, 1, 2, "hay_block[axis=y]");
            }
            case "residence_2" -> {
                // Workers: a two-storey brick house with a slate roof, eaves to the street, hedge in front.
                p.ground("grass_block"); p.fill(1, 0, 1, 5, 0, 5, "stone_bricks"); p.set(3, 0, 0, "dirt_path");
                int top = p.body(1, 1, 5, 5, 1, 2, 3, new String[]{"bricks"}, "stone_bricks", "stone_bricks", "glass_pane");
                p.door(3, 1, 1, "spruce", "south");
                p.roof(1, 0, 5, 6, top, "deepslate_tile", true, "bricks", 1, 1, 5, 5);
                p.chimney(5, 3, top, top + 4, "bricks", true); p.chimney(1, 3, top, top + 3, "bricks", false);
                for (int x : new int[]{0, 1, 2, 4, 5, 6}) p.set(x, 1, 0, "azalea_leaves[persistent=true]");
                p.set(2, 3, 0, "spruce_trapdoor[facing=north,half=top,open=false]"); p.set(4, 3, 0, "spruce_trapdoor[facing=north,half=top,open=false]");
            }
            case "residence_3" -> {
                // Artisans: a three-storey plastered town house with stone quoins, shutters and a tiled gable.
                p.ground("grass_block"); p.fill(1, 0, 1, 5, 0, 5, "stone_bricks"); p.set(3, 0, 0, "stone_bricks");
                int top = p.body(1, 1, 5, 5, 1, 3, 3, new String[]{"stone_bricks", "smooth_sandstone"}, "polished_andesite", "stone_bricks", "glass_pane");
                p.door(3, 1, 1, "dark_oak", "south");
                for (int y : new int[]{5, 8}) for (int x : new int[]{1, 3, 5}) p.shutter(x, y, 0, "dark_oak", "north");
                int r = p.roof(0, 0, 6, 6, top, "brick", false, "smooth_sandstone", 1, 1, 5, 5);
                p.set(3, top + 1, 1, "glass_pane");
                p.chimney(4, 4, top, r + 1, "bricks", true);
                p.fence(0, 0, 6, 0, "dark_oak_fence", 3, 0); p.set(0, 2, 0, "lantern[hanging=false]"); p.set(6, 2, 0, "lantern[hanging=false]");
                p.set(1, 1, 6, "oak_leaves[persistent=true]"); p.set(5, 1, 6, "oak_leaves[persistent=true]");
            }
            case "residence_4" -> {
                // Engineers: a broad brick house on a stone ground floor, cornice, slate mansard with dormers and an iron balcony.
                p.ground("stone_bricks");
                int top = p.body(0, 1, 6, 6, 1, 3, 3, new String[]{"stone_bricks", "bricks"}, "stone_bricks", "stone_bricks", "glass_pane");
                p.door(3, 1, 1, "dark_oak", "south");
                p.fill(2, 3, 0, 4, 3, 0, "stone_brick_slab[type=top]"); p.fill(2, 4, 0, 4, 4, 0, "iron_bars"); p.set(3, 4, 1, "glass_pane"); p.set(3, 5, 1, "glass_pane");
                p.cornice(0, 1, 6, 6, top, "stone_brick");
                p.ring(1, 2, 5, 5, top, top + 1, "deepslate_tiles");
                for (int[] c : new int[][]{{2, 2}, {4, 2}, {2, 5}, {4, 5}}) p.set(c[0], top + 1, c[1], "glass_pane");
                int r = p.hip(1, 2, 5, 5, top + 2, "deepslate_tile", "deepslate_tiles");
                p.chimney(1, 4, top + 2, r + 2, "bricks", true); p.chimney(5, 3, top + 2, r + 1, "bricks", false);
                p.set(0, 1, 0, "polished_blackstone_wall"); p.set(0, 2, 0, "polished_blackstone_wall"); p.set(0, 3, 0, "lantern[hanging=false]");
                p.set(6, 1, 0, "azalea_leaves[persistent=true]"); p.set(5, 1, 0, "flowering_azalea_leaves[persistent=true]");
            }
            case "residence_5" -> {
                // Investors: a white stone villa with a columned porch, a corner tower and green copper roofs.
                p.ground("grass_block"); p.fill(0, 0, 1, 6, 0, 6, "smooth_quartz"); p.fill(2, 0, 0, 4, 0, 0, "smooth_quartz");
                int top = p.body(0, 1, 6, 6, 1, 3, 3, new String[]{"quartz_bricks", "calcite"}, "quartz_pillar", "smooth_quartz", "glass_pane");
                p.door(3, 1, 1, "dark_oak", "south");
                p.fill(2, 1, 0, 2, 2, 0, "quartz_pillar[axis=y]"); p.fill(4, 1, 0, 4, 2, 0, "quartz_pillar[axis=y]");
                p.fill(2, 3, 0, 4, 3, 0, "smooth_quartz"); p.fill(2, 4, 0, 4, 4, 0, "iron_bars"); p.set(3, 4, 1, "glass_pane"); p.set(3, 5, 1, "glass_pane");
                p.cornice(0, 1, 6, 6, top, "quartz");
                p.hip(1, 2, 5, 5, top + 1, "waxed_oxidized_cut_copper", "waxed_oxidized_cut_copper");
                p.ring(4, 1, 6, 3, top, top + 2, "calcite"); p.set(5, top + 1, 1, "glass_pane"); p.set(6, top + 1, 2, "glass_pane");
                int r = p.hip(4, 1, 6, 3, top + 3, "waxed_oxidized_cut_copper", "waxed_oxidized_cut_copper");
                p.set(5, r + 1, 2, "gold_block"); p.set(5, r + 2, 2, "lightning_rod[facing=up]");
                p.chimney(1, 4, top + 1, top + 3, "quartz_bricks", false);
                for (int x : new int[]{0, 1, 5, 6}) p.set(x, 1, 0, x % 5 == 0 ? "flowering_azalea_leaves[persistent=true]" : "azalea_leaves[persistent=true]");
            }

            // ---------------------------------------------------------------- farmers' production
            case "fishery" -> {
                // Fishery: the fisherman's hut on the beach and a plank jetty on piles running out into the sea,
                // with drying racks, nets, fish barrels and a lantern at its end. Open water between the piles.
                p.ground("annocraft1800:packed_earth"); p.fill(0, 0, 3, w - 1, 0, d - 1, "air");
                p.fill(2, 0, 3, 4, 0, d - 1, "spruce_planks"); p.fill(1, 0, d - 1, 5, 0, d - 1, "spruce_planks");
                int top = p.body(0, 0, 3, 2, 1, 1, 3, new String[]{"spruce_planks"}, "stripped_spruce_log", null, "glass_pane");
                p.door(2, 1, 0, "spruce", "south");
                p.roof(0, 0, 3, 2, top, "spruce", false, "spruce_planks", 0, 0, 3, 2);
                p.chimney(1, 2, top, top + 2, "cobblestone", true);
                // Drying rack with nets on the beach.
                p.fill(5, 1, 0, 5, 2, 0, "spruce_fence"); p.fill(5, 1, 2, 5, 2, 2, "spruce_fence"); p.fill(5, 3, 0, 5, 3, 2, "spruce_fence");
                p.set(5, 2, 1, "cobweb"); p.set(6, 1, 1, "dried_kelp_block"); p.set(4, 1, 2, "barrel[facing=up,open=false]");
                // Jetty railings, the catch and the lantern at the end.
                for (int z = 3; z < d - 1; z += 2) { p.set(2, 1, z, "spruce_fence"); p.set(4, 1, z, "spruce_fence"); }
                p.set(1, 1, d - 1, "barrel[facing=up,open=false]"); p.set(5, 1, d - 1, "spruce_fence"); p.set(5, 2, d - 1, "lantern[hanging=false]");
                p.set(3, 1, d - 2, "dried_kelp_block");
            }
            case "lumberjack" -> {
                // Lumberjack's hut: a log cabin with a log pile, a chopping stump and a tree at hand.
                p.ground("podzol");
                int top = p.body(0, 2, 2, 4, 1, 1, 3, new String[]{"oak_log[axis=y]"}, "oak_log", null, "glass_pane");
                p.door(1, 1, 2, "oak", "south");
                p.roof(0, 1, 2, 4, top, "spruce", false, "spruce_planks", 0, 2, 2, 4);
                p.logs(3, 2, 4, 4, 1, 1, "oak_log", "z"); p.set(3, 2, 3, "oak_log[axis=z]"); p.set(4, 2, 3, "oak_log[axis=z]");
                p.set(1, 1, 0, "oak_log[axis=y]"); p.set(0, 1, 0, "oak_slab[type=bottom]");
                p.tree(4, 0, 1, "spruce_log", "spruce_leaves", 3);
            }
            case "sawmill" -> {
                // Sawmill: a half-timbered hall open to the yard, the saw at work, logs in and planks out.
                p.ground("coarse_dirt"); p.fill(0, 0, 1, 5, 0, 6, "spruce_planks");
                int top = p.body(0, 1, 5, 6, 1, 1, 4, new String[]{"oak_planks"}, "stripped_dark_oak_log", null, "glass_pane");
                p.fill(1, 1, 1, 4, 3, 1, "air"); p.set(0, 4, 1, "stripped_dark_oak_log[axis=y]");
                p.fill(1, 4, 1, 4, 4, 1, "stripped_dark_oak_log[axis=x]");
                p.roof(0, 0, 5, 7, top + 1, "spruce", true, "oak_planks", 0, 1, 5, 6);
                p.fill(0, top, 1, 5, top, 6, "oak_planks"); p.ring(0, 1, 5, 6, top, top, "stripped_dark_oak_log[axis=x]");
                p.set(2, 1, 2, "stonecutter[facing=north]"); p.set(3, 1, 2, "stripped_oak_log[axis=x]"); p.set(1, 1, 2, "oak_slab[type=top]");
                p.logs(6, 1, 8, 2, 1, 1, "oak_log", "x"); p.set(7, 2, 1, "oak_log[axis=x]");
                p.fill(6, 1, 4, 8, 1, 5, "oak_planks"); p.fill(6, 2, 4, 8, 2, 4, "oak_slab[type=bottom]"); p.fill(7, 2, 5, 8, 2, 5, "oak_planks");
                p.chimney(4, 5, top + 1, top + 5, "cobblestone", false);
            }
            case "sheep_farm" -> {
                // Sheep farm: a fenced pasture with a shearing shed, wool bales and hay racks.
                p.ground("grass_block");
                p.fence(0, 0, 8, 8, "oak_fence", 4, 0);
                for (int x = 1; x < 8; x++) for (int z = 1; z < 8; z++) if ((x * 5 + z * 3) % 7 == 0) p.set(x, 1, z, "grass");
                int top = p.body(5, 5, 8, 8, 1, 1, 3, new String[]{"spruce_planks"}, "stripped_spruce_log", null, null);
                p.fill(6, 1, 5, 7, 2, 5, "air");
                p.roof(4, 5, 8, 8, top, "spruce", false, "spruce_planks", 5, 5, 8, 8);
                p.set(6, 1, 4, "white_wool"); p.set(7, 1, 4, "white_wool"); p.set(7, 2, 4, "white_wool"); p.set(6, 1, 6, "white_wool");
                p.set(2, 1, 6, "hay_block[axis=y]"); p.set(1, 1, 6, "hay_block[axis=y]"); p.set(1, 1, 7, "hay_block[axis=x]");
                p.set(2, 1, 2, "cauldron"); p.set(1, 2, 1, "lantern[hanging=false]");
                p.set(3, 1, 3, "white_wool"); p.set(4, 1, 3, "white_wool"); p.set(5, 1, 3, "light_gray_wool");
                p.set(2, 1, 4, "white_wool"); p.set(1, 1, 4, "gray_wool");
            }
            case "knitter" -> {
                // Framework knitters: a white half-timbered workshop, the frame loom inside the open door.
                p.ground("grass_block"); p.fill(1, 0, 1, 5, 0, 5, "cobblestone"); p.set(3, 0, 0, "dirt_path");
                int top = p.body(1, 1, 5, 5, 1, 2, 3, new String[]{"white_terracotta"}, "stripped_dark_oak_log", "stripped_dark_oak_log", "glass_pane");
                p.fill(3, 1, 1, 3, 2, 1, "air"); p.set(3, 1, 2, "loom[facing=north]");
                for (int z : new int[]{1, 5}) p.fill(3, 4, z, 3, 6, z, "stripped_dark_oak_log[axis=y]");
                for (int x : new int[]{1, 5}) p.fill(x, 4, 3, x, 6, 3, "stripped_dark_oak_log[axis=y]");
                p.roof(0, 0, 6, 6, top, "dark_oak", true, "white_terracotta", 1, 1, 5, 5);
                p.chimney(4, 4, top, top + 3, "bricks", true);
                p.set(0, 1, 1, "white_wool"); p.set(0, 1, 2, "white_wool"); p.set(0, 2, 1, "white_wool"); p.set(6, 1, 1, "cyan_wool");
                p.cart(4, 0, "white_wool");
            }
            case "potato_farm" -> {
                // Potato farm: furrowed potato beds, a little farmhouse and a scarecrow.
                p.ground("grass_block");
                p.field(0, 3, 8, 8, "potatoes[age=7]", "farmland[moisture=7]", false);
                int top = p.body(0, 0, 2, 2, 1, 1, 3, new String[]{"spruce_planks"}, "stripped_spruce_log", null, "glass_pane");
                p.door(1, 1, 0, "spruce", "south");
                p.roof(0, 0, 2, 2, top, "spruce", true, "spruce_planks", 0, 0, 2, 2);
                p.set(4, 0, 4, "dirt_path"); p.set(4, 1, 4, "spruce_fence"); p.set(4, 2, 4, "hay_block[axis=y]"); p.set(4, 3, 4, "carved_pumpkin[facing=north]");
                p.set(4, 1, 0, "brown_wool"); p.set(5, 1, 0, "brown_wool"); p.set(5, 2, 0, "brown_wool"); p.cart(6, 1, "brown_wool");
            }
            case "distillery" -> {
                // Schnapps distillery: a stone and timber still-house, copper stills, kegs and a smoking chimney.
                p.ground("coarse_dirt"); p.fill(0, 0, 1, 4, 0, 5, "cobblestone");
                int top = p.body(0, 1, 4, 5, 1, 2, 3, new String[]{"cobblestone", "spruce_planks"}, "stripped_spruce_log", "stripped_spruce_log", "glass_pane");
                p.door(2, 1, 1, "spruce", "south");
                p.roof(0, 0, 4, 6, top, "deepslate_tile", true, "spruce_planks", 0, 1, 4, 5);
                p.chimney(3, 3, 1, top + 3, "bricks", true);
                p.still(5, 2); p.still(5, 4);
                p.kegs(5, 6, 6, 6, 1, 1); p.set(6, 2, 6, "stripped_spruce_log[axis=x]");
                p.set(1, 1, 0, "stripped_spruce_log[axis=z]"); p.set(3, 1, 0, "stripped_spruce_log[axis=z]");
            }
            case "pub" -> {
                // Pub: a half-timbered tavern with a hanging sign, a beer garden and kegs.
                p.ground("grass_block"); p.fill(1, 0, 2, 5, 0, 6, "cobblestone"); p.fill(0, 0, 0, 6, 0, 1, "stone_bricks");
                int top = p.body(1, 2, 5, 6, 1, 2, 3, new String[]{"bricks", "white_terracotta"}, "stripped_dark_oak_log", "stripped_dark_oak_log", "glass_pane");
                p.door(3, 1, 2, "dark_oak", "south");
                p.roof(0, 2, 6, 6, top, "dark_oak", false, "white_terracotta", 1, 2, 5, 6);
                p.set(3, top + 1, 2, "glass_pane");
                p.set(2, 4, 1, "red_wall_banner[facing=north]");
                p.chimney(5, 5, top, top + 3, "bricks", true);
                p.set(5, 1, 0, "dark_oak_fence"); p.set(5, 2, 0, "spruce_pressure_plate"); p.set(4, 1, 0, "spruce_stairs[facing=east,half=bottom]"); p.set(6, 1, 0, "spruce_stairs[facing=west,half=bottom]");
                p.kegs(0, 1, 0, 0, 1, 1); p.set(0, 2, 1, "stripped_spruce_log[axis=x]"); p.lamp(2, 0, "dark_oak_fence");
            }

            // ---------------------------------------------------------------- workers' production
            case "pig_farm" -> {
                // Pig farm: a muddy pen with a sty, a trough and straw.
                p.ground("grass_block");
                p.fill(1, 0, 1, 7, 0, 7, "mud"); p.fence(0, 0, 8, 8, "spruce_fence", 4, 0);
                int top = p.body(5, 5, 8, 8, 1, 1, 3, new String[]{"spruce_planks"}, "stripped_spruce_log", null, null);
                p.fill(6, 1, 5, 7, 2, 5, "air");
                p.roof(5, 4, 8, 8, top, "spruce", true, "spruce_planks", 5, 5, 8, 8);
                p.set(2, 1, 6, "cauldron"); p.set(3, 1, 6, "cauldron");
                p.set(1, 1, 1, "hay_block[axis=x]"); p.set(2, 1, 1, "hay_block[axis=x]"); p.set(4, 0, 3, "coarse_dirt"); p.set(3, 0, 4, "coarse_dirt");
                p.set(3, 1, 2, "pink_terracotta"); p.set(4, 1, 2, "pink_terracotta");
            }
            case "slaughterhouse" -> {
                // Slaughterhouse: a red brick butchery with a porch of hooks and a smoking chimney.
                p.ground("stone_bricks");
                int top = p.body(0, 2, 6, 6, 1, 2, 3, new String[]{"red_terracotta", "bricks"}, "stone_bricks", "stone_bricks", "glass_pane");
                p.door(3, 1, 2, "spruce", "south");
                p.roof(0, 2, 6, 6, top, "deepslate_tile", true, "bricks", 0, 2, 6, 6);
                p.posts(1, 0, 5, 1, 1, 3, "stripped_spruce_log[axis=y]"); p.fill(1, 4, 0, 5, 4, 1, "spruce_slab[type=bottom]");
                p.set(2, 3, 0, "chain[axis=y]"); p.set(4, 3, 0, "chain[axis=y]"); p.set(2, 2, 0, "lantern[hanging=true]");
                p.chimney(5, 5, top, top + 3, "bricks", true);
                p.kegs(0, 0, 0, 1, 1, 1);
            }
            case "grain_farm" -> {
                // Grain farm: golden wheat fields, a big barn and hay stooks.
                p.ground("grass_block");
                p.field(0, 4, 8, 8, "wheat[age=7]", "farmland[moisture=7]", false);
                int top = p.body(0, 0, 4, 3, 1, 1, 4, new String[]{"spruce_planks"}, "stripped_dark_oak_log", null, null);
                p.fill(1, 1, 0, 3, 3, 0, "air");
                p.roof(0, 0, 4, 3, top, "dark_oak", false, "spruce_planks", 0, 0, 4, 3);
                p.set(6, 1, 1, "hay_block[axis=y]"); p.set(7, 1, 1, "hay_block[axis=y]"); p.set(6, 2, 1, "hay_block[axis=y]"); p.set(7, 1, 2, "hay_block[axis=z]");
                p.cart(5, 3, "hay_block[axis=y]");
            }
            case "flour_mill" -> {
                // Flour mill: Anno's windmill, a stone and timber tower with a cap and four sails to the front.
                p.ground("grass_block"); p.fill(1, 0, 1, 5, 0, 5, "cobblestone");
                p.ring(1, 1, 5, 5, 1, 3, "stone_bricks");
                p.ring(1, 1, 5, 5, 4, 6, "spruce_planks"); p.posts(1, 1, 5, 5, 4, 6, "stripped_spruce_log[axis=y]");
                p.ring(2, 2, 4, 4, 7, 7, "spruce_planks");
                p.door(3, 1, 5, "spruce", "north"); p.set(1, 2, 3, "glass_pane"); p.set(5, 2, 3, "glass_pane"); p.set(3, 5, 1, "glass_pane");
                int r = p.hip(1, 1, 5, 5, 8, "spruce", "spruce_planks");
                p.set(3, 7, 0, "stripped_spruce_log[axis=z]"); p.set(3, 7, 1, "stripped_spruce_log[axis=z]");
                for (int k = 1; k <= 3; k++) {
                    p.set(3, 7 + k, 0, "spruce_fence"); p.set(4, 7 + k, 0, "white_wool");
                    p.set(3 + k, 7, 0, "spruce_fence"); p.set(3 + k, 6, 0, "white_wool");
                    p.set(3, 7 - k, 0, "spruce_fence"); p.set(2, 7 - k, 0, "white_wool");
                    p.set(3 - k, 7, 0, "spruce_fence"); p.set(3 - k, 8, 0, "white_wool");
                }
                p.set(6, 1, 0, "white_wool"); p.set(6, 1, 1, "white_wool"); p.set(0, 1, 6, "hay_block[axis=y]");
            }
            case "bakery" -> {
                // Bakery: a brick bakehouse with a big oven chimney and a bread counter under an awning.
                p.ground("stone_bricks");
                int top = p.body(0, 1, 6, 6, 1, 2, 3, new String[]{"bricks", "yellow_terracotta"}, "stripped_dark_oak_log", "stripped_dark_oak_log", "glass_pane");
                p.door(3, 1, 1, "spruce", "south"); p.fill(1, 2, 1, 2, 2, 1, "glass_pane"); p.fill(4, 2, 1, 5, 2, 1, "glass_pane");
                p.fill(1, 3, 0, 5, 3, 0, "red_wool"); p.set(2, 3, 0, "white_wool"); p.set(4, 3, 0, "white_wool");
                p.set(1, 1, 0, "spruce_slab[type=top]"); p.set(5, 1, 0, "spruce_slab[type=top]"); p.set(1, 2, 0, "hay_block[axis=x]");
                p.roof(0, 0, 6, 7, top, "brick", true, "yellow_terracotta", 0, 1, 6, 6);
                p.fill(5, 1, 5, 5, top + 3, 5, "bricks"); p.set(5, top + 4, 5, "campfire[lit=true,signal_fire=false,facing=north]");
                p.set(0, 1, 6, "bricks"); p.set(0, 2, 6, "brick_slab[type=bottom]");
            }
            case "rendering_works" -> {
                // Rendering works: a grey boiling house with tallow vats and a smoking stack.
                p.ground("stone_bricks");
                int top = p.body(0, 2, 4, 6, 1, 2, 3, new String[]{"light_gray_terracotta"}, "stone_bricks", "stone_bricks", "glass_pane");
                p.door(2, 1, 2, "spruce", "south");
                p.roof(0, 2, 4, 6, top, "deepslate_tile", false, "light_gray_terracotta", 0, 2, 4, 6);
                p.chimney(6, 6, 1, top + 4, "bricks", true);
                p.set(5, 1, 1, "cauldron"); p.set(6, 1, 1, "cauldron"); p.set(5, 1, 3, "cauldron");
                p.fill(5, 1, 5, 6, 1, 5, "bricks"); p.set(6, 1, 0, "yellow_terracotta"); p.set(0, 1, 0, "stripped_spruce_log[axis=x]");
            }
            case "soap_factory" -> {
                // Soap factory: a plastered workshop with a boiling chimney and stacks of soap bars.
                p.ground("stone_bricks");
                int top = p.body(0, 1, 5, 6, 1, 2, 3, new String[]{"stone_bricks", "white_terracotta"}, "stripped_dark_oak_log", "stripped_dark_oak_log", "glass_pane");
                p.door(2, 1, 1, "dark_oak", "south");
                p.roof(0, 0, 5, 6, top, "dark_prismarine", true, "white_terracotta", 0, 1, 5, 6);
                p.chimney(4, 4, 1, top + 4, "bricks", true);
                p.fill(6, 1, 1, 6, 1, 3, "smooth_quartz_slab[type=bottom]"); p.set(6, 1, 2, "pink_terracotta"); p.set(6, 2, 2, "smooth_quartz_slab[type=bottom]");
                p.set(4, 1, 0, "cauldron");
            }
            case "hop_farm" -> {
                // Hop garden: tall poles strung with climbing hop vines, and an oast house with its white cowl.
                p.ground("grass_block");
                for (int x = 0; x <= 8; x += 2) for (int z = 3; z <= 8; z += 1) {
                    p.set(x, 0, z, "rooted_dirt");
                    if (z % 3 == 0) p.fill(x, 1, z, x, 4, z, "spruce_fence");
                    else p.fill(x, 1, z, x, 2 + (x + z) % 2, z, "oak_leaves[persistent=true]");
                }
                for (int x = 0; x <= 8; x += 2) { p.set(x, 5, 3, "spruce_fence"); p.set(x, 5, 6, "spruce_fence"); }
                p.ring(5, 0, 7, 2, 1, 4, "bricks"); p.door(6, 1, 2, "spruce", "north");
                p.hip(5, 0, 7, 2, 5, "brick", "bricks"); p.set(6, 7, 1, "white_terracotta"); p.set(6, 8, 1, "white_concrete");
                p.body(0, 0, 3, 1, 1, 1, 3, new String[]{"white_terracotta"}, "stripped_dark_oak_log", null, null);
                p.roof(0, 0, 3, 1, 4, "dark_oak", true, "white_terracotta", 0, 0, 3, 1);
            }
            case "brewery" -> {
                // Brewery: a large brick brewhouse with a copper kettle dome, a tall stack and kegs in the yard.
                p.ground("stone_bricks");
                int top = p.body(0, 1, 6, 6, 1, 2, 4, new String[]{"bricks"}, "stone_bricks", "stone_bricks", "glass_pane");
                p.fill(2, 1, 1, 4, 3, 1, "air"); p.fill(2, 4, 1, 4, 4, 1, "stone_brick_stairs[facing=north,half=top]");
                int r = p.roof(0, 0, 6, 7, top, "deepslate_tile", true, "bricks", 0, 1, 6, 6);
                p.fill(2, r - 1, 3, 4, r, 4, "waxed_cut_copper"); p.set(3, r + 1, 3, "waxed_cut_copper_slab[type=bottom]"); p.set(3, r + 1, 4, "lightning_rod[facing=up]");
                p.chimney(6, 6, top, top + 6, "bricks", true);
                p.kegs(7, 1, 8, 2, 1, 1); p.set(7, 2, 1, "stripped_spruce_log[axis=x]"); p.kegs(7, 4, 8, 4, 1, 2);
                p.set(8, 1, 6, "hay_block[axis=y]");
            }
            case "school" -> {
                // School: a red brick schoolhouse with tall windows and a little bell tower on the ridge.
                p.ground("grass_block"); p.fill(1, 0, 1, 5, 0, 6, "stone_bricks"); p.set(3, 0, 0, "dirt_path");
                int top = p.body(1, 1, 5, 6, 1, 1, 5, new String[]{"bricks"}, "stone_bricks", null, null);
                for (int z : new int[]{2, 4}) { p.fill(1, 2, z, 1, 3, z, "glass_pane"); p.fill(5, 2, z, 5, 3, z, "glass_pane"); }
                p.fill(2, 2, 6, 2, 3, 6, "glass_pane"); p.fill(4, 2, 6, 4, 3, 6, "glass_pane");
                p.door(3, 1, 1, "spruce", "south"); p.set(3, 3, 1, "glass_pane"); p.set(2, 4, 1, "stone_bricks"); p.set(4, 4, 1, "stone_bricks");
                int r = p.roof(0, 0, 6, 7, top, "deepslate_tile", false, "bricks", 1, 1, 5, 6);
                p.posts(2, 2, 4, 4, r + 1, r + 2, "spruce_fence"); p.set(3, r + 2, 3, "bell[attachment=ceiling,facing=north]");
                p.hip(2, 2, 4, 4, r + 3, "deepslate_tile", "deepslate_tiles");
                p.set(3, r + 1, 3, "spruce_planks");
                p.fill(0, 1, 2, 0, 1, 5, "oak_fence"); p.set(6, 1, 3, "oak_leaves[persistent=true]");
            }
            case "church" -> {
                // Church: a stone nave with tall windows and a slate roof, the bell tower and spire over the porch.
                p.ground("stone_bricks"); p.set(3, 0, 0, "polished_andesite");
                int top = p.body(1, 2, 5, 6, 1, 1, 6, new String[]{"stone_bricks"}, "chiseled_stone_bricks", null, null);
                for (int z : new int[]{3, 5}) { p.fill(1, 2, z, 1, 4, z, "light_blue_stained_glass_pane"); p.fill(5, 2, z, 5, 4, z, "light_blue_stained_glass_pane"); }
                p.roof(0, 2, 6, 6, top, "deepslate_tile", false, "stone_bricks", 1, 2, 5, 6);
                int t = p.body(2, 0, 4, 2, 1, 1, 10, new String[]{"stone_bricks"}, "chiseled_stone_bricks", null, null);
                p.door(3, 1, 0, "dark_oak", "south"); p.set(3, 3, 0, "stone_brick_stairs[facing=north,half=top]");
                p.set(3, 5, 0, "light_blue_stained_glass_pane");
                p.fill(3, 8, 0, 3, 9, 0, "air"); p.fill(2, 8, 1, 2, 9, 1, "air"); p.fill(4, 8, 1, 4, 9, 1, "air"); p.fill(3, 8, 2, 3, 9, 2, "air");
                p.fill(3, 10, 1, 3, 10, 1, "stone_bricks"); p.set(3, 9, 1, "bell[attachment=ceiling,facing=north]");
                p.cornice(2, 0, 4, 2, t, "stone_brick");
                int s = p.hip(2, 0, 4, 2, t + 1, "deepslate_tile", "deepslate_tiles");
                p.fill(3, s + 1, 1, 3, s + 2, 1, "deepslate_tile_wall"); p.set(3, s + 3, 1, "lightning_rod[facing=up]");
            }
            case "clay_pit" -> {
                // Clay pit: wet clay diggings in terraces, a plank ramp, a wheelbarrow and a small tool shed.
                p.ground("mud");
                p.fill(0, 0, 3, 6, 0, 6, "clay"); p.ring(0, 3, 6, 6, 1, 1, "packed_mud"); p.ring(0, 5, 6, 6, 2, 2, "packed_mud");
                p.fill(1, 1, 4, 5, 1, 5, "air"); p.set(3, 1, 3, "spruce_slab[type=bottom]");
                p.set(2, 1, 4, "clay"); p.set(4, 1, 5, "clay");
                p.body(0, 0, 2, 2, 1, 1, 3, new String[]{"spruce_planks"}, "stripped_spruce_log", null, null);
                p.fill(1, 1, 2, 1, 2, 2, "air"); p.roof(0, 0, 2, 2, 4, "spruce", true, "spruce_planks", 0, 0, 2, 2);
                p.set(4, 1, 1, "clay"); p.set(5, 1, 1, "clay"); p.set(5, 2, 1, "clay"); p.cart(4, 0, "clay");
            }
            case "brickworks" -> {
                // Brick factory: a long kiln with fire arches, a tall chimney, and stacks of fresh bricks.
                p.ground("coarse_dirt");
                p.fill(0, 1, 1, 6, 3, 5, "bricks");
                for (int x = 1; x <= 5; x += 2) { p.set(x, 1, 1, "magma_block"); p.set(x, 2, 1, "air"); p.set(x, 1, 5, "air"); p.set(x, 2, 5, "air"); }
                p.roof(0, 1, 6, 5, 4, "brick", true, "bricks", 0, 1, 6, 5);
                p.chimney(3, 3, 4, 13, "bricks", true); p.ring(2, 2, 4, 4, 4, 5, "bricks");
                p.fill(7, 1, 0, 8, 1, 1, "bricks"); p.fill(7, 2, 0, 8, 2, 0, "brick_slab[type=bottom]"); p.fill(7, 1, 3, 8, 2, 4, "bricks"); p.set(8, 3, 4, "brick_slab[type=bottom]");
                p.posts(7, 6, 8, 6, 1, 2, "spruce_fence"); p.fill(6, 3, 6, 8, 3, 6, "spruce_slab[type=bottom]"); p.fill(7, 1, 6, 7, 1, 6, "clay");
            }
            case "iron_mine" -> p.mine("iron_ore", "stone");
            case "coal_mine" -> p.mine("coal_ore", "stone");
            case "gold_mine" -> p.mine("gold_ore", "granite");
            case "charcoal_kiln" -> {
                // Charcoal kiln: a smoking earth mound over stacked wood, the charcoal burner's hut beside it.
                p.ground("podzol");
                p.fill(1, 1, 1, 3, 1, 3, "coarse_dirt"); p.fill(1, 2, 2, 3, 2, 2, "podzol"); p.fill(2, 2, 1, 2, 2, 3, "podzol");
                p.set(1, 1, 1, "mud"); p.set(3, 1, 3, "mud"); p.set(2, 3, 2, "campfire[lit=true,signal_fire=false,facing=north]");
                p.logs(0, 4, 2, 4, 1, 1, "oak_log", "x"); p.set(1, 2, 4, "oak_log[axis=x]");
                p.set(4, 1, 0, "coal_block"); p.set(4, 1, 1, "coal_block"); p.set(4, 2, 0, "coal_block");
                p.set(4, 1, 4, "spruce_fence"); p.set(4, 2, 4, "lantern[hanging=false]");
            }
            case "furnace" -> {
                // Furnace: a tapering stone blast furnace glowing at its mouth, with a charging ramp and ore heaps.
                p.ground("stone_bricks");
                p.fill(1, 1, 2, 5, 5, 6, "deepslate_bricks"); p.fill(2, 6, 3, 4, 8, 5, "deepslate_bricks"); p.ring(2, 3, 4, 5, 9, 9, "deepslate_bricks");
                p.set(3, 9, 4, "campfire[lit=true,signal_fire=false,facing=north]");
                p.fill(2, 1, 2, 4, 2, 2, "air"); p.set(3, 1, 3, "magma_block"); p.set(2, 1, 3, "magma_block"); p.set(4, 1, 3, "magma_block");
                p.fill(2, 3, 2, 4, 3, 2, "deepslate_brick_stairs[facing=north,half=top]");
                p.cornice(1, 2, 5, 6, 5, "deepslate_brick"); p.cornice(2, 3, 4, 5, 8, "deepslate_brick");
                for (int k = 0; k < 5; k++) p.set(6, 1 + k, 6 - k, "spruce_stairs[facing=north,half=bottom]");
                p.set(0, 1, 0, "iron_ore"); p.set(1, 1, 0, "raw_iron_block"); p.set(5, 1, 0, "coal_block"); p.set(6, 1, 0, "coal_block"); p.set(6, 2, 0, "coal_block");
            }
            case "steelworks" -> {
                // Steelworks: a huge dark hall with a saw-tooth glass roof, two stacks and girders in the yard.
                p.ground("stone_bricks");
                int top = p.body(0, 1, 6, 6, 1, 1, 6, new String[]{"polished_blackstone_bricks"}, "bricks", null, null);
                for (int x = 1; x <= 5; x += 2) { p.fill(x, 2, 1, x, 4, 1, "glass_pane"); p.fill(x, 2, 6, x, 4, 6, "glass_pane"); }
                p.fill(2, 1, 1, 4, 3, 1, "air"); p.set(3, 1, 3, "magma_block");
                for (int z = 1; z <= 6; z += 2) { p.fill(0, top, z, 6, top, z, "deepslate_tile_stairs[facing=south,half=bottom]"); p.fill(0, top, z + 1, 6, top, z + 1, "glass"); }
                p.fill(0, top, 6, 6, top, 6, "deepslate_tiles");
                p.chimney(7, 2, 1, 14, "bricks", true); p.chimney(7, 5, 1, 12, "bricks", true);
                p.fill(7, 1, 0, 8, 1, 0, "iron_bars"); p.set(8, 1, 3, "iron_block"); p.fill(8, 1, 6, 8, 2, 6, "iron_bars");
            }
            case "weapon_factory" -> {
                // Weapon factory: a dark brick armoury with a smoking forge and a cannon displayed by the door.
                p.ground("stone_bricks");
                int top = p.body(0, 2, 6, 6, 1, 2, 3, new String[]{"deepslate_bricks"}, "polished_blackstone_bricks", "polished_blackstone_bricks", "glass_pane");
                p.door(3, 1, 2, "dark_oak", "south"); p.set(2, 3, 1, "red_wall_banner[facing=north]"); p.set(4, 3, 1, "red_wall_banner[facing=north]");
                p.set(2, 3, 1, "red_wall_banner[facing=north]"); p.set(4, 3, 1, "red_wall_banner[facing=north]");
                p.set(2, 3, 2, "deepslate_bricks"); p.set(4, 3, 2, "deepslate_bricks");
                p.roof(0, 2, 6, 6, top, "dark_prismarine", true, "deepslate_bricks", 0, 2, 6, 6);
                p.chimney(5, 5, top, top + 4, "bricks", true);
                p.set(1, 1, 0, "polished_basalt[axis=z]"); p.set(1, 1, 1, "polished_basalt[axis=z]");
                p.set(0, 1, 1, "dark_oak_trapdoor[facing=west,half=bottom,open=true]"); p.set(2, 1, 1, "dark_oak_trapdoor[facing=east,half=bottom,open=true]");
                p.set(5, 1, 0, "coal_block"); p.set(6, 1, 0, "iron_block");
            }
            case "sailmakers" -> {
                // Sailmakers: a long timber loft with fresh sails stretched on frames to dry.
                p.ground("grass_block"); p.fill(0, 0, 3, 6, 0, 6, "spruce_planks");
                int top = p.body(0, 3, 6, 6, 1, 1, 4, new String[]{"oak_planks"}, "stripped_spruce_log", null, "glass_pane");
                p.fill(2, 1, 3, 4, 3, 3, "air");
                p.roof(0, 3, 6, 6, top, "spruce", true, "oak_planks", 0, 3, 6, 6);
                p.fill(0, 1, 1, 0, 4, 1, "spruce_fence"); p.fill(6, 1, 1, 6, 4, 1, "spruce_fence"); p.fill(0, 5, 1, 6, 5, 1, "spruce_fence");
                p.fill(1, 2, 1, 5, 4, 1, "white_wool"); p.fill(2, 1, 1, 4, 1, 1, "white_wool"); p.set(3, 4, 1, "light_gray_wool");
                p.set(3, 1, 4, "loom[facing=north]");
            }

            // ---------------------------------------------------------------- artisans
            case "cattle_farm" -> {
                // Cattle farm: a red barn with a hayloft door, a fenced pasture and hay.
                p.ground("grass_block"); p.fence(0, 0, 8, 8, "spruce_fence", 4, 0);
                for (int x = 1; x < 8; x++) for (int z = 1; z < 5; z++) if ((x * 3 + z * 5) % 6 == 0) p.set(x, 1, z, "grass");
                int top = p.body(1, 5, 7, 8, 1, 1, 4, new String[]{"mangrove_planks"}, "stripped_dark_oak_log", null, null);
                p.fill(3, 1, 5, 5, 3, 5, "air"); p.set(4, 5, 5, "white_terracotta");
                p.roof(0, 5, 8, 8, top, "deepslate_tile", false, "mangrove_planks", 1, 5, 7, 8);
                p.set(4, top + 1, 5, "spruce_trapdoor[facing=north,half=bottom,open=true]");
                p.fill(1, 1, 1, 2, 1, 1, "hay_block[axis=x]"); p.set(1, 2, 1, "hay_block[axis=x]"); p.set(6, 1, 3, "cauldron");
                p.set(3, 1, 2, "brown_terracotta"); p.set(4, 1, 2, "brown_terracotta"); p.set(5, 1, 2, "white_terracotta");
            }
            case "cannery" -> {
                // Cannery: a two-storey brick factory with a stack, crates of tins by the loading door.
                p.ground("stone_bricks");
                int top = p.body(0, 1, 6, 6, 1, 2, 3, new String[]{"bricks"}, "polished_blackstone_bricks", "light_gray_terracotta", "glass_pane");
                p.fill(2, 1, 1, 4, 2, 1, "air");
                p.roof(0, 0, 6, 7, top, "deepslate_tile", true, "bricks", 0, 1, 6, 6);
                p.chimney(5, 4, 1, top + 5, "bricks", true);
                p.fill(7, 1, 1, 8, 1, 2, "spruce_planks"); p.set(7, 2, 1, "iron_block"); p.set(8, 2, 2, "spruce_planks"); p.set(8, 1, 5, "iron_block");
                p.crane(8, 4, 5, 6);
            }
            case "sewing_machine_factory" -> {
                // Sewing machine factory: a brick workshop under a saw-tooth glass roof, with its office front.
                p.ground("stone_bricks");
                int top = p.body(0, 1, 6, 6, 1, 1, 5, new String[]{"bricks"}, "polished_andesite", null, null);
                for (int x = 1; x <= 5; x += 2) { p.fill(x, 2, 1, x, 3, 1, "glass_pane"); p.fill(x, 2, 6, x, 3, 6, "glass_pane"); }
                p.door(3, 1, 1, "dark_oak", "south");
                for (int z = 1; z <= 6; z += 2) { p.fill(0, top, z, 6, top, z, "deepslate_tile_stairs[facing=south,half=bottom]"); p.fill(0, top, z + 1, 6, top, z + 1, "glass"); }
                p.fill(0, top + 1, 2, 6, top + 1, 2, "deepslate_tile_slab[type=bottom]"); p.fill(0, top + 1, 4, 6, top + 1, 4, "deepslate_tile_slab[type=bottom]");
                p.chimney(8, 5, 1, top + 5, "bricks", true);
                p.fill(7, 1, 1, 8, 1, 2, "spruce_planks"); p.set(7, 2, 1, "black_wool");
            }
            case "variety_theatre" -> {
                // Variety theatre: a gaily painted hall with a pediment, lit marquee and red banners.
                p.ground("polished_andesite");
                int top = p.body(1, 2, 7, 8, 1, 2, 4, new String[]{"red_terracotta", "smooth_sandstone"}, "quartz_pillar", "smooth_sandstone", "glass_pane");
                p.fill(3, 1, 2, 5, 3, 2, "air"); p.fill(3, 1, 3, 5, 3, 3, "black_concrete");
                p.fill(1, 4, 0, 7, 4, 1, "smooth_sandstone_slab[type=bottom]");
                p.fill(2, 5, 1, 6, 5, 1, "glowstone"); p.set(3, 3, 0, "lantern[hanging=true]"); p.set(5, 3, 0, "lantern[hanging=true]");
                p.posts(1, 0, 7, 0, 1, 3, "quartz_pillar[axis=y]");
                p.fill(1, 4, 0, 7, 4, 0, "smooth_sandstone_slab[type=bottom]");
                for (int x : new int[]{2, 6}) p.set(x, 7, 1, "red_wall_banner[facing=north]");
                int r = p.roof(0, 2, 8, 8, top, "brick", false, "smooth_sandstone", 1, 2, 7, 8);
                p.set(4, top + 1, 2, "gold_block"); p.set(4, top + 2, 2, "glowstone");
                p.set(4, r + 1, 5, "lightning_rod[facing=up]");
            }
            case "quartz_pit" -> {
                // Quartz sand pit: pale sand terraces, sieves and sacks of sand.
                p.ground("sandstone");
                p.fill(0, 1, 4, 6, 1, 6, "smooth_sandstone"); p.fill(0, 2, 5, 6, 2, 6, "sandstone"); p.fill(1, 3, 6, 5, 3, 6, "smooth_sandstone");
                p.fill(2, 1, 4, 4, 1, 4, "cut_sandstone_slab[type=bottom]");
                p.set(1, 1, 1, "spruce_fence"); p.set(3, 1, 1, "spruce_fence"); p.fill(1, 2, 1, 3, 2, 1, "spruce_trapdoor[facing=north,half=top,open=false]");
                p.set(5, 1, 0, "white_wool"); p.set(6, 1, 0, "white_wool"); p.set(6, 2, 0, "white_wool"); p.cart(4, 2, "smooth_sandstone_slab[type=bottom]");
                p.lamp(0, 3, "spruce_fence");
            }
            case "glassworks" -> {
                // Glassworks: a workshop beside a great brick cone kiln glowing at its base.
                p.ground("stone_bricks");
                int top = p.body(0, 1, 3, 6, 1, 1, 4, new String[]{"smooth_sandstone"}, "stone_bricks", null, "glass_pane");
                p.door(2, 1, 1, "spruce", "south");
                p.roof(0, 1, 3, 6, top, "dark_prismarine", false, "smooth_sandstone", 0, 1, 3, 6);
                p.fill(4, 1, 1, 8, 3, 5, "bricks"); p.hip(4, 1, 8, 5, 4, "brick", "bricks");
                p.chimney(6, 3, 6, 9, "bricks", true);
                p.fill(6, 1, 1, 6, 2, 1, "air"); p.set(6, 1, 2, "magma_block");
                p.set(4, 1, 0, "glass"); p.set(5, 1, 0, "light_blue_stained_glass");
            }

            // ---------------------------------------------------------------- engineers
            case "university" -> {
                // University: a classical white building with a columned portico and a green copper dome.
                p.ground("smooth_stone"); p.fill(0, 0, 0, 8, 0, 1, "polished_andesite");
                int top = p.body(1, 2, 7, 8, 1, 2, 4, new String[]{"calcite"}, "quartz_pillar", "smooth_quartz", "glass_pane");
                p.door(4, 1, 2, "dark_oak", "south");
                for (int x = 1; x <= 7; x += 2) p.fill(x, 1, 0, x, 4, 0, "quartz_pillar[axis=y]");
                p.fill(0, 5, 0, 8, 5, 1, "smooth_quartz");
                p.roof(0, 0, 8, 1, 6, "quartz", false, "smooth_quartz", 0, 0, 8, 1);
                p.cornice(1, 2, 7, 8, top, "quartz");
                p.fill(1, top + 1, 2, 7, top + 1, 8, "smooth_quartz_slab[type=bottom]");
                p.fill(2, top + 1, 3, 6, top + 2, 7, "calcite");
                p.hip(2, 3, 6, 7, top + 3, "waxed_oxidized_cut_copper", "waxed_oxidized_cut_copper");
                p.set(4, top + 6, 5, "gold_block"); p.set(4, top + 7, 5, "lightning_rod[facing=up]");
            }
            case "light_bulb_factory" -> {
                // Light bulb factory: a modern brick works with a glass roof, glowing windows and a stack.
                p.ground("stone_bricks");
                int top = p.body(0, 1, 6, 6, 1, 1, 5, new String[]{"white_terracotta"}, "polished_blackstone_bricks", null, null);
                for (int x = 1; x <= 5; x += 2) { p.fill(x, 2, 1, x, 4, 1, "yellow_stained_glass_pane"); p.fill(x, 2, 6, x, 4, 6, "yellow_stained_glass_pane"); }
                p.door(3, 1, 1, "dark_oak", "south"); p.fill(1, 1, 2, 5, 1, 5, "glowstone"); p.fill(1, 1, 3, 5, 1, 4, "smooth_stone");
                p.roof(0, 1, 6, 6, top, "polished_blackstone_brick", true, "glass", 0, 1, 6, 6);
                p.chimney(8, 5, 1, top + 6, "bricks", true);
                p.fill(7, 1, 1, 8, 1, 2, "spruce_planks"); p.set(7, 2, 1, "glass"); p.set(8, 1, 0, "lantern[hanging=false]");
            }
            case "window_maker" -> {
                // Window makers: a workshop with large panes on display racks before the door.
                p.ground("stone_bricks");
                int top = p.body(0, 2, 6, 6, 1, 2, 3, new String[]{"smooth_sandstone"}, "stripped_dark_oak_log", "stripped_dark_oak_log", "glass_pane");
                p.door(3, 1, 2, "dark_oak", "south"); p.fill(1, 1, 2, 2, 2, 2, "glass_pane"); p.fill(4, 1, 2, 5, 2, 2, "glass_pane");
                p.roof(0, 2, 6, 6, top, "waxed_cut_copper", true, "smooth_sandstone", 0, 2, 6, 6);
                p.chimney(5, 4, top, top + 3, "bricks", false);
                p.fill(0, 1, 0, 2, 2, 0, "glass_pane"); p.fill(4, 1, 0, 6, 2, 0, "glass_pane"); p.set(0, 3, 0, "dark_oak_slab[type=bottom]"); p.set(6, 3, 0, "dark_oak_slab[type=bottom]");
                p.fill(1, 3, 0, 2, 3, 0, "dark_oak_slab[type=bottom]"); p.fill(4, 3, 0, 5, 3, 0, "dark_oak_slab[type=bottom]");
            }
            case "goldsmith" -> {
                // Clockmakers: an elegant shop crowned by a clock tower.
                p.ground("polished_andesite");
                int top = p.body(0, 2, 6, 6, 1, 2, 3, new String[]{"quartz_bricks", "calcite"}, "quartz_pillar", "smooth_quartz", "glass_pane");
                p.door(3, 1, 2, "dark_oak", "south"); p.fill(1, 1, 2, 2, 2, 2, "glass_pane"); p.fill(4, 1, 2, 5, 2, 2, "glass_pane");
                p.fill(0, 3, 1, 6, 3, 1, "green_wool"); p.set(1, 3, 1, "white_wool"); p.set(3, 3, 1, "white_wool"); p.set(5, 3, 1, "white_wool");
                p.cornice(0, 2, 6, 6, top, "quartz");
                p.hip(0, 2, 6, 6, top + 1, "waxed_cut_copper", "waxed_cut_copper");
                p.fill(2, top + 1, 2, 4, top + 4, 4, "quartz_bricks");
                p.fill(2, top + 3, 2, 4, top + 3, 2, "white_concrete"); p.set(3, top + 3, 2, "black_concrete"); p.set(3, top + 4, 2, "white_concrete"); p.set(3, top + 2, 2, "white_concrete");
                p.hip(2, 2, 4, 4, top + 5, "waxed_cut_copper", "waxed_cut_copper"); p.set(3, top + 7, 3, "gold_block");
            }
            case "bank" -> {
                // Bank: a temple of money, a portico of columns under a pediment, gold over the door.
                p.ground("smooth_stone"); p.fill(0, 0, 0, 8, 0, 2, "polished_andesite");
                int top = p.body(1, 3, 7, 8, 1, 2, 4, new String[]{"quartz_bricks"}, "chiseled_quartz_block", "smooth_quartz", "glass_pane");
                p.door(4, 1, 3, "dark_oak", "south"); p.set(4, 3, 3, "gold_block");
                p.fill(0, 1, 0, 8, 1, 2, "smooth_quartz_slab[type=bottom]");
                for (int x = 1; x <= 7; x += 2) p.fill(x, 1, 1, x, 5, 1, "quartz_pillar[axis=y]");
                p.fill(0, 6, 0, 8, 6, 3, "smooth_quartz");
                int r = p.roof(0, 0, 8, 8, 7, "quartz", false, "smooth_quartz", 0, 0, 8, 8);
                p.set(4, 8, 0, "gold_block");
                p.set(4, r + 1, 4, "lightning_rod[facing=up]");
            }

            // ---------------------------------------------------------------- investors
            case "vineyard" -> {
                // Vineyard: trellised vine rows heavy with fruit, and the vintner's stone press house.
                p.ground("grass_block");
                for (int x = 0; x <= 8; x += 2) for (int z = 3; z <= 8; z++) {
                    p.set(x, 1, z, z % 5 == 3 ? "spruce_fence" : "oak_leaves[persistent=true]");
                    p.set(x, 2, z, z % 5 == 3 ? "spruce_fence" : (x + z) % 3 == 0 ? "purple_terracotta" : "oak_leaves[persistent=true]");
                    p.set(x + 1, 0, z, "coarse_dirt");
                }
                int top = p.body(0, 0, 4, 2, 1, 1, 3, new String[]{"stone_bricks"}, "stripped_spruce_log", null, "glass_pane");
                p.door(2, 1, 0, "spruce", "south");
                p.roof(0, 0, 4, 2, top, "brick", true, "stone_bricks", 0, 0, 4, 2);
                p.kegs(6, 0, 7, 0, 1, 1); p.set(6, 1, 1, "purple_terracotta"); p.lamp(8, 0, "spruce_fence");
            }
            case "champagne_cellar" -> {
                // Champagne cellar: an elegant white maison with copper roof and the arched cellar entrance.
                p.ground("smooth_stone");
                int top = p.body(0, 1, 6, 6, 1, 2, 3, new String[]{"calcite"}, "quartz_pillar", "smooth_quartz", "glass_pane");
                p.door(3, 1, 1, "dark_oak", "south");
                p.cornice(0, 1, 6, 6, top, "quartz");
                p.hip(0, 1, 6, 6, top + 1, "waxed_oxidized_cut_copper", "waxed_oxidized_cut_copper");
                p.fill(7, 1, 1, 8, 2, 4, "stone_bricks"); p.fill(7, 1, 1, 8, 1, 1, "air"); p.fill(7, 1, 2, 8, 1, 2, "black_concrete"); p.fill(7, 2, 1, 8, 2, 1, "stone_brick_stairs[facing=north,half=top]");
                p.fill(7, 3, 1, 8, 3, 4, "grass_block"); p.set(8, 4, 2, "flowering_azalea_leaves[persistent=true]");
                p.kegs(7, 5, 8, 5, 1, 1); p.lamp(8, 0, "polished_blackstone_wall");
            }
            case "jeweller" -> {
                // Jewellers: a gilded shop front with display windows under a copper mansard.
                p.ground("polished_andesite");
                int top = p.body(0, 1, 6, 6, 1, 3, 3, new String[]{"polished_blackstone_bricks", "quartz_bricks"}, "quartz_pillar", "gold_block", "glass_pane");
                p.door(3, 1, 1, "dark_oak", "south"); p.fill(1, 1, 1, 2, 2, 1, "glass_pane"); p.fill(4, 1, 1, 5, 2, 1, "glass_pane");
                p.fill(0, 3, 0, 6, 3, 0, "black_wool"); p.set(3, 3, 0, "gold_block");
                p.cornice(0, 1, 6, 6, top, "quartz");
                p.hip(0, 1, 6, 6, top + 1, "waxed_cut_copper", "waxed_cut_copper");
                p.set(1, top + 1, 1, "glass_pane"); p.set(5, top + 1, 1, "glass_pane");
            }
            case "club" -> {
                // Members' club: a grand white mansion with a portico, corner turrets and gardens.
                p.ground("grass_block"); p.fill(3, 0, 0, 5, 0, 2, "smooth_quartz");
                int top = p.body(1, 2, 7, 7, 1, 3, 3, new String[]{"calcite"}, "quartz_pillar", "smooth_quartz", "glass_pane");
                p.door(4, 1, 2, "dark_oak", "south");
                p.set(3, 1, 0, "quartz_pillar[axis=y]"); p.set(3, 2, 0, "quartz_pillar[axis=y]"); p.set(5, 1, 0, "quartz_pillar[axis=y]"); p.set(5, 2, 0, "quartz_pillar[axis=y]");
                p.fill(3, 3, 0, 5, 3, 1, "smooth_quartz"); p.fill(3, 4, 0, 5, 4, 0, "quartz_slab[type=bottom]");
                p.cornice(1, 2, 7, 7, top, "quartz");
                p.hip(1, 2, 7, 7, top + 1, "waxed_oxidized_cut_copper", "waxed_oxidized_cut_copper");
                for (int[] c : new int[][]{{1, 2}, {7, 2}}) { p.fill(c[0], top, c[1], c[0], top + 2, c[1], "calcite"); p.set(c[0], top + 3, c[1], "waxed_oxidized_cut_copper_slab[type=bottom]"); p.set(c[0], top + 4, c[1], "lightning_rod[facing=up]"); }
                for (int x : new int[]{0, 8}) for (int z = 0; z < 9; z++) p.set(x, 1, z, z % 2 == 0 ? "azalea_leaves[persistent=true]" : "flowering_azalea_leaves[persistent=true]");
                p.set(1, 1, 0, "lantern[hanging=false]"); p.set(7, 1, 0, "lantern[hanging=false]");
                p.fill(1, 1, 8, 7, 1, 8, "azalea_leaves[persistent=true]");
            }

            // ---------------------------------------------------------------- New World
            case "laborer_house" -> {
                // Jornaleros: an adobe hut with a thatched roof on timber posts and a shaded porch.
                p.ground("coarse_dirt"); p.fill(1, 0, 2, 5, 0, 6, "packed_mud");
                int top = p.body(1, 2, 5, 6, 1, 1, 3, new String[]{"packed_mud"}, "stripped_jungle_log", null, "glass_pane");
                p.door(3, 1, 2, "jungle", "south");
                p.roof(0, 0, 6, 6, top, "bamboo_mosaic", true, "mud_bricks", 1, 2, 5, 6);
                p.fill(0, 1, 0, 0, 3, 0, "jungle_fence"); p.fill(6, 1, 0, 6, 3, 0, "jungle_fence");
                p.set(1, 1, 0, "bamboo_mosaic_slab[type=bottom]"); p.set(5, 1, 1, "orange_terracotta"); p.set(1, 1, 1, "flowering_azalea");
            }
            case "overseer_house" -> {
                // Obreros: a two-storey colonial house in warm plaster with a timber balcony and a tile roof.
                p.ground("coarse_dirt"); p.fill(1, 0, 1, 5, 0, 6, "packed_mud");
                int top = p.body(1, 2, 5, 6, 1, 2, 3, new String[]{"orange_terracotta", "white_terracotta"}, "stripped_jungle_log", "stripped_jungle_log", "glass_pane");
                p.door(3, 1, 2, "jungle", "south");
                p.fill(1, 3, 1, 5, 3, 1, "jungle_slab[type=top]"); p.fill(1, 4, 1, 5, 4, 1, "jungle_fence"); p.posts(1, 1, 5, 1, 1, 2, "jungle_fence");
                p.set(3, 4, 2, "glass_pane"); p.set(3, 5, 2, "glass_pane");
                for (int x : new int[]{1, 5}) p.shutter(x, 5, 1, "jungle", "north");
                p.roof(0, 1, 6, 7, top, "granite", true, "white_terracotta", 1, 2, 5, 6);
                p.set(0, 1, 0, "jungle_leaves[persistent=true]"); p.set(6, 1, 0, "orange_terracotta");
            }
            case "plantain_plantation" -> {
                // Plantain plantation: rows of banana plants, a packing shed with green bunches.
                p.ground("grass_block");
                for (int x = 1; x <= 7; x += 3) for (int z = 3; z <= 7; z += 3) {
                    p.set(x, 0, z, "rooted_dirt"); p.fill(x, 1, z, x, 3, z, "jungle_log[axis=y]");
                    for (int[] o : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) p.set(x + o[0], 3, z + o[1], "jungle_leaves[persistent=true]");
                    p.set(x, 4, z, "jungle_leaves[persistent=true]"); p.set(x + 1, 2, z, "lime_terracotta");
                }
                p.posts(0, 0, 3, 1, 1, 2, "jungle_fence"); p.roof(0, 0, 3, 1, 3, "bamboo_mosaic", true, null, 0, 0, 0, 0);
                p.set(1, 1, 0, "lime_terracotta"); p.set(2, 1, 1, "yellow_terracotta"); p.set(5, 1, 0, "lime_terracotta"); p.cart(5, 1, "yellow_terracotta");
            }
            case "alpaca_farm" -> {
                // Alpaca farm: a mountain pen with an adobe shelter, fleeces and fodder.
                p.ground("grass_block"); p.fence(0, 0, 8, 8, "jungle_fence", 4, 0);
                for (int x = 1; x < 8; x++) for (int z = 1; z < 8; z++) if ((x + z * 2) % 5 == 0) p.set(x, 0, z, "coarse_dirt");
                int top = p.body(5, 5, 8, 8, 1, 1, 3, new String[]{"packed_mud"}, "stripped_jungle_log", null, null);
                p.fill(6, 1, 5, 7, 2, 5, "air");
                p.roof(5, 4, 8, 8, top, "bamboo_mosaic", true, "mud_bricks", 5, 5, 8, 8);
                p.set(2, 1, 6, "hay_block[axis=y]"); p.set(1, 1, 6, "hay_block[axis=y]");
                p.set(3, 1, 3, "brown_wool"); p.set(4, 1, 3, "brown_wool"); p.set(4, 2, 3, "white_wool"); p.set(2, 1, 3, "white_wool");
                p.set(6, 1, 2, "brown_wool"); p.set(7, 1, 2, "white_wool");
            }
            case "poncho_darner" -> {
                // Poncho darner: an adobe workshop with bright woven cloths hanging out to dry.
                p.ground("coarse_dirt"); p.fill(1, 0, 2, 5, 0, 6, "packed_mud");
                int top = p.body(1, 2, 5, 6, 1, 1, 3, new String[]{"yellow_terracotta"}, "stripped_jungle_log", null, "glass_pane");
                p.fill(3, 1, 2, 3, 2, 2, "air"); p.set(3, 1, 3, "loom[facing=north]");
                p.roof(0, 1, 6, 7, top, "bamboo_mosaic", true, "mud_bricks", 1, 2, 5, 6);
                p.fill(0, 1, 0, 0, 3, 0, "jungle_fence"); p.fill(6, 1, 0, 6, 3, 0, "jungle_fence"); p.fill(0, 4, 0, 6, 4, 0, "jungle_fence");
                String[] cloth = {"red_wool", "yellow_wool", "cyan_wool", "orange_wool", "red_wool"};
                for (int x = 1; x <= 5; x++) { p.set(x, 3, 0, cloth[x - 1]); if (x % 2 == 1) p.set(x, 2, 0, cloth[(x + 1) % 5]); }
            }
            case "chapel" -> {
                // Chapel: a whitewashed mission church with a bell gable over the door and a tiled roof.
                p.ground("coarse_dirt"); p.fill(1, 0, 0, 5, 0, 6, "packed_mud");
                int top = p.body(1, 2, 5, 6, 1, 1, 5, new String[]{"white_terracotta"}, "white_terracotta", null, null);
                p.set(1, 3, 4, "glass_pane"); p.set(5, 3, 4, "glass_pane");
                p.door(3, 1, 2, "jungle", "south");
                p.roof(0, 2, 6, 6, top, "granite", false, "white_terracotta", 1, 2, 5, 6);
                p.fill(1, 1, 1, 5, 6, 1, "white_terracotta"); p.fill(3, 1, 1, 3, 2, 1, "air"); p.fill(2, 7, 1, 4, 8, 1, "white_terracotta"); p.set(3, 9, 1, "white_terracotta");
                p.set(3, 7, 1, "bell[attachment=ceiling,facing=north]");
                p.fill(3, 10, 1, 3, 11, 1, "jungle_fence"); p.set(2, 11, 1, "jungle_fence"); p.set(4, 11, 1, "jungle_fence");
                p.set(3, 4, 1, "glass_pane");
            }
            case "sugar_cane_plantation" -> {
                // Sugar cane plantation: dense rows of tall cane and a cutters' shelter.
                p.ground("grass_block");
                for (int x = 0; x <= 8; x++) for (int z = 3; z <= 8; z++) {
                    if (x % 3 == 2) { p.set(x, 0, z, "coarse_dirt"); continue; }
                    p.fill(x, 1, z, x, 2 + (x + z) % 2, z, "bamboo[age=1,leaves=none,stage=1]");
                    p.set(x, 3 + (x + z) % 2, z, "bamboo[age=1,leaves=large,stage=1]");
                }
                p.posts(0, 0, 3, 2, 1, 2, "jungle_fence"); p.roof(0, 0, 3, 2, 3, "bamboo_mosaic", true, null, 0, 0, 0, 0);
                p.fill(1, 1, 1, 2, 1, 1, "bamboo_block[axis=x]"); p.set(5, 1, 0, "bamboo_block[axis=z]"); p.set(6, 1, 0, "bamboo_block[axis=z]"); p.set(5, 2, 0, "bamboo_block[axis=z]");
            }
            case "rum_distillery" -> {
                // Rum distillery: an adobe and timber still-house with copper stills and barrels of rum.
                p.ground("coarse_dirt"); p.fill(0, 0, 1, 5, 0, 6, "packed_mud");
                int top = p.body(0, 2, 5, 6, 1, 1, 4, new String[]{"orange_terracotta"}, "stripped_jungle_log", null, "glass_pane");
                p.door(2, 1, 2, "jungle", "south");
                p.roof(0, 1, 5, 7, top, "granite", true, "orange_terracotta", 0, 2, 5, 6);
                p.chimney(4, 5, top, top + 3, "bricks", true);
                p.still(7, 2); p.still(7, 4);
                p.kegs(6, 6, 8, 6, 1, 1); p.set(7, 2, 6, "stripped_spruce_log[axis=x]");
                p.set(0, 1, 0, "bamboo_block[axis=y]"); p.set(1, 1, 0, "bamboo_block[axis=y]");
            }
            case "coffee_plantation" -> {
                // Coffee plantation: shrub rows under shade, and beans spread to dry on the patio.
                p.ground("grass_block");
                for (int x = 0; x <= 8; x++) for (int z = 4; z <= 8; z++) if (z % 2 == 0) p.set(x, 1, z, (x + z) % 3 == 0 ? "flowering_azalea" : "azalea");
                p.fill(4, 0, 0, 8, 0, 2, "smooth_stone"); p.fill(5, 1, 0, 7, 1, 1, "brown_carpet");
                int top = p.body(0, 0, 2, 2, 1, 1, 3, new String[]{"packed_mud"}, "stripped_jungle_log", null, null);
                p.door(1, 1, 2, "jungle", "north");
                p.roof(0, 0, 2, 2, top, "bamboo_mosaic", true, "mud_bricks", 0, 0, 2, 2);
                p.set(8, 1, 2, "brown_wool"); p.set(8, 2, 2, "brown_wool"); p.set(3, 1, 0, "brown_wool");
                p.tree(4, 6, 1, "jungle_log", "jungle_leaves", 3);
            }
            case "coffee_roaster" -> {
                // Coffee roaster: a terracotta roasting house with a smoking flue and sacks of beans.
                p.ground("coarse_dirt"); p.fill(0, 0, 1, 4, 0, 5, "packed_mud");
                int top = p.body(0, 1, 4, 5, 1, 1, 4, new String[]{"brown_terracotta"}, "stripped_jungle_log", null, "glass_pane");
                p.door(2, 1, 1, "jungle", "south");
                p.roof(0, 0, 4, 6, top, "granite", true, "brown_terracotta", 0, 1, 4, 5);
                p.chimney(5, 4, 1, top + 3, "bricks", true); p.set(5, 1, 3, "bricks"); p.set(6, 1, 4, "bricks");
                p.set(5, 1, 0, "brown_wool"); p.set(6, 1, 0, "brown_wool"); p.set(6, 2, 0, "brown_wool"); p.set(6, 1, 1, "brown_wool");
            }
            case "tobacco_plantation" -> {
                // Tobacco plantation: rows of broad-leaved plants and a drying barn hung with leaves.
                p.ground("grass_block");
                for (int x = 0; x <= 8; x++) for (int z = 4; z <= 8; z++) {
                    if (x % 3 == 2) { p.set(x, 0, z, "coarse_dirt"); continue; }
                    p.set(x, 1, z, "large_fern[half=lower]"); p.set(x, 2, z, "large_fern[half=upper]");
                }
                int top = p.body(0, 0, 4, 2, 1, 1, 4, new String[]{"spruce_planks"}, "stripped_jungle_log", null, null);
                for (int x = 1; x <= 3; x++) { p.set(x, 2, 0, "air"); p.set(x, 3, 0, "dried_kelp_block"); }
                p.roof(0, 0, 4, 2, top, "bamboo_mosaic", true, "spruce_planks", 0, 0, 4, 2);
                p.set(6, 1, 1, "dried_kelp_block"); p.set(7, 1, 1, "dried_kelp_block"); p.set(6, 2, 1, "dried_kelp_block");
            }
            case "cigar_factory" -> {
                // Cigar factory: a colonial manufactory with an arcade, tiled roof and the owner's flag.
                p.ground("coarse_dirt"); p.fill(0, 0, 0, 6, 0, 6, "packed_mud");
                int top = p.body(0, 2, 6, 6, 1, 2, 3, new String[]{"white_terracotta"}, "stripped_jungle_log", "stripped_jungle_log", "glass_pane");
                p.door(3, 1, 2, "jungle", "south");
                // Arcade along the street, its flat top a balcony.
                for (int x = 0; x <= 6; x += 2) p.fill(x, 1, 0, x, 2, 0, "white_terracotta");
                p.fill(0, 3, 0, 6, 3, 1, "white_terracotta"); p.fill(0, 4, 0, 6, 4, 0, "jungle_fence");
                p.roof(0, 1, 6, 7, top, "granite", true, "white_terracotta", 0, 2, 6, 6);
                p.chimney(8, 5, 1, top + 3, "bricks", true);
                p.fill(7, 1, 1, 8, 1, 2, "dried_kelp_block"); p.flag(8, 0, 4, "orange_wool");
            }
            default -> throw new IllegalArgumentException("No design for " + id);
        }
        return p;
    }

    static int structure(String id, int w, int d) throws IOException {
        Plot p = design(id, w, d);
        // Anno's plots: the building's ground layer is beaten earth, which marks out its footprint in the meadow.
        for (var e : p.b.entrySet()) if (e.getKey().y == 0) e.setValue(switch (e.getValue()) {
            case "grass_block" -> "annocraft1800:trodden_grass";
            case "coarse_dirt", "podzol", "dirt" -> "annocraft1800:packed_earth";
            default -> e.getValue();
        });
        int h = 1;
        for (var e : p.b.entrySet()) if (!e.getValue().equals("air")) h = Math.max(h, e.getKey().y + 1);
        // Explicit air makes the template's bounding volume exact and keeps upgrades deterministic.
        Map<Pos, String> blocks = new TreeMap<>();
        for (int x = 0; x < w; x++) for (int y = 0; y < h; y++) for (int z = 0; z < d; z++) blocks.put(new Pos(x, y, z), "air");
        final int height = h;
        p.b.forEach((pos, s) -> { if (pos.y < height) blocks.put(pos, s); });
        write(id, w, h, d, blocks);
        return h;
    }

    static void write(String name, int w, int h, int d, Map<Pos, String> blocks) throws IOException {
        List<String> palette = new ArrayList<>(new TreeSet<>(blocks.values()));
        Path root = DATA.resolve("structures");
        Files.createDirectories(root);
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(raw);
        out.writeByte(10); out.writeUTF("");
        intTag(out, "DataVersion", 3465);
        out.writeByte(9); out.writeUTF("size"); out.writeByte(3); out.writeInt(3); out.writeInt(w); out.writeInt(h); out.writeInt(d);
        out.writeByte(9); out.writeUTF("palette"); out.writeByte(10); out.writeInt(palette.size());
        for (String block : palette) {
            int bracket = block.indexOf('[');
            out.writeByte(8); out.writeUTF("Name"); String blockId = bracket < 0 ? block : block.substring(0, bracket);
            out.writeUTF(blockId.contains(":") ? blockId : "minecraft:" + blockId);
            if (bracket >= 0) {
                out.writeByte(10); out.writeUTF("Properties");
                for (String prop : block.substring(bracket + 1, block.length() - 1).split(",")) {
                    String[] kv = prop.split("=");
                    out.writeByte(8); out.writeUTF(kv[0]); out.writeUTF(kv[1]);
                }
                out.writeByte(0);
            }
            out.writeByte(0);
        }
        out.writeByte(9); out.writeUTF("blocks"); out.writeByte(10); out.writeInt(blocks.size());
        for (var e : blocks.entrySet()) {
            Pos p = e.getKey();
            out.writeByte(9); out.writeUTF("pos"); out.writeByte(3); out.writeInt(3); out.writeInt(p.x); out.writeInt(p.y); out.writeInt(p.z);
            intTag(out, "state", palette.indexOf(e.getValue()));
            out.writeByte(0);
        }
        out.writeByte(9); out.writeUTF("entities"); out.writeByte(10); out.writeInt(0);
        out.writeByte(0);
        try (OutputStream file = new GZIPOutputStream(Files.newOutputStream(root.resolve(name + ".nbt")))) { file.write(raw.toByteArray()); }
    }
    static void intTag(DataOutputStream out, String name, int value) throws IOException { out.writeByte(3); out.writeUTF(name); out.writeInt(value); }
}
