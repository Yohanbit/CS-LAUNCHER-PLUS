#!/usr/bin/env python3
"""Verify packaged ARM libraries and APK update prerequisites; no device claims."""
import argparse
import hashlib
import os
import re
import subprocess
import tempfile
import zipfile


def run(*args):
    return subprocess.check_output(args, text=True, stderr=subprocess.STDOUT)


def identity(apk, signer, aapt):
    sig = run(signer, "verify", "--verbose", "--print-certs", apk)
    badging = run(aapt, "dump", "badging", apk)
    package = re.search(r"package: name='([^']+)' versionCode='(\d+)'", badging)
    certs = re.findall(r"Signer #\d+ certificate SHA-256 digest: ([0-9a-fA-F]+)", sig)
    sdk = re.search(r"^sdkVersion:'(\d+)'", badging, re.M)
    target = re.search(r"^targetSdkVersion:'(\d+)'", badging, re.M)
    if not package or not certs or not sdk or not target:
        raise ValueError("Missing package, signature or SDK identity")
    return (package.group(1), int(package.group(2)), tuple(sorted(certs)), int(sdk.group(1))), sig, badging


def inspect(apk, signer, aapt, previous=None, require_update=False, expected_min_sdk=21):
    if require_update and not previous:
        raise ValueError("Update continuity requires the identified previous APK")
    ident, sig, badging = identity(apk, signer, aapt)
    if ident[3] != expected_min_sdk:
        raise ValueError("Unexpected minSDK: " + str(ident[3]))
    print("APK:", os.path.basename(apk))
    with open(apk, "rb") as f:
        print("SHA256:", hashlib.file_digest(f, "sha256").hexdigest())
    print(sig.strip())
    print("\n".join(line for line in badging.splitlines()
                    if line.startswith(("package:", "sdkVersion:", "targetSdkVersion:", "native-code:"))))
    platform = {"libc.so", "libm.so", "libdl.so", "liblog.so", "libandroid.so", "libEGL.so",
                "libGLESv1_CM.so", "libGLESv2.so", "libGLESv3.so", "libOpenSLES.so",
                "libz.so", "libjnigraphics.so", "libmediandk.so"}
    if ident[3] >= 24:
        platform.add("libvulkan.so")
    if ident[3] >= 26:
        platform.add("libaaudio.so")
    with zipfile.ZipFile(apk) as z, tempfile.TemporaryDirectory() as temp:
        names = set(z.namelist())
        abis = {p.split("/")[1] for p in names if p.startswith("lib/") and p.endswith(".so")}
        if abis != {"arm64-v8a", "armeabi-v7a"}:
            raise ValueError("Expected ARM32 + ARM64, got " + repr(abis))
        for abi in sorted(abis):
            for lib in ("libmobileglues.so", "libSDL3.so", "libSDL2.so", "libopenal.so", "libspirv-cross-c-shared.so"):
                path = f"lib/{abi}/{lib}"
                if path not in names or z.getinfo(path).file_size == 0:
                    raise ValueError("Missing native: " + path)
            for version in ("3.3.3", "3.4.1"):
                path = f"assets/components/lwjgl-{version}-natives/{abi}/liblwjgl.so"
                if path not in names or z.getinfo(path).file_size == 0:
                    raise ValueError("Missing LWJGL: " + path)
            path = f"lib/{abi}/libmobileglues.so"
            file = os.path.join(temp, abi + ".so")
            with open(file, "wb") as out:
                out.write(z.read(path))
            header = run("readelf", "-h", file)
            expected = "AArch64" if abi == "arm64-v8a" else "ARM"
            machine = re.search(r"Machine:\s*(.+)", header)
            elf_class = re.search(r"Class:\s*(\S+)", header)
            if not machine or machine.group(1).strip() != expected or not elf_class or elf_class.group(1) != ("ELF64" if abi == "arm64-v8a" else "ELF32"):
                raise ValueError("Renderer ELF architecture mismatch: " + abi)
            needed = re.findall(r"Shared library: \[([^\]]+)\]", run("readelf", "-d", file))
            missing = [lib for lib in needed if lib not in platform and f"lib/{abi}/{lib}" not in names]
            if missing:
                raise ValueError("Missing MobileGlues dependencies: " + repr(missing))
            print("MobileGlues", abi, expected, "NEEDED=" + ",".join(needed))
    print("Packaging/signature verified; actual installation, renderer load and gameplay NOT executed.")
    if previous:
        prior, prior_sig, _ = identity(previous, signer, aapt)
        print("Previous identified APK:", os.path.basename(previous))
        with open(previous, "rb") as f:
            print("Previous SHA256:", hashlib.file_digest(f, "sha256").hexdigest())
        print("Previous identity:", prior)
        same_package = ident[0] == prior[0]
        same_cert = ident[2] == prior[2]
        monotonic_version = ident[1] > prior[1]
        same_min_sdk = ident[3] == prior[3]
        print("Update prerequisites: same_package=", same_package, "same_certificate=", same_cert,
              "higher_version_code=", monotonic_version, "same_min_sdk=", same_min_sdk)
        matched = same_package and same_cert and monotonic_version and same_min_sdk
        print("MATCHED" if matched else "NOT MATCHED",
              "— metadata comparison only; update/data migration on device still untested.")
        if require_update and not matched:
            raise ValueError("APK update prerequisites did not match; publication blocked")
    else:
        print("No prior APK available: update continuity is NOT verified.")


if __name__ == "__main__":
    p = argparse.ArgumentParser()
    p.add_argument("apk")
    p.add_argument("--previous")
    p.add_argument("--apksigner", default="apksigner")
    p.add_argument("--aapt", default="aapt")
    p.add_argument("--require-update", action="store_true")
    p.add_argument("--expected-min-sdk", type=int, default=21)
    args = p.parse_args()
    inspect(args.apk, args.apksigner, args.aapt, args.previous, args.require_update, args.expected_min_sdk)
