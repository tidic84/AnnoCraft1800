import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/**
 * Paints the portraits' characters as 64 × 64 Minecraft skins (textures/entity/avatar): the players' presets and
 * the rivals and campaign characters, dressed after the 1800s. JDK standard library only.
 * Run from the project root: {@code java tools/GenerateAvatars.java}
 */
public final class GenerateAvatars {
    static final Path OUT = Path.of("src/main/resources/assets/annocraft1800/textures/entity/avatar");
    enum Hair { SHORT, LONG, BUN, BALD, CURLS }
    enum Face { NONE, MUSTACHE, BEARD, SIDEBURNS }
    enum Hat { NONE, TRICORN, TOPHAT, BICORNE, BONNET, CAP, BANDANA }
    record Look(String id, int skin, int hair, Hair hairStyle, Face face, Hat hat, int hatColor, int coat, int trim, int shirt, int eyes, boolean feminine, int legs) { }

    public static void main(String[] args) throws IOException {
        Files.createDirectories(OUT);
        List<Look> looks = List.of(
                // The players' presets.
                new Look("captain", 0xe2b48f, 0x4a2e1c, Hair.SHORT, Face.BEARD, Hat.TRICORN, 0x1e1e24, 0x1f3a6b, 0xd9b45a, 0xf2efe6, 0x2a4d7a, false, 0x2b2b30),
                new Look("lady", 0xf0c9a8, 0x7a3e1d, Hair.BUN, Face.NONE, Hat.BONNET, 0xe8d8c0, 0x8b2f3a, 0xf3e3c3, 0xf6f1e8, 0x3b6e3a, true, 0x8b2f3a),
                new Look("merchant", 0xd9a77e, 0x2a211c, Hair.SHORT, Face.MUSTACHE, Hat.TOPHAT, 0x222226, 0x3c2a1e, 0xb08840, 0xf0ebe0, 0x4a3324, false, 0x3b3b40),
                new Look("engineer", 0xc58e64, 0x1b1714, Hair.CURLS, Face.NONE, Hat.CAP, 0x5a4632, 0x55603a, 0x9c8a5a, 0xe9e2cf, 0x3a2a1c, true, 0x4a4030),
                new Look("admiral", 0xe8c09a, 0xd8d4cc, Hair.SHORT, Face.SIDEBURNS, Hat.BICORNE, 0x15151a, 0x10234a, 0xe0b850, 0xf4f2ec, 0x4a6a8a, false, 0xf0eee8),
                new Look("explorer", 0x9b6a45, 0x15110e, Hair.LONG, Face.NONE, Hat.NONE, 0, 0x7a5a2e, 0x4a3a20, 0xe6dcc2, 0x2a1c12, true, 0x5a4a35),
                new Look("inventor", 0xead0b5, 0xb8692e, Hair.CURLS, Face.MUSTACHE, Hat.NONE, 0, 0x4a3a5a, 0xa8a0b0, 0xf2eee4, 0x5a7a4a, false, 0x2f2a35),
                new Look("baroness", 0x6e4a32, 0x0f0c0a, Hair.LONG, Face.NONE, Hat.TOPHAT, 0x3a1530, 0x5a1a4a, 0xd8c08a, 0xf3ece0, 0x2a1a10, true, 0x3a1530),
                // Rivals and the campaign's characters.
                new Look("ashby", 0xf3d2b8, 0xc9c2b8, Hair.BUN, Face.NONE, Hat.BONNET, 0x6a3d8a, 0x6a3d8a, 0xe8d9a8, 0xf8f4ec, 0x5a6a8a, true, 0x6a3d8a),
                new Look("dravek", 0xd8b090, 0x2a2a2e, Hair.SHORT, Face.BEARD, Hat.BICORNE, 0x101014, 0x6e1a1a, 0xc8a040, 0xe8e2d4, 0x2a2a2a, false, 0x1a1a1e),
                new Look("rook", 0xb57c55, 0x101010, Hair.LONG, Face.BEARD, Hat.BANDANA, 0x9a1a1a, 0x2a2a2a, 0x7a5a3a, 0xd8ccb0, 0x1a1a1a, false, 0x3a2a1a),
                new Look("agnes", 0xe9c4a2, 0x8a5a32, Hair.BUN, Face.NONE, Hat.CAP, 0x1f3a6b, 0x1f3a6b, 0xe0c060, 0xf2efe6, 0x3a5a7a, true, 0x2a2a30),
                new Look("linnell", 0xf0d4bc, 0xe0e0dc, Hair.BALD, Face.SIDEBURNS, Hat.NONE, 0, 0x4a4a52, 0x9a9aa2, 0xf2f0ea, 0x5a5a6a, false, 0x2a2a30),
                new Look("julien", 0xe2b890, 0x6a4024, Hair.SHORT, Face.NONE, Hat.NONE, 0, 0x2f5a3a, 0xc8a860, 0xf2efe6, 0x4a6a3a, false, 0x2a2a30));
        for (Look look : looks) ImageIO.write(paint(look), "png", OUT.resolve(look.id() + ".png").toFile());
        System.out.println("Painted " + looks.size() + " avatars.");
    }

