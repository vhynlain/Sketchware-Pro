package mod.ai.andiasriefail;

import android.os.Environment;
import android.util.Log;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import a.a.a.wq;

/**
 * Sandbox for ONE Sketchware project. Every path the AI uses goes through {@link #resolve}.
 * The project id is fixed when this object is created, so no tool can reach another project.
 *
 * Virtual roots:
 * java/      -> data/ID/files/java      (writable)
 * res/       -> data/ID/files/resource  (writable)
 * assets/    -> data/ID/files/assets    (writable)
 * generated/ -> mysc/ID                 (read-only)
 * config/    -> data/ID, but ONLY a whitelist of config files and folders (see configAccess)
 */
public final class AiProjectGuard {

    public static final String ROOT_JAVA = "java";
    public static final String ROOT_RES = "res";
    public static final String ROOT_ASSETS = "assets";
    public static final String ROOT_GENERATED = "generated";
    public static final String ROOT_CONFIG = "config";

    public static final int MAX_WRITE_BYTES = 400_000;
    private static final String TAG = "AiProjectGuard";
    private static final Pattern ID_PATTERN = Pattern.compile("^\\d{1,12}$");
    private static final String[] TEXT_EXTENSIONS = {
            ".java", ".kt", ".xml", ".txt", ".json", ".md", ".pro", ".properties", ".html", ".css", ".js", ".csv", ".gradle"
    };

    private final String scId;
    private final File dataDir;

    public AiProjectGuard(String scId) throws Exception {
        if (scId == null || !ID_PATTERN.matcher(scId).matches()) {
            throw new IllegalArgumentException("ID proyek tidak valid");
        }
        this.scId = scId;
        this.dataDir = new File(Environment.getExternalStorageDirectory(), ".sketchware/data/" + scId);
        if (!dataDir.isDirectory()) {
            throw new IOException("Proyek " + scId + " tidak ditemukan");
        }
    }

    public String getScId() {
        return scId;
    }

    public File getDataDir() {
        return dataDir;
    }

    public File getInjectionDir() {
        return new File(dataDir, "Injection/androidmanifest");
    }

    public File getRootDir(String rootName) throws Exception {
        switch (rootName) {
            case ROOT_JAVA:
                return new File(dataDir, "files/java");
            case ROOT_RES:
                return new File(dataDir, "files/resource");
            case ROOT_ASSETS:
                return new File(dataDir, "files/assets");
            case ROOT_GENERATED:
                return new File(wq.d(scId));
            case ROOT_CONFIG:
                return dataDir;
            default:
                throw new IllegalArgumentException("Awalan jalur tidak dikenal: " + rootName
                        + ". Gunakan java/, res/, assets/, config/, atau generated/");
        }
    }

    public static String[] rootNames() {
        return new String[]{ROOT_JAVA, ROOT_RES, ROOT_ASSETS, ROOT_CONFIG, ROOT_GENERATED};
    }

    public boolean isWritableRoot(String rootName) {
        return !ROOT_GENERATED.equals(rootName);
    }

    /**
     * Result of resolving a virtual path.
     */
    public static final class Resolved {
        public final String root;
        public final String relative;
        public final File file;
        public final boolean isRoot;

        Resolved(String root, String relative, File file, boolean isRoot) {
            this.root = root;
            this.relative = relative;
            this.file = file;
            this.isRoot = isRoot;
        }

        public String virtualPath() {
            return isRoot ? root + "/" : root + "/" + relative;
        }
    }

