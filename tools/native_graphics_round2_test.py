#!/usr/bin/env python3
"""Execute production native initialization functions with host API doubles.

No reimplemented decision logic: loaders compile in full, bridge selection and
EGL context setup are extracted verbatim with brace matching. These tests do
not execute any Android graphics driver or assert device performance.
"""
import os
import pathlib
import subprocess
import sys
import tempfile

ROOT = pathlib.Path(sys.argv[1]).resolve()
JNI = ROOT / "app_pojavlauncher/src/main/jni"


def function(path, name):
    source = path.read_text()
    start = source.index(name + "(")
    start = source.rfind("\n", 0, start) + 1
    brace = source.index("{", start)
    depth = 1
    end = brace + 1
    while depth:
        depth += (source[end] == "{") - (source[end] == "}")
        end += 1
    return source[start:end] + "\n"


EGL_HEADER = r'''
#ifndef ROUND2_EGL_H
#define ROUND2_EGL_H
#include <stdint.h>
typedef void *EGLDisplay, *EGLSurface, *EGLContext, *EGLConfig;
typedef void *NativeDisplayType, *NativeWindowType;
typedef int EGLint;
typedef unsigned int EGLBoolean, EGLenum;
typedef void (*__eglMustCastToProperFunctionPointerType)(void);
#define EGL_NO_DISPLAY ((EGLDisplay)0)
#define EGL_NO_SURFACE ((EGLSurface)0)
#define EGL_NO_CONTEXT ((EGLContext)0)
#define EGL_DEFAULT_DISPLAY ((NativeDisplayType)0)
#define EGL_TRUE 1
#define EGL_FALSE 0
#define EGL_NONE 0x3038
#define EGL_BLUE_SIZE 0x3022
#define EGL_GREEN_SIZE 0x3023
#define EGL_RED_SIZE 0x3024
#define EGL_ALPHA_SIZE 0x3021
#define EGL_DEPTH_SIZE 0x3025
#define EGL_SURFACE_TYPE 0x3033
#define EGL_WINDOW_BIT 4
#define EGL_PBUFFER_BIT 1
#define EGL_RENDERABLE_TYPE 0x3040
#define EGL_OPENGL_ES2_BIT 4
#define EGL_NATIVE_VISUAL_ID 0x302e
#define EGL_OPENGL_API 0x30a2
#define EGL_OPENGL_ES_API 0x30a0
#define EGL_CONTEXT_CLIENT_VERSION 0x3098
#endif
'''

KHR_HEADER = r'''
/* Minimal host-only ABI types needed by the repository's GL headers. */
#include <stdint.h>
#include <stddef.h>
typedef int32_t khronos_int32_t;
typedef int64_t khronos_int64_t;
typedef intptr_t khronos_intptr_t;
typedef ptrdiff_t khronos_ssize_t;
typedef uint16_t khronos_uint16_t;
typedef uint64_t khronos_uint64_t;
'''

COMMON = r'''
#define _POSIX_C_SOURCE 200809L
#include <assert.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <dlfcn.h>
#include <pthread.h>
#define EXTERNAL_API
'''

LOADER_PREFIX = COMMON + r'''
static int resolver_mode, closes, opens, direct_lookups, resolver_lookups;
static int fail_open, fail_cutils;
static const char *missing;
static void dummy(void) {}
static void *resolver(const char *name) {
    resolver_lookups++;
    if (resolver_mode || (missing && !strcmp(name, missing))) return NULL;
    return (void *)dummy;
}
static void *fake_dlsym(void *handle, const char *name) {
    assert(handle == (void *)1);
    if (!strcmp(name, "eglGetProcAddress") || !strcmp(name, "OSMesaGetProcAddress"))
        return resolver_mode == 2 ? NULL : (void *)resolver;
    direct_lookups++;
    if (!resolver_mode || (missing && !strcmp(name, missing))) return NULL;
    return (void *)dummy;
}
static int fake_dlclose(void *handle) {
    assert(handle == (void *)1 || handle == (void *)2);
    closes++;
    return 0;
}
static void *fake_loader_dlopen(char *primary, char *secondary, int flags) {
    (void)secondary; (void)flags;
    opens++;
    if (primary && !strcmp(primary, "libcutils.so")) return fail_cutils ? NULL : (void *)2;
    return fail_open ? NULL : (void *)1;
}
#define dlsym fake_dlsym
#define dlclose fake_dlclose
#define loader_dlopen fake_loader_dlopen
'''


