#!/usr/bin/env python3
"""Compile the actual MainActivity watcher wiring with inert lifecycle/UI doubles.

This is not a full Android Activity emulator. The unchanged watcher initializer
and relevant statements are extracted from production; placement assertions
also enforce the full caller ordering around Android pause/stop/destroy.
"""
import pathlib
import re
import sys

root = pathlib.Path(sys.argv[1])
out = pathlib.Path(sys.argv[2])
source = (root / 'app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/MainActivity.java').read_text()


def body(method):
    match = re.search(r'\b(?:public|protected) void ' + method + r'\([^)]*\)\s*\{', source)
    assert match, method
    start = match.end()
    depth = 1
    end = start
    while depth:
        if source[end] == '{':
            depth += 1
        elif source[end] == '}':
            depth -= 1
        end += 1
    return source[start:end - 1]


create = body('onCreate')
resume = body('onResume')
pause = body('onPause')
stop = body('onStop')
destroy = body('onDestroy')
assert 'mFirstSubmissionWatch' not in create
assert pause.strip().startswith('mFirstSubmissionWatch.pause();')
assert stop.strip().startswith('mFirstSubmissionWatch.pause();')
assert destroy.strip().startswith('mFirstSubmissionWatch.release();')
assert resume.index('hasObservedSubmission()') < resume.index('mFirstSubmissionWatch.resume();')
assert pause.index('mFirstSubmissionWatch.pause();') < pause.index('mGameStateController.paused()')
assert source.count('mFirstSubmissionWatch.') == 5, 'unexpected watcher call site'
assert 'mFirstFrameWatcher' not in source and 'mFirstGameFramePresented' not in source

start = source.index('    private final net.kdt.pojavlaunch.launch.FirstSubmissionWatch mFirstSubmissionWatch')
end = source.index('\n\n    @Override', start)
initializer = source[start:end]


def relevant(method):
    return '\n'.join(line for line in body(method).splitlines()
                     if 'mFirstSubmissionWatch.' in line or 'mGameStateController' in line)


files = {
    'net/kdt/pojavlaunch/Round3WatcherCaller.java': '''package net.kdt.pojavlaunch;
public final class Round3WatcherCaller {
    public net.kdt.pojavlaunch.performance.AndroidGameStateController mGameStateController;
    public final Overlay mBootLog = new Overlay();
    public boolean finishing, destroyed;
    public boolean isFinishing() { return finishing; }
    public boolean isDestroyed() { return destroyed; }
    public static final class Overlay { public int visibleCalls;
        public void onGameVisible() { visibleCalls++; } }
''' + initializer + '\n' + '\n'.join('    public void ' + method + '() {\n'
          + relevant(method) + ('\n        destroyed = true;' if method == 'onDestroy' else '')
          + '\n    }' for method in ['onCreate', 'onResume', 'onPause', 'onStop', 'onDestroy']) + '\n}\n',
    'net/kdt/pojavlaunch/Tools.java': '''package net.kdt.pojavlaunch;
import java.util.ArrayList;
public final class Tools {
    public static final Queue MAIN_HANDLER = new Queue();
    public static final class Queue {
        public final ArrayList<Runnable> callbacks = new ArrayList<>();
        public final ArrayList<Long> delays = new ArrayList<>();
        public boolean rejectNext;
        public boolean postDelayed(Runnable callback, long delay) {
            if (rejectNext) { rejectNext = false; return false; }
            if (callback == null) throw new AssertionError("null callback");
            callbacks.add(callback); delays.add(delay); return true;
        }
        public void removeCallbacks(Runnable callback) {
            for (int i = callbacks.size() - 1; i >= 0; i--) {
                if (callbacks.get(i) == callback) { callbacks.remove(i); delays.remove(i); }
            }
        }
        public Runnable take() { delays.remove(0); return callbacks.remove(0); }
        public void runNext() { take().run(); }
        public void clear() { callbacks.clear(); delays.clear(); rejectNext = false; }
    }
}
''',
    'net/kdt/pojavlaunch/performance/AndroidGameStateController.java': '''package net.kdt.pojavlaunch.performance;
public final class AndroidGameStateController {
    public String state;
    public int playingCalls;
    public AndroidGameStateController(Object context) {}
    public void loading() { state = "loading"; }
    public void playing() { state = "playing"; playingCalls++; }
    public void paused() { state = "paused"; }
}
''',
}
for filename, contents in files.items():
    target = out / filename
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(contents)
print('PASS production MainActivity watcher extraction and lifecycle/launch separation guards')