    static BufferedImage image;
    static long seed;
    static void px(int x, int y, int rgb) { image.setRGB(x, y, 0xff000000 | rgb); }
    static void clear(int x, int y) { image.setRGB(x, y, 0); }
    static void rect(int x, int y, int w, int h, int rgb) { for (int i = x; i < x + w; i++) for (int j = y; j < y + h; j++) px(i, j, shade(rgb, i, j)); }
    /** Cloth and skin are never flat: a slight per-pixel grain. */
    static int shade(int rgb, int x, int y) {
        long h = (x * 73856093L) ^ (y * 19349663L) ^ seed; h ^= h >>> 13; h *= 0x5bd1e995L; h ^= h >>> 15;
        double k = .93 + (h & 255) / 255.0 * .1;
        return mul(rgb, k);
    }
    static int mul(int rgb, double k) {
        int r = (int) Math.min(255, (rgb >> 16 & 255) * k), g = (int) Math.min(255, (rgb >> 8 & 255) * k), b = (int) Math.min(255, (rgb & 255) * k);
        return r << 16 | g << 8 | b;
    }

    static BufferedImage paint(Look l) {
        image = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB); seed = l.id().hashCode();
        for (int x = 0; x < 64; x++) for (int y = 0; y < 64; y++) clear(x, y);
        // Head: every face in skin, then hair, eyes and mouth on the front (8, 8)-(16, 16).
        rect(0, 8, 32, 8, l.skin()); rect(8, 0, 16, 8, l.skin());
        hair(l);
        int eyeY = 12;
        px(9, eyeY, 0xf4f4f0); px(10, eyeY, l.eyes()); px(13, eyeY, l.eyes()); px(14, eyeY, 0xf4f4f0);
        px(9, eyeY - 1, mul(l.hair(), .9)); px(10, eyeY - 1, mul(l.hair(), .9)); px(13, eyeY - 1, mul(l.hair(), .9)); px(14, eyeY - 1, mul(l.hair(), .9));
        px(11, 13, mul(l.skin(), .85)); px(12, 13, mul(l.skin(), .85));
        int mouth = l.feminine() ? 0xa8484a : mul(l.skin(), .65);
        px(11, 14, mouth); px(12, 14, mouth);
        if (l.feminine()) { px(9, 13, mul(l.skin(), 1.04) | 0x200000); px(14, 13, mul(l.skin(), 1.04) | 0x200000); }
        switch (l.face()) {
            case MUSTACHE -> { px(10, 14, l.hair()); px(11, 14, l.hair()); px(12, 14, l.hair()); px(13, 14, l.hair()); }
            case BEARD -> { for (int x = 8; x < 16; x++) { px(x, 15, l.hair()); if (x < 10 || x > 13) px(x, 14, l.hair()); } px(10, 14, l.hair()); px(13, 14, l.hair());
                for (int y = 13; y < 16; y++) { px(0 + 7, y, l.hair()); px(16, y, l.hair()); } }
            case SIDEBURNS -> { for (int y = 11; y < 15; y++) { px(8, y, l.hair()); px(15, y, l.hair()); px(7, y, l.hair()); px(16, y, l.hair()); } }
            default -> { }
        }
        hat(l);
        // Body: coat with lapels and buttons over the shirt, a cravat or a collar.
        rect(16, 20, 24, 12, l.coat()); rect(20, 16, 16, 4, l.coat());
        for (int y = 20; y < 32; y++) { px(23, y, mul(l.shirt(), .95)); px(24, y, mul(l.shirt(), .95)); }
        for (int y = 20; y < 24; y++) { px(22, y, l.trim()); px(25, y, l.trim()); }
        px(23, 20, l.feminine() ? l.trim() : 0x2a2a30); px(24, 20, l.feminine() ? l.trim() : 0x2a2a30); px(23, 21, l.feminine() ? l.shirt() : 0x2a2a30); px(24, 21, l.feminine() ? l.shirt() : 0x2a2a30);
        for (int y = 24; y < 31; y += 2) { px(22, y, l.trim()); px(25, y, l.trim()); }
        for (int x = 20; x < 28; x++) px(x, 27, mul(l.coat(), .7));
        if (l.feminine()) for (int x = 20; x < 28; x++) for (int y = 28; y < 32; y++) px(x, y, shade(l.legs(), x, y));
        // Arms in coat sleeves with cuffs and hands.
        rect(40, 16, 16, 16, l.coat()); rect(32, 48, 16, 16, l.coat());
        for (int x = 40; x < 56; x++) { px(x, 30, l.trim()); px(x, 31, l.skin()); }
        for (int x = 32; x < 48; x++) { px(x, 62, l.trim()); px(x, 63, l.skin()); }
        rect(48, 16, 4, 4, l.skin()); rect(40, 48, 4, 4, l.skin());
        // Legs: breeches or skirt, boots.
        rect(0, 16, 16, 16, l.legs()); rect(16, 48, 16, 16, l.legs());
        for (int x = 0; x < 16; x++) for (int y = 28; y < 32; y++) px(x, y, 0x231a14);
        for (int x = 16; x < 32; x++) for (int y = 60; y < 64; y++) px(x, y, 0x231a14);
        return image;
    }
    static void hair(Look l) {
        int h = l.hair();
        if (l.hairStyle() == Hair.BALD) { for (int x = 0; x < 32; x++) { if (x / 8 != 1) px(x, 10, h); } rect(24, 9, 8, 3, h); return; }
        rect(8, 0, 8, 8, h);
        rect(24, 8, 8, l.hairStyle() == Hair.LONG ? 8 : 5, h);
        for (int x : new int[]{0, 16}) rect(x, 8, 8, l.hairStyle() == Hair.LONG ? 7 : 3, h);
        for (int x = 8; x < 16; x++) px(x, 8, h);
        if (l.hairStyle() == Hair.CURLS) { px(8, 9, h); px(15, 9, h); px(9, 9, h); }
        if (l.hairStyle() == Hair.LONG) { for (int y = 9; y < 15; y++) { px(8, y, h); px(15, y, h); } }
        if (l.hairStyle() == Hair.BUN) { // a bun on the hat layer's back, and the parting
            rect(58, 9, 4, 3, h); px(12, 8, mul(h, .8));
        }
    }
    static void hat(Look l) {
        int c = l.hatColor();
        switch (l.hat()) {
            case TRICORN -> { rect(40, 0, 8, 8, c); for (int x = 32; x < 64; x++) { px(x, 8, c); px(x, 9, c); }
                for (int x = 40; x < 48; x++) px(x, 9, 0xd9b45a); }
            case BICORNE -> { rect(40, 0, 8, 8, c); for (int x = 32; x < 64; x++) { px(x, 8, c); px(x, 9, c); px(x, 10, c); }
                px(43, 9, 0xe0b850); px(44, 9, 0xe0b850); px(43, 10, 0xb03030); px(44, 10, 0xb03030); }
            case TOPHAT -> { rect(40, 0, 8, 8, c); for (int x = 32; x < 64; x++) { px(x, 8, c); px(x, 9, mul(c, 1.3)); } }
            case BONNET -> { rect(40, 0, 8, 8, c); for (int x = 32; x < 64; x++) if (x / 8 != 5) { px(x, 8, c); px(x, 9, c); px(x, 10, c); }
                for (int x = 40; x < 48; x++) px(x, 8, c); px(40, 9, c); px(47, 9, c); px(40, 10, 0xc04050); px(47, 10, 0xc04050); }
            case CAP -> { rect(40, 0, 8, 8, c); for (int x = 32; x < 64; x++) px(x, 8, c); for (int x = 40; x < 48; x++) px(x, 9, mul(c, .7)); }
            case BANDANA -> { rect(40, 0, 8, 8, c); for (int x = 32; x < 64; x++) px(x, 8, c); px(57, 9, c); px(58, 10, c); px(41, 8, 0xf0f0f0); px(45, 8, 0xf0f0f0); }
            default -> { }
        }
    }
}
