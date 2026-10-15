package mod.ai.andiasriefail;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Shell-like navigation and file-structure tools: cd, pwd, head, tail, mv, cp.
 * The working directory lives in the AiProjectGuard of the chat, so every tool (list_files, read_file, write_file, ...)
 * resolves relative paths from it. A path that starts with "/" or with java/, res/, assets/, config/ or generated/ is always
 * relative to the project root.
 */
public final class ToolsNav {

    private ToolsNav() {
    }

    private static final int MAX_COPY_FILES = 500;
    private static final long MAX_COPY_BYTES = 30L * 1024 * 1024;

    static boolean isRootName(String s) {
        return AiProjectGuard.ROOT_JAVA.equals(s) || AiProjectGuard.ROOT_RES.equals(s) || AiProjectGuard.ROOT_ASSETS.equals(s)
                || AiProjectGuard.ROOT_CONFIG.equals(s) || AiProjectGuard.ROOT_GENERATED.equals(s);
    }

    private static String vp(AiProjectGuard.Resolved r) {
        String v = r.virtualPath();
        while (v.endsWith("/")) v = v.substring(0, v.length() - 1);
        return v;
    }

    private static String clip(String s) {
        String t = s.length() > 400 ? s.substring(0, 400) + " ...[line cut]" : s;
        return t.replace("\t", "  ");
    }

    private static String describeFolder(AiTool.Env env, String cwd) throws Exception {
        List<String> out = new ArrayList<>();
        if (cwd.isEmpty()) {
            out.add("java/  res/  assets/  config/  generated/");
            return String.join("\n", out);
        }
        File dir = env.guard.resolve("/" + cwd, false).file;
        File[] kids = dir.listFiles();
        if (kids == null || kids.length == 0) return "(empty)";
        Arrays.sort(kids);
        int shown = 0;
        for (File k : kids) {
            if (shown++ >= 40) {
                out.add("... " + (kids.length - 40) + " more (list_files shows everything)");
                break;
            }
            out.add(k.getName() + (k.isDirectory() ? "/" : ""));
        }
        return String.join("\n", out);
    }

    // ------------------------------------------------------------------ cd / pwd

    public static final class ChangeDirectory extends AiTool {
        @Override
        public String name() {
            return "change_directory";
        }

        @Override
        public String description() {
            return "Like cd: sets the working folder. Afterwards relative paths in every tool resolve from it, e.g. after change_directory java, "
                    + "read_file Helper.java reads java/Helper.java, and list_files with no path lists the folder. Supports .., . and / (project root). "
                    + "Paths starting with / or with java/, res/, assets/, config/, generated/ always mean the project root. Returns the folder content.";
        }

        @Override
        public JSONObject schema() throws Exception {
            return props(new String[]{"path"}, "path", "string", "Folder such as java, res/layout, .., or / for the project root");
        }

        @Override
        public String summary(JSONObject input) {
            return input.optString("path", "");
        }

        @Override
        protected String run(Env env, JSONObject input) throws Exception {
            String target = requireString(input, "path").trim().replace('\\', '/');
            String cur = env.guard.getCwd();
            String next;
            if (target.isEmpty() || target.equals("/") || target.equals("~")) {
                next = "";
            } else {
                List<String> segs = new ArrayList<>();
                String[] parts = target.split("/");
                boolean fromRoot = target.startsWith("/") || isRootName(parts[0]);
                if (!fromRoot && !cur.isEmpty()) segs.addAll(Arrays.asList(cur.split("/")));
                for (String part : parts) {
                    if (part.isEmpty() || part.equals(".")) continue;
                    if (part.equals("..")) {
                        if (!segs.isEmpty()) segs.remove(segs.size() - 1);
                    } else {
                        segs.add(part);
                    }
                }
                next = String.join("/", segs);
            }
            String note = "";
            if (!next.isEmpty()) {
                AiProjectGuard.Resolved r = env.guard.resolve("/" + next, false);
                boolean rootOnly = !next.contains("/") && isRootName(next);
                if (!r.file.exists()) {
                    if (rootOnly && env.guard.isWritableRoot(next) && !AiProjectGuard.ROOT_CONFIG.equals(next)) {
                        note = " (does not exist yet; write_file or make_dir creates it)";
                    } else {
                        throw new IOException("Folder does not exist: " + next + ". Create it with make_dir first, or check the name with list_tree.");
                    }
                } else if (!r.file.isDirectory()) {
                    throw new IOException(next + " is a file, not a folder");
                }
            }
            env.guard.setCwd(next);
            String shown = next.isEmpty() ? "/ (project root)" : next + "/";
            if (!note.isEmpty()) return "Current directory: " + shown + note;
            return "Current directory: " + shown + "\n" + describeFolder(env, next);
        }
    }

    public static final class CurrentDirectory extends AiTool {
        @Override
        public String name() {
            return "current_directory";
        }

