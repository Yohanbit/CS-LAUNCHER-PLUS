# Round 2: native graphics robustness and redundant setup work

Base: `13f0d24f38494a3a745739c7f4fed601e88b6fc0`, working branch `fix/mali-runtime-round2`.

**Integration correction:** this report supersedes its initial version. Review found that requiring OpenGL success during `pojavInit()` blocked the standard GLFW init→NO_API hint→window sequence. The final implementation separates platform initialization from lazy GL context initialization, and tests the full sequence rather than hint→create alone.

## Scope and evidence

Implemented three bounded changes in the existing EGL/OSMesa bridges. These are family-independent launcher fixes usable with supported Mali renderers, not G76-specific tuning, replacement drivers, a new backend, or an FPS guarantee. No Java files, workflow files, frame_metrics.c/h, synchronization primitives, or OSMesa render/swap code were changed by this worker. No commits or pushes were made.

### 1. Platform initialization is separate; GL context requests fail closed

Evidence in the original production code:

- `pojavInitOpenGL()` returned zero even after successful backend initialization. Its caller `pojavInit()` initialized GL unconditionally but ignored failure, permitting subsequent invalid GL dispatch. However, GLFW platform initialization must succeed before the application can select `GLFW_NO_API`, so propagating an unused GL failure at that point was not a valid fix.
- Missing/unknown renderer refused selection, but current-context/context-creation/make-current APIs unconditionally dereferenced bridge entries. A failed backend loader also left its table installed.
- GLFW's OpenGL window hint compared a possibly null `AMETHYST_RENDERER` with `strncmp`/`strcmp`.
- Legacy termination attempted EGL calls even though its PotatoBridge display is not populated by the current ctxbridges implementation.

Changes:

- Added production `clear_bridge_tbl()`. Successful platform initialization starts with an empty table/configuration and unknown metric coverage; rejected or incomplete/failed GL initialization clears all callable entries again.
- `pojavInit()` now initializes only JNI/platform/native-window state. It does not load or initialize a GL bridge, and succeeds independently of a missing/unusable renderer. JNI attachment or a null native window still makes platform initialization fail before native-window acquisition.
- The first non-NO_API `pojavCreateContext()` lazily initializes the backend, validates the complete bridge table, and returns NULL on missing/unknown/incomplete/failed GL initialization. Successful initialization is reused for subsequent/shared contexts; the default GLFW API remains GL even with no explicit hint.
- Platform initialization owns the acquired native-window reference whether the subsequent GL request succeeds or fails: a failed GL request does not tear down the still-valid platform, which can serve a NO_API window. This preserves the existing successful-platform lifetime rather than adding renderer-specific release behavior.
- Null-safe get/create/make-current dispatch and window-hint environment comparisons. Existing swap/interval pointer guards remain.
- Skip legacy termination calls with no legacy display or no loaded EGL termination function; this does not claim to implement complete ctxbridges teardown.
- Preserve the native Vulkan `GLFW_NO_API` window-handle branch and unknown frame coverage, now with a tested full platform-init→hint→create sequence independent of GL. Added explicit `<inttypes.h>`/`<android/api-level.h>` dependencies and a standard-C compound block around the OpenGL switch case.

### 2. Loader success requires a complete symbol set

Previously, both loaders checked only their GetProcAddress resolver, then returned success regardless of null function entries. EGL also depended exclusively on GetProcAddress for core EGL functions, which is not guaranteed on EGL 1.4 implementations.

Both production loaders now resolve the existing symbol set from the selected library's resolver first, with a same-handle direct-export `dlsym` fallback. Direct exports also work if the resolver is absent. Existing wrapper resolver precedence is preserved; no cross-library/default-process symbol lookup was introduced.

All 19 existing EGL entries and 10 existing OSMesa/GL entries must be nonnull before returning success. A missing entry emits a startup-only symbol-specific error, clears partial/stale function pointers, and closes the unsuccessful library handle. The temporary global libcutils dependency used for Mesa/Kopper is also released on failed loading. Successful library references remain loaded for the lifetime of their function pointers, as before.

### 3. EGL context setup checks failures and removes one redundant query

Previously, context creation allocated and immediately memset an unchecked pointer; queried config count; repeated config selection without checking that second call; ignored native-visual lookup/API-binding failures; and passed null environment values to `strncmp`/`strtol`.

Now:

- Check zero-initialized allocation and initialized display.
- Request the first matching config directly with `config_size=1`, validating return, count and config. The successful setup path executes exactly one `eglChooseConfig`, rather than two.
- Refuse context creation after native-visual lookup/API-binding failures, freeing the allocation.
- Missing/malformed/out-of-range/zero `LIBGL_ES` defaults to ES2, with parsing in `long` before conversion. Missing renderer comparison is safe. Valid version, desktop-API and sharing behavior remain supported.
- Clear the stored EGL display after failed `eglInitialize` and leave metric coverage unknown.

This is an initialization-time reduction, not a demonstrated per-frame speedup. No per-frame JNI, logging, allocations or extra synchronization were added. OSMesa `glFinish_p()` is unchanged.

## Changed resources

