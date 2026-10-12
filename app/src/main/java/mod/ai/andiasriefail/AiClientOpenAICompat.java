package mod.ai.andiasriefail;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Client generik untuk semua provider yang kompatibel dengan OpenAI Chat Completions API.
 *
 * <p>Digunakan oleh:
 * <ul>
 *   <li><b>Mistral AI</b>   — https://api.mistral.ai/v1/chat/completions  (auth: Bearer)
 *   <li><b>Groq</b>         — https://api.groq.com/openai/v1/chat/completions  (auth: Bearer)
 *   <li><b>OpenAI / GPT</b> — https://api.openai.com/v1/chat/completions  (auth: Bearer)
 * </ul>
 *
 * <p>Format request standar OpenAI:
 * <pre>
 * {
 *   "model": "...",
 *   "messages": [
 *     { "role": "system", "content": "..." },
 *     { "role": "user",   "content": "..." },
 *     { "role": "assistant", "content": "..." }
 *   ],
 *   "stream": false,          // atau true untuk streaming
 *   "max_tokens": 8192,
 *   "tools": [...]            // opsional
 * }
 * </pre>
 *
 * <p>Streaming: Server-Sent Events dengan format {@code data: {"choices":[{"delta":{"content":"..."}}]}}.
 */
public final class AiClientOpenAICompat {

    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    private static final int MAX_TOKENS  = 8192;
    private static final int MAX_RETRIES = 2;

    /** Nama provider (untuk pesan error). */
    private final String providerName;
    /** Base URL endpoint chat completions. */
    private final String endpoint;

    private final OkHttpClient http = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .readTimeout(180, TimeUnit.SECONDS)
            .build();

    private volatile Call    currentCall;
    private volatile boolean cancelled;

    /**
     * @param providerName nama ramah (e.g. "Mistral", "Groq", "OpenAI") — untuk pesan error
     * @param endpoint     URL lengkap endpoint chat completions
     */
    public AiClientOpenAICompat(String providerName, String endpoint) {
        this.providerName = providerName;
        this.endpoint     = endpoint;
    }

    // ---------------------------------------------------------------- Factory methods

    /** Buat client untuk Mistral AI. */
    public static AiClientOpenAICompat mistral() {
        return new AiClientOpenAICompat("Mistral", "https://api.mistral.ai/v1/chat/completions");
    }

    /** Buat client untuk Groq. */
    public static AiClientOpenAICompat groq() {
        return new AiClientOpenAICompat("Groq", "https://api.groq.com/openai/v1/chat/completions");
    }

    /** Buat client untuk OpenAI (GPT). */
    public static AiClientOpenAICompat openAI() {
        return new AiClientOpenAICompat("OpenAI", "https://api.openai.com/v1/chat/completions");
    }

    // ---------------------------------------------------------------- Abort

    public void cancel() {
        cancelled = true;
        try {
            Call c = currentCall;
            if (c != null) c.cancel();
        } catch (Throwable ignored) { }
    }

    public void reset() {
        cancelled = false;
    }

    // ---------------------------------------------------------------- Non-streaming

