# Round 2 — concrete launcher overhead/lifecycle corrections

## Scope and status

Working tree: `/tmp/unamed-work`, branch `fix/mali-runtime-round2`, inspected clean at baseline `13f0d24f38494a3a745739c7f4fed601e88b6fc0` before changes. Other agents subsequently made their own integrated changes. No commit or push performed.

Implemented three conservative improvement groups. No GPU tuning, driver/vendor assumptions, guessed FPS, surface/native-rendering changes, or claimed device-level gain. Existing diagnostic opt-in, native submission metric meaning, thermal sample cadence on supported devices, priority experiment bounds, and foreground teardown contracts remain intact.

## 1. Eliminate pointless thermal-only workers and heartbeat listener allocations

### Evidence / defect

Baseline `SchedulingPolicy.begin` creates `CS-PerfEngine` whenever `thermalPolling` is true, even on Android <29 or without the power service. Baseline `ThermalGovernor.sample` has no usable public thermal API on these devices, so the resulting 1-second heartbeat can only report UNKNOWN. Baseline `tick` also constructs `new ArrayList<>(tickListeners)` each second although registrations normally change only at foreground boundaries.

### Change

- `SchedulingPolicy.begin` skips thermal-only work when `ThermalGovernor.canPoll(ctx)` cannot acquire a public API-compatible power service. Explicit priority experiments still start normally, even on older Android.
- `ThermalGovernor.start` leaves no sampling marker/listener on unsupported devices or when the scheduler could not start.
- Listeners use a volatile immutable `Runnable[]`, copied on registration/removal instead of on every tick. Existing public void `addTickListener` API is preserved; a package-private success-returning handshake lets the thermal consumer distinguish a live scheduler from a failed start.
- Listener registration checks `stopped` under the instance lifecycle lock. Old callbacks cannot attach or repost into replacement runs.

### Tests

Real production scheduler/governor with deterministic Android doubles verifies normal-mode inactivity, Android 28 and missing-service no-thread/no-callback paths, exactly one supported foreground worker, synchronous initial status/headroom, unchanged minimum 10-second headroom interval, duplicate registration/removal, immutable snapshot reuse, stale callback rejection, preserved explicit experiment startup, and 100 concurrent callback/end races. This proves logical overhead removal, not battery or FPS improvement.

## 2. Default-off / duplicate diagnostic teardown performs no JNI timing writes

### Evidence / defect

Baseline `FrameMetrics.stop` always invokes `nativeSetTimingEnabled(false)` before checking whether any run exists. `start` when diagnostics are disabled calls `stop`, and Activity pause, stop and destroy also call `stop`. Therefore default-off and duplicate lifecycle transitions unnecessarily enter native timing control. Before JNI is loaded, each attempt instead constructs/catches an `UnsatisfiedLinkError`.

### Change

`stop` first invalidates `active`, returns immediately when no run exists, and disables native timing only if that exact run successfully enabled it. A pre-first-tick or late-linkage run owns no native timing epoch and therefore performs no unowned timing write. Enabled runs still revoke timing exactly once; queued old callbacks remain inert.

### Tests

The production `FrameMetrics` class is loaded with a small host JNI call spy: default-off startup plus repeated stop produces zero enable/disable calls; stopping before the first tick also produces zero calls; two sampling ticks enable once and read twice; repeated stops disable once; live opt-out disables once; stale callbacks cannot enable or repost. Existing measurement lifecycle tests separately cover absent/late native linkage. The spy is only a test library and is not packaged in the launcher.

## 3. Roll back partially created optional workers

### Evidence / defect

Baseline collector startup leaves `active` assigned and a started thread behind if Handler construction fails. Baseline scheduler constructor similarly starts its thread before Handler creation without cleanup. Initial `Handler.post` rejection was not checked by either sampler or scheduler. An optional diagnostics/scheduler startup failure could thus leave a live worker, a dead retained run preventing retries, or a false thermal sampling marker.

### Change

- Collector starts transactionally and pauses/cleans partial startup while retaining any earlier observations.
- Scheduler constructor quits a partially started worker; a failed initial post causes `end` cleanup.
- Both samplers and the scheduler reject unsuccessful initial posts explicitly.
- Thermal registration handshake prevents reporting active polling after scheduler startup failure.
- Failures produce one diagnostic warning at the failed startup, not a repeated periodic error loop.

### Tests

