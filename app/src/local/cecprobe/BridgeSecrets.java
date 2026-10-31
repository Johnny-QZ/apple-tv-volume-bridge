package local.cecprobe;

import android.content.Context;
import android.util.AtomicFile;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.security.SecureRandom;

/** One random bridge identity per app installation, excluded from Android backups. */
final class BridgeSecrets {
    private BridgeSecrets() {}

    static synchronized byte[] load(Context context) throws IOException {
        File dir = context.getNoBackupFilesDir();
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("Cannot create identity directory");
        AtomicFile file = new AtomicFile(new File(dir, "bridge-v3.key"));
        if (file.getBaseFile().exists() || new File(file.getBaseFile()+".bak").exists()) {
            byte[] key = file.readFully();
            if (key.length != 32) throw new IOException("Invalid stored bridge identity");
            return key;
        }
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        FileOutputStream out = null;
        try {
            out = file.startWrite();
            out.write(key);
            file.finishWrite(out);
        } catch (IOException e) {
            if (out != null) file.failWrite(out);
            throw e;
        }
        return key;
    }
}