def loader_test(kind):
    egl = kind == "EGL"
    path = JNI / "ctxbridges" / ("egl_loader.c" if egl else "osmesa_loader.c")
    symbols = "EGL_BRIDGE_SYMBOLS" if egl else "OSMESA_BRIDGE_SYMBOLS"
    resolver = "eglGetProcAddress_p" if egl else "OSMesaGetProcAddress_p"
    suffix = r'''
#define CHECK_SET(N) assert(N##_p != NULL);
#define CHECK_CLEAR(N) assert(N##_p == NULL);
#define NAME(N) #N,
static const char *names[] = { SYMBOLS(NAME) };
static void reset(void) {
    closes=opens=direct_lookups=resolver_lookups=0;
    missing=NULL; fail_open=fail_cutils=0;
}
int main(void) {
    for (resolver_mode=0; resolver_mode<3; resolver_mode++) {
        reset(); assert(dlsym_KIND()); assert(!closes);
        SYMBOLS(CHECK_SET)
        assert(resolver_mode == 2 ? RESOLVER == NULL : RESOLVER != NULL);
        assert(resolver_mode == 0 ? direct_lookups == 0 : direct_lookups == (int)(sizeof(names)/sizeof(names[0])));
        for (size_t i=0; i<sizeof(names)/sizeof(names[0]); i++) {
            reset(); missing=names[i];
            assert(!dlsym_KIND()); assert(closes==1);
            SYMBOLS(CHECK_CLEAR)
            assert(RESOLVER == NULL);
        }
    }
    reset(); fail_open=1; assert(!dlsym_KIND()); assert(!closes);
    SYMBOLS(CHECK_CLEAR)
    EXTRA
    printf("PASS %s loader: 3 resolution modes, each required symbol missing, open failure%s\n", "KIND", SUFFIX);
}
'''
    extra = ""
    if egl:
        extra = r'''
    assert(setenv("POJAVEXEC_EGL", "libEGL_mesa.so", 1)==0);
    reset(); resolver_mode=0; assert(dlsym_EGL()); assert(opens==2 && !closes);
    reset(); missing=names[0]; assert(!dlsym_EGL()); assert(closes==2);
    reset(); fail_open=1; assert(!dlsym_EGL()); assert(closes==1);
    reset(); fail_cutils=1; assert(!dlsym_EGL()); assert(opens==1 && !closes);
'''
    return (LOADER_PREFIX + '#include "' + str(path) + '"\n' +
            suffix.replace("SYMBOLS", symbols).replace("RESOLVER", resolver)
            .replace("KIND", kind).replace("EXTRA", extra)
            .replace("SUFFIX", '"; Kopper dependency cleanup"' if egl else '""'))


