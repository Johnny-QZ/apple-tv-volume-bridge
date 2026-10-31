#!/usr/bin/env python3
"""Fetch pinned Maven inputs and produce the Android-only Bouncy Castle input JAR."""
import argparse
import hashlib
import os
from pathlib import Path
import tempfile
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parents[1]
DEFAULT_CACHE = ROOT / "build/deps"
MAVEN = "https://repo.maven.apache.org/maven2/"
DEPENDENCIES = (
    ("org/bouncycastle/bcprov-jdk18on/1.86/bcprov-jdk18on-1.86.jar",
     "2af190b300cbb0b35e248ccf5f4a06b6072030aeb3da7a98ec73abe5b4cb371f"),
    ("com/googlecode/plist/dd-plist/1.28/dd-plist-1.28.jar",
     "88ed8e730f7386297485176c4387146c6914a38c0e58fc296e8a01cdc3b621e1"),
)


class DependencyError(Exception):
    pass


def digest(path):
    with path.open("rb") as stream:
        value = hashlib.sha256()
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            value.update(chunk)
        return value.hexdigest()


def verify(path, expected):
    if not path.is_file() or digest(path) != expected:
        raise DependencyError("Missing or SHA-256 mismatch: {}. Run scripts/dependencies.py.".format(path))


def repack_bouncy_castle(original, destination):
    # Match the prototype's Android input: unchanged Java 8 classes only. Exclude
    # multi-release classes, module metadata, signatures and unused resources.
    # Fixed metadata and uncompressed ZIP entries make this repack deterministic.
    with zipfile.ZipFile(original) as source, zipfile.ZipFile(destination, "w") as target:
        for name in sorted(source.namelist()):
            if name.startswith("META-INF/") or not name.endswith(".class"):
                continue
            if name == "module-info.class":
                continue
            info = zipfile.ZipInfo(name, date_time=(1980, 1, 1, 0, 0, 0))
            info.create_system = 3
            info.external_attr = 0o100644 << 16
            info.compress_type = zipfile.ZIP_STORED
            target.writestr(info, source.read(name))


def prepare(cache=DEFAULT_CACHE, download=False):
    cache = Path(cache).expanduser().resolve()
    cache.mkdir(parents=True, exist_ok=True)
    for relative, expected in DEPENDENCIES:
        destination = cache / Path(relative).name
        if not destination.exists() and download:
            request = urllib.request.Request(MAVEN + relative, headers={"User-Agent": "apple-tv-volume-bridge-build"})
            with urllib.request.urlopen(request, timeout=60) as response:
                if not response.url.startswith("https://"):
                    raise DependencyError("Refusing an insecure download redirect")
                with tempfile.NamedTemporaryFile(dir=cache, delete=False) as output:
                    temporary = Path(output.name)
                    try:
                        total = 0
                        while True:
                            chunk = response.read(1024 * 1024)
                            if not chunk:
                                break
                            total += len(chunk)
                            if total > 32 * 1024 * 1024:
                                raise DependencyError("Dependency download exceeded size limit")
                            output.write(chunk)
                        output.close()
                        verify(temporary, expected)
                        os.replace(temporary, destination)
                    finally:
                        temporary.unlink(missing_ok=True)
        verify(destination, expected)
    derived = cache / "bc-android-input.jar"
    # Always regenerate from the verified original; never trust an arbitrary
    # cached repack or the private prototype's binary.
    with tempfile.NamedTemporaryFile(dir=cache, suffix=".jar", delete=False) as stream:
        temporary = Path(stream.name)
    try:
        repack_bouncy_castle(cache / "bcprov-jdk18on-1.86.jar", temporary)
        os.replace(temporary, derived)
    finally:
        temporary.unlink(missing_ok=True)
    return [derived, cache / "dd-plist-1.28.jar"]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--cache", type=Path, default=DEFAULT_CACHE, help="Dependency cache (default: build/deps)")
    parser.add_argument("--offline", action="store_true", help="Verify existing originals and repack without network access")
    args = parser.parse_args()
    try:
        for path in prepare(args.cache, download=not args.offline):
            print("Verified input: {}".format(path.name))
    except (DependencyError, OSError, zipfile.BadZipFile) as error:
        parser.exit(1, "Dependencies failed: {}\n".format(error))


if __name__ == "__main__":
    main()
