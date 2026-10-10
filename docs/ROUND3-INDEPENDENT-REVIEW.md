# Round 3 — focused runtime/watcher integration review

## Scope and result

Reviewed the current working tree on `fix/vulkan11-native-round3`, HEAD/base `1d630a86d4346622f17c533edf19ad3f83cbdf56`, against that checkpoint. Scope: actual-process ABI selection, declared Vulkan metadata versus backend verification, manual-only Zink selection, foreground first-submission watcher, and its atomic JNI coverage enum. **No blocking defect found in the reviewed round-three changes.**

The initial runtime/watcher pass excluded the then-unfinished native Vulkan loader/cache and consumer leases. The completed loader portion has now received the separate focused review below: **no blocking defect found; approved within the stated host/source-review limits**. No repository source edits, commits, or push were performed.

## Verified contracts

- `app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/Architecture.java:29–59,86–87`: address-space limit and legacy runtime/native selector now use process width; API 23+ uses guarded `Process.is64Bit()`, API 21–22 parses `os.arch`, unknown ABI fails closed. ARM/x86 family is normalized to the process width. `JREUtils.java:163–182` rejects incompatible embedded-JVM release metadata before altering library paths and chooses system `lib`/`lib64` by process width. `Tools.java:364–378` measures narrow-process address space for the launch warning.
- `performance/DeviceCapability.java:121–125,246–268,322–334` and `performance/RuntimeVulkanSupport.java:9–29`: metadata is explicitly reported, not a physical-device extension/feature verification. Unknown/malformed metadata is not advertised as a Zink attempt; reported Vulkan 1.0/1.1 is not universally excluded. Cache identity includes the process ABI and APK version.
- `Tools.java:1935–1972`, `performance/RuntimeRendererRules.java:42–57`, and `JREUtils.java:739–744`: Zink remains an explicit selection/alias attempt, not an automatic fallback from GL wrappers or an invalid selection. Its label/log states backend requirements are unverified. GPU name/GLES is not used to certify Zink.
- `MainActivity.java:120–146,450–462,490–503` and `launch/FirstSubmissionWatch.java:45–107`: no watcher starts in `onCreate`; polling starts on resume, pauses before pause/stop state changes, releases before destroy, and has generation checks for stale callbacks. Unready coverage backs off; native Vulkan terminates that foreground run without reading stale GL totals; a real pause/resume may retry. Counted coverage is checked again after reading the cumulative total. Observation is one-shot and uses a positive cumulative submission, not presentation or an FPS rate window.
- `utils/FpsCounter.java:59–75` and `jni/egl_bridge.c:67–74,373–395,424–430,481–497`: optional JNI coverage returns `0=unready`, `1=counted`, `2=native Vulkan unsupported`; missing/invalid linkage stays unready. JNI reads only the atomic32 enum, not renderer or bridge pointers. NO_API stays independent of GL initialization. GL context success restores counted coverage after NO_API without reinitializing the successful bridge. The production translation unit asserts lock-free atomic32; Android ABI compilation is still a CI responsibility.

## Concrete nonblocking follow-up — remaining hardware-width RAM defaults

`Tools.java:1574–1579` still gates the Settings maximum on `Architecture.is32BitsDevice()`, and `prefs/LauncherPreferences.java:204–216` uses the same hardware-based gate for the initial default. Consequently, a 32-bit process on 64-bit-capable hardware with an 8192 MB RAM reading receives a Settings maximum of **7168 MB** and a default of **3072 MB**, not the existing narrow-process values. The launch warning is corrected by this diff, but these two earlier policy sites are not.

This is an **existing inconsistency, not a new round-three regression or an APK blocker**: the current launch flow warns about actual address space (`Tools.java:366–414`) and intentionally preserves the chosen heap (`JREUtils.java:478–498`). If narrow-process RAM defaults/Settings caps are meant to follow the newly corrected ABI contract, change these two policy checks to `is32BitProcess()` and add a narrow-process/wide-hardware fixture. Do not silently rewrite an explicit final JVM override.

## Tests executed

