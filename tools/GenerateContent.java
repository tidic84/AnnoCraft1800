import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.GZIPOutputStream;

/**
 * Generates AnnoCraft1800 content from tools/content.txt: building definitions (JSON), original deterministic
 * vanilla structures (NBT) and the content language files. JDK standard library only.
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
                    String[] size = f[5].split("x");
                    int w = Integer.parseInt(size[0]), h = Integer.parseInt(size[1]), d = Integer.parseInt(size[2]);
                    structure(id, f[4], w, h, d);
                    Files.writeString(buildings.resolve(id + ".json"), definition(id, w, h, d, options(f.length > 6 ? f[6] : "")), StandardCharsets.UTF_8);
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

    static void structure(String id, String spec, int w, int h, int d) throws IOException {
        boolean chimney = spec.endsWith(" chimney");
        String[] s = spec.replace(" chimney", "").split(":");
        // Explicit air makes the template's bounding volume exact and keeps upgrades deterministic.
        Map<Pos, String> b = new TreeMap<>();
        for (int x = 0; x < w; x++) for (int y = 0; y < h; y++) for (int z = 0; z < d; z++) b.put(new Pos(x, y, z), "air");
        switch (s[0]) {
            case "house" -> house(b, w, h, d, s[1], s[2], s.length < 4 || !s[3].equals("plain"));
            case "farm" -> farm(b, w, h, d, s[1], s[2], s[3]);
            case "ranch" -> ranch(b, w, h, d, s[1]);
            case "mine" -> mine(b, w, h, d, s[1], s[2]);
            case "market" -> market(b, w, h, d, s[1]);
            default -> throw new IllegalArgumentException("Unknown style " + spec);
        }
        if (chimney) {
            // A lit campfire on a brick chimney: visible smoke above working industry.
            int cx = w - 2, cz = d - 2;
            for (int y = 1; y < h - 1; y++) b.put(new Pos(cx, y, cz), "bricks");
            b.put(new Pos(cx, h - 1, cz), "campfire[lit=true,signal_fire=false]");
        }
        write(id, w, h, d, b);
    }
    static void house(Map<Pos, String> b, int w, int h, int d, String material, String roof, boolean windows) {
        for (int x = 0; x < w; x++) for (int z = 0; z < d; z++) b.put(new Pos(x, 0, z), "stone_bricks");
        int wallTop = h - 3;
        for (int y = 1; y <= wallTop; y++) for (int x = 0; x < w; x++) for (int z = 0; z < d; z++)
            if (x == 0 || x == w - 1 || z == 0 || z == d - 1)
                b.put(new Pos(x, y, z), (x == 0 || x == w - 1) && (z == 0 || z == d - 1) ? "stone_bricks" : material);
        // Open entrance; no interactive door or chest means no unmanaged inventory.
        for (int y : new int[]{1, 2}) b.put(new Pos(w / 2, y, 0), "air");
        if (windows) {
            for (int x : new int[]{1, w - 2}) { b.put(new Pos(x, 2, 0), "glass"); b.put(new Pos(x, 2, d - 1), "glass"); }
            b.put(new Pos(0, 2, d / 2), "glass"); b.put(new Pos(w - 1, 2, d / 2), "glass");
            // Taller buildings get a window row on every storey.
            for (int y = 5; y < wallTop; y += 3) {
                for (int x : new int[]{1, w - 2}) { b.put(new Pos(x, y, 0), "glass"); b.put(new Pos(x, y, d - 1), "glass"); }
                b.put(new Pos(0, y, d / 2), "glass"); b.put(new Pos(w - 1, y, d / 2), "glass");
            }
        }
        for (int y = wallTop + 1; y < h; y++) {
            int inset = y - wallTop - 1;
            for (int x = inset; x < w - inset; x++) for (int z = 0; z < d; z++) b.put(new Pos(x, y, z), roof);
        }
        b.put(new Pos(w / 2, 1, d / 2), "glowstone");
    }
    static void hut(Map<Pos, String> b, String wall, String roof) {
        for (int x = 0; x < 3; x++) for (int z = 0; z < 3; z++) {
            b.put(new Pos(x, 0, z), "stone_bricks");
            for (int y = 1; y <= 2; y++) b.put(new Pos(x, y, z), x == 1 && z == 1 ? "air" : wall);
            b.put(new Pos(x, 3, z), roof);
        }
        b.put(new Pos(1, 1, 2), "air"); b.put(new Pos(1, 2, 2), "air");
        b.put(new Pos(1, 1, 1), "lantern");
    }
    static void farm(Map<Pos, String> b, int w, int h, int d, String crop, String soil, String hutWall) {
        for (int x = 0; x < w; x++) for (int z = 0; z < d; z++) {
            boolean path = x == w / 2 || z == d / 2;
            b.put(new Pos(x, 0, z), path ? "coarse_dirt" : soil);
            if (!path) b.put(new Pos(x, 1, z), crop);
        }
        hut(b, hutWall, "deepslate_tiles");
    }
    static void ranch(Map<Pos, String> b, int w, int h, int d, String hutWall) {
        for (int x = 0; x < w; x++) for (int z = 0; z < d; z++) {
            b.put(new Pos(x, 0, z), "grass_block");
            if (x == 0 || z == 0 || x == w - 1 || z == d - 1) b.put(new Pos(x, 1, z), "stone_brick_wall");
            else if ((x * 7 + z * 3) % 5 == 0) b.put(new Pos(x, 1, z), "grass");
        }
        b.put(new Pos(w / 2, 1, 0), "air");
        b.put(new Pos(w - 3, 1, d - 3), "hay_block");
        hut(b, hutWall, "deepslate_tiles");
    }
    static void mine(Map<Pos, String> b, int w, int h, int d, String ore, String base) {
        for (int x = 0; x < w; x++) for (int z = 0; z < d; z++) b.put(new Pos(x, 0, z), base);
        // A spoil heap of ore in the middle of the plot.
        for (int y = 1; y < h - 1; y++) {
            int r = Math.max(0, (Math.min(w, d) - 3) / 2 - (y - 1));
            for (int x = w / 2 - r; x <= w / 2 + r; x++) for (int z = d / 2 - r; z <= d / 2 + r; z++)
                if (x > 2 || z > 2) b.put(new Pos(x, y, z), (x + y + z) % 2 == 0 ? ore : base);
        }
        hut(b, "stone_bricks", "deepslate_tiles");
    }
    static void market(Map<Pos, String> b, int w, int h, int d, String roof) {
        for (int x = 0; x < w; x++) for (int z = 0; z < d; z++) b.put(new Pos(x, 0, z), "smooth_stone");
        // Four stalls with posts and awnings around a central lantern.
        for (int[] c : new int[][]{{1, 1}, {w - 4, 1}, {1, d - 4}, {w - 4, d - 4}}) {
            for (int[] p : new int[][]{{0, 0}, {2, 0}, {0, 2}, {2, 2}}) for (int y = 1; y <= 2; y++) b.put(new Pos(c[0] + p[0], y, c[1] + p[1]), "stone_brick_wall");
            for (int x = 0; x < 3; x++) for (int z = 0; z < 3; z++) b.put(new Pos(c[0] + x, 3, c[1] + z), roof);
        }
        b.put(new Pos(w / 2, 1, d / 2), "stone_brick_wall"); b.put(new Pos(w / 2, 2, d / 2), "lantern");
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
            out.writeByte(8); out.writeUTF("Name"); out.writeUTF("minecraft:" + (bracket < 0 ? block : block.substring(0, bracket)));
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