    /**
     * Kirim pesan dan tunggu respons penuh (blocking).
     *
     * <p>History dalam format Claude (role "user"/"assistant" + content array) dikonversi otomatis
     * ke format OpenAI (string content).
     *
     * @param apiKey      API key provider
     * @param model       ID model (misalnya "mistral-large-latest", "llama-3.3-70b-versatile", "gpt-5.5")
     * @param systemPrompt system prompt (akan dimasukkan sebagai pesan pertama dengan role "system")
     * @param claudeHistory riwayat percakapan dalam format Claude JSONArray
     * @param tools       daftar tool (bisa null — diabaikan jika provider tidak mendukung)
     * @return JSONObject respons dalam format mirip Claude untuk kompatibilitas AiAgent
     */
    public JSONObject send(String apiKey, String model, String systemPrompt,
                           JSONArray claudeHistory, JSONArray tools) throws AiClient.AiException {
        validateApiKey(apiKey);
        String body = buildRequestBody(model, systemPrompt, claudeHistory, tools, false).toString();
        Request request = buildRequest(apiKey, body);

        AiClient.AiException lastErr = null;
        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            if (cancelled) throw new AiClient.AiException("Dihentikan", true);
            Call call = http.newCall(request);
            currentCall = call;
            try (Response response = call.execute()) {
                ResponseBody rb = response.body();
                String text = rb == null ? "" : rb.string();
                if (response.isSuccessful()) {
                    try {
                        return openAIToClaudeFormat(new JSONObject(text));
                    } catch (Throwable t) {
                        throw new AiClient.AiException("Balasan " + providerName + " tidak dapat dibaca");
                    }
                }
                int code = response.code();
                String detail = extractOpenAIError(text);
                if (code == 400) throw new AiClient.AiException(providerName + " permintaan ditolak (400): " + detail);
                if (code == 401 || code == 403) throw new AiClient.AiException(providerName + " API key ditolak (" + code + "): " + detail);
                if (code == 404) throw new AiClient.AiException("Model " + providerName + " tidak ditemukan (404). Pilih model lain. " + detail);
                if (code == 429 || code >= 500) {
                    lastErr = new AiClient.AiException((code == 429
                            ? providerName + " rate-limit (429)"
                            : "Server " + providerName + " error (" + code + ")") + ": " + detail);
                    sleepBackoff(attempt);
                    continue;
                }
                throw new AiClient.AiException(providerName + " error (" + code + "): " + detail);
            } catch (AiClient.AiException e) {
                throw e;
            } catch (IOException e) {
                if (cancelled || call.isCanceled()) throw new AiClient.AiException("Dihentikan", true);
                lastErr = new AiClient.AiException("Gagal terhubung ke " + providerName + ": " + msgOf(e));
                sleepBackoff(attempt);
            } catch (Throwable t) {
                throw new AiClient.AiException("Kesalahan tak terduga " + providerName + ": " + t.getMessage());
            } finally {
                currentCall = null;
            }
        }
        throw lastErr != null ? lastErr : new AiClient.AiException("Permintaan " + providerName + " gagal");
    }

    // ---------------------------------------------------------------- Streaming

    /**
     * Kirim pesan dengan streaming SSE.
     *
     * <p>Format SSE OpenAI: {@code data: {"id":"...","choices":[{"delta":{"content":"..."}}]}}
     * Terakhir: {@code data: [DONE]}
     */
    public void sendStreaming(String apiKey, String model, String systemPrompt,
                              JSONArray claudeHistory, JSONArray tools,
                              AiStreamCallback callback) {
        if (apiKey == null || apiKey.trim().isEmpty()) {
            callback.onError(providerName + " API key belum diisi.", false);
            return;
        }
        String body;
        try {
            body = buildRequestBody(model, systemPrompt, claudeHistory, tools, true).toString();
        } catch (Throwable t) {
            callback.onError("Gagal menyusun permintaan " + providerName + " streaming: " + t.getMessage(), false);
            return;
        }

        Request request = new Request.Builder()
                .url(endpoint)
                .header("Authorization", "Bearer " + apiKey.trim())
                .header("content-type", "application/json")
                .header("Accept", "text/event-stream")
                .post(RequestBody.create(body.getBytes(StandardCharsets.UTF_8), JSON))
                .build();

        AiClient.AiException lastErr = null;
        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            if (cancelled) {
                callback.onError("Dihentikan", true);
                return;
            }
            Call call = http.newCall(request);
            currentCall = call;
            try (Response response = call.execute()) {
                if (!response.isSuccessful()) {
                    ResponseBody rb = response.body();
                    String errText = rb == null ? "" : rb.string();
                    int code = response.code();
                    String detail = extractOpenAIError(errText);
                    if (code == 429 || code >= 500) {
                        lastErr = new AiClient.AiException((code == 429
                                ? providerName + " rate-limit"
                                : "Server " + providerName + " error (" + code + ")") + ": " + detail);
                        sleepBackoff(attempt);
                        continue;
                    }
                    callback.onError(providerName + " error (" + code + "): " + detail, false);
                    return;
                }

                ResponseBody rb = response.body();
                if (rb == null) {
                    callback.onError(providerName + ": respons streaming kosong", false);
                    return;
                }

                StringBuilder full = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(rb.byteStream(), StandardCharsets.UTF_8))) {

                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (cancelled) {
                            callback.onError("Dihentikan", true);
                            return;
                        }
                        if (!line.startsWith("data:")) continue;
                        String jsonStr = line.substring(5).trim();
                        if (jsonStr.isEmpty() || "[DONE]".equals(jsonStr)) continue;

                        try {
                            JSONObject chunk = new JSONObject(jsonStr);
                            String delta = extractOpenAIDelta(chunk);
                            if (delta != null && !delta.isEmpty()) {
                                full.append(delta);
                                callback.onDelta(delta);
                            }
                        } catch (Throwable ignored) {
                            // chunk tidak valid — lanjut
                        }
                    }
                }
                callback.onComplete(full.toString());
                return; // sukses

            } catch (AiClient.AiException e) {
                callback.onError(e.getMessage(), e.cancelled);
                return;
            } catch (IOException e) {
                if (cancelled || call.isCanceled()) {
                    callback.onError("Dihentikan", true);
                    return;
                }
                lastErr = new AiClient.AiException("Gagal terhubung ke " + providerName + ": " + msgOf(e));
                try { sleepBackoff(attempt); } catch (AiClient.AiException ex) { callback.onError(ex.getMessage(), ex.cancelled); return; }
            } catch (Throwable t) {
                callback.onError("Kesalahan tak terduga " + providerName + ": " + t.getMessage(), false);
                return;
            } finally {
                currentCall = null;
            }
        }
        callback.onError(lastErr != null ? lastErr.getMessage() : "Permintaan " + providerName + " gagal", false);
    }

    // ---------------------------------------------------------------- Request builder

    private JSONObject buildRequestBody(String model, String systemPrompt,
                                        JSONArray claudeHistory, JSONArray tools,
                                        boolean stream) throws Exception {
        JSONObject body = new JSONObject();
        body.put("model", model.trim());
        body.put("stream", stream);
        body.put("max_tokens", MAX_TOKENS);

        // Bangun messages array
        JSONArray messages = new JSONArray();

        // System prompt sebagai pesan pertama
        if (systemPrompt != null && !systemPrompt.trim().isEmpty()) {
            messages.put(new JSONObject()
                    .put("role", "system")
                    .put("content", systemPrompt));
        }

        // Konversi history Claude → OpenAI format
        // Claude bisa punya content sebagai array [{type:"text",text:"..."}] — ratakan ke string
        for (int i = 0; i < claudeHistory.length(); i++) {
            JSONObject msg = claudeHistory.optJSONObject(i);
            if (msg == null) continue;
            String role    = msg.optString("role", "user");
            String content = extractTextFromContent(msg.opt("content"));
            if (content == null || content.isEmpty()) continue;
            messages.put(new JSONObject().put("role", role).put("content", content));
        }

        body.put("messages", messages);

        // Tools (hanya tambahkan jika ada dan provider mendukung)
        if (tools != null && tools.length() > 0) {
            // Konversi Claude tool format ke OpenAI function-calling format
            JSONArray openAiTools = convertToolsToOpenAI(tools);
            if (openAiTools.length() > 0) {
                body.put("tools", openAiTools);
                body.put("tool_choice", "auto");
            }
        }

        return body;
    }

    /**
     * Konversi tool format Claude ke OpenAI function calling.
     * Claude: { name, description, input_schema: { type, properties, required } }
     * OpenAI: { type: "function", function: { name, description, parameters: { ... } } }
     */
    private static JSONArray convertToolsToOpenAI(JSONArray claudeTools) {
        JSONArray result = new JSONArray();
        for (int i = 0; i < claudeTools.length(); i++) {
            try {
                JSONObject ct = claudeTools.optJSONObject(i);
                if (ct == null) continue;
                JSONObject func = new JSONObject()
                        .put("name", ct.optString("name", ""))
                        .put("description", ct.optString("description", ""))
                        .put("parameters", ct.optJSONObject("input_schema") != null
                                ? ct.optJSONObject("input_schema")
                                : new JSONObject().put("type", "object").put("properties", new JSONObject()));
                result.put(new JSONObject().put("type", "function").put("function", func));
            } catch (Throwable ignored) { }
        }
        return result;
    }

    private Request buildRequest(String apiKey, String body) {
        return new Request.Builder()
                .url(endpoint)
                .header("Authorization", "Bearer " + apiKey.trim())
                .header("content-type", "application/json")
                .post(RequestBody.create(body.getBytes(StandardCharsets.UTF_8), JSON))
                .build();
    }

    // ---------------------------------------------------------------- Format conversion

    /**
     * Konversi respons OpenAI ke format Claude agar AiAgent bisa memprosesnya.
     *
     * <p>OpenAI: {@code { "choices": [{ "message": { "role": "assistant", "content": "..." } }] }}
     * Claude:  {@code { "content": [{ "type": "text", "text": "..." }], "stop_reason": "end_turn" }}
     */
    private static JSONObject openAIToClaudeFormat(JSONObject openAIResp) throws Exception {
        JSONArray choices = openAIResp.optJSONArray("choices");
        String text = "";
        String stopReason = "end_turn";
        if (choices != null && choices.length() > 0) {
            JSONObject choice = choices.optJSONObject(0);
            if (choice != null) {
                JSONObject message = choice.optJSONObject("message");
                if (message != null) text = message.optString("content", "");
                String fr = choice.optString("finish_reason", "");
                if ("stop".equals(fr) || "length".equals(fr)) stopReason = "end_turn";
            }
        }
        JSONObject textBlock = new JSONObject().put("type", "text").put("text", text);
        return new JSONObject()
                .put("content", new JSONArray().put(textBlock))
                .put("stop_reason", stopReason);
    }

    private static String extractOpenAIDelta(JSONObject chunk) {
        try {
            JSONArray choices = chunk.optJSONArray("choices");
            if (choices == null || choices.length() == 0) return null;
            JSONObject delta = choices.optJSONObject(0).optJSONObject("delta");
            if (delta == null) return null;
            return delta.optString("content", null);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String extractTextFromContent(Object content) {
        if (content instanceof String) return (String) content;
        if (content instanceof JSONArray) {
            JSONArray arr = (JSONArray) content;
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject block = arr.optJSONObject(i);
                if (block != null && "text".equals(block.optString("type"))) {
                    sb.append(block.optString("text", ""));
                }
            }
            return sb.toString();
        }
        return "";
    }

    private static String extractOpenAIError(String text) {
        try {
            JSONObject json = new JSONObject(text);
            JSONObject error = json.optJSONObject("error");
            if (error != null) return error.optString("message", "");
        } catch (Throwable ignored) { }
        return text == null ? "" : (text.length() > 200 ? text.substring(0, 200) : text);
    }

    private void validateApiKey(String apiKey) throws AiClient.AiException {
        if (apiKey == null || apiKey.trim().isEmpty()) {
            throw new AiClient.AiException(providerName + " API key belum diisi. Buka Pengaturan AI lalu isi key.");
        }
    }

    private void sleepBackoff(int attempt) throws AiClient.AiException {
        try {
            long ms = 1500L * (attempt + 1);
            long end = System.currentTimeMillis() + ms;
            while (System.currentTimeMillis() < end) {
                if (cancelled) throw new AiClient.AiException("Dihentikan", true);
                Thread.sleep(100);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AiClient.AiException("Dihentikan", true);
        }
    }

    private static String msgOf(Throwable t) {
        return t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
    }
}
