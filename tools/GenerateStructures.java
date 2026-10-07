import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.GZIPOutputStream;

/**
 * Generates original, deterministic vanilla structure NBT; JDK standard library only.
 * Run from the project root with the JDK 17 used by Gradle: {@code java tools/GenerateStructures.java}
 */
public final class GenerateStructures {
    static final Path ROOT = Path.of("src/main/resources/data/annocraft1800/structures");
    record Pos(int x, int y, int z) implements Comparable<Pos> {
        public int compareTo(Pos o) { return x != o.x ? Integer.compare(x, o.x) : y != o.y ? Integer.compare(y, o.y) : Integer.compare(z, o.z); }
    }

    public static void main(String[] args) throws IOException {
        // Nonflammable blocks keep managed templates safe without changing global vanilla gamerules.
        building("residence", 7, 7, 7, "terracotta", "deepslate_tiles", true);
        building("residence_2", 7, 9, 7, "bricks", "deepslate_tiles", true);
        building("warehouse", 11, 8, 9, "cut_sandstone", "stone_bricks", true);
        building("trading_post", 11, 8, 9, "polished_diorite", "prismarine_bricks", true);
        building("fishery", 7, 6, 7, "cyan_terracotta", "dark_prismarine", true);
        building("lumberjack", 5, 6, 5, "packed_mud", "mud_bricks", false);
        building("sawmill", 9, 7, 7, "brown_terracotta", "deepslate_bricks", true);
        Map<Pos, String> empty = new TreeMap<>(); empty.put(new Pos(0, 0, 0), "air");
        write("empty", 1, 1, 1, empty);
        System.out.println("Generated seven original building structures and one GameTest fixture.");
    }

    static void building(String name, int w, int h, int d, String material, String roof, boolean windows) throws IOException {
        // Explicit air makes the template's bounding volume exact and keeps upgrades deterministic.
        Map<Pos, String> b = new TreeMap<>();
        for (int x = 0; x < w; x++) for (int y = 0; y < h; y++) for (int z = 0; z < d; z++) b.put(new Pos(x, y, z), "air");
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
        }
        for (int y = wallTop + 1; y < h; y++) {
            int inset = y - wallTop - 1;
            for (int x = inset; x < w - inset; x++) for (int z = 0; z < d; z++) b.put(new Pos(x, y, z), roof);
        }
        b.put(new Pos(w / 2, 1, d / 2), "glowstone");
        write(name, w, h, d, b);
    }

    static void write(String name, int w, int h, int d, Map<Pos, String> blocks) throws IOException {
        List<String> palette = new ArrayList<>(new TreeSet<>(blocks.values()));
        Files.createDirectories(ROOT);
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(raw);
        out.writeByte(10); out.writeUTF("");
        intTag(out, "DataVersion", 3465);
        out.writeByte(9); out.writeUTF("size"); out.writeByte(3); out.writeInt(3); out.writeInt(w); out.writeInt(h); out.writeInt(d);
        out.writeByte(9); out.writeUTF("palette"); out.writeByte(10); out.writeInt(palette.size());
        for (String block : palette) { out.writeByte(8); out.writeUTF("Name"); out.writeUTF("minecraft:" + block); out.writeByte(0); }
        out.writeByte(9); out.writeUTF("blocks"); out.writeByte(10); out.writeInt(blocks.size());
        for (var e : blocks.entrySet()) {
            Pos p = e.getKey();
            out.writeByte(9); out.writeUTF("pos"); out.writeByte(3); out.writeInt(3); out.writeInt(p.x); out.writeInt(p.y); out.writeInt(p.z);
            intTag(out, "state", palette.indexOf(e.getValue()));
            out.writeByte(0);
        }
        out.writeByte(9); out.writeUTF("entities"); out.writeByte(10); out.writeInt(0);
        out.writeByte(0);
        try (OutputStream file = new GZIPOutputStream(Files.newOutputStream(ROOT.resolve(name + ".nbt")))) { file.write(raw.toByteArray()); }
    }
    static void intTag(DataOutputStream out, String name, int value) throws IOException { out.writeByte(3); out.writeUTF(name); out.writeInt(value); }
}
