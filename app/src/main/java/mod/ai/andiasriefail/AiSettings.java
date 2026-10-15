package mod.ai.andiasriefail;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import android.util.Log;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Menyimpan pengaturan AI termasuk pemilihan provider dan model.
 *
 * <p>Setiap API key provider dienkripsi dengan Android Keystore AES-GCM sehingga
 * tidak pernah tersimpan sebagai teks biasa di disk.
 *
 * <p>Provider yang didukung: Claude, Gemini, Mistral, Groq, OpenAI/GPT.
 * Lihat {@link AiProvider} untuk daftar model per provider.
 */
public final class AiSettings {

    // ---------- backward-compat: model Claude yang sudah ada ----------
    /** @deprecated Gunakan {@link AiProvider#CLAUDE}.models */
    @Deprecated
    public static final String[] MODELS = {"claude-sonnet-5-5", "claude-opus-5-5", "claude-haiku-5-5"};
    /** @deprecated Gunakan {@link AiProvider#CLAUDE}.defaultModel() */
    @Deprecated
    public static final String DEFAULT_MODEL = MODELS[0];

    public static final String LANG_AUTO = "auto";
    public static final String LANG_EN   = "en";
    public static final String LANG_ID   = "id";

    public static final int DEFAULT_MAX_STEPS = 20;
    public static final int MIN_STEPS         = 5;
    public static final int MAX_STEPS         = 40;

    private static final String TAG   = "AiSettings";
    private static final String PREFS = "ai_andiasriefail_prefs";

    // Keystore alias — pisahkan per provider agar setiap key punya enkripsi sendiri
    private static final String KEY_ALIAS_CLAUDE  = "ai_andiasriefail_key";          // kompatibilitas lama
    private static final String KEY_ALIAS_GEMINI  = "ai_andiasriefail_gemini_key";
    private static final String KEY_ALIAS_MISTRAL = "ai_andiasriefail_mistral_key";
    private static final String KEY_ALIAS_GROQ    = "ai_andiasriefail_groq_key";
    private static final String KEY_ALIAS_GPT     = "ai_andiasriefail_gpt_key";

    // Preference keys
    private static final String K_API_KEY         = "api_key_enc";         // Claude (lama)
    private static final String K_API_KEY_GEMINI  = "api_key_gemini_enc";
    private static final String K_API_KEY_MISTRAL = "api_key_mistral_enc";
    private static final String K_API_KEY_GROQ    = "api_key_groq_enc";
    private static final String K_API_KEY_GPT     = "api_key_gpt_enc";

    private static final String K_MODEL           = "model";
    private static final String K_PROVIDER        = "provider";            // nama AiProvider enum
    private static final String K_MODEL_GEMINI    = "model_gemini";
    private static final String K_MODEL_MISTRAL   = "model_mistral";
    private static final String K_MODEL_GROQ      = "model_groq";
    private static final String K_MODEL_GPT       = "model_gpt";

    private static final String K_MAX_STEPS       = "max_steps";
    private static final String K_ASK_BUILD       = "ask_before_build";
    private static final String K_LANGUAGE        = "language";

    private AiSettings() { }

    // ================================================================ prefs helper

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    // ================================================================ provider

    /** Kembalikan provider yang sedang aktif. Default: CLAUDE. */
    public static AiProvider getProvider(Context context) {
        try {
            String name = prefs(context).getString(K_PROVIDER, AiProvider.CLAUDE.name());
            return AiProvider.fromName(name);
        } catch (Throwable t) {
            return AiProvider.CLAUDE;
        }
    }

    /** Simpan provider yang aktif. */
    public static void setProvider(Context context, AiProvider provider) {
        try {
            prefs(context).edit()
                    .putString(K_PROVIDER, provider == null ? AiProvider.CLAUDE.name() : provider.name())
                    .apply();
        } catch (Throwable t) {
            Log.e(TAG, "Failed to store provider", t);
        }
    }

    // ================================================================ model per provider

    /** Model aktif untuk provider yang sedang dipilih. */
    public static String getModel(Context context) {
        return getModelFor(context, getProvider(context));
    }

    /** Model aktif untuk provider tertentu. */
    public static String getModelFor(Context context, AiProvider provider) {
        try {
            String key = prefKeyModel(provider);
            String def = provider.defaultModel();
            String model = prefs(context).getString(key, def);
            return (model == null || model.trim().isEmpty()) ? def : model.trim();
        } catch (Throwable t) {
            return provider.defaultModel();
        }
    }

    /** Simpan model untuk provider yang sedang aktif. */
    public static void setModel(Context context, String model) {
        setModelFor(context, getProvider(context), model);
    }

    /** Simpan model untuk provider tertentu. */
    public static void setModelFor(Context context, AiProvider provider, String model) {
        try {
            String key = prefKeyModel(provider);
            String def = provider.defaultModel();
            prefs(context).edit()
                    .putString(key, model == null ? def : model.trim())
                    .apply();
        } catch (Throwable t) {
            Log.e(TAG, "Failed to store model", t);
        }
    }

    private static String prefKeyModel(AiProvider provider) {
        switch (provider) {
            case GEMINI:  return K_MODEL_GEMINI;
            case MISTRAL: return K_MODEL_MISTRAL;
            case GROQ:    return K_MODEL_GROQ;
            case GPT:     return K_MODEL_GPT;
            default:      return K_MODEL;
        }
    }

    // ================================================================ API key per provider

    /** Simpan API key untuk provider yang sedang aktif. */
    public static boolean setApiKey(Context context, String apiKey) {
        return setApiKeyFor(context, getProvider(context), apiKey);
    }

