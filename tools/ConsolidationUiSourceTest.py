"""Static wiring checks only, not an Android widget/lifecycle test."""
import re
import sys
from pathlib import Path

s = Path(sys.argv[1]).read_text()
checks = 0
def check(ok, why):
    global checks
    checks += 1
    assert ok, why

check(s.count('markDirty(item.key);') == 5, 'all five control bindings must submit their edited key')
check('private void markDirty(String key)' in s, 'autosave is key-aware')
check('SharedSettings.mirrorAll(context, mDraftPrefs.getAll())' not in s, 'no stale whole-draft mirror')
check('boolean verified = flushDirtySettings();' in s, 'legacy save routes to central commit')
check('SettingsSaveManager.resetLauncherSettings(requireContext())' in s, 'reset routes to shared-aware central reset')
check('SettingsSaveManager.markDirty(PerformanceMode.PREF_MAX_ACK)' in s, 'Maximum acknowledgement survives key-only save')
check(s.count('int selectedRam = mDraftPrefs.getInt("allocation", 1024);') == 2, 'both heap cards read draft')
check(s.count('"Requested Minecraft heap"') == 2 and '"Exact Minecraft heap"' not in s, 'heap cards do not promise final heap')
check('if ("allocation".equals(key) && mRecyclerView != null)' in s, 'slider/exact-number refresh heap card')
check('heap headroom, code-cache sizing and G1 young-generation sizing' not in s, 'retired experimental claims removed')
check('next game resume; GC logging needs the next JVM launch' in s, 'diagnostic copy states actual timing')
check('Cambios en vivo con el juego en primer plano.' in s, 'DRS copy is live')
check(re.search(r'new SettingItem\("experimentalModWorkarounds", SettingItem.TYPE_SWITCH,.*?false\)\)', s, re.S), 'mod workaround opt-in exists default false')
check('mDraftPrefs.edit().putString("launch_screen_style", "gif")' in s and 'markDirty("launch_screen_style")' in s, 'explicit GIF edits retain autosave semantics')
check('if (!flushDirtySettings()) throw new IllegalStateException("Preset could not be saved")' in s, 'preset publish before loader, using central dirty commit')
print(f'PASS ConsolidationUiSourceTest: {checks} static wiring assertions (no Android execution)')
