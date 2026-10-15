package mod.ai.andiasriefail;

import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import a.a.a.lC;
import com.besome.sketch.Config;
import mod.hey.studios.build.BuildSettings;
import mod.hey.studios.project.ProjectSettings;

/**
 * Project info, SDK versions and Build Settings tools.
 */
public final class ToolsProject {

    private ToolsProject() {
    }

    private static final Pattern CLASS_NAME = Pattern.compile("^\\.?[A-Za-z_][A-Za-z0-9_.]*$");
    private static final Pattern VERSION_NAME = Pattern.compile("^[A-Za-z0-9._+\\- ]{1,30}$");

    /** Keys kept in project_config (ProjectSettings). */
    static final List<String> PROJECT_KEYS = Arrays.asList(
            ProjectSettings.SETTING_ENABLE_BRIDGELESS_THEMES,
            ProjectSettings.SETTING_ENABLE_VIEWBINDING,
            ProjectSettings.SETTING_DISABLE_OLD_METHODS,
            ProjectSettings.SETTING_NEW_XML_COMMAND,
            ProjectSettings.SETTING_APPLICATION_CLASS);

    /** Keys kept in build_config (BuildSettings). */
    static final List<String> BUILD_KEYS = Arrays.asList(
            BuildSettings.SETTING_JAVA_VERSION,
            BuildSettings.SETTING_DEXER,
            BuildSettings.SETTING_NO_HTTP_LEGACY,
            BuildSettings.SETTING_NO_WARNINGS,
            BuildSettings.SETTING_ENABLE_LOGCAT,
            BuildSettings.SETTING_CLASSPATH,
            BuildSettings.SETTING_ANDROID_JAR_PATH);

    static final List<String> JAVA_VERSIONS = Arrays.asList(
            "1.7", "1.8", "1.9", "10", "11", "12", "13", "14", "15", "16", "17");

    private static boolean isBool(String v) {
        return "true".equals(v) || "false".equals(v);
    }

    static HashMap<String, Object> metadata(String scId) throws Exception {
        HashMap<String, Object> map = lC.b(scId);
        if (map == null) throw new IllegalStateException("Project data could not be read");
        return map;
    }

    private static String str(HashMap<String, Object> map, String key) {
        Object v = map.get(key);
        return v == null ? "" : String.valueOf(v);
    }

    // -------------------------------------------------------------------- tools

    public static final class GetProjectInfo extends AiTool {
        @Override
        public String name() {
            return "get_project_info";
        }

        @Override
        public String description() {
            return "Reads project info: app name, package, version name/code, min and target SDK, "
                    + "build settings (Java, dexer, logcat) and the custom icon.";
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
            String id = env.guard.getScId();
            HashMap<String, Object> m = metadata(id);
            ProjectSettings ps = new ProjectSettings(id);
            BuildSettings bs = new BuildSettings(id);
            StringBuilder sb = new StringBuilder();
            sb.append("app_name: ").append(str(m, "my_app_name")).append('\n');
            sb.append("project_name: ").append(str(m, "my_ws_name")).append('\n');
            sb.append("package: ").append(str(m, "my_sc_pkg_name")).append('\n');
            sb.append("version_name: ").append(str(m, "sc_ver_name")).append('\n');
            sb.append("version_code: ").append(str(m, "sc_ver_code")).append('\n');
            sb.append("custom_icon: ").append(str(m, "custom_icon")).append('\n');
            sb.append("min_sdk: ").append(ps.getValue(ProjectSettings.SETTING_MINIMUM_SDK_VERSION,
                    String.valueOf(Config.VAR_DEFAULT_MIN_SDK_VERSION))).append('\n');
            sb.append("target_sdk: ").append(ps.getValue(ProjectSettings.SETTING_TARGET_SDK_VERSION,
                    String.valueOf(Config.VAR_DEFAULT_TARGET_SDK_VERSION))).append('\n');
            sb.append("java_ver: ").append(bs.getValue(BuildSettings.SETTING_JAVA_VERSION, BuildSettings.DEFAULT_JAVA_VERSION)).append('\n');
            sb.append("dexer: ").append(bs.getValue(BuildSettings.SETTING_DEXER, BuildSettings.DEFAULT_DEXER)).append('\n');
            sb.append("enable_logcat (debug mode): ").append(bs.getValue(BuildSettings.SETTING_ENABLE_LOGCAT, "true")).append('\n');
            return sb.toString();
        }
    }

