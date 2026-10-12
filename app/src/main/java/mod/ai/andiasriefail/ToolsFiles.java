package mod.ai.andiasriefail;

import org.json.JSONObject;
import org.w3c.dom.Document;
import org.xml.sax.InputSource;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

/**
 * File tools: list, read, write, replace, rename, delete, make folders.
 * All paths are virtual paths checked by {@link AiProjectGuard}.
 */
public final class ToolsFiles {

    private ToolsFiles() {
    }

    private static final long MAX_READ_BYTES = 2L * 1024 * 1024;
    private static final int MAX_LIST_ENTRIES = 300;

    // ------------------------------------------------------------------ helpers

    static String readText(File file) throws IOException {
        if (file.length() > MAX_READ_BYTES) {
            throw new IOException("File terlalu besar (" + file.length() + " byte, batas 2 MB)");
        }
        try (InputStream in = new FileInputStream(file);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    static void writeText(File file, String content) throws IOException {
        File parent = file.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("Gagal membuat folder " + parent.getName());
        }
        try (OutputStream out = new FileOutputStream(file)) {
            out.write(content.getBytes(StandardCharsets.UTF_8));
        }
    }

    static void validateXml(String content) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(false);
        DocumentBuilder builder = factory.newDocumentBuilder();
        builder.setErrorHandler(null);
        try {
            Document ignored = builder.parse(new InputSource(new StringReader(content)));
        } catch (Exception e) {
            throw new IllegalArgumentException("XML tidak valid, file TIDAK disimpan: " + e.getMessage());
        }
    }

    static void validateJson(String content) {
        try {
            String trimmed = content.trim();
            if (trimmed.startsWith("[")) new org.json.JSONArray(trimmed);
            else new JSONObject(trimmed);
        } catch (Exception e) {
            throw new IllegalArgumentException("JSON tidak valid, file TIDAK disimpan: " + e.getMessage());
        }
    }

    /**
     * Validates content for the target (XML and JSON must be well-formed), then size-checks it.
     */
    static void checkContent(AiProjectGuard.Resolved r, String content) throws Exception {
        if (content.getBytes(StandardCharsets.UTF_8).length > AiProjectGuard.MAX_WRITE_BYTES) {
            throw new IllegalArgumentException("Isi terlalu besar (maksimal " + AiProjectGuard.MAX_WRITE_BYTES + " byte)");
        }
        String name = r.file.getName().toLowerCase();
        if (name.endsWith(".xml")) validateXml(content);
        if (AiProjectGuard.mustBeJson(r)) validateJson(content);
    }

    static int deleteRecursive(File file) {
        int count = 0;
        File[] children = file.isDirectory() ? file.listFiles() : null;
        if (children != null) {
            for (File child : children) count += deleteRecursive(child);
        }
        if (file.delete()) count++;
        return count;
    }

    private static String describe(File f) {
        return f.isDirectory() ? "/" : "  (" + f.length() + " B)";
    }

    private static void listInto(List<String> out, File dir, String virtualPrefix, int depth, int maxDepth) {
        File[] children = dir.listFiles();
        if (children == null) return;
        Arrays.sort(children, (a, b) -> {
            if (a.isDirectory() != b.isDirectory()) return a.isDirectory() ? -1 : 1;
            return a.getName().compareToIgnoreCase(b.getName());
        });
        for (File child : children) {
            if (out.size() >= MAX_LIST_ENTRIES) {
                out.add("...[daftar dipotong di " + MAX_LIST_ENTRIES + " entri]");
                return;
            }
            out.add(virtualPrefix + child.getName() + describe(child));
            if (child.isDirectory() && depth < maxDepth) {
                listInto(out, child, virtualPrefix + child.getName() + "/", depth + 1, maxDepth);
            }
        }
    }

    // -------------------------------------------------------------------- tools

    public static final class ListFiles extends AiTool {
        @Override
        public String name() {
            return "list_files";
        }

        @Override
        public String description() {
            return "Menampilkan isi folder proyek. Tanpa path: menampilkan folder akar (java/, res/, assets/, config/, generated/). "
                    + "Gunakan 'config' untuk melihat file konfigurasi yang boleh diakses. Maksimal 300 entri.";
        }

