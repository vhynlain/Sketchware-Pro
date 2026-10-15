package mod.ai.andiasriefail;

import android.content.Context;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import mod.jbk.diagnostic.CompileErrorSaver;

/**
 * Build and diagnostics tools.
 */
public final class ToolsBuild {

    private ToolsBuild() {
    }

    private static final long BUILD_TIMEOUT_SECONDS = 300;

    static String readBuildLog(String scId) throws Exception {
        CompileErrorSaver saver = new CompileErrorSaver(scId);
        if (!saver.logFileExists()) return "No saved build log for this project yet.";
        String log = saver.getLogsFromFile();
        return (log == null || log.trim().isEmpty()) ? "The saved build log is empty." : log;
    }

    public static final class BuildProject extends AiTool {
        @Override
        public String name() {
            return "build_project";
        }

        @Override
        public String description() {
            return "Builds this project locally on the phone and waits for the result. Returns BUILD_SUCCESS (with APK_PATH), "
                    + "BUILD_FAILED (with the real compile error and the saved build log), BUILD_CANCELED or BUILD_TIMEOUT. "
                    + "Call it after a complete group of changes, not after every file. The APK is not installed automatically; "
                    + "the chat shows an Install APK button after a successful build. Never call it just to look at the project: read the code instead.";
        }

        @Override
        public JSONObject schema() throws Exception {
            return props(new String[]{});
        }

        @Override
        public String summary(JSONObject input) {
            return "";
        }

        @Override
        public boolean needsConfirmation(Env env, JSONObject input) {
            return AiSettings.isAskBeforeBuild(env.context);
        }

        @Override
        public String confirmationText(Context context, JSONObject input) {
            return AiStrings.t(context, "Allow the assistant to build the project? A build uses battery and takes a few minutes.",
                    "Izinkan asisten membangun proyek? Build memakai baterai dan beberapa menit.");
        }

        @Override
        protected String run(Env env, JSONObject input) throws Exception {
            // If the editor activity was killed (for example the phone ran low on memory) the build runner is gone.
            // Try to reopen the editor of this project first instead of failing.
            AiBuildBridge.ensureEditor(env.context, env.guard.getScId());
            String result = AiBuildBridge.buildAndWait(env.guard.getScId(), BUILD_TIMEOUT_SECONDS);
            if (result.startsWith("BUILD_FAILED") || result.startsWith("BUILD_TIMEOUT")) {
                result = result + "\n\n--- saved build log (same as get_build_log) ---\n" + readBuildLog(env.guard.getScId());
            }
            return result;
        }
    }

    public static final class GetBuildLog extends AiTool {
        @Override
        public String name() {
            return "get_build_log";
        }

        @Override
        public String description() {
            return "Reads the last saved build error log of this project.";
        }

        @Override
        public JSONObject schema() throws Exception {
            return props(new String[]{});
        }

        @Override
        public String summary(JSONObject input) {
            return "";
        }

        @Override
        protected String run(Env env, JSONObject input) throws Exception {
            return readBuildLog(env.guard.getScId());
        }
    }

    public static final class GetCrashLog extends AiTool {
        @Override
        public String name() {
            return "get_crash_log";
        }

        @Override
        public String description() {
            return "Reads the crash/error log of the built app that the user just ran. "
                    + "It only exists if enable_logcat is on and the app was run while this chat was open. "
                    + "If it is empty, ask the user to run the app.";
        }

        @Override
        public JSONObject schema() throws Exception {
            return props(new String[]{},
                    "clear", "boolean", "true to clear the log buffer after reading.");
        }

        @Override
        public String summary(JSONObject input) {
            return "";
        }

        @Override
        protected String run(Env env, JSONObject input) throws Exception {
            String log = env.logs.summary(120);
            if (input.optBoolean("clear", false)) env.logs.clear();
            if (log.isEmpty()) {
                return "No log from the app yet. Make sure enable_logcat = true (get_build_settings), build again, "
                        + "ask the user to run the app, then call this again.";
            }
            return log;
        }
    }

    static List<AiTool> all() {
        List<AiTool> tools = new ArrayList<>();
        Collections.addAll(tools, new BuildProject(), new GetBuildLog(), new GetCrashLog());
        return tools;
    }
}
