package com.besome.sketch.projects;

import static mod.hey.studios.util.ProjectFile.getDefaultColor;

import android.graphics.Color;
import android.os.Build;

import java.util.Random;

import mod.hey.studios.util.ProjectFile;


public class ThemeManager {

    public static class ThemePreset {
        public String name;
        public int colorAccent;
        public int colorPrimary;
        public int colorPrimaryDark;
        public int colorControlHighlight;
        public int colorControlNormal;

        public ThemePreset(String name, int colorAccent, int colorPrimary, int colorPrimaryDark,
                           int colorControlHighlight, int colorControlNormal) {
            this.name = name;
            this.colorAccent = colorAccent;
            this.colorPrimary = colorPrimary;
            this.colorPrimaryDark = colorPrimaryDark;
            this.colorControlHighlight = colorControlHighlight;
            this.colorControlNormal = colorControlNormal;
        }
    }

    private static ThemePreset[] buildThemePresets() {
        final int accent = getSystemAccent();
        return new ThemePreset[]{
            new ThemePreset("Material Purple",
                    accent, 0xFF6200EE, 0xFF3700B3, 0xFFE8EAF6, 0xFFBDBDBD),

            new ThemePreset("Material Blue",
                    accent, 0xFF1976D2, 0xFF0D47A1, 0xFFE3F2FD, 0xFFBDBDBD),

            new ThemePreset("Material Green",
                    accent, 0xFF388E3C, 0xFF1B5E20, 0xFFE8F5E8, 0xFFBDBDBD),

            new ThemePreset("Material Red",
                    accent, 0xFFD32F2F, 0xFFB71C1C, 0xFFFFEBEE, 0xFFBDBDBD),

            new ThemePreset("Material Orange",
                    accent, 0xFFF57C00, 0xFFE65100, 0xFFFFF3E0, 0xFFBDBDBD),

            new ThemePreset("Material Teal",
                    accent, 0xFF00796B, 0xFF004D40, 0xFFE0F2F1, 0xFFBDBDBD),

            new ThemePreset("Material Indigo",
                    accent, 0xFF3F51B5, 0xFF1A237E, 0xFFE8EAF6, 0xFFBDBDBD),

            new ThemePreset("Material Pink",
                    accent, 0xFFC2185B, 0xFF880E4F, 0xFFFCE4EC, 0xFFBDBDBD),

            new ThemePreset("Dark Purple",
                    accent, 0xFFBB86FC, 0xFF3700B3, 0xFF1F1F1F, 0xFF666666),

            new ThemePreset("Dark Blue",
                    accent, 0xFF64B5F6, 0xFF1976D2, 0xFF1F1F1F, 0xFF666666),

            new ThemePreset("Light Purple",
                    accent, 0xFF6200EE, 0xFF3700B3, 0xFFF3E5F5, 0xFFE1BEE7),

            new ThemePreset("Light Blue",
                    accent, 0xFF1976D2, 0xFF0D47A1, 0xFFE3F2FD, 0xFFBBDEFB)
        };
    }

    /** Accent follows the system color on Android 12+, else the old Material teal. */
    private static int getSystemAccent() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return getDefaultColor(ProjectFile.COLOR_ACCENT);
        }
        return 0xFF03DAC5;
    }

    public static ThemePreset[] getThemePresets() {
        return buildThemePresets();
    }

    public static ThemePreset generateRandomTheme() {
        Random random = new Random();

        int primaryColor = Color.HSVToColor(new float[]{
                random.nextFloat() * 360,
                0.6f + random.nextFloat() * 0.3f,
                0.5f + random.nextFloat() * 0.4f
        });

        int primaryDarkColor = darkenColor(primaryColor, 0.3f);

        float[] hsv = new float[3];
        Color.colorToHSV(primaryColor, hsv);
        hsv[0] = (hsv[0] + 180) % 360;
        hsv[1] = 0.7f + random.nextFloat() * 0.2f;
        hsv[2] = 0.8f + random.nextFloat() * 0.2f;
        int accentColor = Color.HSVToColor(hsv);

        int controlHighlightColor = lightenColor(primaryColor, 0.9f);
        int controlNormalColor = Color.GRAY;

        return new ThemePreset("Random Theme", accentColor, primaryColor, primaryDarkColor,
                controlHighlightColor, controlNormalColor);
    }

    public static ThemePreset getDefault() {
        return new ThemePreset(
                "Default",
                getDefaultColor(ProjectFile.COLOR_ACCENT),
                getDefaultColor(ProjectFile.COLOR_PRIMARY),
                getDefaultColor(ProjectFile.COLOR_PRIMARY_DARK),
                getDefaultColor(ProjectFile.COLOR_CONTROL_HIGHLIGHT),
                getDefaultColor(ProjectFile.COLOR_CONTROL_NORMAL));
    }

    private static int darkenColor(int color, float factor) {
        float[] hsv = new float[3];
        Color.colorToHSV(color, hsv);
        hsv[2] *= (1 - factor);
        return Color.HSVToColor(hsv);
    }

    private static int lightenColor(int color, float factor) {
        float[] hsv = new float[3];
        Color.colorToHSV(color, hsv);
        hsv[2] = Math.min(1.0f, hsv[2] + factor);
        hsv[1] = Math.max(0.0f, hsv[1] - factor * 0.5f);
        return Color.HSVToColor(hsv);
    }

} 