        @Override
        public JSONObject schema() throws Exception {
            return props(new String[]{},
                    "path", "string", "Jalur virtual folder, misalnya res/layout. Kosongkan untuk folder akar.",
                    "recursive", "boolean", "true untuk menampilkan isi subfolder (maksimal kedalaman 3).");
        }

        @Override
        public String summary(JSONObject input) {
            String p = input.optString("path", "");
            return p.isEmpty() ? "(akar)" : p;
        }

        @Override
        protected String run(Env env, JSONObject input) throws Exception {
            String path = input.optString("path", "").trim();
            while (path.startsWith("/")) path = path.substring(1);
            while (path.endsWith("/")) path = path.substring(0, path.length() - 1);
            List<String> lines = new ArrayList<>();

            if (path.isEmpty()) {
                lines.add("java/       file Java kustom (bisa ditulis)");
                lines.add("res/        resource: layout, values, drawable, dan sebagainya (bisa ditulis)");
                lines.add("assets/     folder assets (bisa ditulis)");
                lines.add("config/     file konfigurasi proyek yang diizinkan (sebagian bisa ditulis)");
                lines.add("generated/  hasil generate build terakhir (hanya baca)");
                return String.join("\n", lines);
            }

            if (path.equals("config")) {
                for (String entry : AiProjectGuard.CONFIG_LISTING) {
                    File f = new File(env.guard.getDataDir(), entry);
                    int access = AiProjectGuard.configAccess(entry.endsWith("/") ? entry.substring(0, entry.length() - 1) : entry);
                    lines.add("config/" + entry + (f.exists() ? "" : "  (belum ada)") + (access == 1 ? "  [hanya baca]" : ""));
                }
                return String.join("\n", lines);
            }
            if (path.equals("config/files")) {
                return "config/files/broadcast/\nconfig/files/service/\nconfig/files/native_libs/\nconfig/files/kt_plugins/\nconfig/files/library/";
            }

            AiProjectGuard.Resolved r = env.guard.resolve(path, false);
            if (!r.file.exists()) {
                throw new IOException("Folder tidak ada: " + r.virtualPath());
            }
            if (!r.file.isDirectory()) {
                return r.virtualPath() + describe(r.file);
            }
            boolean recursive = input.optBoolean("recursive", false);
            listInto(lines, r.file, r.virtualPath().endsWith("/") ? r.virtualPath() : r.virtualPath() + "/", 1, recursive ? 3 : 1);
            if (lines.isEmpty()) return "(folder kosong) " + r.virtualPath();
            return String.join("\n", lines);
        }
    }

    public static final class ReadFile extends AiTool {
        @Override
        public String name() {
            return "read_file";
        }

        @Override
        public String description() {
            return "Membaca file teks. Setiap baris diberi nomor di depan (format 'nomor<tab>teks'); nomor bukan bagian file. "
                    + "Gunakan start_line dan end_line untuk file panjang.";
        }

        @Override
        public JSONObject schema() throws Exception {
            return props(new String[]{"path"},
                    "path", "string", "Jalur virtual file, misalnya java/Helper.java atau res/values/styles.xml",
                    "start_line", "integer", "Baris awal (mulai dari 1). Opsional.",
                    "end_line", "integer", "Baris akhir (inklusif). Opsional.");
        }

        @Override
        protected String run(Env env, JSONObject input) throws Exception {
            AiProjectGuard.Resolved r = env.guard.resolve(requireString(input, "path"), false);
            if (!r.file.exists()) throw new IOException("File tidak ada: " + r.virtualPath());
            if (r.file.isDirectory()) throw new IOException(r.virtualPath() + " adalah folder, pakai list_files");
            if (!AiProjectGuard.isTextFile(r)) throw new IOException("File biner tidak bisa dibaca sebagai teks");

            String text = readText(r.file);
            String[] lines = text.split("\n", -1);
            int start = Math.max(1, input.optInt("start_line", 1));
            int end = Math.min(lines.length, input.optInt("end_line", lines.length));
            if (start > end) return "(tidak ada baris pada rentang itu; file punya " + lines.length + " baris)";

            StringBuilder sb = new StringBuilder();
            sb.append(r.virtualPath()).append(" (").append(lines.length).append(" baris)\n");
            for (int i = start; i <= end; i++) {
                sb.append(i).append('\t').append(lines[i - 1]).append('\n');
            }
            return sb.toString();
        }
    }

