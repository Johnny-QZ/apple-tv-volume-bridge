#!/usr/bin/env python3
"""Offline tests for dependency repacking and private provisioning; no devices contacted."""
import argparse
import asyncio
import ipaddress
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import types
import unittest
from unittest.mock import AsyncMock, patch
import zipfile

import dependencies
import provision


class SetupTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="bridge-setup-test-")
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)

    def test_android_repack(self):
        original = self.root / "original.jar"
        with zipfile.ZipFile(original, "w") as archive:
            archive.writestr("org/example/B.class", b"unchanged B")
            archive.writestr("META-INF/versions/9/org/example/B.class", b"different version")
            archive.writestr("META-INF/test.SF", b"signature")
            archive.writestr("module-info.class", b"module")
            archive.writestr("resource.properties", b"unused")
            archive.writestr("org/example/A.class", b"unchanged A")
        first = self.root / "first.jar"
        second = self.root / "second.jar"
        dependencies.repack_bouncy_castle(original, first)
        dependencies.repack_bouncy_castle(original, second)
        self.assertEqual(first.read_bytes(), second.read_bytes())
        with zipfile.ZipFile(first) as archive:
            self.assertEqual(archive.namelist(), ["org/example/A.class", "org/example/B.class"])
            self.assertEqual(archive.read("org/example/B.class"), b"unchanged B")

    def test_bad_dependency_hash_is_rejected(self):
        source = self.root / "input.jar"
        source.write_bytes(b"tampered")
        with self.assertRaises(dependencies.DependencyError):
            dependencies.verify(source, "0" * 64)

    def fixture(self):
        identity = self.root / "private" / "companion.conf"
        provision.private_parent(identity)
        # Synthetic identity only; this is not a valid paired device.
        credential = ":".join(("ab" * 32, "cd" * 32, "01" * 16, "02" * 16))
        payload = ("appleTvHost=192.0.2.1\ncompanionPort=1234\ncompanionCredentials=" + credential
                   + "\nheadphoneNames=演示耳机\n")
        provision.save_private(identity, payload)
        return identity, payload, credential

    @unittest.skipUnless(os.name == "posix", "POSIX private provisioning only")
    def test_private_creation_and_permissions(self):
        identity, payload, _ = self.fixture()
        self.assertEqual(identity.stat().st_mode & 0o777, 0o600)
        self.assertEqual(identity.parent.stat().st_mode & 0o777, 0o700)
        self.assertEqual(provision.read_private(identity), payload.encode("utf-8"))
        with self.assertRaises(FileExistsError):
            provision.private_parent(identity)
        with self.assertRaises(FileExistsError):
            provision.save_private(identity, "overwrite")
        link = identity.parent / "linked.conf"
        link.symlink_to(identity)
        with self.assertRaises(OSError):
            provision.read_private(link)
        identity.chmod(0o644)
        with self.assertRaises(ValueError):
            provision.read_private(identity)

    def test_headphone_names(self):
        for name in ("", "one|two", "one\ntwo"):
            with self.assertRaises(argparse.ArgumentTypeError):
                provision.headphone_name(name)
        self.assertEqual(provision.headphone_name(" Example Headphones "), "Example Headphones")
        self.assertEqual(provision.properties_value(r"a\b"), r"a\\b")

    def test_pairing_credentials_match_native_key_format(self):
        fields = ["ab" * 32, "cd" * 32, "01" * 16, "02" * 16]
        value = ":".join(fields)
        self.assertEqual(provision.valid_credentials(value), value)
        for index, replacement in ((1, "cd" * 64), (2, "01" * 513), (3, "")):
            invalid = fields.copy()
            invalid[index] = replacement
            with self.assertRaises(ValueError):
                provision.valid_credentials(":".join(invalid))

    @unittest.skipUnless(os.name == "posix", "POSIX private provisioning only")
    def test_optional_airplay_failure_preserves_companion_identity(self):
        args = types.SimpleNamespace(out=self.root / "private" / "companion.conf",
                                     host=ipaddress.IPv4Address("192.0.2.1"),
                                     headphone_name=["Example Headphones"], pair_airplay=True)
        protocol = types.SimpleNamespace(Companion=1, AirPlay=2)
        config = types.SimpleNamespace(address=args.host,
                                       get_service=lambda _: types.SimpleNamespace(port=1234))
        fake_pyatv = types.SimpleNamespace(scan=AsyncMock(return_value=[config]))
        credential = ":".join(("ab" * 32, "cd" * 32, "01" * 16, "02" * 16))
        with patch.dict(sys.modules, {"pyatv": fake_pyatv,
                                     "pyatv.const": types.SimpleNamespace(Protocol=protocol)}), \
                patch.object(provision, "version", return_value="0.18.0"), \
                patch.object(provision, "pair_one", side_effect=[credential, RuntimeError("synthetic failure")]):
            with self.assertRaises(RuntimeError):
                asyncio.run(provision.pair(args))
        stored = provision.read_private(args.out).decode("utf-8")
        self.assertIn("companionCredentials=" + credential, stored)
        self.assertNotIn("airplayCredentials=", stored)
        self.assertEqual(fake_pyatv.scan.call_args.kwargs["hosts"], ["192.0.2.1"])

    @unittest.skipUnless(os.name == "posix", "POSIX private provisioning only")
    def test_install_uses_explicit_target_and_secret_stdin(self):
        identity, payload, credential = self.fixture()
        args = types.SimpleNamespace(config=identity, serial="192.0.2.2:5555", server_port=5037,
                                     adb="adb", replace=False)
        with patch.object(provision.shutil, "which", return_value="/fake/adb"), \
                patch.object(provision.subprocess, "run") as run, patch("builtins.print"):
            provision.install(args)
        self.assertEqual(run.call_count, 2)
        call = run.call_args
        argv = call.args[0]
        self.assertEqual(argv[:7], ["/fake/adb", "-P", "5037", "-s", "192.0.2.2:5555", "shell", "-T"])
        self.assertNotIn(credential, repr(argv))
        self.assertEqual(call.kwargs["input"], payload.encode("utf-8"))
        self.assertIn("exit 30", argv[-1])  # Refuse an existing config without --replace.
        self.assertIn('[ "$(stat -c %a "$private_dir")" = 700 ]', argv[-1])
        self.assertIn('[ "$(stat -c %a "$config")" = 600 ]', argv[-1])
        subprocess.run(["/bin/sh", "-n"], input=argv[-1].encode(), check=True)


if __name__ == "__main__":
    unittest.main()
