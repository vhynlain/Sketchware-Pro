package mod.hey.studios.util;

import android.content.Context;
import android.graphics.Color;
import android.os.Build;

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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Android 12+ (API 31): follow the system dynamic (Material You) colors
            Context context = SketchApplication.getContext();
            return switch (color) {
                case COLOR_PRIMARY ->
                        context.getColor(android.R.color.system_accent1_600);
                case COLOR_PRIMARY_DARK ->
                        context.getColor(android.R.color.system_accent1_700);
                case COLOR_ACCENT ->
                        context.getColor(android.R.color.system_accent2_500);
                case COLOR_CONTROL_HIGHLIGHT ->
                        context.getColor(android.R.color.system_accent1_100);
                case COLOR_CONTROL_NORMAL ->
                        context.getColor(android.R.color.system_neutral2_500);
                default -> context.getColor(android.R.color.system_accent1_500);
            };
        } else {
            // Below Android 12: static fallback colors
            return switch (color) {
                case COLOR_PRIMARY -> Color.parseColor("#ff2196f3");
                case COLOR_PRIMARY_DARK -> Color.parseColor("#ff1976d2");
                case COLOR_ACCENT -> Color.parseColor("#ff2196f3");
                case COLOR_CONTROL_HIGHLIGHT -> Color.parseColor("#202196f3");
                case COLOR_CONTROL_NORMAL -> Color.parseColor("#ff757575");
                default -> Color.parseColor("#ff2196f3");
            };
        }
    }
}
