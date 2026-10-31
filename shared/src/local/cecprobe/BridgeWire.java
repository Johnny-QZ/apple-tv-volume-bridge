package local.cecprobe;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;

/** Small ASCII commands after the authenticated handshake. */
public final class BridgeWire {
    private BridgeWire() {}

    public static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        for (;;) {
            int c = in.read();
            if (c < 0) {
                if (line.size() == 0) return null;
                throw new EOFException("Truncated bridge command");
            }
            if (c == '\n') return line.toString("US-ASCII");
            if (c < 32 || c > 126 || line.size() >= 96) {
                throw new IOException("Invalid bridge command");
            }
            line.write(c);
        }
    }
}
