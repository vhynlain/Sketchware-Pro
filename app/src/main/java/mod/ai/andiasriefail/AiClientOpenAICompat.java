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
    /** Name of the request field that limits the answer length ("max_tokens" or "max_completion_tokens"). */
    private final String tokenParam;
    /** Answer length limit (reasoning models count their thinking tokens in it). */
    private final int maxTokens;

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
        this(providerName, endpoint, "max_tokens", MAX_TOKENS);
    }

    public AiClientOpenAICompat(String providerName, String endpoint, String tokenParam, int maxTokens) {
        this.providerName = providerName;
        this.endpoint     = endpoint;
        this.tokenParam   = tokenParam;
        this.maxTokens    = maxTokens;
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
        // GPT-5 family rejects "max_tokens"; it needs "max_completion_tokens" (which also covers reasoning tokens)
        return new AiClientOpenAICompat("OpenAI", "https://api.openai.com/v1/chat/completions", "max_completion_tokens", 16384);
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
        String body;
        try {
            body = buildRequestBody(model, systemPrompt, claudeHistory, tools, false).toString();
        } catch (Throwable t) {
            throw new AiClient.AiException("Gagal menyusun permintaan " + providerName + ": " + t.getMessage());
        }
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
        body.put(tokenParam, maxTokens);

        JSONArray messages = new JSONArray();
        if (systemPrompt != null && !systemPrompt.trim().isEmpty()) {
            messages.put(new JSONObject()
                    .put("role", "system")
                    .put("content", systemPrompt));
        }
        convertHistory(messages, claudeHistory, model);
        body.put("messages", messages);

        if (tools != null && tools.length() > 0) {
            JSONArray openAiTools = convertToolsToOpenAI(tools);
            if (openAiTools.length() > 0) {
                body.put("tools", openAiTools);
                body.put("tool_choice", "auto");
            }
        }
        return body;
    }

    /**
     * Converts the Claude-format history (text, image, tool_use, tool_result blocks) to OpenAI chat messages:
     * assistant tool_use -> assistant.tool_calls, user tool_result -> role "tool" messages.
     */
    private void convertHistory(JSONArray out, JSONArray history, String model) throws Exception {
        boolean vision = supportsVision(model);
        for (int i = 0; i < history.length(); i++) {
            JSONObject msg = history.optJSONObject(i);
            if (msg == null) continue;
            boolean assistant = "assistant".equals(msg.optString("role", "user"));
            Object content = msg.opt("content");

            if (content instanceof String) {
                String text = (String) content;
                if (!text.isEmpty()) out.put(new JSONObject().put("role", assistant ? "assistant" : "user").put("content", text));
                continue;
            }
            if (!(content instanceof JSONArray)) continue;
            JSONArray blocks = (JSONArray) content;

            if (assistant) {
                StringBuilder text = new StringBuilder();
                JSONArray calls = new JSONArray();
                for (int j = 0; j < blocks.length(); j++) {
                    JSONObject b = blocks.optJSONObject(j);
                    if (b == null) continue;
                    String type = b.optString("type");
                    if ("text".equals(type)) {
                        text.append(b.optString("text", ""));
                    } else if ("tool_use".equals(type)) {
                        JSONObject input = b.optJSONObject("input");
                        if (input == null) input = new JSONObject();
                        JSONObject fn = new JSONObject()
                                .put("name", b.optString("name", ""))
                                .put("arguments", input.toString());
                        calls.put(new JSONObject()
                                .put("id", toolId(b.optString("id", "")))
                                .put("type", "function")
                                .put("function", fn));
                    }
                }
                if (text.length() == 0 && calls.length() == 0) continue;
                JSONObject m = new JSONObject().put("role", "assistant");
                if (text.length() > 0) m.put("content", text.toString());
                if (calls.length() > 0) m.put("tool_calls", calls);
                out.put(m);
            } else {
                JSONArray parts = new JSONArray();
                StringBuilder plain = new StringBuilder();
                boolean hasImage = false;
                for (int j = 0; j < blocks.length(); j++) {
                    JSONObject b = blocks.optJSONObject(j);
                    if (b == null) continue;
                    String type = b.optString("type");
                    if ("tool_result".equals(type)) {
                        String result = extractTextFromContent(b.opt("content"));
                        if (result == null || result.isEmpty()) result = "(no output)";
                        if (b.optBoolean("is_error", false)) result = "[error] " + result;
                        out.put(new JSONObject()
                                .put("role", "tool")
                                .put("tool_call_id", toolId(b.optString("tool_use_id", "")))
                                .put("content", result));
                    } else if ("text".equals(type)) {
                        String t = b.optString("text", "");
                        if (t.isEmpty()) continue;
                        parts.put(new JSONObject().put("type", "text").put("text", t));
                        if (plain.length() > 0) plain.append('\n');
                        plain.append(t);
                    } else if ("image".equals(type)) {
                        JSONObject source = b.optJSONObject("source");
                        if (vision && source != null && source.optString("data", "").length() > 0) {
                            String url = "data:" + source.optString("media_type", "image/jpeg") + ";base64," + source.optString("data");
                            parts.put(new JSONObject().put("type", "image_url")
                                    .put("image_url", new JSONObject().put("url", url)));
                            hasImage = true;
                        } else {
                            String note = "[image omitted: this model cannot see images]";
                            parts.put(new JSONObject().put("type", "text").put("text", note));
                            if (plain.length() > 0) plain.append('\n');
                            plain.append(note);
                        }
                    }
                }
                if (parts.length() == 0) continue;
                out.put(new JSONObject().put("role", "user").put("content", hasImage ? parts : plain.toString()));
            }
        }
    }

    /** Which models of this provider accept image input. Unknown models get text only. */
    private boolean supportsVision(String model) {
        String m = model == null ? "" : model.toLowerCase(java.util.Locale.ROOT);
        switch (providerName) {
            case "OpenAI":
                return true;
            case "Mistral":
                return m.contains("pixtral") || m.contains("mistral-medium") || m.contains("mistral-large") || m.contains("mistral-small");
            case "Groq":
                return m.contains("llama-4");
            default:
                return false;
        }
    }

    /** Mistral only accepts tool call ids of exactly 9 letters/digits; map any id to such a value (same input, same output). */
    private String toolId(String id) {
        if (!"Mistral".equals(providerName)) return id;
        if (id != null && id.matches("[a-zA-Z0-9]{9}")) return id;
        long h = 1125899906842597L;
        String src = id == null ? "" : id;
        for (int i = 0; i < src.length(); i++) h = 31 * h + src.charAt(i);
        StringBuilder sb = new StringBuilder(Long.toString(h & Long.MAX_VALUE, 36));
        while (sb.length() < 9) sb.insert(0, '0');
        return sb.substring(0, 9);
    }

    private static String newCallId() {
        return java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 9);
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
     * Converts an OpenAI chat completion into the Claude format the agent understands:
     * message.content -> text block, message.tool_calls -> tool_use blocks, finish_reason -> stop_reason.
     */
    private static JSONObject openAIToClaudeFormat(JSONObject openAIResp) throws Exception {
        JSONArray content = new JSONArray();
        boolean hasToolUse = false;
        String finishReason = "";
        JSONArray choices = openAIResp.optJSONArray("choices");
        if (choices != null && choices.length() > 0) {
            JSONObject choice = choices.optJSONObject(0);
            if (choice != null) {
                finishReason = choice.optString("finish_reason", "");
                JSONObject message = choice.optJSONObject("message");
                if (message != null) {
                    String text = contentToText(message.opt("content"));
                    if (!text.isEmpty()) content.put(new JSONObject().put("type", "text").put("text", text));
                    JSONArray calls = message.optJSONArray("tool_calls");
                    if (calls != null) {
                        for (int i = 0; i < calls.length(); i++) {
                            JSONObject call = calls.optJSONObject(i);
                            if (call == null) continue;
                            JSONObject fn = call.optJSONObject("function");
                            if (fn == null) continue;
                            String name = fn.optString("name", "");
                            if (name.isEmpty()) continue;
                            String id = call.optString("id", "");
                            if (id.isEmpty()) id = newCallId();
                            content.put(new JSONObject()
                                    .put("type", "tool_use")
                                    .put("id", id)
                                    .put("name", name)
                                    .put("input", parseArguments(fn.opt("arguments"))));
                            hasToolUse = true;
                        }
                    }
                }
            }
        }
        if (content.length() == 0) content.put(new JSONObject().put("type", "text").put("text", ""));
        String stopReason = hasToolUse ? "tool_use" : ("length".equals(finishReason) ? "max_tokens" : "end_turn");
        return new JSONObject().put("content", content).put("stop_reason", stopReason);
    }

    /** Tool arguments arrive as a JSON string (sometimes malformed); fall back to an empty object. */
    private static JSONObject parseArguments(Object raw) {
        try {
            if (raw instanceof JSONObject) return (JSONObject) raw;
            if (raw instanceof String) {
                String s = ((String) raw).trim();
                if (s.isEmpty()) return new JSONObject();
                return new JSONObject(s);
            }
        } catch (Throwable ignored) { }
        return new JSONObject();
    }

    /** message.content is a string, or (Mistral reasoning models) a list of typed chunks. */
    private static String contentToText(Object content) {
        if (content instanceof String) return (String) content;
        if (content instanceof JSONArray) {
            JSONArray arr = (JSONArray) content;
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject chunk = arr.optJSONObject(i);
                if (chunk != null && "text".equals(chunk.optString("type"))) sb.append(chunk.optString("text", ""));
            }
            return sb.toString();
        }
        return "";
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
