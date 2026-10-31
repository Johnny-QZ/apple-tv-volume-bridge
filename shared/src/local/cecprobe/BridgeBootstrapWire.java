package local.cecprobe;

import java.io.*;
import java.util.Arrays;

/** Fixed-size one-shot key transfer over a kernel-credential-checked local socket. */
public final class BridgeBootstrapWire {
    public static final int SHELL_UID = 2000;
    private static final int MAGIC = 0x43454b34; // CEK4
    private static final int ACK = 0x4f4b3400; // OK4 NUL
    private BridgeBootstrapWire() {}

    public static void send(InputStream input, OutputStream output, byte[] key, int peerUid) throws IOException {
        // Check before writing even a header. ADB transport identity is not trusted.
        if (peerUid != SHELL_UID) throw new IOException("Bootstrap peer is not shell");
        if (key == null || key.length != BridgeAuth.KEY_LENGTH) throw new IOException("Invalid bootstrap key");
        DataOutputStream out = new DataOutputStream(output);
        out.writeInt(MAGIC); out.write(key); out.flush();
        if (new DataInputStream(input).readInt() != ACK) throw new IOException("Bootstrap acknowledgement invalid");
    }

    public static byte[] receive(InputStream input, OutputStream output, int peerUid, int expectedAppUid) throws IOException {
        if (expectedAppUid < 10000 || peerUid != expectedAppUid) throw new IOException("Bootstrap peer is not the expected app");
        byte[] key = new byte[BridgeAuth.KEY_LENGTH];
        try {
            DataInputStream in = new DataInputStream(input);
            if (in.readInt() != MAGIC) throw new IOException("Bootstrap header invalid");
            in.readFully(key);
            DataOutputStream out = new DataOutputStream(output);
            out.writeInt(ACK); out.flush();
            return key;
        } catch (IOException error) { Arrays.fill(key, (byte) 0); throw error; }
    }

    public static boolean validName(String name) {
        return name != null && name.startsWith(BridgeConfig.BOOTSTRAP_PREFIX)
                && name.substring(BridgeConfig.BOOTSTRAP_PREFIX.length()).matches("[0-9a-f]{32}");
    }
}
