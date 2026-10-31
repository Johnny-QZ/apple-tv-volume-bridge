# 用户自己的配对与私有配置

不配对也能使用原有 HDMI-CEC 转发。精调音量与输出路由观察需要自己的 Apple TV 身份，不能使用开发者或其他人的凭据。本仓库和构建产物都不包含任何已配对身份。

本流程在 macOS/Linux 电脑运行。公开版配对/安装脚本只完成离线检查，尚未在电视上实测；不要把原型已有配对成功等同于新脚本已验收。

## 生成配置

确认 Apple TV 与电脑网络可通，并让 Apple TV 画面可见。只用自己的 Apple TV IPv4 地址；脚本不会扫描整个局域网。先在 Apple TV 查看自己的耳机完整名称，作为明确允许的输出身份。

```sh
python3 -m venv .venv
.venv/bin/python -m pip install -r requirements-pairing.txt
.venv/bin/python scripts/provision.py pair \
  --host APPLE_TV_IP \
  --headphone-name '你的耳机完整名称'
```

在电脑隐藏输入提示中输入 Apple TV 显示的四位 PIN。配对会产生持久授权，但不发送调音量或电源命令。默认仅配对 Companion；原型已确认其凭据可用于本机 AirPlay 路由观察。若你的设备要求独立 AirPlay 身份，可加 `--pair-airplay`，按两个协议的提示分别完成配对。若第二次 AirPlay 配对失败，已成功的 Companion 身份仍保存在私有文件中；不要直接覆盖或删除它。

配置默认写入 `.private/companion.conf`。目录必须属于当前用户、权限为 `0700`；文件通过独占创建写为 `0600`，不覆盖已有文件，也不跟随文件符号链接。可用 `--out .private/another.conf` 生成另一份配置。脚本关闭第三方日志，错误只显示类型，避免第三方异常携带凭据。

多个允许的输出名可重复 `--headphone-name`。匹配会去除名称两端空白并做 Unicode NFC 归一化，之后**区分大小写、完整匹配**。不采用“包含 AirPods 字样”之类的泛化判断。不要将客人耳机、音箱或不认识的输出加入允许名单。

## 安装到电视

先停止桥接，避免在使用旧配置的进程仍运行时误认为新配置已生效：

```sh
python3 scripts/device.py --serial TV_IP:5555 stop
python3 scripts/provision.py install --serial TV_IP:5555
python3 scripts/device.py --serial TV_IP:5555 start
```

`TV_IP` 是运行桥接的电视地址，`APPLE_TV_IP` 是 Apple TV 地址，二者不是同一设备。安装脚本只连接 `--serial` 指定的 ADB 设备。可用 `--adb /path/to/adb` 与 `--server-port PORT` 指定本机工具。

配置内容通过 ADB 标准输入传输，不进入命令行参数、日志、APK 或公共临时文件。目标固定为 `/data/local/tmp/cec-bridge-private/companion.conf`，以 `shell` 用户创建 `0700` 目录和 `0600` 文件，检查已有目标非符号链接、所有者与权限后原子替换。默认拒绝覆盖；明确更新时使用：

```sh
python3 scripts/provision.py install --serial TV_IP:5555 \
  --config .private/another.conf --replace
```

它不会读取或覆盖私有原型的旧配置目录，不会生成应用的 HMAC 密钥；HMAC 和 ADB 身份仍由 Android 应用本机生成。

## 配置格式

文件采用 UTF-8 Java properties。由脚本生成的字段如下，实际值仅保存于私有文件：

| 字段 | 含义 |
| --- | --- |
| `appleTvHost` | 用户明确选择的 Apple TV IPv4 地址 |
| `companionPort` | 定向发现得到的 Companion 端口 |
| `companionCredentials` | `ltpk:ltsk:accessoryId:controllerId` 形式的四个十六进制字段 |
| `airplayPort` | 定向发现得到的 AirPlay 端口；没有发现时默认 `7000` |
| `airplayCredentials` | 可选；省略时复用 Companion 凭据 |
| `headphoneNames` | 明确允许的完整输出名，以 `|` 分隔；名称本身不能含 `|` |

helper 拒绝不安全的目录/文件权限、符号链接和无效配置。APK 内不需要这些字段。Apple TV 地址或端口变化后需要重新生成或私下更新配置，再重启 helper；本版没有通用网络迁移/自动发现向导。

## 自动路由与失败行为

只有新鲜的设备信息快照中**恰好一个活动输出**与配置名称一致、且协议音量能力满足要求，自动模式才选择耳机精调。空输出、多输出（即使名称都已配置）、未知输出、过期状态、不可用配置都不会视作已知耳机；新的按键沿用普通 CEC。允许列表可包含多个备选耳机名称，但不能据此同时控制多个活动输出。快照有效期为 6 秒，心跳本身不延长输出身份有效期。

每次实体按下只选择一次路径，直到松手。精调写入失败或结果不确定时，当前按键不会自动重试写入，也不会再走 CEC 导致二次调音量。需要确认当前输出和响度后再操作。路由适配依赖已研究的 AirPlay/MRP 消息与拓扑，仍是实验实现，不能声称通用 AirPods 自动发现。

## 排查与撤销

- `PackageNotFoundError`：使用 `.venv/bin/python` 执行配对，并先安装 `requirements-pairing.txt`。脚本要求确切的 pyatv `0.18.0`。
- `FileExistsError`：配置已有，改用新 `--out`，不要把真实凭据粘贴到聊天或 issue。
- `ValueError`：检查输出父目录 `0700`、当前用户所有权、四位 PIN、耳机名称、正确地址和服务可用性。输出目录不能是符号链接。
- `CalledProcessError`：检查电视 ADB 已授权、目标设备选择、shell 用户权限、现有配置目录/文件权限；覆盖已有配置需要 `--replace`。不要开启会打印命令输入内容的调试日志。
- 路由未知：先确认 Apple TV 的实际音频输出、名称完全匹配和网络可达。可暂时使用普通 CEC 或手动选择模式；本版不把配对成功当作路由探测已通过。

`device.py ... stop` 保留配对配置供下次启动。`device.py ... uninstall` 删除公开 Alpha 电视侧配置，但不删除电脑上的 `.private` 文件，也不在 Apple TV 上撤销配对。彻底停用时自行删除本机文件，并在 Apple TV 的已配对设备管理中移除对应身份。具体菜单入口随 tvOS 可能不同。
