#!/usr/bin/env python3
"""Package checksum-pinned Android binaries, never desktop LWJGL natives."""
import argparse, hashlib, json, pathlib, struct, urllib.request, zipfile
ROOT = pathlib.Path(__file__).resolve().parents[1]
APP = ROOT / 'app_pojavlauncher/src/main'
ABIS = {'arm64-v8a': (2, 183), 'armeabi-v7a': (1, 40)}
def elf(data, abi):
    bits, machine = ABIS[abi]
    if data[:4] != b'\x7fELF' or data[4] != bits or data[5] != 1 or struct.unpack_from('<H', data, 18)[0] != machine:
        raise RuntimeError(f'Wrong ELF class/architecture: {abi}')
def main():
    p = argparse.ArgumentParser(); p.add_argument('--local-dir', type=pathlib.Path); args = p.parse_args()
    manifest = json.loads((ROOT / 'tools/android-natives.json').read_text())
    cache = ROOT / '.native-cache'; cache.mkdir(exist_ok=True)
    for payload in manifest['payloads']:
        archive = args.local_dir / payload['name'] if args.local_dir else cache / payload['name']
        if not args.local_dir: urllib.request.urlretrieve(manifest['base_url'] + payload['name'], archive)
        if hashlib.sha256(archive.read_bytes()).hexdigest() != payload['sha256']:
            raise RuntimeError(f'Checksum mismatch: {archive.name}')
        count = 0
        with zipfile.ZipFile(archive) as z:
            for name in z.namelist():
                path = pathlib.PurePosixPath(name)
                if '..' in path.parts or path.is_absolute() or name.endswith('/'): continue
                target = None
                if name.startswith('assets/components/lwjgl-') and name.endswith('.so'):
                    if path.parts[-2] not in ABIS: continue
                    elf(z.read(name), path.parts[-2]); target = APP / path
                elif name.startswith('jni/') and name.endswith('.so') and path.parts[1] in ABIS:
                    elf(z.read(name), path.parts[1]); target = APP / 'jniLibs' / path.parts[1] / path.name
                elif name.startswith('assets/licenses/'):
                    target = APP / 'assets/licenses' / archive.stem / path.name
                if target:
                    target.parent.mkdir(parents=True, exist_ok=True); target.write_bytes(z.read(name)); count += 1
        if count == 0: raise RuntimeError(f'No usable payload: {archive.name}')
        print(f'{archive.name}: checksum verified; {count} files packaged')
    for abi in ABIS:
        for version in ['3.3.3', '3.4.1']:
            elf((APP / f'assets/components/lwjgl-{version}-natives/{abi}/liblwjgl.so').read_bytes(), abi)
        for name in ['libSDL3.so', 'libSDL2.so', 'libopenal.so', 'libspirv-cross-c-shared.so']:
            elf((APP / 'jniLibs' / abi / name).read_bytes(), abi)
    print('ARM32 and ARM64 bootstrap libraries verified')
if __name__ == '__main__': main()
