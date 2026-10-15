package mod.ai.andiasriefail;

import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Search and precise-edit tools. They exist because str_replace needs an exact text match and a
 * model often cannot reproduce it; line-based and regex edits give it a reliable way to change code,
 * and every result shows exactly what changed.
 */
public final class ToolsEdit {

    private ToolsEdit() {
    }

    private static final int MAX_FILE_BYTES = 1_000_000;

    // ---------------------------------------------------------------- helpers

    private static String clip(String s) {
        String t = s.length() > 200 ? s.substring(0, 200) + "..." : s;
        return t.replace("\t", "  ");
    }

    /** Human readable "- old / + new" summary with line numbers. Used by every edit tool. */
    static String diffSnippet(int startLine, List<String> removed, List<String> added) {
        StringBuilder sb = new StringBuilder();
        int max = 25;
        for (int i = 0; i < removed.size() && i < max; i++) {
            sb.append("- ").append(startLine + i).append(": ").append(clip(removed.get(i))).append('\n');
        }
        if (removed.size() > max) sb.append("- ... ").append(removed.size() - max).append(" more removed lines\n");
        for (int i = 0; i < added.size() && i < max; i++) {
            sb.append("+ ").append(startLine + i).append(": ").append(clip(added.get(i))).append('\n');
        }
        if (added.size() > max) sb.append("+ ... ").append(added.size() - max).append(" more added lines\n");
        return sb.toString();
    }

    /** A text file split into lines the same way read_file numbers them. */
    static final class Doc {
        final List<String> lines;
        final boolean crlf;

        private Doc(List<String> lines, boolean crlf) {
            this.lines = lines;
            this.crlf = crlf;
        }

        static Doc read(File file) throws IOException {
            String text = ToolsFiles.readText(file);
            boolean crlf = text.contains("\r\n");
            String norm = crlf ? text.replace("\r\n", "\n") : text;
            return new Doc(new ArrayList<>(Arrays.asList(norm.split("\n", -1))), crlf);
        }

        String join() {
            return String.join(crlf ? "\r\n" : "\n", lines);
        }
    }

    private static List<String> splitNew(String text) {
        if (text == null || text.isEmpty()) return new ArrayList<>();
        String t = text.replace("\r\n", "\n");
        if (t.endsWith("\n")) t = t.substring(0, t.length() - 1);
        return new ArrayList<>(Arrays.asList(t.split("\n", -1)));
    }

    private static AiProjectGuard.Resolved editable(AiTool.Env env, String path) throws Exception {
        AiProjectGuard.Resolved r = env.guard.resolve(path, true);
        if (!r.file.isFile()) throw new IOException("File does not exist: " + r.virtualPath() + " (use write_file to create it)");
        if (!AiProjectGuard.isTextFile(r)) throw new IOException("Not a text file: " + r.virtualPath());
        return r;
    }

    private static String base(AiProjectGuard.Resolved r) {
        String v = r.virtualPath();
        while (v.endsWith("/")) v = v.substring(0, v.length() - 1);
        return v;
    }

    // ----------------------------------------------------------------- search

    public static final class SearchInFiles extends AiTool {
        @Override
        public String name() {
            return "search_in_files";
        }

        @Override
        public String description() {
            return "Searches text inside project files and returns 'path:line: text' for every hit (like grep). "
                    + "Default scope is java/, res/ and assets/. Use it to find where a string, class, id or function is used "
                    + "BEFORE editing, e.g. to find hard-coded UI text or every call of a method. Set regex=true for a regular expression.";
        }

        @Override
        public JSONObject schema() throws Exception {
            return props(new String[]{"query"},
                    "query", "string", "Text or regular expression to find",
                    "path", "string", "Optional file or folder to search, e.g. java/ or res/layout. Default: java/, res/, assets/",
                    "regex", "boolean", "Treat query as a Java regular expression (default false)",
                    "case_sensitive", "boolean", "Default false",
                    "max_results", "integer", "Default 80, maximum 200");
        }

        @Override
        public String summary(JSONObject input) {
            return input.optString("query", "");
        }

