# Apple TV 音量转发

用原装小米电视遥控器调节 Apple TV 所连接的 AirPods 音量，并保留电视扬声器控制。

An experimental, no-root Xiaomi TV remote bridge for Apple TV, with optional paired headphone volume control.

**当前源码：`0.3.0-alpha.3`。最新私有原型为 `0.2.11`；两者不是同一安装包。** `0.2.x` 的功能已在下述单台设备逐步实测，公开 Alpha 合入这些功能并保留每次安装独立的双向 HMAC 认证。公开合并版本只有离线构建/测试记录，尚未在电视上完成回归。它不是通用 Android TV 安装包，也不承诺所有电视、耳机或 tvOS 版本兼容。详见[兼容性记录](docs/compatibility.md)和[验证范围](docs/validation.md)。

## 功能与限制

- 小米遥控器音量键通过 HDMI-CEC 控制 Apple TV 当前输出；电视扬声器直控模式可绕开 CEC 往返。
- 完成自己的 Apple TV 配对并明确配置耳机名称后，可识别已配置的耳机输出，使用网络协议精调音量：标准 `6.25/3 ≈ 2.0833` 个百分点，超细 `6.25/6 ≈ 1.0417` 个百分点。`6.25` 是已测设备的单步校准值，本版固定使用它；这里是 0–100 音量标尺上的变化，不是分贝或感知响度。
- 菜单提供自动路由和手动模式；没有私有配对配置时仍可使用原有 CEC 路径。自动检测依赖配置、网络和路由消息，不能把未知设备当成耳机。
- 保留确认键长按、切源返回后的自动恢复、静默启动，以及息屏前全屏/首页小窗状态恢复。方向键长按在特定 Apple TV 界面停下的现象，在原装 Apple TV 遥控器对照中也出现，不能将其宣称为本项目已解决的问题。
- 修复进入转发界面或断开耳机后，电视扬声器可能有画面却无声的问题；通过小米固件的 `TV-Mute` 接口恢复 HDMI 硬件声音，同时保留电视自己的静音和零音量状态。
- 三个息屏联动开关在公开版默认全部关闭，需分别启用。**真正重启或断电后，测试电视会关闭 ADB，需要手动重新开启并打开应用。**原生息屏唤醒与真正重启不是同一情况。

已实测组合：小米 `MiTV-MFTP0`、Android 9 / API 28、固件 `23.5.23.6436`、原装小米蓝牙遥控器、HDMI 2 上的 Apple TV `A2169`。tvOS `26.6` 为用户提供的信息。AirPods 具体型号未记录。

## 工作方式

```text
小米遥控器 → shell 辅助程序读取 evdev → 本机认证连接 → 全屏 TvView → HDMI-CEC → Apple TV
                         └→ 已配对的 Companion 精调 + AirPlay/MRP 输出路由观察（可选）
```

应用通过电视自身 `127.0.0.1:5555` 的 ADB 启动辅助程序；正常运行不需要电脑持续连接。辅助程序只有在应用前台、获得焦点且 HDMI 视频就绪时接管遥控器，退出后释放输入。它需要固件允许 shell 读取/独占 evdev 和重新注入按键。项目不需要 root，也不替换系统播放器或修改系统分区。

应用与辅助程序的 `127.0.0.1:17863` 通信使用每次安装独立的随机密钥和双向 HMAC。可选精调功能另外使用你自己的 Apple TV 配对凭据；这些凭据不附带在项目或 APK 中。网络与权限边界见[安全说明](docs/security.md)。

## 从源码构建

需要 Python 3.9+、JDK 17、Android SDK API 28 和 Build Tools（离线验证环境使用 `35.0.0`，需包含 `aapt`、`lib/d8.jar`、`lib/apksigner.jar`）。安装到电视另需 SDK Platform Tools 的 `adb`。工具安装路径由参数或 `JAVA_HOME` / `ANDROID_SDK_ROOT` 指定。

在仓库根目录先下载并校验固定依赖，再离线构建：

```sh
python3 scripts/dependencies.py
python3 scripts/build.py \
  --android-jar /path/to/android-sdk/platforms/android-28/android.jar \
  --build-tools /path/to/android-sdk/build-tools/35.0.0 \
  --java-home /path/to/jdk17
python3 scripts/test.py --java-home /path/to/jdk17
python3 scripts/test_native.py --java-home /path/to/jdk17 \
  --android-jar /path/to/android-sdk/platforms/android-28/android.jar
python3 scripts/test_setup.py
```

产物为 `build/apple-tv-volume-bridge-0.3.0-alpha.3-debug.apk` 和 `build/bridge-helper.jar`。依赖缓存默认在 `build/deps/`；依赖准备脚本支持 `--offline`，构建脚本本身不联网。需要自定义缓存时，分别给准备脚本传 `--cache PATH`、给构建脚本传 `--deps PATH`。

Bouncy Castle `1.80` 和 dd-plist `1.28` 的下载地址、SHA-256、Android 重打包与 R8 流程见[构建说明](docs/build.md)。固定输入可复现构建流程；没有声称 APK 字节完全可复现，调试签名密钥和 ZIP 元数据会影响产物。请保留本机生成的 `build/debug.keystore` 供后续升级使用，并勿公开它。

