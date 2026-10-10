#!/bin/sh
set -eu
ROOT=$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)
OUT=$(mktemp -d)
trap 'rm -rf "$OUT"' EXIT HUP INT TERM
PREFS="$ROOT/app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/prefs"
# Scan the entire checkout, not just the subset compiled by the host doubles.
# Any new production consumer needs review for dirty-key registration before CI passes.
python3 - "$ROOT" <<'PY'
import os, pathlib, re, sys
root = pathlib.Path(sys.argv[1])
allowed = {
    'app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/prefs/SettingsSaveManager.java',
    'app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/prefs/screens/LauncherPreferenceFragment.java',
}
reference = re.compile(r'\bSettingsSaveManager\b')
apis = re.compile(r'\b(commitChanges|saveToMain|getDraftPrefs)\s*\(')
def code_only(text):
    # Preserve newlines for locations; exclude comments/string documentation false positives.
    return re.sub(r'/\*.*?\*/|//[^\n]*|"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'',
                  lambda m: '\n' * m.group().count('\n'), text, flags=re.S)
# Guard the guard: unknown direct and static-import consumers must be detected.
for sample in ('SettingsSaveManager.commitChanges(c);',
               'import static a.SettingsSaveManager.getDraftPrefs; getDraftPrefs(c).edit();',
               'import a.SettingsSaveManager; manager.saveToMain(c, key);'):
    assert reference.search(code_only(sample))
assert not reference.search(code_only('// SettingsSaveManager.commitChanges(c);'))
unknown = []
count = 0
for directory, dirs, files in os.walk(root):
    dirs[:] = sorted(d for d in dirs if d not in ('.git', '.gradle', 'build'))
    for name in sorted(files):
        if not name.endswith(('.java', '.kt')):
            continue
        path = pathlib.Path(directory) / name
        rel = path.relative_to(root).as_posix()
        count += 1
        code = code_only(path.read_text(encoding='utf-8'))
        if not reference.search(code):
            continue
        calls = sorted(set(apis.findall(code)))
        if rel.startswith('tools/'):
            print('Settings consumer test fixture:', rel, ','.join(calls) or 'reference')
        elif rel in allowed:
            print('Settings consumer reviewed:', rel, ','.join(calls) or 'reference')
        else:
            unknown.append(rel)
            print('UNREVIEWED SettingsSaveManager consumer:', rel, ','.join(calls) or 'reference', file=sys.stderr)
print('Settings checkout consumer scan:', count, 'Java/Kotlin files;', len(unknown), 'unreviewed production consumers')
if unknown:
    sys.exit('Review these consumers and add dirty-key regression coverage; do not bypass with whole-draft publication.')
PY
find "$ROOT/tools/ConsolidationSettingsSupport" -name '*.java' > "$OUT/settings-stubs.txt"
javac --release 8 -d "$OUT/settings" @"$OUT/settings-stubs.txt" \
    "$PREFS/SettingsSaveManager.java" "$PREFS/SharedSettings.java" "$PREFS/CrossProcessState.java" \
    "$ROOT/tools/ConsolidationSettingsTest.java" "$ROOT/tools/ConsolidationStateWriter.java"
java -cp "$OUT/settings" ConsolidationSettingsTest "$OUT/data"
# Reuse unchanged host lifecycle doubles; replace only thermal/history sinks for assertions.
find "$ROOT/tools/measurement_test_support" -name '*.java' \
    ! -name ThermalGovernor.java ! -name SessionStats.java > "$OUT/measurement-stubs.txt"
find "$ROOT/tools/ConsolidationMeasurementSupport" -name '*.java' >> "$OUT/measurement-stubs.txt"
UTILS="$ROOT/app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/utils"
javac --release 8 -d "$OUT/measurement" @"$OUT/measurement-stubs.txt" \
    "$UTILS/FrameCounterWindow.java" "$UTILS/FpsCounter.java" \
    "$ROOT/app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/performance/FrameSampleCollector.java" \
    "$ROOT/tools/ConsolidationThermalPeakTest.java"
java -cp "$OUT/measurement" ConsolidationThermalPeakTest "$OUT/data"
UI_SOURCE=${UI_SOURCE_PATH:-"$PREFS/screens/LauncherPreferenceFragment.java"}
python3 "$ROOT/tools/ConsolidationUiSourceTest.py" "$UI_SOURCE"
