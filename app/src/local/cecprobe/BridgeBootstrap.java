package local.cecprobe;

import android.net.LocalServerSocket;
import android.net.LocalSocket;
import java.io.Closeable;
import java.io.IOException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** One-shot, bounded key handoff. The random socket address is public, not a secret. */
final class BridgeBootstrap implements Closeable {
    private static final long TIMEOUT_MS = 30000;
    private final LocalServerSocket listener;
    private final byte[] key;
    private final CountDownLatch finished = new CountDownLatch(1);
    private final long deadline;
    private final long timeoutMs;
    private final String name;
    private volatile boolean closed, delivered;
    private volatile LocalSocket peer;

    BridgeBootstrap(byte[] value) throws IOException {
        this(value, TIMEOUT_MS);
    }

    BridgeBootstrap(byte[] value, long timeout) throws IOException {
        if (value == null || value.length != BridgeAuth.KEY_LENGTH) throw new IOException("Invalid bootstrap identity");
        if (timeout < 1 || timeout > TIMEOUT_MS) throw new IOException("Invalid bootstrap deadline");
        timeoutMs = timeout;
        deadline = System.nanoTime() + timeout * 1000000L;
        byte[] random = new byte[16]; new SecureRandom().nextBytes(random);
        StringBuilder address = new StringBuilder(BridgeConfig.BOOTSTRAP_PREFIX);
        for (byte b : random) address.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
        name = address.toString();
        listener = new LocalServerSocket(name); // Bind before launching any helper.
        key = value.clone();
        Thread server = new Thread(new Runnable() { public void run() { serve(); } }, "BridgeKeyBootstrap");
        server.setDaemon(true); server.start();
        Thread timer = new Thread(new Runnable() { public void run() {
            try { if (!finished.await(timeoutMs, TimeUnit.MILLISECONDS)) close(); }
            catch (InterruptedException stopped) { Thread.currentThread().interrupt(); close(); }
        } }, "BridgeKeyDeadline");
        timer.setDaemon(true); timer.start();
    }

    String name() { return name; }

    private void serve() {
        try {
            while (!closed && System.nanoTime() < deadline) {
                LocalSocket socket = listener.accept();
                peer = socket;
                try {
                    if (closed) break;
                    if (socket.getPeerCredentials().getUid() != BridgeBootstrapWire.SHELL_UID) continue;
                    socket.setSoTimeout(3000);
                    BridgeBootstrapWire.send(socket.getInputStream(), socket.getOutputStream(), key,
                            socket.getPeerCredentials().getUid());
                    delivered = !closed && System.nanoTime() < deadline;
                    return;
                } finally { try { socket.close(); } catch (IOException ignored) {} peer = null; }
            }
        } catch (IOException ignored) {
            // Never include peer data or key bytes in user-facing errors or logs.
        } finally { close(); }
    }

    void awaitDelivery() throws IOException, InterruptedException {
        long remaining = Math.max(0, deadline - System.nanoTime());
        if (!finished.await(remaining, TimeUnit.NANOSECONDS) || !delivered)
            throw new IOException("The shell helper did not complete the private key handoff");
    }

    public void close() {
        closed = true;
        LocalSocket socket = peer;
        if (socket != null) try { socket.close(); } catch (IOException ignored) {}
        try { listener.close(); } catch (IOException ignored) {}
        Arrays.fill(key, (byte) 0);
        finished.countDown();
    }
}
