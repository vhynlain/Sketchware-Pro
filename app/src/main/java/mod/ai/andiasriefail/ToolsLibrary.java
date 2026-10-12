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
            return "Menampilkan library lokal yang terpasang di perangkat dan mana yang dicentang di proyek ini. "
                    + "Library yang tidak terpasang tidak bisa diunduh olehmu; minta pengguna mengunduhnya.";
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
            sb.append("Dicentang di proyek (").append(enabledNames.size()).append("):\n");
            if (enabledNames.isEmpty()) sb.append("  (tidak ada)\n");
            for (String n : enabledNames) {
                sb.append("  ").append(n).append(installed.contains(n) ? "" : "   [FILE LIBRARY HILANG di perangkat]").append('\n');
            }
            sb.append("Terpasang di perangkat (").append(installed.size()).append("):\n");
            if (installed.isEmpty()) sb.append("  (tidak ada)\n");
            List<String> sorted = new ArrayList<>(installed);
            Collections.sort(sorted, String.CASE_INSENSITIVE_ORDER);
            for (String n : sorted) {
                sb.append("  ").append(n).append(enabledNames.contains(n) ? "   [dicentang]" : "").append('\n');
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
            return "Memeriksa library yang dicentang: apakah file-nya ada, dan perkiraan apakah kode proyek memakainya "
                    + "(heuristik: mencari nama package di kode Java dan hasil generate). Hasilnya perkiraan, bukan kepastian.";
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
            if (enabled.isEmpty()) return "Tidak ada library yang dicentang di proyek ini.";
            String sources = collectSources(env.guard);

            StringBuilder sb = new StringBuilder();
            for (HashMap<String, Object> m : enabled) {
                String name = nameOf(m);
                sb.append(name).append(":\n");
                boolean present = installed.contains(name);
                sb.append("  file di perangkat: ").append(present ? "ada" : "HILANG (tercentang tapi tidak terpasang)").append('\n');
                if (present) {
                    sb.append("  isi: ");
                    List<String> parts = new ArrayList<>();
                    if (pathExists(m, "jarPath")) parts.add("classes.jar");
                    if (pathExists(m, "dexPath")) parts.add("classes.dex");
                    if (pathExists(m, "resPath")) parts.add("res");
                    if (pathExists(m, "assetsPath")) parts.add("assets");
                    sb.append(parts.isEmpty() ? "TIDAK ADA jar/dex/res (library kosong)" : String.join(", ", parts)).append('\n');
                }
                Object pkg = m.get("packageName");
                if (pkg != null && !String.valueOf(pkg).trim().isEmpty()) {
                    String p = String.valueOf(pkg).trim();
                    boolean used = sources.contains(p);
                    sb.append("  package ").append(p).append(": ")
                            .append(used ? "ADA referensi di kode" : "tidak ditemukan referensi di kode").append('\n');
                } else {
                    sb.append("  package tidak diketahui, tidak bisa diperiksa pemakaiannya\n");
                }
            }
            sb.append("\nCatatan: hasil bersifat perkiraan. Library AndroidX bawaan Sketchware dan library lain mungkin dipakai tanpa import eksplisit.");
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
            return "Mencentang (enabled true) atau membatalkan centang (enabled false) satu library lokal di proyek ini. "
                    + "Mencentang hanya bisa untuk library yang sudah terpasang di perangkat. Tidak mengunduh dan tidak menghapus file library.";
        }

        @Override
        public JSONObject schema() throws Exception {
            return props(new String[]{"name", "enabled"},
                    "name", "string", "Nama library persis seperti di list_local_libraries.",
                    "enabled", "boolean", "true untuk mencentang, false untuk membatalkan.");
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
                if (existing != null) return name + " sudah dicentang.";
                if (!installedNames().contains(name)) {
                    return "Error: library '" + name + "' belum terpasang di perangkat. Minta pengguna mengunduhnya lewat menu Local library, lalu coba lagi.";
                }
                list.add(LocalLibrariesUtil.createLibraryMap(name, null));
            } else {
                if (existing == null) return name + " memang tidak dicentang.";
                list.remove(existing);
            }
            LocalLibrariesUtil.rewriteLocalLibFile(id, new Gson().toJson(list));
            return (enable ? "Dicentang: " : "Centang dibatalkan: ") + name;
        }
    }

    static List<AiTool> all() {
        List<AiTool> tools = new ArrayList<>();
        Collections.addAll(tools, new ListLocalLibraries(), new CheckLibraryUsage(), new SetLocalLibraryEnabled());
        return tools;
    }
}
