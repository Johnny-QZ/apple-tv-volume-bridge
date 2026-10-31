import local.cecprobe.BridgeConfig;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Restores the forwarding UI when the stock source picker selects Apple TV. */
public final class SourceWatcher {
    private static final AtomicBoolean STARTED = new AtomicBoolean(false);
    private static final long POLL_MS = 2000;
    private static final int MAX_OUTPUT_BYTES = 262144;
    private static final Pattern RESUMED = Pattern.compile("(?m)^.*mResumedActivity[^\\r\\n]*");
    private static final Pattern ROUTE = Pattern.compile("mActiveRoutingPath:\\s*(0x[0-9a-fA-F]+)");

    private SourceWatcher() {}

    public static void start() {
        if (!STARTED.compareAndSet(false, true)) return;
        Thread thread = new Thread(new Runnable() {
            public void run() { watch(); }
        }, "CECSourceWatcher");
        thread.setDaemon(true);
        thread.start();
    }

    private static void watch() {
        boolean handled = false;
        long retryAfter = 0;
        String lastError = "";
        while (!Thread.currentThread().isInterrupted()) {
            long started = monotonicMs();
            try {
                Result activity = command(new String[] { "/system/bin/dumpsys", "activity", "activities" });
                if (!activity.ok) throw new Exception("activity query " + activity.reason);
                Matcher resumed = RESUMED.matcher(activity.text);
                String foreground = null;
                // A missing/incomplete snapshot does not count as a source transition.
                while (resumed.find()) foreground = resumed.group();
                if (foreground != null) {
                    if (foreground.contains("local.cecprobe/")) {
                        handled = true;
                        retryAfter = 0;
                    } else if (foreground.contains(BridgeConfig.STOCK_PLAYER_PACKAGE+"/")) {
                        Result hdmi = command(new String[] { "/system/bin/dumpsys", "hdmi_control" });
                        if (!hdmi.ok) throw new Exception("HDMI query " + hdmi.reason);
                        Matcher route = ROUTE.matcher(hdmi.text);
                        if (route.find()) {
                            boolean appleTv = Integer.parseInt(route.group(1).substring(2), 16) == BridgeConfig.PHYSICAL_ADDRESS;
                            if (!appleTv) {
                                handled = false;
                                retryAfter = 0;
                            } else if (!handled && monotonicMs() >= retryAfter) {
                                Result launch = command(new String[] { "/system/bin/am", "start", "-n", "local.cecprobe/.MainActivity" });
                                if (launch.ok && !launch.text.contains("Error:") && !launch.text.contains("Exception")) {
                                    handled = true;
                                    System.out.println("SOURCE_WATCHER_RESTORE_TARGET");
                                } else {
                                    retryAfter = monotonicMs() + 8000;
                                    throw new Exception("launch " + launch.reason);
                                }
                            }
                        }
                    } else {
                        // Home or another app: the next configured HDMI selection is new.
                        handled = false;
                        retryAfter = 0;
                    }
                }
                lastError = "";
            } catch (Throwable error) {
                String reason = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
                if (!reason.equals(lastError)) {
                    System.out.println("SOURCE_WATCHER_WAIT " + reason);
                    lastError = reason;
                }
            }
            try {
                Thread.sleep(Math.max(200, POLL_MS - (monotonicMs() - started)));
            } catch (InterruptedException stopped) {
                Thread.currentThread().interrupt();
            }
        }
        STARTED.set(false);
    }

    private static long monotonicMs() { return System.nanoTime() / 1000000L; }

    private static final class Result {
        final boolean ok;
        final String text, reason;
        Result(boolean ok, String text, String reason) { this.ok = ok; this.text = text; this.reason = reason; }
    }

    /** Direct processes, bounded duration and bounded capture; no shell pipeline. */
    private static Result command(String[] args) throws Exception {
        final Process process = new ProcessBuilder(args).redirectErrorStream(true).start();
        final ByteArrayOutputStream captured = new ByteArrayOutputStream();
        final AtomicBoolean overflow = new AtomicBoolean(false);
        final AtomicBoolean readFailed = new AtomicBoolean(false);
        Thread drain = new Thread(new Runnable() {
            public void run() {
                try {
                    InputStream stream = process.getInputStream();
                    byte[] bytes = new byte[4096];
                    int count;
                    while ((count = stream.read(bytes)) != -1) {
                        synchronized (captured) {
                            if (captured.size() + count > MAX_OUTPUT_BYTES) {
                                overflow.set(true);
                                process.destroy();
                                return;
                            }
                            captured.write(bytes, 0, count);
                        }
                    }
                } catch (Exception error) { readFailed.set(true); }
            }
        }, "CECSourceQuery");
        drain.setDaemon(true);
        drain.start();
        try {
            boolean exited = process.waitFor(2000, TimeUnit.MILLISECONDS);
            if (!exited) {
                process.destroy();
                if (!process.waitFor(150, TimeUnit.MILLISECONDS)) process.destroyForcibly();
                return new Result(false, "", "timed out");
            }
            drain.join(150);
            if (drain.isAlive()) return new Result(false, "", "incomplete output");
            if (overflow.get()) return new Result(false, "", "output limit");
            if (readFailed.get()) return new Result(false, "", "read failed");
            String output;
            synchronized (captured) { output = captured.toString("UTF-8"); }
            int exit = process.exitValue();
            return new Result(exit == 0, output, "exit " + exit);
        } finally {
            process.destroy();
        }
    }
}
