#include <EGL/egl.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <string.h>
#include <malloc.h>
#include <stdlib.h>
#include <dlfcn.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <environ/environ.h>
#include "gl_bridge.h"
#include "egl_loader.h"
#include "../frame_pacing.h"
#include "../frame_metrics.h"

#define TAG __FILE_NAME__
#include <unistd.h>
#include <log.h>

//
// Created by maks on 17.09.2022.
//

static __thread gl_render_window_t* currentBundle;
static EGLDisplay g_EglDisplay;
static __thread int requested_swap_interval = 1; /* EGL's normal default */
static void gl_apply_swap_interval(void);

bool gl_init() {
    pojav_frame_set_coverage(0);
    if(!dlsym_EGL()) return false;
    g_EglDisplay = eglGetDisplay_p(EGL_DEFAULT_DISPLAY);
    if (g_EglDisplay == EGL_NO_DISPLAY) {
        LOGE("%s", "eglGetDisplay_p(EGL_DEFAULT_DISPLAY) returned EGL_NO_DISPLAY");
        return false;
    }
    if (eglInitialize_p(g_EglDisplay, 0, 0) != EGL_TRUE) {
        LOGE("eglInitialize_p() failed: %04x", eglGetError_p());
        g_EglDisplay = EGL_NO_DISPLAY;
        return false;
    }
    /* Loader/display availability is not a successfully created context. */
    return true;
}

gl_render_window_t* gl_get_current() {
    return currentBundle;
}

static void gl4esi_get_display_dimensions(int* width, int* height) {
    if(currentBundle == NULL) goto zero;
    EGLSurface surface = currentBundle->surface;
    // Fetch dimensions from the EGL surface - the most reliable way
    EGLBoolean result_width = eglQuerySurface_p(g_EglDisplay, surface, EGL_WIDTH, width);
    EGLBoolean result_height = eglQuerySurface_p(g_EglDisplay, surface, EGL_HEIGHT, height);
    if(!result_width || !result_height) goto zero;
    return;

    zero:
    // No idea what to do, but feeding gl4es incorrect or non-initialized dimensions may be
    // a bad idea. Set to zero in case of errors.
    *width = 0;
    *height = 0;
}

gl_render_window_t* gl_init_context(gl_render_window_t *share) {
    if (g_EglDisplay == EGL_NO_DISPLAY) return NULL;
    gl_render_window_t* bundle = calloc(1, sizeof(gl_render_window_t));
    if (bundle == NULL) return NULL;
    EGLint egl_attributes[] = { EGL_BLUE_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_RED_SIZE, 8, EGL_ALPHA_SIZE, 8, EGL_DEPTH_SIZE, 24, EGL_SURFACE_TYPE, EGL_WINDOW_BIT|EGL_PBUFFER_BIT, EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT, EGL_NONE };
    EGLint num_configs = 0;

    /* Request the first matching config directly: the previous count-only
     * query was redundant and the second (unchecked) query could fail. */
    if (eglChooseConfig_p(g_EglDisplay, egl_attributes, &bundle->config, 1, &num_configs) != EGL_TRUE) {
        LOGE("eglChooseConfig_p() failed: %04x", eglGetError_p());
        free(bundle);
        return NULL;
    }
    if (num_configs == 0 || bundle->config == NULL) {
        LOGE("%s", "eglChooseConfig_p() found no matching config");
        free(bundle);
        return NULL;
    }

    if (!eglGetConfigAttrib_p(g_EglDisplay, bundle->config, EGL_NATIVE_VISUAL_ID, &bundle->format)) {
        LOGE("eglGetConfigAttrib_p() failed: %04x", eglGetError_p());
        free(bundle);
        return NULL;
    }

    {
        EGLBoolean bindResult;
        const char* renderer = getenv("AMETHYST_RENDERER");
        if (renderer != NULL && strncmp(renderer, "opengles3_desktopgl", 19) == 0) {
            printf("EGLBridge: Binding to desktop OpenGL\n");
            bindResult = eglBindAPI_p(EGL_OPENGL_API);
        } else {
            printf("EGLBridge: Binding to OpenGL ES\n");
            bindResult = eglBindAPI_p(EGL_OPENGL_ES_API);
        }
        if (!bindResult) {
            LOGE("EGLBridge: bind failed: %04x", eglGetError_p());
            free(bundle);
            return NULL;
        }
    }

    const char* version = getenv("LIBGL_ES");
    char* end = NULL;
    long parsed_version = version != NULL ? strtol(version, &end, 0) : 0;
    int libgl_es = version != NULL && end != version && *end == '\0' &&
                   parsed_version > 0 && parsed_version <= INT16_MAX ? (int)parsed_version : 2;
    const EGLint egl_context_attributes[] = { EGL_CONTEXT_CLIENT_VERSION, libgl_es, EGL_NONE };
    bundle->context = eglCreateContext_p(g_EglDisplay, bundle->config, share == NULL ? EGL_NO_CONTEXT : share->context, egl_context_attributes);

    if (bundle->context == EGL_NO_CONTEXT) {
        LOGE("eglCreateContext_p() finished with error: %04x", eglGetError_p());
        free(bundle);
        return NULL;
    }
    return bundle;
}