    public static final class WriteFile extends AiTool {
        @Override
        public String name() {
            return "write_file";
        }

        @Override
        public String description() {
            return "Membuat file teks baru atau menimpa file yang ada dengan isi lengkap. Folder induk dibuat otomatis. "
                    + "File XML dan JSON diperiksa validitasnya dan ditolak jika rusak. Untuk perubahan kecil pakai str_replace.";
        }

        @Override
        public JSONObject schema() throws Exception {
            return props(new String[]{"path", "content"},
                    "path", "string", "Jalur virtual tujuan, misalnya res/layout/item_note.xml",
                    "content", "string", "Isi file lengkap (UTF-8), tanpa nomor baris.");
        }

        @Override
        protected String run(Env env, JSONObject input) throws Exception {
            AiProjectGuard.Resolved r = env.guard.resolve(requireString(input, "path"), true);
            String content = input.optString("content", "");
            if (r.file.isDirectory()) throw new IOException(r.virtualPath() + " adalah folder");
            if (!AiProjectGuard.isTextFile(r)) {
                throw new IOException("Tipe file ini tidak boleh ditulis sebagai teks: " + r.file.getName());
            }
            checkContent(r, content);
            boolean existed = r.file.exists();
            writeText(r.file, content);
            int lines = content.isEmpty() ? 0 : content.split("\n", -1).length;
            return (existed ? "Menimpa " : "Membuat ") + r.virtualPath() + " (" + lines + " baris)";
        }
    }

    public static final class StrReplace extends AiTool {
        @Override
        public String name() {
            return "str_replace";
        }

        @Override
        public String description() {
            return "Mengganti teks persis di dalam file. old_str harus cocok persis (tanpa nomor baris) dan unik, "
                    + "kecuali replace_all true. Gagal jika tidak ditemukan atau tidak unik.";
        }

        @Override
        public JSONObject schema() throws Exception {
            return props(new String[]{"path", "old_str", "new_str"},
                    "path", "string", "Jalur virtual file.",
                    "old_str", "string", "Teks lama persis seperti di file.",
                    "new_str", "string", "Teks pengganti (boleh kosong untuk menghapus).",
                    "replace_all", "boolean", "true untuk mengganti semua kemunculan.");
        }

        @Override
        protected String run(Env env, JSONObject input) throws Exception {
            AiProjectGuard.Resolved r = env.guard.resolve(requireString(input, "path"), true);
            String oldStr = requireString(input, "old_str");
            String newStr = input.optString("new_str", "");
            boolean all = input.optBoolean("replace_all", false);
            if (!r.file.isFile()) throw new IOException("File tidak ada: " + r.virtualPath());
            if (!AiProjectGuard.isTextFile(r)) throw new IOException("Bukan file teks");

            String text = readText(r.file);
            int count = 0;
            int from = 0;
            while ((from = text.indexOf(oldStr, from)) != -1) {
                count++;
                from += oldStr.length();
            }
            if (count == 0) {
                throw new IllegalArgumentException("old_str tidak ditemukan di " + r.virtualPath()
                        + ". Baca ulang file dengan read_file dan salin teks persis (tanpa nomor baris).");
            }
            if (count > 1 && !all) {
                throw new IllegalArgumentException("old_str ditemukan " + count + " kali. Tambahkan konteks agar unik, atau set replace_all true.");
            }
            String updated;
            if (all) {
                updated = text.replace(oldStr, newStr);
            } else {
                int at = text.indexOf(oldStr);
                updated = text.substring(0, at) + newStr + text.substring(at + oldStr.length());
            }
            checkContent(r, updated);
            writeText(r.file, updated);
            return count + " penggantian di " + r.virtualPath();
        }
    }

