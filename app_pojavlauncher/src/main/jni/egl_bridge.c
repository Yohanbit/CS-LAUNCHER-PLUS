#include <jni.h>
#include <assert.h>
#include <dlfcn.h>

#include <stdbool.h>
#include <stdint.h>
#include <inttypes.h>
#include <stdio.h>
#include <stdlib.h>
#include <sys/types.h>
#include <unistd.h>
#include <time.h>
#include <pthread.h>

#include <EGL/egl.h>
#include <GL/osmesa.h>
#include "ctxbridges/osmesa_loader.h"
#include "driver_helper/nsbypass.h"

#ifdef GLES_TEST
#include <GLES2/gl2.h>
#endif

#include <android/native_window.h>
#include <android/api-level.h>
#include <android/native_window_jni.h>
#include <android/rect.h>
#include <string.h>
#include <environ/environ.h>
#include <android/dlext.h>
#include "utils.h"
#include "ctxbridges/bridge_tbl.h"
#include "ctxbridges/osm_bridge.h"
#include "frame_metrics.h"

#define GLFW_CLIENT_API 0x22001
/* Consider GLFW_NO_API as Vulkan API */
#define GLFW_NO_API 0
#define GLFW_OPENGL_API 0x30001

// This means that the function is an external API and that it will be used
#define EXTERNAL_API __attribute__((used))
// This means that you are forced to have this function/variable for ABI compatibility
#define ABI_COMPAT __attribute__((unused))


struct PotatoBridge {

    /* EGLContext */ void* eglContext;
    /* EGLDisplay */ void* eglDisplay;
    /* EGLSurface */ void* eglSurface;
/*
    void* eglSurfaceRead;
    void* eglSurfaceDraw;
*/
};
EGLConfig config;
struct PotatoBridge potatoBridge;

#include "ctxbridges/egl_loader.h"
#include "ctxbridges/osmesa_loader.h"

#define RENDERER_GL4ES 1
#define RENDERER_VK_ZINK 2
#define RENDERER_VULKAN 4

/* Separate atomic32 readiness snapshot: config_renderer and bridge pointers
 * are GLFW-thread state, not safe to read from a UI/watchdog JNI thread.
 * All accesses use relaxed atomics: this publishes ONLY the enum, not graphics
 * objects; observing it never authorizes dereferencing a bridge/context.
 * 0 UNREADY, 1 COUNTED submissions, 2 NATIVE_VULKAN_UNSUPPORTED. */
static uint32_t native_counter_mode;
_Static_assert(__atomic_always_lock_free(sizeof(uint32_t), 0),
               "counter coverage snapshot must be lock-free on this ABI");