1. `sh tools/test-round3-runtime.sh` — **286 checks PASS**, production ABI/metadata/routing helpers and `DeviceCapability` with controllable Android API seams.
2. `sh tools/test-round3-watcher.sh` — **PASS** production MainActivity wiring extraction, production watcher/FpsCounter wrapper, JNI spy and missing optional legacy JNI; unready/reset/NO_API transitions, post failures, and 100 stale-callback lifecycle sequences.
3. Readiness-only extraction from the unique round-three native fixture, run with **GCC and Clang: both PASS**. Production init/hint/context/JNI coverage functions; GL failure, NO_API, restored GL coverage, 8 readers × 300000 queries during 30000 transitions. Loader functions and real-linker/lease fixtures were excluded. Reproducible helper: `/tasklet/threads/a_mbnwcmag78z8ebyjcqg3/work/scripts/round3-readiness-only.py`.
4. `git diff --check` — PASS.

The Java fixtures simulate `os.arch`/Android APIs; the C fixtures replace graphics/JNI dependencies. No full Activity/Android SDK/NDK compilation or physical-device execution was performed in this pass. No performance or device-compatibility claim follows from these tests.

## Addendum — completed native loader root and consumer leases

Reviewed only the completed lifetime/consumer changes on the same branch/checkpoint, including the namespace retain call path. **Approved: no concrete blocker or new nonblocking defect found in this scope.** Previously reviewed coverage enum behavior was not re-audited.

- `app_pojavlauncher/src/main/jni/egl_bridge.c:153–245`: the authoritative cache is an internally opened, validated handle under a mutex, not a parsed `VULKAN_PTR`. A fresh load clears stale/arbitrary environment values before constructors run. The required globals are Vulkan 1.0 exports, also present in 1.1; there is no requirement for a 1.2/1.3 or even a 1.1-only export, and this is not a backend/device capability probe. Open/required-symbol/publication failures return NULL; failed unpublished handles are closed. A publication failure after caching does not consume the process-lifetime root.
- `egl_bridge.c:253–288`: internal queries borrow the single retained root. Owning consumers acquire a separate reference by reopening the selected `dladdr` path in its original regular or optional driver namespace. The successful path is cached while the root keeps its library mapped. A NULL acquisition returns NULL; a different acquired handle is closed and never exported. Path/retain failures preserve the root and may retry. No `RTLD_NODELETE` was added.
- `egl_bridge.c:501–504` and `jvm_hooks/lwjgl_dlopen_hook.c:18,32–35`: both consumer entry points now acquire leases rather than exporting a borrowed root for later `dlclose`. The hook's non-Vulkan route is unchanged. `egl_bridge.c:308–324` checks the borrowed load result before configuring either Zink bridge.
- `driver_helper/nsbypass.c:119–127`: the selected optional namespace is reused for retains. Its existing loader path remains optional and gated to API 28+; new regular cache/retain operations use longstanding libdl/pthread/environment APIs. `app_pojavlauncher/build.gradle:98` remains minSDK 21. Full API-21 ARM compilation remains CI validation, not a local result.

Executed `sh tools/test-vulkan-loader-round3.sh` with GCC/default cc, then `CC=clang sh tools/test-vulkan-loader-round3.sh`: **both PASS**. Production-function fixtures cover JNI plus hooked ndlopen with two owning consumers, 100 balanced lease cycles, retain/mismatch/path failures, each required-symbol failure, forged/stale environment, cached publication failure, and 20 × 32 concurrent caller rounds. Real host ELF tests cover two consumers, 20 repeated acquire/close cycles and 1000 borrowed queries; the test-only final root close unloads exactly once. The packaged suite also runs its existing readiness fixture; it introduced no new coverage review or claim. `git diff --check`: PASS.

**Validation limit:** `ANDROID_NDK_HOME` is unset, so both runs explicitly skipped Android ARM32/ARM64 compilation. Namespace behavior uses doubles; the real-linker fixture uses host ELF. No Android runtime, physical-device, GPU, or performance claim is made. These limits do not reveal a source-level blocker; production SDK/NDK CI is still required.
