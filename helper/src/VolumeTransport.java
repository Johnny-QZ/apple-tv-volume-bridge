import java.io.Closeable;

/** Only the volume operations used by the guarded fine-step worker. */
interface VolumeTransport extends Closeable {
    double getVolume() throws Exception;
    void setVolume(double value) throws Exception;
}