    public static final class RenameFile extends AiTool {
        @Override
        public String name() {
            return "rename_file";
        }

        @Override
        public String description() {
            return "Mengganti nama atau memindahkan file/folder di dalam proyek. Tujuan tidak boleh sudah ada. "
                    + "Setelah mengganti nama file Java, perbarui referensinya (nama kelas, import, manifest).";
        }

        @Override
        public JSONObject schema() throws Exception {
            return props(new String[]{"from", "to"},
                    "from", "string", "Jalur virtual asal.",
                    "to", "string", "Jalur virtual tujuan.");
        }

        @Override
        public String summary(JSONObject input) {
            return input.optString("from", "") + " -> " + input.optString("to", "");
        }

        @Override
        protected String run(Env env, JSONObject input) throws Exception {
            AiProjectGuard.Resolved from = env.guard.resolve(requireString(input, "from"), true);
            AiProjectGuard.Resolved to = env.guard.resolve(requireString(input, "to"), true);
            if (!from.file.exists()) throw new IOException("Asal tidak ada: " + from.virtualPath());
            if (to.file.exists()) throw new IOException("Tujuan sudah ada: " + to.virtualPath());
            File parent = to.file.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                throw new IOException("Gagal membuat folder tujuan");
            }
            if (!from.file.renameTo(to.file)) {
                throw new IOException("Gagal mengganti nama " + from.virtualPath());
            }
            return "Dipindahkan: " + from.virtualPath() + " -> " + to.virtualPath();
        }
    }

    public static final class DeleteFile extends AiTool {
        @Override
        public String name() {
            return "delete_file";
        }

        @Override
        public String description() {
            return "Menghapus file atau folder (beserta isinya) di dalam proyek. SELALU meminta izin pengguna. "
                    + "Jelaskan alasannya sebelum memanggil. Cadangan proyek ada di awal sesi.";
        }

        @Override
        public JSONObject schema() throws Exception {
            return props(new String[]{"path"}, "path", "string", "Jalur virtual yang dihapus.");
        }

        @Override
        public boolean needsConfirmation(Env env, JSONObject input) {
            return true;
        }

        @Override
        public String confirmationText(JSONObject input) {
            return "Izinkan asisten menghapus:\n" + input.optString("path", "?");
        }

        @Override
        protected String run(Env env, JSONObject input) throws Exception {
            AiProjectGuard.Resolved r = env.guard.resolve(requireString(input, "path"), true);
            if (!r.file.exists()) throw new IOException("Tidak ada: " + r.virtualPath());
            int removed = deleteRecursive(r.file);
            if (r.file.exists()) throw new IOException("Gagal menghapus " + r.virtualPath());
            return "Dihapus: " + r.virtualPath() + " (" + removed + " item)";
        }
    }

    public static final class MakeDir extends AiTool {
        @Override
        public String name() {
            return "make_dir";
        }

        @Override
        public String description() {
            return "Membuat folder (termasuk folder induk), misalnya res/layout, res/drawable, res/values-v31, res/menu.";
        }

        @Override
        public JSONObject schema() throws Exception {
            return props(new String[]{"path"}, "path", "string", "Jalur virtual folder yang dibuat.");
        }

        @Override
        protected String run(Env env, JSONObject input) throws Exception {
            AiProjectGuard.Resolved r = env.guard.resolve(requireString(input, "path"), true);
            if (r.file.isDirectory()) return "Folder sudah ada: " + r.virtualPath();
            if (r.file.exists()) throw new IOException("Sudah ada file dengan nama itu: " + r.virtualPath());
            if (!r.file.mkdirs()) throw new IOException("Gagal membuat folder " + r.virtualPath());
            return "Folder dibuat: " + r.virtualPath();
        }
    }

    static List<AiTool> all() {
        List<AiTool> tools = new ArrayList<>();
        Collections.addAll(tools, new ListFiles(), new ReadFile(), new WriteFile(), new StrReplace(),
                new RenameFile(), new DeleteFile(), new MakeDir());
        return tools;
    }
}