    public static final class SetProjectInfo extends AiTool {
        @Override
        public String name() {
            return "set_project_info";
        }

        @Override
        public String description() {
            return "Changes the app name, version name and/or version code. Fill in only what should change. "
                    + "The package name and project name cannot be changed with this tool.";
        }

        @Override
        public JSONObject schema() throws Exception {
            return props(new String[]{},
                    "app_name", "string", "App name shown in the launcher.",
                    "version_name", "string", "Version text, for example 1.2.0 (letters, digits, dot, minus).",
                    "version_code", "integer", "Integer version code >= 1.");
        }

        @Override
        public String summary(JSONObject input) {
            return input.toString();
        }

        @Override
        protected String run(Env env, JSONObject input) throws Exception {
            String id = env.guard.getScId();
            HashMap<String, Object> m = metadata(id);
            List<String> changes = new ArrayList<>();

            if (input.has("app_name") && !input.isNull("app_name")) {
                String name = input.getString("app_name").trim();
                if (name.isEmpty() || name.length() > 60) throw new IllegalArgumentException("app_name must be 1 to 60 characters");
                m.put("my_app_name", name);
                changes.add("app_name=" + name);
            }
            if (input.has("version_name") && !input.isNull("version_name")) {
                String v = input.getString("version_name").trim();
                if (!VERSION_NAME.matcher(v).matches()) throw new IllegalArgumentException("version_name is not valid");
                m.put("sc_ver_name", v);
                changes.add("version_name=" + v);
            }
            if (input.has("version_code") && !input.isNull("version_code")) {
                int code = input.getInt("version_code");
                if (code < 1) throw new IllegalArgumentException("version_code must be >= 1");
                m.put("sc_ver_code", String.valueOf(code));
                changes.add("version_code=" + code);
            }
            if (changes.isEmpty()) return "Nothing changed (give app_name, version_name or version_code).";

            lC.b(id, m);

            // keep strings.xml app_name in sync, like the project settings screen does
            if (input.has("app_name") && !input.isNull("app_name")) {
                File strings = new File(env.guard.getDataDir(), "files/resource/values/strings.xml");
                if (strings.isFile()) {
                    String xml = ToolsFiles.readText(strings);
                    String name = input.getString("app_name").trim()
                            .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
                    String updated = xml.replaceAll("(<string\\s+name=\"app_name\">)(.*?)(</string>)",
                            "$1" + java.util.regex.Matcher.quoteReplacement(name) + "$3");
                    ToolsFiles.validateXml(updated);
                    ToolsFiles.writeText(strings, updated);
                }
            }
            return "Changed: " + String.join(", ", changes);
        }
    }

    public static final class SetSdkVersions extends AiTool {
        @Override
        public String name() {
            return "set_sdk_versions";
        }

        @Override
        public String description() {
            return "Changes minSdk and/or targetSdk. Fill in only what should change. Range 21 to 40; min must not be greater than target.";
        }

        @Override
        public JSONObject schema() throws Exception {
            return props(new String[]{},
                    "min_sdk", "integer", "minSdkVersion, 21 sampai 40.",
                    "target_sdk", "integer", "targetSdkVersion, 21 sampai 40.");
        }

        @Override
        public String summary(JSONObject input) {
            return input.toString();
        }

