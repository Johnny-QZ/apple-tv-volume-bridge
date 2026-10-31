#!/usr/bin/env python3
"""Explicit-device install/start/stop/uninstall. Does not enable ADB; restores an interrupted power-link transaction if marked."""
import argparse
import re
import shutil
import subprocess
import sys
from pathlib import Path
from xml.etree import ElementTree

ROOT = Path(__file__).resolve().parents[1]
PACKAGE = "local.cecprobe"
KEY_PATH = "/data/local/tmp/cec-bridge-v3.key"

# Only known complete cmdlines qualify; never kill every app_process.
STOP_HELPER = """for task_pid in $(pidof app_process app_process32 app_process64 BridgeHelper); do
  task_cmd=$(tr '\\000' '\\n' < /proc/$task_pid/cmdline 2>/dev/null) || continue
  case "$task_cmd" in
    'BridgeHelper'|'app_process
/system/bin
BridgeHelper'|'app_process
/system/bin
BridgeHelper
180'|'BridgeHelper
/data/local/tmp/cec-bridge-v3.key'|'app_process
/system/bin
BridgeHelper
/data/local/tmp/cec-bridge-v3.key') kill "$task_pid" 2>/dev/null ;;
  esac
done
sleep 1
if [ -f /data/local/tmp/cec-bridge-v3-power-restore ]; then
  settings put global hdmi_control_auto_device_off_enabled 0
  if [ "$(settings get global hdmi_control_auto_device_off_enabled)" = 0 ]; then
    rm -f /data/local/tmp/cec-bridge-v3-power-restore
  else
    echo 'Power setting restore failed' >&2
    exit 1
  fi
fi
"""


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True, help="Explicit ADB serial, e.g. TV_IP:5555")
    parser.add_argument("--adb", default="adb", help="Path to Android platform-tools adb")
    parser.add_argument("--server-port", type=int, default=5037, help="Local ADB server port")
    parser.add_argument("--apk", type=Path, help="APK to install; defaults to this version's build output")
    parser.add_argument("action", choices=("install", "start", "stop", "uninstall"))
    args = parser.parse_args()
    if not re.fullmatch(r"[A-Za-z0-9_.:\[\]%-]+", args.serial) or args.serial.startswith("-"):
        parser.error("Invalid ADB serial")
    if not 1 <= args.server_port <= 65535:
        parser.error("Server port must be 1..65535")
    adb = shutil.which(args.adb)
    if not adb:
        parser.error("adb not found; install Android SDK platform-tools or pass --adb")
    base = [adb, "-P", str(args.server_port)]
    target = base + ["-s", args.serial]

    def run(command, timeout=30):
        subprocess.run(command, check=True, timeout=timeout)

    # Validate the local artifact before connecting to or changing a device.
    apk = None
    if args.action == "install":
        manifest = ElementTree.parse(ROOT / "app/AndroidManifest.xml").getroot()
        version = manifest.get("{http://schemas.android.com/apk/res/android}versionName")
        apk = (args.apk or ROOT / "build" / ("apple-tv-volume-bridge-" + version + "-debug.apk")).resolve()
        if not apk.is_file():
            parser.error("APK not found; build first or pass --apk")

    if ":" in args.serial:
        run(base + ["connect", args.serial])
    run(target + ["get-state"])
    if args.action == "install":
        run(target + ["install", "-r", str(apk)], timeout=120)
        print("Installed. Run the start action or open the app on your TV.")
    elif args.action == "start":
        run(target + ["shell", "am", "start", "-n", PACKAGE + "/.MainActivity"])
    else:
        # Stop the foreground UI first, then its independent shell watcher/helper.
        run(target + ["shell", "am", "force-stop", PACKAGE])
        run(target + ["shell", STOP_HELPER])
        if args.action == "uninstall":
            run(target + ["uninstall", PACKAGE])
            files = [KEY_PATH, KEY_PATH + ".tmp"]
            for version in (2, 3):
                files += ["/data/local/tmp/cec-bridge-helper-v%d.%s" % (version, ext)
                          for ext in ("jar", "jar.tmp", "log")]
            run(target + ["shell", "rm", "-f"] + files)
            # Only remove this public alpha's own shell-private configuration.
            # Never touch the prototype's separate private directory.
            run(target + ["shell", """set -eu
private_dir=/data/local/tmp/cec-bridge-private
if [ -e "$private_dir" ] || [ -L "$private_dir" ]; then
  [ ! -L "$private_dir" ] && [ -d "$private_dir" ] || exit 1
  [ "$(stat -c %u "$private_dir")" = "$(id -u)" ] || exit 1
  rm -f "$private_dir/companion.conf" "$private_dir"/.companion.*
  rmdir "$private_dir"
fi
"""])
            print("App, public-alpha pairing configuration and known helper files removed. ADB settings were not changed.")
        else:
            print("App and helper stopped; opening the app later will start it again.")


if __name__ == "__main__":
    try:
        main()
    except (subprocess.CalledProcessError, subprocess.TimeoutExpired, OSError) as error:
        print("Failed: " + str(error), file=sys.stderr)
        sys.exit(1)