        @Override
        protected String run(Env env, JSONObject input) throws Exception {
            String query = requireString(input, "query");
            boolean regex = input.optBoolean("regex", false);
            boolean cs = input.optBoolean("case_sensitive", false);
            int max = Math.max(1, Math.min(200, input.optInt("max_results", 80)));
            Pattern pattern;
            try {
                pattern = Pattern.compile(regex ? query : Pattern.quote(query), cs ? 0 : Pattern.CASE_INSENSITIVE);
            } catch (PatternSyntaxException e) {
                throw new IllegalArgumentException("Invalid regular expression: " + e.getDescription());
            }

            List<AiProjectGuard.Resolved> starts = new ArrayList<>();
            String path = input.optString("path", "").trim();
            if (path.isEmpty()) {
                for (String root : new String[]{AiProjectGuard.ROOT_JAVA, AiProjectGuard.ROOT_RES, AiProjectGuard.ROOT_ASSETS}) {
                    starts.add(env.guard.resolve(root, false));
                }
            } else {
                starts.add(env.guard.resolve(path, false));
            }

            List<String> hits = new ArrayList<>();
            int[] filesScanned = {0};
            boolean[] full = {false};
            for (AiProjectGuard.Resolved s : starts) {
                if (!s.file.exists()) continue;
                scan(s.file, s.file, base(s), pattern, hits, max, filesScanned, full, 0);
                if (full[0]) break;
            }
            StringBuilder sb = new StringBuilder();
            if (hits.isEmpty()) {
                sb.append("No matches for \"").append(query).append("\" in ").append(filesScanned[0]).append(" files.");
                return sb.toString();
            }
            for (String h : hits) sb.append(h).append('\n');
            sb.append(hits.size()).append(" match(es) in ").append(filesScanned[0]).append(" files scanned");
            if (full[0]) sb.append(" (stopped at max_results=").append(max).append(", narrow the query or path)");
            return sb.toString();
        }

        private static void scan(File root, File file, String virtualBase, Pattern pattern, List<String> hits, int max,
                                 int[] scanned, boolean[] full, int depth) throws IOException {
            if (full[0] || depth > 12) return;
            if (file.isDirectory()) {
                File[] kids = file.listFiles();
                if (kids == null) return;
                Arrays.sort(kids);
                for (File k : kids) scan(root, k, virtualBase, pattern, hits, max, scanned, full, depth + 1);
                return;
            }
            if (!AiProjectGuard.isTextFileName(file.getName()) || file.length() > MAX_FILE_BYTES) return;
            scanned[0]++;
            String rel = file.equals(root) ? "" : file.getAbsolutePath().substring(root.getAbsolutePath().length()).replace('\\', '/');
            String vpath = virtualBase + rel;
            String[] lines = ToolsFiles.readText(file).split("\n", -1);
            for (int i = 0; i < lines.length; i++) {
                if (pattern.matcher(lines[i]).find()) {
                    hits.add(vpath + ":" + (i + 1) + ": " + clip(lines[i].trim()));
                    if (hits.size() >= max) {
                        full[0] = true;
                        return;
                    }
                }
            }
        }
    }

    // -------------------------------------------------------------- list_tree

    public static final class ListTree extends AiTool {
        @Override
        public String name() {
            return "list_tree";
        }

        @Override
        public String description() {
            return "Shows the folder structure of the project in ONE call (indented tree with file sizes). Default: java/, res/ and assets/ "
                    + "up to depth 4. Use this first to see how the project is organised, instead of guessing folder names or calling "
                    + "list_files many times. A missing root simply means it is empty so far.";
        }

        @Override
        public JSONObject schema() throws Exception {
            return props(new String[]{},
                    "path", "string", "Optional folder, e.g. java/ or res/layout. Default: java/, res/, assets/",
                    "max_depth", "integer", "Default 4, maximum 8",
                    "max_entries", "integer", "Default 300, maximum 600");
        }

        @Override
        public String summary(JSONObject input) {
            String p = input.optString("path", "");
            return p.isEmpty() ? "(java, res, assets)" : p;
        }

        @Override
        protected String run(Env env, JSONObject input) throws Exception {
            int depth = Math.max(1, Math.min(8, input.optInt("max_depth", 4)));
            int max = Math.max(20, Math.min(600, input.optInt("max_entries", 300)));
            List<String> out = new ArrayList<>();
            String path = input.optString("path", "").trim();
            List<AiProjectGuard.Resolved> starts = new ArrayList<>();
            if (path.isEmpty()) {
                for (String root : new String[]{AiProjectGuard.ROOT_JAVA, AiProjectGuard.ROOT_RES, AiProjectGuard.ROOT_ASSETS}) {
                    starts.add(env.guard.resolve(root, false));
                }
            } else {
                starts.add(env.guard.resolve(path, false));
            }
            int[] count = {0};
            for (AiProjectGuard.Resolved s : starts) {
                if (!s.file.exists()) {
                    out.add(base(s) + "/  (does not exist yet; write_file or make_dir creates it)");
                    continue;
                }
                if (!s.file.isDirectory()) {
                    out.add(base(s) + "  " + size(s.file));
                    continue;
                }
                out.add(base(s) + "/");
                walk(s.file, 1, depth, out, count, max);
            }
            if (count[0] >= max) out.add("... list cut at " + max + " entries; pass path or a smaller max_depth to see more");
            return String.join("\n", out);
        }