BRIDGE_PREFIX = COMMON + r'''
#include <EGL/egl.h>
enum { RENDERER_GL4ES=1, RENDERER_VK_ZINK=2, RENDERER_VULKAN=4,
       GLFW_CLIENT_API=0x22001, GLFW_NO_API=0, GLFW_OPENGL_API=0x30001,
       AHARDWAREBUFFER_FORMAT_R8G8B8X8_UNORM=2 };
typedef struct basic_window { struct basic_window *next; unsigned int bound_threads; } basic_render_window_t;
static basic_render_window_t window;
struct state { int force_vsync, config_renderer, savedWidth, savedHeight;
    void *glfwThreadVmEnv, *runtimeJavaVMPtr, *pojavWindow; } state;
static struct state *pojav_environ=&state;
static bool (*br_init)(void);
static void (*br_setup_window)(void), (*br_swap_interval)(int);
static void (*br_make_current)(basic_render_window_t*);
static basic_render_window_t *(*br_init_context)(basic_render_window_t*);
static basic_render_window_t *(*br_get_current)(void);
static bool (*br_swap_buffers)(void);
static int coverage, inits, setups, vulkan_loads, vulkan_fail, incomplete, init_fail, dispatches;
static uint32_t native_counter_mode;
static pthread_mutex_t bridge_lifecycle_mutex = PTHREAD_MUTEX_INITIALIZER;
static basic_render_window_t *live_contexts;
static int bridge_backend;
static bool main_context_available;
static int acquires, releases, attach_fail;
static void pojav_frame_set_coverage(int value) { coverage=value; }
static bool init(void) { inits++; return !init_fail; }
static void setup(void) { setups++; }
static void interval(int value) { (void)value; dispatches++; }
static void make(basic_render_window_t *value) { (void)value; dispatches++; }
static basic_render_window_t *create(basic_render_window_t *value) { (void)value; dispatches++; return &window; }
static basic_render_window_t *current(void) { dispatches++; return &window; }
static bool swap(void) { dispatches++; return true; }
static void set_gl_bridge_tbl(void) {
    br_init=init; br_setup_window=setup; br_swap_interval=interval;
    br_make_current=make; br_init_context=incomplete?NULL:create;
    br_get_current=current; br_swap_buffers=swap;
}
static void set_osm_bridge_tbl(void) { set_gl_bridge_tbl(); }
static void *maybe_load_vulkan(void) { vulkan_loads++; return vulkan_fail?NULL:(void *)1; }
static void *get_attached_env(void *vm) { (void)vm; return attach_fail?NULL:(void *)1; }
static void ANativeWindow_acquire(void *w) { assert(w); acquires++; }
static void ANativeWindow_release(void *w) { assert(w); releases++; }
static void *pojav_acquire_window(void *generation) { (void)generation; if(state.pojavWindow)ANativeWindow_acquire(state.pojavWindow); return state.pojavWindow; }
static void *retain_vulkan_window(void) { return state.pojavWindow; }
static void track_current_change(basic_render_window_t *before,basic_render_window_t *after) { (void)before; (void)after; }
static int ANativeWindow_getWidth(void *w) { assert(w); return 800; }
static int ANativeWindow_getHeight(void *w) { assert(w); return 600; }
static int ANativeWindow_setBuffersGeometry(void *w, int x, int y, int f) { assert(w); (void)x; (void)y; (void)f; return 0; }
static void updateMonitorSize(int x, int y) { assert(x==800&&y==600); }
'''