## 安装与基础使用

1. 在电视开启 HDMI-CEC 和 ADB 调试。测试设备 ADB 开关位于“设置 → 账号与安全”；其他固件可能不同。
2. 用电视实际地址替换 `TV_IP`，运行：

   ```sh
   python3 scripts/device.py --serial TV_IP:5555 install
   python3 scripts/device.py --serial TV_IP:5555 start
   ```

3. 允许电视上的 ADB 授权提示。应用首次通过本机 ADB 启动辅助程序时，也可能需要授权；“始终允许”保存的是该安装生成的公钥身份。
4. 打开“Apple TV 音量转发”，确认目标 HDMI 画面。将耳机连接到 Apple TV，先从较低音量测试遥控器。菜单可选择自动、电视扬声器、耳机精调及步进档位。

旧原型与本机新构建的签名可能不同，不能直接覆盖安装。安装脚本遇到签名冲突会停止，不会自动卸载。先保留旧 APK，再安排卸载、重装和重新授权；卸载会清除应用设置与私有身份。

## 可选：配对与耳机精调

基础 CEC 转发不需要 Apple TV 网络配对。若要启用细步进与自动耳机路由，在 macOS/Linux 电脑安装可选的 `pyatv==0.18.0`：

```sh
python3 -m venv .venv
.venv/bin/python -m pip install -r requirements-pairing.txt
.venv/bin/python scripts/provision.py pair \
  --host APPLE_TV_IP --headphone-name '你自己的耳机完整名称'
```

将 `APPLE_TV_IP` 替换成自己的 Apple TV IPv4 地址。这个命令仅向指定地址发现服务，并在 Apple TV 上显示配对 PIN；在电脑提示中输入 PIN。名称要与 Apple TV 的输出设备名称一致；多个名称可重复 `--headphone-name`。默认保存到 Git 忽略的 `.private/companion.conf`，目录 `0700`、文件 `0600`，不会输出密钥。

然后停止桥接程序、把私有配置装到明确指定的电视，再启动：

```sh
python3 scripts/device.py --serial TV_IP:5555 stop
python3 scripts/provision.py install --serial TV_IP:5555
python3 scripts/device.py --serial TV_IP:5555 start
```

安装目标是 `/data/local/tmp/cec-bridge-private/companion.conf`，并检查 shell 所有权及严格权限。再次配置时先使用新输出文件配对，再传 `install --config PATH --replace`。默认复用 Companion 凭据观察 AirPlay 路由，这是原型已使用的方式；若你的设备要求独立 AirPlay 配对，使用 `pair --pair-airplay --out .private/another.conf`。公开配置脚本尚未实机验证，操作细节、失败处理和撤销见[配对说明](docs/pairing.md)。

## 息屏、恢复与撤销

菜单中的三个息屏开关分别控制“小米息屏 → Apple TV 休眠”“Apple TV 唤醒 → 电视亮屏”和“Apple TV 休眠 → 电视息屏”。公开版默认全关；只在了解本机固件行为后开启。使用测试小米固件的原生息屏路径，需要电视电源键设为原生息屏，并保持设备自动关机设置关闭。它可能涉及 CEC Standby 广播及同一 HDMI 拓扑的其他设备，详见[兼容性记录](docs/compatibility.md)。

主页键或菜单退出只离开画面并释放遥控器，后台切源观察以及已启用的息屏联动可继续运行。从其他 HDMI 返回目标源时，辅助程序通常会重新打开应用；极快切源可能被轮询错过。需要完全停止：

```sh
python3 scripts/device.py --serial TV_IP:5555 stop
```

完整卸载并清理公开 Alpha 的辅助文件、HMAC 密钥和电视侧配对配置：

```sh
python3 scripts/device.py --serial TV_IP:5555 uninstall
```

仅从电视设置卸载 APK 不会删除 shell 私有配置。电脑上的 `.private/` 不会自动删除；可自行删除，并在 Apple TV 的已配对设备管理中撤销不再使用的身份。卸载脚本不更改 ADB 开关，不再使用时可自行关闭。

## 适配与许可

其他电视先检查 [BridgeConfig.java](shared/src/local/cecprobe/BridgeConfig.java) 中的 HDMI 输入 ID、CEC 物理路由、遥控器设备名及原厂播放器标识。当前配置针对上述小米 HDMI 2，不能把端口号直接当通用输入 ID。辅助程序拒绝未经适配的 64 位输入事件布局。仅修改配置并构建成功，不等于设备兼容。

反馈请附版本、型号、固件、按键操作和实际响度变化；区分耳机/电视音量条、主页退出/切源/息屏/重启。不要上传 IP、MAC、个人名称、配对配置、ADB 密钥、签名密钥或原始私人日志。

项目源码使用 [MIT](LICENSE)，依赖许可见[第三方声明](THIRD_PARTY_LICENSES.md)。项目与 Apple、小米没有关联，不附带 SDK、厂商 APK、固件、个人身份或已配对安装包。[发布检查](docs/release.md)列出安装包正式发布前仍需完成的验证。
