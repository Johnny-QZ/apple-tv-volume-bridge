#!/usr/bin/env python3
"""Pair your own Apple TV, then install its private configuration on an explicit TV."""
import argparse
import asyncio
import getpass
from importlib.metadata import version
import ipaddress
import logging
import os
from pathlib import Path
import re
import shutil
import stat
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
DEFAULT_CONFIG = ROOT / ".private/companion.conf"
REMOTE_DIRECTORY = "/data/local/tmp/cec-bridge-private"


def headphone_name(value):
    value = value.strip()
    if not value or len(value) > 200 or "|" in value or any(ord(c) < 32 for c in value):
        raise argparse.ArgumentTypeError("Use an exact, nonempty headphone name without | or control characters")
    return value


def private_parent(path):
    parent = path.parent
    if not parent.exists():
        parent.mkdir(mode=0o700, parents=True)
    info = parent.lstat()
    if not stat.S_ISDIR(info.st_mode) or info.st_uid != os.getuid() or stat.S_IMODE(info.st_mode) != 0o700:
        raise ValueError("Output parent must be your own real directory with mode 0700")
    if path.exists() or path.is_symlink():
        raise FileExistsError("Output already exists; choose a new file instead of overwriting credentials")


def save_private(path, payload):
    descriptor = os.open(str(path), os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    try:
        with os.fdopen(descriptor, "w", encoding="utf-8", newline="\n") as stream:
            stream.write(payload)
            stream.flush()
            os.fsync(stream.fileno())
    except BaseException:
        path.unlink(missing_ok=True)
        raise


def valid_credentials(value):
    fields = str(value).split(":")
    if len(fields) != 4 or any(not re.fullmatch(r"(?:[a-fA-F0-9]{2})+", field) for field in fields):
        raise ValueError("Expected HomeKit-style credentials; legacy pairing is unsupported")
    if len(fields[0]) != 64 or len(fields[1]) != 64 or any(len(field) > 1024 for field in fields):
        raise ValueError("Unexpected pairing key lengths")
    return str(value)


def properties_value(value):
    # The helper loads UTF-8 with Properties.load(Reader), not the Latin-1 stream overload.
    return str(value).replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "\\r")


async def pair_one(config, protocol, loop):
    import pyatv
    from pyatv.const import PairingRequirement
    service = config.get_service(protocol)
    if service is None or service.pairing not in (PairingRequirement.Mandatory, PairingRequirement.Optional):
        raise ValueError("Requested pairing service is not available")
    handler = await pyatv.pair(config, protocol, loop)
    try:
        await asyncio.wait_for(handler.begin(), 20)
        if not handler.device_provides_pin:
            raise ValueError("Unsupported pairing PIN flow")
        pin = getpass.getpass(protocol.name + " PIN shown on your Apple TV (hidden): ").strip()
        if not re.fullmatch(r"[0-9]{4}", pin):
            raise ValueError("Expected the four-digit PIN shown on your Apple TV")
        handler.pin(pin)
        await asyncio.wait_for(handler.finish(), 25)
        if not handler.has_paired or not handler.service.credentials:
            raise ValueError("Pairing did not complete")
        return valid_credentials(handler.service.credentials)
    finally:
        await asyncio.wait_for(handler.close(), 10)


async def pair(args):
    if version("pyatv") != "0.18.0":
        raise ValueError("Install the pinned requirements-pairing.txt first")
    import pyatv
    from pyatv.const import Protocol
    private_parent(args.out)
    loop = asyncio.get_running_loop()
    # Address-targeted discovery; never browse every device on the LAN.
    configs = await pyatv.scan(loop, hosts=[str(args.host)],
                              protocol={Protocol.Companion, Protocol.AirPlay}, timeout=5)
    configs = [config for config in configs if config.address == args.host]
    if len(configs) != 1:
        raise ValueError("Expected one Apple TV at the explicitly selected address")
    config = configs[0]
    companion = config.get_service(Protocol.Companion)
    airplay = config.get_service(Protocol.AirPlay)
    if companion is None:
        raise ValueError("Apple TV did not advertise Companion")
    fields = {
        "appleTvHost": str(args.host),
        "companionPort": companion.port,
        "companionCredentials": await pair_one(config, Protocol.Companion, loop),
        "airplayPort": airplay.port if airplay else 7000,
        "headphoneNames": "|".join(dict.fromkeys(args.headphone_name)),
    }
    def serialize():
        return ("# Private Apple TV identity. Never commit or share this file.\n"
                + "\n".join(key + "=" + properties_value(value) for key, value in fields.items()) + "\n")
    # Persist the successful Companion identity before optional second pairing.
    # If AirPlay pairing fails, the first identity remains available privately.
    save_private(args.out, serialize())
    if args.pair_airplay:
        fields["airplayCredentials"] = await pair_one(config, Protocol.AirPlay, loop)
        with tempfile.NamedTemporaryFile(mode="w", encoding="utf-8", newline="\n",
                                         prefix=".companion-", dir=args.out.parent, delete=False) as stream:
            temporary = Path(stream.name)
            try:
                stream.write(serialize())
                stream.flush()
                os.fsync(stream.fileno())
                stream.close()
                os.replace(temporary, args.out)
            finally:
                temporary.unlink(missing_ok=True)
    print("Pairing saved with mode 0600. No volume or power command was sent.")
    print("Next: stop the bridge, install this file on your TV, and start the bridge again.")