BRIDGE_SUFFIX = r'''
static void reset(const char *renderer) {
    live_contexts=NULL; bridge_backend=0; main_context_available=false;
    memset(&state,0,sizeof(state)); state.pojavWindow=(void *)1;
    inits=setups=vulkan_loads=vulkan_fail=incomplete=init_fail=dispatches=acquires=releases=attach_fail=0;
    coverage=1;
    /* Deliberately stale table: rejected init must clear every entry. */
    set_gl_bridge_tbl();
    if(renderer) assert(setenv("AMETHYST_RENDERER",renderer,1)==0);
    else assert(unsetenv("AMETHYST_RENDERER")==0);
}
static void assert_failed(void) {
    assert(!br_init&&!br_setup_window&&!br_init_context&&!br_make_current);
    assert(!br_get_current&&!br_swap_buffers&&!br_swap_interval);
    assert(!coverage && !state.config_renderer);
    assert(pojavGetCurrentContext()==NULL);
    pojavMakeCurrent(NULL); pojavSwapInterval(0); assert(!dispatches);
}
int main(void) {
    reset(NULL); assert(!pojavInitOpenGL()); assert_failed();
    pojavSetWindowHint(GLFW_CLIENT_API,GLFW_OPENGL_API); assert(!state.config_renderer);
    reset("gallium_panfrost"); assert(!pojavInitOpenGL()); assert_failed();
    reset("gallium_freedreno"); assert(!pojavInitOpenGL()); assert_failed();
    reset("opengles_mobileglues"); incomplete=1;
    assert(!pojavInitOpenGL()); assert_failed(); assert(!inits&&!setups);
    reset("opengles_mobileglues"); init_fail=1;
    assert(!pojavInitOpenGL()); assert_failed(); assert(inits==1&&!setups);
    reset("opengles_mobileglues"); assert(pojavInitOpenGL());
    assert(inits==1&&setups==1&&!vulkan_loads);
    assert(pojavGetCurrentContext()==&window); assert(pojavCreateContext(NULL)==&window);
    pojavMakeCurrent(&window); pojavSwapInterval(0); assert(dispatches==6);
    reset("opengles3_desktopgl_zink_kopper"); assert(pojavInitOpenGL()); assert(vulkan_loads==1);
    reset("vulkan_zink"); assert(pojavInitOpenGL()); assert(vulkan_loads==1);
    reset("vulkan_zink"); vulkan_fail=1;
    assert(!pojavInitOpenGL()); assert_failed(); assert(vulkan_loads==1&&!inits&&!setups);
    reset("opengles3_desktopgl_zink_kopper"); vulkan_fail=1;
    assert(!pojavInitOpenGL()); assert_failed(); assert(vulkan_loads==1&&!inits&&!setups);
    /* Real GLFW consumer ordering: never send hints/create after failed Init.
     * A broken unused GL backend must not block platform init or NO_API. */
    reset("opengles_mobileglues"); init_fail=1;
    assert(pojavInit()); assert(acquires==1&&releases==1&&!inits&&!setups&&!dispatches);
    assert_failed();
    pojavSetWindowHint(GLFW_CLIENT_API,GLFW_NO_API);
    assert(pojavCreateContext(NULL)==state.pojavWindow);
    assert(!inits&&!setups&&!dispatches&&!coverage);
    /* Requesting GL after the same successful platform init does initialize
     * the backend and rejects the context on failure, without stale dispatch. */
    pojavSetWindowHint(GLFW_CLIENT_API,GLFW_OPENGL_API);
    assert(pojavCreateContext(NULL)==NULL); assert(inits==1&&!setups);
    assert_failed(); assert(acquires==1&&releases==1);
    reset(NULL); assert(pojavInit());
    pojavSetWindowHint(GLFW_CLIENT_API,GLFW_NO_API);
    assert(pojavCreateContext(NULL)==state.pojavWindow&&!inits&&!dispatches);
    pojavSetWindowHint(GLFW_CLIENT_API,GLFW_OPENGL_API);
    assert(pojavCreateContext(NULL)==NULL); assert_failed();
    reset("gallium_panfrost"); assert(pojavInit());
    assert(pojavCreateContext(NULL)==NULL); assert_failed();
    /* The default GLFW API is GL even when no explicit window hint is sent.
     * Init is platform-only; first context lazily initializes, later shared
     * contexts reuse the loaded table. */
    reset("opengles_mobileglues"); assert(pojavInit()); assert(acquires==1&&releases==1&&!inits);
    assert(pojavCreateContext(NULL)==&window); assert(inits==1&&setups==1&&dispatches==1);
    assert(pojavCreateContext(&window)==&window); assert(inits==1&&setups==1&&dispatches==2);
    reset("opengles3_desktopgl_zink_kopper"); assert(pojavInit()); assert(!vulkan_loads&&!inits);
    assert(pojavCreateContext(NULL)==&window); assert(vulkan_loads==1&&inits==1&&setups==1);
    reset("vulkan_zink"); assert(pojavInit()); assert(!vulkan_loads&&!inits);
    assert(pojavCreateContext(NULL)==&window); assert(vulkan_loads==1&&inits==1&&setups==1);
    reset("opengles_mobileglues"); state.pojavWindow=NULL;
    assert(!pojavInit()); assert(!acquires&&!inits);
    reset("opengles_mobileglues"); attach_fail=1; assert(!pojavInit()); assert(!acquires&&!inits);
    puts("PASS platform init -> NO_API hint -> native window with broken/missing GL; GL request fails closed, lazy/default/shared context setup and null platform inputs");
}
'''

