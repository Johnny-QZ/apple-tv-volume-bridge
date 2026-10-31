#!/usr/bin/env python3
"""Build the Android app and shell helper offline with JDK 17 and Android SDK tools."""

import argparse
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import xml.etree.ElementTree as ET
import zipfile

from dependencies import DEFAULT_CACHE, DependencyError, prepare


ROOT = Path(__file__).resolve().parents[1]
MIN_API = 23
TARGET_API = 28


class BuildError(Exception):
    pass


def run_checked(command):
    """Run an argv list without a shell; show tool output only on failure."""
    try:
        result = subprocess.run(
            [str(part) for part in command], stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT, text=True, encoding="utf-8", errors="replace",
        )
    except OSError as error:
        raise BuildError("Cannot run {}: {}".format(command[0], error)) from error
    if result.returncode:
        raise BuildError("{} failed (exit {}):\n{}".format(
            Path(str(command[0])).name, result.returncode, result.stdout.strip()
        ))
    return result.stdout


def executable(directory, name):
    path = Path(directory) / (name + (".exe" if os.name == "nt" else ""))
    if not path.is_file():
        raise BuildError("Missing tool: {}".format(path))
    return path


def resolve_java_home(value=None):
    candidate = value or os.environ.get("JAVA_HOME")
    if candidate:
        home = Path(candidate).expanduser().resolve()
    else:
        javac = shutil.which("javac")
        if not javac:
            raise BuildError("JDK 17 not found. Set JAVA_HOME or pass --java-home.")
        home = Path(javac).resolve().parent.parent
    for name in ("java", "javac", "keytool"):
        executable(home / "bin", name)
    version = run_checked([executable(home / "bin", "javac"), "-version"])
    if not re.search(r"\bjavac\s+17(?:\.|\s|$)", version):
        raise BuildError("JDK 17 is required; found {}. Pass --java-home.".format(version.strip()))
    return home


def sdk_roots():
    candidates = [os.environ.get("ANDROID_SDK_ROOT"), os.environ.get("ANDROID_HOME")]
    candidates.extend([
        Path.home() / "Library/Android/sdk",
        Path.home() / "Android/Sdk",
        Path(os.environ.get("LOCALAPPDATA", str(Path.home() / "AppData/Local"))) / "Android/Sdk",
    ])
    roots = []
    for candidate in candidates:
        if candidate:
            path = Path(candidate).expanduser().resolve()
            if path.is_dir() and path not in roots:
                roots.append(path)
    return roots


def version_key(path):
    # Prefer a stable release over a preview with the same numeric version.
    match = re.search(r"\d+(?:\.\d+)*", path.name)
    numbers = tuple(int(n) for n in match.group(0).split(".")) if match else ()
    suffix = path.name[match.end():] if match else path.name
    return (numbers, not suffix, suffix)


def resolve_android_jar(value=None):
    if value:
        jar = Path(value).expanduser().resolve()
        if not jar.is_file():
            raise BuildError("Android API jar does not exist: {}".format(jar))
        return jar
    for root in sdk_roots():
        preferred = root / "platforms/android-28/android.jar"
        if preferred.is_file():
            return preferred
        platforms = sorted((root / "platforms").glob("android-*"), key=version_key, reverse=True)
        for platform in platforms:
            jar = platform / "android.jar"
            if jar.is_file():
                return jar
    raise BuildError("Android SDK platform not found. Set ANDROID_SDK_ROOT or pass --android-jar.")


def validate_build_tools(directory):
    executable(directory, "aapt")
    for name in ("d8.jar", "apksigner.jar"):
        if not (directory / "lib" / name).is_file():
            raise BuildError("Missing tool: {}".format(directory / "lib" / name))
    return directory


def resolve_build_tools(value=None):
    if value:
        return validate_build_tools(Path(value).expanduser().resolve())
    for root in sdk_roots():
        for directory in sorted((root / "build-tools").glob("*"), key=version_key, reverse=True):
            if directory.is_dir():
                try:
                    return validate_build_tools(directory)
                except BuildError:
                    continue
    raise BuildError("Android build-tools (aapt, d8.jar, apksigner.jar) not found. Pass --build-tools.")


