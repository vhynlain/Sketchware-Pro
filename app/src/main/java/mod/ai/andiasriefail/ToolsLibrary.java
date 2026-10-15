package mod.ai.andiasriefail;

import com.google.gson.Gson;

import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import dev.aldi.sayuti.editor.manage.LocalLibrariesUtil;
import dev.aldi.sayuti.editor.manage.LocalLibrary;

/**
 * Local library tools. The AI can list, check, enable and disable libraries, but it never
 * downloads one: downloading is slow and the user does it from the Local library screen.
 */
public final class ToolsLibrary {

    private ToolsLibrary() {
    }

    private static Set<String> installedNames() {
        Set<String> names = new HashSet<>();
        for (LocalLibrary lib : LocalLibrariesUtil.getAllLocalLibraries()) names.add(lib.getName());
        return names;
    }

    private static String nameOf(HashMap<String, Object> map) {
        Object n = map.get("name");
        return n == null ? "" : String.valueOf(n);
    }

    private static boolean pathExists(HashMap<String, Object> map, String key) {
        Object p = map.get(key);
        return p != null && new File(String.valueOf(p)).exists();
    }

    /**
     * Collects the text of the project's own Java sources and (if present) the generated sources.
     */
    private static String collectSources(AiProjectGuard guard) {
        StringBuilder sb = new StringBuilder();
        try {
            collectInto(sb, guard.getRootDir(AiProjectGuard.ROOT_JAVA), 0);
            collectInto(sb, guard.getRootDir(AiProjectGuard.ROOT_GENERATED), 0);
        } catch (Throwable ignored) {
        }
        return sb.toString();
    }

    private static void collectInto(StringBuilder sb, File dir, int depth) {
        if (sb.length() > 3_000_000 || depth > 12) return;
        File[] children = dir.listFiles();
        if (children == null) return;
        for (File f : children) {
            if (f.isDirectory()) {
                collectInto(sb, f, depth + 1);
            } else if ((f.getName().endsWith(".java") || f.getName().endsWith(".xml") || f.getName().endsWith(".kt"))
                    && f.length() < 500_000) {
                try {
                    sb.append(ToolsFiles.readText(f)).append('\n');
                } catch (Throwable ignored) {
                }
            }
        }
    }

    public static final class ListLocalLibraries extends AiTool {
        @Override
        public String name() {
            return "list_local_libraries";
        }

        @Override
        public String description() {
            return "Lists the local libraries installed on the device and which of them are ticked in this project. "
                    + "A library that is not installed cannot be downloaded by you; ask the user to download it.";
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
            Set<String> installed = installedNames();
            List<HashMap<String, Object>> enabled = LocalLibrariesUtil.getLocalLibraries(env.guard.getScId());
            Set<String> enabledNames = new HashSet<>();
            for (HashMap<String, Object> m : enabled) enabledNames.add(nameOf(m));

            StringBuilder sb = new StringBuilder();
            sb.append("Enabled in project (").append(enabledNames.size()).append("):\n");
            if (enabledNames.isEmpty()) sb.append("  (none)\n");
            for (String n : enabledNames) {
                sb.append("  ").append(n).append(installed.contains(n) ? "" : "   [LIBRARY FILE MISSING on the device]").append('\n');
            }
            sb.append("Installed on the device (").append(installed.size()).append("):\n");
            if (installed.isEmpty()) sb.append("  (none)\n");
            List<String> sorted = new ArrayList<>(installed);
            Collections.sort(sorted, String.CASE_INSENSITIVE_ORDER);
            for (String n : sorted) {
                sb.append("  ").append(n).append(enabledNames.contains(n) ? "   [enabled]" : "").append('\n');
            }
            return sb.toString();
        }
    }

    public static final class CheckLibraryUsage extends AiTool {
        @Override
        public String name() {
            return "check_library_usage";
        }

