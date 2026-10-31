#!/usr/bin/env python3
"""Compile shared code and run standalone Java tests offline; no Android device is used."""

import argparse
from pathlib import Path
import re
import tempfile

from build import BuildError, ROOT, executable, java_sources, resolve_java_home, run_checked


def discover_tests(sources):
    names = []
    for source in sources:
        text = source.read_text(encoding="utf-8")
        if not re.search(r"\bpublic\s+static\s+void\s+main\s*\(", text):
            continue
        package = re.search(r"^\s*package\s+([\w.$]+)\s*;", text, re.MULTILINE)
        names.append((package.group(1) + "." if package else "") + source.stem)
    # Run the authentication regression test first, followed by new main-based tests.
    return sorted(names, key=lambda name: (name.rsplit(".", 1)[-1] != "BridgeAuthTest", name))


def test(args):
    java_home = resolve_java_home(args.java_home)
    sources = java_sources(ROOT / "tests")
    discovered = discover_tests(sources)
    tests = args.main or discovered
    if not tests:
        raise BuildError("No public static void main tests found under tests/.")
    if not args.main and not any(name.rsplit(".", 1)[-1] == "BridgeAuthTest" for name in tests):
        raise BuildError("BridgeAuthTest main was not found under tests/.")
    out = Path(args.out).expanduser().resolve()
    out.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix=".tests-", dir=str(out)) as temporary:
        classes = Path(temporary)
        run_checked([
            executable(java_home / "bin", "javac"), "-encoding", "UTF-8",
            "--release", "8", "-d", classes,
        ] + java_sources(ROOT / "shared/src") + sources)
        for name in tests:
            output = run_checked([executable(java_home / "bin", "java"), "-ea", "-cp", classes, name])
            print("PASS {}".format(name))
            if output.strip():
                print(output.strip())
    print("{} test program(s) passed.".format(len(tests)))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--java-home", help="JDK 17 home; defaults to JAVA_HOME, then javac on PATH")
    parser.add_argument("--out", default=str(ROOT / "build/tests"), help="Temporary output parent (default: build/tests/)")
    parser.add_argument("--main", action="append", metavar="CLASS", help="Run a fully qualified test main; repeat for multiple tests")
    args = parser.parse_args()
    try:
        test(args)
    except (BuildError, OSError) as error:
        parser.exit(1, "Tests failed: {}\n".format(error))


if __name__ == "__main__":
    main()
