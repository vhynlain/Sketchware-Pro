package pro.sketchware.utility;

import android.app.Activity;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;

import androidx.annotation.Nullable;

/**
 * Wrappers around Android APIs that got deprecated, each one using the new API where it exists
 * and falling back to the old one on older Android versions.
 */
public final class ApiCompat {

    private ApiCompat() {
    }

    /**
     * Gets the default {@link Vibrator}. Uses VibratorManager on Android 12+ (API 31).
     */
    @Nullable
    @SuppressWarnings("deprecation")
    public static Vibrator getVibrator(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            VibratorManager manager = (VibratorManager) context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
            return manager != null ? manager.getDefaultVibrator() : null;
        }
        return (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
    }

    /**
     * Vibrates for the given duration. Uses VibrationEffect on Android 8+ (API 26).
     */
    @SuppressWarnings("deprecation")
    public static void vibrate(@Nullable Vibrator vibrator, long milliseconds) {
        if (vibrator == null) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(milliseconds, VibrationEffect.DEFAULT_AMPLITUDE));
        } else {
            vibrator.vibrate(milliseconds);
        }
    }

    /**
     * Overrides the activity transition animation. Uses overrideActivityTransition on
     * Android 14+ (API 34), overridePendingTransition below.
     *
     * @param opening true for the open transition, false for the close transition
     */
    @SuppressWarnings("deprecation")
    public static void overrideTransition(Activity activity, boolean opening, int enterAnim, int exitAnim) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            activity.overrideActivityTransition(
                    opening ? Activity.OVERRIDE_TRANSITION_OPEN : Activity.OVERRIDE_TRANSITION_CLOSE,
                    enterAnim, exitAnim);
        } else {
            activity.overridePendingTransition(enterAnim, exitAnim);
        }
    }

    /**
     * Gets package info. Uses PackageInfoFlags on Android 13+ (API 33).
     */
    @SuppressWarnings("deprecation")
    public static PackageInfo getPackageInfo(PackageManager packageManager, String packageName, int flags)
            throws PackageManager.NameNotFoundException {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(flags));
        }
        return packageManager.getPackageInfo(packageName, flags);
    }

    /**
     * Gets application info. Uses ApplicationInfoFlags on Android 13+ (API 33).
     */
    @SuppressWarnings("deprecation")
    public static ApplicationInfo getApplicationInfo(PackageManager packageManager, String packageName, int flags)
            throws PackageManager.NameNotFoundException {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return packageManager.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(flags));
        }
        return packageManager.getApplicationInfo(packageName, flags);
    }
}
