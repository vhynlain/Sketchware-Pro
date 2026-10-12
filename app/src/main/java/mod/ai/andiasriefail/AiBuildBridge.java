package mod.ai.andiasriefail;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Connects the AI tools to the build that runs inside DesignActivity. The activity registers a
 * runner for its project; the tool asks that runner to build and waits for the result.
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
     * Starts a build for the project and blocks until it finishes (or the timeout hits).
     * Must NOT be called on the main thread.
     */
    public static String buildAndWait(String scId, long timeoutSeconds) throws Exception {
        Runner current = runner;
        if (current == null || scId == null || !scId.equals(runnerScId)) {
            throw new IllegalStateException("Build tidak tersedia. Buka proyek ini di editor lalu buka lagi Asisten AI.");
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
            return "BUILD_TIMEOUT: build belum selesai setelah " + timeoutSeconds + " detik.";
        }
        return (success[0] ? "BUILD_SUCCESS" : "BUILD_FAILED") + (message[0].isEmpty() ? "" : "\n" + message[0]);
    }
}