def java_sources(directory, required=True):
    files = sorted(directory.rglob("*.java"))
    if required and not files:
        raise BuildError("No Java sources in {}".format(directory))
    return files


def compile_dex(java_home, android_jar, build_tools, sources, stage, name, dependencies=()):
    classes = stage / (name + "-classes")
    dex = stage / (name + "-dex")
    classes.mkdir()
    dex.mkdir()
    run_checked([
        executable(java_home / "bin", "javac"), "-encoding", "UTF-8",
        "-source", "8", "-target", "8", "-bootclasspath", android_jar,
        "-d", classes,
    ] + (["-classpath", os.pathsep.join(str(p) for p in dependencies)] if dependencies else []) + sources)
    if dependencies:
        rules = stage / "helper-r8.pro"
        rules.write_text("-keep public class BridgeHelper { public static void main(java.lang.String[]); }\n"
                         "-dontobfuscate\n-dontwarn javax.naming.**\n"
                         "-dontwarn java.lang.management.**\n"
                         "-dontwarn java.security.cert.CertPathValidatorException$BasicReason\n", encoding="utf-8")
        program = stage / "helper-classes.jar"
        with zipfile.ZipFile(program, "w") as archive:
            for source in sorted(classes.rglob("*.class")):
                archive.write(source, source.relative_to(classes).as_posix())
        run_checked([executable(java_home / "bin", "java"), "-cp", build_tools / "lib/d8.jar",
                     "com.android.tools.r8.R8", "--release", "--min-api", str(MIN_API),
                     "--lib", android_jar, "--pg-conf", rules, "--output", dex, program] + list(dependencies))
        if not (dex / "classes.dex").is_file() or (dex / "classes2.dex").exists():
            raise BuildError("Helper must produce exactly one classes.dex for app_process")
        return dex
    run_checked([
        executable(java_home / "bin", "java"), "-cp", build_tools / "lib/d8.jar",
        "com.android.tools.r8.D8", "--min-api", str(MIN_API), "--lib", android_jar,
        "--output", dex,
    ] + sorted(classes.rglob("*.class")))
    if not (dex / "classes.dex").is_file():
        raise BuildError("D8 did not create {} classes.dex".format(name))
    return dex


def add_dex(archive, directory):
    with zipfile.ZipFile(archive, "a", compression=zipfile.ZIP_DEFLATED) as target:
        for dex in sorted(directory.glob("classes*.dex")):
            target.write(dex, dex.name)