    /** Simpan API key terenkripsi untuk provider tertentu. */
    public static boolean setApiKeyFor(Context context, AiProvider provider, String apiKey) {
        try {
            String alias  = keyAliasFor(provider);
            String prefKey = prefKeyApiKey(provider);
            String trimmed = apiKey == null ? "" : apiKey.trim();
            if (trimmed.isEmpty()) {
                prefs(context).edit().remove(prefKey).apply();
                return true;
            }
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey(alias));
            byte[] iv        = cipher.getIV();
            byte[] encrypted = cipher.doFinal(trimmed.getBytes(StandardCharsets.UTF_8));
            String stored = Base64.encodeToString(iv, Base64.NO_WRAP)
                    + ":" + Base64.encodeToString(encrypted, Base64.NO_WRAP);
            prefs(context).edit().putString(prefKey, stored).apply();
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "Failed to store API key for " + provider, t);
            return false;
        }
    }

    /** Kembalikan API key terdekripsi untuk provider yang sedang aktif, atau "" jika tidak ada. */
    public static String getApiKey(Context context) {
        return getApiKeyFor(context, getProvider(context));
    }

    /** Kembalikan API key terdekripsi untuk provider tertentu, atau "" jika tidak ada/gagal. */
    public static String getApiKeyFor(Context context, AiProvider provider) {
        try {
            String alias   = keyAliasFor(provider);
            String prefKey = prefKeyApiKey(provider);
            String stored  = prefs(context).getString(prefKey, "");
            if (stored == null || stored.isEmpty()) return "";
            String[] parts = stored.split(":");
            if (parts.length != 2) return "";
            byte[] iv        = Base64.decode(parts[0], Base64.NO_WRAP);
            byte[] encrypted = Base64.decode(parts[1], Base64.NO_WRAP);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(alias), new GCMParameterSpec(128, iv));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (Throwable t) {
            Log.e(TAG, "Failed to read API key for " + provider, t);
            return "";
        }
    }

    /** Cek apakah provider yang sedang aktif sudah punya API key. */
    public static boolean hasApiKey(Context context) {
        return !getApiKey(context).isEmpty();
    }

    /** Cek apakah provider tertentu sudah punya API key. */
    public static boolean hasApiKeyFor(Context context, AiProvider provider) {
        return !getApiKeyFor(context, provider).isEmpty();
    }

    private static String prefKeyApiKey(AiProvider provider) {
        switch (provider) {
            case GEMINI:  return K_API_KEY_GEMINI;
            case MISTRAL: return K_API_KEY_MISTRAL;
            case GROQ:    return K_API_KEY_GROQ;
            case GPT:     return K_API_KEY_GPT;
            default:      return K_API_KEY;
        }
    }

    private static String keyAliasFor(AiProvider provider) {
        switch (provider) {
            case GEMINI:  return KEY_ALIAS_GEMINI;
            case MISTRAL: return KEY_ALIAS_MISTRAL;
            case GROQ:    return KEY_ALIAS_GROQ;
            case GPT:     return KEY_ALIAS_GPT;
            default:      return KEY_ALIAS_CLAUDE;
        }
    }

    // ================================================================ max steps

    public static int getMaxSteps(Context context) {
        try {
            int steps = prefs(context).getInt(K_MAX_STEPS, DEFAULT_MAX_STEPS);
            return Math.max(MIN_STEPS, Math.min(MAX_STEPS, steps));
        } catch (Throwable t) {
            return DEFAULT_MAX_STEPS;
        }
    }

    public static void setMaxSteps(Context context, int steps) {
        try {
            prefs(context).edit()
                    .putInt(K_MAX_STEPS, Math.max(MIN_STEPS, Math.min(MAX_STEPS, steps)))
                    .apply();
        } catch (Throwable t) {
            Log.e(TAG, "Failed to store max steps", t);
        }
    }

    // ================================================================ language

    /** @return {@link #LANG_EN} (default), {@link #LANG_ID} or {@link #LANG_AUTO} */
    public static String getLanguage(Context context) {
        try {
            String lang = prefs(context).getString(K_LANGUAGE, LANG_EN);
            if (LANG_ID.equals(lang) || LANG_AUTO.equals(lang)) return lang;
            return LANG_EN;
        } catch (Throwable t) {
            return LANG_EN;
        }
    }

    public static void setLanguage(Context context, String lang) {
        try {
            String value = LANG_ID.equals(lang) || LANG_AUTO.equals(lang) ? lang : LANG_EN;
            prefs(context).edit().putString(K_LANGUAGE, value).apply();
        } catch (Throwable t) {
            Log.e(TAG, "Failed to store language", t);
        }
    }

    // ================================================================ ask before build

    public static boolean isAskBeforeBuild(Context context) {
        try {
            return prefs(context).getBoolean(K_ASK_BUILD, false);
        } catch (Throwable t) {
            return false;
        }
    }

    public static void setAskBeforeBuild(Context context, boolean ask) {
        try {
            prefs(context).edit().putBoolean(K_ASK_BUILD, ask).apply();
        } catch (Throwable t) {
            Log.e(TAG, "Failed to store ask-before-build", t);
        }
    }

    // ================================================================ Keystore

    private static SecretKey getOrCreateKey(String alias) throws Exception {
        KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
        keyStore.load(null);
        if (!keyStore.containsAlias(alias)) {
            KeyGenerator generator = KeyGenerator.getInstance(
                    KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            generator.init(new KeyGenParameterSpec.Builder(alias,
                    KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build());
            return generator.generateKey();
        }
        KeyStore.SecretKeyEntry entry = (KeyStore.SecretKeyEntry) keyStore.getEntry(alias, null);
        return entry.getSecretKey();
    }
}