        @Override
        protected String run(Env env, JSONObject input) throws Exception {
            ProjectSettings ps = new ProjectSettings(env.guard.getScId());
            int min = Integer.parseInt(ps.getValue(ProjectSettings.SETTING_MINIMUM_SDK_VERSION, String.valueOf(Config.VAR_DEFAULT_MIN_SDK_VERSION)));
            int target = Integer.parseInt(ps.getValue(ProjectSettings.SETTING_TARGET_SDK_VERSION, String.valueOf(Config.VAR_DEFAULT_TARGET_SDK_VERSION)));
            if (input.has("min_sdk") && !input.isNull("min_sdk")) min = input.getInt("min_sdk");
            if (input.has("target_sdk") && !input.isNull("target_sdk")) target = input.getInt("target_sdk");
            if (min < 21 || min > 40 || target < 21 || target > 40) throw new IllegalArgumentException("SDK must be between 21 and 40");
            if (min > target) throw new IllegalArgumentException("min_sdk (" + min + ") must not be greater than target_sdk (" + target + ")");
            ps.setValue(ProjectSettings.SETTING_MINIMUM_SDK_VERSION, String.valueOf(min));
            ps.setValue(ProjectSettings.SETTING_TARGET_SDK_VERSION, String.valueOf(target));
            return "min_sdk=" + min + ", target_sdk=" + target;
        }
    }

    public static final class GetBuildSettings extends AiTool {
        @Override
        public String name() {
            return "get_build_settings";
        }

        @Override
        public String description() {
            return "Reads all project and build settings that can be changed with set_build_setting, with their current values.";
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
            String id = env.guard.getScId();
            ProjectSettings ps = new ProjectSettings(id);
            BuildSettings bs = new BuildSettings(id);
            StringBuilder sb = new StringBuilder();
            sb.append("[build_config]\n");
            sb.append("java_ver=").append(bs.getValue(BuildSettings.SETTING_JAVA_VERSION, BuildSettings.DEFAULT_JAVA_VERSION)).append('\n');
            sb.append("dexer=").append(bs.getValue(BuildSettings.SETTING_DEXER, BuildSettings.DEFAULT_DEXER)).append('\n');
            sb.append("no_http_legacy=").append(bs.getValue(BuildSettings.SETTING_NO_HTTP_LEGACY, "false")).append('\n');
            sb.append("no_warn=").append(bs.getValue(BuildSettings.SETTING_NO_WARNINGS, "true")).append('\n');
            sb.append("enable_logcat=").append(bs.getValue(BuildSettings.SETTING_ENABLE_LOGCAT, "true")).append('\n');
            sb.append("classpath=").append(bs.getValue(BuildSettings.SETTING_CLASSPATH, "")).append('\n');
            sb.append("android_jar=").append(bs.getValue(BuildSettings.SETTING_ANDROID_JAR_PATH, "")).append('\n');
            sb.append("[project_config]\n");
            sb.append("enable_bridgeless_themes=").append(ps.getValue(ProjectSettings.SETTING_ENABLE_BRIDGELESS_THEMES, "false")).append('\n');
            sb.append("enable_viewbinding=").append(ps.getValue(ProjectSettings.SETTING_ENABLE_VIEWBINDING, "false")).append('\n');
            sb.append("disable_old_methods=").append(ps.getValue(ProjectSettings.SETTING_DISABLE_OLD_METHODS, "false")).append('\n');
            sb.append("xml_command=").append(ps.getValue(ProjectSettings.SETTING_NEW_XML_COMMAND, "false")).append('\n');
            sb.append("app_class=").append(ps.getValue(ProjectSettings.SETTING_APPLICATION_CLASS, ".SketchApplication")).append('\n');
            sb.append("\nNote: ProGuard, StringFog, permissions and local libraries have their own tools (get_code_shrinking, get_stringfog, list_permissions, list_local_libraries).");
            return sb.toString();
        }
    }

    public static final class SetBuildSetting extends AiTool {
        @Override
        public String name() {
            return "set_build_setting";
        }

        @Override
        public String description() {
            return "Changes one setting. Keys: java_ver (1.7, 1.8, 1.9, 10 to 17), dexer (D8 or Dx), "
                    + "no_http_legacy, no_warn, enable_logcat (debug mode on/off), enable_bridgeless_themes, enable_viewbinding, "
                    + "disable_old_methods, xml_command (true/false), app_class (Application class name), "
                    + "classpath and android_jar (file paths inside /sdcard/.sketchware/, classpath entries separated by ':').";
        }

