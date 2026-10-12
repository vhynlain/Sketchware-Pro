package mod.ai.andiasriefail;

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

    public static final class BuildProject extends AiTool {
        @Override
        public String name() {
            return "build_project";
        }

        @Override
        public String description() {
            return "Membangun proyek ini di HP (lokal) dan menunggu hasilnya. Mengembalikan BUILD_SUCCESS atau BUILD_FAILED beserta teks error. "
                    + "Panggil setelah satu kelompok perubahan utuh, bukan setelah setiap file. APK tidak langsung dipasang.";
        }

        @Override
        public JSONObject schema() throws Exception {
            return props(new String[]{});
        }

        @Override
        public String summary(JSONObject input) {
            return "release";
        }

        @Override
        public boolean needsConfirmation(Env env, JSONObject input) {
            return AiSettings.isAskBeforeBuild(env.context);
        }

        @Override
        public String confirmationText(JSONObject input) {
            return "Izinkan asisten membangun proyek? Build memakai baterai dan beberapa menit.";
        }

        @Override
        protected String run(Env env, JSONObject input) throws Exception {
            return AiBuildBridge.buildAndWait(env.guard.getScId(), BUILD_TIMEOUT_SECONDS);
        }
    }

    public static final class GetBuildLog extends AiTool {
        @Override
        public String name() {
            return "get_build_log";
        }

        @Override
        public String description() {
            return "Membaca log error build terakhir yang tersimpan untuk proyek ini.";
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
            CompileErrorSaver saver = new CompileErrorSaver(env.guard.getScId());
            if (!saver.logFileExists()) return "Belum ada log build untuk proyek ini.";
            String log = saver.getLogsFromFile();
            return (log == null || log.trim().isEmpty()) ? "Log build kosong." : log;
        }
    }

    public static final class GetCrashLog extends AiTool {
        @Override
        public String name() {
            return "get_crash_log";
        }

        @Override
        public String description() {
            return "Membaca log crash/error dari aplikasi hasil build yang baru dijalankan pengguna. "
                    + "Hanya ada jika enable_logcat aktif dan aplikasi sudah dijalankan saat chat ini terbuka. "
                    + "Jika kosong, minta pengguna menjalankan aplikasinya.";
        }

        @Override
        public JSONObject schema() throws Exception {
            return props(new String[]{},
                    "clear", "boolean", "true untuk mengosongkan buffer log setelah dibaca.");
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
                return "Belum ada log dari aplikasi. Pastikan enable_logcat = true (get_build_settings), build ulang, "
                        + "minta pengguna menjalankan aplikasinya, lalu panggil lagi.";
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
