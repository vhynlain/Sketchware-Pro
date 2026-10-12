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
 * Client untuk Google Gemini API.
 *
 * <p>Endpoint resmi (sumber: https://ai.google.dev/api):
 * <pre>
 *   Non-streaming: POST https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent
 *   Streaming SSE: POST https://generativelanguage.googleapis.com/v1beta/models/{model}:streamGenerateContent?alt=sse
 * </pre>
 *
 * <p>Auth: header {@code x-goog-api-key: <key>}
 *
 * <p>Format request (berbeda dengan OpenAI-compat):
 * <pre>
 * {
 *   "system_instruction": { "parts": [{ "text": "..." }] },
 *   "contents": [
 *     { "role": "user",  "parts": [{ "text": "..." }] },
 *     { "role": "model", "parts": [{ "text": "..." }] }
 *   ],
 *   "generationConfig": { "maxOutputTokens": 8192 }
 * }
 * </pre>
 *
 * <p>Catatan: Gemini menggunakan role {@code "model"} (bukan {@code "assistant"}).
 *            History dari Claude format akan dikonversi otomatis.
 */
public final class AiClientGemini {

    private static final String BASE_URL   = "https://generativelanguage.googleapis.com/v1beta/models/";
    private static final MediaType JSON    = MediaType.parse("application/json; charset=utf-8");
    private static final int MAX_TOKENS    = 8192;
    private static final int MAX_RETRIES   = 2;

    private final OkHttpClient http = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .readTimeout(180, TimeUnit.SECONDS)
            .build();

    private volatile Call  currentCall;
    private volatile boolean cancelled;

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

    // ---------------------------------------------------------------- non-streaming

    /**
     * Kirim pesan dan tunggu respons penuh (blocking).
     *
     * <p>History dalam format Claude (role "user"/"assistant") akan dikonversi ke format Gemini.
     */
    public JSONObject send(String apiKey, String model, String systemPrompt,
                           JSONArray claudeHistory) throws AiClient.AiException {
        if (apiKey == null || apiKey.trim().isEmpty()) {
            throw new AiClient.AiException("Gemini API key belum diisi. Buka Pengaturan AI lalu isi key.");
        }

        String url  = BASE_URL + model.trim() + ":generateContent";
        String body;
        try {
            body = buildRequestBody(systemPrompt, claudeHistory).toString();
        } catch (Throwable t) {
            throw new AiClient.AiException("Gagal menyusun permintaan Gemini: " + t.getMessage());
        }

        Request request = new Request.Builder()
                .url(url)
                .header("x-goog-api-key", apiKey.trim())
                .header("content-type", "application/json")
                .post(RequestBody.create(body.getBytes(StandardCharsets.UTF_8), JSON))
                .build();

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
                        // Gemini returns: { "candidates": [{ "content": { "parts": [{"text":"..."}] } }] }
                        return geminiToClaudeFormat(new JSONObject(text));
                    } catch (Throwable t) {
                        throw new AiClient.AiException("Balasan Gemini tidak dapat dibaca");
                    }
                }
                int code = response.code();
                String detail = extractGeminiError(text);
                if (code == 400) throw new AiClient.AiException("Permintaan Gemini ditolak (400): " + detail);
                if (code == 401 || code == 403) throw new AiClient.AiException("Gemini API key ditolak (" + code + "): " + detail);
                if (code == 404) throw new AiClient.AiException("Model Gemini tidak ditemukan (404). Pilih model lain. " + detail);
                if (code == 429 || code >= 500) {
                    lastErr = new AiClient.AiException((code == 429 ? "Gemini rate-limit (429)" : "Server Gemini error (" + code + ")") + ": " + detail);
                    sleepBackoff(attempt);
                    continue;
                }
                throw new AiClient.AiException("Gemini error (" + code + "): " + detail);
            } catch (AiClient.AiException e) {
                throw e;
            } catch (IOException e) {
                if (cancelled || call.isCanceled()) throw new AiClient.AiException("Dihentikan", true);
                lastErr = new AiClient.AiException("Gagal terhubung ke Gemini: " + msgOf(e));
                sleepBackoff(attempt);
            } catch (Throwable t) {
                throw new AiClient.AiException("Kesalahan tak terduga Gemini: " + t.getMessage());
            } finally {
                currentCall = null;
            }
        }
        throw lastErr != null ? lastErr : new AiClient.AiException("Permintaan Gemini gagal");
    }

    // ---------------------------------------------------------------- streaming

    /**
     * Kirim pesan dengan streaming SSE (Server-Sent Events).
     *
     * <p>Setiap potongan teks yang tiba langsung dikirim ke {@code callback.onDelta()}.
     * Setelah selesai (atau error), salah satu dari {@code onComplete} / {@code onError} dipanggil.
     */
    public void sendStreaming(String apiKey, String model, String systemPrompt,
                              JSONArray claudeHistory, AiStreamCallback callback) {
        if (apiKey == null || apiKey.trim().isEmpty()) {
            callback.onError("Gemini API key belum diisi.", false);
            return;
        }

        // Endpoint streaming: tambahkan ?alt=sse
        String url = BASE_URL + model.trim() + ":streamGenerateContent?alt=sse";
        String body;
        try {
            body = buildRequestBody(systemPrompt, claudeHistory).toString();
        } catch (Throwable t) {
            callback.onError("Gagal menyusun permintaan Gemini streaming: " + t.getMessage(), false);
            return;
        }

        Request request = new Request.Builder()
                .url(url)
                .header("x-goog-api-key", apiKey.trim())
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
                    String detail = extractGeminiError(errText);
                    if (code == 429 || code >= 500) {
                        lastErr = new AiClient.AiException((code == 429 ? "Gemini rate-limit" : "Server Gemini error (" + code + ")") + ": " + detail);
                        sleepBackoff(attempt);
                        continue;
                    }
                    callback.onError("Gemini error (" + code + "): " + detail, false);
                    return;
                }

                ResponseBody rb = response.body();
                if (rb == null) {
                    callback.onError("Gemini: respons streaming kosong", false);
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
                        // SSE format: "data: {json}\n"
                        if (!line.startsWith("data:")) continue;
                        String jsonStr = line.substring(5).trim();
                        if (jsonStr.isEmpty() || "[DONE]".equals(jsonStr)) continue;

                        try {
                            JSONObject chunk = new JSONObject(jsonStr);
                            String delta = extractGeminiDelta(chunk);
                            if (delta != null && !delta.isEmpty()) {
                                full.append(delta);
                                callback.onDelta(delta);
                            }
                        } catch (Throwable ignored) {
                            // chunk JSON tidak valid — lanjut saja
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
                lastErr = new AiClient.AiException("Gagal terhubung ke Gemini: " + msgOf(e));
                try { sleepBackoff(attempt); } catch (AiClient.AiException ex) { callback.onError(ex.getMessage(), ex.cancelled); return; }
            } catch (Throwable t) {
                callback.onError("Kesalahan tak terduga Gemini: " + t.getMessage(), false);
                return;
            } finally {
                currentCall = null;
            }
        }
        callback.onError(lastErr != null ? lastErr.getMessage() : "Permintaan Gemini gagal", false);
    }

    // ---------------------------------------------------------------- helpers

    /**
     * Bangun request body Gemini dari system prompt + history format Claude.
     *
     * <p>Konversi role: "assistant" → "model" (Gemini tidak mengenal "assistant").
     */
    private static JSONObject buildRequestBody(String systemPrompt, JSONArray claudeHistory) throws Exception {
        JSONObject body = new JSONObject();

        // System instruction
        if (systemPrompt != null && !systemPrompt.trim().isEmpty()) {
            JSONObject sysPart = new JSONObject().put("text", systemPrompt);
            body.put("system_instruction", new JSONObject()
                    .put("parts", new JSONArray().put(sysPart)));
        }

        // Konversi history Claude → Gemini
        JSONArray contents = new JSONArray();
        for (int i = 0; i < claudeHistory.length(); i++) {
            JSONObject msg = claudeHistory.optJSONObject(i);
            if (msg == null) continue;
            String role = msg.optString("role", "user");
            String geminiRole = "assistant".equals(role) ? "model" : "user";

            // Ambil teks dari content (bisa string atau array)
            String textContent = extractTextFromContent(msg.opt("content"));
            if (textContent == null || textContent.isEmpty()) continue;

            contents.put(new JSONObject()
                    .put("role", geminiRole)
                    .put("parts", new JSONArray().put(new JSONObject().put("text", textContent))));
        }
        body.put("contents", contents);

        // Generation config
        body.put("generationConfig", new JSONObject()
                .put("maxOutputTokens", MAX_TOKENS));

        return body;
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

    /**
     * Konversi respons Gemini ke format yang mirip Claude untuk kompatibilitas AiAgent.
     *
     * <p>Claude format: {@code { "content": [{ "type": "text", "text": "..." }] }}
     */
    private static JSONObject geminiToClaudeFormat(JSONObject geminiResp) throws Exception {
        StringBuilder sb = new StringBuilder();
        JSONArray candidates = geminiResp.optJSONArray("candidates");
        if (candidates != null) {
            for (int i = 0; i < candidates.length(); i++) {
                JSONObject cand = candidates.optJSONObject(i);
                if (cand == null) continue;
                JSONObject contentObj = cand.optJSONObject("content");
                if (contentObj == null) continue;
                JSONArray parts = contentObj.optJSONArray("parts");
                if (parts == null) continue;
                for (int j = 0; j < parts.length(); j++) {
                    JSONObject part = parts.optJSONObject(j);
                    if (part != null) sb.append(part.optString("text", ""));
                }
            }
        }
        JSONObject textBlock = new JSONObject().put("type", "text").put("text", sb.toString());
        return new JSONObject()
                .put("content", new JSONArray().put(textBlock))
                .put("stop_reason", "end_turn");
    }

    private static String extractGeminiDelta(JSONObject chunk) {
        try {
            JSONArray cands = chunk.optJSONArray("candidates");
            if (cands == null || cands.length() == 0) return null;
            JSONObject cand = cands.optJSONObject(0);
            if (cand == null) return null;
            JSONObject content = cand.optJSONObject("content");
            if (content == null) return null;
            JSONArray parts = content.optJSONArray("parts");
            if (parts == null || parts.length() == 0) return null;
            return parts.optJSONObject(0).optString("text", null);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String extractGeminiError(String text) {
        try {
            JSONObject json = new JSONObject(text);
            JSONObject error = json.optJSONObject("error");
            if (error != null) return error.optString("message", "");
        } catch (Throwable ignored) { }
        return text == null ? "" : (text.length() > 200 ? text.substring(0, 200) : text);
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