bool gl_swap_surface(gl_render_window_t* bundle) {
    /*
     * In some cases (see MinecraftGLSurface.start(), android kills the surface automatically for
     * us, if we try to release/destroy it, we SIGSEGV. Check if we are -19x-19 or some other
     * invalid value and skip the release because Android decided to handle releasing it for us.
     * This goes against every piece of documentation I have ever seen but who actually reads those?
     *
     * Some drivers take forever to properly destroy the surface, they do it part at a time or
     * some other garbage while SIGSEGVing us if we try releasing while they're in the middle of
     * turning the surface dead. This makes the width and height make it look valid when it actually
     * isn't so we wait for them and hope there is no race condition of both us and Android trying
     * to release the surface. This seems driver dependent as AVD and Waydroid do not need 0.75s
     * to set the bloody height and width to their proper values. They just do it, instantly.
     */
    usleep(750000); // An overkill amount of time to wait for a surface to finish dying
    /* Android destroys the Java Surface, not our acquired ANativeWindow ref.
     * Dimensions are not an ownership/liveness test. The caller has detached
     * EGL before replacing a live surface. Preserve the legacy delay above,
     * but balance our references even when the underlying Surface is gone. */
    if (bundle->surface != EGL_NO_SURFACE) {
        if (!eglDestroySurface_p(g_EglDisplay, bundle->surface)) {
            LOGW("Could not destroy old EGL surface: %04x", eglGetError_p());
            return false; /* Retain both old and pending ownership; retry later. */
        }
        bundle->surface = EGL_NO_SURFACE;
    }
    if (bundle->nativeSurface != NULL) ANativeWindow_release(bundle->nativeSurface);
    bundle->nativeSurface = NULL;
    if(bundle->newNativeSurface != NULL) {
        LOGI("Switching to new native surface");
        bundle->nativeSurface = bundle->newNativeSurface;
        bundle->newNativeSurface = NULL;
        /* Transfer the reference acquired by the publication snapshot. */
        ANativeWindow_setBuffersGeometry(bundle->nativeSurface, 0, 0, bundle->format);
        bundle->surface = eglCreateWindowSurface_p(g_EglDisplay, bundle->config, bundle->nativeSurface, NULL);
    }else{
        LOGI("No new native surface, switching to 1x1 pbuffer");
        bundle->nativeSurface = NULL;
        const EGLint pbuffer_attrs[] = {EGL_WIDTH, 1 , EGL_HEIGHT, 1, EGL_NONE};
        bundle->surface = eglCreatePbufferSurface_p(g_EglDisplay, bundle->config, pbuffer_attrs);
    }
    //eglMakeCurrent_p(g_EglDisplay, bundle->surface, bundle->surface, bundle->context);
    return bundle->surface != EGL_NO_SURFACE;
}

