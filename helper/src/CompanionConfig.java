import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Optional shell-private configuration. No credentials are packaged in the APK/helper. */
final class CompanionConfig {
    static final String DIRECTORY = "/data/local/tmp/cec-bridge-private";
    static final String PATH = DIRECTORY + "/companion.conf";
    final String host, companionCredentials, airplayCredentials;
    final int companionPort, airplayPort;
    final Set<String> headphoneNames;

    private CompanionConfig(Properties values) throws IOException {
        host = required(values, "appleTvHost");
        if (host.length() > 253 || host.matches(".*[\\s/\\\\].*")) throw new IOException("Invalid device host");
        companionPort = port(required(values, "companionPort"));
        airplayPort = port(values.getProperty("airplayPort", "7000"));
        companionCredentials = credentials(required(values, "companionCredentials"));
        airplayCredentials = credentials(values.getProperty("airplayCredentials", companionCredentials).trim());
        Set<String> names = new HashSet<String>();
        for (String name : required(values, "headphoneNames").split("\\|", -1)) {
            String normalized = AudioRoutePolicy.normalizeName(name);
            if (normalized.isEmpty() || normalized.length() > 256 || names.size() >= 32)
                throw new IOException("Invalid configured headphone names");
            names.add(normalized);
        }
        headphoneNames = Collections.unmodifiableSet(names);
    }

    static CompanionConfig load() throws Exception {
        StructStat directory = Os.lstat(DIRECTORY);
        if (!OsConstants.S_ISDIR(directory.st_mode) || directory.st_uid != 2000 || (directory.st_mode & 0777) != 0700)
            throw new IOException("Configuration directory must be shell-owned mode 0700");
        FileDescriptor fd = Os.open(PATH, OsConstants.O_RDONLY | OsConstants.O_CLOEXEC | OsConstants.O_NOFOLLOW, 0);
        try {
            StructStat file = Os.fstat(fd);
            if (!OsConstants.S_ISREG(file.st_mode) || file.st_uid != 2000 || (file.st_mode & 0777) != 0600 || file.st_size > 32768)
                throw new IOException("Configuration must be a shell-owned regular file with mode 0600");
            Properties values = new Properties();
            try (InputStream in = new FileInputStream(fd)) {
                fd = null;
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                byte[] buffer = new byte[1024]; int count;
                while ((count = in.read(buffer)) != -1) {
                    if (bytes.size() + count > 32768) throw new IOException("Configuration size limit");
                    bytes.write(buffer, 0, count);
                }
                values.load(new InputStreamReader(new ByteArrayInputStream(bytes.toByteArray()), StandardCharsets.UTF_8));
            }
            return new CompanionConfig(values);
        } finally { if (fd != null) Os.close(fd); }
    }

    private static String required(Properties values, String key) throws IOException {
        String value = values.getProperty(key);
        if (value == null || value.trim().isEmpty()) throw new IOException("Missing companion configuration field");
        return value.trim();
    }
    private static int port(String value) throws IOException {
        try { int port = Integer.parseInt(value.trim()); if (port >= 1 && port <= 65535) return port; }
        catch (NumberFormatException ignored) {}
        throw new IOException("Invalid device port");
    }
    private static String credentials(String value) throws IOException {
        String[] fields = value.split(":", -1);
        if (fields.length != 4) throw new IOException("Invalid pairing credential format");
        for (int i = 0; i < fields.length; i++) {
            if (!fields[i].matches("[0-9A-Fa-f]+") || fields[i].length() % 2 != 0 || fields[i].length() > 1024 || (i < 2 && fields[i].length() != 64))
                throw new IOException("Invalid pairing credential format");
        }
        return value;
    }
}
