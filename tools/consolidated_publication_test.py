#!/usr/bin/env python3
"""Synthetic publication gates; no real signing, Android build or device claims."""
import contextlib
import hashlib
import io
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import textwrap
import unittest
import warnings
import zipfile
from unittest.mock import patch

import apk_publication_test as fixtures
import inspect_apk


class ConsolidatedPublicationTest(unittest.TestCase):
    def setUp(self):
        fixtures.PublicationTest.setUp(self)
        self.current = (self.current[0], 10015000, self.current[2], 21)
        self.badging = ("package: name='com.cslaucher.plus.debug' versionCode='10015000' "
                        "versionName='1.5.0' platformBuildVersionName='14'\n"
                        "sdkVersion:'21'\ntargetSdkVersion:'34'\n")

    @staticmethod
    def elf(*args):
        if args[1] == '-h':
            wide = 'arm64-v8a' in args[-1]
            return 'Machine: ' + ('AArch64' if wide else 'ARM') + '\nClass: ' + ('ELF64' if wide else 'ELF32')
        return 'Shared library: [libc.so]'

    def verify(self, badging=None, current=None, elf=None):
        def identity(path, *_):
            ident = (current or self.current) if path == self.apk else self.prior
            return ident, 'synthetic certificate', self.badging if badging is None else badging
        with patch.object(inspect_apk, 'identity', side_effect=identity), patch.object(inspect_apk, 'run', side_effect=elf or self.elf), contextlib.redirect_stdout(io.StringIO()):
            return inspect_apk.inspect(self.apk, 'signer', 'aapt', self.previous, True, 21,
                                       expected_version_name='1.5.0', expected_version_code=10015000)

    def rewrite_zip(self, change):
        replacement = self.apk + '.tmp'
        with zipfile.ZipFile(self.apk) as old, zipfile.ZipFile(replacement, 'w') as new:
            for name in old.namelist():
                data = change(name, old.read(name))
                if data is not None:
                    new.writestr(name, data)
        os.replace(replacement, self.apk)

    def test_fixed_version_and_report(self):
        report = self.verify()
        self.assertEqual(report['versionName'], '1.5.0')
        self.assertEqual(report['versionCode'], 10015000)
        self.assertTrue(report['updateMatched'])
        self.assertFalse(report['physicalValidation'])
        self.assertEqual(report['abis'], ['arm64-v8a', 'armeabi-v7a'])
        self.assertEqual(report['sha256'], hashlib.sha256(Path(self.apk).read_bytes()).hexdigest())

    def test_wrong_or_missing_version_name(self):
        for badging in (self.badging.replace('1.5.0', '1.5.0-test'),
                        self.badging.replace("versionName='1.5.0'", ''),
                        self.badging.replace("versionName='1.5.0'", "versionName=''")):
            with self.subTest(badging=badging), self.assertRaisesRegex(ValueError, 'Unexpected versionName'):
                self.verify(badging=badging)

    def test_nonfixed_version_code_even_if_monotonic(self):
        for code in (10014999, 10015001):
            with self.subTest(code=code), self.assertRaisesRegex(ValueError, 'Unexpected versionCode'):
                self.verify(current=(self.current[0], code, self.current[2], 21))

    def test_duplicate_zip_is_rejected(self):
        with warnings.catch_warnings():
            warnings.simplefilter('ignore', UserWarning)
            with zipfile.ZipFile(self.apk, 'a') as z:
                z.writestr('lib/arm64-v8a/libmobileglues.so', b'other')
        with self.assertRaisesRegex(ValueError, 'Duplicate ZIP'):
            self.verify()

    def test_zip_crc_corruption_is_rejected(self):
        with zipfile.ZipFile(self.apk) as z:
            entry = z.getinfo('lib/arm64-v8a/libmobileglues.so')
        with open(self.apk, 'r+b') as f:
            f.seek(entry.header_offset + 30 + len(entry.filename.encode()) + len(entry.extra))
            f.write(b'X')
        with self.assertRaisesRegex(ValueError, 'Corrupt ZIP'):
            self.verify()

    def test_missing_arm32_is_rejected(self):
        self.rewrite_zip(lambda name, data: None if name.startswith('lib/armeabi-v7a/') else data)
        with self.assertRaisesRegex(ValueError, 'Expected ARM32'):
            self.verify()

    def test_missing_or_empty_lwjgl_is_rejected(self):
        name = 'assets/components/lwjgl-3.4.1-natives/arm64-v8a/liblwjgl.so'
        self.rewrite_zip(lambda path, data: b'' if path == name else data)
        with self.assertRaisesRegex(ValueError, 'Missing LWJGL'):
            self.verify()

    def test_incorrect_renderer_machine_or_class(self):
        for header in ('Machine: Intel 80386\nClass: ELF32', 'Machine: AArch64\nClass: ELF32'):
            with self.subTest(header=header), self.assertRaisesRegex(ValueError, 'ELF architecture mismatch'):
                self.verify(elf=lambda *args: header if args[1] == '-h' else '')

    def fake_tools(self):
        root = Path(self.temp.name)
        signer = root / 'apksigner'
        signer.write_text("#!/bin/sh\necho 'Signer #1 certificate SHA-256 digest: abc123'\n")
        aapt = root / 'aapt'
        aapt.write_text("#!/bin/sh\nif [ \"${3##*/}\" = previous.apk ]; then code=10010020; else code=10015000; fi\n"
                        "echo \"package: name='com.cslaucher.plus.debug' versionCode='$code' versionName='1.5.0'\"\n"
                        "echo \"sdkVersion:'21'\"\necho \"targetSdkVersion:'34'\"\n")
        elf = root / 'readelf'
        elf.write_text("#!/bin/sh\nif [ \"$1\" = -h ]; then\ncase \"$2\" in *arm64-v8a*) echo 'Machine: AArch64'; echo 'Class: ELF64';; *) echo 'Machine: ARM'; echo 'Class: ELF32';; esac\nelse echo 'Shared library: [libc.so]'; fi\n")
        for tool in (signer, aapt, elf):
            tool.chmod(0o755)
        return root, signer, aapt

    def cli(self, expected_name):
        root, signer, aapt = self.fake_tools()
        report = root / 'identity.json'
        inspector = str(Path(inspect_apk.__file__).resolve())
        env = dict(os.environ, PATH=str(root) + os.pathsep + os.environ['PATH'])
        result = subprocess.run([sys.executable, inspector, self.apk, '--previous', self.previous,
                                 '--require-update', '--expected-version-name', expected_name,
                                 '--expected-version-code', '10015000', '--report-json', str(report),
                                 '--apksigner', str(signer), '--aapt', str(aapt)],
                                env=env, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        return result, report

    def test_cli_success_writes_verified_json(self):
        result, report = self.cli('1.5.0')
        self.assertEqual(result.returncode, 0, result.stdout)
        identity = json.loads(report.read_text())
        self.assertTrue(identity['updateMatched'])
        self.assertEqual(identity['versionName'], '1.5.0')

    def test_cli_wrong_version_exits_nonzero_without_report(self):
        result, report = self.cli('1.5.1')
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('Unexpected versionName', result.stdout)
        self.assertFalse(report.exists())


class WorkflowBuildGateTest(unittest.TestCase):
    def setUp(self):
        self.workflow = (Path(__file__).resolve().parents[1] / '.github/workflows/build-apk.yml').read_text()
        condition = self.workflow.split('  build:\n', 1)[1].splitlines()[0].strip()
        self.assertTrue(condition.startswith('if: ${{ ') and condition.endswith(' }}'))
        expression = condition[len('if: ${{ '):-len(' }}')]
        for field, variable in (('github.event.head_commit.message', 'message'),
                                ('github.event_name', 'event'), ('github.ref', 'ref')):
            expression = expression.replace(field, variable)
        expression = expression.replace('&&', ' and ').replace('||', ' or ')
        self.expression = re.sub(r'!(?!=)', 'not ', expression)

    def enabled(self, message='', event='push', ref='refs/heads/release/consolidated-1.5.0'):
        # Model only the operators used by the actual condition; GitHub's
        # contains() is case-insensitive. No invented workflow condition.
        return eval(self.expression, {'__builtins__': {}},
                    {'message':message, 'event':event, 'ref':ref,
                     'contains':lambda value, needle: needle.lower() in (value or '').lower()})

    def test_documentation_and_cleanup_markers_skip_push(self):
        for message in ('docs: add CI results [skip apk]', '[release-cleanup] preserve release history',
                        'docs [SKIP APK]', 'cleanup [RELEASE-CLEANUP]'):
            with self.subTest(message=message):
                self.assertFalse(self.enabled(message=message))
        self.assertTrue(self.enabled(message='release: consolidated 1.5.0'))

    def test_dispatch_and_branch_guard_and_publish_dependency(self):
        self.assertTrue(self.enabled(message=None, event='workflow_dispatch'))
        self.assertFalse(self.enabled(ref='refs/heads/main'))
        publish = self.workflow.split('  publish:\n', 1)[1]
        self.assertIn('    needs: build\n', publish)
        self.assertNotIn('always()', publish.split('    steps:\n', 1)[0])

    def test_ndk_scripts_really_compile_both_targets_and_workflow_requires_markers(self):
        root = Path(__file__).resolve().parents[1]
        for name, required in (('test-measurement.sh', 'MEASUREMENT_REQUIRE_NDK'),
                               ('test-native-graphics-round2.sh', 'NATIVE_REQUIRE_NDK'),
                               ('test-vulkan-loader-round3.sh', 'NATIVE_REQUIRE_NDK')):
            source = (root / 'tools' / name).read_text()
            with self.subTest(script=name):
                self.assertIn('for TARGET in armv7a-linux-androideabi21 aarch64-linux-android21; do', source)
                self.assertIn('"$BIN/$TARGET-clang"', source)
                self.assertIn(' -c ', source)
                self.assertIn(required, source)
                self.assertIn('exit 1', source)
        host = self.workflow.split('      - name: Run all host logic and native tests\n', 1)[1].split('      - name:', 1)[0]
        self.assertIn("MEASUREMENT_REQUIRE_NDK: '1'", host)
        self.assertIn("NATIVE_REQUIRE_NDK: '1'", host)
        self.assertIn('for target in armv7a-linux-androideabi21 aarch64-linux-android21; do', host)
        self.assertEqual(host.count('grep -Fq "PASS $target'), 3)


class WorkflowRuntimeDependenciesTest(unittest.TestCase):
    """Run the actual resolution/export shell with synthetic Gradle and jars."""
    def resolve(self, mode):
        workflow = (Path(__file__).resolve().parents[1] / '.github/workflows/build-apk.yml').read_text()
        name = 'Resolve real runtime archive dependencies before host tests'
        self.assertLess(workflow.index('      - name: ' + name),
                        workflow.index('      - name: Run all host logic and native tests'))
        block = workflow.split('      - name: ' + name + '\n', 1)[1].split('\n      - name:', 1)[0]
        shell = textwrap.dedent(block.split('        run: |\n', 1)[1])
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / 'verification').mkdir()
            envfile = root / 'github-env'
            envfile.touch()
            gradle = root / 'gradlew'
            gradle.write_text('#!' + sys.executable + '\n' + textwrap.dedent('''\
                import os, pathlib, sys
                assert sys.argv[1:] == [':app_pojavlauncher:resolveConsolidationArchiveTestDependencies',
                                        '-PallowMissingFirebase=true', '--no-daemon']
                if os.environ['FIXTURE_MODE'] == 'gradle-failure':
                    print('Synthetic resolution failure')
                    sys.exit(1)
                root = pathlib.Path.cwd()
                names = ['commons-compress-1.24.0.jar', 'xz-1.8.jar', 'commons-io-2.14.0.jar']
                if os.environ['FIXTURE_MODE'] == 'missing-version':
                    names[0] = 'commons-compress-1.23.0.jar'
                for name in names:
                    (root / name).write_bytes(b'synthetic jar fixture')
                (root / 'verification/runtime-archive-classpath.txt').write_text(
                    os.pathsep.join(str(root / name) for name in names) + '\\n')
                print('Synthetic Gradle resolution complete')
                '''))
            env = dict(os.environ, GITHUB_ENV=str(envfile), FIXTURE_MODE=mode)
            result = subprocess.run(['bash', '-c', shell], cwd=root, env=env, text=True,
                                    stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
            return result, envfile.read_text()

    def test_resolution_precedes_host_and_exports_classpath(self):
        result, exported = self.resolve('success')
        self.assertEqual(result.returncode, 0, result.stdout)
        self.assertTrue(exported.startswith('RUNTIME_TAR_TEST_CP=/'))
        self.assertIn('commons-compress-1.24.0.jar', exported)
        self.assertIn('xz-1.8.jar', exported)
        self.assertIn('commons-io-2.14.0.jar', exported)
        self.assertEqual(len(exported.splitlines()), 1)

    def test_wrong_dependency_version_blocks_export(self):
        result, exported = self.resolve('missing-version')
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('Missing exact runtime archive dependency versions', result.stdout)
        self.assertEqual(exported, '')

    def test_gradle_failure_is_not_hidden_by_tee(self):
        result, exported = self.resolve('gradle-failure')
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(exported, '')


class WorkflowTagGateTest(unittest.TestCase):
    """Execute the actual final shell gate with an isolated fake GitHub CLI."""
    def gate(self, mode):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            workflow = (Path(__file__).resolve().parents[1] / '.github/workflows/build-apk.yml').read_text()
            block = workflow.split('      - name: Gate fixed tag ownership', 1)[1]
            shell = textwrap.dedent(block.split('        run: |\n', 1)[1])
            gh = root / 'gh'
            gh.write_text('#!' + sys.executable + '\n' + textwrap.dedent('''\
                import json, os, sys
                args = sys.argv[1:]
                mode = os.environ['FIXTURE_MODE']
                with open('calls.jsonl', 'a') as f:
                    f.write(json.dumps(args) + '\\n')
                def fail(code):
                    print(f'gh: fixture error (HTTP {code})', file=sys.stderr)
                    sys.exit(1)
                if args[0] == 'api' and '/git/ref/tags/' in args[1]:
                    if mode == 'absent': fail(404)
                    if mode == 'api-error': fail(403)
                    if mode == 'annotated':
                        print(json.dumps({'object':{'type':'tag', 'sha':'annotated-object'}}))
                    else:
                        print(json.dumps({'object':{'type':'commit', 'sha':'alien' if mode == 'alien' else 'candidate-sha'}}))
                elif args[0] == 'api' and '/git/tags/' in args[1]:
                    print(json.dumps({'object':{'type':'commit', 'sha':'candidate-sha'}}))
                elif args[0] == 'api' and '/releases/tags/' in args[1]:
                    if mode == 'existing-release': print('{}')
                    elif mode == 'release-api-error': fail(500)
                    else: fail(404)
                else:
                    print('{}')
                '''))
            gh.chmod(0o755)
            env = dict(os.environ, PATH=str(root) + os.pathsep + os.environ['PATH'],
                       FIXTURE_MODE=mode, GH_REPO='fixture/repository', BUILD_SHA='candidate-sha')
            result = subprocess.run(['bash', '-c', shell], cwd=root, env=env, text=True,
                                    stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
            calls = [json.loads(line) for line in (root / 'calls.jsonl').read_text().splitlines()]
            return result, calls

    def test_alien_tag_blocks_without_writes(self):
        result, calls = self.gate('alien')
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('another commit', result.stdout)
        self.assertFalse(any(call[0] == 'release' or '--method' in call for call in calls))

    def test_existing_release_blocks_without_writes(self):
        result, calls = self.gate('existing-release')
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('already exists', result.stdout)
        self.assertFalse(any(call[0] == 'release' or '--method' in call for call in calls))

    def test_api_errors_are_not_treated_as_absence(self):
        for mode in ('api-error', 'release-api-error'):
            with self.subTest(mode=mode):
                result, calls = self.gate(mode)
                self.assertNotEqual(result.returncode, 0)
                self.assertFalse(any(call[0] == 'release' or '--method' in call for call in calls))

    def test_new_release_is_normal_and_never_clobbers(self):
        for mode in ('absent', 'matching', 'annotated'):
            with self.subTest(mode=mode):
                result, calls = self.gate(mode)
                self.assertEqual(result.returncode, 0, result.stdout)
                create = next(call for call in calls if call[:2] == ['release', 'create'])
                self.assertEqual(create[2], 'consolidated-1.5.0')
                self.assertIn('--verify-tag', create)
                self.assertNotIn('--prerelease', create)
                self.assertNotIn('--clobber', create)
                self.assertFalse(any('delete' in call for call in calls))
                writes = [call for call in calls if '--method' in call]
                self.assertEqual(len(writes), 1 if mode == 'absent' else 0)


class WorkflowEvidenceTest(unittest.TestCase):
    """Exercise embedded report generation and publication rechecks, offline."""
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.root = Path(temp.name)
        self.source = self.root / 'source'
        self.source.mkdir()
        docs = self.source / 'docs'
        docs.mkdir()
        (docs / 'CONSOLIDATED-RELEASE-NOTES.md').write_text('Synthetic changelog\n')
        (docs / 'CONSOLIDATION-REPORT.md').write_text('Synthetic methodology report\n')
        subprocess.run(['git', 'init', '-q', str(self.source)], check=True)
        subprocess.run(['git', '-C', str(self.source), 'add', '.'], check=True)
        subprocess.run(['git', '-C', str(self.source), '-c', 'user.name=fixture',
                        '-c', 'user.email=fixture@example.invalid', 'commit', '-qm', 'fixture'], check=True)
        sha = subprocess.check_output(['git', '-C', str(self.source), 'rev-parse', 'HEAD'], text=True).strip()
        self.env = dict(os.environ, GITHUB_SHA=sha, BUILD_SHA=sha, GITHUB_RUN_ID='123',
                        GITHUB_REPOSITORY='fixture/repository')
        verification = self.source / 'verification'
        verification.mkdir()
        (verification / 'tests.txt').write_text('Synthetic host/NDK fixture output\n')
        (verification / 'gradle.txt').write_text('> Task :app_pojavlauncher:testDebugUnitTest NO-SOURCE\n')
        (verification / 'commit.txt').write_text(sha + '\n')
        apk = self.source / 'app_pojavlauncher/build/outputs/apk/debug/Unamed.launcher.apk'
        apk.parent.mkdir(parents=True)
        apk.write_bytes(b'synthetic-artifact-not-an-android-apk')
        report = {'sha256':hashlib.sha256(apk.read_bytes()).hexdigest(),
                  'versionName':'1.5.0', 'versionCode':10015000, 'minSDK':21,
                  'abis':['arm64-v8a', 'armeabi-v7a'], 'package':'com.cslaucher.plus.debug',
                  'updateMatched':True,
                  'previousSha256':'917609fb4b8695324610937ccae97640f8d620b279fa1c9b69f86d1f66368666'}
        (verification / 'apk-identity.json').write_text(json.dumps(report))
        result = self.step('Generate publication report, source matrix and asset hashes', self.source)
        self.assertEqual(result.returncode, 0, result.stdout)
        import shutil
        shutil.copytree(verification, self.root / 'verification')
        (self.root / 'apk').mkdir()
        shutil.copyfile(apk, self.root / 'apk/Unamed.launcher.apk')

    def step(self, name, cwd):
        workflow = (Path(__file__).resolve().parents[1] / '.github/workflows/build-apk.yml').read_text()
        block = workflow.split('      - name: ' + name + '\n', 1)[1]
        block = block.split('\n      - name:', 1)[0]
        shell = textwrap.dedent(block.split('        run: |\n', 1)[1])
        return subprocess.run(['bash', '-c', shell], cwd=cwd, env=self.env, text=True,
                              stdout=subprocess.PIPE, stderr=subprocess.STDOUT)

    def test_no_source_is_explicit_and_valid_manifest_passes(self):
        report = (self.root / 'verification/build-report.md').read_text()
        self.assertIn('NO-SOURCE (no Gradle unit tests executed)', report)
        self.assertTrue((self.root / 'verification/source-matrix.csv').is_file())
        self.assertTrue((self.root / 'verification/CONSOLIDATION-REPORT.md').is_file())
        result = self.step('Recheck immutable publication inputs', self.root)
        self.assertEqual(result.returncode, 0, result.stdout)

    def test_apk_hash_tampering_blocks_publication(self):
        (self.root / 'apk/Unamed.launcher.apk').write_bytes(b'tampered')
        result = self.step('Recheck immutable publication inputs', self.root)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('differs from inspected artifact', result.stdout)

    def test_identity_tampering_blocks_publication(self):
        path = self.root / 'verification/apk-identity.json'
        report = json.loads(path.read_text())
        report['versionName'] = '1.5.0-test'
        path.write_text(json.dumps(report))
        result = self.step('Recheck immutable publication inputs', self.root)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('Incorrect publication identity', result.stdout)


if __name__ == '__main__':
    unittest.main()