- `app_pojavlauncher/src/main/jni/egl_bridge.c`
- `app_pojavlauncher/src/main/jni/ctxbridges/{bridge_tbl.h,egl_loader.c,osmesa_loader.c,gl_bridge.c}`
- New `tools/native_graphics_round2_test.py`, `tools/test-native-graphics-round2.sh`
- Existing `tools/native_bridge_test.py`: fixture compatibility update to compile the actual production table-clearing helper and provide the newly checked complete-table doubles.

Other workers' concurrent Java/workflow changes are outside this report.

## Executed validation

New suite passes under both host GCC (`cc`) and Clang, with C11, optimization, `-Wall -Wextra -Werror`:

- Compiles each production loader in full against resolver/dlopen doubles. Tests resolver-first, resolver returning no core addresses, and absent-resolver/direct-export modes. Tests every required symbol individually missing in all three modes (57 EGL and 30 OSMesa missing-symbol failures), clearing every pointer and closing only unsuccessful handles. Tests library-open failure and all Mesa/Kopper dependency failure paths.
- Extracts actual production bridge/table/init/dispatch functions without rewriting their logic. Tests missing/unknown renderer, deliberately stale/incomplete tables, failed initialization, null dispatch, JNI attachment failure, null window, and full platform-init→NO_API hint→native-window creation sequences with an injected broken GL backend and a missing renderer. Switching the same initialized platform to OpenGL correctly rejects its unusable backend with no stale dispatch. Tests default GL, MobileGlues/Kopper/Zink lazy setup, and exactly-once successful backend initialization across shared contexts.
- Executes actual EGL initialization/context functions with API/allocation doubles: loader/display/initialize failures; single config-query success; allocation/config-count/config-pointer/native-visual/API-bind/context-create failures; missing and seven malformed version strings; valid ES3/desktop/shared-context behavior.

Existing suites passed locally:

- `test-native-bridge.sh`: seven original selection cases.
- `test-measurement.sh`: independent readers/lifecycle/native stress tests; host disabled-path mutex/clock checks; freestanding ARM32/ARM64 atomic probes.
- `test-runtime-safety.sh`: 184 safety-policy and 71 launch regression checks after the integration correction (the earlier snapshot contained 58 regressions).
- `test-extreme-options.sh`: 37 assertions.
- `test-dynamic-resolution.sh`: 298 policy plus 102 response/transition assertions.
- `git diff --check`: passed.

`NATIVE_REQUIRE_NDK=1` without an NDK was tested and correctly fails instead of reporting an Android pass. With `ANDROID_NDK_HOME` available, the new shell suite compiles all five relevant full production native translation units for `armv7a-linux-androideabi21` and `aarch64-linux-android21`, including the existing ARM64 `ADRENO_POSSIBLE` build define. The parent has added `NATIVE_REQUIRE_NDK=1` to CI; this worker did not edit the workflow.

### First-attempt test fixture issues, resolved

Integration review identified a real contract regression in the initial patch: eager GL failure caused `glfwInit()` to fail before consumers could request NO_API. The previous direct hint→create test missed this ordering. This was corrected by deferring GL initialization to the first GL context request; GCC and Clang now execute successful platform init followed by NO_API window creation with an unusable/missing GL backend, and separately execute rejected GL context requests. The existing suites were rerun after the correction. See `/tasklet/threads/a_mbnwcmag78z8ebyjcqg3/work/round2-integration-review.md` for the independent reviewer reproducer.

The first OSMesa host build lacked `KHR/khrplatform.h`: the repository GL headers require Khronos platform types normally supplied by the Android NDK, which is absent locally. The new test generates a minimal host-only type header in its temporary fixture directory; production includes were not substituted or altered. Subsequent full GCC and Clang runs passed. This explains the earlier missing-header result another worker observed while the fixture was being completed.

Clang's strict host compile also caught the existing declaration immediately after the OpenGL case label; the production case now has a compound block valid in pre-C23 C. Both compilers pass after that correction.

## Limits and remaining work

No local Android SDK/NDK or physical Android/Mali device is available. Actual NDK object compilation, complete APK linking and device EGL/OSMesa execution are not claimed locally; CI must confirm its mandatory ARM32/ARM64 compile and APK build. The freestanding atomic checks are not substitutes for a full NDK compile. No measured FPS, frame latency, compositor presentation, thermal or memory improvement is claimed.

Broader existing surface lifecycle risks were inspected but intentionally not changed: EGL surface replacement contains a driver-workaround sleep and unchecked detach/rebind calls, and OSMesa swaps do not check MakeCurrent before finishing. Removing the workaround, changing buffer ownership/surface recovery, or replacing `glFinish` needs a separately scoped device-backed validation task. Current changes stop uninitialized/missing dependency dispatch and strengthen context setup, but do not claim comprehensive runtime surface recovery or renderer teardown.

Raw local logs: `work/round2-native-host-gcc.log`, `work/round2-native-host-clang.log`, `work/round2-native-existing-suites.log`, `work/round2-native-required-ndk.log`. Expected missing-symbol/renderer stderr entries are intentionally injected failure tests.