    /**
     * @param virtualPath path such as "res/layout/main.xml"
     * @param forWrite    true when the caller wants to modify the path
     */
    public Resolved resolve(String virtualPath, boolean forWrite) throws Exception {
        if (virtualPath == null) throw new IllegalArgumentException("Jalur kosong");
        String p = virtualPath.trim().replace('\\', '/');
        if (p.indexOf('\0') >= 0) throw new IllegalArgumentException("Jalur tidak valid");
        while (p.startsWith("/")) p = p.substring(1);
        if (p.isEmpty()) throw new IllegalArgumentException("Jalur kosong");

        String[] parts = p.split("/");
        String root = parts[0];
        StringBuilder rel = new StringBuilder();
        for (int i = 1; i < parts.length; i++) {
            String part = parts[i];
            if (part.isEmpty() || part.equals(".")) continue;
            if (part.equals("..")) throw new SecurityException("Jalur dengan '..' ditolak");
            if (rel.length() > 0) rel.append('/');
            rel.append(part);
        }

        File rootDir = getRootDir(root);
        if (forWrite && !isWritableRoot(root)) {
            throw new SecurityException("Folder " + root + "/ hanya bisa dibaca");
        }

        String relative = rel.toString();
        if (ROOT_CONFIG.equals(root) && !relative.isEmpty()) {
            int access = configAccess(relative);
            if (access == 0) {
                throw new SecurityException("Jalur config/" + relative + " tidak diizinkan. Lihat daftar dengan list_files path 'config'");
            }
            if (forWrite && access < 2) {
                throw new SecurityException("config/" + relative + " hanya bisa dibaca. Ubah lewat tool khususnya (set_project_info, set_build_setting, add_permission, dan sejenisnya)");
            }
        }
        File target = relative.isEmpty() ? rootDir : new File(rootDir, relative);

        String canonicalRoot = rootDir.getCanonicalPath();
        String canonicalTarget = target.getCanonicalPath();
        boolean inside = canonicalTarget.equals(canonicalRoot)
                || canonicalTarget.startsWith(canonicalRoot + File.separator);
        if (!inside) {
            throw new SecurityException("Jalur keluar dari folder proyek, ditolak");
        }
        if (forWrite && relative.isEmpty()) {
            throw new SecurityException("Tidak boleh mengubah folder akar " + root + "/ itu sendiri");
        }
        return new Resolved(root, relative, target, relative.isEmpty());
    }


    // ------------------------------------------------------- config whitelist

    /** Top-level entries of config/ that the AI may see (name -> description). */
    public static final String[] CONFIG_LISTING = {
            "proguard-rules.pro", "proguard", "proguard_fm", "stringfog",
            "injection/", "Injection/",
            "files/broadcast/", "files/service/", "files/native_libs/", "files/kt_plugins/", "files/library/",
            "project_config", "build_config", "permission", "local_library", "compile_log"
    };

    /**
     * @return 0 = not allowed, 1 = read-only, 2 = read and write
     */
    public static int configAccess(String relative) {
        String r = relative;
        while (r.startsWith("/")) r = r.substring(1);
        if (r.isEmpty()) return 1;
        String first = r.contains("/") ? r.substring(0, r.indexOf('/')) : r;
        switch (first) {
            case "proguard-rules.pro":
            case "proguard":
            case "proguard_fm":
            case "stringfog":
                return r.equals(first) ? 2 : 0;
            case "project_config":
            case "build_config":
            case "permission":
            case "local_library":
            case "compile_log":
                return r.equals(first) ? 1 : 0;
            case "injection":
            case "Injection":
                return 2;
            case "files": {
                String rest = r.length() > 6 ? r.substring(6) : "";
                String second = rest.contains("/") ? rest.substring(0, rest.indexOf('/')) : rest;
                switch (second) {
                    case "broadcast":
                    case "service":
                    case "native_libs":
                    case "kt_plugins":
                    case "library":
                        return 2;
                    default:
                        return 0;
                }
            }
            default:
                return 0;
        }
    }

    /**
     * Binary folders: text tools refuse them; only save_attachment_to_project may write there.
     */
    public static boolean isBinaryArea(Resolved r) {
        if (ROOT_CONFIG.equals(r.root)) {
            return r.relative.startsWith("files/native_libs") || r.relative.startsWith("files/library");
        }
        return false;
    }

    /**
     * Files in config/ that must stay valid JSON.
     */
    public static boolean mustBeJson(Resolved r) {
        if (ROOT_CONFIG.equals(r.root)) {
            return r.relative.equals("proguard") || r.relative.equals("proguard_fm") || r.relative.equals("stringfog")
                    || r.relative.endsWith(".json");
        }
        return r.relative.endsWith(".json");
    }

