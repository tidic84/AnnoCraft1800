import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * Generates the ground blocks' resources: painted 16 × 16 textures (packed earth, dirt road, the grass overlays of
 * worn and trodden grass), block states with random turns, models, item models and loot tables.
 * JDK standard library only. Run from the project root: {@code java tools/GenerateGround.java}
 */
public final class GenerateGround {
    static final Path ASSETS = Path.of("src/main/resources/assets/annocraft1800"), DATA = Path.of("src/main/resources/data/annocraft1800");

    public static void main(String[] args) throws IOException {
        Path textures = ASSETS.resolve("textures/block");
        Files.createDirectories(textures);
        ImageIO.write(earth(11, false), "png", textures.resolve("packed_earth.png").toFile());
        ImageIO.write(earth(12, true), "png", textures.resolve("earth_road.png").toFile());
        ImageIO.write(roadSide(), "png", textures.resolve("earth_road_side.png").toFile());
        ImageIO.write(grass(13, .30), "png", textures.resolve("worn_grass_overlay.png").toFile());
        ImageIO.write(grass(14, .68), "png", textures.resolve("trodden_grass_overlay.png").toFile());

        write("blockstates/packed_earth.json", turned("packed_earth"));
        write("blockstates/earth_road.json", turned("earth_road"));
        write("blockstates/worn_grass.json", turned("worn_grass"));
        write("blockstates/trodden_grass.json", turned("trodden_grass"));
        write("models/block/packed_earth.json", "{\n  \"parent\": \"minecraft:block/cube_all\",\n  \"textures\": {\"all\": \"annocraft1800:block/packed_earth\"}\n}\n");
        write("models/block/earth_road.json", """
                {
                  "parent": "minecraft:block/dirt_path",
                  "textures": {
                    "particle": "annocraft1800:block/earth_road",
                    "top": "annocraft1800:block/earth_road",
                    "side": "annocraft1800:block/earth_road_side",
                    "bottom": "annocraft1800:block/packed_earth"
                  }
                }
                """);
        for (String id : List.of("worn_grass", "trodden_grass")) write("models/block/" + id + ".json", wornModel(id));
        for (String id : List.of("packed_earth", "earth_road", "worn_grass", "trodden_grass")) {
            write("models/item/" + id + ".json", "{\n  \"parent\": \"annocraft1800:block/" + id + "\"\n}\n");
            Path loot = DATA.resolve("loot_tables/blocks/" + id + ".json");
            Files.createDirectories(loot.getParent());
            Files.writeString(loot, """
                    {
                      "type": "minecraft:block",
                      "pools": [{"rolls": 1, "entries": [{"type": "minecraft:item", "name": "minecraft:dirt"}], "conditions": [{"condition": "minecraft:survives_explosion"}]}]
                    }
                    """, StandardCharsets.UTF_8);
        }
        System.out.println("Generated the ground blocks' textures, models and loot tables.");
    }
    static void write(String path, String text) throws IOException {
        Path p = ASSETS.resolve(path); Files.createDirectories(p.getParent()); Files.writeString(p, text, StandardCharsets.UTF_8);
    }
    /** Four quarter turns, so the ground never shows a repeating grid. */
    static String turned(String id) {
        return "{\n  \"variants\": {\n    \"\": [\n"
                + "      {\"model\": \"annocraft1800:block/" + id + "\"},\n"
                + "      {\"model\": \"annocraft1800:block/" + id + "\", \"y\": 90},\n"
                + "      {\"model\": \"annocraft1800:block/" + id + "\", \"y\": 180},\n"
                + "      {\"model\": \"annocraft1800:block/" + id + "\", \"y\": 270}\n    ]\n  }\n}\n";
    }
    /** Packed earth under a tinted grass overlay on top; vanilla grass sides. */
    static String wornModel(String id) {
        return """
                {
                  "parent": "minecraft:block/block",
                  "render_type": "minecraft:cutout_mipped",
                  "textures": {
                    "particle": "annocraft1800:block/packed_earth",
                    "earth": "annocraft1800:block/packed_earth",
                    "overlay": "annocraft1800:block/%s_overlay",
                    "side": "minecraft:block/grass_block_side",
                    "side_overlay": "minecraft:block/grass_block_side_overlay",
                    "bottom": "minecraft:block/dirt"
                  },
                  "elements": [
                    {"from": [0, 0, 0], "to": [16, 16, 16], "faces": {
                      "down": {"uv": [0, 0, 16, 16], "texture": "#bottom", "cullface": "down"},
                      "up": {"uv": [0, 0, 16, 16], "texture": "#earth", "cullface": "up"},
                      "north": {"uv": [0, 0, 16, 16], "texture": "#side", "cullface": "north"},
                      "south": {"uv": [0, 0, 16, 16], "texture": "#side", "cullface": "south"},
                      "west": {"uv": [0, 0, 16, 16], "texture": "#side", "cullface": "west"},
                      "east": {"uv": [0, 0, 16, 16], "texture": "#side", "cullface": "east"}
                    }},
                    {"from": [0, 0, 0], "to": [16, 16, 16], "faces": {
                      "up": {"uv": [0, 0, 16, 16], "texture": "#overlay", "tintindex": 0, "cullface": "up"},
                      "north": {"uv": [0, 0, 16, 16], "texture": "#side_overlay", "tintindex": 0, "cullface": "north"},
                      "south": {"uv": [0, 0, 16, 16], "texture": "#side_overlay", "tintindex": 0, "cullface": "south"},
                      "west": {"uv": [0, 0, 16, 16], "texture": "#side_overlay", "tintindex": 0, "cullface": "west"},
                      "east": {"uv": [0, 0, 16, 16], "texture": "#side_overlay", "tintindex": 0, "cullface": "east"}
                    }}
                  ]
                }
                """.formatted(id);
    }

