//
// Created by maks on 21.09.2022.
//
#include <stddef.h>
#include <stdlib.h>
#include <dlfcn.h>
#include <string.h>
#include <stdio.h>
#include "egl_loader.h"
#include "loader_dlopen.h"

EGLBoolean (*eglMakeCurrent_p) (EGLDisplay dpy, EGLSurface draw, EGLSurface read, EGLContext ctx);
EGLBoolean (*eglDestroyContext_p) (EGLDisplay dpy, EGLContext ctx);
EGLBoolean (*eglDestroySurface_p) (EGLDisplay dpy, EGLSurface surface);
EGLBoolean (*eglTerminate_p) (EGLDisplay dpy);
EGLBoolean (*eglReleaseThread_p) (void);
EGLContext (*eglGetCurrentContext_p) (void);
EGLDisplay (*eglGetDisplay_p) (NativeDisplayType display);
EGLBoolean (*eglInitialize_p) (EGLDisplay dpy, EGLint *major, EGLint *minor);
EGLBoolean (*eglChooseConfig_p) (EGLDisplay dpy, const EGLint *attrib_list, EGLConfig *configs, EGLint config_size, EGLint *num_config);
EGLBoolean (*eglGetConfigAttrib_p) (EGLDisplay dpy, EGLConfig config, EGLint attribute, EGLint *value);
EGLBoolean (*eglBindAPI_p) (EGLenum api);
EGLSurface (*eglCreatePbufferSurface_p) (EGLDisplay dpy, EGLConfig config, const EGLint *attrib_list);
EGLSurface (*eglCreateWindowSurface_p) (EGLDisplay dpy, EGLConfig config, NativeWindowType window, const EGLint *attrib_list);
EGLBoolean (*eglSwapBuffers_p) (EGLDisplay dpy, EGLSurface draw);
EGLint (*eglGetError_p) (void);
EGLContext (*eglCreateContext_p) (EGLDisplay dpy, EGLConfig config, EGLContext share_list, const EGLint *attrib_list);
EGLBoolean (*eglSwapInterval_p) (EGLDisplay dpy, EGLint interval);
EGLSurface (*eglGetCurrentSurface_p) (EGLint readdraw);
EGLBoolean (*eglQuerySurface_p)( 	EGLDisplay display,
                                           EGLSurface surface,
                                           EGLint attribute,
                                           EGLint * value);
__eglMustCastToProperFunctionPointerType (*eglGetProcAddress_p) (const char *procname);

/* Core EGL entry points are not guaranteed to be returned by
 * eglGetProcAddress on EGL 1.4 implementations. Keep the selected library's
 * resolver first (wrapper compatibility), then try its direct exports. */
#define EGL_BRIDGE_SYMBOLS(X) \
    X(eglBindAPI) X(eglChooseConfig) X(eglCreateContext) \
    X(eglCreatePbufferSurface) X(eglCreateWindowSurface) \
    X(eglDestroyContext) X(eglDestroySurface) X(eglGetConfigAttrib) \
    X(eglGetCurrentContext) X(eglGetDisplay) X(eglGetError) \
    X(eglInitialize) X(eglMakeCurrent) X(eglSwapBuffers) \
    X(eglReleaseThread) X(eglSwapInterval) X(eglTerminate) \
    X(eglGetCurrentSurface) X(eglQuerySurface)

static void clear_egl_symbols(void) {
#define CLEAR(N) N##_p = NULL;
    EGL_BRIDGE_SYMBOLS(CLEAR)
#undef CLEAR
    eglGetProcAddress_p = NULL;
}

static void* egl_bridge_symbol(void* handle, const char* name) {
    void* symbol = eglGetProcAddress_p != NULL ? (void*)eglGetProcAddress_p(name) : NULL;
    return symbol != NULL ? symbol : dlsym(handle, name);
}

bool dlsym_EGL() {
    clear_egl_symbols();
    void* cutils_handle = NULL;
    char* gles = getenv("LIBGL_GLES");
    char* eglName = (strncmp(gles ? gles : "", "libGLESv2_angle.so", 18) == 0) ? "libEGL_angle.so" : getenv("POJAVEXEC_EGL");
    // Kopper needs this
    if (eglName != NULL && strncmp(eglName, "libEGL_mesa.so", 14) == 0) {
        cutils_handle = loader_dlopen("libcutils.so", "libcutils.so", RTLD_GLOBAL|RTLD_NOW);
        if(cutils_handle == NULL) return false;
    }
    void* dl_handle = loader_dlopen(eglName,"libEGL.so", RTLD_LOCAL|RTLD_LAZY);
    if(dl_handle == NULL) {
        if (cutils_handle != NULL) dlclose(cutils_handle);
        return false;
    }
    eglGetProcAddress_p = dlsym(dl_handle, "eglGetProcAddress");
#define LOAD(N) \
    N##_p = egl_bridge_symbol(dl_handle, #N); \
    if (N##_p == NULL) { \
        fprintf(stderr, "EGLBridge: missing required symbol %s\n", #N); \
        goto fail; \
    }
    EGL_BRIDGE_SYMBOLS(LOAD)
#undef LOAD
    /* Successful libraries remain loaded for the lifetime of their pointers. */
    return true;

fail:
    clear_egl_symbols();
    dlclose(dl_handle);
    if (cutils_handle != NULL) dlclose(cutils_handle);
    return false;
}
