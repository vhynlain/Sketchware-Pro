package mod.ai.andiasriefail;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Base64;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The agent loop: send the conversation to the model, run the tools it asks for, send the
 * results back, repeat until the model is done, the user stops it, or the step limit is hit.
 *
 * Everything inside {@link #runLoop} is wrapped in try/catch, and {@link Listener#onFinished}
 * is always called exactly once per run.
 */
public final class AiAgent {

    /**
     * Callbacks run on the agent's background thread. The UI must post to the main thread itself.
     */
    public interface Listener {
        void onAssistantText(String text);

        void onToolStart(String callId, String name, String summary);

        void onToolEnd(String callId, boolean ok, String output);

        void onStep(int used, int max);

        void onError(String message);

        /**
         * Blocks until the user answers.
         *
         * @return true if the user allows the action
         */
        boolean confirm(String title, String text);

        void onFinished(String note);
    }

    private static final String TAG = "AiAgent";
    private static final int COMPACT_THRESHOLD_CHARS = 600_000;
    private static final int KEEP_RECENT_MESSAGES = 8;
    private static final int MAX_IMAGE_SIDE = 1568;
    private static final int MAX_TEXT_ATTACHMENT_CHARS = 40_000;

    private final Context context;
    private final AiProjectGuard guard;
    private final AiAttachments attachments;
    private final AiLogCollector logs;
    private final AiToolRegistry registry = new AiToolRegistry();
    private final AiClient client = new AiClient();
    private final AiTool.Env env;
    private final Listener listener;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile boolean stopRequested = false;
    private volatile JSONArray history = new JSONArray();

    public AiAgent(Context context, AiProjectGuard guard, AiAttachments attachments, AiLogCollector logs, Listener listener) {
        this.context = context.getApplicationContext();
        this.guard = guard;
        this.attachments = attachments;
        this.logs = logs;
        this.listener = listener;
        this.env = new AiTool.Env(this.context, guard, attachments, logs);
    }

    public boolean isRunning() {
        return running.get();
    }

    public int toolCount() {
        return registry.size();
    }

    /**
     * Starts one run for the given user message.
     *
     * @return false if a run is already active or the request could not be started
     */
    public boolean start(String userText, List<AiAttachments.Item> items) {
        if (!running.compareAndSet(false, true)) return false;
        try {
            stopRequested = false;
            client.reset();
            JSONArray content = buildUserContent(userText, items);
            JSONArray updated = copyArray(history);
            updated.put(new JSONObject().put("role", "user").put("content", content));
            history = updated;
            executor.execute(this::runLoop);
            return true;
        } catch (Throwable t) {
            running.set(false);
            Log.e(TAG, "Failed to start", t);
            safeError(AiStrings.t(context, "Failed to start: ", "Gagal memulai: ") + t.getMessage());
            return false;
        }
    }

    public void stop() {
        stopRequested = true;
        client.cancel();
    }

    public void resetConversation() {
        if (running.get()) return;
        history = new JSONArray();
    }

    public void close() {
        stop();
        try {
            executor.shutdownNow();
        } catch (Throwable ignored) {
        }
    }

    // ---------------------------------------------------------------- the loop

    private void runLoop() {
        String note = AiStrings.t(context, "done", "selesai");
        try {
            String apiKey = AiSettings.getApiKey(context);
            String model = AiSettings.getModel(context);
            AiProvider provider = AiSettings.getProvider(context);
            int max = AiSettings.getMaxSteps(context);
            int used = 0;
            String system = AiPrompt.build(guard);
            JSONArray tools = registry.toApiJson();

            while (true) {
                if (stopRequested) {
                    note = AiStrings.t(context, "stopped", "dihentikan");
                    break;
                }
                compactHistory();
                JSONObject response = client.send(apiKey, model, system, history, tools, provider);

                JSONArray content = response.optJSONArray("content");
                if (content == null) throw new AiClient.AiException(AiStrings.t(context, "Empty reply from the server", "Balasan kosong dari server"));
                JSONArray updated = copyArray(history);
                updated.put(new JSONObject().put("role", "assistant").put("content", content));
                history = updated;

                List<JSONObject> toolCalls = new ArrayList<>();
                for (int i = 0; i < content.length(); i++) {
                    JSONObject block = content.optJSONObject(i);
                    if (block == null) continue;
                    String type = block.optString("type");
                    if ("text".equals(type)) {
                        String text = block.optString("text", "").trim();
                        if (!text.isEmpty()) safeText(text);
                    } else if ("tool_use".equals(type)) {
                        toolCalls.add(block);
                    }
                }

                if (toolCalls.isEmpty()) {
                    if ("max_tokens".equals(response.optString("stop_reason"))) {
                        note = AiStrings.t(context, "answer cut off (length limit)", "jawaban terpotong (batas panjang)");
                    }
                    break;
                }

                JSONArray results = new JSONArray();
                boolean limitHit = false;
                for (JSONObject call : toolCalls) {
                    String id = call.optString("id");
                    JSONObject result = new JSONObject().put("type", "tool_result").put("tool_use_id", id);
                    if (stopRequested) {
                        result.put("content", "Stopped by the user").put("is_error", true);
                    } else if (used >= max) {
                        limitHit = true;
                        result.put("content", "Step limit per task reached. Stop and summarize the latest state for the user.").put("is_error", true);
                    } else {
                        used++;
                        safeStep(used, max);
                        AiTool.Result r = runTool(call);
                        result.put("content", r.text);
                        if (!r.ok) result.put("is_error", true);
                    }
                    results.put(result);
                }
                JSONArray withResults = copyArray(history);
                withResults.put(new JSONObject().put("role", "user").put("content", results));
                history = withResults;

                if (stopRequested) {
                    note = AiStrings.t(context, "stopped", "dihentikan");
                    break;
                }
                if (limitHit) {
                    note = AiStrings.t(context, "step limit (" + max + ") reached", "batas langkah (" + max + ") tercapai");
                    // one last call so the model can summarize (tools stay declared because the history has tool calls)
                    try {
                        JSONObject last = client.send(apiKey, model, system, history, tools, provider);
                        JSONArray lastContent = last.optJSONArray("content");
                        if (lastContent != null) {
                            JSONArray fin = copyArray(history);
                            fin.put(new JSONObject().put("role", "assistant").put("content", lastContent));
                            history = fin;
                            for (int i = 0; i < lastContent.length(); i++) {
                                JSONObject b = lastContent.optJSONObject(i);
                                if (b != null && "text".equals(b.optString("type"))) safeText(b.optString("text", ""));
                            }
                            repairHistory(); // in case the model asked for another tool: close it with an error result
                        }
                    } catch (Throwable ignored) {
                    }
                    break;
                }
            }
        } catch (AiClient.AiException e) {
            repairHistory();
            if (e.cancelled || stopRequested) {
                note = AiStrings.t(context, "stopped", "dihentikan");
            } else {
                note = "error";
                safeError(e.getMessage());
            }
        } catch (Throwable t) {
            Log.e(TAG, "Agent loop failed", t);
            repairHistory();
            note = "error";
            String msg = t.getMessage();
            safeError(AiStrings.t(context, "Something went wrong: ", "Terjadi kesalahan: ") + (msg == null ? t.getClass().getSimpleName() : msg));
        } finally {
            running.set(false);
            try {
                listener.onFinished(note);
            } catch (Throwable t) {
                Log.e(TAG, "onFinished failed", t);
            }
        }
    }

    private AiTool.Result runTool(JSONObject call) {
        String id = call.optString("id");
        String name = call.optString("name");
        JSONObject input = call.optJSONObject("input");
        if (input == null) input = new JSONObject();
        try {
            AiTool tool = registry.get(name);
            if (tool == null) {
                safeToolStart(id, name, "");
                AiTool.Result r = new AiTool.Result(false, AiStrings.t(context, "Unknown tool: ", "Tool tidak dikenal: ") + name);
                safeToolEnd(id, false, r.text);
                return r;
            }
            String summary;
            try {
                summary = tool.summary(input);
            } catch (Throwable t) {
                summary = "";
            }
            safeToolStart(id, name, summary == null ? "" : summary);

            boolean needs;
            try {
                needs = tool.needsConfirmation(env, input);
            } catch (Throwable t) {
                needs = true;
            }
            if (needs) {
                boolean allowed;
                try {
                    allowed = listener.confirm(name, tool.confirmationText(context, input));
                } catch (Throwable t) {
                    allowed = false;
                }
                if (!allowed || stopRequested) {
                    AiTool.Result r = new AiTool.Result(false, AiStrings.t(context, "The user denied permission or cancelled.", "Pengguna menolak izin atau membatalkan."));
                    safeToolEnd(id, false, r.text);
                    return r;
                }
            }
            AiTool.Result r = tool.execute(env, input);
            safeToolEnd(id, r.ok, r.text);
            return r;
        } catch (Throwable t) {
            Log.e(TAG, "runTool failed", t);
            AiTool.Result r = new AiTool.Result(false, "Error: " + t.getMessage());
            safeToolEnd(id, false, r.text);
            return r;
        }
    }

    // ----------------------------------------------------------------- history

    private static JSONArray copyArray(JSONArray source) throws Exception {
        return new JSONArray(source.toString());
    }

    /**
     * If the last message is an assistant turn with tool calls but no results (because of an error),
     * add error results so the history stays valid for the next request.
     */
    private void repairHistory() {
        try {
            JSONArray h = history;
            if (h.length() == 0) return;
            JSONObject last = h.getJSONObject(h.length() - 1);
            if (!"assistant".equals(last.optString("role"))) return;
            JSONArray content = last.optJSONArray("content");
            if (content == null) return;
            JSONArray results = new JSONArray();
            for (int i = 0; i < content.length(); i++) {
                JSONObject b = content.optJSONObject(i);
                if (b != null && "tool_use".equals(b.optString("type"))) {
                    results.put(new JSONObject().put("type", "tool_result").put("tool_use_id", b.optString("id"))
                            .put("content", "Cancelled because of an error").put("is_error", true));
                }
            }
            if (results.length() > 0) {
                JSONArray updated = copyArray(h);
                updated.put(new JSONObject().put("role", "user").put("content", results));
                history = updated;
            }
        } catch (Throwable t) {
            Log.e(TAG, "repairHistory failed, clearing history", t);
            history = new JSONArray();
        }
    }

    /**
     * Shrinks old tool outputs and images when the conversation gets too large.
     */
    private void compactHistory() {
        try {
            JSONArray h = history;
            if (h.toString().length() < COMPACT_THRESHOLD_CHARS) return;
            JSONArray updated = copyArray(h);
            int limit = updated.length() - KEEP_RECENT_MESSAGES;
            for (int i = 0; i < limit; i++) {
                JSONObject msg = updated.getJSONObject(i);
                if (!"user".equals(msg.optString("role"))) continue;
                JSONArray content = msg.optJSONArray("content");
                if (content == null) continue;
                for (int j = 0; j < content.length(); j++) {
                    JSONObject b = content.optJSONObject(j);
                    if (b == null) continue;
                    String type = b.optString("type");
                    if ("tool_result".equals(type) && b.optString("content", "").length() > 400) {
                        b.put("content", "[old result cut to save tokens]");
                    } else if ("image".equals(type)) {
                        content.put(j, new JSONObject().put("type", "text").put("text", "[old image removed]"));
                    }
                }
            }
            history = updated;
        } catch (Throwable t) {
            Log.w(TAG, "compactHistory failed", t);
        }
    }

    // ---------------------------------------------------------- user message

    private JSONArray buildUserContent(String text, List<AiAttachments.Item> items) throws Exception {
        JSONArray content = new JSONArray();
        StringBuilder note = new StringBuilder();
        if (items != null) {
            for (AiAttachments.Item item : items) {
                try {
                    if (item.image) {
                        JSONObject block = imageBlock(item.file);
                        if (block != null) content.put(block);
                        note.append("\n- ").append(item.id).append(": ").append(item.displayName).append(" (image)");
                    } else if (isTextAttachment(item)) {
                        String body = ToolsFiles.readText(item.file);
                        if (body.length() > MAX_TEXT_ATTACHMENT_CHARS) {
                            body = body.substring(0, MAX_TEXT_ATTACHMENT_CHARS) + "\n...[cut]";
                        }
                        content.put(new JSONObject().put("type", "text")
                                .put("text", "Content of attachment " + item.id + " (" + item.displayName + "):\n" + body));
                        note.append("\n- ").append(item.id).append(": ").append(item.displayName).append(" (teks, isi disertakan di atas)");
                    } else {
                        note.append("\n- ").append(item.id).append(": ").append(item.displayName).append(" (").append(item.mime).append(")");
                    }
                } catch (Throwable t) {
                    note.append("\n- ").append(item.id).append(": ").append(item.displayName).append(" (could not be read: ").append(t.getMessage()).append(")");
                }
            }
        }
        String full = text == null ? "" : text.trim();
        if (note.length() > 0) full += "\n\n[Attachments from the user]" + note;
        if (full.isEmpty()) full = "(no text)";
        content.put(new JSONObject().put("type", "text").put("text", full));
        return content;
    }

    private static boolean isTextAttachment(AiAttachments.Item item) {
        if (item.mime != null && (item.mime.startsWith("text/") || item.mime.contains("json") || item.mime.contains("xml"))) return true;
        String n = item.displayName.toLowerCase(Locale.ROOT);
        return n.endsWith(".java") || n.endsWith(".kt") || n.endsWith(".xml") || n.endsWith(".json") || n.endsWith(".md")
                || n.endsWith(".txt") || n.endsWith(".gradle") || n.endsWith(".pro") || n.endsWith(".csv") || n.endsWith(".html");
    }

    private static JSONObject imageBlock(File file) throws Exception {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
        int sample = 1;
        while (Math.max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_IMAGE_SIDE) sample *= 2;
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sample;
        Bitmap bitmap = BitmapFactory.decodeFile(file.getAbsolutePath(), options);
        if (bitmap == null) return null;
        int maxSide = Math.max(bitmap.getWidth(), bitmap.getHeight());
        if (maxSide > MAX_IMAGE_SIDE) {
            float scale = (float) MAX_IMAGE_SIDE / maxSide;
            bitmap = Bitmap.createScaledBitmap(bitmap, Math.round(bitmap.getWidth() * scale), Math.round(bitmap.getHeight() * scale), true);
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out);
        JSONObject source = new JSONObject()
                .put("type", "base64")
                .put("media_type", "image/jpeg")
                .put("data", Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP));
        return new JSONObject().put("type", "image").put("source", source);
    }

    // ------------------------------------------------- listener calls, guarded

    private void safeText(String text) {
        try {
            listener.onAssistantText(text);
        } catch (Throwable t) {
            Log.e(TAG, "onAssistantText failed", t);
        }
    }

    private void safeError(String message) {
        try {
            listener.onError(message == null ? AiStrings.t(context, "Something went wrong", "Terjadi kesalahan") : message);
        } catch (Throwable t) {
            Log.e(TAG, "onError failed", t);
        }
    }

    private void safeStep(int used, int max) {
        try {
            listener.onStep(used, max);
        } catch (Throwable t) {
            Log.e(TAG, "onStep failed", t);
        }
    }

    private void safeToolStart(String id, String name, String summary) {
        try {
            listener.onToolStart(id, name, summary);
        } catch (Throwable t) {
            Log.e(TAG, "onToolStart failed", t);
        }
    }

    private void safeToolEnd(String id, boolean ok, String output) {
        try {
            listener.onToolEnd(id, ok, output);
        } catch (Throwable t) {
            Log.e(TAG, "onToolEnd failed", t);
        }
    }
}
