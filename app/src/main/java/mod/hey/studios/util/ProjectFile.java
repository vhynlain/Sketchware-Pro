package mod.hey.studios.util;

import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.TypedArray;
import android.graphics.Color;
import android.view.ContextThemeWrapper;

import a.a.a.lC;
import a.a.a.yB;
import pro.sketchware.SketchApplication;

public class ProjectFile {
    public static final String COLOR_ACCENT = "color_accent";
    public static final String COLOR_PRIMARY = "color_primary";
    public static final String COLOR_PRIMARY_DARK = "color_primary_dark";
    public static final String COLOR_CONTROL_HIGHLIGHT = "color_control_highlight";
    public static final String COLOR_CONTROL_NORMAL = "color_control_normal";

    public static int getColor(String sc_id, String color) {
        return yB.a(lC.b(sc_id), color, getDefaultColor(color));
        /*
        Old in-progress by Mike?
        HashMap<String, Object> hashMap = lC.b(sc_id);

        return 0;//yB.a(hashMap, color, getDefaultColor(color));
        */
    }

	/*
	 // comment by Jbk0
	 // the smali files say something completely different lol

	 package a.a.a;
	 import java.util.Map;

	 public class yB {
	 public static int a(Map<String, Object> paramMap, String paramString, int paramInt) {

	 return 0;

	 }
	 }
	*/

    public static int getDefaultColor(String color) {
        // Default = the Android system's own theme colors (Theme.Material.Light.DarkActionBar, same parent as AppTheme),
        // resolved straight from the system theme instead of being picked by Sketchware.
        return switch (color) {
            case COLOR_PRIMARY -> getSystemColor(android.R.attr.colorPrimary, "#ff2196f3");
            case COLOR_PRIMARY_DARK -> getSystemColor(android.R.attr.colorPrimaryDark, "#ff1976d2");
            case COLOR_ACCENT -> getSystemColor(android.R.attr.colorAccent, "#ff2196f3");
            case COLOR_CONTROL_HIGHLIGHT -> getSystemColor(android.R.attr.colorControlHighlight, "#202196f3");
            case COLOR_CONTROL_NORMAL -> getSystemColor(android.R.attr.colorControlNormal, "#ff757575");
            default -> getSystemColor(android.R.attr.colorPrimary, "#ff2196f3");
        };
    }

    private static int getSystemColor(int attr, String fallback) {
        try {
            Context themed = new ContextThemeWrapper(SketchApplication.getContext(), android.R.style.Theme_Material_Light_DarkActionBar);
            TypedArray a = themed.obtainStyledAttributes(new int[]{attr});
            try {
                ColorStateList colors = a.getColorStateList(0);
                if (colors != null) {
                    return colors.getDefaultColor();
                }
            } finally {
                a.recycle();
            }
        } catch (Exception ignored) {
        }
        return Color.parseColor(fallback);
    }
}