        private static void walk(File dir, int level, int maxDepth, List<String> out, int[] count, int max) {
            File[] kids = dir.listFiles();
            if (kids == null) return;
            Arrays.sort(kids, (a, b) -> {
                if (a.isDirectory() != b.isDirectory()) return a.isDirectory() ? -1 : 1;
                return a.getName().compareToIgnoreCase(b.getName());
            });
            StringBuilder pad = new StringBuilder();
            for (int i = 0; i < level; i++) pad.append("  ");
            for (File k : kids) {
                if (count[0] >= max) return;
                count[0]++;
                if (k.isDirectory()) {
                    String[] inner = k.list();
                    out.add(pad + k.getName() + "/" + (inner == null || inner.length == 0 ? "  (empty)" : ""));
                    if (level < maxDepth) walk(k, level + 1, maxDepth, out, count, max);
                } else {
                    out.add(pad + k.getName() + "  " + size(k));
                }
            }
        }

        private static String size(File f) {
            long n = f.length();
            if (n < 1024) return n + " B";
            if (n < 1024 * 1024) return String.format(Locale.US, "%.1f KB", n / 1024.0);
            return String.format(Locale.US, "%.1f MB", n / 1024.0 / 1024.0);
        }
    }

    // ------------------------------------------------------------ line editing

    public static final class ReplaceLines extends AiTool {
        @Override
        public String name() {
            return "replace_lines";
        }

        @Override
        public String description() {
            return "Replaces an inclusive range of lines (numbers as shown by read_file) with new text. Use it when str_replace cannot match. "
                    + "Empty new_text deletes the lines. Read the file range right before calling, because line numbers shift after every edit. "
                    + "The result shows the removed and added lines.";
        }

        @Override
        public JSONObject schema() throws Exception {
            return props(new String[]{"path", "start_line", "new_text"},
                    "path", "string", "Virtual path of the file",
                    "start_line", "integer", "First line to replace (1-based)",
                    "end_line", "integer", "Last line to replace, inclusive (default: start_line)",
                    "new_text", "string", "Replacement text (may be several lines, may be empty to delete)");
        }

        @Override
        public String summary(JSONObject input) {
            return input.optString("path", "") + ":" + input.optInt("start_line") + "-" + input.optInt("end_line", input.optInt("start_line"));
        }

        @Override
        protected String run(Env env, JSONObject input) throws Exception {
            AiProjectGuard.Resolved r = editable(env, requireString(input, "path"));
            Doc d = Doc.read(r.file);
            int start = input.getInt("start_line");
            int end = input.optInt("end_line", start);
            if (start < 1 || end < start || end > d.lines.size()) {
                throw new IllegalArgumentException("Range must satisfy 1 <= start_line <= end_line <= " + d.lines.size()
                        + " (file has " + d.lines.size() + " lines). Re-read the file.");
            }
            List<String> removed = new ArrayList<>(d.lines.subList(start - 1, end));
            List<String> added = splitNew(input.optString("new_text", ""));
            for (int i = 0; i < end - start + 1; i++) d.lines.remove(start - 1);
            d.lines.addAll(start - 1, added);
            String updated = d.join();
            ToolsFiles.checkContent(r, updated);
            ToolsFiles.writeText(r.file, updated);
            return "Replaced lines " + start + "-" + end + " of " + r.virtualPath() + " (now " + d.lines.size() + " lines)\n"
                    + diffSnippet(start, removed, added);
        }
    }

    public static final class InsertLines extends AiTool {
        @Override
        public String name() {
            return "insert_lines";
        }

        @Override
        public String description() {
            return "Inserts new text after a given line (0 = at the very top; the last line number = at the end). "
                    + "Good for adding imports, methods or XML elements without matching existing text.";
        }

