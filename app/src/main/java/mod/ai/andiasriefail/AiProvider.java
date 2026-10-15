package mod.ai.andiasriefail;

/**
 * Semua provider AI yang didukung beserta model-model aktif per Oktober 2026.
 *
 * <p>Sumber resmi:
 * <ul>
 *   <li>Anthropic  — https://docs.anthropic.com/en/docs/about-claude/models
 *   <li>Google     — https://ai.google.dev/gemini-api/docs/models  (per 13 Okt 2026)
 *   <li>Mistral    — https://docs.mistral.ai/getting-started/models/models_overview
 *   <li>Groq       — https://console.groq.com/docs/models
 *   <li>OpenAI     — https://developers.openai.com/api/docs/models
 * </ul>
 */
public enum AiProvider {

    // ---------------------------------------------------------------- Claude (Anthropic)
    CLAUDE(
            "Claude (Anthropic)",
            new ModelEntry[]{
                    new ModelEntry("claude-sonnet-5-5",  "Claude Sonnet 5.5 (default)"),
                    new ModelEntry("claude-opus-5-5",    "Claude Opus 5.5"),
                    new ModelEntry("claude-haiku-5-5",   "Claude Haiku 5.5"),
            }
    ),

    // ---------------------------------------------------------------- Gemini (Google)
    // Model aktif Oktober 2026 — sumber: ai.google.dev/gemini-api/docs/models
    // gemini-3.5-flash: GA sejak 19 Mei 2026, pengganti utama Gemini 2.5 Flash
    // gemini-3.1-flash-lite: GA sejak 7 Mei 2026
    // gemini-3.6-flash: tersedia (short-term), belum ada tanggal pensiun
    // gemini-2.5-pro / flash / flash-lite: pensiun 16 Okt 2026 (masih aktif saat ini)
    GEMINI(
            "Gemini (Google)",
            new ModelEntry[]{
                    new ModelEntry("gemini-3.5-flash",        "Gemini 3.5 Flash (recommended)"),
                    new ModelEntry("gemini-3.6-flash",        "Gemini 3.6 Flash (short-term)"),
                    new ModelEntry("gemini-3.1-flash-lite",   "Gemini 3.1 Flash Lite"),
                    new ModelEntry("gemini-2.5-pro",          "Gemini 2.5 Pro (retiring Oct 16)"),
                    new ModelEntry("gemini-2.5-flash",        "Gemini 2.5 Flash (retiring Oct 16)"),
                    new ModelEntry("gemini-2.5-flash-lite",   "Gemini 2.5 Flash Lite (retiring Oct 16)"),
            }
    ),

    // ---------------------------------------------------------------- Mistral AI
    // Sumber: docs.mistral.ai/getting-started/models/models_overview (aktif Okt 2026)
    MISTRAL(
            "Mistral AI",
            new ModelEntry[]{
                    new ModelEntry("mistral-large-latest",    "Mistral Large (flagship)"),
                    new ModelEntry("mistral-medium-latest",   "Mistral Medium"),
                    new ModelEntry("mistral-small-latest",    "Mistral Small"),
                    new ModelEntry("magistral-medium-latest", "Magistral Medium (reasoning)"),
                    new ModelEntry("magistral-small-latest",  "Magistral Small (reasoning)"),
                    new ModelEntry("codestral-latest",        "Codestral (coding)"),
                    new ModelEntry("devstral-small-latest",   "Devstral Small (coding)"),
                    new ModelEntry("pixtral-large-latest",    "Pixtral Large (vision)"),
                    new ModelEntry("open-mistral-nemo",       "Mistral NeMo (open)"),
            }
    ),

    // ---------------------------------------------------------------- Groq
    // Sumber: console.groq.com/docs/models — Production + Preview aktif Okt 2026
    // Groq menggunakan endpoint OpenAI-compatible: https://api.groq.com/openai/v1
    GROQ(
            "Groq",
            new ModelEntry[]{
                    // Production models
                    new ModelEntry("llama-3.3-70b-versatile",                    "Llama 3.3 70B (production)"),
                    new ModelEntry("llama-3.1-8b-instant",                       "Llama 3.1 8B Instant (production)"),
                    new ModelEntry("gemma2-9b-it",                               "Gemma 2 9B (production)"),
                    // Preview models
                    new ModelEntry("meta-llama/llama-4-maverick-17b-128e-instruct", "Llama 4 Maverick 17B (preview)"),
                    new ModelEntry("meta-llama/llama-4-scout-17b-16e-instruct",     "Llama 4 Scout 17B (preview)"),
                    new ModelEntry("moonshotai/kimi-k2-instruct",                 "Kimi K2 (preview)"),
                    new ModelEntry("qwen/qwen3-32b",                             "Qwen3 32B (preview, reasoning)"),
                    new ModelEntry("qwen-qwq-32b",                               "Qwen QwQ 32B (preview, reasoning)"),
                    new ModelEntry("deepseek-r1-distill-llama-70b",              "DeepSeek R1 Distill 70B (preview)"),
                    new ModelEntry("openai/gpt-oss-120b",                        "GPT OSS 120B via Groq"),
                    new ModelEntry("openai/gpt-oss-20b",                         "GPT OSS 20B via Groq"),
            }
    ),

    // ---------------------------------------------------------------- OpenAI / GPT
    // Sumber: developers.openai.com/api/docs/changelog & models (aktif Okt 2026)
    // gpt-4 / gpt-4-turbo / gpt-4o (snapshot lama) pensiun 23 Okt 2026 — JANGAN gunakan
    // Model aktif: gpt-5.5, gpt-5.4, gpt-5.4-mini, gpt-5.4-nano, gpt-4o, gpt-4o-mini
    GPT(
            "OpenAI (GPT)",
            new ModelEntry[]{
                    new ModelEntry("gpt-5.5",       "GPT-5.5 (flagship, Apr 2026)"),
                    new ModelEntry("gpt-5.4",       "GPT-5.4 (frontier, Mar 2026)"),
                    new ModelEntry("gpt-5.4-mini",  "GPT-5.4 Mini (fast & cheap)"),
                    new ModelEntry("gpt-5.4-nano",  "GPT-5.4 Nano (ultra fast)"),
                    new ModelEntry("gpt-4o",        "GPT-4o (retires Oct 23)"),
                    new ModelEntry("gpt-4o-mini",   "GPT-4o Mini (retires Oct 23)"),
            }
    );

    // ---------------------------------------------------------------- fields

    public final String displayName;
    public final ModelEntry[] models;

    AiProvider(String displayName, ModelEntry[] models) {
        this.displayName = displayName;
        this.models = models;
    }

    /** @return ID model default (elemen pertama) untuk provider ini. */
    public String defaultModel() {
        return models[0].id;
    }

    /** Cari provider berdasarkan name()-nya; fallback ke CLAUDE. */
    public static AiProvider fromName(String name) {
        if (name == null) return CLAUDE;
        for (AiProvider p : values()) {
            if (p.name().equalsIgnoreCase(name)) return p;
        }
        return CLAUDE;
    }

    // ---------------------------------------------------------------- inner

    /** Pasangan ID model + label ramah pengguna. */
    public static final class ModelEntry {
        public final String id;
        public final String label;

        ModelEntry(String id, String label) {
            this.id = id;
            this.label = label;
        }
    }
}