        @Override
        public JSONObject schema() throws Exception {
            return props(new String[]{"key", "value"},
                    "key", "string", "Setting name.",
                    "value", "string", "New value. Booleans are written true or false.");
        }

        @Override
        public String summary(JSONObject input) {
            return input.optString("key", "") + "=" + input.optString("value", "");
        }

        private static void checkSketchwarePath(String raw) throws Exception {
            File base = new File(android.os.Environment.getExternalStorageDirectory(), ".sketchware").getCanonicalFile();
            for (String part : raw.split(":")) {
                String p = part.trim();
                if (p.isEmpty()) continue;
                File f = new File(p).getCanonicalFile();
                String c = f.getPath();
                if (!c.startsWith(base.getPath() + File.separator)) {
                    throw new SecurityException("Path must be inside " + base.getPath() + " : " + p);
                }
                if (!f.exists()) throw new IllegalArgumentException("File does not exist: " + p);
            }
        }

        @Override
        protected String run(Env env, JSONObject input) throws Exception {
            String key = requireString(input, "key").trim();
            String value = input.optString("value", "").trim();
            String id = env.guard.getScId();

            boolean buildKey = BUILD_KEYS.contains(key);
            boolean projectKey = PROJECT_KEYS.contains(key);
            if (!buildKey && !projectKey) {
                throw new IllegalArgumentException("Unknown key: " + key + ". See get_build_settings.");
            }

            switch (key) {
                case BuildSettings.SETTING_JAVA_VERSION:
                    if (!JAVA_VERSIONS.contains(value)) throw new IllegalArgumentException("java_ver must be one of: " + JAVA_VERSIONS + ". Maximum 17.");
                    break;
                case BuildSettings.SETTING_DEXER:
                    if (!value.equals("D8") && !value.equals("Dx")) throw new IllegalArgumentException("dexer must be D8 or Dx");
                    break;
                case BuildSettings.SETTING_NO_HTTP_LEGACY:
                case BuildSettings.SETTING_NO_WARNINGS:
                case BuildSettings.SETTING_ENABLE_LOGCAT:
                case ProjectSettings.SETTING_ENABLE_BRIDGELESS_THEMES:
                case ProjectSettings.SETTING_ENABLE_VIEWBINDING:
                case ProjectSettings.SETTING_DISABLE_OLD_METHODS:
                case ProjectSettings.SETTING_NEW_XML_COMMAND:
                    if (!isBool(value)) throw new IllegalArgumentException(key + " must be true or false");
                    break;
                case ProjectSettings.SETTING_APPLICATION_CLASS:
                    if (!CLASS_NAME.matcher(value).matches()) throw new IllegalArgumentException("app_class is not a valid class name");
                    break;
                case BuildSettings.SETTING_CLASSPATH:
                case BuildSettings.SETTING_ANDROID_JAR_PATH:
                    if (!value.isEmpty()) checkSketchwarePath(value);
                    break;
                default:
                    break;
            }

            if (buildKey) new BuildSettings(id).setValue(key, value);
            else new ProjectSettings(id).setValue(key, value);

            String extra = "";
            if (key.equals(BuildSettings.SETTING_JAVA_VERSION) && !value.equals("17")) {
                extra = " (17 with the D8 dexer is recommended)";
            }
            if (key.equals(BuildSettings.SETTING_DEXER) && value.equals("Dx")) {
                extra = " (disarankan D8)";
            }
            return key.toLowerCase(Locale.ROOT) + " = " + value + extra;
        }
    }

    static List<AiTool> all() {
        List<AiTool> tools = new ArrayList<>();
        Collections.addAll(tools, new GetProjectInfo(), new SetProjectInfo(), new SetSdkVersions(),
                new GetBuildSettings(), new SetBuildSetting());
        return tools;
    }
}
