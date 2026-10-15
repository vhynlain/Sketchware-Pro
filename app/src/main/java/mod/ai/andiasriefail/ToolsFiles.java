package mod.ai.andiasriefail;

import android.content.Context;

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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
            throw new IOException("File too large (" + file.length() + " bytes, limit 2 MB)");
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
            throw new IOException("Could not create folder " + parent.getName());
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
            throw new IllegalArgumentException("Invalid XML, file NOT saved: " + e.getMessage());
        }
    }

    static void validateJson(String content) {
        try {
            String trimmed = content.trim();
            if (trimmed.startsWith("[")) new org.json.JSONArray(trimmed);
            else new JSONObject(trimmed);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid JSON, file NOT saved: " + e.getMessage());
        }
    }

    /**
     * Validates content for the target (XML and JSON must be well-formed), then size-checks it.
     */
    static void checkContent(AiProjectGuard.Resolved r, String content) throws Exception {
        if (content.getBytes(StandardCharsets.UTF_8).length > AiProjectGuard.MAX_WRITE_BYTES) {
            throw new IllegalArgumentException("Content too large (maximum " + AiProjectGuard.MAX_WRITE_BYTES + " byte)");
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
                out.add("...[list cut at " + MAX_LIST_ENTRIES + " entri]");
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
            return "Lists the content of a project folder. Without a path it lists the working folder set by change_directory, or the project root (java/, res/, assets/, config/, generated/). "
                    + "Use 'config' to see which configuration files may be accessed. Maximum 300 entries. list_tree shows a whole structure in one call.";
        }

        @Override
        public JSONObject schema() throws Exception {
            return props(new String[]{},
                    "path", "string", "Virtual folder path, for example res/layout. Leave empty for the working folder (or the project root).",
                    "recursive", "boolean", "true to also list subfolders (maximum depth 3).");
        }

        @Override
        public String summary(JSONObject input) {
            String p = input.optString("path", "");
            return p.isEmpty() ? "(akar)" : p;
        }

        @Override
        protected String run(Env env, JSONObject input) throws Exception {
            String path = input.optString("path", "").trim();
            boolean fromRootSlash = path.startsWith("/");
            while (path.startsWith("/")) path = path.substring(1);
            while (path.endsWith("/")) path = path.substring(0, path.length() - 1);
            if (path.equals(".")) path = "";
            // No path (or "."): list the working folder set by change_directory; "/" always lists the project root.
            if (path.isEmpty() && !fromRootSlash && !env.guard.getCwd().isEmpty()) path = env.guard.getCwd();
            List<String> lines = new ArrayList<>();

            if (path.isEmpty()) {
                lines.add("java/       custom Java/Kotlin files (writable)");
                lines.add("res/        resources: layout, values, drawable, ... (writable)");
                lines.add("assets/     assets folder (writable)");
                lines.add("config/     allowed project config files (partly writable)");
                lines.add("generated/  output of the last build (read-only)");
                lines.add("(a root that is missing from disk simply has no files yet; list_tree shows the whole structure)");
                return String.join("\n", lines);
            }

            if (path.equals("config")) {
                for (String entry : AiProjectGuard.CONFIG_LISTING) {
                    File f = new File(env.guard.getDataDir(), entry);
                    int access = AiProjectGuard.configAccess(entry.endsWith("/") ? entry.substring(0, entry.length() - 1) : entry);
                    lines.add("config/" + entry + (f.exists() ? "" : "  (not created yet)") + (access == 1 ? "  [read-only]" : ""));
                }
                return String.join("\n", lines);
            }
            if (path.equals("config/files")) {
                return "config/files/broadcast/\nconfig/files/service/\nconfig/files/native_libs/\nconfig/files/kt_plugins/\nconfig/files/library/";
            }

            AiProjectGuard.Resolved r = env.guard.resolve(path, false);
            if (!r.file.exists()) {
                if (AiProjectGuard.ROOT_GENERATED.equals(r.root)) {
                    return "(" + r.virtualPath() + " does not exist: generated/ is filled by build_project. Run build_project, then list it again.)";
                }
                if (r.file.getName().contains(".")) {
                    throw new IOException("File does not exist: " + r.virtualPath()
                            + ". Use list_tree or list_files on its folder to see what exists.");
                }
                if (env.guard.isWritableRoot(r.root) && !AiProjectGuard.ROOT_CONFIG.equals(r.root)) {
                    return "(" + r.virtualPath() + " does not exist yet, so it is empty. This is normal in a new project. Create it with make_dir, "
                            + "or write_file a file inside it (missing parent folders are created automatically). list_tree shows the whole structure at once.)";
                }
                throw new IOException("Folder does not exist: " + r.virtualPath());
            }
            if (!r.file.isDirectory()) {
                return r.virtualPath() + describe(r.file);
            }
            boolean recursive = input.optBoolean("recursive", false);
            listInto(lines, r.file, r.virtualPath().endsWith("/") ? r.virtualPath() : r.virtualPath() + "/", 1, recursive ? 3 : 1);
            if (lines.isEmpty()) return "(empty folder) " + r.virtualPath();
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
            return "Reads a text file. Each row starts with its line number and a tab; that prefix is NOT part of the file. "
                    + "Long files come back in pages: the result ends with [CONTINUES ...] and the start_line to use next, or [END OF FILE]. "
                    + "Read a file completely (follow CONTINUES) before editing it, and do not re-read lines you already have. "
                    + "Use start_line and end_line to read one region.";
        }

        @Override
        public JSONObject schema() throws Exception {
            return props(new String[]{"path"},
                    "path", "string", "Virtual path of the file, for example java/Helper.java or res/values/styles.xml",
                    "start_line", "integer", "First line (1-based). Optional.",
                    "end_line", "integer", "Last line, inclusive. Optional.");
        }

        @Override
        public String summary(JSONObject input) {
            String p = input.optString("path", "");
            if (input.has("start_line") || input.has("end_line")) {
                p += " [" + input.optInt("start_line", 1) + "-" + (input.has("end_line") ? String.valueOf(input.optInt("end_line")) : "end") + "]";
            }
            return p;
        }

        @Override
        protected String run(Env env, JSONObject input) throws Exception {
            AiProjectGuard.Resolved r = env.guard.resolve(requireString(input, "path"), false);
            if (!r.file.exists()) {
                throw new IOException("File does not exist: " + r.virtualPath() + ". Use list_tree or search_in_files to find the real path.");
            }
            if (r.file.isDirectory()) throw new IOException(r.virtualPath() + " is a folder; use list_files or list_tree");
            if (!AiProjectGuard.isTextFile(r)) throw new IOException("Binary file, cannot be read as text");

            String text = readText(r.file).replace("\r\n", "\n");
            String[] lines = text.split("\n", -1);
            int total = lines.length;
            int start = Math.max(1, input.optInt("start_line", 1));
            int requestedEnd = Math.min(total, input.optInt("end_line", total));
            if (start > requestedEnd) return "(no lines in that range; the file has " + total + " lines)";

            // Stop at a whole line and say where to continue, so the end of a file is never hidden silently.
            final int budget = 14_000;
            StringBuilder body = new StringBuilder();
            int end = start - 1;
            for (int i = start; i <= requestedEnd; i++) {
                String content = lines[i - 1];
                if (content.length() > 4000) {
                    content = content.substring(0, 4000) + " ...[line cut, " + (lines[i - 1].length() - 4000) + " more chars]";
                }
                String row = i + "\t" + content + "\n";
                if (body.length() + row.length() > budget && i > start) break;
                body.append(row);
                end = i;
            }
            StringBuilder sb = new StringBuilder();
            sb.append(r.virtualPath()).append(" - lines ").append(start).append('-').append(end).append(" of ").append(total)
                    .append(" (the number and tab at the start of each row are not part of the file)\n");
            sb.append(body);
            if (end < requestedEnd) {
                sb.append("[CONTINUES: ").append(requestedEnd - end).append(" more lines. Call read_file again with start_line=")
                        .append(end + 1).append(" to read on. Do not re-read the lines above.]");
            } else if (end >= total) {
                sb.append("[END OF FILE: ").append(total).append(" lines]");
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
            return "Creates a new text file or overwrites an existing one with the complete content. Missing parent folders are created automatically. Works for any text format (java, kt, xml, json, html, js, css, txt, ...). "
                    + "XML and JSON files are validated and rejected if malformed. For small changes use str_replace, replace_lines or regex_replace instead of rewriting the file.";
        }

        @Override
        public JSONObject schema() throws Exception {
            return props(new String[]{"path", "content"},
                    "path", "string", "Destination virtual path, for example res/layout/item_note.xml",
                    "content", "string", "Complete file content (UTF-8), without line numbers.");
        }

        @Override
        protected String run(Env env, JSONObject input) throws Exception {
            AiProjectGuard.Resolved r = env.guard.resolve(requireString(input, "path"), true);
            String content = input.optString("content", "");
            if (r.file.isDirectory()) throw new IOException(r.virtualPath() + " is a folder");
            if (!AiProjectGuard.isTextFile(r)) {
                throw new IOException("This file type cannot be written as text: " + r.file.getName());
            }
            checkContent(r, content);
            boolean existed = r.file.exists();
            writeText(r.file, content);
            int lines = content.isEmpty() ? 0 : content.split("\n", -1).length;
            return (existed ? "Overwrote " : "Created ") + r.virtualPath() + " (" + lines + " lines)";
        }
    }

    public static final class StrReplace extends AiTool {
        @Override
        public String name() {
            return "str_replace";
        }

        @Override
        public String description() {
            return "Replaces text in a file. old_str must be the text as it is in the file (copy it from read_file WITHOUT the line numbers) and unique, "
                    + "unless replace_all is true. Differences in spacing or indentation are tolerated. The result shows the removed and added lines. "
                    + "If it cannot find old_str it returns the closest places in the file; then re-read those lines instead of retrying the same text, "
                    + "or use replace_lines / regex_replace / write_file.";
        }

        @Override
        public JSONObject schema() throws Exception {
            return props(new String[]{"path", "old_str", "new_str"},
                    "path", "string", "Virtual path of the file.",
                    "old_str", "string", "Existing text, without line numbers.",
                    "new_str", "string", "Replacement text (may be empty to delete).",
                    "replace_all", "boolean", "true to replace every occurrence.");
        }

        @Override
        protected String run(Env env, JSONObject input) throws Exception {
            AiProjectGuard.Resolved r = env.guard.resolve(requireString(input, "path"), true);
            String oldStr = requireString(input, "old_str");
            String newStr = input.optString("new_str", "");
            boolean all = input.optBoolean("replace_all", false);
            if (!r.file.isFile()) {
                throw new IOException("File does not exist: " + r.virtualPath() + " (list_tree shows what exists; write_file creates a file)");
            }
            if (!AiProjectGuard.isTextFile(r)) throw new IOException("Not a text file: " + r.virtualPath());

            String raw = readText(r.file);
            boolean crlf = raw.contains("\r\n");
            String text = crlf ? raw.replace("\r\n", "\n") : raw;
            String oldN = stripLineNumbers(oldStr.replace("\r\n", "\n"));
            String newN = newStr.replace("\r\n", "\n");
            if (oldN.isEmpty()) throw new IllegalArgumentException("old_str must not be empty. To add text use insert_lines.");

            String how = "exact match";
            int count = 0;
            int from = 0;
            while ((from = text.indexOf(oldN, from)) != -1) {
                count++;
                from += oldN.length();
            }

            String updated;
            int firstLine;
            String removedText;
            if (count > 0) {
                if (count > 1 && !all) {
                    throw new IllegalArgumentException("old_str matches " + count + " places (lines " + lineList(text, oldN)
                            + "). Add more surrounding lines so it is unique, or set replace_all=true.");
                }
                int at = text.indexOf(oldN);
                firstLine = lineOf(text, at);
                removedText = oldN;
                updated = all ? text.replace(oldN, newN) : text.substring(0, at) + newN + text.substring(at + oldN.length());
            } else {
                Matcher m = looseMatcher(text, oldN);
                int found = 0;
                int firstStart = -1;
                int firstEnd = -1;
                List<Integer> where = new ArrayList<>();
                while (m != null && m.find()) {
                    if (found == 0) {
                        firstStart = m.start();
                        firstEnd = m.end();
                    }
                    found++;
                    if (where.size() < 8) where.add(lineOf(text, m.start()));
                }
                if (found == 0) throw new IllegalArgumentException(notFoundMessage(r.virtualPath(), text, oldN));
                if (found > 1 && !all) {
                    throw new IllegalArgumentException("old_str matches " + found + " places when spacing differences are ignored (lines "
                            + where + "). Add more surrounding lines so it is unique, or set replace_all=true.");
                }
                how = "whitespace-tolerant match: spacing or indentation differed from the file";
                count = all ? found : 1;
                firstLine = where.get(0);
                removedText = text.substring(firstStart, firstEnd);
                updated = all ? looseMatcher(text, oldN).replaceAll(Matcher.quoteReplacement(newN))
                        : text.substring(0, firstStart) + newN + text.substring(firstEnd);
            }
            if (crlf) updated = updated.replace("\n", "\r\n");
            checkContent(r, updated);
            writeText(r.file, updated);

            List<String> removed = Arrays.asList(removedText.split("\n", -1));
            List<String> added = newN.isEmpty() ? Collections.<String>emptyList() : Arrays.asList(newN.split("\n", -1));
            StringBuilder sb = new StringBuilder();
            sb.append("Replaced ").append(count).append(" occurrence(s) in ").append(r.virtualPath())
                    .append(", first at line ").append(firstLine).append(" [").append(how).append("]\n");
            sb.append(ToolsEdit.diffSnippet(firstLine, removed, added));
            if (count > 1) sb.append("(the diff shows the first occurrence only)\n");
            return sb.toString();
        }

        /** Models sometimes copy the "12<tab>" prefix of read_file rows into old_str. */
        private static String stripLineNumbers(String s) {
            String[] ls = s.split("\n", -1);
            int nonEmpty = 0;
            int numbered = 0;
            for (String l : ls) {
                if (!l.trim().isEmpty()) {
                    nonEmpty++;
                    if (l.matches("^\\s*\\d+\\t.*")) numbered++;
                }
            }
            if (nonEmpty == 0 || numbered != nonEmpty) return s;
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < ls.length; i++) {
                if (i > 0) sb.append('\n');
                sb.append(ls[i].replaceFirst("^\\s*\\d+\\t", ""));
            }
            return sb.toString();
        }

        private static Matcher looseMatcher(String text, String oldN) {
            String[] tokens = oldN.trim().split("\\s+");
            if (tokens.length == 0 || tokens.length > 600 || tokens[0].isEmpty()) return null;
            StringBuilder re = new StringBuilder();
            for (int i = 0; i < tokens.length; i++) {
                if (i > 0) re.append("\\s+");
                re.append(Pattern.quote(tokens[i]));
            }
            return Pattern.compile(re.toString()).matcher(text);
        }

        private static int lineOf(String text, int index) {
            int line = 1;
            for (int i = 0; i < index && i < text.length(); i++) if (text.charAt(i) == '\n') line++;
            return line;
        }

        private static String lineList(String text, String needle) {
            StringBuilder sb = new StringBuilder();
            int from = 0;
            int n = 0;
            while ((from = text.indexOf(needle, from)) != -1 && n < 8) {
                if (n > 0) sb.append(", ");
                sb.append(lineOf(text, from));
                from += needle.length();
                n++;
            }
            return sb.toString();
        }

        private static String notFoundMessage(String path, String text, String oldN) {
            String[] fileLines = text.split("\n", -1);
            String key = "";
            for (String l : oldN.split("\n", -1)) {
                if (!l.trim().isEmpty()) {
                    key = l.trim();
                    break;
                }
            }
            if (key.length() > 50) key = key.substring(0, 50);
            List<Integer> cand = new ArrayList<>();
            String low = key.toLowerCase();
            for (int i = 0; i < fileLines.length && cand.size() < 3 && !low.isEmpty(); i++) {
                if (fileLines[i].toLowerCase().contains(low)) cand.add(i);
            }
            if (cand.isEmpty()) {
                String best = "";
                for (String t : oldN.split("\\s+")) if (t.length() > best.length()) best = t;
                if (best.length() >= 5) {
                    String lb = best.toLowerCase();
                    for (int i = 0; i < fileLines.length && cand.size() < 3; i++) {
                        if (fileLines[i].toLowerCase().contains(lb)) cand.add(i);
                    }
                }
            }
            StringBuilder sb = new StringBuilder();
            sb.append("old_str was not found in ").append(path).append(" (the file has ").append(fileLines.length)
                    .append(" lines). It must match the text in the file; the file may differ from what you remember.");
            if (cand.isEmpty()) {
                sb.append(" No similar line was found; the code may be in another file (search_in_files).");
            } else {
                sb.append(" Closest places in the file:\n");
                for (int c : cand) {
                    int a = Math.max(0, c - 1);
                    int b = Math.min(fileLines.length - 1, c + 3);
                    for (int i = a; i <= b; i++) sb.append("  ").append(i + 1).append('\t').append(fileLines[i]).append('\n');
                    sb.append("  ---\n");
                }
            }
            sb.append("If you cannot reproduce the exact text: use replace_lines (line numbers from read_file), regex_replace, ")
                    .append("or rewrite the whole file with write_file after reading it completely.");
            return sb.toString();
        }
    }

    public static final class RenameFile extends AiTool {
        @Override
        public String name() {
            return "rename_file";
        }

        @Override
        public String description() {
            return "Renames a file or folder inside the project (move_path also moves into an existing folder and creates parents). The destination must not exist. "
                    + "After renaming a Java file, update its references (class name, imports, manifest).";
        }

        @Override
        public JSONObject schema() throws Exception {
            return props(new String[]{"from", "to"},
                    "from", "string", "Source virtual path.",
                    "to", "string", "Destination virtual path.");
        }

        @Override
        public String summary(JSONObject input) {
            return input.optString("from", "") + " -> " + input.optString("to", "");
        }

        @Override
        protected String run(Env env, JSONObject input) throws Exception {
            AiProjectGuard.Resolved from = env.guard.resolve(requireString(input, "from"), true);
            AiProjectGuard.Resolved to = env.guard.resolve(requireString(input, "to"), true);
            if (!from.file.exists()) throw new IOException("Source does not exist: " + from.virtualPath());
            if (to.file.exists()) throw new IOException("Destination already exists: " + to.virtualPath());
            File parent = to.file.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                throw new IOException("Could not create the destination folder");
            }
            if (!from.file.renameTo(to.file)) {
                throw new IOException("Could not rename " + from.virtualPath());
            }
            return "Moved: " + from.virtualPath() + " -> " + to.virtualPath();
        }
    }

    public static final class DeleteFile extends AiTool {
        @Override
        public String name() {
            return "delete_file";
        }

        @Override
        public String description() {
            return "Deletes a file or a folder (with its content) inside the project. ALWAYS asks the user for permission. "
                    + "Explain the reason before calling. A project backup was made at the start of the session.";
        }

        @Override
        public JSONObject schema() throws Exception {
            return props(new String[]{"path"}, "path", "string", "Virtual path to delete.");
        }

        @Override
        public boolean needsConfirmation(Env env, JSONObject input) {
            return true;
        }

        @Override
        public String confirmationText(Context context, JSONObject input) {
            return AiStrings.t(context, "Allow the assistant to delete:\n", "Izinkan asisten menghapus:\n") + input.optString("path", "?");
        }

        @Override
        protected String run(Env env, JSONObject input) throws Exception {
            AiProjectGuard.Resolved r = env.guard.resolve(requireString(input, "path"), true);
            if (!r.file.exists()) throw new IOException("Does not exist: " + r.virtualPath());
            int removed = deleteRecursive(r.file);
            if (r.file.exists()) throw new IOException("Could not delete " + r.virtualPath());
            return "Deleted: " + r.virtualPath() + " (" + removed + " item)";
        }
    }

    public static final class MakeDir extends AiTool {
        @Override
        public String name() {
            return "make_dir";
        }

        @Override
        public String description() {
            return "Creates a folder including missing parent folders, for example res/layout, res/drawable, res/values-v31, res/menu. Reports an error if it already exists.";
        }

        @Override
        public JSONObject schema() throws Exception {
            return props(new String[]{"path"}, "path", "string", "Virtual path of the folder to create.");
        }

        @Override
        protected String run(Env env, JSONObject input) throws Exception {
            AiProjectGuard.Resolved r = env.guard.resolve(requireString(input, "path"), true);
            if (r.file.isDirectory()) return "Folder already exists: " + r.virtualPath();
            if (r.file.exists()) throw new IOException("A file with that name already exists: " + r.virtualPath());
            if (!r.file.mkdirs()) throw new IOException("Could not create folder " + r.virtualPath());
            return "Folder created: " + r.virtualPath();
        }
    }

    static List<AiTool> all() {
        List<AiTool> tools = new ArrayList<>();
        Collections.addAll(tools, new ListFiles(), new ReadFile(), new WriteFile(), new StrReplace(),
                new RenameFile(), new DeleteFile(), new MakeDir());
        return tools;
    }
}
