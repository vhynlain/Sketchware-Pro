package mod.ai.andiasriefail;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.util.Log;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Collects the debug log lines that apps built by Sketchware broadcast while "Debug logcat" is on.
 * It only buffers lines from the package of the open project.
 */
public final class AiLogCollector {

    private static final String ACTION = "pro.sketchware.ACTION_NEW_DEBUG_LOG";
    private static final int MAX_LINES = 600;

    private final ArrayDeque<String> lines = new ArrayDeque<>();
    private final String packageName;
    private BroadcastReceiver receiver;
    private Context registeredIn;

    public AiLogCollector(String packageName) {
        this.packageName = packageName == null ? "" : packageName;
    }

    public synchronized void start(Context context) {
        if (receiver != null) return;
        try {
            receiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context c, Intent intent) {
                    try {
                        String log = intent.getStringExtra("log");
                        if (log == null) return;
                        String pkg = intent.getStringExtra("packageName");
                        if (pkg != null && !packageName.isEmpty() && !pkg.equals(packageName)) return;
                        add(log);
                    } catch (Throwable t) {
                        Log.w("AiLogCollector", "Bad log broadcast", t);
                    }
                }
            };
            IntentFilter filter = new IntentFilter(ACTION);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
            } else {
                context.registerReceiver(receiver, filter);
            }
            registeredIn = context;
        } catch (Throwable t) {
            Log.e("AiLogCollector", "Failed to register receiver", t);
            receiver = null;
        }
    }

    public synchronized void stop() {
        try {
            if (receiver != null && registeredIn != null) registeredIn.unregisterReceiver(receiver);
        } catch (Throwable ignored) {
        }
        receiver = null;
        registeredIn = null;
    }

    private synchronized void add(String line) {
        if (lines.size() >= MAX_LINES) lines.pollFirst();
        lines.addLast(line);
    }

    public synchronized void clear() {
        lines.clear();
    }

    public synchronized int size() {
        return lines.size();
    }

    /**
     * @return error-looking lines (with a bit of context) or, if none, the last lines
     */
    public synchronized String summary(int maxLines) {
        if (lines.isEmpty()) return "";
        List<String> all = new ArrayList<>(lines);
        List<String> picked = new ArrayList<>();
        for (int i = 0; i < all.size(); i++) {
            String lower = all.get(i).toLowerCase(Locale.ROOT);
            if (lower.contains("fatal exception") || lower.contains("androidruntime")
                    || lower.contains(" e ") || lower.contains("exception") || lower.contains("\tat ")) {
                picked.add(all.get(i));
            }
        }
        List<String> source = picked.isEmpty() ? all : picked;
        int from = Math.max(0, source.size() - maxLines);
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < source.size(); i++) sb.append(source.get(i)).append('\n');
        return sb.toString();
    }
}