        @Override
        public String description() {
            return "Checks the enabled libraries: whether their files exist and an estimate of whether the project code uses them "
                    + "(heuristic: looks for the package name in the Java code and the generated code). The result is an estimate, not proof.";
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
            Set<String> installed = installedNames();
            List<HashMap<String, Object>> enabled = LocalLibrariesUtil.getLocalLibraries(env.guard.getScId());
            if (enabled.isEmpty()) return "No library is enabled in this project.";
            String sources = collectSources(env.guard);

            StringBuilder sb = new StringBuilder();
            for (HashMap<String, Object> m : enabled) {
                String name = nameOf(m);
                sb.append(name).append(":\n");
                boolean present = installed.contains(name);
                sb.append("  file on the device: ").append(present ? "ada" : "MISSING (enabled but not installed)").append('\n');
                if (present) {
                    sb.append("  content: ");
                    List<String> parts = new ArrayList<>();
                    if (pathExists(m, "jarPath")) parts.add("classes.jar");
                    if (pathExists(m, "dexPath")) parts.add("classes.dex");
                    if (pathExists(m, "resPath")) parts.add("res");
                    if (pathExists(m, "assetsPath")) parts.add("assets");
                    sb.append(parts.isEmpty() ? "NO jar/dex/res (empty library)" : String.join(", ", parts)).append('\n');
                }
                Object pkg = m.get("packageName");
                if (pkg != null && !String.valueOf(pkg).trim().isEmpty()) {
                    String p = String.valueOf(pkg).trim();
                    boolean used = sources.contains(p);
                    sb.append("  package ").append(p).append(": ")
                            .append(used ? "references FOUND in code" : "no references found in code").append('\n');
                } else {
                    sb.append("  package unknown, usage cannot be checked\n");
                }
            }
            sb.append("\nNote: the result is an estimate. Sketchware's built-in AndroidX libraries and other libraries may be used without an explicit import.");
            return sb.toString();
        }
    }

    public static final class SetLocalLibraryEnabled extends AiTool {
        @Override
        public String name() {
            return "set_local_library_enabled";
        }

        @Override
        public String description() {
            return "Enables (enabled true) or disables (enabled false) one local library in this project. "
                    + "Enabling only works for libraries that are already installed on the device. It does not download or delete library files.";
        }

        @Override
        public JSONObject schema() throws Exception {
            return props(new String[]{"name", "enabled"},
                    "name", "string", "Library name exactly as in list_local_libraries.",
                    "enabled", "boolean", "true to enable, false to disable.");
        }

        @Override
        public String summary(JSONObject input) {
            return input.optString("name", "") + " = " + input.optBoolean("enabled");
        }

        @Override
        protected String run(Env env, JSONObject input) throws Exception {
            String name = requireString(input, "name").trim();
            if (!input.has("enabled")) throw new IllegalArgumentException("Parameter 'enabled' wajib diisi");
            boolean enable = input.getBoolean("enabled");
            String id = env.guard.getScId();

            List<HashMap<String, Object>> list = LocalLibrariesUtil.getLocalLibraries(id);
            HashMap<String, Object> existing = null;
            for (HashMap<String, Object> m : list) {
                if (nameOf(m).equals(name)) {
                    existing = m;
                    break;
                }
            }

            if (enable) {
                if (existing != null) return name + " is already enabled.";
                if (!installedNames().contains(name)) {
                    return "Error: library '" + name + "' is not installed on the device. Ask the user to download it in the Local library menu, then try again.";
                }
                list.add(LocalLibrariesUtil.createLibraryMap(name, null));
            } else {
                if (existing == null) return name + " was not enabled.";
                list.remove(existing);
            }
            LocalLibrariesUtil.rewriteLocalLibFile(id, new Gson().toJson(list));
            return (enable ? "Enabled: " : "Disabled: ") + name;
        }
    }

    static List<AiTool> all() {
        List<AiTool> tools = new ArrayList<>();
        Collections.addAll(tools, new ListLocalLibraries(), new CheckLibraryUsage(), new SetLocalLibraryEnabled());
        return tools;
    }
}
