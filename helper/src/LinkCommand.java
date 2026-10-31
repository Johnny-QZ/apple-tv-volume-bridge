import java.io.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
final class LinkCommand {
static final int MAX_OUTPUT_BYTES=262144;
    static final class Result {
        final boolean ok;
        final String text, reason;
        Result(boolean ok, String text, String reason) { this.ok = ok; this.text = text; this.reason = reason; }
    }

    /** Direct processes, bounded duration and bounded capture; no shell pipeline. */
    static Result command(String[] args) throws Exception {
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