void gl_make_current(gl_render_window_t* bundle) {

    if(bundle == NULL) {
        if(eglMakeCurrent_p(g_EglDisplay, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT)) {
            currentBundle = NULL;
        }
        return;
    }
    bool hasSetMainWindow = false;
    if(pojav_environ->mainWindowBundle == NULL) {
        pojav_environ->mainWindowBundle = (basic_render_window_t*)bundle;
        LOGI("Main window bundle is now %p", pojav_environ->mainWindowBundle);
        bundle->newNativeSurface = pojav_acquire_window(&bundle->window_generation);
        hasSetMainWindow = true;
    }
    LOGI("Making current, surface=%p, nativeSurface=%p, newNativeSurface=%p", bundle->surface, bundle->nativeSurface, bundle->newNativeSurface);
    if(bundle->surface == NULL) { //it likely will be on the first run
        gl_swap_surface(bundle);
    }
    if(eglMakeCurrent_p(g_EglDisplay, bundle->surface, bundle->surface, bundle->context)) {
        currentBundle = bundle;
        gl_apply_swap_interval();
    }else {
        if(hasSetMainWindow) {
            if (bundle->newNativeSurface != NULL) ANativeWindow_release(bundle->newNativeSurface);
            pojav_environ->mainWindowBundle->newNativeSurface = NULL;
            gl_swap_surface((gl_render_window_t*)pojav_environ->mainWindowBundle);
            pojav_environ->mainWindowBundle = NULL;
        }
        LOGE("eglMakeCurrent returned with error: %04x", eglGetError_p());
    }

}

bool gl_swap_buffers() {
    if(currentBundle == NULL) return false;
    if ((basic_render_window_t*)currentBundle == pojav_environ->mainWindowBundle) gl_setup_window();
    if(currentBundle->state == STATE_RENDERER_NEW_WINDOW) {
        if (!eglMakeCurrent_p(g_EglDisplay, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT)) return false;
        bool replaced = gl_swap_surface(currentBundle);
        if (!eglMakeCurrent_p(g_EglDisplay, currentBundle->surface, currentBundle->surface, currentBundle->context)) return false;
        if (!replaced) return false;
        currentBundle->state = STATE_RENDERER_ALIVE;
        gl_apply_swap_interval();
    }
    if(currentBundle->surface == NULL) return false;
    EGLBoolean swapped = eglSwapBuffers_p(g_EglDisplay, currentBundle->surface);
    if(!swapped && eglGetError_p() == EGL_BAD_SURFACE) {
            if (!eglMakeCurrent_p(g_EglDisplay, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT)) return false;
            if (currentBundle->newNativeSurface != NULL) ANativeWindow_release(currentBundle->newNativeSurface);
            currentBundle->newNativeSurface = NULL;
            if (!gl_swap_surface(currentBundle)) currentBundle->state = STATE_RENDERER_NEW_WINDOW;
            eglMakeCurrent_p(g_EglDisplay, currentBundle->surface, currentBundle->surface, currentBundle->context);
            gl_apply_swap_interval();
            LOGI("The window has died, awaiting window change");
    }
    /* EGL success means queued/submitted, not on-screen. Pbuffers don't count. */
    return swapped == EGL_TRUE && currentBundle->nativeSurface != NULL;
}

void gl_setup_window() {
    if(pojav_environ->mainWindowBundle != NULL) {
        basic_render_window_t *bundle = pojav_environ->mainWindowBundle;
        struct ANativeWindow *replacement = NULL;
        if (pojav_take_window_update(&bundle->window_generation, &replacement)) {
            if (bundle->newNativeSurface != NULL) ANativeWindow_release(bundle->newNativeSurface);
            bundle->newNativeSurface = replacement;
            bundle->state = STATE_RENDERER_NEW_WINDOW;
        }
    }
}