        @Override
        public String description() {
            return "Like pwd: shows the working folder set by change_directory (/ means the project root).";
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
        protected String run(Env env, JSONObject input) {
            String cwd = env.guard.getCwd();
            return cwd.isEmpty() ? "/ (project root)" : cwd + "/";
        }
    }

    // ---------------------------------------------------------------- head / tail

    private abstract static class Peek extends AiTool {
        abstract boolean fromEnd();

        @Override
        public JSONObject schema() throws Exception {
            return props(new String[]{"path"},
                    "path", "string", "Virtual path of the file",
                    "lines", "integer", "How many lines (default 20, maximum 200)");
        }

        @Override
        public String summary(JSONObject input) {
            return input.optString("path", "") + " (" + input.optInt("lines", 20) + ")";
        }

        @Override
        protected String run(Env env, JSONObject input) throws Exception {
            AiProjectGuard.Resolved r = env.guard.resolve(requireString(input, "path"), false);
            if (!r.file.isFile()) throw new IOException("File does not exist: " + r.virtualPath() + ". Use list_tree to find the real path.");
            if (!AiProjectGuard.isTextFile(r)) throw new IOException("Binary file, cannot be shown as text");
            int n = Math.max(1, Math.min(200, input.optInt("lines", 20)));
            String[] lines = ToolsFiles.readText(r.file).replace("\r\n", "\n").split("\n", -1);
            int total = lines.length;
            int start = fromEnd() ? Math.max(1, total - n + 1) : 1;
            int end = fromEnd() ? total : Math.min(total, n);
            StringBuilder sb = new StringBuilder();
            sb.append(r.virtualPath()).append(" - lines ").append(start).append('-').append(end).append(" of ").append(total).append('\n');
            for (int i = start; i <= end; i++) sb.append(i).append('\t').append(clip(lines[i - 1])).append('\n');
            if (fromEnd()) {
                if (start > 1) sb.append("[").append(start - 1).append(" earlier lines not shown]");
                else sb.append("[START OF FILE reached]");
            } else if (end < total) {
                sb.append('[').append(total - end).append(" more lines. read_file start_line=").append(end + 1).append(" continues]");
            } else {
                sb.append("[END OF FILE]");
            }
            return sb.toString();
        }
    }

    public static final class HeadFile extends Peek {
        @Override
        boolean fromEnd() {
            return false;
        }

        @Override
        public String name() {
            return "head_file";
        }

        @Override
        public String description() {
            return "Like head: shows the first N lines of a file (default 20) with line numbers. Cheap way to see imports, the package line, "
                    + "the class declaration or the root element of an XML file before deciding what to read.";
        }
    }

    public static final class TailFile extends Peek {
        @Override
        boolean fromEnd() {
            return true;
        }

        @Override
        public String name() {
            return "tail_file";
        }

        @Override
        public String description() {
            return "Like tail: shows the last N lines of a file (default 20) with their real line numbers. Useful to see how a class or XML file ends, "
                    + "or the newest entries of a log, and to find the line number for insert_lines at the end.";
        }
    }

    // ---------------------------------------------------------------- move / copy

