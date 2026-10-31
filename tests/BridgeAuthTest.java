import local.cecprobe.BridgeAuth;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.net.ProtocolException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/** Plain Java 8 tests over paired streams; no network or Android dependency. */
public final class BridgeAuthTest {
    private static final int HEADER_SIZE = 6;
    private static final int HELLO_SIZE = HEADER_SIZE + 32;
    private static final int SERVER_PROOF_SIZE = HEADER_SIZE + 32 + 32;
    private static final int CLIENT_PROOF_SIZE = HEADER_SIZE + 32;
    private static final byte[] KEY = new byte[BridgeAuth.KEY_LENGTH];
    private static byte[] clientTranscript;
    private static byte[] serverTranscript;

    public static void main(String[] args) throws Exception {
        new SecureRandom().nextBytes(KEY);
        successfulExchange();
        wrongKey();
        replayedServer();
        replayedClientProof();
        truncatedServer();
        truncatedClient();
        invalidHeadersAndKeySizes();
        System.out.println("PASS (offline duplex streams): mutual authentication, application boundary, wrong key, "
                + "server/client replay, truncation, headers and key sizes");
    }

    private static void successfulExchange() throws Exception {
        ByteArrayOutputStream fromClient = new ByteArrayOutputStream();
        ByteArrayOutputStream fromServer = new ByteArrayOutputStream();
        Result result = exchange(socket -> {
            OutputStream output = new RecordingOutput(socket.getOutputStream(), fromServer);
            BridgeAuth.accept(socket.getInputStream(), output, KEY);
            check("STATE".equals(new DataInputStream(socket.getInputStream()).readUTF()),
                    "server lost application bytes");
            DataOutputStream out = new DataOutputStream(output);
            out.writeUTF("ACTIVE");
            out.flush();
        }, socket -> {
            OutputStream output = new RecordingOutput(socket.getOutputStream(), fromClient);
            BridgeAuth.connect(socket.getInputStream(), output, KEY);
            DataOutputStream out = new DataOutputStream(output);
            out.writeUTF("STATE");
            out.flush();
            check("ACTIVE".equals(new DataInputStream(socket.getInputStream()).readUTF()),
                    "client lost application bytes");
        });
        succeeded(result.server, "successful server");
        succeeded(result.client, "successful client");
        clientTranscript = fromClient.toByteArray();
        serverTranscript = fromServer.toByteArray();
    }

    private static void wrongKey() throws Exception {
        byte[] wrong = KEY.clone();
        wrong[0] ^= 1;
        Result result = exchange(
                socket -> BridgeAuth.accept(socket.getInputStream(), socket.getOutputStream(), KEY),
                socket -> BridgeAuth.connect(socket.getInputStream(), socket.getOutputStream(), wrong));
        rejected(result.client, "wrong-key client");
        rejected(result.server, "wrong-key server");
    }

    private static void replayedServer() throws Exception {
        // Fake server has only an old public transcript, not the shared key.
        Result result = exchange(socket -> {
            readExactly(socket, HELLO_SIZE);
            socket.getOutputStream().write(serverTranscript, 0, SERVER_PROOF_SIZE);
            socket.getOutputStream().flush();
            check(socket.getInputStream().read() == -1,
                    "client continued after a replayed server proof");
        }, socket -> BridgeAuth.connect(socket.getInputStream(), socket.getOutputStream(), KEY));
        succeeded(result.server, "replay server observation");
        rejected(result.client, "replayed server proof");
    }

    private static void replayedClientProof() throws Exception {
        Result result = exchange(
                socket -> BridgeAuth.accept(socket.getInputStream(), socket.getOutputStream(), KEY),
                socket -> {
                    socket.getOutputStream().write(clientTranscript, 0, HELLO_SIZE);
                    socket.getOutputStream().flush();
                    readExactly(socket, SERVER_PROOF_SIZE);
                    socket.getOutputStream().write(clientTranscript, HELLO_SIZE, CLIENT_PROOF_SIZE);
                    socket.getOutputStream().flush();
                    check(socket.getInputStream().read() == -1,
                            "server accepted a replayed client proof");
                });
        rejected(result.server, "replayed client proof");
        succeeded(result.client, "replay client observation");
    }

    private static void truncatedServer() throws Exception {
        Result result = exchange(socket -> {
            readExactly(socket, HELLO_SIZE);
            socket.getOutputStream().write(serverTranscript, 0, HEADER_SIZE + 8);
            socket.getOutputStream().flush();
        }, socket -> BridgeAuth.connect(socket.getInputStream(), socket.getOutputStream(), KEY));
        succeeded(result.server, "truncated server sender");
        rejected(result.client, "truncated server hello");
    }