    // ------------------------------------------------------------------------------------------------ painting

    /** Tileable value noise in 0..1 over a 16 × 16 tile, {@code cells} lattice cells per side. */
    static double noise(int x, int y, int cells, long seed) {
        double fx = x * cells / 16.0, fy = y * cells / 16.0;
        int x0 = (int) Math.floor(fx), y0 = (int) Math.floor(fy);
        double tx = smooth(fx - x0), ty = smooth(fy - y0);
        double a = lattice(x0, y0, cells, seed), b = lattice(x0 + 1, y0, cells, seed), c = lattice(x0, y0 + 1, cells, seed), d = lattice(x0 + 1, y0 + 1, cells, seed);
        return (a * (1 - tx) + b * tx) * (1 - ty) + (c * (1 - tx) + d * tx) * ty;
    }
    static double smooth(double t) { return t * t * (3 - 2 * t); }
    static double lattice(int x, int y, int cells, long seed) { return hash(Math.floorMod(x, cells), Math.floorMod(y, cells), seed); }
    static double hash(int x, int y, long seed) {
        long h = x * 0x9E3779B97F4A7C15L ^ y * 0xC2B2AE3D27D4EB4FL ^ seed * 0x165667B19E3779F9L;
        h ^= h >>> 33; h *= 0xff51afd7ed558ccdL; h ^= h >>> 33;
        return (h >>> 11) * 0x1.0p-53;
    }
    static int rgb(double r, double g, double b) {
        return 0xff000000 | clamp(r) << 16 | clamp(g) << 8 | clamp(b);
    }
    static int clamp(double v) { return (int) Math.max(0, Math.min(255, Math.round(v))); }

    /** Packed earth: warm brown with darker clods and pale pebbles; the road version is lighter and finer. */
    static BufferedImage earth(long seed, boolean road) {
        BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        double[] base = road ? new double[]{146, 113, 78} : new double[]{118, 86, 57};
        for (int x = 0; x < 16; x++) for (int y = 0; y < 16; y++) {
            double n = noise(x, y, 4, seed) * .6 + noise(x, y, 8, seed + 1) * .4, grain = hash(x, y, seed + 2);
            double k = .82 + n * .3 + (grain - .5) * (road ? .08 : .14);
            image.setRGB(x, y, rgb(base[0] * k, base[1] * k, base[2] * k));
        }
        // Clods: small dark specks.
        for (int i = 0; i < (road ? 7 : 12); i++) {
            int x = (int) (hash(i, 1, seed + 3) * 16), y = (int) (hash(i, 2, seed + 3) * 16);
            int c = image.getRGB(x, y);
            image.setRGB(x, y, rgb((c >> 16 & 255) * .72, (c >> 8 & 255) * .7, (c & 255) * .68));
        }
        // Pebbles: one or two pale pixels with a shadow below.
        for (int i = 0; i < (road ? 6 : 4); i++) {
            int x = (int) (hash(i, 3, seed + 4) * 16), y = (int) (hash(i, 4, seed + 4) * 16);
            double v = 150 + hash(i, 5, seed + 4) * 40;
            image.setRGB(x, y, rgb(v, v * .94, v * .84));
            if (hash(i, 6, seed + 4) < .5) image.setRGB((x + 1) % 16, y, rgb(v * .9, v * .85, v * .76));
            int c = image.getRGB(x, (y + 1) % 16);
            image.setRGB(x, (y + 1) % 16, rgb((c >> 16 & 255) * .8, (c >> 8 & 255) * .78, (c & 255) * .75));
        }
        return image;
    }
    /** Side of the road: the road's packed surface over plain dirt, as the vanilla path side. */
    static BufferedImage roadSide() {
        BufferedImage top = earth(12, true), dirt = earth(15, false), image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        for (int x = 0; x < 16; x++) for (int y = 0; y < 16; y++) {
            int edge = 3 + (hash(x, 0, 16) < .4 ? 1 : 0);
            image.setRGB(x, y, y <= edge ? top.getRGB(x, y) : dirt.getRGB(x, y));
        }
        return image;
    }
    /**
     * Grey grass overlay, tinted by the biome in game, with bare patches where the earth below shows through.
     * {@code bare} is the share of the tile left bare, in soft-edged blobs.
     */
    static BufferedImage grass(long seed, double bare) {
        BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        double[] values = new double[256];
        for (int x = 0; x < 16; x++) for (int y = 0; y < 16; y++)
            values[x * 16 + y] = noise(x, y, 4, seed) * .7 + noise(x, y, 8, seed + 1) * .2 + hash(x, y, seed + 2) * .1;
        double[] sorted = values.clone(); Arrays.sort(sorted);
        double threshold = sorted[(int) Math.min(255, Math.round(bare * 256))];
        for (int x = 0; x < 16; x++) for (int y = 0; y < 16; y++) {
            if (values[x * 16 + y] < threshold) continue;
            double v = 128 + hash(x, y, seed + 5) * 48 + noise(x, y, 8, seed + 6) * 20;
            // Blades along the patches' edges are darker, as trampled grass.
            if (values[x * 16 + y] < threshold + .04) v *= .82;
            image.setRGB(x, y, rgb(v, v, v));
        }
        return image;
    }
}
