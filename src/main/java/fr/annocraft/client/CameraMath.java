package fr.annocraft.client;

/** Adapted from SoLegendary/Reign of the Nether (GPL-3.0), see NOTICE.md. */
public final class CameraMath {
    public static final float MIN_ZOOM = 10, MAX_ZOOM = 90, DEFAULT_ZOOM = 30;
    private CameraMath() { }
    public static double[] rotateCoords(double x, double z, double degrees) {
        double radians = Math.toRadians(degrees);
        return new double[] {x * Math.cos(radians) - z * Math.sin(radians), z * Math.cos(radians) + x * Math.sin(radians)};
    }
    public static float zoom(float value) { return Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, value)); }
}
