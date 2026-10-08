package fr.annocraft.client;

import java.util.*;

/** Adapted from SoLegendary/Reign of the Nether (GPL-3.0), see NOTICE.md. Pure: shared by the client and the server. */
public final class CameraMath {
    /** Zoom is the camera's height above the city; beyond the detailed range the distant view (LOD) takes over. */
    public static final float MIN_ZOOM = 8, MAX_ZOOM = 2500, DEFAULT_ZOOM = 32;
    /** How far the player may tilt the camera from its automatic pitch, towards the horizon (negative) or straight down. */
    public static final float MIN_TILT = -35, MAX_TILT = 25;
    /** Vertical field of view of the management camera, in degrees. */
    public static final float FOV = 45;
    /** Ground height the camera looks at. */
    public static final double GROUND = 74;
    /** Camera pitch: closer to the horizon near the city, more top-down when zoomed out, as in Anno. */
    public static float pitch(float zoom) {
        if (zoom <= 150) return 42 + (zoom - MIN_ZOOM) / (150 - MIN_ZOOM) * 30;
        return 72 + Math.min(1, (zoom - 150) / 1350) * 12;
    }
    /** Pitch with the player's tilt, kept between a low view along the coast and straight down. */
    public static float pitch(float zoom, float tilt) { return Math.max(12, Math.min(89, pitch(zoom) + tilt)); }
    public static float tilt(float value) { return Math.max(MIN_TILT, Math.min(MAX_TILT, value)); }
    private CameraMath() { }
    public static double[] rotateCoords(double x, double z, double degrees) {
        double radians = Math.toRadians(degrees);
        return new double[] {x * Math.cos(radians) - z * Math.sin(radians), z * Math.cos(radians) + x * Math.sin(radians)};
    }
    public static float zoom(float value) { return Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, value)); }
    /** Unit view direction for a yaw and zoom. */
    public static double[] forward(float yaw, float zoom) { return forward(yaw, zoom, 0); }
    public static double[] forward(float yaw, float zoom, float tilt) {
        double a = Math.toRadians(yaw), b = Math.toRadians(pitch(zoom, tilt));
        return new double[]{-Math.sin(a) * Math.cos(b), -Math.sin(b), Math.cos(a) * Math.cos(b)};
    }
    /** Eye position of a camera looking at (cx, GROUND, cz). */
    public static double[] eye(double cx, double cz, float yaw, float zoom) { return eye(cx, cz, yaw, zoom, 0); }
    public static double[] eye(double cx, double cz, float yaw, float zoom, float tilt) {
        double[] f = forward(yaw, zoom, tilt); double distance = zoom / Math.sin(Math.toRadians(pitch(zoom, tilt)));
        return new double[]{cx - f[0] * distance, GROUND - f[1] * distance, cz - f[2] * distance};
    }
    public static List<double[]> footprint(double cx, double cz, float yaw, float zoom, double aspect, double reach) { return footprint(cx, cz, yaw, zoom, 0, aspect, reach); }
    /**
     * The ground the camera sees: where the rays through the four screen corners meet the plateau, each clamped to
     * {@code reach} blocks from the point looked at. @return four {x, z} corners, a convex quadrilateral
     */
    public static List<double[]> footprint(double cx, double cz, float yaw, float zoom, float tilt, double aspect, double reach) {
        double[] f = forward(yaw, zoom, tilt), e = eye(cx, cz, yaw, zoom, tilt);
        double[] right = normalize(new double[]{-f[2], 0, f[0]});
        double[] up = normalize(cross(right, f));
        double t = Math.tan(Math.toRadians(FOV / 2));
        List<double[]> corners = new ArrayList<>();
        for (int[] c : new int[][]{{-1, 1}, {1, 1}, {1, -1}, {-1, -1}}) {
            double rx = f[0] + right[0] * c[0] * t * aspect + up[0] * c[1] * t, ry = f[1] + right[1] * c[0] * t * aspect + up[1] * c[1] * t,
                    rz = f[2] + right[2] * c[0] * t * aspect + up[2] * c[1] * t;
            double k = ry < -1e-3 ? (GROUND - e[1]) / ry : reach * 4;
            double px = e[0] + rx * k, pz = e[2] + rz * k, dx = px - cx, dz = pz - cz, d = Math.hypot(dx, dz);
            if (d > reach) { px = cx + dx / d * reach; pz = cz + dz / d * reach; }
            corners.add(new double[]{px, pz});
        }
        return corners;
    }
    /** Whether a point lies in a convex quadrilateral given in order, enlarged by {@code margin} blocks. */
    public static boolean inside(List<double[]> quad, double x, double z, double margin) {
        boolean positive = false, negative = false;
        for (int i = 0; i < quad.size(); i++) {
            double[] a = quad.get(i), b = quad.get((i + 1) % quad.size());
            double ex = b[0] - a[0], ez = b[1] - a[1], length = Math.max(1e-6, Math.hypot(ex, ez));
            double side = (ex * (z - a[1]) - ez * (x - a[0])) / length;
            if (side > margin) positive = true; else if (side < -margin) negative = true;
        }
        return !(positive && negative);
    }
    private static double[] cross(double[] a, double[] b) { return new double[]{a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]}; }
    private static double[] normalize(double[] v) { double l = Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]); return new double[]{v[0] / l, v[1] / l, v[2] / l}; }
}
