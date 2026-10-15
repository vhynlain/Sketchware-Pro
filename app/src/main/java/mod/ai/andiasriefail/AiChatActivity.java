package mod.ai.andiasriefail;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.Menu;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;
import androidx.core.graphics.ColorUtils;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import a.a.a.lC;
import io.noties.markwon.Markwon;

/**
 * Chat screen of the AI assistant. The UI is built in code, so the whole feature lives in this one folder.
 * Started from DesignActivity with the extra "sc_id"; it can only ever touch that project.
 */
public class AiChatActivity extends AppCompatActivity implements AiAgent.Listener {

    public static final String EXTRA_SC_ID = "sc_id";
    private static final String TAG = "AiChatActivity";

    private AiProjectGuard guard;
    private AiAttachments attachments;
    private AiLogCollector logs;
    private AiAgent agent;
    private Markwon markwon;

    private LinearLayout chat;
    private ScrollView scroll;
    private TextView banner;
    private LinearLayout runBar;
    private TextView runText;
    private HorizontalScrollView chipScroll;
    private LinearLayout chipRow;
    private EditText input;
    private MaterialButton sendButton;

    private final List<AiAttachments.Item> pending = new ArrayList<>();
    private final Map<String, ToolRow> toolRows = new HashMap<>();
    private boolean backupDone = false;
    private volatile boolean destroyed = false;

    private final Object confirmLock = new Object();
    private CountDownLatch confirmLatch;
    private AtomicBoolean confirmAnswer;

    private int colorSurface, colorOnSurface, colorMuted, colorPrimary, colorOnPrimary, colorBubble, colorOnBubble, colorBlock, colorError, colorOk, colorWarn;

    private final ActivityResultLauncher<String> pickFile = registerForActivityResult(
            new ActivityResultContracts.GetContent(), this::onPicked);

