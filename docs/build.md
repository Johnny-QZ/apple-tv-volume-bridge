# 构建输入与依赖

公开源码版本 `0.3.0-alpha.4` 使用 Python 3.9+、JDK 17、Android API 28 和 Android Build Tools。指定工具路径可避免自动选择另一版本；本次离线验证选用 Build Tools `35.0.0`。构建脚本支持 `JAVA_HOME`、`ANDROID_SDK_ROOT`、`ANDROID_HOME` 以及显式路径参数，没有开发者个人目录硬编码。

## 固定 Maven 输入

运行 `python3 scripts/dependencies.py` 从 Maven Central 下载以下原始 JAR，核对 SHA-256 后保存到忽略的 `build/deps/`。已有文件若校验失败会报错，不会静默接受或替换。

| 输入 | SHA-256 |
| --- | --- |
| `bcprov-jdk18on-1.86.jar` | `2af190b300cbb0b35e248ccf5f4a06b6072030aeb3da7a98ec73abe5b4cb371f` |
| `dd-plist-1.28.jar` | `88ed8e730f7386297485176c4387146c6914a38c0e58fc296e8a01cdc3b621e1` |

下载源：[Bouncy Castle 1.86](https://repo.maven.apache.org/maven2/org/bouncycastle/bcprov-jdk18on/1.86/bcprov-jdk18on-1.86.jar)、[dd-plist 1.28](https://repo.maven.apache.org/maven2/com/googlecode/plist/dd-plist/1.28/dd-plist-1.28.jar)。许可证分别为 Bouncy Castle MIT-style 和 MIT，完整文本见 [THIRD_PARTY_LICENSES.md](../THIRD_PARTY_LICENSES.md)。

离线用户可将这两个**原始文件**放入缓存，再运行：

```sh
python3 scripts/dependencies.py --offline
```

脚本从校验过的 BC 原始 JAR 重新生成 `bc-android-input.jar`：只保留 `META-INF/` 以外的 Java 8 `.class`，排除模块信息、签名、多版本实现和非 class 资源；保留 class 字节不变，以固定排序、时间戳和存储方式打包。这沿用原型的类筛选方法，但已升级库版本，不是上游发布的独立 Android 库，也不能当作包含全部 BC 功能的通用发行版。

构建脚本每次重新校验原始依赖并重建该输入，不信任任意缓存的派生 JAR。helper 先经 `javac`，再由 SDK `d8.jar` 内的 R8 按 `BridgeHelper.main` 入口裁剪，保留类名并要求只生成一个 DEX，以适配 `app_process`。app 使用 D8。APK 与 helper 都携带第三方许可声明。

## 完整流程

```sh
python3 scripts/dependencies.py
python3 scripts/build.py \
  --java-home /path/to/jdk17 \
  --android-jar /path/to/android-sdk/platforms/android-28/android.jar \
  --build-tools /path/to/android-sdk/build-tools/35.0.0
python3 scripts/test.py --java-home /path/to/jdk17
python3 scripts/test_bootstrap.py --java-home /path/to/jdk17
python3 scripts/test_native.py --java-home /path/to/jdk17 \
  --android-jar /path/to/android-sdk/platforms/android-28/android.jar
python3 scripts/test_setup.py
```

构建不会联网或连接电视。默认生成 `build/apple-tv-volume-bridge-0.3.0-alpha.4-debug.apk`、`build/bridge-helper.jar` 与本机调试签名密钥。可传 `--out PATH` 改输出目录；依赖仍默认在仓库的 `build/deps`，可单独用 `--deps PATH` 修改。自定义路径应在仓库外或加入本机忽略规则。

这些步骤固定了库输入和重打包方式，并没有保证所有机器生成字节完全相同的 APK。JDK/SDK 具体版本、ZIP 元数据及本机签名身份仍会影响输出。正式分发安装包之前还需稳定的签名管理和实机回归。

`requirements-pairing.txt` 固定可选工具 `pyatv==0.18.0` 与其兼容的 `srptools==1.0.1`；其他依赖仍由 pip 按平台解析，并非跨平台完整锁文件。后者避免 srptools 2.0 改变调用方式导致配对失败，见[上游问题 #2934](https://github.com/postlund/pyatv/issues/2934)。pyatv 不参与 Android 构建，也不打进 APK。它仅用于用户在自己的电脑上完成配对。
