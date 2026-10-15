package mod.ai.andiasriefail;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.regex.Pattern;

import a.a.a.lC;
import a.a.a.wq;

/**
 * Tools that use files the user attached in the chat: app icon, resources, assets, library files.
 */
public final class ToolsAttachments {

    private ToolsAttachments() {
    }

    private static final Pattern FILE_NAME = Pattern.compile("^[A-Za-z0-9._-]+$");
    private static final int ICON_SIZE = 192;

    private static AiAttachments.Item requireItem(AiTool.Env env, String id) {
        AiAttachments.Item item = env.attachments.get(id);
        if (item == null) {
            throw new IllegalArgumentException("Attachment '" + id + "' does not exist. See list_attachments.");
        }
        return item;
    }

    private static void copy(File from, File to) throws IOException {
        File parent = to.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("Could not create the destination folder");
        }
        try (InputStream in = new FileInputStream(from); OutputStream out = new FileOutputStream(to)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
        }
    }

    public static final class ListAttachments extends AiTool {
        @Override
        public String name() {
            return "list_attachments";
        }

        @Override
        public String description() {
            return "Lists the attachments (images or files) the user gave in this chat, with id, name and type.";
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
            List<AiAttachments.Item> items = env.attachments.all();
            if (items.isEmpty()) return "(no attachments yet)";
            StringBuilder sb = new StringBuilder();
            for (AiAttachments.Item i : items) {
                sb.append(i.id).append("  ").append(i.displayName).append("  ").append(i.mime)
                        .append("  ").append(i.file.length()).append(" B\n");
            }
            return sb.toString();
        }
    }

    public static final class SetAppIcon extends AiTool {
        @Override
        public String name() {
            return "set_app_icon";
        }

        @Override
        public String description() {
            return "Uses an image attachment as the app icon. The image is cropped square from the center and converted to a 192x192 PNG. "
                    + "The old adaptive icon is removed.";
        }

        @Override
        public JSONObject schema() throws Exception {
            return props(new String[]{"attachment_id"}, "attachment_id", "string", "Image attachment id, for example att1.");
        }

        @Override
        public String summary(JSONObject input) {
            return input.optString("attachment_id", "");
        }

        @Override
        protected String run(Env env, JSONObject input) throws Exception {
            AiAttachments.Item item = requireItem(env, requireString(input, "attachment_id"));
            if (!item.image) throw new IllegalArgumentException("This attachment is not an image (" + item.mime + ")");

            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(item.file.getAbsolutePath(), bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw new IOException("The image could not be read");

            int sample = 1;
            while (bounds.outWidth / (sample * 2) >= ICON_SIZE * 2 && bounds.outHeight / (sample * 2) >= ICON_SIZE * 2) sample *= 2;
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = sample;
            Bitmap source = BitmapFactory.decodeFile(item.file.getAbsolutePath(), options);
            if (source == null) throw new IOException("The image could not be decoded");

            int side = Math.min(source.getWidth(), source.getHeight());
            Bitmap square = Bitmap.createBitmap(source, (source.getWidth() - side) / 2, (source.getHeight() - side) / 2, side, side);
            Bitmap scaled = Bitmap.createScaledBitmap(square, ICON_SIZE, ICON_SIZE, true);

            String id = env.guard.getScId();
            File iconDir = new File(wq.e() + File.separator + id);
            if (!iconDir.isDirectory() && !iconDir.mkdirs()) throw new IOException("Could not create the icon folder");
            File icon = new File(iconDir, "icon.png");
            try (OutputStream out = new FileOutputStream(icon)) {
                if (!scaled.compress(Bitmap.CompressFormat.PNG, 100, out)) throw new IOException("Could not save the PNG");
            }

            // an old adaptive icon set would override the new icon, so remove it
            ToolsFiles.deleteRecursive(new File(iconDir, "mipmaps"));

            HashMap<String, Object> metadata = ToolsProject.metadata(id);
            metadata.put("custom_icon", true);
            metadata.put("isIconAdaptive", false);
            lC.b(id, metadata);
            return "App icon replaced from " + item.displayName + ". It applies after the next build.";
        }
    }

    public static final class SaveAttachmentToProject extends AiTool {
        @Override
        public String name() {
            return "save_attachment_to_project";
        }

        @Override
        public String description() {
            return "Copies an attachment into the project, for example res/drawable/logo.png, assets/data.json or "
                    + "config/files/library/jar/lib.jar. File name: letters, digits, dot, underscore, minus. "
                    + "For Android resources use lowercase letters without spaces.";
        }

        @Override
        public JSONObject schema() throws Exception {
            return props(new String[]{"attachment_id", "path"},
                    "attachment_id", "string", "Attachment id, for example att2.",
                    "path", "string", "Destination virtual path including the file name.");
        }

        @Override
        public String summary(JSONObject input) {
            return input.optString("attachment_id", "") + " -> " + input.optString("path", "");
        }

        @Override
        protected String run(Env env, JSONObject input) throws Exception {
            AiAttachments.Item item = requireItem(env, requireString(input, "attachment_id"));
            AiProjectGuard.Resolved r = env.guard.resolve(requireString(input, "path"), true);
            if (!FILE_NAME.matcher(r.file.getName()).matches()) {
                throw new IllegalArgumentException("Invalid file name: " + r.file.getName());
            }
            if (AiProjectGuard.ROOT_JAVA.equals(r.root)) {
                throw new IllegalArgumentException("java/ is only for text files, use write_file");
            }
            if (r.file.isDirectory()) throw new IOException(r.virtualPath() + " is a folder");
            boolean existed = r.file.exists();
            copy(item.file, r.file);
            return (existed ? "Overwrote " : "Saved to ") + r.virtualPath() + " (" + r.file.length() + " B)";
        }
    }

    static List<AiTool> all() {
        List<AiTool> tools = new ArrayList<>();
        Collections.addAll(tools, new ListAttachments(), new SetAppIcon(), new SaveAttachmentToProject());
        return tools;
    }
}