bool gl_destroy_context(gl_render_window_t* bundle) {
    if (bundle == NULL || bundle->bound_threads != 0) return false;
    if (bundle->surface != EGL_NO_SURFACE) {
        if (!eglDestroySurface_p(g_EglDisplay, bundle->surface)) return false;
        bundle->surface = EGL_NO_SURFACE;
    }
    if (bundle->context != EGL_NO_CONTEXT) {
        if (!eglDestroyContext_p(g_EglDisplay, bundle->context)) return false;
        bundle->context = EGL_NO_CONTEXT;
    }
    if (bundle->nativeSurface != NULL) ANativeWindow_release(bundle->nativeSurface);
    if (bundle->newNativeSurface != NULL) ANativeWindow_release(bundle->newNativeSurface);
    free(bundle);
    return true;
}

bool gl_terminate(void) {
    if (g_EglDisplay != EGL_NO_DISPLAY) {
        if (eglTerminate_p == NULL || !eglTerminate_p(g_EglDisplay)) return false;
        g_EglDisplay = EGL_NO_DISPLAY;
    }
    if (eglReleaseThread_p != NULL) eglReleaseThread_p();
    return true;
}

// Its not in a .h file because it is shared with osm_bridge.c (same pattern).
extern void setNativeWindowSwapInterval(struct ANativeWindow* nativeWindow, int swapInterval);

static void gl_apply_swap_interval(void) {
    if(currentBundle == NULL || currentBundle->surface == EGL_NO_SURFACE) return;
    int interval = pojav_effective_swap_interval(requested_swap_interval, pojav_environ->force_vsync);
    /* EGL legally clamps to the config's minimum/maximum. A successful call
     * does not guarantee interval zero or render FPS above display refresh. */
    EGLint minimum = -1, maximum = -1;
    eglGetConfigAttrib_p(g_EglDisplay, currentBundle->config, EGL_MIN_SWAP_INTERVAL, &minimum);
    eglGetConfigAttrib_p(g_EglDisplay, currentBundle->config, EGL_MAX_SWAP_INTERVAL, &maximum);
    if(!eglSwapInterval_p(g_EglDisplay, interval)) {
        LOGW("eglSwapInterval(%d) failed: %04x", interval, eglGetError_p());
    } else {
        LOGI("Requested EGL swap interval %d (config range %d..%d); presentation remains display-limited",
             interval, minimum, maximum);
    }
    if(pojav_private_swap_interval_enabled() && currentBundle->nativeSurface != NULL) {
        setNativeWindowSwapInterval(currentBundle->nativeSurface, interval);
    }
}

void gl_swap_interval(int swapInterval) {
    requested_swap_interval = swapInterval;
    gl_apply_swap_interval();
}

JNIEXPORT void JNICALL
Java_org_lwjgl_opengl_PojavRendererInit_nativeInitGl4esInternals(JNIEnv *env, jclass clazz,
                                                            jobject function_provider) {
    LOGI("GL4ES internals initializing...");
    jclass funcProviderClass = (*env)->GetObjectClass(env, function_provider);
    jmethodID method_getFunctionAddress = (*env)->GetMethodID(env, funcProviderClass, "getFunctionAddress", "(Ljava/lang/CharSequence;)J");
#define GETSYM(N) ((*env)->CallLongMethod(env, function_provider, method_getFunctionAddress, (*env)->NewStringUTF(env, N)));

    void (*set_getmainfbsize)(void (*new_getMainFBSize)(int* width, int* height)) = (void*)GETSYM("set_getmainfbsize");
    if(set_getmainfbsize != NULL) {
        LOGI("GL4ES internals initialized dimension callback");
        set_getmainfbsize(gl4esi_get_display_dimensions);
    }

#undef GETSYM
}
