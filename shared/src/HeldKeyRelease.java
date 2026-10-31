import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Claims each injected key-up once, including a bounded best-effort exit path. */
public final class HeldKeyRelease {
    public interface Sender { void keyUp(int key, long downTime) throws Exception; }

    public static final class Key {
        public final int code;
        public final long downTime;
        private Key(int code, long downTime) { this.code = code; this.downTime = downTime; }
    }

    private final Map<Integer, Key> held = new LinkedHashMap<Integer, Key>();
    private volatile boolean stopping;

    /** Register before submitting DOWN, so a blocked injection is still known. */
    public synchronized boolean trackDown(int key, long downTime) {
        if (stopping) return false;
        if (!held.containsKey(key)) held.put(key, new Key(key, downTime));
        return true;
    }

    /** Physical UP and cleanup must claim before submitting their UP event. */
    public synchronized Key claimUp(int key) { return held.remove(key); }

    public boolean isStopping() { return stopping; }

    private synchronized Key claimFirst() {
        if (held.isEmpty()) return null;
        Integer first = held.keySet().iterator().next();
        return held.remove(first);
    }

    /** Normal cleanup does not prevent a later reconnect from tracking new keys. */
    public void releaseAll(Sender sender) {
        Key key;
        while ((key = claimFirst()) != null) {
            try { sender.keyUp(key.code, key.downTime); } catch (Exception ignored) { }
        }
    }

    private synchronized List<Key> stopAndClaimAll() {
        stopping = true;
        List<Key> keys = new ArrayList<Key>(held.values());
        held.clear();
        return keys;
    }

    /**
     * Permanently stop tracking and attempt each remaining UP on a daemon thread.
     * One blocked Android injection cannot prevent another key's release, and no
     * worker can keep the process alive. The caller must still force exit even
     * when this returns false. An already blocked DOWN cannot be undone reliably.
     */
    public boolean releaseBeforeExit(final Sender sender, long timeoutMs) {
        if (timeoutMs < 0 || timeoutMs > 1000) throw new IllegalArgumentException("Exit release deadline must be 0..1000 ms");
        final long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        final List<Key> keys = stopAndClaimAll();
        final CountDownLatch finished = new CountDownLatch(keys.size());
        final AtomicBoolean failed = new AtomicBoolean(false);
        for (final Key key : keys) {
            if (System.nanoTime() >= deadline) { failed.set(true); break; }
            Thread release = new Thread(new Runnable() {
                public void run() {
                    try { sender.keyUp(key.code, key.downTime); }
                    catch (Throwable error) { failed.set(true); }
                    finally { finished.countDown(); }
                }
            }, "CECExitKeyUp");
            release.setDaemon(true);
            try { release.start(); }
            catch (Throwable error) { failed.set(true); finished.countDown(); }
        }
        long remaining = Math.max(0L, deadline - System.nanoTime());
        try {
            return finished.await(remaining, TimeUnit.NANOSECONDS) && !failed.get();
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