        @Override
        public JSONObject schema() throws Exception {
            return props(new String[]{"path", "after_line", "text"},
                    "path", "string", "Virtual path of the file",
                    "after_line", "integer", "Insert after this line number (0 = top)",
                    "text", "string", "Text to insert (may be several lines)");
        }

        @Override
        public String summary(JSONObject input) {
            return input.optString("path", "") + " after " + input.optInt("after_line");
        }

        @Override
        protected String run(Env env, JSONObject input) throws Exception {
            AiProjectGuard.Resolved r = editable(env, requireString(input, "path"));
            Doc d = Doc.read(r.file);
            int after = input.getInt("after_line");
            if (after < 0 || after > d.lines.size()) {
                throw new IllegalArgumentException("after_line must be between 0 and " + d.lines.size() + ". Re-read the file.");
            }
            List<String> added = splitNew(requireString(input, "text"));
            d.lines.addAll(after, added);
            String updated = d.join();
            ToolsFiles.checkContent(r, updated);
            ToolsFiles.writeText(r.file, updated);
            return "Inserted " + added.size() + " line(s) after line " + after + " of " + r.virtualPath() + " (now " + d.lines.size() + " lines)\n"
                    + diffSnippet(after + 1, Collections.<String>emptyList(), added);
        }
    }

    public static final class RegexReplace extends AiTool {
        @Override
        public String name() {
            return "regex_replace";
        }

        @Override
        public String description() {
            return "Regular-expression search and replace inside one file (like sed). Java regex syntax; $1 in the replacement refers to group 1, "
                    + "write \\\\$ for a literal dollar. Replaces all matches by default. Fails if nothing matches, and reports the matched lines. "
                    + "Good for renaming something everywhere in a file or changing a repeated pattern.";
        }

        @Override
        public JSONObject schema() throws Exception {
            return props(new String[]{"path", "pattern", "replacement"},
                    "path", "string", "Virtual path of the file",
                    "pattern", "string", "Java regular expression",
                    "replacement", "string", "Replacement text, may use $1 groups",
                    "replace_all", "boolean", "Default true; false replaces only the first match",
                    "ignore_case", "boolean", "Default false");
        }

        @Override
        public String summary(JSONObject input) {
            return input.optString("path", "");
        }

        @Override
        protected String run(Env env, JSONObject input) throws Exception {
            AiProjectGuard.Resolved r = editable(env, requireString(input, "path"));
            String pattern = requireString(input, "pattern");
            String replacement = input.optString("replacement", "");
            boolean all = input.optBoolean("replace_all", true);
            Pattern p;
            try {
                p = Pattern.compile(pattern, Pattern.MULTILINE | (input.optBoolean("ignore_case", false) ? Pattern.CASE_INSENSITIVE : 0));
            } catch (PatternSyntaxException e) {
                throw new IllegalArgumentException("Invalid regular expression: " + e.getDescription());
            }
            String text = ToolsFiles.readText(r.file);
            Matcher m = p.matcher(text);
            List<String> where = new ArrayList<>();
            int count = 0;
            while (m.find()) {
                count++;
                if (where.size() < 8) {
                    int line = 1;
                    for (int i = 0; i < m.start(); i++) if (text.charAt(i) == '\n') line++;
                    where.add("line " + line + ": " + clip(m.group().split("\n", -1)[0].trim()));
                }
                if (!all) break;
            }
            if (count == 0) {
                throw new IllegalArgumentException("Pattern did not match anything in " + r.virtualPath()
                        + ". Use search_in_files or read_file to check the real text.");
            }
            String updated;
            try {
                updated = all ? p.matcher(text).replaceAll(replacement) : p.matcher(text).replaceFirst(replacement);
            } catch (IndexOutOfBoundsException | IllegalArgumentException e) {
                throw new IllegalArgumentException("Bad replacement text: " + e.getMessage());
            }
            ToolsFiles.checkContent(r, updated);
            ToolsFiles.writeText(r.file, updated);
            StringBuilder sb = new StringBuilder("Replaced " + count + " match(es) in " + r.virtualPath() + "\n");
            for (String w : where) sb.append("  ").append(w).append('\n');
            if (count > where.size()) sb.append("  ... and ").append(count - where.size()).append(" more\n");
            return sb.toString();
        }
    }

    static List<AiTool> all() {
        List<AiTool> tools = new ArrayList<>();
        Collections.addAll(tools, new SearchInFiles(), new ListTree(), new ReplaceLines(), new InsertLines(), new RegexReplace());
        return tools;
    }
}
