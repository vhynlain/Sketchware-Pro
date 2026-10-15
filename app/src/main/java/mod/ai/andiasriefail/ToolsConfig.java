package mod.ai.andiasriefail;

import com.besome.sketch.Config;
import com.besome.sketch.beans.ProjectLibraryBean;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import a.a.a.jC;
import a.a.a.lC;
import dev.aldi.sayuti.editor.injection.AppCompatInjection;
import mod.hey.studios.project.ProjectSettings;

/**
 * Tools that mirror the "Configuration" drawer of the Sketchware Pro editor:
 * Library Manager, Permission Manager (see ToolsManifest), AndroidManifest Manager, AppCompat Manager,
 * Code Shrinking Manager, StringFog Manager and Custom Blocks, plus project-structure helpers
 * and helpers for custom (AI written) activities.
 *
 * Not covered on purpose: View Manager, Image/Sound/Font Manager (they edit the encrypted visual
 * project data). Use create_activity, res/drawable, res/raw, res/font and assets/ instead.
 */
public final class ToolsConfig {

    private ToolsConfig() {
    }

    private static final Pattern CLASS_NAME = Pattern.compile("^[A-Z][A-Za-z0-9]*$");
    private static final Pattern PACKAGE = Pattern.compile("^[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)+$");
    private static final Pattern SCREEN = Pattern.compile("^[a-z][a-z0-9_]*$");

    // ------------------------------------------------------------------ helpers

    static String packageName(AiProjectGuard guard) {
        HashMap<String, Object> m = lC.b(guard.getScId());
        Object pkg = m == null ? null : m.get("my_sc_pkg_name");
        String p = pkg == null ? "" : String.valueOf(pkg).trim();
        if (!PACKAGE.matcher(p).matches()) {
            throw new IllegalStateException("Cannot read a valid package name for this project. Call get_project_structure.");
        }
        return p;
    }

    static boolean appCompatEnabled(String scId) {
        ProjectLibraryBean compat = jC.c(scId).c();
        return compat != null && compat.isEnabled();
    }

    static String baseClass(String scId) {
        return appCompatEnabled(scId) ? "androidx.appcompat.app.AppCompatActivity" : "android.app.Activity";
    }

    private static String simple(String fq) {
        return fq.substring(fq.lastIndexOf('.') + 1);
    }

    private static String yn(boolean b) {
        return b ? "yes" : "no";
    }

    private static boolean dirExists(AiTool.Env env, String virtualPath) throws Exception {
        return env.guard.resolve(virtualPath, false).file.isDirectory();
    }

    private static boolean fileExists(AiTool.Env env, String virtualPath) throws Exception {
        return env.guard.resolve(virtualPath, false).file.isFile();
    }

    private static void ensureDir(File dir, List<String> created, String label) throws IOException {
        if (dir.isDirectory()) return;
        if (!dir.mkdirs()) throw new IOException("Could not create folder " + label);
        created.add(label);
    }

    private static void checkFree(AiTool.Env env, String virtualPath, boolean overwrite) throws Exception {
        AiProjectGuard.Resolved r = env.guard.resolve(virtualPath, true);
        if (r.file.exists() && !overwrite) {
            throw new IOException(r.virtualPath() + " already exists. Read it first, or pass overwrite=true to replace it.");
        }
    }

    private static String put(AiTool.Env env, String virtualPath, String content) throws Exception {
        AiProjectGuard.Resolved r = env.guard.resolve(virtualPath, true);
        if (virtualPath.endsWith(".xml")) ToolsFiles.validateXml(content);
        ToolsFiles.writeText(r.file, content);
        return "Wrote " + r.virtualPath();
    }

    /** Reads a flat {"key":"value"} JSON config such as data/ID/proguard. Missing file = empty object. */
    private static JSONObject readConfig(File file) throws Exception {
        if (!file.isFile()) return new JSONObject();
        String text = ToolsFiles.readText(file).trim();
        return text.isEmpty() ? new JSONObject() : new JSONObject(text);
    }

    private static String flag(JSONObject o, String key) {
        return o.has(key) ? o.optString(key) : "(unset)";
    }