CONTEXT_PREFIX = COMMON + r'''
#include <EGL/egl.h>
#include "ctxbridges/gl_bridge.h"
#include "ctxbridges/egl_loader.h"
#define LOGE(...) do {} while(0)
static EGLDisplay g_EglDisplay=(void *)1;
static int choose_calls, attrib_calls, bind_calls, create_calls, allocs, frees;
static int failure, seen_version;
static int loader_fail, display_fail, initialize_fail, coverage;
static EGLenum seen_api;
static EGLContext seen_share;
static void *test_calloc(size_t n, size_t s) { allocs++; return failure==1?NULL:calloc(n,s); }
static void test_free(void *p) { assert(p); frees++; free(p); }
static EGLBoolean choose(EGLDisplay d, const EGLint *a, EGLConfig *c, EGLint n, EGLint *count) {
    assert(d && a && c && n==1); choose_calls++;
    *count=failure==3?0:1; *c=failure==4?NULL:(void *)2;
    return failure==2?EGL_FALSE:EGL_TRUE;
}
static EGLBoolean attrib(EGLDisplay d, EGLConfig c, EGLint a, EGLint *v) {
    assert(d&&c&&a==EGL_NATIVE_VISUAL_ID); attrib_calls++; *v=1;
    return failure==5?EGL_FALSE:EGL_TRUE;
}
static EGLBoolean bind(EGLenum a) { seen_api=a; bind_calls++; return failure==6?EGL_FALSE:EGL_TRUE; }
static EGLContext create_ctx(EGLDisplay d, EGLConfig c, EGLContext share, const EGLint *a) {
    assert(d&&c&&a[0]==EGL_CONTEXT_CLIENT_VERSION&&a[2]==EGL_NONE);
    create_calls++; seen_version=a[1]; seen_share=share;
    return failure==7?EGL_NO_CONTEXT:(void *)3;
}
static EGLint error(void) { return 0; }
bool dlsym_EGL(void) { return !loader_fail; }
static void pojav_frame_set_coverage(int value) { coverage=value; }
static EGLDisplay get_display(NativeDisplayType d) { (void)d; return display_fail?EGL_NO_DISPLAY:(void *)1; }
static EGLBoolean initialize(EGLDisplay d, EGLint *major, EGLint *minor) {
    assert(d); (void)major; (void)minor;
    return initialize_fail?EGL_FALSE:EGL_TRUE;
}
EGLDisplay (*eglGetDisplay_p)(NativeDisplayType)=get_display;
EGLBoolean (*eglInitialize_p)(EGLDisplay,EGLint*,EGLint*)=initialize;
EGLBoolean (*eglChooseConfig_p)(EGLDisplay,const EGLint*,EGLConfig*,EGLint,EGLint*)=choose;
EGLBoolean (*eglGetConfigAttrib_p)(EGLDisplay,EGLConfig,EGLint,EGLint*)=attrib;
EGLBoolean (*eglBindAPI_p)(EGLenum)=bind;
EGLContext (*eglCreateContext_p)(EGLDisplay,EGLConfig,EGLContext,const EGLint*)=create_ctx;
EGLint (*eglGetError_p)(void)=error;
#define calloc test_calloc
#define free test_free
'''