EXTERNAL_API void pojavTerminate() {
    __atomic_store_n(&native_counter_mode, 0, __ATOMIC_RELAXED);
    printf("EGLBridge: Terminating\n");

    switch (pojav_environ->config_renderer) {
        case RENDERER_GL4ES: {
            /* The legacy PotatoBridge is not populated by ctxbridges. In
             * particular, failed initialization must not call unloaded EGL. */
            if (potatoBridge.eglDisplay == EGL_NO_DISPLAY || eglTerminate_p == NULL) break;
            eglMakeCurrent_p(potatoBridge.eglDisplay, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
            eglDestroySurface_p(potatoBridge.eglDisplay, potatoBridge.eglSurface);
            eglDestroyContext_p(potatoBridge.eglDisplay, potatoBridge.eglContext);
            eglTerminate_p(potatoBridge.eglDisplay);
            eglReleaseThread_p();

            potatoBridge.eglContext = EGL_NO_CONTEXT;
            potatoBridge.eglDisplay = EGL_NO_DISPLAY;
            potatoBridge.eglSurface = EGL_NO_SURFACE;
        } break;

            //case RENDERER_VIRGL:
        case RENDERER_VK_ZINK: {
            // Nothing to do here
        } break;
    }
}

JNIEXPORT void JNICALL Java_net_kdt_pojavlaunch_utils_JREUtils_setupBridgeWindow(JNIEnv* env, ABI_COMPAT jclass clazz, jobject surface) {
    pojav_environ->pojavWindow = ANativeWindow_fromSurface(env, surface);
    if(br_setup_window != NULL) br_setup_window();
}


JNIEXPORT void JNICALL
Java_net_kdt_pojavlaunch_utils_JREUtils_releaseBridgeWindow(ABI_COMPAT JNIEnv *env, ABI_COMPAT jclass clazz) {
    ANativeWindow_release(pojav_environ->pojavWindow);
}

EXTERNAL_API void* pojavGetCurrentContext() {
    return br_get_current != NULL ? br_get_current() : NULL;
}

//#define ADRENO_POSSIBLE
#ifdef ADRENO_POSSIBLE
void* load_turnip_vulkan() {
    if(getenv("POJAV_LOAD_TURNIP") == NULL) return NULL;
    const char* native_dir = getenv("POJAV_NATIVEDIR");
    const char* cache_dir = getenv("TMPDIR");
    if(!linker_ns_load(native_dir)) return NULL;
    void* linkerhook = linker_ns_dlopen("liblinkerhook.so", RTLD_LOCAL | RTLD_NOW);
    if(linkerhook == NULL) return NULL;
    void* turnip_driver_handle = linker_ns_dlopen("libvulkan_freedreno.so", RTLD_LOCAL | RTLD_NOW);
    if(turnip_driver_handle == NULL) {
        printf("AdrenoSupp: Failed to load Turnip!\n%s\n", dlerror());
        dlclose(linkerhook);
        return NULL;
    }
    void* dl_android = linker_ns_dlopen("libdl_android.so", RTLD_LOCAL | RTLD_LAZY);
    if(dl_android == NULL) {
        dlclose(linkerhook);
        dlclose(turnip_driver_handle);
        return NULL;
    }
    void* android_get_exported_namespace = dlsym(dl_android, "android_get_exported_namespace");
    void (*linkerhook_pass_handles)(void*, void*, void*) = dlsym(linkerhook, "app__pojav_linkerhook_pass_handles");
    if(linkerhook_pass_handles == NULL || android_get_exported_namespace == NULL) {
        dlclose(dl_android);
        dlclose(linkerhook);
        dlclose(turnip_driver_handle);
        return NULL;
    }
    linkerhook_pass_handles(turnip_driver_handle, android_dlopen_ext, android_get_exported_namespace);
    void* libvulkan = linker_ns_dlopen_unique(cache_dir, "libvulkan.so", RTLD_LOCAL | RTLD_NOW);
    return libvulkan;
}
#endif

/* VULKAN_PTR is an output ABI for Mesa, never an input handle. An environment
 * value may survive a previous process/failed launch or be supplied by a user.
 * Only this mutex-protected, successfully opened handle is authoritative. */
static pthread_mutex_t vulkan_loader_mutex = PTHREAD_MUTEX_INITIALIZER;
static void *vulkan_loader_handle;
static bool vulkan_loader_in_namespace;
/* dladdr's pathname belongs to the loader, kept alive by our root reference. */
static const char *vulkan_loader_path;

static bool set_vulkan_ptr(void* ptr) {
    if (ptr == NULL) return unsetenv("VULKAN_PTR") == 0;
    char envval[2 * sizeof(uintptr_t) + 1];
    int length = snprintf(envval, sizeof(envval), "%"PRIxPTR, (uintptr_t)ptr);
    if (length < 0 || (size_t)length >= sizeof(envval)) return false;
    const char *published = getenv("VULKAN_PTR");
    if (published != NULL && strcmp(published, envval) == 0) return true;
    return setenv("VULKAN_PTR", envval, 1) == 0;
}

static bool valid_vulkan_loader(void *handle) {
    /* Required loader globals from Vulkan 1.0, also present in Vulkan 1.1.
     * This is NOT a physical-device feature/version/extension compatibility
     * probe and deliberately requires no Vulkan 1.2/1.3 entry points. */
    const char *required[] = {"vkGetInstanceProcAddr", "vkCreateInstance",
        "vkEnumerateInstanceExtensionProperties", "vkEnumerateInstanceLayerProperties"};
    for (size_t i = 0; i < sizeof(required) / sizeof(required[0]); i++) {
        dlerror();
        void *symbol = dlsym(handle, required[i]);
        const char *error = dlerror();
        if (symbol == NULL || error != NULL) {
            fprintf(stderr, "EGLBridge: Vulkan loader missing %s: %s\n",
                    required[i], error != NULL ? error : "null export");
            return false;
        }
    }
    return true;
}

static void *open_vulkan_loader(void) {
#ifdef ADRENO_POSSIBLE
    if(android_get_device_api_level() >= 28) { // the optional loader does not support below that
        void* result = load_turnip_vulkan();
        if(result != NULL) {
            if (valid_vulkan_loader(result)) {
                printf("AdrenoSupp: Loaded Turnip, loader address: %p\n", result);
                vulkan_loader_in_namespace = true;
                return result;
            }
            dlclose(result);
        }
    }
#endif
    vulkan_loader_in_namespace = false;
    printf("OSMDroid: loading vulkan regularly...\n");
    void* vulkan_ptr = dlopen("libvulkan.so", RTLD_NOW | RTLD_LOCAL);
    if (vulkan_ptr == NULL) {
        const char *error = dlerror();
        fprintf(stderr, "EGLBridge: could not load libvulkan.so: %s\n",
                error != NULL ? error : "unknown dlopen failure");
        return NULL;
    }
    if (!valid_vulkan_loader(vulkan_ptr)) {
        dlclose(vulkan_ptr);
        return NULL;
    }
    printf("OSMDroid: loaded vulkan, ptr=%p\n", vulkan_ptr);
    return vulkan_ptr;
}

void* maybe_load_vulkan(void) {
    pthread_mutex_lock(&vulkan_loader_mutex);
    void *result = vulkan_loader_handle;
    if (result == NULL) {
        /* Do not let a loader/Mesa constructor observe a stale input handle. */
        if (!set_vulkan_ptr(NULL)) {
            fprintf(stderr, "EGLBridge: could not clear stale VULKAN_PTR\n");
            pthread_mutex_unlock(&vulkan_loader_mutex);
            return NULL;
        }
        result = open_vulkan_loader();
    }
    if (result != NULL && !set_vulkan_ptr(result)) {
        fprintf(stderr, "EGLBridge: could not publish Vulkan loader handle\n");
        /* A published cached handle may already be in use; never unload it.
         * New, unpublished handles have no consumers and can be closed. */
        if (vulkan_loader_handle == NULL) dlclose(result);
        set_vulkan_ptr(NULL);
        result = NULL;
    }
    if (result != NULL) vulkan_loader_handle = result;
    pthread_mutex_unlock(&vulkan_loader_mutex);
    return result;
}

/* Keep the existing C ABI; both GL and LWJGL use the checked path. Successful
 * handles remain loaded for process lifetime, as required by Mesa's raw ABI. */
void load_vulkan(void) {
    (void)maybe_load_vulkan();
}

/* LWJGL SharedLibrary.free() consumes the reference created by its dlopen (or
 * createFromHandle). Keep the cache's root reference independent: only owning
 * consumers use this function, and each must dlclose its returned reference.
 * Borrowed internal maybe_load_vulkan() queries never increment the refcount. */
void *acquire_vulkan_loader(void) {
    void *handle = maybe_load_vulkan();
    if (handle == NULL) return NULL;
    pthread_mutex_lock(&vulkan_loader_mutex);
    dlerror(); /* Retain diagnostics must not reuse another call's error. */
    if (vulkan_loader_path == NULL) {
        Dl_info info;
        void *entry = dlsym(handle, "vkGetInstanceProcAddr");
        if (entry != NULL && dladdr(entry, &info) != 0 && info.dli_fname != NULL)
            vulkan_loader_path = info.dli_fname;
    }
    void *owned = NULL;
    if (vulkan_loader_path != NULL) {
#ifdef ADRENO_POSSIBLE
        if (vulkan_loader_in_namespace)
            owned = linker_ns_dlopen(vulkan_loader_path, RTLD_NOW | RTLD_LOCAL);
        else
#endif
            owned = dlopen(vulkan_loader_path, RTLD_NOW | RTLD_LOCAL);
    }
    /* Never hand a different library to LWJGL or leak its extra reference. */
    if (owned != handle) {
        if (owned != NULL) dlclose(owned);
        const char *error = dlerror();
        fprintf(stderr, "EGLBridge: could not retain selected Vulkan loader: %s\n",
                error != NULL ? error : "no matching loader reference");
        vulkan_loader_path = NULL;
        owned = NULL;
    }
    pthread_mutex_unlock(&vulkan_loader_mutex);
    return owned;
}

int pojavInitOpenGL() {
    __atomic_store_n(&native_counter_mode, 0, __ATOMIC_RELAXED);
    clear_bridge_tbl();
    pojav_environ->config_renderer = 0;
    pojav_frame_set_coverage(0);
    // Only affects GL4ES as of now
    const char *forceVsync = getenv("FORCE_VSYNC");
    pojav_environ->force_vsync = forceVsync != NULL && strcmp(forceVsync, "true") == 0;

    // NOTE: Override for now.
    const char *renderer = getenv("AMETHYST_RENDERER");
    if (renderer == NULL) {
        pojav_frame_set_coverage(0);
        fprintf(stderr, "EGLBridge: no renderer configured; refusing bridge initialization\n");
        return 0;
    }
    if (strncmp("opengles", renderer, 8) == 0) {
        pojav_environ->config_renderer = RENDERER_GL4ES;
        if (!strcmp(renderer, "opengles3_desktopgl_zink_kopper")) {
            if (maybe_load_vulkan() == NULL) {
                pojav_environ->config_renderer = 0;
                return 0;
            }
            setenv("GALLIUM_DRIVER", "zink", 1);
            setenv("MESA_ANDROID_NO_KMS_SWRAST", "1", 1);
        }
        set_gl_bridge_tbl();
    } else if (strcmp(renderer, "vulkan_zink") == 0) {
        pojav_environ->config_renderer = RENDERER_VK_ZINK;
        if (maybe_load_vulkan() == NULL) {
            pojav_environ->config_renderer = 0;
            return 0;
        }
        setenv("GALLIUM_DRIVER","zink",1);
        set_osm_bridge_tbl();
    } else {
        pojav_frame_set_coverage(0);
        fprintf(stderr, "EGLBridge: unsupported renderer %s; refusing bridge initialization\n", renderer);
        return 0;
    }
    if (br_init == NULL || br_setup_window == NULL || br_init_context == NULL ||
        br_make_current == NULL || br_get_current == NULL || br_swap_buffers == NULL ||
        br_swap_interval == NULL) {
        clear_bridge_tbl();
        pojav_environ->config_renderer = 0;
        pojav_frame_set_coverage(0);
        fprintf(stderr, "EGLBridge: incomplete bridge table\n");
        return 0;
    }
    if (!br_init()) {
        clear_bridge_tbl();
        pojav_environ->config_renderer = 0;
        pojav_frame_set_coverage(0);
        fprintf(stderr, "EGLBridge: backend initialization failed\n");
        return 0;
    }
    br_setup_window();
    __atomic_store_n(&native_counter_mode, 1, __ATOMIC_RELAXED);
    return 1;
}

extern void updateMonitorSize(int width, int height);

EXTERNAL_API int pojavInit() {
    __atomic_store_n(&native_counter_mode, 0, __ATOMIC_RELAXED);
    pojav_environ->glfwThreadVmEnv = get_attached_env(pojav_environ->runtimeJavaVMPtr);
    if(pojav_environ->glfwThreadVmEnv == NULL) {
        printf("Failed to attach Java-side JNIEnv to GLFW thread\n");
        return 0;
    }
    if (pojav_environ->pojavWindow == NULL) return 0;
    /* GLFW initialization precedes GLFW_CLIENT_API window hints. Set up the
     * platform only: an unused/failed GL backend must not block NO_API windows. */
    clear_bridge_tbl();
    pojav_environ->config_renderer = 0;
    pojav_frame_set_coverage(0);
    ANativeWindow_acquire(pojav_environ->pojavWindow);
    pojav_environ->savedWidth = ANativeWindow_getWidth(pojav_environ->pojavWindow);
    pojav_environ->savedHeight = ANativeWindow_getHeight(pojav_environ->pojavWindow);
    ANativeWindow_setBuffersGeometry(pojav_environ->pojavWindow,pojav_environ->savedWidth,pojav_environ->savedHeight,AHARDWAREBUFFER_FORMAT_R8G8B8X8_UNORM);
    updateMonitorSize(pojav_environ->savedWidth, pojav_environ->savedHeight);
    return 1;
}

EXTERNAL_API void pojavSetWindowHint(int hint, int value) {
    if (hint != GLFW_CLIENT_API) return;
    switch (value) {
        case GLFW_NO_API:
            pojav_environ->config_renderer = RENDERER_VULKAN;
            pojav_frame_set_coverage(0); /* Native Vulkan has no counted boundary. */
            __atomic_store_n(&native_counter_mode, 2, __ATOMIC_RELAXED);
            /* Nothing to do: initialization is handled in Java-side */
            // pojavInitVulkan();
            break;
        case GLFW_OPENGL_API: {
            __atomic_store_n(&native_counter_mode, 0, __ATOMIC_RELAXED);
            const char *renderer = getenv("AMETHYST_RENDERER");
            if (renderer != NULL && strncmp("opengles", renderer, 8) == 0) {
                pojav_environ->config_renderer = RENDERER_GL4ES;
            } else if (renderer != NULL && strcmp(renderer, "vulkan_zink") == 0) {
                pojav_environ->config_renderer = RENDERER_VK_ZINK;
            } else {
                pojav_environ->config_renderer = 0;
            }
            /* Nothing to do: initialization is called in pojavCreateContext */
            // pojavInitOpenGL();
            break;
        }
        default:
            printf("GLFW: Unimplemented API 0x%x\n", value);
            abort();
    }
}

EXTERNAL_API void pojavSwapBuffers() {
    if (br_swap_buffers == NULL || br_get_current == NULL) return;
    basic_render_window_t *current = br_get_current();
    if (current == NULL) return;
    /* Shared/upload contexts and dummy surfaces are not displayed frames. */
    if (current != pojav_environ->mainWindowBundle) {
        br_swap_buffers();
        return;
    }
    uint32_t token = pojav_frame_timing_token();
    uint64_t start_ns = (token & 1u) ? pojav_monotonic_ns() : 0;
    bool submitted = br_swap_buffers();
    uint64_t end_ns = (token & 1u) ? pojav_monotonic_ns() : 0;
    pojav_frame_record_swap(submitted, token, start_ns, end_ns);
}

/* Native Vulkan bypasses these bridges. Never report an uncounted path as 0 FPS. */
static int frame_counter_coverage(void) {
    return (int)pojav_frame_coverage();
}

/* Startup/watchdog readiness, not a Vulkan presentation counter. No renderer
 * pointer, loader lock, clock, allocation, or graphics call on this path. */
JNIEXPORT jint JNICALL
Java_net_kdt_pojavlaunch_utils_FpsCounter_nativeGetCounterCoverage(JNIEnv* env, jclass clazz) {
    (void)env; (void)clazz;
    return (jint)__atomic_load_n(&native_counter_mode, __ATOMIC_RELAXED);
}

JNIEXPORT jint JNICALL
Java_net_kdt_pojavlaunch_utils_FpsCounter_nativeGetFps(JNIEnv* env, jclass clazz) {
    (void) env; (void) clazz;
    static __thread uint64_t previous_count, previous_time_ns;
    if (!frame_counter_coverage()) { previous_time_ns = 0; return -1; }
    int fps = pojav_frame_rate(pojav_frame_submissions(), pojav_monotonic_ns(),
                               &previous_count, &previous_time_ns);
    return (jint)fps;
}

/* Legacy name retained for binary compatibility: successful queue submissions,
 * NOT compositor presentation or scanout. No count for pbuffer/failed swaps. */
JNIEXPORT jlong JNICALL
Java_net_kdt_pojavlaunch_utils_FpsCounter_nativeGetTotalPresents(JNIEnv* env, jclass clazz) {
    (void) env; (void) clazz;
    return frame_counter_coverage() ? (jlong)pojav_frame_submissions() : -1;
}

JNIEXPORT void JNICALL
Java_net_kdt_pojavlaunch_utils_FrameMetrics_nativeSetTimingEnabled(JNIEnv* env, jclass clazz,
                                                                  jboolean enabled) {
    (void)env; (void)clazz;
    pojav_frame_set_timing_enabled(enabled == JNI_TRUE);
}

/* Original long[11] layout retained. New readers use long[13], appending the
 * timing epoch and coverage (0 unknown, 1 EGL, 2 OSMesa native-window post). */
JNIEXPORT jboolean JNICALL
Java_net_kdt_pojavlaunch_utils_FrameMetrics_nativeReadSnapshot(JNIEnv* env, jclass clazz,
                                                             jlongArray values) {
    (void)clazz;
    if (values == NULL || (*env)->GetArrayLength(env, values) < 11) return JNI_FALSE;
    pojav_frame_snapshot s;
    pojav_frame_snapshot_read(&s);
    jlong data[13] = {(jlong)s.attempts, (jlong)s.submissions, (jlong)s.unsuccessful_swaps,
        (jlong)s.swap_ns_total, (jlong)s.swap_ns_last, (jlong)s.swap_ns_max,
        (jlong)s.inter_swap_ns_total, (jlong)s.inter_swap_samples,
        (jlong)s.inter_swap_ns_last, (jlong)s.sample_time_ns,
        (jlong)pojav_monotonic_ns(), (jlong)s.timing_epoch, (jlong)frame_counter_coverage()};
    (*env)->SetLongArrayRegion(env, values, 0,
                             (*env)->GetArrayLength(env, values) >= 13 ? 13 : 11, data);
    return (*env)->ExceptionCheck(env) ? JNI_FALSE : JNI_TRUE;
}


EXTERNAL_API void pojavMakeCurrent(void* window) {
    if (br_make_current != NULL) br_make_current((basic_render_window_t*)window);
}

EXTERNAL_API void* pojavCreateContext(void* contextSrc) {
    if (pojav_environ->config_renderer == RENDERER_VULKAN) {
        return (void *) pojav_environ->pojavWindow;
    }
    /* Only a GL context request needs the GL bridge. Failed initialization
     * clears the table and returns NULL; successful setup is reused by shared
     * contexts rather than repeated for every context creation. */
    if ((pojav_environ->config_renderer == 0 || br_init_context == NULL) && !pojavInitOpenGL()) return NULL;
    basic_render_window_t *context = br_init_context != NULL ?
        br_init_context((basic_render_window_t*)contextSrc) : NULL;
    /* NO_API clears coverage without unloading the GL bridge. Restore the
     * existing bridge's counted boundary only after GL context success. */
    if (context != NULL) {
        pojav_frame_set_coverage(pojav_environ->config_renderer == RENDERER_VK_ZINK ? 2 : 1);
    }
    __atomic_store_n(&native_counter_mode, context != NULL ? 1 : 0, __ATOMIC_RELAXED);
    return context;
}

EXTERNAL_API JNIEXPORT jlong JNICALL
Java_org_lwjgl_vulkan_VK_getVulkanDriverHandle(ABI_COMPAT JNIEnv *env, ABI_COMPAT jclass thiz) {
    printf("EGLBridge: LWJGL-side Vulkan loader requested the Vulkan handle\n");
    return (jlong)(uintptr_t)acquire_vulkan_loader();
}

EXTERNAL_API void pojavSwapInterval(int interval) {
    if (br_swap_interval != NULL) br_swap_interval(interval);
}