Inject thread-start exception, Handler-construction exception, and initial-post rejection separately for the production scheduler and both production samplers. Every case has no live worker or queued callback, no retained active run/false thermal marker, clean successful retry, and clean subsequent teardown. Unstarted diagnostic retries do not alter native timing. Fatal VM/resource errors are not claimed to be recoverable by sampler startup guards.

## Changed files (ownership only)

Production:

- `app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/performance/SchedulingPolicy.java`
- `app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/performance/ThermalGovernor.java`
- `app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/performance/FrameSampleCollector.java`
- `app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/utils/FrameMetrics.java`

New tests:

- `tools/test-overhead-lifecycle.sh` (executable; include all new files when committing)
- `tools/OverheadLifecycleTest.java`
- `tools/overhead_frame_metrics_jni.c`
- `tools/overhead_test_support/` (9 small doubles)

Existing measurement doubles are reused selectively without modifications. No edits to Tools, JREUtils, Runtime classes, JvmPolicy, MainActivity/HUD, surfaces, DRS, native rendering, policies or build workflow. SessionStats, PerformanceEngine and existing HUD paths were inspected but not rewritten gratuitously.

## Commands, results and logs

Environment: OpenJDK/javac 21.0.12.1, Java source/API target `--release 8`, GCC 14.2.0, Clang 19.1.7.

Run from the repository root:

```sh
tools/test-overhead-lifecycle.sh
CC=clang tools/test-overhead-lifecycle.sh
sh tools/test-measurement.sh
for test in tools/test-*.sh; do bash "$test"; done
git diff --check
```

Results:

- New production overhead/lifecycle suite: PASS with GCC and Clang.
- Existing measurement suites: PASS independent readers, reset/pause/zero/>1000 windows, 100 sampler pause races, foreground fallback label reconciliation, 400,000 native submissions with 4 writers/4 readers, diagnostic epoch/gating checks.
- Complete available host regression invocation: dynamic resolution 298 +102 assertions PASS; options/pacing 37 PASS; native bridge selection 7 PASS; runtime safety 184 and launch regressions 58 PASS; measurement and overhead PASS.
- New parallel graphics-agent suite passed its EGL-loader section but then failed compiling its OSMesa-loader fixture because `KHR/khrplatform.h` was missing from the host include setup. This is outside this task's changed files. Parent/native agent must resolve before claiming a fully green integrated suite.
- `git diff --check`: PASS on the integrated tree at final inspection.
- Full Android NDK compile skipped locally (`ANDROID_NDK_HOME` unset); freestanding ARM32/64 atomic probes passed. Current Java changes need no NDK feature, but Android APK/build verification remains the parent's CI responsibility.

Absolute logs:

- `/tasklet/threads/a_mbnwcmag78z8ebyjcqg3/work/test-logs/round2-overhead.txt`
- `/tasklet/threads/a_mbnwcmag78z8ebyjcqg3/work/test-logs/round2-overhead-clang.txt`
- `/tasklet/threads/a_mbnwcmag78z8ebyjcqg3/work/test-logs/round2-measurement.txt`
- `/tasklet/threads/a_mbnwcmag78z8ebyjcqg3/work/test-logs/round2-host-regressions.txt`

Exact new-test requirements: Linux JDK with `javac`, `java`, `jni.h`/`include/linux/jni_md.h`, C compiler supporting shared libraries, standard `sh`, `find`, `readlink`; no Android SDK/NDK or third-party packages. Uses `JAVA_HOME` if set or derives it from javac. Compatible with CI JDK17. Workflow already discovers `tools/test-*.sh`, so no workflow change needed here.

## Limits and follow-up

Android doubles model queues and lifecycle ownership, not a physical Looper's scheduling or platform thread/resource exhaustion. There are no device GPU/presentation, thermal, energy, allocation-profile, memory-footprint, startup-latency, FPS or controlled A/B measurements. Logical no-work paths, eliminated listener copies and JNI ownership counts are the supported claims.

Inspection also noticed MainActivity's first-frame watcher posts every 250ms until a native count appears and is only removed on destroy; unlike the diagnostic samplers it is not explicitly paused. This was not modified in the selected three groups and is a possible separately coordinated follow-up, especially for native Vulkan's intentionally unknown counter coverage. Do not claim every launcher callback is background-inert based solely on these sampler tests.

One first-attempt test invocation (`tools/test-measurement.sh`) failed with permission denied because this branch stores that existing script without executable mode. Rerunning with `sh` succeeded. Its mode was not changed; CI invokes scripts through bash already.
