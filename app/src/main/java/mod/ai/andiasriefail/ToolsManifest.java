package mod.ai.andiasriefail;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import a.a.a.wq;

/**
 * Manifest and permission tools.
 * Sketchware generates the manifest at build time, so changes go through its injection files and
 * the permission list; the generated manifest can only be read.
 */
public final class ToolsManifest {

    private ToolsManifest() {
    }

    private static final List<String> INJECTION_FILES = Arrays.asList(
            "attributes.json", "activities_components.json", "activity_launcher.txt", "app_components.txt");
    private static final Pattern PERMISSION = Pattern.compile("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+$");

    static File permissionFile(AiProjectGuard guard) {
        return new File(guard.getDataDir(), "permission");
    }

    static List<String> readPermissions(AiProjectGuard guard) throws Exception {
        File file = permissionFile(guard);
        List<String> result = new ArrayList<>();
        if (!file.isFile()) return result;
        String text = ToolsFiles.readText(file).trim();
        if (text.isEmpty()) return result;
        JSONArray array = new JSONArray(text);
        for (int i = 0; i < array.length(); i++) result.add(array.getString(i));
        return result;
    }

    static void writePermissions(AiProjectGuard guard, List<String> permissions) throws Exception {
        JSONArray array = new JSONArray();
        for (String p : permissions) array.put(p);
        ToolsFiles.writeText(permissionFile(guard), array.toString());
    }

    private static String normalizePermission(String raw) {
        String p = raw.trim();
        if (!p.contains(".")) p = "android.permission." + p.toUpperCase(Locale.ROOT);
        if (!PERMISSION.matcher(p).matches()) {
            throw new IllegalArgumentException("Nama izin tidak valid: " + raw);
        }
        return p;
    }

    private static File injectionFile(AiProjectGuard guard, String name) {
        if (!INJECTION_FILES.contains(name)) {
            throw new IllegalArgumentException("File injeksi harus salah satu: " + INJECTION_FILES);
        }
        return new File(guard.getInjectionDir(), name);
    }

    // -------------------------------------------------------------------- tools

    public static final class GetGeneratedManifest extends AiTool {
        @Override
        public String name() {
            return "get_generated_manifest";
        }

        @Override
        public String description() {
            return "Membaca AndroidManifest.xml hasil generate build terakhir (hanya baca). Jika belum ada, jalankan build_project dulu.";
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
            File manifest = new File(wq.d(env.guard.getScId()), "app/src/main/AndroidManifest.xml");
            if (!manifest.isFile()) {
                return "Belum ada manifest hasil generate. Jalankan build_project dulu, lalu panggil tool ini lagi.";
            }
            return ToolsFiles.readText(manifest);
        }
    }

    public static final class ReadManifestInjection extends AiTool {
        @Override
        public String name() {
            return "read_manifest_injection";
        }

        @Override
        public String description() {
            return "Membaca file kustomisasi manifest. Pilihan: attributes.json, activities_components.json, "
                    + "activity_launcher.txt, app_components.txt. Jika file belum ada, hasilnya kosong.";
        }

        @Override
        public JSONObject schema() throws Exception {
            return props(new String[]{"file"}, "file", "string", "Nama file injeksi.");
        }

        @Override
        public String summary(JSONObject input) {
            return input.optString("file", "");
        }

        @Override
        protected String run(Env env, JSONObject input) throws Exception {
            File file = injectionFile(env.guard, requireString(input, "file"));
            if (!file.isFile()) return "(file belum ada: " + file.getName() + ")";
            return ToolsFiles.readText(file);
        }
    }

    public static final class WriteManifestInjection extends AiTool {
        @Override
        public String name() {
            return "write_manifest_injection";
        }

        @Override
        public String description() {
            return "Menulis file kustomisasi manifest (isi lengkap, menimpa). Baca dulu dengan read_manifest_injection, "
                    + "pertahankan entri yang sudah ada, tiru format entrinya. File .json harus array JSON berisi objek.";
        }