    private static void deleteRecursive(File f) throws IOException {
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) for (File k : kids) deleteRecursive(k);
        }
        if (!f.delete() && f.exists()) throw new IOException("Could not delete " + f.getName());
    }

    private static void measure(File f, long[] totals) throws IOException {
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) for (File k : kids) measure(k, totals);
        } else {
            totals[0]++;
            totals[1] += f.length();
            if (totals[0] > MAX_COPY_FILES || totals[1] > MAX_COPY_BYTES) {
                throw new IOException("Too large to copy in one step (limit " + MAX_COPY_FILES + " files / " + (MAX_COPY_BYTES / 1024 / 1024) + " MB)");
            }
        }
    }

    private static void copyRecursive(File from, File to) throws IOException {
        if (from.isDirectory()) {
            if (!to.isDirectory() && !to.mkdirs()) throw new IOException("Could not create folder " + to.getName());
            File[] kids = from.listFiles();
            if (kids != null) for (File k : kids) copyRecursive(k, new File(to, k.getName()));
            return;
        }
        File parent = to.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) throw new IOException("Could not create folder " + parent.getName());
        try (InputStream in = new FileInputStream(from); OutputStream out = new FileOutputStream(to)) {
            byte[] buf = new byte[16 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
    }

    private static boolean isInside(File child, File parent) throws IOException {
        String c = child.getCanonicalPath();
        String p = parent.getCanonicalPath();
        return c.equals(p) || c.startsWith(p + File.separator);
    }

    public static final class MovePath extends AiTool {
        @Override
        public String name() {
            return "move_path";
        }

        @Override
        public String description() {
            return "Like mv: moves or renames a file or a whole folder, also between java/, res/ and assets/. If 'to' is an existing folder the item "
                    + "is moved INTO it (keeping its name). Missing parent folders of the destination are created. Refuses to overwrite unless "
                    + "overwrite=true (files only). After moving Java files, check the package line, imports and manifest entries; a file at the top of java/ "
                    + "named like a visual screen's class (for example MainActivity.java) replaces the generated class.";
        }

        @Override
        public JSONObject schema() throws Exception {
            return props(new String[]{"from", "to"},
                    "from", "string", "Source file or folder",
                    "to", "string", "Destination path, or an existing folder to move into",
                    "overwrite", "boolean", "Replace an existing destination FILE (default false)");
        }

        @Override
        public String summary(JSONObject input) {
            return input.optString("from", "") + " -> " + input.optString("to", "");
        }

        @Override
        protected String run(Env env, JSONObject input) throws Exception {
            AiProjectGuard.Resolved from = env.guard.resolve(requireString(input, "from"), true);
            AiProjectGuard.Resolved to = env.guard.resolve(requireString(input, "to"), true);
            boolean overwrite = input.optBoolean("overwrite", false);
            if (!from.file.exists()) throw new IOException("Source does not exist: " + from.virtualPath() + ". Use list_tree to check.");
            File dest = to.file;
            String destVirtual = vp(to);
            if (dest.isDirectory()) {
                dest = new File(dest, from.file.getName());
                destVirtual = destVirtual + "/" + from.file.getName();
            }
            if (from.file.getCanonicalPath().equals(dest.getCanonicalPath())) throw new IOException("Source and destination are the same");
            if (from.file.isDirectory() && isInside(dest, from.file)) throw new IOException("Cannot move a folder into itself");
            if (dest.exists()) {
                if (dest.isDirectory() || from.file.isDirectory() || !overwrite) {
                    throw new IOException("Destination already exists: " + destVirtual
                            + (dest.isFile() && from.file.isFile() ? ". Pass overwrite=true to replace it." : ""));
                }
                if (!dest.delete()) throw new IOException("Could not replace " + destVirtual);
            }
            File parent = dest.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) throw new IOException("Could not create the destination folder");
            if (!from.file.renameTo(dest)) {
                copyRecursive(from.file, dest);
                deleteRecursive(from.file);
            }
            String kind = dest.isDirectory() ? "folder" : "file";
            StringBuilder sb = new StringBuilder("Moved " + kind + ": " + vp(from) + " -> " + destVirtual);
            if (destVirtual.endsWith(".java") || destVirtual.endsWith(".kt") || dest.isDirectory()) {
                sb.append("\nCheck package lines, imports and manifest entries that referred to the old location.");
            }
            return sb.toString();
        }
    }

    public static final class CopyPath extends AiTool {
        @Override
        public String name() {
            return "copy_path";
        }

        @Override
        public String description() {
            return "Like cp -r: copies a file or a whole folder. The source may also be read-only (generated/ or an allowed config file), "
                    + "which is the safe way to start from generated code, for example copy generated/app/src/main/res/layout/main.xml to "
                    + "res/layout/main.xml and then edit your copy. If 'to' is an existing folder the copy goes INTO it. Refuses to overwrite unless overwrite=true.";
        }

        @Override
        public JSONObject schema() throws Exception {
            return props(new String[]{"from", "to"},
                    "from", "string", "Source file or folder",
                    "to", "string", "Destination path, or an existing folder to copy into",
                    "overwrite", "boolean", "Replace an existing destination FILE (default false)");
        }

        @Override
        public String summary(JSONObject input) {
            return input.optString("from", "") + " -> " + input.optString("to", "");
        }

        @Override
        protected String run(Env env, JSONObject input) throws Exception {
            AiProjectGuard.Resolved from = env.guard.resolve(requireString(input, "from"), false);
            AiProjectGuard.Resolved to = env.guard.resolve(requireString(input, "to"), true);
            boolean overwrite = input.optBoolean("overwrite", false);
            if (!from.file.exists()) throw new IOException("Source does not exist: " + from.virtualPath() + ". Use list_tree to check.");
            File dest = to.file;
            String destVirtual = vp(to);
            if (dest.isDirectory()) {
                dest = new File(dest, from.file.getName());
                destVirtual = destVirtual + "/" + from.file.getName();
            }
            if (from.file.isDirectory() && isInside(dest, from.file)) throw new IOException("Cannot copy a folder into itself");
            if (dest.exists()) {
                if (dest.isDirectory() || from.file.isDirectory() || !overwrite) {
                    throw new IOException("Destination already exists: " + destVirtual
                            + (dest.isFile() && from.file.isFile() ? ". Pass overwrite=true to replace it." : ""));
                }
            }
            long[] totals = {0, 0};
            measure(from.file, totals);
            copyRecursive(from.file, dest);
            return "Copied " + vp(from) + " -> " + destVirtual + " (" + totals[0] + " file(s), " + totals[1] + " bytes)";
        }
    }

    static List<AiTool> all() {
        List<AiTool> tools = new ArrayList<>();
        Collections.addAll(tools, new ChangeDirectory(), new CurrentDirectory(), new HeadFile(), new TailFile(), new MovePath(), new CopyPath());
        return tools;
    }
}
