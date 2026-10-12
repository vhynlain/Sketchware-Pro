package mod.ai.andiasriefail;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Client AI terpusat. Mendukung lima provider:
 * <ul>
 *   <li>Claude (Anthropic) — {@code https://api.anthropic.com/v1/messages}
 *   <li>Gemini (Google)    — {@code https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent}
 *   <li>Mistral AI         — {@code https://api.mistral.ai/v1/chat/completions}
 *   <li>Groq               — {@code https://api.groq.com/openai/v1/chat/completions}
 *   <li>OpenAI / GPT       — {@code https://api.openai.com/v1/chat/completions}
 * </ul>
 *
 * <p>Semua metode blocking — panggil dari background thread.
 * Setiap kegagalan diubah menjadi {@link AiException} dengan pesan yang bisa dibaca pengguna.
 *
 * <p>Streaming tersedia via {@link #sendStreaming} dengan {@link AiStreamCallback}.
 * Abort via {@link #cancel()} bekerja untuk semua provider dan mode (streaming / non-streaming).
 */
public final class AiClient {

    // ================================================================ Exception

    public static final class AiException extends Exception {
        public final boolean cancelled;

        public AiException(String message, boolean cancelled) {
            super(message);
            this.cancelled = cancelled;
        }

        public AiException(String message) {
            this(message, false);
        }
    }

    // ================================================================ Claude constants (unchanged)

    private static final String CLAUDE_ENDPOINT   = "https://api.anthropic.com/v1/messages";
    private static final String CLAUDE_API_VERSION = "2023-06-01";
    private static final MediaType JSON            = MediaType.parse("application/json; charset=utf-8");
    private static final int MAX_TOKENS            = 8192;
    private static final int MAX_RETRIES           = 2;

    // ================================================================ Sub-clients (lazy init)

    private final OkHttpClient http = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .readTimeout(180, TimeUnit.SECONDS)
            .build();

    private final AiClientGemini         geminiClient  = new AiClientGemini();
    private final AiClientOpenAICompat   mistralClient = AiClientOpenAICompat.mistral();
    private final AiClientOpenAICompat   groqClient    = AiClientOpenAICompat.groq();
    private final AiClientOpenAICompat   openAIClient  = AiClientOpenAICompat.openAI();

    // ================================================================ Abort state

    private volatile Call    currentCall;
    private volatile boolean cancelled;

    /** Batalkan permintaan yang sedang berjalan (non-streaming maupun streaming). */
    public void cancel() {
        cancelled = true;
        try {
            Call c = currentCall;
            if (c != null) c.cancel();
        } catch (Throwable ignored) { }
        // Propagate ke semua sub-client
        geminiClient.cancel();
        mistralClient.cancel();
        groqClient.cancel();
        openAIClient.cancel();
    }

    /** Reset status cancel — panggil sebelum memulai request baru. */
    public void reset() {
        cancelled = false;
        geminiClient.reset();
        mistralClient.reset();
        groqClient.reset();
        openAIClient.reset();
    }

    // ================================================================ Non-streaming dispatch

    /**
     * Kirim satu request dan kembalikan JSONObject respons dalam format Claude.
     *
     * <p>Provider dipilih dari {@link AiSettings#getProvider(android.content.Context)}.
     * Metode ini mendeteksi provider via prefix model ID atau bisa dipanggil
     * langsung oleh AiAgent yang sudah membaca {@code AiSettings}.
     *
     * @param apiKey  API key provider aktif
     * @param model   ID model (dari AiSettings.getModel)
     * @param system  system prompt
     * @param history riwayat percakapan (format Claude JSONArray)
     * @param tools   daftar tool Claude (null jika tidak ada)
     */
    public JSONObject send(String apiKey, String model, String system,
                           JSONArray history, JSONArray tools) throws AiException {
        return send(apiKey, model, system, history, tools, AiProvider.CLAUDE);
    }

    /**
     * Kirim request ke provider tertentu.
     *
     * <p>Versi ini digunakan oleh AiAgent setelah membaca provider dari AiSettings.
     */
    public JSONObject send(String apiKey, String model, String system,
                           JSONArray history, JSONArray tools,
                           AiProvider provider) throws AiException {
        if (provider == null) provider = AiProvider.CLAUDE;
        switch (provider) {
            case GEMINI:  return geminiClient.send(apiKey, model, system, history);
            case MISTRAL: return mistralClient.send(apiKey, model, system, history, tools);
            case GROQ:    return groqClient.send(apiKey, model, system, history, tools);
            case GPT:     return openAIClient.send(apiKey, model, system, history, tools);
            default:      return sendClaude(apiKey, model, system, history, tools);
        }
    }

    // ================================================================ Streaming dispatch

    /**
     * Kirim request dengan streaming SSE.
     *
     * @param apiKey   API key provider aktif
     * @param model    ID model
     * @param system   system prompt
     * @param history  riwayat percakapan format Claude
     * @param tools    daftar tool (null jika tidak ada)
     * @param provider provider yang digunakan
     * @param callback penerima delta / selesai / error
     */
    public void sendStreaming(String apiKey, String model, String system,
                              JSONArray history, JSONArray tools,
                              AiProvider provider, AiStreamCallback callback) {
        if (provider == null) provider = AiProvider.CLAUDE;
        switch (provider) {
            case GEMINI:
                geminiClient.sendStreaming(apiKey, model, system, history, callback);
                break;
            case MISTRAL:
                mistralClient.sendStreaming(apiKey, model, system, history, tools, callback);
                break;
            case GROQ:
                groqClient.sendStreaming(apiKey, model, system, history, tools, callback);
                break;
            case GPT:
                openAIClient.sendStreaming(apiKey, model, system, history, tools, callback);
                break;
            default:
                // Claude streaming belum tersedia via SSE pada versi ini;
                // fallback ke non-streaming dan kirim hasilnya sebagai satu delta
                sendClaudeWithStreamingEmulation(apiKey, model, system, history, tools, callback);
                break;
        }
    }

    // ================================================================ Claude (internal)

    /**
     * Implementasi asli Claude blocking — tidak berubah dari versi sebelumnya.
     */
    private JSONObject sendClaude(String apiKey, String model, String system,
                                  JSONArray messages, JSONArray tools) throws AiException {
        if (apiKey == null || apiKey.trim().isEmpty()) {
            throw new AiException("API key belum diisi. Buka Pengaturan AI lalu isi key.");
        }

        String body;
        try {
            JSONObject json = new JSONObject();
            json.put("model", model);
            json.put("max_tokens", MAX_TOKENS);
            json.put("system", system);
            json.put("messages", messages);
            if (tools != null && tools.length() > 0) json.put("tools", tools);
            body = json.toString();
        } catch (Throwable t) {
            throw new AiException("Gagal menyusun permintaan: " + t.getMessage());
        }

        final byte[] bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Request request = new Request.Builder()
                .url(CLAUDE_ENDPOINT)
                .header("x-api-key", apiKey.trim())
                .header("anthropic-version", CLAUDE_API_VERSION)
                .header("content-type", "application/json")
                .post(new JsonBody(bytes))
                .build();

        AiException last = null;
        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            if (cancelled) throw new AiException("Dihentikan", true);
            Call call = http.newCall(request);
            currentCall = call;
            try (Response response = call.execute()) {
                ResponseBody responseBody = response.body();
                String text = responseBody == null ? "" : responseBody.string();
                if (response.isSuccessful()) {
                    try {
                        return new JSONObject(text);
                    } catch (Throwable t) {
                        throw new AiException("Balasan server tidak bisa dibaca");
                    }
                }
                int code = response.code();
                String detail = extractClaudeError(text);
                if (code == 401 || code == 403)
                    throw new AiException("API key ditolak (" + code + "). Periksa key di Pengaturan AI. " + detail);
                if (code == 400)
                    throw new AiException("Permintaan ditolak (400): " + detail);
                if (code == 404)
                    throw new AiException("Model tidak ditemukan (404). Pilih model lain di Pengaturan AI. " + detail);
                if (code == 429 || code == 500 || code == 502 || code == 503 || code == 529) {
                    last = new AiException((code == 429 ? "Terlalu banyak permintaan (429)" : "Server sibuk (" + code + ")") + ". " + detail);
                    sleepBackoff(attempt);
                    continue;
                }
                throw new AiException("Error server (" + code + "): " + detail);
            } catch (AiException e) {
                throw e;
            } catch (IOException e) {
                if (cancelled || call.isCanceled()) throw new AiException("Dihentikan", true);
                last = new AiException("Gagal terhubung: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
                sleepBackoff(attempt);
            } catch (Throwable t) {
                throw new AiException("Kesalahan tak terduga: " + t.getMessage());
            } finally {
                currentCall = null;
            }
        }
        throw last != null ? last : new AiException("Permintaan gagal");
    }

    /**
     * Emulasi streaming untuk Claude: lakukan request normal, lalu kirim hasilnya sebagai satu delta.
     * Ini menjamin callback interface tetap konsisten untuk semua provider.
     */
    private void sendClaudeWithStreamingEmulation(String apiKey, String model, String system,
                                                   JSONArray history, JSONArray tools,
                                                   AiStreamCallback callback) {
        try {
            JSONObject response = sendClaude(apiKey, model, system, history, tools);
            // Ambil teks dari respons Claude
            JSONArray content = response.optJSONArray("content");
            StringBuilder sb = new StringBuilder();
            if (content != null) {
                for (int i = 0; i < content.length(); i++) {
                    JSONObject block = content.optJSONObject(i);
                    if (block != null && "text".equals(block.optString("type"))) {
                        sb.append(block.optString("text", ""));
                    }
                }
            }
            String fullText = sb.toString();
            callback.onDelta(fullText);   // kirim sebagai satu delta besar
            callback.onComplete(fullText);
        } catch (AiException e) {
            callback.onError(e.getMessage(), e.cancelled);
        } catch (Throwable t) {
            callback.onError("Kesalahan tak terduga Claude: " + t.getMessage(), false);
        }
    }

    // ================================================================ Helpers

    private void sleepBackoff(int attempt) throws AiException {
        try {
            long ms = 1500L * (attempt + 1);
            long end = System.currentTimeMillis() + ms;
            while (System.currentTimeMillis() < end) {
                if (cancelled) throw new AiException("Dihentikan", true);
                Thread.sleep(100);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AiException("Dihentikan", true);
        }
    }

    private static String extractClaudeError(String text) {
        try {
            JSONObject json = new JSONObject(text);
            JSONObject error = json.optJSONObject("error");
            if (error != null) return error.optString("message", "");
        } catch (Throwable ignored) { }
        return text == null ? "" : (text.length() > 200 ? text.substring(0, 200) : text);
    }

    // ================================================================ JsonBody (unchanged)

    /** RequestBody yang bekerja di semua versi OkHttp. */
    private static final class JsonBody extends RequestBody {
        private final byte[] bytes;

        JsonBody(byte[] bytes) { this.bytes = bytes; }

        @Override public MediaType contentType() { return JSON; }
        @Override public long contentLength() { return bytes.length; }

        @Override public void writeTo(okio.BufferedSink sink) throws IOException {
            sink.write(bytes);
        }
    }
}