    public static boolean isTextFile(Resolved r) {
        if (ROOT_CONFIG.equals(r.root)) {
            return !isBinaryArea(r);
        }
        return isTextFileName(r.file.getName());
    }

    public static boolean isTextFileName(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        for (String ext : TEXT_EXTENSIONS) {
            if (lower.endsWith(ext)) return true;
        }
        return false;
    }

    // ---------------------------------------------------------------- backup

    public File getBackupDir() {
        return new File(Environment.getExternalStorageDirectory(), ".sketchware/ai_backups/" + scId);
    }

    /**
     * Zips the whole project data folder of THIS project into .sketchware/ai_backups/ID/.
     * Keeps the 5 newest backups.
     *
     * @return the created backup file
     */
    public File createBackup() throws Exception {
        File dir = getBackupDir();
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("Tidak bisa membuat folder cadangan");
        }
        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        File zip = new File(dir, "backup_" + stamp + ".zip");
        try (ZipOutputStream out = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(zip)))) {
            addToZip(out, dataDir, "");
        } catch (Throwable t) {
            //noinspection ResultOfMethodCallIgnored
            zip.delete();
            throw t;
        }
        pruneBackups(dir, 5);
        return zip;
    }

    private void addToZip(ZipOutputStream out, File file, String prefix) throws IOException {
        File[] children = file.listFiles();
        if (children == null) return;
        for (File child : children) {
            String name = prefix + child.getName();
            if (child.isDirectory()) {
                addToZip(out, child, name + "/");
            } else if (child.length() <= 20L * 1024 * 1024) {
                out.putNextEntry(new ZipEntry(name));
                try (BufferedInputStream in = new BufferedInputStream(new FileInputStream(child))) {
                    byte[] buffer = new byte[8192];
                    int read;
                    while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
                }
                out.closeEntry();
            }
        }
    }

    private void pruneBackups(File dir, int keep) {
        try {
            File[] all = dir.listFiles((d, n) -> n.startsWith("backup_") && n.endsWith(".zip"));
            if (all == null || all.length <= keep) return;
            Arrays.sort(all, Comparator.comparingLong(File::lastModified).reversed());
            for (int i = keep; i < all.length; i++) {
                //noinspection ResultOfMethodCallIgnored
                all[i].delete();
            }
        } catch (Throwable t) {
            Log.w(TAG, "Failed to prune backups", t);
        }
    }

    public File getLatestBackup() {
        try {
            File[] all = getBackupDir().listFiles((d, n) -> n.startsWith("backup_") && n.endsWith(".zip"));
            if (all == null || all.length == 0) return null;
            Arrays.sort(all, Comparator.comparingLong(File::lastModified).reversed());
            return all[0];
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Restores the newest backup over this project's data folder. Files that were added after the
     * backup stay in place; files in the backup overwrite the current ones.
     *
     * @return number of restored files
     */
    public int restoreLatestBackup() throws Exception {
        File zip = getLatestBackup();
        if (zip == null) throw new IOException("Belum ada cadangan untuk proyek ini");
        int count = 0;
        String canonicalData = dataDir.getCanonicalPath();
        try (ZipInputStream in = new ZipInputStream(new BufferedInputStream(new FileInputStream(zip)))) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                File target = new File(dataDir, entry.getName());
                String canonicalTarget = target.getCanonicalPath();
                if (!canonicalTarget.startsWith(canonicalData + File.separator)) {
                    continue; // zip-slip protection
                }
                if (entry.isDirectory()) {
                    //noinspection ResultOfMethodCallIgnored
                    target.mkdirs();
                    continue;
                }
                File parent = target.getParentFile();
                if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                    throw new IOException("Gagal membuat folder " + parent);
                }
                try (BufferedOutputStream out = new BufferedOutputStream(new FileOutputStream(target))) {
                    byte[] buffer = new byte[8192];
                    int read;
                    while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
                }
                count++;
            }
        }
        return count;
    }
}
