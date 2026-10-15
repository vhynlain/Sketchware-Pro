package mod.ai.andiasriefail;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
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
 *   "generationConfig": { "maxOutputTokens": 16384 }
 * }
 * </pre>
 *
 * <p>Catatan: Gemini menggunakan role {@code "model"} (bukan {@code "assistant"}).
 *            History dari Claude format akan dikonversi otomatis.
 */
public final class AiClientGemini {

    private static final String BASE_URL   = "https://generativelanguage.googleapis.com/v1beta/models/";
    private static final MediaType JSON    = MediaType.parse("application/json; charset=utf-8");
    private static final int MAX_TOKENS    = 16384;
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
                           JSONArray claudeHistory, JSONArray tools) throws AiClient.AiException {
        if (apiKey == null || apiKey.trim().isEmpty()) {
            throw new AiClient.AiException("Gemini API key belum diisi. Buka Pengaturan AI lalu isi key.");
        }

        String url  = BASE_URL + model.trim() + ":generateContent";
        String body;
        try {
            body = buildRequestBody(model, systemPrompt, claudeHistory, tools).toString();
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
                    } catch (AiClient.AiException e) {
                        throw e;
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
            body = buildRequestBody(model, systemPrompt, claudeHistory, null).toString();
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
     * Build the Gemini request from the system prompt + Claude-format history (+ tools).
     * Claude blocks are mapped: text -> text, image -> inlineData, tool_use -> functionCall,
     * tool_result -> functionResponse (Gemini matches responses by function name, not id).
     */
    private static JSONObject buildRequestBody(String model, String systemPrompt,
                                               JSONArray claudeHistory, JSONArray tools) throws Exception {
        JSONObject body = new JSONObject();

        if (systemPrompt != null && !systemPrompt.trim().isEmpty()) {
            JSONObject sysPart = new JSONObject().put("text", systemPrompt);
            body.put("system_instruction", new JSONObject()
                    .put("parts", new JSONArray().put(sysPart)));
        }

        boolean gemini3 = model != null && model.toLowerCase().contains("gemini-3");
        Map<String, String> idToName = new HashMap<>();
        JSONArray contents = new JSONArray();
        for (int i = 0; i < claudeHistory.length(); i++) {
            JSONObject msg = claudeHistory.optJSONObject(i);
            if (msg == null) continue;
            boolean assistant = "assistant".equals(msg.optString("role", "user"));
            Object content = msg.opt("content");
            JSONArray parts = new JSONArray();
            boolean firstCall = true;

            if (content instanceof String) {
                if (!((String) content).isEmpty()) parts.put(new JSONObject().put("text", (String) content));
            } else if (content instanceof JSONArray) {
                JSONArray arr = (JSONArray) content;
                for (int j = 0; j < arr.length(); j++) {
                    JSONObject block = arr.optJSONObject(j);
                    if (block == null) continue;
                    String type = block.optString("type");
                    if ("text".equals(type)) {
                        String t = block.optString("text", "");
                        if (!t.isEmpty()) parts.put(new JSONObject().put("text", t));
                    } else if ("image".equals(type)) {
                        JSONObject src = block.optJSONObject("source");
                        if (src != null && src.has("data")) {
                            parts.put(new JSONObject().put("inlineData", new JSONObject()
                                    .put("mimeType", src.optString("media_type", "image/png"))
                                    .put("data", src.optString("data"))));
                        }
                    } else if ("tool_use".equals(type)) {
                        String id = block.optString("id");
                        String name = block.optString("name");
                        idToName.put(id, name);
                        JSONObject args = block.optJSONObject("input");
                        if (args == null) args = new JSONObject();
                        JSONObject part = new JSONObject().put("functionCall",
                                new JSONObject().put("name", name).put("args", args));
                        String sig = block.optString("thought_signature", "");
                        if (!sig.isEmpty()) {
                            part.put("thoughtSignature", sig);
                        } else if (gemini3 && firstCall) {
                            part.put("thoughtSignature", "skip_thought_signature_validator");
                        }
                        firstCall = false;
                        parts.put(part);
                    } else if ("tool_result".equals(type)) {
                        String name = idToName.get(block.optString("tool_use_id"));
                        if (name == null) name = "tool";
                        String out = toolResultText(block.opt("content"));
                        JSONObject resp = new JSONObject();
                        if (block.optBoolean("is_error", false)) resp.put("error", out);
                        else resp.put("result", out);
                        parts.put(new JSONObject().put("functionResponse",
                                new JSONObject().put("name", name).put("response", resp)));
                    }
                }
            }
            if (parts.length() == 0) continue;
            contents.put(new JSONObject()
                    .put("role", assistant ? "model" : "user")
                    .put("parts", parts));
        }
        body.put("contents", contents);

        JSONArray decls = convertTools(tools);
        if (decls.length() > 0) {
            body.put("tools", new JSONArray().put(new JSONObject().put("functionDeclarations", decls)));
        }

        body.put("generationConfig", new JSONObject().put("maxOutputTokens", MAX_TOKENS));
        return body;
    }

    private static String toolResultText(Object content) {
        String out;
        if (content instanceof JSONArray) {
            StringBuilder sb = new StringBuilder();
            JSONArray arr = (JSONArray) content;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject b = arr.optJSONObject(i);
                if (b != null && "text".equals(b.optString("type"))) sb.append(b.optString("text", ""));
            }
            out = sb.toString();
        } else {
            out = content == null ? "" : String.valueOf(content);
        }
        return out.isEmpty() ? "(no output)" : out;
    }

    /** Claude tools {name, description, input_schema} -> Gemini functionDeclarations. */
    private static JSONArray convertTools(JSONArray tools) throws Exception {
        JSONArray decls = new JSONArray();
        if (tools == null) return decls;
        for (int i = 0; i < tools.length(); i++) {
            JSONObject t = tools.optJSONObject(i);
            if (t == null) continue;
            JSONObject d = new JSONObject()
                    .put("name", t.optString("name"))
                    .put("description", t.optString("description", ""));
            JSONObject schema = t.optJSONObject("input_schema");
            if (schema != null) {
                JSONObject props = schema.optJSONObject("properties");
                if (props != null && props.length() > 0) d.put("parameters", cleanSchema(schema));
            }
            decls.put(d);
        }
        return decls;
    }

    /** Keep only the OpenAPI-subset keys Gemini accepts. */
    private static JSONObject cleanSchema(JSONObject in) throws Exception {
        JSONObject out = new JSONObject();
        for (String k : new String[]{"type", "description", "enum", "required", "format", "nullable"}) {
            if (in.has(k)) out.put(k, in.get(k));
        }
        Object type = in.opt("type");
        if (type instanceof JSONArray) { // e.g. ["string","null"]
            JSONArray ta = (JSONArray) type;
            String chosen = "string";
            for (int i = 0; i < ta.length(); i++) {
                if (!"null".equals(ta.optString(i))) { chosen = ta.optString(i); break; }
            }
            out.put("type", chosen);
        }
        JSONObject props = in.optJSONObject("properties");
        if (props != null) {
            JSONObject np = new JSONObject();
            java.util.Iterator<String> it = props.keys();
            while (it.hasNext()) {
                String key = it.next();
                JSONObject child = props.optJSONObject(key);
                if (child != null) np.put(key, cleanSchema(child));
            }
            out.put("properties", np);
        }
        JSONObject items = in.optJSONObject("items");
        if (items != null) out.put("items", cleanSchema(items));
        if ("array".equals(out.optString("type")) && !out.has("items")) {
            out.put("items", new JSONObject().put("type", "string"));
        }
        return out;
    }

    /**
     * Gemini response -> Claude format: text parts become a text block, functionCall parts become
     * tool_use blocks (stop_reason "tool_use").
     */
    private static JSONObject geminiToClaudeFormat(JSONObject resp) throws Exception {
        JSONArray cands = resp.optJSONArray("candidates");
        JSONObject cand = (cands == null) ? null : cands.optJSONObject(0);
        if (cand == null) {
            JSONObject fb = resp.optJSONObject("promptFeedback");
            String reason = fb == null ? "" : fb.optString("blockReason", "");
            throw new AiClient.AiException("Gemini returned no answer" + (reason.isEmpty() ? "" : " (blocked: " + reason + ")"));
        }

        StringBuilder text = new StringBuilder();
        JSONArray blocks = new JSONArray();
        boolean hasCall = false;
        JSONObject contentObj = cand.optJSONObject("content");
        JSONArray parts = contentObj == null ? null : contentObj.optJSONArray("parts");
        if (parts != null) {
            for (int j = 0; j < parts.length(); j++) {
                JSONObject part = parts.optJSONObject(j);
                if (part == null || part.optBoolean("thought", false)) continue;
                JSONObject fc = part.optJSONObject("functionCall");
                if (fc != null) {
                    if (text.length() > 0) {
                        blocks.put(new JSONObject().put("type", "text").put("text", text.toString()));
                        text.setLength(0);
                    }
                    JSONObject args = fc.optJSONObject("args");
                    JSONObject block = new JSONObject()
                            .put("type", "tool_use")
                            .put("id", "gem_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12))
                            .put("name", fc.optString("name"))
                            .put("input", args == null ? new JSONObject() : args);
                    String sig = part.optString("thoughtSignature", "");
                    if (!sig.isEmpty()) block.put("thought_signature", sig);
                    blocks.put(block);
                    hasCall = true;
                } else if (part.has("text")) {
                    text.append(part.optString("text", ""));
                }
            }
        }
        if (text.length() > 0 || blocks.length() == 0) {
            blocks.put(new JSONObject().put("type", "text").put("text", text.toString()));
        }

        String finish = cand.optString("finishReason", "STOP");
        String stop = hasCall ? "tool_use" : ("MAX_TOKENS".equals(finish) ? "max_tokens" : "end_turn");
        if (!hasCall && blocks.length() == 1 && text.length() == 0
                && !"STOP".equals(finish) && !"MAX_TOKENS".equals(finish)) {
            throw new AiClient.AiException("Gemini stopped without an answer (" + finish + ")");
        }
        return new JSONObject().put("content", blocks).put("stop_reason", stop);
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
