#!/usr/bin/env python3
"""Publication policy fixtures; no Android APK/signature execution claims."""
import contextlib
import io
import os
import subprocess
import sys
import tempfile
import unittest
import zipfile
from unittest.mock import patch
import inspect_apk


class PublicationTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.apk = os.path.join(self.temp.name, "candidate.apk")
        self.previous = os.path.join(self.temp.name, "previous.apk")
        with open(self.previous, "wb") as f:
            f.write(b"previous-fixture")
        with zipfile.ZipFile(self.apk, "w") as z:
            for abi in ("armeabi-v7a", "arm64-v8a"):
                for lib in ("mobileglues", "SDL3", "SDL2", "openal", "spirv-cross-c-shared"):
                    z.writestr(f"lib/{abi}/lib{lib}.so", b"fixture")
                for version in ("3.3.3", "3.4.1"):
                    z.writestr(f"assets/components/lwjgl-{version}-natives/{abi}/liblwjgl.so", b"fixture")
        self.current = ("com.cslaucher.plus.debug", 10010020, ("cert",), 21)
        self.prior = (self.current[0], 10010018, self.current[2], 21)

    def verify(self, prior=None, current=None, previous=True, required=True):
        def identity(path, *_):
            return ((current or self.current) if path == self.apk else (prior or self.prior)), "fixture signature", "fixture badging"
        def run(*args):
            if args[1] == "-h":
                wide = "arm64-v8a" in args[-1]
                return "Machine: " + ("AArch64" if wide else "ARM") + "\nClass: " + ("ELF64" if wide else "ELF32")
            return "Shared library: [libc.so]"
        with patch.object(inspect_apk, "identity", side_effect=identity), patch.object(inspect_apk, "run", side_effect=run), contextlib.redirect_stdout(io.StringIO()):
            inspect_apk.inspect(self.apk, "signer", "aapt", self.previous if previous else None, required)

    def test_matching_update(self):
        self.verify()

    def test_missing_previous_is_rejected(self):
        with self.assertRaisesRegex(ValueError, "previous APK"):
            self.verify(previous=False)

    def test_each_update_mismatch_is_rejected(self):
        for i, replacement in enumerate(("other.package", 10010020, ("other-cert",), 22)):
            with self.subTest(field=i):
                prior = list(self.prior)
                prior[i] = replacement
                with self.assertRaisesRegex(ValueError, "publication blocked"):
                    self.verify(prior=tuple(prior))

    def test_version_downgrade_is_rejected(self):
        prior = list(self.prior)
        prior[1] = self.current[1] + 1
        with self.assertRaisesRegex(ValueError, "publication blocked"):
            self.verify(prior=tuple(prior))

    def test_changed_min_sdk_is_rejected(self):
        current = list(self.current)
        current[3] = 22
        with self.assertRaisesRegex(ValueError, "Unexpected minSDK"):
            self.verify(current=tuple(current))

    def test_optional_inspection_without_previous(self):
        self.verify(previous=False, required=False)

    def test_real_identity_parser(self):
        signature = "Signer #1 certificate SHA-256 digest: abc123\n"
        badging = "package: name='com.cslaucher.plus.debug' versionCode='10010020'\nsdkVersion:'21'\ntargetSdkVersion:'34'\n"
        with patch.object(inspect_apk, "run", side_effect=[signature, badging]):
            ident, _, _ = inspect_apk.identity("fixture.apk", "signer", "aapt")
        self.assertEqual(ident, (self.current[0], self.current[1], ("abc123",), 21))

    def test_incomplete_identity_is_rejected(self):
        with patch.object(inspect_apk, "run", return_value="malformed"):
            with self.assertRaisesRegex(ValueError, "Missing package"):
                inspect_apk.identity("fixture.apk", "signer", "aapt")

    def test_signature_tool_failure_propagates(self):
        with patch.object(inspect_apk, "run", side_effect=subprocess.CalledProcessError(1, "apksigner")):
            with self.assertRaises(subprocess.CalledProcessError):
                inspect_apk.identity("fixture.apk", "signer", "aapt")

    def test_cli_failure_is_not_hidden_by_tee(self):
        inspector = os.path.join(os.path.dirname(__file__), "inspect_apk.py")
        result = subprocess.run(["bash", "-c", 'set -euo pipefail; "$1" "$2" "$3" --require-update 2>&1 | tee "$4"',
                                 "fixture", sys.executable, inspector, self.apk, os.path.join(self.temp.name, "pipeline.txt")],
                                stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("previous APK", result.stdout)

    def test_incorrect_abi_is_rejected(self):
        with zipfile.ZipFile(self.apk, "a") as z:
            z.writestr("lib/x86/libfixture.so", b"fixture")
        with self.assertRaisesRegex(ValueError, "Expected ARM32"):
            self.verify()

    def test_empty_native_is_rejected(self):
        replacement = self.apk + ".tmp"
        with zipfile.ZipFile(self.apk) as original, zipfile.ZipFile(replacement, "w") as changed:
            for name in original.namelist():
                changed.writestr(name, b"" if name == "lib/arm64-v8a/libmobileglues.so" else original.read(name))
        os.replace(replacement, self.apk)
        with self.assertRaisesRegex(ValueError, "Missing native"):
            self.verify()

    def test_newer_api_system_dependency_is_not_accepted_on_api21(self):
        for library in ("libvulkan.so", "libaaudio.so"):
            with self.subTest(library=library):
                def run(*args):
                    if args[1] == "-h":
                        wide = "arm64-v8a" in args[-1]
                        return "Machine: " + ("AArch64" if wide else "ARM") + "\nClass: " + ("ELF64" if wide else "ELF32")
                    return f"Shared library: [{library}]"
                # verify() patches run internally, so use the inspector directly.
                with patch.object(inspect_apk, "identity", return_value=(self.current, "fixture", "fixture")), patch.object(inspect_apk, "run", side_effect=run), contextlib.redirect_stdout(io.StringIO()):
                    with self.assertRaisesRegex(ValueError, "Missing MobileGlues dependencies"):
                        inspect_apk.inspect(self.apk, "signer", "aapt")


if __name__ == "__main__":
    unittest.main()
