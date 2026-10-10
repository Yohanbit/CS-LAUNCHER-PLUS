"""Compile exact launch methods without Android app bootstrapping. Never copies their logic."""
import pathlib, re, sys
root = pathlib.Path(__file__).resolve().parents[1]
src = root / 'app_pojavlauncher/src/main/java/net/kdt/pojavlaunch'
out = pathlib.Path(sys.argv[1])

def method(path, name):
    text = (src / path).read_text()
    match = re.search(r'[^\n]*(?:public|private) [^\n]*\b' + name + r'\([^\n]*\)\s*\{', text)
    if not match:
        raise AssertionError(name)
    start = match.start(); pos = text.index('{', start) + 1; depth = 1
    while depth:
        depth += (text[pos] == '{') - (text[pos] == '}'); pos += 1
    return text[start:pos].replace('private static', 'public static', 1)

jre = (src / 'utils/JREUtils.java').read_text()
tools = (src / 'Tools.java').read_text()
multi = (src / 'multirt/MultiRTUtils.java').read_text()
prefs = (src / 'prefs/LauncherPreferences.java').read_text()
main = (src / 'MainActivity.java').read_text()
newjre = (src / 'NewJREUtil.java').read_text()
assert 'List<String> userArguments = parseJavaArguments(userArgumentsString);' in jre
assert 'RuntimeModWorkarounds.addJvmDefault(' in tools
assert 'javaArgList, JREUtils.parseJavaArguments(args), modWorkarounds)' in tools
assert 'PREF_EXPERIMENTAL_MOD_WORKAROUNDS && hasSodiumMod' in tools
assert 'getBoolean("experimentalModWorkarounds", false)' in prefs
assert 'RuntimeRendererSelection\n                    .initialFallback' in main
assert 'RuntimeArchiveExtractor.extract(tarFileInputStream, dest' in multi
assert 'Os.symlink(target, linkPath)' in multi and 'Os.link(target, linkPath)' in multi
assert 'getCompatibleInstalledRuntimes()' in newjre
assert 'MultiRTUtils.isCompatible(runtime)' in newjre
assert 'RuntimeNativeProperties.freetypePath(' in jre
assert 'EGL/backend/context initialization is still pending' in jre

# Actual reserved-key filtering and final selected-heap injection, extracted from launchJavaVM.
purge = '\n'.join(line.strip() for line in jre.splitlines() if re.match(r'\s*purgeArg\(userArgs', line))
start = jre.index('int initialRam = RuntimeMemoryBudget.initialHeapMb(effectiveRam);')
end = jre.index('Log.i("ProfileLaunch", "Effective JVM RAM:', start)
heap = jre[start:end]
assembly = '''public static List<String> selectedHeap(String input, int effectiveRam, List<String> JVMArgs) {
    List<String> userArgs = parseJavaArguments(input);
''' + purge + '\n' + heap + '\nuserArgs.addAll(JVMArgs); return userArgs; }'
code = '''import java.util.*;
import java.io.*;
import net.kdt.pojavlaunch.Architecture;
import static net.kdt.pojavlaunch.Architecture.is32BitProcess;
import net.kdt.pojavlaunch.multirt.Runtime;
import net.kdt.pojavlaunch.performance.*;
public class ActualLaunch {
 public static class Context { public int ram; public Context(int ram) { this.ram = ram; } }
 public static class Tools { public static int getTotalDeviceMemory(Context c) { return c.ram; } }
 public static int getTotalDeviceMemory(Context c) { return c.ram; }
 public java.io.File gameDirectory;
'''
for path, name in [('utils/JREUtils.java','parseJavaArguments'), ('utils/JREUtils.java','purgeArg'),
                   ('Tools.java','getMaximumRamAllocation'), ('prefs/LauncherPreferences.java','findBestRAMAllocation'),
                   ('multirt/MultiRTUtils.java','isCompatible'), ('performance/McContext.java','mcOption')]:
    code += method(path, name) + '\n'
(out / 'ActualLaunch.java').write_text(code + assembly + '\n}\n')
print('Production route guards + exact extracted parse/purge/heap/RAM/ABI/options methods verified')