    private static void truncatedClient() throws Exception {
        Result result = exchange(
                socket -> BridgeAuth.accept(socket.getInputStream(), socket.getOutputStream(), KEY),
                socket -> {
                    socket.getOutputStream().write(clientTranscript, 0, HEADER_SIZE + 8);
                    socket.getOutputStream().flush();
                });
        rejected(result.server, "truncated client hello");
        succeeded(result.client, "truncated client sender");
    }

    private static void invalidHeadersAndKeySizes() throws Exception {
        // No body is supplied: wrong framing must be rejected before reading it.
        for (int offset : new int[] {0, 4, 5}) {
            byte[] header = Arrays.copyOf(clientTranscript, HEADER_SIZE);
            header[offset] ^= 0x40;
            try {
                BridgeAuth.accept(new ByteArrayInputStream(header), new ByteArrayOutputStream(), KEY);
                throw new AssertionError("invalid header at " + offset + " was accepted");
            } catch (ProtocolException expected) {
                // Must reject framing, rather than merely reach EOF on the missing body.
            }
        }
        for (byte[] invalid : new byte[][] {null, new byte[0], new byte[31], new byte[33]}) {
            expectIOException(() -> BridgeAuth.accept(new ByteArrayInputStream(new byte[0]),
                    new ByteArrayOutputStream(), invalid), "invalid server key");
            expectIOException(() -> BridgeAuth.connect(new ByteArrayInputStream(new byte[0]),
                    new ByteArrayOutputStream(), invalid), "invalid client key");
        }
    }

    private static void readExactly(Endpoint socket, int length) throws IOException {
        new DataInputStream(socket.getInputStream()).readFully(new byte[length]);
    }

    private interface Peer { void run(Endpoint endpoint) throws Exception; }
    private interface Operation { void run() throws Exception; }

    private static Result exchange(Peer serverPeer, Peer clientPeer) throws Exception {
        PipedInputStream serverInput = new PipedInputStream(4096);
        PipedInputStream clientInput = new PipedInputStream(4096);
        Endpoint serverEndpoint = new Endpoint(serverInput, new PipedOutputStream(clientInput));
        Endpoint clientEndpoint = new Endpoint(clientInput, new PipedOutputStream(serverInput));
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Throwable> server = executor.submit(() -> runPeer(serverPeer, serverEndpoint));
            Future<Throwable> client = executor.submit(() -> runPeer(clientPeer, clientEndpoint));
            return new Result(server.get(5, TimeUnit.SECONDS), client.get(5, TimeUnit.SECONDS));
        } finally {
            serverEndpoint.close();
            clientEndpoint.close();
            executor.shutdownNow();
            check(executor.awaitTermination(2, TimeUnit.SECONDS), "test peer did not terminate");
        }
    }

    private static Throwable runPeer(Peer peer, Endpoint endpoint) {
        try (Endpoint connection = endpoint) {
            peer.run(connection);
            return null;
        } catch (Throwable failure) {
            return failure;
        }
    }

    private static final class Endpoint implements AutoCloseable {
        private final InputStream input;
        private final OutputStream output;
        Endpoint(InputStream input, OutputStream output) {
            this.input = input;
            this.output = output;
        }
        InputStream getInputStream() { return input; }
        OutputStream getOutputStream() { return output; }
        @Override public void close() {
            try { output.close(); } catch (IOException ignored) { }
            try { input.close(); } catch (IOException ignored) { }
        }
    }

    private static void expectIOException(Operation operation, String label) throws Exception {
        try {
            operation.run();
            throw new AssertionError(label + " was accepted");
        } catch (IOException expected) {
            // Expected rejection; no authentication material is printed.
        }
    }

    private static void rejected(Throwable failure, String label) {
        check(failure instanceof IOException, label + " was not rejected with IOException");
    }

    private static void succeeded(Throwable failure, String label) {
        if (failure != null) throw new AssertionError(label + " failed", failure);
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private static final class Result {
        final Throwable server;
        final Throwable client;
        Result(Throwable server, Throwable client) {
            this.server = server;
            this.client = client;
        }
    }

    private static final class RecordingOutput extends FilterOutputStream {
        private final ByteArrayOutputStream recording;
        RecordingOutput(OutputStream output, ByteArrayOutputStream recording) {
            super(output);
            this.recording = recording;
        }
        @Override public void write(int value) throws IOException {
            out.write(value);
            recording.write(value);
        }
        @Override public void write(byte[] data, int offset, int length) throws IOException {
            out.write(data, offset, length);
            recording.write(data, offset, length);
        }
    }
}
