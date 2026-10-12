package mod.ai.andiasriefail;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Base class of every tool the AI can call. Tools always work on the single project held by
 * {@link Env#guard}; none of them accepts a project id from the model.
 */
public abstract class AiTool {

    /**
     * Everything a tool may use while running.
     */
    public static final class Env {
        public final Context context;
        public final AiProjectGuard guard;
        public final AiAttachments attachments;
        public final AiLogCollector logs;

        public Env(Context context, AiProjectGuard guard, AiAttachments attachments, AiLogCollector logs) {
            this.context = context;
            this.guard = guard;
            this.attachments = attachments;
            this.logs = logs;
        }
    }

    /**
     * Outcome of one tool call.
     */
    public static final class Result {
        public final boolean ok;
        public final String text;

        public Result(boolean ok, String text) {
            this.ok = ok;
            this.text = text == null ? "" : text;
        }
    }

    public static final int MAX_OUTPUT_CHARS = 20_000;

    public abstract String name();

    public abstract String description();

    public abstract JSONObject schema() throws Exception;

    protected abstract String run(Env env, JSONObject input) throws Exception;

    /**
     * @return true when the user must approve this call first
     */
    public boolean needsConfirmation(Env env, JSONObject input) {
        return false;
    }

    public String confirmationText(JSONObject input) {
        return "Izinkan asisten menjalankan " + name() + "?";
    }

    /**
     * Short text shown next to the tool name in the chat.
     */
    public String summary(JSONObject input) {
        String path = input.optString("path", "");
        if (!path.isEmpty()) return path;
        String from = input.optString("from", "");
        if (!from.isEmpty()) return from;
        return input.optString("name", input.optString("key", ""));
    }

    /**
     * Runs the tool. Never throws: every Throwable becomes a failed Result.
     */
    public final Result execute(Env env, JSONObject input) {
        try {
            String out = run(env, input == null ? new JSONObject() : input);
            return new Result(true, truncate(out));
        } catch (Throwable t) {
            String msg = t.getMessage();
            if (msg == null || msg.isEmpty()) msg = t.getClass().getSimpleName();
            return new Result(false, truncate("Error: " + msg));
        }
    }

    public final JSONObject toApiJson() throws Exception {
        JSONObject tool = new JSONObject();
        tool.put("name", name());
        tool.put("description", description());
        tool.put("input_schema", schema());
        return tool;
    }

    public static String truncate(String text) {
        if (text == null) return "";
        if (text.length() <= MAX_OUTPUT_CHARS) return text;
        return text.substring(0, MAX_OUTPUT_CHARS) + "\n...[dipotong, " + (text.length() - MAX_OUTPUT_CHARS) + " karakter lagi]";
    }

    // ------------------------------------------------------------ schema helpers

    /**
     * Builds an input schema. {@code fields} are triples: name, type (string|integer|boolean), description.
     */
    protected static JSONObject props(String[] required, String... fields) throws Exception {
        JSONObject properties = new JSONObject();
        for (int i = 0; i + 2 < fields.length; i += 3) {
            JSONObject p = new JSONObject();
            p.put("type", fields[i + 1]);
            p.put("description", fields[i + 2]);
            properties.put(fields[i], p);
        }
        JSONObject schema = new JSONObject();
        schema.put("type", "object");
        schema.put("properties", properties);
        JSONArray req = new JSONArray();
        if (required != null) for (String r : required) req.put(r);
        schema.put("required", req);
        return schema;
    }

    protected static String requireString(JSONObject in, String key) {
        String v = in.optString(key, null);
        if (v == null || v.isEmpty() || in.isNull(key)) {
            throw new IllegalArgumentException("Parameter '" + key + "' wajib diisi");
        }
        return v;
    }
}
