package mod.ai.andiasriefail;

import android.content.Context;

import java.util.Locale;

/**
 * Tiny translation helper for the AI assistant screens. Every user-visible text is written as an
 * English / Indonesian pair at the place where it is used: {@code AiStrings.t(context, "Send", "Kirim")}.
 *
 * The language comes from {@link AiSettings#getLanguage(Context)}: English (default), Indonesian,
 * or "auto" which follows the device language.
 */
public final class AiStrings {

    private AiStrings() {
    }

    public static boolean isIndonesian(Context context) {
        String lang = AiSettings.getLanguage(context);
        if (AiSettings.LANG_ID.equals(lang)) return true;
        if (AiSettings.LANG_AUTO.equals(lang)) {
            String device = Locale.getDefault().getLanguage();
            return "id".equals(device) || "in".equals(device);
        }
        return false;
    }

    public static String t(Context context, String en, String id) {
        return isIndonesian(context) ? id : en;
    }
}