CONTEXT_SUFFIX = r'''
#undef calloc
#undef free
static void reset(void) {
    choose_calls=attrib_calls=bind_calls=create_calls=allocs=frees=failure=seen_version=0;
    loader_fail=display_fail=initialize_fail=0; coverage=1;
    g_EglDisplay=(void *)1;
}
int main(void) {
    unsetenv("AMETHYST_RENDERER"); unsetenv("LIBGL_ES");
    reset(); gl_render_window_t *b=gl_init_context(NULL);
    assert(b&&seen_version==2&&seen_api==EGL_OPENGL_ES_API&&seen_share==EGL_NO_CONTEXT);
    assert(choose_calls==1&&attrib_calls==1&&bind_calls==1&&create_calls==1); free(b);
    reset(); g_EglDisplay=EGL_NO_DISPLAY; assert(!gl_init_context(NULL)); assert(!allocs&&!choose_calls);
    for(int f=1; f<=7; f++) {
        reset(); failure=f; assert(!gl_init_context(NULL));
        assert(allocs==1&&frees==(f==1?0:1)); assert(choose_calls==(f==1?0:1));
        assert(create_calls==(f==7?1:0));
    }
    const char *invalid[]={"", "abc", "0", "-1", "32768", "999999999999999999999999", "3x"};
    for(size_t i=0;i<sizeof(invalid)/sizeof(invalid[0]);i++) {
        reset(); setenv("LIBGL_ES",invalid[i],1); b=gl_init_context(NULL); assert(b&&seen_version==2); free(b);
    }
    gl_render_window_t shared={0}; shared.context=(void *)4;
    reset(); setenv("LIBGL_ES","3",1); setenv("AMETHYST_RENDERER","opengles3_desktopgl_zink_kopper",1);
    b=gl_init_context(&shared); assert(b&&seen_version==3&&seen_api==EGL_OPENGL_API&&seen_share==shared.context); free(b);
    reset(); loader_fail=1; assert(!gl_init()); assert(!coverage);
    reset(); display_fail=1; assert(!gl_init()); assert(!coverage&&g_EglDisplay==EGL_NO_DISPLAY);
    reset(); initialize_fail=1; assert(!gl_init()); assert(!coverage&&g_EglDisplay==EGL_NO_DISPLAY);
    reset(); assert(gl_init()); assert(coverage==0&&g_EglDisplay==(void *)1);
    puts("PASS EGL initialization loader/display/init failures do not advertise instrumented coverage");
    puts("PASS EGL context: one config query, 8 setup/allocation failure cases, null/malformed version defaults, desktop and shared paths");
}
'''


def run():
    with tempfile.TemporaryDirectory(prefix="native-graphics-round2-") as directory:
        out = pathlib.Path(directory)
        (out / "EGL").mkdir()
        (out / "EGL/egl.h").write_text(EGL_HEADER)
        (out / "KHR").mkdir()
        (out / "KHR/khrplatform.h").write_text(KHR_HEADER)
        bridge = JNI / "egl_bridge.c"
        bridge_code = BRIDGE_PREFIX + function(JNI / "ctxbridges/bridge_tbl.h", "clear_bridge_tbl")
        for name in ["pojavInitOpenGL", "pojavInit", "pojavGetCurrentContext", "pojavMakeCurrent",
                     "pojavCreateContext", "pojavSetWindowHint", "pojavSwapInterval"]:
            bridge_code += function(bridge, name)
        fixtures = {"egl-loader": loader_test("EGL"), "osmesa-loader": loader_test("OSMesa"),
                    "bridge": bridge_code + BRIDGE_SUFFIX,
                    "context": CONTEXT_PREFIX + function(JNI / "ctxbridges/gl_bridge.c", "gl_init") +
                    function(JNI / "ctxbridges/gl_bridge.c", "gl_init_context") + CONTEXT_SUFFIX}
        for name, source in fixtures.items():
            c = out / (name + ".c")
            c.write_text(source)
            binary = out / name
            subprocess.run([os.environ.get("CC", "cc"), "-std=c11", "-O2", "-Wall", "-Wextra", "-Werror",
                            "-I" + str(out), "-I" + str(JNI), str(c), "-o", str(binary)], check=True)
            subprocess.run([str(binary)], check=True)


if __name__ == "__main__":
    run()
