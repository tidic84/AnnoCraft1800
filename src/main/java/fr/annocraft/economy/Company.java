package fr.annocraft.economy;

import net.minecraft.nbt.CompoundTag;
import java.util.*;

/**
 * A trading company, as in Anno: its name, its colour on the map and the sails, and its flag, painted on a
 * 24 × 16 grid from a heraldic palette of 16 colours. Presets give a quick start; every cell can then be painted.
 */
public record Company(String id, String name, int color, byte[] flag, boolean configured) {
    public static final int FLAG_W = 24, FLAG_H = 16, NAME_LENGTH = 24;
    /** The flag's palette: tinctures and a few shades, as on Anno's flag editor. */
    public static final int[] PALETTE = {
            0xf4f1e6, 0x1d1d1f, 0xb3262e, 0x1f4e9a, 0x2f7d3b, 0xe7b62c, 0x6b2f8f, 0xe07a24,
            0x6fb4e0, 0x7a4a2a, 0x8c8c8c, 0xd86f9c, 0x0f6f6a, 0x9fd04a, 0x5a1520, 0xc9a35a};
    /** Main colours a company can take on the map, the HUD and its sails. */
    public static final int[] COLORS = {
            0x2f6fd0, 0xc23b3b, 0x2f9a4a, 0xe0b028, 0x8a3fb0, 0xe07a24, 0x1fa3a0, 0xd9d4c4, 0x3a3a40, 0xd8609a, 0x7a4a2a, 0x7fc8f0};
    /** Names of the flag presets, in order. */
    public static final String[] PRESETS = {"tricolor", "bands", "cross", "saltire", "quarters", "canton", "chevron", "border", "stripes", "disc"};

    public Company {
        name = clean(name);
        if (flag == null || flag.length != FLAG_W * FLAG_H) flag = preset(0, 2, 0, 3);
        for (int i = 0; i < flag.length; i++) if (flag[i] < 0 || flag[i] >= PALETTE.length) flag[i] = 0;
        color &= 0xffffff;
    }
    public static String clean(String name) {
        String s = name == null ? "" : name.replaceAll("[\\p{Cntrl}§]", "").strip();
        return s.length() > NAME_LENGTH ? s.substring(0, NAME_LENGTH) : s;
    }
    public int cell(int x, int y) { return flag[y * FLAG_W + x]; }
    public int rgb(int x, int y) { return PALETTE[cell(x, y)]; }
    public Company with(String name, int color, byte[] flag) { return new Company(id, name, color, flag, true); }

    /** A preset pattern in three palette colours: field {@code a}, charges {@code b} and {@code c}. */
    public static byte[] preset(int pattern, int a, int b, int c) {
        byte[] f = new byte[FLAG_W * FLAG_H];
        for (int y = 0; y < FLAG_H; y++) for (int x = 0; x < FLAG_W; x++) {
            int v = switch (PRESETS[Math.floorMod(pattern, PRESETS.length)]) {
                case "tricolor" -> x < 8 ? a : x < 16 ? b : c;
                case "bands" -> y < 5 ? a : y < 11 ? b : c;
                case "cross" -> (Math.abs(x - 8) <= 1 || Math.abs(y - 7.5) <= 1.5) ? b : a;
                case "saltire" -> Math.abs(x * 16 / 24.0 - y) <= 1.6 || Math.abs(x * 16 / 24.0 - (15 - y)) <= 1.6 ? b : a;
                case "quarters" -> (x < 12) == (y < 8) ? a : b;
                case "canton" -> x < 10 && y < 8 ? (Math.hypot(x - 4.5, y - 3.5) < 2.2 ? c : b) : (y / 2 % 2 == 0 ? a : c);
                case "chevron" -> x < 10 - Math.abs(y - 7.5) * 1.2 ? b : a;
                case "border" -> x < 2 || y < 2 || x >= 22 || y >= 14 ? b : Math.hypot(x - 11.5, y - 7.5) < 3.5 ? c : a;
                case "stripes" -> y / 2 % 2 == 0 ? a : b;
                default -> Math.hypot(x - 11.5, y - 7.5) < 5 ? b : a;
            };
            f[y * FLAG_W + x] = (byte) v;
        }
        return f;
    }
    /** A random company look, so nobody starts with the same flag. */
    public static Company random(String id, String name, Random random) {
        int a = random.nextInt(PALETTE.length), b, c;
        do { b = random.nextInt(PALETTE.length); } while (b == a);
        do { c = random.nextInt(PALETTE.length); } while (c == a || c == b);
        return new Company(id, name, COLORS[random.nextInt(COLORS.length)], preset(random.nextInt(PRESETS.length), a, b, c), false);
    }

    public CompoundTag save() {
        CompoundTag t = new CompoundTag();
        t.putString("id", id); t.putString("name", name); t.putInt("color", color); t.putByteArray("flag", flag); t.putBoolean("configured", configured);
        return t;
    }
    public static Company load(CompoundTag t) {
        return new Company(t.getString("id"), t.getString("name"), t.getInt("color"), t.getByteArray("flag"), t.getBoolean("configured"));
    }
    @Override public boolean equals(Object o) {
        return o instanceof Company c && id.equals(c.id) && name.equals(c.name) && color == c.color && Arrays.equals(flag, c.flag) && configured == c.configured;
    }
    @Override public int hashCode() { return Objects.hash(id, name, color, Arrays.hashCode(flag), configured); }

    /** A player of the archipelago: name, chosen portrait, and the company they play for. */
    public record Member(UUID player, String name, String avatar, String company) {
        /** Portraits a player can pick; "skin" shows the player's own Minecraft skin. */
        public static final List<String> AVATARS = List.of("captain", "lady", "merchant", "engineer", "admiral", "explorer", "inventor", "baroness", "skin");
        public Member { if (!AVATARS.contains(avatar)) avatar = AVATARS.get(0); }
        public CompoundTag save() {
            CompoundTag t = new CompoundTag(); t.putUUID("player", player); t.putString("name", name); t.putString("avatar", avatar); t.putString("company", company);
            return t;
        }
        public static Member load(CompoundTag t) { return new Member(t.getUUID("player"), t.getString("name"), t.getString("avatar"), t.getString("company")); }
    }
}
