package mod.ai.andiasriefail;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import com.besome.sketch.design.DesignActivity;

import mod.hey.studios.project.ProjectTracker;

/**
 * Connects the AI tools to the build that runs inside DesignActivity. The activity registers a
 * runner for its project; the tool asks that runner to build and waits for the result.
 *
 * The runner lives in the editor activity. If Android killed the app process while the chat was open, only the
 * chat is restored and the editor is gone; {@link #ensureEditor} reopens the editor of the project in that case.
 */
public final class AiBuildBridge {

    public interface Listener {
        void onFinished(boolean success, String message);
    }

    public interface Runner {
        void start(Listener listener);
    }

    private static volatile Runner runner;
    private static volatile String runnerScId;

    private AiBuildBridge() {
    }

    public static void register(String scId, Runner newRunner) {
        runnerScId = scId;
        runner = newRunner;
    }

    public static void unregister(Runner oldRunner) {
        if (runner == oldRunner) {
            runner = null;
            runnerScId = null;
        }
    }

    public static boolean isAvailableFor(String scId) {
        return runner != null && scId != null && scId.equals(runnerScId);
    }

    /**
     * Makes sure the editor of this project is running so a build can start. When it is not, the editor is opened again
     * and the chat is brought back to the front once the editor has registered. Must NOT run on the main thread.
     *
     * @return true when a build runner is available afterwards
     */
    public static boolean ensureEditor(Context context, String scId) {
        if (isAvailableFor(scId)) return true;
        if (context == null || scId == null) return false;
        try {
            ProjectTracker.setScId(scId);
            Intent open = new Intent(context, DesignActivity.class);
            open.putExtra("sc_id", scId);
            open.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
            if (!(context instanceof Activity)) open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(open);

            long deadline = System.currentTimeMillis() + 45_000;
            while (System.currentTimeMillis() < deadline) {
                if (isAvailableFor(scId)) {
                    Intent back = new Intent(context, AiChatActivity.class);
                    back.putExtra(AiChatActivity.EXTRA_SC_ID, scId);
                    back.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                    if (!(context instanceof Activity)) back.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    context.startActivity(back);
                    return true;
                }
                Thread.sleep(300);
            }
        } catch (Throwable ignored) {
        }
        return isAvailableFor(scId);
    }

    /**
     * Starts a build for the project and blocks until it finishes (or the timeout hits).
     * Must NOT be called on the main thread.
     *
     * @return text starting with BUILD_SUCCESS, BUILD_FAILED, BUILD_CANCELED or BUILD_TIMEOUT
     */
    public static String buildAndWait(String scId, long timeoutSeconds) throws Exception {
        Runner current = runner;
        if (current == null || scId == null || !scId.equals(runnerScId)) {
            throw new IllegalStateException("The Sketchware editor of this project is not running, so there is no build runner. "
                    + "This is not a compile error. It usually happens after Android closed the editor in the background (low memory). "
                    + "Ask the user to open the project in the editor and reopen the AI assistant, then call build_project again.");
        }
        CountDownLatch latch = new CountDownLatch(1);
        final boolean[] success = {false};
        final String[] message = {""};
        current.start((ok, msg) -> {
            success[0] = ok;
            message[0] = msg == null ? "" : msg;
            latch.countDown();
        });
        if (!latch.await(timeoutSeconds, TimeUnit.SECONDS)) {
            return "BUILD_TIMEOUT: the build did not finish within " + timeoutSeconds + " seconds. It may still be running; check get_build_log later.";
        }
        String text = message[0];
        if (success[0]) return "BUILD_SUCCESS" + (text.isEmpty() ? "" : "\n" + text);
        if (text.startsWith("Build was cancelled")) return "BUILD_CANCELED\n" + text;
        return "BUILD_FAILED" + (text.isEmpty() ? "" : "\n" + text);
    }
}