    private static String snake(String camel) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < camel.length(); i++) {
            char c = camel.charAt(i);
            if (Character.isUpperCase(c) && i > 0) sb.append('_');
            sb.append(Character.toLowerCase(c));
        }
        return sb.toString();
    }

    // ---------------------------------------------------------------- templates

    private static final String MAIN_LAYOUT = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
            + "<LinearLayout xmlns:android=\"http://schemas.android.com/apk/res/android\"\n"
            + "    android:layout_width=\"match_parent\"\n"
            + "    android:layout_height=\"match_parent\"\n"
            + "    android:orientation=\"vertical\"\n"
            + "    android:padding=\"16dp\">\n\n"
            + "    <TextView\n"
            + "        android:id=\"@+id/title\"\n"
            + "        android:layout_width=\"match_parent\"\n"
            + "        android:layout_height=\"wrap_content\"\n"
            + "        android:text=\"Hello\"\n"
            + "        android:textSize=\"20sp\" />\n\n"
            + "    <Button\n"
            + "        android:id=\"@+id/button_add\"\n"
            + "        android:layout_width=\"match_parent\"\n"
            + "        android:layout_height=\"wrap_content\"\n"
            + "        android:text=\"Add\" />\n\n"
            + "</LinearLayout>\n";

    private static String layoutOnlyXml(String title) {
        return "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
                + "<LinearLayout xmlns:android=\"http://schemas.android.com/apk/res/android\"\n"
                + "    android:layout_width=\"match_parent\"\n"
                + "    android:layout_height=\"match_parent\"\n"
                + "    android:orientation=\"vertical\"\n"
                + "    android:padding=\"16dp\">\n\n"
                + "    <TextView\n"
                + "        android:id=\"@+id/title\"\n"
                + "        android:layout_width=\"match_parent\"\n"
                + "        android:layout_height=\"wrap_content\"\n"
                + "        android:text=\"" + title + "\"\n"
                + "        android:textSize=\"20sp\" />\n\n"
                + "</LinearLayout>\n";
    }

    private static String xmlActivitySource(String pkg, String cls, String baseFq, String layoutName) {
        return "package " + pkg + ";\n\n"
                + "import " + baseFq + ";\n"
                + "import android.os.Bundle;\n\n"
                + "public class " + cls + " extends " + simple(baseFq) + " {\n\n"
                + "    @Override\n"
                + "    protected void onCreate(Bundle savedInstanceState) {\n"
                + "        super.onCreate(savedInstanceState);\n"
                + "        setContentView(R.layout." + layoutName + ");\n"
                + "    }\n"
                + "}\n";
    }

    private static String programmaticSource(String pkg, String cls, String baseFq) {
        return "package " + pkg + ";\n\n"
                + "import " + baseFq + ";\n"
                + "import android.os.Bundle;\n"
                + "import android.view.View;\n"
                + "import android.widget.Button;\n"
                + "import android.widget.LinearLayout;\n"
                + "import android.widget.TextView;\n\n"
                + "public class " + cls + " extends " + simple(baseFq) + " {\n\n"
                + "    @Override\n"
                + "    protected void onCreate(Bundle savedInstanceState) {\n"
                + "        super.onCreate(savedInstanceState);\n\n"
                + "        LinearLayout root = new LinearLayout(this);\n"
                + "        root.setOrientation(LinearLayout.VERTICAL);\n"
                + "        int pad = (int) (16 * getResources().getDisplayMetrics().density);\n"
                + "        root.setPadding(pad, pad, pad, pad);\n\n"
                + "        TextView title = new TextView(this);\n"
                + "        title.setText(\"Hello\");\n"
                + "        title.setTextSize(20);\n"
                + "        root.addView(title, new LinearLayout.LayoutParams(\n"
                + "                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));\n\n"
                + "        Button add = new Button(this);\n"
                + "        add.setText(\"Add\");\n"
                + "        add.setOnClickListener(new View.OnClickListener() {\n"
                + "            @Override\n"
                + "            public void onClick(View v) {\n"
                + "                // TODO\n"
                + "            }\n"
                + "        });\n"
                + "        root.addView(add, new LinearLayout.LayoutParams(\n"
                + "                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));\n\n"
                + "        setContentView(root);\n"
                + "    }\n"
                + "}\n";
    }

    private static String appendToAppComponents(AiTool.Env env, String xml, String marker) throws Exception {
        File file = new File(env.guard.getInjectionDir(), "app_components.txt");
        String existing = file.isFile() ? ToolsFiles.readText(file) : "";
        if (existing.contains(marker)) return "app_components.txt already declares " + marker;
        String joined = existing.trim().isEmpty() ? xml : existing.replaceAll("\\s+$", "") + "\n" + xml;
        ToolsFiles.writeText(file, joined + "\n");
        return "Registered in app_components.txt: " + xml;
    }

    // -------------------------------------------------- structure (check + create)

    public static final class GetProjectStructure extends AiTool {
        @Override
        public String name() {
            return "get_project_structure";
        }

        @Override
        public String description() {
            return "One-call overview of the project: app name, package, SDK, built-in libraries, which folders exist, "
                    + "custom MainActivity/main.xml overrides, manifest injection files, shrinking/StringFog state, and whether "
                    + "a build has produced generated/ yet. Call this first. Missing folders are normal in a new project: "
                    + "create them with ensure_project_structure, make_dir, or just write_file (parent folders are created automatically).";
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
            StringBuilder sb = new StringBuilder();
            HashMap<String, Object> m = lC.b(id);
            if (m != null) {
                sb.append("App: ").append(m.get("my_app_name")).append('\n');
                sb.append("Package: ").append(m.get("my_sc_pkg_name")).append('\n');
                sb.append("Version: ").append(m.get("sc_ver_name")).append(" (").append(m.get("sc_ver_code")).append(")\n");
            }
            ProjectSettings ps = new ProjectSettings(id);
            sb.append("minSdk: ").append(ps.getValue(ProjectSettings.SETTING_MINIMUM_SDK_VERSION, String.valueOf(Config.VAR_DEFAULT_MIN_SDK_VERSION))).append('\n');
            sb.append("targetSdk: ").append(ps.getValue(ProjectSettings.SETTING_TARGET_SDK_VERSION, String.valueOf(Config.VAR_DEFAULT_TARGET_SDK_VERSION))).append('\n');

            ProjectLibraryBean compat = jC.c(id).c();
            boolean appcompat = compat != null && compat.isEnabled();
            boolean material3 = appcompat && compat.configurations != null
                    && Boolean.TRUE.equals(compat.configurations.get("material3"));
            ProjectLibraryBean firebase = jC.c(id).d();
            ProjectLibraryBean admob = jC.c(id).b();
            ProjectLibraryBean maps = jC.c(id).e();
            sb.append("\nBuilt-in libraries: appcompat=").append(yn(appcompat))
                    .append(", material3=").append(yn(material3))
                    .append(", firebase=").append(yn(firebase != null && firebase.isEnabled()))
                    .append(", admob=").append(yn(admob != null && admob.isEnabled()))
                    .append(", google_maps=").append(yn(maps != null && maps.isEnabled())).append('\n');

            sb.append("\nFolders (missing is normal in a new project):\n");
            String[] dirs = {"java", "res", "res/layout", "res/values", "res/drawable", "res/menu", "assets", "config/Injection/androidmanifest"};
            for (String d : dirs) {
                AiProjectGuard.Resolved r = env.guard.resolve(d, false);
                if (r.file.isDirectory()) {
                    String[] kids = r.file.list();
                    sb.append("  ").append(d).append("/  exists (").append(kids == null ? 0 : kids.length).append(" entries)\n");
                } else {
                    sb.append("  ").append(d).append("/  missing\n");
                }
            }

            sb.append("\nCustom overrides of generated code:\n");
            sb.append("  java/MainActivity.java: ").append(yn(fileExists(env, "java/MainActivity.java"))).append('\n');
            sb.append("  res/layout/main.xml: ").append(yn(fileExists(env, "res/layout/main.xml"))).append('\n');

            sb.append("\nManifest injection files:\n");
            for (String f : new String[]{"attributes.json", "activities_components.json", "activity_launcher.txt", "app_components.txt"}) {
                sb.append("  ").append(f).append(": ").append(new File(env.guard.getInjectionDir(), f).isFile() ? "exists" : "missing").append('\n');
            }
            sb.append("Permissions: ").append(ToolsManifest.readPermissions(env.guard).size()).append(" (list_permissions)\n");

            JSONObject pg = readConfig(new File(env.guard.getDataDir(), "proguard"));
            JSONObject sf = readConfig(new File(env.guard.getDataDir(), "stringfog"));
            sb.append("Code shrinking: enabled=").append(flag(pg, "enabled")).append(", r8=").append(flag(pg, "r8"))
                    .append(", debug=").append(flag(pg, "debug")).append('\n');
            sb.append("StringFog: enabled=").append(flag(sf, "enabled")).append('\n');

            boolean built = new File(env.guard.getRootDir(AiProjectGuard.ROOT_GENERATED), "app/src/main/AndroidManifest.xml").isFile();
            sb.append("\nBuilt before (generated/ filled): ").append(yn(built)).append('\n');
            if (built) {
                sb.append("Generated sources: generated/app/src/main/java/<package>/, layouts: generated/app/src/main/res/layout/, ")
                        .append("manifest: generated/app/src/main/AndroidManifest.xml\n");
            }
            return sb.toString();
        }
    }

    public static final class EnsureProjectStructure extends AiTool {
        @Override
        public String name() {
            return "ensure_project_structure";
        }

        @Override
        public String description() {
            return "Creates any missing standard folders and empty config files, and leaves existing ones untouched (safe to call repeatedly): "
                    + "java/, assets/, res/layout, res/values, res/drawable, res/menu, and the manifest injection folder with attributes.json and "
                    + "activities_components.json set to []. It never creates res/values/styles.xml or colors.xml (those would override the generated theme). "
                    + "Returns what was created.";
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
            List<String> created = new ArrayList<>();
            ensureDir(env.guard.getRootDir(AiProjectGuard.ROOT_JAVA), created, "java/");
            ensureDir(env.guard.getRootDir(AiProjectGuard.ROOT_ASSETS), created, "assets/");
            File res = env.guard.getRootDir(AiProjectGuard.ROOT_RES);
            for (String sub : new String[]{"layout", "values", "drawable", "menu"}) {
                ensureDir(new File(res, sub), created, "res/" + sub + "/");
            }
            File inj = env.guard.getInjectionDir();
            ensureDir(inj, created, "config/Injection/androidmanifest/");
            for (String f : new String[]{"attributes.json", "activities_components.json"}) {
                File file = new File(inj, f);
                if (!file.isFile()) {
                    ToolsFiles.writeText(file, "[]");
                    created.add("config/Injection/androidmanifest/" + f);
                }
            }
            if (created.isEmpty()) return "Nothing to create: the standard structure already exists.";
            return "Created:\n" + String.join("\n", created);
        }
    }

    // ------------------------------------------------ Library Manager (built-in)

    public static final class GetBuiltinLibraries extends AiTool {
        @Override
        public String name() {
            return "get_builtin_libraries";
        }

        @Override
        public String description() {
            return "Shows the built-in library switches of Library Manager: AppCompat, Material3 (with theme and dynamic colors), "
                    + "Firebase, AdMob, Google Maps. The app theme depends on these: Material3 -> Theme.Material3.*, AppCompat -> "
                    + "Theme.MaterialComponents.*, neither -> framework Theme.Material.Light.DarkActionBar.";
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
            ProjectLibraryBean compat = jC.c(id).c();
            boolean appcompat = compat != null && compat.isEnabled();
            HashMap<String, Object> cfg = compat == null ? null : compat.configurations;
            StringBuilder sb = new StringBuilder();
            sb.append("appcompat: ").append(appcompat ? "on" : "off").append('\n');
            sb.append("material3: ").append(cfg != null && Boolean.TRUE.equals(cfg.get("material3")) ? "on" : "off").append('\n');
            sb.append("material3_theme: ").append(cfg != null && cfg.get("theme") != null ? cfg.get("theme") : "DayNight").append('\n');
            sb.append("dynamic_colors: ").append(cfg != null && Boolean.TRUE.equals(cfg.get("dynamic_colors")) ? "on" : "off").append('\n');
            ProjectLibraryBean firebase = jC.c(id).d();
            ProjectLibraryBean admob = jC.c(id).b();
            ProjectLibraryBean maps = jC.c(id).e();
            sb.append("firebase: ").append(firebase != null && firebase.isEnabled() ? "on" : "off").append('\n');
            sb.append("admob: ").append(admob != null && admob.isEnabled() ? "on" : "off").append('\n');
            sb.append("google_maps: ").append(maps != null && maps.isEnabled() ? "on" : "off").append('\n');
            return sb.toString();
        }
    }

    public static final class SetBuiltinLibrary extends AiTool {
        @Override
        public String name() {
            return "set_builtin_library";
        }

        @Override
        public String description() {
            return "Turns the AppCompat or Material3 built-in library on or off (same as Library Manager). Enabling material3 also enables "
                    + "appcompat. Optional theme (DayNight, Light, Dark) and dynamic_colors apply to material3. Firebase, AdMob and Google Maps "
                    + "need IDs and are not supported here. Run build_project afterwards.";
        }

        @Override
        public JSONObject schema() throws Exception {
            return props(new String[]{"library", "enabled"},
                    "library", "string", "appcompat or material3",
                    "enabled", "boolean", "true to turn on, false to turn off",
                    "theme", "string", "material3 only: DayNight, Light or Dark",
                    "dynamic_colors", "boolean", "material3 only: dynamic colors on/off");
        }

        @Override
        public String summary(JSONObject input) {
            return input.optString("library", "") + "=" + input.opt("enabled");
        }

        @Override
        protected String run(Env env, JSONObject input) throws Exception {
            String id = env.guard.getScId();
            String lib = requireString(input, "library").toLowerCase(Locale.ROOT);
            if (!input.has("enabled")) throw new IllegalArgumentException("Parameter 'enabled' is required");
            boolean enabled = input.getBoolean("enabled");

            ProjectLibraryBean compat = jC.c(id).c();
            if (compat == null) compat = new ProjectLibraryBean(ProjectLibraryBean.PROJECT_LIB_TYPE_COMPAT);
            if (compat.configurations == null) compat.configurations = new HashMap<>();
            StringBuilder note = new StringBuilder();

            switch (lib) {
                case "appcompat":
                    if (!enabled) {
                        ProjectLibraryBean firebase = jC.c(id).d();
                        if (firebase != null && firebase.isEnabled()) {
                            throw new IllegalStateException("Firebase is on and needs AppCompat. Turn Firebase off in Library Manager first.");
                        }
                        compat.configurations.put("material3", Boolean.FALSE);
                    }
                    compat.useYn = enabled ? ProjectLibraryBean.LIB_USE_Y : ProjectLibraryBean.LIB_USE_N;
                    break;
                case "material3":
                    if (enabled && !compat.isEnabled()) {
                        compat.useYn = ProjectLibraryBean.LIB_USE_Y;
                        note.append("AppCompat was off and has been turned on (Material3 needs it). ");
                    }
                    compat.configurations.put("material3", Boolean.valueOf(enabled));
                    if (enabled) {
                        String theme = input.optString("theme", "").trim();
                        if (!theme.isEmpty()) {
                            if (!theme.equals("DayNight") && !theme.equals("Light") && !theme.equals("Dark")) {
                                throw new IllegalArgumentException("theme must be DayNight, Light or Dark");
                            }
                            compat.configurations.put("theme", theme);
                        } else if (!compat.configurations.containsKey("theme")) {
                            compat.configurations.put("theme", "DayNight");
                        }
                        if (input.has("dynamic_colors")) {
                            compat.configurations.put("dynamic_colors", Boolean.valueOf(input.getBoolean("dynamic_colors")));
                        }
                    }
                    break;
                default:
                    throw new IllegalArgumentException("library must be 'appcompat' or 'material3'");
            }

            // Same sequence as ManageLibraryActivity.saveLibraryConfiguration() for the AppCompat bean.
            jC.c(id).b(compat);
            jC.c(id).k();
            jC.b(id).a(jC.c(id));
            jC.a(id).a(jC.b(id));

            return note + lib + " is now " + (enabled ? "on" : "off")
                    + ". Run build_project to apply. A custom res/values/styles.xml that defines AppTheme still overrides the generated theme.";
        }
    }

    // ------------------------------------------- AndroidManifest Manager (append)

    public static final class AppendManifestInjection extends AiTool {
        @Override
        public String name() {
            return "append_manifest_injection";
        }

        @Override
        public String description() {
            return "Adds ONE entry to a manifest customization file without touching existing entries. For attributes.json and "
                    + "activities_components.json, 'entry' is a JSON object like {\"name\":\"...\",\"value\":\"...\"} (name and value are strings; "
                    + "for _application_permissions the value is android:name=\"android.permission.X\"). For app_components.txt, 'entry' is raw XML "
                    + "placed inside <application>, for example <service android:name=\".MyService\" />. Duplicates are skipped. "
                    + "No build is needed; use get_generated_manifest after a build to verify.";
        }

        @Override
        public JSONObject schema() throws Exception {
            return props(new String[]{"file", "entry"},
                    "file", "string", "attributes.json, activities_components.json or app_components.txt",
                    "entry", "string", "JSON object (for .json files) or raw XML (for app_components.txt)");
        }

        @Override
        public String summary(JSONObject input) {
            return input.optString("file", "");
        }

        @Override
        protected String run(Env env, JSONObject input) throws Exception {
            String name = requireString(input, "file");
            String entry = requireString(input, "entry").trim();
            if (!name.equals("attributes.json") && !name.equals("activities_components.json") && !name.equals("app_components.txt")) {
                throw new IllegalArgumentException("file must be attributes.json, activities_components.json or app_components.txt (use write_manifest_injection for activity_launcher.txt)");
            }
            File file = new File(env.guard.getInjectionDir(), name);
            String existing = file.isFile() ? ToolsFiles.readText(file) : "";

            if (name.endsWith(".json")) {
                JSONArray array = new JSONArray(existing.trim().isEmpty() ? "[]" : existing.trim());
                JSONObject obj = new JSONObject(entry);
                if (!(obj.opt("name") instanceof String) || !(obj.opt("value") instanceof String)) {
                    throw new IllegalArgumentException("entry must have string fields 'name' and 'value'");
                }
                for (int i = 0; i < array.length(); i++) {
                    JSONObject o = array.optJSONObject(i);
                    if (o != null && obj.optString("name").equals(o.optString("name"))
                            && obj.optString("value").equals(o.optString("value"))) {
                        return "Already present in " + name;
                    }
                }
                array.put(obj);
                String out = array.toString(2);
                if (out.length() > AiProjectGuard.MAX_WRITE_BYTES) throw new IllegalArgumentException("File too large");
                ToolsFiles.writeText(file, out);
                return "Appended to " + name + " (" + array.length() + " entries)";
            }
            return appendToAppComponents(env, entry, entry);
        }
    }

    // ----------------------------------------------------- AppCompat Manager

    public static final class GetAppCompatInjection extends AiTool {
        @Override
        public String name() {
            return "get_appcompat_injection";
        }

        @Override
        public String description() {
            return "Reads the AppCompat Manager attribute injections of one visual screen (screen = lowercase file name such as 'main'). "
                    + "A JSON array of {\"type\":\"toolbar|appbarlayout|coordinatorlayout|drawerlayout|navigationdrawer|...\",\"value\":\"attribute=\\\"x\\\"\"}. "
                    + "When no custom file exists the defaults are returned. These only affect visual screens that use toolbar/drawer/FAB options.";
        }

        @Override
        public JSONObject schema() throws Exception {
            return props(new String[]{"screen"}, "screen", "string", "Screen file name, e.g. main");
        }

        @Override
        public String summary(JSONObject input) {
            return input.optString("screen", "");
        }

        @Override
        protected String run(Env env, JSONObject input) throws Exception {
            String screen = requireString(input, "screen");
            if (!SCREEN.matcher(screen).matches()) throw new IllegalArgumentException("Invalid screen name: " + screen);
            File file = new File(env.guard.getDataDir(), "injection/appcompat/" + screen);
            if (!file.isFile() || file.length() == 0) {
                return "(no custom file, defaults in effect)\n" + AppCompatInjection.getDefaultActivityInjections();
            }
            return ToolsFiles.readText(file);
        }
    }

    public static final class SetAppCompatInjection extends AiTool {
        @Override
        public String name() {
            return "set_appcompat_injection";
        }

        @Override
        public String description() {
            return "Writes the complete AppCompat Manager injection list of one screen (overwrites). Start from get_appcompat_injection, keep the entries "
                    + "you do not want to change. Must be a JSON array of objects with string fields 'type' and 'value'.";
        }

        @Override
        public JSONObject schema() throws Exception {
            return props(new String[]{"screen", "content"},
                    "screen", "string", "Screen file name, e.g. main",
                    "content", "string", "Complete JSON array");
        }

        @Override
        public String summary(JSONObject input) {
            return input.optString("screen", "");
        }

        @Override
        protected String run(Env env, JSONObject input) throws Exception {
            String screen = requireString(input, "screen");
            if (!SCREEN.matcher(screen).matches()) throw new IllegalArgumentException("Invalid screen name: " + screen);
            String content = requireString(input, "content").trim();
            JSONArray array;
            try {
                array = new JSONArray(content);
            } catch (Exception e) {
                throw new IllegalArgumentException("Invalid JSON, file NOT saved: " + e.getMessage());
            }
            for (int i = 0; i < array.length(); i++) {
                JSONObject o = array.optJSONObject(i);
                if (o == null || !(o.opt("type") instanceof String) || !(o.opt("value") instanceof String)) {
                    throw new IllegalArgumentException("Element " + i + " must be an object with string 'type' and 'value'");
                }
            }
            if (content.length() > AiProjectGuard.MAX_WRITE_BYTES) throw new IllegalArgumentException("Content too large");
            ToolsFiles.writeText(new File(env.guard.getDataDir(), "injection/appcompat/" + screen), content);
            try {
                AppCompatInjection.refreshInjections();
            } catch (Throwable ignored) {
            }
            return "Saved injection/appcompat/" + screen + " (" + array.length() + " entries)";
        }
    }

    // ------------------------------------------- Code Shrinking / StringFog / Custom Blocks

    public static final class GetCodeShrinking extends AiTool {
        @Override
        public String name() {
            return "get_code_shrinking";
        }

        @Override
        public String description() {
            return "Shows Code Shrinking Manager state (enabled, r8, debug). Rules live in config/proguard-rules.pro (read_file/write_file).";
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
            JSONObject c = readConfig(new File(env.guard.getDataDir(), "proguard"));
            return "enabled: " + flag(c, "enabled") + "\nr8: " + flag(c, "r8") + "\ndebug: " + flag(c, "debug");
        }
    }

    public static final class SetCodeShrinking extends AiTool {
        @Override
        public String name() {
            return "set_code_shrinking";
        }

        @Override
        public String description() {
            return "Changes Code Shrinking Manager switches. Pass only the ones to change: enabled (shrink/obfuscate on or off), "
                    + "r8 (use R8 instead of ProGuard), debug (keep debug files).";
        }

        @Override
        public JSONObject schema() throws Exception {
            return props(new String[]{},
                    "enabled", "boolean", "Turn code shrinking on or off",
                    "r8", "boolean", "Use R8",
                    "debug", "boolean", "Keep debug files");
        }

        @Override
        public String summary(JSONObject input) {
            return "";
        }

        @Override
        protected String run(Env env, JSONObject input) throws Exception {
            File file = new File(env.guard.getDataDir(), "proguard");
            JSONObject c = readConfig(file);
            if (!c.has("enabled")) c.put("enabled", "false");
            if (!c.has("debug")) c.put("debug", "false");
            boolean any = false;
            for (String key : new String[]{"enabled", "r8", "debug"}) {
                if (input.has(key)) {
                    c.put(key, String.valueOf(input.getBoolean(key)));
                    any = true;
                }
            }
            if (!any) throw new IllegalArgumentException("Pass at least one of: enabled, r8, debug");
            ToolsFiles.writeText(file, c.toString());
            return "Saved. enabled=" + flag(c, "enabled") + ", r8=" + flag(c, "r8") + ", debug=" + flag(c, "debug");
        }
    }

    public static final class GetStringFog extends AiTool {
        @Override
        public String name() {
            return "get_stringfog";
        }

        @Override
        public String description() {
            return "Shows whether StringFog (string encryption at compile time) is enabled.";
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
            return "enabled: " + flag(readConfig(new File(env.guard.getDataDir(), "stringfog")), "enabled");
        }
    }

    public static final class SetStringFog extends AiTool {
        @Override
        public String name() {
            return "set_stringfog";
        }

        @Override
        public String description() {
            return "Turns StringFog on or off.";
        }

        @Override
        public JSONObject schema() throws Exception {
            return props(new String[]{"enabled"}, "enabled", "boolean", "true to enable");
        }

        @Override
        public String summary(JSONObject input) {
            return String.valueOf(input.opt("enabled"));
        }

        @Override
        protected String run(Env env, JSONObject input) throws Exception {
            if (!input.has("enabled")) throw new IllegalArgumentException("Parameter 'enabled' is required");
            File file = new File(env.guard.getDataDir(), "stringfog");
            JSONObject c = readConfig(file);
            c.put("enabled", String.valueOf(input.getBoolean("enabled")));
            ToolsFiles.writeText(file, c.toString());
            return "StringFog enabled=" + c.optString("enabled");
        }
    }

    public static final class ListCustomBlocks extends AiTool {
        @Override
        public String name() {
            return "list_custom_blocks";
        }

        @Override
        public String description() {
            return "Reads the project's custom blocks definition file (read only, same as See Custom Blocks).";
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
            File file = new File(env.guard.getDataDir(), "custom_blocks");
            if (!file.isFile()) return "(this project has no custom blocks)";
            return ToolsFiles.readText(file);
        }
    }

    // ------------------------------------------- custom MainActivity / extra activities

    public static final class UseCustomMain extends AiTool {
        @Override
        public String name() {
            return "use_custom_main";
        }

        @Override
        public String description() {
            return "Replaces the generated main screen with your own code. Sketchware skips generating a screen when a file with its exact name "
                    + "exists at the TOP of java/ (java/MainActivity.java, NOT in a package folder) and for layouts at res/layout/main.xml. "
                    + "mode 'xml' (default) writes res/layout/main.xml and java/MainActivity.java that calls setContentView(R.layout.main). "
                    + "mode 'programmatic' writes only java/MainActivity.java that builds its views in code. Visual-editor blocks of the main "
                    + "screen stop applying. Refuses to overwrite existing files unless overwrite=true.";
        }

        @Override
        public JSONObject schema() throws Exception {
            return props(new String[]{},
                    "mode", "string", "xml (default) or programmatic",
                    "overwrite", "boolean", "Replace existing custom files");
        }

        @Override
        public String summary(JSONObject input) {
            return input.optString("mode", "xml");
        }

        @Override
        protected String run(Env env, JSONObject input) throws Exception {
            String id = env.guard.getScId();
            String pkg = packageName(env.guard);
            String mode = input.optString("mode", "xml").toLowerCase(Locale.ROOT);
            boolean overwrite = input.optBoolean("overwrite", false);
            if (!mode.equals("xml") && !mode.equals("programmatic")) {
                throw new IllegalArgumentException("mode must be 'xml' or 'programmatic'");
            }
            String base = baseClass(id);
            checkFree(env, "java/MainActivity.java", overwrite);
            if (mode.equals("xml")) checkFree(env, "res/layout/main.xml", overwrite);

            StringBuilder out = new StringBuilder();
            if (mode.equals("xml")) {
                out.append(put(env, "res/layout/main.xml", MAIN_LAYOUT)).append('\n');
                out.append(put(env, "java/MainActivity.java", xmlActivitySource(pkg, "MainActivity", base, "main"))).append('\n');
            } else {
                out.append(put(env, "java/MainActivity.java", programmaticSource(pkg, "MainActivity", base))).append('\n');
            }
            out.append("Sketchware will skip its generated MainActivity").append(mode.equals("xml") ? " and main.xml" : "")
                    .append(". Base class: ").append(simple(base)).append(". Run build_project next.");
            return out.toString();
        }
    }

    public static final class CreateActivity extends AiTool {
        @Override
        public String name() {
            return "create_activity";
        }

        @Override
        public String description() {
            return "Creates an additional Activity (java/<Name>Activity.java, optional res/layout/activity_<name>.xml) and registers it in the "
                    + "manifest through app_components.txt. Use start_activity from other code with new Intent(this, <Name>Activity.class). "
                    + "Not for MainActivity (use use_custom_main). Refuses names that a visual screen already generates.";
        }

        @Override
        public JSONObject schema() throws Exception {
            return props(new String[]{"name"},
                    "name", "string", "Class name such as Detail or DetailActivity (letters and digits, starts uppercase)",
                    "with_layout", "boolean", "Also create a layout XML and use it (default true)",
                    "exported", "boolean", "android:exported value (default false)");
        }

        @Override
        public String summary(JSONObject input) {
            return input.optString("name", "");
        }

        @Override
        protected String run(Env env, JSONObject input) throws Exception {
            String id = env.guard.getScId();
            String pkg = packageName(env.guard);
            String raw = requireString(input, "name").trim();
            String cls = raw.endsWith("Activity") ? raw : raw + "Activity";
            if (!CLASS_NAME.matcher(cls).matches() || cls.equals("Activity")) {
                throw new IllegalArgumentException("Invalid activity name: " + raw);
            }
            if (cls.equals("MainActivity")) throw new IllegalArgumentException("MainActivity is handled by use_custom_main");
            boolean withLayout = input.optBoolean("with_layout", true);
            boolean exported = input.optBoolean("exported", false);
            String layoutName = "activity_" + snake(cls.substring(0, cls.length() - "Activity".length()));

            File generated = new File(env.guard.getRootDir(AiProjectGuard.ROOT_GENERATED),
                    "app/src/main/java/" + pkg.replace('.', '/') + "/" + cls + ".java");
            if (generated.isFile() && !fileExists(env, "java/" + cls + ".java")) {
                throw new IllegalArgumentException(cls + " is already generated by a visual screen. Choose another name.");
            }
            checkFree(env, "java/" + cls + ".java", false);
            if (withLayout) checkFree(env, "res/layout/" + layoutName + ".xml", false);

            String base = baseClass(id);
            StringBuilder out = new StringBuilder();
            if (withLayout) {
                out.append(put(env, "res/layout/" + layoutName + ".xml", layoutOnlyXml(cls))).append('\n');
                out.append(put(env, "java/" + cls + ".java", xmlActivitySource(pkg, cls, base, layoutName))).append('\n');
            } else {
                out.append(put(env, "java/" + cls + ".java", programmaticSource(pkg, cls, base))).append('\n');
            }
            String xml = "<activity android:name=\"." + cls + "\" android:exported=\"" + exported + "\" />";
            out.append(appendToAppComponents(env, xml, "android:name=\"." + cls + "\"")).append('\n');
            out.append("Run build_project next.");
            return out.toString();
        }
    }

    static List<AiTool> all() {
        List<AiTool> tools = new ArrayList<>();
        Collections.addAll(tools,
                new GetProjectStructure(), new EnsureProjectStructure(),
                new GetBuiltinLibraries(), new SetBuiltinLibrary(),
                new AppendManifestInjection(),
                new GetAppCompatInjection(), new SetAppCompatInjection(),
                new GetCodeShrinking(), new SetCodeShrinking(),
                new GetStringFog(), new SetStringFog(),
                new ListCustomBlocks(),
                new UseCustomMain(), new CreateActivity());
        return tools;
    }
}
