#!/usr/bin/env python3
"""Exercise bootstrap security offline with synthetic Android peers and a fake ADB daemon."""
import argparse
import ast
from pathlib import Path
import subprocess
import tempfile

from build import BuildError, ROOT, executable, java_sources, resolve_java_home, run_checked


def check_stop_script(script, directory):
    name = "local.cecprobe.bootstrap.v4." + "a" * 32
    fixtures = [
        (1, ["app_process", "/system/bin", "BridgeHelper", "--bootstrap", name, "10001"], True),
        (2, ["BridgeHelper", "--bootstrap", name, "10001"], True),
        (3, ["app_process", "/system/bin", "BridgeHelper", "--bootstrap", name, "10001", "extra"], False),
        (4, ["BridgeHelper", "--bootstrap", name, "0"], False),
        (5, ["BridgeHelper", "--bootstrap", name + ";bad", "10001"], False),
        (6, ["app_process", "/system/bin", "OtherHelper"], False),
        (7, ["BridgeHelper", "/data/local/tmp/cec-bridge-v3.key"], True),
    ]
    for pid, argv, _ in fixtures:
        (directory / str(pid)).write_bytes(("\0".join(argv) + "\0").encode())
    # Replace /proc only in the fixture; kill/settings/sleep are harmless shell functions.
    script = script.replace("/proc/$task_pid/cmdline", '"' + str(directory) + '/$task_pid"')
    prefix = "pidof(){ echo '1 2 3 4 5 6 7'; }; kill(){ echo \"$1\"; }; sleep(){ :; }; settings(){ :; };\n"
    result = subprocess.run(["/bin/sh"], input=prefix + script, text=True, capture_output=True, check=True)
    actual = set(result.stdout.split())
    expected = {str(pid) for pid, _, matched in fixtures if matched}
    if actual != expected:
        raise BuildError("Helper process matcher accepted incorrect argv: " + repr(actual))


def test(args):
    java_home = resolve_java_home(args.java_home)
    out = Path(args.out).expanduser().resolve()
    out.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix=".bootstrap-tests-", dir=str(out)) as temporary:
        stage = Path(temporary)
        sources = java_sources(ROOT / "shared/src") + java_sources(ROOT / "bootstrap-tests")
        app = ROOT / "app/src/local/cecprobe"
        sources += [app / "BridgeBootstrap.java", app / "BridgeSecrets.java"]
        # Replace only the TCP socket factory, retaining production ADB handshake,
        # install/start logic and key handling. All mock I/O stays in memory.
        original = (app / "LocalAdb.java").read_text(encoding="utf-8")
        needle = "Socket socket=new Socket();"
        if original.count(needle) != 1:
            raise BuildError("Update the synthetic socket fixture for changed LocalAdb construction")
        local_adb = stage / "LocalAdb.java"
        local_adb.write_text(original.replace(needle, "Socket socket=new FakeAdbSocket();"), encoding="utf-8")
        sources.append(local_adb)
        classes = stage / "classes"
        run_checked([executable(java_home / "bin", "javac"), "-encoding", "UTF-8", "--release", "8", "-d", classes] + sources)
        command = [executable(java_home / "bin", "java"), "-ea", "-cp", classes, "local.cecprobe.BootstrapSecurityTest"]
        print(run_checked(command).strip())
        app_stop = run_checked(command + ["--stop-script"])
        fixtures = stage / "processes"
        fixtures.mkdir()
        check_stop_script(app_stop, fixtures)
        module = ast.parse((ROOT / "scripts/device.py").read_text(encoding="utf-8"))
        node = next(item for item in module.body if isinstance(item, ast.Assign)
                    and any(isinstance(target, ast.Name) and target.id == "STOP_HELPER" for target in item.targets))
        check_stop_script(ast.literal_eval(node.value), fixtures)
        print("PASS app and device-script helper argv matchers; all tests used synthetic peers only")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--java-home", help="JDK 17 home")
    parser.add_argument("--out", default=str(ROOT / "build/tests"), help="Temporary output parent")
    args = parser.parse_args()
    try:
        test(args)
    except (BuildError, OSError, subprocess.SubprocessError) as error:
        parser.exit(1, "Bootstrap tests failed: {}\n".format(error))


if __name__ == "__main__":
    main()
