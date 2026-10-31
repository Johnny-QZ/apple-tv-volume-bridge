# 本地验证记录

日期：2026-10-31。源码版本：`0.3.0-alpha.3`。

以下检查针对本次公开源码合并版实际执行。环境为 macOS、JDK 17、Android API 28、Build Tools 35.0.0、Python 3.9。本次没有连接、更新或操作电视。

| 检查 | 结果与范围 |
| --- | --- |
| 固定依赖 | 从公开 Maven 下载 BC 1.80 和 dd-plist 1.28，并通过源码中固定的 SHA-256 检查。 |
| 全量构建 | Java 源码编译、R8 单 DEX helper、APK 构建、debug 签名和签名校验通过。 |
| 内嵌内容 | APK 中的 helper 与本次生成的 JAR 字节一致；APK 和 helper 均包含第三方许可声明。 |
| BridgeAuthTest | 双向认证、错误密钥、双方重放、截断、非法头、密钥长度及应用数据边界通过。使用内存流，不替代设备网络测试。 |
| ConfirmHoldTest | 短按、确认长按、十秒方向长按、重复按下、松手、取消及避免突发重复通过。 |
| ScreenLinkPolicyTest | 冷启动、未知状态、三个方向、防回送、信号源保护及八种开关组合通过。 |
| WakeViewMemoryTest | 全屏、首页/未知、过期、手动唤醒、重启和仅恢复一次通过。 |
| AudioRoutePolicyTest | 显式名称、单输出限定、未知/混合输出、Unicode 归一化、能力值与六秒有效期通过。 |
| 原生协议离线回归 | 路由拓扑解析、能力消息不刷新输出身份、OPACK 边界、HAP 加解密、半帧超时、细步进队列与松手/不确定结果后的重复取消通过。使用回环连接与测试时钟，没有接触设备。 |
| 配置与构建工具 | 七项 Python 测试通过：固定摘要、确定性类重打包、私有文件权限与无覆盖/无文件符号链接、名称与凭据格式、可选 AirPlay 失败时保留 Companion 身份、明确的 ADB 目标和凭据仅经标准输入传递。ADB 使用 mock，远端命令只做 shell 语法检查。 |
| 文档与源码 | Python 语法与帮助入口、文档相对链接、待提交文件与个人信息检查通过。依赖、构建产物、签名和配对身份不在发布源码中。 |

复现离线测试：

```sh
python3 scripts/test.py --java-home /path/to/jdk17
python3 scripts/test_native.py --java-home /path/to/jdk17 --android-jar /path/to/android-sdk/platforms/android-28/android.jar
python3 scripts/test_setup.py
```

依赖准备与全量构建命令见[构建说明](build.md)。固定输入可以复现流程，但本项目不宣称 APK 字节完全可复现。

这些结果不等于公开 Alpha 已通过电视实测。首次安装/授权、公开配对脚本、私有配置远端安装、动态路由刷新、网络异常、实际响度、完整息屏与音视频行为仍需硬件回归。原型 `0.2.1–0.2.11` 的用户实测单独记录在[兼容性记录](compatibility.md)中，不能转记为本版本验收。真正重启后的 ADB 限制没有解决。