        @Override
        public JSONObject schema() throws Exception {
            return props(new String[]{"file", "content"},
                    "file", "string", "attributes.json, activities_components.json, activity_launcher.txt, atau app_components.txt",
                    "content", "string", "Isi lengkap file.");
        }

        @Override
        public String summary(JSONObject input) {
            return input.optString("file", "");
        }

        @Override
        protected String run(Env env, JSONObject input) throws Exception {
            String name = requireString(input, "file");
            File file = injectionFile(env.guard, name);
            String content = input.optString("content", "");
            if (content.length() > AiProjectGuard.MAX_WRITE_BYTES) throw new IllegalArgumentException("Isi terlalu besar");
            if (name.endsWith(".json")) {
                String trimmed = content.trim();
                JSONArray array;
                try {
                    array = new JSONArray(trimmed.isEmpty() ? "[]" : trimmed);
                } catch (Exception e) {
                    throw new IllegalArgumentException("JSON tidak valid, file TIDAK disimpan: " + e.getMessage());
                }
                for (int i = 0; i < array.length(); i++) {
                    if (!(array.get(i) instanceof JSONObject)) {
                        throw new IllegalArgumentException("Setiap elemen array harus berupa objek (elemen " + i + ")");
                    }
                }
                content = trimmed.isEmpty() ? "[]" : trimmed;
            }
            ToolsFiles.writeText(file, content);
            return "Ditulis: " + name + ". Jalankan build_project lalu get_generated_manifest untuk memeriksa hasilnya.";
        }
    }

    public static final class ListPermissions extends AiTool {
        @Override
        public String name() {
            return "list_permissions";
        }

        @Override
        public String description() {
            return "Menampilkan izin (uses-permission) yang dicentang di proyek.";
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
            List<String> list = readPermissions(env.guard);
            if (list.isEmpty()) return "(belum ada izin)";
            return String.join("\n", list);
        }
    }

    public static final class AddPermission extends AiTool {
        @Override
        public String name() {
            return "add_permission";
        }

        @Override
        public String description() {
            return "Menambah izin ke proyek, misalnya android.permission.INTERNET. Jika hanya 'CAMERA' diberikan, "
                    + "awalan android.permission. ditambahkan otomatis. Izin yang sudah ada tidak digandakan.";
        }

        @Override
        public JSONObject schema() throws Exception {
            return props(new String[]{"permission"}, "permission", "string", "Nama izin.");
        }

        @Override
        public String summary(JSONObject input) {
            return input.optString("permission", "");
        }

        @Override
        protected String run(Env env, JSONObject input) throws Exception {
            String p = normalizePermission(requireString(input, "permission"));
            Set<String> set = new LinkedHashSet<>(readPermissions(env.guard));
            if (!set.add(p)) return "Sudah ada: " + p;
            writePermissions(env.guard, new ArrayList<>(set));
            return "Ditambahkan: " + p;
        }
    }

    public static final class RemovePermission extends AiTool {
        @Override
        public String name() {
            return "remove_permission";
        }

        @Override
        public String description() {
            return "Menghapus izin dari proyek.";
        }

        @Override
        public JSONObject schema() throws Exception {
            return props(new String[]{"permission"}, "permission", "string", "Nama izin.");
        }

        @Override
        public String summary(JSONObject input) {
            return input.optString("permission", "");
        }

        @Override
        protected String run(Env env, JSONObject input) throws Exception {
            String p = normalizePermission(requireString(input, "permission"));
            List<String> list = readPermissions(env.guard);
            if (!list.remove(p)) throw new IOException("Izin tidak ada di proyek: " + p);
            writePermissions(env.guard, list);
            return "Dihapus: " + p;
        }
    }

    static List<AiTool> all() {
        List<AiTool> tools = new ArrayList<>();
        Collections.addAll(tools, new GetGeneratedManifest(), new ReadManifestInjection(), new WriteManifestInjection(),
                new ListPermissions(), new AddPermission(), new RemovePermission());
        return tools;
    }
}
