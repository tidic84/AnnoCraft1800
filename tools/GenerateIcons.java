import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.List;

/**
 * Paints the interface icons of AnnoCraft1800 in the spirit of Anno 1800's goods icons: each good, population tier,
 * public building and tool is drawn as the object it stands for (a fish for fish, a top hat for investors...),
 * with soft shading and a dark rim. Original vector drawings, JDK standard library only.
 * Run from the project root with JDK 17: {@code java tools/GenerateIcons.java [contact-sheet.png]}
 */
public final class GenerateIcons {
    static final Path OUT = Path.of("src/main/resources/assets/annocraft1800/textures/gui/icons");
    static final int SIZE = 64, WORK = 256;
    static final float U = WORK / 100f;
    static Graphics2D g;
    static final Map<String, Runnable> ICONS = new LinkedHashMap<>();

    public static void main(String[] args) throws IOException {
        define();
        Files.createDirectories(OUT);
        Map<String, BufferedImage> done = new LinkedHashMap<>();
        for (var e : ICONS.entrySet()) {
            BufferedImage big = new BufferedImage(WORK, WORK, BufferedImage.TYPE_INT_ARGB);
            g = big.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.scale(U, U);
            e.getValue().run();
            g.dispose();
            BufferedImage small = shrink(big);
            Path file = OUT.resolve(e.getKey().replace(':', '/') + ".png");
            Files.createDirectories(file.getParent());
            ImageIO.write(small, "png", file.toFile());
            done.put(e.getKey(), small);
        }
        if (args.length > 0) sheet(done, Path.of(args[0]));
        System.out.println("Painted " + done.size() + " icons.");
    }
    static BufferedImage shrink(BufferedImage img) {
        while (img.getWidth() > SIZE) {
            int s = img.getWidth() / 2;
            BufferedImage half = new BufferedImage(s, s, BufferedImage.TYPE_INT_ARGB);
            Graphics2D h = half.createGraphics();
            h.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            h.drawImage(img, 0, 0, s, s, null); h.dispose(); img = half;
        }
        return img;
    }
    /** Preview of every icon on an interface-coloured background, for review. */
    static void sheet(Map<String, BufferedImage> icons, Path file) throws IOException {
        int cols = 12, cell = 80, rows = (icons.size() + cols - 1) / cols;
        BufferedImage s = new BufferedImage(cols * cell, rows * (cell + 14), BufferedImage.TYPE_INT_RGB);
        Graphics2D h = s.createGraphics();
        h.setColor(new Color(0x213140)); h.fillRect(0, 0, s.getWidth(), s.getHeight());
        h.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 9)); int i = 0;
        for (var e : icons.entrySet()) {
            int x = i % cols * cell, y = i / cols * (cell + 14);
            h.drawImage(e.getValue(), x + 8, y + 4, null);
            h.drawImage(e.getValue(), x + 56, y + 52, 16, 16, null);
            h.setColor(new Color(0xe7cf8a)); h.drawString(e.getKey(), x + 2, y + cell + 8); i++;
        }
        h.dispose(); ImageIO.write(s, "png", file.toFile());
    }

    // ---------------------------------------------------------------- painting toolkit (100 x 100 design grid)

    static Color c(int rgb) { return new Color(rgb); }
    static Color mix(Color a, Color b, float t) {
        return new Color(Math.round(a.getRed() + (b.getRed() - a.getRed()) * t), Math.round(a.getGreen() + (b.getGreen() - a.getGreen()) * t),
                Math.round(a.getBlue() + (b.getBlue() - a.getBlue()) * t), Math.round(a.getAlpha() + (b.getAlpha() - a.getAlpha()) * t));
    }
    static Color light(int rgb, float t) { return mix(c(rgb), Color.WHITE, t); }
    static Color dark(int rgb, float t) { return mix(c(rgb), Color.BLACK, t); }
    static Color alpha(int rgb, int a) { return new Color(rgb >> 16 & 255, rgb >> 8 & 255, rgb & 255, a); }

    /** Absolute SVG-like path: M L H V Q C Z with comma or space separated numbers. */
    static Path2D path(String d) {
        Path2D p = new Path2D.Float();
        Scanner s = new Scanner(d.replace(",", " ").replaceAll("([MLHVQCZ])", " $1 ")).useLocale(Locale.ROOT);
        float x = 0, y = 0; String cmd = "M";
        while (s.hasNext()) {
            if (s.hasNextFloat()) {
                switch (cmd) {
                    case "M" -> { x = s.nextFloat(); y = s.nextFloat(); p.moveTo(x, y); cmd = "L"; }
                    case "L" -> { x = s.nextFloat(); y = s.nextFloat(); p.lineTo(x, y); }
                    case "H" -> { x = s.nextFloat(); p.lineTo(x, y); }
                    case "V" -> { y = s.nextFloat(); p.lineTo(x, y); }
                    case "Q" -> { float a = s.nextFloat(), b = s.nextFloat(); x = s.nextFloat(); y = s.nextFloat(); p.quadTo(a, b, x, y); }
                    case "C" -> { float a = s.nextFloat(), b = s.nextFloat(), e = s.nextFloat(), f = s.nextFloat(); x = s.nextFloat(); y = s.nextFloat(); p.curveTo(a, b, e, f, x, y); }
                    default -> throw new IllegalArgumentException(d);
                }
            } else {
                cmd = s.next();
                if (cmd.equals("Z")) p.closePath();
            }
        }
        return p;
    }
    static Shape ell(float x, float y, float w, float h) { return new Ellipse2D.Float(x, y, w, h); }
    static Shape circ(float cx, float cy, float r) { return ell(cx - r, cy - r, 2 * r, 2 * r); }
    static Shape rect(float x, float y, float w, float h) { return new Rectangle2D.Float(x, y, w, h); }
    static Shape rrect(float x, float y, float w, float h, float r) { return new RoundRectangle2D.Float(x, y, w, h, r, r); }
    static Shape poly(float... p) {
        Path2D path = new Path2D.Float(); path.moveTo(p[0], p[1]);
        for (int i = 2; i < p.length; i += 2) path.lineTo(p[i], p[i + 1]);
        path.closePath(); return path;
    }
    static Shape rotated(Shape s, float degrees, float cx, float cy) {
        return AffineTransform.getRotateInstance(Math.toRadians(degrees), cx, cy).createTransformedShape(s);
    }
    static Shape moved(Shape s, float dx, float dy) { return AffineTransform.getTranslateInstance(dx, dy).createTransformedShape(s); }

    /** Painted volume: light from the top left, darker towards the bottom right, dark rim. */
    static void paint(Shape s, int rgb) { paint(s, rgb, 1.6f); }
    static void paint(Shape s, int rgb, float rim) {
        Rectangle2D b = s.getBounds2D();
        g.setPaint(new LinearGradientPaint((float) b.getMinX(), (float) b.getMinY(), (float) b.getMaxX(), (float) b.getMaxY() + .01f,
                new float[]{0, .45f, 1}, new Color[]{light(rgb, .32f), c(rgb), dark(rgb, .38f)}));
        g.fill(s);
        if (rim > 0) { g.setColor(dark(rgb, .62f)); g.setStroke(new BasicStroke(rim, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)); g.draw(s); }
    }
    /** Vertical light band, for cylinders, bottles and logs seen from the side. */
    static void cyl(Shape s, int rgb) {
        Rectangle2D b = s.getBounds2D();
        g.setPaint(new LinearGradientPaint((float) b.getMinX(), 0, (float) b.getMaxX() + .01f, 0,
                new float[]{0, .28f, .55f, 1}, new Color[]{dark(rgb, .3f), light(rgb, .35f), c(rgb), dark(rgb, .5f)}));
        g.fill(s); rim(s, rgb);
    }
    /** Round volume lit from the top left. */
    static void ball(Shape s, int rgb) {
        Rectangle2D b = s.getBounds2D();
        float r = (float) Math.max(b.getWidth(), b.getHeight());
        g.setPaint(new RadialGradientPaint(new Point2D.Double(b.getMinX() + b.getWidth() * .35, b.getMinY() + b.getHeight() * .3), r * .85f,
                new float[]{0, .5f, 1}, new Color[]{light(rgb, .45f), c(rgb), dark(rgb, .45f)}));
        g.fill(s); rim(s, rgb);
    }
    static void flat(Shape s, Color color) { g.setColor(color); g.fill(s); }
    static void flat(Shape s, int rgb) { flat(s, c(rgb)); }
    static void rim(Shape s, int rgb) { g.setColor(dark(rgb, .62f)); g.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)); g.draw(s); }
    static void line(float x1, float y1, float x2, float y2, float w, Color color) {
        g.setColor(color); g.setStroke(new BasicStroke(w, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)); g.draw(new Line2D.Float(x1, y1, x2, y2));
    }
    static void line(float x1, float y1, float x2, float y2, float w, int rgb) { line(x1, y1, x2, y2, w, c(rgb)); }
    static void stroke(Shape s, float w, Color color) { g.setColor(color); g.setStroke(new BasicStroke(w, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)); g.draw(s); }
    static void stroke(Shape s, float w, int rgb) { stroke(s, w, c(rgb)); }
    /** Soft white sheen clipped to a shape. */
    static void gloss(Shape s, Shape spot, int a) {
        Shape old = g.getClip(); g.clip(s); flat(spot, new Color(255, 255, 255, a)); g.setClip(old);
    }
    /** Soft contact shadow under the object. */
    static void shadow(float cx, float cy, float w, float h) {
        for (int i = 6; i > 0; i--) { float k = 1 + i * .09f; flat(ell(cx - w * k / 2, cy - h * k / 2, w * k, h * k), new Color(0, 0, 0, 16)); }
    }
    /** Box in cabinet projection: front face, lit top, shaded side. */
    static void box(float x, float y, float w, float h, float dx, float dy, int rgb) {
        Shape front = rect(x, y, w, h), top = poly(x, y, x + w, y, x + w + dx, y - dy, x + dx, y - dy),
                side = poly(x + w, y, x + w + dx, y - dy, x + w + dx, y + h - dy, x + w, y + h);
        flat(front, c(rgb)); flat(top, light(rgb, .3f)); flat(side, dark(rgb, .3f));
        Path2D outline = new Path2D.Float(); outline.append(front, false); outline.append(top, false); outline.append(side, false);
        rim(outline, rgb);
    }
    /** Upright cylinder with lit top. */
    static void drum(float x, float y, float w, float h, int rgb, int topRgb) {
        float e = w * .32f;
        Path2D body = new Path2D.Float(); body.append(rect(x, y + e / 2, w, h - e / 2), false);
        Area a = new Area(rect(x, y + e / 2, w, h - e)); a.add(new Area(ell(x, y + h - e, w, e)));
        cyl(a, rgb);
        paint(ell(x, y, w, e), topRgb, 1.4f);
    }
    /** Medallion background used by population and building emblems. */
    static void medal(int rgb) {
        ball(circ(50, 50, 44), rgb);
        stroke(circ(50, 50, 40), 2.4f, alpha(0xe8cf8a, 170));
    }

    // ---------------------------------------------------------------- the icons

    static void icon(String id, Runnable r) { ICONS.put(id, r); }
    /** Draws a motif enlarged around the centre of the icon. */
    static void zoom(float k, Runnable r) {
        AffineTransform t = g.getTransform(); g.translate(50, 50); g.scale(k, k); g.translate(-50, -50); r.run(); g.setTransform(t);
    }

    static void define() {
        // Treasury.
        icon("coins", () -> {
            shadow(50, 84, 70, 12);
            for (int i = 0; i < 4; i++) coinSide(26, 70 - i * 9, 44);
            coinFace(66, 56, 22);
        });

        // ------------------------------------------------ farmers
        icon("fish", () -> {
            shadow(50, 80, 74, 10);
            Shape tail = path("M 74 50 L 92 34 Q 86 50 92 68 Z"); paint(tail, 0x5b7e94);
            Shape body = path("M 8 52 Q 30 26 62 40 Q 72 44 78 50 Q 72 58 62 62 Q 30 76 8 52 Z");
            paint(body, 0x8fb0c2);
            gloss(body, ell(10, 30, 70, 20), 70);
            Shape back = path("M 18 44 Q 38 32 62 40 Q 70 44 76 49 Q 46 42 18 47 Z"); flat(back, alpha(0x35566e, 150));
            paint(path("M 36 36 Q 46 22 60 36 Z"), 0x6b8ea4, 1.2f);
            paint(path("M 40 64 Q 48 74 56 62 Z"), 0x6b8ea4, 1.2f);
            stroke(path("M 26 42 Q 22 52 28 62"), 1.6f, 0x3d5566);
            flat(circ(17, 48, 3.6f), 0x1b1f24); flat(circ(16.2f, 47, 1.2f), Color.WHITE);
            for (int i = 0; i < 5; i++) stroke(path("M " + (34 + i * 7) + " 50 Q " + (37 + i * 7) + " 53 " + (34 + i * 7) + " 56"), .8f, alpha(0x3d5566, 140));
        });
        icon("wood", () -> {
            shadow(50, 82, 80, 12);
            log(10, 58, 60, 18); log(30, 58, 60, 18); log(20, 40, 60, 18);
        });
        icon("timber", () -> {
            shadow(50, 84, 84, 12);
            for (int i = 0; i < 4; i++) {
                float y = 70 - i * 11, x = 8 + (i % 2) * 4;
                box(x, y, 66, 8, 16, 12, 0xcc9a5c);
                for (int k = 0; k < 3; k++) line(x + 10 + k * 20, y + 3, x + 22 + k * 20, y + 3, .7f, alpha(0x7a5230, 120));
            }
        });
        icon("wool", () -> skein(0xece3cc));
        icon("work_clothes", () -> {
            shadow(50, 88, 70, 10);
            Shape jacket = path("M 30 16 L 42 12 Q 50 20 58 12 L 70 16 L 88 34 L 80 48 L 72 42 L 72 86 L 28 86 L 28 42 L 20 48 L 12 34 Z");
            paint(jacket, 0x7d6448);
            line(50, 20, 50, 86, 1.6f, 0x4a3828);
            paint(path("M 42 12 L 50 30 L 58 12 Q 50 20 42 12 Z"), 0xd8cfb8, 1.2f);
            for (int i = 0; i < 4; i++) paint(circ(54, 36 + i * 12, 2.2f), 0xc9a35a, .8f);
            paint(rect(31, 58, 13, 10), 0x6a543a, 1.2f);
            paint(rect(14, 30, 6, 4), 0x6a543a, 0);
        });
        icon("potatoes", () -> {
            shadow(50, 80, 80, 12);
            potato(12, 46, 40, 30, -10); potato(46, 44, 42, 32, 12); potato(28, 26, 38, 28, 4);
        });
        icon("schnapps", () -> {
            shadow(50, 90, 46, 9);
            Shape bottle = path("M 43 10 L 57 10 L 57 30 Q 72 38 72 52 L 72 86 Q 72 90 68 90 L 32 90 Q 28 90 28 86 L 28 52 Q 28 38 43 30 Z");
            flat(bottle, alpha(0xdbeee8, 120));
            Shape liquid = path("M 29 56 L 71 56 L 71 86 Q 71 89 68 89 L 32 89 Q 29 89 29 86 Z");
            Shape old = g.getClip(); g.clip(bottle); cyl(liquid, 0xe8e4c8); g.setClip(old);
            stroke(bottle, 1.8f, 0x4a6660);
            paint(rect(42, 4, 16, 9), 0xa07a50, 1.4f);
            paint(rrect(35, 60, 30, 18, 4), 0xe8dcc0, 1.2f);
            line(40, 66, 60, 66, 1.2f, 0x8a3a2a); line(40, 71, 56, 71, 1, 0x8a7a5a);
            gloss(bottle, rect(33, 30, 6, 56), 120);
        });

        // ------------------------------------------------ workers
        icon("grain", () -> {
            shadow(50, 90, 50, 9);
            for (int i = -3; i <= 3; i++) line(50 + i * 1.6f, 88, 50 + i * 7, 30, 1.8f, 0xc49a3a);
            for (int i = -3; i <= 3; i++) ear(50 + i * 7, 30, i * 9);
            paint(rrect(40, 58, 20, 7, 3), 0x8a5a2a, 1.2f);
        });
        icon("flour", () -> {
            shadow(50, 88, 70, 10);
            Shape sack = path("M 30 26 Q 22 20 30 14 L 40 22 Q 50 18 60 22 L 70 14 Q 78 20 70 26 Q 84 50 78 82 Q 50 92 22 82 Q 16 50 30 26 Z");
            paint(sack, 0xe2d4b0);
            stroke(path("M 32 30 Q 50 36 68 30"), 2.4f, 0x8a6a3a);
            paint(rrect(34, 50, 32, 18, 5), 0xc9b48a, 1.2f);
            flat(ell(42, 54, 16, 10), alpha(0xffffff, 200));
            flat(ell(56, 78, 34, 10), alpha(0xfaf6ea, 230));
        });
        icon("bread", () -> {
            shadow(50, 78, 84, 12);
            Shape loaf = path("M 10 62 Q 8 32 50 30 Q 92 32 90 62 Q 90 74 50 74 Q 10 74 10 62 Z");
            paint(loaf, 0xc98a3e);
            gloss(loaf, ell(14, 28, 60, 22), 50);
            for (int i = 0; i < 4; i++) stroke(path("M " + (24 + i * 16) + " 42 Q " + (30 + i * 16) + " 38 " + (34 + i * 16) + " 50"), 3, 0xedd29a);
        });
        icon("pigs", () -> {
            shadow(50, 86, 70, 10);
            paint(path("M 18 22 L 34 34 L 22 44 Z"), 0xe48f8c); paint(path("M 82 22 L 66 34 L 78 44 Z"), 0xe48f8c);
            Shape head = ell(16, 26, 68, 58); ball(head, 0xf2aaa4);
            paint(ell(34, 54, 32, 22), 0xe48f8c);
            flat(ell(41, 61, 6, 9), 0x8a3a3a); flat(ell(53, 61, 6, 9), 0x8a3a3a);
            flat(circ(35, 46, 3.4f), 0x1b1f24); flat(circ(65, 46, 3.4f), 0x1b1f24);
            flat(circ(34, 45, 1.1f), Color.WHITE); flat(circ(64, 45, 1.1f), Color.WHITE);
        });
        icon("sausages", () -> {
            shadow(50, 82, 84, 12);
            stroke(path("M 14 60 Q 20 40 36 40 Q 50 42 52 30 Q 60 22 72 30"), 1.4f, 0xd8cfb8);
            sausage(path("M 8 66 Q 10 46 30 44 Q 36 46 34 52 Q 20 54 18 70 Q 12 74 8 66 Z"));
            sausage(path("M 34 50 Q 42 40 56 44 Q 66 48 64 58 Q 58 62 54 56 Q 46 52 40 58 Q 32 60 34 50 Z"));
            sausage(path("M 58 50 Q 64 30 82 32 Q 94 36 90 46 Q 84 48 80 44 Q 70 44 66 56 Q 58 60 58 50 Z"));
        });
        icon("tallow", () -> {
            shadow(50, 84, 80, 12);
            drum(16, 46, 56, 36, 0x9a7a52, 0xe8dca0);
            paint(path("M 26 50 Q 44 40 62 50 Q 52 56 26 50 Z"), 0xf0e6b8, 1);
            paint(rrect(62, 24, 20, 34, 6), 0xeadca0);
            line(72, 24, 72, 16, 1.6f, 0x3a3020);
            flat(path("M 72 4 Q 78 12 72 16 Q 66 12 72 4 Z"), 0xf2b23a);
        });
        icon("soap", () -> {
            shadow(50, 84, 80, 12);
            box(10, 58, 46, 18, 14, 10, 0xd7c2e6);
            box(36, 40, 40, 16, 14, 10, 0xc6e0d6);
            for (float[] b : new float[][]{{30, 30, 7}, {18, 38, 5}, {44, 22, 4}, {64, 18, 5}}) { flat(circ(b[0], b[1], b[2]), alpha(0xdff2ff, 120)); stroke(circ(b[0], b[1], b[2]), 1, alpha(0x8ab0c8, 200)); flat(circ(b[0] - b[2] * .4f, b[1] - b[2] * .4f, b[2] * .25f), Color.WHITE); }
        });
        icon("hops", () -> {
            shadow(50, 86, 70, 10);
            leaf(path("M 50 18 Q 80 8 88 30 Q 70 40 50 18 Z"), 0x4f8a3a);
            line(50, 12, 46, 30, 1.8f, 0x4a6a2a);
            hopCone(22, 30, 30, 42); hopCone(46, 38, 32, 46);
        });
        icon("beer", () -> {
            shadow(46, 90, 60, 10);
            stroke(path("M 68 40 Q 88 40 88 56 Q 88 72 68 72"), 6, 0x8a6a3a);
            stroke(path("M 68 40 Q 88 40 88 56 Q 88 72 68 72"), 3, 0xb8945a);
            Shape mug = rect(18, 30, 52, 56);
            cyl(mug, 0xd99a2a);
            for (int i = 0; i < 3; i++) line(30 + i * 14, 40, 30 + i * 14, 80, 1.4f, alpha(0x8a5a10, 120));
            Shape foam = path("M 14 34 Q 12 20 26 20 Q 30 10 42 14 Q 52 8 60 16 Q 74 14 74 28 Q 76 36 66 36 L 22 36 Q 14 38 14 34 Z");
            paint(foam, 0xf6f0e0, 1.2f);
            flat(path("M 64 36 Q 70 44 66 50 Q 62 44 64 36 Z"), 0xf6f0e0);
        });
        icon("clay", () -> {
            shadow(50, 82, 80, 12);
            Shape lump = path("M 14 70 Q 10 44 34 38 Q 40 22 60 28 Q 84 30 86 54 Q 92 74 70 78 L 26 80 Q 14 80 14 70 Z");
            paint(lump, 0xc0784a);
            gloss(lump, ell(24, 30, 40, 18), 60);
            stroke(path("M 30 60 Q 46 52 66 60"), 1.4f, alpha(0x7a3a20, 160));
            stroke(path("M 46 44 Q 56 40 70 46"), 1.2f, alpha(0x7a3a20, 140));
        });
        icon("bricks", () -> {
            shadow(50, 86, 84, 12);
            box(8, 68, 36, 14, 10, 8, 0xb34c34); box(46, 68, 36, 14, 10, 8, 0xa84630);
            box(26, 52, 36, 14, 10, 8, 0xbd5638);
            box(16, 36, 36, 14, 10, 8, 0xb04a32); box(42, 22, 30, 14, 10, 8, 0xc05a3c);
        });
        icon("iron", () -> {
            shadow(50, 82, 80, 12);
            Shape rock = poly(14, 66, 22, 36, 44, 22, 70, 26, 88, 48, 82, 74, 52, 82, 24, 80);
            paint(rock, 0x7c7a78);
            stroke(path("M 44 22 L 50 50 L 88 48 M 50 50 L 52 82 M 22 36 L 50 50"), 1.2f, alpha(0x3a3836, 160));
            for (float[] s : new float[][]{{34, 40, 7}, {64, 38, 6}, {36, 64, 6}, {68, 62, 7}, {54, 30, 4}})
                paint(circ(s[0], s[1], s[2]), 0xb4693a, 1.2f);
        });
        icon("coal", () -> {
            shadow(50, 82, 84, 12);
            lump(poly(10, 74, 16, 52, 34, 46, 46, 60, 40, 80, 16, 82), 0x2c2d33);
            lump(poly(50, 80, 46, 56, 62, 42, 84, 50, 90, 72, 74, 82), 0x34353c);
            lump(poly(30, 50, 34, 26, 54, 18, 70, 30, 64, 50, 44, 56), 0x26272c);
        });
        icon("steel", () -> {
            shadow(50, 84, 84, 12);
            ingot(10, 64, 0x8f9cab); ingot(46, 64, 0x9aa7b6); ingot(28, 44, 0xa4b1c0);
        });
        icon("steel_beams", () -> {
            shadow(50, 84, 86, 12);
            beam(8, 60, 0x6d7884); beam(22, 36, 0x7a8692);
        });
        icon("weapons", () -> zoom(1.3f, () -> {
            shadow(50, 88, 80, 10);
            Shape stock = rotated(path("M 14 46 L 46 46 L 48 52 L 30 56 L 12 60 Q 8 54 14 46 Z"), -35, 50, 50);
            Shape barrel = rotated(rect(44, 46, 50, 5), -35, 50, 50);
            paint(stock, 0x7a4a2a); paint(barrel, 0x55606a, 1.2f);
            Shape blade = rotated(path("M 50 47 L 92 49 Q 96 50 92 51 L 50 53 Z"), 35, 50, 50);
            paint(blade, 0xcfd6dc, 1.2f);
            gloss(blade, rotated(rect(50, 47, 44, 2), 35, 50, 50), 160);
            paint(rotated(rrect(38, 44, 8, 12, 2), 35, 50, 50), 0xc9a24a, 1.2f);
            paint(rotated(rrect(22, 47, 16, 6, 3), 35, 50, 50), 0x3a2a1a, 1.2f);
        }));
        icon("sails", () -> {
            shadow(50, 90, 60, 8);
            line(30, 8, 30, 90, 4, 0x6a4a2a);
            line(30, 14, 84, 20, 2.4f, 0x6a4a2a);
            Shape sail = path("M 32 18 L 82 22 Q 92 50 82 80 L 32 76 Q 42 48 32 18 Z");
            paint(sail, 0xf0e6cc);
            stroke(path("M 36 38 Q 60 42 86 42 M 36 58 Q 60 62 88 60"), 1, alpha(0xa89a78, 180));
            line(82, 80, 92, 92, 1.2f, 0x6a4a2a);
        });

        // ------------------------------------------------ artisans
        icon("beef", () -> {
            shadow(50, 82, 84, 12);
            Shape meat = path("M 12 52 Q 12 26 46 24 Q 82 22 88 46 Q 92 70 60 76 Q 20 82 12 52 Z");
            paint(meat, 0xc84a40);
            stroke(path("M 14 50 Q 16 30 46 27 Q 80 25 86 46"), 4, 0xf2dcc8);
            stroke(path("M 30 52 Q 50 40 70 54 M 36 64 Q 54 58 72 66"), 1.6f, alpha(0xf2c4b0, 200));
            paint(circ(66, 46, 8), 0xf4ead6, 1.2f); flat(circ(66, 46, 3.4f), 0xd8c2a0);
        });
        icon("canned_food", () -> {
            shadow(50, 88, 64, 10);
            drum(22, 22, 56, 66, 0xb8bec6, 0xd6dce2);
            Shape label = rect(22, 40, 56, 30);
            Shape old = g.getClip(); g.clip(rect(22, 40, 56, 30)); cyl(label, 0xb0382e); g.setClip(old);
            paint(ell(38, 46, 24, 18), 0xf0e2b8, 1.2f);
            paint(ell(44, 51, 12, 8), 0xc84a40, 1);
            line(22, 40, 78, 40, 1.2f, 0x6a1a14); line(22, 70, 78, 70, 1.2f, 0x6a1a14);
            stroke(ell(30, 24, 40, 12), 1, alpha(0x6a7078, 200));
        });
        icon("sewing_machines", () -> {
            shadow(50, 88, 88, 10);
            box(8, 72, 72, 12, 12, 8, 0x8a5a32);
            Shape arm = path("M 18 72 L 18 40 Q 18 26 32 26 L 78 26 Q 86 26 86 34 L 86 48 L 76 48 L 76 40 L 32 40 L 32 72 Z");
            paint(arm, 0x26272c);
            line(80, 48, 80, 66, 2.4f, 0xb8bec6);
            paint(rect(74, 62, 14, 10), 0x26272c, 1.2f);
            paint(circ(16, 44, 12), 0x3a3b42); stroke(circ(16, 44, 8), 1.4f, 0xc9a24a);
            line(36, 33, 68, 33, 2, 0xc9a24a); flat(circ(52, 33, 3), 0xe8cf8a);
            paint(rect(56, 18, 6, 8), 0xc9a24a, 1);
        });
        icon("sand", () -> {
            shadow(50, 82, 88, 12);
            Shape pile = path("M 6 80 Q 20 72 32 50 Q 44 28 56 30 Q 66 32 76 52 Q 86 72 96 80 Z");
            paint(pile, 0xe6d4a4);
            gloss(pile, ell(30, 28, 30, 30), 70);
            for (float[] s : new float[][]{{40, 52}, {58, 60}, {50, 42}, {30, 70}, {70, 72}, {62, 46}}) { flat(circ(s[0], s[1], 1.6f), Color.WHITE); }
            stroke(path("M 22 74 Q 40 64 54 70 M 50 58 Q 62 54 72 62"), 1, alpha(0xa8905a, 160));
        });
        icon("glass", () -> {
            shadow(50, 86, 80, 10);
            Shape back = poly(32, 14, 88, 22, 76, 78, 20, 70);
            flat(back, alpha(0xbfe6ee, 120)); stroke(back, 1.8f, 0x5a8a96);
            Shape pane = poly(14, 22, 70, 30, 60, 86, 6, 78);
            flat(pane, alpha(0xd8f4f8, 150)); stroke(pane, 2, 0x4a7a86);
            line(22, 40, 46, 30, 3, alpha(0xffffff, 200)); line(18, 54, 56, 38, 2, alpha(0xffffff, 150));
        });
        icon("windows", () -> {
            shadow(50, 90, 76, 8);
            paint(rect(16, 10, 68, 76), 0xece6da);
            Shape glass = rect(22, 16, 56, 64);
            g.setPaint(new LinearGradientPaint(22, 16, 78, 80, new float[]{0, 1}, new Color[]{c(0x9fd2e6), c(0x3e6e8e)})); g.fill(glass);
            line(50, 16, 50, 80, 4, 0xece6da); line(22, 44, 78, 44, 4, 0xece6da);
            line(28, 34, 40, 22, 2, alpha(0xffffff, 170)); line(56, 70, 70, 56, 2, alpha(0xffffff, 120));
            paint(rect(10, 84, 80, 7), 0xc9bfae, 1.2f);
            rim(rect(16, 10, 68, 76), 0xa89e8e);
        });
        // ------------------------------------------------ engineers and investors
        icon("light_bulbs", () -> {
            for (int i = 8; i > 0; i--) flat(circ(50, 40, 30 + i * 2.4f), new Color(255, 220, 120, 10));
            Shape bulb = path("M 50 8 Q 80 8 80 38 Q 80 54 64 64 L 64 72 L 36 72 L 36 64 Q 20 54 20 38 Q 20 8 50 8 Z");
            g.setPaint(new RadialGradientPaint(new Point2D.Float(44, 30), 40, new float[]{0, .6f, 1}, new Color[]{c(0xfffbe0), c(0xffe48a), c(0xe8b84a)}));
            g.fill(bulb); stroke(bulb, 1.6f, 0x8a6a2a);
            stroke(path("M 42 64 L 42 44 Q 46 36 50 44 Q 54 36 58 44 L 58 64"), 1.6f, 0x8a5a1a);
            paint(rect(36, 70, 28, 16), 0xa8aeb6);
            for (int i = 0; i < 3; i++) line(36, 74 + i * 4, 64, 72 + i * 4, 1.2f, 0x5a606a);
            paint(path("M 42 86 L 58 86 L 54 94 L 46 94 Z"), 0x3a3c40, 1.2f);
            gloss(bulb, ell(28, 14, 16, 26), 160);
        });
        icon("grapes", () -> {
            shadow(50, 90, 50, 8);
            leaf(path("M 50 16 Q 74 2 86 20 Q 74 34 50 16 Z"), 0x5a8a3a);
            line(50, 8, 48, 24, 2.2f, 0x6a4a2a);
            float[][] b = {{36, 30}, {50, 28}, {64, 30}, {30, 44}, {44, 42}, {58, 42}, {70, 44}, {38, 56}, {52, 56}, {64, 56}, {44, 69}, {58, 69}, {51, 81}};
            for (float[] p : b) ball(circ(p[0], p[1], 8), 0x6e3a82);
        });
        icon("champagne", () -> {
            shadow(50, 92, 70, 8);
            Shape bottle = path("M 30 6 L 42 6 L 42 30 Q 54 38 54 50 L 54 88 Q 54 92 50 92 L 22 92 Q 18 92 18 88 L 18 50 Q 18 38 30 30 Z");
            cyl(bottle, 0x2e5a3c);
            paint(path("M 29 4 L 43 4 L 43 30 Q 36 34 29 30 Z"), 0xd8b24a);
            paint(rrect(22, 56, 28, 20, 3), 0xf2e8cc, 1.2f);
            line(26, 62, 46, 62, 1.6f, 0xb08a3a); line(28, 68, 44, 68, 1, 0x7a6a4a);
            Shape glass = path("M 62 40 L 92 40 Q 90 58 77 60 Z");
            flat(glass, alpha(0xf6e6a8, 200)); stroke(glass, 1.4f, 0x8a7a4a);
            line(77, 60, 77, 84, 1.8f, alpha(0xd8e4e4, 255)); line(68, 86, 86, 86, 2.4f, 0xc8d4d4);
            for (float[] p : new float[][]{{74, 46}, {80, 50}, {78, 44}}) flat(circ(p[0], p[1], 1.2f), Color.WHITE);
        });
        icon("gold", () -> {
            shadow(50, 82, 84, 12);
            nugget(poly(10, 70, 18, 52, 36, 48, 44, 62, 36, 80, 16, 80));
            nugget(poly(48, 78, 46, 58, 62, 46, 82, 54, 88, 72, 70, 82));
            nugget(poly(30, 46, 34, 28, 50, 20, 66, 30, 62, 48, 44, 52));
        });
        icon("pocket_watches", () -> {
            shadow(50, 90, 64, 9);
            stroke(path("M 50 16 Q 80 0 88 30 Q 92 50 80 60"), 2.4f, 0xc9a24a);
            paint(rrect(44, 8, 12, 10, 3), 0xd8b24a, 1.2f);
            ball(circ(48, 56, 32), 0xd8b24a);
            Shape face = circ(48, 56, 25); flat(face, 0xf6f0e0); stroke(face, 1.4f, 0x8a6a2a);
            for (int i = 0; i < 12; i++) { double a = Math.toRadians(i * 30); line(48 + (float) Math.cos(a) * 21, 56 + (float) Math.sin(a) * 21, 48 + (float) Math.cos(a) * 24, 56 + (float) Math.sin(a) * 24, 1.4f, 0x2a2a2a); }
            line(48, 56, 48, 40, 2, 0x1b1f24); line(48, 56, 60, 62, 2, 0x1b1f24); flat(circ(48, 56, 2.2f), 0x8a6a2a);
        });
        icon("jewelry", () -> {
            shadow(50, 86, 70, 10);
            Shape ring = new Area(ell(20, 40, 60, 44)); ((Area) ring).subtract(new Area(ell(28, 48, 44, 30)));
            paint(ring, 0xe0b84a);
            gloss(ring, ell(22, 40, 30, 14), 120);
            Shape gem = poly(36, 30, 44, 18, 56, 18, 64, 30, 50, 46);
            g.setPaint(new LinearGradientPaint(36, 18, 64, 46, new float[]{0, .5f, 1}, new Color[]{c(0xcff4ff), c(0x4ab4e8), c(0x1a5a9a)})); g.fill(gem);
            stroke(gem, 1.4f, 0x1a3a6a);
            stroke(path("M 36 30 L 64 30 M 44 18 L 46 30 L 50 46 L 54 30 L 56 18"), .8f, alpha(0xffffff, 170));
            paint(rect(42, 38, 16, 6), 0xd8b24a, 1);
        });
        // ------------------------------------------------ New World
        icon("plantains", () -> {
            shadow(50, 86, 80, 10);
            line(56, 6, 52, 22, 5, 0x6a5a2a);
            for (int i = 0; i < 3; i++) {
                Shape b = rotated(path("M 52 18 Q 8 24 12 74 Q 16 86 28 80 Q 26 40 56 30 Z"), -30 + i * 30, 54, 22);
                paint(b, i == 1 ? 0xc8c845 : 0xb8bc3e);
            }
        });
        icon("alpaca_wool", () -> skein(0xb08a62));
        icon("ponchos", () -> {
            shadow(50, 90, 76, 9);
            Shape poncho = path("M 40 10 L 60 10 L 92 70 L 70 86 L 30 86 L 8 70 Z");
            paint(poncho, 0xb83a2e);
            Shape old = g.getClip(); g.clip(poncho);
            int[] stripes = {0xe8b83a, 0x2e7a7a, 0xf0e6cc, 0x2e7a7a, 0xe8b83a};
            for (int i = 0; i < stripes.length; i++) flat(rect(0, 34 + i * 9, 100, 4), stripes[i]);
            g.setClip(old); rim(poncho, 0xb83a2e);
            paint(ell(41, 8, 18, 9), 0x6a1a14, 1);
            for (int i = 0; i < 9; i++) line(30 + i * 5, 86, 30 + i * 5, 92, 1.4f, 0xe8b83a);
        });
        icon("sugar_cane", () -> {
            shadow(50, 90, 60, 8);
            for (int i = 0; i < 3; i++) {
                float x = 30 + i * 16, top = 14 + (i % 2) * 8;
                cyl(rect(x, top, 9, 88 - top), 0xa8b84a);
                for (float y = top + 14; y < 86; y += 16) line(x, y, x + 9, y, 1.6f, 0x6a7a2a);
            }
            leaf(path("M 36 30 Q 10 20 6 40 Q 22 36 36 34 Z"), 0x5a8a3a);
            leaf(path("M 70 26 Q 92 12 96 32 Q 82 30 70 32 Z"), 0x5a8a3a);
        });
        icon("rum", () -> {
            shadow(50, 92, 60, 8);
            Shape bottle = path("M 36 6 L 50 6 L 50 24 Q 66 30 66 44 L 66 88 Q 66 92 62 92 L 24 92 Q 20 92 20 88 L 20 44 Q 20 30 36 24 Z");
            cyl(bottle, 0x6a3418);
            paint(rect(35, 2, 16, 8), 0x2a1a10, 1.2f);
            paint(rrect(24, 52, 38, 24, 3), 0xe8d6a8, 1.2f);
            paint(ell(36, 56, 14, 10), 0xb03a2a, 1);
            line(28, 70, 58, 70, 1, 0x7a5a3a);
            gloss(bottle, rect(26, 30, 5, 56), 90);
        });
        icon("coffee_beans", () -> {
            shadow(50, 82, 80, 12);
            bean(14, 46, -20); bean(46, 50, 25); bean(32, 22, 5);
        });
        icon("coffee", () -> {
            shadow(50, 86, 84, 10);
            for (int i = 0; i < 2; i++) stroke(path("M " + (40 + i * 14) + " 30 Q " + (34 + i * 14) + " 22 " + (40 + i * 14) + " 16 Q " + (46 + i * 14) + " 10 " + (40 + i * 14) + " 4"), 2.2f, alpha(0xffffff, 160));
            paint(ell(8, 70, 84, 16), 0xf2eee6);
            stroke(path("M 72 44 Q 90 44 86 58 Q 82 68 70 64"), 4, 0xe8e2d6);
            Shape cup = path("M 20 38 L 80 38 Q 78 70 50 74 Q 22 70 20 38 Z");
            paint(cup, 0xf6f2ea);
            paint(ell(22, 34, 56, 10), 0x4a2a16, 1.2f);
            line(22, 50, 78, 50, 2, 0x2e6a8a);
        });
        icon("tobacco", () -> {
            shadow(50, 86, 80, 10);
            leaf(path("M 18 86 Q 8 40 40 14 Q 54 50 18 86 Z"), 0x9a7a3a);
            leaf(path("M 36 88 Q 46 30 86 18 Q 82 66 36 88 Z"), 0x7a6a30);
        });
        icon("cigars", () -> {
            shadow(50, 84, 84, 10);
            for (int i = 0; i < 3; i++) {
                Shape cigar = rotated(rrect(10, 44 + i * 2, 80, 12, 12), -20 + i * 14, 50, 50);
                cyl(cigar, 0x6e4426);
                paint(rotated(rect(66, 44 + i * 2, 8, 12), -20 + i * 14, 50, 50), 0xc03a2a, 1);
                flat(rotated(rect(68, 47 + i * 2, 4, 6), -20 + i * 14, 50, 50), 0xe8c24a);
            }
            for (int i = 0; i < 4; i++) flat(circ(14, 40 + i * 2, 1.6f), alpha(0xcccccc, 160));
        });

        // ------------------------------------------------ populations: what each tier wears on its head
        icon("tier:farmers", () -> {
            shadow(50, 74, 86, 12);
            paint(ell(6, 52, 88, 22), 0xd8b45a);
            paint(path("M 28 60 Q 26 30 50 28 Q 74 30 72 60 Z"), 0xe2c06a);
            paint(path("M 28 52 Q 50 58 72 52 L 72 60 Q 50 66 28 60 Z"), 0x8a4a2a, 1.2f);
            for (int i = 0; i < 6; i++) line(14 + i * 14, 62, 20 + i * 14, 58, .8f, alpha(0x8a6a2a, 140));
        });
        icon("tier:workers", () -> {
            shadow(50, 74, 80, 12);
            paint(path("M 14 58 Q 10 30 46 28 Q 82 28 84 50 Q 84 60 70 62 L 24 64 Q 14 64 14 58 Z"), 0x5a5c66);
            paint(path("M 50 60 Q 74 56 92 66 Q 92 72 80 72 Q 62 70 46 68 Z"), 0x44464e);
            flat(circ(46, 30, 3), 0x3a3c44);
            stroke(path("M 22 48 Q 46 40 78 46"), 1, alpha(0x2a2c32, 160));
        });
        icon("tier:artisans", () -> {
            shadow(50, 74, 80, 12);
            paint(path("M 12 54 Q 8 30 46 26 Q 86 24 88 46 Q 90 58 74 60 L 28 62 Q 14 62 12 54 Z"), 0x8a2a3a);
            paint(path("M 24 56 Q 50 66 78 56 L 78 64 Q 50 72 24 64 Z"), 0x5a1a24, 1.2f);
            line(50, 26, 54, 18, 2.2f, 0x5a1a24);
        });
        icon("tier:engineers", () -> {
            shadow(50, 78, 86, 12);
            paint(ell(8, 58, 84, 18), 0x4a3428);
            paint(path("M 24 66 Q 22 26 50 24 Q 78 26 76 66 Z"), 0x5a4030);
            paint(path("M 24 56 Q 50 62 76 56 L 76 64 Q 50 70 24 64 Z"), 0x2a1c14, 1.2f);
            gloss(ell(24, 24, 52, 40), ell(30, 28, 16, 20), 60);
        });
        icon("tier:investors", () -> {
            shadow(50, 84, 86, 12);
            paint(ell(8, 68, 84, 14), 0x1e1e24);
            paint(path("M 28 74 L 30 14 Q 50 8 70 14 L 72 74 Q 50 80 28 74 Z"), 0x2a2a32);
            paint(path("M 29 58 Q 50 64 71 58 L 72 68 Q 50 74 28 68 Z"), 0x8a1a24, 1.2f);
            paint(ell(30, 9, 40, 10), 0x34343e, 1.2f);
            gloss(rect(30, 14, 40, 60), rect(34, 14, 6, 44), 50);
        });
        icon("tier:laborers", () -> {
            shadow(50, 76, 90, 12);
            paint(ell(4, 54, 92, 22), 0xc8a050);
            paint(path("M 30 62 L 50 18 L 70 62 Z"), 0xd6b05e);
            paint(path("M 33 56 Q 50 62 67 56 L 70 62 Q 50 68 30 62 Z"), 0x2e7a7a, 1.2f);
            for (int i = 0; i < 5; i++) line(36 + i * 7, 54, 48 + i * 1, 26, .8f, alpha(0x8a6a2a, 120));
        });
        icon("tier:overseers", () -> {
            shadow(50, 76, 90, 12);
            paint(path("M 4 62 Q 10 52 26 56 L 74 56 Q 90 52 96 62 Q 90 72 50 72 Q 10 72 4 62 Z"), 0x7a5030);
            paint(path("M 26 60 Q 24 26 50 24 Q 76 26 74 60 Z"), 0x8a5a36);
            stroke(path("M 50 26 Q 46 40 50 54"), 1.6f, alpha(0x4a2a14, 180));
            paint(path("M 26 52 Q 50 58 74 52 L 74 60 Q 50 66 26 60 Z"), 0x3a2414, 1.2f);
        });

        // ------------------------------------------------ public and special buildings, as emblems
        icon("building:trading_post", () -> {
            shadow(50, 88, 60, 10);
            stroke(path("M 50 18 L 50 80 M 22 56 Q 26 82 50 84 Q 74 82 78 56"), 8, 0x2e3a46);
            stroke(path("M 50 18 L 50 80 M 22 56 Q 26 82 50 84 Q 74 82 78 56"), 4.4f, 0xc9a24a);
            stroke(path("M 34 32 L 66 32"), 8, 0x2e3a46); stroke(path("M 34 32 L 66 32"), 4.4f, 0xc9a24a);
            stroke(circ(50, 13, 6), 7, 0x2e3a46); stroke(circ(50, 13, 6), 3.4f, 0xc9a24a);
            paint(path("M 14 58 L 22 48 L 30 58 Z"), 0xc9a24a); paint(path("M 70 58 L 78 48 L 86 58 Z"), 0xc9a24a);
        });
        icon("building:warehouse", () -> {
            shadow(50, 86, 86, 12);
            crate(8, 56, 36, 28); crate(46, 56, 36, 28); crate(26, 26, 36, 28);
        });
        icon("building:market", () -> {
            shadow(50, 88, 86, 10);
            line(18, 40, 18, 86, 3, 0x6a4a2a); line(82, 40, 82, 86, 3, 0x6a4a2a);
            paint(rect(12, 64, 76, 20), 0x9a6a3a);
            for (int i = 0; i < 4; i++) ball(circ(24 + i * 8, 60, 5), i % 2 == 0 ? 0xc84a30 : 0xe0a030);
            box(54, 56, 22, 8, 4, 3, 0xd8c08a);
            Shape awning = path("M 8 40 L 18 14 L 82 14 L 92 40 Q 86 46 80 40 Q 74 46 68 40 Q 62 46 56 40 Q 50 46 44 40 Q 38 46 32 40 Q 26 46 20 40 Q 14 46 8 40 Z");
            paint(awning, 0xf0e6cc);
            Shape old = g.getClip(); g.clip(awning);
            for (int i = 0; i < 7; i += 2) flat(poly(18 + i * 9.1f, 14, 27.1f + i * 9.1f, 14, 20 + i * 12, 48, 8 + i * 12, 48), 0xb8342a);
            g.setClip(old); rim(awning, 0xb8342a);
        });
        icon("building:shipyard", () -> {
            shadow(50, 86, 86, 10);
            Shape hull = path("M 6 50 L 94 50 Q 86 80 64 82 L 32 82 Q 14 80 6 50 Z");
            paint(hull, 0x7a4e2c);
            for (int i = 0; i < 3; i++) line(10 + i * 2, 58 + i * 8, 90 - i * 3, 58 + i * 8, 1, alpha(0x3a2414, 160));
            line(48, 8, 48, 50, 3, 0x5a3a20);
            Shape sail = path("M 50 12 Q 76 26 80 46 L 50 46 Z"); paint(sail, 0xf0e6cc);
            Shape jib = path("M 46 14 L 46 46 L 20 46 Z"); paint(jib, 0xe8dcc0);
            flat(path("M 48 8 L 62 11 L 48 14 Z"), 0xb8342a);
        });
        icon("building:harbor_defense", () -> {
            shadow(50, 84, 86, 12);
            Shape barrel = rotated(path("M 30 40 L 88 34 Q 94 34 94 40 L 94 46 Q 94 52 88 52 L 30 56 Z"), -10, 50, 50);
            cyl(barrel, 0x3a3e46);
            paint(rotated(rect(84, 33, 6, 22), -10, 50, 50), 0x2a2e34, 1.2f);
            paint(rect(14, 52, 46, 18), 0x7a4e2c);
            wheel(24, 72, 13); wheel(52, 72, 13);
        });
        icon("building:trade_union", () -> {
            medal(0x7a2a24);
            gear(50, 50, 26, 0xc9a24a);
            Shape h1 = rotated(rrect(46, 22, 8, 50, 2), 40, 50, 50), h2 = rotated(rrect(46, 22, 8, 50, 2), -40, 50, 50);
            paint(h1, 0x8a5a32, 1.2f); paint(h2, 0x8a5a32, 1.2f);
            paint(rotated(rrect(36, 18, 28, 12, 3), 40, 50, 50), 0x5a606a, 1.2f);
            paint(rotated(rrect(36, 18, 28, 12, 3), -40, 50, 50), 0x5a606a, 1.2f);
        });
        icon("building:power_plant", () -> {
            medal(0x23303c);
            Shape bolt = poly(56, 10, 26, 54, 46, 54, 38, 90, 74, 42, 52, 42, 64, 10);
            g.setPaint(new LinearGradientPaint(30, 10, 70, 90, new float[]{0, 1}, new Color[]{c(0xfff2a0), c(0xf0a020)})); g.fill(bolt);
            stroke(bolt, 1.8f, 0x8a5a10);
        });
        icon("building:pub", () -> {
            shadow(50, 88, 86, 10);
            tankard(6, 30, -12); tankard(44, 30, 12);
        });
        icon("building:school", () -> {
            shadow(50, 88, 86, 10);
            box(10, 64, 64, 12, 14, 8, 0x2e5a8a); box(16, 52, 58, 12, 12, 6, 0x8a2a2a); box(12, 40, 60, 12, 14, 7, 0x3a6a3a);
            flat(rect(10, 66, 64, 2), 0xf0e6cc); flat(rect(16, 54, 58, 2), 0xf0e6cc); flat(rect(12, 42, 60, 2), 0xf0e6cc);
            ball(circ(56, 24, 12), 0xc83a2a);
            line(56, 13, 58, 6, 2, 0x5a3a20);
            leaf(path("M 58 10 Q 70 2 74 10 Q 66 16 58 10 Z"), 0x4f8a3a);
        });
        icon("building:church", () -> {
            shadow(50, 90, 86, 10);
            box(30, 50, 56, 36, 6, 5, 0xc8c2b4);
            paint(poly(28, 50, 58, 28, 88, 50), 0x3e4a5a);
            box(14, 34, 22, 52, 4, 4, 0xd8d2c4);
            paint(poly(12, 34, 25, 4, 38, 34), 0x3e4a5a);
            line(25, 4, 25, -4, 2, 0xc9a24a); line(21, 0, 29, 0, 2, 0xc9a24a);
            flat(path("M 21 50 L 21 42 Q 25 36 29 42 L 29 50 Z"), 0x2a2e36);
            flat(path("M 52 86 L 52 70 Q 58 62 64 70 L 64 86 Z"), 0x4a2e1a);
            flat(circ(58, 58, 4), 0x6a8ab0);
        });
        icon("building:variety_theatre", () -> {
            shadow(50, 88, 86, 10);
            mask(8, 18, 0xf2e6c8, true, -12);
            mask(44, 30, 0xc8a24a, false, 12);
        });
        icon("building:university", () -> {
            shadow(50, 80, 86, 10);
            paint(path("M 28 46 L 28 66 Q 50 78 72 66 L 72 46 Z"), 0x2a2a34);
            paint(poly(4, 40, 50, 20, 96, 40, 50, 60), 0x34343e);
            gloss(poly(4, 40, 50, 20, 96, 40, 50, 60), poly(4, 40, 50, 20, 60, 24, 14, 44), 50);
            flat(circ(50, 40, 3), 0xc9a24a);
            stroke(path("M 50 40 Q 74 44 80 50 L 80 70"), 1.6f, 0xc9a24a);
            paint(path("M 76 68 L 84 68 L 86 82 L 74 82 Z"), 0xe8b83a, 1);
        });
        icon("building:bank", () -> {
            shadow(50, 90, 90, 10);
            paint(poly(8, 34, 50, 10, 92, 34), 0xe4dcc8);
            flat(circ(50, 26, 5), 0xc9a24a);
            paint(rect(10, 34, 80, 7), 0xd8d0bc, 1.2f);
            for (int i = 0; i < 5; i++) cyl(rect(15 + i * 16, 41, 8, 36), 0xece6d6);
            paint(rect(8, 77, 84, 6), 0xd8d0bc, 1.2f); paint(rect(4, 83, 92, 6), 0xc8c0ac, 1.2f);
        });
        icon("building:club", () -> {
            shadow(50, 88, 70, 10);
            Shape glass = path("M 26 30 Q 20 64 50 68 Q 80 64 74 30 Z");
            flat(glass, alpha(0xe6f2f4, 140));
            Shape drink = path("M 23 50 L 77 50 Q 74 66 50 68 Q 26 66 23 50 Z");
            Shape old = g.getClip(); g.clip(glass); cyl(drink, 0xa85a1a); g.setClip(old);
            stroke(glass, 1.8f, 0x5a6a70);
            line(50, 68, 50, 84, 3, 0xd6e2e4); paint(ell(32, 82, 36, 8), 0xd6e2e4, 1.2f);
            gloss(glass, rect(28, 32, 6, 30), 140);
            paint(rotated(rrect(56, 18, 40, 8, 8), -20, 70, 22), 0x6e4426, 1.2f);
            flat(circ(90, 12, 2), 0xff8a3a);
        });
        icon("building:chapel", () -> {
            shadow(50, 90, 80, 10);
            Shape tower = path("M 22 88 L 22 30 Q 22 10 50 8 Q 78 10 78 30 L 78 88 Z");
            paint(tower, 0xf0e8d6);
            flat(path("M 34 56 L 34 36 Q 50 22 66 36 L 66 56 Z"), 0x4a3a2a);
            paint(path("M 40 38 Q 40 30 50 30 Q 60 30 60 38 L 62 50 L 38 50 Z"), 0xc9a24a);
            flat(circ(50, 52, 3), 0x8a6a2a);
            line(50, 8, 50, -2, 2.4f, 0x6a4a2a); line(45, 2, 55, 2, 2.4f, 0x6a4a2a);
            flat(path("M 42 88 L 42 72 Q 50 64 58 72 L 58 88 Z"), 0x6a4a2a);
        });

        // ------------------------------------------------ menu categories and tools
        icon("cat:infrastructure", () -> { shadow(50, 86, 70, 10); crate(16, 30, 60, 50); });
        icon("cat:housing", () -> {
            shadow(50, 88, 80, 10);
            box(18, 46, 52, 40, 12, 9, 0xe8dcc0);
            for (int i = 0; i < 4; i++) line(18, 54 + i * 8, 70, 54 + i * 8, .7f, alpha(0x8a7a5a, 90));
            paint(poly(12, 48, 44, 18, 76, 48), 0xa84a32);
            paint(poly(44, 18, 76, 48, 88, 39, 56, 9), 0x8a3a26);
            paint(rect(56, 14, 7, 14), 0x8a5a3a, 1.2f);
            flat(rect(26, 56, 12, 12), 0x5a8ab0); line(32, 56, 32, 68, 1.2f, 0xe8dcc0);
            flat(rect(46, 62, 12, 24), 0x6a4226);
        });
        icon("cat:services", () -> {
            shadow(50, 88, 60, 10);
            Shape bell = path("M 50 12 Q 70 12 72 40 Q 74 64 86 72 L 14 72 Q 26 64 28 40 Q 30 12 50 12 Z");
            paint(bell, 0xc9a24a);
            gloss(bell, ell(30, 14, 16, 40), 90);
            paint(rrect(10, 70, 80, 8, 6), 0xb08a3a, 1.2f);
            ball(circ(50, 82, 6), 0x8a6a2a);
            paint(rrect(46, 4, 8, 10, 4), 0x8a6a2a, 1.2f);
        });
        for (String tier : List.of("farmers", "workers", "artisans", "engineers", "investors"))
            ICONS.put("cat:" + tier, ICONS.get("tier:" + tier));
        icon("cat:new_world", () -> {
            shadow(50, 90, 60, 8);
            stroke(path("M 52 88 Q 48 60 54 30"), 7, 0x7a5a32);
            for (int i = 0; i < 5; i++) line(48, 86 - i * 12, 58, 84 - i * 12, 1.2f, 0x4a3420);
            leaf(path("M 54 30 Q 30 14 6 34 Q 30 26 54 34 Z"), 0x3e8a4a);
            leaf(path("M 54 30 Q 78 12 96 32 Q 76 26 54 34 Z"), 0x3e8a4a);
            leaf(path("M 54 30 Q 40 40 24 64 Q 44 46 56 34 Z"), 0x4f9a4a);
            leaf(path("M 54 30 Q 70 38 82 62 Q 64 44 52 34 Z"), 0x4f9a4a);
            leaf(path("M 54 30 Q 52 10 66 4 Q 60 18 56 32 Z"), 0x5aa85a);
            ball(circ(50, 36, 5), 0x6a4a2a); ball(circ(59, 37, 5), 0x6a4a2a);
        });
        icon("road", () -> {
            shadow(50, 86, 86, 8);
            Shape r = poly(30, 10, 70, 10, 92, 88, 8, 88); paint(r, 0xb89a6a);
            Shape old = g.getClip(); g.clip(r);
            for (int row = 0; row < 8; row++) for (int k = -2; k < 7; k++) {
                float y = 12 + row * 10, x = 6 + k * 16 + (row % 2) * 8;
                stroke(rrect(x, y, 15, 9, 3), 1, alpha(0x6a5232, 180));
            }
            g.setClip(old);
        });
        icon("road_remove", () -> {
            ICONS.get("road").run();
            line(14, 14, 86, 86, 12, 0x5a1a14); line(86, 14, 14, 86, 12, 0x5a1a14);
            line(14, 14, 86, 86, 7, 0xd8443a); line(86, 14, 14, 86, 7, 0xd8443a);
        });
        icon("map", () -> {
            shadow(50, 86, 86, 10);
            Shape sheet = poly(8, 20, 36, 12, 64, 20, 92, 12, 92, 80, 64, 88, 36, 80, 8, 88);
            paint(sheet, 0xe8d8b0);
            flat(poly(36, 12, 64, 20, 64, 88, 36, 80), alpha(0x8a6a3a, 40));
            flat(path("M 18 40 Q 30 28 44 36 Q 52 48 40 58 Q 24 62 18 40 Z"), 0x8ab06a);
            flat(path("M 58 52 Q 72 44 82 54 Q 84 68 70 70 Q 56 66 58 52 Z"), 0x8ab06a);
            stroke(path("M 40 50 Q 56 44 66 60"), 1.6f, 0xb8342a);
            line(66, 56, 72, 64, 2, 0xb8342a); line(72, 56, 66, 64, 2, 0xb8342a);
        });
        icon("diplomacy", () -> {
            shadow(50, 90, 70, 8);
            line(22, 90, 58, 12, 3.4f, 0x5a3a20); line(78, 90, 42, 12, 3.4f, 0x5a3a20);
            paint(path("M 57 14 Q 70 8 82 16 Q 90 22 98 18 L 92 42 Q 84 46 76 40 Q 64 34 50 40 Z"), 0x2e5a9a);
            paint(path("M 43 14 Q 30 8 18 16 Q 10 22 2 18 L 8 42 Q 16 46 24 40 Q 36 34 50 40 Z"), 0xb8342a);
            flat(circ(74, 28, 5), 0xe8cf8a); flat(circ(26, 28, 5), 0xe8cf8a);
            ball(circ(58, 12, 3.4f), 0xc9a24a); ball(circ(42, 12, 3.4f), 0xc9a24a);
        });
        icon("colony", () -> {
            shadow(50, 88, 80, 10);
            paint(rrect(14, 14, 66, 74, 6), 0x6a2a24);
            paint(rect(20, 18, 62, 66), 0xf2e8cc, 1.2f);
            for (int i = 0; i < 6; i++) line(28, 30 + i * 8, 72 - (i % 3) * 6, 30 + i * 8, 1.4f, alpha(0x5a4a3a, 160));
            line(50, 30, 50, 72, 1, alpha(0x5a4a3a, 90));
            paint(rect(14, 14, 8, 74), 0x4a1a14, 1.2f);
            flat(path("M 64 18 L 72 18 L 72 34 L 68 30 L 64 34 Z"), 0xb8342a);
        });
        icon("visit", () -> {
            shadow(50, 90, 60, 8);
            Shape body = path("M 22 92 Q 22 56 50 56 Q 78 56 78 92 Z"); paint(body, 0x3e5a7a);
            ball(circ(50, 38, 16), 0xe8c4a0);
            paint(path("M 30 30 Q 32 14 50 14 Q 68 14 70 30 Q 50 26 30 30 Z"), 0x5a3a20);
        });
        icon("quest", () -> {
            shadow(50, 86, 80, 10);
            paint(rect(20, 18, 60, 64), 0xecdcb4);
            paint(rrect(14, 10, 72, 12, 12), 0xd8c49a); paint(rrect(14, 78, 72, 12, 12), 0xd8c49a);
            for (int i = 0; i < 5; i++) line(30, 32 + i * 9, 70 - (i % 2) * 8, 32 + i * 9, 1.4f, alpha(0x5a4a3a, 150));
            paint(circ(66, 72, 8), 0xb8342a, 1.2f);
        });
        icon("ship", () -> ICONS.get("building:shipyard").run());

        // ------------------------------------------------ campaign speakers: portraits
        icon("speaker:agnes", () -> portrait(0x4a6a8a, 0xb8763a, 0x2e3e52, "bonnet"));
        icon("speaker:linnell", () -> portrait(0x5a3a5a, 0x2a1a14, 0x1e1e24, "tophat"));
        icon("speaker:rook", () -> portrait(0x6a2a24, 0x3a2a1a, 0x2a2a2a, "tricorne"));
        icon("speaker:ashby", () -> portrait(0x3a5a3a, 0xc8c8c0, 0x4a3428, "bald"));
        icon("speaker:julien", () -> portrait(0x7a6a4a, 0x8a5a2a, 0x5a5c66, "cap"));
    }

    // ---------------------------------------------------------------- reusable motifs

    static void coinSide(float x, float y, float w) {
        float e = w * .34f;
        Area side = new Area(rect(x, y + e / 2, w, 6)); side.add(new Area(ell(x, y + 6, w, e)));
        cyl(side, 0xc9962a); paint(ell(x, y, w, e), 0xf0c84a, 1.2f);
        stroke(ell(x + 6, y + 2, w - 12, e - 4), 1, alpha(0xa87a1a, 200));
    }
    static void coinFace(float cx, float cy, float r) {
        ball(circ(cx, cy, r), 0xe8b83a);
        stroke(circ(cx, cy, r - 4), 1.4f, 0xa87a1a);
        flat(path("M " + (cx - 6) + " " + (cy + 6) + " L " + cx + " " + (cy - 8) + " L " + (cx + 6) + " " + (cy + 6) + " Z"), 0xb8861a);
    }
    static void log(float x, float y, float len, float d) {
        Shape bark = rect(x + d / 2, y, len - d / 2, d);
        g.setPaint(new LinearGradientPaint(0, y, 0, y + d, new float[]{0, .35f, 1}, new Color[]{c(0x9a6e46), c(0x7a5232), c(0x4a3020)})); g.fill(bark); rim(bark, 0x7a5232);
        for (int i = 0; i < 3; i++) line(x + d + i * 14, y + 4 + i % 2 * 6, x + d + 10 + i * 14, y + 5 + i % 2 * 6, .9f, alpha(0x3a2414, 160));
        Shape end = ell(x, y, d * .8f, d); paint(end, 0xdcb47e, 1.4f);
        stroke(ell(x + d * .2f, y + d * .25f, d * .4f, d * .5f), .9f, alpha(0x9a6e46, 200));
    }
    static void skein(int rgb) {
        shadow(50, 84, 80, 12);
        Shape ball = ell(14, 20, 72, 64); ball(ball, rgb);
        Shape old = g.getClip(); g.clip(ball);
        for (int i = -3; i < 6; i++) stroke(path("M " + (i * 14) + " 90 Q " + (40 + i * 6) + " 40 " + (60 + i * 14) + " 10"), 1.6f, dark(rgb, .25f));
        for (int i = 0; i < 4; i++) stroke(path("M 10 " + (34 + i * 12) + " Q 50 " + (24 + i * 16) + " 92 " + (44 + i * 10)), 1.2f, alpha(0xffffff, 90));
        g.setClip(old); rim(ball, rgb);
        stroke(path("M 80 64 Q 92 76 84 88"), 2.4f, dark(rgb, .2f));
    }
    static void potato(float x, float y, float w, float h, float turn) {
        Shape p = rotated(ell(x, y, w, h), turn, x + w / 2, y + h / 2); ball(p, 0xb98d55);
        for (int i = 0; i < 3; i++) flat(circ(x + w * (.3f + i * .22f), y + h * (.35f + (i % 2) * .3f), 1.4f), 0x6a4a28);
    }
    static void ear(float x, float y, float turn) {
        Shape e = rotated(ell(x - 4, y - 14, 8, 22), turn, x, y); paint(e, 0xe0b44a, 1.2f);
        for (int i = 0; i < 3; i++) line(x, y - 10 + i * 6, x + 3, y - 12 + i * 6, .8f, 0x8a6a1a);
        line(x, y - 14, x + turn * .1f, y - 24, .8f, 0xc49a3a);
    }
    static void sausage(Shape s) { paint(s, 0xa8483a); gloss(s, moved(s, -2, -3), 40); }
    static void leaf(Shape s, int rgb) {
        paint(s, rgb, 1.4f);
        Rectangle2D b = s.getBounds2D();
        line((float) b.getMinX() + 4, (float) b.getMaxY() - 4, (float) b.getMaxX() - 4, (float) b.getMinY() + 4, 1, alpha(0xffffff, 70));
    }
    static void hopCone(float x, float y, float w, float h) {
        Shape cone = path("M " + (x + w / 2) + " " + y + " Q " + (x + w + 2) + " " + (y + h * .4f) + " " + (x + w / 2) + " " + (y + h) + " Q " + (x - 2) + " " + (y + h * .4f) + " " + (x + w / 2) + " " + y + " Z");
        paint(cone, 0x9cc85a);
        for (int r = 1; r < 5; r++) for (int k = 0; k < 2; k++) {
            float cx = x + w * (.32f + k * .36f) - (r % 2) * 4, cy = y + h * r / 5f;
            stroke(path("M " + (cx - 6) + " " + (cy - 3) + " Q " + cx + " " + (cy + 4) + " " + (cx + 6) + " " + (cy - 3)), 1.1f, 0x5a7a2a);
        }
    }
    static void lump(Shape s, int rgb) { paint(s, rgb); gloss(s, moved(s, -4, -5), 30); stroke(s, 1.6f, 0x111114); }
    static void ingot(float x, float y, int rgb) {
        Shape top = poly(x + 6, y, x + 34, y, x + 30, y + 6, x + 10, y + 6);
        box(x, y + 6, 40, 12, 0, 0, rgb);
        Shape body = poly(x, y + 18, x + 40, y + 18, x + 34, y, x + 6, y);
        paint(body, rgb); flat(top, light(rgb, .35f));
        gloss(body, rect(x + 8, y + 2, 20, 3), 120);
    }
    static void beam(float x, float y, int rgb) {
        Shape body = poly(x, y + 10, x + 70, y - 6, x + 76, y + 2, x + 6, y + 18);
        Shape flangeTop = poly(x, y + 4, x + 70, y - 12, x + 76, y - 8, x + 6, y + 8);
        Shape flangeBottom = poly(x, y + 20, x + 70, y + 4, x + 76, y + 8, x + 6, y + 24);
        paint(flangeBottom, dark(rgb, .1f).getRGB() & 0xffffff); paint(body, rgb); paint(flangeTop, light(rgb, .15f).getRGB() & 0xffffff);
        paint(poly(x - 4, y + 2, x + 10, y + 2, x + 10, y + 6, x + 5, y + 6, x + 5, y + 22, x + 10, y + 22, x + 10, y + 26, x - 4, y + 26, x - 4, y + 22, x + 1, y + 22, x + 1, y + 6, x - 4, y + 6), light(rgb, .25f).getRGB() & 0xffffff, 1.2f);
    }
    static void nugget(Shape s) { paint(s, 0xe2b232); gloss(s, moved(s, -5, -6), 80); }
    static void bean(float x, float y, float turn) {
        Shape b = rotated(ell(x, y, 40, 28), turn, x + 20, y + 14); ball(b, 0x5a3418);
        stroke(rotated(path("M " + (x + 4) + " " + (y + 14) + " Q " + (x + 20) + " " + (y + 6) + " " + (x + 36) + " " + (y + 14)), turn, x + 20, y + 14), 2, 0x2a1408);
    }
    static void wheel(float cx, float cy, float r) {
        paint(circ(cx, cy, r), 0x6a4226);
        flat(circ(cx, cy, r - 4), 0x3a2414);
        for (int i = 0; i < 6; i++) { double a = Math.toRadians(i * 60); line(cx, cy, cx + (float) Math.cos(a) * (r - 3), cy + (float) Math.sin(a) * (r - 3), 2, 0x8a5a32); }
        paint(circ(cx, cy, 3), 0x8a8e96, 1);
    }
    static void gear(float cx, float cy, float r, int rgb) {
        Area a = new Area(circ(cx, cy, r));
        for (int i = 0; i < 10; i++) a.add(new Area(rotated(rect(cx - 4, cy - r - 6, 8, 10), i * 36, cx, cy)));
        a.subtract(new Area(circ(cx, cy, r * .45f)));
        paint(a, rgb);
    }
    static void crate(float x, float y, float w, float h) {
        box(x, y, w, h, w * .3f, h * .25f, 0xb07a44);
        stroke(rect(x + 3, y + 3, w - 6, h - 6), 1.6f, 0x6a4422);
        line(x + 3, y + 3, x + w - 3, y + h - 3, 2.4f, 0x8a5a30); line(x + 3, y + h - 3, x + w - 3, y + 3, 2.4f, 0x8a5a30);
    }
    static void tankard(float x, float y, float turn) {
        Shape old = g.getClip();
        AffineTransform t = g.getTransform(); g.rotate(Math.toRadians(turn), x + 25, y + 30);
        stroke(path("M " + (x + 40) + " " + (y + 12) + " Q " + (x + 54) + " " + (y + 12) + " " + (x + 54) + " " + (y + 26) + " Q " + (x + 54) + " " + (y + 40) + " " + (x + 40) + " " + (y + 40)), 4.4f, 0x8a6a3a);
        cyl(rect(x, y, 42, 50), 0xd99a2a);
        paint(rect(x - 2, y + 44, 46, 6), 0x8a6a3a, 1.2f);
        paint(path("M " + (x - 4) + " " + (y + 4) + " Q " + (x - 4) + " " + (y - 10) + " " + (x + 12) + " " + (y - 8) + " Q " + (x + 22) + " " + (y - 14) + " " + (x + 32) + " " + (y - 8) + " Q " + (x + 48) + " " + (y - 10) + " " + (x + 46) + " " + (y + 4) + " Z"), 0xf6f0e0, 1.2f);
        g.setTransform(t); g.setClip(old);
    }
    static void mask(float x, float y, int rgb, boolean smile, float turn) {
        AffineTransform t = g.getTransform(); g.rotate(Math.toRadians(turn), x + 24, y + 28);
        Shape face = path("M " + x + " " + (y + 4) + " Q " + (x + 24) + " " + (y - 4) + " " + (x + 48) + " " + (y + 4) + " Q " + (x + 50) + " " + (y + 44) + " " + (x + 24) + " " + (y + 58) + " Q " + (x - 2) + " " + (y + 44) + " " + x + " " + (y + 4) + " Z");
        paint(face, rgb);
        flat(ell(x + 9, y + 16, 11, 8), 0x1b1f24); flat(ell(x + 28, y + 16, 11, 8), 0x1b1f24);
        if (smile) flat(path("M " + (x + 12) + " " + (y + 34) + " Q " + (x + 24) + " " + (y + 48) + " " + (x + 36) + " " + (y + 34) + " Q " + (x + 24) + " " + (y + 40) + " " + (x + 12) + " " + (y + 34) + " Z"), 0x5a1a14);
        else flat(path("M " + (x + 12) + " " + (y + 44) + " Q " + (x + 24) + " " + (y + 32) + " " + (x + 36) + " " + (y + 44) + " Q " + (x + 24) + " " + (y + 38) + " " + (x + 12) + " " + (y + 44) + " Z"), 0x5a1a14);
        g.setTransform(t);
    }
    static void portrait(int coat, int hair, int hat, String style) {
        medal(0x2e3e4e);
        Shape old = g.getClip(); g.clip(circ(50, 50, 42));
        paint(path("M 16 100 Q 16 66 50 64 Q 84 66 84 100 Z"), coat);
        paint(path("M 42 64 L 50 80 L 58 64 Z"), 0xf2ead8, 1);
        g.setClip(old);
        if (!style.equals("bald")) paint(path("M 30 48 Q 28 22 50 20 Q 72 22 70 48 Q 72 60 64 62 L 36 62 Q 28 60 30 48 Z"), hair);
        ball(ell(34, 26, 32, 38), 0xe8c4a0);
        flat(circ(43, 44, 1.8f), 0x1b1f24); flat(circ(57, 44, 1.8f), 0x1b1f24);
        switch (style) {
            case "tophat" -> { paint(ell(26, 24, 48, 8), hat); paint(rect(36, 4, 28, 22), hat); paint(rect(36, 18, 28, 4), 0x8a1a24, 0); }
            case "tricorne" -> paint(path("M 22 30 Q 50 8 78 30 Q 70 18 50 18 Q 30 18 22 30 Z M 22 30 Q 50 20 78 30 Q 50 26 22 30 Z"), hat);
            case "bonnet" -> paint(path("M 28 46 Q 22 14 50 12 Q 78 14 72 46 Q 70 28 50 26 Q 30 28 28 46 Z"), hat);
            case "cap" -> { paint(path("M 32 30 Q 34 16 52 16 Q 70 18 70 30 Z"), hat); paint(path("M 48 28 Q 66 26 80 32 L 50 32 Z"), hat); }
            case "bald" -> { paint(path("M 33 38 Q 30 30 36 28 L 36 40 Z M 67 38 Q 70 30 64 28 L 64 40 Z"), hair); stroke(path("M 40 56 Q 50 62 60 56"), 3, hair); }
            default -> { }
        }
    }
}