def read_private(path):
    descriptor = os.open(str(path), os.O_RDONLY | getattr(os, "O_NOFOLLOW", 0))
    with os.fdopen(descriptor, "rb") as stream:
        info = os.fstat(stream.fileno())
        if (not stat.S_ISREG(info.st_mode) or info.st_uid != os.getuid()
                or stat.S_IMODE(info.st_mode) != 0o600 or info.st_size > 16384):
            raise ValueError("Configuration must be your own regular 0600 file, at most 16 KiB")
        payload = stream.read(16385)
    text = payload.decode("utf-8")
    if "\x00" in text or not all(re.search(r"^" + field + r"=.+$", text, re.MULTILINE)
                                  for field in ("appleTvHost", "companionPort", "companionCredentials", "headphoneNames")):
        raise ValueError("Configuration is incomplete; use the pair command")
    return payload


def install(args):
    payload = read_private(args.config)
    if not re.fullmatch(r"[A-Za-z0-9_.:\[\]%-]+", args.serial) or args.serial.startswith("-"):
        raise ValueError("Invalid explicit ADB serial")
    if not 1 <= args.server_port <= 65535:
        raise ValueError("ADB server port must be 1..65535")
    adb = shutil.which(args.adb)
    if not adb:
        raise ValueError("adb was not found")
    base = [adb, "-P", str(args.server_port)]
    if ":" in args.serial:
        subprocess.run(base + ["connect", args.serial], check=True, timeout=20,
                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    # Secrets travel through stdin, never argv, logs, an APK asset or a public temp file.
    command = r'''set -eu
private_dir=/data/local/tmp/cec-bridge-private
[ "$(id -u)" = 2000 ] || exit 21
[ ! -L "$private_dir" ] || exit 22
if [ ! -e "$private_dir" ]; then (umask 077; mkdir "$private_dir"); fi
[ -d "$private_dir" ] || exit 23
[ "$(stat -c %u "$private_dir")" = 2000 ] || exit 24
[ "$(stat -c %a "$private_dir")" = 700 ] || exit 25
config="$private_dir/companion.conf"
[ ! -L "$config" ] || exit 26
if [ -e "$config" ]; then
  [ -f "$config" ] || exit 27
  [ "$(stat -c %u "$config")" = 2000 ] || exit 28
  [ "$(stat -c %a "$config")" = 600 ] || exit 29
  REPLACE_CHECK
fi
umask 077
temporary=$(mktemp "$private_dir/.companion.XXXXXXXX")
trap 'rm -f "$temporary"' EXIT HUP INT TERM
cat > "$temporary"
chmod 600 "$temporary"
mv -f "$temporary" "$config"
'''.replace("REPLACE_CHECK", ":" if args.replace else "exit 30")
    subprocess.run(base + ["-s", args.serial, "shell", "-T", command], input=payload,
                   check=True, timeout=30, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    print("Configuration installed privately. Restart the bridge helper to load it.")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="action", required=True)
    pairing = commands.add_parser("pair", help="Display a pairing PIN on your own Apple TV")
    pairing.add_argument("--host", required=True, type=ipaddress.IPv4Address, help="Explicit Apple TV IPv4 address")
    pairing.add_argument("--headphone-name", required=True, action="append", type=headphone_name,
                         help="Exact name of your own headphones; repeat for multiple names")
    pairing.add_argument("--out", type=Path, default=DEFAULT_CONFIG, help="New private file (default: .private/companion.conf)")
    pairing.add_argument("--pair-airplay", action="store_true", help="Also perform separate AirPlay pairing if required by your device")
    deploy = commands.add_parser("install", help="Install an existing private config through explicitly selected TV ADB")
    deploy.add_argument("--serial", required=True, help="Explicit TV ADB serial, e.g. TV_IP:5555")
    deploy.add_argument("--adb", default="adb")
    deploy.add_argument("--server-port", type=int, default=5037)
    deploy.add_argument("--config", type=Path, default=DEFAULT_CONFIG)
    deploy.add_argument("--replace", action="store_true", help="Atomically replace an existing public-alpha config")
    args = parser.parse_args()
    logging.disable(logging.CRITICAL)
    try:
        if os.name != "posix":
            raise ValueError("Private provisioning currently requires macOS or Linux")
        if args.action == "pair":
            args.out = args.out.expanduser().absolute()
            asyncio.run(pair(args))
        else:
            install(args)
    except (Exception, KeyboardInterrupt) as error:
        # Some third-party exceptions contain device data; do not print them.
        parser.exit(1, "Provisioning failed ({}). No secrets were printed. Check the setup guide and private-file permissions.\n".format(type(error).__name__))


if __name__ == "__main__":
    main()
