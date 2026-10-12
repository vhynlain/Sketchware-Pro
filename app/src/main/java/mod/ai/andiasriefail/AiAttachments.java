package mod.ai.andiasriefail;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Files the user attached through the "+" button. They are copied into the app's private cache,
 * so the AI never gets a path outside of it.
 */
public final class AiAttachments {

    public static final long MAX_BYTES = 15L * 1024 * 1024;

    public static final class Item {
        public final String id;
        public final String displayName;
        public final String mime;
        public final File file;
        public final boolean image;

        Item(String id, String displayName, String mime, File file) {
            this.id = id;
            this.displayName = displayName;
            this.mime = mime;
            this.file = file;
            this.image = mime != null && mime.startsWith("image/");
        }
    }

    private final File dir;
    private final List<Item> items = new ArrayList<>();
    private int counter = 0;

    public AiAttachments(Context context, String scId) {
        dir = new File(context.getCacheDir(), "ai_attachments/" + scId);
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
    }

    public synchronized Item add(Context context, Uri uri) throws Exception {
        ContentResolver resolver = context.getContentResolver();
        String name = "file";
        long size = -1;
        try (Cursor cursor = resolver.query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                int sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE);
                if (nameIndex >= 0) name = cursor.getString(nameIndex);
                if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex);
            }
        }
        if (size > MAX_BYTES) throw new IllegalArgumentException("File terlalu besar (maksimal 15 MB)");
        String mime = resolver.getType(uri);
        if (mime == null) mime = "application/octet-stream";

        String safeName = name.replaceAll("[^A-Za-z0-9._-]", "_");
        if (safeName.isEmpty()) safeName = "file";
        String id = "att" + (++counter);
        File target = new File(dir, id + "_" + safeName);

        long total = 0;
        try (InputStream in = resolver.openInputStream(uri);
             OutputStream out = new FileOutputStream(target)) {
            if (in == null) throw new IllegalStateException("Tidak bisa membuka file");
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                total += read;
                if (total > MAX_BYTES) {
                    //noinspection ResultOfMethodCallIgnored
                    target.delete();
                    throw new IllegalArgumentException("File terlalu besar (maksimal 15 MB)");
                }
                out.write(buffer, 0, read);
            }
        }
        Item item = new Item(id, name, mime.toLowerCase(Locale.ROOT), target);
        items.add(item);
        return item;
    }

    public synchronized Item get(String id) {
        for (Item item : items) {
            if (item.id.equals(id)) return item;
        }
        return null;
    }

    public synchronized List<Item> all() {
        return new ArrayList<>(items);
    }

    public synchronized void remove(String id) {
        Item item = get(id);
        if (item != null) {
            items.remove(item);
            //noinspection ResultOfMethodCallIgnored
            item.file.delete();
        }
    }

    public synchronized void clear() {
        for (Item item : items) {
            //noinspection ResultOfMethodCallIgnored
            item.file.delete();
        }
        items.clear();
    }
}
