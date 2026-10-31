#!/usr/bin/env python3
"""Run helper protocol/queue regressions offline with a deterministic Android clock stub."""
import argparse
import os
from pathlib import Path
import tempfile

from build import BuildError, ROOT, executable, java_sources, resolve_android_jar, resolve_java_home, run_checked
from dependencies import DEFAULT_CACHE, DependencyError, prepare


def test(args):
    java_home = resolve_java_home(args.java_home)
    android_jar = resolve_android_jar(args.android_jar)
    dependencies = prepare(args.deps, download=False)
    classpath = os.pathsep.join(str(path) for path in [android_jar] + dependencies)
    out = Path(args.out).expanduser().resolve()
    out.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix=".native-tests-", dir=str(out)) as temporary:
        classes = Path(temporary)
        sources = java_sources(ROOT / "helper/src") + java_sources(ROOT / "shared/src") + java_sources(ROOT / "native-tests")
        run_checked([executable(java_home / "bin", "javac"), "-encoding", "UTF-8", "--release", "8",
                     "-classpath", classpath, "-d", classes] + sources)
        result = run_checked([executable(java_home / "bin", "java"), "-ea", "-cp",
                              str(classes) + os.pathsep + classpath, "HelperOfflineTest"])
        print(result.strip())
    print("Native helper regressions passed without network or device access.")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--java-home", help="JDK 17 home")
    parser.add_argument("--android-jar", help="Android platform android.jar")
    parser.add_argument("--deps", default=str(DEFAULT_CACHE), help="Verified dependency cache (default: build/deps)")
    parser.add_argument("--out", default=str(ROOT / "build/tests"), help="Temporary output parent")
    args = parser.parse_args()
    try:
        test(args)
    except (BuildError, DependencyError, OSError) as error:
        parser.exit(1, "Native tests failed: {}\n".format(error))


if __name__ == "__main__":
    main()
