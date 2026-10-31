package local.cecprobe;

/** Device profile: these defaults describe the tested Xiaomi HDMI 2 setup. */
public final class BridgeConfig {
    private BridgeConfig() {}

    // Keep INPUT_ID and PHYSICAL_ADDRESS aligned when adapting another HDMI port.
    public static final String INPUT_ID = "com.mediatek.tvinput/.hdmi.HDMIInputService/HDMI200004";
    public static final int PHYSICAL_ADDRESS = 0x2000;
    public static final String REMOTE_NAME = "小米蓝牙语音遥控器";
    public static final String STOCK_PLAYER_PACKAGE = "com.xiaomi.mitv.tvplayer";

    public static final int PORT = 17863;
    public static final String HELPER_PATH = "/data/local/tmp/cec-bridge-helper-v3.jar";
    public static final String KEY_PATH = "/data/local/tmp/cec-bridge-v3.key";
    public static final String LOG_PATH = "/data/local/tmp/cec-bridge-helper-v3.log";
}
