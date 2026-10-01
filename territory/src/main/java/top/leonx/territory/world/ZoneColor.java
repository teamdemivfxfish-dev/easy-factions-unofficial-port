package top.leonx.territory.world;

import java.awt.Color;

public final class ZoneColor {

    public static final int WARZONE = 0xAA2A2A;
    public static final int SAFEZONE = 0x70E030;

    private static final float MOVED_SAFEZONE_HUE = 160f / 360f;

    private ZoneColor() {
    }

    private static float[] hsb(int rgb) {
        return Color.RGBtoHSB((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF, null);
    }

    public static boolean isWarzoneShade(int rgb) {
        float[] hsb = hsb(rgb);
        float degrees = hsb[0] * 360f;
        return (degrees <= 14f || degrees >= 346f) && hsb[1] >= 0.55f && hsb[1] <= 0.95f && hsb[2] >= 0.50f && hsb[2] <= 0.82f;
    }

    public static boolean isSafezoneShade(int rgb) {
        float[] hsb = hsb(rgb);
        float degrees = hsb[0] * 360f;
        return degrees >= 70f && degrees <= 135f && hsb[1] >= 0.40f && hsb[2] >= 0.50f;
    }

    public static boolean isReserved(int rgb) {
        return isWarzoneShade(rgb) || isSafezoneShade(rgb);
    }

    public static int safe(int rgb) {
        int plain = rgb & 0xFFFFFF;
        float[] hsb = hsb(plain);
        if (isWarzoneShade(plain)) {
            return Color.HSBtoRGB(hsb[0], hsb[1], 1.0f) & 0xFFFFFF;
        }
        if (isSafezoneShade(plain)) {
            return Color.HSBtoRGB(MOVED_SAFEZONE_HUE, hsb[1], hsb[2]) & 0xFFFFFF;
        }
        return plain;
    }
}
