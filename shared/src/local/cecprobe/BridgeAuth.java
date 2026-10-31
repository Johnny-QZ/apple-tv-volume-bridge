package local.cecprobe;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ProtocolException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Mutual authentication for the local app (server) and input helper (client).
 * Both callers must keep the 32-byte key private and set a socket read timeout.
 * These methods neither close nor buffer ahead on the supplied streams. No
 * application command may be processed until the corresponding method returns.
 * Authentication does not encrypt or authenticate subsequent application data.
 */
public final class BridgeAuth {
    public static final int KEY_LENGTH = 32;
    public static final int PROTOCOL_VERSION = 3;

    private static final int MAGIC = 0x43454342; // CECB, big endian
    private static final int CLIENT_HELLO = 1;
    private static final int SERVER_PROOF = 2;
    private static final int CLIENT_PROOF = 3;
    private static final int SERVER_FINISHED = 4;
    private static final int NONCE_LENGTH = 32;
    private static final int MAC_LENGTH = 32;
    private static final byte[] DOMAIN =
            "AppleTVVolumeBridge/auth".getBytes(StandardCharsets.US_ASCII);
    private static final SecureRandom RANDOM = new SecureRandom();

    private BridgeAuth() { }

    /** Server: authenticate the helper before returning to the command loop. */
    public static void accept(InputStream input, OutputStream output, byte[] key)
            throws IOException {
        byte[] sessionKey = copyKey(key);
        try {
            DataInputStream in = new DataInputStream(input);
            DataOutputStream out = new DataOutputStream(output);
            readHeader(in, CLIENT_HELLO);
            byte[] clientNonce = readFixed(in, NONCE_LENGTH);
            byte[] serverNonce = nonce();

            writeHeader(out, SERVER_PROOF);
            out.write(serverNonce);
            out.write(proof(sessionKey, "server", clientNonce, serverNonce));
            out.flush();

            readHeader(in, CLIENT_PROOF);
            verify(readFixed(in, MAC_LENGTH),
                    proof(sessionKey, "client", clientNonce, serverNonce));

            writeHeader(out, SERVER_FINISHED);
            out.write(proof(sessionKey, "server-finished", clientNonce, serverNonce));
            out.flush();
        } finally {
            Arrays.fill(sessionKey, (byte) 0);
        }
    }

    /** Client: authenticate the app, prove our identity, and await confirmation. */
    public static void connect(InputStream input, OutputStream output, byte[] key)
            throws IOException {
        byte[] sessionKey = copyKey(key);
        try {
            DataInputStream in = new DataInputStream(input);
            DataOutputStream out = new DataOutputStream(output);
            byte[] clientNonce = nonce();

            writeHeader(out, CLIENT_HELLO);
            out.write(clientNonce);
            out.flush();

            readHeader(in, SERVER_PROOF);
            byte[] serverNonce = readFixed(in, NONCE_LENGTH);
            verify(readFixed(in, MAC_LENGTH),
                    proof(sessionKey, "server", clientNonce, serverNonce));

            writeHeader(out, CLIENT_PROOF);
            out.write(proof(sessionKey, "client", clientNonce, serverNonce));
            out.flush();

            readHeader(in, SERVER_FINISHED);
            verify(readFixed(in, MAC_LENGTH),
                    proof(sessionKey, "server-finished", clientNonce, serverNonce));
        } finally {
            Arrays.fill(sessionKey, (byte) 0);
        }
    }

    private static byte[] copyKey(byte[] key) throws IOException {
        if (key == null || key.length != KEY_LENGTH) {
            throw new IOException("Bridge authentication requires a 32-byte key");
        }
        return key.clone();
    }

    private static byte[] nonce() {
        byte[] value = new byte[NONCE_LENGTH];
        RANDOM.nextBytes(value);
        return value;
    }

    private static void writeHeader(DataOutputStream out, int message) throws IOException {
        out.writeInt(MAGIC);
        out.writeByte(PROTOCOL_VERSION);
        out.writeByte(message);
    }

    private static void readHeader(DataInputStream in, int expectedMessage) throws IOException {
        if (in.readInt() != MAGIC || in.readUnsignedByte() != PROTOCOL_VERSION
                || in.readUnsignedByte() != expectedMessage) {
            throw new ProtocolException("Invalid bridge authentication message");
        }
    }

    // All fields have fixed sizes. Untrusted peers cannot request allocations.
    private static byte[] readFixed(DataInputStream in, int length) throws IOException {
        byte[] value = new byte[length];
        in.readFully(value);
        return value;
    }

    private static byte[] proof(byte[] key, String role, byte[] clientNonce,
            byte[] serverNonce) throws IOException {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            mac.update(DOMAIN);
            mac.update((byte) 0);
            mac.update((byte) PROTOCOL_VERSION);
            mac.update(role.getBytes(StandardCharsets.US_ASCII));
            mac.update((byte) 0);
            mac.update(clientNonce);
            mac.update(serverNonce);
            return mac.doFinal();
        } catch (GeneralSecurityException e) {
            throw new IOException("Bridge authentication unavailable", e);
        }
    }

    private static void verify(byte[] received, byte[] expected) throws IOException {
        if (!MessageDigest.isEqual(expected, received)) {
            throw new IOException("Bridge authentication failed");
        }
    }
}