def build(args):
    java_home = resolve_java_home(args.java_home)
    android_jar = resolve_android_jar(args.android_jar)
    build_tools = resolve_build_tools(args.build_tools)
    out = Path(args.out).expanduser().resolve()
    out.mkdir(parents=True, exist_ok=True)
    dependencies = prepare(args.deps, download=False)
    shared = java_sources(ROOT / "shared/src")
    helper_sources = java_sources(ROOT / "helper/src") + shared
    app_sources = java_sources(ROOT / "app/src") + shared
    manifest = ROOT / "app/AndroidManifest.xml"
    if not manifest.is_file():
        raise BuildError("Missing manifest: {}".format(manifest))
    version = ET.parse(manifest).getroot().get("{http://schemas.android.com/apk/res/android}versionName", "")
    if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._+-]*", version):
        raise BuildError("Manifest versionName must be nonempty and safe to use in an APK filename.")
    apk_name = "apple-tv-volume-bridge-{}-debug.apk".format(version)

    print("Using JDK 17: {}".format(java_home))
    print("Using Android platform: {}".format(android_jar))
    print("Using build-tools: {}".format(build_tools))
    with tempfile.TemporaryDirectory(prefix=".build-", dir=str(out)) as temporary:
        stage = Path(temporary)
        print("Building shell helper...")
        helper_dex = compile_dex(java_home, android_jar, build_tools, helper_sources, stage, "helper", dependencies)
        assets = stage / "assets"
        if (ROOT / "app/assets").is_dir():
            shutil.copytree(ROOT / "app/assets", assets)
        else:
            assets.mkdir()
        shutil.copy2(ROOT / "THIRD_PARTY_LICENSES.md", assets / "THIRD_PARTY_LICENSES.md")
        helper_jar = assets / "bridge-helper.jar"
        # Always replace any prebuilt helper asset with a fresh source build.
        with zipfile.ZipFile(helper_jar, "w"):
            pass
        add_dex(helper_jar, helper_dex)
        with zipfile.ZipFile(helper_jar, "a") as archive:
            archive.write(ROOT / "THIRD_PARTY_LICENSES.md", "META-INF/THIRD_PARTY_LICENSES.md")

        print("Building Android app...")
        app_dex = compile_dex(java_home, android_jar, build_tools, app_sources, stage, "app")
        resources = ROOT / "app/res"
        if not resources.is_dir():
            resources = stage / "res"
            resources.mkdir()
        unsigned = stage / "unsigned.apk"
        run_checked([
            executable(build_tools, "aapt"), "package", "-f", "-A", assets,
            "-S", resources, "-M", manifest, "-I", android_jar,
            "--min-sdk-version", str(MIN_API), "--target-sdk-version", str(TARGET_API),
            "-F", unsigned,
        ])
        add_dex(unsigned, app_dex)
        zipalign = build_tools / ("zipalign.exe" if os.name == "nt" else "zipalign")
        if zipalign.is_file():
            aligned = stage / "aligned.apk"
            run_checked([zipalign, "-f", "4", unsigned, aligned])
            unsigned = aligned
        else:
            print("zipalign not installed; skipping optional alignment.")

        keystore = out / "debug.keystore"
        if not keystore.exists():
            run_checked([
                executable(java_home / "bin", "keytool"), "-genkeypair", "-noprompt",
                "-keystore", keystore, "-storetype", "JKS", "-storepass", "android",
                "-keypass", "android", "-alias", "androiddebugkey", "-keyalg", "RSA",
                "-keysize", "2048", "-validity", "10000",
                "-dname", "CN=Android Debug,O=Android,C=US",
            ])
        print("Signing and verifying debug APK...")
        signed = stage / apk_name
        signer = [executable(java_home / "bin", "java"), "-jar", build_tools / "lib/apksigner.jar"]
        run_checked(signer + [
            "sign", "--ks", keystore, "--ks-key-alias", "androiddebugkey",
            "--ks-pass", "pass:android", "--key-pass", "pass:android",
            "--min-sdk-version", str(MIN_API), "--out", signed, unsigned,
        ])
        run_checked(signer + ["verify", "--min-sdk-version", str(MIN_API), signed])
        if zipalign.is_file():
            run_checked([zipalign, "-c", "4", signed])
        shutil.copy2(helper_jar, out / "bridge-helper.jar")
        shutil.copy2(signed, out / signed.name)
    print("Built: {}".format(out / apk_name))
    print("Helper: {}".format(out / "bridge-helper.jar"))
    print("Debug signing key: {} (keep private and retain for updates)".format(keystore))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--java-home", help="JDK 17 home; defaults to JAVA_HOME, then javac on PATH")
    parser.add_argument("--android-jar", help="Android platform android.jar; API 28 is preferred")
    parser.add_argument("--build-tools", help="Android SDK build-tools directory containing aapt and lib/")
    parser.add_argument("--deps", default=str(DEFAULT_CACHE), help="Verified Maven originals cache (default: build/deps/)")
    parser.add_argument("--out", default=str(ROOT / "build"), help="Output directory (default: project build/)")
    args = parser.parse_args()
    try:
        build(args)
    except (BuildError, DependencyError, OSError, ET.ParseError, zipfile.BadZipFile) as error:
        parser.exit(1, "Build failed: {}\n".format(error))


if __name__ == "__main__":
    main()