    // ------------------------------------------------------------------ setup

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            String scId = getIntent() == null ? null : getIntent().getStringExtra(EXTRA_SC_ID);
            guard = new AiProjectGuard(scId);
        } catch (Throwable t) {
            Log.e(TAG, "Invalid project", t);
            Toast.makeText(this, t("Invalid project: ", "Proyek tidak valid: ") + t.getMessage(), Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        try {
            attachments = new AiAttachments(this, guard.getScId());
            String pkg = "";
            try {
                HashMap<String, Object> meta = lC.b(guard.getScId());
                if (meta != null && meta.get("my_sc_pkg_name") != null) pkg = String.valueOf(meta.get("my_sc_pkg_name"));
            } catch (Throwable ignored) {
            }
            logs = new AiLogCollector(pkg);
            logs.start(this);
            agent = new AiAgent(this, guard, attachments, logs, this);
            try {
                markwon = Markwon.create(this);
            } catch (Throwable t) {
                markwon = null;
            }
            buildUi();
            if (!AiSettings.hasApiKey(this)) showSettings();
        } catch (Throwable t) {
            Log.e(TAG, "Failed to build UI", t);
            Toast.makeText(this, t("Failed to open assistant: ", "Gagal membuka asisten: ") + t.getMessage(), Toast.LENGTH_LONG).show();
            finish();
        }
    }

    private String t(String en, String id) {
        return AiStrings.t(this, en, id);
    }

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }

    private int attrColor(View v, int attr, int fallback) {
        try {
            return MaterialColors.getColor(v, attr, fallback);
        } catch (Throwable t) {
            return fallback;
        }
    }

    private GradientDrawable rounded(int color, float radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radiusDp));
        return d;
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        // only attributes that other screens of this app already use
        colorSurface = attrColor(root, pro.sketchware.R.attr.colorSurface, Color.WHITE);
        colorOnSurface = attrColor(root, com.google.android.material.R.attr.colorOnSurface, Color.BLACK);
        colorMuted = attrColor(root, pro.sketchware.R.attr.colorOnSurfaceVariant, Color.GRAY);
        colorPrimary = attrColor(root, pro.sketchware.R.attr.colorPrimary, 0xFF006A6A);
        colorOnPrimary = colorSurface;
        colorBubble = ColorUtils.setAlphaComponent(colorPrimary, 56);
        colorOnBubble = colorOnSurface;
        colorBlock = ColorUtils.setAlphaComponent(colorOnSurface, 22);
        colorError = 0xFFD32F2F;
        colorOk = 0xFF2E7D32;
        colorWarn = 0xFFB26A00;
        root.setBackgroundColor(colorSurface);

        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.ime());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });

        // toolbar
        MaterialToolbar toolbar = new MaterialToolbar(this);
        toolbar.setTitle(t("AI Assistant", "Asisten AI"));
        toolbar.setSubtitle(t("Project ", "Proyek ") + guard.getScId());
        try {
            toolbar.setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material);
            if (toolbar.getNavigationIcon() != null) toolbar.getNavigationIcon().setTint(colorOnSurface);
        } catch (Throwable ignored) {
        }
        toolbar.setNavigationOnClickListener(v -> finish());
        Menu menu = toolbar.getMenu();
        menu.add(Menu.NONE, 1, Menu.NONE, t("AI settings", "Pengaturan AI"));
        menu.add(Menu.NONE, 2, Menu.NONE, t("New conversation", "Percakapan baru"));
        menu.add(Menu.NONE, 3, Menu.NONE, t("Restore project backup", "Pulihkan cadangan proyek"));
        toolbar.setOnMenuItemClickListener(item -> {
            try {
                switch (item.getItemId()) {
                    case 1:
                        showSettings();
                        break;
                    case 2:
                        newConversation();
                        break;
                    case 3:
                        confirmRestore();
                        break;
                    default:
                        break;
                }
            } catch (Throwable t) {
                showError(t("Failed: ", "Gagal: ") + t.getMessage());
            }
            return true;
        });
        root.addView(toolbar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // banner
        banner = new TextView(this);
        banner.setTextSize(13);
        banner.setTextColor(colorMuted);
        banner.setPadding(dp(16), dp(6), dp(16), dp(6));
        banner.setVisibility(View.GONE);
        root.addView(banner, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // chat
        scroll = new ScrollView(this);
        scroll.setFillViewport(false);
        chat = new LinearLayout(this);
        chat.setOrientation(LinearLayout.VERTICAL);
        chat.setPadding(dp(14), dp(12), dp(14), dp(12));
        scroll.addView(chat, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // run bar
        runBar = new LinearLayout(this);
        runBar.setOrientation(LinearLayout.HORIZONTAL);
        runBar.setGravity(Gravity.CENTER_VERTICAL);
        runBar.setPadding(dp(16), dp(4), dp(8), dp(4));
        runBar.setBackground(rounded(colorBubble, 20));
        runText = new TextView(this);
        runText.setTextColor(colorOnBubble);
        runText.setTextSize(13);
        runBar.addView(runText, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView stop = new TextView(this);
        stop.setText(t("Stop", "Hentikan"));
        stop.setTextColor(colorError);
        stop.setTextSize(14);
        stop.setTypeface(Typeface.DEFAULT_BOLD);
        stop.setPadding(dp(16), dp(10), dp(16), dp(10));
        stop.setOnClickListener(v -> stopAgent());
        runBar.addView(stop, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        runBar.setVisibility(View.GONE);
        LinearLayout.LayoutParams runLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        runLp.setMargins(dp(12), dp(4), dp(12), dp(4));
        root.addView(runBar, runLp);

        // attachment chips
        chipScroll = new HorizontalScrollView(this);
        chipScroll.setHorizontalScrollBarEnabled(false);
        chipRow = new LinearLayout(this);
        chipRow.setOrientation(LinearLayout.HORIZONTAL);
        chipRow.setPadding(dp(12), dp(4), dp(12), dp(4));
        chipScroll.addView(chipRow);
        chipScroll.setVisibility(View.GONE);
        root.addView(chipScroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // composer
        LinearLayout composer = new LinearLayout(this);
        composer.setOrientation(LinearLayout.HORIZONTAL);
        composer.setGravity(Gravity.BOTTOM);
        composer.setPadding(dp(8), dp(6), dp(8), dp(8));

        MaterialButton plus = new MaterialButton(this);
        plus.setBackgroundTintList(android.content.res.ColorStateList.valueOf(colorBubble));
        plus.setTextColor(colorOnSurface);
        plus.setText("+");
        plus.setTextSize(22);
        plus.setInsetTop(0);
        plus.setInsetBottom(0);
        plus.setMinWidth(0);
        plus.setMinimumWidth(0);
        plus.setPadding(dp(14), 0, dp(14), 0);
        plus.setContentDescription(t("Add attachment", "Tambah lampiran"));
        plus.setOnClickListener(v -> showAttachSheet());
        LinearLayout.LayoutParams plusLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48));
        plusLp.setMargins(0, 0, dp(6), 0);
        composer.addView(plus, plusLp);

        input = new EditText(this);
        input.setHint(t("Describe the app or the change you want", "Jelaskan aplikasi atau perubahan yang kamu mau"));
        input.setTextColor(colorOnSurface);
        input.setHintTextColor(colorMuted);
        input.setTextSize(15);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setMaxLines(5);
        input.setMinHeight(dp(48));
        input.setPadding(dp(16), dp(10), dp(16), dp(10));
        input.setBackground(rounded(colorBlock, 24));
        composer.addView(input, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        sendButton = new MaterialButton(this);
        sendButton.setText(t("Send", "Kirim"));
        sendButton.setOnClickListener(v -> onSend());
        LinearLayout.LayoutParams sendLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48));
        sendLp.setMargins(dp(6), 0, 0, 0);
        composer.addView(sendButton, sendLp);
        root.addView(composer, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        setContentView(root);
    }

    // --------------------------------------------------------------- UI helpers

    private void ui(Runnable r) {
        runOnUiThread(() -> {
            if (destroyed || isFinishing()) return;
            try {
                r.run();
            } catch (Throwable t) {
                Log.e(TAG, "UI update failed", t);
            }
        });
    }

    private void toBottom() {
        scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
    }

    private void toast(String msg) {
        try {
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
        } catch (Throwable ignored) {
        }
    }

    private void addNote(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(12);
        tv.setTextColor(colorMuted);
        tv.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(4), 0, dp(10));
        chat.addView(tv, lp);
        toBottom();
    }

    private void addUserBubble(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(15);
        tv.setTextColor(colorOnBubble);
        tv.setPadding(dp(14), dp(10), dp(14), dp(10));
        tv.setBackground(rounded(colorBubble, 18));
        tv.setTextIsSelectable(true);
        tv.setMaxWidth((int) (getResources().getDisplayMetrics().widthPixels * 0.82f));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.END;
        lp.setMargins(0, dp(6), 0, dp(10));
        chat.addView(tv, lp);
        toBottom();
    }

    private void addAiBubble(String text) {
        TextView tv = new TextView(this);
        tv.setTextSize(15);
        tv.setTextColor(colorOnSurface);
        tv.setTextIsSelectable(true);
        try {
            if (markwon != null) markwon.setMarkdown(tv, text);
            else tv.setText(text);
        } catch (Throwable t) {
            tv.setText(text);
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(6), 0, dp(10));
        chat.addView(tv, lp);
        toBottom();
    }

    private void showError(String message) {
        try {
            TextView tv = new TextView(this);
            tv.setText(message);
            tv.setTextSize(14);
            tv.setTextColor(colorError);
            tv.setTextIsSelectable(true);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(0, dp(6), 0, dp(10));
            chat.addView(tv, lp);
            toBottom();
        } catch (Throwable t) {
            toast(message);
        }
    }

    /**
     * One tool call in the chat: a colored bar, a title line, the real tool output (always visible, a short preview that
     * expands on tap) and, after a successful build, an Install APK button.
     */
    private final class ToolRow {
        final LinearLayout box = new LinearLayout(AiChatActivity.this);
        final View bar = new View(AiChatActivity.this);
        final TextView title = new TextView(AiChatActivity.this);
        final TextView out = new TextView(AiChatActivity.this);
        final TextView apk = new TextView(AiChatActivity.this);
        final String name;
        final String summary;
        final int previewLines = 14;
        String status = "";
        boolean expanded;
        boolean hasMore;

        ToolRow(String name, String summary) {
            this.name = name;
            this.summary = summary == null ? "" : summary;
            box.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout col = new LinearLayout(AiChatActivity.this);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setPadding(dp(10), dp(2), 0, dp(2));
            title.setTextSize(13);
            title.setTypeface(Typeface.MONOSPACE);
            title.setTextColor(colorOnSurface);
            title.setPadding(0, dp(4), 0, dp(4));
            out.setTextSize(12);
            out.setTypeface(Typeface.MONOSPACE);
            out.setTextColor(colorOnSurface);
            out.setBackground(rounded(colorBlock, 8));
            out.setPadding(dp(10), dp(8), dp(10), dp(8));
            out.setTextIsSelectable(true);
            out.setVisibility(View.GONE);
            View.OnClickListener toggle = v -> {
                if (hasMore) {
                    expanded = !expanded;
                    applyExpansion();
                    setStatus(status, currentColor);
                }
            };
            title.setOnClickListener(toggle);
            out.setOnClickListener(toggle);
            apk.setText(t("Install APK", "Pasang APK"));
            apk.setTextSize(14);
            apk.setTypeface(Typeface.DEFAULT_BOLD);
            apk.setTextColor(colorOnPrimary);
            apk.setBackground(rounded(colorPrimary, 20));
            apk.setPadding(dp(16), dp(10), dp(16), dp(10));
            apk.setVisibility(View.GONE);
            col.addView(title);
            col.addView(out);
            LinearLayout.LayoutParams apkLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            apkLp.topMargin = dp(6);
            col.addView(apk, apkLp);
            box.addView(bar, new LinearLayout.LayoutParams(dp(3), ViewGroup.LayoutParams.MATCH_PARENT));
            box.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            setStatus(t("running", "berjalan"), colorPrimary);
        }

        int currentColor;

        void setStatus(String newStatus, int color) {
            status = newStatus;
            currentColor = color;
            bar.setBackgroundColor(color);
            String arrow = hasMore ? (expanded ? "  \u25B2" : "  \u25BC") : "";
            title.setText(name + (summary.isEmpty() ? "" : "  " + summary) + "  [" + newStatus + "]" + arrow);
        }

        void applyExpansion() {
            out.setMaxLines(expanded ? Integer.MAX_VALUE : previewLines);
            out.setEllipsize(expanded ? null : android.text.TextUtils.TruncateAt.END);
        }

        void finish(boolean ok, String output) {
            String o = output == null ? "" : output;
            boolean canceled = o.startsWith("BUILD_CANCELED");
            int lines = o.isEmpty() ? 0 : o.split("\n", -1).length;
            hasMore = lines > previewLines;
            expanded = !ok;
            setStatus(ok ? t("done", "berhasil") : canceled ? t("cancelled", "dibatalkan") : t("failed", "gagal"),
                    ok ? colorOk : canceled ? colorWarn : colorError);
            out.setText(o);
            out.setVisibility(o.isEmpty() ? View.GONE : View.VISIBLE);
            applyExpansion();
            if (ok && "build_project".equals(name)) showInstall(o);
        }

        void showInstall(String o) {
            for (String line : o.split("\n")) {
                if (line.startsWith("APK_PATH: ")) {
                    final File f = new File(line.substring("APK_PATH: ".length()).trim());
                    if (f.isFile()) {
                        apk.setVisibility(View.VISIBLE);
                        apk.setOnClickListener(v -> installApk(f));
                    }
                    return;
                }
            }
        }
    }

    private void installApk(File apkFile) {
        try {
            Uri uri = FileProvider.getUriForFile(getApplicationContext(), getApplicationContext().getPackageName() + ".provider", apkFile);
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(uri, "application/vnd.android.package-archive");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(intent);
        } catch (Throwable t) {
            toast(t("Could not open the installer: ", "Gagal membuka pemasang: ") + t.getMessage());
        }
    }

    // ----------------------------------------------------------------- sending

    private void setRunning(boolean running) {
        runBar.setVisibility(running ? View.VISIBLE : View.GONE);
        sendButton.setEnabled(!running);
        input.setEnabled(!running);
    }

    private void onSend() {
        try {
            if (agent.isRunning()) return;
            String text = input.getText().toString().trim();
            if (text.isEmpty() && pending.isEmpty()) return;
            if (!AiSettings.hasApiKey(this)) {
                toast(t("Enter an API key first", "Isi API key dulu"));
                showSettings();
                return;
            }
            final String sendText = text;
            final List<AiAttachments.Item> items = new ArrayList<>(pending);
            setRunning(true);
            runText.setText(t("Preparing project backup...", "Menyiapkan cadangan proyek..."));

            if (backupDone) {
                dispatch(sendText, items);
                return;
            }
            new Thread(() -> {
                try {
                    File zip = guard.createBackup();
                    backupDone = true;
                    ui(() -> {
                        banner.setText(t("Project backup created: ", "Cadangan proyek dibuat: ") + zip.getName());
                        banner.setVisibility(View.VISIBLE);
                        dispatch(sendText, items);
                    });
                } catch (Throwable t) {
                    Log.e(TAG, "Backup failed", t);
                    ui(() -> {
                        setRunning(false);
                        showError(t("Project backup failed, assistant not started for safety: ", "Cadangan proyek gagal, asisten tidak dijalankan demi keamanan: ") + t.getMessage());
                    });
                }
            }, "ai-backup").start();
        } catch (Throwable t) {
            setRunning(false);
            showError(t("Failed to send: ", "Gagal mengirim: ") + t.getMessage());
        }
    }

    private void dispatch(String text, List<AiAttachments.Item> items) {
        try {
            StringBuilder shown = new StringBuilder(text);
            for (AiAttachments.Item i : items) {
                if (shown.length() > 0) shown.append('\n');
                shown.append("[").append(i.id).append(": ").append(i.displayName).append("]");
            }
            addUserBubble(shown.toString());
            input.setText("");
            pending.clear();
            refreshChips();
            runText.setText(t("Step 0 of ", "Langkah 0 dari ") + AiSettings.getMaxSteps(this));
            if (!agent.start(text, items)) {
                setRunning(false);
            }
        } catch (Throwable t) {
            setRunning(false);
            showError(t("Failed to start: ", "Gagal memulai: ") + t.getMessage());
        }
    }

    private void stopAgent() {
        try {
            agent.stop();
            releaseConfirm(false);
            runText.setText(t("Stopping...", "Menghentikan..."));
        } catch (Throwable t) {
            Log.e(TAG, "stop failed", t);
        }
    }

    private void newConversation() {
        if (agent.isRunning()) {
            toast(t("Wait until it finishes or stop it first", "Tunggu selesai atau hentikan dulu"));
            return;
        }
        agent.resetConversation();
        attachments.clear();
        pending.clear();
        toolRows.clear();
        chat.removeAllViews();
        refreshChips();
    }

    private void confirmRestore() {
        if (agent.isRunning()) {
            toast(t("Wait for the assistant to finish", "Tunggu asisten selesai dulu"));
            return;
        }
        File latest = guard.getLatestBackup();
        if (latest == null) {
            toast(t("No backup for this project yet", "Belum ada cadangan untuk proyek ini"));
            return;
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle(t("Restore backup?", "Pulihkan cadangan?"))
                .setMessage(t("Project files will be restored to " + latest.getName() + ". Files changed since then will be overwritten.",
                        "File proyek akan dikembalikan ke " + latest.getName() + ". File yang berubah setelahnya akan ditimpa."))
                .setNegativeButton(t("Cancel", "Batal"), null)
                .setPositiveButton(t("Restore", "Pulihkan"), (d, w) -> new Thread(() -> {
                    try {
                        int count = guard.restoreLatestBackup();
                        ui(() -> addNote(t("Backup restored (" + count + " files).", "Cadangan dipulihkan (" + count + " file).")));
                    } catch (Throwable t) {
                        ui(() -> showError(t("Restore failed: ", "Gagal memulihkan: ") + t.getMessage()));
                    }
                }, "ai-restore").start())
                .show();
    }

    // ------------------------------------------------------------- attachments

    private void showAttachSheet() {
        try {
            BottomSheetDialog sheet = new BottomSheetDialog(this);
            LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setPadding(dp(8), dp(12), dp(8), dp(20));
            String[][] options = {{t("Image", "Gambar"), "image/*"}, {t("File", "File"), "*/*"}};
            for (String[] option : options) {
                TextView row = new TextView(this);
                row.setText(option[0]);
                row.setTextSize(16);
                row.setTextColor(colorOnSurface);
                row.setPadding(dp(20), dp(16), dp(20), dp(16));
                row.setOnClickListener(v -> {
                    sheet.dismiss();
                    try {
                        pickFile.launch(option[1]);
                    } catch (Throwable t) {
                        toast(t("Can't open the file picker", "Tidak bisa membuka pemilih file"));
                    }
                });
                col.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            }
            sheet.setContentView(col);
            sheet.show();
        } catch (Throwable t) {
            showError(t("Failed to open attachment menu: ", "Gagal membuka menu lampiran: ") + t.getMessage());
        }
    }

    private void onPicked(Uri uri) {
        if (uri == null) return;
        final Context context = this;
        new Thread(() -> {
            try {
                AiAttachments.Item item = attachments.add(context, uri);
                ui(() -> {
                    pending.add(item);
                    refreshChips();
                });
            } catch (Throwable t) {
                Log.e(TAG, "Attach failed", t);
                ui(() -> toast(t("Failed to attach: ", "Gagal melampirkan: ") + t.getMessage()));
            }
        }, "ai-attach").start();
    }

    private void refreshChips() {
        chipRow.removeAllViews();
        for (AiAttachments.Item item : pending) {
            TextView chip = new TextView(this);
            chip.setText(item.id + "  " + item.displayName + "  \u2715");
            chip.setTextSize(13);
            chip.setTextColor(colorOnBubble);
            chip.setBackground(rounded(colorBubble, 16));
            chip.setPadding(dp(12), dp(6), dp(12), dp(6));
            chip.setOnClickListener(v -> {
                pending.remove(item);
                attachments.remove(item.id);
                refreshChips();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(0, 0, dp(8), 0);
            chipRow.addView(chip, lp);
        }
        chipScroll.setVisibility(pending.isEmpty() ? View.GONE : View.VISIBLE);
    }

    // ---------------------------------------------------------------- settings

    /** Fills the model spinner with the models of one provider and selects the saved one. */
    private void fillModelSpinner(Spinner modelSpinner, AiProvider provider) {
        String saved = AiSettings.getModelFor(this, provider);
        String[] labels = new String[provider.models.length];
        int selected = 0;
        for (int i = 0; i < provider.models.length; i++) {
            labels[i] = provider.models[i].label;
            if (provider.models[i].id.equals(saved)) selected = i;
        }
        modelSpinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, labels));
        modelSpinner.setSelection(selected);
    }

    private String keyHintFor(AiProvider provider) {
        if (AiSettings.hasApiKeyFor(this, provider)) return t("Already saved. Leave empty to keep it", "Sudah tersimpan. Kosongkan jika tidak diubah");
        switch (provider) {
            case GEMINI:
                return "AIza...";
            case GPT:
                return "sk-...";
            case CLAUDE:
                return "sk-ant-...";
            default:
                return t("Paste API key", "Tempel API key");
        }
    }

    private void showSettings() {
        try {
            LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setPadding(dp(24), dp(12), dp(24), 0);

            final AiProvider[] providers = AiProvider.values();
            final AiProvider currentProvider = AiSettings.getProvider(this);

            TextView providerLabel = new TextView(this);
            providerLabel.setText("Provider");
            col.addView(providerLabel);
            Spinner providerSpinner = new Spinner(this);
            String[] providerNames = new String[providers.length];
            for (int i = 0; i < providers.length; i++) providerNames[i] = providers[i].displayName;
            providerSpinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, providerNames));
            providerSpinner.setSelection(Math.max(0, Arrays.asList(providers).indexOf(currentProvider)));
            col.addView(providerSpinner);

            TextView modelLabel = new TextView(this);
            modelLabel.setText("Model");
            modelLabel.setPadding(0, dp(12), 0, 0);
            col.addView(modelLabel);
            Spinner spinner = new Spinner(this);
            fillModelSpinner(spinner, currentProvider);
            col.addView(spinner);

            TextView keyLabel = new TextView(this);
            keyLabel.setText("API key");
            keyLabel.setPadding(0, dp(12), 0, 0);
            col.addView(keyLabel);
            EditText key = new EditText(this);
            key.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
            key.setHint(keyHintFor(currentProvider));
            key.setSingleLine(true);
            col.addView(key);
            providerSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override
                public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                    AiProvider p = providers[position];
                    fillModelSpinner(spinner, p);
                    key.setHint(keyHintFor(p));
                }

                @Override
                public void onNothingSelected(AdapterView<?> parent) {
                }
            });
            TextView keyHelp = new TextView(this);
            keyHelp.setText(t("The key is stored encrypted on this device.", "Key disimpan terenkripsi di perangkat ini."));
            keyHelp.setTextSize(12);
            keyHelp.setTextColor(colorMuted);
            col.addView(keyHelp);

            TextView languageLabel = new TextView(this);
            languageLabel.setText(t("Language", "Bahasa"));
            languageLabel.setPadding(0, dp(12), 0, 0);
            col.addView(languageLabel);
            final String[] languageCodes = {AiSettings.LANG_EN, AiSettings.LANG_ID, AiSettings.LANG_AUTO};
            String[] languageNames = {"English", "Bahasa Indonesia", t("Follow device", "Ikuti perangkat")};
            Spinner languageSpinner = new Spinner(this);
            languageSpinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, languageNames));
            languageSpinner.setSelection(Math.max(0, Arrays.asList(languageCodes).indexOf(AiSettings.getLanguage(this))));
            col.addView(languageSpinner);

            TextView stepsLabel = new TextView(this);
            stepsLabel.setText(t("Step limit per task (" + AiSettings.MIN_STEPS + " to " + AiSettings.MAX_STEPS + ")",
                    "Batas langkah per tugas (" + AiSettings.MIN_STEPS + " sampai " + AiSettings.MAX_STEPS + ")"));
            stepsLabel.setPadding(0, dp(12), 0, 0);
            col.addView(stepsLabel);
            EditText steps = new EditText(this);
            steps.setInputType(InputType.TYPE_CLASS_NUMBER);
            steps.setText(String.valueOf(AiSettings.getMaxSteps(this)));
            col.addView(steps);

            CheckBox askBuild = new CheckBox(this);
            askBuild.setText(t("Ask permission before every build", "Minta izin sebelum setiap build"));
            askBuild.setChecked(AiSettings.isAskBeforeBuild(this));
            col.addView(askBuild);

            new MaterialAlertDialogBuilder(this)
                    .setTitle(t("AI settings", "Pengaturan AI"))
                    .setView(col)
                    .setNegativeButton(t("Cancel", "Batal"), null)
                    .setNeutralButton(t("Delete key", "Hapus key"), (d, w) -> {
                        AiProvider target = providers[Math.max(0, providerSpinner.getSelectedItemPosition())];
                        AiSettings.setApiKeyFor(this, target, "");
                        toast(t("API key deleted: ", "API key dihapus: ") + target.displayName);
                    })
                    .setPositiveButton(t("Save", "Simpan"), (d, w) -> {
                        try {
                            AiProvider chosen = providers[Math.max(0, providerSpinner.getSelectedItemPosition())];
                            AiSettings.setProvider(this, chosen);
                            int modelIndex = spinner.getSelectedItemPosition();
                            if (modelIndex >= 0 && modelIndex < chosen.models.length) {
                                AiSettings.setModelFor(this, chosen, chosen.models[modelIndex].id);
                            }
                            String newKey = key.getText().toString().trim();
                            if (!newKey.isEmpty() && !AiSettings.setApiKeyFor(this, chosen, newKey)) {
                                toast(t("Failed to save key", "Gagal menyimpan key"));
                                return;
                            }
                            try {
                                AiSettings.setMaxSteps(this, Integer.parseInt(steps.getText().toString().trim()));
                            } catch (NumberFormatException e) {
                                toast(t("Invalid step limit, not changed", "Batas langkah tidak valid, tidak diubah"));
                            }
                            AiSettings.setAskBeforeBuild(this, askBuild.isChecked());
                            String oldLanguage = AiSettings.getLanguage(this);
                            String newLanguage = languageCodes[Math.max(0, languageSpinner.getSelectedItemPosition())];
                            AiSettings.setLanguage(this, newLanguage);
                            if (!oldLanguage.equals(newLanguage)) {
                                toast(t("Settings saved. Reopen the assistant to apply the language everywhere.",
                                        "Pengaturan disimpan. Buka ulang asisten agar bahasa berlaku di semua tampilan."));
                            } else {
                                toast(t("Settings saved", "Pengaturan disimpan"));
                            }
                        } catch (Throwable t) {
                            toast(t("Failed to save: ", "Gagal menyimpan: ") + t.getMessage());
                        }
                    })
                    .show();
        } catch (Throwable t) {
            showError(t("Failed to open settings: ", "Gagal membuka pengaturan: ") + t.getMessage());
        }
    }

    // ------------------------------------------------------- AiAgent.Listener

    @Override
    public void onAssistantText(String text) {
        ui(() -> addAiBubble(text));
    }

    @Override
    public void onToolStart(String callId, String name, String summary) {
        ui(() -> {
            ToolRow row = new ToolRow(name, summary);
            toolRows.put(callId, row);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(dp(4), dp(2), 0, dp(6));
            chat.addView(row.box, lp);
            toBottom();
        });
    }

    @Override
    public void onToolEnd(String callId, boolean ok, String output) {
        ui(() -> {
            ToolRow row = toolRows.get(callId);
            if (row != null) row.finish(ok, output);
        });
    }

    @Override
    public void onStep(int used, int max) {
        ui(() -> runText.setText(t("Step " + used + " of " + max, "Langkah " + used + " dari " + max)));
    }

    @Override
    public void onError(String message) {
        ui(() -> showError(message));
    }

    @Override
    public boolean confirm(String title, String text) {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicBoolean answer = new AtomicBoolean(false);
        synchronized (confirmLock) {
            confirmLatch = latch;
            confirmAnswer = answer;
        }
        runOnUiThread(() -> {
            if (destroyed || isFinishing()) {
                releaseConfirm(false);
                return;
            }
            try {
                new MaterialAlertDialogBuilder(this)
                        .setTitle(t("Permission required", "Izin diperlukan"))
                        .setMessage(text)
                        .setCancelable(false)
                        .setNegativeButton(t("Deny", "Tolak"), (d, w) -> releaseConfirm(false))
                        .setPositiveButton(t("Allow", "Izinkan"), (d, w) -> releaseConfirm(true))
                        .show();
            } catch (Throwable t) {
                releaseConfirm(false);
            }
        });
        try {
            if (!latch.await(10, TimeUnit.MINUTES)) return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
        return answer.get();
    }

    private void releaseConfirm(boolean allowed) {
        synchronized (confirmLock) {
            if (confirmLatch != null) {
                confirmAnswer.set(allowed);
                confirmLatch.countDown();
                confirmLatch = null;
            }
        }
    }

    @Override
    public void onFinished(String note) {
        ui(() -> {
            setRunning(false);
            if ("dihentikan".equals(note)) addNote(t("Stopped.", "Dihentikan."));
            else if (note != null && note.startsWith("batas langkah")) addNote(t("Step limit reached. Send another message to continue.", "Batas langkah tercapai. Kirim pesan lagi untuk melanjutkan."));
            else if (note != null && note.startsWith("jawaban terpotong")) addNote(t("The answer was cut off. Send 'continue' to resume.", "Jawaban terpotong. Kirim 'lanjutkan' untuk meneruskan."));
        });
    }

    // --------------------------------------------------------------- lifecycle

    @Override
    protected void onDestroy() {
        destroyed = true;
        try {
            releaseConfirm(false);
            if (agent != null) agent.close();
            if (logs != null) logs.stop();
            if (attachments != null) attachments.clear();
        } catch (Throwable t) {
            Log.e(TAG, "onDestroy cleanup failed", t);
        }
        super.onDestroy();
    }
}
